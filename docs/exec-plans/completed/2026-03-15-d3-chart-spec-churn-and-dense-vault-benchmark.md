# Reduce D3 Chart Spec Churn and Add Dense Vault Benchmark Coverage

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Related `bd` issue: `hyperopen-zy9l` ("Reduce D3 chart spec churn and add dense vault benchmark scene").

## Purpose / Big Picture

The shared D3 runtime now has a much smoother hover loop, but the chart stack still does extra work whenever the parent views rerender. The Portfolio and Vault chart-model builders still produce legacy SVG path strings that the D3 renderer does not use, and the D3 host currently rerenders on every Replicant update because the chart spec contains a fresh tooltip closure each time. The current Vault workbench scene is also too sparse to stress the route the way real histories do.

After this change, the D3 runtime will be able to skip no-op updates using a stable spec key, the Portfolio and Vault chart-model layers will stop building legacy SVG path data when D3 is the active renderer, and the workbench will include a dense Vault returns-with-benchmarks scene that can reproduce realistic hover load in Chromium.

## Progress

- [x] (2026-03-15 21:19Z) Created and claimed `bd` issue `hyperopen-zy9l` for the chart-spec churn and dense Vault benchmark pass.
- [x] (2026-03-15 21:22Z) Audited the current runtime and VM pipeline. Confirmed that `update-runtime!` always rebuilds tooltip models and rerenders the SVG, Portfolio and Vault still compute legacy SVG path strings in their chart-model builders, and the existing Vault workbench scenes only contain six points per series.
- [x] (2026-03-15 21:25Z) Authored this active ExecPlan.
- [x] (2026-03-15 21:28Z) Added stable `:update-key` support to the shared D3 runtime and passed stable spec keys from the Portfolio and Vault D3 spec builders so no-op Replicant updates can bail out before rebuilding tooltip models or rerendering the SVG.
- [x] (2026-03-15 21:29Z) Added `:include-svg-paths?` control to the Portfolio and Vault chart-model builders and wired the top-level view models to skip legacy SVG path and area generation when the D3 renderer is active, while preserving the fallback `:svg` path.
- [x] (2026-03-15 21:35Z) Added a dense Vault returns-with-benchmarks workbench scene with 240 deterministic samples per series and benchmark overlays, then validated the scene in Chromium.
- [x] (2026-03-15 21:36Z) Ran `npm run check`, `npm test`, and `npm run test:websocket` successfully on the final code state.

## Surprises & Discoveries

- Observation: The D3 runtime still fully rerenders on every Replicant update, even when the meaningful chart inputs did not change.
  Evidence: `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` `update-runtime!` always resets tooltip models and calls `render-runtime!`, and the Portfolio/Vault spec builders in `/hyperopen/src/hyperopen/views/portfolio_view.cljs` and `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs` always create a fresh `:build-tooltip` closure.

- Observation: Portfolio and Vault chart-model builders still do legacy SVG string work that the D3 renderer ignores.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs` computes `:path` via `vm-chart-math/chart-line-path`, and `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs` computes `:path` and `:area-path` even though D3 mode renders directly from `:points`.

- Observation: The current Vault workbench scene is too sparse to be a strong proxy for route-level chart lag.
  Evidence: `/hyperopen/portfolio/hyperopen/workbench/scenes/vaults/detail_chart_scenes.cljs` defines only six points each for strategy and benchmark series.

- Observation: The new dense Vault workbench scene does not reproduce a density-driven hover regression relative to the sparse Vault scene.
  Evidence: Chromium browser-inspection sweeps on `returns-with-benchmarks` and `dense-returns-with-benchmarks` both settled at roughly one frame per move with median hover settle times of `16.6-16.7ms`, `p95` under `18ms`, near-zero crosshair error, and clean tooltip hide-on-leave behavior.

## Decision Log

- Decision: Use a stable `:update-key` inside the chart spec instead of trying to compare the whole spec structurally inside the runtime.
  Rationale: The spec contains a fresh tooltip builder function every render, so whole-spec equality is unstable. A dedicated key makes the runtime bailout explicit and cheap to reason about.
  Date/Author: 2026-03-15 / Codex

- Decision: Keep the fallback SVG path generation behind an explicit `:include-svg-paths?` option in the chart-model builders rather than deleting it in this issue.
  Rationale: The renderer config still supports `:svg`, so the optimization must preserve fallback behavior while skipping the unused work in the default D3 path.
  Date/Author: 2026-03-15 / Codex

- Decision: Implement the third high-leverage item as a dense Vault workbench scene rather than profiling the live route in this issue.
  Rationale: The workbench route is deterministic and already wired into browser inspection. Adding a dense scene raises fidelity without mixing this change with route bootstrapping or live data dependencies.
  Date/Author: 2026-03-15 / Codex

## Outcomes & Retrospective

The final result reduced unnecessary chart work in the expected places without changing page behavior. The D3 runtime now has an explicit `:update-key` contract, so unrelated parent rerenders no longer force tooltip-model rebuilds or SVG rerenders. The Portfolio and Vault view-model layers also stop building legacy SVG strings when D3 is active, which removes path-generation work that the runtime never consumed.

The dense Vault workbench scene succeeded as a profiling target, but it did not expose a chart-density-specific regression. In Chromium, the sparse Vault returns scene measured `sameDispatchMs ~= 1.2`, `sameSettleMs ~= 14.1`, `medianSettleMs ~= 16.6`, `p95 ~= 17.9`, and near-zero crosshair error. The dense Vault scene measured `sameDispatchMs ~= 0.8`, `sameSettleMs ~= 16.0`, `medianSettleMs ~= 16.7`, `p95 ~= 17.7`, and the same near-zero crosshair error. That result suggests any remaining jank felt on the live Vault route is more likely to come from route-level state churn, page layout/compositing, or surrounding subscriptions than from the shared D3 hover runtime itself.

## Context and Orientation

The shared D3 runtime is `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs`. Its `on-render` hook mounts the SVG and tooltip DOM, and `update-runtime!` is the place where Replicant updates currently force a full runtime rerender. Any no-op bailout belongs there.

Portfolio chart specs are built in `/hyperopen/src/hyperopen/views/portfolio_view.cljs`, but the underlying chart-model comes from `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`, which currently computes normalized points and a legacy SVG `:path` string for each series. Vault chart specs are built in `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs`, and their chart-model comes from `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`, which computes line and area path strings for the legacy SVG renderer.

Renderer mode lives in `/hyperopen/src/hyperopen/views/chart/renderer.cljs`. The config defaults to D3 for both Portfolio and Vault today, but the codebase still keeps a `:svg` fallback path. That means this change must skip path work only when D3 is active.

Workbench coverage for Vault charts lives in `/hyperopen/portfolio/hyperopen/workbench/scenes/vaults/detail_chart_scenes.cljs`. The existing scenes use tiny fixed vectors; a dense scene belongs here so Chromium benchmarks can stress a more realistic sample count and benchmark overlay set.

## Plan of Work

Milestone 1 adds stable D3 update keys. Extend the Portfolio and Vault D3 spec builders so each spec carries a stable `:update-key` made only from render-relevant values: surface, axis kind, time range, points, series, y-ticks, and theme. Then update `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` so `update-runtime!` compares the incoming `:update-key` with the previously mounted one and returns early when nothing meaningful changed. The runtime must still rerender on mount, on true spec changes, and on `ResizeObserver` notifications.

Milestone 2 removes fallback-only chart-model work from D3 mode. Update the Portfolio and Vault chart-model builders so they accept an explicit `:include-svg-paths?` option, defaulting to true for existing tests and fallback use. The top-level Portfolio and Vault view-model builders should pass `false` when the renderer is D3. In D3 mode, the model must still produce points, series, hover metadata, and y-ticks, but it should skip `chart-line-path`, `line-path`, and `area-path` generation.

Milestone 3 raises benchmark fidelity for Vault. Add a dense returns-with-benchmarks workbench scene under `/hyperopen/portfolio/hyperopen/workbench/scenes/vaults/detail_chart_scenes.cljs`, generating many deterministic points for the strategy and benchmark series. Validate in Chromium that the dense scene renders and can be driven by the browser-inspection harness without errors.

Milestone 4 validates and closes out. Update tests to cover the new runtime bailout and D3-mode chart-model behavior, run the required gates, record the dense-scene browser result in this plan, then close `hyperopen-zy9l` and move this file to `/hyperopen/docs/exec-plans/completed/`.

## Concrete Steps

1. Update the D3 spec builders in:

   `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
   `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs`

   so they emit a stable `:update-key`.

2. Patch `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` so `update-runtime!` bails out when `:update-key` is unchanged.

3. Patch the chart-model builders in:

   `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`
   `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`
   `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
   `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`

   so D3 mode skips legacy SVG path work while `:svg` fallback keeps it.

4. Add or update tests in:

   `/hyperopen/test/hyperopen/views/chart/d3/runtime_test.cljs`
   `/hyperopen/test/hyperopen/views/portfolio/vm/chart_helpers_test.cljs`
   `/hyperopen/test/hyperopen/views/vaults/detail/chart_test.cljs`

5. Extend `/hyperopen/portfolio/hyperopen/workbench/scenes/vaults/detail_chart_scenes.cljs` with a dense returns benchmark scene, then verify it in Chromium.

6. Run from the repo root `/hyperopen`:

   `npm run check`
   `npm test`
   `npm run test:websocket`

## Validation and Acceptance

The work is accepted when all of the following are true:

1. Replicant updates that rebuild the same D3 chart spec do not force a runtime rerender or tooltip-model rebuild.
2. Portfolio and Vault chart-model builders skip legacy SVG path generation when D3 is active, but preserve path generation when renderer config is switched to `:svg`.
3. A dense Vault returns-with-benchmarks workbench scene exists, renders successfully, and can be used for browser hover profiling.
4. `npm run check`, `npm test`, and `npm run test:websocket` pass on the final code state.

## Idempotence and Recovery

These changes are source-only and safe to repeat. The `:update-key` bailout is additive; if it causes a stale-render bug, recovery is to remove the early return while keeping the stable key generation for investigation. The chart-model changes are guarded by `:include-svg-paths?`, so rollback can preserve fallback behavior by setting that option back to true in the top-level view models.

## Artifacts and Notes

Pre-implementation evidence:

  - Runtime rerender always happens in `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` `update-runtime!`.
  - Portfolio chart-model path generation always happens in `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`.
  - Vault chart-model line/area path generation always happens in `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`.
  - Existing Vault workbench scenes only cover six points per series.

## Interfaces and Dependencies

The D3 runtime interface remains a chart spec map passed into `chart-d3-runtime/on-render`. After this change, the spec must also carry a stable `:update-key`. That key is internal to the renderer contract and must not change public actions or page behavior.

The Portfolio chart-model and Vault chart-model functions must support a fallback-aware option to control whether SVG path strings are built. Existing callers and tests without that option should continue to behave exactly as before.

Plan revision note: 2026-03-15 21:25Z - Initial plan authored for `hyperopen-zy9l` after auditing the runtime update path, the unused SVG path generation in the VM builders, and the sparse Vault workbench fixtures.
Plan revision note: 2026-03-15 21:36Z - Completed implementation, validation, and Chromium dense-scene benchmarking. Dense Vault hover behavior matched sparse Vault hover behavior, so remaining manual lag likely sits outside the shared chart runtime.
