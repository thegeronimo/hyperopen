# Decompose Portfolio VM into a Thin Facade and Extract Runtime Ownership

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Today `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` is a 1,647 line god namespace that mixes benchmark selector caches, chart math, history alignment, equity aggregation, and a metrics Web Worker bridge that directly mutates `/hyperopen/src/hyperopen/system.cljs` store state. This makes the portfolio view layer responsible for runtime behavior that should live behind smaller, explicit seams.

After this refactor, `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` will remain as the stable entry point for `portfolio-vm`, but it will behave as a thin facade and final composer. Benchmark caches, metrics worker orchestration, chart/history shaping, and equity or volume helpers will live in focused namespaces under `/hyperopen/src/hyperopen/views/portfolio/vm/`. A contributor will be able to verify success by inspecting that the root VM namespace primarily normalizes high-level inputs and assembles the final view-model map, while the extracted namespaces own the runtime and data-shaping behavior and the required gates pass (`npm run check`, `npm test`, `npm run test:websocket`).

## Progress

- [x] (2026-03-06 16:57Z) Read required governance and UI/runtime docs: `/hyperopen/ARCHITECTURE.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, and `/hyperopen/docs/agent-guides/trading-ui-policy.md`.
- [x] (2026-03-06 16:57Z) Audited current portfolio VM surface and helper modules. Baseline: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` is 1,647 LOC; helper namespaces already exist for `summary`, `history`, `chart`, `chart_math`, `benchmarks`, `metrics_bridge`, `equity`, and `volume`.
- [x] (2026-03-06 16:57Z) Audited existing regression coverage in `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_helpers_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm/history_helpers_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm/metrics_bridge_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm/metrics_bridge_helpers_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm/chart_math_test.cljs`, and `/hyperopen/test/hyperopen/views/portfolio/vm/volume_test.cljs`.
- [x] (2026-03-06 16:57Z) Confirmed no unblocked pre-existing `bd` work was queued for this area via `bd ready --json`.
- [x] (2026-03-06 16:57Z) Wrote this ExecPlan in `/hyperopen/docs/exec-plans/active/2026-03-06-portfolio-vm-facade-and-runtime-extraction.md`.
- [x] (2026-03-06 17:00Z) Created `bd` issue hierarchy for this refactor: epic `hyperopen-i8i`; child tasks `hyperopen-i8i.1` through `hyperopen-i8i.5`.
- [x] (2026-03-06 17:00Z) Claimed `hyperopen-i8i.1` for the first implementation slice: benchmark selector caches and benchmark computation context extraction.
- [x] (2026-03-06 17:09Z) Rewrote `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs` to own benchmark selector caches, selector-model rules, vault and market benchmark alignment, source-version sampling, and `benchmark-computation-context`.
- [x] (2026-03-06 17:09Z) Delegated the benchmark slice in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` through `hyperopen.views.portfolio.vm.benchmarks`, including `reset-portfolio-vm-cache!`, selector-model wiring, and benchmark computation-context assembly.
- [x] (2026-03-06 17:09Z) Updated `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_helpers_test.cljs` so helper-module coverage now targets the extracted benchmark module’s actual ownership contract instead of the stale pre-refactor state shape.
- [x] (2026-03-06 17:26Z) Restored worktree dependencies with `npm ci`, which installed the local `shadow-cljs` binary and missing Node modules needed by `npm test`.
- [x] (2026-03-06 17:34Z) Rewrote `/hyperopen/src/hyperopen/views/portfolio/vm/metrics_bridge.cljs` to own metrics worker lifecycle, request dedupe state, worker-result normalization, sync metrics helpers, and store mutation.
- [x] (2026-03-06 17:34Z) Delegated the root metrics slice in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` through `hyperopen.views.portfolio.vm.metrics-bridge`, keeping only facade wrappers for stable seams used by existing tests.
- [x] (2026-03-06 17:41Z) Updated `/hyperopen/test/hyperopen/views/portfolio/vm/metrics_bridge_helpers_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio/vm/metrics_bridge_test.cljs` so the helper-module coverage matches the extracted module’s normalized worker payload and request-signature contract.
- [x] Milestone 1: Move benchmark selector caches and benchmark computation context into `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`, then delegate root VM helpers through that module.
- [x] Milestone 2: Move metrics worker ownership, request dedupe, and store mutation out of `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` into `/hyperopen/src/hyperopen/views/portfolio/vm/metrics_bridge.cljs`.
- [x] (2026-03-06 17:32Z) Claimed `hyperopen-i8i.4` and started Milestone 3 with the first low-coupling extraction slice: `chart_math`, `volume`, and `equity`.
- [x] (2026-03-06 17:48Z) Rewrote `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/volume.cljs`, and `/hyperopen/src/hyperopen/views/portfolio/vm/equity.cljs` to match the canonical behavior that had still been embedded in the root VM.
- [x] (2026-03-06 17:48Z) Delegated the root VM’s chart-math, volume, fee, and account-equity helpers through those extracted modules while preserving the root public/private seams used by existing tests.
- [x] (2026-03-06 17:53Z) Added direct module coverage in `/hyperopen/test/hyperopen/views/portfolio/vm/volume_helpers_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio/vm/equity_helpers_test.cljs`, and updated `/hyperopen/test/hyperopen/views/portfolio/vm/chart_math_test.cljs` plus `/hyperopen/test/hyperopen/views/portfolio/vm/chart_math_additional_test.cljs` to assert the canonical root-VM contract.
- [ ] Milestone 3: Align `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/equity.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/volume.cljs`, and `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs` with the behavior currently embedded in the root VM.
- [ ] Milestone 4: Reduce `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` to a thin facade/composer, remove duplicate private implementations, and update tests toward module ownership.
- [x] (2026-03-06 17:09Z) Validation for the first slice: `npx shadow-cljs compile test` passed after the benchmark extraction.
- [x] (2026-03-06 17:47Z) Ran required validation gates after the benchmark and metrics-runtime extractions: `npm test`, `npm run check`, and `npm run test:websocket` all exited `0`.
- [x] (2026-03-06 18:00Z) Re-ran the required validation gates after the `chart_math`/`volume`/`equity` extraction slice: `npm test`, `npm run check`, and `npm run test:websocket` all exited `0`.

## Surprises & Discoveries

- Observation: The extracted helper namespaces already exist, but several of them are stale relative to the root VM and still do not own the behavior the root file duplicates.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` still defines its own benchmark selector caches, metrics worker, history normalization, chart math, equity totals, and volume helpers despite parallel namespaces with those names existing under `/hyperopen/src/hyperopen/views/portfolio/vm/`.

- Observation: `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs` and `/hyperopen/src/hyperopen/vaults/detail/performance.cljs` already use a decomposed benchmark/performance shape that closely matches the target ownership model for portfolio.
  Evidence: Vault detail keeps benchmark selector, alignment, and performance model logic outside the top-level view model and uses the root VM only as a composer.

- Observation: `bd create` can hit Dolt serialization failures when multiple issue creations race in parallel.
  Evidence: Concurrent child-task creation returned `serialization failure: this transaction conflicts with a committed transaction from another client`; retrying sequentially succeeded.

- Observation: The extracted benchmark module could not rely on the current `metrics_bridge.cljs` vault snapshot helpers because those helpers still implement an older one-argument contract and different semantics.
  Evidence: `npx shadow-cljs compile test` initially flagged `Wrong number of args (2) passed to hyperopen.views.portfolio.vm.metrics-bridge/vault-benchmark-snapshot-values` after the first delegation pass.

- Observation: The `npm test` failure was caused by missing worktree dependencies, not by a broken `npm` script.
  Evidence: Running `npm ci` restored `node_modules/.bin/shadow-cljs` and the missing `@noble/secp256k1` dependency; after that, `npm test` and the other required gates passed without script changes.

- Observation: The extracted metrics helper module already preserved the root VM's normalization behavior for absent `:metric-status` and `:metric-reason` maps, so the failing expectations were in the new helper tests rather than in the extraction.
  Evidence: The old root helper used `(update :metric-status normalize-metric-token-map)` and `(update :metric-reason normalize-metric-token-map)`, which normalize missing maps to `{}` just like the new module.

- Observation: `chart_math.cljs`, `volume.cljs`, and `equity.cljs` had drifted farther from the root VM than their filenames suggested, including incompatible public contracts and data sources.
  Evidence: `chart_math.cljs` still exposed ratio-based hover semantics and SVG paths in a different coordinate system, `volume.cljs` still read `:market-data` paths instead of `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`'s `:orders` and `:portfolio :user-fees` sources, and `equity.cljs` still used an older `compute-total-equity` signature.

## Decision Log

- Decision: Keep `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` as the stable facade namespace for `portfolio-vm` while moving logic behind focused module seams.
  Rationale: The portfolio page and many tests already depend on the namespace path; preserving the facade avoids broad consumer churn while still satisfying the architecture requirement that responsibilities be decomposed.
  Date/Author: 2026-03-06 / Codex

- Decision: Use the existing `/hyperopen/src/hyperopen/views/portfolio/vm/` module directory as the primary extraction target instead of inventing a parallel folder tree.
  Rationale: The repo already contains the intended ownership buckets (`benchmarks`, `metrics_bridge`, `history`, `chart`, `chart_math`, `equity`, `volume`, `summary`); the immediate problem is divergence and duplicate logic, not missing directories.
  Date/Author: 2026-03-06 / Codex

- Decision: Treat `benchmark` and `metrics runtime` extraction as the first implementation slice.
  Rationale: The highest-risk behavior called out in the request is the root namespace owning mutable caches, a Web Worker, and direct `swap!` calls into global state. Removing those first gives the largest architectural payoff while preserving user-visible behavior.
  Date/Author: 2026-03-06 / Codex

- Decision: Localize vault snapshot alignment helpers inside `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs` for the first slice instead of routing through the current `metrics_bridge.cljs`.
  Rationale: The existing bridge helpers still reflect an older helper contract and would have kept the benchmark module coupled to stale semantics. Moving the benchmark-specific alignment code into the benchmark owner keeps Milestone 1 coherent and avoids a half-migrated cross-namespace dependency.
  Date/Author: 2026-03-06 / Codex

## Outcomes & Retrospective

Milestones 1 and 2 are complete, and Milestone 3 is in progress. The benchmark selector/cache/context slice now lives in `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`, the metrics worker/runtime slice now lives in `/hyperopen/src/hyperopen/views/portfolio/vm/metrics_bridge.cljs`, and the `chart_math`/`volume`/`equity` helpers now live canonically in their dedicated modules. The root VM delegates those areas instead of owning the cache atoms, worker, fee-volume logic, or total-equity math directly, and the required validation gates pass in this worktree.

## Context and Orientation

In this repository, the “portfolio VM” is the map builder consumed by `/hyperopen/src/hyperopen/views/portfolio_view.cljs`. It should shape already-available application data into rendering-friendly fields. It should not own browser workers, cross-build caches, or direct runtime store mutation.

The current root namespace is:

- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`

The existing helper namespaces that are intended to own decomposed behavior are:

- `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm/chart_math.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm/metrics_bridge.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm/equity.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm/volume.cljs`

The root namespace currently duplicates behavior already suggested by those files. Examples from the current root file:

- `metrics-worker`, `last-metrics-request`, and `request-metrics-computation!` directly own a `js/Worker` and call `swap!` on `system/store`.
- `benchmark-selector-options-cache` and `eligible-vault-benchmark-rows-cache` own selector memoization.
- `benchmark-computation-context` owns candle and vault benchmark alignment.
- `performance-metrics-model` owns request signatures and metrics result orchestration.
- `build-chart-model` owns chart domain, tick, path, and hover behavior.
- `volume-14d-usd`, `fees-from-user-fees`, `compute-total-equity`, and summary derivation live directly in the facade.

Current tests fall into two categories:

- High-level portfolio behavior tests in `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs`.
- Helper-module tests in `/hyperopen/test/hyperopen/views/portfolio/vm/*_helpers_test.cljs`.

Those tests demonstrate a second problem: helper modules and root behavior have drifted apart. The refactor should make helper modules the canonical implementations and either update tests to target those modules directly or keep minimal facade wrappers only when preserving a stable seam is valuable.

Issue tracking for this plan lives in `bd`:

- Epic: `hyperopen-i8i`
- Child task `hyperopen-i8i.1`: benchmark selector and benchmark context extraction
- Child task `hyperopen-i8i.2`: reduce root `vm.cljs` to facade composer
- Child task `hyperopen-i8i.3`: metrics worker/runtime bridge extraction
- Child task `hyperopen-i8i.4`: summary/history/chart/equity/volume module alignment
- Child task `hyperopen-i8i.5`: validation and follow-up tracking

## Plan of Work

### Milestone 1: Canonicalize Benchmark Selector and Benchmark Context Ownership

Update `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs` so it owns the current portfolio benchmark-selector rules now duplicated in the root VM. This includes market ranking, vault benchmark inclusion, selector search behavior, cache invalidation, candle alignment, vault snapshot alignment, and source-version/signature helpers needed by metrics orchestration.

The root namespace should stop owning benchmark cache atoms and benchmark-selection private helpers. It may keep minimal wrappers only if they preserve a stable testing seam, but the actual behavior should live in `benchmarks.cljs`.

Acceptance for this milestone is that benchmark selector and benchmark context behavior still matches current portfolio tests, while cache ownership moves out of `vm.cljs`.

### Milestone 2: Move Metrics Runtime Ownership Behind `metrics_bridge.cljs`

Expand `/hyperopen/src/hyperopen/views/portfolio/vm/metrics_bridge.cljs` so it owns the `js/Worker`, worker-result normalization, request dedupe state, sync fallback computation, and the direct `system/store` mutation required to receive background results.

The root namespace may assemble a request or call a single bridge function, but it must not own `defonce` worker state or direct `swap!` calls into `system/store`.

Acceptance for this milestone is that metrics request behavior, request signature gating, and “keep existing metrics visible during background recompute” behavior remain covered by tests while the side-effect ownership leaves the root namespace.

### Milestone 3: Align History, Chart, Summary, Equity, and Volume Modules with Real Portfolio Behavior

Port the canonical behavior from the root namespace into the existing modules:

- `summary.cljs` for summary-key normalization and range fallback behavior.
- `history.cljs` for history-point parsing, time windows, benchmark alignment, and return-point conversion.
- `chart.cljs` and `chart_math.cljs` for chart series assembly and SVG geometry.
- `equity.cljs` for unified-vs-classic account composition and totals.
- `volume.cljs` for fills-volume caching, user-fee volume, and fee-rate derivation.

Use `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs` and `/hyperopen/src/hyperopen/vaults/detail/performance.cljs` as local precedent for decomposed benchmark/performance ownership where helpful, but keep the portfolio-specific output contract unchanged.

Acceptance for this milestone is that helper modules represent the real behavior currently expected by portfolio tests, not alternative or stale behavior.

### Milestone 4: Reduce the Root Namespace to a Thin Facade Composer

After the helper namespaces become canonical, remove duplicate private implementations from `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`. The remaining root file should primarily:

- gather top-level inputs from `state`;
- call focused helper namespaces;
- assemble the final `portfolio-vm` map;
- expose only compatibility seams that are still justified.

Acceptance for this milestone is a materially smaller `vm.cljs` whose remaining code is mostly composition rather than algorithmic logic or runtime ownership.

### Milestone 5: Final Validation and Handoff

Run targeted portfolio VM regressions after each migration slice and then run the required gates:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Update this ExecPlan with the commands actually run, their outcomes, any discovered follow-up work, and the `bd` status changes for completed or remaining tasks.

## Concrete Steps

All commands run from `/hyperopen`.

1. Record the initial baseline and current seams:

    wc -l src/hyperopen/views/portfolio/vm.cljs src/hyperopen/views/portfolio/vm/*.cljs
    rg -n "defonce|swap!|Worker|benchmark-selector-options-cache|eligible-vault-benchmark-rows-cache|last-metrics-request|request-metrics-computation!" src/hyperopen/views/portfolio/vm.cljs
    rg -n "portfolio-vm|metrics-bridge|returns-benchmark-selector-model|benchmark-selector-options" test/hyperopen/views/portfolio

2. Create the `bd` epic and child tasks, then claim the first implementation task.

3. For Milestone 1, move benchmark behavior into `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs` and delegate the root helpers.

4. Restore local dependencies before relying on Node-based test runners:

    npm ci

5. Run targeted regressions for the benchmark slice, for example:

    npm test -- --focus=portfolio/vm/benchmarks

   If focused test execution is not available, run:

    npx shadow-cljs compile test
    node out/test.js

   Current status in this worktree:

    npx shadow-cljs compile test

   passed after the benchmark extraction. After `npm ci` restored local dependencies, `npm test`, `npm run check`, and `npm run test:websocket` also passed.

6. For Milestone 2, move worker/runtime behavior into `/hyperopen/src/hyperopen/views/portfolio/vm/metrics_bridge.cljs` and re-run targeted metrics regressions.

7. Continue module-by-module until the root namespace is mostly composition code, then run the required gates:

    npm run check
    npm test
    npm run test:websocket

Expected outcome: all commands exit `0`, or any blocker is recorded here and in the relevant `bd` issue.

## Validation and Acceptance

This refactor is accepted when all of the following are true:

- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` remains the stable entry point for `portfolio-vm`.
- The root namespace no longer owns a `js/Worker`, benchmark selector caches, or direct `swap!` mutation of `system/store`.
- Benchmark, metrics, chart/history, summary, equity, and volume behavior live in focused namespaces under `/hyperopen/src/hyperopen/views/portfolio/vm/`.
- Portfolio view-model behavior remains regression-covered by targeted tests plus the required gates:
  - `npm run check`
  - `npm test`
  - `npm run test:websocket`
- Any remaining follow-up work is tracked in `bd`, not in ad hoc markdown TODOs.

## Idempotence and Recovery

This work is a move-and-delegate refactor, so each milestone should be safe to repeat. Prefer additive delegation first and deletion second. If a migration step fails mid-way, restore a working state by ensuring each helper namespace has one canonical implementation and the root facade delegates to that implementation.

If tests fail after extraction, compare the root file and helper module behavior before deleting the duplicate root helper. Because helper modules are currently stale in places, do not delete the root implementation until the extracted module is confirmed to preserve the current contract.

## Artifacts and Notes

Baseline evidence captured during planning:

- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`: 1,647 LOC.
- Existing helper-module LOC: `metrics_bridge.cljs` 115, `history.cljs` 140, `chart.cljs` 59, `benchmarks.cljs` 215.
- `bd ready --json` returned `[]`, so this work starts from new issue creation rather than claiming existing ready work.
- First implementation-slice validation: `npx shadow-cljs compile test` completed with `0 warnings` after the benchmark extraction.
- Full validation after environment repair: `npm test`, `npm run check`, and `npm run test:websocket` all exited `0`.
- Milestone 3 slice validation: after aligning `chart_math.cljs`, `volume.cljs`, and `equity.cljs`, `npm test`, `npm run check`, and `npm run test:websocket` all exited `0` again.

The older note `/hyperopen/docs/exec-plans/active/refactor_portfolio_vm.md` exists but is stale relative to the current source tree. This new plan supersedes it for active implementation because it reflects the present duplicate-module state and the current request to remove runtime behavior from the root view namespace.

## Interfaces and Dependencies

The final shape should preserve these high-level interfaces:

- In `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`, `portfolio-vm` remains:

    (defn portfolio-vm [state] ...)

- In `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`, expose focused benchmark APIs that let the root compose without re-implementing logic. Expected functions include:

    (defn reset-portfolio-vm-cache! [] ...)
    (defn returns-benchmark-selector-model [state] ...)
    (defn benchmark-computation-context [state summary-entry summary-scope summary-time-range returns-benchmark-selector] ...)

- In `/hyperopen/src/hyperopen/views/portfolio/vm/metrics_bridge.cljs`, expose focused runtime bridge APIs. Expected functions include:

    (defonce metrics-worker ...)
    (defonce last-metrics-request ...)
    (defn request-metrics-computation! [request-data request-signature] ...)
    (defn performance-metrics-model [state summary-time-range returns-benchmark-selector benchmark-context] ...)

Function names may change during implementation if the updated plan records the reason, but the final module boundaries must make runtime ownership and pure view-model composition explicit.

Plan revision note: Created this plan to supersede the stale portfolio VM refactor note and align execution with the current request to remove runtime ownership from the root view-model namespace.
