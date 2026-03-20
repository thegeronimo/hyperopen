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
