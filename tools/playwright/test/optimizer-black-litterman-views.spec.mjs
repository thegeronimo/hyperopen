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

async function seedBlackLittermanAutomaticReturnState(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
    const vector = (items) => c.PersistentVector.fromArray(items, true);
    const candle = (time, close) => map([kw("time"), time, kw("close"), close]);
    const btcInstrument = map([
      kw("instrument-id"), "perp:BTC",
      kw("market-type"), kw("perp"),
      kw("coin"), "BTC",
      kw("symbol"), "BTC-USDC"
    ]);
    const draft = map([
      kw("universe"), vector([btcInstrument]),
      kw("objective"), map([kw("kind"), kw("max-sharpe")]),
      kw("return-model"), map([kw("kind"), kw("black-litterman"), kw("views"), vector([])]),
      kw("risk-model"), map([kw("kind"), kw("sample-covariance")]),
      kw("constraints"), map([])
    ]);
    const editorState = map([
      kw("selected-kind"), kw("absolute"),
      kw("drafts"), map([
        kw("absolute"), map([
          kw("instrument-id"), null,
          kw("return-text"), "",
          kw("return-text-touched?"), false,
          kw("confidence"), kw("medium"),
          kw("horizon"), kw("3m"),
          kw("notes"), ""
        ])
      ]),
      kw("editing-view-id"), null,
      kw("errors"), map([]),
      kw("clear-confirmation-open?"), false
    ]);
    const store = globalThis.hyperopen.system.store;
    let state = c.deref(store);
    state = c.assoc_in(
      state,
      c.PersistentVector.fromArray([kw("portfolio"), kw("optimizer"), kw("draft")], true),
      draft
    );
    state = c.assoc_in(
      state,
      c.PersistentVector.fromArray(
        [kw("portfolio"), kw("optimizer"), kw("history-data"), kw("candle-history-by-coin"), "BTC"],
        true
      ),
      vector([
        candle(1000, "100"),
        candle(2000, "101"),
        candle(3000, "103.02"),
        candle(4000, "106.1106")
      ])
    );
    state = c.assoc_in(
      state,
      c.PersistentVector.fromArray(
        [kw("portfolio"), kw("optimizer"), kw("history-data"), kw("funding-history-by-coin")],
        true
      ),
      map([])
    );
    state = c.assoc_in(
      state,
      c.PersistentVector.fromArray([kw("portfolio"), kw("optimizer"), kw("market-cap-by-coin")], true),
      map(["BTC", 1])
    );
    state = c.assoc_in(
      state,
      c.PersistentVector.fromArray([kw("portfolio"), kw("optimizer"), kw("runtime"), kw("as-of-ms")], true),
      5000
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

async function widthRatio(child, parent) {
  const [childBox, parentBox] = await Promise.all([
    child.boundingBox(),
    parent.boundingBox()
  ]);

  if (!childBox || !parentBox || parentBox.width === 0) {
    return 0;
  }

  return childBox.width / parentBox.width;
}

test("portfolio optimizer use my views editor flow exposes the Edit Views contract @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 900, height: 900 });
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

  const instrumentGrid = panel.locator(
    "[data-role='portfolio-optimizer-black-litterman-editor-instrument-grid']"
  );
  const assetOptions = panel.locator(
    "[data-role='portfolio-optimizer-black-litterman-editor-asset-options']"
  );
  const comparatorOptions = panel.locator(
    "[data-role='portfolio-optimizer-black-litterman-editor-comparator-options']"
  );

  await expect(assetOptions).toBeVisible();
  await expect(comparatorOptions).toHaveCount(0);
  await expect
    .poll(() => widthRatio(assetOptions, instrumentGrid), {
      message: "absolute asset selector should span the instrument editor grid",
      timeout: 4_000
    })
    .toBeGreaterThan(0.95);

  await page.locator("[data-role='portfolio-optimizer-black-litterman-editor-type-relative']").click();
  await expect(comparatorOptions).toBeVisible();
  await expect
    .poll(() => widthRatio(assetOptions, instrumentGrid), {
      message: "relative asset selector should share the grid with comparator",
      timeout: 4_000
    })
    .toBeLessThan(0.65);
  await page.locator("[data-role='portfolio-optimizer-black-litterman-editor-type-absolute']").click();
  await expect(comparatorOptions).toHaveCount(0);

  await page.locator("[data-role='portfolio-optimizer-black-litterman-active-view-view-2-remove']").click();
  await expect(panel).toContainText("ACTIVE VIEWS (1/10)");

  await page.locator("[data-role='portfolio-optimizer-black-litterman-clear-all']").click();
  await expect(panel.locator("[data-role='portfolio-optimizer-black-litterman-clear-confirm']"))
    .toBeVisible();
  await page.locator("[data-role='portfolio-optimizer-black-litterman-clear-cancel']").click();
  await expect(panel).toContainText(/views adjust expected returns only/i);
});

test("portfolio optimizer use my views prepopulates absolute return from Sharpe input @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 900, height: 900 });
  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']"))
    .toBeVisible({ timeout: 60_000 });

  await seedBlackLittermanAutomaticReturnState(page);

  const panel = page.locator("[data-role='portfolio-optimizer-black-litterman-panel']");
  const returnInput = panel.locator("[data-role='portfolio-optimizer-black-litterman-editor-return']");
  await expect(returnInput).toHaveValue("3.65");
  await expect(panel.locator("[data-role='portfolio-optimizer-black-litterman-preview-text']"))
    .toContainText("BTC expected return +3.65% annualized");
});
