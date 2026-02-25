# ADR 0020: Startup Layering Intent and Permanent Boundaries

- Status: Accepted
- Date: 2026-02-25

## Context

Startup behavior was routed through multiple delegation-only layers:

- `/hyperopen/src/hyperopen/app/startup.cljs`
- `/hyperopen/src/hyperopen/startup/wiring.cljs`
- `/hyperopen/src/hyperopen/startup/composition.cljs`
- `/hyperopen/src/hyperopen/startup/runtime.cljs`

Most logic and invariants already lived in `startup/runtime`, `startup/init`, and `startup/collaborators`. The extra pass-through layers increased indirection and encouraged tests that asserted namespace delegation topology instead of user-visible startup behavior and idempotence.

Some startup integration tests also patched `app/startup` seam vars with `set!`, which coupled tests to transitional wrappers rather than permanent dependency boundaries.

## Decision

1. Permanent startup architecture is explicitly defined as four layers:
   - entrypoint-facing startup facade:
     `/hyperopen/src/hyperopen/app/startup.cljs`
   - startup collaborator assembly:
     `/hyperopen/src/hyperopen/startup/collaborators.cljs`
   - startup lifecycle behavior owners:
     `/hyperopen/src/hyperopen/startup/init.cljs` and
     `/hyperopen/src/hyperopen/startup/runtime.cljs`
   - runtime bootstrap and watcher installation owners:
     `/hyperopen/src/hyperopen/app/bootstrap.cljs`,
     `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`, and
     `/hyperopen/src/hyperopen/startup/watchers.cljs`
2. Transitional scaffolding namespaces are removed:
   - `/hyperopen/src/hyperopen/startup/wiring.cljs`
   - `/hyperopen/src/hyperopen/startup/composition.cljs`
3. Address-handler reify ownership moves to `startup/runtime` (`reify-address-handler` plus default handler-name contract).
4. Startup tests must target behavior and idempotence contracts and prefer collaborator/runtime dependency-injection seams over mutating startup seam vars.

## Consequences

- Startup call flow is shorter and explicit.
- Layer ownership is durable and documented, reducing ambiguity for future contributors.
- Tests no longer preserve wrapper topology and instead enforce startup behavior contracts.
- Public `hyperopen.core` entrypoint API remains unchanged.

## Extension Rules

- New startup behavior belongs in existing permanent layers listed above.
- Do not add new delegation-only startup wrappers between `app/startup` and `startup/runtime` or `startup/init`.
- If a new startup boundary is necessary, document ownership and rationale in a follow-up ADR and add boundary contract tests.

## Invariant Ownership

- Startup facade orchestration:
  `/hyperopen/src/hyperopen/app/startup.cljs`
- Startup dependency assembly:
  `/hyperopen/src/hyperopen/startup/collaborators.cljs`
- Startup state transitions, staging, and address-handler contracts:
  `/hyperopen/src/hyperopen/startup/runtime.cljs`
- Startup reset/restore/init sequence:
  `/hyperopen/src/hyperopen/startup/init.cljs`
- Runtime bootstrap/watcher install contracts:
  `/hyperopen/src/hyperopen/app/bootstrap.cljs`,
  `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`,
  `/hyperopen/src/hyperopen/startup/watchers.cljs`
- Boundary behavior tests:
  `/hyperopen/test/hyperopen/app/startup_test.cljs`,
  `/hyperopen/test/hyperopen/startup/runtime_test.cljs`,
  `/hyperopen/test/hyperopen/core_bootstrap/runtime_startup_test.cljs`
