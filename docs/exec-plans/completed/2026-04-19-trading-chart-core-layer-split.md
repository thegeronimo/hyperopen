# Split trading chart core into focused ownership layers

This ExecPlan is a completed execution record. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` capture the implementation story that closed the work.

This plan follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/WORK_TRACKING.md`, and the UI contracts in `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/BROWSER_TESTING.md`, `/hyperopen/docs/agent-guides/browser-qa.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, and `/hyperopen/docs/agent-guides/trading-ui-policy.md`.

The live tracker is `bd` issue `hyperopen-91mv`, "Split trading chart core ownership".

## Purpose / Big Picture

The trading chart currently works, but `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` owns too many concerns at once: toolbar Hiccup, chart lifecycle, scheduled decoration passes, accessibility patching, visible-range persistence, open-order dispatch callbacks, liquidation drag prefill actions, and page-level view-model derivation. The result is a large 886-line file with a namespace-size exception that makes later chart changes risky.

After this change, users should see the same `/trade` chart behavior, toolbar controls, overlays, visible-range persistence, cancellation callbacks, liquidation drag prefill flow, and accessibility attributes. Maintainers should see separate namespaces with one clear owner each and a smaller `core.cljs` facade that keeps the public entry points `chart-top-menu`, `chart-canvas`, and `trading-chart-view` compatible for existing callers and tests.

This work is not performance-motivated. It must preserve the existing memoization and scheduling behavior, but it does not introduce a new faster algorithm, new rendering cadence, or broader data-structure optimization. No performance baseline is required unless implementation discovers and proposes a real performance change; if that happens, update this plan first with the workload, measurement method, baseline, and reason a simpler ownership split is insufficient.

## Progress

- [x] (2026-04-19T13:36Z) Confirmed branch `codex/trading-chart-core-layers`, clean worktree, live `bd` issue `hyperopen-91mv`, and current line counts: `src/hyperopen/views/trading_chart/core.cljs` is 886 LOC and `test/hyperopen/views/trading_chart/core_test.cljs` is 1333 LOC.
- [x] (2026-04-19T13:36Z) Read the required planning, multi-agent, work-tracking, browser-testing, frontend, browser-QA, UI-foundation, and trading-UI policy docs.
- [x] (2026-04-19T13:36Z) Inspected the current `core.cljs`, focused chart tests, namespace-size exception registry, and existing chart namespace layout.
- [x] (2026-04-19T13:36Z) Created this active ExecPlan for `hyperopen-91mv`.
- [x] (2026-04-19T13:45Z) Refreshed this plan after read-only review to add high-risk desktop geometry QA, chart-specific Playwright ordering, explicit `main-timeframes` facade preservation, and the `browser_debugger` phase before final gates.
- [x] (2026-04-19T13:52Z) Acceptance and edge-case test writers proposed focused compatibility and invariant coverage under `tmp/multi-agent/hyperopen-91mv/`; the parent merged the first-slice approved contract into `tmp/multi-agent/hyperopen-91mv/approved-test-contract.json`.
- [x] (2026-04-19T13:59Z) TDD writer materialized the approved first-slice RED tests in focused chart test namespaces without editing production source and verified the expected compile-time RED failure for the first missing owner namespace.
- [x] (2026-04-19T14:24Z) Worker extracted the toolbar/top-menu owner and preserved `hyperopen.views.trading-chart.core/main-timeframes` plus `hyperopen.views.trading-chart.core/chart-top-menu`.
- [x] (2026-04-19T14:24Z) Worker extracted the dispatch bridge, liquidation drag prefill helpers, and stable chart callback constructors into `hyperopen.views.trading-chart.actions`.
- [x] (2026-04-19T14:24Z) Worker extracted chart view-model derivation out of `trading-chart-view` into `hyperopen.views.trading-chart.vm`.
- [x] (2026-04-19T14:24Z) Worker extracted chart runtime lifecycle, scheduled decoration, accessibility, visible-range lifecycle, and unmount cleanup into `hyperopen.views.trading-chart.runtime`.
- [x] (2026-04-19T14:40Z) Worker removed the retired `src/hyperopen/views/trading_chart/core.cljs` namespace-size exception. Current production line counts are `core.cljs` 154, `runtime.cljs` 318, `actions.cljs` 151, `vm.cljs` 241, and `toolbar.cljs` 85.
- [x] (2026-04-19T14:47Z) Worker ran the first-slice validation commands plus repo code-change gates. `npm run check`, `npm test`, and `npm run test:websocket` passed after restoring missing locked `node_modules` dependencies with `npm install`.
- [x] (2026-04-19) Cleanup worker reverted the out-of-scope indicator-id dropdown search behavior and split the toolbar test's `"sm"` search assertion from the unfiltered SMA add-row assertion. Requested cleanup commands passed: `npm run lint:delimiters -- --changed`, `npx shadow-cljs --force-spawn compile test`, `node out/test.js`, and `npm run lint:hiccup`.
- [x] (2026-04-19) Reviewer completed read-only implementation review with no blocking source findings. The single medium test finding was that `runtime_test.cljs` recorded `legend-updates` without asserting them.
- [x] (2026-04-19) Parent fixed the review finding by adding the missing `legend-updates` assertion to `test/hyperopen/views/trading_chart/runtime_test.cljs`; `npm run lint:delimiters -- --changed`, `npx shadow-cljs --force-spawn compile test`, and `node out/test.js` passed after the assertion.
- [x] (2026-04-19) `browser_debugger` completed governed trade-route browser QA, focused chart Playwright checks, high-risk desktop geometry checks, design UI QA, and browser cleanup with PASS results. Report artifact: `tmp/browser-inspection/hyperopen-91mv-browser-qa/browser-report.json`.
- [x] (2026-04-19) Parent reran final required gates after reviewer and browser QA phases. `npm run check`, `npm test`, and `npm run test:websocket` passed.
- [x] (2026-04-19) Closed `bd` issue `hyperopen-91mv` with reason `Completed` and moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: `core.cljs` has public dynamic scheduling vars, `*schedule-chart-decoration-frame!*` and `*cancel-chart-decoration-frame!*`, that are used by existing tests even though they are part of chart lifecycle internals.
  Evidence: `test/hyperopen/views/trading_chart/core_test.cljs` binds `chart-core/*schedule-chart-decoration-frame!*` and `chart-core/*cancel-chart-decoration-frame!*` in the scheduled-decoration and unmount tests.

- Observation: The current focused chart tests already cover the important compatibility surface for this refactor.
  Evidence: `test/hyperopen/views/trading_chart/core_test.cljs` includes toolbar Hiccup tests, `chart-canvas` mount/update/unmount lifecycle tests, scheduled decoration pass tests, visible-range restore/persistence tests, overlay identity memoization tests, dispatch fallback tests, and liquidation drag prefill tests.

- Observation: Only one production caller outside the namespace currently imports the public chart view facade.
  Evidence: `src/hyperopen/views/trading_chart/module.cljs` requires `hyperopen.views.trading-chart.core` and calls `trading-chart-core/trading-chart-view`.

- Observation: The namespace-size exception for `core.cljs` is explicitly temporary and cites the cleanup this issue is now performing.
  Evidence: `/hyperopen/dev/namespace_size_exceptions.edn` has an entry for `src/hyperopen/views/trading_chart/core.cljs` with `:max-lines 886` and a reason deferring a broader split to dedicated trading-chart cleanup.

- Observation: The repository's `npm test` script runs the generated ClojureScript test runner rather than a documented per-namespace filter.
  Evidence: `package.json` defines `npm test` as `npm run test:runner:generate`, `npx shadow-cljs --force-spawn compile test`, and `node out/test.js`; no package script provides a chart-only test runner.

- Observation: The approved toolbar test's `"sm"` search term did not match the SMA row with the current indicator catalog because SMA's short name is `"MA"` and the dropdown filter searches only name, short name, and description. The initial worker added indicator-id search to satisfy the combined assertion, but that changed production behavior in an ownership-only refactor and was reverted.
  Evidence: `node out/test.js` initially failed only `chart-top-menu-owner-preserves-toolbar-output-test`; `src/hyperopen/domain/trading/indicators/catalog/trend.cljs` defines `:sma` with `:short-name "MA"` and `:description "Simple moving average"`. Cleanup split `test/hyperopen/views/trading_chart/toolbar_test.cljs` so the search input assertion keeps `"sm"` while the `[:actions/add-indicator :sma {:period 20}]` row is asserted with an empty search term.

- Observation: The local `node_modules` tree was incomplete even though `package-lock.json` declared `lucide`.
  Evidence: the first `node out/test.js` run failed before tests with `Cannot find module 'lucide/dist/esm/icons/external-link.js'`, and `npm ls lucide --depth=0` returned `(empty)`. Running `npm install` restored locked dependencies without tracked package file changes, after which `node out/test.js` passed.

- Observation: The implementation review found no source blocker, but it caught a missing assertion in a new runtime test.
  Evidence: `test/hyperopen/views/trading_chart/runtime_test.cljs` recorded `legend-updates` but did not assert the recorded value. The parent added the assertion, and the focused CLJS validation then passed with 3290 tests and 17950 assertions.

## Decision Log

- Decision: Keep `hyperopen.views.trading-chart.core` as a compatibility facade for `chart-top-menu`, `chart-canvas`, and `trading-chart-view`.
  Rationale: The existing public caller and focused tests import `core`. This ticket is an ownership split, not an API migration.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Extract chart lifecycle and scheduling into `hyperopen.views.trading-chart.runtime`, but keep core-level dynamic scheduler bindings compatible during the migration.
  Rationale: Runtime owns chart creation, update, decoration, accessibility, and cleanup; existing tests bind scheduler vars through `chart-core`, so `chart-canvas` should pass those bound functions into the runtime hook until callers are explicitly migrated.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Extract dispatch bridge and liquidation drag prefill helpers into `hyperopen.views.trading-chart.actions`.
  Rationale: Dispatch fallback through `replicant.core/*dispatch*`, `app-system/store`, and `nexus.registry/dispatch` is not view rendering. The pure prefill action vector is easier to test and maintain with the dispatch bridge.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Extract view-model derivation into `hyperopen.views.trading-chart.vm`.
  Rationale: `trading-chart-view` should assemble Hiccup from a model; it should not normalize orders, transform candles, build position overlays, calculate pending liquidation preview, construct callback identities, or derive chart runtime options inline.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Extract toolbar/top-menu rendering into `hyperopen.views.trading-chart.toolbar` as an early slice.
  Rationale: The current toolbar depends on timeframe, chart-type, indicators dropdowns, and websocket freshness, and it can move without depending on runtime or view-model extraction. Core can delegate `chart-top-menu` to preserve the public function.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Preserve `hyperopen.views.trading-chart.core/main-timeframes` as a public facade var or delegation unconditionally.
  Rationale: `main-timeframes` is currently a non-private var in the public chart core namespace. The ownership split must not make that compatibility conditional on today's callers or tests.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Run the smallest relevant chart-specific Playwright regressions before broad smoke and governed browser QA.
  Rationale: This refactor touches chart runtime, canvas, context-menu lifecycle, focus, and accessibility surfaces. `tools/playwright/test/trade-regressions.spec.mjs` already contains targeted chart context-menu/runtime/canvas and trade accessibility tests, so they should fail close to the changed surface before broader smoke runs.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Require high-risk desktop `/trade` geometry evidence in the `browser_debugger` phase.
  Rationale: The chart sits inside the governed desktop trade shell alongside the order book and lower account tables. Even an ownership-only refactor can accidentally change sizing, lifecycle timing, or DOM availability, so the browser QA result must include measured `1280` and `1440` rect evidence and tab-switch stability checks.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Require browser-QA accounting because this touches `/hyperopen/src/hyperopen/views/**`, but treat visual redesign as out of scope.
  Rationale: `/hyperopen/docs/FRONTEND.md` requires UI-facing changes to account for the governed browser QA contract. This refactor should preserve the emitted UI; any DOM, class, event, focus, or layout drift is a regression unless explicitly approved.
  Date/Author: 2026-04-19 / spec_writer

## Outcomes & Retrospective

First production slice implementation is in place. `core.cljs` is now a 154-line compatibility facade and chart host owner; `runtime.cljs` owns chart lifecycle/scheduling/accessibility/visible-range cleanup; `actions.cljs` owns dispatch fallback, chart action dispatch, liquidation prefill actions, and stable callbacks; `vm.cljs` owns chart view-model derivation; `toolbar.cljs` owns `main-timeframes` and `chart-top-menu`.

The retired source namespace-size exception for `src/hyperopen/views/trading_chart/core.cljs` was removed. Current production line counts are `core.cljs` 154, `runtime.cljs` 318, `actions.cljs` 151, `vm.cljs` 241, `toolbar.cljs` 85, and `indicators_dropdown.cljs` 152.

Worker validation passed for the requested first-slice commands and the repo code-change gates.

Cleanup validation also passed for `npm run lint:delimiters -- --changed`, `npx shadow-cljs --force-spawn compile test`, `node out/test.js`, and `npm run lint:hiccup` after reverting indicator-id search and separating the toolbar search/add-row assertions.

Read-only implementation review found no blocking source issues. The review-identified test gap was fixed with a runtime legend-update assertion.

Browser QA passed for the focused chart regressions, smoke coverage, governed `trade-route` design UI pass, high-risk desktop geometry checks at `1280` and `1440`, and explicit browser cleanup. The remaining browser-QA blind spot is the standard design-review interaction sampling limit for hover, active, disabled, and loading states that are not default route state.

Final required repo gates passed after review and browser QA evidence was applied to the worktree.

## Context and Orientation

This repository is a ClojureScript app using Replicant Hiccup. In this plan, "Hiccup" means vectors such as `[:div {:class [...]} ...]` that describe DOM nodes. "Replicant lifecycle" means the `:replicant/on-render` hook receives mount, update, and unmount events for a DOM node. "Chart interop" means calls into Lightweight Charts and chart overlay sidecars through `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`.

The current chart entrypoint is `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`. It defines:

- `main-timeframes` and `chart-top-menu`, which render the top toolbar and call `timeframe-dropdown`, `chart-type-dropdown`, `indicators-dropdown`, and `ws-freshness/surface-cue`.
- Memoization helpers for open orders, fills, position overlays, fill markers, liquidation callbacks, cancel callbacks, runtime options, and legend metadata.
- Visible-range helpers that apply default/persisted ranges and subscribe to visible-range persistence through chart interop.
- Chart lifecycle helpers that create, update, and remove charts, swap the main series on chart-type changes, sync indicator data, schedule decoration passes, apply chart accessibility attributes, and clear runtime artifacts on unmount.
- Dispatch helpers that turn chart overlay actions into Replicant actions, including cancel order, hide volume indicator, and liquidation drag margin preview/confirm prefill.
- `chart-canvas`, which builds the chart host Hiccup and the `:replicant/on-render` lifecycle callback.
- `trading-chart-view`, which derives candles, overlays, callbacks, runtime options, legend metadata, error state, toolbar, and chart canvas from the full app state.

Focused compatibility tests live in `/hyperopen/test/hyperopen/views/trading_chart/core_test.cljs`. They are intentionally broad because `core.cljs` currently owns many responsibilities. As ownership moves, tests should be split into focused namespaces where practical while retaining core facade tests that prove public behavior remains compatible.

Existing related production namespaces are:

- `/hyperopen/src/hyperopen/views/trading_chart/runtime_state.cljs`, a small DOM-node runtime-state store already used by chart lifecycle code.
- `/hyperopen/src/hyperopen/views/trading_chart/derived_cache.cljs`, which memoizes candle transforms and indicator outputs.
- `/hyperopen/src/hyperopen/views/trading_chart/timeframe_dropdown.cljs`, `/hyperopen/src/hyperopen/views/trading_chart/chart_type_dropdown.cljs`, and `/hyperopen/src/hyperopen/views/trading_chart/indicators_dropdown.cljs`, which are toolbar children.
- `/hyperopen/src/hyperopen/views/trading_chart/utils/position_overlay_model.cljs`, which builds position overlay and fill marker model data.
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs` and its sidecars, which own direct chart library and overlay interop.

Do not change browser storage. The visible-range persistence APIs are already behind chart interop; this ticket may move callers but must not change storage keys, storage format, persistence timing, or `/hyperopen/docs/BROWSER_STORAGE.md` behavior.

Do not redesign the chart. The toolbar, chart host, data-parity ids, classes, aria labels, events, visible focus behavior, overlay behavior, and layout should be identical unless a failing compatibility test proves the current behavior is already broken and the plan is updated before changing it.

## Scope

In scope:

- Create `src/hyperopen/views/trading_chart/toolbar.cljs` to own toolbar rendering and `main-timeframes`.
- Create `src/hyperopen/views/trading_chart/actions.cljs` to own chart dispatch bridging and liquidation drag prefill action helpers.
- Create `src/hyperopen/views/trading_chart/vm.cljs` to own chart view-model derivation for `trading-chart-view`.
- Create `src/hyperopen/views/trading_chart/runtime.cljs` to own chart lifecycle, visible-range lifecycle, scheduling, decoration passes, accessibility patching, chart updates, and unmount cleanup.
- Keep `src/hyperopen/views/trading_chart/core.cljs` as the public facade for `main-timeframes`, `chart-top-menu`, `chart-canvas`, and `trading-chart-view`.
- Add or split focused tests under `test/hyperopen/views/trading_chart/**` to prove the extracted owners and the core facade remain compatible.
- Update `dev/namespace_size_exceptions.edn` and any generated test runner files as needed.
- Update this ExecPlan and `bd` lifecycle state as implementation proceeds.

Out of scope:

- Changing public action ids such as `:actions/select-chart-timeframe`, `:actions/cancel-order`, `:actions/hide-volume-indicator`, or `:actions/open-position-margin-modal`.
- Changing chart storage, visible-range persistence keys, or browser persistence behavior.
- Changing chart visual design, toolbar layout, parity ids, chart data semantics, indicator math, overlay rendering, order formatting, or liquidation margin modal product behavior.
- Moving chart interop sidecars such as context menu, navigation overlay, open-order overlays, or position overlays beyond any import changes required by the new runtime owner.
- Introducing new performance optimizations or render throttling behavior.

## Interfaces and Dependencies

The intended new production interfaces are:

- `hyperopen.views.trading-chart.toolbar/main-timeframes`
- `hyperopen.views.trading-chart.toolbar/chart-top-menu [state]`
- `hyperopen.views.trading-chart.core/main-timeframes` as a public facade var or delegation with the same value as `toolbar/main-timeframes`
- `hyperopen.views.trading-chart.actions/current-dispatch-fn []`
- `hyperopen.views.trading-chart.actions/dispatch-chart-cancel-order!` with the existing one- and two-arity behavior
- `hyperopen.views.trading-chart.actions/dispatch-hide-volume-indicator!` with the existing one- and zero-arity behavior
- `hyperopen.views.trading-chart.actions/chart-liquidation-drag-prefill-actions [position-data suggestion]`
- `hyperopen.views.trading-chart.actions/dispatch-chart-liquidation-drag-margin-preview!` with the existing one- and two-arity behavior
- `hyperopen.views.trading-chart.actions/dispatch-chart-liquidation-drag-margin-confirm!` with the existing one- and two-arity behavior
- `hyperopen.views.trading-chart.actions/cancel-order-callback [dispatch-fn]`
- `hyperopen.views.trading-chart.actions/hide-volume-indicator-callback [dispatch-fn]`
- `hyperopen.views.trading-chart.actions/liquidation-drag-preview-callback [dispatch-fn active-position-data]`
- `hyperopen.views.trading-chart.actions/liquidation-drag-confirm-callback [dispatch-fn active-position-data]`
- `hyperopen.views.trading-chart.vm/chart-view-model [state dispatch-fn]`
- `hyperopen.views.trading-chart.runtime/chart-canvas-on-render [context]`

`chart-view-model` should return all values that `trading-chart-view` needs to decide between the error state and `chart-canvas`, including at least:

- `:has-error?`
- `:candle-data`
- `:selected-chart-type`
- `:selected-timeframe`
- `:active-indicators`
- `:legend-meta`
- `:chart-runtime-options`
- `:active-open-orders`
- `:on-cancel-order`

`chart-runtime-options` must keep the existing keys consumed by `chart-canvas`: `:series-options`, `:legend-deps`, `:volume-visible?`, `:indicator-runtime-ready?`, `:show-fill-markers?`, `:on-hide-volume-indicator`, `:persistence-deps`, `:on-liquidation-drag-preview`, `:on-liquidation-drag-confirm`, `:position-overlay`, and `:fill-markers`.

`runtime/chart-canvas-on-render` should accept the current context map plus scheduler dependencies. To preserve compatibility with existing tests that bind core vars, `core/chart-canvas` should pass `:schedule-decoration-frame! hyperopen.views.trading-chart.core/*schedule-chart-decoration-frame!*` and `:cancel-decoration-frame! hyperopen.views.trading-chart.core/*cancel-chart-decoration-frame!*` into the runtime context. The runtime namespace may define default scheduler helpers, but the core facade must keep current test bindings effective.

Keep dependency direction acyclic:

- `core` may require `toolbar`, `actions`, `vm`, and `runtime`.
- `vm` may require `actions`, `derived-cache`, `position-overlay-model`, account projections, formatting, trading state, and trading settings.
- `runtime` may require `runtime-state` and `utils.chart-interop`.
- `toolbar` may require the dropdown namespaces and websocket freshness.
- `actions` may require `app-system`, `nexus.registry`, `replicant.core`, and account projections.
- None of the new namespaces should require `hyperopen.views.trading-chart.core`.

## Plan of Work

First, freeze compatibility with tests. The test contract should cover both the new focused owners and the old core facade. Move existing assertions out of `core_test.cljs` only when the moved tests stay focused and under the namespace-size limit. Keep a small `core_test.cljs` compatibility suite that calls `chart-core/main-timeframes`, `chart-core/chart-top-menu`, `chart-core/chart-canvas`, and `chart-core/trading-chart-view` so existing callers remain protected.

Second, extract the toolbar. Move `main-timeframes` and the body of `chart-top-menu` into `toolbar.cljs`. In `core.cljs`, preserve `main-timeframes` as a public facade var or delegation with the same value as `toolbar/main-timeframes`, and implement `chart-top-menu` as a delegation wrapper. The rendered Hiccup must stay identical for existing toolbar tests: `bg-base-100`, `min-w-0`, `data-parity-id "chart-toolbar"`, dropdown rows, indicator search events, volume show/hide actions, and freshness cue behavior must not change.

Third, extract the actions. Move dispatch fallback, action-vector construction, cancel order dispatch, hide volume dispatch, liquidation drag margin prefill dispatch, and memoized callback constructors into `actions.cljs`. Preserve triggers `:chart-order-overlay-cancel`, `:chart-volume-indicator-remove`, `:chart-liquidation-drag-margin-preview`, and `:chart-liquidation-drag-margin-confirm`. Preserve the behavior that cancel callbacks use the render-time Replicant dispatch when available and fall back to `nexus.registry/dispatch` against `app-system/store` when not. Preserve the pure liquidation prefill vector shape: first select the `:positions` account-info tab, then open `:actions/open-position-margin-modal` with merged position data, chart-drag prefill fields, and the suggestion anchor.

Fourth, extract the view model. Move `preferred-orders-value`, `chart-open-orders`, `chart-fills`, position overlay derivation, fill marker derivation, pending liquidation preview, runtime option construction, and legend metadata construction into `vm.cljs`. `trading-chart-view` should become a thin function: get a dispatch function from `actions/current-dispatch-fn`, call `vm/chart-view-model`, render the same outer panel/shell Hiccup, delegate toolbar rendering through `chart-top-menu`, and pass the same argument order into `chart-canvas`. Preserve memoized object identity behavior for stable inputs because existing tests assert it for candle data, legend metadata, runtime options, open orders, callbacks, and position overlays.

Fifth, extract runtime lifecycle ownership. Move visible-range interaction helpers, chart creation/update/unmount helpers, scheduled decoration state, `apply-chart-accessibility!`, and `chart-canvas-on-render` into `runtime.cljs`. `chart-canvas` can remain in `core.cljs` as the public Hiccup function, but its render hook must come from the runtime namespace. Preserve lifecycle ordering: mount creates chart and legend, initializes runtime state, subscribes baseline value, schedules decoration, then restores/subscribes visible range. Update swaps the main series while preserving visible logical range, updates volume and indicator series, schedules decoration, updates legend, and records chart type. Unmount cancels any pending decoration frame, destroys legend, clears overlays and baseline subscription, runs visible-range cleanup, removes the chart, and clears runtime state.

Sixth, clean up namespace-size tracking. Run line counts and update `dev/namespace_size_exceptions.edn`. The target is for `src/hyperopen/views/trading_chart/core.cljs` to drop to `<= 500` lines and for its source exception to be removed. Every new production namespace should remain `<= 500` lines. If test files are split, also update or remove the `test/hyperopen/views/trading_chart/core_test.cljs` exception truthfully.

After the worker implementation, run the read-only `reviewer` phase before browser QA. The reviewer should check correctness, import cycles, compatibility facades, namespace-size cleanup, missing tests, and whether any new public behavior slipped into an ownership-only ticket.

Then run the `browser_debugger` phase before final gates. The browser debugger must report PASS, FAIL, or BLOCKED for the governed trade-route browser QA passes, record artifact paths or measured DOM evidence, include the high-risk desktop geometry checks from this plan, and explicitly confirm browser-inspection cleanup.

Finally, validate. Run focused CLJS compatibility through the generated test runner, the namespace-size lint, the smallest relevant chart-specific Playwright regressions, governed browser QA accounting for the `/trade` surface, and the required repository gates. Update this plan with exact command outcomes and move it to completed only after acceptance passes and `bd` is closed with a completion reason.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/8eff/hyperopen`.

Confirm tracker and baseline:

    bd show hyperopen-91mv --json
    git status --short
    wc -l src/hyperopen/views/trading_chart/core.cljs test/hyperopen/views/trading_chart/core_test.cljs

When adding or splitting tests, regenerate the test runner:

    npm run test:runner:generate

Use reader and focused lint preflights after ClojureScript edits:

    npm run lint:delimiters -- --changed
    npm run lint:hiccup
    npm run lint:namespace-sizes

Run the generated CLJS test suite. This is the authoritative local path for chart tests because the current package scripts do not expose a chart-only filter:

    npx shadow-cljs --force-spawn compile test
    node out/test.js

After implementation and read-only review, run the smallest relevant chart-specific Playwright regressions before broad smoke. The chart context-menu test covers the chart module loader, chart canvas availability, right-click context menu, keyboard context menu, focus movement, and close behavior. The accessibility test is also relevant because this refactor moves chart canvas `role`, `aria-label`, `tabindex`, and lifecycle accessibility ownership:

    npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "trade chart right-click opens the custom context menu"
    npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "trade route preserves core accessibility affordances"

Then run browser coverage for the stable trade route before the full gates:

    npm run test:playwright:smoke
    npm run qa:design-ui -- --targets trade-route --manage-local-app
    npm run browser:cleanup

The `browser_debugger` phase must add high-risk desktop geometry evidence for `1280` and `1440` widths. For each width, capture DOM or bounding-box rectangles for `[data-parity-id="trade-chart-panel"]`, `[data-parity-id="trade-orderbook-panel"]`, and `[data-parity-id="trade-account-tables-panel"]`; switch account tabs `Balances`, `Positions`, `Open Orders`, `TWAP`, `Trade History`, `Funding History`, and `Order History`; then capture the same rectangles after each tab. The report must fail if the account panel outer width or height changes across those tabs, if the chart bottom edge is no longer flush with the account panel top edge, or if the order-book bottom edge is no longer flush with the account panel top edge.

Run required repository gates before completion:

    npm run check
    npm test
    npm run test:websocket

Record the exact pass/fail/BLOCKED result of every command in this plan's `Validation and Acceptance` section. If Browser MCP or browser-inspection sessions are started, `npm run browser:cleanup` is required before signoff.

## Validation and Acceptance

Acceptance is behavior-preserving. All criteria below must be observable by a command, rendered behavior, or a concrete touched area.

1. Core public facade compatibility is preserved. `test/hyperopen/views/trading_chart/core_test.cljs` or a renamed focused facade test still calls `hyperopen.views.trading-chart.core/main-timeframes`, `chart-top-menu`, `chart-canvas`, and `trading-chart-view`; running `npm test` passes those assertions. `hyperopen.views.trading-chart.core/main-timeframes` must remain a public facade var or delegation with the same value as `hyperopen.views.trading-chart.toolbar/main-timeframes`, not a private var and not a removed alias.

2. Toolbar behavior is unchanged. Focused toolbar tests prove the rendered Hiccup still includes `data-parity-id "chart-toolbar"`, tokenized class collections rather than space-separated class strings, selected timeframe green text without `bg-blue-600`, indicators search input with `:actions/update-indicators-search`, volume show/hide rows with the current action ids, and websocket freshness cue behavior gated by `[:websocket-ui :show-surface-freshness-cues?]`.

3. Runtime lifecycle behavior is unchanged. Focused runtime tests prove mount chooses the volume chart path when no indicators are derived, chooses the indicator chart path when indicators exist, swaps main series and preserves visible logical range on chart-type change, restores/subscribes visible range only once, schedules decoration into the injected frame callback, applies accessibility only after decoration, cancels pending decoration on unmount, and cleans up legend, overlays, baseline subscription, visible-range cleanup, chart removal, and runtime state in the existing order.

4. Dispatch behavior is unchanged. Focused action tests prove cancel order dispatch uses `:chart-order-overlay-cancel` with `[[:actions/cancel-order order]]`, render-time Replicant dispatch is preferred, runtime dispatch fallback still mutates the store through `nexus.registry/dispatch`, hide volume dispatch uses `:chart-volume-indicator-remove`, and liquidation drag preview/confirm dispatches the two-action prefill sequence with the existing triggers and anchor payload.

5. View-model behavior is unchanged. Focused VM tests prove stable inputs reuse candle data, legend metadata, runtime options, active open orders, cancel callback, volume hide callback, liquidation drag callbacks, and position overlay identities; runtime options include `:persistence-deps` with active asset and transformed candles; fill-marker preference is threaded; and pending margin-modal chart-drag liquidation preview overrides the rendered liquidation price while preserving existing position overlay fields.

6. Ownership moved out of `core.cljs`. The following commands show definitions are no longer owned by `core.cljs` and are owned by focused namespaces:

    rg -n "defn-? (mount-chart!|update-chart!|unmount-chart!|schedule-chart-decoration-pass!|apply-chart-accessibility!|ensure-visible-range-lifecycle!)" src/hyperopen/views/trading_chart/core.cljs
    rg -n "mount-chart!|update-chart!|unmount-chart!|schedule-chart-decoration-pass!|apply-chart-accessibility!|ensure-visible-range-lifecycle!" src/hyperopen/views/trading_chart/runtime.cljs
    rg -n "dispatch-chart-cancel-order!|chart-liquidation-drag-prefill-actions|current-dispatch-fn" src/hyperopen/views/trading_chart/core.cljs
    rg -n "dispatch-chart-cancel-order!|chart-liquidation-drag-prefill-actions|current-dispatch-fn" src/hyperopen/views/trading_chart/actions.cljs
    rg -n "position-for-active-asset|build-position-overlay|normalized-open-orders-for-active-asset|memoized-candle-data" src/hyperopen/views/trading_chart/core.cljs
    rg -n "chart-view-model|position-for-active-asset|build-position-overlay|normalized-open-orders-for-active-asset|memoized-candle-data" src/hyperopen/views/trading_chart/vm.cljs
    rg -n "defn chart-top-menu|main-timeframes" src/hyperopen/views/trading_chart/toolbar.cljs

   The first, third, and fifth commands should produce no ownership definitions in `core.cljs` after extraction, except harmless wrapper references. The corresponding new namespace commands should show the moved owner definitions.

7. Namespace-size debt is reduced. `wc -l src/hyperopen/views/trading_chart/core.cljs src/hyperopen/views/trading_chart/runtime.cljs src/hyperopen/views/trading_chart/actions.cljs src/hyperopen/views/trading_chart/vm.cljs src/hyperopen/views/trading_chart/toolbar.cljs` shows every listed production namespace is `<= 500` lines, and `rg -n "src/hyperopen/views/trading_chart/core.cljs" dev/namespace_size_exceptions.edn` returns no match. `npm run lint:namespace-sizes` passes.

8. Browser storage is untouched. `git diff --name-only` shows no browser persistence owner outside the planned chart source/test/doc surfaces, and review of the diff shows no new `localStorage`, `sessionStorage`, IndexedDB, or browser-storage key changes.

9. Focused browser regression coverage runs before broad smoke. From `/Users/barry/.codex/worktrees/8eff/hyperopen`, run `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "trade chart right-click opens the custom context menu"` and `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "trade route preserves core accessibility affordances"` after implementation and review. Both commands must pass, or any failure must be recorded in this plan with the failing assertion and whether it is a product regression or an environment/tooling blocker.

10. UI/browser QA is accounted for by `browser_debugger` before final gates. Because this work touches `/hyperopen/src/hyperopen/views/**`, run `npm run test:playwright:smoke`, then `npm run qa:design-ui -- --targets trade-route --manage-local-app`, and then `npm run browser:cleanup`. The QA report must mark visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes as `PASS`, `FAIL`, or `BLOCKED` for widths `375`, `768`, `1280`, and `1440`, with evidence and residual blind spots. If tooling blocks this, record `BLOCKED` with the command output and do not claim browser QA passed.

11. High-risk desktop `/trade` geometry passes at `1280` and `1440`. The `browser_debugger` report must include DOM or bounding-box evidence for `[data-parity-id="trade-chart-panel"]`, `[data-parity-id="trade-orderbook-panel"]`, and `[data-parity-id="trade-account-tables-panel"]` before and after switching each account tab: `Balances`, `Positions`, `Open Orders`, `TWAP`, `Trade History`, `Funding History`, and `Order History`. Mark the overall browser QA result `FAIL` if the account panel outer width or height drifts across those tab switches, if the chart bottom edge stops aligning with the account panel top edge, or if the order-book bottom edge stops aligning with the account panel top edge.

12. Required repo gates pass from `/Users/barry/.codex/worktrees/8eff/hyperopen`: `npm run check`, `npm test`, and `npm run test:websocket`.

### Worker First-Slice Validation Evidence

Commands run by `worker` on 2026-04-19:

    npm run lint:delimiters -- --changed
    PASS: Delimiter preflight passed for 14 file(s).

    npm run test:runner:generate
    PASS: Generated test/test_runner_generated.cljs with 511 namespaces.

    npx shadow-cljs --force-spawn compile test
    PASS: Build completed. (1291 files, 1290 compiled, 0 warnings, 14.22s)

    node out/test.js
    BLOCKED first attempt: missing `lucide/dist/esm/icons/external-link.js` because local `node_modules` did not include the locked `lucide` package.

    npm install
    PASS: added 333 packages; no tracked package file changes.

    node out/test.js
    PASS: Ran 3290 tests containing 17949 assertions. 0 failures, 0 errors.

    npm run lint:hiccup
    PASS: No space-separated class strings found in :class attrs. No string keys found in literal :style maps.

    npm run lint:namespace-sizes
    PASS: Namespace size check passed.

    npm run lint:namespace-boundaries
    PASS: Namespace boundary check passed.

    npm run check
    PASS: Included tooling checks, docs/lint checks, namespace checks, release-asset tests, and Shadow CLJS app/portfolio/worker/test builds completed with 0 reported failures.

    npm test
    PASS: Generated 511 namespaces; test build completed with 0 warnings; ran 3290 tests containing 17949 assertions. 0 failures, 0 errors.

    npm run test:websocket
    PASS: ws-test build completed with 0 warnings; ran 449 tests containing 2701 assertions. 0 failures, 0 errors.

Browser-specific validation from criteria 9-11 was not run by the worker in this first production slice. The `reviewer` and `browser_debugger` phases remain pending above.

### Review and Browser QA Evidence

Read-only implementation review completed on 2026-04-19:

    PASS: No blocking source findings for correctness, regressions, security, or ownership boundaries.

    FINDING FIXED: `test/hyperopen/views/trading_chart/runtime_test.cljs` recorded `legend-updates` without asserting them.

Post-review focused validation after adding the missing assertion:

    npm run lint:delimiters -- --changed
    PASS: Delimiter preflight passed for 13 file(s).

    npx shadow-cljs --force-spawn compile test
    PASS: Test build completed with 0 warnings.

    node out/test.js
    PASS: Ran 3290 tests containing 17950 assertions. 0 failures, 0 errors.

Ownership evidence from 2026-04-19:

    rg -n "defn-? (mount-chart!|update-chart!|unmount-chart!|schedule-chart-decoration-pass!|apply-chart-accessibility!|ensure-visible-range-lifecycle!)" src/hyperopen/views/trading_chart/core.cljs
    PASS: No ownership definitions in core.

    rg -n "mount-chart!|update-chart!|unmount-chart!|schedule-chart-decoration-pass!|apply-chart-accessibility!|ensure-visible-range-lifecycle!" src/hyperopen/views/trading_chart/runtime.cljs
    PASS: Runtime owner definitions found in `runtime.cljs`.

    rg -n "dispatch-chart-cancel-order!|chart-liquidation-drag-prefill-actions|current-dispatch-fn" src/hyperopen/views/trading_chart/core.cljs
    PASS: Only core facade use of `actions/current-dispatch-fn` remains.

    rg -n "dispatch-chart-cancel-order!|chart-liquidation-drag-prefill-actions|current-dispatch-fn" src/hyperopen/views/trading_chart/actions.cljs
    PASS: Action owner definitions found in `actions.cljs`.

    rg -n "position-for-active-asset|build-position-overlay|normalized-open-orders-for-active-asset|memoized-candle-data" src/hyperopen/views/trading_chart/core.cljs
    PASS: No view-model owner definitions in core.

    rg -n "chart-view-model|position-for-active-asset|build-position-overlay|normalized-open-orders-for-active-asset|memoized-candle-data" src/hyperopen/views/trading_chart/vm.cljs
    PASS: View-model owner definitions and calls found in `vm.cljs`.

    rg -n "defn chart-top-menu|main-timeframes" src/hyperopen/views/trading_chart/toolbar.cljs
    PASS: Toolbar owner definitions found in `toolbar.cljs`.

    rg -n "src/hyperopen/views/trading_chart/core.cljs" dev/namespace_size_exceptions.edn
    PASS: No retired source namespace-size exception remains.

Browser QA completed by `browser_debugger` on 2026-04-19:

    npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "trade chart right-click opens the custom context menu"
    PASS: 1 passed.

    npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "trade route preserves core accessibility affordances"
    PASS: 1 passed.

    npm run test:playwright:smoke
    PASS: 22 passed.

    npm run qa:design-ui -- --targets trade-route --manage-local-app
    PASS: Governed trade-route design UI pass completed.

    npm run browser:cleanup
    PASS: Browser-inspection session cleanup completed; session list was empty after cleanup.

Browser QA artifact:

    tmp/browser-inspection/hyperopen-91mv-browser-qa/browser-report.json

High-risk desktop geometry evidence:

    1280x900 before and after tab switching:
    trade-chart-panel x=0 y=65 w=680 h=445 bottom=510
    trade-orderbook-panel x=680 y=65 w=280 h=445 bottom=510
    trade-account-tables-panel x=0 y=510 w=960 h=342 top=510
    PASS: All seven tabs switched with account rect deltas of 0, chart bottom flush with account top, and order-book bottom flush with account top.

    1440x900 before and after tab switching:
    trade-chart-panel x=0 y=65 w=840 h=445 bottom=510
    trade-orderbook-panel x=840 y=65 w=280 h=445 bottom=510
    trade-account-tables-panel x=0 y=510 w=1120 h=342 top=510
    PASS: All seven tabs switched with account rect deltas of 0, chart bottom flush with account top, and order-book bottom flush with account top.

Browser QA pass matrix:

    PASS: Visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes for widths 375, 768, 1280, and 1440.

Residual browser-QA blind spot:

    The standard design-review interaction sampling does not exhaust hover, active, disabled, or loading states that are not reachable in the default route state. No Hyperliquid parity compare was performed because it is out of scope for this ownership split.

### Final Gate Evidence

Final required gates run by the parent on 2026-04-19:

    npm run check
    PASS: test runner generation, tooling tests, docs checks, namespace-size checks, namespace-boundary checks, release-asset tests, and Shadow CLJS app/portfolio/worker/test builds completed with 0 reported failures.

    npm test
    PASS: Generated 511 namespaces; test build completed with 0 warnings; ran 3290 tests containing 17950 assertions. 0 failures, 0 errors.

    npm run test:websocket
    PASS: ws-test build completed with 0 warnings; ran 449 tests containing 2701 assertions. 0 failures, 0 errors.

## Idempotence and Recovery

This refactor should be safe to execute incrementally. Each extraction can be committed or reverted independently because `core.cljs` remains the compatibility facade. If a moved namespace causes an import cycle, back out only that extraction and re-establish the dependency direction listed in `Interfaces and Dependencies`.

If tests fail after a move, keep the focused failing test and route the public facade back to the previous implementation temporarily. Do not remove a failing compatibility assertion to make the refactor pass. If browser QA fails with visual or interaction drift, treat it as a regression unless this plan is updated with an explicit accepted behavior change.

If `npm run check` fails only because namespace-size exceptions are stale after successful extraction, update `dev/namespace_size_exceptions.edn` truthfully: remove retired entries, lower any remaining exception to the exact observed line count, and include `hyperopen-91mv` in the reason. Do not widen an exception above observed line count.

If Browser MCP or browser-inspection sessions are started, always stop them with `npm run browser:cleanup` before ending the session. If Playwright or browser-inspection tooling fails because dependencies are missing, restore the declared toolchain with the repository's normal install path, record the blocker or install step in this plan, and rerun the same validation command.

## Artifacts and Notes

RED phase evidence from `tdd_test_writer` on 2026-04-19:

    npm run lint:delimiters -- --changed
    Delimiter preflight passed for 6 file(s).

    npm run test:runner:generate
    Generated test/test_runner_generated.cljs with 511 namespaces.

    npx shadow-cljs --force-spawn compile test
    FAIL expected: The required namespace "hyperopen.views.trading-chart.actions" is not available, it was required by "hyperopen/views/trading_chart/actions_test.cljs".

Detailed RED report artifact: `tmp/multi-agent/hyperopen-91mv/red-phase-report.json`.

Initial orientation commands produced these relevant facts:

    git branch --show-current
    codex/trading-chart-core-layers

    git status --short
    <clean>

    wc -l src/hyperopen/views/trading_chart/core.cljs test/hyperopen/views/trading_chart/core_test.cljs
         886 src/hyperopen/views/trading_chart/core.cljs
        1333 test/hyperopen/views/trading_chart/core_test.cljs
        2219 total

    bd show hyperopen-91mv --json
    [{"id":"hyperopen-91mv","title":"Split trading chart core ownership","status":"open","priority":2,"issue_type":"task"}]

Current `core.cljs` ownership map by definition line:

- Lines 21-196: main timeframes, scheduler dynamic vars, memoization helpers, and memoized view-model/callback/runtime option helpers.
- Lines 196-480: visible-range lifecycle, chart creation/update/unmount, scheduled decoration, accessibility, and render hook.
- Lines 501-635: open orders, fills, dispatch bridge, liquidation drag prefill, and overlay size formatting.
- Lines 640-725: `chart-top-menu`.
- Lines 727-799: `chart-canvas`.
- Lines 801-886: `trading-chart-view`.

Current focused tests cover:

- Lines 256-456: toolbar and freshness cue Hiccup behavior.
- Lines 475-540: candle and indicator memoization.
- Lines 543-1007: chart canvas lifecycle, scheduling, visible range, series swap, and unmount cleanup.
- Lines 1008-1158: trading chart view-model identity and runtime option derivation.
- Lines 1159-1333: dispatch fallback and liquidation drag prefill callbacks.

## Revision Note

2026-04-19T13:36Z: Initial spec_writer ExecPlan created for `hyperopen-91mv`. The plan freezes scope, non-goals, acceptance criteria, milestones, focused test expectations, namespace-size targets, required repo gates, and UI/browser QA accounting without editing `/hyperopen/src/**`.

2026-04-19T13:45Z: Refreshed after read-only review. Added explicit preservation of `core/main-timeframes`, required focused chart Playwright commands before broad smoke, inserted the `browser_debugger` phase after read-only review and before final gates, and expanded acceptance with `1280` and `1440` desktop `/trade` geometry evidence across all seven account tabs.

2026-04-19T13:52Z: Recorded the acceptance and edge-case proposal phase and froze the first implementation slice in `tmp/multi-agent/hyperopen-91mv/approved-test-contract.json`. The approved slice intentionally selects owner-level RED tests for `core`, `toolbar`, `actions`, `vm`, and `runtime` rather than all proposed edge cases so implementation can begin with a coherent, reviewable boundary split.
