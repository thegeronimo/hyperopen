---
owner: api-runtime
status: canonical
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Reliability and Runtime Invariants

## WebSocket Runtime Architecture Rules (MUST)
- MUST keep runtime decisions pure via `step(state, msg) -> {:state next-state :effects [...]}`.
- MUST keep canonical websocket runtime state single-writer: only the engine loop mutates it.
- MUST keep websocket projection ownership single-source: interpreters write one `runtime-view` projection atom.
- MUST treat websocket client canonical read API as `runtime-view` plus accessor functions in `/hyperopen/src/hyperopen/websocket/client.cljs` (`connected?`, `get-connection-status`, `get-runtime-metrics`, `get-tier-depths`, `get-health-snapshot`).
- MUST keep compatibility reads explicit and read-only through `/hyperopen/src/hyperopen/websocket/client_compat.cljs`; do not expose mutable compatibility projection atoms on the primary websocket client API.
- MUST execute input and output operations only through effect interpreters (transport, timers, lifecycle hooks, logging, routing, state projections).
- MUST NOT perform direct transport/timer/dom/log side effects inside reducers or domain message handling.
- MUST NOT use multi-loop shared mutable writes for connection/metrics runtime ownership.
- MUST preserve existing websocket public APIs in `/hyperopen/src/hyperopen/websocket/client.cljs` unless explicitly requested.
- MUST maintain message/effect algebra using `RuntimeMsg` and `RuntimeEffect` style contracts.

## Runtime Invariants
- Keep websocket runtime decisions pure.
- Keep canonical runtime state single-writer in engine loop.
- Represent side effects explicitly and execute them only in interpreters.
- Keep reducer logic deterministic and replay-safe.
- Keep startup/bootstrap/effect handlers idempotent and reentrant.
- Keep balance projection prerequisites hydrated: when `:spot :clearinghouse-state :balances` is present for non-USDC assets, `:spot :meta :tokens` MUST be present in the same runtime window.

## Startup Layering and Lifecycle Rules (MUST)
- Startup lifecycle orchestration entrypoint lives in `/hyperopen/src/hyperopen/app/startup.cljs`.
- Startup collaborator dependency assembly lives in `/hyperopen/src/hyperopen/startup/collaborators.cljs`.
- Startup behavior ownership lives in `/hyperopen/src/hyperopen/startup/init.cljs` and `/hyperopen/src/hyperopen/startup/runtime.cljs`.
- Runtime bootstrap/watcher installation ownership lives in `/hyperopen/src/hyperopen/app/bootstrap.cljs`, `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`, and `/hyperopen/src/hyperopen/startup/watchers.cljs`.
- Startup orchestration MUST avoid delegation-only pass-through wrappers between the startup facade and behavior owners.
- Startup integration tests MUST prefer collaborator/runtime dependency-injection seams over mutating startup facade vars.

## Account Data Hydration Rules (MUST)
- Canonical account-surface stream-policy ownership lives in `/hyperopen/src/hyperopen/account/surface_policy.cljs`.
- Canonical account-surface bootstrap and post-event fallback orchestration lives in `/hyperopen/src/hyperopen/account/surface_service.cljs`.
- Startup bootstrap, websocket user refresh, and order mutation refresh paths MUST delegate account-surface fallback behavior to that shared seam.
- Asset-selector and bootstrap loaders that fetch spot metadata MUST project it into `[:spot :meta]`; fetching without projection is considered a contract violation.
- Balance projections that depend on token metadata (for example contract ids/decimals) MUST treat `[:spot :meta :tokens]` as a required input and emit a debug invariant warning when balances are present but token metadata is empty.
- Changes to market-loader response shapes MUST include parity updates in all callers that apply projections (`runtime api effects` and `fetch compat` paths).

## core.async / Channel Best Practices (MUST)
- MUST define explicit channel roles and keep them stable:
- `mailbox`: runtime command/event ingress, lossless, bounded.
- `effects`: effect execution queue, bounded.
- `router-bus`: topic fanout for decoded envelopes.
- `metrics`: best-effort telemetry (dropping allowed).
- `dead-letter`: diagnostics/error sink (dropping allowed).
- MUST keep control and lossless domain flows lossless and bounded.
- MUST only use sliding or dropping buffers where loss policy is explicitly intended and documented.
- MUST define overflow behavior for bounded queues (drop policy, dead-letter, and metric/log signal).
- MUST isolate handlers behind dedicated channels/workers so slow handlers do not stall core runtime loops.
- MUST document tiering and backpressure rationale for market vs user/account/order flows.

## Purity and Input/Output Boundary Checklist
- [ ] Yes/No: reducer code has no `swap!`, `reset!`, `println`, JavaScript interop side effects, or `infra/*` input and output calls.
- [ ] Yes/No: all side effects are represented as `RuntimeEffect` values.
- [ ] Yes/No: effect interpreters are the only place where socket/timer/dom/log input and output executes.
- [ ] Yes/No: canonical runtime state has a single writer (engine loop).
- [ ] Yes/No: websocket runtime-view projection is applied as one explicit projection effect source.
- [ ] Yes/No: new websocket behaviors are introduced through message/effect algebra, not ad hoc direct calls.

## Interaction Responsiveness Rules
- User-visible close/save projection effects come before heavy subscription/fetch effects.
- One logical interaction should avoid duplicate side effects.
- Multi-write flows should use one atomic transition when intermediate states are not intentional.
- Effect-order authority for covered interaction actions lives in runtime validation contract enforcement:
  `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs` is enforced by
  `/hyperopen/src/hyperopen/runtime/validation.cljs`.
- New interaction-critical actions MUST opt into the centralized effect-order contract and add regression tests that prove projection-before-heavy ordering and no duplicate heavy effects.

## Order-Form Command Mapping Rules (MUST)
- Command-driven order-form mapping authority MUST live in one canonical catalog:
  `/hyperopen/src/hyperopen/schema/order_form_command_catalog.cljs`.
- The order-form runtime gateway and runtime action registration MUST derive command-driven order-form mappings from that catalog.
- DO NOT maintain duplicate command-id -> action-id tables in gateway and registry modules.

## Runtime Registration Catalog Rules (MUST)
- Runtime action/effect registration metadata authority MUST live in one canonical catalog:
  `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`.
- Runtime registry installation (`/hyperopen/src/hyperopen/registry/runtime.cljs`) MUST derive action/effect binding rows and registered ID sets from that catalog.
- Runtime registration composition (`/hyperopen/src/hyperopen/runtime/registry_composition.cljs`) MUST derive runtime action/effect handler key selection from that catalog.
- Runtime contract ID coverage (`/hyperopen/src/hyperopen/schema/contracts.cljs`) MUST derive contracted action/effect ID sets from that same catalog and fail fast on ID drift.
- DO NOT maintain duplicate action/effect ID binding tables across runtime registry, registry composition, and contract surfaces.

## Order-Form Legacy Key Policy Rules (MUST)
- Canonical order-form key ownership policy MUST live in one shared module:
  `/hyperopen/src/hyperopen/state/trading/order_form_key_policy.cljs`.
- Legacy order-form key compatibility support is allowed only at order-form read ingress normalization boundaries
  (for example `/hyperopen/src/hyperopen/state/trading.cljs` `normalize-order-form`).
- Canonical persisted `:order-form` state MUST NOT contain policy-defined deprecated keys; app-state contract validation MUST reject those keys.
- Transition write paths (for example `update-order-form`) MUST reject policy-defined UI-owned, runtime, and legacy key paths.
- Schema contracts, transition path guards, and persistence stripping MUST derive from the shared key policy module; do not maintain duplicate key lists.

## Anti-Patterns (DO NOT)
- DO NOT perform side effects inside reducer transitions.
- DO NOT allow multiple writers to mutate canonical runtime state.
- DO NOT add hidden synchronous fallback paths that bypass the channel model.
- DO NOT use unbounded queues for lossless flows.
- DO NOT bypass message/effect contracts with direct infrastructure calls from domain logic.
- DO NOT read deprecated websocket compatibility fields (`ws-client/connection-state`, `ws-client/stream-runtime`) from production code.
- DO NOT put business rules in effect interpreters, transport handlers, or UI callbacks.
- DO NOT leak raw exchange payload shapes directly into domain consumers without Anti-Corruption Layer mapping.
- DO NOT introduce new behavior via direct infrastructure calls that bypass runtime message/effect algebra.
- DO NOT mix domain decision logic and infrastructure input/output concerns in the same function/module.
- DO NOT modify stable interfaces for one-off behavior when existing extension points can satisfy the change.
- DO NOT couple high-level runtime logic directly to concrete browser/JS infrastructure primitives.
- DO NOT design broad protocols/handlers that force consumers to implement unused methods or accept unused arguments.
- DO NOT place UI-close/visibility effects after subscription or fetch effects in the same synchronous interaction pipeline.
- DO NOT write the same semantic state in multiple layers of one flow (action + effect) unless idempotency and intent are documented.
- DO NOT persist partial denormalized market projections without required identity/display fields (`:coin`, `:symbol`) when non-nil.
- DO NOT pass space-separated class strings to `:class` in Hiccup attrs.
- DO NOT use string keys in Hiccup `:style` maps; use keyword keys (including keyword CSS custom properties like `:--foo`).

## Change Workflow for Agents
- Read websocket runtime files before editing:
- `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
- `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs`
- `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`
- `/hyperopen/src/hyperopen/websocket/client.cljs`
- Validate Single Responsibility Principle, Open-Closed Principle, Liskov Substitution Principle, Interface Segregation Principle, and Dependency Inversion Principle impact before editing websocket runtime components; document intentional tradeoffs in PR notes.
- Add or adjust tests before finalizing large runtime behavior changes.
- When changing interaction flows (selector/modals/dropdowns), explicitly document intended effect order in PR notes.
- Require at least one targeted regression test that validates both responsiveness ordering and rendering fallback behavior.
- Keep compatibility adapter behavior explicit and documented.
- Document any intentional invariant deviations in PR notes.

## Mini-Template: Add a New WebSocket Event
- Define or update the domain concept/invariant and ubiquitous term first.
- Validate the intended extension seam (Open-Closed Principle) and responsibility split (Single Responsibility Principle) before adding message/effect branches.
- Add a `RuntimeMsg` variant in domain model (constructor/predicate coverage).
- Extend reducer `step` with pure state transition and emitted effects.
- Add or extend interpreter handling for any new effect type.
- Follow the RuntimeMsg -> reducer -> interpreter -> tests sequence.
- Add tests for:
- reducer branch determinism,
- expected effect emission,
- integration path through engine + interpreter.

## Required Reliability Tests
- Reducer determinism.
- Single-writer runtime ownership.
- FIFO/replay correctness under reconnect.
- Coalescing behavior under burst traffic.
- Lossless ordering for account and order streams.
- Effect-order and no-duplicate-effects regressions for critical interaction flows.
