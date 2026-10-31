# Vol Range local real-money test runbook

## Scope

How to run a first, small, real-money test of Vol Range placement (HO-SPEC-014 Release A) from a local
development build, and what to collect afterwards. Placement is on by default in dev and release builds,
with no per-ladder cap.

## Before you start

- With no cap, a ladder can use everything the account holds. Use a wallet or subaccount holding only
  what you are willing to lose in a test: a small amount of USDC
  and, if you want sell orders, a small amount of one spot token.
- Pick a liquid USDC spot market. The app supports spot markets quoted in USDC only.
- The account's raw mode must be `default`, `disabled` or `dexAbstraction` (the review tells you if not).
- Every order needs at least 10.50 USDC of notional. A 2-buy + 2-sell ladder therefore needs about
  45 USDC of order value.

## Start the app from this checkout

```bash
cd /Users/barry/projects/hyperopen/.claude/worktrees/vol-range-a0
npm run dev
```

It serves http://localhost:8080. Stop any other HyperOpen dev server first: only one can hold port 8080.

## Optional: turn placement off or set a cap (this browser only)

Placement needs no setup in the dev build. To turn it off, or to cap each ladder's total order value
(the sum of price × size over every order), run in the DevTools console on http://localhost:8080 and
reload:

```js
localStorage.setItem('hyperopen.volRange.execution', 'off');      // placement off
localStorage.setItem('hyperopen.volRange.maxLadderUsdc', '60');   // cap each ladder at 60 USDC
```

Remove either key to go back to the default (placement on, no cap). Cancelling always works, whatever
these are set to.

## Test steps

1. Connect your wallet and press Enable Trading (the agent must be ready).
2. Select the spot market. In the order form choose Scale, then Volatility range.
3. Keep the ladder small: few rungs per side, small quantities. Each side's size can be typed as a base
   quantity or, with the unit switch above the field, as a USDC amount: buys then spend at most that amount
   (fee reserve included, and it is the debit cap), sells place at most that much order value. The line
   under the field shows the base quantity it works out to. Inventory limits are folded into one line under
   Inventory; by default sells may use all holdings and there is no holding limit. "Edit limits" opens Keep
   at least and Hold at most (and Lean toward when tilt is on), with the same unit switch ("Limits in");
   USDC limits are converted at the anchor price, shown under the fields. Check the fee reserve and the
   limits line. Fix anything listed under "issues to fix before review".
4. Press Review N orders. Check the prices, sizes and costs. Press Place N orders.
   - Pressing Enter never places orders; only the button does.
   - Before sending, the app re-reads your balances, open orders, the order book and your fee rate, and
     refuses if anything changed since review. If the market moved, review again.
5. Open the Vol ladders tab in the bottom panel.
   - Expect "Live" with the number of live orders, or "Partially deployed" with a sentence saying how many
     orders were not placed and why ("View orders" shows each order and its reason).
   - Compare with the Open Orders tab: the same prices and sizes, all post-only.
   - The ladder refreshes itself every 15 seconds while the page is visible; "Refresh status" does it now.
6. Optional: let an order fill, or watch one fill, and check the filled quantity and fill price under
   "Execution detail (not P&L)".
7. Press Cancel this ladder. Expect "Canceling", then "Closed" once every order is confirmed canceled or
   filled. Check that Open Orders no longer lists them.
8. Optional: reload the page while a ladder is live. The Vol ladders tab should show it again.
9. Optional: repair a partially deployed ladder (see "Repair rejected orders") or update a live one (see
   "Update a ladder").

## Repair rejected orders

When some orders of a ladder were rejected (usually a post-only order that would have crossed the book),
the ladder shows "Partially deployed" and a **Repair N rejected orders** button. The button shows only
while placement is on and the ladder is not being canceled or updated; with the ticket on another market
or account it is disabled with "Open VRT/USDC to repair or update this ladder." (your market's name).

1. Press Repair N rejected orders. The "Review rejected orders" dialog reads the current book and, for
   each rejected order, proposes a post-only price at the same size: the original price if it no longer
   crosses, otherwise one moved away from the book in the ladder's own volatility steps (never to the
   best bid or ask). An order with no safe price is listed as not repairable, with the reason.
2. Check the new prices, the notional and the bounds panel ("If all buys fill first", "Hold at most",
   ...). Untick any order you don't want to send. Refresh prices re-reads the book; unticked orders stay
   unticked.
3. Press Place N repair orders. Only the ticked orders are sent, as one new action with new order ids.
   The surviving orders are never sent again, and the repaired orders join the same ladder. Expect
   "Repair sent. 7 of 7 orders active." (your counts) and "Live". Open Orders should show the repaired
   order once, at the reviewed price.
   - If the market moved since the review, the dialog says so and nothing is sent: press Refresh prices
     and review again.
   - Right after a partial placement, Place N repair orders may be disabled with "Funds held for an
     earlier placement are still being confirmed. Refresh prices in a few seconds." Wait, or press
     Refresh status on the ladder, then Refresh prices.

## Update a ladder

Updating replaces a ladder in separate, explicit steps. Nothing new is placed until you press Place in
the ordinary review.

1. Press **Preview update** on a Live or Partially deployed ladder (same market and account rules as
   repair). The ticket switches to Scale, Volatility range, with the ladder's deployed settings and its
   unfilled remainder as the quantities, and the "Update ladder" dialog compares the current ladder with
   the proposal. "Edit in ticket" closes the dialog so you can change the ticket; "Open update" in the
   ticket's banner brings it back. Your ladder is not touched yet.
2. Press **Cancel current ladder to update**. Every open order of the old ladder is canceled once. The
   app then checks every old order, including fills that land while the cancel is pending, and re-reads
   your balances.
   - If an old order's outcome is unknown or it is still open, the dialog says so and offers Check again
     (and Cancel remaining orders when some are still open). The replacement can't be reviewed until it
     clears.
3. Expect exactly "Old ladder canceled. Review the updated quantities before placing the replacement.",
   then the unfilled remainder (for example "buys 40 VRT, sells 27 VRT") and anything "Filled while
   canceling". Filled quantity is never added back. Orders that were never placed (rejected) are listed
   separately and are included only if you tick them.
4. Press **Review replacement**. The ticket's quantities become the remainder (plus any ticked rejected
   orders). Change the ticket if you want; press Refresh balances in the banner if time has passed. Then
   press Review N orders: the review says which ladder it replaces and whether you changed the
   quantities.
5. Press Place N orders. The new ladder shows "Replaces the ladder created …" and the old, closed one
   "Replaced by the ladder created …". Open Orders should list only the new orders.

Leaving places nothing. Stop updating (in the dialog or the ticket banner) ends the update at any step:
before the cancel your ladder is unchanged; after it the old ladder stays canceled and no new orders are
placed. Reloading the page, or switching market or account, also abandons the update.

## Live updates

The Vol ladders tab learns about fills and order changes from the account's WebSocket streams as well
as from the 15-second check. One line at the top of the tab says how live that is:

- "Live updates on": fills and order changes arrive within about a second. Placement is allowed.
- "Live updates paused while reconnecting. Your orders stay on the exchange.": the connection dropped.
  The app reconnects by itself.
- "Live updates paused while another account is shown.": the streams follow a spectated or trader
  account, not the account that owns the ladders.
- "Connecting live updates…": the streams are not answering yet (for example right after loading).
- "Catching up on fills and orders…": the streams are back and the app is re-reading every open ladder
  (journal, open orders, balances, order statuses and the fills it may have missed).
- "Couldn't catch up yet. Retrying automatically.": a catch-up read was incomplete; it retries after
  15, 30, 60 then every 120 seconds.

Until the line says "Live updates on", the review's Place button and the repair dialog's submit are
disabled with "Placement waits for live order and fill updates to catch up.", and a placement already
under way is stopped before it is sent. Cancel this ladder and Refresh status always work.

To watch a fill arrive:

1. Place a small ladder whose nearest buy (or sell) is close to the book, and keep the Vol ladders tab
   open with the ladder's "Execution detail (not P&L)" visible.
2. When the order fills on the exchange (Open Orders or Trade History shows it), the filled quantity,
   VWAP and fees should update within about a second, without pressing Refresh status.
3. Press Refresh status afterwards: the filled quantity must not change (the same fill is never counted
   twice).
4. Optional: turn the network off for a few seconds (DevTools → Network → Offline), then back on. The line
   should go to "paused", then "Catching up…", then "Live updates on", and a fill made while offline should
   then show once.
5. Optional: open Portfolio (your own account) while an order fills, then come back to Trade: the fill
   should already be shown.

### Save WebSocket frames as protocol fixtures

The app's parsing of stream frames is written from the documented shapes and has open questions (below).
Real frames answer them. In DevTools → Network → WS, select the `wss://api.hyperliquid.xyz/ws`
connection, open Messages and filter by `userFills` or `openOrders`. For the subscription snapshot, one
increment with a fill of a ladder order, and one `openOrders` frame while the ladder rests, right-click
the message → Copy message. Paste each into a file and replace your address everywhere with
`0x1234567890abcdef1234567890abcdef12345678` (also replace any `hash` values if you prefer). Send the
files back; they become test fixtures in `test/hyperopen/vol_range/live_fixtures.cljs`. Frames hold
order ids, prices, sizes and fees; they never hold keys or signatures.

### Open protocol questions to answer from the frames

- Does a `userFills` frame carry `data.user`? (If not, the app falls back to the subscribed address.)
- Do fill rows carry `cloid` for orders placed with one? (Without it, an order whose placement outcome
  was lost can only be resolved by REST.)
- Does the default-dex `openOrders` frame include spot orders, with `cloid`, `origSz` and `oid`?
- Are `time` and `tid` numbers in both the WebSocket rows and the REST `userFillsByTime` rows?
- Does a reconnect replay a `userFills` snapshot with `isSnapshot: true`?

## Vol Range report (Portfolio)

The app also records what your ladders did, so you can compare running them with simply holding your
tokens. Reporting is on by default in dev and release builds; the protocol questions below are still
open.

- Tracking starts by itself at your first placement in a market: the app stores your total base balance
  (held units included) and the book midpoint at that moment, before anything is sent. The Vol ladders
  row then reads "Tracking since …". Ladders placed before this build, or from another browser, show
  "Not tracked".
- Open Portfolio → Vol Range, or press View report on a ladder row. Expect "Tracking began … with … at
  … (book midpoint).", "Cost basis unavailable", a coverage chip ("Complete", "Checking", "Gap",
  "Outside activity" or "Unavailable") and the Execution activity table (fills, volumes, VWAPs,
  maker/taker, fees by token).
- Dollar figures appear after the first completed hourly close: Net tracked asset P&L, Hold P&L and
  Ladder contribution versus hold, valued at hourly closes (Since tracking began starts from the
  inception midpoint). 7D and 30D stay unavailable until tracking is that old. Below them are the
  cumulative P&L chart and, collapsed, Gross spread on matched fills.
- Any trade of the same token that is not a Vol Range order (a manual order, another pair containing the
  token, a transfer in or out) hides the dollar figures from that moment; activity stays shown. Start new
  tracking from now begins a fresh baseline; no orders are changed.
- Fills are confirmed by the account's REST history. Refresh reads it now; otherwise it is read on
  opening the tab and every 15 minutes while a ladder is tracked. "Checking" for a minute after a fill
  is expected.

To check it with real fills: place a small ladder, let a rung fill, wait for the next full hour plus a
minute, open the report and compare the activity with Trade History (same fill count, sizes, prices and
fees by token). Do not trade the same token by hand during the test unless you want to see the figures
hidden.

### Export the report

Export report (JSON) downloads `hyperopen-vol-range-report-<account>-<time>.json`: every tracking period
with its baseline, coverage, breaks and window figures, the order mappings (client order id and order id
to ladder, operation and leg), every stored fill with its attribution and signed fee, ledger updates,
coverage records and the hourly closes used, with every model version. Export fills (CSV) downloads
`hyperopen-vol-range-fills-<account>-<time>.csv` with one row per fill for a spreadsheet. Both are built
from this browser's stored report only (nothing is read from or sent to the exchange) and hold your
address, order ids, prices, sizes and fees; they never hold keys or signatures. Send both back with any
report screenshot that looked wrong.

### Save ledger responses as protocol fixtures

Reporting assumes `userNonFundingLedgerUpdates` returns at most 2,000 rows per page and does not know how
far back it goes. While the Vol Range tab is open, in DevTools → Network filter by `info`, find the POST
requests whose payload has `"type":"userNonFundingLedgerUpdates"` and `"type":"userFillsByTime"`, and save
each response (right-click → Copy response) after replacing your address with
`0x1234567890abcdef1234567890abcdef12345678`. Note for each: the number of rows, the oldest row's `time`,
and whether your account has older activity than that. These answer whether the page size is 2,000 and
how long the ledger is retained.

## If something looks wrong

- "Checking status" means an outcome is not proven yet (for example a lost response). The app never
  re-sends a ladder; it checks the original orders. Press Refresh status. If it stays Checking, cancel
  the ladder and check Open Orders.
- While a ladder is not closed, a second ladder in the same market is blocked by design.
- If the page or browser crashes mid-send, reopen it: the ladder is restored from browser storage and
  checked against the exchange.
- You can always cancel the orders from the ordinary Open Orders tab as well.

## What to send back

- Screenshots of anything that looked wrong or confusing, with the time it happened.
- The exact status text and any message shown in the Vol ladders tab or the Vol Range report.
- Both report exports (see "Export the report") if a report figure or coverage state looked wrong.
- The exchange replies, if anything was unexpected. They are stored only in this browser, in DevTools →
  Application → IndexedDB → `hyperopen-persistence` → `vol-range-journal` (send-attempt and cancel rows
  carry a `:raw-response` field). To copy every journal row to the clipboard, run in the console:

```js
const db = await new Promise((ok, err) => { const r = indexedDB.open('hyperopen-persistence'); r.onsuccess = () => ok(r.result); r.onerror = () => err(r.error); });
const rows = await new Promise((ok, err) => { const q = db.transaction('vol-range-journal').objectStore('vol-range-journal').getAll(); q.onsuccess = () => ok(q.result); q.onerror = () => err(q.error); });
copy(JSON.stringify(rows, null, 2));
```

The journal holds order identities, prices, sizes and exchange replies; it never holds keys or signatures.

## Known limits of this build

- Ladders placed by a build from before repair and update recorded no settings. For those, Preview update
  starts from the ticket's current settings (the dialog says so) and a repair checks balances but not
  the ladder's inventory limits (the repair dialog says so).
- An update in progress is kept in memory only: a reload ends it (nothing is placed).
- Recovered ladders (found on the exchange without a journal record) can only be canceled, not repaired
  or updated.
- Fills arrive live only while the line says "Live updates on"; otherwise the 15-second background
  check and the catch-up after a reconnect pick them up.
- If your order book uses price grouping, the review uses the grouped best bid/ask but the pre-send check
  uses the exact book, so a review may be refused as changed; review again.
- An order whose outcome was lost and that the exchange reports as unknown stays "Checking", and keeps
  that market blocked for new ladders, because "not found" is never treated as proof that an order was
  not placed. Report it with the journal rows; clearing it safely needs a rule proven against the
  exchange, which is still open work.
- One ladder per market at a time.
- Tracking lives in this browser. A ladder placed from another device or browser appears as recovered
  orders here and hides the report's dollar figures for that market from its first fill.
- The report has no cost basis, percentage return or account value; it compares the tracked inventory
  with holding it.
