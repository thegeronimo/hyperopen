# Portfolio Chart Follow-up: Y-Axis Visibility and Axis/Plot Geometry Parity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After the first Account Value/PNL chart implementation, users reported that the y-axis was effectively broken and the chart geometry did not match Hyperliquid. This follow-up aligns the axis model and plot rendering with Hyperliquid’s observed behavior so users can reliably read y-axis ticks and view chart structure that matches expectations.

Users can verify by loading `/portfolio` with empty history: the chart now shows a visible left y-axis with `3/2/1/0` ticks and an axis-only frame (no full-width synthetic grid), consistent with Hyperliquid’s empty-state behavior.

## Progress

- [x] (2026-02-20 01:11Z) Captured new Hyperliquid vs local browser-inspection parity snapshots for `/portfolio`.
- [x] (2026-02-20 01:12Z) Identified axis-model mismatch (dedicated y-axis gutter in Hyperliquid vs collapsed/inline axis in Hyperopen; no-data tick behavior mismatch).
- [x] (2026-02-20 01:13Z) Updated portfolio chart VM to provide Hyperliquid-style empty y-axis fallback ticks and readable step-based y-axis domain ticks.
- [x] (2026-02-20 01:14Z) Updated portfolio chart view to use dedicated y-axis gutter width, axis line + tick marks, and axis-line-only frame (removed full-width synthetic grid).
- [x] (2026-02-20 01:15Z) Added/updated VM tests for empty ticks and readable y-axis step behavior.
- [x] (2026-02-20 01:16Z) Ran validation gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-20 01:17Z) Re-captured browser-inspection screenshots and confirmed y-axis visibility/parity direction.

## Surprises & Discoveries

- Observation: Hyperliquid’s no-data portfolio chart still renders four y-axis ticks (`3`, `2`, `1`, `0`) instead of collapsing to a single zero tick.
  Evidence: Browser inspection capture: `/hyperopen/tmp/browser-inspection/compare-2026-02-20T01-11-11-141Z-b2b0088b/hyperliquid/desktop/screenshot.png`.

- Observation: Hyperliquid chart card draws axis lines/ticks without full-width horizontal grid lines.
  Evidence: Bundle inspection (`/hyperopen/tmp/hyperliquid-6951.pretty.js`) shows `XAxis` + `YAxis` + `Line` only, no `CartesianGrid`.

## Decision Log

- Decision: Add deterministic empty-state y-ticks in VM (`3/2/1/0`) to preserve axis readability even with no history points.
  Rationale: This matches observed Hyperliquid behavior and prevents the “invisible y-axis” failure mode reported by users.
  Date/Author: 2026-02-20 / Codex

- Decision: Move y-axis labels/ticks into a dedicated left gutter with computed width based on tick label length.
  Rationale: Prevents clipping and ensures large formatted values remain visible.
  Date/Author: 2026-02-20 / Codex

- Decision: Remove synthetic full-width y-grid lines from our SVG chart.
  Rationale: Hyperliquid uses axis lines/ticks, and the extra grid lines made our chart look materially different.
  Date/Author: 2026-02-20 / Codex

## Outcomes & Retrospective

Delivered:

- Y-axis visibility fixed with dedicated gutter and visible tick labels.
- Empty-state chart now renders `3/2/1/0` y-axis scale instead of a single `0` tick.
- Axis and framing style now follows Hyperliquid more closely (axis lines/ticks instead of synthetic grid overlay).
- Added a VM regression test to enforce readable step-based y-axis ticks.

Validation:

- `npm run check` passed.
- `npm test` passed.
- `npm run test:websocket` passed.

## Context and Orientation

Primary implementation files:

- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
- `/hyperopen/src/hyperopen/views/portfolio_view.cljs`

Primary tests:

- `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`

Browser-inspection artifacts:

- Before fix local screenshot: `/hyperopen/tmp/browser-inspection/compare-2026-02-20T01-11-11-141Z-b2b0088b/hyperopen-local/desktop/screenshot.png`
- After fix local screenshot: `/hyperopen/tmp/browser-inspection/compare-2026-02-20T01-14-17-034Z-0d289757/hyperopen-local/desktop/screenshot.png`
- Hyperliquid reference screenshot: `/hyperopen/tmp/browser-inspection/compare-2026-02-20T01-11-11-141Z-b2b0088b/hyperliquid/desktop/screenshot.png`

## Plan of Work

1. Reproduce visual mismatch with browser-inspection against live Hyperliquid.
2. Correct VM y-axis tick/domain behavior for empty and populated chart states.
3. Correct chart view layout to reserve axis gutter and remove non-parity grid elements.
4. Validate via automated tests and browser screenshot capture.

## Concrete Steps

Executed commands (repo root):

    node tools/browser-inspection/src/cli.mjs compare --left-url https://app.hyperliquid.xyz/portfolio --right-url http://localhost:8080/portfolio --left-label hyperliquid --right-label hyperopen-local --viewports desktop --manage-local-app
    npm run check
    npm test
    npm run test:websocket
    node tools/browser-inspection/src/cli.mjs compare --left-url https://app.hyperliquid.xyz/portfolio --right-url http://localhost:8080/portfolio --left-label hyperliquid --right-label hyperopen-local --viewports desktop --manage-local-app

## Validation and Acceptance

Accepted when:

1. Y-axis is visible and legible in empty chart state.
2. Empty chart state renders `3/2/1/0` style y-ticks.
3. Chart frame uses axis lines/ticks rather than synthetic full-width grid.
4. Required validation gates pass.

## Idempotence and Recovery

All changes are source-level and safe to re-run. If a future parity pass introduces regressions, revert only chart-card rendering and VM y-axis helpers while preserving action/state plumbing.

## Artifacts and Notes

Inspection run IDs:

- `compare-2026-02-20T01-11-11-141Z-b2b0088b` (pre-fix)
- `compare-2026-02-20T01-14-17-034Z-0d289757` (post-fix)

## Interfaces and Dependencies

No new dependencies. Existing `:chart` VM contract extended with stable y-tick expectations in empty state.

Plan revision note: 2026-02-20 01:17Z - Initial and final entry for y-axis/axis-geometry parity follow-up, completed with validation and artifact links.
