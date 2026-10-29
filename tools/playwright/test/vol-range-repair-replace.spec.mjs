import { expect, test } from "../support/guarded_test.mjs";
import { dispatch, waitForIdle } from "../support/hyperopen.mjs";
import {
  COIN,
  actionsOfType,
  cancelResponses,
  exchangeCalls,
  installExchange,
  newWorld,
  okOrderResponse,
  openOrderRows,
  openReview,
  openVolLaddersTab,
  orderStatusFor,
  placeLive,
  reloadAndRebootstrap,
  resting,
  restingAll,
  row,
  showLadderOrders,
  startTrade,
  statusLabel,
  ticket,
  trackExchange,
  venueOid
} from "../support/vol_range_world.mjs";

// Vol Range repair of definitively rejected orders (HO-SPEC-014 v2.1 sections 8.6, 11.2,
// 11.4, 13.4; VR-16). The seven-order fixture is placed with the 76.4 buy rejected
// post-only; the stubbed book then moves to 76.00/76.35, where 76.4 would cross and the
// repair review steps it away from the book to 76.16 (0.80 sigma from the deployed anchor).
//
// Vol Range ladder update (replace; spec sections 10.2, 11.2, 11.4, 13.4; VR-15, VR-16):
// Preview update, cancel the old ladder with 3 of its 10-unit sell filled while the cancel
// is pending, review and place the unfilled remainder (buys 40, sells 27) with lineage;
// leaving after the cancel places nothing.
//
// Every signed action is answered by the debug exchange simulator and every read by the
// stubbed /info world; anything bound for a real /exchange endpoint is aborted.
//
// See docs/exec-plans/completed/2026-10-28-vol-range-repair-replace.md (Stages R5 and P4).

const REJECT_REASON = "Post only order would have immediately matched, bbo was 76.00@76.35. asset=19001";
const CROSSING_BOOK = { bid: "76.00", ask: "76.35" };
const BLOCKED_TEXT = "Some old orders have an unknown status.";

const repairButton = (page) => row(page).locator('[data-role="vol-ladder-repair"]');
const dialog = (page) => page.locator('[data-role="vol-ladder-repair-dialog"]');
const repairLeg = (page) => dialog(page).locator('[data-role="vol-ladder-repair-leg"]');
const repairPrice = (page) => repairLeg(page).locator('[data-role="vol-ladder-repair-price"]');
const submitRepair = (page) => dialog(page).locator('[data-role="vol-ladder-repair-submit"]');

async function orderActions(page) {
  return actionsOfType(await exchangeCalls(page), "order");
}

/**
 * Place the fixture with its first order (the 76.4 buy) rejected post-only and the other
 * six resting; `repairResponses` answer later signed order actions (the repair) and
 * `extra` adds simulator answers for other signed actions (a cancel).
 * Returns the first signed order action.
 */
async function placePartial(page, world, repairResponses = [], extra = {}) {
  world.accepted = (i, call) => !(call === 0 && i === 0);
  const statuses = [{ error: REJECT_REASON }, ...Array.from({ length: 6 }, (_, i) => resting(i + 2))];
  await installExchange(page, { order: { responses: [okOrderResponse(statuses), ...repairResponses] }, ...extra });
  await openReview(page);
  await page.locator('[data-role="vol-range-submit"]').click();
  await expect(page.locator('[data-role="vol-range-review"]')).toBeHidden({ timeout: 15_000 });

  const orders = await orderActions(page);
  expect(orders).toHaveLength(1);
  expect(orders[0].orders).toHaveLength(7);
  expect(orders[0].orders[0].b).toBe(true);
  expect(orders[0].orders[0].p).toBe("76.4");

  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toHaveText("Partially deployed");
  // Reconcile once, as the dock does in the background: the balance read it requests
  // includes the venue hold of the six resting orders, which releases the placement's own
  // pending reservation (spec 3.3) so admission does not count those funds twice.
  const refresh = row(page).locator('[data-role="vol-ladder-refresh"]');
  await refresh.click();
  await expect(refresh).toBeEnabled({ timeout: 15_000 });
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });
  await expect(statusLabel(page)).toHaveText("Partially deployed");
  return orders[0];
}

/** Move the stubbed book, then open the rejected-order review from the dock. */
async function openRepair(page, world, book = CROSSING_BOOK) {
  world.book = book;
  await expect(repairButton(page)).toHaveText("Repair 1 rejected order");
  await expect(repairButton(page)).toBeEnabled();
  await repairButton(page).click();
  await expect(dialog(page)).toBeVisible();
  await expect(dialog(page).getByRole("heading", { name: "Review rejected orders" })).toBeVisible();
  await expect(repairLeg(page)).toHaveCount(1, { timeout: 15_000 });
}

test("repairing the rejected buy sends one new order and the ladder becomes live @regression", async ({ page }) => {
  const exchange = trackExchange(page);
  const world = newWorld();
  await startTrade(page, world);
  const repairOid = venueOid(1, 0);
  const first = await placePartial(page, world, [okOrderResponse([{ resting: { oid: repairOid } }])]);
  const rejectedCloid = String(first.orders[0].c).toLowerCase();

  await openRepair(page, world);
  await expect(dialog(page).locator('[data-role="vol-ladder-repair-intro"]')).toContainText(
    "The 6 active orders in this ladder are not touched."
  );
  await expect(repairPrice(page)).toHaveText("76.16");
  await expect(repairLeg(page)).toContainText("76.4");
  await expect(repairLeg(page).locator("td").nth(4)).toHaveText("7");
  await expect(repairLeg(page).locator('[data-role="vol-ladder-repair-note"]')).toHaveText(
    "Moved away from the book: 76.4 would cross the best ask 76.35."
  );
  await expect(dialog(page).locator('[data-role="vol-ladder-repair-leg-select"]')).toBeChecked();
  await expect(submitRepair(page)).toHaveText("Place 1 repair order");
  await expect(submitRepair(page)).toBeEnabled();
  // Opening the review sends nothing.
  expect(await orderActions(page)).toHaveLength(1);

  await submitRepair(page).click();
  await expect(dialog(page)).toBeHidden({ timeout: 15_000 });
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });

  const orders = await orderActions(page);
  expect(orders).toHaveLength(2);
  const repair = orders[1];
  expect(repair.orders).toHaveLength(1);
  const [sent] = repair.orders;
  expect(sent.b).toBe(true);
  expect(sent.p).toBe("76.16");
  expect(sent.s).toBe(first.orders[0].s);
  expect(sent.t.limit.tif).toBe("Alo");
  expect(sent.r).toBe(false);
  const repairCloid = String(sent.c).toLowerCase();
  expect(repairCloid).not.toBe(rejectedCloid);
  expect(repairCloid.startsWith("0x484f5652")).toBe(true);
  // The six survivors are never resent.
  const survivorCloids = first.orders.slice(1).map((o) => String(o.c).toLowerCase());
  expect(survivorCloids).not.toContain(repairCloid);

  await expect(row(page).locator('[data-role="vol-ladder-counts"]')).toContainText("7 live", { timeout: 15_000 });
  await expect(statusLabel(page)).toContainText("Live");
  await expect(statusLabel(page)).not.toContainText("Partially");
  await expect(repairButton(page)).toHaveCount(0);
  await expect(page.locator('[data-role="vol-ladder-status-message"]')).toContainText(
    "Repair sent. 7 of 7 orders active."
  );

  await showLadderOrders(page);
  const legs = row(page).locator('[data-role="vol-ladder-leg"]');
  await expect(legs).toHaveCount(8);
  const earlier = row(page).locator('[data-role="vol-ladder-leg"][data-historical="true"]');
  await expect(earlier).toHaveCount(1);
  await expect(earlier).toContainText("earlier attempt");
  await expect(earlier).toContainText("76.4");

  expect(exchange).toEqual([]);
  expect(world.blockedExchange).toEqual([]);
});

test("a fully live ladder shows no repair control @regression", async ({ page }) => {
  const world = newWorld();
  await startTrade(page, world);
  await placeLive(page, world);
  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toContainText("Live");
  await expect(row(page).locator('[data-role="vol-ladder-cancel"]')).toBeVisible();
  await expect(repairButton(page)).toHaveCount(0);
  await expect(dialog(page)).toHaveCount(0);
});

test("Refresh prices re-proposes from the moved book and sends nothing @regression", async ({ page }) => {
  const world = newWorld();
  await startTrade(page, world);
  await placePartial(page, world);
  await openRepair(page, world);
  await expect(repairPrice(page)).toHaveText("76.16");

  // The ask moves above the rejected price: 76.4 no longer crosses and is kept.
  world.book = { bid: "75.00", ask: "76.50" };
  await dialog(page).locator('[data-role="vol-ladder-repair-refresh"]').click();
  await expect(repairPrice(page)).toHaveText("76.4", { timeout: 15_000 });
  await expect(repairLeg(page).locator('[data-role="vol-ladder-repair-note"]')).toHaveText(
    "The original price no longer crosses the book."
  );
  await expect(submitRepair(page)).toBeEnabled();

  await dialog(page).locator('[data-role="vol-ladder-repair-close"]').click();
  await expect(dialog(page)).toBeHidden();
  expect(await orderActions(page)).toHaveLength(1);
  expect(world.blockedExchange).toEqual([]);
});

test("Escape closes the review and focus returns to the repair button @regression", async ({ page }) => {
  const world = newWorld();
  await startTrade(page, world);
  await placePartial(page, world);
  await openRepair(page, world);
  await expect(repairPrice(page)).toHaveText("76.16");
  // Opening the review moves focus into the dialog.
  await expect
    .poll(() => page.evaluate(() => !!document.activeElement?.closest('[data-role="vol-ladder-repair-dialog"]')))
    .toBe(true);

  await page.keyboard.press("Escape");
  await expect(dialog(page)).toBeHidden();
  await expect(repairButton(page)).toBeFocused();
  expect(await orderActions(page)).toHaveLength(1);
});

test("a repair refused because the book moved past the frozen price sends nothing @regression", async ({ page }) => {
  const world = newWorld();
  await startTrade(page, world);
  await placePartial(page, world);
  await openRepair(page, world);
  await expect(repairPrice(page)).toHaveText("76.16");

  // The ask drops below the frozen 76.16: admission re-reads the book and refuses.
  world.book = { bid: "75.90", ask: "76.10" };
  await submitRepair(page).click();
  await expect(dialog(page).locator('[data-role="vol-ladder-repair-error"]')).toContainText(
    "The market moved since these prices were set. Refresh prices and review again.",
    { timeout: 15_000 }
  );
  await expect(dialog(page).locator('[data-role="vol-ladder-repair-error"]')).not.toContainText("Not enough");
  await expect(dialog(page)).toBeVisible();
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });
  expect(await orderActions(page)).toHaveLength(1);
  await expect(statusLabel(page)).toHaveText("Partially deployed");
  expect(world.blockedExchange).toEqual([]);
});

// ---------------------------------------------------------------------------
// Update (replace) a ladder
// ---------------------------------------------------------------------------

const INTERMEDIATE = "Old ladder canceled. Review the updated quantities before placing the replacement.";

const previewButton = (page) => row(page).locator('[data-role="vol-ladder-preview-update"]');
const updateDialog = (page) => page.locator('[data-role="vol-range-replace-dialog"]');
const inUpdate = (page, role) => updateDialog(page).locator(`[data-role="${role}"]`);
const banner = (page) => page.locator('[data-role="vol-range-replace-banner"]');
const liveRegion = (page) => page.locator('[data-role="vol-ladder-status-message"]');

function cancelActions(calls) {
  return [...actionsOfType(calls, "cancel"), ...actionsOfType(calls, "cancelByCloid")];
}

const sumSizes = (orders) => orders.reduce((n, o) => n + Number(o.s), 0);

/** The venue's open-order rows for the orders of the first signed order action, minus `skip`. */
function firstActionRows(orderAction, skip = []) {
  return orderAction.orders
    .map((o, i) => [i, openOrderRows({ orders: [o] }, { oidBase: venueOid(0, i) - 1 })[0]])
    .filter(([i]) => !skip.includes(i))
    .map(([, r]) => r);
}

/** A venue fill row for `sz` of `order` (oid `oid`), as the REST fill reads return it. */
function fillRow(order, oid, { sz, tid }) {
  const buy = !!order.b;
  return {
    coin: COIN,
    px: String(order.p),
    sz,
    side: buy ? "B" : "A",
    time: Date.now(),
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

/**
 * Venue state once the cancel lands: nothing from the old ladder rests (later order
 * actions do), every old order reports canceled with `remaining` unfilled, and `fills`
 * landed while the cancel was pending.
 */
function cancelLands(world, rows, { remaining = (r) => r.sz, fills = [], totals = null } = {}) {
  world.onCancel = (w) => {
    w.openOrders = null;
    w.accepted = (_i, call) => call >= 1;
    w.orderStatus = orderStatusFor(rows, { remaining });
    w.fills = fills;
    if (totals) w.totals = totals;
  };
}

async function openUpdate(page) {
  await expect(previewButton(page)).toHaveText("Preview update");
  await expect(previewButton(page)).toBeEnabled();
  await previewButton(page).click();
  await expect(updateDialog(page)).toBeVisible();
  await expect(updateDialog(page).getByRole("heading", { name: "Update ladder" })).toBeVisible();
}

/** Click "Cancel current ladder to update" and wait until the cancellation is confirmed. */
async function cancelForUpdate(page) {
  await inUpdate(page, "vol-range-replace-cancel-old").click();
  const message = inUpdate(page, "vol-range-replace-message");
  const check = inUpdate(page, "vol-range-replace-check");
  // "Check again" when a fill or cancel status had not been read yet.
  await expect
    .poll(
      async () => {
        if (await message.isVisible()) return message.innerText();
        if ((await check.isVisible()) && (await check.isEnabled())) await check.click();
        await page.waitForTimeout(300);
        return message.isVisible().then((v) => (v ? message.innerText() : ""));
      },
      { timeout: 30_000 }
    )
    .toBe(INTERMEDIATE);
}

async function orderActionCount(page) {
  return (await orderActions(page)).length;
}

test("updating a live ladder places the unfilled remainder after a fill during the cancel @regression", async ({
  page
}) => {
  test.setTimeout(120_000);
  const exchange = trackExchange(page);
  const world = newWorld();
  await startTrade(page, world);
  const second = okOrderResponse(Array.from({ length: 7 }, (_, i) => ({ resting: { oid: venueOid(1, i) } })));
  const first = await placeLive(page, world, { order: { responses: [restingAll(), second] }, ...cancelResponses(7) });
  const sellIndex = first.orders.findIndex((o) => !o.b && Number(o.s) === 10);
  expect(sellIndex).toBeGreaterThan(-1);
  const sell = first.orders[sellIndex];
  const sellOid = venueOid(0, sellIndex);
  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toContainText("Live");

  // Step 1: preview, old versus proposed. Nothing is sent.
  await openUpdate(page);
  await expect(inUpdate(page, "vol-range-replace-explainer")).toContainText("Updating takes two separate steps.");
  await expect(inUpdate(page, "vol-range-replace-compare")).toBeVisible();
  await expect(inUpdate(page, "vol-range-replace-compare-row")).toHaveCount(9);
  await expect(inUpdate(page, "vol-range-replace-cancel-old")).toHaveAttribute(
    "aria-label",
    "Cancel the 7 open orders of the VRT/USDC ladder"
  );
  expect(await orderActionCount(page)).toBe(1);

  // Steps 2-4: 3 of the 10-unit sell fill while the cancel is pending; the rest is canceled.
  cancelLands(world, firstActionRows(first), {
    remaining: (r) => (r.oid === sellOid ? "7" : r.sz),
    fills: [fillRow(sell, sellOid, { sz: "3", tid: 990001 })],
    totals: { usdc: (3000 + 3 * Number(sell.p) - 0.0004).toFixed(8), base: "147" }
  });
  await cancelForUpdate(page);
  const calls = await exchangeCalls(page);
  const cancels = cancelActions(calls);
  expect(cancels).toHaveLength(1);
  expect(cancels[0].cancels).toHaveLength(7);
  const targets = cancels[0].cancels.map((t) => (t.cloid ? String(t.cloid).toLowerCase() : String(t.o)));
  expect(new Set(targets).size).toBe(7);
  expect(actionsOfType(calls, "order")).toHaveLength(1);
  await expect(inUpdate(page, "vol-range-replace-remaining")).toHaveText(
    "Unfilled remainder: buys 40 VRT, sells 27 VRT."
  );
  await expect(inUpdate(page, "vol-range-replace-filled")).toHaveText("Filled while canceling: sells 3 VRT.");
  await expect(inUpdate(page, "vol-range-replace-rejected")).toHaveCount(0);

  // Step 5: the remainder goes into the ticket.
  await inUpdate(page, "vol-range-replace-use-quantities").click();
  await expect(updateDialog(page)).toBeHidden();
  await expect(ticket(page).locator("#vol-range-buy-quantity")).toHaveValue("40");
  await expect(ticket(page).locator("#vol-range-sell-quantity")).toHaveValue("27");
  await expect(banner(page)).toContainText(INTERMEDIATE);
  await expect(banner(page).locator('[data-role="vol-range-replace-lineage"]')).toContainText(
    "Replacing the ladder created"
  );

  // Step 6: the ordinary review shows the lineage and places the replacement.
  await openReview(page);
  const lineage = page.locator('[data-role="vol-range-review-lineage"]');
  await expect(lineage).toContainText("Replaces the ladder created");
  await expect(lineage).toContainText("Unfilled remainder: buys 40 VRT, sells 27 VRT.");
  await expect(lineage).toContainText("Filled while canceling: sells 3 VRT.");
  await expect(page.locator('[data-role="vol-range-review-rejected"]')).toHaveText(
    "Rejected orders from the old ladder are not included."
  );
  const submit = page.locator('[data-role="vol-range-submit"]');
  await expect(submit).toBeEnabled({ timeout: 15_000 });
  await submit.click();
  await expect(page.locator('[data-role="vol-range-review"]')).toBeHidden({ timeout: 15_000 });
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });

  const orders = await orderActions(page);
  expect(orders).toHaveLength(2);
  const replacement = orders[1].orders;
  expect(sumSizes(replacement.filter((o) => !o.b))).toBe(27);
  expect(sumSizes(replacement.filter((o) => o.b))).toBe(40);
  for (const o of replacement) {
    expect(o.t.limit.tif).toBe("Alo");
    expect(String(o.c).toLowerCase()).not.toBe(String(sell.c).toLowerCase());
  }
  await expect(banner(page)).toHaveCount(0);

  const lineages = page.locator('[data-role="vol-ladder-lineage"]');
  await expect(lineages.filter({ hasText: "Replaces the ladder created" })).toHaveCount(1, { timeout: 15_000 });
  await expect(lineages.filter({ hasText: "Replaced by the ladder created" })).toHaveCount(1);
  expect(cancelActions(await exchangeCalls(page))).toHaveLength(1);
  expect(exchange).toEqual([]);
  expect(world.blockedExchange).toEqual([]);
});

/** A live ladder, its update previewed and the old ladder canceled (no fills). */
async function canceledForUpdate(page, world) {
  const first = await placeLive(page, world, cancelResponses(7));
  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toContainText("Live");
  await openUpdate(page);
  cancelLands(world, firstActionRows(first));
  await cancelForUpdate(page);
  await expect(inUpdate(page, "vol-range-replace-remaining")).toHaveText(
    "Unfilled remainder: buys 40 VRT, sells 30 VRT."
  );
  await expect(inUpdate(page, "vol-range-replace-filled")).toHaveCount(0);
  expect(await orderActionCount(page)).toBe(1);
  return first;
}

test("Stop updating after the cancel places nothing @regression", async ({ page }) => {
  test.setTimeout(90_000);
  const world = newWorld();
  await startTrade(page, world);
  await canceledForUpdate(page, world);

  await inUpdate(page, "vol-range-replace-abandon").click();
  await expect(updateDialog(page)).toBeHidden();
  await expect(banner(page)).toHaveCount(0);
  await expect(liveRegion(page)).toContainText(
    "Update stopped. The old ladder stays canceled; no new orders were placed."
  );
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });
  await page.waitForTimeout(1_000);
  expect(await orderActionCount(page)).toBe(1);
  await expect(statusLabel(page)).toContainText("Closed");
  await expect(previewButton(page)).toHaveCount(0);
  expect(world.blockedExchange).toEqual([]);
});

test("a reload after the cancel abandons the update and places nothing @regression", async ({ page }) => {
  test.setTimeout(120_000);
  const world = newWorld();
  await startTrade(page, world);
  await canceledForUpdate(page, world);
  const before = await orderActionCount(page);
  expect(before).toBe(1);

  await reloadAndRebootstrap(page);
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });
  await openVolLaddersTab(page);
  await expect(row(page)).toHaveCount(1, { timeout: 15_000 });
  await expect(statusLabel(page)).toContainText("Closed", { timeout: 15_000 });
  await expect(banner(page)).toHaveCount(0);
  await expect(updateDialog(page)).toHaveCount(0);
  await page.waitForTimeout(1_000);
  // The simulator was re-installed after the reload: its fresh log has no order action.
  expect(before + (await orderActionCount(page))).toBe(1);
  expect(world.blockedExchange).toEqual([]);
});

test("a rejected order is listed unchecked and adds its 7 only when selected @regression", async ({ page }) => {
  test.setTimeout(120_000);
  const world = newWorld();
  await startTrade(page, world);
  const first = await placePartial(page, world, [], cancelResponses(6));
  await expect(repairButton(page)).toBeVisible();

  await openUpdate(page);
  await expect(inUpdate(page, "vol-range-replace-cancel-old")).toHaveAttribute(
    "aria-label",
    "Cancel the 6 open orders of the VRT/USDC ladder"
  );
  cancelLands(world, firstActionRows(first, [0]));
  await cancelForUpdate(page);
  const cancels = cancelActions(await exchangeCalls(page));
  expect(cancels).toHaveLength(1);
  expect(cancels[0].cancels).toHaveLength(6);
  await expect(inUpdate(page, "vol-range-replace-remaining")).toHaveText(
    "Unfilled remainder: buys 33 VRT, sells 30 VRT."
  );

  const rejected = inUpdate(page, "vol-range-replace-rejected");
  await expect(rejected).toContainText("Orders that were never placed");
  await expect(rejected).toContainText("These are not included unless you select them.");
  const select = rejected.getByLabel("Include Buy 7 at 76.4");
  await expect(select).not.toBeChecked();
  await expect(inUpdate(page, "vol-range-replace-included")).toHaveCount(0);

  await select.check();
  await expect(select).toBeChecked();
  await expect(inUpdate(page, "vol-range-replace-included")).toHaveText(
    "With the selected orders: buys 40 VRT, sells 30 VRT."
  );
  await select.uncheck();
  await expect(inUpdate(page, "vol-range-replace-included")).toHaveCount(0);
  await select.check();

  await inUpdate(page, "vol-range-replace-use-quantities").click();
  await expect(updateDialog(page)).toBeHidden();
  await expect(ticket(page).locator("#vol-range-buy-quantity")).toHaveValue("40");
  await expect(ticket(page).locator("#vol-range-sell-quantity")).toHaveValue("30");

  await banner(page).locator('[data-role="vol-range-replace-banner-stop"]').click();
  await expect(banner(page)).toHaveCount(0);
  await waitForIdle(page, { quietMs: 500, timeoutMs: 5_000, pollMs: 50 });
  expect(await orderActionCount(page)).toBe(1);
  expect(world.blockedExchange).toEqual([]);
});

test("an unknown cancel outcome blocks the replacement review @regression", async ({ page }) => {
  test.setTimeout(90_000);
  const world = newWorld();
  await startTrade(page, world);
  const lost = { rejectMessage: "Failed to fetch (simulated network drop after send)" };
  const first = await placeLive(page, world, {
    cancel: { responses: [lost] },
    cancelByCloid: { responses: [lost] }
  });
  // The cancel never reached the venue: every old order still rests there.
  world.orderStatus = orderStatusFor(firstActionRows(first), { status: () => "open" });
  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toContainText("Live");
  await openUpdate(page);

  await inUpdate(page, "vol-range-replace-cancel-old").click();
  const blocking = inUpdate(page, "vol-range-replace-blocking");
  await expect(blocking).toBeVisible({ timeout: 30_000 });
  await expect(blocking).toContainText(BLOCKED_TEXT);
  await expect(inUpdate(page, "vol-range-replace-check")).toBeVisible();
  await expect(inUpdate(page, "vol-range-replace-use-quantities")).toHaveCount(0);
  await expect(inUpdate(page, "vol-range-replace-message")).toHaveCount(0);

  // Checking again while the outcome is still unknown stays blocked.
  await expect(inUpdate(page, "vol-range-replace-check")).toBeEnabled({ timeout: 15_000 });
  await inUpdate(page, "vol-range-replace-check").click();
  await expect(inUpdate(page, "vol-range-replace-check")).toBeEnabled({ timeout: 15_000 });
  await expect(blocking).toBeVisible();
  await expect(inUpdate(page, "vol-range-replace-use-quantities")).toHaveCount(0);
  expect(await orderActionCount(page)).toBe(1);

  // Stopping now must not call the old ladder canceled: its orders still rest.
  await inUpdate(page, "vol-range-replace-abandon").click();
  await expect(updateDialog(page)).toBeHidden();
  await expect(banner(page)).toHaveCount(0);
  await expect(liveRegion(page)).toContainText(
    "Update stopped. No new orders were placed. Some of the old ladder's orders may still be open."
  );
  await expect(liveRegion(page)).not.toContainText("stays canceled");
  expect(await orderActionCount(page)).toBe(1);
  expect(world.blockedExchange).toEqual([]);
});

test("Escape returns focus to Preview update and the cancel result is announced politely @regression", async ({
  page
}) => {
  test.setTimeout(90_000);
  const world = newWorld();
  await startTrade(page, world);
  const first = await placeLive(page, world, cancelResponses(7));
  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toContainText("Live");
  await openUpdate(page);
  await expect
    .poll(() => page.evaluate(() => !!document.activeElement?.closest('[data-role="vol-range-replace-dialog"]')))
    .toBe(true);

  await page.keyboard.press("Escape");
  await expect(updateDialog(page)).toBeHidden();
  await expect(previewButton(page)).toBeFocused();
  // The ticket banner reopens the update; nothing has been canceled.
  await expect(banner(page)).toContainText("step 1 of 3");
  await banner(page).locator('[data-role="vol-range-replace-open"]').click();
  await expect(updateDialog(page)).toBeVisible();
  expect(cancelActions(await exchangeCalls(page))).toHaveLength(0);

  cancelLands(world, firstActionRows(first));
  await cancelForUpdate(page);
  await expect(liveRegion(page)).toHaveAttribute("aria-live", "polite");
  await expect(liveRegion(page)).toContainText(INTERMEDIATE);
  await expect(banner(page)).toHaveAttribute("role", "status");
  await expect(banner(page)).toContainText(INTERMEDIATE);
  expect(await orderActionCount(page)).toBe(1);
});

/** The dialog fits the viewport and nothing scrolls horizontally. */
async function expectDialogFits(page, width) {
  await expect(updateDialog(page)).toBeVisible();
  const box = await updateDialog(page).boundingBox();
  expect(box.x).toBeGreaterThanOrEqual(0);
  expect(box.x + box.width).toBeLessThanOrEqual(width + 0.5);
  const overflow = await page.evaluate(() => {
    const d = document.querySelector('[data-role="vol-range-replace-dialog"]');
    return {
      page: document.documentElement.scrollWidth - window.innerWidth,
      dialog: d.scrollWidth - d.clientWidth
    };
  });
  expect(overflow.page).toBeLessThanOrEqual(0);
  expect(overflow.dialog).toBeLessThanOrEqual(0);
}

test("the update dialog fits at 1280 and 375 CSS pixels @regression", async ({ page }) => {
  test.setTimeout(90_000);
  await page.setViewportSize({ width: 1280, height: 800 });
  const world = newWorld();
  await startTrade(page, world);
  await placeLive(page, world);
  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toContainText("Live");
  await openUpdate(page);
  await expectDialogFits(page, 1280);

  await page.setViewportSize({ width: 375, height: 812 });
  // At phone width the order form, and with it the dialog, lives on the Trade surface.
  await dispatch(page, [":actions/select-trade-mobile-surface", ":ticket"]);
  await expectDialogFits(page, 375);
  const box = await updateDialog(page).boundingBox();
  expect(box.width).toBeGreaterThanOrEqual(374);
  await expect(inUpdate(page, "vol-range-replace-cancel-old")).toBeVisible();
  expect(await orderActionCount(page)).toBe(1);
});
