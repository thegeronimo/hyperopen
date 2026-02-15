# Order Form SOLID/DDD Slice 6 (Registry-Driven View Controls, Unified Type Extensions, Application Service, Canonical Contracts, and Stateful Invariants)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

This slice completes five remaining architecture hardening tasks for the order form: remove remaining type-branching from the view, unify order-type extension points, move VM orchestration into an application service layer, promote VM/transition contracts to canonical schema specs, and add sequence-level transition invariants. After this change, adding or modifying an order type should require updating one extension surface with capability metadata and section wiring, while the view remains mostly declarative against VM control flags.

## Progress

- [x] (2026-02-15 23:40Z) Created active ExecPlan for items 1–5 and mapped concrete files to edit.
- [x] (2026-02-15 23:47Z) Replaced remaining `:type` branching in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` with VM `:controls` flags derived from order-type capabilities.
- [x] (2026-02-15 23:48Z) Added unified extension surface in `/hyperopen/src/hyperopen/views/trade/order_form_type_extensions.cljs` and rewired section rendering through it.
- [x] (2026-02-15 23:49Z) Extracted VM orchestration into `/hyperopen/src/hyperopen/trading/order_form_application.cljs` and simplified VM composition.
- [x] (2026-02-15 23:52Z) Promoted VM/transition contracts to canonical schema in `/hyperopen/src/hyperopen/schema/order_form_contracts.cljs`; trading contract namespace now delegates.
- [x] (2026-02-15 23:54Z) Added deterministic sequence-level transition invariants in `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`.
- [x] (2026-02-15 23:58Z) Ran required validation gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: The current order form already has clean pure transitions and handler-map decoupling; remaining work is mostly boundary tightening and extension-surface unification.
  Evidence: `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs` and `/hyperopen/src/hyperopen/views/trade/order_form_handlers.cljs` are already isolated from render layout concerns.
- Observation: `cljs.spec` unqualified key specs collide when a top-level and nested map share the same key name (for example `:display`), which broke the first version of VM schema validation.
  Evidence: `npm test` initially failed in `order-form-vm-schema-contracts-test` with `:price :display` being validated against the top-level display-map spec.
- Observation: Existing order-form view tests were stable after removing direct type-branching because controls map values preserved prior behavior.
  Evidence: `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` passed unchanged under full `npm test`.

## Decision Log

- Decision: Keep behavior parity while shifting control logic into registry-derived VM flags rather than introducing any new order flow behavior.
  Rationale: The user asked for refactor compliance improvements, not UX/functionality changes.
  Date/Author: 2026-02-15 / Codex
- Decision: Keep canonical VM/transition contracts in a dedicated schema namespace (`order_form_contracts.cljs`) instead of growing `/hyperopen/src/hyperopen/schema/contracts.cljs`.
  Rationale: Order-form contracts are domain-specific and easier to evolve when isolated while still living in the canonical schema layer.
  Date/Author: 2026-02-15 / Codex
- Decision: Implement deterministic pseudo-random transition simulations instead of non-deterministic random tests.
  Rationale: Sequence-level coverage improves while preserving repeatable CI outcomes.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

All five requested items were implemented. The view now relies on registry-driven control flags instead of direct order-type branching, section rendering is routed through a unified extension surface, VM orchestration is supplied by an application service context, canonical schema contracts now own VM/transition validation, and sequence-level transition invariants are covered by deterministic state-machine tests.

Validation gates passed with zero failures. The remaining SOLID/DDD work for this component is optional incremental hardening (for example widening invariant generators), not missing scope from this slice.

## Context and Orientation

Current order-form architecture is split across:

- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` for render layout.
- `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs` and helper modules for derived presentation state.
- `/hyperopen/src/hyperopen/trading/order_type_registry.cljs` for type metadata.
- `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs` for section renderers.
- `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs` for pure transitions.
- `/hyperopen/src/hyperopen/trading/order_form_contracts.cljs` for lightweight predicates currently used by tests.

The remaining DDD gap is that order-type behavior is still partially split between registry metadata and view conditionals, and canonical schema contracts do not yet own VM/transition shapes.

## Plan of Work

Milestone 1 will extend `order_type_registry` with explicit control capabilities and add VM `:controls` data so the view stops branching directly on order type.

Milestone 2 will create a unified order-type extension namespace that combines registry metadata and section renderer mapping, then rewire section rendering through this extension surface.

Milestone 3 will add an application service namespace to assemble normalized form context, summary, and submit policy before VM mapping.

Milestone 4 will define canonical schema specs for order-form VM and transition outputs in the schema layer and migrate contract checks to these specs.

Milestone 5 will add sequence-level deterministic transition simulations that assert invariants after each step.

Milestone 6 will run required validation gates and update this plan’s living sections.

## Concrete Steps

From `/hyperopen`:

1. Update `/hyperopen/src/hyperopen/trading/order_type_registry.cljs` with capability metadata and helper accessors.
2. Add `/hyperopen/src/hyperopen/views/trade/order_form_type_extensions.cljs` to unify order-type section rendering and metadata access.
3. Refactor `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs`, `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs`, and `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` to consume extension surface + controls.
4. Add `/hyperopen/src/hyperopen/trading/order_form_application.cljs` and rewire VM composition.
5. Add canonical schema contracts (new schema namespace) and migrate `/hyperopen/src/hyperopen/trading/order_form_contracts.cljs` to delegate.
6. Expand tests in:
   - `/hyperopen/test/hyperopen/views/trade/order_form_vm_test.cljs`
   - `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`
   - `/hyperopen/test/hyperopen/schema/contracts_test.cljs`
7. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

Expected transcript shape:

    npm run check
    ...
    Build completed. (... 0 warnings ...)

    npm test
    ...
    0 failures, 0 errors.

    npm run test:websocket
    ...
    0 failures, 0 errors.

## Validation and Acceptance

Acceptance criteria:

- View no longer branches directly on order type for TP/SL panel/toggle, scale preview, liquidation row, or slippage row.
- One extension surface owns order-type metadata + section renderer linkage.
- VM orchestration of summary/submit policy is sourced from application service context.
- Canonical schema specs validate order-form VM and transition outputs; lightweight contracts delegate to schema.
- Sequence-level transition tests verify invariants across deterministic multi-step action flows.
- Required validation gates pass.

## Idempotence and Recovery

All edits are additive/refactor-only. If a migration step regresses behavior, restore previous call site wiring while keeping new extension and schema modules in place, then rewire incrementally with tests. No data migration or destructive operations are involved.

## Artifacts and Notes

Validation outputs from `/hyperopen`:

    npm run check
    ...
    [:app] Build completed. (... 0 warnings ...)
    [:test] Build completed. (... 0 warnings ...)

    npm test
    ...
    Ran 821 tests containing 6376 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    ...
    Ran 86 tests containing 267 assertions.
    0 failures, 0 errors.

Key new files:

- `/hyperopen/src/hyperopen/views/trade/order_form_type_extensions.cljs`
- `/hyperopen/src/hyperopen/trading/order_form_application.cljs`
- `/hyperopen/src/hyperopen/schema/order_form_contracts.cljs`

## Interfaces and Dependencies

Required interfaces after completion:

- `/hyperopen/src/hyperopen/trading/order_type_registry.cljs`: capability-rich order-type metadata accessors.
- `/hyperopen/src/hyperopen/views/trade/order_form_type_extensions.cljs`: unified order-type extension surface with section renderer binding.
- `/hyperopen/src/hyperopen/trading/order_form_application.cljs`: application context orchestration for VM.
- `/hyperopen/src/hyperopen/schema/order_form_contracts.cljs` (or equivalent): canonical schema specs and validators for VM/transition shapes.

Plan revision note: 2026-02-15 23:40Z - Initial plan created for SOLID/DDD slice 6 (items 1–5).
Plan revision note: 2026-02-15 23:58Z - Updated living sections after full implementation, schema-contract correction, and passing required validation gates.
