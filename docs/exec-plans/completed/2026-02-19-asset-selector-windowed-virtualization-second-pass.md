# Asset Selector Second Pass: Windowed Virtualization

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, the asset selector should keep scrolling smooth even with hundreds of markets by rendering only the visible row window plus overscan, instead of rendering all rows up to the current progressive limit. Users should still be able to search, sort, favorite, and scroll to the full bottom of the selector, but DOM size and per-scroll rendering work should be materially lower.

## Progress

- [x] (2026-02-19 15:46Z) Created second-pass ExecPlan and scoped implementation to windowed virtualization with existing selector interaction semantics.
- [x] (2026-02-19 15:49Z) Implemented virtual window rendering in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`.
- [x] (2026-02-19 15:50Z) Wired scroll events to sync virtualization scroll state and preserve near-bottom prefetch action behavior.
- [x] (2026-02-19 15:51Z) Adjusted selector action behavior for virtualization-friendly row-aligned scroll state updates.
- [x] (2026-02-19 15:52Z) Updated and expanded tests for view virtualization and selector action behavior.
- [x] (2026-02-19 15:53Z) Ran required validation gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-19 15:56Z) Re-ran the browser perf trace workflow and produced an updated baseline-vs-second-pass delta report.
- [x] (2026-02-19 15:56Z) Finalized this plan and moved it to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: The first-pass smaller chunk increased growth events from 7 to 13 during bottom scroll and regressed aggregate trace totals despite preserving final rows.
  Evidence: `/hyperopen/tmp/asset-selector-perf-delta-2026-02-19.md`.
- Observation: With windowed virtualization, rendered-row metrics (`avgFinalRows`, `avgRowGrowth`) are no longer comparable to the non-virtual baseline because DOM rows intentionally remain bounded.
  Evidence: `/hyperopen/tmp/asset-selector-perf-2026-02-19T15-55-33-135Z/summary.json`.
- Observation: Near-bottom prefetch still grows the underlying list after virtualization, confirmed by scroll-height expansion while rendered row count remains constant.
  Evidence: `/tmp/hyperopen_asset_selector_dom_count.mjs` output (`scrollHeight 5760 -> 30192`, rows `20 -> 20`).

## Decision Log

- Decision: Keep the existing selector actions/contracts/public APIs and layer virtualization in the view first.
  Rationale: This targets the largest rendering hotspot with minimal behavioral churn and keeps rollout risk lower than a broader state/action redesign.
  Date/Author: 2026-02-19 / Codex
- Decision: Revert automatic render-limit growth step to 80 for second pass while relying on virtualization to cap DOM work.
  Rationale: Small growth chunks increased growth frequency in first pass; with virtualization in place, larger growth increments reduce dispatch churn without increasing rendered rows.
  Date/Author: 2026-02-19 / Codex

## Outcomes & Retrospective

Second-pass windowed virtualization is implemented and validated. The selector now renders a bounded visible window plus overscan spacers based on row-aligned `scroll-top`, while preserving existing render-limit growth controls and near-bottom prefetch behavior.

Required gates passed after implementation:

- `npm run check`
- `npm test` (1125 tests, 5168 assertions, 0 failures)
- `npm run test:websocket` (135 tests, 587 assertions, 0 failures)

Reprofiled with the same trace workflow and compared to baseline:

- Baseline: `/hyperopen/tmp/asset-selector-perf-2026-02-19T15-22-33-236Z/summary.json`
- Second pass: `/hyperopen/tmp/asset-selector-perf-2026-02-19T15-55-33-135Z/summary.json`
- Delta report: `/hyperopen/tmp/asset-selector-perf-delta-2026-02-19-second-pass.md`

Key aggregate changes vs baseline:

- `avgLongTaskCount`: `16.33 -> 1.00` (`-93.88%`)
- `avgScriptMs`: `4577.23 -> 1791.16` (`-60.87%`)
- `avgRenderMs`: `389.00 -> 90.48` (`-76.74%`)
- `avgPaintMs`: `256.84 -> 77.56` (`-69.80%`)
- `avgGcMs`: `221.10 -> 44.15` (`-80.03%`)

Residual note: `maxObservedLongTaskMs` increased (`263.60 -> 284.21`), so follow-up should inspect the outlier trace before further tuning.

## Context and Orientation

Asset selector rows render in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`. The current list uses a progressive `:render-limit` cap and maps row components for all rows up to that cap. Scroll-driven prefetch logic is in `/hyperopen/src/hyperopen/asset_selector/actions.cljs` via `maybe-increase-asset-selector-render-limit`, and selector state defaults are in `/hyperopen/src/hyperopen/state/app_defaults.cljs`.

In this repository, a "virtualized list" means rendering only a sliding window of rows based on current scroll position while preserving full scroll height using spacer elements. This is different from progressive loading because it controls DOM node count directly during scrolling.

## Plan of Work

Implement windowed rendering in the selector list function by calculating start/end row indexes from `scroll-top`, fixed row height, viewport height, and an overscan count. Keep full scroll geometry via top and bottom spacers, and render only the rows in the current window. Preserve existing progressive limit semantics (`render-limit`, "Load more", "Show all") for now so user flows remain stable while reducing row DOM and row component work.

Update scroll wiring so the list dispatches both `set-asset-selector-scroll-top` and `maybe-increase-asset-selector-render-limit` on scroll; this ensures virtualization receives scroll position while preserving existing near-bottom limit growth behavior. Adjust `set-asset-selector-scroll-top` to store row-aligned values so app state updates occur at row granularity rather than per pixel.

Update tests for view scroll action payloads and virtual window expectations, then run required gates and reprofile with the same trace script used in first pass.

## Concrete Steps

From `/hyperopen`:

1. Edit selector view/action files to add virtual window computation and row-aligned scroll state updates.
2. Update selector tests (view + actions + any impacted bootstrap expectations).
3. Run required gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
4. Run `node /tmp/hyperopen_asset_selector_perf_profile.mjs` and compare output to the baseline summary at `/hyperopen/tmp/asset-selector-perf-2026-02-19T15-22-33-236Z/summary.json`.

## Validation and Acceptance

Acceptance criteria:

- Selector list renders only a bounded window of rows plus spacers while maintaining correct scroll height.
- Scrolling still reaches the final market rows and preserves selection/favorites/search/sort interactions.
- Near-bottom prefetch behavior still functions and does not break "Load more" / "Show all".
- Required repository validation gates pass.
- Updated perf delta report shows before/after metrics for the same trace workflow.

## Idempotence and Recovery

Changes are additive and safe to rerun. If virtualization logic causes incorrect row visibility, fallback is to restore `asset-list` mapping behavior while keeping tests as guardrails. No destructive operations are required.

## Artifacts and Notes

Reference artifacts:

- `/hyperopen/tmp/asset-selector-perf-2026-02-19T15-22-33-236Z/summary.json` (baseline)
- `/hyperopen/tmp/asset-selector-perf-delta-2026-02-19.md` (first-pass delta)

## Interfaces and Dependencies

No new dependencies are required.

Interface notes:

- `hyperopen.views.asset-selector-view/asset-list` will gain virtualization based on `scroll-top` input.
- `hyperopen.asset-selector.actions/set-asset-selector-scroll-top` will normalize stored scroll values for virtualization cadence.
- Existing action IDs and runtime contracts remain intact.

Plan revision note: 2026-02-19 15:46Z - Initial second-pass virtualization plan created.
Plan revision note: 2026-02-19 15:56Z - Implementation, validation, and second-pass delta profiling completed.
