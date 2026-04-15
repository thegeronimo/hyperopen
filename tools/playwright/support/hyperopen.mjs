import { expect } from "@playwright/test";

const defaultIdleOptions = Object.freeze({
  quietMs: 300,
  timeoutMs: 6_000,
  pollMs: 50
});

export const mobileViewport = Object.freeze({
  viewport: { width: 390, height: 844 },
  isMobile: true,
  hasTouch: true,
  deviceScaleFactor: 3
});

export async function waitForDebugBridge(page, timeoutMs = 20_000) {
  await expect
    .poll(
      () =>
        page.evaluate(() =>
          Boolean(
            globalThis.HYPEROPEN_DEBUG &&
              typeof globalThis.HYPEROPEN_DEBUG.dispatch === "function" &&
              typeof globalThis.HYPEROPEN_DEBUG.waitForIdle === "function" &&
              typeof globalThis.HYPEROPEN_DEBUG.oracle === "function"
          )
        ),
      { timeout: timeoutMs }
    )
    .toBe(true);
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

  await page.goto(`/index.html${search}`);
  await waitForDebugBridge(page);
  await debugCall(page, "qaReset");
  if (path !== "/trade") {
    await debugCall(page, "dispatch", [
      ":actions/navigate",
      path,
      { "replace?": true }
    ]);
  }
  await waitForIdle(page, options.idleOptions || defaultIdleOptions);
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
