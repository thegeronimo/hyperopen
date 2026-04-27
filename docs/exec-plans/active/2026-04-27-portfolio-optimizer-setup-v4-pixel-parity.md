owner: product+platform
status: active
source_of_truth: true
tracked_issue: hyperopen-g0m6
based_on:
  - /Users/barry/Downloads/hyperopen portfolio optimizer/v4.jsx
  - /var/folders/dg/3nkyzrp12fn141vv7f6rc9v40000gn/T/TemporaryItems/NSIRD_screencaptureui_WvbRPz/Screenshot 2026-04-27 at 11.47.13 AM.png
  - /Users/barry/.codex/worktrees/d394/hyperopen/docs/exec-plans/active/2026-04-26-portfolio-optimizer-v4-alignment.md

# Portfolio Optimizer Setup V4 Pixel Parity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while the work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. It is self-contained so an engineer can execute the work without relying on the conversation that produced it.

Tracked issue: `hyperopen-g0m6` ("Align optimizer setup page to v4 pixel parity").

## Purpose / Big Picture

Bring the editable setup route at `/portfolio/optimize/new` materially closer to the v4 designer layout while preserving the existing HyperOpen application header, footer, optimizer run logic, history-loading behavior, scenario persistence, and route state. A user should be able to open `/portfolio/optimize/new` and see a dense, desktop-first optimizer workspace that resembles the v4 screenshot: a calm scenario header with Save draft and Run optimization actions, a three-card "Start with" preset row, a left control rail with numbered sections, a central scenario summary, and a right context rail. The route should still run an optimization with one button and still show progress, readiness, and run status.

This is not a solver pass and not a global shell pass. The designer's `Topbar`, `PortfolioSubnav`, and bottom `Statusbar` must not be ported because HyperOpen already owns those surfaces.

## Progress

- [x] (2026-04-27 16:16Z) Created and claimed tracked issue `hyperopen-g0m6`.
- [x] (2026-04-27 16:18Z) Audited `v4.jsx`, the attached screenshot, current setup route code, current optimizer panel components, and existing tests.
- [x] (2026-04-27 16:20Z) Wrote this active ExecPlan.
- [x] (2026-04-27 16:40Z) Implemented the setup route layout decomposition and kept existing optimizer actions wired. `workspace_view.cljs` is now a small composition layer and the v4 setup body is split across `setup_v4_sections.cljs` and `setup_v4_context.cljs`.
- [x] (2026-04-27 16:47Z) Added focused layout regression tests in `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` without growing the capped legacy `view_test.cljs`.
- [x] (2026-04-27 17:05Z) Ran repo gates and optimizer browser QA. `npm run check`, `npm test`, `npm run test:websocket`, and `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer"` passed.
- [ ] Optional follow-up: capture a designer-facing screenshot comparison if product wants another manual pixel-tuning pass after reviewing the implemented browser surface.

## Surprises & Discoveries

- Observation: The current setup view is already 607 lines and `test/hyperopen/views/portfolio/optimize/view_test.cljs` is exactly 640 lines.
  Evidence: `wc -l src/hyperopen/views/portfolio/optimize/workspace_view.cljs src/hyperopen/views/portfolio/optimize/universe_panel.cljs test/hyperopen/views/portfolio/optimize/view_test.cljs` reported `607`, `132`, and `640`.

- Observation: The current setup route already has correct optimizer actions and product behavior that must be preserved rather than rebuilt.
  Evidence: `workspace_view.cljs` wires `run-portfolio-optimizer-from-draft`, `save-portfolio-optimizer-scenario-from-current`, objective/return/risk setters, one-click run progress, infeasible highlighting, readiness, status, and Black-Litterman views. The visual hierarchy is the issue.

- Observation: The designer source includes global navigation, subnavigation, footer/statusbar, and annotation callouts that should not ship in this pass.
  Evidence: `v4.jsx` defines `Topbar`, `PortfolioSubnav`, `Statusbar`, and annotation notes, but the user explicitly asked not to change HyperOpen header/footer.

- Observation: V4 setup wants a 420px left rail, flexible center, and about 380px right rail; the current setup route uses a `260px / 1fr / 280px` grid with a scenario navigation rail.
  Evidence: `workspace_view.cljs` root grid currently uses `xl:grid-cols-[260px_minmax(0,1fr)_280px]` and renders `portfolio-optimizer-left-rail`.

- Observation: The refactor retired the previous `workspace_view.cljs` namespace-size exception.
  Evidence: `npm run check` initially failed with `[stale-size-exception] src/hyperopen/views/portfolio/optimize/workspace_view.cljs - namespace is now 79 lines; remove the stale exception entry`. Removing that entry made `npm run lint:namespace-sizes` and `npm run check` pass.

- Observation: Existing browser regressions intentionally encode a few legacy text contracts even after the visual layout changes.
  Evidence: The optimizer setup Playwright regression expected `Use Current Holdings`, `Black-Litterman`, and capitalized `Active`. The implementation keeps the visible v4 language while adding accessible legacy text where needed so the product and automation contracts both remain stable.

## Decision Log

- Decision: Implement this as a focused setup-route refactor, not by changing scenario detail, results, rebalance, tracking, header, or footer.
  Rationale: The latest user request and screenshot target the new optimization page below the app header. Scenario detail alignment has a separate active plan and should not be mixed into this pixel-parity slice.
  Date/Author: 2026-04-27 / Codex

- Decision: Create a new setup component namespace instead of adding more helpers to `workspace_view.cljs`.
  Rationale: The existing setup view is already large. A focused namespace makes the v4 control rail, summary pane, and context rail easier to review and keeps namespace-size checks manageable.
  Date/Author: 2026-04-27 / Codex

- Decision: Preserve existing data-role hooks for run, save, universe add/remove/search, objective, return model, risk model, constraints, readiness, run status, and progress.
  Rationale: Those hooks are used by existing unit and Playwright coverage. Pixel parity should not break working product behavior or browser regression selectors.
  Date/Author: 2026-04-27 / Codex

- Decision: Use amber/warning accents for v4 optimizer selection states inside the scoped optimizer surface while leaving the global HyperOpen primary green system unchanged.
  Rationale: The v4 screenshot uses amber as the optimizer decision accent and the existing active-plan visual pass already scoped optimizer-specific styling under `portfolio-optimizer-v4`.
  Date/Author: 2026-04-27 / Codex

## Outcomes & Retrospective

The setup route is now structurally closer to the v4 design. The old scenario-navigation left rail is gone from `/portfolio/optimize/new`; the page now renders a calm scenario header, the three-card preset row, a dense numbered control rail, a central summary pane, and a right context rail. Existing optimizer behavior was preserved: universe add/remove/search, Use Current Holdings, one-click Run Optimization, progress/status/readiness panels, objective/return/risk selection, constraints, infeasible highlighting, and Black-Litterman editing still use the existing action contracts.

The implementation reduced complexity in the primary setup entrypoint by shrinking `workspace_view.cljs` from 607 lines to 79 lines and removing its namespace-size exception. Complexity moved into two focused setup namespaces under the 500-line production threshold, which should make follow-up pixel tuning less risky. One optional follow-up remains: if the designer needs exact artboard-level parity, capture a fresh screenshot sequence and tune spacing/copy against that visual artifact.

## Context and Orientation

The editable optimizer setup route is rendered by `src/hyperopen/views/portfolio/optimize/workspace_view.cljs`. The route is reached through `/portfolio/optimize/new`; the global app header and footer come from the existing portfolio/app shell and must remain intact. The setup view currently renders a full-width setup header, a preset row, a left scenario/navigation rail, a main column containing universe/model/constraints sections, and a right trust/freshness rail. The core actions already work, but the layout does not match v4.

The designer's `v4.jsx` setup page uses this content order below the header: scenario breadcrumb/title/status/actions, "Start with" preset cards, then a desktop workspace grid. The workspace grid has a left control rail with numbered sections `01 Universe`, `02 Return / Risk model`, `03 Objective`, and `04 Constraints`; a central explanation pane with a `Summary` table and assumptions note; and a right rail that explains why the selected preset is safe or, when using Black-Litterman, hosts the views editor.

The existing `universe_panel.cljs` implements selected universe rows, manual add search, add/remove buttons, and Use Current Holdings. It is visually too wide for the v4 left rail, but its action contracts should be reused. Existing `black_litterman_views_panel.cljs` hosts real Black-Litterman inputs and should remain the functional editor if the return model is Black-Litterman. Existing `optimization_progress_panel.cljs`, `setup_readiness_panel.cljs`, and `run_status_panel.cljs` should remain mounted in the context rail so one-click run feedback remains visible.

## Plan of Work

Create `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs` to hold focused setup layout primitives. This namespace should render flat, dense, v4-like panels with small uppercase section headers, low-radius borders, tabular numeric values, amber active states, and compact rows. It must expose functions for the preset row, control rail, center summary, and context rail. It should not import or alter global shell components.

Update `workspace_view.cljs` so `/portfolio/optimize/new` uses the new v4 grid. Remove the fake setup left navigation rail from the setup route. Keep the run and save actions in the scenario header and keep compatibility buttons or roles only where tests require them. The header should show `Optimizer - portfolio / optimize / new`, `Untitled scenario - configure your target portfolio` for a new route, a `draft` or computed status tag, overflow affordance, Save draft, and Run optimization. The preset row should show `Conservative`, `Risk-adjusted`, and `Use my views` as three equal cards.

The new left control rail should show the current universe, manual add, return model, risk model, objective, and constraints as dense grouped panels. Preserve all existing action roles and events. It should keep return model and risk model separate, keep Black-Litterman as a return-model mode, and present constraints as compact rows while still keeping editable inputs available.

The center pane should show a `Summary` panel that tells the user what the scenario will solve for using current draft values. When the return model is Black-Litterman, add a contextual "Use my views" explainer and keep the real Black-Litterman editor visible in the workspace rather than hiding it behind the old long-scroll form.

The right context rail should show "Why this preset is safe" or Use-my-views context, and it should also contain the existing optimization progress, readiness, run status, and last-successful-run panels. Do not reintroduce execution assumptions into this view; the user already asked to remove execution assumptions until execution exists.

Add a new test file under `test/hyperopen/views/portfolio/optimize/` for v4 setup layout. The tests should render `/portfolio/optimize/new` and assert that the old `portfolio-optimizer-left-rail` is absent, the new control rail and summary/context rail are present, the preset actions still dispatch the existing preset action, run/save actions remain wired, and the main product controls still dispatch their existing optimizer actions.

Run `npm test`, `npm run check`, and `npm run test:websocket`. For browser QA, run the smallest optimizer Playwright regression that exercises setup route selectors. If the local dev server is already running, inspect `/portfolio/optimize/new` in browser and capture a screenshot only if useful for visual comparison.

## Concrete Steps

From `/Users/barry/.codex/worktrees/d394/hyperopen`, make the edits with `apply_patch`.

1. Add `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs` with reusable components.
2. Modify `src/hyperopen/views/portfolio/optimize/workspace_view.cljs` to require the new namespace and replace the setup route body with the v4 grid composition.
3. Add `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` for focused setup layout coverage.
4. Run:

       npm test
       npm run check
       npm run test:websocket

5. Run the smallest relevant browser regression after unit gates pass:

       npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer"

If a command fails, inspect the failure, update the implementation or tests, and rerun the failed command before broadening validation.

Completed validation transcript:

    npm run check
    # passed: docs, lint, namespace-size, namespace-boundary, release-assets, app/portfolio/worker/test compiles

    npm test
    # Ran 3583 tests containing 19614 assertions.
    # 0 failures, 0 errors.

    npm run test:websocket
    # Ran 461 tests containing 2798 assertions.
    # 0 failures, 0 errors.

    npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer"
    # 9 passed

## Validation and Acceptance

Acceptance is visual and behavioral. On `/portfolio/optimize/new`, the user should see a v4-like setup surface below the existing HyperOpen header and above the existing footer. The route must no longer have the old scenario-navigation left rail. The first content area must be a calm scenario header with the scenario breadcrumb, title, status tag, overflow affordance, Save draft, and Run optimization actions. The preset row must show three equal cards and highlight the active preset using the optimizer amber decision accent.

The workspace body must be a three-column desktop layout. The left column contains dense numbered control sections. The center column contains a summary of what the optimizer will solve for. The right column contains context plus progress/readiness/status panels. Running an optimization still uses a single Run Optimization button and still shows progress.

The implementation is accepted when the test suite passes, the setup-specific browser regression passes, and a manual browser inspection shows that the screen hierarchy is materially closer to the provided v4 screenshot without changing the global header/footer.

## Idempotence and Recovery

The edits are additive and local to the optimizer setup route. If a layout change breaks behavior, revert only the current patch hunks touching `workspace_view.cljs`, `setup_v4_sections.cljs`, or the new test file; do not reset unrelated optimizer commits. Running tests is safe to repeat. Browser QA may leave local browser sessions or generated screenshots; run `npm run browser:cleanup` if a Playwright or Browser MCP process remains.

## Artifacts and Notes

The explorer audit of `v4.jsx` identified these non-negotiable layout elements: preserve the app header/footer, use setup header to preset row to three-column workspace order, use cards for `Conservative`, `Risk-adjusted`, and `Use my views`, render left rail sections in order `Universe`, `Return / Risk model`, `Objective`, `Constraints`, keep return and risk models separate, render center `Summary`, and use a right rail for preset safety or view-editing context.

## Interfaces and Dependencies

The new namespace should expose pure view functions that return Hiccup:

    setup-header
    preset-row
    control-rail
    summary-pane
    context-rail

These functions receive the existing `state`, `route`, `draft`, `readiness`, `snapshot`, `preview-snapshot`, `run-state`, `optimization-progress`, `history-load-state`, `last-successful-run`, `saving-scenario?`, `running?`, `run-triggerable?`, and `highlighted-controls` values already computed by `workspace_view.cljs`. They should emit the existing action vectors directly and not add new application actions unless a behavior cannot otherwise be preserved.
