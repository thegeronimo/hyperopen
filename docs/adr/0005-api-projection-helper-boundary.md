# ADR 0005: API Store Projection Helper Boundary

## Status
Accepted

## Context
`src/hyperopen/api.cljs` contained sequential `swap!` writes for the same logical transitions in spot-meta, asset-selector, and spot-clearinghouse flows.
This made state transitions harder to reason about and violated the interaction/runtime rule to batch related writes into a single atomic update when intermediate states are not intentionally observable.

## Decision
Introduce a dedicated projection helper module:
- `src/hyperopen/api/projections.cljs`
  - pure state transition helpers for:
    - spot metadata load/success/error,
    - asset selector load/success/error,
    - spot balances load/success/error.

Update `src/hyperopen/api.cljs` to apply these helpers through single `swap!` transitions per logical branch.

## Consequences
- API projection behavior is centralized and pure.
- Sequential multi-write transitions in key hotspots are replaced by one atomic store transition.
- Projection semantics are now directly testable as boundary contracts without network IO.
