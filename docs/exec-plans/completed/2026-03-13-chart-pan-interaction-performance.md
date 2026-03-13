# Reduce Trading Chart Pan Interaction Work

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

Dragging or panning the trading chart currently feels acceptable to a human, but Chrome Performance still attributes repeated pointer interactions to the chart surface while the viewport is moving. The goal of this plan is to remove avoidable app-owned work from that drag path so the chart pan flow more closely resembles Hyperliquid’s lower-overhead behavior.

After this change, a contributor should be able to pan the trade chart and know that Hyperopen no longer performs unnecessary visible-range bookkeeping on every range tick and no longer rebuilds open-order overlay DOM for every pan repaint. The result should be visible in targeted tests and in a cleaner Performance trace during chart drag.

## Progress

- [x] (2026-03-13 14:18Z) Re-read `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/FRONTEND.md`, and `/hyperopen/docs/RELIABILITY.md` before implementation.
- [x] (2026-03-13 14:20Z) Audited chart pan listeners in `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` and interop modules for visible-range persistence, navigation overlay, open-order overlays, position overlays, volume overlay, and baseline range subscriptions.
- [x] (2026-03-13 14:23Z) Created `bd` issue `hyperopen-8uzx` (`Reduce trading chart pan interaction work`) and claimed it.
- [x] (2026-03-13 14:26Z) Authored this active ExecPlan.
- [x] (2026-03-13 14:32Z) Reduced visible-range interaction bookkeeping so a drag burst marks interaction once per debounce window while preserving debounced persistence flushes.
- [x] (2026-03-13 14:35Z) Refactored `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs` so repaint subscriptions patch retained row DOM instead of clearing and rebuilding the overlay root on every chart movement.
- [x] (2026-03-13 14:35Z) Removed open-order overlay repaint subscriptions that did not affect rendered output during pan or hover (`subscribeCrosshairMove` and `subscribeClick`).
- [x] (2026-03-13 14:37Z) Extended targeted tests in `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays_test.cljs` and `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence_test.cljs`.
- [x] (2026-03-13 14:39Z) Restored missing `node_modules` with `npm ci`, then ran `npm run check`, `npm test`, and `npm run test:websocket` successfully.
- [x] (2026-03-13 14:39Z) Created follow-up `bd` issue `hyperopen-v48a` for live Chrome re-profiling after this code change.

## Surprises & Discoveries

- Observation: The chart pan path is not limited to the Lightweight Charts canvas. Hyperopen also hooks visible-range subscriptions in persistence, position overlays, open-order overlays, the volume indicator overlay, and baseline mode.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/*.cljs` shows repeated `subscribeVisibleLogicalRangeChange` or `subscribeVisibleTimeRangeChange` registrations.

- Observation: Open-order overlays still use a root-level `clear-children!` rebuild path for `render-overlays!`, unlike the newer position overlay path that already patches retained DOM.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs` `render-overlays!` clears the root and appends fresh rows for every repaint.

- Observation: Visible-range persistence already debounces storage writes, but it still invokes the interaction callback on every visible-range event before that debounce collapses the write stream.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs` `logical-handler` and `time-handler` call `on-visible-range-change!` before `queue-persist!`.

- Observation: The navigation overlay itself creates SVG buttons, so some Chrome interaction rows targeting `svg` can be our own overlay controls rather than the chart canvas.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay.cljs` constructs SVG icons with `createElementNS`.

- Observation: The local validation environment initially had an empty `node_modules`, which caused both `npm test` and `npm run check` to fail before any repository code executed.
  Evidence: `npm test` initially failed with `sh: shadow-cljs: command not found`, and `npm run check` initially reported missing npm dependency `@noble/secp256k1` under `/hyperopen/node_modules` until `npm ci` restored the lockfile-resolved packages.

- Observation: The retained-row approach works cleanly for open-order overlays because pan changes the row container `top` position while the badge-relative offset can stay constant.
  Evidence: The new repaint regression test needed to assert retained row identity plus changed row `top`, not changed badge-relative `top`, because the badge moves with the row during a pure viewport shift.

## Decision Log

- Decision: Scope the first pass to two hotspots: visible-range interaction bookkeeping and open-order overlay repaint churn.
  Rationale: Those are directly on the pan path, clearly app-owned, and can be reduced without changing chart semantics or touching broader chart infrastructure.
  Date/Author: 2026-03-13 / Codex

- Decision: Keep position overlays, baseline subscriptions, and volume overlay subscriptions intact for this pass unless testing shows a safe no-op reduction.
  Rationale: Those paths either already use retained DOM patching or appear to provide behavior tied to viewport movement. Open-order overlays are the most obvious remaining full-rebuild offender.
  Date/Author: 2026-03-13 / Codex

- Decision: Preserve the existing visible-range restore guard semantics while making interaction marking coarser.
  Rationale: The restore guard still needs to cancel async restore when the user begins interacting, but it does not need to mutate sidecar state on every single pan tick.
  Date/Author: 2026-03-13 / Codex

- Decision: Keep live browser Performance re-profiling as a separate follow-up issue instead of blocking this implementation closeout on an in-session manual trace capture.
  Rationale: The repository-required validation gates are deterministic and now pass, while Chrome Performance verification depends on a running app session and manual trace capture. The code change is complete, and the remaining measurement work is tracked in `hyperopen-v48a`.
  Date/Author: 2026-03-13 / Codex

## Outcomes & Retrospective

Implementation is complete for the scoped first pass.

The trading chart now performs less app-owned work during pan in two concrete ways. First, visible-range persistence coalesces interaction marking to one callback per debounce window instead of mutating sidecar state on every visible-range tick. Second, open-order overlays now retain row DOM and patch in place during repaint subscriptions instead of clearing and rebuilding the overlay root on every movement. The overlay also no longer repaints on crosshair and click subscriptions that were not used by its rendering path.

This reduced hot-path complexity. Before the change, the open-order overlay path mixed repaint subscription ownership with full DOM teardown/rebuild work. After the change, it follows the same retained-node direction already used by position overlays, which narrows repaint cost and makes the overlay ownership model more coherent across chart surfaces.

Validation passed through the required repository gates:

- `npm run check`
- `npm test`
- `npm run test:websocket`

The remaining gap is live browser Performance re-profiling. That follow-up is tracked separately in `hyperopen-v48a`.

## Context and Orientation

The trading chart mounts in `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`. That file owns chart lifecycle and wires runtime callbacks into the interop facade in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`.

In this repository, “visible range” means the time-scale viewport currently shown by the chart. Hyperopen persists that viewport by asset and timeframe in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs`. During chart mount, `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` also stores a per-node `:visible-range-interaction-epoch` in `/hyperopen/src/hyperopen/views/trading_chart/runtime_state.cljs`; that epoch prevents an async persisted-range restore from overriding a user-initiated chart interaction.

The trading chart also renders DOM overlays on top of the chart canvas. Open-order overlays live in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs`. Each overlay row displays order text, a price badge, and an inline cancel button positioned from chart coordinates. Right now, `render-overlays!` clears the entire root and rebuilds every visible row on repaint, even when only the row coordinates changed during pan.

Position overlays in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs` are the better reference implementation for this work. That file already retains DOM nodes and patches values in place instead of rebuilding the whole overlay subtree on every repaint.

Relevant tests already exist:

- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays_test.cljs`
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence_test.cljs`

These tests use fake DOM helpers under `/hyperopen/test/hyperopen/views/trading_chart/test_support/fake_dom.cljs`.

## Plan of Work

### Milestone 1: Coalesce visible-range interaction marking

Update `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs` so the injected `on-visible-range-change!` callback fires once when a burst of visible-range changes begins and stays quiet until the pending debounced persist flushes. This keeps the restore guard correct because the first user movement still marks interaction immediately, while eliminating repeated WeakMap writes during a single pan gesture.

Keep the persistence write debounce behavior unchanged and preserve the existing public function surface:

- `hyperopen.views.trading-chart.utils.chart-interop/subscribe-visible-range-persistence!`
- `hyperopen.views.trading-chart.utils.chart-interop.visible-range-persistence/subscribe-visible-range-persistence!`

### Milestone 2: Convert open-order overlays to retained DOM patching

Refactor `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs` so overlay row DOM is keyed by order identity and reused across repaint subscriptions. Repaint should update style/text on existing rows, create rows only for newly visible orders, and remove rows only when an order disappears or is no longer visible. The overlay root itself should remain mounted.

The retained row cache should hold enough references to patch:

- the row wrapper;
- the line node;
- the badge node and any chip subnodes;
- the inline cancel button;
- any text-bearing nodes used by the row label or badge text.

Do not change the user-facing label content or inline cancel behavior.

### Milestone 3: Trim unnecessary open-order repaint subscriptions

Audit the open-order overlay subscription set in the same file. Keep the range and size subscriptions that are required to reposition rows while panning or resizing. Remove crosshair or click subscriptions if they are not consumed by open-order rendering, because they currently add extra repaint entry points without changing output.

### Milestone 4: Add regression coverage and validate

Extend the visible-range persistence tests to assert that repeated range callbacks within one debounce window only notify interaction once and still flush the final range. Extend the open-order overlay tests to assert retained DOM identity across repaints and updated positions/text after a repaint trigger.

After the code changes land, run the required repository gates:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Then update this ExecPlan, close `hyperopen-8uzx`, and summarize the measurable outcome. Browser trace re-profiling, if not completed in the same session, should move to a follow-up `bd` task rather than leaving this implementation artifact stale.

## Concrete Steps

From repository root `/hyperopen`:

1. Update `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs`.
   Add burst-level interaction gating around `on-visible-range-change!` while preserving debounced persistence.

2. Refactor `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs`.
   Introduce retained row ownership, patch functions, and minimal reconciliation for visible rows.

3. Update tests:
   - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence_test.cljs`
   - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays_test.cljs`

4. Run validation:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

5. Update this plan’s living sections and close `hyperopen-8uzx` when the work is complete.

## Validation and Acceptance

Acceptance is satisfied when all of the following are true:

1. A pan burst only marks visible-range interaction once before the debounced persistence flush, and the final viewport still persists correctly.
2. Open-order overlay repaint no longer clears and rebuilds the overlay root for ordinary pan-driven range updates.
3. Open-order rows retain identity across repaint triggers when the underlying order set is unchanged.
4. Inline cancel behavior still fires exactly once for a pointer-first cancel interaction.
5. Required gates pass: `npm run check`, `npm test`, and `npm run test:websocket`.

## Idempotence and Recovery

This work is safe to repeat because it changes only chart-side DOM ownership and callback coalescing. If the retained-row overlay patch introduces a rendering regression, recovery is to revert only the open-order overlay refactor while keeping the visible-range coalescing change. If the visible-range coalescing change interferes with persisted-range restore behavior, recovery is to restore the old callback cadence and keep the overlay refactor isolated.

No destructive storage migration or remote sync is involved.

## Artifacts and Notes

Primary implementation files:

- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs`
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence_test.cljs`
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays_test.cljs`

Related reference files:

- `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/runtime_state.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs`
- `/hyperopen/docs/exec-plans/completed/2026-03-06-position-overlay-live-drag-validation-and-text-node-patching.md`

`bd` tracking for this work:

- `hyperopen-8uzx` — Reduce trading chart pan interaction work
- `hyperopen-v48a` — Re-profile chart pan interactions after overlay optimization

## Interfaces and Dependencies

The work must preserve the existing public chart interop entry points:

- `hyperopen.views.trading-chart.utils.chart-interop/subscribe-visible-range-persistence!`
- `hyperopen.views.trading-chart.utils.chart-interop/sync-open-order-overlays!`
- `hyperopen.views.trading-chart.utils.chart-interop/clear-open-order-overlays!`

The implementation must continue to respect frontend responsiveness policy from `/hyperopen/docs/FRONTEND.md`: user-visible chart state should not become less deterministic, and interaction work should stay minimal. The implementation must also preserve deterministic runtime behavior from `/hyperopen/docs/RELIABILITY.md`: chart-side callbacks should remain pure UI/infrastructure work and should not add duplicate side effects.

Revision note: 2026-03-13. Created this ExecPlan during implementation after profiling the chart pan path and identifying two concrete app-owned hotspots: per-tick visible-range interaction marking and full-rebuild open-order overlay repainting.
Revision note: 2026-03-13 14:39Z. Updated living sections after implementing the retained open-order overlay patch, visible-range interaction coalescing, restoring `node_modules` with `npm ci`, passing required gates, and filing `hyperopen-v48a` for live browser re-profiling.
