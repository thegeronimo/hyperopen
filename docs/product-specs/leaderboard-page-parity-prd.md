---
owner: product
status: supporting
last_reviewed: 2026-03-24
review_cycle_days: 90
source_of_truth: true
---

# Leaderboard Page Parity PRD (Hyperliquid Reference)

## Overview

Build a dedicated `/leaderboard` experience in Hyperopen that is structurally and behaviorally aligned with Hyperliquid’s leaderboard page, while staying intentionally lean.

The reference page is not a dashboard with hero cards, charts, or extra promos. It is a dense ranking surface: title, controls, ranked traders, and a methodology note. This PRD defines the full target product behavior and a pragmatic Phase 1 scope that activates the route and delivers a usable read-only leaderboard.

## Reference Inspection Summary

Reference sources inspected on March 24, 2026:
- Live page route: `https://app.hyperliquid.xyz/leaderboard`
- Leaderboard route bundle loaded by the live page:
  - `https://app.hyperliquid.xyz/static/js/4047.d6c5d5f1.chunk.js`
- Live leaderboard data feed:
  - `https://stats-data.hyperliquid.xyz/Mainnet/leaderboard`
- Live vaults data feed used by the route for filtering:
  - `https://stats-data.hyperliquid.xyz/Mainnet/vaults`
- Hyperopen repo evidence:
  - `src/hyperopen/views/header/nav.cljs`
  - `src/hyperopen/route_modules.cljs`
  - `src/hyperopen/views/app_view.cljs`

Observed Hyperliquid page structure:
1. Page title only: `Leaderboard`.
2. Control row with:
   - search input
   - time-range selector with `Day`, `Week`, `Month`, and `All Time`
3. Ranking surface:
   - desktop: sortable paginated table
   - mobile/tablet: stacked ranking cards with pagination controls
4. Columns / row metrics:
   - `Rank`
   - `Trader`
   - `Account Value`
   - `PnL (<selected window>)`
   - `ROI (<selected window>)`
   - `Volume (<selected window>)`
5. Connected-user treatment:
   - if the connected address appears in results, it is pinned to the top
   - row shows a `YOU` badge
   - current user can open a display-name edit modal in the reference app
6. Trader rows drill into an address detail destination.
7. Footer copy explains the leaderboard methodology / formula.

Observed live data shape:
1. `Mainnet/leaderboard` returns `leaderboardRows`.
2. Each row includes:
   - `ethAddress`
   - `accountValue`
   - `windowPerformances`
   - `prize`
   - optional `displayName`
3. `windowPerformances` contains `day`, `week`, `month`, and `allTime` values for:
   - `pnl`
   - `roi`
   - `vlm`
4. On March 24, 2026, the raw feed returned `32,967` rows.
5. The route filters out vault and system-like addresses before display by cross-referencing the vault dataset and treasury-like addresses.

Observed interaction rules from the live bundle:
1. Default sort is `PnL` descending.
2. `Rank` is recomputed from the visible sorted result, not trusted from the raw payload.
3. Search matches both wallet address and display name.
4. Mobile keeps the connected user row pinned separately so it does not consume normal page results.
5. Display-name editing is gated to the current user row only.

Observed Hyperopen state:
1. `Leaderboard` already exists in header navigation and routes to `/leaderboard`.
2. Hyperopen does not currently have a leaderboard route module or dedicated leaderboard view.
3. Navigating to `/leaderboard` currently leaves the header on `Leaderboard` but falls back to trade-page content.

## Problem Statement

Hyperopen exposes `Leaderboard` in primary navigation, but the route is not active as a first-class surface. Users who click it do not get a ranking page, which breaks navigation trust and leaves a visible parity gap against Hyperliquid.

## Product Goals

1. Activate `/leaderboard` as a dedicated route with its own view.
2. Match the familiar Hyperliquid leaderboard information architecture instead of inventing a heavier analytics page.
3. Surface trader rankings across the same four windows: `Day`, `Week`, `Month`, and `All Time`.
4. Keep the page usable for a large dataset with search, sorting, pagination, and responsive behavior.
5. Preserve user identity affordances for the connected trader, including a pinned `YOU` row.

## Non-Goals (Phase 1)

1. Building a richer dashboard with hero metrics, charts, banners, or extra summary cards not present on the reference page.
2. New websocket feeds or streaming updates.
3. Native trader profile pages beyond the leaderboard itself.
4. Signed display-name mutation parity if the required account-signing path is not ready.
5. Prize or competition-season UI beyond the ranked table/card surface.

## Users

1. Active traders benchmarking performance against peers.
2. Connected users who want to quickly find their own standing.
3. Mobile users checking rank and recent performance windows without needing a full desktop table.

## Information Architecture

Primary sections in order:
1. Page title (`Leaderboard`).
2. Controls row:
   - search
   - time-range selector
3. Ranking surface:
   - desktop table
   - mobile card list
4. Methodology / formula note.

The page should remain intentionally compact. Do not add unrelated portfolio or trading modules to this route.

## Functional Requirements

### FR-1 Route and Navigation

1. `/leaderboard` MUST render a dedicated leaderboard page, not the trade layout.
2. Header nav MUST mark `Leaderboard` as active when the current path starts with `/leaderboard`.
3. Direct navigation, refresh, and browser back/forward MUST preserve leaderboard state safely.

### FR-2 Controls

1. The page MUST provide a search input above the ranking surface.
2. Search MUST match:
   - wallet address text
   - display name text when present
3. The page MUST provide a time-range selector with exactly:
   - `Day`
   - `Week`
   - `Month`
   - `All Time`
4. Changing the selected time range MUST update:
   - `PnL`
   - `ROI`
   - `Volume`
   - column/card labels that reference the active window

### FR-3 Desktop Ranking Table

1. Desktop MUST render a ranked table with these columns:
   - `Rank`
   - `Trader`
   - `Account Value`
   - `PnL`
   - `ROI`
   - `Volume`
2. `Account Value`, `PnL`, `ROI`, and `Volume` MUST be sortable.
3. `Rank` and `Trader` MAY remain non-sortable to match the reference behavior.
4. Default ordering MUST be `PnL` descending for the selected window.
5. Rank numbers MUST be recomputed after filtering and sorting.
6. The table MUST support pagination with a default page size of `10`.

### FR-4 Mobile Ranking Cards

1. Mobile and tablet MUST switch to stacked ranking cards instead of the dense desktop table.
2. Each card MUST show:
   - trader identity
   - rank
   - account value
   - `PnL` for the active window
   - `ROI` for the active window
   - `Volume` for the active window
3. Mobile MUST preserve pagination.
4. The connected user row MUST remain pinned above the normal card list when present.

### FR-5 Trader Identity and Current User Treatment

1. Trader identity MUST display the display name when present; otherwise it MUST fall back to an abbreviated wallet address.
2. If the connected wallet appears in leaderboard results, that row MUST be visually marked with a `YOU` badge.
3. The connected user row MUST be pinned to the top of the visible ranking surface.
4. Full parity target: the connected user row SHOULD expose a display-name edit affordance that opens a modal.
5. The parity modal behavior SHOULD match the reference:
   - current display name as placeholder when present
   - minimum length `3`
   - maximum length `20`
   - primary action to update
   - secondary action to reset back to wallet-address display when a custom name exists
6. Phase 1 MAY ship the current-user row read-only if the mutation/signing path is not yet available.

### FR-6 Row Selection and Drill-Down

1. Trader rows MUST provide a drill-down action to a trader-detail destination.
2. Phase 1 MAY use an external Hyperliquid address-explorer link while Hyperopen lacks a native trader-detail route.
3. Unavailable drill-down destinations MUST fail safely and MUST NOT break row rendering or pagination.

### FR-7 Data Filtering and Derivation Rules

1. The displayed leaderboard MUST exclude non-user accounts such as vault and treasury/system-like addresses.
2. Account value MUST be displayed independently of the selected performance window.
3. `PnL`, `ROI`, and `Volume` MUST come from the currently selected window.
4. Search and filtering MUST run before pagination.
5. The connected user row MUST NOT appear twice when it is pinned separately.

### FR-8 States

1. Loading MUST show a visible skeleton or placeholder state for the ranking surface.
2. Empty search results MUST render an explicit empty state.
3. Fetch failures MUST render an explicit error state with a retry path.
4. Missing display names or missing connected-wallet context MUST degrade gracefully.

### FR-9 Responsiveness and Accessibility

1. The controls row MUST remain usable at narrow mobile widths without clipped controls.
2. Table/card content MUST remain legible on desktop and mobile.
3. Sorting, pagination, and time-range selection MUST remain keyboard reachable.
4. Positive/negative PnL color MUST not be the sole semantic signal; sign and numeric value MUST remain explicit.

## Data Requirements

### DR-1 External Inputs

Phase 1 external inputs:
1. Leaderboard feed equivalent to Hyperliquid `Mainnet/leaderboard`.
2. Vaults or system-address metadata equivalent to Hyperliquid `Mainnet/vaults`.
3. Connected wallet address, when available.

### DR-2 Row Shape

Expected row fields:
1. `ethAddress`
2. `accountValue`
3. `windowPerformances` with `day`, `week`, `month`, and `allTime`
4. optional `displayName`
5. optional `prize` for future use

### DR-3 Derivations

1. Selected-window metrics are derived from `windowPerformances[selectedWindow]`.
2. Display rank is derived from the post-filter, post-sort visible order.
3. Display-name fallback is derived from:
   - custom display name when present
   - abbreviated wallet address otherwise

### DR-4 Fallback Policy

1. Missing numeric values MUST render deterministic placeholders instead of throwing.
2. Missing display names MUST fall back to abbreviated wallet addresses.
3. Missing connected-wallet context MUST suppress the `YOU` treatment without affecting the base leaderboard.

## UX Copy and Formatting

1. Page title MUST be `Leaderboard`.
2. Search copy SHOULD communicate wallet lookup; if Hyperopen broadens copy, it should still make display-name search discoverable.
3. Currency values SHOULD use standard USD formatting with separators, not compact shorthand.
4. ROI SHOULD render as a percentage.
5. PnL SHOULD show sign and semantic color.
6. Window-aware labels SHOULD include the selected window context, for example:
   - `PnL (Day)`
   - `ROI (Month)`
   - `Volume (All Time)`
7. The methodology note SHOULD explain how ranking values are computed and why displayed ranks can change across windows.

## Technical Constraints

1. Preserve existing public APIs unless explicitly expanded for leaderboard data.
2. Keep leaderboard ranking derivations deterministic in the client.
3. Avoid websocket-specific logic in the view layer for Phase 1.
4. Handle large datasets without visibly janky search, sorting, or pagination.
5. Reuse existing header/nav routing patterns where possible.

## Acceptance Criteria

### AC (Phase 1 delivery)

1. Navigating to `/leaderboard` renders a dedicated leaderboard page.
2. The page includes:
   - title
   - search
   - time-range selector
   - ranking surface
   - methodology note
3. The selected time range updates `PnL`, `ROI`, and `Volume`.
4. Search filters results by wallet address and display name.
5. Desktop renders a sortable paginated table.
6. Mobile renders paginated ranking cards.
7. If the connected wallet appears in results, it is pinned and marked `YOU`.
8. Loading, empty, and error states render explicitly and safely.

### AC (future parity phases)

1. Current-user display-name editing matches the reference modal behavior.
2. Trader row drill-down lands on a first-class trader-detail destination.
3. Any prize or season-specific metadata is surfaced only if product explicitly chooses to expose it.

## Risks and Mitigations

1. Risk: the raw leaderboard dataset is large enough to cause sluggish client-side interactions.
   - Mitigation: keep rendering dense but minimal, page aggressively, and measure search/sort cost before adding heavier UI.
2. Risk: incorrect vault/system-address filtering would pollute trader rankings.
   - Mitigation: codify exclusion rules and verify against the live Hyperliquid filtering behavior.
3. Risk: Hyperopen does not yet have a native trader-detail surface.
   - Mitigation: allow an external explorer fallback in Phase 1.
4. Risk: display-name editing requires signing or account capabilities not yet present on this route.
   - Mitigation: ship current-user pinning first and treat name mutation as a follow-up parity phase.

## Release Plan

1. Phase 1: activate `/leaderboard`, ship the read-only ranking surface, search, time windows, pagination, current-user pinning, and safe states.
2. Phase 2: add current-user display-name editing parity and improve trader drill-down behavior.
3. Phase 3: evaluate whether any prize, season, or competition framing should be surfaced beyond the current reference page.
