# Chart Hover Navigation Controls (Hyperliquid Parity)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Linked `bd` issue: `hyperopen-qro8` (closed as Completed).

## Purpose / Big Picture

Users can currently normalize the chart only through gestures (wheel/drag/double-click behavior). Hyperliquid users expect explicit hover controls at the bottom of the chart for zooming, panning, and resetting view. After this change, the trade chart will show a hover/focus button rail (`-`, `+`, left, right, reset) that drives deterministic time-scale changes and preserves existing visible-range persistence behavior.

## Progress

- [x] (2026-03-12 20:02Z) Reviewed repository requirements: `/hyperopen/AGENTS.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, `/hyperopen/docs/agent-guides/trading-ui-policy.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and `/hyperopen/.agents/PLANS.md`.
- [x] (2026-03-12 20:02Z) Created and claimed `bd` issue `hyperopen-qro8`.
- [x] (2026-03-12 20:07Z) Researched target behavior from TradingView docs and captured product requirements in `/hyperopen/docs/product-specs/chart-hover-navigation-controls-prd.md`.
- [x] (2026-03-12 20:08Z) Created this active ExecPlan.
- [x] (2026-03-12 20:09Z) Implemented chart navigation overlay interop module and lifecycle wiring in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay.cljs`, `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`, and `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`.
- [x] (2026-03-12 20:10Z) Added/extended unit tests in `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay_test.cljs` and `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`.
- [x] (2026-03-12 20:13Z) Ran required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-03-12 20:14Z) Reran `npm run check` after final warning-removal patch to confirm clean lint/compile state.
- [x] (2026-03-12 20:14Z) Moved this plan to `/hyperopen/docs/exec-plans/completed/` after validation passed.
- [x] (2026-03-12 20:14Z) Closed `bd` issue `hyperopen-qro8` with reason `Completed`.

## Surprises & Discoveries

- Observation: Existing chart code already has a robust visible-range persistence model keyed by asset/timeframe and wired through chart lifecycle, including default-range fallback and interaction guards.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs`.
- Observation: Existing chart overlay modules (volume, open-order, position) are implemented as DOM overlays at the chart interop boundary, which is the correct architectural seam for this feature.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/volume_indicator_overlay.cljs`.
- Observation: This worktree initially lacked installed npm dependencies, which caused `npm run check` to fail before compilation (`@noble/secp256k1` missing).
  Evidence: Initial `npm run check` failure output followed by `npm install` and successful reruns.

## Decision Log

- Decision: Implement hover controls as a dedicated chart interop overlay module instead of Hiccup controls in the view tree.
  Rationale: The controls operate directly on chart/time-scale runtime objects and should live at the same boundary as existing chart overlays.
  Date/Author: 2026-03-12 / Codex
- Decision: Reuse existing default visible-range policy for reset behavior rather than introducing new reset math.
  Rationale: Keeps reset deterministic and aligned with current chart initialization and persistence semantics.
  Date/Author: 2026-03-12 / Codex
- Decision: Trigger navigation actions on `click` only (not both `pointerdown` and `click`).
  Rationale: Prevents duplicate action dispatch for a single user interaction and keeps range mutations deterministic.
  Date/Author: 2026-03-12 / Codex
- Decision: Add lightweight fallback reset behavior (`resetTimeScale` or `fitContent + scrollToRealTime`) when no injected reset callback is supplied.
  Rationale: Makes overlay behavior robust for tests and future reuse while preserving current chart-specific reset policy via injected callback.
  Date/Author: 2026-03-12 / Codex

## Outcomes & Retrospective

Implemented scope is complete. The trading chart now includes a dedicated hover/focus bottom navigation overlay with five controls (`Zoom out`, `Zoom in`, `Scroll left`, `Scroll right`, `Reset chart view`) and tooltips/ARIA labels. Control actions mutate the chart time scale deterministically and invoke interaction callbacks so existing visible-range persistence behavior remains intact.

Validation results:

- `npm run check` passed.
- `npm test` passed (`2345` tests, `0` failures).
- `npm run test:websocket` passed (`376` tests, `0` failures).

Complexity impact:

- Overall complexity increased slightly at the interop boundary (one new overlay module + wrapper wiring) but reduced behavioral complexity for users by providing explicit normalization controls. The implementation keeps complexity localized to chart interop and avoids adding global action/state machinery.

## Context and Orientation

The trading chart surface is rendered in `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`. Chart objects are created and updated through `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs` and its modules under `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/**`.

Visible-range persistence already exists in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs` and is wired from chart lifecycle in `core.cljs`. The new hover controls must operate without breaking that behavior.

Tests for this area live in:

- `/hyperopen/test/hyperopen/views/trading_chart/core_test.cljs`
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence_test.cljs`
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/volume_indicator_overlay_test.cljs`

## Plan of Work

Milestone 1 adds a new interop overlay module for chart navigation controls. The module will own DOM creation, hover/focus visibility state, tooltips, and click handlers for zoom/pan/reset actions.

Milestone 2 wires this module through the chart interop facade and chart lifecycle in `core.cljs`, using injected callbacks so button actions mark visible-range interaction epochs and reuse existing default-range reset policy.

Milestone 3 extends tests to cover overlay behavior and wrapper delegation, then runs repository-required quality gates.

Milestone 4 finalizes documentation/work tracking by moving this plan to completed and closing the linked `bd` issue.

## Concrete Steps

From `/hyperopen`:

1. Implement overlay module and interop facade updates.
   - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay.cljs` (new)
   - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`
2. Wire mount/update/unmount lifecycle.
   - `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`
3. Add test coverage.
   - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay_test.cljs` (new)
   - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`
4. Run quality gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
5. Complete planning/work-tracking closure:
   - Move this file to `/hyperopen/docs/exec-plans/completed/`.
   - `bd close hyperopen-qro8 --reason "Completed" --json`

## Validation and Acceptance

Acceptance is met when:

- Chart hover/focus shows bottom navigation controls in target order.
- Zoom/pan buttons update visible range in expected directions.
- Reset button restores default normalized chart view.
- Tooltips/ARIA labels are present and meaningful.
- Overlay teardown leaves no mounted controls after chart unmount.
- Required gates (`npm run check`, `npm test`, `npm run test:websocket`) pass.

## Idempotence and Recovery

This work is additive and safe to re-run. Overlay sync functions are idempotent and use sidecar state keyed by chart object. If regressions occur, disabling the new sync/clear calls in `core.cljs` and interop wrappers reverts behavior without touching persisted chart data.

## Artifacts and Notes

Primary reference docs used for behavior parity:

- https://www.tradingview.com/support/solutions/43000703699-how-to-configure-your-supercharts/
- https://www.tradingview.com/charting-library-docs/latest/releases/release-notes
- https://tradingview.github.io/lightweight-charts/docs/api/interfaces/ITimeScaleApi
- https://tradingview.github.io/lightweight-charts/docs/api/interfaces/IChartApi

## Interfaces and Dependencies

At completion, these functions should exist:

- `hyperopen.views.trading-chart.utils.chart-interop/sync-chart-navigation-overlay!`
- `hyperopen.views.trading-chart.utils.chart-interop/clear-chart-navigation-overlay!`
- `hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay/sync-chart-navigation-overlay!`
- `hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay/clear-chart-navigation-overlay!`

Plan revision note: 2026-03-12 20:08Z - Initial plan created for `hyperopen-qro8`.
Plan revision note: 2026-03-12 20:14Z - Updated progress/decisions/discoveries/outcomes after implementation and validation.
Plan revision note: 2026-03-12 20:14Z - Moved plan to completed and recorded `bd` closure state.
