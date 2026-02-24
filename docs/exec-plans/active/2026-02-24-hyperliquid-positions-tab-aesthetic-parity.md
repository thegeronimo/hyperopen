# Hyperliquid Positions Tab Aesthetic Parity (Trade Account Table)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, Hyperopen's Positions tab in the trade account table will match Hyperliquid's visual language for row polarity, numeric presentation, tooltip affordances, and single-line density. A user will be able to open the Positions tab and immediately see long vs short direction encoded the same way Hyperliquid does (coin stripe color, coin/size coloring, no signed short size), with matching one-line PNL and tooltip-underlined headers (`PNL`, `Margin`, `Funding`).

A user can verify this by loading a long row and a short row and confirming the cell-level styling and text layout match Hyperliquid conventions in one screenshot: short coin row appears red-striped/red text, short size is absolute (no minus sign), PNL is single-line with percentage inline, and header tooltip affordances are present.

## Progress

- [x] (2026-02-24 15:18Z) Audited current Hyperopen implementation in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`, `/hyperopen/src/hyperopen/views/account_info/shared.cljs`, and tests under `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`.
- [x] (2026-02-24 15:18Z) Collected Hyperliquid live evidence via browser inspection artifacts and frontend bundle code (`/hyperopen/tmp/browser-inspection/inspect-2026-02-24T15-15-36-731Z-bd34d304/*`, `/hyperopen/tmp/hyperliquid-main-split.js`).
- [x] (2026-02-24 15:18Z) Identified concrete aesthetic deltas and mapped them to exact Hyperopen files to edit.
- [x] (2026-02-24 15:18Z) Authored this ExecPlan.
- [ ] Implement parity UI changes in positions rendering, header tooltips, and shared style tokens.
- [ ] Add/adjust tests for short-row styling, size sign behavior, single-line PNL, and tooltip affordances.
- [ ] Run required validation gates: `npm run check`, `npm test`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Hyperliquid's row gradient for the Coin cell is not generic table styling; it is applied only to first-column cells when the row carries `colorType` (`buy`/`sell`) and uses separate long/short gradients.
  Evidence: `/hyperopen/tmp/hyperliquid-main-split.js` line region around `line-<row>-cell-<key>` style injection includes `0===r&&"buy"===e.colorType` and `0===r&&"sell"===e.colorType` branches with explicit gradient strings.

- Observation: Hyperliquid's position size is intentionally rendered as absolute size plus side color, not signed quantity text.
  Evidence: `/hyperopen/tmp/hyperliquid-main-split.js` positions row builder computes `K=Math.abs(N)` and renders size via `sRF(..., side, K, ...)`.

- Observation: Hyperliquid's `PNL (ROE %)` is a single inline string in one element and not split into two stacked lines.
  Evidence: `/hyperopen/tmp/hyperliquid-main-split.js` builds `te` and `ne` as one combined string, then assigns `pnl.element` once (no second percentage block).

- Observation: Header tooltip affordance is systematic: any column with an `explanation` shows dashed underline and hover tooltip in the shared data-table renderer.
  Evidence: `/hyperopen/tmp/hyperliquid-main-split.js` table header renderer uses `underline: !(Rwg(t.explanation))` and wraps header with tooltip when `t.explanation` exists.

- Observation: Hyperliquid uses side-aware pill colors for leverage/coin chip (`pillLongBackground` vs `pillShortBackground`), while Hyperopen currently uses a single green chip style for all sides.
  Evidence: theme token block in `/hyperopen/tmp/hyperliquid-main-split.js` includes `pillLongBackground:"#0E3333"` and `pillShortBackground:"#34242E"`; pill component at module region near line ~7486 selects colors by side.

## Decision Log

- Decision: Implement parity by extending existing Hyperopen positions table and shared account-table primitives rather than introducing a new table framework.
  Rationale: Hyperopen already has deterministic sorting/cache behavior and tests around current table modules; extending in place minimizes regression surface.
  Date/Author: 2026-02-24 / Codex

- Decision: Treat this scope as aesthetic parity only for the Positions tab on the trade page, not a full account-table redesign.
  Rationale: User request targets styling/layout mismatches (direction colors, PNL line density, tooltips, cell formatting), and this keeps scope aligned and shippable.
  Date/Author: 2026-02-24 / Codex

- Decision: Reuse existing tooltip composition patterns already present in balances view instead of adding a new tooltip subsystem.
  Rationale: `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` already demonstrates the repo's accepted tooltip/accessibility style for account tables.
  Date/Author: 2026-02-24 / Codex

## Outcomes & Retrospective

This plan is currently in authored state. No code has been changed yet under this plan. Retrospective updates will be recorded after implementation and validation gates.

## Context and Orientation

Hyperopen Positions rendering currently lives in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`. Shared style constants and formatting helpers used by Positions live in `/hyperopen/src/hyperopen/views/account_info/shared.cljs`. Header button rendering and tabular wrappers live in `/hyperopen/src/hyperopen/views/account_info/table.cljs`. Current regression tests for Positions are in `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`.

Hyperliquid reference behavior was reconstructed from:

- Live browser capture artifacts in `/hyperopen/tmp/browser-inspection/inspect-2026-02-24T15-15-36-731Z-bd34d304/`
- Frontend bundle in `/hyperopen/tmp/hyperliquid-main-split.js`

The key term `colorType` in this plan means Hyperliquid's row polarity marker (`buy` or `sell`) that drives first-cell gradient and side-colored text treatment.

The key term `explanation header` means a column header that carries a tooltip body and is rendered with dashed underline (Hyperliquid behavior for at least `PNL`, `Margin`, and `Funding`).

### Current Hyperopen vs Hyperliquid Delta Inventory

1. Coin first-cell polarity styling:
   Hyperliquid: long uses green gradient and long-tinted text/pill; short uses red gradient and red-tinted text/pill.
   Hyperopen: always uses one green gradient (`position-coin-cell-style`) and one green chip class (`position-chip-classes`) regardless of side.

2. Size sign and color:
   Hyperliquid: size text uses absolute quantity and side color.
   Hyperopen: `format-position-size` emits raw `:szi`, so shorts display a negative sign and no side color treatment.

3. PNL density and composition:
   Hyperliquid: one line (`-$X (Y%)`) in one cell block (optional icon to the right).
   Hyperopen: two-line block with value on first line and percent in a secondary `text-xs` block below.

4. Header tooltip affordances:
   Hyperliquid: `PNL`, `Margin`, and `Funding` headers are tooltip-backed and visually underlined (dashed).
   Hyperopen: sortable headers are plain text with no tooltip/underline semantics.

5. Position Value unit format:
   Hyperliquid: value followed by quote unit (`20.04 USDC`), no dollar prefix.
   Hyperopen: prefixed with `$` and no quote unit suffix.

6. TP/SL action styling:
   Hyperliquid: `-- / --` plus edit affordance icon (pencil) in cell.
   Hyperopen: plain ghost button text without icon.

7. Funding/Liq tooltip affordance:
   Hyperliquid: funding value is tooltip-backed; liq price can be dashed-underlined with tooltip in portfolio-margin contexts.
   Hyperopen: static value text; no tooltip semantics.

8. Row density and nowrap posture:
   Hyperliquid: dense single-line row rhythm (small line-height, nowrap values).
   Hyperopen: custom grid currently allows/encourages multi-line PNL and mixed vertical density.

## Plan of Work

### Milestone 1: Introduce Side-Aware Positions Presentation Model

Edit `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` and `/hyperopen/src/hyperopen/views/account_info/shared.cljs` to derive explicit position side (`:long`/`:short`) from signed `:szi` and expose helper selectors for:

- side-aware coin cell style map (long vs short gradient and text color)
- side-aware chip classes (long and short variants)
- side-aware size display text with absolute numeric quantity
- side-aware size class for color

Keep sorting semantics unchanged: sort accessors should continue using numeric signed values where needed.

Milestone acceptance: a short fixture row renders red-stripe coin styling and red side chip; size string has no minus sign.

### Milestone 2: Collapse PNL to One-Line Hyperliquid Shape

In `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`, replace the two-line nested PNL structure with one inline expression formatted as:

- value part with explicit sign and dollar formatting
- percentage part in parentheses on the same line
- one text node/class path for polarity color

Preserve placeholder behavior for invalid values (`--`) and keep deterministic formatting in tests.

Milestone acceptance: rendered PNL cell contains a single-line value+percent string and no secondary stacked percentage node.

### Milestone 3: Add Header Tooltip/Affordance Support

Extend `/hyperopen/src/hyperopen/views/account_info/table.cljs` to support optional `:explanation` metadata on sortable/non-sortable headers and render:

- dashed underline affordance on headers with explanation
- tooltip container/contents (consistent with existing account-table tooltip style patterns)

Then wire `position-table-header` in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` so:

- `PNL (ROE %)` has explanation text
- `Margin` has explanation text
- `Funding` has explanation text

Milestone acceptance: those three headers visibly expose dashed-underline cue and hover/focus tooltip content.

### Milestone 4: Align Remaining Cell-Level Aesthetic Gaps

Update Positions row rendering in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` to match Hyperliquid formatting conventions:

- Position Value uses `<amount> USDC` instead of `$<amount>`
- TP/SL cell includes edit affordance icon with existing placeholder text
- Funding cell gets tooltip wrapper where applicable
- Liq. Price supports dashed-underline tooltip affordance for context-sensitive explanation hooks (at minimum keep structure ready even if data context is absent)

Avoid introducing side effects in rendering logic; keep this as pure view transformation.

Milestone acceptance: row-level screenshot parity checklist passes for short-row styling, position value unit format, and TP/SL icon affordance.

### Milestone 5: Regression Tests and Validation Gates

Update tests in `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs` (and new test files if needed) to cover:

- short position coin gradient + short chip colors
- size formatting strips sign for short while retaining sort behavior
- PNL single-line rendering
- header tooltip affordance classes/structure for `PNL`, `Margin`, `Funding`
- TP/SL icon node presence

Run required gates:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Milestone acceptance: all required gates pass and new tests guard parity behaviors.

## Concrete Steps

1. Implement side-aware helper functions and coin/size rendering changes.

   cd /Users//projects/hyperopen
   npm test -- positions

   Expected result: positions tests for side-aware gradients and size sign behavior pass.

2. Implement PNL single-line rendering and header tooltip metadata/rendering.

   cd /Users//projects/hyperopen
   npm test -- positions

   Expected result: no two-line PNL remnants; tooltip-related tests pass.

3. Implement remaining formatting adjustments (Position Value unit, TP/SL icon, funding/liq tooltip wrappers).

   cd /Users//projects/hyperopen
   npm test -- positions

   Expected result: row formatting tests and snapshot/string assertions pass.

4. Run full required repository gates.

   cd /Users//projects/hyperopen
   npm run check
   npm test
   npm run test:websocket

   Expected result: all commands exit 0.

## Validation and Acceptance

The change is accepted when all of the following are true:

1. Short position coin cell renders short-specific red gradient/text/chip styling; long remains green.
2. Size column renders absolute size text (no negative sign), with side color conveying direction.
3. PNL cell renders one-line value plus ROE percentage in parentheses.
4. `PNL (ROE %)`, `Margin`, and `Funding` headers have tooltip affordance (dashed underline + tooltip body).
5. Position Value formatting uses quote-unit suffix (`USDC`) instead of dollar-prefix format.
6. TP/SL cell includes placeholder text and edit affordance icon.
7. Existing sorting, memoization, and tab rendering behavior remains deterministic.
8. `npm run check`, `npm test`, and `npm run test:websocket` all pass.

## Idempotence and Recovery

All edits are source-level and additive/refinement changes; rerunning steps is safe.

If tooltip integration causes regressions, temporarily keep header metadata but fall back to non-tooltip header rendering in `/hyperopen/src/hyperopen/views/account_info/table.cljs` while preserving row-format parity updates.

If side-aware rendering introduces unexpected color regressions, keep side derivation logic but temporarily route chip/gradient classes through existing constants in `/hyperopen/src/hyperopen/views/account_info/shared.cljs` until class maps are corrected.

## Artifacts and Notes

Hyperliquid bundle evidence snippets used for this plan:

- `/hyperopen/tmp/hyperliquid-main-split.js` positions columns include `explanation` for `pnl`, `margin`, `funding`.
- `/hyperopen/tmp/hyperliquid-main-split.js` positions row sets `colorType` to `buy`/`sell` and `K=Math.abs(N)` for size.
- `/hyperopen/tmp/hyperliquid-main-split.js` table renderer applies first-cell gradients:

  buy: `linear-gradient(90deg, positive 0, positive 4px, rgba(11,50,38,1) 4px, transparent 100%)`
  sell: `linear-gradient(90deg, negative 0, negative 4px, rgba(52,36,46,1) 0%, transparent 100%)`

- `/hyperopen/tmp/hyperliquid-main-split.js` header renderer underlines explained columns and wraps them with tooltip content.

Live-capture artifact root for this audit:

- `/hyperopen/tmp/browser-inspection/inspect-2026-02-24T15-15-36-731Z-bd34d304/`

## Interfaces and Dependencies

No new library dependency is required.

Target interfaces to exist after implementation:

- Positions row rendering function in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` emits side-aware style/class selection based on signed size.
- Shared style helpers in `/hyperopen/src/hyperopen/views/account_info/shared.cljs` expose separate long/short coin-cell and chip styles.
- Header rendering helpers in `/hyperopen/src/hyperopen/views/account_info/table.cljs` support optional explanation tooltips for sortable headers.
- Positions tests in `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs` assert short/long parity visuals and one-line PNL structure.

Plan revision note: 2026-02-24 15:18Z - Initial plan authored after live Hyperliquid bundle inspection and local Positions audit.
