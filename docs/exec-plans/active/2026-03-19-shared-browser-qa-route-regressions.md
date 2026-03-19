# Resolve Shared Browser QA Route Regressions

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-v7jc`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

The governed browser review is still red after the Trading Settings rollout, but the remaining failures are route-level regressions outside the settings menu itself. This plan tracks the cleanup pass for those shared issues so governed UI signoff can go green again.

The first implementation slice will target the highest-severity remaining issues on `/vaults`: missing keyboard focus indicators and tablet-width table overflow. After that, the plan will revisit `/portfolio` root overflow and desktop `/trade` root overflow with fresh evidence.

## Progress

- [x] (2026-03-19 18:53Z) Created and claimed `hyperopen-v7jc` to track the remaining shared browser-QA failures after the Trading Settings rollout.
- [x] (2026-03-19 18:54Z) Reviewed the latest governed browser artifacts and identified the first concrete slice: `/vaults` focus-indicator regressions and tablet table overflow.
- [x] (2026-03-19 19:01Z) Implemented the first `/vaults` cleanup slice: added a shared focus-ring treatment to route controls, moved the desktop table breakpoint to `1024px`, and added deterministic coverage for the focus classes and tablet layout fallback.
- [x] (2026-03-19 19:03Z) Re-ran required repo gates and the governed browser review. The `/vaults` tablet overflow cleared, but the route still has focus-indicator failures on dropdown option buttons inside the open native-details menus.
- [ ] Reassess the remaining `/portfolio` and desktop `/trade` overflow issues with the updated artifact set.
- [ ] Resolve the remaining `/vaults` dropdown-option focus audit failure or deliberately replace that menu interaction pattern.

## Surprises & Discoveries

- Observation: the highest-severity remaining failures are on `/vaults`, where multiple buttons and row links explicitly suppress focus rings.
  Evidence: `/hyperopen/src/hyperopen/views/vaults/list_view.cljs` and `tmp/browser-inspection/design-review-2026-03-19T18-43-44-839Z-1e263f37/summary.md`.

- Observation: the `/vaults` tablet overflow is tied to the table layout becoming desktop-style at `768px`, even though the table width slightly exceeds the viewport and is being audited as out-of-viewport.
  Evidence: `tmp/browser-inspection/design-review-2026-03-19T18-43-44-839Z-1e263f37/vaults-route/review-768/probes/layout-audit.json`.

- Observation: `/portfolio` and desktop `/trade` are still failing on root-level vertical overflow, but those issues are structurally separate from the `/vaults` interaction failures and should be handled in a follow-up slice after the first route is stabilized.
  Evidence: `tmp/browser-inspection/design-review-2026-03-19T18-43-44-839Z-1e263f37/summary.md`.

- Observation: the `/vaults` tablet overflow was caused by treating `768px` as desktop layout; moving the route to mobile cards until `1024px` cleared the layout audit at that width.
  Evidence: `tmp/browser-inspection/design-review-2026-03-19T19-00-50-880Z-8c7dcd7b/vaults-route/review-768/probes/layout-audit.json`.

- Observation: the remaining `/vaults` interaction failures are isolated to the dropdown option buttons inside the open `details` panels. Those buttons now carry the shared focus classes, but the governed focus walk still does not observe an applied focus indicator there.
  Evidence: `tmp/browser-inspection/design-review-2026-03-19T19-00-50-880Z-8c7dcd7b/vaults-route/review-375/probes/focus-walk.json`.

## Decision Log

- Decision: start the shared cleanup on `/vaults` before `/portfolio` or `/trade`.
  Rationale: the `/vaults` failures include high-severity keyboard-focus regressions and a clear tablet overflow cause, making them the highest-severity and most actionable blockers.
  Date/Author: 2026-03-19 / Codex

- Decision: move the vaults desktop-table breakpoint from `768px` to `1024px`.
  Rationale: the governed tablet audit showed the table was slightly wider than the viewport at `768px`; using the existing mobile-card layout until desktop widths avoids that overflow without inventing a new compact table variant.
  Date/Author: 2026-03-19 / Codex

## Outcomes & Retrospective

The first shared cleanup slice improved `/vaults`, but it did not finish it. The tablet layout regression is fixed, and deterministic coverage now pins both the new breakpoint and the focus classes. The remaining `/vaults` failures are confined to dropdown option buttons inside native-details menus, while `/portfolio` and desktop `/trade` remain separate overflow follow-ups.

## Context and Orientation

The affected view files are:

- `/hyperopen/src/hyperopen/views/vaults/list_view.cljs`
- `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
- `/hyperopen/src/hyperopen/views/trade_view.cljs`

The relevant governed browser artifacts are under:

- `/hyperopen/tmp/browser-inspection/design-review-2026-03-19T18-43-44-839Z-1e263f37/`

## Plan of Work

First, fix the `/vaults` focus-indicator regressions and tablet table-overflow trigger in the route view, then add deterministic coverage for the resulting focus-visible classes and layout breakpoint behavior.

Next, rerun the required repo gates and governed browser review to see how much of the shared failure set is cleared.

Finally, use the updated artifact set to target the remaining `/portfolio` and desktop `/trade` overflow issues in a second slice.

## Concrete Steps

1. Update `/hyperopen/src/hyperopen/views/vaults/list_view.cljs`.

2. Add or adjust deterministic coverage under `/hyperopen/test/hyperopen/views/`.

3. Run:

   `npm test`
   `npm run test:websocket`
   `npm run check`
   `npm run qa:design-ui -- --changed-files src/hyperopen/views/vaults/list_view.cljs --manage-local-app`
