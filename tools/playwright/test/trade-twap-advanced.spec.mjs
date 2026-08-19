import { expect, test } from "@playwright/test";

// Browser coverage for the TWAP ticket after the 2026-08-01 Hyperliquid TWAP upgrade:
// a Day(s) runtime input alongside Hours and Minutes, an Advanced Settings disclosure
// holding Trigger Price and a side-dependent Max/Min Price, and a slice preview that
// reports the venue's dynamic schedule instead of a fixed 30-second cadence.
//
// See docs/exec-plans/active/2026-08-19-twap-advanced-controls-and-slice-model.md.

async function openTwapTicket(page) {
  await page.goto("/trade", { waitUntil: "commit" });
  await expect(page.locator('[data-parity-id="trade-root"]')).toBeVisible();

  // The pro order types (Scale, TWAP, Stop, ...) live behind the third entry-mode tab,
  // which is a plain button rendered next to Market and Limit.
  const proTab = page
    .locator("button")
    .filter({ hasText: /^(Pro|TWAP|Scale|Stop Market|Stop Limit|Take Market|Take Limit)$/ })
    .first();
  await proTab.click();

  // Two buttons read exactly "TWAP": this order-type option, and the account panel's TWAP
  // tab further down the page. The order-type option comes first in DOM order.
  await page.getByRole("button", { name: "TWAP", exact: true }).first().click();
  await expect(page.getByLabel("Minutes")).toBeVisible();
}

test("twap ticket exposes days, hours and minutes up to a seven day runtime @regression", async ({
  page
}) => {
  await openTwapTicket(page);

  await expect(page.getByLabel("Days")).toBeVisible();
  await expect(page.getByLabel("Hours")).toBeVisible();
  await expect(page.getByLabel("Minutes")).toBeVisible();

  await page.getByLabel("Days").fill("7");
  await page.getByLabel("Hours").fill("0");
  await page.getByLabel("Minutes").fill("0");

  // The preview restates the runtime, which proves the days input reached the domain
  // rather than merely rendering.
  await expect(page.getByText("Runtime").locator("..").getByText("7d")).toBeVisible();

  // The old copy asserted a cadence the venue no longer always uses.
  await expect(
    page.getByText("Hyperliquid slices TWAP orders every 30 seconds.")
  ).toHaveCount(0);
});

test("twap advanced settings disclose trigger and max price @regression", async ({ page }) => {
  await openTwapTicket(page);

  const disclosure = page.getByText("Advanced Settings", { exact: true });
  await expect(disclosure).toBeVisible();

  // Collapsed by default: the inputs exist in the DOM but are not visible until opened.
  await expect(page.getByLabel("Trigger Price")).toBeHidden();

  await disclosure.click();

  await expect(page.getByLabel("Trigger Price")).toBeVisible();
  await expect(page.getByLabel("Max Price")).toBeVisible();

  // The stop is a kill switch on the mark, not a cap on the fill price, and the copy
  // must say so -- "Max Price" invites exactly the wrong reading.
  await expect(page.getByText("does not cap the fill price", { exact: false })).toBeVisible();

  await page.getByLabel("Trigger Price").fill("65000");
  await expect(page.getByLabel("Trigger Price")).toHaveValue("65000");
});

test("twap max price becomes min price on the sell side @regression", async ({ page }) => {
  await openTwapTicket(page);

  await page.getByText("Advanced Settings", { exact: true }).click();
  await expect(page.getByLabel("Max Price")).toBeVisible();

  await page
    .getByRole("button", { name: /Sell|Short/ })
    .first()
    .click();

  await expect(page.getByLabel("Min Price")).toBeVisible();
  await expect(page.getByLabel("Max Price")).toHaveCount(0);
});

test("twap advanced panel stays open while typing @regression", async ({ page }) => {
  await openTwapTicket(page);

  await page.getByText("Advanced Settings", { exact: true }).click();
  await page.getByLabel("Trigger Price").fill("65000");

  // Re-renders triggered by other form edits must not remount the <details> node and
  // snap the panel shut mid-edit; the renderer carries a stable :replicant/key for this.
  await page.getByLabel("Minutes").fill("45");

  await expect(page.getByLabel("Trigger Price")).toBeVisible();
  await expect(page.getByLabel("Trigger Price")).toHaveValue("65000");
});
