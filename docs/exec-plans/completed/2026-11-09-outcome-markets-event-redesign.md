# Outcome markets: events you can read and trade by side

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It is maintained in accordance with `/hyperopen/.agents/PLANS.md` and the public planning contract in `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

Hyperopen lists Hyperliquid outcome markets (prediction markets) under the market selector's Outcome tab and lets people trade them from the trade page. Today almost every one of them is unreadable: rows show raw template keys such as `template:sportsContestWinner`, side buttons say `Buy template:{shortNameA}`, the Sports and Economics tabs are empty, untraded contracts show a fake 50% chance, the countdown is blank for anything that is not a native daily crypto contract, and a position renders as `0.0000 #270370`.

After this change a person can open the Outcome tab and see one row per real-world event ("Detroit Pistons vs Washington Wizards", "Fed — December 2026 decision", "Bitcoin reaches… by Nov 30"), grouped by category, each with clickable answers that show the chance ("DET 82%"). Clicking an answer opens the trade page with that side already picked. The ticket asks one question at a time: which side, how much money, and what it pays ("Pays $122.00 if DET wins"); selling only appears as "Cash out" on shares the person already holds. Pick-one events (Italy / Draw / France / Other) and price ladders (several strikes on one date) show every answer at once. The account panel's Outcomes tab reads as bets ("DET to win · 122 shares · pays $122.00"), not coin ids.

The approved visual design is the "Outcome Markets Redesign" canvas (link under Context References). Every rule this plan relies on is restated here so the canvas is not needed to execute it.

## Context References

Public refs:
- Direct user request, 2026-11-09 (this session): "I saw the designs. I like them. I want you to create an execution plan based upon them. And implement it."

Repo artifacts:
- This plan: `docs/exec-plans/active/2026-11-09-outcome-markets-event-redesign.md`.
- Design canvas (private to the requester, non-authoritative for execution; all rules are restated below): https://claude.ai/artifact/KmRqFk3VEzH3JGxX3ke8xj — boards 1a–3c.
- Governing UI docs: `docs/FRONTEND.md`, `docs/agent-guides/ui-foundations.md`, `docs/agent-guides/trading-ui-policy.md`, `docs/agent-guides/browser-qa.md`, `docs/BROWSER_TESTING.md`.

Local scratch refs (non-authoritative):
- None.

## Progress

- [x] (2026-11-09 17:40Z) Diagnosed the live `outcomeMeta` data and published the design canvas (prior work in this session).
- [x] (2026-11-09 18:30Z) Wrote this ExecPlan from the approved boards and a read of the current outcome code paths.
- [x] (2026-11-09 19:40Z) Milestone 1: `src/hyperopen/outcomes/domain/{labels,clock,templates,quotes,book,events,display}.cljs`; `build-outcome-markets` renders titles, side and option labels, categories, clocks, venues and rules from the registry and groups ladders; caches persist the display fields; `market_live_projection.cljs` patches `midPx`. Fixture `test/hyperopen/outcomes/test_support/fixture.cljs` (38 live outcomes, 4 questions) drives `test/hyperopen/outcomes/domain/*_test.cljs`. `markets.cljs` shrank 1349 → 1245 lines.
- [x] (2026-11-09 20:30Z) Milestone 2: `asset_selector/outcome_listing.cljs` (sections, answers, chip counts, subscribed coins) and `views/asset_selector/outcome_list.cljs` (chips + event rows with answer buttons); `layout.cljs` swaps them in for outcome tabs; `settings.cljs`/`query.cljs` know the new chips.
- [x] (2026-11-09 21:40Z) Milestone 3: `views/active_asset/outcome_header_vm.cljs` + `outcome_row.cljs` replace the outcome header (desktop and mobile); `views/trade/outcome_ticket_vm.cljs` + `outcome_ticket.cljs` replace the outcome branch of the order form (pick a side, USDC amount, shares and payout, Cash out, no-market guard, Rules/Timeline/Market details). `row.cljs` (415) and `vm.cljs` (426) dropped under the size cap and left the exceptions file.
- [x] (2026-11-09 22:10Z) Milestone 4: the ticket lists every answer of pick-one, field and ladder events with Yes/No buttons, a chance bar and the "Other" line; the listed-sum warning gates the button behind a confirmation; ladder rungs show direction and distance from the live underlying and flag out-of-order prices.
- [x] (2026-11-09 22:30Z) Milestone 5: `projections/outcomes.cljs` adds `:your-side`, `:market-meta`, `:clock`, `:pays`; `tabs/outcomes.cljs` shows Market · Your side · Shares · Avg → now · Value now · Pays if right · P&L · Resolves · Cash out.
- [x] (2026-11-10 00:40Z) Milestone 6 (browser part): built the worktree app (`npx shadow-cljs --force-spawn compile app`, 0 warnings), served it with `tools/playwright/static_server.mjs` on :4180 and checked live data at 1440, 768 and 375 px: Outcome tab chips All 136 / Sports 88 / Crypto 25 / Stocks & commodities 20 / Economics 1 / Companies 2; answer → trade page on that side; Fed and BTC ladder tickets; phone header and list. Added `tools/playwright/test/outcome-markets.spec.mjs` (2 tests, stubbed feed) and moved six `trade-regressions.spec.mjs` outcome tests to the new UI; all pass twice in a row.
- [x] (2026-11-10 01:30Z) Milestone 6 (gates): `npm run gates` 33/34 with the one failure a stale expectation in this branch's own entry-mode test; fixed and `npm test` re-run green (8147 tests, 50037 assertions, 0 failures); `lint:hiccup`, `lint:namespace-sizes`, `lint:theme-colors`, `lint:docs` re-run green after the last header tweak; 8/8 outcome Playwright tests pass on the final build.
- [x] (2026-11-10) Self-review of commit `8a060b95f` found order-path defects and gaps in the diagnosis; canvas row 4 (boards 4a-4f) records the corrections.
- [x] (2026-11-10) Second pass, order path: every ticket click is one action (`:actions/set-outcome-ticket-entry`); a blank limit price blocks submit; the stale-price confirmation is form state enforced by the submit policy; `select-asset` owns the selected answer and resets an outcome ticket to Buy; held-shares, market-off and price-range checks live in `trading/submit_policy.cljs`.
- [x] (2026-11-10) Second pass, one chance rule (board 4b) in `outcomes/domain/quotes.cljs` + `book.cljs`, fed by a `bbo` subscription per visible contract (`websocket/outcome_bbo.cljs`, cap 60, one REST `l2Book` seed per quiet book); list, header, ticket and the stale gate read the same quote.
- [x] (2026-11-10) Second pass, ladders (4d): ladder key includes venue, source, window and deadline; direction from the live price; each rung checked against every easier rung; event totals recomputed per contract.
- [x] (2026-11-10) Second pass, fees and exits (4c): `outcomes/domain/fees.cljs`; buy, cash-out and merge summaries after fees; `mergeOutcome` action built from the documented payload (NOT verified against the exchange).
- [x] (2026-11-10) Second pass, surfaces (4e) and room (4f): event names in tab title, toasts and order tables; positions grouped by event after fees; account value counts outcome shares; full-height list panel with sort, load age, Refresh and Retry; two-line header for multi-answer events; phone Buy bar and ticket sheet.
- [x] (2026-11-10) Validation: `npm run gates` 34/34 on Node 22 (8996 tests); `tools/playwright/test/outcome-markets.spec.mjs` 12/12 on a stubbed feed where mark differs from the midpoint; live check of list, header and ticket at 1440x900 with no wallet.
- [x] (2026-11-10) Performance pass (`cc9336c92`): owner reported seconds of lag with an outcome market selected. `bbo` quotes rewrote the catalog about 10 times a second and missed every catalog-keyed memo; quotes are now batched to at most 4 store writes a second, and account panels and the chart get a catalog that is stable across quote-only changes. Main thread busy fell from 8.4 s to 1.7 s per 10 s (dev build); side switch and selector open about 110 ms.
- [x] (2026-11-11) Chance-scaled chart axis and cents order book (`cbbaa730a`, canvas https://claude.ai/artifact/HBjhb16Ufc5RfPn5tsFnzZ): chart Axis `%`/`¢` control (percent by default, stored in `chart-outcome-price-unit`); order book and Trades in cents; All answers chart (one line per answer on a 0-100% scale) on events with two or more named answers; selector tab switch no longer leaves the Outcome panel's box on the dropdown.
- [x] (2026-11-11) Owner review, commit and landing: merged to `main` as `3acd9c4ab` (branch `feature/outcome-markets-redesign`) after `npm run gates` 34/34 on the merged result and 17/17 outcome Playwright tests; plan moved to `docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: no outcome contract in the live feed ships display text. `name` is a template key and the words live in the pipe-delimited `description` parameters; even side names are templates (`template:{shortNameA}`).
  Evidence: live `outcomeMeta` (2026-11-09 16:25 UTC) — 306 outcomes, 23 questions, 15 distinct `name` values, zero human-readable names.
- Observation: an untraded side reports `markPx "0.5"`, `midPx null`, `prevDayPx "0.0"`. A thin book quoted 1.2¢ / 98.8¢ reports `midPx "0.5"` while `markPx` follows the last trade (`0.051985`), so `markPx` behaves like median(best bid, best ask, last trade).
  Evidence: `#290680` (fallback "Other" of Italy vs France) and `#300160` (BTC daily range bucket) in `spotMetaAndAssetCtxs`.
- Observation: the two sides of one contract always sum to exactly 1.00 and side 1's book is side 0's book mirrored, so "buy side B" is economically "sell side A".
  Evidence: `#270370` mark 0.81908 + `#270371` mark 0.18092 = 1.00000.
- Observation: missing context fields reach side maps as 0, not nil (`safe-num` in `markets.cljs`), so a side with no context looked like a 0% chance.
  Evidence: the invented `template:weatherHighTemperature` fixture contract rendered "<1%" until `quotes/side-quote` treated a 0 price as missing.
- Observation: the order-form view model is a closed contract; adding a `:summary` key failed `order-form-vm-valid?` across eight order types.
  Evidence: `order_form_vm_test.cljs` "contract failed for :market"; the ticket now reads available USDC from `hyperopen.state.trading/available-to-trade` instead.
- Observation: a question's fallback ("Other") outcome is not in the question's side aliases, so its coins cannot be selected through `select-asset`.
  Evidence: `question-side-aliases` in `markets.cljs` only walks named options; the ticket shows "Other" as a read-only line.
- Observation: selecting any outcome market already failed in dev builds: `select-asset` emitted one `:effects/subscribe-orderbook` per side coin, and the dev-only effect-order contract rejects duplicate heavy effects for `:actions/select-asset`.
  Evidence: browser console "effect-order contract failed for :actions/select-asset. rule=duplicate-heavy-effect effect-id=:effects/subscribe-orderbook" on clicking an answer; release builds skip the check (`goog.DEBUG`), so it only bit in development and Playwright.
- Observation: `domain.trading.core/number->clean-string` stripped trailing zeros even without a decimal point, so with 0 size decimals 50 became "5", 100 became "1", 120 became "12" — every whole-share outcome size, and sizes on 0-decimal perps, were cut tenfold.
  Evidence: in the browser, a 20¢ limit with $10 produced size "5"; `trading/base-size-string` on 50 returned "5". The Lean `OrderRequest/Standard.lean` model already trims fraction digits only, so the fix aligns the code with the formal model.
- Observation: the order-form panel receives a selected slice of app state without the market catalog, so ladder tickets could not read the underlying price; and chaining a second form-writing action after a side switch in one dispatch read the stale form and undid the switch.
  Evidence: `trade_view.cljs` `order-form-view-state-keys`; the Playwright side-switch test saw `[:order-form :outcome-side]` return to 0.
- Observation: `src/hyperopen/asset_selector/markets.cljs` (1349 lines), `views/active_asset/row.cljs` (514), `views/active_asset/vm.cljs` (515) and `views/trade/order_form_controls.cljs` (803) all sit exactly at their `dev/namespace_size_exceptions.edn` caps, so new behavior must live in new namespaces.

## Decision Log

- Decision: Put the template registry, event grouping, quote rules and clocks in new pure namespaces under `src/hyperopen/outcomes/domain/`, and only call them from `markets.cljs`.
  Rationale: the boundary linter forbids non-view code from importing `hyperopen.views.*`, domain code must stay pure for tests, and `markets.cljs` is at its size cap.
  Date/Author: 2026-11-09 / Claude.
- Decision: Group standalone contracts that share a template, an underlying (or company) and a time into a synthetic multi-answer market with `:outcome-kind :ladder` and key `ladder:<template>|<subject>|<time>`, whose rungs are `:question-options`.
  Rationale: the selector, the active-market resolver (`market-with-selected-outcome-coin`), live context patching and the ticket already know how to select one option of a question by side coin; reusing that machinery makes ladders tradeable with no new order path. Singles stay `:binary`.
  Date/Author: 2026-11-09 / Claude.
- Decision: Titles carry no time or relative words ("Bitcoin above $74,456", "Bitcoin reaches…"); when the event happens is a separate clock line rendered at view time in the viewer's time zone.
  Rationale: titles are built once when the catalog loads and are cached; "Tonight" or a time-zone-specific time inside a cached title goes stale and makes tests time-zone fragile.
  Date/Author: 2026-11-09 / Claude.
- Decision: The chance shown in lists comes from the side context: mid when `midPx` exists and is within 5¢ of `markPx`, otherwise `markPx` labelled "last"; `midPx` missing means "No market"; `prevDayPx` 0 with no volume and no mid means "Not traded". On the trade page the live order book decides (spread ≤ 5¢ → mid, else last).
  Rationale: contexts carry no bid/ask, and a 1.2¢ / 98.8¢ book reports a meaningless 0.5 mid; a mark far from the mid can only happen when the spread is wider than twice that gap.
  Date/Author: 2026-11-09 / Claude.
- Decision: Outcome sub-tabs become category chips — All, Sports, Crypto, Stocks & commodities, Economics, Companies (`:outcome`, `:outcome-sports`, `:outcome-crypto`, `:outcome-stocks`, `:outcome-economics`, `:outcome-companies`). The legacy `:outcome-1d` and `:outcome-15m` stay valid persisted values and filter like Crypto.
  Rationale: categories now come from the template, so every event lands in one chip; keeping the old keys valid avoids resetting people's saved tab.
  Date/Author: 2026-11-09 / Claude.
- Decision: The outcome event list renders without virtualization.
  Rationale: the feed has about 135 events; one non-virtual list of that size is cheap, and event rows have shape-dependent heights that the fixed-row virtual window cannot model.
  Date/Author: 2026-11-09 / Claude.
- Decision: Keep the existing order pipeline (active side coin + buy/sell + whole-share size). The ticket only changes how the choice is asked: picking a side switches the active side coin and sets buy; Cash out sets sell on a held side with the held size.
  Rationale: order signing, asset ids, submit policy and formal order-request surfaces stay untouched, so the risk is confined to view code.
  Date/Author: 2026-11-09 / Claude.
- Decision: The Outcome tab subscribes live contexts for the side-0 coins of the first 30 events' answers, not a scroll window.
  Rationale: side 1 mirrors side 0, the event list is not virtualized, and the existing subscription budget per selector open is of the same order.
  Date/Author: 2026-11-09 / Claude.
- Decision: The limit price is typed in cents through a function handler that only dispatches complete numbers; "Prices don't add up" gates the order button with a CSS `:has()` rule on an uncontrolled checkbox.
  Rationale: the form stores dollars, and adding new order-form fields or actions would touch the field-ownership formal surface; both choices keep the change in view code. The gate is a guard against stale prices, not a safety interlock.
  Date/Author: 2026-11-09 / Claude.
- Decision: An outcome selection subscribes one order book and trades feed — the selected side's — instead of one per side coin; activeAssetCtx subscriptions still cover every side so every answer's price stays live.
  Rationale: the sibling book is the same book mirrored, the ticket prices both sides from one book, and one subscribe per selection satisfies the effect-order contract without changing its Lean-backed policy.
  Date/Author: 2026-11-09 / Claude.
- Decision: "Not traded" needs positive evidence (previous-day price 0 and no volume); a side with no context yet reads "—".
  Rationale: cached markets on a cold start and seeded states carry no context fields, and calling them untraded is false.
  Date/Author: 2026-11-09 / Claude.
- Decision: The selected outcome market carries `:display-name` = its side ("WAS", "Hold Yes") so the order book, chart legend and page title stop showing coin ids; the event list fits the chart panel's remaining height and drops the shortcut footer on outcome tabs.
  Rationale: those surfaces already read `:display-name`; the dropdown lives inside the chart panel, which clips at its bottom edge.
  Date/Author: 2026-11-09 / Claude.
- Decision: Defer the multi-line event history chart (board 3a) and the settled-receipt ticket (board 2d F).
  Rationale: the chart needs per-option candle history the app does not fetch today, and the feed exposes no settlement result for open markets; both are additive follow-ups.
  Date/Author: 2026-11-09 / Claude.

## Outcomes & Retrospective

Milestones 1–5 are in place and checked on live data (2026-11-09): every outcome reads in words, the Outcome tab lists 135 events under six category chips (Sports was empty before), an answer opens the trade page on that side, the ticket asks side → amount → payout with Cash out on held shares, multi-answer events list every answer with an "Other" line, ladders sit against the live underlying, and the Outcomes tab reads as bets. Browser work surfaced two pre-existing defects outside the views that this branch fixes: whole-number sizes lost their trailing zeros in `number->clean-string` (orders of 50 outcome shares went out as 5), and outcome selection broke the dev-only effect-order contract by subscribing one book per side.

Complexity: overall it went up in the outcome domain (six new pure namespaces, about 900 lines) and down in the shared files — `markets.cljs` shrank by about 100 lines and `active_asset/row.cljs` and `vm.cljs` dropped under the 500-line cap — because display logic moved out of the market builder and header into named, tested modules. Not done: settled receipts (2d F), ←/→ answer keys in the list, and trading the "Other" fallback from the ticket.

Closed 2026-11-11 with these left open, not done and not tracked elsewhere:
- Not built: the settled-market receipt is written (`outcomes/receipt.cljs`, `views/account_info/outcome_receipt.cljs`) but not mounted, because the feed drops a settled contract and nothing records its result.
- Unverified against the venue: the `mergeOutcome` signing scheme and the meaning of its `outcome` id (built from docs, never sent); fees on skew, txyz and fox; the open-interest definition ("Locked" is the larger side's supply summed per event); the open-order coin form behind the chart's order badges.
- Not done: ←/→ answer keys in the list; trading the "Other" fallback from the ticket; tail-refreshing a revisited coin's cached candles (a pre-existing gap).

## Context and Orientation

Terms used in this plan:

An outcome is one binary contract with two sides that pay $1.00 (in USDC) to the winning side and $0 to the other. A side is tradeable as its own coin named `#` followed by `outcome × 10 + side` (side 0 of outcome 27037 is `#270370`, side 1 is `#270371`); its order asset id is 100000000 plus that number. Shares trade in whole units (size decimals 0). Because the two sides of one outcome are one order book seen from both ends, their prices always add up to 1.00.

A question groups several outcomes where exactly one wins: `namedOutcomes` plus a `fallbackOutcome` ("Other"). A template is the recipe a deployer used to create a contract; its key is the contract's `name` (for example `template:sportsContestWinner`) and its parameters are `key:value` pairs in the pipe-delimited `description`. A venue is the deployer that listed the contract (`out`, `skew`, `txyz`, `fox`); contracts without a venue are Hyperliquid's own ("native") daily markets, whose names are `Recurring`, `Recurring Named Outcome` and `Recurring Fallback`.

An event is what a person bets on: one game, one Fed decision, one set of strikes on one date. A shape is how an event is traded: head-to-head (one contract, two named sides), pick one (a question with a short list), field (a season-long question with many contenders), ladder (several standalone contracts on the same underlying and time, one per strike — each strike is a rung), and yes/no (a single standalone contract). Chance is the market's probability for a side, shown as a percent; price is what one share costs, shown in cents. Percentage points ("pts") are the difference between two chances.

The live template keys and their parameters, all of which the registry must render:

    sportsContestWinner         competition contestType countedPlay officialSource participantA participantB
                                resolutionDeadline scheduledStart season shortNameA shortNameB sport stage
    sportsContestResult (Q)     same game fields minus shortNames; options sportsContestParticipant2 {participant},
                                sportsContestDraw2 (no params), fallback
    sportsTournamentWinner (Q)  competition officialSource resolutionDeadline season sport;
                                options sportsTournamentParticipant {participant}, fallback
    policyRateDecision (Q)      decisionDeadline decisionLabel institution officialSource policyMeasure scheduledDecision;
                                options policyRateIncrease / policyRateNoChange / policyRateDecrease, fallback
    binaryPrice                 perp priceDescription seconds threshold time
    priceTouch                  perp priceDescription seconds target time
    companyIpoFirstDayMarketCap company listingDeadline marketCapThresholdB
    companyIpoConfirmed         company dateTime
    Recurring                   class:priceBinary underlying expiry targetPrice period, or
                                class:priceBucket underlying expiry priceThresholds period (a question)
    Recurring Named Outcome     index (the bucket number of its priceBucket question)
    template fallback / Recurring Fallback   description "other"

Times are `YYYYMMDD-HHMM` in UTC. Side names are `template:{shortNameA}`, `template:{shortNameB}`, `template:Yes`, `template:No`, or plain `Yes`/`No`.

How the code is laid out today. `src/hyperopen/api/endpoints/market.cljs` (`request-asset-selector-markets!` area, around line 430) fetches `outcomeMeta` and spot asset contexts and calls `hyperopen.asset-selector.markets/build-outcome-markets`. That function (in `src/hyperopen/asset_selector/markets.cljs`, lines 419–1145) builds one market map per question (`:outcome-kind :question`, key `question:<id>`, options in `:question-options`) and one per standalone outcome (`:outcome-kind :binary`, key `outcome:<id>`). Each market carries `:outcome-sides` (side maps with `:coin`, `:asset-id`, `:side-index`, `:side-name`, `:mark`, `:mid`, prices), `:outcome-side-aliases` (side coin → which option/side it is) and `:outcome-subscription-coins`. `market-with-selected-outcome-coin` turns a market plus a side coin into the active market for that side; `src/hyperopen/asset_selector/actions.cljs` `select-asset` uses it, so dispatching `[:actions/select-asset "#270371"]` already opens the right market on side 1. `src/hyperopen/asset_selector/market_live_projection.cljs` patches live marks into sides and options. `src/hyperopen/asset_selector/markets_cache.cljs` and `active_market_cache.cljs` persist a whitelisted subset of market fields to browser storage for fast startup.

The selector UI lives in `src/hyperopen/views/asset_selector/` (`layout.cljs` composes search, tabs, sub-tabs, sort header, the virtualized `runtime/asset-list`, and the mobile list in `rows.cljs`); tab filtering is `src/hyperopen/asset_selector/query.cljs` `tab-match?`; valid tabs are in `src/hyperopen/asset_selector/settings.cljs`. The trade header for outcomes is `desktop-outcome-active-asset-row` in `src/hyperopen/views/active_asset/row.cljs`, fed by `active-asset-row-vm` in `src/hyperopen/views/active_asset/vm.cljs`, with the rules tooltip in `outcome_tooltip.cljs`. The order form is `src/hyperopen/views/trade/order_form_view.cljs` (`render-order-form`, outcome branch: Buy/Sell `action-side-tabs` plus `controls/outcome-side-row`), its view model `order_form_vm.cljs`, handlers `order_form_handlers.cljs`; picking a side dispatches the `set-order-outcome-side` command, and `src/hyperopen/order/outcome_side_selection.cljs` switches the active coin to that side. The account panel's Outcomes tab is `src/hyperopen/views/account_info/tabs/outcomes.cljs` over the projection `src/hyperopen/views/account_info/projections/outcomes.cljs`.

Repository rules that bite: views may not use `text-[10px]`…`text-[15px]` (use `text-xs`/`text-sm`), Hiccup `:class` values must be vectors of single tokens and `:style` keys keywords (`npm run lint:hiccup`, `lint:style-keys`), raw color literals in views are ratcheted (`npm run lint:theme-colors` — use `ho-*` tokens and Tailwind theme classes), `.cljs` files over 500 lines need an entry in `dev/namespace_size_exceptions.edn`, Replicant has no `[:<>]` fragments and reads keys only from the attrs map (`:replicant/key`), and `into` must target the tag vector when generating children.

## Plan of Work

Milestone 1 — the outcome domain. Create `src/hyperopen/outcomes/domain/templates.cljs` with `parse-params` (description → map of parameter name to trimmed value, splitting each field on its first colon) and a registry keyed by the normalized template id (strip `template:`; `template fallback` and `Recurring Fallback` become `fallback`; `Recurring` becomes `recurring-price-binary` or `recurring-price-bucket` by its `class`). For an outcome spec the registry returns `{:template :shape :title :side-labels :side-long-labels :option-label :category :subcategory :clock :rules :venue :subject}`; for a question spec it returns the same with `:shape` `:pick-one` or `:field`. Titles follow the table in "Artifacts and Notes"; any unknown key is humanized (strip `template:`, split camelCase, fill `{param}` holes, fall back to "Market <id>") and gets category `:other`. Category is `:sports`, `:crypto`, `:stocks` (any `perp` in the `xyz:` namespace), `:economics` or `:companies`. Display aliases: `Federal Open Market Committee` → `Fed`; `BTC` → `Bitcoin`, `ETH` → `Ethereum`, `SOL` → `Solana`, `ZEC` → `Zcash`, `xyz:SP500` → `S&P 500`, `xyz:GOLD` → `Gold`, `xyz:SILVER` → `Silver`, `xyz:CL` → `WTI crude`; anything else shows its base symbol. Prices format with thousands separators and a `$` except index underlyings (`xyz:SP500`, `xyz:XYZ100`); market-cap thresholds in billions show as `$1.5T` from 1000 upward.

Create `src/hyperopen/outcomes/domain/clock.cljs`: `clock-spec` picks the clock field by template (`scheduledStart` → `:starts`, `time`/`expiry` → `:settles`, `scheduledDecision` → `:decision`, `resolutionDeadline` alone → `:resolves`, `listingDeadline` → `:listing`, `dateTime` → `:ends`) and returns `{:kind :at-ms :deadline-ms}`; `clock-view [spec now-ms opts]` returns `{:phase :label :absolute}` with phases `:upcoming`, `:soon` (under an hour), `:live` (games after start, before the deadline), `:awaiting` (past the time, not settled) and labels "Starts in 7h 34m", "Settles in 42m", "Decision in 30d", "Resolves by Jun 30, 2027", "If listed by Jan 1, 2027", "Ends Nov 30", "Live", "Awaiting result". `:absolute` formats through `Intl.DateTimeFormat` with `timeZoneName "short"`; `opts` may pin `:time-zone` so tests are deterministic.

Create `src/hyperopen/outcomes/domain/quotes.cljs`: `side-quote` from a side map (`:mark`, `:mid`, `:prevDayRaw`, `:volume24h`) returning `{:state (:live|:last|:no-market|:not-traded) :chance :price}`; `book-quote` from best bid/ask (`:state :live` when the spread is at most 0.05, else `:last` with the mark); `chance-text` ("82%", "<1%", ">99%", "Not traded", "No market"), `cents-text` (one decimal; `:precise` three decimals), `points-text` ("+0.2 pts"), `payout` math (`shares`, `cost`, `pays`, `profit`, `profit-pct`) and `walk-asks` (fills a USDC amount against ask levels in whole shares, returning shares, cost and average price).

Create `src/hyperopen/outcomes/domain/events.cljs`: `ladder-key` for a standalone outcome spec (binaryPrice and priceTouch → template + perp + time; companyIpoFirstDayMarketCap → template + company; everything else nil) and `build-ladder-market` that turns two or more built binary entries into one `:outcome-kind :ladder` market (rungs sorted by threshold descending, each rung a question option with `:label`, `:threshold`, `:venue`, both side coins; selection on the rung with the most 24h volume; aliases and subscription coins over all rungs; summed volume and open interest).

Wire it in `markets.cljs` with as few changed lines as possible: `build-outcome-spec` and `build-question-spec` merge the registry output; `build-binary-outcome-market-entry` and `build-question-market-entry` take `:title`, side labels, option labels, `:outcome-category`, `:outcome-shape`, `:outcome-clock`, `:outcome-venue`, `:outcome-rules` from it; `build-outcome-markets` groups binaries through `events/group-standalone`. Questions also expose their fallback as `:fallback-option` so views can show "Other". Extend `market_live_projection.cljs` to patch `:mid`/`:midRaw` from `midPx`. Persist the new fields through both caches with one helper, `hyperopen.outcomes.domain.display/cache-fields`, so a cold start does not flash raw names. Add `:ladder` to the "multi-answer" checks (`outcome-market-key?` learns the `ladder:` prefix).

Acceptance for milestone 1: `npm test` passes, including new `test/hyperopen/outcomes/domain/*_test.cljs` suites over a fixture cut from the real feed (`test/hyperopen/outcomes/test_support/fixture.cljs`) that assert, among others, `"Detroit Pistons vs Washington Wizards"` with sides `DET`/`WAS`, the Fed question options `Hike`/`Hold`/`Cut` plus fallback `Other`, a BTC price-touch ladder with four rungs, 0 raw `template:` strings across every built market title, option label and side label, and the category counts Sports 88 / Economics 1 / Companies 2 for the full fixture.

Milestone 2 — the selector's Outcome tab. `settings.cljs` gains the new tab keys; `query.cljs` `tab-match?` filters them by `:outcome-category`; `controls.cljs` `outcome-subtab-row` renders the six chips with event counts. New `src/hyperopen/views/asset_selector/outcome_list.cljs` renders, for any outcome tab, sections in this order: games by local day and competition ("Today · NBA", "Thu Nov 12 · UEFA Nations League"), "Futures" (field questions), then "Crypto", "Stocks & commodities", "Economics", "Companies", "Other". A row shows a meta line (competition or category, venue chip, clock), the title, and the answers in the shape's form: head-to-head shows both teams with chance; pick-one, field and ladder show the top three answers plus "+N more"; yes/no shows Yes and No. Every answer is a `<button>` whose click dispatches `[[:actions/select-asset <side coin>]]` (side 0 for an answer's Yes or a team, side 1 for No); the row itself selects the event's default side. Right-hand columns show 24h volume and the clock. `layout.cljs` swaps the virtualized list and sort header for this list when an outcome tab is active (desktop and mobile), keeping search, favorites (star per event) and the existing keyboard handler over the ordered event keys. `query.cljs` `selector-visible-market-coins` subscribes the first rendered events' side-0 coins for outcome tabs.

Acceptance for milestone 2: view tests for each row shape and for answer-button actions; in the browser the Outcome tab lists readable events, Sports shows the NBA/NFL/soccer events, and clicking "WAS" on the Pistons row opens the trade page with WAS selected.

Milestone 3 — the trade page. New `src/hyperopen/views/active_asset/outcome_row.cljs` (and a small `outcome_header_vm.cljs`) replaces the body of `desktop-outcome-active-asset-row`: category › competition with venue chip, the title, per-side chances for head-to-head ("DET 82% · 18% WAS") or the selected answer's chance, the clock, "24h +0.2 pts" (or "since open" for daily markets), volume and open interest (shares × $1). The rules tooltip content comes from the registry's `:outcome-rules`. New `src/hyperopen/views/trade/outcome_ticket.cljs` replaces the outcome branch of `render-order-form`: Market / Limit tabs, "Pick a side" buttons (price in cents and chance; clicking dispatches the existing `set-order-outcome-side` command and `set-order-side :buy`), the amount input in USDC with $10 / $50 / $100 / Max presets (existing size input in quote mode), a limit price typed in cents with Bid / Mid / Ask fills, a summary (shares, average price, cost, "Pays if DET wins", profit if right, fee), and a submit label such as "Buy DET · $99.92". When the person holds shares of the selected outcome a Buy / Cash out switch appears; Cash out sells held shares at the bid with 25% / 50% / All presets. With no two-sided book the Market tab is disabled and explained. A Rules / Timeline / Market details block renders below the ticket from the registry (contract id, side coins and venue live only here).

Acceptance for milestone 3: view tests for the ticket in buy, limit, cash-out and no-market states; in the browser, picking WAS on the Pistons game flips the book and the ticket says how many WAS shares $100 buys and what they pay.

Milestone 4 — multi-answer events. In the ticket, pick-one, field and ladder markets show an option list (label, chance, 24h pts, Yes ¢ and No ¢ buttons) instead of the dropdown; choosing Yes or No on an option dispatches the option's side coin. Pick-one and field events show a stacked chance bar, a fallback "Other" row ("Not traded" when untraded) and, when listed chances add up to more than 105%, a warning with an "I understand these prices may be stale" checkbox that must be ticked before submit. Ladders order rungs by threshold, show the underlying's current price and each rung's distance from it (↑ / ↓ for price-touch rungs), a venue chip per rung when venues differ, and flag a rung priced above the rung below it.

Acceptance for milestone 4: view tests for option rows, the stale-sum guard and ladder ordering; in the browser the Fed decision shows Hike / Hold / Cut / Other with Yes and No on each.

Milestone 5 — positions. `projections/outcomes.cljs` and `tabs/outcomes.cljs` show Market (title, category · venue), Your side ("DET to win", "No on Hike", "Yes"), Shares (integer), Avg → bid (cents), Value now (shares × best bid, falling back to mark), Pays if right (shares × $1), P&L, Resolves (clock) and a Cash out action that opens the ticket in Cash out mode. The ticket's holdings line reads "You hold 122 DET · 0 WAS".

Acceptance for milestone 5: projection and view tests; no `#<digits>` coin id appears as a label anywhere in the Outcomes tab or ticket.

Milestone 6 — validation. Run `npm run gates`, add `tools/playwright/test/outcome-markets.spec.mjs` covering the selector rows, answer → ticket side, and payout copy, and run the governed browser-QA passes at 375, 768, 1280 and 1440 px.

## Concrete Steps

All commands run from the worktree root (`/Users/barry/projects/hyperopen/.claude/worktrees/outcome-markets-design-e36605`, or any checkout of this branch).

    npm run setup:worktree          # links node_modules; required once per worktree
    npm test                        # full ClojureScript suite (generates the runner first)
    npm run lint:namespace-sizes    # after each milestone
    npm run lint:hiccup && npm run lint:style-keys && npm run lint:theme-colors
    npm run gates                   # check + test + test:websocket, one PASS/FAIL matrix

For browser checks, build and serve the worktree's own app (port 8080 may belong to another checkout):

    npm run css:build
    npx shadow-cljs --force-spawn compile app
    # serve resources/public with an SPA fallback on a free port, then open /trade

## Validation and Acceptance

The change is accepted when all of the following hold on live data:

1. The Outcome tab shows no string that starts with `template:` and no `#<digits>` coin id; its chips read All, Sports, Crypto, Stocks & commodities, Economics, Companies, and Sports is not empty.
2. "Detroit Pistons vs Washington Wizards" (or whichever NBA game is live) appears once, with DET and WAS answers whose chances add up to about 100%.
3. Clicking the second team's answer opens the trade page with that team selected; the ticket shows "Pick a side", an amount in USDC, the share count, and "Pays $N.00 if <team> wins".
4. A contract with no trades shows "Not traded" (never 50%), and a book with no two-sided quotes shows "No market" and disables the Market tab.
5. The Fed decision shows Hike, Hold, Cut and Other with Yes and No prices on each.
6. A BTC price-touch ladder appears as one row with several rungs; on the trade page the rungs sit in strike order with distance from the live BTC price.
7. The account Outcomes tab names the side and shows shares, value now and payout.
8. `npm run gates` passes; the new Playwright spec passes; the browser-QA matrix is reported as PASS/FAIL/BLOCKED per pass and width.

## Idempotence and Recovery

Every step is additive and can be re-run. The only persisted data that changes shape is the asset-selector markets cache and the active-market cache in browser storage; both are rebuilt from the live catalog on every boot, and missing new fields degrade to the registry's fallbacks rather than failing. To back out, revert the branch; no migration is needed.

## Artifacts and Notes

Title and label rules (examples from the live feed):

    head-to-head    "{participantA} vs {participantB}"            sides {shortNameA} / {shortNameB}
    pick one        "{participantA} vs {participantB}"            options {participant} · Draw · Other
    field           "{competition} champion {season}"             options {participant} · Other
    Fed decision    "Fed — {decisionLabel} decision"              options Hike · Hold · Cut · Other
    binaryPrice     "{asset} above {threshold}"                   ladder title "{asset} above…", rung "{threshold}"
    priceTouch      "{asset} reaches {target}"                    ladder title "{asset} reaches…", rung "{target}"
    IPO market cap  "{company} first-day market cap ≥ {cap}"      ladder title "{company} first-day market cap", rung "≥ {cap}"
    IPO confirmed   "{company} IPO"
    native daily    "{asset} above {targetPrice}"                 range question "{asset} price range", buckets
                                                                  "Below $72,967", "$72,967 – $75,945", "Above $75,945"

Number rules: chance is an integer percent clamped to "<1%" and ">99%"; prices are cents with one decimal in lists and buttons and three decimals in the book and limit field; change is percentage points; size is whole shares; each share pays $1.00; open interest is shares outstanding × $1.

## Interfaces and Dependencies

No new libraries. New namespaces and their public functions at the end of milestone 1:

    hyperopen.outcomes.domain.templates
      (parse-params description) -> {"participantA" "Detroit Pistons", ...}
      (template-id name params) -> keyword such as :sports-contest-winner, :fallback, :recurring-price-binary
      (describe-outcome {:keys [name description venue outcome-id side-specs]}) -> {:template :shape :title :side-labels
                                                                                   :side-long-labels :option-label :category
                                                                                   :subcategory :clock :rules :venue :subject}
      (describe-question {:keys [name description question-id]}) -> same keys, :shape :pick-one or :field
    hyperopen.outcomes.domain.clock
      (clock-spec template params) -> {:kind :at-ms :deadline-ms} or nil
      (clock-view clock now-ms opts) -> {:phase :label :absolute}
    hyperopen.outcomes.domain.quotes
      (side-quote side) (book-quote best-bid best-ask mark) (chance-text quote) (cents-text price opts)
      (points-text delta) (walk-asks levels usdc) (payout shares cost)
    hyperopen.outcomes.domain.events
      (ladder-key spec) (group-standalone binary-entries) -> entries with ladders merged

Views added in later milestones: `hyperopen.views.asset-selector.outcome-list`, `hyperopen.views.active-asset.outcome-row`, `hyperopen.views.trade.outcome-ticket`.
