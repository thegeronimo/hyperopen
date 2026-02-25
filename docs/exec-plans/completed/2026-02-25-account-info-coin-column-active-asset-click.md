# Account Info Coin Column Click Selects Active Asset

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, clicking any value in a `Coin` column inside Account Info tabs selects that coin as the active trading asset. This applies to Balances, Positions, Open Orders, Order History, Trade History, and Funding History. A user can verify the behavior by opening each tab and clicking a coin cell; the active asset in the trade header should switch immediately to the clicked coin and downstream subscriptions should follow the existing `:actions/select-asset` flow.

## Progress

- [x] (2026-02-25 15:41Z) Audited coin-column render paths and existing asset-selection pipeline (`:actions/select-asset`) across account-info tabs.
- [x] (2026-02-25 15:42Z) Added shared coin-click interaction helper in `shared.cljs` and canonical `:selection-coin` mapping in balance row projections.
- [x] (2026-02-25 15:43Z) Wired clickable coin cells into Balances, Positions, Open Orders, Order History, Trade History, and Funding History tab row renderers.
- [x] (2026-02-25 15:44Z) Added tab-level tests asserting `:actions/select-asset` click payloads from each coin column.
- [x] (2026-02-25 15:45Z) Ran required validation gates (`npm run check`, `npm test`, `npm run test:websocket`) with zero failures.

## Surprises & Discoveries

- Observation: Balances render display labels such as `USDC (Spot)` and `USDC (Perps)` that are not always canonical asset identifiers.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/projections/balances.cljs` emits formatted `:coin` labels, so click selection needs a canonical coin field.

- Observation: `order-history-tab-content` wraps the table node in an additional container, so tests that rely on tab-table helper selectors need to target `order-history-table` directly for row-level assertions.
  Evidence: Initial assertion matched the header sort button action until the test switched to `@#'view/order-history-table`.

## Decision Log

- Decision: Reuse the existing `:actions/select-asset` action path for coin-column clicks instead of introducing a new action.
  Rationale: The existing path already enforces UI-first projection ordering, unsubscribe/subscribe sequencing, and side-effect invariants required by `/hyperopen/docs/FRONTEND.md`.
  Date/Author: 2026-02-25 / Codex

- Decision: Add `:selection-coin` to balance projection rows and use that for click dispatch in balances.
  Rationale: Balance display labels intentionally diverge from canonical coin ids for UX (`USDC (Spot)`/`USDC (Perps)`), and click behavior must still target a valid asset identity.
  Date/Author: 2026-02-25 / Codex

- Decision: Centralize coin-cell click rendering in `shared/coin-select-control` and reuse it in all coin-column tab modules.
  Rationale: This keeps interaction semantics and keyboard focus behavior consistent across all tabs with minimal duplication.
  Date/Author: 2026-02-25 / Codex

## Outcomes & Retrospective

The account-info coin column now consistently acts as an active-asset selector across all tabs that expose coin values. The implementation preserved existing visual parity (base label + prefix chip, side-aware color treatment, existing table layout) while adding keyboard-accessible click targets and reusing the canonical `:actions/select-asset` interaction path. Validation gates passed in full. No functional gaps remain for the requested scope.

## Context and Orientation

Account Info tabs are rendered from `/hyperopen/src/hyperopen/views/account_info_view.cljs`, with tab modules under `/hyperopen/src/hyperopen/views/account_info/tabs/`. Shared formatting helpers live in `/hyperopen/src/hyperopen/views/account_info/shared.cljs`. Asset switching is centralized in `/hyperopen/src/hyperopen/asset_selector/actions.cljs` (`select-asset`) and should remain the single owner of active-asset switching behavior.

The `Coin` column appears in the following tab modules:
- `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs`

## Plan of Work

Implement a shared clickable-coin renderer helper in the account-info shared module that emits a keyboard-accessible `button` and dispatches `:actions/select-asset` with a validated coin payload. Then replace static coin text nodes in each tab with this helper while preserving visual styling and existing chip formatting. For balances, add a canonical selection field (for example `:selection-coin`) in projection rows so display-only labels still dispatch canonical asset ids. Finally, extend tab tests to assert click dispatch payloads and run required validation gates.

## Concrete Steps

From `/hyperopen`:

1. Create shared click helper and coin-canonical mapping support:
   - `src/hyperopen/views/account_info/shared.cljs`
   - `src/hyperopen/views/account_info/projections/balances.cljs`
2. Wire tab row coin cells:
   - `src/hyperopen/views/account_info/tabs/balances.cljs`
   - `src/hyperopen/views/account_info/tabs/positions.cljs`
   - `src/hyperopen/views/account_info/tabs/open_orders.cljs`
   - `src/hyperopen/views/account_info/tabs/order_history.cljs`
   - `src/hyperopen/views/account_info/tabs/trade_history.cljs`
   - `src/hyperopen/views/account_info/tabs/funding_history.cljs`
3. Update tests:
   - `test/hyperopen/views/account_info/tabs/balances_test.cljs`
   - `test/hyperopen/views/account_info/tabs/positions_test.cljs`
   - `test/hyperopen/views/account_info/tabs/open_orders_test.cljs`
   - `test/hyperopen/views/account_info/tabs/order_history_test.cljs`
   - `test/hyperopen/views/account_info/tabs/trade_history_test.cljs`
   - `test/hyperopen/views/account_info/tabs/funding_history_test.cljs`
4. Run validation gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

Executed validation output summary:

- `npm run check` passed (linters + app/test compile).
- `npm test` passed (1342 tests, 6356 assertions, 0 failures).
- `npm run test:websocket` passed (147 tests, 643 assertions, 0 failures).

## Validation and Acceptance

Acceptance criteria:

1. Clicking any coin cell in any account-info tab that renders a `Coin` column dispatches `:actions/select-asset` with the intended coin.
2. The behavior uses existing `select-asset` semantics (immediate UI updates first, then subscriptions/effects).
3. Coin display parity (base symbol + namespace chip, side colors, existing typography) remains unchanged.
4. All required validation gates pass.

## Idempotence and Recovery

Changes are additive and source-only. Re-running edits is safe. If a tab-specific regression appears, isolate rollback to the modified tab file and keep shared helper plus tested tabs intact.

## Artifacts and Notes

Implementation files:

- `src/hyperopen/views/account_info/shared.cljs`
- `src/hyperopen/views/account_info/projections/balances.cljs`
- `src/hyperopen/views/account_info/tabs/balances.cljs`
- `src/hyperopen/views/account_info/tabs/positions.cljs`
- `src/hyperopen/views/account_info/tabs/open_orders.cljs`
- `src/hyperopen/views/account_info/tabs/order_history.cljs`
- `src/hyperopen/views/account_info/tabs/trade_history.cljs`
- `src/hyperopen/views/account_info/tabs/funding_history.cljs`
- `test/hyperopen/views/account_info/tabs/balances_test.cljs`
- `test/hyperopen/views/account_info/tabs/positions_test.cljs`
- `test/hyperopen/views/account_info/tabs/open_orders_test.cljs`
- `test/hyperopen/views/account_info/tabs/order_history_test.cljs`
- `test/hyperopen/views/account_info/tabs/trade_history_test.cljs`
- `test/hyperopen/views/account_info/tabs/funding_history_test.cljs`

## Interfaces and Dependencies

No new external dependencies are required.

Internal interface addition:

- Shared coin interaction helper in `hyperopen.views.account-info.shared` that emits a click action vector `[:actions/select-asset <coin>]` when the coin is selectable.

Plan revision note: 2026-02-25 15:41Z - Initial plan created after auditing coin-column surfaces and active-asset selection flow.
Plan revision note: 2026-02-25 15:45Z - Marked implementation complete, logged final decisions/discoveries, and recorded required validation evidence.
