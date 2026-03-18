import path from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

export async function resolveRepoRoot(cwd = process.cwd()) {
  const { stdout } = await execFileAsync("git", ["rev-parse", "--show-toplevel"], { cwd });
  return stdout.trim();
}

export function issueRunDir(repoRoot, issueId) {
  return path.join(repoRoot, "tmp", "multi-agent", issueId);
}

export function repoRelative(repoRoot, absolutePath) {
  return path.relative(repoRoot, absolutePath).replaceAll(path.sep, "/");
}

export function toAbsoluteRepoPath(repoRoot, repoPath) {
  return path.resolve(repoRoot, repoPath);
}

export function ensureRepoRelative(inputPath) {
  return inputPath.replaceAll(path.sep, "/").replace(/^\.\/+/, "");
}

export function buildDefaultExecPlanPath(issue) {
  const slug = issue.title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 64);
  const date = String(issue.created_at || "").slice(0, 10) || "2026-03-16";
  return `docs/exec-plans/active/${date}-${slug || issue.id}.md`;
}

export function buildArtifactPaths(repoRoot, issueId) {
  const runDir = issueRunDir(repoRoot, issueId);
  return {
    runDir,
    spec: path.join(runDir, "spec.json"),
    acceptanceProposal: path.join(runDir, "acceptance-tests.proposal.json"),
    edgeProposal: path.join(runDir, "edge-case-tests.proposal.json"),
    approvedContract: path.join(runDir, "approved-test-contract.json"),
    redPhase: path.join(runDir, "red-phase.json"),
    implementation: path.join(runDir, "implementation.json"),
    review: path.join(runDir, "review-report.json"),
    browser: path.join(runDir, "browser-report.json"),
    summary: path.join(runDir, "run-summary.json")
  };
}
