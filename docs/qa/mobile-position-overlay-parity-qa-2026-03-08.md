---
owner: platform
status: draft
last_reviewed: 2026-03-08
review_cycle_days: 90
source_of_truth: false
---

# Mobile Position Overlay Parity QA (2026-03-08)

## Scope

Manual browser QA for the latest mobile Positions action-layer parity wave on `/trade`, focused on the remaining discrepancies called out in review:

- inline `Margin` and `TP/SL` edit pencils were missing from the expanded card
- the mobile action texts needed to align with `Close`, `Margin`, and `TP/SL`
- the triggered `Margin` and `TP/SL` surfaces needed to present like bottom sheets
- the `Close` action needed the same mobile bottom-sheet treatment

Viewport used:

- iPhone 14 Pro Max: `430x932`

Route and data fixture:

- `http://localhost:8080/trade?spectate=0x162cc7c861ebd0c06b3d72319201150482518185`

The spectate address was chosen because it renders a populated Positions tab without requiring a connected wallet.

## Validation

Completed on the final code:

- `npm test`
- `npm run check`
- `npm run test:websocket`

## Browser Artifacts

- Summary JSON:
  `/hyperopen/tmp/browser-inspection/manual-mobile-position-overlay-parity-2026-03-08T18-18-12-480Z/summary.json`
- Collapsed Positions screenshot:
  `/hyperopen/tmp/browser-inspection/manual-mobile-position-overlay-parity-2026-03-08T18-18-12-480Z/positions-collapsed.png`
- Expanded Positions screenshot:
  `/hyperopen/tmp/browser-inspection/manual-mobile-position-overlay-parity-2026-03-08T18-18-12-480Z/positions-expanded.png`
- Margin sheet screenshot:
  `/hyperopen/tmp/browser-inspection/manual-mobile-position-overlay-parity-2026-03-08T18-18-12-480Z/positions-margin-sheet.png`
- TP/SL sheet screenshot:
  `/hyperopen/tmp/browser-inspection/manual-mobile-position-overlay-parity-2026-03-08T18-18-12-480Z/positions-tpsl-sheet.png`
- Close sheet screenshot:
  `/hyperopen/tmp/browser-inspection/manual-mobile-position-overlay-parity-2026-03-08T18-18-12-480Z/positions-close-sheet.png`

## Manual Checks

1. Warmed the SPA with `/index.html`, then opened the spectate trade route in the iPhone 14 Pro Max viewport.
2. Switched the mobile account panel to `Positions`.
3. Captured the first visible Positions card in collapsed and expanded states.
4. Verified the expanded card exposed inline `Edit Margin` and `Edit TP/SL` affordances.
5. Opened `Margin`, `TP/SL`, and `Close` with browser-side `HYPEROPEN_DEBUG.dispatch(...)` using an explicit iPhone anchor so headless QA exercised the same bottom-sheet branch as a real mobile click.
6. Captured a screenshot of each open overlay and recorded the rendered surface class/title from the live DOM.

## Result

The remaining mobile Positions action-layer discrepancies are materially reduced in the final rendered UI.

### Expanded Card

- The expanded Positions card now exposes inline edit pencils for:
  - `Margin`
  - `TP/SL`
- The browser summary recorded the expected footer actions:
  - `Close`
  - `Margin`
  - `TP/SL`

### Triggered Surfaces

- `Adjust Margin` now renders as a mobile bottom sheet with:
  - dimmed backdrop
  - top-right close affordance
  - full-width bottom-anchored surface
- `TP/SL for Position` now renders as the same bottom-sheet pattern.
- `Close Position` now renders as the same bottom-sheet pattern.

Live DOM checks recorded for all three overlays:

- `layerPresent: true`
- `surfacePresent: true`
- surface class included:
  `absolute inset-x-0 bottom-0 ... rounded-t-[22px] ... bg-[#06131a] ...`

## Notes

- The browser-inspection environment can verify the live rendered UI, but CDP-driven programmatic clicks do not reliably preserve the `:event.currentTarget/bounds` placeholder used by these overlay actions. For that reason, the overlay-opening step in this QA pass used `HYPEROPEN_DEBUG.dispatch(...)` with an explicit iPhone-sized anchor instead of synthetic DOM clicks.
- The QA summary recorded `overlayCount: 2` for each open overlay. The screenshots show only one visible sheet; the second instance is a hidden duplicate from the desktop/mobile dual-render structure and is tracked separately as follow-up work.

## Conclusion

This wave closes the main mobile Positions action-layer parity gap. The expanded card now exposes the missing edit affordances, and `Close`, `Margin`, and `TP/SL` all present as bottom sheets on the iPhone viewport instead of anchored desktop overlays.
