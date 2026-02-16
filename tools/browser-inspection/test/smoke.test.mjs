import { execFile } from "node:child_process";
import { promisify } from "node:util";
import path from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

const execFileAsync = promisify(execFile);
const cliPath = path.resolve("tools/browser-inspection/src/cli.mjs");
const enabled = process.env.RUN_BROWSER_INSPECTION_SMOKE === "1";

test(
  "browser inspection smoke test",
  {
    skip: !enabled,
    timeout: 180000
  },
  async () => {
    const start = await execFileAsync(process.execPath, [cliPath, "session", "start", "--headless"]);
    const session = JSON.parse(start.stdout);
    assert.ok(session.id);

    try {
      const inspect = await execFileAsync(process.execPath, [
        cliPath,
        "inspect",
        "--session-id",
        session.id,
        "--url",
        "https://app.hyperliquid.xyz/trade",
        "--target",
        "smoke",
        "--viewports",
        "desktop"
      ]);
      const inspectResult = JSON.parse(inspect.stdout);
      assert.ok(inspectResult.runDir);
      assert.ok(inspectResult.snapshots.length >= 1);

      const compare = await execFileAsync(process.execPath, [
        cliPath,
        "compare",
        "--session-id",
        session.id,
        "--left-url",
        "https://app.hyperliquid.xyz/trade",
        "--right-url",
        "https://app.hyperliquid.xyz/trade",
        "--left-label",
        "left",
        "--right-label",
        "right",
        "--viewports",
        "desktop"
      ]);
      const compareResult = JSON.parse(compare.stdout);
      assert.ok(compareResult.runDir);
      assert.ok(compareResult.viewportReports.length >= 1);
    } finally {
      await execFileAsync(process.execPath, [cliPath, "session", "stop", "--session-id", session.id]).catch(() => null);
    }
  }
);
