# Retire view boundary exceptions

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`. It is self-contained so a future contributor can continue from this file without relying on conversation history.

Tracked issue: `hyperopen-i62o` ("Retire view boundary exceptions").

## Purpose / Big Picture

The repository enforces dependency direction with `dev/check_namespace_boundaries.clj`: namespaces outside `src/hyperopen/views/` should not import `hyperopen.views.*`. The exception registry `dev/namespace_boundary_exceptions.edn` currently allows five non-view imports into views: app bootstrap imports the root app view, vault detail imports portfolio view-model helpers, and telemetry imports account/order view models for QA oracles. After this change, those imports should flow through application or domain seams instead. The observable result is that `bb dev/check_namespace_boundaries.clj` and `npm run check` pass with the exception registry retired, while the existing app bootstrap, vault detail returns, and telemetry QA oracle behavior remains covered by tests.

## Progress

- [x] (2026-04-22 12:15Z) Created and claimed `bd` issue `hyperopen-i62o`.
- [x] (2026-04-22 12:15Z) Audited `dev/namespace_boundary_exceptions.edn` and confirmed the five remaining imports are `app/bootstrap -> views/app-view`, `vaults/detail/{benchmarks,performance} -> views/portfolio/vm/*`, and `telemetry/console_preload -> views/{account-info.vm,trade.order-form-vm}`.
- [x] (2026-04-22 12:23Z) Extracted app bootstrap rendering into `hyperopen.views.entrypoint`, leaving `src/hyperopen/app/bootstrap.cljs` without a `hyperopen.views.*` import.
- [x] (2026-04-22 12:23Z) Extracted portfolio returns/history helpers into `hyperopen.portfolio.application.history` and `hyperopen.portfolio.application.summary`; vault detail code now imports those seams.
- [x] (2026-04-22 12:23Z) Extracted telemetry oracle dependencies into `hyperopen.trading.order-form-view-model` and `hyperopen.account.history.position-projection`.
- [x] (2026-04-22 12:24Z) Emptied `dev/namespace_boundary_exceptions.edn` and verified `bb dev/check_namespace_boundaries.clj` prints `Namespace boundary check passed.`
- [x] (2026-04-22 12:25Z) Ran the focused CLJS command after installing missing lockfile dependencies with `npm ci`; it passed 69 tests and 422 assertions with 0 failures and 0 errors.
- [x] (2026-04-22 12:30Z) Ran required repo gates. `npm run check` passed, `npm test` passed 3,383 tests and 18,432 assertions, and `npm run test:websocket` passed 461 tests and 2,798 assertions.
- [x] (2026-04-22 12:31Z) Moved this ExecPlan to `docs/exec-plans/completed/` after validation.
- [x] (2026-04-22 12:35Z) Ran `npm run test:playwright:smoke` for UI/browser accounting. The standard run passed 22 smoke tests and failed 2 route smoke cases. A targeted rerun passed the trader-portfolio root-render case, while the leaderboard IndexedDB preference smoke remained blocked by live leaderboard data/reload timing.
- [x] (2026-04-22 12:40Z) Ran `npm run browser:cleanup`; it completed with no browser-inspection sessions to stop.

## Surprises & Discoveries

- Observation: The boundary checker scans only `src/**/*.cljs`, not tests, and treats any non-`src/hyperopen/views/` import of `hyperopen.views.*` as a violation unless it has an exception.
  Evidence: `dev/check_namespace_boundaries.clj` defines `default-source-dirs` as `["src"]`, `view-path?` as `src/hyperopen/views/`, and reports `:missing-boundary-exception` for any other path importing `hyperopen.views.*`.

- Observation: The portfolio VM history and summary helpers used by vault detail are pure data transformations over portfolio histories, benchmark candles, and summary maps.
  Evidence: `src/hyperopen/views/portfolio/vm/history.cljs` depends on portfolio actions, metric parsing, and constants; `src/hyperopen/views/portfolio/vm/summary.cljs` depends on portfolio metric history and the history helper namespace.

- Observation: The order-form VM is already mostly an application projection over `hyperopen.trading.order-form-application`, with view-owned helper namespaces providing formatting and tooltip text.
  Evidence: `src/hyperopen/views/trade/order_form_vm.cljs` builds its map from `application/order-form-context`, `order-type-registry`, and small selector/submit helpers.

- Observation: The first focused Node test attempt failed before executing tests because this worktree had no installed `lucide` package under `node_modules`.
  Evidence: `npm ls lucide --depth=0` returned an empty tree even though `package-lock.json` pins `lucide-0.577.0`; `npm ci` installed 333 packages and the same focused command then passed.

- Observation: The required smoke route coverage currently depends on live leaderboard data and can exceed the configured 45 second Playwright timeout independently of this boundary refactor.
  Evidence: `npm run test:playwright:smoke` passed 22 tests, then failed `trader-portfolio desktop root renders` with an initial debug-bridge startup timeout and `leaderboard preferences persist across reload via IndexedDB` at the post-reload oracle. A targeted rerun made the trader-portfolio case pass. The leaderboard trace showed the `leaderboard-root` oracle returning `{present: true}` just after the standard timeout in one run, and an isolated extended-timeout rerun later failed earlier while waiting for the live-data table's `Volume` header.

## Decision Log

- Decision: Preserve existing view namespace names as compatibility facades where callers and tests already depend on them.
  Rationale: The boundary problem is the dependency direction, not the public names used inside the view layer. Moving implementation into non-view seams and leaving view facades keeps the blast radius small while making non-view callers consume the correct owner.
  Date/Author: 2026-04-22 / Codex

- Decision: Make the app view dependency originate from a view entrypoint namespace instead of `hyperopen.app.bootstrap`.
  Rationale: App bootstrap owns runtime wiring and render-loop integration. The root app view is view-layer composition, so a `hyperopen.views.*` entrypoint can register the app-view render function and then call the existing core startup path without inverting dependencies.
  Date/Author: 2026-04-22 / Codex

- Decision: Put shared portfolio return-window and benchmark-history logic under `hyperopen.portfolio.application.*`.
  Rationale: These helpers select portfolio summary windows and shape benchmark returns for application use by both portfolio and vault detail surfaces. They are not render-only behavior and should be reusable outside `views`.
  Date/Author: 2026-04-22 / Codex

## Outcomes & Retrospective

The five view boundary exceptions were retired. `dev/namespace_boundary_exceptions.edn` now contains an empty vector, and the direct namespace boundary checker passes. App bootstrap no longer imports the root app view; Shadow CLJS starts through a view-owned entrypoint that installs the app view before delegating to `hyperopen.core/start!`. Vault detail benchmark and performance code now import `hyperopen.portfolio.application.history` and `hyperopen.portfolio.application.summary`. Telemetry console preload now imports `hyperopen.trading.order-form-view-model` and `hyperopen.account.history.position-projection`.

Overall complexity decreased at the architectural boundary because non-view code no longer depends on view models for reusable behavior. A small amount of compatibility code remains in existing view namespaces, but those facades point in the permitted direction and keep the view-layer public surface stable for current callers and tests.

Browser QA was explicitly accounted for because this work touched the Shadow CLJS app entrypoint and view facades. `npm run test:playwright:smoke` did not complete cleanly due the existing live-data leaderboard smoke behavior recorded above. The root-render regression relevant to the app entrypoint passed on targeted rerun; the remaining leaderboard smoke timeout is a residual browser-QA blocker unrelated to the retired namespace exceptions.

## Context and Orientation

The working directory is `/Users/barry/.codex/worktrees/1666/hyperopen`.

The boundary check lives in `dev/check_namespace_boundaries.clj`. It parses `src/**/*.cljs`, extracts required namespaces from each namespace form, and fails when a source file outside `src/hyperopen/views/` requires `hyperopen.views.*`. The file `dev/namespace_boundary_exceptions.edn` is a time-bounded allowlist. This plan retires all entries by moving reusable behavior into non-view owners.

The app startup path currently begins at `hyperopen.core/start!`, configured as the Shadow CLJS `:main` init function in `shadow-cljs.edn`. `src/hyperopen/core.cljs` calls `hyperopen.app.bootstrap/bootstrap-runtime!`, and `src/hyperopen/app/bootstrap.cljs` currently imports `hyperopen.views.app-view` to render the root view. To preserve dependency direction, app bootstrap should expose a renderer registration seam and a view namespace should install `app-view` before delegating to core startup.

The vault detail paths are `src/hyperopen/vaults/detail/benchmarks.cljs` and `src/hyperopen/vaults/detail/performance.cljs`. They currently reuse `hyperopen.views.portfolio.vm.history` and `hyperopen.views.portfolio.vm.summary`. The shared logic belongs in new application namespaces under `src/hyperopen/portfolio/application/`, with the existing view VM namespaces changed into facades for compatibility.

The telemetry path is `src/hyperopen/telemetry/console_preload.cljs`. It exposes `HYPEROPEN_DEBUG.oracle(...)` for browser QA. Its `order-form` oracle currently calls `hyperopen.views.trade.order-form-vm/order-form-vm`, and its `first-position` oracle calls `hyperopen.views.account-info.vm/account-info-vm`. The order-form projection should move to `hyperopen.trading.order-form-view-model`. The first-position oracle should use an account-owned projection that can read the current store without importing account-info view models.

## Plan of Work

First, change app bootstrap into a dependency-injected renderer. `src/hyperopen/app/bootstrap.cljs` should no longer require `hyperopen.views.app-view`; instead it should hold an installed app-view function and render through it. Add `src/hyperopen/views/entrypoint.cljs` to install `hyperopen.views.app-view/app-view` and delegate to `hyperopen.core/start!` and `hyperopen.core/reload`. Update both `:app` and `:release` Shadow CLJS module maps to use `hyperopen.views.entrypoint/start!`, and update the devtools `:after-load` hook to use `hyperopen.views.entrypoint/reload`.

Second, extract the portfolio return helpers. Create `src/hyperopen/portfolio/application/history.cljs` from the pure helper surface in `views/portfolio/vm/history.cljs`, and create `src/hyperopen/portfolio/application/summary.cljs` from `views/portfolio/vm/summary.cljs`. Update `src/hyperopen/views/portfolio/vm/history.cljs` and `src/hyperopen/views/portfolio/vm/summary.cljs` to delegate to those application namespaces. Then update vault detail benchmarks and performance to require the new application namespaces.

Third, extract telemetry-safe application seams. Create a trading-owned order-form view-model namespace that contains the current order-form VM projection and pure formatting helpers, with view namespaces delegating to it. Create an account-owned position projection that returns the first current position for telemetry without calling account-info view models. Update `src/hyperopen/telemetry/console_preload.cljs` and its tests to use those new seams.

Finally, remove all entries from `dev/namespace_boundary_exceptions.edn`, run the boundary check directly, run focused tests for app bootstrap, vault detail, portfolio helper compatibility, order-form VM, and telemetry console preload, then run the required gates: `npm run check`, `npm test`, and `npm run test:websocket`.

## Concrete Steps

Run these commands from `/Users/barry/.codex/worktrees/1666/hyperopen`.

1. Verify the starting exception set:

    bb dev/check_namespace_boundaries.clj

   Before this plan is complete, the check should pass only because `dev/namespace_boundary_exceptions.edn` allows five imports.

2. After edits, run the direct boundary check:

    bb dev/check_namespace_boundaries.clj

   Expected final output:

    Namespace boundary check passed.

3. Run focused CLJS tests:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.app.bootstrap-test --test=hyperopen.core-bootstrap.runtime-startup-test --test=hyperopen.vaults.detail.benchmarks-test --test=hyperopen.vaults.detail.performance-test --test=hyperopen.views.portfolio.vm.history-helpers-test --test=hyperopen.views.portfolio.vm.summary-helpers-test --test=hyperopen.views.trade.order-form-vm-test --test=hyperopen.telemetry.console-preload-test --test=hyperopen.telemetry.console-preload-debug-api-test

4. Run required gates:

    npm run check
    npm test
    npm run test:websocket

## Validation and Acceptance

The change is accepted when `dev/namespace_boundary_exceptions.edn` has no live exception entries and `bb dev/check_namespace_boundaries.clj` passes. The app still starts through Shadow CLJS using a view-owned entrypoint, app bootstrap tests prove document-title sync and render delegation still work, vault detail benchmark/performance tests prove the extracted portfolio helpers preserve return-window behavior, and telemetry tests prove the QA oracles still expose wallet, order-form, first-position, and effect-order data. The required repo gates must pass unless an unrelated blocker is recorded here and in the final handoff.

## Idempotence and Recovery

The extraction is safe to rerun because it changes source namespaces and tests only. If a compatibility facade breaks a view test, restore the public function name in the view namespace and delegate to the new application seam rather than moving logic back into `views`. If the Shadow CLJS entrypoint change fails, restore the previous init function temporarily and record the blocker before choosing a narrower app-render seam. Do not run destructive git commands or revert unrelated user work.

## Artifacts and Notes

Initial exception registry:

    src/hyperopen/app/bootstrap.cljs -> hyperopen.views.app-view
    src/hyperopen/vaults/detail/benchmarks.cljs -> hyperopen.views.portfolio.vm.history
    src/hyperopen/vaults/detail/performance.cljs -> hyperopen.views.portfolio.vm.summary
    src/hyperopen/telemetry/console_preload.cljs -> hyperopen.views.account-info.vm
    src/hyperopen/telemetry/console_preload.cljs -> hyperopen.views.trade.order-form-vm

Final validation evidence:

    bb dev/check_namespace_boundaries.clj
    Namespace boundary check passed.

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.app.bootstrap-test --test=hyperopen.core-bootstrap.runtime-startup-test --test=hyperopen.vaults.detail.benchmarks-test --test=hyperopen.vaults.detail.performance-test --test=hyperopen.views.portfolio.vm.history-helpers-test --test=hyperopen.views.portfolio.vm.summary-helpers-test --test=hyperopen.views.trade.order-form-vm-test --test=hyperopen.telemetry.console-preload-test --test=hyperopen.telemetry.console-preload-debug-api-test
    Ran 69 tests containing 422 assertions.
    0 failures, 0 errors.

    npm run check
    passed

    npm test
    Ran 3383 tests containing 18432 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    Ran 461 tests containing 2798 assertions.
    0 failures, 0 errors.

    npm run test:playwright:smoke
    22 passed, 2 failed:
    - trader-portfolio desktop root renders timed out waiting for HYPEROPEN_DEBUG on the first run, then passed on targeted rerun.
    - leaderboard preferences persist across reload via IndexedDB timed out against live leaderboard loading/reload behavior.

    npx playwright test tools/playwright/test/routes.smoke.spec.mjs -g "trader-portfolio desktop root renders|leaderboard preferences persist across reload via IndexedDB"
    1 passed, 1 failed:
    - trader-portfolio desktop root renders passed.
    - leaderboard preferences persist across reload via IndexedDB timed out at the reload oracle.

    npx playwright test tools/playwright/test/routes.smoke.spec.mjs -g "leaderboard preferences persist across reload via IndexedDB" --timeout=90000
    failed waiting for the live leaderboard table's Volume header.

    npm run browser:cleanup
    passed; stopped 0 browser-inspection sessions.

## Interfaces and Dependencies

`hyperopen.portfolio.application.history` must expose the public helper functions currently consumed by portfolio and vault detail callers: `summary-window-cutoff-ms`, `history-point-value`, `history-point-time-ms`, `normalized-history-rows`, `history-window-rows`, `rebase-history-rows`, `benchmark-time-range`, `market-benchmark-anchor-time-ms`, `benchmark-candle-points`, `benchmark-market-return-rows`, `aligned-benchmark-return-rows`, `cumulative-return-time-points`, `aligned-summary-return-rows`, and `cumulative-return-row-pairs`.

`hyperopen.portfolio.application.summary` must expose `canonical-summary-key`, `normalize-summary-by-key`, `selected-summary-key`, `summary-key-candidates`, `all-time-summary-key`, `derived-summary-entry`, `returns-history-context`, `selected-summary-entry`, `selected-summary-context`, `pnl-delta`, and `max-drawdown-ratio`.

`hyperopen.trading.order-form-view-model` must expose the existing order-form VM public surface used by views and telemetry: `order-type-config`, `order-type-label`, `order-type-sections`, `pro-dropdown-options`, `pro-tab-label`, and `order-form-vm`, plus helper functions delegated by existing view compatibility namespaces.

`hyperopen.account.history.position-projection` must expose a telemetry-safe `first-position` function that takes the app state map and returns the first normalized position or `nil`.

Plan revision note: Created on 2026-04-22 to retire all live view boundary exceptions requested in `hyperopen-i62o`.

Plan revision note: Completed on 2026-04-22 after the namespace boundary check, focused tests, and required repo gates passed.
