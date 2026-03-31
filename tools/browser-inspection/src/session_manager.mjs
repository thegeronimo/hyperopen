import fs from "node:fs/promises";
import { assertSessionState } from "./contracts.mjs";
import { ArtifactStore } from "./artifact_store.mjs";
import { CDPClient, getBrowserWsUrl } from "./cdp_client.mjs";
import { killProcess, launchChrome, processIsAlive } from "./chrome_launcher.mjs";
import { classifyErrorMessage, remediationForClassification } from "./failure_classification.mjs";
import { maybeStartLocalApp, maybeStopLocalApp } from "./local_app_manager.mjs";
import { resolveManagedLocalUrl } from "./local_origin.mjs";
import { runPreflightChecks } from "./preflight.mjs";
import { safeNowIso, sleep } from "./util.mjs";

function validateUrlForReadOnly(config, url) {
  const blockedSchemes = config.readOnly?.blockedSchemes || [];
  if (blockedSchemes.some((scheme) => url.startsWith(scheme))) {
    throw new Error(`Blocked URL scheme in read-only mode: ${url}`);
  }
}

function validateEvalForReadOnly(config, expression, allowUnsafeEval = false) {
  if (allowUnsafeEval) {
    return;
  }
  const patterns = config.readOnly?.blockedEvalPatterns || [];
  for (const source of patterns) {
    const regex = new RegExp(source, "i");
    if (regex.test(expression)) {
      throw new Error(`Blocked eval expression in read-only mode by pattern: ${source}`);
    }
  }
}

async function openClientForSession(session) {
  const wsUrl = await getBrowserWsUrl({
    host: session.chrome.host || "127.0.0.1",
    port: session.chrome.port
  });
  const client = new CDPClient(wsUrl);
  await client.connect();
  return client;
}

async function waitForProcessExit(pid, timeoutMs = 5000, pollMs = 100) {
  if (!pid || typeof pid !== "number") {
    return false;
  }

  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (!processIsAlive(pid)) {
      return true;
    }
    await sleep(pollMs);
  }

  return !processIsAlive(pid);
}

function normalizeAttachEndpoint(options = {}) {
  const hasAttachPort = options.attachPort !== undefined && options.attachPort !== null;
  if (!hasAttachPort) {
    return null;
  }
  const port = Number(options.attachPort);
  if (!Number.isInteger(port) || port <= 0) {
    throw new Error(`Invalid attach port: ${options.attachPort}`);
  }
  return {
    host: options.attachHost || "127.0.0.1",
    port
  };
}

function normalizeTargetInfo(targetInfo) {
  return {
    targetId: targetInfo.targetId,
    type: targetInfo.type,
    title: targetInfo.title || "",
    url: targetInfo.url || "",
    attached: Boolean(targetInfo.attached),
    openerId: targetInfo.openerId || null,
    browserContextId: targetInfo.browserContextId || null
  };
}

function withActionableStartupError(error, phaseLabel) {
  const classification = classifyErrorMessage(error?.message || error);
  if (!classification) {
    return error;
  }
  const remediation = remediationForClassification(classification);
  const detail = remediation ? ` Remediation: ${remediation}` : "";
  const wrapped = new Error(`${phaseLabel} failed: ${classification.summary}${detail}`, { cause: error });
  wrapped.classification = classification;
  return wrapped;
}


function parseUrlOrNull(rawUrl) {
  try {
    return new URL(rawUrl);
  } catch (_error) {
    return null;
  }
}

function normalizedPort(url) {
  if (url.port) {
    return url.port;
  }
  if (url.protocol === "https:") {
    return "443";
  }
  if (url.protocol === "http:") {
    return "80";
  }
  return "";
}

function normalizeHostname(hostname) {
  if (hostname === "localhost" || hostname === "127.0.0.1" || hostname === "::1") {
    return "loopback";
  }
  return hostname;
}

function originKeyForUrl(url) {
  return `${url.protocol}//${normalizeHostname(url.hostname)}:${normalizedPort(url)}`;
}

function localBootstrapOriginKeys(session, bootstrapUrl) {
  return new Set(
    [
      session?.localApp?.requestedUrl,
      session?.localApp?.url,
      ...(session?.localApp?.candidateUrls || []),
      bootstrapUrl
    ]
      .map(parseUrlOrNull)
      .filter(Boolean)
      .map(originKeyForUrl)
  );
}

function localBootstrapNavigationDetails(url, bootstrapUrl, session) {
  const target = parseUrlOrNull(url);
  const bootstrap = parseUrlOrNull(bootstrapUrl);
  if (!target || !bootstrap) {
    return null;
  }
  const localOrigins = localBootstrapOriginKeys(session, bootstrapUrl);
  if (!localOrigins.has(originKeyForUrl(target)) || !localOrigins.has(originKeyForUrl(bootstrap))) {
    return null;
  }
  const bootstrapUrlWithSearch = new URL(bootstrap.toString());
  bootstrapUrlWithSearch.search = target.search;
  bootstrapUrlWithSearch.hash = target.hash;
  return {
    bootstrapUrl: bootstrapUrlWithSearch.toString(),
    routePath: `${target.pathname}${target.search}${target.hash}`
  };
}

function bootstrapCandidates(session, options = {}) {
  const candidates = [
    options.bootstrapUrl,
    session?.localApp?.url,
    ...(session?.localApp?.candidateUrls || [])
  ].filter(Boolean);
  return [...new Set(candidates)];
}

function isDebugBridgeTimeout(error) {
  return (error?.message || String(error || "")).includes(
    "Timed out waiting for HYPEROPEN_DEBUG to initialize."
  );
}

async function waitForDebugBridge(attached, timeoutMs = 15000, pollMs = 50) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const result = await attached.client.send(
        "Runtime.evaluate",
        {
          expression: `Boolean(globalThis.HYPEROPEN_DEBUG &&
                             typeof globalThis.HYPEROPEN_DEBUG.dispatch === "function" &&
                             typeof globalThis.HYPEROPEN_DEBUG.waitForIdle === "function")`,
          returnByValue: true
        },
        attached.cdpSessionId,
        Math.min(timeoutMs, 1500)
      );
      if (result?.result?.value === true) {
        return true;
      }
    } catch (_error) {
      // keep polling until the bridge is available
    }
    await sleep(pollMs);
  }
  throw new Error("Timed out waiting for HYPEROPEN_DEBUG to initialize.");
}

async function navigatePage(attached, url, timeoutMs) {
  const loadEvent = attached.client.waitForEvent("Page.loadEventFired", {
    sessionId: attached.cdpSessionId,
    timeoutMs
  });

  await attached.client.send("Page.navigate", { url }, attached.cdpSessionId);
  await loadEvent;
}

async function waitForDebugIdle(attached, options = {}) {
  const idleOptions = {
    quietMs: options.quietMs || 200,
    timeoutMs: options.timeoutMs || 6000,
    pollMs: options.pollMs || 50
  };
  return attached.client.send(
    "Runtime.evaluate",
    {
      expression: `(async () => {
        const api = globalThis.HYPEROPEN_DEBUG;
        if (!api || typeof api.waitForIdle !== "function") {
          return null;
        }
        return await api.waitForIdle(${JSON.stringify(idleOptions)});
      })()`,
      returnByValue: true,
      awaitPromise: true
    },
    attached.cdpSessionId,
    idleOptions.timeoutMs
  );
}

async function dispatchNavigationAction(attached, routePath, timeoutMs = 15000) {
  return attached.client.send(
    "Runtime.evaluate",
    {
      expression: `(async () => {
        const api = globalThis.HYPEROPEN_DEBUG;
        if (!api || typeof api.dispatch !== "function") {
          throw new Error("HYPEROPEN_DEBUG.dispatch unavailable");
        }
        return await api.dispatch([":actions/navigate", ${JSON.stringify(routePath)}]);
      })()`,
      returnByValue: true,
      awaitPromise: true
    },
    attached.cdpSessionId,
    timeoutMs
  );
}

export async function prepareAttachedTarget(attached, options = {}) {
  await attached.client.send("Page.enable", {}, attached.cdpSessionId);
  await attached.client.send("Runtime.enable", {}, attached.cdpSessionId);
  if (options.viewport) {
    await attached.client.send(
      "Emulation.setDeviceMetricsOverride",
      {
        width: options.viewport.width,
        height: options.viewport.height,
        mobile: Boolean(options.viewport.mobile),
        deviceScaleFactor: options.viewport.deviceScaleFactor || 1
      },
      attached.cdpSessionId
    );
  }
}

export async function navigateAttachedTarget(attached, session, url, options = {}) {
  const timeoutMs = options.timeoutMs || 15000;
  await prepareAttachedTarget(attached, options);

  const bootstrapOptions =
    options.useBootstrap === false ? [] : bootstrapCandidates(session, options);
  let navigateUrl = url;
  let selectedBootstrapDetails = null;
  let lastBootstrapError = null;

  for (const bootstrapUrl of bootstrapOptions) {
    const bootstrapDetails = localBootstrapNavigationDetails(url, bootstrapUrl, session);
    if (!bootstrapDetails) {
      continue;
    }

    const debugBridgeTimeoutMs = options.debugBridgeTimeoutMs ?? 15000;
    const debugBridgePollMs = options.debugBridgePollMs ?? 50;
    const debugBridgeRetryCount = options.debugBridgeRetryCount ?? 1;
    const debugBridgeRetryDelayMs = options.debugBridgeRetryDelayMs ?? 250;
    let attempt = 0;

    try {
      await navigatePage(attached, bootstrapDetails.bootstrapUrl, timeoutMs);
      while (true) {
        try {
          await waitForDebugBridge(attached, debugBridgeTimeoutMs, debugBridgePollMs);
          break;
        } catch (error) {
          if (!isDebugBridgeTimeout(error) || attempt >= debugBridgeRetryCount) {
            throw error;
          }
          attempt += 1;
          if (debugBridgeRetryDelayMs > 0) {
            await sleep(debugBridgeRetryDelayMs);
          }
          await navigatePage(attached, bootstrapDetails.bootstrapUrl, timeoutMs);
        }
      }

      navigateUrl = bootstrapDetails.bootstrapUrl;
      selectedBootstrapDetails = bootstrapDetails;
      lastBootstrapError = null;
      break;
    } catch (error) {
      lastBootstrapError = error;
      if (!isDebugBridgeTimeout(error)) {
        throw error;
      }
    }
  }

  if (!selectedBootstrapDetails) {
    if (lastBootstrapError) {
      throw lastBootstrapError;
    }
    await navigatePage(attached, url, timeoutMs);
  } else {
    await dispatchNavigationAction(
      attached,
      selectedBootstrapDetails.routePath,
      options.dispatchTimeoutMs || 15000
    );
    await waitForDebugIdle(
      attached,
      options.waitForIdle || {
        quietMs: 200,
        timeoutMs: 6000,
        pollMs: 50
      }
    );
  }

  const titleResult = await attached.client.send(
    "Runtime.evaluate",
    {
      expression: "document.title",
      returnByValue: true
    },
    attached.cdpSessionId
  );

  return {
    sessionId: session?.id || null,
    url,
    navigatedUrl: navigateUrl,
    title: titleResult?.result?.value ?? null
  };
}

export async function closeLaunchedBrowserGracefully(session, options = {}) {
  const openClient = options.openClientForSession || openClientForSession;
  const waitForExit = options.waitForProcessExit || waitForProcessExit;
  const terminateProcess = options.killProcess || killProcess;

  try {
    const client = await openClient(session);
    try {
      await client.send(
        "Browser.close",
        {},
        undefined,
        options.browserCloseCommandTimeoutMs || 1500
      ).catch(() => null);
    } finally {
      await client.close().catch(() => null);
    }
  } catch (_error) {
    // Fallback to process termination below when CDP shutdown is unavailable.
  }

  if (await waitForExit(session?.chrome?.pid, options.browserCloseTimeoutMs || 5000)) {
    return true;
  }

  if (!session?.chrome?.pid) {
    return false;
  }

  return terminateProcess(session.chrome.pid);
}

export async function closeAttachedSessionTarget(session, options = {}) {
  if (
    (session?.chrome?.controlMode || "launched") !== "attached" ||
    session?.targetOwnership !== "created" ||
    !session?.targetId
  ) {
    return false;
  }

  const openClient = options.openClientForSession || openClientForSession;
  let client = null;
  try {
    client = await openClient(session);
    const result = await client.send(
      "Target.closeTarget",
      { targetId: session.targetId },
      undefined,
      options.targetCloseTimeoutMs || 2000
    );
    return result?.success !== false;
  } catch (_error) {
    return false;
  } finally {
    if (client) {
      await client.close().catch(() => null);
    }
  }
}

export async function stopSessionResources(session, options = {}) {
  const stopLocalApp = options.stopLocalApp || maybeStopLocalApp;
  const closeLaunchedBrowser = options.closeLaunchedBrowser || closeLaunchedBrowserGracefully;
  const closeAttachedTarget = options.closeAttachedTarget || closeAttachedSessionTarget;
  const removeDir = options.removeDir || ((target, removeOptions) => fs.rm(target, removeOptions));
  const controlMode = session?.chrome?.controlMode || "launched";

  if (controlMode === "launched" && session?.chrome?.pid) {
    await closeLaunchedBrowser(session, options);
  } else if (controlMode === "attached" && session?.targetOwnership === "created") {
    await closeAttachedTarget(session, options);
  }

  await stopLocalApp(session?.localApp);

  if (
    controlMode === "launched" &&
    session?.chrome?.ephemeralProfile &&
    session?.chrome?.userDataDir
  ) {
    await removeDir(session.chrome.userDataDir, { recursive: true, force: true }).catch(() => null);
  }

  return true;
}

export class SessionManager {
  constructor(config) {
    this.config = config;
    this.store = new ArtifactStore(config);
  }

  async init() {
    await this.store.init();
    await this.cleanupDeadSessions();
  }

  async isSessionAlive(session) {
    const mode = session?.chrome?.controlMode || "launched";
    if (mode === "attached") {
      try {
        await getBrowserWsUrl({
          host: session.chrome.host || "127.0.0.1",
          port: session.chrome.port,
          timeoutMs: 1500,
          pollIntervalMs: 150
        });
        return true;
      } catch (_err) {
        return false;
      }
    }
    return processIsAlive(session?.chrome?.pid);
  }

  async cleanupDeadSessions() {
    const sessions = await this.store.listSessions();
    const statuses = await Promise.all(
      sessions.map(async (session) => ({
        session,
        alive: await this.isSessionAlive(session)
      }))
    );
    const live = statuses.filter((entry) => entry.alive).map((entry) => entry.session);
    if (live.length !== sessions.length) {
      await this.store.writeSessionRegistry(live);
    }
    return live;
  }

  async listSessions() {
    const sessions = await this.cleanupDeadSessions();
    return sessions;
  }

  async stopAllSessions() {
    const sessions = await this.cleanupDeadSessions();
    const results = [];

    for (const session of sessions) {
      try {
        await this.stopSession(session.id);
        results.push({ sessionId: session.id, ok: true });
      } catch (error) {
        results.push({
          sessionId: session.id,
          ok: false,
          error: error?.message || String(error)
        });
      }
    }

    return {
      ok: results.every((entry) => entry.ok),
      stopped: results.filter((entry) => entry.ok).map((entry) => entry.sessionId),
      results
    };
  }

  async listTargets(options = {}) {
    if (options.sessionId && (options.attachPort !== undefined && options.attachPort !== null)) {
      throw new Error("listTargets accepts either sessionId or attach-port, not both");
    }

    let attach;
    if (options.sessionId) {
      const session = await this.getSession(options.sessionId);
      attach = {
        host: session.chrome.host || "127.0.0.1",
        port: session.chrome.port
      };
    } else {
      attach = normalizeAttachEndpoint(options);
      if (!attach) {
        throw new Error("listTargets requires --session-id or --attach-port");
      }
    }

    const client = await openClientForSession({ chrome: attach });
    try {
      const targets = await client.send("Target.getTargets", {});
      const pageTargets = (targets.targetInfos || [])
        .filter((target) => target.type === "page")
        .map(normalizeTargetInfo)
        .sort((a, b) => {
          if (a.url !== b.url) {
            return a.url.localeCompare(b.url);
          }
          return a.title.localeCompare(b.title);
        });

      return {
        host: attach.host,
        port: attach.port,
        targets: pageTargets
      };
    } finally {
      await client.close();
    }
  }

  async getSession(sessionId) {
    const sessions = await this.store.listSessions();
    const session = sessions.find((item) => item.id === sessionId);
    if (!session) {
      throw new Error(`Session not found: ${sessionId}`);
    }
    if (!(await this.isSessionAlive(session))) {
      await this.store.removeSession(sessionId);
      throw new Error(`Session is not alive: ${sessionId}`);
    }
    return session;
  }

  async startSession(options = {}) {
    const attach = normalizeAttachEndpoint(options);
    const requestedTargetId = options.targetId ? String(options.targetId) : null;
    if (requestedTargetId && !attach) {
      throw new Error("targetId can only be provided when attaching to an existing Chrome endpoint");
    }

    if (!attach) {
      const preflight = await runPreflightChecks(this.config, {
        localUrl: options.localAppUrl || this.config.localApp.url
      });
      if (!preflight.ok) {
        const primary = preflight.classification;
        const remediation = preflight.remediation ? ` Remediation: ${preflight.remediation}` : "";
        const reason = primary ? `${primary.summary}` : "Preflight checks failed.";
        const failed = preflight.summary?.failedRequiredCheckIds?.join(", ") || "unknown";
        throw new Error(`${reason} Required checks failed: ${failed}.${remediation}`);
      }
    }

    let localApp;
    try {
      localApp = await maybeStartLocalApp(this.config.localApp, {
        manageLocalApp: Boolean(options.manageLocalApp),
        command: options.localAppCommand,
        cwd: options.localAppCwd,
        url: options.localAppUrl || this.config.localApp.url,
        startupTimeoutMs: options.localAppStartupTimeoutMs || this.config.localApp.startupTimeoutMs,
        pollIntervalMs: options.localAppPollIntervalMs || this.config.localApp.pollIntervalMs
      });
    } catch (error) {
      throw withActionableStartupError(error, "Local app startup");
    }

    let chromeRuntime;
    if (attach) {
      await getBrowserWsUrl({
        host: attach.host,
        port: attach.port
      });
      chromeRuntime = {
        pid: null,
        port: attach.port,
        host: attach.host,
        path: null,
        headless: null,
        userDataDir: null,
        ephemeralProfile: false,
        controlMode: "attached"
      };
    } else {
      let launched;
      try {
        launched = await launchChrome({
          chromePath: options.chromePath || this.config.chrome.path,
          headless: options.headless ?? this.config.chrome.headless,
          extraArgs: [...(this.config.chrome.extraArgs || []), ...(options.extraArgs || [])],
          ephemeralProfile: options.ephemeralProfile ?? this.config.chrome.ephemeralProfile,
          userDataDir: options.userDataDir,
          detached: true
        });
      } catch (error) {
        throw withActionableStartupError(error, "Chrome startup");
      }
      chromeRuntime = {
        pid: launched.pid,
        port: launched.port,
        host: "127.0.0.1",
        path: options.chromePath || this.config.chrome.path,
        headless: options.headless ?? this.config.chrome.headless,
        userDataDir: launched.userDataDir,
        ephemeralProfile: options.ephemeralProfile ?? this.config.chrome.ephemeralProfile,
        controlMode: "launched"
      };
    }

    const sessionId = options.id || `sess-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
    let targetId = null;
    const targetOwnership = requestedTargetId ? "existing" : "created";

    const client = await openClientForSession({ chrome: chromeRuntime });
    try {
      if (requestedTargetId) {
        const targets = await client.send("Target.getTargets", {});
        const exists = (targets.targetInfos || []).some((target) => target.targetId === requestedTargetId);
        if (!exists) {
          throw new Error(`Target not found on attach endpoint: ${requestedTargetId}`);
        }
        targetId = requestedTargetId;
      } else {
        const created = await client.send("Target.createTarget", { url: options.initialUrl || "about:blank" });
        targetId = created.targetId;
      }
    } finally {
      await client.close();
    }

    const session = {
      id: sessionId,
      createdAt: safeNowIso(),
      readOnly: options.readOnly ?? this.config.readOnly.enabled,
      chrome: chromeRuntime,
      localApp,
      targetId,
      targetOwnership
    };

    assertSessionState(session);
    await this.store.upsertSession(session);
    return session;
  }

  async stopSession(sessionId) {
    const session = await this.getSession(sessionId);
    await stopSessionResources(session);
    await this.store.removeSession(sessionId);
    return true;
  }

  async ensureAttachedTarget(sessionId) {
    const session = await this.getSession(sessionId);
    const client = await openClientForSession(session);
    let attached;

    try {
      attached = await client.send("Target.attachToTarget", {
        targetId: session.targetId,
        flatten: true
      });
    } catch (_err) {
      const created = await client.send("Target.createTarget", { url: "about:blank" });
      session.targetId = created.targetId;
      await this.store.upsertSession(session);
      attached = await client.send("Target.attachToTarget", {
        targetId: session.targetId,
        flatten: true
      });
    }

    const cdpSessionId = attached.sessionId;
    return {
      session,
      client,
      cdpSessionId,
      cleanup: async () => {
        try {
          await client.send("Target.detachFromTarget", { sessionId: cdpSessionId });
        } catch (_err) {
          // ignore
        }
        await client.close();
      }
    };
  }

  async navigate(sessionId, url, options = {}) {
    const session = await this.getSession(sessionId);
    const resolvedUrl = resolveManagedLocalUrl(url, session, this.config);
    if (session.readOnly) {
      validateUrlForReadOnly(this.config, resolvedUrl);
    }

    const attached = await this.ensureAttachedTarget(sessionId);
    try {
      return await navigateAttachedTarget(attached, session, resolvedUrl, {
        viewport: options.viewport,
        timeoutMs: options.timeoutMs || this.config.capture.navigationTimeoutMs,
        bootstrapUrl: options.bootstrapUrl || session.localApp?.url || this.config.localApp.url,
        useBootstrap: options.useBootstrap
      });
    } finally {
      await attached.cleanup();
    }
  }

  async evaluate(sessionId, expression, options = {}) {
    const session = await this.getSession(sessionId);
    if (session.readOnly) {
      validateEvalForReadOnly(this.config, expression, options.allowUnsafeEval);
    }

    const attached = await this.ensureAttachedTarget(sessionId);
    try {
      await attached.client.send("Runtime.enable", {}, attached.cdpSessionId);
      const response = await attached.client.send(
        "Runtime.evaluate",
        {
          expression,
          returnByValue: true,
          awaitPromise: true
        },
        attached.cdpSessionId,
        options.timeoutMs || 15000
      );
      return {
        sessionId,
        result: response?.result?.value,
        type: response?.result?.type,
        description: response?.result?.description
      };
    } finally {
      await attached.cleanup();
    }
  }
}
