# Chart Interop SOLID/DDD Split and Safety Hardening

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The trading chart interop layer currently combines chart API calls, data transforms, formatting inference, baseline subscriptions, visible-range persistence, legend DOM rendering, indicator pane wiring, and markers plugin lifecycle in one file. After this change, those concerns are split into focused modules behind the same public facade so behavior stays stable while maintenance risk drops. A user-visible result is that chart rendering, legend updates, indicator overlays, baseline behavior, and persisted viewport restore still work as before, while security and correctness improve through safe legend rendering and clearer chart-type contracts.

## Progress

- [x] (2026-02-16 00:10Z) Reviewed `/hyperopen/.agents/PLANS.md`, current `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`, its call sites, and existing tests.
- [x] (2026-02-16 00:10Z) Created this active ExecPlan with implementation and validation milestones.
- [x] (2026-02-16 00:13Z) Extracted chart-type normalization, OHLC transforms, and registry-driven chart behavior to `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/transforms.cljs` and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/series.cljs`.
- [x] (2026-02-16 00:13Z) Extracted price-format inference and baseline lifecycle to `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/price_format.cljs` and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/baseline.cljs`, with baseline subscription state moved to WeakMap sidecars.
- [x] (2026-02-16 00:13Z) Extracted visible-range persistence and legend rendering to `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs` and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/legend.cljs`; removed legend `innerHTML` writes and corrected style application to `style.cssText`.
- [x] (2026-02-16 00:13Z) Added `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/indicators.cljs` and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/markers.cljs`; markers plugin lifecycle now uses a WeakMap sidecar.
- [x] (2026-02-16 00:13Z) Rewrote `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs` as a stable public facade with unchanged function names/signatures.
- [x] (2026-02-16 00:15Z) Verified transformed architecture against existing chart interop and chart core tests (`hyperopen.views.trading-chart.utils.chart-interop-test` and `hyperopen.views.trading-chart.core-test` included in full suite).
- [x] (2026-02-16 00:15Z) Ran required validation gates: `npm run check`, `npm test`, and `npm run test:websocket` (all green).

## Surprises & Discoveries

- Observation: `chart_interop.cljs` is directly coupled to lifecycle flags in `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` through shared chart object fields, so refactoring must preserve that external contract.
  Evidence: `core.cljs` reads/writes `.__chartType`, `.__visibleRange*`, `.legendControl`, `.mainSeries`, `.volumeSeries`, and `.indicatorSeries` on the chart object.
- Observation: Existing tests already cover baseline subscription behavior and visible range persistence behavior, which provides a good safety net for internal extraction.
  Evidence: `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` has tests for `sync-baseline-base-value-subscription!`, `apply-persisted-visible-range!`, and `subscribe-visible-range-persistence!`.
- Observation: `chart_interop` tests did not require direct edits after extraction because the facade contract remained stable and all assertions stayed green.
  Evidence: `npm test` passed with `0 failures, 0 errors` and includes `hyperopen.views.trading-chart.utils.chart-interop-test`.

## Decision Log

- Decision: Keep the public namespace and function names in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs` unchanged while splitting internals into new helper namespaces.
  Rationale: This delivers SRP/OCP improvements without forcing call-site churn in `core.cljs` or other chart view code.
  Date/Author: 2026-02-16 / Codex
- Decision: Replace internal monkey-patching for baseline and markers lifecycle state with module-local WeakMap sidecars keyed by `chart-obj`.
  Rationale: WeakMap sidecars remove hidden structural coupling to foreign JS objects while preserving behavior and avoiding memory leaks.
  Date/Author: 2026-02-16 / Codex
- Decision: Rebuild legend content using DOM nodes and `.textContent` updates instead of string-concatenated `innerHTML`.
  Rationale: This removes HTML injection risk and fixes the existing incorrect full-style assignment pattern.
  Date/Author: 2026-02-16 / Codex
- Decision: Keep legend behavior parity by preserving text/field layout while swapping rendering primitives to DOM node updates.
  Rationale: This achieves security hardening without changing user-visible legend semantics.
  Date/Author: 2026-02-16 / Codex

## Outcomes & Retrospective

The chart interop namespace is now a thin facade with extracted responsibilities under `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/`. Chart-type behavior is registry-driven, baseline and markers lifecycle state uses WeakMap sidecars, and legend rendering no longer uses unescaped `innerHTML`. Required validation gates (`npm run check`, `npm test`, `npm run test:websocket`) all passed with no failures, so behavior parity was maintained while addressing SRP/OCP and immediate safety issues.

## Context and Orientation

The charting view mounts in `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` and delegates all chart runtime work to `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`. That interop file currently owns multiple responsibilities: chart and series creation, OHLC transforms, price format inference, baseline base-value subscription lifecycle, visible-range save/restore via localStorage, legend DOM management, indicator pane allocation, and markers plugin lifecycle.

In this plan, a “facade” means a thin namespace that keeps old function names and signatures while delegating implementation to focused modules. A “sidecar” means module-owned state stored externally from the chart object, here implemented with JavaScript `WeakMap` keyed by the chart handle object.

Key files for this work:

- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs` (existing public API facade target).
- `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` (consumer with lifecycle behavior that must remain stable).
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` (regression tests for transforms, baseline, and persistence).

## Plan of Work

Milestone 1 extracts pure transformation and chart-type behavior into dedicated modules. Create transform helpers and a chart-type registry that centralizes three operations per type: add series function, transform data function, and price extraction function. Replace duplicated `case` logic in add-series and set-series paths with registry lookup and fallback to `:candlestick`.

Milestone 2 extracts baseline behavior and markers lifecycle state into modules that use WeakMap sidecars. Keep the existing public API functions for synchronization and cleanup, but remove internal `.__baselineBase*` and `.mainSeriesMarkers*` writes from interop internals.

Milestone 3 extracts visible-range persistence and legend behavior. Preserve restore/subscribe API, but keep storage parsing/normalization isolated in its own namespace. Rebuild legend rendering to construct DOM nodes once and update text safely via `.textContent`, and apply styles through `style.cssText`.

Milestone 4 rewires the facade namespace to delegate to extracted modules and keeps compatibility exports for current call sites and tests. After extraction, run required repository validation gates and update this plan’s living sections with results.

## Concrete Steps

From `/hyperopen`:

1. Add new modules under `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/` for:
   - transforms and chart-type normalization.
   - chart-type registry and series add/transform/price extraction.
   - price format inference.
   - baseline subscription lifecycle.
   - visible range persistence.
   - legend rendering.
   - indicator and marker helpers.
2. Update `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs` to require these modules and delegate existing public API functions.
3. Update or add tests in `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` to assert behavior parity and safety-sensitive behavior where practical.
4. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

Expected transcript shape:

  - `npm run check` succeeds with no lint or compile failures.
  - `npm test` succeeds with 0 failures and 0 errors.
  - `npm run test:websocket` succeeds with 0 failures and 0 errors.

## Validation and Acceptance

Acceptance is met when the following are true:

- Chart type handling is registry-driven (single source for add/transform/price extraction) rather than duplicated `case` branches.
- `chart_interop.cljs` no longer directly owns transform logic, visible-range parsing/persistence internals, baseline lifecycle internals, legend DOM internals, and indicator pane allocation internals.
- Baseline and markers lifecycle state in interop internals is managed with WeakMap sidecars, not custom fields on `chart-obj`.
- `create-legend!` no longer writes concatenated `innerHTML` strings with unescaped external text and no longer assigns style through `(set! (.-style legend) "...")`.
- Existing chart interop tests pass, and required repository validation gates pass.

## Idempotence and Recovery

This refactor is additive-first. The facade remains stable while internals move behind it. If a module extraction introduces regressions, the safe recovery path is to temporarily point the affected facade function back to the original local implementation while preserving new module files for iterative repair. No schema migration or destructive command is required.

## Artifacts and Notes

Planned changed paths:

- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/*.cljs` (new modules)
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`
- `/hyperopen/docs/exec-plans/active/2026-02-16-chart-interop-solid-ddd-split.md` (living updates)

## Interfaces and Dependencies

At completion, these interfaces must exist and remain stable for existing callers:

- `hyperopen.views.trading-chart.utils.chart-interop/add-series!`
- `hyperopen.views.trading-chart.utils.chart-interop/set-series-data!`
- `hyperopen.views.trading-chart.utils.chart-interop/sync-baseline-base-value-subscription!`
- `hyperopen.views.trading-chart.utils.chart-interop/clear-baseline-base-value-subscription!`
- `hyperopen.views.trading-chart.utils.chart-interop/apply-persisted-visible-range!`
- `hyperopen.views.trading-chart.utils.chart-interop/subscribe-visible-range-persistence!`
- `hyperopen.views.trading-chart.utils.chart-interop/create-legend!`
- `hyperopen.views.trading-chart.utils.chart-interop/create-chart-with-volume-and-series!`
- `hyperopen.views.trading-chart.utils.chart-interop/create-chart-with-indicators!`
- `hyperopen.views.trading-chart.utils.chart-interop/set-main-series-markers!`

External dependencies continue to be:

- `lightweight-charts` JS API for chart creation, series APIs, and marker plugin creation.
- `/hyperopen/src/hyperopen/platform.cljs` local-storage wrappers for persistence.
- `/hyperopen/src/hyperopen/utils/formatting.cljs` price and delta formatting for legend display.

Plan revision note: 2026-02-16 00:10Z - Initial plan created from chart interop SOLID/DDD review and current codebase analysis.
Plan revision note: 2026-02-16 00:15Z - Updated living sections after completing extraction modules, facade rewiring, and required validation gates.
