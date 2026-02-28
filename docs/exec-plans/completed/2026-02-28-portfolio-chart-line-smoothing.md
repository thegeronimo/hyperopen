# Portfolio Chart Line Smoothing for `/portfolio`

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Users currently see stair-step, jagged chart lines on `/portfolio` because the chart path is intentionally rendered as a step function. After this change, the same underlying portfolio and benchmark points will be rendered with direct line segments, producing a smoother visual line while preserving exact point values for hover and metrics.

A user can verify by opening `/portfolio` and checking that `Returns`, `Account Value`, and `PNL` lines connect points diagonally instead of horizontal-then-vertical stair steps.

## Progress

- [x] (2026-02-28 20:30Z) Reviewed portfolio chart view-model and view implementation to identify where step geometry is generated.
- [x] (2026-02-28 20:30Z) Reviewed UI/plan governance docs and confirmed required validation gates.
- [x] (2026-02-28 20:31Z) Replaced step-path generation in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` with line-path generation and kept single-point right-edge extension behavior.
- [x] (2026-02-28 20:31Z) Updated chart stroke cap/join styling in `/hyperopen/src/hyperopen/views/portfolio_view.cljs` to rounded joins/caps.
- [x] (2026-02-28 20:31Z) Added/adjusted regression tests in `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`.
- [x] (2026-02-28 20:31Z) Ran required validation gates: `npm run check`, `npm test`, and `npm run test:websocket` with passing results.
- [x] (2026-02-28 20:31Z) Moved this plan to `/hyperopen/docs/exec-plans/completed/` after acceptance verification.

## Surprises & Discoveries

- Observation: The portfolio chart path is explicitly rendered as a stair-step path via `chart-step-path`, not by noisy data or anti-aliasing.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` assigns `:path (chart-step-path points)` in `build-chart-model`.

- Observation: The vault detail chart already uses a direct line path builder with the same normalized point model.
  Evidence: `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` defines `line-path` and assigns `:path (line-path points)`.

## Decision Log

- Decision: Implement smoothing by replacing step path generation with direct line path generation, while keeping the same sampled points and hover index math.
  Rationale: This targets visual jaggedness without changing data semantics, metric computation, or hover behavior.
  Date/Author: 2026-02-28 / Codex

- Decision: Reuse the existing vault-chart line path pattern for consistency across product charts.
  Rationale: It is already production-tested in this repository and minimizes implementation risk.
  Date/Author: 2026-02-28 / Codex

## Outcomes & Retrospective

Implemented and validated end-to-end. Portfolio chart lines now render as direct segment lines between normalized points instead of horizontal/vertical step geometry, and path joins/caps are rounded for smoother visual continuity. The underlying sampled points, hover index behavior, and benchmark alignment logic were intentionally left unchanged, so tooltip values and metrics remain consistent with prior behavior.

Regression coverage now includes direct tests of the portfolio line-path helper and explicit view assertions for rounded path stroke attributes. All required repository validation gates passed after the change.

## Context and Orientation

The `/portfolio` page is rendered from `/hyperopen/src/hyperopen/views/portfolio_view.cljs`, which consumes a derived chart model from `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`. The view-model computes normalized chart points (`:x-ratio`, `:y-ratio`) and a serialized SVG path string (`:path`). The view then renders that path as `<path d="...">` in an SVG.

At present, the path serialization in the portfolio VM uses a step function (`chart-step-path`) that emits horizontal and vertical segments between points, creating a jagged look. This task changes only the path geometry and related stroke styling. It does not change the underlying chart points, benchmark alignment, or metric calculations.

## Plan of Work

Update the chart path helper in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` so each point is connected by one line segment (`M/L` commands in order), with the existing single-point fallback extending to the right edge. Wire `build-chart-model` to use the new helper.

Then update `/hyperopen/src/hyperopen/views/portfolio_view.cljs` chart path rendering to use rounded line caps and joins, which visually reduce jagged corners.

Finally, add regression tests: one VM test to assert the line-path helper emits direct-segment paths, and one view test to assert rendered chart paths use rounded stroke cap/join attributes.

## Concrete Steps

From `/Users//projects/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` path helper and `build-chart-model` path assignment.
2. Edit `/hyperopen/src/hyperopen/views/portfolio_view.cljs` chart path stroke attributes.
3. Edit tests in `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`.
4. Run:

    npm run check
    npm test
    npm run test:websocket

5. Move this plan to completed and capture final validation evidence.

## Validation and Acceptance

Acceptance criteria:

1. Portfolio chart series are rendered as direct line segments between points (no stair-step horizontal/vertical sequence).
2. Chart path style uses rounded line caps and rounded line joins.
3. Hover and tooltip behavior remain functional.
4. `npm run check`, `npm test`, and `npm run test:websocket` pass.

## Idempotence and Recovery

These are source-only edits and safe to rerun. If a regression appears, revert the portfolio path helper and view stroke attributes together to restore the previous step rendering while leaving unrelated portfolio logic intact.

## Artifacts and Notes

Validation artifacts from `/Users//projects/hyperopen`:

- `npm run check` passed (includes docs lint, hiccup lint, app/test/worker compiles).
- `npm test` passed (`Ran 1544 tests containing 7977 assertions. 0 failures, 0 errors.`).
- `npm run test:websocket` passed (`Ran 154 tests containing 710 assertions. 0 failures, 0 errors.`).

## Interfaces and Dependencies

No new dependencies are introduced. The existing portfolio chart VM contract remains stable (`:series` entries still include `:points` and `:path`), with only the geometry semantics of `:path` changing from step to line.

Plan revision note: 2026-02-28 20:30Z - Created initial ExecPlan for portfolio chart smoothing implementation and captured current-state discoveries.
Plan revision note: 2026-02-28 20:31Z - Updated progress to complete, recorded validation evidence, and finalized retrospective after successful implementation.
