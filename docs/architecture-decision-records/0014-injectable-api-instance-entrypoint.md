# ADR 0014: Injectable API Instance Entrypoint

- Status: Accepted
- Date: 2026-02-13

## Context

`/hyperopen/src/hyperopen/api.cljs` still relied on process-global facade state for active service ownership.
Even with reset/configure helpers, this remained a global mutable singleton boundary.

## Decision

Add injectable API instance entrypoint:

- `/hyperopen/src/hyperopen/api.cljs`
- `make-api`

`make-api` accepts injected collaborators (`:service`, optional `:now-ms-fn`, optional `:log-fn`) and returns a map of API functions bound to that instance.

The existing global facade remains for legacy call sites, but new code can depend on an injected API map instead of module-global state.

## Consequences

- Multiple API instances can coexist in one runtime (tests, multi-env composition, isolated subsystems).
- Migration away from global facade can proceed incrementally by passing instance maps through composition roots.
- Backward compatibility is preserved for existing `hyperopen.api/*` call sites.
- `/hyperopen/src/hyperopen/startup/collaborators.cljs` now accepts optional injected `:api` ops in `startup-base-deps` and prefers those over global facade functions.

## Invariant Ownership

- Instance-bound request runtime ownership:
  - `/hyperopen/src/hyperopen/api.cljs`
  - `make-api` returned `:get-request-stats` and `:reset-request-runtime!`
- Instance-bound request gateway ownership:
  - `/hyperopen/src/hyperopen/api.cljs`
  - `make-api` returned `:request-*` and `:ensure-*` operations
