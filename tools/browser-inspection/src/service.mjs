import fs from "node:fs/promises";
import path from "node:path";
import { loadConfig } from "./config.mjs";
import { captureSnapshot } from "./capture_pipeline.mjs";
import { compareSnapshots, renderCompareMarkdown } from "./parity_compare.mjs";
import { SessionManager } from "./session_manager.mjs";
import { ensureDir, writeJsonFile } from "./util.mjs";

function resolveViewportNames(config, requested = null) {
  const all = Object.keys(config.viewports);
  if (!requested || requested.length === 0) {
    return all;
  }
  return requested.filter((name) => all.includes(name));
}

async function writeSnapshotArtifacts(runDir, payload) {
  const targetDir = path.join(runDir, payload.targetLabel, payload.viewport);
  await ensureDir(targetDir);

  const screenshotPath = path.join(targetDir, "screenshot.png");
  const snapshotPath = path.join(targetDir, "snapshot.json");

  const screenshotBuffer = Buffer.from(payload.screenshot.dataBase64, "base64");
  await fs.writeFile(screenshotPath, screenshotBuffer);

  const persistedPayload = {
    ...payload,
    screenshot: {
      format: payload.screenshot.format,
      path: screenshotPath
    }
  };
  await writeJsonFile(snapshotPath, persistedPayload);

  return {
    snapshotPath,
    screenshotPath,
    payload: {
      ...persistedPayload,
      screenshotPath
    }
  };
}

export class BrowserInspectionService {
  constructor(config, sessionManager) {
    this.config = config;
    this.sessionManager = sessionManager;
  }

  static async create(options = {}) {
    const config = await loadConfig(options);
    const sessionManager = new SessionManager(config);
    await sessionManager.init();
    return new BrowserInspectionService(config, sessionManager);
  }

  async startSession(options = {}) {
    return this.sessionManager.startSession(options);
  }

  async stopSession(sessionId) {
    return this.sessionManager.stopSession(sessionId);
  }

  async listSessions() {
    return this.sessionManager.listSessions();
  }

  async navigate(options) {
    const viewport = options.viewportName
      ? this.config.viewports[options.viewportName]
      : null;
    return this.sessionManager.navigate(options.sessionId, options.url, {
      viewport,
      timeoutMs: options.timeoutMs
    });
  }

  async evaluate(options) {
    return this.sessionManager.evaluate(options.sessionId, options.expression, {
      allowUnsafeEval: options.allowUnsafeEval,
      timeoutMs: options.timeoutMs
    });
  }

  async capture(options = {}) {
    const run = await this.sessionManager.store.createRun("inspect", {
      requestedAt: new Date().toISOString(),
      options: {
        targetLabel: options.targetLabel,
        url: options.url
      }
    });

    let sessionId = options.sessionId;
    let tempSession = null;

    try {
      if (!sessionId) {
        tempSession = await this.startSession({
          headless: options.headless,
          manageLocalApp: Boolean(options.manageLocalApp),
          localAppUrl: options.localAppUrl,
          readOnly: true
        });
        sessionId = tempSession.id;
      }

      const viewportNames = resolveViewportNames(this.config, options.viewports);
      const snapshots = [];

      for (const viewportName of viewportNames) {
        const payload = await captureSnapshot(this.sessionManager, sessionId, {
          url: options.url,
          targetLabel: options.targetLabel || "target",
          viewportName,
          viewport: this.config.viewports[viewportName],
          maskSelectors: this.config.masking.selectors
        });
        const persisted = await writeSnapshotArtifacts(run.runDir, payload);
        snapshots.push({
          viewport: viewportName,
          snapshotPath: persisted.snapshotPath,
          screenshotPath: persisted.screenshotPath
        });

        await this.sessionManager.store.appendArtifact(run.runDir, {
          type: "snapshot",
          target: options.targetLabel || "target",
          viewport: viewportName,
          snapshotPath: persisted.snapshotPath,
          screenshotPath: persisted.screenshotPath
        });
      }

      const manifest = await this.sessionManager.store.completeRun(run.runDir, {
        sessionId,
        snapshots
      });

      return {
        runId: run.runId,
        runDir: run.runDir,
        sessionId,
        snapshots,
        manifestPath: path.join(run.runDir, "manifest.json"),
        manifest
      };
    } catch (error) {
      await this.sessionManager.store.failRun(run.runDir, error.message);
      throw error;
    } finally {
      if (tempSession) {
        await this.stopSession(tempSession.id).catch(() => null);
      }
    }
  }

  async compare(options = {}) {
    const leftTarget = {
      label: options.leftLabel || this.config.targets.remote.label,
      url: options.leftUrl || this.config.targets.remote.url
    };
    const rightTarget = {
      label: options.rightLabel || this.config.targets.local.label,
      url: options.rightUrl || this.config.targets.local.url
    };

    const run = await this.sessionManager.store.createRun("compare", {
      requestedAt: new Date().toISOString(),
      leftTarget,
      rightTarget
    });

    let sessionId = options.sessionId;
    let tempSession = null;

    try {
      if (!sessionId) {
        tempSession = await this.startSession({
          headless: options.headless,
          manageLocalApp: Boolean(options.manageLocalApp),
          localAppUrl: options.localAppUrl,
          readOnly: true
        });
        sessionId = tempSession.id;
      }

      const viewportNames = resolveViewportNames(this.config, options.viewports);
      const viewportReports = [];

      for (const viewportName of viewportNames) {
        const leftPayload = await captureSnapshot(this.sessionManager, sessionId, {
          url: leftTarget.url,
          targetLabel: leftTarget.label,
          viewportName,
          viewport: this.config.viewports[viewportName],
          maskSelectors: this.config.masking.selectors
        });

        const rightPayload = await captureSnapshot(this.sessionManager, sessionId, {
          url: rightTarget.url,
          targetLabel: rightTarget.label,
          viewportName,
          viewport: this.config.viewports[viewportName],
          maskSelectors: this.config.masking.selectors
        });

        const leftPersisted = await writeSnapshotArtifacts(run.runDir, leftPayload);
        const rightPersisted = await writeSnapshotArtifacts(run.runDir, rightPayload);

        await this.sessionManager.store.appendArtifact(run.runDir, {
          type: "snapshot",
          target: leftTarget.label,
          viewport: viewportName,
          snapshotPath: leftPersisted.snapshotPath,
          screenshotPath: leftPersisted.screenshotPath
        });
        await this.sessionManager.store.appendArtifact(run.runDir, {
          type: "snapshot",
          target: rightTarget.label,
          viewport: viewportName,
          snapshotPath: rightPersisted.snapshotPath,
          screenshotPath: rightPersisted.screenshotPath
        });

        const report = await compareSnapshots(
          {
            ...leftPersisted.payload,
            screenshotPath: leftPersisted.screenshotPath,
            semantic: leftPersisted.payload.semantic
          },
          {
            ...rightPersisted.payload,
            screenshotPath: rightPersisted.screenshotPath,
            semantic: rightPersisted.payload.semantic
          },
          {
            config: this.config,
            outDir: run.runDir,
            viewportName
          }
        );

        const reportJsonPath = path.join(run.runDir, `${viewportName}-report.json`);
        const reportMdPath = path.join(run.runDir, `${viewportName}-report.md`);
        await writeJsonFile(reportJsonPath, report);
        await fs.writeFile(
          reportMdPath,
          renderCompareMarkdown(report, { left: leftTarget.label, right: rightTarget.label })
        );

        await this.sessionManager.store.appendArtifact(run.runDir, {
          type: "report",
          viewport: viewportName,
          reportJsonPath,
          reportMdPath,
          diffImagePath: report.outputs.visualDiffImagePath
        });

        viewportReports.push({
          viewport: viewportName,
          reportJsonPath,
          reportMdPath,
          summary: report.summary,
          diffImagePath: report.outputs.visualDiffImagePath
        });
      }

      const summary = {
        sessionId,
        viewportReports,
        totals: {
          semanticFindings: viewportReports.reduce((acc, entry) => acc + entry.summary.semanticFindingCount, 0),
          avgVisualDiffRatio:
            viewportReports.reduce((acc, entry) => acc + entry.summary.visualDiffRatio, 0) /
            Math.max(viewportReports.length, 1)
        }
      };

      await this.sessionManager.store.completeRun(run.runDir, summary);

      return {
        runId: run.runId,
        runDir: run.runDir,
        summary,
        viewportReports,
        manifestPath: path.join(run.runDir, "manifest.json")
      };
    } catch (error) {
      await this.sessionManager.store.failRun(run.runDir, error.message);
      throw error;
    } finally {
      if (tempSession) {
        await this.stopSession(tempSession.id).catch(() => null);
      }
    }
  }
}
