# Funding Comparison VM Parse/Filter/Sort Memo Cache (hyperopen-ves)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The funding comparison page currently rebuilds parse, filter, and sort work every time `/hyperopen/src/hyperopen/views/funding_comparison/vm.cljs` is evaluated, even when state inputs are unchanged. After this change, repeated VM builds with unchanged predicted funding rows and UI controls will reuse cached rows instead of recomputing the full pipeline.

A contributor can verify the outcome by running VM tests and confirming the new large-dataset regression test proves parse/filter/sort work is executed once for repeated equivalent inputs, while meaningful input changes (for example favorites) still invalidate the cache.

## Progress

- [x] (2026-03-04 03:42Z) Claimed `bd` issue `hyperopen-ves` and reviewed acceptance criteria.
- [x] (2026-03-04 03:44Z) Audited `/hyperopen/src/hyperopen/views/funding_comparison/vm.cljs` and `/hyperopen/test/hyperopen/views/funding_comparison/vm_test.cljs` plus prior cache-signature patterns in account-info and portfolio view models.
- [x] (2026-03-04 03:49Z) Implemented memoized funding comparison row cache in `/hyperopen/src/hyperopen/views/funding_comparison/vm.cljs` with identity-first plus signature fallback matching for predicted fundings, favorites, and market map inputs.
- [x] (2026-03-04 03:51Z) Added cache reset fixture and large-dataset regression coverage in `/hyperopen/test/hyperopen/views/funding_comparison/vm_test.cljs` validating parse/filter/sort reuse under equivalent input churn and invalidation on favorites change.
- [x] (2026-03-04 03:45Z) Ran required gates (`npm run check`, `npm test`, `npm run test:websocket`) with all commands passing.
- [x] (2026-03-04 03:46Z) Closed `bd` issue `hyperopen-ves` as completed and moved this plan to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: Funding comparison row computation depends on `:asset-selector :market-by-key` for open-interest values, not only predicted funding rows and favorites.
  Evidence: `/hyperopen/src/hyperopen/views/funding_comparison/vm.cljs` `build-row` reads `coin-open-interest` from `market-by-key`.

## Decision Log

- Decision: Use a one-entry memo cache with identity-first checks and signature fallback instead of identity-only checks.
  Rationale: This preserves O(1) fast-path behavior and also keeps cache hits when equivalent vectors/maps are recreated with new identity.
  Date/Author: 2026-03-04 / Codex

- Decision: Include market-map match state in the cache key in addition to the issue-required predicted-fundings/query/timeframe/sort/favorites key fields.
  Rationale: Open-interest values are derived from `market-by-key`; excluding it would allow stale rows when market state changes.
  Date/Author: 2026-03-04 / Codex

- Decision: Expose dynamic stage wrappers (`*parse-predicted-row*`, `*has-cex-funding-rate?*`, `*sort-rows*`) and add a large-input regression test using `with-redefs` counters.
  Rationale: This gives deterministic proof that the parse/filter/sort pipeline is skipped on cache hits without changing production behavior.
  Date/Author: 2026-03-04 / Codex

## Outcomes & Retrospective

Implementation and validation are complete. Funding comparison VM now memoizes the full parse/filter/sort row pipeline and reuses rows across equivalent input churn (including cloned predicted-funding vectors). Favorites changes correctly invalidate cache and recompute rows.

Required gates all passed on 2026-03-04:

- `npm run check` passed (test runner generation, lint suites, and app/worker/test compiles).
- `npm test` passed (`Ran 1832 tests containing 9513 assertions. 0 failures, 0 errors.`).
- `npm run test:websocket` passed (`Ran 290 tests containing 1662 assertions. 0 failures, 0 errors.`).

No remaining implementation gaps were identified for this issue scope.

## Context and Orientation

`/hyperopen/src/hyperopen/views/funding_comparison/vm.cljs` builds funding comparison rows from `:funding-comparison :predicted-fundings`, then filters by query/timeframe semantics, enriches rows with favorites/open-interest context, and sorts based on user-selected columns. This row pipeline is currently executed for every VM build. In this plan, “equivalent input churn” means state values that contain the same semantic content but are recreated as new vectors/maps.

The target behavior is:

- repeated VM evaluation with equivalent predicted rows, favorites, market map, query, timeframe, and sort state should reuse cached rows;
- a meaningful input change (for example favorites change) should invalidate cache and recompute rows;
- behavior and output ordering should remain deterministic.

## Plan of Work

Milestone 1 adds memoization infrastructure in `/hyperopen/src/hyperopen/views/funding_comparison/vm.cljs`: cache atom, deterministic signatures, and match-state helpers. The funding row pipeline moves behind `memoized-funding-comparison-rows`, keyed by predicted rows plus UI/context inputs.

Milestone 2 adds regression coverage in `/hyperopen/test/hyperopen/views/funding_comparison/vm_test.cljs`: a cache reset fixture and a large-dataset test that instruments parse/filter/sort stage calls. The test must prove cache reuse for repeated equivalent inputs and invalidation when favorites change.

Milestone 3 runs required repository gates and updates this plan with final evidence, then moves the plan file to completed and closes `hyperopen-ves`.

## Concrete Steps

From `/hyperopen`:

1. Implement memoized cache in funding comparison VM.
   Edit:
   - `/hyperopen/src/hyperopen/views/funding_comparison/vm.cljs`

2. Add large-dataset regression test and cache reset fixture.
   Edit:
   - `/hyperopen/test/hyperopen/views/funding_comparison/vm_test.cljs`

3. Run required gates and record outputs.
   Commands:
       npm run check
       npm test
       npm run test:websocket

4. Close issue and move plan to completed.
   Commands:
       bd close hyperopen-ves --reason "Completed" --json
       mv docs/exec-plans/active/2026-03-04-funding-comparison-vm-parse-filter-sort-memo-cache.md docs/exec-plans/completed/

## Validation and Acceptance

Acceptance is complete when all conditions are true:

1. Funding comparison VM uses memoized cache for parse/filter/sort row pipeline keyed by predicted-fundings identity/version, query, timeframe, sort, and favorites (plus market map for correctness).
2. A regression test with a large synthetic dataset proves repeated equivalent VM builds do not rerun parse/filter/sort stages.
3. The same regression test proves favorites changes invalidate cache and rerun the pipeline.
4. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

The cache implementation is additive and can be safely reapplied. If regressions appear, reset behavior is isolated to `memoized-funding-comparison-rows`; fallback is to temporarily bypass the memo function and call the compute pipeline directly while debugging with the new test.

## Artifacts and Notes

Expected implementation files:

- `/hyperopen/src/hyperopen/views/funding_comparison/vm.cljs`
- `/hyperopen/test/hyperopen/views/funding_comparison/vm_test.cljs`

Validation evidence captured:

- `npm run check` -> pass (no lint failures, compile warnings, or test build errors).
- `npm test` -> `Ran 1832 tests containing 9513 assertions. 0 failures, 0 errors.`
- `npm run test:websocket` -> `Ran 290 tests containing 1662 assertions. 0 failures, 0 errors.`

## Interfaces and Dependencies

No external dependencies are required. Existing public entry point `funding-comparison-vm` remains unchanged. Added helper interfaces are internal to `vm.cljs` and only expose a cache reset function used by tests:

- `reset-funding-comparison-vm-cache!`

Plan revision note: 2026-03-04 03:51Z - Initial ExecPlan created while implementing `hyperopen-ves`; includes completed implementation steps and pending gate/closure steps.
Plan revision note: 2026-03-04 03:45Z - Updated with final validation evidence and outcomes after all required gates passed.
Plan revision note: 2026-03-04 03:46Z - Marked final issue closure + plan move completion.
