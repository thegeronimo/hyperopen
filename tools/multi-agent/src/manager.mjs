import { execFile } from "node:child_process";
import path from "node:path";
import { promisify } from "node:util";
import {
  approvedTestContractSchema,
  browserReportSchema,
  implementationResultSchema,
  redPhaseResultSchema,
  mergeTestProposals,
  phaseNames,
  reviewReportSchema,
  runSummarySchema,
  specArtifactSchema,
  testProposalSchema
} from "./contracts.mjs";
import { runStructuredAgentPhase } from "./agent_runner.mjs";
import { createFollowUpIssue, readIssue } from "./bd.mjs";
import { loadAllRoleConfigs, validateProjectConfig } from "./codex_roles.mjs";
import { appendExecPlanOutcome, appendExecPlanProgress } from "./exec_plan.mjs";
import {
  assertCleanWorktree,
  captureWorktreeSnapshot,
  diffSnapshots,
  enforcePathGate
} from "./git_state.mjs";
import { ensureDir, readJsonIfExists, writeJson } from "./io.mjs";
import {
  buildArtifactPaths,
  buildDefaultExecPlanPath,
  ensureRepoRelative,
  repoRelative
} from "./paths.mjs";

const execFileAsync = promisify(execFile);

function nowIso() {
  return new Date().toISOString().replace(/\.\d{3}Z$/, "Z");
}

function repoIssueSummary(issue) {
  return [
    `Issue id: ${issue.id}`,
    `Title: ${issue.title}`,
    `Description: ${issue.description || ""}`.trim()
  ].join("\n");
}

function buildPromptPreamble({ issue, repoRoot, artifactPaths }) {
  return [
    "You are working inside the Hyperopen repository.",
    `Repository root: ${repoRoot}`,
    repoIssueSummary(issue),
    "Read AGENTS.md first, then follow the linked canonical docs.",
    `Artifact directory: ${artifactPaths.runDir}`
  ].join("\n\n");
}

function buildSpecPrompt({ issue, repoRoot, artifactPaths, execPlanPath }) {
  return `${buildPromptPreamble({ issue, repoRoot, artifactPaths })}

Create or refresh the active ExecPlan at ${execPlanPath}.

Follow these documents:
- /hyperopen/docs/MULTI_AGENT.md
- /hyperopen/.agents/PLANS.md
- /hyperopen/docs/PLANS.md
- /hyperopen/docs/WORK_TRACKING.md

Touch only:
- ${execPlanPath}
- tmp/multi-agent/**

Return structured JSON with:
- a concise summary
- scope
- non-goals
- acceptance criteria
- touched areas
- whether browser QA is required
- validation commands
- the active ExecPlan path
`;
}

function buildProposalPrompt({ issue, repoRoot, artifactPaths, specArtifact, roleName }) {
  const emphasis =
    roleName === "acceptance_test_writer"
      ? "Propose failing acceptance and integration tests only."
      : "Propose adversarial, invariant, and boundary-case tests only.";
  return `${buildPromptPreamble({ issue, repoRoot, artifactPaths })}

Use this frozen spec artifact:
${JSON.stringify(specArtifact, null, 2)}

${emphasis}

Do not edit tracked files. The manager will write the proposal artifact.

Return structured JSON with:
- summary
- target test files
- one validation command that will exercise the proposed tests
- cases with ids, target files, purposes, and behaviors
`;
}

function buildRedPhasePrompt({ issue, repoRoot, artifactPaths, approvedContract }) {
  return `${buildPromptPreamble({ issue, repoRoot, artifactPaths })}

Use this approved test contract:
${JSON.stringify(approvedContract, null, 2)}

Materialize the failing tests from the frozen contract.

Touch only:
- ${approvedContract.activeExecPlanPath}
- ${approvedContract.targetFiles.join("\n- ")}
- tmp/multi-agent/**

Do not edit /hyperopen/src/**.

Return structured JSON with:
- a short summary
- the exact test files you changed
`;
}

function buildWorkerPrompt({ issue, repoRoot, artifactPaths, specArtifact, approvedContract }) {
  return `${buildPromptPreamble({ issue, repoRoot, artifactPaths })}

Use this spec artifact:
${JSON.stringify(specArtifact, null, 2)}

Use this frozen approved test contract:
${JSON.stringify(approvedContract, null, 2)}

Implement the smallest defensible change that makes the approved tests pass.

Touch only:
- src/**
- ${approvedContract.activeExecPlanPath}
- ${approvedContract.targetFiles.join("\n- ")}
- tmp/multi-agent/**

Return structured JSON with:
- a short summary
- the changed files
`;
}

function buildReviewerPrompt({ issue, repoRoot, artifactPaths, specArtifact, approvedContract }) {
  return `${buildPromptPreamble({ issue, repoRoot, artifactPaths })}

Use this spec artifact:
${JSON.stringify(specArtifact, null, 2)}

Use this approved test contract:
${JSON.stringify(approvedContract, null, 2)}

Review the current working tree in read-only mode. Lead with findings. Leave no tracked-file diff.

Return structured JSON with:
- verdict
- summary
- findings with severity, summary, optional repo-relative file path, repro, observed, expected, and optional follow-up issue proposal
`;
}

function buildBrowserPrompt({ issue, repoRoot, artifactPaths, specArtifact }) {
  return `${buildPromptPreamble({ issue, repoRoot, artifactPaths })}

Use this spec artifact:
${JSON.stringify(specArtifact, null, 2)}

Follow:
- /hyperopen/docs/agent-guides/browser-qa.md
- /hyperopen/docs/runbooks/browser-live-inspection.md

If browser QA is not required, return a structured SKIPPED report with an explicit reason.

If browser QA is required:
- account for visual, native-control, styling-consistency, interaction, layout-regression, and jank-perf
- use the existing browser-inspection MCP server
- do not edit application code

Return structured JSON with:
- required/skipped flags
- overall status
- summary
- per-pass PASS/FAIL/BLOCKED entries
- findings with evidence fields
- artifact paths
`;
}

async function runShellCommand(repoRoot, command) {
  return execFileAsync("/bin/bash", ["-lc", command], {
    cwd: repoRoot,
    maxBuffer: 1024 * 1024 * 20
  });
}

async function verifyCommandFails(repoRoot, command) {
  try {
    await runShellCommand(repoRoot, command);
  } catch (error) {
    return {
      ok: true,
      exitCode: error.code,
      stdout: error.stdout || "",
      stderr: error.stderr || ""
    };
  }
  throw new Error(`Expected command to fail before implementation, but it passed: ${command}`);
}

async function verifyCommandPasses(repoRoot, command) {
  const result = await runShellCommand(repoRoot, command);
  return {
    ok: true,
    exitCode: 0,
    stdout: result.stdout || "",
    stderr: result.stderr || ""
  };
}

async function runQualityGates(repoRoot) {
  const commands = ["npm run check", "npm test", "npm run test:websocket"];
  for (const command of commands) {
    await runShellCommand(repoRoot, command);
  }
  return commands;
}

function artifactMap(artifactPaths, repoRoot) {
  return Object.fromEntries(
    Object.entries(artifactPaths).map(([key, absolutePath]) => [key, repoRelative(repoRoot, absolutePath)])
  );
}

async function appendProgress(execPlanAbsolutePath, message) {
  const line = `- [x] (${nowIso()}) ${message}`;
  await appendExecPlanProgress(execPlanAbsolutePath, line);
}

async function maybeCreateFollowUps(repoRoot, issueId, report) {
  const followUps = [];
  const findings = report.findings || [];
  for (const finding of findings) {
    if (!finding.followUp) {
      continue;
    }
    const createdId = await createFollowUpIssue(repoRoot, issueId, finding.followUp);
    followUps.push(createdId);
  }
  return followUps;
}

async function runPhaseWithGate({
  repoRoot,
  roleName,
  prompt,
  outputSchema,
  includeBrowserMcp = false,
  beforeSnapshot,
  allowedPaths
}) {
  const result = await runStructuredAgentPhase({
    repoRoot,
    roleName,
    prompt,
    outputSchema,
    includeBrowserMcp
  });
  const afterSnapshot = await captureWorktreeSnapshot(repoRoot);
  const changedPaths = diffSnapshots(beforeSnapshot, afterSnapshot);
  enforcePathGate(changedPaths, allowedPaths);
  return { result, afterSnapshot, changedPaths };
}

export function buildDryRunArtifacts({ issue, repoRoot }) {
  const artifactPaths = buildArtifactPaths(repoRoot, issue.id);
  const execPlanPath = buildDefaultExecPlanPath(issue);
  const specArtifact = specArtifactSchema.parse({
    version: 1,
    issueId: issue.id,
    title: issue.title,
    summary: issue.description || issue.title,
    activeExecPlanPath: execPlanPath,
    scope: ["Define repo-local multi-agent roles, artifacts, and orchestration."],
    nonGoals: ["Do not add CI PR review automation in milestone 1."],
    acceptanceCriteria: [
      "Dry-run produces structured artifacts under tmp/multi-agent/<bd-id>/.",
      "Real runs enforce role-specific path gates and browser QA skip semantics."
    ],
    touchedAreas: [
      "docs/MULTI_AGENT.md",
      ".codex/config.toml",
      ".codex/agents/*.toml",
      "tools/multi-agent/**"
    ],
    browserQaRequired: false,
    targetTestFiles: ["tools/multi-agent/test/cli_contract.test.mjs"],
    validationCommands: ["npm run test:multi-agent"]
  });
  const acceptanceProposal = testProposalSchema.parse({
    version: 1,
    issueId: issue.id,
    role: "acceptance_test_writer",
    summary: "Propose manager dry-run and CLI contract coverage.",
    targetFiles: ["tools/multi-agent/test/cli_contract.test.mjs"],
    validationCommand: "npm run test:multi-agent",
    cases: [
      {
        id: "acceptance-dry-run-artifacts",
        title: "dry-run writes the expected artifact bundle",
        kind: "acceptance",
        file: "tools/multi-agent/test/cli_contract.test.mjs",
        purpose: "Prove the manager writes a dry-run spec, proposals, contract, and summary.",
        behavior: "Running the dry-run path with a sample issue returns structured artifacts."
      }
    ]
  });
  const edgeProposal = testProposalSchema.parse({
    version: 1,
    issueId: issue.id,
    role: "edge_case_test_writer",
    summary: "Propose path-gate and overlap-rejection coverage.",
    targetFiles: ["tools/multi-agent/test/gates.test.mjs"],
    validationCommand: "npm run test:multi-agent",
    cases: [
      {
        id: "edge-overlap-rejected",
        title: "merge rejects overlapping target files",
        kind: "edge-case",
        file: "tools/multi-agent/test/gates.test.mjs",
        purpose: "Protect independent test-design ownership.",
        behavior: "The manager fails closed when both proposal roles claim the same file."
      }
    ]
  });
  const approvedContract = mergeTestProposals(specArtifact, acceptanceProposal, edgeProposal);
  const redPhase = redPhaseResultSchema.parse({
    version: 1,
    issueId: issue.id,
    summary: "Dry-run placeholder RED-phase artifact.",
    changedFiles: ["tools/multi-agent/test/cli_contract.test.mjs", "tools/multi-agent/test/gates.test.mjs"]
  });
  const implementation = implementationResultSchema.parse({
    version: 1,
    issueId: issue.id,
    summary: "Dry-run placeholder implementation artifact.",
    changedFiles: ["tools/multi-agent/src/manager.mjs"]
  });
  const review = reviewReportSchema.parse({
    version: 1,
    issueId: issue.id,
    verdict: "pass",
    summary: "Dry-run mode emits no review findings.",
    findings: []
  });
  const browser = browserReportSchema.parse({
    version: 1,
    issueId: issue.id,
    required: false,
    skipped: true,
    overallStatus: "SKIPPED",
    summary: "Dry-run sample issue does not require browser QA.",
    passes: [],
    findings: [],
    artifactPaths: []
  });
  return {
    artifactPaths,
    specArtifact,
    acceptanceProposal,
    edgeProposal,
    approvedContract,
    redPhase,
    implementation,
    review,
    browser
  };
}

export async function runDryRun({ repoRoot, issue }) {
  await validateProjectConfig(repoRoot);
  await loadAllRoleConfigs(repoRoot);
  const artifacts = buildDryRunArtifacts({ issue, repoRoot });
  await ensureDir(artifacts.artifactPaths.runDir);
  await writeJson(artifacts.artifactPaths.spec, artifacts.specArtifact);
  await writeJson(artifacts.artifactPaths.acceptanceProposal, artifacts.acceptanceProposal);
  await writeJson(artifacts.artifactPaths.edgeProposal, artifacts.edgeProposal);
  await writeJson(artifacts.artifactPaths.approvedContract, artifacts.approvedContract);
  await writeJson(artifacts.artifactPaths.redPhase, artifacts.redPhase);
  await writeJson(artifacts.artifactPaths.implementation, artifacts.implementation);
  await writeJson(artifacts.artifactPaths.review, artifacts.review);
  await writeJson(artifacts.artifactPaths.browser, artifacts.browser);
  const summary = runSummarySchema.parse({
    version: 1,
    issueId: issue.id,
    mode: "dry-run",
    phases: phaseNames.map((phase) => ({ phase, status: "completed" })),
    artifacts: artifactMap(artifacts.artifactPaths, repoRoot),
    followUpIssues: []
  });
  await writeJson(artifacts.artifactPaths.summary, summary);
  return {
    issueId: issue.id,
    dryRun: true,
    artifacts: summary.artifacts
  };
}

async function resolveCachedArtifact(filePath, schema) {
  const existing = await readJsonIfExists(filePath);
  if (!existing) {
    return null;
  }
  return schema.parse(existing);
}

export async function runTicket({ repoRoot, issue, resume = false }) {
  await validateProjectConfig(repoRoot);
  const artifactPaths = buildArtifactPaths(repoRoot, issue.id);
  await ensureDir(artifactPaths.runDir);
  if (!resume) {
    await assertCleanWorktree(repoRoot);
  }
  const phases = [];
  const followUpIssues = [];
  const execPlanPath =
    (await resolveCachedArtifact(artifactPaths.spec, specArtifactSchema))?.activeExecPlanPath ||
    buildDefaultExecPlanPath(issue);
  const execPlanAbsolutePath = path.join(repoRoot, execPlanPath);

  let currentSnapshot = await captureWorktreeSnapshot(repoRoot);
  let specArtifact = await resolveCachedArtifact(artifactPaths.spec, specArtifactSchema);
  if (!specArtifact) {
    const specPhase = await runPhaseWithGate({
      repoRoot,
      roleName: "spec_writer",
      prompt: buildSpecPrompt({ issue, repoRoot, artifactPaths, execPlanPath }),
      outputSchema: specArtifactSchema,
      beforeSnapshot: currentSnapshot,
      allowedPaths: [execPlanPath, "tmp/multi-agent/**"]
    });
    specArtifact = specArtifactSchema.parse(specPhase.result);
    await writeJson(artifactPaths.spec, specArtifact);
    currentSnapshot = specPhase.afterSnapshot;
    phases.push({ phase: "spec_writer", status: "completed", artifactPath: repoRelative(repoRoot, artifactPaths.spec) });
    await appendProgress(execPlanAbsolutePath, "Spec writer refreshed the ExecPlan and emitted the spec artifact.");
  }

  let acceptanceProposal = await resolveCachedArtifact(
    artifactPaths.acceptanceProposal,
    testProposalSchema
  );
  if (!acceptanceProposal) {
    const proposalPhase = await runPhaseWithGate({
      repoRoot,
      roleName: "acceptance_test_writer",
      prompt: buildProposalPrompt({
        issue,
        repoRoot,
        artifactPaths,
        specArtifact,
        roleName: "acceptance_test_writer"
      }),
      outputSchema: testProposalSchema,
      beforeSnapshot: currentSnapshot,
      allowedPaths: []
    });
    acceptanceProposal = testProposalSchema.parse(proposalPhase.result);
    await writeJson(artifactPaths.acceptanceProposal, acceptanceProposal);
    currentSnapshot = proposalPhase.afterSnapshot;
    phases.push({
      phase: "acceptance_test_writer.proposal",
      status: "completed",
      artifactPath: repoRelative(repoRoot, artifactPaths.acceptanceProposal)
    });
  }

  let edgeProposal = await resolveCachedArtifact(artifactPaths.edgeProposal, testProposalSchema);
  if (!edgeProposal) {
    const proposalPhase = await runPhaseWithGate({
      repoRoot,
      roleName: "edge_case_test_writer",
      prompt: buildProposalPrompt({
        issue,
        repoRoot,
        artifactPaths,
        specArtifact,
        roleName: "edge_case_test_writer"
      }),
      outputSchema: testProposalSchema,
      beforeSnapshot: currentSnapshot,
      allowedPaths: []
    });
    edgeProposal = testProposalSchema.parse(proposalPhase.result);
    await writeJson(artifactPaths.edgeProposal, edgeProposal);
    currentSnapshot = proposalPhase.afterSnapshot;
    phases.push({
      phase: "edge_case_test_writer.proposal",
      status: "completed",
      artifactPath: repoRelative(repoRoot, artifactPaths.edgeProposal)
    });
  }

  let approvedContract = await resolveCachedArtifact(
    artifactPaths.approvedContract,
    approvedTestContractSchema
  );
  if (!approvedContract) {
    approvedContract = mergeTestProposals(specArtifact, acceptanceProposal, edgeProposal);
    await writeJson(artifactPaths.approvedContract, approvedContract);
    phases.push({
      phase: "merge_test_contract",
      status: "completed",
      artifactPath: repoRelative(repoRoot, artifactPaths.approvedContract)
    });
  }

  let redPhaseResult = await resolveCachedArtifact(
    artifactPaths.redPhase,
    redPhaseResultSchema
  );
  if (!redPhaseResult) {
    const redPhase = await runPhaseWithGate({
      repoRoot,
      roleName: "tdd_test_writer",
      prompt: buildRedPhasePrompt({ issue, repoRoot, artifactPaths, approvedContract }),
      outputSchema: redPhaseResultSchema,
      beforeSnapshot: currentSnapshot,
      allowedPaths: [approvedContract.activeExecPlanPath, ...approvedContract.targetFiles, "tmp/multi-agent/**"]
    });
    redPhaseResult = redPhaseResultSchema.parse(redPhase.result);
    await writeJson(artifactPaths.redPhase, redPhaseResult);
    currentSnapshot = redPhase.afterSnapshot;
    phases.push({
      phase: "tdd_test_writer.red_phase",
      status: "completed",
      artifactPath: repoRelative(repoRoot, artifactPaths.redPhase)
    });
    await verifyCommandFails(repoRoot, approvedContract.validationCommand);
    phases.push({ phase: "verify_red_phase", status: "completed" });
  }

  let implementationResult = await resolveCachedArtifact(
    artifactPaths.implementation,
    implementationResultSchema
  );
  if (!implementationResult) {
    const workerPhase = await runPhaseWithGate({
      repoRoot,
      roleName: "worker",
      prompt: buildWorkerPrompt({ issue, repoRoot, artifactPaths, specArtifact, approvedContract }),
      outputSchema: implementationResultSchema,
      beforeSnapshot: currentSnapshot,
      allowedPaths: ["src/**", approvedContract.activeExecPlanPath, ...approvedContract.targetFiles, "tmp/multi-agent/**"]
    });
    implementationResult = implementationResultSchema.parse(workerPhase.result);
    await writeJson(artifactPaths.implementation, implementationResult);
    currentSnapshot = workerPhase.afterSnapshot;
    phases.push({
      phase: "worker",
      status: "completed",
      artifactPath: repoRelative(repoRoot, artifactPaths.implementation)
    });
    await verifyCommandPasses(repoRoot, approvedContract.validationCommand);
    await appendProgress(execPlanAbsolutePath, "Worker implemented the frozen contract and restored the targeted validation command to green.");
  }

  let reviewReport = await resolveCachedArtifact(artifactPaths.review, reviewReportSchema);
  let browserReport = await resolveCachedArtifact(artifactPaths.browser, browserReportSchema);
  if (!reviewReport || !browserReport) {
    const [reviewPhase, browserPhase] = await Promise.all([
      reviewReport
        ? Promise.resolve({ result: reviewReport, afterSnapshot: currentSnapshot })
        : runPhaseWithGate({
            repoRoot,
            roleName: "reviewer",
            prompt: buildReviewerPrompt({
              issue,
              repoRoot,
              artifactPaths,
              specArtifact,
              approvedContract
            }),
            outputSchema: reviewReportSchema,
            beforeSnapshot: currentSnapshot,
            allowedPaths: []
          }),
      browserReport
        ? Promise.resolve({ result: browserReport, afterSnapshot: currentSnapshot })
        : runPhaseWithGate({
            repoRoot,
            roleName: "browser_debugger",
            prompt: buildBrowserPrompt({ issue, repoRoot, artifactPaths, specArtifact }),
            outputSchema: browserReportSchema,
            includeBrowserMcp: true,
            beforeSnapshot: currentSnapshot,
            allowedPaths: ["tmp/browser-inspection/**", "tmp/multi-agent/**", "docs/qa/**"]
          })
    ]);
    reviewReport = reviewReportSchema.parse(reviewPhase.result);
    browserReport = browserReportSchema.parse(browserPhase.result);
    await writeJson(artifactPaths.review, reviewReport);
    await writeJson(artifactPaths.browser, browserReport);
    phases.push({
      phase: "reviewer",
      status: "completed",
      artifactPath: repoRelative(repoRoot, artifactPaths.review)
    });
    phases.push({
      phase: "browser_debugger",
      status: "completed",
      artifactPath: repoRelative(repoRoot, artifactPaths.browser)
    });
  }

  followUpIssues.push(...(await maybeCreateFollowUps(repoRoot, issue.id, reviewReport)));
  followUpIssues.push(...(await maybeCreateFollowUps(repoRoot, issue.id, browserReport)));

  const gateCommands = await runQualityGates(repoRoot);
  phases.push({
    phase: "quality_gates",
    status: "completed",
    notes: gateCommands.join(" && ")
  });

  await appendExecPlanOutcome(
    execPlanAbsolutePath,
    `Manager summary on ${nowIso()}: the multi-agent ticket completed local phase gating, reviewer/browser artifacts were recorded under ${repoRelative(
      repoRoot,
      artifactPaths.runDir
    )}, and follow-up issues created were ${followUpIssues.length > 0 ? followUpIssues.join(", ") : "none"}.`
  );

  const summary = runSummarySchema.parse({
    version: 1,
    issueId: issue.id,
    mode: resume ? "resume-ticket" : "ticket",
    phases,
    artifacts: artifactMap(artifactPaths, repoRoot),
    followUpIssues
  });
  await writeJson(artifactPaths.summary, summary);
  return summary;
}

export async function loadIssueForRun({ repoRoot, issueId, issueOverride = null }) {
  return issueOverride || readIssue(repoRoot, issueId);
}
