import { expect, test } from "../support/guarded_test.mjs";
import { debugCall, dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

// Release A0 browser coverage for the Vol Range Scale mode (HO-SPEC-014 v2.1): ticket, read-only
// review, chart overlays and the VR-24 guarantee that nothing reaches /exchange.
//
// See docs/exec-plans/active/2026-10-21-vol-range-release-a0-preview.md.

const ticket = (page) => page.locator('[data-parity-id="order-form"]').first();
const FIXTURE_ADDRESS = "0x1234567890abcdef1234567890abcdef12345678";
const INFO_URL = "**/info";
const overlayRoot = (page) => page.locator('[data-role="vol-range-overlay-root"]');

// The fixture wallet is synthetic: answer its account bootstrap /info requests with the
// seeded balances so a late response cannot replace the seeded inventory mid-test.
async function stubFixtureAccountInfo(page) {
  await page.route(INFO_URL, async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    if (String(payload?.user || "").toLowerCase() !== FIXTURE_ADDRESS) {
      await route.continue();
      return;
    }
    let body;
    switch (payload?.type) {
      case "spotClearinghouseState":
        body = {
          balances: [
            { coin: "USDC", token: 0, hold: "0", total: "3000", entryNtl: "0" },
            { coin: "VRT", token: 9001, hold: "0", total: "150", entryNtl: "0" }
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
      case "userAbstraction":
        // The raw account mode gates admission; the live endpoint would answer for a
        // synthetic address and clear the seeded mode.
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

async function openSeededTicket(page, { mobile = false } = {}) {
  await stubFixtureAccountInfo(page);
  // shadow-cljs rewrites main.js while the watch settles; retry boot like the TWAP spec.
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
  // App startup selects the default market asynchronously; re-seed until the fixture market
  // sticks (the seed is idempotent).
  const heading = ticket(page).getByRole("heading", { name: "Accumulate buys" });
  let seeded = false;
  for (let attempt = 0; attempt < 8 && !seeded; attempt++) {
    await debugCall(page, "seedVolRangeFixture");
    await waitForIdle(page);
    await page.waitForTimeout(500);
    if (mobile) {
      // The 375px layout shows the order form on the bottom-nav Trade surface.
      await page.getByRole("button", { name: "Trade", exact: true }).last().click();
    }
    seeded = await heading.isVisible();
  }
  await expect(heading).toBeVisible({ timeout: 15_000 });
}

function trackExchangeRequests(page) {
  const exchangeRequests = [];
  page.on("request", (request) => {
    if (request.url().includes("/exchange")) exchangeRequests.push(request.url());
  });
  return exchangeRequests;
}

test("fixture shows the seven-order ticket @regression", async ({ page }) => {
  const exchangeRequests = trackExchangeRequests(page);
  await openSeededTicket(page);

  await expect(
    ticket(page).getByRole("radio", { name: "Volatility range" })
  ).toHaveAttribute("aria-checked", "true");
  await expect(ticket(page).getByRole("heading", { name: "Accumulate buys" })).toBeVisible();
  await expect(ticket(page).getByRole("heading", { name: "Distribute sells" })).toBeVisible();

  const rungRows = ticket(page).locator('[data-role="vol-range-rung-rows"]');
  const buyRows = rungRows.nth(0);
  for (const price of ["76.4", "74", "71.6", "69.2"]) {
    await expect(buyRows).toContainText(price);
  }
  const sellRows = rungRows.nth(1);
  for (const price of ["86", "88.4", "90.8"]) {
    await expect(sellRows).toContainText(price);
  }

  // Spec 13.2 per-rung sizes: buys 7/9/11/13, sells 10/10/10.
  const rowText = async (rows) => (await rows.innerText()).split("\n").join(" ");
  const buyText = await rowText(buyRows);
  for (const [price, size] of [["76.4", "7.00"], ["74", "9.00"], ["71.6", "11.00"], ["69.2", "13.00"]]) {
    expect(buyText).toContain(`${price} ${size} VRT`);
  }
  const sellText = await rowText(sellRows);
  for (const [price, size] of [["86", "10.00"], ["88.4", "10.00"], ["90.8", "10.00"]]) {
    expect(sellText).toContain(`${price} ${size} VRT`);
  }

  // Spec 13.2 side totals, VWAPs and inventory outcomes.
  const buyTotals = ticket(page).locator('[data-role="vol-range-buy-totals"]');
  await expect(buyTotals).toContainText("40.00 VRT");
  await expect(buyTotals).toContainText("2,888.00");
  await expect(buyTotals).toContainText("72.20");
  const sellTotals = ticket(page).locator('[data-role="vol-range-sell-totals"]');
  await expect(sellTotals).toContainText("30.00 VRT");
  await expect(sellTotals).toContainText("2,652.00");
  await expect(sellTotals).toContainText("88.40");
  const outcomes = ticket(page).locator('[data-role="vol-range-outcomes"]');
  await expect(outcomes).toContainText("190.00");
  await expect(outcomes).toContainText("120.00");
  await expect(outcomes).toContainText("160.00");
  // The quoted VWAP gap lives in a collapsed disclosure; read its text, not its visibility.
  expect(await ticket(page).locator('[data-role="vol-range-vwap-gap"]').first().textContent())
    .toContain("16.20");

  await expect(ticket(page).getByRole("button", { name: "Review 7 orders" })).toBeEnabled();
  expect(exchangeRequests).toEqual([]);
});

test("mode switch keeps the draft @regression", async ({ page }) => {
  const exchangeRequests = trackExchangeRequests(page);
  await openSeededTicket(page);

  await ticket(page).getByRole("radio", { name: "Price range" }).click();
  await expect(ticket(page).getByPlaceholder("Start price")).toBeVisible();
  await expect(ticket(page).getByPlaceholder("End price")).toBeVisible();
  await expect(ticket(page).getByRole("heading", { name: "Accumulate buys" })).toHaveCount(0);

  await ticket(page).getByRole("radio", { name: "Volatility range" }).click();
  await expect(ticket(page).getByRole("heading", { name: "Accumulate buys" })).toBeVisible();
  await expect(ticket(page).locator('[data-role="vol-range-rung-rows"]').nth(0)).toContainText("76.4");
  await expect(ticket(page).getByRole("button", { name: "Review 7 orders" })).toBeEnabled();
  expect(exchangeRequests).toEqual([]);
});

test("review is read-only and closes @regression", async ({ page }) => {
  const exchangeRequests = trackExchangeRequests(page);
  await openSeededTicket(page);

  const open = async () => {
    await ticket(page).getByRole("button", { name: "Review 7 orders" }).click();
    await expect(page.getByRole("dialog")).toBeVisible();
  };

  await open();
  const dialog = page.getByRole("dialog");
  await expect(dialog.locator("tr[data-leg-id]")).toHaveCount(7);
  await expect(dialog.getByText("Placement isn't available yet")).toBeVisible();
  await expect(dialog.getByRole("button", { name: /Place/ })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /Place/ })).toHaveCount(0);

  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);

  await open();
  await page.getByRole("button", { name: "Back to edit" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(exchangeRequests).toEqual([]);
});

test("chart overlay renders the draft and updates without remount @regression", async ({
  page
}) => {
  const exchangeRequests = trackExchangeRequests(page);
  await openSeededTicket(page);

  const canvas = page.locator("canvas").first();
  await expect(canvas).toBeVisible({ timeout: 20_000 });
  const canvasHandle = await canvas.elementHandle();

  await expect(overlayRoot(page)).toHaveCount(1, { timeout: 20_000 });
  await expect(overlayRoot(page).locator('[data-role="stage-label"]')).toHaveText("Draft");
  await expect(overlayRoot(page).locator('[data-role="anchor-label"]')).toContainText("Anchor 80");
  const labels = overlayRoot(page).locator('[data-role="leg-label"]');
  await expect(labels.first()).toBeVisible();
  const before = await labels.allTextContents();

  // Labels stay inside the main price pane: left of the price axis, not over it.
  const geometry = await overlayRoot(page).evaluate((root) => {
    const rootBox = root.getBoundingClientRect();
    return [...root.querySelectorAll('[data-role="leg-label"],[data-role="stage-label"]')].map(
      (node) => {
        const box = node.getBoundingClientRect();
        return { right: box.right, bottom: box.bottom, rootRight: rootBox.right, rootBottom: rootBox.bottom };
      }
    );
  });
  expect(geometry.length).toBeGreaterThan(0);
  for (const g of geometry) {
    expect(g.right).toBeLessThanOrEqual(g.rootRight + 0.5);
  }

  await dispatch(page, [
    ":actions/update-vol-range-draft",
    [":buy", ":start-sigma"],
    "1.00"
  ]);
  await expect
    .poll(async () => (await labels.allTextContents()).join("|"))
    .not.toBe(before.join("|"));
  await expect(overlayRoot(page).locator('[data-role="leg-label"]').filter({ hasText: "@ 75.2" }))
    .toHaveCount(1);

  // Same chart canvas: the decoration pass ran, the chart was not remounted.
  expect(await canvasHandle.evaluate((node) => node.isConnected)).toBe(true);
  expect(await canvas.evaluate((node, original) => node === original, canvasHandle)).toBe(true);
  expect(exchangeRequests).toEqual([]);
});

test("ticket and review stay usable at 375px @regression", async ({ page }) => {
  const exchangeRequests = trackExchangeRequests(page);
  await page.setViewportSize({ width: 375, height: 812 });
  await openSeededTicket(page, { mobile: true });

  const noHorizontalScroll = () =>
    page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
  await expect(ticket(page).getByRole("heading", { name: "Accumulate buys" })).toBeVisible();
  expect(await noHorizontalScroll()).toBe(true);

  const reviewButton = ticket(page).getByRole("button", { name: "Review 7 orders" });
  await reviewButton.scrollIntoViewIfNeeded();
  await reviewButton.click();
  await expect(page.getByRole("dialog")).toBeVisible();
  expect(await noHorizontalScroll()).toBe(true);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(exchangeRequests).toEqual([]);
});
