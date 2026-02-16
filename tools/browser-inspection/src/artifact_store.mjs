import fs from "node:fs/promises";
import path from "node:path";
import { assertArtifactManifest, assertSessionState } from "./contracts.mjs";
import { ensureDir, makeRunId, readJsonFile, safeNowIso, writeJsonFile } from "./util.mjs";

export class ArtifactStore {
  constructor(config) {
    this.config = config;
    this.root = config.artifactRoot;
    this.registryPath = path.join(this.root, "sessions.json");
  }

  async init() {
    await ensureDir(this.root);
    const existing = await readJsonFile(this.registryPath, null);
    if (!existing) {
      await writeJsonFile(this.registryPath, { sessions: [] });
    }
    await this.pruneExpiredRuns();
  }

  async createRun(kind, metadata = {}) {
    const runId = makeRunId(kind);
    const runDir = path.join(this.root, runId);
    await ensureDir(runDir);
    const manifest = {
      runId,
      kind,
      createdAt: safeNowIso(),
      status: "in_progress",
      metadata,
      artifacts: []
    };
    assertArtifactManifest(manifest);
    await this.writeManifest(runDir, manifest);
    return { runId, runDir, manifestPath: this.manifestPath(runDir) };
  }

  manifestPath(runDir) {
    return path.join(runDir, "manifest.json");
  }

  async readManifest(runDir) {
    const manifest = await readJsonFile(this.manifestPath(runDir));
    assertArtifactManifest(manifest);
    return manifest;
  }

  async writeManifest(runDir, manifest) {
    assertArtifactManifest(manifest);
    await writeJsonFile(this.manifestPath(runDir), manifest);
  }

  async appendArtifact(runDir, artifact) {
    const manifest = await this.readManifest(runDir);
    manifest.artifacts.push(artifact);
    await this.writeManifest(runDir, manifest);
    return manifest;
  }

  async completeRun(runDir, finalMetadata = {}) {
    const manifest = await this.readManifest(runDir);
    manifest.status = "complete";
    manifest.completedAt = safeNowIso();
    manifest.metadata = { ...(manifest.metadata || {}), ...finalMetadata };
    await this.writeManifest(runDir, manifest);
    return manifest;
  }

  async failRun(runDir, errorMessage) {
    const manifest = await this.readManifest(runDir);
    manifest.status = "failed";
    manifest.completedAt = safeNowIso();
    manifest.error = errorMessage;
    await this.writeManifest(runDir, manifest);
    return manifest;
  }

  async readSessionRegistry() {
    const data = await readJsonFile(this.registryPath, { sessions: [] });
    if (!Array.isArray(data.sessions)) {
      return [];
    }
    return data.sessions;
  }

  async writeSessionRegistry(sessions) {
    await writeJsonFile(this.registryPath, { sessions });
  }

  async upsertSession(session) {
    assertSessionState(session);
    const sessions = await this.readSessionRegistry();
    const idx = sessions.findIndex((s) => s.id === session.id);
    if (idx >= 0) {
      sessions[idx] = session;
    } else {
      sessions.push(session);
    }
    await this.writeSessionRegistry(sessions);
  }

  async removeSession(sessionId) {
    const sessions = await this.readSessionRegistry();
    const filtered = sessions.filter((s) => s.id !== sessionId);
    await this.writeSessionRegistry(filtered);
  }

  async listSessions() {
    return this.readSessionRegistry();
  }

  async pruneExpiredRuns() {
    const ttlMs = (this.config.retentionHours || 72) * 60 * 60 * 1000;
    const maxArtifacts = this.config.maxArtifacts || 120;
    const now = Date.now();

    await ensureDir(this.root);
    const entries = await fs.readdir(this.root, { withFileTypes: true });
    const runDirs = [];

    for (const entry of entries) {
      if (!entry.isDirectory()) {
        continue;
      }
      const full = path.join(this.root, entry.name);
      const stat = await fs.stat(full);
      runDirs.push({ name: entry.name, full, mtimeMs: stat.mtimeMs });
    }

    runDirs.sort((a, b) => b.mtimeMs - a.mtimeMs);
    const keepByAge = new Set(
      runDirs
        .filter((d) => now - d.mtimeMs <= ttlMs)
        .slice(0, maxArtifacts)
        .map((d) => d.full)
    );

    for (const run of runDirs) {
      const tooOld = now - run.mtimeMs > ttlMs;
      const overLimit = !keepByAge.has(run.full);
      if (tooOld || overLimit) {
        await fs.rm(run.full, { recursive: true, force: true });
      }
    }
  }
}
