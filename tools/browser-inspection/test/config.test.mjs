import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { loadConfig } from "../src/config.mjs";

test("loadConfig returns validated defaults", async () => {
  const config = await loadConfig();
  assert.equal(typeof config.artifactRoot, "string");
  assert.equal(typeof config.chrome.path, "string");
  assert.equal(config.chrome.headless, true);
  assert.equal(config.localApp.url, "http://localhost:8080/index.html");
  assert.ok(config.viewports.desktop.width > 0);
  assert.ok(config.viewports.mobile.height > 0);
});

test("loadConfig applies iPhone 14 Pro Max viewport override without disturbing desktop", async () => {
  const config = await loadConfig({
    configPath: path.resolve("tools/browser-inspection/config/iphone-14-pro-max.json")
  });

  assert.equal(config.viewports.mobile.width, 430);
  assert.equal(config.viewports.mobile.height, 932);
  assert.equal(config.viewports.mobile.mobile, true);
  assert.equal(config.viewports.mobile.deviceScaleFactor, 3);
  assert.equal(config.viewports.desktop.width, 1440);
  assert.equal(config.viewports.desktop.height, 900);
});
