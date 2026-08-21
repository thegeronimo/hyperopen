# Repaint the account panel when its lazy tab chunk arrives, and make the remaining wait honest

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It must be maintained in accordance with `/hyperopen/.agents/PLANS.md` (the full ExecPlan writing contract) and the public planning entry point `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

On the trade page (https://hyperopen.xyz/trade) the account panel at the bottom of the screen has eight tabs: Balances, Positions, Outcomes, Open Orders, TWAP, Trade History, Funding History, Order History. Clicking Positions, Open Orders or Order History showed a bare grey spinner with no text for a long, unpredictable time — sometimes a third of a second, sometimes nearly two seconds — while Hyperliquid's own client (app.hyperliquid.xyz) renders the equivalent tables instantly.

The cause is not download size and not the network. The JavaScript for a tab arrives in about 35 milliseconds. The panel then refuses to repaint, because the panel's cached-render key does not include the piece of application state that the chunk loader updates when the chunk finishes. The panel keeps handing back its previously-rendered output — spinner included — until something unrelated changes, in practice the next market-data message from the websocket. When the websocket is quiet or disconnected, the spinner never clears at all.

After this plan, clicking any account tab paints its table within roughly 40 milliseconds of the chunk landing, on a page with no websocket traffic whatsoever. While the chunk is still in flight (which only becomes perceptible on a slow connection), the panel shows a named, screen-reader-announced loading state — "Loading Open Orders…" — instead of an anonymous spinner, and a failed load shows a readable message with a working Retry button instead of hanging forever. A repeat visitor pays nothing, because the two hottest chunks are prefetched at idle priority.

You can see it working without any special tooling: open the trade page, open browser devtools, go offline or throttle to Slow 3G, click Open Orders, and observe a named loading state that resolves into the table. The automated proof is a Playwright spec that blocks the websocket entirely and asserts the table still renders.

## Context References

Public refs:
- Direct user request, 2026-08-21: "investigate why on our site hyper open. When I click on tabs like open orders or positions or order history, it shows a loading screen for quite a while without giving any kind of message… get to the root cause of what they are doing we are not and possible ways we can improve that on our end without causing performance issues elsewhere (such as time to page load)." Followed by: "create an execution plan to fix this and implement it."

Repo artifacts:
- `docs/exec-plans/active/2026-06-11-pagespeed-desktop-performance-90.md` — the plan that introduced the account-tab module split this work depends on. Milestone M2d of that plan deliberately cut `account_surfaces` from ~280 KB raw to ~116 KB by moving the tab bodies into four lazy chunks. **Nothing in this plan may undo that split**, because that plan's Total Blocking Time budget depends on it.
- `AGENTS.md` — the operating contract: required gates, browser-testing routing, write authority.
- `docs/BROWSER_TESTING.md` — Playwright vs Browser MCP routing.

Local scratch refs (non-authoritative):
- None.

## Progress

- [x] (2026-08-21 16:20Z) Root cause proven by measurement, not inspection: chunk lands ~35 ms, paint 330–1700 ms later, and with the websocket stubbed out the panel never repaints at all.
- [x] (2026-08-21 16:55Z) M1 code change applied: `:account-tab-modules` added to `account-info-view-base-state-keys` in `src/hyperopen/views/trade_view.cljs`.
- [x] (2026-08-21 17:05Z) M1 verified end-to-end against a local dev build: websocket-blocked went from never-paints to 34–43 ms; websocket-live went from 1664 ms to ~36 ms. All 34 gates passed (6702 tests / 36173 assertions).
- [x] (2026-08-21 18:10Z) M0 — `tools/playwright/test/account-tab-lazy-module.spec.mjs` added: blocks the exchange websocket and asserts every lazy tab still renders. Proven red before the fix ("open-orders never left its pending state without websocket traffic") and green after.
- [x] (2026-08-21 17:40Z) M1 — `test/hyperopen/views/trade_view/lazy_tab_module_repaint_test.cljs` added. Removing the fix produces exactly 9 failures, one per lazy tab plus the two direct assertions, and no other test in the 5967-test suite changes.
- [x] (2026-08-21 18:00Z) M2 — asset-selector freeze now bypasses on lazy-module change; portfolio hover cache gained an `:account-tab-modules` term. Both proven red before their fixes.
- [x] (2026-08-21 18:25Z) M3 — `src/hyperopen/views/account_info/loading_skeleton.cljs` added; the anonymous spinner is gone, the dead `[:account-info :loading]` branch is deleted.
- [x] (2026-08-21 18:35Z) M4 — 10s timeout in `load-account-tab-module!` makes the failure branch reachable; Retry re-dispatches `:actions/select-account-info-tab`. Verified end-to-end in a browser: abort the chunk, error appears, press Retry with the chunk unblocked, the table renders.
- [x] (2026-08-21 18:45Z) M5 — `ROUTE_PREFETCH_MODULE_IDS` emits `<link rel="prefetch">` for `account_orders` and `account_positions_outcomes` on the trade and portfolio routes only.
- [x] (2026-08-21 19:00Z) Final — `npm run gates` 34/34 PASS (6709 tests, 36196 assertions); Playwright spec 3/3 passed; release build confirms the code split did not collapse.

## Surprises & Discoveries

- Observation: The bug is repaint starvation, not slow loading. Every intuition points at bundle size and every intuition is wrong.
  Evidence: Measured on production. `account_orders` is 8,673 bytes on the wire and its `load` event fires 26–40 ms after the click; the exported renderer function is present on `globalThis` at ~35 ms. The panel painted at 359–1135 ms across six trials with no long tasks (>30 ms) recorded in between — the main thread was idle, waiting.

- Observation: The decisive experiment is to remove the websocket, not to throttle the network.
  Evidence: With `window.WebSocket` stubbed out before app boot, clicking Open Orders left the spinner up for the full 12-second observation window while `hyperopen.views.account_orders_module.open_orders_tab_renderer` was a live function the whole time. Clicking Balances (a non-lazy tab, whose selection key *is* in the cached-render key) cleared it instantly, and re-clicking Open Orders then rendered immediately with no second network request. The renderer had been ready all along; nothing had told the panel.

- Observation: The sibling code path already had the fix, which is what makes this a one-line omission rather than a design flaw.
  Evidence: `trade-chart-view-base-state-keys` in `src/hyperopen/views/trade_view.cljs` lists `:trade-modules` — the exact analogue for the chart's lazy module. `account-info-view-base-state-keys` in the same file listed thirteen keys and not `:account-tab-modules`.

- Observation: Two other lazy-module loaders in this codebase are immune by construction, and the reason is worth writing down so the next person does not "fix" them too.
  Evidence: `surface-modules` and `trade-modules` pass their resolved renderer *into* the cached render as its first argument (`(memoized-account-info-view render-fn view-state opts)`), so when the chunk lands the argument goes from nil to a function and the cache key changes for free. `account-tab-modules` resolves its renderer *inside* the view, from a module-level atom that the cache key cannot see, so application state was the only available signal — and it was omitted.

- Observation: The existing unit test for the module loader could never have caught this.
  Evidence: `test/hyperopen/account_tab_modules_test.cljs` replaces `loader/load` with `(.resolve js/Promise nil)` and asserts on the contents of the store. It never renders anything. The store was always updated correctly; the panel just never looked at it.

- Observation: Every per-tab column-grid definition except Positions lives inside a lazy chunk, so a "pixel-perfect skeleton" is a trap.
  Evidence: Verified against `resources/public/js/manifest.json`. `tabs/open_orders.cljs` and `tabs/order_history.cljs` are in `:account_orders`; `tabs/trade_history.cljs` and `tabs/twap.cljs` are in `:account_activity`; `tabs/positions/layout.cljs` and `tabs/outcomes.cljs` are in `:account_positions_outcomes`. A non-lazy skeleton namespace that required any of them would silently promote that namespace — and its transitive tail — into `:account_surfaces`, collapsing the very code split this panel depends on. It would show up as a larger `account_surfaces.js`, not as a compile error.

- Observation: No existing test depended on the broken behaviour, which is the strongest available evidence that the fix is safe.
  Evidence: Deleting the one added line turns exactly nine assertions red — all of them new, all in the two files added by this plan — out of a 5967-test, 32716-assertion suite. Nothing else moves.

- Observation: A `with-redefs` stub of a multi-arity ClojureScript function must declare every arity the call site uses, or it fails at runtime rather than at compile time.
  Evidence: Stubbing `account-info-view/account-info-view` with `(fn [_state _options] …)` produced `TypeError: hyperopen.views.account_info_view.account_info_view.cljs$core$IFn$_invoke$arity$2 is not a function`. The single-arity `fn` form does not emit the arity dispatch properties the call site looks up. Writing the stub as `(fn ([_state] …) ([_state _options] …))` fixes it.

- Observation: A production deploy that rotates chunk hashes leaves old tabs unable to fail cleanly.
  Evidence: A request to a no-longer-existing `/js/<old-hash>.js` returns HTTP 200 with the SPA `index.html` body rather than 404, so the browser evaluates HTML as JavaScript. Combined with the absence of any timeout in the loader, `mark-account-tab-module-failed` is unreachable in practice and the panel has no way to report the failure. Recorded here as motivation for M4; the server-side half is out of scope and captured under "What this plan does not do".

## Decision Log

- Decision: Fix the cached-render key rather than removing the memoisation or the code split.
  Rationale: The memoisation and the split both exist for measured reasons — the split is Milestone M2d of the active PageSpeed plan and cut `account_surfaces` by roughly 60%. The defect is one missing key, not the strategy. Adding the key costs no bytes and no requires, and the bucket it names changes at most a handful of times per session.
  Date/Author: 2026-08-21, Claude (via direct user request).

- Decision: Do not promote the account tables into the `:main` bundle, and do not merge the four leaf chunks back into `account_surfaces`.
  Rationale: Promotion adds roughly 45–56 KB gzipped to the entry bundle on all nine route modules, while only two of them render the account panel; it moves bytes from a post-first-paint idle callback onto the parse-time critical path. Merging the leaves back reverses an active plan's TBT work. Neither buys anything the one-line key fix does not already deliver.
  Date/Author: 2026-08-21, Claude.

- Decision: The M3 loading state is generic (named message, announced, shimmer rows) rather than a per-tab replica of each table's column grid.
  Rationale: Once M1 lands, the pending state lasts roughly 30–60 ms on broadband, so a pixel-perfect skeleton buys an anti-layout-shift benefit that is almost never visible. Its cost is high and its risk is specific: the grid definitions live in lazy chunks, so reusing them requires lifting seven of them into shared namespaces, and getting that wrong silently collapses the code split. A named, announced, correctly-sized loading state fixes the user's actual complaint ("without giving any kind of message") with none of that risk. The per-tab variant is recorded below as a deferred follow-up, to be reconsidered only if slow-network telemetry says it matters.
  Date/Author: 2026-08-21, Claude.

- Decision: Retry re-dispatches the existing `[[:actions/select-account-info-tab tab]]` action instead of introducing a new action.
  Rationale: Adding an `:actions/*` or `:effects/*` in this repository means touching the implementation, the wiring in `app/actions.cljs` / `app/effects.cljs`, the registration catalog, both argument-contract namespaces, the effect-order contract, and a Lean formal model that must be regenerated with `bb tools/formal.clj sync`. `select-account-info-tab` already re-emits the module-load effect unconditionally and already clears the stored error via `mark-account-tab-module-loading`, so the existing action does the job exactly.
  Date/Author: 2026-08-21, Claude.

- Decision: The committed browser regression test blocks the websocket rather than throttling the network.
  Rationale: Throttling makes the spinner last longer but still resolves, so a throttled test passes both before and after the fix and proves nothing. Removing websocket traffic entirely turns the bug into a hang and the fix into a pass — a genuine red/green boundary.
  Date/Author: 2026-08-21, Claude.

## Outcomes & Retrospective

All six milestones are implemented and verified. The user-visible result matches the purpose stated at the top: clicking a lazy account tab now paints its table within roughly 40 ms of the chunk landing, on a page with no websocket traffic at all — a condition under which the panel previously never painted.

Measured against a local development build: with the websocket stubbed out, Open Orders went from never painting to painting in 34–43 ms; with the websocket live it went from 1664 ms to roughly 36 ms. Per tab with no websocket traffic: Positions 27 ms, Outcomes 28 ms, Open Orders 39 ms, TWAP 20 ms, Trade History 34 ms.

The regression guards are real, not decorative. Removing the one-line fix turns exactly nine ClojureScript assertions red — one per lazy tab plus two direct ones — and nothing else in the 5967-test suite moves. It also fails the Playwright spec with the message "open-orders never left its pending state without websocket traffic". Each M2 fix was likewise proven red before it was applied, and the M4 timeout is load-bearing: without it, the stalled-chunk test never completes at all.

The code split survived, which was the largest risk. After a full release build the four lazy tab chunks are byte-for-byte identical to what production serves today (`account_orders` 38,624; `account_positions_outcomes` 118,362; `account_activity` 33,994; `account_funding_history` 16,263). `account_surfaces` grew by 3,740 raw bytes — the new loading-skeleton namespace, landing exactly where it was meant to. `main` moved by +718 raw / −248 gzip.

Complexity: net roughly neutral, and the parts that grew earn it. M1 and M2 *reduced* complexity by removing a class of "why is the UI stale?" behaviour — three cache keys now tell the truth about lazily-loaded code, and the rule for when that is required is written down in the code. M3 and M4 genuinely add surface (one new namespace, one timeout, one retry path), justified because they convert two silent failures — an anonymous wait and an unreportable load failure — into states a user can read and act on.

What remains open is recorded under "What This Plan Does Not Do": the origin still serves the SPA shell instead of a 404 for a missing chunk URL, the per-tab skeleton was deliberately declined, and the `account_positions_outcomes` modal split is a separate opportunity. None of them block this work.

One process note worth carrying forward. The existing loader test passed throughout the entire life of this bug because it asserted on the store and never rendered anything. A test that drives real state through a real view is worth several that inspect state in isolation — and the cheapest way to find a repaint bug is to remove the thing that accidentally hides it, which here meant blocking the websocket rather than throttling it.

## Context and Orientation

Assume no prior knowledge of this repository. Everything needed is below.

Hyperopen is a ClojureScript single-page application, compiled by shadow-cljs, that talks to the Hyperliquid exchange. The user interface is built with Replicant, a library that renders plain Clojure data structures ("hiccup" — nested vectors like `[:div {:class ["p-2"]} "text"]`) into DOM. Application state lives in one atom, referred to throughout as the store or app-db; it is a single map whose top-level keys (`:account-info`, `:webdata2`, `:orders`, and so on) are called root keys.

Rendering is driven by a watch on that atom, installed by `install-render-loop!` in `src/hyperopen/runtime/bootstrap.cljs`. When the atom changes, the watch schedules one render on the next animation frame. Crucially the watch begins with a guard, `(when (not= old-state new-state) …)`: if a swap produces an equal value, nothing renders.

The trade page is assembled in `src/hyperopen/views/trade_view.cljs`. Rendering the whole page on every websocket tick would be wasteful, so each heavy panel is memoised. The mechanism is `memoize-last` (in that file): it keeps the most recent argument list and result, and if the next call's arguments are `=` to the stored ones it returns the stored result without calling through. To make that comparison cheap and stable, each panel is given only a slice of the store rather than the whole thing — `select-view-state` is a `select-keys` over a fixed vector of root keys, one vector per panel. The account panel's vector is `account-info-view-base-state-keys`.

Lazily-loaded code. The trade page's account tabs are split across four separately-downloaded JavaScript chunks so that the initial page load does not have to parse them. The mapping is declared in `shadow-cljs.edn`: `account_positions_outcomes` serves the Positions and Outcomes tabs, `account_orders` serves Open Orders and Order History, `account_activity` serves Trade History and TWAP, and `account_funding_history` serves Funding History. All four depend on `account_surfaces`, which is loaded during startup. The Balances tab is not lazy. The loader that fetches a chunk on demand is `src/hyperopen/account_tab_modules.cljs`; when a chunk resolves it calls `mark-account-tab-module-loaded`, which writes to the `:account-tab-modules` root key.

The defect. `account-info-view-base-state-keys` did not list `:account-tab-modules`. So when a chunk landed and the loader wrote to that key, the account panel's slice was unchanged, `memoize-last` returned its stored hiccup — which contained the spinner — and the panel did not repaint. It repainted only when some *other* key in the slice changed, which on a live trade page is whenever market data arrives. The panel's view code, `src/hyperopen/views/account_info_view.cljs`, decides between spinner and content using `lazy-tab-pending?`, which asks the loader whether the renderer has resolved. That question was being answered correctly; nobody was asking it.

Files you will touch, with what each is for:

- `src/hyperopen/views/trade_view.cljs` — trade page assembly, the memoised panel slices, and the asset-selector freeze. Already edited for M1; edited again in M2.
- `src/hyperopen/views/portfolio_view.cljs` — the portfolio route, which renders the same account panel and has its own hover cache. Edited in M2.
- `src/hyperopen/views/account_info_view.cljs` — chooses between error, pending and content states for the account panel. Edited in M3.
- `src/hyperopen/views/account_info/vm.cljs` — builds the account panel's view model. Edited in M3 to remove a dead flag.
- `src/hyperopen/account_tab_modules.cljs` — the chunk loader. Edited in M4.
- `tools/release-assets/site_metadata.mjs` — builds the `<head>` of the released `index.html`. Edited in M5.

## Plan of Work

### Milestone 0 — Make the failure reproducible by anyone

Nothing in the repository today lets a contributor see this bug. Before changing more code, commit the harness that demonstrates it, so the red/green boundary is a fact in the tree rather than a claim in a document.

Add a Playwright specification at `tools/playwright/test/account-tab-lazy-module.spec.mjs`. Playwright is the repository's browser-testing tool; specs live in `tools/playwright/test/` and are run against a development server that the Playwright configuration starts automatically. This spec must do three things. First, before any application code runs, install a websocket block scoped to the exchange endpoint only — `page.routeWebSocket("wss://api.hyperliquid.xyz/**", …)` — leaving shadow-cljs's own hot-reload socket alone, because blocking that one prevents the development bundle from ever settling. Second, navigate to `/trade` and select a lazy tab using the repository's existing convention: tabs carry `data-role="account-info-tab-<tab-name>"` (see `src/hyperopen/views/account_info/tab_actions.cljs`), and `tools/playwright/test/trade-regressions.spec.mjs` already contains a `selectAccountTab` helper to copy. Third, assert that the tab's content region renders — not that a spinner disappears, but that the destination table's rows viewport appears. Tag the test `@regression`, matching the repository's tagging convention, so it joins the dispatched CI suite without slowing the `@smoke` grep.

At the end of this milestone the spec exists and passes on the current tree. To see it fail, temporarily remove `:account-tab-modules` from `account-info-view-base-state-keys` and re-run it; it will time out. Restore the line afterwards. Record both transcripts in `Artifacts and Notes`.

### Milestone 1 — Regression tests in the fast suite

The Playwright spec proves the behaviour but takes tens of seconds and is not run on push. The ClojureScript suite runs in about 38 seconds on every `npm test` and every `npm run check`, so the durable guard belongs there.

Create `test/hyperopen/views/trade_view/lazy_tab_module_repaint_test.cljs`. Do not add these tests to the existing `test/hyperopen/views/trade_view/render_cache_test.cljs`: that file is 498 lines against a hard 500-line namespace-size gate with no exception entry, so adding to it fails `npm run lint:namespace-sizes`.

The repository already has the right idiom, in `render_cache_test.cljs`: drive `trade-view/trade-view` twice with a counting stub renderer and assert how many times the stub was invoked. The support namespace `hyperopen.views.trade.test-support` provides `active-asset-state`, `with-viewport-width` and `with-account-surface-exports`.

Write two tests. The primary one is behavioural: build two application states that differ *only* in `:account-tab-modules` — the first with the Open Orders chunk still loading, the second with it loaded — render the trade view with each, and assert the account panel's render function was invoked both times. Assert the count is 1 after the first render as well as 2 after the second; that first assertion is load-bearing, because without it the test would still pass if memoisation broke entirely, which is a different regression. The second test is a data-driven invariant over every lazy tab: take `tab-registry/available-tabs`, keep those for which `account-tab-modules/lazy-tab?` is true, and for each one drive the state through the *production* mutators `mark-account-tab-module-loading` and `mark-account-tab-module-loaded` and assert the panel repaints. Driving the real mutators rather than hand-written maps keeps the test honest if the state shape ever moves. Pin the derived tab list with an equality assertion so that adding a ninth lazy tab forces a deliberate look.

Do not write a test that merely asserts the vector contains the keyword. That asserts the fix is spelled the way it is spelled; it cannot catch a re-introduced snapshot or any of the M2 paths.

Note that `test/test_runner_generated.cljs` is generated by `npm run test:runner:generate` (which runs automatically at the start of `npm test`) and is tracked by git. Adding a test file changes it, and that change must be committed or the repository-state lint gate fails in CI.

### Milestone 2 — Close the two remaining paths of the same class

M1 fixes the common case. Two other code paths can still hold a stale account panel when a chunk lands, and both have been confirmed to still fail with M1 applied.

The first is the asset-selector freeze in `src/hyperopen/views/trade_view.cljs`. While the asset-selector dropdown is open on a desktop layout, `selector-scroll-snapshot` returns a previously-captured panel state instead of recomputing, so that scrolling the dropdown does not re-render heavy panels underneath it. This freeze exists to fix real scroll jank and four existing tests in `render_cache_test.cljs` assert that it holds, so it must not be removed. The correct change is a bypass: when the lazily-loaded module state differs from the state captured in the snapshot, recompute anyway. Implement it inside `selector-scroll-snapshot`'s frozen branch by comparing a caller-supplied invalidation value against the one stored with the snapshot, and pass `:account-tab-modules` as that value for the account panel. The four existing freeze tests should continue to pass unchanged, because they never vary module state; if any needs its expectation widened, widen it rather than deleting it, and say why in `Surprises & Discoveries`.

The second is the portfolio route's hover cache in `src/hyperopen/views/portfolio_view.cljs`. While a chart hover is active, `portfolio-view` returns a cached `sections` map wholesale, and that map contains the entire account table. Its cache key already has terms for the route, the volume-history popover and the fee schedule; add a term for `:account-tab-modules`. Mirror the fixture in `test/hyperopen/views/portfolio_view_hover_freeze_test.cljs`, which clears hover state before and after each test — the cache is a `defonce` atom with no reset hook and will otherwise leak into the next namespace's tests.

Add one test per path, in the M1 test file or a sibling, each proven to fail before its fix.

### Milestone 3 — A named, announced loading state

`loading-spinner` in `src/hyperopen/views/account_info_view.cljs` is a bare spinning div: no text, no `role`, no `aria-live`, no `aria-busy`, no `data-role`. It is the only loading affordance in `src/` with none of those. This is the second half of the user's complaint and the part M1 does not address.

Create `src/hyperopen/views/account_info/loading_skeleton.cljs`. It must require only namespaces that are already in `:main` or `:account_surfaces` — `hyperopen.views.account-info.table`, `hyperopen.views.account-info.tab-registry` and `hyperopen.views.account-info.shared` are safe. It must not require anything under `views/account_info/tabs/` except `balances`, because everything else lives in a lazy chunk and requiring it would pull that chunk into `account_surfaces`.

Follow the house pattern in `src/hyperopen/views/leaderboard/states.cljs`: a wrapper carrying a `:data-role`, a small pulsing dot marked `:aria-hidden true`, one sentence of sentence-case copy naming the thing being fetched, and a small clamped number of shimmer rows built from the `ui-loading-shimmer` class (defined in `src/styles/surfaces/app-shell.css`, and already covered by a `prefers-reduced-motion` rule — do not author a new shimmer class with new raw colours, because `npm run lint:theme-colors` holds a baseline). Vary the placeholder widths across rows so the block does not read as a barcode.

The headline must name the tab, and it must use the *overridden* label rather than the raw one: `account_info_view.cljs` merges `voice/account-tab-overrides`, which renames Funding History to "Interest" on the portfolio route. Give the wrapper `role="status"`, `aria-live="polite"` and `aria-busy="true"` so a screen reader announces the wait.

Then rewrite `error-state` in the same view to a readable message plus a Retry button (wired in M4), and delete the dead branch: `[:account-info :loading]` is read at `views/account_info/vm.cljs` and surfaced as `:loading?`, but nothing in `src/` ever writes it. Remove the read, the view-model entry, and the `(and (nil? selected-extra-renderer) loading?)` arm in `account_info_view.cljs`, leaving three honest states — error, pending, content. Do not wire the dead key up; the real per-section contract is `[:account-info <section> :loading?]`.

One existing test, `account-info-panel-shows-loading-spinner-while-a-lazy-tab-module-is-pending-test` in `test/hyperopen/views/account_info_view_test.cljs`, finds the pending state by looking for the `animate-spin` class. Rewrite it to assert on the new `data-role` and `role="status"` instead. That test also shows the sanctioned way to force the pending state from a unit test: stub `account-tab-modules/tab-ready?`, `tab-loading?` and `resolved-tab-renderer`.

### Milestone 4 — Make failure reachable and recoverable

Today a stalled chunk request hangs forever: `loader/load` never rejects, so the `.catch` in `load-account-tab-module!` never runs, `mark-account-tab-module-failed` is unreachable, and the panel shows a pending state indefinitely. Race the load against a timeout inside `load-account-tab-module!` in `src/hyperopen/account_tab_modules.cljs` — reject after roughly ten seconds so the existing `.catch` fires and stores an error. Keep the timeout a named constant with a comment explaining that it exists to make the error branch reachable, not to make loading faster. Ensure the rejected promise is handled at the adapter boundary in `src/hyperopen/runtime/effect_adapters.cljs` so it does not surface as an unhandled rejection.

Wire the Retry button from M3 to `[[:actions/select-account-info-tab tab]]`. That action always re-emits the module-load effect with no guard on current state, and `mark-account-tab-module-loading` clears the stored error, so the panel returns to its loading state and tries again. Two harmless side effects ride along: a redundant write of the already-selected tab, and a same-URL history push on the trade route. Note them in the code comment so nobody treats them as bugs later.

### Milestone 5 — Prefetch the two hottest chunks

With M1 in place the remaining wait is the chunk fetch itself — 28 ms for `account_orders` and about 58 ms for `account_positions_outcomes` on a warm broadband connection, considerably more on mobile. Removing it costs nothing at page-load time if it is done at the browser's lowest priority.

The released `index.html` head is assembled in `tools/release-assets/site_metadata.mjs`, which already has a `ROUTE_PRELOAD_MODULE_IDS` map and emits `<link rel="preload" as="script">` for `trade_chart` and `account_surfaces` on `/trade`. Add a sibling list that emits `<link rel="prefetch">` for `account_orders` and `account_positions_outcomes` on the trade and portfolio routes.

Use `rel="prefetch"`, not `rel="preload"`, and deliberately omit the `as` attribute. `preload` is a high-priority fetch that competes with the critical path and warns if unused promptly; `prefetch` is dispatched at the browser's lowest priority and costs no main-thread time. The `as` attribute is omitted because the chunk is later fetched by the module loader as a script whose request destination will not match an `as="script"` preload entry — reuse here is mediated by the HTTP cache, and these assets are served `immutable`.

Extend the assertions in `tools/release-assets/generate_release_artifacts.test.mjs`, which already covers the preload links and runs inside `npm run check`. Do not put this assertion in the release Playwright suite: that configuration runs a full production build on every invocation.

## Concrete Steps

Run everything from the repository root, `/hyperopen` (in this worktree, `/Users/barry/projects/hyperopen/.claude/worktrees/hyper-open-tab-loading-12af42`).

A fresh worktree has no `node_modules` and shadow-cljs is not on `PATH`, which makes every gate fail with an opaque, misleading error. Bootstrap first:

    npm run setup:worktree

Expected output:

    [setup:worktree] linked node_modules -> /Users/barry/projects/hyperopen/node_modules

The full gate matrix, which does not stop at the first failure:

    npm run gates

Expected tail:

    Totals:
      gates passed:            34/34
    Overall: PASS

To run a single ClojureScript test namespace instead of all 859 (there is no built-in filter; this merges a one-off regular expression onto the websocket test build, and the lightweight-charts shim must be carried across or unrelated namespaces fail to resolve):

    npx shadow-cljs --force-spawn compile ws-test --config-merge '{:output-to "out/one-test.js" :ns-regexp "^hyperopen\.views\.trade-view\..*-test$" :js-options {:resolve {"lightweight-charts" {:target :file :file "test/shims/lightweight_charts_stub.cjs"}}}}' && node out/one-test.js

Note that this overwrites the cached `ws-test` build, so the next `npm run test:websocket` recompiles from scratch.

For the Playwright specification, start the development server once and reuse it:

    npm run dev:browser-inspection

then, in a second shell:

    PLAYWRIGHT_REUSE_EXISTING_SERVER=true npx playwright test tools/playwright/test/account-tab-lazy-module.spec.mjs --workers=1

Always pass `--workers=1`; the suite is flaky at higher worker counts because every worker points at the same development server.

Stop the development server when finished, and clean up any browser inspection sessions:

    npm run browser:cleanup

## Validation and Acceptance

Acceptance is behavioural. A reviewer should be able to confirm each of the following.

With the websocket blocked entirely, clicking Positions, Outcomes, Open Orders, TWAP or Trade History on `/trade` renders that tab's table within a few tens of milliseconds. Before M1 the panel never rendered at all under this condition. This is asserted automatically by `tools/playwright/test/account-tab-lazy-module.spec.mjs`.

Running `npm test` reports zero failures, and the new namespace `hyperopen.views.trade-view.lazy-tab-module-repaint-test` appears in the output. Removing `:account-tab-modules` from `account-info-view-base-state-keys` and re-running makes exactly those new tests fail with a message naming the tab that did not repaint — for example `no repaint after chunk arrival for tab :positions`. Restore the line and they pass. Both transcripts belong in `Artifacts and Notes`.

`npm run gates` reports 34/34 PASS.

On a page throttled to Slow 3G, clicking Open Orders shows a loading state that names the tab — "Loading Open Orders…" — rather than an anonymous spinner, and a screen reader announces it because the region carries `role="status"` and `aria-live="polite"`. Blocking the chunk request entirely (devtools request blocking on `/js/account_orders*`) shows a readable error with a Retry button after the timeout elapses, and pressing Retry re-attempts the load.

After `npm run build`, `out/release-public/index.html` for the trade route contains `<link rel="prefetch" href="/js/account_orders.<hash>.js">` and the same for `account_positions_outcomes`, and contains no new `rel="preload"` entries. `npm run test:release-assets` passes.

Finally, the code split must not have collapsed. After `npm run build`, confirm that `resources/public/js/account_surfaces.*.js` has not grown materially and that `account_orders`, `account_positions_outcomes`, `account_activity` and `account_funding_history` still exist as separate files. Record the before/after byte sizes in `Artifacts and Notes`. Reference figures from the 2026-08-21 production build: `account_surfaces` 132,026 raw / 31,128 gzip; `account_positions_outcomes` 118,362 / 23,837; `account_orders` 38,624 / 8,673; `account_activity` 33,994 / 6,810; `account_funding_history` 16,263 / 3,632.

## Idempotence and Recovery

Every step is safe to repeat. `npm run setup:worktree` is a no-op once the symlink exists. `npm run gates` and the test commands are read-only with respect to source. `npm run build` overwrites its own outputs.

The single highest-risk step is M3's new namespace, because an accidental `:require` of a lazy tab namespace collapses the code split silently — it produces a larger `account_surfaces.js`, not an error. If the size check in `Validation and Acceptance` shows growth, inspect `resources/public/js/manifest.json`, find which namespace moved, and remove the offending require.

To roll back the whole plan, revert the commits on this branch; every change is additive except the deletion of the dead `[:account-info :loading]` branch in M3, which restores cleanly from git history.

## Artifacts and Notes

Root-cause measurement, production, 2026-08-21. Click on Open Orders with a cold module cache, instrumented in a real (non-hidden) page:

    click                       t = 0 ms
    spinner painted             t = 9 ms
    script tag inserted         t = 3 ms
    chunk response complete     t = 34 ms   (8,673 bytes gzip)
    script onload / renderer
      present on globalThis     t = 36 ms
    panel content painted       t = 394 ms
    long tasks (>30 ms) in
      the intervening window    none

Six-trial distribution of the gap between the renderer becoming available and the panel painting: 327, 448, 657, 905, 984, 996 ms.

Websocket-blocked trials, before the fix (four runs, production build): the panel never painted within the 12-second observation window, while the renderer was a live function throughout.

Websocket-blocked trials, after the fix (local development build): painted at 43, 34, 36 ms. Per-tab, websocket blocked: Positions 27 ms, Outcomes 28 ms, Open Orders 39 ms, TWAP 20 ms, Trade History 34 ms.

The M1 diff in full:

    --- a/src/hyperopen/views/trade_view.cljs
    +++ b/src/hyperopen/views/trade_view.cljs
    @@ -37,6 +37,7 @@
        [:account
         :account-context
         :account-info
    +    :account-tab-modules
         :margin-rec
         :orders

Gate matrix after all milestones, 2026-08-21:

    gates passed:            34/34
    tests run:               6709
    assertions run:          36196
    total suite time:        2m 2s
    Overall: PASS

Red-before evidence for M1, produced by deleting the one added line and re-running `npm test`:

    FAIL in (account-panel-repaints-when-lazy-tab-chunk-lands-test)
      account panel must re-render when the open-orders chunk arrives
    FAIL in (every-lazy-tab-chunk-arrival-repaints-the-account-panel-test)
      no repaint after chunk arrival for tab :positions
      ... one per lazy tab: :outcomes :open-orders :twap :trade-history
          :funding-history :order-history
    FAIL in (account-info-view-state-tracks-lazy-tab-module-progress-test)
      the memoized account panel slice must observe lazy tab module progress
    Ran 5963 tests containing 32716 assertions.
    9 failures, 0 errors.

Same deletion, against the browser spec:

    Error: open-orders never left its pending state without websocket traffic
    2 failed

Playwright, after the fix:

    Running 3 tests using 1 worker
    ✓ every lazy account tab renders with no websocket traffic @regression
    ✓ the pending state names the tab it is loading @regression
    ✓ a chunk that never arrives reports a retryable error @regression
    3 passed (34.8s)

Release build after all milestones, compared with the chunks production serves today. The four
lazy tab chunks are byte-identical, which is the proof the code split did not collapse:

    chunk                        raw before    raw after     delta
    account_orders                   38,624       38,624         0
    account_positions_outcomes      118,362      118,362         0
    account_activity                 33,994       33,994         0
    account_funding_history          16,263       16,263         0
    account_surfaces                132,026      135,766    +3,740   (loading skeleton)
    main                          2,788,902    2,789,620      +718

Generated head for the trade route:

    <link rel="preload" as="script" href="/js/trade_chart.<hash>.js" ...>
    <link rel="preload" as="script" href="/js/account_surfaces.<hash>.js" ...>
    <link rel="prefetch" href="/js/account_orders.<hash>.js" data-hyperopen-perf="module-prefetch" />
    <link rel="prefetch" href="/js/account_positions_outcomes.<hash>.js" data-hyperopen-perf="module-prefetch" />

The leaderboard route emits no account-tab prefetch, confirming the hints are route-scoped.

Note on the soft bundle-budget warning printed at the end of `npm run build`: `main` gzip is
658,552 against a 640,000 advisory. That breach predates this work — the build production serves
today is 654,209 gzip, already over the same budget — and this plan moved `main` gzip down by 248
bytes, not up. `lint:bundle-budget` is advisory (exit 0) and is not one of the 34 gates.

## Interfaces and Dependencies

In `src/hyperopen/views/trade_view.cljs`, `account-info-view-base-state-keys` must contain `:account-tab-modules`, and `selector-scroll-snapshot` gains an invalidation parameter so a frozen snapshot can be bypassed when lazily-loaded module state changes:

    (defn- selector-scroll-snapshot
      [snapshot* freeze? invalidate-on next-state-fn]
      ...)

In `src/hyperopen/views/account_info/loading_skeleton.cljs`, define:

    (defn lazy-tab-loading-state
      "Pending state for an account tab whose code chunk is still downloading.
       tab-label is the display label, already resolved through any route
       overrides. Returns hiccup carrying role=status and aria-live=polite."
      [{:keys [tab-label row-count]}])

    (defn lazy-tab-error-state
      "Failed-chunk state. on-retry-actions is an action vector re-dispatched
       when the reader presses Retry."
      [{:keys [tab-label message on-retry-actions]}])

In `src/hyperopen/account_tab_modules.cljs`, `load-account-tab-module!` keeps its existing two-argument signature `[store tab]` and its existing return type (a promise resolving to the renderer function), but the returned promise now rejects if the chunk has not resolved within the timeout constant.

In `tools/release-assets/site_metadata.mjs`, add a `ROUTE_PREFETCH_MODULE_IDS` map alongside `ROUTE_PRELOAD_MODULE_IDS`, consumed by the existing `buildRoutePerformanceHintsMarkup` so that no new call site is needed in `generate_release_artifacts.mjs`.

## What This Plan Does Not Do

The deployment serves HTTP 200 with the SPA `index.html` for a request to a chunk URL that no longer exists, rather than 404. That turns a post-deploy stale-tab chunk load into an attempt to evaluate HTML as JavaScript. M4's timeout means such a tab now reports an error and offers Retry instead of hanging, which is the user-visible half of the problem; making the origin return 404 for missing `/js/*` is a deployment-configuration change and belongs in its own plan.

A per-tab skeleton that reproduces each table's exact column grid is deliberately out of scope; see the Decision Log. Revisit only with evidence that slow-network users are affected.

Real brotli compression at the CDN edge would cut the main bundle by roughly 150 KB (23%), measured. It is unrelated to this bug and belongs with the active PageSpeed plan.

Splitting the position modals out of `account_positions_outcomes` — about 65% of that chunk is modal and popover code that rendering the table never touches — is a real opportunity but adds another lazy boundary, which is another chance to reintroduce exactly the bug this plan fixes. If it is ever done, the new pending bucket must be added to the panel's memoised slice in the same commit.

## Revision Notes

- 2026-08-21 (second revision): All six milestones implemented and verified; plan moved from `active/` to `completed/`. Three discoveries during implementation are recorded above in `Surprises & Discoveries`: the multi-arity stub trap, the fact that no existing test depended on the broken behaviour, and the confirmation that the code split survived. The M3 scope decision held up — the generic loading state took one dependency-free namespace, where the per-tab variant would have required lifting seven grid definitions out of lazy chunks.
- 2026-08-21: Plan created from a completed root-cause investigation. M1 was already implemented and verified before the plan was written, so it is recorded as done in `Progress` with its evidence in `Artifacts and Notes`; the remaining milestones are unstarted. The scope decision that most shaped the plan is recorded in the Decision Log: the M3 loading state is generic rather than a per-tab replica, because the per-tab grids live in lazy chunks and reusing them risks silently collapsing the code split for a benefit that lasts about 40 milliseconds.
