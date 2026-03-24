---
owner: product
status: supporting
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Hyperopen Parity Roadmap vs app.hyperliquid.xyz

## Scope and Sources
This roadmap compares the current Hyperopen repo to the Hyperliquid web app based on publicly available Hyperliquid Docs references to app pages and workflows. Items that appear in the app UI but are not documented are flagged as "Needs app verification."

Sources used (docs pages that reference app features/pages):
- How to start trading (wallet or email login). citeturn0search1
- Vaults (depositors and leaders workflows, app pages). citeturn0search2turn0search7
- Referrals (referral page and referral links). citeturn0search0
- Staking (staking account/delegation concepts). citeturn0search3
- Portfolio page references (link staking and portfolio balance across vaults). citeturn0search3turn0search2
- Trade page entry point (official app URL). citeturn0search6

## Current Implementation (Repo Evidence Summary)
Implemented UI or plumbing visible in the repository:
- Core app shell and header nav with Trade/Vaults/Portfolio/Staking/Referrals/Leaderboard and wallet connect UI.
- Active asset panel with asset selector, search/sort, and market stats.
- L2 order book view with spread calculation and cumulative totals.
- Trading chart view with timeframe selection, chart type, and indicators UI.
- Account info panel with tabs (balances, positions, open orders, TWAP, trade history, funding history, order history) and table layouts.
- WebSocket client with subscriptions for active asset context, order book, and WebData2.
- REST calls for asset contexts and candle snapshots.

## Parity Target (App Features Referenced in Docs)
High-level app areas and workflows referenced in docs:
- Trade page entry point at `app.hyperliquid.xyz/trade`. citeturn0search6
- Login via EVM wallet or email login on the app. citeturn0search2
- Vaults page with vault listings and vault detail pages, deposit/withdraw workflows. citeturn0search2
- Vault leader workflows (create/manage/close vault, trade on behalf of vault via address selector). citeturn0search1
- Referrals page for creating/entering referral codes and referral links. citeturn0search0
- Staking account and delegation concepts (HYPE staking). citeturn0search3
- Portfolio page used for linking staking and viewing total vault balance. citeturn0search3turn0search2

## Comparison Matrix (What Exists vs. What’s Needed)
Status legend: Implemented, Partial, Missing, Needs app verification.

| Area | Feature (Doc/App Reference) | Hyperopen Status | Notes |
| --- | --- | --- | --- |
| Trade | App entry point `app.hyperliquid.xyz/trade` | Missing | No route/page for trade navigation beyond UI shell. citeturn0search6 |
| Trade | Login via email | Missing | Wallet connect exists; email login flow not present. citeturn0search2 |
| Trade | Wallet login (EVM) | Partial | EVM connect exists; no deposit/withdraw flow or account funding UX. citeturn0search2 |
| Trade | Live market data (order book, asset ctx, candles) | Partial | WebSocket + REST exist; needs integration with full trade UX. |
| Trade | Order entry (market/limit/etc.) | Missing | UI and actions not implemented; verify exact order types in app. |
| Trade | Open orders / order history / trade history | Partial | Tabs exist; no data wiring or actions. |
| Vaults | Vault listing page with stats | Missing | Not implemented. citeturn0search2 |
| Vaults | Vault detail page (pnl, drawdown, positions) | Missing | Not implemented. citeturn0search2 |
| Vaults | Deposit/withdraw flow | Missing | Not implemented. citeturn0search2 |
| Vaults | Vault leader flows (create/manage/close) | Missing | Not implemented. citeturn0search1 |
| Vaults | Trade on behalf of vault via address selector | Missing | Not implemented. citeturn0search1 |
| Portfolio | Portfolio page (vault totals) | Missing | Not implemented. citeturn0search2 |
| Portfolio | Link staking from Portfolio page | Missing | Not implemented. citeturn0search3 |
| Referrals | Referral page for create/enter code | Missing | Not implemented. citeturn0search0 |
| Referrals | Referral link generation | Missing | Not implemented. citeturn0search0 |
| Staking | Staking account and delegation UI | Missing | Not implemented. citeturn0search3 |
| Leaderboard | Leaderboard page | Supporting spec exists | See `/hyperopen/docs/product-specs/leaderboard-page-parity-prd.md` for the live app parity contract captured on March 24, 2026. |

## Roadmap (Phased Delivery)

### Phase 1: Core Trade Experience (Parity Baseline)
Goal: Make the Trade page usable end-to-end for a connected user.
- Implement routing and navigation for Trade page entry (`/trade`). citeturn0search6
- Add email login flow alongside EVM wallet connect. citeturn0search2
- Implement deposit/funding entry points after login (UI + flow). citeturn0search2
- Wire live market data to views already present (asset ctx, candles, order book) and ensure selection drives subscriptions.
- Build order entry UI and actions (order types to be verified against app).
- Implement positions, open orders, order history, and trade history data wiring for existing tabs.

### Phase 2: Vaults (Depositor and Leader Parity)
Goal: Enable vault discovery, deposits, and leader management.
- Vaults list with APY/TVL and basic stats. citeturn0search2
- Vault detail page with performance, open positions, and trade history. citeturn0search2
- Deposit and withdraw flows. citeturn0search2
- Vault creation flow and leader actions (manage/close). citeturn0search1
- Account context switch to trade on behalf of a vault. citeturn0search1

### Phase 3: Portfolio & Staking Link
Goal: Portfolio parity for balances and staking linkage.
- Portfolio page showing total balance across vaults. citeturn0search2
- Link staking workflow from Portfolio page. citeturn0search3

### Phase 4: Staking Experience
Goal: Provide basic staking account and delegation UX.
- Staking account view and delegation actions. citeturn0search3
- Validator list, delegation amounts, rewards display (details may require app verification).

### Phase 5: Referrals
Goal: Referral creation and usage parity.
- Referrals page to create and enter codes. citeturn0search0
- Referral link generation and tracking UI. citeturn0search0

### Phase 6: App-Only UI Parity (Verify in app)
Goal: Close gaps that are visible in app but not documented.
- Leaderboard page parity now documented in `/hyperopen/docs/product-specs/leaderboard-page-parity-prd.md`.
- Any additional trade-side panels (news, notifications, etc.) that aren’t documented.

## Open Questions / Needs App Verification
- Exact order types and order-entry behavior on `app.hyperliquid.xyz`.
- Any additional tabs or modals present in the Trade UI beyond those referenced in docs.
