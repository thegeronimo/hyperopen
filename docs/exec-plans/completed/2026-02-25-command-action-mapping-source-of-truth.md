# Command/Action Mapping Source Of Truth (Remove Dual Mapping Paths)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, order-form semantic command IDs and runtime action IDs will have one canonical catalog instead of two independently maintained mapping paths. The order-form runtime gateway and runtime action registration will both consume that same catalog, so adding or changing a command/action mapping will happen in one place.

A contributor can verify the result by running tests that prove: command builders still translate correctly, every catalog action is runtime-registered, and drift is detected immediately when one path is changed without the other.

## Progress

- [x] (2026-02-25 20:58Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, and `/hyperopen/docs/RELIABILITY.md` for single-source and boundary rules.
- [x] (2026-02-25 20:58Z) Audited dual mapping surfaces in `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs` and `/hyperopen/src/hyperopen/registry/runtime.cljs`.
- [x] (2026-02-25 20:58Z) Audited neighboring runtime wiring maps in `/hyperopen/src/hyperopen/runtime/collaborators.cljs` and `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`.
- [x] (2026-02-25 20:58Z) Audited current coverage in `/hyperopen/test/hyperopen/views/trade/order_form_runtime_gateway_test.cljs`, `/hyperopen/test/hyperopen/views/trade/order_form_commands_test.cljs`, and `/hyperopen/test/hyperopen/schema/contracts_coverage_test.cljs`.
- [x] (2026-02-25 20:58Z) Authored initial ExecPlan with canonical catalog design, migration milestones, and validation gates.
- [x] (2026-02-25 21:06Z) Implemented Milestone 1 by adding `/hyperopen/src/hyperopen/schema/order_form_command_catalog.cljs` and drift guard tests in `/hyperopen/test/hyperopen/schema/order_form_command_catalog_test.cljs` and existing order-form test suites.
- [x] (2026-02-25 21:06Z) Implemented Milestone 2 by migrating `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs` to derive command support and command->action translation from the catalog.
- [x] (2026-02-25 21:07Z) Implemented Milestone 3 by migrating `/hyperopen/src/hyperopen/registry/runtime.cljs` to derive command-driven order-form runtime action bindings from the catalog while keeping non-command actions explicit.
- [x] (2026-02-25 21:09Z) Implemented Milestone 4 by updating `/hyperopen/docs/RELIABILITY.md`, adding ADR `/hyperopen/docs/architecture-decision-records/0019-command-action-catalog-authority.md`, and passing required gates (`npm run check`, `npm test`, `npm run test:websocket`).

## Surprises & Discoveries

- Observation: order-form command translation currently owns a private command->action map in the gateway, while runtime registration separately owns action bindings for the same order-form actions.
  Evidence: `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs` `action-id-by-command-id` and `/hyperopen/src/hyperopen/registry/runtime.cljs` `action-bindings`.

- Observation: command contract validation currently relies on allowed command IDs passed from the gateway map, so command coverage authority is indirectly tied to gateway internals.
  Evidence: `/hyperopen/src/hyperopen/schema/order_form_command_contracts.cljs` `assert-order-form-command!` receives `allowed-command-ids` from caller; gateway passes `supported-command-id-set`.

- Observation: existing tests verify gateway behavior and registry/contracts alignment independently, but no test currently asserts a shared command/action source of truth across gateway and runtime registration.
  Evidence: `/hyperopen/test/hyperopen/views/trade/order_form_runtime_gateway_test.cljs` and `/hyperopen/test/hyperopen/schema/contracts_coverage_test.cljs`.

- Observation: order action handler dependency maps in collaborators/registry composition are separate concerns (action-key -> function wiring), but they do not solve command/action mapping drift.
  Evidence: `/hyperopen/src/hyperopen/runtime/collaborators.cljs` `order-action-deps` and `/hyperopen/src/hyperopen/runtime/registry_composition.cljs` `order-action-handlers`.

- Observation: Runtime registration migration can stay non-disruptive by concatenating catalog-derived command-driven bindings between existing non-order and explicit non-command order bindings.
  Evidence: `/hyperopen/src/hyperopen/registry/runtime.cljs` now composes `action-bindings` with `(order-form-command-catalog/runtime-action-bindings)` plus explicit `:actions/cancel-order`, `:actions/load-user-data`, and `:actions/set-funding-modal`.

- Observation: Duplicate command/action IDs can be guarded at module load time in addition to tests, giving earlier failure signals during development.
  Evidence: `/hyperopen/src/hyperopen/schema/order_form_command_catalog.cljs` enforces uniqueness via `assert-unique-entry-values!` for `:command-id` and `:action-id`.

## Decision Log

- Decision: Introduce one canonical order-form command catalog in a stable, pure namespace and make both gateway translation and runtime registration consume it.
  Rationale: This removes dual mapping ownership without introducing view-to-runtime dependency direction violations.
  Date/Author: 2026-02-25 / Codex

- Decision: Store catalog entries as explicit maps with `:command-id`, `:action-id`, and `:handler-key` instead of relying on naming conventions.
  Rationale: Explicit entries keep intent clear, avoid fragile implicit derivation, and support future exceptions without redesign.
  Date/Author: 2026-02-25 / Codex

- Decision: Keep public gateway API stable (`OrderFormRuntimeGateway`, `default-gateway`, `supported-command-ids`) while changing internal source ownership.
  Rationale: Existing order-form view/handler call sites should remain unchanged per architecture guardrails.
  Date/Author: 2026-02-25 / Codex

- Decision: Add bidirectional drift guards in tests (catalog -> runtime registration and command builders -> catalog support) before removing old maps.
  Rationale: Failing tests first make migration safe and prove the ownership contract.
  Date/Author: 2026-02-25 / Codex

- Decision: Treat this as an architecture-affecting ownership contract and document it in reliability guidance (and ADR if needed by final scope).
  Rationale: Single-source mapping ownership is a long-lived invariant and should be durable in docs, not only code.
  Date/Author: 2026-02-25 / Codex

- Decision: Keep non-command order actions out of catalog scope and explicitly registered in runtime registry.
  Rationale: The catalog is scoped to command-driven order-form flows; explicit registration keeps the boundary precise and avoids overloading catalog responsibility.
  Date/Author: 2026-02-25 / Codex

- Decision: Add module-level duplicate guards in the catalog, not test-only uniqueness checks.
  Rationale: Failing fast at load-time prevents subtle drift from progressing to runtime integration paths.
  Date/Author: 2026-02-25 / Codex

- Decision: Add ADR 0019 to record ownership and extension rules.
  Rationale: This change formalizes a long-lived mapping authority invariant across view and runtime boundaries.
  Date/Author: 2026-02-25 / Codex

## Outcomes & Retrospective

Implemented end to end. Command-driven order-form mapping ownership now lives in one catalog namespace and both translation and registration paths consume it.

Delivered outcomes:

- Added canonical catalog `/hyperopen/src/hyperopen/schema/order_form_command_catalog.cljs` with explicit `:command-id`, `:action-id`, and `:handler-key` entries plus lookup/query helpers.
- Migrated `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs` to derive both translation and `supported-command-ids` from that catalog.
- Migrated `/hyperopen/src/hyperopen/registry/runtime.cljs` to derive command-driven order-form action bindings from catalog rows.
- Added drift coverage in `/hyperopen/test/hyperopen/schema/order_form_command_catalog_test.cljs` and updated order-form suites so builder and gateway expectations are catalog-backed.
- Updated reliability governance (`/hyperopen/docs/RELIABILITY.md`) and recorded architecture ownership in ADR `/hyperopen/docs/architecture-decision-records/0019-command-action-catalog-authority.md`.

Validation outcome:

- `npm run check` passed.
- `npm test` passed (`1358` tests, `6591` assertions).
- `npm run test:websocket` passed (`148` tests, `644` assertions).

## Context and Orientation

In this repository, order-form interaction handlers build semantic commands, then an adapter/gateway translates those commands into runtime action vectors.

The semantic command layer lives in `/hyperopen/src/hyperopen/views/trade/order_form_commands.cljs`. Each command has a `:command-id` and `:args`. The adapter in `/hyperopen/src/hyperopen/views/trade/order_form_intent_adapter.cljs` delegates translation to `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs`, which now resolves command IDs through `/hyperopen/src/hyperopen/schema/order_form_command_catalog.cljs`.

Runtime action registration is configured in `/hyperopen/src/hyperopen/registry/runtime.cljs` using `action-bindings`, a vector of `[action-id handler-key]` pairs. Command-driven order-form action IDs are now catalog-derived in that vector, while non-command order actions remain explicitly listed.

For this plan:

- A "command ID" means the semantic order-form keyword such as `:order-form/update-order-form`.
- An "action ID" means the runtime dispatch keyword such as `:actions/update-order-form`.
- A "handler key" means the unnamespaced keyword used by runtime registration to look up the injected action function, such as `:update-order-form`.
- The "canonical catalog" means one pure data module that authoritatively lists command/action/handler mappings for command-driven order-form interactions.

## Plan of Work

### Milestone 1: Create Canonical Catalog And Lock Drift With Tests

Add a new pure namespace (for example `/hyperopen/src/hyperopen/schema/order_form_command_catalog.cljs`) containing ordered entries for command-driven order-form interactions:

- `:command-id`
- `:action-id`
- `:handler-key`

Expose narrow query functions from this module, such as command ID set, command->action lookup, and runtime binding rows. Add tests that fail if:

- command builders emit a command ID absent from the catalog,
- a catalog action ID is missing from runtime registration,
- duplicate command IDs or action IDs appear in catalog entries.

This milestone should land tests first where practical, then catalog module scaffolding.

### Milestone 2: Move Gateway And Command-Contract Ownership To Catalog

Refactor `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs` to remove private `action-id-by-command-id` ownership and use catalog lookups for translation and supported command IDs.

Keep command contract assertions in `/hyperopen/src/hyperopen/schema/order_form_command_contracts.cljs` and pass catalog-supported IDs to `assert-order-form-command!` from gateway call sites. Preserve current gateway public API and behavior, including placeholder resolution and error shape.

Update/extend gateway tests so they assert behavior through catalog-derived mappings only.

### Milestone 3: Move Runtime Registration Order-Form Bindings To Catalog

Refactor `/hyperopen/src/hyperopen/registry/runtime.cljs` so order-form action bindings are generated from the same catalog entries used by the gateway. Keep non-command order actions (`:actions/cancel-order`, `:actions/load-user-data`, `:actions/set-funding-modal`) explicitly defined in runtime registry unless they are intentionally brought into catalog scope.

Retain stable registration entrypoints (`registered-action-ids`, `register-actions!`) and runtime validation wrapping behavior. Add regression coverage that proves every catalog runtime binding is present in `registered-action-ids`.

### Milestone 4: Cleanup, Documentation, And Final Validation

Remove now-dead mapping code paths and redundant constants. Update reliability documentation to explicitly state that command/action translation authority for command-driven order-form interactions lives in the canonical catalog. If architecture scope expands beyond a local refactor, add ADR `0019` describing ownership and extension rules.

Run all required validation gates and capture evidence in this plan.

## Concrete Steps

From `/hyperopen`:

1. Add catalog module and failing drift tests.

   - Create `/hyperopen/src/hyperopen/schema/order_form_command_catalog.cljs`.
   - Add tests in existing order-form gateway/commands suites and one central drift suite (new file or existing coverage suite).
   - Run:
     - `npm test`

   Expected result before migration is complete: newly added drift checks fail on current dual ownership.

2. Migrate gateway to catalog.

   - Edit `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs`.
   - Keep adapter and handlers unchanged unless tests require minimal updates.
   - Run:
     - `npm test`

   Expected result: gateway behavior remains unchanged, and command coverage derives from catalog data.

3. Migrate runtime registration order-form bindings to catalog.

   - Edit `/hyperopen/src/hyperopen/registry/runtime.cljs`.
   - Ensure order-form command-driven actions are catalog-derived and non-command actions remain explicitly registered.
   - Run:
     - `npm test`
     - `npm run test:websocket`

   Expected result: no missing runtime handlers, no registration regressions, and drift tests pass.

4. Finalize docs and required gates.

   - Update `/hyperopen/docs/RELIABILITY.md`.
   - Add ADR `0019` only if final implementation changes architecture governance materially.
   - Run required gates:
     - `npm run check`
     - `npm test`
     - `npm run test:websocket`

   Expected result: all gates pass and docs reflect final ownership contract.

## Validation and Acceptance

Acceptance is complete when all conditions below are true.

1. Command-driven order-form command->action mapping is defined in exactly one canonical catalog module.
2. `order_form_runtime_gateway` derives both translation and `supported-command-ids` from that catalog, with no private duplicate mapping table.
3. `registry/runtime` derives command-driven order-form action bindings from that same catalog.
4. Drift tests fail when catalog, command builders, and runtime registration are out of sync, and pass when synchronized.
5. Public order-form command adapter/gateway behavior remains unchanged for existing call sites.
6. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

This refactor is safe to apply incrementally. Keep migration commits additive and test-backed:

- First add catalog/tests.
- Then switch gateway.
- Then switch runtime registration.
- Then remove dead mappings.

If a migration step breaks runtime registration, restore the previous binding list in a small rollback commit while keeping the new catalog and drift tests, then reintroduce the migration in smaller slices.

## Artifacts and Notes

Primary files expected to change:

- `/hyperopen/src/hyperopen/schema/order_form_command_catalog.cljs` (new)
- `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs`
- `/hyperopen/src/hyperopen/registry/runtime.cljs`
- `/hyperopen/docs/RELIABILITY.md` (wording update for ownership contract)
- `/hyperopen/docs/architecture-decision-records/0019-command-action-catalog-authority.md`

Primary tests expected to change:

- `/hyperopen/test/hyperopen/views/trade/order_form_runtime_gateway_test.cljs`
- `/hyperopen/test/hyperopen/views/trade/order_form_commands_test.cljs`
- `/hyperopen/test/hyperopen/schema/order_form_command_catalog_test.cljs` (new dedicated catalog drift suite).

Evidence to capture during implementation:

- Required gate outputs:
  - `npm run check` succeeded (all lint/docs/compile checks green).
  - `npm test` succeeded (`Ran 1358 tests containing 6591 assertions. 0 failures, 0 errors.`).
  - `npm run test:websocket` succeeded (`Ran 148 tests containing 644 assertions. 0 failures, 0 errors.`).

## Interfaces and Dependencies

Interfaces to preserve:

- `/hyperopen/src/hyperopen/views/trade/order_form_intent_adapter.cljs` `command->actions` arities and behavior.
- `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs` protocol and constructor surface.
- `/hyperopen/src/hyperopen/registry/runtime.cljs` public registration functions and validation wrapper behavior.

Interfaces to add:

- A pure catalog API that exposes command/action/handler lookup and binding extraction for command-driven order-form interactions.

Dependency direction requirements:

- Runtime registry and view gateway may both depend on the new pure catalog namespace.
- The catalog namespace must not depend on view/runtime/infrastructure namespaces.

No new external libraries are required.

Plan revision note: 2026-02-25 20:58Z - Initial ExecPlan created to converge order-form command/action mapping ownership into one canonical catalog and remove dual mapping paths.
Plan revision note: 2026-02-25 21:09Z - Marked all milestones complete, recorded catalog/gateway/registry migration outcomes, documented ADR 0019 + reliability updates, and captured required gate results.
