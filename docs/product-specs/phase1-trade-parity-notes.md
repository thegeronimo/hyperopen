---
owner: product
status: supporting
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Phase 1 Trade Parity Notes

## App UI Inspection Status
- Attempted to inspect `app.hyperliquid.xyz` via automated browsing, but the site is a client-side app that did not render usable DOM content in the inspection environment.
- Action: Using official Hyperliquid documentation to define order types, options, and API payloads. App UI parity should be validated manually in a browser.

## Order Types and Options (Docs)
Source: Hyperliquid order types docs.
- Order types: Market, Limit, Stop Market, Stop Limit, Take Market, Take Limit, Scale, TWAP.
- Order options: Reduce Only, GTC/IOC/ALO TIF, Take Profit, Stop Loss.
- TWAP behavior: 30s suborders, max 3% slippage, catch-up logic.

Ref: https://hyperliquid.gitbook.io/hyperliquid-docs/trading/order-types

## Exchange Endpoint Payloads
Source: Hyperliquid exchange endpoint docs.
- Place order action:
  - `action.type = "order"`
  - `orders[]` with keys: `a` (asset index), `b` (isBuy), `p` (price), `s` (size), `r` (reduceOnly), `t` (limit/trigger), `c` (cloid optional)
  - `t.limit.tif` in {"Alo","Ioc","Gtc"}
  - `t.trigger` with `isMarket`, `triggerPx`, `tpsl` ("tp"|"sl")
  - `grouping` in {"na","normalTpsl","positionTpsl"}
- Cancel action:
  - `action.type = "cancel"`
  - `cancels[]` with keys: `a` (asset), `o` (order id)
- TWAP order action:
  - `action.type = "twapOrder"`
  - `twap` fields: `a` (asset), `b` (isBuy), `s` (size), `r` (reduceOnly), `m` (minutes), `t` (randomize)

Ref: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint

## Info Endpoint Data (Open Orders & Fills)
Source: Hyperliquid info endpoint docs.
- Open orders with frontend fields: `type = "frontendOpenOrders"`.
- User fills: `type = "userFills"` (supports `aggregateByTime`).

Ref: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint

## WebSocket Subscriptions (User Data)
Source: Hyperliquid websocket subscriptions docs.
- User data feeds: `openOrders`, `userFills`, `userFundings`, `userNonFundingLedgerUpdates`, `webData2`.
- Market data feeds: `l2Book`, `activeAssetCtx`, `candle`, `bbo`.

Ref: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/websocket/subscriptions

## L1 Action Signing (Phantom Agent)
Source: Chainstack Hyperliquid signing guide.
- L1 action signing uses phantom agent construction:
  - Msgpack serialize action
  - Append vault address (or 20-byte zero) and nonce
  - keccak256 hash => connectionId
  - EIP-712 domain: name "Exchange", version "1", chainId 1337, verifyingContract 0x0
  - Types: Agent { source: string, connectionId: bytes32 }
- Use `source = "a"` for mainnet.

Ref: https://docs.chainstack.com/docs/hyperliquid-l1-action-signing
Ref: https://docs.chainstack.com/docs/hyperliquid-authentication-guide
