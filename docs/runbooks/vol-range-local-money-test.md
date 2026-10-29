# Vol Range local real-money test runbook

## Scope

How to run a first, small, real-money test of Vol Range placement (HO-SPEC-014 Release A) from a local
development build, and what to collect afterwards. Placement exists only in the dev build, where it is on
by default with no per-ladder cap. Release builds never include it.

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
- The exact status text and any message shown in the Vol ladders tab.
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

- Rejected orders are shown with their reason, but there is no "Review rejected orders" repair screen
  yet, and no "Preview update" (replace) flow. Cancel and place a new ladder instead.
- Fills arrive live only while the line says "Live updates on"; otherwise the 15-second background
  check and the catch-up after a reconnect pick them up.
- If your order book uses price grouping, the review uses the grouped best bid/ask but the pre-send check
  uses the exact book, so a review may be refused as changed; review again.
- An order whose outcome was lost and that the exchange reports as unknown stays "Checking", and keeps
  that market blocked for new ladders, because "not found" is never treated as proof that an order was
  not placed. Report it with the journal rows; clearing it safely needs a rule proven against the
  exchange, which is still open work.
- One ladder per market at a time.
