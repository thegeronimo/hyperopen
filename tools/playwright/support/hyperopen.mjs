import { expect } from "@playwright/test";

const requiredDebugBridgeMethods = Object.freeze([
  "dispatch",
  "waitForIdle",
  "oracle"
]);

const defaultIdleOptions = Object.freeze({
  quietMs: 300,
  timeoutMs: 6_000,
  pollMs: 50
});

const debugBridgeEventState = new WeakMap();

export const mobileViewport = Object.freeze({
  viewport: { width: 390, height: 844 },
  isMobile: true,
  hasTouch: true,
  deviceScaleFactor: 3
});

function truncateDiagnosticText(value, maxLength = 500) {
  const text = String(value ?? "");
  if (text.length <= maxLength) {
    return text;
  }
  return `${text.slice(0, maxLength)}...`;
}

function pushRecent(items, value, maxItems = 8) {
  items.push(truncateDiagnosticText(value));
  if (items.length > maxItems) {
    items.shift();
  }
}

function trackDebugBridgeEvents(page) {
  if (!page || typeof page.on !== "function") {
    return null;
  }

  const existing = debugBridgeEventState.get(page);
  if (existing) {
    return existing;
  }

  const state = {
    consoleMessages: [],
    pageErrors: []
  };

  const onConsole = (message) => {
    const type =
      typeof message?.type === "function" ? message.type() : message?.type;
    const text =
      typeof message?.text === "function" ? message.text() : String(message);
    if (type === "error" || type === "warning") {
      pushRecent(state.consoleMessages, `${type}: ${text}`);
    }
  };

  const onPageError = (error) => {
    pushRecent(state.pageErrors, error?.stack || error?.message || error);
  };

  page.on("console", onConsole);
  page.on("pageerror", onPageError);
  debugBridgeEventState.set(page, state);
  return state;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));
}

async function sleepForPage(page, ms) {
  if (ms <= 0) {
    return;
  }
  if (typeof page?.waitForTimeout === "function") {
    await page.waitForTimeout(ms);
    return;
  }
  await sleep(ms);
}

async function readDebugBridgeState(page) {
  return page.evaluate((requiredMethods) => {
    const required =
      Array.isArray(requiredMethods) && requiredMethods.length > 0
        ? requiredMethods
        : ["dispatch", "waitForIdle", "oracle"];
    const api = globalThis.HYPEROPEN_DEBUG;
    const bridgeMethods = Object.fromEntries(
      required.map((method) => [method, typeof api?.[method] === "function"])
    );
    const ready =
      Boolean(api) && required.every((method) => bridgeMethods[method]);

    return {
      ready,
      debugBridgePresent: Boolean(api),
      bridgeMethods
    };
  }, requiredDebugBridgeMethods);
}

function readPageUrl(page) {
  try {
    return typeof page?.url === "function" ? page.url() : null;
  } catch (error) {
    return `unavailable: ${error?.message || error}`;
  }
}

async function hasShadowStaleOutput(page) {
  try {
    return (await page.locator("text=shadow-cljs - Stale Output").count()) > 0;
  } catch (_error) {
    return false;
  }
}

async function readPageTitle(page) {
  try {
    return typeof page?.title === "function" ? await page.title() : null;
  } catch (error) {
    return `unavailable: ${error?.message || error}`;
  }
}

async function collectDebugBridgeDiagnostics(page, eventState, lastError) {
  let browserState = null;
  let browserStateError = null;

  try {
    browserState = await page.evaluate((requiredMethods) => {
      const required =
        Array.isArray(requiredMethods) && requiredMethods.length > 0
          ? requiredMethods
          : ["dispatch", "waitForIdle", "oracle"];
      const doc = globalThis.document || null;
      const loc = globalThis.location || null;
      const api = globalThis.HYPEROPEN_DEBUG;
      const store = globalThis.hyperopen?.system?.store;
      const appRoot = doc?.querySelector?.("#app, [data-parity-id='app-root']");
      const bridgeMethods = Object.fromEntries(
        required.map((method) => [method, typeof api?.[method] === "function"])
      );

      return {
        href: loc?.href || null,
        "document.readyState": doc?.readyState || null,
        appRootPresent: Boolean(appRoot),
        debugBridgePresent: Boolean(api),
        bridgeMethods,
        appStorePresent: Boolean(store)
      };
    }, requiredDebugBridgeMethods);
  } catch (error) {
    browserStateError = error?.message || String(error);
  }

  return {
    url: readPageUrl(page),
    title: await readPageTitle(page),
    browserState,
    browserStateError,
    lastReadinessError: lastError?.message || null,
    consoleMessages: eventState?.consoleMessages || [],
    pageErrors: eventState?.pageErrors || []
  };
}

function formatBridgeMethods(methods) {
  if (!methods || typeof methods !== "object") {
    return "unavailable";
  }
  return Object.entries(methods)
    .map(([method, present]) => `${method}:${present ? "ok" : "missing"}`)
    .join(", ");
}

function formatRecentList(items) {
  if (!items || items.length === 0) {
    return "none";
  }
  return items.map((item) => JSON.stringify(item)).join(" | ");
}

function formatDebugBridgeDiagnostics(diagnostics) {
  const state = diagnostics.browserState || {};
  return [
    `url=${JSON.stringify(diagnostics.url)}`,
    `title=${JSON.stringify(diagnostics.title)}`,
    `href=${JSON.stringify(state.href || null)}`,
    `document.readyState=${JSON.stringify(state["document.readyState"] || null)}`,
    `appRootPresent=${Boolean(state.appRootPresent)}`,
    `debugBridgePresent=${Boolean(state.debugBridgePresent)}`,
    `bridgeMethods={${formatBridgeMethods(state.bridgeMethods)}}`,
    `appStorePresent=${Boolean(state.appStorePresent)}`,
    `lastReadinessError=${JSON.stringify(diagnostics.lastReadinessError)}`,
    `browserStateError=${JSON.stringify(diagnostics.browserStateError)}`,
    `consoleMessages=${formatRecentList(diagnostics.consoleMessages)}`,
    `pageErrors=${formatRecentList(diagnostics.pageErrors)}`
  ].join("; ");
}

export async function waitForDebugBridge(page, timeoutMs = 15_000, options = {}) {
  const pollMs = options.pollMs ?? 50;
  const eventState = trackDebugBridgeEvents(page);
  const deadline = Date.now() + timeoutMs;
  let lastError = null;

  while (Date.now() <= deadline) {
    try {
      const state = await readDebugBridgeState(page);
      if (state.ready) {
        return true;
      }
    } catch (error) {
      lastError = error;
    }

    const remainingMs = deadline - Date.now();
    if (remainingMs <= 0) {
      break;
    }
    await sleepForPage(page, Math.min(pollMs, remainingMs));
  }

  const diagnostics = await collectDebugBridgeDiagnostics(
    page,
    eventState,
    lastError
  );
  const error = new Error(
    `Timed out waiting for HYPEROPEN_DEBUG to initialize after ${timeoutMs}ms. ` +
      formatDebugBridgeDiagnostics(diagnostics)
  );
  error.debugBridgeDiagnostics = diagnostics;
  throw error;
}

export async function debugCall(page, method, ...args) {
  await waitForDebugBridge(page);
  return page.evaluate(
    async ({ methodName, methodArgs }) => {
      const api = globalThis.HYPEROPEN_DEBUG;
      if (!api) {
        throw new Error("HYPEROPEN_DEBUG unavailable");
      }
      const fn = api[methodName];
      if (typeof fn !== "function") {
        throw new Error(`HYPEROPEN_DEBUG.${methodName} unavailable`);
      }
      return await fn(...methodArgs);
    },
    { methodName: method, methodArgs: args }
  );
}

export async function waitForIdle(page, options = defaultIdleOptions) {
  return debugCall(page, "waitForIdle", options);
}

function splitRoute(route) {
  const routeUrl = new URL(route || "/trade", "http://hyperopen.local");
  return {
    path: routeUrl.pathname || "/trade",
    search: routeUrl.search || ""
  };
}

export async function visitRoute(page, route, options = {}) {
  const { path, search } = splitRoute(route);
  const debugBridgeTimeoutMs = options.debugBridgeTimeoutMs ?? 15_000;
  const debugBridgePollMs = options.debugBridgePollMs ?? 50;
  const debugBridgeRetryCount = options.debugBridgeRetryCount ?? 1;
  const pageLoadRetryCount =
    options.pageLoadRetryCount ?? Math.max(debugBridgeRetryCount, 8);
  const debugBridgeRetryDelayMs = options.debugBridgeRetryDelayMs ?? 250;
  const initialUrl = `${path}${search}`;

  trackDebugBridgeEvents(page);
  let lastDebugBridgeError = null;
  for (let attempt = 0; attempt <= pageLoadRetryCount; attempt += 1) {
    try {
      await page.goto(initialUrl);
      await waitForDebugBridge(page, debugBridgeTimeoutMs, {
        pollMs: debugBridgePollMs
      });
      if (await hasShadowStaleOutput(page)) {
        throw new Error("shadow-cljs stale output loaded");
      }
      lastDebugBridgeError = null;
      break;
    } catch (error) {
      lastDebugBridgeError = error;
      if (attempt >= pageLoadRetryCount) {
        throw error;
      }
      await sleepForPage(page, debugBridgeRetryDelayMs);
    }
  }
  if (lastDebugBridgeError) {
    throw lastDebugBridgeError;
  }
  await debugCall(page, "qaReset");
  if (path !== "/trade") {
    await debugCall(page, "dispatch", [
      ":actions/navigate",
      path,
      { "replace?": true }
    ]);
  }
  await waitForIdle(page, options.idleOptions || defaultIdleOptions);
  if (path !== "/trade") {
    await expect(page.locator("[data-parity-id='app-route-module-shell']"))
      .toHaveCount(0, { timeout: options.routeModuleTimeoutMs ?? 15_000 });
  }
}

export async function dispatch(page, action) {
  return debugCall(page, "dispatch", action);
}

export async function dispatchMany(page, actions) {
  return debugCall(page, "dispatchMany", actions);
}

export async function oracle(page, name, args = {}) {
  return debugCall(page, "oracle", name, args);
}

export async function expectOracle(page, name, expected, options = {}) {
  const { args = {}, timeoutMs = 10_000 } = options;
  await expect
    .poll(() => oracle(page, name, args), { timeout: timeoutMs })
    .toMatchObject(expected);
}

export async function sourceRectForLocator(page, locator) {
  const box = await locator.boundingBox();
  const viewport = page.viewportSize();

  if (!box || !viewport) {
    return {
      left: 16,
      right: 374,
      top: 732,
      bottom: 772,
      width: 358,
      height: 40,
      "viewport-width": 390,
      "viewport-height": 844
    };
  }

  return {
    left: box.x,
    right: box.x + box.width,
    top: box.y,
    bottom: box.y + box.height,
    width: box.width,
    height: box.height,
    "viewport-width": viewport.width,
    "viewport-height": viewport.height
  };
}

export async function hoverLocatorAtRatio(
  page,
  locator,
  { xRatio = 0.5, yRatio = 0.5 } = {}
) {
  const box = await locator.boundingBox();

  if (!box) {
    throw new Error("Locator has no bounding box for hover");
  }

  const clampRatio = (value) => Math.max(0, Math.min(1, Number(value) || 0));
  const nextX = box.x + box.width * clampRatio(xRatio);
  const nextY = box.y + box.height * clampRatio(yRatio);

  await page.mouse.move(nextX, nextY);
}
