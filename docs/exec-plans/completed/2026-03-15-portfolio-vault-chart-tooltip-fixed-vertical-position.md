# Keep Portfolio And Vault Chart Tooltips Vertically Stable

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Related `bd` issue: `hyperopen-ulci` ("Center portfolio and vault chart tooltip vertically").

## Purpose / Big Picture

The Portfolio and Vault detail performance charts already show the correct hovered value, but the tooltip currently shifts up and down as the hovered point changes. That movement makes the hover state feel noisy even when the user is only scanning left and right through time. After this change, the tooltip will keep a stable vertical anchor near the chart midpoint and will only travel horizontally with the cursor or hovered sample. A user can verify the result by opening `/portfolio` and a Vault detail route, dragging across the chart, and observing that the tooltip stays centered vertically while the timestamp and values continue updating normally.

## Progress

- [x] (2026-03-16 00:24Z) Reviewed the frontend and planning policies, audited the Portfolio and Vault chart renderers, and confirmed the tooltip top-position logic exists in both the shared D3 runtime and the legacy SVG fallback branches.
- [x] (2026-03-16 00:24Z) Created and claimed `bd` issue `hyperopen-ulci` for this work.
- [x] (2026-03-16 00:25Z) Authored this active ExecPlan in `/hyperopen/docs/exec-plans/active/2026-03-15-portfolio-vault-chart-tooltip-fixed-vertical-position.md`.
- [x] (2026-03-16 00:30Z) Implemented a shared fixed midpoint anchor in `/hyperopen/src/hyperopen/views/chart/d3/model.cljs` and switched the Portfolio/Vault fallback renderers to the same 50% top anchor.
- [x] (2026-03-16 00:31Z) Added regression coverage for the shared D3 layout/runtime plus explicit fallback tooltip top-style assertions in the Portfolio and Vault view tests.
- [x] (2026-03-16 00:37Z) Installed lockfile dependencies with `npm ci` because the worktree did not yet have `shadow-cljs` available locally.
- [x] (2026-03-16 00:38Z) Ran `npm run check` successfully.
- [x] (2026-03-16 00:39Z) Ran `npm test` successfully: 2403 tests, 12635 assertions, 0 failures, 0 errors.
- [x] (2026-03-16 00:39Z) Ran `npm run test:websocket` successfully: 385 tests, 2187 assertions, 0 failures, 0 errors.
- [x] (2026-03-16 00:40Z) Closed `hyperopen-ulci` as completed and moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: The current jitter is not a formatting problem. The tooltip payload is already stable; only the layout path is moving vertically.
  Evidence: `/hyperopen/src/hyperopen/views/chart/d3/model.cljs` computes `:top-px` from the hovered point `:y-ratio`, and the legacy Portfolio/Vault SVG renderers in `/hyperopen/src/hyperopen/views/portfolio_view.cljs` and `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs` duplicate that same vertical clamping logic with inline percentages.

- Observation: The repository still carries a non-D3 fallback for both surfaces even though D3 is the default.
  Evidence: Both chart views branch on `chart-renderer/d3-performance-chart?`, and the fallback branch still renders the hover tooltip directly in Hiccup. This change must update both paths so behavior stays consistent if the fallback is used.

- Observation: This worktree did not have the local npm toolchain installed before validation started.
  Evidence: The first targeted `npm test` attempt failed with `sh: shadow-cljs: command not found`, and `npm ci` restored the expected local binaries so the required gates could run.

## Decision Log

- Decision: Keep the tooltip vertically centered for the full hover session instead of only reducing the amount of vertical movement.
  Rationale: The user goal is to eliminate the perceptual jump entirely. A fixed midpoint anchor is simpler to reason about and easier to test than a softened or clamped-follow behavior.
  Date/Author: 2026-03-16 / Codex

- Decision: Preserve the existing horizontal behavior and left/right side switching.
  Rationale: The horizontal placement already avoids overflow by flipping the tooltip to the opposite side when the hover position approaches the right edge. Keeping that behavior isolates the change to vertical stability.
  Date/Author: 2026-03-16 / Codex

- Decision: Implement the shared vertical anchor in the D3 tooltip layout helper and mirror the same midpoint constant in the legacy SVG fallback views.
  Rationale: The D3 path is the main runtime used by users, but the fallback path remains part of the product contract and test surface. Updating both avoids renderer-dependent behavior drift.
  Date/Author: 2026-03-16 / Codex

## Outcomes & Retrospective

The implementation met the goal. Portfolio and Vault performance-chart tooltips now keep a stable midpoint vertical anchor while still updating horizontally, flipping sides near the right edge, and showing the same hovered timestamp, value, and benchmark rows as before.

The final code reduces complexity slightly. The shared D3 helper now exposes one explicit vertical anchor instead of deriving top placement from every hovered point, and the legacy fallback views no longer duplicate their own point-following top math. The only added surface area is a small shared helper API that keeps D3 and fallback behavior aligned.

## Context and Orientation

Portfolio and Vault detail both use the shared performance-chart D3 runtime in `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs`. That runtime receives a chart specification from `/hyperopen/src/hyperopen/views/portfolio_view.cljs` and `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs`, renders the chart lines, and positions a floating HTML tooltip with inline styles.

Pure positioning helpers for D3 live in `/hyperopen/src/hyperopen/views/chart/d3/model.cljs`. Right now `tooltip-layout` returns a `:left-px`, `:top-px`, and `:right-side?` tuple. `:top-px` is derived from the hovered point’s `:y-ratio`, which makes the tooltip jump vertically as the nearest chart sample changes.

The chart views also keep a fallback inline SVG path for environments where the D3 renderer is disabled. Those fallback branches compute `hover-tooltip-top-pct` directly from the hovered point’s `:y-ratio` and then write the percentage into the tooltip `:style`. Because that code duplicates the same behavior, it must be updated in parallel.

The relevant regression coverage already exists in `/hyperopen/test/hyperopen/views/chart/d3/model_test.cljs`, `/hyperopen/test/hyperopen/views/chart/d3/runtime_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`, and `/hyperopen/test/hyperopen/views/vaults/detail/chart_view_test.cljs`.

## Plan of Work

First, update `/hyperopen/src/hyperopen/views/chart/d3/model.cljs` so `tooltip-layout` no longer derives the vertical tooltip position from `:y-ratio`. The function should keep computing the horizontal pixel position and right-side flip exactly as it does now, but it should return a constant midpoint top position based on the chart height. `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` can then keep using the helper without any further behavior change beyond the new top value.

Second, update the fallback Hiccup renderers in `/hyperopen/src/hyperopen/views/portfolio_view.cljs` and `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs`. Their hover tooltip `:top` style should use a fixed midpoint percentage rather than a hovered-point-derived percentage. The tooltip should still be vertically centered with `translate(..., -50%)`, which means a midpoint `top` anchor keeps the card visually stationary while the contents change.

Third, extend the regression tests. The D3 model test should assert that `tooltip-layout` always returns the vertical midpoint regardless of the hovered point `:y-ratio`. The D3 runtime test should assert the rendered tooltip style gets that stable midpoint top value after hover. The Portfolio and Vault chart view tests should cover the fallback Hiccup style if those branches are exercised directly, or at minimum confirm the D3 host keeps the centered top style through mounted hover interaction.

Finally, run the required repo validation gates and then update this plan with the result before moving it to the completed directory.

## Concrete Steps

All commands in this section run from `/Users/barry/.codex/worktrees/d157/hyperopen`.

Inspect the positioning code and affected tests:

    sed -n '1,220p' src/hyperopen/views/chart/d3/model.cljs
    sed -n '420,470p' src/hyperopen/views/chart/d3/runtime.cljs
    sed -n '520,690p' src/hyperopen/views/portfolio_view.cljs
    sed -n '380,570p' src/hyperopen/views/vaults/detail/chart_view.cljs
    sed -n '1,220p' test/hyperopen/views/chart/d3/model_test.cljs
    sed -n '1,240p' test/hyperopen/views/chart/d3/runtime_test.cljs
    sed -n '650,760p' test/hyperopen/views/portfolio_view_test.cljs
    sed -n '1,240p' test/hyperopen/views/vaults/detail/chart_view_test.cljs

Apply the implementation edits and the matching regression tests.

Run the required validation gates:

    npm run check
    npm test
    npm run test:websocket

Expected success shape:

    npm run check
    ...
    Docs check passed.
    [:app] Build completed.
    [:portfolio] Build completed.
    [:portfolio-worker] Build completed.
    [:test] Build completed.

    npm test
    ...
    0 failures, 0 errors.

    npm run test:websocket
    ...
    0 failures, 0 errors.

## Validation and Acceptance

This work is accepted when these behaviors are all true:

1. On `/portfolio`, moving across the chart keeps the hover tooltip vertically centered instead of drifting with the plotted line.
2. On Vault detail, moving across the chart keeps the hover tooltip vertically centered instead of drifting with the plotted line.
3. Timestamp, metric value, and benchmark rows still update for the nearest hovered sample.
4. Left/right tooltip flipping still works near the right edge.
5. `npm run check`, `npm test`, and `npm run test:websocket` pass.

## Idempotence and Recovery

The changes are source-only and safe to rerun. If the fixed vertical anchor causes an overlap regression, the safe rollback is to restore only the tooltip top-position calculations in `/hyperopen/src/hyperopen/views/chart/d3/model.cljs`, `/hyperopen/src/hyperopen/views/portfolio_view.cljs`, and `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs` while keeping the new regression tests as a guide for the intended behavior.

## Artifacts and Notes

Pre-change evidence:

    /hyperopen/src/hyperopen/views/chart/d3/model.cljs
      tooltip-layout derives :top-px from hovered-point :y-ratio.

    /hyperopen/src/hyperopen/views/portfolio_view.cljs
      chart-card computes hover-tooltip-top-pct from hovered-point :y-ratio.

    /hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs
      chart-section computes hover-tooltip-top-pct from hovered-point :y-ratio.

Final validation evidence:

    npm run check
      Docs check passed.
      [:app] Build completed.
      [:portfolio] Build completed.
      [:portfolio-worker] Build completed.
      [:test] Build completed.

    npm test
      Ran 2403 tests containing 12635 assertions.
      0 failures, 0 errors.

    npm run test:websocket
      Ran 385 tests containing 2187 assertions.
      0 failures, 0 errors.

## Interfaces and Dependencies

No new dependency is needed. The public chart contract remains the same. The only interface that changes is the internal return shape semantics of `hyperopen.views.chart.d3.model/tooltip-layout`, where `:top-px` will now represent a fixed midpoint anchor instead of a hovered-point-derived offset. The Portfolio and Vault views will continue passing the same chart specs into the D3 runtime and will continue rendering the same tooltip markup and classes in the fallback branches.

Plan revision note: 2026-03-16 00:25Z - Initial plan authored after auditing the shared D3 tooltip layout path and the two fallback renderers.
Plan revision note: 2026-03-16 00:39Z - Updated the plan after implementation and validation to record the shared midpoint helper, fallback alignment, dependency-install prerequisite, and passing gate results.
