import test from "node:test";
import assert from "node:assert/strict";
import { buildServer } from "../src/mcp_server.mjs";

test("mcp server registers browser inspection tools", async () => {
  const server = await buildServer();
  const toolNames = Object.keys(server._registeredTools || {});

  assert.ok(toolNames.includes("browser_session_start"));
  assert.ok(toolNames.includes("browser_session_stop"));
  assert.ok(toolNames.includes("browser_navigate"));
  assert.ok(toolNames.includes("browser_eval"));
  assert.ok(toolNames.includes("browser_capture_snapshot"));
  assert.ok(toolNames.includes("browser_compare_targets"));
  assert.ok(toolNames.includes("browser_sessions_list"));

  await server.close();
});
