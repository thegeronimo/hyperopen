import test from "node:test";
import assert from "node:assert/strict";
import { loadConfig } from "../src/config.mjs";

test("loadConfig returns validated defaults", async () => {
  const config = await loadConfig();
  assert.equal(typeof config.artifactRoot, "string");
  assert.equal(typeof config.chrome.path, "string");
  assert.equal(config.chrome.headless, true);
  assert.ok(config.viewports.desktop.width > 0);
  assert.ok(config.viewports.mobile.height > 0);
});
