---
owner: portfolio
status: completed
created: 2026-05-01
source_of_truth: false
tracked_issue: hyperopen-89az
---

# Portfolio Optimizer Namespace Debt Retirement

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while work proceeds.

This document follows `/hyperopen/AGENTS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and `/hyperopen/src/hyperopen/portfolio/optimizer/BOUNDARY.md`. The live `bd` issue is `hyperopen-89az`; `bd` remains the lifecycle source of truth.

## Purpose / Big Picture

Retire the portfolio optimizer namespace-size debt called out by the user without changing public APIs or user-visible behavior. The target source namespaces are near or over the 500-line standard: `src/hyperopen/runtime/effect_adapters/portfolio_optimizer.cljs`, `src/hyperopen/portfolio/metrics/builder.cljs`, and `src/hyperopen/views/portfolio/vm/benchmarks.cljs`. The paired oversized test surface is `test/hyperopen/views/portfolio_view_test.cljs`.

After this work, the runtime adapter should be a small compatibility facade over focused history, execution, tracking, and scenario persistence owners. The metrics builder should delegate to focused window, core, and benchmark metric owners. The portfolio benchmark VM should separate selector option/cache ownership from benchmark history computation. The portfolio view test should keep only root view compatibility coverage while chart, benchmark, metrics, and trader-tab assertions live in smaller focused suites.

## Progress

- [x] (2026-05-01 15:39Z) Created and claimed `bd` task `hyperopen-89az`.
- [x] (2026-05-01 15:40Z) Created this active ExecPlan.
- [x] (2026-05-01 15:43Z) Established the structural RED signal by removing only the target namespace-size exceptions; `npm run lint:namespace-sizes` failed on the four target files.
- [x] (2026-05-01 15:50Z) Split the runtime effect adapter into history, execution, and tracking owners while preserving root dynamic vars and public effect wrappers.
- [x] (2026-05-01 15:57Z) Split `hyperopen.portfolio.metrics.builder` into focused window/core/benchmark metric owners while preserving `compute-performance-metrics` and `metric-rows`.
- [x] (2026-05-01 16:04Z) Split `hyperopen.views.portfolio.vm.benchmarks` into selector and computation owners while preserving existing public functions and test redefinition seams.
- [x] (2026-05-01 16:19Z) Split `test/hyperopen/views/portfolio_view_test.cljs` into root, chart, performance metrics, status, and shared support namespaces; regenerated test discovery.
- [x] (2026-05-01 16:45Z) Removed retired namespace-size exceptions and ran focused checks plus required gates.

## Surprises & Discoveries

- Observation: The current checkout already passes `npm run lint:namespace-sizes`; the work is exception retirement, not fixing a broken lint gate.
  Evidence: The command passed before this plan was created.
- Observation: The first portfolio view test split left orphaned chart and metrics test bodies in the root file.
  Evidence: `npx shadow-cljs --force-spawn compile test` failed with an unmatched delimiter in `test/hyperopen/views/portfolio_view_test.cljs`; reconstructing the root from `HEAD` and moving chart/status/metrics tests to focused namespaces restored the test build.
- Observation: The benchmark VM split initially bypassed a root-namespace `with-redefs` test seam for `benchmark-option-matches-search?`.
  Evidence: `node out/test.js` failed in `returns-benchmark-selector-model-keeps-candidate-derivation-stable-across-open-toggle-test` with `match-count` at 0; adding a child dynamic matcher hook and binding it through the root facade restored the expected interception.
- Observation: This worktree was missing locked npm dependencies when the compiled CLJS runner was first executed.
  Evidence: `node out/test.js` failed before tests with `Cannot find module 'lucide/dist/esm/icons/external-link.js'`; `npm install` restored `node_modules` from the existing lockfile.
- Observation: The first `npm run check` attempt was blocked by an unrelated stale active ExecPlan.
  Evidence: `lint:docs` reported `docs/exec-plans/active/2026-05-01-api-namespace-debt-retirement.md` referenced only the already-closed `bd` issue `hyperopen-y4f6` and had no unchecked progress items.

## Decision Log

- Decision: Use namespace-size lint as the RED signal for this behavior-preserving refactor.
  Rationale: The target behavior is structural. Removing the target exceptions before extraction should fail on the target files, and the work is accepted when the same lint passes without re-adding those exceptions.
  Date/Author: 2026-05-01 / Codex

- Decision: Keep public compatibility facades in the existing namespaces.
  Rationale: Runtime registration and tests import the existing namespace names. Moving implementation behind facade functions reduces comprehension cost without widening the public API.
  Date/Author: 2026-05-01 / Codex

- Decision: Browser QA is accounted for as not required unless rendered DOM or interaction behavior changes.
  Rationale: The planned UI-adjacent source change is view-model extraction and the test split is test-only. If implementation touches Hiccup/CSS/interaction code, run the smallest relevant Playwright/browser QA from `/hyperopen/docs/BROWSER_TESTING.md`.
  Date/Author: 2026-05-01 / Codex

## Outcomes & Retrospective

The four target namespace-size exceptions were retired from `dev/namespace_size_exceptions.edn`. The target files are now below the 500-line default: `src/hyperopen/runtime/effect_adapters/portfolio_optimizer.cljs` 173 lines, `src/hyperopen/portfolio/metrics/builder.cljs` 67, `src/hyperopen/views/portfolio/vm/benchmarks.cljs` 74, and `test/hyperopen/views/portfolio_view_test.cljs` 408. The largest new owner is `src/hyperopen/views/portfolio/vm/benchmarks/selector.cljs` at 453 lines.

Implementation preserves the existing public facades:

    hyperopen.runtime.effect-adapters.portfolio-optimizer
    hyperopen.portfolio.metrics.builder
    hyperopen.views.portfolio.vm.benchmarks

The portfolio view test surface now has focused namespaces for root route/layout coverage, chart behavior, performance metrics rendering, status banner rendering, and shared Hiccup/fake-DOM helpers. `test/test_runner_generated.cljs` includes the new test namespaces.

Validation passed:

    npm run test:runner:generate
    npx shadow-cljs --force-spawn compile test
    npm run lint:namespace-sizes
    npm run lint:namespace-boundaries
    node out/test.js
    npm run check
    npm test
    npm run test:websocket
    npm run lint:docs:test
    npm run lint:namespace-sizes:test
    npm run lint:namespace-boundaries:test
    npm run test:release-assets
    npx shadow-cljs --force-spawn compile app
    npx shadow-cljs --force-spawn compile portfolio
    npx shadow-cljs --force-spawn compile portfolio-worker
    npx shadow-cljs --force-spawn compile portfolio-optimizer-worker
    npx shadow-cljs --force-spawn compile vault-detail-worker

`npm test` covered 3677 tests / 20255 assertions with 0 failures and 0 errors. `npm run test:websocket` covered 461 tests / 2798 assertions with 0 failures and 0 errors. Browser QA was not run because no Hiccup, CSS, browser storage, or interaction implementation changed; the UI-adjacent work was view-model and test ownership only.

## Context and Orientation

`src/hyperopen/runtime/effect_adapters/portfolio_optimizer.cljs` currently owns request-run delegation, history loading, execution submission/ledger persistence, tracking refresh, and scenario facade wiring. Existing tests rebind dynamic vars in the root namespace, so the root must retain those vars and pass an environment map into child owners.

`src/hyperopen/portfolio/metrics/builder.cljs` currently owns window slicing, quality/core context construction, drawdown/window metrics, daily risk/distribution metrics, and benchmark-relative metrics. The split should keep `compute-performance-metrics` as the orchestration facade while moving groups of pure helpers to child namespaces.

`src/hyperopen/views/portfolio/vm/benchmarks.cljs` currently owns selector option ranking/cache behavior and benchmark cumulative-row computation. The split should keep existing public functions callable while moving selector/cache helpers and computation helpers into focused child namespaces.

`test/hyperopen/views/portfolio_view_test.cljs` currently mixes root layout, trader inspection tabs, funding anchors, chart rendering, benchmark selector, metrics formatting, and tooltip runtime assertions. Focused split files should share only minimal reusable Hiccup/DOM helpers.

## Plan of Work

1. Remove the four target entries from `dev/namespace_size_exceptions.edn`: `portfolio_optimizer.cljs`, `portfolio/metrics/builder.cljs`, `views/portfolio/vm/benchmarks.cljs`, and `views/portfolio_view_test.cljs`.
2. Run `npm run lint:namespace-sizes`; expected RED is missing exceptions for only those target paths.
3. Create `src/hyperopen/runtime/effect_adapters/portfolio_optimizer/history.cljs`, `execution.cljs`, and `tracking.cljs`. Move implementation helpers there. Keep root dynamic vars and public wrappers.
4. Create `src/hyperopen/portfolio/metrics/builder/window.cljs`, `core.cljs`, and `benchmark.cljs`. Move pure helper groups there. Keep root orchestration and metric row facade.
5. Create `src/hyperopen/views/portfolio/vm/benchmarks/selector.cljs` and `computation.cljs`. Move option/cache and history-computation helpers there. Keep root facade public names.
6. Split `test/hyperopen/views/portfolio_view_test.cljs` into focused suites for root/trader layout, benchmark selector and metrics, and chart tooltip/runtime behavior. Regenerate the CLJS test runner.
7. Run line counts and `npm run lint:namespace-sizes`; do not add replacement exceptions for the target owners.
8. Run focused validation:
   - `npm run test:runner:generate`
   - `npx shadow-cljs --force-spawn compile test`
   - `node out/test.js --test=hyperopen.runtime.effect-adapters.portfolio-optimizer-test --test=hyperopen.runtime.effect-adapters.portfolio-optimizer-execution-test --test=hyperopen.runtime.effect-adapters.portfolio-optimizer-tracking-test --test=hyperopen.views.portfolio.vm.benchmarks-test --test=hyperopen.views.portfolio-view-test`
9. Run required gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Acceptance Criteria

- `npm run lint:namespace-sizes` passes with no exception entries for the four target files.
- The root adapter namespace still exports the same public effect functions and dynamic vars used by existing tests.
- `compute-performance-metrics`, `metric-rows`, and portfolio benchmark VM public functions remain callable through their existing namespaces.
- The split portfolio view tests cover the same behavior groups and the generated test runner includes the new namespaces.
- Required validation commands are run and recorded here before the issue is closed.
