#!/usr/bin/env node
import { browserQaEvalCorpus } from "../evals/browser_qa_cases.mjs";
import { assertBrowserQaEvalCorpus, REQUIRED_VIEWPORTS } from "./design_review_contracts.mjs";
import { DESIGN_REVIEW_PASS_NAMES } from "./design_review/pass_registry.mjs";

const REQUIRED_PASSES = DESIGN_REVIEW_PASS_NAMES;
const REQUIRED_WIDTHS = Object.values(REQUIRED_VIEWPORTS).map((entry) => entry.width);

function passMap(report) {
  return new Map((report.passes || []).map((entry) => [entry.pass, entry]));
}

function graderPassCoverage(caseEntry) {
  const map = passMap(caseEntry.report);
  return REQUIRED_PASSES.every((passName) => map.has(passName));
}

function graderRequiredWidths(caseEntry) {
  const widths = [...(caseEntry.report.inspectedViewports || [])]
    .map((entry) => (typeof entry === "number" ? entry : entry?.width))
    .filter((entry) => typeof entry === "number")
    .sort((a, b) => a - b);
  return JSON.stringify(widths) === JSON.stringify(REQUIRED_WIDTHS);
}

function graderEvidenceAttached(caseEntry) {
  const passesHaveEvidence = (caseEntry.report.passes || []).every(
    (entry) => Array.isArray(entry.evidencePaths) && entry.evidencePaths.length > 0
  );
  const issuesHaveArtifacts = (caseEntry.report.issues || []).every(
    (entry) => typeof entry.artifactPath === "string" && entry.artifactPath.length > 0
  );
  return passesHaveEvidence && issuesHaveArtifacts;
}

function graderFocusReferenced(caseEntry) {
  const interactionPass = passMap(caseEntry.report).get("interaction");
  const issueCategories = new Set((caseEntry.report.issues || []).map((entry) => entry.category));
  return Boolean(interactionPass) && (
    issueCategories.has("focus-regression") ||
    (caseEntry.report.residualBlindSpots || []).some((entry) => String(entry).includes("hover"))
  );
}

function graderNativeControls(caseEntry) {
  if (!caseEntry.expectedCategories.includes("native-control-leak")) {
    return true;
  }
  return (caseEntry.report.issues || []).some((entry) => entry.category === "native-control-leak");
}

function graderExpectedCategories(caseEntry) {
  const categories = new Set((caseEntry.report.issues || []).map((entry) => entry.category));
  return caseEntry.expectedCategories.every((category) => categories.has(category));
}

const graders = [
  ["passCoveragePresent", graderPassCoverage],
  ["requiredWidthsInspected", graderRequiredWidths],
  ["evidenceAttached", graderEvidenceAttached],
  ["hoverFocusChecked", graderFocusReferenced],
  ["nativeControlDetection", graderNativeControls],
  ["labeledIssueDetection", graderExpectedCategories]
];

export function runBrowserQaEval(corpus = browserQaEvalCorpus) {
  assertBrowserQaEvalCorpus(corpus);
  const results = corpus.cases.map((entry) => {
    const checks = graders.map(([name, grader]) => ({
      name,
      ok: grader(entry)
    }));
    return {
      id: entry.id,
      sourcePath: entry.sourcePath,
      ok: checks.every((check) => check.ok),
      checks
    };
  });

  const failing = results.filter((entry) => !entry.ok);
  const payload = {
    corpusSize: corpus.cases.length,
    passing: results.length - failing.length,
    failing: failing.length,
    results
  };
  return payload;
}

function main() {
  const payload = runBrowserQaEval(browserQaEvalCorpus);
  process.stdout.write(`${JSON.stringify(payload, null, 2)}\n`);
  if (payload.failing > 0) {
    process.exitCode = 2;
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}

export { graders, REQUIRED_PASSES, REQUIRED_WIDTHS };
