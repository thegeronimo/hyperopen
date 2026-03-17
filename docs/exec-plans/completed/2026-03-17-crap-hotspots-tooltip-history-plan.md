# Reduce CRAP In Tooltip And Vault History Helpers

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Linked live work: `hyperopen-08yd` ("Reduce CRAP in tooltip and vault history helpers").

## Purpose / Big Picture

After this work, the account-equity tooltip, asset-selector tooltip, and the vault detail history-point parsing paths should be easier to reason about and directly covered by tests instead of carrying high CRAP because they combine multiple decision branches with no direct coverage. A contributor should be able to run the targeted tests and see that tooltip positioning and history-row parsing still behave the same while the duplicated branch-heavy code is reduced or removed.

The intended user-visible result is no visual or behavioral change. The observable result is quality: the hotspot functions either disappear in favor of shared helpers or become simple wrappers with direct tests proving the supported positions and row shapes.

## Progress

- [x] (2026-03-17 19:59Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/QUALITY_SCORE.md`, `/hyperopen/docs/MULTI_AGENT.md`, and the required frontend policy docs.
- [x] (2026-03-17 19:59Z) Created and claimed `hyperopen-08yd` for this CRAP-reduction task.
- [x] (2026-03-17 20:00Z) Audited the hotspot sources and adjacent helpers in `/hyperopen/src/hyperopen/views/account_equity_view.cljs`, `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`, `/hyperopen/src/hyperopen/vaults/detail/performance.cljs`, `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs`, `/hyperopen/src/hyperopen/portfolio/metrics/parsing.cljs`, and `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`.
- [x] (2026-03-17 20:02Z) Added focused regression coverage in the existing view and vault detail test suites for tooltip positioning plus mixed row-shape history normalization.
- [x] (2026-03-17 20:03Z) Replaced inline tooltip `case` branching with local position lookup maps in the two hotspot view namespaces while preserving rendered structure and supported positions.
- [x] (2026-03-17 20:03Z) Removed the duplicate `history-point` implementations from the two vault detail namespaces and routed parsing through `hyperopen.portfolio.metrics/history-point-time-ms` and `history-point-value`.
- [x] (2026-03-17 20:13Z) Completed validation with `npm test`, `npm run test:websocket`, `npm run check`, and `npm run coverage`, then recorded module-level CRAP results for the touched files.
- [x] (2026-03-17 20:14Z) Performed adversarial self-review, restored fail-fast behavior for unsupported tooltip positions, reran the required gates, and prepared this plan to move to completed.

## Surprises & Discoveries

- Observation: the vault detail namespaces already duplicate parsing and chart-history helpers that exist in shared seams.
  Evidence: `/hyperopen/src/hyperopen/portfolio/metrics/parsing.cljs` exports `history-point-time-ms` and `history-point-value`, and `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs` already covers nearby normalization branches with direct tests.

- Observation: the two tooltip hotspots are structurally similar, but not identical enough to justify a large shared UI abstraction as the first move.
  Evidence: both use the same position-switch pattern for panel and arrow classes, but the account-equity tooltip accepts `trigger` plus text and uses wider content styling, while the asset-selector tooltip accepts a `[trigger text]` tuple and narrower content styling.

- Observation: direct tooltip tests reduced complexity-driven CRAP, but the coverage tooling still attributes `0.00` function coverage to the two tooltip vars.
  Evidence: the final CRAP reports show `hyperopen.views.account-equity-view/tooltip` at `crap=6.00 coverage=0.00 complexity=2` and `hyperopen.views.asset-selector-view/tooltip` at `crap=6.00 coverage=0.00 complexity=2` even though the new tests in `/hyperopen/test/hyperopen/views/account_equity_view_test.cljs` and `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs` call those vars directly.

- Observation: the original vault `history-point` hotspots disappeared from the CRAP report once the duplicate parsers were deleted and the shared metrics parsing seam was used.
  Evidence: the final module reports for `/hyperopen/src/hyperopen/vaults/detail/performance.cljs` and `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs` no longer list any `history-point` function; the hottest remaining benchmark function is `vault-snapshot-range-keys`, not the deleted parser.

## Decision Log

- Decision: Execute this task centrally without worker agents.
  Rationale: the write surface is small, the safest change is coherent reuse of existing helpers across overlapping files, and the repo-local multi-agent contract would add overhead without a clear isolation boundary.
  Date/Author: 2026-03-17 / Codex

- Decision: Prefer deletion and reuse in the vault detail modules instead of introducing a new parsing namespace.
  Rationale: a canonical shared parsing seam already exists under `/hyperopen/src/hyperopen/portfolio/metrics/parsing.cljs`, so a new helper namespace would widen surface area for little benefit.
  Date/Author: 2026-03-17 / Codex

- Decision: Reduce tooltip complexity with data-driven position maps instead of a broader generic tooltip abstraction.
  Rationale: this removes the branch-heavy `case` logic that drives CRAP while keeping the visible structure, styling, and call sites local to each view namespace.
  Date/Author: 2026-03-17 / Codex

- Decision: Preserve fail-fast semantics for unsupported tooltip positions after the refactor.
  Rationale: the original `case` implementation would throw on an unexpected position token, so silently falling back would have changed behavior in a hard-to-detect way.
  Date/Author: 2026-03-17 / Codex

## Outcomes & Retrospective

This change reduced the targeted hotspot complexity without broadening the architecture. The two tooltip functions stayed in their existing namespaces, but their branch-heavy position handling moved into static lookup maps. The two vault detail `history-point` helpers were deleted entirely and replaced by the existing shared parsing seam under `/hyperopen/src/hyperopen/portfolio/metrics.cljs`.

The requested hotspot reductions were achieved:

- `hyperopen.views.account-equity-view/tooltip`: from CRAP `110.00` / complexity `10` to CRAP `6.00` / complexity `2`
- `hyperopen.views.asset-selector-view/tooltip`: from CRAP `110.00` / complexity `10` to CRAP `6.00` / complexity `2`
- `hyperopen.vaults.detail.performance/history-point`: removed from the module; the module now has `crappy_functions=0`
- `hyperopen.vaults.detail.benchmarks/history-point`: removed from the module; the hotspot no longer appears in the module report

Validation passed on the final revision:

- `npm test`
- `npm run test:websocket`
- `npm run check`
- `npm run coverage`

Browser-QA accounting for the touched view namespaces is `BLOCKED` for all six passes (`visual`, `native-control`, `styling-consistency`, `interaction`, `layout-regression`, `jank/perf`) because this task was a behavior-preserving internal refactor with no intended visual delta and no live browser review session or route-specific design review artifact was run. The remaining blind spot is that an unintended visual regression would only be caught by follow-up browser QA, not by the current test suite.

## Context and Orientation

The first hotspot is `tooltip` in `/hyperopen/src/hyperopen/views/account_equity_view.cljs`. It renders a hover tooltip around metric labels in the account-equity surface. The current implementation contains two `case` branches keyed by tooltip position: one for the floating panel and one for the arrow. The second hotspot is another `tooltip` in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`, used for the funding-rate hover affordance in the asset selector. It repeats the same branch-heavy position logic with slightly different styling.

The third and fourth hotspots are both named `history-point`, one in `/hyperopen/src/hyperopen/vaults/detail/performance.cljs` and one in `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs`. They parse row-shaped history data that may arrive as vectors like `[time value]` or as maps with alternate keys such as `:time`, `:timestamp`, `:time-ms`, `:timeMs`, `:ts`, `:t`, `:value`, `:account-value`, `:accountValue`, and `:pnl`. These branches are already represented in `/hyperopen/src/hyperopen/portfolio/metrics/parsing.cljs` via `history-point-time-ms` and `history-point-value`.

The existing tests already provide the nearby surfaces that should be extended instead of creating unrelated suites:

- `/hyperopen/test/hyperopen/views/account_equity_view_test.cljs`
- `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`
- `/hyperopen/test/hyperopen/vaults/detail/performance_test.cljs`
- `/hyperopen/test/hyperopen/vaults/detail/benchmarks_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio/vm/history_helpers_test.cljs`

This work must preserve visible behavior. The account-equity and asset-selector UIs should look and behave the same, and the vault detail chart/model paths should continue accepting the same row shapes. Because the touched files live under `/hyperopen/src/hyperopen/views/**`, the frontend QA policy applies; since the intended result is no visual change, the final handoff must explicitly account for the required browser-QA passes rather than implying a visual review happened when it did not.

## Plan of Work

First, extend the existing tests so the hot paths are directly exercised. In `/hyperopen/test/hyperopen/views/account_equity_view_test.cljs`, add a focused tooltip test that calls `hyperopen.views.account-equity-view/tooltip` with default and non-default positions and asserts the expected panel and arrow classes remain attached. In `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`, add an equivalent focused test for the asset-selector tooltip wrapper.

Second, reduce the tooltip complexity in place. In each tooltip namespace, replace the inline `case` expressions with private lookup maps or small helper functions that return the panel and arrow classes for a given position. Keep the rendered tag structure, copy handling, and per-view styling unchanged. The goal is to turn the branch-heavy render function into a thin composition over static data.

Third, remove duplicated history parsing branches from the vault detail modules. In `/hyperopen/src/hyperopen/vaults/detail/performance.cljs`, route `history-points` and `rows->chart-points` through `hyperopen.portfolio.metrics/history-point-time-ms` and `hyperopen.portfolio.metrics/history-point-value` so the local `history-point` helper can be deleted. In `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs`, do the same for `rows->chart-points` and `benchmark-candle-points`. Extend the existing vault detail tests so both vector-shaped and map-shaped rows continue to normalize as expected.

Finally, run the focused test namespaces first, then the required repo gates, and then a CRAP verification pass using the repo toolchain once fresh coverage data exists. Update this plan with results, move it to completed when the work is accepted, and close `hyperopen-08yd`.

## Concrete Steps

Run these commands from `/hyperopen`:

1. Add and run targeted tests while refactoring:

       npm test -- --namespace hyperopen.views.account-equity-view-test
       npm test -- --namespace hyperopen.views.asset-selector-view-test
       npm test -- --namespace hyperopen.vaults.detail.performance-test
       npm test -- --namespace hyperopen.vaults.detail.benchmarks-test

2. Run required repository gates after the patch is stable:

       npm run check
       npm test
       npm run test:websocket

3. Generate fresh coverage and inspect the affected CRAP hotspots:

       npm run coverage
       bb tools/crap_report.clj --module src/hyperopen/views/account_equity_view.cljs
       bb tools/crap_report.clj --module src/hyperopen/views/asset_selector_view.cljs
       bb tools/crap_report.clj --module src/hyperopen/vaults/detail/performance.cljs
       bb tools/crap_report.clj --module src/hyperopen/vaults/detail/benchmarks.cljs

Expected result: the previous hotspot functions either no longer appear by name because the duplicated helper was deleted, or they appear with materially lower complexity and non-zero coverage.

## Validation and Acceptance

Acceptance is behavior-oriented:

- Calling the account-equity tooltip with `nil`, `"bottom"`, `"left"`, and `"right"` positions still yields the expected placement and arrow classes, with `"top"` remaining the default.
- Calling the asset-selector tooltip with the same positions still yields the expected placement and arrow classes around the funding-rate affordance.
- `performance/chart-series-data` and the benchmark chart-building paths still accept mixed vector and map row shapes and ignore invalid rows.
- `npm run check`, `npm test`, and `npm run test:websocket` all pass.
- The post-change CRAP report shows the named hotspots reduced from the original user-supplied values.

## Idempotence and Recovery

These changes are safe to rerun because they are pure source edits plus deterministic tests. If a refactor breaks a shape assumption, rerun the focused namespace tests to identify the failing normalization or tooltip branch before rerunning the full gates. If the CRAP report does not move enough after tests are added, prefer deleting duplicate helpers or reducing local branch count further instead of adding broader abstractions.

## Artifacts and Notes

The final update to this plan should include:

- targeted test command results,
- required gate results,
- the post-change CRAP report snippets for the four touched modules,
- and a short browser-QA accounting note marking the six required passes as `BLOCKED` or equivalent no-visual-change status if live browser review is not materially applicable for this internal refactor.

## Interfaces and Dependencies

At the end of this work:

- `/hyperopen/src/hyperopen/views/account_equity_view.cljs` and `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` should still export their existing `tooltip` vars with unchanged call signatures.
- `/hyperopen/src/hyperopen/vaults/detail/performance.cljs` and `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs` should depend on `hyperopen.portfolio.metrics/history-point-time-ms` and `hyperopen.portfolio.metrics/history-point-value` for shared row parsing rather than duplicating those parsing branches locally.
- No public interface should change, and no new cross-layer cycles should be introduced.

Revision note: created on 2026-03-17 to execute `hyperopen-08yd` as a focused CRAP-reduction plan covering tooltip position branching and vault history parsing duplication.
Revision note: updated on 2026-03-17 after implementation to record the final validation results, the fail-fast tooltip-position correction from self-review, and the measured CRAP reductions before moving this plan to completed.
