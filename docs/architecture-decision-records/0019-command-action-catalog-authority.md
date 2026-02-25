# ADR 0019: Order-Form Command/Action Catalog Authority

- Status: Accepted
- Date: 2026-02-25

## Context

Order-form semantic command IDs were translated to runtime action IDs in one module, while runtime action registration kept a separate order-form action binding list in another module.

That created drift risk:

- command builders could emit valid command IDs that were missing from runtime registration
- runtime registration could include order-form action IDs not represented in command translation
- mapping edits required synchronized changes across multiple modules with no single owner

## Decision

1. Command-driven order-form mapping authority is centralized in `/hyperopen/src/hyperopen/schema/order_form_command_catalog.cljs`.
2. The catalog stores explicit entries with `:command-id`, `:action-id`, and `:handler-key`.
3. `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs` derives both command support and command->action translation from that catalog.
4. `/hyperopen/src/hyperopen/registry/runtime.cljs` derives command-driven order-form runtime action bindings from that same catalog.
5. Non-command order actions (`:actions/cancel-order`, `:actions/load-user-data`, `:actions/set-funding-modal`) remain explicitly registered outside catalog scope.
6. Drift guard tests enforce:
   - catalog command/action uniqueness
   - command builder coverage by catalog IDs
   - catalog action registration coverage in runtime registry

## Consequences

- Adding or changing command-driven order-form mappings is a one-file edit in the catalog.
- Gateway and runtime registration cannot diverge without failing tests.
- Public adapter/gateway and runtime registry APIs remain stable.
- Mapping ownership becomes explicit governance in reliability docs and this ADR.

## Invariant Ownership

- Canonical command/action/handler catalog:
  - `/hyperopen/src/hyperopen/schema/order_form_command_catalog.cljs`
- Gateway command translation boundary:
  - `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs`
- Runtime action registration boundary:
  - `/hyperopen/src/hyperopen/registry/runtime.cljs`
- Drift guard tests:
  - `/hyperopen/test/hyperopen/schema/order_form_command_catalog_test.cljs`
  - `/hyperopen/test/hyperopen/views/trade/order_form_commands_test.cljs`
