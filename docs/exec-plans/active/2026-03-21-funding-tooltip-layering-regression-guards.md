# Prevent Funding Tooltip Layering Regressions On Trade

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-huu2`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

Users should be able to open the active-asset funding tooltip on `/trade` at smaller viewport widths and still read the full panel even when the order book and lower account surfaces are adjacent. The recent bug happened because the tooltip was visually above its own trigger but still trapped by the trade shell’s overflow and stacking-context rules. After this plan lands, the repository should have both deterministic view-level guards and a browser-level interaction check that prove the funding tooltip can escape the chart cell and render above neighboring panels.

## Progress

- [x] (2026-03-21 16:32Z) Created and claimed `hyperopen-huu2` for funding-tooltip regression prevention.
- [x] (2026-03-21 16:34Z) Wrote the initial active ExecPlan with concrete test, selector, and browser-QA scope.
- [x] (2026-03-21 19:24Z) Added the trade-shell class-contract regression test in `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs` and expanded tooltip selector coverage in `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_popover_test.cljs`.
- [x] (2026-03-21 19:24Z) Added stable `data-role` anchors for the active-asset funding trigger and tooltip panel in `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`.
- [x] (2026-03-21 19:35Z) Added deterministic browser-inspection coverage in `/hyperopen/tools/browser-inspection/scenarios/trade-funding-tooltip-layering.json`, plus minimal `eval` / `wait_for_eval` scenario support and a narrow QA fixture seeding hook so the scenario can click the real trigger on cold boot.
- [x] (2026-03-21 19:36Z) Ran `npm run test:browser-inspection`, `npm test`, `npm run test:websocket`, the targeted funding-tooltip scenario, and the governed `/trade` design review with passing results for the new guardrail stack.
- [ ] Move this ExecPlan out of `active/` once the implementation is landed and the issue lifecycle is closed so docs lint stays green.

## Surprises & Discoveries

- Observation: the repository already has strong shell-geometry tests for `/trade`, but none of them exercise the funding-tooltip-open state that temporarily changes the chart cell’s overflow contract.
  Evidence: `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs` asserts default trade panel classes and geometry, but the current tests do not render the trade shell with `[:funding-ui :tooltip :visible-id]` set.

- Observation: the existing funding tooltip tests focus on pinning behavior and body presence, not on browser-targetable selectors or cross-panel overlap.
  Evidence: `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_popover_test.cljs` checks click actions and open/closed rendering only.

- Observation: the governed design-review run for the tooltip fix cleared visual, styling, layout, and perf passes but left `interaction` blocked because the hover-only state was not reachable through stable browser hooks.
  Evidence: `/hyperopen/tmp/browser-inspection/design-review-2026-03-21T16-17-37-268Z-ea1695ef/summary.json` reported `interaction: BLOCKED` with the residual blind spot `/trade: hover, active, disabled, and loading states were not reachable.`

- Observation: the focused browser scenario could not rely on cold-boot market data alone because the funding trigger does not render until the active asset has a numeric funding rate.
  Evidence: the first local scenario attempts under `/hyperopen/tmp/browser-inspection/scenario-2026-03-21T19-31-07-036Z-3862dd68/` and `/hyperopen/tmp/browser-inspection/scenario-2026-03-21T19-32-37-506Z-5c474a3f/` timed out waiting for `[data-role='active-asset-funding-trigger']` on a fresh `/trade` boot.

- Observation: a narrow QA-only fixture seed was enough to make the scenario deterministic without broadening production surface area.
  Evidence: `/hyperopen/src/hyperopen/telemetry/console_preload/simulators.cljs` `seed-funding-tooltip-fixture!` sets the active asset, active market, active-asset context funding rate, and predictability summary, after which the targeted scenario passed at `review-1280`.

## Decision Log

- Decision: treat this as a trade-shell regression-prevention task, not a tooltip-component-only task.
  Rationale: the underlying bug was caused by `trade_view` overflow and stacking behavior, so the strongest guard must assert shell classes and browser geometry, not just tooltip markup.
  Date/Author: 2026-03-21 / Codex

- Decision: require stable DOM anchors for the tooltip trigger and panel before adding browser-interaction coverage.
  Rationale: governed browser QA cannot reliably verify hover or pin behavior against anonymous buttons and absolute panels whose only identifiers are classes.
  Date/Author: 2026-03-21 / Codex

- Decision: keep the browser-level check focused on pinning or otherwise deterministic opening, rather than raw hover simulation only.
  Rationale: pinned state is already part of the production interaction model, survives transient pointer movement, and is easier to verify across viewports without flaky timing.
  Date/Author: 2026-03-21 / Codex

- Decision: extend the browser-inspection scenario runner with `eval` and `wait_for_eval` rather than hardcoding a one-off tooltip probe into governed design review.
  Rationale: the polling/eval step is reusable for future deterministic DOM-state interactions, while the guarded scenario stays focused on the funding tooltip regression.
  Date/Author: 2026-03-21 / Codex

- Decision: seed the active-asset funding fixture through the debug bridge before clicking the trigger in browser QA.
  Rationale: the regression guard should validate tooltip layering, not cold-start market-data timing. A narrow debug-only fixture removes that unrelated nondeterminism while still clicking the real trigger and measuring the real overlay.
  Date/Author: 2026-03-21 / Codex

## Outcomes & Retrospective

Implementation is complete; only archival/issue-close cleanup remains. The completed outcome is:

- `/trade` now has a deterministic view test that fails if the funding-tooltip-open state stops elevating the market strip and unclipping the chart cell.
- the active-asset funding tooltip exposes stable selectors that browser tooling can target directly.
- the browser-inspection toolchain now has a deterministic path that opens the tooltip via the real trigger, samples the overlap region against the order book at `1280px`, and fails if the tooltip stops being the topmost surface there.
- the governed `/trade` design review now passes again across `375`, `768`, `1280`, and `1440`.

This plan reduced risk more than raw complexity. The final shape is one explicit selector seam, one explicit shell-state regression test, one reusable browser-scenario polling primitive, and one narrow QA fixture seed for cold-boot determinism. The only remaining repo-wide blocker is unrelated: `npm run check` still fails because `/hyperopen/docs/exec-plans/active/2026-03-20-decrap-vault-and-funding-hotspots.md` has no unchecked progress items and still lives under `active/`.

## Context and Orientation

The active-asset funding tooltip lives in `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`. That namespace renders the trigger button, the optional fixed dismiss target, and the floating tooltip body. The active-asset strip itself is rendered from `/hyperopen/src/hyperopen/views/active_asset/row.cljs` and `/hyperopen/src/hyperopen/views/active_asset_view.cljs`.

The `/trade` page layout is owned by `/hyperopen/src/hyperopen/views/trade_view.cljs`. That file decides which large shell panels clip their contents, which panels are elevated with `z-index`, and when the chart-side wrapper is allowed to switch from `overflow-hidden` to `overflow-visible`. A “stacking context” in this repository means a parent surface whose positioning and `z-index` rules determine whether a child can appear above neighboring panels. A tooltip can have a large local `z-index` and still be hidden if one of its parent surfaces clips overflow or sits under an adjacent panel in the trade grid.

The current unit and view-test seams are:

- `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_popover_test.cljs`
- `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`
- `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs`
- `/hyperopen/test/hyperopen/views/active_asset/test_support.cljs`

The browser QA and inspection surfaces are under `/hyperopen/tools/browser-inspection/`. The governed route review is configured through:

- `/hyperopen/tools/browser-inspection/config/design-review-routing.json`
- `/hyperopen/tools/browser-inspection/scenarios/`
- `/hyperopen/docs/agent-guides/browser-qa.md`
- `/hyperopen/docs/runbooks/browser-live-inspection.md`

The recent tooltip fix added the shell-state predicate `/hyperopen/src/hyperopen/views/active_asset/vm.cljs` `active-asset-funding-tooltip-open?`, lifted the market strip z-layer in `/hyperopen/src/hyperopen/views/active_asset_view.cljs`, and conditionally made the chart-side wrapper overflow-visible in `/hyperopen/src/hyperopen/views/trade_view.cljs`. Those are the exact seams the new regression guards must protect.

## Plan of Work

First, strengthen the view-level contract. Extend `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs` with one or more tests that render `/trade` using the existing active-asset test fixture plus `/hyperopen/test/hyperopen/views/active_asset/test_support.cljs` `with-visible-funding-tooltip`. The closed-state assertions that already exist for the chart panel should remain intact. The new open-state assertions should prove that the same shell switches to elevated classes only while the funding tooltip is open. The key conditions are that `trade-chart-panel` gains `overflow-visible` and the elevated z-layer, while `market-strip` also gains the matching z-layer. These tests should fail if a future refactor removes the shell escape hatch or reintroduces clipping.

Second, make the tooltip browser-addressable. Add stable `data-role` attributes in `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs` for the trigger button and the floating panel. The names should be explicit and narrow, such as `active-asset-funding-trigger` and `active-asset-funding-tooltip`. Avoid relying on CSS classes as selectors because the current classes are presentation-oriented and likely to drift during design work.

Third, add deterministic browser coverage in `/hyperopen/tools/browser-inspection/scenarios/`. Prefer a focused scenario that loads `/trade`, opens the active-asset funding tooltip by click, pins it if needed, and asserts that the tooltip panel is present with a bounding box that is not hidden beneath adjacent surfaces. The browser assertion does not need pixel-perfect diff logic; it should verify the panel exists, is visible, and remains open long enough for geometry sampling at governed widths. If a new scenario is not the cleanest fit, extend the design-review path with a route-specific interaction probe that targets the new `data-role` hooks, but keep the behavior deterministic and repo-local.

Finally, validate the full guardrail stack. Run the repository tests that cover the new view logic and then run the governed browser QA command against `/trade`. The intended result is that this tooltip no longer sits in the category “hover-only state not reachable,” because the browser tooling can now open and inspect it directly.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/e459/hyperopen`.

1. Extend `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs`.

   Add a fixture variant that sets the visible funding tooltip id with `with-visible-funding-tooltip`, then assert:

   - `trade-chart-panel` contains `overflow-visible`
   - `trade-chart-panel` contains the elevated z-layer class
   - `market-strip` contains the elevated z-layer class
   - the default closed-state fixture still contains `overflow-hidden`

   Keep the assertions narrow and class-based, matching the existing shell contract style in this file.

2. Update `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`.

   Add stable `data-role` attributes to:

   - the trigger button that opens or pins the tooltip
   - the floating tooltip panel wrapper rendered when `open?` is true

   If the current wrapper split makes the selector ambiguous, add the attribute to the exact node the browser should click or measure.

3. Add browser-inspection coverage under `/hyperopen/tools/browser-inspection/scenarios/` and any supporting loader/config file required by the existing scenario framework.

   The scenario should:

   - open `/trade`
   - target the active-asset funding trigger by `data-role`
   - click to open or pin the tooltip
   - assert that the tooltip panel exists and is visible
   - capture geometry or presence evidence at the widths used by the scenario

   If the scenario framework needs a new helper expression, keep it local to the browser-inspection toolchain and document the expected returned fields in the scenario file comments or nearby helper.

4. Run validation.

   - `npm test`
   - `npm run test:websocket`
   - `npm run qa:design-ui -- --targets trade-route --changed-files src/hyperopen/views/active_asset/funding_tooltip.cljs,src/hyperopen/views/active_asset_view.cljs,src/hyperopen/views/trade_view.cljs`
   - If a dedicated scenario is added, run the scenario command for it and save the artifact path in this plan.

5. Update this ExecPlan.

   Record the exact tests and browser artifacts that prove the regression guard works. If the browser interaction pass is still blocked, document the remaining blocker precisely instead of hand-waving.

## Validation and Acceptance

Acceptance is behavioral, not just structural.

The first acceptance check is local test behavior. After the new test is added, `npm test` should pass and the new trade-shell test should prove that an open funding tooltip changes the shell from clipped to overflow-visible while the default closed state remains clipped. This demonstrates that the trade shell owns the escape hatch intentionally rather than by accident.

The second acceptance check is browser reachability. After the new selectors and scenario land, the browser-inspection tooling should be able to open the funding tooltip without relying on manual hover timing. The artifact should show a visible tooltip panel on `/trade`, and the governed `/trade` browser QA run should no longer cite this surface as unreachable due solely to missing hooks.

The third acceptance check is regression resistance. A future contributor who removes the tooltip-specific shell elevation or renames the interaction seam without updating tests should see either the view contract test or the browser scenario fail immediately.

## Idempotence and Recovery

These steps are safe to repeat. The new view test and browser scenario are additive. If the browser scenario becomes flaky during implementation, keep the stable `data-role` hooks and the shell-state unit test, then refine the scenario until it opens the pinned tooltip deterministically. Do not weaken the acceptance criteria by falling back to screenshot-only checks with no interaction seam.

If a selector name changes during review, update both the production view and the scenario in the same patch so the browser coverage does not silently drift out of sync.

## Artifacts and Notes

The most relevant existing artifact is:

    /Users/barry/.codex/worktrees/e459/hyperopen/tmp/browser-inspection/design-review-2026-03-21T16-17-37-268Z-ea1695ef/summary.json

That run already shows why this work matters: the trade-route design review passed most categories, but `interaction` remained blocked because the hover-only funding tooltip state was not programmatically reachable.

The most relevant existing test helper is:

    /hyperopen/test/hyperopen/views/active_asset/test_support.cljs

Specifically, `with-visible-funding-tooltip` already provides the state seam needed to render `/trade` with the funding tooltip open in deterministic view tests.

Fresh implementation artifacts:

    /Users/barry/.codex/worktrees/e459/hyperopen/tmp/browser-inspection/scenario-2026-03-21T19-35-39-040Z-6332eab9/summary.json

That targeted scenario proves the pinned funding tooltip overlaps the order book at `review-1280` and remains the topmost element at the sampled overlap point (`rightSample.topDataRole = active-asset-funding-tooltip`, `rightSample.insidePanel = true`).

    /Users/barry/.codex/worktrees/e459/hyperopen/tmp/browser-inspection/design-review-2026-03-21T19-36-02-468Z-670f0212/summary.json

That governed run returns overall `PASS` for `/trade` across all required widths and all six browser-QA passes. It still records the broader residual blind spot that hover/active/disabled/loading states not present by default require targeted route actions, but the funding-tooltip interaction itself is no longer blocked because the repo now has a dedicated deterministic scenario for it.

## Interfaces and Dependencies

Do not introduce a new tooltip framework or alternate popover abstraction. Stay inside the existing active-asset tooltip and browser-inspection systems.

The implementation must preserve these seams:

- `/hyperopen/src/hyperopen/views/active_asset/vm.cljs`
  `active-asset-funding-tooltip-open?`
- `/hyperopen/src/hyperopen/views/trade_view.cljs`
  `trade-view`
- `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`
  `funding-tooltip-popover`

At the end of this work, the following interface-level conditions must be true:

- `funding-tooltip-popover` renders stable `data-role` hooks for the browser toolchain.
- `trade-view` has a test that renders the tooltip-open state and asserts the intended shell classes.
- the browser-inspection toolchain has one deterministic path that can open or pin the active-asset funding tooltip and verify the panel is present.

Plan update note (2026-03-21): created this ExecPlan after the funding-tooltip layering fix landed on local `main`. The fix itself was validated, but the governed browser run still reported the tooltip interaction as blocked because the surface lacked stable selectors and deterministic interaction coverage. This plan exists to close that regression-prevention gap.
