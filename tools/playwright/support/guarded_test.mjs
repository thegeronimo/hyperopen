// The `test` every Playwright spec imports (never `@playwright/test`'s own;
// `spec_imports.test.mjs` enforces it).
//
// It adds one automatic fixture, `hyperEvmRpcGuard`
// (`hyperevm_rpc_guard.mjs`): the test's browser context, and every context
// it opens through `browser.newContext()`, answers the HyperEVM RPC from an
// empty mock, and the test fails at teardown if any request reached the
// public RPC host without the mock answering it.
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
