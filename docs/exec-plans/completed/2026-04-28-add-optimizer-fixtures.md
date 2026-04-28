---
owner: platform
status: completed
source_of_truth: true
tracked_issue: hyperopen-8c28
based_on:
  - /hyperopen/src/hyperopen/portfolio/optimizer/defaults.cljs
  - /hyperopen/src/hyperopen/portfolio/optimizer/application/request_builder.cljs
  - /hyperopen/src/hyperopen/portfolio/optimizer/application/engine.cljs
  - /hyperopen/src/hyperopen/portfolio/optimizer/BOUNDARY.md
---

# Add Optimizer Fixture Builders

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while the work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. It is self-contained so an engineer can execute the work without relying on the conversation that produced it.

Tracked issue: `hyperopen-8c28` ("Add optimizer fixture builders").

## Purpose / Big Picture

Future portfolio optimizer work should start from valid optimizer state instead of each test rebuilding drafts, universes, account exposure snapshots, history inputs, solved results, and scenario detail state by hand. This change adds one test-only fixture namespace that produces realistic, internally consistent optimizer data. A contributor should be able to require the fixture namespace, call `sample-draft` or `sample-scenario-state`, and immediately exercise request-building, engine, view, action, or scenario lifecycle code with less malformed-state risk.

This is an internal test-support change. It is observable by a focused test suite that fails before the fixture namespace exists and passes once the builders return valid optimizer shapes, plus the required repository gates.

## Progress

- [x] (2026-04-28 17:15Z) Created and claimed tracked issue `hyperopen-8c28`.
- [x] (2026-04-28 17:15Z) Read optimizer defaults, request builder, engine result shape, current portfolio tests, scenario record tests, fixture conventions, and existing optimizer ExecPlan precedents.
- [x] (2026-04-28 17:15Z) Wrote this active ExecPlan with the fixture API, test contract, validation path, and recovery notes.
- [x] (2026-04-28 17:16Z) Added `test/hyperopen/portfolio/optimizer/fixtures_test.cljs` and verified the RED failure: `npm test` failed because `hyperopen.portfolio.optimizer.fixtures` was not available.
- [x] (2026-04-28 17:19Z) Implemented `test/hyperopen/portfolio/optimizer/fixtures.cljs` with draft, universe, current portfolio, history, request, solved result, last-run, and scenario-state builders.
- [x] (2026-04-28 17:21Z) Fixed the override merge helper after the first GREEN run showed empty vector baselines for `sample-universe`.
- [x] (2026-04-28 17:22Z) Ran `npm test`; result: `Ran 3607 tests containing 19783 assertions. 0 failures, 0 errors.`
- [x] (2026-04-28 17:24Z) Ran `npm run check`; result: exit 0 after docs/lint/tooling checks and Shadow CLJS app, portfolio, worker, and test compiles.
- [x] (2026-04-28 17:24Z) Ran `npm run test:websocket`; result: `Ran 461 tests containing 2798 assertions. 0 failures, 0 errors.`

## Surprises & Discoveries

- Observation: The highest-leverage refactors immediately adjacent to this work already exist in this worktree.
  Evidence: `src/hyperopen/views/portfolio/optimize/format.cljs` and `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` are present, with completed plans at `docs/exec-plans/completed/2026-04-28-portfolio-optimizer-formatting-helpers.md` and `docs/exec-plans/completed/2026-04-28-optimizer-universe-candidates.md`.

- Observation: Optimizer tests currently rebuild valid request and result shapes locally.
  Evidence: `test/hyperopen/portfolio/optimizer/application/engine_test.cljs` defines a local `base-request`; `test/hyperopen/portfolio/optimizer/application/request_builder_test.cljs` builds draft/current/history maps inline; `test/hyperopen/portfolio/optimizer/application/scenario_records_test.cljs` defines a local solved run.

- Observation: Test helper namespaces already live under `test/hyperopen/**/test_support` or direct test support paths, and pure fixture data is acceptable in `test/`.
  Evidence: `test/hyperopen/core_bootstrap/test_support/fixtures.cljs`, `test/hyperopen/views/account_info/test_support/fixtures.cljs`, and `test/hyperopen/websocket/application/runtime_tla_trace_fixtures.cljs`.

- Observation: This worktree did not have `node_modules` installed when the GREEN run reached Node execution.
  Evidence: `test -d node_modules` returned `node_modules-missing`, and `npm test` failed while loading `lucide/dist/esm/icons/external-link.js`. Running `npm install` installed the package set from the existing lockfile; no `package.json` or `package-lock.json` diff remained.

- Observation: The first implementation used `merge-with-key`, which is not available to this ClojureScript test target.
  Evidence: Shadow CLJS reported `Use of undeclared Var hyperopen.portfolio.optimizer.fixtures/merge-with-key`. The fixture namespace now uses `reduce-kv` for keyed recursive merging.

## Decision Log

- Decision: Add the reusable optimizer fixture namespace under `test/hyperopen/portfolio/optimizer/fixtures.cljs`, not under `src/`.
  Rationale: These builders are for tests and future agent scaffolding only. Putting them under `test/` prevents accidental runtime coupling and keeps production optimizer boundaries unchanged.
  Date/Author: 2026-04-28 / Codex

- Decision: Provide small builder functions with optional override maps rather than static top-level constants.
  Rationale: Future tests need a reliable valid baseline and a cheap way to alter a scenario id, objective, universe, current weights, or solved target weights without rebuilding the whole shape.
  Date/Author: 2026-04-28 / Codex

- Decision: Include `sample-engine-request` even though the user named only local fixture builders.
  Rationale: A major source of malformed-state risk is the boundary between draft/current/history fixtures and the request builder. A fixture that goes through `request-builder/build-engine-request` proves the sample draft and history are compatible with the application contract.
  Date/Author: 2026-04-28 / Codex

- Decision: Treat empty override maps as "no override" and replace selected collection-valued keys explicitly.
  Rationale: Tests need `(sample-universe)` to return the baseline vector while still allowing callers to replace `:universe`, `:instrument-ids`, `:target-weights`, `:rows`, `:markets`, and similar collection fields in parent fixture maps.
  Date/Author: 2026-04-28 / Codex

## Outcomes & Retrospective

Implemented a reusable test-only optimizer fixture namespace and a focused contract test for it.

Changed files:

- `docs/exec-plans/active/2026-04-28-add-optimizer-fixtures.md`, later moved to `docs/exec-plans/completed/2026-04-28-add-optimizer-fixtures.md`
- `test/hyperopen/portfolio/optimizer/fixtures.cljs`
- `test/hyperopen/portfolio/optimizer/fixtures_test.cljs`
- `test/test_runner_generated.cljs`

Complexity decreased for future optimizer work because tests can now start from one stable fixture seam instead of rebuilding optimizer drafts, current portfolio maps, history data, solved results, and scenario state in each namespace. The only added complexity is a small override merge helper in test support; it is constrained to test fixtures and covered by the focused override test.

Validation evidence:

- RED verification: `npm test` failed before implementation with `The required namespace "hyperopen.portfolio.optimizer.fixtures" is not available`.
- GREEN verification: `npm test` passed with `Ran 3607 tests containing 19783 assertions. 0 failures, 0 errors.`
- Required gate: `npm run check` exited 0.
- Required gate: `npm run test:websocket` passed with `Ran 461 tests containing 2798 assertions. 0 failures, 0 errors.`

Remaining risk: the new builders are not yet adopted by older inline optimizer tests. That is intentional for this slice; future refactors can migrate local `base-request`, `solved-run`, and `solved-result` definitions incrementally.

## Context and Orientation

The portfolio optimizer lives under `src/hyperopen/portfolio/optimizer`. A draft is the editable optimization configuration stored at `[:portfolio :optimizer :draft]`. The default draft comes from `hyperopen.portfolio.optimizer.defaults/default-draft` and contains `:universe`, `:objective`, `:return-model`, `:risk-model`, `:constraints`, `:execution-assumptions`, and `:metadata`.

An optimizer universe is a vector of instrument maps. Each instrument has an `:instrument-id` such as `"perp:BTC"` or `"spot:PURR"`, a `:market-type` such as `:perp` or `:spot`, and labels such as `:coin`, `:symbol`, `:base`, and `:quote`. Current portfolio snapshots have a `:capital` map and a `:by-instrument` map keyed by instrument id; request-building and engine code read weights from `[:current-portfolio :by-instrument instrument-id :weight]`.

The request builder in `src/hyperopen/portfolio/optimizer/application/request_builder.cljs` combines a draft, current portfolio, candle/funding history, market caps, and `:as-of-ms` into the engine request consumed by `src/hyperopen/portfolio/optimizer/application/engine.cljs`. Engine solved results contain `:status :solved`, `:scenario-id`, `:instrument-ids`, `:target-weights`, `:target-weights-by-instrument`, `:current-weights-by-instrument`, `:diagnostics`, `:performance`, `:history-summary`, and `:rebalance-preview`.

Scenario views and lifecycle tests often need a larger application state shape. The useful shape is `{:portfolio {:optimizer ...} :portfolio-ui {:optimizer ...} :asset-selector ...}` where `:optimizer` starts from `defaults/default-optimizer-state`, `:portfolio-ui` starts from `defaults/default-optimizer-ui-state`, and the selected scenario has `:last-successful-run`, `:active-scenario`, `:scenario-index`, and `:tracking` filled in.

## Plan of Work

First add `test/hyperopen/portfolio/optimizer/fixtures_test.cljs`. It should require `hyperopen.portfolio.optimizer.fixtures`, assert the desired API, and intentionally fail before the namespace exists. The test should prove that the sample draft contains a stable scenario id, universe, objective, models, constraints, execution assumptions, and dirty metadata; that the sample current portfolio has capital and matching current weights; that the sample engine request contains eligible instruments and no warnings; that the sample solved result is internally aligned by instrument id; and that the sample scenario state includes draft, last-successful-run, active-scenario, scenario-index, tracking, portfolio-ui optimizer defaults, and asset-selector markets.

Then create `test/hyperopen/portfolio/optimizer/fixtures.cljs`. Implement pure functions:

    (sample-universe)
    (sample-universe overrides)
    (sample-current-portfolio)
    (sample-current-portfolio overrides)
    (sample-history-data)
    (sample-history-data overrides)
    (sample-draft)
    (sample-draft overrides)
    (sample-engine-request)
    (sample-engine-request overrides)
    (sample-solved-result)
    (sample-solved-result overrides)
    (sample-last-successful-run)
    (sample-last-successful-run overrides)
    (sample-scenario-state)
    (sample-scenario-state overrides)

Each override map should deep-merge into the generated baseline map, except collection-valued keys such as `:universe`, `:instrument-ids`, and `:target-weights` should be replaceable by passing those keys explicitly in the override. This keeps callers from writing a new fixture just to alter one field while still allowing exact vector replacement.

Use `hyperopen.portfolio.optimizer.defaults` for default draft, optimizer state, and UI state. Use `hyperopen.portfolio.optimizer.application.request-builder/build-engine-request` for `sample-engine-request` so the fixture remains compatible with the real application seam. Keep the namespace under `test/`; do not add production dependencies and do not edit optimizer runtime code.

## Concrete Steps

Run all commands from `/Users/barry/.codex/worktrees/ad49/hyperopen`.

1. Create `test/hyperopen/portfolio/optimizer/fixtures_test.cljs` and run:

       npm test

   Expected before implementation: the CLJS test compile fails because `hyperopen.portfolio.optimizer.fixtures` is not available.

2. Create `test/hyperopen/portfolio/optimizer/fixtures.cljs` with the builder functions listed above.

3. Run the green test cycle:

       npm test

   Expected after implementation: the full CLJS test suite exits 0 and the new `hyperopen.portfolio.optimizer.fixtures-test` namespace is included in `test/test_runner_generated.cljs`.

4. Finish with the required repository gates for code changes:

       npm run check
       npm run test:websocket

   Expected: each command exits 0. This change does not touch browser UI or browser-test tooling, so Playwright and Browser MCP are not required.

5. Update this ExecPlan with final progress, final validation evidence, and outcomes. Move it to `docs/exec-plans/completed/` only after the acceptance criteria pass and `bd` is closed or updated according to the work-tracking rules.

   Completed on 2026-04-28 after `npm test`, `npm run check`, and `npm run test:websocket` all passed.

## Validation and Acceptance

Acceptance requires all of the following:

The new test namespace `test/hyperopen/portfolio/optimizer/fixtures_test.cljs` must fail before the fixture namespace exists and pass after implementation. The failure should be the missing namespace `hyperopen.portfolio.optimizer.fixtures`, not a syntax error.

The fixture namespace must expose `sample-universe`, `sample-draft`, `sample-current-portfolio`, `sample-history-data`, `sample-engine-request`, `sample-solved-result`, `sample-last-successful-run`, and `sample-scenario-state`.

`sample-engine-request` must call the real request builder and produce a request with scenario id `"fixture-scenario"`, instrument ids `["perp:BTC" "perp:ETH" "spot:PURR"]`, no warnings, a current portfolio with `:capital {:nav-usdc 100000}`, and aligned candle history.

`sample-solved-result` must return a solved result whose `:instrument-ids`, `:target-weights`, `:target-weights-by-instrument`, and `:current-weights-by-instrument` agree with each other. It must include diagnostics, performance, history summary, and rebalance preview data so result-oriented view tests can consume it without adding ad hoc fields.

`sample-scenario-state` must return a route/view-ready state that includes portfolio optimizer state, portfolio UI optimizer state, and asset-selector markets. The active scenario id, draft id, last successful run result scenario id, tracking scenario id, and scenario-index summary id should all agree by default.

Required gates must pass: `npm test`, `npm run check`, and `npm run test:websocket`.

## Idempotence and Recovery

The implementation is additive and test-only. Re-running `npm run test:runner:generate` is safe because `test/test_runner_generated.cljs` is generated from the test tree. If a fixture shape later proves too narrow, add an override to the builder or extend the baseline map in `test/hyperopen/portfolio/optimizer/fixtures.cljs`; do not copy the entire fixture into a new test.

If the green test fails because `sample-engine-request` has warnings, inspect the candle history in `sample-history-data`; the request builder excludes instruments with insufficient candle history. If namespace-boundary checks fail, verify no production namespace imports the test fixture namespace. If generated runner output drifts, rerun `npm test` and inspect `test/test_runner_generated.cljs` for `hyperopen.portfolio.optimizer.fixtures-test`.

## Artifacts and Notes

Useful search commands:

    rg -n "base-request|current-portfolio|last-successful-run|scenario-state|sample-" test/hyperopen/portfolio/optimizer test/hyperopen/views/portfolio/optimize
    rg -n "defn default-.*optimizer|default-draft|default-optimizer-state" src/hyperopen/portfolio/optimizer/defaults.cljs
    rg -n "build-engine-request|:eligible-instruments|:warnings" src/hyperopen/portfolio/optimizer/application/request_builder.cljs test/hyperopen/portfolio/optimizer/application/request_builder_test.cljs

Revision note 2026-04-28: Initial active ExecPlan created for `hyperopen-8c28` after confirming repo planning conventions and optimizer fixture needs.

Revision note 2026-04-28: Recorded RED/GREEN validation, implementation discoveries, final outcomes, and completion evidence before moving this plan to completed.

## Interfaces and Dependencies

The new test support namespace is:

    hyperopen.portfolio.optimizer.fixtures

The namespace may depend on:

    hyperopen.portfolio.optimizer.defaults
    hyperopen.portfolio.optimizer.application.request-builder

The namespace must not dispatch actions, mutate runtime state, read browser storage, call workers, call IndexedDB, make network requests, or render Hiccup. It returns plain ClojureScript maps and vectors for tests to consume.
