---
owner: platform
status: draft
last_reviewed: 2026-03-08
review_cycle_days: 90
source_of_truth: false
---

# Mobile Positions Card Visual Parity QA (2026-03-08)

## Scope

Manual browser QA for the latest mobile Positions-card parity wave on `/trade`, focused on the screenshot-driven discrepancies called out in review:

- shell/background looked too light and card-like
- summary text treatment differed from Hyperliquid
- coin readability/truncation was suspect
- expanded footer exposed an explicit `Actions` label
- expanded footer buttons looked pill-like and bordered

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
  `/hyperopen/tmp/browser-inspection/manual-mobile-positions-card-visual-parity-2026-03-08T17-38-46-769Z/summary.json`
- Collapsed Positions screenshot:
  `/hyperopen/tmp/browser-inspection/manual-mobile-positions-card-visual-parity-2026-03-08T17-38-46-769Z/positions-collapsed.png`
- Expanded Positions screenshot:
  `/hyperopen/tmp/browser-inspection/manual-mobile-positions-card-visual-parity-2026-03-08T17-38-46-769Z/positions-expanded.png`
- Completed ExecPlan:
  `/hyperopen/docs/exec-plans/completed/2026-03-08-mobile-positions-card-visual-parity.md`

## Manual Checks

1. Warmed the SPA with `/index.html`, then opened the spectate trade route in the iPhone 14 Pro Max viewport.
2. Switched the mobile account panel from `Balances` to `Positions`.
3. Captured the first visible position card and recorded shell styles, summary labels, summary values, and coin truncation metrics.
4. Expanded the same card and recorded footer/action styling metrics from the live DOM.
5. Captured screenshots for the collapsed and expanded states.

## Result

The previously reported mobile Positions-card discrepancies are materially reduced in the final rendered UI.

### Collapsed Card

- The card shell now renders as a darker, flatter surface:
  - `backgroundColor: rgb(8, 22, 31)`
  - `borderColor: rgb(23, 49, 61)`
  - `borderRadius: 8px`
- The summary row now uses neutral text for coin and size rather than side-tinted summary values.
- Live browser metrics showed `coinTruncated: false` on the sampled card.

### Expanded Card

- The expanded view no longer renders an `Actions` label.
- A divider now separates the detail grid from the footer action row.
- Footer actions are plain text controls instead of bordered pill buttons.
- Live browser metrics showed:
  - `hasActionsLabel: false`
  - `dividerPresent: true`
  - `footerButtonsHaveBorders: false`
  - `footerButtonsHaveBackground: false`
- The rendered footer action texts were:
  - `Close`
  - `Margin`
  - `TP/SL`

## Notes

- The sampled live position during QA was `BTC`, not `GOLD`; the spectate dataset changes over time. The parity checks in this pass were about shell styling, text treatment, truncation behavior, and footer presentation rather than a specific instrument.
- Browser text extraction from the detail panel can compress letter spacing in a few labels inside the JSON summary. The screenshots show the actual rendered UI.

## Conclusion

This wave resolved the remaining screenshot-driven mismatches on the mobile Positions card: the shell is flatter and darker, the summary text treatment is closer to Hyperliquid, the sampled coin label was not truncated, and the expanded footer now uses a divider plus flat text actions instead of an `Actions` label and bordered pills.
