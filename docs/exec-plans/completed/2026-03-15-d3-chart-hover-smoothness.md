# D3 Performance Charts: Smooth Hover and Crosshair Interaction on `/portfolio` and Vault Detail

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Related `bd` issue: `hyperopen-yns5` ("Optimize D3 performance chart hover smoothness").

## Purpose / Big Picture

The Portfolio and Vault detail charts already render through the shared D3 runtime, but the hover experience still feels less smooth than it should. Users can see the vertical marker trail the cursor on the wider Vault chart and can feel extra work happening while moving the mouse back and forth over the plot area. After this change, the hover interaction will feel continuous and lighter: the crosshair line will follow the pointer smoothly, tooltip updates will avoid unnecessary work, and the runtime will stop doing layout reads and tooltip rebuilds on every pointer event.

A user can verify this by opening `/portfolio` and a Vault detail route, moving the pointer quickly across each chart, and observing that the vertical line tracks the cursor without stepping lag while the tooltip still shows the nearest exact data point value. The same chart shell, line styling, tooltip styling, benchmark rows, and value formatting must remain intact.

## Progress

- [x] (2026-03-15 20:07Z) Checked repo policy docs, created and claimed `bd` issue `hyperopen-yns5`, and confirmed the working tree was clean before changes.
- [x] (2026-03-15 20:10Z) Audited the current D3 runtime hot path and confirmed the likely sources of jank: snapped crosshair positioning, layout reads on each pointer move, no frame batching, and tooltip row rebuilds.
- [x] (2026-03-15 20:13Z) Authored this active ExecPlan.
- [x] (2026-03-15 20:15Z) Implemented the D3 runtime hover-loop optimization pass: frame-batched pointer handling, continuous crosshair positioning, synchronous non-browser fallback, pointer-left caching, precomputed tooltip models, and keyed tooltip row reuse.
- [x] (2026-03-15 20:16Z) Updated D3 helper/runtime tests to cover frame batching, same-index row reuse, and continuous tooltip positioning semantics.
- [x] (2026-03-15 20:16Z) Ran `npm run check`, `npm test`, and `npm run test:websocket` successfully on the final code state.
- [ ] Close `hyperopen-yns5`, move this ExecPlan to `/hyperopen/docs/exec-plans/completed/`, and summarize the result.

## Surprises & Discoveries

- Observation: The current D3 runtime already removed store writes from pointer motion, but it still performs expensive hot-path work locally.
  Evidence: `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` `handle-pointer-move!` measures the host on every move, `show-hover!` rebuilds tooltip content on every move, and `update-tooltip-rows!` clears and recreates benchmark rows.

- Observation: The "lagging vertical line" complaint is partly semantic, not only frame-rate related.
  Evidence: `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` positions the hover line from `(:x-ratio hovered-point)`, so the line snaps to the nearest data point instead of the pointer position. The effect is more visible on the wider Vault chart because the pixel distance between plotted points is larger.

- Observation: Full-suite view tests expect hover work to happen synchronously in non-browser test environments.
  Evidence: The first `npm test` run failed in Portfolio and Vault D3 tooltip assertions because `requestAnimationFrame` was absent under Node and the initial fallback used `setTimeout`, delaying hover DOM updates past the assertions. Switching the no-RAF fallback to synchronous callback execution restored deterministic tests without affecting browser behavior.

## Decision Log

- Decision: Keep plotted values exact and snap only the tooltip content to the nearest data point, while moving the visible crosshair line continuously with the pointer.
  Rationale: This removes the perceived "cursor lag" without inventing interpolated values that were not actually sampled.
  Date/Author: 2026-03-15 / Codex

- Decision: Optimize within the existing shared SVG + D3 runtime first instead of moving to canvas immediately.
  Rationale: The current bottleneck is the hover loop and DOM update strategy, not the basic ability of SVG to render the small number of chart series we have. This path is lower-risk and preserves existing styling and test contracts.
  Date/Author: 2026-03-15 / Codex

- Decision: Batch hover work through `requestAnimationFrame` and split "pointer position changed" from "hovered data index changed".
  Rationale: The crosshair needs frequent visual updates, while tooltip text and benchmark values only need to change when the nearest sampled point changes.
  Date/Author: 2026-03-15 / Codex

- Decision: Precompute tooltip models on chart-spec updates instead of formatting tooltip payloads during hover movement.
  Rationale: Spec changes are relatively infrequent, while pointer movement is frequent. Moving timestamp/value/benchmark formatting out of the hover loop removes more hot-path work and makes same-index movement essentially positional only.
  Date/Author: 2026-03-15 / Codex

- Decision: Reuse keyed benchmark tooltip row nodes rather than clearing and recreating the row container for every update.
  Rationale: Vault and Portfolio both render a small but variable number of benchmark rows. Reusing nodes preserves styling and data-role contracts while avoiding avoidable DOM churn when benchmark values update.
  Date/Author: 2026-03-15 / Codex

- Decision: Make the no-`requestAnimationFrame` fallback synchronous instead of timer-based.
  Rationale: Tests and non-browser runtimes need deterministic immediate behavior; the browser path still uses native frame scheduling where it exists.
  Date/Author: 2026-03-15 / Codex

## Outcomes & Retrospective

Implemented and validated end to end. The shared D3 runtime now keeps pointer movement local and lighter by storing the latest pointer x, scheduling at most one hover update per animation frame in browsers, moving the visible crosshair continuously with the pointer, precomputing tooltip models on spec updates, and reusing benchmark tooltip row nodes instead of rebuilding them.

The user-visible result should be most noticeable on the wider Vault chart: the vertical line no longer visually trails the pointer due to sample snapping, and same-index pointer movement no longer pays the cost of rebuilding tooltip content. The change did not alter chart geometry, benchmark fetch behavior, tooltip copy, or line/fill styling. Overall complexity increased slightly inside the shared D3 runtime because it now owns a small local scheduler and cache, but it reduced operational complexity in the hover hot path by separating fast positional updates from slower content updates. That tradeoff is justified because the new state is fully contained inside one shared runtime module and backed by runtime tests.

## Context and Orientation

The current shared D3 chart runtime lives in `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs`. It is mounted from `/hyperopen/src/hyperopen/views/portfolio_view.cljs` for `/portfolio` and from `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs` for the Vault detail chart. Both views pass a chart specification into the same `:replicant/on-render` hook, so runtime changes here affect both surfaces.

The runtime currently follows this flow. `pointermove` calls `handle-pointer-move!`, which reads the host bounds, computes the nearest hover index, and immediately calls `show-hover!`. `show-hover!` calls the supplied tooltip builder, rewrites tooltip text and classes, recreates benchmark rows, and positions the hover line using the selected point's `x-ratio`. This means the runtime performs more work than needed for every move and makes the vertical line appear to lag behind the actual pointer when data points are spaced widely.

Pure helper functions for hover index, tooltip layout, point-to-pixel mapping, and area semantics live in `/hyperopen/src/hyperopen/views/chart/d3/model.cljs`. Tooltip content is still built from the existing Portfolio and Vault tooltip helpers in `/hyperopen/src/hyperopen/views/portfolio/vm/chart_tooltip.cljs`, `/hyperopen/src/hyperopen/views/vaults/detail/chart_tooltip.cljs`, and `/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs`. Tests for the runtime and helpers live in `/hyperopen/test/hyperopen/views/chart/d3/runtime_test.cljs` and `/hyperopen/test/hyperopen/views/chart/d3/model_test.cljs`.

This plan does not change chart math, benchmark fetch behavior, or the meaning of hovered values. It only changes how the runtime reacts to pointer movement after the chart specification has already been derived.

## Plan of Work

Milestone 1 rewrites the hot hover loop in `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs`. The runtime will store the latest pointer x-position, schedule at most one hover update per animation frame, and keep a cached host width/height from `ResizeObserver` rather than measuring layout on each move. The visible crosshair line will follow the continuous pointer x-position. The hovered data index will still be computed from that x-position, but tooltip content updates will only happen when the hovered index changes or when the chart specification changes.

Milestone 2 minimizes tooltip work. The runtime will stop clearing and recreating benchmark rows on every frame. Instead, it will either diff keyed benchmark rows or precompute/carry stable tooltip models per hover index so that per-frame updates only touch what changed. The tooltip should continue to render the same timestamp, metric label, metric value, benchmark labels, colors, and benchmark values as before.

Milestone 3 hardens tests. Runtime tests must prove that pointer movement is frame-batched, that the crosshair can move continuously while the hovered index stays stable, and that tooltip rows are not recreated or recomputed unnecessarily for same-index movement. Portfolio and Vault view tests should continue asserting the D3 host contract and any hover-specific data-role expectations that remain valid.

Milestone 4 validates behavior with the required gates and a manual acceptance pass on `/portfolio` and Vault detail. When the work is complete, this plan will be updated, moved to `/hyperopen/docs/exec-plans/completed/`, and the `bd` issue will be closed.

## Concrete Steps

1. Update `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` to introduce a frame-batched hover scheduler, continuous pointer tracking, and index-change gating for tooltip updates.
2. Update `/hyperopen/src/hyperopen/views/chart/d3/model.cljs` if helper functions are needed for pointer-to-pixel mapping or continuous line positioning.
3. Keep the view-layer chart specs in `/hyperopen/src/hyperopen/views/portfolio_view.cljs` and `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs` stable unless the runtime needs additional theme/spec fields for the hover optimization.
4. Extend runtime/helper tests in:

   `/hyperopen/test/hyperopen/views/chart/d3/runtime_test.cljs`
   `/hyperopen/test/hyperopen/views/chart/d3/model_test.cljs`
   `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`
   `/hyperopen/test/hyperopen/views/vaults/detail/chart_view_test.cljs`

5. Run the required commands from the repo root `/Users/barry/.codex/worktrees/6f74/hyperopen`:

   npm run check
   npm test
   npm run test:websocket

## Validation and Acceptance

The work is accepted when all of the following are true:

1. On `/portfolio` and Vault detail, the vertical crosshair line follows the pointer smoothly instead of visibly stepping behind it on a wide chart.
2. Tooltip values still represent the nearest exact chart sample; no interpolated values are introduced silently.
3. Moving the pointer within the same nearest index does not trigger unnecessary tooltip DOM rebuilds.
4. The runtime no longer depends on a layout measurement on every pointer move.
5. Automated tests cover the new hover-loop behavior and the required gates pass:

   npm run check
   npm test
   npm run test:websocket

## Idempotence and Recovery

These changes are source-only and safe to reapply. If the frame-batched hover path regresses a chart surface, recovery is to preserve the D3 renderer and revert only the hover loop back to the prior immediate-update behavior while keeping new tests to isolate the failing assumption. The plan intentionally keeps the public chart specification stable so rollback is confined to the shared runtime.

## Artifacts and Notes

Expected validation commands will be run from:

  /Users/barry/.codex/worktrees/6f74/hyperopen

Observed pre-implementation evidence:

  - `runtime.cljs` currently updates the hover line from the hovered point position, not the pointer position.
  - `runtime.cljs` currently rebuilds tooltip benchmark rows inside the pointer-move path.
  - Vault detail exposes the issue more clearly because the plot area is wider.

Final validation evidence:

  - `npm run check` (pass)
  - `npm test` (pass)
  - `npm run test:websocket` (pass)

## Interfaces and Dependencies

The shared renderer remains the same public interface: Portfolio and Vault pass a chart specification with `:surface`, `:axis-kind`, `:time-range`, `:points`, `:series`, `:y-ticks`, `:theme`, and `:build-tooltip`.

After this plan is complete, the runtime in `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` must additionally maintain internal mutable state for:

- the latest pointer x-position in local plot coordinates,
- the latest hovered index,
- any scheduled animation-frame handle,
- cached tooltip benchmark row nodes or precomputed tooltip models,
- cached chart size and any cached left-offset information required to derive local x without forcing layout each move.

No new third-party dependencies are required. This remains an SVG-backed D3 runtime with HTML tooltip overlays.

Plan revision note: 2026-03-15 20:13Z - Initial plan authored for `hyperopen-yns5` after auditing the D3 hover path and identifying continuous crosshair motion, frame batching, and tooltip diffing as the main optimization targets.
Plan revision note: 2026-03-15 20:16Z - Updated the plan after implementation and final validation to record the runtime scheduler/cache approach, the synchronous no-RAF fallback, and the successful gate results.
