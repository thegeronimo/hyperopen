# Reduce Black-Litterman View-Edit CRAP

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. It is anchored to `bd` issue `hyperopen-kmw1`, created for this work and linked as discovered from the broader portfolio optimizer remediation issue `hyperopen-zenl`.

## Purpose / Big Picture

The Black-Litterman optimizer lets a user express investment views such as “BTC will return 10%” or “ETH will outperform SOL by 5%.” The action namespace `src/hyperopen/portfolio/optimizer/black_litterman_actions/views.cljs` owns direct edits to those saved views after they already exist. A CRAP report named `set-portfolio-optimizer-black-litterman-view-parameter` at line 132 as the dominant optimizer hotspot, with 268 of 295.88 optimizer crapload. CRAP combines branch complexity and test coverage, so the quickest useful improvement is to cover the behavior that users can exercise when editing views: switching view kind, editing numbers, recalculating confidence variance, changing absolute and relative instruments, and rejecting invalid duplicate instruments.

After this change, a contributor can run the ClojureScript tests and see direct coverage for those branches. The tests also protect a correctness rule: a relative view cannot use the same instrument as both primary asset and comparator, because that would produce an incoherent pair view.

## Progress

- [x] (2026-05-02T01:53:00Z) Created and claimed `bd` issue `hyperopen-kmw1` for this CRAP reduction slice.
- [x] (2026-05-02T01:53:49Z) Read the target action modules, existing Black-Litterman action tests, planning contract, and package test commands.
- [x] (2026-05-02T01:56:48Z) Added direct action coverage for kind switching, numeric updates, confidence variance, absolute/relative instrument changes, and duplicate-instrument rejection.
- [x] (2026-05-02T01:57:57Z) Verified RED with the focused Shadow CLJS test build. The duplicate primary-instrument assertion failed because the action saved a relative view with `:instrument-id "perp:SOL"` and `:comparator-instrument-id "perp:SOL"`.
- [x] (2026-05-02T01:58:45Z) Updated `valid-instrument-update?` so primary instrument edits reject the current comparator and comparator edits reject the current primary.
- [x] (2026-05-02T01:59:03Z) Verified GREEN with the focused Shadow CLJS test build: 8 tests, 79 assertions, 0 failures, 0 errors.
- [x] (2026-05-02T02:03:20Z) Split the direct view-edit coverage into `test/hyperopen/portfolio/optimizer/black_litterman_view_edits_test.cljs` after `npm run check` reported the original action test namespace exceeded the 500-line size guardrail.
- [x] (2026-05-02T02:07:57Z) Ran focused tests, required repo gates, coverage, and the CRAP report for `views.cljs`; all validation commands passed.
- [x] (2026-05-02T02:07:57Z) Ready to move this ExecPlan to completed and close `hyperopen-kmw1`.

## Surprises & Discoveries

- Observation: The existing `test/hyperopen/portfolio/optimizer/black_litterman_actions_test.cljs` covers editor save flows well, but it does not directly exercise `set-portfolio-optimizer-black-litterman-view-parameter`.
  Evidence: The test file resolves editor actions and calls `remove-portfolio-optimizer-black-litterman-view`, but has no tests that invoke `set-portfolio-optimizer-black-litterman-view-parameter`.

- Observation: The direct view-edit duplicate validation is asymmetric for modern relative-view keys.
  Evidence: `valid-instrument-update?` rejects `:comparator-instrument-id` equal to the primary instrument, but returns `true` for `:instrument-id`, so changing the primary instrument to the current comparator is not rejected before `rebuild-weights`.

- Observation: The full default Node test runner currently cannot start until npm packages are installed.
  Evidence: `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js` built the test target, then failed before tests with `Cannot find module 'lucide/dist/esm/icons/external-link.js'`. A focused build using `--config-merge '{:ns-regexp "^hyperopen\\.portfolio\\.optimizer\\.black-litterman-actions-test$"}'` avoided unrelated UI imports and reached the new RED assertion.

- Observation: Installing npm dependencies from the lockfile resolved the missing package precondition for broad JavaScript-backed validation.
  Evidence: `npm ci` added 335 packages and completed with exit code 0. It reported 12 audit vulnerabilities, which are existing dependency audit findings and not introduced by this source/test change.

- Observation: Putting all new direct view-edit coverage into the existing action test namespace violated the namespace-size guardrail.
  Evidence: The first `npm run check` attempt failed at `lint:namespace-sizes` with `[missing-size-exception] test/hyperopen/portfolio/optimizer/black_litterman_actions_test.cljs - namespace has 509 lines`. Splitting the new direct view-edit tests into `test/hyperopen/portfolio/optimizer/black_litterman_view_edits_test.cljs` kept the existing editor-action test file at 362 lines and the new focused suite at 227 lines.

## Decision Log

- Decision: Keep this as a focused test-and-validation change rather than splitting or restructuring the action namespace.
  Rationale: The user asked for the fastest improvement and specifically named direct coverage around view edits. Adding coverage first also exposes one small validation bug without creating broader churn.
  Date/Author: 2026-05-02 / Codex

- Decision: Put the new coverage in the existing `test/hyperopen/portfolio/optimizer/black_litterman_actions_test.cljs` namespace.
  Rationale: The public action facade in `src/hyperopen/portfolio/optimizer/black_litterman_actions.cljs` re-exports the view-edit action. Existing action tests already include state fixtures and effect helpers for this action layer, so this keeps behavior tests close to the API under test.
  Date/Author: 2026-05-02 / Codex

- Decision: After the namespace-size guardrail failed, keep editor-save coverage in `test/hyperopen/portfolio/optimizer/black_litterman_actions_test.cljs` and move direct saved-view edit coverage to `test/hyperopen/portfolio/optimizer/black_litterman_view_edits_test.cljs`.
  Rationale: Adding a size exception would have preserved a growing test file. A focused test namespace keeps the new CRAP-reduction coverage discoverable while satisfying the repository's size guardrail.
  Date/Author: 2026-05-02 / Codex

## Outcomes & Retrospective

This work added direct saved-view edit coverage for kind switching, numeric return and confidence edits, confidence variance recalculation, absolute instrument weight rebuilds, relative primary and comparator instrument weight rebuilds, and duplicate relative-instrument rejection. The RED test exposed a real correctness bug: changing a relative view's primary instrument to the current comparator was accepted and saved an incoherent view with duplicate instruments. The fix made direct instrument validation symmetric for primary and comparator keys while preserving the public action API.

The target module is no longer a CRAP hotspot. After final coverage, `npm run crap:report -- --module src/hyperopen/portfolio/optimizer/black_litterman_actions/views.cljs --format json` reported `crappy-functions: 0`, module `crapload: 0.0`, module `max-crap: 20.0`, and `set-portfolio-optimizer-black-litterman-view-parameter` at CRAP `16.00171954029165` with coverage `0.9811320754716981`. Overall complexity decreased in practical terms because the action now rejects a previously valid invalid state, and the high-risk branchy function is directly specified by tests without adding production abstractions.

## Context and Orientation

The repository is a ClojureScript app. Actions return effects, not mutated state. An effect such as `[:effects/save-many [[path value] ...]]` tells the app runtime which state paths to save. The existing tests use `effect-values-by-path` to turn those effects into a map from state path to saved value, which makes action behavior easy to assert without starting the browser.

The relevant files are:

- `src/hyperopen/portfolio/optimizer/black_litterman_actions.cljs`: public facade that re-exports actions from the `editor` and `views` helper namespaces.
- `src/hyperopen/portfolio/optimizer/black_litterman_actions/views.cljs`: direct view editing actions. The hotspot is `set-portfolio-optimizer-black-litterman-view-parameter`.
- `src/hyperopen/portfolio/optimizer/black_litterman_actions/common.cljs`: shared parsing, effect, confidence, universe, and instrument helper functions.
- `src/hyperopen/portfolio/optimizer/black_litterman_actions/editor.cljs`: editor save actions already covered by existing tests.
- `test/hyperopen/portfolio/optimizer/black_litterman_actions_test.cljs`: existing action-level tests and the right home for direct view-edit coverage.

A Black-Litterman absolute view names one instrument and an expected return. A relative view names a primary instrument, a comparator instrument, a direction such as `:outperform`, and a spread return. Direct view edits must keep `:weights` synchronized with the instruments: absolute views use `{instrument-id 1}`, outperform relative views use `{primary 1 comparator -1}`, and underperform relative views reverse the signs.

## Plan of Work

First, extend the existing action test namespace with a helper for reading the updated first view from returned effects. Add direct tests for `actions/set-portfolio-optimizer-black-litterman-view-parameter`. One test should switch an existing absolute view to relative and assert that the action preserves return, confidence, confidence variance, and horizon while rebuilding relative instruments and weights from the universe. The same test should update numeric return and confidence values and assert that confidence updates also rewrite `:confidence-variance`.

Second, add direct instrument-change coverage. For an absolute view, changing `:instrument-id` to `perp:SOL` must rewrite `:weights` to `{"perp:SOL" 1}`. For a relative view, changing `:instrument-id` from `perp:ETH` to `perp:HYPE` must rewrite weights to `{"perp:HYPE" 1 "perp:SOL" -1}`, and changing `:comparator-instrument-id` from `perp:SOL` to `perp:BTC` must rewrite weights to `{"perp:ETH" 1 "perp:BTC" -1}`.

Third, add duplicate-instrument rejection coverage. Starting from a relative view with primary `perp:ETH` and comparator `perp:SOL`, changing `:instrument-id` to `perp:SOL` must return no effects and must not save a new views vector. Changing `:comparator-instrument-id` to `perp:ETH` must also return no effects. This test is expected to fail before the implementation fix because the current validation only rejects comparator-to-primary duplicates.

Fourth, update `valid-instrument-update?` in `views.cljs` so primary-key edits (`:instrument-id` and `:long-instrument-id`) compare against the current comparator from `view-comparator-instrument-id`, and comparator-key edits (`:comparator-instrument-id` and `:short-instrument-id`) compare against the current primary from `view-primary-instrument-id`. Keep all other validation behavior unchanged.

## Concrete Steps

Run all commands from `/Users/barry/.codex/worktrees/a8aa/hyperopen`.

1. Add tests to `test/hyperopen/portfolio/optimizer/black_litterman_actions_test.cljs`.

2. Verify RED with:

       npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js

   Expected before the fix: the new duplicate primary-instrument assertion fails because updating `:instrument-id` to the current comparator returns a save effect.

3. Edit `src/hyperopen/portfolio/optimizer/black_litterman_actions/views.cljs` inside `valid-instrument-update?` only. The intended behavior is:

       :instrument-id and :long-instrument-id reject values equal to the current comparator instrument.
       :comparator-instrument-id and :short-instrument-id reject values equal to the current primary instrument.
       Other parameter keys remain valid.

4. Verify GREEN with:

       npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js

   Expected after the fix: all ClojureScript tests pass.

5. Generate coverage and inspect CRAP for the target module:

       npm run coverage
       npm run crap:report -- --module src/hyperopen/portfolio/optimizer/black_litterman_actions/views.cljs --format json

   Expected: the CRAP report can read `coverage/lcov.info`, and `set-portfolio-optimizer-black-litterman-view-parameter` is lower than the user-reported 268 contribution. If the entire function remains over the configured threshold, record the exact remaining hotspot and why additional scope would be needed.

6. Run required gates for code changes:

       npm run check
       npm test
       npm run test:websocket

   Expected: all commands complete with exit code 0. If any command fails due to an unrelated pre-existing issue, capture the failing command and concise evidence in this plan and in the final handoff.

## Validation and Acceptance

Acceptance requires direct tests that exercise all named view-edit behaviors: kind switching, numeric return and confidence updates, confidence variance recalculation, absolute instrument weight rebuilds, relative primary/comparator weight rebuilds, and duplicate relative-instrument rejection. The duplicate rejection test must fail before the implementation fix and pass after the fix.

The final validation set passed:

- `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js`
- `npm run coverage`
- `npm run crap:report -- --module src/hyperopen/portfolio/optimizer/black_litterman_actions/views.cljs --format json`
- `npm run check`
- `npm test`
- `npm run test:websocket`

Because this work changes action behavior and tests only, browser QA and Playwright are not required. No browser interaction flow or UI rendering code is changed.

## Idempotence and Recovery

The tests and source edit are safe to apply repeatedly. Re-running `npm run coverage` removes and recreates `.coverage` and `coverage` by design. If the RED test unexpectedly passes, inspect whether another local change has already fixed the duplicate validation and keep the test as regression coverage. If a required gate fails, do not revert unrelated files; capture evidence and fix only failures caused by this change.

## Artifacts and Notes

The baseline CRAP number came from the user prompt rather than a local coverage file. A local attempt to run the module CRAP report before coverage produced:

    Missing coverage/lcov.info. Run npm run coverage first.

The RED evidence from the focused test run was:

    Testing hyperopen.portfolio.optimizer.black-litterman-actions-test
    FAIL in (black-litterman-direct-view-edits-reject-duplicate-relative-instruments-test)
    expected: (= [] primary-effects)
    actual: saved [:portfolio :optimizer :draft :return-model :views] with :instrument-id "perp:SOL" and :comparator-instrument-id "perp:SOL"

The final focused split-suite test run was:

    Testing hyperopen.portfolio.optimizer.black-litterman-view-edits-test
    Ran 3 tests containing 30 assertions.
    0 failures, 0 errors.

The final broad validation evidence was:

    npm run check
    exit code 0

    npm test
    Ran 3684 tests containing 20315 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    Ran 520 tests containing 3027 assertions.
    0 failures, 0 errors.

    npm run coverage
    Statements   : 90.54% ( 153152/169151 )
    Branches     : 70.03% ( 34087/48671 )
    Functions    : 83.25% ( 9520/11435 )
    Lines        : 90.54% ( 153152/169151 )

    npm run crap:report -- --module src/hyperopen/portfolio/optimizer/black_litterman_actions/views.cljs --format json
    summary.crappy-functions = 0
    summary.project-crapload = 0.0
    modules[0].max-crap = 20.0
    set-portfolio-optimizer-black-litterman-view-parameter.crap = 16.00171954029165
    set-portfolio-optimizer-black-litterman-view-parameter.coverage = 0.9811320754716981

During full `npm test` and coverage, `hyperopen.vaults.infrastructure.preview-cache-test` intentionally logged `Failed to persist vault startup preview cache: Error: storage-unavailable`; the suites still completed with 0 failures and 0 errors. This is expected test coverage of the storage-unavailable path, not a failing gate.

This plan was first written before implementation on 2026-05-02 to satisfy the active ExecPlan requirement for complex/risky optimizer work.

## Interfaces and Dependencies

No new libraries or public APIs are introduced. The public action remains:

    hyperopen.portfolio.optimizer.black-litterman-actions/set-portfolio-optimizer-black-litterman-view-parameter
    [state view-id parameter-key value] -> vector of effects

The helper `valid-instrument-update?` remains private to `src/hyperopen/portfolio/optimizer/black_litterman_actions/views.cljs`. It must continue to return a boolean and must not emit effects or mutate state.

Revision note, 2026-05-02: Initial ExecPlan created from the user-reported CRAP hotspot and local source/test inspection so implementation can proceed from a self-contained plan.

Revision note, 2026-05-02: Updated progress and discoveries after adding RED coverage and confirming the duplicate primary-instrument failure with a focused Shadow CLJS test build.

Revision note, 2026-05-02: Updated progress after the validation fix and focused GREEN test run; recorded the npm dependency installation needed for broad validation in this worktree.

Revision note, 2026-05-02: Updated final progress, decisions, outcomes, and validation evidence after splitting the direct view-edit tests, passing required gates, and confirming the target module has zero CRAP load.
