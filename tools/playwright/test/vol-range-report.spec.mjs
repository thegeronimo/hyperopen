import { expect, test } from "../support/guarded_test.mjs";
import {
  COIN,
  actionsOfType,
  exchangeCalls,
  newWorld,
  openVolLaddersTab,
  placeLive,
  row,
  startTrade,
  statusLabel,
  trackExchange,
  venueOid
} from "../support/vol_range_world.mjs";

// Vol Range Portfolio report (HO-SPEC-014 v2.1 sections 11.2, 11.3, 12.1, 12.6; reporting
// plan stage R3).
//
// The shared world (tools/playwright/support/vol_range_world.mjs) stubs /info (including the
// report backfill's `userFillsByTime` and `userNonFundingLedgerUpdates` reads), answers
// signed actions with the debug exchange simulator and aborts anything bound for a real
// /exchange endpoint. Its WebSocket simulator owns the page's Hyperliquid socket, so the
// streamed fill below is pushed by the test. A ladder is placed through the simulator, which
// records the tracking baseline (q0 = 150 VRT, P0 = 80 from the 79.99/80.01 book); the
// dock's View report opens the Portfolio Vol Range tab. There are no dollar figures yet.

const report = (page) => page.locator('[data-role="vol-range-report"]');
const campaigns = (page) => page.locator('[data-role="vol-range-report-campaign"]');
const openCampaign = (page) => page.locator('[data-role="vol-range-report-campaign"][data-open="true"]');

function fillRow(order, oid, { sz, tid, time = Date.now() }) {
  return {
    coin: COIN,
    px: String(order.p),
    sz,
    side: "B",
    time,
    startPosition: "150.0",
    dir: "Buy",
    closedPnl: "0.0",
    hash: `0x${String(tid).padStart(64, "0")}`,
    oid,
    crossed: false,
    fee: "0.0004",
    tid,
    feeToken: "VRT"
  };
}

test("a placed ladder's tracking baseline, activity and Start tracking in the Portfolio report @regression", async ({ page }) => {
  const exchange = trackExchange(page);
  const world = newWorld();
  await startTrade(page, world);
  const orderAction = await placeLive(page, world);
  await openVolLaddersTab(page);
  await expect(statusLabel(page)).toContainText("Live");
  await expect(row(page).locator('[data-role="vol-ladder-tracking"]')).toContainText("Tracking since");

  // A buy rung fills on the stream (the venue's REST reads then show it too).
  const buy = orderAction.orders[0];
  expect(buy.b).toBe(true);
  const fill = fillRow(buy, venueOid(0, 0), { sz: "2", tid: 990001 });
  world.fills = [fill];
  world.ws.push(world.ws.userFillsFrame([fill]));
  await expect(row(page).locator('[data-role="vol-ladder-filled-buy"]')).toContainText("Buy filled 2", {
    timeout: 5_000
  });

  // View report: the Portfolio Vol Range tab on this ladder's market.
  await row(page).locator('[data-role="vol-ladder-view-report"]').click();
  await expect(page).toHaveURL(/\/portfolio/);
  await expect(report(page)).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('[data-role="vol-range-report-market"]')).toHaveValue(/\|spot\|9001\|9001$/);

  await expect(page.locator('[data-role="vol-range-report-baseline"]')).toHaveText(
    /^Tracking began [A-Z][a-z]{2} \d{1,2}, \d{2}:\d{2} UTC with 150 VRT at 80\.00 USDC \(book midpoint\)\.$/
  );
  await expect(page.locator('[data-role="vol-range-report-cost-basis"]')).toHaveText("Cost basis unavailable");
  await expect(page.locator('[data-role="vol-range-report-suppressed"]')).toContainText(
    "Figures appear after the first completed hourly close"
  );
  await expect(page.locator('[data-role="vol-range-report-coverage"]')).toBeVisible();

  // Execution activity of the tracked fill (no P&L figures anywhere yet).
  await expect(page.locator('[data-role="vol-range-report-activity-fills"]')).toHaveText("1", { timeout: 15_000 });
  await expect(page.locator('[data-role="vol-range-report-activity-bought"]')).toContainText("2 VRT");
  const decimals = (String(buy.p).split(".")[1] || "").length;
  await expect(page.locator('[data-role="vol-range-report-activity-buy-vwap"]')).toHaveText(
    decimals <= 2 ? Number(buy.p).toFixed(2) : String(buy.p)
  );
  await expect(page.locator('[data-role="vol-range-report-activity-fees"]')).toHaveText("0.0004 VRT");
  await expect(page.locator('[data-role="vol-range-report-status"]')).toHaveText("Report updated.", {
    timeout: 20_000
  });
  const text = await report(page).innerText();
  expect(text).not.toMatch(/\bgrid\b|equity|%/i);

  // Start new tracking from now: an inline confirmation, then a new baseline. No order is
  // changed and nothing is signed.
  const signedBefore = (await exchangeCalls(page)).length;
  await page.locator('[data-role="vol-range-report-start-tracking"]').click();
  const confirm = page.locator('[data-role="vol-range-report-start-tracking-confirm"]');
  await expect(confirm).toContainText("Current tracking is kept as history");
  await page.locator('[data-role="vol-range-report-start-tracking-yes"]').click();
  await expect(page.locator('[data-role="vol-range-report-status"]')).toHaveText("New tracking started.", {
    timeout: 15_000
  });
  await expect(campaigns(page)).toHaveCount(2);
  await expect(openCampaign(page)).toHaveCount(1);
  await expect(openCampaign(page).locator('[data-role="vol-range-report-baseline"]')).toContainText(
    "with 150 VRT at 80.00 USDC (book midpoint)."
  );
  await expect(page.locator('[data-role="vol-range-report-ended"]')).toContainText("when new tracking started");

  const orders = actionsOfType(await exchangeCalls(page), "order");
  expect(orders).toHaveLength(1);
  expect((await exchangeCalls(page)).length).toBe(signedBefore);
  expect(exchange).toEqual([]);
  expect(world.blockedExchange).toEqual([]);
});
