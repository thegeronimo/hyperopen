// Proxy-workflow loading visibility: while the history-bundle request is in
// flight, the assumption card chip, the section banner, and the right-rail row
// must all say "Loading history…" instead of passing provisional verdicts
// ("Needs input" / premature "Configured") off as final — and settle to the
// honest labels once the response lands. The bundle response is held open by
// the test and released explicitly, so the in-flight window is deterministic.
import { expect, test } from "../support/guarded_test.mjs";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import {
  keyword,
  optimizerPath,
  seedOptimizerMarkets,
  seedOptimizerState,
  seedPatch,
  stringMap
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

test("portfolio optimizer proxy workflow surfaces in-flight history loading @regression", async ({ page }) => {
  test.setTimeout(120_000);

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
        request_id: "rid-discovery-loading-ux",
        dataset_version: "dv-loading-ux",
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
            history: { status: "missing", quality_status: "failed", observation_count: 0 }
          }
        ],
        warnings: []
      })
    });
  });

  // Hold every history-bundle response until the test releases the gate — this
  // IS the in-flight window the loading UI must cover.
  let releaseBundle;
  const bundleGate = new Promise((resolve) => {
    releaseBundle = resolve;
  });
  let bundleRequests = 0;
  const calendar = Array.from({ length: 400 }, (_unused, i) => (i + 1) * DAY_MS);
  await page.route("https://price-history.hyperopen.xyz/v1/optimizer/history-bundle", async (route) => {
    bundleRequests += 1;
    await bundleGate;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        contract_version: "optimizer-history-api-v2",
        request_id: "rid-history-loading-ux",
        dataset_version: "dv-loading-ux",
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

  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']"))
    .toBeVisible({ timeout: 60_000 });

  await seedOptimizerMarkets(page, [
    { key: "perp:BTC", "market-type": "perp", coin: "BTC", symbol: "BTC-USDC", name: "Bitcoin" },
    { key: "perp:WLFI", "market-type": "perp", coin: "WLFI", symbol: "WLFI-USDC", name: "World Liberty" }
  ]);

  // A hydrated proxy entry (the assumption-library case the maintainer hit):
  // complete except that its proxy's usable history hasn't loaded yet.
  await seedOptimizerState(page, [
    seedPatch(
      optimizerPath("draft", "history-assumptions"),
      stringMap([["perp:WLFI", {
        behavior: keyword("proxy"),
        "expected-return": 0,
        volatility: 0.8,
        "max-weight": 0.05,
        proxy: {
          "instrument-ids": ["perp:BTC"],
          "relationship-strength": keyword("medium"),
          "prior-weights": null
        }
      }]])
    )
  ]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  // Adding the assets kicks off the (held) history load.
  await page.locator("[data-role='portfolio-optimizer-universe-search-input']").fill("wlfi");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:WLFI']").click();
  await page.locator("[data-role='portfolio-optimizer-universe-search-input']").fill("btc");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:BTC']").click();

  // --- In flight: every surface says loading, none passes a verdict.
  const chip = page.locator("[data-role='portfolio-optimizer-history-assumption-status-perp:WLFI']");
  await expect(chip).toHaveAttribute("data-loading", "true", { timeout: 15_000 });
  await expect(chip).toContainText("Loading history");
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumptions-loading-banner']"))
    .toBeVisible();
  const railStatus = page.locator("[data-role='portfolio-optimizer-history-assumptions-rail-status-perp:WLFI']");
  await expect(railStatus).toHaveAttribute("data-loading", "true");
  await expect(railStatus).toContainText("Loading history");
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-apply-perp:WLFI']"))
    .toBeDisabled();
  // The rail header count must not pass a configured-verdict mid-flight (the
  // old all-configured ready banner is gone; the count carries the gate now).
  const railCount = page.locator("[data-role='portfolio-optimizer-history-assumptions-rail-count']");
  await expect(railCount).toHaveAttribute("data-loading", "true");
  await expect(railCount).toContainText("Loading history");
  expect(bundleRequests).toBeGreaterThan(0);

  // --- Release the response: the provisional loading labels settle to the
  // honest verdicts (BTC's 400-day history makes the WLFI entry complete).
  releaseBundle();

  await expect(chip).toHaveAttribute("data-status", "configured", { timeout: 20_000 });
  await expect(chip).not.toHaveAttribute("data-loading", "true");
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumptions-loading-banner']"))
    .toHaveCount(0);
  // Configured settles to the glyph chip (aria-label "Configured") and the
  // header count states the aggregate once.
  await expect(railStatus).toContainText("✓");
  await expect(railStatus).not.toHaveAttribute("data-loading", "true");
  await expect(railCount).toContainText("1 of 1 configured");
  await expect(railCount).not.toHaveAttribute("data-loading", "true");
});
