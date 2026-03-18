---
owner: platform
status: canonical
last_reviewed: 2026-03-18
review_cycle_days: 90
source_of_truth: true
---

# Multi-Agent Workflow

## Purpose

Describe the actual Hyperopen multi-agent workflow without duplicating the full imperative steps that already live in the workflow skills and runtime code. The executable workflow surfaces are the repo skills under `/hyperopen/.agents/skills/**`, the project agent config under `/hyperopen/.codex/config.toml`, the checked-in custom agents under `/hyperopen/.codex/agents/*.toml`, and the manager under `/hyperopen/tools/multi-agent/`.

## Scope And Precedence

- This document governs how Hyperopen expects multi-agent orchestration to work.
- `/hyperopen/AGENTS.md` is the root operating contract.
- `/hyperopen/docs/PLANS.md` is the public planning entry point for ExecPlans.
- `/hyperopen/docs/WORK_TRACKING.md` remains the source of truth for `bd` workflow and handoff.
- If the runtime code and prose docs drift, fix the runtime or clearly mark compatibility behavior; do not document two equal “canonical” flows.

## Workflow Entry Points

- `$feature-flow`: explicit-only workflow skill for complex features and significant refactors.
- `$bug-flow`: explicit-only workflow skill for diagnosis-first bug work.
- `$ui-flow`: explicit-only workflow skill for UI-facing work that requires governed browser QA.

These workflow skills are explicit-only by policy. They do not trigger ambiently; a user or parent agent must invoke them directly.

## Troubleshooting Preflight

Use these checks when instruction loading looks wrong, after changing `/hyperopen/AGENTS.md` or `/hyperopen/.codex/config.toml`, or when validating this workflow tooling in tests:

- `env -u TERM codex exec -s read-only --cd /hyperopen "Summarize the current instructions."`
- `env -u TERM codex exec -s read-only --cd /hyperopen/src/hyperopen/views "Show which instruction files are active."`

Expected behavior in this repo:

- project-scoped `/hyperopen/.codex/config.toml` is active in trusted runs
- `/hyperopen/AGENTS.md` is the root repo instruction file
- UI subdirectories still pick up the root contract plus the governed UI docs
- repo skills under `/hyperopen/.agents/skills/**` load without frontmatter errors

## Exact Agent Registry

Use exact agent `name` values. Nicknames are display-only.

- `spec_writer`: creates or refreshes the active ExecPlan and freezes scope. Do not use for production-code edits.
- `acceptance_test_writer`: proposes happy-path acceptance and integration coverage only. Do not use to materialize tests.
- `edge_case_test_writer`: proposes adversarial, boundary, and invariant coverage only. Do not use to materialize tests.
- `tdd_test_writer`: materializes approved failing tests for the RED phase. Do not use for production-code edits.
- `worker`: default implementation agent and the only role allowed to edit `/hyperopen/src/**`.
- `reviewer`: read-only correctness, regression, security, and missing-test reviewer.
- `browser_debugger`: browser evidence collector and PASS / FAIL / BLOCKED QA reporter.
- `debugger`: diagnosis-first bug investigator.
- `ui_designer`: UI decision-making support when the design direction is unclear.
- `ui_visual_validator`: read-only final UI validation.
- `architect_review`: optional release-readiness and design-coherence review for risky changes.
- `documentation_specialist`, `content_writer`, and `clojure_expert`: specialist roles used only when the task needs them.

## Execution Modes

### Interactive Codex Work

Use the workflow skills as the primary interactive entry points. The skills carry the operational step-by-step contract; this document only summarizes the shape of each flow:

- `feature-flow`: `spec_writer` -> `acceptance_test_writer` and `edge_case_test_writer` -> approved test contract -> `tdd_test_writer` -> RED verification -> `worker` -> `reviewer` -> `browser_debugger` when UI-facing -> repo gates
- `bug-flow`: `debugger` and optional `browser_debugger` -> agreed failure mode -> `tdd_test_writer` when testable -> `worker` -> `reviewer` -> browser rerun when needed -> repo gates
- `ui-flow`: `browser_debugger` and optional `ui_designer` -> optional `tdd_test_writer` when deterministic tests are appropriate -> `worker` -> `ui_visual_validator` and `reviewer` -> governed browser-QA matrix

### Repo-Local Manager

`npm run agent:ticket -- --issue <bd-id>` implements the ticket-runner path for complex tracked work:

1. `spec_writer` refreshes the active ExecPlan and emits `spec.json`
2. `acceptance_test_writer` and `edge_case_test_writer` emit proposal artifacts
3. the manager freezes `approved-test-contract.json`
4. `tdd_test_writer` writes the RED-phase failing tests
5. the manager verifies the RED phase fails for the intended reason
6. `worker` implements the smallest change that satisfies the contract
7. `reviewer` and `browser_debugger` run before final quality gates

The manager writes artifacts under `/hyperopen/tmp/multi-agent/<bd-id>/` and the tests under `/hyperopen/tools/multi-agent/test/**` enforce that contract.

## Planning Integration

- Complex multi-agent work still requires an ExecPlan under `/hyperopen/docs/exec-plans/**`.
- `spec_writer` owns creating or refreshing the active ExecPlan instead of creating a parallel requirements document.
- The approved test contract and any important workflow decisions must be reflected back into the active ExecPlan.
- `bd` remains the lifecycle source of truth; markdown artifacts do not replace it.

## Write Surfaces

- `spec_writer`: active ExecPlan path and `/hyperopen/tmp/multi-agent/**`
- `acceptance_test_writer`: proposal artifacts only
- `edge_case_test_writer`: proposal artifacts only
- `tdd_test_writer`: approved `test/**` files, active ExecPlan path, and `/hyperopen/tmp/multi-agent/**`
- `worker`: `/hyperopen/src/**`, approved `test/**` files, active ExecPlan path, and `/hyperopen/tmp/multi-agent/**`
- `reviewer`, `architect_review`, `ui_visual_validator`: no tracked-file writes
- `browser_debugger`: `/hyperopen/tmp/browser-inspection/**`, `/hyperopen/tmp/multi-agent/**`, and optional `/hyperopen/docs/qa/**`

## Failure Semantics

- Real manager runs fail fast when `OPENAI_API_KEY` is missing.
- Real manager runs fail fast in a dirty worktree unless explicitly resumed.
- A phase fails if its artifact does not validate or its diff touches disallowed paths.
- Test-proposal merge fails when proposal roles claim overlapping target files or conflicting validation commands.
- Browser QA is required only when the spec marks the ticket as UI-facing or interaction-heavy; otherwise the browser report must record an explicit skip.

## Validation

- `npm run test:multi-agent`
- `npm run check`
- `npm test`
- `npm run test:websocket`

Use a fresh Codex run after changing `/hyperopen/AGENTS.md` or `/hyperopen/.codex/config.toml`. Skill content changes are detected automatically, but if a skill update does not appear, restart and re-run the checks above.
