# Account Info Cache-Key Stability Under WebSocket Churn

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, account-info table memo caches will stay warm when websocket churn recreates row vectors without changing row content. This reduces avoidable normalize/sort/filter work during frequent websocket updates and lowers per-render CPU cost in the account panel.

A contributor can verify the result by running account-info tab tests that simulate churn (new vector identity, same row contents) and confirming expensive operations are not re-run. The same contributor can also verify that real data changes still invalidate caches and produce correct output.

## Progress

- [x] (2026-02-26 01:38Z) Reviewed `/hyperopen/.agents/PLANS.md` and current active ExecPlan conventions in `/hyperopen/docs/exec-plans/active/`.
- [x] (2026-02-26 01:39Z) Audited memoized cache paths in `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`, and `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs`.
- [x] (2026-02-26 01:40Z) Audited existing cache/memo tests in `/hyperopen/test/hyperopen/views/account_info/tabs/*_test.cljs` to identify current behavior contracts that encode identity fragility.
- [x] (2026-02-26 01:41Z) Authored this ExecPlan with concrete milestones, file-level edits, and acceptance criteria.
- [x] (2026-02-26 15:11Z) Implemented `/hyperopen/src/hyperopen/views/account_info/cache_keys.cljs` and `/hyperopen/test/hyperopen/views/account_info/cache_keys_test.cljs` with deterministic row/value signatures and identity-first match-state helpers.
- [x] (2026-02-26 15:18Z) Refactored open-orders, positions, trade-history, and order-history memo caches to use shared signature fallback; removed deep `=` map comparison from order-history base guard; split trade/order base reuse from market-index reuse.
- [x] (2026-02-26 15:26Z) Updated churn regression tests in all four tab test files to assert cloned-equivalent row reuse, real-content invalidation, and market-map index-only invalidation behavior.
- [x] (2026-02-26 15:32Z) Ran required gates: `npm run check`, `npm test`, and `npm run test:websocket` with passing results.

## Surprises & Discoveries

- Observation: `order_history` already has incremental reuse (`same-base?`) but still uses deep map equality against `market-by-key` in its hot cache guard.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs` uses `(= market-by-key (:market-by-key cache))` in `memoized-order-history-rows`.

- Observation: Existing tests intentionally assert cache invalidation when rows are cloned with identical values (`(into [] rows)`), which encodes identity-only cache behavior and confirms current fragility under churn.
  Evidence: `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs`, `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders_test.cljs`, and `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs` all expect extra sort/normalize calls on cloned vectors.

- Observation: `trade_history` coin sorting still depends on market label resolution; base-sort reuse can ignore market-map churn for non-`Coin` sorts, but must include market equivalence when sorting the `Coin` column.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs` `sort-trade-history-by-column` resolves the `Coin` accessor through `resolve-coin-display`.

- Observation: ClojureScript `with-redefs` cannot target private helper vars directly in these tests, so index-builder counters need dynamic wrapper vars exposed from production namespaces.
  Evidence: test compile failed with `ClassCastException` during macroexpansion when redefining `#'trade-history-tab/build-trade-history-coin-search-index` and `#'order-history-tab/build-order-history-coin-search-index`; resolved by adding `*build-*-coin-search-index*` vars.

- Observation: Coin search performance already improved with pre-indexed candidate vectors, so cache-hit stability is now the remaining higher-leverage gap.
  Evidence: `build-*-coin-search-index` and `filter-*-by-coin-search` pipelines in trade/order/open-order tabs precompute normalized candidates and reuse them when cache hits.

## Decision Log

- Decision: Introduce a shared account-info cache-key helper that computes stable row signatures and exposes identity-first, signature-fallback matching.
  Rationale: This removes duplicated cache-key logic and enables consistent churn-safe behavior across tabs.
  Date/Author: 2026-02-26 / Codex

- Decision: Preserve identity checks as the zero-cost fast path, and compute signatures only when identity misses.
  Rationale: This keeps best-case performance unchanged while improving behavior for churned-but-equivalent row vectors.
  Date/Author: 2026-02-26 / Codex

- Decision: Remove deep `=` map equality from `order_history` hot cache checks; use identity-first + signature-fallback matching for market-map index reuse, and decouple market-dependent index reuse from base sort reuse.
  Rationale: Deep map equality is avoidable in the render hot path, base sort does not require `market-by-key`, and signature fallback keeps index caches warm under equivalent-content market-map churn.
  Date/Author: 2026-02-26 / Codex

- Decision: Update tests to assert semantic cache stability (same content) rather than object identity stability (same vector instance).
  Rationale: Websocket churn often produces new object identities; tests should reflect intended UI/runtime behavior under churn.
  Date/Author: 2026-02-26 / Codex

- Decision: Keep trade-history base-sort reuse market-sensitive only when the active sort column is `Coin`; otherwise treat market changes as index-only invalidation.
  Rationale: This preserves sort correctness for market-derived coin labels while still preventing avoidable sort work under market-map churn for non-coin sorts.
  Date/Author: 2026-02-26 / Codex

- Decision: Add dynamic wrapper vars for trade/order coin-search index builders to support deterministic counter tests with `with-redefs`.
  Rationale: This preserves encapsulation of private helpers while enabling regression tests to validate index rebuild behavior precisely.
  Date/Author: 2026-02-26 / Codex

## Outcomes & Retrospective

Implemented all four milestones end-to-end. The new shared helper (`cache_keys.cljs`) provides deterministic row signatures (count + rolling hash + xor hash) and generic value signatures, plus identity-first match-state helpers used by all account-info tab memo caches.

`open_orders`, `positions`, `trade_history`, and `order_history` now preserve O(1) identity fast-path behavior while staying warm under cloned-but-equivalent row vectors. `order_history` no longer uses deep map equality in its hot base guard. Trade/order histories now separate base-sort reuse from market-index reuse, with a correctness guard that only ties trade base sort to market equivalence when sorting the `Coin` column.

Regression tests were updated to enforce semantic reuse under `(into [] rows)` churn and to enforce invalidation on real content changes. Trade/order history tests now explicitly prove that market-map-only changes do not force base sort work.

All required validation gates passed on 2026-02-26:

- `npm run check` passed (lint/doc checks + app/test compile).
- `npm test` passed (`Ran 1399 tests containing 6918 assertions. 0 failures, 0 errors.`).
- `npm run test:websocket` passed (`Ran 153 tests containing 696 assertions. 0 failures, 0 errors.`).

Remaining gaps: none identified for this scope. No reliability doc update was required because the invariant change is local to account-info memoization boundaries and is fully covered by view-level regression tests.

## Context and Orientation

The account-info tabs render large tables that are sorted, filtered, and optionally coin-searched. To avoid recomputing these pipelines every render, each tab stores a memo cache atom:

- `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` → `sorted-open-orders-cache`
- `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` → `sorted-positions-cache`
- `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs` → `sorted-trade-history-cache`
- `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs` → `sorted-order-history-cache`

In this plan, “identity churn” means receiving logically equivalent row collections with new vector identity (for example, recreated vectors during websocket/state transitions). “Cache hit stability” means the memo cache should still hit when rendered output inputs are semantically unchanged, even if identity changed.

Today, most caches rely primarily on `identical?` row references. Under churn, this causes redundant work. `order_history` also performs deep `=` equality on `market-by-key` in a hot guard, which adds avoidable per-render cost.

The target behavior is:

- If row content is equivalent and sort/filter settings are unchanged, memo caches should reuse base sorted artifacts.
- If only coin-search text changes, caches should reuse base sorted/indexed data and only re-filter.
- If `market-by-key` changes identity but base row content/sort inputs are unchanged, trade/order caches should avoid re-normalizing/re-sorting and only rebuild market-dependent index artifacts when needed.

## Plan of Work

### Milestone 1: Add Shared Cache-Key Helpers and Churn Signature Contract

Create a focused helper module for account-info cache keys (for example `/hyperopen/src/hyperopen/views/account_info/cache_keys.cljs`). The helper should provide:

- identity-first checks,
- stable row signature generation for vectors/sequences,
- small utilities for comparing cache signatures and deciding “same base input” versus “same search/index input”.

Define signatures in plain terms so a novice can maintain them. A practical shape is a small map with count and rolling hash derived from rows, computed only when identity fast-path misses.

Add tests for this helper module to prove deterministic signatures and expected matching behavior.

### Milestone 2: Refactor Tab Memo Caches to Use Stable Signatures

Update each tab memo function to use the shared helper:

- `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs`

For all tabs, retain identity fast-path and add signature fallback for rows. This allows cloned-but-equivalent row vectors to hit cache and skip expensive sort work.

For trade/order tabs, separate base cache reuse from market-index reuse:

- Base reuse key: rows signature + sort/filter settings that affect sort order.
- Index reuse key: base key + market map identity/signature for coin-label resolution.

For order history specifically, remove deep `=` map equality from the hot `same-base?` check and avoid tying base sort reuse to `market-by-key` equality.

### Milestone 3: Add Churn Regression Tests and Update Existing Expectations

Update existing tab memoization tests to align with churn-safe behavior:

- `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs`

Add or modify tests so they assert:

- cloned equivalent rows do not increase sort/normalize counters,
- real content changes do increase counters,
- changing coin-search alone does not re-sort,
- order/trade market-map changes only invalidate index-dependent work, not base sort work.

Where counters are used (`sort-calls`, `normalize-calls`), keep tests deterministic with `with-redefs` around expensive functions.

### Milestone 4: Validate End-to-End and Document Results

Run required validation gates and capture concise evidence in this plan’s artifacts section. If implementation reveals notable invariants worth preserving (for example, “base sort must not depend on market map”), add a short reliability note to `/hyperopen/docs/RELIABILITY.md`.

Update `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` to reflect actual implementation outcomes.

## Concrete Steps

From `/hyperopen`:

1. Add shared cache-key helper module and tests.

   Edit/create:

   - `/hyperopen/src/hyperopen/views/account_info/cache_keys.cljs` (new)
   - `/hyperopen/test/hyperopen/views/account_info/cache_keys_test.cljs` (new)

   Run:

       npm test

   Expected outcome: helper tests pass and no regressions in existing account-info tests.

2. Refactor memoized cache logic in tab modules.

   Edit:

   - `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`
   - `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`
   - `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`
   - `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs`

   Run:

       npm test

   Expected outcome: account-info tab tests compile and behavior remains correct while cache-hit behavior under churn improves.

3. Update tab memoization tests for churn-safe expectations.

   Edit:

   - `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders_test.cljs`
   - `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`
   - `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`
   - `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs`

   Run:

       npm test

   Expected outcome: tests demonstrate no extra sort/normalize work for equivalent-content churn and proper invalidation for actual content changes.

4. Run required gates and capture evidence.

   Run:

       npm run check
       npm test
       npm run test:websocket

   Expected outcome: all required gates pass.

## Validation and Acceptance

Acceptance is complete when all conditions below are true.

1. Account-info memo caches no longer depend solely on row identity; cloned equivalent rows reuse cached base sort artifacts.
2. `order_history` no longer performs deep `=` comparison on `market-by-key` in its hot base-cache guard.
3. Trade and order history caches separate base-sort invalidation from market-index invalidation.
4. Tab tests prove churn-safe reuse and correct invalidation on real data changes.
5. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

This work is safe to apply incrementally. Each tab can be migrated independently while keeping tests green. If a migration causes stale cache behavior, temporarily disable signature fallback for that tab and keep identity fast-path while preserving newly added tests for diagnosis.

Avoid broad rewrites of sorting/filtering logic while introducing cache keys; keep changes limited to cache guards and memo payload structure. If regressions appear, revert only the affected tab’s cache logic and rerun tests before proceeding.

## Artifacts and Notes

Primary implementation files expected to change:

- `/hyperopen/src/hyperopen/views/account_info/cache_keys.cljs` (new)
- `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs`

Primary tests expected to change:

- `/hyperopen/test/hyperopen/views/account_info/cache_keys_test.cljs` (new)
- `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs`

Evidence to capture while implementing:

- before/after counter assertions showing cache behavior on `(into [] rows)` churn inputs,
- before/after evidence that order-history base sort is not invalidated by market-map-only changes,
- required-gate command outputs.

Captured evidence from this implementation:

- Churn counter behavior is asserted in:
  `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders_test.cljs`,
  `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`,
  `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`,
  `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs`.
- Helper signature determinism and match-state behavior is asserted in:
  `/hyperopen/test/hyperopen/views/account_info/cache_keys_test.cljs`.
- Validation outputs:
  - `npm run check` completed with zero failures.
  - `npm test` reported `Ran 1399 tests containing 6918 assertions. 0 failures, 0 errors.`
  - `npm run test:websocket` reported `Ran 153 tests containing 696 assertions. 0 failures, 0 errors.`

## Interfaces and Dependencies

No external dependencies are needed.

Internal interface additions:

- Shared cache-key helper API in `/hyperopen/src/hyperopen/views/account_info/cache_keys.cljs`, expected to expose deterministic signature and matching utilities used by tab memoizers.

Interfaces that must remain stable:

- Public view entry points in `/hyperopen/src/hyperopen/views/account_info_view.cljs` and tab rendering behavior.
- Existing action dispatch semantics for sorting/filtering/search.

Performance contract after implementation:

- identity fast-path remains O(1),
- fallback signature computation runs only on identity miss,
- deep map equality is removed from order-history hot guard,
- base sort recomputation occurs only when semantically required.

Plan revision note: 2026-02-26 01:41Z - Initial ExecPlan created to harden account-info memo cache hit stability under websocket churn and remove deep map equality from order-history hot checks.
Plan revision note: 2026-02-26 15:32Z - Implemented milestones 1-4, updated progress/discoveries/decisions/outcomes with final code and test evidence, and recorded required gate results.
