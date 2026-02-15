# Refactor Trading Chart Indicators (Phase 4: Wave2/Wave3 Metadata Migration + Extraction Start)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` are updated with final state for this phase.

This document follows `/hyperopen/.agents/PLANS.md` and is self-contained for contributors working only from the current tree.

## Purpose / Big Picture

This phase starts migrating wave-2/wave-3 indicators into semantic domain ownership while moving their presentation metadata into the dedicated view adapter map. The user-visible behavior remains stable, but more indicator IDs now resolve via domain calculators + view projection rather than mixed view utility calculators.

## Progress

- [x] (2026-02-15 14:33Z) Expanded domain oscillators metadata and calculator coverage to start absorbing wave-2/wave-3 IDs.
- [x] (2026-02-15 14:34Z) Added domain volatility calculators for `:standard-deviation`, `:standard-error`, and `:standard-error-bands`.
- [x] (2026-02-15 14:34Z) Migrated style metadata for newly migrated IDs into `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`.
- [x] (2026-02-15 14:34Z) Updated `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators.cljs` to dedupe indicator definitions by `:id` to avoid duplicate dropdown entries during migration overlap.
- [x] (2026-02-15 14:36Z) Passed required validation gates: `npm run check`, `npm test`, and `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Definition-level migration can be advanced ahead of full calculator migration safely when coordinator dispatch order is domain-first with wave fallback.
  Evidence: domain-owned definitions for overlapping IDs coexist with wave files; runtime remains stable because unresolved IDs still fall through to wave calculators.
- Observation: Deduping `get-available-indicators` by `:id` is necessary once IDs exist in both domain and wave catalogs.
  Evidence: without dedupe, overlapping IDs would render duplicate dropdown rows.

## Decision Log

- Decision: Use an incremental overlap strategy (domain-first, wave fallback) instead of removing wave-2/wave-3 entries immediately.
  Rationale: minimizes regression risk while allowing steady migration of IDs and presentation metadata.
  Date/Author: 2026-02-15 / Codex
- Decision: Migrate adapter metadata for extracted IDs immediately so styling is centralized before full wave extraction completes.
  Rationale: enforces separation of concerns early and keeps new domain outputs view-agnostic.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

Phase 4 delivered the requested next slice:

- Started semantic extraction of wave-2/wave-3 calculators into domain namespaces.
- Migrated presentation metadata for the extracted IDs into the view adapter map.
- Added stable deduplication in indicator catalog assembly.

Required validation gates all passed:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Remaining work: continue migrating the rest of wave-2/wave-3 calculators into semantic domain modules and retire equivalent wave utility implementations once parity is validated.

## Context and Orientation

Key files changed in this phase:

- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/volatility.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators.cljs`

## Plan of Work

This phase used a domain-first migration strategy:

1. Extend domain semantic namespaces with initial wave-2/wave-3 IDs and calculators.
2. Add style entries for those IDs in the view adapter.
3. Keep coordinator dispatch order domain-first and wave fallback.
4. Prevent catalog duplicates by deduping on `:id` in `get-available-indicators`.

## Concrete Steps

All commands run from `/Users//projects/hyperopen`.

1. Validate code compiles and tests pass.

    npm test

2. Run required quality and websocket gates.

    npm run check
    npm run test:websocket

## Validation and Acceptance

Acceptance criteria met:

- Wave-2/wave-3 extraction is started in domain namespaces.
- Adapter map includes migrated style metadata for newly domain-owned IDs.
- Public behavior remains stable.
- Required validation gates pass.

## Idempotence and Recovery

Migration is non-destructive and overlap-safe. If any newly migrated ID regresses, remove that ID from domain calculator map to fall back to existing wave calculators.

## Artifacts and Notes

No public API shape changes were introduced in this phase.

## Interfaces and Dependencies

Public API compatibility maintained:

- `hyperopen.views.trading-chart.utils.indicators/get-available-indicators`
- `hyperopen.views.trading-chart.utils.indicators/calculate-indicator`
- `hyperopen.views.trading-chart.utils.indicators/calculate-sma`

Plan revision note: 2026-02-15 14:36Z - Completed phase-4 record added after implementation and validation.
