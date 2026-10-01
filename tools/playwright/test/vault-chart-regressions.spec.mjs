import { expect, test } from "../support/guarded_test.mjs";
import {
  dispatch,
  hoverLocatorAtRatio,
  visitRoute,
  waitForIdle
} from "../support/hyperopen.mjs";

const VAULT_ADDRESS = "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303";
const LEADER_ADDRESS = "0x677d00000000000000000000000000000008a4e7";
const REVIEW_VIEWPORTS = [
  { label: "review-375", width: 375, height: 812 },
  { label: "review-768", width: 768, height: 1024 },
  { label: "review-1280", width: 1280, height: 900 },
  { label: "review-1440", width: 1440, height: 900 }
];

const T0 = Date.UTC(2025, 4, 1);
const T1 = Date.UTC(2025, 7, 1);
const T2 = Date.UTC(2025, 10, 1);
const T3 = Date.UTC(2026, 1, 18);

function vaultDetailsFixture() {
  const summary = {
    accountValueHistory: [
      [T0, 100],
      [T1, 125],
      [T2, 120],
      [T3, 140]
    ],
    pnlHistory: [
      [T0, 0],
      [T1, 25],
      [T2, 20],
      [T3, 40]
    ]
  };

  return {
    name: "Tooltip Vault",
    vaultAddress: VAULT_ADDRESS,
    leader: LEADER_ADDRESS,
    description: "Deterministic tooltip fixture",
    apr: "0.12",
    portfolio: [
      ["allTime", summary],
      ["oneYear", summary]
    ],
    followers: [],
    relationship: { type: "normal" },
    allowDeposits: false,
    alwaysCloseOnWithdraw: false
  };
}

function marketCandleFixture(coin) {
  switch (coin) {
    case "BTC":
      return [
        { t: T0, c: 100 },
        { t: T1, c: 90 },
        { t: T2, c: 95 },
        { t: T3, c: 110 }
      ];

    case "HYPE":
      return [
        { t: T0, c: 100 },
        { t: T1, c: 150 },
        { t: T2, c: 140 },
        { t: T3, c: 160 }
      ];

    default:
      return [];
  }
}

async function stubVaultDetailTooltipFixture(page) {
  await page.route("https://stats-data.hyperliquid.xyz/Mainnet/vaults", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          apr: "0.12",
          summary: {
            name: "Tooltip Vault",
            vaultAddress: VAULT_ADDRESS,
            leader: LEADER_ADDRESS,
            tvl: "321.5",
            isClosed: false,
            relationship: { type: "normal" },
            createTimeMillis: String(T0)
          }
        }
      ])
    });
  });

  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;
    const requestVaultAddress = String(
      payload?.vaultAddress || payload?.user || ""
    ).toLowerCase();

    if (requestType === "vaultSummaries") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([])
      });
      return;
    }

    if (
      requestType === "vaultDetails" &&
      requestVaultAddress === VAULT_ADDRESS
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(vaultDetailsFixture())
      });
      return;
    }

    if (
      requestType === "webData2" &&
      requestVaultAddress === VAULT_ADDRESS
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({})
      });
      return;
    }

    if (requestType === "candleSnapshot") {
      const coin = String(payload?.req?.coin || "").trim();
      const candles = marketCandleFixture(coin);
      if (candles.length > 0) {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(candles)
        });
        return;
      }
    }

    await route.continue();
  });
}

test("vault detail range menu uses phone-sized touch targets @regression", async ({ page }) => {
  await stubVaultDetailTooltipFixture(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await visitRoute(page, `/vaults/${VAULT_ADDRESS}`);

  const trigger = page.locator("[data-role='vault-detail-chart-timeframe-trigger']");
  await trigger.scrollIntoViewIfNeeded();
  await expect(trigger).toBeVisible();

  const triggerBox = await trigger.boundingBox();
  expect(triggerBox?.height ?? 0).toBeGreaterThanOrEqual(44);

  await trigger.click();
  const option = page.locator("[data-role='vault-detail-chart-timeframe-option-one-year']");
  await expect(option).toBeVisible();

  const optionBox = await option.boundingBox();
  expect(optionBox?.height ?? 0).toBeGreaterThanOrEqual(44);
});

test("vault performance metrics keep scrolled value columns under their headers on mobile @regression", async ({ page }) => {
  await stubVaultDetailTooltipFixture(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await visitRoute(page, `/vaults/${VAULT_ADDRESS}`);

  await expect(page.locator("[data-parity-id='vault-detail-root']")).toBeVisible();
  await dispatch(page, [":actions/select-vault-detail-returns-benchmark", "HYPE"]);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

  const scrollRegion = page.locator("[data-role='vault-detail-performance-metrics-scroll-region']");
  await scrollRegion.scrollIntoViewIfNeeded();
  await expect(scrollRegion).toBeVisible();

  const horizontalScroll = await scrollRegion.evaluate((node) => {
    node.scrollLeft = 160;
    return {
      scrollLeft: node.scrollLeft,
      maxScrollLeft: node.scrollWidth - node.clientWidth
    };
  });

  expect(horizontalScroll.maxScrollLeft).toBeGreaterThan(0);
  expect(horizontalScroll.scrollLeft).toBeGreaterThan(0);

  const vaultLabel = page.locator("[data-role='vault-detail-performance-metrics-vault-label']");
  const hypeLabel = page.locator("[data-role='vault-detail-performance-metrics-benchmark-label-HYPE']");
  const vaultValue = page.locator(
    "[data-role='vault-detail-performance-metric-cumulative-return-vault-value']"
  );
  await expect(vaultLabel).toHaveText("Tooltip Vault");
  await expect(hypeLabel).toContainText("HYPE");
  await expect(vaultValue).toBeVisible();

  const headerPaintCoverage = await page.evaluate(() => {
    const label = document.querySelector("[data-role='vault-detail-performance-metrics-benchmark-label-HYPE']");
    const header = label.parentElement;
    return header.getBoundingClientRect().right - label.getBoundingClientRect().right;
  });

  expect(headerPaintCoverage).toBeGreaterThanOrEqual(0);

  const alignment = await page.evaluate(() => {
    const label = document.querySelector("[data-role='vault-detail-performance-metrics-vault-label']");
    const value = document.querySelector(
      "[data-role='vault-detail-performance-metric-cumulative-return-vault-value']"
    );
    return Math.abs(label.getBoundingClientRect().left - value.getBoundingClientRect().left);
  });

  expect(alignment).toBeLessThanOrEqual(4);
});

for (const viewport of REVIEW_VIEWPORTS) {
  test(`vault detail returns tooltip labels the selected vault after hover ${viewport.label} @regression`, async ({ page }) => {
    await stubVaultDetailTooltipFixture(page);
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await visitRoute(page, `/vaults/${VAULT_ADDRESS}`);

    await expect(page.locator("[data-parity-id='vault-detail-root']")).toBeVisible();

    await dispatch(page, [":actions/set-vault-detail-chart-series", ":returns"]);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

    await dispatch(page, [":actions/set-vaults-snapshot-range", ":one-year"]);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

    await dispatch(page, [":actions/select-vault-detail-returns-benchmark", "BTC"]);
    await dispatch(page, [":actions/select-vault-detail-returns-benchmark", "HYPE"]);

    await expect
      .poll(async () => {
        const paths = await page
          .locator("[data-role^='vault-detail-chart-path-benchmark-']")
          .evaluateAll((nodes) => nodes.map((node) => node.getAttribute("d") || ""));
        return paths.filter(Boolean).length;
      }, { timeout: 10_000 })
      .toBeGreaterThanOrEqual(2);

    const plotArea = page.locator("[data-role='vault-detail-chart-plot-area']");
    const tooltip = page.locator("[data-role='vault-detail-chart-hover-tooltip']");
    await plotArea.scrollIntoViewIfNeeded();
    await expect(
      page.locator("[data-role='vault-detail-performance-metrics-vault-label']"),
      `${viewport.label} selected-vault metrics column`
    ).toHaveText("Tooltip Vault");

    await expect
      .poll(async () => {
        await hoverLocatorAtRatio(page, plotArea, { xRatio: 0.8, yRatio: 0.45 });
        const text = (await tooltip.textContent()) || "";
        return ["Tooltip Vault Returns", "BTC", "HYPE"].every((snippet) =>
          text.includes(snippet)
        );
      }, { message: `${viewport.label} chart tooltip selected-vault label`, timeout: 6_000 })
      .toBe(true);
  });
}
