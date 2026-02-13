# ADR 0015: Funding Domain Exports Removed from API Facade

- Status: Accepted
- Date: 2026-02-13

## Context

`/hyperopen/src/hyperopen/api.cljs` re-exported funding-history domain functions (normalization/merge/filter helpers).
This blurred layer boundaries by teaching consumers to import domain behavior from the infrastructure API facade.

## Decision

1. Remove funding-history helper exports from:
   - `/hyperopen/src/hyperopen/api.cljs`
2. Route internal API funding behavior directly to:
   - `/hyperopen/src/hyperopen/domain/funding_history.cljs`
3. Add compatibility namespace for legacy callers:
   - `/hyperopen/src/hyperopen/api/legacy.cljs`

## Consequences

- Clearer import semantics: domain behavior comes from domain namespaces.
- Reduced ambiguity for contributors and agents when choosing import boundaries.
- Backward-compatibility path exists without keeping domain exports on the primary API facade.

## Invariant Ownership

- Funding normalization/merge/filter ownership:
  - `/hyperopen/src/hyperopen/domain/funding_history.cljs`
- Legacy compatibility wrappers:
  - `/hyperopen/src/hyperopen/api/legacy.cljs`
