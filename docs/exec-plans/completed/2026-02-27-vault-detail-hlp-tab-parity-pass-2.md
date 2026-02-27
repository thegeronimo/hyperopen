# Vault Detail HLP Tab-by-Tab Visual and Functional Parity (Pass 2)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, `/vaults/0xdfc24b077bc1425ad1dea75bcb6f8158e10df303` in Hyperopen should behave much closer to Hyperliquid tab-by-tab, not just visually but functionally. Users should be able to switch chart series (`Account Value` and `PNL`) and see each activity tab render structured data or correct empty states rather than placeholder text.

A user can verify this by opening the vault detail page, clicking each tab from `Balances` through `Depositors`, and confirming that each tab has a dedicated table panel, working tab switching, and data sourced from live vault APIs where available.

## Progress

- [x] (2026-02-27 01:55Z) Re-ran parity research inputs and audited current implementation gaps in `/hyperopen/src/hyperopen/views/vault_detail_view.cljs`, `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`, and vault runtime wiring.
- [x] (2026-02-27 01:55Z) Confirmed live discrepancy evidence from tab audit artifacts at `/hyperopen/tmp/vault-tab-audit/2026-02-27T01-41-29-502Z-direct/`.
- [x] (2026-02-27 01:55Z) Authored this ExecPlan with a concrete implementation path.
- [x] (2026-02-27 02:11Z) Implemented chart-series parity via `:vaults-ui :detail-chart-series`, `:actions/set-vault-detail-chart-series`, VM series selection, and interactive chart tab buttons in vault detail view.
- [x] (2026-02-27 02:11Z) Implemented vault-detail activity fetches and state/projections for fills, funding history, order history, and ledger updates, including runtime effect wiring and deterministic effect-order contract updates.
- [x] (2026-02-27 02:11Z) Preserved normalized follower row payloads in vault detail endpoint normalization and added `:followers-count` for tab count rendering.
- [x] (2026-02-27 02:11Z) Replaced placeholder tab content with structured tab-specific table renderers and explicit loading/error/empty states for `TWAP`, `Funding History`, `Order History`, `Deposits and Withdrawals`, and `Depositors`.
- [x] (2026-02-27 02:11Z) Expanded and updated tests across actions/effects/projections/endpoints/runtime wiring/view model/view contracts, including new chart-series action dispatch coverage.
- [x] (2026-02-27 02:11Z) Ran required validation gates successfully: `npm run check`, `npm test`, and `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Current chart series controls are rendered as non-interactive spans, so `Account Value` and `PNL` cannot switch series even though both labels exist.
  Evidence: `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` renders chart series controls as `:span` and no dispatch action; tab audit showed identical chart state after clicks.

- Observation: Vault details normalization currently converts followers into a scalar count, which prevents rendering depositor rows.
  Evidence: `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs` sets `:followers` via `followers-count` instead of preserving list payload.

- Observation: `webData2` for this vault includes positions/open orders/TWAP state, but not direct fills/funding/order-history/deposit-withdraw rows; those must come from additional info methods.
  Evidence: direct API response checks for `webData2` and `userNonFundingLedgerUpdates` against `https://api.hyperliquid.xyz/info`.

## Decision Log

- Decision: Add dedicated vault-detail history fetches keyed by vault address instead of overloading existing global account-history state.
  Rationale: Vault detail should remain deterministic and isolated from wallet/account tabs; keyed vault caches avoid cross-view data coupling.
  Date/Author: 2026-02-27 / Codex

- Decision: Implement chart-series selection as explicit `:vaults-ui` state with a dedicated action, mirroring portfolio chart-tab patterns.
  Rationale: This gives deterministic interaction behavior, testability, and prevents implicit series selection bugs.
  Date/Author: 2026-02-27 / Codex

- Decision: Reuse account/portfolio table density and structure patterns for vault detail activity tabs instead of introducing a vault-specific table system.
  Rationale: This closes the readability/compression gap faster, reduces UI divergence, and keeps maintenance aligned with existing tested table primitives.
  Date/Author: 2026-02-27 / Codex

## Outcomes & Retrospective

Implemented. The vault detail page now has working chart series switching (`PNL` and `Account Value`) and tab-by-tab functional data wiring for balances, positions, open orders, TWAP, trade history, funding history, order history, deposits/withdrawals, and depositors.

Primary parity deltas closed in this pass:

1. Chart controls were non-interactive; now each series tab dispatches and updates selected series state.
2. Activity tabs that previously rendered generic placeholders now render structured tables with real row derivations and scoped loading/error/empty handling.
3. Depositor tab now has access to normalized follower rows because endpoint normalization preserves payload rows and separately tracks follower count.
4. Vault route loading now fetches the additional datasets Hyperliquid uses for activity parity (fills, funding history, order history, ledger updates).

Residual differences versus Hyperliquid remain in stylistic details (exact typography, pixel spacing, and some column semantics), but the major functional parity gaps called out in this pass are closed and covered by tests.

## Context and Orientation

The vault detail route is rendered by `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` and derives data through `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`. Route load orchestration lives in `/hyperopen/src/hyperopen/vaults/actions.cljs`, while effect execution and projection writes are implemented by `/hyperopen/src/hyperopen/vaults/effects.cljs` and `/hyperopen/src/hyperopen/api/projections.cljs`.

Currently, only `vaultDetails` and `webData2` fetches are wired for detail route loading. Missing per-tab datasets (fills/funding/order-history/ledger updates) are not fetched into vault-scoped state. This causes several tabs to render placeholder messages rather than functional tables.

## Plan of Work

Milestone 1 focuses on chart parity and interaction correctness. Add `:detail-chart-series` state under `:vaults-ui`, add a normalizer/action in `/hyperopen/src/hyperopen/vaults/actions.cljs`, register the action in runtime registry/composition/collaborators/contracts, and wire the view model to choose series based on user state with fallback to available data. Update the view so series chips are buttons that dispatch the new action.

Milestone 2 introduces vault-scoped activity history data. Extend `:vaults` defaults and projection functions to store per-vault `fills`, `funding-history`, `order-history`, and `ledger-updates`, each with loading/error/loaded timestamps. Add corresponding effect functions and adapter/registry wiring. Update `load-vault-detail` action to request these datasets alongside existing detail/webdata requests while preserving projection-first ordering.

Milestone 3 replaces placeholder tab rendering with concrete tables. In `detail_vm.cljs`, add robust row normalizers for each missing tab and compute counts from real rows. In `vault_detail_view.cljs`, add dedicated renderers and empty states for `TWAP`, `Funding History`, `Order History`, `Deposits and Withdrawals`, and `Depositors`, and improve balances fallback using perps margin summary data when spot balances are empty.

Milestone 4 validates and stabilizes. Update endpoint, action, effect, projection, VM, view, and runtime contract tests for new action/effect IDs and data shape changes. Run required gates and commit.

## Concrete Steps

From `/hyperopen`:

1. Edit planning artifact and implementation files listed above.
2. Run targeted tests for changed namespaces while iterating.
3. Run full required validation gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
4. Capture final `git status` and create a single commit.

## Validation and Acceptance

Acceptance criteria:

1. Chart series toggles on vault detail are clickable and switching between `Account Value` and `PNL` changes selected chart state.
2. Activity tabs `TWAP`, `Funding History`, `Order History`, `Deposits and Withdrawals`, and `Depositors` render table structures (or explicit tab-specific empty states), not generic placeholders.
3. Depositor tab is populated from normalized follower rows when available.
4. Required validation commands pass.

## Idempotence and Recovery

All changes are additive and can be re-run safely. If any fetch path fails, projections keep per-vault error state scoped to that dataset without mutating unrelated account state. Retry is achieved by reloading the route (`:actions/load-vault-route`) which re-issues requests.

## Artifacts and Notes

Reference parity audit artifact used to drive this plan:

- `/hyperopen/tmp/vault-tab-audit/2026-02-27T01-41-29-502Z-direct/summary.json`

## Interfaces and Dependencies

This plan extends the following stable interfaces:

- Vault action IDs in `/hyperopen/src/hyperopen/registry/runtime.cljs` and `/hyperopen/src/hyperopen/schema/contracts.cljs`.
- Vault effect IDs in `/hyperopen/src/hyperopen/registry/runtime.cljs`, `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`, and `/hyperopen/src/hyperopen/schema/contracts.cljs`.
- Vault state branches in `/hyperopen/src/hyperopen/state/app_defaults.cljs` and projection writers in `/hyperopen/src/hyperopen/api/projections.cljs`.

Implementation must preserve deterministic effect ordering for vault route interactions and keep side effects inside effect/adapters boundaries.

---

Revision note (2026-02-27 / Codex): Created initial living ExecPlan from the second parity pass request, then updated progress/outcomes after implementing chart, tab data, runtime wiring, and test coverage changes.
