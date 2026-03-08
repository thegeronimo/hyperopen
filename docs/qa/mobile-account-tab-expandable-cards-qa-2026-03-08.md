---
owner: platform
status: draft
last_reviewed: 2026-03-08
review_cycle_days: 90
source_of_truth: false
---

# Mobile Account Tab Expandable Cards QA (2026-03-08)

## Scope

Manual browser QA for the mobile account-tab card conversion on `/trade`, focused on the Balances and Trade History tabs inside the account panel.

Viewport used:

- iPhone 14 Pro Max: `430x932`

Route and data fixture:

- `http://localhost:8080/trade?spectate=0x162cc7c861ebd0c06b3d72319201150482518185`

The spectate address was chosen because it renders populated balances, positions, open orders, and trade-history data in a live browser session.

## Validation

Completed on the final code:

- `npm test`
- `npm run check`
- `npm run test:websocket`

## Browser Artifacts

- Summary JSON:
  `/hyperopen/tmp/browser-inspection/manual-mobile-account-tabs-2026-03-08T16-38-40Z/summary.json`
- Expanded balances screenshot:
  `/hyperopen/tmp/browser-inspection/manual-mobile-account-tabs-2026-03-08T16-38-40Z/balances-expanded.png`
- Expanded trade-history screenshot:
  `/hyperopen/tmp/browser-inspection/manual-mobile-account-tabs-2026-03-08T16-38-40Z/trade-history-expanded-stable.png`
- Completed ExecPlan:
  `/hyperopen/docs/exec-plans/completed/2026-03-08-mobile-account-tab-expandable-cards.md`

## Manual Checks

1. Started an iPhone-configured browser-inspection session and warmed the SPA with `/index.html`.
2. Navigated to `/trade?spectate=0x162cc7c861ebd0c06b3d72319201150482518185`.
3. Verified the mobile account panel rendered populated Balances cards instead of the old dense table rows.
4. Expanded the first visible balance card and recorded summary labels, expansion state, and overflow metrics from the live DOM.
5. Switched to `Trade History`, expanded a visible fill card, and recorded the same metrics.
6. Captured screenshots of the rendered mobile account panel states.

## Result

The mobile card conversion fixed the original overlap/compression problem on both targeted tabs.

### Balances

- Visible data rendered as summary cards, not dense desktop-style rows.
- The first expanded card exposed the expected three-field summary:
  - `Coin`
  - `USDC Value`
  - `Total Balance`
- Inline expansion worked and revealed `Available Balance`, `PNL (ROE %)`, and action chips.
- Live DOM metrics showed:
  - `21` visible mobile balance cards
  - `noHorizontalOverlap: true`
  - `overflowNodeCount: 0`

### Trade History

- Visible data rendered as summary cards with one tappable row per fill.
- The expanded card exposed the expected three-field summary:
  - `Coin`
  - `Time`
  - `Size`
- Inline expansion revealed detail fields including `Direction`, `Price`, `Trade Value`, `Fee`, and `Closed PNL`.
- Live DOM metrics showed:
  - `50` visible mobile trade-history cards on the current page
  - `noHorizontalOverlap: true`
  - timestamp cells remain tight and may wrap, but they no longer collide with adjacent columns

## Notes

- Trade History is a live stream in spectate mode, so the newest fill can reorder between click and screenshot. The stable screenshot uses an older visible fill card to avoid capture churn.
- Trade History timestamps are still compact enough that the text can wrap within its summary cell. That is a density choice, not the original overlap failure, and the summary columns remain readable in-browser.

## Conclusion

This wave achieved the intended mobile-parity behavior for the account panel: Balances and Trade History now use a Hyperliquid-style summary-and-expand pattern instead of cramped desktop tables. The original overlapping-column problem is resolved in live browser QA.
