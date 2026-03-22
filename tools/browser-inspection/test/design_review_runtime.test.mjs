import test from "node:test";
import assert from "node:assert/strict";
import { createDesignReviewRuntime } from "../src/design_review/runtime.mjs";

test("interactionTrace probe passes target settleDelay override into the probe expression", async () => {
  const evaluateCalls = [];
  const service = {
    config: { masking: { selectors: [] } },
    sessionManager: { store: {} },
    async evaluate(payload) {
      evaluateCalls.push(payload);
      return {
        result: {
          performanceObserverSupported: true,
          layoutShiftValue: 0,
          maxLongTaskMs: 0
        }
      };
    }
  };

  const runtime = createDesignReviewRuntime(service);
  const result = await runtime.probeGateway.interactionTrace("session-1", {
    target: {
      selectors: [".scope"],
      interactionTrace: {
        settleDelayMs: 5000
      }
    },
    designConfig: {
      interactionTrace: {
        focusLimit: 4,
        scrollFractions: [0, 0.5, 0],
        delayMs: 90,
        settleDelayMs: 0
      }
    }
  });

  assert.equal(result.ok, true);
  assert.equal(evaluateCalls.length, 1);
  assert.match(evaluateCalls[0].expression, /const settleDelayMs = 5000;/);
  assert.match(evaluateCalls[0].expression, /await sleep\(settleDelayMs\);/);
});
