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

    c.reset_BANG_(store, nextState);
  }, assetPositions);
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

async function seedOutcomeActiveAsset(page) {
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
        ]
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
  });
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

test("outcome market tooltip stays within active selector width and glows on hover @regression", async ({ page }) => {
  await visitRoute(page, "/trade");
  await seedOutcomeActiveAsset(page);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });

  const hoverRegion = page.locator('[data-role="outcome-market-name-hover-region"]');
  const trigger = hoverRegion.locator("button").first();
  const tooltip = page.locator('[data-role="outcome-market-tooltip"]');

  await expect(hoverRegion).toHaveCount(1);
  await hoverRegion.hover();
  await expect(tooltip).toHaveCSS("opacity", "1");

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
      panelWidth: panelRect.width
    };
  });

  expect(Math.abs(geometry.panelLeft - geometry.triggerLeft)).toBeLessThanOrEqual(1);
  expect(Math.abs(geometry.panelRight - geometry.triggerRight)).toBeLessThanOrEqual(1);
  expect(Math.abs(geometry.panelWidth - geometry.triggerWidth)).toBeLessThanOrEqual(1);

  const triggerGlow = await trigger.evaluate((node) => {
    const style = getComputedStyle(node);
    return {
      borderColor: style.borderTopColor,
      boxShadow: style.boxShadow
    };
  });

  expect(triggerGlow.borderColor).toBe("rgba(45, 212, 191, 0.35)");
  expect(triggerGlow.boxShadow).toContain("45, 212, 191");

  const settlementLabel = tooltip.getByText("BTC mark price is above 78,213");
  await expect(settlementLabel).toHaveCSS("white-space", "nowrap");
  await expect(tooltip.getByText("on May 03, 2026 02:00 AM UTC")).toBeVisible();
  await expect(tooltip.getByText("Payouts are in USDH.")).toBeVisible();
  await expect(tooltip.getByText("Learn more")).toHaveCount(0);
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
  await seedDesktopPositionsTableState(page, [
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
  ]);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });

  const monRow = page
    .locator("[data-role='account-tab-rows-viewport'] > div")
    .filter({ hasText: "MON" })
    .first();
  await expect(monRow).toBeVisible();
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
  await seedReadyTradingSession(page);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await seedDesktopPositionsTableState(page, [
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
  ]);
  await forceAssetSelectorBootstrapState(page);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });

  const brentRow = page
    .locator("[data-role='account-tab-rows-viewport'] > div")
    .filter({ hasText: "BRENTOIL" })
    .first();
  await expect(brentRow).toBeVisible();
  await brentRow.locator("[data-position-reduce-trigger='true']").click();
  await expect(page.locator("[data-position-reduce-surface='true']")).toBeVisible();

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

  await seedAssetSelectorMarketsCache(page);
  await visitRoute(page, "/trade/BTC");
  await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
  await waitForIdle(page, { quietMs: 800, timeoutMs: 12_000, pollMs: 50 });
  await freezeAccountSurfaceSync(page, SPECTATE_ADDRESS);
  await seedFundingTooltipLivePositionState(page, { position: livePosition });

  const tooltipTrigger = page.locator('[data-role="active-asset-funding-trigger"]');
  await expect(tooltipTrigger).toHaveCount(1);
  await tooltipTrigger.click({ force: true });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const tooltip = page.locator('[data-role="active-asset-funding-tooltip"]');
  const positionSection = tooltip.locator('[data-role="active-asset-funding-position-section"]');
  await expect(positionSection).toHaveAttribute("data-position-mode", "live");
  await expect(tooltip.getByRole("heading", { name: "Your Position" })).toBeVisible();
  await expect(tooltip.getByRole("button", { name: "Edit estimate" })).toBeVisible();

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
    await expect(sheet.getByText("Past Rate Correlation")).toBeVisible();

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
    expect(bodyHasInternalScroll).toBe(false);
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

  await dispatch(page, [":actions/select-order-entry-mode", ":limit"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-input-mode", ":base"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/update-order-form", [":price"], "100"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-display", "1"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await page.locator('[data-parity-id="trade-submit-order-button"]').click();
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
