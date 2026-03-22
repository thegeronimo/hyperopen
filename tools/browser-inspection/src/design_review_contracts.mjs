import {
  PASS_STATUS_VALUES,
  REVIEW_OUTCOME_VALUES,
  RUN_STATUS_VALUES
} from "./design_review/models.mjs";
import { DESIGN_REVIEW_PASS_NAMES } from "./design_review/pass_registry.mjs";

function fail(message) {
  const error = new Error(`DesignReviewContractError: ${message}`);
  error.name = "DesignReviewContractError";
  throw error;
}

export const REQUIRED_PASSES = [...DESIGN_REVIEW_PASS_NAMES];

export const REQUIRED_VIEWPORTS = {
  "review-375": { width: 375, height: 812 },
  "review-768": { width: 768, height: 1024 },
  "review-1280": { width: 1280, height: 900 },
  "review-1440": { width: 1440, height: 900 }
};

function assertObject(value, key) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    fail(`${key} must be an object`);
  }
}

function assertString(value, key) {
  if (typeof value !== "string" || value.trim() === "") {
    fail(`${key} must be a non-empty string`);
  }
}

function assertArray(value, key) {
  if (!Array.isArray(value)) {
    fail(`${key} must be an array`);
  }
}

export function assertDesignReviewConfig(value) {
  assertObject(value, "designReviewConfig");
  assertArray(value.passes, "designReviewConfig.passes");
  assertObject(value.viewports, "designReviewConfig.viewports");
  if (JSON.stringify(value.passes) !== JSON.stringify(REQUIRED_PASSES)) {
    fail(`designReviewConfig.passes must equal ${JSON.stringify(REQUIRED_PASSES)}`);
  }
  const viewportNames = Object.keys(value.viewports || {}).sort();
  const requiredNames = Object.keys(REQUIRED_VIEWPORTS).sort();
  if (JSON.stringify(viewportNames) !== JSON.stringify(requiredNames)) {
    fail(`designReviewConfig.viewports must equal ${JSON.stringify(requiredNames)}`);
  }
  for (const [name, viewport] of Object.entries(value.viewports || {})) {
    assertObject(viewport, `designReviewConfig.viewports.${name}`);
    if (typeof viewport.width !== "number" || Number.isNaN(viewport.width)) {
      fail(`designReviewConfig.viewports.${name}.width must be a number`);
    }
    if (typeof viewport.height !== "number" || Number.isNaN(viewport.height)) {
      fail(`designReviewConfig.viewports.${name}.height must be a number`);
    }
    if (viewport.width !== REQUIRED_VIEWPORTS[name].width) {
      fail(
        `designReviewConfig.viewports.${name}.width must equal ${REQUIRED_VIEWPORTS[name].width}`
      );
    }
    if (viewport.height !== REQUIRED_VIEWPORTS[name].height) {
      fail(
        `designReviewConfig.viewports.${name}.height must equal ${REQUIRED_VIEWPORTS[name].height}`
      );
    }
  }
  assertArray(value.computedStyleKeys || [], "designReviewConfig.computedStyleKeys");
}

export function assertDesignReviewRouting(value) {
  assertObject(value, "designReviewRouting");
  assertArray(value.defaultTargets || [], "designReviewRouting.defaultTargets");
  assertArray(value.targets || [], "designReviewRouting.targets");
  assertArray(value.rules || [], "designReviewRouting.rules");

  for (const [index, target] of (value.targets || []).entries()) {
    assertObject(target, `designReviewRouting.targets[${index}]`);
    assertString(target.id, `designReviewRouting.targets[${index}].id`);
    assertString(target.route, `designReviewRouting.targets[${index}].route`);
    assertString(target.url, `designReviewRouting.targets[${index}].url`);
    assertArray(target.selectors || [], `designReviewRouting.targets[${index}].selectors`);
    assertArray(
      target.referenceDocs || [],
      `designReviewRouting.targets[${index}].referenceDocs`
    );
    assertArray(
      target.workbenchScenes || [],
      `designReviewRouting.targets[${index}].workbenchScenes`
    );
    assertArray(
      target.nativeControlAllowlist || [],
      `designReviewRouting.targets[${index}].nativeControlAllowlist`
    );
  }

  for (const [index, rule] of (value.rules || []).entries()) {
    assertObject(rule, `designReviewRouting.rules[${index}]`);
    assertString(rule.id, `designReviewRouting.rules[${index}].id`);
    assertString(rule.glob, `designReviewRouting.rules[${index}].glob`);
    assertArray(rule.targets || [], `designReviewRouting.rules[${index}].targets`);
  }
}

export function assertDesignReviewSummary(value) {
  assertObject(value, "designReviewSummary");
  assertString(value.runId, "designReviewSummary.runId");
  assertString(value.runDir, "designReviewSummary.runDir");
  assertString(value.runStatus, "designReviewSummary.runStatus");
  if (!RUN_STATUS_VALUES.includes(value.runStatus)) {
    fail(`designReviewSummary.runStatus must be one of ${RUN_STATUS_VALUES.join(", ")}`);
  }
  assertString(value.reviewOutcome, "designReviewSummary.reviewOutcome");
  if (!REVIEW_OUTCOME_VALUES.includes(value.reviewOutcome)) {
    fail(
      `designReviewSummary.reviewOutcome must be one of ${REVIEW_OUTCOME_VALUES.join(", ")}`
    );
  }
  assertString(value.state, "designReviewSummary.state");
  if (!REVIEW_OUTCOME_VALUES.includes(value.state)) {
    fail(`designReviewSummary.state must be one of ${REVIEW_OUTCOME_VALUES.join(", ")}`);
  }
  if (value.state !== value.reviewOutcome) {
    fail("designReviewSummary.state must mirror designReviewSummary.reviewOutcome");
  }
  assertString(value.startedAt, "designReviewSummary.startedAt");
  assertString(value.endedAt, "designReviewSummary.endedAt");
  assertArray(value.passes || [], "designReviewSummary.passes");
  assertArray(value.issues || [], "designReviewSummary.issues");
  assertArray(value.inspectedViewports || [], "designReviewSummary.inspectedViewports");
  assertArray(value.targets || [], "designReviewSummary.targets");
  assertArray(value.targetResults || [], "designReviewSummary.targetResults");
  assertArray(value.blindSpots || [], "designReviewSummary.blindSpots");
  assertArray(value.residualBlindSpots || [], "designReviewSummary.residualBlindSpots");

  for (const [index, viewport] of (value.inspectedViewports || []).entries()) {
    assertObject(viewport, `designReviewSummary.inspectedViewports[${index}]`);
    assertString(viewport.name, `designReviewSummary.inspectedViewports[${index}].name`);
    if (typeof viewport.width !== "number" || Number.isNaN(viewport.width)) {
      fail(`designReviewSummary.inspectedViewports[${index}].width must be a number`);
    }
    if (typeof viewport.height !== "number" || Number.isNaN(viewport.height)) {
      fail(`designReviewSummary.inspectedViewports[${index}].height must be a number`);
    }
  }

  for (const [index, passEntry] of (value.passes || []).entries()) {
    assertObject(passEntry, `designReviewSummary.passes[${index}]`);
    assertString(passEntry.pass, `designReviewSummary.passes[${index}].pass`);
    assertString(passEntry.status, `designReviewSummary.passes[${index}].status`);
    if (!PASS_STATUS_VALUES.includes(passEntry.status)) {
      fail(
        `designReviewSummary.passes[${index}].status must be one of ${PASS_STATUS_VALUES.join(", ")}`
      );
    }
    if (typeof passEntry.issueCount !== "number" || Number.isNaN(passEntry.issueCount)) {
      fail(`designReviewSummary.passes[${index}].issueCount must be a number`);
    }
    assertArray(
      passEntry.evidencePaths || [],
      `designReviewSummary.passes[${index}].evidencePaths`
    );
  }

  for (const [index, issue] of (value.issues || []).entries()) {
    assertObject(issue, `designReviewSummary.issues[${index}]`);
    assertString(issue.id, `designReviewSummary.issues[${index}].id`);
    assertString(issue.fingerprint, `designReviewSummary.issues[${index}].fingerprint`);
    assertString(issue.targetId, `designReviewSummary.issues[${index}].targetId`);
    assertString(issue.ruleCode, `designReviewSummary.issues[${index}].ruleCode`);
    assertString(issue.severity, `designReviewSummary.issues[${index}].severity`);
    assertString(issue.pass, `designReviewSummary.issues[${index}].pass`);
    assertString(issue.route, `designReviewSummary.issues[${index}].route`);
    assertString(issue.viewport, `designReviewSummary.issues[${index}].viewport`);
    assertString(issue.selector, `designReviewSummary.issues[${index}].selector`);
    assertArray(issue.reproSteps || [], `designReviewSummary.issues[${index}].reproSteps`);
    assertString(issue.artifactPath, `designReviewSummary.issues[${index}].artifactPath`);
    assertArray(issue.evidenceRefs || [], `designReviewSummary.issues[${index}].evidenceRefs`);
    assertString(
      issue.observedBehavior,
      `designReviewSummary.issues[${index}].observedBehavior`
    );
    assertString(
      issue.expectedBehavior,
      `designReviewSummary.issues[${index}].expectedBehavior`
    );
    assertString(issue.category, `designReviewSummary.issues[${index}].category`);
  }

  for (const [index, blindSpot] of (value.blindSpots || []).entries()) {
    assertObject(blindSpot, `designReviewSummary.blindSpots[${index}]`);
    assertString(blindSpot.pass, `designReviewSummary.blindSpots[${index}].pass`);
    assertString(blindSpot.targetId, `designReviewSummary.blindSpots[${index}].targetId`);
    assertString(blindSpot.route, `designReviewSummary.blindSpots[${index}].route`);
    assertString(blindSpot.reasonCode, `designReviewSummary.blindSpots[${index}].reasonCode`);
    assertString(blindSpot.message, `designReviewSummary.blindSpots[${index}].message`);
    assertArray(
      blindSpot.evidenceRefs || [],
      `designReviewSummary.blindSpots[${index}].evidenceRefs`
    );
  }

  for (const [index, targetResult] of (value.targetResults || []).entries()) {
    assertObject(targetResult, `designReviewSummary.targetResults[${index}]`);
    assertString(targetResult.id, `designReviewSummary.targetResults[${index}].id`);
    assertString(targetResult.route, `designReviewSummary.targetResults[${index}].route`);
    assertArray(
      targetResult.viewports || [],
      `designReviewSummary.targetResults[${index}].viewports`
    );
    for (const [viewportIndex, viewport] of (targetResult.viewports || []).entries()) {
      assertObject(
        viewport,
        `designReviewSummary.targetResults[${index}].viewports[${viewportIndex}]`
      );
      assertString(
        viewport.name,
        `designReviewSummary.targetResults[${index}].viewports[${viewportIndex}].name`
      );
      if (typeof viewport.width !== "number" || Number.isNaN(viewport.width)) {
        fail(
          `designReviewSummary.targetResults[${index}].viewports[${viewportIndex}].width must be a number`
        );
      }
      if (typeof viewport.height !== "number" || Number.isNaN(viewport.height)) {
        fail(
          `designReviewSummary.targetResults[${index}].viewports[${viewportIndex}].height must be a number`
        );
      }
    }
  }
}

export function assertBrowserQaEvalCorpus(value) {
  assertObject(value, "browserQaEvalCorpus");
  assertArray(value.cases || [], "browserQaEvalCorpus.cases");
  for (const [index, entry] of value.cases.entries()) {
    assertObject(entry, `browserQaEvalCorpus.cases[${index}]`);
    assertString(entry.id, `browserQaEvalCorpus.cases[${index}].id`);
    assertString(entry.sourcePath, `browserQaEvalCorpus.cases[${index}].sourcePath`);
    assertArray(
      entry.expectedCategories || [],
      `browserQaEvalCorpus.cases[${index}].expectedCategories`
    );
    assertObject(entry.report, `browserQaEvalCorpus.cases[${index}].report`);
  }
}

export function isDesignReviewContractError(error) {
  return error?.name === "DesignReviewContractError";
}
