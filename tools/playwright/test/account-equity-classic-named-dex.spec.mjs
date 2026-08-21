import { expect, test } from "@playwright/test";
import { dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

// A classic account can carry its entire book on a HIP-3 dex, leaving the base
// dex's clearinghouse state zeroed. Hyperliquid folds every dex into one
// synthetic state before deriving anything, so its panel reports the whole
// account. Reading `[:webdata2 :clearinghouseState]` alone -- the base dex --
// put four confident zeros on screen over a six-figure book: $0.00 Balance,
// $0.00 Maintenance Margin, 0.00% Cross Margin Ratio and 0.00x Cross Account
// Leverage, which hides real liquidation risk rather than merely being wrong.
//
// The figures below are the shape of `0xb9aebb46919bccbf210537a1f2173690d9ee7af7`
// as of 2026-08-21. It is seeded rather than spectated so this spec keeps
// passing after that wallet closes its positions.
const EQUITY_PANEL = "[data-parity-id='account-equity']";
const SPECTATE_ADDRESS = "0x162cc7c861ebd0c06b3d72319201150482518185";

const XYZ_ACCOUNT_VALUE = "155901.46";
const XYZ_CROSS_NOTIONAL = "45765.92";
const XYZ_MAINTENANCE = "1144.15";
const XYZ_UNREALIZED_PNL = "6743.65";
const SPOT_USDC = "100.0";

// Spectate gives the state an effective account -- account-derived surfaces
// throw from the lifecycle invariants without one -- and the live sync is then
// detached so the seeded snapshot is not overwritten by the real account.
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

async function seedClassicNamedDexAccountState(page, figures) {
  await page.evaluate((f) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const zeroSummary = {
      accountValue: "0.0",
      totalNtlPos: "0.0",
      totalRawUsd: "0.0",
      totalMarginUsed: "0.0"
    };
    const xyzSummary = {
      accountValue: f.accountValue,
      totalNtlPos: f.crossNotional,
      totalRawUsd: "110135.54",
      totalMarginUsed: "15255.31"
    };
    const nextWebdata2 = c.js__GT_clj(
      {
        clearinghouseState: {
          marginSummary: zeroSummary,
          crossMarginSummary: zeroSummary,
          crossMaintenanceMarginUsed: "0.0",
          withdrawable: "0.0",
          assetPositions: []
        }
      },
      opts
    );
    const nextPerpDexStates = c.js__GT_clj(
      {
        xyz: {
          marginSummary: xyzSummary,
          crossMarginSummary: xyzSummary,
          crossMaintenanceMarginUsed: f.maintenance,
          withdrawable: "0.0",
          assetPositions: [
            {
              type: "oneWay",
              position: {
                coin: "xyz:AAPL",
                szi: "180.0",
                positionValue: f.crossNotional,
                entryPx: "220",
                unrealizedPnl: f.unrealizedPnl,
                liquidationPx: "120",
                leverage: { type: "cross", value: 3, rawUsd: "0.0" },
                maxLeverage: 10,
                marginUsed: "15255.31",
                cumFunding: { sinceOpen: "0" }
              }
            }
          ]
        }
      },
      opts
    );
    const nextSpot = c.js__GT_clj(
      { balances: [{ coin: "USDC", hold: "0.0", total: f.spotUsdc, entryNtl: "0.0" }] },
      opts
    );
    // The dex's collateral token is read from its perp markets, so the market
    // catalogue has to know that `xyz` settles in USDC. Assoc'd into the live
    // catalogue rather than replacing it, so every other surface keeps working.
    const xyzMarket = c.js__GT_clj(
      {
        key: "perp:xyz:AAPL",
        coin: "xyz:AAPL",
        "market-type": "perp",
        dex: "xyz",
        base: "AAPL",
        quote: "USDC"
      },
      opts
    );
    const withMarketType = c.assoc(
      xyzMarket,
      keyword("market-type"),
      keyword("perp")
    );
    const current = c.deref(store);
    const marketByKey = c.get_in(current, kwPath("asset-selector", "market-by-key"));
    const seededState = c.assoc_in(
      c.assoc_in(
        c.assoc_in(
          c.assoc_in(
            c.assoc_in(current, kwPath("webdata2"), nextWebdata2),
            kwPath("perp-dex-clearinghouse"),
            nextPerpDexStates
          ),
          kwPath("spot", "clearinghouse-state"),
          nextSpot
        ),
        kwPath("account", "mode"),
        keyword("classic")
      ),
      kwPath("asset-selector", "market-by-key"),
      c.assoc(marketByKey || c.PersistentArrayMap.EMPTY, "perp:xyz:AAPL", withMarketType)
    );

    c.reset_BANG_(store, seededState);
  }, figures);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

test.describe("classic account equity with the whole book on a named dex", () => {
  test("reports the aggregate account rather than the empty base dex", async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    await visitRoute(page, "/trade");
    await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
    // Let the real spectate fetches land before detaching sync, otherwise a
    // late response silently overwrites the seed and the panel renders the
    // real account instead.
    await waitForIdle(page, { quietMs: 800, timeoutMs: 12_000, pollMs: 50 });
    await freezeAccountSurfaceSync(page, SPECTATE_ADDRESS);
    await seedClassicNamedDexAccountState(page, {
      accountValue: XYZ_ACCOUNT_VALUE,
      crossNotional: XYZ_CROSS_NOTIONAL,
      maintenance: XYZ_MAINTENANCE,
      unrealizedPnl: XYZ_UNREALIZED_PNL,
      spotUsdc: SPOT_USDC
    });

    const panel = page.locator(EQUITY_PANEL).first();
    await expect(panel).toBeVisible();

    const text = (await panel.innerText()).replace(/\s+/g, " ");

    // Perps is the aggregate account value; Balance is that net of unrealized
    // PNL, which is what our own tooltip already promises.
    expect(text).toContain("$155,901.46");
    expect(text).toContain("$149,157.81");
    expect(text).toContain("+$6,743.65");
    // Account Value must reconcile with the two rows above it: 100 + 155,901.46.
    expect(text).toContain("$156,001.46");
    expect(text).toContain("$100.00");
    // 1,144.15 / 155,901.46 and 45,765.92 / 155,901.46.
    expect(text).toContain("$1,144.15");
    expect(text).toContain("0.73%");
    expect(text).toContain("0.29x");

    // The regression itself: none of these rows may read as an empty account.
    expect(text).not.toContain("$0.00");
    expect(text).not.toContain("0.00%");
    expect(text).not.toContain("0.00x");
    expect(text).not.toContain("--");
  });
});
