owner: product+platform
status: active
source_of_truth: true
tracked_issue: hyperopen-cwlf
based_on:
  - /Users/barry/Downloads/hyperopen portfolio optimizer/styles.css
  - /Users/barry/Downloads/hyperopen portfolio optimizer/v4.jsx
  - /Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-current-v4-diff-1294x885.png
  - /Users/barry/.codex/worktrees/d394/hyperopen/docs/exec-plans/active/2026-04-27-portfolio-optimizer-setup-v4-pixel-parity.md
  - /Users/barry/.codex/worktrees/d394/hyperopen/docs/exec-plans/active/2026-04-26-portfolio-optimizer-v4-alignment.md

# Portfolio Optimizer V4 Visual Parity Remediation

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while the work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. It is self-contained so an engineer can execute the work without relying on the conversation that produced it.

Tracked issue: `hyperopen-cwlf` ("Remediate portfolio optimizer v4 visual parity deltas").

## Purpose / Big Picture

Close the remaining visible setup-route gaps between the current `/portfolio/optimize/new` implementation and the v4 design without changing optimizer behavior. After this pass, a user should still be able to seed the universe, change models and constraints, save a draft, and run the optimizer exactly as before, but the page should read as a denser v4 workspace: a single compact header band, a tighter preset strip, a continuous left control rail, and a normalized right rail that matches the artboard’s border, spacing, and control primitives.

This is a visual remediation pass, not another information-architecture rewrite. The setup surface already has the right high-level composition. The job here is to convert the latest screenshot-diff findings into reviewable implementation slices that preserve existing action vectors, route state, and optimizer semantics.

## Progress

- [x] (2026-04-27 17:39Z) Created and claimed tracked issue `hyperopen-cwlf` as a follow-up discovered from `hyperopen-g0m6`.
- [x] (2026-04-27 17:43Z) Audited the v4 design CSS and JSX, the current implementation screenshot, the active optimizer parity plans, and the current setup route seams in `workspace_view.cljs`, `setup_v4_sections.cljs`, `setup_v4_context.cljs`, `main.css`, and existing tests.
- [x] (2026-04-27 17:50Z) Wrote this active ExecPlan.
- [x] (2026-04-27 17:56Z) Ran `npm run check` and captured the current namespace-size guard failure for `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs`.
- [x] (2026-04-27 17:58Z) Slice 1: added scoped v4 tokens and control primitives under `.portfolio-optimizer-v4`, including neutral surfaces, thin borders, 2px radii, denser controls, amber primary CTA treatment, and scoped selected-state behavior.
- [x] (2026-04-27 18:02Z) Slice 2: normalized the setup header and preset strip while preserving existing save, run, view-weights, and preset action vectors. The large visible draft-state row is now screen-reader-only compatibility text.
- [x] (2026-04-27 18:07Z) Slice 3: collapsed the main setup surface into a no-gutter three-pane grid, made the left rail continuous, converted return/risk controls to segmented controls, compacted constraints, and tightened manual-add rows.
- [x] (2026-04-27 18:11Z) Slice 4: normalized the right rail so idle setup shows preset guidance first, while trust/readiness/progress/run-status panels appear only when history, run, warning, retained result, or read-only context makes them actionable.
- [x] (2026-04-27 18:16Z) Slice 5: captured a fresh browser screenshot artifact at `tmp/portfolio-optimizer-v4-remediation-1294x885.png` and ran the optimizer Playwright regression subset plus the history-network smoke.
- [x] (2026-04-27 18:25Z) Validation completed: `npm run check`, `npm test`, `npm run test:websocket`, `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer"`, `npx playwright test tools/playwright/test/optimizer-history-network.qa.spec.mjs`, and `npm run browser:cleanup` passed.
- [x] (2026-04-27 18:49Z) Follow-up pass: extracted `setup_v4_universe.cljs`, replaced the generic manual-add list with a v4-style custom search block, search-result rows, quick-add chips, and included-instrument table treatment; tightened the route grid to `380px / 1fr / 340px`; captured `tmp/portfolio-optimizer-v4-pass2-1294x885.png`.
- [ ] Await product/design review of `tmp/portfolio-optimizer-v4-pass2-1294x885.png` against the v4 artboard before moving this ExecPlan out of `active`.

## Surprises & Discoveries

- Observation: The setup route is already decomposed into the correct route-local seams for a visual-only follow-up.
  Evidence: `src/hyperopen/views/portfolio/optimize/workspace_view.cljs` already composes `setup_v4_sections.cljs`, `setup_v4_context.cljs`, and the scoped `portfolio-optimizer-v4` wrapper, so no new route split is required.

- Observation: The current screenshot shows the remaining gap is density and border treatment, not missing setup sections.
  Evidence: `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-current-v4-diff-1294x885.png` already includes the header, preset row, left rail, summary pane, and right rail, but the header has an extra secondary row, the left column is broken into separated cards, and the right rail still reads as mixed panel primitives.

- Observation: The current optimizer CSS wrapper is intentionally light and does not yet encode most of the v4 primitive system.
  Evidence: `src/styles/main.css` only scopes accent colors, panel backgrounds, and a few radius and selected-state overrides, while `/Users/barry/Downloads/hyperopen portfolio optimizer/styles.css` defines the deeper v4 primitive set: 2px radii, 26px controls, dense mono labels, thin borders, tabular numerics, and continuous pane dividers.

- Observation: The v4 artboard uses one continuous left column and one continuous right column, not a stack of independent cards separated by large gutters.
  Evidence: `v4.jsx` renders the setup workspace as `420px 1fr 380px`, with section dividers inside the left and right columns. The current implementation uses `space-y-3` stacks for both rails.

- Observation: The v4 artboard includes a preset-switch confirmation row, but preserving the current immediate preset action is safer for this remediation pass.
  Evidence: `v4.jsx` contains an inline confirmation row for preset changes after local deviations. The current setup flow immediately dispatches `:actions/apply-portfolio-optimizer-setup-preset`, and the user explicitly asked to preserve existing optimizer behavior.

- Observation: `setup_v4_sections.cljs` is already over the namespace-size guard, so a naive parity edit will fail repo validation before behavior is even reviewed.
  Evidence: `npm run check` currently stops at `[missing-size-exception] src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs - namespace has 550 lines; add an exception entry in dev/namespace_size_exceptions.edn`.

- Observation: The size failure was resolved by extraction, not by adding a namespace-size exception.
  Evidence: `setup-header` and `preset-row` moved to `src/hyperopen/views/portfolio/optimize/setup_v4_header.cljs`; `wc -l` now reports `448` lines for `setup_v4_sections.cljs` and `114` lines for `setup_v4_header.cljs`, and `npm run check` reports `Namespace size check passed`.

- Observation: Several existing tests and browser regressions intentionally assert legacy product labels such as `Historical Mean`, `Diagonal Shrink`, `Max Asset Weight`, and `Draft clean`.
  Evidence: The visible v4 labels now use denser wording such as `Historical`, `Stabilized Covariance`, and `Per-asset cap`; screen-reader-only compatibility text preserves the old strings without reintroducing the larger visual treatment.

- Observation: Default right-rail readiness and run-status cards were the source of visible operational noise.
  Evidence: The default setup screenshot now shows only `Why this preset is safe` in the right rail. The readiness and run-status panels still render when history is loading, failed, succeeded, stale/incomplete, or when a run has started, failed, or completed.

- Observation: The largest remaining visible setup delta was the universe builder, not the summary or model controls.
  Evidence: The second parity pass moved universe rendering into `setup_v4_universe.cljs` and now matches the designer's mental model more closely: mode tabs, search bar with `↵ add`, query results above the included table, semantic spot/perp/history tags, quick-add chips for empty search, and compact included rows.

## Decision Log

- Decision: Keep this plan scoped to `/portfolio/optimize/new` and explicitly exclude scenario detail, recommendation, rebalance, and tracking surfaces.
  Rationale: The screenshot and required slices target the setup route only. Reopening detail-route parity would turn a narrow remediation pass back into a broad optimizer UI program.
  Date/Author: 2026-04-27 / Codex

- Decision: Express the v4 primitive system through selectors scoped under `.portfolio-optimizer-v4` instead of changing global app tokens or Tailwind theme values.
  Rationale: The v4 amber, radius, and dense control treatment are optimizer-specific in this pass. Global theme changes would create unrelated risk outside the setup route.
  Date/Author: 2026-04-27 / Codex

- Decision: Preserve all existing optimizer action vectors and data-role hooks for universe, model, constraint, save, and run controls.
  Rationale: The prior parity slices already stabilized those hooks in unit tests and Playwright. This pass should be visual-first and behavior-preserving.
  Date/Author: 2026-04-27 / Codex

- Decision: Treat the v4 preset-switch confirmation row as out of scope unless preserving behavior becomes impossible without it.
  Rationale: The current implementation applies presets immediately and the user asked to preserve optimizer behavior. Introducing a new confirmation flow would be product behavior work, not narrow parity remediation.
  Date/Author: 2026-04-27 / Codex

- Decision: Use deterministic Playwright-driven screenshot capture at the setup route as the visual acceptance artifact.
  Rationale: A parity pass needs an observable before/after artifact. The existing committed Playwright optimizer regressions cover behavior, while a saved same-size screenshot artifact keeps visual review repeatable without introducing brittle pixel snapshot assertions.
  Date/Author: 2026-04-27 / Codex

- Decision: Treat the current `setup_v4_sections.cljs` namespace-size failure as a real implementation constraint and prefer extracting smaller setup view namespaces over adding a new long-lived exception.
  Rationale: This pass will add or move view structure in the same area that already fails `npm run check`. Hiding that growth behind another exception would increase future review cost and make later parity iterations harder.
  Date/Author: 2026-04-27 / Codex

- Decision: Extract setup header and preset rendering into `setup_v4_header.cljs`.
  Rationale: This keeps the main setup sections namespace under the repo size limit and gives future pixel-tuning work a smaller file focused only on the top setup band.
  Date/Author: 2026-04-27 / Codex

- Decision: Keep readiness/run-status hidden in the idle, empty-universe default state.
  Rationale: The v4 artboard's right rail is explanatory by default. Status cards remain available for actionable states, but hiding idle status reduces the "operations dashboard" feel called out by visual review.
  Date/Author: 2026-04-27 / Codex

- Decision: Capture the parity screenshot as an artifact instead of adding brittle screenshot snapshot assertions in this pass.
  Rationale: Existing Playwright tests cover behavior and stable selectors. A saved artifact is sufficient for human visual review without introducing pixel-snapshot churn while the design is still being tuned.
  Date/Author: 2026-04-27 / Codex

- Decision: Extract the universe builder into `setup_v4_universe.cljs` instead of continuing to grow `setup_v4_sections.cljs`.
  Rationale: The universe custom-flow parity work needs enough helper functions for candidate sorting, tag rendering, quick chips, and included rows that keeping it in the section namespace would make the file harder to review and risk reintroducing the namespace-size failure.
  Date/Author: 2026-04-27 / Codex

## Outcomes & Retrospective

This pass materially tightened `/portfolio/optimize/new` against the v4 setup artboard while preserving optimizer behavior. The setup surface now uses scoped neutral v4 tokens, a compact header without a visible draft-state row, a filled amber Run Optimization CTA, a tighter preset strip, a no-gutter three-pane workspace, a continuous left control rail, segmented return/risk controls, compact editable constraints, v4-style universe search/results/included rows, and an idle right rail focused on preset guidance instead of default status dashboards.

The implementation reduced local complexity by extracting `setup_v4_header.cljs` and `setup_v4_universe.cljs` from the previously oversized `setup_v4_sections.cljs`. It increased CSS specificity inside `.portfolio-optimizer-v4`, but that complexity is intentionally route-scoped and prevents global HyperOpen header/footer or unrelated route drift. Remaining visual gaps are now narrower: the custom search/results model is structurally aligned, but exact pixel parity still depends on final production data density, the current app shell gutters, and the right-rail status behavior when readiness warnings are legitimately actionable.

## Context and Orientation

The setup route is rendered by `src/hyperopen/views/portfolio/optimize/workspace_view.cljs`. That namespace already calculates the optimizer draft, readiness, current-portfolio preview, run state, and result-link context, then composes the setup surface from helper namespaces. `src/hyperopen/views/portfolio/optimize/setup_v4_header.cljs` owns the compact setup header and preset row. `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs` owns the left control rail and center summary pane. `src/hyperopen/views/portfolio/optimize/setup_v4_context.cljs` owns the right rail, including the preset rationale copy, the Black-Litterman editor, trust and freshness messaging, optimization progress, readiness, run status, and the results link. `src/styles/main.css` contains the route-local `portfolio-optimizer-v4` style wrapper that scopes the v4 look.

For this plan, "visual parity remediation" means closing the remaining deltas in spacing, border treatment, rail continuity, and control density while leaving optimizer logic intact. The design source of truth is `/Users/barry/Downloads/hyperopen portfolio optimizer/v4.jsx` plus `/Users/barry/Downloads/hyperopen portfolio optimizer/styles.css`. The screenshot source of truth for the current state is `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-current-v4-diff-1294x885.png`.

The current screenshot identifies five concrete deltas that align to the requested slices. The header still reads as two stacked bands because the draft-state badge creates a second visual row. The preset strip is close but does not fully match the v4 control density and selected-state treatment. The left rail is composed from four boxed panels plus a detached advanced-overrides box instead of a denser continuous column. The right rail still mixes separate cards and nested panels instead of one normalized stacked shell. The scoped CSS wrapper does not yet encode the full v4 primitive system needed to make those surfaces look consistent.

## Risks and Mitigations

The largest product risk is accidental behavior drift while restyling working controls. Mitigate that by keeping all existing data roles and action vectors unchanged and by extending `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` instead of replacing it.

The largest visual risk is over-correcting through global CSS. Mitigate that by keeping every new selector under `.portfolio-optimizer-v4` and by avoiding changes to global Tailwind config or generic app shell classes.

The main QA risk is calling the pass "done" based on structural tests alone. Mitigate that by adding a deterministic Playwright screenshot capture at the setup route and comparing it against the current screenshot inventory at the same desktop footprint before accepting the slice.

The main scope risk is expanding into new preset behavior, new summary content, or scenario-detail polish. Mitigate that by treating the required slices as the only implementation slices in this plan and recording any additional deltas as separate `bd` follow-ups instead of folding them into this pass.

The main implementation risk is that `setup_v4_sections.cljs` already fails the namespace-size gate before new parity work begins. Mitigate that by extracting focused helper namespaces as part of the header/preset or left-rail slices instead of growing the existing file and then backfilling an exception.

## Plan of Work

### Slice 1: Scoped V4 Tokens and Control Primitives

Extend `src/styles/main.css` only inside `.portfolio-optimizer-v4` so the setup route can express the v4 primitive system locally. Add the missing optimizer-scoped custom properties and primitive selectors needed for the artboard: tighter 2px-like radii, 26px control heights, denser mono eyebrows, table and tag treatment, tabular numerics, softer panel surfaces, stronger divider borders, and consistent amber selected-state styling. Do not move these tokens into global theme variables and do not restyle unrelated routes.

Use this slice to add only the class hooks or `data-role` hooks that the primitives need in `setup_v4_sections.cljs` and `setup_v4_context.cljs`. This slice should not change control wiring or layout ownership on its own. Its job is to create the visual vocabulary the later slices reuse.

### Slice 2: Setup Header and Preset Strip

Refine `setup-header` and `preset-row`, now extracted to `src/hyperopen/views/portfolio/optimize/setup_v4_header.cljs`, so the top of `/portfolio/optimize/new` matches the v4 artboard more closely. The header should read as one compact band with the eyebrow, title, subtitle, status tag, overflow button, Save draft, and Run optimization actions on the same visual plane. The current secondary draft-state row should either be removed from the default scan or collapsed into the primary header treatment so the setup surface no longer feels double-stacked.

The preset row should keep the existing three presets and action contract, but it should adopt the v4 card density, selected radio glyph treatment, default tag placement, and label-column spacing from `v4.jsx`. Do not introduce a new preset confirmation flow in this slice. Preserve the current `:actions/apply-portfolio-optimizer-setup-preset` behavior. If the header and preset changes push `setup_v4_sections.cljs` further over the size limit, extract a focused setup-header or preset namespace instead of adding a new exception.

### Slice 3: Dense Continuous Left Rail

Rework `control-rail` in `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs` so the left column reads as one dense continuous rail rather than a stack of separate cards. The `Universe`, `Return / Risk Model`, `Objective`, and `Constraints` sections should keep their current numbered order and action hooks, but they should share one outer rail shell or an equivalent divider treatment with reduced vertical guttering. The `Advanced Overrides` disclosure may remain collapsed and secondary, but it should no longer visually pull the setup surface apart.

Within this rail, tighten the toggle rows, universe table/search/add rows, objective cards, and constraint rows to the v4 density. Preserve the existing universe add/remove/search behavior, current-holdings seed action, model selectors, objective actions, and constraint input actions. This slice is accepted only if the rail looks denser and more continuous while the view tests still prove the same control actions exist. If that work makes the current section namespace harder to validate, extract a dedicated left-rail namespace rather than widening the existing file further.

### Slice 4: Right-Rail Normalization

Normalize `src/hyperopen/views/portfolio/optimize/setup_v4_context.cljs` so the right column uses one consistent shell for both non-Black-Litterman and Black-Litterman states. In the idle default state, the top section should present the "Why this preset is safe" copy in the same v4 style as the design artboard without default readiness or run-status cards. In actionable states, trust and freshness, readiness, optimization progress, run status, last successful run, and the results link should appear below with shared divider and spacing treatment. In the Black-Litterman state, the existing `black_litterman_views_panel` should occupy the top context section without displacing the conditional status stack below it.

This slice should not remove any working status panels or the results link. The normalization is visual and compositional: fewer mixed card styles, tighter spacing, and a stable right-column hierarchy across both states.

### Slice 5: Browser Screenshot Parity Validation

Use the existing optimizer Playwright regression path to exercise `/portfolio/optimize/new` and capture a deterministic manual screenshot artifact at the same desktop footprint used by the screenshot inventory. Reuse existing optimizer fixtures where possible, wait for the setup route surface to settle, and record the screenshot artifact path in this plan so reviewers can compare it directly against `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-current-v4-diff-1294x885.png`.

This slice does not require screenshot snapshot testing for the entire route shell. It requires passing committed browser regressions plus one saved artifact that proves the targeted header, preset strip, left rail, and right rail deltas were actually closed.

## Concrete Steps

From `/Users/barry/.codex/worktrees/d394/hyperopen`, make the edits with `apply_patch`.

1. Update `src/styles/main.css` inside the `.portfolio-optimizer-v4` scope with the missing v4 tokens and primitive selectors required by the setup surface.
2. Add `src/hyperopen/views/portfolio/optimize/setup_v4_header.cljs` for the compact header and preset strip, and update `workspace_view.cljs` to call it.
3. Update `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs` to tighten the left rail while preserving existing action hooks.
4. Update `src/hyperopen/views/portfolio/optimize/setup_v4_context.cljs` to normalize the right rail for both default and Black-Litterman states.
5. Extend `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` and `tools/playwright/test/portfolio-regressions.spec.mjs` only where the v4 idle-state contract changed.
6. Run:

       npm run check
       npm test
       npm run test:websocket
       npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer"
       npx playwright test tools/playwright/test/optimizer-history-network.qa.spec.mjs

7. If Playwright or Browser MCP leaves a browser session open, run:

       npm run browser:cleanup

If any gate fails, fix the specific slice and rerun the failed command before broadening the validation surface.

## Validation and Acceptance

Acceptance must stay behavior-first and screenshot-backed.

- `src/styles/main.css` only introduces selectors and variables scoped under `.portfolio-optimizer-v4`; no diff hunk changes global theme tokens, generic shell classes, or non-optimizer route selectors. This is accepted by reviewing the CSS diff and confirming that only optimizer setup surfaces change at runtime.

- At `/portfolio/optimize/new`, the top surface renders as one compact header band followed immediately by the preset strip. The header still exposes `portfolio-optimizer-setup-overflow`, `portfolio-optimizer-save-scenario`, and `portfolio-optimizer-run-draft`, and `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` still proves those controls keep their current action vectors.

- The preset strip still renders the three existing presets in the same order, still dispatches `:actions/apply-portfolio-optimizer-setup-preset`, and visually matches the v4 density more closely through tighter card spacing, selected amber treatment, and label-column alignment. The acceptance check is the browser surface plus the existing layout test contract, not a new preset behavior.

- The left column at `data-role="portfolio-optimizer-setup-control-rail"` reads as one dense continuous rail with numbered sections `01` through `04`, and it still exposes the existing universe, search, add, remove, objective, return-model, risk-model, and constraint controls. Acceptance requires both the updated view test coverage and a browser inspection at the setup route that no longer shows the current large inter-panel gaps.

- The right column at `data-role="portfolio-optimizer-right-rail"` uses one normalized hierarchy in both default and Black-Litterman states. In the idle default state it shows preset-safety guidance without default readiness/run-status dashboard cards. In actionable states it still renders trust, readiness, progress, run-status, last-successful-run, read-only warning, and result-link content. In Black-Litterman state it renders the existing views editor in the top context section while retaining the conditional status stack below.

- `npm run check`, `npm test`, and `npm run test:websocket` all pass from `/Users/barry/.codex/worktrees/d394/hyperopen`, and the setup-route parity work does not rely on a new long-lived namespace-size exception for `setup_v4_sections.cljs`.

- `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer"` and `npx playwright test tools/playwright/test/optimizer-history-network.qa.spec.mjs` pass, and the desktop screenshot artifact at `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-v4-remediation-1294x885.png` visibly improves header density, preset strip, left-rail continuity, and right-rail normalization compared with `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-current-v4-diff-1294x885.png`.

## Idempotence and Recovery

These changes are local to optimizer-scoped CSS, setup view composition, and targeted tests. Reapplying or rerunning the commands is safe. If a slice introduces visual regressions, revert only the affected hunks in `src/styles/main.css`, `setup_v4_sections.cljs`, `setup_v4_context.cljs`, `setup_v4_layout_test.cljs`, or the targeted Playwright test. Do not reset unrelated optimizer work or broader app-shell styling.

Browser screenshot capture can leave local artifacts or browser processes behind. Keep the artifact path recorded in this plan, and use `npm run browser:cleanup` after exploratory or deterministic browser work if anything remains open.

## Artifacts and Notes

The primary design reference for this pass is the setup block in `/Users/barry/Downloads/hyperopen portfolio optimizer/v4.jsx` beginning at the compact header, the preset row, and the `420px 1fr 380px` workspace grid. The primary CSS reference is `/Users/barry/Downloads/hyperopen portfolio optimizer/styles.css`, especially the control primitives, panel shells, mono labels, and border treatment.

The current-state reviewer artifact is `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-current-v4-diff-1294x885.png`.

The post-remediation reviewer artifact is `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-v4-remediation-1294x885.png`.

The second-pass reviewer artifact is `/Users/barry/.codex/worktrees/d394/hyperopen/tmp/portfolio-optimizer-v4-pass2-1294x885.png`.

Validation transcript summary:

    npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.views.portfolio.optimize.setup-v4-layout-test --test=hyperopen.views.portfolio.optimize.view-test
    # Ran 17 tests containing 197 assertions. 0 failures, 0 errors.

    npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer"
    # 9 passed

    npx playwright test tools/playwright/test/optimizer-history-network.qa.spec.mjs
    # 1 passed

    npm run check
    # passed, including namespace-size validation

    npm test
    # Ran 3583 tests containing 19614 assertions. 0 failures, 0 errors.

    npm run test:websocket
    # Ran 461 tests containing 2798 assertions. 0 failures, 0 errors.

    npm run browser:cleanup
    # ok, stopped []

## Interfaces and Dependencies

The implementation should preserve the current setup-route interfaces:

- `src/hyperopen/views/portfolio/optimize/workspace_view.cljs` remains the setup-route composition entrypoint and continues to pass the existing draft, readiness, snapshot, progress, run-state, and result-path data into the helper namespaces.
- `src/hyperopen/views/portfolio/optimize/setup_v4_header.cljs` exposes `setup-header` and `preset-row`.
- `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs` exposes `control-rail` and `summary-pane`.
- `src/hyperopen/views/portfolio/optimize/setup_v4_context.cljs` continues to expose `context-rail`.
- Existing control hooks remain stable, especially `portfolio-optimizer-run-draft`, `portfolio-optimizer-save-scenario`, `portfolio-optimizer-universe-use-current`, `portfolio-optimizer-universe-search-input`, `portfolio-optimizer-return-model-black-litterman`, `portfolio-optimizer-risk-model-sample-covariance`, and the constraint input roles already asserted in `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs`.
- `tools/playwright/test/portfolio-regressions.spec.mjs` remains the committed deterministic browser surface for setup-route regression coverage unless a smaller dedicated optimizer setup spec becomes necessary during implementation.

Revision note (2026-04-27 / Codex): Initial plan created after auditing the v4 design CSS and JSX, the current setup screenshot, the existing optimizer setup parity plans, and the current setup route implementation seams.
