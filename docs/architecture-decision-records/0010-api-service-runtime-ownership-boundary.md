# ADR 0010: API Service Runtime Ownership Boundary

- Status: Accepted
- Date: 2026-02-13

## Context

`/hyperopen/src/hyperopen/api.cljs` still mixed façade exports with runtime ownership logic:

- request runtime stats/reset plumbing
- public webdata cache reads/writes
- ensure-perp single-flight tracking

This kept mutable runtime concerns in the façade namespace instead of behind a dedicated boundary.

## Decision

Introduce `/hyperopen/src/hyperopen/api/service.cljs` as the runtime ownership seam.

- `make-service` creates an instance with injected collaborators (`info-client`, clock, logger).
- service API owns runtime-scoped cache/flight behaviors:
  - `ensure-perp-dexs-data!`
  - `ensure-spot-meta-data!`
  - `ensure-public-webdata2!`
- `api.cljs` remains the compatibility façade and delegates runtime behavior to a single service instance.

## Consequences

- Runtime/cache/flight behavior is encapsulated behind one instance boundary.
- `api.cljs` becomes a thinner orchestration façade with fewer reasons to change.
- The service seam is directly testable without monkey-patching unrelated façade behavior.

## Invariant Ownership

- Request runtime reset ownership:
  - `/hyperopen/src/hyperopen/api/service.cljs`
  - `reset-service!`
- Perp DEX ensure single-flight ownership:
  - `/hyperopen/src/hyperopen/api/service.cljs`
  - `ensure-perp-dexs-data!`
- Public webdata snapshot cache ownership:
  - `/hyperopen/src/hyperopen/api/service.cljs`
  - `ensure-public-webdata2!`
