import test from "node:test";
import assert from "node:assert/strict";
import { aggregateSummaryState, runDesignReview } from "../src/design_review_runner.mjs";
import { aggregatePassResults, makeEvidenceRef } from "../src/design_review/models.mjs";
import { DESIGN_REVIEW_PASS_NAMES, PASS_REGISTRY } from "../src/design_review/pass_registry.mjs";

function createRuntime(overrides = {}) {
  const finalizations = [];
  const writtenSummaries = [];

  const runtime = {
    now: () => "2026-03-21T20:10:00.000Z",
    runRepository: {
      async createRun() {
        return {
          runId: "design-review-test-run",
          runDir: "/tmp/design-review-test-run"
        };
      },
      async appendArtifact() {
        return null;
      },
      async finalizeRun(_runRef, result) {
        finalizations.push(result);
        return result;
      }
    },
    sessionGateway: {
      async startSession() {
        return { id: "session-1" };
      },
      async stopSession() {
        return true;
      }
    },
    captureGateway: {
      async captureViewport() {
        return {
          ok: true,
          snapshotPath: "/tmp/design-review-test-run/trade-route/review-375/snapshot.json",
          screenshotPath: "/tmp/design-review-test-run/trade-route/review-375/screenshot.png",
          evidenceRefs: [
            makeEvidenceRef(
              "snapshot",
              "/tmp/design-review-test-run/trade-route/review-375/snapshot.json"
            ),
            makeEvidenceRef(
              "screenshot",
              "/tmp/design-review-test-run/trade-route/review-375/screenshot.png"
            )
          ]
        };
      }
    },
    probeGateway: {
      async computedStyles() {
        return {
          ok: true,
          value: { selectors: [] }
        };
      },
      async nativeControls() {
        return {
          ok: true,
          value: { unexpectedSpecialNative: [] }
        };
      },
      async focusWalk() {
        return {
          ok: true,
          value: {
            count: 1,
            steps: [{ hasVisibleFocusIndicator: true, tag: "button" }]
          }
        };
      },
      async layoutAudit() {
        return {
          ok: true,
          value: {
            documentHorizontalOverflowPx: 0,
            overflowIssues: []
          }
        };
      },
      async interactionTrace() {
        return {
          ok: true,
          value: {
            performanceObserverSupported: true,
            layoutShiftValue: 0,
            maxLongTaskMs: 0
          }
        };
      }
    },
    artifactStore: {
      async ensureViewportDir() {
        return null;
      },
      async writeReviewSpec() {
        return "/tmp/design-review-test-run/review-spec.json";
      },
      async writeProbe(_runRef, { name }) {
        return `/tmp/design-review-test-run/probes/${name}.json`;
      },
      async writePassDetails() {
        return null;
      },
      async writeSummary(_runRef, summary, renderSummary) {
        writtenSummaries.push(summary);
        return {
          summaryPath: "/tmp/design-review-test-run/summary.json",
          summaryMarkdownPath: "/tmp/design-review-test-run/summary.md",
          markdown: renderSummary(summary)
        };
      }
    },
    summaryRenderer: (summary) => JSON.stringify(summary)
  };

  return {
    runtime: { ...runtime, ...overrides },
    finalizations,
    writtenSummaries
  };
}

test("aggregateSummaryState prioritizes fail before blocked before pass", () => {
  assert.equal(aggregateSummaryState([{ status: "PASS" }, { status: "PASS" }]), "PASS");
  assert.equal(aggregateSummaryState([{ status: "PASS" }, { status: "TOOLING_GAP" }]), "BLOCKED");
  assert.equal(aggregateSummaryState([{ status: "TOOLING_GAP" }, { status: "FAIL" }]), "FAIL");
  assert.equal(
    aggregateSummaryState([{ status: "PASS" }, { status: "NOT_APPLICABLE" }]),
    "PASS"
  );
});

test("runDesignReview dry-run exposes the required pass matrix and review widths", async () => {
  const result = await runDesignReview({}, {
    dryRun: true,
    targetIds: ["trade-route"]
  });

  assert.equal(result.dryRun, true);
  assert.deepEqual(result.passes, DESIGN_REVIEW_PASS_NAMES);
  assert.deepEqual(
    result.viewports.map((entry) => entry.width),
    [375, 768, 1280, 1440]
  );
  assert.deepEqual(result.selection.targets.map((target) => target.id), ["trade-route"]);
});

test("aggregatePassResults does not leak blockedReason into failed aggregate entries", () => {
  const aggregated = aggregatePassResults(PASS_REGISTRY, [
    {
      pass: "native-control",
      status: "FAIL",
      issueCount: 1,
      summary: "Found a leak.",
      evidencePaths: ["/tmp/a.json"]
    },
    {
      pass: "native-control",
      status: "TOOLING_GAP",
      issueCount: 0,
      summary: "Probe failed.",
      blockedReason: "Probe failed.",
      evidencePaths: ["/tmp/b.json"]
    }
  ]);

  const nativeControl = aggregated.find((entry) => entry.pass === "native-control");
  assert.equal(nativeControl.status, "FAIL");
  assert.equal(nativeControl.blockedReason, undefined);
});

test("runDesignReview keeps failed viewport entries and finalizes audit failures as completed runs", async () => {
  const { runtime, finalizations, writtenSummaries } = createRuntime({
    captureGateway: {
      async captureViewport() {
        return {
          ok: false,
          status: "FAIL",
          reason: "Page render timed out"
        };
      }
    }
  });

  const result = await runDesignReview({}, {
    runtime,
    headless: true,
    targetIds: ["trade-route"],
    viewports: ["review-375"]
  });

  assert.equal(result.runStatus, "completed");
  assert.equal(result.reviewOutcome, "FAIL");
  assert.equal(result.state, "FAIL");
  assert.deepEqual(result.inspectedViewports, [
    {
      name: "review-375",
      width: 375,
      height: 812
    }
  ]);
  assert.equal(result.targetResults[0].viewports.length, 1);
  assert.equal(result.targetResults[0].viewports[0].name, "review-375");
  assert.equal(result.targetResults[0].viewports[0].passes.length, DESIGN_REVIEW_PASS_NAMES.length);
  assert.equal(result.issues[0].category, "capture-failure");
  assert.equal(finalizations.length, 1);
  assert.equal(finalizations[0].runStatus, "completed");
  assert.equal(finalizations[0].reviewOutcome, "FAIL");
  assert.equal(writtenSummaries[0].reviewOutcome, "FAIL");
});

test("runDesignReview rebases local target URLs to the managed-local session origin", async () => {
  const { runtime, writtenSummaries } = createRuntime({
    sessionGateway: {
      async startSession() {
        return {
          id: "session-1",
          localApp: {
            url: "http://127.0.0.1:8084/index.html",
            requestedUrl: "http://localhost:8080/index.html"
          }
        };
      },
      async stopSession() {
        return true;
      }
    }
  });

  const result = await runDesignReview(
    {
      config: {
        localApp: { url: "http://localhost:8080/index.html" },
        targets: { local: { url: "http://localhost:8080/trade" } }
      }
    },
    {
      runtime,
      headless: true,
      manageLocalApp: true,
      targetIds: ["trade-route"],
      viewports: ["review-375"]
    }
  );

  assert.equal(result.targetResults[0].url, "http://127.0.0.1:8084/trade");
  assert.equal(writtenSummaries[0].targetResults[0].url, "http://127.0.0.1:8084/trade");
});

test("runDesignReview finalizes execution failures as failed runs", async () => {
  const { runtime, finalizations } = createRuntime({
    artifactStore: {
      async ensureViewportDir() {
        return null;
      },
      async writeReviewSpec() {
        throw new Error("disk full");
      }
    }
  });

  await assert.rejects(
    runDesignReview({}, {
      runtime,
      headless: true,
      targetIds: ["trade-route"],
      viewports: ["review-375"]
    }),
    /disk full/
  );

  assert.equal(finalizations.length, 1);
  assert.equal(finalizations[0].runStatus, "failed");
  assert.equal(finalizations[0].errorMessage, "disk full");
});
