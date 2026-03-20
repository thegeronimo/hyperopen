# Restore Governed Browser QA Closure For Trade, Portfolio, And Vaults

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-v7jc`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

The March 20 governed browser runs are red again, but the failure set is now split three ways:

1. local browser-QA bootstrap debt for `/trade`, `/portfolio`, and `/vaults`
2. a real mobile position-overlay regression on `/trade`
3. a real `/portfolio` tablet-width perf regression

This plan tracks the combined cleanup needed to get managed local browser QA green again without changing public route contracts.

## Progress

- [x] (2026-03-19) Created and claimed `hyperopen-v7jc` for the remaining shared browser-QA cleanup.
- [x] (2026-03-19) Landed the earlier `/vaults` focus and breakpoint cleanup slice and reran governed review.
- [x] (2026-03-20) Audited the March 20 nightly and design-review artifacts, plus live local-app responses, and split the current red set into bootstrap debt versus product regressions.
- [x] (2026-03-20) Refreshed the browser-inspection local bootstrap path so managed local runs start from `/index.html`, reject `404` readiness, and navigate through `:actions/navigate`.
- [x] (2026-03-20) Hoisted mobile position overlays out of expandable-card detail content, added fallback layout anchors for active card layouts, and proved the chart-surface mobile margin flow still renders a single mounted sheet.
- [x] (2026-03-20) Removed benchmark-selector candidate recompute on `/portfolio` focus/blur toggles at `768px` and added cache-stability regressions.
- [x] (2026-03-20) Restored stable default height for the desktop trade account-table panel across standard tabs so chart/order-book alignment no longer shifts when switching between balances, positions, and history tabs.
- [x] (2026-03-20) Diagnosed the remaining desktop trade layout regression from live browser evidence and two explorer audits: the chart shell was under-filling the top row, while the lower account panel still had competing row, panel, header, and viewport sizing contracts.
- [x] (2026-03-20) Replaced the stale desktop row-class path with an explicit inline desktop grid contract so the lower account panel keeps a stable viewport-scaled height and the chart/order-book row no longer collapses toward content auto-height.
- [ ] Re-run targeted scenarios, required repo gates, `qa:design-ui`, and `qa:nightly-ui` with `--manage-local-app`.

## Surprises & Discoveries

- Observation: direct deep links on the local dev server currently return `404`, even though `/index.html` boots the SPA correctly.
  Evidence: `curl -I http://localhost:8080/trade`, `curl -I http://localhost:8080/portfolio`, and `curl -I http://localhost:8080/vaults` returned `HTTP/1.1 404 Not Found`, while `curl -I http://localhost:8080/index.html` returned `200 OK`.

- Observation: browser-inspection currently treats any `<500` local response as “ready,” so a `404` deep link is incorrectly accepted as a healthy managed local app.
  Evidence: `/hyperopen/tools/browser-inspection/src/local_app_manager.mjs` `waitForUrl` and `/hyperopen/tools/browser-inspection/src/preflight.mjs` `probeUrl` both accept any status from `200` through `499`.

- Observation: both scenario runs and design review navigate straight to route URLs like `http://localhost:8080/trade`, so the current managed-local flow never warms the SPA before asking route oracles for parity roots.
  Evidence: `/hyperopen/tools/browser-inspection/src/scenario_runner.mjs`, `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs`, and `/hyperopen/tools/browser-inspection/config/design-review-routing.json`.

- Observation: the mobile position-margin scenario reaches the debug bridge, dispatches `:actions/open-position-margin-modal`, and still ends with `open: false` and `presentationMode: "closed"`.
  Evidence: `tmp/browser-inspection/nightly-ui-qa-2026-03-20T12-15-34-925Z-070c11f7/scenarios/mobile-position-margin-presentation-mobile.md`.

- Observation: mobile position overlays are mounted inside expandable-card `detail-content`, which only exists when the card is expanded.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs` `expandable-card` and `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` `mobile-position-card-from-vm`.

- Observation: the current trade positions view mounts overlay surfaces in both the desktop row tree and the mobile-card tree, which previously produced duplicate overlay nodes in manual QA.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` renders overlay views in both `position-row-from-vm` and `mobile-position-card-from-vm`, and `/hyperopen/docs/qa/mobile-position-overlay-parity-qa-2026-03-08.md` notes `overlayCount: 2`.

- Observation: the remaining `/portfolio` governed failure is a real long task during post-idle focus/scroll interaction sampling at `768px`, not a buffered page-load false positive.
  Evidence: `tmp/browser-inspection/design-review-2026-03-20T12-16-37-233Z-28275c95/portfolio-route/review-768/probes/interaction-trace.json` reports `maxLongTaskMs: 211`, while the current interaction trace waits for idle before sampling.

- Observation: `returns-benchmark-selector-model` currently includes `suggestions-open?` in its cache key, so simple focus/blur toggles rebuild the candidate list.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`.

- Observation: the mobile `Account` footer surface is intentionally summary-only; the account-history tabs remain owned by the mobile market surfaces, so the governed `mobile-position-margin-presentation` scenario was still targeting the wrong surface after the March 9 parity change.
  Evidence: `/hyperopen/src/hyperopen/views/trade_view.cljs` renders `trade-mobile-account-summary-panel` for `:account` and keeps `trade-account-tables-panel` mounted only for market surfaces, while `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs` asserts that `:account` does not show balances/open-orders/trade-history text.

- Observation: the desktop trade account-table shell had drifted so `:balances` received `lg:h-[29rem]` while other standard tabs only kept `h-96`, which explains the user-reported chart/order-book misalignment and height jumps when switching tabs.
  Evidence: `/hyperopen/src/hyperopen/views/account_info_view.cljs` had a tab-specific `panel-shell-classes` branch, and the restored regression in `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs` now locks stable default height across balances and positions.

- Observation: the blank desktop gap under the chart is not an order-book overflow; the desktop trade grid stretches the top row, but the inner chart shell does not fill that row.
  Evidence: `/hyperopen/src/hyperopen/views/trade_view.cljs` uses a tall desktop top row, while `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` `trading-chart-view` and `/hyperopen/src/hyperopen/views/trade_view.cljs` `trade-chart-loading-shell` both lacked a full-height inner flex shell before this cleanup, so the chart stopped at its toolbar plus `min-h-[360px]` host floor and left visible empty row space below.

- Observation: the lower account panel still changed perceived geometry across the seven standard tabs because desktop trade sizing was owned by multiple layers at once: the trade grid row, the embedded panel root, the header band, and inconsistent desktop table viewports.
  Evidence: `/hyperopen/src/hyperopen/views/trade_view.cljs` row 2 remained content-sized, `/hyperopen/src/hyperopen/views/account_info_view.cljs` imposed its own desktop height, `tab-navigation` collapsed the action slot for `:twap`, and desktop table viewports across balances, positions, trade history, and `table/tab-table-content` did not share one `min-w-0 overflow-auto` contract.

- Observation: the desktop trade grid row classes were not the effective runtime source of truth in the live app, so the lower account row kept collapsing toward content height while the chart row absorbed the rest of the viewport.
  Evidence: live browser inspection on 2026-03-20 measured the pre-fix local grid at `1440x900` with computed `gridTemplateRows` of `588px 199px`, and at `1440x1213` with computed `gridTemplateRows` of `901px 199px`, despite the class list still advertising the older desktop row classes. After moving the contract to inline style, the same surfaces measured `445px 342px` at `1440x900` and `639.0625px 460.9375px` at `1440x1213`.

## Decision Log

- Decision: treat the route-smoke cluster as managed-local browser-QA bootstrap debt, not as missing production route code.
  Rationale: the parity roots exist in the view code, while managed local deep links are returning `404` before the SPA boots.
  Date/Author: 2026-03-20 / Codex

- Decision: fix managed local navigation in browser-inspection by bootstrapping through `/index.html` and dispatching `:actions/navigate`, rather than adding ad hoc per-scenario warm-up workarounds.
  Rationale: the same failure mode affects scenario runs and design review, so the fix belongs in shared tooling.
  Date/Author: 2026-03-20 / Codex

- Decision: hoist mobile position overlays to a positions-tab-level outlet while keeping desktop inline overlays for the desktop table layout only.
  Rationale: the open modal state already lives above the card row, and card-local mounting is what makes collapsed-row and duplicate-overlay states possible.
  Date/Author: 2026-03-20 / Codex

- Decision: split benchmark candidate derivation from `suggestions-open?` instead of changing the view-level focus/blur contract.
  Rationale: focus/blur is valid UI state churn; the expensive work should not be keyed on it.
  Date/Author: 2026-03-20 / Codex

- Decision: treat the remaining governed `mobile-position-margin-presentation` red as scenario drift after the March 9 mobile account-surface redesign, and retarget it to the chart/mobile-market surface where account tabs and position cards still exist.
  Rationale: live browser inspection shows the real mobile chart-surface positions flow opens a mounted margin sheet successfully, while the `:account` surface intentionally hides the account tables by design and by test contract.
  Date/Author: 2026-03-20 / Codex

- Decision: make `trade-view` own the desktop lower-panel height and pass a fill-height contract into the embedded desktop `account-info-view`, instead of keeping a second fixed desktop height inside `account-info-view`.
  Rationale: the user-visible regression comes from competing height contracts. One grid-owned desktop row height is simpler and keeps all seven tabs inside one stable outer box.
  Date/Author: 2026-03-20 / Codex

- Decision: reserve a stable desktop account-tab action band even for tabs with no actions, and normalize desktop table viewports to `min-w-0 overflow-auto`.
  Rationale: the header and table shell should not visually jump when switching between balances, TWAP, and the history tabs. A stable action slot plus one overflow contract addresses the remaining width and spacing drift without changing the tab-specific tools.
  Date/Author: 2026-03-20 / Codex

- Decision: make the desktop trade shell own its row proportions through inline `grid-template-rows`, using a viewport-scaled lower account row (`clamp(21rem, 38vh, 29rem)`) plus a chart-row minimum, instead of relying on Tailwind-generated arbitrary row classes for this contract.
  Rationale: live browser evidence showed the class-based row split was not the effective runtime contract in the loaded app, while the inline style immediately produced the intended stable geometry across viewport heights and tab switches.
  Date/Author: 2026-03-20 / Codex

## Outcomes & Retrospective

Work in progress. Success for this pass requires:

- managed local browser-inspection no longer treating `404` route URLs as healthy startup
- targeted route-smoke, wallet, and asset-selector scenarios passing under `--manage-local-app`
- mobile position-margin opening as a mounted sheet even when the card is collapsed
- `/portfolio` `review-768` clearing `jank-perf`
- required repo gates passing
- the desktop trade chart fills the full top row with no blank gap above the account panel
- the desktop account panel keeps one stable outer height and width while switching between balances, positions, open orders, TWAP, trade history, funding history, and order history
- the desktop trade shell keeps the lower account panel near the Hyperliquid reference proportion instead of collapsing it toward content height as viewport height grows

## Context and Orientation

The affected implementation seams are:

- browser-inspection local startup and navigation:
  `/hyperopen/tools/browser-inspection/src/local_app_manager.mjs`
  `/hyperopen/tools/browser-inspection/src/preflight.mjs`
  `/hyperopen/tools/browser-inspection/src/session_manager.mjs`
  `/hyperopen/tools/browser-inspection/src/scenario_runner.mjs`
  `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs`
  `/hyperopen/tools/browser-inspection/config/defaults.json`
  `/hyperopen/tools/browser-inspection/config/design-review-routing.json`

- trade positions overlays:
  `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs`
  `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`
  `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`

- portfolio benchmark selector VM:
  `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`
  `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
  `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs`

## Plan of Work

First, correct managed-local browser-inspection startup and route navigation so shared tools stop navigating directly into `404` deep links. This must include readiness semantics, a reusable local SPA bootstrap path, and JS tests that cover the new contract.

Next, refactor the positions tab so mobile-card layouts render their active overlay surface from one tab-level outlet instead of from inside each card’s expanded detail subtree. Desktop table rows should keep their existing inline overlay behavior.

Then, update the portfolio benchmark selector VM so candidate derivation is cached independently from `suggestions-open?`, and pin that behavior with deterministic tests.

Finally, rerun targeted scenarios, repo gates, governed design review, and nightly UI QA with managed local app startup.

In the final desktop trade cleanup slice, let the desktop trade grid own the lower account-panel height, make the chart shell fill the top row, keep a stable desktop account-tab action band even when the selected tab has no actions, and normalize the desktop table viewport overflow contract so tab switches do not change the outer box geometry.

## Concrete Steps

1. Update the browser-inspection managed-local flow to:

   - probe a `2xx` bootstrap URL
   - reject `404` deep-link readiness
   - bootstrap the SPA through `/index.html`
   - wait for `HYPEROPEN_DEBUG`
   - dispatch `[:actions/navigate <full-browser-path>]`
   - wait for idle before route assertions

2. Add or adjust browser-inspection tests covering:

   - readiness classification for `404` versus `200`
   - local SPA navigation helper behavior
   - scenario/design-review local-route integration

3. Edit `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` so:

   - desktop inline overlays render only in desktop table layout
   - mobile-card layouts render active overlays from a single positions-tab-level outlet
   - collapsed rows can still mount the active overlay

4. Extend `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs` with regressions for:

   - collapsed mobile row plus open margin modal
   - single mobile overlay mount
   - unchanged desktop inline overlay behavior

5. Edit `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs` so benchmark candidate derivation is cached by options signature, selected coins, and search only.

6. Extend `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs` so focus/blur toggles do not trigger candidate recompute when other selector inputs are unchanged.

7. Run validation:

   - `npm run test:browser-inspection`
   - targeted CLJS tests for positions and portfolio benchmark VM
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
   - targeted scenario reruns under `--manage-local-app`
   - `npm run qa:design-ui -- --targets trade-route,portfolio-route,vaults-route --manage-local-app`
   - `npm run qa:nightly-ui -- --allow-non-main --manage-local-app`

8. Verify the desktop `/trade` layout directly after the code changes by switching the seven standard account tabs in the live app. The chart and order book should stay flush with the account panel, and the lower account panel should not change outer height or width as the selected tab changes.

Plan update note (2026-03-20): expanded the plan to include the remaining desktop trade layout regression after live browser inspection showed a second root cause beyond the earlier balances-versus-positions height drift. The plan now records the chart under-fill defect, the competing desktop lower-panel sizing contracts, and the requirement that all seven standard account tabs keep one stable outer box.
