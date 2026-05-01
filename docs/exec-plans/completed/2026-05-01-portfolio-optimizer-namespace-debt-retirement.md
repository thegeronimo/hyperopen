# Portfolio Optimizer Namespace Debt Retirement

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked `bd` work is `hyperopen-ghll`, and `bd` remains the lifecycle source of truth while this plan records the implementation story.

## Purpose / Big Picture

The portfolio optimizer is an active product surface, but its core action, engine, history-loading, and view-test namespaces are already over the repository's 500-line namespace guardrail. That slows feature work because small optimizer changes repeatedly collide with `dev/namespace_size_exceptions.edn` caps instead of landing in focused owners.

After this work, future optimizer features should have obvious homes: draft-model actions, universe actions, execution actions, tracking actions, engine solving, engine result payload shaping, history request planning, history normalization, setup-route tests, scenario-route tests, and frontier/results tests will no longer be concentrated in seven oversized files. A contributor can verify the result by running `npm run lint:namespace-sizes` and seeing no optimizer entries left in `dev/namespace_size_exceptions.edn`, then running the focused optimizer test set and the required repo gates.

## Progress

- [x] (2026-05-01T02:13Z) Created and claimed `bd` task `hyperopen-ghll` for the optimizer namespace-size debt retirement scope.
- [x] (2026-05-01T02:15Z) Audited the current oversized optimizer files: `actions.cljs` 596 lines, `black_litterman_actions.cljs` 642, `application/engine.cljs` 597, `application/history_loader.cljs` 543, `actions_test.cljs` 597, `views/portfolio/optimize/view_test.cljs` 640, and `views/portfolio/optimize/results_panel_test.cljs` 536.
- [x] (2026-05-01T02:17Z) Removed the seven optimizer size exceptions and confirmed `npm run lint:namespace-sizes` fails only on those seven target files.
- [x] (2026-05-01T02:26Z) Split `hyperopen.portfolio.optimizer.actions` behind a 90-line compatibility facade while keeping every public action function callable from the old namespace.
- [x] (2026-05-01T02:26Z) Split `hyperopen.portfolio.optimizer.black-litterman-actions` behind a 45-line compatibility facade while keeping existing public view helper and action names stable.
- [x] (2026-05-01T02:28Z) Split `hyperopen.portfolio.optimizer.application.engine` into context, solve, target-selection, and payload owners while preserving `run-optimization` and `run-optimization-async`.
- [x] (2026-05-01T02:28Z) Split `hyperopen.portfolio.optimizer.application.history-loader` into instruments, request-plan, normalization, and alignment owners while preserving `build-history-request-plan` and `align-history-inputs`.
- [x] (2026-05-01T02:35Z) Split the oversized optimizer action and view tests into focused namespaces, regenerated `test/test_runner_generated.cljs`, and kept each resulting test namespace below 500 lines.
- [x] (2026-05-01T02:44Z) Ran focused optimizer validation, `npm run lint:namespace-sizes`, `npm run check`, `npm test`, and `npm run test:websocket`; all exited 0.
- [x] (2026-05-01T02:47Z) Moved this ExecPlan to completed and closed `bd` task `hyperopen-ghll`.

## Surprises & Discoveries

- Observation: `docs/exec-plans/tech-debt-tracker.md` still references `/hyperopen/docs/exec-plans/active/2026-03-24-architecture-audit-remediation-wave.md`, but that plan now lives under `/hyperopen/docs/exec-plans/completed/`.
  Evidence: `find docs/exec-plans -iname '*architecture*audit*'` returns `docs/exec-plans/completed/2026-03-24-architecture-audit-remediation-wave.md`.
- Observation: The immediate optimizer debt is broader than the existing `hyperopen-qgmq` size-gate issue.
  Evidence: `hyperopen-qgmq` names only `frontier_chart.cljs` and `results_panel_test.cljs`, while the current optimizer exception set contains seven active optimizer files in `dev/namespace_size_exceptions.edn`.
- Observation: The structural RED phase failed for exactly the target optimizer debt files.
  Evidence: `npm run lint:namespace-sizes` reported missing exceptions for `src/hyperopen/portfolio/optimizer/application/history_loader.cljs`, `src/hyperopen/portfolio/optimizer/application/engine.cljs`, `src/hyperopen/portfolio/optimizer/black_litterman_actions.cljs`, `test/hyperopen/views/portfolio/optimize/view_test.cljs`, `test/hyperopen/portfolio/optimizer/actions_test.cljs`, `src/hyperopen/portfolio/optimizer/actions.cljs`, and `test/hyperopen/views/portfolio/optimize/results_panel_test.cljs`.
- Observation: The worktree had `lucide` declared in `package.json` and `package-lock.json`, but `node_modules` was missing it, so focused Node tests initially failed before optimizer namespaces ran.
  Evidence: `npm ls lucide` returned `(empty)`, while `rg -n "lucide" package.json package-lock.json` found `lucide` in both files. Running `npm install` populated local dependencies without modifying `package.json` or `package-lock.json`.
- Observation: The first mechanical split of `frontier_callout_test.cljs` accidentally included the old `solved-result` fixture def from between top-level tests.
  Evidence: `npx shadow-cljs --force-spawn compile test` still exited 0 but warned that `fixtures/sample-solved-result` was undeclared in `frontier_callout_test.cljs`. Removing the stray fixture block made the compile clean with 0 warnings.

## Decision Log

- Decision: Treat this as an exception-retirement refactor, not a behavioral optimizer feature.
  Rationale: The user goal is feature velocity. The safest way to improve velocity is to preserve public APIs and behavior while reducing namespace size and review surface.
  Date/Author: 2026-05-01 / Codex
- Decision: Use compatibility facades for existing public namespaces instead of forcing all callers to move in this ticket.
  Rationale: `hyperopen.portfolio.optimizer.actions`, `hyperopen.portfolio.optimizer.black-litterman-actions`, `hyperopen.portfolio.optimizer.application.engine`, and `hyperopen.portfolio.optimizer.application.history-loader` are stable seams already used by runtime code and tests. Keeping those names stable reduces blast radius.
  Date/Author: 2026-05-01 / Codex
- Decision: Use the namespace-size lint as the RED phase for this structural refactor.
  Rationale: This work changes ownership, not user behavior. Removing the optimizer exceptions before implementation should make `npm run lint:namespace-sizes` fail for exactly the files this plan is retiring; the implementation is accepted when that same lint passes without replacement optimizer exceptions.
  Date/Author: 2026-05-01 / Codex

## Outcomes & Retrospective

Implementation retired all seven target optimizer namespace-size exceptions without replacement caps. The stable public namespaces now act as small compatibility facades, and focused child namespaces own draft actions, universe actions, execution actions, tracking actions, Black-Litterman view/editor logic, engine context/solve/target-selection/payload logic, and history-loader instrument/request/normalization/alignment logic.

The final source line counts are all under the 500-line guardrail: `actions.cljs` 90, largest `actions/*` child 199, `black_litterman_actions.cljs` 45, largest `black_litterman_actions/*` child 263, `application/engine.cljs` 76, largest `application/engine/*` child 277, `application/history_loader.cljs` 26, and largest `application/history_loader/*` child 242. The final split test counts are also under the guardrail: `actions_test.cljs` 162, `draft_actions_test.cljs` 237, `universe_actions_test.cljs` 204, `view_test.cljs` 13, `setup_view_test.cljs` 175, `workspace_view_test.cljs` 237, `scenario_detail_view_test.cljs` 194, `results_panel_test.cljs` 87, `frontier_callout_test.cljs` 24, `frontier_chart_contract_test.cljs` 303, and `test_support.cljs` 138.

Validation passed:

- `npm run lint:namespace-sizes` exited 0.
- `npx shadow-cljs --force-spawn compile test` exited 0 with 0 warnings after removing the stray fixture block.
- `node out/test.js --test=hyperopen.portfolio.optimizer.actions-test,hyperopen.portfolio.optimizer.draft-actions-test,hyperopen.portfolio.optimizer.universe-actions-test,hyperopen.portfolio.optimizer.black-litterman-actions-test,hyperopen.portfolio.optimizer.application.engine-test,hyperopen.portfolio.optimizer.application.history-loader-test,hyperopen.views.portfolio.optimize.view-test,hyperopen.views.portfolio.optimize.setup-view-test,hyperopen.views.portfolio.optimize.workspace-view-test,hyperopen.views.portfolio.optimize.scenario-detail-view-test,hyperopen.views.portfolio.optimize.results-panel-test,hyperopen.views.portfolio.optimize.frontier-callout-test,hyperopen.views.portfolio.optimize.frontier-chart-contract-test` ran 71 tests / 577 assertions with 0 failures and 0 errors.
- `npm run check` exited 0.
- `npm test` ran 3677 tests / 20255 assertions with 0 failures and 0 errors.
- `npm run test:websocket` ran 461 tests / 2798 assertions with 0 failures and 0 errors.

Retrospective: the compatibility-facade approach kept the runtime blast radius low while making future optimizer work easier to localize. The one caution for future splits is to avoid purely top-level `deftest` block extraction when fixtures or helper defs live between tests; shared support namespaces should be established first, then tests moved with those non-test forms accounted for explicitly.

## Context and Orientation

The repository enforces a maximum of 500 lines per ClojureScript namespace. Oversized namespaces must be listed in `dev/namespace_size_exceptions.edn` with an owner, reason, maximum line count, and retirement date. The optimizer currently depends on exceptions that all retire on `2026-06-30`.

The stable optimizer action seam is `src/hyperopen/portfolio/optimizer/actions.cljs`. Runtime action dispatch and tests call functions from that namespace, so this plan keeps the old namespace as a thin facade. The implementation should create focused child namespaces under `src/hyperopen/portfolio/optimizer/actions/`:

- `common.cljs` for shared parsing and effect helpers.
- `draft.cljs` for objective, return model, risk model, constraint, objective parameter, execution assumption, instrument filter, and asset override actions.
- `universe.cljs` for universe search, add, remove, use-current, and Black-Litterman universe pruning.
- `run.cljs` for history load, run, ready-run signature, scenario save, route load, archive, duplicate, and raw run effect helpers.
- `execution.cljs` for execution modal open, close, and confirm actions.
- `tracking.cljs` for tracking refresh and manual tracking actions.

The stable Black-Litterman seam is `src/hyperopen/portfolio/optimizer/black_litterman_actions.cljs`. It currently owns both pure view normalization and UI editor state updates. The implementation should create child namespaces under `src/hyperopen/portfolio/optimizer/black_litterman_actions/`:

- `common.cljs` for shared parsing, paths, draft reads, confidence helpers, and view lookup helpers.
- `views.cljs` for `view-primary-instrument-id`, `view-comparator-instrument-id`, `view-instrument-ids`, default view creation, direct view parameter updates, add, and remove.
- `editor.cljs` for editor draft defaults, draft field updates, draft validation, save/edit/cancel, and clear confirmation actions.

The stable engine seam is `src/hyperopen/portfolio/optimizer/application/engine.cljs`. It must continue exposing `run-optimization` and `run-optimization-async`. The implementation should create child namespaces under `src/hyperopen/portfolio/optimizer/application/engine/`:

- `solve.cljs` for default solver behavior, synchronous solving, async solving, display-frontier solve sequencing, and progress callback dispatch.
- `target_selection.cljs` for solved point construction, efficient-frontier target selection, and solver-failure payloads.
- `payload.cljs` for weight cleaning, result diagnostics, labels, overlays, warnings, history summary, rebalance preview, and the final solved payload map.

The stable history-loader seam is `src/hyperopen/portfolio/optimizer/application/history_loader.cljs`. It must continue exposing `build-history-request-plan` and `align-history-inputs`. The implementation should create child namespaces under `src/hyperopen/portfolio/optimizer/application/history_loader/`:

- `instruments.cljs` for instrument id, coin, market type, vault address, and grouping helpers.
- `request_plan.cljs` for `build-history-request-plan`.
- `normalization.cljs` for candle, vault, funding, and daily price normalization.
- `alignment.cljs` for common-calendar alignment, returns, funding summary, freshness, and `align-history-inputs`.

The oversized tests should be split without changing the behavior they assert. Keep root tests as compatibility coverage and move cohesive assertions to focused suites:

- Move draft model, constraint, and setup-preset assertions from `test/hyperopen/portfolio/optimizer/actions_test.cljs` to `test/hyperopen/portfolio/optimizer/draft_actions_test.cljs`.
- Move universe assertions from `actions_test.cljs` to `test/hyperopen/portfolio/optimizer/universe_actions_test.cljs`.
- Keep run, route, scenario, and raw effect compatibility in `actions_test.cljs`.
- Move setup/new-route render assertions from `test/hyperopen/views/portfolio/optimize/view_test.cljs` to `setup_view_test.cljs`.
- Move scenario-detail route assertions from `view_test.cljs` to `scenario_detail_view_test.cljs`.
- Keep a small root `view_test.cljs` for portfolio route delegation compatibility.
- Create `test/hyperopen/views/portfolio/optimize/test_support.cljs` for Hiccup traversal helpers used by optimizer view tests.
- Move the large frontier SVG contract from `results_panel_test.cljs` to `frontier_chart_contract_test.cljs`.
- Keep `results_panel_test.cljs` for broad result-panel composition and constrain-frontier checkbox coverage, using shared support helpers.

Every new file must stay under 500 lines. Do not add replacement optimizer size exceptions unless a blocker is recorded in this plan and in `bd`.

## Plan of Work

First, remove the seven optimizer entries from `dev/namespace_size_exceptions.edn` and run `npm run lint:namespace-sizes`. This should fail because the source and test files listed above are still over 500 lines. Record the failure in `Surprises & Discoveries` as the RED evidence.

Second, split source namespaces in dependency order. Start with action helpers because `actions.cljs` imports Black-Litterman helpers and several existing focused action tests already prove facade behavior. Then split Black-Litterman actions. Then split `history_loader.cljs`, because it has clear public functions and direct application tests. Finally split `engine.cljs`, because it touches the solver, display frontier, diagnostics, overlays, and rebalance preview. After each source slice, run a focused generated-runner command for the related tests and `npm run lint:namespace-sizes` when enough files should be below threshold.

Third, split the test files. Preserve the assertions exactly unless the assertion must change to require a shared support namespace. After creating, deleting, or renaming any `_test.cljs` file, run `npm run test:runner:generate`. Do not manually edit `test/test_runner_generated.cljs`.

Fourth, clean the registry. Once all target files are below 500 lines, make sure `rg -n "portfolio/optimizer|portfolio/optimize/(view_test|results_panel_test)" dev/namespace_size_exceptions.edn` returns no optimizer source/test exception entries from this plan. It is acceptable for unrelated optimizer runtime adapter exceptions to remain if they were not part of this plan.

Fifth, run focused validation and the required repo gates. The focused validation should compile the generated test build and run optimizer-related tests with `node out/test.js --test=...`. The required gates are `npm run check`, `npm test`, and `npm run test:websocket`.

## Concrete Steps

All commands are run from `/Users/barry/.codex/worktrees/6ba3/hyperopen`.

1. Structural RED:

        npm run lint:namespace-sizes

   Expected before source splits: failure showing missing size exceptions for the seven target optimizer files after the exception entries are removed.

2. Focused test runner refresh and optimizer validation after test splits:

        npm run test:runner:generate
        npx shadow-cljs --force-spawn compile test
        node out/test.js --test=hyperopen.portfolio.optimizer.actions-test,hyperopen.portfolio.optimizer.draft-actions-test,hyperopen.portfolio.optimizer.universe-actions-test,hyperopen.portfolio.optimizer.execution-actions-test,hyperopen.portfolio.optimizer.tracking-actions-test,hyperopen.portfolio.optimizer.black-litterman-actions-test,hyperopen.portfolio.optimizer.application.engine-test,hyperopen.portfolio.optimizer.application.history-loader-test,hyperopen.views.portfolio.optimize.view-test,hyperopen.views.portfolio.optimize.setup-view-test,hyperopen.views.portfolio.optimize.scenario-detail-view-test,hyperopen.views.portfolio.optimize.results-panel-test,hyperopen.views.portfolio.optimize.frontier-chart-contract-test

   Expected after implementation: all listed tests run with 0 failures and 0 errors.

3. Required gates:

        npm run lint:namespace-sizes
        npm run check
        npm test
        npm run test:websocket

   Expected after implementation: every command exits 0. If any command fails for an unrelated pre-existing issue, record the exact failure and create or link a `bd` follow-up instead of hiding it in this plan.

## Validation and Acceptance

Acceptance is structural and behavioral. Structurally, all seven target files must be at or below 500 lines, no replacement optimizer exception entries from this plan may remain in `dev/namespace_size_exceptions.edn`, and every new optimizer source/test namespace must also be below 500 lines. Behaviorally, the public functions in the four old source namespaces must continue to work through the same names, and the focused optimizer CLJS test command plus `npm run check`, `npm test`, and `npm run test:websocket` must pass.

This refactor is not accepted if it only raises caps, adds new long-lived exceptions, deletes regression coverage, or forces callers outside this scope to change imports.

## Idempotence and Recovery

The source splits are additive-first: create focused namespaces, delegate from the old public namespace, run tests, then remove moved private code from the old namespace. If a split fails, keep the facade and focused namespace in place, inspect the failed test, and move the smallest missing helper or require rather than changing behavior.

`npm run test:runner:generate` is safe to run repeatedly. If generated test runner churn appears after new tests are added, keep it only when it reflects real test namespace additions. If namespace-size lint fails after behavior tests pass, use `wc -l` to identify the oversized file and continue splitting; do not restore the removed exception unless this plan records a blocker.

## Artifacts and Notes

Initial size inventory:

        596 src/hyperopen/portfolio/optimizer/actions.cljs
        642 src/hyperopen/portfolio/optimizer/black_litterman_actions.cljs
        597 src/hyperopen/portfolio/optimizer/application/engine.cljs
        543 src/hyperopen/portfolio/optimizer/application/history_loader.cljs
        597 test/hyperopen/portfolio/optimizer/actions_test.cljs
        640 test/hyperopen/views/portfolio/optimize/view_test.cljs
        536 test/hyperopen/views/portfolio/optimize/results_panel_test.cljs

## Interfaces and Dependencies

Public interfaces that must remain available:

- `hyperopen.portfolio.optimizer.actions/set-portfolio-optimizer-objective-kind`
- `hyperopen.portfolio.optimizer.actions/set-portfolio-optimizer-return-model-kind`
- `hyperopen.portfolio.optimizer.actions/set-portfolio-optimizer-risk-model-kind`
- `hyperopen.portfolio.optimizer.actions/apply-portfolio-optimizer-setup-preset`
- `hyperopen.portfolio.optimizer.actions/set-portfolio-optimizer-constraint`
- `hyperopen.portfolio.optimizer.actions/set-portfolio-optimizer-objective-parameter`
- `hyperopen.portfolio.optimizer.actions/set-portfolio-optimizer-execution-assumption`
- `hyperopen.portfolio.optimizer.actions/set-portfolio-optimizer-instrument-filter`
- `hyperopen.portfolio.optimizer.actions/set-portfolio-optimizer-asset-override`
- `hyperopen.portfolio.optimizer.actions/set-portfolio-optimizer-universe-search-query`
- `hyperopen.portfolio.optimizer.actions/handle-portfolio-optimizer-universe-search-keydown`
- `hyperopen.portfolio.optimizer.actions/set-portfolio-optimizer-results-tab`
- `hyperopen.portfolio.optimizer.actions/add-portfolio-optimizer-universe-instrument`
- `hyperopen.portfolio.optimizer.actions/remove-portfolio-optimizer-universe-instrument`
- `hyperopen.portfolio.optimizer.actions/set-portfolio-optimizer-universe-from-current`
- `hyperopen.portfolio.optimizer.actions/load-portfolio-optimizer-history-from-draft`
- `hyperopen.portfolio.optimizer.actions/run-portfolio-optimizer-from-draft`
- `hyperopen.portfolio.optimizer.actions/run-portfolio-optimizer-from-ready-draft`
- `hyperopen.portfolio.optimizer.actions/save-portfolio-optimizer-scenario-from-current`
- `hyperopen.portfolio.optimizer.actions/open-portfolio-optimizer-execution-modal`
- `hyperopen.portfolio.optimizer.actions/close-portfolio-optimizer-execution-modal`
- `hyperopen.portfolio.optimizer.actions/confirm-portfolio-optimizer-execution`
- `hyperopen.portfolio.optimizer.actions/refresh-portfolio-optimizer-tracking`
- `hyperopen.portfolio.optimizer.actions/enable-portfolio-optimizer-manual-tracking`
- `hyperopen.portfolio.optimizer.actions/load-portfolio-optimizer-route`
- `hyperopen.portfolio.optimizer.actions/archive-portfolio-optimizer-scenario`
- `hyperopen.portfolio.optimizer.actions/duplicate-portfolio-optimizer-scenario`
- `hyperopen.portfolio.optimizer.actions/run-portfolio-optimizer`
- `hyperopen.portfolio.optimizer.black-litterman-actions/view-primary-instrument-id`
- `hyperopen.portfolio.optimizer.black-litterman-actions/view-comparator-instrument-id`
- `hyperopen.portfolio.optimizer.black-litterman-actions/view-instrument-ids`
- all public `set-portfolio-optimizer-black-litterman-*`, `add-portfolio-optimizer-black-litterman-view`, `remove-portfolio-optimizer-black-litterman-view`, and clear-confirmation functions currently exported by `black_litterman_actions.cljs`
- `hyperopen.portfolio.optimizer.application.engine/run-optimization`
- `hyperopen.portfolio.optimizer.application.engine/run-optimization-async`
- `hyperopen.portfolio.optimizer.application.history-loader/build-history-request-plan`
- `hyperopen.portfolio.optimizer.application.history-loader/align-history-inputs`

Revision note, 2026-05-01T02:15Z: Initial ExecPlan created for `hyperopen-ghll` after auditing optimizer namespace-size debt and repo planning rules. The plan intentionally uses compatibility facades and namespace-size lint as structural RED evidence because this is a behavior-preserving ownership refactor.

Revision note, 2026-05-01T02:17Z: Recorded the structural RED evidence after removing the target optimizer size exceptions and running `npm run lint:namespace-sizes`.

Revision note, 2026-05-01T02:46Z: Recorded final namespace splits, line counts, focused optimizer validation, required repo gates, and retrospective. The implementation is accepted and ready for plan completion bookkeeping.
