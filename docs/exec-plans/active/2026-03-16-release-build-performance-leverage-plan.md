# Release Build Performance Leverage Plan

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Linked live work: `hyperopen-s95z` ("Implement repeat-visit caching and bfcache polish").

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
- [x] (2026-03-16 19:08Z) Completed Milestone 3 by narrowing cold `/trade` startup to asset contexts plus bootstrap-phase selector metadata and eliminating the automatic deferred full selector-market fetch; full selector-market expansion now waits for explicit UI demand such as opening the asset selector or navigating to a route that needs it.
- [x] (2026-03-16 19:08Z) Validated the completed Milestone 3 cut with `npm run check`, `npm test`, `npm run test:websocket`, and `npm run build`; startup/runtime integration tests now lock in that initial `/trade` startup fetches asset contexts plus bootstrap selector metadata and does not auto-schedule full selector-market expansion.
- [x] (2026-03-16 19:08Z) Closed `hyperopen-p4dz` as completed and opened `hyperopen-e0cr` for Milestone 4 render-churn reduction work.
- [x] (2026-03-16 19:25Z) Implemented the first Milestone 4 cut by suppressing duplicate visual `l2Book` projections, skipping default store rewrites for net-noop queued market projections, and instrumenting the root render loop to emit `:ui/app-render-flush` telemetry with changed top-level store keys and render duration.
- [x] (2026-03-16 19:25Z) Validated the Milestone 4 first cut with `npm run check`, `npm test`, `npm run test:websocket`, and `npm run build`; new regression coverage now locks in timestamp-only orderbook dedupe, no-op market-projection suppression, and render-flush telemetry emission.
- [x] (2026-03-16 19:45Z) Implemented the second Milestone 4 cut in `/hyperopen/src/hyperopen/views/trade_view.cljs` by pruning hidden mobile/desktop subtree renders for chart, orderbook, order-entry, account-info, and account-equity surfaces; the trade route now keeps the panel shells and parity IDs but only invokes the heavy child views for the currently visible breakpoint/surface combination.
- [x] (2026-03-16 19:45Z) Validated the second Milestone 4 cut with `npm run check`, `npm test`, `npm run test:websocket`, and `npm run build`; the view suite now includes viewport-aware regression tests that prove desktop heavy surfaces render once each and hidden mobile chart-mode subtrees are skipped entirely.
- [x] (2026-03-16 20:13Z) Captured live browser render-flush telemetry on `http://localhost:8080/trade?market=HYPE&tab=positions`; before the next cut, pure `["orderbooks"]` flushes were the dominant remaining trade-route render root, repeatedly landing in the high-teens-to-high-30ms range under live market traffic.
- [x] (2026-03-16 20:13Z) Implemented two additional Milestone 4 cuts in `/hyperopen/src/hyperopen/views/trade_view.cljs`: visible trade subtrees now render through reduced top-level state slices with single-entry memoization, and default trade renders no longer propagate websocket-health freshness inputs into chart/account/orderbook surfaces when freshness cues are disabled.
- [x] (2026-03-16 20:13Z) Validated the additional Milestone 4 cuts with `npm run check`, `npm test`, `npm run test:websocket`, and `npm run build`; new trade-view regressions now prove non-orderbook heavy subtrees stay cached across orderbook-only updates and default trade subtrees skip websocket-only rerenders when surface freshness cues are disabled.
- [x] (2026-03-16 22:06Z) Implemented the next Milestone 4 chart-path cut across `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`, `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/legend.cljs`, and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/volume_indicator_overlay.cljs`: chart open-order inputs, overlay callbacks, runtime-option maps, and legend metadata now keep stable identities across unchanged renders, and the legend plus volume-indicator overlays now reconcile candle lookups incrementally for noop, tail-rewrite, and single-append candle updates instead of rescanning the full candle vector on every chart refresh.
- [x] (2026-03-16 22:06Z) Validated the latest Milestone 4 chart-path cut with `npm run check`, `npm test`, `npm run test:websocket`, and `npm run build`; new regressions now lock in stable overlay/chart-runtime identities across unchanged chart renders and incremental candle-index reconciliation for both legend and volume-indicator overlays.
- [x] (2026-03-16 22:36Z) Corrected the balances-tab regression discovered after Milestone 4 by restoring the bootstrap-phase selector metadata fetch on critical startup while keeping the deferred full selector-market expansion removed from cold `/trade` startup.
- [x] (2026-03-16 22:36Z) Validated the startup correction with `npm run check`, `npm test`, `npm run test:websocket`, and `npm run build`; startup/runtime tests now prove critical startup performs both the asset-context fetch and the selector `:bootstrap` fetch without auto-scheduling the deferred `:full` fetch.
- [x] (2026-03-16 23:12Z) Captured a clean extension-free desktop release Lighthouse baseline on `http://localhost:8082/trade?market=HYPE&tab=positions` across three stable runs with median Perf `48`, FCP `2.707s`, LCP `2.911s`, TBT `430ms`, TTI `3.853s`, total bytes `2.42 MB`, script bytes `1.62 MB`, font bytes `0`, and request count `18`; Milestone 0 baseline debt is now closed.
- [x] (2026-03-16 23:12Z) Captured fresh live render-flush telemetry on `/trade?market=HYPE&tab=positions`; `candles` remains the hottest remaining render root at `67` flushes, `8.03ms` average, `17ms` max, and `538ms` total sampled cost, so Milestone 4 stays open on the chart/candle path.
- [x] (2026-03-16 23:28Z) Implemented the next Milestone 4 chart/candle cut in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/transforms.cljs` and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/series_sync.cljs`: main-series and volume-series sync now reuse cached transformed vectors and derive incremental `:update-last` / `:append-last` tail data instead of remapping the full candle vector on every live tail update, including Heikin Ashi support with safe nil fallback to the full-reset path.
- [x] (2026-03-16 23:28Z) Validated the incremental chart-series cut with `npm test`, `npm run test:websocket`, `npm run check`, and `npm run build`; new regressions now lock in pure incremental tail derivation in the transform layer and prove the public chart-interop API skips the full transform path on main-series and volume tail updates when the incremental helper is available.
- [x] (2026-03-17 01:30Z) Reconciled the later March 16 release Lighthouse run captured against `http://localhost:8082/trade?market=HYPE&tab=positions` with the active milestone structure: cold-load paint is now strong enough that the remaining leverage is a narrow Milestone 3 follow-up on the residual critical-path `/info` chain plus a Milestone 4 closeout focused on chart/overlay layout stability and CLS, while a static non-JavaScript app shell is deferred to post-plan scope unless future clean traces regress.
- [x] (2026-03-17 01:45Z) Created and claimed `hyperopen-xlqu` to execute the narrow Milestone 3 follow-up on the residual critical-path `/info` chain without reopening broader startup/bootstrap scope.
- [x] (2026-03-17 01:45Z) Implemented the first Milestone 3 follow-up cut in `/hyperopen/src/hyperopen/api/market_loader.cljs` by reusing the existing startup `metaAndAssetCtxs` single-flight for the bootstrap default-dex selector request instead of sending a second identical `/info` request during critical startup.
- [x] (2026-03-17 01:45Z) Validated the Milestone 3 follow-up cut with `npm run check`, `npm test`, and `npm run test:websocket`; the bootstrap market-loader test now locks in the shared `:asset-contexts` dedupe key for the bootstrap default-dex request while full-phase behavior remains unchanged.
- [x] (2026-03-17 01:56Z) Extended the Milestone 3 follow-up cut by removing the bootstrap-only `perpDexs` fetch from `/hyperopen/src/hyperopen/api/market_loader.cljs`; bootstrap now skips named perp DEX metadata entirely because that phase only builds the default-dex path.
- [x] (2026-03-17 01:56Z) Added deterministic single-flight coverage in `/hyperopen/test/hyperopen/api/info_client_test.cljs` and updated the bootstrap loader tests so the request-level performance claim is pinned both at the market-loader boundary and at the shared info-client dedupe layer.
- [x] (2026-03-17 01:56Z) Revalidated the expanded Milestone 3 follow-up cut with `npm run check`, `npm test`, and `npm run test:websocket`; a post-change headless Lighthouse sample was captured to `/hyperopen/tmp/lighthouse-bootstrap-followup/localhost_8082-trade-bootstrap-followup.json`, but the overall trace was too noisy to use as sign-off-quality before/after evidence, so measurement debt remains open separately from the accepted code change.
- [x] (2026-03-17 01:56Z) Closed `hyperopen-xlqu` as completed; the startup code cut is accepted, while clean measurement/sign-off remains separate plan debt rather than an open code task.
- [x] (2026-03-17 15:10Z) Reviewed the clean extension-free release reruns captured to `/hyperopen/tmp/lighthouse-20260317-next-step/run3.json` and `/hyperopen/tmp/lighthouse-20260317-next-step/run4.json` as Milestone 3 follow-up sign-off; the landing route held at `15` requests with `4` `Fetch` `/info` calls, Perf `88 / 90`, and no reintroduced bootstrap chatter after the funding-predictability demand gate.
- [x] (2026-03-17 15:10Z) Implemented the Milestone 4 chart-shell closeout in `/hyperopen/src/hyperopen/views/trade_view.cljs` by reshaping the deferred trade-chart loading shell to match the resolved chart panel geometry and preserve the `trading-chart-host` min-height during async load and error states.
- [x] (2026-03-17 15:10Z) Validated the chart-shell closeout with `npm test`, `npm run test:websocket`, `npm run check`, and `npm run build`, then captured clean extension-free release Lighthouse reruns to `/hyperopen/tmp/lighthouse-20260317-chart-shell-followup/run1.json` and `/hyperopen/tmp/lighthouse-20260317-chart-shell-followup/run2.json`; those traces reported Perf `95 / 94`, FCP `842 / 831 ms`, LCP `1343 / 1506 ms`, TBT `32 / 29 ms`, and CLS `0.004183 / 0.005112`.
- [x] (2026-03-17 15:10Z) Closed `hyperopen-e0cr` as completed and opened `hyperopen-s95z` for Milestone 5 repeat-visit caching and back/forward cache work.
- [x] Implement Milestone 0 (clean measurement harness and extension-free baseline capture).
- [x] Implement Milestone 1 (remove non-essential cold-load font payloads).
- [x] Implement Milestone 2 (split the monolithic app bundle and defer non-critical route code).
- [x] Implement Milestone 3 (reduce startup fetch and subscription work on initial trade load).
- [x] Implement Milestone 3 follow-up (trim the remaining critical-path `/info` market-loader chain without regressing the selector metadata that balances and account surfaces require).
- [x] Implement Milestone 4 (close out remaining trade-route render churn and chart/overlay layout instability, with CLS as the primary user-visible finish-line metric).
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

- Observation: a clean extension-free Lighthouse baseline is now available for the trade route, and it is materially slower than the earlier noisy provisional run suggested.
  Evidence: the stable three-run desktop baseline at `http://localhost:8082/trade?market=HYPE&tab=positions` produced scores `47 / 48 / 51` with median Perf `48`, FCP `2.707s`, LCP `2.911s`, TBT `430ms`, TTI `3.853s`, and no Lighthouse warnings or extension noise.

- Observation: a later release Lighthouse run now shows that cold-load paint and interactivity have improved substantially, but the remaining visible issues have shifted to the startup dependency chain and residual CLS rather than raw FCP/LCP/TTI.
  Evidence: the later March 16 release trace captured against `http://localhost:8082/trade?market=HYPE&tab=positions` reported Perf `95`, FCP `0.8s`, LCP `1.0s`, TTI `1.1s`, TBT `32ms`, and CLS `0.092`, while Lighthouse still highlighted `/js/main.js` as a render-blocking request and showed a dependency chain from that script into several `/info` requests plus `trade_chart.js`.

- Observation: the deferred chart shell geometry, not deeper chart runtime churn, was the last dominant owner of release CLS on the trade route.
  Evidence: the clean pre-fix traces in `/hyperopen/tmp/lighthouse-20260317-next-step/run3.json` and `/hyperopen/tmp/lighthouse-20260317-next-step/run4.json` both attributed the dominant shift to the chart-shell root (`div.flex > div.overflow-hidden > div.h-full > div.flex`) at `0.072387`, while the post-fix traces in `/hyperopen/tmp/lighthouse-20260317-chart-shell-followup/run1.json` and `/hyperopen/tmp/lighthouse-20260317-chart-shell-followup/run2.json` dropped total CLS to `0.004183` and `0.005112` and replaced that culprit with small chart-toolbar and canvas-local shifts.

- Observation: the route-level Milestone 2 split is real at build time; release output now contains dedicated chunks for every non-trade top-level screen.
  Evidence: `npm run build` now emits `resources/public/js/portfolio_route.js` (`413,024` bytes), `resources/public/js/funding_comparison_route.js` (`98,351` bytes), `resources/public/js/staking_route.js` (`211,052` bytes), `resources/public/js/api_wallets_route.js` (`90,718` bytes), and `resources/public/js/vaults_route.js` (`851,863` bytes), alongside `module-loader.edn` and `module-loader.json`.

- Observation: route-level splitting alone does not finish Milestone 2 because the initial trade bundle still contains the entire chart stack.
  Evidence: after the route split, `resources/public/js/main.js` remains `15,007,924` bytes on disk in the release output, which confirms that the next meaningful JavaScript cut is still the trade-chart/libraries path.

- Observation: the dedicated trade-chart module materially shrank the initial release bundle while preserving the existing trade layout shell.
  Evidence: after completing the trade-chart split, `npm run build` emits `resources/public/js/trade_chart.js` at `516,614` bytes and reduces `resources/public/js/main.js` to `2,398,298` bytes on disk, down from the earlier post-route-split `15,007,924` byte main bundle recorded in this plan.

- Observation: the startup “deferred bootstrap” still ran only `1200 ms` after initialization, so leaving it enabled would continue to pull the full selector-market catalog into the same cold-load performance window.
  Evidence: `/hyperopen/src/hyperopen/config.cljs` sets `:startup {:deferred-bootstrap-delay-ms 1200}`, `/hyperopen/src/hyperopen/app/startup.cljs` wires that delay into `schedule-idle-or-timeout!`, and `/hyperopen/src/hyperopen/startup/runtime.cljs` previously used that deferred phase solely for `fetch-asset-selector-markets!`.

- Observation: `l2Book` traffic still produced app-store writes when only the transport timestamp changed, so visually identical depth snapshots could keep waking the whole-store render loop on the trade route.
  Evidence: `/hyperopen/src/hyperopen/websocket/orderbook.cljs` previously queued every parsed book into `[:orderbooks coin]`, and the book payload always included `:time` even when the sorted levels were unchanged.

- Observation: the default market-projection store writer still touched the root atom for queued net-noop updates, which means coalescing alone did not eliminate useless render-loop wakeups.
  Evidence: `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs` previously implemented `default-apply-store!` as an unconditional `swap!`, so equal pre/post states still triggered store watches.

- Observation: Milestone 4 needed cheap render-loop telemetry before deeper root splitting so follow-on work can target the top-level state changes that actually flush frames under release traffic.
  Evidence: `/hyperopen/src/hyperopen/runtime/bootstrap.cljs` previously had no built-in visibility into which top-level store keys changed before each RAF render or how long each render flush took.

- Observation: the trade route was still invoking both mobile and desktop variants of several expensive subtrees even when one branch was hidden by responsive classes, so a single render could pay twice for orderbook, account-info, and account-equity work.
  Evidence: `/hyperopen/src/hyperopen/views/trade_view.cljs` previously called both `l2-orderbook-view/l2-orderbook-view` branches, both account-info branches, the hidden mobile active-asset strip, and the hidden desktop account-equity panel unconditionally, relying on CSS classes such as `lg:hidden` and `lg:block` to suppress only the DOM visibility.

- Observation: `trade-view` still derived account-equity metrics on mobile market surfaces where no equity surface was rendered.
  Evidence: `/hyperopen/src/hyperopen/views/trade_view.cljs` previously called `account-equity-view/account-equity-metrics` before selecting the visible mobile surface, even though chart/orderbook/trades mobile layouts do not render the account-equity view.

- Observation: render-flush telemetry made the remaining Milestone 4 target concrete; the orderbook root was still the largest live trade-route flush before subtree memoization, and then dropped sharply after the trade-view cache cut.
  Evidence: the browser-inspection sample on `http://localhost:8080/trade?market=HYPE&tab=positions` initially showed repeated `["orderbooks"]` render-flush durations in the high-teens to high-30ms range; after the reduced-slice memoization pass, the same sample reported `orderbooks` flushes with `count 40`, `avg 3.75ms`, and `max 6ms`, leaving `candles` as the highest remaining sampled root.

- Observation: websocket health was still waking the default trade subtree even when surface freshness cues were disabled, so the orderbook panel itself needed the same reduced-slice cache as the other trade surfaces.
  Evidence: the new regression in `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs` initially failed because a websocket-only state change still invoked the orderbook view twice until the orderbook panel was memoized on the reduced trade-view state.

- Observation: the chart overlay interop already had identity-based fast paths, but `trading-chart-view` was recreating order-cancel and liquidation-drag callbacks plus overlay input maps on every render, which prevented those fast paths from firing even when the underlying chart data had not changed.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs`, `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs`, and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/markers.cljs` all guard on `identical?` input references, while `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` previously rebuilt the relevant callbacks, legend meta, and runtime-option maps every render.

- Observation: the remaining chart/candle hot path was not just the chart series update; the legend and volume-indicator overlays were rebuilding full candle lookup tables on every chart refresh.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/legend.cljs` and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/volume_indicator_overlay.cljs` previously looped the entire candle vector inside every `.update` / `sync-*` call, even though the existing candle sync policy already classified most live updates as `:noop`, `:update-last`, or `:append-last`.

- Observation: after the latest chart-path cuts, `candles` is still the dominant remaining render-flush root on live `/trade`, while `orderbooks` has fallen to a lower-cost frequent root.
  Evidence: the fresh telemetry sample written under `/hyperopen/tmp/render-flush-telemetry-20260316T230536Z/` reports `candles` at `67` flushes with `8.03ms` average and `17ms` max, versus `orderbooks` at `160` flushes but only `1.90ms` average and `8ms` max.

- Observation: the main-series and volume-series sync layer was still remapping the entire candle vector for every non-`:noop` tail update even though the sync policy already classified those cases as `:update-last` or `:append-last`.
  Evidence: before this cut, `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/series_sync.cljs` always rebuilt `transformed-data` with `series/transform-main-series-data` or `transforms/transform-data-for-volume` before applying `update`, which meant live tail updates still paid a full transform cost on the hottest remaining `candles` root.

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

- Decision: Complete Milestone 3 by keeping the critical selector `:bootstrap` fetch but removing the automatic deferred selector-market `:full` expansion from cold startup.
  Rationale: balances and account surfaces need bootstrap-phase selector metadata for correct spot pricing and contract IDs, but the full catalog expansion can still wait for explicit UI demand without widening the initial cold-load window as much.
  Date/Author: 2026-03-16 / Codex

- Decision: Start Milestone 4 at the projection boundary and render-loop seam before attempting larger app-root refactors.
  Rationale: duplicate market snapshots and net-noop store writes are low-risk sources of render churn that can be removed immediately, and render-flush telemetry provides the evidence needed to choose the next, more invasive cut.
  Date/Author: 2026-03-16 / Codex

- Decision: Keep the existing trade panel shells and parity IDs, but stop invoking hidden breakpoint-specific child views.
  Rationale: this removes duplicated render work on the trade route without changing the current layout grid, CSS breakpoint behavior, or QA selectors that depend on the panel structure.
  Date/Author: 2026-03-16 / Codex

- Decision: Continue Milestone 4 at the `trade_view` boundary with reduced state-slice memoization before deeper chart-specific rewrites.
  Rationale: render-flush telemetry showed that whole-store root changes were still waking multiple always-visible trade subtrees, and caching those panel entrypoints removes large amounts of unnecessary render participation with much lower risk than immediately rewriting chart internals.
  Date/Author: 2026-03-16 / Codex

- Decision: Continue Milestone 4 inside the chart-local candle path by stabilizing overlay input identities in `trading-chart-view` and reconciling legend/volume overlay candle indexes incrementally.
  Rationale: the chart series sync already handles noop, tail-rewrite, and append updates efficiently, so the next highest-leverage cut is to let the surrounding overlay sidecars use their own identity fast paths and stop rebuilding full candle lookups on every chart refresh.
  Date/Author: 2026-03-16 / Codex

- Decision: Continue Milestone 4 by making main-series and volume-series transformed candle data incremental for `:update-last` and `:append-last`, with safe fallback to the existing full transform path.
  Rationale: fresh render-flush telemetry proved `candles` was still the hottest remaining root, and the chart sync path was still paying a full-vector transform cost even on tail-only updates that already had a narrower sync decision.
  Date/Author: 2026-03-16 / Codex

- Decision: Treat the remaining critical-path `/info` chain as a narrow Milestone 3 follow-up instead of reopening bundle splitting or jumping ahead to caching work.
  Rationale: the later release trace shows that cold-load paint and first useful interactivity are already healthy, but the startup dependency tree still includes unnecessary market-loader work after `main.js`; that is still a startup-bootstrap concern, not a route-bundle or repeat-visit concern.
  Date/Author: 2026-03-17 / Codex

- Decision: Narrow the remaining Milestone 4 scope around chart-shell and overlay layout stability, with CLS as the primary closeout metric for the trade route.
  Rationale: the later release trace shows FCP, LCP, TTI, and TBT already in a good range, while CLS remains the weakest user-visible metric and the chart/overlay stack is the most plausible owner of the remaining movement.
  Date/Author: 2026-03-17 / Codex

- Decision: Finish Milestone 4 by matching the deferred chart shell to the resolved chart panel geometry before attempting deeper chart-runtime or DOM-sync rewrites.
  Rationale: the clean CLS culprit traces showed the dominant remaining movement came from the shell-to-chart replacement boundary itself, so preserving chart-host structure and min-height during async load was lower-risk and higher-leverage than more invasive chart runtime work.
  Date/Author: 2026-03-17 / Codex

- Decision: Defer a true static app shell and non-JavaScript first-frame work to post-plan follow-up scope unless future clean release traces regress on cold-load paint.
  Rationale: that architectural change is directionally valid, but the latest release metrics already meet the plan's cold-load goals, so the higher-leverage remaining work is to trim the startup `/info` chain and stabilize chart/overlay layout before broadening scope.
  Date/Author: 2026-03-17 / Codex

- Decision: Close Milestone 4 and advance the linked live work to Milestone 5.
  Rationale: the clean March 17 desktop release reruns now satisfy the plan's acceptance thresholds with Perf `>= 90`, FCP `<= 1.5s`, LCP `<= 1.8s`, TTI `<= 3.5s`, TBT at or below the later healthy release baseline, and CLS reduced from `0.092` to roughly `0.005`; the next remaining leverage is repeat-visit caching and `bf-cache` polish.
  Date/Author: 2026-03-17 / Codex

## Outcomes & Retrospective

Milestones 1 through 4 are now implemented. The default route no longer cold-loads Splash or Inter, the browser build loads portfolio, funding comparison, staking, API-wallets, vault screens, and the trade chart from dedicated async modules, cold `/trade` startup now performs only the active-asset contexts plus the minimal selector `:bootstrap` metadata that balances/account surfaces require, and the render loop plus trade/chart surfaces now avoid several classes of unnecessary participation under live market traffic. That includes duplicate timestamp-only orderbook suppression, net-noop market-projection suppression, render-flush telemetry, hidden responsive subtree pruning, reduced-slice trade-view memoization, stable chart overlay identities, incremental legend/volume overlay reconciliation, incremental transformed candle reuse for tail updates, and now a deferred chart shell that preserves the resolved chart host geometry during async load.

The verification result is now strong enough to move the plan forward. Repository gates all pass, the startup follow-up remains narrowed without reintroducing the balances metadata regression, and clean extension-free desktop Lighthouse reruns now meet the concrete release targets in this plan: Perf `94 / 95`, FCP `831 / 842 ms`, LCP `1.34 / 1.51 s`, TTI `1.34 / 1.51 s`, TBT `29 / 32 ms`, and CLS `0.004183 / 0.005112`. The dominant pre-fix chart-shell root shift is gone, so the active remaining work is Milestone 5 repeat-visit caching and `bf-cache` polish rather than further cold-load or chart-stability rewrites.

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

Today the app correctly yields one macrotask before post-render startup, but it still begins a broad set of websocket initializers and market bootstrap calls immediately after that yield. The plan here is not to make the trade route “static”; it still needs active-asset market data. The plan is to stop doing startup work that is not necessary for initial trade use. Concretely, the landing route likely needs the active asset contexts, the active asset subscriptions, and the minimal data needed to render the current market, including the selector `:bootstrap` metadata that balances/account surfaces depend on for spot pricing and contract IDs. It does not necessarily need full asset-selector market expansion, dormant route loaders, or account-oriented websocket subsystems before the user connects a wallet or opens the related UI.

This milestone should make the startup phases explicit: required-for-initial-trade, required-after-first-interaction, and required-only-on-navigation or wallet connection. `start-critical-bootstrap!` and `run-deferred-bootstrap!` already provide a seam for this split. Use that seam to delay or remove work rather than layering more work into the existing phases. The desired result is fewer `/info` requests, less post-render JavaScript, and a shorter path to quiet main-thread time.

The first Milestone 3 wave is now complete, but later release traces show a smaller follow-up still remains. The remaining Milestone 3 work is to keep the correctness-critical selector `:bootstrap` fetch while trimming any residual startup `/info` fan-out that is still landing on the Lighthouse dependency tree for the default trade route. That follow-up should stay narrow: do not reintroduce the balances metadata regression that occurred when selector metadata was removed too aggressively.

That follow-up is now complete. The clean March 17 release reruns in `/hyperopen/tmp/lighthouse-20260317-next-step/run3.json` and `/hyperopen/tmp/lighthouse-20260317-next-step/run4.json` confirmed the narrowed landing-route startup request shape at `15` total requests with `4` `Fetch` `/info` calls and no correctness regressions on balances metadata or active-market startup.

### Milestone 4: Reduce Whole-App Render Churn and Expensive Live-Surface Layout Work

The fourth milestone should target `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`, `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs`, `/hyperopen/src/hyperopen/views/trade_view.cljs`, and high-churn views such as `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`.

The report shows that even after the page paints, the browser spends several additional seconds in script evaluation, rendering, and style/layout work. The existing render loop watches the whole store and schedules a full app render on the next animation frame whenever any store change occurs. Market-projection coalescing reduces write frequency, but it does not prevent unrelated UI subtrees from participating in every render. On the trade route, live orderbook, trade, and chart updates are frequent enough that whole-app rendering is a likely driver of the observed tail.

This milestone should first add targeted instrumentation so the team can see which store paths and view subtrees are generating the heaviest render and layout cost during a release trace. Then reduce churn in the highest-cost surfaces. Likely changes include keeping more high-frequency chart and orderbook updates in imperative interop layers instead of whole-app state, memoizing or precomputing display data at the projection boundary instead of during render, and splitting the app shell into smaller independently renderable roots where that aligns with Replicant’s model. The goal is not “no rerenders”; the goal is “only the surfaces that changed should pay, and only at the rate the user can perceive.”

At this stage, the remaining Milestone 4 closeout work is narrower than the original milestone text. Recent release traces suggest the final leverage is less about raw cold-load execution and more about the trade chart shell, overlays, and any layout movement they still induce. Treat CLS and chart/overlay stability as the primary exit criteria for this milestone, with render-flush telemetry used to confirm whether the candle path or another chart-local subtree still dominates.

That closeout is now complete. Reshaping the deferred chart shell to preserve the resolved chart host geometry removed the dominant remaining chart-shell CLS, and the clean March 17 release reruns now hold CLS near zero while keeping the earlier improved cold-load paint and interactivity metrics.

### Milestone 5: Repeat-Visit Caching and Back/Forward Cache Polish

The final milestone should address serving concerns that improve repeat visits after the cold-load bottlenecks are reduced. This includes cache lifetimes for the release assets served from `/hyperopen/resources/public`, immutable fingerprinting if asset naming needs to change, and the `bf-cache` failure reason reported by Lighthouse. These changes matter most after the app’s first-load script, startup, and render churn have been reduced. They should not pre-empt the earlier milestones because they will not fix the current `TTI` tail on their own.

This milestone is now the active linked work via `hyperopen-s95z`.

### Deferred Follow-Up: Static App Shell and Non-JavaScript First Frame

A real static app shell remains a valid follow-up idea for the release build entrypoint in `/hyperopen/resources/public/index.html`, especially if the team wants to decouple the first visible frame from `main.js` entirely. It is intentionally deferred from the active milestone sequence because the later release trace already shows strong cold-load paint and interactivity. Unless future clean release traces regress on FCP/LCP or product goals explicitly expand to require a non-JavaScript first frame, the higher-leverage remaining work is still the startup `/info` chain and chart/overlay stability.

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

   Rebuild and re-run Lighthouse plus manual network inspection. Confirm that the initial trade load sends fewer `/info` requests, that the remaining startup dependency tree no longer contains avoidable `/info` work on the critical chain, and that non-essential market bootstrap still waits until user demand or idle time.

5. Implement Milestone 4 and validate lower main-thread rendering and layout cost plus lower CLS.

   Edit:

   - `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`
   - `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs`
   - `/hyperopen/src/hyperopen/views/trade_view.cljs`
   - `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`
   - Any new instrumentation or projection helper namespaces added to support churn reduction

   Rebuild and re-run Lighthouse plus a focused performance trace. Confirm that style/layout and rendering shrink materially relative to the clean baseline, that the landing route reaches quiet main-thread time sooner, and that chart/overlay work no longer produces the remaining meaningful CLS on the trade route.

6. Implement Milestone 5 and validate repeat-visit improvements.

   Adjust serving or deployment-layer configuration as needed for cache headers and back/forward cache compatibility, then confirm with repeat-visit traces and Lighthouse’s cache and `bf-cache` audits.

## Validation and Acceptance

The performance work is complete when a clean release run, captured without browser extensions, shows that the landing trade route has materially improved both cold-load and post-paint behavior.

Acceptance for the full plan should include all of the following:

The default route no longer eagerly downloads large branding or optional UI fonts. On the system-font path, the initial document should render with the system stack and avoid loading `InterVariable.woff2` unless the user has opted into that font mode.

The initial app bundle is smaller and more route-specific. The landing route should not ship portfolio, vaults, staking, funding-comparison, or API-wallet implementation code in the initial critical bundle when those routes are not active.

The startup sequence performs only the work required for the initial trade route. Non-essential market catalog bootstrap, wallet-dependent data work, and dormant-route loaders should not run before the user needs them.

The landing route reaches useful interactivity substantially sooner than the March 16 baseline and preserves the later improved release result. As a concrete release goal for the clean desktop trace, keep Performance score `>= 90`, FCP `<= 1.5s`, LCP `<= 1.8s`, and TTI `<= 3.5s`, while reducing CLS below the later `0.092` release trace and keeping TBT at or below the current release baseline.

The performance trace shows reduced main-thread rendering and style/layout time during the first several seconds after load, demonstrating that live market updates are no longer driving expensive whole-app rendering by default, and the startup dependency tree no longer carries avoidable `/info` work on the critical path.

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
Plan revision note: 2026-03-17 - Reconciled the later improved release Lighthouse run with the active milestone structure, reopening a narrow Milestone 3 follow-up for residual startup `/info` work, narrowing Milestone 4 around chart/overlay CLS stability, and deferring a static app shell to post-plan scope.
