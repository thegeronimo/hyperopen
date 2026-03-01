# Active Asset Funding Tooltip Position-Aware Projections

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and must be maintained in accordance with that file.

## Purpose / Big Picture

Today, hovering the `Funding / Countdown` value in the active asset strip only shows one annualized percentage. After this change, the tooltip will explain the current position impact: position side and size, current position value, and projected funding payments for the current interval, next 24h, and annualized horizon. This lets users immediately see expected nominal funding cashflow, not only rate percentages.

You can verify behavior by opening a perp position, hovering the funding rate in the top strip, and confirming the tooltip shows both `Rate` and `Payment` projections that match the current position direction and value.

## Progress

- [x] (2026-03-01 18:41Z) Audited current funding tooltip path in `/hyperopen/src/hyperopen/views/active_asset_view.cljs`, position source selectors in `/hyperopen/src/hyperopen/state/trading.cljs`, and existing tests.
- [x] (2026-03-01 18:44Z) Implemented position-aware funding tooltip model and rich tooltip UI in `/hyperopen/src/hyperopen/views/active_asset_view.cljs`.
- [x] (2026-03-01 18:44Z) Added regression tests in `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs` for projection content and long/short payment sign behavior.
- [x] (2026-03-01 18:45Z) Ran required validation gates: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-03-01 18:45Z) Updated this plan with implementation outcomes, discoveries, and validation evidence.

## Surprises & Discoveries

- Observation: `fundingRate` in active asset context is already in percentage units (for example `0.0056` means `0.0056%`), and annualization uses direct multiplication rather than dividing by 100 first.
  Evidence: Existing `active_asset_view.cljs` renders `(fmt/format-percentage funding-rate 4)` and calls `(fmt/annualized-funding-rate funding-rate)`.

- Observation: Active position retrieval already exists in canonical trading selectors and can be reused in view code without introducing new websocket coupling.
  Evidence: `/hyperopen/src/hyperopen/state/trading.cljs` exposes `position-for-active-asset`.

- Observation: Local environment initially lacked `node_modules`, which causes `npm run check` to fail before compile/test stages.
  Evidence: Initial `check` run failed with missing package error for `@noble/secp256k1`; resolved by running `npm install` and re-running gates.

## Decision Log

- Decision: Keep projection math local to `/hyperopen/src/hyperopen/views/active_asset_view.cljs` as pure helpers for this tooltip, rather than introducing a new shared domain module.
  Rationale: The change is a view-only display enhancement with no state transition or side effects, and colocating helpers keeps scope tight.
  Date/Author: 2026-03-01 / Codex

- Decision: Derive expected nominal payment from position direction and absolute position value using `payment = -direction * value * (rate / 100)`.
  Rationale: This matches funding semantics and the requested UX: long positions with positive funding show expected negative payment.
  Date/Author: 2026-03-01 / Codex

- Decision: Always show projection rates in the tooltip, but show position-dependent payment estimates only when a non-flat position value is available.
  Rationale: Users can still inspect rate projections while flat, and nominal payment remains accurate and non-misleading when no position is open.
  Date/Author: 2026-03-01 / Codex

## Outcomes & Retrospective

Implemented behavior:

- Funding hover now opens a structured tooltip panel with:
  - `Position` section (`Size`, `Value`).
  - `Projections` section with `Rate` and `Payment` columns.
  - Rows for `Current in mm:ss`, `Next 24h *`, and `APY *`.
- Tooltip uses canonical active-position lookup via `hyperopen.state.trading/position-for-active-asset`.
- Payment sign and amount are derived from position direction and current position value:
  - Long + positive funding rate -> negative payment.
  - Short + positive funding rate -> positive payment.

Validation outcomes:

- `npm run check`: pass
- `npm test`: pass (1669 tests, 8697 assertions)
- `npm run test:websocket`: pass (289 tests, 1639 assertions)

## Context and Orientation

The current hover UI is implemented in `/hyperopen/src/hyperopen/views/active_asset_view.cljs` via a small `tooltip` helper that only shows annualized funding text. The active row already has `fundingRate`, mark/oracle, and open-interest data from `ctx-data`. The same row receives full app state, so it can also derive active position data.

Canonical active-position lookup exists in `/hyperopen/src/hyperopen/state/trading.cljs` (`position-for-active-asset`). Position payloads include fields such as `:szi` (signed size) and `:positionValue` (notional value in quote currency). This plan uses those fields to compute position-aware funding projections.

Tests for this view live in `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs` and already validate row rendering details with `collect-strings` and class traversal helpers.

## Plan of Work

Update `/hyperopen/src/hyperopen/views/active_asset_view.cljs` to introduce pure helper functions that:

1. Normalize active position size/value and direction (`:long`, `:short`, `:flat`) from `trading-state/position-for-active-asset`.
2. Compute funding projection rows for current interval, next 24h, and annualized rate using the live `fundingRate`.
3. Compute nominal expected payment by applying direction sign and position value.
4. Build a richer tooltip panel with structured sections: `Position` and `Projections`, mirroring the screenshot intent.

Keep the visible funding cell unchanged except for replacing the old single-line tooltip body with the richer content.

Extend `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs` with deterministic assertions that confirm:

- Tooltip includes position summary text (`Long`/`Short`, size, value).
- Tooltip includes projection labels (`Current in`, `Next 24h`, `APY`) and rate/payment values.
- Payment sign reflects position direction and rate sign.

## Concrete Steps

From repository root `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/views/active_asset_view.cljs` to add projection helpers and rich tooltip rendering.
2. Edit `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs` to add focused regression tests.
3. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
4. Update this plan sections (`Progress`, `Decision Log`, `Outcomes & Retrospective`, revision note) with completed work and command outcomes.

## Validation and Acceptance

Acceptance is satisfied when:

1. Hovering funding for a perp with an open position shows:
   - Position side and size.
   - Current position value.
   - Projection rows for current interval, next 24h, and annualized horizon.
2. Projection rows display both `Rate` and `Payment`.
3. Payment sign matches expected funding direction:
   - Long + positive funding rate => negative payment.
   - Short + positive funding rate => positive payment.
4. Spot markets continue to render `—` and do not attempt funding projections.
5. Required repository validation gates pass.

## Idempotence and Recovery

All edits are additive and localized to one view module and its tests. Re-running commands is safe. If tooltip styling causes layout regressions, revert only tooltip body structure while preserving projection helper functions and tests so behavior can be retained with simpler presentation.

## Artifacts and Notes

Implementation files:

- `/hyperopen/src/hyperopen/views/active_asset_view.cljs`
- `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs`

Validation command artifacts:

- `npm run check`
  - First run failed due missing npm dependencies.
  - After `npm install`, command passed.
- `npm test`
  - Passed with `1669` tests and `8697` assertions.
- `npm run test:websocket`
  - Passed with `289` tests and `1639` assertions.

## Interfaces and Dependencies

No new external dependencies.

Internal interface updates expected:

- `/hyperopen/src/hyperopen/views/active_asset_view.cljs` will import `hyperopen.state.trading` for canonical active-position retrieval.
- New private helper functions in that file will produce deterministic funding projection rows and tooltip models from existing inputs (`fundingRate`, active position, countdown text).

Plan revision note: 2026-03-01 18:41Z - Created initial ExecPlan before implementation, with scope, acceptance criteria, and required validation gates.
Plan revision note: 2026-03-01 18:45Z - Marked implementation complete, recorded dependency-install discovery, and added validation results.
