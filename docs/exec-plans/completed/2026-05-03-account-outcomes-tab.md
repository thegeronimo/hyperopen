# Account Outcomes Tab

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan follows `/hyperopen/.agents/PLANS.md`. It is tracked by `bd` issue `hyperopen-kcn2`.

## Purpose / Big Picture

Outcome-market holdings currently arrive as spot-like token balances and can appear in the Balances tab as opaque token symbols such as `+0` or `#0`. A trader who buys Yes or No on an outcome market needs to see that holding as an outcome position with the full market title, side, size, value, entry price, mark price, and PNL. After this change, the account table includes an Outcomes tab when outcome holdings exist. The Balances tab no longer lists outcome side tokens as ordinary balances.

The user-visible result should match the Hyperliquid pattern in the supplied screenshot: the tab strip shows `Outcomes (N)` near Positions, the row starts with the readable outcome question, a chip marks it as `Outcome`, and numeric columns use position-style meaning rather than balance-style meaning.

## Progress

- [x] (2026-05-03T01:52:42Z) Created and claimed `bd` issue `hyperopen-kcn2`.
- [x] (2026-05-03T01:52:42Z) Inspected account table modules, balance projection, position projection, outcome market metadata normalization, and current tab routing.
- [x] (2026-05-03T01:52:42Z) Checked Hyperliquid docs for outcome asset encoding: outcome side coins use `#<encoding>`, token names use `+<encoding>`, and asset ids use `100_000_000 + encoding`.
- [x] (2026-05-03T01:55:00Z) Added failing tests for outcome balance filtering, outcome row derivation, tab counts, route normalization, and rendering.
- [x] (2026-05-03T01:57:00Z) Implemented the outcome projection, tab registry/view-model wiring, and Outcomes tab UI.
- [x] (2026-05-03T02:02:23Z) Ran focused tests, required gates, websocket tests, and governed browser QA accounting.
- [x] (2026-05-03T02:05:00Z) Updated this plan with final outcomes, validation evidence, and prepared it to move to completed.

## Surprises & Discoveries

- Observation: Hyperopen already fetches `outcomeMeta` and builds normalized outcome markets in `/hyperopen/src/hyperopen/asset_selector/markets.cljs`. The derived market rows include `:key`, `:title`, `:outcome-id`, `:outcome-sides`, side coins such as `#0`, side names such as `Yes`, side marks, and settlement details.
  Evidence: `build-outcome-markets` maps `outcomeMeta` plus `spotAssetCtxs` into `:market-type :outcome` markets, and `test/hyperopen/asset_selector/markets_test.cljs` asserts title, side coin, side asset id, mark, and details.

- Observation: Hyperliquid docs confirm outcome accounts are spot-like but not semantically normal spot balances. They encode each outcome side as `#<encoding>` and the corresponding token name as `+<encoding>`.
  Evidence: official Hyperliquid Asset IDs docs state `encoding = 10 * outcome + side` and list outcome spot coin, token name, and asset id representations.

## Decision Log

- Decision: Derive Outcomes tab rows from raw spot balances, not from perps `assetPositions`.
  Rationale: Outcome holdings are fully collateralized spot-like side tokens. Their position semantics come from joining spot balance size and entry notional with outcome market metadata, not from clearinghouse perp position rows.
  Date/Author: 2026-05-03 / Codex

- Decision: Keep the new tab separate from Positions for this implementation.
  Rationale: Hyperliquid currently gives outcomes their own tab. A separate tab avoids forcing fully collateralized outcome holdings into the perps position grid, which has margin, liquidation, funding, close, and TP/SL columns that do not apply.
  Date/Author: 2026-05-03 / Codex

- Decision: Exclude outcome side tokens from Balances only when they are classified as outcome tokens, while preserving all ordinary spot balances.
  Rationale: This fixes the confusing display without hiding user value. If metadata is partial, the outcome row should still render a raw fallback so the holding is visible.
  Date/Author: 2026-05-03 / Codex

## Outcomes & Retrospective

Implemented. Outcome side token balances are now identified from `+<encoding>`, `#<encoding>`, or outcome asset ids, excluded from Balances, and projected into an account Outcomes tab. Rows join spot balance size and entry notional with normalized outcome market metadata so the UI can render the full outcome title, `Outcome` and side chips, raw side symbol, size, position value, entry price, mark price, and PNL/ROE. If metadata is missing, the projection still returns a fallback outcome row rather than dropping the holding.

The chosen separate-tab model matched the user-supplied Hyperliquid screenshot and avoided forcing fully collateralized outcome holdings into perp-specific Positions columns.

## Context and Orientation

The account table UI lives under `/hyperopen/src/hyperopen/views/account_info*`. `/hyperopen/src/hyperopen/views/account_info_view.cljs` owns the top-level tab renderers. `/hyperopen/src/hyperopen/views/account_info/tab_registry.cljs` defines base tabs and labels. `/hyperopen/src/hyperopen/views/account_info/vm.cljs` projects global application state into the account table view model and computes tab counts. `/hyperopen/src/hyperopen/views/account_info/projections/balances.cljs` builds Balances rows from perps account value plus spot balances. `/hyperopen/src/hyperopen/views/account_info/projections/positions.cljs` builds perps position rows.

Outcome market metadata is already normalized in `/hyperopen/src/hyperopen/asset_selector/markets.cljs`. The important fields for this task are `:market-type :outcome`, `:key` like `outcome:0`, `:title`, `:symbol`, `:quote` currently `USDH`, and `:outcome-sides`. Each side has `:side-index`, `:side-name`, `:coin` like `#0`, `:asset-id`, `:mark`, and `:markRaw`. Hyperliquid also represents the same outcome token as `+0` in token-name contexts. Account spot balances contain `:coin`, `:token`, `:hold`, `:total`, and `:entryNtl` or equivalent entry notional fields.

## Plan of Work

First add tests. Extend balance projection tests to prove outcome side tokens are removed from balance rows. Add a new outcomes projection test namespace that feeds spot balances with `+0` and `#1` plus market metadata and expects readable rows with title, side, size, value, entry, mark, PNL, and ROE. Extend account VM tests so `:outcomes` selection computes outcome rows and tab count, and so `:balances` excludes outcome counts. Add rendering tests for the new tab to assert the Outcome chip, full symbol metadata, and columns.

Then implement a small projection namespace, `/hyperopen/src/hyperopen/views/account_info/projections/outcomes.cljs`. It should define helpers to recognize outcome side coins and token names, normalize `+<encoding>` to `#<encoding>`, build a side lookup from `market-by-key`, and convert raw balances to outcome rows. It should use entry notional keys already supported by the balance projection. It should not make network calls.

Next wire the namespace through `/hyperopen/src/hyperopen/views/account_info/projections.cljs`, `/hyperopen/src/hyperopen/views/account_info/projections/balances.cljs`, `/hyperopen/src/hyperopen/views/account_info/vm.cljs`, `/hyperopen/src/hyperopen/views/account_info/tab_registry.cljs`, and `/hyperopen/src/hyperopen/account/history/shared.cljs`. The tab order should become Balances, Positions, Outcomes, Open Orders, TWAP, Trade History, Funding History, Order History. The label should count outcomes like Positions and Open Orders.

Finally add `/hyperopen/src/hyperopen/views/account_info/tabs/outcomes.cljs` for desktop and mobile rendering. The desktop grid should use columns: Outcome, Size, Position Value, Entry Price, Mark Price, PNL (ROE %). The Outcome cell should show the market title, an `Outcome` chip, the side chip, and secondary raw identifiers such as `#0 / outcome:0`. The size should be absolute token amount plus side name, for example `19 Yes`. There are no action columns because outcome side holdings are not perps positions with liquidation, margin, funding, TP/SL, or reduce controls.

## Concrete Steps

Run commands from `/Users/barry/.codex/worktrees/b80d/hyperopen`.

1. Write tests:

   `npm test -- --focus hyperopen.views.account-info.projections.outcomes-test`

   Expected before implementation: the namespace or functions are missing, so the focused test fails.

2. Implement projection and tab wiring.

3. Re-run focused tests:

   `npm test -- --focus hyperopen.views.account-info.projections.outcomes-test`
   `npm test -- --focus hyperopen.views.account-info.vm-test`
   `npm test -- --focus hyperopen.views.account-info.tabs.outcomes-test`
   `npm test -- --focus hyperopen.views.account-info.tabs.balances.projection-test`

4. Run required gates after code changes:

   `npm run check`
   `npm test`
   `npm run test:websocket`

5. For UI accounting, run the governed design review and cleanup:

   `npm run qa:design-ui -- --targets trade-route --manage-local-app`
   `npm run browser:cleanup`

## Validation and Acceptance

Acceptance is met when a state with an outcome balance such as `{:coin "+0" :total "19" :entryNtl "11.0271"}` and market metadata for `outcome:0` renders no `+0` row in Balances, renders an Outcomes tab with count `1`, and shows a row whose title is the full outcome question, whose chips include `Outcome` and `Yes`, whose size reads `19 Yes`, whose position value uses side mark times size, whose entry price uses entry notional divided by size, and whose PNL/ROE are derived from value minus entry notional.

The tests should prove both `+<encoding>` token names and `#<encoding>` spot coin ids classify as outcome side holdings. They should also prove regular spot balances such as `HYPE` remain in Balances.

## Idempotence and Recovery

All changes are local source, tests, and one plan file. Re-running tests and derivations is safe. If outcome metadata is missing for a balance, the projection should still produce a fallback row with the raw coin and `Outcome` chip rather than throwing. If the UI wiring causes an unknown tab, reverting the tab registry and view model additions cleanly restores the previous account table.

## Artifacts and Notes

Official Hyperliquid docs consulted: `https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/asset-ids`. Relevant facts are embedded above so this plan remains self-contained.

Validation completed:

- `npm test` passed: 3722 tests, 20570 assertions, 0 failures, 0 errors.
- `npm run check` passed, including policy/lint/tooling gates and Shadow CLJS compiles for `app`, `portfolio`, workers, and `test`.
- `npm run test:websocket` passed: 523 tests, 3041 assertions, 0 failures, 0 errors.
- `npm run qa:design-ui -- --targets trade-route --manage-local-app` passed with `reviewOutcome: PASS` at widths 375, 768, 1280, and 1440. Visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes all reported PASS with zero issues.
- `npm run browser:cleanup` completed with no tracked sessions left running.

Browser QA residual blind spot: the design-review tool reported its standard default-state limitation that hover, active, disabled, and loading states require targeted route actions when not present by default. No blocking issue or failure was reported.

## Interfaces and Dependencies

No new dependency is required. The new public projection functions should be:

    hyperopen.views.account-info.projections.outcomes/outcome-token?
    hyperopen.views.account-info.projections.outcomes/build-outcome-rows

`build-outcome-rows` accepts `spot-data` and `market-by-key` and returns a vector of row maps consumed by the Outcomes tab. `/hyperopen/src/hyperopen/views/account_info/projections.cljs` should re-export these functions. `/hyperopen/src/hyperopen/views/account_info/vm.cljs` should add `:outcomes` to the view model and `:outcomes` to `:tab-counts`.

Revision note: 2026-05-03T01:52:42Z - Created the initial plan after code and Hyperliquid outcome-encoding research.

Revision note: 2026-05-03T02:05:00Z - Recorded implementation, validation results, browser QA evidence, and residual blind spot.
