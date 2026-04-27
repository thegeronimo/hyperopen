owner: product+platform
status: completed
source_of_truth: true
tracked_issue: hyperopen-m819
based_on:
  - /Users/barry/Downloads/HO-PDD-002_portfolio_optimization.md
  - /var/folders/dg/3nkyzrp12fn141vv7f6rc9v40000gn/T/TemporaryItems/NSIRD_screencaptureui_oBsC6L/Screenshot 2026-04-27 at 9.00.27 AM.png
  - /Users/barry/.codex/worktrees/d394/hyperopen/docs/exec-plans/active/2026-04-26-portfolio-optimizer-v4-alignment.md

# Portfolio Optimizer One-Click Run Progress ExecPlan

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while the work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. It is self-contained so an engineer can execute the work without relying on the conversation that produced it.

Tracked issue: `hyperopen-m819` ("Portfolio optimizer one-click run progress").

## Purpose / Big Picture

The Portfolio Optimizer setup screen currently asks a user to press `Load History` and then press `Run Optimization`. That is the wrong product interaction. A power user should configure a scenario, press one `Run Optimization` button, and see a clear terminal-like progress panel that explains what the optimizer is doing while it automatically fetches needed return/funding history and then computes the allocation.

After this change, `/portfolio/optimize/new` has no visible `Load History` button. `Run Optimization` starts a unified pipeline: validate the draft, fetch history if required, build the optimizer request, run the worker-backed optimizer, and retain the last successful result until completion. While the pipeline is active, a progress component shows step rows such as `fetch returns matrix`, `shrinkage estimator`, `Black-Litterman posterior`, `QP solve`, `diagnostics + rebalance preview`, and `frontier sweep`, with real percentages where the implementation can know them and checkpoint percentages for synchronous steps.

## Progress

- [x] (2026-04-27 13:12Z) Created tracked issue `hyperopen-m819` and moved it to `in_progress`.
- [x] (2026-04-27 13:13Z) Inspected current seams: `src/hyperopen/portfolio/optimizer/actions.cljs`, `src/hyperopen/runtime/effect_adapters/portfolio_optimizer.cljs`, `src/hyperopen/portfolio/optimizer/application/run_bridge.cljs`, `src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs`, `src/hyperopen/views/portfolio/optimize/setup_readiness_panel.cljs`, and `src/hyperopen/views/portfolio/optimize/run_status_panel.cljs`.
- [x] (2026-04-27 13:14Z) Wrote this active ExecPlan.
- [x] (2026-04-27 15:04Z) Implemented unified run progress state defaults and progress helper functions in `hyperopen.portfolio.optimizer.application.progress`.
- [x] (2026-04-27 15:11Z) Replaced setup run orchestration with `:effects/run-portfolio-optimizer-pipeline` while preserving the lower-level direct worker run effect.
- [x] (2026-04-27 15:19Z) Removed the visible setup history-load button and added `optimization-progress-panel` to the setup right rail.
- [x] (2026-04-27 15:33Z) Added worker and history progress events, including engine checkpoints for risk model, return model, solve, diagnostics, and frontier.
- [x] (2026-04-27 15:45Z) Updated unit and Playwright tests for one-click behavior, progress visibility, and no history fetch on asset add.
- [x] (2026-04-27 16:10Z) Ran validation and recorded evidence below.
- [x] (2026-04-27 16:18Z) Moved this ExecPlan to `docs/exec-plans/completed/` after acceptance criteria and required gates passed.

## Surprises & Discoveries

- Observation: The current run action deliberately refuses to dispatch if history has not already been loaded.
  Evidence: `run-portfolio-optimizer-from-draft` in `src/hyperopen/portfolio/optimizer/actions.cljs` calls `setup-readiness/build-readiness` and only emits `:effects/run-portfolio-optimizer` when `:runnable?` is true. `setup-readiness/build-readiness` sets `:runnable?` false for `:no-eligible-history` and `:incomplete-history`.

- Observation: The history loader already produces a complete request plan that can drive progress percentages.
  Evidence: `history-client/request-history-bundle!` calls `history-loader/build-history-request-plan`, which returns `:candle-requests` and `:funding-requests`. The progress denominator can be the combined count of those vectors.

- Observation: The optimizer worker currently returns only final result/error messages.
  Evidence: `src/hyperopen/portfolio/optimizer/worker.cljs` posts `optimizer-result` and `optimizer-error`; `src/hyperopen/portfolio/optimizer/infrastructure/worker_client.cljs` forwards worker messages to `run_bridge/handle-worker-message!`. There is no `optimizer-progress` message yet.

- Observation: A full streaming worker-progress implementation is possible but not required for the first honest UX improvement.
  Evidence: The engine performs several synchronous setup steps before solving, and the frontier solve count is known only inside the worker. This plan chooses a pragmatic first implementation: real fetch progress, then checkpoint progress around worker submission and completion. A later enhancement can emit worker checkpoint messages from the engine without changing the public UI contract.

- Observation: Worker checkpoint progress was cheap to add once the progress state contract existed.
  Evidence: `engine/solve-plan` now accepts `:on-progress`, the worker posts `optimizer-progress`, and `run_bridge/handle-worker-message!` merges those updates into `[:portfolio :optimizer :optimization-progress]`.

- Observation: The existing runtime adapter namespace was already near the governed namespace-size ceiling.
  Evidence: `npm run check` initially failed `lint:namespace-sizes` after the pipeline was added to `portfolio_optimizer.cljs`; the orchestration was extracted to `src/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline.cljs` and a dedicated test namespace.

## Decision Log

- Decision: Keep `:effects/run-portfolio-optimizer` as the low-level worker-run effect and add `:effects/run-portfolio-optimizer-pipeline` for the one-click UI action.
  Rationale: Existing tests and internals can still call the direct run effect with a fully built request, while the setup UI gets a safer orchestration boundary that can fetch history before building the final request.
  Date/Author: 2026-04-27 / Codex.

- Decision: Remove only the visible `Load History` button, not the existing history-load action/effect.
  Rationale: Existing internals and tests can still exercise history loading directly. The product surface should stop exposing the action as a required user step.
  Date/Author: 2026-04-27 / Codex.

- Decision: Use real percentages for history fetch progress and checkpoint percentages for worker computation in this pass.
  Rationale: History request completion is observable per candle/funding promise today. The worker does not yet emit granular progress; inventing smooth fake percentages would be misleading. Checkpoint progress still tells the user that the run advanced from fetching into computation.
  Date/Author: 2026-04-27 / Codex.

- Decision: Extract the pipeline orchestration into `hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline`.
  Rationale: The adapter facade and portfolio optimizer adapter are governed by namespace-size checks. A separate pipeline namespace keeps orchestration testable and prevents the facade from becoming a dumping ground.
  Date/Author: 2026-04-27 / Codex.

## Outcomes & Retrospective

The user-visible workflow now has one primary run action. `/portfolio/optimize/new` no longer exposes a `Load History` button; `Run Optimization` starts a pipeline that fetches or refreshes missing optimizer history, runs the worker-backed optimizer, and displays a terminal-like progress panel with step rows and percentages.

The lower-level history loading and direct worker-run effects remain available for internals and tests, but they are no longer part of the primary setup UX. The implementation also preserves the existing stale/last-successful-run behavior: pipeline failure updates the progress error state without clearing prior successful results.

Validation evidence recorded on 2026-04-27:

- `npm test` passed: `Ran 3580 tests containing 19582 assertions. 0 failures, 0 errors.`
- `npm run check` passed, including namespace-size lint and all Shadow CLJS compile targets with 0 warnings.
- `npm run test:websocket` passed: `Ran 461 tests containing 2798 assertions. 0 failures, 0 errors.`
- `npx playwright test tools/playwright/test/optimizer-history-network.qa.spec.mjs` passed.
- `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer"` passed.
- Temporary responsive browser QA for widths `375`, `768`, `1280`, and `1440` passed, confirming no visible `portfolio-optimizer-load-history`, visible progress panel, no native control leaks on setup, and no horizontal overflow.
- `git diff --check` passed.
- `npm run browser:cleanup` completed.

## Context and Orientation

The optimizer setup route is rendered by `src/hyperopen/views/portfolio/optimize/workspace_view.cljs`. Before this change, the right rail called `setup-readiness-panel/readiness-panel`, implemented in `src/hyperopen/views/portfolio/optimize/setup_readiness_panel.cljs`, and that panel rendered `data-role="portfolio-optimizer-load-history"`, which dispatched `[:actions/load-portfolio-optimizer-history-from-draft]`.

The action layer lives in `src/hyperopen/portfolio/optimizer/actions.cljs`. `run-portfolio-optimizer-from-draft` builds a request only when `setup-readiness/build-readiness` says the draft is runnable. The readiness module lives in `src/hyperopen/portfolio/optimizer/application/setup_readiness.cljs`; it reports `:no-eligible-history` or `:incomplete-history` until history exists for the selected universe.

The browser-side effect adapter lives in `src/hyperopen/runtime/effect_adapters/portfolio_optimizer.cljs`. It already has `load-portfolio-optimizer-history-effect`, which calls `history-client/request-history-bundle!`, and `run-portfolio-optimizer-effect`, which delegates to `run-bridge/request-run!`. `run-bridge/request-run!` sends work to the Web Worker and updates `[:portfolio :optimizer :run-state]` when the worker returns.

The new progress panel should use the same route styling as the optimizer v4 alignment work. It should not introduce a new app shell. It should use compact panels, uppercase labels, terminal-like rows, bars, percentages, and an amber/green status chip similar to the attached screenshot.

## Plan of Work

First, extend defaults with `default-optimization-progress-state`. The state belongs at `[:portfolio :optimizer :optimization-progress]`. It tracks `:status`, `:run-id`, timestamps, `:active-step`, `:overall-percent`, `:steps`, and `:error`. The default optimizer state should include it.

Second, add a pipeline effect in `src/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline.cljs`, with the browser adapter in `src/hyperopen/runtime/effect_adapters/portfolio_optimizer.cljs` passing concrete dependencies. The effect reads the draft universe, initializes progress, calls the history request path when history is missing or stale for the selected universe, updates fetch progress as individual candle/funding requests finish, rebuilds readiness from the updated store, then dispatches the existing direct run effect. If the draft has no universe, it marks progress failed with a plain message. If history fails, it marks progress failed and leaves prior history/results intact.

Third, change `run-portfolio-optimizer-from-draft` in `src/hyperopen/portfolio/optimizer/actions.cljs` so it emits `[:effects/run-portfolio-optimizer-pipeline]` when the draft has a universe. It should no longer require preloaded history. Keep `run-portfolio-optimizer` unchanged for low-level direct run tests.

Fourth, expose the new effect through `src/hyperopen/runtime/effect_adapters.cljs`, `src/hyperopen/app/effects.cljs`, `src/hyperopen/schema/runtime_registration/portfolio.cljs`, and `src/hyperopen/schema/contracts/effect_args.cljs`.

Fifth, update `src/hyperopen/views/portfolio/optimize/setup_readiness_panel.cljs` so it displays readiness copy and warnings but no button. Add a new `src/hyperopen/views/portfolio/optimize/optimization_progress_panel.cljs` component and render it from the setup right rail near run status. When `:status` is `:running`, the panel title should read `Optimization In Progress` with a `Computing` chip; when failed, show `Optimization Failed`; when succeeded, show a compact completed state.

Sixth, update tests. The key unit tests are `test/hyperopen/portfolio/optimizer/actions_test.cljs`, `test/hyperopen/runtime/effect_adapters/portfolio_optimizer_test.cljs`, `test/hyperopen/runtime/effect_adapters/facade_contract_test.cljs`, `test/hyperopen/runtime/wiring_test.cljs`, `test/hyperopen/schema/contracts/effect_args_test.cljs`, and `test/hyperopen/views/portfolio/optimize/view_test.cljs`. Browser tests in `tools/playwright/test/portfolio-regressions.spec.mjs` and `tools/playwright/test/optimizer-history-network.qa.spec.mjs` should switch from clicking `Load History` to clicking `Run Optimization` and asserting the progress panel.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/d394/hyperopen`.

1. Edit defaults and action/effect registration.
2. Add progress helpers and pipeline orchestration in `runtime/effect_adapters/portfolio_optimizer_pipeline.cljs`, with a thin adapter wrapper in `runtime/effect_adapters/portfolio_optimizer.cljs`.
3. Add the progress panel component and remove the visible history-load button.
4. Update tests and Playwright expectations.
5. Run the focused browser checks, then the required repository gates.

Expected command sequence after implementation:

    npm test
    npm run check
    npm run test:websocket
    npx playwright test tools/playwright/test/optimizer-history-network.qa.spec.mjs
    npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer"
    npm run browser:cleanup

Expected success is zero failures/errors in the CLJS test runner, websocket tests, and Playwright checks.

## Validation and Acceptance

Acceptance is user-visible:

On `/portfolio/optimize/new`, the setup right rail must not show a `Load History` button. The only primary action needed to run a scenario is `Run Optimization`.

With a selected universe and no existing history, pressing `Run Optimization` must automatically fetch optimizer history, show a progress panel with a running fetch step, then run the optimizer worker and show the existing successful run/result behavior.

The history network regression must still prove that merely adding an asset to the universe does not fetch history. The fetch should happen only after pressing `Run Optimization`.

If history loading fails, the progress panel must show failure with a useful message and must not erase the last successful run.

## Idempotence and Recovery

The implementation is additive around existing history and worker paths. If the pipeline starts with an empty universe, it should fail in progress state without performing network work. If history loading fails halfway, existing `history-data` and `last-successful-run` remain in state. The plan can be retried by pressing `Run Optimization` again after fixing inputs or connectivity.

Temporary Playwright config files or screenshots used during validation should go under `tmp/`, which is ignored. Do not commit generated browser artifacts.

## Artifacts and Notes

The attached screenshot establishes the desired visual direction: a compact progress panel with a title, amber status chip, numbered rows, progress bars, right-aligned percentages, and an elapsed/remaining footer. The exact labels should reflect current implementation rather than copying impossible work. In this pass, use `diagnostics + rebalance preview` instead of `bootstrap stability` because the current engine computes sensitivity diagnostics but not bootstrap resampling.

## Interfaces and Dependencies

At the end of this work:

`src/hyperopen/portfolio/optimizer/defaults.cljs` provides `default-optimization-progress-state`.

`src/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline.cljs` owns the pure orchestration helper, and `src/hyperopen/runtime/effect_adapters/portfolio_optimizer.cljs` provides `run-portfolio-optimizer-pipeline-effect`.

`src/hyperopen/views/portfolio/optimize/optimization_progress_panel.cljs` provides `progress-panel`, which accepts one progress map and returns Hiccup.

`src/hyperopen/views/portfolio/optimize/setup_readiness_panel.cljs` no longer renders `data-role="portfolio-optimizer-load-history"`.

`Run Optimization` dispatches `:effects/run-portfolio-optimizer-pipeline` through `:actions/run-portfolio-optimizer-from-draft`.

## Revision Notes

- 2026-04-27: Initial ExecPlan written because the product interaction changed from a two-step history load plus optimizer run into a single run pipeline with visible progress.
- 2026-04-27: Updated after implementation. Pipeline orchestration was extracted to its own runtime adapter namespace after `lint:namespace-sizes` correctly rejected further growth in the existing portfolio optimizer adapter.
