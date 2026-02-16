# Optimize Account Info Rendering with Tab-Scoped Derivations and Identity Memoization

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The account information panel currently performs expensive data projection work on every render, even when the user is on a different tab. The heavy paths include unconditional balance/position/open-order derivations in `/hyperopen/src/hyperopen/views/account_info/vm.cljs` and repeated sorting in tab renderers. After this change, the panel will only compute expensive derived rows for the selected tab, and repeated renders with unchanged inputs will reuse memoized derived and sorted rows keyed by input identity plus sort state. Users should see smoother interaction during high-frequency updates, especially while viewing tabs that do not need balance or orderbook projections.

The user-visible way to confirm the change is to keep the Account Info panel open on one tab (for example Trade History) while other websocket/account state updates continue, then verify that inactive-tab derivation functions are not repeatedly invoked and active-tab tables remain correct and responsive.

## Progress

- [x] (2026-02-16 01:56Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/RELIABILITY.md`, and `/hyperopen/docs/FRONTEND.md` plus required UI companion guides.
- [x] (2026-02-16 01:56Z) Confirmed hotspot evidence in `/hyperopen/src/hyperopen/views/account_info/vm.cljs`, `/hyperopen/src/hyperopen/views/account_info/projections.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`, and `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`.
- [x] (2026-02-16 01:56Z) Authored this active ExecPlan with concrete milestones, commands, and acceptance checks.
- [x] (2026-02-16 02:05Z) Implemented tab-scoped derivation and identity-preserving source wiring in `/hyperopen/src/hyperopen/views/account_info/vm.cljs`, `/hyperopen/src/hyperopen/views/account_info_view.cljs`, and `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`.
- [x] (2026-02-16 02:06Z) Added identity caches for heavy derivations in `/hyperopen/src/hyperopen/views/account_info/derived_cache.cljs` and memoized sorting in `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` plus `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`.
- [x] (2026-02-16 02:10Z) Added regression tests in `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs` and `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` for selected-tab derivation gating and sort memoization behavior.
- [x] (2026-02-16 02:17Z) Ran required validation gates and confirmed pass: `npm run check`, `npm test`, and `npm run test:websocket`.

## Surprises & Discoveries

- Observation: `/hyperopen/src/hyperopen/views/account_info/vm.cljs` currently computes `build-balance-rows`, `collect-positions`, and `normalized-open-orders` unconditionally before tab dispatch (`line 15` through `line 19`), so inactive tabs still pay those costs.
  Evidence: `account-info-vm` binds all three derived collections before returning `:selected-tab` data.

- Observation: the same logical position projection is performed twice when Positions is active, once in VM for `:tab-counts` and again in renderer-level `positions-tab-content`.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/vm.cljs:16` and `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs:151` each call `collect-positions`.

- Observation: `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs:117` and `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs:325` sort rows on every render, even when rows identity and sort state are unchanged.
  Evidence: both functions call sort directly inside render-time `let` bindings with no memoization boundary.

- Observation: `account-info-vm` merges `:webdata2` and `:orders` into a new map each call (`/hyperopen/src/hyperopen/views/account_info/vm.cljs:7`), which breaks identity-based memoization if caches key off that merged value.
  Evidence: `(merge (:webdata2 state) (get state :orders))` allocates a fresh map every render.

- Observation: the codebase already uses a proven identity+parameter one-entry cache pattern in UI code.
  Evidence: `/hyperopen/src/hyperopen/views/asset_selector_view.cljs:328` through `:359` implements `processed-assets-cache` using `identical?` inputs plus sort/filter keys.

- Observation: preserving prior `merge` precedence between `:orders` and `:webdata2` is required for funding/trade history rendering parity in tests and runtime paths.
  Evidence: using raw `webdata2` only caused funding history panel regressions until VM switched to explicit “orders key wins when present” reads via `prefer-orders-value`.

- Observation: direct `with-redefs` against multi-arity projection fns in this codepath can fail under static arity dispatch in compiled ClojureScript tests.
  Evidence: tests raised `...cljs$core$IFn$_invoke$arity$4 is not a function` until cache collaborators moved behind dynamic vars and tests used `binding`.

## Decision Log

- Decision: Keep public Account Info entrypoints stable (`account-info-vm`, `account-info-panel`, tab action contracts) and scope changes to internal derivation/sorting behavior.
  Rationale: This isolates performance refactor risk and preserves existing view integration seams.
  Date/Author: 2026-02-16 / Codex

- Decision: Remove merged-map identity churn from VM internals by reading raw source slices (`:webdata2` and `:orders`) separately and passing stable substructures through derivation code.
  Rationale: Identity-based memoization is ineffective when a fresh merged wrapper is created each render.
  Date/Author: 2026-02-16 / Codex

- Decision: Use explicit one-entry identity memo caches for derived rows and sorted rows, with reset helpers for deterministic tests.
  Rationale: This pattern already exists in the repository and avoids unbounded memo tables.
  Date/Author: 2026-02-16 / Codex

- Decision: Compute heavy row projections only for the selected tab; keep tab labels/counts sourced from lightweight count projections that do not build full row maps.
  Rationale: This satisfies the optimization objective while keeping tab navigation informative.
  Date/Author: 2026-02-16 / Codex

- Decision: Preserve legacy merged-source semantics by preferring `:orders` keys when present and falling back to `:webdata2` keys otherwise.
  Rationale: This keeps existing funding/trade/order history projection behavior while removing merged-map identity churn.
  Date/Author: 2026-02-16 / Codex

- Decision: Introduce dynamic collaborator vars in derived-cache (`*build-balance-rows*`, `*collect-positions*`, `*normalized-open-orders*`) for deterministic VM tests.
  Rationale: This avoids brittle multi-arity `with-redefs` failures while keeping production call paths unchanged.
  Date/Author: 2026-02-16 / Codex

## Outcomes & Retrospective

Implemented. Account Info VM now derives heavy row data only for the selected tab and uses identity-aware caches for balances, positions, and open-order normalization. Open Orders and Trade History tab renderers now memoize sorted rows by input identity plus sort state, so no-op rerenders reuse prior sorted vectors.

What was achieved:

- Added `/hyperopen/src/hyperopen/views/account_info/derived_cache.cljs` with resettable one-entry caches and dynamic collaborator hooks for tests.
- Refactored `/hyperopen/src/hyperopen/views/account_info/vm.cljs` to remove unconditional heavy derivations, preserve `:orders`/`:webdata2` precedence via `prefer-orders-value`, and expose selected-tab derived payloads.
- Updated `/hyperopen/src/hyperopen/views/account_info_view.cljs` and `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` so positions tab can consume pre-derived positions without duplicate projection.
- Added sort memoization + cache reset APIs in `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` and `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`.
- Added regression tests covering selected-tab heavy-derivation gating, derived-row identity memoization, and renderer sort memoization behavior.

Validation evidence:

- `npm run check`: pass.
- `npm test`: pass (`907` tests, `4113` assertions, `0` failures, `0` errors).
- `npm run test:websocket`: pass (`116` tests, `458` assertions, `0` failures, `0` errors).

Residual risk:

- `:tab-counts :balances` now comes from lightweight eligibility logic rather than full `build-balance-rows`. This was chosen for performance and kept parity with existing fixtures; if balance-row inclusion rules change in projections, a dedicated count-parity test should be updated in the same PR.

## Context and Orientation

The Account Info panel entrypoint is `/hyperopen/src/hyperopen/views/account_info_view.cljs`. It calls `account-info-vm/account-info-vm` from `/hyperopen/src/hyperopen/views/account_info/vm.cljs`, then routes to tab renderers via `tab-renderers` and `tab-content`.

In this plan, “selected-tab derivation” means expensive row construction occurs only for the active tab key in `:account-info :selected-tab` (for example `:balances`, `:positions`, `:open-orders`, `:trade-history`). “Identity memoization” means if input collections/maps are the same reference (`identical?`) and sort controls are unchanged (`=` on column/direction), we return cached rows instead of recomputing.

Heavy transforms to de-amortize are currently in `/hyperopen/src/hyperopen/views/account_info/projections.cljs`, especially `build-balance-rows` at `line 510`, and in renderer-level sorting at `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs:117` and `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs:325`.

Repository guardrails relevant to this change are frontend interaction determinism and single atomic state transitions (`/hyperopen/docs/FRONTEND.md`), plus required validation gates (`npm run check`, `npm test`, `npm run test:websocket`) from `/hyperopen/AGENTS.md`.

## Plan of Work

Milestone 1 introduces a derivation boundary in `/hyperopen/src/hyperopen/views/account_info/vm.cljs` that computes only base metadata plus selected-tab derived payload. The VM will stop building all heavy rows unconditionally. This milestone also removes reliance on a freshly merged `webdata2` wrapper so identity caching can work from stable submaps.

Milestone 2 adds a small cache helper namespace for Account Info derived rows, for example `/hyperopen/src/hyperopen/views/account_info/derived_cache.cljs`. The helper will provide one-entry cache functions for balances rows, positions rows, and normalized open-orders rows keyed by input identity and selected tab. It will also expose a reset function used by tests to avoid cross-test cache coupling.

Milestone 3 memoizes renderer-level sorting in `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` and `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`. Sorting should only run when row input identity or sort state changes. Pagination behavior remains unchanged; only sorted-row derivation is memoized.

Milestone 4 updates wiring in `/hyperopen/src/hyperopen/views/account_info_view.cljs` and, if needed, `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` so active tab renderers consume already-derived selected-tab rows instead of recomputing projections in render-time code. If the positions tab keeps backward-compatible arities, existing call sites continue to work while the VM path uses pre-derived rows.

Milestone 5 adds regression tests in `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs` and `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` that prove inactive-tab heavy derivations are not executed and repeated renders do not re-sort unchanged data. The tests should use call counters (`with-redefs`) and cache-reset hooks to make behavior deterministic.

## Concrete Steps

1. From `/hyperopen`, implement VM derivation split and identity-preserving source handling.

   Edit `/hyperopen/src/hyperopen/views/account_info/vm.cljs` to separate base state extraction from selected-tab data derivation. Replace unconditional heavy bindings with selected-tab `case` dispatch. Ensure data sources are read from stable submaps instead of one merged wrapper map.

2. Add derived row cache helper.

   Create `/hyperopen/src/hyperopen/views/account_info/derived_cache.cljs` with one-entry cache utilities and explicit reset functions for tests.

3. Memoize sort operations in tab renderers.

   Update `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` and `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs` so sorted rows come from identity+sort memo caches.

4. Rewire tab renderers to consume selected-tab derived payload without duplicate projection work.

   Update `/hyperopen/src/hyperopen/views/account_info_view.cljs` (and optionally overloaded signatures in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`) to avoid recomputing positions/open-order rows already derived by VM.

5. Add tests and run required gates from `/hyperopen`.

   Run these commands:

       npm run check
       npm test
       npm run test:websocket

Expected transcript shape:

       npm run check
       ...
       Compilation completed. (no lint/doc failures)

       npm test
       ...
       0 failures, 0 errors

       npm run test:websocket
       ...
       0 failures, 0 errors

## Validation and Acceptance

Acceptance is met when the following are all true.

Inactive-tab heavy projections are skipped. In VM regression tests, selecting `:trade-history` does not call `build-balance-rows`, `collect-positions`, or `normalized-open-orders` unless their tab is selected.

Open Orders and Trade History sorting is memoized by input identity and sort state. Re-rendering with the same row collection and sort state does not re-invoke sort helpers; changing rows identity or sort direction does re-invoke once.

Visible tab behavior remains correct. Row content, pagination controls, and sort direction toggles continue to match existing behavior for active tabs.

Required gates pass: `npm run check`, `npm test`, and `npm run test:websocket`.

## Idempotence and Recovery

All edits are source-only and safe to re-run. Cache helpers are bounded one-entry structures, so no persistent migration is involved. If regressions appear, recovery is to bypass memoized helper calls and route directly to the previous derivation/sort functions while keeping regression tests in place; this is a local code rollback, not a data rollback.

## Artifacts and Notes

Changed paths:

- `/hyperopen/src/hyperopen/views/account_info/derived_cache.cljs` (new)
- `/hyperopen/src/hyperopen/views/account_info/vm.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`
- `/hyperopen/src/hyperopen/views/account_info_view.cljs`
- `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info_view_test.cljs`

Evidence to capture during implementation should include concise call-count assertions for inactive-tab projection skips and sort memoization hits/misses, plus required gate outputs.

## Interfaces and Dependencies

Public interfaces that must remain stable include `/hyperopen/src/hyperopen/views/account_info/vm.cljs` function `account-info-vm` and `/hyperopen/src/hyperopen/views/account_info_view.cljs` exported rendering functions used by tests.

If introducing cache helpers, define stable functions in `/hyperopen/src/hyperopen/views/account_info/derived_cache.cljs` with this shape:

    (memoized-balance-rows webdata2 spot account market-by-key) => vector
    (memoized-positions webdata2 perp-dex-states) => vector
    (memoized-open-orders orders snapshot snapshot-by-dex) => vector
    (memoized-open-orders-sort rows sort-state) => vector
    (memoized-trade-history-sort rows sort-state market-by-key) => vector
    (reset-derived-cache!) => nil

These helpers must remain pure in return values for the same inputs, with internal cache mutation hidden and test-resettable.

Plan revision note: 2026-02-16 01:56Z - Initial plan created from hotspot inspection and repository policy constraints; selected-tab derivation and identity-memoized sorting path chosen to target the reported render bottlenecks.
Plan revision note: 2026-02-16 02:17Z - Updated after implementation with completed milestones, test-gate evidence, runtime parity decisions (`:orders` precedence), and testing-dispatch discovery.
