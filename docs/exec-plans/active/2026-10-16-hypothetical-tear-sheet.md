# Hypothetical tear sheet for an optimizer target

This ExecPlan is a living document. Keep `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` up to date as work proceeds.

## Purpose / Big Picture

After an optimizer run that includes vaults, the user wants to see how the target allocation would have performed: a time series and a tear sheet that look like a vault's own performance page, clearly marked as a hypothetical backtest (you cannot deposit into it).

After this change:

1. The Recommendation tab of a solved run that holds at least one vault gains a "Hypothetical tear sheet" button in the scenario header and a compact "Hypothetical performance" card (sparkline, return, max drawdown, Sharpe, volatility, coverage line, in-sample warning) above the existing results. Runs with no vaults are unchanged. Nothing else on the tab changes, and nothing navigates automatically.
2. The button and the card's "Open tear sheet" open `/portfolio/optimize/<scenario-id>/tear-sheet`, a page with a hero (name, HYPOTHETICAL badge, return/volatility/Sharpe/max drawdown), an in-sample warning, a composition + method aside, a Returns/Drawdown chart with range pills (30D, 3M, 6M, 1Y, 2Y, All), benchmark toggles (Equal weight, HLP, BTC), a "vaults with history" strip with join markers, and tabs for Performance metrics (the vault page's own metrics table and engine), Holdings, and Method (rebalancing, before-a-vault-existed rule, leader profit share, per-vault data spacing).

Design reference: canvas "Hypothetical portfolio tear sheet" (https://claude.ai/artifact/UDCrjhnyQfM8YXW8ZxEQ4X), artboards 1–4. The owner confirmed (2026-10-16) that the existing Recommendation view stays as is and only gains the button and card, and only for runs with vaults.

## Context References

Public refs:
- Direct user request in this session (2026-10-16): "Okay, now it's clear to me. I'd like you to proceed with building it out now."

Repo artifacts:
- Run result: `contracts/last-successful-run-path` → `{:result {:instrument-ids [...] :target-weights [...]}}`; scenario detail model `application/view_model/scenario.cljs`, view `views/portfolio/optimize/scenario_detail_view.cljs`.
- Vault histories: `contracts/history-data-path :vault-details-by-address` (normalized vaultDetails, `:portfolio {:day :week :month :all-time}`, `:leader-commission`); cumulative-return rows via `portfolio.metrics.history/returns-history-rows-from-summary`, price rows via `history-loader/normalization`.
- Non-vault legs: the readiness request's `[:history :price-series-by-instrument]`.
- Benchmarks: `:effects/api-fetch-vault-benchmark-details` → `[:vaults :benchmark-details-by-address addr]`; `:effects/fetch-candle-snapshot :coin "BTC" :interval :1d` → `[:candles "BTC" :1d]`.
- Metrics engine/table: `vaults/detail/performance/performance-metrics-model` (sync 3-arity) and `views/vaults/detail/activity/performance_metrics/performance-metrics-card`.
- Chart: `views/vaults/detail/chart/build-chart-model`, `views/chart/d3/runtime`.
- Routes: `portfolio/routes.cljs` (`:optimize-scenario`).

## Plan

- Pure backtest (`portfolio/optimizer/application/tear_sheet/backtest.cljs`): per-instrument price rows → daily UTC grid with log-linear interpolation between samples; portfolio index under rebalance `:daily | :monthly | :hold` and missing rule `:spread | :cash | :common`; optional per-vault leader profit share on gains above a high-water mark; outputs cumulative-percent rows, drawdown rows, live-count rows, join events, per-instrument contribution and data spacing.
- Series sourcing (`tear_sheet/sources.cljs`): stitched vault index (all-time samples, then the dense month window rescaled at the junction), non-vault price rows, HLP and BTC benchmark rows.
- View model (`tear_sheet/model.cljs`): options from UI state, range window, hero stats, chart series, metrics model (vault engine), holdings, coverage, card summary; memoized.
- Actions: `:actions/set-portfolio-optimizer-tear-sheet-option`; route load emits benchmark fetches for the tear-sheet route.
- Views: `views/portfolio/optimize/tear_sheet_view.cljs` (+ parts), `tear_sheet_card.cljs`, header button.
- Route: `/portfolio/optimize/<id>/tear-sheet` parses as `:optimize-scenario` with `:view :tear-sheet` so scenario loading is reused.

## Validation and Acceptance

- `npm run gates` passes (check, `npm test`, `npm run test:websocket`).
- Unit: `tear_sheet_backtest_test.cljs` (grid, interpolation, rebalance/missing rules, profit share, drawdown) and `tear_sheet_model_test.cljs` (vault-run gate, options, stitched vault rows, metrics estimation flags, route, action, benchmark fetches, entry points only for vault runs).
- Playwright: `PLAYWRIGHT_REUSE_EXISTING_SERVER=true npx playwright test tools/playwright/test/optimizer-tear-sheet.spec.mjs` — a vault run shows the card and link, opens the tear sheet, range/tab/method changes recompute, Back returns; a perp-only run shows no entry points.
- Acceptance: on a real vault run, the Recommendation tab is unchanged apart from the button and card, and the tear sheet's metrics match the vault page's engine for the same series.

## Progress

- [x] (2026-10-16) Research: run result shape, vault histories, metrics engine and table, chart runtime, routes, benchmark fetch effects.
- [x] (2026-10-16) Design canvas approved by the owner.
- [x] (2026-10-16) Pure backtest (`tear_sheet/backtest.cljs`) and sources (`tear_sheet/sources.cljs`) with tests (`tear_sheet_backtest_test.cljs`, `tear_sheet_model_test.cljs`).
- [x] (2026-10-16) View model (`tear_sheet/model.cljs`), route (`/portfolio/optimize/<id>/tear-sheet` parsed as `:optimize-scenario` + `:view :tear-sheet`), `:actions/set-portfolio-optimizer-tear-sheet-option` wiring and arg contract, `ui-tear-sheet-path`.
- [x] (2026-10-16) Views: `tear_sheet_view.cljs`, `tear_sheet_parts.cljs`, `tear_sheet_card.cljs`; header link + card on the Recommendation tab for vault runs only; the vault chart's plot area extracted as `chart-view/chart-plot` (vault page unchanged).
- [x] (2026-10-16) Playwright `tools/playwright/test/optimizer-tear-sheet.spec.mjs` (2 tests) green against the worktree dev build; live run on 5 vaults + BTC verified (card, page, ranges, benchmarks incl. live HLP/BTC fetches, method controls, holdings, phone width without horizontal overflow).
- [ ] Owner review.

## Surprises & Discoveries

- The vault metrics engine sees the backtest as a dense daily series, so its own gates never flag interpolation. The model marks every path-dependent metric `:low-confidence` with reason `:interpolated-samples` when any leg's median sample spacing in the window exceeds 1.5 days; the vault metrics card gained titles for that reason.
- HLP's real history has step jumps (e.g. +6.7% between 2026-01-21 and 2026-02-04, +8.7% in early October 2025) between flat stretches, so its benchmark line looks stepped; that is the data, not a bug.
- A per-period high-water fee compounds below the "90% of the gain" a depositor actually keeps; profit share is modelled as `gross - rate * (peak - 1)` per leg instead, matching the charge on withdrawal.

## Decision Log

- Gate the entry points on "the solved result holds at least one vault" so runs of only perps/spot are unchanged (owner, 2026-10-16).
- Defaults: daily rebalance to target, spread missing weight over live vaults, deduct leader profit share, range 1Y.
- Reuse the vault page's metrics engine and table so numbers are computed and labelled exactly like a vault's tear sheet.

## Outcomes & Retrospective

Built as designed. Remaining risk: vault histories older than the month window are sparse (a sample every ~6-15 days), so path metrics over 3M+ are estimates; the page says so in the metrics banner, the hero ("estimated") and the Method & data tab.
