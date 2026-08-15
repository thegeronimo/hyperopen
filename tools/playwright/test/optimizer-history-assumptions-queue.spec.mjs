// The History-assumptions section is a QUEUE (designer restructure 2026-08-14,
// option 1c): a fixed asset rail that never scrolls, one question at a time,
// the recommended model leading, and manual controls opt-in. This pins the
// three properties that make it a queue rather than a stack — the rail moves
// the pointer without settling anything, accepting advances to the next asset,
// and the rail's order does NOT reshuffle as assets get accepted — plus the
// "every diagnostic behind one toggle" fold. Discovery and history-bundle are
// stubbed, so the whole flow is deterministic.
import { expect, test } from "@playwright/test";
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

function nativeSeries(id, length) {
  return {
    instrument_id: id,
    lineage_kind: "native",
    series_kind: "market_price",
    points: Array.from({ length }, (_unused, i) => ({
      time_ms: (i + 1) * DAY_MS,
      close: 100 + (i % 11),
      return: i === 0 ? null : 0.001 + (i % 5) / 10000
    })),
    funding: { status: "available", annualized_carry: 0.01 },
    warnings: []
  };
}

function anchor(symbol) {
  return {
    instrument_id: `hl:perp:${symbol}`,
    display_symbol: symbol,
    instrument_kind: "hl_perp",
    funding_enabled: true,
    aliases: { hyperopen_market_key: `perp:${symbol}` },
    history: { status: "available", quality_status: "passed", observation_count: 400 }
  };
}

function thin(symbol, observations, defaultAssumption) {
  return {
    instrument_id: `hl:perp:${symbol}`,
    display_symbol: symbol,
    instrument_kind: "hl_perp",
    funding_enabled: true,
    aliases: { hyperopen_market_key: `perp:${symbol}` },
    history: { status: "missing", quality_status: "failed", observation_count: observations },
    default_assumption: defaultAssumption
  };
}

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
        request_id: "rid-discovery-queue",
        dataset_version: "dv-queue",
        status: "ok",
        instruments: [
          anchor("BTC"),
          anchor("ETH"),
          thin("MEGA", 61, {
            approach: "proxy",
            members: [{ instrument_id: "hl:perp:ETH", weight: 1, role: "anchor" }],
            "relationship-strength": "medium",
            rationale: "MEGA has moved almost one-for-one with ETH since it listed."
          }),
          thin("PUMP", 18, {
            approach: "proxy",
            members: [
              { instrument_id: "hl:perp:ETH", weight: 0.5, role: "anchor" },
              { instrument_id: "hl:perp:BTC", weight: 0.5, role: "anchor" }
            ],
            "relationship-strength": "medium",
            rationale: "18 days is too short to stand alone."
          }),
          thin("CHIP", 27, {
            approach: "conservative",
            members: [],
            rationale: "Nothing on the book tracks CHIP well enough to model it on a basket."
          })
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
        request_id: "rid-history-queue",
        dataset_version: "dv-queue",
        status: "partial",
        common_calendar: calendar,
        return_calendar: calendar.slice(1),
        aligned_returns_by_instrument: {
          "perp:BTC": {
            instrument_id: "hl:perp:BTC",
            returns: Array.from({ length: 399 }, (_u, i) => 0.001 + (i % 5) / 10000)
          },
          "perp:ETH": {
            instrument_id: "hl:perp:ETH",
            returns: Array.from({ length: 399 }, (_u, i) => 0.0012 + (i % 7) / 10000)
          }
        },
        series_by_instrument: {
          "perp:BTC": nativeSeries("hl:perp:BTC", 400),
          "perp:ETH": nativeSeries("hl:perp:ETH", 400)
        },
        warnings: [
          { code: "missing-candle-history", instrument_id: "perp:MEGA" },
          { code: "missing-candle-history", instrument_id: "perp:PUMP" },
          { code: "missing-candle-history", instrument_id: "perp:CHIP" }
        ]
      })
    });
  });
}

function proxyEntry(instrumentIds, acknowledged) {
  const entry = {
    behavior: keyword("proxy"),
    "expected-return": 0,
    volatility: 0.8,
    "max-weight": 0.05,
    proxy: {
      "instrument-ids": instrumentIds,
      "relationship-strength": keyword("medium"),
      "prior-weights": null
    }
  };
  if (acknowledged) {
    entry.metadata = { source: keyword("user"), "acknowledged?": true };
  }
  return entry;
}

async function addAsset(page, key) {
  await page.locator("[data-role='portfolio-optimizer-universe-search-input']")
    .fill(key.split(":")[1]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator(`[data-role='portfolio-optimizer-universe-add-${key}']`).click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

// MEGA accepted, PUMP engine-backed but still owing an Accept, CHIP untouched
// on its backend recommendation — one asset in each of the queue's three states.
async function seedQueue(page) {
  await stubHistoryApi(page);
  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']"))
    .toBeVisible({ timeout: 60_000 });

  await seedOptimizerMarkets(page, [
    { key: "perp:BTC", "market-type": "perp", coin: "BTC", symbol: "BTC-USDC", name: "Bitcoin" },
    { key: "perp:ETH", "market-type": "perp", coin: "ETH", symbol: "ETH-USDC", name: "Ethereum" },
    { key: "perp:MEGA", "market-type": "perp", coin: "MEGA", symbol: "MEGA-USDC", name: "Mega" },
    { key: "perp:PUMP", "market-type": "perp", coin: "PUMP", symbol: "PUMP-USDC", name: "Pump" },
    { key: "perp:CHIP", "market-type": "perp", coin: "CHIP", symbol: "CHIP-USDC", name: "Chip" }
  ]);

  for (const key of ["perp:BTC", "perp:ETH", "perp:MEGA", "perp:PUMP", "perp:CHIP"]) {
    await addAsset(page, key);
  }

  await seedOptimizerState(page, [
    seedPatch(optimizerPath("draft", "history-assumptions"), stringMap([
      ["perp:MEGA", proxyEntry(["perp:ETH"], true)],
      ["perp:PUMP", proxyEntry(["perp:ETH", "perp:BTC"], false)]
    ]))
  ]);
  await waitForIdle(page, { quietMs: 400, timeoutMs: 10_000, pollMs: 100 });

  const queue = page.locator("[data-role='portfolio-optimizer-history-assumptions-queue']");
  await expect(queue).toBeVisible({ timeout: 30_000 });
  return queue;
}

function pill(page, symbol) {
  return page.locator(`[data-role='portfolio-optimizer-history-assumption-pill-perp:${symbol}']`);
}

test("portfolio optimizer history assumptions queue asks one question at a time @regression", async ({ page }) => {
  test.setTimeout(180_000);
  await seedQueue(page);

  // The rail carries every asset; the queue opens on the first UNSETTLED one.
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumptions-settled']"))
    .toHaveText("1 of 3 settled");
  await expect(pill(page, "MEGA")).toHaveAttribute("aria-pressed", "false");
  await expect(pill(page, "PUMP")).toHaveAttribute("aria-pressed", "true");
  await expect(pill(page, "CHIP")).toHaveAttribute("aria-pressed", "false");
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-question']"))
    .toHaveText("What does PUMP behave like?");

  // Exactly one asset's editors exist at a time — that is the whole point.
  await expect(page.locator("[data-role^='portfolio-optimizer-history-assumption-card-']"))
    .toHaveCount(1);

  // Engine-backed but unaccepted reads as the decision still owed, while the
  // raw engine-backing status rides on untouched underneath.
  const status = page.locator("[data-role='portfolio-optimizer-history-assumption-status-perp:PUMP']");
  await expect(status).toHaveText("Ready to accept");
  await expect(status).toHaveAttribute("data-status", "configured");

  // The panel leads with the basket the engine would use.
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-model-line']"))
    .toHaveText("ETH 50% / BTC 50% · medium similarity · 80% vol · 5% max");

  // Moving the rail settles nothing.
  await pill(page, "MEGA").click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 6_000, pollMs: 50 });
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-question']"))
    .toHaveText("MEGA is modeled - change it?");
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumptions-settled']"))
    .toHaveText("1 of 3 settled");
});

test("portfolio optimizer history assumptions queue folds every diagnostic behind one toggle @regression", async ({ page }) => {
  test.setTimeout(180_000);
  await seedQueue(page);

  const detail = page.locator("[data-role='portfolio-optimizer-history-assumption-model-detail']");
  await expect(detail).toHaveCount(1);
  expect(await detail.evaluate((el) => el.open)).toBe(false);
  // Closed, the head still answers the question.
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-model-line']"))
    .toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-detail-Final basket']"))
    .not.toBeVisible();

  await detail.locator("summary").first().click();
  for (const row of ["Prior basket", "Regression", "Final basket", "Confidence",
                     "Specific risk", "Window"]) {
    await expect(page.locator(`[data-role='portfolio-optimizer-history-assumption-detail-${row}']`))
      .toBeVisible();
  }

  // Manual controls are a separate, independently opt-in disclosure.
  const adjust = page.locator("[data-role='portfolio-optimizer-history-assumption-adjust-perp:PUMP']");
  expect(await adjust.evaluate((el) => el.open)).toBe(false);
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-proxy-search-perp:PUMP']"))
    .not.toBeVisible();
  await adjust.locator("summary").first().click();
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-proxy-search-perp:PUMP']"))
    .toBeVisible();
});

test("portfolio optimizer history assumptions queue advances on accept and keeps rail order @regression", async ({ page }) => {
  test.setTimeout(180_000);
  await seedQueue(page);

  const railOrder = async () =>
    page.locator("[data-role^='portfolio-optimizer-history-assumption-pill-']")
      .evaluateAll((nodes) => nodes.map((n) => n.getAttribute("data-role")));
  const before = await railOrder();

  // "Accept & next" settles the active asset and hands over the next one.
  await page.locator("[data-role='portfolio-optimizer-history-assumptions-queue-advance']").click();
  await waitForIdle(page, { quietMs: 400, timeoutMs: 10_000, pollMs: 100 });

  await expect(page.locator("[data-role='portfolio-optimizer-history-assumptions-settled']"))
    .toHaveText("2 of 3 settled");
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-question']"))
    .toHaveText("What does CHIP behave like?");
  const entry = await readOptimizerState(page, [
    "portfolio", "optimizer", "draft", "history-assumptions"
  ]);
  expect(entry["perp:PUMP"].metadata["acknowledged?"]).toBe(true);

  // A rail that reshuffles as you accept destroys the only thing it is for.
  expect(await railOrder()).toEqual(before);

  // The last one settles from its recommendation; the queue then owes nothing.
  await page.locator("[data-role='portfolio-optimizer-history-assumption-recommendation-apply-perp:CHIP']")
    .click();
  await waitForIdle(page, { quietMs: 400, timeoutMs: 10_000, pollMs: 100 });

  const section = page.locator("[data-role='portfolio-optimizer-history-assumptions-section']");
  if (!(await section.evaluate((el) => el.open))) {
    await section.locator("summary").first().click();
  }
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumptions-settled']"))
    .toHaveText("3 of 3 settled");
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumptions-count']"))
    .toHaveText("3 of 3 settled");
  expect(await railOrder()).toEqual(before);
});
