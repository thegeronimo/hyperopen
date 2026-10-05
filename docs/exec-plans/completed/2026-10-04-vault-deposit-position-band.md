# Vault detail: "Your position" band with per-deposit history

Status: completed
Owner: vaults
Started: 2026-10-04

## Purpose / Big Picture

The vault detail page (`/vaults/<address>`) shows the vault's own P&L well but
says almost nothing about the viewer's money. Today the hero and the "Your
Performance" tab show two numbers, "Your Deposits" (current vault equity) and
"All-time Earned". A depositor cannot answer the basic question "how has my
current deposit done since I put it in?": there is no cost basis, no return
percentage, no holding period, no lockup status and no list of their own
transfers into this vault.

This plan ships design direction A from the Claude Design canvas "Vault Deposit
Performance" (a position band under the hero) with direction B's per-deposit
list folded in, as recommended in review and approved by the user.

## Context References

- Originating request: direct user request on 2026-10-04 ("go with your
  recommendation and implement it") after reviewing the design canvas
  `https://claude.ai/artifact/UyrJBC8E3a7RQy79i5Dr4P` (artboards `Main.dc.html`,
  `PanelDirection.dc.html`, `States.dc.html`).
- Repo artifacts:
  - `src/hyperopen/views/vaults/detail/hero.cljs`, `panels.cljs`
  - `src/hyperopen/views/vaults/detail_vm.cljs`, `detail_vm/context.cljs`
  - `src/hyperopen/vaults/effects.cljs` (`api-fetch-vault-details!`)
  - `src/hyperopen/api/projections/vaults.cljs`
  - `src/hyperopen/api/endpoints/vaults/details.cljs` (`normalize-follower-state`)

## Data semantics (verified against the live API on 2026-10-04)

`vaultDetails` with `user` returns `followerState` with `vaultEquity`, `pnl`,
`allTimePnl`, `daysFollowing`, `vaultEntryTime`, `lockupUntil`. Checked against
follower `0x0183…3834` of vault `0x1e37…8d5e` and that user's
`userNonFundingLedgerUpdates`:

- `vaultEquity − pnl` equals the cost basis of the CURRENT position
  (22,609.80 = the single re-deposit after a full withdrawal).
- `pnl` is unrealized P&L on the current position.
- `allTimePnl − pnl` equals realized P&L net of leader commission
  (1,213.74 = `netWithdrawnUsd − basis` of the earlier full withdrawal).
- `vaultEntryTime` is the FIRST ever deposit; it is not reset by a full
  withdrawal.
- Ledger `vaultWithdraw` rows carry `requestedUsd`, `commission`,
  `closingCost`, `basis` and `netWithdrawnUsd`; `vaultDeposit` rows carry `usdc`.

## Scope

In:

1. Pure model `hyperopen.vaults.detail.position`: from the follower row, the
   viewer's raw ledger rows, the vault's all-time returns rows and `now-ms`,
   derive value, cost basis, unrealized and realized P&L, return on cost basis,
   current-position start (first deposit after the last full withdrawal), days
   held, lockup status, and a transfer list where each deposit in the current
   position carries the vault's return since that deposit (interpolated over
   the sparse all-time history, labelled approximate) and each withdrawal
   carries realized P&L and commission.
2. Fetch the viewer's ledger for this vault after vault details resolve with a
   follower state, starting at `vaultEntryTime`, stored per vault and viewer at
   `[:vaults :viewer-ledger-by-address <vault> <viewer>]`. Failure of this fetch
   never fails the details load.
3. View: a "Your position" band directly under the hero (all states: no
   position, locked, underwater, history loading, spectating), hero cards
   become TVL / Past Month Return / APR / Your Position, and the "Your
   Performance" tab shows the same model's numbers.

Added in review round 2 (user request, 2026-10-04):

- Band restyled onto teal theme tokens (`ho-bg-deep`, `ho-border-accent`,
  `ho-accent-soft` wash) to match the vault hero instead of grey surfaces.
- In-band "Position value since deposit" chart
  (`views/vaults/detail/position_chart.cljs`): estimated value vs dashed cost
  basis, deposit/withdraw markers, CSS-only hover readouts. Value is
  reconstructed as units bought at the vault's share-price index
  (`position/position-series`), using the finest of the day/week/month/
  all-time histories that covers the first deposit, with drift to the live
  balance spread linearly so there is no cliff at "now".
- Near-zero values render neutral (no red "0.00%"); vault-relative returns
  wait for a full day; "opened today" instead of "0 days"; transfer lists over
  six rows fold older ones behind a native disclosure; y-axis never pads below
  $0; long spans show the year.
- Unrelated testing aid: the Depositors table's address cell reveals the full
  address on hover/focus and copies it on click (reusing
  `:actions/copy-spectate-mode-watchlist-address`), with an inline
  "Address copied" status; the vault leader row (Hyperliquid sends the literal
  `"Leader"`) now shows a Leader tag with the leader's real address.

Out (deferred):

- A "Your position" series inside the main vault chart (the in-band chart
  covers the need without touching the d3 chart engine).
- Annualized return and "vs. vault" attribution (direction C). Simple return
  on a basis that changes with top-ups cannot be annualized honestly.

## Progress

- [x] Verify follower-state and ledger semantics against the live API.
- [x] Pure position model + unit tests.
- [x] Viewer ledger fetch: projections, effect chaining, adapter wiring, tests.
- [x] View model wiring and view components.
- [x] Tests for VM and view.
- [x] Gates: `npm run gates` 34/34 PASS (7701 tests).
- [x] Browser QA of the band on a real vault in spectate mode (1440 and 390 wide).

## Surprises & Discoveries

- `vaultEntryTime` survives full withdrawals, so "since inception" of the
  current position has to be reconstructed from the ledger, walking basis.
- `lint:theme-colors` forbids new hex literals in views, so the band uses
  `ho-*` tokens (`bg-ho-surface-raised`, `text-ho-buy`/`-sell`/`-warn`/`-info`)
  rather than the canvas's hand-picked teal hexes; it reads slightly greyer than
  the legacy teal vault panels around it.
- `vaults/effects.cljs` sits at its namespace-size cap; the ledger follow-up
  body lives in `hyperopen.vaults.effects.viewer-ledger` and the cap entry was
  raised 666 → 674 for the call site. New tests went to new namespaces
  (`vaults/effects/viewer_ledger_test.cljs`, `views/vaults/detail_vm_position_test.cljs`)
  instead of growing capped test files.
- On the live follower, "vault, same period" read +3.65% against the
  position's +2.92% over 36 days. Part of that gap is interpolation over the
  vault's sparse all-time history, which is why both are labelled "≈".

## Decision Log

- Headline return is `pnl / cost basis` (Hyperliquid's own definition of the
  position's P&L), not a money-weighted or time-weighted figure. It is exact,
  explainable, and matches what Hyperliquid itself reports.
- "Vault return since" per deposit is derived from the vault's all-time
  history (~60-70 points) by linear interpolation and is labelled "≈".
- The viewer ledger is fetched inside the existing details effect instead of a
  new effect id, so no new action/effect contract surface is introduced.

## Validation and Acceptance

- Unit tests cover: single deposit, top-up, partial withdrawal, full
  withdrawal then re-deposit (the live example above), locked vs withdrawable,
  missing ledger, missing follower state.
- `npm run check`, `npm test`, `npm run test:websocket` pass.
- In the browser, spectating a known follower of a vault shows the band with
  cost basis = value − pnl and the transfer list.

## Outcomes & Retrospective

Shipped on branch `feature/vault-deposit-performance-d26c51` (uncommitted at
hand-off):

- `src/hyperopen/vaults/detail/position.cljs` (pure model) and
  `src/hyperopen/views/vaults/detail/position.cljs` (band).
- `src/hyperopen/vaults/effects/viewer_ledger.cljs`, chained from
  `api-fetch-vault-details!`; projections in `api/projections/vaults.cljs`;
  adapter wiring in `runtime/effect_adapters/vaults.cljs`; defaults in
  `state/app_defaults.cljs`.
- Hero cards are now TVL / Past Month Return / APR / Your Position; the "Your
  Performance" tab reads the same model.

Round 2 verified live (spectate) on six accounts: Growi re-deposit, Growi
loss, Growi 19-transfer recent, HLP 817-day/39-transfer, HLP 2-day, Growi loss
at 390px; depositor copy verified with a real clipboard read. Gates 34/34.

Verified live by spectating follower `0x0183…3834` on vault `0x1e37…8d5e`:
cost basis $22,609.80 = value − pnl, realized +$1,213.74 = the full
withdrawal's `netWithdrawnUsd − basis`, earlier-position transfers dimmed.
No committed Playwright spec: the live check depends on real API data; the
deterministic behaviour is covered by unit tests. Deferred: chart "Your
position" series mode and direction C's timing attribution.
