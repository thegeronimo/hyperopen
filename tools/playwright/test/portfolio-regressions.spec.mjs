import { expect, test } from "@playwright/test";
import { debugCall, dispatch, sourceRectForLocator, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

const TRADER_ADDRESS = "0x3333333333333333333333333333333333333333";
const SPECTATE_ADDRESS = "0x162cc7c861ebd0c06b3d72319201150482518185";

async function selectSummaryScope(page, scopeValue, expectedLabel) {
  const trigger = page.locator("[data-role='portfolio-summary-scope-selector-trigger']");
  const option = page.locator(`[data-role='portfolio-summary-scope-selector-option-${scopeValue}']`);

  await expect(trigger).not.toHaveAttribute("aria-expanded", "true");
  await trigger.click();
  await expect(trigger).toHaveAttribute("aria-expanded", "true");
  await expect(option).toBeVisible();

  await option.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(trigger).not.toHaveAttribute("aria-expanded", "true");
  await expect(trigger).toContainText(expectedLabel);
}

async function selectChartTab(page, tabValue) {
  const tab = page.locator(`[data-role='portfolio-chart-tab-${tabValue}']`);
  await tab.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tab).toHaveAttribute("aria-pressed", "true");
}

async function selectAccountTab(page, tabValue) {
  const tab = page.locator(`[data-role='account-info-tab-${tabValue}']`);
  await tab.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(tab).toHaveAttribute("aria-pressed", "true");
}

test("portfolio route exposes deterministic interaction states @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio");

  await expect(page.locator("[data-role='portfolio-actions-row']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-action-deposit']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-action-withdraw']")).toBeVisible();

  await selectSummaryScope(page, "perps", "Perps");
  await selectChartTab(page, "pnl");
  await selectAccountTab(page, "balances");

  await expect(page.locator("[data-role='account-info-tab-performance-metrics']"))
    .not.toHaveAttribute("aria-pressed", "true");
});

test("portfolio funding modal restores opener focus on close @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio");

  const openButton = page.locator("[data-role='portfolio-action-deposit']");
  await expect(openButton).toBeVisible();
  await openButton.focus();
  await dispatch(page, [
    ":actions/open-funding-deposit-modal",
    await sourceRectForLocator(page, openButton),
    await openButton.getAttribute("data-role")
  ]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  const dialog = page.locator("[data-role='funding-modal']");
  const closeButton = page.locator("[data-role='funding-modal-close']");

  await expect(dialog).toBeVisible();
  await expect(closeButton).toBeFocused();

  await closeButton.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(dialog).toBeHidden();
  await expect(openButton).toBeFocused();
});

test("trader portfolio route stays read-only while reusing stable controls @regression", async ({ page }) => {
  await visitRoute(page, `/portfolio/trader/${TRADER_ADDRESS}`);
  const accountTable = page.locator("[data-role='portfolio-account-table']");

  await expect(page.locator("[data-role='portfolio-inspection-header']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-actions-row']")).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-action-deposit']")).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-inspection-explorer-link']"))
    .toHaveAttribute("href", `https://app.hyperliquid.xyz/explorer/address/${TRADER_ADDRESS}`);

  await selectSummaryScope(page, "perps", "Perps");
  await selectChartTab(page, "pnl");
  await selectAccountTab(page, "balances");
  await expect(accountTable).toContainText("Contract");
  await expect(accountTable).not.toContainText("Send");
  await expect(accountTable).not.toContainText("Transfer");

  await selectAccountTab(page, "positions");
  await expect(accountTable).not.toContainText("Close All");

  await selectAccountTab(page, "open-orders");
  await expect(accountTable).not.toContainText("Cancel All");

  await selectAccountTab(page, "twap");
  await expect(accountTable).not.toContainText("Terminate");

  await page.locator("[data-role='portfolio-inspection-own-portfolio']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect.poll(async () => {
    const snapshot = await debugCall(page, "qaSnapshot");
    return snapshot.route;
  }).toBe("/portfolio");

  await expect(page.locator("[data-role='portfolio-actions-row']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-action-deposit']")).toBeVisible();
});

test("portfolio positions coin jumps to the trade route market @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio");
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const opts = c.PersistentArrayMap.fromArray([kw("keywordize-keys"), true], true);
    const payload = {
      clearinghouseState: {
        assetPositions: [
          {
            position: {
              coin: "HYPE",
              szi: "1.25",
              positionValue: "2500",
              entryPx: "100",
              markPx: "101",
              unrealizedPnl: "12",
              returnOnEquity: "0.10",
              leverage: { value: 10 },
              cumFunding: { allTime: "0" }
            }
          }
        ]
      }
    };
    const state = c.deref(globalThis.hyperopen.system.store);
    const nextState = c.assoc_in(
      state,
      c.PersistentVector.fromArray([kw("webdata2")], true),
      c.js__GT_clj(payload, opts)
    );
    c.reset_BANG_(globalThis.hyperopen.system.store, nextState);
  });
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await selectAccountTab(page, "positions");
  const coinButton = page
    .locator("[data-role='portfolio-account-table'] [data-role='positions-coin-select']")
    .first();

  await expect(coinButton).toBeVisible();
  await coinButton.click();
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

  await expect.poll(async () => {
    const snapshot = await debugCall(page, "qaSnapshot");
    return {
      route: snapshot.route,
      activeAsset: snapshot.activeAsset
    };
  }).toMatchObject({
    route: "/trade/HYPE",
    activeAsset: "HYPE"
  });
});

test("spectate mode stays active when navigating from trade to portfolio via header nav @regression", async ({ page }) => {
  await visitRoute(page, "/trade");
  await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const spectateBanner = page.locator("[data-role='spectate-mode-active-banner']");
  const portfolioLink = page
    .locator("[data-parity-id='header-nav']")
    .getByRole("link", { name: "Portfolio", exact: true });

  await expect(spectateBanner).toBeVisible();
  await expect.poll(() => new URL(page.url()).searchParams.get("spectate")).toBe(SPECTATE_ADDRESS);
  await expect(portfolioLink).toHaveAttribute("href", `/portfolio?spectate=${SPECTATE_ADDRESS}`);

  await portfolioLink.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect.poll(async () => {
    const snapshot = await debugCall(page, "qaSnapshot");
    return {
      route: snapshot.route,
      spectate: new URL(page.url()).searchParams.get("spectate")
    };
  }).toMatchObject({
    route: "/portfolio",
    spectate: SPECTATE_ADDRESS
  });

  await expect(spectateBanner).toBeVisible();
  await expect(page.locator("[data-role='portfolio-actions-row']")).toBeVisible();
});
