# Asset Selector Density and Separator Parity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, the asset selector in Hyperopen visually matches Hyperliquid more closely in row density and table rhythm. Users now see tighter rows, no horizontal row separators, and a compact table header that aligns with Hyperliquid’s selector scan pattern.

## Progress

- [x] (2026-02-19 17:03Z) Captured Hyperliquid selector screenshot and computed style metrics with Playwright.
- [x] (2026-02-19 17:06Z) Captured Hyperopen pre-change selector screenshot and computed style metrics for direct comparison.
- [x] (2026-02-19 17:12Z) Implemented selector row/header density and separator changes in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`.
- [x] (2026-02-19 17:13Z) Updated selector view tests in `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs` for new row height and virtual-window behavior.
- [x] (2026-02-19 17:16Z) Ran required validation gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-19 17:17Z) Captured post-change selector screenshot and parity spot-check metrics.

## Surprises & Discoveries

- Observation: Hyperliquid selector rows and headers both resolve to 24px visual height with 0px bottom borders.
  Evidence: Browser measurements: `rowTopDeltaPx = 24`, row border bottom `0px solid`, header height `24`.
- Observation: Hyperopen pre-change row density was exactly double Hyperliquid’s list rhythm.
  Evidence: Pre-change measurements: `row height = 48`, `rowTopDeltaPx = 48`, row border bottom `1px solid`, header height `41`.
- Observation: Hyperopen post-change search input became denser than Hyperliquid while still readable.
  Evidence: Post-change measurement: `search input height = 26` vs Hyperliquid `31`.

## Decision Log

- Decision: Match Hyperliquid’s list rhythm by setting selector row and header heights to 24px and removing explicit row separators.
  Rationale: This addresses the user-visible disparity with minimal architectural risk and keeps interaction behavior untouched.
  Date/Author: 2026-02-19 / Codex
- Decision: Reduce selector list viewport to `max-h-64` and align virtualization constants to `row-height=24`, `viewport=256`.
  Rationale: Prevents the dropdown from becoming overly tall after row-density changes and keeps virtual window math accurate.
  Date/Author: 2026-02-19 / Codex
- Decision: Keep existing grid-column model rather than converting to `<table>` markup.
  Rationale: The visual gap was solved without a structural rewrite, reducing scope and regression risk.
  Date/Author: 2026-02-19 / Codex

## Outcomes & Retrospective

Implemented parity-focused density updates in the asset selector while preserving existing behavior and action flow.

Delivered visual outcomes:

- Row height changed from 48px to 24px.
- Header row height changed from 41px to 24px.
- Horizontal row separator lines removed (`border-b` removed on data rows and header row).
- Selected row now uses background highlight instead of ring border, matching Hyperliquid’s flatter emphasis.
- List viewport reduced to 256px to maintain a compact dropdown height.

Validation results:

- `npm run check` passed.
- `npm test` passed (1129 tests, 5187 assertions, 0 failures).
- `npm run test:websocket` passed (135 tests, 587 assertions, 0 failures).

## Context and Orientation

The selector UI lives in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`. This file defines search controls, tab row, sortable header row, asset list row rendering, and the virtualization constants that determine which rows render for a given scroll position.

Selector behavior tests live in `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`.

In this repository, the selector rows are rendered as a 12-column grid and virtualized with fixed `asset-list-row-height-px` and `asset-list-viewport-height-px` constants. Any row-height change must be reflected in both the rendered class list and virtualization math.

## Plan of Work

Inspect Hyperliquid and Hyperopen selectors with the same viewport using Playwright and gather concrete metrics for row height, row spacing, header height, and row border styles.

Apply compactness edits in `asset_selector_view.cljs`: tighten row/header classes, remove row separators, shrink icon/gap spacing, and update list max-height plus virtualization constants.

Update selector tests to assert the new structural class tokens and adjust virtual-scroll expectation values so tests still prove that deep scrolling excludes top rows and includes the expected mid-list row.

Run required repository validation gates and verify selector appearance via a fresh browser screenshot.

## Concrete Steps

From `/hyperopen`:

1. Capture parity baselines with Playwright:
   - `node` script against `https://app.hyperliquid.xyz/trade` (open selector by clicking `HYPE/USDC`).
   - `node` script against `http://localhost:8080/index.html` (open selector by clicking `BTC-USDC`).
2. Edit `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`:
   - row classes (`h-12` -> `h-6`, remove `border-b`, compact spacing),
   - header row classes (remove separator/uppercase tracking, set 24px height rhythm),
   - virtualization/list viewport constants (`48/384` -> `24/256`) and list max-height (`max-h-96` -> `max-h-64`).
3. Edit `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`:
   - class assertions for row height/separator,
   - highlighted-row class assertion,
   - deep-scroll fixture (`4300` -> `2200`) for new row-height math.
4. Run required gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
5. Re-capture post-change selector screenshot and confirm core parity metrics.

## Validation and Acceptance

Acceptance criteria:

- Selector rows visually match Hyperliquid density (`~24px` row cadence).
- Data rows have no horizontal separator borders.
- Header row uses compact single-row rhythm similar to Hyperliquid.
- Virtualized scrolling still renders deep-list rows correctly.
- Required validation gates pass.

Observed acceptance evidence:

- Post-change browser metrics: row height `24`, row top delta `24`, row border bottom `0px solid`, header height `24`, header border bottom `0px solid`.
- Required validation gates all passed.

## Idempotence and Recovery

Changes are idempotent and can be re-applied safely. If visual density regression is reported, recover by reverting only `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` and `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs` together so virtualization constants and tests remain in sync.

## Artifacts and Notes

Browser inspection artifacts:

- `/hyperopen/tmp/browser-inspection/inspect-2026-02-19-asset-selector-density-parity/hyperliquid-selector.png`
- `/hyperopen/tmp/browser-inspection/inspect-2026-02-19-asset-selector-density-parity/hyperopen-before.png`
- `/hyperopen/tmp/browser-inspection/inspect-2026-02-19-asset-selector-density-parity/hyperopen-after.png`

## Interfaces and Dependencies

No new dependencies were added. Existing selector interfaces and action/event contracts remain unchanged.

Plan revision note: 2026-02-19 17:03Z - Initial plan created for selector density and separator parity.
Plan revision note: 2026-02-19 17:17Z - Plan finalized with implemented changes, validation results, and captured artifacts.
