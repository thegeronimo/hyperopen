import { expect, test } from "../support/guarded_test.mjs";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import {
  keyword,
  optimizerPath,
  readOptimizerState,
  seedOptimizerState,
  seedPatch
} from "../support/optimizer_state.mjs";

const PANEL = "[data-role='portfolio-optimizer-constraints-panel']";

async function expectPortfolioExposureOpen(page) {
  const panel = page.locator(PANEL);
  await expect(panel).toHaveCount(1);
  await expect.poll(async () => panel.evaluate((el) => el.open)).toBe(true);
  await expect(panel.locator("> summary")).toContainText("Portfolio exposure");
  await expect(panel).toContainText("Set target leverage and net long/short bias.");
}

// The band sliders, current-portfolio line, and memory row live behind the
// "Fine-tune exposure" disclosure (2026-07-10 simplified default view).
async function openFineTune(page) {
  const drawer = page.locator("[data-role='portfolio-optimizer-exposure-fine-tune']");
  if (!(await drawer.evaluate((el) => el.open))) {
    await drawer.locator("> summary").click();
  }
}

async function expectControlUnavailable(locator) {
  const count = await locator.count();
  if (count === 0) {
    expect(count).toBe(0);
    return;
  }
  await expect(locator.first()).toBeDisabled();
}

async function expectControlAvailable(locator) {
  await expect(locator).toHaveCount(1);
  await expect(locator).toBeEnabled();
}

test.describe("optimizer exposure-map Positioning control", () => {
  test.beforeEach(async ({ page }) => {
    await visitRoute(page, "/portfolio/optimize/new");
    await expectPortfolioExposureOpen(page);
  });

  test("renders the quiet default view; bands and memory sit behind Fine-tune", async ({ page }) => {
    await expect(page.locator("[data-role='portfolio-optimizer-exposure-map']")).toBeVisible();
    await expect(page.locator("[data-role='portfolio-optimizer-exposure-pad']")).toBeVisible();
    await expect(page.locator("[data-role='portfolio-optimizer-exposure-handle']")).toBeVisible();
    await expect(page.locator("[data-role='portfolio-optimizer-exposure-echo']")).toContainText(
      "Sent to solver"
    );
    // The drag caption and preset chips are gone (2026-07-10 simplified view).
    await expect(page.locator("[data-role='portfolio-optimizer-exposure-caption']")).toHaveCount(0);
    await expect(
      page.locator("[data-role='portfolio-optimizer-exposure-preset-balanced']")
    ).toHaveCount(0);
    // Bands + memory rest hidden behind the Fine-tune drawer…
    await expect(
      page.locator("[data-role='portfolio-optimizer-exposure-gross-band']")
    ).not.toBeVisible();
    await expect(
      page.locator("[data-role='portfolio-optimizer-exposure-profile']")
    ).not.toBeVisible();
    await openFineTune(page);
    await expect(
      page.locator("[data-role='portfolio-optimizer-exposure-gross-band']")
    ).toBeVisible();
    await expect(
      page.locator("[data-role='portfolio-optimizer-exposure-profile']")
    ).toBeVisible();
    // (No exposure-preview assertion: the CURRENT line only exists once a
    // connected/spectated book supplies a current exposure.)
    // A fresh draft is the Balanced policy; its echo caps gross at 2x with no floor.
    await expect(page.locator("[data-role='portfolio-optimizer-exposure-echo']")).toContainText(
      "gross ≤ 2.00×"
    );
  });

  test("the pad is bounded and the Run bar stays visible with the panel open", async ({
    page
  }) => {
    // The open Portfolio exposure panel must not consume more than a screen: the pad renders in a
    // bounded column (~21rem) instead of spanning the full center pane.
    const pad = page.locator("[data-role='portfolio-optimizer-exposure-pad']");
    await expect(pad).toBeVisible();
    const box = await pad.boundingBox();
    expect(box.height).toBeLessThanOrEqual(360);
    expect(box.width).toBeLessThanOrEqual(360);
    // The sticky Run bar is pinned inside the viewport even while the tall panel is expanded.
    await expect(
      page.locator("[data-role='portfolio-optimizer-setup-bottom-actions']")
    ).toBeInViewport();
    // The live readout mirrors the Balanced targets in large type next to the pad.
    await expect(
      page.locator("[data-role='portfolio-optimizer-exposure-readout-gross']")
    ).toHaveText("2.00×");
    await expect(
      page.locator("[data-role='portfolio-optimizer-exposure-readout-net']")
    ).toHaveText("+1.00×");
  });

  test("the zoom control steps the fixed axis scale and dragging never grows it", async ({
    page
  }) => {
    const yMax = page.locator("[data-role='portfolio-optimizer-exposure-y-max']");
    const zoomOut = page.locator("[data-role='portfolio-optimizer-exposure-zoom-out']");
    const zoomIn = page.locator("[data-role='portfolio-optimizer-exposure-zoom-in']");
    // The Balanced policy fits the floor level: 0–3x, zoom-in disabled.
    await expect(yMax).toHaveText("3×");
    await expect(zoomIn).toBeDisabled();
    await zoomOut.click();
    await waitForIdle(page);
    await expect(yMax).toHaveText("5×");
    await expect(zoomIn).toBeEnabled();
    // Zooming back in returns to the smallest level that frames the policy.
    await zoomIn.click();
    await waitForIdle(page);
    await expect(yMax).toHaveText("3×");
    await zoomOut.click();
    await waitForIdle(page);
    await expect(yMax).toHaveText("5×");
    // Dragging to the pad's very top edge clamps to the visible scale instead of re-scaling
    // (the old adaptive axis ratcheted upward while the pointer was held at the edge).
    // The bounding box is re-captured before each drag: prior clicks/renders may scroll the
    // page, and a drag against stale viewport coordinates silently misses the pad.
    const pad = page.locator("[data-role='portfolio-optimizer-exposure-pad']");
    await pad.scrollIntoViewIfNeeded();
    const box = await pad.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2, box.y + 1, { steps: 5 });
    await page.mouse.move(box.x + box.width / 2, box.y + 1, { steps: 3 });
    await page.mouse.up();
    await waitForIdle(page);
    await expect(yMax).toHaveText("5×");
    expect(
      await readOptimizerState(page, optimizerPath("draft", "constraints", "gross-max"))
    ).toBeLessThanOrEqual(5);
    // The dragged policy now needs the 5x level itself, so zooming further in is disabled
    // (zooming in may never clip the band box).
    await expect(zoomIn).toBeDisabled();
    // Dragging back DOWN re-fits the policy smaller, but the scale stays pinned at the level
    // the drag was performed at — the pad never shrinks under the pointer. Zooming back in
    // re-arms instead.
    await pad.scrollIntoViewIfNeeded();
    const box2 = await pad.boundingBox();
    await page.mouse.move(box2.x + box2.width / 2, box2.y + box2.height / 2);
    await page.mouse.down();
    await page.mouse.move(box2.x + box2.width / 2, box2.y + box2.height * 0.6, { steps: 5 });
    await page.mouse.up();
    await waitForIdle(page);
    await expect(yMax).toHaveText("5×");
    await expect(zoomIn).toBeEnabled();
  });

  test("the read-only cards expose the editable controls behind Edit", async ({ page }) => {
    // Resting view: current values as text, controls behind the card's Edit toggle.
    await expect(
      page.locator("[data-role='portfolio-optimizer-risk-guards-cap-value']")
    ).toHaveText("50%");
    await expect(
      page.locator("[data-role='portfolio-optimizer-rebalancing-turnover-value']")
    ).toHaveText("1.00×");
    const maxWeight = page.locator(
      "[data-role='portfolio-optimizer-constraint-max-asset-weight-input']"
    );
    await expect(maxWeight).not.toBeVisible();
    const guards = page.locator("[data-role='portfolio-optimizer-risk-guards-card']");
    await guards.locator("> summary").click();
    await expect(maxWeight).toBeVisible();
  });

  test("widening the net band stores a percentage of gross, leaving the target alone", async ({
    page
  }) => {
    await openFineTune(page);
    const slider = page.locator("[data-role='portfolio-optimizer-exposure-net-band']");
    await slider.evaluate((el) => {
      el.value = "25"; // the control is denominated in percent
      el.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await waitForIdle(page);

    await expect(
      page.locator("[data-role='portfolio-optimizer-exposure-net-band-value']")
    ).toHaveText("± 25.0% of gross");
    expect(
      await readOptimizerState(page, optimizerPath("draft", "constraints", "net-band-pct"))
    ).toBe(0.25);
    // The percentage band is a tolerance around the UNCHANGED net target.
    expect(await readOptimizerState(page, optimizerPath("draft", "constraints", "net-min"))).toBe(
      1
    );
    expect(await readOptimizerState(page, optimizerPath("draft", "constraints", "net-max"))).toBe(
      1
    );
    // The approximate absolute preview reflects the configured gross target
    // (2x by default): 25% of 2x ≈ ±0.50x. Solver-side the band scales with
    // realized gross, so this line is explicitly a preview.
    await expect(
      page.locator("[data-role='portfolio-optimizer-exposure-net-band-abs-preview']")
    ).toContainText("≈ ±0.50× at 2.00× gross");
  });

  test("Equal Risk keeps gross editable and restores stored net controls after switching objectives", async ({
    page
  }) => {
    await seedOptimizerState(page, [
      seedPatch(optimizerPath("draft", "objective"), { kind: keyword("equal-risk") }),
      seedPatch(optimizerPath("draft", "constraints"), {
        "gross-max": 2.2,
        "net-min": -0.4,
        "net-max": 0.6,
        "net-band-pct": 0.15,
        "max-asset-weight": 0.5
      })
    ]);
    await waitForIdle(page);

    const panel = page.locator(PANEL);
    await expect(panel).toContainText("Resulting net");
    await expect(panel).toContainText(
      "Resulting net is determined by Equal Risk from covariance and selected sides."
    );
    await expect(page.locator("[data-role='portfolio-optimizer-exposure-readout-gross']")).toHaveText(
      "2.20×"
    );

    await openFineTune(page);
    await expectControlAvailable(
      page.locator("[data-role='portfolio-optimizer-exposure-gross-band']")
    );
    await expectControlUnavailable(
      page.locator("[data-role='portfolio-optimizer-exposure-net-band']")
    );
    await expectControlUnavailable(
      page.locator("[data-role='portfolio-optimizer-exposure-net-band-input']")
    );
    await expectControlUnavailable(
      page.locator("[data-role='portfolio-optimizer-constraint-net-min-input']")
    );
    await expectControlUnavailable(
      page.locator("[data-role='portfolio-optimizer-constraint-net-max-input']")
    );

    const storedNetBefore = {
      min: await readOptimizerState(page, optimizerPath("draft", "constraints", "net-min")),
      max: await readOptimizerState(page, optimizerPath("draft", "constraints", "net-max")),
      band: await readOptimizerState(
        page,
        optimizerPath("draft", "constraints", "net-band-pct")
      )
    };
    const grossBefore = await readOptimizerState(
      page,
      optimizerPath("draft", "constraints", "gross-max")
    );
    const pad = page.locator("[data-role='portfolio-optimizer-exposure-pad']");
    await pad.scrollIntoViewIfNeeded();
    const box = await pad.boundingBox();
    await page.mouse.move(box.x + box.width * 0.1, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.1, box.y + box.height * 0.2, {
      steps: 8
    });
    await page.mouse.up();
    await waitForIdle(page);
    expect(
      await readOptimizerState(page, optimizerPath("draft", "constraints", "gross-max"))
    ).not.toBe(grossBefore);
    expect({
      min: await readOptimizerState(page, optimizerPath("draft", "constraints", "net-min")),
      max: await readOptimizerState(page, optimizerPath("draft", "constraints", "net-max")),
      band: await readOptimizerState(
        page,
        optimizerPath("draft", "constraints", "net-band-pct")
      )
    }).toEqual(storedNetBefore);

    await page.locator("[data-role='portfolio-optimizer-objective-minimum-variance']").click();
    await waitForIdle(page);
    await expect(panel).toContainText("Set target leverage and net long/short bias.");
    await expectControlAvailable(
      page.locator("[data-role='portfolio-optimizer-exposure-net-band']")
    );
    await expectControlAvailable(
      page.locator("[data-role='portfolio-optimizer-exposure-net-band-input']")
    );
    await expectControlAvailable(
      page.locator("[data-role='portfolio-optimizer-constraint-net-min-input']")
    );
    await expectControlAvailable(
      page.locator("[data-role='portfolio-optimizer-constraint-net-max-input']")
    );
    expect(
      await readOptimizerState(page, optimizerPath("draft", "constraints", "net-min"))
    ).toBe(storedNetBefore.min);
    expect(
      await readOptimizerState(page, optimizerPath("draft", "constraints", "net-max"))
    ).toBe(storedNetBefore.max);
    expect(
      await readOptimizerState(page, optimizerPath("draft", "constraints", "net-band-pct"))
    ).toBe(storedNetBefore.band);
  });

  test("the Advanced drawer exposes the raw gross/net min/max fields", async ({ page }) => {
    const advanced = page.locator("[data-role='portfolio-optimizer-constraints-advanced']");
    await expect(advanced).toHaveCount(1);
    await advanced.evaluate((el) => {
      el.open = true;
    });
    await expect(
      page.locator("[data-role='portfolio-optimizer-constraint-gross-max-input']")
    ).toBeVisible();
    await expect(
      page.locator("[data-role='portfolio-optimizer-constraint-net-min-input']")
    ).toBeVisible();
  });

  test("band sliders visibly expand the allowed region at any zoom via full-length stripes", async ({
    page
  }) => {
    // Regression: at a wide axis the band box around the dot is smaller than the
    // drag handle itself, so slider drags looked like they did nothing. The
    // full-width gross stripe / full-height net stripe are the always-visible
    // rendering of the band, at every zoom level.
    const grossStripe = page.locator(
      "[data-role='portfolio-optimizer-exposure-gross-stripe']"
    );
    const netStripe = page.locator(
      "[data-role='portfolio-optimizer-exposure-net-stripe']"
    );
    await openFineTune(page);
    const grossSlider = page.locator(
      "[data-role='portfolio-optimizer-exposure-gross-band']"
    );
    await expect(grossStripe).toHaveCount(1);
    await expect(netStripe).toHaveCount(1);
    // The gross stripe spans the full pad width; the net band renders as a
    // sloped WEDGE polygon (net = target ± pct·gross), not a vertical stripe.
    await expect(grossStripe).toHaveAttribute("width", "100");
    await expect(netStripe).toHaveAttribute("points", /,/);
    const heightBefore = Number(await grossStripe.getAttribute("height"));
    // Drag the gross band slider to its maximum.
    const box = await grossSlider.boundingBox();
    await page.mouse.click(box.x + box.width - 2, box.y + box.height / 2);
    await waitForIdle(page);
    await expect(
      page.locator("[data-role='portfolio-optimizer-exposure-gross-band-value']")
    ).toHaveText("± 0.50×");
    const heightAfter = Number(await grossStripe.getAttribute("height"));
    expect(heightAfter).toBeGreaterThan(heightBefore);
    // The allowed-region band box is the wedge/gross-stripe intersection.
    const bandBox = page.locator("[data-role='portfolio-optimizer-exposure-band-box']");
    await expect(bandBox).toHaveAttribute("points", /,/);
  });

  test("the net band wedge is sloped: wider at higher gross", async ({ page }) => {
    await openFineTune(page);
    const slider = page.locator("[data-role='portfolio-optimizer-exposure-net-band']");
    await slider.evaluate((el) => {
      el.value = "20";
      el.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await waitForIdle(page);
    const points = await page
      .locator("[data-role='portfolio-optimizer-exposure-net-stripe']")
      .getAttribute("points");
    const vertices = points.split(" ").map((pair) => pair.split(",").map(Number));
    const ys = vertices.map(([, y]) => y);
    const topY = Math.min(...ys);
    const bottomY = Math.max(...ys);
    const widthAt = (y) => {
      const xs = vertices.filter(([, vy]) => vy === y).map(([x]) => x);
      return Math.max(...xs) - Math.min(...xs);
    };
    // y grows downward: the top edge is the highest gross, where the
    // percentage band's absolute width must be largest.
    expect(widthAt(topY)).toBeGreaterThan(widthAt(bottomY));
  });
});

test.describe("optimizer exposure-map drag under Maximum Sharpe", () => {
  test("dragging the pad never recomputes the risk model per pointermove", async ({ page }) => {
    // Regression: under Maximum Sharpe the Return-views rail (and BL preview)
    // read the baseline expected-return inputs on every render, and each read
    // estimated the full covariance matrix from history. A pad drag renders
    // once per pointermove, so the drag recomputed the risk model dozens of
    // times per second and janked. The inputs are memoized on the request
    // sub-values they consume, so a constraints-only change must not call
    // risk/estimate-risk-model at all.
    await visitRoute(page, "/portfolio/optimize/new");
    await seedOptimizerState(page, [
      seedPatch(optimizerPath("draft", "universe"), [
        { "instrument-id": "perp:BTC", "market-type": keyword("perp"), coin: "BTC" },
        { "instrument-id": "perp:ETH", "market-type": keyword("perp"), coin: "ETH" },
        { "instrument-id": "perp:SOL", "market-type": keyword("perp"), coin: "SOL" },
        { "instrument-id": "perp:HYPE", "market-type": keyword("perp"), coin: "HYPE" }
      ]),
      seedPatch(optimizerPath("draft", "objective"), { kind: keyword("max-sharpe") }),
      seedPatch(optimizerPath("draft", "return-model"), {
        kind: keyword("black-litterman"),
        views: []
      })
    ]);
    await waitForIdle(page);
    // The Maximum-Sharpe-only surface that triggered the per-frame recompute.
    await expect(
      page.locator("[data-role='portfolio-optimizer-setup-use-my-views-editor']")
    ).toHaveCount(1);

    const pad = page.locator("[data-role='portfolio-optimizer-exposure-pad']");
    await pad.scrollIntoViewIfNeeded();
    const box = await pad.boundingBox();

    // Count risk-model estimations from here on (dev builds expose namespaces).
    await page.evaluate(() => {
      const risk = globalThis.hyperopen.portfolio.optimizer.domain.risk;
      const original = risk.estimate_risk_model;
      globalThis.__optimizerRiskModelCalls = 0;
      risk.estimate_risk_model = function (...args) {
        globalThis.__optimizerRiskModelCalls += 1;
        return original.apply(this, args);
      };
    });

    const grossBefore = await readOptimizerState(
      page,
      optimizerPath("draft", "constraints", "gross-max")
    );
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.7, box.y + box.height * 0.3, { steps: 12 });
    await page.mouse.move(box.x + box.width * 0.4, box.y + box.height * 0.55, { steps: 12 });
    await page.mouse.up();
    const riskModelCalls = await page.evaluate(() => globalThis.__optimizerRiskModelCalls);
    await waitForIdle(page);

    // The drag must have actually re-rendered the page per move…
    expect(
      await readOptimizerState(page, optimizerPath("draft", "constraints", "gross-max"))
    ).not.toBe(grossBefore);
    // …without re-estimating the covariance matrix. A small allowance covers an
    // async history-load transition landing mid-drag (a legitimate input
    // change); the unmemoized bug produced one-plus call per pointermove.
    expect(riskModelCalls).toBeLessThanOrEqual(2);
  });

  test("switching Minimum risk to Maximum Sharpe estimates the risk model at most once", async ({
    page
  }) => {
    // Regression (owner trace 2026-07-08 18:59): the goal-card switch paid the
    // multi-second Ledoit-Wolf estimate repeatedly - once per UI helper (the
    // two return-input memos each estimated independently) and again on every
    // 5s as-of bucket roll, because the memo keys included the history's
    // wall-clock :freshness stamp. One covariance estimate is shared and the
    // keys drop :freshness, so the switch estimates at most once and later
    // draft edits across a bucket boundary estimate zero times.
    await visitRoute(page, "/portfolio/optimize/new");
    await seedOptimizerState(page, [
      seedPatch(optimizerPath("draft", "universe"), [
        { "instrument-id": "perp:BTC", "market-type": keyword("perp"), coin: "BTC" },
        { "instrument-id": "perp:ETH", "market-type": keyword("perp"), coin: "ETH" },
        { "instrument-id": "perp:SOL", "market-type": keyword("perp"), coin: "SOL" },
        { "instrument-id": "perp:HYPE", "market-type": keyword("perp"), coin: "HYPE" }
      ])
    ]);
    await waitForIdle(page);
    // Default objective is Minimum risk; the Max-Sharpe surfaces are unmounted.
    await expect(
      page.locator("[data-role='portfolio-optimizer-setup-use-my-views-editor']")
    ).toHaveCount(0);

    await page.evaluate(() => {
      const risk = globalThis.hyperopen.portfolio.optimizer.domain.risk;
      const original = risk.estimate_risk_model;
      globalThis.__optimizerRiskModelCalls = 0;
      risk.estimate_risk_model = function (...args) {
        globalThis.__optimizerRiskModelCalls += 1;
        return original.apply(this, args);
      };
    });

    await page.locator("text=Maximum Sharpe").first().click();
    await waitForIdle(page);
    await expect(
      page.locator("[data-role='portfolio-optimizer-setup-use-my-views-editor']")
    ).toHaveCount(1);
    const callsAfterSwitch = await page.evaluate(
      () => globalThis.__optimizerRiskModelCalls
    );
    // One shared estimate for both return-input helpers (an async history
    // transition landing mid-switch may legitimately add one more).
    expect(callsAfterSwitch).toBeLessThanOrEqual(2);

    // Cross a 5s as-of bucket boundary, then force a request rebuild with a
    // draft edit. The history data is unchanged, so no re-estimation.
    await page.waitForTimeout(5500);
    const slider = page.locator("[data-role='portfolio-optimizer-exposure-net-band']");
    await slider.evaluate((el) => {
      el.value = "0.25";
      el.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await waitForIdle(page);
    const callsAfterBucketRoll = await page.evaluate(
      () => globalThis.__optimizerRiskModelCalls
    );
    expect(callsAfterBucketRoll).toBe(callsAfterSwitch);
  });
});
