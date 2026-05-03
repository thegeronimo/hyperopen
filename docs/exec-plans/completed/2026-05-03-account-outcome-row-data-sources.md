# Fix Account Outcome Row Data Sources

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. This document follows `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Account outcome positions must render like Hyperliquid's Outcomes tab: the Outcome column should show the human-readable market title, the Size column should show amount plus side, and Position Value, Mark Price, and PNL should be computed from the held side's current mark. The current UI can show `#0`, `0.00 USDH`, and `0.00000` even when the app has already received the user's `+0` balance and has public/live context data for `#0`. This change will make account outcome rows consume the same available data sources used by the rest of the app instead of depending only on the asset selector market map.

The live `bd` issue is `hyperopen-mh69`.

## Progress

- [x] (2026-05-03 02:43Z) Created `bd` issue `hyperopen-mh69` and marked it in progress.
- [x] (2026-05-03 02:45Z) Confirmed from the screenshot and current code that the row uses fallback side decoding but still lacks market metadata and side mark data.
- [x] (2026-05-03 02:53Z) Found that deferred full market hydration is skipped after any selector cache restore, which can preserve older caches that have no outcome markets and therefore no human-readable outcome titles.
- [x] (2026-05-03 02:59Z) Added regression tests for raw `spotAssetCtxs` outcome marks, VM outcome context wiring, and outcome-less selector cache refresh behavior.
- [x] (2026-05-03 03:05Z) Patched outcome rows to merge active, root, and raw spot context lookups; patched account VM options; patched deferred bootstrap cache completeness.
- [x] (2026-05-03 03:31Z) Ran required repository gates and browser QA accounting.

## Surprises & Discoveries

- Observation: The WebData2 handler writes normalized contexts to root `:asset-contexts`, but account outcome rows only read `[:active-assets :contexts]`.
  Evidence: `/hyperopen/src/hyperopen/websocket/webdata2.cljs` associates `:asset-contexts` after patching incoming `webData2`; `/hyperopen/src/hyperopen/views/account_info/vm.cljs` currently passes only `:active-contexts` from `[:active-assets :contexts]` into `build-outcome-rows`.
- Observation: Raw public `webData2.spotAssetCtxs` is already considered a reliable direct price source for normal spot balances but not for outcome balances.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/projections/balances.cljs` builds token prices from `(:spotAssetCtxs webdata2)` before falling back to market projection prices; `/hyperopen/src/hyperopen/views/account_info/projections/outcomes.cljs` has no equivalent input.
- Observation: A restored asset selector cache can make `/hyperopen/src/hyperopen/startup/runtime.cljs` skip deferred full market hydration. Caches created before outcome support do not include `:market-type :outcome` rows, so the app can show an account outcome balance while never fetching `outcomeMeta` in that session.
  Evidence: `skip-deferred-bootstrap?` returns true when `[:asset-selector :cache-hydrated?]` is true unless the active perp market is incomplete; it does not inspect whether the cache contains outcome markets.
- Observation: The local test runner does not support `--focus`; passing it prints `Unknown arg` and still runs the full suite.
  Evidence: `npm test -- --focus hyperopen.startup.runtime-test` compiled and ran all tests before the production patch, ending with the expected seven RED failures.
- Observation: The quick Playwright smoke route is blocked on this machine by local server state rather than by the outcome code.
  Evidence: the default smoke command refused to start because Java already listens on `8080`; a temporary Python static server on `18083` did not provide SPA fallback, so `/trade` returned 404 and the run was killed as invalid. `npm run browser:cleanup` reported no browser-inspection sessions to stop.

## Decision Log

- Decision: Keep the account outcome fix inside pure projection functions plus VM input wiring, with no new network request from the table component.
  Rationale: The app already fetches `outcomeMeta`, public `webData2.spotAssetCtxs`, root `:asset-contexts`, and active side contexts. The account table should consume already-loaded state deterministically rather than making UI rendering perform I/O.
  Date/Author: 2026-05-03 / Codex
- Decision: Use market metadata when it is available for human-readable titles, and use context sources independently for marks.
  Rationale: The screenshot proves these two failures can occur independently. A row can know the side is `Yes` from the `+0`/`#0` encoding while still lacking the outcome title and current mark.
  Date/Author: 2026-05-03 / Codex
- Decision: Treat a cache with no outcome markets as incomplete and run the low-priority full selector hydration.
  Rationale: Outcome titles cannot be reconstructed from `+0`, `#0`, or spot context prices alone. The existing full hydration path already fetches `outcomeMeta` and builds outcome markets, so the safest repair is to let that path run when the restored cache predates outcome support.
  Date/Author: 2026-05-03 / Codex

## Outcomes & Retrospective

Implementation is complete. The change reduces user-visible ambiguity by making account outcome rows use the best available in-memory side context for mark/value and by ensuring old selector caches do not suppress the full `outcomeMeta` hydration needed for readable titles. Complexity increased slightly in the pure outcome projection because it now normalizes several context shapes, but the added helper keeps network and side effects out of the table rendering path.

## Context and Orientation

Outcome markets are prediction-style spot assets on Hyperliquid. A user's balance can arrive with a token-like coin such as `+0`, while the tradable side context uses a coin such as `#0`. In this repository, `/hyperopen/src/hyperopen/views/account_info/projections/outcomes.cljs` converts those balances into rows for the account Outcomes tab. `/hyperopen/src/hyperopen/views/account_info/vm.cljs` gathers app state and passes it into the projection.

Human-readable outcome market metadata is normalized by `/hyperopen/src/hyperopen/asset_selector/markets.cljs` and stored in `[:asset-selector :market-by-key]`, `:active-market`, or `[:asset-selector :active-market]` when market hydration has completed. Current side marks can come from several places: active subscription contexts in `[:active-assets :contexts]`, normalized account contexts at root `:asset-contexts`, and public raw spot contexts in `[:webdata2 :spotAssetCtxs]`. The bug occurs when account outcomes depend on too small a subset of those paths.

## Plan of Work

First, add tests in `/hyperopen/test/hyperopen/views/account_info/projections/outcomes_test.cljs`, `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs`, and `/hyperopen/test/hyperopen/startup/runtime_test.cljs`. The projection test should show that when metadata is available through explicit market candidates and the mark is available through broader context sources, the row receives the title and mark. The VM test should show that account-info state passes root `:asset-contexts` and `:webdata2.spotAssetCtxs` to the projection. The startup test should show that a cache-hydrated selector with no outcome markets still runs full hydration, while a cache that already includes outcomes can still skip it.

Second, update `/hyperopen/src/hyperopen/views/account_info/projections/outcomes.cljs`. Add helpers to normalize context maps and raw context vectors into a lookup keyed by side coin and token name. Extend `build-outcome-rows` options with `:asset-contexts`, `:spot-asset-ctxs`, and, if needed, extra outcome market candidates. Preserve the existing two-argument and three-argument public signatures.

Third, update `/hyperopen/src/hyperopen/views/account_info/vm.cljs` so `outcome-options` includes root `:asset-contexts` and `(:spotAssetCtxs webdata2)`.

Fourth, update `/hyperopen/src/hyperopen/startup/runtime.cljs` so `skip-deferred-bootstrap?` only skips full market hydration when a restored cache has outcome markets. This avoids persisting old no-outcome caches as a complete market universe.

## Concrete Steps

Run commands from `/Users/barry/.codex/worktrees/b80d/hyperopen`.

1. Add the focused tests before implementation. An attempted focused invocation used:

       npm test -- --focus hyperopen.views.account-info.projections.outcomes-test
       npm test -- --focus hyperopen.views.account-info.vm-test

   The runner ignored `--focus` and ran the full suite, but the newly added tests failed before the production patch as intended.

2. Patch the projection and VM state inputs.

3. Re-run focused tests and then required gates:

       npm test -- --focus hyperopen.views.account-info.projections.outcomes-test
       npm test -- --focus hyperopen.views.account-info.vm-test
       npm run check
       npm test
       npm run test:websocket

4. Account for browser QA. `npm run test:playwright:smoke` was attempted but blocked by local port/server conditions: `8080` was already occupied by Java, and the temporary static fallback-free server returned 404 for `/trade`. The deterministic tests cover the changed behavior.

## Validation and Acceptance

Acceptance requires tests that prove the screenshot state is fixed: an outcome balance for `+0` with size `19` and entry notional `11.0271` renders one row, the row title is the human-readable BTC outcome title when metadata is available anywhere the VM can see it, the side is `Yes`, mark price is `0.53210` when current context supplies it, position value is `10.1099`, and PNL is approximately `-0.9172`.

Repository acceptance requires `npm run check`, `npm test`, and `npm run test:websocket` to pass. These commands passed after the patch. UI browser smoke was explicitly accounted for as blocked by local server configuration, with `browser:cleanup` confirming no Browser MCP sessions were left open.

## Idempotence and Recovery

The changes are source-only and safe to re-run. If a context source has an unexpected shape, keep the new tests and narrow the helper to the actual state shape observed in existing normalization code. If the full patch regresses non-outcome balances, revert only the account outcome projection and VM changes from this plan; the issue and plan can remain as evidence.

## Artifacts and Notes

The current live app nREPL port previously reported by the user, `60305`, was no longer listening during this investigation. A local server was temporarily reachable on `localhost:18083` during early inspection, but it was gone by the browser smoke attempt; direct nREPL state inspection was unavailable.

## Interfaces and Dependencies

`hyperopen.views.account-info.projections.outcomes/build-outcome-rows` must keep these callable forms:

    (build-outcome-rows spot-data market-by-key)
    (build-outcome-rows spot-data market-by-key options)

The `options` map may include:

    :active-market
    :selector-active-market
    :active-contexts
    :asset-contexts
    :spot-asset-ctxs

The return row shape consumed by the account table remains unchanged: keys include `:title`, `:side-name`, `:size`, `:position-value`, `:quote`, `:entry-price`, `:mark-price`, `:pnl-value`, and `:roe-pct`.

Revision note: Created this plan to track `hyperopen-mh69` and to record the root-cause hypothesis before implementing the fix.
