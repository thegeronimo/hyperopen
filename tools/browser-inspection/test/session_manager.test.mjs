import test from "node:test";
import assert from "node:assert/strict";
import { navigateAttachedTarget } from "../src/session_manager.mjs";

test("navigateAttachedTarget bootstraps same-origin local routes through index.html", async () => {
  const calls = [];
  const attached = {
    cdpSessionId: "cdp-1",
    client: {
      async send(method, params = {}, sessionId) {
        calls.push({ method, params, sessionId });
        if (method === "Runtime.evaluate") {
          if (params.expression.includes("Boolean(globalThis.HYPEROPEN_DEBUG")) {
            return { result: { value: true } };
          }
          if (params.expression.includes("document.title")) {
            return { result: { value: "Trade" } };
          }
          return { result: { value: null } };
        }
        return {};
      },
      async waitForEvent(method, options = {}) {
        calls.push({ method: `waitForEvent:${method}`, params: options, sessionId: options.sessionId });
        return { method, options };
      }
    }
  };

  const session = {
    id: "sess-1",
    localApp: {
      url: "http://localhost:8080/index.html"
    }
  };

  const result = await navigateAttachedTarget(
    attached,
    session,
    "http://localhost:8080/trade?spectate=0xabc#panel",
    {
      viewport: {
        width: 1280,
        height: 720,
        mobile: false,
        deviceScaleFactor: 1
      }
    }
  );

  assert.equal(result.sessionId, "sess-1");
  assert.equal(result.url, "http://localhost:8080/trade?spectate=0xabc#panel");
  assert.equal(result.navigatedUrl, "http://localhost:8080/index.html?spectate=0xabc#panel");
  assert.equal(result.title, "Trade");

  assert.deepEqual(calls.slice(0, 5).map((call) => call.method), [
    "Page.enable",
    "Runtime.enable",
    "Emulation.setDeviceMetricsOverride",
    "waitForEvent:Page.loadEventFired",
    "Page.navigate"
  ]);

  const navigateCall = calls.find((call) => call.method === "Page.navigate");
  assert.equal(navigateCall.params.url, "http://localhost:8080/index.html?spectate=0xabc#panel");

  const runtimeExpressions = calls
    .filter((call) => call.method === "Runtime.evaluate")
    .map((call) => call.params.expression);

  assert.ok(runtimeExpressions[0].includes("HYPEROPEN_DEBUG"));
  assert.ok(runtimeExpressions[1].includes(":actions/navigate"));
  assert.ok(runtimeExpressions[1].includes("/trade?spectate=0xabc#panel"));
  assert.ok(runtimeExpressions[2].includes("waitForIdle"));
  assert.ok(runtimeExpressions[3].includes("document.title"));
});

test("navigateAttachedTarget tolerates delayed bridge availability within one wait budget", async () => {
  const calls = [];
  let bridgeChecks = 0;
  const attached = {
    cdpSessionId: "cdp-1",
    client: {
      async send(method, params = {}, sessionId) {
        calls.push({ method, params, sessionId });
        if (method === "Runtime.evaluate") {
          if (params.expression.includes("Boolean(globalThis.HYPEROPEN_DEBUG")) {
            bridgeChecks += 1;
            await new Promise((resolve) => setTimeout(resolve, 1));
            return { result: { value: bridgeChecks >= 3 } };
          }
          if (params.expression.includes("document.title")) {
            return { result: { value: "Trade" } };
          }
          return { result: { value: null } };
        }
        return {};
      },
      async waitForEvent(method, options = {}) {
        calls.push({ method: `waitForEvent:${method}`, params: options, sessionId: options.sessionId });
        return { method, options };
      }
    }
  };

  const session = {
    id: "sess-1",
    localApp: {
      url: "http://localhost:8080/index.html"
    }
  };

  await navigateAttachedTarget(attached, session, "http://localhost:8080/trade", {
    debugBridgeTimeoutMs: 20,
    debugBridgePollMs: 0,
    debugBridgeRetryCount: 0
  });

  assert.equal(calls.filter((call) => call.method === "Page.navigate").length, 1);
  assert.equal(bridgeChecks, 3);
  assert.equal(
    calls.some(
      (call) =>
        call.method === "Runtime.evaluate" && call.params.expression.includes(":actions/navigate")
    ),
    true
  );
});

test("navigateAttachedTarget retries bootstrap navigation once when the first bridge wait times out", async () => {
  const calls = [];
  let navigationCount = 0;
  let bridgeChecksForNavigation = 0;
  const attached = {
    cdpSessionId: "cdp-1",
    client: {
      async send(method, params = {}, sessionId) {
        calls.push({ method, params, sessionId });
        if (method === "Page.navigate") {
          navigationCount += 1;
          bridgeChecksForNavigation = 0;
          return {};
        }
        if (method === "Runtime.evaluate") {
          if (params.expression.includes("Boolean(globalThis.HYPEROPEN_DEBUG")) {
            bridgeChecksForNavigation += 1;
            await new Promise((resolve) =>
              setTimeout(resolve, navigationCount > 1 ? 1 : 2)
            );
            return { result: { value: navigationCount > 1 && bridgeChecksForNavigation >= 2 } };
          }
          if (params.expression.includes("document.title")) {
            return { result: { value: "Trade" } };
          }
          return { result: { value: null } };
        }
        return {};
      },
      async waitForEvent(method, options = {}) {
        calls.push({ method: `waitForEvent:${method}`, params: options, sessionId: options.sessionId });
        return { method, options };
      }
    }
  };

  const session = {
    id: "sess-1",
    localApp: {
      url: "http://localhost:8080/index.html"
    }
  };

  const result = await navigateAttachedTarget(attached, session, "http://localhost:8080/trade", {
    debugBridgeTimeoutMs: 6,
    debugBridgePollMs: 0,
    debugBridgeRetryCount: 1,
    debugBridgeRetryDelayMs: 0
  });

  assert.equal(result.navigatedUrl, "http://localhost:8080/index.html");
  const navigateCalls = calls.filter((call) => call.method === "Page.navigate");
  assert.equal(navigateCalls.length, 2);
  assert.equal(navigateCalls[0].params.url, "http://localhost:8080/index.html");
  assert.equal(navigateCalls[1].params.url, "http://localhost:8080/index.html");
  assert.equal(
    calls.some(
      (call) =>
        call.method === "Runtime.evaluate" && call.params.expression.includes(":actions/navigate")
    ),
    true
  );
});

test("navigateAttachedTarget preserves the bridge timeout error when startup never recovers", async () => {
  const calls = [];
  const attached = {
    cdpSessionId: "cdp-1",
    client: {
      async send(method, params = {}, sessionId) {
        calls.push({ method, params, sessionId });
        if (method === "Runtime.evaluate") {
          if (params.expression.includes("Boolean(globalThis.HYPEROPEN_DEBUG")) {
            await new Promise((resolve) => setTimeout(resolve, 2));
            return { result: { value: false } };
          }
          if (params.expression.includes("document.title")) {
            return { result: { value: "Trade" } };
          }
          return { result: { value: null } };
        }
        return {};
      },
      async waitForEvent(method, options = {}) {
        calls.push({ method: `waitForEvent:${method}`, params: options, sessionId: options.sessionId });
        return { method, options };
      }
    }
  };

  const session = {
    id: "sess-1",
    localApp: {
      url: "http://localhost:8080/index.html"
    }
  };

  await assert.rejects(
    navigateAttachedTarget(attached, session, "http://localhost:8080/trade", {
      debugBridgeTimeoutMs: 3,
      debugBridgePollMs: 0,
      debugBridgeRetryCount: 1,
      debugBridgeRetryDelayMs: 0
    }),
    /Timed out waiting for HYPEROPEN_DEBUG to initialize\./
  );

  assert.equal(calls.filter((call) => call.method === "Page.navigate").length, 2);
});

test("navigateAttachedTarget falls back to later managed-local candidates when an earlier bootstrap never exposes the debug bridge", async () => {
  const calls = [];
  let lastNavigatedUrl = null;
  const attached = {
    cdpSessionId: "cdp-1",
    client: {
      async send(method, params = {}, sessionId) {
        calls.push({ method, params, sessionId });
        if (method === "Page.navigate") {
          lastNavigatedUrl = params.url;
          return {};
        }
        if (method === "Runtime.evaluate") {
          if (params.expression.includes("Boolean(globalThis.HYPEROPEN_DEBUG")) {
            await new Promise((resolve) => setTimeout(resolve, 2));
            return {
              result: {
                value: lastNavigatedUrl === "http://127.0.0.1:8083/index.html"
              }
            };
          }
          if (params.expression.includes("document.title")) {
            return { result: { value: "Trade" } };
          }
          return { result: { value: null } };
        }
        return {};
      },
      async waitForEvent(method, options = {}) {
        calls.push({ method: `waitForEvent:${method}`, params: options, sessionId: options.sessionId });
        return { method, options };
      }
    }
  };

  const session = {
    id: "sess-1",
    localApp: {
      requestedUrl: "http://localhost:8080/index.html",
      url: "http://127.0.0.1:8082/index.html",
      candidateUrls: [
        "http://127.0.0.1:8082/index.html",
        "http://127.0.0.1:8083/index.html"
      ]
    }
  };

  const result = await navigateAttachedTarget(attached, session, "http://127.0.0.1:8082/trade", {
    debugBridgeTimeoutMs: 3,
    debugBridgePollMs: 0,
    debugBridgeRetryCount: 0
  });

  const navigateCalls = calls.filter((call) => call.method === "Page.navigate");
  assert.deepEqual(
    navigateCalls.map((call) => call.params.url),
    ["http://127.0.0.1:8082/index.html", "http://127.0.0.1:8083/index.html"]
  );
  assert.equal(result.navigatedUrl, "http://127.0.0.1:8083/index.html");
  assert.equal(
    calls.some(
      (call) =>
        call.method === "Runtime.evaluate" && call.params.expression.includes(":actions/navigate")
    ),
    true
  );
});
