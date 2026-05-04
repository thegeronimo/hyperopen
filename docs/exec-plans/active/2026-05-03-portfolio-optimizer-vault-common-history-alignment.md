---
owner: platform
status: active
created: 2026-05-03
source_of_truth: false
tracked_issue: hyperopen-ejzz
---

# Portfolio Optimizer Vault Common-History Alignment

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Tracked issue: `hyperopen-ejzz` ("Fix optimizer vault common-history alignment").

## Purpose / Big Picture

A user should be able to build a Portfolio Optimizer universe out of real Hyperliquid vaults and press `Run Optimization` without being blocked merely because each vault's best individual summary window has different timestamp density. The motivating scenario is selecting `Hyperliquidity Provider (HLP)`, `Growi HF`, and `[ Systemic Strategies ] HyperGrowth` on `/portfolio/optimize/new`. Today all three vault detail requests succeed, but the run fails with `only 1 usable shared return observations; 2 required.`

After this change, the optimizer should derive a returns matrix from a common shorter vault-history window when the individually preferred windows do not overlap enough. In the motivating scenario, the run should move past `fetch returns matrix`, build at least two aligned shared return observations, and either solve or fail later for a different, truthful optimizer reason. The setup universe table should also stop showing a green `sufficient` history chip when only raw vault details are loaded but the selected universe still lacks enough shared aligned history.

## Progress

- [x] (2026-05-03 20:15Z) Traced the screenshot failure to `:insufficient-common-history` after successful `vaultDetails` fetches.
- [x] (2026-05-03 20:18Z) Reproduced the failure with live Hyperliquid payloads for HLP, Growi HF, and Systemic HyperGrowth: exact common timestamps were `0`, day-aligned common dates were only `2026-05-03`.
- [x] (2026-05-03 20:20Z) Created tracked bug `hyperopen-ejzz` for the implementation work.
- [x] (2026-05-03 20:21Z) Created this active ExecPlan from the investigation findings.
- [x] (2026-05-03 20:27Z) Added RED history-loader coverage for the mixed-window HLP/Growi/Systemic vault shape; verified it failed with empty aligned history before the fallback.
- [x] (2026-05-03 20:34Z) Implemented vault history candidate generation and bounded common-window fallback; focused history-loader tests pass.
- [x] (2026-05-03 20:39Z) Added setup readiness status projection and routed it into the setup universe row chip; loaded-but-misaligned histories now render as `shared gap`.
- [x] (2026-05-03 20:43Z) Added pipeline regression proving the three-vault fixture runs after history loading with `:alignment-source {:kind :common-vault-window :window :month :observations 4}`.
- [x] (2026-05-03 20:50Z) Added and ran targeted Playwright regression for the selected vault row `shared gap` label.
- [x] (2026-05-03 20:52Z) Ran focused optimizer tests, `npm test`, `npm run test:websocket`, targeted Playwright, and browser cleanup. `npm run check` remains blocked by unrelated `docs/exec-plans/active/2026-05-03-outcome-no-market-order-book-subscription.md` failing `active-exec-plan-no-unchecked-progress`.
- [x] (2026-05-03 20:57Z) Addressed static review edge case where direct `:one-year` candidates could mask valid derived `:one-year` candidates during common-window fallback; added regression coverage and reran focused optimizer tests.

## Surprises & Discoveries

- Observation: The fetch step is not the failing component. The progress panel reaches `3/3 requests` and `100%`; the failure is thrown when the pipeline rebuilds readiness after loading history.
  Evidence: `src/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline.cljs` calls `load-history!`, then `pipeline-ready-request`, which throws `setup-readiness/readiness-error-message` when `setup-readiness/build-readiness` remains non-runnable.

- Observation: `Hyperliquidity Provider (HLP)` and `Growi HF` receive derived one-year histories, while `[ Systemic Strategies ] HyperGrowth` falls back to a direct month history.
  Evidence: `src/hyperopen/portfolio/metrics/history.cljs` prefers direct one-year, then derived one-year from all-time when complete, then shorter direct summaries. Live payload replay showed HLP has 29 derived one-year daily points, Growi has 54 derived one-year daily points, and HyperGrowth has 32 month daily points.

- Observation: The three selected vaults have enough overlapping date range in reality, but not enough overlapping chosen summary timestamps under the current algorithm.
  Evidence: live replay on 2026-05-03 found HLP one-year dates including `2026-04-01`, `2026-04-12`, `2026-04-23`, `2026-05-03`; Growi one-year dates including weekly April dates and `2026-05-03`; HyperGrowth month dates daily from `2026-04-02` through `2026-05-03`. The intersection across the selected chosen histories is only `2026-05-03`.

- Observation: The selected row `sufficient` chip in the setup screenshot is a raw-fetch indicator, not an optimizer-readiness indicator.
  Evidence: `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` returns `sufficient` for a vault when `[:portfolio :optimizer :history-data :vault-details-by-address <address>]` is non-empty, without checking the aligned common calendar or readiness warnings.

- Observation: The repository-wide `npm run check` gate is currently blocked before application compilation by an unrelated active ExecPlan hygiene lint.
  Evidence: `npm run check` fails at `lint:docs` with `[active-exec-plan-no-unchecked-progress] docs/exec-plans/active/2026-05-03-outcome-no-market-order-book-subscription.md - active ExecPlan has no remaining unchecked progress items; move it out of active`.

- Observation: Same-window vault candidates must be tried by rank, not collapsed to the first candidate for a window.
  Evidence: static review found that a direct `:one-year` summary could prevent fallback from trying a derived `:one-year` summary for the same vault. Added `align-history-inputs-tries-derived-one-year-when-direct-one-year-window-does-not-overlap-test` and updated fallback to try ranked candidates per common window.

## Decision Log

- Decision: Fix the return-history selection, not the solver or progress UI.
  Rationale: The optimizer never reaches shrinkage estimation, expected-return estimation, or OSQP. The failure happens while building the request history calendar, before solver math runs.
  Date/Author: 2026-05-03 / Codex

- Decision: Prefer a universe-wide common vault summary window fallback over interpolation as the first implementation.
  Rationale: The live failure is caused by independently choosing different vault summary windows. Trying a common direct window such as `:month` is smaller, easier to reason about, and preserves real observed return points without manufacturing daily prices. Interpolation or last-observation-carried-forward resampling can be considered later only if common-window fallback still leaves important scenarios blocked.
  Date/Author: 2026-05-03 / Codex

- Decision: Keep the existing default preference when it already yields enough shared observations.
  Rationale: Existing behavior is correct for market candles and vault combinations that share enough one-year or derived one-year observations. The fallback should activate only after current exact and day-aligned common-calendar attempts fail.
  Date/Author: 2026-05-03 / Codex

- Decision: Treat the setup universe row history chip as a readiness label, not only a raw network-cache label.
  Rationale: The current green `sufficient` chip creates the false impression that the selected universe is ready even when the actual readiness panel says `INSUFFICIENT-COMMON-HISTORY`.
  Date/Author: 2026-05-03 / Codex

## Outcomes & Retrospective

Implemented. Vault detail normalization now exposes ordered candidate histories while preserving `normalize-vault-history` as the preferred-history compatibility wrapper. History alignment first uses the existing preferred histories, then tries deterministic common vault windows and same-window candidate ranks before emitting `:insufficient-common-history`. The motivating fixture aligns on the direct `:month` window with four shared price observations and three return observations.

Setup readiness now exposes `history-status-by-instrument`, and the setup universe panel uses that status map so rows distinguish `sufficient`, `shared gap`, `short`, and `missing`. A targeted Playwright regression covers the browser-rendered `shared gap` chip.

## Context and Orientation

The Portfolio Optimizer setup route is implemented under `src/hyperopen/views/portfolio/optimize/**`. The canonical route in this repository is `/portfolio/optimize/new`; the user report wrote `/portfolio/optimizer/new`, but the screenshots and route code refer to `/portfolio/optimize/new`.

An optimizer universe is the set of selected instruments used to build the returns matrix. A returns matrix is a map from instrument id to aligned return series. Mean-variance optimization requires the same number of return observations for every selected instrument, so the optimizer first builds a shared calendar of price observations and then converts adjacent price pairs into returns.

Vault instruments are represented with ids like `vault:0xdfc24b077bc1425ad1dea75bcb6f8158e10df303`. They are not spot or perp markets. For vault instruments, the optimizer history client fetches Hyperliquid `vaultDetails` and converts the returned portfolio summaries into price-like rows. That conversion currently starts in `src/hyperopen/portfolio/optimizer/application/history_loader/alignment.cljs` and calls `normalization/normalize-vault-history` from `src/hyperopen/portfolio/optimizer/application/history_loader/normalization.cljs`.

The current failure path is:

1. `src/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline.cljs` starts the run pipeline.
2. `src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs` fetches three `vaultDetails` payloads.
3. `src/hyperopen/portfolio/optimizer/application/request_builder.cljs` calls `history-loader/align-history-inputs`.
4. `src/hyperopen/portfolio/optimizer/application/history_loader/alignment.cljs` builds one preferred history per instrument, intersects exact timestamps, then intersects UTC-day timestamps.
5. Because the selected histories share only one daily date, `align-history-inputs` emits `{:code :insufficient-common-history :observations 1 :required 2}` and no eligible instruments.
6. `src/hyperopen/portfolio/optimizer/application/setup_readiness.cljs` turns that warning into `No eligible history was available: only 1 usable shared return observations; 2 required.`

Important existing behavior to preserve: `default-min-observations` is `2`, because two price points are the minimum needed to compute one return. A calendar with one price point cannot produce any return observations.

## Plan of Work

First, add a failing history-loader regression for the reported vault set shape. Do not use live network in the test. Build fixture summaries that mirror the live behavior: HLP and Growi have all-time summaries that can derive one-year sparse histories, and all three vaults have direct month summaries with enough shared April and May dates. The test should call `history-loader/align-history-inputs` with three vault instruments and expect a non-empty `:eligible-instruments`, at least three calendar points from the common month window, and no `:insufficient-common-history` warning. Before implementation, this test should fail with an empty calendar or an insufficient-common-history warning.

Add that test to `test/hyperopen/portfolio/optimizer/application/history_loader_test.cljs`. Reuse the existing helpers in that file: `day-start-ms`, `summary-from-points`, and the established vault-history tests around `align-history-inputs-prefers-derived-one-year-vault-history-over-direct-month-test`. Name the new test:

    align-history-inputs-falls-back-to-common-direct-vault-window-when-preferred-windows-do-not-overlap-test

The shape should be concrete:

    h0 = 2025-05-03
    h1 = 2025-10-30
    m0 = 2026-04-02
    m1 = 2026-04-12
    m2 = 2026-04-23
    m3 = 2026-05-03

    HLP all-time points: h0, h1, m1, m2, m3
    Growi all-time points: h0, h1, m1, m2, m3
    HyperGrowth all-time points: m3 only
    All three month summaries: m0, m1, m2, m3

Expected after the fix:

    (:calendar aligned) includes [m0 m1 m2 m3] after day alignment.
    (:return-calendar aligned) includes [m1 m2 m3].
    (:eligible-instruments aligned) contains all three vault ids.
    (:warnings aligned) does not contain {:code :insufficient-common-history ...}.

Second, expose vault history candidates instead of only one preferred vault history. In `src/hyperopen/portfolio/optimizer/application/history_loader/normalization.cljs`, add a function that returns labeled candidate histories for a vault details payload. Keep `normalize-vault-history` as a compatibility wrapper that returns the first preferred candidate so existing callers and tests keep their contract.

Suggested internal shape:

    {:source :derived-one-year
     :window :one-year
     :history [{:time-ms ... :close ...} ...]}

Candidate order should preserve the current preference: direct one-year, derived one-year from all-time, direct six-month, direct three-month, direct month, direct week, direct day, all-time, first-any fallback. Dedupe candidates that produce the same `:history` time series so fallback does not retry equivalent histories.

If private helpers in `hyperopen.portfolio.metrics.history` block this cleanly, do not make a broad public API. Instead, implement a focused candidate builder in optimizer normalization using existing normalized portfolio maps and the same rules already visible in `preferred-vault-summary`: direct one-year, derived one-year when the all-time window is complete and has at least three observations, then shorter direct summaries.

Third, update alignment to try fallback vault candidate sets only when current alignment fails. In `src/hyperopen/portfolio/optimizer/application/history_loader/alignment.cljs`, keep the current prepared instrument flow but retain candidates for vault instruments. After `effective-history-alignment` fails with fewer than `min-observations`, try common vault windows in descending useful length:

    :one-year
    :six-month
    :three-month
    :month
    :week
    :day
    :all-time

For each window, build a candidate prepared set where each vault instrument uses its candidate for that window and non-vault instruments keep their normal candle history. Skip a window if any selected vault lacks a usable candidate for it. Run the existing exact and daily calendar alignment on each candidate set. Pick the first candidate set with at least `min-observations` shared price points. Preserve the existing current result when it already passes.

This keeps the fallback bounded. It does not attempt a Cartesian product across all vault candidates, which would grow badly with a 25-asset universe and would be hard to explain.

The aligned result should include enough metadata for diagnostics and tests to explain the fallback. Add a small key under the returned history map, for example:

    :alignment-source {:kind :common-vault-window
                       :window :month
                       :observations 4}

When no fallback was needed, use:

    :alignment-source {:kind :preferred-history
                       :observations <count>}

Do not add this metadata to the engine request contract in a way that changes solver input. It belongs inside `:history` as explanatory metadata only.

Fourth, add tests for preservation and failure behavior. In `test/hyperopen/portfolio/optimizer/application/history_loader_test.cljs`, add or update assertions proving:

- existing derived-one-year alignment still wins when it already has enough common points;
- the common-window fallback chooses `:month` for the reported mixed-window vault shape;
- if no common direct window has enough shared points, `:insufficient-common-history` still appears with the best observed count and all instruments remain excluded.

Use deterministic fixture points and the existing `near?` helper. Do not call Hyperliquid in tests.

Fifth, update readiness/status display. The selected-row chip lives in `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` in `history-label`. That helper currently checks only whether cached raw history exists. Replace or augment it with a readiness-aware label model derived from the built request history. Prefer a pure helper in `src/hyperopen/portfolio/optimizer/application/setup_readiness.cljs` because view code should not duplicate history warning semantics.

Suggested API:

    (defn history-status-by-instrument [readiness] ...)

Return a map from instrument id to one of:

    :aligned
    :loaded-but-misaligned
    :missing
    :insufficient

For universe-wide `:insufficient-common-history`, mark every requested instrument that otherwise had raw history as `:loaded-but-misaligned`. The setup UI can render that as `shared gap` or `misaligned` with warning tone. Keep `sufficient` only for `:aligned`.

Add view tests in `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` or `test/hyperopen/views/portfolio/optimize/universe_panel_test.cljs` proving that when vault details exist but readiness has `:insufficient-common-history`, the selected vault row does not render green `sufficient`. The exact visible label should be short and scannable; use `shared gap` if no established label exists.

Sixth, verify the full action pipeline. Add a focused test in `test/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline_test.cljs` or `test/hyperopen/runtime/effect_adapters/portfolio_optimizer_history_facade_test.cljs` that simulates the run pipeline with three vault detail payloads matching the fixture shape. The test should prove that after `load-history!`, `pipeline-ready-request` can build a runnable request and the worker run effect is requested. If directly exercising private `pipeline-ready-request` is awkward, test through `run-portfolio-optimizer-pipeline-effect` with stubbed `load-history!` and `request-run!` dependencies.

Seventh, update user-facing readiness copy only if needed. The existing error copy remains good when no fallback can align the histories. If the fallback succeeds, no new error should show. If the fallback fails, keep the current `only N usable shared return observations; M required` message, but include the new setup-row status so users can see that raw history was loaded but not mutually alignable.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/ccbe/hyperopen`.

1. Confirm the tracker is visible:

       bd show hyperopen-ejzz --json

   Expected: the issue title is `Fix optimizer vault common-history alignment` and status is `open` or `in_progress`.

2. Add the RED history-loader test in `test/hyperopen/portfolio/optimizer/application/history_loader_test.cljs`.

3. Run the focused test namespace:

       npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.history-loader-test

   Expected before implementation: the new common-window test fails because the aligned calendar is empty or contains only one point, and `:warnings` contains `:insufficient-common-history`.

4. Implement vault candidate generation in `src/hyperopen/portfolio/optimizer/application/history_loader/normalization.cljs`.

5. Implement bounded common-window fallback in `src/hyperopen/portfolio/optimizer/application/history_loader/alignment.cljs`.

6. Re-run the focused history-loader test command. Expected after implementation: the new test passes, and existing vault history tests still pass.

7. Add setup readiness status helper tests in `test/hyperopen/portfolio/optimizer/application/setup_readiness_test.cljs`.

8. Update `src/hyperopen/portfolio/optimizer/application/setup_readiness.cljs` with the status helper. Keep `readiness-error-message` behavior unchanged unless tests prove the copy is inaccurate.

9. Update `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` to render `sufficient` only for aligned histories and a warning chip such as `shared gap` for loaded-but-misaligned histories.

10. Add view coverage for the selected row chip. Use the existing Hiccup collection helpers in the optimizer view tests. Expected before the view change: the row still contains `sufficient`; expected after: it contains `shared gap` or the chosen warning label.

11. Add the pipeline-level regression using stubbed history bundles and worker request capture.

12. Run focused optimizer tests:

       npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.history-loader-test --test=hyperopen.portfolio.optimizer.application.setup-readiness-test --test=hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline-test --test=hyperopen.views.portfolio.optimize.setup-v4-layout-test

   If the test runner ignores `--test` filters and runs the full suite, record the observed behavior in this ExecPlan and continue.

13. Run the required gates for code changes:

       npm run check
       npm test
       npm run test:websocket

   Expected: all three commands exit with code `0`.

14. Run browser validation for the user scenario. Start the app or use the existing Playwright static server pattern from optimizer tests. Exercise `/portfolio/optimize/new`, search and add HLP, Growi HF, and Systemic HyperGrowth, then click `Run Optimization`.

   Smallest acceptable deterministic Playwright coverage is a new regression in `tools/playwright/test/portfolio-regressions.spec.mjs` that seeds or selects those vaults and asserts the progress panel does not fail at `fetch returns matrix` with `INSUFFICIENT-COMMON-HISTORY`.

15. Because this touches a UI setup surface, account for browser QA from `/hyperopen/docs/BROWSER_TESTING.md`. Run the smallest relevant Playwright regression first, then broaden only if it passes. Stop browser-inspection sessions before completion:

       npm run browser:cleanup

## Validation and Acceptance

The implementation is accepted when the reported scenario has a usable shared return calendar. With HLP, Growi HF, and Systemic HyperGrowth selected, the optimizer history alignment should choose a common direct vault window, expected to be `:month` for the live payload shape, and produce at least two shared price observations. The run should no longer fail with `INSUFFICIENT-COMMON-HISTORY` immediately after `3/3 requests`.

Unit acceptance:

- `align-history-inputs-falls-back-to-common-direct-vault-window-when-preferred-windows-do-not-overlap-test` fails before the alignment change and passes after.
- Existing tests proving direct one-year and derived one-year preference continue to pass.
- A no-overlap test still emits `:insufficient-common-history` rather than silently inventing data.
- Setup readiness tests distinguish `:aligned` from `:loaded-but-misaligned`.
- View tests prove selected vault rows no longer show green `sufficient` when the universe has loaded raw histories but insufficient shared history.

Pipeline acceptance:

- A stubbed run pipeline with the three-vault fixture history bundle calls the worker run dependency after history loading.
- The progress panel can advance past `fetch returns matrix`.

Browser acceptance:

- On `/portfolio/optimize/new`, a user can select the three motivating vaults and click `Run Optimization`.
- The setup universe table does not mislabel a misaligned universe as fully sufficient.
- Browser validation exits cleanly and `npm run browser:cleanup` reports no lingering sessions.

Required gates after code changes:

- `npm run check`
- `npm test`
- `npm run test:websocket`

## Idempotence and Recovery

The planned changes are additive and local to optimizer history normalization, history alignment, setup readiness, and tests. The common-window fallback should be deterministic for the same input bundle, so re-running the optimizer with unchanged vault details should select the same alignment source.

If the common-window fallback creates a regression for market-and-vault mixed universes, keep the candidate generation tests but narrow fallback activation to vault-only or vault-dominated scenarios while recording the reason in the Decision Log. If the setup row status helper becomes too expensive to call on every render, memoize the readiness result at the setup view boundary instead of duplicating warning logic in the view.

No destructive migration is required. Do not run `git pull --rebase` or `git push` unless the user explicitly requests remote sync.

## Artifacts and Notes

Live reproduction from 2026-05-03:

    Hyperliquidity Provider (HLP)
    address: 0xdfc24b077bc1425ad1dea75bcb6f8158e10df303
    selected source under current logic: derived-one-year-from-all-time
    daily points: 29
    first: 2025-05-03
    last: 2026-05-03

    Growi HF
    address: 0x1e37a337ed460039d1b15bd3bc489de789768d5e
    selected source under current logic: derived-one-year-from-all-time
    daily points: 54
    first: 2025-05-03
    last: 2026-05-03

    [ Systemic Strategies ] HyperGrowth
    address: 0xd6e56265890b76413d1d527eb9b75e334c0c5b42
    selected source under current logic: direct month
    daily points: 32
    first: 2026-04-02
    last: 2026-05-03

    exact common count: 0
    daily common count: 1
    daily common dates: 2026-05-03

Current code reference points:

- `src/hyperopen/portfolio/optimizer/application/history_loader/alignment.cljs`: owns common calendar selection, daily fallback, warnings, and return-series construction.
- `src/hyperopen/portfolio/optimizer/application/history_loader/normalization.cljs`: owns conversion of candle, funding, and vault detail payloads into normalized rows.
- `src/hyperopen/portfolio/metrics/history.cljs`: owns `preferred-vault-summary`, which currently picks one summary per vault without seeing the rest of the selected universe.
- `src/hyperopen/portfolio/optimizer/application/setup_readiness.cljs`: owns blocking readiness reasons and user-facing error copy.
- `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`: owns the selected universe row history chip that currently says `sufficient` when raw vault details exist.
- `src/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline.cljs`: owns the one-click run pipeline that fetches history and then rebuilds readiness before requesting the worker run.

Related prior plan:

- `docs/exec-plans/completed/2026-04-29-portfolio-optimizer-vault-universe.md` introduced vault instruments and intentionally converted vault detail portfolio summaries into optimizer return history. This plan is a follow-up to make that history selection robust when selected vaults have different viable summary windows.

## Interfaces and Dependencies

No new third-party dependency is required.

Keep these public or established functions stable:

    hyperopen.portfolio.optimizer.application.history-loader/build-history-request-plan
    hyperopen.portfolio.optimizer.application.history-loader/align-history-inputs
    hyperopen.portfolio.optimizer.application.request-builder/build-engine-request
    hyperopen.portfolio.optimizer.application.setup-readiness/build-readiness
    hyperopen.portfolio.optimizer.application.setup-readiness/readiness-error-message

Add focused helper functions only where they express domain concepts:

    hyperopen.portfolio.optimizer.application.history-loader.normalization/vault-history-candidates
    Input: normalized vault details map
    Output: vector of candidate maps with :source, :window, and :history keys

    hyperopen.portfolio.optimizer.application.setup-readiness/history-status-by-instrument
    Input: readiness map returned by build-readiness
    Output: map of instrument id to status keyword for setup display

The solver should continue receiving the same core request shape. The fallback should only change `:history`, `:universe`, and explanatory history metadata when the existing preferred histories cannot produce enough shared observations.

Plan revision note: 2026-05-03 20:21Z - Initial plan created from investigation of the HLP, Growi HF, and Systemic HyperGrowth vault optimizer failure. The plan chooses bounded common-window fallback as the first fix and includes the misleading setup chip as part of acceptance.
