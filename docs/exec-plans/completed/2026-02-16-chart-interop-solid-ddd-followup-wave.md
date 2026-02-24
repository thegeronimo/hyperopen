# Chart Interop SOLID/DDD Follow-up Wave

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

This plan builds on `/hyperopen/docs/exec-plans/completed/2026-02-16-chart-interop-solid-ddd-split.md`, which completed the initial namespace split, registry extraction, and legend XSS hardening.

## Purpose / Big Picture

The chart interop refactor removed major SRP/OCP issues, but there are still design gaps: implicit data contracts, direct infrastructure coupling, runtime state coupled to mutable ad hoc fields, missing tests around new seams, and avoidable hot-path format inference. After this change, charting boundaries will be explicit and validated, runtime orchestration state will move to a dedicated sidecar, and chart price formatting will prefer metadata over full-series scans while preserving existing behavior.

## Progress

- [x] (2026-02-16 00:36Z) Reviewed current chart interop split modules, chart core lifecycle flow, and existing test coverage gaps.
- [x] (2026-02-16 00:36Z) Created this active ExecPlan for follow-up SOLID/DDD work.
- [x] (2026-02-16 00:43Z) Added `/hyperopen/src/hyperopen/schema/chart_interop_contracts.cljs` with explicit contracts and assertions for candles, visible ranges, indicators/series definitions, legend metadata, and chart handles.
- [x] (2026-02-16 00:44Z) Wired contract assertions into `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs` and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs`.
- [x] (2026-02-16 00:44Z) Introduced dependency injection seams with backward-compatible arities for legend document/formatters and visible-range storage get/set collaborators.
- [x] (2026-02-16 00:45Z) Added `/hyperopen/src/hyperopen/views/trading_chart/runtime_state.cljs` and migrated `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` lifecycle orchestration to runtime sidecar state (removed ad hoc `.__*` field ownership).
- [x] (2026-02-16 00:46Z) Added targeted tests for legend business-day lookup and marker sidecar lifecycle, unknown chart-type fallback behavior, injected persistence dependency, metadata decimal preference, and runtime sidecar state.
- [x] (2026-02-16 00:47Z) Optimized series price-format behavior to prefer metadata decimals when provided and only scan transformed prices when metadata is absent.
- [x] (2026-02-16 00:47Z) Ran required gates: `npm run check`, `npm test`, `npm run test:websocket` (all green).
- [x] (2026-02-16 00:47Z) Fixed post-integration regression where chart contracts required vector-only candle collections; relaxed to sequential collections and normalized processed candles to vectors in `/hyperopen/src/hyperopen/views/trading_chart/utils/data_processing.cljs`.
- [x] (2026-02-16 00:47Z) Re-ran required gates after regression fix: `npm run check`, `npm test`, and `npm run test:websocket` (all green).

## Surprises & Discoveries

- Observation: Existing chart interop tests validate transforms and persistence/baseline behavior but do not cover legend lookup by Lightweight Charts business-day time objects or marker sidecar replacement semantics.
  Evidence: `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` currently includes persistence/transform/baseline tests only.
- Observation: Chart core lifecycle still mutates several `.__*` runtime flags on the chart handle from `core.cljs`, even after sidecars were added for baseline and markers internals.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` writes `.__chartType`, `.__visibleRangeRestoreTried`, `.__visibleRangePersistenceSubscribed`, and `.__visibleRangeCleanup`.
- Observation: Injecting a default DOM document via `js/document` in Node tests raises a runtime `ReferenceError` even when an injected document dependency is supplied.
  Evidence: Initial legend DI test failed in `npm test` until legend defaults switched to `js/globalThis` lookup with explicit missing-document error handling.
- Observation: Candle data entering chart interop can be sequential collections (for example `sort-by` output), not only vectors.
  Evidence: Runtime regression appeared in UI after contract enforcement until `assert-candles!` accepted `sequential?` and candle processing was normalized with `vec`.

## Decision Log

- Decision: Implement contracts in a dedicated schema namespace under `/hyperopen/src/hyperopen/schema/` and expose assertion helpers for chart interop boundaries.
  Rationale: This matches repository conventions used by order-form contracts and keeps boundary semantics centralized.
  Date/Author: 2026-02-16 / Codex
- Decision: Keep existing public chart interop function signatures stable by adding optional dependency/option map arities, not replacing current arities.
  Rationale: Call-site compatibility is required for safe incremental refactor.
  Date/Author: 2026-02-16 / Codex
- Decision: Use a dedicated WeakMap runtime sidecar in chart core keyed by mount node to own lifecycle flags and cleanups.
  Rationale: This removes hidden mutable contract fields from chart handle objects while preserving existing user-visible lifecycle behavior.
  Date/Author: 2026-02-16 / Codex
- Decision: Keep contract validation lightweight on candle collections by validating representative samples (first/middle/last) rather than full vectors.
  Rationale: This preserves boundary confidence in debug mode without adding heavy per-update overhead on large candle arrays.
  Date/Author: 2026-02-16 / Codex
- Decision: Metadata decimal optimization uses optional `:price-decimals` and preserves inference fallback.
  Rationale: This avoids behavioral regressions where metadata is unavailable while eliminating avoidable hot-path scans when market metadata is present.
  Date/Author: 2026-02-16 / Codex
- Decision: Keep chart candle contracts collection-shape tolerant (`sequential?`) while still enforcing candle field invariants.
  Rationale: The charting pipeline includes lazy/sequential intermediate collections, so vector-only contracts are too strict for runtime behavior.
  Date/Author: 2026-02-16 / Codex

## Outcomes & Retrospective

All five follow-up SOLID/DDD steps are complete. Chart interop now has explicit contracts, dependency seams, and metadata-first price formatting. Chart core runtime orchestration no longer stores ad hoc `.__*` fields on chart handles and now uses a dedicated runtime sidecar module. Additional focused tests cover legend business-day keying, markers sidecar lifecycle, fallback chart-type behavior, injected persistence seam behavior, metadata decimal preference, and runtime sidecar state. A post-integration regression (vector-only candle contract) was fixed by accepting sequential candle collections and normalizing processed candle output to vectors. Required validation gates passed with zero failures and zero compile warnings.

## Context and Orientation

Chart runtime entrypoint is `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`, which creates chart handles via `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`. The interop namespace now delegates to focused modules in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/`.

In this plan:

- “Contract” means an explicit runtime-validated shape at module boundaries (for example candle and legend metadata maps).
- “Dependency injection seam” means optional caller-provided collaborators (formatter, storage, document) with default implementations to preserve behavior.
- “Runtime sidecar” means state owned externally via `WeakMap`, keyed by the mounted DOM node, instead of ad hoc fields on chart objects.

Key files:

- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/legend.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`
- `/hyperopen/test/hyperopen/views/trading_chart/core_test.cljs`

## Plan of Work

Milestone 1 adds chart-specific contracts in a new schema namespace and wires boundary assertions into chart interop entrypoints. This includes candle and legend metadata validation, visible-range value object shape validation, indicator/series definitions, and chart handle validation for lifecycle functions.

Milestone 2 adds dependency seams. Legend creation accepts optional injected document and formatter functions while keeping current behavior by default. Visible-range persistence accepts optional injected storage get/set functions while keeping platform storage defaults.

Milestone 3 migrates chart core lifecycle flags/cleanup ownership into a dedicated runtime sidecar module. Core will stop mutating `.__*` fields and instead read/update sidecar state. Existing chart object references used by lightweight-charts interop remain unchanged.

Milestone 4 expands tests for the new seams and edge-cases: legend business-day lookup, markers plugin replacement lifecycle, chart-type fallback behavior, and runtime sidecar semantics.

Milestone 5 updates price-format selection to prefer caller-provided decimals (from market metadata) and only infer from full transformed data when metadata is absent.

## Concrete Steps

From `/hyperopen`:

1. Add `/hyperopen/src/hyperopen/schema/chart_interop_contracts.cljs` with specs/assertions for candle, visible range, indicator defs, legend metadata, and chart handle.
2. Update interop modules and facade:
   - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`
   - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/legend.cljs`
   - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs`
   - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/price_format.cljs`
3. Add chart core runtime sidecar module and integrate into:
   - `/hyperopen/src/hyperopen/views/trading_chart/runtime_state.cljs` (new)
   - `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`
4. Expand tests:
   - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`
   - `/hyperopen/test/hyperopen/views/trading_chart/core_test.cljs`
5. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

Expected transcript shape:

  - `npm run check`: pass, no lint/compile errors.
  - `npm test`: pass, 0 failures, 0 errors.
  - `npm run test:websocket`: pass, 0 failures, 0 errors.

## Validation and Acceptance

Acceptance is met when:

- Chart interop boundary functions assert explicit contract shapes for candle/legend/indicator/visible-range/chart-handle inputs.
- Legend and visible-range persistence modules support optional dependency injection and still work with default dependencies.
- Chart core no longer stores runtime orchestration flags on chart handles via ad hoc `.__*` fields.
- Targeted tests exist and pass for legend business-day key matching, markers sidecar lifecycle, chart-type fallback behavior, and runtime sidecar behavior.
- `set-series-data!` supports metadata decimals input and skips full-series inference when decimals are provided.
- Required repository validation gates pass.

## Idempotence and Recovery

This work is additive and backward-compatible by arity. If any step regresses behavior, callers can continue using existing function arities while optional seams remain unused. Runtime sidecar migration is reversible by restoring prior field reads/writes in `core.cljs`; no data migration or destructive operation is involved.

## Artifacts and Notes

Planned changed files:

- `/hyperopen/src/hyperopen/schema/chart_interop_contracts.cljs` (new)
- `/hyperopen/src/hyperopen/views/trading_chart/runtime_state.cljs` (new)
- `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/legend.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/price_format.cljs`
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`
- `/hyperopen/test/hyperopen/views/trading_chart/core_test.cljs`
- `/hyperopen/docs/exec-plans/completed/2026-02-16-chart-interop-solid-ddd-followup-wave.md`

## Interfaces and Dependencies

At completion, these interfaces must exist:

- `hyperopen.schema.chart-interop-contracts/assert-candles!`
- `hyperopen.schema.chart-interop-contracts/assert-legend-meta!`
- `hyperopen.schema.chart-interop-contracts/assert-indicators!`
- `hyperopen.schema.chart-interop-contracts/assert-chart-handle!`
- `hyperopen.views.trading-chart.runtime-state/get-state`
- `hyperopen.views.trading-chart.runtime-state/assoc-state!`
- `hyperopen.views.trading-chart.runtime-state/clear-state!`
- Optional-arity interop seam functions:
  - `create-legend!`
  - `apply-persisted-visible-range!`
  - `subscribe-visible-range-persistence!`
  - `set-series-data!`

Plan revision note: 2026-02-16 00:36Z - Initial follow-up plan created for remaining SOLID/DDD work after first chart interop split.
Plan revision note: 2026-02-16 00:47Z - Updated after implementing contracts, DI seams, runtime sidecar migration, targeted tests, metadata decimal optimization, and required validation gates.
Plan revision note: 2026-02-16 00:47Z - Updated after fixing sequential candle collection contract regression and re-running validation gates.
