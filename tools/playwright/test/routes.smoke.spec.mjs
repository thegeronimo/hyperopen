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

test("trade desktop Vaults click stays in-app and shows the vault-shaped loader only @smoke", async ({ page }) => {
  const vaultsUrl = "https://stats-data.hyperliquid.xyz/Mainnet/vaults";
  const infoUrl = "https://api.hyperliquid.xyz/info";
  let releaseRouteModule;
  let routeModuleRequests = 0;
  let vaultIndexRequests = 0;
  const routeModuleGate = new Promise(resolve => {
    releaseRouteModule = resolve;
  });

  await page.route(/\/js\/vaults_route\.js(?:\?.*)?$/, async route => {
    routeModuleRequests += 1;
    await routeModuleGate;
    await route.continue();
  });

  await page.route(vaultsUrl, async route => {
    vaultIndexRequests += 1;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          apr: "0.12",
          summary: {
            name: "Smoke Vault",
            vaultAddress: "0x61b1cf5c2d7c4bf6d5db14f36651b2242e7cba0a",
            leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            tvl: "321.5",
            isClosed: false,
            relationship: { type: "normal" },
            createTimeMillis: "1700"
          }
        }
      ])
    });
  });

  await page.route(infoUrl, async route => {
    const payload = JSON.parse(route.request().postData() || "{}");
    if (payload?.type === "vaultSummaries") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([])
      });
      return;
    }

    await route.continue();
  });

  await visitRoute(page, "/trade");
  await page.evaluate(() => {
    sessionStorage.removeItem("__vaultBeforeUnloadCount");
    globalThis.__vaultNavMarker = "trade-still-alive";
    globalThis.addEventListener("beforeunload", () => {
      const current = Number(sessionStorage.getItem("__vaultBeforeUnloadCount") || "0");
      sessionStorage.setItem("__vaultBeforeUnloadCount", String(current + 1));
    });
  });

  const headerNav = page.locator("[data-parity-id='header-nav']");
  const vaultsLink = headerNav.getByRole("link", { name: "Vaults", exact: true });

  await expect(vaultsLink).toHaveAttribute("href", "/vaults");
  await vaultsLink.click();
  await expect
    .poll(() => routeModuleRequests, { timeout: 10_000 })
    .toBe(1);
  await expect(page.locator("[data-role='vaults-route-loading-shell']")).toBeVisible();
  await expect(page.locator("[data-role='vault-loading-row']").first()).toBeVisible();
  await expect(page.getByText("Loading Route", { exact: true })).toHaveCount(0);
  await expect
    .poll(() => page.evaluate(() => sessionStorage.getItem("__vaultBeforeUnloadCount") || "0"))
    .toBe("0");
  await expect(page.evaluate(() => globalThis.__vaultNavMarker)).resolves.toBe("trade-still-alive");

  releaseRouteModule();

  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "vaults-root" } }
  );
  await expect
    .poll(() => vaultIndexRequests, { timeout: 10_000 })
    .toBe(1);
  await expect(routeModuleRequests).toBe(1);
  await expect(page.evaluate(() => sessionStorage.getItem("__vaultBeforeUnloadCount") || "0")).resolves.toBe("0");
  await expect(page.evaluate(() => globalThis.__vaultNavMarker)).resolves.toBe("trade-still-alive");
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

test("trade footer shows the condensed build badge when the dev build asset is present @smoke", async ({ page }) => {
  await page.addInitScript(() => {
    globalThis.__copiedBuildInfo = [];
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        writeText(text) {
          globalThis.__copiedBuildInfo.push(text);
          return Promise.resolve();
        },
      },
    });
  });
  await visitRoute(page, "/trade");

  const buildId = page.locator("[data-role='footer-build-id']");
  const tooltip = page.locator("[data-role='footer-build-id-tooltip']");
  const envPill = page.locator("[data-role='footer-build-env']");
  const freshness = page.locator("[data-role='footer-build-freshness']");
  const sha = page.locator("[data-role='footer-build-sha']");
  const deployed = page.locator("[data-role='footer-build-deployed']");
  const copy = page.locator("[data-role='footer-build-copy']");
  const commitLink = page.locator("[data-role='footer-build-commit-link']");

  await expect(buildId).toBeVisible();
  await expect(buildId).toHaveText(/^[0-9a-f]{7}$/);
  await expect(buildId).not.toHaveAttribute("title", /.+/);
  await buildId.hover();
  await expect(tooltip).toBeVisible();
  await expect(tooltip).toHaveCSS("width", "280px");
  await expect(envPill).toHaveText(/^(prod|staging|dev)$/);
  await expect(freshness).toHaveCount(0);
  await expect(sha).toHaveAttribute("title", /^[0-9a-f]{40}$/);
  await expect(sha).toHaveCSS("white-space", "nowrap");
  await expect(deployed).toContainText("DEPLOYED");
  await expect(deployed).toContainText(/ago|unknown/);
  await expect(copy).toHaveAttribute("aria-label", "Copy build info");
  await expect(commitLink).toHaveCount(0);

  const tooltipBox = await tooltip.boundingBox();
  const viewport = page.viewportSize();
  expect(tooltipBox).not.toBeNull();
  expect(viewport).not.toBeNull();
  expect(tooltipBox.x).toBeGreaterThanOrEqual(0);
  expect(tooltipBox.x + tooltipBox.width).toBeLessThanOrEqual(viewport.width);

  await copy.click();
  await expect(copy).toHaveAttribute("data-copied", "true");
  await expect
    .poll(() => page.evaluate(() => globalThis.__copiedBuildInfo?.[0] || ""))
    .toContain("Build:");
  await page.keyboard.press("Escape");
  await expect(tooltip).not.toBeVisible();
});

test("trade route exposes score-bearing accessibility hooks @smoke", async ({ page }) => {
  await page.goto("/trade", { waitUntil: "commit" });

  await expect(page.locator("[data-parity-id='trade-root']")).toBeVisible();
  await expect(page.locator("main#main-content")).toBeVisible();
  await expect(page.locator("[data-parity-id='order-form']")).toBeVisible();
  await expect(page.locator(".order-size-slider")).toHaveAttribute("aria-label", "Order size percentage slider");
  await expect(page.locator(".order-size-percent-input")).toHaveAttribute("aria-label", "Order size percentage input");
  await expect(
    page.getByRole("button", { name: /margin mode: (cross|isolated)/i }).first()
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: /adjust leverage: \d+x/i }).first()
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: /time in force: [a-z]+/i }).first()
  ).toBeVisible();
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
  const leaderboardUrl = "https://stats-data.hyperliquid.xyz/Mainnet/leaderboard";
  const vaultsUrl = "https://stats-data.hyperliquid.xyz/Mainnet/vaults";

  await page.route(leaderboardUrl, async route => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        leaderboardRows: [
          {
            ethAddress: "0x1111111111111111111111111111111111111111",
            displayName: "Alpha Desk",
            accountValue: 1200000,
            windowPerformances: [["allTime", { pnl: 1200, roi: 0.2, vlm: 9000 }]]
          },
          {
            ethAddress: "0x3333333333333333333333333333333333333333",
            displayName: "Beta Desk",
            accountValue: 900000,
            windowPerformances: [["allTime", { pnl: 500, roi: 0.1, vlm: 4000 }]]
          }
        ]
      })
    });
  });

  await page.route(vaultsUrl, async route => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([])
    });
  });

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
