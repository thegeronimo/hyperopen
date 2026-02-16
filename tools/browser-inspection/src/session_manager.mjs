import fs from "node:fs/promises";
import { assertSessionState } from "./contracts.mjs";
import { ArtifactStore } from "./artifact_store.mjs";
import { CDPClient, getBrowserWsUrl } from "./cdp_client.mjs";
import { killProcess, launchChrome, processIsAlive } from "./chrome_launcher.mjs";
import { maybeStartLocalApp, maybeStopLocalApp } from "./local_app_manager.mjs";
import { safeNowIso } from "./util.mjs";

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
  const wsUrl = await getBrowserWsUrl(session.chrome.port);
  const client = new CDPClient(wsUrl);
  await client.connect();
  return client;
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

  async cleanupDeadSessions() {
    const sessions = await this.store.listSessions();
    const live = sessions.filter((session) => processIsAlive(session.chrome?.pid));
    if (live.length !== sessions.length) {
      await this.store.writeSessionRegistry(live);
    }
    return live;
  }

  async listSessions() {
    const sessions = await this.cleanupDeadSessions();
    return sessions;
  }

  async getSession(sessionId) {
    const sessions = await this.store.listSessions();
    const session = sessions.find((item) => item.id === sessionId);
    if (!session) {
      throw new Error(`Session not found: ${sessionId}`);
    }
    if (!processIsAlive(session.chrome?.pid)) {
      await this.store.removeSession(sessionId);
      throw new Error(`Session is not alive: ${sessionId}`);
    }
    return session;
  }

  async startSession(options = {}) {
    const localApp = await maybeStartLocalApp(this.config.localApp, {
      manageLocalApp: Boolean(options.manageLocalApp),
      command: options.localAppCommand,
      cwd: options.localAppCwd,
      url: options.localAppUrl || this.config.localApp.url,
      startupTimeoutMs: options.localAppStartupTimeoutMs || this.config.localApp.startupTimeoutMs,
      pollIntervalMs: options.localAppPollIntervalMs || this.config.localApp.pollIntervalMs
    });

    const launched = await launchChrome({
      chromePath: options.chromePath || this.config.chrome.path,
      headless: options.headless ?? this.config.chrome.headless,
      extraArgs: [...(this.config.chrome.extraArgs || []), ...(options.extraArgs || [])],
      ephemeralProfile: options.ephemeralProfile ?? this.config.chrome.ephemeralProfile,
      userDataDir: options.userDataDir,
      detached: true
    });

    const sessionId = options.id || `sess-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
    let targetId = null;

    const client = await openClientForSession({ chrome: launched });
    try {
      const created = await client.send("Target.createTarget", { url: options.initialUrl || "about:blank" });
      targetId = created.targetId;
    } finally {
      await client.close();
    }

    const session = {
      id: sessionId,
      createdAt: safeNowIso(),
      readOnly: options.readOnly ?? this.config.readOnly.enabled,
      chrome: {
        pid: launched.pid,
        port: launched.port,
        path: options.chromePath || this.config.chrome.path,
        headless: options.headless ?? this.config.chrome.headless,
        userDataDir: launched.userDataDir,
        ephemeralProfile: options.ephemeralProfile ?? this.config.chrome.ephemeralProfile
      },
      localApp,
      targetId
    };

    assertSessionState(session);
    await this.store.upsertSession(session);
    return session;
  }

  async stopSession(sessionId) {
    const session = await this.getSession(sessionId);
    await killProcess(session.chrome.pid);
    await maybeStopLocalApp(session.localApp);
    if (session.chrome.ephemeralProfile && session.chrome.userDataDir) {
      await fs.rm(session.chrome.userDataDir, { recursive: true, force: true }).catch(() => null);
    }
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
    if (session.readOnly) {
      validateUrlForReadOnly(this.config, url);
    }

    const attached = await this.ensureAttachedTarget(sessionId);
    try {
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

      const loadEvent = attached.client.waitForEvent("Page.loadEventFired", {
        sessionId: attached.cdpSessionId,
        timeoutMs: options.timeoutMs || this.config.capture.navigationTimeoutMs
      });
      await attached.client.send("Page.navigate", { url }, attached.cdpSessionId);
      await loadEvent;

      const titleResult = await attached.client.send(
        "Runtime.evaluate",
        {
          expression: "document.title",
          returnByValue: true
        },
        attached.cdpSessionId
      );

      return {
        sessionId,
        url,
        title: titleResult?.result?.value ?? null
      };
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
