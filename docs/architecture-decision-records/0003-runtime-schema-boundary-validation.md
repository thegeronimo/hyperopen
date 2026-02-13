# ADR 0003: Dev-Time Schema Validation for Action/Effect and State Boundaries

## Status
Accepted

## Context
The application relies on event-driven action/effect pipelines and a nested map app-state model.
Without boundary validation, malformed payloads or invalid projection writes can propagate until a downstream view/runtime path fails.

This was especially visible at three boundaries:
- action dispatch payloads (`:actions/*`),
- emitted effect requests (`:effects/*`),
- anti-corruption boundaries for provider websocket and exchange payloads.

## Decision
Introduce centralized schema contracts and runtime validation wrappers:
- `src/hyperopen/schema/contracts.cljs`
  - canonical `cljs.spec` contracts for app-state invariants,
  - action/effect payload contracts,
  - websocket provider payload shape,
  - signed exchange payload shape.
- `src/hyperopen/runtime/validation.cljs`
  - wraps registered action/effect handlers in dev builds,
  - validates dispatch payloads before handler execution,
  - validates emitted effects before interpreter dispatch,
  - installs a dev-only store watch to validate every resulting state transition.

Runtime wiring now installs state validation through bootstrap (`runtime.bootstrap` -> `app.bootstrap`), and ACL/API boundaries validate inbound/outbound payload shapes in dev mode.

## Consequences
- Boundary violations fail fast with explicit schema context instead of delayed UI/runtime breakage.
- Validation ownership is explicit and centralized, reducing duplicated ad hoc guards.
- Production behavior remains unchanged because checks are dev-gated.
- New actions/effects can adopt stricter contracts incrementally while retaining arity validation coverage for all registered handlers.
