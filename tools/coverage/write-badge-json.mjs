import fs from "node:fs/promises";
import path from "node:path";

const COVERAGE_SUMMARY_PATH =
  process.env.COVERAGE_SUMMARY_PATH ?? "coverage/coverage-summary.json";
const COVERAGE_BADGE_JSON_PATH =
  process.env.COVERAGE_BADGE_PATH ?? ".github/badges/coverage.json";
const COVERAGE_BADGE_SVG_PATH =
  process.env.COVERAGE_BADGE_SVG_PATH ?? ".github/badges/coverage.svg";

const COLOR_HEX_BY_NAME = {
  brightgreen: "#4c1",
  green: "#97ca00",
  yellowgreen: "#a4a61d",
  yellow: "#dfb317",
  orange: "#fe7d37",
  red: "#e05d44",
};

function coverageColor(percent) {
  if (percent >= 90) return "brightgreen";
  if (percent >= 80) return "green";
  if (percent >= 70) return "yellowgreen";
  if (percent >= 60) return "yellow";
  if (percent >= 50) return "orange";
  return "red";
}

function escapeXml(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}

function badgeWidth(text) {
  return Math.max(40, text.length * 7 + 10);
}

function renderSvgBadge({ label, message, color }) {
  const labelWidth = badgeWidth(label);
  const messageWidth = badgeWidth(message);
  const totalWidth = labelWidth + messageWidth;
  const colorHex = COLOR_HEX_BY_NAME[color] ?? COLOR_HEX_BY_NAME.red;
  const safeLabel = escapeXml(label);
  const safeMessage = escapeXml(message);
  const safeAriaLabel = escapeXml(`${label}: ${message}`);

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${totalWidth}" height="20" role="img" aria-label="${safeAriaLabel}">
  <linearGradient id="s" x2="0" y2="100%">
    <stop offset="0" stop-color="#fff" stop-opacity=".7"/>
    <stop offset=".1" stop-color="#aaa" stop-opacity=".1"/>
    <stop offset=".9" stop-opacity=".3"/>
    <stop offset="1" stop-opacity=".5"/>
  </linearGradient>
  <mask id="m">
    <rect width="${totalWidth}" height="20" rx="3" fill="#fff"/>
  </mask>
  <g mask="url(#m)">
    <rect width="${labelWidth}" height="20" fill="#555"/>
    <rect x="${labelWidth}" width="${messageWidth}" height="20" fill="${colorHex}"/>
    <rect width="${totalWidth}" height="20" fill="url(#s)"/>
  </g>
  <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="110">
    <text aria-hidden="true" x="${(labelWidth * 10) / 2}" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)" textLength="${Math.max(1, label.length * 70)}">${safeLabel}</text>
    <text x="${(labelWidth * 10) / 2}" y="140" transform="scale(.1)" fill="#fff" textLength="${Math.max(1, label.length * 70)}">${safeLabel}</text>
    <text aria-hidden="true" x="${((labelWidth + messageWidth / 2) * 10)}" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)" textLength="${Math.max(1, message.length * 70)}">${safeMessage}</text>
    <text x="${((labelWidth + messageWidth / 2) * 10)}" y="140" transform="scale(.1)" fill="#fff" textLength="${Math.max(1, message.length * 70)}">${safeMessage}</text>
  </g>
</svg>
`;
}

async function main() {
  const rawSummary = await fs.readFile(COVERAGE_SUMMARY_PATH, "utf8");
  const summary = JSON.parse(rawSummary);
  const lineCoverage = summary?.total?.lines?.pct;

  if (typeof lineCoverage !== "number" || Number.isNaN(lineCoverage)) {
    throw new Error(
      `Expected numeric total.lines.pct in ${COVERAGE_SUMMARY_PATH}`,
    );
  }

  const badgePayload = {
    schemaVersion: 1,
    label: "coverage",
    message: `${lineCoverage.toFixed(2)}%`,
    color: coverageColor(lineCoverage),
  };

  const badgeSvg = renderSvgBadge(badgePayload);

  await fs.mkdir(path.dirname(COVERAGE_BADGE_JSON_PATH), { recursive: true });
  await fs.mkdir(path.dirname(COVERAGE_BADGE_SVG_PATH), { recursive: true });
  await fs.writeFile(
    COVERAGE_BADGE_JSON_PATH,
    `${JSON.stringify(badgePayload, null, 2)}\n`,
    "utf8",
  );
  await fs.writeFile(COVERAGE_BADGE_SVG_PATH, badgeSvg, "utf8");

  console.log(
    `Wrote coverage badge payload (${badgePayload.message}) to ${COVERAGE_BADGE_JSON_PATH} and ${COVERAGE_BADGE_SVG_PATH}`,
  );
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
