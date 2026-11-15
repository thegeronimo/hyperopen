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
    await expect(
      targetPanel.locator(
        role("portfolio-optimizer-volatility-intuition-target-monthly")
      )
    ).toContainText("±118.1%");
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

    await expect(targetPanel).toBeVisible();
    await expect(currentPanel).toBeHidden();
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
    await expect(
      panel.locator(role("portfolio-optimizer-leverage-impact-mean"))
    ).toContainText("$1,966,060");
    await expect(
      panel.locator(role("portfolio-optimizer-leverage-impact-terminal"))
    ).toContainText("87.8%");
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

    const medianTip = panel.locator(
      role("portfolio-optimizer-leverage-impact-median-tip")
    );
    const medianTrigger = panel.locator(
      role("portfolio-optimizer-leverage-impact-median-tip-trigger")
    );
    expect(await medianTip.evaluate((el) => getComputedStyle(el).opacity)).toBe(
      "0"
    );
    await expect(medianTip).toContainText("not the average");
    await expect(medianTip).toContainText("Both rows start from $100,000");
    await medianTrigger.hover();
    await expect
      .poll(() => medianTip.evaluate((el) => getComputedStyle(el).opacity))
      .toBe("1");
    await medianTrigger.focus();
    await expect
      .poll(() => medianTip.evaluate((el) => getComputedStyle(el).opacity))
      .toBe("1");

    await expect(
      panel.locator(role("portfolio-optimizer-leverage-impact-terminal-tip"))
    ).toContainText("finishes the year at or below half");
    const touchTip = panel.locator(
      role("portfolio-optimizer-leverage-impact-touch-tip")
    );
    await expect(touchTip).toContainText("at any point in the year");
    await expect(touchTip).toContainText("floor on ruin risk");
    await expect(touchTip).toContainText("not a liquidation probability");

    const titleTip = panel.locator(
      role("portfolio-optimizer-leverage-impact-title-tip")
    );
    await expect(titleTip).toContainText("not a simulation");
    await expect(titleTip).toContainText("are not modeled");

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

    await expect(
      frame.locator(role("portfolio-optimizer-volatility-intuition-tabs"))
    ).toHaveCount(0);
    await expect(
      frame.locator(
        role("portfolio-optimizer-volatility-intuition-panel-current")
      )
    ).toHaveCount(0);
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
