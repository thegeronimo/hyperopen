owner: product+platform
status: active
source_of_truth: true
tracked_issue: hyperopen-q7j5
based_on:
  - /Users/barry/Downloads/HO-PDD-002_portfolio_optimization.md
  - /Users/barry/Downloads/hyperopen portfolio optimizer.zip
  - /tmp/hyperopen-optimizer-design-v4/v4.jsx
  - /tmp/hyperopen-optimizer-design-v4/Portfolio Optimizer.html
  - /tmp/hyperopen-optimizer-design-v4/DESIGN.md
  - /tmp/hyperopen-optimizer-design-v4/styles.css
  - /Users/barry/.codex/worktrees/d394/hyperopen/tmp/hyperopen-source-review-2026-04-26.zip
  - /Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-scroll-sequence-2026-04-26/

# Portfolio Optimizer V4 Alignment ExecPlan

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while the work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. It is self-contained so an engineer can execute the work without relying on the conversation that produced it.

Tracked issue: `hyperopen-q7j5` ("Align portfolio optimizer UI with v4 design").

## Purpose / Big Picture

Bring the existing Portfolio Optimizer implementation materially closer to the v4 designer source of truth without destabilizing the already-working optimizer domain, worker, scenario persistence, history loading, manual capital sizing, rebalance preview contracts, and tracking lifecycle.

The current implementation is functionally useful but structurally misaligned. `/portfolio/optimize/new` and `/portfolio/optimize/:scenario-id` both render through `src/hyperopen/views/portfolio/optimize/workspace_view.cljs`, producing one long vertical page that mixes setup inputs, run status, results, diagnostics, rebalance preview, tracking, current summary, and execution controls. The v4 design expects distinct route-specific and tab-specific surfaces: a scenario landing/history route, a setup workspace route, and a scenario detail route with recommendation, rebalance preview, tracking, and inputs tabs.

This is not a CSS reskin. The primary job is to decompose the product surface into the intended information architecture, then normalize visual primitives against the v4 design language. The implementation should preserve the existing optimizer logic wherever it already satisfies the product contract.

## Source Precedence And Corrections

Source precedence for this plan:

1. Product contract: `/Users/barry/Downloads/HO-PDD-002_portfolio_optimization.md`.
2. Design source of truth: `/Users/barry/Downloads/hyperopen portfolio optimizer.zip`.
3. Current repo implementation and current review screenshots.

Design zip precedence:

1. `v4.jsx` is the latest layout and interaction source of truth.
2. `Portfolio Optimizer.html` is the design canvas and artboard navigation source of truth.
3. `DESIGN.md` and `styles.css` are the visual system and primitive behavior source of truth.
4. Older v2 and v3 files are historical and should only be used for context.

Important product correction retained from previous planning: Black-Litterman is a return-model mode, not an optimization objective. The V1 objective layer remains Minimum Variance, Max Sharpe, Target Volatility, and Target Return. The return-model layer remains Historical Mean, EW Mean or stabilized historical where copy maps to existing estimator semantics, and Black-Litterman / Use my views.

## Executive Summary

What is wrong now:

- The optimizer route renders as a monolithic scroll page. Setup, result review, rebalance preview, tracking, diagnostics, and current exposure appear as sequential sections rather than separate work surfaces.
- `/portfolio/optimize/:scenario-id` does not yet feel like a scenario detail surface. It reuses the setup workspace and only conditionally appends results below it.
- The left rail is a fake tab/navigation system. `Setup`, `Results`, and `Diagnostics` mostly link back to the same route instead of owning distinct surfaces.
- The setup screen exposes too much at once. Mandatory controls are present, but presets, contextual Black-Litterman / Use my views, and tiered advanced constraints are missing.
- The results surface has important data, but not the v4 hierarchy. Provenance, KPI strip, recommendation table, real frontier chart, diagnostics rail, and trust/caution hierarchy are not screen-level elements.
- Rebalance preview and tracking are buried below the results rather than behaving like reviewable scenario tabs.
- Visual styling still uses broad rounded cards, heavy green selected states, and isolated Tailwind/DaisyUI card patterns instead of the v4 tiled, dense, low-radius, restrained-accent language.

What "aligned with v4" means:

- `/portfolio/optimize` is a scenario landing/history surface with filters, KPI strip, scenario table, active tracked scenario context, and a New Scenario CTA.
- `/portfolio/optimize/new` is a setup workspace with calm scenario header, status tag, preset row, grouped/tiered setup panels, contextual Use my views editor, and two visible primary actions plus overflow.
- `/portfolio/optimize/:scenario-id` is a scenario detail surface with a header, provenance strip, tab bar, KPI strip, and tab-owned bodies for Recommendation, Rebalance preview, Tracking, and Inputs.
- Rebalance preview and tracking are dedicated surfaces inside scenario detail, not sections appended below setup/results.
- The visual system uses v4 density, typography, table behavior, tag/chip treatment, button hierarchy, and panel tiling while still fitting HyperOpen's existing route shell.

What will not change in this pass:

- Do not rewrite the solver, covariance estimators, history loader, persistence layer, or execution adapter unless a small data-contract field is required to support the v4 UI.
- Do not copy mock data from the design. Real data from current optimizer state remains the source for tables, KPIs, charts, warnings, and previews.
- Do not introduce a parallel app shell. Continue to route through `hyperopen.views.portfolio-view` and `hyperopen.views.portfolio.optimize.view`.
- Do not reopen settled V1 product decisions such as Black-Litterman being a return model, signed exposure support, funding decomposition, and scenario lifecycle states.

Recommended sequencing:

1. Route/shell decomposition first.
2. Setup workspace alignment second.
3. Scenario detail scaffold with tabs and provenance third.
4. Recommendation/results tab refactor fourth.
5. Rebalance tab refactor fifth.
6. Tracking and inputs tabs sixth.
7. Visual primitive normalization and screenshot parity pass last.

## Progress

- [x] (2026-04-26) Inspected repo planning rules, v4 design source files, PDD route/product contract, current optimizer CLJS seams, current route/query-state contracts, and current scroll screenshots.
- [x] (2026-04-26) Created and claimed tracked issue `hyperopen-q7j5` for the v4 alignment work.
- [x] (2026-04-26) Wrote this active ExecPlan.
- [x] (2026-04-26) Completed Phase 1 route and shell decomposition. `/portfolio/optimize/:scenario-id` now renders a scenario-detail scaffold with provenance and v4 scenario tabs, `/portfolio/optimize/new` routes through `setup-view` and no longer appends retained results, rebalance, tracking, current summary, or signed-exposure sections, optimizer `otab` defaults to `recommendation`, and legacy `allocation` / `frontier` / `diagnostics` tabs alias to `recommendation`. The scenario tabs update shareable tab state through a dedicated optimizer action rather than generic navigation. Full CLJS suite passed: `npx shadow-cljs --force-spawn compile test && node out/test.js` produced `3569 tests`, `19488 assertions`, `0 failures`, `0 errors`.
- [x] (2026-04-26) Fixed Phase 1 review issues. `/portfolio/optimize/new` saves as a new scenario instead of reusing a stale loaded scenario id, scenario detail hides retained unsaved run output while a routed scenario is loading with `loaded-id=nil`, and bare scenario routes reset `otab` to `recommendation` instead of leaking a previous scenario tab. Regression coverage was added for all three cases.
- [x] (2026-04-26) Completed the first Phase 2 setup alignment slice. Setup now has a calm full-width header with status tag and primary actions, a `Start With` preset row (`Conservative`, `Risk-adjusted`, `Use my views`), a preset action that only changes objective/return-model layers while preserving universe and constraints, contextual `Use my views`/Black-Litterman support copy, and an `Advanced Overrides` disclosure containing row-level overrides and execution assumptions.
- [x] (2026-04-26) Updated and ran the existing optimizer browser regression against the new setup route seam. `npx playwright test tools/playwright/test/optimizer-history-network.qa.spec.mjs` passed and continues to verify that adding a universe asset does not fetch history until `Load History`.
- [x] (2026-04-26) Moved setup execution assumptions out of the advanced disclosure and into the right setup rail so fallback slippage, manual capital, default order type, and fee mode are visible in the default setup scan. The advanced disclosure now only contains per-asset overrides and held locks. Regression coverage asserts the execution assumptions panel renders inside `portfolio-optimizer-right-rail`.
- [x] (2026-04-26) Completed Phase 2 setup workspace alignment. The setup model layer now uses a dedicated objective-vs-model grid, keeps return and risk controls visually separate, preserves contextual Black-Litterman editing, and keeps mandatory V1 constraints visible while per-asset overrides remain tiered.
- [x] (2026-04-26) Completed Phase 3 scenario detail scaffold. Scenario detail now has a dedicated header/status/actions block, KPI strip, stale rerun banner, richer provenance with history/as-of/constraint/capital/link fields, and the existing route-backed Recommendation/Rebalance/Tracking/Inputs tabs.
- [x] (2026-04-26) Full CLJS suite after Phases 2/3 passed: `npx shadow-cljs --force-spawn compile test && node out/test.js` produced `3575 tests`, `19536 assertions`, `0 failures`, `0 errors`.
- [x] (2026-04-26) Completed the first Phase 4 recommendation/results alignment. The frontier is now a pure SVG chart with path/grid/target marker and click/drag point actions preserved, target exposure rows are grouped by asset while keeping signed instrument legs visible, and the scenario Recommendation tab opts out of embedded rebalance preview.
- [x] (2026-04-26) Full CLJS suite after Phase 4 passed: `npx shadow-cljs --force-spawn compile test && node out/test.js` produced `3575 tests`, `19546 assertions`, `0 failures`, `0 errors`.
- [x] (2026-04-26) Completed Phase 5 rebalance preview tab alignment. Rebalance now renders through a dedicated review surface with review-only header, summary KPIs, grouped trade rows, blocked-reason caution, margin context, and the existing execution review action. The stale execution-modal test now targets the Rebalance tab explicitly.
- [x] (2026-04-26) Full CLJS suite after Phase 5 passed: `npx shadow-cljs --force-spawn compile test && node out/test.js` produced `3575 tests`, `19551 assertions`, `0 failures`, `0 errors`.
- [x] (2026-04-26) Completed Phase 6 tracking and read-only inputs tab alignment. The temporary tracking bridge was removed from the Recommendation tab, tracking tests now target `otab=tracking`, and the Inputs tab now renders a read-only audit for universe, model choices, constraints, and execution assumptions with a duplicate-to-edit action.
- [x] (2026-04-26) Full CLJS suite after Phase 6 passed: `npx shadow-cljs --force-spawn compile test && node out/test.js` produced `3576 tests`, `19563 assertions`, `0 failures`, `0 errors`.
- [x] (2026-04-26) Extracted target exposure and read-only inputs rendering into dedicated component namespaces after namespace-size validation surfaced oversized migration modules. `scenario_detail_view.cljs` and `results_panel.cljs` are now below the production namespace-size threshold; the migration view test keeps a temporary capped exception pending a later test-suite split.
- [x] (2026-04-26) Started Phase 7 visual normalization with a route-local `portfolio-optimizer-v4` wrapper and scoped CSS for denser radii, optimizer amber selected states, tiled panel layering, and tabular numeric treatment. This is deliberately local to optimizer surfaces and does not change global HyperOpen primitives.
- [x] (2026-04-26) Updated browser regression coverage for the route-decomposed optimizer IA. The optimizer history-fetch smoke passed, and the `portfolio-regressions.spec.mjs -g "portfolio optimizer"` subset passed `9/9`, including setup, manual universe add/remove, history load, run storage, persisted scenario hydration, tracking tab, rebalance execution modal, Spectate Mode read-only handling, and failed-attempt recovery.
- [ ] Phase 7: Visual primitive normalization and browser QA artifact pass.

## Surprises & Discoveries

- Observation: The route parser already distinguishes `:optimize-index`, `:optimize-new`, and `:optimize-scenario`, but the view dispatcher sends both `:optimize-new` and `:optimize-scenario` to the same `workspace-view`.
  Evidence: `src/hyperopen/views/portfolio/optimize/view.cljs` dispatches `:optimize-new` and `:optimize-scenario` to `workspace-view/workspace-view`.

- Observation: The current query-state layer already has optimizer-owned deep-link params, but the allowed values model the old all-in-one page rather than v4 scenario tabs.
  Evidence: `src/hyperopen/portfolio/optimizer/query_state.cljs` owns `oview`, `otab`, and `odiag`; `results-tab-values` currently include `:allocation`, `:frontier`, `:diagnostics`, `:rebalance`, and `:tracking`, while v4 wants Recommendation, Rebalance preview, Tracking, and Inputs at the scenario-detail level.

- Observation: Current implementation contains several contract-correct data surfaces that should be preserved during visual refactor.
  Evidence: `results_panel.cljs` already renders signed current-vs-target weights, funding decomposition, trust/caution warnings, binding constraints, weight sensitivity, and rebalance rows. The plan should move these into better surfaces, not replace them with design mock data.

- Observation: The current efficient frontier affordance is interaction-capable but visually wrong for v4.
  Evidence: `frontier_chart.cljs` renders the frontier as a list of draggable/clickable point buttons. v4 expects an actual plotted chart with current and target points, gridlines, legend, hover/read affordance, and frontier-driven target setting.

- Observation: Current visual classes use the existing HyperOpen/DaisyUI green-heavy palette and rounded card shell, while v4 uses a more restrained amber/info accent model and sharper terminal-like tiling.
  Evidence: `tailwind.config.js` defines primary `#00d4aa`, and current optimizer panels use `rounded-xl`, `border-primary/50`, `bg-primary/10`, and repeated card gutters. v4 `styles.css` uses 2px radii, thin borders, tiled panels, restrained long/short green/red, amber accent for optimizer emphasis, and mono numerics.

- Observation: Existing tracking tests still expect tracking content to be reachable from scenario routes without first selecting `otab=tracking`.
  Evidence: After introducing the scenario detail shell, `tracking_panel_test.cljs` failed because default scenario detail rendered the recommendation tab and did not include the old tracking panel. Phase 1 keeps a temporary tracking compatibility bridge inside the recommendation surface; Phase 6 should remove this bridge when tracking has its own dedicated tab tests.

- Observation: Generic `:actions/navigate` was not sufficient for scenario tabs because it did not update the optimizer query-state model used by `otab`.
  Evidence: The review pass found that clicking a scenario tab emitted `[:actions/navigate "/portfolio/optimize/:id?otab=tracking"]`; the corrected action is `[:actions/set-portfolio-optimizer-results-tab :tracking]` and emits `[:effects/replace-shareable-route-query]`.

- Observation: The setup route must not reuse retained result bodies even when `last-successful-run` is present.
  Evidence: The old workspace composition appended `results-panel`, `tracking-panel`, current summary, and signed exposure beneath setup. The Phase 1 view tests now assert those data roles are absent from `/portfolio/optimize/new` while the run-state and last-successful-run status panels remain available in the trust/freshness rail.

- Observation: Scenario detail needs to guard against stale loaded data while an async route load is pending.
  Evidence: A route for `scn_new` with active loaded scenario `scn_old` previously rendered the old name and rows. The scenario detail surface now hides retained scenario output and displays a loading state until the route-matched scenario is available.

- Observation: The first review pass exposed three additional route-state leaks after Phase 1.
  Evidence: A loaded scenario id could be reused when saving from `/portfolio/optimize/new`; a solved unsaved run with `loaded-id=nil` could render under a new scenario route while load was pending; and a previously selected `otab=tracking` could leak into a different bare scenario route. Regression tests now cover each case.

- Observation: Phase 2 can safely add setup presets without mutating optimizer constraints or universe.
  Evidence: `apply-portfolio-optimizer-setup-preset` emits only objective, return-model, and dirty metadata writes. The action does not write universe, risk model, constraints, execution assumptions, or BL prior data.

- Observation: The existing optimizer Playwright regression was tied to the old all-in-one workspace data role.
  Evidence: Phase 1 intentionally removed `portfolio-optimizer-workspace` from `/portfolio/optimize/new`. The browser regression now waits for `portfolio-optimizer-setup-route-surface` while preserving the add-asset/history-fetch assertions.

- Observation: Manual capital and cost fallback inputs should not be hidden behind advanced overrides.
  Evidence: The setup screen needs manual capital to explain preview sizing when no account capital is available. The execution assumptions panel now lives in the right rail, while advanced remains scoped to per-asset controls.

- Observation: Scenario detail needed its own audit/KPI layer instead of relying on the first results panel section.
  Evidence: Phase 3 now renders scenario-level header, KPI strip, stale banner, and provenance before any tab body. This keeps auditability visible even when a tab is empty or loading.

- Observation: The existing frontier component already had valid point actions, but the visual representation was the wrong primitive.
  Evidence: Phase 4 replaced the point list with an SVG frontier chart while preserving the existing `portfolio-optimizer-frontier-point-*` click/drag action contract.

- Observation: The Recommendation tab should not carry rebalance preview as a trailing section.
  Evidence: `results-panel` now accepts `:include-rebalance?`; scenario Recommendation passes false while the legacy/default results panel behavior remains available for existing direct tests.

- Observation: The execution review affordance belongs to the Rebalance tab once tabs are route-backed.
  Evidence: The prior execution-modal test looked for the execution button on the default detail tab. Phase 5 retargets it to `:rebalance` and locks the dedicated rebalance review surface roles.

- Observation: The temporary tracking bridge can now be removed from Recommendation without losing coverage.
  Evidence: Phase 6 updates tracking panel tests to render scenario detail with `:portfolio-ui {:optimizer {:results-tab :tracking}}`, and default Recommendation tests assert that `portfolio-optimizer-tracking-panel` is absent.

- Observation: Inputs can be an audit surface in this pass without creating a new editable draft flow.
  Evidence: The Inputs tab uses the existing routed scenario state and dispatches `duplicate-portfolio-optimizer-scenario` for edit intent, preserving setup/new as the only editable workspace.

- Observation: Scenario detail must render the execution modal directly.
  Evidence: Browser regression caught that the Rebalance tab could dispatch `open-portfolio-optimizer-execution-modal`, but no modal appeared because the modal was only mounted by the legacy setup workspace. Scenario detail now renders `execution-modal` so saved scenario rebalance execution review works.

- Observation: The broader optimizer Playwright suite still encoded the old monolithic workspace IA.
  Evidence: Tests under `tools/playwright/test/portfolio-regressions.spec.mjs` referenced `portfolio-optimizer-workspace`, expected setup runs to inline results, and expected tracking on the default detail tab. The tests now assert setup-only behavior, scenario-detail surfaces, and tab-owned recommendation/rebalance/tracking behavior.

## Decision Log

- Decision: Use route-backed scenario detail tabs through the existing optimizer query-state mechanism, not nested route paths.
  Rationale: The v4 IA notes state that `/portfolio/optimize/:id` is the canonical detail surface and Results, Rebalance preview, Tracking, and Inputs are tabs because they share scenario state and are cross-referenced constantly. Existing `otab` query-state already provides shareable tab state without route-parser churn.
  Date/Author: 2026-04-26 / Codex

- Decision: Refactor the current `workspace_view.cljs` into route-specific surfaces instead of attempting to hide sections with CSS.
  Rationale: The current long-scroll page is the primary mismatch. CSS cannot produce the v4 mental model, provenance hierarchy, or tab-specific review surfaces.
  Date/Author: 2026-04-26 / Codex

- Decision: Preserve optimizer application/domain/infrastructure code unless a UI contract requires a small, tested data addition.
  Rationale: The problem in this pass is product-surface alignment. Solver/math churn would increase risk and distract from the IA correction.
  Date/Author: 2026-04-26 / Codex

- Decision: Keep execution as review/stage aware but make blocked rows and read-only limitations explicit in the rebalance tab.
  Rationale: The existing execution and blocked-row semantics are product-contract relevant. The v4 rebalance surface is review-first and should make limitations visible rather than hiding execution behind a missing or disabled button.
  Date/Author: 2026-04-26 / Codex

- Decision: Keep a temporary Phase 1 tracking compatibility bridge in the recommendation tab.
  Rationale: The route split should not break existing saved/executed tracking behavior while the dedicated Tracking tab is still scaffold-only. This is explicitly transitional and must be removed when Phase 6 moves tracking into its own tab with updated acceptance coverage.
  Date/Author: 2026-04-26 / Codex

- Decision: Scenario tab clicks use `set-portfolio-optimizer-results-tab` plus `replace-shareable-route-query`, while anchors retain `href` URLs for copy/open-in-new-tab affordances.
  Rationale: This keeps tab state in the optimizer UI model, preserves shareable `?otab=` deep links, and avoids generic navigation stripping or bypassing query-state normalization.
  Date/Author: 2026-04-26 / Codex

- Decision: Saving from `/portfolio/optimize/new` must ignore any retained `active-scenario.loaded-id` or draft id unless an explicit `:scenario-id` option is passed.
  Rationale: `/new` is a new-scenario setup route. Reusing a previously loaded id would overwrite an existing scenario after a normal load -> new -> run -> save flow.
  Date/Author: 2026-04-26 / Codex

- Decision: Bare `/portfolio/optimize/:scenario-id` route-query restore resets the detail tab to `recommendation`.
  Rationale: Opening a different scenario from the scenario table should not inherit a previous scenario's selected tab unless the URL explicitly carries `?otab=...`.
  Date/Author: 2026-04-26 / Codex

- Decision: The first setup preset implementation is a narrow model-layer preset, not a full draft template system.
  Rationale: V4 needs a preset row, but a safe first slice should not silently overwrite universe, constraints, risk model, manual capital, or per-asset overrides.
  Date/Author: 2026-04-26 / Codex

- Decision: Scenario Recommendation no longer renders tracking as a compatibility bridge.
  Rationale: Tracking is now a route-backed scenario tab with direct tests, so keeping it embedded under Recommendation would preserve the original all-in-one scroll mismatch.
  Date/Author: 2026-04-26 / Codex

- Decision: Keep Phase 7 visual normalization route-local for this pass.
  Rationale: The goal is to move optimizer surfaces materially toward v4 without risking unrelated portfolio/trade surfaces. A scoped `portfolio-optimizer-v4` wrapper lets the optimizer adopt denser radius/accent/table behavior while preserving the global HyperOpen shell.
  Date/Author: 2026-04-26 / Codex

## Outcomes & Retrospective

Not started. This section must be updated after each implementation phase. The expected outcome is a route-decomposed optimizer surface that passes browser QA and is materially closer to v4 in IA, hierarchy, visual language, and interaction model while preserving current optimizer behavior.

## Context And Orientation

Current route/view structure:

- `src/hyperopen/portfolio/routes.cljs` parses `/portfolio/optimize`, `/portfolio/optimize/new`, and `/portfolio/optimize/:scenario-id`.
- `src/hyperopen/views/portfolio-view.cljs` detects optimizer routes and renders `optimize-view/optimizer-view` inside the existing portfolio module shell.
- `src/hyperopen/views/portfolio/optimize/view.cljs` dispatches index routes to `index-view/index-view` and all non-index optimizer routes to `workspace-view/workspace-view`.
- `src/hyperopen/views/portfolio/optimize/workspace_view.cljs` currently owns setup controls, left rail actions, right trust/freshness rail, stale banners, results, tracking, current summary, and execution modal.
- `src/hyperopen/views/portfolio/optimize/results_panel.cljs` currently owns results assumptions, target exposure table, funding decomposition, performance summary, frontier list, trust/caution, diagnostics, warnings, and rebalance preview.
- `src/hyperopen/views/portfolio/optimize/tracking_panel.cljs` currently owns manual tracking and active tracking sections, but it is rendered as another section below results.
- `src/hyperopen/views/portfolio/optimize/frontier_chart.cljs` currently owns frontier point interactions, but renders a table-like point list instead of a chart.
- `src/hyperopen/portfolio/optimizer/query_state.cljs` owns `ofilter`, `osort`, `oview`, `otab`, and `odiag`.

Current implementation capabilities to preserve:

- Separate return model and risk model controls.
- Black-Litterman / Use my views as return-model mode and existing view authoring actions.
- Manual universe add, current-holdings seed, and history load.
- Manual capital base for non-connected preview sizing.
- Worker-based optimizer runs.
- Signed exposure and long/short-aware target output.
- Funding decomposition by instrument.
- Stale result comparison based on request signatures.
- Rebalance preview rows with ready/blocked status, cost, slippage, margin, and blocked reasons.
- Execution modal and scenario lifecycle statuses including executed and partially executed.
- Tracking snapshot model with weight drift and predicted-vs-realized surfaces.

Current implementation mismatches to fix:

- Route and tab ownership do not match PDD/v4 IA.
- Setup, results, rebalance, tracking, and current portfolio summary are mixed into one vertical scroll.
- Results have no screen-level provenance strip.
- KPI strip is not the dominant result/detail hierarchy.
- Frontier is not a plotted chart.
- Rebalance preview is not a dedicated review surface.
- Tracking is not a dedicated scenario surface.
- Inputs audit view is missing from scenario detail.
- Visual primitives are not normalized to v4 density and accent discipline.

## Mismatch Inventory

### IA / Routing

Current:

- `/portfolio/optimize` renders `index_view.cljs` and has a basic scenario board.
- `/portfolio/optimize/new` renders the same workspace view as a saved scenario route.
- `/portfolio/optimize/:scenario-id` renders setup first, then appends results if available.
- Query-state has optimizer tab-like params, but the visible page does not use them as the primary IA.

Expected:

- `/portfolio/optimize` is the scenario landing/history surface with a dense KPI strip, filters, table, active scenario context, and new scenario CTA.
- `/portfolio/optimize/new` is setup-only and optimized for configuring a run.
- `/portfolio/optimize/:scenario-id` is scenario detail with tabbed Recommendation, Rebalance preview, Tracking, and Inputs.
- Tabs are shareable through `?otab=...`, not separate nested route files.

Gap:

- The current implementation is structurally incompatible with v4 until the scenario detail surface is separated from setup.

### Setup Workspace

Current:

- Header is dominated by left rail scenario card and persistent actions.
- No "Start with" preset row.
- Objective, return model, risk model, constraints, BL views, overrides, and execution assumptions are all stacked.
- Black-Litterman authoring exists, but does not appear as the contextual "Use my views" workspace with market reference, views, and combined output.
- Advanced constraints and per-asset overrides are always exposed in large blocks.
- Right trust/freshness rail is persistent and competes with setup content.

Expected:

- Calm top header with eyebrow, scenario name, status tag, overflow, Save draft, and Run optimization.
- Preset row: Conservative, Risk-adjusted, Use my views.
- Preset switching is explicit and does not silently overwrite user deviations.
- Three-column workspace: left setup sections, center live summary or views explainer, right assumptions rail or views editor.
- Return model and risk model remain separate.
- Use my views / Black-Litterman reveals contextual trust panel and editor.
- Advanced constraints are collapsed or tiered.
- Trust/freshness context supports setup but does not dominate.

Gap:

- Current setup has the right controls but wrong hierarchy, control grouping, and contextual behavior.

### Results / Recommendation Surface

Current:

- Results appear below setup on the same route.
- Run assumptions strip exists but is card-local.
- KPI cards exist inside performance summary, not as screen-level summary.
- Allocation table is a grid of rounded rows, not a dense v4 table with grouped asset rows and expandable legs.
- Frontier is a point list/table, not a chart.
- Diagnostics are present but not composed as the right-side "How much to trust this" module.
- Provenance is missing as a persistent audit strip.

Expected:

- Scenario header, provenance strip, tabs, KPI strip, and then three-column recommendation body.
- Left: allocation by asset with current, target, delta, delta dollars, grouped legs, and binding-constraint chips.
- Center: actual efficient frontier chart with current and recommended target points, legend, hover/click affordances, and exposure comparison visuals.
- Right: diagnostics/trust/caution stack with more diagnostics collapsed.
- Stale/rerun state is visible near provenance/header and keeps last successful output.

Gap:

- Current data is useful, but component composition and visual hierarchy do not protect the review workflow.

### Rebalance Preview

Current:

- Rebalance preview is appended below results as another long-section panel.
- It shows ready/blocked counts, fees, slippage, margin, row statuses, and an execution modal trigger.
- Rows are flat instrument rows; grouped by asset/leg/venue behavior is limited.

Expected:

- Rebalance preview is a dedicated scenario tab and review surface.
- Header states review-only posture and no orders are sent yet.
- Provenance strip captures source scenario, generated time, trade count, turnover, slippage, and funding window.
- Summary KPIs sit at top.
- Trade rows are grouped by asset with expandable leg-level implementation.
- Side panel explains what the rebalance achieves and pre-staging cautions.
- Execution/staging is clearly downstream from review.

Gap:

- The preview data contract is close, but the page-level workflow is wrong.

### Tracking Surface

Current:

- Tracking appears below rebalance and current summary.
- Manual tracking enablement and active tracking display exist.
- Drift chart is a simple bar list and realized-vs-predicted is a list of snapshots.

Expected:

- Tracking is a scenario tab with drift status in header, provenance, KPI strip, drift table, tolerance-band visualization, realized-vs-target chart, and plain-language explanation of why the scenario is flagged.
- Tracking state should clarify scenario lifecycle: saved, executed, partially executed, tracking, archived/read-only.

Gap:

- Current tracking behavior exists but is visually and structurally buried.

### Visual System / Tokens / Component Styling

Current:

- Optimizer UI uses rounded `rounded-xl` card shells with gutters and Tailwind/DaisyUI `primary` green for many active/selected states.
- Numeric data uses `tabular-nums` in many places, but not consistently via a shared primitive.
- Tags often look like filled or rounded pills rather than v4 outline chips.
- Tables are mostly custom grids of bordered cards, not sticky dense table rows.
- Primary actions often use green outline/tint even when they are not directional trading actions.

Expected:

- Dense tiled panels with 1px borders, little or no gutter inside data regions, 2px radii for controls, and restrained accents.
- Green/red reserved for long/short, execution direction, P&L, and success/error signal. Optimizer emphasis should use the v4 amber/info accent discipline where appropriate.
- Header typography stays compact; labels are uppercase xs mono/letterspaced; numerics are mono with tabular figures.
- Buttons use default/primary/ghost hierarchy with only one or two visible screen actions plus overflow.
- Tags are outline chips, color variants tint border/text only.
- Hover/active states are subtle background lifts, not bright fills.

Gap:

- Current UI reads as an internal tool using HyperOpen colors, not yet as the v4 optimizer product surface.

### Data Presentation / Contract Gaps

Current data contracts mostly support v4 but need presentation normalization.

Known gaps:

- Scenario detail provenance needs a compact view model assembled from draft, result, scenario metadata, run request, and history freshness.
- Recommendation table needs grouped asset rows and optional leg rows. Current result stores instrument-level rows; grouping may need a view-model function that groups `spot:BTC`, `perp:BTC`, and cash/USDC style rows by base asset without changing engine output.
- Frontier chart needs chart-ready scaled points plus current and target metrics. Existing `:frontier` points exist, but current-vs-target overlay and hover tooltip view model may need a small helper.
- Rebalance tab needs summary grouping by asset and leg-level rows. Existing preview rows include enough status/cost fields but may need a grouping view model.
- Tracking tab needs tolerance-band view model from target/current/drift rows. Existing tracking rows have drift and target/current data; chart-friendly series may need a helper.
- Inputs tab needs a read-only mirror of the solved request/draft. Existing saved scenario config can be used; avoid editable side effects from the scenario detail inputs tab.

### Browser QA / Screenshot Parity Gaps

Current captured screenshots prove the mismatch:

- `portfolio-optimizer-scroll-01.png` shows setup and side rails at top.
- `portfolio-optimizer-scroll-04.png` shows per-asset overrides and execution assumptions mid-page.
- `portfolio-optimizer-scroll-06.png` shows diagnostics and rebalance preview only after scrolling through setup/results.
- `portfolio-optimizer-scroll-07.png` shows rebalance preview, tracking inactive, current summary, and signed exposure at the bottom.

Expected screenshot set after implementation:

- `/portfolio/optimize` landing at 1440px.
- `/portfolio/optimize/new` default setup at 1440px.
- `/portfolio/optimize/new` Use my views setup at 1440px.
- `/portfolio/optimize/:id?otab=recommendation` with solved scenario at 1440px.
- `/portfolio/optimize/:id?otab=rebalance` with ready and blocked rows at 1440px.
- `/portfolio/optimize/:id?otab=tracking` with tracking/drift state at 1440px.
- `/portfolio/optimize/:id?otab=inputs` read-only audit mirror at 1440px.
- Responsive spot checks at 1280px, 768px, and 375px per `docs/FRONTEND.md`; mobile can be read/light-edit/review only, execution remains desktop-only.

## Proposed Route And Screen Model

### Landing / History Route

Route: `/portfolio/optimize`.

View owner: keep `src/hyperopen/views/portfolio/optimize/index_view.cljs`, but align it to v4 landing.

Target responsibilities:

- Header: "Optimizer" with scenario index context, filters, search if supported by existing state, and New Scenario CTA.
- KPI strip: active scenario, live portfolio metric if available, realized vol, predicted-vs-realized, last run.
- Scenario table: name, status badge, objective and constraints fingerprint, universe count, BL on/off or return model, target/live metrics, drift, updated time, actions.
- Empty state: no runs yet, CTA to new scenario, and common entry deep links.
- Active tracked scenario context if tracking state exists.

### Setup Route

Route: `/portfolio/optimize/new`.

New likely view owner: `src/hyperopen/views/portfolio/optimize/setup_view.cljs`.

Target responsibilities:

- Use existing optimizer draft and readiness state.
- Header with scenario name/status, overflow, Save draft, Run optimization.
- Preset row with Conservative, Risk-adjusted, Use my views.
- Left setup sections: Universe, Return / Risk model, Objective, Constraints.
- Center live summary for non-BL modes; Use my views explainer for Black-Litterman mode.
- Right assumptions rail for non-BL modes; BL views editor for Use my views.
- Advanced constraints and per-asset overrides collapsed/tiered.
- Readiness/history/trust context appears in a supporting rail, not as a dominant side card.

### Scenario Detail Route

Route: `/portfolio/optimize/:scenario-id`.

New likely view owner: `src/hyperopen/views/portfolio/optimize/scenario_detail_view.cljs`.

Tab state:

- Use query param `otab` through `hyperopen.portfolio.optimizer.query-state`.
- Recommended values: `recommendation`, `rebalance`, `tracking`, `inputs`.
- Add aliases for old values: `allocation`, `frontier`, and `diagnostics` normalize to `recommendation`; old `rebalance` and `tracking` continue to work.

Target responsibilities:

- Header: scenario name, status tag, last run, overflow actions, Preview rebalance, Save scenario or Save advisory.
- Provenance strip: objective, return model, risk model, horizon/lookback, funding, constraints, data as-of, scenario id, copy-link affordance.
- Tab bar: Recommendation, Rebalance preview, Tracking, Inputs.
- KPI strip: current-to-target volatility, expected return, Sharpe, turnover, gross/net, plus effective N or condition number where space allows.
- Tab body selected by `otab`.

Recommendation tab:

- Three-column body: allocation table, frontier/exposure visuals, diagnostics/trust rail.

Rebalance tab:

- Review-only surface with top summary KPIs, grouped trade rows, side panel explaining impact, and existing execution modal/staging action.

Tracking tab:

- Drift KPIs, drift table with tolerance, realized-vs-target chart, why flagged panel, re-optimize and preview-rebalance actions where valid.

Inputs tab:

- Read-only mirror of the run inputs that produced the scenario. It should not mutate the active draft unless the user explicitly duplicates or forks.

## File-By-File Touch List

Likely existing files to edit:

- `src/hyperopen/views/portfolio/optimize/view.cljs`: dispatch `:optimize-new` to setup view and `:optimize-scenario` to scenario detail view.
- `src/hyperopen/views/portfolio/optimize/workspace_view.cljs`: break apart or convert into a temporary compatibility wrapper; remove monolithic rendering once replacement views pass tests.
- `src/hyperopen/views/portfolio/optimize/index_view.cljs`: align landing/history surface with v4 scenario index.
- `src/hyperopen/views/portfolio/optimize/results_panel.cljs`: split into recommendation, rebalance, diagnostics, provenance/KPI helpers; preserve formatting and data extraction logic where useful.
- `src/hyperopen/views/portfolio/optimize/frontier_chart.cljs`: replace point-list rendering with an SVG chart while preserving click/drag-to-set-target behavior.
- `src/hyperopen/views/portfolio/optimize/tracking_panel.cljs`: migrate to dedicated tracking tab and preserve manual tracking actions.
- `src/hyperopen/views/portfolio/optimize/universe_panel.cljs`: adapt to setup left-column section and v4 table/toggle density.
- `src/hyperopen/views/portfolio/optimize/black_litterman_views_panel.cljs`: adapt into contextual Use my views editor and trust panel.
- `src/hyperopen/views/portfolio/optimize/instrument_overrides_panel.cljs`: make advanced/tiered constraints compatible with the setup workspace.
- `src/hyperopen/views/portfolio/optimize/execution_modal.cljs`: preserve existing modal but ensure launch context and labels match the rebalance tab.
- `src/hyperopen/views/portfolio/optimize/run_status_panel.cljs`: reuse status badge/run-state display in new headers and provenance.
- `src/hyperopen/views/portfolio/optimize/setup_readiness_panel.cljs`: move into setup supporting rail or compact readiness module.
- `src/hyperopen/portfolio/optimizer/query_state.cljs`: revise `otab` allowed values and aliases for v4 scenario tabs.
- `src/hyperopen/state/app_defaults.cljs`: update optimizer UI default tab if query-state defaults change.
- `src/hyperopen/portfolio/routes.cljs`: probably no structural change; only add helper if a scenario tab URL helper is needed.
- `src/hyperopen/views/portfolio-view.cljs`: keep existing optimizer route seam; only adjust if new surfaces need outer spacing/styling hooks.
- `src/styles/main.css`: add a narrow optimizer v4 utility layer if class vectors alone cannot express table/chip/chart states cleanly.
- `tailwind.config.js`: avoid broad token rewrite. Touch only if a small named color/font token is necessary and approved by visual-system review.

Likely new files:

- `src/hyperopen/views/portfolio/optimize/shell.cljs`: shared optimizer page chrome, screen header, overflow, provenance, tabs, status tags.
- `src/hyperopen/views/portfolio/optimize/setup_view.cljs`: `/portfolio/optimize/new` setup surface.
- `src/hyperopen/views/portfolio/optimize/scenario_detail_view.cljs`: `/portfolio/optimize/:scenario-id` detail shell and tab dispatcher.
- `src/hyperopen/views/portfolio/optimize/recommendation_tab.cljs`: v4 Recommendation tab composition.
- `src/hyperopen/views/portfolio/optimize/rebalance_tab.cljs`: v4 Rebalance preview tab composition.
- `src/hyperopen/views/portfolio/optimize/inputs_tab.cljs`: read-only scenario inputs mirror.
- `src/hyperopen/views/portfolio/optimize/kpi_strip.cljs`: shared KPI strip helpers.
- `src/hyperopen/views/portfolio/optimize/provenance_strip.cljs`: audit/provenance view model and renderer.
- `src/hyperopen/views/portfolio/optimize/allocation_table.cljs`: grouped current/target/delta allocation table and leg expansion.
- `src/hyperopen/views/portfolio/optimize/rebalance_table.cljs`: grouped trade rows and leg expansion.
- `src/hyperopen/views/portfolio/optimize/view_models.cljs`: pure view-model assembly for provenance, KPIs, grouped allocations, grouped rebalance rows, tracking rows, and inputs audit.
- `src/hyperopen/views/portfolio/optimize/components.cljs`: small v4-aligned primitives if a CSS utility layer is not enough.

Likely tests to create or modify:

- `test/hyperopen/portfolio/optimizer/query_state_test.cljs`: v4 tab parse/default/alias/serialization.
- `test/hyperopen/portfolio/routes_test.cljs`: no nested route regression and existing optimizer route family remains valid.
- `test/hyperopen/views/portfolio/optimize/view_test.cljs`: route dispatch and surface isolation.
- `test/hyperopen/views/portfolio/optimize/setup_view_test.cljs`: preset row, run/save actions, BL contextual editor.
- `test/hyperopen/views/portfolio/optimize/scenario_detail_view_test.cljs`: provenance, tabs, KPI strip, selected tab rendering.
- `test/hyperopen/views/portfolio/optimize/recommendation_tab_test.cljs`: signed grouped allocation, frontier chart presence, diagnostics rail.
- `test/hyperopen/views/portfolio/optimize/rebalance_tab_test.cljs`: summary KPIs, blocked reasons, grouped rows, execution CTA state.
- `test/hyperopen/views/portfolio/optimize/tracking_tab_test.cljs`: drift KPIs, manual/active tracking states.
- `tools/playwright/test/optimizer-v4-alignment.spec.mjs` or extend `tools/playwright/test/optimizer-history-network.qa.spec.mjs`: stable browser route/screenshot QA.

## Refactor Strategy

The safe migration path is decomposition around existing state and effects, not a rewrite.

Step 1: Add shared shell primitives without changing behavior.

- Introduce header, tag, button, tab, provenance, KPI, and table helpers under `views/portfolio/optimize`.
- Keep current `workspace_view.cljs` rendering untouched until helpers are ready.
- Add unit tests for pure view-model helpers first.

Step 2: Split route ownership.

- Update `optimize/view.cljs` to dispatch `/new` and `/:scenario-id` separately.
- Initially, `setup_view.cljs` can reuse existing setup panel functions from `workspace_view.cljs`.
- Initially, `scenario_detail_view.cljs` can reuse existing `results_panel` and `tracking_panel` content inside tabs.
- This step should eliminate the all-in-one primary desktop flow even before visual polish is complete.

Step 3: Move logic, then restyle.

- Extract pure data derivation from `results_panel.cljs` into `view_models.cljs`.
- Move recommendation-only rendering to `recommendation_tab.cljs`.
- Move rebalance-only rendering to `rebalance_tab.cljs`.
- Move tracking-only rendering to a dedicated tab.
- Preserve action vectors and effect paths exactly unless tests prove a route/state ownership bug.

Step 4: Replace old scroll sections.

- Once new route-specific surfaces pass tests, delete or shrink `workspace_view.cljs` to a compatibility wrapper.
- Ensure no route appends setup, results, rebalance, and tracking in one primary desktop scroll.

Step 5: Normalize visual primitives.

- Replace repeated rounded card/grids with dense table/panel primitives.
- Restrict green to long/success/directional states.
- Use amber/info for optimizer accent, warnings, frontier target, and provenance where v4 expects them.
- Keep existing HyperOpen topbar, portfolio subnav, statusbar, and page shell. Do not create a second app shell.

## Visual System Alignment Plan

Ground rules from `DESIGN.md` and `styles.css`:

- Use dense tiled panels with 1px borders.
- Use 2px radii for controls and chips; avoid large rounded cards in dense regions.
- Keep spacing tight: 4px/8px inside controls and 16px/24px only at screen boundaries.
- Use mono/tabular numerics for all weights, prices, notionals, percentages, timestamps, and ids.
- Use uppercase xs labels with modest letter-spacing for panel headings and table headers.
- Use outline tags/chips with border/text tint only.
- Use green/red sparingly for long/short, directional execution, P&L, and true success/error.
- Use amber/info accents for optimizer recommendations, target point, warnings, provenance, and selected optimizer tabs where appropriate.
- Buttons should have explicit hierarchy. Each screen should expose at most two visible actions and put the rest in overflow.
- Tables should be real dense rows with sticky headers where scrollable, not stacks of rounded row cards.
- Charts should use flat gridlines, mono axis labels, no gradients, and current/target legend patterns.

Patterns to delete or normalize:

- Repeated `rounded-xl` section shells in optimizer dense areas.
- Bright green `bg-primary/10` as the default selected state for every optimizer option.
- Long scroll `space-y-4` route composition as the main desktop layout.
- Fake nav links in left rail that route back to the same page.
- Frontier point list as the primary chart.
- Rebalance and tracking as appended cards below the results.

Patterns to preserve:

- Existing HyperOpen top-level navigation and portfolio route shell.
- Existing account/wallet/read-only posture language.
- Existing warning/error colors where they indicate true risk, blocked rows, or infeasible constraints.
- Existing data-role attributes where tests depend on them; add new stable data roles for new surfaces rather than removing old ones abruptly.

## Plan Of Work / PR Sequence

### Phase 1 — Route And Shell Decomposition

Goals:

- Introduce route-specific optimizer views.
- Stop rendering `/new` and `/:scenario-id` through the same all-in-one workspace.
- Preserve current optimizer behavior while changing only composition.

Dependencies:

- Current route parser and query-state.
- Current workspace setup functions and results/tracking panels.

Deliverables:

- `setup_view.cljs` for `/portfolio/optimize/new`.
- `scenario_detail_view.cljs` for `/portfolio/optimize/:scenario-id`.
- Shared shell/header/tabs primitives.
- Query-state values for v4 `otab` with aliases for old values.
- View tests proving route dispatch and no all-in-one render for scenario detail.

Risks:

- Saved scenario route loading could regress if scenario detail assumes last-successful-run before route effect completes.
- Existing Playwright selectors may rely on data roles rendered in the old monolith.

Test strategy:

- Unit tests for route dispatch and query-state aliases.
- Existing optimizer route tests.
- Focused Playwright smoke: `/portfolio/optimize`, `/portfolio/optimize/new`, `/portfolio/optimize/:id?otab=recommendation`.

Exit criteria:

- `/portfolio/optimize/new` no longer appends results/rebalance/tracking by default.
- `/portfolio/optimize/:id` shows scenario-detail shell with tab bar.
- Existing optimizer run and saved scenario load still work.

### Phase 2 — Setup Workspace V4 Alignment

Goals:

- Align setup screen hierarchy with v4.
- Keep existing controls and actions, but make them contextual and tiered.

Dependencies:

- Phase 1 setup route split.
- Existing draft actions and readiness state.

Deliverables:

- Calm setup header with status tag, Save draft, Run optimization, overflow.
- Start with preset row: Conservative, Risk-adjusted, Use my views.
- Preset mutation policy: only objective and return-model mode change; universe and constraints are preserved.
- Left setup sections for Universe, Return / Risk model, Objective, Constraints.
- Center summary or Use my views explainer.
- Right assumptions rail or BL views editor.
- Advanced constraints and per-asset overrides collapsed/tiered.

Risks:

- Preset behavior can become magical if it silently overwrites user changes.
- Collapsing advanced controls can hide required V1 constraints unless summaries clearly show active values.

Test strategy:

- View tests for preset row, preset confirmation behavior, and preservation of universe/constraints.
- BL view tests still pass.
- Browser QA for default setup and Use my views setup.

Exit criteria:

- Setup no longer reads like a dumped form stack.
- Return model and risk model remain visually separate.
- Black-Litterman / Use my views appears contextually with editor and trust explanation.
- Run readiness and history load remain accessible.

### Phase 3 — Scenario Detail Scaffold, Provenance, And Tabs

Goals:

- Build the scenario-detail IA before refining individual tabs.
- Make auditability persistent and visible.

Dependencies:

- Phase 1 scenario detail view.
- Existing scenario metadata and run result state.

Deliverables:

- Scenario header with name/status/last run/actions.
- Provenance strip with objective, return model, risk model, horizon/lookback, funding, constraints, data as-of, scenario id, copy link.
- Tab bar: Recommendation, Rebalance preview, Tracking, Inputs.
- KPI strip across scenario detail.
- Stale/rerun state connected to last-successful-run request comparison.

Risks:

- Provenance can overstate data quality if fields are absent; unknowns must render honestly as unavailable, stale, or fallback.
- Header action enablement differs by draft/saved/executed/tracking status.

Test strategy:

- Unit tests for provenance view model with solved, stale, fallback funding, and missing history cases.
- View tests for status tags and action enablement.
- Browser screenshot of solved scenario detail.

Exit criteria:

- Scenario detail is understandable without scrolling into setup.
- Provenance and KPIs are visible above tab body.
- Query-backed tabs are shareable and reload safely.

### Phase 4 — Recommendation / Results Tab Alignment

Goals:

- Convert results from a stacked section into v4 recommendation surface.
- Preserve signed exposure, funding decomposition, diagnostics, binding constraints, and warnings.

Dependencies:

- Phase 3 detail scaffold.
- Existing result payloads and diagnostics.

Deliverables:

- Left allocation table grouped by asset with expandable leg rows.
- Current, target, delta, and notional columns with signed exposure semantics.
- Center efficient frontier SVG chart with current and target points, legend, hover/click affordance, and click/drag target behavior.
- Center exposure comparison visuals where available.
- Right diagnostics/trust/caution rail with concise top diagnostics and expandable more diagnostics.
- Funding decomposition visible in the recommendation surface, either as a table module or expandable diagnostic.

Risks:

- Grouping instruments by base asset can be wrong for namespaced assets if implemented with naive string parsing.
- Frontier chart interactions can duplicate run/model actions if not routed through existing action paths.

Test strategy:

- Pure tests for instrument grouping and display labels.
- View tests for signed rows, binding chips, funding decomposition, and diagnostics.
- Interaction test for frontier click/drag updating target objective parameters.
- Browser screenshot comparison to v4 results artboard.

Exit criteria:

- Results tab has no setup panels or rebalance table embedded in it.
- Frontier is a chart, not a point list.
- Diagnostics are first-class and readable without scrolling past setup.

### Phase 5 — Rebalance Preview Tab Alignment

Goals:

- Turn rebalance preview into a review-first tab.
- Keep execution integration honest and visible.

Dependencies:

- Phase 3 tabs.
- Existing `:rebalance-preview` result contract and execution modal/effect.

Deliverables:

- Rebalance tab header copy: review-only, no orders sent yet until confirmation/staging.
- Provenance from source scenario and preview generation context.
- Summary KPI strip: buys, sells, fees/slippage, funding/cost context, margin/cross-margin impact.
- Grouped trade table by asset with expandable leg rows and status/reason columns.
- Side panel explaining what the rebalance achieves and pre-staging cautions.
- Existing execution modal launched from this tab, with blocked rows clearly represented.

Risks:

- Current preview rows may not contain enough leg detail for true grouped drill-down; if so, use asset-level groups first and document the missing leg contract.
- Execution labels must not imply spot support when spot rows are blocked.

Test strategy:

- View tests for ready, blocked, partially blocked, and read-only preview states.
- Integration test for execution modal launch and disabled state when no ready rows exist.
- Browser screenshot for mixed ready/blocked scenario.

Exit criteria:

- Rebalance preview is no longer below recommendation results.
- Blocked reasons are visible per row and summarized.
- Review-only posture is explicit before execution/staging.

### Phase 6 — Tracking And Inputs Tabs

Goals:

- Make tracking a dedicated lifecycle surface.
- Add read-only scenario input audit.

Dependencies:

- Phase 3 tabs.
- Existing tracking snapshot model and scenario persistence.

Deliverables:

- Tracking tab with drift KPIs, drift table, tolerance visuals, realized-vs-target chart, why flagged panel, and rerun/preview actions.
- Tracking inactive state for saved/computed scenarios that can be manually tracked.
- Archived/read-only state behavior.
- Inputs tab as frozen read-only mirror of the solved request/draft, including universe, objective, return model, risk model, constraints, BL views, execution assumptions, and data freshness.
- Duplicate/fork affordance from Inputs if editing is desired.

Risks:

- Tracking data may be sparse in local-only review. Empty and partial states need to be explicit and useful.
- Inputs mirror must not mutate active draft accidentally.

Test strategy:

- View tests for inactive, active, drift breach, and archived tracking states.
- Regression test that Inputs tab controls are read-only and do not dispatch draft mutations.
- Browser screenshots for tracking and inputs.

Exit criteria:

- Tracking is not buried below rebalance.
- Scenario lifecycle is visible and understandable.
- Inputs audit can answer "what produced this run?" without navigating away.

### Phase 7 — Visual Primitive Normalization And Browser QA

Goals:

- Normalize v4 visual language across all optimizer surfaces.
- Produce screenshot artifacts suitable for product/design review.

Dependencies:

- Phases 1 through 6.

Deliverables:

- Shared optimizer primitive layer for panels, tables, tags, buttons, tab bar, KPIs, provenance, and chart shell.
- Removal or containment of rounded card/gutter patterns from dense optimizer surfaces.
- Updated Playwright/browser QA coverage.
- Screenshot artifact bundle for v4 parity review.

Risks:

- Over-normalizing global styles can break non-optimizer portfolio pages.
- Visual QA can become subjective unless acceptance screenshots and source artboards are listed explicitly.

Test strategy:

- `npm run check`, `npm test`, and `npm run test:websocket`.
- Smallest relevant Playwright optimizer command first, then broader browser regression if it passes.
- Browser QA accounting for visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes.
- Width checks at 375, 768, 1280, and 1440.

Exit criteria:

- Primary desktop flow no longer presents as one all-in-one scroll page.
- Screenshot set materially matches v4 IA, layout hierarchy, and visual language.
- No non-optimizer route visual regressions are observed in smoke checks.

## Concrete Steps

1. Start Phase 1 by extracting shared header/tab/provenance/KPI primitives without changing route output.
2. Add query-state aliases for v4 scenario tabs and tests.
3. Create `setup_view.cljs` and `scenario_detail_view.cljs`, initially reusing existing setup/results/tracking renderers.
4. Update `optimize/view.cljs` dispatch.
5. Run focused route/view/query-state tests and a minimal Playwright optimizer smoke.
6. Commit Phase 1.
7. Implement each subsequent phase as a separate commit or small PR slice, updating Progress and Decision Log after each phase.
8. After Phase 7, move the ExecPlan to `docs/exec-plans/completed/` only if implementation, screenshots, and validation pass.

## Validation And Acceptance

General validation for any code-changing phase:

- `npm run check`
- `npm test`
- `npm run test:websocket`

UI-specific validation:

- Use the smallest relevant Playwright command first for the changed optimizer route.
- Account for all browser QA passes from `docs/FRONTEND.md` and `docs/agent-guides/browser-qa.md`.
- Capture screenshots for all major surfaces and include them under the plan artifacts directory or a linked review bundle.

Major acceptance criteria:

- `/portfolio/optimize` is a landing/history surface, not a setup/results hybrid.
- `/portfolio/optimize/new` is setup-only and uses the v4 preset row and grouped/tiered setup panels.
- `/portfolio/optimize/:scenario-id` renders scenario header, provenance strip, scenario tabs, KPI strip, and tab-specific body.
- Recommendation tab includes signed grouped allocation, funding decomposition, diagnostics, and actual frontier chart placement.
- Rebalance preview is its own review surface/tab with summary KPIs, grouped rows, blocked reasons, and honest execution/staging state.
- Tracking is its own surface/tab and is not buried below rebalance.
- Inputs tab exists as read-only audit mirror.
- Stale/rerun/provenance behaviors are visible and preserve the last successful run.
- Visual density, tables, tags, buttons, panels, and accents are materially closer to v4 than the current scroll screenshots.
- Existing optimizer domain behavior is preserved unless an intentional, tested data-contract adjustment is documented in Decision Log.

## Browser QA And Artifact Plan

Capture these screenshots at minimum:

- `portfolio-optimizer-v4-landing-1440.png` for `/portfolio/optimize`.
- `portfolio-optimizer-v4-setup-default-1440.png` for `/portfolio/optimize/new`.
- `portfolio-optimizer-v4-setup-views-1440.png` for `/portfolio/optimize/new` with Use my views selected.
- `portfolio-optimizer-v4-recommendation-1440.png` for `/portfolio/optimize/:id?otab=recommendation`.
- `portfolio-optimizer-v4-rebalance-1440.png` for `/portfolio/optimize/:id?otab=rebalance`.
- `portfolio-optimizer-v4-tracking-1440.png` for `/portfolio/optimize/:id?otab=tracking`.
- `portfolio-optimizer-v4-inputs-1440.png` for `/portfolio/optimize/:id?otab=inputs`.
- Width spot checks at 1280, 768, and 375 for setup and scenario detail.

Compare against these design artboards:

- `v4 setup default`
- `v4 setup views`
- `v4 results`
- `v4 rebalance`
- `v4 tracking`
- `v4 mobile/states`
- `lofi landing` for landing/history IA because v4 has no high-fidelity landing replacement beyond IA/lo-fi.

"Close enough" for this pass:

- The route/screen decomposition matches v4.
- The primary visible hierarchy matches v4.
- Data surfaces are real implementation data, not copied placeholders.
- Visual primitives are materially normalized: table density, thin borders, compact tags, compact buttons, mono numerics, restrained accent use.
- Minor pixel differences are acceptable if the product mental model is correct and HyperOpen shell conventions are preserved.

## Idempotence And Recovery

- The refactor should be staged so each phase can be reverted independently.
- Keep data view-model helpers pure so tests can lock behavior before visual composition changes.
- Preserve existing data roles during migrations where practical. Add new v4 data roles rather than deleting old roles until Playwright coverage is updated.
- Do not remove `workspace_view.cljs` until setup and scenario detail routes have focused tests and browser smoke coverage.
- If a phase exposes a missing data contract, add the smallest pure view-model fallback first. Only change application/domain contracts after documenting the need in Decision Log.
- If the visual primitive layer leaks into non-optimizer pages, revert global style changes and move optimizer styles under a route-local class or CLJS class-vector helpers.

## Interfaces And Dependencies

State:

- `:portfolio :optimizer :draft`
- `:portfolio :optimizer :last-successful-run`
- `:portfolio :optimizer :active-scenario`
- `:portfolio :optimizer :scenario-index`
- `:portfolio :optimizer :tracking`
- `:portfolio :optimizer :run-state`
- `:portfolio :optimizer :history-load-state`
- `:portfolio-ui :optimizer`

Actions/effects to preserve:

- `:actions/run-portfolio-optimizer-from-draft`
- `:actions/save-portfolio-optimizer-scenario-from-current`
- `:actions/load-portfolio-optimizer-route`
- `:actions/open-portfolio-optimizer-execution-modal`
- `:actions/confirm-portfolio-optimizer-execution`
- `:actions/refresh-portfolio-optimizer-tracking`
- `:actions/enable-portfolio-optimizer-manual-tracking`
- Universe, BL, objective, return-model, risk-model, constraint, and execution-assumption draft actions.

View dependencies:

- Existing portfolio shell remains `hyperopen.views.portfolio-view`.
- Optimizer-specific shell remains under `hyperopen.views.portfolio.optimize`.
- Browser persistence remains under existing optimizer infrastructure and IndexedDB registration.
- Worker execution remains under existing optimizer worker target.

## Risks / Unresolved Decisions

1. Exact visual token mapping between v4 amber accent and current HyperOpen green primary needs owner/design confirmation before any global token changes.
   Recommendation: implement optimizer-local primitives first; do not change global Tailwind/DaisyUI primary in this pass.

2. Grouping spot/perp/cash rows by base asset can be lossy for namespaced instruments.
   Recommendation: create a tested optimizer display/grouping helper that understands current instrument ids instead of using ad hoc string splitting in view code.

3. The design shows a real frontier chart and hover/click interactions, while the current implementation only renders point buttons.
   Recommendation: implement a pure SVG chart in CLJS using existing frontier points, current metrics, and target metrics. Avoid adding a charting dependency unless SVG proves insufficient.

4. Rebalance preview may not yet have true leg-level detail for all execution paths.
   Recommendation: group by asset using current rows first and render expandable leg detail only where row data is present. Do not invent fake legs.

5. Inputs tab needs to be read-only and tied to the solved request, not the mutable draft.
   Recommendation: derive inputs from saved scenario `:config` and `:saved-run :request-signature :request` where available; show explicit fallback if only mutable draft data exists.

6. Browser screenshot parity is qualitative unless artifacts are versioned.
   Recommendation: store the final QA screenshots under an ExecPlan artifact directory and reference the v4 artboard names in the QA notes.

## Non-Goals

- Do not implement a new optimizer engine.
- Do not change solver strategy.
- Do not implement a scenario compare route.
- Do not create a second app shell.
- Do not replace real optimizer data with v4 mock values.
- Do not make mobile execution-capable. Mobile remains read/light-edit/review only per v4 notes.
- Do not reduce the product into a consumer robo-advisor. Preserve power-user controls, signed exposure, funding decomposition, diagnostics, and explicit stale/rerun states.

## Artifacts And Notes

Design artifacts inspected:

- `/tmp/hyperopen-optimizer-design-v4/v4.jsx`
- `/tmp/hyperopen-optimizer-design-v4/Portfolio Optimizer.html`
- `/tmp/hyperopen-optimizer-design-v4/DESIGN.md`
- `/tmp/hyperopen-optimizer-design-v4/styles.css`
- `/tmp/hyperopen-optimizer-design-v4/wireframes.jsx`
- `/tmp/hyperopen-optimizer-design-v4/states.jsx`

Current implementation evidence inspected:

- `src/hyperopen/views/portfolio/optimize/view.cljs`
- `src/hyperopen/views/portfolio/optimize/workspace_view.cljs`
- `src/hyperopen/views/portfolio/optimize/index_view.cljs`
- `src/hyperopen/views/portfolio/optimize/results_panel.cljs`
- `src/hyperopen/views/portfolio/optimize/frontier_chart.cljs`
- `src/hyperopen/views/portfolio/optimize/tracking_panel.cljs`
- `src/hyperopen/portfolio/routes.cljs`
- `src/hyperopen/portfolio/optimizer/query_state.cljs`
- `src/hyperopen/views/portfolio-view.cljs`
- `tailwind.config.js`
- `src/styles/main.css`

Screenshot evidence:

- `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-current-view-2026-04-26.png`
- `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-full-page-2026-04-26.png`
- `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-scroll-sequence-2026-04-26/portfolio-optimizer-scroll-01.png`
- `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-scroll-sequence-2026-04-26/portfolio-optimizer-scroll-04.png`
- `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-scroll-sequence-2026-04-26/portfolio-optimizer-scroll-06.png`
- `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-scroll-sequence-2026-04-26/portfolio-optimizer-scroll-07.png`
