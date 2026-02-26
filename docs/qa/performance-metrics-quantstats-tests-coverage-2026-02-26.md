# QuantStats Test Coverage Comparison for Portfolio Metrics (2026-02-26)

## Scope

Compared QuantStats tests at commit `fbd10daed0227aa0d10da6513f1b15e7e98d7fae`:

- `tests/test_stats.py`

against Hyperopen portfolio metric tests:

- `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs`

Goal: ensure base-case coverage for overlapping functions used by Hyperopen performance metrics tab is at least equivalent in type.

## Result

For all overlapping functions from QuantStats `test_stats.py`, Hyperopen now has equivalent-or-stronger coverage for base cases and parity values.

## Coverage Mapping (Overlapping Functions)

- `comp`: covered for parity and single-return base case.
- `exposure` (QuantStats) vs `time-in-market` (Hyperopen): covered for parity and edge ratios.
- `win_rate`: covered for all-positive and all-negative base cases.
- `volatility`: covered for exact annualized and non-annualized parity values.
- `max_drawdown`: covered for parity value and drawdown-detail integration.
- `var` / `cvar`: covered for parity values, ordering relationship (`cvar <= var`), and report-sign normalization in `compute-performance-metrics`.
- `sharpe`: covered for parity value, empty input behavior, and `rf` sensitivity (`rf` higher => Sharpe lower).
- `sortino`: covered for parity value and non-equality vs Sharpe.
- `calmar`: covered for parity value and annualization semantics via compute-level tests.
- `omega`: covered for parity value.
- `cagr`: covered for parity value and compute-level annualization basis behavior.
- `r_squared`: covered for parity value and bounded-range constraint (`0 <= r2 <= 1`).
- `information_ratio`: covered for parity value.
- `to_drawdown_series`: covered for output length and sign constraints (`<= 0`).
- `drawdown_details`: covered for period count, key fields, and max-dd extraction.
- `consecutive_wins` / `consecutive_losses`: covered for exact expected values.

## Not Applicable / Intentionally Different

QuantStats `test_stats.py` coverage for these functions is not directly comparable for Hyperopen performance tab scope:

- `compsum`: Hyperopen does not expose this function.
- `greeks`, `treynor_ratio`: not part of current performance metrics tab implementation.
- DataFrame-input behavior in QuantStats: Hyperopen metrics operate on Clojure vectors/rows, not pandas DataFrames.

## Evidence

Key Hyperopen tests now covering the QuantStats-style base cases:

- `quantstats-base-case-coverage-alignment-test`
- `quantstats-ratio-parity-test`
- `quantstats-risk-and-distribution-parity-test`
- `quantstats-benchmark-parity-test`
- `drawdown-details-parity-test`
- `compute-performance-metrics-*` parity tests

