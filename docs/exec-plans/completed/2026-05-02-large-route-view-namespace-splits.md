# Split Large Route View Namespaces

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`. It is self-contained so a future contributor can continue from this file without relying on conversation history.

Tracked issue: `hyperopen-6sf8` ("Split large route view namespaces"). This issue was discovered from `hyperopen-kefh` because the trade route and large route surfaces overlap with the root-bundle follow-up work, but this plan is about source ownership and namespace-size governance rather than bundle-size benchmarking.

## Purpose / Big Picture

The repository has a namespace-size guard that keeps production ClojureScript namespaces from becoming broad ownership buckets. Five route-level view files are currently allowed through `dev/namespace_size_exceptions.edn`: `src/hyperopen/views/leaderboard_view.cljs`, `src/hyperopen/views/l2_orderbook_view.cljs`, `src/hyperopen/views/trade_view.cljs`, `src/hyperopen/views/api_wallets_view.cljs`, and `src/hyperopen/views/account_equity_view.cljs`. These files mix top-level route composition with reusable rows, modals, model math, memoization, and panel rendering. That makes future UI edits riskier because a small change often requires loading and reasoning about an entire route.

After this plan is implemented, each route root remains the stable public entry point, but the heavy responsibilities live in focused namespaces under the same feature area. A human can see the work by running the existing focused view tests and by running `npm run lint:namespace-sizes`; the lint should pass without size exceptions for the five route roots.

## Progress

- [x] (2026-05-02 00:49Z) Confirmed the worktree was clean and detached, then created branch `codex/large-route-view-splits`.
- [x] (2026-05-02 00:50Z) Created `bd` issue `hyperopen-6sf8` and linked it to `hyperopen-kefh` with a `discovered-from` dependency.
- [x] (2026-05-02 00:51Z) Inspected the five oversized route files, their current line counts, and the focused test suites that exercise their public behavior.
- [x] (2026-05-02 00:52Z) Established the RED gate by removing the five root entries from `dev/namespace_size_exceptions.edn`; `npm run lint:namespace-sizes` failed only on the five requested route roots.
- [x] (2026-05-02 01:08Z) Split `leaderboard_view.cljs` into a route root plus row, control, state, and style component namespaces while preserving `leaderboard-view` and `route-view`.
- [x] (2026-05-02 01:08Z) Split `api_wallets_view.cljs` into a route root plus common formatting, form, row/table, and modal component namespaces while preserving `api-wallets-view` and `route-view`.
- [x] (2026-05-02 01:08Z) Split `account_equity_view.cljs` so metrics, funding actions, display formatting, and panel rendering have explicit owners while preserving public display helpers, `funding-actions-view`, `account-equity-metrics`, `reset-account-equity-metrics-cache!`, and `account-equity-view`.
- [x] (2026-05-02 01:08Z) Split `l2_orderbook_view.cljs` so orderbook/trades model helpers and panel components are no longer owned by the route root while preserving the existing public helper API used by tests and `trade_view.cljs`.
- [x] (2026-05-02 01:08Z) Split `trade_view.cljs` so the loading shell and panel layout helpers live outside the route root while preserving `trade-view`.
- [x] (2026-05-02 01:08Z) Ran focused view tests, namespace lint, and namespace boundary lint.
- [x] (2026-05-02 01:32Z) Ran full required repo gates and deterministic Playwright smoke coverage.

## Surprises & Discoveries

- Observation: The current worktree already contains the account-surface lazy-loading split from `hyperopen-kefh`.
  Evidence: `src/hyperopen/views/trade_view.cljs` no longer statically requires account info or account equity; it resolves account surface exports through `hyperopen.surface-modules`.

- Observation: Existing tests directly reference public helper functions in `l2_orderbook_view.cljs` and public UI helpers in `account_equity_view.cljs`.
  Evidence: `test/hyperopen/views/l2_orderbook_view_test.cljs` calls functions such as `resolve-base-symbol`, `normalize-trade`, `recent-trades-for-coin`, `order-row`, and `l2-orderbook-panel`. `test/hyperopen/views/account_equity_view_test.cljs` calls `metric-row`, `tooltip`, and `pnl-display`; `test/hyperopen/views/account_equity_view_token_price_test.cljs` reaches private var `token-price-usd`.

- Observation: The RED lint failure is isolated to the requested route roots.
  Evidence: `npm run lint:namespace-sizes` reported missing exceptions for `api_wallets_view.cljs`, `leaderboard_view.cljs`, `trade_view.cljs`, `l2_orderbook_view.cljs`, and `account_equity_view.cljs`, with no additional source or test namespace-size failures introduced by the RED step.

- Observation: Local `node_modules` was missing the locked `lucide` package even though `package.json` and `package-lock.json` declare it.
  Evidence: `npm ls lucide --depth=0` returned empty and `ls node_modules/lucide/dist/esm/icons/external-link.js` failed before `npm ci`. After `npm ci`, focused Node runtime tests completed successfully.

- Observation: The route roots now sit comfortably below the namespace-size threshold.
  Evidence: `wc -l` reports 113 lines for `leaderboard_view.cljs`, 79 for `api_wallets_view.cljs`, 25 for `account_equity_view.cljs`, 98 for `l2_orderbook_view.cljs`, and 394 for `trade_view.cljs`; all new production namespaces in the split are below 500 lines.

- Observation: Splitting the leaderboard route made the route module load fast enough to expose a Playwright helper race.
  Evidence: `npm run test:playwright:smoke` initially failed the two leaderboard cache smokes because `visitRoute("/leaderboard")` direct-loaded `/leaderboard`, then reset QA fixtures and dispatched an in-app navigate to `/leaderboard`, counting two initial leaderboard endpoint requests. Bootstrapping non-trade `visitRoute` calls at `/trade` and then performing the single explicit in-app navigate restored the one-request contract.

## Decision Log

- Decision: Use the namespace-size lint as the RED test for this refactor.
  Rationale: The behavior is intentionally unchanged, so the failing contract should be the architectural guardrail this work is meant to satisfy. Removing the five stale exceptions before source edits makes `npm run lint:namespace-sizes` fail for the exact reason this plan fixes. Existing focused tests then protect behavioral parity during the split.
  Date/Author: 2026-05-02 / Codex

- Decision: Keep root namespaces as compatibility facades where tests or callers use public helper vars.
  Rationale: Preserving public APIs avoids a broad caller migration and matches the repository rule to preserve public APIs unless explicitly requested. The route roots can delegate public helpers to focused namespaces while still dropping below the line-count limit.
  Date/Author: 2026-05-02 / Codex

- Decision: Add an in-flight guard to `api-fetch-leaderboard!` while fixing the browser smoke failure.
  Rationale: The Playwright failure was ultimately caused by the helper's bootstrap path, but the investigation showed the leaderboard effect had no protection against duplicate route-load effects while an existing leaderboard request was already loading. Returning `{:source :in-flight}` for non-forced requests keeps duplicate route refreshes from starting unnecessary network work without blocking explicit force refresh.
  Date/Author: 2026-05-02 / Codex

## Outcomes & Retrospective

Implemented the split for all five requested route roots and removed their entries from `dev/namespace_size_exceptions.edn`. The roots now act as stable entry points or compatibility facades, while focused namespaces own row rendering, controls, modal bodies, metrics, orderbook model logic, depth/trade panels, and trade-route shell layout.

Validation completed so far:

    npx shadow-cljs --force-spawn compile test
    npm run lint:namespace-sizes
    npm test -- --test=hyperopen.views.leaderboard-view-test --test=hyperopen.views.api-wallets-view-test --test=hyperopen.views.account-equity-view-test --test=hyperopen.views.account-equity-view-token-price-test --test=hyperopen.views.l2-orderbook-view-test --test=hyperopen.views.trade-view.layout-test --test=hyperopen.views.trade-view.loading-shell-test --test=hyperopen.views.trade-view.mobile-surface-test --test=hyperopen.views.trade-view.render-cache-test --test=hyperopen.views.trade-view.startup-defer-test
    npm run lint:namespace-boundaries
    npm test -- --test=hyperopen.leaderboard.effects-test
    npx playwright test tools/playwright/test/routes.smoke.spec.mjs --grep "leaderboard cache serves"
    npm run test:playwright:smoke
    npm run check
    npm test
    npm run test:websocket

Browser-QA accounting: `npm run test:playwright:smoke` passed 25 tests covering route rendering for `/trade` and `/leaderboard` at desktop and mobile widths, trade header/navigation/accessibility/context-menu smoke coverage, and leaderboard preference/cache persistence. No Browser MCP sessions were created.

## Context and Orientation

The working directory is `/Users/barry/.codex/worktrees/d1f4/hyperopen`. The app is ClojureScript rendered with Hiccup-style vectors. A "route view" in this plan means a namespace under `src/hyperopen/views/**` that renders the top-level UI for a route or route surface. The namespace-size guard is implemented by `dev/check_namespace_sizes.clj` and configured through `dev/namespace_size_exceptions.edn`. Production source namespaces above the guard limit are allowed only if they appear in that EDN file with an owner, reason, max line count, and retirement date.

The five root files currently have these line counts: `chart_context_menu_overlay.cljs` is larger, but among route roots the big bucket is `leaderboard_view.cljs` at 706 lines, `l2_orderbook_view.cljs` at 676 lines, `trade_view.cljs` at 676 lines, `api_wallets_view.cljs` at 663 lines, and `account_equity_view.cljs` at 618 lines. This plan targets those five and does not touch unrelated route exceptions such as vaults, chart D3 runtime, or API surfaces outside this bucket.

`leaderboard_view.cljs` currently owns row rendering, trader links, sort controls, page-size dropdowns, loading/empty/error states, and the route shell. It already gets normalized data from `hyperopen.views.leaderboard.vm`, so the split should keep the VM dependency in the root or a dedicated component namespace and move UI-only pieces under `src/hyperopen/views/leaderboard/`.

`api_wallets_view.cljs` currently owns form controls, API-wallet row rendering, authorize/remove modal bodies, modal footer behavior, and the route shell. It already gets normalized data from `hyperopen.views.api-wallets.vm`, so the split should move form/table/modal UI under `src/hyperopen/views/api_wallets/`.

`account_equity_view.cljs` currently owns display helpers, funding action buttons, unified/classic account-equity metrics, a metrics cache, and panel rendering. It is consumed by trade account surfaces and portfolio VMs. Public helpers must remain available from `hyperopen.views.account-equity-view`.

`l2_orderbook_view.cljs` currently owns public orderbook math helpers, trade normalization, dropdowns, row rendering, mobile split rendering, snapshot fallback, and the root panel. It is used by trade route render-cache tests, so its public helper names must remain stable.

`trade_view.cljs` currently owns route state selection, memoization wrappers, render freezing during selector scroll, account surface lazy export resolution, mobile surface tabs, loading shell, and desktop panel layout. It already has `src/hyperopen/views/trade_view/layout_state.cljs`; this plan should add sibling namespaces under `src/hyperopen/views/trade_view/` for responsibilities that are not the top-level route root.

## Plan of Work

First, remove only the five root entries from `dev/namespace_size_exceptions.edn` and run `npm run lint:namespace-sizes`. The command should fail with missing-size-exception messages for the five route roots. Do not remove test exceptions in this pass; test namespace cleanup is a separate backlog item.

Second, split the easiest route roots first: leaderboard and API wallets. Move their private component functions into focused namespaces and reduce each root to VM invocation plus route shell composition. Run `npm test -- --test=hyperopen.views.leaderboard-view-test --test=hyperopen.views.api-wallets-view-test` after the split.

Third, split account equity by extracting metric derivation and funding action UI into focused namespaces. Keep compatibility wrappers or `def` aliases in `account_equity_view.cljs` for public functions used by tests and other callers. Run `npm test -- --test=hyperopen.views.account-equity-view-test --test=hyperopen.views.account-equity-view-token-price-test` and relevant portfolio VM tests that call `account-equity-metrics`.

Fourth, split L2 orderbook by moving data/model helpers into one namespace and render components into another. Keep public helper wrappers in `l2_orderbook_view.cljs` for the tests that document the API. Run `npm test -- --test=hyperopen.views.l2-orderbook-view-test` and the trade render-cache tests that stub `l2-orderbook-view/l2-orderbook-view`.

Fifth, split trade view by moving state selection/render-cache and large loading shell/panel helpers into sibling namespaces. Keep `trade-view` in the root file. Run all trade view focused tests under `test/hyperopen/views/trade_view/`.

Finally, run `npm run lint:namespace-sizes`, `npm run lint:namespace-boundaries`, `npm run check`, `npm test`, and `npm run test:websocket`. Because the touched files are UI-facing, run the smallest deterministic Playwright coverage for impacted routes if time permits and record browser-QA pass accounting across visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf. If browser QA cannot run, record it as blocked with the exact blocker and do not claim full UI completion.

## Concrete Steps

From `/Users/barry/.codex/worktrees/d1f4/hyperopen`, run:

    npm run lint:namespace-sizes

After removing the five root exceptions, the expected RED result is a non-zero exit with missing-size-exception messages for the five root paths. After the refactor, the same command must exit zero.

Focused validation commands:

    npm test -- --test=hyperopen.views.leaderboard-view-test --test=hyperopen.views.api-wallets-view-test
    npm test -- --test=hyperopen.views.account-equity-view-test --test=hyperopen.views.account-equity-view-token-price-test
    npm test -- --test=hyperopen.views.l2-orderbook-view-test
    npm test -- --test=hyperopen.views.trade-view.layout-test --test=hyperopen.views.trade-view.loading-shell-test --test=hyperopen.views.trade-view.mobile-surface-test --test=hyperopen.views.trade-view.render-cache-test --test=hyperopen.views.trade-view.startup-defer-test

Required final gates:

    npm run lint:namespace-sizes
    npm run lint:namespace-boundaries
    npm run check
    npm test
    npm run test:websocket

## Validation and Acceptance

Acceptance requires all five root route view namespaces to fall below the production namespace-size threshold without entries in `dev/namespace_size_exceptions.edn`. Existing public route entry points must continue to work: `leaderboard-view/route-view`, `api-wallets-view/route-view`, `trade-view/trade-view`, `l2-orderbook-view/l2-orderbook-view`, and `account-equity-view/account-equity-view`.

Focused tests must pass for the five affected areas. The namespace-size lint must pass without re-adding exceptions for the five root files. Required repo gates must pass or any blocker must be shown to be unrelated and recorded here. UI browser QA must be explicitly accounted for because this plan touches `/hyperopen/src/hyperopen/views/**`.

## Idempotence and Recovery

The refactor is source-only and behavior-preserving. The safest recovery from a broken split is to keep the new focused namespace but temporarily restore the root compatibility wrapper for the failing public function, then rerun the focused test that failed. Do not use destructive git commands. If a split creates a new namespace above the size limit, split that new namespace by responsibility instead of adding a new production exception.

## Artifacts and Notes

The pre-refactor production line counts recorded by `bb` were:

    706  src/hyperopen/views/leaderboard_view.cljs
    676  src/hyperopen/views/l2_orderbook_view.cljs
    676  src/hyperopen/views/trade_view.cljs
    663  src/hyperopen/views/api_wallets_view.cljs
    618  src/hyperopen/views/account_equity_view.cljs

## Interfaces and Dependencies

Root public APIs that must remain callable:

    hyperopen.views.leaderboard-view/leaderboard-view
    hyperopen.views.leaderboard-view/route-view
    hyperopen.views.api-wallets-view/api-wallets-view
    hyperopen.views.api-wallets-view/route-view
    hyperopen.views.account-equity-view/parse-num
    hyperopen.views.account-equity-view/safe-div
    hyperopen.views.account-equity-view/display-currency
    hyperopen.views.account-equity-view/display-percent
    hyperopen.views.account-equity-view/display-leverage
    hyperopen.views.account-equity-view/pnl-display
    hyperopen.views.account-equity-view/tooltip
    hyperopen.views.account-equity-view/label-with-tooltip
    hyperopen.views.account-equity-view/default-metric-value-class
    hyperopen.views.account-equity-view/metric-row
    hyperopen.views.account-equity-view/funding-actions-view
    hyperopen.views.account-equity-view/account-equity-metrics
    hyperopen.views.account-equity-view/reset-account-equity-metrics-cache!
    hyperopen.views.account-equity-view/account-equity-view
    hyperopen.views.l2-orderbook-view/normalize-orderbook-tab
    hyperopen.views.l2-orderbook-view/resolve-base-symbol
    hyperopen.views.l2-orderbook-view/resolve-quote-symbol
    hyperopen.views.l2-orderbook-view/normalize-trade
    hyperopen.views.l2-orderbook-view/recent-trades-for-coin
    hyperopen.views.l2-orderbook-view/order-row
    hyperopen.views.l2-orderbook-view/trades-panel
    hyperopen.views.l2-orderbook-view/l2-orderbook-panel
    hyperopen.views.l2-orderbook-view/l2-orderbook-view
    hyperopen.views.trade-view/trade-view

## Revision Notes

- 2026-05-02 / Codex: Created the active ExecPlan for `hyperopen-6sf8`, scoped to the five large route view namespace-size exceptions requested by the user. The plan uses namespace-size lint as the RED architectural gate and preserves public route/view APIs.
- 2026-05-02 / Codex: Confirmed the RED namespace-size lint failure after removing only the five route-root exception entries.
- 2026-05-02 / Codex: Completed all five source splits, refreshed local dependencies with `npm ci` because `node_modules` was missing a locked runtime dependency, and confirmed focused tests plus namespace lint/boundary checks pass.
- 2026-05-02 / Codex: Fixed the leaderboard cache smoke race exposed during browser QA, completed deterministic Playwright smoke coverage, and recorded full gate commands for final verification.
