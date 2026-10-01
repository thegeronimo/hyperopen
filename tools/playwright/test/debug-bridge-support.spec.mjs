import { expect, test } from "../support/guarded_test.mjs";

import { waitForDebugBridge } from "../support/hyperopen.mjs";

test("waitForDebugBridge reports page diagnostics on timeout", async () => {
  const fakePage = {
    url: () => "http://127.0.0.1:8080/index.html",
    title: async () => "Loading HyperOpen",
    evaluate: async (fn) => {
      if (typeof fn === "function") {
        return fn();
      }
      return null;
    }
  };

  await expect(waitForDebugBridge(fakePage, 40, { pollMs: 5 })).rejects.toThrow(
    /document\.readyState/
  );
});
