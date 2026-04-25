import { expect, test } from "@playwright/test";
import {
  debugCall,
  dispatch,
  expectOracle,
  sourceRectForLocator,
  visitRoute,
  waitForDebugBridge,
  waitForIdle
} from "../support/hyperopen.mjs";

const TRADER_ADDRESS = "0x3333333333333333333333333333333333333333";
const SPECTATE_ADDRESS = "0x162cc7c861ebd0c06b3d72319201150482518185";
const OPTIMIZER_RELOAD_SCENARIO_ID = "scn_playwright_tracking_reload";
const OPTIMIZER_RELOAD_SCENARIO_EDN = `{:schema-version 1
 :id "scn_playwright_tracking_reload"
 :name "QA Tracking Reload"
 :address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
 :status :partially-executed
 :config {:id "scn_playwright_tracking_reload"
          :name "QA Tracking Reload"
          :status :partially-executed
          :objective {:kind :max-sharpe}
          :return-model {:kind :historical-mean}
          :risk-model {:kind :ledoit-wolf}
          :constraints {:long-only? true
                        :max-asset-weight 0.75
                        :rebalance-tolerance 0.001}
          :execution-assumptions {:fallback-slippage-bps 25
                                  :default-order-type :market
                                  :fee-mode :taker}
          :universe [{:instrument-id "perp:BTC"
                      :market-type :perp
                      :coin "BTC"
                      :shortable? true}
                     {:instrument-id "perp:ETH"
                      :market-type :perp
                      :coin "ETH"
                      :shortable? true}]
          :metadata {:dirty? false
                     :created-at-ms 1777045900000
                     :updated-at-ms 1777046100000}}
 :saved-run {:request-signature {:scenario-id "scn_playwright_tracking_reload"}
             :computed-at-ms 1777046000000
             :result {:status :solved
                      :scenario-id "scn_playwright_tracking_reload"
                      :instrument-ids ["perp:BTC" "perp:ETH"]
                      :target-weights [0.6 0.4]
                      :current-weights [0.55 0.45]
                      :expected-return 0.24
                      :volatility 0.38
                      :return-model :historical-mean
                      :risk-model :ledoit-wolf
                      :frontier [{:id 0
                                  :expected-return 0.2
                                  :volatility 0.32
                                  :sharpe 0.62}
                                 {:id 1
                                  :expected-return 0.24
                                  :volatility 0.38
                                  :sharpe 0.63}]
                      :diagnostics {:gross-exposure 1.0
                                    :net-exposure 1.0
                                    :effective-n 1.92
                                    :turnover 0.1
                                    :binding-constraints []}
                      :return-decomposition-by-instrument
                      {"perp:BTC" {:return-component 0.2
                                   :funding-component 0.04
                                   :funding-source :market-funding-history}
                       "perp:ETH" {:return-component 0.12
                                   :funding-component -0.01
                                   :funding-source :market-funding-history}}
                      :rebalance-preview {:capital-usd 10000.0
                                          :status :ready
                                          :summary {:ready-count 2
                                                    :blocked-count 0
                                                    :gross-trade-notional-usd 1000.0}
                                          :rows [{:instrument-id "perp:BTC"
                                                  :instrument-type :perp
                                                  :coin "BTC"
                                                  :status :ready
                                                  :side :buy
                                                  :price 100
                                                  :quantity 5.0
                                                  :delta-notional-usd 500.0
                                                  :reason :supported-perp}
                                                 {:instrument-id "perp:ETH"
                                                  :instrument-type :perp
                                                  :coin "ETH"
                                                  :status :ready
                                                  :side :sell
                                                  :price 50
                                                  :quantity 10.0
                                                  :delta-notional-usd -500.0
                                                  :reason :supported-perp}]}}}
 :execution-ledger [{:attempt-id "exec_playwright"
                    :status :partially-executed
                    :completed-at-ms 1777046100000
                    :rows [{:row-id "perp:BTC"
                            :status :submitted}]}]
 :created-at-ms 1777045900000
 :updated-at-ms 1777046100000}`;
const OPTIMIZER_RELOAD_TRACKING_EDN = `{:status :loaded
 :scenario-id "scn_playwright_tracking_reload"
 :updated-at-ms 1777046200000
 :snapshots [{:status :partially-executed
              :snapshot-at-ms 1777046200000
              :weight-drift-rms 0.0282842712
              :distance-to-target 0.0282842712
              :max-abs-weight-drift 0.04
              :predicted-return 0.24
              :realized-return 0.018
              :rows [{:instrument-id "perp:BTC"
                      :current-weight 0.56
                      :target-weight 0.6
                      :weight-drift 0.04
                      :signed-notional-usdc 400.0}
                     {:instrument-id "perp:ETH"
                      :current-weight 0.44
                      :target-weight 0.4
                      :weight-drift -0.04
                      :signed-notional-usdc -400.0}]}]
 :error nil}`;
const VOLUME_HISTORY_FIXTURE = {
  dailyUserVlm: [
    {
      date: "2026-04-03",
      exchange: 2_655_076_900.23,
      userCross: 130_550_000,
      userAdd: 219_830_000
    },
    {
      date: "2026-04-04",
      exchange: 1_346_037_058.89,
      userCross: 66_210_000,
      userAdd: 121_590_000
    },
    {
      date: "2026-04-05",
      exchange: 2_709_694_881.11,
      userCross: 140_640_000,
      userAdd: 245_600_000
    },
    {
      date: "2026-04-06",
      exchange: 5_184_032_316.32,
      userCross: 275_420_000,
      userAdd: 468_930_000
    },
    {
      date: "2026-04-07",
      exchange: 7_395_657_172.08,
      userCross: 425_220_000,
      userAdd: 593_900_000
    },
    {
      date: "2026-04-08",
      exchange: 6_112_033_361.92,
      userCross: 322_570_000,
      userAdd: 459_100_000
    },
    {
      date: "2026-04-09",
      exchange: 5_818_326_367.93,
      userCross: 315_080_000,
      userAdd: 416_950_000
    },
    {
      date: "2026-04-10",
      exchange: 4_455_513_989.13,
      userCross: 287_960_000,
      userAdd: 386_750_000
    },
    {
      date: "2026-04-11",
      exchange: 2_510_109_441.87,
      userCross: 196_400_000,
      userAdd: 201_910_000
    },
    {
      date: "2026-04-12",
      exchange: 3_910_442_359.76,
      userCross: 298_760_000,
      userAdd: 227_500_000
    },
    {
      date: "2026-04-13",
      exchange: 5_852_384_179.01,
      userCross: 420_570_000,
      userAdd: 448_150_000
    },
    {
      date: "2026-04-14",
      exchange: 6_280_954_566.8,
      userCross: 393_030_000,
      userAdd: 429_830_000
    },
    {
      date: "2026-04-15",
      exchange: 4_531_036_402.43,
      userCross: 296_770_000,
      userAdd: 322_300_000
    },
    {
      date: "2026-04-16",
      exchange: 5_729_184_977.88,
      userCross: 294_100_000,
      userAdd: 375_590_000
    },
    {
      date: "2026-04-17",
      exchange: 5_009_186_442.41,
      userCross: 1,
      userAdd: 1
    }
  ]
};

async function selectSummaryScope(page, scopeValue, expectedLabel) {
  const trigger = page.locator("[data-role='portfolio-summary-scope-selector-trigger']");
  const option = page.locator(`[data-role='portfolio-summary-scope-selector-option-${scopeValue}']`);

  await expect(trigger).not.toHaveAttribute("aria-expanded", "true");
  await trigger.click();
  await expect(trigger).toHaveAttribute("aria-expanded", "true");
  await expect(option).toBeVisible();

  await option.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(trigger).not.toHaveAttribute("aria-expanded", "true");
  await expect(trigger).toContainText(expectedLabel);
}

async function selectChartTab(page, tabValue) {
  const tab = page.locator(`[data-role='portfolio-chart-tab-${tabValue}']`);
  await tab.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tab).toHaveAttribute("aria-pressed", "true");
}

async function selectAccountTab(page, tabValue) {
  const tab = page.locator(`[data-role='account-info-tab-${tabValue}']`);
  await tab.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tab).toHaveAttribute("aria-pressed", "true");
}

async function expectFundingPopoverAnchoredLeftOfTrigger(page, trigger) {
  const modal = page.locator("[data-role='funding-modal']");
  const [triggerBox, modalBox] = await Promise.all([
    trigger.boundingBox(),
    modal.boundingBox()
  ]);

  expect(triggerBox).not.toBeNull();
  expect(modalBox).not.toBeNull();

  const horizontalGap = triggerBox.x - (modalBox.x + modalBox.width);
  expect(horizontalGap).toBeGreaterThanOrEqual(4);
  expect(horizontalGap).toBeLessThanOrEqual(16);
  expect(Math.abs(modalBox.y - Math.max(12, triggerBox.y - 20))).toBeLessThanOrEqual(8);
}

async function seedPortfolioVolumeHistory(page) {
  await page.evaluate((payload) => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const opts = c.PersistentArrayMap.fromArray([kw("keywordize-keys"), true], true);
    const walletAddress = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const stateWithWallet = c.assoc_in(
      c.deref(globalThis.hyperopen.system.store),
      c.PersistentVector.fromArray([kw("wallet"), kw("address")], true),
      walletAddress
    );
    const stateWithUserFees = c.assoc_in(
      stateWithWallet,
      c.PersistentVector.fromArray([kw("portfolio"), kw("user-fees")], true),
      c.js__GT_clj(payload, opts)
    );
    const nextState = c.assoc_in(
      stateWithUserFees,
      c.PersistentVector.fromArray([kw("portfolio"), kw("user-fees-loaded-for-address")], true),
      walletAddress
    );
    c.reset_BANG_(globalThis.hyperopen.system.store, nextState);
  }, VOLUME_HISTORY_FIXTURE);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function seedOptimizerAssetSelectorMarkets(page) {
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
    const purr = market("spot:PURR/USDC", "spot", "PURR/USDC", "PURR/USDC");
    const markets = c.PersistentVector.fromArray([btc, eth, sol, hype, purr], true);
    const marketByKey = c.PersistentArrayMap.fromArray(
      [
        "perp:BTC", btc,
        "perp:ETH", eth,
        "perp:SOL", sol,
        "perp:HYPE", hype,
        "spot:PURR/USDC", purr
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

async function readOptimizerTargetWeights(page) {
  return await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const state = c.deref(globalThis.hyperopen.system.store);
    const weights = c.get_in(
      state,
      path(
        "portfolio",
        "optimizer",
        "last-successful-run",
        "result",
        "target-weights"
      )
    );
    return c.clj__GT_js(weights || c.PersistentVector.EMPTY);
  });
}

async function seedOptimizerBtcOnlyHistory(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const vector = (items) => c.PersistentVector.fromArray(items, true);
    const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
    const candle = (time, close) =>
      map([kw("time"), time, kw("close"), close]);

    const btcInstrument = map([
      kw("instrument-id"), "perp:BTC",
      kw("market-type"), kw("perp"),
      kw("coin"), "BTC",
      kw("shortable?"), true,
      kw("symbol"), "BTC-USDC"
    ]);
    const historyData = map([
      kw("candle-history-by-coin"),
      map(["BTC", vector([candle(1000, "100"), candle(2000, "110")])]),
      kw("funding-history-by-coin"),
      c.PersistentArrayMap.EMPTY
    ]);

    const store = globalThis.hyperopen.system.store;
    const withUniverse = c.assoc_in(
      c.deref(store),
      path("portfolio", "optimizer", "draft", "universe"),
      vector([btcInstrument])
    );
    const withHistory = c.assoc_in(
      withUniverse,
      path("portfolio", "optimizer", "history-data"),
      historyData
    );

    c.reset_BANG_(store, withHistory);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function putOptimizerRecord(page, key, payload) {
  await page.evaluate(async ({ key, payload }) => {
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open("hyperopen-persistence", 6);
      request.onupgradeneeded = (event) => {
        const database = event.target.result;
        if (!database.objectStoreNames.contains("portfolio-optimizer")) {
          database.createObjectStore("portfolio-optimizer");
        }
      };
      request.onsuccess = (event) => resolve(event.target.result);
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error("IndexedDB open blocked"));
    });

    await new Promise((resolve, reject) => {
      const transaction = db.transaction(["portfolio-optimizer"], "readwrite");
      const store = transaction.objectStore("portfolio-optimizer");
      const request = store.put({ encoding: "edn-v1", payload }, key);
      request.onerror = () => reject(request.error);
      transaction.oncomplete = () => resolve(true);
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error);
    });
    db.close();
  }, { key, payload });
}

async function seedPersistedOptimizerTrackingScenario(page) {
  await putOptimizerRecord(
    page,
    `scenario::${OPTIMIZER_RELOAD_SCENARIO_ID}`,
    OPTIMIZER_RELOAD_SCENARIO_EDN
  );
  await putOptimizerRecord(
    page,
    `tracking::${OPTIMIZER_RELOAD_SCENARIO_ID}`,
    OPTIMIZER_RELOAD_TRACKING_EDN
  );
}

async function enableOptimizerSpectateMode(page) {
  await page.evaluate((address) => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const spectateMode = c.PersistentArrayMap.fromArray(
      [
        kw("active?"), true,
        kw("address"), address,
        kw("started-at-ms"), 1777046300000
      ],
      true
    );
    const store = globalThis.hyperopen.system.store;
    c.reset_BANG_(
      store,
      c.assoc_in(c.deref(store), path("account-context", "spectate-mode"), spectateMode)
    );
  }, SPECTATE_ADDRESS);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function seedOptimizerFailedExecutionAttempt(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
    const vector = (items) => c.PersistentVector.fromArray(items, true);
    const failedRow = map([
      kw("instrument-id"), "perp:BTC",
      kw("status"), kw("failed"),
      kw("side"), kw("buy"),
      kw("delta-notional-usd"), 500,
      kw("error"), map([kw("message"), "Order submit failed: exchange down"])
    ]);
    const ledger = map([
      kw("attempt-id"), "exec_playwright_failed",
      kw("status"), kw("failed"),
      kw("rows"), vector([failedRow])
    ]);
    const execution = map([
      kw("status"), kw("failed"),
      kw("attempt"), null,
      kw("history"), vector([ledger]),
      kw("error"), map([kw("message"), "Execution failed before any rows submitted."])
    ]);
    const store = globalThis.hyperopen.system.store;
    c.reset_BANG_(
      store,
      c.assoc_in(c.deref(store), path("portfolio", "optimizer", "execution"), execution)
    );
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function seedOptimizerRerunInFlight(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
    const runState = map([
      kw("status"), kw("running"),
      kw("run-id"), "optimizer-rerun-playwright",
      kw("scenario-id"), "scn_playwright_tracking_reload",
      kw("started-at-ms"), 1777046500000,
      kw("error"), null
    ]);
    const store = globalThis.hyperopen.system.store;
    c.reset_BANG_(
      store,
      c.assoc_in(c.deref(store), path("portfolio", "optimizer", "run-state"), runState)
    );
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function seedExpandedTradeBlotterToast(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const fill = (id, qty, price, ts) =>
      c.PersistentArrayMap.fromArray(
        [
          keyword("id"), id,
          keyword("side"), keyword("buy"),
          keyword("symbol"), "HYPE",
          keyword("qty"), qty,
          keyword("price"), price,
          keyword("orderType"), "limit",
          keyword("ts"), ts
        ],
        true
      );
    const fills = c.PersistentVector.fromArray(
      [
        fill("fill-1", 0.25, 44.2, 1800000000000),
        fill("fill-2", 0.3, 44.3, 1800000003300),
        fill("fill-3", 0.4, 44.4, 1800000006600),
        fill("fill-4", 0.5, 44.5, 1800000009900)
      ],
      true
    );
    const toast = c.PersistentArrayMap.fromArray(
      [
        keyword("id"), "blotter",
        keyword("kind"), keyword("success"),
        keyword("toast-surface"), keyword("trade-confirmation"),
        keyword("variant"), keyword("consolidated"),
        keyword("expanded?"), true,
        keyword("fills"), fills
      ],
      true
    );
    const nextState = c.assoc_in(
      c.deref(store),
      kwPath("ui", "toasts"),
      c.PersistentVector.fromArray([toast], true)
    );

    c.reset_BANG_(store, nextState);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function stubPortfolioUserFees(page, observedRequests = []) {
  await page.route("**/info", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      try {
        const payload = request.postDataJSON();
        if (payload?.type === "userFees") {
          observedRequests.push(payload);
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify(VOLUME_HISTORY_FIXTURE)
          });
          return;
        }
      } catch {
        // Let non-JSON info requests follow the normal route.
      }
    }
    await route.continue();
  });
}

async function unroutePortfolioUserFees(page) {
  await page.unroute("**/info");
}

test("portfolio route exposes deterministic interaction states @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio");

  await expect(page.locator("[data-role='portfolio-actions-row']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-action-perps-spot']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-action-deposit']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-action-withdraw']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-action-swap-stablecoins']")).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-action-evm-core']")).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-action-portfolio-margin']")).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-action-send']")).toHaveCount(0);

  await selectSummaryScope(page, "perps", "Perps");
  await selectChartTab(page, "pnl");
  await selectAccountTab(page, "balances");

  await expect(page.locator("[data-role='account-info-tab-performance-metrics']"))
    .not.toHaveAttribute("aria-pressed", "true");
});

test("portfolio optimizer scenario board renders the local scenario surface @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");

  await expect(page.locator("[data-role='portfolio-optimizer-index']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-filters']"))
    .toContainText("Scenario Filters");
  await expect(page.locator("[data-role='portfolio-optimizer-empty-scenarios']"))
    .toContainText("No local optimizer scenarios are loaded yet.");

  await page.locator("a[href='/portfolio/optimize/new']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-workspace']")).toBeVisible();
});

test("portfolio optimizer setup exposes separate model layers @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");

  await expect(page.locator("[data-role='portfolio-optimizer-workspace']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-run-draft']")).toBeDisabled();
  await expect(page.locator("[data-role='portfolio-optimizer-draft-state']"))
    .toContainText("Draft clean");
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .toContainText("Select a universe before running.");
  await expect(page.locator("[data-role='portfolio-optimizer-load-history']"))
    .toContainText("Load History");
  await expect(page.locator("[data-role='portfolio-optimizer-load-history']")).toBeDisabled();
  await expect(page.locator("[data-role='portfolio-optimizer-run-status-panel']"))
    .toContainText("Idle");
  await expect(page.locator("[data-role='portfolio-optimizer-universe-panel']"))
    .toContainText("Use Current Holdings");
  await expect(page.locator("[data-role='portfolio-optimizer-objective-panel']"))
    .toContainText("Minimum Variance");
  await expect(page.locator("[data-role='portfolio-optimizer-return-model-panel']"))
    .toContainText("Historical Mean");
  await expect(page.locator("[data-role='portfolio-optimizer-return-model-panel']"))
    .toContainText("Black-Litterman");
  await expect(page.locator("[data-role='portfolio-optimizer-risk-model-panel']"))
    .toContainText("Ledoit-Wolf");
  await expect(page.locator("[data-role='portfolio-optimizer-constraints-panel']"))
    .toContainText("Max Asset Weight");
  await expect(page.locator("[data-role='portfolio-optimizer-constraints-panel']"))
    .toContainText("Gross Leverage");
  await expect(page.locator("[data-role='portfolio-optimizer-constraints-panel']"))
    .toContainText("Rebalance Tolerance");
  await expect(page.locator("[data-role='portfolio-optimizer-execution-assumptions-panel']"))
    .toContainText("Fallback Slippage");
  await expect(
    page.locator(
      "[data-role='portfolio-optimizer-workspace'] select, " +
      "[data-role='portfolio-optimizer-workspace'] input[type='number'], " +
      "[data-role='portfolio-optimizer-workspace'] input[type='date'], " +
      "[data-role='portfolio-optimizer-workspace'] input[type='time'], " +
      "[data-role='portfolio-optimizer-workspace'] input[type='color'], " +
      "[data-role='portfolio-optimizer-workspace'] input[type='file']"
    )
  ).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-instrument-overrides-panel']"))
    .toContainText("Per-Asset Overrides");

  const maxSharpe = page.locator("[data-role='portfolio-optimizer-objective-max-sharpe']");
  const blackLitterman = page.locator("[data-role='portfolio-optimizer-return-model-black-litterman']");
  const sampleCovariance = page.locator("[data-role='portfolio-optimizer-risk-model-sample-covariance']");

  await expect(maxSharpe).toHaveAttribute("aria-pressed", "false");
  await maxSharpe.click();
  await expect(maxSharpe).toHaveAttribute("aria-pressed", "true");
  await expect(maxSharpe).toContainText("Active");

  await expect(blackLitterman).toHaveAttribute("aria-pressed", "false");
  await blackLitterman.click();
  await expect(blackLitterman).toHaveAttribute("aria-pressed", "true");
  await expect(blackLitterman).toContainText("Active");

  await expect(sampleCovariance).toHaveAttribute("aria-pressed", "false");
  await sampleCovariance.click();
  await expect(sampleCovariance).toHaveAttribute("aria-pressed", "true");
  await expect(sampleCovariance).toContainText("Active");

  const longOnly = page.locator("[data-role='portfolio-optimizer-constraint-long-only-input']");
  const maxAssetWeight = page.locator(
    "[data-role='portfolio-optimizer-constraint-max-asset-weight-input']"
  );

  await expect(longOnly).not.toBeChecked();
  await longOnly.check();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(longOnly).toBeChecked();

  await expect(maxAssetWeight).toHaveValue("0.35");
  await maxAssetWeight.fill("0.25");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(maxAssetWeight).toHaveValue("0.25");

  const targetReturn = page.locator(
    "[data-role='portfolio-optimizer-objective-target-return-input']"
  );
  const fallbackSlippage = page.locator(
    "[data-role='portfolio-optimizer-execution-fallback-slippage-bps-input']"
  );

  await expect(targetReturn).toHaveValue("0.15");
  await targetReturn.fill("0.18");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(targetReturn).toHaveValue("0.18");

  await expect(fallbackSlippage).toHaveValue("25");
  await fallbackSlippage.fill("35");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(fallbackSlippage).toHaveValue("35");
});

test("portfolio optimizer manual universe builder adds and removes assets @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-workspace']")).toBeVisible();
  await seedOptimizerAssetSelectorMarkets(page);
  await seedOptimizerBtcOnlyHistory(page);

  const searchInput = page.locator("[data-role='portfolio-optimizer-universe-search-input']");
  const ethCandidate = page.locator("[data-role='portfolio-optimizer-universe-add-perp:ETH']");
  const ethRemove = page.locator("[data-role='portfolio-optimizer-universe-remove-perp:ETH']");

  await expect(page.locator("[data-role='portfolio-optimizer-universe-panel']"))
    .toContainText("Manual Add");
  await searchInput.fill("eth");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(ethCandidate).toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-universe-panel']"))
    .toContainText("ETH-USDC");
  await ethCandidate.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(page.locator("[data-role='portfolio-optimizer-draft-state']"))
    .toContainText("Draft has unsaved changes");
  await expect(ethRemove).toBeVisible();
  await expect(ethCandidate).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-universe-panel']"))
    .toContainText("Requires history reload after adding new assets.");
  await expect(page.locator("[data-role='portfolio-optimizer-run-draft']")).toBeDisabled();
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .toContainText("Reload history before running this changed universe.");

  await ethRemove.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(ethRemove).toHaveCount(0);
  await expect(ethCandidate).toBeVisible();
});

test("portfolio optimizer history load requests each manual perp once @regression", async ({ page }) => {
  const historyRequests = [];
  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const request = route.request();
    if (request.method() !== "POST") {
      await route.continue();
      return;
    }

    const payload = request.postDataJSON();
    if (payload?.type === "candleSnapshot") {
      historyRequests.push(`${payload.type}:${payload.req?.coin}`);
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([
          { T: 1776800000000, c: "100" },
          { T: 1776886400000, c: "105" },
          { T: 1776972800000, c: "110" }
        ])
      });
      return;
    }

    if (payload?.type === "fundingHistory") {
      historyRequests.push(`${payload.type}:${payload.coin}`);
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([
          { time: 1776800000000, coin: payload.coin, fundingRate: "0.00001" },
          { time: 1776886400000, coin: payload.coin, fundingRate: "0.00002" }
        ])
      });
      return;
    }

    await route.continue();
  });

  await visitRoute(page, "/portfolio/optimize/new");
  await seedOptimizerAssetSelectorMarkets(page);

  const searchInput = page.locator("[data-role='portfolio-optimizer-universe-search-input']");
  await searchInput.fill("btc");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:BTC']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await searchInput.fill("eth");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:ETH']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  historyRequests.length = 0;
  await expect(page.locator("[data-role='portfolio-optimizer-load-history']")).toBeEnabled();
  await page.locator("[data-role='portfolio-optimizer-load-history']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .toContainText("Optimizer history is loaded.", { timeout: 10_000 });

  expect(historyRequests.sort()).toEqual([
    "candleSnapshot:BTC",
    "candleSnapshot:ETH",
    "fundingHistory:BTC",
    "fundingHistory:ETH"
  ]);
});

test("portfolio optimizer default minimum variance run returns non-zero target weights @regression", async ({ page }) => {
  const priceHistoryByCoin = {
    BTC: ["100", "104", "103", "108"],
    ETH: ["50", "52", "55", "54"],
    SOL: ["20", "21", "20.5", "22"],
    HYPE: ["10", "10.4", "10.2", "10.8"]
  };

  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const request = route.request();
    if (request.method() !== "POST") {
      await route.continue();
      return;
    }

    const payload = request.postDataJSON();
    if (payload?.type === "candleSnapshot") {
      const coin = payload.req?.coin;
      const closes = priceHistoryByCoin[coin] || ["100", "101", "102", "103"];
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          closes.map((close, idx) => ({
            T: 1776800000000 + idx * 86_400_000,
            c: close
          }))
        )
      });
      return;
    }

    if (payload?.type === "fundingHistory") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([
          { time: 1776800000000, coin: payload.coin, fundingRate: "0" },
          { time: 1776886400000, coin: payload.coin, fundingRate: "0" }
        ])
      });
      return;
    }

    await route.continue();
  });

  await visitRoute(page, "/portfolio/optimize/new");
  await seedOptimizerAssetSelectorMarkets(page);

  const searchInput = page.locator("[data-role='portfolio-optimizer-universe-search-input']");
  for (const coin of ["btc", "eth", "sol", "hype"]) {
    await searchInput.fill(coin);
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
    await page.locator(`[data-role='portfolio-optimizer-universe-add-perp:${coin.toUpperCase()}']`).click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  }

  await page.locator("[data-role='portfolio-optimizer-load-history']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .toContainText("Optimizer history is loaded.", { timeout: 10_000 });
  await page.locator("[data-role='portfolio-optimizer-run-draft']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-run-status-panel']"))
    .toContainText("Succeeded", { timeout: 10_000 });
  await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
    .toBeVisible();

  const weights = await readOptimizerTargetWeights(page);
  const grossTarget = weights.reduce((sum, weight) => sum + Math.abs(weight), 0);
  const netTarget = weights.reduce((sum, weight) => sum + weight, 0);
  expect(weights.some((weight) => Math.abs(weight) > 0.01)).toBe(true);
  expect(grossTarget).toBeGreaterThan(0.79);
  expect(netTarget).toBeGreaterThan(0.79);
});

test("portfolio optimizer persisted scenario hydrates results and tracking after reload @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await seedPersistedOptimizerTrackingScenario(page);

  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}`);

  const workspace = page.locator("[data-role='portfolio-optimizer-workspace']");
  const results = page.locator("[data-role='portfolio-optimizer-results-surface']");
  const tracking = page.locator("[data-role='portfolio-optimizer-tracking-panel']");

  await expect(workspace).toHaveAttribute("data-scenario-id", OPTIMIZER_RELOAD_SCENARIO_ID);
  await expect(results).toContainText("Funding Decomposition");
  await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-row-0']"))
    .toContainText("perp:BTC");
  await expect(tracking).toContainText("Weight Drift RMS");
  await expect(page.locator("[data-role='portfolio-optimizer-tracking-row-0']"))
    .toContainText("perp:BTC");

  await page.reload();
  await waitForDebugBridge(page);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });
  await expect(page.locator("[data-parity-id='app-route-module-shell']"))
    .toHaveCount(0, { timeout: 15_000 });

  await expect(workspace).toHaveAttribute("data-scenario-id", OPTIMIZER_RELOAD_SCENARIO_ID);
  await expect(results).toContainText("Rebalance Preview");
  await expect(tracking).toContainText("Realized Return");
  await expect(page.locator("[data-role='portfolio-optimizer-tracking-row-1']"))
    .toContainText("perp:ETH");
});

test("portfolio optimizer rerun keeps last successful result visible @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await seedPersistedOptimizerTrackingScenario(page);
  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}`);
  await seedOptimizerRerunInFlight(page);

  await expect(page.locator("[data-role='portfolio-optimizer-run-status-panel']"))
    .toContainText("Running");
  await expect(page.locator("[data-role='portfolio-optimizer-last-successful-run']"))
    .toContainText("Retaining last successful result while rerunning.");
  await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
    .toContainText("Rebalance Preview");
  await expect(page.locator("[data-role='portfolio-optimizer-run-draft']"))
    .toBeDisabled();
});

test("portfolio optimizer execution remains read-only in Spectate Mode @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await seedPersistedOptimizerTrackingScenario(page);
  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}`);
  await enableOptimizerSpectateMode(page);

  await page.locator("[data-role='portfolio-optimizer-open-execution-modal']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const modal = page.locator("[data-role='portfolio-optimizer-execution-modal']");
  await expect(modal).toContainText(
    "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds."
  );
  await expect(page.locator("[data-role='portfolio-optimizer-execution-modal-confirm']"))
    .toBeDisabled();
});

test("portfolio optimizer execution modal surfaces failed attempt recovery details @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await seedPersistedOptimizerTrackingScenario(page);
  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}`);
  await seedOptimizerFailedExecutionAttempt(page);

  await page.locator("[data-role='portfolio-optimizer-open-execution-modal']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const latestAttempt = page.locator("[data-role='portfolio-optimizer-execution-latest-attempt']");
  await expect(latestAttempt).toContainText("Latest Attempt");
  await expect(latestAttempt).toContainText("failed");
  await expect(latestAttempt).toContainText("Order submit failed: exchange down");
  await expect(page.locator("[data-role='portfolio-optimizer-execution-modal-confirm']"))
    .toBeEnabled();
});

test("portfolio volume history opens near the metric card trigger @regression", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await stubPortfolioUserFees(page);
  await visitRoute(page, "/portfolio");
  await seedPortfolioVolumeHistory(page);

  const trigger = page.locator("[data-role='portfolio-volume-history-trigger']");
  const popover = page.locator("[data-role='portfolio-volume-history-popover']");
  const closeButton = page.locator("[data-role='portfolio-volume-history-close']");
  const tableFrame = page.locator("[data-role='portfolio-volume-history-table-frame']");

  await expect(popover).toHaveCount(0);
  await expect(trigger).toBeVisible();
  await trigger.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(popover).toBeVisible();
  await expect(popover).toContainText("Your Volume History");
  await expect(popover).toContainText("Exchange Volume");
  await expect(popover).toContainText("Your Weighted Maker Volume");
  await expect(popover).toContainText("Your Weighted Taker Volume");
  await expect(popover).toContainText("Thu. 16. Apr. 2026");
  await expect(popover).toContainText("Fri. 3. Apr. 2026");
  await expect(page.locator("[data-role='portfolio-volume-history-day-row']")).toHaveCount(14);
  await expect(popover).toContainText("$5.73b");
  await expect(popover).toContainText("$375.59m");
  await expect(popover).toContainText("$64.49b");
  await expect(popover).toContainText("$4.92b");
  await expect(popover).toContainText("Your 14 day maker volume share is 7.63%");
  await expect(popover).not.toContainText("Dates do not include the current day");
  await expect(page.locator("[data-role='portfolio-volume-history-total-row']"))
    .toContainText("Total");
  await expect(tableFrame).toBeVisible();
  await expect.poll(async () => (
    await page.evaluate(() => {
      const triggerEl = document.querySelector("[data-role='portfolio-volume-history-trigger']");
      const popoverEl = document.querySelector("[data-role='portfolio-volume-history-popover']");
      if (!triggerEl || !popoverEl) return Number.POSITIVE_INFINITY;
      return Math.abs(popoverEl.getBoundingClientRect().top - triggerEl.getBoundingClientRect().top);
    })
  )).toBeLessThan(96);
  await expect.poll(async () => (
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)
  )).toBe(true);
  await expect.poll(async () => (
    await tableFrame.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)
  )).toBe(true);

  await closeButton.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(popover).toHaveCount(0);

  await trigger.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(popover).toBeVisible();
  await page.keyboard.press("Escape");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(popover).toHaveCount(0);
  await unroutePortfolioUserFees(page);
});

test("portfolio volume history follows the spectated account user fees @regression", async ({ page }) => {
  const observedUserFeesRequests = [];
  await stubPortfolioUserFees(page, observedUserFeesRequests);
  await visitRoute(page, `/portfolio?spectate=${SPECTATE_ADDRESS}`);

  await expect(page.locator("[data-role='spectate-mode-active-banner']")).toBeVisible();
  await expect.poll(() => observedUserFeesRequests.map((request) => request.user))
    .toContain(SPECTATE_ADDRESS);
  await expect.poll(async () => page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    return c.get_in(
      c.deref(globalThis.hyperopen.system.store),
      c.PersistentVector.fromArray([kw("portfolio"), kw("user-fees-loaded-for-address")], true)
    );
  })).toBe(SPECTATE_ADDRESS);

  const trigger = page.locator("[data-role='portfolio-volume-history-trigger']");
  const popover = page.locator("[data-role='portfolio-volume-history-popover']");
  await trigger.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(popover).toBeVisible();
  await expect(page.locator("[data-role='portfolio-volume-history-day-row']")).toHaveCount(14);
  await expect(popover).toContainText("Thu. 16. Apr. 2026");
  await expect(popover).toContainText("$5.73b");
  await expect(popover).toContainText("$375.59m");
  await expect(popover).toContainText("$294.10m");
  await expect(popover).toContainText("$64.49b");
  await expect(popover).toContainText("$4.92b");
  await expect(popover).toContainText("$3.86b");
  await expect(popover).toContainText("Your 14 day maker volume share is 7.63%");
  await unroutePortfolioUserFees(page);
});

test("portfolio funding modal restores opener focus on close @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio");

  const openButton = page.locator("[data-role='portfolio-action-deposit']");
  await expect(openButton).toBeVisible();
  await openButton.focus();
  await dispatch(page, [
    ":actions/open-funding-deposit-modal",
    await sourceRectForLocator(page, openButton),
    await openButton.getAttribute("data-role")
  ]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  const dialog = page.locator("[data-role='funding-modal']");
  const closeButton = page.locator("[data-role='funding-modal-close']");

  await expect(dialog).toBeVisible();
  await expect(closeButton).toBeFocused();

  await closeButton.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(dialog).toBeHidden();
  await expect(openButton).toBeFocused();
});

test("portfolio funding openers launch the funding modal on real click @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio");

  for (const [dataRole, title] of [
    ["portfolio-action-deposit", "Deposit"],
    ["portfolio-action-perps-spot", "Perps <-> Spot"],
    ["portfolio-action-withdraw", "Withdraw"]
  ]) {
    const openButton = page.locator(`[data-role='${dataRole}']`);

    await expect(openButton).toBeVisible();
    await openButton.click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
    await expectOracle(page, "funding-modal", { open: true, title });
    if (dataRole === "portfolio-action-deposit" || dataRole === "portfolio-action-withdraw") {
      await expectFundingPopoverAnchoredLeftOfTrigger(page, openButton);
    }

    await page.locator("[data-role='funding-modal-close']").click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
    await expectOracle(page, "funding-modal", { open: false });
  }

  await selectAccountTab(page, "deposits-withdrawals");

  for (const [dataRole, title] of [
    ["portfolio-funding-action-deposit", "Deposit"],
    ["portfolio-funding-action-transfer", "Perps <-> Spot"],
    ["portfolio-funding-action-withdraw", "Withdraw"]
  ]) {
    const openButton = page.locator(`[data-role='${dataRole}']`);

    await expect(openButton).toBeVisible();
    await openButton.click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
    await expectOracle(page, "funding-modal", { open: true, title });

    await page.locator("[data-role='funding-modal-close']").click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
    await expectOracle(page, "funding-modal", { open: false });
  }
});

test("portfolio fee schedule opens, switches market type, and restores focus @regression", async ({ page }) => {
  await page.setViewportSize({ width: 552, height: 690 });
  await visitRoute(page, "/portfolio");

  const trigger = page.locator("[data-role='portfolio-fee-schedule-trigger']");
  const dialog = page.locator("[data-role='portfolio-fee-schedule-dialog']");
  const referralTrigger = page.locator("[data-role='portfolio-fee-schedule-referral-trigger']");
  const referralDiscountOption = page.locator("[data-role='portfolio-fee-schedule-referral-option-referral-4']");
  const stakingTrigger = page.locator("[data-role='portfolio-fee-schedule-staking-trigger']");
  const stakingDiamondOption = page.locator("[data-role='portfolio-fee-schedule-staking-option-diamond']");
  const makerRebateTrigger = page.locator("[data-role='portfolio-fee-schedule-maker-rebate-trigger']");
  const makerRebateTierTwoOption = page.locator("[data-role='portfolio-fee-schedule-maker-rebate-option-tier-2']");
  const marketTrigger = page.locator("[data-role='portfolio-fee-schedule-market-trigger']");
  const marketOptionRoles = [
    "portfolio-fee-schedule-market-option-spot",
    "portfolio-fee-schedule-market-option-spot-aligned-quote",
    "portfolio-fee-schedule-market-option-spot-stable-pair",
    "portfolio-fee-schedule-market-option-spot-aligned-stable-pair",
    "portfolio-fee-schedule-market-option-perps",
    "portfolio-fee-schedule-market-option-hip3-perps",
    "portfolio-fee-schedule-market-option-hip3-perps-growth-mode",
    "portfolio-fee-schedule-market-option-hip3-perps-aligned-quote",
    "portfolio-fee-schedule-market-option-hip3-perps-growth-mode-aligned-quote"
  ];
  const corePerpsOption = page.locator("[data-role='portfolio-fee-schedule-market-option-perps']");
  const hip3PerpsOption = page.locator("[data-role='portfolio-fee-schedule-market-option-hip3-perps']");
  const hip3GrowthOption = page.locator(
    "[data-role='portfolio-fee-schedule-market-option-hip3-perps-growth-mode']"
  );
  const stableAlignedOption = page.locator(
    "[data-role='portfolio-fee-schedule-market-option-spot-aligned-stable-pair']"
  );
  const tierZero = page.locator("[data-role='portfolio-fee-schedule-tier-0']");
  const closeButton = page.locator("[data-role='portfolio-fee-schedule-close']");

  await expect(trigger).toBeVisible();
  await expect(trigger).toHaveAttribute("aria-expanded", "false");

  await trigger.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(dialog).toBeVisible();
  await expect(trigger).toHaveAttribute("aria-expanded", "true");
  await expect.poll(async () =>
    page.evaluate(() => {
      const dialogNode = document.querySelector("[data-role='portfolio-fee-schedule-dialog']");
      const overlayNode = document.querySelector("[data-role='portfolio-fee-schedule-overlay']");
      const backdropNode = document.querySelector("[data-role='portfolio-fee-schedule-backdrop']");
      const triggerNode = document.querySelector("[data-role='portfolio-fee-schedule-trigger']");
      if (!dialogNode || !overlayNode || !backdropNode || !triggerNode) {
        return {
          fitsVertically: false,
          anchoredNearTrigger: false,
          transparentBackdrop: false,
          noVerticalScroll: false,
          noPageHorizontalOverflow: false,
          usesReferenceYellow: true
        };
      }

      const rect = dialogNode.getBoundingClientRect();
      const triggerRect = triggerNode.getBoundingClientRect();
      const backdropStyle = window.getComputedStyle(backdropNode);
      const dialogStyle = window.getComputedStyle(dialogNode);
      const overlayStyle = window.getComputedStyle(overlayNode);
      const classes = Array.from(overlayNode.querySelectorAll("*"))
        .map((node) => node.getAttribute("class") || "")
        .join(" ");
      const horizontallyNearTrigger =
        (rect.left <= triggerRect.right && rect.right >= triggerRect.left) ||
        Math.abs(rect.left - triggerRect.right) <= 16 ||
        Math.abs(rect.right - triggerRect.left) <= 16;
      const verticallyNearTrigger =
        rect.top <= triggerRect.bottom + 24 && rect.bottom >= triggerRect.top - 24;
      const opaqueSurface = (backgroundColor) => {
        const rgbaMatch = backgroundColor.match(/^rgba\([^,]+,[^,]+,[^,]+,\s*([0-9.]+)\)$/);
        return backgroundColor.startsWith("rgb(") || (rgbaMatch && Number(rgbaMatch[1]) >= 0.99);
      };
      const topElementBelongsToFeeSchedule = (x, y) => {
        const topNode = document.elementFromPoint(x, y);
        return Boolean(
          topNode?.closest(
            "[data-role='portfolio-fee-schedule-dialog'], [data-role='portfolio-fee-schedule-backdrop']"
          )
        );
      };
      const dialogHitTestPoints = [
        [rect.left + rect.width / 2, rect.top + rect.height / 2],
        [rect.left + 24, rect.top + Math.min(210, rect.height - 24)],
        [rect.right - 24, rect.top + Math.min(210, rect.height - 24)]
      ];
      const triggerTopNode = document.elementFromPoint(
        triggerRect.left + triggerRect.width / 2,
        triggerRect.top + triggerRect.height / 2
      );

      return {
        fitsVertically: rect.top >= 0 && rect.bottom <= window.innerHeight,
        anchoredNearTrigger: horizontallyNearTrigger && verticallyNearTrigger,
        opaquePopoverSurface: opaqueSurface(dialogStyle.backgroundColor),
        feeScheduleOwnsDialogHitArea: dialogHitTestPoints.every(([x, y]) =>
          topElementBelongsToFeeSchedule(x, y)
        ),
        triggerBlockedByOverlay: Boolean(
          triggerTopNode?.closest(
            "[data-role='portfolio-fee-schedule-dialog'], [data-role='portfolio-fee-schedule-backdrop']"
          )
        ),
        overlayInterceptsPointerEvents: overlayStyle.pointerEvents === "auto",
        overlayAboveAccountLayers: Number.parseInt(overlayStyle.zIndex, 10) >= 650,
        dialogAboveAccountLayers: Number.parseInt(dialogStyle.zIndex, 10) >= 651,
        noTranslucentInternalSurfaces:
          !classes.includes("bg-base-100/") &&
          !classes.includes("bg-base-200/") &&
          !classes.includes("bg-base-300/"),
        transparentBackdrop:
          backdropStyle.backgroundColor === "rgba(0, 0, 0, 0)" ||
          backdropStyle.backgroundColor === "transparent",
        noVerticalScroll: dialogNode.scrollHeight <= dialogNode.clientHeight + 1,
        noPageHorizontalOverflow: document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1,
        usesReferenceYellow: classes.includes("#f4c430") || classes.includes("#ffe08a") || classes.includes("text-yellow")
      };
    })
  ).toMatchObject({
    fitsVertically: true,
    anchoredNearTrigger: true,
    opaquePopoverSurface: true,
    feeScheduleOwnsDialogHitArea: true,
    triggerBlockedByOverlay: true,
    overlayInterceptsPointerEvents: true,
    overlayAboveAccountLayers: true,
    dialogAboveAccountLayers: true,
    noTranslucentInternalSurfaces: true,
    transparentBackdrop: true,
    noVerticalScroll: true,
    noPageHorizontalOverflow: true,
    usesReferenceYellow: false
  });
  await expect(tierZero).toContainText("0.045%");
  await expect(tierZero).toContainText("0.015%");

  await marketTrigger.click();
  for (const role of marketOptionRoles) {
    await expect(page.locator(`[data-role='${role}']`)).toBeVisible();
  }
  await expect(marketTrigger).toContainText("Core Perps");
  await expect(hip3PerpsOption).not.toHaveAttribute("aria-disabled", "true");
  await hip3PerpsOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(marketTrigger).toContainText("HIP-3 Perps");
  await expect(tierZero).toContainText("0.090%");
  await expect(tierZero).toContainText("0.030%");
  await marketTrigger.click();
  await expect(corePerpsOption).toBeVisible();
  await corePerpsOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(marketTrigger).toContainText("Core Perps");
  await expect(tierZero).toContainText("0.045%");
  await expect(tierZero).toContainText("0.015%");

  await referralTrigger.click();
  await expect(referralDiscountOption).toBeVisible();
  await referralDiscountOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(referralTrigger).toContainText("4%");
  await expect(tierZero).toContainText("0.0432%");
  await expect(tierZero).toContainText("0.0144%");

  await stakingTrigger.click();
  await expect(stakingDiamondOption).toBeVisible();
  const stakingDiamondBeforeHover = await stakingDiamondOption.evaluate((node) => {
    const rect = node.getBoundingClientRect();
    return {
      backgroundColor: window.getComputedStyle(node).backgroundColor,
      compactRow: rect.height <= 30
    };
  });
  expect(stakingDiamondBeforeHover.compactRow).toBe(true);
  await stakingDiamondOption.hover();
  await expect.poll(async () =>
    stakingDiamondOption.evaluate((node) => {
      const rect = node.getBoundingClientRect();
      return {
        backgroundColor: window.getComputedStyle(node).backgroundColor,
        compactRow: rect.height <= 30
      };
    })
  ).toMatchObject({
    compactRow: true
  });
  const stakingDiamondAfterHoverBackground = await stakingDiamondOption.evaluate((node) =>
    window.getComputedStyle(node).backgroundColor
  );
  expect(stakingDiamondAfterHoverBackground).not.toBe(stakingDiamondBeforeHover.backgroundColor);
  await stakingDiamondOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(stakingTrigger).toContainText("Diamond");
  await expect(tierZero).toContainText("0.0259%");
  await expect(tierZero).toContainText("0.0086%");

  await makerRebateTrigger.click();
  await expect(makerRebateTierTwoOption).toBeVisible();
  await makerRebateTierTwoOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(makerRebateTrigger).toContainText("Tier 2");
  await expect(tierZero).toContainText("0.0066%");

  await marketTrigger.click();
  await expect(stableAlignedOption).toBeVisible();
  await stableAlignedOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(marketTrigger).toContainText("Spot + Aligned Quote + Stable Pair");
  await expect(tierZero).toContainText("0.0065%");
  await expect(tierZero).toContainText("0.0026%");

  await closeButton.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(dialog).toHaveCount(0);
  await expect(trigger).toHaveAttribute("aria-expanded", "false");
  await expect(trigger).toBeFocused();

  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const activeMarket = c.PersistentArrayMap.fromArray(
      [
        keyword("coin"), "testdex:WTIOIL",
        keyword("key"), "perp:testdex:WTIOIL",
        keyword("base"), "WTIOIL",
        keyword("quote"), "USDC",
        keyword("symbol"), "WTIOIL-USDC",
        keyword("market-type"), keyword("perp"),
        keyword("dex"), "testdex",
        keyword("hip3?"), true,
        keyword("growth-mode?"), true
      ],
      true
    );
    const feeConfig = c.PersistentArrayMap.fromArray(
      [
        "testdex",
        c.PersistentArrayMap.fromArray([keyword("deployer-fee-scale"), 0.5], true)
      ],
      true
    );
    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, kwPath("active-asset"), "testdex:WTIOIL");
    nextState = c.assoc_in(nextState, kwPath("active-market"), activeMarket);
    nextState = c.assoc_in(nextState, kwPath("perp-dex-fee-config-by-name"), feeConfig);
    c.reset_BANG_(store, nextState);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await trigger.click();
  await expect(dialog).toBeVisible();
  await marketTrigger.click();
  await expect(hip3GrowthOption).toBeVisible();
  await expect(hip3GrowthOption).not.toHaveAttribute("aria-disabled", "true");
  await expect(hip3GrowthOption).toContainText("Active market: WTIOIL");
  await hip3GrowthOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(marketTrigger).toContainText("HIP-3 Perps + Growth mode");
  await expect(tierZero).toContainText("0.0068%");
  await expect(tierZero).toContainText("0.0023%");
  await page.keyboard.press("Escape");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
});

test("trader portfolio route stays read-only while reusing stable controls @regression", async ({ page }) => {
  await visitRoute(page, `/portfolio/trader/${TRADER_ADDRESS}`);
  const accountTable = page.locator("[data-role='portfolio-account-table']");

  await expect(page.locator("[data-role='portfolio-inspection-header']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-actions-row']")).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-action-deposit']")).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-inspection-explorer-link']"))
    .toHaveAttribute("href", `https://app.hyperliquid.xyz/explorer/address/${TRADER_ADDRESS}`);

  await selectSummaryScope(page, "perps", "Perps");
  await selectChartTab(page, "pnl");
  await selectAccountTab(page, "balances");
  await expect(accountTable).toContainText("Contract");
  await expect(accountTable).not.toContainText("Send");
  await expect(accountTable).not.toContainText("Transfer");

  await selectAccountTab(page, "positions");
  await expect(accountTable).not.toContainText("Close All");

  await selectAccountTab(page, "open-orders");
  await expect(accountTable).not.toContainText("Cancel All");

  await selectAccountTab(page, "twap");
  await expect(accountTable).not.toContainText("Terminate");

  await page.locator("[data-role='portfolio-inspection-own-portfolio']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect.poll(async () => {
    const snapshot = await debugCall(page, "qaSnapshot");
    return snapshot.route;
  }).toBe("/portfolio");

  await expect(page.locator("[data-role='portfolio-actions-row']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-action-deposit']")).toBeVisible();
});

test("portfolio positions coin jumps to the trade route market @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio");
  await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const opts = c.PersistentArrayMap.fromArray([kw("keywordize-keys"), true], true);
    const payload = {
      clearinghouseState: {
        assetPositions: [
          {
            position: {
              coin: "HYPE",
              szi: "1.25",
              positionValue: "2500",
              entryPx: "100",
              markPx: "101",
              unrealizedPnl: "12",
              returnOnEquity: "0.10",
              leverage: { value: 10 },
              cumFunding: { allTime: "0" }
            }
          }
        ]
      }
    };
    const nextState = c.assoc_in(
      c.deref(globalThis.hyperopen.system.store),
      c.PersistentVector.fromArray([kw("webdata2")], true),
      c.js__GT_clj(payload, opts)
    );
    c.reset_BANG_(globalThis.hyperopen.system.store, nextState);
  });
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await selectAccountTab(page, "positions");
  const coinButton = page
    .locator("[data-role='portfolio-account-table'] [data-role='positions-coin-select']")
    .filter({ hasText: "HYPE" })
    .first();

  await expect(coinButton).toBeVisible();
  await coinButton.click();
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

  await expect.poll(async () => {
    const snapshot = await debugCall(page, "qaSnapshot");
    return {
      route: snapshot.route,
      activeAsset: snapshot.activeAsset
    };
  }).toMatchObject({
    route: "/trade/HYPE",
    activeAsset: "HYPE"
  });
});

test("spectate mode stays active when navigating from trade to portfolio via header nav @regression", async ({ page }) => {
  await visitRoute(page, "/trade");
  await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const spectateBanner = page.locator("[data-role='spectate-mode-active-banner']");
  const portfolioLink = page
    .locator("[data-parity-id='header-nav']")
    .getByRole("link", { name: "Portfolio", exact: true });

  await expect(spectateBanner).toBeVisible();
  await expect.poll(() => new URL(page.url()).searchParams.get("spectate")).toBe(SPECTATE_ADDRESS);
  await expect(portfolioLink).toHaveAttribute("href", `/portfolio?spectate=${SPECTATE_ADDRESS}`);

  await portfolioLink.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect.poll(async () => {
    const snapshot = await debugCall(page, "qaSnapshot");
    return {
      route: snapshot.route,
      spectate: new URL(page.url()).searchParams.get("spectate")
    };
  }).toMatchObject({
    route: "/portfolio",
    spectate: SPECTATE_ADDRESS
  });

  await expect(spectateBanner).toBeVisible();
  await expect(page.locator("[data-role='portfolio-actions-row']")).toBeVisible();
});

test("expanded trade blotter full history opens spectated portfolio order history @regression", async ({ page }) => {
  await visitRoute(page, "/trade");
  await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await seedExpandedTradeBlotterToast(page);

  const blotter = page.locator("[data-role='BlotterCard']");
  const historyLink = page.locator("[data-role='trade-toast-view-full-history']");

  await expect(blotter).toContainText("Grouped fills · avg 0.3 fills/sec");
  await expect(blotter).not.toContainText("TWAP · avg 1.2 fills/sec");
  await expect(historyLink).toBeVisible();
  await expect(historyLink).toHaveAttribute(
    "href",
    `/portfolio?spectate=${SPECTATE_ADDRESS}&tab=order-history`
  );

  await historyLink.click();
  await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });

  await expect.poll(() => new URL(page.url()).pathname).toBe("/portfolio");
  await expect.poll(() => new URL(page.url()).searchParams.get("spectate")).toBe(SPECTATE_ADDRESS);
  await expect.poll(() => new URL(page.url()).searchParams.get("tab")).toBe("order-history");
  await expect(page.locator("[data-role='spectate-mode-active-banner']")).toBeVisible();
  await expect(page.locator("[data-role='account-info-tab-order-history']"))
    .toHaveAttribute("aria-pressed", "true");
});
