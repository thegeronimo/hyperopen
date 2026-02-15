# Refactor Trading Chart Indicators (Phase 3: Domain Ownership + View Projection)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and is self-contained for contributors working only from the current tree.

## Purpose / Big Picture

After this phase, selected indicator calculation logic will be owned by domain namespaces instead of `views`, and view code will project raw domain series into styled chart series through a dedicated adapter. This improves Single Responsibility and Dependency Inversion: calculations remain pure domain behavior, while rendering metadata stays in the view layer.

User-visible indicator behavior should remain unchanged.

## Progress

- [x] (2026-02-15 14:32Z) Created this ExecPlan at `/hyperopen/docs/exec-plans/active/2026-02-15-indicator-refactor-phase-3.md`.
- [x] (2026-02-15 14:35Z) Added domain indicator result contract and semantic domain namespaces: `/hyperopen/src/hyperopen/domain/trading/indicators/result.cljs`, `/hyperopen/src/hyperopen/domain/trading/indicators/trend.cljs`, `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators.cljs`, `/hyperopen/src/hyperopen/domain/trading/indicators/volatility.cljs`.
- [x] (2026-02-15 14:35Z) Updated view adapter projection with `project-domain-indicator` in `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`.
- [x] (2026-02-15 14:35Z) Rewired `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators.cljs` to use domain calculators and preserve public API behavior.
- [x] (2026-02-15 14:35Z) Removed obsolete view-semantic calculator namespaces.
- [x] (2026-02-15 14:36Z) Passed required validation gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-15 14:36Z) Moved plan to completed with outcomes.

## Surprises & Discoveries

- Observation: Previous phase already introduced semantic files in `views`; those can be used as migration source and then removed.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators_trend.cljs`, `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators_oscillators.cljs`, `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators_volatility.cljs`.
- Observation: Existing indicator tests were broad enough to validate domain-ownership migration without adding new fixtures in this phase.
  Evidence: `npm test` passed with `769` tests and `0` failures after rewiring to domain calculators.

## Decision Log

- Decision: Keep the migration scope to the semantic subset introduced in phase 2 rather than all wave-2/wave-3 indicators.
  Rationale: preserves momentum and keeps risk bounded while materially advancing DDD ownership boundaries.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

Phase 3 completed the ownership boundary shift for the semantic subset: indicator calculations now live in domain namespaces and return raw series values, while style metadata remains in the view adapter. The view coordinator now projects domain outputs into chart-ready series and remains API-compatible.

Domain ownership now covers the migrated subset:

- Trend: `sma`, `alma`, `aroon`, `adx`
- Oscillators: `accelerator-oscillator`, `awesome-oscillator`, `balance-of-power`, `advance-decline`
- Volatility: `week-52-high-low`, `atr`, `bollinger-bands`

Required validation gates all passed:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Remaining migration work is primarily wave-2/wave-3 semantic extraction and adapter unification for their presentation metadata.

## Context and Orientation

Current coordinator file: `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators.cljs`

Current view adapter: `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`

Current shared math kernel: `/hyperopen/src/hyperopen/domain/trading/indicators/math.cljs`

Migration target domain path: `/hyperopen/src/hyperopen/domain/trading/indicators/`

## Plan of Work

Define a domain result contract for indicator outputs where each series carries only structural information (`id`, `series-type`, `values`) without visual styling. Implement trend/oscillator/volatility subset calculators in domain namespaces returning this contract.

Enhance view adapter to map domain result into chart interop shape using centralized style metadata maps.

Update coordinator to call domain calculators first for migrated subset, then continue existing fallback chain for non-migrated indicators.

Delete obsolete view-semantic calculator namespaces from phase 2.

## Concrete Steps

All commands run from `/Users//projects/hyperopen`.

1. Add domain indicator contract and semantic domain calculators.

    rg --files src/hyperopen/domain/trading/indicators

2. Rewire view adapter and coordinator.

    rg -n "get-available-indicators|calculate-indicator|indicator-calculators" src/hyperopen/views/trading_chart/utils/indicators.cljs

3. Remove obsolete view semantic files.

    rg --files src/hyperopen/views/trading_chart/utils | rg "indicators_(trend|oscillators|volatility)"

4. Run required validation.

    npm run check
    npm test
    npm run test:websocket

## Validation and Acceptance

Acceptance criteria:

- Migrated subset calculation logic is implemented in domain namespaces.
- View adapter owns style projection for migrated subset.
- Public indicator API remains unchanged and tests continue passing.
- Required validation gates pass.

## Idempotence and Recovery

Migration is additive and can be rolled back per-indicator family by restoring coordinator routing to previous view-semantic calculators.

## Artifacts and Notes

This phase migrates ownership boundaries only for the subset introduced in phase 2.

## Interfaces and Dependencies

Public API compatibility required:

- `hyperopen.views.trading-chart.utils.indicators/get-available-indicators`
- `hyperopen.views.trading-chart.utils.indicators/calculate-indicator`
- `hyperopen.views.trading-chart.utils.indicators/calculate-sma`

Plan revision note: 2026-02-15 14:32Z - Initial phase-3 plan created before implementation.
Plan revision note: 2026-02-15 14:36Z - Updated with completed migration details, validation outcomes, and retrospective.
