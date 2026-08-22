import { expect, test } from "@playwright/test";
import { dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

// End-to-end coverage for the PnL share card.
//
// The card is a pure function of a Positions row view-model, so the unit suite
// already pins its contents. What only a browser can prove is the rest: that
// the lazy module loads and the modal actually paints, that switching template
// repaints the artwork without moving a single number, that a toggled-off field
// leaves nothing behind, and that the on-screen SVG rasterizes to a correctly
// sized PNG with the webfont inlined.
//
// Note for anyone debugging this by hand in a Browser MCP pane: that pane's tab
// reports document.hidden === true, so Replicant's rAF-scheduled render never
// runs and the modal appears to never open. Use Playwright.

const WINNING_POSITION = {
  type: "oneWay",
  dex: null,
  position: {
    coin: "SOL",
    szi: "41.2",
    entryPx: "142.08",
    markPx: "168.90",
    positionValue: "6958.68",
    unrealizedPnl: "18204.11",
    returnOnEquity: "2.146",
    liquidationPx: "98.4",
    marginUsed: "8481.2",
    leverage: { type: "cross", value: 20 },
    cumFunding: { allTime: "24.5", sinceOpen: "18.4" }
  }
};

const LOSING_POSITION = {
  type: "oneWay",
  dex: null,
  position: {
    coin: "AMZN",
    szi: "39",
    entryPx: "262.87",
    markPx: "257.82",
    positionValue: "10054.98",
    unrealizedPnl: "-3946.44",
    returnOnEquity: "-0.384",
    liquidationPx: "251.1",
    marginUsed: "512.75",
    leverage: { type: "isolated", value: 20 },
    cumFunding: { allTime: "41.1", sinceOpen: "41.1" }
  }
};

async function openShareCard(page, position) {
  await dispatch(page, [":actions/open-pnl-share-card", position]);
  await expect(page.locator("[data-role='pnl-share-modal']")).toBeVisible();
  await expect(page.locator("[data-role='pnl-share-card-svg']")).toBeVisible();
}

async function cardText(page) {
  return page.evaluate(() =>
    Array.from(
      document.querySelectorAll("[data-role='pnl-share-card-svg'] text")
    ).map((node) => node.textContent)
  );
}

async function rasterizeCard(page) {
  return page.evaluate(async () => {
    const node = document.querySelector("[data-role='pnl-share-card-svg']");
    const result = await globalThis.hyperopen.pnl_share.raster.render_png(node, {
      scale: 2,
      dataUrl: true,
      fonts: [
        { family: "JetBrains Mono", weight: 400, url: "/fonts/JetBrainsMono-Regular.woff2" },
        { family: "JetBrains Mono", weight: 500, url: "/fonts/JetBrainsMono-Medium.woff2" }
      ]
    });
    return {
      width: result.width,
      height: result.height,
      bytes: result.blob.size,
      type: result.blob.type,
      head: Array.from(atob(result.dataUrl.split(",")[1].slice(0, 16)))
        .slice(0, 8)
        .map((c) => c.charCodeAt(0))
    };
  });
}

// The icon host sends no Access-Control-Allow-Origin, so the card reads icons
// through the same-origin proxy (functions/api/coin-icon/[key].js in
// production, tools/hyperunit-proxy in development). Stubbing that one route is
// what lets this spec prove the whole chain -- fetch, base64, embed, rasterize
// -- without depending on a third party being up.
const STUB_ICON =
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">' +
  '<circle cx="16" cy="16" r="16" fill="#f7931a"/>' +
  '<path d="M9 8h9a6 6 0 0 1 0 12H9z" fill="#ffffff"/></svg>';

async function stubCoinIcons(page) {
  await page.route("**/api/coin-icon/**", (route) =>
    route.fulfill({
      status: 200,
      contentType: "image/svg+xml",
      headers: { "access-control-allow-origin": "*" },
      body: STUB_ICON
    })
  );
}

test.describe("pnl share card", () => {
  test.beforeEach(async ({ page }) => {
    await visitRoute(page, "/trade");
    await waitForIdle(page);
  });

  test("opens from a position and renders the figures the row shows @regression", async ({
    page
  }) => {
    await openShareCard(page, WINNING_POSITION);

    const texts = await cardText(page);
    expect(texts).toContain("+214.6%");
    expect(texts).toContain("SOL");
    expect(texts).toContain("LONG 20X");
    expect(texts).toContain("$142.08");
    expect(texts).toContain("$168.90");
    expect(
      texts.some((t) => t.includes("UNREALIZED")),
      "an open position's PnL must be labelled unrealized"
    ).toBe(true);
  });

  test("switching template repaints the art and moves no numbers @regression", async ({
    page
  }) => {
    await openShareCard(page, WINNING_POSITION);
    const before = await cardText(page);

    await page.locator("[data-role='pnl-share-template-option'][data-template='number-hero']").click();
    await expect(
      page.locator("[data-role='pnl-share-template-option'][data-template='number-hero']")
    ).toHaveAttribute("aria-pressed", "true");

    const after = await cardText(page);
    for (const figure of ["+214.6%", "$142.08", "$168.90"]) {
      expect(before, `before: ${figure}`).toContain(figure);
      expect(after, `after: ${figure}`).toContain(figure);
    }
    expect(after, "the two templates must not render identically").not.toEqual(before);
  });

  test("a toggled-off field leaves nothing behind @regression", async ({ page }) => {
    await openShareCard(page, WINNING_POSITION);
    expect(await cardText(page)).toContain("Entry Price");

    await page.locator("[data-role='pnl-share-field-toggle'][data-field='show-prices?']").click();
    await expect(
      page.locator("[data-role='pnl-share-field-toggle'][data-field='show-prices?']")
    ).toHaveAttribute("aria-pressed", "false");

    const texts = await cardText(page);
    expect(texts).not.toContain("Entry Price");
    expect(texts).not.toContain("Mark Price");
    expect(texts.filter((t) => t.trim() === ""), "no blank text nodes left behind").toHaveLength(0);
  });

  test("the losing treatment repaints and says so @regression", async ({ page }) => {
    await openShareCard(page, LOSING_POSITION);
    const texts = await cardText(page);
    expect(texts).toContain("-38.4%");
    expect(texts).toContain("CERTIFIED L");
    expect(texts.some((t) => t.includes("UNREALIZED LOSS"))).toBe(true);
  });

  test("the on-screen card rasterizes to a 2x PNG @regression", async ({ page }) => {
    await openShareCard(page, WINNING_POSITION);
    const png = await rasterizeCard(page);

    expect(png.head).toEqual([137, 80, 78, 71, 13, 10, 26, 10]);
    expect(png.width).toBe(2160);
    expect(png.height).toBe(1216);
    expect(png.type).toBe("image/png");
    expect(
      png.bytes,
      "a card this dense should not encode to a near-empty PNG"
    ).toBeGreaterThan(20_000);
  });

  test("the caption counter tracks what X will accept @regression", async ({ page }) => {
    await openShareCard(page, WINNING_POSITION);
    const counter = page.locator("[data-role='pnl-share-caption-count']");
    await expect(counter).toHaveText("0/280");

    await page.locator("[data-role='pnl-share-caption']").fill("gm");
    await expect(counter).toHaveText("2/280");
  });

  test("the real coin icon is embedded, not linked @regression", async ({ page }) => {
    await stubCoinIcons(page);
    await openShareCard(page, WINNING_POSITION);

    const image = page.locator("[data-role='pnl-share-card-svg'] image");
    await expect(image).toHaveCount(1);

    const href = await image.getAttribute("href");
    expect(
      href.startsWith("data:image/svg+xml;base64,"),
      "an external href would render the broken-image placeholder once rasterized"
    ).toBe(true);

    const png = await rasterizeCard(page);
    expect(png.head).toEqual([137, 80, 78, 71, 13, 10, 26, 10]);
    expect(png.width).toBe(2160);
  });

  test("an unreachable icon falls back to the monogram @regression", async ({ page }) => {
    await page.route("**/api/coin-icon/**", (route) => route.fulfill({ status: 404, body: "" }));
    await openShareCard(page, WINNING_POSITION);

    await expect(page.locator("[data-role='pnl-share-card-svg'] image")).toHaveCount(0);
    expect(await cardText(page)).toContain("S");

    const png = await rasterizeCard(page);
    expect(png.width, "a missing icon must not break the export").toBe(2160);
  });

  test("escape closes the modal @regression", async ({ page }) => {
    await openShareCard(page, WINNING_POSITION);
    await page.locator("[data-role='pnl-share-modal']").press("Escape");
    await expect(page.locator("[data-role='pnl-share-modal']")).toHaveCount(0);
  });
});
