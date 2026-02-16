import path from "node:path";
import { fileURLToPath } from "node:url";
import { assertConfig } from "./contracts.mjs";
import { deepMerge, readJsonFile } from "./util.mjs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, "../../..");
const defaultsPath = path.resolve(__dirname, "../config/defaults.json");

function resolvePathMaybe(baseDir, rawPath) {
  if (!rawPath || typeof rawPath !== "string") {
    return rawPath;
  }
  if (path.isAbsolute(rawPath)) {
    return rawPath;
  }
  return path.resolve(baseDir, rawPath);
}

export function getRepoRoot() {
  return repoRoot;
}

export async function loadConfig(options = {}) {
  const defaults = await readJsonFile(defaultsPath);
  const envPath = process.env.BROWSER_INSPECTION_CONFIG;
  const fileOverridePath = options.configPath || envPath;
  const fileOverride = fileOverridePath ? await readJsonFile(fileOverridePath, {}) : {};
  const merged = deepMerge(deepMerge(defaults, fileOverride), options.override || {});

  const chromePathEnv = merged.chrome?.pathEnvVar
    ? process.env[merged.chrome.pathEnvVar]
    : null;
  if (chromePathEnv) {
    merged.chrome.path = chromePathEnv;
  }

  merged.artifactRoot = resolvePathMaybe(repoRoot, merged.artifactRoot);
  merged.localApp.cwd = resolvePathMaybe(repoRoot, merged.localApp.cwd);

  for (const target of Object.values(merged.targets || {})) {
    if (target.cwd) {
      target.cwd = resolvePathMaybe(repoRoot, target.cwd);
    }
  }

  assertConfig(merged);
  return merged;
}
