// Backend-recommended history assumptions, beyond the center banner: the
// right-rail panel carries its own "Apply all recommended (N)" shortcut, and a
// "Run optimization" click on a draft still blocked by missing assumptions
// applies every pending recommendation first (same bulk funnel) instead of
// dead-ending in the pipeline's readiness failure. The discovery and
// history-bundle endpoints are stubbed, so both flows are deterministic.
import { expect, test } from "../support/guarded_test.mjs";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import {
  optimizerPath,
  seedOptimizerMarkets,
  readOptimizerState,
  stateKey
} from "../support/optimizer_state.mjs";

const DAY_MS = 24 * 60 * 60 * 1000;

function btcSeries() {
  const points = Array.from({ length: 400 }, (_unused, i) => ({
    time_ms: (i + 1) * DAY_MS,
    close: 100 + (i % 7),
    return: i === 0 ? null : 0.001
  }));
  return {
    instrument_id: "hl:perp:BTC",
    lineage_kind: "native",
    series_kind: "market_price",
    points,
    funding: { status: "available", annualized_carry: 0.01 },
    warnings: []
  };
}

// BTC anchors with a full 400-day native history; WLFI has none, sits below
// the assumption-required threshold (so readiness blocks the run), and carries
// a conservative default-assumption the one-click flows can apply.
async function stubHistoryApi(page) {
  await page.addInitScript(() => {
    globalThis.__HYPEROPEN_OPTIMIZER_HISTORY_API__ = {
      enabled: true,
      baseUrl: "https://price-history.hyperopen.xyz",
      proxyPolicy: "approved-proxy-allowed",
      includeAlignedReturns: true,
      fallbackToLegacy: false
    };
  });

  await page.route("https://price-history.hyperopen.xyz/v1/optimizer/instruments", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        contract_version: "optimizer-history-api-v2",
        request_id: "rid-discovery-recommended",
        dataset_version: "dv-recommended",
        status: "ok",
        instruments: [
          {
            instrument_id: "hl:perp:BTC",
            display_symbol: "BTC",
            instrument_kind: "hl_perp",
            funding_enabled: true,
            aliases: { hyperopen_market_key: "perp:BTC" },
            history: { status: "available", quality_status: "passed", observation_count: 400 }
          },
          {
            instrument_id: "hl:perp:WLFI",
            display_symbol: "WLFI",
            instrument_kind: "hl_perp",
            funding_enabled: true,
            aliases: { hyperopen_market_key: "perp:WLFI" },
            history: { status: "missing", quality_status: "failed", observation_count: 0 },
            default_assumption: {
              approach: "conservative",
              members: [],
              rationale: "No defensible basket exists for this listing yet."
            }
          }
        ],
        warnings: []
      })
    });
  });

  const calendar = Array.from({ length: 400 }, (_unused, i) => (i + 1) * DAY_MS);
  await page.route("https://price-history.hyperopen.xyz/v1/optimizer/history-bundle", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        contract_version: "optimizer-history-api-v2",
        request_id: "rid-history-recommended",
        dataset_version: "dv-recommended",
        status: "partial",
        common_calendar: calendar,
        return_calendar: calendar.slice(1),
        aligned_returns_by_instrument: {
          "perp:BTC": {
            instrument_id: "hl:perp:BTC",
            returns: Array.from({ length: 399 }, () => 0.001)
          }
        },
        series_by_instrument: { "perp:BTC": btcSeries() },
        warnings: [{ code: "missing-candle-history", instrument_id: "perp:WLFI" }]
      })
    });
  });
}

async function seedRecommendedWorkflow(page) {
  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']"))
    .toBeVisible({ timeout: 60_000 });

  await seedOptimizerMarkets(page, [
    { key: "perp:BTC", "market-type": "perp", coin: "BTC", symbol: "BTC-USDC", name: "Bitcoin" },
    { key: "perp:WLFI", "market-type": "perp", coin: "WLFI", symbol: "WLFI-USDC", name: "World Liberty" }
  ]);

  // Real universe adds so the history load, load-state, and adequacy all flow
  // through production paths — WLFI settles as the one pending workflow asset.
  await page.locator("[data-role='portfolio-optimizer-universe-search-input']").fill("wlfi");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:WLFI']").click();
  await page.locator("[data-role='portfolio-optimizer-universe-search-input']").fill("btc");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:BTC']").click();

  const railCount = page.locator("[data-role='portfolio-optimizer-history-assumptions-rail-count']");
  await expect(railCount).toContainText("0 of 1 configured", { timeout: 20_000 });
  return railCount;
}

test("portfolio optimizer rail offers apply-all-recommended beside the configured count @regression", async ({ page }) => {
  test.setTimeout(120_000);
  await stubHistoryApi(page);
  const railCount = await seedRecommendedWorkflow(page);

  const railButton = page.locator(
    "[data-role='portfolio-optimizer-history-assumptions-rail-apply-all-recommended']"
  );
  await expect(railButton).toHaveText("Apply all recommended (1)");

  await railButton.click();

  await expect(railCount).toContainText("1 of 1 configured", { timeout: 20_000 });
  await expect(railButton).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumptions-rail-status-perp:WLFI']"))
    .toContainText("✓");
});

test("portfolio optimizer run click auto-applies pending recommendations instead of failing @regression", async ({ page }) => {
  test.setTimeout(120_000);
  await stubHistoryApi(page);
  const railCount = await seedRecommendedWorkflow(page);

  await page.locator("[data-role='portfolio-optimizer-run-draft']").click();

  // The auto-apply projections land at dispatch, before the pipeline effect:
  // the rail flips to configured and the draft carries the acknowledged
  // backend-recommended entry, so the run proceeds instead of failing on
  // missing assumptions.
  await expect(railCount).toContainText("1 of 1 configured", { timeout: 20_000 });
  await expect(page.locator(
    "[data-role='portfolio-optimizer-history-assumptions-rail-apply-all-recommended']"
  )).toHaveCount(0);

  const wlfi = await readOptimizerState(
    page,
    optimizerPath("draft", "history-assumptions", stateKey("perp:WLFI"))
  );
  expect(wlfi.behavior).toBe("conservative");
  expect(wlfi.metadata.source).toBe("backend-recommendation");
  expect(wlfi.metadata["acknowledged?"]).toBe(true);
});
