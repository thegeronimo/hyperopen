import { execFile } from "node:child_process";
import { promisify } from "node:util";
import path from "node:path";
import test from "node:test";
import assert from "node:assert/strict";
import { DESIGN_REVIEW_PASS_NAMES } from "../src/design_review/pass_registry.mjs";

const execFileAsync = promisify(execFile);
const cliPath = path.resolve("tools/browser-inspection/src/cli.mjs");

test("cli session list returns JSON", async () => {
  const { stdout } = await execFileAsync(process.execPath, [cliPath, "session", "list"]);
  const parsed = JSON.parse(stdout);
  assert.ok(Array.isArray(parsed.sessions));
});

test("cli inspect without url exits non-zero", async () => {
  await assert.rejects(
    async () => {
      await execFileAsync(process.execPath, [cliPath, "inspect"]);
    },
    (error) => {
      assert.notEqual(error.code, 0);
      return true;
    }
  );
});

test("cli session attach without attach-port exits non-zero", async () => {
  await assert.rejects(
    async () => {
      await execFileAsync(process.execPath, [cliPath, "session", "attach"]);
    },
    (error) => {
      assert.notEqual(error.code, 0);
      return true;
    }
  );
});

test("cli session targets without selector exits non-zero", async () => {
  await assert.rejects(
    async () => {
      await execFileAsync(process.execPath, [cliPath, "session", "targets"]);
    },
    (error) => {
      assert.notEqual(error.code, 0);
      return true;
    }
  );
});

test("cli preflight returns structured JSON", async () => {
  const { stdout } = await execFileAsync(process.execPath, [cliPath, "preflight"]);
  const parsed = JSON.parse(stdout);
  assert.ok(typeof parsed.ok === "boolean");
  assert.ok(Array.isArray(parsed.checks));
  assert.ok(typeof parsed.mode === "string");
});

test("cli scenario list returns scenario manifests", async () => {
  const { stdout } = await execFileAsync(process.execPath, [cliPath, "scenario", "list", "--tags", "critical"]);
  const parsed = JSON.parse(stdout);
  assert.ok(Array.isArray(parsed.scenarios));
  assert.ok(parsed.scenarios.some((scenario) => scenario.id === "trade-route-smoke"));
});

test("cli scenario run dry-run returns selected scenarios", async () => {
  const { stdout } = await execFileAsync(process.execPath, [
    cliPath,
    "scenario",
    "run",
    "--tags",
    "wallet",
    "--dry-run"
  ]);
  const parsed = JSON.parse(stdout);
  assert.equal(parsed.dryRun, true);
  assert.ok(Array.isArray(parsed.selected));
  assert.ok(parsed.selected.some((scenario) => scenario.id === "wallet-enable-trading-simulated"));
});

test("cli design-review dry-run returns review targets and pass matrix", async () => {
  const { stdout } = await execFileAsync(process.execPath, [
    cliPath,
    "design-review",
    "--dry-run",
    "--targets",
    "trade-route"
  ]);
  const parsed = JSON.parse(stdout);
  assert.equal(parsed.dryRun, true);
  assert.deepEqual(parsed.passes, DESIGN_REVIEW_PASS_NAMES);
  assert.deepEqual(parsed.selection.targets.map((target) => target.id), ["trade-route"]);
});
