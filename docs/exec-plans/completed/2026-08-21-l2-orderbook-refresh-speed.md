# Make the L2 orderbook refresh at Hyperliquid frontend speed

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md` (the full ExecPlan writing contract) and `/hyperopen/docs/PLANS.md` (the public planning entry point).

## Purpose / Big Picture

Today the order book ladder on the trade route (`/trade/BTC` and every other market) visibly redraws about once every five seconds. The official Hyperliquid frontend at `app.hyperliquid.xyz` redraws the same ladder about twice a second. Traders reading our book are looking at prices that are, on average, two and a half seconds stale, and the "Spread" row and top-of-book sizes lurch rather than flow.

After this change, the ladder updates roughly every 550 milliseconds — about nine times more often — with the same twenty price levels per side it shows today, and the depth bars snap to their new widths instead of easing over 300 milliseconds. A person can see this working by opening the trade route and watching the top few rows change several times per second instead of once every few seconds.

The cause is not our rendering code. It is that we subscribe to the wrong websocket channel. Hyperliquid's publicly documented `l2Book` channel is server-throttled to roughly one push every 5.4 seconds. The official frontend does not use it; it uses an undocumented `l2` channel that sends one snapshot followed by a stream of compressed incremental updates at roughly 550 millisecond intervals. This plan moves our ladder onto that faster channel while keeping the existing `l2Book` subscription alive as a safety net, so that if the undocumented channel ever disappears the book keeps working exactly as it does today.

## Context References

Public refs:

- Direct user request, 2026-08-21: "I want you to do an investigation into why our l2 orderbook is not refreshing and updating at nearly the same speed as on hl.xyz. Get to the root cause of this", followed by "Based on your findings, I want you to create an execution plan and implement it."
- User decisions captured during planning on 2026-08-21, recorded in full in the `Decision Log` below: use the `l2` channel with an `l2Book` fallback; limit scope to the feed switch plus the depth-bar transition; leave the coarse price-aggregation modes on `l2Book`.

Repo artifacts:

- `/hyperopen/AGENTS.md` — the operating contract this work follows, including the required validation gates.
- `/hyperopen/docs/BROWSER_TESTING.md` — browser-tool routing for the verification steps below.
- `/hyperopen/src/hyperopen/websocket/BOUNDARY.md` — the websocket namespace boundary rules that constrain where new code may live.

Local scratch refs (non-authoritative):

- None.

## Progress

- [x] (2026-08-21 08:20Z) Measured Hyperliquid's live websocket channel cadences and established that `l2Book` is server-throttled, not client-limited.
- [x] (2026-08-21 08:35Z) Captured the official frontend's own subscribe frames and identified the `l2` channel and its wire format.
- [x] (2026-08-21 08:55Z) Confirmed the slow cadence reproduces inside our running dev build (0.20 book updates per second measured at ingest).
- [x] (2026-08-21 09:40Z) Spiked delta reconstruction and validated the apply-order semantics against `l2Book` truth frames on two coins (19 of 19 exact matches).
- [x] (2026-08-21 10:05Z) Milestone 1 — added `src/hyperopen/websocket/orderbook_l2.cljs` and `test/hyperopen/websocket/orderbook_l2_test.cljs`.
- [x] (2026-08-21 10:15Z) Milestone 2 — added `inflate-raw-base64-supported?` and `inflate-raw-base64!` to `src/hyperopen/platform.cljs`.
- [x] (2026-08-21 10:35Z) Milestone 3 — wired the dual subscription and the `l2` handler in `src/hyperopen/websocket/orderbook.cljs`; registered the topic in `domain/policy.cljs` and `domain/model.cljs`.
- [x] (2026-08-21 10:50Z) Milestone 4 — replaced the depth-bar transition in `src/hyperopen/views/l2_orderbook/styles.cljs`; updated the guard in `test/hyperopen/views/l2_orderbook_view_test.cljs` and added `test/hyperopen/views/l2_orderbook/depth_transition_test.cljs`.
- [x] (2026-08-21 11:05Z) Milestone 5 — `npm run gates` reports 34/34 PASS.
- [x] (2026-08-21 11:25Z) Milestone 5 — verified live against mainnet: 1.86 book updates/s, p50 gap 532ms, 20 levels/side, coarse-precision round trip intact, console clean.

## Surprises & Discoveries

- Observation: Hyperliquid's documented `l2Book` websocket channel is throttled server-side to roughly one push every 5.4 seconds, regardless of market activity. This is not a property of our client.
  Evidence: A 40-second measurement against `wss://api-ui.hyperliquid.xyz/ws` for BTC. `{"type":"l2Book","coin":"BTC"}` delivered 8 messages, 0.20 per second, median inter-arrival gap 5434 ms. All coins measured (BTC, ETH, SOL, HYPE) arrived in lockstep with identical gaps, which is the signature of a fixed server-side throttle rather than market quiet.

- Observation: The two Hyperliquid websocket hosts behave identically. Only the channel matters.
  Evidence: `{"type":"l2Book","coin":"BTC"}` measured 0.23 per second on `api.hyperliquid.xyz` and 0.23 per second on `api-ui.hyperliquid.xyz`. `{"type":"l2","c":"BTC"}` measured 1.83 per second on both.

- Observation: `nSigFigs`, the one subscription parameter our code does forward, has no effect on cadence.
  Evidence: `{"type":"l2Book","coin":"BTC","nSigFigs":5}` measured a 5323 ms median gap against 5434 ms without it.

- Observation: The official frontend uses a channel that is absent from Hyperliquid's public API documentation.
  Evidence: Patching `WebSocket.prototype.send` on `app.hyperliquid.xyz` and forcing a client-side route change captured its own outbound frames verbatim, including `{"method":"subscribe","subscription":{"type":"l2","c":"ETH"}}` and `{"method":"unsubscribe","subscription":{"type":"l2","c":"BTC"}}`.

- Observation: The `l2` channel keeps full twenty-level depth while matching `fast:true` for speed, so no dual top-of-book merge is needed.
  Evidence: A 40-second BTC comparison — `l2` delivered 1.85 messages per second at 546 ms median with 20 levels per side and 557 average bytes; `{"type":"l2Book","coin":"BTC","fast":true}` delivered 1.85 per second at 550 ms median but only 5 levels per side.

- Observation: The `l2` channel silently ignores `nSigFigs` and rejects the `coin` key.
  Evidence: `{"type":"l2","c":"BTC","nSigFigs":3}` produced byte-identical cadence and identical top-of-book prices to `{"type":"l2","c":"BTC"}`. `{"type":"l2","coin":"BTC"}` returned `{"channel":"error","data":"Error parsing JSON into valid websocket request: ..."}`.

- Observation: The delta frames are base64-wrapped raw DEFLATE, which browsers can decode natively with no new dependency.
  Evidence: `zlib.inflateRawSync` decodes them in Node, and `new DecompressionStream('deflate-raw')` decodes them in the browser. Verified from our own page origin at `http://localhost:8081`, sustaining 1.87 messages per second.

- Observation: Delta application order is not symmetric. Removals must be applied before upserts, and the difference is not subtle.
  Evidence: A reconstruction spike maintained two books in parallel from the same delta stream and compared each against `l2Book` snapshots at identical `time` values. Remove-first matched 8 of 8 on BTC and 11 of 11 on ETH. Upsert-first matched 0 of 8 on BTC and 2 of 11 on ETH.

- Observation: The reconstructed book stays at exactly twenty levels per side, and `l` entries never carry a zero size — removal is expressed only through `r`.
  Evidence: Across 192 deltas on two coins, minimum and maximum levels per side were both 20, and the count of `l` entries with size 0 was 0.

- Observation: The existing websocket freshness threshold for `l2Book` is 5000 ms while the channel's real cadence is 5434 ms, so the book was being marked delayed almost continuously.
  Evidence: `src/hyperopen/websocket/health.cljs:6` sets `{"l2Book" 5000}`; measured median gap is 5434 ms. `src/hyperopen/views/l2_orderbook/panel.cljs:57` turns that into `depth-dimmed?`, which applies `opacity-90` to the depth body. After this change the book updates every ~532 ms, so the cue reads live and the dimming stops.

- Observation: `with-redefs` cannot stub an asynchronous boundary. It restores the original binding when its body exits, which happens before any promise callback runs, so a stubbed decoder was silently swapped back for the real one mid-test.
  Evidence: A first version of `l2-delta-applies-to-the-seeded-sides-test` redefined `platform/inflate-raw-base64!` and asserted the sides were unchanged: `actual: (not (= [[{:px "100", :sz "1"} {:px "98", :sz "5"}] ...] [[{:px "100", :sz "1"} {:px "99", :sz "2"}] ...]))`. The real decoder had rejected the stub's placeholder string and the `.catch` swallowed it. Resolved by injecting the decode function instead -- see the Decision Log.

- Observation: `setInterval` is clamped to roughly 1000 ms in a background tab, which silently floors any polling-based rate measurement.
  Evidence: A 20 ms poll of the book timestamp reported exactly `updatesPerSec: 0.97` with `gapMs_p50: 1000` -- the clamp, not the feed. Re-measuring with `add-watch` on `orderbook-state`, which fires synchronously on every `swap!` and is immune to the clamp, reported 1.86 updates/s with `gapMs_p50: 532`. The original 0.20/s baseline was unaffected because its real 5001 ms gaps are far longer than the 1000 ms clamp.

## Decision Log

- Decision: Move the ladder to the undocumented `l2` channel rather than to the documented `l2Book` `fast:true` flag.
  Rationale: Both reach the same ~550 ms cadence, but `fast:true` returns only 5 levels per side, which would force a dual subscription that merges a fast top-5 over a slow 20-deep book. `l2` delivers the full 20 levels at a third of the current bytes and is what the official frontend actually ships, so it is the better-tested path in practice. The user chose this option explicitly.
  Date/Author: 2026-08-21, Geronimo (user decision), planned by Claude.

- Decision: Keep the existing `l2Book` subscription running alongside `l2` rather than building an explicit fallback timeout state machine.
  Rationale: This was originally scoped as "fall back if `l2` fails", but keeping both streams subscribed achieves the same protection with far less machinery and buys three things at once. First, the fallback becomes implicit: if `l2` never delivers — because Hyperliquid removes the channel, or the browser lacks `DecompressionStream('deflate-raw')` — the `l2Book` stream alone keeps the book working exactly as it does today, with no failure detection needed. Second, the periodic `l2Book` snapshot is a free resynchronisation point for the delta-reconstructed book. Third, and decisively, it means the websocket health and freshness surface needs no changes at all: the freshness cue in `src/hyperopen/views/l2_orderbook/panel.cljs` stays keyed on the `"l2Book"` topic, and `src/hyperopen/websocket/health.cljs` needs no new descriptor matcher. The extra cost is 1593 bytes every 5.4 seconds, which is negligible.
  Date/Author: 2026-08-21, Claude.

- Decision: Route the `"l2"` topic to the `:lossless` tier, not the `:market` tier.
  Rationale: The `:market` tier coalesces envelopes into a map keyed by `[topic coin]` and flushes after a 16 ms window, keeping only the last envelope per key (`src/hyperopen/websocket/application/runtime_reducer.cljs:396`). That is safe for `l2Book`, whose frames are complete snapshots, but it would be silently destructive for `l2`, whose frames are deltas that must all be applied in order. Compounding this, `market-coalesce-key` (`src/hyperopen/websocket/domain/model.cljs:150`) reads the coin from the payload, and `l2` delta frames carry no coin outside the compressed blob — so every coin would collapse onto the single key `["l2" nil]`. The `:lossless` tier dispatches immediately with no coalescing, which is exactly the required semantics. As a side benefit this also removes the 16 ms coalesce delay from our hot path.
  Date/Author: 2026-08-21, Claude.

- Decision: Apply an incoming `l2Book` snapshot only when its `time` is greater than or equal to the stored book's timestamp.
  Rationale: Once `l2` deltas are flowing, our book is usually newer than the 5-second `l2Book` snapshot. Applying the snapshot unconditionally would drag the ladder backwards in time roughly every 5 seconds, producing a visible stutter. The monotonic guard means `l2Book` acts as bootstrap and fallback when `l2` is not flowing, and is ignored when it would be a regression.
  Date/Author: 2026-08-21, Claude.

- Decision: Leave the coarse price-aggregation modes (`:sf2`, `:sf3`, `:sf4`) on `l2Book`.
  Rationale: The `l2` channel ignores `nSigFigs`, so those modes cannot ride on it. Aggregating client-side is possible but only twenty raw levels are available, which would bucket into a sparse and worse-looking coarse ladder. The default `:full` mode — what almost every user sees — gets the speed win, and the coarse modes keep exactly today's behavior. The user chose this option explicitly. The consequence to accept is that switching to a coarse step is a visible speed cliff.
  Date/Author: 2026-08-21, Geronimo (user decision), planned by Claude.

- Decision: Put the delta-application logic in a new namespace `src/hyperopen/websocket/orderbook_l2.cljs` rather than extending `src/hyperopen/websocket/orderbook_policy.cljs`.
  Rationale: `orderbook_policy.cljs` is already 280 lines against the repository's 500-line default namespace budget enforced by `dev/check_namespace_sizes.clj`, and the delta logic is a self-contained concern with its own test surface. A separate namespace keeps both files under budget with no size exception needed.
  Date/Author: 2026-08-21, Claude.

- Decision: Leave `default-stream-stale-threshold-ms` in `src/hyperopen/websocket/health.cljs` unchanged, so the `l2` stream reports the benign `:n-a` status rather than participating in staleness alarms.
  Rationale: `derive-stream-status` returns `:n-a` for any topic without a configured threshold, and `transport-expected-traffic?` ignores such streams. That is exactly the posture the fallback design wants: `l2Book` remains the health-bearing stream, and if Hyperliquid ever removes the undocumented `l2` channel the app degrades quietly instead of flapping the connection-health surface and triggering the auto-recover resubscribe machinery over a channel that is legitimately gone. Worth revisiting only if `l2` ever becomes the sole book source.
  Date/Author: 2026-08-21, Claude.

- Decision: Inject the inflate function into `create-l2-data-handler` rather than calling `platform/inflate-raw-base64!` directly.
  Rationale: Discovered while writing the tests. Decoding is asynchronous, and `with-redefs` restores the original binding when its body exits — which happens before the promise chain resolves — so a redefined stub was silently replaced by the real decoder mid-test. Injection also matches how the rest of the websocket layer takes its collaborators (`subscriptions-runtime` takes `subscribe-orderbook-fn`, and so on) and keeps the side-effecting boundary substitutable.
  Date/Author: 2026-08-21, Claude.

- Decision: Scope this plan to the feed switch and the depth-bar transition, deliberately excluding the measured client-side scheduling latency.
  Rationale: The end-to-end client pipeline adds about 46 ms per update — a 16 ms market-coalesce timer, a guaranteed extra animation frame from a `requestAnimationFrame` registered inside another `requestAnimationFrame`, and a render-snapshot rebuild. Against the current 5409 ms update period that is 0.85% of the latency, and against the post-change 596 ms period it is still under 8%. Those are real defects worth fixing, but they are invisible next to the feed and they touch the shared websocket runtime and the app render loop, which is a much wider regression surface. The user chose to keep this change focused.
  Date/Author: 2026-08-21, Geronimo (user decision), planned by Claude.

## Outcomes & Retrospective

The order book ladder now refreshes 9.3 times more often, which was the point of the work.

Measured on the running dev build against Hyperliquid mainnet, sampling via `add-watch` on
`hyperopen.websocket.orderbook/orderbook-state` so the reading is not floored by the
background-tab timer clamp:

    before   0.20 updates/s   p50 gap 5001 ms
    after    1.86 updates/s   p50 gap  532 ms   p90 605 ms   max 732 ms

The reconstructed book stayed correct throughout: 20 levels per side, bids strictly
descending, best bid 77679 against best ask 77680 for a coherent $1 spread, and no console
errors across the measurement window.

The precision dropdown round trip behaves as designed. Switching BTC to three significant
figures sent `unsubscribe {"type":"l2","c":"BTC"}` alongside the aggregated
`subscribe {"type":"l2Book","coin":"BTC","nSigFigs":3}`, discarded the delta sides, and
rendered a correct $100-step ladder (77600 / 77500 / 77400 / 77300). Switching back
resubscribed `l2` and reseeded the sides.

`npm run gates` reports 34/34 PASS, covering 6690 tests and 36129 assertions.

Complexity: this increased overall complexity, and unavoidably so. The application went from
one book source to two, and gained an asynchronous decode step with an ordering constraint
that did not exist before. Three choices kept the increase as small as it could be. Keeping
`l2Book` subscribed rather than building an explicit fallback state machine removed an entire
category of failure detection and left the websocket health surface untouched. Routing `l2` to
the existing `:lossless` tier reused the exact delivery semantics deltas need instead of
adding a new one. Putting the delta logic in its own pure namespace kept it fully unit-testable
without touching the network. The residual risk is the honest one: `l2` is undocumented and
could change without notice. That risk is bounded by the retained `l2Book` stream, which means
the failure mode is a return to today's speed rather than a broken order book.

What remains, deliberately out of scope and recorded here so it is not lost: roughly 46 ms of
client-side scheduling latency in the ingest-to-paint path -- the 16 ms market-coalesce timer
at `src/hyperopen/websocket/application/runtime_reducer.cljs:396`, a guaranteed extra animation
frame because `src/hyperopen/runtime/bootstrap.cljs:66` requests a frame from inside the frame
callback scheduled at `src/hyperopen/websocket/market_projection_runtime.cljs:253`, and a render
snapshot at `src/hyperopen/websocket/orderbook_policy.cljs:265` that the view cannot use and so
rebuilds on every render. At the new 532 ms cadence that is still under 9% of the update period.
Two further items worth a look someday: `bbo` would put the touch at 79 ms if the ladder and the
top of book are allowed to tick at different rates, and the coarse precision modes remain a
speed cliff at 5.4 s that a user can hit from the dropdown without warning.

## Context and Orientation

This section assumes no prior knowledge of this repository.

Hyperopen is a ClojureScript single-page trading application. It is built with `shadow-cljs`, renders with Replicant (a virtual-DOM library that turns nested Clojure vectors called "hiccup" into DOM), and manages state in a single atom — a mutable reference cell holding one big immutable map — referred to throughout as "the store". Actions are dispatched through Nexus, a library that maps a keyword like `:effects/subscribe-orderbook` to a handler function.

All market data arrives over a single websocket connection to Hyperliquid. The URL lives at `src/hyperopen/config.cljs:6` as `:ws-url "wss://api.hyperliquid.xyz/ws"`.

The websocket stack is layered, and the layers matter for this change:

- `src/hyperopen/websocket/infrastructure/transport.cljs` owns the actual browser `WebSocket` object.
- `src/hyperopen/websocket/acl/hyperliquid.cljs` is the "anti-corruption layer" — it parses raw JSON text into an internal map called an "envelope", shaped `{:topic "l2Book" :tier :market :ts 123 :payload {...}}`. The word "topic" here means the value Hyperliquid puts in the JSON field `"channel"`.
- `src/hyperopen/websocket/domain/policy.cljs` assigns each topic to a "tier". There are two tiers. The `:market` tier is allowed to drop messages: envelopes are collected into a map keyed by `[topic coin]` and flushed 16 milliseconds later, so if two envelopes for the same coin arrive inside that window only the second survives. The `:lossless` tier dispatches every envelope immediately and never drops.
- `src/hyperopen/websocket/application/runtime_reducer.cljs` is the pure state machine implementing that tiering. The `:market` branch is at line 396; the `:lossless` branch is at line 410.
- `src/hyperopen/websocket/client.cljs` exposes `register-handler!`, which attaches a function to a topic string, and `send-message!`, which queues an outbound frame.

The order book specifically:

- `src/hyperopen/websocket/orderbook.cljs` holds a module-local atom `orderbook-state` with `{:subscriptions {coin -> subscription-map} :books {coin -> book}}`. `subscribe-orderbook!` sends the subscribe frame; `create-orderbook-data-handler` receives `l2Book` payloads, builds a book, and pushes it into the store at the path `[:orderbooks coin]`. `init!` registers that handler for the `"l2Book"` topic.
- `src/hyperopen/websocket/orderbook_policy.cljs` is pure: it sorts levels, computes cumulative totals, formats display strings, and builds the subscription map in `build-subscription` at line 277.
- `src/hyperopen/orderbook/price_aggregation.cljs` maps the price-precision dropdown to a subscription fragment: `:full` maps to `{}`, and `:sf4`/`:sf3`/`:sf2` map to `{:nSigFigs 4}` and so on.
- `src/hyperopen/views/l2_orderbook/panel.cljs` renders the ladder, and `src/hyperopen/views/l2_orderbook/depth.cljs` renders each row including the horizontal depth bar behind it.
- `src/hyperopen/views/l2_orderbook/styles.cljs:15-19` defines the CSS classes applied to that depth bar.

A "level" is one price point in the book, shaped `{:px "77368.0" :sz "0.00129" :n 1}` where `px` is price, `sz` is size, and `n` is the number of orders. A "side" is either the bid side (buyers, sorted highest price first) or the ask side (sellers, sorted lowest price first). Hyperliquid sends both sides as a two-element array: index 0 is bids, index 1 is asks.

### What the `l2` channel actually sends

This is the knowledge the rest of the plan depends on. It was established by measurement, not documentation, because Hyperliquid does not document this channel.

Subscribing is `{"method":"subscribe","subscription":{"type":"l2","c":"BTC"}}`. The key must be `c`. Sending `coin` instead returns an error frame.

The first message back is a full snapshot, shaped exactly like an `l2Book` message but nested one level deeper under an `s` key:

    {"channel":"l2","data":{"s":{"coin":"BTC","time":1787316486464,
       "levels":[[{"px":"77310.0","sz":"1.57151","n":17}, ...20 bids...],
                 [{"px":"77311.0","sz":"0.00014","n":1},  ...20 asks...]]}}}

Every subsequent message is a compressed delta:

    {"channel":"l2","data":{"c":"XZS9jhcxDMTfJXVk+dvOv4RXoDttRUuBgO50705OQolNuftbeyaT0b6P7+M1vnz7Oub4M14UGUKuGRg8x4/xent7Hz/3J7HfI+CY4/d+IlBMGR/zQFwH7s9cPRq1SveoNaqHEkhu6UblUAQRyb6ZC41Q6q7oUIJwx66LhzIsXl5ned0TEdhyy+KKV55ZhHSKPhuHKiwL77N+KDm4B3esByMI6eImLIcypDF3YT7UgIS4hsWLKs0VfTMemrBS16q6eeNASP1vc9Y40FaTzZuGAaLJln1mKda1FVvYaPPbLJJjC4HYrFMtdNtCKqaFtt6/yiIYknbqZdY4o7WDrmsCcWJtm+uB2bG3kmpYiNJnGY8ugZNjTUv4piGQvKS5Yi6zHNHPyzcrBdEVzTPXrFyihcFWFkdwraTwTYpBlbPTKKNu0oojfJNioCCrtRK+SRHoMmyOBc9mB0YOr3cgN6kAF/u8v+eZ49fnb4ts6pTJu2ikcz+RT4pJOWk9z8f"}}

The value of `c` is base64 text. Decoding the base64 yields bytes that are raw DEFLATE — that is, a DEFLATE stream with no zlib and no gzip header. In Node this is `zlib.inflateRawSync`; in a browser it is `new DecompressionStream('deflate-raw')`. Attempting `zlib.inflateSync` on it fails with "incorrect header check", which is how the format was identified.

The decompressed text is JSON using short keys:

    {"c":"BTC","t":1787316532470,
     "l":[[{"p":"77392.0","s":"5.74322"}, ...],[{"p":"77393.0","s":"4.29395"}, ...]],
     "r":[[19],[1]]}

Here `c` is the coin, `t` is a millisecond timestamp, `l` is a two-element array of levels to insert-or-update per side using short keys `p` for price and `s` for size, and `r` is a two-element array of *indices* to remove per side. The indices are positions in the current sorted side — index 19 on the bid side means "remove the twentieth-best bid".

The correct application order, established by the spike and non-obvious, is:

1. Remove, per side, every index listed in `r` from the currently sorted side.
2. Then insert-or-update, per side, every entry in `l`, matching on price.
3. Then re-sort each side — bids descending by price, asks ascending by price.

Doing the upserts before the removals produces a wrong book, because the removal indices refer to the pre-upsert ordering. The spike measured this directly: applying removals first matched the authoritative `l2Book` snapshot 19 times out of 19 across BTC and ETH; applying upserts first matched 2 times out of 19.

Two further properties held across all 192 observed deltas and are relied on below: the book stays at exactly twenty levels per side, and entries in `l` never carry a size of zero, so a zero size never needs to be interpreted as a deletion.

Note that the delta frame carries no `n` (order count) field, only price and size. Our render path does not use `n`, so this is not a loss — but it means a delta-updated level will not carry `:n`, and nothing may depend on its presence.

## Plan of Work

The work proceeds in five milestones. Each one leaves the repository in a working, testable state.

### Milestone 1 — the pure delta namespace

Create `src/hyperopen/websocket/orderbook_l2.cljs`. This namespace is pure: it must not touch the network, the DOM, timers, or any atom. It contains the subscription builder for the `l2` channel and the delta-application function, plus the small parsing helpers they need.

It needs:

- `build-l2-subscription`, taking a coin string and returning `{:type "l2" :c coin}`.
- `l2-eligible?`, taking the aggregation config that `price-aggregation/mode->subscription-config` returns and answering whether the fast channel may be used. It returns true exactly when the config carries no `:nSigFigs` — that is, when the user is on the default `:full` precision mode.
- `snapshot-levels`, taking a decoded `l2` snapshot payload and returning `[bids asks]` in the same level shape `l2Book` produces, so the existing `orderbook-policy/build-book` can consume it unchanged.
- `apply-delta`, taking the current `[bids asks]` and a decoded delta map and returning the next `[bids asks]`, implementing remove-then-upsert-then-sort exactly as described above.
- `parse-delta`, taking the decompressed JSON string and returning a Clojure map with the short keys normalised.

Then add `test/hyperopen/websocket/orderbook_l2_test.cljs` covering: the subscription shape; eligibility for `:full` versus a `nSigFigs` config; a delta that only upserts; a delta that only removes; a delta that does both, asserting the remove-before-upsert ordering by constructing a case where the two orders disagree; sort order on both sides; and a real captured frame reconstructed against its real captured `l2Book` truth.

Acceptance: `npm test` passes with the new suite included.

### Milestone 2 — the decode seam

Raw-DEFLATE decoding is a side effect against a browser API, so per `/hyperopen/AGENTS.md` it belongs at the platform boundary, which is `src/hyperopen/platform.cljs`. Add two functions there:

- `inflate-raw-base64-supported?`, returning true when `js/DecompressionStream` exists and can be constructed with `"deflate-raw"`. This must not throw in environments that lack it, including the Node test runner.
- `inflate-raw-base64!`, taking a base64 string and returning a JavaScript Promise that resolves to the decompressed text.

These are the only new browser-API calls in this change.

Acceptance: the project compiles and `npm test` still passes. The functions are exercised for real in Milestone 5.

### Milestone 3 — dual subscription and the `l2` handler

First, register the topic. In `src/hyperopen/websocket/domain/policy.cljs`, add `"l2" :lossless` to `default-channel-tier-policy`. In `src/hyperopen/websocket/domain/model.cljs`, add `"l2" :market_data` to `default-topic->group` so diagnostics group it with the other market streams.

Then change `src/hyperopen/websocket/orderbook.cljs`:

- `subscribe-orderbook!` keeps sending its existing `l2Book` frame unchanged. Additionally, when `orderbook-l2/l2-eligible?` is true for the requested aggregation config and `platform/inflate-raw-base64-supported?` is true, it also sends the `l2` frame and records it under a new `:l2-subscriptions` key in `orderbook-state`.
- `unsubscribe-orderbook!` unsubscribes whichever of the two it recorded and clears both.
- A new handler registered for the `"l2"` topic receives snapshot frames and delta frames. Snapshot frames go straight through `orderbook-policy/build-book` and are stored. Delta frames are decompressed asynchronously; because `inflate-raw-base64!` returns a Promise, deltas for a coin must be chained through a per-coin promise so that they are applied in arrival order. After decoding, the delta is applied to the coin's current sides, rebuilt through `build-book`, and pushed to the store through the same `market-projection-runtime/queue-market-projection!` call the existing handler uses, so the order-form reconciliation behaviour is preserved.
- The existing `l2Book` handler gains a monotonic guard: it applies its snapshot only when the incoming `time` is greater than or equal to the stored book's `:timestamp`. This stops the 5-second snapshot from dragging a fresher delta-built book backwards.

Acceptance: `npm test` and `npm run test:websocket` pass, including new coverage that a `:full`-mode subscribe emits both frames, that a `nSigFigs` subscribe emits only the `l2Book` frame, and that a stale `l2Book` snapshot does not overwrite a newer book.

### Milestone 4 — the depth-bar transition

`src/hyperopen/views/l2_orderbook/styles.cljs:15-19` currently reads:

    (def depth-bar-transition-classes
      ["transition-all"
       "duration-300"
       "ease-[cubic-bezier(0.68,-0.6,0.32,1.6)]"])

Three things are wrong with this once the feed is fast. The duration is 300 ms, which is more than half the new 550 ms update interval. The easing curve is an "easeInOutBack" — its control points go below 0 and above 1, so the bar deliberately undershoots and then overshoots its target, which reads as a wobble on data that should be crisp. And `transition-all` transitions every animatable property rather than just the width that actually changes.

Replace it with a short, non-overshooting, width-only transition:

    (def depth-bar-transition-classes
      ["transition-[width]"
       "duration-100"
       "ease-out"])

Then update the test guard. `test/hyperopen/views/l2_orderbook_view_test.cljs:76` defines `animated-depth-bar-node?` by asserting the three old class names; it must assert the new ones, or the existing test at line 484 will pass for the wrong reason.

Acceptance: `npm test` passes, and `npm run check` still passes the typography and class-attribute gates.

### Milestone 5 — gates and live verification

Run the full gate set and verify the change in a real browser against live market data.

## Concrete Steps

Work from the repository root of this worktree, `/Users/barry/projects/hyperopen/.claude/worktrees/l2-orderbook-refresh-speed-374e79`.

Bootstrap the worktree once before running anything. A fresh worktree has no `node_modules` and `shadow-cljs` is not on `PATH`, which makes every gate fail with an opaque error that is environmental rather than a code defect:

    npm run setup:worktree

Expect either `[setup:worktree] linked node_modules -> ...` or an instruction to run `npm ci`.

After each milestone, run:

    npm test

For milestone 3 also run:

    npm run test:websocket

At the end, run the full matrix, which does not short-circuit on the first failure:

    npm run gates

Expect a PASS line for each of `npm run check`, `npm test`, and `npm run test:websocket`.

For the live verification in Milestone 5, start the dev server through the browser preview tooling rather than through a bare shell, per `/hyperopen/docs/BROWSER_TESTING.md`, and open the trade route. Note that the shadow-cljs HTTP server falls back to port 8081 when 8080 is already taken by another checkout; read the startup log to confirm which port is live.

To measure the refresh rate objectively rather than by eye, evaluate this in the page console. It polls the module-local book atom and counts distinct book timestamps, which measures ingest and is therefore unaffected by whether the tab is foregrounded:

    window.__m = { start: performance.now(), seen: new Set() };
    window.__t = setInterval(() => {
      const b = hyperopen.websocket.orderbook.get_orderbook('BTC');
      if (b) window.__m.seen.add(cljs.core.get(b, cljs.core.keyword('timestamp')));
    }, 20);

After 30 seconds, read the result:

    clearInterval(window.__t);
    window.__m.seen.size / ((performance.now() - window.__m.start) / 1000);

Before this change that number is approximately 0.20. After this change it must be approximately 1.8, a roughly nine-fold improvement. Anything below 1.0 means the `l2` stream is not being applied and the book has silently fallen back to `l2Book` — check the console for decode errors and confirm the `l2` subscribe frame was actually sent.

Finally, before concluding, stop any dev server and browser-inspection sessions that were started:

    npm run browser:cleanup

## Acceptance Criteria

The change is complete when all of the following hold.

Opening `/trade/BTC` shows an order book whose top rows visibly change several times per second rather than once every few seconds, and the measured ingest rate from the console snippet above is at least 1.5 distinct book updates per second, against a pre-change baseline of 0.20.

The ladder still shows twenty price levels per side, the Spread row still renders, and the size and total columns still respect the base/quote unit toggle.

Selecting a coarse step from the price-precision dropdown still produces a correctly aggregated ladder, at today's slower cadence, and selecting the default step returns to the fast cadence.

Depth bars move to their new widths without visible overshoot or wobble.

Disabling the fast path — by forcing `l2-eligible?` to return false, or by running a browser without `DecompressionStream` — leaves the order book working exactly as it does today, at the old cadence, with no errors in the console.

`npm run gates` reports PASS for `npm run check`, `npm test`, and `npm run test:websocket`.
