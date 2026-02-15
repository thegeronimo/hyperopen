# Refactor Trading Chart Indicators (Phase 6: Wave3 Volatility Family Migration)

This ExecPlan record is maintained in accordance with `/hyperopen/.agents/PLANS.md` and captures a completed migration slice.

## Purpose / Big Picture

Move the wave3 volatility indicator family to semantic domain ownership so calculation logic lives in `hyperopen.domain.trading.indicators.volatility`, while view presentation metadata remains in the adapter. After this change, these IDs no longer rely on `indicators_wave3.cljs`, reducing duplication and coupling.

## Progress

- [x] (2026-02-15 14:56Z) Confirmed domain parity for `:volatility-close-to-close`, `:volatility-index`, `:volatility-ohlc`, and `:volatility-zero-trend-close-to-close` in `src/hyperopen/domain/trading/indicators/volatility.cljs`.
- [x] (2026-02-15 14:57Z) Added view adapter style metadata for the migrated volatility series in `src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`.
- [x] (2026-02-15 14:57Z) Removed wave3 definitions, calculator functions, and dispatch entries for migrated volatility IDs from `src/hyperopen/views/trading_chart/utils/indicators_wave3.cljs`.
- [x] (2026-02-15 14:58Z) Ran required validation gates (`npm run check`, `npm test`, `npm run test:websocket`) with zero failures.

## Surprises & Discoveries

- Observation: This family was already partially migrated to domain code but not yet removed from wave3; finishing removal eliminated duplicate ownership cleanly.
  Evidence: `npm run check`, `npm test`, and `npm run test:websocket` all passed after wave3 removals.

## Decision Log

- Decision: Complete the volatility quartet as one migration family before starting another family.
  Rationale: The implementations and metadata were already aligned in domain code, making this the lowest-risk parity removal slice.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

The volatility quartet is now fully domain-owned with view metadata in the adapter map. `indicators_wave3.cljs` no longer carries duplicate definitions/implementations for these IDs. Required validation confirms no behavioral regression.

## Context and Orientation

Files changed in this slice:

- `/hyperopen/src/hyperopen/domain/trading/indicators/volatility.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators_wave3.cljs`

Coordinator behavior remains domain-first in `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators.cljs`, so removed wave3 IDs resolve through domain calculators.

## Validation and Acceptance

Validation commands run from `/hyperopen`:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Acceptance criteria met:

- Migrated volatility IDs are absent from wave3 definitions/calculators.
- Domain volatility calculators provide those IDs.
- View adapter supplies series names/colors for those IDs.
- Required validation gates pass.

## Idempotence and Recovery

This slice is additive-safe and repeatable: re-running validation is idempotent. Recovery path is scoped to re-adding the removed wave3 entries if regression is detected, without changing public indicator APIs.

## Interfaces and Dependencies

Public entry points remain unchanged:

- `hyperopen.views.trading-chart.utils.indicators/get-available-indicators`
- `hyperopen.views.trading-chart.utils.indicators/calculate-indicator`

Plan revision note: 2026-02-15 14:58Z - Completed phase-6 record created after parity validation and wave3 volatility removal.
