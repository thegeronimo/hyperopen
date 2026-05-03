# Outcome No Market Order Book Subscription Fix

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Tracked issue: `hyperopen-agty` ("Implement outcome markets").

## Purpose / Big Picture

Outcome traders should be able to buy either side of a binary market. Today the order ticket can render `Buy No`, but market submission can stay disabled with `Load order book data before placing a market order.` The user-visible goal is that, after opening an outcome market such as the BTC recurring market and selecting `Buy No`, Hyperopen has the No-side order book loaded, derives the market IOC price from that No book, and submits an order for the No side asset id.

The failure is not that Hyperliquid lacks a No order book. Hyperliquid represents each outcome side as its own spot-like coin. For outcome `1`, Yes is `#10` and No is `#11`; both have independent `l2Book` data. Hyperopen already models this in most pure trading paths, but the startup and subscription orchestration can leave only the Yes side subscribed.

## Progress

- [x] (2026-05-03 13:10Z) Traced the visible tooltip to `src/hyperopen/views/trade/order_form_vm_submit.cljs` and the submit gate to `src/hyperopen/trading/submit_policy.cljs`.
- [x] (2026-05-03 13:20Z) Verified from official Hyperliquid docs and live `/info` API calls that outcome sides have separate coins and separate books.
- [x] (2026-05-03 13:30Z) Identified that `src/hyperopen/runtime/action_adapters/websocket.cljs` resolves outcome side subscriptions only through `[:asset-selector :market-by-key]` and ignores a cached `:active-market`.
- [x] (2026-05-03 13:35Z) Identified that startup restores selector markets asynchronously and initializes remote streams without waiting for that cache, so `:market-by-key` can be empty when `subscribe-to-asset` first runs.
- [x] (2026-05-03 14:05Z) Added a failing action-adapter regression proving restored outcome active-market metadata must subscribe both `#10` and `#11` when selector cache is empty.
- [x] (2026-05-03 14:12Z) Implemented the active-market fallback in `subscribe-to-asset`; `npm test` passed after the fix.
- [x] (2026-05-03 14:20Z) Added a failing duplicate-trade-subscription regression and made `subscribe-trades!` idempotent before adding hydration resyncs.
- [x] (2026-05-03 14:45Z) Added runtime and startup asset-selector hydration resync coverage, then wired both success paths to reconcile active outcome side order book, trade, and active-asset-context streams.
- [x] (2026-05-03 15:05Z) Added submit-policy coverage proving `Buy No` market orders use the No-side `#11` ask and still fail closed when only the Yes book is loaded.
- [x] (2026-05-03 15:35Z) Ran required repository gates and focused Playwright order-form validation.

## Surprises & Discoveries

- Observation: The existing submit policy is already form-aware for outcome sides. When `:outcome-side` is `1`, `trading-context` selects the No-side coin and reads `[:orderbooks "#11"]` for outcome `1`.
  Evidence: `src/hyperopen/state/trading.cljs` selects `outcome-side` from the normalized form and uses `(:coin outcome-side)` as the trading-context `:active-asset`.

- Observation: Hyperliquid has separate live books for the two side coins.
  Evidence: `POST https://api.hyperliquid.xyz/info {"type":"outcomeMeta"}` returned outcome `1` with Yes/No side specs. `POST /info {"type":"l2Book","coin":"#10"}` returned a top bid/ask around `0.61027/0.6112`; `POST /info {"type":"l2Book","coin":"#11"}` returned a top bid/ask around `0.3888/0.38973`.

- Observation: The completed outcome-markets plan already recorded Hyperliquid frontend parity: the canonical frontend subscribes to both side books for the selected outcome question.
  Evidence: `docs/exec-plans/completed/2026-05-02-outcome-markets.md` records websocket subscriptions for `l2Book #0`, `l2Book #1`, `activeAssetCtx #0`, and `activeAssetCtx #1`.

- Observation: The local startup path can miss the second side. `restore-asset-selector-markets-cache!` is asynchronous and is not awaited before `initialize-remote-data-streams!` dispatches `[:actions/subscribe-to-asset asset]`.
  Evidence: `src/hyperopen/startup/init.cljs` calls `restore-asset-selector-markets-cache!` during critical UI restore, then later initializes remote streams; the cache restore returns a promise but the restore pipeline does not wait on it.

## Decision Log

- Decision: Treat this as a local subscription and hydration bug, not as a market-order price-formatting bug.
  Rationale: The exact disabled message appears before request construction because `prepare-order-form-for-submit` cannot derive a market price from the selected side order book. The selected No book is absent from app state, while Hyperliquid's API returns a valid No book.
  Date/Author: 2026-05-03 / Codex

- Decision: Preserve the current form-aware submit model.
  Rationale: `src/hyperopen/state/trading.cljs` and `test/hyperopen/state/trading/order_request_test.cljs` already prove that `:outcome-side 1` resolves to asset id `100000001` for outcome `0`. The bug is upstream of submit request construction.
  Date/Author: 2026-05-03 / Codex

- Decision: Make stream subscription reconciliation idempotent before broadening resyncs after market hydration.
  Rationale: Re-subscribing order books is already mostly idempotent, but trade subscriptions currently send a new websocket subscribe every call. A market-hydration resync must not create duplicate live trade feeds.
  Date/Author: 2026-05-03 / Codex

## Outcomes & Retrospective

Implemented the fix in two layers. First, `subscribe-to-asset` now uses a matching restored `:active-market` as the source of outcome sides when `[:asset-selector :market-by-key]` is still empty. Second, asset-selector market hydration now reconciles the active outcome side streams after metadata arrives, covering direct-load and cache-miss startup paths.

The change increases behavior at the websocket/effect boundary but keeps submit policy pure and form-aware. Trade subscriptions are now idempotent, which makes repeated hydration reconciliation safe instead of creating duplicate live trade subscriptions.

Tests failed before the relevant fixes:

- `subscribe-to-asset-uses-restored-active-outcome-market-when-selector-cache-is-empty-test` emitted only `#10`; after the active-market fallback it emits both `#10` and `#11`.
- `subscribe-trades-sends-one-subscription-per-symbol-test` observed duplicate websocket subscribe messages; after the idempotence guard repeated calls send one message.
- Asset-selector hydration tests observed no active outcome side subscriptions after market metadata success; after wiring the resync helper, order book, trades, and active asset context state contain both side coins.

Final validation:

- `npm run check` passed after keeping `src/hyperopen/runtime/effect_adapters.cljs` within the namespace-size threshold.
- `npm test` passed with `3745` tests and `20696` assertions.
- `npm run test:websocket` passed with `524` tests and `3043` assertions.
- `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "order submit and cancel gating uses simulator-backed assertions"` passed.
- `npm run browser:cleanup` returned `ok: true` with no tracked browser-inspection sessions left running.

## Context and Orientation

Outcome markets are prediction-style binary markets. Hyperliquid encodes an outcome side as `encoding = 10 * outcome + side`; the displayed order book coin is `#<encoding>` and the order asset id is `100000000 + encoding`. For outcome `1`, Yes is `#10` with asset id `100000010`; No is `#11` with asset id `100000011`.

The order ticket view model is in `src/hyperopen/views/trade/order_form_vm.cljs`. It passes submit state through `src/hyperopen/views/trade/order_form_vm_submit.cljs`, which renders the visible `Load order book data before placing a market order.` tooltip when `:market-price-missing?` is true.

The submit policy is in `src/hyperopen/trading/submit_policy.cljs`. For market orders it calls `hyperopen.domain.trading/apply-market-price`, which uses the best ask for buys and the best bid for sells. If the selected side book is missing, market price preparation returns nil and the policy disables submit.

Outcome side selection is handled correctly in `src/hyperopen/state/trading.cljs`. The private `trading-context` function chooses the selected outcome side from the form and reads `[:orderbooks selected-side-coin]`. That means `Buy No` should read the No coin book, not the Yes coin book.

The broken path is subscription orchestration. `src/hyperopen/runtime/action_adapters/websocket.cljs` computes side book subscriptions for `[:actions/subscribe-to-asset coin]`, but it resolves an outcome market only from `[:asset-selector :market-by-key]`. During startup or direct route entry, `:active-market` may already contain the outcome sides from the active-market display cache, while `:market-by-key` is still empty. In that state the adapter emits only `[:effects/subscribe-orderbook "#10"]` and never emits `[:effects/subscribe-orderbook "#11"]`.

There is a second startup gap when neither active-market cache nor selector cache is available yet. Startup fetches selector markets later and updates `:active-market`, but the startup collaborator path does not resubscribe side books after market hydration. The implementation must close both gaps.

## Plan of Work

First, add tests that lock down the failure at the pure action-adapter boundary. In `test/hyperopen/runtime/action_adapters/websocket_test.cljs`, add a test where state has an outcome `:active-market` with `:outcome-sides [{:coin "#10"} {:coin "#11"}]` but `[:asset-selector :market-by-key]` is empty. Calling `subscribe-to-asset` with `"#10"` must emit order book and trade subscriptions for both `#10` and `#11`. This test should fail before the fix because the adapter emits only `#10`.

Second, update `src/hyperopen/runtime/action_adapters/websocket.cljs`. Change `active-market-side-coins` so it first uses `(:active-market state)` when that market matches the requested coin through `markets/market-matches-coin?`, then falls back to `markets/resolve-or-infer-market-by-coin` from `market-by-key`. Keep the output stable and distinct. This is the smallest fix for restored outcome routes with cached active-market metadata.

Third, make trade subscription idempotent. In `src/hyperopen/websocket/trades.cljs`, change `subscribe-trades!` so it checks `(:subscriptions @trades-state)` before sending a websocket subscribe. If the symbol is already present, log an unchanged subscription and do not send another message. Add or update `test/hyperopen/websocket/trades_test.cljs` to prove repeated `subscribe-trades!` calls send one websocket subscribe.

Fourth, add a hydration resync helper for active outcome markets. Prefer a small function in `src/hyperopen/runtime/effect_adapters/websocket.cljs` or a focused adjacent namespace that can compute the active market side coins from current state and subscribe missing side books, trades, and active asset contexts after market metadata arrives. Wire that helper into both asset-selector market success paths:

- `src/hyperopen/runtime/effect_adapters.cljs`, inside `fetch-asset-selector-markets-effect` after `sync-asset-selector-active-ctx-subscriptions`.
- `src/hyperopen/startup/collaborators.cljs`, inside the startup `fetch-asset-selector-markets!` path through `runtime-api-effects/fetch-asset-selector-markets!` with an `after-asset-selector-success!` callback.

This closes the first-load deep-link case where no active-market cache exists before the first subscription action.

Fifth, add a submit-policy regression that proves the end-to-end state shape works. In `test/hyperopen/state/trading/identity_and_submit_policy_test.cljs`, add a case for outcome `1`, `:outcome-side 1`, `:type :market`, `:side :buy`, and `:orderbooks {"#11" {:asks [{:px "0.39"}] :bids [{:px "0.38"}]}}`. The policy should not report `:market-price-missing` and the prepared form should contain a market price derived from the No ask. Also add the inverse missing-book assertion if it is not already covered: with only `#10` loaded and `:outcome-side 1`, submit must still report `:market-price-missing`.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/2d0a/hyperopen`.

1. Edit `test/hyperopen/runtime/action_adapters/websocket_test.cljs` and add the active-market fallback regression.
2. Run `npm test -- --test=hyperopen.runtime.action-adapters.websocket-test`. The repository runner may print that targeted args are unsupported and run the full suite; if so, record that and continue.
3. Edit `src/hyperopen/runtime/action_adapters/websocket.cljs` to prefer matching `:active-market`.
4. Edit `test/hyperopen/websocket/trades_test.cljs` and `src/hyperopen/websocket/trades.cljs` to make trade subscribe idempotent.
5. Add the market-hydration resync helper and wire it into runtime and startup asset-selector success callbacks.
6. Add the submit-policy outcome No market-order test in `test/hyperopen/state/trading/identity_and_submit_policy_test.cljs`.
7. Run focused checks for the touched namespaces where possible.
8. Run the required gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
9. Because this affects a browser order-flow surface, run the smallest stable Playwright or browser-inspection check that exercises an outcome route, then clean up with `npm run browser:cleanup`.

## Validation and Acceptance

The fix is accepted when a restored or direct-linked outcome route subscribes both side books. For outcome `1`, selecting or restoring `#10` must result in active book subscriptions for both `#10` and `#11`. Selecting `Buy No` on a market order must use the `#11` ask to prepare the IOC market price and must not show `Load order book data before placing a market order.` once the `#11` book has arrived.

Regression tests must prove:

- `subscribe-to-asset` emits both side book and trade effects when `:active-market` contains outcome sides but `market-by-key` is empty.
- Repeated `subscribe-trades!` calls for the same symbol send only one websocket subscribe.
- Asset-selector market hydration reconciles active outcome side streams after metadata arrives.
- Submit policy for `Buy No` no longer reports `:market-price-missing` when `#11` is loaded, and still fails closed when the selected side book is absent.

Required gates must pass: `npm run check`, `npm test`, and `npm run test:websocket`.

## Idempotence and Recovery

All planned changes are additive or narrowly behavioral. Re-running the subscription resync after market hydration must be safe; order book subscription is already desired-subscription based, and this plan makes trade subscription idempotent before adding broader resyncs. If browser validation reveals duplicate websocket traffic, first inspect `trades/get-subscriptions`, `orderbook/get-subscriptions`, and `active-asset-ctx` owner state before changing submit policy.

No data migration or destructive operation is required. If the hydration-resync helper causes unexpected side effects, keep the active-market fallback fix and disable only the after-success resync while preserving the failing test as a guide for the next pass.

## Artifacts and Notes

Official Hyperliquid docs used in this investigation:

- `https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/asset-ids`: outcome side encoding is `10 * outcome + side`; outcome spot coin is `#<encoding>`; asset id is `100000000 + encoding`.
- `https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/websocket/subscriptions`: `l2Book` subscriptions use a `coin` string and `WsBook` includes the subscribed `coin`.

Live API samples from 2026-05-03:

    POST https://api.hyperliquid.xyz/info
    {"type":"outcomeMeta"}
    => outcome 1, sideSpecs Yes/No, description class:priceBinary|underlying:BTC|expiry:20260504-0600|targetPrice:78213|period:1d

    POST https://api.hyperliquid.xyz/info
    {"type":"l2Book","coin":"#10"}
    => coin "#10", top bid 0.61027, top ask 0.6112

    POST https://api.hyperliquid.xyz/info
    {"type":"l2Book","coin":"#11"}
    => coin "#11", top bid 0.3888, top ask 0.38973

Relevant prior repo evidence:

- `docs/exec-plans/completed/2026-05-02-outcome-markets.md` records that Hyperliquid's frontend subscribes to both Yes and No side books for the active outcome route.
- `test/hyperopen/asset_selector/actions_test.cljs` already covers selecting an outcome from hydrated selector state and subscribing both side books.
- `test/hyperopen/state/trading/order_request_test.cljs` already covers selected outcome side asset id resolution for limit orders.

## Interfaces and Dependencies

No new third-party dependency is required.

The implementation should preserve these existing interfaces:

    hyperopen.runtime.action-adapters.websocket/subscribe-to-asset
    [state coin] -> vector of runtime effects

    hyperopen.websocket.subscriptions-runtime/active-market-subscription-coins
    [market canonical-coin] -> vector of side coins for activeAssetCtx subscriptions

    hyperopen.trading.submit-policy/submit-policy
    [submit-context normalized-form options] -> submit policy map

If a new helper is introduced, keep it internal to the runtime/effect adapter boundary and make it take explicit dependencies for side effects in tests. Do not move websocket calls into pure state or view namespaces.

Plan revision note: 2026-05-03 13:40Z - Initial plan created after tracing the Buy No market-order disabled state to missing No-side book subscriptions and confirming Hyperliquid exposes separate Yes/No `l2Book` feeds.
