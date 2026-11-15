import { expect, test as base } from "@playwright/test";
import { createHyperEvmRpcGuard } from "./hyperevm_rpc_guard.mjs";

export { expect };

export const test = base.extend({
  hyperEvmRpcGuard: [
    async ({ browser, context }, use) => {
      const guard = createHyperEvmRpcGuard();
      await guard.protectContext(context);
      const restoreBrowser = guard.patchBrowser(browser);
      try {
        await use(guard);
      } finally {
        restoreBrowser();
      }
      await guard.assertClean();
    },
    { auto: true }
  ]
});
