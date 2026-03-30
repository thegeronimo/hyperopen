# Add Trade-Route Jumps From Portfolio And Vault Position Coins

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The live `bd` issue for this work is `hyperopen-gllr`.

## Purpose / Big Picture

Users can already click a position coin on the trade route and immediately switch the active market. The same gesture on `/portfolio` or `/vaults/:vaultAddress` does not take them into the trade route, so they lose the shortcut from position discovery to market action. After this change, clicking a position coin from the portfolio positions surface or the vault-detail positions surface must land on the trade route with that market selected, while leaving every non-position coin click unchanged.

## Progress

- [x] (2026-03-30 16:26Z) Audited the existing trade-route position click path and confirmed the reusable coin control dispatches `:actions/select-asset`.
- [x] (2026-03-30 16:26Z) Confirmed `hyperopen.asset-selector.actions/select-asset` updates the route only when the current route is already `/trade`.
- [x] (2026-03-30 16:26Z) Confirmed `/portfolio` reuses the shared account-info positions table, while vault detail renders an independent positions table with no coin click handler.
- [x] (2026-03-30 16:26Z) Created and claimed `hyperopen-gllr` for this navigation feature.
- [x] (2026-03-30 16:26Z) Chose a surface-local navigation strategy that composes existing actions instead of changing global `select-asset` semantics.
- [x] (2026-03-30 17:07Z) Extended the shared coin control with optional `:click-actions` and `:attrs` overrides while preserving the default single-action `select-asset` behavior for unchanged callers.
- [x] (2026-03-30 17:07Z) Wired portfolio positions coin cells to dispatch `select-asset` and then navigate to `router/trade-route-path` when the account-info VM is rendered on a portfolio route.
- [x] (2026-03-30 17:07Z) Wired vault-detail position coin cells to dispatch the same two-action sequence and preserved the existing side accent styling via a button wrapper inside the table cell.
- [x] (2026-03-30 17:07Z) Added focused view regressions plus deterministic Playwright coverage for portfolio and vault detail, then passed the targeted Playwright runs, `npm test`, `npm run test:websocket`, and `npm run check`.

## Surprises & Discoveries

- Observation: `select-asset` intentionally does not emit router effects when the current route is not a trade route.
  Evidence: `test/hyperopen/asset_selector/actions_test.cljs` already asserts that `/portfolio` input produces no `[:effects/save [:router :path] ...]` or `[:effects/push-state ...]` effects.

- Observation: generic `:actions/navigate` cannot be used with `/trade?market=<coin>` because route normalization strips search parameters before storing `:router :path`.
  Evidence: `/hyperopen/src/hyperopen/runtime/action_adapters/navigation.cljs` calls `router/normalize-path`, and `/hyperopen/src/hyperopen/router.cljs` removes query and fragment text during normalization.

- Observation: portfolio mobile positions do not expose a standalone coin button because the entire summary shell is already a disclosure button.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs` renders each expandable summary as `[:button ...]`, so nesting a second coin button there would be invalid HTML.

- Observation: vault detail position rows preserve `:coin` but discard any outer market identity before render.
  Evidence: `/hyperopen/src/hyperopen/vaults/adapters/webdata.cljs` projects position rows to `:coin`, `:size`, `:leverage`, `:position-value`, and related numeric fields only.

- Observation: the Playwright portfolio route did not issue a deterministic `webData2` request inside the local browser harness, even after wallet simulation.
  Evidence: the first isolated regression attempts timed out waiting for a stubbed `webData2` request, while the rendered portfolio shell stayed on its default no-positions state.

- Observation: the compiled browser runtime exposes `hyperopen.system.store` and `cljs.core`, so deterministic state seeding is available without adding production-only debug actions.
  Evidence: a compiled-runtime probe against the Playwright static server confirmed `globalThis.hyperopen.system.store`, `cljs.core.reset_BANG_`, and `cljs.core.assoc_in` are all available to `page.evaluate`.

- Observation: the vault detail implementation initially referenced an unqualified `non-blank-text`, and the compile step surfaced it immediately.
  Evidence: a local `shadow-cljs` compile emitted `Use of undeclared Var hyperopen.views.vaults.detail.activity/non-blank-text` for `/hyperopen/src/hyperopen/views/vaults/detail/activity.cljs`.

## Decision Log

- Decision: keep the fix local to the affected position surfaces instead of changing `hyperopen.asset-selector.actions/select-asset`.
  Rationale: `select-asset` is also used by balances, open orders, histories, and asset selector rows. Changing it globally would broaden the behavior far beyond the requested portfolio and vault positions surfaces.
  Date/Author: 2026-03-30 / Codex

- Decision: implement the new behavior as a two-action click sequence, first `[:actions/select-asset coin]`, then `[:actions/navigate (router/trade-route-path coin)]`.
  Rationale: `select-asset` keeps the running app state, subscriptions, and active-market display in sync immediately, while `navigate` handles route-module loading and spectate-aware browser navigation without requiring a new action contract.
  Date/Author: 2026-03-30 / Codex

- Decision: use `/trade/<coin>` as the navigated browser route instead of inventing a new action that preserves `?market=<coin>`.
  Rationale: `/hyperopen/src/hyperopen/startup/restore.cljs` already restores the active market from either the route asset segment or the market query. The path form works with existing navigation helpers and remains reload-safe.
  Date/Author: 2026-03-30 / Codex

- Decision: scope this ticket to desktop and table-style position coin affordances, and treat mobile portfolio cards as regression-only for now.
  Rationale: the current mobile summary card is a disclosure button, so a true coin-symbol tap target would require a broader card-structure refactor that is larger than the requested feature.
  Date/Author: 2026-03-30 / Codex

## Outcomes & Retrospective

The final implementation stayed within the planned local-surface scope. Portfolio and vault-detail position coin clicks now compose the existing `select-asset` action with a follow-up `navigate` to `/trade/<coin>`, while trade-route positions and every other coin affordance keep their prior semantics. Browser coverage stayed deterministic by using the normal vault route-load stubs and by seeding portfolio `webdata2` directly through the exposed CLJS store inside the Playwright harness rather than broadening production runtime seams.

Validation completed with passing targeted Playwright regressions for both affected surfaces plus passing repository gates: `npm test`, `npm run test:websocket`, and `npm run check`. The only follow-up debt recorded during execution was a namespace-size exception bump to exact post-change counts for the already-exempt oversized files touched by this ticket.

## Context and Orientation

The trade route account surfaces are assembled through `/hyperopen/src/hyperopen/views/account_info_view.cljs`. The positions table inside that surface lives in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`, and its coin cell uses the reusable `coin-select-control` from `/hyperopen/src/hyperopen/views/account_info/shared.cljs`. Today that helper always dispatches `[:actions/select-asset coin]`.

`/hyperopen/src/hyperopen/asset_selector/actions.cljs` implements `select-asset`. It resolves the market, updates `:active-market`, resets order-form UI as needed, unsubscribes the previous market streams, subscribes the new market streams, and only then calls `trade-route-sync-effects`. That route helper deliberately emits browser navigation only when the current route is already a trade route.

`/portfolio` is not a separate positions implementation. `/hyperopen/src/hyperopen/views/portfolio_view.cljs` renders `/hyperopen/src/hyperopen/views/account_info_view.cljs`, so the portfolio positions tab is the same positions component with route-specific state. That means the portfolio feature should be implemented as an opt-in override, not as a second hand-built positions table.

Vault detail is separate. `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` derives activity rows from `/hyperopen/src/hyperopen/vaults/adapters/webdata.cljs`, and `/hyperopen/src/hyperopen/views/vaults/detail/activity.cljs` renders the positions table. The current vault position coin cell is plain text with accent styling and no click action.

The router primitives already exist in `/hyperopen/src/hyperopen/router.cljs`. `router/trade-route-path` returns `/trade/<coin>`, and startup restore in `/hyperopen/src/hyperopen/startup/restore.cljs` accepts that route form as an authoritative market source. Generic `:actions/navigate` can move the browser to that path and preserve spectate mode through `/hyperopen/src/hyperopen/account/spectate_mode_links.cljs`.

## Plan of Work

First, extend `/hyperopen/src/hyperopen/views/account_info/shared.cljs` so `coin-select-control` can accept an opt-in click-action override while preserving its existing default. The default must remain `[:actions/select-asset coin]` for every caller that does not pass an override. This keeps balances, open orders, trade history, funding history, order history, TWAP rows, and asset-selector rows untouched.

Second, thread a portfolio-only navigation flag into the positions table. The cleanest place to derive that flag is `/hyperopen/src/hyperopen/views/account_info/vm.cljs`, because it already has the full route state and already builds `positions-state` for both trade and portfolio consumers. Use `/hyperopen/src/hyperopen/portfolio/routes.cljs` rather than ad hoc string matching so the flag is true on portfolio routes and false on trade routes. In `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`, compute coin click actions from the raw market coin stored in `(:coin (:position row-data))`, not from the stripped display label. When the flag is false, keep the existing single `select-asset` action. When the flag is true, pass the two-action override into `coin-select-control`.

Third, update `/hyperopen/src/hyperopen/views/vaults/detail/activity.cljs` so `position-coin-cell` becomes a button-styled cell that preserves the current accent strip, leverage chip, and side tone classes while dispatching the same two-action sequence. The click target must use the raw `:coin` carried by the vault activity row. If deterministic browser selectors are missing, add tight `data-role` attributes only where needed for the new regression tests instead of broad selector churn.

Fourth, keep non-goals explicit. Do not broaden the new navigation to balances, open orders, fills, funding history, or generic asset selector rows. Do not alter `hyperopen.asset-selector.actions/select-asset` semantics for non-trade routes. Do not attempt to push a `?market=<coin>` query through `:actions/navigate`, because that action intentionally normalizes routes to paths. Do not refactor the mobile portfolio card shell in this ticket.

Fifth, add regression coverage. In `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`, keep the existing trade-surface assertion that a default position row emits only `[:actions/select-asset coin]`. Add a portfolio-specific assertion in `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs` that the positions-tab coin button dispatches both `select-asset` and `navigate` to `/trade/<coin>`. In `/hyperopen/test/hyperopen/views/vaults/detail/activity_test.cljs`, assert that vault position coin cells become buttons with the same two actions while retaining the current long/short color treatment.

Finally, add browser coverage using existing Playwright suites. `/hyperopen/tools/playwright/test/portfolio-regressions.spec.mjs` should gain a regression that opens `/portfolio`, selects the `Positions` tab, clicks a deterministic position coin, waits for idle, and verifies both the trade route pathname and the selected active asset. `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` should gain a vault-detail regression that stubs a vault `webData2` response with a known position coin, navigates to `/vaults/:address`, switches to the `Positions` activity tab, clicks the coin, and verifies the jump to `/trade/<coin>`.

## Concrete Steps

From `/Users/barry/.codex/worktrees/912b/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/views/account_info/shared.cljs`, `/hyperopen/src/hyperopen/views/account_info/vm.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`, and `/hyperopen/src/hyperopen/views/vaults/detail/activity.cljs` to add the opt-in position-surface navigation behavior.
2. Update `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`, and `/hyperopen/test/hyperopen/views/vaults/detail/activity_test.cljs` to lock the new click-action contracts and to prove the trade-route positions surface still keeps its existing single-action behavior.
3. Update `/hyperopen/tools/playwright/test/portfolio-regressions.spec.mjs` and `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` with deterministic route-jump regressions for portfolio and vault detail.
4. Run the smallest relevant Playwright commands first:

   npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "position coin jumps to trade"
   npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "vault position coin jumps to trade"

5. Run the required repository gates:

   npm run check
   npm test
   npm run test:websocket

If Playwright browsers are not installed in the environment yet, run `npm run test:playwright:install` once before step 4.

## Validation and Acceptance

Acceptance is satisfied when all of the following are true:

1. On `/portfolio`, after selecting the `Positions` tab, clicking a position coin moves the browser to `/trade/<coin>` and the active market matches that raw coin.
2. On `/vaults/:vaultAddress`, after selecting the `Positions` activity tab, clicking a position coin moves the browser to `/trade/<coin>` and the active market matches that raw coin.
3. On `/trade`, clicking a position coin still behaves like the current implementation and does not acquire extra navigation logic from the portfolio override path.
4. Balances, open orders, histories, TWAP rows, and asset selector rows keep their current click behavior because they do not pass the new override.
5. The targeted Playwright regressions pass before broader validation, and `npm run check`, `npm test`, and `npm run test:websocket` all pass afterward.

## Idempotence and Recovery

The planned code changes are additive. Re-running the patch should be safe because the shared coin control keeps a stable default path for every unchanged caller. If the override flag leaks into trade surfaces, remove the portfolio-route derivation in `/hyperopen/src/hyperopen/views/account_info/vm.cljs` and rerun the view tests before touching any action logic. If the vault-detail click target loses its accent styling, restore the original cell class list first, then reintroduce the button wrapper without changing the surrounding table structure.

## Artifacts and Notes

Expected click-action contracts after the patch:

   Trade positions default:
     [[:actions/select-asset "xyz:HYPE"]]

   Portfolio positions override:
     [[:actions/select-asset "xyz:HYPE"]
      [:actions/navigate "/trade/xyz:HYPE"]]

   Vault detail positions:
     [[:actions/select-asset "BTC"]
      [:actions/navigate "/trade/BTC"]]

Existing evidence that motivated the scope:

   `test/hyperopen/asset_selector/actions_test.cljs`
     "non-trade routes do not emit route-sync effects from select-asset"

   `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs`
     summary rows are already buttons, so a nested mobile coin button is not a safe patch for this ticket

## Interfaces and Dependencies

The implementation must preserve the current public behavior of `hyperopen.asset-selector.actions/select-asset` and `hyperopen.runtime.action-adapters.navigation/navigate`. The only interface expansion planned here is an optional click-action override on `hyperopen.views.account-info.shared/coin-select-control`. That helper must continue to render the same default button semantics for all existing callers that do not opt in.

The portfolio route detection should use `hyperopen.portfolio.routes/portfolio-route?` from `/hyperopen/src/hyperopen/portfolio/routes.cljs`. Trade destinations must use `hyperopen.router/trade-route-path` from `/hyperopen/src/hyperopen/router.cljs`. Vault detail should continue to consume projected rows from `/hyperopen/src/hyperopen/vaults/adapters/webdata.cljs` without inventing a new route-specific market format.

Plan revision note (2026-03-30): Created after repository audit and sub-agent discovery confirmed that the missing behavior is caused by route-local `select-asset` semantics, not by missing market-selection state on the trade route itself.
