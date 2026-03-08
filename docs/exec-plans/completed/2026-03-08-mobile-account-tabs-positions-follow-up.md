# Mobile Account Tabs Positions Follow-up

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

The previous mobile account-tab wave fixed Balances and Trade History, but two parity gaps remain on the trade page at phone sizes:

1. the tab strip still looks more like a desktop-selected nav than Hyperliquid’s flatter underline-first treatment
2. the Positions tab still renders the dense desktop table instead of mobile summary cards

After this follow-up, the mobile account panel on `/trade` should have a lighter tab strip and a Positions tab that follows the same summary-and-expand pattern already used for Balances and Trade History.

## Progress

- [x] (2026-03-08 16:59Z) Audited the current tab strip in `/hyperopen/src/hyperopen/views/account_info_view.cljs` and confirmed Positions still uses only the desktop table renderer in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`.
- [x] (2026-03-08 16:59Z) Filed tracking epic `hyperopen-3qy` with child issues `hyperopen-ya6` and `hyperopen-ad9`.
- [x] (2026-03-08 17:01Z) Authored this ExecPlan for the follow-up wave.
- [x] (2026-03-08 17:05Z) Updated the mobile account tab strip to remove the filled selected-tab treatment on small screens while preserving the desktop `lg:` background behavior.
- [x] (2026-03-08 17:05Z) Extended the shared mobile expansion state and toggle action policy to support `:positions`.
- [x] (2026-03-08 17:06Z) Implemented mobile Positions summary cards with inline expansion for `Entry Price`, `Mark Price`, `Liq. Price`, `Position Value`, `Margin`, `TP/SL`, `Funding`, and supported HyperOpen actions.
- [x] (2026-03-08 17:08Z) Added regression coverage for the mobile Positions renderer, the expanded-card action state, and the flatter mobile tab-strip styling assumptions.
- [x] (2026-03-08 17:19Z) Ran `npm test`, `npm run check`, `npm run test:websocket`, and completed iPhone 14 Pro Max browser QA with artifacts under `/hyperopen/tmp/browser-inspection/manual-mobile-account-tabs-positions-follow-up-2026-03-08T17-19-04-353Z/`.

## Surprises & Discoveries

- Observation: Positions already exposes all the necessary data and action seams for a good mobile card, including margin edit, reduce popover, and TP/SL edit triggers.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` already formats `Margin`, `Funding`, `TP/SL`, and wires `:actions/open-position-margin-modal`, `:actions/open-position-reduce-popover`, and `:actions/open-position-tpsl-modal`.

- Observation: Hyperliquid’s screenshot shows `Reverse`, but Hyperopen does not currently implement a reverse-position action.
  Evidence: Repository search for `Reverse` returns no corresponding action path; existing mobile-safe position actions are reduce, margin edit, and TP/SL edit.

## Decision Log

- Decision: Match the Hyperliquid mobile Positions information architecture, but keep Hyperopen’s existing supported actions instead of inventing a non-existent `Reverse` action.
  Rationale: The user asked to close the visual/interaction discrepancy, not to create unsupported trading behavior. Reusing real actions preserves correctness.
  Date/Author: 2026-03-08 / Codex

- Decision: Keep the desktop Positions table subtree intact and add a separate `lg:hidden` mobile card renderer, just like the prior Balances and Trade History wave.
  Rationale: This minimizes regression risk and keeps the existing desktop-specific tests and overlays meaningful.
  Date/Author: 2026-03-08 / Codex

## Outcomes & Retrospective

This follow-up closed the two remaining gaps from the previous mobile account-tab wave.

- The mobile tab strip now reads as underline-first navigation instead of a desktop-style filled tab bar.
- The Positions tab now matches the Balances and Trade History mobile pattern with summary cards and inline expansion.
- Live browser QA on the iPhone 14 Pro Max viewport confirmed:
  - `16` visible mobile position cards
  - inline expansion on a live position card
  - no horizontal overlap in the expanded card
  - `account-tab-rows-viewport` computed `display: none` on mobile, so the desktop table stays hidden

Evidence:

- QA note: `/hyperopen/docs/qa/mobile-account-tabs-positions-follow-up-qa-2026-03-08.md`
- Browser summary JSON: `/hyperopen/tmp/browser-inspection/manual-mobile-account-tabs-positions-follow-up-2026-03-08T17-19-04-353Z/summary.json`
