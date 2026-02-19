# Asset Selector Scroll Exhaustiveness Regression Fix

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, scrolling to the bottom of the asset selector should continue loading additional symbols until the full filtered market set is reachable. Users should no longer hit a suspiciously short list caused by render-limit growth failing to trigger near the bottom of the viewport.

The behavior will be visible by opening the selector, scrolling down, and observing that the list keeps extending beyond the initial chunk while preserving smooth virtualized rendering.

## Progress

- [x] (2026-02-19 18:14Z) Investigated selector list pipeline and isolated a regression: view virtualization constants were updated to `row-height=24` and `viewport=256`, but action-side near-bottom math remained at `48/384`, making growth thresholds unreachable in normal scrolling.
- [x] (2026-02-19 18:15Z) Implemented shared selector-list metrics in `/hyperopen/src/hyperopen/asset_selector/list_metrics.cljs` and wired `/hyperopen/src/hyperopen/asset_selector/actions.cljs` and `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` to consume it.
- [x] (2026-02-19 18:15Z) Updated regression fixtures in `/hyperopen/test/hyperopen/asset_selector/actions_test.cljs` and `/hyperopen/test/hyperopen/core_bootstrap_test.cljs` from unrealistic `scrollTop=5100` to near-boundary `scrollTop=2304` to catch geometry drift.
- [x] (2026-02-19 18:16Z) Ran required validation gates: `npm run check`, `npm test`, `npm run test:websocket` (all passed).
- [x] (2026-02-19 18:16Z) Moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/` after implementation and validation.

## Surprises & Discoveries

- Observation: `:actions/maybe-increase-asset-selector-render-limit` computes near-bottom against action-local constants (`row=48`, `viewport=384`) while `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` renders at `row=24`, `viewport=256`.
  Evidence: `/hyperopen/src/hyperopen/asset_selector/actions.cljs` and `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` currently define different fixed constants.
- Observation: Existing near-bottom tests used very large synthetic `scrollTop` values (for example `5100`) that still pass with stale constants and therefore did not catch the regression.
  Evidence: `/hyperopen/test/hyperopen/asset_selector/actions_test.cljs` and `/hyperopen/test/hyperopen/core_bootstrap_test.cljs` near-bottom cases use `5100`.
- Observation: `scrollTop=5100` snapped to `5088` under both 48px and 24px row heights, so prior assertions accidentally hid row-height mismatches.
  Evidence: `floor(5100/48)*48 = 5088` and `floor(5100/24)*24 = 5088`.

## Decision Log

- Decision: Introduce a shared asset-selector list-metrics namespace and consume it from both action and view layers.
  Rationale: This removes duplicated metric definitions and prevents future drift between rendering math and growth-threshold math.
  Date/Author: 2026-02-19 / Codex
- Decision: Update near-bottom tests to realistic scroll values derived from current row/viewport geometry.
  Rationale: Regression coverage should fail if row/viewport metrics diverge again, rather than relying on unrealistically high scroll values that mask mismatches.
  Date/Author: 2026-02-19 / Codex
- Decision: Keep `asset-selector-default-render-limit` symbol in actions while sourcing its value from shared metrics.
  Rationale: Preserves existing call/test surface while removing duplicated literal definitions.
  Date/Author: 2026-02-19 / Codex

## Outcomes & Retrospective

The regression is fixed by centralizing selector list geometry and default limit values so action-side growth logic and view-side virtualization cannot drift.

Implemented changes:

- Added `/hyperopen/src/hyperopen/asset_selector/list_metrics.cljs` as canonical source for `default-render-limit`, `row-height-px`, and `viewport-height-px`.
- Updated `/hyperopen/src/hyperopen/asset_selector/actions.cljs` to use shared row/viewport metrics for `set-asset-selector-scroll-top` and `maybe-increase-asset-selector-render-limit`.
- Updated `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` to consume the same shared metrics for virtualization windowing.
- Tightened regression coverage in `/hyperopen/test/hyperopen/asset_selector/actions_test.cljs` and `/hyperopen/test/hyperopen/core_bootstrap_test.cljs` with realistic near-bottom input that fails if constants drift again.

Validation results:

- `npm run check` passed.
- `npm test` passed (`1129` tests, `5195` assertions, `0` failures).
- `npm run test:websocket` passed (`135` tests, `587` assertions, `0` failures).

## Context and Orientation

The selector UI rendering is in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`. It owns virtual window calculations (`virtual-window`) and list row geometry assumptions.

The selector interaction and progressive render-limit growth logic is in `/hyperopen/src/hyperopen/asset_selector/actions.cljs`, specifically `set-asset-selector-scroll-top` and `maybe-increase-asset-selector-render-limit`.

Regression tests that cover this behavior live in:

- `/hyperopen/test/hyperopen/asset_selector/actions_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap_test.cljs`
- `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`

In this repository, "render-limit growth" means increasing the number of filtered rows eligible for virtualization when scroll position approaches the currently rendered-bottom threshold. "Virtualization window" means rendering only a visible row subset with top/bottom spacer elements to preserve total scroll geometry.

## Plan of Work

Create a shared selector-list metrics module under `/hyperopen/src/hyperopen/asset_selector/` that defines canonical geometry and render-limit defaults used by both view and action logic.

Replace local duplicated constants in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` and `/hyperopen/src/hyperopen/asset_selector/actions.cljs` with references to this shared module.

Revise action-layer tests to use scroll fixtures at the true near-bottom boundary for the current geometry so mismatched constants are detected.

Run required validation gates and then move this plan into the completed folder with final outcomes, evidence, and revision notes.

## Concrete Steps

From `/hyperopen`:

1. Add shared constants module and update requires:
   - Edit `/hyperopen/src/hyperopen/asset_selector/actions.cljs`.
   - Edit `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`.
   - Add `/hyperopen/src/hyperopen/asset_selector/list_metrics.cljs`.
2. Update regression tests:
   - Edit `/hyperopen/test/hyperopen/asset_selector/actions_test.cljs`.
   - Edit `/hyperopen/test/hyperopen/core_bootstrap_test.cljs`.
3. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
4. Move this file to `/hyperopen/docs/exec-plans/completed/` and finalize all living sections.

## Validation and Acceptance

Acceptance criteria:

- Scrolling near the bottom of the selector’s currently available rows triggers `:render-limit` growth under current 24px-row geometry.
- Action-side scroll snapping and near-bottom math uses the same geometry assumptions as view-side virtualization.
- Selector regression tests fail before the fix and pass after it for realistic near-bottom scroll input.
- Required validation gates pass.

## Idempotence and Recovery

Changes are additive and safe to rerun. If behavior regresses, revert only the modified selector metric, action, and test files together so geometry assumptions remain synchronized.

## Artifacts and Notes

Key changed files:

- `/hyperopen/src/hyperopen/asset_selector/list_metrics.cljs`
- `/hyperopen/src/hyperopen/asset_selector/actions.cljs`
- `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`
- `/hyperopen/test/hyperopen/asset_selector/actions_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap_test.cljs`

## Interfaces and Dependencies

No external dependency changes are expected. Existing public action event names and selector component interfaces remain unchanged. The new internal module will expose constant values consumed by selector view/action internals.

Plan revision note: 2026-02-19 18:14Z - Initial plan created after identifying action/view geometry drift causing render-limit growth regression.
Plan revision note: 2026-02-19 18:16Z - Implemented shared list metrics, updated regression fixtures, and recorded passing required validation gates.
Plan revision note: 2026-02-19 18:16Z - Moved plan from active to completed after finishing implementation and validation.
