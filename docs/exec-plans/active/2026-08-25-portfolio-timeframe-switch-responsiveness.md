# Make the portfolio chart timeframe switch feel instant

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It must be maintained in accordance with `.agents/PLANS.md` (repository root).

## Purpose / Big Picture

A trader on `/portfolio` who changes the chart timeframe — for example from 30D to 2Y — currently sees the page hesitate, the whole page jump down and back, some tearsheet rows disappear and return, and a loading spinner that visibly stutters while it waits. After this change the same interaction keeps the page perfectly still, keeps every tearsheet row on screen, labels the numbers honestly while they are being recalculated, and spins its loading indicator smoothly no matter how busy the browser's main thread is. On a repeat switch back to a timeframe already loaded, it does not go to the network at all.

The word "main thread" below means the single JavaScript thread the browser uses to run our code, lay out the page, and paint it. When that thread is busy for longer than about 16 milliseconds, animation visibly stalls. "Compositor" means the separate, faster path the browser uses for certain animations (transforms and opacity) which keeps running even when the main thread is busy.

You can see the result working by opening `/portfolio` for an active account, switching the timeframe selector from 30D to 2Y, and observing: nothing above or below the chart moves, the spinner (if it appears at all) rotates smoothly, the performance-metrics table keeps all of its rows and shows an "Updating…" chip next to the range label until the new numbers arrive, and switching back to 30D within a few minutes produces no `candleSnapshot` network request at all.

## Context References

Public refs:

- Direct user/maintainer request (2026-08-24/25, this session): "when switching timeframes for say 30D to 2Y, it takes a while to load and I notice some jank in the loading icon", followed by "make an execution plan to correct it and implement it". No GitHub issue yet; open one if this lands as a PR.

Repo artifacts:

- The measurement report that motivated this plan is published at `https://claude.ai/code/artifact/575a3177-64e8-4617-a019-769089ce54db`. Its findings are restated in full below so this plan stands alone.
- `docs/BROWSER_TESTING.md` governs which browser harness to use. Playwright is the only viable harness for this app; the in-app Browser pane cannot render it because its tabs are always `hidden`, so the app's `requestAnimationFrame` render loop never fires.

Local scratch refs (non-authoritative):

- None.

## Progress

- [x] (2026-08-25 00:20Z) Measurement pass complete: live Playwright profiling of production plus direct API measurement; findings recorded in Context and Orientation below.
- [x] (2026-08-25 00:45Z) Change sites researched and confirmed by reading source; scope frozen into four milestones and an explicit deferral list.
- [x] (2026-08-25 00:50Z) ExecPlan authored.
- [x] (2026-08-25 01:05Z) Baseline gate matrix recorded: `npm run gates` 34/34 PASS, 6943 tests / 38572 assertions, 2m53s. Any red after this point is a regression introduced by this plan.
- [x] (2026-08-25 01:25Z) Milestone 1 — correctness defects GREEN. `apply-candle-snapshot-error` normalizes a warm slot to the map shape the store already supports instead of throwing; `install-render-loop!` gained a `:telemetry-enabled?` dep (wired to `telemetry/dev-enabled?` in `app/bootstrap.cljs`) that skips the per-write root-key diff in release builds. Suite: 6198 tests / 35112 assertions, 0 failures (baseline 6195/35104 + 3 new tests / 8 new assertions).
- [x] (2026-08-25 02:10Z) Milestone 2 — perceived performance GREEN. Status banner is now an always-present FIXED live region (no in-flow mount/unmount, so no ~86px double shift) with `backdrop-blur-sm` removed; new `.ho-spinner` utility animates `transform` only and replaced the DaisyUI SMIL-mask spinner at both call sites; `performance-metrics-model` waits for benchmark rows before posting, so the empty-benchmark worker job and the vanish/reappear of six metric rows are gone; worker replies are correlated by `:id` and the applied signature is recorded, driving a new `:stale?` flag rendered as an "Updating…" chip. Suite: 6204 tests / 35133 assertions, 0 failures.
- [x] (2026-08-25 03:05Z) Milestone 3 — ambient main-thread load GREEN. `benchmark-computation-context` now keys on the `[coin interval]` candle slots it actually reads instead of the identity of the whole `:candles` map; `on-render` is memoized per chart host behind an explicit `:tooltip-key` so Replicant's by-value `unchanged?` can succeed for the chart host and its ancestors; the fill-driven open-orders refresh stopped hardcoding `:force-refresh? true`, which restores the 2.5s response cache and single-flight de-duplication for it while order mutations opt in explicitly. Suite: 6208 tests / 35144 assertions, 0 failures.
- [x] (2026-08-25 03:40Z) Milestone 4 — network waste GREEN. `returns-benchmark-fetch-effects` now takes `state` (all five call sites already had it) and skips any coin whose stored `[coin interval]` slot both reaches back to the start of the requested window and is current to within two bars; a new public `candle-slot-covers-window?` carries that rule, and a `*now-ms*` dynamic seam keeps the action deterministic under test. `select-portfolio-summary-time-range` now gates the fetch on the Returns tab, matching the guard `select-portfolio-chart-tab` already had. Suite: 6211 tests / 35153 assertions, 0 failures. The coverage rule itself lives in a new `hyperopen.portfolio.candle-coverage` namespace rather than inside the already-at-cap actions seam.
- [ ] Milestone 5 — full gate matrix green and browser verification of the user-visible acceptance list.

## Surprises & Discoveries

- Observation: the timeframe switch itself is cheap. It emits four effects and one HTTP request; every piece of main-thread work on that path sums to well under 50 milliseconds. What makes it feel slow is that it lands on a page whose main thread is already heavily occupied.
  Evidence: measured with Playwright against production. Unthrottled, the click-to-settled interval was about 480 ms, of which a single 128,172-byte `candleSnapshot` POST took 0.70–0.76 s of overlapping network time. With a 4x CPU throttle applied the same interaction took 3.0–7.9 s.

- Observation: the cost models implied by several docstrings in this repository are wrong by roughly an order of magnitude. Hyperliquid's `portfolio` endpoint returns only `day`, `week`, `month`, and `allTime` buckets, each with roughly 50 to 90 samples regardless of how much time they span. For the account used in measurement, `allTime` is 90 rows spanning 943 days — about fourteen days between samples, not one. Every range of three months or longer is produced by clipping that 90-row series client-side, so the 2Y strategy series is roughly 70 points.
  Evidence: `POST /info {"type":"portfolio","user":"0xecb63caa…2b00"}` returned 40,285 bytes; row counts per bucket were day 62, week 68, month 48, allTime 90.

- Observation: the page is not idle when the user interacts with it. Sitting on `/portfolio` and touching nothing for twenty seconds, the main thread was blocked between 37% and 86% of wall-clock time depending on market activity, while producing only 279 DOM mutations across 36 render batches. Enormous compute, almost no visible change.
  Evidence: Playwright with a `longtask` PerformanceObserver, a `requestAnimationFrame` gap sampler, and a `MutationObserver` on `#app`, under a 4x CPU throttle. Frame rate measured 5.9 to 23.6 frames per second; the worst single frame gap was 1,042 ms.

- Observation: muting the WebSocket payloads on that same idle page raised throughput from 23.6 to 74.3 frames per second and dropped the worst frame from 1,042 ms to 300 ms. That is the causal test showing the ambient load is the live data stream, not the timeframe switch.
  Evidence: same harness, using Playwright's `routeWebSocket` to drop server-to-client frames.

- Observation: the loading indicator has no compositor path at all. DaisyUI's `.loading-spinner` is not a CSS animation — it is `background-color: currentColor` plus a `mask-image` data URI whose motion comes from an SMIL `<animateTransform>` element inside the SVG. There is no `@keyframes` rule and no `animation` property anywhere in it. An SVG consumed as a mask image is re-rasterised from the main thread, so the spinner freezes for exactly as long as the thread is blocked.
  Evidence: `node_modules/daisyui/dist/full.css` around line 5162; and in a live browser, `document.querySelector('[data-role="portfolio-background-status"] .loading').getAnimations()` returns an empty array, proving nothing is driving it through the animation timeline.

- Observation: the "still syncing" banner is a plain sibling in a vertically-spaced column, so mounting and unmounting it translates everything below it — the summary grid, the chart card, and the entire performance-metrics table — down by roughly 86 pixels and back. On the URL in question the user is most likely clicking the tearsheet's own range selector, which sits far down the page, so the control they just clicked moves out from under the cursor and returns. This is the only mechanism in the whole investigation that makes things visibly move, and a CPU profile would never reveal it.
  Evidence: `src/hyperopen/views/portfolio/header.cljs` `background-status-banner` is wrapped in `(when visible? …)`, and `src/hyperopen/views/portfolio_view.cljs` composes the page as a flat `space-y-4` column with the banner as its second child.

- Observation: every timeframe switch posts two jobs to the performance-metrics Web Worker, and the first one is guaranteed to be wrong. Because the candle store is keyed by interval and each preset uses a different interval, the new interval's slot is empty on the first render after the click, so the benchmark request is built with an empty series. The worker computes it anyway and the listener applies the result unconditionally, which makes six metric rows and an entire labelled group vanish from the table and then reappear when the real data lands.
  Evidence: `src/hyperopen/views/portfolio/vm/performance.cljs` `build-metrics-request-data` substitutes `[]` for a missing coin; `src/hyperopen/portfolio/application/metrics_bridge.cljs` writes any `metrics-result` message into the store with no correlation check, even though the worker already echoes back an `id` field that nobody sets.

- Observation: `apply-candle-snapshot-error` writes to a path that cannot exist. `apply-candle-snapshot-success` stores a Clojure vector of candle rows at `[:candles coin interval]`, but the error handler does `assoc-in` with the keyword `:error` appended to that same path, which resolves to associating a keyword key into a vector and throws. It throws inside a `swap!` that runs inside a promise `.catch`, so a failed refresh of an already-populated slot records nothing, clears nothing, and leaves the pending indicator up until some later fetch happens to succeed.
  Evidence: `src/hyperopen/api/projections/market.cljs` — the success path ends in `vec`, the error path does `(assoc-in state [:candles coin interval :error] message)`.

- Observation: the post-fill account refresh deliberately bypasses both of the mechanisms that exist to prevent request storms. `refresh-open-orders-snapshot!` hardcodes `:force-refresh? true`, and the request pipeline routes a forced request around both the 2.5-second response cache and the single-flight de-duplicator. For the account measured, that means re-downloading a 518,440-byte / 1,626-order payload up to roughly once per second — data the `openOrders` WebSocket topic, which is already subscribed and marked lossless, has just delivered. Sustained long enough, this earns an HTTP 429.
  Evidence: `src/hyperopen/websocket/user_runtime/refresh.cljs` merges `{:force-refresh? true}`; `src/hyperopen/api/info_client/flow.cljs` `request-info-with-flow!` has a `force-refresh?` branch that calls the request function directly, outside `with-single-flight!` and without consulting the cache. Measured 6.0 to 11.0 MB of `frontendOpenOrders` responses per twenty idle seconds.

- Observation (negative result, recorded so nobody re-runs it): stubbing out the redundant `frontendOpenOrders` REST responses entirely removed 8.8 MB of traffic but did not measurably reduce blocked main-thread time (82% versus 84%, inside run-to-run noise). Treat that change as a bandwidth and rate-limit win, not a smoothness win.

- Observation (refuted theory, recorded so nobody re-chases it): a `nil` child does not shift its siblings in Replicant and does not remount them. Replicant's `update-children` has dedicated branches for a `nil` on either side that keep a positional placeholder in the virtual DOM, so the banner mounts once and unmounts once rather than restarting on every render.
  Evidence: `replicant/core.cljc` in the `no.cjohansen/replicant` 2025.06.21 artifact, `update-children`, the `(and new-nil? old-nil?)` and `new-nil?` branches.

- Observation: the existing regression test guarding the portfolio view-model caches was passing vacuously. It read the caches as `@#'vm/benchmark-computation-context-cache`, which derefs the Var and yields the ATOM rather than the atom's value, so `(:context ...)` was `nil` on both sides of every `identical?` assertion and the test could not fail. Found while adding the candle-slot cache coverage, because the new test used the same idiom and its negative assertion — "a write to the slot the benchmark DOES read must still invalidate" — failed against `nil` vs `nil`.
  Evidence: a temporary `js/console.log` of `(:candle-slots @#'vm/benchmark-computation-context-cache)` printed `nil` after three `portfolio-vm` calls; switching to `@vm/benchmark-computation-context-cache` (the atoms are public `defonce`s) made both the new assertions and the pre-existing ones meaningful. The old test now also asserts the caches are populated at all before comparing them.

## Decision Log

- Decision: fix the perceived-performance symptoms and the ambient per-render cost, but do not attempt to route-scope the order-book and trades WebSocket subscriptions in this plan.
  Rationale: those subscriptions are established once at startup by `subscribe-to-asset` and are never re-established on route entry, so scoping them to the trading route requires building subscribe-on-navigation plumbing that does not exist today. Getting it wrong silently breaks the order book on the primary trading surface. The measured win available without touching subscriptions — stopping the benchmark pipeline from re-deriving on every unrelated candle write — captures most of the `/portfolio` benefit at a fraction of the risk.
  Date/Author: 2026-08-25, Claude (session with Barry).

- Decision: do not collapse the 3M / 6M / 1Y / 2Y presets onto a single daily candle interval, even though the measurements show it would eliminate the network round trip on four of five coarse presets.
  Rationale: it visibly changes the resolution of the benchmark line on three existing presets. That is a product decision, not a performance decision, and it needs owner sign-off rather than being smuggled in under a responsiveness fix. The state-aware fetch guard in Milestone 4 delivers the "no network on a repeat switch" behavior without changing what any preset looks like.
  Date/Author: 2026-08-25, Claude.

- Decision: keep showing the previous window's metric numbers while the new ones are computed, rather than blanking the table or covering it with the existing full-card overlay.
  Rationale: blanking is what produces the churn the user is complaining about. The defect today is not that old numbers are visible, it is that they are visible with no indication that they are stale and with the new range's label above them. Labelling them fixes the honesty problem without introducing a second flash.
  Date/Author: 2026-08-25, Claude.

- Decision: correlate worker results by request signature using the `id` field the worker already echoes, rather than adding a new message type or a sequence counter.
  Rationale: `src/hyperopen/portfolio/worker.cljs` already reads `(.-id data)` off the incoming message and copies it onto the outgoing one. The plumbing exists and is simply unused; wiring it costs two lines and removes a whole class of out-of-order application bug.
  Date/Author: 2026-08-25, Claude.

- Decision: stop hardcoding `:force-refresh? true` inside `refresh-open-orders-snapshot!` and instead let the caller opt in, with the order-mutation path opting in and the fill-driven path not.
  Rationale: a user who just placed or cancelled an order must see the result immediately, so that path genuinely needs to bypass the cache. A fill arriving on a stream that already carries the authoritative order list does not. Making the distinction explicit at the call site preserves the behavior that matters and removes the storm.
  Date/Author: 2026-08-25, Claude.

- Decision: raise the `src/hyperopen/views/portfolio/vm.cljs` namespace-size exception from 520 to 535 lines rather than performing the extraction that exception asks for.
  Rationale: the added lines are the memo keys and their explanations, which is exactly the kind of context that stops this class of bug from being reintroduced. The extraction the exception calls for — moving the three atom caches and their key computations into a `vm/model_cache` namespace — is a worthwhile cleanup, but a several-hundred-line mechanical refactor in the middle of a behavioural change set adds regression risk to work the user asked to be corrected, not restructured. The exception text now names that extraction as the way to retire the entry instead of raising it again.
  Date/Author: 2026-08-25, Claude.

- Decision: move the background-status banner to a fixed bottom-left region rather than reserving permanent in-flow space for it.
  Rationale: reserving space removes the shift but costs roughly 86px of the page forever, on a view whose whole complaint is about the chart and tearsheet being hard to read. The banner reports ambient background sync, not a blocking state, so a corner status card is the honest treatment. It sits opposite the existing global toast region (`notifications_view` uses bottom-right) so the two cannot collide, and one z-band below it so toasts win if they ever do.
  Date/Author: 2026-08-25, Claude.

- Decision: keep the explanatory comments and raise three namespace-size exceptions rather than trimming the "why" out of the code, but only after cutting every comment that merely repeated another one.
  Rationale: this codebase's own convention is long causal comments at the site of a subtle decision, and every one of these changes is subtle in exactly the way that invites reintroduction. The duplicate copy in `order/effects.cljs` was reduced to a pointer and the vault chart comment removed entirely, since `spec-update-key` now carries the canonical explanation; what remains is load-bearing. Each raised entry names this work and the split that should retire it.
  Date/Author: 2026-08-25, Claude.

- Decision: extract the candle-coverage rule into `src/hyperopen/portfolio/candle_coverage.cljs` instead of raising the `portfolio/actions.cljs` size exception by 76 lines.
  Rationale: unlike the earlier ratchets in this plan, this was genuinely new logic rather than explanation, and `portfolio.actions` is a documented stable public seam that was already sitting exactly at its cap. Coverage is a cohesive, independently testable concern with its own clock seam, so it earns a namespace. The seam file still absorbed +15 lines for the guard call and the Returns-tab gate, which is what its exception was raised by.
  Date/Author: 2026-08-25, Claude.

## Outcomes & Retrospective

To be completed at the end of Milestone 5.

## Context and Orientation

This repository is a ClojureScript single-page application built with shadow-cljs. The user interface is rendered by Replicant, a virtual-DOM library: application code produces a nested data structure describing the desired DOM ("hiccup"), and Replicant compares it against what is currently on screen and applies the difference. Application state lives in one atom (a mutable reference cell) referred to throughout as "the store". Every write to the store schedules a render on the next animation frame; multiple writes in the same frame collapse into one render. That render loop lives in `src/hyperopen/runtime/bootstrap.cljs` in `install-render-loop!`.

Actions and effects follow a Nexus-style split: an "action" is a pure function from the current state plus arguments to a vector of "effects", and an "effect" is an impure interpreter that performs input/output. The effect registry is `src/hyperopen/app/effects.cljs`.

The portfolio page is assembled in `src/hyperopen/views/portfolio_view.cljs`. Its `portfolio-view` function builds a flat column of sections: a header, a background-status banner, a summary grid containing the chart card, an account table containing the performance-metrics tearsheet, and two popovers. It obtains all of its data from one view model built by `portfolio-vm` in `src/hyperopen/views/portfolio/vm.cljs`.

The chart timeframe selector dispatches `:actions/select-portfolio-summary-time-range`, implemented in `src/hyperopen/portfolio/actions.cljs`. That action stores the new preset, clears any custom range, writes the preset to local storage, rewrites the shareable URL query, and emits one `:effects/fetch-candle-snapshot` per selected benchmark coin. The interval and bar count for each preset come from a static table, `returns-benchmark-candle-request-by-summary-time-range`, near the top of the same file. Because each preset maps to a different candle interval, and because fetched candles are stored at `[:candles coin interval]`, switching preset always reads a store slot that is empty until the network responds.

The performance-metrics table is computed off the main thread by a Web Worker whose entry point is `src/hyperopen/portfolio/worker.cljs`, driven from `src/hyperopen/portfolio/application/metrics_bridge.cljs`. The view-model side that decides when to post a job is `performance-metrics-model` in `src/hyperopen/views/portfolio/vm/performance.cljs`.

The chart itself is not rendered by Replicant. `src/hyperopen/views/portfolio/chart_view.cljs` renders an empty host element carrying a `:replicant/on-render` hook, and `src/hyperopen/views/chart/d3/runtime.cljs` imperatively builds and updates SVG inside that host. Replicant decides whether a subtree changed by comparing the new hiccup to the previous hiccup by value; because the hook is currently constructed fresh on every render, that comparison can never succeed for the chart host or for any ancestor containing it.

Finally, `src/hyperopen/websocket/user_runtime/refresh.cljs` reacts to WebSocket user events. When new fills arrive it schedules, on a 250-millisecond trailing debounce, a refresh of the account surfaces, which includes re-fetching the full open-orders snapshot over HTTP.

## Plan of Work

The work is organised into four milestones that can be validated independently, followed by a validation milestone. Each milestone leaves the application in a working, testable state.

### Milestone 1 — correctness defects

This milestone fixes two things that are simply wrong, independent of performance. At the end of it, a candle fetch that fails against an already-populated slot records its error instead of throwing, and the render loop stops performing a second structural comparison of application state for a telemetry event that production builds discard.

In `src/hyperopen/api/projections/market.cljs`, `apply-candle-snapshot-error` must stop writing into the vector that `apply-candle-snapshot-success` stores. Move the error and error-category writes to a sibling path — `[:candles-errors coin interval]` — and add a reader so the existing consumers can still find an error if they need one. The rows path keeps holding rows and only rows. Confirm by reading every consumer of `[:candles …]`: `benchmark-candle-rows` in `src/hyperopen/views/portfolio/vm/history.cljs` walks nested maps looking for `:rows`, `:data`, or `:candles` keys and otherwise yields an empty sequence, so it tolerates both shapes; the change makes the vector shape the only shape it ever sees.

In `src/hyperopen/runtime/bootstrap.cljs`, `install-render-loop!` currently calls `changed-root-keys` on every store write. That function allocates two sets of the store's top-level keys and compares each key's value between the old and new state. Its only consumer is the `:ui/app-render-flush` telemetry event, and `src/hyperopen/telemetry.cljs` `emit!` discards everything unless `dev-enabled?` is true. Guard the computation so it only runs when telemetry is actually going to keep it, and make the "did anything change" gate an identity check rather than a deep value comparison, which is what the caller semantics already imply.

### Milestone 2 — perceived performance

At the end of this milestone the page no longer moves during a timeframe switch, the spinner is compositor-driven, no worker job is ever computed against an empty benchmark, and the tearsheet says when its numbers are stale.

First, take the background-status banner out of the document flow. `background-status-banner` in `src/hyperopen/views/portfolio/header.cljs` currently returns `nil` when there is nothing to report. Change it to always return an element that hides itself when there is nothing to report, following the pattern already used and documented by `range-strip` in `src/hyperopen/views/chart/range_strip.cljs`. Position the visible banner so that showing it does not resize the column: give the always-present wrapper a fixed footprint of zero and render the visible card absolutely within a relatively-positioned page wrapper, or equivalently render it as a fixed-position status toast. Also remove `backdrop-blur-sm` from it, because a backdrop filter forces the browser to read back and re-blur what is behind the element on every frame the spinner repaints, which is precisely the frames where the main thread is already contended.

Second, replace the loading indicator. Add a `.ho-spinner` rule to `src/styles/surfaces/utilities.css` built the same way the existing `.hx-spin` rule is: a bordered circle with a transparent top border animated by a `@keyframes` rule that only changes `transform: rotate()`. Transform animations are eligible for the compositor, so the indicator keeps rotating while the main thread is blocked. Add size modifiers for the two call sites, and honour `prefers-reduced-motion` the way `src/styles/surfaces/app-shell.css` already does elsewhere. Then swap both DaisyUI spinners — the one in `background-status-banner` and the one in the metrics loading overlay in `src/hyperopen/views/portfolio/performance_metrics_view.cljs` — onto the new class.

Third, stop posting a metrics-worker job whose benchmark side is empty. In `performance-metrics-model` in `src/hyperopen/views/portfolio/vm/performance.cljs`, compute whether every selected benchmark coin currently has rows. When one or more do not, skip the post entirely and mark the model as awaiting benchmark data. The existing result stays on screen. When the candles land, the signature changes, every coin has rows, and exactly one job is posted with correct inputs. This removes one of the two jobs per switch and removes the vanish-and-reappear of the six benchmark-relative metric rows outright, because no empty-benchmark result is ever produced.

Fourth, make staleness visible and results correlated. In `src/hyperopen/portfolio/application/metrics_bridge.cljs`, send the request signature as the message `id` — the worker already copies `id` onto its reply — and, in the listener, ignore any reply whose `id` does not match the most recent request. Store the applied signature alongside the result. Then have `performance-metrics-model` compare the applied signature to the current one and expose `:stale? true` when they differ, and render a small "Updating…" chip beside the Range label in the tearsheet header when that flag is set. The numbers stay visible; they are simply labelled.

### Milestone 3 — ambient main-thread load

At the end of this milestone, unrelated WebSocket traffic stops forcing the portfolio's benchmark pipeline and chart subtree to be rebuilt, and the open-orders refresh stops bypassing its own cache.

First, narrow the benchmark context cache key. `benchmark-computation-context` in `src/hyperopen/views/portfolio/vm.cljs` currently compares `(identical? candles (:candles cache))`, where `candles` is the entire `[:candles]` map for every coin and interval in the application. The trades WebSocket handler writes `[:candles active-asset timeframe]` on a buffered interval regardless of which route is displayed, so on `/portfolio` that identity check fails roughly twice a second and the whole benchmark pipeline re-derives for candle data this page never draws. Replace the whole-map identity check with a check over only the `[coin interval]` slots the context actually reads, derived from the selected benchmark coins and the interval that the current range resolves to.

Second, make the chart's render hook stable. In `src/hyperopen/views/chart/d3/runtime.cljs`, memoise the function returned by `on-render` per surface, keyed by the same update key the runtime already uses to decide whether to redraw. Returning the identical function object when nothing has changed lets Replicant's value comparison succeed for the chart host and, transitively, for its ancestors, so the chart card subtree stops being re-diffed on every unrelated store write. To make that memo provably correct rather than incidentally correct, add an explicit `:tooltip-key` to the spec at both call sites — `src/hyperopen/views/portfolio/chart_view.cljs` and `src/hyperopen/views/vaults/detail/chart_view.cljs` — capturing everything the spec's `:build-tooltip` closure depends on, and include that key in `spec-update-key`. Today `:build-tooltip` is rebuilt every render and excluded from the update key, so the runtime already retains a stale closure whenever it short-circuits; adding the key closes that latent hole at the same time.

Third, stop bypassing the request cache on fill-driven open-orders refreshes. In `src/hyperopen/websocket/user_runtime/refresh.cljs`, remove the hardcoded `{:force-refresh? true}` from `refresh-open-orders-snapshot!` so the caller decides. In `src/hyperopen/account/surface_service.cljs`, thread a `:force-open-orders-request?` option through `run-post-event-refresh!`, defaulting to false, and have `refresh-after-order-mutation!` set it true while `refresh-after-user-fill!` leaves it false. The effect is that a user action that changes orders still refreshes immediately, while a stream of incoming fills collapses to at most one request per 2.5 seconds per address and dex, which is what the existing `:frontend-open-orders` cache time-to-live in `src/hyperopen/api/request_policy.cljs` was always meant to provide.

### Milestone 4 — network waste

At the end of this milestone, switching back to a timeframe whose candles are already loaded makes no network request, and no candle request is made at all when the chart is not showing Returns.

`returns-benchmark-fetch-effects` in `src/hyperopen/portfolio/actions.cljs` currently takes only the range and the coin list, so it structurally cannot consult the store. Every one of its five call sites already has `state` in hand. Give it `state` and skip any coin whose stored slot for the resolved interval already covers the requested window. Coverage must be judged on two axes, not on mere presence: the oldest stored candle must be at or before the start of the requested window, and the newest stored candle must be recent enough that the series is not stale. Presence alone is not sufficient, because the all-time preset and the two-year preset share the `:1d` interval slot while requesting very different spans, and a presence-only guard would silently render a two-year benchmark for an all-time window.

Separately, gate the fetch on the chart tab. `select-portfolio-summary-time-range` fetches benchmark candles unconditionally, even when the chart is showing Account Value or PNL, where no benchmark is drawn. `select-portfolio-chart-tab` in the same file already guards the identical call with `(= chart-tab* :returns)` and re-emits the fetch when the user returns to the Returns tab, so adding the same guard here is safe and loses nothing.

### Milestone 5 — validation

Run the full gate matrix and compare against the baseline recorded before any edits. Then verify the user-visible acceptance list in a real browser via Playwright, since the in-app browser pane cannot render this application.

## Validation and Acceptance

Every gate below must be run from the repository root of this worktree. Run `npm run setup:worktree` first if `node_modules` is missing; a fresh worktree has none, and every gate then fails with an opaque error that is environmental rather than a code defect.

The full matrix is `npm run gates`, which runs every gate to completion without stopping at the first failure and prints a PASS/FAIL table. The individual required gates when code changes are `npm run check`, `npm test`, and `npm run test:websocket`.

Acceptance is behavioural. After implementation, on `/portfolio` for an account with history and one benchmark selected:

1. Switching the chart timeframe from 30D to 2Y does not move any content vertically. Verified by installing a `layout-shift` PerformanceObserver before the click and asserting that no entry is attributed to the portfolio root during the transition. Before this change that observer reports two large shifts roughly 700 milliseconds apart.

2. The loading indicator, when it appears, is driven by the animation timeline. Verified by evaluating `getAnimations()` on the spinner element and asserting a non-empty result. Before this change the same expression returns an empty array.

3. The performance-metrics table never loses rows during the transition. Verified by counting elements matching the metric-row data role immediately after the click, again 300 milliseconds later, and again after the transition settles, and asserting all three counts are equal. Before this change the middle count is six lower and an entire labelled group is missing.

4. Exactly one job is posted to the metrics worker per timeframe switch, and its benchmark request carries a non-empty series. Verified by a unit test over `performance-metrics-model` that drives the model twice — once with an empty candle slot and once with a populated one — and asserts the post happens only on the second.

5. While the new metrics are computing, the tearsheet shows an "Updating…" chip beside the range label and keeps its previous values visible. Verified by a view test asserting the chip is present when the model carries `:stale? true` and absent otherwise.

6. Switching from 2Y back to 30D and then to 2Y again within the cache window issues no second `candleSnapshot` request for a slot that already covers the window. Verified by a unit test over `returns-benchmark-fetch-effects` with a seeded store, plus a Playwright assertion counting `candleSnapshot` requests across a switch cycle.

7. Switching the timeframe while the chart tab is Account Value or PNL issues no `candleSnapshot` request at all. Verified by a unit test over `select-portfolio-summary-time-range`.

8. A candle fetch that fails after a previous fetch succeeded records an error rather than throwing. Verified by a unit test that calls `apply-candle-snapshot-success` and then `apply-candle-snapshot-error` on the same coin and interval and asserts the resulting state is a map with the rows intact and an error recorded.

9. A fill-driven account refresh does not bypass the open-orders response cache, while an order-mutation refresh still does. Verified by unit tests over the two refresh entry points asserting the presence and absence of `:force-refresh?` in the request options.

10. Unrelated candle writes do not rebuild the benchmark context. Verified by a unit test that builds the context, writes a candle slot for an unrelated coin, rebuilds, and asserts the returned context is the identical object.

## Concrete Steps

Work milestone by milestone. After each milestone run `npm test` and fix anything red before moving on; run the full `npm run gates` matrix only at Milestone 5 and whenever a lint-shaped gate is plausibly affected, because the matrix takes several minutes.

Commit after each milestone with a message naming the milestone, so that a bisect can attribute any regression to a single concern.
