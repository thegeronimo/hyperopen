# Add vaults to the Portfolio Optimizer universe

This ExecPlan is a completed execution record. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` capture the implementation story that closed the work.

This document follows `/hyperopen/.agents/PLANS.md`. It is self-contained so a future contributor can continue from this file without relying on conversation history.

Tracked issue: `hyperopen-rfdz` ("Add vaults to portfolio optimizer universe").

## Purpose / Big Picture

The Portfolio Optimizer custom universe search currently lets users add spot and perp market instruments, but it does not expose Hyperliquid vaults. Users can already search vaults as return benchmarks in the portfolio and vault detail pages. This work extends the optimizer universe builder with the same `vault:<address>` identity pattern so a user can search a vault by name, add it to a draft optimizer universe, load its return history, and run the optimizer with that vault alongside market instruments.

After this change, navigate to `/portfolio/optimize/new`, type a known vault name or `vault` into the Universe search field, and the result list should include vault rows marked as `vault`. Clicking `+ add` should add a selected row with a `vault:<address>` instrument id. Running the optimizer should fetch vault details for selected vaults, turn their portfolio return history into optimizer return series, and avoid trying to fetch market candles for the `vault:<address>` pseudo-coin.

## Progress

- [x] (2026-04-29 21:33Z) Created and claimed `bd` issue `hyperopen-rfdz`.
- [x] (2026-04-29 21:34Z) Mapped current optimizer universe search in `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`, candidate selection in `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs`, optimizer draft mutation actions in `src/hyperopen/portfolio/optimizer/actions.cljs`, and optimizer history loading in `src/hyperopen/portfolio/optimizer/application/history_loader.cljs` plus `src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs`.
- [x] (2026-04-29 21:34Z) Mapped existing benchmark vault search behavior in `src/hyperopen/views/portfolio/vm/benchmarks.cljs` and benchmark detail-fetch actions in `src/hyperopen/portfolio/actions.cljs` and `src/hyperopen/vaults/application/detail_commands.cljs`.
- [x] (2026-04-29 21:39Z) Added RED tests proving optimizer universe candidates include vault rows, action handling adds vault instruments, route loading fetches vault metadata, history planning avoids market candles for vaults, history client fetches vault details, and the v4 UI renders vault candidates.
- [x] (2026-04-29 21:40Z) Ran the focused RED command. The test build completed with 0 warnings and the selected tests ran with 24 expected failures and 0 errors. Failures show the current code returns no vault candidates, rejects `vault:<address>` adds, emits no optimizer-route vault metadata fetches, and still requests candles for the `vault:<address>` pseudo-coin.
- [x] (2026-04-29 22:00Z) Implemented vault universe candidates and UI labels in the v4 and legacy optimizer universe panels, including `vault:<address>` candidate keys, vault chips, vault display labels, and copy that no longer says ticker-only search.
- [x] (2026-04-29 22:00Z) Implemented vault draft instrument creation and optimizer route metadata fetches. Optimizer routes now request vault index and summaries when vault rows are missing, and `vault:<address>` add actions write `:market-type :vault` instruments with normalized `:vault-address`.
- [x] (2026-04-29 22:01Z) Implemented vault detail fetching in optimizer history bundles and vault return-series alignment. Vault instruments produce `:vault-detail-requests`, skip candle/funding requests, and align vault portfolio return history into the optimizer return-series contract.
- [x] (2026-04-29 22:02Z) Focused optimizer/vault test command passed: 52 tests, 248 assertions, 0 failures, 0 errors.
- [x] (2026-04-29 22:07Z) Required repository gates passed: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-04-29 22:09Z) Added and ran deterministic Playwright coverage for manual universe vault add/remove. `PLAYWRIGHT_BASE_URL=http://127.0.0.1:4174 PLAYWRIGHT_WEB_SERVER_COMMAND="PLAYWRIGHT_WEB_PORT=4174 node tools/playwright/static_server.mjs" npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "portfolio optimizer manual universe"` passed 3 tests.
- [x] (2026-04-29 22:13Z) Accounted for governed browser QA. `design-review` target `portfolio-optimizer-route` across `review-375`, `review-768`, `review-1280`, and `review-1440` returned `FAIL`; see Surprises & Discoveries for details. Browser-inspection cleanup succeeded with `npm run browser:cleanup`.
- [x] (2026-04-30 14:57Z) Planned a focused vault-label migration follow-up for optimizer setup: find remaining setup surfaces that render `vault:<address>` strings, add RED view tests, migrate display copy to existing human-readable instrument labels, and rerun focused optimizer tests before broader gates.
- [x] (2026-04-30 15:09Z) Added RED tests for setup summary and Black-Litterman prior/select labels, then broadened coverage to result diagnostics, rebalance preview/tab, tracking, and inputs audit surfaces so vault names are visible while `vault:<address>` strings are not.
- [x] (2026-04-30 15:16Z) Implemented the display-layer migration by routing vault labels through existing optimizer display names and result `:labels-by-instrument`, while preserving `vault:<address>` values in form values, action payloads, and data-role hooks.
- [x] (2026-04-30 15:19Z) Focused optimizer view tests passed: `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.views.portfolio.optimize.setup-v4-layout-test --test=hyperopen.views.portfolio.optimize.black-litterman-views-panel-test --test=hyperopen.views.portfolio.optimize.results-panel-test --test=hyperopen.views.portfolio.optimize.tracking-panel-test --test=hyperopen.views.portfolio.optimize.inputs-tab-test` ran 21 tests and 275 assertions with 0 failures.
- [x] (2026-04-30 15:27Z) Smallest relevant Playwright optimizer regression passed for the changed setup route: `PLAYWRIGHT_BASE_URL=http://127.0.0.1:4174 PLAYWRIGHT_WEB_SERVER_COMMAND="PLAYWRIGHT_WEB_PORT=4174 node tools/playwright/static_server.mjs" npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "portfolio optimizer manual universe builder adds and removes vaults"` passed 1 test.
- [x] (2026-04-30 15:18Z) Accounted for governed browser QA. `design-review-2026-04-30T15-17-43-737Z-46b84c0a` returned `FAIL`: `review-375`, `review-1280`, and `review-1440` captured visual, native-control, styling-consistency, interaction, and layout-regression evidence but failed jank/perf long-task thresholds; `review-768` failed capture waiting for `Runtime.evaluate`. Browser-inspection cleanup succeeded with `npm run browser:cleanup`.
- [x] (2026-04-30 15:40Z) Added RED coverage for the remaining execution-modal vault label seam; it failed on visible `vault:<address>` text in both plan rows and latest-attempt recovery rows, then passed after routing those rows through the result label map.
- [x] (2026-04-30 15:44Z) Required repository gates passed after the final vault-label migration: `npm run check`, `npm test` ran 3645 tests and 20081 assertions with 0 failures, and `npm run test:websocket` ran 461 tests and 2798 assertions with 0 failures.
- [x] (2026-04-30 15:45Z) Existing deterministic Playwright execution-modal regression passed for the additional row path: `PLAYWRIGHT_BASE_URL=http://127.0.0.1:4174 PLAYWRIGHT_WEB_SERVER_COMMAND="PLAYWRIGHT_WEB_PORT=4174 node tools/playwright/static_server.mjs" npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "portfolio optimizer execution modal"` passed 1 test.
- [x] (2026-04-30 16:06Z) Followed up on governed browser-inspection failures. The repeated long tasks were a real optimizer setup-route performance bug: the empty universe search path was ranking and sorting 9,448 vault rows on every render/tick even though search results were hidden. After guarding candidate search behind a non-empty query, `npm run qa:design-ui -- --targets portfolio-optimizer-route --manage-local-app` passed across `review-375`, `review-768`, `review-1280`, and `review-1440` in run `design-review-2026-04-30T15-53-14-090Z-64603eb8`.
- [x] (2026-04-30 16:10Z) Required repository gates passed after the governed browser-QA fix: `npm run check`, `npm test` ran 3646 tests and 20083 assertions with 0 failures, and `npm run test:websocket` ran 461 tests and 2798 assertions with 0 failures.
- [x] (2026-04-30 16:12Z) Moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/` after acceptance checks and governed browser QA passed.

## Surprises & Discoveries

- Observation: The screenshot corresponds to the v4 optimizer setup universe surface, not only the older `universe_panel.cljs` surface.
  Evidence: `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` renders the `01 Universe` header, `From holdings / Custom / Index` tabs, search placeholder, and `cap: 25 assets` copy visible in the screenshot.

- Observation: Benchmark vault search already has the user-facing identity convention this work should reuse.
  Evidence: `src/hyperopen/views/portfolio/vm/benchmarks.cljs` builds vault benchmark values as `vault:<address>`, filters child vault rows out, ranks vault candidates by TVL, and labels rows with `(VAULT)`.

- Observation: Adding vault rows to the optimizer UI alone is not enough for a working optimizer.
  Evidence: `src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs` currently only requests candle snapshots and funding history. `src/hyperopen/portfolio/optimizer/application/history_loader.cljs` aligns optimizer returns from candle history keyed by `:coin`, so a naive `vault:<address>` coin would produce an invalid market candle request and then be excluded for missing candle history.

- Observation: The RED run confirmed the missing support at each required layer.
  Evidence: `node out/test.js --test=hyperopen.portfolio.optimizer.application.universe-candidates-test --test=hyperopen.portfolio.optimizer.actions-test --test=hyperopen.portfolio.optimizer.application.history-loader-test --test=hyperopen.portfolio.optimizer.infrastructure.history-client-test --test=hyperopen.views.portfolio.optimize.universe-panel-test --test=hyperopen.views.portfolio.optimize.setup-v4-layout-test` ran 52 tests with 24 failures and 0 errors after compiling 1,445 files with 0 warnings.

- Observation: A pre-existing Shadow dev server on port 8080 served an older optimizer bundle during the first Playwright attempt.
  Evidence: The first focused Playwright run with `PLAYWRIGHT_REUSE_EXISTING_SERVER=true` failed because the browser saw placeholder text `"Search ticker or name (e.g. TIA, AVAX, Solana...)"` instead of the updated vault copy. Re-running against a fresh static server on port 4174 passed.

- Observation: Governed browser-inspection design review did not pass for the optimizer setup route.
  Evidence: Run `design-review-2026-04-29T22-12-06-174Z-060642e3` captured `review-375` but recorded a 297ms long task and then failed to capture `review-768`, `review-1280`, and `review-1440` with `Timed out waiting for Runtime.evaluate`. Artifacts are under `tmp/browser-inspection/design-review-2026-04-29T22-12-06-174Z-060642e3/`.

- Observation: The vault-name migration was incomplete in setup summary and Black-Litterman surfaces.
  Evidence: `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs` builds the summary universe copy from `(keep :coin)`, so vault instruments render as `vault:<address>`. `src/hyperopen/views/portfolio/optimize/black_litterman_views_panel.cljs` renders select option text from `:symbol` or `:instrument-id` and prior rows from `:instrument-id`, so vaults without a symbol still show their `vault:<address>` key even when `:name` is present.

- Observation: Result-phase optimizer views also had visible raw vault ids after a scenario is solved or reviewed.
  Evidence: The RED tests in `results_panel_test.cljs`, `tracking_panel_test.cljs`, and `inputs_tab_test.cljs` caught raw `vault:<address>` strings in diagnostics, target leg labels, rebalance preview rows, the dedicated rebalance tab, tracking drift rows, and scenario input audit rows.

- Observation: Execution review still had visible raw vault ids in modal rows after the first result-phase migration.
  Evidence: The RED test in `execution_modal_test.cljs` caught `vault:<address>` text in the execution preview plan rows and failed latest-attempt recovery rows. Both rows now use the solved result `:labels-by-instrument` map for vault ids.

- Observation: The follow-up governed browser-inspection run still did not pass, but its failures were not vault-label regressions.
  Evidence: Run `design-review-2026-04-30T15-17-43-737Z-46b84c0a` captured `review-375`, `review-1280`, and `review-1440` visual/native-control/style/interaction/layout evidence, then failed only jank/perf with 248ms, 250ms, and 260ms long tasks. `review-768` failed capture with `Timed out waiting for Runtime.evaluate`. Artifacts are under `tmp/browser-inspection/design-review-2026-04-30T15-17-43-737Z-46b84c0a/`.

- Observation: The governed browser-inspection failures were caused by real optimizer setup-route jank, not by an outdated QA expectation.
  Evidence: An idle PerformanceObserver probe on `/portfolio/optimize/new` showed repeated roughly 300ms long tasks, while `/portfolio` and `/trade` had none. A CPU profile on the optimizer setup route was dominated by `hyperopen$portfolio$optimizer$application$universe_candidates$vault_row_rank` because `setup_v4_universe.cljs` called `candidate-markets` for an empty search query, sorting 9,448 vault rows even though no result list was visible.

- Observation: Removing empty-search candidate work cleared both the jank failure and the intermittent capture timeout.
  Evidence: After guarding `candidate-markets` behind a non-empty normalized query, the idle long-task probe reported zero long tasks on `/portfolio/optimize/new`, and governed browser-inspection run `design-review-2026-04-30T15-53-14-090Z-64603eb8` passed all six review passes across `review-375`, `review-768`, `review-1280`, and `review-1440`.

## Decision Log

- Decision: Represent optimizer vault instruments with `:instrument-id` and `:coin` set to `vault:<normalized-address>`, `:market-type :vault`, and `:vault-address <normalized-address>`.
  Rationale: This matches the benchmark selector's established persisted value format while making vault instruments distinguishable from market symbols during history loading, UI tags, and constraint cleanup.
  Date/Author: 2026-04-29 / Codex

- Decision: Load vault rows for optimizer routes instead of requiring the user to visit the Vaults page first.
  Rationale: A search feature should not depend on unrelated navigation state. The portfolio benchmark selector fetches vault index and summaries when the benchmark suggestions open; for the optimizer, route-level metadata loading is the lowest-risk path because the v4 search results only render while typing and currently have no open/closed suggestions state.
  Date/Author: 2026-04-29 / Codex

- Decision: Treat vault optimizer history as return history derived from vault details, not as market candles.
  Rationale: Vaults are not tradeable ticker markets. The vault detail and benchmark code already receives portfolio summaries from vault details. Converting those summaries into price-like return series lets the existing optimizer engine consume the same return-series contract without making invalid candle API requests.
  Date/Author: 2026-04-29 / Codex

- Decision: Keep vault identity values in form values, action payloads, and data-role hooks, but use human-readable labels for visible setup copy.
  Rationale: The optimizer still needs stable `vault:<address>` ids to update draft state and Black-Litterman views. The bug is presentation-only: visible strings should use the existing display-name helpers while hidden values and actions remain stable.
  Date/Author: 2026-04-30 / Codex

- Decision: Apply result `:labels-by-instrument` only to vault ids on legacy raw-id rows, rather than changing non-vault instrument copy.
  Rationale: Existing optimizer tests and some review surfaces intentionally expose non-vault ids such as `perp:BTC`. Narrowing label substitution to `vault:` ids fixes the reported readability bug without changing established perp/spot labels.
  Date/Author: 2026-04-30 / Codex

- Decision: Do not build optimizer universe search candidates while the search query is empty.
  Rationale: The result list is hidden until the user types. Building candidates for an empty query was pure wasted work on the setup route and ranked thousands of vault rows on repeated renders, causing the governed jank failures and CDP capture timeouts.
  Date/Author: 2026-04-30 / Codex

## Outcomes & Retrospective

Implemented vaults as first-class optimizer universe instruments while preserving the existing spot/perp contracts. The main behavioral changes are:

- Universe search combines asset-selector markets with eligible vault rows from `[:vaults :merged-index-rows]`, excludes child/already-selected vaults, and emits `vault:<normalized-address>` keys.
- Optimizer route loading fetches vault metadata when needed so users do not have to visit the Vaults page before searching vaults in the optimizer.
- Optimizer history loading fetches vault details and converts vault portfolio return history into price-like rows for the existing return-series pipeline, without issuing candle or funding requests for vault pseudo-symbols.
- Playwright now has deterministic regression coverage for adding/removing vaults from the manual universe builder, and the Playwright config can opt into a supplied base URL, server command, or existing server without changing the default local behavior.

Remaining gap: the governed browser-inspection design review reported `FAIL` for the optimizer setup route due a mobile long task and CDP capture timeouts at the wider review widths. Focused deterministic Playwright coverage passed, and browser-inspection sessions were cleaned up.

The 2026-04-30 vault-label follow-up migrated remaining visible optimizer vault ids to human-readable names in setup summary, Black-Litterman prior/select controls, scenario input audit rows, target diagnostics, rebalance previews/tabs, tracking drift displays, and execution modal review rows. The implementation keeps `vault:<address>` as the stable identity in form values, payloads, and test hooks while using existing optimizer label maps for user-facing text. Focused view tests, the targeted Playwright regressions, and all required repository gates passed.

The governed browser-QA follow-up found and fixed a real setup-route performance regression rather than an outdated QA test. The optimizer universe panel now skips candidate generation while the search query is empty, eliminating repeated ranking/sorting of thousands of vault rows when the search results are hidden. Governed browser-inspection design review now passes for the optimizer route across all required review widths.

## Context and Orientation

The working directory is `/Users/barry/.codex/worktrees/5c7c/hyperopen`.

The Portfolio Optimizer draft universe is a vector at `[:portfolio :optimizer :draft :universe]`. Each existing instrument has an `:instrument-id`, a `:market-type` such as `:perp` or `:spot`, and a `:coin` used by the history loader. The v4 setup UI lives in `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`. It calls `hyperopen.portfolio.optimizer.application.universe-candidates/candidate-markets` to build the search results shown under the input. Adding a result dispatches `[:actions/add-portfolio-optimizer-universe-instrument <key>]`, which is implemented by `src/hyperopen/portfolio/optimizer/actions.cljs`.

Vault list rows are kept under `[:vaults :merged-index-rows]`. A row normally includes `:vault-address`, `:name`, `:tvl`, and optional `:relationship`. Child vault rows should not be selectable as top-level vault candidates, matching the benchmark selector. Vault details are cached under `[:vaults :details-by-address <address>]` for normal vault detail pages and under `[:vaults :benchmark-details-by-address <address>]` for benchmark comparisons. Optimizer history loading should fetch vault details directly into the optimizer history bundle so an optimizer run does not depend on those UI caches being present.

The optimizer history pipeline starts in `src/hyperopen/runtime/effect_adapters/portfolio_optimizer.cljs`, calls `src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs`, and builds a request plan through `src/hyperopen/portfolio/optimizer/application/history_loader.cljs`. The history loader should continue to fetch market candles for spot and perp instruments and market funding history only for perps. Vault instruments should instead create vault detail requests and should be treated as non-funding instruments.

## Plan of Work

First, add focused tests before production changes. In `test/hyperopen/portfolio/optimizer/application/universe_candidates_test.cljs`, add a test that `candidate-markets` returns vault rows from `:vaults :merged-index-rows` when the query matches `vault` or a vault name, excludes selected vault ids, excludes child rows, and preserves `vault:<address>` keys. In `test/hyperopen/portfolio/optimizer/actions_test.cljs`, add a test that adding a `vault:<address>` candidate writes a vault instrument into the draft universe and clears the search state. In `test/hyperopen/portfolio/optimizer/application/history_loader_test.cljs` and `test/hyperopen/portfolio/optimizer/infrastructure/history_client_test.cljs`, add tests proving vault instruments produce vault detail requests, do not produce candle or funding requests, and align vault portfolio history into eligible optimizer return history.

Second, update `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs`. Add helpers to normalize vault addresses, build `vault:<address>` keys, filter eligible vault rows, and convert rows to candidate maps with `:market-type :vault`, `:coin`, `:vault-address`, `:name`, `:symbol`, and `:tvl`. Search should match the vault name, address, `vault:<address>`, and the literal word `vault`. Candidate results should remain capped by the existing default limit after combining asset-market and vault candidates.

Third, update the UI display helpers. In `src/hyperopen/views/portfolio/optimize/instrument_display.cljs`, make vault instruments display their vault name or symbol as the primary label and the normalized vault address as the secondary label. In `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` and the older `src/hyperopen/views/portfolio/optimize/universe_panel.cljs`, update the copy so the search invites tickers or vaults and the empty text no longer says only "markets." The existing `market-type-tags` helper should render a `vault` chip.

Fourth, update optimizer actions. In `src/hyperopen/portfolio/optimizer/actions.cljs`, add vault row resolution for `vault:<address>` keys and convert resolved rows into draft universe instruments. Extend optimizer route loading so `/portfolio/optimize`, `/portfolio/optimize/new`, and `/portfolio/optimize/<scenario>` fetch vault index and summaries when vault rows are not already loaded, allowing search to work without prior Vaults navigation.

Fifth, update optimizer history loading. In `src/hyperopen/portfolio/optimizer/application/history_loader.cljs`, split market history request planning from vault history request planning. Add `:vault-detail-requests` to the request plan, keyed by normalized vault address. Add vault-detail history alignment that converts a vault detail portfolio summary into price-like rows from cumulative return history. In `src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs`, request those vault details with a new `:request-vault-details!` dependency and include `:vault-details-by-address` in the returned bundle. In `src/hyperopen/runtime/effect_adapters/portfolio_optimizer.cljs`, pass `api/request-vault-details!` into the history client.

Sixth, add deterministic browser coverage if the existing Playwright optimizer manual-universe flow can be extended cheaply. The stable path is to navigate to `/portfolio/optimize/new`, inject vault rows into app state through the existing debug bridge if needed, search for a vault, click the add control, and assert the selected universe row renders. If the debug bridge cannot reliably seed vault rows without broad fixture work, record the browser QA as blocked for deterministic coverage and rely on ClojureScript view/action tests plus required design-review accounting.

## Concrete Steps

Run commands from `/Users/barry/.codex/worktrees/5c7c/hyperopen`.

After adding RED tests, run:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.universe-candidates-test --test=hyperopen.portfolio.optimizer.actions-test --test=hyperopen.portfolio.optimizer.application.history-loader-test --test=hyperopen.portfolio.optimizer.infrastructure.history-client-test --test=hyperopen.views.portfolio.optimize.universe-panel-test --test=hyperopen.views.portfolio.optimize.setup-v4-layout-test

Expected RED result before implementation: at least the new vault-specific assertions fail because vault candidates, vault draft instruments, and vault detail history requests do not exist yet.

After implementation, run the same focused command and expect all selected tests to pass with 0 failures and 0 errors. Then run the smallest relevant Playwright command covering optimizer manual universe behavior, likely:

    npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "portfolio optimizer manual universe"

If code changed, run the repository gates required by `AGENTS.md`:

    npm run check
    npm test
    npm run test:websocket

For UI-facing signoff, also account for browser-QA passes from `/hyperopen/docs/FRONTEND.md`: visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf at widths `375`, `768`, `1280`, and `1440`. If a full governed design review cannot be run in this session, report it as `BLOCKED` with the reason and the deterministic coverage that did run.

## Validation and Acceptance

Acceptance requires the focused tests to prove four behaviors. First, a matching query returns vault candidates from `:vaults :merged-index-rows`, with child vaults and already-selected vaults excluded. Second, clicking or keyboard-selecting a vault candidate adds a draft universe instrument with `:instrument-id "vault:<address>"`, `:market-type :vault`, and `:vault-address <address>`. Third, optimizer route loading fetches vault list metadata when needed. Fourth, optimizer history loading fetches vault details and aligns vault return history without making candle or funding requests for the `vault:<address>` pseudo-coin.

Acceptance also requires a user-visible UI check: on `/portfolio/optimize/new`, search copy should mention vaults, a vault result should render with a `vault` chip, and the selected universe row should remain removable through the existing remove action.

## Idempotence and Recovery

All changes are additive and local to optimizer universe search, optimizer history loading, and tests. The `bd` issue can remain in progress while implementation proceeds and should be closed only after validation. If vault history alignment proves too risky, keep the UI candidate changes behind tests but block optimizer runs with an explicit warning rather than silently issuing invalid candle requests. If route-level vault metadata fetches cause duplicate network work, move that metadata fetch to an explicit search-focus action following the portfolio benchmark selector pattern and add an effect-order policy for the new interaction action.

## Artifacts and Notes

Important existing references:

    src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs
    src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs
    src/hyperopen/portfolio/optimizer/actions.cljs
    src/hyperopen/portfolio/optimizer/application/history_loader.cljs
    src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs
    src/hyperopen/views/portfolio/vm/benchmarks.cljs

Plan revision note: 2026-04-29 21:34Z - Created the active ExecPlan after mapping optimizer universe search, existing benchmark vault search, and optimizer history loading boundaries.

## Interfaces and Dependencies

At completion, `hyperopen.portfolio.optimizer.application.universe-candidates/candidate-markets` should still accept `(state universe query)` and `(state universe query opts)` and return candidate maps with `:key`. Existing callers should not need a new function name.

At completion, `hyperopen.portfolio.optimizer.application.history-loader/build-history-request-plan` should return the existing `:candle-requests`, `:funding-requests`, and `:warnings` keys plus a new `:vault-detail-requests` vector. `hyperopen.portfolio.optimizer.infrastructure.history-client/request-history-bundle!` should accept a dependency map containing `:request-vault-details!` and should return a bundle with `:vault-details-by-address` in addition to the existing history maps.
