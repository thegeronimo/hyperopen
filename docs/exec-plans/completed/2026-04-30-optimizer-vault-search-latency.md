# Fix optimizer vault search latency

This ExecPlan is a completed execution record. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` capture the implementation story that closed the work.

This document follows `/hyperopen/.agents/PLANS.md`. It is self-contained so a future contributor can continue from this file without relying on conversation history.

Tracked issue: `hyperopen-8vx4` ("Fix optimizer vault search latency").

## Purpose / Big Picture

The Portfolio Optimizer universe search became visibly slow after vault rows were added as searchable candidates. Today the search path rebuilds, normalizes, and sorts the full vault candidate set from `[:vaults :merged-index-rows]` on every render and every keypress, even though the UI only shows six suggestions and the v4 setup screen does not need any candidates while the search box is blank.

After this change, the optimizer will precompute and cache the normalized, sorted vault candidate pool when the vault row input changes, reuse that cached pool for per-query filtering, and skip the candidate lookup entirely on the v4 setup screen while the search box is blank. A user should still be able to type a vault name or address on `/portfolio/optimize/new` and see the same vault results, but blank renders and repeated keypresses should no longer pay the full vault sort cost.

## Scope / Non-Goals

This ticket is a direct latency fix only. It covers the optimizer universe search path in `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` and the v4 setup surface in `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`.

This ticket does not change vault history loading, optimizer route loading, benchmark selector behavior, the public arities of `candidate-markets`, or the legacy optimizer universe panel semantics beyond any shared benefit it gets from the new cached vault candidate pool. If further latency remains outside this direct fix, capture it in a follow-up `bd` issue instead of broadening this change.

## Progress

- [x] (2026-04-30 13:38Z) Confirmed the tracked issue and fixed scope: direct latency remediation only, with no production or test edits in this planning pass.
- [x] (2026-04-30 13:38Z) Mapped the current hot path in `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` and the unconditional v4 call site in `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`.
- [x] (2026-04-30 13:38Z) Chose the concrete test surfaces for the future RED phase: `test/hyperopen/portfolio/optimizer/application/universe_candidates_test.cljs` and `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs`.
- [x] (2026-04-30 15:04Z) Added RED tests in `test/hyperopen/portfolio/optimizer/application/universe_candidates_test.cljs` that count `vault-row->candidate` calls across repeated queries, changed row inputs, and changing selected-id universes.
- [x] (2026-04-30 15:04Z) Added a RED v4 layout test in `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` that stubs `candidate-markets` and proves blank normalized queries still trigger an unwanted lookup today while non-blank queries render the expected candidate row contract.
- [x] (2026-04-30 15:04Z) Ran the focused RED command and confirmed the intended failures after restoring local npm dependencies with `npm install`.
- [x] (2026-04-30 15:27Z) Addressed reviewer follow-up coverage only in the approved test surfaces: added a per-test optimizer cache reset fixture, equal-clone cache reuse assertions, same-count cache invalidation coverage for `:name`, `:tvl`, and `[:relationship :type]`, and a stronger blank-search keyboard contract in `setup_v4_layout_test.cljs`.
- [x] (2026-04-30 15:27Z) Re-ran `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.universe-candidates-test --test=hyperopen.views.portfolio.optimize.setup-v4-layout-test` against the current patch and observed 24 tests, 155 assertions, 0 failures, and 0 errors.
- [x] (2026-04-30 15:34Z) Implemented the cached vault candidate pool in `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` with an `identical?` fast path, normalized row-signature fallback, and live per-call selected/query filtering.
- [x] (2026-04-30 15:34Z) Implemented the v4 blank-search short-circuit in `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` so blank normalized queries bind empty candidates and do not call `candidate-markets`.
- [x] (2026-04-30 15:40Z) Re-ran the focused ClojureScript tests, required gates, and narrow Playwright optimizer-search regression on the final patch.
- [x] (2026-04-30 16:18Z) Moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/` after the tracked issue was closed and the active-plan docs gate reported no remaining unchecked progress.

## Surprises & Discoveries

- Observation: The expensive work happens before query filtering and before the six-row cap.
  Evidence: `candidate-vaults` in `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` currently does `filter -> sort-by -> keep -> remove selected ids -> filter query -> vec` over `[:vaults :merged-index-rows]`, so the full eligible vault set is normalized and sorted before the query removes most rows and before `candidate-markets` calls `take 6`.

- Observation: The v4 setup surface always computes search candidates before it decides whether the UI is actually searching.
  Evidence: `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` binds `markets` from `universe-candidates/candidate-markets` before it binds `searching?`, so a blank `:portfolio-ui :optimizer :universe-search-query` still triggers the vault candidate rebuild even though no suggestion list is rendered.

- Observation: The repository already has a local precedent for identity-plus-signature memoization of vault search data.
  Evidence: `src/hyperopen/views/portfolio/vm/benchmarks.cljs` memoizes benchmark vault selector options by first checking `identical?` on the row vector and then falling back to a content signature so equal-but-not-identical vectors can reuse cached work. The matching tests live in `test/hyperopen/views/portfolio/vm/benchmarks_helpers_test.cljs`.

- Observation: The focused ClojureScript runner could not reach the new RED assertions until npm dependencies were installed in this worktree.
  Evidence: the first `node out/test.js` run failed before test execution with `Cannot find module 'lucide/dist/esm/icons/external-link.js'`; `npm install` restored the locked dependency tree and the same focused command then ran the two target namespaces to 4 failures and 0 errors.

- Observation: Count-based optimizer cache assertions need explicit cache reset isolation to stay trustworthy.
  Evidence: `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` stores the vault pool in the namespace-global atom `vault-candidates-cache`; without `reset-universe-candidates-cache!` before and after each test, earlier calls can prewarm the pool and suppress later `vault-row->candidate` instrumentation counts.

- Observation: Same-count vault row edits still need cache invalidation because matching, ranking, eligibility, and display depend on more than row count or address identity.
  Evidence: the review follow-up tests now prove that an equal-but-not-identical clone of `[:vaults :merged-index-rows]` reuses the cached pool, while same-count changes to `:name`, `:tvl`, and `[:relationship :type]` rebuild the pool and change candidate matching/order/eligibility as expected.

## Decision Log

- Decision: Keep the fix scoped to the optimizer universe search path and the v4 setup surface.
  Rationale: The issue description and root cause both point at repeated vault candidate rebuilding in the optimizer search flow. Broadening the change into history loading, route loading, or benchmark-selector refactors would add risk without addressing the measured bottleneck.
  Date/Author: 2026-04-30 / Codex

- Decision: Cache the normalized and sorted vault candidate pool, then apply selected-id filtering and query filtering on top of that cached vector for each `candidate-markets` call.
  Rationale: The expensive work is constructing and globally ordering the full vault pool. Filtering by selected ids and query must still happen per call because those inputs change independently of `[:vaults :merged-index-rows]`, but those per-call filters are linear over an already-sorted vector and do not need to repeat the full normalization and sort.
  Date/Author: 2026-04-30 / Codex

- Decision: Gate the blank-search optimization in `setup_v4_universe.cljs` instead of changing blank-query semantics inside `candidate-markets`.
  Rationale: `candidate-markets` is also used by the legacy `src/hyperopen/views/portfolio/optimize/universe_panel.cljs`, which currently relies on the existing blank-query behavior. Changing the shared function to return no candidates on blank input would be a broader behavior change than this issue calls for. The v4 surface can safely skip the call when it already knows no suggestions should render.
  Date/Author: 2026-04-30 / Codex

- Decision: Mirror the benchmark-selector cache pattern locally inside the optimizer search namespace instead of importing a view-model helper from `src/hyperopen/views/portfolio/vm/benchmarks.cljs`.
  Rationale: `universe_candidates.cljs` lives in optimizer application code. Pulling a dependency from that namespace into a portfolio view-model namespace would blur boundaries for a direct fix. A small local cache or a locally defined rows-signature helper keeps the dependency graph simple.
  Date/Author: 2026-04-30 / Codex

## Outcomes & Retrospective

Implemented the direct latency fix. `candidate-vaults` now reuses a cached, sorted vault candidate pool when `[:vaults :merged-index-rows]` is the same vector, and it also reuses the pool for equal cloned row vectors by comparing normalized row signatures. Selected-id exclusion and query filtering still run per call, so the cache cannot stale the active draft universe or typed search string.

The v4 setup UI now computes `searching?` before candidate lookup. Blank or whitespace-only search queries bind `markets` and `market-keys` to empty vectors, skip `candidate-markets`, omit `aria-activedescendant`, and render no result list. Non-blank queries keep the existing candidate row and keyboard contract.

Final validation on 2026-04-30 passed:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.universe-candidates-test --test=hyperopen.views.portfolio.optimize.setup-v4-layout-test
    # 24 tests, 155 assertions, 0 failures, 0 errors

    npm run check
    # passed

    npm test
    # 3645 tests, 20095 assertions, 0 failures, 0 errors

    npm run test:websocket
    # 461 tests, 2798 assertions, 0 failures, 0 errors

    PLAYWRIGHT_REUSE_EXISTING_SERVER=true npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "portfolio optimizer manual universe builder adds and removes vaults|portfolio optimizer manual universe search supports keyboard selection" --workers=1
    # 2 passed

The main residual risk is intentional: the cache assumes app-state row vectors are immutable. That matches the repository's ClojureScript state model. If future code mutates `:merged-index-rows` in place, the `identical?` fast path could return a stale pool; that would be a broader state-management violation rather than a local search-cache issue.

## Context and Orientation

The working directory is `/Users/barry/.codex/worktrees/6430/hyperopen`.

The optimizer draft universe is stored at `[:portfolio :optimizer :draft :universe]`. The search query is stored at `[:portfolio-ui :optimizer :universe-search-query]`. The search results are built by `hyperopen.portfolio.optimizer.application.universe-candidates/candidate-markets`, which currently combines asset-selector market candidates from `[:asset-selector :markets]` with vault candidates derived from `[:vaults :merged-index-rows]`.

`src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` owns the vault normalization helpers, candidate ranking, selected-id filtering, and the public `candidate-markets` function. `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` is the v4 setup UI that renders the search field and the suggestion list. `src/hyperopen/views/portfolio/optimize/universe_panel.cljs` is the older panel that still uses the same `candidate-markets` API with `{:ranking :asset-query}`.

In this repository, a vault search candidate is a map with `:key`, `:market-type :vault`, `:coin`, `:vault-address`, `:name`, `:symbol`, and `:tvl`. A vault row in `[:vaults :merged-index-rows]` is eligible only when it has a valid `:vault-address` and is not a child vault row where `[:relationship :type]` is `:child`.

## Performance Baseline

The bottleneck is not rendering six visible suggestion rows. The bottleneck is rebuilding the full vault candidate pool for each call. The current code normalizes and sorts the full eligible vault row set before any query filtering and before `take 6`. Under the issue workload of roughly 9.4k merged vault rows, that means every render and keypress can pay for a global vault candidate rebuild.

The implementation must record the baseline through deterministic work-count evidence, not flaky wall-clock thresholds. Add a focused test that instruments the new vault-candidate builder path with a counter and then calls `candidate-markets` against three scenarios: the exact same row vector, an equal-but-not-identical clone, and a materially changed row set. Before the fix, the builder counter would increment on every call because no cache exists. After the fix, it should increment once for the same rows, stay flat for the equal clone, and increment exactly once more after a relevant content change.

The workload assumption is that `[:vaults :merged-index-rows]` is large, but the UI only needs six ordered suggestions and can filter by query after the expensive ordering step has been cached. A simpler change such as lowering the result limit or moving `take 6` earlier is insufficient because the vault list still needs a stable global ordering by TVL and name before the first six can be chosen.

## Plan of Work

Start with RED tests in `test/hyperopen/portfolio/optimizer/application/universe_candidates_test.cljs`. Add a fixture that resets a new optimizer search cache before and after each test. Add one test that proves query matching still works for vault names, addresses, and selected-id exclusion after the cache is introduced. Add a second test that instruments a new internal builder path, invokes `candidate-markets` multiple times with the same rows, an equal clone, and a changed row set, and then asserts the expensive builder only reruns when the vault-row content meaningfully changes.

Next, update `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs`. Keep the public `candidate-markets` arities and return shape unchanged. Introduce a small cache dedicated to the normalized, sorted vault candidate pool. The cache should first reuse work when the `:merged-index-rows` vector is the identical object, and then fall back to a content signature so cloned-but-equivalent row vectors reuse the cached pool as well. Keep the precomputed vector limited to the direct vault search inputs that matter for ranking and matching: normalized vault address, display name, TVL, and relationship type. After a cache hit, continue filtering the cached vector by selected ids and by the normalized query, then merge those vault results with the asset-query results and preserve the existing top-six cap and ranking rules.

Then add a focused RED view test in `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs`. Rebind `universe-candidates/candidate-markets` to count calls or throw if it is invoked unexpectedly. Assert that the v4 universe section with a blank search query renders without calling `candidate-markets`, and that a non-blank query still calls it and renders the suggestion row contract already covered by the existing vault candidate test.

Finally, update `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`. Compute `searching?` before candidate lookup, and when the normalized query is blank bind `markets` and `market-keys` to empty vectors instead of calling `candidate-markets`. Preserve the existing non-blank rendering, keyboard event wiring, candidate row markup, and clear-button behavior. Do not change `src/hyperopen/views/portfolio/optimize/universe_panel.cljs` in this issue unless a shared helper extraction is unavoidable for compilation.

## Concrete Steps

Run commands from `/Users/barry/.codex/worktrees/6430/hyperopen`.

For the RED phase after the tests are added, run:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.universe-candidates-test --test=hyperopen.views.portfolio.optimize.setup-v4-layout-test

Expected RED result before implementation: the new cache-reuse assertions fail because the vault builder still runs on every call, and the blank-search v4 assertion fails because `setup_v4_universe.cljs` still invokes `candidate-markets` even when no suggestions render.

Observed RED result on 2026-04-30 15:04Z: the focused command failed with 4 failures and 0 errors. The vault-cache assertions observed `vault-row->candidate` counts of `6`, `7`, and `6` where the tests require `2`, `5`, and `2`, and the v4 blank-search assertion observed `candidate-markets` calls for `["   " "vault"]` where only `["vault"]` is allowed.

After implementation, run the same focused command again and expect both namespaces to pass with 0 failures and 0 errors.

Then run the repository gates required by `/hyperopen/AGENTS.md`:

    npm run check
    npm test
    npm run test:websocket

If the implementation broadens beyond the direct fix and changes observable browser interaction semantics, add the smallest relevant Playwright command at that time. That is not part of this plan’s default validation path.

## Validation and Acceptance

Acceptance is complete only when all of the following are true.

First, the focused ClojureScript command above passes, and the new optimizer-search cache test proves three observable behaviors in `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs`: a repeated call with the identical `:merged-index-rows` vector does not rebuild the sorted vault pool, a repeated call with equal cloned rows also reuses the cached pool, and a relevant row change forces exactly one rebuild.

Second, the same focused command proves search correctness did not regress. A query such as `"vault"` or a vault-name fragment still returns the expected `vault:<address>` candidate keys, excludes child vault rows, and excludes already-selected vault ids after the cache hit.

Third, the same focused command proves the v4 blank-search optimization. Rendering `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` with a blank `:portfolio-ui :optimizer :universe-search-query` must not call `candidate-markets` and must not render the search-results list. Rendering it with a non-blank query such as `"vault"` must still call `candidate-markets` and render the candidate row markup already used by the add flow.

Fourth, `npm run check`, `npm test`, and `npm run test:websocket` all pass from the repository root.

As an optional manual smoke check after automated validation, open `/portfolio/optimize/new`, leave the search input blank, verify that no suggestions appear, then type a known vault name and verify that up to six matching vault candidates appear with the same `+ add` affordance as before.

## Idempotence and Recovery

The planned changes are local and repeatable. Re-running the focused tests should be safe as long as the new cache-reset helper is used in test fixtures so cache state does not leak across test cases.

If the cache invalidation logic proves too brittle, keep the v4 blank-search guard and temporarily fall back to identity-only reuse rather than shipping a signature that can serve stale results. If the v4 guard unexpectedly breaks keyboard interaction, revert only the blank-search short-circuit, keep the cache work behind the passing tests, and record the UI follow-up as a new `bd` issue.

## Artifacts and Notes

Current hot-path evidence:

    src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs
      candidate-vaults ->
        (filter vault-row?)
        (sort-by vault-row-rank)
        (keep vault-row->candidate)
        (remove selected ids)
        (filter query)
        vec

    src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs
      (let [...
            markets (universe-candidates/candidate-markets state universe search-query)
            ...
            searching? (seq (normalized-text search-query))])

Nearby cache precedent:

    src/hyperopen/views/portfolio/vm/benchmarks.cljs
      memoized-vault-benchmark-selector-options-result
      memoized-eligible-vault-benchmark-rows

Plan revision note: 2026-04-30 13:38Z - Created the active ExecPlan for `hyperopen-8vx4` after mapping the current optimizer vault search hot path, the v4 blank-search call pattern, and the benchmark-selector memoization precedent.

## Interfaces and Dependencies

At completion, `hyperopen.portfolio.optimizer.application.universe-candidates/candidate-markets` must keep both existing arities, still accept `(state universe query)` and `(state universe query opts)`, and still return candidate maps with `:key`.

At completion, `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` should expose a small test-only reset helper such as `reset-universe-candidates-cache!` so focused tests can run without cache bleed. If a dynamic builder var is introduced for instrumentation, keep it in this namespace and use it only to count cache rebuilds in tests.

At completion, `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` must still dispatch `:actions/set-portfolio-optimizer-universe-search-query` on input changes and `:actions/handle-portfolio-optimizer-universe-search-keydown` on keydown for non-blank searches. The blank-search optimization must not require caller changes or route-level state changes elsewhere in the optimizer.
