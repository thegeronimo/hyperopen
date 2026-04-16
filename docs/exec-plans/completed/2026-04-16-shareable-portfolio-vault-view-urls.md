# Shareable Portfolio and Vault View URLs

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The tracked `bd` issue for this work was `hyperopen-jwa8`, which is closed after completed validation.

## Purpose / Big Picture

Users can currently change the portfolio and vault views by selecting ranges, chart tabs, benchmarks, table filters, sort order, and tabs, but most of those choices live only in in-memory UI state or browser storage. When a user shares the browser URL, the recipient often sees the route default or their own local preference instead of the view the sender was looking at. After this change, interacting with the portfolio and vault pages updates the query string so the current view can be copied from the address bar and restored on another browser.

The visible proof is simple: open `/portfolio`, change the range to `3M`, add ETH as a benchmark, copy the URL, reload it in a clean browser context, and see `3M` plus BTC and ETH selected. Do the same on `/vaults` for range, filters, sort, and page, and on `/vaults/<address>` for range, chart series, benchmarks, primary tab, activity tab, and side filter.

## Progress

- [x] (2026-04-16 11:05 EDT) Inspected portfolio and vault route, action, VM, and startup code to identify the actual UI state that must become shareable.
- [x] (2026-04-16 11:05 EDT) Created and claimed `bd` issue `hyperopen-jwa8` for the implementation work.
- [x] (2026-04-16 11:05 EDT) Created this active ExecPlan with parameter schema, milestones, validation, and recovery guidance.
- [x] (2026-04-16 11:06 EDT) Ran `npm run lint:docs` and `npm run lint:docs:test`; both passed for this active ExecPlan.
- [x] (2026-04-16 12:18 EDT) Implemented pure query-state parsers and serializers for portfolio, vault list, and vault detail routes.
- [x] (2026-04-16 12:18 EDT) Implemented startup, route-change, and current-location restore so URL query state wins over defaults and localStorage restores.
- [x] (2026-04-16 12:18 EDT) Added the `:effects/replace-shareable-route-query` effect and emitted it from portfolio and vault shareable-view actions after projection/persistence and before heavy fetch effects.
- [x] (2026-04-16 12:18 EDT) Added unit, action, wiring, startup, route-query, and Playwright regression coverage for URL updates and deep-link restore behavior.
- [x] (2026-04-16 12:18 EDT) Ran required repository gates and governed UI browser QA; all required checks passed.
- [x] (2026-04-16 12:24 EDT) Added explicit `bench=` serialization for cleared benchmark lists, reran the affected test and Playwright coverage, and moved this ExecPlan to `completed/`.

## Surprises & Discoveries

- Observation: portfolio range and vault range are currently restored from `localStorage`, not from the URL.
  Evidence: `/hyperopen/src/hyperopen/portfolio/actions.cljs` defines `portfolio-summary-time-range-storage-key`, and `/hyperopen/src/hyperopen/vaults/infrastructure/persistence.cljs` stores `vaults-snapshot-range`.

- Observation: the router currently stores only normalized path state and ignores query parameters for portfolio and vault routes.
  Evidence: `/hyperopen/src/hyperopen/router.cljs` provides generic `query-param-value` helpers and trade-specific query readers, but `set-route!` writes only `{:path normalized-path}` to `:router`.

- Observation: portfolio and vault benchmark state already supports multiple selected benchmarks, including market coins and vault references.
  Evidence: `/hyperopen/src/hyperopen/portfolio/actions.cljs` stores `:portfolio-ui :returns-benchmark-coins`, and `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs` stores `:vaults-ui :detail-returns-benchmark-coins`.

- Observation: one vault range action is shared by both the vault list and vault detail chart.
  Evidence: `/hyperopen/src/hyperopen/vaults/application/list_commands.cljs:set-vaults-snapshot-range` writes `[:vaults-ui :snapshot-range]` and also closes both detail timeframe dropdowns.

- Observation: route query restore must run after localStorage restore, or shared links can be overwritten during startup.
  Evidence: `/hyperopen/src/hyperopen/startup/init.cljs` restores vault range in the critical path and restores portfolio range again during deferred UI restore.

- Observation: the initial `npm run check` blocker was local dependency state, not this change.
  Evidence: `npm ci` restored the missing Node packages, and the final `npm run check` passed on 2026-04-16.

- Observation: the Browser MCP design-review target for the vault list is named `vaults-route`, not `vault-list-route`.
  Evidence: `npm run qa:design-ui -- --targets portfolio-route,trader-portfolio-route,vault-list-route,vault-detail-route --manage-local-app` failed with an unknown target id, then the same command with `vaults-route` completed with `reviewOutcome: PASS`.

- Observation: Playwright debug-state polling was not reliable for the `/portfolio?spectate=...` restored account tab and chart tab, even while the visible UI had restored correctly.
  Evidence: the portfolio screenshot showed `3M`, `Returns`, BTC, ETH, and `Positions` selected, so the committed Playwright regression asserts those restored visible controls directly for that case.

- Observation: the managed Playwright dev app can expose a blank page for more than the previous 20 second debug-bridge wait on cold starts, even though subsequent tests in the same run pass.
  Evidence: the shareable URL spec twice failed before feature assertions in `waitForDebugBridge`, while tests 2-4 passed immediately afterward; the helper timeout is now 45 seconds.

- Observation: an absent `bench` key is ambiguous because it can mean either "no URL opinion; use defaults" or "the sender cleared all selected benchmarks."
  Evidence: portfolio and vault detail serializers now emit `bench=` when the selected benchmark list is intentionally empty, while a URL with no `bench` key preserves default/local behavior.

## Decision Log

- Decision: represent shareable view state in route-specific query parameters instead of adding browser storage keys.
  Rationale: the user need is link sharing, and browser storage is device-local. Query state makes the current view portable across sessions and recipients.
  Date/Author: 2026-04-16 / Codex

- Decision: use `history.replaceState` for interaction-driven URL updates, not `pushState`.
  Rationale: changing a range or adding a benchmark should keep the current address bar accurate without adding a new browser-history entry for every chip, dropdown, or filter click.
  Date/Author: 2026-04-16 / Codex

- Decision: preserve existing unrelated query parameters such as `spectate` while replacing shareable portfolio or vault parameters.
  Rationale: spectate mode is already URL-addressable and must not be dropped when a user changes a chart range or benchmark.
  Date/Author: 2026-04-16 / Codex

- Decision: serialize a full route-specific view snapshot after any shareable interaction rather than only serializing the changed field.
  Rationale: a link should reproduce what the sender sees even when the recipient has different localStorage preferences. Empty transient state such as open dropdowns and search-draft text should still be omitted.
  Date/Author: 2026-04-16 / Codex

- Decision: keep transient UI state out of the query string.
  Rationale: open menus, hover state, loading flags, modal fields, and benchmark search draft text do not define the durable view being shared and would make links noisy or stale.
  Date/Author: 2026-04-16 / Codex

- Decision: serialize cleared benchmark selections as an explicit empty `bench=` parameter.
  Rationale: links need to distinguish an intentionally empty benchmark list from a link that simply does not specify benchmarks and should fall back to default/local state.
  Date/Author: 2026-04-16 / Codex

## Outcomes & Retrospective

Completed on 2026-04-16. Portfolio and vault shareable controls now serialize route-owned query parameters with `history.replaceState`, preserve unrelated parameters such as `spectate`, and restore recognized values on startup and browser route changes. The implementation increased code surface modestly by adding pure query-state modules and a route coordinator, but reduced behavioral complexity at the user boundary because the address bar is now the portable source of truth for these view choices instead of a mix of route path, in-memory state, and browser-local preferences.

Validation completed:

- `npx playwright test tools/playwright/test/shareable-view-url.spec.mjs` passed: 4 tests.
- `npm run qa:design-ui -- --targets portfolio-route,trader-portfolio-route,vaults-route,vault-detail-route --manage-local-app` passed with run id `design-review-2026-04-16T16-12-28-638Z-9b724ee9`; all six passes were `PASS` across widths `375`, `768`, `1280`, and `1440`.
- `npm run browser:cleanup` passed and reported no remaining sessions to stop.
- `npm run check` passed.
- `npm test` passed: 3204 tests, 17076 assertions.
- `npm run test:websocket` passed: 432 tests, 2479 assertions.

## Context and Orientation

The main application state is a ClojureScript map held in an atom. UI actions return effect vectors such as `[:effects/save path value]`, and the runtime applies those effects. A "projection" is a store update that changes what the UI shows. A "heavy I/O effect" is a network, websocket, or module-loading effect that should happen only after the visible UI state has already changed. The central effect-order rules live in `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`.

Portfolio routes are parsed in `/hyperopen/src/hyperopen/portfolio/routes.cljs`. The canonical portfolio page is `/portfolio`, and a trader portfolio page is `/portfolio/trader/<address>`. Portfolio UI state lives under `:portfolio-ui` and is initialized in `/hyperopen/src/hyperopen/state/app_defaults.cljs:default-portfolio-ui-state`. Important portfolio action functions are in `/hyperopen/src/hyperopen/portfolio/actions.cljs`: `select-portfolio-summary-scope`, `select-portfolio-summary-time-range`, `select-portfolio-chart-tab`, `set-portfolio-account-info-tab`, `select-portfolio-returns-benchmark`, `remove-portfolio-returns-benchmark`, and `clear-portfolio-returns-benchmark`.

Vault routes are parsed in `/hyperopen/src/hyperopen/vaults/infrastructure/routes.cljs`. The vault list route is `/vaults`, and a vault detail route is `/vaults/<address>`. Vault UI state lives under `:vaults-ui` and is initialized in `/hyperopen/src/hyperopen/state/app_defaults.cljs:default-vaults-ui-state`. Vault list actions are in `/hyperopen/src/hyperopen/vaults/application/list_commands.cljs`; vault detail actions are in `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs`.

Startup restore is split between `/hyperopen/src/hyperopen/startup/init.cljs`, `/hyperopen/src/hyperopen/startup/restore.cljs`, and `/hyperopen/src/hyperopen/app/startup.cljs`. The current startup flow restores some localStorage preferences before router initialization, initializes the router, then restores additional deferred UI state after the first render. Query-state restore must be placed carefully so URL values win over both default state and localStorage.

Existing browser history effects are in `/hyperopen/src/hyperopen/runtime/app_effects.cljs` and `/hyperopen/src/hyperopen/runtime/effect_adapters/common.cljs`. Navigation currently emits `:effects/push-state` or `:effects/replace-state` through `/hyperopen/src/hyperopen/runtime/action_adapters/navigation.cljs`. The new work should add a focused effect for replacing only the shareable route query state from the current store and current browser location.

The stable URL vocabulary for this plan is:

- `range`: selected time window. Use `24h`, `7d`, `30d`, `3m`, `6m`, `1y`, `2y`, and `all` as public URL tokens. Internally these map to `:day`, `:week`, `:month`, `:three-month`, `:six-month`, `:one-year`, `:two-year`, and `:all-time`.
- `chart`: active chart series or portfolio chart tab. Valid values are `returns`, `account-value`, and `pnl`.
- `bench`: selected return benchmarks. This parameter is repeated once per selected benchmark, for example `bench=BTC&bench=ETH&bench=vault%3A0xabc...`. Preserve order.
- `scope`: portfolio summary scope. Valid values are `all` and `perps`.
- `tab`: route-specific selected tab. On portfolio routes it means the account-info tab. On vault detail routes it means the primary vault tab.
- `activity`: vault detail activity tab, such as `performance-metrics`, `positions`, `trade-history`, or `deposits-withdrawals`.
- `side`: vault detail activity direction filter. Valid values are `all`, `long`, and `short`.
- `q`: vault list search query.
- `roles`: vault list role filters as a comma-separated list using `leading`, `deposited`, and `others`.
- `closed`: vault list closed-vault toggle. Use `1` when closed vaults are included; omit or ignore other values for false.
- `sort`: vault list sort as `<column>:<direction>`, for example `tvl:desc` or `apr:asc`.
- `page` and `pageSize`: vault list pagination integers.

The resulting share URLs should look like these examples:

    /portfolio?range=3m&scope=all&chart=returns&bench=BTC&bench=ETH&tab=performance-metrics
    /portfolio/trader/0x3333333333333333333333333333333333333333?range=1y&scope=all&chart=returns&bench=BTC&bench=vault%3A0x4444444444444444444444444444444444444444
    /vaults?range=30d&q=lp&roles=deposited,others&closed=1&sort=tvl:desc&page=2&pageSize=25
    /vaults/0x3333333333333333333333333333333333333333?range=6m&chart=returns&bench=BTC&bench=ETH&tab=vault-performance&activity=performance-metrics&side=all

## Plan of Work

First add pure query-state modules. Pure means the functions take strings and maps as input and return data without touching browser APIs, localStorage, network, or the global store. Add `/hyperopen/src/hyperopen/portfolio/query_state.cljs` for portfolio parsing and serialization. It should expose functions equivalent to `parse-portfolio-query`, `portfolio-query-state`, `apply-portfolio-query-state`, and `portfolio-query-params`. `parse-portfolio-query` should accept a URL search string or `URLSearchParams`-like input and return normalized portfolio UI values. `apply-portfolio-query-state` should merge only recognized values into the state map. `portfolio-query-params` should read `:portfolio-ui` and return a deterministic ordered set of query pairs for `range`, `scope`, `chart`, repeated `bench`, and `tab`.

Add `/hyperopen/src/hyperopen/vaults/application/query_state.cljs` for vault parsing and serialization. It should expose functions equivalent to `parse-vault-list-query`, `parse-vault-detail-query`, `apply-vault-query-state`, and `vault-query-params`. The list serializer must cover `range`, `q`, `roles`, `closed`, `sort`, `page`, and `pageSize`. The detail serializer must cover `range`, `chart`, repeated `bench`, `tab`, `activity`, and `side`. Use existing normalizers from `/hyperopen/src/hyperopen/vaults/application/ui_state.cljs` and `/hyperopen/src/hyperopen/portfolio/actions.cljs` so invalid query values fall back exactly like invalid UI events.

Then add a route-level query coordinator. A good target is a new namespace such as `/hyperopen/src/hyperopen/route_query_state.cljs`. This namespace should decide which surface is active by using `/hyperopen/src/hyperopen/portfolio/routes.cljs` and `/hyperopen/src/hyperopen/vaults/infrastructure/routes.cljs`. It should provide a pure function that receives `state`, current `pathname`, and current `search`, removes only route-owned query keys for the active surface, preserves unrelated query keys such as `spectate`, appends the serialized route-owned keys, and returns the browser path to pass to `replaceState`. It should also provide restore helpers that parse the current location and apply recognized query values to `:portfolio-ui` or `:vaults-ui` in one `swap!`.

Next wire startup and route changes. In `/hyperopen/src/hyperopen/startup/init.cljs`, accept a new `restore-route-query-state!` dependency and call it after localStorage-backed route preferences have been restored. Call it once in the critical restore path after `restore-spectate-mode-url!` and `restore-trade-route-tab!`, call it again after router initialization and the second vault range restore, and call it again after deferred portfolio range restore. This repeated restore is intentional and idempotent: it guarantees URL params win even if a later localStorage restore would otherwise overwrite them.

In `/hyperopen/src/hyperopen/app/startup.cljs`, pass the new restore dependency into `startup-init/init!`. Update `make-route-change-handler` so a browser Back or Forward event restores route query state before route module loading or route-specific fetches are dispatched. This matters for a vault detail URL whose query chooses `activity=order-history`, because the activity tab should be restored before the route loader decides which history effects to request.

Add the runtime effect for URL updates. Create a small effect adapter, for example `/hyperopen/src/hyperopen/runtime/effect_adapters/route_query.cljs`, with a function that reads the current store after prior projection effects have applied, reads `js/location.pathname` and `js/location.search`, asks `hyperopen.route-query-state` for the replacement path, and calls `history.replaceState` only if the generated browser path differs from the current path plus search. Register it in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` and the runtime registration area so actions can emit `[:effects/replace-shareable-route-query]`. Add this effect id to the persistence phase in `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`.

Then update portfolio actions. In `/hyperopen/src/hyperopen/portfolio/actions.cljs`, append `[:effects/replace-shareable-route-query]` to the actions that change shareable state. For actions with heavy fetch effects, keep the order as projection effects, localStorage persistence when present, URL replacement, then heavy fetch effects. The affected actions are `select-portfolio-summary-scope`, `select-portfolio-summary-time-range`, `select-portfolio-chart-tab`, `set-portfolio-account-info-tab`, `select-portfolio-returns-benchmark`, `remove-portfolio-returns-benchmark`, and `clear-portfolio-returns-benchmark`. Do not write the query when only benchmark search text or suggestion-menu open state changes.

Update vault list and detail actions. In `/hyperopen/src/hyperopen/vaults/application/list_commands.cljs`, append the URL replacement effect when changing search query, role filters, snapshot range, sort, page size, or page. In `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs`, append it when changing primary detail tab, activity tab, activity direction filter, chart series, selected detail benchmarks, removed benchmarks, or cleared benchmarks. For `set-vaults-snapshot-range`, preserve the existing projection, localStorage, and benchmark-fetch ordering, and place the URL replacement effect after the localStorage effect but before benchmark fetch effects.

Update route query semantics around internal navigation. `/hyperopen/src/hyperopen/runtime/action_adapters/navigation.cljs` should continue to navigate to clean route paths for normal in-app route changes. It should not automatically carry portfolio query state from `/portfolio` to `/vaults` or vault query state from one route family to another. The replace-query effect should be responsible for adding route-owned query state only after the user changes shareable controls on the current route. Existing spectate behavior from `/hyperopen/src/hyperopen/account/spectate_mode_links.cljs` must continue to work.

Finally add tests and browser coverage. The tests should first prove the pure parsers and serializers, then prove startup restore precedence, then prove action effect order, and then prove browser behavior with Playwright. Browser MCP or browser-inspection may be useful for exploratory checking, but any stable interaction path discovered there must be converted into Playwright coverage unless the specific check is purely exploratory.

## Concrete Steps

From the repository root, `/Users/barry/.codex/worktrees/8bbc/hyperopen`, begin by adding unit tests for the pure query state modules:

    npm test -- --focus hyperopen.portfolio.query-state-test
    npm test -- --focus hyperopen.vaults.application.query-state-test

The first run should fail because the namespaces do not exist yet. Implement the source namespaces and rerun until both pass.

Add route query coordinator and restore tests:

    npm test -- --focus hyperopen.route-query-state-test
    npm test -- --focus hyperopen.startup.route-query-state-test

These tests should cover all of the following cases: portfolio URL params override localStorage range, vault URL params override localStorage range, invalid params are ignored or normalized to existing defaults, unrelated params such as `spectate` survive serialization, repeated `bench` params preserve order, and route-owned params are removed when serializing a different route family.

Add action tests around effect emission:

    npm test -- --focus hyperopen.portfolio.actions-test
    npm test -- --focus hyperopen.vaults.application.list-commands-test
    npm test -- --focus hyperopen.vaults.actions-test

Existing action tests will need expectation updates because shareable actions will now include `:effects/replace-shareable-route-query`. For actions with fetch effects, assert the URL replacement effect appears after projection and localStorage effects and before `:effects/fetch-candle-snapshot` or vault API effects.

Add effect-order and navigation tests:

    npm test -- --focus hyperopen.runtime.effect-order-contract-test
    npm test -- --focus hyperopen.runtime.action-adapters.navigation-test

The effect-order contract must classify `:effects/replace-shareable-route-query` as persistence. Navigation tests should continue to prove that `spectate` is preserved on route navigation and that route-owned portfolio or vault query params do not leak across unrelated route changes.

Add a committed Playwright regression file, for example `/hyperopen/tools/playwright/test/shareable-view-url.spec.mjs`, using existing Playwright helpers under `/hyperopen/tools/playwright/**`. Cover at least these stable paths:

    /portfolio
    /portfolio/trader/0x3333333333333333333333333333333333333333
    /vaults
    /vaults/0x3333333333333333333333333333333333333333

The Playwright tests should interact with the actual controls rather than mutating the store directly where practical. They should assert `page.url()` contains the expected query parameters after interaction, reload or open the copied URL in a fresh page, and assert the selected controls or chips are restored.

Run the smallest Playwright command first:

    npx playwright test tools/playwright/test/shareable-view-url.spec.mjs

Then run the governed UI design review for the touched routes:

    npm run qa:design-ui -- --targets portfolio-route,trader-portfolio-route,vault-list-route,vault-detail-route --manage-local-app

Record all six required browser-QA passes as `PASS`, `FAIL`, or `BLOCKED`: visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf. Record coverage for widths `375`, `768`, `1280`, and `1440`. If Browser MCP or browser-inspection sessions were created, stop them before concluding:

    npm run browser:cleanup

After focused tests and browser checks pass, run required repository gates:

    npm run check
    npm test
    npm run test:websocket

Because this work touches UI interactions, also keep the focused Playwright command in the final validation summary. If a local port conflict blocks a Playwright command, record the blocked command, the port, and the alternate browser evidence in this plan rather than silently skipping browser validation.

## Validation and Acceptance

Acceptance requires all of the following observable behavior:

- On `/portfolio`, selecting `3M`, selecting chart tab `Returns`, adding ETH, and selecting account tab `Positions` updates the browser URL to include `range=3m`, `chart=returns`, repeated `bench` values for BTC and ETH, and `tab=positions`.
- Loading that copied `/portfolio` URL in a fresh browser context restores `3M`, `Returns`, the BTC and ETH benchmark chips, and the `Positions` account tab.
- On `/portfolio/trader/<address>`, the same portfolio query parameters work without converting the route into spectate mode and without losing the trader address in the path.
- On `/vaults`, changing range, search, role filters, closed-vault visibility, sort, page size, and page updates the URL with `range`, `q`, `roles`, `closed`, `sort`, `pageSize`, and `page`.
- Loading that copied `/vaults` URL restores the same list controls and the same visible list page after data loads.
- On `/vaults/<address>`, selecting range `6M`, chart `Returns`, adding ETH as a benchmark, selecting primary tab `Vault Performance`, selecting activity tab `Trade History`, and setting side filter `Long` updates the URL with `range=6m`, `chart=returns`, repeated `bench`, `tab=vault-performance`, `activity=trade-history`, and `side=long`.
- Loading that copied vault detail URL restores the same chart controls, benchmark chips, primary tab, activity tab, and side filter. If the selected activity tab requires route data, the correct fetch action is issued after restore.
- Existing `spectate` query parameters survive all portfolio and vault URL replacement effects.
- Invalid query values do not throw exceptions and do not write invalid state. They fall back through existing portfolio and vault normalizers.
- Open dropdown state, hover state, benchmark search draft text, loading flags, and transfer modal fields never appear in the URL.
- `npx playwright test tools/playwright/test/shareable-view-url.spec.mjs`, `npm run check`, `npm test`, and `npm run test:websocket` pass.
- Governed browser QA is explicitly accounted for across the four required widths and six required passes.

## Idempotence and Recovery

The pure query parsers and serializers are safe to test repeatedly because they do not touch browser APIs or storage. The restore helper must be idempotent: running it multiple times against the same `pathname` and `search` should leave the store in the same state after the first successful restore. The URL replacement effect must also be idempotent: if the generated path equals the current path plus search, it should do nothing.

If startup restore causes localStorage to override URL query state, do not remove localStorage restore. Instead, reapply route query restore after the localStorage restore or make the localStorage restore skip only the route-specific key when the current URL supplies that key. URL precedence is required, but existing local preference behavior should remain for routes without query params.

If adding URL replacement to actions breaks effect-order tests, keep the new effect classified as persistence and repair action ordering so projection still comes first and heavy fetches still come last. Do not move benchmark fetches before UI projection to make tests easier.

If Browser MCP exploration is used to find selectors or timing, promote the stable route interactions into Playwright before finishing. Clean up all browser-inspection sessions with `npm run browser:cleanup`.

Do not run `git pull --rebase` or `git push` unless the user explicitly requests remote sync in the current session.

## Artifacts and Notes

The route-owned query keys are:

    portfolio routes:
      range, scope, chart, bench, tab

    vault list route:
      range, q, roles, closed, sort, page, pageSize

    vault detail route:
      range, chart, bench, tab, activity, side

The route-independent query keys that must be preserved include:

    spectate

The exact range token mapping is:

    :day -> 24h
    :week -> 7d
    :month -> 30d
    :three-month -> 3m
    :six-month -> 6m
    :one-year -> 1y
    :two-year -> 2y
    :all-time -> all

The first manual smoke scenario after implementation should be:

    Open /portfolio.
    Select range 3M.
    Confirm the address bar contains range=3m.
    Add ETH as a benchmark.
    Confirm the address bar contains both bench=BTC and bench=ETH.
    Copy the URL into a fresh context.
    Confirm 3M and both benchmark chips are restored.

## Interfaces and Dependencies

In `/hyperopen/src/hyperopen/portfolio/query_state.cljs`, define public functions with semantics equivalent to:

    parse-portfolio-query
      Input: search string or URLSearchParams-compatible value.
      Output: map containing any recognized normalized keys among :summary-time-range, :summary-scope, :chart-tab, :returns-benchmark-coins, and :account-info-tab.

    apply-portfolio-query-state
      Input: app state and parsed query map.
      Output: app state with recognized values merged into :portfolio-ui.

    portfolio-query-params
      Input: app state.
      Output: ordered key/value pairs for range, scope, chart, repeated bench, and tab.

In `/hyperopen/src/hyperopen/vaults/application/query_state.cljs`, define public functions with semantics equivalent to:

    parse-vault-list-query
      Output: normalized list state for :snapshot-range, :search-query, role filters, :filter-closed?, :sort, :user-vaults-page-size, and :user-vaults-page.

    parse-vault-detail-query
      Output: normalized detail state for :snapshot-range, :detail-chart-series, :detail-returns-benchmark-coins, :detail-tab, :detail-activity-tab, and :detail-activity-direction-filter.

    apply-vault-query-state
      Input: app state and parsed query map.
      Output: app state with recognized values merged into :vaults-ui.

    vault-query-params
      Input: app state and parsed vault route kind.
      Output: ordered key/value pairs for either the list route or detail route.

In `/hyperopen/src/hyperopen/route_query_state.cljs`, define public functions with semantics equivalent to:

    restore-current-route-query-state!
      Input: store atom.
      Behavior: read current browser path and search, parse route-owned query state, and apply it to the store in one swap.

    replace-shareable-route-query!
      Input: store atom.
      Behavior: read current browser path and search, preserve unrelated params, replace route-owned params from current store state, and call history.replaceState only when the path or query would change.

In `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`, register the effect id:

    :effects/replace-shareable-route-query

This effect must run after save/save-many projections in the same action emission. It should not accept untrusted raw query strings from UI actions; it should derive the URL from normalized store state so generated links are canonical.

Plan revision note: 2026-04-16 11:05 EDT - Initial active ExecPlan created from the user's requested portfolio and vault URL-parameter planning task. The plan records the chosen parameter schema, restore precedence, implementation milestones, and validation contract.

Plan revision note: 2026-04-16 11:06 EDT - Recorded docs-lint validation and the local `npm run check` blocker caused by missing multi-agent Node packages.

Plan revision note: 2026-04-16 12:18 EDT - Implementation completed. Recorded final parser, restore, effect, action, test, Playwright, governed browser-QA, and required gate results.

Plan revision note: 2026-04-16 12:24 EDT - Added explicit cleared-benchmark URL semantics, updated final validation counts, closed `hyperopen-jwa8`, and moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/`.

Plan revision note: 2026-04-16 12:24 EDT - Increased the Playwright debug bridge timeout for cold managed-server starts and reran `npx playwright test tools/playwright/test/shareable-view-url.spec.mjs`, `npm run check`, `npm run test:websocket`, and `npm run browser:cleanup`.
