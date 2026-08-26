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
const OPTIMIZER_VAULT_LEADER_ADDRESS = "0x2222222222222222222222222222222222222222";
const OPTIMIZER_EXACT_HLP_VAULT_ADDRESS = "0x4444444444444444444444444444444444444444";
const OPTIMIZER_LARGE_HLP_VAULT_ADDRESS = "0x5555555555555555555555555555555555555555";
const OPTIMIZER_MID_HLP_VAULT_ADDRESS = "0x6666666666666666666666666666666666666666";
const OPTIMIZER_DEFAULT_VAULT_SUMMARY = {
  vaultAddress: OPTIMIZER_VAULT_ADDRESS,
  name: "Alpha Yield",
  leader: OPTIMIZER_VAULT_LEADER_ADDRESS,
  tvl: "5000000",
  relationship: { type: "normal" },
  createTimeMillis: 1777045900000
};
const OPTIMIZER_HLP_VAULT_SUMMARIES = [
  {
    vaultAddress: OPTIMIZER_EXACT_HLP_VAULT_ADDRESS,
    name: "HLP",
    leader: OPTIMIZER_VAULT_LEADER_ADDRESS,
    tvl: "0",
    relationship: { type: "normal" },
    createTimeMillis: 1777045900000
  },
  {
    vaultAddress: OPTIMIZER_LARGE_HLP_VAULT_ADDRESS,
    name: "Hyperliquid HLP Provider",
    leader: OPTIMIZER_VAULT_LEADER_ADDRESS,
    tvl: "397000000",
    relationship: { type: "normal" },
    createTimeMillis: 1777045900000
  },
  {
    vaultAddress: OPTIMIZER_MID_HLP_VAULT_ADDRESS,
    name: "HLP Rule",
    leader: OPTIMIZER_VAULT_LEADER_ADDRESS,
    tvl: "5",
    relationship: { type: "normal" },
    createTimeMillis: 1777045900000
  }
];
const OPTIMIZER_RELOAD_SCENARIO_ID = "scn_playwright_tracking_reload";
const PORTFOLIO_LEDGER_REVIEW_VIEWPORTS = [
  { width: 375, height: 812 },
  { width: 768, height: 900 },
  { width: 1280, height: 900 },
  { width: 1440, height: 900 }
];
const PORTFOLIO_LEDGER_FIXTURE = [
  {
    time: 1772460616000,
    hash: "0xdeposit001",
    delta: { type: "deposit", usdc: "100.0" }
  },
  {
    time: 1772300494000,
    hash: "0xvault001",
    delta: { type: "vaultDeposit", usdc: "10.0" }
  },
  {
    time: 1765148052000,
    hash: "0xgenesis001",
    delta: { type: "spotGenesis", token: "HYPE", amount: "2670.03" }
  },
  {
    time: 1765529064000,
    hash: "0xsend001",
    delta: { type: "internalTransfer", coin: "HYPE", amount: "1" }
  },
  {
    // Incoming: the seeded wallet is this delta's destination, so the row must
    // render as a credit. The pre-parity table hardcoded every spot transfer
    // negative regardless of direction.
    time: 1765529065000,
    hash: "0xspotin001",
    delta: {
      type: "spotTransfer",
      token: "HYPE",
      amount: "5",
      usdcValue: "212.5",
      user: "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      destination: "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
  }
];
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

async function selectOptimizerScenarioTab(page, tabValue) {
  const tab = page.locator(`[data-role='portfolio-optimizer-scenario-tab-${tabValue}']`);
  await tab.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect.poll(async () => tab.evaluate((element) =>
    element.getAttribute("aria-pressed") === "true" ||
    element.getAttribute("aria-current") === "page"
  )).toBe(true);
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

async function seedPortfolioWalletAddress(page, address) {
  await page.evaluate((walletAddress) => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    c.reset_BANG_(
      globalThis.hyperopen.system.store,
      c.assoc_in(
        c.deref(globalThis.hyperopen.system.store),
        c.PersistentVector.fromArray([kw("wallet"), kw("address")], true),
        walletAddress
      )
    );
  }, address);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function seedPortfolioWebdata2(page, webdata2) {
  await page.evaluate((payload) => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const opts = c.PersistentArrayMap.fromArray([kw("keywordize-keys"), true], true);
    const store = globalThis.hyperopen.system.store;
    const nextState = c.assoc_in(
      c.deref(store),
      c.PersistentVector.fromArray([kw("webdata2")], true),
      c.js__GT_clj(payload, opts)
    );
    c.reset_BANG_(store, nextState);
  }, webdata2);
}

async function seedPortfolioLedgerRows(page, rows) {
  await page.evaluate((payload) => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const opts = c.PersistentArrayMap.fromArray([kw("keywordize-keys"), true], true);
    const store = globalThis.hyperopen.system.store;
    const ledgerRows = c.js__GT_clj(payload, opts);
    const withRows = c.assoc_in(c.deref(store), path("portfolio", "ledger-updates"), ledgerRows);
    const withLoading = c.assoc_in(withRows, path("portfolio", "ledger-loading?"), false);
    const nextState = c.assoc_in(withLoading, path("portfolio", "ledger-error"), null);

    c.reset_BANG_(store, nextState);
  }, rows);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function stubPortfolioLedgerRows(page, rows, observedRequests = []) {
  await page.route("**/info", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      try {
        const payload = request.postDataJSON();
        if (payload?.type === "userNonFundingLedgerUpdates") {
          observedRequests.push(payload);
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify(rows)
          });
          return;
        }
      } catch {
        // Let non-JSON requests continue.
      }
    }
    await route.continue();
  });
}

function optimizerApiV2HistoryBundleResponse(payload, requestId = "rid-portfolio-prefetch") {
  const requestedIds = payload.instruments.map((instrument) => instrument.client_instrument_id);
  const baseByInstrument = {
    "perp:BTC": 100,
    "perp:ETH": 200,
    "perp:SOL": 50,
    "perp:HYPE": 10
  };
  const seriesFor = (instrumentId) => {
    const base = baseByInstrument[instrumentId] || 100;
    return {
      instrument_id: `hl:${instrumentId}`,
      lineage_kind: "native",
      series_kind: "market_price",
      points: [
        { time_ms: 1000, close: base, return: null, component: "native" },
        { time_ms: 2000, close: base * 1.04, return: 0.04, component: "native" },
        { time_ms: 3000, close: base * 1.0608, return: 0.02, component: "native" }
      ],
      funding: {
        status: "available",
        source: "hyperliquid:fundingHistory",
        annualized_carry: 0.012
      },
      warnings: []
    };
  };

  return {
    contract_version: "optimizer-history-api-v2",
    request_id: requestId,
    dataset_version: "dv-portfolio-prefetch",
    status: "ok",
    common_calendar: [1000, 2000, 3000],
    return_calendar: [2000, 3000],
    aligned_returns_by_instrument: Object.fromEntries(
      requestedIds.map((instrumentId) => [
        instrumentId,
        { instrument_id: `hl:${instrumentId}`, returns: [0.04, 0.02] }
      ])
    ),
    series_by_instrument: Object.fromEntries(
      requestedIds.map((instrumentId) => [instrumentId, seriesFor(instrumentId)])
    ),
    warnings: []
  };
}

async function stubOptimizerHistoryBundle(page, observedRequests = []) {
  await page.route("https://price-history.hyperopen.xyz/v1/optimizer/history-bundle", async (route) => {
    const payload = route.request().postDataJSON();
    observedRequests.push(payload);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(optimizerApiV2HistoryBundleResponse(payload))
    });
  });
}

async function stubPortfolioSummaryInfo(page, summaryByRange) {
  await page.route("**/info", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      try {
        const payload = request.postDataJSON();
        if (payload?.type === "portfolio") {
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ data: summaryByRange })
          });
          return;
        }
        if (payload?.type === "userFees") {
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ dailyUserVlm: [] })
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

async function seedNearYearMonteCarloForecast(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const row = (timeMs, value) => c.PersistentVector.fromArray([timeMs, value], true);
    const startMs = Date.UTC(2025, 0, 1);
    const weekMs = 7 * 24 * 60 * 60 * 1000;
    const accountRows = [];
    const pnlRows = [];
    for (let idx = 0; idx < 53; idx += 1) {
      const timeMs = startMs + idx * weekMs;
      accountRows.push(row(timeMs, 10_000 + idx * 20));
      pnlRows.push(row(timeMs, idx * 10));
    }
    const rows = (items) => c.PersistentVector.fromArray(items, true);
    const summary = c.PersistentArrayMap.fromArray(
      [
        kw("accountValueHistory"), rows(accountRows),
        kw("pnlHistory"), rows(pnlRows)
      ],
      true
    );
    const summaryByKey = c.PersistentArrayMap.fromArray([kw("all-time"), summary], true);
    const controls = c.PersistentArrayMap.fromArray(
      [
        kw("method"), kw("bootstrap"),
        kw("sims"), 1000,
        kw("horizon"), 12,
        kw("bust"), -30,
        kw("goal"), 50,
        kw("seed"), 42,
        kw("run-nonce"), 0
      ],
      true
    );
    const store = globalThis.hyperopen.system.store;
    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, path("portfolio", "summary-by-key"), summaryByKey);
    nextState = c.assoc_in(nextState, path("portfolio-ui", "summary-time-range"), kw("all-time"));
    nextState = c.assoc_in(nextState, path("portfolio-ui", "account-info-tab"), kw("monte-carlo"));
    nextState = c.assoc_in(nextState, path("portfolio-ui", "monte-carlo"), controls);
    c.reset_BANG_(store, nextState);
  });
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

async function seedOptimizerCurrentResultPoint(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
    const vector = (items) => c.PersistentVector.fromArray(items, true);
    const store = globalThis.hyperopen.system.store;
    const state = c.deref(store);
    const resultPath = path("portfolio", "optimizer", "last-successful-run", "result");
    const result = c.get_in(state, resultPath);
    const labels = c.assoc(
      c.get(result, kw("labels-by-instrument")) || c.PersistentArrayMap.EMPTY,
      "perp:HYPE",
      "HYPE"
    );
    const withCurrentPoint = c.assoc(
      result,
      kw("current-weights"), vector([0, 0, 0, 0]),
      kw("current-portfolio-instrument-ids"), vector(["perp:HYPE"]),
      kw("current-portfolio-weights"), vector([0.3]),
      kw("current-portfolio-weights-by-instrument"), map(["perp:HYPE", 0.3]),
      kw("labels-by-instrument"), labels,
      kw("current-expected-return"), 0.02,
      kw("current-volatility"), 0.3,
      kw("current-performance"), map([kw("in-sample-sharpe"), 0.067])
    );
    c.reset_BANG_(store, c.assoc_in(state, resultPath, withCurrentPoint));
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function stubOptimizerVaultMetadata(page, vaultSummaries = null) {
  const summaries = vaultSummaries ?? [OPTIMIZER_DEFAULT_VAULT_SUMMARY];

  await page.route("https://stats-data.hyperliquid.xyz/Mainnet/vaults", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(summaries.map((summary) => ({ summary })))
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
            body: JSON.stringify(summaries)
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

async function seedOptimizerAssetSelectorFullPhase(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const store = globalThis.hyperopen.system.store;
    let state = c.assoc_in(c.deref(store), path("asset-selector", "phase"), kw("full"));
    state = c.assoc_in(
      state,
      path("asset-selector", "markets"),
      c.PersistentVector.fromArray([], true)
    );
    state = c.assoc_in(
      state,
      path("asset-selector", "market-by-key"),
      c.PersistentArrayMap.fromArray([], true)
    );
    c.reset_BANG_(store, state);
  });
}

async function putOptimizerVaultIndexCacheRecord(page, summaries) {
  await page.evaluate(async (vaultSummaries) => {
    const storeNames = [
      "asset-selector-markets-cache",
      "funding-history-cache",
      "chart-visible-range-cache",
      "vault-index-cache",
      "leaderboard-preferences",
      "leaderboard-cache",
      "agent-locked-session",
      "portfolio-optimizer"
    ];
    const cacheRecord = {
      id: "vault-index-cache",
      version: 1,
      "saved-at-ms": 1777046000000,
      etag: "\"optimizer-cache\"",
      "last-modified": "Thu, 30 Apr 2026 10:00:00 GMT",
      rows: vaultSummaries
    };
    const metadataRecord = {
      id: "vault-index-cache:metadata",
      version: 1,
      "saved-at-ms": cacheRecord["saved-at-ms"],
      etag: cacheRecord.etag,
      "last-modified": cacheRecord["last-modified"]
    };
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open("hyperopen-persistence", 7);
      request.onupgradeneeded = (event) => {
        const database = event.target.result;
        for (const storeName of storeNames) {
          if (!database.objectStoreNames.contains(storeName)) {
            database.createObjectStore(storeName);
          }
        }
      };
      request.onsuccess = (event) => resolve(event.target.result);
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error("IndexedDB open blocked"));
    });

    await new Promise((resolve, reject) => {
      const transaction = db.transaction(["vault-index-cache"], "readwrite");
      const store = transaction.objectStore("vault-index-cache");
      store.put(cacheRecord, "vault-index-cache");
      store.put(metadataRecord, "vault-index-cache:metadata");
      transaction.oncomplete = () => resolve(true);
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error);
    });
    db.close();
  }, summaries);
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

async function seedOptimizerHlpVaultRows(page) {
  await page.evaluate(
    (summaries) => {
      const c = globalThis.cljs.core;
      const kw = (name) => c.keyword(name);
      const path = (...segments) =>
        c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
      const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
      const row = ({ name, vaultAddress, leader, tvl }) =>
        map([
          kw("name"), name,
          kw("vault-address"), vaultAddress,
          kw("leader"), leader,
          kw("tvl"), Number(tvl),
          kw("relationship"), map([kw("type"), kw("normal")])
        ]);
      const rows = c.PersistentVector.fromArray(summaries.map(row), true);
      const store = globalThis.hyperopen.system.store;
      c.reset_BANG_(
        store,
        c.assoc_in(c.deref(store), path("vaults", "merged-index-rows"), rows)
      );
    },
    OPTIMIZER_HLP_VAULT_SUMMARIES
  );
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

async function appendOptimizerBtcHistoryPoint(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const candle = c.PersistentArrayMap.fromArray(
      [kw("time"), 1777145600000, kw("close"), "109"],
      true
    );
    const btcHistoryPath = c.PersistentVector.fromArray(
      [
        kw("portfolio"),
        kw("optimizer"),
        kw("history-data"),
        kw("candle-history-by-coin"),
        "BTC"
      ],
      true
    );
    const store = globalThis.hyperopen.system.store;
    const state = c.deref(store);
    const candles = c.get_in(state, btcHistoryPath) || c.PersistentVector.EMPTY;
    c.reset_BANG_(
      store,
      c.assoc_in(state, btcHistoryPath, c.conj(candles, candle))
    );
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
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

async function seedOptimizerMisalignedVaultHistory(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const vector = (items) => c.PersistentVector.fromArray(items, true);
    const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
    const dayMs = 24 * 60 * 60 * 1000;
    const dayStartMs = (day) => new Date(`${day}T00:00:00.000Z`).getTime();
    const summaryFromPoints = (points) =>
      map([
        kw("accountValueHistory"),
        vector(points.map(([timeMs, accountValue]) => vector([timeMs, accountValue]))),
        kw("pnlHistory"),
        vector(points.map(([timeMs, _accountValue, pnlValue]) => vector([timeMs, pnlValue])))
      ]);
    const vaultInstrument = (vaultAddress, name) => {
      const vaultId = `vault:${vaultAddress}`;
      return map([
        kw("instrument-id"), vaultId,
        kw("market-type"), kw("vault"),
        kw("coin"), vaultId,
        kw("vault-address"), vaultAddress,
        kw("name"), name
      ]);
    };
    const vaultA = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const vaultB = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    const a0 = dayStartMs("2026-04-01");
    const a1 = dayStartMs("2026-04-02");
    const b0 = dayStartMs("2026-04-10");
    const b1 = dayStartMs("2026-04-11");
    const draft = map([
      kw("universe"), vector([
        vaultInstrument(vaultA, "Vault A"),
        vaultInstrument(vaultB, "Vault B")
      ]),
      kw("objective"), map([kw("kind"), kw("minimum-variance")]),
      kw("return-model"), map([kw("kind"), kw("historical-mean")]),
      kw("risk-model"), map([kw("kind"), kw("diagonal-shrink")]),
      kw("constraints"), map([kw("long-only?"), true])
    ]);
    const historyData = map([
      kw("vault-details-by-address"),
      map([
        vaultA,
        map([
          kw("portfolio"),
          map([kw("month"), summaryFromPoints([[a0, 100, 0], [a1, 101, 1]])])
        ]),
        vaultB,
        map([
          kw("portfolio"),
          map([kw("month"), summaryFromPoints([[b0, 100, 0], [b1, 101, 1]])])
        ])
      ])
    ]);
    const store = globalThis.hyperopen.system.store;
    let state = c.deref(store);
    state = c.assoc_in(state, path("portfolio", "optimizer", "draft"), draft);
    state = c.assoc_in(state, path("portfolio", "optimizer", "history-data"), historyData);
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "runtime"),
      map([kw("as-of-ms"), b1 + dayMs, kw("stale-after-ms"), 2 * dayMs])
    );
    c.reset_BANG_(store, state);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function putOptimizerRecord(page, key, payload) {
  await page.evaluate(async ({ key, payload }) => {
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open("hyperopen-persistence", 7);
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

async function seedLegacyV6PersistenceDb(page) {
  const legacyStoreNames = [
    "asset-selector-markets-cache",
    "funding-history-cache",
    "chart-visible-range-cache",
    "vault-index-cache",
    "leaderboard-preferences",
    "leaderboard-cache",
    "agent-locked-session"
  ];

  await page.route("**/__legacy-v6-indexeddb-seed", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "text/html",
      body: "<!doctype html><meta charset=\"utf-8\"><title>IDB seed</title>"
    });
  });
  await page.goto("/__legacy-v6-indexeddb-seed");
  await page.evaluate(async (storeNames) => {
    await new Promise((resolve, reject) => {
      const request = indexedDB.deleteDatabase("hyperopen-persistence");
      request.onsuccess = () => resolve(true);
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error("Legacy IndexedDB reset blocked"));
    });

    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open("hyperopen-persistence", 6);
      request.onupgradeneeded = (event) => {
        const database = event.target.result;
        for (const storeName of storeNames) {
          if (!database.objectStoreNames.contains(storeName)) {
            database.createObjectStore(storeName);
          }
        }
      };
      request.onsuccess = (event) => resolve(event.target.result);
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error("Legacy IndexedDB open blocked"));
    });
    db.close();
  }, legacyStoreNames);
  await page.unroute("**/__legacy-v6-indexeddb-seed");
}

async function readOptimizerRecord(page, key) {
  return await page.evaluate(async (recordKey) => {
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open("hyperopen-persistence", 7);
      request.onsuccess = (event) => resolve(event.target.result);
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error("IndexedDB open blocked"));
    });

    const record = await new Promise((resolve, reject) => {
      const transaction = db.transaction(["portfolio-optimizer"], "readonly");
      const store = transaction.objectStore("portfolio-optimizer");
      const request = store.get(recordKey);
      request.onsuccess = (event) => resolve(event.target.result || null);
      request.onerror = () => reject(request.error);
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error);
    });
    db.close();
    return record;
  }, key);
}

async function readOptimizerKeys(page) {
  return await page.evaluate(async () => {
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open("hyperopen-persistence", 7);
      request.onsuccess = (event) => resolve(event.target.result);
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error("IndexedDB open blocked"));
    });

    const keys = await new Promise((resolve, reject) => {
      const transaction = db.transaction(["portfolio-optimizer"], "readonly");
      const store = transaction.objectStore("portfolio-optimizer");
      const request = store.getAllKeys();
      request.onsuccess = (event) => resolve(event.target.result || []);
      request.onerror = () => reject(request.error);
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error);
    });
    db.close();
    return keys;
  });
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

async function seedOptimizerDraftSaveState(page, placeholderId = "draft-current") {
  await page.evaluate(({ address, placeholderId }) => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
    const vector = (items) => c.PersistentVector.fromArray(items, true);
    const store = globalThis.hyperopen.system.store;
    const state = c.deref(store);
    const draft = c.get_in(state, path("portfolio", "optimizer", "draft")) || map([]);
    const metadata = c.get(draft, kw("metadata")) || map([]);
    const lastSuccessfulRun = c.get_in(
      state,
      path("portfolio", "optimizer", "last-successful-run")
    );
    const requestSignature =
      c.get(lastSuccessfulRun, kw("request-signature")) ||
      map([kw("scenario-id"), placeholderId]);
    const draftScenario = c.assoc(
      draft,
      kw("id"), placeholderId,
      kw("name"), "Draft Scenario",
      kw("status"), kw("draft"),
      kw("metadata"),
      c.assoc(metadata, kw("dirty?"), false)
    );
    const activeScenario = map([
      kw("loaded-id"), placeholderId,
      kw("status"), kw("computed"),
      kw("read-only?"), false
    ]);
    const runState = map([
      kw("status"), kw("succeeded"),
      kw("run-id"), "optimizer-draft-save-playwright",
      kw("scenario-id"), placeholderId,
      kw("request-signature"), requestSignature,
      kw("started-at-ms"), 1777046000000,
      kw("completed-at-ms"), 1777046000000,
      kw("error"), null
    ]);
    const emptyScenarioIndex = map([
      kw("ordered-ids"), vector([]),
      kw("by-id"), map([])
    ]);
    let nextState = state;
    nextState = c.assoc_in(nextState, path("wallet", "address"), address);
    nextState = c.assoc_in(
      nextState,
      path("portfolio", "optimizer", "draft"),
      draftScenario
    );
    nextState = c.assoc_in(
      nextState,
      path("portfolio", "optimizer", "active-scenario"),
      activeScenario
    );
    nextState = c.assoc_in(
      nextState,
      path("portfolio", "optimizer", "run-state"),
      runState
    );
    nextState = c.assoc_in(
      nextState,
      path("portfolio", "optimizer", "scenario-index"),
      emptyScenarioIndex
    );
    c.reset_BANG_(store, nextState);
  }, {
    address: "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    placeholderId
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function readOptimizerSavedScenarioId(page) {
  return await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const saveState = c.get_in(
      c.deref(globalThis.hyperopen.system.store),
      path("portfolio", "optimizer", "scenario-save-state")
    );
    return c.get(saveState, kw("scenario-id"));
  });
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
    // The v4 execution surface is a tab driven by the staged plan + run-state, so seed
    // the staged plan and switch the results tab to Execution (the live halted flow keeps
    // the plan; re-opening via the CTA would re-stage and clear the failed history).
    const planRow = map([
      kw("row-id"), "perp:BTC",
      kw("instrument-id"), "perp:BTC",
      kw("instrument-type"), kw("perp"),
      kw("status"), kw("ready"),
      kw("side"), kw("buy"),
      kw("quantity"), 0.25,
      kw("order-type"), kw("market"),
      kw("delta-notional-usd"), 500,
      kw("cost"), map([kw("source"), kw("snapshot"), kw("slippage-bps"), 5])
    ]);
    const plan = map([
      kw("status"), kw("ready"),
      kw("execution-disabled?"), false,
      kw("summary"), map([
        kw("ready-count"), 1,
        kw("blocked-count"), 0,
        kw("skipped-count"), 0,
        kw("gross-ready-notional-usd"), 500,
        kw("estimated-fees-usd"), 5,
        kw("estimated-slippage-usd"), 3,
        kw("margin"), map([kw("after-utilization"), 0.42, kw("warning"), kw("none")])
      ]),
      kw("rows"), vector([planRow])
    ]);
    const modal = map([
      kw("open?"), true,
      kw("phase"), kw("staged"),
      kw("submitting?"), false,
      kw("error"), "Execution halted before all rows submitted.",
      kw("plan"), plan
    ]);
    const store = globalThis.hyperopen.system.store;
    let next = c.deref(store);
    next = c.assoc_in(next, path("portfolio", "optimizer", "execution"), execution);
    next = c.assoc_in(next, path("portfolio", "optimizer", "execution-modal"), modal);
    next = c.assoc_in(next, path("portfolio-ui", "optimizer", "results-tab"), kw("execution"));
    c.reset_BANG_(store, next);
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

test("portfolio optimizer route lands on the setup workspace with a scenario library @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");

  // The bare path IS the workspace — the scenario-index board was removed.
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-index']")).toHaveCount(0);

  // The header hosts the scenario library: menu trigger + always-available Save.
  await expect(page.locator("[data-role='portfolio-optimizer-header-save-scenario']")).toBeEnabled();
  await page.locator("[data-role='portfolio-optimizer-scenario-menu-trigger']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-menu-new']"))
    .toContainText("New scenario");
});

test("portfolio optimizer scenario menu recovers saved scenarios when the address index is missing @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await putOptimizerRecord(
    page,
    `scenario::${OPTIMIZER_RELOAD_SCENARIO_ID}`,
    OPTIMIZER_RELOAD_SCENARIO_EDN
  );
  await seedPortfolioWalletAddress(page, "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  await waitForIdle(page, { quietMs: 300, timeoutMs: 8_000, pollMs: 50 });

  // Opening the menu reloads the index; the loader falls back to a full-store
  // scan when the per-address index record is missing.
  await page.locator("[data-role='portfolio-optimizer-scenario-menu-trigger']").click();
  const savedRow = page.locator(
    `[data-role='portfolio-optimizer-scenario-row-${OPTIMIZER_RELOAD_SCENARIO_ID}']`
  );
  await expect(savedRow).toContainText("QA Tracking Reload");
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-menu-empty']"))
    .toHaveCount(0);
});

test("portfolio optimizer scenario menu recovers saved scenarios when the address index is incomplete @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await putOptimizerRecord(
    page,
    `scenario::${OPTIMIZER_RELOAD_SCENARIO_ID}`,
    OPTIMIZER_RELOAD_SCENARIO_EDN
  );
  await putOptimizerRecord(
    page,
    "scenario-index::0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    `{:ordered-ids ["${OPTIMIZER_RELOAD_SCENARIO_ID}"] :by-id {}}`
  );
  await seedPortfolioWalletAddress(page, "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  await waitForIdle(page, { quietMs: 300, timeoutMs: 8_000, pollMs: 50 });

  await page.locator("[data-role='portfolio-optimizer-scenario-menu-trigger']").click();
  const savedRow = page.locator(
    `[data-role='portfolio-optimizer-scenario-row-${OPTIMIZER_RELOAD_SCENARIO_ID}']`
  );
  await expect(savedRow).toContainText("QA Tracking Reload");
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-menu-empty']"))
    .toHaveCount(0);
});

test("portfolio optimizer setup puts policy controls in the center pane @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");

  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-objective-panel']"))
    .toContainText("Optimization goal");
  await expect(page.locator("[data-role='portfolio-optimizer-return-risk-panel']"))
    .toContainText(/Return \/ Risk Model/);
  // LEFT rail is universe-only now; the editable policy controls moved to the wide center pane.
  await expect.poll(async () => page
    .locator("[data-role='portfolio-optimizer-setup-control-rail']")
    .evaluate((rail) => Array.from(rail.children).map((child) => child.getAttribute("data-role"))))
    .toEqual(["portfolio-optimizer-universe-panel"]);
  await expect.poll(async () => page
    .locator("[data-role='portfolio-optimizer-setup-policy-pane']")
    .evaluate((pane) => Array.from(pane.children).map((child) => child.getAttribute("data-role"))))
    .toEqual([
      "portfolio-optimizer-objective-panel",
      "portfolio-optimizer-constraints-panel",
      "portfolio-optimizer-proxy-workflow-slot",
      "portfolio-optimizer-return-risk-panel",
      "portfolio-optimizer-advanced-overrides-shell",
      "portfolio-optimizer-why-safe-note",
      "portfolio-optimizer-model-assumptions-stack",
      // The Run bar is the LAST direct child of the pane so `position: sticky`
      // can pin it to the viewport bottom (see setup_sections/policy-pane).
      "portfolio-optimizer-setup-bottom-actions"
    ]);
});

test("portfolio optimizer setup turnover cap switch disables and restores cap @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");

  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
  const constraintsPanel = page.locator("[data-role='portfolio-optimizer-constraints-panel']");
  const turnoverToggle = page.locator(
    "[data-role='portfolio-optimizer-constraint-max-turnover-toggle']"
  );
  const turnoverInput = page.locator(
    "[data-role='portfolio-optimizer-constraint-max-turnover-input']"
  );

  await expect.poll(async () => constraintsPanel.evaluate((element) => element.open)).toBe(true);
  await expect(constraintsPanel.locator("> summary")).toContainText("Portfolio exposure");
  // The turnover controls live behind the Rebalancing card's Edit disclosure
  // (2026-07-10 simplified default view).
  const rebalancingCard = page.locator("[data-role='portfolio-optimizer-rebalancing-card']");
  if (!(await rebalancingCard.evaluate((element) => element.open))) {
    await rebalancingCard.locator("> summary").click();
  }
  await expect(turnoverToggle).toHaveAttribute("role", "switch");
  await expect(turnoverToggle).toHaveAttribute("aria-checked", "true");
  await expect(turnoverInput).toBeEnabled();
  await expect(turnoverInput).toHaveValue(/1(?:\.0)?/);

  await turnoverToggle.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(turnoverToggle).toHaveAttribute("aria-checked", "false");
  await expect(turnoverInput).toBeDisabled();
  await expect(turnoverInput).toHaveValue("");
  await expect(constraintsPanel).toContainText("no cap");

  await turnoverToggle.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(turnoverToggle).toHaveAttribute("aria-checked", "true");
  await expect(turnoverInput).toBeEnabled();
  await expect(turnoverInput).toHaveValue(/1(?:\.0)?/);
});

test("portfolio optimizer From holdings seeds current exposure constraints @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");
  await seedPortfolioWalletAddress(page, "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  await waitForIdle(page, { quietMs: 800, timeoutMs: 8_000, pollMs: 50 });
  await seedPortfolioWebdata2(page, {
    clearinghouseState: {
      marginSummary: { accountValue: "1000" },
      assetPositions: [
        {
          position: {
            coin: "BTC",
            szi: "1",
            positionValue: "1500",
            leverage: { type: "cross", value: "5" }
          }
        },
        {
          position: {
            coin: "ETH",
            szi: "-1",
            markPx: "500",
            leverage: { type: "cross", value: "5" }
          }
        }
      ]
    }
  });

  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']"))
    .toBeVisible();
  await page.locator("[data-role='portfolio-optimizer-universe-use-current']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(page.locator("[data-role='portfolio-optimizer-universe-selected-row-perp:BTC']"))
    .toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-universe-selected-row-perp:ETH']"))
    .toBeVisible();

  const constraintsPanel = page.locator("[data-role='portfolio-optimizer-constraints-panel']");
  await expect.poll(async () => constraintsPanel.evaluate((element) => element.open)).toBe(true);
  await expect(constraintsPanel.locator("> summary")).toContainText("Portfolio exposure");
  await expect(page.locator("[data-role='portfolio-optimizer-constraint-gross-max-input']"))
    .toHaveValue("2");
  await expect(page.locator("[data-role='portfolio-optimizer-constraint-net-min-input']"))
    .toHaveValue("0.95");
  await expect(page.locator("[data-role='portfolio-optimizer-constraint-net-max-input']"))
    .toHaveValue("1.05");
  await expect(page.locator("[data-role='portfolio-optimizer-constraint-max-asset-weight-input']"))
    .toHaveValue("1.5");
  await expect(page.locator("[data-role='portfolio-optimizer-constraint-max-turnover-toggle']"))
    .toHaveAttribute("aria-checked", "false");
  await expect(page.locator("[data-role='portfolio-optimizer-constraint-max-turnover-input']"))
    .toBeDisabled();
  await expect(constraintsPanel).toContainText("no cap");
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
  await expect.poll(async () => constraintsPanel.evaluate((element) => element.open)).toBe(true);
  await expect.poll(async () => advancedPanel.evaluate((element) => element.open)).toBe(false);
  await expect(returnModelPanel).toBeHidden();
  // Since the 2026-07-10 quieter-by-default pass the per-asset cap rests as a
  // read-only value on the Risk guards card; the editable input appears only
  // after the card's Edit disclosure opens.
  await expect(maxAssetWeight).toBeHidden();
  await expect(page.locator("[data-role='portfolio-optimizer-risk-guards-cap-value']"))
    .toContainText("50%");

  const summaryPane = page.locator("[data-role='portfolio-optimizer-setup-policy-pane']");
  const assumptionsPanel = page.locator("[data-role='portfolio-optimizer-model-assumptions-panel']");
  const bottomActions = page.locator("[data-role='portfolio-optimizer-setup-bottom-actions']");
  const actionMeta = page.locator("[data-role='portfolio-optimizer-setup-bottom-actions-status-meta']");
  const actionDetail = page.locator("[data-role='portfolio-optimizer-setup-bottom-actions-status-detail']");
  const footer = page.locator("[data-parity-id='footer']");
  await expect(summaryPane.locator("[data-role='portfolio-optimizer-setup-bottom-actions']"))
    .toBeVisible();
  await expect(assumptionsPanel).toBeVisible();
  // The Run bottom bar lives in the CENTER policy pane as its LAST direct child (after the
  // model-assumptions stack), where `position: sticky; bottom: 0` pins it to the viewport while
  // the tall pane scrolls — expanding Constraints can no longer push Run below the fold. The
  // placement is also pinned by the setup-run-action unit test.
  await expect.poll(async () => page
    .locator("[data-role='portfolio-optimizer-model-assumptions-stack']")
    .evaluate((stack) => Array.from(stack.children).map((child) => child.getAttribute("data-role"))))
    .toEqual(["portfolio-optimizer-model-assumptions-panel"]);
  await expect.poll(async () => summaryPane
    .evaluate((pane) => {
      const children = Array.from(pane.children).map((child) => child.getAttribute("data-role"));
      return children[children.length - 1];
    }))
    .toBe("portfolio-optimizer-setup-bottom-actions");
  await expect.poll(async () => bottomActions
    .evaluate((element) => getComputedStyle(element).position))
    .toBe("sticky");
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
    .toContainText("Load my holdings");
  await expect(page.locator("[data-role='portfolio-optimizer-objective-panel']"))
    .toContainText("Optimization goal");
  await expect(page.locator("[data-role='portfolio-optimizer-objective-panel']"))
    .toContainText("Minimum risk");
  await expect(page.locator("[data-role='portfolio-optimizer-return-model-panel']"))
    .toContainText("Historical Mean");
  await expect(page.locator("[data-role='portfolio-optimizer-return-model-panel']"))
    .toContainText("Black-Litterman");
  await expect(page.locator("[data-role='portfolio-optimizer-risk-model-panel']"))
    .toContainText("Diagonal Shrink");
  await expect(page.locator("[data-role='portfolio-optimizer-constraints-panel']"))
    .toContainText("Portfolio exposure");
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
    .toContainText("Row-level eligibility and weight caps");

  await modelPanel.locator("summary").click();
  await expect.poll(async () => modelPanel.evaluate((element) => element.open)).toBe(true);
  await expect(returnModelPanel).toBeVisible();
  await expect(riskModelPanel).toBeVisible();

  await expect.poll(async () => constraintsPanel.evaluate((element) => element.open)).toBe(true);
  await page.locator("[data-role='portfolio-optimizer-risk-guards-card'] summary").click();
  await expect(maxAssetWeight).toBeVisible();

  const maxSharpe = page.locator("[data-role='portfolio-optimizer-objective-max-sharpe']");
  const targetVolatilityObjective = page.locator("[data-role='portfolio-optimizer-objective-target-volatility']");
  const targetReturnObjective = page.locator("[data-role='portfolio-optimizer-objective-target-return']");
  const blackLitterman = page.locator("[data-role='portfolio-optimizer-return-model-black-litterman']");
  const sampleCovariance = page.locator("[data-role='portfolio-optimizer-risk-model-sample-covariance']");
  const targetReturn = page.locator(
    "[data-role='portfolio-optimizer-objective-target-return-input']"
  );
  const targetVolatility = page.locator(
    "[data-role='portfolio-optimizer-objective-target-volatility-input']"
  );
  const inputTextFits = async (input) =>
    input.evaluate((element) => element.scrollWidth <= element.clientWidth + 1);

  await expect(maxSharpe).toHaveAttribute("aria-pressed", "false");
  // Selecting the Maximum Sharpe goal now carries its return-model pairing: it
  // activates the views-aware Black-Litterman estimator in one move, the way the
  // retired top-of-page preset did. (Before consolidation the objective card set
  // only the objective and left the estimator on Historical mean.)
  await expect(blackLitterman).toHaveAttribute("aria-pressed", "false");
  await maxSharpe.click();
  await expect(maxSharpe).toHaveAttribute("aria-pressed", "true");
  await expect(blackLitterman).toHaveAttribute("aria-pressed", "true");
  await expect(targetReturn).toHaveCount(0);
  await expect(targetVolatility).toHaveCount(0);

  await expect(sampleCovariance).toHaveAttribute("aria-pressed", "false");
  await sampleCovariance.click();
  await expect(sampleCovariance).toHaveAttribute("aria-pressed", "true");

  const longOnly = page.locator("[data-role='portfolio-optimizer-constraint-long-only-input']");
  await expect(longOnly).toHaveAttribute("aria-checked", "false");
  await longOnly.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(longOnly).toHaveAttribute("aria-checked", "true");

  await expect(maxAssetWeight).toHaveValue("0.5");
  await maxAssetWeight.fill("0.3");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(maxAssetWeight).toHaveValue("0.3");

  // The advanced targets live behind the collapsed "More goals" drawer since
  // the goal consolidation; open it before selecting one.
  await page.locator("[data-role='portfolio-optimizer-more-goals'] summary").click();

  // Equal Risk is the parameterless third PRIMARY goal card (between Minimum
  // risk and Maximum Sharpe, above the More-goals drawer): selecting it flips
  // the card, sets only the objective (no return-model change, no parameter
  // block), and its covariance-only framing is stated on the card itself.
  const equalRiskObjective = page.locator("[data-role='portfolio-optimizer-objective-equal-risk']");
  await expect(equalRiskObjective)
    .toContainText("Balance each position's share of portfolio risk");
  await expect(equalRiskObjective).toContainText("no return forecast needed");
  await expect(equalRiskObjective).toHaveAttribute("aria-pressed", "false");
  await equalRiskObjective.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(equalRiskObjective).toHaveAttribute("aria-pressed", "true");
  await expect(targetReturn).toHaveCount(0);
  await expect(targetVolatility).toHaveCount(0);

  await targetVolatilityObjective.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(targetVolatility).toHaveValue("20");
  await expect.poll(() => inputTextFits(targetVolatility)).toBe(true);
  await expect(targetReturn).toHaveCount(0);

  await targetReturnObjective.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(targetReturn).toHaveValue("15");
  await expect.poll(() => inputTextFits(targetReturn)).toBe(true);
  await expect(targetVolatility).toHaveCount(0);
  await targetReturn.fill("18");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(targetReturn).toHaveValue("18");
});

test("portfolio optimizer universe search uses one integrated shell @regression", async ({ page }) => {
  // Four full route visits, each followed by forced-layout reads (getComputedStyle
  // + getBoundingClientRect on five elements). The universe search results list
  // now renders facet chips, grouped rows and a footer while data-searching is
  // true, so those synchronous layout reads walk a larger DOM and this test sits
  // right at the old 45s budget. The panel itself projects and renders in ~1.4ms
  // (measured in-browser), so this is test structure, not a UI regression.
  test.setTimeout(90_000);

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
  const historyBundleRequests = [];
  await stubOptimizerHistoryBundle(page, historyBundleRequests);

  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
  await seedOptimizerAssetSelectorMarkets(page);
  await seedOptimizerBtcOnlyHistory(page);

  const searchInput = page.locator("[data-role='portfolio-optimizer-universe-search-input']");
  const ethCandidate = page.locator("[data-role='portfolio-optimizer-universe-candidate-row-perp:ETH']");
  const ethAdd = page.locator("[data-role='portfolio-optimizer-universe-add-perp:ETH']");
  const ethSelected = page.locator("[data-role='portfolio-optimizer-universe-selected-row-perp:ETH']");
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
  await expect(ethSelected).toHaveCount(1);
  await expect(ethRemove).toBeVisible();
  await expect(searchInput).toHaveValue("");
  await expect(page.locator("[data-role='portfolio-optimizer-universe-search-results']"))
    .toHaveCount(0);
  await expect(ethCandidate).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-universe-panel']"))
    .toContainText("History starts loading after assets are included.");
  await expect(page.locator("[data-role='portfolio-optimizer-run-draft']")).toBeEnabled();
  await expect.poll(
    () => historyBundleRequests.some((payload) =>
      payload.instruments?.some((instrument) => instrument.client_instrument_id === "perp:ETH")
    ),
    { timeout: 10_000 }
  ).toBe(true);
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .toContainText("Optimizer history is loaded for the selected assets.", { timeout: 10_000 });

  await ethRemove.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(ethRemove).toHaveCount(0);
  await expect(ethSelected).toHaveCount(0);
  await searchInput.fill("eth");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(ethCandidate).toBeVisible();
  await expect(ethAdd).toBeVisible();
  await ethAdd.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(ethSelected).toHaveCount(1);
});

test("portfolio optimizer vault search hydrates cached rows before live index completes @regression", async ({ page }) => {
  let releaseLiveVaultIndex;
  let liveReleased = false;
  const liveVaultIndexReleased = new Promise((resolve) => {
    releaseLiveVaultIndex = () => {
      if (!liveReleased) {
        liveReleased = true;
        resolve();
      }
    };
  });
  let vaultIndexRequests = 0;

  await page.route("https://stats-data.hyperliquid.xyz/Mainnet/vaults", async (route) => {
    vaultIndexRequests += 1;
    await liveVaultIndexReleased;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([OPTIMIZER_DEFAULT_VAULT_SUMMARY].map((summary) => ({ summary })))
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
            body: JSON.stringify([OPTIMIZER_DEFAULT_VAULT_SUMMARY])
          });
          return;
        }
      } catch {
        // Let non-JSON info requests follow the normal route.
      }
    }
    await route.continue();
  });

  try {
    await page.goto("/trade");
    await waitForDebugBridge(page);
    await debugCall(page, "qaReset");
    await seedOptimizerAssetSelectorFullPhase(page);
    await putOptimizerVaultIndexCacheRecord(page, [OPTIMIZER_DEFAULT_VAULT_SUMMARY]);
    await debugCall(page, "dispatch", [
      ":actions/navigate",
      "/portfolio/optimize/new",
      { "replace?": true }
    ]);

    await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']"))
      .toBeVisible();

    const vaultKey = `vault:${OPTIMIZER_VAULT_ADDRESS}`;
    const searchInput = page.locator("[data-role='portfolio-optimizer-universe-search-input']");
    const vaultRow = page.locator(
      `[data-role='portfolio-optimizer-universe-candidate-row-${vaultKey}']`
    );

    await searchInput.fill("alpha");
    await expect(vaultRow).toBeVisible({ timeout: 4_000 });
    await expect(vaultRow).toContainText("Alpha Yield");
    await expect.poll(() => vaultIndexRequests, { timeout: 4_000 }).toBe(1);
    expect(liveReleased).toBe(false);
  } finally {
    releaseLiveVaultIndex?.();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 })
      .catch(() => {});
  }
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
  // The per-row type tag was replaced by a sticky per-type group header: with a
  // full, grouped result list the type is stated once per section instead of
  // being repeated on every row of a narrow rail.
  await expect(
    page.locator("[data-role='portfolio-optimizer-universe-candidate-group-header-vault']")
  ).toContainText("Vaults");
  await vaultAdd.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(vaultSelected).toBeVisible();
  await expect(vaultSelected).toContainText("Alpha Yield");
  // PRE-EXISTING RED, fixed here in passing: the selected table renders its TYPE
  // column by exception, so a universe holding a single market type (this one
  // holds only the vault) renders no per-row type tag at all. This assertion has
  // been failing on main since that by-exception column shipped — Playwright is
  // workflow_dispatch-only, so nothing caught it. Assert the 4-track variant the
  // by-exception path actually produces.
  await expect(vaultSelected).toHaveClass(/optimizer-universe-cols-4/);
  // The verbose center summary panel is gone; the compact right-column summary card shows the
  // asset COUNT and must never leak the raw vault address/id.
  await expect(page.locator("[data-role='portfolio-optimizer-setup-summary-card']"))
    .toContainText("assets");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-summary-card']"))
    .not.toContainText(vaultKey);
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

test("portfolio optimizer selected vault rows show shared gap for loaded misaligned history @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
  await seedOptimizerMisalignedVaultHistory(page);

  const vaultAId = "vault:0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  const vaultARow = page.locator(
    `[data-role='portfolio-optimizer-universe-selected-row-${vaultAId}']`
  );

  await expect(vaultARow).toBeVisible();
  // Per-row data-quality chips were removed (2026-07-02 setup readability pass):
  // the grouped Readiness panel carries the visible copy, and the row keeps only
  // the data-history-status hook.
  await expect(vaultARow).not.toContainText("shared gap");
  await expect(vaultARow).toHaveAttribute("data-history-status", "shared-gap");
});

test("portfolio optimizer manual universe vault search sorts by TVL @regression", async ({ page }) => {
  await stubOptimizerVaultMetadata(page, OPTIMIZER_HLP_VAULT_SUMMARIES);
  await visitRoute(page, "/portfolio/optimize/new");
  await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
  await seedOptimizerHlpVaultRows(page);

  const searchInput = page.locator("[data-role='portfolio-optimizer-universe-search-input']");
  const vaultRows = page.locator(
    "[data-role^='portfolio-optimizer-universe-candidate-row-vault:']"
  );

  await searchInput.fill("hlp");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(vaultRows).toHaveCount(3);
  await expect(vaultRows.nth(0)).toHaveAttribute(
    "data-role",
    `portfolio-optimizer-universe-candidate-row-vault:${OPTIMIZER_LARGE_HLP_VAULT_ADDRESS}`
  );
  await expect(vaultRows.nth(1)).toHaveAttribute(
    "data-role",
    `portfolio-optimizer-universe-candidate-row-vault:${OPTIMIZER_MID_HLP_VAULT_ADDRESS}`
  );
  await expect(vaultRows.nth(2)).toHaveAttribute(
    "data-role",
    `portfolio-optimizer-universe-candidate-row-vault:${OPTIMIZER_EXACT_HLP_VAULT_ADDRESS}`
  );
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

test("portfolio optimizer selection prefetch requests API v2 history before run @regression", async ({ page }) => {
  const historyBundleRequests = [];
  const legacyHistoryRequests = [];
  await stubOptimizerHistoryBundle(page, historyBundleRequests);
  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const request = route.request();
    if (request.method() !== "POST") {
      await route.continue();
      return;
    }

    const payload = request.postDataJSON();
    if (payload?.type === "candleSnapshot") {
      legacyHistoryRequests.push(`${payload.type}:${payload.req?.coin}`);
    }

    if (payload?.type === "fundingHistory") {
      legacyHistoryRequests.push(`${payload.type}:${payload.coin}`);
    }

    await route.continue();
  });

  await visitRoute(page, "/portfolio/optimize/new");
  await seedOptimizerAssetSelectorMarkets(page);
  historyBundleRequests.length = 0;
  legacyHistoryRequests.length = 0;

  const searchInput = page.locator("[data-role='portfolio-optimizer-universe-search-input']");
  await searchInput.fill("btc");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:BTC']").click();
  await expect.poll(
    () => historyBundleRequests.some((payload) =>
      payload.instruments?.some((instrument) => instrument.client_instrument_id === "perp:BTC")
    ),
    { timeout: 10_000 }
  ).toBe(true);
  await expect(page.locator("[data-role='portfolio-optimizer-universe-selected-row-perp:BTC']"))
    .toHaveAttribute("data-history-status", "sufficient", { timeout: 10_000 });

  await searchInput.fill("eth");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-universe-add-perp:ETH']").click();
  await expect.poll(
    () => historyBundleRequests.some((payload) =>
      payload.instruments?.some((instrument) => instrument.client_instrument_id === "perp:ETH")
    ),
    { timeout: 10_000 }
  ).toBe(true);
  await expect(page.locator("[data-role='portfolio-optimizer-universe-selected-row-perp:ETH']"))
    .toHaveAttribute("data-history-status", "sufficient", { timeout: 10_000 });

  const beforeRun = [...historyBundleRequests];
  await expect(page.locator("[data-role='portfolio-optimizer-load-history']")).toHaveCount(0);
  await page.locator("[data-role='portfolio-optimizer-run-draft']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-progress-panel']"))
    .toContainText("Optimization", { timeout: 10_000 });
  await expect(page.locator("[data-role='portfolio-optimizer-readiness-panel']"))
    .toContainText("Optimizer history is loaded for the selected assets.", { timeout: 10_000 });

  expect(legacyHistoryRequests).toEqual([]);
  expect(new Set(
    beforeRun.flatMap((payload) =>
      payload.instruments?.map((instrument) => instrument.client_instrument_id) || []
    )
  )).toEqual(new Set(["perp:BTC", "perp:ETH"]));
  expect(historyBundleRequests).toEqual(beforeRun);
});

test("portfolio optimizer recommendation chart shows minimum variance frontier overlays and honest target weights @regression", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });

  await page.addInitScript(() => {
    globalThis.__HYPEROPEN_OPTIMIZER_HISTORY_API__ = {
      enabled: false,
      baseUrl: "https://price-history.hyperopen.xyz",
      proxyPolicy: "approved-proxy-allowed",
      includeAlignedReturns: true,
      fallbackToLegacy: false
    };
  });

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
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    c.swap_BANG_(
      globalThis.hyperopen.system.store,
      c.assoc_in,
      c.PersistentVector.fromArray(
        [
          kw("portfolio"),
          kw("optimizer"),
          kw("draft"),
          kw("objective"),
          kw("frontier-points")
        ],
        true
      ),
      28
    );
  });

  const searchInput = page.locator("[data-role='portfolio-optimizer-universe-search-input']");
  for (const coin of ["btc", "eth", "sol", "hype"]) {
    await searchInput.fill(coin);
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
    await page.locator(`[data-role='portfolio-optimizer-universe-add-perp:${coin.toUpperCase()}']`).click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
    await expect(page.locator(`[data-role='portfolio-optimizer-universe-selected-row-perp:${coin.toUpperCase()}']`))
      .toHaveAttribute("data-history-status", "sufficient", { timeout: 10_000 });
  }

  await expect(page.locator("[data-role='portfolio-optimizer-load-history']")).toHaveCount(0);
  await page.locator("[data-role='portfolio-optimizer-run-draft']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-progress-panel']"))
    .toContainText("Optimization", { timeout: 10_000 });
  await expect(page).toHaveURL(/\/portfolio\/optimize\/draft/, {
    timeout: 10_000
  });
  await expect(page.locator("[data-role='portfolio-optimizer-view-weights']"))
    .toHaveCount(0);
  await appendOptimizerBtcHistoryPoint(page);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
    .toContainText("Allocation");
  await expect(page.locator("[data-role='portfolio-optimizer-trust-caution-panel']"))
    .toContainText("History Used");
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-panel']"))
    .toContainText("Efficient Frontier");
  const resultsGrid = page.locator("[data-role='portfolio-optimizer-results-grid']");
  const centerPanel = page.locator("[data-role='portfolio-optimizer-results-center-panel']");
  const rightPanel = page.locator("[data-role='portfolio-optimizer-results-right-panel']");
  const readingLabel = page
    .locator("[data-role='portfolio-optimizer-frontier-panel']")
    .getByText("Reading this", { exact: true });
  const [resultsGridBox, centerPanelBox, readingLabelBox] = await Promise.all([
    resultsGrid.boundingBox(),
    centerPanel.boundingBox(),
    readingLabel.boundingBox()
  ]);
  expect(resultsGridBox).not.toBeNull();
  expect(centerPanelBox).not.toBeNull();
  expect(readingLabelBox).not.toBeNull();
  expect(centerPanelBox.width / resultsGridBox.width).toBeGreaterThanOrEqual(0.44);
  expect(readingLabelBox.height).toBeLessThanOrEqual(20);
  await page.setViewportSize({ width: 1536, height: 900 });
  const [wideCenterPanelBox, wideRightPanelBox] = await Promise.all([
    centerPanel.boundingBox(),
    rightPanel.boundingBox()
  ]);
  expect(wideCenterPanelBox).not.toBeNull();
  expect(wideRightPanelBox).not.toBeNull();
  expect(wideRightPanelBox.x).toBeGreaterThan(wideCenterPanelBox.x);
  expect(Math.abs(wideRightPanelBox.y - wideCenterPanelBox.y)).toBeLessThanOrEqual(4);
  await seedOptimizerCurrentResultPoint(page);
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-current-marker']"))
    .toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-legend']"))
    .toHaveCount(0);
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
  const standaloneFrontierPath = await frontierPath.getAttribute("d");
  await constrainFrontierCheckbox.check();
  await expect(constrainFrontierCheckbox).toBeChecked();
  // The constrained and standalone frontier paths are identical here BY DESIGN,
  // not by coincidence: this scenario has no held-position locks, and the
  // optimizer's reference ("unconstrained") frontier deliberately aliases the
  // constrained frontier whenever there are no locks (see
  // src/hyperopen/portfolio/optimizer/application/display_frontier.cljs —
  // reference-frontier-constraints, :aliases {:constrained :unconstrained}). So
  // we do NOT assert the constrained path differs from the standalone one — a
  // genuine difference only arises for runs WITH held-position locks. The
  // boolean -> [:frontiers key] -> rendered-path selection is covered directly
  // and deterministically in frontier_chart_model_test. Here we assert the
  // toggle keeps a valid multi-segment frontier and round-trips deterministically
  // in both directions, the meaningful view-level guarantee for this fixture.
  await expect(frontierPath).toBeVisible();
  await expect.poll(async () => await frontierPath.getAttribute("d"))
    .toMatch(/\bL\b/);
  const constrainedFrontierPath = await frontierPath.getAttribute("d");
  await constrainFrontierCheckbox.uncheck();
  await expect(constrainFrontierCheckbox).not.toBeChecked();
  await expect(constrainFrontierCheckbox).toHaveCSS("box-shadow", "none");
  await expect.poll(async () => await frontierPath.getAttribute("d"))
    .toBe(standaloneFrontierPath);
  // Re-checking returns to the constrained rendering, proving the toggle is a
  // pure, deterministic function of its state in both directions.
  await constrainFrontierCheckbox.check();
  await expect(constrainFrontierCheckbox).toBeChecked();
  await expect.poll(async () => await frontierPath.getAttribute("d"))
    .toBe(constrainedFrontierPath);
  await constrainFrontierCheckbox.uncheck();
  await expect(constrainFrontierCheckbox).not.toBeChecked();
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
  await expect(targetRing).toHaveAttribute("stroke-width", "1.15");
  await expect(targetRing).toHaveAttribute("opacity", "0.74");
  await expect(targetLabel).toBeVisible();
  await expect(targetLeaderLine).toHaveAttribute("stroke-dasharray", "3 3");
  await targetMarker.hover();
  await expect(targetCallout).toHaveCSS("opacity", "1");
  expect(await targetCallout.evaluate((callout) => {
    for (let node = callout.parentElement; node; node = node.parentElement) {
      if (node.getAttribute("data-role") === "portfolio-optimizer-frontier-callout-layer") {
        return true;
      }
    }
    return false;
  })).toBe(true);
  await expect(targetCallout.locator("[data-role='portfolio-optimizer-frontier-callout-card']"))
    .toHaveAttribute("stroke", "var(--optimizer-border-strong)");
  await expect(targetCallout).toContainText("TARGET");
  await expect(targetCallout).toContainText("PORTFOLIO");
  await expect(targetCallout).toContainText("μ · return");
  await expect(targetCallout).toContainText("σ · vol");
  await expect(targetCallout).toContainText("Sharpe");
  await expect(targetCallout).toContainText("IMPLIED ALLOCATION");
  await expect(targetCallout).not.toContainText("Gross Exposure");

  const currentMarker = page.locator("[data-role='portfolio-optimizer-frontier-current-marker-hitbox']");
  const currentCallout = page.locator("[data-role='portfolio-optimizer-frontier-callout-current']");
  await expect(currentMarker).toBeVisible();
  await currentMarker.hover();
  await expect(currentCallout).toHaveCSS("opacity", "1");
  await expect(currentCallout).toContainText("CURRENT");
  await expect(currentCallout).toContainText("PORTFOLIO");
  await expect(currentCallout).toContainText("μ · return");
  await expect(currentCallout).toContainText("σ · vol");
  await expect(currentCallout).toContainText("Gross Exposure");
  await expect(currentCallout).toContainText("Net Exposure");
  await expect(currentCallout).toContainText("IMPLIED ALLOCATION");
  await expect(currentCallout).toContainText("HYPE");
  await expect(currentCallout).toContainText("30.0%");

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
  expect(await standaloneCallout.evaluate((callout) => {
    for (let node = callout.parentElement; node; node = node.parentElement) {
      if (node.getAttribute("data-role") === "portfolio-optimizer-frontier-callout-layer") {
        return true;
      }
    }
    return false;
  })).toBe(true);
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

test("portfolio optimizer saves draft scenarios under durable ids and reloads them from the index @regression", async ({ page }) => {
  await seedLegacyV6PersistenceDb(page);
  await visitRoute(page, "/portfolio/optimize");
  await seedPersistedOptimizerTrackingScenario(page);
  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}`);

  const retainedDraftId = "draft-current";
  await dispatch(page, [
    ":actions/navigate",
    `/portfolio/optimize/${retainedDraftId}`,
    { "replace?": true }
  ]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await seedOptimizerDraftSaveState(page, retainedDraftId);

  const draftDetail = page.locator("[data-role='portfolio-optimizer-scenario-detail-surface']");
  const draftSave = page.locator("[data-role='portfolio-optimizer-scenario-save']");
  await expect(draftDetail).toHaveAttribute("data-scenario-id", retainedDraftId);
  await expect(draftSave).toBeEnabled();

  await draftSave.click();
  const saveModal = page.locator("[data-role='portfolio-optimizer-scenario-save-modal']");
  const saveName = page.locator("[data-role='portfolio-optimizer-scenario-save-name']");
  const saveConfirm = page.locator("[data-role='portfolio-optimizer-scenario-save-confirm']");
  await expect(saveModal).toBeVisible();
  await expect(saveName).toHaveValue("Draft Scenario");
  await saveName.fill("May Rotation");
  await saveConfirm.click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });

  const savedScenarioId = await readOptimizerSavedScenarioId(page);
  expect(savedScenarioId).toMatch(/^scn_[0-9]+$/);
  expect(savedScenarioId).not.toBe("draft");
  await expect(page).toHaveURL(new RegExp(`/portfolio/optimize/${savedScenarioId}`));
  await expect(saveModal).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-loading-state']"))
    .toHaveCount(0);

  const optimizerKeys = await readOptimizerKeys(page);
  expect(optimizerKeys).toContain(`scenario::${savedScenarioId}`);
  expect(optimizerKeys).not.toContain("scenario::draft");
  expect(optimizerKeys).not.toContain(`scenario::${retainedDraftId}`);

  const indexRecord = await readOptimizerRecord(
    page,
    "scenario-index::0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  );
  expect(indexRecord?.encoding).toBe("edn-v1");
  expect(indexRecord?.payload).toContain(savedScenarioId);
  expect(indexRecord?.payload).toContain("May Rotation");
  expect(indexRecord?.payload).not.toContain('"draft"');
  expect(indexRecord?.payload).not.toContain(`"${retainedDraftId}"`);

  await page.reload();
  await waitForDebugBridge(page);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });
  await seedPortfolioWalletAddress(page, "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  await dispatch(page, [
    ":actions/navigate",
    "/portfolio/optimize",
    { "replace?": true }
  ]);
  await waitForIdle(page, { quietMs: 300, timeoutMs: 8_000, pollMs: 50 });

  // Saved scenarios list inside the workspace-header Scenarios menu now.
  await page.locator("[data-role='portfolio-optimizer-scenario-menu-trigger']").click();
  const savedRow = page.locator(`[data-role='portfolio-optimizer-scenario-row-${savedScenarioId}']`);
  await expect(savedRow).toContainText("May Rotation");

  await savedRow.click();
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-detail-surface']"))
    .toHaveAttribute("data-scenario-id", savedScenarioId);
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-header']"))
    .toContainText("May Rotation");
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-loading-state']"))
    .toHaveCount(0);
});

test("portfolio optimizer persisted scenario hydrates results and tracking after reload @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await seedPersistedOptimizerTrackingScenario(page);

  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}`);

  const scenarioDetail = page.locator("[data-role='portfolio-optimizer-scenario-detail-surface']");
  const results = page.locator("[data-role='portfolio-optimizer-results-surface']");
  const tracking = page.locator("[data-role='portfolio-optimizer-tracking-panel']");

  await expect(scenarioDetail).toHaveAttribute("data-scenario-id", OPTIMIZER_RELOAD_SCENARIO_ID);
  await expect(results).toContainText("Optimization status");
  await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-asset-BTC']"))
    .toContainText("BTC");
  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}`);
  await selectOptimizerScenarioTab(page, "tracking");
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
    .toContainText("Optimization status");
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-rerun']"))
    .toBeDisabled();
});

test("portfolio optimizer execution remains read-only in Spectate Mode @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await seedPersistedOptimizerTrackingScenario(page);
  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}`);
  // The standalone Rebalance preview tab/card was retired — the rebalance stages straight
  // into Execution. Reach Execution directly; the plan is rebuilt on tab entry, so spectate
  // must be enabled first for the read-only message to bake into it.
  await enableOptimizerSpectateMode(page);
  await selectOptimizerScenarioTab(page, "execution");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const tab = page.locator("[data-role='portfolio-optimizer-execution-tab']");
  // Renamed from "Account leverage after" by the gross/venue leverage-lens
  // reconciliation (2026-07-06).
  await expect(tab).toContainText("Gross leverage after");
  await expect(tab).toContainText(
    "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds."
  );
  await expect(page.locator("[data-role='portfolio-optimizer-execution-arm']"))
    .toBeDisabled();
});

test("portfolio optimizer execution surfaces failed attempt recovery details @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize");
  await seedPersistedOptimizerTrackingScenario(page);
  await visitRoute(page, `/portfolio/optimize/${OPTIMIZER_RELOAD_SCENARIO_ID}`);
  await seedPortfolioWalletAddress(page, "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  await seedPortfolioWebdata2(page, {
    clearinghouseState: {
      marginSummary: { accountValue: "10000" },
      assetPositions: []
    }
  });
  // seeds the staged plan + failed run-state + switches to the Execution tab in-place
  await seedOptimizerFailedExecutionAttempt(page);

  const band = page.locator("[data-role='portfolio-optimizer-execution-control-band']");
  await expect(band).toHaveAttribute("data-phase", "halted");

  const latestAttempt = page.locator("[data-role='portfolio-optimizer-execution-latest-attempt']");
  await expect(latestAttempt).toContainText("Latest attempt");
  await expect(latestAttempt).toContainText("failed");
  await expect(latestAttempt).toContainText("Order submit failed: exchange down");
  await expect(page.locator("[data-role='portfolio-optimizer-execution-resume']")).toBeVisible();
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
  )).toBeLessThan(160);
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
  await page.goto(`/portfolio?spectate=${SPECTATE_ADDRESS}`);
  await waitForDebugBridge(page);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });
  await expect(page.locator("[data-parity-id='app-route-module-shell']"))
    .toHaveCount(0, { timeout: 15_000 });

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
});

test("portfolio account activity tab renders sub-tabbed ledger history @regression", async ({ page }) => {
  await page.setViewportSize(PORTFOLIO_LEDGER_REVIEW_VIEWPORTS[0]);
  const observedLedgerRequests = [];
  await stubPortfolioLedgerRows(page, PORTFOLIO_LEDGER_FIXTURE, observedLedgerRequests);
  await visitRoute(page, "/portfolio");
  await seedPortfolioWalletAddress(page, "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  await selectAccountTab(page, "deposits-withdrawals");
  await expect(page.locator("[data-role='account-info-tab-deposits-withdrawals']"))
    .toHaveText("Account Activity");
  await expect.poll(() => observedLedgerRequests.length, { timeout: 10_000 })
    .toBeGreaterThan(0);

  const strip = page.locator("[data-role='account-activity-sub-tab-strip']");
  const table = page.locator("[data-role='portfolio-deposits-withdrawals-table']");

  for (const viewport of PORTFOLIO_LEDGER_REVIEW_VIEWPORTS) {
    await page.setViewportSize(viewport);
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

    await expect(strip).toBeVisible();
    for (const label of [
      "All",
      "Account Transfers",
      "Deposits and Withdrawals",
      "Spot Transfers",
      "Internal Transfers",
      "Earn",
      "Vaults",
      "Staking",
      "Auctions"
    ]) {
      await expect(strip).toContainText(label);
    }

    await expect(table).toBeVisible();
    for (const header of [
      "Time",
      "Status",
      "Asset",
      "Action",
      "From",
      "To",
      "Destination",
      "Account Change",
      "USD Value",
      "Fee"
    ]) {
      await expect(table).toContainText(header);
    }
    // The pre-parity column names must be gone.
    await expect(table).not.toContainText("Account Value Change");

    await expect(table).toContainText("Deposit");
    await expect(table).toContainText("Vault Deposit");
    await expect(table).toContainText("Genesis Distribution");
    await expect(table).toContainText("Internal Transfer");
    await expect(table).toContainText("Arbitrum");
    await expect(table).toContainText("Trading Account");
    await expect(table).toContainText("+100 USDC");
    await expect(table).toContainText("-10 USDC");
    await expect(table).toContainText("+2670.03 HYPE");
    await expect(table).toContainText("-1 HYPE");
    // Incoming spot transfer: a credit, valued from the payload's usdcValue.
    await expect(table).toContainText("+5 HYPE");
    await expect(table).toContainText("$212.50");

    await expect(table.locator("a[aria-label='Open transaction in Hyperliquid explorer']"))
      .toHaveCount(5);
    // Every column header sorts; with five rows the pagination footer stays hidden,
    // so no other control may appear inside the table.
    await expect(table.locator("button")).toHaveCount(10);
    await expect(table.locator("input, select, textarea")).toHaveCount(0);
    await expect(page.locator("[data-role='portfolio-funding-action-deposit']")).toHaveCount(0);

    const tableMetrics = await table.evaluate((node) => {
      const rect = node.getBoundingClientRect();
      return {
        top: rect.top,
        bottom: rect.bottom,
        width: rect.width,
        hasBodyHorizontalOverflow:
          document.documentElement.scrollWidth > window.innerWidth + 1
      };
    });
    expect(tableMetrics.width).toBeGreaterThan(0);
    expect(tableMetrics.bottom).toBeGreaterThan(tableMetrics.top);
    expect(tableMetrics.hasBodyHorizontalOverflow).toBe(false);

    const firstExplorerLink = table
      .locator("a[aria-label='Open transaction in Hyperliquid explorer']")
      .first();
    await firstExplorerLink.focus();
    await expect(firstExplorerLink).toBeFocused();
  }

  await page.setViewportSize(PORTFOLIO_LEDGER_REVIEW_VIEWPORTS[0]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  // Selecting a sub-tab filters to that type only.
  await page.locator("[data-role='account-activity-sub-tab-vaults']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(table).toContainText("Vault Deposit");
  await expect(table).not.toContainText("Genesis Distribution");
  await expect(table).not.toContainText("+100 USDC");

  // Spot Transfers hides the Action and Destination columns, per the reference.
  await page.locator("[data-role='account-activity-sub-tab-spot-transfers']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(table).toContainText("+5 HYPE");
  await expect(table).not.toContainText("Action");
  await expect(table).not.toContainText("Destination");
  await expect(table.locator("button")).toHaveCount(8);

  // A sub-tab with no matching rows says so rather than rendering an empty grid.
  await page.locator("[data-role='account-activity-sub-tab-staking']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(table).toContainText("No Staking");

  // All is a genuine superset: every row is reachable again.
  await page.locator("[data-role='account-activity-sub-tab-all']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(table).toContainText("Genesis Distribution");
  await expect(table).toContainText("Vault Deposit");
  await expect(table).toContainText("+5 HYPE");
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

test("portfolio Monte Carlo forecast horizon can return to one year after six months @regression", async ({ page }) => {
  await stubPortfolioSummaryInfo(page, {
    allTime: {
      accountValueHistory: [],
      pnlHistory: []
    }
  });
  await visitRoute(page, `/portfolio/trader/${TRADER_ADDRESS}`);

  for (const viewport of PORTFOLIO_LEDGER_REVIEW_VIEWPORTS) {
    await page.setViewportSize(viewport);
    await seedNearYearMonteCarloForecast(page);

    const horizon = page.locator("[data-role='portfolio-monte-carlo-seg-horizon']");
    const sixMonths = horizon.getByRole("button", { name: "6M" });
    const oneYear = horizon.getByRole("button", { name: "1Y" });
    const twoYears = horizon.getByRole("button", { name: "2Y" });

    await expect(horizon).toBeVisible();
    await expect(oneYear).toHaveAttribute("aria-pressed", "true");
    await expect(oneYear).toBeEnabled();
    await expect(twoYears).toBeDisabled();

    await sixMonths.click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
    await expect(sixMonths).toHaveAttribute("aria-pressed", "true");
    await expect(oneYear).toBeEnabled();

    await oneYear.click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
    await expect(oneYear).toHaveAttribute("aria-pressed", "true");
    await expect(twoYears).toBeDisabled();
  }

  await page.unroute("**/info");
});

test("portfolio positions coin jumps to the trade route market @regression", async ({ page }) => {
  const hypeClearinghouseState = {
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
  };
  const hypeWebData2 = {
    clearinghouseState: hypeClearinghouseState
  };
  await page.route("**/info", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      try {
        const payload = request.postDataJSON();
        if (payload?.type === "webData2" || payload?.type === "clearinghouseState") {
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify(payload.type === "webData2" ? hypeWebData2 : hypeClearinghouseState)
          });
          return;
        }
      } catch {
        // Let non-JSON requests continue.
      }
    }
    await route.continue();
  });

  await visitRoute(page, "/portfolio");
  await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await page.evaluate(({ webData2, clearinghouseState }) => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const opts = c.PersistentArrayMap.fromArray([kw("keywordize-keys"), true], true);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const webData2Clj = c.js__GT_clj(webData2, opts);
    const clearinghouseClj = c.js__GT_clj(clearinghouseState, opts);
    const perpDexStates = c.PersistentArrayMap.fromArray(
      ["", clearinghouseClj, "hl", clearinghouseClj],
      true
    );
    let nextState = c.assoc_in(
      c.deref(globalThis.hyperopen.system.store),
      path("webdata2"),
      webData2Clj
    );
    nextState = c.assoc_in(nextState, path("perp-dex-clearinghouse"), perpDexStates);
    c.reset_BANG_(globalThis.hyperopen.system.store, nextState);
  }, { webData2: hypeWebData2, clearinghouseState: hypeClearinghouseState });
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
