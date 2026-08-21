import { expect, test } from "@playwright/test";
import { visitRoute } from "../support/hyperopen.mjs";

// Milestone 0 harness for the PnL share card export path.
//
// The riskiest part of the share-card feature is not the artwork, it is whether
// an SVG -> PNG round trip with an inlined webfont produces correct bytes in a
// real browser. This spec proves the rasterizer in isolation, against a fixture
// SVG that shares no code with the card templates.
//
// It asserts four things:
//   1. the result really is a PNG (signature bytes, not just a MIME string),
//   2. it is rasterized at 2x the SVG's intrinsic size,
//   3. a known fill colour survives the round trip at the expected pixel, and
//   4. inlining the font actually changed the rendering -- rendering the same
//      fixture with no inlined faces must produce different bytes. If the
//      @font-face injection silently failed, both renders would fall back to
//      the same generic monospace and the two data URLs would be identical.
//
// An SVG loaded through an Image element renders in a sandboxed document that
// cannot fetch anything, so assertion 4 is the one that catches the failure
// mode that matters: a card that looks right on screen and ships a PNG set in
// the wrong typeface.

const FIXTURE_WIDTH = 200;
const FIXTURE_HEIGHT = 100;
const FIXTURE_FILL = "#00d4aa";
const EXPECTED_RGB = [0, 212, 170];

const PNG_SIGNATURE = [137, 80, 78, 71, 13, 10, 26, 10];

// Deliberately glyph-heavy and wide: the more advance-width differences between
// JetBrains Mono and the fallback stack, the more pixels differ in assertion 4.
const FIXTURE_TEXT = "+214.6% W@1809";

function fixtureSvgMarkup() {
  return [
    `<svg xmlns="http://www.w3.org/2000/svg"`,
    ` width="${FIXTURE_WIDTH}" height="${FIXTURE_HEIGHT}"`,
    ` viewBox="0 0 ${FIXTURE_WIDTH} ${FIXTURE_HEIGHT}"`,
    ` data-role="pnl-share-export-fixture">`,
    `<rect x="0" y="0" width="${FIXTURE_WIDTH}" height="${FIXTURE_HEIGHT}" fill="#000000"></rect>`,
    `<rect x="20" y="20" width="60" height="30" fill="${FIXTURE_FILL}"></rect>`,
    `<text x="10" y="85" font-family="JetBrains Mono, monospace"`,
    ` font-size="20" font-weight="500" fill="#ffffff">${FIXTURE_TEXT}</text>`,
    `</svg>`
  ].join("");
}

async function renderFixture(page, { withFonts }) {
  return page.evaluate(
    async ({ markup, withFonts: inlineFonts }) => {
      const raster = globalThis.hyperopen?.pnl_share?.raster;
      const render = raster?.render_png;
      if (typeof render !== "function") {
        throw new Error(
          "hyperopen.pnl_share.raster.render_png is not exported; the rasterizer does not exist yet"
        );
      }

      const host = document.createElement("div");
      // Keep the node in the document but out of the way: some engines refuse to
      // serialize a detached SVG's computed geometry.
      host.setAttribute("style", "position:fixed;left:-9999px;top:0;");
      host.innerHTML = markup;
      document.body.appendChild(host);

      try {
        const result = await render(host.firstElementChild, {
          scale: 2,
          dataUrl: true,
          fonts: inlineFonts
            ? [
                { family: "JetBrains Mono", weight: 400, url: "/fonts/JetBrainsMono-Regular.woff2" },
                { family: "JetBrains Mono", weight: 500, url: "/fonts/JetBrainsMono-Medium.woff2" }
              ]
            : []
        });

        const probe = document.createElement("canvas");
        probe.width = result.width;
        probe.height = result.height;
        const ctx = probe.getContext("2d");
        const img = await new Promise((resolve, reject) => {
          const el = new Image();
          el.onload = () => resolve(el);
          el.onerror = reject;
          el.src = result.dataUrl;
        });
        ctx.drawImage(img, 0, 0);
        // Centre of the 60x30 swatch at (20,20), doubled by the 2x scale.
        const px = ctx.getImageData(100, 70, 1, 1).data;

        const head = atob(result.dataUrl.split(",")[1].slice(0, 16));
        const signature = Array.from(head.slice(0, 8)).map((c) => c.charCodeAt(0));

        return {
          width: result.width,
          height: result.height,
          dataUrl: result.dataUrl,
          signature,
          pixel: [px[0], px[1], px[2], px[3]]
        };
      } finally {
        host.remove();
      }
    },
    { markup: fixtureSvgMarkup(), withFonts }
  );
}

test.describe("pnl share card export", () => {
  test("rasterizes an SVG to a 2x PNG with fonts inlined @regression", async ({
    page
  }) => {
    await visitRoute(page, "/trade");

    const withFonts = await renderFixture(page, { withFonts: true });

    expect(
      withFonts.signature,
      "result is not a PNG; the first eight bytes must be the PNG signature"
    ).toEqual(PNG_SIGNATURE);

    expect(withFonts.width).toBe(FIXTURE_WIDTH * 2);
    expect(withFonts.height).toBe(FIXTURE_HEIGHT * 2);

    expect(
      [withFonts.pixel[0], withFonts.pixel[1], withFonts.pixel[2]],
      "the known swatch colour did not survive the round trip"
    ).toEqual(EXPECTED_RGB);
    expect(withFonts.pixel[3], "the swatch pixel is not fully opaque").toBe(255);

    const withoutFonts = await renderFixture(page, { withFonts: false });

    expect(
      withoutFonts.dataUrl,
      "inlining JetBrains Mono changed nothing, so the @font-face injection silently failed and the PNG is set in the fallback face"
    ).not.toBe(withFonts.dataUrl);
  });
});
