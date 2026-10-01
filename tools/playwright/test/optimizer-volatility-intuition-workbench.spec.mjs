// Volatility intuition + one-year modeled leverage impact (designer spec
// 2026-07-12, trimmed per the direct user request; leverage impact promoted
// to a center-column panel with the mockup's ending-wealth distribution on
// 2026-07-12 follow-up). The workbench scenes render the REAL rail card and
// panel over explicit result fixtures, so this covers the browser-only
// behavior deterministically without solving an optimization: the
// 365-calendar-day sqrt-time scaling of the displayed values, the DOM-radio
// Target/Current toggle, severity + (-100%)-boundary messaging with the
// monthly value uncapped, the leverage panel's gross/volatility gates, its
// modeled dollar rows with the vs-current shortfall headline, the lognormal
// ending-wealth distribution markers, and the multiples fallback when
// account equity is unknown.
import { expect, test } from "../support/guarded_test.mjs";

const SCENE_BASE =
  "/ui-workbench.html?id=hyperopen.workbench.scenes.optimize.volatility-intuition-scenes";

function role(name) {
  return `[data-role='${name}']`;
}

async function openScene(page, scene) {
  await page.goto(`${SCENE_BASE}/${scene}`);
  const frame = page.frameLocator("iframe").first();
  await expect(
    frame.locator(role("portfolio-optimizer-volatility-intuition"))
  ).toBeVisible({ timeout: 30_000 });
  return frame;
}

test.describe("volatility intuition (workbench scenes)", () => {
  test("an extreme levered book shows uncapped 365-day horizon scales with the warning stack", async ({
    page
  }) => {
    const frame = await openScene(page, "extreme-levered-book");
    const targetPanel = frame.locator(
      role("portfolio-optimizer-volatility-intuition-panel-target")
    );

    // 411.82% annualized on the optimizer's 365-calendar-day basis.
    await expect(
      targetPanel.locator(
        role("portfolio-optimizer-volatility-intuition-target-annualized")
      )
    ).toHaveText("411.8%");
    await expect(
      targetPanel.locator(
        role("portfolio-optimizer-volatility-intuition-target-daily")
      )
    ).toContainText("±21.56%");
    await expect(
      targetPanel.locator(
        role("portfolio-optimizer-volatility-intuition-target-weekly")
      )
    ).toContainText("±57.03%");
    // Above 100% the monthly value renders uncapped...
    await expect(
      targetPanel.locator(
        role("portfolio-optimizer-volatility-intuition-target-monthly")
      )
    ).toContainText("±118.1%");
    // ...with the extreme callout and the boundary explanation.
    await expect(
      targetPanel.locator(
        role("portfolio-optimizer-volatility-intuition-target-severity")
      )
    ).toContainText("Extreme volatility");
    await expect(
      targetPanel.locator(
        role("portfolio-optimizer-volatility-intuition-target-boundary")
      )
    ).toContainText("dispersion measure");
    // The scaling convention and the 1σ meaning are always-visible copy.
    await expect(
      frame.locator(role("portfolio-optimizer-volatility-intuition-basis"))
    ).toContainText("365 calendar days");
    await expect(
      frame.locator(role("portfolio-optimizer-volatility-intuition"))
    ).toContainText("not a forecast or maximum loss");
  });

  test("the Target/Current toggle is pure DOM state", async ({ page }) => {
    const frame = await openScene(page, "extreme-levered-book");
    const targetPanel = frame.locator(
      role("portfolio-optimizer-volatility-intuition-panel-target")
    );
    const currentPanel = frame.locator(
      role("portfolio-optimizer-volatility-intuition-panel-current")
    );
    const dailyTrack = targetPanel
      .locator(role("portfolio-optimizer-volatility-intuition-target-daily"))
      .locator(".optimizer-vol-intuition-track");
    const dailyFill = dailyTrack.locator(".optimizer-vol-intuition-fill");
    const targetTab = frame.locator(
      role("portfolio-optimizer-volatility-intuition-tab-target")
    );

    // Target is the recommendation default (no radio checked yet).
    await expect(targetPanel).toBeVisible();
    await expect(currentPanel).toBeHidden();
    // Reference-worktree visual contract: the magnitude track is visible and
    // the active tab carries the amber underline rather than browser defaults.
    await expect(dailyTrack).toHaveCSS("height", "6px");
    await expect(dailyFill).toHaveCSS("height", "6px");
    await expect(dailyFill).not.toHaveCSS("width", "0px");
    await expect(targetTab).toHaveCSS(
      "box-shadow",
      /rgb\(212, 181, 88\) 0px -2px 0px/
    );

    await frame
      .locator(role("portfolio-optimizer-volatility-intuition-tab-current"))
      .click();
    await expect(currentPanel).toBeVisible();
    await expect(targetPanel).toBeHidden();
    await expect(
      currentPanel.locator(
        role("portfolio-optimizer-volatility-intuition-current-annualized")
      )
    ).toHaveText("313.9%");
    await expect(
      currentPanel.locator(
        role("portfolio-optimizer-volatility-intuition-current-daily")
      )
    ).toContainText("±16.43%");

    await frame
      .locator(role("portfolio-optimizer-volatility-intuition-tab-target"))
      .click();
    await expect(targetPanel).toBeVisible();
    await expect(currentPanel).toBeHidden();
  });

  test("the leverage-impact panel models dollar outcomes honestly for the levered book", async ({
    page
  }) => {
    const frame = await openScene(page, "extreme-levered-book");
    const panel = frame.locator(role("portfolio-optimizer-leverage-impact"));

    await expect(panel).toBeVisible();
    await expect(panel).toContainText("One-year modeled leverage impact");
    await expect(panel).toContainText("Modeled");
    await expect(panel).toContainText(
      "Modeled dollar outcomes on account equity $100,000"
    );
    // Median ending wealth: the volatility drag makes the target median
    // collapse even though its arithmetic mean is +1866%.
    await expect(
      panel.locator(role("portfolio-optimizer-leverage-impact-median-current"))
    ).toContainText("$18,356");
    await expect(
      panel.locator(role("portfolio-optimizer-leverage-impact-median-target"))
    ).toContainText("$408");
    const shortfall = panel.locator(
      role("portfolio-optimizer-leverage-impact-median-shortfall")
    );
    await expect(shortfall).toContainText("Median wealth shortfall vs current");
    await expect(shortfall).toContainText("−$17,948");
    // Tiles: mean is pulled up by rare extreme paths; both loss odds render.
    await expect(
      panel.locator(role("portfolio-optimizer-leverage-impact-mean"))
    ).toContainText("$1,966,060");
    await expect(
      panel.locator(role("portfolio-optimizer-leverage-impact-terminal"))
    ).toContainText("87.8%");
    // Never a liquidation probability — the drawdown odds are a floor.
    const touch = panel.locator(
      role("portfolio-optimizer-leverage-impact-touch")
    );
    await expect(touch).toContainText("98.2%");
    await expect(touch).toContainText("floor on ruin risk");
    await expect(panel).toContainText("Modeled, not a guarantee");
  });

  test("the ending-wealth distribution draws the lognormal with 5th/median/mean markers", async ({
    page
  }) => {
    const frame = await openScene(page, "extreme-levered-book");
    const dist = frame.locator(
      role("portfolio-optimizer-leverage-impact-distribution")
    );

    await expect(dist).toBeVisible();
    await expect(dist).toContainText("Target ending wealth distribution");
    await expect(
      dist.locator(role("portfolio-optimizer-leverage-impact-dist-curve"))
    ).toBeVisible();
    // Compact marker labels: near-total median loss, mean in the millions.
    await expect(
      dist.locator(role("portfolio-optimizer-leverage-impact-dist-p5"))
    ).toContainText("$0");
    await expect(
      dist.locator(role("portfolio-optimizer-leverage-impact-dist-median"))
    ).toContainText("$408");
    await expect(
      dist.locator(role("portfolio-optimizer-leverage-impact-dist-mean"))
    ).toContainText("$2M");
    await expect(dist).toContainText("Lower");
    await expect(dist).toContainText("Higher");
    // The mean marker sits right of the median marker by the σ²/2 drag.
    const medianDot = dist
      .locator(`${role("portfolio-optimizer-leverage-impact-dist-median")} circle`)
      .first();
    const meanDot = dist
      .locator(`${role("portfolio-optimizer-leverage-impact-dist-mean")} circle`)
      .first();
    const medianX = Number(await medianDot.getAttribute("cx"));
    const meanX = Number(await meanDot.getAttribute("cx"));
    expect(meanX).toBeGreaterThan(medianX);
  });

  test("every field carries a hover/keyboard info-tip with copy honest to the model", async ({
    page
  }) => {
    const frame = await openScene(page, "extreme-levered-book");
    const panel = frame.locator(role("portfolio-optimizer-leverage-impact"));

    // Each tip is wired to a focusable trigger and reveals on hover.
    const medianTip = panel.locator(
      role("portfolio-optimizer-leverage-impact-median-tip")
    );
    const medianTrigger = panel.locator(
      role("portfolio-optimizer-leverage-impact-median-tip-trigger")
    );
    // Hidden until interaction (opacity 0), copy present in the DOM.
    expect(await medianTip.evaluate((el) => getComputedStyle(el).opacity)).toBe(
      "0"
    );
    await expect(medianTip).toContainText("not the average");
    await expect(medianTip).toContainText("Both rows start from $100,000");
    // Hover the trigger → the card fades in (group-hover).
    await medianTrigger.hover();
    await expect
      .poll(() => medianTip.evaluate((el) => getComputedStyle(el).opacity))
      .toBe("1");
    // Keyboard focus reveals it too (group-focus-within) — accessibility.
    await medianTrigger.focus();
    await expect
      .poll(() => medianTip.evaluate((el) => getComputedStyle(el).opacity))
      .toBe("1");

    // The two loss-odds tips draw the end-of-year vs path-dependent line the
    // guide insists on, and the touch tip stays honest about liquidation.
    await expect(
      panel.locator(role("portfolio-optimizer-leverage-impact-terminal-tip"))
    ).toContainText("finishes the year at or below half");
    const touchTip = panel.locator(
      role("portfolio-optimizer-leverage-impact-touch-tip")
    );
    await expect(touchTip).toContainText("at any point in the year");
    await expect(touchTip).toContainText("floor on ruin risk");
    await expect(touchTip).toContainText("not a liquidation probability");

    // The panel-level tip is explicit that this is NOT a simulation and that
    // funding/execution/liquidation are out of the model.
    const titleTip = panel.locator(
      role("portfolio-optimizer-leverage-impact-title-tip")
    );
    await expect(titleTip).toContainText("not a simulation");
    await expect(titleTip).toContainText("are not modeled");

    // The distribution tip discloses the nonlinear axis.
    await expect(
      panel.locator(role("portfolio-optimizer-leverage-impact-distribution-tip"))
    ).toContainText("log-scaled");
  });

  test("a moderate book stays quiet: designer's 40% vector, no warnings, no leverage panel", async ({
    page
  }) => {
    const frame = await openScene(page, "moderate-book");
    const targetPanel = frame.locator(
      role("portfolio-optimizer-volatility-intuition-panel-target")
    );

    // 40% annualized / 365 days: the spec's worked example.
    await expect(targetPanel).toContainText("±2.09%");
    await expect(targetPanel).toContainText("±5.54%");
    await expect(targetPanel).toContainText("±11.47%");
    await expect(
      frame.locator(
        role("portfolio-optimizer-volatility-intuition-target-severity")
      )
    ).toHaveCount(0);
    await expect(
      frame.locator(
        role("portfolio-optimizer-volatility-intuition-target-boundary")
      )
    ).toHaveCount(0);
    await expect(
      frame.locator(role("portfolio-optimizer-leverage-impact"))
    ).toHaveCount(0);
  });

  test("without a current book or account equity the panel degrades honestly", async ({
    page
  }) => {
    const frame = await openScene(page, "very-high-vol-no-current");

    // No current volatility → no toggle, no current panel.
    await expect(
      frame.locator(role("portfolio-optimizer-volatility-intuition-tabs"))
    ).toHaveCount(0);
    await expect(
      frame.locator(
        role("portfolio-optimizer-volatility-intuition-panel-current")
      )
    ).toHaveCount(0);
    // σ ≥ 100% surfaces the panel even at 1.2x gross, speaking in multiples
    // of starting equity because no capital is known — the distribution
    // markers included.
    const panel = frame.locator(role("portfolio-optimizer-leverage-impact"));
    await expect(panel).toBeVisible();
    await expect(panel).toContainText("multiples of starting equity");
    await expect(
      panel.locator(role("portfolio-optimizer-leverage-impact-median-target"))
    ).toContainText("0.58x start");
    await expect(
      panel.locator(role("portfolio-optimizer-leverage-impact-median-shortfall"))
    ).toHaveCount(0);
    await expect(
      panel.locator(role("portfolio-optimizer-leverage-impact-dist-median"))
    ).toContainText("0.58x");
  });
});
