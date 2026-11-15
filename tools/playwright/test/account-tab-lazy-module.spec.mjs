import { expect, test } from "../support/guarded_test.mjs";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";

async function blockExchangeWebsocket(page) {
  await page.routeWebSocket("wss://api.hyperliquid.xyz/**", () => {
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

    blocked = false;
    await page.locator("[data-role='account-tab-error-retry']").click();
    await expect(failure).toHaveCount(0, { timeout: 15_000 });
    await expect(page.locator("[data-role='account-tab-loading']")).toHaveCount(0, {
      timeout: 15_000
    });
  });
});
