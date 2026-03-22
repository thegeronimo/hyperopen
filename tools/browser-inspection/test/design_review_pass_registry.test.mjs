import test from "node:test";
import assert from "node:assert/strict";
import { loadDesignReviewConfig } from "../src/design_review_loader.mjs";
import { makeEvidenceRef } from "../src/design_review/models.mjs";
import { DESIGN_REVIEW_PASS_NAMES, PASS_REGISTRY } from "../src/design_review/pass_registry.mjs";

function pass(name) {
  return PASS_REGISTRY.find((entry) => entry.name === name);
}

function baseCtx(overrides = {}) {
  return {
    target: {
      id: "trade-route",
      route: "/trade",
      selectors: ["[data-parity-id='trade-root']"],
      referenceDocs: [],
      workbenchScenes: [],
      ...overrides
    },
    viewportName: "review-375"
  };
}

function baseArtifacts() {
  return {
    evidenceRefs: [makeEvidenceRef("artifact", "/tmp/design-review-test.json")]
  };
}

test("design review pass registry stays aligned with config and renames the visual pass honestly", async () => {
  const config = await loadDesignReviewConfig();
  assert.deepEqual(config.passes, DESIGN_REVIEW_PASS_NAMES);
  assert.equal(DESIGN_REVIEW_PASS_NAMES[0], "visual-evidence-captured");
  assert.ok(!DESIGN_REVIEW_PASS_NAMES.includes("visual"));
});

test("visual-evidence-captured reports missing references as a config gap", () => {
  const result = pass("visual-evidence-captured").grade({
    ctx: baseCtx(),
    probes: {},
    artifacts: baseArtifacts(),
    policy: {}
  });

  assert.equal(result.status, "CONFIG_GAP");
  assert.equal(result.issues.length, 0);
  assert.equal(result.blindSpots[0].reasonCode, "missing-design-reference");
});

test("visual-evidence-captured passes when evidence exists and references are resolved", () => {
  const result = pass("visual-evidence-captured").grade({
    ctx: baseCtx({
      referenceDocs: ["/hyperopen/docs/DESIGN.md"]
    }),
    probes: {},
    artifacts: baseArtifacts(),
    policy: {}
  });

  assert.equal(result.status, "PASS");
  assert.equal(result.issues.length, 0);
});

test("styling-consistency reports empty approved scales as a config gap", async () => {
  const config = await loadDesignReviewConfig();
  const result = pass("styling-consistency").grade({
    ctx: baseCtx(),
    probes: {
      computedStyles: {
        selectors: [
          {
            selector: "[data-parity-id='trade-root']",
            matches: [{ styles: { paddingTop: "8px" } }]
          }
        ]
      }
    },
    artifacts: baseArtifacts(),
    policy: {
      ...config,
      approvedValues: {
        ...config.approvedValues,
        spacingPx: []
      }
    }
  });

  assert.equal(result.status, "CONFIG_GAP");
  assert.equal(result.issues.length, 0);
  assert.equal(result.blindSpots[0].reasonCode, "missing-approved-scale");
});

test("styling-consistency reports unsupported non-px values as a tooling gap", async () => {
  const config = await loadDesignReviewConfig();
  const result = pass("styling-consistency").grade({
    ctx: baseCtx(),
    probes: {
      computedStyles: {
        selectors: [
          {
            selector: "[data-parity-id='trade-root']",
            matches: [{ styles: { lineHeight: "normal" } }]
          }
        ]
      }
    },
    artifacts: baseArtifacts(),
    policy: config
  });

  assert.equal(result.status, "TOOLING_GAP");
  assert.equal(result.issues.length, 0);
  assert.equal(result.blindSpots[0].reasonCode, "unsupported-style-unit");
});
