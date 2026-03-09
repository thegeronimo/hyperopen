import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import assert from "node:assert/strict";
import { runScenarioBundle } from "../src/scenario_runner.mjs";

async function writeJson(filePath, value) {
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`);
}

function buildFakeService(artifactRoot, overrides = {}) {
  return {
    config: {
      artifactRoot,
      localApp: { url: "http://localhost:8080/trade" },
      targets: { remote: { url: "https://app.hyperliquid.xyz/trade", label: "hyperliquid" } },
      viewports: { desktop: { width: 1440, height: 900 } }
    },
    sessionManager: {
      store: {
        async createRun(kind) {
          const runId = `${kind}-test-run`;
          const runDir = path.join(artifactRoot, runId);
          await fs.mkdir(runDir, { recursive: true });
          return { runId, runDir };
        },
        async appendArtifact() {},
        async completeRun(_runDir, metadata) {
          return metadata;
        },
        async failRun() {}
      }
    },
    async preflight() {
      return { ok: true, mode: "local", checks: [] };
    },
    async startSession() {
      return { id: "session-1" };
    },
    async stopSession() {
      return true;
    },
    async navigate({ url, viewportName }) {
      return { navigated: true, url, viewportName };
    },
    async compare() {
      return { runId: "compare-1", runDir: path.join(artifactRoot, "compare-1") };
    },
    ...overrides
  };
}

test("runScenarioBundle dry-run lists selected scenarios", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-scenario-runner-"));
  await writeJson(path.join(root, "one.json"), {
    id: "one",
    title: "One",
    tags: ["critical"],
    viewports: ["desktop"],
    url: "http://localhost:8080/trade",
    steps: [{ type: "navigate" }]
  });

  const service = buildFakeService(root);
  const result = await runScenarioBundle(service, {
    scenarioDir: root,
    tags: ["critical"],
    dryRun: true
  });

  assert.equal(result.dryRun, true);
  assert.deepEqual(result.selected.map((entry) => entry.id), ["one"]);

  await fs.rm(root, { recursive: true, force: true });
});

test("runScenarioBundle records pass and expectation failures", async () => {
  const scenarioDir = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-scenario-manifests-"));
  const artifactRoot = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-scenario-artifacts-"));
  await writeJson(path.join(scenarioDir, "pass.json"), {
    id: "pass-scenario",
    title: "Pass",
    severity: "high",
    tags: ["critical"],
    viewports: ["desktop"],
    url: "http://localhost:8080/trade",
    steps: [{ type: "navigate" }]
  });
  await writeJson(path.join(scenarioDir, "fail.json"), {
    id: "fail-scenario",
    title: "Fail",
    severity: "high",
    tags: ["critical"],
    viewports: ["desktop"],
    url: "http://localhost:8080/trade",
    steps: [
      {
        type: "sleep",
        ms: 1,
        expect: { sleptMs: 2 }
      }
    ]
  });

  const service = buildFakeService(artifactRoot);
  const result = await runScenarioBundle(service, {
    scenarioDir,
    tags: ["critical"],
    runKind: "scenario"
  });

  assert.equal(result.state, "product-regression");
  assert.equal(result.results.length, 2);
  assert.equal(result.results.find((entry) => entry.scenarioId === "pass-scenario").state, "pass");
  assert.equal(
    result.results.find((entry) => entry.scenarioId === "fail-scenario").state,
    "product-regression"
  );

  const summaryPath = path.join(result.runDir, "summary.json");
  const summary = JSON.parse(await fs.readFile(summaryPath, "utf8"));
  assert.equal(summary.state, "product-regression");

  await fs.rm(scenarioDir, { recursive: true, force: true });
  await fs.rm(artifactRoot, { recursive: true, force: true });
});

test("runScenarioBundle waits for oracle expectations to settle", async () => {
  const scenarioDir = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-scenario-wait-"));
  const artifactRoot = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-scenario-wait-artifacts-"));
  await writeJson(path.join(scenarioDir, "wait.json"), {
    id: "wait-scenario",
    title: "Wait",
    severity: "high",
    tags: ["critical"],
    viewports: ["desktop"],
    url: "http://localhost:8080/trade",
    steps: [
      {
        type: "wait_for_oracle",
        name: "first-position",
        timeoutMs: 100,
        pollMs: 1,
        expect: { present: true }
      }
    ]
  });

  const oracleResults = [{ present: false }, { present: true }];
  const service = buildFakeService(artifactRoot, {
    async evaluate() {
      return { result: oracleResults.shift() ?? { present: true } };
    }
  });

  const result = await runScenarioBundle(service, {
    scenarioDir,
    tags: ["critical"],
    runKind: "scenario"
  });

  assert.equal(result.state, "pass");
  assert.equal(result.results[0].state, "pass");

  await fs.rm(scenarioDir, { recursive: true, force: true });
  await fs.rm(artifactRoot, { recursive: true, force: true });
});
