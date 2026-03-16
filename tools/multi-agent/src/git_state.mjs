import fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

function normalizeRepoPath(repoPath) {
  return repoPath.replaceAll(path.sep, "/");
}

async function parseStatusLine(line) {
  const status = line.slice(0, 2);
  const raw = line.slice(3).trim();
  if (!raw.includes(" -> ")) {
    return [{ status, path: raw }];
  }
  const [from, to] = raw.split(" -> ");
  return [
    { status: "D ", path: from },
    { status: status.trim() || "A ", path: to }
  ];
}

async function hashFile(filePath, status) {
  if (status.startsWith("D")) {
    return `deleted:${filePath}`;
  }
  const data = await fs.readFile(filePath);
  return crypto.createHash("sha256").update(data).digest("hex");
}

export async function assertCleanWorktree(repoRoot) {
  const snapshot = await captureWorktreeSnapshot(repoRoot);
  if (snapshot.size > 0) {
    const changed = [...snapshot.keys()].sort().join(", ");
    throw new Error(`multi-agent runs require a clean worktree; found: ${changed}`);
  }
}

export async function captureWorktreeSnapshot(repoRoot) {
  const { stdout } = await execFileAsync("git", ["status", "--porcelain=v1", "-uall"], {
    cwd: repoRoot
  });
  const lines = stdout
    .split("\n")
    .map((line) => line.trimEnd())
    .filter(Boolean);
  const entries = new Map();
  for (const line of lines) {
    const parsed = await parseStatusLine(line);
    for (const entry of parsed) {
      const repoPath = normalizeRepoPath(entry.path);
      const absolutePath = path.join(repoRoot, repoPath);
      const digest = await hashFile(absolutePath, entry.status);
      entries.set(repoPath, { status: entry.status, digest });
    }
  }
  return entries;
}

export function diffSnapshots(beforeSnapshot, afterSnapshot) {
  const changed = new Set();
  const allPaths = new Set([...beforeSnapshot.keys(), ...afterSnapshot.keys()]);
  for (const repoPath of allPaths) {
    const before = beforeSnapshot.get(repoPath);
    const after = afterSnapshot.get(repoPath);
    if (!before || !after) {
      changed.add(repoPath);
      continue;
    }
    if (before.status !== after.status || before.digest !== after.digest) {
      changed.add(repoPath);
    }
  }
  return [...changed].sort();
}

function matchesRule(repoPath, rule) {
  if (rule.endsWith("/**")) {
    const prefix = rule.slice(0, -3);
    return repoPath === prefix || repoPath.startsWith(`${prefix}/`);
  }
  return repoPath === rule;
}

export function enforcePathGate(changedPaths, allowedRules) {
  const allowed = allowedRules.map(normalizeRepoPath);
  const disallowed = changedPaths.filter(
    (repoPath) => !allowed.some((rule) => matchesRule(repoPath, rule))
  );
  if (disallowed.length > 0) {
    throw new Error(
      `Phase touched disallowed paths: ${disallowed.join(", ")}. Allowed: ${allowed.join(", ")}`
    );
  }
}
