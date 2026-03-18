---
name: "bug-flow"
description: "Invoke explicitly with `$bug-flow` for unclear bugs, regressions, or failure investigations that need structured diagnosis, regression tests, the smallest fix, and findings-first review. Do not use for obvious one-line fixes or planned feature work."
---

# Bug Flow

Use this skill explicitly when the failure mode is not already fully understood and the work needs a diagnosis-first workflow.

## Read First

- `/hyperopen/AGENTS.md`
- `/hyperopen/docs/MULTI_AGENT.md`
- `/hyperopen/docs/PLANS.md`
- `/hyperopen/docs/WORK_TRACKING.md`

## Workflow

1. Ensure the work is tracked in `bd`. If the bug is broad or risky, keep an active ExecPlan current while investigating.
2. Spawn `debugger` to isolate likely root cause from code, logs, and local evidence.
3. If the bug is UI-facing, browser-dependent, or needs live reproduction evidence, also spawn `browser_debugger` in parallel. Wait for both before deciding on the fix path.
4. Summarize the agreed failure mode, exact reproduction steps, and the smallest acceptable behavioral correction.
5. Spawn `tdd_test_writer` for a deterministic regression test when the behavior can be exercised automatically. If the bug is not a good automated-test candidate, record that explicitly and carry the manual validation plan forward.
6. Spawn `worker` for the smallest fix that satisfies the regression contract.
7. Spawn `reviewer` after the fix. Re-run `browser_debugger` when browser evidence is needed for final validation.
8. Run the required repository gates and return reproduction steps, root cause, changed files, tests added, validation results, and remaining risks.

## Guardrails

- Use exact agent `name` values.
- Parent thread owns orchestration and synthesis.
- Prefer regression tests over broad rewrites.
- Keep the fix scoped to the proven failure mode unless the user broadens scope.

