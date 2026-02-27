# Portfolio and Vault Returns via Account Value + PnL Implied Flows

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, both `/portfolio` and `/vaults/<address>` construct their `Returns` series from the same mathematically consistent inputs: `accountValueHistory` and `pnlHistory` at aligned timestamps. The implementation will infer net cash flow per interval from the difference between account-value change and cumulative-PnL change, then compute interval returns using a Simple Dietz denominator and geometrically chain to cumulative return.

The user-visible result is that deposits, withdrawals, and internal transfers no longer need explicit ledger classification to avoid false return spikes. A user should be able to switch to `Returns` in both portfolio and vault detail and see that pure cash-flow jumps do not appear as performance gains.

## Progress

- [x] (2026-02-27 18:12Z) Reviewed planning contracts and active/completed returns-related ExecPlans in `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/exec-plans/**`.
- [x] (2026-02-27 18:12Z) Audited current returns computation in `/hyperopen/src/hyperopen/portfolio/metrics.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`, and `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`.
- [x] (2026-02-27 18:12Z) Audited current tests and ledger-fetch coupling in `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`, `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs`, and startup/vault ledger fetch paths.
- [x] (2026-02-27 18:12Z) Authored this ExecPlan.
- [x] (2026-02-27 18:19Z) Implemented AccountValue+PnL implied-flow return engine in `/hyperopen/src/hyperopen/portfolio/metrics.cljs` and removed ledger dependency from return construction.
- [x] (2026-02-27 18:19Z) Adopted the shared return engine in vault detail return-series construction in `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`.
- [x] (2026-02-27 18:19Z) Removed stale, unused local ledger-return implementation from `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` so return math has one authoritative source.
- [x] (2026-02-27 18:19Z) Updated tests in `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`, and `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs` for implied-flow and shared-timestamp behavior.
- [x] (2026-02-27 18:19Z) Ran required validation gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-27 18:19Z) Updated outcomes and prepared this ExecPlan for move to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: Portfolio return construction is currently ledger-driven in `portfolio.metrics` (flow-adjusted via `userNonFundingLedgerUpdates`) and does not use `pnlHistory` deltas for interval returns.
  Evidence: `/hyperopen/src/hyperopen/portfolio/metrics.cljs` functions `ledger-flow-events`, `interval-flow-stats`, and `returns-history-rows`.

- Observation: Vault detail return construction currently ignores `pnlHistory` and uses simple baseline account-value ratio.
  Evidence: `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` function `returns-history-points`.

- Observation: Portfolio VM still contains a local ledger-based returns implementation that appears unused because chart/model paths call `portfolio-metrics/returns-history-rows`.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` includes local `returns-history-rows` and related helpers, while `chart-history-rows` selects `portfolio-metrics/returns-history-rows`.

- Observation: Existing tests explicitly encode ledger event semantics (deposit, withdraw, accountClassTransfer) for return correctness; these assertions must be replaced with AccountValue+PnL implied-flow assertions.
  Evidence: `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs` current ledger-focused test cases.

- Observation: Portfolio bootstrap currently fetches non-funding ledger updates, but return computation can be made independent from that call.
  Evidence: `/hyperopen/src/hyperopen/startup/collaborators.cljs` (`fetch-portfolio!`) and current metrics implementation paths.

- Observation: `portfolio-vm` had a fully duplicated local ledger-return implementation that was not used by chart/performance paths because both already call `portfolio-metrics/returns-history-rows`.
  Evidence: removed block in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`; active call sites in `chart-history-rows` and `benchmark-computation-context`.

- Observation: Existing vault sample fixture (`accountValueHistory` + `pnlHistory`) now yields implied-flow returns `[0, 15.38, 34.62]` instead of raw-baseline `[0, 10, 50]`.
  Evidence: updated assertion in `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs`.

## Decision Log

- Decision: Use the implied-flow method from aligned account value and cumulative PnL for portfolio and vault return series:
  `ΔV_t = V_t - V_(t-1)`, `ΔPnL_t = PnL_t - PnL_(t-1)`, `CF_t = ΔV_t - ΔPnL_t`, `R_t = ΔPnL_t / (V_(t-1) + 0.5 * CF_t)`, cumulative `= Π(1 + R_t) - 1`.
  Rationale: This removes dependency on ledger event classification while still neutralizing external cash flows in return reporting.
  Date/Author: 2026-02-27 / Codex

- Decision: Keep the public function signature `portfolio-metrics/returns-history-rows` stable (`[state summary summary-scope]`) for compatibility in this change, but compute returns from summary histories only.
  Rationale: Limits integration churn while allowing immediate method replacement.
  Date/Author: 2026-02-27 / Codex

- Decision: Use strict timestamp alignment between account-value and pnl histories; when perfect alignment is unavailable, use only shared timestamps in ascending order.
  Rationale: The method requires synchronized measurements. Intersection alignment preserves mathematical correctness without interpolation assumptions.
  Date/Author: 2026-02-27 / Codex

- Decision: Preserve deterministic safety guards used by current returns chaining (`finite` checks, denominator guard, lower bound near `-100%`) and document fallback behavior.
  Rationale: Prevents NaN/Infinity propagation and keeps chart rendering deterministic on sparse or malformed payloads.
  Date/Author: 2026-02-27 / Codex

- Decision: Keep vault ledger fetch paths unchanged in this plan because deposits/withdrawals activity tab depends on them; treat startup portfolio-ledger fetch cleanup as optional follow-up.
  Rationale: Keeps this change scoped to return-construction correctness while avoiding unrelated API/runtime churn.
  Date/Author: 2026-02-27 / Codex

- Decision: Remove duplicate local return-engine code from `portfolio-vm` during migration.
  Rationale: Eliminates drift risk where VM-local and shared metrics implementations diverge silently.
  Date/Author: 2026-02-27 / Codex

## Outcomes & Retrospective

Completed. Both portfolio and vault `Returns` series now use the same AccountValue+PnL implied-flow method (Simple Dietz interval denominator + geometric linking), and return construction no longer depends on ledger classification.

Validation outcome:

- `npm run check` passed.
- `npm test` passed (`Ran 1494 tests containing 7599 assertions. 0 failures, 0 errors.`).
- `npm run test:websocket` passed (`Ran 153 tests containing 701 assertions. 0 failures, 0 errors.`).

Scope outcome:

- Replaced ledger-driven portfolio returns with aligned account/pnl implied-flow computation in `/hyperopen/src/hyperopen/portfolio/metrics.cljs`.
- Unified vault returns on the same shared engine in `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`.
- Removed stale duplicate ledger-return implementation from `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`.
- Rebased tests from ledger-event semantics to implied-flow semantics and timestamp-alignment behavior.

## Context and Orientation

Portfolio return construction currently lives in `/hyperopen/src/hyperopen/portfolio/metrics.cljs` and is consumed by `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` for both chart rendering and performance metrics inputs. The current method infers cash flow from explicit ledger event streams (`:portfolio :ledger-updates` + `:orders :ledger`) and applies Modified Dietz weighting.

Vault detail return construction currently lives separately in `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` and computes simple cumulative return from account value only. This means vault returns and portfolio returns are not computed by the same method today.

In this plan, “implied flow” means cash flow inferred from history series deltas rather than from transaction-ledger events. Given aligned account-value and cumulative-PnL series, interval cash flow is `ΔV - ΔPnL`. “Simple Dietz denominator” means denominator `V_(t-1) + 0.5*CF_t`, which assumes intra-interval flow occurs at midpoint.

## Plan of Work

Milestone 1 replaces the return engine in `/hyperopen/src/hyperopen/portfolio/metrics.cljs` with a pure AccountValue+PnL implied-flow computation pipeline. Add helpers that normalize and align `accountValueHistory` and `pnlHistory` by shared timestamp, then compute interval returns from deltas and chain to cumulative percent rows. Keep existing return output contract (`[[time-ms cumulative-percent] ...]`) and preserve numerical guards for invalid denominators and near-`-100%` intervals.

Milestone 2 updates integration paths to use the unified engine. In portfolio VM, continue routing returns through `portfolio-metrics/returns-history-rows` and remove obsolete local ledger-return helper code in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` so there is one authoritative implementation. In vault detail VM, replace local raw-baseline `returns-history-points` logic with the same shared metric engine so vault and portfolio returns behave consistently.

Milestone 3 updates tests to reflect the new source-of-truth math. Replace ledger-specific assertions with fixtures that provide aligned account-value and pnl histories and validate implied-flow neutralization of cash-flow jumps. Add at least one fallback case where alignment is incomplete and verify deterministic behavior (empty or reduced series per decided guard policy). Ensure benchmark and performance-metric dependent tests still pass with unchanged interfaces.

Milestone 4 runs validation gates and records final evidence. If regressions appear, fix them in the same milestone and update this document’s `Progress`, `Surprises & Discoveries`, and `Decision Log` entries.

## Concrete Steps

Run from `/Users//projects/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/portfolio/metrics.cljs` to implement aligned AccountValue+PnL implied-flow returns and remove ledger dependency from `returns-history-rows`.
2. Edit `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` to remove local stale returns helpers and rely on the shared metrics implementation.
3. Edit `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` to derive returns via shared metrics instead of raw account baseline.
4. Update tests in:
   - `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs`
   - `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
   - `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs`
5. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

Expected result: all commands exit with status 0.

## Validation and Acceptance

Acceptance is met when all of the following are true:

1. Portfolio returns are computed from aligned `accountValueHistory` and `pnlHistory`, not from ledger event classification.
2. Vault detail returns are computed from the same method as portfolio returns.
3. A synthetic deposit/withdrawal-style jump where `ΔPnL = 0` does not produce a return spike in either portfolio or vault returns series.
4. Existing chart contracts remain stable (`Returns` tab, percent axis formatting, benchmark overlays).
5. Performance metrics still consume cumulative return rows without interface breakage.
6. Required validation gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

Manual verification scenario:

- Open `/portfolio`, select `Returns`, and verify large account-value changes without corresponding pnl deltas do not spike returns.
- Open a vault detail route, select `Returns`, and verify the same behavior.
- Confirm benchmark overlays still align to strategy return timestamps.

## Idempotence and Recovery

Code edits are source-only and idempotent. Re-running tests and validation commands is safe.

If the implied-flow computation fails on real data due to unexpected history shape, recovery is to keep the new helper structure but temporarily route `returns-history-rows` to a conservative fallback (for example account-value baseline method) while preserving output shape. This allows rapid stabilization without undoing structural integration changes.

## Artifacts and Notes

Primary implementation files:

- `/hyperopen/src/hyperopen/portfolio/metrics.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
- `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`

Primary tests to update:

- `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
- `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs`

Reference context:

- `/hyperopen/docs/references/hyperliquid-portfolio-history-and-returns.md`
- `/hyperopen/docs/exec-plans/active/2026-02-26-portfolio-returns-tab-time-series.md`

## Interfaces and Dependencies

No new external libraries are required.

Interface expectations at completion:

- `hyperopen.portfolio.metrics/returns-history-rows` still returns cumulative percent rows with timestamp pairs.
- `hyperopen.views.portfolio.vm/portfolio-vm` continues to expose chart/performance-metrics view model shape without breaking selectors/actions.
- `hyperopen.views.vaults.detail-vm/vault-detail-vm` continues to expose current chart/performance-metrics shape while changing returns math internals.

Interfaces intentionally preserved:

- Portfolio and vault returns benchmark selection action IDs and request flow.
- Existing chart tab and axis-kind contracts.
- Required validation gate commands.

Revision note (2026-02-27): Initial ExecPlan created to migrate both portfolio and vault return series to AccountValue+PnL implied-flow construction (Simple Dietz interval + geometric chaining), replacing divergent ledger/raw-baseline methods.
Revision note (2026-02-27): Marked implementation complete with validation evidence, captured final discoveries/decisions, and prepared move to completed plans.
