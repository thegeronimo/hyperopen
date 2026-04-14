# Correct Portfolio Benchmark Range And Source Trust Semantics

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The live `bd` issue for this work is `hyperopen-fc0a`, and `bd` remains the lifecycle source of truth until this plan moves out of `active/`.

## Purpose / Big Picture

The portfolio returns estimator bug for rebasing accounts is already fixed and must stay fixed. The remaining bug is different: on the trader portfolio page, the BTC benchmark can look too flat because the benchmark series is currently anchored to surviving strategy timestamps instead of the effective chart window. After this change, a user selecting `1Y` will either see BTC computed from the effective `1Y` anchor or see truthful metadata that the requested range resolved to a different source. The benchmark line should no longer silently become “BTC since the later strategy start.”

The visible proof is on the trader portfolio route at `/portfolio/trader/0x5b5d51203a0f9079f8aeb098a6523a13f298c060`: the returns chart should stop showing a BTC line whose economics are defined by the first surviving strategy point, and the view model should stop pretending the requested summary key is always the source actually used.

## Progress

- [x] (2026-04-14 19:05 EDT) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and the browser-testing routing contract before drafting this plan.
- [x] (2026-04-14 19:07 EDT) Audited the current benchmark and summary-selection path in `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`, and `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`.
- [x] (2026-04-14 19:08 EDT) Collected two read-only explorer audits focused on benchmark semantics and summary-fallback truth. Both agreed that the strongest bug is strategy-anchored benchmark economics plus silent summary-source mismatch, not the already-fixed rebasing estimator.
- [x] (2026-04-14 19:08 EDT) Created and claimed `bd` issue `hyperopen-fc0a` for the benchmark/range trust regression and linked it as discovered-from `hyperopen-l5m0`.
- [x] (2026-04-14 19:11 EDT) Created this active ExecPlan and froze the implementation scope to summary-source truthfulness, benchmark anchor semantics, benchmark candle normalization, regression tests, and required validation gates. Reverting or weakening the rebasing-account returns fix is out of scope.
- [x] (2026-04-14 19:14 EDT) Added failing regression coverage in `/hyperopen/test/hyperopen/views/portfolio/vm/history_helpers_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_helpers_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm/summary_helpers_test.cljs`, and `/hyperopen/test/hyperopen/views/portfolio/vm/summary_test.cljs` for late strategy starts, wrapped candle containers, duplicate timestamp dedupe, and truthful summary-source metadata.
- [x] (2026-04-14 19:16 EDT) Implemented `selected-summary-context` in `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`, plumbed requested/effective/source metadata through `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`, and preserved `selected-summary-entry` as the compatibility wrapper.
- [x] (2026-04-14 19:17 EDT) Fixed bounded-range market benchmark anchoring in `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs` so market benchmarks anchor to the effective window cutoff when a valid prior candle exists, while keeping the first-strategy-point path only as fallback behavior.
- [x] (2026-04-14 19:18 EDT) Hardened `benchmark-candle-points` for wrapped candle containers and duplicate timestamp last-write-wins dedupe, then moved shared window/alignment helpers into `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs` to stay under the namespace-size guard without adding a size exception.
- [x] (2026-04-14 19:25 EDT) Ran `npx playwright test tools/playwright/test/routes.smoke.spec.mjs --grep trader-portfolio`, `npm run lint:input-parsing`, `npm test`, `npm run test:websocket`, `npm run lint:namespace-sizes`, and `npm run check` successfully.
- [ ] Confirm the live BTC benchmark visually on `/portfolio/trader/0x5b5d51203a0f9079f8aeb098a6523a13f298c060` after the next manual QA pass. The deterministic benchmark-math tests and repo gates are green, but the exact live-data tooltip value was not promoted to a browser assertion in this patch.

## Surprises & Discoveries

- Observation: the benchmark overlay is currently strategy-aligned by design, not an independent market return series over the selected window.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs` builds `strategy-time-points` from `portfolio-metrics/returns-history-rows` and then feeds those timestamps into `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs:aligned-benchmark-return-rows`.

- Observation: the view model can report a requested summary key even when a different summary source actually won.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs:selected-summary-entry` can fall through exact match, derived-from-all-time, nearby fallback, and arbitrary first-summary branches, while `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` still exposes `:summary.selected-key` from `selected-summary-key`.

- Observation: existing benchmark helper tests ratify the current anchor behavior instead of challenging it.
  Evidence: `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_helpers_test.cljs` asserts that the benchmark aligns to the latest prior candle on strategy timestamps, which proves current behavior but not that the behavior is economically correct for `1Y`.

- Observation: benchmark candle normalization is thinner than the shared portfolio-history normalization seam.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs:benchmark-candle-points` sorts rows but does not unwrap wrapped container shapes or dedupe duplicate timestamps.

- Observation: the first implementation pass satisfied behavior but tripped the namespace-size guard on `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`.
  Evidence: `npm run check` failed with `[size-exception-exceeded] src/hyperopen/views/portfolio/vm/benchmarks.cljs - namespace has 633 lines; exception allows at most 595` until the shared window/anchor helpers moved into `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`.

## Decision Log

- Decision: treat this as a new benchmark/range trust bug instead of reopening the portfolio returns estimator fix.
  Rationale: the rebasing-account false `-100%` failure mode is already covered by the `indeterminate-cash-flow?` path and its tests. The user-visible regression is in benchmark anchoring and source truth, not in the estimator kernel.
  Date/Author: 2026-04-14 / Codex

- Decision: add the failing benchmark anchor test before implementation and keep it purely in the portfolio VM test surface.
  Rationale: the bug is local to summary selection, benchmark alignment, and benchmark candle normalization. A VM-level failing test isolates the regression without dragging the returns estimator back into scope.
  Date/Author: 2026-04-14 / Codex

- Decision: add a structured `selected-summary-context` helper and keep `selected-summary-entry` as a compatibility wrapper.
  Rationale: the VM needs truthful source metadata without forcing a broad call-site rewrite all at once. A structured context allows the UI and tests to distinguish requested, effective, and actual source keys while preserving older helper contracts where needed.
  Date/Author: 2026-04-14 / Codex

- Decision: compute bounded-range market benchmark economics from an explicit anchor time, then sample those returns onto strategy timestamps only for rendering.
  Rationale: the chart can still share tooltip timestamps with the strategy series, but the market return itself must be defined by the effective window start rather than the first surviving strategy point.
  Date/Author: 2026-04-14 / Codex

- Decision: keep the old anchor-at-first-strategy-point behavior only as a fallback when no valid anchor candle exists at or before the effective window start.
  Rationale: that preserves a graceful path for sparse data while fixing the normal bounded-range case that currently misleads users.
  Date/Author: 2026-04-14 / Codex

- Decision: harden benchmark candle normalization for wrapped container shapes and duplicate timestamps inside the same patch.
  Rationale: this is a small localized improvement in the same file, and it prevents a second avoidable source of benchmark drift while keeping the patch confined to the benchmark path.
  Date/Author: 2026-04-14 / Codex

- Decision: resolve the namespace-size failure by moving shared benchmark window/alignment helpers into `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs` instead of adding or enlarging a size exception for `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`.
  Rationale: the behavior change was already correct, and the helper code genuinely belongs to the history/alignment seam. Extracting it kept the patch local while preserving the repository guardrail.
  Date/Author: 2026-04-14 / Codex

## Outcomes & Retrospective

Implementation is complete and validated locally. The portfolio VM now exposes truthful `requested-key`, `effective-key`, `source-key`, and `source` metadata; bounded-range market benchmarks anchor to the effective window cutoff instead of the first surviving strategy timestamp when a valid prior candle exists; wrapped candle containers and duplicate timestamps normalize deterministically; and the rebasing-account returns estimator path was left untouched.

Overall complexity went down slightly. The benchmark economics are now explicit instead of hidden inside strategy-timestamp alignment, and the summary-selection path now returns structured source metadata instead of a bare summary map. The only remaining work item on this active plan is route-specific live-data visual confirmation on the previously failing trader page; deterministic math coverage, route smoke, and the required repository gates are already green.

## Context and Orientation

The relevant code lives in four view-model namespaces under `/hyperopen/src/hyperopen/views/portfolio/vm/`.

`summary.cljs` normalizes `summary-by-key` maps from portfolio API payloads and currently chooses one summary through `selected-summary-entry`. That helper can return an exact range match, a derived range from all-time history, a nearby fallback range, or an arbitrary first summary. Today it returns only the summary map, so callers cannot tell which source won.

`vm.cljs` assembles the public portfolio view model. It currently computes `summary-entry` from `selected-summary-entry` but separately reports `selected-key` from `selected-summary-key`, which is the requested key rather than the actual source. That mismatch is the trust bug on the summary side.

`benchmarks.cljs` computes the benchmark overlay context. Today it derives `strategy-time-points` from the already-normalized strategy returns series and then asks `history.cljs` to align market candles to those timestamps. That means the strategy series defines the benchmark timestamps and, indirectly, the current benchmark anchor.

`history.cljs` parses raw history rows and benchmark candles for the VM layer. `benchmark-candle-points` currently accepts only raw row sequences and sorts them. `aligned-benchmark-return-rows` currently anchors the benchmark to the first strategy timestamp with a prior candle because it has no separate anchor-time argument.

The bounded-range bug is easiest to see with a simple example. Suppose the selected range is `:one-year`, BTC candles exist at `T0` one year ago with close `100`, at `T1` six months later with close `86`, and at `T2` now with close `88`. If the strategy series only has points at `T1` and `T2`, the current code anchors BTC at `86` and reports about `+2.33%`. The economically correct bounded-range answer is still `-12%` because BTC moved from `100` to `88` over the selected window. The patch must make that case pass.

## Plan of Work

First, add failing tests in `/hyperopen/test/hyperopen/views/portfolio/vm/`. The highest-value cases are:

- a benchmark-helper regression that proves late strategy starts must not flatten a `:one-year` BTC benchmark when an earlier candle exists at the effective window start
- a history-helper regression for wrapped candle containers such as `{:rows [...]}`, `{:data [...]}`, or `{:candles [...]}`
- a history-helper regression for duplicate candle timestamps that must resolve with last-write-wins semantics
- a summary or VM regression that proves requested and effective summary keys can differ and that the VM now exposes truthful metadata about that difference

Second, patch `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`. Add a `selected-summary-context` helper that returns a map with `:entry`, `:requested-key`, `:effective-key`, `:source-key`, and `:source`, where `:source` is one of `:direct`, `:derived`, `:fallback`, or `:first`. Keep `selected-summary-entry` as a wrapper that returns `(:entry ...)` so older call sites remain stable. When the requested range derives correctly from all-time history, `:effective-key` should remain the requested key while `:source-key` points at the all-time summary. When a nearby fallback wins, both `:source-key` and `:effective-key` should reflect the actual chosen summary key.

Third, patch `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` so `portfolio-vm` carries both the requested selector state and truthful summary metadata. The selector model should still reflect the requested dropdown value, but the summary section must no longer imply that requested and effective are the same thing. Add a structured summary-source payload under `:summary` rather than replacing the selector behavior. Keep the rest of the VM stable unless a small helper extraction is required.

Fourth, patch benchmark anchoring in `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`. Extend `aligned-benchmark-return-rows` with an optional anchor time. For bounded market ranges, compute that anchor time from the selected or effective range cutoff rather than from the first surviving strategy timestamp. The function should choose the latest candle at or before the anchor time as the economic anchor close. It should then keep sampling benchmark returns onto strategy timestamps so the rendered series stays aligned with the strategy series. Only if there is no valid anchor candle at or before the requested anchor time should it fall back to the old first-strategy-point anchor behavior.

Fifth, harden `benchmark-candle-points` in `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`. It must accept plain row sequences plus wrapped candle containers that store the actual rows under `:rows`, `:data`, or `:candles`. It must sort by timestamp and collapse duplicates with last-write-wins semantics so later entries for the same timestamp replace earlier ones.

Finally, update the VM and helper tests so the new semantics are locked in. The benchmark-helper tests should prove that dropping early strategy rows does not change the bounded-range economic return when an earlier valid anchor candle exists. The summary tests should prove that the VM exposes truthful requested-versus-effective metadata. If needed, add one small browser regression in `/hyperopen/tools/playwright/test/portfolio-regressions.spec.mjs` or keep browser QA manual but explicit; the unit tests are the primary truth surface for this patch.

## Concrete Steps

From `/Users/barry/.codex/worktrees/8d94/hyperopen`, first create the failing test surface and run only the targeted portfolio VM tests so the regression is isolated:

    npm test -- --focus hyperopen.views.portfolio.vm.history-helpers-test
    npm test -- --focus hyperopen.views.portfolio.vm.benchmarks-helpers-test
    npm test -- --focus hyperopen.views.portfolio.vm.summary-test

If the test runner does not support `--focus` in the local environment, run the smallest namespace-specific command already used in this repo or fall back to `npm test` after keeping the new tests grouped and named clearly. The important behavior is that the new late-strategy-anchor test fails before the implementation and passes after it.

Then edit these files:

    /hyperopen/src/hyperopen/views/portfolio/vm/history.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs
    /hyperopen/src/hyperopen/views/portfolio/vm.cljs
    /hyperopen/test/hyperopen/views/portfolio/vm/history_helpers_test.cljs
    /hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_helpers_test.cljs
    /hyperopen/test/hyperopen/views/portfolio/vm/summary_test.cljs

If UI-facing source metadata needs a render assertion, also update:

    /hyperopen/test/hyperopen/views/portfolio/summary_cards_test.cljs
    /hyperopen/test/hyperopen/views/portfolio/performance_metrics_view_test.cljs

After the localized patch compiles, run the required gates:

    npm run lint:input-parsing
    npm test
    npm run test:websocket
    npm run check

Because this work touches portfolio UI semantics, also run a small browser verification from `/Users/barry/.codex/worktrees/8d94/hyperopen`:

    npm run dev:portfolio

Then open:

    http://localhost:8081/portfolio/trader/0x5b5d51203a0f9079f8aeb098a6523a13f298c060

Select `Returns`, `1Y`, and BTC benchmark. Confirm that the BTC line no longer looks like it is anchored to a later start without disclosure. If summary fallback occurs, the view model or UI must now expose truthful metadata about it. Clean up any browser-inspection sessions with `npm run browser:cleanup` if browser tooling was used.

## Validation and Acceptance

Acceptance requires all of the following:

- The new benchmark regression test proves that with candles `[T0=100, T1=86, T2=88]`, strategy points at only `T1` and `T2`, and a bounded `:one-year` request, the final BTC benchmark return is `-12`, not about `+2.33`.
- Wrapped candle containers and duplicate candle timestamps normalize correctly in helper tests.
- The summary-selection tests prove that the VM exposes truthful `requested` versus `effective` summary metadata.
- The rebasing-account returns fix remains intact because no estimator files were changed and the full test suite stays green.
- `npm run lint:input-parsing`, `npm test`, `npm run test:websocket`, and `npm run check` all pass.
- Manual trader-page QA no longer shows a misleadingly late-anchored BTC benchmark for the failing case.

## Idempotence and Recovery

This plan is additive and safe to rerun. The code changes are confined to the portfolio VM and its tests. If a benchmark-anchor change causes unexpected downstream effects, revert only the benchmark/source-truth edits in the four VM files and leave the rebasing-account estimator logic untouched. Do not weaken the stricter parsing rules and do not modify `/hyperopen/src/hyperopen/portfolio/metrics/history.cljs` unless a newly added failing test proves the estimator itself is involved.

## Artifacts and Notes

The key deterministic regression to preserve is:

    Requested range: :one-year
    Effective anchor candle: T0 close 100
    Strategy timestamps: [T1 T2]
    Final benchmark close: 88
    Expected cumulative BTC return at T2: -12.0

The key VM truth contract to preserve is:

    requested-key = what the selector asked for
    effective-key = what window the summary and benchmark actually use
    source-key = which summary entry supplied the data
    source = :direct | :derived | :fallback | :first

## Interfaces and Dependencies

In `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`, define:

    (defn selected-summary-context
      [summary-by-key scope time-range]
      => {:entry ...
          :requested-key ...
          :effective-key ...
          :source-key ...
          :source ...})

and preserve:

    (defn selected-summary-entry
      [summary-by-key scope time-range]
      => (:entry (selected-summary-context ...)))

In `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`, extend:

    (defn aligned-benchmark-return-rows
      [benchmark-points strategy-points]
      ...)

to also support an optional anchor time argument, while keeping existing call sites valid. The function must use the latest candle at or before the explicit anchor time when available, then emit cumulative percentage returns on strategy timestamps.

Plan revision note (2026-04-14 19:11 EDT): Initial active ExecPlan created after the benchmark-path audit, the summary-fallback audit, and the user-provided failing scenario. The scope is intentionally limited to benchmark anchoring, summary-source truthfulness, benchmark candle normalization, and regression coverage so the earlier rebasing-account returns fix remains untouched.

Plan revision note (2026-04-14 19:26 EDT): Updated after implementation and validation. The code fix, deterministic regression coverage, trader-route smoke, and required gates are complete. The plan stays active only because the exact live-data visual confirmation on the failing trader page is still an explicit unchecked QA item.
