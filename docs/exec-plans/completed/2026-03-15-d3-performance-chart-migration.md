# D3 Performance Chart Migration for Portfolio and Vaults

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Related issue: `hyperopen-7y15` (closed as completed).

## Purpose / Big Picture

Hyperopen's Portfolio and Vault detail performance charts currently render inline SVG via Hiccup and drive hover state through app-state writes on pointer movement. After this change, both surfaces will use a shared D3-managed runtime for the plot layer so line and area updates, crosshair movement, and tooltip updates stay local to the chart DOM instead of causing app-state churn. The surrounding Hyperopen shell stays intact: tabs, benchmark search, legend, chip rails, and y-axis labels remain in the existing view namespaces.

A user can verify the result by opening `/portfolio` and a Vault detail route, switching between `Returns`, `Account Value`, and `PNL`, adding and removing return benchmarks, and moving the pointer across the chart. The line movement and tooltip should stay visually smooth while tabs, benchmark controls, and number formatting still match the existing product shell.

## Progress

- [x] (2026-03-15 15:59Z) Re-audited Portfolio and Vault chart view, VM, tooltip, and action wiring.
- [x] (2026-03-15 15:59Z) Created and claimed `bd` issue `hyperopen-7y15`.
- [x] (2026-03-15 16:00Z) Authored this active ExecPlan.
- [x] (2026-03-15 16:41Z) Implemented the shared D3 chart runtime, model helpers, and renderer selector config.
- [x] (2026-03-15 16:47Z) Integrated the D3 plot host into `/portfolio` while preserving existing shell controls.
- [x] (2026-03-15 16:49Z) Integrated the same D3 plot host into Vault detail chart rendering, including account-value and PNL area fills.
- [x] (2026-03-15 17:37Z) Added D3 helper/runtime tests, updated Portfolio and Vault view tests for the D3 host contract, and expanded workbench chart scenes.
- [x] (2026-03-15 17:55Z) Ran required validation gates: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-03-15 17:55Z) Captured legacy SVG/hover-plumbing removal as follow-up issue `hyperopen-1lrp` instead of mixing rollback cleanup into the rollout landing.

## Surprises & Discoveries

- Observation: Both Portfolio and Vault charts already expose a nearly identical normalized-series contract (`:series`, `:points`, `:y-ticks`, tooltip helper inputs), which means the migration can keep VM/chart-math logic largely intact and swap only the plot runtime.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs` and `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs` both emit normalized `:points`, `:series`, and hover-oriented point metadata.

- Observation: The current chart geometry is index-based rather than time-distance-based, so adopting the Observable pointer pattern without a semantic layout change requires keeping index spacing for this first D3 cut.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs` computes `:x-ratio` from `(range point-count)` instead of timestamps, and Vault chart math follows the same normalized-ratio approach.

- Observation: Vault chart series structure changes by mode, so keyed D3 joins need to recreate per-series DOM roots when the area mode changes (`:none`, `:solid`, `:split-zero`) instead of only keying on series id.
  Evidence: Switching the same `:strategy` id between returns, account value, and PNL reused an incompatible DOM root until `data-area-type` became part of the join logic in `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs`.

- Observation: The existing fake DOM support did not implement `style.setProperty`, `getAttribute`, or document ownership on created nodes, which blocked lifecycle tests for DOM-owned D3 renderers.
  Evidence: Initial runtime tests failed until `/hyperopen/test/hyperopen/views/trading_chart/test_support/fake_dom.cljs` was extended to emulate those DOM APIs.

## Decision Log

- Decision: Roll out behind a config-controlled renderer selector instead of replacing both SVG charts in one edit.
  Rationale: The new runtime is shared but touches two separate product surfaces. A fallback path reduces regression risk while preserving an easy comparison point during visual and interaction validation.
  Date/Author: 2026-03-15 / Codex

- Decision: Keep pointer hover local to the D3 runtime instead of dispatching app-state writes on every pointer movement.
  Rationale: The user request is explicitly performance-motivated, and the current hover-index actions are the highest-frequency chart interaction path.
  Date/Author: 2026-03-15 / Codex

- Decision: Preserve current normalized/index-based geometry in v1.
  Rationale: Introducing true time-distance scaling during the renderer migration would make it harder to attribute any visible difference to performance work versus chart semantics changes.
  Date/Author: 2026-03-15 / Codex

## Outcomes & Retrospective

Portfolio and Vault performance charts now render through a shared D3 runtime by default via `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs`, with view-owned shells still responsible for tabs, legends, axis labels, and benchmark controls. Hover, tooltip, crosshair, keyed line/area updates, and resize handling are local to the chart DOM instead of driving app-state updates on every pointer move. The work also added focused D3 model/runtime coverage, updated view integration tests to exercise mounted D3 hosts, and added Portfolio/Vault workbench scenes for the new renderer path.

The only deferred piece from the original migration sketch is deletion of the legacy SVG fallback and hover-state plumbing. That cleanup is tracked separately in `bd` issue `hyperopen-1lrp` so the shipped D3 rollout and rollback path stay separable.

Complexity target: reduce overall runtime complexity in the hot hover path by moving per-move updates out of global UI state, even though the migration temporarily increases code complexity by keeping a renderer fallback until both surfaces are validated.

## Context and Orientation

Portfolio chart rendering lives in `/hyperopen/src/hyperopen/views/portfolio_view.cljs`, with chart data prepared in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`. Vault detail chart rendering lives in `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs`, with the data assembled in `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` and `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`.

In this repository, a "plot runtime" means the imperative code that mounts into a DOM node, creates SVG elements, subscribes to pointer and resize events, and updates only the chart-specific DOM that needs to move. Hyperopen already uses this pattern for the trading chart through Replicant life-cycle callbacks in `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`.

Current Portfolio and Vault charts keep most product behavior in pure code already:

- series selection, benchmark inclusion, colors, and normalized points come from the VM/chart model
- y-axis labels and gutter sizing are calculated in the existing view namespaces
- tooltip copy and value formatting are centralized in `/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs` plus surface-specific tooltip helpers

The main behavior to replace is the inline SVG path and hover overlay rendering that currently lives directly in the Hiccup views and depends on hover indexes stored in `:portfolio-ui` and `:vaults-ui`.

## Plan of Work

Milestone 1 adds the shared D3 runtime and the renderer selector. Add `d3` as a dependency, extend `/hyperopen/src/hyperopen/config.cljs` with a `:performance-chart-renderer` selector, and create a shared runtime under `src/hyperopen/views/chart/d3/` that mounts into a host div through `:replicant/on-render`. The runtime will own SVG creation, keyed series joins, area fills, crosshair, local tooltip DOM, and `ResizeObserver` cleanup.

Milestone 2 moves Portfolio to the shared D3 plot host while preserving the existing card shell. The tabs, benchmark selector, legend, chip rail, and y-axis labels remain in `/hyperopen/src/hyperopen/views/portfolio_view.cljs`, but the plot area switches to a D3 host when the config says `:d3`. The legacy SVG renderer remains available when the config says `:svg`.

Milestone 3 moves Vault detail to the same shared D3 plot host with surface-specific theme and fill behavior. Returns stay line-only, account value keeps a solid underfill for the strategy series, and PNL keeps split positive and negative fills around zero.

Milestone 4 removes the legacy hover-state plumbing and SVG plot code once both surfaces use the D3 runtime. This includes deleting the app-state hover-index writes and view-model hover dependencies that only existed for inline SVG hover.

Milestone 5 updates workbench and automated validation. Add focused DOM tests for the runtime, update view tests for the D3 host contract, add Portfolio workbench coverage to match the existing Vault chart scenes, and run the required repository validation gates.

## Concrete Steps

1. Add the new dependency and config selector:

    - update `package.json` and lockfile to include `d3`
    - extend `/hyperopen/src/hyperopen/config.cljs` with a renderer selector map such as `{:portfolio :svg, :vaults :svg}`

2. Create the shared runtime under `src/hyperopen/views/chart/d3/`:

    - one namespace for theme/spec normalization
    - one namespace for mount/update/unmount and DOM ownership
    - optional helper namespaces for tooltip DOM or path/fill math if needed to stay under file-size guidance

3. Wire Portfolio to the runtime:

    - keep selector, legend, and y-axis markup in `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
    - replace only the plot area with a host div using `:replicant/on-render`
    - stop dispatching `:actions/set-portfolio-chart-hover` and `:actions/clear-portfolio-chart-hover` in D3 mode

4. Wire Vault detail to the runtime:

    - keep benchmark selector, legend, and timeframe menu in `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs`
    - use surface-specific theme tokens and fill rules for returns, account value, and PNL
    - stop dispatching Vault chart hover actions in D3 mode

5. Remove legacy SVG and hover-state plumbing after both D3 integrations are stable:

    - default state hover indexes in `/hyperopen/src/hyperopen/state/app_defaults.cljs`
    - action helpers in `/hyperopen/src/hyperopen/portfolio/actions.cljs` and `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs`
    - hover-dependent VM output in the Portfolio and Vault chart view-models
    - inline SVG plot rendering in the Portfolio and Vault chart views

6. Update validation assets:

    - DOM/runtime tests using fake DOM support
    - Portfolio and Vault view tests for host/data-role contract
    - workbench scenes for Portfolio and Vault D3 states

7. Run from repo root:

    npm run check
    npm test
    npm run test:websocket

## Validation and Acceptance

The work is accepted when:

1. `/portfolio` and Vault detail charts render through the shared D3 runtime when their renderer selector is set to `:d3`.
2. Hovering in D3 mode updates crosshair and tooltip without app-state pointer writes.
3. Benchmark add/remove behavior still updates plotted return lines on both surfaces.
4. Vault account-value and PNL fills match the current product behavior.
5. The legacy SVG fallback still works until the final cleanup step, after which the D3 runtime is the only active plot implementation.
6. `npm run check`, `npm test`, and `npm run test:websocket` pass.

## Idempotence and Recovery

The migration is intentionally additive until the cleanup milestone. If the D3 runtime regresses either surface, set that surface back to `:svg` in the renderer config and continue debugging without losing the existing product behavior. Runtime code should clean up listeners and observers on unmount so repeated mount/update cycles in workbench and tests do not leak DOM state.

## Artifacts and Notes

Reference interaction pattern:

- Observable notebook: `https://observablehq.com/@d3/line-with-tooltip/2`
- Observable source: `https://api.observablehq.com/@d3/line-with-tooltip/2.js?v=3`

Key implementation references already in the repo:

- `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` for Replicant mount/update/unmount behavior
- `/hyperopen/test/hyperopen/views/trading_chart/test_support/fake_dom.cljs` for DOM-oriented tests

## Interfaces and Dependencies

The shared D3 runtime must accept a stable chart spec with:

- `:surface` (`:portfolio` or `:vaults`)
- `:axis-kind`
- `:time-range`
- `:series`
- `:y-ticks`
- `:theme`
- a pure tooltip builder function supplied by the calling surface

Each series entry must continue using the existing ids and labels and must remain fetch-agnostic. Optional fill metadata is additive and explicit: `:area-type :none|:solid|:split-zero`, plus fill colors and `:zero-y-ratio` when needed.

Public action ids for chart tab, timeframe, and benchmark selection are unchanged by this migration.

Plan revision note: 2026-03-15 16:00Z - Initial active ExecPlan created for `hyperopen-7y15`.
