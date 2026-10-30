import { expect, test } from "../support/guarded_test.mjs";
import { waitForIdle } from "../support/hyperopen.mjs";
import {
  actionsOfType,
  exchangeCalls,
  installExchange,
  newWorld,
  okCancelResponse,
  okOrderResponse,
  openOrderRows,
  openReview,
  openVolLaddersTab,
  placeLive,
  reloadAndRebootstrap,
  resting,
  restingAll,
  row,
  showLadderOrders,
  startTrade,
  statusLabel,
  trackExchange
} from "../support/vol_range_world.mjs";

// Vol Range placement, cancel and recovery (HO-SPEC-014 v2.1 sections 8-10, 15.2).
//
// The shared world (tools/playwright/support/vol_range_world.mjs) stubs /info, answers
// signed actions with the debug exchange simulator and aborts anything bound for a real
// /exchange endpoint.

test("opted out hides placement and never reaches the exchange @regression", async ({ page }) => {
  const exchange = trackExchange(page);
  const world = newWorld();
  await startTrade(page, world, { armed: false });
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
  await startTrade(page, world);

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
  await startTrade(page, world);

  const reason = "Post only order would have immediately matched, bbo was 80.00@80.01. asset=19001";
  const statuses = [{ error: reason }, ...Array.from({ length: 6 }, (_, i) => resting(i + 2))];
  world.accepted = (i, call) => !(call === 0 && i === 0);
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
  await showLadderOrders(page);
  const legs = row(page).locator('[data-role="vol-ladder-leg"]');
  await expect(legs).toHaveCount(7);
  await expect(row(page).locator('[data-role="vol-ladder-detail"]')).toContainText("immediately matched");
  await expect(legs.filter({ hasText: "immediately matched" })).toContainText("Buy");
});

test("cancel sends one cancel for all seven orders and the ladder closes @regression", async ({ page }) => {
  const world = newWorld();
  await startTrade(page, world);
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
  await startTrade(page, world);
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
  await startTrade(page, world, { cap: "100" });
  await installExchange(page, { order: { responses: [restingAll()] } });
  await openReview(page);
  await expect(page.locator('[data-role="vol-range-submit"]')).toBeDisabled();
  await expect(page.locator('[data-role="vol-range-submit-note"]')).toContainText(/cap/i);
  expect(actionsOfType(await exchangeCalls(page), "order")).toEqual([]);
});

test("unknown outcome shows checking status and never resends @regression", async ({ page }) => {
  const world = newWorld();
  await startTrade(page, world);
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
