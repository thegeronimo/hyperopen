# Supply, borrow and track HyperCore lending from a new Earn page

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current as work proceeds. Maintain it according to `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

HyperCore, the Hyperliquid exchange ledger, now has a native lending pool. Anyone can supply USDC or USDT0 to earn a rate set by how much of the pool is borrowed, and accounts can borrow those stablecoins against HYPE or UBTC collateral. Manual borrowing went live on mainnet on 2026-09-18. Hyperopen today can only repay a borrow, all at once, from a Balances row; it cannot show pool rates, supply, withdraw, borrow, or show how close a borrow is to liquidation. The portfolio's "Earn Balance" row always reads $0 because nothing writes the state it reads.

After this change a Hyperopen user can open a new `/earn` page from the header. They see every lending pool with its supply rate, borrow rate and utilization, and the account's own supplied, collateral and borrowed amounts with a health factor and the HYPE price at which partial liquidation would begin. From that page they open one modal that supplies, withdraws, borrows or repays any amount. Before signing, the modal previews what the change does: for a supply or withdrawal, the pool's new utilization and rates; for a borrow or repay, the new health factor and liquidation price. Portfolio-margin accounts, which borrow automatically, cannot open a manual borrow. The portfolio Earn Balance shows the real supplied value.

To see it working: run the app (`npm run dev`), connect or spectate a wallet, and open `http://localhost:8080/earn`. The Markets tab lists USDC, USDT0 and USDH pools and the HYPE and UBTC collateral rows with live rates. Clicking Supply on USDC opens the modal; typing 10000000 shows utilization and supply APY falling. The deterministic proof is the unit suite (`npm test`), which covers the rate model against a captured live reserve, the health maths against the worked example below, every action's validation, and the effect interpreters with stubbed network calls.

This is feature work. It adds three info requests on the Earn route and makes no performance claims.

## Context References

Public refs:

- Direct user request, 2026-10-20: "the designs look good. Can you create an implementation plan? And build it out." This followed a lending brief ("HyperCore Lending Brief", 2026-10-20) and an approved four-artboard design canvas: an Earn markets page, a Supply modal with a pool-rate preview, a Borrow/Repay modal with a health preview, and an Earn tab in the trade page's account panel.

Repo artifacts:

- `/hyperopen/src/hyperopen/funding/actions.cljs` `submit-funding-repay` and `/hyperopen/src/hyperopen/funding/effects.cljs` `api-submit-funding-repay!`: the existing full-repay path this plan leaves in place.
- `/hyperopen/src/hyperopen/api/trading/agent_actions.cljs` `submit-borrow-lend!`: the existing agent-signed `borrowLend` submit, reused unchanged.
- The Staking feature (`/hyperopen/src/hyperopen/staking/**`, `/hyperopen/src/hyperopen/views/staking_view.cljs`) is the wiring precedent for a lazily loaded route with its own actions and effects.

Local scratch refs (non-authoritative):

- None.

## Progress

- [x] (2026-10-20 13:10Z) Captured live `allBorrowLendReserveStates`, `borrowLendReserveState`, an empty `borrowLendUserState`, and `spotMeta` token names from mainnet; confirmed the rate model reproduces the live rates exactly.
- [x] (2026-10-20 13:40Z) Milestone 1: pure model (`borrow_lend/model.cljs`), anti-corruption normalizer (`borrow_lend/normalize.cljs`), info endpoints through every API facade layer, projections, default state, request TTLs. Tests: `model_test`, `normalize_test`.
- [x] (2026-10-20 14:20Z) Milestone 1 wiring: actions (`borrow_lend/actions.cljs`), selectors (`borrow_lend/account.cljs`), effects (`borrow_lend/effects.cljs`), runtime adapter, registration rows, collaborators, action/effect arg contracts, effect-order policies in CLJS and Lean, regenerated formal vectors. `npm test`: 7243 tests, 0 failures.
- [ ] Milestone 2: `/earn` route, lazy module, header nav entry, route refresh hooks, Earn page view model and view (markets, positions, history, health, rate curve).
- [ ] Milestone 3: Earn modal (supply / withdraw / borrow / repay) with previews and submit.
- [ ] Milestone 4: real Earn Balance on the portfolio; Earn tab in the trade account panel linking into the modal.
- [ ] Tests for actions, effects, view model; `npm run check`, `npm test`, `npm run test:websocket`; browser QA at desktop and phone widths.

## Surprises & Discoveries

- Observation: the documented rate model is exact, not approximate. With utilization 0.800022346 the live USDC reserve reports borrow 0.0501061434 and supply 0.0360774309; `0.05 + 4.75 * max(0, u - 0.8)` and `0.9 * u * r_b` give the same values to nine digits.
  Evidence: `test/hyperopen/borrow_lend/model_test.cljs` `rate-curve-matches-live-reserve-test`.
- Observation: there are five reserves on mainnet, not four: token 0 USDC, 150 HYPE, 197 UBTC, 268 USDT0, 360 USDH. Collateral is identified by `ltv > 0` (HYPE 0.65, UBTC 0.5); stablecoins report `ltv 0`.
- Observation: reserve state carries no caps. The brief's supply and borrow caps are not available from the API, so the UI shows available liquidity (`balance`) instead of cap headroom.
- Observation: `tokenToState` entries are `[tokenIndex, {"supply": {"basis", "value"}, "borrow": {"basis", "value"}}]`. `value` includes accrued interest; `basis` is principal. `healthFactor` is a string or null and `health` a label such as `"healthy"`. Shape taken from the `df-hyperliquid-core` crate; only the empty response was captured live.
- Observation: the account's raw abstraction is already stored at `[:account :abstraction-raw]`, and `hyperopen.domain.trading.core/portfolio-margin-abstraction?` already recognizes portfolio margin. Splitting `:unified` into a third mode was unnecessary for gating manual borrow.

## Decision Log

- Decision: name the code `borrow-lend` (protocol language) and the user-facing route and label "Earn".
  Rationale: the architecture rules require ubiquitous language aligned with the protocol; "Earn" is what users and the Hyperliquid app call it.
  Date/Author: 2026-10-20 / Claude.
- Decision: compute health factor and liquidation prices client-side from reserves and positions, and keep the API's `healthFactor` only as a stored value.
  Rationale: the preview needs the same maths for hypothetical borrows, and the API value's exact definition is undocumented. One formula serves both current and previewed health.
  Date/Author: 2026-10-20 / Claude.
- Decision: Earn reads and submits for the effective account (spectated trader, else selected subaccount, else wallet); submits route a subaccount through `:vault-address`, as the existing repay does.
  Rationale: matches how every other account surface behaves and the existing `api-submit-funding-repay!`.
  Date/Author: 2026-10-20 / Claude.
- Decision: "Max" on withdraw and repay sends `amount: null`.
  Rationale: the exchange treats null as the full position, so interest that accrues between render and signing does not leave dust.
  Date/Author: 2026-10-20 / Claude.
- Decision: the Earn modal renders only on `/earn`; the account panel's Earn tab navigates to `/earn` and opens the modal there.
  Rationale: keeps the modal inside the lazily loaded route module instead of growing the main bundle.
  Date/Author: 2026-10-20 / Claude.
- Decision: the Supply modal does not warn that supplied funds stop counting as trading margin.
  Rationale: the docs do not say so and it has not been verified on testnet. The design's warning is left out until verified rather than shipped as a guess.
  Date/Author: 2026-10-20 / Claude.

## Outcomes & Retrospective

Not yet complete. To be written at completion, including whether complexity rose or fell.

## Context and Orientation

Hyperopen is a ClojureScript single-page app built with shadow-cljs. State lives in one atom. Views are pure functions from state to hiccup (vectors describing HTML). User interactions dispatch named actions such as `[:actions/open-borrow-lend-modal :supply 0]`. An action is a pure function `(fn [state & args] -> [effects])` returning effect vectors such as `[:effects/save path value]` (write state) or `[:effects/api-fetch-borrow-lend-reserves]` (network). Effects are interpreted by handler functions that receive the store atom.

Every action id and effect id is registered in several places, and contract tests fail if one is missing. For this feature they are: `/hyperopen/src/hyperopen/schema/runtime_registration/borrow_lend.cljs` (binding rows mapping ids to handler keys), `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs` (collects all rows), `/hyperopen/src/hyperopen/runtime/collaborators/borrow_lend.cljs` (handler key to action function), `/hyperopen/src/hyperopen/runtime/collaborators.cljs` (action groups and the base effect handler map), `/hyperopen/src/hyperopen/runtime/effect_adapters/borrow_lend.cljs` (effect handlers that inject real API and projection functions), and `/hyperopen/src/hyperopen/schema/contracts/action_args.cljs` and `effect_args.cljs` (argument specs). Actions that emit network effects also need an effect-order policy in `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`, which asserts that state writes come before network effects. That policy table is mirrored in a Lean formal model, `/hyperopen/spec/lean/Hyperopen/Formal/EffectOrderContract.lean`; after editing it, `bb tools/formal.clj sync --surface effect-order-contract` regenerates `/hyperopen/test/hyperopen/formal/effect_order_contract_vectors.cljs`.

Info requests go through layers: endpoint (`/hyperopen/src/hyperopen/api/endpoints/account/borrow_lend.cljs`), `api/endpoints/account.cljs`, `api/gateway/account.cljs`, `api/default/account.cljs`, and `api/default.cljs`, which effect adapters call. Responses are translated once, in `/hyperopen/src/hyperopen/borrow_lend/normalize.cljs`, from string decimals keyed by token index into maps of numbers. Projections in `/hyperopen/src/hyperopen/api/projections/borrow_lend.cljs` write them under `[:borrow-lend ...]`, with loading, error and loaded-at flags per resource.

The lending maths lives in `/hyperopen/src/hyperopen/borrow_lend/model.cljs` and is pure. Terms: utilization `u` is total borrowed divided by total supplied. Borrow APY is `0.05 + 4.75 * max(0, u - 0.8)`, so it is flat at 5% up to the 80% "kink" and steep after it. Supply APY is `0.9 * u * borrow APY`; 10% is kept as a reserve. LTV (loan-to-value) is the fraction of a collateral's value that can be borrowed against (HYPE 0.65, UBTC 0.5). Borrow limit is the sum of collateral value times LTV. Health factor is borrow limit divided by borrowed value; at or below 100% no more can be borrowed. The partial-liquidation threshold for a collateral is `(1 + LTV) / 2`; liquidation begins when the borrow exceeds collateral value times that threshold. Worked example used throughout the tests: 100 HYPE at $40 with 2,000 USDC borrowed gives a $2,600 limit, $600 more borrowable, a health factor of 130%, borrowing stopping at HYPE $30.77, and partial liquidation at HYPE $24.24.

Read-side selectors joining state and the model are in `/hyperopen/src/hyperopen/borrow_lend/account.cljs`. Actions are in `/hyperopen/src/hyperopen/borrow_lend/actions.cljs`; all submit validation happens in `submit-borrow-lend` there. Effects are in `/hyperopen/src/hyperopen/borrow_lend/effects.cljs`; user-scoped fetches discard responses that arrive after the shown account changed.

Account modes: `[:account :mode]` is `:unified` or `:classic`, and portfolio-margin accounts are reported as `:unified` with `[:account :abstraction-raw]` equal to `"portfolioMargin"`. Portfolio-margin accounts borrow automatically and the exchange rejects a manual borrow from them.

## Plan of Work

Milestone 1 (done) built the data layer and runtime wiring described above. At its end, dispatching `[:actions/load-borrow-lend]` fetches reserves, the account's user state and 30 days of interest into `[:borrow-lend ...]`, and `[:actions/submit-borrow-lend]` validates the modal and signs a `borrowLend` action.

Milestone 2 adds the page. `/hyperopen/src/hyperopen/borrow_lend/actions.cljs` already defines `earn-route?`. Register the route in `/hyperopen/src/hyperopen/route_modules.cljs` (module id `:earn`, module name `"earn_route"`, export path `hyperopen.views.earn_view.route_view`), add the `:earn_route` module to both builds in `/hyperopen/shadow-cljs.edn`, call `load-borrow-lend-route` from `route-loader-effects` in `/hyperopen/src/hyperopen/runtime/action_adapters/navigation.cljs`, from `current-route-refresh-effects` in `/hyperopen/src/hyperopen/startup/route_refresh.cljs`, and after wallet connect in `/hyperopen/src/hyperopen/runtime/action_adapters/wallet.cljs`. Add a header nav entry between Vaults and Staking in `/hyperopen/src/hyperopen/views/header/nav.cljs` with a voice label in `/hyperopen/src/hyperopen/ui/voice.cljs`, and update the release metadata in `/hyperopen/tools/release-assets/site_metadata.mjs`. Build `/hyperopen/src/hyperopen/views/earn/vm.cljs` (pure view model: rows with formatted figures, health, rate-curve points) and `/hyperopen/src/hyperopen/views/earn_view.cljs` (page) following the canvas.

Milestone 3 adds `/hyperopen/src/hyperopen/views/earn/modal.cljs`, rendered by the Earn page when `[:borrow-lend-ui :modal :open?]`. It has Supply/Withdraw or Borrow/Repay tabs, the amount field (`:actions/set-borrow-lend-amount` with `:event.target/value`), 25/50/75/Max presets (`:actions/set-borrow-lend-amount-fraction`), a preview from `model/supply-preview` or `model/health-after`, inline errors from `[:borrow-lend-ui :modal :error]`, and a submit button dispatching `:actions/submit-borrow-lend`.

Milestone 4 makes `hyperopen.views.portfolio.vm.equity/earn-balance` use `hyperopen.borrow-lend.account/total-supplied-usd`, loads Earn data on the portfolio route, and adds an Earn tab to the account panel whose actions navigate to `/earn` and open the modal.

## Concrete Steps

All commands run from the repository root of the worktree, `/Users/barry/projects/hyperopen/.claude/worktrees/hypercore-lending-earn`.

    npm run setup:worktree
    npm test
    # expect: Ran N tests ... 0 failures, 0 errors.
    bb tools/formal.clj sync --surface effect-order-contract
    # after any edit to the effect-order policies (CLJS and Lean together)
    npm run check
    npm run test:websocket

To capture a reserve snapshot for a fixture:

    curl -s https://api.hyperliquid.xyz/info -H 'content-type: application/json' -d '{"type":"allBorrowLendReserveStates"}'

## Validation and Acceptance

Run `npm test`; all tests pass, including `hyperopen.borrow-lend.model-test`, `normalize-test`, `actions-test` and `effects-test`. `rate-curve-matches-live-reserve-test` proves the rate model; `brief-worked-example-test` proves the health maths ($2,600 limit, 130%, $30.77, $24.24).

With the dev server running, `/earn` lists live pools; spectating an address with no lending positions shows the empty positions state; a portfolio-margin account sees the automatic-borrowing notice and disabled Borrow buttons; the Supply modal's preview moves as the amount changes; an amount above the maximum shows an inline error and does not sign.

## Idempotence and Recovery

Every step is additive. Re-running `formal.clj sync` is safe. If the registration contract tests fail, the message names the missing id; add it to the file named in Context and Orientation.

## Artifacts and Notes

Live reserve (mainnet, 2026-10-20), abbreviated:

    [[0,{"borrowYearlyRate":"0.0501061434","supplyYearlyRate":"0.0360774309","balance":"108264162.22","utilization":"0.800022346","oraclePx":"1.0","ltv":"0.0","totalSupplied":"541380906.14","totalBorrowed":"433116822.60"}],
     [150,{"utilization":"0.0","oraclePx":"79.35","ltv":"0.65",...}], [197,...UBTC...], [268,...USDT0...], [360,...USDH...]]

## Interfaces and Dependencies

`hyperopen.borrow-lend.model`: `borrow-rate [u]`, `supply-rate [u] [u rb]`, `utilization [supplied borrowed]`, `supply-preview [reserve operation amount] -> {:utilization :borrow-apy :supply-apy}`, `collateral-rows [positions reserves-by-token]`, `borrowed-usd`, `health [collateral borrowed-usd] -> {:borrow-limit-usd :borrowed-usd :available-to-borrow-usd :limit-used :health-factor :liquidatable?}`, `liquidation-prices [collateral debt-usd] -> {token {:borrow-stops-px :liquidation-px}}`, `health-after [collateral borrowed-usd delta-usd]`, `health-status`, `net-apy`.

`hyperopen.borrow-lend.normalize`: `normalize-reserve-states`, `normalize-user-state -> {:positions [{:token :supplied :supplied-basis :borrowed :borrowed-basis}] :health :health-factor}`, `normalize-interest-rows`, `interest-totals`.

State: `[:borrow-lend]` holds `:reserves`, `:user-state`, `:user-address`, `:interest`, `:interest-address`, `:loading`, `:errors`, `:loaded-at-ms`. `[:borrow-lend-ui]` holds `:active-tab` and `:modal {:open? :kind :token :amount :max? :submitting? :error}`.

Exchange action: `{:type "borrowLend" :operation "supply"|"withdraw"|"borrow"|"repay" :token <spot index> :amount <decimal string or nil>}`, signed as an L1 action by the agent session via `hyperopen.api.trading/submit-borrow-lend!`.
