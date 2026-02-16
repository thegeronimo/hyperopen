import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import assert from "node:assert/strict";
import { PNG } from "pngjs";
import { compareSnapshots } from "../src/parity_compare.mjs";

function writePng(filePath, colorA) {
  const png = new PNG({ width: 4, height: 4 });
  for (let i = 0; i < png.data.length; i += 4) {
    png.data[i] = colorA[0];
    png.data[i + 1] = colorA[1];
    png.data[i + 2] = colorA[2];
    png.data[i + 3] = 255;
  }
  return fs.writeFile(filePath, PNG.sync.write(png));
}

test("compareSnapshots returns semantic and visual summary", async () => {
  const tmp = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-compare-"));
  const leftImage = path.join(tmp, "left.png");
  const rightImage = path.join(tmp, "right.png");
  await writePng(leftImage, [0, 0, 0]);
  await writePng(rightImage, [255, 255, 255]);

  const config = {
    masking: {
      textRules: [{ pattern: "\\d+", flags: "g", replace: "<n>" }]
    },
    compare: {
      nodeDiffLimit: 20,
      visualDiffThreshold: 0.1,
      visualAlpha: 0.1,
      severity: { high: 0.5, medium: 0.2, low: 0.05 }
    }
  };

  const leftSnapshot = {
    screenshotPath: leftImage,
    semantic: {
      nodes: [
        {
          parityId: "header",
          path: "body > header:nth-of-type(1)",
          className: "a b",
          text: "Price 123",
          style: { color: "rgb(0,0,0)" },
          masked: false
        }
      ],
      maskRects: []
    }
  };

  const rightSnapshot = {
    screenshotPath: rightImage,
    semantic: {
      nodes: [
        {
          parityId: "header",
          path: "body > header:nth-of-type(1)",
          className: "a c",
          text: "Price 999",
          style: { color: "rgb(255,255,255)" },
          masked: false
        }
      ],
      maskRects: []
    }
  };

  const result = await compareSnapshots(leftSnapshot, rightSnapshot, {
    config,
    outDir: tmp,
    viewportName: "desktop"
  });

  assert.equal(result.viewport, "desktop");
  assert.ok(result.summary.visualDiffRatio > 0);
  assert.ok(result.findings.length > 0);

  await fs.rm(tmp, { recursive: true, force: true });
});
