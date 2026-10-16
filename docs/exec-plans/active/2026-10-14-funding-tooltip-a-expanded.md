# Funding tooltip redesign ("A-Expanded")

This ExecPlan is a living document. Keep `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` up to date as work proceeds.

## Purpose / Big Picture

The funding-rate tooltip on the trade page (hover the funding rate in the active-asset row) answers "what will funding cost me?" in a form that makes users do the work. The 30-day chart runs newest-to-oldest, the "Predictability" table labels an annual payment band as "Volatility", and an autocorrelation chart over 29 lags is shown to non-quant users.

After this change the tooltip leads with a plain verdict ("a long position pays funding every hour"), shows projections in dollars and as a share of margin, shows funding already paid on a live position, states the price move needed to cover 30 days of funding, splits the rate into the fixed baseline and the premium, and replaces both charts with a chronological area/bar chart (user-toggleable, remembered in localStorage) and a four-lag "Does today predict later?" strip with a noise band.

Design reference: the canvas artifact "Funding Tooltip Redesign", artboard "A-Expanded" (https://claude.ai/artifact/HrcBhaZBVqeTHWy6LHQpdE).

## Context References

Public refs:
- Direct user request in this session (2026-10-14): implement A-Expanded in `funding_tooltip.cljs` with the area/bar toggle, margin %, since-open funding, breakeven line, and baseline/premium breakdown.

Repo artifacts:
- View: `src/hyperopen/views/active_asset/funding_tooltip.cljs`, `src/hyperopen/views/funding_rate_plot.cljs`, `src/hyperopen/views/autocorrelation_plot.cljs`.
- Model: `src/hyperopen/active_asset/funding_policy.cljs` (`funding-tooltip-model`, memoized by an explicit cache key).
- Data: `src/hyperopen/funding/predictability.cljs` (`compute-30d-summary`, daily series oldest-first, ACF lags 1..29).
- Wiring: `src/hyperopen/views/active_asset/vm.cljs` (`active-asset-row-vm`, `panel-dependency-state` memo slice).
- Actions: `src/hyperopen/asset_selector/funding_drafts.cljs` and the action registration chain.

## Progress

- [x] (2026-10-14) Research: tooltip view, model, predictability summary, VM memo slice, hypothetical-position actions, localStorage preference pattern (`chart/settings.cljs`).
- [x] (2026-10-14) Model: verdict, margin/leverage, since-open funding, breakeven, rate breakdown, chart model, lag strip (`active_asset/funding_insights.cljs`, wired in `funding_policy.cljs`).
- [x] (2026-10-14) Actions: `:actions/set-funding-hypothetical-side`, `:actions/set-funding-chart-style` (+ localStorage restore in `chart/settings.cljs`); unsigned typing now keeps the current side.
- [x] (2026-10-14) View: panel rebuilt (`funding_tooltip.cljs`, `funding_tooltip_sections.cljs`, `funding_history_chart.cljs`); old `funding_rate_plot.cljs` / `autocorrelation_plot.cljs` removed.
- [x] (2026-10-14) Tests updated/added; `npm run gates` 34/34 PASS.
- [x] (2026-10-14) Browser QA: funding tooltip Playwright specs (desktop + mobile sheet) pass 3x repeated against the worktree build; screenshots reviewed for live, hypothetical long/short, area and bar views.
- [x] (2026-10-14) Owner testing feedback: tooltip scrollbar hidden (`scrollbar-hide`, desktop popover + mobile sheet); 30-day chart shows a per-day hover readout (date · APY, guide line, dot) via CSS `group-hover` only, so hovering dispatches no actions. Gates 34/34; hover verified in Playwright for area and bar views.
- [x] (2026-10-15) Backtest (25 HL perps, Jun 2025 to Oct 2026, scored out of sample from 2025-12-01): the 30-day average beat today's rate by 37% (7d) and 48% (30d) on every coin; the lag-1 verdict repeated only 39% of the time and 44% of persistent verdicts flipped when one day was dropped. Applied as "A-Revised + scenario toggle": Projections default to Expected (24h at today's rate, 30d and 1y at the 30-day average) with a session-only `:actions/set-funding-projection-basis` toggle to a labelled today's-rate scenario; the year-outlook and "Does today predict later?" sections and the lag-strip derivation were removed.
- [ ] Owner review of the live tooltip; PR.

## Surprises & Discoveries

- Observation: `fundingRate` in the active-asset context is already a percent per hour (test fixture 0.0056 renders +0.1344% for 24h), so the Hyperliquid interest baseline in these units is 0.00125.
- Observation: the "Volatility" row is the standard deviation of a one-year sum of i.i.d. daily rates (daily σ × √365), which is why its dollar band is so much narrower than the visible daily swings.
- Observation: HL `cumFunding.sinceOpen` is positive when the position paid; `positions_vm/funding-display-value` negates it for display.
- Observation: the panel is taller than most viewports; the anchored layout's fixed `max-h-[calc(100vh-1.5rem)]` let it spill past the bottom edge once the top was offset, so the popover now sets `max-height: calc(100vh - top - 12px)`.
- Observation: the mobile sheet renders inside a `whitespace-nowrap` row, so the panel root needs `whitespace-normal` or long sentences overflow.
- Observation: `currentColor` in an SVG gradient stop resolves where the gradient is defined, so the gradient element itself carries the buy-color class.

## Decision Log

- Decision: hypothetical leverage is read from the order form (`:ui-leverage`), not a new tooltip input.
  Rationale: avoids a third new action and matches the leverage the user would actually trade with; live positions use the position's own `:leverage`/`:marginUsed`.
  Date/Author: 2026-10-14, Claude.
- Decision: show the baseline/premium breakdown only for base-dex perps (coin without a `dex:` prefix).
  Rationale: the 0.01%/8h interest baseline is the core-perp default; HIP-3 deployers can configure their own, and we do not fetch it.
  Date/Author: 2026-10-14, Claude.
- Decision: lag strip shows lags 1, 3, 7, 14 days with a ±1.96/√n noise band, replacing the 29-lag ACF chart; lags with fewer than 2 pairs are omitted.
  Rationale: large lags on 30 points compare only a handful of pairs and render as spurious near-±1 bars.
  Date/Author: 2026-10-14, Claude.
- Decision (supersedes the lag strip): remove the predictability indicator and project 30 days and a year at the 30-day average by default, with today's rate as an opt-in scenario.
  Rationale: the out-of-sample backtest showed the verdict does not replicate and its advice ("today's rate is a fair guide") was wrong even in "Strongly" months; the 30-day average roughly halves 30-day projection error.
  Date/Author: 2026-10-15, Claude (owner chose the scenario toggle with the evidence line).
- Decision: the chart-style preference is a single localStorage key restored alongside chart options.
  Rationale: tiny, low-frequency UI preference per `docs/BROWSER_STORAGE.md`.
  Date/Author: 2026-10-14, Claude.

## Validation and Acceptance

- Hovering the funding rate shows: header with hourly rate and countdown; position block (live: size/value/margin; hypothetical: side toggle + value/size inputs); verdict banner with margin-per-day; "So far" row for live positions; projections table with "Of margin" column; breakeven sentence; "Why this rate" for base-dex perps; 30-day chart oldest→Today with average line and area/bar toggle; 1-year band; lag strip.
- Toggling chart style persists across reload.
- `npm run gates` passes.

## Outcomes & Retrospective

Implemented as designed. Remaining: owner sign-off on the live tooltip, then commit. Cross-venue rates (predictedFundings) were deliberately left out as a follow-up because they need a new API fetch.
