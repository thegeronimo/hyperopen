---
owner: trading-ui
status: active
created: 2026-05-02
source_of_truth: false
tracked_issue: hyperopen-4lja
---

# Outcome Markets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. Keep this ExecPlan current as work proceeds.

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while work proceeds. Maintain this file according to `.agents/PLANS.md`.

Tracked issue: `hyperopen-4lja` ("Implement outcome markets").

## Objective

Implement Hyperliquid HIP-4 outcome markets in Hyperopen so a user can open the asset selector, see an `Outcome` section, select a recurring BTC outcome such as `BTC above 78213 on May 3 at 2:00 AM?`, view the selected market header with countdown, chance, price, 24h change, 24h volume, open interest, and a details popover, and place Yes or No orders using the correct Hyperliquid outcome asset ids.

Outcome markets are binary markets. A binary market has a question, two sides named `Yes` and `No`, and a settlement time. On settlement the winning side pays `1 USDH` and the losing side pays `0`. Hyperliquid represents the two sides as spot-like order book coins such as `#0` and `#1`, but the application must treat the market as one question with two tradeable sides.

## Evidence

Official Hyperliquid docs:

- Asset id encoding: `https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/asset-ids`. The docs state that outcome encoding is `10 * outcome + side`, valid sides are `0` and `1`, the spot coin is `#<encoding>`, the token name is `+<encoding>`, and the exchange asset id is `100000000 + encoding`.
- Outcome metadata endpoint: `https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/spot`. The docs list `POST /info` with body `{"type":"outcomeMeta"}` and response fields `outcomes` and `questions`.
- Contract specification page requested by the user: `https://hyperliquid.gitbook.io/hyperliquid-docs/trading/contract-specifications#recurring-outcomes`. The crawled page did not expose the recurring-outcomes anchor content on 2026-05-02, so the implementation should rely on live API shape plus the two official API docs above.
- Recurring outcome descriptions use pipe-separated key/value fields. The observed BTC recurring market uses `class:priceBinary|underlying:BTC|expiry:20260503-0600|targetPrice:78213|period:1d`. The parser must not assume field order, and unknown fields must be preserved under an `:extra-fields` map so new Hyperliquid outcome classes can be inspected without losing raw metadata.

Live Hyperliquid frontend inspection on 2026-05-02:

- Route inspected: `https://app.hyperliquid.xyz/trade/btc-above-78213-yes-may-03-0600`.
- Frontend bundle: `https://app.hyperliquid.xyz/static/js/main.5a06e996.js`.
- Browser artifact: `/tmp/hyperliquid-outcome-details.png`.
- Network artifact: `/tmp/hyperliquid-network-ws.json`.
- The frontend calls `https://api-ui.hyperliquid.xyz/info` with `{"type":"outcomeMeta"}`, `{"type":"spotMeta"}`, `{"type":"allPerpMetas"}`, `{"type":"perpDexs"}`, user fee requests, and candle snapshots for `#0`.
- The inspected route used websocket `trades` for selected-side trade flow. The implementation should still investigate whether Hyperopen has an initial REST trade-history bootstrap path that should call `recentTrades` for `#` outcome coins before live `trades` frames arrive.
- The frontend opens `wss://api-ui.hyperliquid.xyz/ws`, first subscribes default spot `@107`, then after resolving the outcome route unsubscribes default market subscriptions and subscribes:
  - `{"type":"trades","coin":"#0"}`
  - `{"type":"l2Book","coin":"#0","nSigFigs":null}`
  - `{"type":"l2Book","coin":"#1","nSigFigs":null}`
  - `{"type":"activeAssetCtx","coin":"#0"}`
  - `{"type":"activeAssetCtx","coin":"#1"}`
  - `{"type":"allMids","dex":"ALL_DEXS"}`
  - `{"type":"spotAssetCtxs"}`
  - `{"type":"outcomeMetaUpdates"}`
- Although the subscription request is `activeAssetCtx`, the server response for an outcome side uses channel `activeSpotAssetCtx` with shape `{"channel":"activeSpotAssetCtx","data":{"coin":"#0","ctx":{...}}}`.
- The selected market details button opens copy: `BTC above 78213 on May 3 at 2:00 AM?` and `If the BTC mark price at time of settlement is above 78213 at May 03, 2026 06:00 UTC, YES tokens pay out $1 each. Otherwise, NO tokens pay out $1 each.`

Live API sample on 2026-05-02:

    POST https://api.hyperliquid.xyz/info
    {"type":"outcomeMeta"}

    {
      "outcomes": [
        {
          "outcome": 0,
          "name": "Recurring",
          "description": "class:priceBinary|underlying:BTC|expiry:20260503-0600|targetPrice:78213|period:1d",
          "sideSpecs": [{"name":"Yes"},{"name":"No"}]
        }
      ],
      "questions": []
    }

For this sample, `outcome = 0`. The Yes side has `encoding = 10 * 0 + 0 = 0`, coin `#0`, token name `+0`, asset id `100000000`. The No side has `encoding = 1`, coin `#1`, token name `+1`, asset id `100000001`.

`webData2` contains outcome side contexts in `spotAssetCtxs`, not in `spotMeta.universe`:

    {"coin":"#0","markPx":"0.65012","midPx":"0.65006","prevDayPx":"0.55","dayNtlVlm":"415908.3249700002","circulatingSupply":"204692.0","dayBaseVlm":"683834.0"}
    {"coin":"#1","markPx":"0.34988","midPx":"0.34994","prevDayPx":"0.45","dayNtlVlm":"267925.6750300001","circulatingSupply":"204692.0","dayBaseVlm":"683834.0"}

The canonical Hyperliquid UI renders one selector row per outcome question, using the Yes side as the headline side. It shows `% Chance` from the Yes mark price times 100, `Volume` from the Yes side 24h notional volume, and `Open Interest` near the shared side circulating supply in USDH. On the trade route it renders both Yes and No books and exposes `Buy Yes` / `Buy No` choices.

## Progress

- [x] (2026-05-02 15:20Z) Created tracked issue `hyperopen-4lja`.
- [x] (2026-05-02 15:20Z) Inspected official Hyperliquid docs for `outcomeMeta` and outcome asset id encoding.
- [x] (2026-05-02 15:20Z) Inspected live Hyperliquid frontend route, details popover text, REST calls, websocket subscriptions, and selected-market display.
- [x] (2026-05-02 15:20Z) Mapped Hyperopen repository touchpoints for market hydration, asset selector rendering, active asset rendering, websocket active asset contexts, and order submission gates.
- [x] (2026-05-02 17:30Z) Implemented outcome metadata request, normalization, recurring description parsing, asset id helpers, and market resolution for `#0`, `#1`, and `outcome:<id>`.
- [x] (2026-05-02 17:30Z) Added `Outcome` selector tab, period subtabs, row rendering, cache round-trip support, and active-market cache support.
- [x] (2026-05-02 17:30Z) Added active outcome header metrics and Details content.
- [x] (2026-05-02 17:30Z) Added active outcome websocket handling for both side coins and `activeSpotAssetCtx` frames.
- [x] (2026-05-02 17:30Z) Added outcome order form side selection and form-aware exchange asset id handling for Yes/No orders.
- [x] (2026-05-02 17:30Z) Ran deterministic unit/websocket gates and browser smoke verification.

## Surprises & Discoveries

- Observation: The official spot info docs still label `outcomeMeta` as testnet-only in one heading, but mainnet returned a valid BTC recurring outcome on 2026-05-02.
  Evidence: `POST https://api.hyperliquid.xyz/info {"type":"outcomeMeta"}` returned outcome `0` for `BTC above 78213`.
- Observation: Outcome side contexts are absent from `spotMeta.universe`; they are present in `webData2.spotAssetCtxs`, `spotAssetCtxs` websocket payloads, `allMids`, `l2Book`, and `activeSpotAssetCtx`.
  Evidence: `spotMetaAndAssetCtxs` returned no `#0` or `#1` universe entry, while `webData2.spotAssetCtxs` returned contexts for both.
- Observation: Hyperliquid's frontend subscribes to both outcome sides for the selected question, even when the route names the Yes side.
  Evidence: websocket sent `l2Book #0`, `l2Book #1`, `activeAssetCtx #0`, and `activeAssetCtx #1`.
- Observation: Hyperopen currently treats `:spot?` identity as read-only in `src/hyperopen/trading/submit_policy.cljs`, so outcomes cannot simply reuse `:market-type :spot`.
  Evidence: `submit-policy` returns `:spot-read-only` when `(:spot? identity)` is true.
- Observation: Hyperopen's `npm test -- --focus ...` runner currently ignores `--focus` arguments and executes the full generated CLJS suite.
  Evidence: repeated focused commands printed `Unknown arg: --focus` and ran all test namespaces; the final full `npm test` covered the same path.

## Decision Log

- Decision: Model outcomes as `:market-type :outcome`, not `:spot`.
  Rationale: Hyperliquid says outcomes share implementation details with spot trading, but Hyperopen intentionally blocks spot order submission today. A separate market type lets the UI support outcome trading without accidentally enabling generic spot trading.
  Date/Author: 2026-05-02 / Codex
- Decision: Represent one selector market per outcome question, with the Yes side as the canonical `:coin` and both side coins stored under `:outcome-sides`.
  Rationale: This matches the canonical Hyperliquid selector, keeps search and favorites question-oriented, and still preserves the exact side coins needed for order book and order submission.
  Date/Author: 2026-05-02 / Codex
- Decision: Keep recurring outcome parsing deterministic and local.
  Rationale: The required display title, countdown, details text, settlement timestamp, underlying, target, and period can be derived from the `description` string. Parsing belongs in a pure normalization boundary, not in views.
  Date/Author: 2026-05-02 / Codex
- Decision: Subscribe both side coins while an outcome market is active.
  Rationale: The order form needs both books for Buy Yes and Buy No, the active header needs Yes chance, and parity with the canonical frontend depends on both sides staying live.
  Date/Author: 2026-05-02 / Codex
- Decision: Keep outcome Details content inline in the active-asset row for this pass rather than introducing new global popover state.
  Rationale: The row now exposes the title and settlement copy deterministically and is covered by Hiccup tests; a fully anchored click-to-open popover can be tightened in a smaller UI follow-up without changing the normalized market model.
  Date/Author: 2026-05-02 / Codex

## Acceptance Criteria

After implementation, a user can open the asset selector and see an `Outcome` top-level tab next to `Spot`, plus the subfilters `All`, `Crypto (15m)`, and `Crypto (1d)` when the `Outcome` tab is active. The BTC recurring market appears as one row titled `BTC above 78213 on May 3 at 2:00 AM?`, with `% Chance`, `Volume`, and `Open Interest` columns matching the live Yes-side context within normal market movement.

Selecting the BTC outcome closes the selector immediately, updates the route and active header before heavy subscription effects, and does not emit duplicate fetch or subscription effects. The active header shows `Countdown`, `% Chance`, `Price (Yes)`, `24h Change`, `24h Volume`, and `Open Interest`. The `Details` button opens a popover with the question title and the settlement rule copy.

The trade route subscribes to live order books and active asset contexts for both Yes and No side coins. The order book and order form expose Yes and No choices without requiring the user to select raw `#0` or `#1` assets from the selector.

Submitting an outcome order uses asset id `100000000 + (10 * outcome + side)`. Generic spot markets remain read-only unless a separate future plan explicitly enables spot trading.

The implementation passes focused unit tests, focused Playwright coverage for the selector and details popover, and the required gates: `npm run check`, `npm test`, and `npm run test:websocket`.

## Context and Orientation

Hyperopen builds market rows in `src/hyperopen/asset_selector/markets.cljs`. Perps come from `metaAndAssetCtxs`; spot markets come from `spotMeta` plus `spotAssetCtxs`. The asset selector hydration orchestration lives in `src/hyperopen/api/market_loader.cljs` and `src/hyperopen/api/endpoints/market.cljs`. Hydration results are projected into app state by `src/hyperopen/api/projections/asset_selector.cljs`.

The asset selector UI is split across `src/hyperopen/views/asset_selector/controls.cljs`, `rows.cljs`, `processing.cljs`, and `runtime.cljs`. Filtering and sorting are pure functions in `src/hyperopen/asset_selector/query.cljs`. Selector action ordering is in `src/hyperopen/asset_selector/actions.cljs`; do not break the existing rule that visible UI updates happen before unsubscribe/subscribe effects.

The selected-market header is built from `src/hyperopen/views/active_asset/vm.cljs` and rendered in `src/hyperopen/views/active_asset/row.cljs`. Funding details are currently perp-specific; outcome details should be a separate popover path rather than being forced through the funding tooltip.

Websocket active market context handling is in `src/hyperopen/websocket/active_asset_ctx.cljs`, while subscription orchestration is in `src/hyperopen/websocket/subscriptions_runtime.cljs` and runtime/effect adapter namespaces. Current code handles only `activeAssetCtx` channel responses. Outcomes require `activeSpotAssetCtx` handling and a way to subscribe both side coins for an active outcome question.

Order submission is shaped in `src/hyperopen/api/gateway/orders/commands.cljs`, selected trading context is assembled in `src/hyperopen/state/trading.cljs`, and submit gating is in `src/hyperopen/trading/submit_policy.cljs`. Outcome markets must pass through these seams with `:market-type :outcome`, correct `:asset-id`, and no `updateLeverage` pre-action.

## Plan of Work

Milestone 1 adds protocol normalization. At the end of this milestone, tests can build an outcome market from `outcomeMeta` plus `spotAssetCtxs` without rendering the UI.

Modify `src/hyperopen/api/endpoints/market.cljs`:

- Add `request-outcome-meta!` beside `request-spot-meta!`.
- Use body `{"type" "outcomeMeta"}`.
- Apply request policy key `:outcome-meta` with priority and dedupe key `:outcome-meta`.
- Extend `build-market-state` to accept `outcome-meta` and call a new pure market builder.

Modify `src/hyperopen/api/market_loader.cljs`:

- Add an `ensure-outcome-meta-data!` collaborator.
- For `:bootstrap`, use a low-risk default of `js/Promise.resolve {:outcomes [] :questions []}` unless product review decides outcomes must be visible on first paint.
- For `:full`, fetch outcome metadata in parallel with spot metadata and public `webData2`.
- Pass outcome metadata to `build-market-state`.

Modify `src/hyperopen/api/default/market.cljs`, `src/hyperopen/api/gateway/market.cljs`, and `src/hyperopen/api/instance.cljs` to thread the new request and ensure functions through existing market gateway seams. Preserve existing public API names unless a new public var is required.

Modify `src/hyperopen/asset_selector/markets.cljs`:

- Define `outcome-encoding`, `outcome-coin`, and `outcome-asset-id`:

        (def outcome-asset-id-base 100000000)

        (defn outcome-encoding [outcome side]
          (+ (* 10 outcome) side))

        (defn outcome-coin [outcome side]
          (str "#" (outcome-encoding outcome side)))

        (defn outcome-asset-id [outcome side]
          (+ outcome-asset-id-base (outcome-encoding outcome side)))

- Define `parse-outcome-description` for strings like `class:priceBinary|underlying:BTC|expiry:20260503-0600|targetPrice:78213|period:1d`. Return keywords `:outcome-class`, `:underlying`, `:expiry`, `:expiry-ms`, `:target-price`, and `:period`.
- `parse-outcome-description` must split each segment on the first `:`, trim key and value, normalize known keys, and preserve unknown keys in `:extra-fields`. It must accept fields in any order, skip empty segments, and return `:raw-description` unchanged for debugging and cache round-trip tests.
- Define `outcome-title` that returns `BTC above 78213 on May 3 at 2:00 AM?` for the sample. Use UTC expiry parsing first, then format for the current UI locale in the view layer if locale is available; keep the normalized market with a stable UTC timestamp.
- Define `outcome-details-copy` that returns the settlement rule copy using UTC: `If the BTC mark price at time of settlement is above 78213 at May 03, 2026 06:00 UTC, YES tokens pay out $1 each. Otherwise, NO tokens pay out $1 each.`
- Define `build-outcome-markets` that accepts `outcome-meta` and `spot-asset-ctxs`.
- For each outcome, create one market map with:
  - `:key` as `outcome:<outcome-id>`
  - `:coin` as the Yes side coin, for example `#0`
  - `:symbol` and `:title` as the question title
  - `:base` as the question title or underlying symbol
  - `:quote` as `USDH`
  - `:market-type` as `:outcome`
  - `:category` as `:outcome`
  - `:outcome-id`, `:outcome-class`, `:underlying`, `:expiry-ms`, `:target-price`, `:period`
  - `:outcome-sides` as two maps containing side index, side name, coin, asset id, mark, raw mark, previous day mark, 24h change, 24h change percent, 24h volume, circulating supply, and size decimals
  - headline `:mark`, `:markRaw`, `:volume24h`, `:change24h`, `:change24hPct`, and `:openInterest` from the Yes side
  - `:asset-id` as the Yes asset id, for example `100000000`
  - `:szDecimals` as `0` unless live payloads prove a different side size precision
- Update `coin->market-key`, `candidate-market-keys`, `resolve-market-by-coin`, `resolve-or-infer-market-by-coin`, `market-matches-coin?`, and `coin-aliases` so `#0`, `#1`, and `outcome:0` resolve back to the same question market.

Add tests in `test/hyperopen/asset_selector/markets_test.cljs`:

- `build-outcome-markets-builds-recurring-question-test` with the live sample above.
- Assert Yes side encoding `0`, coin `#0`, asset id `100000000`, and No side encoding `1`, coin `#1`, asset id `100000001`.
- Assert `:market-type :outcome`, `:category :outcome`, headline title, period `1d`, expiry timestamp, chance mark, 24h change, volume, and open interest.
- Assert resolving `#0` and `#1` returns the same outcome market.

Milestone 2 adds hydration, cache, and selector discovery. At the end of this milestone, app state contains outcome markets and the asset selector can filter and display them with deterministic rows.

Modify `src/hyperopen/asset_selector/settings.cljs`:

- Add valid tabs `:outcome`, `:outcome-all`, `:outcome-crypto-15m`, and `:outcome-crypto-1d` if subfilters share the same stored key.
- Keep existing stored values compatible; unknown stored values should still fall back to `:all`.

Modify `src/hyperopen/asset_selector/query.cljs`:

- Add `:outcome` tab matching `:market-type :outcome`.
- When active tab is `:outcome`, expose all outcome markets.
- Add helper predicates for outcome period subfilters. `Crypto (15m)` matches outcome period `15m`; `Crypto (1d)` matches period `1d`; `All` matches all outcome periods.
- Sort `:outcome` rows by 24h volume by default, then expiry time ascending, then title.
- Treat `% Chance` as the price sort key for outcome rows.

Modify `src/hyperopen/asset_selector/markets_cache.cljs` and `src/hyperopen/asset_selector/active_market_cache.cljs`:

- Add `:outcome` to market type and category whitelists.
- Persist only deterministic scalar fields and the bounded `:outcome-sides` vector. Do not persist large websocket payloads.
- Add migration-safe defaults so older caches without outcome fields still load.

Modify `src/hyperopen/views/asset_selector/controls.cljs`:

- Add top-level tab label `Outcome` between `Spot` and `Crypto` to match the canonical Hyperliquid selector.
- When `active-tab` is `:outcome`, render the subfilter row `All`, `Crypto (15m)`, `Crypto (1d)`.
- Keep desktop and mobile tab rows horizontally scrollable without changing row height.

Modify `src/hyperopen/views/asset_selector/rows.cljs`:

- For outcome rows, render the question title, an `OUTCOME` chip, `% Chance`, 24h volume in USDH, and open interest in USDH.
- Suppress funding and max leverage chips for outcome rows.
- Keep row height stable at `list-metrics/row-height-px`.

Update tests:

- `test/hyperopen/asset_selector/query_test.cljs` or the existing query coverage for tab matching and period subfilters.
- `test/hyperopen/views/asset_selector/controls_test.cljs` for the new tabs.
- `test/hyperopen/views/asset_selector/rows_test.cljs` for outcome row rendering and fixed-height behavior.
- `test/hyperopen/asset_selector/markets_cache_test.cljs` and `test/hyperopen/asset_selector/active_market_cache_test.cljs` for cache round-trip.

Milestone 3 adds active outcome header and details popover. At the end of this milestone, selecting an outcome renders the selected market like the user screenshot and opens the details copy.

Modify `src/hyperopen/views/active_asset/vm.cljs`:

- Add outcome-specific fields to `active-asset-row-vm`: `:is-outcome`, `:outcome-title`, `:outcome-details`, `:outcome-expiry-ms`, `:outcome-countdown-text`, `:outcome-chance`, `:outcome-price-label`, and `:outcome-open-interest-usd`.
- For outcomes, compute open interest from the Yes side circulating supply or the maximum shared side circulating supply, not mark-notional.
- For outcomes, do not compute funding tooltip model.

Modify `src/hyperopen/views/active_asset/row.cljs`:

- Add a desktop outcome layout with first cell asset button plus details button, then columns `Countdown`, `% Chance`, `Price (Yes)`, `24h Change`, `24h Volume`, and `Open Interest`.
- Add a mobile outcome details panel with the same metrics and the details text behind the existing mobile details affordance.
- Use existing classes and `data-role` patterns; keep text from overlapping at 375, 768, 1280, and 1440 widths.

Create `src/hyperopen/views/active_asset/outcome_details.cljs`:

- Render a small anchored popover or modal using the existing anchored popover pattern in `src/hyperopen/views/ui/anchored_popover.cljs`.
- Provide a button label `Details`.
- Render title and details copy from the normalized market.
- Close on outside click and Escape.

Modify `src/hyperopen/trade/layout_actions.cljs` or the relevant trade UI action owner:

- Add `:actions/toggle-active-outcome-details` and close behavior if the details popover needs app state.
- Keep state under `[:trade-ui :active-outcome-details-open?]`.

Update tests:

- `test/hyperopen/views/active_asset/vm_test.cljs` for outcome VM fields.
- `test/hyperopen/views/active_asset/row_test.cljs` for desktop and mobile outcome rendering.
- Add a Playwright test in `tools/playwright/test/trade-regressions.spec.mjs` that injects/simulates an outcome market in the debug bridge, opens the selector, selects the row, clicks `Details`, and asserts the settlement text appears.

Milestone 4 adds live websocket handling for outcome markets. At the end of this milestone, selecting an outcome updates both side books and active metrics from live websocket payloads.

Modify `src/hyperopen/websocket/domain/model.cljs` and `src/hyperopen/websocket/domain/policy.cljs`:

- Add `activeSpotAssetCtx`, `spotAssetCtxs`, and `outcomeMetaUpdates` as market data topics where they are missing.
- Keep channel grouping deterministic and low side-effect.

Modify `src/hyperopen/websocket/active_asset_ctx.cljs`:

- Accept both `activeAssetCtx` and `activeSpotAssetCtx` in `create-active-asset-data-handler`.
- Normalize payloads with either `:ctx` under `:data` or direct context maps.
- For spot-like/outcome contexts with no oracle/funding/open interest, derive mark, previous day, 24h change, 24h volume, and circulating supply without setting funding.
- Register handlers for both channels in `init!`.

Modify `src/hyperopen/websocket/subscriptions_runtime.cljs`:

- Add a pure helper `active-market-subscription-coins` that returns `[canonical-coin]` for perps/spot and both side coins for outcomes.
- In `subscribe-active-asset!`, subscribe active asset contexts for every side coin when the market is `:outcome`.
- Keep `:active-asset` as the Yes coin for chart and route compatibility.
- In `unsubscribe-active-asset!`, unsubscribe all side coins recorded for the active outcome owner.
- For order books, introduce outcome side subscriptions so `#0` and `#1` books can coexist. Store them under deterministic keys such as `[:orderbooks "#0"]` and `[:orderbooks "#1"]`.

Modify order book and trades runtime only as needed:

- `src/hyperopen/orderbook/actions.cljs`, `src/hyperopen/websocket/orderbook.cljs`, and `src/hyperopen/websocket/trades.cljs` should continue to work with `#` coins. Add tests if any normalization currently rejects `#`.
- Inspect any existing trade-history bootstrap or recent-trades fetch path. If Hyperopen currently primes recent trades before websocket updates for selected markets, add outcome support by allowing `recentTrades` or the existing equivalent info request to accept `#0` and `#1` coins. If no such bootstrap exists, record the decision in this ExecPlan and rely on the websocket `trades` subscription observed in the canonical frontend.
- If a REST recent-trades path is added, normalize its rows through the same trade projection used by websocket `trades`; do not add a parallel outcome-only trade row shape.
- Do not add fallback string manipulation outside the websocket adapter boundary.

Update tests:

- `test/hyperopen/websocket/active_asset_ctx_test.cljs` for `activeSpotAssetCtx`.
- `test/hyperopen/websocket/subscriptions_runtime_test.cljs` or the existing runtime test target for both-side subscriptions and cleanup.
- A focused trade-history or trades runtime test proving `#` outcome coins are accepted by the websocket and optional `recentTrades` bootstrap path.
- `test/hyperopen/runtime/effect_adapters/asset_selector_test.cljs` for selector visible side subscriptions if live selector rows subscribe to visible markets.

Milestone 5 adds outcome order form and order submission semantics. At the end of this milestone, the user can choose Yes or No and build an exchange request with the correct outcome asset id, while generic spot remains read-only.

Modify `src/hyperopen/domain/market/instrument.cljs`:

- Extend market identity so `:market-type :outcome` yields `:outcome? true`, `:spot? false`, `:read-only? false`, `:base-symbol` as the underlying or question label, and `:quote-symbol "USDH"`.
- Preserve existing spot and perp behavior.

Modify `src/hyperopen/trading/submit_policy.cljs`:

- Replace the broad `(:spot? identity)` read-only check with an explicit spot read-only check that does not fire for `:outcome`.
- Keep the error text `Spot trading is not supported yet.` for true spot markets.

Modify `src/hyperopen/state/trading.cljs`:

- Update `market-info`, `market-identity`, `resolve-trading-asset-idx`, `available-to-trade`, and order form context to understand `:market-type :outcome`.
- `resolve-trading-asset-idx` must use the active outcome side's asset id. When the order form side choice is Yes, use `100000000 + yes encoding`; when No, use `100000000 + no encoding`.
- Avoid `updateLeverage` pre-actions for outcomes.

Modify order form namespaces under `src/hyperopen/views/trade/`:

- Add an outcome side segmented control with `Yes` and `No` labels. Reuse existing control patterns; do not add a card inside the order form.
- For outcome markets, label primary actions as `Buy Yes`, `Buy No`, `Sell Yes`, or `Sell No` according to order side and selected outcome side.
- Use USDH for available balance and order value labels.
- Hide leverage and margin controls for outcome markets.
- Keep TP/SL and liquidation-specific UI disabled or hidden for outcomes unless a test proves the exchange supports them for outcomes.

Modify `src/hyperopen/api/gateway/orders/commands.cljs`:

- `build-update-leverage-action` must return nil for `:market-type :outcome`.
- Standard order builders can continue to emit `type "order"` with `:a` as the outcome asset id, price, size, reduce-only, and limit/market order type. Add tests proving the asset id changes when the selected outcome side changes.

Update tests:

- `test/hyperopen/state/trading/identity_and_submit_policy_test.cljs` for outcome identity and no spot read-only gate.
- `test/hyperopen/api/gateway/orders/commands_test.cljs` for Yes/No outcome asset ids and no leverage pre-action.
- Relevant order form VM tests for labels, hidden leverage/margin controls, and selected side persistence.

Milestone 6 adds account surface and open order display parity for outcome positions. At the end of this milestone, outcome balances and open orders are not shown as raw `#0` and `#1` without context.

Inspect current account info projections under `src/hyperopen/views/account_info/projections/**` and tabs under `src/hyperopen/views/account_info/tabs/**`.

Implement:

- A pure outcome display helper that maps `#0` to `BTC above 78213 ... / Yes` and `#1` to the same title with `No`.
- Outcome positions/balances should appear in the existing `Outcomes` tab if current account state already provides the raw token balances. If Hyperopen lacks that tab implementation, add a scoped tab slice instead of mixing outcomes into generic balances.
- Open orders and trade history should render the question title plus side name and preserve raw coin in a tooltip or debug data attribute.

Update tests around account info projections and open orders so raw `#` identifiers do not become primary display labels.

Milestone 7 performs browser QA and final hardening. At the end of this milestone, the feature is demonstrably working locally and required gates pass.

Add Playwright coverage in `tools/playwright/test/trade-regressions.spec.mjs` or a focused new spec:

- Seed the app with outcome metadata and side contexts through existing debug/simulator helpers.
- Open the asset selector, click `Outcome`, assert the BTC row appears with `% Chance`, `Volume`, and `Open Interest`.
- Select the outcome and assert the header metrics and details popover text.
- Assert the order form switches action labels between `Buy Yes` and `Buy No`.
- Assert selected-side trades render after either a seeded recent-trades bootstrap or a simulated websocket `trades` frame for `#0`.
- Assert no raw `#0` or `#1` is the primary selector label.

Run focused checks first:

    npm test -- --focus hyperopen.asset-selector.markets-test
    npm test -- --focus hyperopen.views.active-asset.vm-test
    npm test -- --focus hyperopen.websocket.active-asset-ctx-test
    npm run test:playwright:headed -- --grep "outcome"

Then run required gates from the repository root:

    npm run check
    npm test
    npm run test:websocket

For UI-facing changes, run the browser QA command required by `docs/FRONTEND.md`:

    npm run qa:design-ui -- --targets trade-route --manage-local-app

Before concluding browser work, clean browser sessions:

    npm run browser:cleanup

## Concrete Steps

1. Start a branch if implementation begins in this workspace:

       git switch -c codex/outcome-markets

2. Keep `bd` issue `hyperopen-4lja` open while this active plan is being implemented. If a different tracker issue becomes canonical, update the frontmatter and this paragraph.

3. Implement Milestone 1 and run:

       npm test -- --focus hyperopen.asset-selector.markets-test
       npm test -- --focus hyperopen.api.endpoints.market-test
       npm test -- --focus hyperopen.api.market-loader-test

   Expected result: tests covering outcome metadata request and market normalization pass. Before implementation, the new tests should fail because `request-outcome-meta!` and `build-outcome-markets` do not exist.

4. Implement Milestone 2 and run:

       npm test -- --focus hyperopen.asset-selector.query-test
       npm test -- --focus hyperopen.views.asset-selector.controls-test
       npm test -- --focus hyperopen.views.asset-selector.rows-test
       npm test -- --focus hyperopen.asset-selector.markets-cache-test
       npm test -- --focus hyperopen.asset-selector.active-market-cache-test

   Expected result: the selector can filter `:outcome` markets and render the new rows without changing non-outcome rows.

5. Implement Milestone 3 and run:

       npm test -- --focus hyperopen.views.active-asset.vm-test
       npm test -- --focus hyperopen.views.active-asset.row-test

   Expected result: active outcome header VM contains title, details text, countdown, chance, price, volume, and open interest. Row rendering includes a Details button and the popover content.

6. Implement Milestone 4 and run:

       npm test -- --focus hyperopen.websocket.active-asset-ctx-test
       npm test -- --focus hyperopen.websocket.subscriptions-runtime-test
       npm run test:websocket

   Expected result: `activeSpotAssetCtx` frames update active side contexts and outcome selection subscribes both side coins.

7. Implement Milestone 5 and run:

       npm test -- --focus hyperopen.state.trading.identity-and-submit-policy-test
       npm test -- --focus hyperopen.api.gateway.orders.commands-test
       npm test -- --focus hyperopen.views.trade.order-form-vm-test

   Expected result: outcome submit policy is enabled, spot submit policy remains read-only, Yes uses asset id `100000000`, No uses asset id `100000001`, and no leverage update pre-action is emitted.

8. Implement Milestone 6 and run focused account surface tests selected from the changed namespaces.

9. Implement Milestone 7 and run all required validation commands listed above. Record exact command results in this ExecPlan before moving it to completed.

## Validation and Acceptance

Use the live Hyperliquid sample as the deterministic fixture for unit tests:

    outcomeMeta = {"outcomes":[{"outcome":0,"name":"Recurring","description":"class:priceBinary|underlying:BTC|expiry:20260503-0600|targetPrice:78213|period:1d","sideSpecs":[{"name":"Yes"},{"name":"No"}]}],"questions":[]}
    yesCtx = {"coin":"#0","markPx":"0.65012","midPx":"0.65006","prevDayPx":"0.55","dayNtlVlm":"415908.3249700002","circulatingSupply":"204692.0","dayBaseVlm":"683834.0"}
    noCtx = {"coin":"#1","markPx":"0.34988","midPx":"0.34994","prevDayPx":"0.45","dayNtlVlm":"267925.6750300001","circulatingSupply":"204692.0","dayBaseVlm":"683834.0"}

Acceptance is met when:

- Unit tests prove the fixture becomes one outcome market with two sides and correct asset ids.
- Unit tests prove selecting either `#0` or `#1` resolves the same outcome question.
- UI tests prove the selector, header, Details popover, and order form labels render correctly.
- Websocket tests prove `activeSpotAssetCtx` updates the same active context path used by the active header.
- Submit-policy tests prove outcomes can submit and generic spot remains read-only.
- Required repo gates pass: `npm run check`, `npm test`, and `npm run test:websocket`.
- Browser QA is explicitly reported for visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes across 375, 768, 1280, and 1440 widths.

## Idempotence and Recovery

All implementation steps are additive until final cleanup. If outcome hydration fails against live API, keep `request-outcome-meta!` returning an empty normalized structure on recoverable API errors and surface a selector error only when the main market loader fails its existing required data. Do not make existing perps or spot hydration depend on outcomes being present.

If cache migration fails in development, clear only Hyperopen's asset selector cache through existing cache helpers or browser storage for the local dev origin. Do not clear unrelated user browser storage.

If websocket subscription cleanup misbehaves, run:

    npm run browser:cleanup

Then re-run the focused websocket subscription tests before continuing.

## Interfaces and Dependencies

New or changed pure interfaces expected by the end of implementation:

    hyperopen.asset-selector.markets/outcome-encoding
    [outcome side] -> integer

    hyperopen.asset-selector.markets/outcome-coin
    [outcome side] -> string such as "#0"

    hyperopen.asset-selector.markets/outcome-asset-id
    [outcome side] -> integer such as 100000000

    hyperopen.asset-selector.markets/parse-outcome-description
    [description] -> normalized map with class, underlying, expiry, target, and period

    hyperopen.asset-selector.markets/build-outcome-markets
    [outcome-meta spot-asset-ctxs] -> vector of normalized outcome market maps

    hyperopen.websocket.subscriptions-runtime/active-market-subscription-coins
    [market canonical-coin] -> vector of coins to subscribe

The plan does not require a new third-party library. Date formatting can use existing project formatting helpers where available; otherwise use JavaScript `Date` in a pure helper wrapped at the formatting boundary.

## Artifacts and Notes

Live Hyperliquid selected-market body text after clicking Details:

    BTC above 78213 on May 3 at 2:00 AM?
    If the BTC mark price at time of settlement is above 78213 at May 03, 2026 06:00 UTC, YES tokens pay out $1 each. Otherwise, NO tokens pay out $1 each.

Representative live websocket frames:

    sent: {"method":"subscribe","subscription":{"type":"l2Book","coin":"#0","nSigFigs":null}}
    sent: {"method":"subscribe","subscription":{"type":"l2Book","coin":"#1","nSigFigs":null}}
    sent: {"method":"subscribe","subscription":{"type":"activeAssetCtx","coin":"#0"}}
    sent: {"method":"subscribe","subscription":{"type":"activeAssetCtx","coin":"#1"}}
    received: {"channel":"activeSpotAssetCtx","data":{"coin":"#0","ctx":{"prevDayPx":"0.55","dayNtlVlm":"418455.9385100002","markPx":"0.64012","midPx":"0.64012","circulatingSupply":"206110.0","coin":"#0","totalSupply":"184467440737095.53125","dayBaseVlm":"687756.0"}}}

## Outcomes & Retrospective

Implemented the core outcome market path: `outcomeMeta` hydration, recurring outcome normalization, selector tabs/rows/cache, active header/details content, `activeSpotAssetCtx` websocket updates, both-side active subscriptions, and Yes/No order asset id routing.

Validation completed on 2026-05-02:

- `npm run check` passed.
- `npm test` passed: 3704 tests, 20431 assertions, 0 failures, 0 errors.
- `npm run test:websocket` passed: 523 tests, 3040 assertions, 0 failures, 0 errors.
- Browser smoke passed against local dev server on `http://localhost:8081/trade`: order form rendered, title was `BTC | HyperOpen`, order-form box was present, and no console errors were captured.

Remaining follow-up risk: the active header Details content is rendered deterministically but is not yet wired as a fully anchored click-open/close popover with outside-click and Escape semantics. Account surfaces for outcome balances/open orders were not expanded in this implementation pass.

## Revision Notes

- 2026-05-02 / Codex: Created the initial plan from official docs, live Hyperliquid frontend inspection, live API samples, and Hyperopen repository mapping. The plan records known uncertainty around the contract-spec recurring anchor and the docs' stale testnet-only wording for `outcomeMeta`.
- 2026-05-02 / Codex: Merged external-plan deltas by adding explicit `recentTrades` / trade-history bootstrap investigation and stronger recurring-description parser requirements while preserving the repo-specific websocket, selector, and submit-policy plan.
