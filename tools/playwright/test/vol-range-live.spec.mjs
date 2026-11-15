import { expect, test } from "../support/guarded_test.mjs";
import { dispatch, waitForIdle } from "../support/hyperopen.mjs";
import {
  COIN,
  actionsOfType,
  exchangeCalls,
  newWorld,
  okOrderResponse,
  openOrderRows,
  openReview,
  openVolLaddersTab,
  placeLive,
  restingAll,
  row,
  startTrade,
  statusLabel,
  ticket,
  trackExchange,
  venueOid
} from "../support/vol_range_world.mjs";

const MONITORING_NOTE = "Placement waits for live order and fill updates to catch up.";

const monitoring = (page) => page.locator('[data-role="vol-ladders-monitoring"]');
const filled = (page, side) => row(page).locator(`[data-role="vol-ladder-filled-${side}"]`);
const submit = (page) => page.locator('[data-role="vol-range-submit"]');

function fillRow(order, oid, { sz, tid, time = Date.now(), coin = COIN }) {
  const buy = !!order.b;
  return {
    coin,
    px: String(order.p),
    sz,
    side: buy ? "B" : "A",
    time,
    startPosition: "150.0",
    dir: buy ? "Buy" : "Sell",
    closedPnl: "0.0",
    hash: `0x${String(tid).padStart(64, "0")}`,
    oid,
    crossed: false,
    fee: "0.0004",
    tid,
    feeToken: buy ? "VRT" : "USDC"
  };
}

function afterFill(world, oid, sz) {
  world.openOrders = world.openOrders.map((r) =>
    r.oid === oid ? { ...r, sz: String(Number(r.sz) - Number(sz)) } : r
  );
}

async function placeAndWatch(page, world) {
  const orderAction = await placeLive(page, world);
  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toContainText("Live");
  await expect(monitoring(page)).toHaveAttribute("data-status", "live");
  await expect(monitoring(page)).toHaveText("Live updates on");
  await expect(filled(page, "buy")).toContainText(/Bought\s*—/);
  return orderAction;
}

const restFillsSince = (world, from) => world.fillsReads.slice(from).filter((r) => r.rows > 0);

async function refreshAndReadFills(page, world, rows) {
  const from = world.fillsReads.length;
  const refresh = row(page).locator('[data-role="vol-ladder-refresh"]');
  await expect(refresh).toBeEnabled({ timeout: 15_000 });
  await refresh.click();
  await expect
    .poll(() => world.fillsReads.slice(from).some((r) => r.type === "userFillsByTime" && r.rows === rows), {
      timeout: 15_000
    })
    .toBe(true);
  await expect(refresh).toBeEnabled({ timeout: 15_000 });
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });
}

function expectNoRealExchange(exchange, world) {
  expect(exchange).toEqual([]);
  expect(world.blockedExchange).toEqual([]);
}

async function storeFillCount(page) {
  return page.evaluate(() => {
    const grids = globalThis.HYPEROPEN_DEBUG?.snapshot?.()?.["app-state"]?.["vol-range"]?.execution?.grids || {};
    return Object.values(grids).reduce((n, g) => n + Object.keys(g?.fills || {}).length, 0);
  });
}

test("a streamed fill shows at once and a later REST read does not count it again @regression", async ({ page }) => {
  const exchange = trackExchange(page);
  const world = newWorld();
  await startTrade(page, world);
  const orderAction = await placeAndWatch(page, world);
  const buy = orderAction.orders[0];
  expect(buy.b).toBe(true);
  const oid = venueOid(0, 0);
  const fill = fillRow(buy, oid, { sz: "2", tid: 880001 });

  const readsBefore = world.fillsReads.length;
  world.ws.push(world.ws.userFillsFrame([fill]));
  await expect(filled(page, "buy")).toContainText(/Bought\s*2 @ 76\.4/, { timeout: 3_000 });
  expect(restFillsSince(world, readsBefore)).toEqual([]);

  world.fills = [fill];
  afterFill(world, oid, "2");
  await refreshAndReadFills(page, world, 1);
  await expect(filled(page, "buy")).toContainText(/Bought\s*2 @ 76\.4/);
  world.ws.push(world.ws.userFillsFrame([fill]));
  await page.waitForTimeout(500);
  await expect(filled(page, "buy")).toContainText(/Bought\s*2 @ 76\.4/);
  expect(await storeFillCount(page)).toBe(1);
  await expect(statusLabel(page)).toContainText("Live");
  expectNoRealExchange(exchange, world);
});

test("a ladder the stream closes is persisted, so a new ladder in the market can be placed @regression", async ({
  page
}) => {
  test.setTimeout(90_000);
  const exchange = trackExchange(page);
  const world = newWorld();
  await startTrade(page, world);
  const second = okOrderResponse(Array.from({ length: 7 }, (_, i) => ({ resting: { oid: venueOid(1, i) } })));
  const orderAction = await placeLive(page, world, { order: { responses: [restingAll(), second] } });
  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toContainText("Live");
  await expect(monitoring(page)).toHaveAttribute("data-status", "live");
  const fills = orderAction.orders.map((o, i) => fillRow(o, venueOid(0, i), { sz: String(o.s), tid: 880401 + i }));

  world.fills = fills.slice(0, -1);
  world.openOrders = world.openOrders.slice(-1);
  await refreshAndReadFills(page, world, fills.length - 1);
  await expect(statusLabel(page)).toContainText("Live");

  const readsBefore = world.fillsReads.length;
  world.fills = fills;
  world.openOrders = [];
  world.ws.push(world.ws.userFillsFrame(fills.slice(-1)));
  await expect(statusLabel(page)).toHaveText("Closed", { timeout: 3_000 });
  await expect
    .poll(() => world.fillsReads.slice(readsBefore).some((r) => r.rows === fills.length), { timeout: 10_000 })
    .toBe(true);
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });

  await openReview(page);
  await expect(submit(page)).toBeEnabled();
  await submit(page).click();
  await expect(page.locator('[data-role="vol-range-review"]')).toBeHidden({ timeout: 15_000 });
  const orders = actionsOfType(await exchangeCalls(page), "order");
  expect(orders).toHaveLength(2);
  world.openOrders = openOrderRows(orders[1], { oidBase: venueOid(1, 0) - 1 });
  await expect(page.locator('[data-role="vol-ladder-status-message"]')).not.toContainText("already active");
  expectNoRealExchange(exchange, world);
});

test("a dropped connection pauses placement until the missed fills are read @regression", async ({ page }) => {
  test.setTimeout(90_000);
  const exchange = trackExchange(page);
  const world = newWorld();
  await startTrade(page, world);
  const orderAction = await placeAndWatch(page, world);
  const connections = world.ws.connections;

  world.ws.holdSnapshots();
  await world.ws.drop();
  await expect(monitoring(page)).toHaveAttribute("data-status", "stale", { timeout: 10_000 });
  await expect.poll(() => world.ws.connections, { timeout: 15_000 }).toBeGreaterThan(connections);
  await expect(monitoring(page)).toHaveAttribute("data-status", "stale");
  await expect(monitoring(page)).toContainText("⚠");

  await openReview(page);
  await expect(submit(page)).toBeDisabled();
  await expect(page.locator('[data-role="vol-range-submit-note"]')).toHaveText(MONITORING_NOTE);

  const sellIndex = orderAction.orders.findIndex((o) => !o.b);
  expect(sellIndex).toBeGreaterThan(0);
  const sellOid = venueOid(0, sellIndex);
  const gapFill = fillRow(orderAction.orders[sellIndex], sellOid, { sz: "1", tid: 880101 });
  world.fills = [gapFill];
  afterFill(world, sellOid, "1");
  let releaseFills = () => {};
  world.fillsGate = new Promise((resolve) => {
    releaseFills = resolve;
  });
  const infoBefore = world.infoLog.length;
  world.ws.releaseSnapshots();

  await expect(monitoring(page)).toHaveAttribute("data-status", "resyncing", { timeout: 15_000 });
  await expect(monitoring(page)).toHaveText("⚠Catching up on fills and orders…");
  await expect(submit(page)).toBeDisabled();
  await expect(page.locator('[data-role="vol-range-submit-note"]')).toHaveText(MONITORING_NOTE);

  world.fillsGate = null;
  releaseFills();
  await expect(monitoring(page)).toHaveAttribute("data-status", "live", { timeout: 20_000 });
  const reads = world.infoLog.slice(infoBefore);
  expect(reads).toContain("frontendOpenOrders");
  expect(reads).toContain("userFillsByTime");
  await expect(filled(page, "sell")).toContainText(/Sold\s*1 @/);
  await expect(submit(page)).toBeEnabled({ timeout: 10_000 });
  await page.keyboard.press("Escape");

  world.ws.push(world.ws.userFillsFrame([gapFill]));
  await refreshAndReadFills(page, world, 1);
  await expect(filled(page, "sell")).toContainText(/Sold\s*1 @/);
  await expect(filled(page, "buy")).toContainText(/Bought\s*—/);
  expect(await storeFillCount(page)).toBe(1);
  expectNoRealExchange(exchange, world);
});

test("a snapshot of 250 fills whose last row is the ladder's counts it @regression", async ({ page }) => {
  const exchange = trackExchange(page);
  const world = newWorld();
  await startTrade(page, world);
  const orderAction = await placeAndWatch(page, world);
  const buy = orderAction.orders[0];
  const now = Date.now();
  const others = Array.from({ length: 249 }, (_, i) =>
    fillRow(buy, 990000 + i, { sz: "1", tid: 770000 + i, time: now - i, coin: i % 2 ? "@107" : COIN })
  );
  const ladderFill = fillRow(buy, venueOid(0, 0), { sz: "3", tid: 880201, time: now - 1000 });
  const rows = [...others, ladderFill];
  expect(rows).toHaveLength(250);

  const readsBefore = world.fillsReads.length;
  world.ws.state.fills = rows;
  world.ws.push(world.ws.userFillsFrame(rows, { snapshot: true }));
  await expect(filled(page, "buy")).toContainText(/Bought\s*3 @ 76\.4/, { timeout: 5_000 });
  await expect(monitoring(page)).toHaveAttribute("data-status", "live", { timeout: 20_000 });
  expect(restFillsSince(world, readsBefore)).toEqual([]);
  expect(await storeFillCount(page)).toBe(1);
  expectNoRealExchange(exchange, world);
});

test("a fill streamed while Portfolio is open is shown back on Trade @regression", async ({ page }) => {
  test.setTimeout(90_000);
  const exchange = trackExchange(page);
  const world = newWorld();
  await startTrade(page, world);
  const orderAction = await placeAndWatch(page, world);
  const buy = orderAction.orders[0];

  await dispatch(page, [":actions/navigate", "/portfolio"]);
  await expect(page).toHaveURL(/\/portfolio/);
  await expect(ticket(page)).toHaveCount(0, { timeout: 15_000 });
  const readsBefore = world.fillsReads.length;
  const fill = fillRow(buy, venueOid(0, 0), { sz: "4", tid: 880301 });
  world.ws.push(world.ws.userFillsFrame([fill]));
  await expect.poll(() => storeFillCount(page), { timeout: 20_000 }).toBe(1);

  await dispatch(page, [":actions/navigate", "/trade"]);
  await expect(page).toHaveURL(/\/trade/);
  await openVolLaddersTab(page);
  await expect(filled(page, "buy")).toContainText(/Bought\s*4 @ 76\.4/);
  await expect(monitoring(page)).toHaveAttribute("data-status", "live", { timeout: 20_000 });
  expect(restFillsSince(world, readsBefore)).toEqual([]);
  expectNoRealExchange(exchange, world);
});
