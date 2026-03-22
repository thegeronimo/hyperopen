import { captureSnapshot } from "../capture_pipeline.mjs";
import { classifyErrorMessage } from "../failure_classification.mjs";
import {
  getComputedStyles,
  listNativeControls,
  runFocusWalk,
  runLayoutAudit,
  traceInteraction
} from "../dom_probes.mjs";
import { writeSnapshotArtifacts } from "../service.mjs";
import { safeNowIso } from "../util.mjs";
import { createDesignReviewArtifactStore } from "./artifact_store.mjs";
import { makeEvidenceRef, PASS_STATUS } from "./models.mjs";
import { renderSummaryMarkdown } from "./render_summary_markdown.mjs";

function safeProbe(fn) {
  return fn()
    .then((value) => ({ ok: true, value }))
    .catch((error) => ({
      ok: false,
      error,
      status: PASS_STATUS.TOOLING_GAP,
      reason: error?.message || String(error)
    }));
}

function createDesignReviewRunRepository(store) {
  return {
    async createRun(kind, metadata = {}) {
      return store.createRun(kind, metadata);
    },

    async appendArtifact(runRef, artifact) {
      return store.appendArtifact(runRef.runDir, artifact);
    },

    async finalizeRun(runRef, finalState = {}) {
      if (typeof store.finalizeRun === "function") {
        return store.finalizeRun(runRef.runDir, finalState);
      }
      if (finalState.runStatus === "completed") {
        return store.completeRun(runRef.runDir, finalState);
      }
      return store.failRun(runRef.runDir, finalState.errorMessage || "Run failed");
    }
  };
}

function classifyCaptureFailure(error) {
  const reason = error?.message || String(error);
  const classification = classifyErrorMessage(reason);
  if (classification?.classification === "automation-gap") {
    return {
      status: PASS_STATUS.TOOLING_GAP,
      reason
    };
  }
  return {
    status: PASS_STATUS.FAIL,
    reason
  };
}

export function createDesignReviewRuntime(service, overrides = {}) {
  const artifactStore = overrides.artifactStore || createDesignReviewArtifactStore();
  const captureSnapshotFn = overrides.captureSnapshot || captureSnapshot;
  const writeSnapshotArtifactsFn = overrides.writeSnapshotArtifacts || writeSnapshotArtifacts;
  const summaryRenderer = overrides.summaryRenderer || renderSummaryMarkdown;

  return {
    now: overrides.now || safeNowIso,
    artifactStore,
    summaryRenderer,
    runRepository: overrides.runRepository || createDesignReviewRunRepository(service.sessionManager.store),
    sessionGateway: overrides.sessionGateway || {
      startSession: async (options = {}) => service.startSession(options),
      stopSession: async (sessionId) => service.stopSession(sessionId)
    },
    captureGateway: overrides.captureGateway || {
      async captureViewport(runRef, sessionId, { target, viewportName, viewport, designConfig }) {
        try {
          const payload = await captureSnapshotFn(service.sessionManager, sessionId, {
            url: target.url,
            targetLabel: target.id,
            viewportName,
            viewport,
            maskSelectors: service.config.masking.selectors,
            computedStyleKeys: designConfig.computedStyleKeys,
            maxSemanticNodes: designConfig.maxSemanticNodes
          });
          const persisted = await writeSnapshotArtifactsFn(runRef.runDir, payload);
          await service.sessionManager.store.appendArtifact(runRef.runDir, {
            type: "snapshot",
            target: target.id,
            viewport: viewportName,
            snapshotPath: persisted.snapshotPath,
            screenshotPath: persisted.screenshotPath
          });
          return {
            ok: true,
            snapshotPath: persisted.snapshotPath,
            screenshotPath: persisted.screenshotPath,
            evidenceRefs: [
              makeEvidenceRef("snapshot", persisted.snapshotPath),
              makeEvidenceRef("screenshot", persisted.screenshotPath)
            ]
          };
        } catch (error) {
          return {
            ok: false,
            error,
            ...classifyCaptureFailure(error)
          };
        }
      }
    },
    probeGateway: overrides.probeGateway || {
      computedStyles: async (sessionId, { target, designConfig }) =>
        safeProbe(() =>
          getComputedStyles(service, sessionId, {
            selectors: target.selectors,
            props: designConfig.computedStyleKeys,
            maxMatches: designConfig.maxSelectorMatches
          })
        ),
      nativeControls: async (sessionId, { target }) =>
        safeProbe(() =>
          listNativeControls(service, sessionId, {
            allowlist: target.nativeControlAllowlist
          })
        ),
      focusWalk: async (sessionId, { target, designConfig }) =>
        safeProbe(() =>
          runFocusWalk(service, sessionId, {
            selectors: target.selectors,
            limit: designConfig.focusWalk?.limit || 20
          })
        ),
      layoutAudit: async (sessionId, { target, designConfig }) =>
        safeProbe(() =>
          runLayoutAudit(service, sessionId, {
            selectors: target.selectors,
            maxMatches: designConfig.maxSelectorMatches
          })
        ),
      interactionTrace: async (sessionId, { target, designConfig }) =>
        safeProbe(() =>
          traceInteraction(service, sessionId, {
            selectors: target.selectors,
            focusLimit: designConfig.interactionTrace?.focusLimit,
            scrollFractions: designConfig.interactionTrace?.scrollFractions,
            delayMs: designConfig.interactionTrace?.delayMs,
            settleDelayMs:
              target.interactionTrace?.settleDelayMs ??
              designConfig.interactionTrace?.settleDelayMs
          })
        )
    }
  };
}
