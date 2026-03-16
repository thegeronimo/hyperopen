#!/usr/bin/env node
import { buildDryRunArtifacts, loadIssueForRun, runDryRun, runTicket } from "./manager.mjs";
import { resolveRepoRoot } from "./paths.mjs";

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith("--")) {
      out._.push(token);
      continue;
    }
    const stripped = token.slice(2);
    if (stripped.includes("=")) {
      const [key, ...rest] = stripped.split("=");
      out[key] = rest.join("=");
      continue;
    }
    const next = argv[i + 1];
    if (!next || next.startsWith("--")) {
      out[stripped] = true;
      continue;
    }
    out[stripped] = next;
    i += 1;
  }
  return out;
}

function requireIssueId(args) {
  const issueId = args.issue;
  if (!issueId || typeof issueId !== "string") {
    throw new Error("Missing required --issue <bd-id> argument.");
  }
  return issueId;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const [command = "dry-run"] = args._;
  const repoRoot = await resolveRepoRoot(process.cwd());

  if (command === "dry-run" && args["sample-title"]) {
    const issue = {
      id: args.issue || "sample-ticket",
      title: String(args["sample-title"]),
      description: String(args["sample-description"] || ""),
      created_at: new Date().toISOString()
    };
    const artifacts = buildDryRunArtifacts({ issue, repoRoot });
    const result = await runDryRun({ repoRoot, issue });
    process.stdout.write(
      `${JSON.stringify({ ...result, sample: true, artifactDir: artifacts.artifactPaths.runDir }, null, 2)}\n`
    );
    return;
  }

  const issueId = requireIssueId(args);
  const issue = await loadIssueForRun({ repoRoot, issueId });

  if (command === "dry-run") {
    const result = await runDryRun({ repoRoot, issue });
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    return;
  }

  if (command === "ticket") {
    const result = await runTicket({ repoRoot, issue, resume: false });
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    return;
  }

  if (command === "resume-ticket") {
    const result = await runTicket({ repoRoot, issue, resume: true });
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    return;
  }

  throw new Error(`Unknown multi-agent command: ${command}`);
}

main().catch((error) => {
  process.stderr.write(`${error?.stack || error?.message || error}\n`);
  process.exitCode = 1;
});
