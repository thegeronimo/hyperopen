# ADR 0009: API Runtime Instance for Cache and Flight State

- Status: Accepted
- Date: 2026-02-13

## Context

`/hyperopen/src/hyperopen/api.cljs` retained top-level mutable cache/flight atoms for:

- `public-webdata2` snapshot cache
- `ensure-perp-dexs` in-flight single-flight tracking

This kept API runtime behavior process-global and hidden instead of being encapsulated behind an explicit runtime seam.

## Decision

Introduce an API runtime instance module:

- `/hyperopen/src/hyperopen/api/runtime.cljs`

`api.cljs` now holds a single runtime instance and routes cache/flight reads/writes through `api.runtime` accessors instead of directly owning separate top-level atoms.

## Consequences

- Cache and in-flight runtime state is explicitly grouped behind one boundary.
- `reset-request-runtime!` resets both transport client and API cache/flight runtime through explicit seam calls.
- Testability improves via direct runtime boundary tests.

## Invariant Ownership

- Public webdata cache ownership:
  - `/hyperopen/src/hyperopen/api/runtime.cljs`
  - `public-webdata2-cache`, `set-public-webdata2-cache!`
- Perp DEX single-flight ownership:
  - `/hyperopen/src/hyperopen/api/runtime.cljs`
  - `ensure-perp-dexs-flight`, `set-ensure-perp-dexs-flight!`, `clear-ensure-perp-dexs-flight-if-tracked!`
