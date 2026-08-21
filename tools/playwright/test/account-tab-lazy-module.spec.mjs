import { expect, test } from "@playwright/test";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";

// Regression guard for the account panel's lazy tab chunks.
//
// Each account tab except Balances loads its code from a separate chunk on first
// click. The panel is memoized on a fixed slice of app-db, so it can only learn
// that a chunk arrived if the loader's state key is inside that slice. When it
// was not, the panel kept returning its cached pending state and only repainted
// when some unrelated key changed -- in practice the next websocket push.
//
// Blocking the exchange websocket is what makes this a real red/green boundary.
// Throttling the network only makes the wait longer; the tab still resolves on
// the next market tick, so a throttled test passes with or without the fix.
// With no websocket traffic at all, the pre-fix panel hangs forever.
//
// The block is scoped to the exchange endpoint on purpose: shadow-cljs serves
// the dev build over its own websocket, and blocking that one stops the bundle
// from ever settling.
async function blockExchangeWebsocket(page) {
  await page.routeWebSocket("wss://api.hyperliquid.xyz/**", () => {
    // Accept the connection and never deliver a frame.
  });
}

async function selectAccountTab(page, tabValue) {
  const tab = page.locator(`[data-role='account-info-tab-${tabValue}']`);
  await tab.click();
  await expect(tab).toHaveAttribute("aria-pressed", "true");
}

const LAZY_TABS = [
  "positions",
  "outcomes",
  "open-orders",
  "twap",
  "trade-history"
];

test.describe("account tab lazy modules", () => {
  test("every lazy account tab renders with no websocket traffic @regression", async ({
    page
  }) => {
    await blockExchangeWebsocket(page);
    await visitRoute(page, "/trade");

    const pending = page.locator("[data-role='account-tab-loading']");

    for (const tabValue of LAZY_TABS) {
      await selectAccountTab(page, tabValue);
      // The chunk itself lands in tens of milliseconds; the generous timeout is
      // for the dev build's unminified payload, not for the repaint.
      await expect(
        pending,
        `${tabValue} never left its pending state without websocket traffic`
      ).toHaveCount(0, { timeout: 10_000 });
      await expect(page.locator("[data-parity-id='account-tables']")).toBeVisible();
    }
  });

  test("the pending state names the tab it is loading @regression", async ({
    page
  }) => {
    await blockExchangeWebsocket(page);
    // Hold the chunk back long enough to observe the pending state, then let it
    // through so the tab still resolves.
    await page.route(/\/js\/account_orders(\.[A-Z0-9]+)?\.js/, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 1_500));
      await route.continue();
    });
    await visitRoute(page, "/trade");

    const tab = page.locator("[data-role='account-info-tab-open-orders']");
    await tab.click();

    const pending = page.locator("[data-role='account-tab-loading']");
    await expect(pending).toBeVisible({ timeout: 5_000 });
    await expect(pending).toHaveAttribute("role", "status");
    await expect(pending).toHaveAttribute("aria-live", "polite");
    await expect(pending).toContainText("Loading Open Orders");

    await expect(pending).toHaveCount(0, { timeout: 15_000 });
    await waitForIdle(page, { quietMs: 150, timeoutMs: 5_000, pollMs: 50 });
  });

  test("a chunk that never arrives reports a retryable error @regression", async ({
    page
  }) => {
    test.setTimeout(60_000);
    await blockExchangeWebsocket(page);

    // Abort the chunk outright. shadow's module loader does not reject on its
    // own, so without the timeout in load-account-tab-module! the panel would
    // sit on its pending state forever and this test would hang.
    let blocked = true;
    await page.route(/\/js\/account_orders(\.[A-Z0-9]+)?\.js/, async (route) => {
      if (blocked) {
        await route.abort();
        return;
      }
      await route.continue();
    });
    await visitRoute(page, "/trade");

    await page.locator("[data-role='account-info-tab-open-orders']").click();

    const failure = page.locator("[data-role='account-tab-error']");
    await expect(failure).toBeVisible({ timeout: 20_000 });
    await expect(failure).toContainText("Couldn't load Open Orders");

    // Let the chunk through and retry: re-selecting the tab re-fires the load.
    blocked = false;
    await page.locator("[data-role='account-tab-error-retry']").click();
    await expect(failure).toHaveCount(0, { timeout: 15_000 });
    await expect(page.locator("[data-role='account-tab-loading']")).toHaveCount(0, {
      timeout: 15_000
    });
  });
});
