# Vol Range local real-money test runbook

## Scope

How to run a first, small, real-money test of Vol Range placement (HO-SPEC-014 Release A) from a local
development build, and what to collect afterwards. Placement exists only in the dev build and stays off
until you arm it in your own browser. Release builds never include it.

## Before you start

- Use a wallet or subaccount holding only what you are willing to lose in a test: a small amount of USDC
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

## Arm placement (this browser only)

In the browser's DevTools console on http://localhost:8080:

```js
localStorage.setItem('hyperopen.volRange.execution', 'armed');
// Optional: per-ladder cap in USDC (default 150).
localStorage.setItem('hyperopen.volRange.maxLadderUsdc', '60');
```

Reload the page. The cap is the sum of price × size over every order in one ladder; a ladder above it
cannot be placed.

To disarm at any time (cancelling keeps working while disarmed):

```js
localStorage.removeItem('hyperopen.volRange.execution');
```

## Test steps

1. Connect your wallet and press Enable Trading (the agent must be ready).
2. Select the spot market. In the order form choose Scale, then Volatility range.
3. Keep the ladder small: few rungs per side, small quantities. Each side's size can be typed as a base
   quantity or, with the unit switch above the field, as a USDC amount: buys then spend at most that amount
   (fee reserve included, and it is the debit cap), sells place at most that much order value. The line
   under the field shows the base quantity it works out to. Inventory limits have the same switch ("Limits
   in"); USDC limits are converted at the anchor price, shown under the fields. Check the fee reserve, protected core and
   maximum. Fix anything listed under "issues to fix before review".
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
- Fills are picked up by the 15-second background check, not instantly.
- If your order book uses price grouping, the review uses the grouped best bid/ask but the pre-send check
  uses the exact book, so a review may be refused as changed; review again.
- An order whose outcome was lost and that the exchange reports as unknown stays "Checking", and keeps
  that market blocked for new ladders, because "not found" is never treated as proof that an order was
  not placed. Report it with the journal rows; clearing it safely needs a rule proven against the
  exchange, which is still open work.
- One ladder per market at a time.
