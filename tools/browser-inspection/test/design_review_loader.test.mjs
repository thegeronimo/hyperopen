import test from "node:test";
import assert from "node:assert/strict";
import { DESIGN_REVIEW_PASS_NAMES } from "../src/design_review/pass_registry.mjs";
import {
  loadDesignReviewConfig,
  loadDesignReviewRouting,
  resolveDesignReviewSelection,
  selectDesignReviewTargetsForChangedFiles
} from "../src/design_review_loader.mjs";

test("design review config locks the required passes and widths", async () => {
  const config = await loadDesignReviewConfig();
  assert.deepEqual(config.passes, DESIGN_REVIEW_PASS_NAMES);
  assert.deepEqual(
    Object.fromEntries(
      Object.entries(config.viewports).map(([name, viewport]) => [name, viewport.width])
    ),
    {
      "review-375": 375,
      "review-768": 768,
      "review-1280": 1280,
      "review-1440": 1440
    }
  );
});

test("design review routing selects shared shell routes for style changes", async () => {
  const routing = await loadDesignReviewRouting();
  const selection = selectDesignReviewTargetsForChangedFiles(["src/styles/main.css"], routing);
  assert.deepEqual(selection.matchedRuleIds, ["styles"]);
  assert.deepEqual(selection.targets.map((target) => target.id), [
    "portfolio-route",
    "trade-route",
    "vaults-route"
  ]);
});

test("resolveDesignReviewSelection honors explicit target ids", async () => {
  const selection = await resolveDesignReviewSelection({
    changedFiles: ["src/styles/main.css"],
    targetIds: ["vault-detail-route"]
  });
  assert.deepEqual(selection.targets.map((target) => target.id), ["vault-detail-route"]);
});
