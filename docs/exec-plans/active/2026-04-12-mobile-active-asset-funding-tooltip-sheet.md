# Patch Mobile Active-Asset Funding Tooltip Presentation

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The live `bd` issue for this work is `hyperopen-yw1s` ("Patch mobile active-asset funding tooltip presentation"), and `bd` remains the lifecycle source of truth until this plan is moved out of `active`.

## Purpose / Big Picture

Today the mobile `/trade` route opens the active-asset `Funding / Countdown` inspector as the same large anchored popover used on desktop. On phone widths that panel spills over the chart and lower account surfaces instead of behaving like a mobile sheet, which makes the interaction feel broken and obscures too much of the route.

After this work, the same mobile trigger will open a fixed bottom sheet that is sized and dismissed like the other mobile overlays in this repository. Desktop `/trade` must keep the existing anchored popover behavior unchanged, including the selectors, geometry, and layering contract that already have regression coverage.

## Progress

- [x] (2026-04-12 23:10Z) Created and claimed `bd` issue `hyperopen-yw1s` for the mobile active-asset funding tooltip presentation bug.
- [x] (2026-04-12 23:10Z) Audited the current render path and confirmed the root cause: `/hyperopen/src/hyperopen/views/active_asset/row.cljs` routes both desktop and mobile through the shared anchored popover in `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`, which has no mobile-specific sheet path.
- [x] (2026-04-12 23:10Z) Audited the existing regression seams in `/hyperopen/test/hyperopen/views/active_asset/**`, `/hyperopen/test/hyperopen/views/trade_view/mobile_surface_test.cljs`, and `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`.
- [x] (2026-04-12 23:10Z) Authored this active ExecPlan with a mobile-only presentation split that preserves the current desktop popover contract.
- [x] (2026-04-12 23:34Z) Implemented the mobile-only funding-tooltip sheet path in the active-asset view layer, keeping the existing desktop popover contract intact and routing only the mobile details row through the new sheet presentation.
- [x] (2026-04-12 23:45Z) Added deterministic mobile regression coverage, reran the required repo gates, completed the governed `trade-route` browser QA pass, and cleaned up browser-inspection sessions.

## Surprises & Discoveries

- Observation: the screenshoted surface is not the account funding modal in `/hyperopen/src/hyperopen/views/funding_modal.cljs`; it is the active-asset funding inspector embedded in the trade header strip.
  Evidence: the visible copy `Hypothetical Position`, `Projections`, and `Past Rate Correlation` comes from `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`, not from the funding modal namespaces.

- Observation: the mobile trade row already has a dedicated render branch, but the funding trigger inside that branch still calls the shared desktop-style anchored popover helper.
  Evidence: `/hyperopen/src/hyperopen/views/active_asset/row.cljs` `mobile-active-asset-row` renders the `Funding / Countdown` field through `funding-rate-node`, and `funding-rate-node` always calls `funding-tooltip/funding-tooltip-popover`.

- Observation: the current tooltip implementation has no viewport-aware or sheet-aware code path at all.
  Evidence: `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs` `funding-tooltip-popover` renders only an `absolute` panel with `top`, `bottom`, `left`, or `right` placement classes plus an optional fixed dismiss target for the pinned state.

- Observation: the repo already has multiple governed mobile-sheet implementations that match the desired interaction pattern, so this fix does not need a new overlay design language.
  Evidence: `/hyperopen/src/hyperopen/views/funding_modal.cljs`, `/hyperopen/src/hyperopen/views/account_info/position_margin_modal.cljs`, `/hyperopen/src/hyperopen/views/account_info/position_reduce_popover.cljs`, and `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs` all render fixed mobile layers with backdrops, bottom anchoring, rounded top corners, and explicit `data-role` hooks.

- Observation: the desktop trade shell intentionally flips to `overflow-visible` while the funding inspector is open so the anchored popover can escape the chart cell.
  Evidence: `/hyperopen/src/hyperopen/views/trade_view/layout_state.cljs` adds `overflow-visible` and `z-[160]` when `funding-tooltip-open?` is true, and `/hyperopen/test/hyperopen/views/trade_view/mobile_surface_test.cljs` locks that contract in place.

- Observation: the first mobile-sheet patch fixed content placement but the sheet still could not win pointer priority until the entire mobile active-asset strip stacking context was raised above the chart.
  Evidence: the initial focused Playwright run showed the chart canvas intercepting clicks intended for the sheet backdrop; after raising the mobile strip to `z-[200]`, the browser no longer reported chart interception and the mobile sheet could own pointer input.

- Observation: `npm run check` rejected the first implementation because the existing `funding_tooltip.cljs` namespace crossed the repo size limit.
  Evidence: `dev.check-namespace-sizes` failed with `[missing-size-exception] src/hyperopen/views/active_asset/funding_tooltip.cljs - namespace has 528 lines`; extracting the mobile sheet into `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip_mobile.cljs` brought the original namespace back to 485 lines and restored the gate.

## Decision Log

- Decision: keep the existing desktop anchored popover contract intact and route mobile through a separate presentation helper.
  Rationale: the desktop path already has working selectors, layering guards, and a Playwright regression. Rewriting the shared popover to become viewport-aware would broaden risk into the stable desktop path for no user benefit.
  Date/Author: 2026-04-12 / Codex

- Decision: reuse the existing funding-tooltip state and model on mobile instead of introducing a second store shape.
  Rationale: the bug is presentation, not state derivation. The current `visible-id`, `pinned-id`, and tooltip model already know when the surface is open and what content to render.
  Date/Author: 2026-04-12 / Codex

- Decision: follow the repository’s existing mobile-sheet pattern for backdrop, bottom anchoring, and close behavior.
  Rationale: the repo already has accepted mobile overlay structure and tests for fixed sheets. Reusing that pattern keeps the result visually and behaviorally consistent with the rest of Hyperopen.
  Date/Author: 2026-04-12 / Codex

- Decision: do not narrow or “optimize away” the current shell elevation logic as part of this patch unless the new mobile sheet proves it is required for correctness.
  Rationale: the reported bug is the mobile presentation itself. Shell elevation is part of the known-good desktop layering fix and should stay out of scope unless the mobile implementation is still clipped or hidden after the sheet lands.
  Date/Author: 2026-04-12 / Codex

- Decision: keep the desktop chart-shell elevation contract unchanged and lift only the mobile active-asset strip above the chart while the funding sheet is open.
  Rationale: the failure mode was a mobile stacking-context collision, not a broken desktop overflow policy. Raising the mobile strip to `z-[200]` preserved the desktop `z-[160]` regression seam while giving the mobile sheet a parent stacking context that can sit above the chart canvas.
  Date/Author: 2026-04-12 / Codex

- Decision: fix the namespace-size gate by extracting the mobile sheet implementation into a dedicated view namespace instead of adding a new size exception.
  Rationale: this kept the repository lint budget intact, reduced the original tooltip namespace below the 500-line threshold, and preserved the existing public helper shape by re-exporting the mobile sheet wrapper from the original namespace.
  Date/Author: 2026-04-12 / Codex

## Outcomes & Retrospective

Implemented as planned with one narrow shell-layering adjustment. Mobile `/trade` now opens the active-asset funding inspector as a fixed bottom sheet with its own backdrop and deterministic test hooks, while desktop still uses the anchored popover and retains the existing selectors and live-to-hypothetical regression path. The only shell-level change needed was raising the mobile market strip above the chart during the open state so the fixed sheet could win pointer input over the chart canvas.

Validation completed successfully:

- `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "funding tooltip"`
- `npm run check`
- `npm test`
- `npm run test:websocket`
- `npm run qa:design-ui -- --targets trade-route --manage-local-app`
- `npm run browser:cleanup`

The governed browser QA pass finished `PASS` for `/trade` at `375`, `768`, `1280`, and `1440`, with only the standard interaction blind spots for non-default hover/disabled/loading states.

## Context and Orientation

The affected UI lives in the active-asset strip at the top of `/trade`. `/hyperopen/src/hyperopen/views/active_asset/row.cljs` renders both the desktop and mobile versions of that strip. The mobile branch is `mobile-active-asset-row`, which conditionally reveals a details panel containing the `Funding / Countdown` value. That value is currently rendered by `funding-rate-node`.

`funding-rate-node` delegates to `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`. In this repository, a “popover” means an anchored floating panel that is positioned relative to the trigger and is intended for desktop hover or click interactions. A “mobile sheet” means a fixed layer attached to the bottom of the viewport with a backdrop behind it. The current `funding-tooltip-popover` helper is purely the first kind: it renders an `absolute` panel next to the trigger and optionally a fixed dismiss target when the surface is pinned open.

The tooltip’s actual content is already separated cleanly. `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs` `funding-tooltip-panel` renders the content body, including the `Your Position` or `Hypothetical Position` section, the projections, and the two plots. `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs` builds the pure model for that panel. None of that state or math is currently broken; the bug is that the mobile route renders the desktop popover container around it.

The current desktop behavior is protected in two places. `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_popover_test.cljs` checks the popover’s trigger, dismiss, and stable `data-role` hooks. `/hyperopen/test/hyperopen/views/trade_view/mobile_surface_test.cljs` checks that opening the funding inspector lifts the trade shell into the overflow-visible desktop layering mode. There is also an existing Playwright regression in `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` for the funding-tooltip live-to-hypothetical transition. This patch must not break those existing seams.

The repo already contains accepted mobile overlays that should be copied structurally rather than reinvented. The nearest examples are `/hyperopen/src/hyperopen/views/funding_modal.cljs` and `/hyperopen/src/hyperopen/views/account_info/position_margin_modal.cljs`. They use a fixed layer, a full-screen backdrop button, a bottom-anchored panel, safe-area padding, and explicit `data-role` hooks for deterministic tests.

## Plan of Work

### Milestone 1: Split the mobile presentation from the desktop popover

Start in `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`. Keep `funding-tooltip-panel` as the shared content renderer. Keep `funding-tooltip-popover` as the desktop-only anchored surface; do not change its DOM contract beyond any minimal extraction needed to share content wrappers. Add a new helper for the mobile surface, for example `funding-tooltip-mobile-sheet`, that takes the same open-state inputs (`open?`, `pin-id`, `pinned?`, and `body`) but renders them as a fixed bottom sheet with a backdrop. The backdrop should dismiss by clearing the pinned and visible state for the same tooltip id, matching the existing close semantics.

The new mobile helper should follow the repository’s established sheet structure. It should render a fixed full-screen layer only when the tooltip is open, include a backdrop button with a stable `data-role`, and render a bottom-anchored panel with a stable `data-role` for the sheet surface. The panel should use the same dark palette and rounded-top sheet styling used by the accepted mobile overlays elsewhere in the repo. Keep the sheet body scrollable if the tooltip content exceeds the viewport height, and include bottom safe-area padding.

Then update `/hyperopen/src/hyperopen/views/active_asset/row.cljs`. Leave the desktop `funding-rate-node` behavior unchanged. For the mobile branch, stop relying on the shared popover helper. The simplest safe shape is to render the trigger as it does now inside the `Funding / Countdown` row, but render the mobile sheet as a sibling in the mobile active-asset row whenever `funding-tooltip-open?` is true. The mobile row already has a viewport-specific render branch, so this is the right place to split the presentation without introducing viewport conditionals into the shared desktop path.

Do not change `/hyperopen/src/hyperopen/views/active_asset/vm.cljs` or `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs` unless the mobile render needs a tiny additional presentational field such as a sheet title or aria label. This patch should not alter tooltip derivation, live-position logic, or estimate-mode lifecycle.

### Milestone 2: Lock the new mobile behavior without weakening desktop guards

Add focused unit coverage around the new mobile sheet. The most local place is `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`, which already has mobile trade-row tests and a case that proves open funding-tooltip content can render on mobile. Extend those tests so a phone-width render with the funding tooltip open asserts the presence of the new sheet layer, backdrop, and surface roles instead of merely checking that the copied content strings appear somewhere in the details panel.

Add or update a narrow helper-level test in `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_popover_test.cljs` only for the desktop popover contract. The intent is to prove that the popover helper still renders the same desktop selectors and dismiss actions and does not silently inherit the new mobile markup.

Update the committed Playwright regression in `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`. Keep the existing desktop live-to-hypothetical transition test intact. Add a second targeted mobile test, or a clearly separated mobile branch in the same area, that sets a mobile viewport, opens the trade route funding trigger, and asserts a sheet-style surface is present. The test should check the mobile-specific `data-role` hooks, confirm that the sheet contains the funding panel content, and confirm the close backdrop dismisses the surface. This is the smallest relevant committed browser regression for the reported bug and should run before broader validation.

### Milestone 3: Re-run the governed validation path

Once the mobile sheet is implemented and covered by deterministic tests, run the smallest relevant Playwright command first. After that passes, run the required repository gates from the root contract: `npm run check`, `npm test`, and `npm run test:websocket`.

Because this is a user-visible UI change under `/hyperopen/src/hyperopen/views/**`, finish with the governed browser QA pass for the trade route across `375`, `768`, `1280`, and `1440`. The mobile widths must explicitly account for the new funding sheet behavior in the interaction and layout-regression passes. If Browser MCP or browser-inspection sessions are created during that verification, close them with `npm run browser:cleanup` before calling the work complete.

## Concrete Steps

All commands below run from `/Users/barry/.codex/worktrees/eced/hyperopen`.

1. Implement the mobile-only presentation split in:
   `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`
   `/hyperopen/src/hyperopen/views/active_asset/row.cljs`

   Keep the desktop helper `funding-tooltip-popover` stable. Add a mobile sheet helper and wire only the mobile row through it.

2. Update deterministic view tests in:
   `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
   `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_popover_test.cljs`

   Expected result: the mobile row test proves the sheet layer and backdrop exist at phone width, and the popover test still proves the desktop selectors and dismiss actions remain unchanged.

3. Update or add the committed Playwright regression in:
   `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`

   The new browser assertion should exercise the mobile trigger at a phone viewport and expect a mobile sheet surface instead of an anchored popover.

4. Run the smallest relevant Playwright command first:

      npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "funding tooltip"

   Expected result: both the existing desktop funding-tooltip regression and the new mobile funding-tooltip regression pass.

5. Run the required repository validation gates:

      npm run check
      npm test
      npm run test:websocket

   Expected result: all commands exit with status `0`.

6. Run the governed design review and cleanup:

      npm run qa:design-ui -- --targets trade-route --manage-local-app
      npm run browser:cleanup

   Expected result: the trade-route design review explicitly accounts for the changed funding surface at `375`, `768`, `1280`, and `1440`, and cleanup closes any browser-inspection sessions created during review.

## Validation and Acceptance

Acceptance is behavioral.

At a phone viewport on `/trade`, opening the mobile active-asset details panel and tapping the `Funding / Countdown` value must open a fixed bottom sheet with a backdrop. The sheet must contain the same funding inspector content that the desktop popover shows, including the live-position or hypothetical-position section, projections, and plots. The sheet must be dismissible by the backdrop and by the existing close-state actions. It must not appear as a floating anchored panel wedged between the mobile market strip and the chart.

At desktop widths, opening the same funding trigger must still render the anchored popover identified by the existing desktop selectors. The desktop live-to-hypothetical interaction covered by the current Playwright regression must continue to pass without selector or geometry changes.

The implementation is complete only after the targeted Playwright regression passes, `npm run check`, `npm test`, and `npm run test:websocket` all pass, and the governed browser QA pass explicitly accounts for all required passes and widths. Browser cleanup is required before finishing.

## Idempotence and Recovery

This change is safe to iterate on because it is presentation-only and local to the active-asset view layer. If the mobile sheet path regresses during development, reverting the mobile branch in `/hyperopen/src/hyperopen/views/active_asset/row.cljs` to the existing popover call will restore the current behavior while preserving the audited desktop path.

Do not remove or rename the current desktop `data-role` hooks unless the corresponding tests and Playwright selectors are updated in the same patch. If the mobile implementation needs new `data-role` hooks, make them additive instead of repurposing the desktop ones.

If the new mobile sheet is still clipped or hidden after implementation, investigate only the minimal shell-level change needed to make the fixed layer visible. Do not broaden that investigation into a general cleanup of the trade shell overflow logic inside this bug unless the clipping is reproducible and directly blocks the mobile sheet.

## Artifacts and Notes

The originating user evidence for this plan is the local screenshot:
`/var/folders/dg/3nkyzrp12fn141vv7f6rc9v40000gn/T/TemporaryItems/NSIRD_screencaptureui_rwIilN/Screenshot 2026-04-12 at 7.00.11 PM.png`

The root-cause audit for this plan identified these key files:

- `/hyperopen/src/hyperopen/views/active_asset/row.cljs`
- `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`
- `/hyperopen/src/hyperopen/views/trade_view/layout_state.cljs`
- `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
- `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_popover_test.cljs`
- `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`

The nearest accepted mobile-sheet references are:

- `/hyperopen/src/hyperopen/views/funding_modal.cljs`
- `/hyperopen/src/hyperopen/views/account_info/position_margin_modal.cljs`

These should guide structure and test hooks, but this plan remains self-contained and does not require reading any prior ExecPlan to implement successfully.

## Interfaces and Dependencies

In `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`, keep the existing public helper:

    (defn funding-tooltip-popover [{:keys [trigger body position open? pin-id pinned?]}] ...)

Treat that helper as the desktop anchored-popover contract. It should still return the desktop trigger and anchored panel structure at the end of this work.

Add a new mobile presentation helper in the same namespace with a shape equivalent to:

    (defn funding-tooltip-mobile-sheet [{:keys [trigger body open? pin-id pinned?]}] ...)

This helper should own the fixed layer, backdrop, and bottom-anchored sheet surface for mobile. It may share the dismiss actions and content body with the desktop helper, but it must not require desktop callers to become viewport-aware.

In `/hyperopen/src/hyperopen/views/active_asset/row.cljs`, the desktop `funding-rate-node` path must continue to use `funding-tooltip-popover`. The mobile active-asset row must become the only caller of the new sheet helper. Keep the current tooltip state inputs (`funding-tooltip-open?`, `funding-tooltip-id`, `funding-tooltip-pinned?`, and `funding-tooltip-model`) as the single source of truth for whether the surface is open and what it renders.

Revision note (2026-04-12 23:10Z, Codex): Created the initial active ExecPlan after reproducing the mobile bug from the user’s screenshot, tracing the render path to the active-asset funding tooltip surface, auditing the existing desktop and mobile regression seams, and filing `hyperopen-yw1s` as the tracked bug.

Revision note (2026-04-12 23:48Z, Codex): Completed the implementation with a new mobile-sheet presentation helper, mobile stacking-context lift, focused view and Playwright regression coverage, a namespace-size cleanup split into `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip_mobile.cljs`, full required repo gates, governed trade-route browser QA `PASS`, and browser cleanup.
