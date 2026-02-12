# ADR 0002: Separate Entrypoint, System Ownership, and Startup/Runtime Wiring

## Status
Accepted

## Context
`hyperopen.core` still carried multiple responsibilities after ADR 0001:
- system/default state ownership,
- runtime bootstrap wiring,
- startup composition wiring.

This kept the entrypoint namespace large and increased change risk when touching startup/runtime code.

## Decision
Introduce explicit boundaries:
- `src/hyperopen/system.cljs`: default app state + global store/runtime ownership.
- `src/hyperopen/app/actions.cljs`: runtime action dependency wiring.
- `src/hyperopen/app/effects.cljs`: runtime effect dependency wiring.
- `src/hyperopen/app/bootstrap.cljs`: runtime bootstrap, render wiring, reload behavior.
- `src/hyperopen/app/startup.cljs`: startup lifecycle wiring and remote stream initialization.
- `src/hyperopen/core.cljs`: entrypoint-only orchestration (`init`, `reload`, `make-system`, `store`).

`src/hyperopen/runtime/wiring.cljs` now composes action/effect dependencies through the new `hyperopen.app.*` seams.

## Consequences
- `hyperopen.core` is a thin entrypoint and no longer owns startup/runtime implementation details.
- system state ownership is centralized and reusable outside entrypoint tests.
- startup/runtime wiring can evolve independently with smaller local reasoning surfaces.
- public entrypoint API remains stable (`hyperopen.core/init`, `hyperopen.core/reload`).
