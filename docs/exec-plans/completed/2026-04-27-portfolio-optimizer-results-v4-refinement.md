---
owner: frontend
status: completed
created: 2026-04-27
source_of_truth: false
tracked_issue: hyperopen-4pr0
---

# Portfolio Optimizer Results V4 Refinement

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while work proceeds.

Tracked issue: `hyperopen-4pr0` ("Align optimizer results page to v4 parity").

## Objective
Close the remaining visual gap between the implemented `/portfolio/optimize/:scenario-id` recommendation surface and the designer `ResultsV4` artboard without changing solver behavior, run orchestration, persistence, or rebalance contracts.

This is a refinement pass after the first results parity implementation. It focuses on exact layout proportions, strip density, typography/color application, and primitive behavior below the global HyperOpen header/footer.

## Evidence
- Designer interaction/layout source: `/Users/barry/Downloads/hyperopen portfolio optimizer/v4.jsx`, `ResultsV4`.
- Designer token/primitive source: `/Users/barry/Downloads/hyperopen portfolio optimizer/styles.css`.
- Designer screenshot supplied in-thread: `Screenshot 2026-04-27 at 9.50.57 PM.png`.
- Current implementation: `src/hyperopen/views/portfolio/optimize/scenario_detail_view.cljs`, `results_panel.cljs`, `target_exposure_table.cljs`, `frontier_chart.cljs`, and `src/styles/main.css`.

## Remaining Mismatch Inventory
- Header band remains too card-like and too tall relative to the v4 flat results header.
- Scenario title is still visually heavier than the 18px/500 v4 title treatment.
- Header actions use generic rounded app buttons instead of the compact v4 button primitives.
- KPI order differs from the artboard: v4 puts volatility first, expected return second.
- The results grid uses proportional `8/10/6` columns; v4 Results is explicit `500px 1fr 320px`.
- The recommendation body retains panel chrome around the frontier that should belong only to the chart box.
- The allocation table is missing the v4 leading expand/chevron column and uses slightly loose cell geometry.
- Diagnostics rows use generic panel/card styling rather than the v4 `.diag` rhythm.
- The scenario surface still has vertical section gaps from generic `space-y` utilities; v4 strips stack directly with 1px dividers.

## Implementation Plan
1. Add a second results-parity CSS layer under `.portfolio-optimizer-v4` that normalizes the scenario detail route into v4 flat bands.
2. Override the scenario header to a flat band: no rounded card, 18px title, compact status tag, compact actions.
3. Reorder KPI cards so volatility precedes expected return, matching `ResultsV4`.
4. Force the recommendation body to the v4 `500px minmax(0,1fr) 320px` desktop grid at `xl`.
5. Convert the frontier panel to a transparent center column wrapper and keep only the chart canvas boxed.
6. Add the allocation table leading chevron/blank column and tighten table cell padding/sizing.
7. Normalize right-rail diagnostics to the v4 `.diag` visual grammar.
8. Preserve all existing `data-role` hooks and current unit/browser regression behavior.

## Progress
- [x] (2026-04-27) Created this refinement ExecPlan from the latest designer screenshot and source files.
- [x] (2026-04-27) Implemented the v4 grid/strip/table/chart/diagnostics refinement slice.
- [x] (2026-04-27) Added visible copy-link affordance, human-readable provenance labels, exact Results grid columns, denser allocation rows, hidden single-leg detail rows, and subtler chart grid styling.
- [x] (2026-04-27) Browser-checked a solved `/portfolio/optimize/draft` scenario against the target results structure.
- [x] (2026-04-28) Tuned results background layering so allocation group rows and the frontier header area sit on the darker optimizer background while the graph canvas remains a raised surface.
- [x] (2026-04-28) Removed the center-column funding decomposition box from the visible results surface while preserving underlying run metadata.
- [x] (2026-04-28) Owner approved closing this refinement pass after the final route-frame cleanup and remaining visual refinements were validated.

## Surprises & Discoveries
- The first parity pass solved route and section decomposition, but generic Tailwind spacing and card primitives still visually overrode the designer’s tiled-dashboard system.
- SVG opacity attributes were not reliable enough for the efficient-frontier grid in the browser surface; explicit dark strokes match the v4 chart treatment more predictably.
- Low-invested/cash-dominant local runs can produce effective-N values above the visible universe count. The diagnostics rail now caps the display value at the universe count so the trust module reads as a user-facing concentration signal rather than raw intermediate math.

## Decision Log
- Decision: Use targeted `.portfolio-optimizer-v4` CSS overrides instead of broad component rewrites for this refinement.
  Rationale: The remaining issues are primitive-level visual mismatches, while the component decomposition and data contracts are already correct.
  Date/Author: 2026-04-27 / Codex

## Acceptance Criteria
- Results route uses flat header/provenance/tabs/KPI bands with no rounded-card treatment.
- KPI strip orders volatility, expected return, Sharpe, turnover, gross/net.
- Desktop recommendation grid uses left `500px`, center flexible, right `320px`.
- Allocation table includes a leading expand/blank column and maintains compact v4 table density.
- Frontier header and reading copy sit on the center background; only the chart canvas is boxed.
- Diagnostics rail renders as continuous rows with v4 label/value/subtext hierarchy.
- Existing optimizer tests and stale-result regression remain green.

## Deferred Follow-Up
- Rebalance and tracking tabs still need their own v4-specific KPI/provenance/header CTA variants; this pass intentionally focuses on the recommendation/results screenshot.
- Allocation `By Asset` / `By Leg` remains a visual segmented control; making it stateful requires a small view-state action slice.
- Copy link writes to clipboard when available, but does not yet render inline `Link copied` feedback.

## Validation
- Focused CLJS tests for results/scenario detail passed during implementation.
- Browser Use pass on `/portfolio/optimize/draft` after running a local 3-asset optimization scenario.
- `npm run check` passed on 2026-04-28 after the route-frame fix and with this results refinement diff still present in the worktree.
- `npm test` passed on 2026-04-28: 3590 tests, 19658 assertions, 0 failures, 0 errors.
- `npm run test:websocket` passed on 2026-04-28: 461 tests, 2798 assertions, 0 failures, 0 errors.
- In-app browser verification confirmed the optimizer detail route uses the dark optimizer frame and normal `/portfolio` keeps its original dashboard wrapper.

## Outcomes & Retrospective
Completed. The results route now more closely matches the v4 artboard by using the flat scenario header/provenance/tab/KPI bands, the explicit `500px minmax(0,1fr) 320px` recommendation grid, darker allocation and frontier surroundings, a raised chart canvas, compact allocation rows, capped effective-N display, and diagnostics rail styling closer to the designer primitive. The funding decomposition box was removed from the visible results surface while preserving underlying result metadata for future reintroduction if product wants it.
