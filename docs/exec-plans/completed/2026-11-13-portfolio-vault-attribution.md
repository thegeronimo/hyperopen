# Portfolio: vault returns attribution

## Purpose / Big Picture

The Portfolio page reports returns for the whole account. A user who allocates
to several vaults cannot see how that allocation has performed by itself. This
plan adds vault attribution in two places (designs A and C of the 2026-11-13
design canvas "Vault Returns Attribution"):

- **A, Vaults scope.** A third option, "Vaults", in the summary scope selector
  next to "Perps + Spot + Vaults" and "Perps". The summary card, the chart
  (Returns, Vault Value, PNL) and Performance Metrics re-scope to the vault
  sleeve.
- **C, Vaults tab.** A new account-table tab with headline numbers (vault PNL,
  money-weighted return, time-weighted return, vault count), a chart of vault
  value against net deposited with deposit and withdrawal markers, and a
  per-vault table that includes closed positions.

Context: a direct user request in the Claude Code session on 2026-11-13
("Implement A + C together.").

## Context and Orientation

- `src/hyperopen/vaults/detail/position.cljs`: rebuilds ONE vault's current
  position from ledger transfers and the vault's return index. The new model
  generalises it to every vault ever held, closed ones included.
- `src/hyperopen/portfolio/metrics/history.cljs`: returns from a summary entry
  `{:accountValueHistory :pnlHistory}` by modified Dietz, with cash flow
  implied as Δaccount − Δpnl. An interval whose flow is at least half the
  starting balance scores 0%.
- `src/hyperopen/views/portfolio/vm.cljs`, `vm/summary.cljs`: scope and range
  select a key in `summary-by-key` (`:all-time`, `:perp-all-time`, ...).
- `src/hyperopen/startup/collaborators.cljs` `fetch-portfolio!`: loads a
  ledger window that matches the portfolio history, not the full history.

Live payloads, checked on mainnet 2026-11-13 against seven vault depositors:

- `userNonFundingLedgerUpdates` from `startTime 0` returned rows back to
  January 2024. The largest account had 294 rows; no cap was observed.
- `vaultWithdraw` carries `requestedUsd`, `netWithdrawnUsd`, `basis`,
  `commission`, `closingCost`. `vaultDeposit` carries `vault`, `usdc`.
- Vault leaders also have `vaultCreate` and `vaultLeaderCommission` rows.
- One account had four vaults in its ledger and three in
  `userVaultEquities`, so the ledger is the source of the vault list.
- `vaultDetails` history is about daily for the last 31 days and about
  12-day spacing before that.

## Plan of Work

1. Pure model `hyperopen.portfolio.vault-attribution`: per-vault and total
   deposited, withdrawn, value, PNL, realized, unrealized, money-weighted
   return (IRR), vault return while held; the sleeve series (value, net
   deposited, cumulative PNL); transfer markers; synthetic summary entries
   `:vault-day`, `:vault-week`, `:vault-month`, `:vault-all-time`.
2. Data loading: one effect fetches the full ledger and `vaultDetails` (with
   `user`) for each vault found in it, into `[:portfolio :vault-attribution]`;
   a memoized selector builds the model.
3. Design A: `:vaults` scope end to end. The synthetic entries are merged
   into `summary-by-key`, so the existing returns, drawdown, metrics and
   custom-range code runs on the sleeve unchanged.
4. Design C: the Vaults tab.
5. Playwright coverage, gates, live browser check, independent review.

## Progress

- [x] 1. Pure model and unit tests
- [x] 2. Data loading, state, contract surface, selector
- [x] 3. Vaults scope (A)
- [x] 4. Vaults tab (C)
- [x] 5. Playwright spec, gates, browser QA, review

## Surprises & Discoveries

- The portfolio already stores a ledger at `[:portfolio :ledger-updates]`, but
  only for the window of the portfolio history, so it cannot serve attribution.
- Net deposited can go below zero: an account that has withdrawn more than it
  put in (profits taken out) has a negative net-deposited line, so the tab
  chart's axis can legitimately start below $0.
- The independent review found that pairing a ledger snapshot with live
  60-second vault equity turned any later transfer into phantom PNL, and that
  `vaultDistribution` rows were ignored. Both are fixed (Decision Log).
- The in-app browser pane does not paint while hidden, so a correct page can
  look stuck on its loading state during QA; take a screenshot to force frames.

## Decision Log

- Dollar PNL and money-weighted return come from ledger dollars and current
  equity only. They are exact and need no vault price history. The
  time-weighted curve leans on the coarse vault index and is marked as an
  estimate with the existing "~" low-confidence mark.
- Every transfer contributes two series points 1 ms apart, so the cash-flow
  interval carries no PNL and the 0.5 flow-ratio guard cannot erase return.
- The sleeve is injected as synthetic summary entries rather than a parallel
  view-model path, to reuse windowing, custom ranges and metrics.
- The realized/unrealized split treats a `vaultDistribution` as return of
  capital first, unlike the pro-rata basis of withdrawals; total PNL is
  unaffected.
- Between transfers the sleeve path is interpolated from the coarse vault
  index, so windowed returns and drawdown are estimates.
- Every load makes one extra ledger request (the paging probe).
- Deferred from the designs: the whole-portfolio comparison line and column
  in the Vaults scope, and the dashed "estimated" segment of the chart line.
  The estimate is disclosed by text and the "~" mark instead.

- Review fixes (2026-11-13):
  - Equity comes from the `vaultDetails` follower state fetched in the same
    load as the ledger. Live `userVaultEquities` rows are used only for a
    vault whose details failed, and only when they belong to the shown
    account. Otherwise the vault's value is unknown, shown as `--`, and left
    out of totals.
  - The load refetches when the slot is marked stale (after a successful
    vault transfer) or when live equity drifts more than 2% (and $1) from the
    snapshot, once the snapshot is older than 60 s.
  - `vaultDistribution` counts as a withdrawal tagged `:source
    :distribution`. It closes the position only when it is the last transfer
    and the known equity is about zero.
  - The ledger is paged until a page adds no new row, capped at 25 pages.
    Hitting the cap sets `:ledger-partial?`, and the UI says the history may
    be incomplete.
  - Totals cover complete vaults only. P&L totals are nil when no vault is
    complete, and `:incomplete-count` drives the "N vaults excluded" line.
  - Money-weighted return is not annualised for holds under 30 days. A total
    loss reads −100% at any hold length.
  - In the Vaults scope, Max Drawdown is the peak-to-trough fall of the
    sleeve's return index, so it is at most 100%.
  - A failed refresh keeps the last good rows and shows a Retry line.

- Second review fixes (2026-11-13):
  - "Vault return while held" is the time-weighted return of the vault's own
    one-vault sleeve (`series/time-weighted-return-pct`), read by the same
    returns code as the "All vaults" figure, so a one-vault account's row and
    total agree by construction. The raw vault index was distorted by the
    vault's own aggregate flows. The row cell carries the "~" mark. A vault
    without index history now gets a figure too (cost basis, P&L recognised
    at withdrawals), as the total already did.
  - The basis reaching zero no longer proves a position empty. A withdrawal
    or distribution with no open period reopens the previous period, and a
    closed last period reopens while known equity is held. Only a withdrawal
    before any deposit is a leading withdrawal. Held time now runs to a late
    dust row.
  - Equity: a failed vault's live value is used only when the live rows list
    it; otherwise it is unknown, never $0. Details without follower data are
    a known zero only when trusted live rows confirm absence or the ledger
    shows the position fully withdrawn.
  - Drift compares only vaults both in the snapshot and in the live rows,
    plus new live vaults above $1 that were not in the live rows when the
    data loaded (`:live-vaults-at-load`, recorded by the projection). A
    reload that changes nothing is quiet.
  - A vault in the account's trusted live rows but not in its ledger is
    shown with its value as "Deposit history incomplete" and counted as
    excluded. Its name comes from `[:vaults ...]` state, else the short
    address.
  - Ledger paging: a page that adds nothing new is probed once from the next
    millisecond. New rows there mean a page of one timestamp hid rows, so
    paging continues with `:ledger-partial?`. Every load makes one extra
    ledger read.

## Outcomes & Retrospective

Shipped designs A and C. Validation on 2026-11-13, after both review-fix rounds:

- `npm run gates`: 34/34 PASS, 9,393 tests.
- `tools/playwright/test/portfolio-vault-attribution.spec.mjs`: 4 passed
  (tab, scope, error and Retry, empty state), one worker.
- Live check on two mainnet accounts through the read-only trader route. A
  three-vault account (294 ledger rows, one closed vault, one vault it leads)
  reproduced the pre-fix per-vault PNL figures (which had been compared with
  Hyperliquid's `allTimePnl`: within $100 on million-dollar positions, the
  closed vault exactly). On a single-vault account the vault row and the
  total now show the same time-weighted return (+38.8%). Checked at 375,
  768, 1280 and 1440 wide with no horizontal overflow. Repeated reloads drew
  HTTP 429s from the public API (uncaught promise rejections in the info
  client, not in the attribution effect, which catches its own errors).

Remaining risks and follow-ups:

- The time-weighted figures are estimates before the last 31 days. (The
  per-vault vs total mismatch is fixed; see "Second review fixes".)
- The optimizer's vault-funding path does not mark the slot stale; the 2%
  equity-drift check picks it up after 60 seconds.
- The realized/unrealized split treats a `vaultDistribution` as return of
  capital first, unlike the pro-rata basis of withdrawals; total PNL is
  unaffected.
- Between transfers the sleeve path is interpolated from the coarse vault
  index, so windowed returns and drawdown are estimates.
- Every load makes one extra ledger request (the paging probe).
- Deferred from the designs: the whole-portfolio comparison line and column,
  the dashed estimated segment, and hover on the tab chart.

## Validation and Acceptance

- `npm run gates` (check, test, test:websocket) green.
- Per-vault PNL sums to total PNL, which equals the last point of the sleeve
  PNL series.
- Selecting "Vaults" shows vault numbers in the summary card, chart and
  metrics; "Perps + Spot + Vaults" and "Perps" are unchanged.
- The Vaults tab renders loading, empty, error and ready states, and lists
  closed vaults.
- New Playwright spec passes; live check by spectating a multi-vault account.
