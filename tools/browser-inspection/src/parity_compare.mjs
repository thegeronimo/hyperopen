import fs from "node:fs/promises";
import path from "node:path";
import pixelmatch from "pixelmatch";
import { PNG } from "pngjs";
import { assertCompareResult } from "./contracts.mjs";
import {
  compileTextRules,
  mergeMaskRects,
  nodeMasked,
  normalizeClassList,
  normalizeText,
  semanticKey
} from "./masking.mjs";

async function readPng(filePath) {
  const buf = await fs.readFile(filePath);
  return PNG.sync.read(buf);
}

async function writePng(filePath, png) {
  const encoded = PNG.sync.write(png);
  await fs.writeFile(filePath, encoded);
}

function padToDimensions(src, width, height) {
  if (src.width === width && src.height === height) {
    return src;
  }
  const out = new PNG({ width, height, fill: true });
  PNG.bitblt(src, out, 0, 0, src.width, src.height, 0, 0);
  return out;
}

function applyRectsMask(png, rects) {
  for (const rect of rects) {
    const startX = rect.x;
    const endX = rect.x + rect.width;
    const startY = rect.y;
    const endY = rect.y + rect.height;
    for (let y = startY; y < endY; y += 1) {
      for (let x = startX; x < endX; x += 1) {
        if (x < 0 || y < 0 || x >= png.width || y >= png.height) {
          continue;
        }
        const idx = (png.width * y + x) << 2;
        png.data[idx] = 0;
        png.data[idx + 1] = 0;
        png.data[idx + 2] = 0;
        png.data[idx + 3] = 255;
      }
    }
  }
}

function severityForRatio(ratio, thresholds) {
  if (ratio >= thresholds.high) {
    return "high";
  }
  if (ratio >= thresholds.medium) {
    return "medium";
  }
  if (ratio >= thresholds.low) {
    return "low";
  }
  return "none";
}

function toNodeMap(nodes) {
  const map = new Map();
  for (const node of nodes) {
    const key = semanticKey(node);
    if (!map.has(key)) {
      map.set(key, node);
    }
  }
  return map;
}

function styleDelta(left = {}, right = {}) {
  const out = [];
  const keys = new Set([...Object.keys(left), ...Object.keys(right)]);
  for (const key of keys) {
    if ((left[key] || null) !== (right[key] || null)) {
      out.push({ key, left: left[key] || null, right: right[key] || null });
    }
  }
  return out;
}

function semanticDiff(leftSnapshot, rightSnapshot, config) {
  const rules = compileTextRules(config.masking.textRules);
  const leftNodes = (leftSnapshot.semantic.nodes || []).filter((node) => !nodeMasked(node));
  const rightNodes = (rightSnapshot.semantic.nodes || []).filter((node) => !nodeMasked(node));

  const leftMap = toNodeMap(leftNodes);
  const rightMap = toNodeMap(rightNodes);

  const findings = [];

  for (const key of leftMap.keys()) {
    if (!rightMap.has(key)) {
      findings.push({ category: "missing", severity: "medium", key, detail: "Node missing on right target" });
    }
  }

  for (const key of rightMap.keys()) {
    if (!leftMap.has(key)) {
      findings.push({ category: "extra", severity: "medium", key, detail: "Node extra on right target" });
    }
  }

  for (const key of leftMap.keys()) {
    if (!rightMap.has(key)) {
      continue;
    }

    const left = leftMap.get(key);
    const right = rightMap.get(key);

    const leftText = normalizeText(left.text || "", rules);
    const rightText = normalizeText(right.text || "", rules);
    if (leftText !== rightText) {
      findings.push({
        category: "text",
        severity: "low",
        key,
        detail: "Normalized text differs",
        left: leftText,
        right: rightText
      });
    }

    const leftClasses = normalizeClassList(left.className).join(" ");
    const rightClasses = normalizeClassList(right.className).join(" ");
    if (leftClasses !== rightClasses) {
      findings.push({
        category: "style",
        severity: "low",
        key,
        detail: "Class list differs",
        left: leftClasses,
        right: rightClasses
      });
    }

    const styleChanges = styleDelta(left.style || {}, right.style || {});
    if (styleChanges.length > 0) {
      findings.push({
        category: "style",
        severity: styleChanges.length > 3 ? "medium" : "low",
        key,
        detail: "Computed style subset differs",
        changes: styleChanges
      });
    }

    if (findings.length >= config.compare.nodeDiffLimit) {
      break;
    }
  }

  return findings;
}

async function visualDiff(leftSnapshot, rightSnapshot, outDir, config, viewportName) {
  const leftPngRaw = await readPng(leftSnapshot.screenshotPath);
  const rightPngRaw = await readPng(rightSnapshot.screenshotPath);
  const width = Math.max(leftPngRaw.width, rightPngRaw.width);
  const height = Math.max(leftPngRaw.height, rightPngRaw.height);

  const leftPng = padToDimensions(leftPngRaw, width, height);
  const rightPng = padToDimensions(rightPngRaw, width, height);
  const diffPng = new PNG({ width, height });

  const maskRects = mergeMaskRects(
    leftSnapshot.semantic.maskRects || [],
    rightSnapshot.semantic.maskRects || [],
    width,
    height
  );

  applyRectsMask(leftPng, maskRects);
  applyRectsMask(rightPng, maskRects);

  const diffPixels = pixelmatch(leftPng.data, rightPng.data, diffPng.data, width, height, {
    threshold: config.compare.visualDiffThreshold,
    alpha: config.compare.visualAlpha
  });

  const ratio = diffPixels / (width * height);
  const severity = severityForRatio(ratio, config.compare.severity);

  const diffImagePath = path.join(outDir, `${viewportName}-visual-diff.png`);
  await writePng(diffImagePath, diffPng);

  return {
    ratio,
    diffPixels,
    width,
    height,
    severity,
    diffImagePath,
    maskRectCount: maskRects.length
  };
}

export async function compareSnapshots(leftSnapshot, rightSnapshot, options) {
  const { config, outDir, viewportName = "desktop" } = options;

  const semanticFindings = semanticDiff(leftSnapshot, rightSnapshot, config);
  const visual = await visualDiff(leftSnapshot, rightSnapshot, outDir, config, viewportName);

  const findings = [...semanticFindings];
  if (visual.severity !== "none") {
    findings.push({
      category: "visual",
      severity: visual.severity,
      key: viewportName,
      detail: `Visual diff ratio ${visual.ratio.toFixed(4)} (${visual.diffPixels} pixels)`
    });
  }

  const result = {
    viewport: viewportName,
    summary: {
      semanticFindingCount: semanticFindings.length,
      visualDiffRatio: visual.ratio,
      visualDiffPixels: visual.diffPixels,
      visualSeverity: visual.severity,
      maskRectCount: visual.maskRectCount
    },
    findings,
    outputs: {
      visualDiffImagePath: visual.diffImagePath
    }
  };

  assertCompareResult(result);
  return result;
}

export function renderCompareMarkdown(result, labels) {
  const lines = [];
  lines.push(`# Parity Report (${result.viewport})`);
  lines.push("");
  lines.push(`- Left: ${labels.left}`);
  lines.push(`- Right: ${labels.right}`);
  lines.push(`- Semantic findings: ${result.summary.semanticFindingCount}`);
  lines.push(`- Visual diff ratio: ${result.summary.visualDiffRatio.toFixed(6)}`);
  lines.push(`- Visual severity: ${result.summary.visualSeverity}`);
  lines.push("");
  lines.push("## Findings");

  if (!result.findings.length) {
    lines.push("No parity findings.");
  } else {
    for (const finding of result.findings) {
      lines.push(`- [${finding.severity}] (${finding.category}) ${finding.key}: ${finding.detail}`);
    }
  }

  lines.push("");
  lines.push(`Diff image: ${result.outputs.visualDiffImagePath}`);
  return `${lines.join("\n")}\n`;
}
