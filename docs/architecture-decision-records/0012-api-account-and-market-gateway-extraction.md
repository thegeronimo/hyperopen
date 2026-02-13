# ADR 0012: API Account and Market Gateway Extraction

- Status: Accepted
- Date: 2026-02-13

## Context

`/hyperopen/src/hyperopen/api.cljs` still owned most account and market façade orchestration:

- account request/fetch wrappers
- market request/fetch wrappers
- selector market-loader wiring

This kept `api.cljs` as a mixed-responsibility module instead of a thin compatibility seam.

## Decision

Extract account and market façade orchestration into dedicated gateway namespaces:

- `/hyperopen/src/hyperopen/api/gateway/account.cljs`
- `/hyperopen/src/hyperopen/api/gateway/market.cljs`

`api.cljs` now delegates these flows to gateway seams while preserving existing public function signatures.

## Consequences

- Bounded-context behavior is grouped by area (account, market, orders).
- New seams are directly boundary-testable without broad `api.cljs` setup.
- `api.cljs` remains backward-compatible and easier to retire incrementally.

## Invariant Ownership

- Funding history request-window normalization handoff:
  - `/hyperopen/src/hyperopen/api/gateway/account.cljs`
  - `fetch-user-funding-history!`
- Account abstraction normalization handoff:
  - `/hyperopen/src/hyperopen/api/gateway/account.cljs`
  - `fetch-user-abstraction!`
- Asset-selector market loader delegation:
  - `/hyperopen/src/hyperopen/api/gateway/market.cljs`
  - `request-asset-selector-markets!`
