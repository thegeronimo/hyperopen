# Order Form SOLID/DDD Slice 9 (Pricing Policy Unification, Projection Narrowing, Contracts, Callback-Agnostic Primitives, and View Decomposition)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this slice, the order form will have one shared pricing policy used by both display and transitions, narrower application projections so VM/selectors stop reading full app state directly, explicit contracts at the command-to-runtime boundary, callback-agnostic input primitives, and a decomposed view layout that is easier to maintain. Behavior should stay the same for end users, but the component boundary will be cleaner and less coupled.

## Progress

- [x] (2026-02-15 03:05Z) Created active ExecPlan for the five remaining SOLID/DDD improvements.
- [x] (2026-02-15 03:21Z) Added `trading/order-price-policy` and rewired transition focus/midpoint behavior to use shared policy.
- [x] (2026-02-15 03:29Z) Moved VM stateful pricing/scale derivations into application projection; VM/selectors now consume projected fields.
- [x] (2026-02-15 03:36Z) Added runtime gateway command/action contracts and expanded command parity tests.
- [x] (2026-02-15 03:44Z) Made order-form primitives callback-agnostic with dual binding support (plain fn + runtime event DSL).
- [x] (2026-02-15 03:52Z) Decomposed `order_form_view.cljs` into smaller private renderer helpers with behavior preserved.
- [x] (2026-02-15 03:58Z) Ran required validation gates: `npm run check`, `npm test`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Order-form pricing fallback logic already has two near-duplicate implementations (VM selector and transition focus path), making drift likely.
  Evidence: `/hyperopen/src/hyperopen/views/trade/order_form_vm_selectors.cljs` and `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`.
- Observation: callback-agnostic primitive support can be introduced safely without runtime migration by using a dual binder that emits either `:on` maps or native `:on-*` callback attrs.
  Evidence: `/hyperopen/src/hyperopen/views/trade/order_form_component_primitives.cljs` compiles and existing tests pass with unchanged handler call sites.

## Decision Log

- Decision: Keep this slice scoped to the order-form component boundary and immediate collaborators rather than broad runtime-wide event conventions.
  Rationale: The user requested refactoring this component specifically; a runtime-wide event rewrite is separate work.
  Date/Author: 2026-02-15 / Codex
- Decision: Keep pricing policy in `hyperopen.state.trading` instead of view/application namespaces.
  Rationale: transitions and VM both depend on this logic; state trading is the shared domain boundary available to both.
  Date/Author: 2026-02-15 / Codex
- Decision: Use contract assertions at the runtime gateway seam with allowed command IDs provided by gateway mapping.
  Rationale: avoids schema<->view circular dependencies while still enforcing explicit command/action shape guarantees.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

Implemented all five planned SOLID/DDD improvements for the order-form component. Pricing behavior is centralized in one policy function shared by transitions and VM composition, stateful VM selector dependencies were moved into application projection, gateway boundary contracts now assert command/action shapes, primitives support plain callbacks without breaking existing event DSL usage, and the order form view is decomposed into smaller rendering helpers. Required validation gates passed end-to-end.

## Context and Orientation

The order form spans presentation modules under `/hyperopen/src/hyperopen/views/trade/`, application composition in `/hyperopen/src/hyperopen/trading/order_form_application.cljs`, and domain/state rules in `/hyperopen/src/hyperopen/state/trading.cljs` and `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`.

A "pricing policy" in this plan means a single function that decides deterministic order-price behavior for the form (raw value, fallback value, display value, and midpoint metadata). The policy must be reused by both read-model composition (VM) and write-model transitions so price behavior cannot diverge.

A "projection" means a precomputed map prepared at the application boundary (`order-form-context`) so the VM renders from explicit inputs rather than re-reading full global state.

A "callback-agnostic primitive" means a UI primitive that can accept either plain callback functions (for generic reuse) or existing runtime event values (vector/event placeholder forms), without forcing one event transport shape.

## Plan of Work

Milestone 1 introduces a shared pricing policy function in trading state helpers and rewires transition/VM pricing call sites to consume that policy.

Milestone 2 moves remaining stateful selector computations (price display and scale preview lines) into application-layer projections so VM composition only consumes derived context data.

Milestone 3 adds command and runtime-action contracts around the runtime gateway seam, and introduces parity tests covering command builders against translation support.

Milestone 4 updates primitives to support both callback functions and existing runtime event DSL bindings, preserving current behavior while removing hard coupling.

Milestone 5 decomposes `order_form_view.cljs` into smaller private render helpers, reducing god-function pressure in the presentation layer.

Milestone 6 runs required checks and finalizes plan status.

## Concrete Steps

From `/hyperopen`:

1. Add shared pricing policy in `/hyperopen/src/hyperopen/state/trading.cljs` and use it from:
   - `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`
   - `/hyperopen/src/hyperopen/views/trade/order_form_vm_selectors.cljs`
2. Extend `/hyperopen/src/hyperopen/trading/order_form_application.cljs` to project pricing + scale-preview display values and update `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs` to consume projection fields.
3. Add command contracts module and wire gateway assertions:
   - new `/hyperopen/src/hyperopen/schema/order_form_command_contracts.cljs`
   - update `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs`
   - expand `/hyperopen/test/hyperopen/views/trade/order_form_commands_test.cljs`
4. Update `/hyperopen/src/hyperopen/views/trade/order_form_component_primitives.cljs` event binding to support callback functions and runtime event DSL.
5. Refactor `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` into small private section renderers.
6. Run required gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance criteria:

- A single trading-level pricing policy function drives both VM display price data and transition focus/mid behaviors.
- `order-form-vm` no longer calls selectors that require full `state`; stateful derivations occur in `order-form-context` projection.
- Runtime gateway asserts command and translated runtime-action contracts; unknown/invalid command shapes fail fast.
- Primitive widgets can consume plain callbacks without removing support for existing event DSL values.
- `order_form_view.cljs` is structurally decomposed into private rendering helpers with unchanged user-visible behavior.
- Required project validation gates pass.

## Idempotence and Recovery

All edits in this slice are additive/refactor-oriented and safe to re-run. If a partial step fails, rerun from the failing milestone after restoring compile green status. Contract assertions are internal and can be safely tightened iteratively while tests remain passing.

## Artifacts and Notes

Implemented files:

- `/hyperopen/src/hyperopen/state/trading.cljs`
- `/hyperopen/src/hyperopen/trading/order_form_application.cljs`
- `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`
- `/hyperopen/src/hyperopen/schema/order_form_command_contracts.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_vm_selectors.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_component_primitives.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`
- `/hyperopen/test/hyperopen/views/trade/order_form_commands_test.cljs`

## Interfaces and Dependencies

Expected post-slice interfaces:

- Trading pricing policy helper in `/hyperopen/src/hyperopen/state/trading.cljs` returning deterministic display/fallback context.
- Application context projection enriched with precomputed pricing + scale preview display maps.
- Order-form command contract assertions at runtime gateway boundary.
- Primitive event binding helper that accepts plain callback handlers and existing runtime action payloads.
- Order-form view composed from smaller private rendering functions.

Plan revision note: 2026-02-15 03:05Z - Initial plan authored for five-item SOLID/DDD slice.
Plan revision note: 2026-02-15 03:58Z - Marked milestones complete, recorded implementation decisions/discoveries, and captured validation outcomes.
