import test from "node:test";
import assert from "node:assert/strict";
import { enforcePathGate } from "../src/git_state.mjs";
import { mergeTestProposals } from "../src/contracts.mjs";

test("enforcePathGate allows matching exact files and prefixes", () => {
  assert.doesNotThrow(() => {
    enforcePathGate(["docs/exec-plans/active/foo.md", "tmp/multi-agent/x/spec.json"], [
      "docs/exec-plans/active/foo.md",
      "tmp/multi-agent/**"
    ]);
  });
});

test("enforcePathGate rejects disallowed paths", () => {
  assert.throws(() => {
    enforcePathGate(["src/hyperopen/core.cljs"], ["test/**"]);
  }, /disallowed paths/);
});

test("mergeTestProposals rejects overlapping target files", () => {
  const specArtifact = {
    version: 1,
    issueId: "sample-123",
    title: "title",
    summary: "summary",
    activeExecPlanPath: "docs/exec-plans/active/sample.md",
    scope: ["scope"],
    nonGoals: [],
    acceptanceCriteria: ["acceptance"],
    touchedAreas: ["tools/multi-agent/**"],
    browserQaRequired: false,
    targetTestFiles: ["test/foo_test.mjs"],
    validationCommands: ["npm run test:multi-agent"]
  };
  const proposal = {
    version: 1,
    issueId: "sample-123",
    summary: "summary",
    validationCommand: "npm run test:multi-agent",
    targetFiles: ["test/foo_test.mjs"],
    cases: [
      {
        id: "case-1",
        title: "case",
        kind: "acceptance",
        file: "test/foo_test.mjs",
        purpose: "purpose",
        behavior: "behavior"
      }
    ]
  };
  assert.throws(() => {
    mergeTestProposals(
      specArtifact,
      { ...proposal, role: "acceptance_test_writer" },
      {
        ...proposal,
        role: "edge_case_test_writer",
        cases: [{ ...proposal.cases[0], id: "case-2", kind: "edge-case" }]
      }
    );
  }, /overlapping target files/);
});
