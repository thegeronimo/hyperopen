# Earn: realized returns ladder

## Purpose / Big Picture

The Earn page shows the live supply APY, a rate that moves every hour. Users
also want to know what they actually earned. This plan adds a "Realized
returns" card under Your positions (design direction C from the 2026-11-03
design canvas). It has one row per period (7 days, 1 month, 3 months, 6 months,
since first supply) for each supplied, interest-earning token. Each row shows
realized APY, a bar compared with today's APY, interest received, average
balance and days supplied.

Realized APY = interest received ÷ time-weighted average balance, annualized
(simple, ×8760 hours). This makes it the balance-weighted average of the
indicated rate, so it does not get a separate "average indicated APY" column.

Context: a direct user request in the Claude Code session on 2026-11-03
("I like option C. I'd actually like you to implement that.").

## Context and Orientation

- `src/hyperopen/borrow_lend/normalize.cljs`: wire → maps for borrow/lend info.
- `src/hyperopen/borrow_lend/effects.cljs`: `api-fetch-borrow-lend-interest!`
  loaded a 30-day window only.
- `src/hyperopen/api/projections/borrow_lend.cljs`: stores `[:borrow-lend :interest]`.
- `src/hyperopen/views/earn/vm.cljs`, `src/hyperopen/views/earn_view.cljs`: Earn page.

Live payloads, captured on mainnet 2026-11-03:

- `userBorrowLendInterest` from `startTime 0` returns the full history.
  Rows older than about 8 days are daily aggregates `{"time" <day start>
  "nSamples" 24 ...}`. Newer rows are hourly at `hh:00:10`, with no `nSamples`.
- `userNonFundingLedgerUpdates` carries `{"type" "borrowLend" "token" "USDC"
  "operation" "supply"|"withdraw"|"borrow"|"repay" "amount" "…" "interestAmount" "…"}`.
  It returns `null`, not `[]`, when a window has no events.

## Plan of Work

1. Pure model `hyperopen.borrow-lend.returns`. Walk backward from today's
   supplied value through interest rows (each adds to the balance) and
   supply/withdraw events. That gives each row's balance and its exposure
   (balance × hours). Period sums then give realized APY, average balance and
   days supplied.
2. Fetch: the interest effect pages `userBorrowLendInterest` from time 0, then
   pages the ledger from the first interest row. It stores `:interest` (all
   rows) and `:ledger` (borrowLend supply/withdraw events). The VM filters the
   existing "Interest (30d)" column and History tab back to 30 days, using the
   interest load time.
3. The VM builds `:returns` only while the positions tab is active.
4. The view renders the card under the positions table.

## Progress

- [x] Probe live payload shapes and validate the backward reconstruction on a real supplier (residual 0.02 USDC on 4,660).
- [x] Pure returns model and tests.
- [x] Interest + ledger paging fetch and projection.
- [x] VM + view, 30-day filtering for the existing column and history.
- [x] Gates (`npm run check`, `npm test`, `npm run test:websocket`) and browser QA on /earn.
- [ ] Owner review in the live app; move this plan to completed once accepted.

## Surprises & Discoveries

- Portfolio-margin accounts auto-supply idle USDC/USDT0 with no ledger events
  (one probed account: 394k supplied, zero borrowLend events). Backward
  reconstruction cannot be trusted there, so the card says realized returns are
  unavailable for portfolio margin.
- Browser QA on a real supplier first showed "31 / 30" days: a daily row
  that straddles a period's start was counted whole. Rows are now prorated by
  the share of their span inside the window.
- Daily interest rows are stamped at the start of the day they cover, so a
  supply later that day comes after the row's stamp. Rows are ordered by the end
  of the window they cover (hourly: stamp; daily: stamp + nSamples hours) so a
  day's deposit counts toward that day's balance.

## Decision Log

- No "average indicated APY" column: there is no rate-history endpoint, and the
  interest-implied rate equals realized APY by construction.
- Simple annualization (interest ÷ average balance × year ÷ hours supplied),
  matching how HyperCore quotes `supplyYearlyRate`.
- Validity: a reconstructed balance below −tolerance, or interest accruing on
  a zero balance, marks the token unavailable instead of showing a wrong number.
- Periods longer than the position's history are hidden. "Since first supply" always shows.

## Outcomes & Retrospective

Gates green: `npm run check`, `npm test` (8094 tests, 0 failures), `npm run test:websocket`
(612 tests). Browser QA on the worktree build (static SPA server, spectate):
supplier 0x7c4b…e00b shows 7d 5.90%, 1m 4.18%, 3m 3.25%, 6m 2.51% and 1.73% since
Jan 7 2026 against a 6.99% current APY. Day counts are exact after proration. At 375 px the
table scrolls inside its box with no page overflow. Portfolio-margin account
0x325e…9564 shows the unavailable message.

Known differences: "Interest (30d)" keeps its whole-row 30-day filter, so it can
differ from the 1-month row by part of one day's interest. Earn now makes about 4 info
requests (two pages each of interest and ledger) instead of 1.

## Validation and Acceptance

- `npm test` covers `hyperopen.borrow-lend.returns-test`, the updated effects,
  projection and VM tests.
- `npm run check`, `npm run test:websocket` pass.
- /earn › Your positions shows the card for a supplier, the
  portfolio-margin message for PM accounts, and no card without supplied
  interest-earning tokens.
