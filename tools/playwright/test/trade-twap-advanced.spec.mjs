import { expect, test } from "@playwright/test";

// Browser coverage for the TWAP ticket: the venue's advanced controls (7-day runtime,
// trigger price, termination price) and the 2026-08-19 design pass that made them legible
// — a runtime preset row, the schedule stated as a sentence, and price guards that state
// their value when collapsed.
//
// See docs/exec-plans/active/2026-08-19-twap-advanced-controls-and-slice-model.md.

const ticket = (page) => page.locator('[data-parity-id="order-form"]').first();

async function openTwapTicket(page) {
  // shadow-cljs rewrites main.js while the watch settles, so a context that loads during
  // that window receives a truncated bundle and dies with "Invalid or unexpected token"
  // before the app ever mounts — a blank page with no app code, not a slow one. Re-fetch
  // until we get a whole bundle rather than reporting a phantom regression.
  const root = page.locator('[data-parity-id="trade-root"]');
  let mounted = false;
  for (let attempt = 0; attempt < 4 && !mounted; attempt++) {
    if (attempt > 0) await page.waitForTimeout(1500);
    await page.goto(`/trade?boot=${attempt}`, { waitUntil: "commit" });
    mounted = await root
      .waitFor({ state: "visible", timeout: 8000 })
      .then(() => true)
      .catch(() => false);
  }
  await expect(root).toBeVisible();

  // The pro order types (Scale, TWAP, Stop, ...) live behind the third entry-mode tab.
  const proTab = page
    .locator("button")
    .filter({ hasText: /^(Pro|TWAP|Scale|Stop Market|Stop Limit|Take Market|Take Limit)$/ })
    .first();
  await proTab.click();

  // Two buttons read exactly "TWAP": this order-type option, and the account panel's TWAP
  // tab further down the page. The order-type option comes first in DOM order.
  await page.getByRole("button", { name: "TWAP", exact: true }).first().click();
  await expect(ticket(page).getByText("Runtime", { exact: true })).toBeVisible();
}

test("twap runtime offers presets and hides the fields until Custom @regression", async ({
  page
}) => {
  await openTwapTicket(page);

  for (const preset of ["15m", "30m", "4h", "1d", "Custom"]) {
    await expect(ticket(page).getByRole("button", { name: preset, exact: true })).toBeVisible();
  }

  // Three labelled fields no longer compete for the ticket's 320px column.
  await expect(ticket(page).getByLabel("Days")).toHaveCount(0);

  await ticket(page).getByRole("button", { name: "Custom", exact: true }).click();
  await expect(ticket(page).getByLabel("Days")).toBeVisible();
  await expect(ticket(page).getByLabel("Hours")).toBeVisible();
  await expect(ticket(page).getByLabel("Minutes")).toBeVisible();

  // Seven days is the venue ceiling and must be reachable.
  await ticket(page).getByLabel("Days").fill("7");
  await ticket(page).getByLabel("Hours").fill("0");
  await ticket(page).getByLabel("Minutes").fill("0");
  await expect(ticket(page).getByText("7 days", { exact: false }).first()).toBeVisible();
});

test("twap states its schedule as a sentence, or asks for a size @regression", async ({
  page
}) => {
  await openTwapTicket(page);

  // With no size the venue's spacing cannot be derived, so the ticket asks rather than
  // showing a bound to interpret.
  await expect(
    ticket(page).getByText("Enter a size to see the pieces", { exact: false })
  ).toBeVisible();

  await ticket(page).getByRole("button", { name: "1d", exact: true }).click();
  await ticket(page).getByLabel("Size", { exact: true }).first().fill("5000");

  await expect(ticket(page).getByText("Per slice", { exact: true })).toBeVisible();
  await expect(ticket(page).getByText("pieces", { exact: false }).first()).toBeVisible();

  // The old four-row estimate block and its false cadence claim are gone.
  await expect(
    page.getByText("Hyperliquid slices TWAP orders every 30 seconds.")
  ).toHaveCount(0);
});

test("twap price guards state their value when collapsed @regression", async ({ page }) => {
  await openTwapTicket(page);

  await expect(ticket(page).getByText("Price guards", { exact: true })).toBeVisible();
  await expect(ticket(page).getByText("none set", { exact: true })).toBeVisible();
  await expect(ticket(page).getByLabel("Trigger Price")).toBeHidden();

  await ticket(page).getByText("Price guards", { exact: true }).click();

  await expect(ticket(page).getByLabel("Trigger Price")).toBeVisible();
  await expect(ticket(page).getByLabel("Max Price")).toBeVisible();
  await expect(ticket(page).getByText("KILL SWITCH", { exact: true })).toBeVisible();

  // The tag and the note carry the meaning the venue's own label does not.
  await expect(
    ticket(page).getByText("does not cap your fill price", { exact: false })
  ).toBeVisible();

  await ticket(page).getByLabel("Trigger Price").fill("65000");
  await expect(ticket(page).getByLabel("Trigger Price")).toHaveValue("65000");
});

test("twap max price becomes min price on the sell side @regression", async ({ page }) => {
  await openTwapTicket(page);

  await ticket(page).getByText("Price guards", { exact: true }).click();
  await expect(ticket(page).getByLabel("Max Price")).toBeVisible();

  await page
    .getByRole("button", { name: /Sell|Short/ })
    .first()
    .click();

  await expect(ticket(page).getByLabel("Min Price")).toBeVisible();
  await expect(ticket(page).getByLabel("Max Price")).toHaveCount(0);
  // The tag never changes, so the concept is only learned once.
  await expect(ticket(page).getByText("KILL SWITCH", { exact: true })).toBeVisible();
});

test("twap guard panel stays open while typing @regression", async ({ page }) => {
  await openTwapTicket(page);

  await ticket(page).getByText("Price guards", { exact: true }).click();
  await ticket(page).getByLabel("Trigger Price").fill("65000");

  // Re-renders triggered by other form edits must not remount the <details> node and snap
  // the panel shut mid-edit; the renderer carries a stable :replicant/key for this.
  await ticket(page).getByLabel("Size", { exact: true }).first().fill("5000");

  await expect(ticket(page).getByLabel("Trigger Price")).toBeVisible();
  await expect(ticket(page).getByLabel("Trigger Price")).toHaveValue("65000");
});

test("twap submit button names the run and recaps its guards @regression", async ({
  page
}) => {
  await openTwapTicket(page);

  await ticket(page).getByRole("button", { name: "1d", exact: true }).click();
  await ticket(page).getByLabel("Size", { exact: true }).first().fill("5000");

  const submit = page.locator('[data-parity-id="trade-submit-order-button"]');
  // A TWAP is a run the venue works over time, not an order that lands — the button says so.
  await expect(submit).toContainText("Start TWAP");
  await expect(submit).toContainText("24 hours");

  await ticket(page).getByText("Price guards", { exact: true }).click();
  await ticket(page).getByLabel("Trigger Price").fill("65000");
  await ticket(page).getByLabel("Max Price").fill("72000");

  await expect(ticket(page).getByText("Starts at 65,000", { exact: false })).toBeVisible();
});
