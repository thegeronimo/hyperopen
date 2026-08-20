# Add Hyperliquid advanced TWAP controls and correct the stale TWAP slice model

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. This document is maintained in accordance with `/hyperopen/docs/PLANS.md` and the detailed writing contract in `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Hyperliquid changed how TWAP orders work on 1 August 2026. A trader on the official Hyperliquid interface can now open an "Advanced Settings" panel on a TWAP order and set a **Trigger Price** (do not start working the order until the mark price reaches this level) and a **Max Price** for buys or **Min Price** for sells (stop working the order if the mark price runs past this level). The same upgrade raised the maximum running time from one day to seven days and changed the way the venue chops a parent order into child orders.

Hyperopen does not expose any of that yet. Worse, two pieces of Hyperopen encode the *old* venue mechanics as if they were still true, and one of them actively blocks orders that Hyperliquid would happily accept today. A trader who tries to work $200 over 24 hours in Hyperopen is told the order is invalid; the same order placed on Hyperliquid's own interface is accepted and executes as roughly twenty $10 child orders spaced about 76 minutes apart.

After this change, a Hyperopen user selecting the TWAP order type can set a running time in days, hours and minutes up to seven days; can open an Advanced Settings disclosure to enter a Trigger Price and a Max/Min Price; sees a slice preview that matches what the venue will actually do; is no longer blocked by a false "sub-order too small" validation error; and can see the trigger and stop levels of their live TWAPs in the account TWAP table. You can see it working by opening the trade view, choosing TWAP, entering a $200 size with a 24 hour running time, and observing that the form accepts it and previews about twenty slices — where today it refuses to submit.

**Term definitions used throughout this plan.** A *TWAP order* ("time-weighted average price") is a single large order that the exchange executes for you as a series of smaller orders spread over a chosen duration, so that your average fill price tracks the market's average price over that window rather than the price at one instant. Each of those smaller orders is a *sub-order* (Hyperliquid's docs also call them "child orders" or "clips"). The *mark price* is Hyperliquid's own reference price for an asset, computed from oracle and book inputs; it is the price the venue watches for trigger and stop conditions, and it is not necessarily the price you fill at. *Notional* means the dollar value of an order — size multiplied by price. An *action* is the JSON object Hyperopen sends to Hyperliquid's `/exchange` endpoint describing what to do; it is cryptographically signed, so the exact set and order of its keys matters. *msgpack* is the binary encoding Hyperliquid applies to that action before hashing and signing it — because it encodes a map in the order the keys were inserted, key insertion order changes the signature.

## Context References

Public refs:

- Direct user request captured in this plan: a user asked whether Hyperopen could implement "new advanced TWAP features too, like a limit price", and supplied a screenshot of the Hyperliquid order form showing a TWAP ticket with a "Running Time (5m - 7d)" control offering Day(s)/Hour(s)/Min(s) inputs, and an "Advanced Settings" checkbox revealing "Trigger Price" and "Max Price" fields. The maintainer then asked for this ExecPlan and for the live defects to be addressed in the same pass.

Repo artifacts:

- `/hyperopen/AGENTS.md` — operating contract, write authority, and the required validation gates this plan must satisfy.
- `/hyperopen/docs/PLANS.md` and `/hyperopen/.agents/PLANS.md` — the ExecPlan contract this document follows.
- `/hyperopen/docs/agent-guides/trading-ui-policy.md` — constraints on order-entry UI.
- `/hyperopen/docs/BROWSER_TESTING.md` — browser-QA routing for trade-form changes.

Local scratch refs (non-authoritative):

- None.

## Context and Orientation

Assume no prior knowledge of this repository. Hyperopen is a ClojureScript single-page trading application built with shadow-cljs and the Replicant rendering library. Everything described below lives under `/hyperopen/src/hyperopen/`, and tests live under `/hyperopen/test/hyperopen/` mirroring the source tree.

TWAP support today spans four layers.

**The trading domain** holds pure functions and venue constants with no side effects. `src/hyperopen/domain/trading/core.cljs` defines the TWAP constants — `twap-min-runtime-minutes` (5), `twap-max-runtime-minutes` (1440), `twap-frequency-seconds` (30), `twap-min-suborder-notional` (10) — and the pure helpers `twap-total-minutes`, `split-twap-total-minutes`, `valid-twap-runtime?`, `twap-suborder-count`, `twap-suborder-size` and `twap-suborder-notional`. `src/hyperopen/domain/trading.cljs` is a thin barrel that re-exports those names, and `src/hyperopen/state/trading.cljs` re-exports a further subset for view code. `src/hyperopen/domain/trading/validation.cljs` turns a form map into a vector of validation errors; `validate-twap` is the TWAP branch and is reached through the `type-validator-dispatch` map.

**The order-form state** lives in `src/hyperopen/trading/order_form_state.cljs`. A TWAP order form is a map under the `:twap` key holding `{:hours :minutes :randomize}`, with defaults `default-twap-hours` (0), `default-twap-minutes` (30) and `default-twap-randomize` (false). `normalize-twap-form` coerces whatever is in the form — a raw minute count, or an already-split hours/minutes pair — into that canonical shape.

**The write path** turns the form into a signed exchange action. `src/hyperopen/api/gateway/orders/commands.cljs` holds `build-twap-action`, which reads the form and produces `{:action {...} :asset-idx n}`. It builds the action with Clojure's `array-map`, which preserves key insertion order — this is deliberate and load-bearing, because the action is msgpack-encoded and signed, and reordering keys changes the resulting signature and makes the exchange reject the order.

**The read path** brings live TWAP state back. `src/hyperopen/websocket/user_runtime/handlers.cljs` subscribes to the venue's `twapStates` channel and stores rows at `[:orders :twap-states]` in the application database. `src/hyperopen/views/account_info/tabs/twap.cljs` renders both a live-TWAP table and a TWAP-history table in the account panel.

There is also a **second, deliberately independent TWAP model** in the portfolio optimizer at `src/hyperopen/portfolio/optimizer/domain/rebalance.cljs`. It defines its own `twap-suborder-interval-seconds` (30) and its own `twap-suborder-count`, and feeds a cost estimate (`twap-cost`) that the optimizer uses to decide whether to route a rebalance leg as a TWAP. Its comments explicitly say it mirrors the venue mechanics in `hyperopen.domain.trading.core` but is kept decoupled so the optimizer domain does not depend on the trading domain. That duplication means the venue change touches it too.

### What the venue actually does now

These facts were established on 19 August 2026 by reading Hyperliquid's own documentation and cross-checking against a third-party SDK whose integration tests run against the live API. They are restated here so this plan stands alone.

The official order-types documentation states that TWAP running time "can be set from 5 minutes to 7 days, with a $100 minimum total order size"; that "Trigger Price: The TWAP order will be activated when the mark price reaches the trigger price set"; that "Max/Min Price: The TWAP order will be terminated when the mark price reaches the stop price set"; that sub-orders "are sent at a fixed interval, calculated from the total size and running time inputs" with a 30 second minimum; that "larger orders over shorter durations are split into suborders sent as often as every 30 seconds; smaller orders over longer durations are split into suborders spaced further apart"; that a sub-order "is constrained to have a max slippage of 3%"; and that enabling Randomize adjusts each sub-order's size randomly by up to plus or minus 20 percent. The documentation gives two worked examples: a $10,000 order over 1 hour splits into about 121 sub-orders of about $83 sent every 30 seconds, and a $10,000 order over 4 days splits into about 1,000 sub-orders of about $10 sent roughly every 6 minutes.

It is important to be precise about what Max/Min Price is, because the name invites a wrong reading. **It is not a limit price.** It does not cap the price at which sub-orders fill. It is a kill switch measured against the mark price: when the mark reaches that level, the venue stops working the remainder of the order. Sub-orders still execute aggressively against the order book, subject only to the 3 percent per-sub-order slippage constraint. A user who wants "never fill worse than X" is asking for something Hyperliquid's TWAP does not offer, and the UI copy this plan adds must not imply otherwise.

### The wire contract

The advanced fields ride in an **optional `details` object that sits beside `twap` on the action**, not inside it:

    {"action": {"type": "twapOrder",
                "twap": {"a": 0, "b": true, "s": "1", "r": false, "m": 30, "t": false},
                "details": {"t": {"p": "65000", "a": true},
                            "s": "70000"}},
     "nonce": 1755600000000,
     "signature": {...}}

Within `details`, the key `t` is the trigger condition or `null`, where `p` is the trigger price and `a` is a boolean meaning "activate when the mark price is **above** the trigger" when true and "below" when false. The key `s` is the termination price (the Max/Min Price) or `null`. The msgpack key order for the action is `type`, then `twap`, then `details`.

A caveat that must be recorded honestly: **Hyperliquid's own exchange-endpoint API documentation has not been updated for this change.** Fetching that page on 19 August 2026 shows it still lists only "a is asset, b is isBuy, s is size, r is reduceOnly, m is minutes, t is randomize", with no mention of `details`. The schema above comes from the `nktkas/hyperliquid` TypeScript SDK, whose pull request "feat(exchange/twapOrder): add `details` parameter" merged on 2026-08-01 at 19:38 UTC — the same day the venue upgrade went live — and whose integration tests exercise three variants against the real API: a trigger above (`details: {t: {p: pxUp, a: true}, s: null}`), a trigger below (`details: {t: {p: pxDown, a: false}, s: null}`), and a bare stop (`details: {t: null, s: pxUp}`). Note that when `details` is present both of its keys are present, with the unused one set to `null`. Because the official documentation is silent, the plan keeps every venue constant in one named place and requires a testnet confirmation step before the feature is considered done.

On the read side, the venue's `TwapState` payload — which arrives on the `twapStates` websocket channel Hyperopen already consumes — gained two fields: `stopPx`, a decimal string or `null`, and `trigger`, either `null` or an object `{px, above}`.

### The corrected slice model

The old model assumed a fixed 30 second cadence, so the number of sub-orders was always `1 + 2 × minutes`. That is still right when the order is large relative to its duration, but it is badly wrong otherwise, because the venue now stretches the interval to keep each sub-order at or above roughly $10.

The model this plan adopts computes an ideal count at the 30 second floor, a count implied by the $10 sub-order floor, and takes the smaller:

    n_ideal    = 1 + 2 × minutes
    n_notional = floor(total_notional / 10)
    n          = max(2, min(n_ideal, n_notional))
    interval   = (minutes × 60) / (n − 1)   seconds

This reproduces both documented examples exactly. A $10,000 order over 60 minutes gives `n = 121`, $82.64 per slice, every 30 seconds — the docs say about 121 sub-orders of about $83 every 30 seconds. A $10,000 order over 5,760 minutes (4 days) gives `n = 1000`, $10.00 per slice, every 5.77 minutes — the docs say about 1,000 sub-orders of about $10 roughly every 6 minutes. The old model gives 11,521 slices of $0.87 for the second case, wrong by an order of magnitude.

The preview this model drives must be labelled an estimate. The venue owns the real schedule and the documentation states its examples approximately; Hyperopen should not imply exactness it cannot guarantee.

### The live defects

Four defects follow from the venue change. The first three are user-visible today.

**D1 — the duration ceiling is a day too short.** `twap-max-runtime-minutes` is 1440 in `src/hyperopen/domain/trading/core.cljs`, and `valid-twap-runtime?` rejects anything above it. The venue now accepts up to 7 days (10,080 minutes). The form also has no way to express days: `src/hyperopen/views/trade/order_form_type_extensions.cljs` renders only Hours and Minutes inputs.

**D2 — the form blocks orders the exchange accepts.** `validate-twap` in `src/hyperopen/domain/trading/validation.cljs` computes a per-sub-order notional from the fixed-30-second count and raises `:twap/suborder-notional-too-small` when it falls under $10. Worked example: a $200 order over 24 hours. Hyperopen computes `1 + 2 × 1440 = 2881` sub-orders of $0.069 and refuses to submit. Hyperliquid computes 20 sub-orders of $10.00 spaced about 76 minutes apart and accepts. The binding constraint moved: the venue now maintains the $10 floor itself by stretching the interval, and the user-facing minimum is $100 of **total** notional — a check Hyperopen does not currently perform at all.

**D3 — the preview states a falsehood.** `src/hyperopen/views/trade/order_form_type_extensions.cljs` renders the fixed string "Hyperliquid slices TWAP orders every 30 seconds." That is now only true for large orders over short durations.

**D4 — the optimizer's parallel model carries the same stale mechanics.** `src/hyperopen/portfolio/optimizer/domain/rebalance.cljs` defines `twap-suborder-count` from a fixed 30 second interval. In practice the optimizer only routes legs of $70,000 or more to TWAP over 10 to 20 minutes, where the 30 second cadence is still the binding constraint — $70,000 over 10 minutes gives `n_ideal = 21` against `n_notional = 7000`, so the corrected model returns the same 21 — meaning projected costs do not change for any leg the optimizer actually routes this way. Fixing it is therefore a model-correctness change rather than a behavior change, and it is scoped as its own milestone so that it can be dropped without affecting the rest if it proves noisier than expected.

## Plan of Work

The work proceeds in five milestones, ordered so that each is independently verifiable and so the user-visible defect fixes land before the new feature surface.

**Milestone 1 fixes the venue model in the trading domain.** This is pure-function work in `src/hyperopen/domain/trading/core.cljs` and `src/hyperopen/domain/trading/validation.cljs` with no UI involved: raise the duration ceiling to 10,080 minutes, add a total-notional minimum of $100, replace the fixed-cadence sub-order count with the notional-aware model above, add an interval helper, and rewrite `validate-twap` so it enforces the total-notional floor instead of the per-sub-order floor. At the end of this milestone the $200-over-24-hours case validates clean in a unit test that fails before the change and passes after, and no UI has changed yet.

**Milestone 2 threads days, trigger price and stop price through form state and the wire.** The `:twap` form map gains `:days`, `:trigger-px`, `:stop-px` and an `:advanced?` disclosure flag; `twap-total-minutes` learns to fold days in; and `build-twap-action` in `src/hyperopen/api/gateway/orders/commands.cljs` grows an optional `:details` branch. At the end of this milestone a unit test can construct a form with a trigger and a stop and assert the exact action map, including key order, and a form without them produces an action byte-identical to today's.

**Milestone 3 builds the UI.** A Day(s) input joins Hours and Minutes; an Advanced Settings disclosure reveals Trigger Price and a Max/Min Price field whose label flips with the side; the preview switches to the corrected estimate and drops the false 30-second claim. At the end of this milestone the controls are visible and functional in the trade view under browser QA.

**Milestone 4 surfaces the new state on the read side.** The `twapStates` rows are normalized to carry `stopPx` and `trigger`, and the account TWAP table shows them. At the end of this milestone a live TWAP placed with a trigger displays that trigger in the account panel.

**Milestone 5 corrects the optimizer's duplicate model** in `src/hyperopen/portfolio/optimizer/domain/rebalance.cljs`, with tests demonstrating that the optimizer's own routing regime is numerically unchanged.

The precise per-file edits are recorded in `Concrete Steps` and are filled in as each milestone is implemented, so that this document always reflects what was actually done rather than what was first imagined.

## Concrete Steps

Every command in this section is run from the repository root, which in the current working tree is `/Users/barry/projects/hyperopen/.claude/worktrees/twap-advanced-features-limit-de5041`.

Before running any gate in a fresh worktree, run:

    npm run setup:worktree

A fresh worktree has no `node_modules` and `shadow-cljs` is not on the shell `PATH`, so skipping this makes every gate fail with an opaque error that is environmental rather than a real defect.

The fast inner loop while working is:

    npm run lint:delimiters
    npx shadow-cljs --force-spawn compile test && node out/test.js

### Milestone 1 — the venue model and validation (done)

In `src/hyperopen/domain/trading/core.cljs`, `twap-max-runtime-minutes` becomes 10080 and gains a comment naming the upgrade; `twap-frequency-seconds` keeps the value 30 but is re-documented as a spacing FLOOR rather than a cadence; a new `twap-min-order-notional` of 100 is added. `split-twap-total-minutes` now returns `{:days :hours :minutes}` and `twap-total-minutes` folds days in, branching on the presence of either `:days` or `:hours` so legacy drafts that stored a bare total in `:minutes` still migrate. `twap-suborder-count` keeps its old meaning and value and is re-documented as the spacing-floor upper bound. Four functions are new: `twap-order-notional`, `twap-venue-suborder-count`, `twap-interval-seconds-for-count` and `twap-suborder-interval-seconds`. `twap-suborder-size` gains a three-argument arity that uses the venue count when a reference price is known. All new names are re-exported through the barrels `src/hyperopen/domain/trading.cljs` and `src/hyperopen/state/trading.cljs`.

In `src/hyperopen/domain/trading/validation.cljs`, the `:twap/runtime-invalid` copy becomes "TWAP runtime must be between 5 minutes and 7 days.", the `:twap/suborder-notional-too-small` spec is replaced by `:twap/order-notional-too-small` with the copy "A TWAP order must be at least 100 USDC in total order value." and `:fields [:size]`, `validate-twap` checks total notional instead of per-clip notional, and `validate-order-form` computes `:twap-order-notional` instead of `:twap-suborder-notional`. The fail-open convention is preserved: with no reference price the notional check is skipped rather than firing. The code set in `src/hyperopen/schema/trading_submit_policy_contracts.cljs` is updated to match.

### Milestone 2 — the wire (done)

`src/hyperopen/schema/order_request_contracts.cljs` gains `twap-action-with-details-keys`, `twap-details-keys`, `twap-trigger-keys` and the predicates `twap-trigger-detail?` and `twap-details?`, and `twap-action?` now accepts either `[:type :twap]` or `[:type :twap :details]`. An all-nil details block is rejected outright, which is what forces the omit-when-unset rule to be honoured rather than merely intended.

`src/hyperopen/api/gateway/orders/commands.cljs` gains `price-input-present?`, `twap-reference-mark` and `twap-details`, and `build-twap-action` attaches `:details` through a `cond->` so it lands last in the array-map. `twap-details` returns nil when neither field is set, `::invalid` when a typed price cannot be canonicalised or when a trigger was typed with no mark to infer direction from, and otherwise an array-map `{:t {:p px :a above?} :s stop}`. Prices go through the existing `canonical-price-text` helper, the same one every other wire price uses.

### Milestone 3 — the form and its UI (done)

`src/hyperopen/trading/order_form_state.cljs` gains `default-twap-days`, `default-twap-trigger-px` and `default-twap-stop-px`; the `:twap` default map carries `:days`, `:trigger-px` and `:stop-px`; and `normalize-twap-form` names every one of them. That last point is the trap: the function rebuilds the TWAP map from scratch on every read, so a key it does not name is silently discarded.

`src/hyperopen/trading/order_form_transitions.cljs` adds `[:twap :days]`, `[:twap :trigger-px]` and `[:twap :stop-px]` to `localized-numeric-order-form-paths` so they get locale-aware decimal parsing like every other user-entered number.

`src/hyperopen/views/trade/order_form_commands.cljs` gains `set-twap-days-input`, `set-twap-trigger-price-input` and `set-twap-stop-price-input`; `src/hyperopen/views/trade/order_form_handlers.cljs` wires `:on-set-twap-days`, `:on-set-twap-trigger-price` and `:on-set-twap-stop-price` into the `:order-type-sections` group. These ride the generic `update-order-form` command, so no command-catalog, action-args or runtime-registration entry is needed.

`src/hyperopen/views/trade/order_form_type_extensions.cljs` widens the runtime grid to three columns for Days/Hours/Minutes, replaces the false 30-second copy, renames the preview rows to Runtime / Every / Slices / Per Slice, and adds an `Advanced Settings` disclosure built from a native `<details>` element carrying the stable key `trade-twap-advanced-settings`, holding the Trigger Price and Max/Min Price inputs plus a line of copy that says plainly that the stop does not cap the fill price.

`src/hyperopen/views/trade/order_form_view.cljs` injects a `:twap-advanced` model next to `:twap-preview` carrying the side-dependent `:stop-price-label`. Section renderers only receive `(form callbacks)`, so this is the established seam for pushing side-dependent data into one.

`src/hyperopen/views/trade/order_form_feedback.cljs` gives `twap-runtime-label` a day bucket, adds `twap-interval-label`, and rewrites `twap-preview` to resolve a reference price through `trading/reference-price`, pick the venue count when a notional is known and the spacing bound otherwise, and derive the interval from whichever count it displayed.

### Milestone 4 — the read side (done)

The websocket layer needs no change at all: `src/hyperopen/websocket/user_runtime/handlers.cljs` stores `twapStates` rows verbatim, so `stopPx` and `trigger` arrive in the application database for free the day the venue sends them.

`src/hyperopen/views/account_info/projections/twaps.cljs` gains private `twap-stop-price` and `twap-trigger` helpers and merges `:stop-price`, `:trigger-px` and `:trigger-above?` into both `normalize-active-twap-row` and `normalize-twap-history-row`. `display-duration` gains a day bucket, without which a seven-day TWAP would render as "168h 0m".

`src/hyperopen/views/account_info/tabs/twap.cljs` adds one `Trigger / Stop` column to both the active and history tables, rendered by-exception through a new `trigger-stop-node`: a trigger shows as a comparison against the mark, a stop shows as `Stop <price>`, and a row with neither shows a dash. The three `grid-template-columns` constants each gain one track, and the copies of those strings in `test/hyperopen/views/account_info/tabs/twap_test.cljs` are updated in lockstep.

### Formal surfaces

`spec/lean/Hyperopen/Formal/TradingSubmitPolicy.lean` replaces its `twap-suborder-too-small` vector with `twap-order-notional-too-small` (0.5 at a mark of 100 is $50, below the $100 floor) and adds `twap-order-at-notional-floor-is-valid` for the $100 boundary, then:

    bb tools/formal.clj sync --surface trading-submit-policy

`spec/lean/Hyperopen/Formal/OrderRequest/Advanced.lean` and `Standard.lean` model the `details` block and the seven-day runtime, then:

    bb tools/formal.clj sync --surface order-request-advanced
    bb tools/formal.clj verify --surface order-request-advanced
    bb tools/formal.clj verify --surface order-request-standard

The generated vector files are consumed by `test/hyperopen/api/gateway/orders/commands_test.cljs`, which runs every vector through the real builder, so a Lean model that disagrees with the ClojureScript turns `npm test` red with a diff.

### New test files

`test/hyperopen/state/trading/twap_venue_model_test.cljs` pins the slice model against the venue's two documented examples, the runtime window, the days split and the notional helpers. `test/hyperopen/api/gateway/orders/twap_advanced_details_test.cljs` pins the wire: key order at both the Clojure and the JavaScript-object level, trigger direction inference, canonicalisation, fail-closed behaviour, and the property that a plain TWAP produces an action with exactly `[:type :twap]`. They are separate files rather than additions to `validation_and_scale_test.cljs` (465 of 500 lines) or `commands_test.cljs` (481 of 500) because `npm run lint:namespace-sizes` caps files at 500 lines.

After adding any test file, regenerate the runner and commit it:

    npm run test:runner:generate

## Validation and Acceptance

The required gates when code changes are `npm run check`, `npm test` and `npm run test:websocket`. Run all three together and get a single pass/fail matrix with:

    npm run gates

Acceptance is phrased as observable behavior:

1. In a unit test, validating a TWAP order form of size $200 at a reference price of $1 with a running time of 24 hours returns no errors. Before the change the same input returns `[:twap/suborder-notional-too-small]`.
2. In a unit test, `valid-twap-runtime?` accepts 10,080 minutes and rejects 10,081.
3. In a unit test, `twap-suborder-count` for $10,000 over 60 minutes returns 121, and for $10,000 over 5,760 minutes returns 1,000 — matching the venue's two documented examples.
4. In a unit test, `build-twap-action` for a form with no advanced fields produces an action map whose keys are exactly `:type` and `:twap`, in that order, identical to today's output; and for a form with a trigger price above the mark and a stop price produces `:type`, `:twap`, `:details` in that order, with `:details` holding `{:t {:p "..." :a true} :s "..."}`.
5. In the running application, selecting the TWAP order type shows Day(s), Hour(s) and Min(s) inputs and an Advanced Settings toggle; enabling it reveals Trigger Price and a price field labelled "Max Price" when the side is Buy and "Min Price" when the side is Sell.
6. In the running application, the TWAP preview for a $200 size over 24 hours reports roughly 20 sub-orders spaced roughly 76 minutes apart, and no longer claims a fixed 30 second cadence.
7. A TWAP placed on **testnet** with a trigger price is accepted by the exchange and appears in the account TWAP table with its trigger level displayed. This step is required because Hyperliquid's exchange-endpoint documentation does not yet document the `details` field, so acceptance cannot rest on documentation alone.

Browser QA follows `/hyperopen/docs/BROWSER_TESTING.md`: the stable path is converted to Playwright coverage, and any Browser MCP session opened for exploration is explicitly stopped before concluding.

## Idempotence and Recovery

Every step is additive and repeatable. `npm run setup:worktree` is safe to re-run; it re-links `node_modules` or tells you to run `npm ci`. The domain changes are pure functions covered by unit tests, so a bad edit surfaces as a red test rather than as corrupted state. The wire change is guarded by the rule that `:details` is omitted entirely when no advanced field is set, which means the signed payload for an ordinary TWAP is unchanged and the feature cannot regress existing order placement. If the testnet confirmation in acceptance step 7 fails, the recovery path is to leave Milestones 1 and 5 in place — they fix real defects independently of the new fields — and revert only the `:details` branch in `build-twap-action` plus its UI, which is a self-contained deletion.

## Interfaces and Dependencies

No new libraries. The functions that must exist at the end of the work, with their namespaces:

In `hyperopen.domain.trading.core`:

    twap-max-runtime-minutes           ;; 10080
    twap-min-order-notional            ;; 100 (new)
    twap-suborder-count                ;; [minutes notional] -> integer (signature change)
    twap-suborder-interval-seconds     ;; [minutes notional] -> seconds (new)

In `hyperopen.api.gateway.orders.commands`:

    build-twap-action                  ;; [command-context form] -> {:action array-map :asset-idx n}
                                       ;; action key order: :type :twap [:details]

## Progress

- [x] (2026-08-19 15:30Z) Established the venue contract: read Hyperliquid's order-types documentation, confirmed the official exchange-endpoint docs are stale, and recovered the real `details` wire schema from the `nktkas/hyperliquid` SDK pull request #178 merged 2026-08-01 with live-API integration tests.
- [x] (2026-08-19 15:45Z) Derived and validated the corrected slice model against both documented venue examples.
- [x] (2026-08-19 15:55Z) Identified defect D4: the optimizer carries a second, independent copy of the stale 30-second slice model in `src/hyperopen/portfolio/optimizer/domain/rebalance.cljs`.
- [x] (2026-08-19 16:05Z) Authored this ExecPlan.
- [x] (2026-08-19 16:20Z) Mapped every affected surface across the domain, wire, contract, view, read and process layers.
- [x] (2026-08-19 16:40Z) Milestone 1: corrected the venue model and validation in the trading domain; replaced `:twap/suborder-notional-too-small` with `:twap/order-notional-too-small`.
- [x] (2026-08-19 16:55Z) Milestone 2: added the optional `:details` block to `build-twap-action` and widened the runtime wire contract to accept it.
- [x] (2026-08-19 17:10Z) Milestone 3: Day(s) input, Advanced Settings disclosure, side-dependent Max/Min label, and a preview that reflects the dynamic schedule.
- [x] (2026-08-19 17:25Z) Milestone 4: `stopPx` and `trigger` normalized in the account projections and shown by exception in both TWAP tables; `display-duration` gained a day bucket.
- [x] (2026-08-19 17:30Z) Updated the `trading-submit-policy` Lean surface and regenerated its vectors.
- [x] (2026-08-19 17:40Z) New test files pinning the venue slice model and the `details` wire shape; suite green at 5,880 tests / 32,407 assertions.
- [x] (2026-08-19 17:50Z) Added `:twap/trigger-price-invalid` and `:twap/stop-price-invalid`, so a typed-and-unusable advanced price explains itself instead of silently disabling submit.
- [x] (2026-08-19 18:05Z) Browser QA: `tools/playwright/test/trade-twap-advanced.spec.mjs` covers the Day(s) input, the disclosure, the Max/Min label flip and the panel staying open while typing. 4 passed.
- [x] (2026-08-19 18:20Z) Modelled the `details` block and the seven-day runtime in the `order-request-advanced` Lean surface: six new vectors, nine new theorems including both documented venue examples, and `order-request-standard` verified unchanged.
- [x] (2026-08-19 18:30Z) Milestone 5: the optimizer's duplicate slice model is notional-aware and the execution order table no longer claims a flat 30-second cadence. No pinned optimizer figure moved, as predicted.
- [x] (2026-08-19 18:35Z) Bumped the two namespace-size exceptions this work overflowed, with reasons.
- [x] (2026-08-19 18:55Z) Full gate matrix green: 34/34 gates pass, 6,610 tests, 35,861 assertions.
- [x] (2026-08-19 19:10Z) Design polish pass against the reviewing designer's Claude Design project: runtime presets, the schedule as a sentence, price guards with a band and a KILL SWITCH tag, and a TWAP-specific submit label with a guard recap.
- [ ] Confirm a triggered TWAP on testnet (acceptance step 7). This is the one acceptance criterion that cannot be met from the working tree, because Hyperliquid's exchange-endpoint documentation does not describe the `details` field — the shape is taken from an SDK whose integration tests exercise the live API.

### Design polish pass (2026-08-19, second sitting)

The designer reviewed the shipped ticket in a Claude Design project and returned two directions plus a shared set of fixes answering the six questions this plan raised. The maintainer chose the mix: the schedule explained in words, the guards explained by a drawing.

`src/hyperopen/views/trade/order_form_twap_section.cljs` is new and owns the whole TWAP block; `order_form_type_extensions.cljs` now just delegates to it. The runtime is a preset row — 15m / 30m / 4h / 1d / Custom — with Custom revealing three single-letter D/H/M fields, replacing the three labelled fields that never fit the ticket's column. The four-row estimate block is gone, replaced by one sentence ("Buys $5,000 of BTC in 499 pieces — $10.02 about every 3 minutes for 24 hours.") plus a single Per slice row; with no size entered the sentence asks for one rather than showing a bound. "Advanced Settings" is now "Price guards" and states its value when collapsed — "none set", or "65,000 → 72,000". Open, it draws a band placing the trigger and termination levels either side of the mark, and tags the termination price KILL SWITCH.

The submit button reads "Start TWAP - buy over 24 hours" with the guard levels recapped beneath it, because a TWAP is a run the venue works over time rather than an order that lands. That copy is built in `order_form_feedback.cljs/twap-submit-copy` and applied at the single `submit-row` call site, returning nil for every other order type so the voice catalog stays untouched.

The account table's Trigger / Stop cell is verb-first — "starts 65,000" over a tinted "stops 72,000" — because a bare comparison makes the reader supply the verb.

## Surprises & Discoveries

- Observation: "Max Price" on Hyperliquid's TWAP ticket is not a limit price. It terminates the order when the **mark** price reaches the level; sub-orders still cross the book subject only to the 3 percent slippage cap.
  Evidence: Hyperliquid order-types documentation — "Max/Min Price: The TWAP order will be terminated when the mark price reaches the stop price set".

- Observation: Hyperliquid's exchange-endpoint API reference is stale and does not document the `details` field that the live API accepts.
  Evidence: the page fetched on 2026-08-19 still enumerates only "a is asset, b is isBuy, s is size, r is reduceOnly, m is minutes, t is randomize", while the `nktkas/hyperliquid` SDK added and integration-tested `details` against the live API on 2026-08-01.

- Observation: Hyperopen currently rejects TWAP orders that the exchange accepts.
  Evidence: a $200 order over 24 hours yields `1 + 2 × 1440 = 2881` sub-orders of $0.069 under the repo's fixed-cadence model, tripping `:twap/suborder-notional-too-small`; the venue instead works it as 20 sub-orders of $10.00.

- Observation: the corrected slice model reproduces both of the venue's documented worked examples exactly, which is strong evidence it is the venue's actual rule rather than a guess.
  Evidence: `n = max(2, min(1 + 2m, floor(notional/10)))` gives 121 slices of $82.64 every 30s for $10,000 over 1 hour, and 1,000 slices of $10.00 every 5.77 minutes for $10,000 over 4 days.

- Observation: the optimizer keeps a second copy of the venue slice mechanics, so a venue change has two landing sites in this repo rather than one.
  Evidence: `twap-suborder-interval-seconds` and `twap-suborder-count` in `src/hyperopen/portfolio/optimizer/domain/rebalance.cljs`, whose comments state they mirror `hyperopen.domain.trading.core` but are kept decoupled deliberately.

- Observation: `normalize-twap-form` rebuilds the TWAP form map from scratch on every read, so a new form key that is not explicitly named there is silently discarded — the field would appear to work while typing and then vanish.
  Evidence: `src/hyperopen/trading/order_form_state.cljs`; the function ends in `(assoc runtime-fields :randomize ...)`, naming every surviving key.

- Observation: choosing a native `<details>` element for the Advanced Settings disclosure instead of an application-state flag avoided roughly a dozen files of ceremony — a command, a handler, a command-catalog entry, an action-args contract, a runtime registration, a transition, two normalizers, a key-policy entry, a view-model read, and a formal ownership vector regeneration — one of which (`order_form_transitions.cljs`) is already at its namespace-size cap.
  Evidence: the surface inventory for the app-state option versus the actual diff, which touches only the renderer.

- Observation: the top-level `:trigger-px` form key could not be reused for the TWAP trigger.
  Evidence: it is the stop/take-order trigger field, drives `:order/trigger-required`, and is already in `localized-numeric-order-form-paths`; sharing it would bleed values across order-type switches. The TWAP trigger is nested at `[:twap :trigger-px]`.

- Observation: the concern that the corrected model might re-clamp the optimizer's pinned 21-clip figure was unfounded, because the spacing-bound count and the 30-second floor are consistent by construction.
  Evidence: with `n = 1 + 2m` the interval is `60m/(n-1) = 60m/2m = 30s` exactly, never below the floor.

- Observation: adding a `mark` field to the shared Lean `Market` structure does not disturb the `order-request-standard` surface.
  Evidence: `marketToClj` in `spec/lean/Hyperopen/Formal/OrderRequest/Standard.lean` emits optional fields only when they are `some`, so a field defaulting to `none` serialises to nothing and every existing vector is byte-identical.

- Observation: the optimizer's corrected model changes no projected cost for any leg the optimizer actually routes as a TWAP, exactly as predicted, but it does change the estimate for a manually-overridden small leg — which is the point.
  Evidence: for every routed band case ($70k, $100k and $2.5M at 5, 10 and 20 minutes) the spacing bound is binding, so the notional-aware count returns the same number and the pinned figures of 19.07 bp, $190.73, $230.73 and 41 clips are untouched. A $150 leg over 20 minutes now prices at 22.667 bp across 15 clips instead of 19.073 bp across a fictional 41.

- Observation: the wire-level failure mode of an unusable advanced price was a silently disabled submit button.
  Evidence: `build-twap-action` fails closed by returning nil, which the submit policy reports as `:request-unavailable` with no message. Two new validation codes now explain it before the user gets there.

- Observation: dispatching several `update-order-form` actions from one event silently loses all but the last. Every such action re-reads the same pre-event form and rewrites the whole `:order-form` map, so writing days, hours and minutes as three actions left only the third. The preset chips looked completely inert.
  Evidence: the chips rendered and were clickable, the handler returned exactly the right four-action payload, and the runtime never changed. Fixed by making preset selection a single write to `[:twap :preset]` that `apply-order-form-path-effects` expands into the runtime fields in one transition — the extension point that exists for precisely this.

- Observation: that bug was invisible to the entire unit suite and only a browser test caught it. Hiccup-level tests assert the payload a control carries, not what the runtime does with it.
  Evidence: `order_form_component_sections_test.cljs` passed throughout. Three transition tests now pin the expansion directly.

- Observation: `getByRole("button", {name: "1d"})` is ambiguous on the trade route — the chart's timeframe strip carries 15m / 1h / 1d too. Playwright's strict mode turns that into a thrown click rather than a failed assertion, which reads like a broken feature.
  Evidence: "strict mode violation: resolved to 2 elements". Every ticket-local query in the spec is now scoped through the `[data-parity-id="order-form"]` root.

- Observation: the named type scale bottoms out at 12px — `text-xs` and `text-sm` are both 12px — so the designer's 9–11px tags have no utility class. The sanctioned route down is an inline `font-size`, which `order_form_footer.cljs` already uses.
  Evidence: `test/hyperopen/views/typography_scale_test.cljs` bans only `text-[10|11|13|14|15px]`; relying on the gaps in that regex would be relying on a loophole.

- Observation: every colour in the designer's mock except one is already a theme token — they pulled the palette from `dark.css`. #50D2C1 is `--ho-accent`, #0D3A35 is `--ho-accent-soft`, #ED7088 is `--ho-sell-hi`.
  Evidence: `src/styles/themes/dark.css`. Only the sunken teal panel (#0B2124) has no token; it renders as `bg-ho-bg-deep` with an accent border, which does the same figure/ground job without inventing one.

- Observation: two namespace-size exceptions had less headroom than the change needed, and `src/hyperopen/portfolio/optimizer/domain/rebalance.cljs` is now at exactly its 675-line cap.
  Evidence: `npm run lint:namespace-sizes` reported `state/trading.cljs` at 726 against 722 and `order_form_transitions.cljs` at 722 against 719. Both were bumped with reasons naming what was added; the next line added to `rebalance.cljs` will fail the gate and needs its own bump.

## Decision Log

- Decision: infer the trigger direction (`details.t.a`) from the trigger price relative to the current mark rather than asking the user for an above/below choice.
  Rationale: Hyperliquid's own ticket exposes a single Trigger Price field with no direction selector, so adding one would diverge from the venue's mental model; the SDK's live-API tests confirm the pairing of a higher price with `a: true` and a lower price with `a: false`.
  Date/Author: 2026-08-19, Claude (agent), on behalf of the maintainer.

- Decision: omit `details` from the action entirely when neither advanced field is set, rather than always sending it with two nulls.
  Rationale: the action is msgpack-encoded and signed, so any change to the key set changes the signature. Omitting keeps the signed bytes for an ordinary TWAP byte-identical to today's, which means the feature cannot regress existing order placement and makes rollback a clean deletion.
  Date/Author: 2026-08-19, Claude (agent).

- Decision: treat "Max/Min Price" as a single wire field (`details.s`) with a side-dependent **label** only.
  Rationale: the venue has one field and infers direction from the order side; modelling two fields in Hyperopen would invent state the wire cannot carry.
  Date/Author: 2026-08-19, Claude (agent).

- Decision: replace the per-sub-order notional validation with a total-notional minimum of $100, rather than keeping both.
  Rationale: the venue now maintains the $10 per-sub-order floor itself by stretching the interval, so the per-sub-order check can only produce false rejections. The $100 total floor is the constraint the venue actually documents.
  Date/Author: 2026-08-19, Claude (agent).

- Decision: label the sub-order preview as an estimate.
  Rationale: the venue owns the real schedule and states its own examples approximately; presenting a derived count as exact would overstate what Hyperopen can know.
  Date/Author: 2026-08-19, Claude (agent).

- Decision: scope the optimizer's duplicate model fix as its own milestone.
  Rationale: it changes no projected cost for any leg the optimizer actually routes as a TWAP, so it is separable and can be dropped without affecting the user-visible work if it proves noisy.
  Date/Author: 2026-08-19, Claude (agent).

- Decision: build the Advanced Settings disclosure from a native `<details>` element with DOM-owned open state rather than an application-state flag.
  Rationale: the application-state route costs a command, a handler, a command-catalog entry, an action-args contract, a runtime registration, a transition, two normalizers, a key-policy entry, a view-model read and a formal-vector regeneration — for a panel whose only job is to be open or shut. One of those files is already at its namespace-size cap. The `<details>` route needs only a stable `:replicant/key`, which a Playwright test now pins.
  Date/Author: 2026-08-19, Claude (agent).

- Decision: add `:twap/trigger-price-invalid` and `:twap/stop-price-invalid` rather than relying on the builder's fail-closed behaviour.
  Rationale: failing closed is correct for the wire, but on its own it disables the submit button with no explanation. A validation error names the problem.
  Date/Author: 2026-08-19, Claude (agent).

- Decision: build the mix of the designer's two directions — 1b's sentence for the schedule, 1a's band for the guards.
  Rationale: the maintainer's call, and it matches where each device earns its keep. A trigger and a termination price are positions either side of the mark, and position is what a drawing carries better than words; a slice schedule is a set of quantities, and a dashed rail only says "over time", which the sentence says better and in a form that survives screen readers and translation.
  Date/Author: 2026-08-19, maintainer's selection, implemented by Claude (agent).

- Decision: order the guard band by price value rather than by side.
  Rationale: the designer's sell example reverses the band (stop left, start right), but that is a consequence of where a seller's levels naturally sit, not a rule about sides. Positioning from the values themselves reproduces both of their examples and also handles the cases they did not draw.
  Date/Author: 2026-08-19, Claude (agent).

- Decision: the Runtime header states the venue's window rather than the resolved schedule.
  Rationale: the designer's mock puts the recap in that header only in the frame where the sentence is not visible. Our sentence is always visible, so showing both would say the same thing twice, six pixels apart.
  Date/Author: 2026-08-19, Claude (agent).

- Decision: do not validate that the Max/Min price sits on the profitable side of the mark.
  Rationale: a buy whose Max Price is already below the mark would terminate immediately, which is probably a mistake — but Hyperliquid does not document whether it rejects that, and being stricter than the venue would block orders the exchange accepts. That is the exact class of bug this change exists to fix. Left permissive and recorded here.
  Date/Author: 2026-08-19, Claude (agent).

## Outcomes & Retrospective

Everything that can be verified from the working tree is done and green. A trader can now set a TWAP running time in days, hours and minutes up to seven days; open an Advanced Settings disclosure and set a Trigger Price and a side-labelled Max/Min Price; read a slice preview that reports what the venue will actually do, and says "up to N" rather than a figure when it cannot know the notional; and place the $200-over-24-hours order that the form used to refuse. Live TWAPs show their trigger and stop levels in the account panel. The full gate matrix passes 34 of 34 with 6,610 tests and 35,861 assertions, and four Playwright specs exercise the ticket in a real browser.

One acceptance criterion is deliberately still open: step 7, confirming a triggered TWAP against testnet. It cannot be closed from the working tree, and it matters more than usual here because Hyperliquid's exchange-endpoint documentation still does not describe the `details` field — the wire shape is taken from an SDK whose integration tests exercise the live API, cross-checked against the venue's own product documentation and its shipped interface. The design contains that risk: `details` is omitted entirely unless the user sets an advanced field, so every ordinary TWAP signs byte-identically to before and cannot regress, and backing the feature out is the deletion of one `cond->` branch plus its UI.

On complexity, the change is close to net neutral and arguably reducing. It adds four pure functions to the trading domain, one optional wire branch, three form fields and one disclosure. Against that it deletes a validation rule that was actively wrong, removes two exported copies of a misleading slice count from the optimizer's view layer, and replaces three separate statements of a fixed 30-second cadence — in the ticket copy, the execution order table and the optimizer cost model — with one model that reproduces the venue's own documented examples. The largest single saving was structural rather than numeric: choosing a native `<details>` element over an application-state flag kept roughly a dozen files, one of them already at its size cap, entirely out of the diff.

Two pieces of debt are recorded rather than paid: `src/hyperopen/portfolio/optimizer/domain/rebalance.cljs` now sits at exactly its 675-line cap, so the next edit there needs a bump; and the clip-gap derivation lives in `execution_order_type.cljs` rather than beside the rest of the model in `rebalance.cljs` purely because of that cap.

## Artifacts and Notes

Slice-model validation transcript (run 2026-08-19):

    $ 10,000 /    60min -> n=  121  $  82.64/slice  every   0.50 min (30s)
    $ 10,000 /  5760min -> n= 1000  $  10.00/slice  every   5.77 min (346s)
    $    200 /  1440min -> n=   20  $  10.00/slice  every  75.79 min (4547s)
    $    100 /     5min -> n=   10  $  10.00/slice  every   0.56 min (33s)

The first two lines match Hyperliquid's documented examples. The third is the defect case that Hyperopen currently rejects.

Browser QA transcript (`npx playwright test tools/playwright/test/trade-twap-advanced.spec.mjs --workers=1`):

    Running 4 tests using 1 worker
      ok  1 twap ticket exposes days, hours and minutes up to a seven day runtime @regression
      ok  2 twap advanced settings disclose trigger and max price @regression
      ok  3 twap max price becomes min price on the sell side @regression
      ok  4 twap advanced panel stays open while typing @regression
      4 passed

A screenshot of the ticket with the disclosure open, a one-day runtime and both advanced prices filled in was taken during QA and matches the specification: Days / Hours / Minutes inputs, the corrected preview copy, and the Advanced Settings panel holding Trigger Price and Max Price above the line stating that the stop does not cap the fill price.

---

Revision note (2026-08-19): this plan was written before implementation and revised twice during it. The first revision filled in `Concrete Steps` from the actual diff rather than the intended one, and recorded the discoveries that changed the approach — chiefly that `normalize-twap-form` silently drops unnamed keys, that the native `<details>` disclosure avoided a dozen files of ceremony, and that the runtime wire contract had to be widened before any `details`-bearing request could pass. The second revision recorded the two validation codes added after a self-review found that an unusable advanced price disabled submit with no explanation, the preview's shift to reporting a bound when the notional is unknown, the completed Lean and optimizer work, and the final gate results.
