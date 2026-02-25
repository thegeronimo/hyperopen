# Account Info Coin Search Fuzzy Filter

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, users can type in a compact search field on the Balances, Positions, and Order History tabs and the table rows will filter immediately as they type. The search works as an additional filter on top of the existing dropdown filter and uses fuzzy matching against coin names so partial and non-contiguous input can still find the intended symbol.

A user can verify behavior by opening each of the three tabs, typing values like `eth`, `nv`, or `sol`, and seeing rows update without pressing Enter.

## Progress

- [x] (2026-02-25 22:54Z) Reviewed planning and UI policy docs: `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, `/hyperopen/docs/agent-guides/trading-ui-policy.md`.
- [x] (2026-02-25 22:54Z) Audited current account-info filter/search code paths in `/hyperopen/src/hyperopen/views/account_info_view.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs`, and account-history action/runtime wiring.
- [x] (2026-02-25 23:04Z) Added account-info search state defaults and wired new `:actions/set-account-info-coin-search` through actions, contracts, and runtime registries.
- [x] (2026-02-25 23:04Z) Added shared fuzzy coin matching helpers and applied search filtering in Balances, Positions, and Order History pipelines.
- [x] (2026-02-25 23:04Z) Added search UI controls (with icon and live `:input` dispatch) for Balances, Positions, and Order History header actions.
- [x] (2026-02-25 23:04Z) Added/updated tests for action behavior, navigation wiring, per-tab fuzzy filtering, fixture defaults, and VM projection.
- [x] (2026-02-25 23:04Z) Ran required validation gates: `npm run check`, `npm test`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Existing dropdown filters are tab-scoped and live in three different state branches (`:positions`, `:order-history`, and a top-level balances toggle), so search state must be added deliberately per tab rather than as one global key.
  Evidence: `/hyperopen/src/hyperopen/state/app_defaults.cljs` and `/hyperopen/src/hyperopen/views/account_info/vm.cljs`.

- Observation: Action contract coverage is strict and compares registered runtime action IDs against schema contracts, so adding one new action requires synchronized updates in multiple registries.
  Evidence: `/hyperopen/test/hyperopen/schema/contracts_coverage_test.cljs`.

- Observation: `npm test -- <pattern>` does not support path filtering in this repository's Node test entrypoint and still executes the full suite.
  Evidence: Running `npm test -- account/history/actions` reported `Unknown arg: account/history/actions` and then executed all tests successfully.

## Decision Log

- Decision: Implement fuzzy matching as deterministic local logic (exact contains OR ordered subsequence) rather than introducing a dependency.
  Rationale: Keeps runtime deterministic, avoids bundle growth, and is sufficient for short coin symbol searches.
  Date/Author: 2026-02-25 / Codex

- Decision: Scope search to Balances, Positions, and Order History only, as requested, and leave Open Orders and Trade History unchanged.
  Rationale: Minimizes risk and matches requested behavior exactly.
  Date/Author: 2026-02-25 / Codex

- Decision: Store search state at `:account-info :balances-coin-search` for Balances and as `:coin-search` inside existing `:positions` and `:order-history` state maps.
  Rationale: Keeps minimal churn for balances while co-locating search with existing tab-scoped filter state for positions/order-history.
  Date/Author: 2026-02-25 / Codex

- Decision: Reset Order History pagination to page 1 when its search value changes.
  Rationale: Prevents stale page offsets from showing empty pages after search narrowing.
  Date/Author: 2026-02-25 / Codex

## Outcomes & Retrospective

Implemented live fuzzy coin search across Balances, Positions, and Order History while preserving existing dropdown filter behavior. Added one new account-history action (`:actions/set-account-info-coin-search`) and wired it through defaults, contracts, runtime registration, and public action exports. Added reusable fuzzy-matching helpers in shared account-info utilities and integrated them into each tab's filtering pipeline.

All required validation gates passed:

1. `npm run check`
2. `npm test`
3. `npm run test:websocket`

## Context and Orientation

The account info panel is rendered by `/hyperopen/src/hyperopen/views/account_info_view.cljs`. Tab header controls (including existing filter dropdowns) are built there in `tab-navigation` and helper functions such as `positions-header-actions` and `order-history-header-actions`.

Row filtering and sorting are implemented inside each tab module:

- Balances: `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`
- Positions: `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`
- Order History: `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs`

Account-info state defaults are defined in `/hyperopen/src/hyperopen/state/app_defaults.cljs`, and action handling lives in `/hyperopen/src/hyperopen/account/history/actions.cljs`. Runtime action registration and contracts are enforced through:

- `/hyperopen/src/hyperopen/core/public_actions.cljs`
- `/hyperopen/src/hyperopen/core/macros.clj`
- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
- `/hyperopen/src/hyperopen/registry/runtime.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`

The account-info view model projection is in `/hyperopen/src/hyperopen/views/account_info/vm.cljs`, and tests are primarily in:

- `/hyperopen/test/hyperopen/account/history/actions_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/navigation_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/balances_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs`

## Plan of Work

Milestone 1 introduces new account-info search state and a single action to update tab-specific search input values. The state will be reset-safe and default to empty strings for `:balances`, `:positions`, and `:order-history`.

Milestone 2 adds shared fuzzy matching helpers in the account-info shared module so all three tabs use one deterministic search rule. Matching will normalize case and trim whitespace, then succeed on either direct substring or ordered subsequence to support input like `nv` matching `NVDA`.

Milestone 3 applies search filtering in each tab’s existing row pipeline before rendering and in a way that preserves current sort/direction filters. Positions and order-history memoized cache keys will include the normalized search term to keep cache correctness.

Milestone 4 updates tab header actions to render a compact search input (with search icon) beside existing controls for Balances, Positions, and Order History. Input events will dispatch the new action, and filtering will update immediately on `:input`.

Milestone 5 updates tests to cover:

1. Action updates and normalization.
2. Header control rendering and dispatch wiring.
3. Fuzzy search filtering behavior in all three tabs.
4. View-model projection of new search state defaults.

Milestone 6 runs required validation gates and records outcomes.

## Concrete Steps

1. Update account-info state/action/runtime wiring for search.

   cd /Users//projects/hyperopen
   npm test -- account/history/actions

   Expected result: account-history action tests pass with new search action assertions.

2. Add shared fuzzy helpers and apply per-tab search filtering.

   cd /Users//projects/hyperopen
   npm test -- account_info/tabs/balances
   npm test -- account_info/tabs/positions
   npm test -- account_info/tabs/order_history

   Expected result: each tab suite includes passing search filter coverage.

3. Add tab-navigation search UI and view-model/search-state tests.

   cd /Users//projects/hyperopen
   npm test -- account_info/navigation
   npm test -- account_info/vm

   Expected result: navigation and vm tests pass with the new search controls/state.

4. Run required repository validation gates.

   cd /Users//projects/hyperopen
   npm run check
   npm test
   npm run test:websocket

   Expected result: all commands exit with status 0.

## Validation and Acceptance

The change is accepted when all of the following are true:

1. Balances, Positions, and Order History tabs each show a search field in the header controls.
2. Typing in search filters rows immediately without Enter key presses.
3. Filtering is fuzzy by coin name and works together with existing dropdown direction/status filters.
4. Clearing search restores rows based on existing non-search filters.
5. `npm run check`, `npm test`, and `npm run test:websocket` pass.

## Idempotence and Recovery

These edits are idempotent. Re-running tests and commands is safe.

If fuzzy filtering introduces regressions, isolate by temporarily bypassing only the search predicate in each tab and confirm the existing sort/dropdown behavior remains unchanged before re-applying the fuzzy logic.

## Artifacts and Notes

Primary implementation and validation artifacts will be added here after the code lands and tests run.

Validation artifacts:

1. `npm run check` passed (lint/doc checks + app/test compile).
2. `npm test` passed (1366 tests, 6691 assertions, 0 failures).
3. `npm run test:websocket` passed (149 tests, 647 assertions, 0 failures).

## Interfaces and Dependencies

No new external dependencies will be added.

New interface introduced by this plan:

- `:actions/set-account-info-coin-search` with args `(s/tuple ::keyword-or-string any?)` represented as `[tab-key search-value]`.

Shared helper interface added in `/hyperopen/src/hyperopen/views/account_info/shared.cljs`:

- `normalize-coin-search-query`
- `coin-matches-search?`

Plan revision note: 2026-02-25 22:54Z - Initial plan authored after repository and policy audit.
Plan revision note: 2026-02-25 23:04Z - Marked implementation complete, documented design decisions and discoveries, and recorded successful validation gate results.
