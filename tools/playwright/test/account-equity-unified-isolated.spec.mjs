import { expect, test } from "@playwright/test";
import { dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

// Hyperliquid reports isolated positions only inside `marginSummary`, leaving
// `crossMarginSummary.totalNtlPos` and `crossMaintenanceMarginUsed` at zero. A
// unified account whose whole book is isolated therefore used to render 0.00x
// leverage, 0.00% ratio and $0.00 maintenance margin over a real position book.
const EQUITY_PANEL = "[data-parity-id='account-equity']";
const SPECTATE_ADDRESS = "0x162cc7c861ebd0c06b3d72319201150482518185";

// Spectate gives the state an effective account (account-derived surfaces are
// asserted empty without one), then the live sync is detached so the seeded
// snapshot is not overwritten by the real account's data.
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

async function seedUnifiedIsolatedAccountState(page) {
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
    const isolated = (coin, positionValue, marginUsed, maxLeverage) => ({
      type: "oneWay",
      position: {
        coin,
        szi: "1.0",
        positionValue,
        entryPx: "1",
        unrealizedPnl: "0.0",
        liquidationPx: "0.5",
        leverage: { type: "isolated", value: 3, rawUsd: "0.0" },
        maxLeverage,
        marginUsed,
        cumFunding: { sinceOpen: "0" }
      }
    });
    const nextWebdata2 = c.js__GT_clj(
      {
        clearinghouseState: {
          marginSummary: {
            accountValue: "1000.0",
            totalNtlPos: "1700.0",
            totalRawUsd: "1000.0",
            totalMarginUsed: "733.0"
          },
          crossMarginSummary: {
            accountValue: "0.0",
            totalNtlPos: "0.0",
            totalRawUsd: "0.0",
            totalMarginUsed: "0.0"
          },
          crossMaintenanceMarginUsed: "0.0",
          withdrawable: "0.0",
          assetPositions: [
            isolated("BTC", "700.0", "233.0", 40),
            isolated("PUMP", "1000.0", "500.0", 10)
          ]
        }
      },
      opts
    );
    const nextSpot = c.js__GT_clj(
      {
        balances: [{ coin: "USDC", hold: "733.0", total: "1000.0", entryNtl: "0.0" }]
      },
      opts
    );
    const seededState = c.assoc_in(
      c.assoc_in(
        c.assoc_in(c.deref(store), kwPath("webdata2"), nextWebdata2),
        kwPath("spot", "clearinghouse-state"),
        nextSpot
      ),
      kwPath("account", "mode"),
      keyword("unified")
    );

    c.reset_BANG_(store, seededState);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

test.describe("unified account summary with an all-isolated book", () => {
  test("reports whole-book leverage and maintenance instead of zeros", async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    await visitRoute(page, "/trade");
    await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
    // Let the real spectate fetches land before detaching sync, otherwise a
    // late response overwrites the seeded snapshot.
    await waitForIdle(page, { quietMs: 800, timeoutMs: 12_000, pollMs: 50 });
    await freezeAccountSurfaceSync(page, SPECTATE_ADDRESS);
    await seedUnifiedIsolatedAccountState(page);

    const panel = page.locator(EQUITY_PANEL).first();
    await expect(panel).toBeVisible();

    const text = (await panel.innerText()).replace(/\s+/g, " ");

    // 1700 notional over 1000 collateral: the cross-only numerator read 0.00x.
    expect(text).toContain("1.70x");
    // Maintenance rate is 1 / (2 * maxLeverage): 700/80 + 1000/20.
    expect(text).toContain("$58.75");
    // Isolated positions liquidate individually, so a portfolio-liquidation
    // ratio has nothing to say; "--" beats a 0% that reads as no risk.
    expect(text).not.toContain("0.00%");
    expect(text).not.toContain("0.00x");
  });
});
