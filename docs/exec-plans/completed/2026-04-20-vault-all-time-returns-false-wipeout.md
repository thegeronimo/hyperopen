# Fix Vault All-Time Returns False Wipeout

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The vault detail page can show an all-time return near `-100%` for a vault that did not economically lose all capital. After this change, the vault performance card, returns chart, and performance metrics will treat high-magnitude inferred cash-flow or rebase intervals as indeterminate instead of converting them into huge synthetic gains or wipeouts. A user can verify the behavior with a focused estimator test that fails before the fix and passes after it.

The active `bd` issue is `hyperopen-w87u`.

## Progress

- [x] (2026-04-20 13:48Z) Reproduced the live `pmalt` vault all-time payload from `vaultDetails` and confirmed the current estimator compounds to about `-99.998%`.
- [x] (2026-04-20 13:49Z) Identified the failing interval: a large inferred flow relative to a roughly `100` account-value anchor falls through to `fallback-period-return`, producing a clamped near-total-loss period return.
- [x] (2026-04-20 13:51Z) Wrote a focused failing regression in `test/hyperopen/portfolio/metrics/history_test.cljs`; `npm test` failed with `[0, 600, -99.9993, -99.99895]` for the new case.
- [x] (2026-04-20 13:54Z) Changed `src/hyperopen/portfolio/metrics/history.cljs` so high-magnitude inferred flows with a still-positive current account value are neutralized before fallback.
- [x] (2026-04-20 13:56Z) Updated `spec/lean/Hyperopen/Formal/PortfolioReturnsEstimator.lean` and regenerated `test/hyperopen/formal/portfolio_returns_estimator_vectors.cljs` with `npm run formal:sync -- --surface portfolio-returns-estimator`.
- [x] (2026-04-20 14:06Z) Verified the formal surface and required repository gates: `npm run formal:verify -- --surface portfolio-returns-estimator`, `npm run check`, `npm test`, and `npm run test:websocket` all exited 0.

## Surprises & Discoveries

- Observation: The user-visible card values come from `:snapshot` in `src/hyperopen/views/vaults/detail_vm.cljs`, and that path prefers computed portfolio returns over the vault index snapshot when detailed portfolio history exists.
  Evidence: `detail-metrics-context` calls `performance-model/summary-cumulative-return-percent` before `performance-model/snapshot-value-by-range`.
- Observation: The live vault all-time history starts with a nonpositive account anchor and then contains large inferred-flow intervals around December 2024; the current estimator falls back to `delta_pnl / previous_account_value` when Modified Dietz rejects a large flow.
  Evidence: the interval ending `2024-12-18T23:10:00.069Z` has `previous_account_value = 100.000001`, `delta_pnl = -769.5768669999998`, `implied_cash_flow = 769.5768669999998`, and produces a clamped `-0.999999` period return.
- Observation: Broadening the classifier intentionally changes the old invalid-Dietz fixture from `[0, 200, 500]` to `[0, 0, 100]`.
  Evidence: after the production change, `npm test` failed only in the direct invalid-Dietz test and the generated formal vector for `:invalid-dietz-denominator`; the new regression passed.

## Decision Log

- Decision: Fix the shared portfolio returns estimator instead of special-casing the vault performance card.
  Rationale: The same cumulative rows feed the card, returns chart, benchmark alignment, daily returns, and performance metrics. A UI fallback would hide the symptom in one card while leaving the bad series elsewhere.
  Date/Author: 2026-04-20 / Codex.
- Decision: Treat any inferred cash flow whose magnitude is at least half the previous account value as indeterminate unless the existing Modified Dietz guard can safely price it.
  Rationale: The upstream payload does not expose true cash-flow events. When flow magnitude dominates the account base, the fallback return is not a reliable trading return and can create false wipeouts. Neutralizing that interval is conservative and matches the existing safety posture for large positive rebases.
  Date/Author: 2026-04-20 / Codex.

## Outcomes & Retrospective

The estimator now neutralizes high-magnitude inferred-flow intervals while the current account value remains positive. Low-flow intervals continue using Modified Dietz, ordinary no-flow intervals still recover exact returns, catastrophic real losses still clamp, and high-flow ambiguous intervals no longer collapse all-time returns.

## Context and Orientation

Vault detail rendering is built from `src/hyperopen/views/vaults/detail_vm.cljs`. The small return tiles under the Vault Performance tab are the `:snapshot` values returned by `build-vault-detail-chart-section`. The all-time tile uses `detail-metrics-context`, which calls `summary-cumulative-return-percent` in `src/hyperopen/vaults/detail/performance.cljs`.

`summary-cumulative-return-percent` delegates to `hyperopen.portfolio.metrics/returns-history-rows`. That function is implemented in `src/hyperopen/portfolio/metrics/history.cljs`. It aligns `accountValueHistory` and `pnlHistory`, infers cash flow as `delta_account - delta_pnl`, tries a Modified Dietz return when the inferred flow is small, falls back to `delta_pnl / previous_account_value`, and clamps any period return below `-99.9999%`.

The bug is not a formatting error. It is a classifier gap in `indeterminate-cash-flow?`: only large positive inferred flows that also raise account value are neutralized. A large ambiguous flow when account value is flat or dropping still reaches the fallback path, where a PnL move divided by a tiny account base can fabricate extreme returns.

The formal estimator surface in `spec/lean/Hyperopen/Formal/PortfolioReturnsEstimator.lean` generates `test/hyperopen/formal/portfolio_returns_estimator_vectors.cljs`. If the production estimator changes, the Lean model and generated vectors must change too so `npm test` keeps comparing production behavior against an explicit modeled contract.

## Plan of Work

First, add a failing regression to `test/hyperopen/portfolio/metrics/history_test.cljs` using a small sequence that reproduces the live-vault failure mode: a normal start, a high-flow ambiguous rebase around a low account-value anchor, and later positive performance. The assertion should prove the final cumulative return stays well above the false-wipeout threshold.

Second, update `src/hyperopen/portfolio/metrics/history.cljs`. The minimal change is to broaden `indeterminate-cash-flow?` so high-magnitude inferred flows are neutralized before fallback. Low-flow intervals still use Modified Dietz. Catastrophic no-flow losses still reach fallback and clamp.

Third, update `spec/lean/Hyperopen/Formal/PortfolioReturnsEstimator.lean` to match the production classifier. Rename or adjust the old invalid-Dietz vector so it documents high-flow neutralization rather than the old fallback behavior. Run `npm run formal:sync -- --surface portfolio-returns-estimator` to regenerate `test/hyperopen/formal/portfolio_returns_estimator_vectors.cljs`, then run `npm run formal:verify -- --surface portfolio-returns-estimator`.

Fourth, run the focused estimator tests, then the required gates: `npm run check`, `npm test`, and `npm run test:websocket`. Because the change is in shared pure return computation and not UI layout or browser interaction, browser QA is not required unless the implementation touches UI files.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/45af/hyperopen`.

1. Add a new regression in `test/hyperopen/portfolio/metrics/history_test.cljs`.
2. Run `npm test` once before production edits and confirm the new test fails because the final return is near `-100%`.
3. Edit `src/hyperopen/portfolio/metrics/history.cljs` to neutralize high-flow ambiguous intervals.
4. Update the Lean model and generated formal vectors.
5. Run focused and full validation commands.

## Validation and Acceptance

Acceptance requires:

- The new regression fails before the production change and passes after it. Verified by the RED `npm test` run, where the new regression produced `[0, 600, -99.9993, -99.99895]`, and the final GREEN `npm test` run, which reported 3318 tests, 18135 assertions, 0 failures, and 0 errors.
- Existing estimator tests still prove shared timestamps, duplicate timestamps, leading nonpositive anchors, catastrophic loss clamping, positive rebase neutralization, and daily compounding.
- `npm run formal:verify -- --surface portfolio-returns-estimator` succeeds after regenerating vectors. Verified on 2026-04-20.
- Required repository gates succeed: `npm run check`, `npm test`, and `npm run test:websocket`. Verified on 2026-04-20.

## Idempotence and Recovery

The edits are pure source and test changes. If a test fails after the estimator change, inspect whether it codifies the old fallback behavior or a real invariant. `npm run formal:sync -- --surface portfolio-returns-estimator` is deterministic and may be rerun safely. Do not run `git pull --rebase` or `git push` unless the user explicitly requests remote sync.

## Artifacts and Notes

Live reproduction summary from `vaultDetails` for `0x4dec0a851849056e259128464ef28ce78afa27f6`:

    allTime accountValueHistory count: 82
    allTime pnlHistory count: 82
    current estimator final: about -99.998%
    first bad interval: 2024-12-18T23:10:00.069Z
    previous account value: 100.000001
    delta PnL: -769.5768669999998
    implied cash flow: 769.5768669999998
    period return after fallback and clamp: -0.999999

## Interfaces and Dependencies

The public API remains `hyperopen.portfolio.metrics/returns-history-rows`. No caller signature changes are planned. The implementation dependency is the existing `history.cljs` pure helper stack. The model dependency is the existing Lean formal surface and generated CLJS vector namespace.
