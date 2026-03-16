# Release Build Performance Leverage Plan

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Linked live work: `hyperopen-e0cr` ("Reduce trade-route render churn under live market updates").

## Purpose / Big Picture

After this work, the release build of Hyperopen should reach useful interactivity on the default trade load much sooner instead of painting quickly and then spending several more seconds evaluating JavaScript, laying out live market surfaces, and continuing startup work in the background. A user opening the release build at `http://localhost:8081/` should see the trade shell become usable sooner, with smaller initial payloads, less startup churn, and less repeated rendering under live websocket traffic.

The work is intentionally ordered by leverage. The first wave removes large cold-load bytes that are easy to reclaim. The second wave reduces the amount of JavaScript shipped and executed on first load. The third wave narrows startup work to what the landing route truly needs. The fourth wave reduces repeated rendering and layout work caused by live market updates. The final wave handles repeat-visit polish such as cache headers and back/forward cache eligibility after the primary bottlenecks are addressed.

## Progress

- [x] (2026-03-16 11:58Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/AGENTS.md`, and `/hyperopen/docs/PLANS.md` for planning and repository guardrails.
- [x] (2026-03-16 11:59Z) Audited the March 16 release Lighthouse report captured against `http://localhost:8081/` and extracted the dominant performance regressors.
- [x] (2026-03-16 12:00Z) Mapped the dominant regressors to concrete files in the current app shell, font configuration, startup flow, and render loop.
- [x] (2026-03-16 12:01Z) Authored this deferred ExecPlan so the performance work can be promoted into active execution with the research already embedded here.
- [x] (2026-03-16 12:18Z) Promoted this plan into `/hyperopen/docs/exec-plans/active/` and linked live `bd` work via `hyperopen-finw`.
- [x] (2026-03-16 16:24Z) Implemented Milestone 1 by removing Splash from the default stylesheet path, restyling the header brand on the system stack, and routing canvas text measurement through a shared UI-font resolver.
- [x] (2026-03-16 16:31Z) Rebuilt the release app, ran required validation gates, and confirmed via extension-free Lighthouse that `http://localhost:8081/` now issues zero font requests on the default route; the run still hit the 45 second load timeout, so Milestone 0 remains open for clean score baselining.
- [x] (2026-03-16 16:46Z) Created and claimed `hyperopen-7nop` to execute Milestone 2 as route-level bundle splitting while keeping the trade route in the initial browser module.
- [x] (2026-03-16 17:09Z) Completed the route-level Milestone 2 split: portfolio, funding comparison, staking, API-wallets, and vault screens now compile into separate browser modules and load on demand through the router/runtime effect path.
- [x] (2026-03-16 17:10Z) Validated the route-level split with `npm test`, `npm run build`, `npm run test:websocket`, and `npm run check`; the release build now emits `portfolio_route.js`, `funding_comparison_route.js`, `staking_route.js`, `api_wallets_route.js`, and `vaults_route.js`.
- [x] (2026-03-16 17:11Z) Closed `hyperopen-7nop` as completed and opened `hyperopen-0pgn` for the remaining trade-chart split that still belongs to Milestone 2.
- [x] (2026-03-16 18:31Z) Completed the remaining Milestone 2 split by moving the trade chart stack into a dedicated `trade_chart` browser module, loading it on `/trade` startup and navigation, and rendering a stable chart-panel shell while the async module resolves.
- [x] (2026-03-16 18:31Z) Validated the completed Milestone 2 split with `npm run check`, `npm test`, `npm run test:websocket`, and `npm run build`; the release output now emits `trade_chart.js` (`516,614` bytes) and the initial `main.js` release bundle is down to `2,398,298` bytes.
- [x] (2026-03-16 18:31Z) Closed `hyperopen-0pgn` as completed, closed the earlier deferred-route regression issue `hyperopen-8vww`, and opened `hyperopen-p4dz` for Milestone 3 startup-bootstrap reduction work.
- [x] (2026-03-16 19:08Z) Completed Milestone 3 by removing asset-selector market bootstrap from the critical startup phase and eliminating the automatic deferred full selector-market fetch from cold `/trade` startup; full selector-market expansion now waits for explicit UI demand such as opening the asset selector or navigating to a route that needs it.
- [x] (2026-03-16 19:08Z) Validated the completed Milestone 3 cut with `npm run check`, `npm test`, `npm run test:websocket`, and `npm run build`; startup/runtime integration tests now lock in that initial `/trade` startup only fetches asset contexts and does not auto-schedule selector-market expansion.
- [x] (2026-03-16 19:08Z) Closed `hyperopen-p4dz` as completed and opened `hyperopen-e0cr` for Milestone 4 render-churn reduction work.
- [ ] Implement Milestone 0 (clean measurement harness and extension-free baseline capture).
- [x] Implement Milestone 1 (remove non-essential cold-load font payloads).
- [x] Implement Milestone 2 (split the monolithic app bundle and defer non-critical route code).
- [x] Implement Milestone 3 (reduce startup fetch and subscription work on initial trade load).
- [ ] Implement Milestone 4 (reduce whole-app render churn and expensive live-surface layout work).
- [ ] Implement Milestone 5 (repeat-visit caching and back/forward cache polish).

## Surprises & Discoveries

- Observation: the release build paints much faster than the dev/watch build, but interactivity remains late because JavaScript, layout, and rendering continue heavily after first paint.
  Evidence: the March 16 release Lighthouse run reported FCP `1.7s`, LCP `1.9s`, Speed Index `2.8s`, but TTI `6.7s`, with `mainthread-work-breakdown` showing `24.6s` and `bootup-time` showing `7.9s`.

- Observation: the default route still downloads almost 0.9 MB of fonts before the user has expressed any typography preference.
  Evidence: the report transferred `534,599` bytes for `Splash-Regular.ttf` and `352,526` bytes for `InterVariable.woff2`, while `/hyperopen/src/styles/main.css` defaults the app to system fonts and only the header brand plus a hard-coded canvas measurement string require those families.

- Observation: the first-party bundle remains a single large browser entrypoint with substantial unused JavaScript on the landing route.
  Evidence: the report transferred `682,739` bytes for `/js/main.js` and attributed `370,345` wasted bytes to that same file. `/hyperopen/shadow-cljs.edn` still defines one `:main` app module for the browser build, and `/hyperopen/src/hyperopen/views/app_view.cljs` eagerly requires every major route view.

- Observation: live-market rendering cost is likely a first-class bottleneck, not just network payload.
  Evidence: the report attributed `7.9s` to script evaluation, `5.6s` to rendering, and `5.2s` to style/layout. `/hyperopen/src/hyperopen/runtime/bootstrap.cljs` installs a whole-store render loop, and the landing trade route keeps chart, orderbook, ticket, and account surfaces mounted simultaneously.

- Observation: the report was captured with browser extensions enabled, so extension noise exists and must not be mistaken for app-owned work.
  Evidence: the `unused-javascript` audit includes `chrome-extension://...` entries for MetaMask and another extension. This does not erase the app-owned bottlenecks, but it means clean reruns are required before and after each milestone.

- Observation: startup still issues a burst of `/info` requests on first load even though the landing route is the trade shell.
  Evidence: the release report still shows 19 third-party requests totaling about `519 KB`, and `/hyperopen/src/hyperopen/startup/runtime.cljs` immediately initializes websocket modules and starts critical plus deferred market bootstrap work.

- Observation: Milestone 1 removed all cold-load font requests from the default route, not just the oversized Splash download.
  Evidence: the extension-free desktop Lighthouse rerun written to `/tmp/hyperopen-m1-fonts-desktop.json` reports `resource-summary` font `requestCount: 0` and `transferSize: 0`, and the `font-display` audit contains zero items.

- Observation: headless Lighthouse is still not a trustworthy source for score deltas on this route because the page consistently exhausts the 45 second load budget before the browser reports load complete.
  Evidence: the extension-free rerun includes the warning `The page loaded too slowly to finish within the time limit. Results may be incomplete.` and the captured timing shows `lh:driver:navigate` consuming about `45019 ms`.

- Observation: the route-level Milestone 2 split is real at build time; release output now contains dedicated chunks for every non-trade top-level screen.
  Evidence: `npm run build` now emits `resources/public/js/portfolio_route.js` (`413,024` bytes), `resources/public/js/funding_comparison_route.js` (`98,351` bytes), `resources/public/js/staking_route.js` (`211,052` bytes), `resources/public/js/api_wallets_route.js` (`90,718` bytes), and `resources/public/js/vaults_route.js` (`851,863` bytes), alongside `module-loader.edn` and `module-loader.json`.

- Observation: route-level splitting alone does not finish Milestone 2 because the initial trade bundle still contains the entire chart stack.
  Evidence: after the route split, `resources/public/js/main.js` remains `15,007,924` bytes on disk in the release output, which confirms that the next meaningful JavaScript cut is still the trade-chart/libraries path.

- Observation: the dedicated trade-chart module materially shrank the initial release bundle while preserving the existing trade layout shell.
  Evidence: after completing the trade-chart split, `npm run build` emits `resources/public/js/trade_chart.js` at `516,614` bytes and reduces `resources/public/js/main.js` to `2,398,298` bytes on disk, down from the earlier post-route-split `15,007,924` byte main bundle recorded in this plan.

- Observation: the startup “deferred bootstrap” still ran only `1200 ms` after initialization, so leaving it enabled would continue to pull the full selector-market catalog into the same cold-load performance window.
  Evidence: `/hyperopen/src/hyperopen/config.cljs` sets `:startup {:deferred-bootstrap-delay-ms 1200}`, `/hyperopen/src/hyperopen/app/startup.cljs` wires that delay into `schedule-idle-or-timeout!`, and `/hyperopen/src/hyperopen/startup/runtime.cljs` previously used that deferred phase solely for `fetch-asset-selector-markets!`.

## Decision Log

- Decision: Treat the March 16 release report as the performance baseline, but require extension-free reruns before implementation milestones are judged complete.
  Rationale: the report contains enough app-owned signal to prioritize work, but extension scripts make the exact baseline noisy.
  Date/Author: 2026-03-16 / Codex

- Decision: Prioritize font payload removal before deeper rendering work.
  Rationale: almost 42 percent of the transferred bytes are fonts, and the current usage pattern suggests much of that cost is reclaimable with low risk.
  Date/Author: 2026-03-16 / Codex

- Decision: Prioritize bundle splitting before CSS or cache-header polish.
  Rationale: the main first-party script is still the single largest executable asset and Lighthouse estimates more than half of it is unused on the landing route.
  Date/Author: 2026-03-16 / Codex

- Decision: Keep startup-fetch reduction as a separate milestone from bundle splitting.
  Rationale: the app has both shipped-code cost and runtime-startup cost; solving only one will not close the `TTI` gap.
  Date/Author: 2026-03-16 / Codex

- Decision: Treat repeat-visit caching and back/forward cache as final-wave work, not the first intervention.
  Rationale: those changes matter, but the current cold-load trace already shows larger first-order wins in fonts, bundle size, startup behavior, and render churn.
  Date/Author: 2026-03-16 / Codex

- Decision: Implement the first font-reduction wave by retiring the Splash font from the header and routing all canvas text measurement through a shared UI-font resolver.
  Rationale: this removes the largest avoidable cold-load asset immediately and ensures Inter only loads when the active font mode explicitly asks for it.
  Date/Author: 2026-03-16 / Codex

- Decision: Accept headless Lighthouse as Milestone 1 network verification, but defer overall before-and-after score comparison to a manual extension-free DevTools capture.
  Rationale: the headless run reliably proves the font requests are gone, but the repeated page-load timeout makes its score, TTI, and transfer totals too noisy for sign-off-quality comparisons.
  Date/Author: 2026-03-16 / Codex

- Decision: Treat route-level code splitting as the first completed wave of Milestone 2 and keep the milestone open for the trade-chart split.
  Rationale: moving non-trade routes out of `:main` removes obvious landing-route waste, but the chart stack remains the largest trade-owned code path still loaded on the default route.
  Date/Author: 2026-03-16 / Codex

- Decision: Finish Milestone 2 by putting the trade chart behind its own browser module and rendering a stable chart-panel shell until that module resolves.
  Rationale: this moves `lightweight-charts` and the trade-chart interop stack off the cold path without changing the existing trade grid, panel ordering, or retry behavior after async-load failures.
  Date/Author: 2026-03-16 / Codex

- Decision: Complete Milestone 3 by removing both the critical selector bootstrap fetch and the automatic deferred selector-market expansion from cold startup, relying on explicit UI demand instead.
  Rationale: the asset selector already refreshes to `:full` on first open and route-specific loaders request the catalog when needed, so keeping these fetches on startup only widens the Lighthouse cold path without improving initial trade correctness.
  Date/Author: 2026-03-16 / Codex

## Outcomes & Retrospective

Milestones 1 through 3 are now implemented. The default route no longer cold-loads Splash or Inter, the browser build loads portfolio, funding comparison, staking, API-wallets, and vault screens from dedicated async route chunks, the trade route now resolves its chart stack from a separate `trade_chart` module instead of baking that code into the initial browser entrypoint, and cold `/trade` startup no longer fetches or auto-schedules asset-selector market expansion before the user asks for it.

The verification result is strong for correctness and build topology, and still incomplete for final performance scoring. Repository gates all pass, the release build emits the expected async chunks including `trade_chart.js`, startup and navigation now know how to load them on demand, and the cold `/trade` startup path is narrower because selector-market bootstrap now waits for demand instead of running automatically. Milestone 0 remains open as measurement debt, and the next highest-leverage work is Milestone 4 render-churn reduction under `hyperopen-e0cr`.

## Context and Orientation

The release build currently starts from `/hyperopen/resources/public/index.html`, which loads `/css/main.css` and `/js/main.js`. The browser build is configured in `/hyperopen/shadow-cljs.edn`. Right now that build still emits one primary browser app module, `:main`, so route-level code is compiled into a single app payload.

The app shell lives in `/hyperopen/src/hyperopen/views/app_view.cljs`. That namespace requires the trade, portfolio, funding, staking, vault, and API-wallet surfaces directly, which means the landing route pays for those namespaces even when the user only needs the trade view. The trade surface itself lives in `/hyperopen/src/hyperopen/views/trade_view.cljs` and mounts the chart, orderbook, order entry, and account-info surfaces together on desktop.

Typography is configured in `/hyperopen/src/styles/main.css`, `/hyperopen/tailwind.config.js`, and `/hyperopen/src/hyperopen/ui/fonts.cljs`. The stylesheet now keeps the default route on the system stack without registering Splash on the cold path. `/hyperopen/src/hyperopen/views/header_view.cljs` now styles the brand with the existing system typography tokens, and the canvas measurement sites in `/hyperopen/src/hyperopen/views/account_info_view.cljs`, `/hyperopen/src/hyperopen/views/portfolio_view.cljs`, `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs`, and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_options.cljs` resolve font families through the shared helper so Inter only loads when the active UI font mode explicitly selects it.

Startup orchestration is split across `/hyperopen/src/hyperopen/app/startup.cljs`, `/hyperopen/src/hyperopen/startup/init.cljs`, and `/hyperopen/src/hyperopen/startup/runtime.cljs`. After the first render is queued, startup still initializes the websocket client, initializes multiple websocket modules, subscribes to the active asset, invokes route loaders, starts critical market bootstrap, and schedules deferred bootstrap. Market bootstrap fans out further through `/hyperopen/src/hyperopen/api/market_loader.cljs`, which fetches perp DEX metadata, spot metadata, public WebData2, and route-level market state.

Rendering is driven by `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`, which installs a render loop that watches the entire store and renders the whole app on the next animation frame whenever the store changes. Market writes are coalesced by `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs`, which helps, but the landing route still causes frequent whole-app renders while live orderbook, trade, and chart state change. One representative live surface is `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`, which renders up to 80 levels per side plus recent trades and performs display derivation each render when no precomputed snapshot is present.

Terms used in this plan:

Critical path means the bytes, code, and runtime work that must happen before the default trade view becomes visibly usable.

Code splitting means building the app so that route-specific or feature-specific code is loaded only when that route or feature is needed, instead of bundling everything into `/js/main.js`.

Render churn means repeated DOM diffing, layout calculation, and painting caused by live state changes even when the user is not interacting with most of the updated surfaces.

Websocket bootstrap means the startup work that connects to Hyperliquid live streams and initializes the app’s market and account realtime subsystems.

## Plan of Work

### Milestone 0: Capture a Clean, Repeatable Release Baseline

Before code changes begin, create an extension-free release measurement loop so the team can trust before-and-after numbers. This milestone does not improve runtime performance by itself, but it prevents false wins and false regressions. Build the release app, serve `/hyperopen/resources/public` with SPA fallback, and capture at least three Lighthouse runs in a clean browser profile with extensions disabled. Record the median values for Performance score, FCP, LCP, TTI, TBT, total transferred bytes, font bytes, script bytes, and request count. Keep one desktop baseline for `http://localhost:8081/` and one mobile-emulated baseline if the team wants parity with existing browser-QA work.

This clean baseline must stay attached to the plan or linked issue notes before any implementation milestone is declared successful. The extension-contaminated March 16 run remains useful for prioritization, but milestone sign-off should rely on clean traces only.

### Milestone 1: Remove Non-Essential Cold-Load Font Bytes

The first implementation milestone should target `/hyperopen/src/styles/main.css`, `/hyperopen/tailwind.config.js`, `/hyperopen/src/hyperopen/views/header_view.cljs`, and `/hyperopen/src/hyperopen/views/account_info_view.cljs`.

The purpose is simple: the landing route should not download nearly 0.9 MB of font data just to render a brand wordmark and measure compact tab labels. Replace the `Splash` font usage in the header with either inline SVG branding or a tiny subsetted `woff2` asset if the visual identity genuinely depends on that typeface. The current TTF is oversized for the amount of visible text. Separately, stop hard-coding `"Inter Variable"` into the account-info tab measurement path when the app is still in system-font mode. The measurement logic should follow the active UI font token or fall back to the same system stack the page already uses.

This milestone is intentionally first because it is low-risk and directly removes a large fraction of first-load bytes. It also simplifies later performance traces by shrinking network noise and reducing follow-up cache concerns.

### Milestone 2: Split the Monolithic Browser Bundle and Defer Non-Critical Route Code

The second milestone should restructure `/hyperopen/shadow-cljs.edn`, `/hyperopen/resources/public/index.html`, and the view entrypoints rooted at `/hyperopen/src/hyperopen/views/app_view.cljs` and `/hyperopen/src/hyperopen/views/trade_view.cljs`.

The release report shows that `/js/main.js` remains the single largest executable payload and still contains substantial unused code for the landing route. The current topology eagerly requires all major route views from the app shell, so the trade landing page pays for portfolio, vaults, staking, funding-comparison, API-wallet, and modal code even when the user never visits those surfaces. The trade route also eagerly owns expensive subfeatures such as the chart stack. This milestone should introduce route-aware module boundaries so the default trade shell can load independently from secondary routes, and then split the most expensive trade-only subfeatures behind explicit lazy boundaries where that does not violate startup determinism.

The safest first split is route-level: trade shell in the initial bundle, other top-level routes loaded on navigation. The next split inside the trade route should target the chart stack rooted at `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` and `lightweight-charts`, because the chart interop is feature-rich and not every first interaction requires the fully initialized chart immediately. Preserve user-visible ordering guarantees from `/hyperopen/docs/RELIABILITY.md` while deferring code that does not block first paint or first useful interaction.

### Milestone 3: Narrow Startup Bootstrap to What the Landing Trade Route Actually Needs

The third milestone should revisit `/hyperopen/src/hyperopen/startup/init.cljs`, `/hyperopen/src/hyperopen/app/startup.cljs`, `/hyperopen/src/hyperopen/startup/runtime.cljs`, and `/hyperopen/src/hyperopen/api/market_loader.cljs`.

Today the app correctly yields one macrotask before post-render startup, but it still begins a broad set of websocket initializers and market bootstrap calls immediately after that yield. The plan here is not to make the trade route “static”; it still needs active-asset market data. The plan is to stop doing startup work that is not necessary for initial trade use. Concretely, the landing route likely needs the active asset contexts, the active asset subscriptions, and the minimal data needed to render the current market. It does not necessarily need the full asset-selector bootstrap, full market catalog expansion, dormant route loaders, or account-oriented websocket subsystems before the user connects a wallet or opens the related UI.

This milestone should make the startup phases explicit: required-for-initial-trade, required-after-first-interaction, and required-only-on-navigation or wallet connection. `start-critical-bootstrap!` and `run-deferred-bootstrap!` already provide a seam for this split. Use that seam to delay or remove work rather than layering more work into the existing phases. The desired result is fewer `/info` requests, less post-render JavaScript, and a shorter path to quiet main-thread time.

### Milestone 4: Reduce Whole-App Render Churn and Expensive Live-Surface Layout Work

The fourth milestone should target `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`, `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs`, `/hyperopen/src/hyperopen/views/trade_view.cljs`, and high-churn views such as `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`.

The report shows that even after the page paints, the browser spends several additional seconds in script evaluation, rendering, and style/layout work. The existing render loop watches the whole store and schedules a full app render on the next animation frame whenever any store change occurs. Market-projection coalescing reduces write frequency, but it does not prevent unrelated UI subtrees from participating in every render. On the trade route, live orderbook, trade, and chart updates are frequent enough that whole-app rendering is a likely driver of the observed tail.

This milestone should first add targeted instrumentation so the team can see which store paths and view subtrees are generating the heaviest render and layout cost during a release trace. Then reduce churn in the highest-cost surfaces. Likely changes include keeping more high-frequency chart and orderbook updates in imperative interop layers instead of whole-app state, memoizing or precomputing display data at the projection boundary instead of during render, and splitting the app shell into smaller independently renderable roots where that aligns with Replicant’s model. The goal is not “no rerenders”; the goal is “only the surfaces that changed should pay, and only at the rate the user can perceive.”

### Milestone 5: Repeat-Visit Caching and Back/Forward Cache Polish

The final milestone should address serving concerns that improve repeat visits after the cold-load bottlenecks are reduced. This includes cache lifetimes for the release assets served from `/hyperopen/resources/public`, immutable fingerprinting if asset naming needs to change, and the `bf-cache` failure reason reported by Lighthouse. These changes matter most after the app’s first-load script, startup, and render churn have been reduced. They should not pre-empt the earlier milestones because they will not fix the current `TTI` tail on their own.

## Concrete Steps

From `/hyperopen`:

1. Capture the clean baseline before implementation.

   Run:

     npm run build
     npx serve -s resources/public -l 8081

   Then run Lighthouse against `http://localhost:8081/` in a browser profile with extensions disabled. Capture at least three traces and write the median values into the linked `bd` issue or the promoted active plan.

2. Implement Milestone 1 and validate that the landing route no longer downloads large fonts unnecessarily.

   Edit:

   - `/hyperopen/src/styles/main.css`
   - `/hyperopen/tailwind.config.js`
   - `/hyperopen/src/hyperopen/views/header_view.cljs`
   - `/hyperopen/src/hyperopen/views/account_info_view.cljs`

   Rebuild and re-run Lighthouse. Confirm that the default route no longer requests the large `Splash` TTF and does not request `InterVariable.woff2` unless the active UI font mode explicitly requires it.

3. Implement Milestone 2 and validate reduced shipped JavaScript.

   Edit:

   - `/hyperopen/shadow-cljs.edn`
   - `/hyperopen/resources/public/index.html`
   - `/hyperopen/src/hyperopen/views/app_view.cljs`
   - `/hyperopen/src/hyperopen/views/trade_view.cljs`
   - Any new route-loader or lazy-boundary namespaces created during the split

   Rebuild and re-run Lighthouse. Confirm a smaller initial script transfer and a lower unused-JS estimate on the landing route.

4. Implement Milestone 3 and validate a smaller startup request burst.

   Edit:

   - `/hyperopen/src/hyperopen/startup/init.cljs`
   - `/hyperopen/src/hyperopen/app/startup.cljs`
   - `/hyperopen/src/hyperopen/startup/runtime.cljs`
   - `/hyperopen/src/hyperopen/api/market_loader.cljs`

   Rebuild and re-run Lighthouse plus any manual network inspection. Confirm that the initial trade load sends fewer `/info` requests and that non-essential market bootstrap waits until user demand or idle time.

5. Implement Milestone 4 and validate lower main-thread rendering and layout cost.

   Edit:

   - `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`
   - `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs`
   - `/hyperopen/src/hyperopen/views/trade_view.cljs`
   - `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`
   - Any new instrumentation or projection helper namespaces added to support churn reduction

   Rebuild and re-run Lighthouse plus a focused performance trace. Confirm that style/layout and rendering shrink materially relative to the clean baseline and that the landing route reaches quiet main-thread time sooner.

6. Implement Milestone 5 and validate repeat-visit improvements.

   Adjust serving or deployment-layer configuration as needed for cache headers and back/forward cache compatibility, then confirm with repeat-visit traces and Lighthouse’s cache and `bf-cache` audits.

## Validation and Acceptance

The performance work is complete when a clean release run, captured without browser extensions, shows that the landing trade route has materially improved both cold-load and post-paint behavior.

Acceptance for the full plan should include all of the following:

The default route no longer eagerly downloads large branding or optional UI fonts. On the system-font path, the initial document should render with the system stack and avoid loading `InterVariable.woff2` unless the user has opted into that font mode.

The initial app bundle is smaller and more route-specific. The landing route should not ship portfolio, vaults, staking, funding-comparison, or API-wallet implementation code in the initial critical bundle when those routes are not active.

The startup sequence performs only the work required for the initial trade route. Non-essential market catalog bootstrap, wallet-dependent data work, and dormant-route loaders should not run before the user needs them.

The landing route reaches useful interactivity substantially sooner than the March 16 baseline. As a concrete release goal for the clean desktop trace, target Performance score `>= 90`, FCP `<= 1.5s`, LCP `<= 1.8s`, and TTI `<= 3.5s`, with equal or better CLS and TBT than the current release baseline.

The performance trace shows reduced main-thread rendering and style/layout time during the first several seconds after load, demonstrating that live market updates are no longer driving expensive whole-app rendering by default.

Required repository gates still pass after each milestone that changes code: `npm run check`, `npm test`, and `npm run test:websocket`.

## Idempotence and Recovery

Each milestone is intentionally separable. The team should land and validate one milestone at a time, rebuilding and re-running Lighthouse after each wave. If a milestone regresses correctness or determinism, revert only that wave and keep the last accepted baseline. Font changes are easy to retry because they are asset and style scoped. Bundle-splitting and startup changes are riskier; keep those additive at first, with compatibility seams that can be disabled without removing the earlier wins.

The clean measurement loop is also idempotent. Rebuilding the release app and serving `resources/public` can be repeated any number of times. When comparing results, always use the same route, browser profile policy, and network-throttling assumptions so traces stay comparable.

## Artifacts and Notes

Key values from the March 16 release report used to prioritize this plan:

  Performance score: 0.77
  Final URL: http://localhost:8081/
  FCP: 1.7 s
  LCP: 1.9 s
  Speed Index: 2.8 s
  TTI: 6.7 s
  TBT: 120 ms
  CLS: 0.001
  main.js transfer: 682,739 bytes
  main.js unused-JS estimate: 370,345 bytes
  Splash-Regular.ttf transfer: 534,599 bytes
  InterVariable.woff2 transfer: 352,526 bytes
  third-party transfer: 519,378 bytes
  main-thread work: 24.6 s

Repository locations most directly implicated by that report:

  `/hyperopen/src/styles/main.css`
  `/hyperopen/tailwind.config.js`
  `/hyperopen/src/hyperopen/views/header_view.cljs`
  `/hyperopen/src/hyperopen/views/account_info_view.cljs`
  `/hyperopen/shadow-cljs.edn`
  `/hyperopen/src/hyperopen/views/app_view.cljs`
  `/hyperopen/src/hyperopen/views/trade_view.cljs`
  `/hyperopen/src/hyperopen/app/startup.cljs`
  `/hyperopen/src/hyperopen/startup/init.cljs`
  `/hyperopen/src/hyperopen/startup/runtime.cljs`
  `/hyperopen/src/hyperopen/api/market_loader.cljs`
  `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`
  `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs`
  `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`

Plan revision note: 2026-03-16 - Initial deferred performance plan authored from a release-build Lighthouse audit so the next implementation wave can start from a prioritized, evidence-backed roadmap rather than a fresh exploratory pass.
Plan revision note: 2026-03-16 - Promoted to active work for `hyperopen-finw` and updated with the Milestone 1 implementation approach.
