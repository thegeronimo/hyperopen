import { expect, test } from "@playwright/test";
import { dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

test("staking route defaults to disconnected gating when no wallet is connected @regression", async ({ page }) => {
  await visitRoute(page, "/staking");

  await expect(page.locator("[data-parity-id='staking-root']")).toBeVisible();
  await expect(page.locator("[data-role='staking-establish-connection']")).toBeVisible();
  await expect(page.locator("[data-role='staking-action-transfer-button']")).toHaveCount(0);
  await expect(page.locator("[data-role='staking-action-unstake-button']")).toHaveCount(0);
  await expect(page.locator("[data-role='staking-action-stake-button']")).toHaveCount(0);
});

test("staking timeframe menu opens and selects a deterministic option via debug actions @regression", async ({ page }) => {
  await visitRoute(page, "/staking");

  const trigger = page.locator("[data-role='staking-timeframe-menu-trigger']");
  const menu = page.locator("[data-role='staking-timeframe-menu']");
  const dayOption = page.locator("[data-role='staking-timeframe-option-day']");

  await expect(trigger).toContainText("7D");
  await expect(trigger).not.toHaveAttribute("aria-expanded", "true");

  await dispatch(page, [":actions/toggle-staking-validator-timeframe-menu"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(trigger).toHaveAttribute("aria-expanded", "true");
  await expect(menu).toBeVisible();
  await expect(dayOption).toBeVisible();

  await dispatch(page, [":actions/set-staking-validator-timeframe", ":day"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(trigger).toContainText("1D");
  await expect(trigger).not.toHaveAttribute("aria-expanded", "true");
  await expect(menu).not.toHaveClass(/opacity-100/);
});
