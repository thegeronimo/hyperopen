# Refine Trading Settings Into An Icon-Enriched Layered Popover

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-6c2n`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

After this change, the `Trading settings` surface should feel closer to the provided PRD and visual reference: icon-enriched rows, a neutral elevated shell, grouped layered cards, custom toggles, and anchored motion that feels deliberate in a dense trading interface.

This remains a visual refinement pass. Settings behavior, scope, persistence, and safety messaging should remain intact.

## Progress

- [x] (2026-03-19 17:40Z) Reused `hyperopen-6c2n` for the second Trading Settings UI pass based on the icon-popover PRD and screenshot.
- [x] (2026-03-19 17:42Z) Re-read the current Trading Settings render path and header tests and inspected nearby repo patterns for animation and translucent surfaces.
- [x] (2026-03-19 17:53Z) Implemented the icon-enriched layered popover restyle in `header_view.cljs`, kept the existing typography language, and expanded deterministic header-view coverage for the new shell, section cards, and motion hooks.
- [x] (2026-03-19 17:55Z) Ran required repo gates and governed browser QA. The Trading Settings surface passed visual, native-control, and styling-consistency review; the overall governed run still fails on the standing `/portfolio` `375px` layout and jank issue.
- [x] (2026-03-19 23:38Z) Ran a shadow-focused refinement pass against the zoomed reference, tightening the shell top highlight, bottom falloff, grouped-card lift, divider contrast, and switch geometry.
- [x] (2026-03-19 23:42Z) Re-ran the governed browser review after the shadow-stack adjustments. `/trade` still passed visual, native-control, and styling-consistency review, while the overall run remained blocked by standing `/portfolio`, `/trade` desktop overflow, and `/vaults` route issues.
- [x] (2026-03-19 23:49Z) Removed the residual row-hover darkening from Trading Settings after manual verification showed the grouped cards should stay visually stable on pointer hover.
- [x] (2026-03-20 01:14Z) Accepted the latest hover-stability pass based on direct user review and prepared the plan for closeout.
- [x] (2026-03-20 12:11Z) Recorded that no further Trading Settings polish will be done in this plan because `hyperopen-6c2n` is closed; moving the document to `completed` keeps the accepted surface and unrelated QA blockers as historical context instead of active work.

## Surprises & Discoveries

- Observation: the current committed surface still uses the earlier flat divider-list structure with native checkbox presentation, so this pass can directly replace that styling without first undoing another intermediate glass shell.
  Evidence: `/hyperopen/src/hyperopen/views/header_view.cljs`.

- Observation: the repository already has lightweight enter and exit motion patterns for overlays using `:replicant/mounting` and `:replicant/unmounting` style maps, so the requested anchored fade-and-scale motion can be implemented without new infrastructure.
  Evidence: `/hyperopen/src/hyperopen/views/header_view.cljs`, `/hyperopen/src/hyperopen/views/funding_modal.cljs`, and `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`.

- Observation: the current tests pin semantics and content, but they do not yet pin icon presence, layered section containers, or animated panel-shell styling.
  Evidence: `/hyperopen/test/hyperopen/views/header_view_test.cljs`.

- Observation: the reference uses a tighter, denser lower shadow and a faint top inset highlight on both the outer shell and inner cards; the earlier shadow stack was too diffuse and read more like a generic SaaS modal.
  Evidence: user-supplied zoomed reference image and `/hyperopen/src/hyperopen/views/header_view.cljs`.

- Observation: the final remaining acceptance item was direct user polish review rather than another implementation or test gap.
  Evidence: the only unchecked progress item before closeout was explicit user review of the hover-stability pass, and the user confirmed the work was done on 2026-03-20.

## Decision Log

- Decision: restyle the surface around icon-led rows and custom switch controls while preserving native checkbox input semantics under the hood.
  Rationale: this matches the PRD and image while keeping keyboard interaction and action wiring stable.
  Date/Author: 2026-03-19 / Codex

- Decision: implement the desktop popover motion as a short fade-in plus slight scale-up from the top-right anchor, and the mobile sheet as a short upward slide plus fade.
  Rationale: that matches the requested behavior and fits existing overlay motion patterns in the repo.
  Date/Author: 2026-03-19 / Codex

- Decision: keep typography close to the existing header and settings surface instead of introducing a new type voice for the glass popover.
  Rationale: the user explicitly asked to preserve the current font feel, so the redesign leans on iconography, layering, color, and spacing rather than a typography reset.
  Date/Author: 2026-03-19 / Codex

- Decision: bias the shell and grouped cards toward a compact top-light plus short dark-base shadow stack instead of a softer long-blur treatment.
  Rationale: the zoomed reference depends on that asymmetric shading to create the floating-tile feel the user called out, especially on the grouped rows.
  Date/Author: 2026-03-19 / Codex

- Decision: keep Trading Settings rows visually static on hover instead of darkening the inner row wrapper.
  Rationale: the grouped cards already provide the elevation treatment, and the hover darkening introduced a mismatch against the reference and the user's manual review.
  Date/Author: 2026-03-19 / Codex

- Decision: treat the user's direct review as final acceptance and close this visual refinement pass.
  Rationale: the implementation, tests, and governed QA were already complete; the only open item was explicit user confirmation on the final polish pass, and that confirmation has now been provided.
- Decision: move this plan to `completed` after `hyperopen-6c2n` closed instead of leaving it in `active` for follow-up polish.
  Rationale: `/hyperopen/docs/PLANS.md` reserves `active` for live work tied to an open `bd` issue. The remaining governed browser-QA failures are unrelated route regressions, so this document should remain as a closed historical record.
  Date/Author: 2026-03-20 / Codex

## Outcomes & Retrospective

This pass landed the requested icon-enriched popover treatment: linear row icons, grouped layered cards, anchored motion, and custom switches, while preserving the existing font language and settings behavior. The latest refinement tightened the top inset highlight and bottom falloff so the shell and cards read closer to the floating-tile reference, and the user has now approved the resulting Trading Settings polish.

Repo gates are green. Governed browser QA continues to pass the visual, native-control, and styling-consistency checks for the Trading Settings surface on `/trade`, but the overall run still fails because of unrelated `/portfolio`, `/trade` desktop overflow, and `/vaults` route regressions. Because `hyperopen-6c2n` is now closed, this plan moves to `completed` as the historical implementation record rather than remaining in `active`.

## Context and Orientation

The Trading Settings shell and rows live in `/hyperopen/src/hyperopen/views/header_view.cljs`. That file owns the backdrop, desktop anchored panel, mobile sheet, section headings, row layout, and the inline storage-mode confirmation.

The deterministic view coverage lives in `/hyperopen/test/hyperopen/views/header_view_test.cljs`.

## Proposed Product Scope

The visible settings remain:

- `Remember session`
- `Fill alerts`
- `Animate order book`
- `Fill markers`

This visual pass should add:

- linear 20px row icons
- a darker semi-transparent elevated container
- rounded inner layered cards
- custom toggle switches
- stable row color on hover
- anchored entry and exit motion

Non-goals:

- no new settings
- no runtime or persistence changes
- no changes to the settings copy except small fit-and-finish adjustments required by the new row layout

## Plan of Work

First, restyle the outer shell into a neutral layered popover or sheet with the requested rounded border, shadow, blur, and motion.

Next, update the row layout to icon-left, text-center, control-right, and swap the plain checkbox visuals for custom switch styling backed by the same checkbox input.

Then, add deterministic test coverage for the new section-card structure, icon-bearing rows, and shell styling hooks that matter for stability.

Finally, rerun the required repo gates and governed browser QA for the changed header surface.

## Concrete Steps

1. Keep this plan current while the icon-enriched popover restyle is in progress.

2. Update the Trading Settings shell and rows in:

   `/hyperopen/src/hyperopen/views/header_view.cljs`

3. Update deterministic view coverage in:

   `/hyperopen/test/hyperopen/views/header_view_test.cljs`

4. Run required validation:

   `npm test`
   `npm run test:websocket`
   `npm run check`
   `npm run qa:design-ui -- --changed-files src/hyperopen/views/header_view.cljs --manage-local-app`

Revision note: updated on 2026-03-20 to record `hyperopen-6c2n` closure, capture that the remaining browser-QA failures are unrelated route regressions, and move the plan to `completed`.
