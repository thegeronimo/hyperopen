# Order book: click a level to set the price, and mark your own resting orders

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

Context: direct maintainer request (2026-10-16), items 1 and 2 of the "Order Book Redesign" design canvas. This follows `2026-10-16-orderbook-change-flash-and-depth-bars.md`.

## Purpose / Big Picture

Hyperliquid and trade.xyz both let a trader click a book level to load its price into the order ticket. Our levels were inert divs. Traders also could not see where their own resting orders sat in the book without switching to the Open Orders tab. The product heuristics ask for parity with Hyperliquid workflows and for runtime truth to stay visible near order entry.

After this change:

- Every level is a real button. A click, tap, or Enter sets the limit price to that level's exact price. A Market ticket switches to Limit so the price applies; Limit and Pro tickets keep their type.
- Any level holding the user's resting order shows a small amber pip, a hover title, and an aria-label giving the size, for example "Your resting order here: 0.25 BTC".

## Progress

- [x] (2026-10-16) Added the `hyperopen.trading.orderbook-price-pick` transition and the `:actions/set-order-price-from-orderbook` action. The action is wired through the order collaborators, trade runtime registration, and action-args contract. The `action_args.cljs` size cap moved from 744 to 745.
- [x] (2026-10-16) Desktop rows and both halves of the mobile split row are buttons with aria-labels and a focus-visible ring.
- [x] (2026-10-16) Added `hyperopen.views.l2-orderbook.own-orders`. It reads the active asset's open orders from the same sources as the Open Orders tab and the chart's order lines, and maps them onto displayed levels. `trade_view` passes them in through `orderbook-view-state`.
- [x] (2026-10-16) Tests in `orderbook_price_pick_test` (8) and `own_orders_test` (9).
- [x] (2026-10-16) Headless Playwright QA against the worktree build, BTC at 1440x900 and 375x812.
- [x] (2026-10-16) `npm run gates`.

## Surprises & Discoveries

- The typed-price path (`update-order-form [:price]`) parses input in the user's locale. In de-DE it would turn a book price of `1.234` into `1234`. The book's raw price is already canonical, so it is written to the form directly, the same way `set-order-price-to-mid` does it. There is a test for this.
- Hyperliquid sends prices such as `82342.0`. The first QA run put `82342.0` into the ticket, so trailing fraction zeros are now dropped.
- The first QA run also exposed a misplaced pip. When an order's exact level was briefly missing from a snapshot, the bucketing rule moved the pip to the next level down. Bucketing now applies only to aggregated precisions; at full precision only an exact price match counts.
- Injecting open orders with no effective account trips `account lifecycle invariant failed` (expected behaviour). QA spectates a public address first.

## Decision Log

- Decision: the click switches Market to Limit but leaves Pro order types alone. Rationale: a price is meaningless on a market order, while Pro types such as stop-limit use the price as their limit.
- Decision: the new transition lives in its own namespace instead of `order_form_transitions.cljs`. Rationale: that file is at 740 of its 742-line cap. `backfill-tpsl-triggers-from-offset-inputs` was made public so the new namespace can reuse it, with no change in line count.
- Decision: pips render in the row's left padding (`left-[2px]`) rather than in a new gutter column, so column alignment is unchanged.
- Decision: the action takes the raw price string and rejects anything that is not a positive plain decimal; the action then returns `[]`.

## Outcomes & Retrospective

QA evidence (headless Chromium, worktree build):

- Five consecutive clicks on a live ask level each set exactly the price in that row's aria-label. The label was read and the button clicked in the same synchronous step.
- A Market ticket became Limit at the clicked price. The visible price input showed `82215`, with no trailing `.0`.
- Pressing Enter on a focused bid row set `82213`.
- On mobile (375px), tapping an ask half set `82156` and switched to Limit.
- An injected resting bid of 0.25 BTC at 82,185 showed exactly one pip, on 82,185, titled "Your resting order here: 0.25 BTC".
- No page errors.

Remaining risks:

- The pip and size reflect the open-orders feed. A fill or cancel shows up when that feed updates.
- The click handler sends the price the row displayed when it was last rendered. On a fast book that can differ from where the cursor was a moment earlier, which is inherent to clicking a live ladder.
