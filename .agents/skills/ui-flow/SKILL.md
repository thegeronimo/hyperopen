---
name: "ui-flow"
description: "Invoke explicitly with `$ui-flow` for UI changes under `/hyperopen/src/hyperopen/views/**`, `/hyperopen/src/styles/**`, or interaction flows that need governed browser QA, design validation, and implementation review. Do not use for backend-only work or non-visual refactors."
---

# UI Flow

Use this skill explicitly for UI-facing tasks where design evidence and browser QA are part of completion.

## Read First

- `/hyperopen/AGENTS.md`
- `/hyperopen/docs/FRONTEND.md`
- `/hyperopen/docs/agent-guides/browser-qa.md`
- `/hyperopen/docs/agent-guides/ui-foundations.md`
- `/hyperopen/docs/agent-guides/trading-ui-policy.md`
- `/hyperopen/docs/MULTI_AGENT.md`

## Workflow

1. Gather the relevant UI evidence first. Use `browser_debugger` when the current live behavior, screenshots, DOM state, or browser-only issues need to be established.
2. If the UI direction is unclear, spawn `ui_designer` before implementation and capture the chosen approach in the active ExecPlan when the work is complex.
3. If the UI behavior can be validated with deterministic tests, use `tdd_test_writer` for the RED phase before implementation. If not, carry a precise manual validation plan forward.
4. Spawn `worker` to implement the approved UI change while preserving the established runtime and interaction rules.
5. Spawn `ui_visual_validator` and `reviewer` before signoff. Use `browser_debugger` for the governed PASS / FAIL / BLOCKED browser-QA record.
6. Report the required widths `375`, `768`, `1280`, and `1440`, plus changed files, commands run, validation results, and residual risks or blind spots.

## Guardrails

- Use exact agent `name` values.
- Parent thread owns orchestration and the final PASS / FAIL / BLOCKED summary.
- Do not conclude UI work is complete unless every required browser-QA pass is explicitly accounted for.
- Keep repo-root paths in any committed docs or notes.

