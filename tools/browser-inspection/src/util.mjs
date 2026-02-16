import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export async function ensureDir(dirPath) {
  await fs.mkdir(dirPath, { recursive: true });
  return dirPath;
}

export async function readJsonFile(filePath, fallback = null) {
  try {
    const raw = await fs.readFile(filePath, "utf8");
    return JSON.parse(raw);
  } catch (error) {
    if (error?.code === "ENOENT") {
      return fallback;
    }
    throw error;
  }
}

export async function writeJsonFile(filePath, value) {
  const dir = path.dirname(filePath);
  await ensureDir(dir);
  const tmpPath = `${filePath}.tmp-${crypto.randomUUID()}`;
  await fs.writeFile(tmpPath, JSON.stringify(value, null, 2));
  await fs.rename(tmpPath, filePath);
}

export function deepMerge(base, override) {
  if (!override || typeof override !== "object") {
    return base;
  }
  if (!base || typeof base !== "object") {
    return override;
  }
  if (Array.isArray(base) || Array.isArray(override)) {
    return override;
  }

  const merged = { ...base };
  for (const [key, value] of Object.entries(override)) {
    if (
      value &&
      typeof value === "object" &&
      !Array.isArray(value) &&
      base[key] &&
      typeof base[key] === "object" &&
      !Array.isArray(base[key])
    ) {
      merged[key] = deepMerge(base[key], value);
    } else {
      merged[key] = value;
    }
  }
  return merged;
}

export function makeRunId(prefix = "run") {
  const ts = new Date().toISOString().replace(/[.:]/g, "-");
  return `${prefix}-${ts}-${crypto.randomBytes(4).toString("hex")}`;
}

export function safeNowIso() {
  return new Date().toISOString();
}

export function clamp(n, min, max) {
  return Math.max(min, Math.min(max, n));
}

export function chunk(list, size) {
  const out = [];
  for (let i = 0; i < list.length; i += size) {
    out.push(list.slice(i, i + size));
  }
  return out;
}
