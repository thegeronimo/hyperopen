# Split API Test Debt

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. This plan follows `.agents/PLANS.md` and is tracked by `bd` issue `hyperopen-0zfz`.

## Purpose / Big Picture

The API source split has already landed, but three API test namespaces still sit above the repository's normal 500-line namespace limit and remain on temporary size exceptions. After this work, API endpoint, default facade, info-client, and fetch-compat tests will be organized into smaller behavior-focused namespaces that the generated test runner discovers automatically. The result is visible by running the namespace-size lint and the API test suite: the three old exceptions disappear, every moved assertion still runs, and no production API behavior changes.

## Progress

- [x] (2026-05-01 19:49Z) Created `bd` issue `hyperopen-0zfz` for the API test debt split.
- [x] (2026-05-01 19:54Z) Inspected the three oversized namespaces and confirmed current line counts: `test/hyperopen/api/endpoints/account_test.cljs` is 1013 lines, `test/hyperopen/api_test.cljs` is 753 lines, and `test/hyperopen/api/fetch_compat_test.cljs` is 622 lines.
- [x] (2026-05-01 19:57Z) Collected read-only subagent recommendations for all three split surfaces.
- [x] (2026-05-01 19:58Z) Split `test/hyperopen/api/endpoints/account_test.cljs` into six focused endpoint namespaces and deleted the old oversized namespace.
- [x] (2026-05-01 19:58Z) Split `test/hyperopen/api_test.cljs` into focused facade, info-client, and funding-history namespaces and deleted the old oversized namespace.
- [x] (2026-05-01 19:59Z) Split `test/hyperopen/api/fetch_compat_test.cljs` into five focused fetch-compat namespaces and deleted the old oversized namespace.
- [x] (2026-05-01 19:59Z) Removed stale namespace-size exceptions for the three old API test namespaces.
- [x] (2026-05-01 20:01Z) Regenerated the generated test runner and ran focused validation plus required repository gates.
- [x] (2026-05-01 20:02Z) Moved this ExecPlan to `docs/exec-plans/completed/` after acceptance was recorded.

## Surprises & Discoveries

- Observation: `test/hyperopen/api_test.cljs` duplicates some domain funding-history coverage that already lives in `test/hyperopen/domain/funding_history_test.cljs`.
  Evidence: the existing domain file already tests `normalize-info-funding-row` and merge/filter semantics, but the catchall API file also has a three-arity `normalize-funding-history-filters` coverage case that should be preserved.

- Observation: The generated test runner discovers every `*_test.cljs` under `test/hyperopen`, so deleting old oversized files is safer than leaving shell namespaces that could duplicate or obscure execution.
  Evidence: `tools/generate-test-runner.mjs` recursively scans `test/hyperopen`, converts filenames to namespaces, sorts them, and writes `test/test_runner_generated.cljs`.

- Observation: `test:websocket` has a coverage bridge that explicitly requires selected API test namespaces, so deleting the old catchall test namespaces requires updating that bridge.
  Evidence: the first `npm run test:websocket` after the split failed at compile time with `The required namespace "hyperopen.api-test" is not available, it was required by "hyperopen/websocket/api_coverage_bridge_test.cljs".` Updating `test/hyperopen/websocket/api_coverage_bridge_test.cljs` to require the new focused API test namespaces made the rerun pass.

- Observation: the initial direct `node out/test.js` validation failed before tests ran because the worktree had no `node_modules` installed.
  Evidence: `npm ls lucide --depth=0` showed the project as empty, while the runtime error was `Cannot find module 'lucide/dist/esm/icons/external-link.js'`. Running `npm ci` installed the locked dependencies, and the same `node out/test.js` command then passed.

## Decision Log

- Decision: Treat this as a behavior-preserving test reorganization with no `/src` changes.
  Rationale: The prompt says API source debt was completed separately and only API tests remain high priority. Moving tests into smaller namespaces addresses the debt without reopening API runtime design.
  Date/Author: 2026-05-01 / Codex

- Decision: Delete the three old oversized test files after moving their assertions instead of keeping compatibility shells.
  Rationale: Test namespaces are discovered by filename, not imported by production code. Keeping old files would preserve stale exception targets and risk duplicate execution.
  Date/Author: 2026-05-01 / Codex

- Decision: Extract only small info-client test helpers into `test/hyperopen/test_support/info_client.cljs`; keep account endpoint and fetch-compat stubs local or in existing support modules.
  Rationale: `fake-http-response` and stepping-clock helpers are repeated across info-client tests, while account endpoint and fetch-compat projection stubs deliberately document each behavior's arity and state mutation.
  Date/Author: 2026-05-01 / Codex

- Decision: Update the websocket API coverage bridge to require the new focused API test namespaces and `hyperopen.domain.funding-history-test` directly.
  Rationale: The websocket test build intentionally pulls selected API suites into its coverage surface. The old `hyperopen.api-test` catchall also carried two domain funding-history assertions, so requiring the moved domain namespace preserves the previous bridge coverage without reintroducing an oversized compatibility test namespace.
  Date/Author: 2026-05-01 / Codex

## Outcomes & Retrospective

Implementation is complete. The split reduced localized test complexity by replacing three oversized catchall namespaces with focused files whose largest new namespace is 286 lines. The old size exceptions were removed, the generated runner and websocket coverage bridge both discover the new namespaces, and all required validation gates passed.

## Context and Orientation

The repository enforces namespace size through `dev/check_namespace_sizes.clj`. The default threshold is 500 lines for every `.cljs` file under `src` and `test`, except paths listed in `dev/namespace_size_exceptions.edn`. The three files in scope are currently listed there with temporary maximums matching their oversized state. This plan removes those exception entries after the tests are split.

`test/hyperopen/api/endpoints/account_test.cljs` tests pure request builders and normalizers from `hyperopen.api.endpoints.account`. Its tests use `hyperopen.test-support.api-stubs` to fake `/info` POST responses and `hyperopen.test-support.async/unexpected-error` for async failure branches. The natural split is by account endpoint family: funding history, staking, identity/user abstraction, clearinghouse, portfolio, and accounting.

`test/hyperopen/api_test.cljs` is a catchall namespace. It mixes `hyperopen.api.default` facade tests that monkeypatch `hyperopen.api.default/post-info!`, direct `hyperopen.api.info-client` scheduler/stat/cache tests, and domain funding-history normalization tests. The split should move info-client behavior under `test/hyperopen/api/`, move facade account fetch behavior under `test/hyperopen/api/default_*.cljs`, and move the domain funding-history assertions into `test/hyperopen/domain/funding_history_test.cljs`.

`test/hyperopen/api/fetch_compat_test.cljs` tests `hyperopen.api.fetch-compat` wrappers using local dependency maps. These tests are order-independent because each test owns its atoms and dependency stubs. The split should group account-specific fetch-compat flows separately from market, candle, order, and asset-selector fetch-compat flows.

## Plan of Work

First, create `test/hyperopen/test_support/info_client.cljs` with reusable `fake-http-response` and `stepping-now-ms` helpers. Update `test/hyperopen/api/info_client_test.cljs` to use `fake-http-response` from that support namespace so helper ownership is consistent before new info-client test files are added.

Second, split `test/hyperopen/api/endpoints/account_test.cljs` into these files and delete the original:

- `test/hyperopen/api/endpoints/account_funding_history_test.cljs` for all `request-user-funding-history-*` tests, including the late stop-at-end-time test.
- `test/hyperopen/api/endpoints/account_staking_test.cljs` for validator summary and delegator staking tests.
- `test/hyperopen/api/endpoints/account_identity_test.cljs` for extra agents, user webdata2, user abstraction, and `normalize-user-abstraction-mode`.
- `test/hyperopen/api/endpoints/account_clearinghouse_test.cljs` for spot and perp clearinghouse state request tests.
- `test/hyperopen/api/endpoints/account_portfolio_test.cljs` for portfolio summary normalization and request tests.
- `test/hyperopen/api/endpoints/account_accounting_test.cljs` for user fees and non-funding ledger update tests.

Third, split `test/hyperopen/api_test.cljs` and delete the original. Move `ensure-perp-dexs-single-flight-test` into the existing `test/hyperopen/api/facade_runtime_test.cljs`. Create `test/hyperopen/api/info_client_scheduling_test.cljs`, `test/hyperopen/api/info_client_stats_test.cljs`, and `test/hyperopen/api/info_client_cache_test.cljs` for the direct info-client tests. Append the two funding-history assertions to `test/hyperopen/domain/funding_history_test.cljs`. Create `test/hyperopen/api/default_account_history_test.cljs`, `test/hyperopen/api/default_orders_test.cljs`, and `test/hyperopen/api/default_user_abstraction_test.cljs` for default facade account history, order/fill, and user abstraction tests. Each default facade file must keep `use-fixtures` that calls `api/reset-request-runtime!` before and after tests because those tests monkeypatch `hyperopen.api.default/post-info!`.

Fourth, split `test/hyperopen/api/fetch_compat_test.cljs` and delete the original. Create `test/hyperopen/api/fetch_compat_market_metadata_test.cljs`, `test/hyperopen/api/fetch_compat_candles_test.cljs`, `test/hyperopen/api/fetch_compat_orders_test.cljs`, `test/hyperopen/api/fetch_compat_asset_selector_test.cljs`, and `test/hyperopen/api/fetch_compat_account_test.cljs`. Each new namespace should require only `cljs.test` and `hyperopen.api.fetch-compat`, with a local private `reject-promise` helper where an error-path test needs it.

Finally, remove the three stale entries from `dev/namespace_size_exceptions.edn`, regenerate `test/test_runner_generated.cljs`, and run validations.

## Concrete Steps

Work from the repository root at `/Users/barry/.codex/worktrees/5008/hyperopen`.

1. Create or update test files exactly as described in the Plan of Work. Preserve assertion bodies while moving them. Keep namespace names aligned with file paths; for example, `test/hyperopen/api/endpoints/account_funding_history_test.cljs` must declare `hyperopen.api.endpoints.account-funding-history-test`.

2. Run the focused mechanical checks:

    npm run test:runner:generate
    npm run lint:namespace-sizes
    npx shadow-cljs --force-spawn compile test
    node out/test.js

3. Run the required repository gates for code or test changes:

    npm run check
    npm test
    npm run test:websocket

4. Record command results in this plan's `Artifacts and Notes` section. If a command fails because of an unrelated pre-existing blocker, capture the exact failing path and continue with the smallest related validation that proves the API test split.

## Validation and Acceptance

Acceptance requires that the generated test runner includes the new focused namespaces, no test assertion is intentionally dropped, and the three old oversized test files no longer exist. `npm run lint:namespace-sizes` must pass without entries for `test/hyperopen/api/endpoints/account_test.cljs`, `test/hyperopen/api_test.cljs`, or `test/hyperopen/api/fetch_compat_test.cljs`. `npm test` and `npm run test:websocket` must pass. `npm run check` must pass, or any failure must be documented as unrelated with exact output and a follow-up issue if needed.

The line-count target is stronger than merely staying below the old exception caps: every new or modified `.cljs` test namespace from this plan should be under the default 500-line threshold, and the old exception entries should be removed as stale debt.

## Idempotence and Recovery

The split is additive until the old oversized files are deleted. If a moved namespace fails to compile, compare its `ns` form with the file path and confirm all required helpers moved with the tests. If a test is accidentally duplicated, `rg -n "(deftest <test-name>" test/hyperopen` should show both locations; remove the old copy after verifying the new copy compiles. If a moved async test hangs, check that both `.then` and `.catch` branches still call `done` exactly once and that any `.finally` branch still restores monkeypatched globals.

## Artifacts and Notes

Initial baseline:

    wc -l test/hyperopen/api/endpoints/account_test.cljs test/hyperopen/api_test.cljs test/hyperopen/api/fetch_compat_test.cljs
        1013 test/hyperopen/api/endpoints/account_test.cljs
         753 test/hyperopen/api_test.cljs
         622 test/hyperopen/api/fetch_compat_test.cljs
        2388 total

Post-split namespace-size evidence:

    wc -l test/hyperopen/api/endpoints/account_*_test.cljs test/hyperopen/api/info_client*_test.cljs test/hyperopen/api/default_*_test.cljs test/hyperopen/api/fetch_compat_*_test.cljs test/hyperopen/api/facade_runtime_test.cljs test/hyperopen/domain/funding_history_test.cljs test/hyperopen/test_support/info_client.cljs | sort -n
          22 test/hyperopen/test_support/info_client.cljs
          62 test/hyperopen/api/default_account_history_test.cljs
          64 test/hyperopen/api/info_client_scheduling_test.cljs
          82 test/hyperopen/api/fetch_compat_candles_test.cljs
          85 test/hyperopen/api/fetch_compat_orders_test.cljs
          87 test/hyperopen/api/fetch_compat_asset_selector_test.cljs
          94 test/hyperopen/api/endpoints/account_accounting_test.cljs
         102 test/hyperopen/api/default_orders_test.cljs
         104 test/hyperopen/api/endpoints/account_clearinghouse_test.cljs
         129 test/hyperopen/api/default_user_abstraction_test.cljs
         132 test/hyperopen/api/facade_runtime_test.cljs
         143 test/hyperopen/api/endpoints/account_identity_test.cljs
         156 test/hyperopen/api/endpoints/account_portfolio_test.cljs
         161 test/hyperopen/api/info_client_cache_test.cljs
         176 test/hyperopen/api/info_client_stats_test.cljs
         176 test/hyperopen/domain/funding_history_test.cljs
         179 test/hyperopen/api/info_client_test.cljs
         183 test/hyperopen/api/fetch_compat_market_metadata_test.cljs
         213 test/hyperopen/api/fetch_compat_account_test.cljs
         255 test/hyperopen/api/endpoints/account_staking_test.cljs
         286 test/hyperopen/api/endpoints/account_funding_history_test.cljs

Validation evidence:

    npm run test:runner:generate
    Generated test/test_runner_generated.cljs with 603 namespaces.

    npm run lint:namespace-sizes
    Namespace size check passed.

    npx shadow-cljs --force-spawn compile test
    [:test] Build completed. (1532 files, 1531 compiled, 0 warnings, 17.83s)

    node out/test.js
    Ran 3680 tests containing 20281 assertions.
    0 failures, 0 errors.

    npm test
    Ran 3680 tests containing 20281 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    Ran 520 tests containing 3027 assertions.
    0 failures, 0 errors.

    npm run check
    Completed successfully after runner generation, tool tests, docs lint, namespace-size lint, namespace-boundary lint, release-asset tests, and shadow-cljs app/portfolio/worker/test compile stages. All shadow-cljs compile stages reported 0 warnings.

## Interfaces and Dependencies

This plan changes only test files, test support files, `dev/namespace_size_exceptions.edn`, and generated test runner output. The generated runner is `test/test_runner_generated.cljs` and must be regenerated by `npm run test:runner:generate`; it should not be edited by hand. Production API namespaces under `src/hyperopen/**` are out of scope.

Plan revision note: 2026-05-01 / Codex: Created the active ExecPlan after local inspection and read-only subagent recommendations. The plan chooses a behavior-preserving split, records `hyperopen-0zfz`, and defines validation gates before implementation begins.

Plan revision note: 2026-05-01 / Codex: Updated the plan after implementation and validation. Recorded the websocket bridge discovery, dependency-install prerequisite, final namespace sizes, and passing validation gates.

Plan revision note: 2026-05-01 / Codex: Moved the accepted ExecPlan from `docs/exec-plans/active/` to `docs/exec-plans/completed/` after all validation gates passed.
