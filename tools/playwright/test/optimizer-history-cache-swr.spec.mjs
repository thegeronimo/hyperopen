// History-bundle stale-while-revalidate: after one settled visit persists the
// per-wallet bundle (history-bundle::<addr>) and draft to IndexedDB, a fresh
// page load must configure the proxy-assumption cards from the CACHE while the
// history-bundle network request is still held open — the background refresh
// replaces the data when it lands, but the user never stares at "Loading
// history…" for data their browser already has.
import { expect, test } from "../support/guarded_test.mjs";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import {
  keyword,
  optimizerPath,
  readOptimizerState,
  seedOptimizerMarkets,
  seedOptimizerState,
  seedPatch,
  stringMap
} from "../support/optimizer_state.mjs";

const DAY_MS = 24 * 60 * 60 * 1000;
const SPECTATE_ADDRESS = "0x1111111111111111111111111111111111111111";

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

async function installApiRoutes(page, holdState) {
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
        request_id: "rid-discovery-swr",
        dataset_version: "dv-swr",
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
  const calendar = Array.from({ length: 400 }, (_unused, i) => (i + 1) * DAY_MS);
  await page.route("https://price-history.hyperopen.xyz/v1/optimizer/history-bundle", async (route) => {
    holdState.bundleRequests += 1;
    if (holdState.hold) {
      await holdState.gate;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        contract_version: "optimizer-history-api-v2",
        request_id: "rid-history-swr",
        dataset_version: "dv-swr",
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

function seedSpectateIdentity(page) {
  return seedOptimizerState(page, [
    seedPatch(
      ["account-context", "spectate-mode"],
      {
        "active?": true,
        address: SPECTATE_ADDRESS,
        "started-at-ms": 1777046090000
      }
    )
  ]);
}

function readIdbRecord(page, key) {
  return page.evaluate(
    (recordKey) =>
      new Promise((resolve) => {
        const open = indexedDB.open("hyperopen-persistence");
        open.onsuccess = () => {
          const db = open.result;
          try {
            const tx = db.transaction("portfolio-optimizer", "readonly");
            const get = tx.objectStore("portfolio-optimizer").get(recordKey);
            get.onsuccess = () => {
              resolve(get.result ?? null);
              db.close();
            };
            get.onerror = () => {
              resolve(null);
              db.close();
            };
          } catch {
            resolve(null);
            db.close();
          }
        };
        open.onerror = () => resolve(null);
      }),
    key
  );
}

test("portfolio optimizer hydrates history assumptions from the cached bundle before the refresh lands @regression", async ({ page }) => {
  test.setTimeout(150_000);

  const holdState = { hold: false, bundleRequests: 0, gate: null };
  let releaseGate;
  holdState.gate = new Promise((resolve) => {
    releaseGate = resolve;
  });
  await installApiRoutes(page, holdState);

  // --- Phase 1: a normal settled visit warms the per-wallet cache.
  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']"))
    .toBeVisible({ timeout: 60_000 });
  // Identity FIRST: the draft autosave and cache records key off the wallet.
  await seedSpectateIdentity(page);
  await seedOptimizerMarkets(page, [
    { key: "perp:BTC", "market-type": "perp", coin: "BTC", symbol: "BTC-USDC", name: "Bitcoin" },
    { key: "perp:WLFI", "market-type": "perp", coin: "WLFI", symbol: "WLFI-USDC", name: "World Liberty" }
  ]);
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

  await page.locator("[data-role='portfolio-optimizer-universe-search-input']").fill("wlfi");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:WLFI']").click();
  await page.locator("[data-role='portfolio-optimizer-universe-search-input']").fill("btc");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  const btcAddedAtMs = await page.evaluate(() => Date.now());
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:BTC']").click();

  const chip = page.locator("[data-role='portfolio-optimizer-history-assumption-status-perp:WLFI']");
  await expect(chip).toHaveAttribute("data-status", "configured", { timeout: 30_000 });

  // The debounced autosave watcher resets its timer on EVERY draft edit, so an
  // earlier flush (e.g. right after adding WLFI) can already have written a
  // "draft::" record before BTC is even added — merely checking the record
  // exists races ahead of the LATEST edit's write. `draft-persist` records the
  // real flush timestamp, so wait for one at or after BTC's own addition.
  await expect
    .poll(() => readOptimizerState(page, ["portfolio", "optimizer", "draft-persist", "at-ms"]),
          { timeout: 20_000 })
    .toBeGreaterThanOrEqual(btcAddedAtMs);
  await expect
    .poll(() => readIdbRecord(page, `history-bundle::${SPECTATE_ADDRESS}`), { timeout: 20_000 })
    .not.toBeNull();

  // --- Phase 2: fresh page load with the bundle response HELD. The cards must
  // configure from the IndexedDB cache while the refresh is still pending.
  const phaseOneBundleRequests = holdState.bundleRequests;
  holdState.hold = true;
  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']"))
    .toBeVisible({ timeout: 60_000 });
  // Identity arrival re-runs the restore funnel: draft + cached bundle hydrate.
  await seedSpectateIdentity(page);

  // The cached bundle lands in state (the marker proves it came from cache,
  // not from a network response — those are all held).
  await expect
    .poll(() => readOptimizerState(page, [
      "portfolio", "optimizer", "history-data", "restored-from-cache?"
    ]), { timeout: 20_000 })
    .toBe(true);

  await expect(chip).toHaveAttribute("data-status", "configured", { timeout: 30_000 });
  await expect(chip).not.toHaveAttribute("data-loading", "true");
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumptions-loading-banner']"))
    .toHaveCount(0);
  // Configured is the glyph chip (aria-label "Configured") since 2026-07-10.
  const railStatus = page.locator("[data-role='portfolio-optimizer-history-assumptions-rail-status-perp:WLFI']");
  await expect(railStatus).toContainText("✓");
  await expect(railStatus).toHaveAttribute("aria-label", "Configured");

  // Every phase-2 bundle response is held, so Configured above can only have
  // come from the cache; the background revalidate still fires on its own.
  await expect
    .poll(() => holdState.bundleRequests, { timeout: 30_000 })
    .toBeGreaterThan(phaseOneBundleRequests);
  releaseGate();
});
