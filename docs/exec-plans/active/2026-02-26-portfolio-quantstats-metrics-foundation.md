# Portfolio QuantStats Metrics Foundation for the Portfolio Page

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, `/portfolio` will show a deterministic performance-metrics block that matches the QuantStats metric families shown in the design reference (time in market, return/risk ratios, drawdown diagnostics, tail/trade-quality metrics, and rolling period returns), without waiting for the full tear-sheet project.

A contributor can verify the behavior by opening `/portfolio` and seeing each metric row render either a stable numeric value or an explicit fallback (`"--"`) when data is insufficient. The contributor can then run the required gates and the new unit tests that pin formula parity with QuantStats definitions.

## Progress

- [x] (2026-02-26 16:48Z) Reviewed `/hyperopen/.agents/PLANS.md` and planning requirements in `/hyperopen/docs/PLANS.md`.
- [x] (2026-02-26 16:50Z) Audited current portfolio implementation in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`, `/hyperopen/src/hyperopen/views/portfolio_view.cljs`, and portfolio tests under `/hyperopen/test/hyperopen/views/portfolio/`.
- [x] (2026-02-26 16:53Z) Pulled and inspected QuantStats primary sources (`quantstats/stats.py`, `quantstats/reports.py`, `quantstats/utils.py`) to map formulas and report wiring.
- [x] (2026-02-26 16:55Z) Authored this ExecPlan with file-level edits, metric scope mapping, and validation gates.
- [x] (2026-02-26 17:15Z) Implemented Milestone 1 extraction in `/hyperopen/src/hyperopen/portfolio/metrics.cljs` (flow-adjusted cumulative returns + cumulative->interval + daily compounding adapters) and rewired portfolio chart returns source in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`.
- [x] (2026-02-26 17:15Z) Added regression and extraction tests in `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`.
- [x] (2026-02-26 17:15Z) Ran required gates after Milestone 1 changes: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-26 20:05Z) Implemented Milestone 2 QuantStats-style formula module in `/hyperopen/src/hyperopen/portfolio/metrics.cljs` (ratios, drawdown diagnostics, distribution/trade metrics, benchmark-relative metrics, and period window rollups).
- [x] (2026-02-26 20:12Z) Expanded `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs` with deterministic QuantStats-parity fixtures and expected-value assertions covering the new formula families.
- [x] (2026-02-26 20:20Z) Re-ran required gates after Milestone 2 changes: `npm test`, `npm run test:websocket`, `npm run check`.
- [x] (2026-02-26 21:02Z) Implemented Milestone 3 VM integration in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` by adding `:performance-metrics` payload wiring (grouped metric rows + benchmark metadata) sourced from `compute-performance-metrics`.
- [x] (2026-02-26 21:02Z) Added Milestone 3 VM wiring regression coverage in `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs` for benchmark-off and benchmark-on metric behavior.
- [x] (2026-02-26 21:05Z) Re-ran required gates after Milestone 3 changes: `npm test`, `npm run test:websocket`, `npm run check`.
- [ ] Render grouped metric rows in portfolio view.
- [x] Extend parity-focused tests for full QuantStats metric coverage.

## Surprises & Discoveries

- Observation: `/portfolio` already computes flow-adjusted cumulative returns in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` via a Modified-Dietz-style interval flow adjustment (`interval-flow-stats` + `returns-history-rows`), which is a strong base for QuantStats-style return inputs.
  Evidence: Existing tests `portfolio-vm-returns-tab-uses-flow-adjusted-time-weighted-returns-to-avoid-cashflow-spikes-test` and `portfolio-vm-returns-tab-treats-account-class-transfer-as-flow-for-perps-scope-test` in `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`.

- Observation: QuantStats computes most of the requested rows in one place (`reports.metrics`) and then fills drawdown dates/period lengths from a separate drawdown-details pass.
  Evidence: `/tmp/quantstats_reports.py` lines around 1300-1565 and `_calc_dd` around 2185-2330.

- Observation: Some requested metrics are benchmark-dependent (`R^2`, `Information Ratio`) while current Hyperopen benchmark selection is optional and tied to the returns chart selector.
  Evidence: Benchmark selector model and chart benchmark series in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`.

- Observation: QuantStats assumes a regular return frequency (default `252` periods/year) and applies annualization from that constant. Hyperopen history points are timestamped and can be irregular by range, so annualization must be explicit and deterministic in our adapter layer.
  Evidence: QuantStats `sharpe`, `sortino`, `cagr`, and `volatility` annualization formulas in `/tmp/quantstats_stats.py`.

- Observation: Extracting the flow-adjusted return logic without behavior drift required keeping the same row-shape contract (`[timestamp-ms cumulative-percent]`) so existing chart and benchmark alignment code remained unchanged.
  Evidence: Existing return-chart tests in `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs` still pass, and new extraction tests in `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs` validate raw cumulative outputs.

- Observation: QuantStats drawdown-period end dates intentionally report the last negative drawdown day, not the first recovered-zero day, due a shifted end-index pass in `drawdown_details`.
  Evidence: `/tmp/quantstats/quantstats/stats.py` (`drawdown_details`) and parity failures corrected by aligning end-date logic in `/hyperopen/src/hyperopen/portfolio/metrics.cljs`.

- Observation: A direct Clojure threading translation of Sortino downside variance can invert numerator/denominator if not written explicitly.
  Evidence: Initial parity test failures in `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs` corrected by explicit `downside-sum / n` implementation in `/hyperopen/src/hyperopen/portfolio/metrics.cljs`.

- Observation: Multi-benchmark chart selection requires a deterministic primary benchmark for single-benchmark metrics (`R^2`, `Information Ratio`), otherwise metric identity is ambiguous.
  Evidence: Milestone 3 VM now reads the first selected benchmark coin for metric computation and exposes that choice in `:performance-metrics`.

## Decision Log

- Decision: Implement a new pure namespace `/hyperopen/src/hyperopen/portfolio/metrics.cljs` as the single owner of portfolio performance formulas, and keep view-layer code in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` focused on state selection/presentation.
  Rationale: Preserves deterministic runtime boundaries and keeps side effects out of metric calculation.
  Date/Author: 2026-02-26 / Codex

- Decision: Build a canonical daily strategy return series by compounding intraday interval returns into UTC day buckets after flow adjustment, then use that daily series for all QuantStats-style metrics.
  Rationale: Aligns with QuantStats’ daily return expectations while preserving Hyperopen cash-flow adjustments.
  Date/Author: 2026-02-26 / Codex

- Decision: Mark benchmark-dependent metrics as unavailable (`nil` -> `"--"` in view) when no benchmark is selected, instead of injecting a default benchmark.
  Rationale: Avoids silent assumptions and keeps user-visible semantics explicit.
  Date/Author: 2026-02-26 / Codex

- Decision: Keep existing metric labels visually aligned with the screenshot taxonomy even if internal map keys are normalized keywords.
  Rationale: User-facing parity is a product requirement, while internal naming should remain idiomatic ClojureScript.
  Date/Author: 2026-02-26 / Codex

- Decision: Use compatibility-first extraction for Milestone 1: move return math into `hyperopen.portfolio.metrics` and make VM delegate to it, but keep downstream chart consumers on the existing cumulative-percent row format until full metrics integration is complete.
  Rationale: Minimizes regression risk while establishing a single source of truth for future metric expansion.
  Date/Author: 2026-02-26 / Codex

- Decision: Use deterministic expected-value fixtures generated from QuantStats itself as parity anchors in ClojureScript tests.
  Rationale: Prevents silent formula drift while keeping tests independent of Python at runtime.
  Date/Author: 2026-02-26 / Codex

- Decision: Expose both grouped rows and raw metric values in VM under `:performance-metrics` to decouple UI rendering concerns from metric computation.
  Rationale: Enables Milestone 4 view work to focus on formatting/accessibility while preserving a stable internal data contract.
  Date/Author: 2026-02-26 / Codex

## Outcomes & Retrospective

Milestones 1 through 3 are now implemented. The flow-adjusted return adapter and QuantStats-style formula engine live in `/hyperopen/src/hyperopen/portfolio/metrics.cljs`, and VM now emits grouped performance-metric payloads in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`.

Validation status after this slice:

- `npm test` passed (`Ran 1423 tests containing 7045 assertions. 0 failures, 0 errors.`).
- `npm run test:websocket` passed (`Ran 153 tests containing 696 assertions. 0 failures, 0 errors.`).
- `npm run check` passed (docs/hiccup lint + app/test compile).

Remaining work is Milestone 4+: render the grouped `:performance-metrics` payload in `/portfolio` view and add view-level rendering tests.

## Context and Orientation

Today’s portfolio page already has a summary card, a returns chart, and account tables:

- VM: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
- View: `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
- Portfolio actions and selectors: `/hyperopen/src/hyperopen/portfolio/actions.cljs`
- Existing tests: `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`

The new work is metric-centric and should not introduce websocket or API side effects. Metrics are derived from existing state inputs (`:portfolio`, `:portfolio-ui`, `:orders`, `:candles`, `:wallet`, and account projections already used in VM).

QuantStats source of truth for requested formulas:

- Metrics table orchestration: [reports.py](https://github.com/ranaroussi/quantstats/blob/main/quantstats/reports.py)
- Formula implementations: [stats.py](https://github.com/ranaroussi/quantstats/blob/main/quantstats/stats.py)
- Return preparation and aggregation helpers: [utils.py](https://github.com/ranaroussi/quantstats/blob/main/quantstats/utils.py)

Metric groups to implement now (matching requested screenshot rows):

- Exposure/returns: Time in Market, Cumulative Return, CAGR.
- Risk-adjusted ratios: Sharpe, Probabilistic Sharpe, Smart Sharpe, Sortino, Smart Sortino, Sortino/√2, Smart Sortino/√2, Omega.
- Drawdown diagnostics: Max Drawdown, Max DD Date, Max DD Period Start, Max DD Period End, Longest DD Days.
- Distribution/risk: Volatility (ann.), R^2, Information Ratio, Calmar, Skew, Kurtosis, Expected Daily/Monthly/Yearly, Kelly Criterion, Risk of Ruin, Daily VaR, Expected Shortfall (CVaR).
- Trade/shape metrics: Max Consecutive Wins/Losses, Gain/Pain Ratio, Gain/Pain (1M), Payoff Ratio, Profit Factor, Common Sense Ratio, CPC Index, Tail Ratio, Outlier Win Ratio, Outlier Loss Ratio.
- Calendar windows: MTD, 3M, 6M, YTD, 1Y, 3Y (ann.), 5Y (ann.), 10Y (ann.), All-time (ann.).

## Plan of Work

### Milestone 1: Canonical Return Series Adapter

Add pure return-series helpers in `/hyperopen/src/hyperopen/portfolio/metrics.cljs` to produce normalized daily strategy returns and optional daily benchmark returns from current state-derived series.

The adapter sequence is:

1. Extract and reuse the existing flow-adjusted return pipeline from `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` (`ledger-flow-events`, `interval-flow-stats`, `returns-history-rows`) into `/hyperopen/src/hyperopen/portfolio/metrics.cljs` with behavior-preserving tests.
2. Keep the chart path in VM wired to the extracted function so there is one source of truth for flow-adjusted cumulative returns.
3. Convert cumulative return points into interval returns (`r_t = (1 + R_t) / (1 + R_{t-1}) - 1`).
4. Bucket interval returns by UTC date of interval end and compound within each day.
5. Build aligned daily strategy and benchmark vectors for metrics that require both series.

Deliverable: one deterministic data shape that every metric function consumes, with no direct state mutation and no side effects.

### Milestone 2: QuantStats-Style Formula Module

In the same namespace (`/hyperopen/src/hyperopen/portfolio/metrics.cljs`), implement pure functions for each requested metric, explicitly matching QuantStats semantics where possible:

- `comp`, `cagr`, `volatility`, `sharpe`, `smart-sharpe`, `sortino`, `smart-sortino`, `probabilistic-sharpe-ratio`, `omega`.
- Drawdown helpers equivalent to `to_drawdown_series` and `drawdown_details` to support max drawdown date/start/end/longest days.
- `r-squared`, `information-ratio`, `calmar`, `skew`, `kurtosis`, `expected-return` (daily/monthly/yearly), `kelly-criterion`, `risk-of-ruin`, `value-at-risk`, `cvar`.
- `consecutive-wins`, `consecutive-losses`, `gain-to-pain-ratio` (daily + monthly), `payoff-ratio`, `profit-factor`, `common-sense-ratio`, `cpc-index`, `tail-ratio`, `outlier-win-ratio`, `outlier-loss-ratio`.
- Calendar return windows and annualized rolling windows (MTD/3M/6M/YTD/1Y/3Y/5Y/10Y/All-time).

Every function must return `nil` when inputs are insufficient or denominator/threshold constraints fail, so presentation can render deterministic placeholders.

### Milestone 3: VM Integration and Presentation Model

Update `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` to call the new metric engine once per VM build and expose a grouped `:performance-metrics` payload with stable ordering and labels.

Keep existing summary and chart behavior intact. Do not introduce additional fetches, subscriptions, or action sequencing changes. Any benchmark-dependent rows should read from the currently selected benchmark coin and return unavailable when not selected.

### Milestone 4: Portfolio UI Rendering

Update `/hyperopen/src/hyperopen/views/portfolio_view.cljs` to render a dedicated performance-metrics card (or section) that follows the screenshot’s row ordering and section breaks.

Render rules:

- Number formatting reuses existing formatting helpers where possible.
- All unavailable values display `"--"`.
- Percent rows show explicit sign where appropriate.
- Date rows use deterministic `YYYY-MM-DD` format.
- Rows stay keyboard/screen-reader friendly and preserve current UI responsiveness.

### Milestone 5: Parity and Regression Tests

Add a new test module `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs` with deterministic fixtures that verify formula parity for each metric family.

Update VM/view tests:

- `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`

Tests must cover:

- happy-path numeric outputs,
- missing-data fallback behavior,
- benchmark-on vs benchmark-off behavior,
- drawdown period extraction and date fields,
- period-window rollups (MTD/3M/6M/YTD/1Y/3Y/5Y/10Y/all-time).

## Concrete Steps

From `/hyperopen`:

1. Implement metric engine namespace and helpers.

   Edit/create:

   - `/hyperopen/src/hyperopen/portfolio/metrics.cljs`

2. Integrate metrics into portfolio VM and add stable presentation keys.

   Edit:

   - `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`

3. Render metric section in portfolio view with deterministic row order.

   Edit:

   - `/hyperopen/src/hyperopen/views/portfolio_view.cljs`

4. Add/extend tests for formula parity and rendering.

   Edit/create:

   - `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs`
   - `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
   - `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`

5. Run required gates.

       npm run check
       npm test
       npm run test:websocket

## Validation and Acceptance

Acceptance is complete when all conditions are true:

1. `/portfolio` renders the requested QuantStats-style metric rows with deterministic ordering and section grouping.
2. Each metric row either shows a valid computed value or `"--"` fallback; no runtime errors on sparse data.
3. Benchmark-dependent metrics (`R^2`, `Information Ratio`) compute only when benchmark series is available and aligned.
4. Drawdown rows include max drawdown value, valley date, drawdown period start/end, and longest drawdown days.
5. Calendar window rows (MTD through All-time annualized) compute from the canonical daily return adapter.
6. New formula tests and updated VM/view tests pass.
7. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

The work is additive and can be applied incrementally. If any formula migration causes regressions, keep the new metric engine namespace but temporarily gate UI rows behind a feature flag map in VM while fixing individual formulas. Because this plan introduces pure functions first, rollback is limited to VM wiring and does not require state schema migration.

If parity mismatches are found, prioritize preserving deterministic behavior and explicit `nil` fallbacks over forcing approximate values.

## Artifacts and Notes

Primary implementation files expected to change:

- `/hyperopen/src/hyperopen/portfolio/metrics.cljs` (new)
- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
- `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
- `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs` (new)
- `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`

QuantStats parity anchors used for this plan:

- [quantstats/reports.py](https://github.com/ranaroussi/quantstats/blob/main/quantstats/reports.py)
- [quantstats/stats.py](https://github.com/ranaroussi/quantstats/blob/main/quantstats/stats.py)
- [quantstats/utils.py](https://github.com/ranaroussi/quantstats/blob/main/quantstats/utils.py)

## Interfaces and Dependencies

No runtime dependency on Python or QuantStats will be added.

Planned internal interface in `/hyperopen/src/hyperopen/portfolio/metrics.cljs`:

- `build-daily-series` that returns canonical strategy/benchmark daily return vectors and aligned dates.
- `compute-performance-metrics` that returns a map of all requested rows keyed by stable keywords.
- `metric-rows` helper that maps computed metrics into ordered label/value row data for VM consumption.

All functions must remain pure and deterministic. Side effects (fetch, websocket, action dispatch) remain outside this module.

Plan revision note: 2026-02-26 16:55Z - Initial ExecPlan created to implement QuantStats-style portfolio metrics in Hyperopen with formula parity, deterministic fallbacks, and required gate coverage.
Plan revision note: 2026-02-26 17:01Z - Clarified Milestone 1 to explicitly extract and reuse the existing flow-adjusted returns implementation from `portfolio/vm.cljs` (no algorithm rewrite) as requested in follow-up review.
Plan revision note: 2026-02-26 17:15Z - Updated living sections after Milestone 1 implementation (new `portfolio/metrics` extraction, VM delegation, new tests, and gate results).
