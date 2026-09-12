import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { ArtifactStore } from "../src/artifact_store.mjs";
import { baselinePath, compactNightlySummary, findingFingerprint, loadNightlyBaseline, saveNightlyBaseline } from "../src/nightly_baseline.mjs";
import { classifyNightlyFailures, fileNightlyIssues, renderReport } from "../src/nightly_ui_qa.mjs";

const failure = { scenarioId: "margin", viewport: "mobile", route: "/trade", state: "product-regression", severity: "high", message: "sheet stayed closed", url: "http://localhost/trade" };

test("baseline survives real retention pruning and preserves existing failure novelty", async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "nightly-baseline-"));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const runId = "nightly-ui-qa-2026-01-01";
  const runDir = path.join(root, runId);
  await fs.mkdir(runDir);
  const summary = { runId, runDir, state: "product-regression", results: [{ ...failure, steps: [{ large: "payload" }] }] };
  await fs.writeFile(path.join(runDir, "summary.json"), JSON.stringify(summary));
  const old = new Date("2026-01-01");
  await fs.utimes(runDir, old, old);
  const before = await loadNightlyBaseline(root);
  await new ArtifactStore({ artifactRoot: root, retentionHours: 1 }).init();
  await assert.rejects(fs.stat(runDir), { code: "ENOENT" });
  const after = await loadNightlyBaseline(root);
  assert.deepEqual(after, before);
  assert.equal(after.results[0].steps, undefined);
  assert.equal(classifyNightlyFailures(summary, after).novelty, "EXISTING");
});

test("a newer incomplete run cannot erase the last baseline; corrupt baseline bootstraps valid history", async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "nightly-baseline-"));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const summary = { runId: "nightly-ui-qa-2026-01-01", runDir: path.join(root, "nightly-ui-qa-2026-01-01"), state: "pass", results: [] };
  await saveNightlyBaseline(root, summary);
  await fs.mkdir(path.join(root, "nightly-ui-qa-2026-01-02"));
  assert.deepEqual(await loadNightlyBaseline(root), compactNightlySummary(summary));
  const oldDir = path.join(root, summary.runId);
  await fs.mkdir(oldDir);
  await fs.writeFile(path.join(oldDir, "summary.json"), JSON.stringify(summary));
  await fs.writeFile(baselinePath(root), "{broken");
  assert.equal((await loadNightlyBaseline(root)).runId, summary.runId);
});

test("different failure on same scenario is new; trace and timestamp changes are ignored", () => {
  const previous = { state: failure.state, results: [failure] };
  assert.equal(classifyNightlyFailures({ ...previous, results: [{ ...failure, message: "different failure" }] }, previous).novelty, "NEW");
  assert.equal(findingFingerprint({ ...failure, startedAt: "later", steps: [] }), findingFingerprint(failure));
});

const filing = { repoRoot: "/repo", runDir: "/run", runId: "nightly", reportPath: "/report", dryRun: false };

test("existing fingerprint or legacy exact finding suppresses duplicate creation", async () => {
  for (const issue of [
    { id: "old", title: "renamed", status: "closed", description: `Nightly finding fingerprint: ${findingFingerprint(failure)}` },
    { id: "legacy", title: "Nightly UI regression: margin (mobile)", description: `Message: ${failure.message}` }
  ]) {
    const results = await fileNightlyIssues({ ...filing, classification: { newProductRegressions: [failure] }, listIssues: async () => [issue], createIssue: async () => assert.fail("must not create duplicate") });
    assert.equal(results[0].id, issue.id);
    assert.equal(results[0].existing, true);
  }
});

test("baseline reports its actual comparison source and rejects incomplete identity", async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "nightly-baseline-"));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  assert.equal((await loadNightlyBaseline(root, { withSource: true })).source, "none");
  await fs.writeFile(baselinePath(root), JSON.stringify({ state: "pass", results: [] }));
  assert.equal(await loadNightlyBaseline(root), null);
  const runId = "nightly-ui-qa-2026-01-01";
  const runDir = path.join(root, runId);
  await fs.mkdir(runDir);
  await fs.writeFile(path.join(runDir, "summary.json"), JSON.stringify({ runId, runDir, state: "pass", results: [] }));
  assert.equal((await loadNightlyBaseline(root, { withSource: true })).source, "retained-run");
  assert.equal((await loadNightlyBaseline(root, { withSource: true })).source, "durable-baseline");
});

test("a different fingerprint remains eligible and reports distinguish existing, created, and errors", async () => {
  const classification = classifyNightlyFailures({ state: failure.state, results: [failure] }, null);
  let calls = 0;
  await fileNightlyIssues({ ...filing, classification, listIssues: async () => [{ id: "different", title: "Nightly UI regression: margin (mobile)", description: `Nightly finding fingerprint: ${findingFingerprint({ ...failure, message: "other" })}` }], createIssue: async () => { calls++; return { id: "created" }; } });
  assert.equal(calls, 1);
  const report = renderReport({ overallState: failure.state, summary: { runId: "run", runDir: "/run", state: failure.state }, designReview: { state: "PASS", passes: [] }, classification, branch: "main", reportDate: "2026-01-01", reportPath: "/report", comparisonSource: "durable-baseline", filedIssues: [{ id: "closed-match", title: "old issue", existing: true, status: "closed" }, { id: "created", title: "new issue" }, { error: "tracker offline" }] });
  assert.match(report, /EXISTING \(closed; not created\): closed-match/);
  assert.match(report, /created: new issue/);
  assert.match(report, /Issue filing failed: tracker offline/);
  assert.match(report, /durable-baseline/);
});

test("failed issue lookup creates nothing and persistent findings retry filing", async () => {
  const classification = classifyNightlyFailures({ state: failure.state, results: [failure] }, { state: failure.state, results: [failure] });
  const failed = await fileNightlyIssues({ ...filing, classification, listIssues: async () => { throw new Error("offline"); }, createIssue: async () => assert.fail("must not create without lookup") });
  assert.match(failed[0].error, /lookup failed/);
  let calls = 0;
  const retried = await fileNightlyIssues({ ...filing, classification, listIssues: async () => [], createIssue: async (options) => { calls++; assert.match(options.description, /Nightly finding fingerprint:/); return { id: "new" }; } });
  assert.equal(calls, 1);
  assert.equal(retried[0].id, "new");
});

test("duplicate candidates in one run only create once", async () => {
  let calls = 0;
  const results = await fileNightlyIssues({ ...filing, classification: { newProductRegressions: [failure, failure] }, listIssues: async () => [], createIssue: async () => { calls++; return { id: "one" }; } });
  assert.equal(calls, 1);
  assert.equal(results[1].existing, true);
});
