import { expect, test } from "@playwright/test";
import {
  debugCall,
  dispatch,
  expectOracle,
  mobileViewport,
  oracle,
  sourceRectForLocator,
  visitRoute,
  waitForIdle
} from "../support/hyperopen.mjs";

const SPECTATE_ADDRESS = "0x162cc7c861ebd0c06b3d72319201150482518185";

async function selectAccountTab(page, tabValue) {
  const tab = page.locator(`[data-role='account-info-tab-${tabValue}']`);
  await tab.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tab).toHaveAttribute("aria-pressed", "true");
}

async function readTradeShellGeometry(page) {
  return page.evaluate(() => {
    const byParity = (id) => document.querySelector(`[data-parity-id="${id}"]`);
    const byRole = (role) => document.querySelector(`[data-role="${role}"]`);
    const chart = byParity("trade-chart-panel");
    const orderbook = byParity("trade-orderbook-panel");
    const account = byParity("trade-account-tables-panel");
    const chartCanvas = byParity("chart-canvas");
    const chartLibrary = chartCanvas?.querySelector(".tv-lightweight-charts");
    const scrollShell = byRole("trade-scroll-shell");
    const chartRect = chart?.getBoundingClientRect();
    const orderbookRect = orderbook?.getBoundingClientRect();
    const accountRect = account?.getBoundingClientRect();
    const chartCanvasRect = chartCanvas?.getBoundingClientRect();
    const chartLibraryRect = chartLibrary?.getBoundingClientRect();

    if (!chartRect || !orderbookRect || !accountRect || !chartCanvasRect || !chartLibraryRect || !scrollShell) {
      throw new Error("trade shell geometry unavailable");
    }

    const lowerPanelShare = accountRect.height / (chartRect.height + accountRect.height);

    return {
      chartHeight: chartRect.height,
      accountHeight: accountRect.height,
      accountWidth: accountRect.width,
      lowerPanelShare,
      chartFlushDelta: accountRect.top - chartRect.bottom,
      orderbookFlushDelta: accountRect.top - orderbookRect.bottom,
      accountTopMinusChartCanvasBottom: accountRect.top - chartCanvasRect.bottom,
      chartPanelBottomMinusChartCanvasBottom: chartRect.bottom - chartCanvasRect.bottom,
      chartLibraryBottomMinusHostBottom: chartLibraryRect.bottom - chartCanvasRect.bottom,
      scrollShellCanScroll: scrollShell.scrollHeight - scrollShell.clientHeight > 1
    };
  });
}

async function seedDisconnectedSpectateAccountState(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const nextWebdata2 = c.js__GT_clj(
      {
        clearinghouseState: {
          marginSummary: {
            accountValue: "4708974.9",
            totalNtlPos: "6398054.11",
            totalRawUsd: "6392466.23",
            totalMarginUsed: "63387.27"
          },
          crossMarginSummary: {
            accountValue: "4708974.9",
            totalNtlPos: "6398054.11",
            totalRawUsd: "6392466.23",
            totalMarginUsed: "63387.27"
          },
          crossMaintenanceMarginUsed: "63387.27",
          withdrawable: "4234255.18",
          assetPositions: [
            {
              position: {
                coin: "BTC",
                szi: "0.99455",
                positionValue: "67250.5",
                entryPx: "67000",
                markPx: "67251",
                unrealizedPnl: "309",
                returnOnEquity: "0.0046",
                liquidationPx: "4671.46",
                leverage: { value: 0.31 },
                marginUsed: "1000",
                cumFunding: { sinceOpen: "0" }
              }
            }
          ]
        }
      },
      opts
    );
    const nextSpot = c.js__GT_clj(
      {
        balances: [
          {
            coin: "USDC",
            hold: "0",
            total: "4465534.37",
            entryNtl: "4465534.37"
          }
        ]
      },
      opts
    );
    const nextOrders = c.js__GT_clj(
      {
        "open-orders": [
          {
            coin: "BTC",
            oid: 101,
            side: "B",
            sz: "1.0",
            origSz: "1.0",
            limitPx: "65000",
            orderType: "Limit",
            timestamp: 1700000000000,
            reduceOnly: false,
            isTrigger: false,
            isPositionTpsl: false
          }
        ],
        "open-orders-hydrated?": true,
        "open-orders-snapshot": [
          {
            coin: "BTC",
            oid: 101,
            side: "B",
            sz: "1.0",
            origSz: "1.0",
            limitPx: "65000",
            orderType: "Limit",
            timestamp: 1700000000000,
            reduceOnly: false,
            isTrigger: false,
            isPositionTpsl: false
          }
        ],
        "open-orders-snapshot-by-dex": {},
        fills: [
          {
            coin: "BTC",
            tid: 77
          }
        ],
        "fundings-raw": [],
        fundings: [],
        "order-history": [],
        ledger: [],
        "twap-states": [],
        "twap-history": [],
        "twap-slice-fills": [],
        "pending-cancel-oids": null
      },
      opts
    );
    const nextState = c.deref(store);
    const seededState = c.assoc_in(
      c.assoc_in(
        c.assoc_in(nextState, kwPath("webdata2"), nextWebdata2),
        kwPath("spot", "clearinghouse-state"),
        nextSpot
      ),
      kwPath("orders"),
      nextOrders
    );

    c.reset_BANG_(store, seededState);
  });
}

async function seedDesktopPositionsTableState(page, assetPositions) {
  await page.evaluate((nextAssetPositions) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const nextWebdata2 = c.js__GT_clj(
      {
        clearinghouseState: {
          marginSummary: {
            accountValue: "1000000",
            totalNtlPos: "250000",
            totalRawUsd: "1000000",
            totalMarginUsed: "20000"
          },
          crossMarginSummary: {
            accountValue: "1000000",
            totalNtlPos: "250000",
            totalRawUsd: "1000000",
            totalMarginUsed: "20000"
          },
          crossMaintenanceMarginUsed: "20000",
          withdrawable: "800000",
          assetPositions: nextAssetPositions
        }
      },
      opts
    );

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, kwPath("webdata2"), nextWebdata2);
    nextState = c.assoc_in(nextState, kwPath("perp-dex-clearinghouse"), c.PersistentArrayMap.EMPTY);
    nextState = c.assoc_in(nextState, kwPath("account-info", "selected-tab"), keyword("positions"));
    nextState = c.assoc_in(nextState, kwPath("account-info", "loading"), false);
    nextState = c.assoc_in(nextState, kwPath("account-info", "error"), null);

    c.reset_BANG_(store, nextState);
    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  }, assetPositions);
}

async function markAccountInfoReady(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, kwPath("account-info", "loading"), false);
    nextState = c.assoc_in(nextState, kwPath("account-info", "error"), null);
    c.reset_BANG_(store, nextState);

    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  });
}

async function seedDesktopPositionsUntilVisible(page, assetPositions, rowText) {
  const row = page
    .locator("[data-role='account-tab-rows-viewport'] > div")
    .filter({ hasText: rowText })
    .first();

  for (let attempt = 0; attempt < 4; attempt += 1) {
    await seedDesktopPositionsTableState(page, assetPositions);
    await waitForIdle(page, { quietMs: 250, timeoutMs: 5_000, pollMs: 50 });
    await markAccountInfoReady(page);

    if ((await row.count()) > 0 && await row.isVisible()) {
      return row;
    }
  }

  throw new Error(`Unable to render seeded position row for ${rowText}`);
}

async function forceAssetSelectorBootstrapState(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, kwPath("asset-selector", "phase"), keyword("bootstrap"));
    nextState = c.assoc_in(
      nextState,
      kwPath("asset-selector", "market-by-key"),
      c.PersistentArrayMap.EMPTY
    );
    nextState = c.assoc_in(nextState, kwPath("asset-selector", "loading?"), false);
    c.reset_BANG_(store, nextState);
  });
}

async function readSpectateLifecycleProbe(page) {
  return page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const state = c.deref(store);
    const getIn = (...segments) => c.get_in(state, kwPath(...segments));

    return {
      spectateActive: getIn("account-context", "spectate-mode", "active?") ?? null,
      spectateAddress: getIn("account-context", "spectate-mode", "address") ?? null,
      webdata2Present: Boolean(getIn("webdata2")),
      spotClearinghousePresent: Boolean(getIn("spot", "clearinghouse-state"))
    };
  });
}

async function freezeAccountSurfaceSync(page, address) {
  await page.evaluate((nextAddress) => {
    const store = globalThis.hyperopen?.system?.store;
    const addressWatcher = globalThis.hyperopen?.wallet?.address_watcher;
    const webdata2 = globalThis.hyperopen?.websocket?.webdata2;
    const userSubscriptions = globalThis.hyperopen?.websocket?.user_runtime?.subscriptions;

    if (!store || !addressWatcher || !webdata2 || !userSubscriptions) {
      throw new Error("Hyperopen account sync runtime unavailable");
    }

    addressWatcher.stop_watching_BANG_(store);
    addressWatcher.remove_handler_BANG_("webdata2-subscription-handler");
    addressWatcher.remove_handler_BANG_("user-ws-subscription-handler");
    addressWatcher.remove_handler_BANG_("startup-account-bootstrap-handler");
    webdata2.unsubscribe_webdata2_BANG_(nextAddress);
    userSubscriptions.unsubscribe_user_BANG_(nextAddress);
  }, address);
}

function buildCachedAssetSelectorMarkets(count = 240) {
  const baseMarkets = [
    {
      key: "perp:BTC",
      coin: "BTC",
      symbol: "BTC",
      base: "BTC",
      "market-type": "perp",
      category: "crypto",
      volume24h: 2_900_000_000,
      openInterest: 1_900_000_000,
      mark: 66_880,
      markRaw: 66_880,
      change24h: -1_528,
      change24hPct: -2.23,
      fundingRate: -0.00001,
      "cache-order": 0
    },
    {
      key: "perp:ETH",
      coin: "ETH",
      symbol: "ETH",
      base: "ETH",
      "market-type": "perp",
      category: "crypto",
      volume24h: 1_800_000_000,
      openInterest: 1_200_000_000,
      mark: 3_410,
      markRaw: 3_410,
      change24h: 84,
      change24hPct: 2.52,
      fundingRate: 0.00012,
      "cache-order": 1
    },
    {
      key: "perp:SOL",
      coin: "SOL",
      symbol: "SOL",
      base: "SOL",
      "market-type": "perp",
      category: "crypto",
      volume24h: 950_000_000,
      openInterest: 620_000_000,
      mark: 168,
      markRaw: 168,
      change24h: 5.4,
      change24hPct: 3.31,
      fundingRate: 0.00009,
      "cache-order": 2
    }
  ];

  const generatedMarkets = Array.from({ length: Math.max(0, count - baseMarkets.length) }, (_, index) => {
    const ordinal = index + 1;
    return {
      key: `perp:TST${ordinal}`,
      coin: `TST${ordinal}`,
      symbol: `TST${ordinal}`,
      base: `TST${ordinal}`,
      "market-type": "perp",
      category: "crypto",
      volume24h: 500_000_000 - ordinal,
      openInterest: 250_000_000 - ordinal,
      mark: 100 + ordinal,
      markRaw: 100 + ordinal,
      change24h: ordinal / 10,
      change24hPct: ordinal / 100,
      fundingRate: 0.00005,
      "cache-order": baseMarkets.length + index
    };
  });

  return [...baseMarkets, ...generatedMarkets];
}

async function seedAssetSelectorMarketsCache(page, count = 240) {
  const rows = buildCachedAssetSelectorMarkets(count);
  await page.addInitScript((cacheRows) => {
    window.localStorage.setItem(
      "asset-selector-markets-cache",
      JSON.stringify({
        id: "asset-selector-markets-cache",
        version: 1,
        "saved-at-ms": Date.now(),
        rows: cacheRows
      })
    );
  }, rows);
}

async function seedOutcomeAssetSelectorMarketsCache(page) {
  await page.addInitScript(() => {
    const rows = [
      {
        key: "outcome:#0",
        coin: "#0",
        symbol: "BTC above 78213 on May 3 at 2:00 AM?",
        title: "BTC above 78213 on May 3 at 2:00 AM?",
        underlying: "BTC",
        quote: "USDH",
        "market-type": "outcome",
        category: "crypto",
        mark: 0.62,
        markRaw: "0.6214",
        volume24h: 2_250_183,
        openInterest: 865_785,
        change24h: 0.07,
        change24hPct: 12,
        "cache-order": 0
      }
    ];
    window.localStorage.setItem("asset-selector-active-tab", "outcome");
    window.localStorage.setItem(
      "asset-selector-markets-cache",
      JSON.stringify({
        id: "asset-selector-markets-cache",
        version: 1,
        "saved-at-ms": Date.now(),
        rows
      })
    );
  });
}

async function waitForHyperopenStoreReady(page) {
  await expect.poll(async () => page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;
    return Boolean(c && store);
  }), { timeout: 20_000 }).toBe(true);
}

async function waitForAssetSelectorLoadSettled(page) {
  await waitForHyperopenStoreReady(page);
  await expect.poll(async () => page.evaluate(() => {
    const c = globalThis.cljs.core;
    const store = globalThis.hyperopen.system.store;
    const path = c.PersistentVector.fromArray(
      [c.keyword("asset-selector"), c.keyword("loading?")],
      true
    );
    return c.get_in(c.deref(store), path) === true;
  }), { timeout: 20_000 }).toBe(false);
}

async function stubAssetSelectorMarketInfo(page) {
  const emptyInfoResponses = new Map([
    ["perpDexs", []],
    ["spotMeta", { tokens: [], universe: [] }],
    ["webData2", { spotAssetCtxs: [] }],
    ["outcomeMeta", { outcomes: [], questions: [] }],
    ["metaAndAssetCtxs", [{ universe: [], marginTables: [] }, []]]
  ]);

  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;

    if (emptyInfoResponses.has(requestType)) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(emptyInfoResponses.get(requestType))
      });
      return;
    }

    await route.continue();
  });
}

async function seedGroupedOutcomeAssetSelectorState(page, { activeTab = null } = {}) {
  await waitForHyperopenStoreReady(page);
  await page.evaluate((nextActiveTab) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const kw = (name) => c.keyword(name);
    const path = (...segments) => c.PersistentVector.fromArray(segments.map(kw), true);
    const vector = (items) => c.PersistentVector.fromArray(items, true);
    const map = (pairs) => c.PersistentArrayMap.fromArray(pairs.flatMap(([key, value]) => [kw(key), value]), true);
    const stringMap = (pairs) => c.PersistentArrayMap.fromArray(pairs.flatMap(([key, value]) => [key, value]), true);
    const side = ({ sideIndex, sideName, coin, assetId, outcomeId, optionLabel, mark }) =>
      map([
        ["side-index", sideIndex],
        ["side-name", sideName],
        ["side-label", sideName],
        ["coin", coin],
        ["asset-id", assetId],
        ["outcome-id", outcomeId],
        ["outcome-option-label", optionLabel],
        ["mark", mark]
      ]);
    const option = ({ label, outcomeId, yes, no, mark, volume24h, openInterest }) =>
      map([
        ["label", label],
        ["outcome-id", outcomeId],
        ["yes-coin", c.get(yes, kw("coin"))],
        ["yes-asset-id", c.get(yes, kw("asset-id"))],
        ["no-coin", c.get(no, kw("coin"))],
        ["no-asset-id", c.get(no, kw("asset-id"))],
        ["mark", mark],
        ["volume24h", volume24h],
        ["openInterest", openInterest],
        ["sides", vector([yes, no])]
      ]);

    const belowYes = side({
      sideIndex: 0,
      sideName: "Yes",
      coin: "#1610",
      assetId: 100001610,
      outcomeId: 161,
      optionLabel: "Below 61044",
      mark: 0.612275
    });
    const belowNo = side({
      sideIndex: 1,
      sideName: "No",
      coin: "#1611",
      assetId: 100001611,
      outcomeId: 161,
      optionLabel: "Below 61044",
      mark: 0.387725
    });
    const middleYes = side({
      sideIndex: 0,
      sideName: "Yes",
      coin: "#1620",
      assetId: 100001620,
      outcomeId: 162,
      optionLabel: "61044 to 63535",
      mark: 0.3003
    });
    const middleNo = side({
      sideIndex: 1,
      sideName: "No",
      coin: "#1621",
      assetId: 100001621,
      outcomeId: 162,
      optionLabel: "61044 to 63535",
      mark: 0.6997
    });
    const aboveYes = side({
      sideIndex: 0,
      sideName: "Yes",
      coin: "#1630",
      assetId: 100001630,
      outcomeId: 163,
      optionLabel: "Above 63535",
      mark: 0.01181
    });
    const aboveNo = side({
      sideIndex: 1,
      sideName: "No",
      coin: "#1631",
      assetId: 100001631,
      outcomeId: 163,
      optionLabel: "Above 63535",
      mark: 0.98819
    });
    const cpiBelowYes = side({
      sideIndex: 0,
      sideName: "Yes",
      coin: "#1010",
      assetId: 100001010,
      outcomeId: 101,
      optionLabel: "Below 4.3%",
      mark: 0.61
    });
    const cpiBelowNo = side({
      sideIndex: 1,
      sideName: "No",
      coin: "#1011",
      assetId: 100001011,
      outcomeId: 101,
      optionLabel: "Below 4.3%",
      mark: 0.39
    });
    const spursYes = side({
      sideIndex: 0,
      sideName: "San Antonio",
      coin: "#1420",
      assetId: 100001420,
      outcomeId: 142,
      optionLabel: "San Antonio",
      mark: 0.67
    });
    const spursNo = side({
      sideIndex: 1,
      sideName: "New York",
      coin: "#1421",
      assetId: 100001421,
      outcomeId: 142,
      optionLabel: "New York",
      mark: 0.33
    });
    const worldCupSpecs = [
      { label: "Algeria", outcomeId: 301, coinBase: 301, mark: 0.00244, volume24h: 0, openInterest: 27_779 },
      { label: "Argentina", outcomeId: 173, coinBase: 173, mark: 0.09005, volume24h: 2_395, openInterest: 50_807 },
      { label: "Australia", outcomeId: 302, coinBase: 302, mark: 0.00478, volume24h: 0, openInterest: 27_596 },
      { label: "Austria", outcomeId: 303, coinBase: 303, mark: 0.00287, volume24h: 0, openInterest: 27_792 },
      { label: "Belgium", outcomeId: 304, coinBase: 304, mark: 0.02001, volume24h: 42, openInterest: 28_913 },
      { label: "Bosnia and Herzegovina", outcomeId: 305, coinBase: 305, mark: 0.00369, volume24h: 2, openInterest: 27_787 },
      { label: "Brazil", outcomeId: 306, coinBase: 306, mark: 0.085, volume24h: 444, openInterest: 48_379 },
      { label: "Canada", outcomeId: 307, coinBase: 307, mark: 0.0018, volume24h: 0, openInterest: 27_596 },
      { label: "Cape Verde", outcomeId: 308, coinBase: 308, mark: 0.00259, volume24h: 0, openInterest: 27_792 },
      { label: "Colombia", outcomeId: 309, coinBase: 309, mark: 0.01444, volume24h: 26, openInterest: 27_811 },
      { label: "Congo DR", outcomeId: 310, coinBase: 310, mark: 0.00219, volume24h: 0, openInterest: 27_596 },
      { label: "Croatia", outcomeId: 311, coinBase: 311, mark: 0.008, volume24h: 85, openInterest: 28_004 },
      { label: "Curacao", outcomeId: 312, coinBase: 312, mark: 0.0015, volume24h: 0, openInterest: 27_500 },
      { label: "Czechia", outcomeId: 313, coinBase: 313, mark: 0.004, volume24h: 4, openInterest: 27_640 },
      { label: "Ecuador", outcomeId: 314, coinBase: 314, mark: 0.006, volume24h: 7, openInterest: 27_690 },
      { label: "Egypt", outcomeId: 315, coinBase: 315, mark: 0.004, volume24h: 12, openInterest: 27_630 },
      { label: "England", outcomeId: 316, coinBase: 316, mark: 0.1109, volume24h: 615, openInterest: 15_168 },
      { label: "France", outcomeId: 189, coinBase: 189, mark: 0.17514, volume24h: 5_776, openInterest: 41_448 },
      { label: "Germany", outcomeId: 317, coinBase: 317, mark: 0.05319, volume24h: 179, openInterest: 14_524 },
      { label: "Ghana", outcomeId: 318, coinBase: 318, mark: 0.003, volume24h: 0, openInterest: 27_588 },
      { label: "Greece", outcomeId: 319, coinBase: 319, mark: 0.002, volume24h: 0, openInterest: 27_550 },
      { label: "Iran", outcomeId: 320, coinBase: 320, mark: 0.003, volume24h: 0, openInterest: 27_560 },
      { label: "Italy", outcomeId: 321, coinBase: 321, mark: 0.018, volume24h: 39, openInterest: 28_210 },
      { label: "Japan", outcomeId: 322, coinBase: 322, mark: 0.02137, volume24h: 50, openInterest: 12_230 },
      { label: "Mexico", outcomeId: 323, coinBase: 323, mark: 0.015, volume24h: 33, openInterest: 28_100 },
      { label: "Morocco", outcomeId: 324, coinBase: 324, mark: 0.026, volume24h: 58, openInterest: 28_840 },
      { label: "Netherlands", outcomeId: 325, coinBase: 325, mark: 0.04064, volume24h: 415, openInterest: 12_713 },
      { label: "Nigeria", outcomeId: 326, coinBase: 326, mark: 0.007, volume24h: 8, openInterest: 27_820 },
      { label: "Norway", outcomeId: 327, coinBase: 327, mark: 0.0321, volume24h: 121, openInterest: 12_478 },
      { label: "Paraguay", outcomeId: 328, coinBase: 328, mark: 0.006, volume24h: 5, openInterest: 27_780 },
      { label: "Poland", outcomeId: 329, coinBase: 329, mark: 0.008, volume24h: 8, openInterest: 27_880 },
      { label: "Portugal", outcomeId: 330, coinBase: 330, mark: 0.10559, volume24h: 3_092, openInterest: 22_417 },
      { label: "Qatar", outcomeId: 331, coinBase: 331, mark: 0.001, volume24h: 0, openInterest: 27_500 },
      { label: "Saudi Arabia", outcomeId: 332, coinBase: 332, mark: 0.004, volume24h: 3, openInterest: 27_640 },
      { label: "Scotland", outcomeId: 333, coinBase: 333, mark: 0.006, volume24h: 5, openInterest: 27_790 },
      { label: "Senegal", outcomeId: 334, coinBase: 334, mark: 0.006, volume24h: 7, openInterest: 27_800 },
      { label: "Serbia", outcomeId: 335, coinBase: 335, mark: 0.005, volume24h: 3, openInterest: 27_720 },
      { label: "South Korea", outcomeId: 336, coinBase: 336, mark: 0.008, volume24h: 8, openInterest: 27_880 },
      { label: "Spain", outcomeId: 212, coinBase: 212, mark: 0.17098, volume24h: 1_791, openInterest: 27_056 },
      { label: "Sweden", outcomeId: 337, coinBase: 337, mark: 0.005, volume24h: 5, openInterest: 27_720 },
      { label: "Switzerland", outcomeId: 338, coinBase: 338, mark: 0.01, volume24h: 12, openInterest: 27_950 },
      { label: "Tunisia", outcomeId: 339, coinBase: 339, mark: 0.003, volume24h: 0, openInterest: 27_560 },
      { label: "Turkey", outcomeId: 340, coinBase: 340, mark: 0.009, volume24h: 10, openInterest: 27_910 },
      { label: "Ukraine", outcomeId: 341, coinBase: 341, mark: 0.006, volume24h: 5, openInterest: 27_780 },
      { label: "Uruguay", outcomeId: 342, coinBase: 342, mark: 0.02, volume24h: 39, openInterest: 28_300 },
      { label: "USA", outcomeId: 343, coinBase: 343, mark: 0.015, volume24h: 20, openInterest: 28_050 },
      { label: "Wales", outcomeId: 344, coinBase: 344, mark: 0.004, volume24h: 2, openInterest: 27_640 },
      { label: "Ivory Coast", outcomeId: 345, coinBase: 345, mark: 0.005, volume24h: 5, openInterest: 27_700 }
    ];
    const makeWorldCupOption = (spec) => {
      const yesCoin = `#${spec.coinBase}0`;
      const noCoin = `#${spec.coinBase}1`;
      const yes = side({
        sideIndex: 0,
        sideName: "Yes",
        coin: yesCoin,
        assetId: 100_000_000 + spec.coinBase * 10,
        outcomeId: spec.outcomeId,
        optionLabel: spec.label,
        mark: spec.mark
      });
      const no = side({
        sideIndex: 1,
        sideName: "No",
        coin: noCoin,
        assetId: 100_000_001 + spec.coinBase * 10,
        outcomeId: spec.outcomeId,
        optionLabel: spec.label,
        mark: Math.max(0, 1 - spec.mark)
      });
      return {
        ...spec,
        yes,
        no,
        option: option({
          label: spec.label,
          outcomeId: spec.outcomeId,
          yes,
          no,
          mark: spec.mark,
          volume24h: spec.volume24h,
          openInterest: spec.openInterest
        })
      };
    };
    const worldCupRows = worldCupSpecs.map(makeWorldCupOption);
    const worldCupOptions = vector(worldCupRows.map((row) => row.option));
    const worldCupSubscriptionCoins = vector(
      worldCupRows.flatMap((row) => [c.get(row.yes, kw("coin")), c.get(row.no, kw("coin"))])
    );
    const franceWorldCup = worldCupRows.find((row) => row.label === "France");

    const questionOptions = vector([
      option({
        label: "Below 61044",
        outcomeId: 161,
        yes: belowYes,
        no: belowNo,
        mark: 0.612275,
        volume24h: 20_000,
        openInterest: 30_000
      }),
      option({
        label: "61044 to 63535",
        outcomeId: 162,
        yes: middleYes,
        no: middleNo,
        mark: 0.3003,
        volume24h: 14_000,
        openInterest: 20_000
      }),
      option({
        label: "Above 63535",
        outcomeId: 163,
        yes: aboveYes,
        no: aboveNo,
        mark: 0.01181,
        volume24h: 352,
        openInterest: 3_075
      })
    ]);
    const rangeAliases = stringMap([
      ["#1610", map([["coin", "#1610"], ["outcome-id", 161], ["side-index", 0], ["option-label", "Below 61044"], ["sibling-coins", vector(["#1610", "#1611"])]])],
      ["#1611", map([["coin", "#1611"], ["outcome-id", 161], ["side-index", 1], ["option-label", "Below 61044"], ["sibling-coins", vector(["#1610", "#1611"])]])],
      ["#1620", map([["coin", "#1620"], ["outcome-id", 162], ["side-index", 0], ["option-label", "61044 to 63535"], ["sibling-coins", vector(["#1620", "#1621"])]])],
      ["#1621", map([["coin", "#1621"], ["outcome-id", 162], ["side-index", 1], ["option-label", "61044 to 63535"], ["sibling-coins", vector(["#1620", "#1621"])]])],
      ["outcome:162", map([["coin", "outcome:162"], ["outcome-id", 162], ["side-index", 0], ["option-label", "61044 to 63535"], ["sibling-coins", vector(["#1620", "#1621"])]])]
    ]);

    const rangeMarket = map([
      ["key", "question:30"],
      ["coin", "#1610"],
      ["symbol", "BTC price range on Jun 6 at 2:00 AM?"],
      ["title", "BTC price range on Jun 6 at 2:00 AM?"],
      ["base", "BTC"],
      ["quote", "USDC"],
      ["market-type", kw("outcome")],
      ["category", kw("outcome")],
      ["outcome-kind", kw("question")],
      ["outcome-category", kw("crypto")],
      ["question-id", 30],
      ["fallback-outcome-id", 160],
      ["named-outcome-ids", vector([161, 162, 163])],
      ["period", "1d"],
      ["question-options", questionOptions],
      ["outcome-sides", vector([belowYes, belowNo])],
      ["outcome-side-aliases", rangeAliases],
      ["outcome-subscription-coins", vector(["#1610", "#1611", "#1620", "#1621", "#1630", "#1631"])],
      ["outcome-summary", "Below 61044 61%  *  61044 to 63535 30%  *  Above 63535 1%"],
      ["mark", 0.612275],
      ["volume24h", 34_352],
      ["openInterest", 53_075]
    ]);
    const cpiMarket = map([
      ["key", "question:19"],
      ["coin", "#1010"],
      ["symbol", "May CPI year-over-year"],
      ["title", "May CPI year-over-year"],
      ["base", "CPI"],
      ["quote", "USDC"],
      ["market-type", kw("outcome")],
      ["category", kw("outcome")],
      ["outcome-kind", kw("question")],
      ["outcome-category", kw("economics")],
      ["question-id", 19],
      ["question-options", vector([
        option({ label: "Below 4.3%", outcomeId: 101, yes: cpiBelowYes, no: cpiBelowNo, mark: 0.61, volume24h: 4_361, openInterest: 34_654 })
      ])],
      ["outcome-sides", vector([cpiBelowYes, cpiBelowNo])],
      ["outcome-subscription-coins", vector(["#1010", "#1011"])],
      ["outcome-summary", "Below 4.3% 61%"],
      ["mark", 0.61],
      ["volume24h", 4_361],
      ["openInterest", 34_654]
    ]);
    const worldCupMarket = map([
      ["key", "question:32"],
      ["coin", "#1890"],
      ["symbol", "2026 World Cup Champion"],
      ["title", "2026 World Cup Champion"],
      ["base", "World Cup"],
      ["quote", "USDC"],
      ["market-type", kw("outcome")],
      ["category", kw("outcome")],
      ["outcome-kind", kw("question")],
      ["outcome-category", kw("sports")],
      ["outcome-subcategory", kw("football")],
      ["question-id", 32],
      ["fallback-outcome-id", 171],
      ["named-outcome-ids", vector([189, 212, 173])],
      ["question-options", worldCupOptions],
      ["outcome-sides", vector([franceWorldCup.yes, franceWorldCup.no])],
      ["outcome-subscription-coins", worldCupSubscriptionCoins],
      ["outcome-summary", "France 17%  *  Spain 17%  *  Argentina 14%"],
      ["mark", 0.17514],
      ["volume24h", 40_000],
      ["openInterest", 325_917]
    ]);
    const sportsMarket = map([
      ["key", "outcome:142"],
      ["coin", "#1420"],
      ["symbol", "2026 NBA Finals champion"],
      ["title", "2026 NBA Finals champion"],
      ["base", "NBA"],
      ["quote", "USDC"],
      ["market-type", kw("outcome")],
      ["category", kw("outcome")],
      ["outcome-kind", kw("binary")],
      ["outcome-category", kw("sports")],
      ["outcome-subcategory", kw("basketball")],
      ["outcome-id", 142],
      ["outcome-sides", vector([spursYes, spursNo])],
      ["outcome-side-aliases", stringMap([
        ["#1420", map([["outcome-id", 142], ["side-index", 0], ["sibling-coins", vector(["#1420", "#1421"])]])],
        ["#1421", map([["outcome-id", 142], ["side-index", 1], ["sibling-coins", vector(["#1420", "#1421"])]])],
        ["outcome:142", map([["outcome-id", 142], ["side-index", 0], ["sibling-coins", vector(["#1420", "#1421"])]])]
      ])],
      ["outcome-subscription-coins", vector(["#1420", "#1421"])],
      ["outcome-summary", "San Antonio 67%  *  New York 33%"],
      ["mark", 0.67],
      ["volume24h", 29_092],
      ["openInterest", 50_529]
    ]);
    const markets = vector([rangeMarket, worldCupMarket, sportsMarket, cpiMarket]);
    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, path("asset-selector", "markets"), markets);
    nextState = c.assoc_in(
      nextState,
      path("asset-selector", "market-by-key"),
      stringMap([["question:30", rangeMarket], ["question:32", worldCupMarket], ["outcome:142", sportsMarket], ["question:19", cpiMarket]])
    );
    nextState = c.assoc_in(
      nextState,
      path("asset-selector", "market-index-by-key"),
      stringMap([["question:30", 0], ["question:32", 1], ["outcome:142", 2], ["question:19", 3]])
    );
    if (nextActiveTab) {
      nextState = c.assoc_in(nextState, path("asset-selector", "active-tab"), kw(nextActiveTab));
    }
    nextState = c.assoc_in(nextState, path("asset-selector", "phase"), kw("full"));
    nextState = c.assoc_in(nextState, path("asset-selector", "loading?"), false);
    nextState = c.assoc_in(nextState, path("asset-selector", "sort-by"), kw("volume"));
    nextState = c.assoc_in(nextState, path("asset-selector", "sort-direction"), kw("desc"));
    nextState = c.assoc_in(nextState, path("asset-selector", "live-market-subscriptions-paused?"), true);
    c.reset_BANG_(store, nextState);

    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  }, activeTab);
}

async function seedWorldCupOutcomeOrderForm(page) {
  await seedGroupedOutcomeAssetSelectorState(page, { activeTab: "sports" });
  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const kw = (name) => c.keyword(name);
    const path = (...segments) => c.PersistentVector.fromArray(segments, true);
    const worldCupMarket = c.get_in(
      c.deref(store),
      path(kw("asset-selector"), kw("market-by-key"), "question:32")
    );

    if (!worldCupMarket) {
      throw new Error("World Cup grouped outcome market missing from seeded state");
    }

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, path(kw("active-asset")), "#1890");
    nextState = c.assoc_in(nextState, path(kw("selected-asset")), "#1890");
    nextState = c.assoc_in(nextState, path(kw("active-market")), worldCupMarket);
    nextState = c.assoc_in(nextState, path(kw("order-form"), kw("outcome-option-id")), 189);
    nextState = c.assoc_in(nextState, path(kw("order-form"), kw("outcome-side")), 0);
    nextState = c.assoc_in(
      nextState,
      path(kw("order-form-ui"), kw("outcome-option-dropdown-open?")),
      false
    );
    nextState = c.assoc_in(
      nextState,
      path(kw("order-form-ui"), kw("outcome-option-query")),
      ""
    );
    c.reset_BANG_(store, nextState);

    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  });
}

async function seedSportsOutcomeOrderForm(page) {
  await seedGroupedOutcomeAssetSelectorState(page, { activeTab: "sports" });
  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const kw = (name) => c.keyword(name);
    const path = (...segments) => c.PersistentVector.fromArray(segments, true);
    const opts = c.PersistentArrayMap.fromArray([kw("keywordize-keys"), true], true);
    const sportsMarket = c.get_in(
      c.deref(store),
      path(kw("asset-selector"), kw("market-by-key"), "outcome:142")
    );

    if (!sportsMarket) {
      throw new Error("NBA Finals grouped outcome market missing from seeded state");
    }

    const orderbook = (bidPx, askPx) => c.js__GT_clj(
      {
        bids: [{ px: bidPx, sz: "20000.0" }, { px: "0.34000", sz: "152000.0" }],
        asks: [{ px: askPx, sz: "28.0" }, { px: "0.49729", sz: "28.0" }]
      },
      opts
    );
    const context = (coin, mark, openInterest) => c.js__GT_clj(
      {
        coin,
        mark,
        markRaw: String(mark),
        change24h: 0.0054,
        change24hPct: 3.25,
        dayNtlVlm: 5831,
        openInterest
      },
      opts
    );

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, path(kw("active-asset")), "#1420");
    nextState = c.assoc_in(nextState, path(kw("selected-asset")), "#1420");
    nextState = c.assoc_in(nextState, path(kw("active-market")), sportsMarket);
    nextState = c.assoc_in(nextState, path(kw("order-form"), kw("type")), kw("limit"));
    nextState = c.assoc_in(nextState, path(kw("order-form"), kw("side")), kw("buy"));
    nextState = c.assoc_in(nextState, path(kw("order-form"), kw("outcome-option-id")), 142);
    nextState = c.assoc_in(nextState, path(kw("order-form"), kw("outcome-side")), 0);
    nextState = c.assoc_in(nextState, path(kw("orderbooks"), "#1420"), orderbook("0.37240", "0.49864"));
    nextState = c.assoc_in(nextState, path(kw("orderbooks"), "#1421"), orderbook("0.61760", "0.62851"));
    nextState = c.assoc_in(nextState, path(kw("active-assets"), kw("contexts"), "#1420"), context("#1420", 0.3724, 780386));
    nextState = c.assoc_in(nextState, path(kw("active-assets"), kw("contexts"), "#1421"), context("#1421", 0.6176, 780386));
    nextState = c.assoc_in(nextState, path(kw("orderbook-ui"), kw("active-tab")), kw("orderbook"));
    c.reset_BANG_(store, nextState);

    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  });
}

async function seedSportsOutcomeOrderFormUntilReady(page) {
  const orderForm = page.locator('[data-parity-id="order-form"]');
  const sanAntonioButton = orderForm.getByRole("button", {
    name: "Buy San Antonio",
    exact: true
  });

  for (let attempt = 0; attempt < 4; attempt += 1) {
    await seedSportsOutcomeOrderForm(page);
    await waitForIdle(page, { quietMs: 250, timeoutMs: 6_000, pollMs: 50 });

    if ((await sanAntonioButton.count()) > 0 && await sanAntonioButton.isVisible()) {
      return;
    }
  }

  throw new Error("Unable to render seeded sports outcome side controls");
}

async function seedOutcomeSideOrderbook(page, { coin, bidPx, askPx }) {
  await page.evaluate(({ nextCoin, nextBidPx, nextAskPx }) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const kw = (name) => c.keyword(name);
    const path = (...segments) => c.PersistentVector.fromArray(segments, true);
    const opts = c.PersistentArrayMap.fromArray([kw("keywordize-keys"), true], true);
    const orderbook = c.js__GT_clj(
      {
        bids: [{ px: nextBidPx, sz: "20000.0" }, { px: "0.34000", sz: "152000.0" }],
        asks: [{ px: nextAskPx, sz: "28.0" }, { px: "0.49729", sz: "28.0" }]
      },
      opts
    );

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, path(kw("orderbooks"), nextCoin), orderbook);
    c.reset_BANG_(store, nextState);

    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  }, { nextCoin: coin, nextBidPx: bidPx, nextAskPx: askPx });
}

async function seedOutcomeActiveAsset(page, overrides = {}) {
  await page.evaluate((overrides) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    let market = c.js__GT_clj(
      {
        key: "outcome:#0",
        coin: "#0",
        symbol: "BTC above 78213 on May 3 at 2:00 AM?",
        title: "BTC above 78213 on May 3 at 2:00 AM?",
        underlying: "BTC",
        quote: "USDH",
        "target-price": 78213,
        mark: 0.57841,
        markRaw: "0.57841",
        change24h: 0.0268,
        change24hPct: 4.87,
        volume24h: 180211.68,
        openInterest: 537233,
        "expiry-ms": Date.UTC(2026, 4, 3, 2, 0, 0),
        "outcome-details": "If BTC settles above 78213, YES pays $1.",
        "outcome-sides": [
          { coin: "#0", name: "YES", "side-index": 0, circulatingSupply: 537233 },
          { coin: "#1", name: "NO", "side-index": 1, circulatingSupply: 537233 }
        ],
        ...overrides
      },
      opts
    );
    market = c.assoc(market, keyword("market-type"), keyword("outcome"));

    const context = c.js__GT_clj(
      {
        coin: "#0",
        mark: 0.57841,
        markRaw: "0.57841",
        change24h: 0.0268,
        change24hPct: 4.87,
        dayNtlVlm: 180211.68,
        openInterest: 537233
      },
      opts
    );

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, kwPath("active-asset"), "#0");
    nextState = c.assoc_in(nextState, kwPath("selected-asset"), "#0");
    nextState = c.assoc_in(nextState, kwPath("active-market"), market);
    nextState = c.assoc_in(nextState, kwPath("active-assets", "contexts", "#0"), context);
    nextState = c.assoc_in(nextState, kwPath("now-ms"), Date.UTC(2026, 4, 2, 15, 0, 0));

    c.reset_BANG_(store, nextState);
    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  }, overrides);
}

async function seedFundingTooltipLivePositionState(
  page,
  {
    position,
    mark = 107.7426,
    oracle = 107.61,
    fundingRate = 0.00015
  }
) {
  await debugCall(page, "seedFundingTooltipFixture", {
    coin: position.coin,
    mark,
    oracle,
    fundingRate
  });
  await dispatch(page, [":actions/reset-funding-hypothetical-position", position.coin]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 6_000, pollMs: 50 });

  const applySeed = async () => {
    await page.evaluate(({ nextPosition, nextMark, nextFundingRate }) => {
      const c = globalThis.cljs?.core;
      const store = globalThis.hyperopen?.system?.store;

      if (!c || !store) {
        throw new Error("Hyperopen store or cljs core unavailable");
      }

      const keyword = c.keyword;
      const kwPath = (...segments) =>
        c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
      const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
      const nextWebdata2 = c.js__GT_clj(
        {
          clearinghouseState: {
            assetPositions: [nextPosition]
          }
        },
        opts
      );
      let nextState = c.deref(store);
      const nextMarket = c.PersistentArrayMap.fromArray(
        [
          keyword("key"), `perp:${nextPosition.coin}`,
          keyword("coin"), nextPosition.coin,
          keyword("symbol"), nextPosition.coin,
          keyword("market-type"), keyword("perp"),
          keyword("mark"), nextMark,
          keyword("markRaw"), nextMark,
          keyword("fundingRate"), nextFundingRate,
          keyword("szDecimals"), 4,
          keyword("maxLeverage"), 20
        ],
        true
      );

      nextState = c.assoc_in(nextState, kwPath("active-asset"), nextPosition.coin);
      nextState = c.assoc_in(nextState, kwPath("active-market"), nextMarket);
      nextState = c.assoc_in(nextState, kwPath("webdata2"), nextWebdata2);

      c.reset_BANG_(store, nextState);
    }, { nextPosition: position, nextMark: mark, nextFundingRate: fundingRate });
  };

  const livePositionIsSeeded = async () => page.evaluate(({ coin, szi }) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const state = c.deref(store);
    const webdata2 = c.get(state, keyword("webdata2"));
    const clearinghouseState = c.get(webdata2, keyword("clearinghouseState"));
    const assetPositions = c.clj__GT_js(
      c.get(clearinghouseState, keyword("assetPositions"))
    ) || [];
    const matchingPosition = assetPositions.find((entry) => {
      const nextPosition = entry?.position ?? entry;
      return nextPosition?.coin === coin && String(nextPosition?.szi) === String(szi);
    });

    return Boolean(matchingPosition);
  }, { coin: position.coin, szi: position.szi });

  for (let attempt = 0; attempt < 3; attempt += 1) {
    await applySeed();
    await waitForIdle(page, { quietMs: 350, timeoutMs: 6_000, pollMs: 50 });

    if (await livePositionIsSeeded()) {
      return;
    }
  }

  throw new Error(`Unable to stabilize funding tooltip live position for ${position.coin}`);
}

async function seedRememberedTradingSession(page, options = {}) {
  const {
    walletAddress = "0x1111111111111111111111111111111111111111",
    agentAddress = "0x9999999999999999999999999999999999999999",
    privateKey = "0xpriv",
    status = "ready",
    localProtectionMode = "plain",
    passkeySupported = true
  } = options;

  await page.evaluate(
    ({
      walletAddress: nextWalletAddress,
      agentAddress: nextAgentAddress,
      privateKey: nextPrivateKey,
      status: nextStatus,
      localProtectionMode: nextLocalProtectionMode,
      passkeySupported: nextPasskeySupported
    }) => {
      const c = globalThis.cljs?.core;
      const store = globalThis.hyperopen?.system?.store;

      if (!c || !store) {
        throw new Error("Hyperopen store or cljs core unavailable");
      }

      const keyword = c.keyword;
      const kwPath = (...segments) =>
        c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
      const lowerWallet = String(nextWalletAddress).toLowerCase();
      const lastApprovedAt = 1700000000000;
      const sessionKey = `hyperopen:agent-session:v1:${lowerWallet}`;
      const passkeyKey = `hyperopen:agent-passkey-session:v1:${lowerWallet}`;

      localStorage.setItem("hyperopen:agent-storage-mode:v1", "local");
      localStorage.setItem(
        "hyperopen:agent-local-protection-mode:v1",
        String(nextLocalProtectionMode)
      );

      localStorage.removeItem(sessionKey);
      localStorage.removeItem(passkeyKey);

      if (nextLocalProtectionMode === "plain") {
        localStorage.setItem(
          sessionKey,
          JSON.stringify({
            "agent-address": nextAgentAddress,
            "private-key": nextPrivateKey,
            "last-approved-at": lastApprovedAt,
            "nonce-cursor": lastApprovedAt
          })
        );
      } else {
        localStorage.setItem(
          passkeyKey,
          JSON.stringify({
            "agent-address": nextAgentAddress,
            "credential-id": "cred",
            "prf-salt": "salt",
            "last-approved-at": lastApprovedAt,
            "nonce-cursor": lastApprovedAt
          })
        );
      }

      let nextState = c.deref(store);
      nextState = c.assoc_in(nextState, kwPath("wallet", "connected?"), true);
      nextState = c.assoc_in(nextState, kwPath("wallet", "address"), nextWalletAddress);
      nextState = c.assoc_in(nextState, kwPath("wallet", "chain-id"), "0xa4b1");
      nextState = c.assoc_in(nextState, kwPath("wallet", "agent", "status"), keyword(nextStatus));
      nextState = c.assoc_in(nextState, kwPath("wallet", "agent", "storage-mode"), keyword("local"));
      nextState = c.assoc_in(
        nextState,
        kwPath("wallet", "agent", "local-protection-mode"),
        keyword(nextLocalProtectionMode)
      );
      nextState = c.assoc_in(
        nextState,
        kwPath("wallet", "agent", "passkey-supported?"),
        Boolean(nextPasskeySupported)
      );
      nextState = c.assoc_in(
        nextState,
        kwPath("wallet", "agent", "agent-address"),
        nextAgentAddress
      );
      nextState = c.assoc_in(
        nextState,
        kwPath("wallet", "agent", "last-approved-at"),
        lastApprovedAt
      );
      nextState = c.assoc_in(nextState, kwPath("wallet", "agent", "nonce-cursor"), lastApprovedAt);
      nextState = c.assoc_in(nextState, kwPath("wallet", "agent", "error"), null);
      nextState = c.assoc_in(
        nextState,
        kwPath("wallet", "agent", "recovery-modal-open?"),
        false
      );

      c.reset_BANG_(store, nextState);
    },
    { walletAddress, agentAddress, privateKey, status, localProtectionMode, passkeySupported }
  );
}

async function seedReadyTradingSession(page, options = {}) {
  await seedRememberedTradingSession(page, {
    status: "ready",
    localProtectionMode: "plain",
    passkeySupported: false,
    ...options
  });
}

async function seedOwnedSubaccounts(page, { ownerAddress, subaccountAddress, selectedAddress = null }) {
  await page.evaluate(
    ({
      ownerAddress: nextOwnerAddress,
      selectedAddress: nextSelectedAddress,
      subaccountAddress: nextSubaccountAddress
    }) => {
      const c = globalThis.cljs?.core;
      const store = globalThis.hyperopen?.system?.store;

      if (!c || !store) {
        throw new Error("Hyperopen store or cljs core unavailable");
      }

      const keyword = c.keyword;
      const kwPath = (...segments) =>
        c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
      const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
      const rows = c.js__GT_clj(
        [
          {
            name: "Desk",
            master: String(nextOwnerAddress).toLowerCase(),
            subAccountUser: String(nextSubaccountAddress).toLowerCase(),
            clearinghouseState: {
              marginSummary: { accountValue: "1000", totalMarginUsed: "0" },
              withdrawable: "1000",
              assetPositions: []
            },
            spotState: { balances: [] }
          }
        ],
        opts
      );

      let nextState = c.deref(store);
      nextState = c.assoc_in(
        nextState,
        kwPath("account-context", "subaccounts", "rows"),
        rows
      );
      nextState = c.assoc_in(
        nextState,
        kwPath("account-context", "subaccounts", "status"),
        keyword("loaded")
      );
      nextState = c.assoc_in(
        nextState,
        kwPath("account-context", "subaccounts", "loaded-for-owner"),
        String(nextOwnerAddress).toLowerCase()
      );
      nextState = c.assoc_in(
        nextState,
        kwPath("account-context", "subaccounts", "selected-address"),
        nextSelectedAddress ? String(nextSelectedAddress).toLowerCase() : null
      );
      nextState = c.assoc_in(
        nextState,
        kwPath("account-context", "subaccounts", "selection-loaded?"),
        true
      );
      c.reset_BANG_(store, nextState);

      const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
      if (typeof renderApp === "function") {
        renderApp(c.deref(store));
      }
    },
    { ownerAddress, selectedAddress, subaccountAddress }
  );
}

async function fillLimitOrderForm(page) {
  await dispatch(page, [":actions/select-order-entry-mode", ":limit"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-input-mode", ":base"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/update-order-form", [":price"], "100"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-display", "1"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
}

function signedActionRequests(snapshot, actionType) {
  return (snapshot?.calls ?? [])
    .map((call) => call?.request)
    .filter((request) => request?.action?.type === actionType);
}

async function selectHeaderAccountTarget(page, optionDataRole) {
  const option = page.locator(`[data-role="${optionDataRole}"]`);

  if (!(await option.isVisible())) {
    await page.locator('[data-role="wallet-menu-trigger"]').click();
    await expect(option).toBeVisible();
  }

  await option.click();
}

async function closeHeaderAccountTarget(page) {
  await page.locator('[data-role="wallet-menu-details"]').evaluate((node) => {
    node.open = false;
  });
}

async function openHeaderAccountTarget(page) {
  await page.locator('[data-role="wallet-menu-details"]').evaluate((node) => {
    node.open = true;
  });
  await expect(page.locator('[data-role="wallet-menu-panel"]')).toBeVisible();
}

async function headerSubaccountGeometry(page) {
  return page.evaluate(() => {
    const viewportWidth = window.innerWidth;
    const readRect = (role) => {
      const node = document.querySelector(`[data-role="${role}"]`);
      if (!node) {
        return null;
      }
      const rect = node.getBoundingClientRect();
      return {
        left: rect.left,
        right: rect.right,
        top: rect.top,
        bottom: rect.bottom,
        width: rect.width,
        height: rect.height
      };
    };
    return {
      viewportWidth,
      banner: readRect("header-subaccount-active-banner"),
      trigger: readRect("wallet-menu-trigger"),
      menu: readRect("wallet-menu-panel")
    };
  });
}

async function setTradingConfirmations(page, { openOrders, closePosition } = {}) {
  if (typeof openOrders === "boolean") {
    await dispatch(page, [":actions/set-confirm-open-orders-enabled", openOrders]);
  }
  if (typeof closePosition === "boolean") {
    await dispatch(page, [":actions/set-confirm-close-position-enabled", closePosition]);
  }
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function seedNamedDexMarketForCancel(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const silverMarket = c.PersistentArrayMap.fromArray(
      [
        keyword("key"), "perp:xyz:SILVER",
        keyword("coin"), "xyz:SILVER",
        keyword("symbol"), "SILVER",
        keyword("base"), "SILVER",
        keyword("dex"), "xyz",
        keyword("market-type"), keyword("perp"),
        keyword("idx"), 4,
        keyword("asset-id"), 120088,
        keyword("szDecimals"), 2,
        keyword("maxLeverage"), 3
      ],
      true
    );
    const marketByKey = c.PersistentArrayMap.fromArray(
      ["perp:xyz:SILVER", silverMarket],
      true
    );

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, kwPath("asset-selector", "market-by-key"), marketByKey);
    c.reset_BANG_(store, nextState);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function installPasskeyLockboxMock(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const lockbox = globalThis.hyperopen?.wallet?.agent_lockbox;

    if (!c || !lockbox) {
      throw new Error("Hyperopen passkey lockbox namespace unavailable");
    }

    const keyword = c.keyword;
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const getValue = (value, key) => c.get(value, keyword(key));

    lockbox.create_locked_session_BANG_ = (args) => {
      const session = getValue(args, "session");
      const agentAddress = getValue(session, "agent-address");
      const privateKey = getValue(session, "private-key");
      const lastApprovedAt = getValue(session, "last-approved-at");
      const nonceCursor = getValue(session, "nonce-cursor");

      return Promise.resolve(
        c.js__GT_clj(
          {
            metadata: {
              "agent-address": agentAddress,
              "credential-id": "cred",
              "prf-salt": "salt",
              "last-approved-at": lastApprovedAt,
              "nonce-cursor": nonceCursor
            },
            session: {
              "agent-address": agentAddress,
              "private-key": privateKey,
              "last-approved-at": lastApprovedAt,
              "nonce-cursor": nonceCursor
            }
          },
          opts
        )
      );
    };

    lockbox.delete_locked_session_BANG_ = () => Promise.resolve(true);
  });
}

async function installPasskeyUnlockMock(page, options = {}) {
  const {
    privateKey = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  } = options;

  await page.evaluate(({ privateKey: nextPrivateKey }) => {
    const c = globalThis.cljs?.core;
    const lockbox = globalThis.hyperopen?.wallet?.agent_lockbox;

    if (!c || !lockbox) {
      throw new Error("Hyperopen passkey lockbox namespace unavailable");
    }

    const keyword = c.keyword;
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const getValue = (value, key) => c.get(value, keyword(key));

    lockbox.unlock_locked_session_BANG_ = (args) => {
      const metadata = getValue(args, "metadata");
      return Promise.resolve(
        c.js__GT_clj(
          {
            "agent-address": getValue(metadata, "agent-address"),
            "private-key": nextPrivateKey,
            "last-approved-at": getValue(metadata, "last-approved-at"),
            "nonce-cursor": getValue(metadata, "nonce-cursor")
          },
          opts
        )
      );
    };
  }, { privateKey });
}

async function seedBrowserTitleActiveAsset(page, mark) {
  await page.evaluate((nextMark) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const path = (...segments) => c.PersistentVector.fromArray(segments, true);
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const activeAsset = "xyz:SILVER";
    const market = c.PersistentArrayMap.fromArray(
      [
        keyword("key"), "perp:xyz:SILVER",
        keyword("coin"), activeAsset,
        keyword("symbol"), "SILVER",
        keyword("base"), "SILVER",
        keyword("dex"), "xyz",
        keyword("market-type"), keyword("perp")
      ],
      true
    );
    const context = c.js__GT_clj(
      {
        coin: activeAsset,
        mark: nextMark,
        markRaw: String(nextMark)
      },
      opts
    );
    let nextState = c.deref(store);

    nextState = c.assoc_in(nextState, kwPath("active-asset"), activeAsset);
    nextState = c.assoc_in(nextState, kwPath("selected-asset"), activeAsset);
    nextState = c.assoc_in(nextState, kwPath("active-market"), market);
    nextState = c.assoc_in(
      nextState,
      path(keyword("active-assets"), keyword("contexts"), activeAsset),
      context
    );
    c.reset_BANG_(store, nextState);
  }, mark);
}

test("browser title follows active asset mark updates @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await seedBrowserTitleActiveAsset(page, 82.65);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(page).toHaveTitle("82.65 | SILVER (xyz) | HyperOpen");

  await seedBrowserTitleActiveAsset(page, 82.66);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(page).toHaveTitle("82.66 | SILVER (xyz) | HyperOpen");
});

test("asset selector opens and selects ETH @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: "asset-selector",
    desktopPresent: true
  });

  await dispatch(page, [":actions/select-asset", "ETH"]);
  await waitForIdle(page, { quietMs: 300, timeoutMs: 7_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: null,
    activeAsset: "ETH"
  });
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/select-asset",
      covered: true,
      projectionBeforeHeavy: true,
      phaseOrderValid: true,
      duplicateHeavyEffectIds: []
    },
    { args: { actionId: ":actions/select-asset" } }
  );
});

test("asset selector outcome rows use full-width question copy without duplicate chip @regression", async ({ page }) => {
  await visitRoute(page, "/trade");
  await stubAssetSelectorMarketInfo(page);

  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await waitForAssetSelectorLoadSettled(page);
  await seedGroupedOutcomeAssetSelectorState(page, { activeTab: "outcome" });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const row = page
    .locator('[data-role="asset-selector-row"]')
    .filter({ hasText: "BTC price range on Jun 6 at 2:00 AM?" })
    .first();
  const question = row.locator(".truncate").first();

  await expect(row).toContainText("BTC price range on Jun 6 at 2:00 AM?");
  await expect(row).toContainText("61044 to 63535");
  await expect(row).toContainText(/\d+%/);
  await expect(row).not.toContainText("OUTCOME");
  await expect(question).not.toContainText("—");

  const textGeometry = await question.evaluate((node) => ({
    clientWidth: node.clientWidth,
    scrollWidth: node.scrollWidth
  }));
  expect(textGeometry.clientWidth).toBeGreaterThan(90);
});

test("asset selector outcome subtabs render grouped question markets @smoke @regression", async ({ page }) => {
  await visitRoute(page, "/trade");
  await stubAssetSelectorMarketInfo(page);
  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await waitForAssetSelectorLoadSettled(page);
  await seedGroupedOutcomeAssetSelectorState(page, { activeTab: "outcome" });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const rows = page.locator('[data-role="asset-selector-row"]');
  await expect(page.getByRole("button", { name: "Crypto (1d)", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Economics", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sports", exact: true })).toBeVisible();
  const cryptoRangeRow = rows.filter({ hasText: "BTC price range on Jun 6 at 2:00 AM?" }).first();
  await expect(cryptoRangeRow).toBeVisible();
  await expect(cryptoRangeRow).toContainText("61044 to 63535");

  await page.getByRole("button", { name: "Economics", exact: true }).click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await seedGroupedOutcomeAssetSelectorState(page);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(rows.first()).toContainText("May CPI year-over-year");
  await expect(rows.first()).toContainText("Below 4.3%");

  await page.getByRole("button", { name: "Sports", exact: true }).click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await seedGroupedOutcomeAssetSelectorState(page);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  const worldCupRow = rows.filter({ hasText: "2026 World Cup Champion" }).first();
  const nbaChampionRow = rows.filter({ hasText: "2026 NBA Finals champion" }).first();
  await expect(worldCupRow).toBeVisible();
  await expect(worldCupRow).toContainText(/France\s+\d+%/);
  await expect(worldCupRow).toContainText(/Spain\s+\d+%/);
  await expect(worldCupRow).toContainText(/\*\s+[A-Za-z][A-Za-z ]+\s+\d+%/);
  await expect(nbaChampionRow).toBeVisible();
  await expect(nbaChampionRow).toContainText("San Antonio 67%");
  await expect(nbaChampionRow).toContainText("New York 33%");
  await expect(rows.first()).not.toContainText("May CPI year-over-year");

  await page.getByRole("button", { name: "Crypto (1d)", exact: true }).click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await seedGroupedOutcomeAssetSelectorState(page);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(rows.first()).toContainText("BTC price range on Jun 6 at 2:00 AM?");
  await expect(rows.first()).not.toContainText("NBA Finals Game 2");
});

test("market strip uses searchable dropdown for multi-option outcome markets @smoke @regression", async ({ page }) => {
  await visitRoute(page, "/trade");
  await seedWorldCupOutcomeOrderForm(page);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const orderForm = page.locator('[data-parity-id="order-form"]');
  const marketStrip = page.locator('[data-parity-id="market-strip"]');
  const selector = marketStrip.locator('[data-role="market-strip-outcome-option-selector"]');
  const trigger = selector.locator('[data-role="outcome-option-select-trigger"]');
  const rows = selector.locator('[data-role="outcome-option-select-row"]');

  await expect(orderForm.locator('[data-role="outcome-option-select-trigger"]')).toHaveCount(0);
  await expect(selector).toBeVisible();
  await expect(trigger).toBeVisible();
  await expect(trigger).toContainText("France");
  await expect(selector.locator('[data-role="outcome-option-select-menu"]')).toHaveCount(0);
  await expect(rows).toHaveCount(0);

  await trigger.hover();
  await expect.poll(async () => {
    const tooltip = marketStrip.locator('[data-role="outcome-market-tooltip"]');
    if (await tooltip.count() === 0) {
      return "hidden";
    }
    const opacity = await tooltip.first().evaluate((node) => getComputedStyle(node).opacity);
    return opacity === "0" ? "hidden" : "visible";
  }, { timeout: 5_000 }).toBe("hidden");

  await trigger.click();
  const menu = selector.locator('[data-role="outcome-option-select-menu"]');
  await expect(menu).toBeVisible();
  await expect(menu).toHaveCSS("background-color", "rgb(6, 19, 26)");
  await expect(menu).toContainText("Live Outcomes");
  await expect(menu).toContainText("% Chance");
  await expect(menu).toContainText("Price");
  await expect(menu).toContainText("Volume");
  await expect(menu).toContainText("Open Int");
  await expect(rows).toHaveCount(48);
  await expect(rows.nth(0)).toContainText("Algeria");
  await expect(rows.nth(1)).toContainText("Argentina");
  await expect(rows.filter({ hasText: "France" })).toHaveCount(1);
  await expect(rows.filter({ hasText: "Spain" })).toHaveCount(1);
  await expect(menu.locator('[data-role="outcome-option-sort-label"]')).toBeVisible();
  await expect(menu.locator('[data-role="outcome-option-sort-chance"]')).toBeVisible();
  await expect(menu.locator('[data-role="outcome-option-sort-price"]')).toBeVisible();
  await expect(menu.locator('[data-role="outcome-option-sort-volume"]')).toBeVisible();
  await expect(menu.locator('[data-role="outcome-option-sort-open-interest"]')).toBeVisible();
  await expect(menu).toHaveCSS("background-color", "rgb(6, 19, 26)");
  const menuBox = await menu.boundingBox();
  expect(menuBox?.width).toBeGreaterThan(560);
  const layoutProbe = await rows.first().evaluate((row) => {
    const rowRect = row.getBoundingClientRect();
    const cellRects = Array.from(row.children).map((child) => child.getBoundingClientRect());
    return {
      templateColumns: getComputedStyle(row).gridTemplateColumns,
      rowHeight: rowRect.height,
      cellCount: cellRects.length,
      maxTopDelta: Math.max(...cellRects.map((rect) => Math.abs(rect.top - cellRects[0].top))),
      columnsIncrease: cellRects.every((rect, index) => index === 0 || rect.left > cellRects[index - 1].left)
    };
  });
  expect(layoutProbe.cellCount).toBe(5);
  expect(layoutProbe.templateColumns.split(" ").length).toBe(5);
  expect(layoutProbe.rowHeight).toBeLessThan(40);
  expect(layoutProbe.maxTopDelta).toBeLessThan(2);
  expect(layoutProbe.columnsIncrease).toBe(true);

  await menu.locator('[data-role="outcome-option-sort-volume"]').click();
  await expect(rows.first()).not.toContainText("Algeria");
  await expect(rows.first()).toContainText(/\d+%/);
  await menu.locator('[data-role="outcome-option-sort-label"]').click();
  await expect(rows.first()).toContainText("Algeria");

  await menu.getByRole("searchbox", { name: "Search outcome options" }).fill("sp");
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText("Spain");
  await rows.first().click();
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

  await expect(trigger).toContainText("Spain");
  await expect(menu).toHaveCount(0);
  await expect.poll(async () => {
    return page.evaluate(() => {
      const c = globalThis.cljs?.core;
      const store = globalThis.hyperopen?.system?.store;
      if (!c || !store) {
        throw new Error("Hyperopen store unavailable");
      }
      const kw = (name) => c.keyword(name);
      const path = (...segments) => c.PersistentVector.fromArray(segments.map(kw), true);
      const state = c.deref(store);
      return {
        route: c.get_in(state, path("router", "path")),
        activeAsset: c.get(state, kw("active-asset")),
        selectedAsset: c.get(state, kw("selected-asset")),
        outcomeOptionId: c.get_in(state, path("order-form", "outcome-option-id"))
      };
    });
  }, { timeout: 6_000 }).toEqual({
    route: "/trade/%232120",
    activeAsset: "#2120",
    selectedAsset: "#2120",
    outcomeOptionId: 212
  });
  await expect(page.getByRole("region", { name: "#2120 price chart, 1D timeframe" })).toBeVisible();
});

test("two-sided outcome side selector switches chart and order book market @regression", async ({ page }) => {
  await visitRoute(page, "/trade");
  await seedSportsOutcomeOrderFormUntilReady(page);

  const orderForm = page.locator('[data-parity-id="order-form"]');
  await expect(orderForm.getByRole("button", { name: "Buy San Antonio", exact: true })).toBeVisible();
  await expect(orderForm.getByRole("button", { name: "Buy New York", exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "#1420 price chart, 1D timeframe" })).toBeVisible();
  await expect(page.locator('[data-parity-id="orderbook-panel"]')).toContainText("0.3724");

  await orderForm.getByRole("button", { name: "Buy New York", exact: true }).click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 6_000, pollMs: 50 });
  await seedOutcomeSideOrderbook(page, { coin: "#1421", bidPx: "0.61760", askPx: "0.62851" });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(page.getByRole("region", { name: "#1421 price chart, 1D timeframe" })).toBeVisible();
  await expect(page.locator('[data-parity-id="orderbook-panel"]')).toContainText("0.6176");
  await expect(page.locator('[data-parity-id="orderbook-panel"]')).not.toContainText("0.3724");
  await expect.poll(async () => {
    return page.evaluate(() => {
      const c = globalThis.cljs?.core;
      const store = globalThis.hyperopen?.system?.store;
      if (!c || !store) {
        throw new Error("Hyperopen store unavailable");
      }
      const kw = (name) => c.keyword(name);
      const path = (...segments) => c.PersistentVector.fromArray(segments.map(kw), true);
      const state = c.deref(store);
      return {
        activeAsset: c.get(state, kw("active-asset")),
        selectedAsset: c.get(state, kw("selected-asset")),
        outcomeSide: c.get_in(state, path("order-form", "outcome-side"))
      };
    });
  }, { timeout: 6_000 }).toEqual({
    activeAsset: "#1421",
    selectedAsset: "#1421",
    outcomeSide: 1
  });
});

test("outcome market tooltip uses adaptive readable width and glows on hover @regression", async ({ page }) => {
  await visitRoute(page, "/trade");
  await seedOutcomeActiveAsset(page);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });

  const hoverRegion = page.locator('[data-role="outcome-market-name-hover-region"]');
  const trigger = hoverRegion.locator("button").first();
  const tooltip = page.locator('[data-role="outcome-market-tooltip"]');

  await expect.poll(async () => {
    const count = await hoverRegion.count();
    if (count !== 1) {
      await seedOutcomeActiveAsset(page);
      await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });
      return hoverRegion.count();
    }
    return count;
  }, { timeout: 8_000 }).toBe(1);
  await hoverRegion.hover();
  await expect.poll(async () => {
    return tooltip.evaluate((node) => Number(getComputedStyle(node).opacity));
  }, { timeout: 5_000 }).toBeGreaterThan(0.9);

  const geometry = await page.evaluate(() => {
    const region = document.querySelector('[data-role="outcome-market-name-hover-region"]');
    const trigger = region?.querySelector("button");
    const panel = document.querySelector('[data-role="outcome-market-tooltip"]');
    if (!region || !trigger || !panel) {
      throw new Error("Outcome tooltip geometry unavailable");
    }
    const regionRect = region.getBoundingClientRect();
    const triggerRect = trigger.getBoundingClientRect();
    const panelRect = panel.getBoundingClientRect();
    return {
      regionLeft: regionRect.left,
      regionRight: regionRect.right,
      regionWidth: regionRect.width,
      triggerLeft: triggerRect.left,
      triggerRight: triggerRect.right,
      triggerWidth: triggerRect.width,
      panelLeft: panelRect.left,
      panelRight: panelRect.right,
      panelWidth: panelRect.width,
      viewportWidth: window.innerWidth
    };
  });

  expect(Math.abs(geometry.panelLeft - geometry.triggerLeft)).toBeLessThanOrEqual(1);
  expect(geometry.panelWidth).toBeGreaterThan(geometry.triggerWidth + 240);
  expect(geometry.panelRight).toBeLessThanOrEqual(geometry.viewportWidth - 16 + 1);

  const triggerGlow = await trigger.evaluate((node) => {
    const style = getComputedStyle(node);
    return {
      borderColor: style.borderTopColor,
      boxShadow: style.boxShadow
    };
  });

  expect(triggerGlow.borderColor).toContain("45, 212, 191");
  expect(triggerGlow.boxShadow).toContain("45, 212, 191");

  const settlementLabel = tooltip.locator('[data-role="outcome-tooltip-settlement-label"]');
  await expect(settlementLabel).toHaveCSS("white-space", "normal");
  await expect(settlementLabel).toContainText("BTC mark price is above 78,213");
  await expect(tooltip.getByText("on May 03, 2026 02:00 AM UTC")).toBeVisible();
  await expect(tooltip.getByText("Payouts are in USDH.")).toBeVisible();
  await expect(tooltip.getByText("Learn more")).toHaveCount(0);
});

test("outcome market tooltip scrolls long outcome details without becoming narrow @regression", async ({ page }) => {
  const longDetailsSection = [
    "Each associated outcome corresponds to a team confirmed to be participating in the 2026 FIFA World Cup.",
    "An outcome resolves to Yes if FIFA officially declares the corresponding team the champion of the 2026 FIFA World Cup.",
    "An outcome resolves to No once it becomes impossible under FIFA tournament rules for the corresponding team to win the 2026 FIFA World Cup, including but not limited to upon elimination from the tournament.",
    "Match results after regular time, extra time, and penalties, if applicable, are all valid for resolution purposes.",
    "If the final is postponed or delayed, the rescheduled final will be used, provided FIFA officially declares a champion by October 14, 2026 at 23:59 UTC.",
    "If FIFA officially declares a team as champion without a completed final match, including but not limited to following abandonment, walkover, forfeit, disqualification, or administrative decision, that team's outcome resolves to Yes accordingly.",
    "Any outcome not already resolved shall resolve to No if FIFA cancels the 2026 FIFA World Cup, declares no champion, declares any teams as co-champions, or has not officially declared a champion by October 14, 2026 at 23:59 UTC.",
    "FIFA is the primary resolution source, although independent reputable news sources may be used as fallback sources if FIFA has not published the relevant result.",
    "Once resolved, subsequent appeals, corrections, reversals, or title reassignments by FIFA or any other body will not affect the resolution."
  ].join(" ");
  const longDetails = Array.from({ length: 3 }, () => longDetailsSection).join(" ");

  await visitRoute(page, "/trade");
  await seedOutcomeActiveAsset(page, {
    coin: "#1890",
    symbol: "2026 World Cup Champion",
    title: "2026 World Cup Champion",
    base: "World Cup",
    underlying: null,
    "target-price": null,
    "expiry-ms": null,
    "outcome-details": longDetails
  });
  await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });

  const hoverRegion = page.locator('[data-role="outcome-market-name-hover-region"]');
  const trigger = hoverRegion.locator("button").first();
  const tooltip = page.locator('[data-role="outcome-market-tooltip"]');
  const summary = tooltip.locator('[data-role="outcome-tooltip-summary-scroll"]');
  const scrollContainer = tooltip.locator('[data-role="outcome-tooltip-scroll-container"]');

  await expect.poll(async () => {
    const count = await hoverRegion.count();
    if (count !== 1) {
      await seedOutcomeActiveAsset(page, {
        coin: "#1890",
        symbol: "2026 World Cup Champion",
        title: "2026 World Cup Champion",
        base: "World Cup",
        underlying: null,
        "target-price": null,
        "expiry-ms": null,
        "outcome-details": longDetails
      });
      await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });
      return hoverRegion.count();
    }
    return count;
  }, { timeout: 8_000 }).toBe(1);
  await hoverRegion.hover();
  await expect.poll(async () => {
    return tooltip.evaluate((node) => Number(getComputedStyle(node).opacity));
  }, { timeout: 5_000 }).toBeGreaterThan(0.9);
  await expect(summary).toContainText("Each associated outcome corresponds");
  await expect(tooltip.getByText("Settlement Condition")).toHaveCount(0);

  const geometry = await page.evaluate(() => {
    const trigger = document.querySelector('[data-role="outcome-market-name-hover-region"] button');
    const panel = document.querySelector('[data-role="outcome-market-tooltip"]');
    const summary = document.querySelector('[data-role="outcome-tooltip-summary-scroll"]');
    const scrollContainer = document.querySelector('[data-role="outcome-tooltip-scroll-container"]');
    const footer = document.querySelector('[data-role="outcome-tooltip-shield-icon"]')?.parentElement;
    if (!trigger || !panel || !summary || !scrollContainer || !footer) {
      throw new Error("Long outcome tooltip geometry unavailable");
    }
    const triggerRect = trigger.getBoundingClientRect();
    const panelRect = panel.getBoundingClientRect();
    const summaryStyle = getComputedStyle(summary);
    const scrollStyle = getComputedStyle(scrollContainer);
    scrollContainer.scrollTop = scrollContainer.scrollHeight;
    const footerRect = footer.getBoundingClientRect();
    return {
      triggerWidth: triggerRect.width,
      panelWidth: panelRect.width,
      panelRight: panelRect.right,
      panelBottom: panelRect.bottom,
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight,
      summaryClientHeight: summary.clientHeight,
      summaryScrollHeight: summary.scrollHeight,
      summaryOverflowY: summaryStyle.overflowY,
      scrollClientHeight: scrollContainer.clientHeight,
      scrollScrollHeight: scrollContainer.scrollHeight,
      scrollOverflowY: scrollStyle.overflowY,
      footerBottom: footerRect.bottom
    };
  });

  expect(geometry.panelWidth).toBeGreaterThan(geometry.triggerWidth + 240);
  expect(geometry.panelRight).toBeLessThanOrEqual(geometry.viewportWidth - 16 + 1);
  expect(geometry.panelBottom).toBeLessThanOrEqual(geometry.viewportHeight);
  expect(geometry.summaryOverflowY).toBe("visible");
  expect(geometry.summaryScrollHeight).toBeLessThanOrEqual(geometry.summaryClientHeight + 1);
  expect(geometry.scrollOverflowY).toBe("auto");
  expect(geometry.scrollScrollHeight).toBeGreaterThan(geometry.scrollClientHeight);
  expect(geometry.footerBottom).toBeLessThanOrEqual(geometry.panelBottom);
  await expect(scrollContainer).toContainText("Payouts are in USDH.");
});

test("disconnected stop spectate clears stale account surfaces @regression", async ({ page }) => {
  await visitRoute(page, "/trade");
  await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await seedDisconnectedSpectateAccountState(page);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });

  await expect(page.locator("[data-role='spectate-mode-active-banner']")).toBeVisible();
  await expect.poll(() => new URL(page.url()).searchParams.get("spectate")).toBe(SPECTATE_ADDRESS);
  await expect.poll(() => readSpectateLifecycleProbe(page)).toMatchObject({
    spectateActive: true,
    spectateAddress: SPECTATE_ADDRESS,
    webdata2Present: true,
    spotClearinghousePresent: true
  });

  await page.locator("[data-role='spectate-mode-banner-stop']").click();
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

  await expect.poll(() => new URL(page.url()).searchParams.get("spectate")).toBe(null);

  await selectAccountTab(page, "balances");
  await expect(page.getByText("No balance data available")).toBeVisible();

  await selectAccountTab(page, "positions");
  await expect(page.getByText("No active positions")).toBeVisible();

  await selectAccountTab(page, "open-orders");
  await expect(page.getByText("No open orders")).toBeVisible();
});

test("positions margin column leaves funding value readable at compact desktop width @regression", async ({ page }) => {
  await page.setViewportSize({ width: 1365, height: 768 });
  await visitRoute(page, "/trade");
  await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
  await waitForIdle(page, { quietMs: 800, timeoutMs: 12_000, pollMs: 50 });
  await freezeAccountSurfaceSync(page, SPECTATE_ADDRESS);
  await selectAccountTab(page, "positions");
  const positions = [
    {
      position: {
        coin: "MON",
        szi: "-142084.0",
        positionValue: "483.46",
        entryPx: "0.03452",
        markPx: "0.034262",
        unrealizedPnl: "-13.14",
        returnOnEquity: "-0.001",
        liquidationPx: "0.0415",
        marginUsed: "16204.70",
        leverage: { value: 3, type: "isolated" },
        cumFunding: { sinceOpen: "-0.94", sinceChange: "-0.94", allTime: "-0.94" }
      }
    },
    {
      position: {
        coin: "MET",
        szi: "-52324.0",
        positionValue: "7933.36",
        entryPx: "0.149893",
        markPx: "0.15162",
        unrealizedPnl: "-90.32",
        returnOnEquity: "-0.035",
        liquidationPx: "0.1713",
        marginUsed: "2524.25",
        leverage: { value: 3, type: "isolated" },
        cumFunding: { sinceOpen: "-0.98", sinceChange: "-0.98", allTime: "-0.98" }
      }
    }
  ];
  const monRow = await seedDesktopPositionsUntilVisible(page, positions, "MON");
  await expect(monRow.locator(":scope > div").nth(7)).toContainText("$16,204.70");
  await expect(monRow.locator(":scope > div").nth(8)).toContainText("$0.94");

  const spacing = await monRow.evaluate((row) => {
    const marginCell = row.children[7];
    const fundingCell = row.children[8];
    const marginContent = marginCell?.querySelector(":scope > div") ?? marginCell;
    const fundingValue = fundingCell?.querySelector("span.num") ?? fundingCell;

    if (!marginCell || !fundingCell || !marginContent || !fundingValue) {
      throw new Error("positions margin/funding cells missing");
    }

    const marginRect = marginContent.getBoundingClientRect();
    const fundingRect = fundingValue.getBoundingClientRect();

    return {
      marginRight: marginRect.right,
      fundingLeft: fundingRect.left,
      gapPx: fundingRect.left - marginRect.right
    };
  });

  expect(spacing.gapPx).toBeGreaterThanOrEqual(4);
});

test("desktop trade shell keeps the chart dominant while account tabs stay geometry-stable @regression", async ({
  page
}) => {
  const reviewViewports = [
    { width: 1280, height: 800 },
    { width: 1440, height: 900 }
  ];
  const standardTabs = [
    "balances",
    "positions",
    "open-orders",
    "twap",
    "trade-history",
    "funding-history",
    "order-history"
  ];

  for (const viewport of reviewViewports) {
    await page.setViewportSize(viewport);
    await visitRoute(page, "/trade");

    let baselineGeometry = null;
    for (const tab of standardTabs) {
      await selectAccountTab(page, tab);
      const geometry = await readTradeShellGeometry(page);

      expect(Math.abs(geometry.chartFlushDelta)).toBeLessThanOrEqual(1);
      expect(Math.abs(geometry.orderbookFlushDelta)).toBeLessThanOrEqual(1);
      expect(geometry.lowerPanelShare).toBeLessThan(0.4);

      if (!baselineGeometry) {
        baselineGeometry = geometry;
        continue;
      }

      expect(Math.abs(geometry.accountHeight - baselineGeometry.accountHeight)).toBeLessThanOrEqual(1);
      expect(Math.abs(geometry.accountWidth - baselineGeometry.accountWidth)).toBeLessThanOrEqual(1);
    }
  }
});

test("desktop trade chart does not clip under zoom-equivalent viewports @regression", async ({
  page
}) => {
  const reviewViewports = [
    { width: 1440, height: 900 },
    { width: 1280, height: 800 },
    { width: 1285, height: 535 },
    { width: 1102, height: 459 }
  ];
  const standardTabs = [
    "balances",
    "positions",
    "open-orders",
    "twap",
    "trade-history",
    "funding-history",
    "order-history"
  ];

  for (const viewport of reviewViewports) {
    const viewportLabel = `${viewport.width}x${viewport.height}`;
    const expectsSidecarFlush = viewport.width >= 1280;
    await page.setViewportSize(viewport);
    await visitRoute(page, "/trade");

    const chartCanvas = page.locator('[data-parity-id="chart-canvas"]');
    const chartLibrary = chartCanvas.locator(".tv-lightweight-charts");

    await expect(chartCanvas, `${viewportLabel} should render the trade chart canvas`).toBeVisible();
    await expect(chartLibrary, `${viewportLabel} should mount the chart library host`).toBeVisible();
    await waitForIdle(page, { quietMs: 250, timeoutMs: 7_000, pollMs: 50 });

    let baselineGeometry = null;

    for (const tab of standardTabs) {
      await selectAccountTab(page, tab);
      const geometry = await readTradeShellGeometry(page);

      expect(
        Math.abs(geometry.chartLibraryBottomMinusHostBottom),
        `${viewportLabel} ${tab} chart library host should stay inside the chart canvas`
      ).toBeLessThanOrEqual(1);
      expect(
        geometry.chartPanelBottomMinusChartCanvasBottom,
        `${viewportLabel} ${tab} chart canvas should stay inside the chart panel`
      ).toBeGreaterThanOrEqual(0);
      expect(
        geometry.accountTopMinusChartCanvasBottom,
        `${viewportLabel} ${tab} account panel should not cover the chart canvas`
      ).toBeGreaterThanOrEqual(0);
      expect(
        Math.abs(geometry.chartFlushDelta),
        `${viewportLabel} ${tab} chart panel should stay flush with the account panel`
      ).toBeLessThanOrEqual(1);
      if (expectsSidecarFlush) {
        expect(
          Math.abs(geometry.orderbookFlushDelta),
          `${viewportLabel} ${tab} order book should stay flush with the account panel`
        ).toBeLessThanOrEqual(1);
      }

      if (viewport.height <= 535) {
        expect(
          geometry.scrollShellCanScroll,
          `${viewportLabel} ${tab} short desktop shells should hand overflow to the outer scroll shell`
        ).toBe(true);
      }

      if (!baselineGeometry) {
        baselineGeometry = geometry;
        continue;
      }

      expect(
        Math.abs(geometry.accountHeight - baselineGeometry.accountHeight),
        `${viewportLabel} ${tab} account tab changes should not resize the account panel height`
      ).toBeLessThanOrEqual(1);
      expect(
        Math.abs(geometry.accountWidth - baselineGeometry.accountWidth),
        `${viewportLabel} ${tab} account tab changes should not resize the account panel width`
      ).toBeLessThanOrEqual(1);
    }
  }
});

test("named-dex close-position popover loads full market metadata before submit @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");
  await freezeAccountSurfaceSync(page, "0x1111111111111111111111111111111111111111");
  await seedReadyTradingSession(page);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await selectAccountTab(page, "positions");
  const positions = [
    {
      position: {
        coin: "xyz:BRENTOIL",
        szi: "1.31",
        positionValue: "127.47",
        entryPx: "95.5805",
        markPx: "97.32",
        unrealizedPnl: "2.26",
        returnOnEquity: "0.36",
        liquidationPx: "73.584",
        marginUsed: "33.48",
        leverage: { value: 20, type: "isolated" },
        cumFunding: { sinceOpen: "4.55", sinceChange: "4.55", allTime: "4.55" }
      }
    }
  ];
  const brentRow = await seedDesktopPositionsUntilVisible(page, positions, "BRENTOIL");
  await forceAssetSelectorBootstrapState(page);
  await brentRow.locator("[data-position-reduce-trigger='true']").click();
  const reduceSurface = page.locator("[data-position-reduce-surface='true']");
  await expect(reduceSurface).toBeVisible();
  await expect(reduceSurface.getByRole("heading", { name: "Close Position" })).toBeVisible();

  const trace = await oracle(page, "effect-order", {
    actionId: ":actions/open-position-reduce-popover"
  });
  expect(trace).toMatchObject({
    covered: true,
    projectionBeforeHeavy: true,
    phaseOrderValid: true
  });
  expect(trace.effectIds).toEqual(
    expect.arrayContaining([
      ":effects/save-many",
      ":effects/fetch-asset-selector-markets"
    ])
  );
});

test("asset selector focuses search input and keyboard-navigates rows @regression", async ({ page }) => {
  await seedAssetSelectorMarketsCache(page);
  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: "asset-selector",
    desktopPresent: true
  });

  const searchInput = page.locator('[aria-label="Search assets"]');
  const assetRows = page.locator('[data-role="asset-selector-row"]');
  const highlightedRow = () =>
    page.locator('[data-role="asset-selector-row"][data-row-state="highlighted"]').first();
  const highlightedSymbol = async () =>
    (await highlightedRow().locator(".truncate").first().textContent())?.trim();
  const firstRowSymbol = async () =>
    (await assetRows.first().locator(".truncate").first().textContent())?.trim();

  await expect(searchInput).toBeFocused();
  await expect.poll(async () => await assetRows.count(), { timeout: 10_000 }).toBeGreaterThan(1);
  const initialRowSymbol = await firstRowSymbol();

  await page.keyboard.press("ArrowDown");
  await expect(highlightedRow()).toBeVisible();
  await expect.poll(highlightedSymbol, { timeout: 5_000 }).not.toBe(initialRowSymbol);
  const firstHighlightedSymbol = await highlightedSymbol();

  await page.keyboard.press("ArrowDown");
  await expect(highlightedRow()).toBeVisible();
  const secondHighlightedSymbol = await highlightedSymbol();
  expect(secondHighlightedSymbol).not.toEqual(firstHighlightedSymbol);

  await page.keyboard.press("ArrowUp");
  await expect
    .poll(highlightedSymbol, {
      timeout: 5_000
    })
    .toBe(firstHighlightedSymbol);
  const selectedAsset = await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const state = c.deref(store);
    const highlightedMarketKey = c.get_in(state, kwPath("asset-selector", "highlighted-market-key"));

    return highlightedMarketKey
      ? String(highlightedMarketKey).replace(/^[^:]+:/, "")
      : null;
  });

  await page.keyboard.press("Enter");
  await waitForIdle(page, { quietMs: 300, timeoutMs: 7_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: null,
    activeAsset: selectedAsset
  });
});

test("trade route preserves core accessibility affordances @regression", async ({ page }) => {
  await page.goto("/trade", { waitUntil: "commit" });

  await expect(page.locator('[data-parity-id="trade-root"]')).toBeVisible();
  await expect(page.locator('main[data-parity-id="app-main"]')).toHaveCount(1);
  await expect(page.getByRole("button", { name: "Connect Wallet" })).toBeVisible();

  const midButton = page.getByRole("button", { name: "Set order price to mid" });
  await expect(midButton).toBeVisible();
  const midButtonBox = await midButton.boundingBox();
  expect(midButtonBox?.width ?? 0).toBeGreaterThanOrEqual(24);
  expect(midButtonBox?.height ?? 0).toBeGreaterThanOrEqual(24);

  await expect(
    page.getByRole("slider", { name: "Order size percentage slider" })
  ).toBeVisible();
  await expect(
    page.getByRole("textbox", { name: "Order size percentage input" })
  ).toBeVisible();

  await expect(
    page.locator('button[aria-haspopup="listbox"]').filter({ hasText: "Cross" }).first()
  ).toHaveAttribute("aria-label", "Margin mode: Cross");
  await expect(
    page.locator('button[aria-haspopup="listbox"]').filter({ hasText: "USDC" }).first()
  ).toHaveAttribute("aria-label", "Size unit: USDC");
  await expect(
    page.locator('button[aria-haspopup="listbox"]').filter({ hasText: "GTC" }).first()
  ).toHaveAttribute("aria-label", "Time in force: GTC");
});

test("trade chart context menu supports pointer and keyboard flows @regression @smoke", async ({ page }) => {
  await page.addInitScript(() => {
    globalThis.__chartContextMenuClipboardWrites = [];
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        writeText(text) {
          globalThis.__chartContextMenuClipboardWrites.push(text);
          return Promise.resolve();
        },
      },
    });
  });

  await page.goto("/trade", { waitUntil: "commit" });
  await expect(page.locator('[data-parity-id="trade-root"]')).toBeVisible();
  await expect
    .poll(
      () =>
        page.evaluate(
          () =>
            Boolean(
              globalThis.hyperopen?.system?.store &&
                typeof globalThis.hyperopen?.trade_modules?.load_trade_chart_module_BANG_ === "function"
            )
        ),
      { timeout: 20_000 }
    )
    .toBe(true);
  await page.evaluate(async () => {
    const store = globalThis.hyperopen?.system?.store;
    const tradeModules = globalThis.hyperopen?.trade_modules;

    if (!store || typeof tradeModules?.load_trade_chart_module_BANG_ !== "function") {
      throw new Error("Trade chart module loader unavailable");
    }

    await tradeModules.load_trade_chart_module_BANG_(store);
  });
  await expect(page.getByText("Loading Chart")).toBeHidden({ timeout: 20_000 });
  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const vectorPath = (...segments) => c.PersistentVector.fromArray(segments, true);
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const rawCandles = Array.from({ length: 80 }, (_, index) => {
      const base = 67_000 + index * 8;
      return {
        t: 1_700_000_000_000 + index * 86_400_000,
        o: String(base),
        h: String(base + 120),
        l: String(base - 90),
        c: String(base + 42),
        v: String(1_000 + index)
      };
    });
    const activeMarket = c.PersistentArrayMap.fromArray(
      [
        keyword("key"), "perp:BTC",
        keyword("coin"), "BTC",
        keyword("symbol"), "BTC",
        keyword("market-type"), keyword("perp"),
        keyword("price-decimals"), 2,
        keyword("markRaw"), "67674"
      ],
      true
    );

    let nextState = c.deref(store);
    nextState = c.assoc(nextState, keyword("active-asset"), "BTC");
    nextState = c.assoc(nextState, keyword("active-market"), activeMarket);
    nextState = c.assoc_in(nextState, vectorPath(keyword("chart-options"), keyword("selected-timeframe")), keyword("1d"));
    nextState = c.assoc_in(nextState, vectorPath(keyword("candles"), "BTC", keyword("1d")), c.js__GT_clj(rawCandles, opts));
    c.reset_BANG_(store, nextState);
  });
  await waitForIdle(page, { quietMs: 300, timeoutMs: 7_000, pollMs: 50 });

  const chartCanvas = page.locator('[data-parity-id="chart-canvas"]');
  await expect(chartCanvas).toBeVisible();

  const chartBox = await chartCanvas.boundingBox();
  if (!chartBox) {
    throw new Error("Trading chart canvas bounding box unavailable");
  }

  const contextMenu = page.locator('[data-role="chart-context-menu"]');
  const resetItem = page.locator('[data-role="chart-context-menu-reset"]');
  const copyItem = page.locator('[data-role="chart-context-menu-copy"]');
  const openMenuAtChartCenter = async () => {
    await chartCanvas.click({
      button: "right",
      position: {
        x: Math.max(24, Math.floor(chartBox.width / 2)),
        y: Math.max(24, Math.floor(chartBox.height / 2))
      }
    });
    await expect(contextMenu).toBeVisible();
    await expect(contextMenu).toHaveCount(1);
  };

  await openMenuAtChartCenter();
  await expect(contextMenu).toBeVisible();
  await expect(resetItem).toHaveText("Reset chart view");
  await expect(copyItem).toContainText("Copy price");
  await expect(copyItem).toBeEnabled();

  const copyLabel = (await copyItem.textContent())?.replace(/^Copy price\s+/, "") ?? "";
  expect(copyLabel).not.toBe("");
  await copyItem.click();
  await expect(copyItem).toHaveText("Copied");
  await expect
    .poll(() => page.evaluate(() => globalThis.__chartContextMenuClipboardWrites?.[0] || ""))
    .toBe(copyLabel);
  await expect(contextMenu).toBeHidden({ timeout: 3_000 });
  await expect(chartCanvas).toBeFocused();

  await openMenuAtChartCenter();

  await page.keyboard.press("Escape");
  await expect(contextMenu).toBeHidden();
  await expect(chartCanvas).toBeFocused();

  await chartCanvas.focus();
  await page.keyboard.press("Shift+F10");
  await expect(contextMenu).toBeVisible();
  await expect(resetItem).toBeFocused();
  await expect(copyItem).toBeVisible();
  await page.keyboard.press("ArrowDown");
  await expect
    .poll(async () => {
      if (await copyItem.isDisabled()) {
        return await resetItem.evaluate(node => node === document.activeElement ? "reset" : "other");
      }

      return await copyItem.evaluate(node => node === document.activeElement ? "copy" : "other");
    })
    .toMatch(/reset|copy/);

  await resetItem.click();
  await expect(contextMenu).toBeHidden();
  await expect(chartCanvas).toBeFocused();

  for (let index = 0; index < 3; index += 1) {
    await openMenuAtChartCenter();
    await page.keyboard.press("Escape");
    await expect(contextMenu).toBeHidden();
  }

  await openMenuAtChartCenter();
  await page.mouse.click(Math.max(4, chartBox.x - 2), Math.max(4, chartBox.y - 2));
  await expect(contextMenu).toBeHidden();
  await expect(contextMenu).toHaveCount(1);
});

test("active asset icon promotes BTC into loaded-icons after probe load @regression", async ({ page }) => {
  await page.route("https://app.hyperliquid.xyz/coins/BTC.svg", async route => {
    await route.fulfill({
      status: 200,
      contentType: "image/svg+xml",
      body: "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\"><rect width=\"16\" height=\"16\" fill=\"#f7931a\"/></svg>"
    });
  });

  await visitRoute(page, "/trade");

  await expect
    .poll(async () => {
      const snapshot = await debugCall(page, "snapshot");
      return snapshot["app-state"]?.["asset-selector"]?.["loaded-icons"] || [];
    }, { timeout: 10_000 })
    .toContain("perp:BTC");

  await expect
    .poll(async () => {
      const snapshot = await debugCall(page, "snapshot");
      return snapshot["app-state"]?.["asset-selector"]?.["missing-icons"] || [];
    }, { timeout: 10_000 })
    .toEqual([]);
});

test("vault detail 429 retries stop after returning to trade @regression", async ({ page }) => {
  const vaultAddress = "0x61b1cf5c2d7c4bf6d5db14f36651b2242e7cba0a";
  let vaultDetailsRequests = 0;
  let vaultWebDataRequests = 0;

  await page.route("https://api.hyperliquid.xyz/info", async route => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;
    const requestVaultAddress = String(
      payload?.vaultAddress || payload?.user || ""
    ).toLowerCase();

    if (
      requestType === "vaultDetails" &&
      requestVaultAddress === vaultAddress
    ) {
      vaultDetailsRequests += 1;
      await route.fulfill({
        status: 429,
        contentType: "application/json",
        body: JSON.stringify({ error: "rate-limited" })
      });
      return;
    }

    if (
      requestType === "webData2" &&
      requestVaultAddress === vaultAddress
    ) {
      vaultWebDataRequests += 1;
      await route.fulfill({
        status: 429,
        contentType: "application/json",
        body: JSON.stringify({ error: "rate-limited" })
      });
      return;
    }

    await route.continue();
  });

  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/navigate", "/vaults", { "replace?": true }]);
  await waitForIdle(page);
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "vaults-root" } }
  );

  await dispatch(page, [
    ":actions/navigate",
    `/vaults/${vaultAddress}`,
    { "replace?": true }
  ]);

  await expect
    .poll(() => vaultDetailsRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);
  await expect
    .poll(() => vaultWebDataRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);

  await dispatch(page, [":actions/navigate", "/trade", { "replace?": true }]);
  await waitForIdle(page);
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "trade-root" } }
  );

  const detailsRequestsAfterLeave = vaultDetailsRequests;
  const webDataRequestsAfterLeave = vaultWebDataRequests;

  await page.waitForTimeout(1200);

  await expect(vaultDetailsRequests).toBe(detailsRequestsAfterLeave);
  await expect(vaultWebDataRequests).toBe(webDataRequestsAfterLeave);
});

test("vault detail hero TVL bootstraps from list metadata when vaultDetails omits tvl @regression", async ({ page }) => {
  const vaultAddress = "0x61b1cf5c2d7c4bf6d5db14f36651b2242e7cba0a";
  let vaultIndexRequests = 0;
  let vaultSummariesRequests = 0;

  await page.route("https://stats-data.hyperliquid.xyz/Mainnet/vaults", async route => {
    vaultIndexRequests += 1;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          apr: "0.12",
          summary: {
            name: "OnlyShorts",
            vaultAddress,
            leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            tvl: "321.5",
            isClosed: false,
            relationship: { type: "normal" },
            createTimeMillis: "1700"
          }
        }
      ])
    });
  });

  await page.route("https://api.hyperliquid.xyz/info", async route => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;
    const requestVaultAddress = String(
      payload?.vaultAddress || payload?.user || ""
    ).toLowerCase();

    if (requestType === "vaultSummaries") {
      vaultSummariesRequests += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([])
      });
      return;
    }

    if (
      requestType === "vaultDetails" &&
      requestVaultAddress === vaultAddress
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          name: "OnlyShorts",
          vaultAddress,
          leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
          description: "Regression fixture",
          apr: "0.12",
          portfolio: [
            [
              "month",
              {
                accountValueHistory: [
                  [1, 100],
                  [2, 110]
                ],
                pnlHistory: [
                  [1, 0],
                  [2, 10]
                ]
              }
            ]
          ],
          followers: [],
          relationship: { type: "normal" },
          allowDeposits: false,
          alwaysCloseOnWithdraw: false
        })
      });
      return;
    }

    if (
      requestType === "webData2" &&
      requestVaultAddress === vaultAddress
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({})
      });
      return;
    }

    await route.continue();
  });

  await visitRoute(page, `/vaults/${vaultAddress}`);
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "vault-detail-root" } }
  );

  await expect
    .poll(() => vaultIndexRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);
  await expect
    .poll(() => vaultSummariesRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);
  await expect(page.getByRole("heading", { name: "OnlyShorts" })).toBeVisible();
  const tvlCard = page
    .getByText("TVL", { exact: true })
    .locator("xpath=ancestor::div[contains(@class, 'rounded-xl')][1]");
  await expect(tvlCard).toContainText("$321.50");
  await expect(tvlCard).not.toContainText("$0.00");
});

test("vault startup preview row click reuses inflight list bootstrap @regression", async ({ page }) => {
  const vaultAddress = "0x61b1cf5c2d7c4bf6d5db14f36651b2242e7cba0a";
  let releaseIndex;
  let releaseSummaries;
  let vaultIndexRequests = 0;
  let vaultSummariesRequests = 0;
  let vaultDetailsRequests = 0;
  let vaultWebDataRequests = 0;
  const indexGate = new Promise(resolve => {
    releaseIndex = resolve;
  });
  const summariesGate = new Promise(resolve => {
    releaseSummaries = resolve;
  });

  await page.route("https://stats-data.hyperliquid.xyz/Mainnet/vaults", async route => {
    vaultIndexRequests += 1;
    await indexGate;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          apr: "0.12",
          summary: {
            name: "Preview Vault",
            vaultAddress,
            leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            tvl: "321.5",
            isClosed: false,
            relationship: { type: "normal" },
            createTimeMillis: "1700"
          }
        }
      ])
    });
  });

  await page.route("https://api.hyperliquid.xyz/info", async route => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;
    const requestVaultAddress = String(
      payload?.vaultAddress || payload?.user || ""
    ).toLowerCase();

    if (requestType === "vaultSummaries") {
      vaultSummariesRequests += 1;
      await summariesGate;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([])
      });
      return;
    }

    if (
      requestType === "vaultDetails" &&
      requestVaultAddress === vaultAddress
    ) {
      vaultDetailsRequests += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          name: "Preview Vault",
          vaultAddress,
          leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
          description: "Preview regression fixture",
          apr: "0.12",
          portfolio: [],
          followers: [],
          relationship: { type: "normal" },
          allowDeposits: false,
          alwaysCloseOnWithdraw: false
        })
      });
      return;
    }

    if (
      requestType === "webData2" &&
      requestVaultAddress === vaultAddress
    ) {
      vaultWebDataRequests += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({})
      });
      return;
    }

    await route.continue();
  });

  await visitRoute(page, "/trade");
  await page.evaluate(previewRecord => {
    localStorage.setItem("vault-startup-preview:v1", JSON.stringify(previewRecord));
  }, {
    id: "vault-startup-preview:v1",
    version: 1,
    "saved-at-ms": Date.now(),
    "snapshot-range": "month",
    "wallet-address": null,
    "total-visible-tvl": 321.5,
    "protocol-rows": [
      {
        name: "Preview Vault",
        "vault-address": vaultAddress,
        leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
        apr: 12,
        tvl: 321.5,
        "your-deposit": 0,
        "age-days": 2,
        "snapshot-series": [10, 12],
        "is-closed?": false
      }
    ],
    "user-rows": [],
    "stale?": false
  });

  await dispatch(page, [":actions/navigate", "/vaults", { "replace?": true }]);

  const previewRow = page.locator("[data-role='vault-row-link']").first();
  await expect(previewRow).toBeVisible();
  await expect(previewRow).toContainText("Preview Vault");
  await expect(previewRow).not.toHaveClass(/focus:ring-2/);
  await expect
    .poll(() => vaultIndexRequests, { timeout: 10_000 })
    .toBe(1);
  await expect
    .poll(() => vaultSummariesRequests, { timeout: 10_000 })
    .toBe(1);

  await previewRow.click();
  await page.waitForTimeout(150);

  await expect(vaultIndexRequests).toBe(1);
  await expect(vaultSummariesRequests).toBe(1);

  releaseIndex();
  releaseSummaries();

  await expect
    .poll(async () => {
      const snapshot = await debugCall(page, "qaSnapshot");
      return snapshot.route;
    }, { timeout: 10_000 })
    .toBe(`/vaults/${vaultAddress}`);

  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "vault-detail-root" } }
  );
  await expect
    .poll(() => vaultDetailsRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);
  await expect
    .poll(() => vaultWebDataRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);
});

test("vault position coin jumps to the trade route market @regression", async ({ page }) => {
  const vaultAddress = "0x61b1cf5c2d7c4bf6d5db14f36651b2242e7cba0a";
  let vaultWebDataRequests = 0;

  await page.route("https://api.hyperliquid.xyz/info", async route => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;
    const requestVaultAddress = String(
      payload?.vaultAddress || payload?.user || ""
    ).toLowerCase();

    if (
      requestType === "vaultDetails" &&
      requestVaultAddress === vaultAddress
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          name: "OnlyShorts",
          vaultAddress,
          leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
          description: "Regression fixture",
          apr: "0.12",
          portfolio: [
            [
              "month",
              {
                accountValueHistory: [
                  [1, 100],
                  [2, 110]
                ],
                pnlHistory: [
                  [1, 0],
                  [2, 10]
                ]
              }
            ]
          ],
          followers: [],
          relationship: { type: "normal" },
          allowDeposits: false,
          alwaysCloseOnWithdraw: false
        })
      });
      return;
    }

    if (
      requestType === "webData2" &&
      requestVaultAddress === vaultAddress
    ) {
      vaultWebDataRequests += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          clearinghouseState: {
            assetPositions: [
              {
                coin: "BTC",
                szi: "1.25",
                positionValue: "2500",
                entryPx: "50000",
                markPx: "50125",
                unrealizedPnl: "125",
                returnOnEquity: "0.05",
                liquidationPx: "42000",
                marginUsed: "500",
                leverage: { value: 3 },
                cumFunding: { sinceOpen: "-4.2" }
              }
            ]
          }
        })
      });
      return;
    }

    await route.continue();
  });

  await visitRoute(page, `/vaults/${vaultAddress}`);
  await expect
    .poll(() => vaultWebDataRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);

  await dispatch(page, [":actions/set-vault-detail-activity-tab", ":positions"]);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

  const coinButton = page.locator("[data-role='vault-detail-position-coin-select']").first();
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
    route: "/trade/BTC",
    activeAsset: "BTC"
  });
});

test("asset selector favorite toggle keeps dropdown open @regression", async ({ page }) => {
  await seedAssetSelectorMarketsCache(page);
  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: "asset-selector",
    desktopPresent: true
  });

  const selectorState = await oracle(page, "asset-selector");
  const favoriteButton = page.locator('[data-role="asset-selector-row"] [data-role="asset-selector-favorite-button"]').first();

  await expect(favoriteButton).toHaveAttribute("aria-pressed", "false");
  await favoriteButton.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(favoriteButton).toHaveAttribute("aria-pressed", "true");
  await expectOracle(page, "asset-selector", {
    visibleDropdown: "asset-selector",
    activeAsset: selectorState.activeAsset
  });
});

test("asset selector rapid scroll keeps rows visible @regression", async ({ page }) => {
  await seedAssetSelectorMarketsCache(page, 320);
  const nestedRenderWarnings = [];
  const pageErrors = [];
  page.on("console", (message) => {
    const text = message.text();
    if (
      text.includes("Triggered a render while rendering") ||
      text.includes("replicant.dom/render was called while working on a previous render")
    ) {
      nestedRenderWarnings.push(text);
    }
  });
  page.on("pageerror", (error) => {
    pageErrors.push(String(error));
  });

  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(page.locator('[data-role="asset-selector-scroll-container"]')).toBeVisible();
  await expect(page.locator('[data-role="asset-selector-row"]').first()).toBeVisible();

  const coverageSamples = await page.evaluate(async () => {
    const container = document.querySelector('[data-role="asset-selector-scroll-container"]');
    if (!container) {
      throw new Error("asset selector scroll container not found");
    }

    const rowCoverage = () => {
      const containerRect = container.getBoundingClientRect();
      const intervals = Array.from(document.querySelectorAll('[data-role="asset-selector-row"]'))
        .map((row) => row.getBoundingClientRect())
        .map((rect) => [Math.max(containerRect.top, rect.top), Math.min(containerRect.bottom, rect.bottom)])
        .filter(([top, bottom]) => bottom > top)
        .sort((a, b) => a[0] - b[0]);

      let covered = 0;
      let cursor = containerRect.top;
      for (const [top, bottom] of intervals) {
        const start = Math.max(top, cursor);
        if (bottom > start) {
          covered += bottom - start;
          cursor = bottom;
        }
      }

      return {
        covered,
        height: containerRect.height,
        blank: Math.max(0, containerRect.height - covered)
      };
    };

    const maxScrollTop = Math.max(0, container.scrollHeight - container.clientHeight);
    const targets = [0.15, 0.3, 0.45, 0.6, 0.75, 0.9]
      .map((fraction) => Math.max(0, Math.min(maxScrollTop, Math.floor(maxScrollTop * fraction))))
      .filter((target, index, allTargets) => index === 0 || target !== allTargets[index - 1]);

    const sampleTarget = async (target) => {
      container.scrollTop = target;
      const immediateCoverage = rowCoverage();
      await new Promise((resolve) => requestAnimationFrame(() => resolve()));
      const nextFrameCoverage = rowCoverage();
      await new Promise((resolve) => requestAnimationFrame(() => resolve()));
      return {
        target,
        immediateCoverage,
        nextFrameCoverage,
        settledCoverage: rowCoverage()
      };
    };

    const samples = [];
    for (const target of targets) {
      samples.push(await sampleTarget(target));
    }

    return samples;
  });

  for (const sample of coverageSamples) {
    expect(sample.immediateCoverage.blank).toBeLessThanOrEqual(1);
    expect(sample.nextFrameCoverage.blank).toBeLessThanOrEqual(1);
    expect(sample.settledCoverage.blank).toBeLessThanOrEqual(1);
  }
  expect(nestedRenderWarnings).toEqual([]);
  expect(pageErrors).toEqual([]);
});

test("funding modal deposit flow selects USDC @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/open-funding-deposit-modal", null]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expectOracle(page, "funding-modal", {
    open: true,
    title: "Deposit",
    contentKind: ":deposit/select"
  });

  await dispatch(page, [":actions/select-funding-deposit-asset", "usdc"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expectOracle(page, "funding-modal", {
    open: true,
    title: "Deposit USDC",
    contentKind: ":deposit/amount",
    selectedDepositAssetKey: "usdc"
  });
});

test("trade funding openers launch the funding modal on real click @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  for (const [dataRole, title] of [
    ["funding-action-deposit", "Deposit"],
    ["funding-action-transfer", "Perps <-> Spot"],
    ["funding-action-withdraw", "Withdraw"]
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

test("funding modal accessibility keeps focus in dialog, restores opener focus, and exposes labels @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  const openButton = page.locator('[data-role="funding-action-deposit"]');
  await expect(openButton).toBeVisible();
  await openButton.focus();
  await dispatch(page, [
    ":actions/open-funding-deposit-modal",
    await sourceRectForLocator(page, openButton),
    await openButton.getAttribute("data-role")
  ]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  const dialog = page.locator('[data-role="funding-modal"]');
  const closeButton = page.locator('[data-role="funding-modal-close"]');

  await expect(dialog).toHaveAttribute("aria-labelledby", "funding-modal-title");
  await expect(page.locator("#funding-modal-title")).toHaveText("Deposit");
  await expect(page.getByLabel("Search deposit assets")).toBeVisible();
  await expect(closeButton).toBeFocused();

  await page.keyboard.press("Shift+Tab");
  await expect
    .poll(async () =>
      dialog.evaluate((element) => element.contains(document.activeElement))
    )
    .toBe(true);

  await dispatch(page, [":actions/select-funding-deposit-asset", "usdc"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(page.getByLabel("Amount")).toBeVisible();
  await expect(dialog).toHaveAttribute("aria-labelledby", "funding-modal-title");

  await closeButton.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(dialog).toBeHidden();
  await expect(openButton).toBeFocused();
});

test("funding tooltip transitions from live position to hypothetical estimate @regression", async ({ page }) => {
  const livePosition = {
    coin: "BTC",
    szi: "9.2807",
    positionValue: "1000",
    entryPx: "107.7426",
    markPx: "107.7426",
    unrealizedPnl: "0",
    returnOnEquity: "0",
    liquidationPx: "80",
    marginUsed: "250",
    leverage: { value: 4 },
    cumFunding: { sinceOpen: "0" }
  };
  const livePositionValue = Number(livePosition.positionValue).toFixed(2);

  await page.setViewportSize({ width: 1440, height: 900 });
  await seedAssetSelectorMarketsCache(page);
  await visitRoute(page, "/trade/BTC");
  await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
  await waitForIdle(page, { quietMs: 800, timeoutMs: 12_000, pollMs: 50 });
  await freezeAccountSurfaceSync(page, SPECTATE_ADDRESS);
  await seedFundingTooltipLivePositionState(page, { position: livePosition });

  const tooltipTrigger = page.locator('[data-role="active-asset-funding-trigger"]');
  await expect(tooltipTrigger).toHaveCount(1);
  await tooltipTrigger.scrollIntoViewIfNeeded();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tooltipTrigger).toBeVisible();
  await tooltipTrigger.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tooltipTrigger).toHaveAttribute("aria-expanded", "true");

  const tooltip = page.locator('[data-role="active-asset-funding-tooltip"]');
  const positionSection = tooltip.locator('[data-role="active-asset-funding-position-section"]');
  await expect(tooltip).toBeVisible();
  const readTooltipLayering = () => tooltip.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    const x = rect.left + rect.width / 2;
    const y = rect.top + rect.height / 2;
    const hit = document.elementFromPoint(x, y);

    return {
      fullyWithinViewport:
        rect.left >= 0 &&
        rect.top >= 0 &&
        rect.right <= window.innerWidth &&
        rect.bottom <= window.innerHeight,
      centerHitBelongsToTooltip: Boolean(hit && element.contains(hit)),
      centerHitRole: hit?.getAttribute("data-role") ?? null,
      position: getComputedStyle(element).position,
      zIndex: getComputedStyle(element).zIndex,
      style: {
        left: element.style.left,
        top: element.style.top,
        width: element.style.width
      }
    };
  });
  const layering = await readTooltipLayering();
  expect(layering.position).toBe("fixed");
  expect(layering.style.left).not.toBe("");
  expect(layering.style.top).not.toBe("");
  expect(layering.style.width).not.toBe("");
  expect(layering.fullyWithinViewport).toBe(true);
  expect(layering.centerHitBelongsToTooltip, JSON.stringify(layering)).toBe(true);
  await expect(positionSection).toHaveAttribute("data-position-mode", "live");
  await expect(tooltip.getByRole("heading", { name: "Your Position" })).toBeVisible();
  await expect(tooltip.getByRole("button", { name: "Edit estimate" })).toBeVisible();

  await page.setViewportSize({ width: 1023, height: 900 });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tooltip).toHaveCount(0);
  await expect(page.locator('[data-role="trade-mobile-asset-summary"]')).toBeVisible();

  await page.setViewportSize({ width: 1440, height: 900 });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await tooltipTrigger.scrollIntoViewIfNeeded();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await tooltipTrigger.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tooltipTrigger).toHaveAttribute("aria-expanded", "true");
  await expect(tooltip).toBeVisible();

  await page.setViewportSize({ width: 1024, height: 900 });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tooltipTrigger).toHaveAttribute("aria-expanded", "false");
  await expect(tooltip).toBeHidden();

  await tooltipTrigger.scrollIntoViewIfNeeded();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await tooltipTrigger.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tooltipTrigger).toHaveAttribute("aria-expanded", "true");
  await expect(tooltip).toBeVisible();
  const reopenedLayering = await readTooltipLayering();
  expect(reopenedLayering.fullyWithinViewport).toBe(true);
  expect(reopenedLayering.centerHitBelongsToTooltip, JSON.stringify(reopenedLayering)).toBe(true);

  await tooltip.getByRole("button", { name: "Edit estimate" }).click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(positionSection).toHaveAttribute("data-position-mode", "hypothetical");
  await expect(tooltip.getByRole("heading", { name: "Hypothetical Position" })).toBeVisible();
  await expect(tooltip.getByRole("button", { name: "Use live" })).toBeVisible();

  const sizeInput = tooltip.getByLabel("Hypothetical position size");
  const valueInput = tooltip.getByLabel("Hypothetical position value");
  await expect(sizeInput).toHaveValue("9.2807");
  await expect(valueInput).toHaveValue(livePositionValue);

  const next24hPayment = tooltip
    .locator('div.contents')
    .filter({ hasText: "Next 24h" })
    .locator("span")
    .nth(2);
  const next24hBefore = (await next24hPayment.textContent())?.trim() ?? "";

  await sizeInput.fill("10.0000");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect.poll(
    async () => (await next24hPayment.textContent())?.trim() ?? "",
    { timeout: 5_000 }
  ).not.toBe(next24hBefore);

  const statisticsScroll = page.locator('[data-role="active-asset-statistics-scroll"]');
  const scrolled = await statisticsScroll.evaluate((element) => {
    const previous = element.scrollLeft;
    const next = previous > 0 ? 0 : element.scrollWidth - element.clientWidth;
    element.scrollLeft = next;
    element.dispatchEvent(new Event("scroll", { bubbles: true }));
    return element.scrollLeft !== previous;
  });
  expect(scrolled).toBe(true);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tooltipTrigger).toHaveAttribute("aria-expanded", "false");
  await expect(tooltip).toBeHidden();

  await tooltipTrigger.scrollIntoViewIfNeeded();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await tooltipTrigger.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tooltip).toBeVisible();
  const reanchoredLayering = await readTooltipLayering();
  expect(reanchoredLayering.fullyWithinViewport).toBe(true);
  expect(reanchoredLayering.centerHitBelongsToTooltip, JSON.stringify(reanchoredLayering)).toBe(true);
});

test.describe("funding tooltip mobile presentation @mobile", () => {
  test.use(mobileViewport);

  test("funding tooltip opens as a mobile sheet @regression", async ({ page }) => {
    const livePosition = {
      coin: "BTC",
      szi: "9.2807",
      positionValue: "1000",
      entryPx: "107.7426",
      markPx: "107.7426",
      unrealizedPnl: "0",
      returnOnEquity: "0",
      liquidationPx: "80",
      marginUsed: "250",
      leverage: { value: 4 },
      cumFunding: { sinceOpen: "0" }
    };

    await seedAssetSelectorMarketsCache(page);
    await visitRoute(page, "/trade/BTC");
    await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
    await waitForIdle(page, { quietMs: 800, timeoutMs: 12_000, pollMs: 50 });
    await freezeAccountSurfaceSync(page, SPECTATE_ADDRESS);
    await seedFundingTooltipLivePositionState(page, { position: livePosition });

    const detailsToggle = page.locator('[data-role="trade-mobile-asset-details-toggle"]');
    await expect(detailsToggle).toBeVisible();
    await detailsToggle.click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

    const tooltipTrigger = page.locator('[data-role="active-asset-funding-trigger"]');
    await expect(tooltipTrigger).toHaveCount(1);
    await tooltipTrigger.click({ force: true });
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

    const sheetLayer = page.locator('[data-role="active-asset-funding-mobile-sheet-layer"]');
    const sheet = page.locator('[data-role="active-asset-funding-mobile-sheet"]');
    const backdrop = page.locator('[data-role="active-asset-funding-mobile-sheet-backdrop"]');
    const positionSection = sheet.locator('[data-role="active-asset-funding-position-section"]');

    await expect(sheetLayer).toBeVisible();
    await expect(sheet).toBeVisible();
    await expect(positionSection).toHaveAttribute("data-position-mode", "live");
    await expect(sheet.getByRole("heading", { name: "Your Position" })).toBeVisible();
    await expect(sheet.getByRole("heading", { name: "Predictability (30d)" })).toBeVisible();
    await expect(sheet.getByText(/Past Rate Correlation|Loading 30d stats/)).toBeVisible();

    await backdrop.click({ position: { x: 16, y: 16 } });
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
    await expect(sheetLayer).toBeHidden();
  });
});

test("wallet connect and enable trading stays deterministic @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await debugCall(page, "installExchangeSimulator", {
    approveAgent: { responses: [{ status: "ok" }] }
  });

  await debugCall(page, "installWalletSimulator", {
    accounts: ["0x1111111111111111111111111111111111111111"],
    requestAccounts: ["0x1111111111111111111111111111111111111111"],
    chainId: "0xa4b1"
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");

  await dispatch(page, [":actions/connect-wallet"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    connected: true,
    address: "0x1111111111111111111111111111111111111111",
    agentStatus: "not-ready"
  });

  await dispatch(page, [":actions/enable-agent-trading"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 5_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    connected: true,
    agentStatus: "ready",
    agentError: null
  });
  const exchangeSnapshot = await debugCall(page, "exchangeSimulatorSnapshot");
  expect(exchangeSnapshot.calls).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        matchedPath: ["approveAgent"],
        responseStatus: "ok"
      }),
      expect.objectContaining({
        matchedPath: ["signedActions", "scheduleCancel"],
        responseStatus: "ok",
        defaulted: true
      })
    ])
  );
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/enable-agent-trading",
      projectionBeforeHeavy: true,
      heavyEffectCount: 1
    },
    { args: { actionId: ":actions/enable-agent-trading" } }
  );
});

test("order submit and cancel gating uses simulator-backed assertions @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await debugCall(page, "installWalletSimulator", {
    accounts: ["0x1111111111111111111111111111111111111111"],
    requestAccounts: ["0x1111111111111111111111111111111111111111"],
    chainId: "0xa4b1"
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");

  await dispatch(page, [":actions/connect-wallet"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    connected: true,
    address: "0x1111111111111111111111111111111111111111",
    agentStatus: "not-ready"
  });

  await dispatch(page, [":actions/select-order-entry-mode", ":limit"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-input-mode", ":base"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/update-order-form", [":price"], "100"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-display", "1"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "order-form", {
    sizeDisplay: "1",
    submitDisabled: false,
    submitReason: null
  });

  await dispatch(page, [":actions/submit-order"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    agentError: "Enable trading before submitting orders."
  });
  await expectOracle(page, "agent-trading-recovery", {
    open: true,
    message: "Enable trading before submitting orders."
  });
  await expectOracle(page, "order-form", {
    runtimeError: null
  });
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/submit-order",
      covered: true,
      heavyEffectCount: 0,
      projectionBeforeHeavy: true,
      phaseOrderValid: true
    },
    { args: { actionId: ":actions/submit-order" } }
  );

  await dispatch(page, [":actions/cancel-order", { coin: "BTC", oid: 101 }]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "order-form", {
    cancelError: "Enable trading before cancelling orders."
  });
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/cancel-order",
      covered: true,
      heavyEffectCount: 0,
      projectionBeforeHeavy: true,
      phaseOrderValid: true
    },
    { args: { actionId: ":actions/cancel-order" } }
  );
});

test("header subaccount state stays visible across review widths @regression", async ({
  page
}) => {
  const ownerAddress = "0x1111111111111111111111111111111111111111";
  const subaccountAddress = "0x2222222222222222222222222222222222222222";
  const viewports = [
    { width: 375, height: 812 },
    { width: 768, height: 900 },
    { width: 1280, height: 900 },
    { width: 1440, height: 900 }
  ];

  await visitRoute(page, "/trade");
  await freezeAccountSurfaceSync(page, ownerAddress);
  await seedReadyTradingSession(page, { walletAddress: ownerAddress });
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await seedOwnedSubaccounts(page, {
    ownerAddress,
    subaccountAddress,
    selectedAddress: subaccountAddress
  });
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  const banner = page.locator('[data-role="header-subaccount-active-banner"]');
  const trigger = page.locator('[data-role="wallet-menu-trigger"]');

  for (const viewport of viewports) {
    await page.setViewportSize(viewport);
    await expect(banner).toBeVisible();
    await expect(banner).toHaveText(
      "IMPORTANT: You are trading on behalf of your sub-account Desk"
    );

    const closedGeometry = await headerSubaccountGeometry(page);
    expect(closedGeometry.banner.left).toBeGreaterThanOrEqual(0);
    expect(closedGeometry.banner.right).toBeLessThanOrEqual(viewport.width + 1);
    expect(closedGeometry.banner.height).toBeGreaterThanOrEqual(28);

    await expect(trigger).toBeVisible();
    await expect(trigger).toContainText("Sub: Desk");
    await trigger.click();
    await expect(page.locator('[data-role="wallet-menu-panel"]')).toBeVisible();
    await expect(page.locator('[data-role="header-account-target-copy-master"]')).toBeVisible();
    await expect(
      page.locator(`[data-role="header-account-target-copy-${subaccountAddress}"]`)
    ).toBeVisible();
    await expect(page.locator('[data-role="wallet-menu-disconnect"]')).toBeVisible();
    await expect(page.locator('[data-role="header-account-target-details"]')).toHaveCount(0);

    const openGeometry = await headerSubaccountGeometry(page);
    expect(openGeometry.trigger.right).toBeLessThanOrEqual(viewport.width + 1);
    expect(openGeometry.menu.right).toBeLessThanOrEqual(viewport.width + 1);
    await closeHeaderAccountTarget(page);
  }
});

test("header account selector routes subaccount order payloads through vaultAddress @smoke @regression", async ({
  page
}) => {
  const ownerAddress = "0x1111111111111111111111111111111111111111";
  const subaccountAddress = "0x2222222222222222222222222222222222222222";

  await page.addInitScript(() => {
    globalThis.__headerAccountClipboardWrites = [];
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        writeText(text) {
          globalThis.__headerAccountClipboardWrites.push(text);
          return Promise.resolve();
        }
      }
    });
  });
  await visitRoute(page, "/trade");
  await freezeAccountSurfaceSync(page, ownerAddress);
  await debugCall(page, "installExchangeSimulator", {
    signedActions: {
      default: {
        responses: Array.from({ length: 12 }, () => ({ status: "ok" }))
      }
    },
    info: {
      frontendOpenOrders: { responses: [[], [], [], []] },
      clearinghouseState: {
        responses: [
          { marginSummary: { accountValue: "1000" }, assetPositions: [] },
          { marginSummary: { accountValue: "1000" }, assetPositions: [] },
          { marginSummary: { accountValue: "1000" }, assetPositions: [] },
          { marginSummary: { accountValue: "1000" }, assetPositions: [] }
        ]
      },
      historicalOrders: { responses: [[], [], [], []] },
      perpDexs: { responses: [[], [], [], []] }
    }
  });
  await seedReadyTradingSession(page, {
    walletAddress: ownerAddress,
    privateKey: "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  });
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await seedOwnedSubaccounts(page, { ownerAddress, subaccountAddress });
  await setTradingConfirmations(page, { openOrders: false });
  await waitForIdle(page, { quietMs: 350, timeoutMs: 6_000, pollMs: 50 });

  const trigger = page.locator('[data-role="wallet-menu-trigger"]');
  await expect(trigger).toBeVisible();
  await expect(trigger).toContainText(/0x1111.+1111/);
  await selectHeaderAccountTarget(
    page,
    `header-account-target-option-${subaccountAddress}`
  );
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expect(trigger).toContainText("Desk");
  await expect(trigger).toContainText("Sub: Desk");
  await expect(page.locator('[data-role="header-subaccount-active-banner"]')).toHaveText(
    "IMPORTANT: You are trading on behalf of your sub-account Desk"
  );
  await openHeaderAccountTarget(page);
  const masterCopy = page.locator('[data-role="header-account-target-copy-master"]');
  const subaccountCopy = page.locator(
    `[data-role="header-account-target-copy-${subaccountAddress}"]`
  );
  await expect(masterCopy).toBeVisible();
  await expect(
    subaccountCopy
  ).toBeVisible();
  await expect(page.locator('[data-role="wallet-menu-disconnect"]')).toBeVisible();
  await masterCopy.click();
  await subaccountCopy.click();
  await expect
    .poll(() => page.evaluate(() => globalThis.__headerAccountClipboardWrites ?? []))
    .toEqual([ownerAddress, subaccountAddress]);
  await closeHeaderAccountTarget(page);

  await fillLimitOrderForm(page);
  await expectOracle(page, "order-form", {
    submitDisabled: false,
    submitReason: null
  });
  await dispatch(page, [":actions/submit-order"]);
  await waitForIdle(page, { quietMs: 350, timeoutMs: 6_000, pollMs: 50 });
  await dispatch(page, [":actions/cancel-order", { coin: "BTC", oid: 101 }]);
  await waitForIdle(page, { quietMs: 350, timeoutMs: 6_000, pollMs: 50 });

  await expect
    .poll(
      async () => {
        const snapshot = await debugCall(page, "exchangeSimulatorSnapshot");
        return {
          orderVaults: signedActionRequests(snapshot, "order").map(
            (request) => request.vaultAddress ?? null
          ),
          cancelVaults: signedActionRequests(snapshot, "cancel").map(
            (request) => request.vaultAddress ?? null
          )
        };
      },
      { timeout: 10_000 }
    )
    .toMatchObject({
      orderVaults: expect.arrayContaining([subaccountAddress]),
      cancelVaults: expect.arrayContaining([subaccountAddress])
    });

  await openHeaderAccountTarget(page);
  await page.locator('[data-role="header-account-target-option-master"]').click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expect(trigger).toContainText(/0x1111.+1111/);
  await expect(page.locator('[data-role="header-subaccount-active-banner"]')).toHaveCount(0);

  const beforeMasterSnapshot = await debugCall(page, "exchangeSimulatorSnapshot");
  const orderCountBeforeMaster = signedActionRequests(beforeMasterSnapshot, "order").length;
  const cancelCountBeforeMaster = signedActionRequests(beforeMasterSnapshot, "cancel").length;

  await fillLimitOrderForm(page);
  await expectOracle(page, "order-form", {
    submitDisabled: false,
    submitReason: null
  });
  await dispatch(page, [":actions/submit-order"]);
  await waitForIdle(page, { quietMs: 350, timeoutMs: 6_000, pollMs: 50 });
  await dispatch(page, [":actions/cancel-order", { coin: "BTC", oid: 102 }]);
  await waitForIdle(page, { quietMs: 350, timeoutMs: 6_000, pollMs: 50 });

  await expect
    .poll(
      async () => {
        const snapshot = await debugCall(page, "exchangeSimulatorSnapshot");
        return {
          masterOrderVaults: signedActionRequests(snapshot, "order")
            .slice(orderCountBeforeMaster)
            .map((request) => request.vaultAddress ?? null),
          masterCancelVaults: signedActionRequests(snapshot, "cancel")
            .slice(cancelCountBeforeMaster)
            .map((request) => request.vaultAddress ?? null)
        };
      },
      { timeout: 10_000 }
    )
    .toEqual({
      masterOrderVaults: [null],
      masterCancelVaults: [null]
    });
});

test("order submit confirmation renders in-app instead of opening a browser dialog @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");

  let browserDialogSeen = false;
  page.on("dialog", async (dialog) => {
    browserDialogSeen = true;
    await dialog.dismiss();
  });

  await seedReadyTradingSession(page);
  await setTradingConfirmations(page, { openOrders: true });
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await dispatch(page, [":actions/select-order-entry-mode", ":limit"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-input-mode", ":base"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/update-order-form", [":price"], "100"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-display", "1"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await dispatch(page, [":actions/submit-order"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await expect(page.locator('[data-role="order-submit-confirmation-dialog"]')).toBeVisible();
  await expect(page.locator('[data-role="order-submit-confirmation-title"]')).toHaveText(
    "Submit Order?"
  );
  await expect(browserDialogSeen).toBe(false);
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/submit-order",
      covered: true,
      heavyEffectCount: 0,
      projectionBeforeHeavy: true,
      phaseOrderValid: true
    },
    { args: { actionId: ":actions/submit-order" } }
  );

  await page.locator('[data-role="order-submit-confirmation-cancel"]').click();
  await expect(page.locator('[data-role="order-submit-confirmation-dialog"]')).toHaveCount(0);
});

test("trading settings confirmation toggles respond to visible switch clicks @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");
  await setTradingConfirmations(page, { openOrders: true, closePosition: true });

  await page.locator('[data-role="header-settings-button"]').click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  const openToggleLabel = page
    .locator(
      '[data-role="trading-settings-panel"] [data-role="trading-settings-confirm-open-orders-row-toggle"]'
    )
    .first();
  const openToggleInput = page
    .locator(
      '[data-role="trading-settings-panel"] [data-role="trading-settings-confirm-open-orders-row-toggle"]'
    )
    .first();
  const closeToggleLabel = page
    .locator(
      '[data-role="trading-settings-panel"] [data-role="trading-settings-confirm-close-position-row-toggle"]'
    )
    .first();
  const closeToggleInput = page
    .locator(
      '[data-role="trading-settings-panel"] [data-role="trading-settings-confirm-close-position-row-toggle"]'
    )
    .first();

  await expect(openToggleInput).toHaveAttribute("role", "switch");
  await expect(openToggleInput).toHaveAttribute("aria-checked", "true");
  await expect(closeToggleInput).toHaveAttribute("aria-checked", "true");

  await openToggleLabel.click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await expect(openToggleInput).toHaveAttribute("aria-checked", "false");
  await expect
    .poll(
      async () =>
        (await debugCall(page, "snapshot"))["app-state"]["trading-settings"]["confirm-open-orders?"],
      { timeout: 4_000 }
    )
    .toBe(false);

  await closeToggleLabel.click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await expect(closeToggleInput).toHaveAttribute("aria-checked", "false");
  await expect
    .poll(
      async () =>
        (await debugCall(page, "snapshot"))["app-state"]["trading-settings"]["confirm-close-position?"],
      { timeout: 4_000 }
    )
    .toBe(false);

  await expect
    .poll(
      () =>
        page.evaluate(() =>
          JSON.parse(localStorage.getItem("hyperopen:trading-settings:v1") || "{}")
        ),
      { timeout: 4_000 }
    )
    .toMatchObject({
      "confirm-open-orders?": false,
      "confirm-close-position?": false
    });
});

test("trading settings renders compact popover rows without clipping @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");

  await page.locator('[data-role="header-settings-button"]').click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  const settingsSurface = page.locator('[data-role="trading-settings-panel"]:visible');
  const desktopPanel = page.locator('[data-role="trading-settings-panel"]:visible');
  const rememberRow = settingsSurface.locator('[data-role="trading-settings-storage-mode-row"]').first();
  const openOrdersRow = settingsSurface
    .locator('[data-role="trading-settings-confirm-open-orders-row"]')
    .first();
  const fillMarkersRow = settingsSurface.locator('[data-role="trading-settings-fill-markers-row"]').first();

  if ((await desktopPanel.count()) > 0) {
    const bounds = await desktopPanel.boundingBox();
    expect(Math.round(bounds?.width ?? 0)).toBe(400);
    expect(Math.round((bounds?.x ?? 0) + (bounds?.width ?? 0))).toBeGreaterThanOrEqual(1260);
    expect(Math.round(bounds?.y ?? 0)).toBe(56);
    const bodyHasInternalScroll = await desktopPanel.locator(".ts-pop-body").evaluate((node) => {
      return node.scrollHeight > node.clientHeight + 1;
    });
    expect(bodyHasInternalScroll).toBe(true);
    const containment = await desktopPanel.evaluate((panel) => {
      const body = panel.querySelector(".ts-pop-body");
      const footer = panel.querySelector("[data-role='trading-settings-footer-note']");
      if (!body || !footer) {
        throw new Error("settings panel body/footer missing");
      }
      const panelRect = panel.getBoundingClientRect();
      const bodyRect = body.getBoundingClientRect();
      const footerRect = footer.getBoundingClientRect();
      return {
        bodyBottom: bodyRect.bottom,
        footerTop: footerRect.top,
        footerBottom: footerRect.bottom,
        panelBottom: panelRect.bottom,
        viewportBottom: window.innerHeight
      };
    });
    expect(containment.bodyBottom).toBeLessThanOrEqual(containment.footerTop + 1);
    expect(containment.footerBottom).toBeLessThanOrEqual(containment.panelBottom + 1);
    expect(containment.panelBottom).toBeLessThanOrEqual(containment.viewportBottom + 1);
  }

  await expect(settingsSurface).toHaveAttribute("role", "dialog");
  await expect(settingsSurface).toHaveAttribute("aria-label", "Trading settings");
  await expect(settingsSurface).not.toContainText(", to open");
  await expect(settingsSurface).not.toContainText("esc to close");
  await expect(settingsSurface).toContainText("These settings live on this device only.");
  await expect(settingsSurface.locator('[data-role="trading-settings-confirm-market-orders-row"]')).toBeVisible();
  await expect(settingsSurface.locator('[data-role="trading-settings-sound-on-fill-row"]')).toBeVisible();
  await expect(settingsSurface.locator('[data-role="trading-settings-footer-note"]').first()).toBeVisible();
  await expect(rememberRow).toContainText("Stay signed in across browser restarts.");
  await expect(openOrdersRow).toContainText("Show a preview before placing.");
  await expect(fillMarkersRow).toContainText("Show your fills on the price chart.");
});

test("trading settings session toggles gate passkey lock behind remembered sessions @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");

  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const nextState = c.assoc_in(
      c.deref(store),
      kwPath("wallet", "agent", "passkey-supported?"),
      true
    );

    c.reset_BANG_(store, nextState);
  });

  await page.locator('[data-role="header-settings-button"]').click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  const settingsSurface = page.locator('[data-role="trading-settings-panel"]:visible');
  const rememberRow = settingsSurface.locator('[data-role="trading-settings-storage-mode-row"]').first();
  const rememberToggleInput = rememberRow
    .locator('[data-role="trading-settings-storage-mode-row-toggle"]')
    .first();
  const passkeyRow = settingsSurface
    .locator('[data-role="trading-settings-local-protection-mode-row"]')
    .first();
  const passkeyToggleLabel = passkeyRow
    .locator('[data-role="trading-settings-local-protection-mode-row-toggle"]')
    .first();
  const passkeyToggleInput = passkeyToggleLabel;

  await expect(rememberToggleInput).toHaveAttribute("aria-checked", "true");
  await expect(passkeyToggleInput).toBeEnabled();

  await passkeyToggleLabel.click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expect(passkeyToggleInput).toHaveAttribute("aria-checked", "true");
  await expect
    .poll(
      () => page.evaluate(() => localStorage.getItem("hyperopen:agent-local-protection-mode:v1")),
      { timeout: 4_000 }
    )
    .toBe("passkey");
});

test("ready remembered session keeps submit usable after enabling passkey lock @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");
  await installPasskeyLockboxMock(page);
  await seedRememberedTradingSession(page, {
    status: "ready",
    localProtectionMode: "plain",
    passkeySupported: true
  });
  await setTradingConfirmations(page, { openOrders: true });

  await page.locator('[data-role="header-settings-button"]').click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  const settingsSurface = page.locator('[data-role="trading-settings-panel"]:visible');
  const passkeyRow = settingsSurface
    .locator('[data-role="trading-settings-local-protection-mode-row"]')
    .first();
  const passkeyToggleLabel = passkeyRow
    .locator('[data-role="trading-settings-local-protection-mode-row-toggle"]')
    .first();
  const passkeyToggleInput = passkeyToggleLabel;

  await expect(passkeyToggleInput).toBeEnabled();
  await passkeyToggleLabel.click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await expect(passkeyToggleInput).toHaveAttribute("aria-checked", "true");
  await expectOracle(page, "wallet-status", {
    connected: true,
    agentStatus: "ready",
    agentError: null
  });
  await expectOracle(page, "agent-trading-recovery", {
    open: false
  });
  await expect
    .poll(
      () => page.evaluate(() => localStorage.getItem("hyperopen:agent-local-protection-mode:v1")),
      { timeout: 4_000 }
    )
    .toBe("passkey");
  await expect
    .poll(
      () =>
        page.evaluate(() =>
          localStorage.getItem(
            "hyperopen:agent-session:v1:0x1111111111111111111111111111111111111111"
          )
        ),
      { timeout: 4_000 }
    )
    .toBe(null);

  await dispatch(page, [":actions/select-order-entry-mode", ":limit"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-input-mode", ":base"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/update-order-form", [":price"], "100"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-display", "1"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await dispatch(page, [":actions/submit-order"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await expect(page.locator('[data-role="order-submit-confirmation-dialog"]')).toBeVisible();
  await expectOracle(page, "agent-trading-recovery", {
    open: false
  });
});

test("locked remembered passkey session disables downgrade until unlock @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");
  await seedRememberedTradingSession(page, {
    status: "locked",
    localProtectionMode: "passkey",
    passkeySupported: true
  });

  await page.locator('[data-role="header-settings-button"]').click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  const settingsSurface = page.locator('[data-role="trading-settings-panel"]:visible');
  const passkeyRow = settingsSurface
    .locator('[data-role="trading-settings-local-protection-mode-row"]')
    .first();
  const passkeyToggleInput = passkeyRow
    .locator('[data-role="trading-settings-local-protection-mode-row-toggle"]')
    .first();

  await expect(passkeyToggleInput).toBeDisabled();
  await expect(passkeyRow).toContainText("Require passkey for sensitive actions.");
  await expect
    .poll(
      () => page.evaluate(() => localStorage.getItem("hyperopen:agent-local-protection-mode:v1")),
      { timeout: 4_000 }
    )
    .toBe("passkey");
});

test("locked remembered passkey session submit unlocks and submits original order @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");
  await freezeAccountSurfaceSync(page, "0x1111111111111111111111111111111111111111");
  await waitForIdle(page, { quietMs: 500, timeoutMs: 10_000, pollMs: 50 });
  await debugCall(page, "installExchangeSimulator", {
    signedActions: {
      default: {
        responses: [{ status: "ok" }, { status: "ok" }, { status: "ok" }, { status: "ok" }]
      }
    }
  });
  await installPasskeyUnlockMock(page);
  await seedRememberedTradingSession(page, {
    status: "locked",
    localProtectionMode: "passkey",
    passkeySupported: true
  });
  await setTradingConfirmations(page, { openOrders: false });
  await expectOracle(page, "wallet-status", {
    connected: true,
    agentStatus: "locked",
    agentError: null
  });

  await dispatch(page, [":actions/select-order-entry-mode", ":limit"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-input-mode", ":base"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/update-order-form", [":price"], "100"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-display", "1"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "order-form", {
    submitDisabled: false,
    submitReason: null
  });
  await expect(page.locator('[data-parity-id="trade-submit-order-button"]')).toBeEnabled();
  await dispatch(page, [":actions/submit-order"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await expectOracle(page, "wallet-status", {
    connected: true,
    agentStatus: "ready",
    agentError: null
  });
  await expectOracle(page, "agent-trading-recovery", {
    open: false
  });
  await expectOracle(page, "order-form", {
    runtimeError: null
  });
  await expect(page.locator('[data-role="order-submit-confirmation-dialog"]')).toHaveCount(0);

  await expect
    .poll(
      async () => {
        const exchangeSnapshot = await debugCall(page, "exchangeSimulatorSnapshot");
        return exchangeSnapshot.calls.flatMap((call) =>
          (call.paths ?? [])
            .filter((path) => Array.isArray(path) && path[0] === "signedActions")
            .map((path) => path[1])
        );
      },
      { timeout: 10_000 }
    )
    .toEqual(expect.arrayContaining(["updateLeverage", "order"]));
});

test("locked remembered passkey session cancel unlocks and submits named-dex cancel @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");
  await freezeAccountSurfaceSync(page, "0x1111111111111111111111111111111111111111");
  await waitForIdle(page, { quietMs: 500, timeoutMs: 10_000, pollMs: 50 });
  await debugCall(page, "installExchangeSimulator", {
    signedActions: {
      default: {
        responses: [
          { status: "ok" },
          {
            status: "ok",
            response: {
              type: "cancel",
              data: { statuses: ["success"] }
            }
          }
        ]
      }
    },
    info: {
      default: {
        responses: [[], { assetPositions: [] }, [], { assetPositions: [] }]
      }
    }
  });
  await installPasskeyUnlockMock(page);
  await seedRememberedTradingSession(page, {
    status: "locked",
    localProtectionMode: "passkey",
    passkeySupported: true
  });
  await waitForIdle(page, { quietMs: 500, timeoutMs: 10_000, pollMs: 50 });
  await seedNamedDexMarketForCancel(page);

  await dispatch(page, [":actions/cancel-order", { coin: "SILVER", dex: "xyz", oid: 404 }]);
  await waitForIdle(page, { quietMs: 500, timeoutMs: 10_000, pollMs: 50 });

  await expectOracle(page, "wallet-status", {
    connected: true,
    agentStatus: "ready",
    agentError: null
  });
  await expectOracle(page, "order-form", {
    cancelError: null
  });
  await expect
    .poll(
      async () => {
        const exchangeSnapshot = await debugCall(page, "exchangeSimulatorSnapshot");
        return exchangeSnapshot.calls
          .filter((call) =>
            (call.paths ?? []).some(
              (path) => Array.isArray(path) && path[0] === "signedActions"
            )
          )
          .map((call) => ({
            actionTypes: (call.paths ?? [])
              .filter((path) => Array.isArray(path) && path[0] === "signedActions")
              .map((path) => path[1]),
            responseStatus: call.responseStatus
          }));
      },
      { timeout: 10_000 }
    )
    .toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          actionTypes: expect.arrayContaining(["scheduleCancel"]),
          responseStatus: "ok"
        }),
        expect.objectContaining({
          actionTypes: expect.arrayContaining(["cancel"]),
          responseStatus: "ok"
        })
      ])
    );
});
