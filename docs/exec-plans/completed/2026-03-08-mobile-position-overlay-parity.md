# Mobile Position Overlay Parity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

The mobile Positions card is visually closer to Hyperliquid after the last wave, but the action/edit layer still diverges. The remaining gap is in the triggered experience: inline edit affordances for `Margin` and `TP/SL`, and the way the `Close`, `Margin`, and `TP/SL` surfaces present on mobile.

After this wave, the mobile expanded position card should expose inline edit pencils for the editable fields and all three mobile position surfaces should present as bottom-sheet style overlays instead of anchored desktop geometry.

## Progress

- [x] (2026-03-08 17:44Z) Audited the current mobile position-card action layer and found that the inline `Margin`/`TP/SL` pencils are absent and the overlay layout functions still compute anchored desktop-style geometry.
- [x] (2026-03-08 17:45Z) Filed epic `hyperopen-706` with child issues `hyperopen-06b` and `hyperopen-0g3`.
- [x] (2026-03-08 17:45Z) Authored this ExecPlan for the overlay parity wave.
- [x] (2026-03-08 17:58Z) Restored inline `Edit Margin` and `Edit TP/SL` affordances inside the mobile expanded position card and wired them to the existing overlay actions.
- [x] (2026-03-08 18:01Z) Converted the mobile `Close`, `Margin`, and `TP/SL` surfaces to bottom-sheet presentation while preserving the anchored desktop layout branch.
- [x] (2026-03-08 18:06Z) Added regression coverage for the mobile edit affordances and the mobile overlay layout branches.
- [x] (2026-03-08 18:18Z) Ran `npm test`, `npm run check`, `npm run test:websocket`, and completed iPhone 14 Pro Max browser QA with screenshots and JSON evidence under `/hyperopen/tmp/browser-inspection/manual-mobile-position-overlay-parity-2026-03-08T18-18-12-480Z/`.

## Surprises & Discoveries

- Observation: The footer action text mismatch from the prior screenshot is already fixed on the current branch, but the inline edit pencils were lost in the same simplification pass.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` currently renders footer actions as `Close`, `Margin`, and `TP/SL`, but the mobile detail grid no longer includes the previous edit-icon affordances for the `Margin` and `TP/SL` fields.

- Observation: The mobile overlay surfaces are still driven by anchor-relative layout functions, which keeps them behaving like desktop popovers/modals even when the viewport is phone-sized.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/position_margin_modal.cljs`, `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs`, and `/hyperopen/src/hyperopen/views/account_info/position_reduce_popover.cljs` all compute `left`/`top` anchored styles from trigger bounds rather than switching to a bottom-sheet layout on small viewports.

- Observation: During browser QA, each open mobile overlay still had a duplicate hidden counterpart in the DOM because both the hidden desktop rows and visible mobile cards render the active overlay subtree for the same position key.
  Evidence: The final iPhone QA summary at `/hyperopen/tmp/browser-inspection/manual-mobile-position-overlay-parity-2026-03-08T18-18-12-480Z/summary.json` recorded `overlayCount: 2` for all three mobile overlays even though the screenshots only showed one visible sheet. A follow-up issue was filed so the parity wave could close without mixing in a larger ownership refactor.

## Decision Log

- Decision: Preserve the existing action semantics (`Close`, `Margin`, `TP/SL`) and focus this wave on affordance visibility and mobile presentation rather than inventing new workflows.
  Rationale: The goal is parity in how the user reaches and edits those flows on mobile, not a domain rewrite of order behavior.
  Date/Author: 2026-03-08 / Codex

- Decision: Treat the mobile overlay behavior as a viewport-specific layout policy inside the existing overlay views instead of creating separate mobile-only components.
  Rationale: The current overlay views already encapsulate the field logic and tests. Adding a mobile layout branch is lower risk than duplicating full forms.
  Date/Author: 2026-03-08 / Codex

- Decision: Use browser-side `HYPEROPEN_DEBUG.dispatch(...)` with an explicit iPhone anchor during headless QA for the overlay-opening actions.
  Rationale: The browser-inspection environment can verify the live rendered UI, but CDP-driven programmatic clicks do not reliably preserve the `:event.currentTarget/bounds` placeholder used by these actions. Dispatching the same action with an explicit phone-sized anchor exercises the intended mobile layout branch deterministically.
  Date/Author: 2026-03-08 / Codex

## Outcomes & Retrospective

This wave is complete. The mobile Positions card now exposes inline pencil affordances for `Margin` and `TP/SL`, and the three position action surfaces render as bottom sheets on the iPhone viewport instead of anchored desktop popovers.

Validation and QA both passed:

- `npm test`
- `npm run check`
- `npm run test:websocket`
- manual iPhone 14 Pro Max browser QA under `/hyperopen/tmp/browser-inspection/manual-mobile-position-overlay-parity-2026-03-08T18-18-12-480Z/`

The final screenshots show the intended parity improvement:

- the expanded card now includes inline edit pencils next to `Margin` and `TP/SL`
- `Adjust Margin`, `TP/SL for Position`, and `Close Position` now present as bottom sheets with a dimmed backdrop
- the mobile footer actions remain `Close`, `Margin`, and `TP/SL`

Remaining work is narrower and separately tracked: the hidden desktop and visible mobile trees still both render overlay DOM for the active position, which leaves a duplicate hidden surface in the DOM during mobile QA.
