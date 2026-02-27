# Performance Metrics Irregular-Cadence Sensitivity Run (2026-02-27)

## Run Metadata

- Script: `node tools/metrics_irregular_sensitivity.mjs`
- Monte Carlo trials: 750
- Synthetic horizon: 1000 daily observations
- Real accounts analyzed: 5

## Monte Carlo Summary

Relative error is measured against the full daily-series irregular estimator baseline.

### Weekly Subsample

- New CAGR mean error: 0.00% (threshold 1.00%)
- Old CAGR mean error: 551.91%
- New Vol mean error: 4.27% (threshold 15.00%)
- Old Vol mean error: 120.45%
- New Sharpe mean error: 4.30% (threshold 20.00%)
- Old Sharpe mean error: 520.10%

### Biweekly Subsample

- New CAGR mean error: 0.00% (threshold 1.00%)
- Old CAGR mean error: 2654.39%
- New Vol mean error: 6.49% (threshold 15.00%)
- Old Vol mean error: 211.91%
- New Sharpe mean error: 6.54% (threshold 20.00%)
- Old Sharpe mean error: 758.49%

### Random 7-21 Day Subsample

- New CAGR mean error: 0.00% (threshold 1.00%)
- Old CAGR mean error: 2644.91%
- New Vol mean error: 6.16% (threshold 15.00%)
- Old Vol mean error: 212.51%
- New Sharpe mean error: 6.24% (threshold 20.00%)
- Old Sharpe mean error: 789.38%

## Real-Account Sensitivity Summary

Divergence measured against each account's full `month` window baseline.

- Sparse(weekly) vs month (new): CAGR 0.00%, Vol 23.07%, Sharpe 33.65%
- Sparse(weekly) vs month (old): CAGR 27923.03%, Vol 64.80%, Sharpe 538.89%
- AllTime-overlap vs month (new): CAGR 11.72%, Vol 40.80%, Sharpe 44.35%
- AllTime-overlap vs month (old): CAGR 54336623.83%, Vol 107.13%, Sharpe 885.41%

## Notes

- This run validates cadence sensitivity reduction relative to the legacy fixed-period annualization pattern.
- Real-account comparisons depend on public account data available at run time and may vary across dates.
