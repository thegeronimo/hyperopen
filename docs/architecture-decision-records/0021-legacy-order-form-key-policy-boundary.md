# ADR 0021: Legacy Order-Form Key Policy Support/Deprecation Boundary

- Status: Accepted
- Date: 2026-02-25

## Context

Order-form key ownership drifted across multiple modules:

- `/hyperopen/src/hyperopen/state/trading.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`

Each module kept separate lists for UI-owned keys and legacy runtime/UI keys. Compatibility behavior was implicit: read normalization tolerated old keys, while app-state contracts rejected those same keys in canonical `:order-form`.

That made support/deprecation boundaries hard to audit and easy to break during refactors.

## Decision

1. Canonical key policy ownership is centralized in:
   `/hyperopen/src/hyperopen/state/trading/order_form_key_policy.cljs`.
2. Legacy key compatibility is supported only at read ingress normalization boundaries.
3. Canonical write boundaries must remove or reject deprecated canonical keys:
   - persistence stripping via `persist-order-form`
   - app-state schema contract rejection for deprecated keys under `:order-form`
4. `update-order-form` write paths must reject policy-defined blocked key paths (UI-owned, runtime, and legacy compatibility keys).
5. Drift guard tests must prove policy consistency and boundary enforcement across state, schema, and transitions.

## Consequences

- Key support/deprecation policy is explicit and reusable.
- Compatibility remains for transitional callers at ingress without allowing legacy keys in canonical persisted state.
- Transition and schema behavior cannot diverge without failing tests.
- Future key additions/removals require a single policy update plus boundary tests.

## Invariant Ownership

- Canonical order-form key policy:
  `/hyperopen/src/hyperopen/state/trading/order_form_key_policy.cljs`
- Read ingress normalization boundary:
  `/hyperopen/src/hyperopen/state/trading.cljs`
- Canonical app-state contract rejection boundary:
  `/hyperopen/src/hyperopen/schema/contracts.cljs`
- Transition write-path rejection boundary:
  `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`
- Drift and boundary tests:
  `/hyperopen/test/hyperopen/state/trading/order_form_key_policy_test.cljs`
  `/hyperopen/test/hyperopen/state/trading/order_form_state_test.cljs`
  `/hyperopen/test/hyperopen/state/trading_test.cljs`
  `/hyperopen/test/hyperopen/schema/contracts_test.cljs`
  `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`
