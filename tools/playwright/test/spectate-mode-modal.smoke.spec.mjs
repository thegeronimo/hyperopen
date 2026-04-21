import { expect, test } from "@playwright/test";
import { mobileViewport, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

const WATCHLIST_ADDRESS = "0x2222222222222222222222222222222222222222";
const WATCHLIST_LABEL = "Playwright desk";

async function expectWithinViewport(page, locator) {
  const box = await locator.boundingBox();
  const viewport = page.viewportSize();

  expect(box, "expected modal to have a layout box").toBeTruthy();
  expect(viewport, "expected viewport size").toBeTruthy();
  expect(box.x).toBeGreaterThanOrEqual(0);
  expect(box.y).toBeGreaterThanOrEqual(0);
  expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);
  expect(box.y + box.height).toBeLessThanOrEqual(viewport.height);
}

async function openSpectateModal(page, mode) {
  if (mode === "mobile") {
    await page.locator("[data-role='mobile-header-menu-trigger']").click();
    await expect(page.locator("[data-role='mobile-header-menu-panel']")).toBeVisible();
    await page.locator("[data-role='mobile-header-menu-spectate']").click();
  } else {
    await page.locator("[data-role='spectate-mode-open-button']").click();
  }

  await expect(page.locator("[data-role='spectate-mode-modal']")).toBeVisible();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 5_000, pollMs: 50 });
}

async function expectWatchlistActionsActionable(row) {
  const actionRoles = [
    "spectate-mode-watchlist-spectate",
    "spectate-mode-watchlist-copy",
    "spectate-mode-watchlist-link",
    "spectate-mode-watchlist-edit",
    "spectate-mode-watchlist-remove"
  ];

  for (const role of actionRoles) {
    const button = row.locator(`[data-role='${role}']`);
    await expect(button).toBeVisible();
    await button.click({ trial: true });
  }
}

async function exerciseSpectateModal(page, mode) {
  await visitRoute(page, "/trade");
  await openSpectateModal(page, mode);

  const modal = page.locator("[data-role='spectate-mode-modal']");
  const searchInput = modal.locator("[data-role='spectate-mode-search-input']");
  const labelInput = modal.locator("[data-role='spectate-mode-label-input']");
  const startButton = modal.locator("[data-role='spectate-mode-start']");
  const addButton = modal.locator("[data-role='spectate-mode-add-watchlist']");

  await expectWithinViewport(page, modal);
  await expect(searchInput).toBeVisible();

  await searchInput.fill("not an address");
  await expect(startButton).toBeDisabled();
  await expect(addButton).toHaveCount(0);

  await searchInput.fill(WATCHLIST_ADDRESS);
  await labelInput.fill(WATCHLIST_LABEL);
  await expect(startButton).toBeEnabled();
  await expect(startButton).toHaveText("Spectate");
  await expect(addButton).toBeEnabled();
  await expect(addButton).toHaveText("Add To Watchlist");

  await addButton.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 5_000, pollMs: 50 });

  const row = modal.locator("[data-role='spectate-mode-watchlist-row']", {
    hasText: WATCHLIST_LABEL
  });
  await expect(row).toBeVisible();
  await expect(row.locator("[data-role='spectate-mode-watchlist-address']"))
    .toContainText("0x2222");
  await expectWatchlistActionsActionable(row);

  await row.locator("[data-role='spectate-mode-watchlist-spectate']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 5_000, pollMs: 50 });

  await expect(page.locator("[data-role='spectate-mode-modal']")).toHaveCount(0);
  await expect(page.locator("[data-role='spectate-mode-active-banner']")).toBeVisible();
  await expect
    .poll(() => new URL(page.url()).searchParams.get("spectate"))
    .toBe(WATCHLIST_ADDRESS);

  await page.locator("[data-role='spectate-mode-banner-manage']").click();
  const activeModal = page.locator("[data-role='spectate-mode-modal']");
  await expect(activeModal).toBeVisible();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 5_000, pollMs: 50 });

  await expectWithinViewport(page, activeModal);
  await expect(activeModal.locator("[data-role='spectate-mode-stop']")).toBeVisible();
  await expect(activeModal.locator("[data-role='spectate-mode-active-summary']"))
    .toContainText(WATCHLIST_ADDRESS);
  await expect(activeModal.locator("[data-role='spectate-mode-start']")).toHaveText("Switch");

  await activeModal.locator("[data-role='spectate-mode-stop']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 5_000, pollMs: 50 });
  await expect(activeModal.locator("[data-role='spectate-mode-active-summary']")).toHaveCount(0);
  await expect(activeModal.locator("[data-role='spectate-mode-stop']")).toHaveCount(0);
  await expect.poll(() => new URL(page.url()).searchParams.get("spectate")).toBe(null);
  await expect(page.locator("[data-role='spectate-mode-modal']")).toHaveCount(0);
  await expect(page.locator("[data-role='spectate-mode-active-banner']")).toHaveCount(0);
}

test("spectate mode modal supports the desktop watchlist flow @smoke", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await exerciseSpectateModal(page, "desktop");
});

test.describe("mobile spectate modal smoke @smoke", () => {
  test.use(mobileViewport);

  test("spectate mode modal supports the mobile watchlist flow @smoke", async ({ page }) => {
    await exerciseSpectateModal(page, "mobile");
  });
});
