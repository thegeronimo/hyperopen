import fs from "node:fs/promises";
import path from "node:path";
import { createHash, randomUUID } from "node:crypto";

// Root-level files are deliberately outside ArtifactStore's run-directory retention.
export function baselinePath(artifactRoot) {
  return path.join(artifactRoot, "nightly-baseline.json");
}

export function findingFingerprint(result) {
  return createHash("sha256").update(JSON.stringify([
    result.scenarioId, result.viewport, result.route, result.state,
    result.severity || "high", result.message || ""
  ])).digest("hex");
}

export function compactNightlySummary(summary) {
  return {
    runId: summary.runId,
    runDir: summary.runDir,
    state: summary.state,
    results: (summary.results || []).map(({ scenarioId, viewport, route, state, severity, message, url }) =>
      ({ scenarioId, viewport, route: route || "", state, severity: severity || "high", message: message || "", url: url || "" }))
  };
}

function validSummary(value) {
  const states = ["pass", "product-regression", "automation-gap", "manual-exception"];
  return value && typeof value.runId === "string" && value.runId.startsWith("nightly-ui-qa-") &&
    typeof value.runDir === "string" && value.runDir.length > 0 &&
    states.includes(value.state) && Array.isArray(value.results) &&
    value.results.every((result) => result && typeof result.scenarioId === "string" && result.scenarioId.length > 0 &&
      typeof result.viewport === "string" && result.viewport.length > 0 && states.includes(result.state) &&
      ["route", "severity", "message", "url"].every((key) => result[key] == null || typeof result[key] === "string"));
}

async function readSummary(file) {
  try {
    const value = JSON.parse(await fs.readFile(file, "utf8"));
    return validSummary(value) ? value : null;
  } catch (error) {
    if (error.code === "ENOENT" || error instanceof SyntaxError) return null;
    throw error;
  }
}

export async function saveNightlyBaseline(artifactRoot, summary) {
  if (!validSummary(summary)) throw new Error("Invalid nightly comparison summary");
  await fs.mkdir(artifactRoot, { recursive: true });
  const file = baselinePath(artifactRoot);
  const temporary = `${file}.${randomUUID()}.tmp`;
  try {
    await fs.writeFile(temporary, `${JSON.stringify(compactNightlySummary(summary), null, 2)}\n`);
    await fs.rename(temporary, file);
  } finally {
    await fs.rm(temporary, { force: true });
  }
}

// Called before BrowserInspectionService.create(), which prunes old runs.
export async function loadNightlyBaseline(artifactRoot, { withSource = false } = {}) {
  const result = (summary, source, sourcePath) => withSource ? { summary, source, sourcePath } : summary;
  const saved = await readSummary(baselinePath(artifactRoot));
  const entries = await fs.readdir(artifactRoot, { withFileTypes: true }).catch((error) => {
    if (error.code === "ENOENT") return [];
    throw error;
  });
  const names = entries.filter((entry) => entry.isDirectory() && entry.name.startsWith("nightly-ui-qa-"))
    .map((entry) => entry.name).sort().reverse();
  for (const name of names) {
    if (saved?.runId && name <= saved.runId) break;
    const summary = await readSummary(path.join(artifactRoot, name, "summary.json"));
    if (summary) {
      const compact = compactNightlySummary({ ...summary, runId: name, runDir: path.join(artifactRoot, name) });
      await saveNightlyBaseline(artifactRoot, compact);
      return result(compact, "retained-run", path.join(artifactRoot, name, "summary.json"));
    }
  }
  return result(saved, saved ? "durable-baseline" : "none", saved ? baselinePath(artifactRoot) : null);
}
