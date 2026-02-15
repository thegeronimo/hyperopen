# Refactor Trading Chart Indicators (Phase 5: Parity Removal for Migrated Wave IDs)

This ExecPlan record captures completed work for a migration slice where domain-owned implementations replaced corresponding wave-2/wave-3 implementations after parity validation.

## Purpose / Big Picture

Remove duplicated migrated implementations from legacy wave namespaces once domain implementations are in place and parity is confirmed. This reduces coupling, prevents divergence bugs, and advances semantic ownership in domain namespaces.

## Progress

- [x] (2026-02-15 14:48Z) Removed migrated wave-2 indicator definitions and calculators for `:rate-of-change`, `:relative-strength-index`, and `:standard-deviation`.
- [x] (2026-02-15 14:48Z) Removed migrated wave-3 indicator definitions and calculators for `:correlation-coefficient`, `:standard-error`, `:standard-error-bands`, `:trend-strength-index`, and `:true-strength-index`.
- [x] (2026-02-15 14:49Z) Cleaned stale wave-2 `indicatorts` imports that became unused after migration removal.
- [x] (2026-02-15 14:54Z) Ran full required gates and confirmed parity (`npm run check`, `npm test`, `npm run test:websocket`).

## Surprises & Discoveries

- Observation: Domain-first dispatch plus definition de-duplication enables safe staged removals from wave registries without user-visible catalog regressions.
  Evidence: indicator tests and full suite remained green after removing the wave entries/functions.

## Decision Log

- Decision: Remove only IDs with implemented domain calculators and adapter styles in this slice.
  Rationale: keep migration risk bounded and test-backed.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

Legacy wave implementations for the migrated IDs were removed successfully after parity confirmation. The source of truth for those IDs is now domain semantic modules plus view adapter projection.

Validated commands:

- `npm run check`
- `npm test`
- `npm run test:websocket`

## Context and Orientation

Files changed:

- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators_wave2.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators_wave3.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/volatility.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators.cljs`

## Validation and Acceptance

Acceptance achieved:

- Removed migrated implementations from wave files.
- Domain calculators continue to satisfy indicator tests and full runtime checks.
- No regressions in required validation gates.

## Idempotence and Recovery

If any removed ID regresses in future, rollback is scoped to re-adding that ID/function mapping in the corresponding wave namespace while preserving domain-first dispatch.

## Interfaces and Dependencies

Public API remains unchanged:

- `hyperopen.views.trading-chart.utils.indicators/get-available-indicators`
- `hyperopen.views.trading-chart.utils.indicators/calculate-indicator`
- `hyperopen.views.trading-chart.utils.indicators/calculate-sma`

Plan revision note: 2026-02-15 14:54Z - Completed phase-5 record created after parity validation.
