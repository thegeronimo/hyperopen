import fs from "node:fs/promises";
import path from "node:path";

const MAIN_TEST_LOG_PATH =
  process.env.MAIN_TEST_LOG_PATH ?? ".github/badges/test-main.log";
const WEBSOCKET_TEST_LOG_PATH =
  process.env.WEBSOCKET_TEST_LOG_PATH ?? ".github/badges/test-websocket.log";
const TESTS_BADGE_JSON_PATH =
  process.env.TESTS_BADGE_JSON_PATH ?? ".github/badges/tests-total.json";
const TESTS_BADGE_SVG_PATH =
  process.env.TESTS_BADGE_SVG_PATH ?? ".github/badges/tests-total.svg";
const ASSERTIONS_BADGE_JSON_PATH =
  process.env.ASSERTIONS_BADGE_JSON_PATH ?? ".github/badges/assertions-total.json";
const ASSERTIONS_BADGE_SVG_PATH =
  process.env.ASSERTIONS_BADGE_SVG_PATH ?? ".github/badges/assertions-total.svg";

const COLOR_HEX_BY_NAME = {
  blue: "#007ec6",
  red: "#e05d44",
};

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

function parseRunSummary(logText, sourcePath) {
  const runSummaryPattern = /Ran (\d+) tests containing (\d+) assertions\./g;
  let match = runSummaryPattern.exec(logText);
  let lastMatch = null;

  while (match) {
    lastMatch = match;
    match = runSummaryPattern.exec(logText);
  }

  if (!lastMatch) {
    throw new Error(
      `Expected test summary line in ${sourcePath}, but none was found.`,
    );
  }

  return {
    tests: Number.parseInt(lastMatch[1], 10),
    assertions: Number.parseInt(lastMatch[2], 10),
  };
}

async function writeBadge(jsonPath, svgPath, payload) {
  await fs.mkdir(path.dirname(jsonPath), { recursive: true });
  await fs.mkdir(path.dirname(svgPath), { recursive: true });
  await fs.writeFile(jsonPath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
  await fs.writeFile(svgPath, renderSvgBadge(payload), "utf8");
}

async function main() {
  const [mainLogText, websocketLogText] = await Promise.all([
    fs.readFile(MAIN_TEST_LOG_PATH, "utf8").catch((error) => {
      if (error?.code === "ENOENT") {
        throw new Error(
          `Missing ${MAIN_TEST_LOG_PATH}. Run npm run test:ci and capture output to that file before generating test metric badges.`,
        );
      }
      throw error;
    }),
    fs.readFile(WEBSOCKET_TEST_LOG_PATH, "utf8").catch((error) => {
      if (error?.code === "ENOENT") {
        throw new Error(
          `Missing ${WEBSOCKET_TEST_LOG_PATH}. Run npm run test:websocket and capture output to that file before generating test metric badges.`,
        );
      }
      throw error;
    }),
  ]);

  const mainSummary = parseRunSummary(mainLogText, MAIN_TEST_LOG_PATH);
  const websocketSummary = parseRunSummary(
    websocketLogText,
    WEBSOCKET_TEST_LOG_PATH,
  );
  const totalTests = mainSummary.tests + websocketSummary.tests;
  const totalAssertions =
    mainSummary.assertions + websocketSummary.assertions;

  const testsBadgePayload = {
    schemaVersion: 1,
    label: "tests",
    message: totalTests.toString(),
    color: "blue",
  };
  const assertionsBadgePayload = {
    schemaVersion: 1,
    label: "assertions",
    message: totalAssertions.toString(),
    color: "blue",
  };

  await Promise.all([
    writeBadge(TESTS_BADGE_JSON_PATH, TESTS_BADGE_SVG_PATH, testsBadgePayload),
    writeBadge(
      ASSERTIONS_BADGE_JSON_PATH,
      ASSERTIONS_BADGE_SVG_PATH,
      assertionsBadgePayload,
    ),
  ]);

  console.log(
    `Wrote test metric badges (tests=${totalTests}, assertions=${totalAssertions}).`,
  );
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
