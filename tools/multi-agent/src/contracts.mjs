import { z } from "zod";

export const phaseNames = [
  "spec_writer",
  "acceptance_test_writer.proposal",
  "edge_case_test_writer.proposal",
  "merge_test_contract",
  "acceptance_test_writer.materialize",
  "verify_red_phase",
  "worker",
  "reviewer",
  "browser_debugger",
  "quality_gates"
];

const repoPathSchema = z.string().min(1).refine((value) => !value.startsWith("/"), {
  message: "expected repository-relative path"
});

const commandSchema = z.string().min(1);

export const testCaseSchema = z.object({
  id: z.string().min(1),
  title: z.string().min(1),
  kind: z.enum(["acceptance", "edge-case"]),
  file: repoPathSchema,
  purpose: z.string().min(1),
  behavior: z.string().min(1)
});

export const specArtifactSchema = z.object({
  version: z.literal(1),
  issueId: z.string().min(1),
  title: z.string().min(1),
  summary: z.string().min(1),
  activeExecPlanPath: repoPathSchema,
  scope: z.array(z.string().min(1)).min(1),
  nonGoals: z.array(z.string().min(1)).default([]),
  acceptanceCriteria: z.array(z.string().min(1)).min(1),
  touchedAreas: z.array(z.string().min(1)).min(1),
  browserQaRequired: z.boolean(),
  targetTestFiles: z.array(repoPathSchema).default([]),
  validationCommands: z.array(commandSchema).min(1)
});

export const testProposalSchema = z.object({
  version: z.literal(1),
  issueId: z.string().min(1),
  role: z.enum(["acceptance_test_writer", "edge_case_test_writer"]),
  summary: z.string().min(1),
  targetFiles: z.array(repoPathSchema).min(1),
  validationCommand: commandSchema,
  cases: z.array(testCaseSchema).min(1)
});

export const approvedTestContractSchema = z.object({
  version: z.literal(1),
  issueId: z.string().min(1),
  sourceRoles: z.tuple([
    z.literal("acceptance_test_writer"),
    z.literal("edge_case_test_writer")
  ]),
  activeExecPlanPath: repoPathSchema,
  targetFiles: z.array(repoPathSchema).min(1),
  validationCommand: commandSchema,
  cases: z.array(testCaseSchema).min(1)
});

const followUpSchema = z.object({
  title: z.string().min(1),
  description: z.string().min(1),
  issueType: z.enum(["bug", "feature", "task", "epic", "chore"]).default("task"),
  priority: z.number().int().min(0).max(4).default(2)
});

const reviewFindingSchema = z.object({
  severity: z.enum(["high", "medium", "low"]),
  summary: z.string().min(1),
  file: repoPathSchema.optional(),
  repro: z.string().min(1).optional(),
  observed: z.string().min(1).optional(),
  expected: z.string().min(1).optional(),
  followUp: followUpSchema.optional()
});

export const reviewReportSchema = z.object({
  version: z.literal(1),
  issueId: z.string().min(1),
  verdict: z.enum(["pass", "fail"]),
  summary: z.string().min(1),
  findings: z.array(reviewFindingSchema).default([])
});

const browserPassSchema = z.object({
  pass: z.enum([
    "visual",
    "native-control",
    "styling-consistency",
    "interaction",
    "layout-regression",
    "jank-perf"
  ]),
  status: z.enum(["PASS", "FAIL", "BLOCKED"]),
  notes: z.string().min(1).optional()
});

const browserFindingSchema = z.object({
  severity: z.enum(["high", "medium", "low"]),
  pass: browserPassSchema.shape.pass,
  route: z.string().min(1),
  viewport: z.string().min(1),
  selector: z.string().min(1).optional(),
  repro: z.string().min(1),
  observed: z.string().min(1),
  expected: z.string().min(1),
  artifactPath: z.string().min(1).optional(),
  confidence: z.number().min(0).max(1).optional(),
  followUp: followUpSchema.optional()
});

export const browserReportSchema = z
  .object({
    version: z.literal(1),
    issueId: z.string().min(1),
    required: z.boolean(),
    skipped: z.boolean(),
    overallStatus: z.enum(["PASS", "FAIL", "BLOCKED", "SKIPPED"]),
    summary: z.string().min(1),
    passes: z.array(browserPassSchema).default([]),
    findings: z.array(browserFindingSchema).default([]),
    artifactPaths: z.array(z.string().min(1)).default([])
  })
  .superRefine((value, ctx) => {
    if (value.skipped) {
      if (value.overallStatus !== "SKIPPED") {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "skipped browser reports must use overallStatus=SKIPPED"
        });
      }
      return;
    }
    if (value.required && value.passes.length !== 6) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: "required browser reports must account for all six passes"
      });
    }
  });

export const materializationResultSchema = z.object({
  version: z.literal(1),
  issueId: z.string().min(1),
  summary: z.string().min(1),
  changedFiles: z.array(repoPathSchema).min(1)
});

export const implementationResultSchema = z.object({
  version: z.literal(1),
  issueId: z.string().min(1),
  summary: z.string().min(1),
  changedFiles: z.array(repoPathSchema).min(1)
});

export const phaseResultSchema = z.object({
  phase: z.enum(phaseNames),
  status: z.enum(["completed", "skipped", "failed"]),
  artifactPath: z.string().min(1).optional(),
  notes: z.string().min(1).optional()
});

export const runSummarySchema = z.object({
  version: z.literal(1),
  issueId: z.string().min(1),
  mode: z.enum(["dry-run", "ticket", "resume-ticket"]),
  phases: z.array(phaseResultSchema),
  artifacts: z.record(z.string(), z.string()).default({}),
  followUpIssues: z.array(z.string().min(1)).default([])
});

function unique(values) {
  return [...new Set(values)];
}

export function mergeTestProposals(specArtifact, acceptanceProposal, edgeProposal) {
  const acceptance = testProposalSchema.parse(acceptanceProposal);
  const edge = testProposalSchema.parse(edgeProposal);
  const spec = specArtifactSchema.parse(specArtifact);
  const overlap = acceptance.targetFiles.filter((file) => edge.targetFiles.includes(file));
  if (overlap.length > 0) {
    throw new Error(
      `Cannot merge test proposals with overlapping target files: ${overlap.join(", ")}`
    );
  }
  if (acceptance.validationCommand !== edge.validationCommand) {
    throw new Error(
      `Cannot merge test proposals with conflicting validation commands: ${acceptance.validationCommand} vs ${edge.validationCommand}`
    );
  }
  const caseIds = new Set();
  for (const testCase of [...acceptance.cases, ...edge.cases]) {
    if (caseIds.has(testCase.id)) {
      throw new Error(`Duplicate test case id in merged contract: ${testCase.id}`);
    }
    caseIds.add(testCase.id);
  }
  return approvedTestContractSchema.parse({
    version: 1,
    issueId: spec.issueId,
    sourceRoles: ["acceptance_test_writer", "edge_case_test_writer"],
    activeExecPlanPath: spec.activeExecPlanPath,
    targetFiles: unique([...acceptance.targetFiles, ...edge.targetFiles]),
    validationCommand: acceptance.validationCommand,
    cases: [...acceptance.cases, ...edge.cases]
  });
}
