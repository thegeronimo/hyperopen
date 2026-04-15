# Fix Portfolio Benchmark Chart Path Semantics

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The live `bd` issue for this work is `hyperopen-hpzl`, and `bd` remains the lifecycle source of truth until this plan moves out of `active/`.

## Purpose / Big Picture

The trader portfolio page already ends the BTC `1Y` benchmark near the correct value after the anchor fix, but the path through the year is still wrong-looking because the benchmark series is sampled only on surviving portfolio timestamps. After this change, users selecting `Returns` plus a market benchmark like BTC should see a path that looks like the market’s own move through the selected window, while hover and tooltip behavior still lines up cleanly with the portfolio series.

The visible proof is on `/portfolio/trader/0x5b5d51203a0f9079f8aeb098a6523a13f298c060`: with `Returns`, `1Y`, and BTC selected, the benchmark should both end near `-12%` and show a dense BTC-like path rather than a flat or step-like line caused by sparse strategy sampling.

## Progress

- [x] (2026-04-14 19:54 EDT) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/MULTI_AGENT.md`, and `/hyperopen/docs/BROWSER_TESTING.md` before drafting the follow-up plan.
- [x] (2026-04-14 19:55 EDT) Closed the previous anchor-fix issue `hyperopen-fc0a`, moved its completed ExecPlan to `/hyperopen/docs/exec-plans/completed/2026-04-14-correct-portfolio-benchmark-range-trust.md`, and recorded the live visual QA result there so this follow-up can stay narrowly scoped.
- [x] (2026-04-14 19:56 EDT) Created and claimed `bd` issue `hyperopen-hpzl` as a discovered-from follow-up for the remaining dense benchmark chart-path bug.
- [x] (2026-04-14 19:58 EDT) Audited the current implementation seams in `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs`, `/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs`, `/hyperopen/src/hyperopen/views/chart/d3/model.cljs`, and `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs`, plus the existing helper tests that currently ratify strategy-aligned benchmark sampling.
- [x] (2026-04-14 19:59 EDT) Created this active ExecPlan and the matching `/hyperopen/tmp/multi-agent/hyperopen-hpzl/spec.json` artifact with acceptance criteria centered on dense benchmark rows, time-based X normalization, timestamp-based tooltip lookup, and x-ratio-based hover selection.
- [x] (2026-04-14 20:12 EDT) Added failing regressions in `/hyperopen/test/hyperopen/views/portfolio/vm/history_helpers_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_helpers_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm/chart_math_additional_test.cljs`, `/hyperopen/test/hyperopen/views/chart/tooltip_core_test.cljs`, and `/hyperopen/test/hyperopen/views/chart/d3/model_test.cljs` for dense market benchmark rows, time-based chart X normalization, latest-prior tooltip lookup, and hover selection by rendered x-ratio, then confirmed those new expectations failed before implementation.
- [x] (2026-04-14 20:21 EDT) Implemented dense market benchmark rows in `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs` with explicit `anchor-time-ms` and `end-time-ms` handling, while leaving `/hyperopen/src/hyperopen/portfolio/metrics/history.cljs` unchanged.
- [x] (2026-04-14 20:28 EDT) Updated `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs`, `/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs`, `/hyperopen/src/hyperopen/views/chart/d3/model.cljs`, and `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` so benchmark series render on a shared time domain, tooltip benchmark values resolve by latest prior timestamp, and hover selection uses rendered `:x-ratio` instead of point-count buckets.
- [x] (2026-04-14 20:35 EDT) Split the new assembled benchmark chart-path VM regression into `/hyperopen/test/hyperopen/views/portfolio/vm/benchmark_chart_path_test.cljs` to satisfy the namespace-size guard without weakening the existing exception policy.
- [x] (2026-04-14 20:47 EDT) Ran `npm test`, `npm run lint:input-parsing`, `npm run test:websocket`, and `npm run check` successfully. The committed Playwright smoke command remained blocked by an unrelated process already listening on `127.0.0.1:8080`, so route-specific browser QA was completed against the live `8081` portfolio app instead.
- [x] (2026-04-14 20:50 EDT) Verified the live trader route on `http://127.0.0.1:8081/portfolio/trader/0x5b5d51203a0f9079f8aeb098a6523a13f298c060` with `Returns`, `1Y`, and BTC selected. Captured screenshot artifact `/hyperopen/tmp/browser-inspection/manual-btc-path-check/trader-btc-1y-path.png`; the rendered strategy path contained `61` line segments while the BTC benchmark path contained `730`, confirming that the benchmark is now rendered as a dense market path instead of a sparse strategy-sampled step line.

## Surprises & Discoveries

- Observation: the current benchmark end value can be correct while the benchmark path remains visually wrong.
  Evidence: the previous anchor fix changed the benchmark’s economic start close, but `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs` still derives `strategy-time-points` and feeds them into `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs:aligned-benchmark-return-rows`, which emits only one benchmark point per strategy point.

- Observation: the chart layer currently treats point order as the X axis even when points already carry timestamps.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs:normalize-chart-points` sets `:x-ratio` from `idx / (point-count - 1)` rather than from `:time-ms`, so simply returning more benchmark rows without changing chart math would still misplace unequal-length series.

- Observation: tooltip benchmark lookup is index-based rather than time-based.
  Evidence: `/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs:benchmark-point-value` uses `hovered-index` directly into each benchmark series’ `:points`, which only works while every series has the same length and aligned point order.

- Observation: D3 hover selection is bucketed by point count, not by rendered X position.
  Evidence: `/hyperopen/src/hyperopen/views/chart/d3/model.cljs:hover-index` computes the nearest point from `point-count` alone, and `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs:apply-hover!` feeds it only the strategy point count rather than the actual rendered point x positions.

- Observation: one existing VM test now encodes the old benchmark sampling behavior and must be rewritten as part of this fix.
  Evidence: `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs:portfolio-vm-returns-benchmark-series-aligns-to-portfolio-return-timestamps-test` currently asserts that benchmark `:time-ms` exactly matches strategy `:time-ms`.

- Observation: the generic Playwright smoke route command is not reusable in this local environment while another process is already bound to `127.0.0.1:8080`.
  Evidence: `npx playwright test tools/playwright/test/routes.smoke.spec.mjs --grep trader-portfolio` failed fast with `http://127.0.0.1:8080/ is already used`, while route-specific browser verification on the live `8081` app still succeeded.

- Observation: real calendar month spacing produces non-round x ratios even when the benchmark path is correct.
  Evidence: the new assembled VM regression initially expected `0.25 / 0.5 / 0.75` ratios for quarterly timestamps, but the real normalized values are approximately `0.2493 / 0.5014 / 0.7534` because the selected `1Y` window crosses month lengths of 91, 92, 92, and 90 days.

## Decision Log

- Decision: treat this as a new bug in benchmark render semantics rather than reopening either the rebasing-account returns estimator work or the benchmark anchor fix.
  Rationale: the current failure mode is no longer the final return math. The end value is already approximately correct, and the remaining defect is that the benchmark series is still rendered and hovered as if strategy timestamps were the benchmark timeline.
  Date/Author: 2026-04-14 / Codex

- Decision: keep the rebasing-account estimator and strict parsing rules explicitly out of scope unless a new failing test proves they are required.
  Rationale: the user has already asked to preserve those fixes, and the current diagnosis is entirely inside the portfolio VM and chart/tooltip stack.
  Date/Author: 2026-04-14 / Codex

- Decision: fix market benchmark rows and chart X semantics together in one patch.
  Rationale: emitting dense BTC rows without time-based X normalization would still produce misleading visuals because the chart currently spaces points by array index. These two changes are coupled for correctness.
  Date/Author: 2026-04-14 / Codex

- Decision: keep strategy-aligned behavior only where the benchmark is not an independent market candle series, such as vault summary overlays or compatibility fallbacks.
  Rationale: vault benchmarks do not have their own candle stream, but market coin benchmarks do. The implementation should not pay the complexity cost of dense time-based rows where no independent market timeline exists.
  Date/Author: 2026-04-14 / Codex

- Decision: change tooltip and hover semantics to use timestamps and rendered x-ratios instead of shared array index.
  Rationale: once benchmark and strategy series can have different lengths, every index-based assumption in the hover stack becomes a correctness bug even if the chart lines render.
  Date/Author: 2026-04-14 / Codex

- Decision: preserve the generic Playwright smoke failure as an environment note instead of altering `playwright.config.mjs` just to reuse an already-occupied `8080` server.
  Rationale: the benchmark-path fix does not depend on Playwright server boot semantics. Route-specific browser validation on the live `8081` app provides user-visible evidence without relaxing the repo’s existing interactive Playwright configuration.
  Date/Author: 2026-04-14 / Codex

## Outcomes & Retrospective

Implementation is complete and validated locally. Market benchmarks such as BTC now keep their own dense candle timeline inside `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`; the chart VM normalizes visible series against a shared time domain in `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs`; tooltip benchmark rows resolve by latest prior timestamp in `/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs`; and D3 hover selection now chooses the nearest rendered strategy point by `:x-ratio` in `/hyperopen/src/hyperopen/views/chart/d3/model.cljs`.

Overall complexity went down. The previous implementation hid several equal-length assumptions across benchmark sampling, x-position normalization, tooltip lookup, and hover math. The final code replaces those assumptions with one explicit contract: if a visible series has real timestamps, it renders on the shared time axis and participates in hover/tooltip resolution by time instead of by shared array index. The only residual blocker is environmental rather than product behavior: the checked-in interactive Playwright smoke command could not start because another local process already owned `127.0.0.1:8080`.

## Context and Orientation

The portfolio returns estimator fix in `/hyperopen/src/hyperopen/portfolio/metrics/history.cljs` is already correct for the original rebasing-account wipeout bug and is not part of this plan. The current bug lives entirely above that layer in the portfolio view model and generic chart stack.

`/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs` is the portfolio-specific history helper namespace. It already normalizes benchmark candle rows through `benchmark-candle-points` and currently computes market benchmark returns through `aligned-benchmark-return-rows`, which takes normalized candle points plus `strategy-points` and emits one cumulative benchmark row per strategy timestamp. That is the core sampling bug for market benchmarks.

`/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs` is the benchmark orchestration layer for the portfolio page. `benchmark-cumulative-return-rows-by-coin` currently receives `strategy-time-points` and uses them for both market benchmarks and vault benchmarks. `benchmark-computation-context` always derives those strategy timestamps from `portfolio-metrics/returns-history-rows`, so the benchmark timeline inherits every upstream strategy normalization and trimming decision.

`/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs` converts raw strategy and benchmark rows into normalized chart points. Today it assumes that all series can share the same ordinal X spacing because `vm-chart-math/normalize-chart-points` ignores `:time-ms` and spaces points purely by array index.

`/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs` contains the X/Y normalization helpers used by the portfolio chart VM. This is where the new time-domain X semantics must live. The current function `normalize-chart-points` keeps Y normalization correct but sets `:x-ratio` from index order only.

`/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs` builds tooltip rows for the generic chart. It currently assumes the benchmark series can be read at the same `hovered-index` as the hovered strategy point. That assumption becomes wrong as soon as market benchmarks are allowed to keep their own denser candle timeline.

`/hyperopen/src/hyperopen/views/chart/d3/model.cljs` and `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` implement the generic SVG chart runtime. `model/hover-index` currently divides the chart into `point-count` buckets instead of choosing the nearest actual rendered point by `:x-ratio`, and `runtime/apply-hover!` uses that function with the strategy series’ point count. Those two files must change together so a pointer hovering near time `T` selects the rendered strategy point nearest to `T`, not the point nearest to an evenly divided bucket.

Existing tests already cover several adjacent seams. `/hyperopen/test/hyperopen/views/portfolio/vm/history_helpers_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_helpers_test.cljs` cover benchmark helpers. `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs` covers the assembled portfolio VM. `/hyperopen/test/hyperopen/views/portfolio/vm/chart_math_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm/chart_math_additional_test.cljs`, `/hyperopen/test/hyperopen/views/chart/tooltip_core_test.cljs`, `/hyperopen/test/hyperopen/views/chart/d3/model_test.cljs`, and `/hyperopen/test/hyperopen/views/chart/d3/runtime_test.cljs` cover the generic chart stack. One of the current VM tests still asserts the old strategy-aligned benchmark timestamps and must be rewritten.

## Plan of Work

Start by writing the failing regression tests that describe the desired user-visible semantics. In `/hyperopen/test/hyperopen/views/portfolio/vm/history_helpers_test.cljs` or `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_helpers_test.cljs`, add a dense BTC benchmark case with candles `T0=100`, `T1=94`, `T2=90`, `T3=86`, and `T4=88`, while the strategy points exist only at `T3` and `T4`. The benchmark helper under test must return cumulative rows equivalent to `0`, `-6`, `-10`, `-14`, `-12`, proving that dense benchmark render rows can exceed strategy row count and preserve the market path instead of collapsing to two sampled points. Keep the existing late-strategy-start final-value regression from the prior fix so the anchor semantics remain locked.

Add chart-math tests in `/hyperopen/test/hyperopen/views/portfolio/vm/chart_math_test.cljs` or `/hyperopen/test/hyperopen/views/portfolio/vm/chart_math_additional_test.cljs` that pass points at the start, middle, and end of a time window and assert `:x-ratio` values near `0`, `0.5`, and `1` regardless of the number of points in each visible series. Add a tooltip-core regression in `/hyperopen/test/hyperopen/views/chart/tooltip_core_test.cljs` that hovers a strategy point at time `T` and expects the tooltip to read the latest benchmark point at or before `T`, not the point at the same array index. Add a D3 model or runtime regression that proves the hover index is chosen from the nearest rendered point `:x-ratio` rather than from evenly divided `point-count` buckets.

Once the failing tests exist, patch `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`. Keep `benchmark-candle-points` strict and deterministic. Add a dense market-series helper such as `benchmark-market-return-rows` that accepts normalized candle points plus `:anchor-time-ms` and `:end-time-ms`. It must choose the anchor close as the latest candle at or before `anchor-time-ms`, fall back to the first candle only when no prior anchor candle exists, emit a `0%` anchor row, then emit every valid candle-derived cumulative return row up to `end-time-ms`, preserving sorted order, finite values, positive closes, and last-write-wins dedupe semantics.

Then patch `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`. For normal market coin benchmarks, stop requiring `strategy-time-points` as the primary input to `benchmark-cumulative-return-rows-by-coin`. Use the dense candle-derived helper instead and keep strategy-aligned behavior only for vault benchmarks or explicit fallbacks where no market candle timeline exists. Update `benchmark-computation-context` so the benchmark context retains dense market cumulative rows by coin and still carries any selector or source-version metadata needed by the rest of the VM.

After the benchmark rows are dense, patch `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs` together. Introduce a shared time-domain calculation across visible series when the points carry finite `:time-ms` values. Normalize `:x-ratio` from that shared `[min-time-ms, max-time-ms]` window instead of from point index. Preserve the current ordinal/index fallback only for series without valid timestamps so non-time-series charts do not regress.

Then patch the tooltip and D3 hover layers. In `/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs`, stop looking up benchmark tooltip values by `hovered-index`. Instead, when hovering strategy time `T`, find the latest benchmark point at or before `T`. Keep benchmark rows empty for non-return charts exactly as they are now. In `/hyperopen/src/hyperopen/views/chart/d3/model.cljs`, replace the point-count bucket hover math with a nearest-rendered-point helper based on `:x-ratio`. Update `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` to feed the actual rendered strategy points into that helper and to continue positioning the tooltip/hover line from the chosen strategy point.

Finally, update the higher-level VM tests in `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs` so they assert the new market-benchmark behavior instead of encoding the old equal-timestamp contract. The key assembled behavior is that the benchmark series may now have more points than the strategy series while still sharing the same time domain and while the hovered tooltip for the strategy series resolves the benchmark value by time.

## Concrete Steps

From `/Users/barry/.codex/worktrees/8d94/hyperopen`, start by adding the failing tests and running the smallest focused namespaces that exercise the intended seams:

    npm test -- --focus hyperopen.views.portfolio.vm.benchmarks-helpers-test
    npm test -- --focus hyperopen.views.portfolio.vm.chart-math-test
    npm test -- --focus hyperopen.views.chart.tooltip-core-test
    npm test -- --focus hyperopen.views.chart.d3.model-test

If the local test runner does not support `--focus`, run the smallest namespace-specific variant already used in this repository or fall back temporarily to `npm test` after keeping the new test names distinct. The required proof is that the dense BTC path, time-based X normalization, time-based tooltip lookup, and x-ratio hover behavior all fail before the implementation and pass after it.

Then edit these source files:

    /hyperopen/src/hyperopen/views/portfolio/vm/history.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs
    /hyperopen/src/hyperopen/views/chart/tooltip_core.cljs
    /hyperopen/src/hyperopen/views/chart/d3/model.cljs
    /hyperopen/src/hyperopen/views/chart/d3/runtime.cljs

and these tests:

    /hyperopen/test/hyperopen/views/portfolio/vm/history_helpers_test.cljs
    /hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_helpers_test.cljs
    /hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs
    /hyperopen/test/hyperopen/views/portfolio/vm/chart_math_test.cljs
    /hyperopen/test/hyperopen/views/portfolio/vm/chart_math_additional_test.cljs
    /hyperopen/test/hyperopen/views/chart/tooltip_core_test.cljs
    /hyperopen/test/hyperopen/views/chart/d3/model_test.cljs
    /hyperopen/test/hyperopen/views/chart/d3/runtime_test.cljs

After the focused tests pass, run the smallest deterministic browser pass first because this is UI-facing work:

    npx playwright test tools/playwright/test/routes.smoke.spec.mjs --grep trader-portfolio

Then run the required repository gates:

    npm run lint:input-parsing
    npm test
    npm run test:websocket
    npm run check

For manual acceptance, start the local dev portfolio app from the same working tree:

    npm run dev:portfolio

Open:

    http://localhost:8081/portfolio/trader/0x5b5d51203a0f9079f8aeb098a6523a13f298c060

Select `Returns`, `1Y`, and BTC. Confirm two things at once: the right-edge BTC value remains near `-12%`, and the middle of the line now reflects the BTC market path instead of flat segments caused by sparse strategy timestamps. If any Browser MCP or browser-inspection sessions are used during investigation, finish with:

    npm run browser:cleanup

## Validation and Acceptance

Acceptance requires all of the following behavior to be demonstrably true:

- A new failing helper test proves that with candles `100 -> 94 -> 90 -> 86 -> 88` and strategy points only at the last two timestamps, the benchmark render rows preserve the market path as `0`, `-6`, `-10`, `-14`, `-12` rather than collapsing to two sampled points.
- At least one assembled VM test proves the benchmark series can contain more rows than the strategy series for market benchmarks.
- Chart normalization tests prove that `:x-ratio` uses timestamps when finite `:time-ms` values exist, so points at the beginning, middle, and end of the window normalize near `0`, `0.5`, and `1` even when visible series lengths differ.
- Tooltip tests prove that the benchmark value shown while hovering strategy time `T` comes from the latest benchmark point at or before `T`, not the point at the same array index.
- D3 hover tests prove the hovered strategy point is chosen from the nearest rendered `:x-ratio`, not by dividing the chart width into equal point-count buckets.
- The previous anchor regression remains green: a late strategy start does not turn the final bounded `1Y` BTC return from about `-12%` into about `+2.33%`.
- `npx playwright test tools/playwright/test/routes.smoke.spec.mjs --grep trader-portfolio`, `npm run lint:input-parsing`, `npm test`, `npm run test:websocket`, and `npm run check` all pass.
- Manual QA on the failing trader route shows both a correct right-edge BTC value and a visually dense BTC-like path through the selected year.

## Idempotence and Recovery

This plan is safe to execute incrementally because each change can be proven with focused tests before moving outward to the assembled VM and browser validation. If the dense-row implementation destabilizes the chart layer, first revert only the chart/tooltip/D3 changes while keeping the new failing tests. If the chart layer passes but the benchmark final value regresses, revert only the market benchmark row builder and keep the previous anchor regression as the diagnostic guardrail. Do not touch `/hyperopen/src/hyperopen/portfolio/metrics/history.cljs`, do not remove the truthful summary metadata introduced by the prior fix, and do not broaden parsing behavior back toward permissive `parseFloat` coercion.

## Artifacts and Notes

The critical dense-path regression to preserve is:

    Requested range: :one-year
    Anchor candle close: 100
    Intermediate candle closes: 94, 90, 86
    Final candle close: 88
    Strategy timestamps: only the last two timestamps
    Expected benchmark path: 0, -6, -10, -14, -12

The critical chart-stack contract to preserve is:

    If visible series contain finite :time-ms values, use a shared time domain for :x-ratio.
    If a benchmark series is denser than the strategy series, render it on that same time domain.
    When hovering a strategy point at time T, resolve benchmark tooltip values from the latest benchmark point at or before T.

The existing test that currently encodes the wrong behavior and should be rewritten is:

    /hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs
    portfolio-vm-returns-benchmark-series-aligns-to-portfolio-return-timestamps-test

## Interfaces and Dependencies

In `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`, define a dense market benchmark helper with semantics equivalent to:

    (defn benchmark-market-return-rows
      [benchmark-points {:keys [anchor-time-ms end-time-ms]}]
      => [{:time-ms ...
           :value ...}
          ...])

This helper must accept normalized benchmark candle points, choose the anchor close from the latest candle at or before `anchor-time-ms` when possible, fall back to the first candle only when there is no prior anchor candle, emit a `0%` anchor row, and then emit every cumulative return row through `end-time-ms`.

In `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`, update:

    (defn benchmark-cumulative-return-rows-by-coin
      [state summary-time-range benchmark-coins strategy-time-points anchor-time-ms]
      ...)

so market coin benchmarks no longer require `strategy-time-points` for their primary render rows. Vault benchmarks may continue using strategy-aligned summary rows because they do not have an independent candle timeline.

In `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs`, extend `normalize-chart-points` or add a small sibling helper so it can accept a shared time domain and compute `:x-ratio` from time when timestamps exist, while preserving the ordinal fallback for series without timestamps.

In `/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs`, replace the benchmark value lookup contract:

    hovered-index -> same index in benchmark series

with:

    hovered strategy point time-ms -> latest benchmark point at or before that time

In `/hyperopen/src/hyperopen/views/chart/d3/model.cljs`, replace the hover selection contract:

    pointer x -> rounded bucket index from point-count

with:

    pointer x -> nearest rendered point by :x-ratio

Plan revision note (2026-04-14 19:59 EDT): Initial active ExecPlan created after closing the completed anchor-fix issue, auditing the remaining flat benchmark-path symptom, and confirming that the next patch must couple dense benchmark rows with time-based chart/tooltip semantics instead of only changing one layer.

Plan revision note (2026-04-14 20:50 EDT): Updated after implementation, full test coverage, repo gates, and route-specific browser validation. The plan is ready to move to `completed/`; the only validation caveat is the unrelated local `8080` port collision that blocked the generic Playwright smoke command.
