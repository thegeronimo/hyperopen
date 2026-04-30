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
      if (dex) {
        entries.push(kw("dex"), dex);
      }
      return c.PersistentArrayMap.fromArray(entries, true);
    };

    const btc = market("perp:BTC", "perp", "BTC", "BTC-USDC", "hl");
    const eth = market("perp:ETH", "perp", "ETH", "ETH-USDC", "hl");
    const sol = market("perp:SOL", "perp", "SOL", "SOL-USDC", "hl");
    const hype = market("perp:HYPE", "perp", "HYPE", "HYPE-USDC", "hl");
    const markets = c.PersistentVector.fromArray([btc, eth, sol, hype], true);
    const marketByKey = c.PersistentArrayMap.fromArray(
      [
        "perp:BTC", btc,
        "perp:ETH", eth,
        "perp:SOL", sol,
        "perp:HYPE", hype
      ],
      true
    );
    const state = c.deref(globalThis.hyperopen.system.store);
    const withMarkets = c.assoc_in(
      state,
      c.PersistentVector.fromArray([kw("asset-selector"), kw("markets")], true),
      markets
    );
    const nextState = c.assoc_in(
      withMarkets,
      c.PersistentVector.fromArray([kw("asset-selector"), kw("market-by-key")], true),
      marketByKey
    );
    c.reset_BANG_(globalThis.hyperopen.system.store, nextState);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function seedBlackLittermanEditorState(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
    const vector = (items) => c.PersistentVector.fromArray(items, true);
    const absoluteView = map([
      kw("id"), "view-1",
      kw("kind"), kw("absolute"),
      kw("instrument-id"), "perp:HYPE",
      kw("return"), 0.45,
      kw("confidence"), 0.75,
      kw("horizon"), kw("1y"),
      kw("notes"), "Momentum conviction"
    ]);
    const relativeView = map([
      kw("id"), "view-2",
      kw("kind"), kw("relative"),
      kw("instrument-id"), "perp:ETH",
      kw("comparator-instrument-id"), "perp:SOL",
      kw("direction"), kw("outperform"),
      kw("return"), 0.05,
      kw("confidence"), 0.5,
      kw("horizon"), kw("6m"),
      kw("notes"), "Pair view"
    ]);
    const draftReturnModel = map([
      kw("kind"), kw("black-litterman"),
      kw("views"), vector([absoluteView, relativeView])
    ]);
    const editorState = map([
      kw("selected-kind"), kw("absolute"),
      kw("drafts"), map([
        kw("absolute"), map([
          kw("instrument-id"), "perp:HYPE",
          kw("return-text"), "45",
          kw("confidence"), kw("high"),
          kw("horizon"), kw("1y"),
          kw("notes"), "Momentum conviction"
        ]),
        kw("relative"), map([
          kw("instrument-id"), "perp:ETH",
          kw("comparator-instrument-id"), "perp:SOL",
          kw("direction"), kw("outperform"),
          kw("return-text"), "5",
          kw("confidence"), kw("medium"),
          kw("horizon"), kw("6m"),
          kw("notes"), "Pair view"
        ])
      ]),
      kw("editing-view-id"), null,
      kw("clear-confirmation-open?"), false
    ]);
    const store = globalThis.hyperopen.system.store;
    let state = c.deref(store);
    state = c.assoc_in(
      state,
      c.PersistentVector.fromArray(
        [kw("portfolio"), kw("optimizer"), kw("draft"), kw("return-model")],
        true
      ),
      draftReturnModel
    );
    state = c.assoc_in(
      state,
      c.PersistentVector.fromArray(
        [kw("portfolio-ui"), kw("optimizer"), kw("black-litterman-editor")],
        true
      ),
      editorState
    );
    c.reset_BANG_(store, state);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

test("portfolio optimizer use my views editor flow exposes the Edit Views contract @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']"))
    .toBeVisible({ timeout: 60_000 });

  await seedMarkets(page);
  await seedBlackLittermanEditorState(page);

  const panel = page.locator("[data-role='portfolio-optimizer-black-litterman-panel']");
  await expect(panel).toContainText("EDIT VIEWS");
  await expect(panel).toContainText("Tell the model what you believe");
  await expect(panel).toContainText("ACTIVE VIEWS (2/10)");
  await expect(panel.locator("[data-role='portfolio-optimizer-black-litterman-preview-text']"))
    .toContainText("HYPE expected return +45% annualized");
  await expect(panel).toContainText("ETH > SOL by 5% annualized");
  await expect(panel.locator("select")).toHaveCount(0);

  await page.locator("[data-role='portfolio-optimizer-black-litterman-active-view-view-2-remove']").click();
  await expect(panel).toContainText("ACTIVE VIEWS (1/10)");

  await page.locator("[data-role='portfolio-optimizer-black-litterman-clear-all']").click();
  await expect(panel.locator("[data-role='portfolio-optimizer-black-litterman-clear-confirm']"))
    .toBeVisible();
  await page.locator("[data-role='portfolio-optimizer-black-litterman-clear-cancel']").click();
  await expect(panel).toContainText(/views adjust expected returns only/i);
});
