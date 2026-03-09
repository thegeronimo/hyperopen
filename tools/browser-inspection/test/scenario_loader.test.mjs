import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import assert from "node:assert/strict";
import {
  loadScenarios,
  loadScenarioRouting,
  selectScenarioTagsForChangedFiles
} from "../src/scenario_loader.mjs";

async function writeJson(filePath, value) {
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`);
}

test("loadScenarios filters manifests by ids and tags", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-scenarios-"));
  await writeJson(path.join(root, "trade.json"), {
    id: "trade-smoke",
    title: "Trade",
    tags: ["critical", "trade"],
    viewports: ["desktop"],
    url: "http://localhost:8080/trade",
    steps: [{ type: "navigate" }]
  });
  await writeJson(path.join(root, "wallet.json"), {
    id: "wallet-smoke",
    title: "Wallet",
    tags: ["wallet"],
    viewports: ["desktop"],
    url: "http://localhost:8080/trade",
    steps: [{ type: "navigate" }]
  });

  const byTag = await loadScenarios({ scenarioDir: root, tags: ["wallet"] });
  assert.deepEqual(byTag.map((scenario) => scenario.id), ["wallet-smoke"]);

  const byId = await loadScenarios({ scenarioDir: root, ids: ["trade-smoke"] });
  assert.deepEqual(byId.map((scenario) => scenario.id), ["trade-smoke"]);

  await fs.rm(root, { recursive: true, force: true });
});

test("scenario routing selects default, matched, and full-critical tags", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-routing-"));
  const routingPath = path.join(root, "scenario-routing.json");
  await writeJson(routingPath, {
    defaultTags: ["critical"],
    fullCriticalGlobs: ["tools/browser-inspection/**"],
    tagRules: [
      { glob: "src/hyperopen/funding/**", tags: ["funding"] },
      { glob: "src/hyperopen/views/**", tags: ["mobile"] }
    ]
  });

  const routing = await loadScenarioRouting(routingPath);
  const selection = selectScenarioTagsForChangedFiles(
    ["src/hyperopen/funding/actions.cljs", "tools/browser-inspection/src/cli.mjs"],
    routing
  );

  assert.equal(selection.fullCritical, true);
  assert.deepEqual(selection.tags, ["critical", "funding"]);

  await fs.rm(root, { recursive: true, force: true });
});
