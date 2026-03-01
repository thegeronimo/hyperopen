# Chart Liquidation Line Drag Margin Confirmation

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and must be maintained in accordance with that file.

## Purpose / Big Picture

Users can see the chart liquidation line, but they could not act on it directly. After this change, the liquidation badge on the chart is draggable. Releasing the drag opens the existing `Edit Margin` modal prefilled with the required `Add` or `Remove` amount, so the user can review and confirm before submitting the onchain `updateIsolatedMargin` action.

You can verify behavior by opening a position, dragging `Liq. Price` on the chart, and confirming that the margin modal opens with chart-drag context and prefilled amount/mode.

## Progress

- [x] (2026-03-01 02:20Z) Added liquidation-line drag interaction in chart interop with pointer lifecycle, preview rendering, and callback emission on release.
- [x] (2026-03-01 02:20Z) Wired chart callback to `:actions/open-position-margin-modal` with chart-drag prefill payload (`mode`, `amount`, current/target liq, anchor bounds).
- [x] (2026-03-01 02:20Z) Extended margin domain/modal state to accept optional drag-prefill fields and initialize modal inputs from them.
- [x] (2026-03-01 02:20Z) Added margin modal chart-drag confirmation summary copy showing current and target liquidation prices.
- [x] (2026-03-01 02:20Z) Added regression tests for drag callback payload, chart callback dispatch plumbing, and prefill state initialization.
- [x] (2026-03-01 02:24Z) Ran required gates: `npm run check`, `npm test`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Chart overlay tests rely on a fake DOM that does not include payload-capable event dispatch by default.
  Evidence: Existing helpers only emitted `preventDefault`/`stopPropagation`; drag tests required `clientX`/`clientY`.

- Observation: ClojureScript analyzer warns about arithmetic on nullable values even inside runtime guards.
  Evidence: Initial compile warning in `position_overlays.cljs` around sign detection for `margin-delta`; resolved by normalizing to numeric fallback.

## Decision Log

- Decision: Reuse `:actions/open-position-margin-modal` instead of adding a new chart-specific runtime action.
  Rationale: Keeps interaction deterministic and scoped to UI state projection while reusing existing validated margin submit flow.
  Date/Author: 2026-03-01 / Codex

- Decision: Use chart drag as a confirmation prefill step, not auto-submit.
  Rationale: Margin updates are high-impact trading actions and should remain explicit and reviewable.
  Date/Author: 2026-03-01 / Codex

- Decision: Compute drag-derived margin delta in chart interop from side, size, and liquidation delta, then pass normalized payload into modal open.
  Rationale: Enables live drag preview copy and keeps domain submit contracts unchanged.
  Date/Author: 2026-03-01 / Codex

## Outcomes & Retrospective

The chart liquidation overlay now supports direct drag-to-confirm workflow:

- `Liq. Price` badge supports pointer drag.
- During drag, overlay previews target liquidation and derived margin adjustment text.
- Releasing drag emits a suggestion payload and opens the existing `Edit Margin` modal with prefilled mode/amount and chart-drag context.
- Modal shows current/target liquidation summary for confirmation before submit.

Validation outcomes:

- `npm run check`: pass
- `npm test`: pass (1559 tests, 8074 assertions)
- `npm run test:websocket`: pass (154 tests, 710 assertions)

## Context and Orientation

Chart overlay rendering lives in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs`. Trading chart orchestration and action dispatch helpers live in `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`. Margin modal state derivation is in `/hyperopen/src/hyperopen/account/history/position_margin.cljs`, and modal rendering is in `/hyperopen/src/hyperopen/views/account_info/position_margin_modal.cljs`.

In this plan, “drag confirmation” means drag selects a target liquidation price and opens the existing margin modal with the required amount prefilled, but does not submit automatically.

## Plan of Work

Implement liquidation drag lifecycle inside chart interop overlay code. Keep repaint and subscription behavior deterministic, and tear down listeners on clear/unmount. Add a callback option on position overlay sync so chart core can react to drag release. In chart core, dispatch the existing open-margin action with prefill metadata. In the margin domain, consume optional prefill fields and initialize mode/input fields safely with clamping to available limits. In the modal view, display explicit chart-drag context. Add focused tests for each boundary.

## Concrete Steps

From repository root `/hyperopen`:

1. Implement chart drag behavior and callback plumbing.
2. Implement margin prefill parsing and modal summary rendering.
3. Add/adjust tests in:
   - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/position_overlays_test.cljs`
   - `/hyperopen/test/hyperopen/views/trading_chart/core_test.cljs`
   - `/hyperopen/test/hyperopen/account/history/position_margin_test.cljs`
   - `/hyperopen/test/hyperopen/account/history/actions_test.cljs`
   - `/hyperopen/test/hyperopen/views/trading_chart/test_support/fake_dom.cljs`
4. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance is satisfied when:

1. Dragging chart `Liq. Price` and releasing emits a margin confirmation suggestion.
2. Release opens `Edit Margin` with chart-drag prefilled amount/mode.
3. Modal shows chart-drag summary (`Current -> Target`) and requires explicit submit.
4. Existing margin submit path remains unchanged (`updateIsolatedMargin` through existing effect path).
5. All required validation gates pass.

## Idempotence and Recovery

All changes are additive. Re-running tests and build gates is safe. If drag interaction regresses, disable only the drag callback wiring while leaving static overlay rendering and existing margin modal flow intact.

## Artifacts and Notes

Key implementation files:

- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`
- `/hyperopen/src/hyperopen/account/history/position_margin.cljs`
- `/hyperopen/src/hyperopen/views/account_info/position_margin_modal.cljs`

## Interfaces and Dependencies

No new external dependencies were added.

Updated interfaces:

- `hyperopen.views.trading-chart.utils.chart-interop.position-overlays/sync-position-overlays!` now accepts optional `:on-liquidation-drag-confirm` and `:window` in opts.
- Chart drag callback payload shape:
  - `:mode` (`:add` or `:remove`)
  - `:amount` (USDC)
  - `:current-liquidation-price`
  - `:target-liquidation-price`
  - `:anchor` (bounds map for modal placement)

Plan revision note: 2026-03-01 02:24Z - Added full implementation status, validation outcomes, and finalized interface notes after completing drag confirmation workflow.
