# Fix Portfolio Returns Window-Source Regression

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The live `bd` issue for this work is `hyperopen-ftp5`, and `bd` remains the lifecycle source of truth until this plan moves out of `active/`.

## Purpose / Big Picture

The rebasing-account returns estimator fix is already correct and must remain untouched. The benchmark chart-path fix is also directionally correct: BTC now renders as a dense market path across the selected `1Y` window and ends near the expected `-12%`. The remaining regression is on the strategy side. On the trader portfolio `Returns` chart with `1Y` plus BTC selected, the benchmark spans the full selected window but the portfolio returns series collapses into a narrow tail near the right edge.

The likely cause is upstream of the chart renderer. The chart now uses an honest shared time-based X domain, but `strategy-cumulative-rows` still come from a summary entry that can represent only the late surviving tail of the selected range. The fix is to derive bounded-range portfolio returns from the fullest available portfolio history when possible, then window and anchor that history to the selected or effective cutoff, rather than trusting a direct selected summary entry that may already be truncated.

The visible proof is on `/portfolio/trader/0x5b5d51203a0f9079f8aeb098a6523a13f298c060`: with `Returns`, `1Y`, and BTC selected, BTC should remain dense and end near `-12%`, while the portfolio returns series should also span the available `1Y` history instead of appearing only as a narrow tail at the far right. If earlier portfolio data truly does not exist, the VM should expose that truth instead of visually implying a full-window strategy path.

## Progress

- [x] (2026-04-14 20:35 EDT) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and the `spec-writer` workflow before drafting this follow-up.
- [x] (2026-04-14 20:36 EDT) Created and claimed `bd` issue `hyperopen-ftp5` as a discovered-from follow-up to `hyperopen-hpzl` for the post-benchmark-chart strategy-window regression.
- [x] (2026-04-14 20:40 EDT) Audited `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs`, `/hyperopen/src/hyperopen/portfolio/metrics/history.cljs`, and `/hyperopen/src/hyperopen/portfolio/metrics/normalization.cljs` to confirm the likely seam is upstream strategy windowing rather than the new shared time X domain.
- [x] (2026-04-14 20:42 EDT) Confirmed from code that `benchmark-computation-context` still derives `strategy-cumulative-rows` directly from `portfolio-metrics/returns-history-rows state summary-entry summary-scope`, where `summary-entry` can already be a bounded or fallback slice selected by `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`.
- [x] (2026-04-14 20:45 EDT) Created this active ExecPlan and the matching `/hyperopen/tmp/multi-agent/hyperopen-ftp5/spec.json` artifact, with scope centered on reconstructing bounded strategy returns from fuller history, adding explicit series-span diagnostics, and preserving all prior returns, parsing, benchmark-anchor, dense benchmark, and time-based X fixes.
- [x] (2026-04-14 20:51 EDT) Added failing regressions in `/hyperopen/test/hyperopen/views/portfolio/vm/benchmark_chart_path_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio/vm/summary_helpers_test.cljs` for full-window strategy reconstruction, strategy span metadata, truthful incomplete-window metadata, and millisecond timestamp sanity. Confirmed the failures before runtime changes.
- [x] (2026-04-14 20:55 EDT) Implemented `returns-history-context` in `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`, wired it through `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`, and `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`, and kept the benchmark/path, parsing, and estimator fixes unchanged.
- [x] (2026-04-14 20:58 EDT) Updated helper coverage in `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_helpers_test.cljs` to exercise the new state-backed returns-history source resolution without weakening benchmark behavior.
- [x] (2026-04-14 20:59 EDT) Ran `npm test`, `npm run lint:input-parsing`, `npm run test:websocket`, and `npm run check` successfully.
- [x] (2026-04-14 21:02 EDT) Attempted the smallest relevant Playwright trader-route smoke, confirmed it is still blocked by the unrelated `127.0.0.1:8080` collision, then completed a route-specific browser check against the existing local app bundle on `8080` via debug-bridge navigation. Captured artifact `/hyperopen/tmp/browser-inspection/manual-portfolio-returns-window-check/trader-returns-1y-btc.png`; both the strategy and benchmark SVG paths start at `x=0`, and the portfolio path visibly spans the chart instead of collapsing into a right-edge tail.

## Surprises & Discoveries

- Observation: the shared time-based chart X domain is behaving honestly rather than causing the regression.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs` now computes a shared `time-domain` from visible series and `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs` converts `:time-ms` into `:x-ratio`. A strategy series that contains only late timestamps will therefore render as a narrow tail by design.

- Observation: the strategy series source still comes directly from the selected summary entry, not from the fullest available history for the requested/effective window.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs:benchmark-computation-context` computes `strategy-cumulative-rows` via `portfolio-metrics/returns-history-rows state summary-entry summary-scope`, and `summary-entry` comes from `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs:selected-summary-context`.

- Observation: `selected-summary-context` can truthfully report source metadata, but it does not yet give the caller a richer strategy-history source for bounded returns reconstruction.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs:selected-summary-context` returns `:entry`, `:requested-key`, `:effective-key`, `:source-key`, and `:source`, but the actual `:entry` for a bounded range can still be a truncated direct/fallback summary instead of an all-time-derived reconstruction.

- Observation: the lower metrics layer already knows how to anchor and window cumulative rows relative to a threshold.
  Evidence: `/hyperopen/src/hyperopen/portfolio/metrics/builder.cljs:cumulative-rows-since-ms` includes the last anchor row before the threshold and all rows at or after it. That is close to the semantics needed for bounded strategy returns in the portfolio VM, even though the current VM path never asks for that reconstruction directly.

- Observation: current VM tests prove the dense benchmark fix, but they do not yet expose the raw strategy series span that would catch this regression early.
  Evidence: `/hyperopen/test/hyperopen/views/portfolio/vm/benchmark_chart_path_test.cljs` asserts benchmark timestamps and x-ratios, but it does not assert strategy raw-point count, first/last `:time-ms`, or whether the strategy series should span `T0..T4` when all-time history exists.

- Observation: timestamp-unit drift is still worth guarding explicitly even if it is not the most likely root cause here.
  Evidence: both benchmark and portfolio paths rely on `history-point-time-ms` and parsing/normalization boundaries in `/hyperopen/src/hyperopen/portfolio/metrics/normalization.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`, but no current regression test asserts that both rendered series are in milliseconds on the assembled chart path.

- Observation: the generic interactive Playwright trader-route smoke is still blocked by an unrelated local `127.0.0.1:8080` collision even though the app bundle is available on that port.
  Evidence: `npx playwright test tools/playwright/test/routes.smoke.spec.mjs --grep trader-portfolio` exits immediately with `http://127.0.0.1:8080/ is already used`, while a route-specific script against the same bundle succeeds and produces the expected chart artifact.

## Decision Log

- Decision: treat this as a strategy-source/windowing bug, not as a chart-math rollback candidate.
  Rationale: the chart is correctly rendering whatever timestamps it receives. Reverting to ordinal/index X spacing would only hide the source-window defect and reintroduce the earlier benchmark distortion.
  Date/Author: 2026-04-14 / Codex

- Decision: keep the rebasing-account estimator, stricter parsing/normalization, benchmark anchor fix, dense benchmark rendering, and time-based X semantics explicitly out of scope.
  Rationale: the user has already constrained those fixes to remain intact, and the current symptom is best explained by an upstream strategy-history source mismatch rather than by any of those changes being wrong.
  Date/Author: 2026-04-14 / Codex

- Decision: add diagnostics first that surface strategy-series span metadata in tests before patching the runtime.
  Rationale: the new regression is easy to misread from screenshots alone. Explicit assertions for raw-point count, first/last timestamps, and x-ratio span will tell us whether we actually reconstructed a full-window strategy series or merely stretched a tail subset.
  Date/Author: 2026-04-14 / Codex

- Decision: derive bounded-range portfolio returns from fuller history when available, then anchor and window to the selected/effective cutoff.
  Rationale: the direct selected summary entry is no longer trustworthy enough on its own for `Returns`. When all-time or wider-range account/PnL history exists, the VM should use that richer history to build the bounded strategy series instead of accepting a truncated slice.
  Date/Author: 2026-04-14 / Codex

- Decision: expose truthful VM metadata when earlier portfolio history truly does not exist.
  Rationale: if we cannot reconstruct a full selected window from any available source, the right behavior is to disclose that limitation through metadata rather than making the chart appear complete.
  Date/Author: 2026-04-14 / Codex

- Decision: add explicit timestamp-unit assertions to the new regression fixture.
  Rationale: a 1000x seconds-vs-milliseconds mismatch can produce tail-compressed or misaligned series that look similar to a window-source bug. A direct assertion is cheap and prevents chasing the wrong cause again later.
  Date/Author: 2026-04-14 / Codex

## Outcomes & Retrospective

Implementation is complete and validated locally. The portfolio returns chart now reconstructs bounded strategy returns from fuller available history when `:all-time` can supply an honest selected-window anchor, and it exposes truthful `:strategy-window` metadata when earlier history is unavailable. The benchmark path remains dense and time-based, and the rebasing-account estimator plus strict normalization rules were left untouched.

The key runtime change is that `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs:returns-history-context` now resolves a returns-specific source summary, preferring `:all-time` when it can reconstruct the requested bounded window. `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs:benchmark-computation-context` consumes that richer summary instead of blindly using `(:entry summary-context)`, and `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs` passes the resulting strategy-span metadata through to the VM so incomplete windows are explicit.

The remaining caveat is purely environmental: the checked-in interactive Playwright smoke still cannot start while another local process owns `127.0.0.1:8080`. Route-specific browser verification against the existing local app bundle succeeded, and the captured artifact shows the previously truncated portfolio line now spans the full chart width.

## Context and Orientation

The relevant control path starts in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`. `portfolio-vm` computes `summary-context` via `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs:selected-summary-context`, then passes that into `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs:benchmark-computation-context`. That function currently builds `strategy-cumulative-rows` by calling `portfolio-metrics/returns-history-rows` on `summary-entry`, then hands those rows to the chart and benchmark layers.

`/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs` already has two important behaviors. First, `selected-summary-context` tells the truth about `:requested-key`, `:effective-key`, `:source-key`, and `:source`. Second, `derived-summary-entry` can reconstruct bounded slices from `:all-time` history when the requested bounded entry is missing. The likely problem is that this derivation only happens as a fallback when the direct requested entry is absent. If the direct selected summary exists but only contains a late tail, the returns chart still receives that tail instead of a richer bounded reconstruction from all-time history.

`/hyperopen/src/hyperopen/portfolio/metrics/history.cljs` is still intentionally pure and unchanged in scope. `returns-history-rows` accepts a summary and returns cumulative percentage rows from aligned account/PnL history, beginning at the first positive account value. That estimator should remain untouched. The portfolio VM needs to give it a better summary window for bounded ranges.

`/hyperopen/src/hyperopen/portfolio/metrics/normalization.cljs` already canonicalizes and aligns account and PnL rows, while `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs` exposes smaller VM-side helpers for history row normalization and benchmark candle parsing. Any timestamp-unit normalization guard should live at those parsing or normalization boundaries rather than in chart rendering.

The recent benchmark fix correctly changed the benchmark series to use dense candle timestamps and a shared time-based X domain. The side effect is that a late-only portfolio series no longer gets visually “spread out” by ordinal X spacing. The current chart is therefore revealing a genuine source-window mismatch that was previously hidden.

## Plan of Work

Start by adding failing tests that make the regression measurable in VM terms rather than only in screenshots. In `/hyperopen/test/hyperopen/views/portfolio/vm/benchmark_chart_path_test.cljs` or a sibling VM namespace, add a bounded-range fixture with `T0..T4` all-time account/PnL history, `T0..T4` BTC candles, and a selected range of `:one-year`. The fixture should intentionally include a direct selected summary entry that only covers `T3..T4`, while `:all-time` covers the full `T0..T4`. Assert that the strategy series the chart receives exposes raw-point count, first `:time-ms`, last `:time-ms`, and min/max `:x-ratio`, and that the correct post-fix strategy series spans `T0..T4` rather than only `T3..T4`.

Add a second regression that keeps the dense benchmark path expectations from the previous fix while proving the strategy series may and should also span the full selected/effective window when all-time portfolio history exists. In that same fixture, assert both benchmark and strategy `:time-ms` values are in millisecond scale and that both series normalize near `0` at `T0` and `1` at `T4`. Preserve the prior benchmark anchor regression so the final BTC `1Y` value stays near `-12%`.

Then patch the portfolio VM source-selection path. Introduce a helper in `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`, or a small sibling if needed, that resolves a returns-specific summary source for bounded ranges. The intended behavior is:

- determine the selected/effective range and its cutoff
- prefer fuller history from `:all-time` (or another wider canonical source) when available
- include or synthesize an anchor row at or before the cutoff from the latest valid account/PnL point
- include all valid account/PnL points inside the window
- hand that anchored window to `portfolio-metrics/returns-history-rows`

This reconstruction should happen only for the portfolio returns path. Do not change account-value or PnL chart sources unless a failing test proves they share the same defect. Do not modify the estimator math in `/hyperopen/src/hyperopen/portfolio/metrics/history.cljs`.

After the strategy-source fix, update `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` and any assembled VM metadata so the chart model can distinguish between “full selected-window history reconstructed” and “earlier history unavailable.” If the latter happens, add truthful metadata such as a strategy-span descriptor or source note so the UI can avoid implying a complete `1Y` history when only a shorter series exists.

Finally, keep the current chart/benchmark semantics intact. `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs` should continue using time-based X normalization. `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs` should keep dense benchmark candle rows. The goal is to widen the strategy series source when data exists, not to make the chart less honest.

## Concrete Steps

From `/Users/barry/.codex/worktrees/8d94/hyperopen`, first add the failing test surface in the portfolio VM/chart tests and run the smallest relevant namespaces:

    npm test -- --focus hyperopen.views.portfolio.vm.benchmark-chart-path-test
    npm test -- --focus hyperopen.views.portfolio.vm.benchmarks-helpers-test
    npm test -- --focus hyperopen.views.portfolio.vm.summary-helpers-test
    npm test -- --focus hyperopen.views.portfolio.vm.chart-helpers-test

If the local runner does not support `--focus`, run the smallest namespace-specific commands already used in this repository or fall back temporarily to `npm test` after keeping the new tests grouped and named distinctly.

Then edit these source files:

    /hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm/history.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs
    /hyperopen/src/hyperopen/portfolio/metrics/history.cljs
    /hyperopen/src/hyperopen/portfolio/metrics/normalization.cljs

Only the view-model files are expected to need runtime changes. The metrics files are included in the inspection/test surface because timestamp-unit assertions or small normalization guards may belong there if the new failing tests prove they are necessary.

Update these tests:

    /hyperopen/test/hyperopen/views/portfolio/vm/benchmark_chart_path_test.cljs
    /hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_helpers_test.cljs
    /hyperopen/test/hyperopen/views/portfolio/vm/summary_helpers_test.cljs
    /hyperopen/test/hyperopen/views/portfolio/vm/chart_helpers_test.cljs
    /hyperopen/test/hyperopen/views/portfolio/vm/history_helpers_test.cljs
    /hyperopen/test/hyperopen/views/chart/tooltip_core_test.cljs
    /hyperopen/test/hyperopen/views/chart/d3/model_test.cljs

Only extend tooltip or D3 tests if the new VM fixture proves timestamp-unit or hover semantics need an extra guard; do not broaden scope otherwise.

After the targeted tests pass, run the required gates:

    npm run lint:input-parsing
    npm test
    npm run test:websocket
    npm run check

Then run route-specific browser QA against the failing trader page. Start the local app if needed:

    npm run dev:portfolio

Open:

    http://localhost:8081/portfolio/trader/0x5b5d51203a0f9079f8aeb098a6523a13f298c060

Select `Returns`, `1Y`, and BTC. Confirm three things simultaneously:

- BTC still shows a dense full-year path
- BTC still ends near the correct `1Y` return, roughly `-12%`
- Portfolio returns now span the available `1Y` history instead of appearing only as a narrow right-edge tail

If earlier strategy history truly is unavailable, confirm the VM exposes truthful metadata rather than a visually stretched full-window line. End any browser sessions with:

    npm run browser:cleanup

## Validation and Acceptance

Acceptance requires all of the following:

- A new assembled VM regression fixture proves that for selected `:one-year`, all-time portfolio account/PnL history spanning `T0..T4`, BTC candles spanning `T0..T4`, and a direct selected summary that only covers `T3..T4`, the post-fix strategy series still spans `T0..T4`.
- The regression fixture records strategy raw-point count, first `:time-ms`, last `:time-ms`, and min/max `:x-ratio`, and shows the strategy series normalizes near `0` at `T0` and `1` at `T4`.
- Benchmark render rows remain dense and still span `T0..T4`, with a final `1Y` BTC return near `-12%` instead of the earlier `+2.33%` anchor failure.
- At least one new assertion proves both benchmark and portfolio `:time-ms` values are in milliseconds rather than seconds or microseconds.
- No code change reverts the rebasing-account returns estimator fix, strict parsing/normalization, the benchmark anchor fix, dense benchmark candle rendering, or the shared time-based chart X semantics.
- If earlier portfolio history truly cannot be reconstructed for the selected/effective window, the VM exposes that limitation explicitly instead of visually pretending to have a full-window strategy series.
- `npm run lint:input-parsing`, `npm test`, `npm run test:websocket`, and `npm run check` all pass.
- Manual QA on the failing trader route shows both an honest full-window BTC path and an honest full-window portfolio returns path when the data exists.

## Idempotence and Recovery

This plan is safe to execute incrementally because the most important behavior can be locked first in a deterministic VM fixture. If the new reconstruction path produces an incorrect strategy series, keep the failing span diagnostics and revert only the returns-specific source-selection changes. Do not roll back to ordinal X spacing. Do not make the benchmark sparse again. Do not touch `/hyperopen/src/hyperopen/portfolio/metrics/history.cljs` estimator behavior unless a newly added failing test proves it is required. Do not weaken parsing rules or the truthful summary metadata already in place.

## Artifacts and Notes

The critical new regression fixture should model:

    Selected range: :one-year
    All-time portfolio account/PnL history: T0, T1, T2, T3, T4
    Direct selected summary entry: only T3, T4
    BTC candles: T0, T1, T2, T3, T4
    Expected post-fix strategy span: T0..T4
    Expected benchmark span: T0..T4
    Expected x-ratio behavior: both series near 0 at T0 and 1 at T4

The critical diagnostic surface to add is:

    strategy raw-point count
    strategy first :time-ms
    strategy last :time-ms
    strategy min/max :x-ratio
    benchmark first/last :time-ms
    benchmark min/max :x-ratio

The critical truth contract to preserve is:

    If fuller portfolio history exists for the selected/effective window, derive bounded returns from it.
    If fuller history does not exist, expose that absence truthfully through VM metadata.
    Never hide a tail-only strategy series by stretching it back across the full chart width by index.

## Interfaces and Dependencies

The likely new helper contract is a returns-specific bounded summary resolver, conceptually similar to:

    (defn strategy-returns-summary-context
      [summary-by-key scope summary-context]
      => {:summary ...
          :source ...
          :cutoff-ms ...
          :window-start-ms ...
          :window-end-ms ...
          :complete-window? ...})

It may live in `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs` or `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`, but its behavior must be:

- use the selected/effective range from `summary-context`
- prefer all-time or wider canonical source rows when they provide earlier valid account/PnL data
- synthesize or include the latest valid anchor row at or before the cutoff
- return a summary suitable for `portfolio-metrics/returns-history-rows`

`/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs:benchmark-computation-context` should consume that richer summary for `strategy-cumulative-rows` instead of blindly using `(:entry summary-context)` for bounded returns.

If timestamp-unit normalization needs adjustment, keep it localized to:

    /hyperopen/src/hyperopen/portfolio/metrics/normalization.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm/history.cljs

Plan revision note (2026-04-14 20:45 EDT): Initial active ExecPlan created after the post-benchmark-fix regression report, issue creation for `hyperopen-ftp5`, and a source-level audit that showed the chart is honestly rendering a tail-only strategy series delivered from the current bounded summary path.

Plan revision note (2026-04-14 21:02 EDT): Updated after implementation, full repo gates, and route-specific browser verification. The strategy returns line now uses reconstructed bounded history when available, and incomplete windows are surfaced explicitly through `:chart.strategy-window` metadata.
