import { expect, test } from "../support/guarded_test.mjs";
import { debugCall, dispatch, expectOracle, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

// Vol Range placement, cancel and recovery (HO-SPEC-014 v2.1 sections 8-10, 15.2).
//
// Placement is on in the dev build unless `localStorage hyperopen.volRange.execution = off`
// is set before the page loads. The fixture wallet address is connected through the wallet
// simulator so owner/active account match the seeded state. Barrier reads (balances, open
// orders, l2Book, userFees, orderStatus, userFillsByTime) go through the api service
// `fetch` to /info and are answered by a stateful page.route stub (`world`). Signed actions
// are answered by the debug exchange simulator, which records each request.

const ADDRESS = "0x1234567890abcdef1234567890abcdef12345678";
const COIN = "@9001";
const INFO_URL = "**/info";
const ticket = (page) => page.locator('[data-parity-id="order-form"]').first();

async function restingFromSimulator(page, world) {
  const snap = await page.evaluate(() => window.HYPEROPEN_DEBUG?.exchangeSimulatorSnapshot?.()).catch(() => null);
  const calls = (snap?.calls || []).filter((c) => c.request?.action?.type === "order");
  const rows = [];
  for (const call of calls) {
    call.request.action.orders.forEach((o, i) => {
      if (world.accepted(i)) rows.push(...openOrderRows({ orders: [o] }, { oidBase: 7000 + i }));
    });
  }
  return rows;
}

function newWorld() {
  return {
    // null: derive resting orders from the simulator's recorded order actions,
    // keeping the order indexes the venue accepted.
    openOrders: null,
    accepted: () => true,
    // id (oid string or cloid) -> payload
    orderStatus: () => ({ status: "unknownOid" }),
    infoLog: []
  };
}

async function stubInfo(page, world) {
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
      case "spotClearinghouseState":
        body = {
          balances: [
            { coin: "USDC", token: 0, hold: "0", total: "3000", entryNtl: "0" },
            { coin: "VRT", token: 9001, hold: "0", total: "150", entryNtl: "0" }
          ]
        };
        break;
      case "frontendOpenOrders":
        // Like the venue: once an order action was accepted, its resting orders are
        // visible to every later read (background reconciliation can run at any time).
        body = world.openOrders ?? (await restingFromSimulator(page, world));
        break;
      case "l2Book":
        body = {
          coin: COIN,
          time: Date.now(),
          levels: [
            [{ px: "79.99", sz: "100", n: 1 }],
            [{ px: "80.01", sz: "100", n: 1 }]
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

function trackExchange(page) {
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

async function loadTrade(page) {
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

async function installExchange(page, signedActions = {}) {
  await debugCall(page, "installExchangeSimulator", {
    approveAgent: { responses: [{ status: "ok" }] },
    signedActions
  });
}

async function connectAndEnable(page) {
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

async function seedTicket(page) {
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

/** Full bootstrap: arm, stub reads, load, connect + enable agent, seed the fixture. */
async function bootstrap(page, world, { armed = true, cap = null } = {}) {
  await armInitScript(page, { armed, cap });
  await stubInfo(page, world);
  await loadTrade(page);
  await connectAndEnable(page);
  await seedTicket(page);
}

/** After a reload: wallet + agent + fixture again (journal survives in IndexedDB). */
async function reloadAndRebootstrap(page) {
  await loadTrade(page);
  await connectAndEnable(page);
  await seedTicket(page);
}

const okOrderResponse = (statuses) => ({
  status: "ok",
  response: { type: "order", data: { statuses } }
});
const resting = (n) => ({ resting: { oid: 7000 + n } });
const restingAll = () => okOrderResponse(Array.from({ length: 7 }, (_, i) => resting(i + 1)));
const okCancelResponse = () => ({
  status: "ok",
  response: { type: "cancel", data: { statuses: Array.from({ length: 7 }, () => "success") } }
});

async function exchangeCalls(page) {
  const snap = await debugCall(page, "exchangeSimulatorSnapshot");
  return (snap?.calls || []).filter((c) => c.matchedPath?.[0] === "signedActions");
}
const actionsOfType = (calls, type) =>
  calls.filter((c) => c.request?.action?.type === type).map((c) => c.request.action);

function openOrderRows(orderAction, { oidBase = 7000 } = {}) {
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

async function openReview(page) {
  await ticket(page).getByRole("button", { name: "Review 7 orders" }).click();
  await expect(page.locator('[data-role="vol-range-review"]')).toBeVisible();
}

async function openVolLaddersTab(page) {
  await page.getByRole("button", { name: "Vol ladders" }).first().click().catch(async () => {
    await page.getByText("Vol ladders", { exact: true }).first().click();
  });
  await expect(page.locator('[data-role="vol-ladder-dock"]')).toBeVisible();
}

const row = (page) => page.locator('[data-role="vol-ladder-row"]');
const statusLabel = (page) => row(page).locator('[data-role="vol-ladder-status"]');

/** Place a live ladder; returns the signed order action. */
async function placeLive(page, world, extra = {}) {
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

test("opted out hides placement and never reaches the exchange @regression", async ({ page }) => {
  const exchange = trackExchange(page);
  const world = newWorld();
  await bootstrap(page, world, { armed: false });
  await openReview(page);

  await expect(page.locator('[data-role="vol-range-submit"]')).toHaveCount(0);
  await expect(page.locator('[data-role="vol-range-placement-unavailable"]')).toContainText(
    "Placement isn't available yet"
  );
  expect(actionsOfType(await exchangeCalls(page), "order")).toEqual([]);
  expect(exchange).toEqual([]);
});

test("placement (on by default, no cap) sends one post-only ladder and the dock shows it live @regression", async ({ page }) => {
  const exchange = trackExchange(page);
  const world = newWorld();
  await bootstrap(page, world);

  await installExchange(page, { order: { responses: [restingAll()] } });
  await openReview(page);
  const submit = page.locator('[data-role="vol-range-submit"]');
  await expect(submit).toBeEnabled();
  // Double click: the second click must not produce a second order action.
  await submit.dblclick().catch(() => {});
  await expect(page.locator('[data-role="vol-range-review"]')).toBeHidden({ timeout: 15_000 });
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });

  const orders = actionsOfType(await exchangeCalls(page), "order");
  expect(orders).toHaveLength(1);
  expect(orders[0].orders).toHaveLength(7);
  for (const o of orders[0].orders) {
    expect(o.t.limit.tif).toBe("Alo");
    expect(o.r).toBe(false);
    expect(String(o.c).toLowerCase().startsWith("0x484f5652")).toBe(true);
  }

  await openVolLaddersTab(page);
  await expect(row(page)).toHaveCount(1);
  await expect(statusLabel(page)).toContainText("Live");
  await expect(row(page).locator('[data-role="vol-ladder-counts"]')).toContainText("7 live");
  expect(exchange).toEqual([]); // simulator answered; nothing hit the real host
});

test("partial deployment reports the rejected leg @regression", async ({ page }) => {
  const world = newWorld();
  await bootstrap(page, world);

  const reason = "Post only order would have immediately matched, bbo was 80.00@80.01. asset=19001";
  const statuses = [{ error: reason }, ...Array.from({ length: 6 }, (_, i) => resting(i + 2))];
  world.accepted = (i) => i !== 0;
  await installExchange(page, { order: { responses: [okOrderResponse(statuses)] } });
  await openReview(page);
  await page.locator('[data-role="vol-range-submit"]').click();
  await expect(page.locator('[data-role="vol-range-review"]')).toBeHidden({ timeout: 15_000 });

  const orders = actionsOfType(await exchangeCalls(page), "order");
  expect(orders).toHaveLength(1);
  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toHaveText("Partially deployed");
  await expect(row(page).locator('[data-role="vol-ladder-partial"]')).toHaveText(
    "1 order was not placed. 6 other orders remain active."
  );
  // Placement selects the new ladder, so its detail may already be open.
  const viewOrders = row(page).locator('[data-role="vol-ladder-view-orders"]');
  if ((await viewOrders.innerText()).trim() === "View orders") await viewOrders.click();
  const legs = row(page).locator('[data-role="vol-ladder-leg"]');
  await expect(legs).toHaveCount(7);
  await expect(row(page).locator('[data-role="vol-ladder-detail"]')).toContainText("immediately matched");
  await expect(legs.first()).toContainText("Buy");
});

test("cancel sends one cancel for all seven orders and the ladder closes @regression", async ({ page }) => {
  const world = newWorld();
  await bootstrap(page, world);
  const orderAction = await placeLive(page, world, {
    cancel: { responses: [okCancelResponse()] },
    cancelByCloid: { responses: [okCancelResponse()] }
  });
  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toContainText("Live");

  await row(page).locator('[data-role="vol-ladder-cancel"]').click();
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });

  const calls = await exchangeCalls(page);
  const cancels = [...actionsOfType(calls, "cancel"), ...actionsOfType(calls, "cancelByCloid")];
  expect(cancels).toHaveLength(1);
  const targets = cancels[0].cancels;
  expect(targets).toHaveLength(7);
  const ids = targets.map((t) => (t.cloid ? String(t.cloid).toLowerCase() : String(t.o)));
  expect(new Set(ids).size).toBe(7);
  if (cancels[0].type === "cancelByCloid") {
    expect(new Set(ids)).toEqual(new Set(orderAction.orders.map((o) => String(o.c).toLowerCase())));
  }
  expect(actionsOfType(calls, "order")).toHaveLength(1);

  // Venue now shows nothing open and every order canceled.
  world.openOrders = [];
  const canceledRows = new Map(openOrderRows(orderAction).flatMap((r) => [[String(r.oid), r], [String(r.cloid).toLowerCase(), r]]));
  world.orderStatus = (id) => {
    const r = canceledRows.get(id) || canceledRows.get(String(id).toLowerCase());
    if (!r) return { status: "unknownOid" };
    return {
      status: "order",
      order: {
        order: { ...r, sz: r.origSz, children: [] },
        status: "canceled",
        statusTimestamp: Date.now()
      }
    };
  };
  const refresh = row(page).locator('[data-role="vol-ladder-refresh"]');
  await expect
    .poll(
      async () => {
        if (await refresh.isEnabled()) await refresh.click();
        await page.waitForTimeout(300);
        return statusLabel(page).innerText();
      },
      { timeout: 20_000 }
    )
    .toContain("Closed");
});

test("reload restores the ladder from the journal without resending @regression", async ({ page }) => {
  const world = newWorld();
  await bootstrap(page, world);
  await placeLive(page, world);
  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toContainText("Live");

  await reloadAndRebootstrap(page);
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });
  await openVolLaddersTab(page);
  await expect(row(page)).toHaveCount(1, { timeout: 15_000 });
  await expect(row(page).locator('[data-role="vol-ladder-market"]')).toContainText("VRT");
  // The simulator was re-installed after the reload: no order action in its fresh log.
  expect(actionsOfType(await exchangeCalls(page), "order")).toEqual([]);
});

test("ladder cap disables placement @regression", async ({ page }) => {
  const world = newWorld();
  await bootstrap(page, world, { cap: "100" });
  await installExchange(page, { order: { responses: [restingAll()] } });
  await openReview(page);
  await expect(page.locator('[data-role="vol-range-submit"]')).toBeDisabled();
  await expect(page.locator('[data-role="vol-range-submit-note"]')).toContainText(/cap/i);
  expect(actionsOfType(await exchangeCalls(page), "order")).toEqual([]);
});

test("unknown outcome shows checking status and never resends @regression", async ({ page }) => {
  const world = newWorld();
  await bootstrap(page, world);
  await installExchange(page, {
    order: { responses: [{ rejectMessage: "Failed to fetch (simulated network drop after send)" }] }
  });
  world.openOrders = []; // the request never reached the venue
  await openReview(page);
  await page.locator('[data-role="vol-range-submit"]').click();
  await expect(page.locator('[data-role="vol-range-review"]')).toBeHidden({ timeout: 15_000 });
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });

  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toContainText("Checking status");
  await expect(statusLabel(page)).not.toContainText("Live");
  expect(actionsOfType(await exchangeCalls(page), "order")).toHaveLength(1);
  // Even after a status refresh, nothing is resent.
  const refresh = row(page).locator('[data-role="vol-ladder-refresh"]');
  if (await refresh.isEnabled()) await refresh.click();
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });
  expect(actionsOfType(await exchangeCalls(page), "order")).toHaveLength(1);
});
