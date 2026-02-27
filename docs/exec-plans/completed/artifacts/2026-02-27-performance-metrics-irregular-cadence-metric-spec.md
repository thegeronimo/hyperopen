# Irregular-Cadence Performance Metrics Implementation Spec

Companion document for `/hyperopen/docs/exec-plans/completed/2026-02-27-portfolio-performance-metrics-irregular-cadence-hardening.md`.

## Purpose

This document is the metric-by-metric implementation map for the irregular-cadence metrics refactor. It reconciles:

- the first research recommendation (hybrid strategy with strict gating for non-identifiable metrics),
- the follow-up recommendation (stronger formula detail and migration map),
- current Hyperopen implementation constraints in `/hyperopen/src/hyperopen/portfolio/metrics.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`.

Use this file as the source of truth for exact formula selection, gating, suppression policy, and fallback behavior per metric key.

## Reconciliation Summary

1. Adopted from both recommendations:
- Elapsed-time annualization for CAGR-family rows.
- Δt-aware log-return estimators for volatility and Sharpe-family rows.
- Variable-interval risk-free and MAR scaling.
- Strong data-quality gating and explicit suppression reasons.

2. Adopted from the follow-up recommendation:
- Explicit time-normalization framing (`l_i / sqrt(dt_i)`) and de-meaned variance-rate implementation detail.
- Gap-variance diagnostics (`cv-gap`) and max-gap caveat policy.
- Edge-case handling guidance and function-level migration map.

3. Kept from the first recommendation (and not overridden):
- Keep a hybrid policy, not full direct-irregular for every metric.
- Treat fixed-horizon and path-sensitive metrics (`omega`, `gain-pain-*`, `smart-*`, `prob-sharpe-ratio`, `calmar`) as daily-gated or suppressed on sparse cadence rather than forcing irregular approximations.

4. Implementation decision:
- Use direct irregular-time formulas for identifiable metrics.
- Use daily-bucketed formulas only when daily coverage gates pass.
- Never fabricate dense history with interpolation-only resampling as a default computation path.

## Canonical Notation

- `t_i`: interval end timestamp in milliseconds.
- `dt_i_years = (t_i - t_{i-1}) / MS_PER_YEAR`, where `MS_PER_YEAR = 365.2425 * 24 * 60 * 60 * 1000`.
- `f_i`: cumulative growth factor at `t_i` (`1 + cumulative_return_decimal`).
- `l_i = ln(f_i / f_{i-1})`: interval log return.
- `T = sum(dt_i_years)`: elapsed years in the window.
- `rho_f = ln(1 + rf_annual)`: annual continuous risk-free rate.
- `rho_mar = ln(1 + mar_annual)`: annual continuous MAR threshold.

Estimator primitives:

- Drift rate: `mu = sum(l_i) / T`.
- Variance rate: `sigma^2 = (1/(n-1)) * sum(((l_i - mu*dt_i_years)^2) / dt_i_years)`.
- Annualized vol: `sigma_ann = sqrt(max(sigma^2, 0))`.
- CAGR: `exp(sum(l_i)/T) - 1`.

## Quality Gates

Diagnostics are computed once per metric window and carried in output metadata.

- `n-intervals`: number of valid irregular intervals.
- `span-days`: elapsed days in window.
- `median-gap-days`, `p95-gap-days`, `max-gap-days`.
- `cv-gap`: coefficient of variation of interval gaps.
- `downside-count`: intervals where `l_i < rho_mar * dt_i_years`.
- `daily-points`, `daily-coverage`, `daily-max-missing-streak` (for daily-bucket metrics).

Gate levels:

- `core-min`: `n-intervals >= 10` and `span-days >= 30`.
- `core-high-confidence`: `n-intervals >= 20`, `span-days >= 90`, `cv-gap <= 1.5`, `max-gap-days <= 30`.
- `sortino-min`: `core-min` and `downside-count >= 5` and `n-intervals >= 20`.
- `daily-min`: `daily-points >= 60`, `daily-coverage >= 0.90`, `daily-max-missing-streak <= 3`.
- `psr-min`: `daily-min` and `daily-points >= 252`.
- `drawdown-reliable`: `max-gap-days <= 2` and `daily-points >= 180`.
- `rolling-ann-min(target-years)`: window span >= `target-years * 365.2425 * 0.5` days.

Suppression policy:

- Metric returns `nil` when hard gate fails.
- `:metric-status` records `:ok`, `:low-confidence`, or `:suppressed`.
- `:metric-reason` records stable reason codes (for deterministic tooltips and QA assertions).

## Metric-by-Metric Implementation Table

| Metric Key | Implementation Mode | Formula / Algorithm | Hard Gate | Sparse-Cadence Policy |
|---|---|---|---|---|
| `:time-in-market` | irregular | Time-weighted exposure: `sum(dt_i where abs(simple_i)>eps)/T` | `n-intervals >= 1` | show, low-confidence if `span-days < 30` |
| `:cumulative-return` | irregular | Endpoint factor ratio minus 1 | `n-intervals >= 1` | always show |
| `:cagr` | irregular | `exp(sum(l_i)/T)-1` | `core-min` | suppress when gate fails |
| `:volatility-ann` | irregular | variance-rate estimator over `l_i, dt_i_years` | `core-min` | suppress when gate fails |
| `:sharpe` | irregular | `(mu - rho_f) / sigma_ann` | `core-min` and `sigma_ann>0` | suppress when gate fails |
| `:sortino` | irregular | downside variance-rate using `min(0, l_i-rho_mar*dt_i)` | `sortino-min` | suppress when gate fails |
| `:expected-daily` | derived | from CAGR: `expm1(log1p(cagr)/365.2425)` | same as `:cagr` | suppress with `:cagr` |
| `:expected-monthly` | derived | from CAGR: `expm1(log1p(cagr)/12)` | same as `:cagr` | suppress with `:cagr` |
| `:expected-yearly` | derived | equal to CAGR | same as `:cagr` | suppress with `:cagr` |
| `:mtd` | irregular window | timestamp slice + geometric chaining | `n-intervals >= 1` in window | show when slice valid |
| `:m3` | irregular window | timestamp slice + geometric chaining | `n-intervals >= 1` in window | show when slice valid |
| `:m6` | irregular window | timestamp slice + geometric chaining | `n-intervals >= 1` in window | show when slice valid |
| `:ytd` | irregular window | timestamp slice + geometric chaining | `n-intervals >= 1` in window | show when slice valid |
| `:y1` | irregular window | timestamp slice + geometric chaining | `n-intervals >= 1` in window | show when slice valid |
| `:y3-ann` | irregular window | window CAGR via elapsed years | `rolling-ann-min(3)` | suppress if insufficient span |
| `:y5-ann` | irregular window | window CAGR via elapsed years | `rolling-ann-min(5)` | suppress if insufficient span |
| `:y10-ann` | irregular window | window CAGR via elapsed years | `rolling-ann-min(10)` | suppress if insufficient span |
| `:all-time-ann` | irregular | all-time CAGR via elapsed years | `core-min` | suppress when gate fails |
| `:max-drawdown` | observed path | running-peak drawdown on observed points | `n-intervals >= 1` | always show as observed-only when sparse |
| `:max-dd-date` | observed path | valley date from observed drawdown periods | `n-intervals >= 1` | show, mark observed-only when sparse |
| `:max-dd-period-start` | observed path | start date from observed drawdown periods | `n-intervals >= 1` | show, mark observed-only when sparse |
| `:max-dd-period-end` | observed path | end date from observed drawdown periods | `n-intervals >= 1` | show, mark observed-only when sparse |
| `:longest-dd-days` | observed path | max duration from observed drawdown periods | `n-intervals >= 1` | show, mark observed-only when sparse |
| `:calmar` | hybrid | `cagr / abs(max_drawdown)` | `drawdown-reliable` and drawdown < 0 | suppress if drawdown reliability fails |
| `:omega` | daily-gated | existing daily Omega with daily threshold conversion | `daily-min` | suppress when daily cadence not real |
| `:gain-pain-ratio` | daily-gated | existing daily GPR on daily bucket | `daily-min` | suppress when daily cadence not real |
| `:gain-pain-1m` | daily-gated | existing monthly-aggregated GPR on daily bucket | `daily-min` | suppress when daily cadence not real |
| `:smart-sharpe` | daily-gated | existing autocorr-adjusted Sharpe on daily returns | `daily-min` | suppress when daily cadence not real |
| `:smart-sortino` | daily-gated | existing autocorr-adjusted Sortino on daily returns | `daily-min` | suppress when daily cadence not real |
| `:sortino-sqrt2` | derived | `sortino / sqrt(2)` | `:sortino` available | suppress with `:sortino` |
| `:smart-sortino-sqrt2` | derived | `smart-sortino / sqrt(2)` | `:smart-sortino` available | suppress with `:smart-sortino` |
| `:prob-sharpe-ratio` | daily-gated | existing PSR implementation on daily returns | `psr-min` | suppress when gate fails |
| `:daily-var` | daily-gated | existing VaR sign convention (`-abs`) on daily returns | `daily-min` | suppress when daily cadence not real |
| `:expected-shortfall` | daily-gated | existing CVaR sign convention (`-abs`) on daily returns | `daily-min` | suppress when daily cadence not real |
| `:skew` | daily-gated | sample skew over daily returns | `daily-min` | suppress when daily cadence not real |
| `:kurtosis` | daily-gated | sample excess kurtosis over daily returns | `daily-min` | suppress when daily cadence not real |
| `:kelly-criterion` | daily-gated | existing Kelly on daily returns | `daily-min` | suppress when daily cadence not real |
| `:risk-of-ruin` | daily-gated | existing risk-of-ruin on daily returns | `daily-min` | suppress when daily cadence not real |
| `:max-consecutive-wins` | daily-gated | streaks on daily returns | `daily-min` | suppress when daily cadence not real |
| `:max-consecutive-losses` | daily-gated | streaks on daily returns | `daily-min` | suppress when daily cadence not real |
| `:payoff-ratio` | daily-gated | daily returns payoff ratio | `daily-min` | suppress when daily cadence not real |
| `:profit-factor` | daily-gated | daily returns profit factor | `daily-min` | suppress when daily cadence not real |
| `:common-sense-ratio` | daily-gated | `profit-factor * tail-ratio` on daily returns | `daily-min` | suppress when daily cadence not real |
| `:cpc-index` | daily-gated | `profit-factor * win-rate * payoff-ratio` | `daily-min` | suppress when daily cadence not real |
| `:tail-ratio` | daily-gated | daily quantile ratio | `daily-min` | suppress when daily cadence not real |
| `:outlier-win-ratio` | daily-gated | daily q99 / positive mean | `daily-min` | suppress when daily cadence not real |
| `:outlier-loss-ratio` | daily-gated | daily q01 / negative mean | `daily-min` | suppress when daily cadence not real |
| `:r2` | benchmark-gated | correlation^2 on aligned benchmark daily returns | `daily-min` and aligned points >= 10 | suppress when insufficient aligned benchmark data |
| `:information-ratio` | benchmark-gated | mean active return / std active return on aligned daily returns | `daily-min` and aligned points >= 10 | suppress when insufficient aligned benchmark data |

## Rolling Window Boundary Rules

1. Windows are always selected by timestamp, never row count.
2. Window start value is resolved by:
- exact point at boundary if present,
- otherwise log-linear interpolation only when bracketing gap <= `max-window-interp-gap-days`,
- otherwise nearest-snap only when snap distance <= `max-window-snap-distance-days`.
3. If neither condition passes, suppress the window metric and emit reason code.

Default limits:

- `max-window-interp-gap-days = min(2, 3 * median-gap-days)`.
- `max-window-snap-distance-days = 7` for windows <= 1 year, `30` for windows > 1 year.

## Edge-Case Policy

- Duplicate or non-increasing timestamps: keep last point at each timestamp.
- Non-positive cumulative factor or invalid value transitions: truncate series at first invalid point and mark quality warning.
- Zero or negative `dt_i`: drop interval.
- Any NaN/Infinity intermediate: return `nil` for that metric and emit deterministic reason code.
- All-positive downside set: Sortino suppressed (no valid downside denominator).
- Large structural gaps (`max-gap-days > 90`): suppress all risk ratios except cumulative return and observed drawdown fields.

## Function-Level Migration Map

| Existing Function | Change |
|---|---|
| `daily-compounded-returns` | Keep for daily-gated metrics and chart overlays; do not use as the primary path for irregular-core metrics. |
| `normalize-daily-rows` | Keep for daily-gated metrics only. |
| `cagr` | Add irregular path that uses elapsed years and interval factors. |
| `volatility` | Add irregular variance-rate estimator path; keep current daily implementation for daily-gated mode. |
| `sharpe`, `sortino` | Add irregular-core variants (`sharpe-irregular`, `sortino-irregular`) and keep daily variants for daily-gated mode. |
| `smart-sharpe`, `smart-sortino`, `probabilistic-sharpe-ratio`, `omega`, `gain-to-pain-ratio` | Keep existing formulas but gate on daily coverage. |
| `calmar` | Gate behind drawdown reliability diagnostics. |
| `rows-since-ms` and window helpers | Add boundary interpolation/snap checks and suppression reason emission. |
| `compute-performance-metrics` | Split into irregular-core pass + daily-gated pass + policy merge that emits values and statuses. |

## Output Contract Additions

`compute-performance-metrics` keeps existing metric keys and adds:

- `:quality` map with cadence diagnostics.
- `:metric-status` map keyed by metric key.
- `:metric-reason` map keyed by metric key when status is `:suppressed`.

`metric-rows` should attach status/reason so VM and view can keep deterministic fallback rendering and optional caveat tooltip wiring.

## Test Requirements Linked to This Spec

- Metric correctness tests for each irregular-core metric against deterministic fixtures.
- Gate tests for every suppressed metric family.
- Window boundary tests for interpolation allowed/disallowed cases.
- VM tests confirming status/reason propagation and benchmark-column stability.
- View tests confirming `nil` values still render as `"--"` and caveat metadata does not break layout.
