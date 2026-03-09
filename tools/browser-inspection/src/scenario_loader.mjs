import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { readJsonFile } from "./util.mjs";
import { assertScenarioManifest, assertScenarioRouting } from "./scenario_contracts.mjs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const defaultScenarioDir = path.resolve(__dirname, "../scenarios");
const defaultRoutingPath = path.resolve(__dirname, "../config/scenario-routing.json");

function normalizedPath(filePath) {
  return String(filePath || "").replaceAll(path.sep, "/");
}

function globToRegex(glob) {
  const escaped = normalizedPath(glob).replace(/[|\\{}()[\]^$+?.]/g, "\\$&");
  const withDoubleStar = escaped.replaceAll("**", "\u0000");
  const withSingleStar = withDoubleStar.replaceAll("*", "[^/]*");
  return new RegExp(`^${withSingleStar.replaceAll("\u0000", ".*")}$`);
}

function pathMatchesGlob(filePath, glob) {
  return globToRegex(glob).test(normalizedPath(filePath));
}

async function listJsonFiles(rootDir) {
  const entries = await fs.readdir(rootDir, { withFileTypes: true });
  const out = [];
  for (const entry of entries) {
    const fullPath = path.join(rootDir, entry.name);
    if (entry.isDirectory()) {
      out.push(...(await listJsonFiles(fullPath)));
      continue;
    }
    if (entry.isFile() && entry.name.endsWith(".json")) {
      out.push(fullPath);
    }
  }
  return out.sort();
}

export function getDefaultScenarioDir() {
  return defaultScenarioDir;
}

export async function loadScenarioRouting(routingPath = defaultRoutingPath) {
  const routing = await readJsonFile(routingPath, {
    defaultTags: ["critical"],
    fullCriticalGlobs: [],
    tagRules: []
  });
  assertScenarioRouting(routing);
  return routing;
}

export async function loadScenarios(options = {}) {
  const scenarioDir = options.scenarioDir || defaultScenarioDir;
  const ids = new Set((options.ids || []).map((value) => String(value)));
  const tags = new Set((options.tags || []).map((value) => String(value)));
  const files = await listJsonFiles(scenarioDir);
  const manifests = [];

  for (const filePath of files) {
    const manifest = await readJsonFile(filePath);
    manifest.filePath = filePath;
    assertScenarioManifest(manifest);

    const idMatch = ids.size === 0 || ids.has(manifest.id);
    const tagMatch = tags.size === 0 || manifest.tags.some((tag) => tags.has(tag));
    if (idMatch && tagMatch) {
      manifests.push(manifest);
    }
  }

  manifests.sort((left, right) => left.id.localeCompare(right.id));
  return manifests;
}

export function selectScenarioTagsForChangedFiles(changedFiles, routing) {
  const routingConfig = routing || {
    defaultTags: ["critical"],
    fullCriticalGlobs: [],
    tagRules: []
  };
  const files = (changedFiles || []).map(normalizedPath).filter(Boolean);
  const selectedTags = new Set(routingConfig.defaultTags || ["critical"]);
  let fullCritical = files.length === 0;

  for (const filePath of files) {
    if ((routingConfig.fullCriticalGlobs || []).some((glob) => pathMatchesGlob(filePath, glob))) {
      fullCritical = true;
    }
    for (const rule of routingConfig.tagRules || []) {
      if (pathMatchesGlob(filePath, rule.glob)) {
        for (const tag of rule.tags || []) {
          selectedTags.add(tag);
        }
      }
    }
  }

  if (fullCritical) {
    selectedTags.add("critical");
  }

  return {
    changedFiles: files,
    fullCritical,
    tags: [...selectedTags].sort()
  };
}
