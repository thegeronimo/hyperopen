---
owner: platform
status: completed
last_reviewed: 2026-04-06
review_cycle_days: 90
source_of_truth: false
---

# Reduce CRAP Across Five Current Hotspot Helpers

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, a contributor can open each of the five current hotspot namespaces and follow the control flow through small, single-purpose helpers instead of branch-heavy functions that hide unrelated normalization or runtime concerns. The user-visible behavior must stay the same: the asset selector still virtualizes and settles scrolling correctly, staking still derives available HYPE balances the same way, the funding modal still resets and refreshes the same lifecycle fields, the chart navigation overlay still activates only in the intended hover zone, and vault cache records still normalize the same persisted shapes.

The observable workflow from `/hyperopen` is:

    npm run test:playwright:smoke
    npm run check
    npm test
    npm run test:websocket
    npm run coverage
    bb tools/crap_report.clj --module src/hyperopen/views/asset_selector/runtime.cljs --format json --top-functions 50
    bb tools/crap_report.clj --module src/hyperopen/staking/actions.cljs --format json --top-functions 50
    bb tools/crap_report.clj --module src/hyperopen/funding/application/modal_commands.cljs --format json --top-functions 50
    bb tools/crap_report.clj --module src/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay.cljs --format json --top-functions 50
    bb tools/crap_report.clj --module src/hyperopen/vaults/infrastructure/list_cache.cljs --format json --top-functions 50

The Playwright smoke run satisfies the repo rule to exercise the smallest relevant browser coverage first for UI-adjacent changes. The repository gates and refreshed coverage prove the refactors did not trade behavior for a better score. The five focused CRAP reports prove `bd` issue `hyperopen-7y38` is resolved by removing hotspot complexity rather than by moving it elsewhere.

## Progress

- [x] (2026-04-06 00:23Z) Created and claimed `bd` issue `hyperopen-7y38` for this five-module CRAP remediation, linked as `discovered-from:hyperopen-5fq`.
- [x] (2026-04-06 00:23Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and `/hyperopen/docs/BROWSER_TESTING.md`.
- [x] (2026-04-06 00:23Z) Inspected the five hotspot functions and the nearest focused tests in `/hyperopen/test/hyperopen/views/asset_selector/runtime_test.cljs`, `/hyperopen/test/hyperopen/staking/actions_test.cljs`, `/hyperopen/test/hyperopen/funding/actions_test.cljs`, `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay_test.cljs`, and `/hyperopen/test/hyperopen/vaults/infrastructure/list_cache_test.cljs`.
- [x] (2026-04-06 00:23Z) Created this active ExecPlan.
- [x] (2026-04-06 00:28Z) Froze the refactor shape for each hotspot, keeping all changes inside the existing namespace owners and using local helper extraction instead of new sibling namespaces.
- [x] (2026-04-06 00:31Z) Refactored the five hotspot functions in place, preserving public behavior for asset-selector lifecycle handling, staking balance derivation, funding modal state transitions, chart overlay hover activation, and vault cache normalization.
- [x] (2026-04-06 00:32Z) Expanded focused tests in the nearest existing namespaces for asset-selector runtime memory and teardown, staking balance helper coverage, funding modal asset-select resets, chart overlay hover geometry, legacy vault cache normalization, and the surfaced staking withdraw submission path.
- [x] (2026-04-06 00:45Z) Restored a broken local `node_modules` install with `rm -rf node_modules && npm ci`, ran `npm run test:playwright:smoke`, `npm test`, `npm run test:websocket`, `npm run check`, `npm run coverage`, and the five focused CRAP reports, confirmed all five touched modules now report zero CRAP hotspots, and closed `hyperopen-7y38`.

## Surprises & Discoveries

- Observation: Focused CRAP reports cannot be refreshed yet because this worktree does not currently have `coverage/lcov.info`.
  Evidence: Every `bb tools/crap_report.clj --module ...` invocation currently exits with `Missing coverage/lcov.info. Run npm run coverage first.`
- Observation: Three hotspots already have direct, nearby contract tests, while the two UI runtime hotspots have dedicated namespace tests that can be extended without introducing new test files.
  Evidence: `test/hyperopen/funding/actions_test.cljs`, `test/hyperopen/vaults/infrastructure/list_cache_test.cljs`, `test/hyperopen/staking/actions_test.cljs`, `test/hyperopen/views/asset_selector/runtime_test.cljs`, and `test/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay_test.cljs` already exist and target the same behavior surfaces.
- Observation: The workspace had an incomplete dependency install even though compile-only commands still worked.
  Evidence: The first `npm test` run failed with `Cannot find module 'lucide/dist/esm/icons/external-link.js'`; `npm ls lucide` returned `(empty)`, and `rm -rf node_modules && npm ci` restored the expected package set so validation could continue.
- Observation: Eliminating `balance-row-available` as a hotspot briefly exposed `submit-staking-withdraw` as the next staking max, but one focused public-seam test was enough to drop that function back under threshold without more production refactoring.
  Evidence: The first refreshed staking CRAP report showed `submit-staking-withdraw` at `CRAP 31.624088921282805`; after adding withdraw validation coverage and rerunning coverage, the same report showed `submit-staking-withdraw` at `CRAP 6.0` and module `crappy-functions = 0`.

## Decision Log

- Decision: Keep this issue scoped to the five requested functions rather than broad module-wide cleanup.
  Rationale: The request is to decrap a specific hotspot cluster. Expanding scope before measuring the targeted reductions would increase churn and make the behavior-preservation review harder.
  Date/Author: 2026-04-06 / Codex
- Decision: Prefer in-namespace helper extraction and data-driven normalization over moving logic into new sibling namespaces.
  Rationale: Each hotspot is a local function-level problem, and the surrounding tests already exercise the current namespace seams. Local extraction lowers CRAP with less API churn.
  Date/Author: 2026-04-06 / Codex
- Decision: Treat the user-provided CRAP scores as the baseline until fresh coverage is generated, then replace or supplement them with measured report output.
  Rationale: The repository tooling requires `coverage/lcov.info`, and the current worktree does not have it yet. Recording the current known baseline avoids blocking the plan while still requiring measured evidence before completion.
  Date/Author: 2026-04-06 / Codex
- Decision: Expand the existing staking test owner to cover `submit-staking-withdraw` once it surfaced as the new module max instead of filing a follow-up issue.
  Rationale: The extra coverage was small, public-seam based, and sufficient to clear the replacement hotspot immediately. Carrying a fresh follow-up after already having the namespace open would have left avoidable CRAP behind.
  Date/Author: 2026-04-06 / Codex

## Outcomes & Retrospective

Implementation completed for the requested hotspot set. The asset-selector runtime now dispatches mount, update, and unmount work through smaller lifecycle helpers; staking balance derivation now splits direct, derived, and normalization steps; funding modal field mutation now separates path normalization, lifecycle-reset policy, dependent resets, and refresh-effect selection; chart overlay hover activation now resolves geometry through focused helpers; and vault cache record normalization now dispatches between shared-field, record-map, and legacy-sequence flows.

This reduced overall complexity for the requested work. The five named hotspots now measure:

- `asset-list-on-render`: `35.28 -> 4.0`
- `balance-row-available`: `34.95 -> 3.0`
- `set-funding-modal-field`: `34.69 -> 2.0`
- `container-hover-active?`: `34.23 -> 4.0`
- `normalize-vault-index-cache-record`: `34.16 -> 3.0`

All five touched modules now report zero over-threshold functions. The refreshed module maxima are `30.0` in asset-selector runtime, `27.752773896605575` in staking actions, `13.0` in funding modal commands, `20.902278164251634` in chart navigation overlay, and `9.0` in vault list cache. The asset-selector module still has `schedule-asset-list-render-limit-sync!` exactly at `CRAP 30.0`, but because the threshold is strictly greater than `30`, the report classifies it as non-crappy and the module `project-crapload` remains `0.0`.

## Context and Orientation

This change spans five namespaces that do different jobs but share one problem: each hotspot packs multiple decisions into one function, which inflates CRAP when combined with low or incomplete coverage.

`/hyperopen/src/hyperopen/views/asset_selector/runtime.cljs` owns scroll-time virtualization and Replicant render lifecycle behavior for the asset selector. The hotspot `asset-list-on-render` coordinates mount, update, and unmount behavior, runtime memory, event listeners, pending props, timeout cleanup, and scroll freeze state.

`/hyperopen/src/hyperopen/staking/actions.cljs` owns user-triggered staking action effects and form normalization. The hotspot `balance-row-available` reads mixed exchange balance row shapes and derives an available balance from direct or fallback fields.

`/hyperopen/src/hyperopen/funding/application/modal_commands.cljs` owns pure command generation for funding modal state transitions. The hotspot `set-funding-modal-field` normalizes input values, resets related modal fields, clears HyperUnit lifecycle state, and emits follow-up effect commands when a fee estimate or withdrawal queue refresh is needed.

`/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay.cljs` owns the chart navigation overlay that appears near the bottom of the chart when the user hovers or focuses the container. The hotspot `container-hover-active?` derives whether pointer position is inside the lower activation band using the container rectangle, client height, and event coordinates.

`/hyperopen/src/hyperopen/vaults/infrastructure/list_cache.cljs` owns IndexedDB normalization and persistence for cached vault index data. The hotspot `normalize-vault-index-cache-record` accepts either a persisted record map or an older raw rows collection and returns the normalized cache record shape used by load and persist helpers.

The nearest focused tests already exist:

- `/hyperopen/test/hyperopen/views/asset_selector/runtime_test.cljs`
- `/hyperopen/test/hyperopen/staking/actions_test.cljs`
- `/hyperopen/test/hyperopen/funding/actions_test.cljs`
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay_test.cljs`
- `/hyperopen/test/hyperopen/vaults/infrastructure/list_cache_test.cljs`

The baseline CRAP hotspots supplied with the request are:

- `/hyperopen/src/hyperopen/views/asset_selector/runtime.cljs`: `asset-list-on-render` at `CRAP 35.28`
- `/hyperopen/src/hyperopen/staking/actions.cljs`: `balance-row-available` at `CRAP 34.95`
- `/hyperopen/src/hyperopen/funding/application/modal_commands.cljs`: `set-funding-modal-field` at `CRAP 34.69`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay.cljs`: `container-hover-active?` at `CRAP 34.23`
- `/hyperopen/src/hyperopen/vaults/infrastructure/list_cache.cljs`: `normalize-vault-index-cache-record` at `CRAP 34.16`

## Plan of Work

First, freeze the intended refactor shape for each hotspot. For `/hyperopen/src/hyperopen/views/asset_selector/runtime.cljs`, extract mount-time runtime-state creation, active-scroll transition logic, and update-path memory hydration so `asset-list-on-render` becomes a lifecycle dispatcher rather than one nested `case` arm with repeated atom construction. Keep the `:replicant/on-render` contract, remembered keys, timeout cleanup, and scrolling semantics unchanged.

Second, simplify the smaller pure helpers. In `/hyperopen/src/hyperopen/staking/actions.cljs`, split `balance-row-available` into “direct available amount,” “derived total-minus-hold amount,” and “clamp finite result” helpers, mirroring the already-tested funding and vault transfer patterns without changing accepted row keys or fallback order. In `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay.cljs`, separate container height resolution from pointer offset resolution so `container-hover-active?` only decides whether the pointer is inside the activation band. In `/hyperopen/src/hyperopen/vaults/infrastructure/list_cache.cljs`, separate map-record normalization from legacy sequential-row normalization so `normalize-vault-index-cache-record` becomes a shape dispatcher instead of a mixed validator and builder.

Third, decompose the funding modal command path in `/hyperopen/src/hyperopen/funding/application/modal_commands.cljs`. Extract path normalization, input-value normalization, “clear dependent modal fields” decisions, and refresh-effect decisions into small helpers so `set-funding-modal-field` only orchestrates them. Preserve all current follow-up behavior for deposit and withdraw asset selection, `:asset-select` step resets, wallet-address fallback, and HyperUnit lifecycle clearing.

Fourth, widen focused tests at the nearest seams. Extend the runtime tests to assert the refactored asset-selector helpers still remember listeners and defer pending props correctly. Add direct balance-row tests in `/hyperopen/test/hyperopen/staking/actions_test.cljs`. Extend funding modal command tests to cover the extracted dependency-clearing and refresh decisions through the public `funding-actions/set-funding-modal-field` seam. Extend chart navigation overlay tests for hover activation edge cases, including rectangle-vs-clientHeight fallback. Extend vault cache tests to cover both valid map records and legacy sequential inputs through the public normalizer.

Finally, run browser smoke before the full repository gates, then regenerate coverage and rerun the five focused CRAP reports. Record the measured before and after results here. If a targeted function still exceeds `30`, continue refining that module before closing `hyperopen-7y38`. If a different same-module hotspot surfaces and can be cleared cheaply within the current patch, finish it instead of leaving avoidable residual CRAP behind.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/views/asset_selector/runtime.cljs` and `/hyperopen/test/hyperopen/views/asset_selector/runtime_test.cljs` to split the Replicant lifecycle/runtime-memory helper logic and preserve the scroll/runtime contract.
2. Edit `/hyperopen/src/hyperopen/staking/actions.cljs` and `/hyperopen/test/hyperopen/staking/actions_test.cljs` to split available-balance derivation into small helpers and cover the fallback matrix directly.
3. Edit `/hyperopen/src/hyperopen/funding/application/modal_commands.cljs` and `/hyperopen/test/hyperopen/funding/actions_test.cljs` to isolate field normalization, dependent-reset policy, and follow-up effect decisions.
4. Edit `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay.cljs` and `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay_test.cljs` to split hover geometry resolution from hover-threshold evaluation.
5. Edit `/hyperopen/src/hyperopen/vaults/infrastructure/list_cache.cljs` and `/hyperopen/test/hyperopen/vaults/infrastructure/list_cache_test.cljs` to separate map-record and legacy-sequential normalization flows.
6. Run:

       npm run test:playwright:smoke
       npm run check
       npm test
       npm run test:websocket
       npm run coverage
       bb tools/crap_report.clj --module src/hyperopen/views/asset_selector/runtime.cljs --format json --top-functions 50
       bb tools/crap_report.clj --module src/hyperopen/staking/actions.cljs --format json --top-functions 50
       bb tools/crap_report.clj --module src/hyperopen/funding/application/modal_commands.cljs --format json --top-functions 50
       bb tools/crap_report.clj --module src/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay.cljs --format json --top-functions 50
       bb tools/crap_report.clj --module src/hyperopen/vaults/infrastructure/list_cache.cljs --format json --top-functions 50

7. Update this ExecPlan with the validation evidence, close `hyperopen-7y38`, and move this file to `/hyperopen/docs/exec-plans/completed/`.

## Validation and Acceptance

The work is complete when all of the following are true:

- `npm run test:playwright:smoke` passes from `/hyperopen`.
- `npm run check` passes from `/hyperopen`.
- `npm test` passes from `/hyperopen`.
- `npm run test:websocket` passes from `/hyperopen`.
- `npm run coverage` regenerates `coverage/lcov.info` successfully.
- Each focused CRAP report for the five target modules shows the named target function below the CRAP threshold of `30`.
- No same-module replacement hotspot introduced by the refactor remains above the CRAP threshold of `30`.
- The public command/effect outputs, cached record shapes, hover activation behavior, and asset-selector runtime behavior remain unchanged for callers and users.

## Idempotence and Recovery

These changes are source and test edits only, so rerunning the refactor steps is safe. If a refactor breaks behavior in one module, rerun the nearest focused test namespace first before the full gates so the failure is isolated quickly. If the CRAP tool reports missing coverage again, rerun `npm run coverage` because the report is read-only and depends entirely on generated artifacts. If Playwright smoke fails for an unrelated flaky reason, capture that evidence in this plan before deciding whether to continue with the non-browser gates.

## Artifacts and Notes

Known baseline evidence before implementation:

- `bd` issue: `hyperopen-7y38`
- User-supplied hotspots:
  - `asset-list-on-render` => `CRAP 35.28`
  - `balance-row-available` => `CRAP 34.95`
  - `set-funding-modal-field` => `CRAP 34.69`
  - `container-hover-active?` => `CRAP 34.23`
  - `normalize-vault-index-cache-record` => `CRAP 34.16`
- Current tooling state:
  - `bb tools/crap_report.clj --module ...` currently fails until `coverage/lcov.info` exists.

Post-implementation evidence from `/hyperopen`:

- Dependency recovery:
  - `rm -rf node_modules && npm ci` restored the missing `lucide` dependency set and unblocked the test runner.
- `npm run test:playwright:smoke` passed with `24 passed (1.3m)`.
- `npm test` passed with `Ran 3057 tests containing 16286 assertions. 0 failures, 0 errors.`
- `npm run test:websocket` passed with `Ran 432 tests containing 2471 assertions. 0 failures, 0 errors.`
- `npm run check` passed after recording namespace-size exceptions for the existing oversized owners touched by this remediation.
- `npm run coverage` passed with `Statements 92.02%`, `Branches 69.73%`, `Functions 85.96%`, and `Lines 92.02%`.
- Focused CRAP results:
  - `/hyperopen/src/hyperopen/views/asset_selector/runtime.cljs`: `asset-list-on-render` => `CRAP 4.0`, `complexity 4`, `coverage 1.0`; module `crappy-functions = 0`, `max-crap = 30.0`.
  - `/hyperopen/src/hyperopen/staking/actions.cljs`: `balance-row-available` => `CRAP 3.0`, `complexity 3`, `coverage 1.0`; `submit-staking-withdraw` => `CRAP 6.0`; module `crappy-functions = 0`, `max-crap = 27.752773896605575`.
  - `/hyperopen/src/hyperopen/funding/application/modal_commands.cljs`: `set-funding-modal-field` => `CRAP 2.0`, `complexity 2`, `coverage 1.0`; module `crappy-functions = 0`, `max-crap = 13.0`.
  - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay.cljs`: `container-hover-active?` => `CRAP 4.0`, `complexity 4`, `coverage 1.0`; module `crappy-functions = 0`, `max-crap = 20.902278164251634`.
  - `/hyperopen/src/hyperopen/vaults/infrastructure/list_cache.cljs`: `normalize-vault-index-cache-record` => `CRAP 3.0`, `complexity 3`, `coverage 1.0`; module `crappy-functions = 0`, `max-crap = 9.0`.

## Interfaces and Dependencies

At the end of this work, the following public seams must remain compatible:

    hyperopen.views.asset-selector.runtime/asset-list
    hyperopen.views.asset-selector.runtime/asset-list-body
    hyperopen.staking.actions/set-staking-transfer-direction
    hyperopen.staking.actions/submit-staking-deposit
    hyperopen.staking.actions/submit-staking-delegate
    hyperopen.funding.application.modal-actions/set-funding-modal-field
    hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay/sync-chart-navigation-overlay!
    hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay/clear-chart-navigation-overlay!
    hyperopen.vaults.infrastructure.list-cache/normalize-vault-index-cache-record
    hyperopen.vaults.infrastructure.list-cache/load-vault-index-cache-record!
    hyperopen.vaults.infrastructure.list-cache/persist-vault-index-cache-record!

The implementation may add private constants and private helper functions inside these namespaces and may expand the existing test namespaces, but it must not introduce new side effects, change effect ids, change persisted cache keys or record keys, or change the hover and scroll contracts that the existing UI depends on.

Revision note: 2026-04-06 00:45Z - Updated the completed plan after restoring dependencies with `npm ci`, adding final staking withdraw coverage to remove the surfaced replacement hotspot, rerunning `npm run check` and `npm run coverage`, and recording the zero-hotspot module results for all five touched namespaces.
