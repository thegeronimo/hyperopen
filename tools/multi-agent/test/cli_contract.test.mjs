import { execFile } from "node:child_process";
import path from "node:path";
import test from "node:test";
import assert from "node:assert/strict";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const cliPath = path.resolve("tools/multi-agent/src/cli.mjs");

test("cli dry-run with sample issue returns structured JSON", async () => {
  const { stdout } = await execFileAsync(process.execPath, [
    cliPath,
    "dry-run",
    "--sample-title",
    "Sample multi-agent issue",
    "--sample-description",
    "Synthetic issue for CLI contract coverage."
  ]);
  const parsed = JSON.parse(stdout);
  assert.equal(parsed.dryRun, true);
  assert.equal(parsed.sample, true);
  assert.ok(parsed.artifactDir.includes("tmp/multi-agent/sample-ticket"));
});

test("cli dry-run without issue or sample input exits non-zero", async () => {
  await assert.rejects(
    async () => {
      await execFileAsync(process.execPath, [cliPath, "dry-run"]);
    },
    (error) => {
      assert.notEqual(error.code, 0);
      return true;
    }
  );
});
