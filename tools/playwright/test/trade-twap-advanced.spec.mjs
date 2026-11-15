import { expect, test } from "../support/guarded_test.mjs";

const ticket = (page) => page.locator('[data-parity-id="order-form"]').first();

async function openTwapTicket(page) {
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

  const proTab = page
    .locator("button")
    .filter({ hasText: /^(Pro|TWAP|Scale|Stop Market|Stop Limit|Take Market|Take Limit)$/ })
    .first();
  await proTab.click();

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

  await expect(ticket(page).getByLabel("Days")).toHaveCount(0);

  await ticket(page).getByRole("button", { name: "Custom", exact: true }).click();
  await expect(ticket(page).getByLabel("Days")).toBeVisible();
  await expect(ticket(page).getByLabel("Hours")).toBeVisible();
  await expect(ticket(page).getByLabel("Minutes")).toBeVisible();

  await ticket(page).getByLabel("Days").fill("7");
  await ticket(page).getByLabel("Hours").fill("0");
  await ticket(page).getByLabel("Minutes").fill("0");
  await expect(ticket(page).getByText("7 days", { exact: false }).first()).toBeVisible();
});

test("twap states its schedule as a sentence, or asks for a size @regression", async ({
  page
}) => {
  await openTwapTicket(page);

  await expect(
    ticket(page).getByText("Enter a size to see the pieces", { exact: false })
  ).toBeVisible();

  await ticket(page).getByRole("button", { name: "1d", exact: true }).click();
  await ticket(page).getByLabel("Size", { exact: true }).first().fill("5000");

  await expect(ticket(page).getByText("Per slice", { exact: true })).toBeVisible();
  await expect(ticket(page).getByText("pieces", { exact: false }).first()).toBeVisible();

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
  await expect(ticket(page).getByText("KILL SWITCH", { exact: true })).toBeVisible();
});

test("twap guard panel stays open while typing @regression", async ({ page }) => {
  await openTwapTicket(page);

  await ticket(page).getByText("Price guards", { exact: true }).click();
  await ticket(page).getByLabel("Trigger Price").fill("65000");

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
  await expect(submit).toContainText("Start TWAP");
  await expect(submit).toContainText("24 hours");

  await ticket(page).getByText("Price guards", { exact: true }).click();
  await ticket(page).getByLabel("Trigger Price").fill("65000");
  await ticket(page).getByLabel("Max Price").fill("72000");

  await expect(ticket(page).getByText("Starts at 65,000", { exact: false })).toBeVisible();
});
