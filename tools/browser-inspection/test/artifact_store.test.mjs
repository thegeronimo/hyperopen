import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import assert from "node:assert/strict";
import { ArtifactStore } from "../src/artifact_store.mjs";

function baseConfig(root) {
  return {
    artifactRoot: root,
    retentionHours: 1,
    maxArtifacts: 10,
    chrome: { path: "chrome", headless: true },
    targets: { local: {}, remote: {} },
    viewports: { desktop: { width: 1, height: 1 }, mobile: { width: 1, height: 1 } }
  };
}

test("ArtifactStore creates runs and tracks session registry", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-artifacts-"));
  const store = new ArtifactStore(baseConfig(root));
  await store.init();

  const run = await store.createRun("inspect", { foo: "bar" });
  const manifest = await store.readManifest(run.runDir);
  assert.equal(manifest.kind, "inspect");

  const session = {
    id: "sess-a",
    createdAt: new Date().toISOString(),
    readOnly: true,
    chrome: { pid: 123, port: 456, path: "chrome", headless: true, userDataDir: "/tmp/x", ephemeralProfile: true },
    targetId: "target-1"
  };
  await store.upsertSession(session);
  const sessions = await store.listSessions();
  assert.equal(sessions.length, 1);

  await store.removeSession("sess-a");
  const sessionsAfter = await store.listSessions();
  assert.equal(sessionsAfter.length, 0);

  const attachedSession = {
    id: "sess-attached",
    createdAt: new Date().toISOString(),
    readOnly: true,
    chrome: {
      pid: null,
      port: 9222,
      host: "127.0.0.1",
      path: null,
      headless: null,
      userDataDir: null,
      ephemeralProfile: false,
      controlMode: "attached"
    },
    targetId: "target-2"
  };
  await store.upsertSession(attachedSession);
  const attachedSessions = await store.listSessions();
  assert.equal(attachedSessions.length, 1);

  await fs.rm(root, { recursive: true, force: true });
});

test("ArtifactStore finalizes completed audit failures separately from execution failures", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-artifacts-"));
  const store = new ArtifactStore(baseConfig(root));
  await store.init();

  const auditRun = await store.createRun("design-review", { foo: "bar" });
  const completedManifest = await store.finalizeRun(auditRun.runDir, {
    runStatus: "completed",
    reviewOutcome: "FAIL",
    summaryPath: "/tmp/summary.json"
  });
  assert.equal(completedManifest.status, "complete");
  assert.equal(completedManifest.metadata.runStatus, "completed");
  assert.equal(completedManifest.metadata.reviewOutcome, "FAIL");
  assert.equal(completedManifest.error, undefined);

  const failedRun = await store.createRun("design-review", { foo: "bar" });
  const failedManifest = await store.finalizeRun(failedRun.runDir, {
    runStatus: "failed",
    errorMessage: "chrome crashed"
  });
  assert.equal(failedManifest.status, "failed");
  assert.equal(failedManifest.metadata.runStatus, "failed");
  assert.equal(failedManifest.error, "chrome crashed");

  await fs.rm(root, { recursive: true, force: true });
});
