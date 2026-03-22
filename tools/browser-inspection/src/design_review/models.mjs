import { createHash } from "node:crypto";

export const PASS_STATUS = Object.freeze({
  PASS: "PASS",
  FAIL: "FAIL",
  NOT_APPLICABLE: "NOT_APPLICABLE",
  CONFIG_GAP: "CONFIG_GAP",
  TOOLING_GAP: "TOOLING_GAP",
  ENVIRONMENT_LIMITATION: "ENVIRONMENT_LIMITATION"
});

export const PASS_STATUS_VALUES = Object.freeze(Object.values(PASS_STATUS));

export const REVIEW_OUTCOME = Object.freeze({
  PASS: "PASS",
  FAIL: "FAIL",
  BLOCKED: "BLOCKED"
});

export const REVIEW_OUTCOME_VALUES = Object.freeze(Object.values(REVIEW_OUTCOME));

export const RUN_STATUS = Object.freeze({
  COMPLETED: "completed",
  FAILED: "failed"
});

export const RUN_STATUS_VALUES = Object.freeze(Object.values(RUN_STATUS));

export const BLOCKING_PASS_STATUSES = new Set([
  PASS_STATUS.CONFIG_GAP,
  PASS_STATUS.TOOLING_GAP,
  PASS_STATUS.ENVIRONMENT_LIMITATION
]);

function fingerprintFor(parts) {
  return createHash("sha1")
    .update(parts.filter(Boolean).join("|"))
    .digest("hex");
}

function uniqueBy(list, keyFn) {
  const seen = new Set();
  return (list || []).filter((entry) => {
    const key = keyFn(entry);
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

export function compactEvidenceRefs(evidenceRefs = []) {
  return uniqueBy(
    evidenceRefs.filter((entry) => entry?.path),
    (entry) => `${entry.kind || "file"}:${entry.path}`
  );
}

export function makeEvidenceRef(kind, filePath) {
  if (!filePath) {
    return null;
  }
  return {
    kind,
    path: filePath
  };
}

export function primaryEvidencePath(evidenceRefs = []) {
  return compactEvidenceRefs(evidenceRefs)[0]?.path || "";
}

export function makeIssue({
  passName,
  target,
  targetId = target?.id,
  route = target?.route,
  viewportName,
  severity,
  selector,
  evidenceRefs = [],
  artifactPath,
  observed,
  expected,
  category,
  ruleCode,
  reproSteps = []
}) {
  const refs = compactEvidenceRefs(evidenceRefs);
  const fingerprint = fingerprintFor([
    passName,
    targetId,
    route,
    viewportName,
    ruleCode,
    selector,
    category,
    observed,
    expected
  ]);

  return {
    id: `${passName}:${targetId}:${viewportName}:${fingerprint.slice(0, 12)}`,
    fingerprint,
    targetId,
    ruleCode,
    severity,
    pass: passName,
    route,
    viewport: viewportName,
    selector,
    reproSteps,
    artifactPath: artifactPath || primaryEvidencePath(refs),
    evidenceRefs: refs,
    observedBehavior: observed,
    expectedBehavior: expected,
    category
  };
}

export function makeBlindSpot({
  passName,
  target,
  targetId = target?.id,
  route = target?.route,
  viewportName = null,
  reasonCode,
  message,
  evidenceRefs = []
}) {
  const refs = compactEvidenceRefs(evidenceRefs);
  const fingerprint = fingerprintFor([
    "blind-spot",
    passName,
    targetId,
    route,
    viewportName,
    reasonCode,
    message
  ]);

  return {
    id: `blind-spot:${fingerprint.slice(0, 12)}`,
    fingerprint,
    pass: passName,
    targetId,
    route,
    viewport: viewportName,
    reasonCode,
    message,
    evidenceRefs: refs
  };
}

export function blindSpotLine(entry) {
  const scope = [entry.route, entry.viewport].filter(Boolean).join(" / ");
  return scope ? `${scope}: ${entry.message}` : entry.message;
}

export function makePassResult({
  passName,
  status,
  summary,
  issues = [],
  blindSpots = [],
  evidenceRefs = [],
  statusReason
}) {
  const refs = compactEvidenceRefs(evidenceRefs);
  return {
    pass: passName,
    status,
    summary,
    statusReason: statusReason || undefined,
    blockedReason: BLOCKING_PASS_STATUSES.has(status) ? statusReason || summary : undefined,
    issues,
    blindSpots,
    issueCount: issues.length,
    evidenceRefs: refs,
    evidencePaths: refs.map((entry) => entry.path)
  };
}

export function makeViewportSpec(name, viewport) {
  return {
    name,
    width: viewport.width,
    height: viewport.height
  };
}

export function makeTargetResult(target) {
  return {
    id: target.id,
    route: target.route,
    url: target.url,
    referenceDocs: target.referenceDocs,
    workbenchScenes: target.workbenchScenes,
    viewports: []
  };
}

export function makeTargetViewportResult({
  viewportName,
  viewport,
  snapshotPath = null,
  screenshotPath = null,
  probePaths = {},
  passes = []
}) {
  return {
    name: viewportName,
    width: viewport.width,
    height: viewport.height,
    snapshotPath,
    screenshotPath,
    probePaths,
    passes: passes.map((entry) => ({
      pass: entry.pass,
      status: entry.status,
      issueCount: entry.issueCount
    }))
  };
}

export function uniqueBlindSpots(blindSpots = []) {
  return uniqueBy(
    blindSpots,
    (entry) =>
      entry.id ||
      [
        entry.pass,
        entry.targetId,
        entry.route,
        entry.viewport,
        entry.reasonCode,
        entry.message
      ].join("|")
  );
}

export function uniqueStrings(values = []) {
  return [...new Set(values.filter(Boolean))];
}

export function aggregatePassStatus(entries = []) {
  const statuses = new Set(entries.map((entry) => entry.status));
  if (statuses.has(PASS_STATUS.FAIL)) {
    return PASS_STATUS.FAIL;
  }
  if (statuses.has(PASS_STATUS.CONFIG_GAP)) {
    return PASS_STATUS.CONFIG_GAP;
  }
  if (statuses.has(PASS_STATUS.TOOLING_GAP)) {
    return PASS_STATUS.TOOLING_GAP;
  }
  if (statuses.has(PASS_STATUS.ENVIRONMENT_LIMITATION)) {
    return PASS_STATUS.ENVIRONMENT_LIMITATION;
  }
  if (statuses.has(PASS_STATUS.PASS)) {
    return PASS_STATUS.PASS;
  }
  if (statuses.has(PASS_STATUS.NOT_APPLICABLE)) {
    return PASS_STATUS.NOT_APPLICABLE;
  }
  return PASS_STATUS.TOOLING_GAP;
}

export function aggregateSummaryState(passEntries = []) {
  if (passEntries.some((entry) => entry.status === PASS_STATUS.FAIL)) {
    return REVIEW_OUTCOME.FAIL;
  }
  if (passEntries.some((entry) => BLOCKING_PASS_STATUSES.has(entry.status))) {
    return REVIEW_OUTCOME.BLOCKED;
  }
  return REVIEW_OUTCOME.PASS;
}

export function aggregatePassResults(passRegistry = [], passEntries = []) {
  return passRegistry.map((definition) => {
    const matches = passEntries.filter((entry) => entry.pass === definition.name);
    const status = aggregatePassStatus(matches);
    const summary =
      matches.find((entry) => entry.status === status)?.summary ||
      matches.find((entry) => entry.summary)?.summary ||
      "No results were recorded.";

    return {
      pass: definition.name,
      status,
      summary,
      issueCount: matches.reduce((sum, entry) => sum + (entry.issueCount || 0), 0),
      blockedReason: BLOCKING_PASS_STATUSES.has(status) ? summary : undefined,
      evidencePaths: uniqueStrings(matches.flatMap((entry) => entry.evidencePaths || []))
    };
  });
}

export function makeSummary({
  runRef,
  startedAt,
  endedAt,
  reviewSpecPath,
  inspectedViewports,
  targets,
  targetResults,
  passes,
  issues,
  blindSpots
}) {
  const normalizedBlindSpots = uniqueBlindSpots(blindSpots);
  const reviewOutcome = aggregateSummaryState(passes);

  return {
    runId: runRef.runId,
    runDir: runRef.runDir,
    runStatus: RUN_STATUS.COMPLETED,
    reviewOutcome,
    state: reviewOutcome,
    startedAt,
    endedAt,
    reviewSpecPath,
    inspectedViewports,
    targets,
    targetResults,
    passes,
    issues,
    blindSpots: normalizedBlindSpots,
    residualBlindSpots: uniqueStrings(normalizedBlindSpots.map(blindSpotLine)).sort()
  };
}
