import { expect } from "./guarded_test.mjs";
import { debugCall, dispatch, expectOracle, visitRoute, waitForDebugBridge, waitForIdle } from "./hyperopen.mjs";
import { installWsSimulator } from "./ws_simulator.mjs";

// Shared browser world for the Vol Range execution specs (HO-SPEC-014 v2.1 sections 8-10, 15.2).
//
// Placement is on in the dev build unless `localStorage hyperopen.volRange.execution = off`
// is set before the page loads. The fixture wallet address is connected through the wallet
// simulator so owner/active account match the seeded state. Barrier reads (balances, open
// orders, l2Book, userFees, orderStatus, userFillsByTime) go through the api service
// `fetch` to /info and are answered by a stateful page.route stub (`world`). Signed actions
// are answered by the debug exchange simulator, which records each request; a page route
// aborts anything that would still reach a real `/exchange` endpoint.
//
// Every Vol Range execution spec calls `bootstrap(page, world)` before its first navigation,
// so every page route is installed in one place, including the WebSocket simulator
// (`ws_simulator.mjs`, handle in `world.ws`) that owns the page's Hyperliquid socket and
// answers the account stream subscriptions. Placement and repair are refused until live
// monitoring is `:live` (spec 9.3), so `startTrade` (bootstrap plus load, wallet and fixture
// seed) and `reloadAndRebootstrap` end by waiting for it (`waitForLiveMonitoring`).

export const ADDRESS = "0x1234567890abcdef1234567890abcdef12345678";
export const COIN = "@9001";
const INFO_URL = "**/info";
const EXCHANGE_URL = "**/exchange";

export const ticket = (page) => page.locator('[data-parity-id="order-form"]').first();

/** Oid the stubbed venue gives order `index` of the `callIndex`-th signed order action. */
export const venueOid = (callIndex, index) => 7000 + callIndex * 100 + index + 1;

export function newWorld() {
  return {
    // null: derive resting orders from the simulator's recorded order actions,
    // keeping the orders the venue accepted.
    openOrders: null,
    // (orderIndex, callIndex) -> did the venue accept that order of that order action?
    accepted: () => true,
    // Top of the stubbed l2Book.
    book: { bid: "79.99", ask: "80.01" },
    // id (oid string or cloid) -> payload
    orderStatus: () => ({ status: "unknownOid" }),
    infoLog: [],
    // Requests that tried to reach a real /exchange endpoint (aborted).
    blockedExchange: [],
    // The WebSocket simulator handle, set by `bootstrap`.
    ws: null
  };
}

export function openOrderRows(orderAction, { oidBase = 7000 } = {}) {
  return orderAction.orders.map((o, i) => ({
    coin: COIN,
    side: o.b ? "B" : "A",
    limitPx: o.p,
    sz: o.s,
    origSz: o.s,
    oid: oidBase + i + 1,
    timestamp: Date.now(),
    cloid: o.c,
    orderType: "Limit",
    tif: "Alo",
    reduceOnly: false,
    isTrigger: false,
    isPositionTpsl: false,
    triggerPx: "0.0",
    triggerCondition: "N/A",
    children: []
  }));
}

async function restingFromSimulator(page, world) {
  const snap = await page.evaluate(() => window.HYPEROPEN_DEBUG?.exchangeSimulatorSnapshot?.()).catch(() => null);
  const calls = (snap?.calls || []).filter((c) => c.request?.action?.type === "order");
  const rows = [];
  calls.forEach((call, callIndex) => {
    call.request.action.orders.forEach((o, i) => {
      if (world.accepted(i, callIndex)) {
        rows.push(...openOrderRows({ orders: [o] }, { oidBase: venueOid(callIndex, i) - 1 }));
      }
    });
  });
  return rows;
}

/**
 * The venue's open orders: `world.openOrders` when a test set them, else the resting
 * orders of every accepted signed order action. Like the venue, once an order action was
 * accepted its resting orders are visible to every later read (background reconciliation
 * can run at any time).
 */
async function openOrdersNow(page, world) {
  return world.openOrders ?? (await restingFromSimulator(page, world));
}

/** Balance holds for `rows` (open order rows), as decimal strings with 8 places. */
function venueHolds(rows) {
  let usdc = 0;
  let base = 0;
  for (const r of rows) {
    if (r.side === "B") usdc += Number(r.limitPx) * Number(r.sz);
    else base += Number(r.sz);
  }
  return { usdc: usdc.toFixed(8), base: base.toFixed(8) };
}

export async function stubInfo(page, world) {
  await page.route(INFO_URL, async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const user = String(payload?.user || "").toLowerCase();
    const forFixture = user === ADDRESS;
    const isBook = payload?.type === "l2Book" && payload?.coin === COIN;
    if (!forFixture && !isBook) {
      await route.continue();
      return;
    }
    world.infoLog.push(payload.type);
    let body;
    switch (payload.type) {
      case "spotClearinghouseState": {
        // Like the venue: resting buys hold their USDC notional and resting sells their size.
        const holds = venueHolds(await openOrdersNow(page, world));
        body = {
          balances: [
            { coin: "USDC", token: 0, hold: holds.usdc, total: "3000", entryNtl: "0" },
            { coin: "VRT", token: 9001, hold: holds.base, total: "150", entryNtl: "0" }
          ]
        };
        break;
      }
      case "frontendOpenOrders":
        body = await openOrdersNow(page, world);
        break;
      case "l2Book":
        body = {
          coin: COIN,
          time: Date.now(),
          levels: [
            [{ px: world.book.bid, sz: "100", n: 1 }],
            [{ px: world.book.ask, sz: "100", n: 1 }]
          ]
        };
        break;
      case "userFees":
        body = {
          userCrossRate: "0.00045",
          userAddRate: "0.00015",
          userSpotCrossRate: "0.0007",
          userSpotAddRate: "0.0004",
          activeReferralDiscount: "0.0",
          activeStakingDiscount: { bpsOfMaxSupply: "0.0", discount: "0.0" },
          dailyUserVlm: []
        };
        break;
      case "orderStatus":
        body = world.orderStatus(String(payload.oid));
        break;
      case "userFillsByTime":
      case "userFills":
        body = [];
        break;
      case "userAbstraction":
        body = "default";
        break;
      case "clearinghouseState":
      case "webData2":
        body = { assetPositions: [], marginSummary: {}, crossMarginSummary: {}, spotAssetCtxs: [] };
        break;
      default:
        await route.continue();
        return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });
}

/** Records every POST that targets an /exchange URL (the safety net aborts them). */
export function trackExchange(page) {
  const urls = [];
  page.on("request", (request) => {
    if (request.url().includes("/exchange") && request.method() === "POST") urls.push(request.url());
  });
  return urls;
}

async function armInitScript(page, { armed = true, cap = null } = {}) {
  await page.addInitScript(
    ({ armed: a, cap: c }) => {
      try {
        // Defaults: placement on, no cap.
        if (a) window.localStorage.removeItem("hyperopen.volRange.execution");
        else window.localStorage.setItem("hyperopen.volRange.execution", "off");
        if (c) window.localStorage.setItem("hyperopen.volRange.maxLadderUsdc", c);
        else window.localStorage.removeItem("hyperopen.volRange.maxLadderUsdc");
      } catch (_e) { /* storage unavailable */ }
    },
    { armed, cap }
  );
}

/**
 * Page setup every Vol Range execution spec runs before its first navigation: the
 * placement init script, the exchange safety net, the stateful /info stub and the
 * WebSocket simulator (`world.ws`).
 */
export async function bootstrap(page, world, { armed = true, cap = null } = {}) {
  await armInitScript(page, { armed, cap });
  world.ws = await installWsSimulator(page, { address: ADDRESS });
  // Safety net: a signed action the debug simulator does not answer would fall through to
  // the real exchange. Never let it leave the browser.
  await page.route(EXCHANGE_URL, async (route) => {
    world.blockedExchange.push(route.request().url());
    await route.abort();
  });
  await stubInfo(page, world);
}

export async function loadTrade(page) {
  let ready = false;
  for (let attempt = 0; attempt < 4 && !ready; attempt++) {
    if (attempt > 0) await page.waitForTimeout(1500);
    try {
      await visitRoute(page, "/trade");
      ready = true;
    } catch (error) {
      if (attempt === 3) throw error;
    }
  }
}

export async function installExchange(page, signedActions = {}) {
  await debugCall(page, "installExchangeSimulator", {
    approveAgent: { responses: [{ status: "ok" }] },
    signedActions
  });
}

export async function connectAndEnable(page) {
  await installExchange(page);
  await debugCall(page, "installWalletSimulator", {
    accounts: [ADDRESS],
    requestAccounts: [ADDRESS],
    chainId: "0xa4b1"
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");
  await dispatch(page, [":actions/connect-wallet"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", { connected: true, address: ADDRESS });
  await dispatch(page, [":actions/enable-agent-trading"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 5_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", { agentStatus: "ready", agentError: null });
}

export async function seedTicket(page) {
  const heading = ticket(page).getByRole("heading", { name: "Accumulate buys" });
  let seeded = false;
  for (let attempt = 0; attempt < 8 && !seeded; attempt++) {
    await debugCall(page, "seedVolRangeFixture");
    await waitForIdle(page);
    await page.waitForTimeout(500);
    seeded = await heading.isVisible();
  }
  await expect(heading).toBeVisible({ timeout: 15_000 });
}

/** `[:vol-range :execution :monitoring :status]` as a string ("live", ...), or null. */
export async function monitoringStatus(page) {
  await waitForDebugBridge(page);
  return page.evaluate(() => {
    const execution = globalThis.HYPEROPEN_DEBUG?.snapshot?.()?.["app-state"]?.["vol-range"]?.execution;
    return execution?.monitoring?.status ?? null;
  });
}

/**
 * Wait until live monitoring is `:live`: the simulator answered the account stream
 * subscriptions and the resync finished with complete reads (spec 9.3).
 */
export async function waitForLiveMonitoring(page, { timeout = 30_000 } = {}) {
  await expect.poll(() => monitoringStatus(page), { timeout, intervals: [100, 250, 500] }).toBe("live");
}

/** Full start: bootstrap, load /trade, connect + enable agent, seed the fixture. */
export async function startTrade(page, world, options = {}) {
  await bootstrap(page, world, options);
  await loadTrade(page);
  await connectAndEnable(page);
  await seedTicket(page);
  await waitForLiveMonitoring(page);
}

/** After a reload: wallet + agent + fixture again (journal survives in IndexedDB). */
export async function reloadAndRebootstrap(page) {
  await loadTrade(page);
  await connectAndEnable(page);
  await seedTicket(page);
  await waitForLiveMonitoring(page);
}

export const okOrderResponse = (statuses) => ({
  status: "ok",
  response: { type: "order", data: { statuses } }
});
export const resting = (n) => ({ resting: { oid: 7000 + n } });
export const restingAll = () => okOrderResponse(Array.from({ length: 7 }, (_, i) => resting(i + 1)));
export const okCancelResponse = () => ({
  status: "ok",
  response: { type: "cancel", data: { statuses: Array.from({ length: 7 }, () => "success") } }
});

export async function exchangeCalls(page) {
  const snap = await debugCall(page, "exchangeSimulatorSnapshot");
  return (snap?.calls || []).filter((c) => c.matchedPath?.[0] === "signedActions");
}
export const actionsOfType = (calls, type) =>
  calls.filter((c) => c.request?.action?.type === type).map((c) => c.request.action);

export async function openReview(page) {
  await ticket(page).getByRole("button", { name: "Review 7 orders" }).click();
  await expect(page.locator('[data-role="vol-range-review"]')).toBeVisible();
}

export async function openVolLaddersTab(page) {
  await page.getByRole("button", { name: "Vol ladders" }).first().click().catch(async () => {
    await page.getByText("Vol ladders", { exact: true }).first().click();
  });
  await expect(page.locator('[data-role="vol-ladder-dock"]')).toBeVisible();
}

export const row = (page) => page.locator('[data-role="vol-ladder-row"]');
export const statusLabel = (page) => row(page).locator('[data-role="vol-ladder-status"]');

/** Opens the selected ladder's order detail if it is not already open. */
export async function showLadderOrders(page) {
  const viewOrders = row(page).locator('[data-role="vol-ladder-view-orders"]');
  if ((await viewOrders.innerText()).trim() === "View orders") await viewOrders.click();
  await expect(row(page).locator('[data-role="vol-ladder-detail"]')).toBeVisible();
}

/** Place a live ladder; returns the signed order action. */
export async function placeLive(page, world, extra = {}) {
  await installExchange(page, { order: { responses: [restingAll()] }, ...extra });
  await openReview(page);
  const submit = page.locator('[data-role="vol-range-submit"]');
  await expect(submit).toBeEnabled();
  await submit.click();
  await expect(page.locator('[data-role="vol-range-review"]')).toBeHidden({ timeout: 15_000 });
  const orders = actionsOfType(await exchangeCalls(page), "order");
  expect(orders).toHaveLength(1);
  world.openOrders = openOrderRows(orders[0]);
  return orders[0];
}
