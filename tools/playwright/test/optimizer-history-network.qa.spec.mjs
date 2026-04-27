import { expect, test } from "@playwright/test";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";

async function seedMarkets(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const market = (key, marketType, coin, symbol, dex = null) => {
      const entries = [
        kw("key"), key,
        kw("market-type"), kw(marketType),
        kw("coin"), coin,
        kw("symbol"), symbol
      ];
      if (dex) entries.push(kw("dex"), dex);
      return c.PersistentArrayMap.fromArray(entries, true);
    };
    const btc = market("perp:BTC", "perp", "BTC", "BTC-USDC", "hl");
    const eth = market("perp:ETH", "perp", "ETH", "ETH-USDC", "hl");
    const markets = c.PersistentVector.fromArray([btc, eth], true);
    const marketByKey = c.PersistentArrayMap.fromArray(
      ["perp:BTC", btc, "perp:ETH", eth],
      true
    );
    const store = globalThis.hyperopen.system.store;
    let state = c.deref(store);
    state = c.assoc_in(
      state,
      c.PersistentVector.fromArray([kw("asset-selector"), kw("markets")], true),
      markets
    );
    state = c.assoc_in(
      state,
      c.PersistentVector.fromArray([kw("asset-selector"), kw("market-by-key")], true),
      marketByKey
    );
    c.reset_BANG_(store, state);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

test("portfolio optimizer adding an asset does not fetch history until load @regression", async ({ page }) => {
  test.setTimeout(90_000);

  const seen = [];
  await page.route("**/info", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      try {
        const payload = request.postDataJSON();
        if (payload?.type === "candleSnapshot") {
          seen.push({ type: payload.type, coin: payload.req?.coin });
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify([
              { t: 1000, c: "100" },
              { t: 2000, c: "110" }
            ])
          });
          return;
        }
        if (payload?.type === "fundingHistory") {
          seen.push({ type: payload.type, coin: payload.coin });
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify([{ time: 1000, fundingRate: "0.0001" }])
          });
          return;
        }
      } catch {
        // Let non-JSON requests continue.
      }
    }
    await route.continue();
  });

  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']"))
    .toBeVisible({ timeout: 60_000 });
  await seedMarkets(page);
  seen.length = 0;

  await page.locator("[data-role='portfolio-optimizer-universe-search-input']").fill("eth");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:ETH']").click();
  await waitForIdle(page, { quietMs: 300, timeoutMs: 4_000, pollMs: 50 });

  const afterAddCount = seen.length;
  await page.locator("[data-role='portfolio-optimizer-load-history']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .toContainText("Optimizer history is loaded.");

  expect(afterAddCount).toBe(0);
  expect(seen).toEqual([
    { type: "candleSnapshot", coin: "ETH" },
    { type: "fundingHistory", coin: "ETH" }
  ]);
});
