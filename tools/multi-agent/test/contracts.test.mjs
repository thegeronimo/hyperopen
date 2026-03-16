import test from "node:test";
import assert from "node:assert/strict";
import { buildDryRunArtifacts } from "../src/manager.mjs";

const sampleIssue = {
  id: "sample-123",
  title: "Add multi-agent orchestration scaffold",
  description: "Synthetic issue used for dry-run artifact tests.",
  created_at: "2026-03-16T00:00:00Z"
};

test("buildDryRunArtifacts returns the expected contract bundle", () => {
  const artifacts = buildDryRunArtifacts({ issue: sampleIssue, repoRoot: "/tmp/hyperopen" });
  assert.equal(artifacts.specArtifact.issueId, sampleIssue.id);
  assert.equal(artifacts.acceptanceProposal.role, "acceptance_test_writer");
  assert.equal(artifacts.edgeProposal.role, "edge_case_test_writer");
  assert.equal(artifacts.approvedContract.issueId, sampleIssue.id);
  assert.equal(artifacts.browser.skipped, true);
});
