# Refactor Trading Settings Popover For Brand Alignment

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The tracked work item for this plan is `hyperopen-6nzn`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

After this change, the `Trading settings` popover should still behave exactly as it does today, but it should read as a native Hyperopen trading surface instead of a generic layered settings card. Users should see a sharper, flatter, denser panel with fewer nested boxes, tighter radii, stronger alignment to the app’s existing trading accents, and a more deliberate relationship between section structure, toggles, and copy.

A user should be able to open the settings popover from `/trade`, immediately recognize the same options and copy they have today, and notice that the surface now feels more consistent with the rest of the app’s high-density trading UI. Verification is visual and behavioral: the rows still toggle the same persisted settings, but the shell, section rhythm, and switch styling now feel system-native rather than component-library-generic.

## Progress

- [x] (2026-03-30 13:36Z) Created and claimed `bd` issue `hyperopen-6nzn` for the brand-alignment refactor.
- [x] (2026-03-30 13:36Z) Audited the current Trading Settings implementation and the prior completed Trading Settings UI pass in `/hyperopen/docs/exec-plans/completed/2026-03-19-trading-settings-icon-popover-ui.md`.
- [x] (2026-03-30 13:36Z) Authored this active ExecPlan with concrete UI goals, file targets, validation steps, and sub-agent recommendations.
- [x] (2026-03-30 13:44Z) Flattened the Trading Settings interior in `/hyperopen/src/hyperopen/views/header/settings.cljs` by removing icon tiles, tightening the shell, and simplifying section and row chrome while preserving all existing actions and copy.
- [x] (2026-03-30 13:44Z) Aligned the toggle treatment, header divider, confirmation strip, and shell geometry to a sharper trading-surface style family without changing native checkbox semantics.
- [x] (2026-03-30 13:44Z) Updated `/hyperopen/test/hyperopen/views/header_view_test.cljs` to pin the new shell tokens and flatter row contract.
- [x] (2026-03-30 13:44Z) Verified the focused CLJS render seams with `npm run test:runner:generate && npx shadow-cljs compile test && node out/test.js --test=hyperopen.views.header-view-test,hyperopen.views.header.vm-test`.
- [x] (2026-03-30 13:44Z) Reused the existing smallest relevant Playwright regression with `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "trading settings confirmation toggles"`; no Playwright file changes were necessary because the browser contract stayed stable.
- [x] (2026-03-30 13:45Z) Ran `npm test` and `npm run test:websocket`; both passed after the UI refactor.
- [x] (2026-03-30 13:48Z) Governed browser QA passed with `npm run qa:design-ui -- --targets trade-route --manage-local-app`; `trade-route` passed `visual-evidence-captured`, `native-control`, `styling-consistency`, `interaction`, `layout-regression`, and `jank-perf` at `375`, `768`, `1280`, and `1440`.
- [x] (2026-03-30 13:48Z) Updated this plan with final closeout evidence in preparation for moving it out of `active/`.

## Surprises & Discoveries

- Observation: the current Trading Settings surface already lives in a dedicated render module and view-model seam rather than a monolithic header file.
  Evidence: `/hyperopen/src/hyperopen/views/header/settings.cljs`, `/hyperopen/src/hyperopen/views/header/vm.cljs`, and `/hyperopen/src/hyperopen/views/header_view.cljs`.

- Observation: the previous accepted pass deliberately moved the panel toward grouped layered cards, but the resulting composition still reads softer and more generic than the surrounding trading surfaces.
  Evidence: `/hyperopen/docs/exec-plans/completed/2026-03-19-trading-settings-icon-popover-ui.md` plus the current live class stack in `/hyperopen/src/hyperopen/views/header/settings.cljs`.

- Observation: the current shell already exposes stable `data-role` hooks and deterministic view tests for section ordering, shell classes, and copy, so the next pass can stay narrowly focused on layout and style contracts rather than behavior changes.
  Evidence: `/hyperopen/test/hyperopen/views/header_view_test.cljs`.

- Observation: the existing Playwright regression for Trading Settings confirmation toggles was already narrow and stable enough to validate this refactor without any browser-test edits.
  Evidence: `tools/playwright/test/trade-regressions.spec.mjs` and the passing command `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "trading settings confirmation toggles"`.

- Observation: an earlier managed-local startup timeout at `review-375` was transient; the rerun passed every governed review pass at all required widths without any Trading Settings-specific issues.
  Evidence: `/hyperopen/tmp/browser-inspection/design-review-2026-03-30T13-47-28-982Z-9cb9f3bd/summary.json`.

## Decision Log

- Decision: treat this refactor as a UI-facing brand-alignment pass, not a behavior or product-scope change.
  Rationale: the problem is not missing functionality. The current component works, but its nested-card composition and soft geometry weaken brand consistency against the rest of the trading interface.
  Date/Author: 2026-03-30 / Codex

- Decision: keep all existing settings, helper copy, persistence wiring, and confirmation behavior intact unless implementation discovers a concrete fit problem.
  Rationale: the critique is about visual system coherence, not product scope. Preserving content and actions keeps the change low-risk and directly testable.
  Date/Author: 2026-03-30 / Codex

- Decision: bias the redesign toward flatter interior hierarchy, tighter radii, and divider-led organization instead of adding new decorative effects.
  Rationale: the component currently feels too boxed and too soft. The desired correction is precision and restraint, which matches both the user feedback and the repo’s UI guidance for dense product surfaces.
  Date/Author: 2026-03-30 / Codex

- Decision: keep the icon-left row structure, but remove the icon tile chrome instead of removing icons outright.
  Rationale: the icons still help scanning across section types, but the boxed icon tiles were part of what made the panel feel like a generic stacked-settings component.
  Date/Author: 2026-03-30 / Codex

- Decision: reuse the existing Trading Settings Playwright toggle regression instead of broadening browser coverage in this change.
  Rationale: the data-role anchors and interaction contract remained stable, so extending browser coverage would add cost without materially improving regression safety for this specific refactor.
  Date/Author: 2026-03-30 / Codex

## Outcomes & Retrospective

The implementation landed in `/hyperopen/src/hyperopen/views/header/settings.cljs` and `/hyperopen/test/hyperopen/views/header_view_test.cljs`. The Trading Settings surface is now flatter and sharper: icon tiles are gone, the shell is tighter, section grouping is quieter, the toggle accent is more system-aligned, and the title and footer rhythm are less modal-like.

This reduced perceived UI complexity even though several style tokens changed, because the panel now depends less on repeated boxed elements and more on typography, spacing, and dividers to organize the content. Required runtime gates passed, the smallest relevant Playwright regression passed, and the governed trade-route browser review passed at all required widths.

## Context and Orientation

The Trading Settings surface is assembled from three nearby view seams.

`/hyperopen/src/hyperopen/views/header/vm.cljs` builds the settings view model. It decides which sections and rows exist, their titles and helper copy, the checked state for each toggle, and the action vectors that dispatch when a setting changes. This file is not the place for visual styling decisions unless copy or section structure truly changes.

`/hyperopen/src/hyperopen/views/header/settings.cljs` renders the settings trigger, desktop popover, mobile sheet, title bar, section wrappers, rows, icons, toggle control, and storage-mode confirmation strip. This is the primary implementation target for the refactor.

`/hyperopen/src/hyperopen/views/header_view.cljs` composes the broader header surface and mounts the settings shell into the header tree. It should only need small changes if the render contract or wrapper classes shift.

`/hyperopen/test/hyperopen/views/header_view_test.cljs` contains deterministic rendering coverage for the open Trading Settings shell, section ordering, copy, shell classes, and motion hooks. This file must be updated when the structural class contract changes.

In this repository, “governed browser QA” means the Browser MCP design-review run defined in `/hyperopen/docs/BROWSER_TESTING.md` and `/hyperopen/docs/agent-guides/browser-qa.md`. For this task, that QA is required because the work is UI-facing. Playwright remains the committed deterministic browser tool; Browser MCP remains the governed visual-review tool.

## Design Thesis

Visual thesis: make the settings surface feel like a precision instrument panel rather than a floating stack of generic preference cards, using flatter planes, tighter geometry, and one disciplined accent system.

Content plan: keep the current title, section labels, row titles, helper copy, and footer note, but make section grouping and row scanning do more of the work than container chrome.

Interaction thesis: preserve the existing anchored open and mobile-sheet entry motion, keep toggle interaction fast and quiet, and add no ornamental movement that competes with the trading surface beneath the panel.

## Plan of Work

Start by capturing a clean visual baseline of the current Trading Settings popover on desktop and mobile from `/trade`. The goal is to document exactly what must change: the repeated inner-card boxes, the current radius scale, the amount of interior padding, the section-divider rhythm, the title-bar weight, and the relationship between the switch accent and the rest of the trading palette. Record screenshots and notes under `/hyperopen/tmp/browser-inspection/` so the before-state is explicit.

Next, refactor the render structure in `/hyperopen/src/hyperopen/views/header/settings.cljs` toward a flatter composition. Keep the outer shell, but reduce the visual dominance of the inner section cards. Prefer using section spacing, internal padding rhythm, and divider lines to communicate grouping. If a section still needs a container, make it lighter and more structural than decorative. The row wrapper should stop reading like a card inside a card.

Then tighten the geometry. Reduce corner softness where it does not help affordance, trim padding that makes the panel feel over-cushioned, and make the shell, section labels, rows, and toggle placement align to a denser trading-UI cadence. The title bar should feel more deliberate and less modal-like. The close control should remain accessible, but it should visually belong to the same system as the rest of the header controls.

After the composition pass, refine the switch styling. The toggle should continue using native checkbox semantics under the hood, but its on-state color, track contrast, thumb color, and focus treatment should match the app’s trading accent system more precisely. Do not introduce a second accent family. If the current icon-left rows still make sense after the flattening pass, keep them; if they add noise once the panel is flatter, simplify them rather than compensating with more chrome.

Finally, update deterministic tests to pin the new structure that matters for regression safety, then run the required validation sequence. If the new panel creates a stable local browser path not already protected by current Playwright smoke coverage, add the smallest relevant Playwright assertion first and run that before the broader repository gates and governed browser QA.

## Concrete Steps

From `/hyperopen`:

1. Capture before-state evidence for the current panel:
   `npm run qa:design-ui -- --targets trade-route --manage-local-app`

   Save or note the specific artifact directory for the Trading Settings screenshots and review output.

2. Edit the primary rendering seam:
   `/hyperopen/src/hyperopen/views/header/settings.cljs`

   Keep existing behavior and `data-role` coverage intact where possible while flattening the internal hierarchy and tightening geometry.

3. Edit related composition or copy seams only if necessary:
   `/hyperopen/src/hyperopen/views/header/vm.cljs`
   `/hyperopen/src/hyperopen/views/header_view.cljs`

4. Update deterministic tests:
   `/hyperopen/test/hyperopen/views/header_view_test.cljs`

5. If a new committed browser regression is warranted, update the smallest relevant Playwright test under:
   `/hyperopen/tools/playwright/**`

6. Run required validation in this order:
   `npm run check`
   `npm test`
   `npm run test:websocket`
   `npm run test:playwright:smoke`
   `npm run qa:design-ui -- --targets trade-route --manage-local-app`

7. Update this plan with changed files, actual command results, browser artifact paths, and any follow-up `bd` issues before closing `hyperopen-6nzn` and moving the plan to `/hyperopen/docs/exec-plans/completed/`.

## Validation and Acceptance

The refactor is accepted only if all of the following are true.

Users opening Trading Settings on `/trade` still see the same settings, helper copy, and toggle behavior they see today. No persistence or confirmation behavior regresses.

The panel reads as part of Hyperopen’s trading UI rather than a generic settings modal. In practice, that means the interior no longer depends on stacked card-within-card treatment, the radius scale is tighter, the spacing is denser but still readable, and the accent treatment is consistent with the app’s existing trading controls.

The desktop popover and mobile sheet both preserve accessible semantics, close behavior, and existing open/close motion without adding distracting animation.

Deterministic rendering tests pass, repository gates pass, the smallest relevant committed Playwright command passes, and the governed trade-route browser review explicitly accounts for the changed Trading Settings surface.

## Idempotence and Recovery

This refactor should be implemented as a sequence of safe, additive UI edits. If a styling pass becomes visually worse or harder to verify, revert only the Trading Settings render changes and matching test updates together so the structural contract stays aligned.

Browser QA and screenshots are safe to rerun. If the governed browser review is blocked by an unrelated route issue, record the blocker honestly in this plan and in `bd` rather than opening a second untracked markdown TODO.

## Artifacts and Notes

Expected artifacts for this work:

- governed browser-review output under `/hyperopen/tmp/browser-inspection/**`
- any multi-agent planning artifacts under `/hyperopen/tmp/multi-agent/hyperopen-6nzn/**`

Recommended exact-agent roster for implementation:

- `spec_writer`: refresh this ExecPlan if scope shifts once implementation begins.
- `browser_debugger`: capture before/after evidence and run governed browser QA on the `/trade` route.
- `ui_designer`: optional but high-value for evaluating whether the flatter hierarchy still preserves enough grouping and whether icon usage remains justified.
- `worker`: implement the render and style refactor in `/hyperopen/src/**`.
- `reviewer`: run read-only correctness and regression review after the UI pass lands.
- `ui_visual_validator`: perform final read-only visual validation against the intended trading-surface brand alignment.

Optional test-expansion agents if the implementation grows beyond a pure styling pass:

- `acceptance_test_writer`: propose stable happy-path browser coverage if a new Playwright seam is needed.
- `edge_case_test_writer`: propose boundary coverage for desktop/mobile presentation and confirmation-strip states.
- `tdd_test_writer`: materialize approved failing tests only if the team chooses a RED-first browser or view-test pass.

## Interfaces and Dependencies

No new library dependency should be introduced for this refactor. Reuse the existing header settings render path, icon set, native checkbox semantics, `replicant` motion hooks, and current Browser MCP plus Playwright toolchain.

At the end of implementation, these invariants must still hold:

- `/hyperopen/src/hyperopen/views/header/vm.cljs` remains the source of truth for section and row content.
- `/hyperopen/src/hyperopen/views/header/settings.cljs` remains the source of truth for Trading Settings shell and row rendering.
- Existing action vectors for setting changes remain intact unless a behavior bug is discovered and explicitly tracked.
- `data-role` anchors used by `/hyperopen/test/hyperopen/views/header_view_test.cljs` and browser tooling remain stable unless the plan is updated to reflect a deliberate rename.

Plan revision note: 2026-03-30 13:36Z - Created the initial active ExecPlan after claiming `hyperopen-6nzn`, auditing the current Trading Settings render path, reviewing the prior completed Trading Settings UI pass, and translating the latest design critique into an executable refactor plan.
Plan revision note: 2026-03-30 13:46Z - Updated the plan after implementing the flatter Trading Settings shell, passing focused CLJS tests plus the existing Playwright toggle regression, and passing `npm test` and `npm run test:websocket`.
Plan revision note: 2026-03-30 13:48Z - Recorded the passing `npm run check` result and the governed browser-QA PASS for `trade-route` at `375`, `768`, `1280`, and `1440`, then prepared the plan to move to `completed/`.
