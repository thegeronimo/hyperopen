# Mobile Positions Card Visual Parity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

The previous mobile account-tab waves implemented a working Positions card, but the mobile visual treatment still diverges from Hyperliquid in ways that are obvious in screenshot comparison. The remaining gaps are concentrated in the card shell, summary typography and truncation, and the expanded footer/action treatment.

After this wave, the mobile Positions card on `/trade` should feel materially closer to Hyperliquid at phone sizes: darker/flatter shell, cleaner summary row, readable coin label, and a footer that uses a divider plus text-led actions instead of bordered pills and an explicit `Actions` label.

## Progress

- [x] (2026-03-08 17:28Z) Audited the current mobile Positions renderer in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` and the shared card shell in `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs`.
- [x] (2026-03-08 17:29Z) Filed epic `hyperopen-0mj` with child issues `hyperopen-jxz` and `hyperopen-wuw`.
- [x] (2026-03-08 17:30Z) Authored this ExecPlan for the visual-parity wave.
- [x] (2026-03-08 17:33Z) Cataloged the remaining discrepancies from screenshot review and live browser inspection: heavier shell/background, side-tinted summary text, coin readability risk, explicit `Actions` label, and bordered footer pills.
- [x] (2026-03-08 17:35Z) Restyled the mobile Positions shell and summary row with a darker/flatter surface, neutral summary text, tighter spacing, and a wider first summary column.
- [x] (2026-03-08 17:35Z) Removed the explicit `Actions` detail item and replaced it with a divider plus flatter text-led footer actions without borders or pill backgrounds.
- [x] (2026-03-08 17:36Z) Added regression coverage for the new mobile Positions-card shell and footer presentation in `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`.
- [x] (2026-03-08 17:38Z) Completed iPhone 14 Pro Max browser QA and captured evidence under `/hyperopen/tmp/browser-inspection/manual-mobile-positions-card-visual-parity-2026-03-08T17-38-46-769Z/`.

## Surprises & Discoveries

- Observation: The shared mobile card shell still carries a rounded bordered-card treatment that was good enough for the first wave, but it is heavier than Hyperliquid’s flatter row-like surface.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs` uses `rounded-xl`, `border`, and `bg-base-200/70` for every mobile account card.

- Observation: The coin summary cell is currently allowed to truncate because the summary grid gives the first column too little priority compared to the size and PNL columns.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs` currently uses `grid-cols-[minmax(0,1fr)_minmax(0,0.9fr)_minmax(0,1fr)_auto]`, and `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` renders the coin cell with a truncating base label.

- Observation: The mobile summary row was still carrying long/short color semantics on the coin and size text, while the Hyperliquid reference treatment keeps those summary values neutral and reserves color mostly for chips and PNL.
  Evidence: Screenshot review showed Hyperliquid rendering white coin and size text; the pre-fix local browser probe still reported side-toned coin and size classes from `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`.

## Decision Log

- Decision: Keep the existing mobile summary-and-expand information architecture and focus this wave on visual parity, not another structural rewrite.
  Rationale: The current mobile Positions cards already solve the original dense-table problem. The remaining gap is mostly styling, spacing, and footer treatment.
  Date/Author: 2026-03-08 / Codex

- Decision: Adjust the shared mobile card primitive only where the new treatment still works for Balances and Trade History, and keep any Positions-specific parity work in the Positions renderer.
  Rationale: The account panel now has multiple tabs on the same mobile-card system. Shared improvements should stay safe across tabs, while coin-row and footer tweaks can remain Positions-specific.
  Date/Author: 2026-03-08 / Codex

- Decision: Make the mobile Positions summary text neutral instead of side-tinted, and keep color emphasis on the leverage/dex chips plus PNL.
  Rationale: That matches the screenshot review more closely and reduces the visual mismatch the user called out as “text is different.”
  Date/Author: 2026-03-08 / Codex

## Outcomes & Retrospective

This wave closed the remaining screenshot-driven visual gaps on the mobile Positions card.

- The collapsed card shell is now darker and flatter, with a subtler border and less “card” weight.
- The summary row now uses neutral coin and size text, while PNL remains color-coded.
- The expanded state now removes the `Actions` label and uses a divider plus plain text footer actions with no borders or pill backgrounds.
- Final browser QA on the iPhone 14 Pro Max viewport confirmed:
  - `coinTruncated: false`
  - `hasActionsLabel: false`
  - `dividerPresent: true`
  - `footerButtonsHaveBorders: false`
  - `footerButtonsHaveBackground: false`

Evidence:

- QA note: `/hyperopen/docs/qa/mobile-positions-card-visual-parity-qa-2026-03-08.md`
- Browser summary JSON: `/hyperopen/tmp/browser-inspection/manual-mobile-positions-card-visual-parity-2026-03-08T17-38-46-769Z/summary.json`
