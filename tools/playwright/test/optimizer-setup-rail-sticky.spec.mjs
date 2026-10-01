// Setup-page scroll jank regression: the 3-column workbench page-scrolls to the
// tall CENTER policy pane (exposure map + every policy panel), while the shorter
// LEFT universe rail and RIGHT scenario-contract rail used to end far above the
// bottom, leaving large empty voids you scrolled into ("very janky … cut off").
// The side rails are now sticky at desktop width so they stay in view while the
// center scrolls, and a rail scrolls internally when its own content is taller
// than the viewport. This is gated to the xl (>=1280px) 3-column breakpoint;
// below that the layout is a single stacked column with normal page scroll.
import { expect, test } from "../support/guarded_test.mjs";
import { visitRoute } from "../support/hyperopen.mjs";
import { keyword, optimizerPath, seedOptimizerState, seedPatch } from "../support/optimizer_state.mjs";

const COINS = [
  "BTC", "ETH", "SOL", "HBAR", "FET", "BERA", "WLD", "kBONK", "APT",
  "ARB", "SUI", "ZRO", "ZEC", "SEI", "TAO", "LDO", "APE", "ZK"
];

function instrument(coin) {
  return {
    "instrument-id": `perp:${coin}`,
    "market-type": keyword("perp"),
    coin,
    symbol: `${coin}-USDC`,
    name: coin
  };
}

const CONTROL_RAIL = "[data-role='portfolio-optimizer-setup-control-rail']";
const CONTEXT_RAIL = "[data-role='portfolio-optimizer-right-rail']";
const APP_ROOT = "[data-parity-id='app-root']";

async function seedLongCustomUniverse(page) {
  await seedOptimizerState(page, [
    seedPatch(optimizerPath("draft", "universe"), COINS.map(instrument)),
    seedPatch(optimizerPath("draft", "objective"), { kind: keyword("minimum-variance") }),
    seedPatch(optimizerPath("draft", "metadata", "universe-source"), { kind: keyword("custom") })
  ]);
}

function railTop(page, selector) {
  return page.locator(selector).evaluate((el) => Math.round(el.getBoundingClientRect().top));
}

function computedPosition(page, selector) {
  return page.locator(selector).evaluate((el) => getComputedStyle(el).position);
}

async function scrollAppRoot(page, to) {
  await page.evaluate(
    ({ sel, to }) => {
      const root = document.querySelector(sel);
      root.scrollTop = to === "bottom" ? root.scrollHeight : to;
    },
    { sel: APP_ROOT, to }
  );
}

test.describe("optimizer setup side rails stay pinned while the center scrolls", () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test("side rails are sticky and remain in view at the bottom of a tall page @regression", async ({ page }) => {
    await visitRoute(page, "/portfolio/optimize/new");
    await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();

    await seedLongCustomUniverse(page);

    await expect(page.locator(CONTROL_RAIL)).toBeVisible();
    await expect(page.locator(CONTEXT_RAIL)).toBeVisible();

    // Both side rails are sticky at the 3-column desktop breakpoint.
    await expect.poll(() => computedPosition(page, CONTROL_RAIL)).toBe("sticky");
    await expect.poll(() => computedPosition(page, CONTEXT_RAIL)).toBe("sticky");

    // The tall center pane makes the page genuinely overflow (there is something to scroll).
    const overflow = await page.evaluate((sel) => {
      const root = document.querySelector(sel);
      return root.scrollHeight - root.clientHeight;
    }, APP_ROOT);
    expect(overflow).toBeGreaterThan(200);

    // Mid-scroll: both rails pin to the top of the viewport instead of scrolling away.
    await scrollAppRoot(page, 600);
    await expect.poll(() => railTop(page, CONTROL_RAIL)).toBeLessThan(80);
    await expect.poll(() => railTop(page, CONTEXT_RAIL)).toBeLessThan(80);

    // Bottom of the page: the rails are still in view (previously they were stranded
    // hundreds of px above, leaving an empty void here).
    await scrollAppRoot(page, "bottom");
    await expect(page.locator(CONTROL_RAIL)).toBeInViewport();
    await expect(page.locator(CONTEXT_RAIL)).toBeInViewport();

    // A long universe caps the LEFT rail and scrolls it internally rather than
    // stretching the page further.
    const railInternallyScrolls = await page
      .locator(CONTROL_RAIL)
      .evaluate((el) => el.scrollHeight > el.clientHeight + 2);
    expect(railInternallyScrolls).toBe(true);
  });

  test("below the xl breakpoint the rails are static (single-column page scroll) @regression", async ({ page }) => {
    await page.setViewportSize({ width: 768, height: 1024 });
    await visitRoute(page, "/portfolio/optimize/new");
    await expect(page.locator("[data-role='portfolio-optimizer-setup-route-surface']")).toBeVisible();
    await seedLongCustomUniverse(page);

    await expect.poll(() => computedPosition(page, CONTROL_RAIL)).toBe("static");
    await expect.poll(() => computedPosition(page, CONTEXT_RAIL)).toBe("static");
  });
});
