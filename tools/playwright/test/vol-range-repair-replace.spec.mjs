import { expect, test } from "../support/guarded_test.mjs";
import { waitForIdle } from "../support/hyperopen.mjs";
import {
  actionsOfType,
  exchangeCalls,
  installExchange,
  newWorld,
  okOrderResponse,
  openReview,
  openVolLaddersTab,
  placeLive,
  resting,
  row,
  showLadderOrders,
  startTrade,
  statusLabel,
  trackExchange,
  venueOid
} from "../support/vol_range_world.mjs";

// Vol Range repair of definitively rejected orders (HO-SPEC-014 v2.1 sections 8.6, 11.2,
// 11.4, 13.4; VR-16). The seven-order fixture is placed with the 76.4 buy rejected
// post-only; the stubbed book then moves to 76.00/76.35, where 76.4 would cross and the
// repair review steps it away from the book to 76.16 (0.80 sigma from the deployed anchor).
//
// Every signed action is answered by the debug exchange simulator and every read by the
// stubbed /info world; anything bound for a real /exchange endpoint is aborted.
//
// See docs/exec-plans/active/2026-10-28-vol-range-repair-replace.md (Stage R5).

const REJECT_REASON = "Post only order would have immediately matched, bbo was 76.00@76.35. asset=19001";
const CROSSING_BOOK = { bid: "76.00", ask: "76.35" };

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
 * six resting; `repairResponses` answer later signed order actions (the repair).
 * Returns the first signed order action.
 */
async function placePartial(page, world, repairResponses = []) {
  world.accepted = (i, call) => !(call === 0 && i === 0);
  const statuses = [{ error: REJECT_REASON }, ...Array.from({ length: 6 }, (_, i) => resting(i + 2))];
  await installExchange(page, { order: { responses: [okOrderResponse(statuses), ...repairResponses] } });
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
