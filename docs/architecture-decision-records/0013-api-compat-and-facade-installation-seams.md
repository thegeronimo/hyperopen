# ADR 0013: API Compatibility and Facade Installation Seams

- Status: Accepted
- Date: 2026-02-13

## Context

After gateway extraction, `api.cljs` still owned projection-wiring compatibility behavior and an implicit process-wide service instance.

This left two issues:

- Compatibility fetch/ensure wrappers (store projections) were still embedded in the facade.
- Multi-environment or test-specific facade runtime replacement required direct namespace mutation.

## Decision

1. Introduce compatibility seam:
   - `/hyperopen/src/hyperopen/api/compat.cljs`
   - Owns store-mutating fetch/ensure projection wiring while delegating request logic to gateways.
2. Introduce explicit facade runtime installation seam in:
   - `/hyperopen/src/hyperopen/api.cljs`
   - `install-api-service!`
   - `configure-api-service!`
   - `reset-api-service!`

## Consequences

- `api.cljs` is thinner and focuses on public API compatibility.
- Compatibility projection logic is boundary-testable in isolation.
- Tests and multi-env wiring can replace facade runtime dependencies without monkey-patching private vars.

## Invariant Ownership

- Compatibility projection ownership:
  - `/hyperopen/src/hyperopen/api/compat.cljs`
  - `fetch-*` / `ensure-*` wrappers
- Active facade runtime service ownership:
  - `/hyperopen/src/hyperopen/api.cljs`
  - `install-api-service!`, `configure-api-service!`, `reset-api-service!`
