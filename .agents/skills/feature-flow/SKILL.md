---
name: "feature-flow"
description: "Invoke explicitly with `$feature-flow` for complex features, significant refactors, or multi-file behavior changes that need an ExecPlan, proposed test coverage, RED-phase failing tests, implementation, and review. Do not use for tiny fixes, doc-only work, or purely local cleanup."
---

# Feature Flow

Use this skill explicitly when a Hyperopen change is large enough to need orchestration instead of a single straight-line implementation.

## Read First

- `/hyperopen/AGENTS.md`
- `/hyperopen/docs/MULTI_AGENT.md`
- `/hyperopen/docs/PLANS.md`
- `/hyperopen/docs/WORK_TRACKING.md`

## Workflow

1. Ensure the work is tracked in `bd` and has an active ExecPlan if the change is complex.
2. Refresh the plan or spec with `spec_writer` when the scope, risks, or acceptance criteria are not already frozen.
3. Spawn `acceptance_test_writer` and `edge_case_test_writer` in parallel to propose complementary coverage only. Wait for both before merging their outputs.
4. Freeze one approved test contract, then spawn `tdd_test_writer` to materialize the failing tests for that contract only.
5. Verify the RED phase with the narrowest relevant command before implementation starts.
6. Spawn `worker` to implement the smallest defensible change that makes the approved tests pass.
7. Spawn `reviewer` after implementation. If the feature is UI-facing or interaction-heavy, also run `browser_debugger` before signoff.
8. Run the required repository gates and return changed files, tests added, commands run, validation results, and remaining risks or blockers.

## Guardrails

- Use exact agent `name` values.
- Parent thread owns orchestration; child agents do not spawn deeper trees.
- Only `worker` edits `/hyperopen/src/**` unless the user explicitly overrides that rule.
- `acceptance_test_writer` and `edge_case_test_writer` propose tests only.
- `tdd_test_writer` owns RED-phase test materialization.

