// Equal Risk correlation + per-asset breakdown views (designer specs
// 2026-07-11). The workbench scenes render the REAL risk card + Allocation
// table with a scene-local dispatcher, so this covers the browser-only
// behavior deterministically without solving an optimization in-app:
// DOM-state tab switching, the POSITION P&L / UNDERLYING RETURNS matrix
// toggle (sign flips included), the BREAKDOWN tab's per-asset default with
// its Selected asset / All assets sub-toggle, selection flowing between
// allocation-row clicks and the Change-asset select, the full-width
// correlation heatmap, the 12-asset heatmap cap, and degenerate-variance
// em-dashes.
import { expect, test } from "../support/guarded_test.mjs";

const SCENE_BASE =
  "/ui-workbench.html?id=hyperopen.workbench.scenes.optimize.equal-risk-correlation-scenes";

function role(name) {
  return `[data-role='${name}']`;
}

async function openScene(page, scene) {
  await page.goto(`${SCENE_BASE}/${scene}`);
  const frame = page.frameLocator("iframe").first();
  await expect(
    frame.locator(role("portfolio-optimizer-risk-contributions"))
  ).toBeVisible({ timeout: 30_000 });
  return frame;
}

function corrCell(grid, rowId, colId) {
  return grid.locator(
    `${role("portfolio-optimizer-risk-corr-cell")}[data-row='${rowId}'][data-col='${colId}']`
  );
}

test.describe("equal risk correlation view (workbench scenes)", () => {
  test("risk balance, diversification benchmarks, and additive attribution answer separate questions", async ({
    page
  }) => {
    const frame = await openScene(page, "correlation-designer-parity");

    const balanceTab = frame.locator(
      role("portfolio-optimizer-risk-view-tab-contribution")
    );
    await expect(balanceTab).toContainText("Risk Balance");
    await expect(
      frame.locator(role("portfolio-optimizer-risk-contribution-chart"))
    ).toBeVisible();

    const diversificationTab = frame.locator(
      role("portfolio-optimizer-risk-view-tab-breakdown")
    );
    await expect(diversificationTab).toContainText("Diversification");
    await diversificationTab.click();

    const comparison = frame.locator(
      role("portfolio-optimizer-risk-diversification-comparison")
    );
    const matrix = comparison.locator(
      role("portfolio-optimizer-risk-diversification-matrix")
    );
    await expect(matrix).toBeVisible();
    await expect(
      comparison.locator(role("portfolio-optimizer-risk-diversification-current"))
    ).toHaveCount(0);
    await expect(
      comparison.locator(role("portfolio-optimizer-risk-diversification-target"))
    ).toHaveCount(0);

    const modeledRow = matrix.locator(
      `${role("portfolio-optimizer-risk-diversification-benchmark-row")}[data-benchmark='modeled']`
    );
    const currentModeled = modeledRow.locator(
      role("portfolio-optimizer-risk-diversification-current-marker")
    );
    const recommendedModeled = modeledRow.locator(
      role("portfolio-optimizer-risk-diversification-recommended-marker")
    );
    await expect(currentModeled).toHaveCount(1);
    await expect(recommendedModeled).toHaveCount(1);
    const currentPosition = Number(await currentModeled.getAttribute("data-position"));
    const targetPosition = Number(await recommendedModeled.getAttribute("data-position"));
    expect(Number.isFinite(currentPosition)).toBe(true);
    expect(Number.isFinite(targetPosition)).toBe(true);
    expect(targetPosition).toBeLessThan(currentPosition);
    const decision = comparison.locator(
      role("portfolio-optimizer-risk-diversification-decision-summary")
    );
    await expect(decision).toContainText(/Modeled volatility falls/i);
    await expect(decision).toContainText(/all-move-together stress rises/i);

    const selected = frame.locator(
      role("portfolio-optimizer-risk-selected-breakdown")
    );
    await expect(selected).toContainText("offsets");
    await expect(selected).toContainText("final-weight attribution");
    await expect(selected).toContainText("not removal impact");

    await frame
      .locator(role("portfolio-optimizer-risk-breakdown-view-all"))
      .click();
    const firstRow = frame
      .locator(role("portfolio-optimizer-risk-breakdown-row"))
      .first();
    const ownEnd = Number(await firstRow.getAttribute("data-own-end"));
    const crossStart = Number(await firstRow.getAttribute("data-cross-start"));
    const crossEnd = Number(await firstRow.getAttribute("data-cross-end"));
    const netEnd = Number(await firstRow.getAttribute("data-net-end"));
    expect(ownEnd).toBe(crossStart);
    expect(crossEnd).toBe(netEnd);

    await frame
      .locator(role("portfolio-optimizer-risk-breakdown-view-asset"))
      .click();
    await frame
      .locator(role("portfolio-optimizer-risk-asset-select"))
      .selectOption("perp:ETH");
    await expect(
      frame.locator(role("portfolio-optimizer-target-exposure-asset-ETH"))
    ).toHaveAttribute("data-selected", "true");
  });

  test("diversification help reveals exact overview and row copy on keyboard focus", async ({
    page
  }) => {
    const frame = await openScene(page, "correlation-designer-parity");
    await frame
      .locator(role("portfolio-optimizer-risk-view-tab-breakdown"))
      .click();

    const comparison = frame.locator(
      role("portfolio-optimizer-risk-diversification-comparison")
    );
    const triggers = comparison.locator(
      role("portfolio-optimizer-risk-diversification-help-trigger")
    );
    await expect(triggers).toHaveCount(6);

    const cases = [
      {
        trigger: triggers.first(),
        copy: "Current and Recommended share one annualized-volatility scale; Change is Recommended minus Current in percentage points."
      },
      {
        trigger: comparison
          .locator(
            `${role("portfolio-optimizer-risk-diversification-outcome-row")}[data-outcome='correlation-effect']`
          )
          .locator(role("portfolio-optimizer-risk-diversification-help-trigger")),
        copy: "modeled volatility minus zero-correlation volatility; negative offsets risk and positive amplifies it."
      }
    ];

    for (const { trigger, copy } of cases) {
      const tooltipId = await trigger.getAttribute("aria-describedby");
      expect(tooltipId).toBeTruthy();
      const tooltip = frame.locator(`[id='${tooltipId}']`);
      await expect(tooltip).toHaveAttribute("role", "tooltip");
      await expect(tooltip).toHaveText(copy);
      await expect
        .poll(() => tooltip.evaluate((element) => getComputedStyle(element).opacity))
        .toBe("0");
      await trigger.focus();
      await expect
        .poll(() => tooltip.evaluate((element) => getComputedStyle(element).opacity))
        .toBe("1");
      await trigger.hover();
      await trigger.press("Escape");
      await expect(trigger).toBeFocused();
      expect(await trigger.evaluate((element) => element.matches(":hover"))).toBe(
        true
      );
      await expect
        .poll(() => tooltip.evaluate((element) => getComputedStyle(element).opacity))
        .toBe("0");
      await comparison
        .locator(role("portfolio-optimizer-risk-diversification-decision-summary"))
        .hover();
      await expect(trigger).toBeFocused();
      await expect
        .poll(() => tooltip.evaluate((element) => getComputedStyle(element).opacity))
        .toBe("0");
      await frame
        .locator(role("portfolio-optimizer-risk-view-tab-breakdown"))
        .focus();
      await expect(trigger).not.toBeFocused();
      await trigger.focus();
      await expect
        .poll(() => tooltip.evaluate((element) => getComputedStyle(element).opacity))
        .toBe("1");
    }
  });

  test("the parity scene and risk card stay within every governed viewport", async ({
    page
  }) => {
    for (const width of [375, 768, 1280, 1440]) {
      await page.setViewportSize({ width, height: 1000 });
      const frame = await openScene(page, "correlation-designer-parity");
      const card = frame.locator(
        role("portfolio-optimizer-risk-contributions")
      );
      await frame
        .locator(role("portfolio-optimizer-risk-view-tab-breakdown"))
        .click();
      await expect(
        frame.locator(role("portfolio-optimizer-risk-diversification-matrix"))
      ).toBeVisible();
      const matrix = frame.locator(
        role("portfolio-optimizer-risk-diversification-matrix")
      );
      await expect(
        matrix.locator(role("portfolio-optimizer-risk-diversification-benchmark-row"))
      ).toHaveCount(3);
      for (const row of await matrix
        .locator(role("portfolio-optimizer-risk-diversification-benchmark-row"))
        .all()) {
        await expect(
          row.locator(role("portfolio-optimizer-risk-diversification-current-marker"))
        ).toHaveCount(1);
        await expect(
          row.locator(role("portfolio-optimizer-risk-diversification-recommended-marker"))
        ).toHaveCount(1);
      }
      const helpTriggers = frame.locator(
        role("portfolio-optimizer-risk-diversification-help-trigger")
      );
      await expect(helpTriggers).toHaveCount(6);
      for (let index = 0; index < 6; index += 1) {
        const trigger = helpTriggers.nth(index);
        const tooltipId = await trigger.getAttribute("aria-describedby");
        expect(tooltipId, `${width}px trigger ${index} description`).toBeTruthy();
        const tooltip = frame.locator(`[id='${tooltipId}']`);
        await trigger.focus();
        await expect
          .poll(() => tooltip.evaluate((element) => getComputedStyle(element).opacity))
          .toBe("1");
        const tooltipBounds = await tooltip.evaluate((element) => {
          const rect = element.getBoundingClientRect();
          return {
            left: rect.left,
            right: rect.right,
            top: rect.top + window.scrollY,
            bottom: rect.bottom + window.scrollY,
            documentWidth: document.documentElement.clientWidth,
            documentHeight: document.documentElement.scrollHeight
          };
        });
        expect(
          tooltipBounds.left,
          `${width}px tooltip ${index} left edge`
        ).toBeGreaterThanOrEqual(-1);
        expect(
          tooltipBounds.right,
          `${width}px tooltip ${index} right edge`
        ).toBeLessThanOrEqual(tooltipBounds.documentWidth + 1);
        expect(
          tooltipBounds.top,
          `${width}px tooltip ${index} top edge`
        ).toBeGreaterThanOrEqual(-1);
        expect(
          tooltipBounds.bottom,
          `${width}px tooltip ${index} bottom edge`
        ).toBeLessThanOrEqual(tooltipBounds.documentHeight + 1);
        await trigger.evaluate((element) => element.blur());
      }
      const bounds = await card.evaluate((element) => {
        const rect = element.getBoundingClientRect();
        return {
          clientWidth: document.documentElement.clientWidth,
          scrollWidth: Math.max(
            document.documentElement.scrollWidth,
            document.body?.scrollWidth ?? 0
          ),
          card: {
            left: rect.left,
            right: rect.right,
            width: rect.width
          }
        };
      });

      expect(bounds.scrollWidth, `${width}px scene overflow`).toBeLessThanOrEqual(
        bounds.clientWidth + 1
      );
      expect(bounds.card.width, `${width}px risk card width`).toBeGreaterThan(
        bounds.clientWidth * 0.5
      );
      expect(bounds.card.left, `${width}px risk card left edge`).toBeGreaterThanOrEqual(-1);
      expect(bounds.card.right, `${width}px risk card right edge`).toBeLessThanOrEqual(
        bounds.clientWidth + 1
      );
    }
  });

  test("tabs and the P&L/underlying toggle switch via DOM state, flipping long × short signs", async ({
    page
  }) => {
    const frame = await openScene(page, "correlation-designer-parity");
    const contribution = frame.locator(
      role("portfolio-optimizer-risk-contribution-chart")
    );
    const heatmap = frame.locator(
      role("portfolio-optimizer-risk-correlation-heatmap")
    );
    await expect(contribution).toBeVisible();
    await expect(heatmap).toBeHidden();

    await frame
      .locator(role("portfolio-optimizer-risk-view-tab-correlation"))
      .click();
    await expect(heatmap).toBeVisible();
    await expect(contribution).toBeHidden();

    const positionGrid = frame.locator(
      role("portfolio-optimizer-risk-corr-grid-position")
    );
    const underlyingGrid = frame.locator(
      role("portfolio-optimizer-risk-corr-grid-underlying")
    );
    await expect(positionGrid).toBeVisible();
    await expect(underlyingGrid).toBeHidden();
    // BTC long × MSTR short: the held-position P&L correlation flips the
    // underlying +0.28 negative.
    await expect(corrCell(positionGrid, "perp:BTC", "perp:MSTR")).toHaveText(
      "-0.28"
    );

    await frame
      .locator(role("portfolio-optimizer-risk-corr-mode-underlying"))
      .click();
    await expect(underlyingGrid).toBeVisible();
    await expect(positionGrid).toBeHidden();
    await expect(corrCell(underlyingGrid, "perp:BTC", "perp:MSTR")).toHaveText(
      "0.28"
    );

    await frame
      .locator(role("portfolio-optimizer-risk-corr-mode-position"))
      .click();
    await expect(positionGrid).toBeVisible();
    await expect(underlyingGrid).toBeHidden();
  });

  test("the BREAKDOWN tab defaults to the per-asset panel; row clicks and the Change-asset select share one selection", async ({
    page
  }) => {
    const frame = await openScene(page, "correlation-designer-parity");
    await frame
      .locator(role("portfolio-optimizer-risk-view-tab-breakdown"))
      .click();

    const assetPanel = frame.locator(
      role("portfolio-optimizer-risk-selected-breakdown")
    );
    const selected = frame.locator(
      role("portfolio-optimizer-risk-selected-asset")
    );
    const picker = frame.locator(
      role("portfolio-optimizer-risk-asset-select")
    );
    const mstrRow = frame.locator(
      role("portfolio-optimizer-target-exposure-asset-MSTR")
    );
    const btcRow = frame.locator(
      role("portfolio-optimizer-target-exposure-asset-BTC")
    );

    // The scene store preselects MSTR — the short whose decomposition the
    // designer's spec explains. The per-asset view is the default sub-view,
    // and the select must sync to the selection on first render.
    await expect(assetPanel).toBeVisible();
    await expect(selected).toHaveText("MSTR");
    await expect(picker).toHaveValue("perp:MSTR");
    await expect(mstrRow).toHaveAttribute("data-selected", "true");
    await expect(
      frame.locator(role("portfolio-optimizer-risk-selected-identity"))
    ).toContainText("Net risk contribution");
    await expect(
      frame.locator(role("portfolio-optimizer-risk-asset-tile-freedom"))
    ).toContainText("Limited · 2 binding caps");

    // Allocation-row click re-targets the panel AND the select's value.
    await btcRow.click();
    await expect(selected).toHaveText("BTC");
    await expect(btcRow).toHaveAttribute("data-selected", "true");
    await expect(mstrRow).not.toHaveAttribute("data-selected", "true");
    await expect(picker).toHaveValue("perp:BTC");

    // The Change-asset select drives the same app state back the other way.
    await picker.selectOption("perp:ETH");
    await expect(selected).toHaveText("ETH");
    await expect(
      frame.locator(role("portfolio-optimizer-target-exposure-asset-ETH"))
    ).toHaveAttribute("data-selected", "true");
    await expect(btcRow).not.toHaveAttribute("data-selected", "true");

    // The per-row P&L-correlation line rides every held row.
    await expect(
      frame.locator(
        role("portfolio-optimizer-target-exposure-pnl-corr-perp-MSTR")
      )
    ).toContainText("+0.");
  });

  test("the breakdown sub-toggle swaps per-asset and all-assets views without touching app state", async ({
    page
  }) => {
    const frame = await openScene(page, "correlation-designer-parity");
    await frame
      .locator(role("portfolio-optimizer-risk-view-tab-breakdown"))
      .click();

    const assetPanel = frame.locator(
      role("portfolio-optimizer-risk-selected-breakdown")
    );
    const allChart = frame.locator(
      role("portfolio-optimizer-risk-breakdown-chart")
    );
    await expect(assetPanel).toBeVisible();
    await expect(allChart).toBeHidden();

    await frame
      .locator(role("portfolio-optimizer-risk-breakdown-view-all"))
      .click();
    await expect(allChart).toBeVisible();
    await expect(assetPanel).toBeHidden();

    await frame
      .locator(role("portfolio-optimizer-risk-breakdown-view-asset"))
      .click();
    await expect(assetPanel).toBeVisible();
    await expect(allChart).toBeHidden();
  });

  test("the correlation tab is the heatmap alone at full card width", async ({
    page
  }) => {
    const frame = await openScene(page, "correlation-designer-parity");
    await frame
      .locator(role("portfolio-optimizer-risk-view-tab-correlation"))
      .click();

    const heatmap = frame.locator(
      role("portfolio-optimizer-risk-correlation-heatmap")
    );
    await expect(heatmap).toBeVisible();
    // The old in-tab breakdown block now lives on the BREAKDOWN tab, so it
    // must be hidden while the correlation tab is active.
    await expect(
      frame.locator(role("portfolio-optimizer-risk-selected-breakdown"))
    ).toBeHidden();

    const card = frame.locator(
      role("portfolio-optimizer-risk-contributions")
    );
    const cardBox = await card.boundingBox();
    const heatmapBox = await heatmap.boundingBox();
    expect(heatmapBox.width).toBeGreaterThan(cardBox.width * 0.9);
  });

  test("an exact fit flips to the rebalance story: impact strip, shift columns, imbalance KPIs", async ({
    page
  }) => {
    const frame = await openScene(page, "balance-exact-fit");

    // The strip leads with current → target facts.
    const strip = frame.locator(
      role("portfolio-optimizer-equal-risk-impact-strip")
    );
    await expect(strip).toBeVisible();
    await expect(strip).toContainText("What changes if you execute");
    await expect(
      strip.locator(role("portfolio-optimizer-equal-risk-impact-balance"))
    ).toContainText("→");
    // 2.31x gross clears the leverage gate, so the outcome chip renders and
    // carries the ruin-floor odds on its sub line.
    await expect(
      strip.locator(role("portfolio-optimizer-equal-risk-impact-outcome"))
    ).toContainText("Touch −50% odds");

    // Exact fit + current book → the chart tells the move, not the misfit:
    // Current/Shift columns, no constant Target column, no dead-zero KPIs.
    const chart = frame.locator(
      role("portfolio-optimizer-risk-contribution-chart")
    );
    await expect(chart).toContainText("Shift");
    await expect(
      frame.locator(role("portfolio-optimizer-risk-contribution-shift")).first()
    ).toBeVisible();
    await expect(
      frame.locator(role("portfolio-optimizer-risk-contribution-target"))
    ).toHaveCount(0);
    // The shared KPI strip renders once per tab panel — assert the balance
    // tab's copy.
    await expect(
      frame
        .locator(role("portfolio-optimizer-risk-contributions-imbalance"))
        .first()
    ).toContainText("→");
    await expect(
      frame
        .locator(role("portfolio-optimizer-risk-contributions-biggest-shift"))
        .first()
    ).toContainText("BTC");

    // The volatility chip is a plain label→radio deep-link into the
    // Diversification tab, where the verdict leads and the bridge renders.
    await frame
      .locator(role("portfolio-optimizer-equal-risk-impact-volatility"))
      .click();
    await expect(
      frame.locator(role("portfolio-optimizer-risk-diversification-comparison"))
    ).toBeVisible();
    await expect(
      frame.locator(role("portfolio-optimizer-risk-divbridge"))
    ).toBeVisible();
    await expect(
      frame.locator(
        role("portfolio-optimizer-risk-diversification-decision-summary")
      )
    ).toContainText(/Modeled volatility/);
  });

  test("a 28-asset book caps the heatmap at 24 and says how many it dropped", async ({
    page
  }) => {
    const frame = await openScene(page, "correlation-capped-twenty-eight-assets");
    await frame
      .locator(role("portfolio-optimizer-risk-view-tab-correlation"))
      .click();
    const positionGrid = frame.locator(
      role("portfolio-optimizer-risk-corr-grid-position")
    );
    await expect(positionGrid.locator("[data-diagonal='true']")).toHaveCount(24);
    await expect(
      frame.locator(role("portfolio-optimizer-risk-corr-overflow"))
    ).toContainText("+ 4 more held assets not shown");
  });

  test("a zero-variance asset renders em-dash correlations, never numbers", async ({
    page
  }) => {
    const frame = await openScene(page, "correlation-degenerate-column");
    await frame
      .locator(role("portfolio-optimizer-risk-view-tab-correlation"))
      .click();
    const positionGrid = frame.locator(
      role("portfolio-optimizer-risk-corr-grid-position")
    );
    await expect(
      positionGrid.locator("[data-sign='missing']").first()
    ).toHaveText("—");
    // The healthy pair keeps its number.
    await expect(corrCell(positionGrid, "perp:BTC", "perp:ETH")).toHaveText(
      "0.72"
    );
  });
});
