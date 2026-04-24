import { expect, test } from "@playwright/test";
import {
  debugCall,
  dispatch,
  expectOracle,
  sourceRectForLocator,
  visitRoute,
  waitForIdle
} from "../support/hyperopen.mjs";

const TRADER_ADDRESS = "0x3333333333333333333333333333333333333333";
const SPECTATE_ADDRESS = "0x162cc7c861ebd0c06b3d72319201150482518185";
const VOLUME_HISTORY_FIXTURE = {
  dailyUserVlm: [
    {
      date: "2026-04-03",
      exchange: 2_655_076_900.23,
      userCross: 130_550_000,
      userAdd: 219_830_000
    },
    {
      date: "2026-04-04",
      exchange: 1_346_037_058.89,
      userCross: 66_210_000,
      userAdd: 121_590_000
    },
    {
      date: "2026-04-05",
      exchange: 2_709_694_881.11,
      userCross: 140_640_000,
      userAdd: 245_600_000
    },
    {
      date: "2026-04-06",
      exchange: 5_184_032_316.32,
      userCross: 275_420_000,
      userAdd: 468_930_000
    },
    {
      date: "2026-04-07",
      exchange: 7_395_657_172.08,
      userCross: 425_220_000,
      userAdd: 593_900_000
    },
    {
      date: "2026-04-08",
      exchange: 6_112_033_361.92,
      userCross: 322_570_000,
      userAdd: 459_100_000
    },
    {
      date: "2026-04-09",
      exchange: 5_818_326_367.93,
      userCross: 315_080_000,
      userAdd: 416_950_000
    },
    {
      date: "2026-04-10",
      exchange: 4_455_513_989.13,
      userCross: 287_960_000,
      userAdd: 386_750_000
    },
    {
      date: "2026-04-11",
      exchange: 2_510_109_441.87,
      userCross: 196_400_000,
      userAdd: 201_910_000
    },
    {
      date: "2026-04-12",
      exchange: 3_910_442_359.76,
      userCross: 298_760_000,
      userAdd: 227_500_000
    },
    {
      date: "2026-04-13",
      exchange: 5_852_384_179.01,
      userCross: 420_570_000,
      userAdd: 448_150_000
    },
    {
      date: "2026-04-14",
      exchange: 6_280_954_566.8,
      userCross: 393_030_000,
      userAdd: 429_830_000
    },
    {
      date: "2026-04-15",
      exchange: 4_531_036_402.43,
      userCross: 296_770_000,
      userAdd: 322_300_000
    },
    {
      date: "2026-04-16",
      exchange: 5_729_184_977.88,
      userCross: 294_100_000,
      userAdd: 375_590_000
    },
    {
      date: "2026-04-17",
      exchange: 5_009_186_442.41,
      userCross: 1,
      userAdd: 1
    }
  ]
};

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

async function expectFundingPopoverAnchoredLeftOfTrigger(page, trigger) {
  const modal = page.locator("[data-role='funding-modal']");
  const [triggerBox, modalBox] = await Promise.all([
    trigger.boundingBox(),
    modal.boundingBox()
  ]);

  expect(triggerBox).not.toBeNull();
  expect(modalBox).not.toBeNull();

  const horizontalGap = triggerBox.x - (modalBox.x + modalBox.width);
  expect(horizontalGap).toBeGreaterThanOrEqual(4);
  expect(horizontalGap).toBeLessThanOrEqual(16);
  expect(Math.abs(modalBox.y - Math.max(12, triggerBox.y - 20))).toBeLessThanOrEqual(8);
}

async function seedPortfolioVolumeHistory(page) {
  await page.evaluate((payload) => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const opts = c.PersistentArrayMap.fromArray([kw("keywordize-keys"), true], true);
    const walletAddress = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const stateWithWallet = c.assoc_in(
      c.deref(globalThis.hyperopen.system.store),
      c.PersistentVector.fromArray([kw("wallet"), kw("address")], true),
      walletAddress
    );
    const stateWithUserFees = c.assoc_in(
      stateWithWallet,
      c.PersistentVector.fromArray([kw("portfolio"), kw("user-fees")], true),
      c.js__GT_clj(payload, opts)
    );
    const nextState = c.assoc_in(
      stateWithUserFees,
      c.PersistentVector.fromArray([kw("portfolio"), kw("user-fees-loaded-for-address")], true),
      walletAddress
    );
    c.reset_BANG_(globalThis.hyperopen.system.store, nextState);
  }, VOLUME_HISTORY_FIXTURE);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function seedExpandedTradeBlotterToast(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const fill = (id, qty, price, ts) =>
      c.PersistentArrayMap.fromArray(
        [
          keyword("id"), id,
          keyword("side"), keyword("buy"),
          keyword("symbol"), "HYPE",
          keyword("qty"), qty,
          keyword("price"), price,
          keyword("orderType"), "limit",
          keyword("ts"), ts
        ],
        true
      );
    const fills = c.PersistentVector.fromArray(
      [
        fill("fill-1", 0.25, 44.2, 1800000000000),
        fill("fill-2", 0.3, 44.3, 1800000003300),
        fill("fill-3", 0.4, 44.4, 1800000006600),
        fill("fill-4", 0.5, 44.5, 1800000009900)
      ],
      true
    );
    const toast = c.PersistentArrayMap.fromArray(
      [
        keyword("id"), "blotter",
        keyword("kind"), keyword("success"),
        keyword("toast-surface"), keyword("trade-confirmation"),
        keyword("variant"), keyword("consolidated"),
        keyword("expanded?"), true,
        keyword("fills"), fills
      ],
      true
    );
    const nextState = c.assoc_in(
      c.deref(store),
      kwPath("ui", "toasts"),
      c.PersistentVector.fromArray([toast], true)
    );

    c.reset_BANG_(store, nextState);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function stubPortfolioUserFees(page, observedRequests = []) {
  await page.route("**/info", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      try {
        const payload = request.postDataJSON();
        if (payload?.type === "userFees") {
          observedRequests.push(payload);
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify(VOLUME_HISTORY_FIXTURE)
          });
          return;
        }
      } catch {
        // Let non-JSON info requests follow the normal route.
      }
    }
    await route.continue();
  });
}

async function unroutePortfolioUserFees(page) {
  await page.unroute("**/info");
}

test("portfolio route exposes deterministic interaction states @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio");

  await expect(page.locator("[data-role='portfolio-actions-row']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-action-perps-spot']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-action-deposit']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-action-withdraw']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-action-swap-stablecoins']")).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-action-evm-core']")).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-action-portfolio-margin']")).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-action-send']")).toHaveCount(0);

  await selectSummaryScope(page, "perps", "Perps");
  await selectChartTab(page, "pnl");
  await selectAccountTab(page, "balances");

  await expect(page.locator("[data-role='account-info-tab-performance-metrics']"))
    .not.toHaveAttribute("aria-pressed", "true");
});

test("portfolio optimizer setup exposes separate model layers @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");

  await expect(page.locator("[data-role='portfolio-optimizer-workspace']")).toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-objective-panel']"))
    .toContainText("Minimum Variance");
  await expect(page.locator("[data-role='portfolio-optimizer-return-model-panel']"))
    .toContainText("Historical Mean");
  await expect(page.locator("[data-role='portfolio-optimizer-return-model-panel']"))
    .toContainText("Black-Litterman");
  await expect(page.locator("[data-role='portfolio-optimizer-risk-model-panel']"))
    .toContainText("Ledoit-Wolf");
  await expect(page.locator("[data-role='portfolio-optimizer-constraints-panel']"))
    .toContainText("Max Asset Weight");
  await expect(page.locator("[data-role='portfolio-optimizer-constraints-panel']"))
    .toContainText("Gross Leverage");
  await expect(page.locator("[data-role='portfolio-optimizer-constraints-panel']"))
    .toContainText("Rebalance Tolerance");

  const maxSharpe = page.locator("[data-role='portfolio-optimizer-objective-max-sharpe']");
  const blackLitterman = page.locator("[data-role='portfolio-optimizer-return-model-black-litterman']");
  const sampleCovariance = page.locator("[data-role='portfolio-optimizer-risk-model-sample-covariance']");

  await expect(maxSharpe).toHaveAttribute("aria-pressed", "false");
  await maxSharpe.click();
  await expect(maxSharpe).toHaveAttribute("aria-pressed", "true");
  await expect(maxSharpe).toContainText("Active");

  await expect(blackLitterman).toHaveAttribute("aria-pressed", "false");
  await blackLitterman.click();
  await expect(blackLitterman).toHaveAttribute("aria-pressed", "true");
  await expect(blackLitterman).toContainText("Active");

  await expect(sampleCovariance).toHaveAttribute("aria-pressed", "false");
  await sampleCovariance.click();
  await expect(sampleCovariance).toHaveAttribute("aria-pressed", "true");
  await expect(sampleCovariance).toContainText("Active");
});

test("portfolio volume history opens near the metric card trigger @regression", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await stubPortfolioUserFees(page);
  await visitRoute(page, "/portfolio");
  await seedPortfolioVolumeHistory(page);

  const trigger = page.locator("[data-role='portfolio-volume-history-trigger']");
  const popover = page.locator("[data-role='portfolio-volume-history-popover']");
  const closeButton = page.locator("[data-role='portfolio-volume-history-close']");
  const tableFrame = page.locator("[data-role='portfolio-volume-history-table-frame']");

  await expect(popover).toHaveCount(0);
  await expect(trigger).toBeVisible();
  await trigger.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(popover).toBeVisible();
  await expect(popover).toContainText("Your Volume History");
  await expect(popover).toContainText("Exchange Volume");
  await expect(popover).toContainText("Your Weighted Maker Volume");
  await expect(popover).toContainText("Your Weighted Taker Volume");
  await expect(popover).toContainText("Thu. 16. Apr. 2026");
  await expect(popover).toContainText("Fri. 3. Apr. 2026");
  await expect(page.locator("[data-role='portfolio-volume-history-day-row']")).toHaveCount(14);
  await expect(popover).toContainText("$5.73b");
  await expect(popover).toContainText("$375.59m");
  await expect(popover).toContainText("$64.49b");
  await expect(popover).toContainText("$4.92b");
  await expect(popover).toContainText("Your 14 day maker volume share is 7.63%");
  await expect(popover).not.toContainText("Dates do not include the current day");
  await expect(page.locator("[data-role='portfolio-volume-history-total-row']"))
    .toContainText("Total");
  await expect(tableFrame).toBeVisible();
  await expect.poll(async () => (
    await page.evaluate(() => {
      const triggerEl = document.querySelector("[data-role='portfolio-volume-history-trigger']");
      const popoverEl = document.querySelector("[data-role='portfolio-volume-history-popover']");
      if (!triggerEl || !popoverEl) return Number.POSITIVE_INFINITY;
      return Math.abs(popoverEl.getBoundingClientRect().top - triggerEl.getBoundingClientRect().top);
    })
  )).toBeLessThan(96);
  await expect.poll(async () => (
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)
  )).toBe(true);
  await expect.poll(async () => (
    await tableFrame.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)
  )).toBe(true);

  await closeButton.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(popover).toHaveCount(0);

  await trigger.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(popover).toBeVisible();
  await page.keyboard.press("Escape");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(popover).toHaveCount(0);
  await unroutePortfolioUserFees(page);
});

test("portfolio volume history follows the spectated account user fees @regression", async ({ page }) => {
  const observedUserFeesRequests = [];
  await stubPortfolioUserFees(page, observedUserFeesRequests);
  await visitRoute(page, `/portfolio?spectate=${SPECTATE_ADDRESS}`);

  await expect(page.locator("[data-role='spectate-mode-active-banner']")).toBeVisible();
  await expect.poll(() => observedUserFeesRequests.map((request) => request.user))
    .toContain(SPECTATE_ADDRESS);
  await expect.poll(async () => page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    return c.get_in(
      c.deref(globalThis.hyperopen.system.store),
      c.PersistentVector.fromArray([kw("portfolio"), kw("user-fees-loaded-for-address")], true)
    );
  })).toBe(SPECTATE_ADDRESS);

  const trigger = page.locator("[data-role='portfolio-volume-history-trigger']");
  const popover = page.locator("[data-role='portfolio-volume-history-popover']");
  await trigger.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(popover).toBeVisible();
  await expect(page.locator("[data-role='portfolio-volume-history-day-row']")).toHaveCount(14);
  await expect(popover).toContainText("Thu. 16. Apr. 2026");
  await expect(popover).toContainText("$5.73b");
  await expect(popover).toContainText("$375.59m");
  await expect(popover).toContainText("$294.10m");
  await expect(popover).toContainText("$64.49b");
  await expect(popover).toContainText("$4.92b");
  await expect(popover).toContainText("$3.86b");
  await expect(popover).toContainText("Your 14 day maker volume share is 7.63%");
  await unroutePortfolioUserFees(page);
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

test("portfolio funding openers launch the funding modal on real click @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio");

  for (const [dataRole, title] of [
    ["portfolio-action-deposit", "Deposit"],
    ["portfolio-action-perps-spot", "Perps <-> Spot"],
    ["portfolio-action-withdraw", "Withdraw"]
  ]) {
    const openButton = page.locator(`[data-role='${dataRole}']`);

    await expect(openButton).toBeVisible();
    await openButton.click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
    await expectOracle(page, "funding-modal", { open: true, title });
    if (dataRole === "portfolio-action-deposit" || dataRole === "portfolio-action-withdraw") {
      await expectFundingPopoverAnchoredLeftOfTrigger(page, openButton);
    }

    await page.locator("[data-role='funding-modal-close']").click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
    await expectOracle(page, "funding-modal", { open: false });
  }

  await selectAccountTab(page, "deposits-withdrawals");

  for (const [dataRole, title] of [
    ["portfolio-funding-action-deposit", "Deposit"],
    ["portfolio-funding-action-transfer", "Perps <-> Spot"],
    ["portfolio-funding-action-withdraw", "Withdraw"]
  ]) {
    const openButton = page.locator(`[data-role='${dataRole}']`);

    await expect(openButton).toBeVisible();
    await openButton.click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
    await expectOracle(page, "funding-modal", { open: true, title });

    await page.locator("[data-role='funding-modal-close']").click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
    await expectOracle(page, "funding-modal", { open: false });
  }
});

test("portfolio fee schedule opens, switches market type, and restores focus @regression", async ({ page }) => {
  await page.setViewportSize({ width: 552, height: 690 });
  await visitRoute(page, "/portfolio");

  const trigger = page.locator("[data-role='portfolio-fee-schedule-trigger']");
  const dialog = page.locator("[data-role='portfolio-fee-schedule-dialog']");
  const referralTrigger = page.locator("[data-role='portfolio-fee-schedule-referral-trigger']");
  const referralDiscountOption = page.locator("[data-role='portfolio-fee-schedule-referral-option-referral-4']");
  const stakingTrigger = page.locator("[data-role='portfolio-fee-schedule-staking-trigger']");
  const stakingDiamondOption = page.locator("[data-role='portfolio-fee-schedule-staking-option-diamond']");
  const makerRebateTrigger = page.locator("[data-role='portfolio-fee-schedule-maker-rebate-trigger']");
  const makerRebateTierTwoOption = page.locator("[data-role='portfolio-fee-schedule-maker-rebate-option-tier-2']");
  const marketTrigger = page.locator("[data-role='portfolio-fee-schedule-market-trigger']");
  const marketOptionRoles = [
    "portfolio-fee-schedule-market-option-spot",
    "portfolio-fee-schedule-market-option-spot-aligned-quote",
    "portfolio-fee-schedule-market-option-spot-stable-pair",
    "portfolio-fee-schedule-market-option-spot-aligned-stable-pair",
    "portfolio-fee-schedule-market-option-perps",
    "portfolio-fee-schedule-market-option-hip3-perps",
    "portfolio-fee-schedule-market-option-hip3-perps-growth-mode",
    "portfolio-fee-schedule-market-option-hip3-perps-aligned-quote",
    "portfolio-fee-schedule-market-option-hip3-perps-growth-mode-aligned-quote"
  ];
  const corePerpsOption = page.locator("[data-role='portfolio-fee-schedule-market-option-perps']");
  const hip3PerpsOption = page.locator("[data-role='portfolio-fee-schedule-market-option-hip3-perps']");
  const hip3GrowthOption = page.locator(
    "[data-role='portfolio-fee-schedule-market-option-hip3-perps-growth-mode']"
  );
  const stableAlignedOption = page.locator(
    "[data-role='portfolio-fee-schedule-market-option-spot-aligned-stable-pair']"
  );
  const tierZero = page.locator("[data-role='portfolio-fee-schedule-tier-0']");
  const closeButton = page.locator("[data-role='portfolio-fee-schedule-close']");

  await expect(trigger).toBeVisible();
  await expect(trigger).toHaveAttribute("aria-expanded", "false");

  await trigger.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(dialog).toBeVisible();
  await expect(trigger).toHaveAttribute("aria-expanded", "true");
  await expect.poll(async () =>
    page.evaluate(() => {
      const dialogNode = document.querySelector("[data-role='portfolio-fee-schedule-dialog']");
      const overlayNode = document.querySelector("[data-role='portfolio-fee-schedule-overlay']");
      const backdropNode = document.querySelector("[data-role='portfolio-fee-schedule-backdrop']");
      const triggerNode = document.querySelector("[data-role='portfolio-fee-schedule-trigger']");
      if (!dialogNode || !overlayNode || !backdropNode || !triggerNode) {
        return {
          fitsVertically: false,
          anchoredNearTrigger: false,
          transparentBackdrop: false,
          noVerticalScroll: false,
          noPageHorizontalOverflow: false,
          usesReferenceYellow: true
        };
      }

      const rect = dialogNode.getBoundingClientRect();
      const triggerRect = triggerNode.getBoundingClientRect();
      const backdropStyle = window.getComputedStyle(backdropNode);
      const dialogStyle = window.getComputedStyle(dialogNode);
      const overlayStyle = window.getComputedStyle(overlayNode);
      const classes = Array.from(overlayNode.querySelectorAll("*"))
        .map((node) => node.getAttribute("class") || "")
        .join(" ");
      const horizontallyNearTrigger =
        (rect.left <= triggerRect.right && rect.right >= triggerRect.left) ||
        Math.abs(rect.left - triggerRect.right) <= 16 ||
        Math.abs(rect.right - triggerRect.left) <= 16;
      const verticallyNearTrigger =
        rect.top <= triggerRect.bottom + 24 && rect.bottom >= triggerRect.top - 24;
      const opaqueSurface = (backgroundColor) => {
        const rgbaMatch = backgroundColor.match(/^rgba\([^,]+,[^,]+,[^,]+,\s*([0-9.]+)\)$/);
        return backgroundColor.startsWith("rgb(") || (rgbaMatch && Number(rgbaMatch[1]) >= 0.99);
      };
      const topElementBelongsToFeeSchedule = (x, y) => {
        const topNode = document.elementFromPoint(x, y);
        return Boolean(
          topNode?.closest(
            "[data-role='portfolio-fee-schedule-dialog'], [data-role='portfolio-fee-schedule-backdrop']"
          )
        );
      };
      const dialogHitTestPoints = [
        [rect.left + rect.width / 2, rect.top + rect.height / 2],
        [rect.left + 24, rect.top + Math.min(210, rect.height - 24)],
        [rect.right - 24, rect.top + Math.min(210, rect.height - 24)]
      ];
      const triggerTopNode = document.elementFromPoint(
        triggerRect.left + triggerRect.width / 2,
        triggerRect.top + triggerRect.height / 2
      );

      return {
        fitsVertically: rect.top >= 0 && rect.bottom <= window.innerHeight,
        anchoredNearTrigger: horizontallyNearTrigger && verticallyNearTrigger,
        opaquePopoverSurface: opaqueSurface(dialogStyle.backgroundColor),
        feeScheduleOwnsDialogHitArea: dialogHitTestPoints.every(([x, y]) =>
          topElementBelongsToFeeSchedule(x, y)
        ),
        triggerBlockedByOverlay: Boolean(
          triggerTopNode?.closest(
            "[data-role='portfolio-fee-schedule-dialog'], [data-role='portfolio-fee-schedule-backdrop']"
          )
        ),
        overlayInterceptsPointerEvents: overlayStyle.pointerEvents === "auto",
        overlayAboveAccountLayers: Number.parseInt(overlayStyle.zIndex, 10) >= 650,
        dialogAboveAccountLayers: Number.parseInt(dialogStyle.zIndex, 10) >= 651,
        noTranslucentInternalSurfaces:
          !classes.includes("bg-base-100/") &&
          !classes.includes("bg-base-200/") &&
          !classes.includes("bg-base-300/"),
        transparentBackdrop:
          backdropStyle.backgroundColor === "rgba(0, 0, 0, 0)" ||
          backdropStyle.backgroundColor === "transparent",
        noVerticalScroll: dialogNode.scrollHeight <= dialogNode.clientHeight + 1,
        noPageHorizontalOverflow: document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1,
        usesReferenceYellow: classes.includes("#f4c430") || classes.includes("#ffe08a") || classes.includes("text-yellow")
      };
    })
  ).toMatchObject({
    fitsVertically: true,
    anchoredNearTrigger: true,
    opaquePopoverSurface: true,
    feeScheduleOwnsDialogHitArea: true,
    triggerBlockedByOverlay: true,
    overlayInterceptsPointerEvents: true,
    overlayAboveAccountLayers: true,
    dialogAboveAccountLayers: true,
    noTranslucentInternalSurfaces: true,
    transparentBackdrop: true,
    noVerticalScroll: true,
    noPageHorizontalOverflow: true,
    usesReferenceYellow: false
  });
  await expect(tierZero).toContainText("0.045%");
  await expect(tierZero).toContainText("0.015%");

  await marketTrigger.click();
  for (const role of marketOptionRoles) {
    await expect(page.locator(`[data-role='${role}']`)).toBeVisible();
  }
  await expect(marketTrigger).toContainText("Core Perps");
  await expect(hip3PerpsOption).not.toHaveAttribute("aria-disabled", "true");
  await hip3PerpsOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(marketTrigger).toContainText("HIP-3 Perps");
  await expect(tierZero).toContainText("0.090%");
  await expect(tierZero).toContainText("0.030%");
  await marketTrigger.click();
  await expect(corePerpsOption).toBeVisible();
  await corePerpsOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(marketTrigger).toContainText("Core Perps");
  await expect(tierZero).toContainText("0.045%");
  await expect(tierZero).toContainText("0.015%");

  await referralTrigger.click();
  await expect(referralDiscountOption).toBeVisible();
  await referralDiscountOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(referralTrigger).toContainText("4%");
  await expect(tierZero).toContainText("0.0432%");
  await expect(tierZero).toContainText("0.0144%");

  await stakingTrigger.click();
  await expect(stakingDiamondOption).toBeVisible();
  const stakingDiamondBeforeHover = await stakingDiamondOption.evaluate((node) => {
    const rect = node.getBoundingClientRect();
    return {
      backgroundColor: window.getComputedStyle(node).backgroundColor,
      compactRow: rect.height <= 30
    };
  });
  expect(stakingDiamondBeforeHover.compactRow).toBe(true);
  await stakingDiamondOption.hover();
  await expect.poll(async () =>
    stakingDiamondOption.evaluate((node) => {
      const rect = node.getBoundingClientRect();
      return {
        backgroundColor: window.getComputedStyle(node).backgroundColor,
        compactRow: rect.height <= 30
      };
    })
  ).toMatchObject({
    compactRow: true
  });
  const stakingDiamondAfterHoverBackground = await stakingDiamondOption.evaluate((node) =>
    window.getComputedStyle(node).backgroundColor
  );
  expect(stakingDiamondAfterHoverBackground).not.toBe(stakingDiamondBeforeHover.backgroundColor);
  await stakingDiamondOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(stakingTrigger).toContainText("Diamond");
  await expect(tierZero).toContainText("0.0259%");
  await expect(tierZero).toContainText("0.0086%");

  await makerRebateTrigger.click();
  await expect(makerRebateTierTwoOption).toBeVisible();
  await makerRebateTierTwoOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(makerRebateTrigger).toContainText("Tier 2");
  await expect(tierZero).toContainText("0.0066%");

  await marketTrigger.click();
  await expect(stableAlignedOption).toBeVisible();
  await stableAlignedOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(marketTrigger).toContainText("Spot + Aligned Quote + Stable Pair");
  await expect(tierZero).toContainText("0.0065%");
  await expect(tierZero).toContainText("0.0026%");

  await closeButton.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(dialog).toHaveCount(0);
  await expect(trigger).toHaveAttribute("aria-expanded", "false");
  await expect(trigger).toBeFocused();

  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const activeMarket = c.PersistentArrayMap.fromArray(
      [
        keyword("coin"), "testdex:WTIOIL",
        keyword("key"), "perp:testdex:WTIOIL",
        keyword("base"), "WTIOIL",
        keyword("quote"), "USDC",
        keyword("symbol"), "WTIOIL-USDC",
        keyword("market-type"), keyword("perp"),
        keyword("dex"), "testdex",
        keyword("hip3?"), true,
        keyword("growth-mode?"), true
      ],
      true
    );
    const feeConfig = c.PersistentArrayMap.fromArray(
      [
        "testdex",
        c.PersistentArrayMap.fromArray([keyword("deployer-fee-scale"), 0.5], true)
      ],
      true
    );
    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, kwPath("active-asset"), "testdex:WTIOIL");
    nextState = c.assoc_in(nextState, kwPath("active-market"), activeMarket);
    nextState = c.assoc_in(nextState, kwPath("perp-dex-fee-config-by-name"), feeConfig);
    c.reset_BANG_(store, nextState);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await trigger.click();
  await expect(dialog).toBeVisible();
  await marketTrigger.click();
  await expect(hip3GrowthOption).toBeVisible();
  await expect(hip3GrowthOption).not.toHaveAttribute("aria-disabled", "true");
  await expect(hip3GrowthOption).toContainText("Active market: WTIOIL");
  await hip3GrowthOption.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expect(marketTrigger).toContainText("HIP-3 Perps + Growth mode");
  await expect(tierZero).toContainText("0.0068%");
  await expect(tierZero).toContainText("0.0023%");
  await page.keyboard.press("Escape");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
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
  await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

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
    const nextState = c.assoc_in(
      c.deref(globalThis.hyperopen.system.store),
      c.PersistentVector.fromArray([kw("webdata2")], true),
      c.js__GT_clj(payload, opts)
    );
    c.reset_BANG_(globalThis.hyperopen.system.store, nextState);
  });
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await selectAccountTab(page, "positions");
  const coinButton = page
    .locator("[data-role='portfolio-account-table'] [data-role='positions-coin-select']")
    .filter({ hasText: "HYPE" })
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

test("expanded trade blotter full history opens spectated portfolio order history @regression", async ({ page }) => {
  await visitRoute(page, "/trade");
  await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await seedExpandedTradeBlotterToast(page);

  const blotter = page.locator("[data-role='BlotterCard']");
  const historyLink = page.locator("[data-role='trade-toast-view-full-history']");

  await expect(blotter).toContainText("Grouped fills · avg 0.3 fills/sec");
  await expect(blotter).not.toContainText("TWAP · avg 1.2 fills/sec");
  await expect(historyLink).toBeVisible();
  await expect(historyLink).toHaveAttribute(
    "href",
    `/portfolio?spectate=${SPECTATE_ADDRESS}&tab=order-history`
  );

  await historyLink.click();
  await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });

  await expect.poll(() => new URL(page.url()).pathname).toBe("/portfolio");
  await expect.poll(() => new URL(page.url()).searchParams.get("spectate")).toBe(SPECTATE_ADDRESS);
  await expect.poll(() => new URL(page.url()).searchParams.get("tab")).toBe("order-history");
  await expect(page.locator("[data-role='spectate-mode-active-banner']")).toBeVisible();
  await expect(page.locator("[data-role='account-info-tab-order-history']"))
    .toHaveAttribute("aria-pressed", "true");
});
