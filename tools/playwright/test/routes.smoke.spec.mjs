import { expect, test } from "@playwright/test";
import { dispatch, expectOracle, mobileViewport, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

const routeCases = [
  { name: "trade", route: "/trade", parityId: "trade-root" },
  { name: "staking", route: "/staking", parityId: "staking-root" },
  { name: "portfolio", route: "/portfolio", parityId: "portfolio-root" },
  {
    name: "trader-portfolio",
    route: "/portfolio/trader/0x3333333333333333333333333333333333333333",
    parityId: "portfolio-root"
  },
  { name: "leaderboard", route: "/leaderboard", parityId: "leaderboard-root" },
  { name: "vaults", route: "/vaults", parityId: "vaults-root" }
];

test.describe("main route smoke @smoke", () => {
  for (const routeCase of routeCases) {
    test(`${routeCase.name} desktop root renders @smoke`, async ({ page }) => {
      await visitRoute(page, routeCase.route);
      await expectOracle(
        page,
        "parity-element",
        { present: true },
        { args: { parityId: routeCase.parityId } }
      );
    });
  }
});

test("trade desktop header omits dead Earn and Referrals links @smoke", async ({ page }) => {
  await visitRoute(page, "/trade");

  const headerNav = page.locator("[data-parity-id='header-nav']");

  await expect(headerNav.getByRole("link", { name: "Trade", exact: true })).toBeVisible();
  await expect(headerNav.getByRole("link", { name: "Vaults", exact: true })).toBeVisible();
  await expect(headerNav.getByRole("link", { name: "Earn", exact: true })).toHaveCount(0);
  await expect(headerNav.getByRole("link", { name: "Referrals", exact: true })).toHaveCount(0);
});

test("trade cold startup does not render the static boot loading shell @smoke", async ({ page }) => {
  await page.goto("/trade", { waitUntil: "commit" });
  await page.waitForTimeout(100);
  await expect(page.locator("#boot-loading-shell")).toHaveCount(0);
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "trade-root" } }
  );
});

test.describe("main route smoke mobile @smoke", () => {
  test.use(mobileViewport);

  for (const routeCase of routeCases) {
    test(`${routeCase.name} mobile root renders @smoke`, async ({ page }) => {
      await visitRoute(page, routeCase.route);
      await expectOracle(
        page,
        "parity-element",
        { present: true },
        { args: { parityId: routeCase.parityId } }
      );
    });
  }

  test("trade mobile header menu omits dead Earn and Referrals links @smoke", async ({ page }) => {
    await visitRoute(page, "/trade");

    await page.locator("[data-role='mobile-header-menu-trigger']").click();

    await expect(page.locator("[data-role='mobile-header-menu-link-trade']")).toBeVisible();
    await expect(page.locator("[data-role='mobile-header-menu-link-vaults']")).toBeVisible();
    await expect(page.locator("[data-role='mobile-header-menu-link-earn']")).toHaveCount(0);
    await expect(page.locator("[data-role='mobile-header-menu-link-referrals']")).toHaveCount(0);
  });
});

test("leaderboard preferences persist across reload via IndexedDB @smoke", async ({ page }) => {
  await visitRoute(page, "/leaderboard");

  const allTimeButton = page.locator("[data-role='leaderboard-timeframes'] button", { hasText: "All Time" });
  const volumeHeader = page.getByRole("button", { name: "Volume" });
  const pageSizeButton = page.locator("#leaderboard-page-size");

  await allTimeButton.click();
  await volumeHeader.click();
  await pageSizeButton.click();
  await page.getByRole("option", { name: "25" }).click();

  await expect(pageSizeButton).toContainText("25");
  await page.reload();
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "leaderboard-root" } }
  );

  await expect(page.locator("#leaderboard-page-size")).toContainText("25");
  await expect(page.locator("[data-role='leaderboard-timeframes'] button", { hasText: "All Time" }))
    .toHaveClass(/text-\[#97fce4\]/);
  await expect(page.locator("button:has-text('Volume') svg")).toHaveClass(/rotate-0/);
});

test("leaderboard cache serves reload when live endpoints are blocked @smoke", async ({ page }) => {
  const leaderboardUrl = "https://stats-data.hyperliquid.xyz/Mainnet/leaderboard";
  const vaultsUrl = "https://stats-data.hyperliquid.xyz/Mainnet/vaults";
  let allowNetwork = true;
  let leaderboardRequests = 0;
  let vaultRequests = 0;

  await page.route(leaderboardUrl, async route => {
    leaderboardRequests += 1;
    if (!allowNetwork) {
      await route.abort();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        leaderboardRows: [
          {
            ethAddress: "0x1111111111111111111111111111111111111111",
            displayName: "Alpha Desk",
            accountValue: 1200000,
            windowPerformances: [["month", { pnl: 1200, roi: 0.2, vlm: 9000 }]]
          },
          {
            ethAddress: "0x2222222222222222222222222222222222222222",
            displayName: "Hidden Vault",
            accountValue: 900000,
            windowPerformances: [["month", { pnl: 500, roi: 0.1, vlm: 4000 }]]
          }
        ]
      })
    });
  });

  await page.route(vaultsUrl, async route => {
    vaultRequests += 1;
    if (!allowNetwork) {
      await route.abort();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          name: "Shadow Vault",
          vaultAddress: "0x2222222222222222222222222222222222222222",
          relationship: { type: "child" }
        }
      ])
    });
  });

  await visitRoute(page, "/leaderboard");
  await expect(page.getByText("Alpha Desk")).toBeVisible();
  await expect(page.getByText("Hidden Vault")).toHaveCount(0);
  await expect(leaderboardRequests).toBe(1);
  await expect(vaultRequests).toBe(1);

  allowNetwork = false;
  await page.reload();
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "leaderboard-root" } }
  );

  await expect(page.getByText("Alpha Desk")).toBeVisible();
  await expect(page.getByText("Hidden Vault")).toHaveCount(0);
  await expect(page.locator("[data-role='leaderboard-error']")).toHaveCount(0);
  await expect(leaderboardRequests).toBe(1);
  await expect(vaultRequests).toBe(1);
});

test("leaderboard cache serves in-app revisit without hitting live endpoints again @smoke", async ({ page }) => {
  const leaderboardUrl = "https://stats-data.hyperliquid.xyz/Mainnet/leaderboard";
  const vaultsUrl = "https://stats-data.hyperliquid.xyz/Mainnet/vaults";
  let allowNetwork = true;
  let leaderboardRequests = 0;
  let vaultRequests = 0;

  await page.route(leaderboardUrl, async route => {
    leaderboardRequests += 1;
    if (!allowNetwork) {
      await route.abort();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        leaderboardRows: [
          {
            ethAddress: "0x1111111111111111111111111111111111111111",
            displayName: "Alpha Desk",
            accountValue: 1200000,
            windowPerformances: [["month", { pnl: 1200, roi: 0.2, vlm: 9000 }]]
          },
          {
            ethAddress: "0x2222222222222222222222222222222222222222",
            displayName: "Hidden Vault",
            accountValue: 900000,
            windowPerformances: [["month", { pnl: 500, roi: 0.1, vlm: 4000 }]]
          }
        ]
      })
    });
  });

  await page.route(vaultsUrl, async route => {
    vaultRequests += 1;
    if (!allowNetwork) {
      await route.abort();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          name: "Shadow Vault",
          vaultAddress: "0x2222222222222222222222222222222222222222",
          relationship: { type: "child" }
        }
      ])
    });
  });

  await visitRoute(page, "/leaderboard");
  await expect(page.getByText("Alpha Desk")).toBeVisible();
  await expect(page.getByText("Hidden Vault")).toHaveCount(0);
  await expect(leaderboardRequests).toBe(1);
  await expect(vaultRequests).toBe(1);

  allowNetwork = false;
  await dispatch(page, [":actions/navigate", "/vaults", { "replace?": true }]);
  await waitForIdle(page);
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "vaults-root" } }
  );
  const leaderboardRequestsAfterLeaving = leaderboardRequests;
  const vaultRequestsAfterLeaving = vaultRequests;

  await dispatch(page, [":actions/navigate", "/leaderboard", { "replace?": true }]);
  await waitForIdle(page);
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "leaderboard-root" } }
  );

  await expect(page.getByText("Alpha Desk")).toBeVisible();
  await expect(page.getByText("Hidden Vault")).toHaveCount(0);
  await expect(page.locator("[data-role='leaderboard-error']")).toHaveCount(0);
  await expect(leaderboardRequestsAfterLeaving).toBe(1);
  await expect(vaultRequestsAfterLeaving).toBeGreaterThanOrEqual(1);
  await expect(leaderboardRequests).toBe(leaderboardRequestsAfterLeaving);
  await expect(vaultRequests).toBe(vaultRequestsAfterLeaving);
});
