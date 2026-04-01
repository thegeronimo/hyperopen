# Reduce Desktop Trade-Route Startup TBT

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The live `bd` issue for this work is `hyperopen-k47v` ("Reduce desktop trade-route startup TBT"), and `bd` remains the lifecycle source of truth until this plan is moved out of `active`.

This plan is a second-wave follow-up to `/hyperopen/docs/exec-plans/completed/2026-03-16-release-build-performance-leverage-plan.md`. That earlier work already removed large font waste, split non-trade routes, moved the trade chart into its own browser module, and trimmed several startup and render-churn paths. This plan starts from the current deployed baseline and targets the remaining desktop trade-route bottlenecks that still keep PageSpeed at `79`.

## Purpose / Big Picture

Opening Hyperopen on desktop currently paints very quickly but still spends too much time parsing, evaluating, and running JavaScript before the default trade workspace is fully calm. The current PageSpeed desktop report for `https://hyperopen.pages.dev/` shows `Performance 79`, `FCP 0.3s`, `LCP 0.3s`, `Speed Index 1.2s`, `CLS 0.019`, and `TBT 490ms`. That means the user-visible problem is not that the page appears slowly; the problem is that the page keeps the main browser thread busy after first paint.

After this work, the default desktop trade load should remain visually fast while doing materially less startup work. A contributor should be able to open the desktop trade route, see the workspace stabilize sooner, and verify the improvement with repeatable local release measurements, existing Playwright coverage, governed browser QA, and a fresh PageSpeed rerun against the deployed URL.

## Progress

- [x] (2026-04-01 15:42Z) Audited the shared PageSpeed desktop report and mapped its dominant regressors to the current trade-route code and build surfaces.
- [x] (2026-04-01 15:59Z) Created and claimed `bd` issue `hyperopen-k47v` for the desktop trade-route TBT initiative.
- [x] (2026-04-01 16:00Z) Authored this active ExecPlan with explicit milestone ordering, validation commands, and acceptance targets.
- [x] (2026-04-01 16:28Z) Added `browser:profile:trade-startup`, ran it locally, and captured a repeatable `/trade` startup artifact under `tmp/browser-inspection/trade-startup-profile-2026-04-01T16-27-37-777Z-3cb6df7b/`.
- [x] (2026-04-01 16:35Z) Verified the first-wave startup change surface with targeted release-style Playwright `/trade` smoke covering route render, cold startup, and accessibility hooks.
- [x] (2026-04-01 16:44Z) Deferred the initial `/trade` chart module load until post-render startup while preserving later route-driven chart loading behavior.
- [x] (2026-04-01 16:48Z) Extracted agent key generation and agent-address derivation into `/hyperopen/src/hyperopen/wallet/agent_session_crypto.cljs` so default startup no longer pulls `@noble/secp256k1` and keccak through the wallet-session namespace.
- [x] (2026-04-01 17:18Z) Updated deterministic tests for the crypto split and deferred startup contract, then re-ran `npm run check`, `npm test`, and `npm run test:websocket` successfully.
- [x] (2026-04-01 17:18Z) Deferred the desktop account and account-equity lower panels until the first post-render startup pass while preserving the existing trade-shell geometry with explicit placeholders.
- [x] (2026-04-01 17:24Z) Deferred hidden order-form feedback and scale-preview calculations so the cold desktop route no longer builds TP/SL, TWAP, or scale-preview work when those sections are not visible.
- [x] (2026-04-01 17:24Z) Re-ran targeted `/trade` Playwright smoke, `npm run check`, `npm test`, `npm run test:websocket`, and two follow-up local startup profiles after the second and third startup-reduction waves.
- [x] (2026-04-01 18:24Z) Moved agent keygen and exchange-signing helpers behind a dedicated `trading_crypto` browser module, rewired wallet and trading callers to lazy-load that module, and added cold-path tests for lazy load, rejection, and export coverage.
- [x] (2026-04-01 18:24Z) Re-ran targeted `/trade` Playwright smoke, `npm run check`, `npm test`, `npm run test:websocket`, and a fresh local startup profile showing the crypto chunk stays off the default cold route while the blocking proxy dropped to `189ms`.
- [ ] Finish Milestone 0 baseline capture by storing the built asset-size snapshot, governed browser QA evidence, and consolidated notes under one dedicated `tmp/` artifact root.
- [ ] Continue Milestone 1 by deferring or splitting additional noncritical desktop trade surfaces beyond the chart and wallet crypto path.
- [ ] Reduce chart startup and overlay main-thread work so the trade route stops producing the current long-task and forced-reflow pattern.
- [ ] Eliminate the remaining low-risk layout-shift and animation noise on the trade shell without regressing design-system behavior.
- [ ] Re-run deterministic repo gates, targeted Playwright, governed browser QA, and a fresh deployed PageSpeed desktop capture before closing `hyperopen-k47v`.

## Surprises & Discoveries

- Observation: the application already splits non-trade routes and the trade chart into separate browser modules, but the default landing path is still `/trade`, so desktop cold-load performance is graded against the heaviest workspace by design.
  Evidence: `/hyperopen/src/hyperopen/router.cljs` normalizes `/` to `/trade`, `/hyperopen/src/hyperopen/state/app_defaults.cljs` seeds `:router {:path "/trade"}`, and `/hyperopen/src/hyperopen/app/startup.cljs` plus `/hyperopen/src/hyperopen/runtime/action_adapters/navigation.cljs` auto-load the trade chart module on trade startup.

- Observation: the deployed first-party payload is still large enough to explain the PageSpeed unused-JavaScript finding even after the earlier route-splitting work.
  Evidence: on 2026-04-01 the deployed `main.js` downloaded at roughly `585,846` compressed bytes and `2,556,761` uncompressed bytes, while `main.css` downloaded at roughly `30,909` compressed bytes and `195,663` uncompressed bytes.

- Observation: the PageSpeed report is dominated by startup CPU, not by paint or layout quality.
  Evidence: the shared desktop report shows `TBT 490ms`, `Reduce JavaScript execution time 1.5s`, `Reduce unused JavaScript 301.6 KiB`, and `8` long tasks while `FCP`, `LCP`, and `CLS` remain green.

- Observation: the trade chart runtime still owns multiple layout-read and DOM-patching paths on mount and update, which is consistent with the report’s forced-reflow evidence.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` mounts and updates overlays, accessibility decoration, and visible-range persistence; `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs`, `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs`, and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay.cljs` all read geometry or pane dimensions during sync work.

- Observation: the icon cache-lifetime finding is real but small, and part of that surface is already intentionally handled by a service worker.
  Evidence: `/hyperopen/src/hyperopen/views/asset_icon.cljs` pulls coin icons from `https://app.hyperliquid.xyz/coins/`, and `/hyperopen/resources/public/sw.js` already caches those icons after first-load registration.

- Observation: deferring the initial trade-chart module load until the post-render startup hook did not regress the committed release-style `/trade` smoke surface.
  Evidence: on 2026-04-01 `npx playwright test tools/playwright/test/routes.smoke.spec.mjs -g "trade desktop root renders|trade cold startup does not render the static boot loading shell|trade route exposes score-bearing accessibility hooks"` passed after the startup change.

- Observation: the first local profile after the initial startup cuts still shows one meaningful long task, so the remaining bottleneck has shifted from pure bootstrapping toward the residual trade workspace runtime work.
  Evidence: `tmp/browser-inspection/trade-startup-profile-2026-04-01T16-27-37-777Z-3cb6df7b/profile.json` recorded `blockingTimeProxyMs 211`, `maxSingleBlockingTaskMs 261`, `tradeRootVisibleMs 428.5`, and `orderFormVisibleMs 461.9`.

- Observation: moving crypto helpers out of `agent_session.cljs` exposed several tests that were directly redefining the old vars, but the fallout stayed contained to test surfaces.
  Evidence: `shadow-cljs compile test` initially surfaced stale references in `test/hyperopen/api/trading/internal_seams_test.cljs`, `test/hyperopen/core_bootstrap/agent_trading_lifecycle_test.cljs`, `test/hyperopen/wallet/agent_session_test.cljs`, and `test/hyperopen/websocket/agent_session_coverage_test.cljs`, and those warnings cleared once the tests pointed at `hyperopen.wallet.agent-session-crypto`.

- Observation: the desktop trade route was still computing lower account-panel state before those panels were meaningfully needed on first paint.
  Evidence: `/hyperopen/src/hyperopen/views/trade_view.cljs` was eagerly building `account-info-view-state`, `account-equity-view-state`, equity metrics, and mobile orderbook derivatives during the shared panel-context pass even though the desktop report’s remaining cost is concentrated after first paint and the lower panels can be represented by stable placeholders initially.

- Observation: the order form was still paying for hidden feedback work even when TP/SL, TWAP, or scale-preview sections were not visible on the default cold route.
  Evidence: `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` eagerly built `tpsl-panel-model` and `twap-preview` before checking whether those sections would render, and `/hyperopen/src/hyperopen/trading/order_form_application.cljs` eagerly built scale-preview lines even for order types whose registry capabilities never show them.

- Observation: the cold desktop trade route does not need the signing chunk at all, and the local startup profile improved once that code stopped shipping in `main`.
  Evidence: after the new `trading_crypto` module landed, `resources/public/js/manifest.json` showed `@noble/secp256k1`, `hyperopen/vendor/keccak.js`, `hyperopen/wallet/agent_session_crypto.cljs`, and `hyperopen/utils/hl_signing.cljs` only in `trading_crypto`, while `tmp/browser-inspection/trade-startup-profile-2026-04-01T18-23-47-424Z-b0a775ea/profile.json` recorded only `main.js`, `trade_chart.js`, and `main.css` on the cold route plus `blockingTimeProxyMs 189`, `maxSingleBlockingTaskMs 239`, `tradeRootVisibleMs 375.1`, and `orderFormVisibleMs 441.6`.

- Observation: the namespace-size guard forced this wave to compact trivial public wrappers instead of letting `api/trading.cljs` quietly grow.
  Evidence: the first `npm run check` after the crypto split failed with `[size-exception-exceeded] src/hyperopen/api/trading.cljs - namespace has 665 lines; exception allows at most 648`, and the rerun passed only after collapsing thin submit wrappers back under the existing exception budget.

## Decision Log

- Decision: use `/trade` as the local benchmark route even though the public audit URL is `/`.
  Rationale: `/hyperopen/src/hyperopen/router.cljs` normalizes `/` to `/trade`, so `/trade` removes router ambiguity while representing the same cold-load surface.
  Date/Author: 2026-04-01 / Codex

- Decision: treat JavaScript transfer, parse/eval time, and chart-side main-thread work as the first-order scope, and keep CSS, animation, and icon polish in a later wave inside the same plan.
  Rationale: the current report is overwhelmingly a TBT problem, not a paint or large-CLS problem, so the plan should spend complexity where the report says the score is being lost.
  Date/Author: 2026-04-01 / Codex

- Decision: keep deployment-owned immutable cache-header work out of the critical path for this plan unless the repository gains a concrete deployment config during execution.
  Rationale: `/hyperopen/docs/WORK_TRACKING.md` already has a separate deployment cache-policy follow-up (`hyperopen-c2xn`), and the current PageSpeed cache-lifetime savings are too small to justify blocking the main TBT work on hosting decisions.
  Date/Author: 2026-04-01 / Codex

- Decision: require both deterministic local evidence and one fresh deployed PageSpeed rerun before closure.
  Rationale: local release measurements are repeatable and suitable for regression control, while the user asked about the deployed PageSpeed report specifically and sign-off should confirm improvement there as well.
  Date/Author: 2026-04-01 / Codex

- Decision: extract only the crypto-specific agent helpers into a new wallet namespace and leave storage-mode, session persistence, and approval-action helpers in `agent_session.cljs`.
  Rationale: this removes the secp256k1 and keccak startup dependency from the default boot path without forcing a wider public-API or state-shape rewrite across wallet flows.
  Date/Author: 2026-04-01 / Codex

- Decision: defer only the initial `/trade` chart module load from router init and continue loading the chart module on later navigation-driven trade entries.
  Rationale: the cold-load route was the audited bottleneck, so the least risky first cut is to move the first chart load behind the existing post-render startup hook while preserving existing runtime navigation behavior after boot.
  Date/Author: 2026-04-01 / Codex

- Decision: gate desktop lower trade surfaces behind an explicit startup-ready flag stored under `[:trade-ui :desktop-secondary-panels-ready?]` and flip that flag during the post-render startup phase.
  Rationale: this keeps the trade shell’s visual structure stable on desktop while preventing the initial shared render pass from paying for account-table and equity-panel state that can safely appear one startup tick later.
  Date/Author: 2026-04-01 / Codex

- Decision: gate TWAP preview work by the registry-driven `:twap` section contract instead of hard-coding the concrete `:twap` order type.
  Rationale: the order-form renderer is intentionally registry-driven, so the lazy path must follow the rendered section contract to avoid silently breaking future order types that reuse the TWAP extension surface.
  Date/Author: 2026-04-01 / Codex

- Decision: lazy-load signing and agent-key crypto on first use instead of preloading it after wallet connect.
  Rationale: the current desktop TBT problem is measured on the cold default `/trade` route, so the first version should maximize cold-route savings and only pay the extra module fetch on explicit signing flows.
  Date/Author: 2026-04-01 / Codex

## Outcomes & Retrospective

Four implementation waves have landed and the plan is now materially reducing the audited startup cost. The current result is a narrower startup path on the default desktop trade route: the initial router bootstrap no longer loads the trade chart module before first paint, wallet-session startup no longer imports agent keygen or address-derivation crypto by default, exchange-signing helpers now live in a separate `trading_crypto` browser module that is not fetched on the cold route, the lower desktop account surfaces stay behind stable placeholders until the post-render startup phase marks them ready, and the order form no longer computes hidden TP/SL, TWAP, or scale-preview work on the cold desktop path. The repository gates remain green after those changes, and the latest local profile at `tmp/browser-inspection/trade-startup-profile-2026-04-01T18-23-47-424Z-b0a775ea/` still shows one remaining long task but improved the blocking proxy to `189ms` and max single long task to `239ms`, with the trade root visible at `375.1ms` and the order form visible at `441.6ms`.

This plan remains active because the highest-leverage follow-up work is still outstanding. The baseline artifacts are not yet consolidated into one dedicated milestone folder, no governed browser QA artifact has been captured yet, the chart-runtime long-task work from Milestone 2 has not started, and the local startup profiler is still showing one residual long task even after the route-startup reductions. The execution order is holding up: the first three waves reduced cold-load startup cost without destabilizing the route, and the next likely leverage is now chart-runtime batching plus any remaining desktop trade-shell render work rather than more router bootstrap work.

## Context and Orientation

In this repository, the “default desktop trade route” means the browser entry surface a user gets when opening `https://hyperopen.pages.dev/` on desktop. `/hyperopen/src/hyperopen/router.cljs` converts `/` into `/trade`, so the default landing route is already the trading workspace. A “cold load” means opening the page without previously warmed JavaScript module state for the current tab. “TBT” means Total Blocking Time, which is the amount of time the main browser thread is blocked by long tasks after the page begins rendering. “CLS” means Cumulative Layout Shift, which is the amount of visible layout movement after the initial paint.

Several files define why the current load behaves the way it does. `/hyperopen/shadow-cljs.edn` defines the browser modules. `/hyperopen/src/hyperopen/system.cljs` creates the initial store and pulls in startup dependencies. `/hyperopen/src/hyperopen/app/startup.cljs` and `/hyperopen/src/hyperopen/runtime/action_adapters/navigation.cljs` decide which route modules or trade modules load during startup and navigation. `/hyperopen/src/hyperopen/views/app_view.cljs` and `/hyperopen/src/hyperopen/views/trade_view.cljs` compose the default desktop trade shell. `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` and the interop files under `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/` own the chart lifecycle, overlay DOM, and layout-sensitive chart work. `/hyperopen/src/hyperopen/views/trading_chart/derived_cache.cljs` and `/hyperopen/src/hyperopen/domain/trading/indicators/math_adapter.cljs` own indicator calculation inputs and the `indicatorts` dependency. `/hyperopen/src/hyperopen/wallet/agent_session.cljs` owns the agent-session and `@noble/secp256k1` startup surface.

The current desktop report implies that the remaining waste is concentrated in three places. First, the initial `main` trade route still includes more noncritical code than necessary. Second, the chart runtime still performs enough mount/update work to show up in long-task and forced-reflow diagnostics. Third, a smaller amount of residual layout-shift and animation polish remains in the trade shell and footer surfaces. This plan attacks those in that order.

The implementation must also respect the repo’s browser validation contract. `/hyperopen/docs/BROWSER_TESTING.md` says Playwright owns deterministic committed browser validation and Browser MCP or browser-inspection owns exploratory or governed design review. `/hyperopen/docs/agent-guides/browser-qa.md` says any UI-facing change under `/hyperopen/src/hyperopen/views/**` or `/hyperopen/src/styles/**` must account for visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes at widths `375`, `768`, `1280`, and `1440`.

## Plan of Work

### Milestone 0: Capture a stable baseline and comparison workflow

Before changing code, capture one repeatable local release baseline for the desktop trade route and store the evidence in a dedicated `tmp/` directory. The goal is not to prove that the current report exists; the goal is to lock in one workload, one route, and one validation recipe that later milestones can reuse without argument.

Use the local release-style Playwright server from `/hyperopen/playwright.config.mjs` so the benchmark reflects fingerprinted release assets instead of watch-mode development output. Capture at minimum the rendered route smoke, one governed trade-route browser QA pass, and a byte-size snapshot for the built `main` and CSS artifacts. The local benchmark route should be `/trade?market=HYPE&tab=positions` because it is stable, already exercised by prior performance work, and keeps the account panel visible. Also record the existing deployed PageSpeed desktop numbers in the same artifact folder so the plan has both local and deployed baselines.

At the end of this milestone, a future contributor should be able to point to one artifact folder and answer four questions without rerunning discovery work: what the starting TBT was, what the starting payload size was, which browser command proves the route still renders correctly, and which browser QA command proves the trade shell did not visually regress.

### Milestone 1: Reduce default-route JavaScript shipped and evaluated

The current main bundle is still too expensive for a route whose cold-load report already says about `301.6 KiB` is unused. This milestone reduces that cost without changing public behavior. The first wave should stay demand-driven and low-risk: move optional or secondary trade subtrees out of the always-loaded desktop path, defer code that only matters after user action, and make sure startup no longer imports optional libraries just to define dormant features.

The main entry point for this work is `/hyperopen/src/hyperopen/views/trade_view.cljs`. Today it eagerly requires and prepares the asset selector, order book, account info, account equity, and order form surfaces even though the PageSpeed report says startup CPU is the limiting factor and the desktop route does not need every secondary path immediately. The implementation should preserve the current visible desktop geometry and parity anchors while introducing clearer demand boundaries. That can mean creating one or more additional browser modules in `/hyperopen/shadow-cljs.edn`, introducing async panel shells similar to the existing trade-chart shell, or converting selected heavy surfaces to delayed imports after first paint. The plan must keep the desktop trade shell functionally identical once the surfaces resolve.

This same milestone also owns optional-library deferral. `/hyperopen/src/hyperopen/views/trading_chart/derived_cache.cljs` and `/hyperopen/src/hyperopen/domain/trading/indicators/math_adapter.cljs` should stop paying the `indicatorts` import cost on the cold path when indicators are not active. `/hyperopen/src/hyperopen/system.cljs` should stop forcing `@noble/secp256k1` into the startup path if agent-session code is only needed during wallet or agent flows. The exact boundary may differ between modules, but the end state must be that default desktop trade startup does not parse or evaluate indicator or agent-session logic until the user actually enables those features.

At the end of this milestone, the release build should either produce a materially smaller initial `main` artifact or move a measurable amount of startup code into async chunks that do not execute during the default desktop trade boot. The acceptance threshold for this milestone is that the local built `main` JavaScript size, measured both raw and with `gzip`, drops by at least `15%` from the captured baseline or that the equivalent amount of code is demonstrably moved into async route or panel chunks that do not load during the benchmark route.

### Milestone 2: Reduce chart startup long tasks and forced reflow

Once the initial route ships less code, the next leverage point is chart runtime behavior. `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` currently mounts chart decorations, runs an accessibility DOM sweep, restores visible-range persistence, and keeps syncing overlays during updates. That is coherent behavior, but it is still expensive enough to show up in the desktop report’s forced-reflow and long-task sections.

This milestone should treat the chart path as an execution budget problem. The implementation must identify which chart-side work is required on first paint, which work can be deferred until after the chart is visible, and which work can be batched into one animation frame instead of multiple layout-sensitive passes. The likely hotspots are the accessibility sweep in `apply-chart-accessibility!`, repeated overlay synchronization in `apply-chart-decorations!`, and geometry reads in the position, open-order, navigation, and volume overlay interop files. The work should prefer three patterns: run once instead of every update, batch related DOM reads and writes into one render pass, and subscribe only when the corresponding overlay or interaction mode is actually present.

This plan intentionally builds on the earlier completed chart performance plans rather than re-opening already-solved pan-only work. `/hyperopen/docs/exec-plans/completed/2026-03-13-chart-pan-interaction-performance.md` already removed obvious repaint churn during chart pan. The new requirement is narrower: the cold desktop trade boot still spends too much time doing chart-side work before the user has interacted at all.

At the end of this milestone, local release measurements should show a clear main-thread improvement on the benchmark route. The acceptance threshold is that the local benchmark’s TBT drops by at least `120ms` from baseline or reaches `<= 300ms`, whichever requires less improvement, while the governed trade-route browser QA still passes its jank/perf and layout-regression checks.

### Milestone 3: Stabilize shell geometry and low-risk polish

The report’s CLS is already good at `0.019`, so this milestone is not about rescuing the score. It is about removing the remaining low-cost layout and polish issues so the earlier JavaScript work does not leave visible rough edges behind. The current audit suggests two concrete candidates. First, the trade-chart loading shell in `/hyperopen/src/hyperopen/views/trade_view.cljs` does not reserve the exact same toolbar and panel geometry as the resolved chart in `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`. Second, the footer connection meter in `/hyperopen/src/hyperopen/views/footer/connection_meter.cljs` uses color transitions that trip the non-composited animation audit even though the impact is small.

This milestone should keep scope deliberately narrow. Match the chart shell dimensions and structure closely enough that the shell-to-chart swap does not move the toolbar or canvas row. Remove or simplify any tiny transition that exists only to satisfy aesthetics but does not justify an audit warning. If CSS trimming is still obviously cheap after the JavaScript milestones land, use `/hyperopen/tailwind.config.js`, `/hyperopen/src/styles/main.css`, and the current DaisyUI plugin settings to remove dead weight that is no longer needed. Do not broaden this milestone into deployment cache-header work unless the hosting configuration is materially available during implementation.

At the end of this milestone, local and deployed measurements should still keep `CLS <= 0.03`, and the governed design-review pass for `/trade` should not record avoidable chart-row or account-panel layout movement at `1280` and `1440`.

### Milestone 4: Re-run full validation and close only with deployed proof

After the code milestones land, rerun the repo gates, the smallest relevant Playwright route smoke first, the broader smoke suite second, and the governed trade-route browser QA last. Only after the deterministic local checks pass should the contributor perform a fresh desktop PageSpeed capture against `https://hyperopen.pages.dev/`.

This milestone closes the loop between development and the original user request. The plan is complete only when both local and deployed evidence say the same thing: the default desktop trade route now does less startup work. The preferred sign-off target is a deployed desktop PageSpeed score of `>= 90` with `TBT <= 300ms`, while keeping `FCP`, `LCP`, and `CLS` in the green band. Because remote lab runs are noisy, the fallback acceptance is that deployed PageSpeed improves TBT by at least `150ms` and overall score by at least `8` points from the April 1, 2026 baseline without regressing `CLS` above `0.03`.

## Concrete Steps

Run all commands from `/Users/barry/.codex/worktrees/cc82/hyperopen`.

1. Install Playwright browsers once if the local machine has not run the suite before.

    npm run test:playwright:install

2. Capture the deterministic release-style trade smoke first, because `/hyperopen/docs/BROWSER_TESTING.md` requires the smallest relevant Playwright command before broader browser validation.

    npx playwright test tools/playwright/test/routes.smoke.spec.mjs --grep "trade"

   Expected result: the `/trade` smoke tests pass and the route renders through the release-style static server defined in `/hyperopen/playwright.config.mjs`.

3. Run the broader committed smoke suite once the targeted trade smoke passes.

    npm run test:playwright:smoke

4. Build the release artifact whenever a milestone changes shipped payload or startup behavior.

    npm run build

5. Record the built artifact sizes after each milestone. Use the generated `out/release-public/` artifacts and store the numbers in the active artifact folder for comparison.

    wc -c out/release-public/js/*.js
    wc -c out/release-public/css/*.css
    gzip -c out/release-public/js/main*.js | wc -c
    gzip -c out/release-public/css/main*.css | wc -c

6. Run the governed browser QA review for the trade route after any UI-facing milestone that changes `/hyperopen/src/hyperopen/views/**` or `/hyperopen/src/styles/**`.

    npm run qa:design-ui -- --targets trade-route --manage-local-app

7. Clean up browser-inspection sessions after browser QA.

    npm run browser:cleanup

8. Run the required repository gates before claiming the milestone is complete.

    npm run check
    npm test
    npm run test:websocket

9. Perform a fresh manual desktop PageSpeed rerun against `https://hyperopen.pages.dev/` after the release is deployed. Record the report URL, capture time, and headline metrics in this plan before closing `hyperopen-k47v`.

## Validation and Acceptance

The work is complete only when all of the following are true:

1. The local release-style benchmark route still renders correctly under committed Playwright coverage, starting with targeted trade smoke and then the broader smoke suite.
2. The local release-style benchmark shows a clear startup improvement, measured by either `TBT <= 300ms` or at least `120ms` less TBT than the baseline captured in Milestone 0.
3. The initial `main` JavaScript path is materially smaller on cold load, measured as either at least `15%` less raw and gzipped size than the Milestone 0 baseline or a demonstrable movement of equivalent code into async chunks that do not load during the benchmark route.
4. Governed browser QA for `/trade` records explicit `PASS` or acceptable `BLOCKED` states for visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes at widths `375`, `768`, `1280`, and `1440`.
5. The trade shell does not regress its geometry contract. The chart row, order book, and lower account tables must remain flush and dimensionally stable on desktop widths, especially `1280` and `1440`.
6. `npm run check`, `npm test`, and `npm run test:websocket` all pass.
7. A fresh deployed desktop PageSpeed rerun against `https://hyperopen.pages.dev/` reaches either the preferred target of `Performance >= 90` with `TBT <= 300ms` or the fallback target of at least `8` score points and `150ms` TBT improvement over the April 1, 2026 baseline, while keeping `CLS <= 0.03`.

## Idempotence and Recovery

This plan is safe to execute incrementally because each milestone is additive and measurable. Repeating the build, Playwright, and repository test commands is safe. If a milestone partially lands and regresses behavior, recovery should prefer rolling back only the most recent milestone while preserving earlier measurement artifacts and validated cuts.

Milestone 1 must avoid breaking the public trade-route geometry or removing parity anchors that existing Playwright coverage expects. If a new async panel boundary proves too disruptive, keep the panel synchronous and instead defer the optional library or dormant feature that made it heavy. Milestone 2 must avoid changing chart semantics or order/position overlay correctness; if a batching or subscription reduction changes visible output, restore the previous behavior and move the optimization behind stricter guards. Milestone 3 must not broaden into deployment or service-worker redesign unless that work becomes explicitly available and separately measurable.

Always update the `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` sections after each stopping point so a future contributor can resume without reconstructing context.

## Artifacts and Notes

Create one milestone artifact root such as `tmp/desktop-trade-route-tbt-2026-04-01/` and keep the following inside it as work proceeds:

- the captured baseline metrics and later comparison notes
- the output or summaries of the targeted Playwright trade smoke run
- the output or summaries of `npm run qa:design-ui -- --targets trade-route --manage-local-app`
- the built asset byte counts after each milestone
- the final deployed PageSpeed report URL and headline metrics

Current first-wave artifact captured on 2026-04-01:

- `tmp/browser-inspection/trade-startup-profile-2026-04-01T16-27-37-777Z-3cb6df7b/`

Primary implementation files expected to change:

- `/hyperopen/shadow-cljs.edn`
- `/hyperopen/src/hyperopen/system.cljs`
- `/hyperopen/src/hyperopen/app/startup.cljs`
- `/hyperopen/src/hyperopen/runtime/action_adapters/navigation.cljs`
- `/hyperopen/src/hyperopen/views/app_view.cljs`
- `/hyperopen/src/hyperopen/views/trade_view.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/derived_cache.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/math_adapter.cljs`
- `/hyperopen/src/hyperopen/wallet/agent_session.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/chart_navigation_overlay.cljs`
- `/hyperopen/src/hyperopen/views/footer/connection_meter.cljs`
- `/hyperopen/src/hyperopen/views/asset_icon.cljs`
- `/hyperopen/src/styles/main.css`
- `/hyperopen/tailwind.config.js`

Primary validation files and commands:

- `/hyperopen/tools/playwright/test/routes.smoke.spec.mjs`
- `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`
- `/hyperopen/playwright.config.mjs`
- `/hyperopen/docs/BROWSER_TESTING.md`
- `/hyperopen/docs/agent-guides/browser-qa.md`
- `npm run test:playwright:smoke`
- `npm run qa:design-ui -- --targets trade-route --manage-local-app`
- `npm run browser:cleanup`
- `npm run check`
- `npm test`
- `npm run test:websocket`
- `npm run build`

Related tracked work that remains intentionally out of the main implementation path:

- `hyperopen-c2xn` — deployment cache policy for `release-public` assets

## Interfaces and Dependencies

The implementation must preserve the existing browser entry contract that `/trade` renders through the current app shell and release-style static output. It must keep the current deterministic test surfaces alive: `data-parity-id` anchors on the trade shell, the release-style Playwright server in `/hyperopen/playwright.config.mjs`, and the existing browser QA tooling under `/hyperopen/tools/browser-inspection/**`.

If new async browser modules are introduced, `/hyperopen/shadow-cljs.edn` must remain the single source of truth for those module boundaries, and the runtime load triggers must stay in repository-owned action or startup boundaries rather than being scattered through ad hoc DOM scripts. If indicator or wallet code is deferred, the public user-facing behavior must remain unchanged after the first interaction that requests those features. If chart overlay subscriptions are narrowed, they must remain pure infrastructure behavior and must not drop any user-visible overlay or persistence state.

Plan revision note: 2026-04-01 16:00Z. Created this active ExecPlan after the April 1, 2026 desktop PageSpeed audit showed the remaining deployed trade-route gap is dominated by startup JavaScript and chart-side main-thread work rather than paint speed.
Plan revision note: 2026-04-01 17:18Z. Refreshed the living sections after the first implementation wave landed: added the local startup profiler, deferred the initial trade-chart boot load, split wallet crypto helpers out of the startup path, updated tests, and recorded the current local profile evidence.
