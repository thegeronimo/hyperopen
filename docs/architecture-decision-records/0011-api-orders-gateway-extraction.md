# ADR 0011: API Orders Gateway Extraction

- Status: Accepted
- Date: 2026-02-13

## Context

`/hyperopen/src/hyperopen/api.cljs` still contained order-focused façade behavior mixed with other bounded contexts:

- open orders request/fetch wrappers
- user fills request/fetch wrappers
- historical orders request/fetch wrappers

This increased mixed responsibility in the API façade and made order flow evolution harder to isolate.

## Decision

Extract order façade behavior into:

- `/hyperopen/src/hyperopen/api/gateway/orders.cljs`

The new gateway owns order-specific request/fetch orchestration and option normalization, while `api.cljs` delegates and preserves existing public function signatures.

## Consequences

- Order behavior now has a dedicated module boundary for testing and future extension.
- `api.cljs` remains backward-compatible but delegates order logic instead of owning it directly.
- Boundary tests now cover gateway contracts independently.

## Invariant Ownership

- Frontend open orders option normalization ownership:
  - `/hyperopen/src/hyperopen/api/gateway/orders.cljs`
  - `request-frontend-open-orders!`
- Historical order normalization wrapper ownership:
  - `/hyperopen/src/hyperopen/api/gateway/orders.cljs`
  - `fetch-historical-orders!`
