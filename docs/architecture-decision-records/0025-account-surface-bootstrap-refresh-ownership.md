# ADR 0025: Account-Surface Bootstrap and Refresh Ownership

- Status: Accepted
- Date: 2026-03-07

## Context

Hyperopen currently decides account-surface websocket coverage and REST fallback behavior in multiple places:

- `/hyperopen/src/hyperopen/startup/runtime.cljs`
- `/hyperopen/src/hyperopen/websocket/user.cljs`
- `/hyperopen/src/hyperopen/order/effects.cljs`

Those modules all need account data, but none of them should be the permanent owner of the rule that says when websocket streams are “good enough” and when a REST backstop must still run. That split ownership created duplicate helpers for stream usability and duplicated orchestration for open orders, default clearinghouse state, spot balances, and per-dex clearinghouse snapshots.

The architecture rules for this repository require a single invariant owner, deterministic policy seams, and thin callers around shared orchestration.

## Decision

1. Canonical pure account-surface policy now lives in:
   - `/hyperopen/src/hyperopen/account/surface_policy.cljs`
2. Canonical effectful account-surface bootstrap and refresh orchestration now lives in:
   - `/hyperopen/src/hyperopen/account/surface_service.cljs`
3. Startup bootstrap, websocket user handlers, and order mutation effects keep only trigger-specific responsibilities and delegate account-surface orchestration to that service:
   - `/hyperopen/src/hyperopen/startup/runtime.cljs`
   - `/hyperopen/src/hyperopen/websocket/user.cljs`
   - `/hyperopen/src/hyperopen/order/effects.cljs`
4. Future changes to stream-coverage rules, spot-surface activity rules, or account-surface fallback sequencing must be made in the account-surface policy/service boundary first, then consumed by callers.
5. New caller-specific behavior is allowed only when it is truly trigger-specific, for example startup-only reset behavior or websocket-only debounce timing. Trigger-specific code must not duplicate the shared stream-policy or fallback orchestration logic.

## Consequences

- Account-surface behavior now has one discoverable owner.
- Startup, websocket, and order modules are smaller and easier to reason about.
- Stream-coverage and fallback policy can be tested directly without going through startup or websocket handler wiring.
- The repository gains a durable seam for future WS-first and rate-limit work without scattering more policy copies.

## Invariant Ownership

- Pure stream-coverage selectors, dex normalization, and spot-surface visibility policy:
  `/hyperopen/src/hyperopen/account/surface_policy.cljs`
- Effectful startup backfill and post-event refresh orchestration:
  `/hyperopen/src/hyperopen/account/surface_service.cljs`
- Startup trigger ownership and address-handler lifecycle:
  `/hyperopen/src/hyperopen/startup/runtime.cljs`
- Websocket subscription ownership, handler registration, and debounce timing:
  `/hyperopen/src/hyperopen/websocket/user.cljs`
- Order mutation trigger ownership and optimistic state updates:
  `/hyperopen/src/hyperopen/order/effects.cljs`
- Boundary and regression tests:
  `/hyperopen/test/hyperopen/account/surface_policy_test.cljs`
  `/hyperopen/test/hyperopen/account/surface_service_test.cljs`
  `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
  `/hyperopen/test/hyperopen/websocket/user_test.cljs`
  `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`

## Extension Rules

- Do not add new local `topic-usable-*`, `topic-subscribed-*`, or account-surface fallback orchestration helpers in startup, websocket, or order modules.
- If a new trigger needs account-surface refresh behavior, add it through the account-surface service and cover it with seam tests plus caller regression tests.
- If account-surface policy needs a new selector or rule, add it to `surface_policy.cljs` and document the reason in tests or a follow-up ADR when architecture ownership changes again.
