#!/usr/bin/env node
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { BrowserInspectionService } from "./service.mjs";
import { parseCsvArg, resolvePrSelection, runScenarioBundle } from "./scenario_runner.mjs";

const execFileAsync = promisify(execFile);

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

async function changedFilesFromGit(baseRef) {
  const { stdout } = await execFileAsync("git", ["diff", "--name-only", baseRef, "--"]);
  return stdout
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const changedFiles = args["changed-files"]
    ? parseCsvArg(args["changed-files"])
    : args["base-ref"]
      ? await changedFilesFromGit(String(args["base-ref"]))
      : [];
  const selection = await resolvePrSelection({ changedFiles });

  if (args["dry-run"]) {
    const service = await BrowserInspectionService.create();
    const result = await runScenarioBundle(service, {
      tags: selection.tags,
      dryRun: true
    });
    process.stdout.write(`${JSON.stringify({ selection, ...result }, null, 2)}\n`);
    return;
  }

  const service = await BrowserInspectionService.create();
  const result = await runScenarioBundle(service, {
    tags: selection.tags,
    runKind: "pr-ui",
    headless: !Boolean(args.headed),
    includeCompare: Boolean(args["include-compare"]),
    attachPort: args["attach-port"] || null,
    attachHost: args["attach-host"] || null,
    targetId: args["target-id"] || null,
    localUrl: args["local-url"] || null
  });

  process.stdout.write(`${JSON.stringify({ selection, ...result }, null, 2)}\n`);
  if (result.state !== "pass") {
    process.exitCode = result.state === "manual-exception" ? 3 : 2;
  }
}

main().catch((error) => {
  process.stderr.write(`${error?.stack || error?.message || error}\n`);
  process.exitCode = 1;
});
