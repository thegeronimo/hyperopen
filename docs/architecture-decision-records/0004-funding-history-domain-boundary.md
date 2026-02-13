# ADR 0004: Funding History Normalization Domain Boundary

## Status
Accepted

## Context
Funding history normalization, merge/sort semantics, and filter normalization lived in `src/hyperopen/api.cljs` with IO-oriented API fetch orchestration.
That mixed pure domain behavior with infrastructure concerns and made websocket/account-history projections depend on API internals.

## Decision
Introduce a dedicated pure domain module:
- `src/hyperopen/domain/funding_history.cljs`
  - funding row normalization for info/websocket payloads,
  - deterministic merge/sort semantics keyed by funding row id,
  - deterministic filter normalization and projection logic.

Keep `src/hyperopen/api.cljs` as a compatibility facade by delegating existing public funding helpers to the new domain module.

Use the domain module directly in consumers that only need pure behavior:
- `src/hyperopen/account/history/actions.cljs`
- `src/hyperopen/account/history/effects.cljs`
- `src/hyperopen/websocket/user.cljs`

## Consequences
- Domain logic is isolated from IO/retry/single-flight API orchestration.
- Funding invariants are now owned by one pure module and reused across REST + websocket flows.
- Existing public API call sites remain stable while new code can depend on the domain seam directly.
