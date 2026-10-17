# Order book depth readout and order-form book impact

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

Context: direct maintainer request (2026-10-16) to build the two decision-aid cards from the "Order Book Redesign" design canvas (artboards "hover depth readout" and "order impact preview"). This follows `2026-10-16-orderbook-click-to-price-and-own-orders.md`.

## Purpose / Big Picture

The product heuristics ask us to prevent errors and to keep risk context visible near order submission. Before this change, the only cue a trader got about how far a market order would move through the book was one "Slippage Est" percentage in the order form footer.

After this change:

- **Depth readout card.** Hovering a level, or focusing it with the keyboard, shows a card beside the book. It describes a market order swept up to that level: levels swept, total size, notional, average fill price, and slippage against mid. The rows covered by that sweep are tinted.
- **Book impact card.** For a sized Market order, a card above Place Order shows levels swept (and how much of the last one), worst price, average fill price, and the share of the visible side used. It warns when the visible book cannot fill the whole order. The same levels are marked on the ladder with an accent rail and a band sized to the share taken.

## Progress

- [x] (2026-10-16) Added `hyperopen.domain.trading.book-sweep`, with `market-impact` and `sweep-to-price`. It uses the same levels, best-first ordering and top-of-book midpoint as the footer's estimate. A test asserts the two agree.
- [x] (2026-10-16) Added `:actions/set-orderbook-hover-level` and `:actions/clear-orderbook-hover-level`. They write the transient `[:orderbook-ui :hover-level]` and do nothing when the value is unchanged. The `action_args.cljs` size cap moved from 745 to 747.
- [x] (2026-10-16) Added `hyperopen.views.l2-orderbook.readout` (the card) and `hyperopen.views.trade.book-impact` (a memoized impact keyed on the stored form and book, plus the card). `order-row` now takes an options map. The panel's ninth argument is an `extras` map with `:open-orders` and `:impact`.
- [x] (2026-10-16) Tests: `book_sweep_test` (7) and `depth_readout_test` (8). `npm test` reported 7048 tests and 0 failures.
- [x] (2026-10-16) `npm run gates`: 34/34 PASS.
- [x] (2026-10-16) Headless Playwright QA against the worktree build, BTC at 1440x900.
- [x] (2026-10-16) Maintainer feedback fix:
  - The hover is now keyed by row position (side and depth from the spread) instead of price.
  - The card is restyled to the mockup.
  - The tint and outline moved onto their own layers.
  - Re-ran the gates (34/34) and a steady-cursor QA.

## Surprises & Discoveries

- The book panel and its panes use `overflow: hidden`, which would clip a tooltip placed beside a row. The card instead uses `position: fixed` with no offsets, so it sits at its static position in the flex row, vertically centred, and escapes the clipping. A transform then moves it to the row's left. QA measured it 10px left of the row, centred to the pixel, fully outside the book, with no bounds measurement or extra state.
- Replicant does not re-render on `mousemove`. Hover therefore dispatches only on `mouseenter` of each row, and the depth body's `mouseleave` clears it, so a pass across the book costs one write per row.

- Maintainer feedback after the first build: the card moved or vanished while the cursor sat still and the book ticked. The hover state was keyed by price. Rows are reused by position, and `mouseenter` does not fire again under a still cursor, so the card followed the old price or vanished when that price left the book. The hover is now `{:side :depth}`, measured from the spread. The row under the cursor keeps the card, and its figures follow whatever price that row now shows. Steady-cursor QA held the cursor still for 8 seconds over 156 samples: the card was never missing and its position never changed, while the row's price changed 4 times and its figures 16 times.
- The change-flash animates the row's own `background-color` and `box-shadow`. That wiped the readout tint and hovered-row outline whenever the row flashed. Both now render as their own overlay spans, and QA saw the outline in every sample.
- The first card used `bg-base-200`, which is darker than the mockup's grey. It now uses `--ho-surface-raised` (rgb 39 48 53, the mockup's #273035), a `--ho-border` border, a 6px radius, 268px width, 12px/14px padding, monospace values, amber slippage, a rule above the footer, and an arrow pointing at the row.

## Decision Log

- Decision: the book impact card does not repeat the slippage percentage. Rationale: the footer already shows "Slippage Est / Max" from the same simulation. Showing it twice in different units invites a "which is right?" moment. The card adds what the footer lacks: levels, worst price, average price, and depth used.
- Decision: the mockup's "Shift-click to set size" hint is left out of the card. Rationale: shift-click is not implemented, and a hint for a gesture that does nothing would be false.
- Decision: the hover readout is desktop only. Rationale: touch has no hover, and on mobile a tap already sets the price.
- Decision: impact is computed from the stored `:order-form` (`:type`, `:side`, and the synced base `:size`) rather than `order-form-draft`. Rationale: this keeps the memo key cheap on every trade-view render. The values are keywordized because the form can be restored from storage as strings.

## Outcomes & Retrospective

QA evidence (headless Chromium, worktree build, BTC):

- Hovering the fourth-best ask showed "Market buy up to 81,596 | Levels swept 4 | Total size 1.39904 BTC | Notional $114,152 | Avg fill price 81,593.2 | Slippage vs mid 0.08 bps". Four ask rows were tinted.
- The card disappeared when the mouse left the book. Keyboard focus on a bid showed "Market sell down to 81,590", and blur cleared it. No page errors.
- A Market buy of 2,000,000 USDC (24.5119 BTC) showed "Levels swept 14 (last 40% filled) | Worst price 81,607 | Avg fill price 81,600.4 | Visible asks used 45%". The ladder had 14 rails, and the partial level's band was `scaleX(0.397)`. The footer still showed its estimate from the same sweep.

Remaining risks:

- The visible book is about 20 levels per side. Larger orders get the "fills only X of Y" warning rather than a deeper estimate.
- The hover card's fixed positioning depends on no ancestor creating a containing block (transform, filter, or contain). QA confirmed the current trade layout has none. A future layout wrapper with a transform would pin the card to that wrapper instead.
