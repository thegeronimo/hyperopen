#!/usr/bin/env node
import fs from "node:fs/promises";
import path from "node:path";
import { spawn } from "node:child_process";
import { chromium } from "@playwright/test";
import { getRepoRoot, loadConfig } from "./config.mjs";
import { ensureDir, makeRunId, sleep, writeJsonFile } from "./util.mjs";

const VIEWPORT = { width: 1440, height: 900 };
const BENCHMARK_ROUTE = "/trade";
const DEFAULT_ORIGIN = "http://127.0.0.1:4173";
const DEFAULT_STATIC_ROOT = "out/release-public";
const DEFAULT_LOCAL_APP_COMMAND =
  "PLAYWRIGHT_STATIC_ROOT=out/release-public PLAYWRIGHT_WEB_PORT=4173 node tools/playwright/static_server.mjs";
const SERVER_WAIT_TIMEOUT_MS = Number(process.env.HYPEROPEN_PROFILE_SERVER_TIMEOUT_MS || "120000");
const PAGE_WAIT_TIMEOUT_MS = Number(process.env.HYPEROPEN_PROFILE_PAGE_TIMEOUT_MS || "30000");
const SETTLE_TIMEOUT_MS = Number(process.env.HYPEROPEN_PROFILE_SETTLE_TIMEOUT_MS || "1000");
const NAVIGATION_TIMEOUT_MS = Number(process.env.HYPEROPEN_PROFILE_NAVIGATION_TIMEOUT_MS || "30000");
const LOCAL_APP_COMMAND = process.env.HYPEROPEN_PROFILE_LOCAL_APP_COMMAND || DEFAULT_LOCAL_APP_COMMAND;
const MANAGE_LOCAL_APP = process.env.HYPEROPEN_PROFILE_MANAGE_LOCAL_APP !== "0";
const ORIGIN = process.env.HYPEROPEN_PROFILE_ORIGIN || DEFAULT_ORIGIN;
const STATIC_ROOT = process.env.PLAYWRIGHT_STATIC_ROOT || DEFAULT_STATIC_ROOT;

function log(message, details = undefined) {
  const prefix = `[trade-startup-profile ${new Date().toISOString()}] ${message}`;
  if (details === undefined) {
    console.error(prefix);
    return;
  }
  console.error(`${prefix} ${JSON.stringify(details)}`);
}

async function withTimeout(label, timeoutMs, work) {
  let timeoutId;
  try {
    return await Promise.race([
      Promise.resolve().then(work),
      new Promise((_, reject) => {
        timeoutId = setTimeout(() => {
          reject(new Error(`${label} timed out after ${timeoutMs}ms`));
        }, timeoutMs);
      })
    ]);
  } finally {
    clearTimeout(timeoutId);
  }
}

async function waitForHttp(url, timeoutMs, pollMs = 500) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { redirect: "follow" });
      if (response.ok) {
        return true;
      }
    } catch (_error) {
      // keep polling
    }
    await sleep(pollMs);
  }
  throw new Error(`Timed out waiting for ${url}`);
}

async function startStaticServer(runDir) {
  if (!MANAGE_LOCAL_APP) {
    await waitForHttp(ORIGIN, SERVER_WAIT_TIMEOUT_MS);
    return {
      child: null,
      logPath: null,
      url: ORIGIN
    };
  }

  const repoRoot = getRepoRoot();
  const logPath = path.join(runDir, "local-app.log");
  const child = spawn("sh", ["-lc", LOCAL_APP_COMMAND], {
    cwd: repoRoot,
    detached: true,
    stdio: ["ignore", "pipe", "pipe"]
  });
  let output = "";
  const appendOutput = (chunk) => {
    output = `${output}${chunk}`.slice(-16000);
  };
  for (const stream of [child.stdout, child.stderr]) {
    if (!stream) {
      continue;
    }
    stream.setEncoding("utf8");
    stream.on("data", appendOutput);
  }

  const exitPromise = new Promise((resolve) => {
    child.once("exit", (code, signal) => resolve({ code, signal }));
  });
  const readyPromise = waitForHttp(ORIGIN, SERVER_WAIT_TIMEOUT_MS).then(() => ({ ok: true }));
  const winner = await Promise.race([readyPromise, exitPromise]);
  if (!winner.ok) {
    await fs.writeFile(logPath, output || "Static server exited before becoming ready.\n");
    throw new Error(
      `Static server exited early (${winner.code !== null ? `code ${winner.code}` : `signal ${winner.signal || "unknown"}`}).`
    );
  }

  await fs.writeFile(logPath, output || `Ready at ${ORIGIN}\n`);
  child.unref();
  return {
    child,
    logPath,
    url: ORIGIN
  };
}

async function stopStaticServer(server) {
  if (!server?.child?.pid) {
    return;
  }
  try {
    process.kill(-server.child.pid, "SIGTERM");
  } catch (_error) {
    try {
      process.kill(server.child.pid, "SIGTERM");
    } catch (_err) {
      // ignore
    }
  }
  await sleep(500);
  try {
    process.kill(-server.child.pid, "SIGKILL");
  } catch (_error) {
    try {
      process.kill(server.child.pid, "SIGKILL");
    } catch (_err) {
      // ignore
    }
  }
}

function summarizeLongTasks(entries) {
  const sorted = [...entries].sort((a, b) => a.startTime - b.startTime);
  const totalMs = sorted.reduce((acc, entry) => acc + entry.duration, 0);
  const blockingMs = sorted.reduce((acc, entry) => acc + Math.max(0, entry.duration - 50), 0);
  const maxLongTaskMs = sorted.reduce((acc, entry) => Math.max(acc, entry.duration), 0);
  return {
    count: sorted.length,
    totalMs,
    blockingMs,
    maxLongTaskMs,
    entries: sorted
  };
}

async function captureProfile() {
  const config = await loadConfig();
  const artifactRoot = config.artifactRoot;
  const runId = makeRunId("trade-startup-profile");
  const runDir = path.join(artifactRoot, runId);
  await ensureDir(runDir);
  const server = await startStaticServer(runDir);
  const browser = await chromium.launch({
    headless: true,
    args: [
      "--disable-background-networking",
      "--disable-component-update",
      "--disable-renderer-backgrounding",
      "--disable-ipc-flooding-protection",
      "--no-first-run",
      "--no-default-browser-check",
      "--disable-features=Translate,MediaRouter"
    ]
  });
  const context = await browser.newContext({ viewport: VIEWPORT });
  const page = await context.newPage();
  const cdp = await context.newCDPSession(page);

  const networkByRequestId = new Map();
  const networkEvents = [];

  await cdp.send("Network.enable");
  cdp.on("Network.requestWillBeSent", (event) => {
    networkByRequestId.set(event.requestId, {
      url: event.request.url,
      method: event.request.method,
      type: event.type || null,
      startTime: event.timestamp
    });
  });
  cdp.on("Network.responseReceived", (event) => {
    const record = networkByRequestId.get(event.requestId);
    if (!record) {
      return;
    }
    record.status = event.response.status;
    record.mimeType = event.response.mimeType;
    record.fromDiskCache = Boolean(event.response.fromDiskCache);
    record.fromServiceWorker = Boolean(event.response.fromServiceWorker);
    record.responseTime = event.timestamp;
  });
  cdp.on("Network.loadingFinished", (event) => {
    const record = networkByRequestId.get(event.requestId);
    if (!record) {
      return;
    }
    record.encodedDataLength = event.encodedDataLength;
    networkEvents.push(record);
  });

  await page.addInitScript(() => {
    const state = {
      longTasks: [],
      paints: [],
      lcp: [],
      supported: typeof PerformanceObserver === "function"
    };
    Object.defineProperty(globalThis, "__hyperopenTradeStartupProfile", {
      configurable: true,
      value: state
    });

    if (!state.supported) {
      return;
    }

    const safeObserve = (type, handler) => {
      try {
        const observer = new PerformanceObserver(handler);
        observer.observe({ type, buffered: true });
      } catch (_error) {
        // ignore unsupported entry types
      }
    };

    safeObserve("longtask", (list) => {
      for (const entry of list.getEntries()) {
        state.longTasks.push({
          name: entry.name || null,
          startTime: entry.startTime || 0,
          duration: entry.duration || 0
        });
      }
    });

    safeObserve("paint", (list) => {
      for (const entry of list.getEntries()) {
        state.paints.push({
          name: entry.name || null,
          startTime: entry.startTime || 0
        });
      }
    });

    safeObserve("largest-contentful-paint", (list) => {
      for (const entry of list.getEntries()) {
        state.lcp.push({
          startTime: entry.startTime || 0,
          renderTime: entry.renderTime || 0,
          loadTime: entry.loadTime || 0,
          size: entry.size || 0
        });
      }
    });
  });

  const targetUrl = new URL(BENCHMARK_ROUTE, server.url).toString();
  const navStartWall = Date.now();
  const result = {
    runId,
    capturedAt: new Date().toISOString(),
    benchmarkRoute: BENCHMARK_ROUTE,
    origin: server.url,
    viewport: VIEWPORT,
    localAppCommand: MANAGE_LOCAL_APP ? LOCAL_APP_COMMAND : null,
    localAppManaged: MANAGE_LOCAL_APP,
    staticRoot: STATIC_ROOT,
    timings: {},
    network: {},
    browser: {
      name: "chromium",
      headless: true
    },
    page: {
      url: null,
      title: null
    },
    longTasks: [],
    longTaskSummary: null,
    paintEntries: [],
    lcpEntries: [],
    navigation: null
  };

  try {
    await withTimeout("goto trade route", NAVIGATION_TIMEOUT_MS, () =>
      page.goto(targetUrl, { waitUntil: "commit" })
    );
    result.timings.gotoCommitElapsedMs = Date.now() - navStartWall;

    await withTimeout("wait for trade root", PAGE_WAIT_TIMEOUT_MS, () =>
      page.locator("[data-parity-id='trade-root']").waitFor({ state: "visible" })
    );
    result.timings.tradeRootVisibleMs = await page.evaluate(() => performance.now());

    await withTimeout("wait for order form", PAGE_WAIT_TIMEOUT_MS, () =>
      page.locator("[data-parity-id='order-form']").waitFor({ state: "visible" })
    );
    result.timings.orderFormVisibleMs = await page.evaluate(() => performance.now());

    await page.waitForTimeout(SETTLE_TIMEOUT_MS);
    result.timings.settleElapsedMs = await page.evaluate(() => performance.now());
    result.page.url = page.url();
    result.page.title = await page.title();

    const pageMetrics = await page.evaluate(() => {
      const navigation = performance.getEntriesByType("navigation")[0] || null;
      const paints = performance.getEntriesByType("paint").map((entry) => ({
        name: entry.name || null,
        startTime: entry.startTime || 0
      }));
      const startup = globalThis.__hyperopenTradeStartupProfile || { longTasks: [], paints: [], lcp: [] };
      return {
        navigation: navigation
          ? {
              domContentLoadedMs: navigation.domContentLoadedEventEnd || null,
              loadEventEndMs: navigation.loadEventEnd || null,
              responseStartMs: navigation.responseStart || null,
              responseEndMs: navigation.responseEnd || null,
              transferSize: navigation.transferSize || null,
              encodedBodySize: navigation.encodedBodySize || null,
              decodedBodySize: navigation.decodedBodySize || null
            }
          : null,
        paints,
        longTasks: startup.longTasks || [],
        lcp: startup.lcp || []
      };
    });

    result.navigation = pageMetrics.navigation;
    result.paintEntries = pageMetrics.paints;
    result.longTasks = pageMetrics.longTasks;
    result.longTaskSummary = summarizeLongTasks(result.longTasks);
    result.longTaskSummary.blockingTimeProxyMs = result.longTaskSummary.blockingMs;
    result.longTaskSummary.maxSingleBlockingTaskMs = result.longTaskSummary.maxLongTaskMs;
    result.longTaskSummary.longTaskCount = result.longTaskSummary.count;
    result.paintEntries = pageMetrics.paints;
    result.lcpEntries = pageMetrics.lcp;

    const mainJsRequests = networkEvents.filter((entry) => /\/js\/main\.[^/]+\.js$/u.test(entry.url));
    const mainCssRequests = networkEvents.filter((entry) => /\/css\/main\.[^/]+\.css$/u.test(entry.url));
    result.network.mainJs = mainJsRequests.map((entry) => ({
      url: entry.url,
      encodedDataLength: entry.encodedDataLength || 0,
      status: entry.status || null,
      mimeType: entry.mimeType || null,
      fromDiskCache: Boolean(entry.fromDiskCache),
      fromServiceWorker: Boolean(entry.fromServiceWorker)
    }));
    result.network.mainCss = mainCssRequests.map((entry) => ({
      url: entry.url,
      encodedDataLength: entry.encodedDataLength || 0,
      status: entry.status || null,
      mimeType: entry.mimeType || null,
      fromDiskCache: Boolean(entry.fromDiskCache),
      fromServiceWorker: Boolean(entry.fromServiceWorker)
    }));
    result.network.summary = {
      mainJsBytes: mainJsRequests.reduce((sum, entry) => sum + (entry.encodedDataLength || 0), 0),
      mainJsRequests: mainJsRequests.length,
      mainCssBytes: mainCssRequests.reduce((sum, entry) => sum + (entry.encodedDataLength || 0), 0),
      mainCssRequests: mainCssRequests.length,
      criticalBytes:
        mainJsRequests.reduce((sum, entry) => sum + (entry.encodedDataLength || 0), 0) +
        mainCssRequests.reduce((sum, entry) => sum + (entry.encodedDataLength || 0), 0)
    };
    result.network.allRequests = networkEvents
      .filter((entry) => /\/(js|css)\//u.test(entry.url))
      .map((entry) => ({
        url: entry.url,
        type: entry.type || null,
        encodedDataLength: entry.encodedDataLength || 0,
        status: entry.status || null
      }));

    const profilePath = path.join(runDir, "profile.json");
    const manifestPath = path.join(runDir, "manifest.json");
    await writeJsonFile(profilePath, result);
    await writeJsonFile(manifestPath, {
      runId,
      kind: "trade-startup-profile",
      createdAt: result.capturedAt,
      status: "complete",
      profilePath,
      benchmarkRoute: BENCHMARK_ROUTE,
      origin: server.url,
      viewport: VIEWPORT,
      localAppManaged: MANAGE_LOCAL_APP,
      localAppCommand: MANAGE_LOCAL_APP ? LOCAL_APP_COMMAND : null,
      staticRoot: STATIC_ROOT,
      serverLogPath: server.logPath,
      timings: result.timings,
      longTaskSummary: result.longTaskSummary,
      network: {
        mainJs: result.network.mainJs,
        mainCss: result.network.mainCss,
        summary: result.network.summary
      }
    });

    log("captured trade startup profile", {
      runDir,
      profilePath,
      blockingTimeProxyMs: result.longTaskSummary.blockingTimeProxyMs,
      maxSingleBlockingTaskMs: result.longTaskSummary.maxSingleBlockingTaskMs
    });

    return {
      runDir,
      profilePath,
      manifestPath,
      result
    };
  } catch (error) {
    const failurePath = path.join(runDir, "failure.json");
    const screenshotPath = path.join(runDir, "failure.png");
    const failure = {
      runId,
      capturedAt: new Date().toISOString(),
      benchmarkRoute: BENCHMARK_ROUTE,
      origin: server.url,
      error: {
        message: error.message,
        stack: error.stack
      },
      pageUrl: page.url()
    };
    try {
      await page.screenshot({ path: screenshotPath, fullPage: true });
      failure.screenshotPath = screenshotPath;
    } catch (_screenshotError) {
      // ignore screenshot failures
    }
    await writeJsonFile(failurePath, failure);
    log("failed trade startup profile", { runDir, failurePath, error: error.message });
    throw error;
  } finally {
    await page.close().catch(() => null);
    await context.close().catch(() => null);
    await browser.close().catch(() => null);
    await stopStaticServer(server).catch(() => null);
  }
}

async function main() {
  const { runDir, profilePath } = await captureProfile();
  console.log(JSON.stringify({ runDir, profilePath }, null, 2));
}

main().catch((error) => {
  console.error(error?.stack || error?.message || String(error));
  process.exitCode = 1;
});
