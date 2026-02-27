# Portfolio Performance Metrics Irregular-Cadence Validation (2026-02-27)

## Scope

Validated the irregular-cadence hardening changes in:

- `/hyperopen/src/hyperopen/portfolio/metrics.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
- `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`

Reference implementation plan and metric spec:

- `/hyperopen/docs/exec-plans/active/2026-02-27-portfolio-performance-metrics-irregular-cadence-hardening.md`
- `/hyperopen/docs/exec-plans/active/artifacts/2026-02-27-performance-metrics-irregular-cadence-metric-spec.md`

## What Was Validated

1. Core metrics now run on irregular intervals (elapsed-time annualization):
- `:cagr`
- `:volatility-ann`
- `:sharpe`
- `:sortino`
- `:expected-daily`, `:expected-monthly`, `:expected-yearly`

2. Daily-horizon metrics are gated and suppressed under insufficient cadence/coverage:
- `:omega`
- `:gain-pain-ratio`, `:gain-pain-1m`
- `:smart-sharpe`, `:smart-sortino`, `:prob-sharpe-ratio`
- benchmark-relative metrics (`:r2`, `:information-ratio`) when aligned points/coverage are insufficient

3. Drawdown-family metrics remain available as observed-path values, with reliability status separated from value emission.

4. Metric output now includes deterministic diagnostics metadata:
- `:quality`
- `:metric-status`
- `:metric-reason`

5. VM propagates metric status/reason into performance rows while preserving existing value/fallback contracts.

## Targeted Regression Coverage Added/Updated

In `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs`:

- Updated compute-level tests to validate elapsed-time CAGR behavior under irregular gating.
- Added gating-contract tests for suppressed metrics under sparse cadence.
- Added quality-diagnostics presence test (`:quality` + gate booleans).
- Kept explicit sign-convention validation for VaR/CVaR with gate overrides to exercise enabled path.

In `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`:

- Added assertions that portfolio metric rows carry `:portfolio-status` and `:portfolio-reason` for suppressed benchmark-relative metrics.

## Required Gate Results

All required gates passed after implementation:

- `npm run check`:
  - passed (docs/hiccup lint + app/test compile)
- `npm test`:
  - passed (`Ran 1494 tests containing 7594 assertions. 0 failures, 0 errors.`)
- `npm run test:websocket`:
  - passed (`Ran 153 tests containing 701 assertions. 0 failures, 0 errors.`)

## Notes

This validation pass focuses on deterministic behavior, gating policy correctness, and regression safety. It does not include a Monte Carlo synthetic-error benchmark run. If needed, add a follow-up QA artifact with cadence-distortion simulation metrics (CAGR/vol/Sharpe error envelopes) against the acceptance thresholds in the ExecPlan.
