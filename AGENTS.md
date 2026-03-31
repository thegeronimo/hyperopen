---
owner: platform
status: canonical
last_reviewed: 2026-03-22
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
- Use the repo-local `playwright-e2e` skill for committed deterministic browser coverage.
- Use the repo-local `browser-mcp-explore` skill for exploratory browser debugging, live attach, design review, and parity investigation.
- If a workflow skill is unavailable, follow `/hyperopen/docs/MULTI_AGENT.md` and use exact agent `name` values.

## Immediate Rules
- Use exact agent `name` values. Nicknames are display-only.
- Parent thread orchestrates multi-agent work. `agents.max_depth = 1`; child agents do not build deeper trees.
- Resolve shared command phrases with `/hyperopen/tools/phrase get "<phrase>" --suggest` before normal intent handling when the normalized user input is 2-5 words.
- Preserve public APIs unless explicitly requested.
- Keep websocket runtime decisions pure and deterministic.
- Keep side effects in interpreters and infrastructure boundaries only.
- Follow `/hyperopen/docs/BROWSER_STORAGE.md` for browser persistence changes.
- Use Playwright for anything that should be committed, asserted, repeated, reviewed, or run in CI.
- Use Playwright for deterministic browser verification, smoke tests, regression coverage, and stable multi-viewport validation.
- Use Browser MCP for exploratory debugging, reproducing flaky UI behavior, inspecting live browser state, parity compare, and selector or flow discovery before converting a stable path into Playwright coverage.
- After Browser MCP exploration stabilizes a flow, convert that stable local path into Playwright unless the task is explicitly exploratory.
- Follow `/hyperopen/docs/BROWSER_TESTING.md` for browser-tool routing and exact commands.
- Before concluding browser QA, let Playwright exit cleanly and explicitly stop any Browser MCP or browser-inspection sessions you created. Use `npm run browser:cleanup` for repo-wide cleanup or `session stop` / `browser_session_stop` for a specific session.
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
- When browser flows or browser-test tooling change, also run the smallest relevant Playwright command first and broaden only after that passes.
- Always return changed files, commands run, validation results, and remaining risks or blockers.

## UI-Specific Docs
When work touches UI code or interaction flows, also follow:
- [BROWSER_TESTING.md](/hyperopen/docs/BROWSER_TESTING.md)
- [FRONTEND.md](/hyperopen/docs/FRONTEND.md)
- [browser-qa.md](/hyperopen/docs/agent-guides/browser-qa.md)
- [ui-foundations.md](/hyperopen/docs/agent-guides/ui-foundations.md)
- [trading-ui-policy.md](/hyperopen/docs/agent-guides/trading-ui-policy.md)

Do not conclude UI work is complete unless every required browser-QA pass is explicitly accounted for.

## Canonical References
- Architecture and runtime: [ARCHITECTURE.md](/hyperopen/ARCHITECTURE.md), [RELIABILITY.md](/hyperopen/docs/RELIABILITY.md), [SECURITY.md](/hyperopen/docs/SECURITY.md)
- Design and product: [DESIGN.md](/hyperopen/docs/DESIGN.md), [FRONTEND.md](/hyperopen/docs/FRONTEND.md), [BROWSER_TESTING.md](/hyperopen/docs/BROWSER_TESTING.md), [PRODUCT_SENSE.md](/hyperopen/docs/PRODUCT_SENSE.md), [QUALITY_SCORE.md](/hyperopen/docs/QUALITY_SCORE.md)
- Planning and execution: [PLANS.md](/hyperopen/docs/PLANS.md), [MULTI_AGENT.md](/hyperopen/docs/MULTI_AGENT.md), [WORK_TRACKING.md](/hyperopen/docs/WORK_TRACKING.md), [tools.md](/hyperopen/docs/tools.md), [BROWSER_STORAGE.md](/hyperopen/docs/BROWSER_STORAGE.md)
- Indexes: [design docs index](/hyperopen/docs/design-docs/index.md), [AGENTS section reindex map](/hyperopen/docs/design-docs/agents-section-index.md), [product specs index](/hyperopen/docs/product-specs/index.md), [references index](/hyperopen/docs/references/index.md)
