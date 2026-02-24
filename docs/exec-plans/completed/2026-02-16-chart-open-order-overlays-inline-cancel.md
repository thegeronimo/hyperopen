# Chart Open-Order Overlays With Inline Cancel

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Today users can cancel open orders from the Open Orders table, but not directly from the chart at the order price level. After this change, when an instrument is the active asset, that asset’s open orders will render as horizontal chart overlays at each order price, styled by side (`Buy` vs `Sell`), with an inline `X` cancel affordance. Users will be able to click cancel directly on the chart and use the same canonical cancel action path already used by the account table.

## Progress

- [x] (2026-02-16 23:50Z) Reviewed chart runtime lifecycle, chart interop boundaries, open-order normalization/source selection, and cancel-order action/effect flow.
- [x] (2026-02-16 23:50Z) Created this active ExecPlan file.
- [x] (2026-02-16 23:54Z) Added active-asset open-order projection helpers in `/hyperopen/src/hyperopen/views/account_info/projections.cljs` (`open-order-for-active-asset?`, `normalized-open-orders-for-active-asset`, dedupe by coin/oid).
- [x] (2026-02-16 23:56Z) Added `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs` with chart-local sidecar lifecycle, overlay rendering at `priceToCoordinate`, inline cancel button wiring, and deterministic cleanup/unsubscribe behavior.
- [x] (2026-02-16 23:56Z) Wired chart interop facade functions (`sync-open-order-overlays!`, `clear-open-order-overlays!`) and chart core lifecycle usage (mount/update/unmount) in `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`.
- [x] (2026-02-16 23:57Z) Wired chart-side cancel dispatch through Replicant runtime dispatch binding to canonical `:actions/cancel-order`.
- [x] (2026-02-16 23:58Z) Added/extended tests in `/hyperopen/test/hyperopen/views/account_info/projections_test.cljs` and `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`.
- [x] (2026-02-16 23:59Z) Ran required gates: `npm run check`, `npm test`, `npm run test:websocket` (all green).

## Surprises & Discoveries

- Observation: Current chart interop already uses a `WeakMap` sidecar pattern for markers and baseline subscriptions, which is suitable for managing overlay DOM lifecycle without mutating chart handles ad hoc.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/markers.cljs` and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/baseline.cljs`.
- Observation: Open order rows come from multiple sources (`:open-orders`, snapshots, per-dex snapshots), and the existing account projection utilities already normalize heterogeneous order payloads.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/projections.cljs` (`normalized-open-orders`, `open-orders-source`, `normalize-open-order`).
- Observation: Cancel order behavior should remain on the canonical action path; direct API calls from chart view would bypass existing guardrails and optimistic prune behavior.
  Evidence: `/hyperopen/src/hyperopen/order/actions.cljs` and `/hyperopen/src/hyperopen/order/effects.cljs`.
- Observation: Strict fake DOM child removal in tests caused an existing legend cleanup assertion to fail because the prior helper left stale child references in `children`.
  Evidence: `legend-supports-time-lookups-update-and-destroy-cleanup-test` in `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` required assertion update to validate empty children after destroy.
- Observation: Overlay sort order by descending price means the first clickable cancel target in tests is the highest-price order, not insertion order.
  Evidence: `open-order-overlays-render-lines-and-inline-cancel-test` deterministic expectation updated to the highest priced row.

## Decision Log

- Decision: Reuse existing open-order normalization helpers and derive an active-asset subset rather than introducing a separate chart-only order shape.
  Rationale: Keeps one normalization truth and prevents drift between table and chart.
  Date/Author: 2026-02-16 / Codex
- Decision: Implement chart overlay lifecycle in chart interop sidecar state (not in global app state).
  Rationale: Overlay geometry is chart-runtime-local, derived from `priceToCoordinate`, and should be cleaned up with chart mount/unmount.
  Date/Author: 2026-02-16 / Codex
- Decision: Route inline chart cancel through `:actions/cancel-order`.
  Rationale: Preserves wallet/trading-enabled checks, optimistic prune logic, error handling, and refresh behavior already implemented.
  Date/Author: 2026-02-16 / Codex
- Decision: Use dynamic optional method invocation (`aget` + `.apply`) for chart/time-scale subscription hooks in overlay interop.
  Rationale: Preserves compatibility across optional chart API surfaces and avoids compile infer warnings in ClojureScript.
  Date/Author: 2026-02-16 / Codex
- Decision: Render overlay rows sorted by descending price.
  Rationale: Ensures deterministic row layering/selection order and keeps highest-price overlays visually consistent at the top.
  Date/Author: 2026-02-16 / Codex

## Outcomes & Retrospective

Implementation completed end-to-end for this scope. Active-asset open orders are now derived from canonical normalized order sources, rendered as chart-local overlays at order prices, visually distinguished by side, and cancellable inline through the existing action/effect path.

The chart integration stayed additive: no public action/effect APIs changed, and chart lifecycle cleanup remains deterministic (`clear-open-order-overlays!` on unmount). New tests validate both projection behavior (asset filtering + dedupe) and overlay behavior (render + cancel callback + cleanup unsubscribe path). All required validation gates passed with zero test failures and zero compile warnings.

## Context and Orientation

The chart surface is rendered from `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`, and JS chart APIs are mediated by `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`. Existing chart runtime mutable concerns are isolated in interop sidecars (for example markers and baseline) and a mount-node runtime sidecar.

Open orders are sourced and normalized in `/hyperopen/src/hyperopen/views/account_info/projections.cljs`. The cancel flow is action-driven: UI emits `:actions/cancel-order`, which resolves into `:effects/api-cancel-order` through `/hyperopen/src/hyperopen/order/actions.cljs`, then executes via `/hyperopen/src/hyperopen/order/effects.cljs` and `/hyperopen/src/hyperopen/api/trading.cljs`.

In this plan, “overlay” means a chart-local visual layer anchored to order price coordinates; “inline cancel” means user-triggered cancel from that overlay, dispatched through the existing action registry.

## Plan of Work

Milestone 1 adds active-asset open-order derivation and deduplication helpers in account projections so chart and table can consume the same normalized order model. This includes active-asset matching rules robust to namespaced coin forms where possible.

Milestone 2 adds a new chart interop module dedicated to open-order overlays. It manages creation, update, and cleanup of overlay DOM nodes via sidecar state keyed by chart handle. The module maps each order price to screen coordinates with `priceToCoordinate`, draws side-colored horizontal guides, renders side/price/size labels, and adds an inline cancel button.

Milestone 3 wires chart core to compute active-asset open orders from canonical state and pass them to chart interop during mount/update/unmount lifecycle. The chart cancel affordance dispatches `:actions/cancel-order` via the existing runtime dispatch path.

Milestone 4 adds tests that prove (a) active-asset filtering/dedupe behavior and (b) overlay rendering and cancel callback behavior, then runs required validation gates.

## Concrete Steps

From `/hyperopen`:

1. Update order projection utilities:
   - `/hyperopen/src/hyperopen/views/account_info/projections.cljs`
2. Add chart overlay interop module and wire facade:
   - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs` (new)
   - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`
3. Wire chart core lifecycle + open-order derivation:
   - `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`
4. Add/extend tests:
   - `/hyperopen/test/hyperopen/views/account_info/projections_test.cljs` (new)
   - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`
5. Run validation:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

Expected transcript shape:

  - `npm run check`: completes lint + compile with no failures.
  - `npm test`: test run completes with no failures.
  - `npm run test:websocket`: websocket suite completes with no failures.

## Validation and Acceptance

Acceptance is met when:

- With an active asset selected and matching open orders present in state, the chart displays one overlay per active-asset open order at the corresponding order price.
- Overlays visually distinguish side (`Buy` vs `Sell`) and include explicit side text (not color-only meaning).
- Overlay includes a clickable inline cancel affordance (`X`) with keyboard-focusable semantics.
- Clicking the overlay cancel affordance dispatches the existing cancel action path and does not bypass order cancellation guardrails.
- Overlay lifecycle is deterministic: created on mount/update as needed and fully cleaned up on unmount.
- Tests cover active-asset filtering/dedupe and overlay cancel callback behavior.
- Required repository validation gates pass.

## Idempotence and Recovery

The implementation is additive and idempotent. Re-running the sync/update path replaces overlay children based on current inputs. If overlay rendering regresses, rollback can be done by removing the new interop calls from chart core while leaving existing chart rendering intact. No schema/data migrations or destructive operations are involved.

## Artifacts and Notes

Planned changed files:

- `/hyperopen/src/hyperopen/views/account_info/projections.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs` (new)
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`
- `/hyperopen/test/hyperopen/views/account_info/projections_test.cljs` (new)
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`
- `/hyperopen/docs/exec-plans/completed/2026-02-16-chart-open-order-overlays-inline-cancel.md`

## Interfaces and Dependencies

At completion, these interfaces should exist:

- `hyperopen.views.account-info.projections/normalized-open-orders-for-active-asset`
- `hyperopen.views.account-info.projections/open-order-for-active-asset?`
- `hyperopen.views.trading-chart.utils.chart-interop/sync-open-order-overlays!`
- `hyperopen.views.trading-chart.utils.chart-interop/clear-open-order-overlays!`

Overlay interop dependency assumptions:

- Lightweight Charts main series supports `priceToCoordinate`.
- Chart/time-scale APIs support subscription methods used for repaint synchronization where available.
- Runtime dispatch remains available through Replicant dispatch binding for non-Hiccup event sources.

Plan revision note: 2026-02-16 23:50Z - Initial plan created for chart open-order overlays and inline cancel implementation.
Plan revision note: 2026-02-16 23:59Z - Updated after completing implementation, tests, and required validation gates.
