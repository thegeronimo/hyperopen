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
const TESTS_STATUS_BADGE_JSON_PATH =
  process.env.TESTS_STATUS_BADGE_JSON_PATH ?? ".github/badges/tests-status.json";
const TESTS_STATUS_BADGE_SVG_PATH =
  process.env.TESTS_STATUS_BADGE_SVG_PATH ?? ".github/badges/tests-status.svg";
const TESTS_STATUS_BADGE_MESSAGE = process.env.TESTS_STATUS_BADGE_MESSAGE;
const TESTS_STATUS_BADGE_COLOR = process.env.TESTS_STATUS_BADGE_COLOR;
const ASSERTIONS_BADGE_JSON_PATH =
  process.env.ASSERTIONS_BADGE_JSON_PATH ?? ".github/badges/assertions-total.json";
const ASSERTIONS_BADGE_SVG_PATH =
  process.env.ASSERTIONS_BADGE_SVG_PATH ?? ".github/badges/assertions-total.svg";

const COLOR_HEX_BY_NAME = {
  brightgreen: "#4c1",
  blue: "#007ec6",
  red: "#e05d44",
};

function formatCount(value) {
  return value.toLocaleString("en-US");
}

function escapeXml(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}

function badgeWidth(text, { minWidth, padding }) {
  return Math.max(minWidth, text.length * 10 + padding);
}

function renderSvgBadge({ label, message, color }) {
  const displayLabel = label.toUpperCase();
  const displayMessage = message.toUpperCase();
  const labelWidth = badgeWidth(displayLabel, { minWidth: 62, padding: 9 });
  const messageWidth = badgeWidth(displayMessage, {
    minWidth: 57,
    padding: 16,
  });
  const totalWidth = labelWidth + messageWidth;
  const colorHex = COLOR_HEX_BY_NAME[color] ?? COLOR_HEX_BY_NAME.red;
  const safeLabel = escapeXml(displayLabel);
  const safeMessage = escapeXml(displayMessage);
  const safeAriaLabel = escapeXml(`${displayLabel}: ${displayMessage}`);

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${totalWidth}" height="28" role="img" aria-label="${safeAriaLabel}">
  <title>${safeAriaLabel}</title>
  <g shape-rendering="crispEdges">
    <rect width="${labelWidth}" height="28" fill="#555"/>
    <rect x="${labelWidth}" width="${messageWidth}" height="28" fill="${colorHex}"/>
  </g>
  <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="100">
    <text transform="scale(.1)" x="${labelWidth * 5}" y="175">${safeLabel}</text>
    <text transform="scale(.1)" x="${(labelWidth + messageWidth / 2) * 10}" y="175" font-weight="bold">${safeMessage}</text>
  </g>
</svg>
`;
}

async function readBadgePayloadFromJson(jsonPath) {
  const rawPayload = await fs.readFile(jsonPath, "utf8");
  const payload = JSON.parse(rawPayload);

  if (
    typeof payload?.label !== "string" ||
    typeof payload?.message !== "string" ||
    typeof payload?.color !== "string"
  ) {
    throw new Error(`Expected label/message/color fields in ${jsonPath}`);
  }

  return {
    ...payload,
    message: /^\d+$/.test(payload.message)
      ? formatCount(Number.parseInt(payload.message, 10))
      : payload.message,
  };
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

async function buildBadgePayloads() {
  try {
    const [mainLogText, websocketLogText] = await Promise.all([
      fs.readFile(MAIN_TEST_LOG_PATH, "utf8"),
      fs.readFile(WEBSOCKET_TEST_LOG_PATH, "utf8"),
    ]);

    const mainSummary = parseRunSummary(mainLogText, MAIN_TEST_LOG_PATH);
    const websocketSummary = parseRunSummary(
      websocketLogText,
      WEBSOCKET_TEST_LOG_PATH,
    );
    const totalTests = mainSummary.tests + websocketSummary.tests;
    const totalAssertions =
      mainSummary.assertions + websocketSummary.assertions;

    return {
      testsStatusBadgePayload: {
        schemaVersion: 1,
        label: "tests",
        message: "passing",
        color: "brightgreen",
      },
      testsBadgePayload: {
        schemaVersion: 1,
        label: "tests",
        message: formatCount(totalTests),
        color: "blue",
      },
      assertionsBadgePayload: {
        schemaVersion: 1,
        label: "assertions",
        message: formatCount(totalAssertions),
        color: "blue",
      },
    };
  } catch (error) {
    if (error?.code !== "ENOENT") {
      throw error;
    }

    const [
      testsStatusBadgePayload,
      testsBadgePayload,
      assertionsBadgePayload,
    ] = await Promise.all([
      readBadgePayloadFromJson(TESTS_STATUS_BADGE_JSON_PATH),
      readBadgePayloadFromJson(TESTS_BADGE_JSON_PATH),
      readBadgePayloadFromJson(ASSERTIONS_BADGE_JSON_PATH),
    ]);

    return {
      testsStatusBadgePayload,
      testsBadgePayload,
      assertionsBadgePayload,
    };
  }
}

function applyStatusOverride(payload) {
  if (
    typeof TESTS_STATUS_BADGE_MESSAGE !== "string" ||
    TESTS_STATUS_BADGE_MESSAGE.length === 0
  ) {
    return payload;
  }

  return {
    ...payload,
    message: TESTS_STATUS_BADGE_MESSAGE,
    color:
      typeof TESTS_STATUS_BADGE_COLOR === "string" &&
      TESTS_STATUS_BADGE_COLOR.length > 0
        ? TESTS_STATUS_BADGE_COLOR
        : payload.color,
  };
}

async function main() {
  const {
    testsStatusBadgePayload,
    testsBadgePayload,
    assertionsBadgePayload,
  } = await buildBadgePayloads();
  const nextTestsStatusBadgePayload = applyStatusOverride(
    testsStatusBadgePayload,
  );

  await Promise.all([
    writeBadge(
      TESTS_STATUS_BADGE_JSON_PATH,
      TESTS_STATUS_BADGE_SVG_PATH,
      nextTestsStatusBadgePayload,
    ),
    writeBadge(TESTS_BADGE_JSON_PATH, TESTS_BADGE_SVG_PATH, testsBadgePayload),
    writeBadge(
      ASSERTIONS_BADGE_JSON_PATH,
      ASSERTIONS_BADGE_SVG_PATH,
      assertionsBadgePayload,
    ),
  ]);

  console.log(
    `Wrote test metric badges (status=${nextTestsStatusBadgePayload.message}, tests=${testsBadgePayload.message}, assertions=${assertionsBadgePayload.message}).`,
  );
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
