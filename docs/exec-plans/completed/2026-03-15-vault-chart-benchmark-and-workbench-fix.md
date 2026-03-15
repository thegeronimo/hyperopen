# Vault Chart Benchmarking and Workbench Recovery

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Related `bd` issue: `hyperopen-jiai` ("Fix vault chart workbench render failure and verify hover performance").

## Purpose / Big Picture

Vault chart hover still feels worse than the smoother Portfolio returns chart in manual testing, but the current Vault workbench scenes fail before the chart mounts, so there is no trustworthy way to measure the regression yet. After this change, the Vault workbench will render again, the Vault chart will be benchmarkable in Chromium against the pre-hover-smoothness baseline, and any remaining Vault-specific hover problems will be fixed in the shared D3 runtime or Vault chart wiring.

A user can verify the result by opening the Vault detail workbench scenes, seeing them render instead of crashing, and then moving the pointer across the chart without the crosshair or tooltip feeling materially worse than Portfolio. The browser benchmark must show whether the remaining problem was only the broken workbench, a Vault-specific runtime issue, or both.

## Progress

- [x] (2026-03-15 20:46Z) Created and claimed `bd` issue `hyperopen-jiai` for the Vault workbench failure and Vault-specific hover verification.
- [x] (2026-03-15 20:48Z) Confirmed the current working tree already contains the uncommitted D3 hover-smoothness runtime changes plus the completed plan for `hyperopen-yns5`; this plan builds on that state and must not discard it.
- [x] (2026-03-15 20:50Z) Reproduced that every current Vault workbench scene fails before mounting the chart, with the visible workbench error `Vector's key for assoc must be a number`.
- [x] (2026-03-15 20:51Z) Fixed `layout/interactive-shell` so it passes attrs into `merge-class` in the correct order and added `/hyperopen/test/hyperopen/workbench/support/layout_test.cljs` to lock the contract.
- [x] (2026-03-15 20:52Z) Verified in Chromium that all Vault workbench scenes render again and mount the Vault D3 host node instead of failing before chart render.
- [x] (2026-03-15 20:53Z) Benchmarked current Vault versus current Portfolio and confirmed the current workbench hover hot path is effectively identical between the two surfaces.
- [x] (2026-03-15 20:53Z) Benchmarked current Vault versus baseline commit `38deca7a` after applying the same workbench-shell fix to the temporary baseline worktree used only for benchmarking.
- [x] (2026-03-15 20:55Z) Ran `npm run check`, `npm test`, and `npm run test:websocket` successfully on the final code state.
- [x] (2026-03-15 20:55Z) Filed follow-up issue `hyperopen-3msx` to profile Vault hover with production-like data density, because the existing workbench scenes only contain six samples and may not reproduce the route-level lag reported by the user.
- [x] (2026-03-15 20:55Z) Closed `hyperopen-jiai` and moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: The current Vault workbench failure happens before any chart runtime code can execute.
  Evidence: Browser inspection shows every `hyperopen.workbench.scenes.vaults.detail-chart-scenes/*` scene rendering `Failed to render ... Vector's key for assoc must be a number`, with the stack pointing through `hyperopen.workbench.support.layout/merge_class` and `hyperopen.workbench.scenes.vaults.detail_chart_scenes/chart_scene`.

- Observation: Portfolio hover improvements are already measurable in the browser on the current working tree, but they do not answer the user's Vault complaint directly.
  Evidence: The prior browser benchmark on `returns-with-benchmarks` showed near-zero crosshair alignment error and zero same-index tooltip child-list churn on Portfolio, while Vault could not yet be measured because the scene never mounted.

- Observation: The baseline Vault workbench could not be benchmarked until the same shell helper bug was patched in the temporary baseline worktree.
  Evidence: Baseline commit `38deca7a` still failed every interactive Vault scene with the same `Vector's key for assoc must be a number` workbench error until `/tmp/hyperopen-benchmark-38deca7a/portfolio/hyperopen/workbench/support/layout.cljs` was patched for measurement.

- Observation: Once the Vault workbench scene mounts, current Vault and current Portfolio have nearly identical hover-path measurements in the workbench.
  Evidence: Current Portfolio measured width `574`, same-index dispatch `0.9ms`, mutation count `0`, and average crosshair error `0.000008px`; current Vault measured width `635`, same-index dispatch `1.1ms`, mutation count `0`, and average crosshair error `0.000008px`.

- Observation: Current Vault is dramatically better than the pre-hover-smoothness baseline on the same workbench scene.
  Evidence: Baseline Vault measured same-index dispatch `47.7ms`, mutation count `1400`, average crosshair error `30.48px`, and max error `63.5px`, while current Vault measured `1.0ms`, `0`, `0.000008px`, and `0.000020px` respectively.

- Observation: The existing Vault workbench scene is not a high-fidelity reproduction of real route load.
  Evidence: `/hyperopen/portfolio/hyperopen/workbench/scenes/vaults/detail_chart_scenes.cljs` defines only six sample points per series, which is far sparser than a production-like history chart.

## Decision Log

- Decision: Treat the Vault workbench crash as the first blocker and fix it before making any further performance claims about Vault.
  Rationale: A broken workbench invalidates any attempt to compare current versus baseline Vault behavior and hides whether the remaining jank is in the shared runtime, the Vault chart spec, or the workbench harness itself.
  Date/Author: 2026-03-15 / Codex

- Decision: Benchmark current Vault behavior against baseline commit `38deca7a`, which predates the latest hover-smoothness runtime optimizations but already uses the D3 renderer.
  Rationale: The user is asking whether the recent smoothness changes helped Vault, not whether D3 is better than the original SVG migration. Comparing to `38deca7a` isolates the latest hover-loop work.
  Date/Author: 2026-03-15 / Codex

- Decision: Fix the workbench-shell bug in the repository and patch the same bug into the temporary baseline worktree only for measurement.
  Rationale: The shell bug is outside the chart runtime under test. Applying the same harness fix to the temporary baseline keeps the comparison focused on the hover-loop changes rather than a known scene bootstrap failure.
  Date/Author: 2026-03-15 / Codex

- Decision: Stop after the workbench fix and benchmark pass instead of making more D3 runtime changes now.
  Rationale: The current Vault workbench numbers do not show a Vault-specific hot-path regression relative to Portfolio. The remaining user-reported lag is more likely tied to production-like data density or route context, so that work was filed separately as `hyperopen-3msx`.
  Date/Author: 2026-03-15 / Codex

## Outcomes & Retrospective

Implemented and validated. The actual repository change was a small workbench-shell fix in `/hyperopen/portfolio/hyperopen/workbench/support/layout.cljs`, plus a direct test in `/hyperopen/test/hyperopen/workbench/support/layout_test.cljs`. That fix restored every Vault workbench chart scene, which made direct Chromium benchmarking possible again.

The benchmark result did not justify additional Vault-specific runtime work in this issue. In the current workbench, Vault and Portfolio are effectively equal on the shared hover hot path, and current Vault is dramatically faster than the pre-hover-smoothness baseline. The unresolved gap is fidelity: the workbench scenes use only six samples, so they may not reproduce the route-level lag reported in manual testing. That follow-up is tracked separately in `hyperopen-3msx`.

Overall complexity decreased. The codebase now has one less hidden workbench failure mode, and the fix removed an argument-order bug instead of adding more special-case logic. No new Vault-only D3 behavior was introduced.

## Context and Orientation

The shared D3 chart runtime lives in `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` and is mounted by `/hyperopen/src/hyperopen/views/portfolio_view.cljs` for Portfolio and `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs` for Vault detail. The Vault workbench scenes live in `/hyperopen/portfolio/hyperopen/workbench/scenes/vaults/detail_chart_scenes.cljs`, and the common workbench shell helpers live in `/hyperopen/portfolio/hyperopen/workbench/support/layout.cljs`.

The Vault workbench scenes use `layout/interactive-shell` to wrap a local atom-backed store. The current browser stack shows that the crash happens inside `merge_class`, which means the workbench shell is trying to merge class attributes into a shape it does not support before the chart even renders. Until that failure is fixed, browser-inspection cannot measure Vault hover behavior because the D3 host node never exists in the DOM.

Baseline commit `38deca7a` is the local commit created earlier in this session after the initial D3 migration and before the hover-smoothness follow-up. A detached worktree at `/tmp/hyperopen-benchmark-38deca7a` is available for before/after browser comparison if it still exists; if not, it can be recreated from the same commit.

## Plan of Work

Milestone 1 restores the Vault workbench scenes. Inspect `/hyperopen/portfolio/hyperopen/workbench/support/layout.cljs` and `/hyperopen/portfolio/hyperopen/workbench/scenes/vaults/detail_chart_scenes.cljs` to determine why the Vault scenes are passing a class value that `merge_class` cannot merge. Fix the workbench wrapper or scene call site so all Vault chart scenes render again without changing production chart behavior. Add or update tests that exercise the workbench rendering path if coverage exists for this layer.

Milestone 2 benchmarks Vault hover behavior in Chromium. Use the current worktree and the baseline `38deca7a` worktree to measure the same Vault scene with the same pointer-move harness that was used for Portfolio. Collect at least crosshair alignment error, same-index tooltip DOM churn, and synchronous pointer-event dispatch cost so the result is comparable with Portfolio.

Milestone 3 addresses any remaining Vault-specific jank. If the benchmark shows Vault is still materially worse than Portfolio after the workbench fix, inspect differences in the Vault chart spec, plot width, tooltip density, and runtime code paths. Prefer a shared runtime fix in `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` or a helper fix in `/hyperopen/src/hyperopen/views/chart/d3/model.cljs`. Only add Vault-specific behavior in `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs` if the issue is truly surface-specific. This milestone ended with no additional runtime change because the workbench benchmark did not show a current Vault-specific regression.

Milestone 4 validates the final state. Run the required test gates, update this plan with benchmark evidence and decisions, close `hyperopen-jiai`, and move the plan to `/hyperopen/docs/exec-plans/completed/`.

## Concrete Steps

1. Read and compare the workbench support and Vault workbench scene files:

   `/hyperopen/portfolio/hyperopen/workbench/support/layout.cljs`
   `/hyperopen/portfolio/hyperopen/workbench/scenes/vaults/detail_chart_scenes.cljs`

2. Reproduce the browser failure through the workbench route and capture the exact stack and visible error text.

3. Fix the workbench rendering path with minimal scope, then verify that at least one Vault scene mounts a chart host with `data-role="vault-detail-chart-d3-host"`.

4. Run the browser benchmark on current and baseline Vault scenes and record the measured metrics in this plan.

5. If needed, patch the shared D3 runtime or Vault chart integration, extend tests, and rerun:

   `npm run check`
   `npm test`
   `npm run test:websocket`

## Validation and Acceptance

The work is accepted when all of the following are true:

1. The Vault workbench scenes render instead of failing with `Vector's key for assoc must be a number`.
2. Browser inspection can find the Vault D3 host node and drive synthetic pointer movement on it.
3. A before/after Vault benchmark against baseline `38deca7a` is recorded with comparable metrics to the earlier Portfolio benchmark.
4. If Vault still measured worse than Portfolio after the workbench fix, the remaining cause is fixed and the follow-up benchmark shows improvement.
5. `npm run check`, `npm test`, and `npm run test:websocket` pass on the final code state.

## Idempotence and Recovery

The browser benchmark steps are safe to repeat. The baseline worktree can be recreated from commit `38deca7a` if `/tmp/hyperopen-benchmark-38deca7a` is missing. If the workbench fix regresses the workbench shell, recovery is to revert only the workbench-specific change and keep the benchmark harness/results so the failure remains reproducible. Production Vault chart behavior must not be changed merely to make the workbench pass.

## Artifacts and Notes

Pre-fix browser evidence from the current worktree:

  - Visible workbench error: `Failed to render 'Benchmark search open'`
  - Error text: `Vector's key for assoc must be a number.`
  - Stack excerpt: `hyperopen.workbench.support.layout/merge_class` -> `hyperopen.workbench.support.layout/interactive_shell` -> `hyperopen.workbench.scenes.vaults.detail_chart_scenes/chart_scene`

Current versus current workbench comparison:

  - Portfolio: width `574`, same-index dispatch `0.9ms`, same-index mutations `0`, average crosshair error `0.000008px`
  - Vault: width `635`, same-index dispatch `1.1ms`, same-index mutations `0`, average crosshair error `0.000008px`

Current Vault versus baseline Vault comparison:

  - Current Vault: same-index dispatch `1.0ms`, same-index mutations `0`, average crosshair error `0.000008px`, max error `0.000020px`
  - Baseline Vault: same-index dispatch `47.7ms`, same-index mutations `1400`, average crosshair error `30.48px`, max error `63.5px`

Validation commands on the final code state:

  - `npm run check`
  - `npm test`
  - `npm run test:websocket`

## Interfaces and Dependencies

The relevant workbench interface is `layout/interactive-shell` in `/hyperopen/portfolio/hyperopen/workbench/support/layout.cljs`. After the fix, it must continue accepting the existing scene usage pattern from both Portfolio and Vault workbench scenes without forcing production view changes.

The relevant chart runtime interface remains the same D3 chart spec consumed by `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs`. If a runtime change is needed, it must continue to support both Portfolio and Vault surfaces without changing public action names or benchmark-search behavior.

Plan revision note: 2026-03-15 20:50Z - Initial plan authored for `hyperopen-jiai` after reproducing the Vault workbench crash and confirming that direct Vault browser benchmarking is blocked until the scene harness is repaired.
Plan revision note: 2026-03-15 20:55Z - Updated after fixing `interactive-shell`, validating all Vault scenes, benchmarking current versus baseline Vault, filing `hyperopen-3msx` for higher-fidelity route profiling, and completing all required test gates.
