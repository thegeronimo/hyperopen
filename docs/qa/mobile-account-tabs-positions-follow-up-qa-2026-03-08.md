---
owner: platform
status: draft
last_reviewed: 2026-03-08
review_cycle_days: 90
source_of_truth: false
---

# Mobile Account Tabs Positions Follow-up QA (2026-03-08)

## Scope

Manual browser QA for the remaining mobile account-panel parity gaps on `/trade`:

- flatter tab-strip styling on phone-sized viewports
- mobile Positions cards replacing the dense desktop table rows

Viewport used:

- iPhone 14 Pro Max: `430x932`

Route and data fixture:

- `http://localhost:8080/trade?spectate=0x162cc7c861ebd0c06b3d72319201150482518185`

The spectate address was chosen because it renders live balances, positions, and history data without requiring a connected wallet.

## Validation

Completed on the final code:

- `npm test`
- `npm run check`
- `npm run test:websocket`

## Browser Artifacts

- Summary JSON:
  `/hyperopen/tmp/browser-inspection/manual-mobile-account-tabs-positions-follow-up-2026-03-08T17-19-04-353Z/summary.json`
- Positions-selected screenshot:
  `/hyperopen/tmp/browser-inspection/manual-mobile-account-tabs-positions-follow-up-2026-03-08T17-19-04-353Z/positions-tab-selected.png`
- Expanded positions screenshot:
  `/hyperopen/tmp/browser-inspection/manual-mobile-account-tabs-positions-follow-up-2026-03-08T17-19-04-353Z/positions-expanded.png`
- Completed ExecPlan:
  `/hyperopen/docs/exec-plans/completed/2026-03-08-mobile-account-tabs-positions-follow-up.md`

## Manual Checks

1. Warmed the SPA with `/index.html`, then navigated to the spectate trade route in the iPhone 14 Pro Max viewport.
2. Waited for the mobile account panel tab strip to render under the chart.
3. Verified the phone tab strip no longer uses the old filled selected-tab look and instead reads as underline-first navigation.
4. Switched from `Balances` to `Positions`.
5. Confirmed the Positions mobile viewport rendered cards and that the desktop rows viewport remained hidden on phone.
6. Expanded the first visible position card and recorded the live summary/detail labels, overlap state, and overflow metrics.
7. Captured screenshots for the selected Positions tab state and the expanded position card state.

## Result

This follow-up resolved the remaining mobile account-panel discrepancies that prompted the wave.

### Tab Strip

- The phone tab strip now renders with transparent tab backgrounds instead of the old filled selected state.
- The selected tab is communicated by underline and text emphasis, which is visually closer to Hyperliquid’s mobile treatment.
- Tabs remained single-line in the live iPhone capture.

### Positions

- The Positions tab now renders as mobile summary cards instead of the dense table layout.
- Live browser metrics showed:
  - `16` visible position cards
  - `desktopRowsDisplay: none`
  - `noHorizontalOverlap: true`
  - `overflowNodeCount: 0`
- The expanded card exposed the expected summary labels:
  - `Coin`
  - `Size`
  - `PNL (ROE %)`
- The expanded card exposed the expected detail labels:
  - `Entry Price`
  - `Mark Price`
  - `Liq. Price`
  - `Position Value`
  - `Margin`
  - `TP/SL`
  - `Funding`
  - `Actions`

## Notes

- This is live spectate data, so counts, PNL values, and the specific first visible position can change between runs.
- The exported `detailText` in the summary JSON reflects browser text extraction from the rendered UI and can compress letter spacing in a few labels. The screenshots show the actual visual presentation.

## Conclusion

The mobile account panel now covers the missing Positions implementation and removes the desktop-like filled tab styling on phone. Live browser QA on the iPhone 14 Pro Max viewport confirms the tab strip is flatter and the Positions surface now follows the same expandable-card pattern as the other mobile account tabs.
