---
owner: product
status: supporting
last_reviewed: 2026-03-12
review_cycle_days: 90
source_of_truth: false
---

# Chart Hover Navigation Controls PRD (Hyperliquid Parity)

## 1) Purpose

Add Hyperliquid-style chart navigation controls that appear when users hover the trading chart and let them quickly normalize the view without using wheel/drag gestures.

After this change, the chart will expose a bottom control rail with zoom, pan, and reset actions that work deterministically with the existing visible-range persistence model.

## 2) Reference Inputs and Analysis

### Hyperliquid behavior target

- User-provided screenshots from 2026-03-12 show a bottom chart control rail with five buttons in this order:
  - Zoom out (`-`)
  - Zoom in (`+`)
  - Scroll left
  - Scroll right
  - Reset chart view
- The same screenshots show a tooltip for the reset button: `Reset chart view`.
- The controls are not always visible and appear while hovering the chart surface.

### TradingView references (authoritative semantics)

- TradingView Help Center says `Navigation buttons` are located at the bottom of the chart and include:
  - Zoom in
  - Zoom out
  - Scroll left
  - Scroll right
  - Reset chart
  Source: https://www.tradingview.com/support/solutions/43000703699-how-to-configure-your-supercharts/
- TradingView release notes refer to the reset action as `scroll to most recent bar`, which aligns with restoring a normalized, current view.  
  Source: https://www.tradingview.com/charting-library-docs/latest/releases/release-notes
- TradingView Lightweight Charts API exposes the required primitives on `ITimeScaleApi` (`getVisibleLogicalRange`, `setVisibleLogicalRange`, `scrollPosition`, `scrollToPosition`, `fitContent`, `scrollToRealTime`) and `IChartApi` (`resetTimeScale`).  
  Sources:
  - https://tradingview.github.io/lightweight-charts/docs/api/interfaces/ITimeScaleApi
  - https://tradingview.github.io/lightweight-charts/docs/api/interfaces/IChartApi

### Interpretation

The target interaction is a compact bottom chart navigation rail that provides explicit, one-click viewport normalization and directional movement. In HyperOpen, this should be implemented as chart-runtime-local behavior (chart interop boundary), not as global app state.

## 3) Scope

### In scope

- Trading chart only (`/trade` chart panel).
- Hover/focus visibility behavior for bottom navigation controls.
- Five controls: zoom out, zoom in, pan left, pan right, reset chart view.
- Tooltip text for all controls, including `Reset chart view`.
- Deterministic interop operations that work with existing visible-range persistence.
- Unit tests for overlay behavior and interop wrapper wiring.

### Out of scope

- New chart indicators or toolbar menus.
- Mobile-specific always-visible chart navigation rail behavior.
- Portfolio and vault chart surfaces.
- Persistence model redesign.

## 4) User Stories

1. As a trader, when I hover the chart, I can quickly zoom out/in without wheel gestures.
2. As a trader, I can nudge the chart left/right in predictable steps.
3. As a trader, I can click one button to reset to a normalized recent view.
4. As a keyboard user, I can focus and activate these controls without mouse hover.

## 5) Product Requirements

### 5.1 Visibility and placement

- The control rail must render inside the trading chart canvas container.
- It must be anchored near the bottom-left edge of the chart content region.
- It must default to visually hidden and become visible on chart hover.
- It must also become visible when any control receives focus.

### 5.2 Controls and order

- Controls must appear left-to-right in this exact order:
  1. Zoom out
  2. Zoom in
  3. Scroll left
  4. Scroll right
  5. Reset chart view
- Each control must expose:
  - `aria-label`
  - `title` tooltip
  - button semantics (`type="button"`)

### 5.3 Behavioral contract

- `Zoom out`: increase visible logical range width around the current center.
- `Zoom in`: decrease visible logical range width around the current center.
- `Scroll left`: move visible logical range backward by a fixed fraction of current width.
- `Scroll right`: move visible logical range forward by a fixed fraction of current width.
- `Reset chart view`: restore the same normalized “recent data” viewport policy used by existing chart default range logic.

### 5.4 Persistence and determinism

- All button-driven viewport changes must count as user-visible range interactions.
- Existing visible-range persistence subscription must continue to observe and persist these updates.
- Reset must not bypass existing range policy; it must reuse the same default-range implementation already used during chart initialization.

### 5.5 Accessibility and interaction quality

- Buttons must meet minimum 24x24 CSS pixel hit target.
- Buttons must have visible focus treatment.
- Controls must not interfere with chart crosshair when hidden.
- Controls must not emit duplicate click actions from pointerdown/click overlap.

## 6) Technical Design Summary

- Implement a dedicated chart interop overlay module (same boundary style as legend/open-order/volume overlays).
- Overlay module responsibilities:
  - Mount/unmount DOM root.
  - Manage hover/focus visibility.
  - Render five buttons with tooltips/icons.
  - Execute time-scale operations for zoom/pan.
  - Invoke injected reset callback for normalized restore.
  - Invoke injected interaction callback after successful user-triggered range changes.
- Core chart lifecycle (`mount/update/unmount`) must call:
  - `sync-chart-navigation-overlay!`
  - `clear-chart-navigation-overlay!`

## 7) Acceptance Criteria

1. On hover over chart canvas, the bottom navigation rail appears; on mouse leave it hides.
2. Controls are ordered `-`, `+`, left, right, reset and each has tooltip text.
3. Repeated click on zoom buttons changes visible range width in expected direction.
4. Repeated click on pan buttons shifts visible range in expected direction.
5. Clicking reset returns chart to default normalized recent view.
6. Focusing a button via keyboard reveals and keeps controls visible.
7. Existing chart behavior (legend, overlays, timeframe switching, visible-range persistence) remains functional.

## 8) Validation Plan

- Unit tests for overlay:
  - Mount/visibility/focus behavior.
  - Zoom/pan/reset callbacks and range mutation.
  - Tooltip/label presence.
  - Cleanup on clear.
- Unit tests for chart interop wrappers.
- Required repo quality gates:
  - `npm run check`
  - `npm test`
  - `npm run test:websocket`

