import { expect, test } from "../support/guarded_test.mjs";
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

function apiV2HistoryBundleResponse(payload, requestId = "rid-history-network-prefetch") {
  const requestedIds = payload.instruments.map((instrument) => instrument.client_instrument_id);
  const baseByInstrument = {
    "perp:BTC": 100,
    "perp:ETH": 200
  };
  const seriesFor = (instrumentId) => {
    const base = baseByInstrument[instrumentId] || 100;
    return {
      instrument_id: `hl:${instrumentId}`,
      lineage_kind: "native",
      series_kind: "market_price",
      points: [
        { time_ms: 1000, close: base, return: null, component: "native" },
        { time_ms: 2000, close: base * 1.04, return: 0.04, component: "native" },
        { time_ms: 3000, close: base * 1.0608, return: 0.02, component: "native" }
      ],
      funding: {
        status: "available",
        source: "hyperliquid:fundingHistory",
        annualized_carry: 0.012
      },
      warnings: []
    };
  };

  return {
    contract_version: "optimizer-history-api-v2",
    request_id: requestId,
    dataset_version: "dv-history-network-prefetch",
    status: "ok",
    common_calendar: [1000, 2000, 3000],
    return_calendar: [2000, 3000],
    aligned_returns_by_instrument: Object.fromEntries(
      requestedIds.map((instrumentId) => [
        instrumentId,
        { instrument_id: `hl:${instrumentId}`, returns: [0.04, 0.02] }
      ])
    ),
    series_by_instrument: Object.fromEntries(
      requestedIds.map((instrumentId) => [instrumentId, seriesFor(instrumentId)])
    ),
    warnings: []
  };
}

test("portfolio optimizer adding an asset prefetches API v2 history before run @regression", async ({ page }) => {
  test.setTimeout(90_000);

  const seenHistoryBundles = [];
  const seenLegacyHistory = [];
  await page.route("https://price-history.hyperopen.xyz/v1/optimizer/history-bundle", async (route) => {
    const payload = route.request().postDataJSON();
    seenHistoryBundles.push(payload);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(apiV2HistoryBundleResponse(payload))
    });
  });
  await page.route("**/info", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      try {
        const payload = request.postDataJSON();
        if (payload?.type === "candleSnapshot") {
          seenLegacyHistory.push({ type: payload.type, coin: payload.req?.coin });
        }
        if (payload?.type === "fundingHistory") {
          seenLegacyHistory.push({ type: payload.type, coin: payload.coin });
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
  seenHistoryBundles.length = 0;
  seenLegacyHistory.length = 0;

  await page.locator("[data-role='portfolio-optimizer-universe-search-input']").fill("eth");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:ETH']").click();
  await expect.poll(
    () => seenHistoryBundles.some((payload) =>
      payload.instruments?.some((instrument) => instrument.client_instrument_id === "perp:ETH")
    ),
    { timeout: 10_000 }
  ).toBe(true);
  await expect(page.locator("[data-role='portfolio-optimizer-universe-selected-row-perp:ETH']"))
    .toHaveAttribute("data-history-status", "sufficient", { timeout: 10_000 });

  const beforeRun = [...seenHistoryBundles];
  await expect(page.locator("[data-role='portfolio-optimizer-load-history']")).toHaveCount(0);
  await page.locator("[data-role='portfolio-optimizer-run-draft']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-progress-panel']"))
    .toContainText("Optimization", { timeout: 10_000 });
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .toContainText("Optimizer history is loaded for the selected assets.", { timeout: 10_000 });

  expect(seenLegacyHistory.filter((entry) => entry.coin === "ETH")).toEqual([]);
  expect(beforeRun.length).toBeGreaterThan(0);
  expect(seenHistoryBundles).toEqual(beforeRun);
});
