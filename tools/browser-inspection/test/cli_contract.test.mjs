import { execFile } from "node:child_process";
import { promisify } from "node:util";
import path from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

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
