# Fix Account Outcome Enrichment

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan follows `/hyperopen/.agents/PLANS.md`. It is tracked by `bd` issue `hyperopen-13ve`.

## Purpose / Big Picture

The account Outcomes tab currently shows the user's outcome holding as two raw side-token rows: one real Yes balance and one zero-size No balance. The real row is missing the human-readable market name, side name, mark price, position value, and correct PNL. After this fix, the account table should match Hyperliquid's outcome table semantics: one row per non-zero outcome side held, the Outcome column should display only the market's readable title, Size should display amount plus side such as `19 Yes`, and value, mark, and PNL should derive from the live side mark.

The visible proof is a state with a `+0` or `#0` balance of `19`, an opposite-side zero row, active outcome market metadata, and a live `#0` mark. The tab should show `Outcomes (1)`, the full BTC outcome title, `19 Yes`, non-zero position value, mark `0.53210` for the supplied example, and PNL based on that value minus entry notional.

## Progress

- [x] (2026-05-03T02:07:26Z) Created and claimed `bd` issue `hyperopen-13ve`.
- [x] (2026-05-03T02:07:40Z) Investigated the existing outcome projection, renderer, asset-selector outcome metadata, live active-asset context patching, and VM inputs.
- [x] (2026-05-03T02:10:00Z) Added failing tests that reproduce the screenshot: zero-size side row included, missing active-market enrichment, missing live side mark, and extra chips.
- [x] (2026-05-03T02:12:00Z) Implemented the smallest data-flow fix in projections, VM wiring, live market side patching, and renderer.
- [x] (2026-05-03T02:17:00Z) Ran focused tests, required gates, websocket tests, and browser QA accounting.
- [x] (2026-05-03T02:17:09Z) Updated this plan with final validation and prepared it to move to completed.

## Surprises & Discoveries

- Observation: The screenshot's `#0`, `Side 0`, `0.00000` mark, zero position value, and `#1` zero-size row are only possible when `build-outcome-rows` falls back because side metadata is missing or not considered, and when the projection keeps zero-size outcome balances.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/projections/outcomes.cljs` returns fallback title `side-coin`, fallback side name `Side <encoding>`, mark price `0`, and includes every `outcome-balance?` without checking `:total`.

- Observation: The account VM passes only `[:asset-selector :market-by-key]` into `build-outcome-rows`. On a trade route, the most reliable currently selected outcome metadata may also be present as `:active-market` or `[:asset-selector :active-market]`, and live marks are present in `[:active-assets :contexts "#0"]`.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/vm.cljs` calls `projections/build-outcome-rows spot-data market-by-key`; `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs` stores normalized live context under `[:active-assets :contexts coin]`.

- Observation: Live active outcome updates patch the top-level outcome market but do not patch the matching side inside `:outcome-sides`.
  Evidence: `/hyperopen/src/hyperopen/asset_selector/market_live_projection.cljs` updates `:mark` and `:openInterest` on the market map for `:market-type :outcome`, but it does not update the side map whose `:coin` equals the live context coin.

## Decision Log

- Decision: Filter outcome rows to non-zero token balances.
  Rationale: Hyperliquid's outcome table shows positions the user holds. A zero-size opposite side is not a position and creates the false `Outcomes (2)` count shown in the screenshot.
  Date/Author: 2026-05-03 / Codex

- Decision: Remove the `Outcome` and side chips from the Outcome column.
  Rationale: The user-supplied Hyperliquid screenshot shows only the readable market name in the Outcome column. The side belongs in Size, not as a visual chip in the market-name cell.
  Date/Author: 2026-05-03 / Codex

- Decision: Enrich rows from multiple local sources before falling back: `market-by-key`, active outcome market, selector active outcome market, and active side contexts.
  Rationale: The account surface should not render raw `#0` while the current route already has the selected outcome market and live side mark in state. This fixes the root data-flow problem without adding network calls.
  Date/Author: 2026-05-03 / Codex

## Outcomes & Retrospective

Implemented. The account Outcomes tab now filters out zero-size outcome side balances, enriches active holdings from all local outcome metadata sources available to the account surface, uses live side context marks when present, and renders the Outcome cell without the extra `Outcome` and side chips. The live market projection also patches the matching `:outcome-sides` entry and no longer lets a No-side active context update clobber the top-level Yes market mark.

The change reduces user-visible complexity by making the account row follow Hyperliquid's semantics: one held side, one readable title, side in the Size column, and value/P&L derived from the correct side mark. Internally it adds a small options map to `build-outcome-rows`, which is a modest increase in projection inputs but keeps the fix pure and avoids network calls from the account UI.

## Context and Orientation

Outcome markets on Hyperliquid are represented as spot-like side tokens. For an outcome id and side index, Hyperliquid encodes the side as `encoding = 10 * outcome + side`. The side coin is `#<encoding>`, the token name can be `+<encoding>`, and the asset id is `100000000 + encoding`. A balance row can therefore arrive as `{:coin "+0" :token 100000000 :total "19" :entryNtl "11.0271"}` even though the user thinks of it as a Yes outcome position.

The account view model is built in `/hyperopen/src/hyperopen/views/account_info/vm.cljs`. The pure outcome row projection is `/hyperopen/src/hyperopen/views/account_info/projections/outcomes.cljs`. The rendered tab is `/hyperopen/src/hyperopen/views/account_info/tabs/outcomes.cljs`. Outcome market metadata is built in `/hyperopen/src/hyperopen/asset_selector/markets.cljs` with fields such as `:title`, `:key`, and `:outcome-sides`. Live market updates are applied in `/hyperopen/src/hyperopen/asset_selector/market_live_projection.cljs`; active side contexts are stored in application state under `[:active-assets :contexts]`.

## Plan of Work

First, add failing tests that reproduce the user's screenshot rather than the optimistic fixture from the first implementation. Extend `/hyperopen/test/hyperopen/views/account_info/projections/outcomes_test.cljs` with a case where `market-by-key` is empty but `active-market` has the outcome title and sides, active contexts contain `#0` mark `0.53210`, and spot balances contain `+0` total `19` plus `+1` total `0`. The expected result is one row with title `BTC above 78213 on May 3 at 2:00 AM?`, side `Yes`, size `19`, mark `0.53210`, position value `10.1099`, and PNL `-0.9172` for entry notional `11.0271`. Add a VM test for the same state to prove `:tab-counts :outcomes` is `1`. Add a renderer test proving the row no longer contains `Outcome`, `Yes` as chips, or `#0 / outcome:0`, but still contains title, `19 Yes`, value, entry, mark, and PNL. Add a live projection test proving active context for `#1` patches the `#1` side mark, not just the top-level market.

Second, update `/hyperopen/src/hyperopen/views/account_info/projections/outcomes.cljs`. Add a row activity predicate that requires non-zero `:total` after numeric parsing. Add an options arity to `build-outcome-rows` so callers can pass `:active-market`, `:selector-active-market`, and `:active-contexts` while the existing two-argument call remains valid. Build the side lookup from all outcome market candidates, not only `market-by-key`. When choosing mark price, prefer active context for the side coin, then side mark, then top-level market mark for that side. When metadata is still missing, derive side name from `encoding mod 10` so `0` maps to `Yes` and `1` maps to `No`, rather than displaying `Side 0` for common binary outcomes.

Third, update `/hyperopen/src/hyperopen/views/account_info/vm.cljs` so both selected-tab rows and tab counts pass the active market and active contexts to the projection. Keep the change pure: no network calls and no mutation inside the projection.

Fourth, update `/hyperopen/src/hyperopen/asset_selector/market_live_projection.cljs` so active context updates for outcome side coins patch the matching entry in `:outcome-sides`. This keeps future `market-by-key` lookups complete even outside the currently active account table render.

Fifth, update `/hyperopen/src/hyperopen/views/account_info/tabs/outcomes.cljs` to remove the `Outcome` chip, side chip, and raw symbol secondary line from the desktop and mobile Outcome cell. The Size column continues to display the side as text, for example `19 Yes`.

## Concrete Steps

Run commands from `/Users/barry/.codex/worktrees/b80d/hyperopen`.

1. Write and run failing tests:

   `npm test`

   Observed before implementation: 22 failures. The failures showed zero rows included, active context ignored, side marks not patched, the No-side context clobbering the top-level outcome mark, and the renderer still including chips/raw identifiers.

2. Implement the projection, VM, live-market patching, and renderer changes.

3. Re-run focused tests during implementation:

   `npm test`

4. Run required gates after code changes:

   `npm run check`
   `npm test`
   `npm run test:websocket`

5. For UI accounting, run:

   `npm run qa:design-ui -- --targets trade-route --manage-local-app`
   `npm run browser:cleanup`

## Validation and Acceptance

Acceptance is met when a test state with `+0` total `19`, `+1` total `0`, active outcome title `BTC above 78213 on May 3 at 2:00 AM?`, entry notional `11.0271`, and live `#0` mark `0.53210` projects exactly one outcome row. The tab count must be `1`, the row title must be the full human-readable market name, Size must be `19 Yes`, Position Value must be `10.11 USDH`, Entry Price must be `0.58037` or the existing rounded equivalent, Mark Price must be `0.53210`, and PNL must be derived from `10.1099 - 11.0271`.

The rendered Outcome cell must not show an `Outcome` chip, a side chip, or raw `#0 / outcome:0` metadata. The side remains visible in the Size column.

## Idempotence and Recovery

All changes are local source, tests, and this plan. Re-running projections and tests is safe. If enrichment metadata is unavailable after all local sources are checked, the row still falls back to the side coin so the holding remains visible rather than disappearing. If live context patching causes unexpected market state changes, revert the `market_live_projection.cljs` side-patching function while retaining the projection's active-context fallback.

## Artifacts and Notes

The user-provided current screenshot shows `Outcomes (2)`, rows `#0` and `#1`, `19 Side 0`, `0 Side 1`, mark `0.00000`, and PNL `-$11.04 (-100.0%)`. The user-provided Hyperliquid reference screenshot shows `Outcomes (1)`, title `BTC above 78213 on May 3 at 2:00 AM?`, `19 Yes`, position value `10.10 USDH`, entry `0.58090`, mark `0.53210`, and PNL `-$0.93 (-8.4%)`.

Validation completed:

- `npm test` passed: 3725 tests, 20591 assertions, 0 failures, 0 errors.
- `npm run check` passed, including namespace checks, docs checks, tooling tests, and Shadow CLJS compiles for app, portfolio, workers, and test.
- `npm run test:websocket` passed: 523 tests, 3041 assertions, 0 failures, 0 errors.
- First `npm run qa:design-ui -- --targets trade-route --manage-local-app` attempt was blocked because the user's live Shadow server was already running on `localhost:9630`.
- `node tools/browser-inspection/src/cli.mjs session start --local-url http://localhost:8080/index.html` started browser-inspection session `sess-1777774602126-1fd72c` against the existing local app.
- `npm run qa:design-ui -- --targets trade-route --session-id sess-1777774602126-1fd72c` passed with `reviewOutcome: PASS` at widths 375, 768, 1280, and 1440. Visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes all reported PASS with zero issues.
- `npm run browser:cleanup` stopped `sess-1777774602126-1fd72c`.

Browser QA residual blind spot: the design-review tool reported its standard default-state limitation that hover, active, disabled, and loading states require targeted route actions when not present by default. No failure or blocking issue was reported.

## Interfaces and Dependencies

No new dependencies are required. The projection interface remains:

    hyperopen.views.account-info.projections.outcomes/outcome-token?
    hyperopen.views.account-info.projections.outcomes/outcome-balance?
    hyperopen.views.account-info.projections.outcomes/build-outcome-rows

`build-outcome-rows` keeps its existing two-argument form and gains a three-argument form:

    (build-outcome-rows spot-data market-by-key)
    (build-outcome-rows spot-data market-by-key {:active-market market
                                                 :selector-active-market market
                                                 :active-contexts contexts})

Revision note: 2026-05-03T02:07:40Z - Created the plan after root-cause investigation of the account outcome projection, renderer, market metadata, and live context update path.

Revision note: 2026-05-03T02:17:09Z - Recorded implementation outcome, red/green evidence, browser QA workaround for the live Shadow server, and final validation results.
