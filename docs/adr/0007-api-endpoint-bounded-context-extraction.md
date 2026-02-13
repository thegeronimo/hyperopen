# ADR 0007: API Endpoint Bounded-Context Extraction

- Status: Accepted
- Date: 2026-02-13

## Context

`/hyperopen/src/hyperopen/api.cljs` had accumulated request-shape construction and response normalization for multiple bounded contexts:

- market endpoints (`metaAndAssetCtxs`, `perpDexs`, `candleSnapshot`, `spotMeta`, `webData2`)
- account endpoints (`userFunding`, `spotClearinghouseState`, `userAbstraction`, `clearinghouseState`)

This mixed endpoint concerns with facade compatibility wrappers and projection orchestration, increasing file size and reducing responsibility clarity.

## Decision

Extract request boundary logic into dedicated endpoint modules:

- `/hyperopen/src/hyperopen/api/endpoints/market.cljs`
- `/hyperopen/src/hyperopen/api/endpoints/account.cljs`

Keep `/hyperopen/src/hyperopen/api.cljs` as the stable public facade and compatibility seam. Existing public function names and call shapes remain unchanged.

## Consequences

- Endpoint request payload rules and normalization are isolated per bounded context.
- `api.cljs` remains the compatibility facade for existing callers while shrinking direct endpoint logic.
- Boundary tests now target endpoint modules directly:
  - `/hyperopen/test/hyperopen/api/endpoints/market_test.cljs`
  - `/hyperopen/test/hyperopen/api/endpoints/account_test.cljs`

## Invariant Ownership

- Funding pagination/order invariant owner:
  - `/hyperopen/src/hyperopen/api/endpoints/account.cljs`
  - `request-user-funding-history!`
- Market request-shape and dedupe-key invariant owner:
  - `/hyperopen/src/hyperopen/api/endpoints/market.cljs`
  - `request-meta-and-asset-ctxs!`, `request-candle-snapshot!`, `request-perp-dexs!`
