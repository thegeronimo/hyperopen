# Account Info Fuzzy Coin Search Hot-Path Optimization

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, typing into the coin search fields in Open Orders, Trade History, and Order History remains responsive even when there are many rows. The user-visible behavior stays the same (same fuzzy matching semantics and same filtered rows), but the implementation avoids repeated per-row normalization work and avoids unnecessary full re-sorts on every keystroke.

A user can verify the result by opening each of the three tabs, typing incremental queries such as `n`, `nv`, `nvd`, and seeing rows update immediately while preserving the same sort order and filter semantics as before.

## Progress

- [x] (2026-02-26 14:55Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and current fuzzy-search implementation in shared helpers plus Open Orders, Trade History, and Order History tab pipelines.
- [x] (2026-02-26 14:59Z) Implemented compiled query + normalized candidate helper APIs in `/hyperopen/src/hyperopen/views/account_info/shared.cljs` while preserving `coin-matches-search?`.
- [x] (2026-02-26 14:59Z) Refactored Open Orders filtering path in `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` to cache sorted base rows and precomputed coin search candidates across keystrokes.
- [x] (2026-02-26 14:59Z) Refactored Trade History filtering path in `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs` to cache sorted base rows and precomputed coin search candidates across keystrokes.
- [x] (2026-02-26 14:59Z) Refactored Order History filtering path in `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs` to cache normalized/sorted base rows and precomputed coin search candidates across keystrokes.
- [x] (2026-02-26 14:59Z) Updated tab tests in `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders_test.cljs`, `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`, and `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs` to validate no re-sort on coin-search-only updates.
- [x] (2026-02-26 14:59Z) Ran required validation gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Existing memoization tests currently expect sort to rerun when only `:coin-search` changes, which encodes the current performance issue directly in test expectations.
  Evidence: `test/hyperopen/views/account_info/tabs/open_orders_test.cljs` and `test/hyperopen/views/account_info/tabs/trade_history_test.cljs` increment sort-call counts after coin-search changes.

- Observation: `npm test -- <file>` does not support file filtering in this repository and falls back to running the whole generated suite.
  Evidence: Running `npm test -- open_orders_test.cljs` printed `Unknown arg: open_orders_test.cljs` and then executed all test namespaces.

- Observation: The shared helper refactor initially introduced a missing closing parenthesis in the optimized subsequence function.
  Evidence: ClojureScript compiler error `Unexpected EOF while reading item ... starting at line 48` in `/hyperopen/src/hyperopen/views/account_info/shared.cljs`.

## Decision Log

- Decision: Preserve fuzzy semantics (substring OR ordered subsequence) and optimize by caching normalized candidates and sorted base rows instead of changing matching behavior.
  Rationale: This directly addresses keystroke cost while minimizing user-facing risk and avoiding regressions in search quality.
  Date/Author: 2026-02-26 / Codex

- Decision: Keep changes scoped to Open Orders, Trade History, Order History, and shared search helpers.
  Rationale: This matches the reported hot path and avoids unrelated churn.
  Date/Author: 2026-02-26 / Codex

- Decision: Change tab pipelines from `direction/status filter -> coin search -> sort` to `direction/status filter -> sort -> coin search`.
  Rationale: Filtering a pre-sorted base set preserves final row ordering but avoids re-sorting when only the search query changes.
  Date/Author: 2026-02-26 / Codex

- Decision: Build per-pass coin candidate caches keyed by coin symbol when constructing indexed rows.
  Rationale: Many rows share the same coin string, so this avoids repeated `resolve-coin-display` and repeated candidate normalization inside the same pass.
  Date/Author: 2026-02-26 / Codex

## Outcomes & Retrospective

Implemented the hot-path optimization for fuzzy coin search in Open Orders, Trade History, and Order History. Shared search helpers now support compiled query usage and normalized candidate vectors, while keeping `coin-matches-search?` backward compatible. Each affected tab now memoizes a base sorted set and precomputed normalized candidate index, so changing only `:coin-search` reuses existing sort results and avoids repeated per-row normalization.

The updated tab tests verify the new memoization expectation: search-only changes do not trigger additional sort calls when data, non-search filters, and sort state are unchanged. Full repository validation gates passed.

## Context and Orientation

Coin fuzzy search helpers currently live in `/hyperopen/src/hyperopen/views/account_info/shared.cljs`. The current helper `coin-matches-search?` normalizes both query and candidate per invocation and runs ordered subsequence matching. In the affected tabs, this helper is called for coin, base label, and prefix label per row on each keystroke.

Affected tab modules:

- `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs`

Each tab uses a one-entry memoization atom for sorted rows, keyed by row identity plus filter/sort/search fields. When `:coin-search` changes, cache misses trigger full filtering and full sorting work even if row data and non-search filters are unchanged.

Test files that currently lock in this behavior:

- `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs`

## Plan of Work

Milestone 1 updates shared search helpers to support a compiled-query flow and pre-normalized candidates. The existing `coin-matches-search?` interface remains available so unchanged callers continue to work.

Milestone 2 refactors each affected tab memoization pipeline to compute and cache a base sorted row set for a given data/sort/filter key. Coin-search then filters that base set using precomputed normalized search candidates instead of re-normalizing strings for each row per keystroke.

Milestone 3 updates tests so memoization assertions validate the new behavior: changing only `:coin-search` should not rerun sort for unchanged data/sort/non-search filters. Existing fuzzy matching assertions remain and must continue passing.

Milestone 4 runs required repository validation gates and records outcomes.

## Concrete Steps

1. Implement shared helper additions and tab pipeline changes.

   cd /Users//projects/hyperopen
   npm test -- open_orders_test.cljs
   npm test -- trade_history_test.cljs
   npm test -- order_history_test.cljs

   Observed result: this repository test runner does not accept per-file args; each command runs the full generated test suite instead.

2. Run required full validation gates.

   cd /Users//projects/hyperopen
   npm run check
   npm test
   npm run test:websocket

   Expected result: all commands exit with status 0.

## Validation and Acceptance

The change is accepted when all of the following are true:

1. Open Orders, Trade History, and Order History still return the same fuzzy matches for representative queries (contains and ordered subsequence).
2. For unchanged input rows, non-search filters, and sort state, changing only `:coin-search` does not re-trigger sorting in the tab memoization tests.
3. Required validation gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

Edits are idempotent. Re-running tests is safe.

If a regression appears, rollback is straightforward by restoring previous per-tab memoization behavior and retaining helper additions behind existing `coin-matches-search?` compatibility.

## Artifacts and Notes

Validation artifacts:

1. `npm run check` passed (hiccup/docs lint + app/test compile succeeded).
2. `npm test` passed (`Ran 1396 tests containing 6884 assertions. 0 failures, 0 errors.`).
3. `npm run test:websocket` passed (`Ran 153 tests containing 696 assertions. 0 failures, 0 errors.`).

## Interfaces and Dependencies

No external dependencies will be added.

Shared helper interfaces to add in `/hyperopen/src/hyperopen/views/account_info/shared.cljs`:

- `compile-coin-search-query`
- `coin-search-query-blank?`
- `normalized-coin-search-candidates`
- `normalized-coin-candidates-match?`

Existing interface preserved:

- `coin-matches-search?`

Plan revision note: 2026-02-26 14:55Z - Initial plan created for fuzzy coin search hot-path optimization in account-info tabs.
Plan revision note: 2026-02-26 14:59Z - Marked implementation complete, documented discoveries/decisions, and recorded successful validation gate results.
