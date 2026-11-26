# Trade route fits laptop viewports and keeps the market selector reachable

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It is maintained in accordance with `/hyperopen/docs/PLANS.md` and the detailed template in `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

On a laptop (13 to 15 inch, browser viewports around 1280x700 to 1512x860) the desktop `/trade` shell is taller than the space under the header, so the whole terminal scrolls inside a scroll shell whose scrollbar is hidden. When a user scrolls to read positions or open orders, the market strip (the asset selector) scrolls out of view, and scrolling back is hard: wheel over the chart zooms the chart, and nothing shows that the page scrolled at all. Between 1024 and 1279 px wide the asset button also scrolls sideways out of the statistics row, and the order ticket spills over the order book.

After this change the terminal fits laptop viewports without outer scrolling, the order ticket column scrolls on its own, and on any viewport short enough to still need outer scrolling the market strip stays pinned at the top of the chart column, with the asset button pinned at the left of the statistics row.

## Context References

- Direct user request in this session (2026-11-25): full laptop design review of `/trade`, root causes, and a fix that keeps the asset selector reachable down to a 13 inch display.
- Hyperliquid comparison (2026-11-25, 1280x700): its page also scrolls (main scroller 1191 vs 614 px) and its selector also scrolls away, so parity alone is not the fix.
- Shell geometry contract: `/hyperopen/docs/agent-guides/trading-ui-policy.md` (chart and order book flush with the account panel, stable panel geometry across tabs) and `/hyperopen/docs/agent-guides/browser-qa.md`.

## Root causes (measured, spectate banner present, before the change)

1. Rigid desktop minimums. The grid's min height is `5.5rem strip + 2.8125rem toolbar + 22.5rem canvas + clamp(17rem, 32vh, 23rem)` = 765 px, larger than the shell on every laptop (shell 536 at 1280x700, 656 at 1440x820, 741 at 1440x900). The chart row never grows past its minimum on a laptop.
2. The order ticket column sets the page height. It is `overflow-visible`; ticket 570 px + account equity 293 px = 863 px. It overflows its own cell by 82 to 98 px at xl and by 370 px at 1024 wide, where it is drawn over the order book.
3. The overflow goes to `trade-scroll-shell`, which is `scrollbar-hide`, and the market strip lives inside the scrolled content (not pinned). Wheel over the chart is consumed by the chart; wheel over the account table chains into the shell.
4. The asset button is the first cell of the horizontally scrollable statistics row (`active-asset-statistics-scroll`); below about 1440 wide the row overflows (7 to 167 px) and the button can scroll off to the left.

## Progress

- [x] (2026-11-25) Reproduced and measured at 1024x700, 1100x760, 1280x700, 1280x800, 1366x660, 1440x820, 1440x900, 1512x860; compared Hyperliquid.
- [x] (2026-11-25) Root causes and design recorded.
- [x] (2026-11-25) Implemented: lower minimums (grid, chart canvas hosts), ticket column scroll, pinned strip, pinned asset button, popovers and click-outside backdrops kept working inside the ticket column, selector-open chart lift, viewport-aware selector list height.
- [x] (2026-11-25) Unit tests and the Playwright reachability regression (`market selector stays reachable on laptop and short desktop viewports`).
- [x] (2026-11-25) `npm run gates` 34/34 PASS; popover sweep at 1280x700 (spectate banner) and 1100x760: timeframe, chart type, indicators, leverage, margin mode, size unit, TIF, Pro menu, liquidation and fees tooltips all reachable; no ticket popover escapes the column on Limit or TWAP.
- [x] (2026-11-25) `trade-regressions.spec.mjs` and `mobile-regressions.spec.mjs` run in full: every failure also fails on an unmodified build of the base commit (listed under Validation); the shell geometry, zoom-equivalent and new reachability specs pass.
- [x] (2026-11-25) Stats strip focus ring: the focusable market statistics scroller drew a primary ring on mouse click; it now uses `focus-visible`, so only keyboard focus shows it.
- [x] (2026-11-25) Landed: committed `ef5f9d0bf` and merged to `main` as `c567f9559` (branch `feature/trade-laptop-viewport-fit`) after `npm run gates` 34/34; owner checked the page on a real laptop; plan moved to `docs/exec-plans/completed/`.

## Decision Log

- Decision: lower the desktop minimums to `12rem` canvas and `clamp(12rem, 30vh, 23rem)` account panel (grid min about 517 px). Rationale: fits a 1280x700 viewport without the spectate banner and keeps the account panel share under 0.4 at 1280x800 and 1440x900.
- Decision: the order ticket column becomes its own vertical scroller at `lg` and up. Rationale: the ticket plus account equity is taller than any laptop shell; a column scroller is the standard terminal pattern and stops the column from setting the page height. The three popovers that relied on horizontal overflow (leverage panel, liquidation tooltip, fees tooltip) are anchored to stay inside the 320 px column.
- Decision: keep the outer scroll shell as the fallback for short viewports (existing regression test at heights up to 535 px), show its scrollbar at `lg`, and pin the desktop market strip with `position: sticky` at the top of the chart column. The chart panel uses `lg:overflow-clip` (mobile keeps `overflow-hidden`) so it clips without becoming the sticky element's scroll container. With the lowered minimums the scroll range stays smaller than the chart row height minus the strip; verified at 1102x459 with the spectate banner. Not measured under the hyperdegen theme, whose stats strip and widgets row add to the scroll range.
- Decision: pin the asset button cell at the left of the statistics scroller (`position: sticky; left: 0` with the strip background) instead of restructuring the row.

- Decision: the five order-ticket click-outside backdrops become `fixed inset-0` instead of a 200vmax absolute square. Rationale: inside a scrolling column the absolute square is clipped to the column and stretches its scroll area; a fixed layer covers the viewport the same way.
- Decision: while the asset selector is open on desktop, the chart panel is lifted (`relative z-[160] overflow-visible`, the existing funding-tooltip treatment) so the 437 px dropdown is not clipped by a chart row that is now shorter; the list height is `clamp(6rem, 100vh - 25rem, 16rem)` (the budget includes the fixed footer) so the dropdown fits short viewports. The sticky strip sits at `z-[130]`, above the chart's own layers (up to 120) and below header menus (260).

- Decision: the chart toolbar's timeframe (523 px) and chart-type menus scroll inside a cap of `max(8rem, 100vh - clamp(12rem, 30vh, 23rem) - 17rem)` instead of `overflow-hidden`. Rationale: they open inside the chart's own `overflow-hidden` wrappers, so they were already clipped on any viewport under about 1000 px tall; the shorter chart row made it worse. The cap tracks the space between the toolbar and the account panel.
- Decision: accept that the 30vh account panel is slightly shorter than the old 32vh on large monitors (about 19 px at a 950 px viewport).

## Surprises & Discoveries

- Hyperliquid's trade page also scrolls at 1280x700 and its selector also leaves the screen; the difference users feel is discoverability and how easy it is to get back.
- At 1024 to 1279 px the ticket column already overlapped the order book by up to 370 px.
- The chart canvas hosts carry their own `min-h-[360px]` / `min-h-[320px]`, which silently overrode the grid's canvas minimum and pushed the time axis out of the panel once the row shrank; they now drop to `12rem` at `lg`.
- The worktree's Tailwind watcher can miss edits and overwrite a manual `npm run css:build` with stale classes; restart `npm run dev` after class changes before trusting browser QA.
- After the change, measured with the spectate banner: 1280x700 shell overflow 0 (was 327), 1440x820 overflow 0 (was 207), chart canvas 311 px and account panel 246 px at 1440x820.

## Validation

- Unit: `test/hyperopen/views/trade_view/layout_test.cljs`, `layout_state_test.cljs`, `mobile_surface_test.cljs`, `loading_shell_test.cljs`, `test/hyperopen/views/active_asset/row_selector_pin_test.cljs`, `test/hyperopen/views/trading_chart/timeframe_dropdown_test.cljs`, `chart_type_dropdown_test.cljs`.
- Playwright: `market selector stays reachable on laptop and short desktop viewports` (1024x700, 1280x700, 1366x660, 1440x820, 1512x860, 1102x459): shell fits without outer scroll at laptop sizes, ticket stays in its cell, then at max shell scroll and max statistics scroll it hit-tests and mouse-clicks the selector, checks the dropdown fits the viewport and its search is uncovered, types ETH and mouse-clicks the ETH row.
- Pre-existing failures, reproduced on an unmodified build of the same base commit: the outcome-market specs in `trade-regressions.spec.mjs` at lines 1707, 1731, 1780, 1888 and 1932 assert live outcome data and copy (percent chances, USDH payout copy, a Spain option, a fixed price). Line 2002 (same USDH copy), the three vault specs at lines 2766, 2967 and 3133 (vault request counts) and the trading-settings toggle spec at line 4024 also fail on that unmodified baseline.

## Outcomes & Retrospective

The desktop `/trade` terminal now fits laptop viewports without outer scrolling (shell overflow 0 at 1280x700 and 1440x820 with the spectate banner, down from 327 and 207 px), the order ticket column scrolls on its own, and on shorter viewports the market strip stays pinned with the asset button pinned at the left of the statistics row. The Playwright regression clicks the selector and picks ETH at five laptop sizes and at 1102x459. A mouse click on the statistics strip no longer draws a focus ring.

Closed 2026-11-25 with these left open, not done and not tracked elsewhere:
- Not measured: the pinned strip's scroll range under the hyperdegen theme, whose stats strip and widgets row add height.
- Constraint for future work: any new popover in the order ticket column must fit the 320 px column, and any chart-column popover must fit the shorter chart row or lift the panel.
- Pre-existing failing Playwright specs listed under Validation still fail on `main`.
