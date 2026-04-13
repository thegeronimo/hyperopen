import { expect, test } from "@playwright/test";
import {
  dispatch,
  dispatchMany,
  expectOracle,
  mobileViewport,
  oracle,
  sourceRectForLocator,
  visitRoute,
  waitForIdle
} from "../support/hyperopen.mjs";

const spectateRoute =
  "/trade?spectate=0x162cc7c861ebd0c06b3d72319201150482518185";

test.describe("mobile browser regressions @mobile", () => {
  test.use(mobileViewport);

  test("account surface positions tab stays reachable on mobile @regression", async ({ page }) => {
    await visitRoute(page, spectateRoute);

    await dispatchMany(page, [
      [":actions/select-trade-mobile-surface", ":account"],
      [":actions/select-account-info-tab", ":positions"]
    ]);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });
    await expectOracle(page, "account-surface", {
      mobileSurface: "account",
      selectedTab: "positions",
      mobileAccountPanelPresent: true
    });
  });

  test("position margin opens as a mobile sheet @regression", async ({ page }) => {
    await visitRoute(page, spectateRoute);

    await dispatchMany(page, [
      [":actions/select-trade-mobile-surface", ":chart"],
      [":actions/select-account-info-tab", ":positions"]
    ]);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });
    await expectOracle(
      page,
      "first-position",
      { present: true },
      { timeoutMs: 8_000 }
    );

    const firstPosition = await oracle(page, "first-position");
    const sourceRect = await sourceRectForLocator(
      page,
      page.locator("[data-role^='mobile-position-card-']").first()
    );

    await dispatch(page, [
      ":actions/open-position-margin-modal",
      firstPosition.positionData,
      sourceRect
    ]);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });
    await expectOracle(
      page,
      "position-overlay",
      {
        open: true,
        presentationMode: "mobile-sheet"
      },
      { args: { surface: "margin" } }
    );
  });

  test("mobile positions list clears the fixed bottom nav @regression", async ({ page }) => {
    await visitRoute(page, spectateRoute);

    await dispatchMany(page, [
      [":actions/select-trade-mobile-surface", ":chart"],
      [":actions/select-account-info-tab", ":positions"]
    ]);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });

    const viewport = page.locator("[data-role='positions-mobile-cards-viewport']");
    const lastCard = page.locator(
      "[data-role='positions-mobile-cards-viewport'] [data-role^='mobile-position-card-']"
    ).last();
    const bottomNav = page.locator("[data-role='mobile-bottom-nav']");

    await expect(lastCard).toBeVisible({ timeout: 15_000 });
    const cardCount = await page.evaluate(() =>
      document.querySelectorAll(
        "[data-role='positions-mobile-cards-viewport'] [data-role^='mobile-position-card-']"
      ).length
    );

    expect(cardCount).toBeGreaterThan(0);
    await expect
      .poll(
        () =>
          page.evaluate(() => {
            const viewportNode = document.querySelector(
              "[data-role='positions-mobile-cards-viewport']"
            );
            const cards = Array.from(
              document.querySelectorAll(
                "[data-role='positions-mobile-cards-viewport'] [data-role^='mobile-position-card-']"
              )
            );
            const lastCardNode = cards.at(-1);
            const bottomNavNode = document.querySelector("[data-role='mobile-bottom-nav']");

            if (!viewportNode || !lastCardNode || !bottomNavNode) {
              throw new Error("required mobile positions nodes missing");
            }

            viewportNode.scrollTop = viewportNode.scrollHeight;
            return (
              lastCardNode.getBoundingClientRect().bottom -
              bottomNavNode.getBoundingClientRect().top
            );
          }),
        { timeout: 5_000 }
      )
      .toBeLessThanOrEqual(0);
    await expect(viewport).toBeVisible();
    await expect(bottomNav).toBeVisible();
  });

  test("mobile balances list clears the fixed bottom nav @regression", async ({ page }) => {
    await visitRoute(page, spectateRoute);

    await dispatchMany(page, [
      [":actions/select-trade-mobile-surface", ":chart"],
      [":actions/select-account-info-tab", ":balances"]
    ]);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });

    const viewport = page.locator("[data-role='balances-mobile-cards-viewport']");
    const lastCard = page.locator(
      "[data-role='balances-mobile-cards-viewport'] [data-role^='mobile-balance-card-']"
    ).last();
    const bottomNav = page.locator("[data-role='mobile-bottom-nav']");

    await expect(lastCard).toBeVisible({ timeout: 15_000 });
    const cardCount = await page.evaluate(() =>
      document.querySelectorAll(
        "[data-role='balances-mobile-cards-viewport'] [data-role^='mobile-balance-card-']"
      ).length
    );

    expect(cardCount).toBeGreaterThan(0);
    await expect
      .poll(
        () =>
          page.evaluate(() => {
            const viewportNode = document.querySelector(
              "[data-role='balances-mobile-cards-viewport']"
            );
            const cards = Array.from(
              document.querySelectorAll(
                "[data-role='balances-mobile-cards-viewport'] [data-role^='mobile-balance-card-']"
              )
            );
            const lastCardNode = cards.at(-1);
            const bottomNavNode = document.querySelector("[data-role='mobile-bottom-nav']");

            if (!viewportNode || !lastCardNode || !bottomNavNode) {
              throw new Error("required mobile balances nodes missing");
            }

            viewportNode.scrollTop = viewportNode.scrollHeight;
            return (
              lastCardNode.getBoundingClientRect().bottom -
              bottomNavNode.getBoundingClientRect().top
            );
          }),
        { timeout: 5_000 }
      )
      .toBeLessThanOrEqual(0);
    await expect(viewport).toBeVisible();
    await expect(bottomNav).toBeVisible();
  });
});
