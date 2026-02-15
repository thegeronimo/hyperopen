# Indicator SOLID/DDD Alignment Roadmap

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The indicator system still has domain math in view namespaces, fallback dispatch coupling, and mixed concerns between calculation and presentation. After this plan is complete, indicator calculation will live in semantic domain namespaces, the view layer will only project style metadata, and adding a new indicator family will not require editing the chart coordinator. The result is safer changes, lower regression risk, and reusable indicator logic for non-UI contexts.

## Progress

- [x] (2026-02-15 15:11Z) Created active ExecPlan and captured remaining SOLID/DDD gaps and migration milestones.
- [x] (2026-02-15 15:16Z) Milestone 1 completed: introduced domain-level registry orchestration and migrated remaining base calculators from `indicators.cljs` into domain namespaces.
- [x] (2026-02-15 15:18Z) Validated Milestone 1 with required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-15 15:25Z) Milestone 2 batch A completed: migrated wave3 trend-overlay family (`:guppy-multiple-moving-average`, `:mcginley-dynamic`, `:moving-average-adaptive`, `:moving-average-hamming`, `:williams-alligator`) to domain trend and removed wave3 duplicates.
- [x] (2026-02-15 15:25Z) Milestone 2 batch B completed: migrated wave3 structure/pattern family (`:pivot-points-standard`, `:rank-correlation-index`, `:williams-fractal`, `:zig-zag`) to a new domain structure namespace and removed wave3 duplicates.
- [x] (2026-02-15 15:25Z) Validated Milestone 2 batches A+B with required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-15 15:29Z) Milestone 2 batch C completed: migrated remaining wave3 oscillator/volume family (`:chaikin-volatility`, `:chande-kroll-stop`, `:chop-zone`, `:connors-rsi`, `:correlation-log`, `:klinger-oscillator`, `:know-sure-thing`, `:volume`) to domain modules.
- [x] (2026-02-15 15:29Z) Milestone 2 completed: removed wave3 fallback path from coordinator and deleted `indicators_wave3.cljs`.
- [x] (2026-02-15 15:29Z) Validated Milestone 2 batch C and wave3 retirement with required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-15 15:32Z) Milestone 3 batch A completed: migrated wave2 starter family (`:median-price`, `:typical-price`, `:momentum`) into domain price/oscillator modules and removed wave2 duplicates.
- [x] (2026-02-15 15:32Z) Validated Milestone 3 batch A with required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-15 15:48Z) Milestone 3 batch B completed: migrated wave2 flow family (`:net-volume`, `:on-balance-volume`, `:price-volume-trend`, `:volume-oscillator`) into domain flow and removed wave2 duplicates.
- [x] (2026-02-15 15:48Z) Validated Milestone 3 batch B with required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-15 15:54Z) Milestone 3 batch C completed: migrated wave2 moving-average variants (`:double-ema`, `:hull-moving-average`, `:moving-average-double`, `:moving-average-exponential`, `:moving-average-triple`, `:moving-average-weighted`, `:smoothed-moving-average`, `:triple-ema`) into domain trend and removed wave2 duplicates.
- [x] (2026-02-15 15:54Z) Validated Milestone 3 batch C with required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-15 15:58Z) Milestone 3 batch D completed: migrated wave2 crossover family (`:ema-cross`, `:ma-cross`, `:ma-with-ema-cross`) into domain trend and removed wave2 duplicates.
- [x] (2026-02-15 15:58Z) Validated Milestone 3 batch D with required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-15 16:18Z) Milestone 3 batch E completed: migrated wave2 channel/volatility family (`:donchian-channels`, `:price-channel`, `:keltner-channels`, `:historical-volatility`, `:moving-average-channel`, `:bollinger-bands-percent-b`, `:bollinger-bands-width`) into domain volatility and removed wave2 duplicates.
- [x] (2026-02-15 16:18Z) Milestone 3 batch F completed: migrated wave2 oscillator family (`:stochastic`, `:stochastic-rsi`, `:williams-r`, `:chande-momentum-oscillator`, `:detrended-price-oscillator`, `:price-oscillator`, `:trix`, plus `:choppiness-index`, `:commodity-channel-index`, `:macd`, `:mass-index`) into domain oscillators and removed wave2 duplicates.
- [x] (2026-02-15 16:18Z) Milestone 3 batch G completed: migrated wave2 regression family (`:least-squares-moving-average`, `:linear-regression-curve`, `:linear-regression-slope`) into domain trend and removed wave2 duplicates.
- [x] (2026-02-15 16:18Z) Milestone 3 completed: migrated all remaining wave2 families, removed coordinator wave2 fallback path, and deleted `indicators_wave2.cljs`.
- [x] (2026-02-15 16:18Z) Validated Milestone 3 batches E/F/G and wave2 retirement with required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-15 16:29Z) Milestone 4 batch A completed: added explicit domain indicator input/result contracts and applied fail-closed enforcement at all domain family entrypoints.
- [x] (2026-02-15 16:29Z) Milestone 4 batch B completed: added focused heavy-indicator determinism/performance-smoke coverage and invalid-input fail-closed checks.
- [x] (2026-02-15 16:29Z) Validated Milestone 4 batches A+B with required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-15 17:08Z) Milestone 4 batch C completed: introduced a dedicated `math-adapter` boundary for all `indicatorts` calls and rewired domain calculators to depend only on adapter functions.
- [x] (2026-02-15 17:08Z) Added adapter-focused parity tests covering series-returning and map-returning adapter functions.
- [x] (2026-02-15 17:08Z) Validated Milestone 4 batch C with required gates: `npm run check`, `npm test`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: `src/hyperopen/views/trading_chart/utils/indicators.cljs` still contains many legacy private calculator functions that are no longer dispatched by `calculate-indicator`.
  Evidence: current `indicator-calculators` map only includes three IDs, while many private calculator fns remain in the file.
- Observation: Replacing the coordinator with a domain-registry path does not change behavior for existing tests when wave fallback order is preserved.
  Evidence: full required validation passed with 0 failures after the coordinator rewrite.
- Observation: Preserving wave3 parity for Williams Alligator required aligned RMA seeding semantics for shifted median series.
  Evidence: trend domain migration used `imath/rma-values` with `:aligned` for alligator lines and validation remained green.
- Observation: Splitting structure/pattern indicators into a dedicated domain namespace reduced duplicate ownership in oscillators without changing observable test behavior.
  Evidence: removed moved IDs from oscillator definitions, added structure registry routing, and full suites remained green.
- Observation: The wave3 namespace could be retired cleanly once all remaining IDs were domain-owned and adapter metadata was filled in.
  Evidence: coordinator no longer references wave3 and all required suites passed after deleting `indicators_wave3.cljs`.
- Observation: A low-risk wave2 starter batch can be extracted using pure formulas (no dependency on `indicatorts`) while preserving behavior.
  Evidence: moved `:median-price`, `:typical-price`, and `:momentum` to domain code and full required suites remained green.
- Observation: `indicatorts`-backed wave2 flow calculators can move to domain cleanly when style metadata remains in the adapter map.
  Evidence: migrated `:on-balance-volume`, `:price-volume-trend`, and `:volume-oscillator` to domain flow, removed wave2 handlers, and required suites remained green.
- Observation: Moving-average overlays from wave2 can be semantically consolidated into the trend namespace without changing chart output contracts.
  Evidence: migrated eight MA-variant IDs into domain trend, updated adapter style mappings, removed wave2 calculators, and required suites remained green.
- Observation: Crossover indicators require aligned SMA windows to preserve wave2 output parity.
  Evidence: `:ma-cross` and `:ma-with-ema-cross` domain implementations use aligned SMA windows, and required suites remained green after migration.
- Observation: Full wave2 retirement was achievable without changing public coordinator APIs once all residual IDs were redistributed into existing semantic domain modules.
  Evidence: `indicators.cljs` now orchestrates only domain registry + adapter projection, `indicators_wave2.cljs` was deleted, and full required suites remained green.
- Observation: Strict result-length contracts are not valid for all overlay indicators (for example Ichimoku cloud series can be shorter/shifted while still structurally valid).
  Evidence: Initial strict-length check caused `:ichimoku-cloud` to fail-closed; relaxing to shape contracts restored parity while retaining boundary validation.
- Observation: Adapter parity must treat `NaN` warmup slots as equivalent values.
  Evidence: direct `=` comparison failed for parity on `:commodity-channel-index` because `NaN` does not equal itself; parity tests now canonicalize `NaN`.

## Decision Log

- Decision: Start this phase by extracting the last three coordinator-owned calculators (`:accumulation-distribution`, `:accumulative-swing-index`, `:average-price`) into domain namespaces and simplify the coordinator to orchestration-only.
  Rationale: This immediately removes active domain logic from the main view coordinator and creates a clean pattern for subsequent family migrations.
  Date/Author: 2026-02-15 / Codex
- Decision: Introduce a domain registry orchestration namespace before full polymorphic dispatch conversion.
  Rationale: This creates a single domain extension point now, while deferring a broader defmulti migration to later milestones to keep this slice low-risk.
  Date/Author: 2026-02-15 / Codex
- Decision: Migrate the wave3 trend-overlay family into `domain.trading.indicators.trend` as the first Milestone 2 batch.
  Rationale: This family is cohesive, high-visibility, and mostly independent from marker-heavy structure indicators, making it a low-risk semantic extraction step.
  Date/Author: 2026-02-15 / Codex
- Decision: Introduce `domain.trading.indicators.structure` for pivot/rank/zigzag/fractal instead of expanding oscillators further.
  Rationale: These indicators are geometric/market-structure concepts, not momentum oscillators; semantic partitioning improves bounded-context clarity.
  Date/Author: 2026-02-15 / Codex
- Decision: Keep migrated oscillators in `domain.trading.indicators.oscillators` for this phase (rather than splitting a second new namespace) while retiring wave3 completely.
  Rationale: This minimizes churn while still eliminating wave3 fallback coupling; finer semantic subdivision can continue in Milestone 3+ with lower risk.
  Date/Author: 2026-02-15 / Codex
- Decision: Start wave2 extraction with formula-based indicators before `indicatorts`-wrapped groups.
  Rationale: This establishes safe migration throughput and test confidence prior to adapting additional third-party-backed calculators.
  Date/Author: 2026-02-15 / Codex
- Decision: Take wave2 flow-family indicators as the next migration batch after the starter slice.
  Rationale: The family is cohesive and already semantically aligned with `domain.trading.indicators.flow`, making it a low-risk extension of existing domain ownership.
  Date/Author: 2026-02-15 / Codex
- Decision: Follow flow-family migration with wave2 moving-average variants in `domain.trading.indicators.trend`.
  Rationale: These overlays are conceptually trend indicators and share math helpers already present in trend, enabling coherent semantic extraction with contained risk.
  Date/Author: 2026-02-15 / Codex
- Decision: Migrate the crossover family as a separate trend batch after moving-average variants.
  Rationale: Crossovers reuse existing trend primitives (EMA/SMA) but required explicit alignment semantics, so isolating them reduced regression scope.
  Date/Author: 2026-02-15 / Codex
- Decision: Complete wave2 retirement in the same slice by migrating all remaining IDs to semantic domain namespaces instead of creating an intermediate legacy namespace.
  Rationale: This removes the last procedural fallback and closes the bounded-context leak while keeping extension points centralized in domain registry code.
  Date/Author: 2026-02-15 / Codex
- Decision: Enforce indicator output contracts at domain family boundaries using structural validation (type/pane/series shape) instead of strict value-vector length equality.
  Rationale: Some indicators intentionally use shifted or reduced output windows, so shape contracts preserve correctness without rejecting valid domain results.
  Date/Author: 2026-02-15 / Codex
- Decision: Isolate all `indicatorts` integration in a single `math-adapter` namespace and keep domain families dependent on adapter functions only.
  Rationale: This enforces a stable Anti-Corruption seam, reduces coupling to third-party details, and enables adapter-level parity testing.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

Milestone 1 achieved the immediate separation-of-concerns target. Milestone 2 fully retired wave3 from runtime wiring. Milestone 3 fully retired wave2 from runtime wiring. Milestone 4 completed boundary hardening with explicit contracts, heavy-indicator parity/performance coverage, and a dedicated `indicatorts` adapter seam with parity tests.

## Context and Orientation

Indicator code is currently split across these paths:

- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators.cljs` is the chart-facing coordinator and now performs orchestration/projection only.
- `/hyperopen/src/hyperopen/domain/trading/indicators/*.cljs` holds the new semantic domain calculators (trend, oscillators, volatility, shared math/result contracts).
- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs` is the style projection layer and should be the only source of series names/colors/line styles.

For this plan, “domain calculator” means a pure-ish function that returns indicator type, pane, and raw series values via `hyperopen.domain.trading.indicators.result`; it does not construct chart point objects or hardcode display styling.

## Plan of Work

Milestone 1 removes residual domain logic from the coordinator and introduces a single domain registry entry point. Add a new domain registry namespace that aggregates indicator definitions and dispatches to family calculators. Migrate coordinator-owned calculators into dedicated domain namespaces and replace direct view-layer calculations with adapter projection of domain results.

Milestone 2 continues wave3 migration by semantic families. For each family, port calculators and definitions to domain modules, add view adapter metadata, remove migrated wave3 implementations, and re-run parity gates before proceeding.

Milestone 3 repeats the same pattern for wave2 families. The order will prioritize low-coupling, high-reuse indicators first, followed by heavier or externally-backed indicators.

Milestone 4 finalizes architecture boundaries. Isolate third-party math adapters behind domain math interfaces, add explicit parameter/result contract checks, and add parity/performance tests for heavy algorithms now that wave fallbacks are retired.

## Concrete Steps

From `/hyperopen`:

1. Implement Milestone 1 files and coordinator simplification.
2. Run validation gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
3. Continue Milestone 2 by extracting the next wave3 semantic family (structure/pattern or remaining oscillators), then re-run the same validation gates.
4. Record completed milestone details and move this plan to `/hyperopen/docs/exec-plans/completed/` when the full roadmap is done.

Expected validation transcript shape:

  - check: all lint/doc checks pass; `shadow-cljs compile app` and `shadow-cljs compile test` succeed with 0 warnings.
  - test: all test namespaces run; 0 failures, 0 errors.
  - websocket test: websocket suite runs; 0 failures, 0 errors.

## Validation and Acceptance

Milestone 1 acceptance:

- `indicators.cljs` is orchestration-only (domain registry + adapter projection + wave fallback).
- `:accumulation-distribution`, `:accumulative-swing-index`, and `:average-price` are calculated in domain namespaces, not view coordinator code.
- Presentation metadata for migrated series is owned by `indicator_view_adapter.cljs`.
- Required validation gates pass.

Roadmap acceptance:

- All indicator calculations are domain-owned.
- View namespaces contain projection/styling and chart interop only.
- Wave fallback namespaces are retired.
- Domain registry is the single extension point for indicator calculation.

## Idempotence and Recovery

Each family migration is additive then subtractive: first add domain implementation and adapter metadata, then remove wave/view implementation only after validation passes. If a regression appears, restore the removed family mapping in the prior owner namespace while preserving the domain implementation for investigation.

## Artifacts and Notes

Artifacts for each completed milestone will be added to dedicated completed ExecPlan records under `/hyperopen/docs/exec-plans/completed/` with validation evidence and file lists.

## Interfaces and Dependencies

The following public APIs must remain stable during this roadmap:

- `hyperopen.views.trading-chart.utils.indicators/get-available-indicators`
- `hyperopen.views.trading-chart.utils.indicators/calculate-indicator`
- `hyperopen.views.trading-chart.utils.indicators/calculate-sma`

New domain-level interfaces introduced by Milestone 1:

- `hyperopen.domain.trading.indicators.registry/get-domain-indicators`
- `hyperopen.domain.trading.indicators.registry/calculate-domain-indicator`

Plan revision note: 2026-02-15 15:11Z - Initial active roadmap created to drive remaining SOLID/DDD alignment work and start Milestone 1.
Plan revision note: 2026-02-15 15:18Z - Updated living sections after completing Milestone 1 implementation and validation.
Plan revision note: 2026-02-15 15:19Z - Updated living sections after completing first Milestone 2 migration batch (wave3 trend overlays) and validation.
Plan revision note: 2026-02-15 15:25Z - Updated living sections after completing Milestone 2 structure/pattern batch and validation.
Plan revision note: 2026-02-15 15:29Z - Updated living sections after completing Milestone 2 batch C and retiring wave3 runtime fallback.
Plan revision note: 2026-02-15 15:32Z - Updated living sections after completing Milestone 3 wave2 starter batch and validation.
Plan revision note: 2026-02-15 15:48Z - Updated living sections after completing Milestone 3 wave2 flow-family batch and validation.
Plan revision note: 2026-02-15 15:54Z - Updated living sections after completing Milestone 3 wave2 moving-average-variants batch and validation.
Plan revision note: 2026-02-15 15:58Z - Updated living sections after completing Milestone 3 wave2 crossover batch and validation.
Plan revision note: 2026-02-15 16:18Z - Updated living sections after completing Milestone 3 wave2 channel/oscillator/regression batches and retiring wave2 runtime fallback.
Plan revision note: 2026-02-15 16:29Z - Updated living sections after Milestone 4 contract hardening and heavy-indicator parity/performance test coverage.
Plan revision note: 2026-02-15 17:08Z - Updated living sections after completing Milestone 4 math-adapter isolation and adapter parity tests.
