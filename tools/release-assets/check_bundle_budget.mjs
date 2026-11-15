import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const TOOL_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(TOOL_DIR, "..", "..");
const JS_OUTPUT_DIR = path.join(REPO_ROOT, "resources", "public", "js");
const MANIFEST_PATH = path.join(JS_OUTPUT_DIR, "manifest.json");
const BUDGET_PATH = path.join(TOOL_DIR, "bundle-budget.json");

const RELEASE_OUTPUT_NAME_PATTERN = /^main\.[0-9A-F]{8,}\.js$/;

export function resolveMainArtifact(manifestEntries) {
  if (!Array.isArray(manifestEntries)) {
    throw new Error("manifest.json must be an array of module entries");
  }

  const mainEntry = manifestEntries.find((entry) => entry["module-id"] === "main");
  if (!mainEntry) {
    throw new Error("manifest.json has no entry with module-id \"main\"");
  }

  const outputName = mainEntry["output-name"];
  if (typeof outputName !== "string" || outputName.length === 0) {
    throw new Error("main module entry has no output-name");
  }

  if (!RELEASE_OUTPUT_NAME_PATTERN.test(outputName)) {
    throw new Error(
      `main output-name "${outputName}" is not a hashed release artifact; run \`npm run build\` before checking the bundle budget`
    );
  }

  return outputName;
}

export function measureGzipBytes(buffer) {
  return zlib.gzipSync(buffer, { level: 9 }).length;
}

export function checkBudget({ moduleId, actualGzipBytes, budgetGzipBytes }) {
  if (!Number.isFinite(budgetGzipBytes) || budgetGzipBytes <= 0) {
    return {
      ok: false,
      message: `bundle budget for "${moduleId}" is missing or invalid; expected a positive gzipBytes number`,
    };
  }

  if (actualGzipBytes > budgetGzipBytes) {
    return {
      ok: false,
      message:
        `${moduleId} gzip size ${actualGzipBytes} exceeds budget ${budgetGzipBytes} ` +
        `(+${actualGzipBytes - budgetGzipBytes} bytes). Either shrink the bundle or, if the growth is ` +
        `deliberate and reviewed, re-ratchet with \`node tools/release-assets/check_bundle_budget.mjs --update\`.`,
    };
  }

  return {
    ok: true,
    message: `${moduleId} gzip size ${actualGzipBytes} is within budget ${budgetGzipBytes} (headroom ${budgetGzipBytes - actualGzipBytes} bytes)`,
  };
}

export function renderBudgetFile({ gzipBytes, recordedAt, outputName }) {
  return `${JSON.stringify(
    {
      comment:
        "Soft gzip target for the release main module — advisory only; exceeding it warns (and annotates CI) but never fails the build. Tracked by docs/exec-plans/active/2026-06-11-pagespeed-desktop-performance-90.md; lower it as the bundle diets.",
      main: {
        gzipBytes,
        recordedAt,
        recordedOutputName: outputName,
      },
    },
    null,
    2
  )}\n`;
}

function loadBudget() {
  if (!fs.existsSync(BUDGET_PATH)) {
    return null;
  }
  return JSON.parse(fs.readFileSync(BUDGET_PATH, "utf8"));
}

function reportSoftBudget(message) {
  console.warn(`bundle budget (soft): ${message}`);

  if (process.env.GITHUB_ACTIONS === "true") {
    console.log(`::warning title=Bundle budget (soft)::${message}`);
    const summaryPath = process.env.GITHUB_STEP_SUMMARY;
    if (summaryPath) {
      try {
        fs.appendFileSync(summaryPath, `> ⚠️ **Bundle budget (soft):** ${message}\n`);
      } catch {
      }
    }
  }
}

function main() {
  const update = process.argv.includes("--update");

  if (!fs.existsSync(MANIFEST_PATH)) {
    console.error(`bundle budget: ${MANIFEST_PATH} not found; run \`npm run build\` first`);
    process.exit(1);
  }

  const manifest = JSON.parse(fs.readFileSync(MANIFEST_PATH, "utf8"));

  let outputName;
  try {
    outputName = resolveMainArtifact(manifest);
  } catch (error) {
    console.error(`bundle budget: ${error.message}`);
    process.exit(1);
  }

  const artifactPath = path.join(JS_OUTPUT_DIR, outputName);
  if (!fs.existsSync(artifactPath)) {
    console.error(`bundle budget: manifest names ${outputName} but ${artifactPath} does not exist`);
    process.exit(1);
  }

  const actualGzipBytes = measureGzipBytes(fs.readFileSync(artifactPath));

  if (update) {
    fs.writeFileSync(
      BUDGET_PATH,
      renderBudgetFile({
        gzipBytes: actualGzipBytes,
        recordedAt: new Date().toISOString(),
        outputName,
      })
    );
    console.log(`bundle budget: recorded main gzipBytes=${actualGzipBytes} from ${outputName}`);
    return;
  }

  const budget = loadBudget();
  const result = checkBudget({
    moduleId: "main",
    actualGzipBytes,
    budgetGzipBytes: budget?.main?.gzipBytes,
  });

  if (!result.ok) {
    reportSoftBudget(result.message);
    return;
  }

  console.log(`bundle budget: ${result.message}`);
}

const invokedDirectly =
  process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (invokedDirectly) {
  main();
}
