# Fix Vault Detail Returns Chart Path

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The live `bd` issue for this work is `hyperopen-7dbd`, and `bd` remains the lifecycle source of truth until this plan moves out of `active/`.

## Purpose / Big Picture

The portfolio page fixes should remain intact. This follow-up is only for the vault detail page. On `/vaults/0xdfc24b077bc1425ad1dea75bcb6f8158e10df303` with `Returns`, `1Y`, and BTC selected, BTC should continue to render as a dense one-year market path that ends near the correct final return, and the white `Vault` series should render over the actual available vault history instead of appearing sparse, choppy, or misleading.

Today the vault detail route still uses a legacy path that picks bounded summary slices too crudely, rebases from the first surviving point instead of the selected window cutoff, spaces points across the chart by array index instead of timestamp, and still samples market benchmarks on vault timestamps. After this change, the vault detail route should follow the same architectural principles the portfolio page now uses: reconstruct bounded returns from the richest honest history source when possible, anchor the selected window correctly, render market benchmarks from their own candle timestamps, and place all visible series on a shared time-based X axis. If earlier vault data truly does not exist, the VM must expose that limitation instead of visually pretending the vault has a full `1Y` path.

## Progress

- [x] (2026-04-14 21:31 EDT) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/MULTI_AGENT.md`, and the repo-local `spec-writer` skill before drafting this plan.
- [x] (2026-04-14 21:32 EDT) Filed `bd` bug `hyperopen-7dbd`, claimed it, and linked it as a follow-up discovered from `hyperopen-ftp5`.
- [x] (2026-04-14 21:32 EDT) Closed `hyperopen-ftp5` as completed and moved `/hyperopen/docs/exec-plans/active/2026-04-14-fix-portfolio-returns-window-source-regression.md` to `completed/` so `active/` reflects only the current work.
- [x] (2026-04-14 21:39 EDT) Audited the vault detail stack in `/hyperopen/src/hyperopen/vaults/detail/performance.cljs`, `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs`, `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`, `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`, `/hyperopen/src/hyperopen/views/vaults/detail/chart_tooltip.cljs`, and the existing vault tests.
- [x] (2026-04-14 21:44 EDT) Confirmed from code that the vault detail route still uses legacy summary derivation, index-based X coordinates, and strategy-aligned benchmark sampling rather than the portfolio page’s corrected architecture.
- [x] (2026-04-14 21:49 EDT) Created this active ExecPlan and the matching `/hyperopen/tmp/multi-agent/hyperopen-7dbd/spec.json` manager artifact.
- [x] (2026-04-14 22:02 EDT) Added failing regressions across vault performance, benchmarks, chart model, assembled VM, and tooltip coverage so the sparse/choppy vault path was reproducible without manual screenshots.
- [x] (2026-04-14 22:11 EDT) Implemented a vault returns window context that prefers richer all-time-derived bounded windows, synthesizes a cutoff anchor when honest prior account/PnL points exist, and exposes truthful coverage metadata.
- [x] (2026-04-14 22:15 EDT) Ported vault market benchmark rendering to dense candle timestamps over the effective window while keeping vault-as-benchmark behavior scoped and honest.
- [x] (2026-04-14 22:18 EDT) Moved vault detail chart X placement to shared time-based normalization with ordinal fallback only for series that genuinely lack finite timestamps.
- [x] (2026-04-14 22:28 EDT) Ran repo-required validation and completed direct browser QA on the failing vault route against `localhost:8081`, including a screenshot artifact and rendered SVG span check.

## Surprises & Discoveries

- Observation: the vault detail page is not using the newer portfolio VM stack. It still has a separate legacy implementation for bounded summary selection, benchmark shaping, and chart normalization.
  Evidence: `/hyperopen/src/hyperopen/vaults/detail/performance.cljs`, `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs`, and `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs` each duplicate logic that already evolved on the portfolio page.

- Observation: vault bounded summary derivation is too crude for returns reconstruction.
  Evidence: `/hyperopen/src/hyperopen/vaults/detail/performance.cljs:135` defines `derived-portfolio-summary` by filtering all-time rows to `>= cutoff` and rebasing PNL from the first surviving row. It never inserts a cutoff anchor from the latest valid prior account/PnL point.

- Observation: direct bounded vault summaries win even when they may be sparse tail slices.
  Evidence: `/hyperopen/src/hyperopen/vaults/detail/performance.cljs:152` and `:164` choose `get portfolio snapshot-range` before any richer all-time-derived bounded reconstruction strategy.

- Observation: vault chart X placement is still ordinal rather than time-based.
  Evidence: `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs:84` computes `:x-ratio` from `idx / (point-count - 1)`, which stretches sparse points across the full chart width and hides when time gaps are large.

- Observation: vault market benchmarks are still sampled only on strategy timestamps.
  Evidence: `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs:456` and `:511` define `aligned-benchmark-return-rows` and `aligned-summary-return-rows`, and `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs:541` uses them inside `benchmark-cumulative-return-points-by-coin`.

- Observation: the shared tooltip and D3 hover layers already know how to use timestamp-aware behavior once the series carry honest `:time-ms` and `:x-ratio`.
  Evidence: `/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs` already has latest-prior benchmark lookup by hover time, and `/hyperopen/src/hyperopen/views/chart/d3/model.cljs` already chooses hover index by nearest rendered `:x-ratio`.

- Observation: the minimal vault fix path is to reuse the portfolio page’s corrected helper seams rather than duplicating yet another vault-specific implementation.
  Evidence: reusing `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`, and `/hyperopen/src/hyperopen/views/portfolio/vm/chart-math.cljs` resolved the vault regression with only two narrow namespace-boundary exceptions.

- Observation: the live vault route on `localhost:8081` is client-routed only for manual QA.
  Evidence: a direct `curl` to `/vaults/<address>` returned `404`, while `/index.html` plus `:actions/navigate` through `HYPEROPEN_DEBUG` loaded the route correctly for browser verification.

## Decision Log

- Decision: keep the already-fixed portfolio estimator, parsing, benchmark anchor, dense benchmark path, and portfolio time-based X behavior out of scope.
  Rationale: the vault issue is a separate legacy path. Reopening the portfolio fixes would only increase risk without addressing the vault-specific seam.
  Date/Author: 2026-04-14 / Codex

- Decision: fix the vault route by porting the portfolio page architecture, not by visually smoothing the existing sparse series.
  Rationale: the right outcome is an honest time-based chart built from richer bounded history when available, not an index-based rendering trick that makes sparse data look denser than it is.
  Date/Author: 2026-04-14 / Codex

- Decision: add failing tests first that expose both window-source truth and time-domain truth.
  Rationale: this regression has two coupled symptoms. One is upstream summary/window selection. The other is downstream X placement. The plan must lock both so the fix does not accidentally repair only the screenshot while keeping the wrong semantics.
  Date/Author: 2026-04-14 / Codex

- Decision: prefer reusing the shared corrected helpers from the portfolio path where they already capture the intended semantics.
  Rationale: `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/chart-math.cljs`, and `/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs` already implement the recent benchmark/time/tooltip corrections. Reuse reduces divergence and lowers long-term maintenance cost.
  Date/Author: 2026-04-14 / Codex

- Decision: do not fabricate a full-year vault line when earlier data truly does not exist.
  Rationale: the chart must remain honest. If there is no account/PnL point at or before the selected cutoff, the VM should expose incomplete coverage metadata instead of inventing a synthetic earlier history.
  Date/Author: 2026-04-14 / Codex

## Outcomes & Retrospective

Implementation completed. The vault detail route now reconstructs bounded returns from richer all-time history when honest prior aligned points exist, exposes truthful window and coverage metadata, renders the chart on a shared time-based X domain, and renders market benchmarks from dense candle timestamps over the effective window. The fix stays localized to the vault detail stack and does not modify the already-correct portfolio estimator or portfolio benchmark semantics.

The main tradeoff is temporary reuse of three helpers from the portfolio VM namespace. That reuse is currently governed by narrow entries in `/hyperopen/dev/namespace_boundary_exceptions.edn` and should be retired later by extracting the shared windowing and benchmark helpers out of `views/portfolio/vm/**` into a neutral seam. That follow-up is not required for correctness now.

Validation passed:

- targeted test compile and namespace run for the new vault regressions
- `npm run lint:input-parsing`
- `npm test`
- `npm run test:websocket`
- `npm run check`
- `npm run lint:namespace-sizes`
- `npm run lint:namespace-boundaries`

Manual QA also passed on `http://localhost:8081/vaults/0xdfc24b077bc1425ad1dea75bcb6f8158e10df303` using a direct Playwright inspection against `/index.html` plus client-side navigation. The browser artifact is `/hyperopen/tmp/browser-inspection/manual-vault-detail-returns-check/vault-returns-1y-btc.png`. The rendered SVG evidence on that route showed:

- vault series path `minX = 0`, `maxX = 1077`, `segmentCount = 28`
- BTC benchmark path `minX = 0`, `maxX = 1082.519`, `segmentCount = 720`

That confirms the white vault series now spans the plot honestly instead of collapsing into a right-edge tail, while BTC remains a dense market path.

## Context and Orientation

The vault detail page is assembled in `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`. The function `build-vault-detail-chart-section` chooses the selected chart series, builds `strategy-return-points`, asks `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs` for benchmark points, and passes both into `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`.

Vault summary selection and returns shaping live in `/hyperopen/src/hyperopen/vaults/detail/performance.cljs`. A “summary” in this repository is a map that can contain `:accountValueHistory` and `:pnlHistory`. Each history is a time series of rows that ultimately feed `portfolio-metrics/returns-history-rows`. For vaults, `portfolio-summary` and `portfolio-summary-by-range` currently prefer a direct bounded entry such as `:one-year`, and only fall back to `derived-portfolio-summary` if that direct entry is missing. `derived-portfolio-summary` simply filters all-time rows down to `>= cutoff` and rebases PNL from the first surviving row. That means the selected bounded summary can start late and still “win”, leaving `returns-history-rows` with only a tail slice.

Vault market benchmark shaping lives in `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs`. For market coins like BTC, that file still parses candles locally and then runs `aligned-benchmark-return-rows`, which samples the benchmark only on the vault strategy timestamps. For vault benchmarks, it runs `aligned-summary-return-rows`, which also samples onto strategy timestamps. This is the same family of bug the portfolio page had before the dense benchmark path fix.

Vault chart normalization lives in `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`. That file currently computes `:x-ratio` from point index. By contrast, the corrected portfolio path in `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/chart-math.cljs` computes a shared time domain across visible series and normalizes `:x-ratio` from `:time-ms`. The vault route should follow that behavior.

Shared hover and tooltip behavior is already centralized in `/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs` and `/hyperopen/src/hyperopen/views/chart/d3/model.cljs`. These files already use hover time and rendered `:x-ratio` semantics. The vault-specific work should therefore focus on supplying honest timestamped series and only patch vault-specific tooltip assembly if a failing regression proves extra wiring is still needed.

## Plan of Work

Begin with failing tests that model the exact regression. In `/hyperopen/test/hyperopen/vaults/detail/performance_test.cljs`, add a fixture where the all-time vault account and PNL history spans `T0..T4`, but the direct `:one-year` summary only contains `T3..T4`. Assert that the new vault returns-window helper prefers the richer all-time-derived bounded window, synthesizes a cutoff anchor from the latest valid account/PnL point at or before the cutoff, rebases PNL from that anchor, and records truthful coverage metadata for account, PnL, and returns rows. Add a second case where there is no earlier point before the cutoff and assert that the context reports incomplete coverage rather than fabricating earlier history.

Add a chart-model regression in `/hyperopen/test/hyperopen/views/vaults/detail/chart_test.cljs` that proves vault `:x-ratio` is timestamp-based. Use a series with points at `T0`, `T2`, and `T4` and assert the resulting `:x-ratio` values land near `0`, `0.5`, and `1` based on time, not simply evenly by point index when another visible series has a different point count. Include an ordinal fallback case for points that genuinely lack finite timestamps so the new logic remains safe for malformed or synthetic input.

Add benchmark regressions in `/hyperopen/test/hyperopen/vaults/detail/benchmarks_test.cljs`. Model BTC candles at `T0` close `100`, `T1` close `94`, `T2` close `90`, `T3` close `86`, and `T4` close `88`, with vault return points only at `T3` and `T4`. Assert that market benchmark rows remain dense and preserve the path `[0, -6, -10, -14, -12]` over `T0..T4`, and assert that the benchmark row count can exceed the vault row count. Preserve the existing expectation that the anchor close is the latest candle at or before the bounded window cutoff.

Add an assembled VM regression in `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs` proving that the vault detail `Returns` chart on selected `:one-year` now exposes both a full-span dense BTC series and a strategy series whose raw points cover the available bounded history when all-time data supports it. This test should assert the chart metadata exposes truthful coverage information: first and last `:time-ms`, point count, and whether the selected window is complete.

Add a tooltip regression in `/hyperopen/test/hyperopen/views/vaults/detail/chart_tooltip_test.cljs` or `/hyperopen/test/hyperopen/views/chart/tooltip_core_test.cljs` that shows a hovered vault point at time `T` resolves the BTC benchmark value from the latest benchmark point at or before `T`, not from the same array index. If the shared tooltip-core test already proves the shared behavior, add only a vault-specific wiring test to ensure the vault detail route passes the correct hover data through.

After the failing tests exist, implement the runtime changes in the vault detail stack. In `/hyperopen/src/hyperopen/vaults/detail/performance.cljs`, introduce a vault summary/window context helper that mirrors the portfolio page’s truthful source metadata. It should return `:entry`, `:requested-key`, `:effective-key`, `:source-key`, `:source`, and coverage metadata for account, PnL, and returns rows. The core bounded-window algorithm should:

1. determine the selected or effective range and compute its cutoff from the window end,
2. inspect the direct bounded summary and the all-time summary as normalized aligned points,
3. prefer the richer all-time-derived window when it can provide an honest point at or before the cutoff and yields an earlier or denser bounded series than the direct summary,
4. synthesize a cutoff anchor row from the latest valid aligned account/PnL point at or before the cutoff when such a point exists,
5. include all valid aligned points inside the window,
6. rebase PnL from the cutoff anchor before passing the resulting summary into `portfolio-metrics/returns-history-rows`.

Keep account-value and PnL chart behavior as close to current behavior as possible; the returns-specific bounded-window reconstruction is the main fix. If there is no honest prior aligned point at or before the cutoff, leave the series incomplete and record that fact in the context metadata rather than inventing history.

In `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`, stop asking the chart path to infer truth from the selected summary alone. Thread the new vault returns-window context through `build-vault-detail-chart-section`, expose it on the assembled chart model as something like `:strategy-window`, and use it when computing the returns strategy series and any benchmark anchors.

In `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs`, port market benchmark rendering to the dense market-path semantics already proven on the portfolio page. Prefer importing the shared benchmark candle normalization and dense cumulative-return helpers from `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs` rather than maintaining another candle parser. Market coin benchmarks like BTC and ETH should render from their own normalized candle timestamps over the effective window, with anchor close equal to the latest candle at or before the cutoff and with last-write-wins duplicate handling. Vault-as-benchmark behavior may remain strategy-aligned for this pass unless the new regressions prove it shares the same user-facing path defect.

In `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`, replace index-based `:x-ratio` math with shared time-based normalization. The preferred minimal approach is to reuse `/hyperopen/src/hyperopen/views/portfolio/vm/chart-math.cljs` for shared time-domain and y-domain helpers instead of maintaining a third slightly different implementation. Use a shared time domain across visible series when finite timestamps exist, and keep ordinal/index fallback only for a series that lacks finite `:time-ms`.

Finally, verify the hover and tooltip path. Because the shared chart runtime and tooltip-core are already timestamp-aware, the likely work is only to ensure the vault detail route passes the correct timestamped points. Only edit `/hyperopen/src/hyperopen/views/vaults/detail/chart_tooltip.cljs`, `/hyperopen/src/hyperopen/views/chart/tooltip_core.cljs`, or the shared D3 files if the new failing regressions show a remaining vault-specific mismatch.

## Concrete Steps

From `/Users/barry/.codex/worktrees/8d94/hyperopen`, add the failing test coverage first and run the smallest affected namespaces:

    npm test -- --focus hyperopen.vaults.detail.performance-test
    npm test -- --focus hyperopen.vaults.detail.benchmarks-test
    npm test -- --focus hyperopen.views.vaults.detail.chart-test
    npm test -- --focus hyperopen.views.vaults.detail-vm-test
    npm test -- --focus hyperopen.views.vaults.detail.chart-tooltip-test

If the local runner does not support `--focus`, run the smallest equivalent namespace-targeted command in this repository or temporarily fall back to `npm test` once the new test names are in place and easy to isolate by failure output.

Then edit these runtime files:

    /hyperopen/src/hyperopen/vaults/detail/performance.cljs
    /hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs
    /hyperopen/src/hyperopen/views/vaults/detail_vm.cljs
    /hyperopen/src/hyperopen/views/vaults/detail/chart.cljs
    /hyperopen/src/hyperopen/views/vaults/detail/chart_tooltip.cljs

Inspect but avoid changing these shared files unless a new failing test proves it is necessary:

    /hyperopen/src/hyperopen/views/portfolio/vm/history.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm/chart-math.cljs
    /hyperopen/src/hyperopen/views/chart/tooltip_core.cljs
    /hyperopen/src/hyperopen/views/chart/d3/model.cljs

Update these tests:

    /hyperopen/test/hyperopen/vaults/detail/performance_test.cljs
    /hyperopen/test/hyperopen/vaults/detail/benchmarks_test.cljs
    /hyperopen/test/hyperopen/views/vaults/detail/chart_test.cljs
    /hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs
    /hyperopen/test/hyperopen/views/vaults/detail/chart_tooltip_test.cljs
    /hyperopen/test/hyperopen/views/chart/tooltip_core_test.cljs

After the targeted tests pass, run the repo-required gates for code changes:

    npm run lint:input-parsing
    npm test
    npm run test:websocket
    npm run check

Then run browser QA on the failing route. Start the app if needed:

    npm run dev:portfolio

Open:

    http://localhost:8081/vaults/0xdfc24b077bc1425ad1dea75bcb6f8158e10df303

Select `Returns`, `1Y`, and BTC. Confirm:

- BTC still renders a dense real one-year path and ends near the correct final return.
- Vault returns now span the honest available vault history for the selected window when all-time data supports it.
- The chart X positions reflect real time instead of uniformly spaced point index.
- If earlier vault history truly does not exist, the assembled VM exposes incomplete coverage instead of visually implying a full-year vault path.

Stop browser sessions afterward:

    npm run browser:cleanup

## Validation and Acceptance

Acceptance requires all of the following:

- A new vault returns-window regression proves that when all-time vault account/PnL history spans `T0..T4` but a direct `:one-year` summary only covers `T3..T4`, the selected `:one-year` returns series still spans `T0..T4` by using the richer all-time-derived bounded context.
- A new regression proves the bounded-window derivation synthesizes a cutoff anchor from the latest valid aligned account/PnL point at or before the cutoff and rebases PnL from that anchor.
- A new regression proves vault chart `:x-ratio` is timestamp-based and that visible series with different point counts can still share the same time-domain start and end.
- A new regression proves BTC benchmark row count can exceed vault return row count and that BTC keeps the dense `T0..T4` path instead of being sampled only on vault timestamps.
- A new regression proves vault hover/tooltip benchmark lookup uses the latest benchmark point at or before the hovered vault timestamp rather than the same array index.
- The portfolio estimator fix, strict parsing, portfolio dense benchmark rendering, and portfolio time-based charting remain unchanged.
- `npm run lint:input-parsing`, `npm test`, `npm run test:websocket`, and `npm run check` all pass.
- Manual QA on the failing vault route shows both an honest dense BTC path and an honest vault return path for the available window.

## Idempotence and Recovery

This work is safe to stage incrementally because the first milestone is deterministic failing tests. If the first implementation attempt widens the vault series incorrectly, keep the new regressions and revert only the vault-specific window-selection or chart-normalization changes. Do not switch the chart back to ordinal/index X as a “quick fix”. Do not make market benchmarks sparse again. Do not modify `/hyperopen/src/hyperopen/portfolio/metrics/history.cljs` unless a newly added failing regression proves the vault issue somehow depends on estimator behavior. Do not weaken strict parsing rules or remove the truthful summary metadata that already exists on the portfolio page.

## Artifacts and Notes

The canonical regression fixture should look like this:

    Selected range: :one-year
    All-time vault account/PnL history: T0, T1, T2, T3, T4
    Direct :one-year summary: T3, T4 only
    BTC candles: T0 close 100, T1 close 94, T2 close 90, T3 close 86, T4 close 88
    Expected dense BTC path: 0, -6, -10, -14, -12
    Expected post-fix vault strategy span: T0..T4 when the all-time source provides an honest cutoff anchor

The truth contract to preserve is:

    If richer all-time vault history can reconstruct the selected bounded window honestly, use it.
    If it cannot, expose incomplete coverage truthfully.
    Never stretch sparse vault history across the chart by index and call it a one-year path.

Plan revision note (2026-04-14 21:49 EDT): Initial ExecPlan created after vault-detail source audit, issue creation for `hyperopen-7dbd`, and retirement of the now-completed portfolio window-source plan from `active/`.

## Interfaces and Dependencies

The likely new helper contract in `/hyperopen/src/hyperopen/vaults/detail/performance.cljs` is conceptually:

    (defn returns-history-context
      [details snapshot-range]
      => {:entry ...
          :summary ...
          :requested-key ...
          :effective-key ...
          :source-key ...
          :source ...
          :account-point-count ...
          :pnl-point-count ...
          :returns-point-count ...
          :first-time-ms ...
          :last-time-ms ...
          :cutoff-ms ...
          :complete-window? ...})

This helper must work only from the vault detail data already present in the route. It should inspect both the direct bounded summary and the all-time summary, compare them after normalization and alignment, and choose the source that yields the richest honest bounded window.

The chart layer should end up relying on shared time normalization semantics from:

    /hyperopen/src/hyperopen/views/portfolio/vm/chart-math.cljs

The market benchmark layer should end up relying on shared candle normalization and dense market-path semantics from:

    /hyperopen/src/hyperopen/views/portfolio/vm/history.cljs

The hover and tooltip path should continue to rely on:

    /hyperopen/src/hyperopen/views/chart/tooltip_core.cljs
    /hyperopen/src/hyperopen/views/chart/d3/model.cljs

Only patch those shared files if the new vault-specific regressions prove the vault route still wires them incorrectly.
