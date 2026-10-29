import { expect, test } from "../support/guarded_test.mjs";
import { debugCall, dispatch, waitForIdle } from "../support/hyperopen.mjs";
import {
  COIN,
  actionsOfType,
  bootstrap,
  exchangeCalls,
  loadTrade,
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
// dock's View report opens the Portfolio Vol Range tab. Before the first completed hourly close
// there are no dollar figures.
//
// Stage R4 scenarios seed SYNTHETIC tracking into this browser's journal and ledger through
// `seedVolRangeFixture({report})` and stub the 1h `candleSnapshot` reads with a flat close
// (`world.hourlyCloses`), so the headline figures are exact.

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

// ---------------------------------------------------------------------------
// Stage R4: headline figures, windows, suppression and All tracked markets
// ---------------------------------------------------------------------------

const role = (page, r) => page.locator(`[data-role="${r}"]`);

async function hasFixtureSpotMeta(page) {
  return page.evaluate(() => {
    const universe = globalThis.HYPEROPEN_DEBUG?.snapshot?.()?.["app-state"]?.spot?.meta?.universe || [];
    return universe.some((m) => m.name === "@9001") && universe.some((m) => m.name === "@9002");
  });
}

/**
 * Bootstrap the world, load the app, seed a report scenario and open the Vol Range tab. The
 * app's own spot metadata load can land after a seed and replace the synthetic markets, so the
 * seed is repeated (it commits the scenario only once) until they stay.
 */
async function openSeededReport(page, world, scenario) {
  await bootstrap(page, world);
  await loadTrade(page);
  let stable = false;
  for (let attempt = 0; attempt < 8 && !stable; attempt++) {
    const seeded = await debugCall(page, "seedVolRangeFixture", { report: scenario });
    expect(seeded.ok, JSON.stringify(seeded)).toBe(true);
    await waitForIdle(page);
    await page.waitForTimeout(500);
    stable = await hasFixtureSpotMeta(page);
  }
  expect(stable).toBe(true);
  await dispatch(page, [":actions/set-portfolio-account-info-tab", ":vol-range"]);
  await dispatch(page, [":actions/navigate", "/portfolio"]);
  await expect(report(page)).toBeVisible({ timeout: 20_000 });
  await expect(role(page, "vol-range-report-status")).toHaveText("Report updated.", { timeout: 30_000 });
}

async function expectNoHorizontalScroll(page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
}

test("Since tracking began shows the three headline figures and windows switch @regression", async ({ page }) => {
  const exchange = trackExchange(page);
  const world = newWorld();
  world.totals.base = "157";
  world.hourlyCloses = { [COIN]: "70" };
  await openSeededReport(page, world, "headline-buy");

  const headline = role(page, "vol-range-report-headline");
  await expect(role(page, "vol-range-report-window-since-start")).toHaveAttribute("aria-pressed", "true");
  await expect(role(page, "vol-range-report-net-pnl-value")).toHaveText("−1,544.80 USDC", { timeout: 20_000 });
  await expect(role(page, "vol-range-report-hold-pnl-value")).toHaveText("−1,500.00 USDC");
  await expect(role(page, "vol-range-report-contribution-value")).toHaveText("−44.80 USDC");
  await expect(role(page, "vol-range-report-net-pnl")).toContainText("Net tracked asset P&L");
  await expect(role(page, "vol-range-report-contribution")).toContainText("Ladder contribution versus hold");
  await expect(role(page, "vol-range-report-benchmark")).toContainText(
    "Benchmark: Hold inventory at the selected window start."
  );
  await expect(role(page, "vol-range-report-range")).toHaveText(
    /^[A-Z][a-z]{2} \d{1,2}, \d{2}:\d{2} UTC → [A-Z][a-z]{2} \d{1,2}, \d{2}:00 UTC · start valued at the book midpoint$/
  );
  await expect(headline.locator('[data-role="vol-range-report-suppressed"]')).toHaveCount(0);
  await expect(role(page, "vol-range-report-activity-fills")).toHaveText("1");

  // 7D needs tracking to have started a week before the window end: unavailable, not clipped.
  await role(page, "vol-range-report-window-7d").click();
  await expect(role(page, "vol-range-report-window-7d")).toHaveAttribute("aria-pressed", "true");
  await expect(role(page, "vol-range-report-suppressed")).toHaveText(
    /^7D needs tracking to have started by .+ UTC\. Tracking began .+ UTC\.$/
  );
  await expect(role(page, "vol-range-report-net-pnl")).toHaveCount(0);
  await role(page, "vol-range-report-window-30d").click();
  await expect(role(page, "vol-range-report-suppressed")).toContainText("30D needs tracking to have started by");
  await role(page, "vol-range-report-window-since-start").click();
  await expect(role(page, "vol-range-report-net-pnl-value")).toHaveText("−1,544.80 USDC");

  for (const size of [{ width: 375, height: 812 }, { width: 1440, height: 900 }]) {
    await page.setViewportSize(size);
    await expect(role(page, "vol-range-report-net-pnl-value")).toBeVisible();
    await expect(role(page, "vol-range-report-contribution-value")).toBeVisible();
    await expect(role(page, "vol-range-report-window")).toBeVisible();
    await expectNoHorizontalScroll(page);
  }
  const net = await role(page, "vol-range-report-net-pnl").boundingBox();
  const hold = await role(page, "vol-range-report-hold-pnl").boundingBox();
  expect(Math.abs(net.y - hold.y)).toBeLessThan(2); // side by side at 1440
  await page.setViewportSize({ width: 375, height: 812 });
  const net375 = await role(page, "vol-range-report-net-pnl").boundingBox();
  const hold375 = await role(page, "vol-range-report-hold-pnl").boundingBox();
  expect(hold375.y).toBeGreaterThan(net375.y); // stacked at 375

  const text = await report(page).innerText();
  expect(text).not.toMatch(/\bgrid\b|equity|%/i);
  expect(world.infoLog).toContain("candleSnapshot:1h");
  expect(exchange).toEqual([]);
  expect(world.blockedExchange).toEqual([]);
});

test("an external transfer hides the dollar figures while activity stays @regression", async ({ page }) => {
  const exchange = trackExchange(page);
  const world = newWorld();
  world.hourlyCloses = { [COIN]: "70" };
  await openSeededReport(page, world, "external-transfer");

  await expect(role(page, "vol-range-report-suppressed")).toHaveText(
    /^Dollar figures are hidden from .+ UTC: a VRT transfer \(spotTransfer\)\. Orders, fills and fees are still shown below\.$/,
    { timeout: 20_000 }
  );
  await expect(role(page, "vol-range-report-suppressed")).toHaveAttribute("role", "status");
  await expect(role(page, "vol-range-report-net-pnl")).toHaveCount(0);
  await expect(role(page, "vol-range-report-activity-fills")).toHaveText("1");
  await expect(role(page, "vol-range-report-activity-bought")).toContainText("7 VRT");
  await expect(role(page, "vol-range-report-coverage")).toContainText("Outside activity");
  expect(exchange).toEqual([]);
  expect(world.blockedExchange).toEqual([]);
});

test("All tracked markets totals only covered markets on request @regression", async ({ page }) => {
  const exchange = trackExchange(page);
  const world = newWorld();
  world.totals.base = "157";
  world.hourlyCloses = { [COIN]: "70", "@9002": "10" };
  await openSeededReport(page, world, "two-markets");

  await expect(role(page, "vol-range-report-headline-title")).toHaveText("All tracked markets");
  await expect(role(page, "vol-range-report-no-total")).toHaveText(
    "Totals need the same window for every market. Choose 7D or 30D."
  );
  await role(page, "vol-range-report-window-7d").click();
  await expect(role(page, "vol-range-report-no-total")).toHaveText(
    "Totals are hidden because VRS isn't fully covered for this window.",
    { timeout: 20_000 }
  );
  await expect(role(page, "vol-range-report-net-pnl")).toHaveCount(0);
  const subset = role(page, "vol-range-report-covered-subset");
  await expect(subset).toHaveText("Show total for covered markets (VRT)");
  await expect(subset).toHaveAttribute("aria-pressed", "false");
  await subset.click();
  await expect(role(page, "vol-range-report-headline-title")).toHaveText("Covered markets: VRT");
  await expect(subset).toHaveAttribute("aria-pressed", "true");
  await expect(role(page, "vol-range-report-net-pnl-value")).toHaveText("−44.80 USDC");
  await expect(role(page, "vol-range-report-hold-pnl-value")).toHaveText("0.00 USDC");
  await expect(role(page, "vol-range-report-range")).toContainText("valued at hourly closes");

  // One market chosen: its own figures.
  await role(page, "vol-range-report-market").selectOption({ label: "VRT/USDC" });
  await expect(role(page, "vol-range-report-headline-title")).toHaveText("VRT/USDC");
  await expect(role(page, "vol-range-report-net-pnl-value")).toHaveText("−44.80 USDC");
  await role(page, "vol-range-report-market").selectOption({ label: "VRS/USDC" });
  await expect(role(page, "vol-range-report-suppressed")).toContainText(
    "a VRS trade that wasn't a Vol Range order"
  );
  expect(exchange).toEqual([]);
  expect(world.blockedExchange).toEqual([]);
});
