import { expect, test } from "../support/guarded_test.mjs";
import { dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import { hyperEvmFixture } from "../support/hyperevm_fixtures.mjs";

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
const INFO_URL = "https://api.hyperliquid.xyz/info";

const XYZ_ACCOUNT_VALUE = "155901.46";
const XYZ_CROSS_NOTIONAL = "45765.92";
const XYZ_MAINTENANCE = "1144.15";
const XYZ_UNREALIZED_PNL = "6743.65";
const SPOT_USDC = "100.0";
const VAULT_EQUITY = "12500.0";
const HYPEREVM_USD = "56538.39";
const VIEWPORTS = [
  { width: 375, height: 812 },
  { width: 768, height: 1024 },
  { width: 1280, height: 900 },
  { width: 1440, height: 900 }
];

function displayCents(value) {
  return Math.round(Number(String(value).replace(/[$,]/g, "")) * 100);
}

function emptyClearinghouseState() {
  return {
    marginSummary: {
      accountValue: "0.0",
      totalNtlPos: "0.0",
      totalRawUsd: "0.0",
      totalMarginUsed: "0.0"
    },
    crossMarginSummary: {
      accountValue: "0.0",
      totalNtlPos: "0.0",
      totalRawUsd: "0.0",
      totalMarginUsed: "0.0"
    },
    crossMaintenanceMarginUsed: "0.0",
    withdrawable: "0.0",
    assetPositions: []
  };
}

function namedDexClearinghouseState() {
  const summary = {
    accountValue: XYZ_ACCOUNT_VALUE,
    totalNtlPos: XYZ_CROSS_NOTIONAL,
    totalRawUsd: "110135.54",
    totalMarginUsed: "15255.31"
  };
  return {
    marginSummary: summary,
    crossMarginSummary: summary,
    crossMaintenanceMarginUsed: XYZ_MAINTENANCE,
    withdrawable: "0.0",
    assetPositions: [
      {
        type: "oneWay",
        position: {
          coin: "xyz:AAPL",
          szi: "180.0",
          positionValue: XYZ_CROSS_NOTIONAL,
          entryPx: "220",
          unrealizedPnl: XYZ_UNREALIZED_PNL,
          liquidationPx: "120",
          leverage: { type: "cross", value: 3, rawUsd: "0.0" },
          maxLeverage: 10,
          marginUsed: "15255.31",
          cumFunding: { sinceOpen: "0" }
        }
      }
    ]
  };
}

// The assertions below use a complete seed. Route every initial /info request
// deterministically so an account bootstrap response cannot arrive after sync
// is detached and replace a seeded value mid-viewport loop.
async function stubSeededAccountInfoRequests(page, address) {
  await page.route(INFO_URL, async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestAddress = String(payload?.user || "").toLowerCase();
    if (requestAddress !== address.toLowerCase()) {
      await route.continue();
      return;
    }
    let body;

    switch (payload?.type) {
      case "clearinghouseState":
        body = payload?.dex === "xyz" ? namedDexClearinghouseState() : emptyClearinghouseState();
        break;
      case "spotClearinghouseState":
        body = { balances: [{ coin: "USDC", hold: "0.0", total: SPOT_USDC, entryNtl: "0.0" }] };
        break;
      case "webData2":
        body = { clearinghouseState: emptyClearinghouseState(), spotAssetCtxs: [] };
        break;
      case "userVaultEquities":
        body = [{ vaultAddress: "0xvault-a", equity: VAULT_EQUITY }];
        break;
      default:
        await route.continue();
        return;
    }

    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body)
    });
  });
}

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
    globalThis.hyperopen?.vaults?.infrastructure?.user_equity_poller?.stop_user_equity_poller_BANG_?.();
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
    const nextVaults = c.js__GT_clj(f.vaults, opts);
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
            c.assoc_in(
              c.assoc_in(current, kwPath("webdata2"), nextWebdata2),
              kwPath("perp-dex-clearinghouse"),
              nextPerpDexStates
            ),
            kwPath("spot", "clearinghouse-state"),
            nextSpot
          ),
          kwPath("vaults"),
          nextVaults
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

// The compact desktop card includes its actual funding controls only for a
// connected owner. Spectate intentionally suppresses those controls, which
// gives a false amount of room below the Account Equity summary.
async function seedConnectedClassicOwner(page, address) {
  await page.evaluate((ownerAddress) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;
    if (!c || !store) throw new Error("Hyperopen store or cljs core unavailable");

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    let next = c.deref(store);
    next = c.assoc_in(next, kwPath("wallet", "connected?"), true);
    next = c.assoc_in(next, kwPath("wallet", "address"), ownerAddress);
    next = c.assoc_in(next, kwPath("account-context", "spectate-mode", "active?"), false);
    next = c.assoc_in(next, kwPath("account-context", "spectate-mode", "address"), null);
    c.reset_BANG_(store, next);
  }, address);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function readPanelRows(page) {
  return page.evaluate((selector) => {
    const panel = document.querySelector(selector);
    if (!panel) throw new Error("account equity panel not rendered");
    const rows = {};
    for (const value of panel.querySelectorAll("span.num")) {
      const row = value.parentElement;
      const label = row?.querySelector("span")?.textContent?.trim();
      if (label) rows[label] = value.textContent.trim();
    }
    const hyperEvmValue = panel.querySelector("[data-role='account-equity-hyperevm-value']");
    if (hyperEvmValue) rows.HyperEVM = hyperEvmValue.textContent.trim();
    return rows;
  }, EQUITY_PANEL);
}

async function expectNoAccountPanelOverlap(page, viewport) {
  const panel = page.locator(EQUITY_PANEL).filter({ visible: true }).first();
  await expect(panel).toBeVisible();
  const geometry = await panel.evaluate((node) => {
    const panelRect = node.getBoundingClientRect();
    const visibleRows = [...node.querySelectorAll("span.num")]
      .map((value) => value.parentElement?.getBoundingClientRect())
      .filter(Boolean);
    return {
      viewportWidth: window.innerWidth,
      panelLeft: panelRect.left,
      panelRight: panelRect.right,
      panelWidth: panelRect.width,
      rowsOverlap: visibleRows.some((row, index) =>
        visibleRows.slice(index + 1).some(
          (other) => row.top < other.bottom && other.top < row.bottom
        )
      )
    };
  });

  expect(geometry.viewportWidth, `${viewport.width}px viewport applied`).toBe(viewport.width);
  expect(geometry.panelWidth, `${viewport.width}px account panel has width`).toBeGreaterThan(0);
  expect(geometry.panelLeft, `${viewport.width}px account panel stays onscreen`).toBeGreaterThanOrEqual(-1);
  expect(geometry.panelRight, `${viewport.width}px account panel stays onscreen`).toBeLessThanOrEqual(
    geometry.viewportWidth + 1
  );
  expect(geometry.rowsOverlap, `${viewport.width}px metric rows do not overlap`).toBeFalsy();
}

async function expectAccountEquitySummaryContainment(page, viewport) {
  const panel = page.locator(EQUITY_PANEL).filter({ visible: true }).first();
  await expect(panel).toBeVisible();
  const geometry = await panel.evaluate((node) => {
    const summaryLabels = new Set(["Total Account Value", "Spot", "Perps", "Vaults"]);
    const panelRect = node.getBoundingClientRect();
    const footerRect = document.querySelector("footer")?.getBoundingClientRect();
    const rows = Object.fromEntries(
      [...node.querySelectorAll("span.num")]
        .map((value) => {
          const row = value.parentElement;
          const label = row?.querySelector("span")?.textContent?.trim();
          return [label, row?.getBoundingClientRect()];
        })
        .filter(([label, rect]) => summaryLabels.has(label) && rect)
        .map(([label, rect]) => [label, { top: rect.top, bottom: rect.bottom }])
    );
    const hyperEvmValue = node.querySelector("[data-role='account-equity-hyperevm-value']");
    const hyperEvmRow = hyperEvmValue?.closest("[data-role='account-equity-hyperevm-line']");
    if (hyperEvmRow) {
      rows.HyperEVM = { top: hyperEvmRow.getBoundingClientRect().top, bottom: hyperEvmRow.getBoundingClientRect().bottom };
    }
    return {
      panelTop: panelRect.top,
      panelBottom: panelRect.bottom,
      footerTop: footerRect?.top ?? null,
      viewportBottom: window.innerHeight,
      rows
    };
  });

  for (const label of ["Total Account Value", "Spot", "Perps", "Vaults", "HyperEVM"]) {
    const row = geometry.rows[label];
    expect(row, `${viewport.width}px ${label} row is rendered`).toBeTruthy();
    expect(row.top, `${viewport.width}px ${label} stays inside the panel`).toBeGreaterThanOrEqual(
      geometry.panelTop - 1
    );
    expect(row.bottom, `${viewport.width}px ${label} stays inside the panel`).toBeLessThanOrEqual(
      geometry.panelBottom + 1
    );
  }

  if (viewport.width >= 1280) {
    expect(geometry.footerTop, `${viewport.width}px fixed footer is rendered`).not.toBeNull();
    expect(geometry.rows.HyperEVM.bottom, `${viewport.width}px HyperEVM keeps an 8px footer clearance`).toBeLessThanOrEqual(
      geometry.footerTop - 8
    );
  } else {
    expect(geometry.rows.HyperEVM.bottom, `${viewport.width}px HyperEVM stays in the visible mobile account surface`).toBeLessThanOrEqual(
      geometry.viewportBottom + 1
    );
  }
}

async function expectHyperEvmMoveTarget(page, viewport) {
  const move = page.locator("[data-role='account-equity-hyperevm-move']").filter({ visible: true }).first();
  await expect(move).toBeVisible();
  const box = await move.boundingBox();
  expect(box, `${viewport.width}px HyperEVM Move target has a box`).toBeTruthy();
  expect(box.width, `${viewport.width}px HyperEVM Move target is at least 24px wide`).toBeGreaterThanOrEqual(24);
  expect(box.height, `${viewport.width}px HyperEVM Move target is at least 24px tall`).toBeGreaterThanOrEqual(24);
}

test.describe("classic account equity with the whole book on a named dex", () => {
  test("reports the aggregate account rather than the empty base dex", async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    await stubSeededAccountInfoRequests(page, SPECTATE_ADDRESS);
    await visitRoute(page, "/trade", {
      hyperEvmFixture: hyperEvmFixture({ owner: SPECTATE_ADDRESS, holdings: { USDC: HYPEREVM_USD } })
    });
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
      spotUsdc: SPOT_USDC,
      vaults: {
        "user-equities": [
          { "vault-address": "0xvault-a", equity: Number(VAULT_EQUITY), "equity-raw": VAULT_EQUITY }
        ],
        "user-equities-for-address": SPECTATE_ADDRESS,
        loading: { "user-equities?": false },
        errors: { "user-equities": null }
      }
    });
    await seedConnectedClassicOwner(page, SPECTATE_ADDRESS);

    const panel = page.locator(EQUITY_PANEL).first();
    await expect(panel).toBeVisible();
    await expect(
      page.locator("[data-role='account-equity-hyperevm-line']").filter({ visible: true }).first()
    ).toContainText("Not margin");

    const text = (await panel.innerText()).replace(/\s+/g, " ");

    // Perps is the aggregate account value; Balance is that net of unrealized
    // PNL, which is what our own tooltip already promises.
    expect(text).toContain("$155,901.46");
    expect(text).toContain("$149,157.81");
    expect(text).toContain("+$6,743.65");
    // Account Value retains its existing Spot + Perps semantics. The grouped
    // total also includes account-owned, fully priced HyperEVM assets:
    // 100 + 155,901.46 + 12,500 + 56,538.39.
    expect(text).toContain("$225,039.85");
    expect(text).toContain("$12,500.00");
    expect(text).toContain("$56,538.39");
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

    const rows = await readPanelRows(page);
    expect(rows).toMatchObject({
      "Total Account Value": "$225,039.85",
      Spot: "$100.00",
      Perps: "$155,901.46",
      Vaults: "$12,500.00",
      HyperEVM: "$56,538.39"
    });
    // The old two-part figure remains available to callers that use named-dex
    // rows; the visible total reconciles all four owned components.
    expect(displayCents(rows["Total Account Value"])).toBe(
      displayCents(rows.Spot) +
        displayCents(rows.Perps) +
        displayCents(rows.Vaults) +
        displayCents(rows.HyperEVM)
    );

    for (const viewport of VIEWPORTS) {
      await page.setViewportSize(viewport);
      if (viewport.width <= 768) {
        await dispatch(page, [":actions/select-trade-mobile-surface", ":account"]);
      }
      await expectNoAccountPanelOverlap(page, viewport);
      await expectAccountEquitySummaryContainment(page, viewport);
      await expectHyperEvmMoveTarget(page, viewport);
    }

    // The compact Account Equity line remains operable without a pointer and
    // returns focus to its actual opener after its transfer modal closes.
    const hyperEvmMove = page.locator("[data-role='account-equity-hyperevm-move']").filter({ visible: true }).first();
    await hyperEvmMove.focus();
    await expect(hyperEvmMove).toBeFocused();
    await page.keyboard.press("Enter");
    const fundingModal = page.locator("[data-role='funding-modal']");
    await expect(fundingModal).toBeVisible();
    await expect(page.locator("[data-role='funding-transfer-from-hyperevm']")).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("[data-role='funding-transfer-to-spot']")).toHaveAttribute("aria-pressed", "true");
    await page.keyboard.press("Escape");
    await expect(fundingModal).toHaveCount(0);
    await expect(hyperEvmMove).toBeFocused();

    await seedClassicNamedDexAccountState(page, {
      accountValue: XYZ_ACCOUNT_VALUE,
      crossNotional: XYZ_CROSS_NOTIONAL,
      maintenance: XYZ_MAINTENANCE,
      unrealizedPnl: XYZ_UNREALIZED_PNL,
      spotUsdc: SPOT_USDC,
      vaults: {
        "user-equities": [],
        "user-equities-for-address": SPECTATE_ADDRESS,
        loading: { "user-equities?": false },
        errors: { "user-equities": null }
      }
    });
    await expect.poll(() => readPanelRows(page)).toMatchObject({
      "Total Account Value": "$212,539.85",
      Vaults: "$0.00"
    });

    await seedClassicNamedDexAccountState(page, {
      accountValue: XYZ_ACCOUNT_VALUE,
      crossNotional: XYZ_CROSS_NOTIONAL,
      maintenance: XYZ_MAINTENANCE,
      unrealizedPnl: XYZ_UNREALIZED_PNL,
      spotUsdc: SPOT_USDC,
      vaults: {
        "user-equities": [],
        "user-equities-for-address": null,
        loading: { "user-equities?": true },
        errors: { "user-equities": null }
      }
    });
    await expect.poll(() => readPanelRows(page)).toMatchObject({
      "Total Account Value": "--",
      Vaults: "--",
      Spot: "$100.00",
      Perps: "$155,901.46"
    });
  });

  test("reconciles the supplied classic account snapshot across Spot, Perps, Vaults, and HyperEVM", async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    await stubSeededAccountInfoRequests(page, SPECTATE_ADDRESS);
    await visitRoute(page, "/trade", {
      hyperEvmFixture: hyperEvmFixture({ owner: SPECTATE_ADDRESS, holdings: { USDC: HYPEREVM_USD } })
    });
    await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
    await waitForIdle(page, { quietMs: 800, timeoutMs: 12_000, pollMs: 50 });
    await freezeAccountSurfaceSync(page, SPECTATE_ADDRESS);
    await seedClassicNamedDexAccountState(page, {
      accountValue: "0.01",
      crossNotional: "0.0",
      maintenance: "0.0",
      unrealizedPnl: "0.0",
      spotUsdc: "224892.08",
      vaults: {
        "user-equities": [
          { "vault-address": "0xvault-a", equity: 23028.52, "equity-raw": "23028.52" }
        ],
        "user-equities-for-address": SPECTATE_ADDRESS,
        loading: { "user-equities?": false },
        errors: { "user-equities": null }
      }
    });
    await seedConnectedClassicOwner(page, SPECTATE_ADDRESS);

    await expect.poll(() => readPanelRows(page)).toMatchObject({
      "Total Account Value": "$304,459.00",
      Spot: "$224,892.08",
      Perps: "$0.01",
      Vaults: "$23,028.52",
      HyperEVM: "$56,538.39"
    });
    const rows = await readPanelRows(page);
    expect(displayCents(rows["Total Account Value"])).toBe(
      displayCents(rows.Spot) +
        displayCents(rows.Perps) +
        displayCents(rows.Vaults) +
        displayCents(rows.HyperEVM)
    );
  });

  test("keeps a confirmed empty HyperEVM wallet in the classic total as zero", async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    await stubSeededAccountInfoRequests(page, SPECTATE_ADDRESS);
    await visitRoute(page, "/trade", {
      hyperEvmFixture: hyperEvmFixture({ owner: SPECTATE_ADDRESS, holdings: {} })
    });
    await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
    await waitForIdle(page, { quietMs: 800, timeoutMs: 12_000, pollMs: 50 });
    await freezeAccountSurfaceSync(page, SPECTATE_ADDRESS);
    await seedClassicNamedDexAccountState(page, {
      accountValue: XYZ_ACCOUNT_VALUE,
      crossNotional: XYZ_CROSS_NOTIONAL,
      maintenance: XYZ_MAINTENANCE,
      unrealizedPnl: XYZ_UNREALIZED_PNL,
      spotUsdc: SPOT_USDC,
      vaults: {
        "user-equities": [
          { "vault-address": "0xvault-a", equity: Number(VAULT_EQUITY), "equity-raw": VAULT_EQUITY }
        ],
        "user-equities-for-address": SPECTATE_ADDRESS,
        loading: { "user-equities?": false },
        errors: { "user-equities": null }
      }
    });
    await seedConnectedClassicOwner(page, SPECTATE_ADDRESS);

    await expect.poll(() => readPanelRows(page)).toMatchObject({
      "Total Account Value": "$168,501.46",
      Spot: "$100.00",
      Perps: "$155,901.46",
      Vaults: "$12,500.00"
    });
    const rows = await readPanelRows(page);
    expect(rows.HyperEVM, "an empty wallet does not invent a visible $0.00 line").toBeUndefined();
  });

  test("keeps the classic total unknown when a held HyperEVM token is unpriced", async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    await stubSeededAccountInfoRequests(page, SPECTATE_ADDRESS);
    await visitRoute(page, "/trade", {
      // JOFF has a readable on-chain balance but no spot market in this
      // fixture. It is a fully completed read with an explicitly unpriced
      // holding, not a missing RPC response.
      hyperEvmFixture: hyperEvmFixture({
        owner: SPECTATE_ADDRESS,
        holdings: { JOFF: "1" },
        overrides: { revertingTokens: [] }
      })
    });
    await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
    await waitForIdle(page, { quietMs: 800, timeoutMs: 12_000, pollMs: 50 });
    await freezeAccountSurfaceSync(page, SPECTATE_ADDRESS);
    await seedClassicNamedDexAccountState(page, {
      accountValue: XYZ_ACCOUNT_VALUE,
      crossNotional: XYZ_CROSS_NOTIONAL,
      maintenance: XYZ_MAINTENANCE,
      unrealizedPnl: XYZ_UNREALIZED_PNL,
      spotUsdc: SPOT_USDC,
      vaults: {
        "user-equities": [
          { "vault-address": "0xvault-a", equity: Number(VAULT_EQUITY), "equity-raw": VAULT_EQUITY }
        ],
        "user-equities-for-address": SPECTATE_ADDRESS,
        loading: { "user-equities?": false },
        errors: { "user-equities": null }
      }
    });
    await seedConnectedClassicOwner(page, SPECTATE_ADDRESS);

    await expect.poll(() => readPanelRows(page)).toMatchObject({
      "Total Account Value": "--",
      Spot: "$100.00",
      Perps: "$155,901.46",
      Vaults: "$12,500.00",
      HyperEVM: "Unpriced"
    });
  });
});
