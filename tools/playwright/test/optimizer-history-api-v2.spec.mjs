import { expect, test } from "../support/guarded_test.mjs";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import { seedOptimizerMarkets } from "../support/optimizer_state.mjs";

test("optimizer history API v2 loads rows without legacy history fanout @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.addInitScript(() => {
    globalThis.__HYPEROPEN_OPTIMIZER_HISTORY_API__ = {
      enabled: true,
      baseUrl: "https://price-history.hyperopen.xyz",
      proxyPolicy: "approved-proxy-allowed",
      includeAlignedReturns: true,
      fallbackToLegacy: false
    };
  });

  const legacyHistoryRequests = [];
  const v2Requests = [];

  await page.route("https://price-history.hyperopen.xyz/v1/optimizer/instruments", async (route) => {
    v2Requests.push({ type: "instruments" });
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        contract_version: "optimizer-history-api-v2",
        request_id: "rid-discovery-playwright",
        dataset_version: "dv-playwright",
        status: "ok",
        instruments: [
          {
            instrument_id: "hl:perp:ETH",
            display_symbol: "ETH",
            instrument_kind: "hl_perp",
            funding_enabled: true,
            aliases: { hyperopen_market_key: "perp:ETH" },
            history: {
              status: "available",
              quality_status: "passed",
              observation_count: 3
            },
            proxy: {
              available: true,
              proxy_mapping_id: "proxy-review:hl:perp:ETH",
              mapping_kind: "stitched_native_proxy",
              provider: "coingecko",
              confidence: "high",
              disclosure: "Pre-listing ETH history uses an approved proxy."
            }
          }
        ],
        warnings: []
      })
    });
  });

  await page.route("https://price-history.hyperopen.xyz/v1/optimizer/history-bundle", async (route) => {
    const payload = route.request().postDataJSON();
    v2Requests.push({ type: "history-bundle", payload });
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        contract_version: "optimizer-history-api-v2",
        request_id: "rid-history-playwright",
        dataset_version: "dv-playwright",
        status: "partial",
        common_calendar: [1000, 2000, 3000],
        return_calendar: [2000, 3000],
        aligned_returns_by_instrument: {
          "perp:ETH": {
            instrument_id: "hl:perp:ETH",
            returns: [0.04, 0.02]
          }
        },
        series_by_instrument: {
          "perp:ETH": {
            instrument_id: "hl:perp:ETH",
            lineage_kind: "stitched_native_proxy",
            series_kind: "market_price",
            points: [
              { time_ms: 1000, close: 100, return: null, component: "proxy" },
              { time_ms: 2000, close: 104, return: 0.04, component: "native" },
              { time_ms: 3000, close: 106.08, return: 0.02, component: "native" }
            ],
            funding: {
              status: "available",
              source: "hyperliquid:fundingHistory",
              annualized_carry: 0.012
            },
            proxy: {
              proxy_mapping_id: "proxy-review:hl:perp:ETH",
              disclosure: "Pre-listing ETH history uses an approved proxy."
            },
            warnings: [{ code: "proxy-history-used", instrument_id: "perp:ETH" }]
          }
        },
        warnings: []
      })
    });
  });

  await page.route("**/info", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      try {
        const payload = request.postDataJSON();
        if (payload?.type === "candleSnapshot" || payload?.type === "fundingHistory") {
          legacyHistoryRequests.push(payload);
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

  await seedOptimizerMarkets(page, [
    {
      key: "perp:ETH",
      "market-type": "perp",
      coin: "ETH",
      symbol: "ETH-USDC",
      name: "Ether"
    }
  ]);
  legacyHistoryRequests.length = 0;

  await page.locator("[data-role='portfolio-optimizer-universe-search-input']").fill("eth");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:ETH']").click();

  await expect(page.locator("[data-role='portfolio-optimizer-universe-selected-row-perp:ETH']"))
    .toHaveAttribute("data-history-status", "sufficient", { timeout: 10_000 });
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .toContainText("approved substitute history", { timeout: 10_000 });

  expect(v2Requests.some((entry) => entry.type === "instruments")).toBe(true);
  const historyRequest = v2Requests.find((entry) => entry.type === "history-bundle");
  expect(historyRequest?.payload?.proxy_policy).toBe("approved_proxy_allowed");
  expect(historyRequest?.payload?.include_aligned_returns).toBe(true);
  expect(historyRequest?.payload?.instruments).toEqual([
    {
      client_instrument_id: "perp:ETH",
      instrument_id: "hl:perp:ETH"
    }
  ]);
  expect(legacyHistoryRequests).toEqual([]);
});

test("optimizer history API v2 uses aligned returns when point rows are sparse @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.addInitScript(() => {
    globalThis.__HYPEROPEN_OPTIMIZER_HISTORY_API__ = {
      enabled: true,
      baseUrl: "https://price-history.hyperopen.xyz",
      proxyPolicy: "approved-proxy-allowed",
      includeAlignedReturns: true,
      fallbackToLegacy: false
    };
  });

  const legacyHistoryRequests = [];
  const v2Requests = [];

  await page.route("https://price-history.hyperopen.xyz/v1/optimizer/instruments", async (route) => {
    v2Requests.push({ type: "instruments" });
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        contract_version: "optimizer-history-api-v2",
        request_id: "rid-discovery-return-first",
        dataset_version: "dv-return-first",
        status: "ok",
        instruments: [
          {
            instrument_id: "hl:perp:ETH",
            display_symbol: "ETH",
            instrument_kind: "hl_perp",
            funding_enabled: true,
            aliases: { hyperopen_market_key: "perp:ETH" },
            history: { status: "available", quality_status: "passed", observation_count: 2 }
          },
          {
            instrument_id: "hl:perp:BTC",
            display_symbol: "BTC",
            instrument_kind: "hl_perp",
            funding_enabled: true,
            aliases: { hyperopen_market_key: "perp:BTC" },
            history: { status: "available", quality_status: "passed", observation_count: 2 },
            proxy: {
              available: true,
              proxy_mapping_id: "proxy-review:hl:perp:BTC",
              mapping_kind: "stitched_native_proxy",
              provider: "coingecko",
              confidence: "high"
            }
          }
        ],
        warnings: []
      })
    });
  });

  const seriesDefinitions = {
    "perp:ETH": {
      instrument_id: "hl:perp:ETH",
      lineage_kind: "native",
      series_kind: "market_price",
      points: [{ time_ms: 3000, close: 2200, return: 0.03 }],
      funding: { status: "available", annualized_carry: 0.01 },
      warnings: [{ code: "insufficient-candle-history", instrument_id: "hl:perp:ETH" }]
    },
    "perp:BTC": {
      instrument_id: "hl:perp:BTC",
      lineage_kind: "stitched_native_proxy",
      series_kind: "market_price",
      points: [],
      funding: { status: "available", annualized_carry: 0.002 },
      warnings: [{ code: "missing-candle-history", instrument_id: "hl:perp:BTC" }]
    }
  };
  const batchedReturnDefinitions = {
    "perp:ETH": { instrument_id: "hl:perp:ETH", returns: [0.02, 0.03] },
    "perp:BTC": { instrument_id: "hl:perp:BTC", returns: [-0.01, 0.04] }
  };
  const singleAssetReturnDefinitions = {
    "perp:ETH": { instrument_id: "hl:perp:ETH", returns: [0.02, 0.03] },
    "perp:BTC": { instrument_id: "hl:perp:BTC", returns: [0.01, -0.01, 0.04] }
  };

  await page.route("https://price-history.hyperopen.xyz/v1/optimizer/history-bundle", async (route) => {
    const payload = route.request().postDataJSON();
    v2Requests.push({ type: "history-bundle", payload });
    const requestedIds = payload.instruments.map((instrument) => instrument.client_instrument_id);
    const batched = requestedIds.length > 1;
    const returnDefinitions = batched ? batchedReturnDefinitions : singleAssetReturnDefinitions;
    const returnCalendar = batched || requestedIds[0] !== "perp:BTC"
      ? [2000, 3000]
      : [1000, 2000, 3000];
    const responseKey = (instrumentId) => (
      instrumentId === "perp:BTC" ? seriesDefinitions[instrumentId].instrument_id : instrumentId
    );
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        contract_version: "optimizer-history-api-v2",
        request_id: "rid-history-return-first",
        dataset_version: "dv-return-first",
        status: "partial",
        common_calendar: batched || requestedIds[0] !== "perp:BTC"
          ? [1000, 2000, 3000]
          : [0, 1000, 2000, 3000],
        return_calendar: returnCalendar,
        aligned_returns_by_instrument: Object.fromEntries(
          requestedIds.map((instrumentId) => [responseKey(instrumentId), returnDefinitions[instrumentId]])
        ),
        series_by_instrument: Object.fromEntries(
          requestedIds.map((instrumentId) => [responseKey(instrumentId), seriesDefinitions[instrumentId]])
        ),
        warnings: []
      })
    });
  });

  await page.route("**/info", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      try {
        const payload = request.postDataJSON();
        if (payload?.type === "candleSnapshot" || payload?.type === "fundingHistory") {
          legacyHistoryRequests.push(payload);
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

  await seedOptimizerMarkets(page, [
    {
      key: "perp:ETH",
      "market-type": "perp",
      coin: "ETH",
      symbol: "ETH-USDC",
      name: "Ether"
    },
    {
      key: "perp:BTC",
      "market-type": "perp",
      coin: "BTC",
      symbol: "BTC-USDC",
      name: "Bitcoin"
    }
  ]);
  legacyHistoryRequests.length = 0;

  await page.locator("[data-role='portfolio-optimizer-universe-search-input']").fill("btc");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:BTC']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-universe-selected-row-perp:BTC']"))
    .toHaveAttribute("data-history-status", "sufficient", { timeout: 10_000 });

  await page.locator("[data-role='portfolio-optimizer-universe-search-input']").fill("eth");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:ETH']").click();

  await expect(page.locator("[data-role='portfolio-optimizer-universe-selected-row-perp:BTC']"))
    .toHaveAttribute("data-history-status", "sufficient", { timeout: 10_000 });
  await expect(page.locator("[data-role='portfolio-optimizer-universe-selected-row-perp:ETH']"))
    .toHaveAttribute("data-history-status", "sufficient", { timeout: 10_000 });
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .not.toContainText("candle history", { timeout: 10_000 });
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .not.toContainText("return history", { timeout: 10_000 });

  const historyRequests = v2Requests.filter((entry) => entry.type === "history-bundle");
  expect(historyRequests.length).toBeGreaterThan(0);
  for (const historyRequest of historyRequests) {
    expect(historyRequest.payload.proxy_policy).toBe("approved_proxy_allowed");
    expect(historyRequest.payload.include_aligned_returns).toBe(true);
  }
  expect(historyRequests.flatMap((entry) => entry.payload.instruments)).toEqual(
    expect.arrayContaining([
      {
        client_instrument_id: "perp:ETH",
        instrument_id: "hl:perp:ETH"
      },
      {
        client_instrument_id: "perp:BTC",
        instrument_id: "hl:perp:BTC"
      }
    ])
  );
  expect(historyRequests.at(-1).payload.instruments).toEqual([
    {
      client_instrument_id: "perp:BTC",
      instrument_id: "hl:perp:BTC"
    },
    {
      client_instrument_id: "perp:ETH",
      instrument_id: "hl:perp:ETH"
    }
  ]);
  expect(legacyHistoryRequests).toEqual([]);
});
