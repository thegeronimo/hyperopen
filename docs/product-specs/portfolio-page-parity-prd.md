---
owner: product
status: supporting
last_reviewed: 2026-02-19
review_cycle_days: 90
source_of_truth: true
---

# Portfolio Page Parity PRD (Hyperliquid Reference)

## Overview

Build a dedicated `/portfolio` experience in Hyperopen that is structurally and behaviorally aligned with Hyperliquid’s portfolio page, while reusing existing Hyperopen account data pipelines and interaction primitives.

This PRD defines the full target product behavior and the initial delivery scope (Phase 1) required in this task.

## Reference Inspection Summary

Reference source inspected:
- Live page: `/portfolio` on Hyperliquid (`https://app.hyperliquid.xyz/portfolio`)
- Desktop screenshot capture: `/hyperopen/tmp/hyperliquid-portfolio-full.png`
- Tablet screenshot capture: `/hyperopen/tmp/hyperliquid-portfolio-1024.png`
- Mobile screenshot capture: `/hyperopen/tmp/hyperliquid-portfolio-mobile390.png`
- Loaded portfolio UI bundle/chunks:
  - `/hyperopen/tmp/hyperliquid-185.pretty.js`
  - `/hyperopen/tmp/hyperliquid-6951.pretty.js`

Observed structure:
1. Global nav with `Portfolio` active.
2. Jurisdiction warning bar near top of page.
3. Portfolio heading + action button row (`Link Staking`, `Swap Stablecoins`, `Perps ↔ Spot`, `EVM ↔ Core`, `Portfolio Margin`, `Send`, `Withdraw`, `Deposit`).
4. Summary grid:
   - Card A: `14 Day Volume` + `View Volume`.
   - Card B: `Fees (Taker / Maker)` + market scope toggle (`Perps`/`Spot`) + `View Fee Schedule`.
   - Card C: account summary list (PNL, volume, max drawdown, total equity, perps/spot/earn balances) with scope + time selectors (`Perps + Spot + Vaults`, `30D`).
   - Card D: graph panel with tabs (`Account Value`, `PNL`) and time selector.
5. Wide account table panel with tabs (`Balances`, `Positions`, `Open Orders`, `TWAP`, `Trade History`, `Funding History`, `Order History`, `Interest`, `Deposits and Withdrawals`) and `Hide Small Balances` control.
6. Mobile behavior: the same sections are stacked vertically with the actions and summary cards wrapping.

## Problem Statement

Hyperopen currently sends `/portfolio` to the trade view, so users do not get a portfolio-first information hierarchy. The current experience does not match expected navigation semantics or layout for portfolio workflows.

## Product Goals

1. Make `/portfolio` a first-class route with dedicated layout.
2. Surface portfolio KPIs at top of page before detailed tables.
3. Preserve existing account table workflows and data parity.
4. Provide responsive behavior matching desktop and mobile usage patterns.

## Non-Goals (Phase 1)

1. Full feature parity for every Hyperliquid modal or downstream flow.
2. New backend APIs.
3. Full historical chart engine parity.
4. New websocket protocol changes.

## Users

1. Active traders monitoring account risk and PNL.
2. Portfolio users checking balances, orders, and history in one place.
3. Mobile users needing quick portfolio status without opening the trade screen.

## Information Architecture

Primary sections in order:
1. Optional warning/information banner.
2. Page header (`Portfolio`) and actions row.
3. KPI summary grid.
4. Account detail tables.

## Functional Requirements

### FR-1 Route and Navigation

1. `/portfolio` MUST render a dedicated portfolio view, not the trade layout.
2. Header nav MUST mark `Portfolio` as active when path starts with `/portfolio`.
3. Header nav MUST mark `Trade` as active when path starts with `/trade`.

### FR-2 Action Row

1. Portfolio view MUST render action buttons matching the Hyperliquid IA:
   - Link Staking
   - Swap Stablecoins
   - Perps ↔ Spot
   - EVM ↔ Core
   - Portfolio Margin
   - Send
   - Withdraw
   - Deposit
2. Buttons MUST be wired to existing Hyperopen navigation/actions where available.
3. Unimplemented destinations MUST fail safely (no runtime errors) and can route to existing placeholders.

### FR-3 KPI Summary Cards

1. Card A (`14 Day Volume`) MUST show a computed USD-like volume value with fallback.
2. Card B (`Fees (Taker / Maker)`) MUST show taker/maker values with fallback defaults.
3. Card C (`Perps + Spot + Vaults` summary) MUST show at least:
   - PNL
   - Volume
   - Max Drawdown (fallback allowed)
   - Total Equity
   - Perps Account Equity
   - Spot Account Equity
   - Earn Balance
4. Card D (`Account Value` / `PNL`) MUST provide a chart container and the metric toggle affordance.

### FR-4 Account Details Panel

1. Portfolio page MUST include the existing account detail panel to preserve current behavior.
2. Existing table interactions MUST remain functional:
   - tab switching
   - sort controls
   - hide small balances toggle
   - pagination where already supported

### FR-5 Responsive Behavior

1. Desktop: summary panel MUST use multi-column layout.
2. Mobile: summary and actions MUST stack and remain readable/tappable.
3. Interactive controls MUST meet minimum 24x24 touch target.

### FR-6 Accessibility and Interaction Quality

1. Controls MUST remain keyboard reachable.
2. Color usage MUST not be the sole semantic indicator for values.
3. Loading/empty/error states MUST be explicit for KPI surfaces.

## Data Requirements

### DR-1 Inputs

Use existing state inputs only in Phase 1:
- `:webdata2`
- `:orders`
- `:spot`
- `:account`
- `:perp-dex-clearinghouse`
- `:wallet`

### DR-2 Derived Metrics

Phase 1 metric derivations:
1. `14 Day Volume`: derive from available fills/trade history in state; fallback `0`.
2. `Fees`: use trading defaults (`taker 0.045`, `maker 0.015`) when account-tier data is unavailable.
3. `Total Equity`: derive from existing account equity logic/projections.
4. `Spot/Perps/Earn` values: derive from existing account projection helpers where possible.

### DR-3 Fallback Policy

If source data is unavailable, render deterministic placeholder values (`$0.00`, `0.00%`, `--`) without throwing.

## UX Copy and Formatting

1. Money: `$` prefix with compact formatting for high-level cards.
2. Percentages: fixed 2-4 decimals as context requires.
3. PNL sign: explicit `+`/`-` and color, but include textual sign independent of color.

## Technical Constraints

1. Preserve existing public APIs and runtime contracts.
2. Keep websocket decisions pure/deterministic (no new side effects in view layer).
3. Reuse existing account-info and account-equity derivation paths where possible.

## Acceptance Criteria

### AC (Phase 1 delivery in this task)

1. Navigating to `/portfolio` renders dedicated portfolio layout.
2. Header active state correctly switches between `Trade` and `Portfolio` by route.
3. Portfolio layout includes:
   - title
   - action row
   - KPI summary card region
   - account details panel
4. KPI values render from state-derived data with safe fallbacks.
5. Existing account details tab interactions still function.
6. Layout is readable and usable on desktop and narrow mobile widths.
7. Required repository gates pass:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

### AC (future parity phases)

1. Full action-button flow parity (all modals/routes).
2. Portfolio history chart parity (time range + data domain toggles).
3. Full table-tab parity including `Interest` and `Deposits and Withdrawals` as native tabs.

## Risks and Mitigations

1. Risk: Existing account data does not include direct 14-day volume.
   - Mitigation: deterministic fallback and explicit metric derivation helper.
2. Risk: Route/nav changes could regress existing tests.
   - Mitigation: add focused route/nav rendering tests.
3. Risk: Mobile overflow in KPI grid.
   - Mitigation: explicit responsive grid breakpoints and spacing.

## Release Plan

1. Phase 1: route + layout + baseline data wiring + tests (this task).
2. Phase 2: richer charting and action workflows.
3. Phase 3: full tab parity and deeper account workflows.
