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
const OPTIMIZER_VAULT_ADDRESS = "0x1111111111111111111111111111111111111111";
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
          :risk-model {:kind :diagonal-shrink}
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
                      :risk-model :diagonal-shrink
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
                                                    :gross-trade-notional-usd 1000.0
                                                    :gross-ready-notional-usd 1000.0
                                                    :estimated-fees-usd 0.35
                                                    :estimated-slippage-usd 1.0
                                                    :margin {:capital-usd 10000.0
                                                             :current-used-usd 1200.0
                                                             :estimated-impact-usd 1000.0
                                                             :after-used-usd 2200.0
                                                             :before-utilization 0.12
                                                             :after-utilization 0.22
                                                             :warning nil}}
                                          :rows [{:instrument-id "perp:BTC"
                                                  :instrument-type :perp
                                                  :coin "BTC"
                                                  :status :ready
                                                  :side :buy
                                                  :price 100
                                                  :quantity 5.0
                                                  :delta-notional-usd 500.0
                                                  :order-type :market
                                                  :cost {:source :live-orderbook
                                                         :estimated-fill-price 100.1
                                                         :notional-usd 500.0
                                                         :slippage-bps 10.0
                                                         :estimated-slippage-usd 0.5
                                                         :fee-bps 3.5
                                                         :estimated-fee-usd 0.175}
                                                  :reason :supported-perp}
                                                 {:instrument-id "perp:ETH"
                                                  :instrument-type :perp
                                                  :coin "ETH"
                                                  :status :ready
                                                  :side :sell
                                                  :price 50
                                                  :quantity 10.0
                                                  :delta-notional-usd -500.0
                                                  :order-type :market
                                                  :cost {:source :live-orderbook
                                                         :estimated-fill-price 49.95
                                                         :notional-usd 500.0
                                                         :slippage-bps 10.0
                                                         :estimated-slippage-usd 0.5
                                                         :fee-bps 3.5
                                                         :estimated-fee-usd 0.175}
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
              :nav-usdc 10000.0
              :weight-drift-rms 0.0282842712
              :max-abs-weight-drift 0.04
              :predicted-return 0.24
              :predicted-volatility 0.38
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

async function stubOptimizerVaultMetadata(page) {
  const vaultSummary = {
    vaultAddress: OPTIMIZER_VAULT_ADDRESS,
    name: "Alpha Yield",
    leader: "0x2222222222222222222222222222222222222222",
    tvl: "5000000",
    relationship: { type: "normal" },
    createTimeMillis: 1777045900000
  };

  await page.route("https://stats-data.hyperliquid.xyz/Mainnet/vaults", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([{ summary: vaultSummary }])
    });
  });

  await page.route("**/info", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      try {
        const payload = request.postDataJSON();
        if (payload?.type === "vaultSummaries") {
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify([vaultSummary])
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

async function seedOptimizerVaultRows(page) {
  await page.evaluate((vaultAddress) => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
    const row = map([
      kw("name"), "Alpha Yield",
      kw("vault-address"), vaultAddress,
      kw("leader"), "0x2222222222222222222222222222222222222222",
      kw("tvl"), 5000000,
      kw("relationship"), map([kw("type"), kw("normal")])
    ]);
    const rows = c.PersistentVector.fromArray([row], true);
    const store = globalThis.hyperopen.system.store;
    c.reset_BANG_(
      store,
      c.assoc_in(c.deref(store), path("vaults", "merged-index-rows"), rows)
    );
  }, OPTIMIZER_VAULT_ADDRESS);
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
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
});

test("portfolio optimizer setup orders objective before return risk model @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");

  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-objective-panel']"))
    .toContainText(/02\s*Objective/);
  await expect(page.locator("[data-role='portfolio-optimizer-return-risk-panel']"))
    .toContainText(/03\s*Return \/ Risk Model/);
  await expect.poll(async () => page
    .locator("[data-role='portfolio-optimizer-setup-control-rail']")
    .evaluate((rail) => Array.from(rail.children).map((child) => child.getAttribute("data-role"))))
    .toEqual([
      "portfolio-optimizer-universe-panel",
      "portfolio-optimizer-objective-panel",
      "portfolio-optimizer-return-risk-panel",
      "portfolio-optimizer-constraints-panel",
      "portfolio-optimizer-advanced-overrides-shell"
    ]);
});

test("portfolio optimizer setup exposes separate model layers @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");

  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-run-draft']")).toBeDisabled();
  await expect(page.locator("[data-role='portfolio-optimizer-setup-header'] [data-role='portfolio-optimizer-run-draft']"))
    .toHaveCount(0);
  const modelPanel = page.locator("[data-role='portfolio-optimizer-return-risk-panel']");
  const constraintsPanel = page.locator("[data-role='portfolio-optimizer-constraints-panel']");
  const advancedPanel = page.locator("[data-role='portfolio-optimizer-advanced-overrides-shell']");
  const returnModelPanel = page.locator("[data-role='portfolio-optimizer-return-model-panel']");
  const riskModelPanel = page.locator("[data-role='portfolio-optimizer-risk-model-panel']");
  const maxAssetWeight = page.locator(
    "[data-role='portfolio-optimizer-constraint-max-asset-weight-input']"
  );

  await expect.poll(async () => modelPanel.evaluate((element) => element.open)).toBe(false);
  await expect.poll(async () => constraintsPanel.evaluate((element) => element.open)).toBe(false);
  await expect.poll(async () => advancedPanel.evaluate((element) => element.open)).toBe(false);
  await expect(returnModelPanel).toBeHidden();
  await expect(maxAssetWeight).toBeHidden();

  const summaryPane = page.locator("[data-role='portfolio-optimizer-setup-summary-pane']");
  const assumptionsPanel = page.locator("[data-role='portfolio-optimizer-model-assumptions-panel']");
  const bottomActions = page.locator("[data-role='portfolio-optimizer-setup-bottom-actions']");
  const actionMeta = page.locator("[data-role='portfolio-optimizer-setup-bottom-actions-status-meta']");
  const actionDetail = page.locator("[data-role='portfolio-optimizer-setup-bottom-actions-status-detail']");
  const footer = page.locator("[data-parity-id='footer']");
  await expect(summaryPane.locator("[data-role='portfolio-optimizer-setup-bottom-actions']"))
    .toBeVisible();
  await expect(assumptionsPanel).toBeVisible();
  await expect.poll(async () => {
    const [summaryBox, assumptionsBox, actionsBox, metaBox, detailBox, footerBox] = await Promise.all([
      summaryPane.boundingBox(),
      assumptionsPanel.boundingBox(),
      bottomActions.boundingBox(),
      actionMeta.boundingBox(),
      actionDetail.boundingBox(),
      footer.boundingBox()
    ]);
    if (!summaryBox || !assumptionsBox || !actionsBox || !metaBox || !detailBox || !footerBox) return false;
    const topPadding = metaBox.y - actionsBox.y;
    const bottomPadding = actionsBox.y + actionsBox.height - (detailBox.y + detailBox.height);
    return actionsBox.x >= summaryBox.x
      && actionsBox.x + actionsBox.width <= summaryBox.x + summaryBox.width + 1
      && detailBox.x >= actionsBox.x
      && detailBox.x + detailBox.width <= actionsBox.x + actionsBox.width + 1
      && bottomPadding >= 10
      && Math.abs(topPadding - bottomPadding) <= 8
      && actionsBox.y > assumptionsBox.y + assumptionsBox.height
      && actionsBox.y < footerBox.y;
  }).toBe(true);
  await expect.poll(async () => {
    const [metaColor, detailColor] = await Promise.all([
      actionMeta.evaluate((element) => getComputedStyle(element).color),
      actionDetail.evaluate((element) => getComputedStyle(element).color)
    ]);
    const rgb = (color) => (color.match(/\d+(\.\d+)?/g) || []).slice(0, 3).map(Number);
    const meta = rgb(metaColor);
    const detail = rgb(detailColor);
    return meta.length === 3
      && detail.length === 3
      && detail[0] > meta[0]
      && detail[1] > meta[1]
      && detail[2] > meta[2];
  }).toBe(true);
  await expect(page.locator("[data-role='portfolio-optimizer-draft-state']"))
    .toContainText("Draft clean");
  await expect(page.locator("[data-role='portfolio-optimizer-trust-freshness-panel']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-load-history']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-run-status-panel']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-universe-panel']"))
    .toContainText("Use Current Holdings");
  await expect(page.locator("[data-role='portfolio-optimizer-objective-panel']"))
    .toContainText("Minimum Variance");
  await expect(page.locator("[data-role='portfolio-optimizer-return-model-panel']"))
    .toContainText("Historical Mean");
  await expect(page.locator("[data-role='portfolio-optimizer-return-model-panel']"))
    .toContainText("Black-Litterman");
  await expect(page.locator("[data-role='portfolio-optimizer-risk-model-panel']"))
    .toContainText("Diagonal Shrink");
  await expect(page.locator("[data-role='portfolio-optimizer-constraints-panel']"))
    .toContainText("Max Asset Weight");
  await expect(page.locator("[data-role='portfolio-optimizer-constraints-panel']"))
    .toContainText("Gross Leverage");
  await expect(page.locator("[data-role='portfolio-optimizer-constraints-panel']"))
    .toContainText("Rebalance Tolerance");
  await expect(page.locator("[data-role='portfolio-optimizer-execution-assumptions-panel']"))
    .toHaveCount(0);
  await expect(
    page.locator(
      "[data-role='portfolio-optimizer-setup-route-surface'] select, " +
      "[data-role='portfolio-optimizer-setup-route-surface'] input[type='number'], " +
      "[data-role='portfolio-optimizer-setup-route-surface'] input[type='date'], " +
      "[data-role='portfolio-optimizer-setup-route-surface'] input[type='time'], " +
      "[data-role='portfolio-optimizer-setup-route-surface'] input[type='color'], " +
      "[data-role='portfolio-optimizer-setup-route-surface'] input[type='file']"
    )
  ).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-instrument-overrides-panel']"))
    .toContainText("Per-Asset Overrides");

  await modelPanel.locator("summary").click();
  await expect.poll(async () => modelPanel.evaluate((element) => element.open)).toBe(true);
  await expect(returnModelPanel).toBeVisible();
  await expect(riskModelPanel).toBeVisible();

  await constraintsPanel.locator("summary").click();
  await expect.poll(async () => constraintsPanel.evaluate((element) => element.open)).toBe(true);
  await expect(maxAssetWeight).toBeVisible();

  const maxSharpe = page.locator("[data-role='portfolio-optimizer-objective-max-sharpe']");
  const blackLitterman = page.locator("[data-role='portfolio-optimizer-return-model-black-litterman']");
  const sampleCovariance = page.locator("[data-role='portfolio-optimizer-risk-model-sample-covariance']");

  await expect(maxSharpe).toHaveAttribute("aria-pressed", "false");
  await maxSharpe.click();
  await expect(maxSharpe).toHaveAttribute("aria-pressed", "true");

  await expect(blackLitterman).toHaveAttribute("aria-pressed", "false");
  await blackLitterman.click();
  await expect(blackLitterman).toHaveAttribute("aria-pressed", "true");

  await expect(sampleCovariance).toHaveAttribute("aria-pressed", "false");
  await sampleCovariance.click();
  await expect(sampleCovariance).toHaveAttribute("aria-pressed", "true");

  const longOnly = page.locator("[data-role='portfolio-optimizer-constraint-long-only-input']");
  await expect(longOnly).not.toBeChecked();
  await longOnly.check();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(longOnly).toBeChecked();

  await expect(maxAssetWeight).toHaveValue("0.5");
  await maxAssetWeight.fill("0.3");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(maxAssetWeight).toHaveValue("0.3");

  const targetReturn = page.locator(
    "[data-role='portfolio-optimizer-objective-target-return-input']"
  );
  await expect(targetReturn).toHaveValue("0.15");
  await targetReturn.fill("0.18");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(targetReturn).toHaveValue("0.18");
});

test("portfolio optimizer universe search uses one integrated shell @regression", async ({ page }) => {
  const reviewViewports = [
    { width: 375, height: 812 },
    { width: 768, height: 1024 },
    { width: 1280, height: 900 },
    { width: 1440, height: 900 }
  ];

  for (const viewport of reviewViewports) {
    await page.setViewportSize(viewport);
    await visitRoute(page, "/portfolio/optimize/new");
    await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
    await seedOptimizerAssetSelectorMarkets(page);

    const searchShell = page.locator("[data-role='portfolio-optimizer-universe-search-shell']");
    const searchInput = page.locator("[data-role='portfolio-optimizer-universe-search-input']");
    const searchIcon = page.locator("[data-role='portfolio-optimizer-universe-search-icon']");
    const clearButton = page.locator("[data-role='portfolio-optimizer-universe-search-clear']");
    const addHint = page.locator("[data-role='portfolio-optimizer-universe-search-add-hint']");

    await searchInput.fill("eth");
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

    await expect(searchShell).toHaveAttribute("data-searching", "true");
    await expect(searchIcon).toBeVisible();
    await expect(clearButton).toBeVisible();
    await expect(addHint).toBeVisible();

    const styles = await searchShell.evaluate((shell) => {
      const read = (selector) => {
        const element = shell.querySelector(selector);
        const style = window.getComputedStyle(element);
        const rect = element.getBoundingClientRect();
        return {
          backgroundColor: style.backgroundColor,
          borderColor: style.borderColor,
          borderRadius: style.borderRadius,
          color: style.color,
          marginBottom: style.marginBottom,
          marginTop: style.marginTop,
          textTransform: style.textTransform,
          whiteSpace: style.whiteSpace,
          rect: {
            left: rect.left,
            right: rect.right,
            top: rect.top,
            bottom: rect.bottom,
            width: rect.width,
            height: rect.height
          }
        };
      };
      const shellStyle = window.getComputedStyle(shell);
      const shellRect = shell.getBoundingClientRect();
      return {
        shell: {
          backgroundColor: shellStyle.backgroundColor,
          borderColor: shellStyle.borderColor,
          rect: {
            left: shellRect.left,
            right: shellRect.right,
            top: shellRect.top,
            bottom: shellRect.bottom,
            width: shellRect.width,
            height: shellRect.height
          }
        },
        icon: read("[data-role='portfolio-optimizer-universe-search-icon']"),
        input: read("[data-role='portfolio-optimizer-universe-search-input']"),
        clear: read("[data-role='portfolio-optimizer-universe-search-clear']"),
        addHint: read("[data-role='portfolio-optimizer-universe-search-add-hint']")
      };
    });

    expect(styles.shell.backgroundColor).not.toBe("rgba(0, 0, 0, 0)");
    expect(styles.shell.borderColor).toBe("rgb(212, 181, 88)");
    expect(styles.icon.backgroundColor).toBe("rgba(0, 0, 0, 0)");
    expect(styles.input.backgroundColor).toBe("rgba(0, 0, 0, 0)");
    expect(styles.input.borderColor).toBe("rgba(0, 0, 0, 0)");
    expect(styles.clear.backgroundColor).toBe("rgba(0, 0, 0, 0)");
    expect(styles.addHint.backgroundColor).toBe("rgba(0, 0, 0, 0)");
    expect(styles.addHint.borderColor).not.toBe("rgba(0, 0, 0, 0)");
    expect(parseFloat(styles.addHint.borderRadius)).toBeGreaterThanOrEqual(4);
    expect(styles.addHint.color).not.toBe(styles.shell.borderColor);
    expect(parseFloat(styles.addHint.marginTop)).toBeGreaterThanOrEqual(4);
    expect(parseFloat(styles.addHint.marginBottom)).toBeGreaterThanOrEqual(4);
    expect(styles.addHint.textTransform).toBe("none");
    expect(styles.addHint.whiteSpace).toBe("nowrap");
    expect(styles.shell.rect.left).toBeGreaterThanOrEqual(0);
    expect(styles.shell.rect.right).toBeLessThanOrEqual(viewport.width + 1);
    expect(styles.addHint.rect.left).toBeGreaterThanOrEqual(styles.input.rect.right);
    expect(styles.addHint.rect.right).toBeLessThanOrEqual(styles.shell.rect.right + 1);
  }
});

test("portfolio optimizer manual universe builder adds and removes assets @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
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
  await expect(searchInput).toHaveValue("");
  await expect(page.locator("[data-role='portfolio-optimizer-universe-search-results']"))
    .toHaveCount(0);
  await expect(ethCandidate).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-universe-panel']"))
    .toContainText("Requires history reload after adding new assets.");
  await expect(page.locator("[data-role='portfolio-optimizer-run-draft']")).toBeEnabled();
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .toContainText("Run Optimization will refresh history for this changed universe.");

  await ethRemove.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(ethRemove).toHaveCount(0);
  await searchInput.fill("eth");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(ethCandidate).toBeVisible();
});

test("portfolio optimizer manual universe builder adds and removes vaults @regression", async ({ page }) => {
  await stubOptimizerVaultMetadata(page);
  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
  await seedOptimizerVaultRows(page);

  const vaultKey = `vault:${OPTIMIZER_VAULT_ADDRESS}`;
  const searchInput = page.locator("[data-role='portfolio-optimizer-universe-search-input']");
  const vaultRow = page.locator(
    `[data-role='portfolio-optimizer-universe-candidate-row-${vaultKey}']`
  );
  const vaultAdd = page.locator(`[data-role='portfolio-optimizer-universe-add-${vaultKey}']`);
  const vaultSelected = page.locator(
    `[data-role='portfolio-optimizer-universe-selected-row-${vaultKey}']`
  );
  const vaultRemove = page.locator(
    `[data-role='portfolio-optimizer-universe-remove-${vaultKey}']`
  );

  await expect(searchInput).toHaveAttribute("placeholder", /vault/);
  await searchInput.fill("alpha");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(vaultRow).toBeVisible();
  await expect(vaultRow).toContainText("Alpha Yield");
  await expect(vaultRow).toContainText("vault");
  await vaultAdd.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(vaultSelected).toBeVisible();
  await expect(vaultSelected).toContainText("Alpha Yield");
  await expect(vaultSelected).toContainText("vault");
  await expect(vaultRemove).toBeVisible();
  await expect(searchInput).toHaveValue("");
  await expect(page.locator("[data-role='portfolio-optimizer-universe-search-results']"))
    .toHaveCount(0);

  await vaultRemove.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(vaultSelected).toHaveCount(0);
  await searchInput.fill("alpha");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(vaultAdd).toBeVisible();
});

test("portfolio optimizer manual universe search supports keyboard selection @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
  await seedOptimizerAssetSelectorMarkets(page);

  const searchInput = page.locator("[data-role='portfolio-optimizer-universe-search-input']");
  const activeCandidate = page.locator(
    "[data-role^='portfolio-optimizer-universe-candidate-row-'][data-active='true']"
  );

  await searchInput.fill("h");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(activeCandidate).toHaveCount(1);
  const firstActiveRole = await activeCandidate.getAttribute("data-role");
  await searchInput.press("ArrowDown");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(activeCandidate).toHaveCount(1);
  const secondActiveRole = await activeCandidate.getAttribute("data-role");
  expect(secondActiveRole).not.toEqual(firstActiveRole);
  const selectedMarketKey = secondActiveRole.replace(
    "portfolio-optimizer-universe-candidate-row-",
    ""
  );

  await searchInput.press("Enter");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(
    page.locator(`[data-role='portfolio-optimizer-universe-remove-${selectedMarketKey}']`)
  ).toBeVisible();
  await expect(searchInput).toHaveValue("");
  await expect(page.locator("[data-role='portfolio-optimizer-universe-search-results']"))
    .toHaveCount(0);
});

test("portfolio optimizer selected universe keeps remove controls visible for long assets @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");

  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const instrument = (instrumentId, coin) =>
      c.PersistentArrayMap.fromArray(
        [
          kw("instrument-id"), instrumentId,
          kw("market-type"), kw("perp"),
          kw("coin"), coin,
          kw("symbol"), `${coin}-USDC`
        ],
        true
      );
    const universe = c.PersistentVector.fromArray(
      [
        instrument("perp:CFX", "CFX"),
        instrument("perp:REZ", "REZ"),
        instrument("perp:KAITO", "KAITO"),
        instrument("perp:XYZ:GOLD", "xyz:GOLD"),
        instrument("perp:XYZ:AAPL", "xyz:AAPL"),
        instrument("perp:XYZ:SILVER", "xyz:SILVER"),
        instrument("perp:XYZ:BRENTOIL", "xyz:BRENTOIL")
      ],
      true
    );
    const draft = c.PersistentArrayMap.fromArray(
      [
        kw("universe"), universe,
        kw("objective"), c.PersistentArrayMap.fromArray([kw("kind"), kw("minimum-variance")], true),
        kw("return-model"), c.PersistentArrayMap.fromArray([kw("kind"), kw("historical-mean")], true),
        kw("risk-model"), c.PersistentArrayMap.fromArray([kw("kind"), kw("diagonal-shrink")], true),
        kw("constraints"), c.PersistentArrayMap.fromArray([kw("long-only?"), true], true)
      ],
      true
    );
    const state = c.deref(globalThis.hyperopen.system.store);
    c.reset_BANG_(
      globalThis.hyperopen.system.store,
      c.assoc_in(state, path("portfolio", "optimizer", "draft"), draft)
    );
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const panel = page.locator("[data-role='portfolio-optimizer-universe-panel']");
  const longAssetRow = page.locator(
    "[data-role='portfolio-optimizer-universe-selected-row-perp:XYZ:BRENTOIL']"
  );
  const longAssetRemove = page.locator(
    "[data-role='portfolio-optimizer-universe-remove-perp:XYZ:BRENTOIL']"
  );

  await expect(panel).toContainText("7 included");
  await expect(longAssetRow).toBeVisible();
  await expect(longAssetRemove).toBeVisible();

  const [panelBox, removeBox] = await Promise.all([
    panel.boundingBox(),
    longAssetRemove.boundingBox()
  ]);
  expect(panelBox).not.toBeNull();
  expect(removeBox).not.toBeNull();
  expect(removeBox.x + removeBox.width).toBeLessThanOrEqual(panelBox.x + panelBox.width);

  await longAssetRemove.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(longAssetRemove).toHaveCount(0);
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
  await expect(page.locator("[data-role='portfolio-optimizer-load-history']")).toHaveCount(0);
  await page.locator("[data-role='portfolio-optimizer-run-draft']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-progress-panel']"))
    .toContainText("Optimization", { timeout: 10_000 });
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .toContainText("Optimizer history is loaded.", { timeout: 10_000 });

  expect(historyRequests.sort()).toEqual([
    "candleSnapshot:BTC",
    "candleSnapshot:ETH",
    "fundingHistory:BTC",
    "fundingHistory:ETH"
  ]);
});

test("portfolio optimizer recommendation chart shows minimum variance frontier overlays and honest target weights @regression", async ({ page }) => {
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

  await expect(page.locator("[data-role='portfolio-optimizer-load-history']")).toHaveCount(0);
  await page.locator("[data-role='portfolio-optimizer-run-draft']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-progress-panel']"))
    .toContainText("Optimization", { timeout: 10_000 });
  await expect(page.locator("[data-role='portfolio-optimizer-run-status-panel']"))
    .toContainText("Succeeded", { timeout: 10_000 });
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']"))
    .toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-last-successful-run']"))
    .toContainText("Last successful result is available for comparison.");
  await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-view-weights']"))
    .toBeVisible();
  await page.locator("[data-role='portfolio-optimizer-view-weights']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(page).toHaveURL(/\/portfolio\/optimize\/draft/);
  await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
    .toContainText("Allocation");
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-panel']"))
    .toContainText("Efficient Frontier");
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-current-marker']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-legend']"))
    .not.toContainText("Where you are now");
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-legend']"))
    .toContainText("Target");
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-legend']"))
    .not.toContainText("Recommended target");
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-legend']"))
    .toContainText("Efficient frontier");
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-legend']"))
    .toContainText("Standalone assets");
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-x-axis-label']"))
    .toHaveText("Volatility (Annualized)");
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-y-axis-label']"))
    .toHaveText("Expected Return (Annualized)");
  await expect.poll(async () => page.locator("[data-role^='portfolio-optimizer-frontier-x-tick-']").count())
    .toBeGreaterThanOrEqual(5);
  await expect.poll(async () => page.locator("[data-role^='portfolio-optimizer-frontier-y-tick-']").count())
    .toBeGreaterThanOrEqual(5);
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-x-axis-ticks']"))
    .toContainText("0%");
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-y-axis-ticks']"))
    .toContainText("0%");
  const constrainFrontierControl = page.locator("[data-role='portfolio-optimizer-constrain-frontier-control']");
  const constrainFrontierCheckbox = page.locator("[data-role='portfolio-optimizer-constrain-frontier-checkbox']");
  await expect(constrainFrontierControl).toBeVisible();
  await expect(constrainFrontierControl).toContainText("Constrain Frontier");
  await expect(constrainFrontierCheckbox).not.toBeChecked();
  expect(await constrainFrontierCheckbox.evaluate((input) => {
    const rect = input.getBoundingClientRect();
    const styles = window.getComputedStyle(input);
    return {
      width: Math.round(rect.width),
      height: Math.round(rect.height),
      radius: styles.borderRadius,
    };
  })).toEqual({ width: 14, height: 14, radius: "2px" });
  expect(await page.locator("[data-role='portfolio-optimizer-frontier-svg']").evaluate((svg) => {
    const xAxis = svg.querySelector("[data-role='portfolio-optimizer-frontier-x-axis-label']");
    const yAxis = svg.querySelector("[data-role='portfolio-optimizer-frontier-y-axis-label']");
    const svgRect = svg.getBoundingClientRect();
    const xAxisRect = xAxis.getBoundingClientRect();
    const yAxisRect = yAxis.getBoundingClientRect();
    const viewBox = svg.viewBox.baseVal;
    const scaleX = svgRect.width / viewBox.width;
    const scaleY = svgRect.height / viewBox.height;
    const xCenter = svgRect.left + Number(xAxis.getAttribute("x")) * scaleX;
    const yCenter = svgRect.top + Number(yAxis.getAttribute("y")) * scaleY;
    return xAxis.getAttribute("text-anchor") === "middle"
      && yAxis.getAttribute("text-anchor") === "middle"
      && Math.abs((xAxisRect.left + xAxisRect.width / 2) - xCenter) <= 2
      && Math.abs((yAxisRect.top + yAxisRect.height / 2) - yCenter) <= 3;
  })).toBe(true);
  const frontierPath = page.locator("[data-role='portfolio-optimizer-frontier-path']");
  await expect(frontierPath).toBeVisible();
  await expect.poll(async () => await frontierPath.getAttribute("d"))
    .toMatch(/\bL\b/);
  await expect.poll(async () =>
    page.locator("[data-role^='portfolio-optimizer-frontier-point-'][data-frontier-drag-target='true']").count()
  ).toBeGreaterThanOrEqual(8);
  await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
    .not.toContainText("display-frontier-unavailable");
  const standaloneFrontierPath = await frontierPath.getAttribute("d");
  await constrainFrontierCheckbox.check();
  await expect(constrainFrontierCheckbox).toBeChecked();
  await expect(frontierPath).toHaveAttribute("d", standaloneFrontierPath);
  await constrainFrontierCheckbox.uncheck();
  await expect(constrainFrontierCheckbox).not.toBeChecked();
  await expect(constrainFrontierCheckbox).toHaveCSS("box-shadow", "none");
  await expect.poll(async () => await frontierPath.getAttribute("d"))
    .toBe(standaloneFrontierPath);
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-overlay-mode-standalone']"))
    .toHaveAttribute("aria-pressed", "true");
  const modeButtonsFit = async () => page.locator("[data-role^='portfolio-optimizer-frontier-overlay-mode-']")
    .evaluateAll((buttons) => buttons.every((button) => button.scrollWidth <= button.clientWidth));
  expect(await modeButtonsFit()).toBe(true);
  const modeButtonPositions = async () => page.locator("[data-role^='portfolio-optimizer-frontier-overlay-mode-']")
    .evaluateAll((buttons) => buttons.map((button) => {
      const rect = button.getBoundingClientRect();
      return {
        left: Math.round(rect.left),
        top: Math.round(rect.top),
        width: Math.round(rect.width),
        height: Math.round(rect.height),
      };
    }));
  const standaloneModeButtonPositions = await modeButtonPositions();
  await page.locator("[data-role='portfolio-optimizer-frontier-overlay-mode-none']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-overlay-mode-none']"))
    .toHaveAttribute("aria-pressed", "true");
  expect(await modeButtonPositions()).toEqual(standaloneModeButtonPositions);
  expect(await modeButtonsFit()).toBe(true);
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-overlay-mode-none']"))
    .toHaveCSS("box-shadow", /rgb\(212, 181, 88\)/);
  await page.locator("[data-role='portfolio-optimizer-frontier-overlay-mode-standalone']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-overlay-mode-standalone']"))
    .toHaveAttribute("aria-pressed", "true");
  const targetMarker = page.locator("[data-role='portfolio-optimizer-frontier-target-marker-hitbox']");
  const targetCore = page.locator("[data-role='portfolio-optimizer-frontier-target-core']");
  const targetHalo = page.locator("[data-role='portfolio-optimizer-frontier-target-halo']");
  const targetRing = page.locator("[data-role='portfolio-optimizer-frontier-target-ring']");
  const targetLabel = page.locator("[data-role='portfolio-optimizer-frontier-target-label']");
  const targetLeaderLine = page.locator("[data-role='portfolio-optimizer-frontier-target-leader-line']");
  const targetCallout = page.locator("[data-role='portfolio-optimizer-frontier-callout-target']");
  await expect(targetCore).toHaveAttribute("fill", "url(#portfolioOptimizerTargetOrbGradient)");
  await expect(targetCore).toHaveAttribute("stroke", "rgba(246, 235, 255, 0.58)");
  await expect(targetCore).toHaveAttribute("r", "8");
  await expect(targetHalo).toHaveAttribute("fill", "url(#portfolioOptimizerTargetHaloGradient)");
  await expect(targetHalo).toHaveAttribute("r", "17");
  await expect(targetHalo).toHaveAttribute("opacity", "0.52");
  await expect(targetRing).toHaveAttribute("stroke", "url(#portfolioOptimizerTargetRingGradient)");
  await expect(targetRing).toHaveAttribute("strokeWidth", "1.15");
  await expect(targetRing).toHaveAttribute("opacity", "0.74");
  await expect(targetLabel).toBeVisible();
  await expect(targetLeaderLine).toHaveAttribute("stroke-dasharray", "3 3");
  await targetMarker.hover();
  await expect(targetCallout).toHaveCSS("opacity", "1");
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-callout-target-border']"))
    .toHaveAttribute("fill", "url(#portfolioOptimizerTargetTooltipBorderGradient)");
  await expect(targetCallout).toContainText("Target");
  await expect(targetCallout).toContainText("Expected Return");
  await expect(targetCallout).toContainText("Volatility");
  await expect(targetCallout).toContainText("Sharpe");
  await expect(targetCallout).toContainText("Gross Exposure");

  const standaloneMarkerGroup = page.locator("[data-role='portfolio-optimizer-frontier-overlay-standalone-perp:BTC']");
  const standaloneMarkerSymbol = page.locator("[data-role='portfolio-optimizer-frontier-overlay-symbol-standalone-perp:BTC']");
  const standaloneMarker = page.locator("[data-role='portfolio-optimizer-frontier-overlay-standalone-perp:BTC-hitbox']");
  const standaloneCallout = page.locator("[data-role='portfolio-optimizer-frontier-callout-standalone-perp:BTC']");
  await expect(standaloneMarker)
    .toBeVisible();
  await expect(standaloneMarkerSymbol.locator("image")).toHaveAttribute(
    "href",
    "https://app.hyperliquid.xyz/coins/BTC.svg"
  );
  await expect(standaloneMarkerGroup.locator("rect[transform*='rotate']")).toHaveCount(0);
  await standaloneMarker.hover();
  await expect(standaloneCallout).toHaveCSS("opacity", "1");
  await expect(standaloneMarkerGroup).toHaveCSS("outline-style", "none");
  await expect(standaloneCallout).toContainText("BTC");
  await expect(standaloneCallout).toContainText("Expected Return");
  await expect(standaloneCallout).toContainText("Target Weight");
  await expect(standaloneMarkerGroup.locator("title")).toHaveCount(0);
  await expect(standaloneCallout.locator("text[text-anchor='end']")).toHaveCount(4);
  await expect(standaloneCallout.locator("rect")).toHaveAttribute("stroke", "none");
  await expect(standaloneCallout.locator("line")).toHaveCount(1);
  const standaloneFocusRing = standaloneMarkerGroup.locator(".portfolio-frontier-focus-ring");
  await expect(standaloneFocusRing).toHaveCSS("display", "inline");
  await expect(standaloneFocusRing).not.toHaveCSS("stroke-dasharray", /3px/);
  expect(await standaloneCallout.evaluate((node) => {
    const rect = node.querySelector("rect").getBBox();
    return [...node.querySelectorAll("text")]
      .every((text) => {
        const box = text.getBBox();
        return box.x >= rect.x - 1 && (box.x + box.width) <= (rect.x + rect.width + 1);
      });
  })).toBe(true);
  await page.locator("[data-role='portfolio-optimizer-frontier-overlay-mode-contribution']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-overlay-mode-contribution']"))
    .toHaveAttribute("aria-pressed", "true");
  await expect(frontierPath).toHaveAttribute("d", standaloneFrontierPath);
  const contributionMarker = page.locator("[data-role='portfolio-optimizer-frontier-overlay-contribution-perp:BTC']");
  const contributionMarkerSymbol = page.locator("[data-role='portfolio-optimizer-frontier-overlay-symbol-contribution-perp:BTC']");
  const contributionCallout = page.locator("[data-role='portfolio-optimizer-frontier-callout-contribution-perp:BTC']");
  await expect(contributionMarker)
    .toBeVisible();
  await expect(contributionMarkerSymbol.locator("image")).toHaveAttribute(
    "href",
    "https://app.hyperliquid.xyz/coins/BTC.svg"
  );
  await expect(contributionMarker.locator("path")).toHaveCount(0);
  await contributionMarker.focus();
  await expect(contributionCallout).toHaveCSS("opacity", "1");
  await expect(contributionCallout).toContainText("Return Contribution");
  await expect(contributionCallout).toContainText("Volatility Contribution");
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-stale-banner']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-stale-result-banner']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-asset-BTC']"))
    .toContainText("BTC");

  const weights = await readOptimizerTargetWeights(page);
  const grossTarget = weights.reduce((sum, weight) => sum + Math.abs(weight), 0);
  expect(weights).toHaveLength(4);
  if (grossTarget < 0.01) {
    expect(weights.every((weight) => Math.abs(weight) < 0.01)).toBe(true);
  } else {
    expect(weights.some((weight) => Math.abs(weight) > 0.01)).toBe(true);
  }
});

test("portfolio optimizer persisted scenario hydrates results and tracking after reload @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await seedPersistedOptimizerTrackingScenario(page);

  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}`);

  const scenarioDetail = page.locator("[data-role='portfolio-optimizer-scenario-detail-surface']");
  const results = page.locator("[data-role='portfolio-optimizer-results-surface']");
  const tracking = page.locator("[data-role='portfolio-optimizer-tracking-panel']");

  await expect(scenarioDetail).toHaveAttribute("data-scenario-id", OPTIMIZER_RELOAD_SCENARIO_ID);
  await expect(results).toContainText("Funding Decomposition");
  await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-row-0']"))
    .toContainText("perp:BTC");
  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}?otab=tracking`);
  await expect(tracking).toContainText("Weight Drift RMS");
  await expect(tracking).toContainText("Predicted Vol");
  await expect(tracking).toContainText("Drift Chart");
  await expect(tracking).toContainText("Realized vs Predicted");
  await expect(tracking).toContainText("Re-optimize From Current");
  await expect(page.locator("[data-role='portfolio-optimizer-tracking-row-0']"))
    .toContainText("perp:BTC");

  await page.reload();
  await waitForDebugBridge(page);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });
  await expect(page.locator("[data-parity-id='app-route-module-shell']"))
    .toHaveCount(0, { timeout: 15_000 });

  await expect(scenarioDetail).toHaveAttribute("data-scenario-id", OPTIMIZER_RELOAD_SCENARIO_ID);
  await page.locator("[data-role='portfolio-optimizer-scenario-tab-tracking']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tracking).toContainText("Realized Return");
  await expect(tracking).toContainText("38.00%");
  await expect(page.locator("[data-role='portfolio-optimizer-tracking-row-1']"))
    .toContainText("perp:ETH");
});

test("portfolio optimizer rerun keeps last successful result visible @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await seedPersistedOptimizerTrackingScenario(page);
  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}`);
  await seedOptimizerRerunInFlight(page);

  await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
    .toContainText("Funding Decomposition");
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-rerun']"))
    .toBeDisabled();
});

test("portfolio optimizer execution remains read-only in Spectate Mode @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await seedPersistedOptimizerTrackingScenario(page);
  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}?otab=rebalance`);
  await enableOptimizerSpectateMode(page);

  await page.locator("[data-role='portfolio-optimizer-open-execution-modal']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const modal = page.locator("[data-role='portfolio-optimizer-execution-modal']");
  await expect(modal).toContainText("Cost Source");
  await expect(modal).toContainText("Margin After");
  await expect(modal).toContainText(
    "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds."
  );
  await expect(page.locator("[data-role='portfolio-optimizer-execution-modal-confirm']"))
    .toBeDisabled();
});

test("portfolio optimizer execution modal surfaces failed attempt recovery details @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await seedPersistedOptimizerTrackingScenario(page);
  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}?otab=rebalance`);
  await seedOptimizerFailedExecutionAttempt(page);

  await page.locator("[data-role='portfolio-optimizer-open-execution-modal']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const latestAttempt = page.locator("[data-role='portfolio-optimizer-execution-latest-attempt']");
  await expect(page.locator("[data-role='portfolio-optimizer-execution-modal']"))
    .toContainText("live-orderbook");
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
