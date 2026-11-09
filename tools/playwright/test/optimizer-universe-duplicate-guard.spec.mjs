import { expect, test } from "../support/guarded_test.mjs";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import {
  keyword,
  optimizerPath,
  optimizerUiPath,
  readOptimizerState,
  seedOptimizerMarkets,
  seedOptimizerState,
  seedPatch
} from "../support/optimizer_state.mjs";

// The same-stock guard: NVDA-USDC is already in the universe, and the search
// offers NVDA on two other venues. The panel must say so, ask inline before a
// second listing is added, and keep twins linked if the trader keeps both.

function hip3Perp(dex, base, quote, volume) {
  return {
    key: `perp:${dex}:${base}`,
    "market-type": keyword("perp"),
    coin: `${dex}:${base}`,
    dex,
    symbol: `${base}-${quote}`,
    base,
    quote,
    volume24h: volume
  };
}

const MARKETS = [
  hip3Perp("xyz", "NVDA", "USDC", 900),
  hip3Perp("flx", "NVDA", "USDH", 500),
  hip3Perp("cash", "NVDA", "USDT0", 300),
  hip3Perp("xyz", "NVDL", "USDC", 100)
];

function held(market, side) {
  return {
    "instrument-id": market.key,
    "market-type": keyword("perp"),
    coin: market.coin,
    dex: market.dex,
    symbol: market.symbol,
    base: market.base,
    quote: market.quote,
    "shortable?": true,
    "position-side": keyword(side)
  };
}

async function seed(page) {
  await seedOptimizerMarkets(page, MARKETS);
  await seedOptimizerState(page, [
    seedPatch(optimizerPath("draft", "universe"), [
      {
        "instrument-id": "perp:SOL",
        "market-type": keyword("perp"),
        coin: "SOL",
        symbol: "SOL-USDC",
        base: "SOL",
        "position-side": keyword("long")
      },
      held(MARKETS[0], "short")
    ]),
    seedPatch(optimizerUiPath("universe-duplicate-acknowledged"), [])
  ]);
}

async function search(page, query) {
  const input = page.locator('[data-role="portfolio-optimizer-universe-search-input"]');
  await input.fill(query);
  await waitForIdle(page);
}

async function universeIds(page) {
  const universe = await readOptimizerState(page, optimizerPath("draft", "universe"));
  return universe.map((instrument) => instrument["instrument-id"]);
}

test.describe("optimizer universe same-stock guard", () => {
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await visitRoute(page, "/portfolio/optimize/new");
    await seed(page);
  });

  test("search flags the held stock and swap replaces it in place", async ({ page }) => {
    await search(page, "NVD");

    const banner = page.locator('[data-role="portfolio-optimizer-universe-duplicate-banner"]');
    await expect(banner).toContainText("You already have NVDA");
    await expect(
      page.locator('[data-role="portfolio-optimizer-universe-same-as-perp:flx:NVDA"]')
    ).toHaveText("Same as NVDA-USDC · xyz");
    await expect(
      page.locator('[data-role="portfolio-optimizer-universe-same-as-perp:xyz:NVDL"]')
    ).toHaveCount(0);
    await expect(
      page.locator('[data-role="portfolio-optimizer-universe-selected-row-perp:xyz:NVDA"]')
    ).toHaveAttribute("data-search-match", "true");

    await page.locator('[data-role="portfolio-optimizer-universe-add-perp:flx:NVDA"]').click();
    await waitForIdle(page);
    const confirm = page.locator(
      '[data-role="portfolio-optimizer-universe-duplicate-confirm-perp:flx:NVDA"]'
    );
    await expect(confirm).toContainText("NVDA-USDH · flx is the same asset as NVDA-USDC · xyz");
    await expect(
      page.locator('[data-role="portfolio-optimizer-universe-duplicate-swap-perp:flx:NVDA"]')
    ).toBeInViewport();
    expect(await universeIds(page)).toEqual(["perp:SOL", "perp:xyz:NVDA"]);

    await page
      .locator('[data-role="portfolio-optimizer-universe-duplicate-swap-perp:flx:NVDA"]')
      .click();
    await waitForIdle(page);
    expect(await universeIds(page)).toEqual(["perp:SOL", "perp:flx:NVDA"]);
    const universe = await readOptimizerState(page, optimizerPath("draft", "universe"));
    expect(universe[1]["position-side"]).toBe("short");
  });

  test("keep closes the confirm; add both groups the twins", async ({ page }) => {
    await search(page, "NVD");
    const addFlx = page.locator('[data-role="portfolio-optimizer-universe-add-perp:flx:NVDA"]');
    await addFlx.click();
    await waitForIdle(page);
    await page
      .locator('[data-role="portfolio-optimizer-universe-duplicate-keep-perp:flx:NVDA"]')
      .click();
    await waitForIdle(page);
    await expect(
      page.locator('[data-role="portfolio-optimizer-universe-duplicate-confirm-perp:flx:NVDA"]')
    ).toHaveCount(0);
    expect(await universeIds(page)).toEqual(["perp:SOL", "perp:xyz:NVDA"]);

    await addFlx.click();
    await waitForIdle(page);
    await page
      .locator('[data-role="portfolio-optimizer-universe-duplicate-both-perp:flx:NVDA"]')
      .click();
    await waitForIdle(page);
    expect(await universeIds(page)).toEqual(["perp:SOL", "perp:xyz:NVDA", "perp:flx:NVDA"]);
    await expect(
      page.locator('[data-role="portfolio-optimizer-universe-twin-header-NVDA"]')
    ).toContainText("NVDA × 2 · same asset");
    await expect(
      page.locator('[data-role="portfolio-optimizer-universe-twin-count"]')
    ).toContainText("1 duplicate");
  });
});
