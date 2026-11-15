import { expect, test } from "../support/guarded_test.mjs";

const SCENE_BASE =
  "/ui-workbench.html?id=hyperopen.workbench.scenes.account.margin-recommendation-scenes";

function role(name) {
  return `[data-role='${name}']`;
}

async function openScene(page, scene) {
  await page.goto(`${SCENE_BASE}/${scene}`);
  const frame = page.frameLocator("iframe").first();
  await expect(frame.locator(role("margin-rec-panel"))).toBeVisible({
    timeout: 30_000
  });
  return frame;
}

test.describe("margin recommendation panel (workbench scenes)", () => {
  test("elevated-risk scene renders the wide card end to end", async ({
    page
  }) => {
    const frame = await openScene(page, "elevated-risk-recommendation");
    const panel = frame.locator(role("margin-rec-panel"));

    await expect(panel).toContainText("Margin recommendation");
    await expect(panel.locator(role("margin-rec-coin"))).toContainText("TSM");
    await expect(panel.locator(role("margin-rec-leverage"))).toContainText(
      "10x isolated"
    );

    await expect(panel.locator(role("margin-rec-recommended"))).toContainText(
      "$18.64 USDC"
    );
    await expect(panel.locator(role("margin-rec-recommended"))).toContainText(
      "+50.1% vs current"
    );
    await expect(panel.locator(role("margin-rec-additional"))).toContainText(
      "Add $6.22 USDC to your current $12.42"
    );

    await expect(panel.locator(role("margin-rec-risk-delta"))).toContainText(
      "14.6%"
    );
    await expect(panel.locator(role("margin-rec-risk-delta"))).toContainText(
      "2.1%"
    );
    await expect(panel.locator(role("margin-rec-new-liq"))).toContainText(
      "$403.10"
    );

    await expect(
      panel.locator(role("margin-rec-current-stats"))
    ).toHaveCount(0);
    await expect(panel.locator(role("margin-rec-stat-p-now"))).toHaveCount(0);

    await expect(panel.locator(role("margin-rec-buffers"))).toContainText(
      "Adverse-path protection"
    );
    await expect(panel.locator(role("margin-rec-buffers"))).toContainText(
      "$7.87 (42%)"
    );
    await expect(panel.locator(role("margin-rec-methods"))).toContainText(
      "Scenario simulation (4,000 paths)"
    );

    await expect(panel.locator(role("margin-rec-apply"))).toContainText(
      "Apply recommendation"
    );
    await expect(panel.locator(role("margin-rec-custom"))).toContainText(
      "Set custom margin"
    );
  });

  test("selecting a risk mode shows that mode's precomputed recommendation", async ({
    page
  }) => {
    const balanced = await openScene(page, "elevated-risk-recommendation");
    await expect(
      balanced.locator(role("margin-rec-recommended"))
    ).toContainText("$18.64 USDC");
    await expect(
      balanced.locator(role("margin-rec-risk-mode-balanced"))
    ).toHaveAttribute("aria-pressed", "true");

    const efficient = await openScene(page, "capital-efficient-selected");
    await expect(
      efficient.locator(role("margin-rec-recommended"))
    ).toContainText("$15.90 USDC");
    await expect(
      efficient.locator(role("margin-rec-additional"))
    ).toContainText("Add $3.48 USDC to your current $12.42");
    await expect(
      efficient.locator(role("margin-rec-risk-mode-capital-efficient"))
    ).toHaveAttribute("aria-pressed", "true");
  });

  test("help tooltips are styled and appear instantly on hover (not native title)", async ({
    page
  }) => {
    const frame = await openScene(page, "elevated-risk-recommendation");
    const panel = frame.locator(role("margin-rec-panel"));

    await expect(panel.locator("[title]")).toHaveCount(0);

    const visibleTips = () =>
      frame
        .locator("[role='tooltip']")
        .evaluateAll(
          (els) => els.filter((e) => getComputedStyle(e).opacity === "1").length
        );

    await page.mouse.move(5, 5);
    expect(await visibleTips()).toBe(0);

    await frame
      .locator(`${role("margin-rec-risk-delta")} >> text=Modeled liq. probability`)
      .hover();
    await expect
      .poll(async () => await visibleTips(), { timeout: 1000 })
      .toBe(1);
  });

  test("clickable buttons delay their tooltip (no flash while moving to click)", async ({
    page
  }) => {
    await page.setViewportSize({ width: 1440, height: 1200 });
    const frame = await openScene(page, "elevated-risk-recommendation");
    const shown = () =>
      frame
        .locator("[role='tooltip']")
        .evaluateAll(
          (els) => els.filter((e) => getComputedStyle(e).opacity !== "0").length
        );

    await frame.locator(role("margin-rec-apply")).hover();
    await page.waitForTimeout(500);
    expect(await shown()).toBe(0);
    await expect.poll(async () => await shown(), { timeout: 3000 }).toBe(1);
  });

  test("the chart is the full-width centrepiece", async ({ page }) => {
    const frame = await openScene(page, "elevated-risk-recommendation");
    const panel = await frame.locator(role("margin-rec-panel")).boundingBox();
    const chart = await frame.locator(role("margin-rec-curve")).boundingBox();
    expect(chart.width).toBeGreaterThan(panel.width * 0.85);
    expect(chart.width).toBeGreaterThan(640);
    expect(chart.height).toBeGreaterThan(240);
  });

  test("methodology and buffers are collapsed by default behind the disclosure", async ({
    page
  }) => {
    const frame = await openScene(page, "elevated-risk-recommendation");
    const panel = frame.locator(role("margin-rec-panel"));
    const methods = panel.locator(role("margin-rec-methods"));
    const advanced = panel.locator(role("margin-rec-advanced"));

    await expect(advanced).toBeVisible();
    await expect(methods).toBeHidden();

    await panel.locator(`${role("margin-rec-advanced")} summary`).click();
    await expect(methods).toBeVisible();
    await expect(methods).toContainText("Scenario simulation (4,000 paths)");
    await expect(panel.locator(role("margin-rec-buffers"))).toBeVisible();
  });

  test("curve renders with the current marker left of the recommended marker", async ({
    page
  }) => {
    const frame = await openScene(page, "elevated-risk-recommendation");
    const svg = frame.locator(role("margin-rec-curve"));
    await expect(svg).toBeVisible();

    const currentDot = svg.locator(
      `${role("margin-rec-curve-current")} circle`
    );
    const recommendedDot = svg.locator(
      `${role("margin-rec-curve-recommended")} circle`
    );
    await expect(currentDot).toBeVisible();
    await expect(recommendedDot).toBeVisible();

    const currentCx = Number(await currentDot.getAttribute("cx"));
    const recommendedCx = Number(await recommendedDot.getAttribute("cx"));
    expect(currentCx).toBeGreaterThan(0);
    expect(recommendedCx).toBeGreaterThan(currentCx);

    await expect(svg).toContainText("Isolated margin (USDC)");
    await expect(
      svg.locator(role("margin-rec-curve-current"))
    ).toContainText("Current");
    await expect(
      svg.locator(role("margin-rec-curve-recommended"))
    ).toContainText("Recommended");
  });

  test("curve is stroked with an amber-to-green gradient across the marker band", async ({
    page
  }) => {
    const frame = await openScene(page, "elevated-risk-recommendation");
    const polyline = frame.locator(role("margin-rec-curve") + " polyline");
    await expect(polyline).toHaveAttribute(
      "stroke",
      "url(#margin-rec-curve-gradient)"
    );

    const stops = frame.locator("#margin-rec-curve-gradient stop");
    await expect(stops).toHaveCount(4);

    const offsets = await stops.evaluateAll((els) =>
      els.map((el) => parseFloat(el.getAttribute("offset")))
    );
    expect(offsets[0]).toBe(0);
    expect(offsets[3]).toBe(100);
    expect(offsets[1]).toBeGreaterThan(offsets[0]);
    expect(offsets[2]).toBeGreaterThan(offsets[1]);
    expect(offsets[3]).toBeGreaterThan(offsets[2]);
  });

  test("within-target scene hides apply but keeps custom margin", async ({
    page
  }) => {
    const frame = await openScene(page, "within-target");
    const panel = frame.locator(role("margin-rec-panel"));

    await expect(panel.locator(role("margin-rec-within-target"))).toBeVisible();
    await expect(panel.locator(role("margin-rec-apply"))).toHaveCount(0);
    await expect(panel.locator(role("margin-rec-custom"))).toBeVisible();
  });

  test("cached results without a curve omit the chart card only", async ({
    page
  }) => {
    const frame = await openScene(page, "cached-result-without-curve");
    const panel = frame.locator(role("margin-rec-panel"));

    await expect(panel.locator(role("margin-rec-summary"))).toBeVisible();
    await expect(panel.locator(role("margin-rec-curve-card"))).toHaveCount(0);
    await expect(panel.locator(role("margin-rec-advanced"))).toBeVisible();
    await expect(panel.locator(role("margin-rec-buffers"))).toHaveCount(1);
  });
});
