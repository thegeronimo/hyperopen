---
owner: platform
status: canonical
last_reviewed: 2026-03-18
review_cycle_days: 90
source_of_truth: true
---

# Hyperopen AGENTS

## Purpose
Root operating contract for Codex in this repository. Keep the first screen practical. Canonical detail lives in [ARCHITECTURE.md](/hyperopen/ARCHITECTURE.md) and the governed docs linked later in this file under `UI-Specific Docs` and `Canonical References`.

## Workflow Entry Points
- Use `$feature-flow` for complex features, significant refactors, or multi-file behavior changes.
- Use `$bug-flow` for unclear bugs or regressions that need structured diagnosis.
- Use `$ui-flow` for UI work under `/hyperopen/src/hyperopen/views/**`, `/hyperopen/src/styles/**`, or interaction flows.
- If a workflow skill is unavailable, follow `/hyperopen/docs/MULTI_AGENT.md` and use exact agent `name` values.

## Immediate Rules
- Use exact agent `name` values. Nicknames are display-only.
- Parent thread orchestrates multi-agent work. `agents.max_depth = 1`; child agents do not build deeper trees.
- Resolve shared command phrases with `/hyperopen/tools/phrase get "<phrase>" --suggest` before normal intent handling when the normalized user input is 2-5 words.
- Preserve public APIs unless explicitly requested.
- Keep websocket runtime decisions pure and deterministic.
- Keep side effects in interpreters and infrastructure boundaries only.
- Follow `/hyperopen/docs/BROWSER_STORAGE.md` for browser persistence changes.
- Never run `git pull --rebase` or `git push` unless the user explicitly requests remote sync in the current session.

## Write Authority
- Only `worker` edits `/hyperopen/src/**` by default.
- `acceptance_test_writer` and `edge_case_test_writer` propose tests only.
- `tdd_test_writer` owns RED-phase failing-test materialization and may edit only approved test surfaces plus the active ExecPlan.
- `reviewer`, `architect_review`, and `ui_visual_validator` are read-only.
- `browser_debugger` may write only browser artifacts and optional QA notes.

## Planning And Tracking
- Complex work requires an ExecPlan under `/hyperopen/docs/exec-plans/**`.
- `bd` is the issue lifecycle source of truth.
- Follow `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and `/hyperopen/docs/MULTI_AGENT.md`.

## Validation And Return Contract
- Required gates when code changes: `npm run check`, `npm test`, `npm run test:websocket`.
- Always return changed files, commands run, validation results, and remaining risks or blockers.

## UI-Specific Docs
When work touches UI code or interaction flows, also follow:
- [FRONTEND.md](/hyperopen/docs/FRONTEND.md)
- [browser-qa.md](/hyperopen/docs/agent-guides/browser-qa.md)
- [ui-foundations.md](/hyperopen/docs/agent-guides/ui-foundations.md)
- [trading-ui-policy.md](/hyperopen/docs/agent-guides/trading-ui-policy.md)

Do not conclude UI work is complete unless every required browser-QA pass is explicitly accounted for.

## Canonical References
- Architecture and runtime: [ARCHITECTURE.md](/hyperopen/ARCHITECTURE.md), [RELIABILITY.md](/hyperopen/docs/RELIABILITY.md), [SECURITY.md](/hyperopen/docs/SECURITY.md)
- Design and product: [DESIGN.md](/hyperopen/docs/DESIGN.md), [FRONTEND.md](/hyperopen/docs/FRONTEND.md), [PRODUCT_SENSE.md](/hyperopen/docs/PRODUCT_SENSE.md), [QUALITY_SCORE.md](/hyperopen/docs/QUALITY_SCORE.md)
- Planning and execution: [PLANS.md](/hyperopen/docs/PLANS.md), [MULTI_AGENT.md](/hyperopen/docs/MULTI_AGENT.md), [WORK_TRACKING.md](/hyperopen/docs/WORK_TRACKING.md), [tools.md](/hyperopen/docs/tools.md), [BROWSER_STORAGE.md](/hyperopen/docs/BROWSER_STORAGE.md)
- Indexes: [design docs index](/hyperopen/docs/design-docs/index.md), [AGENTS section reindex map](/hyperopen/docs/design-docs/agents-section-index.md), [product specs index](/hyperopen/docs/product-specs/index.md), [references index](/hyperopen/docs/references/index.md)
