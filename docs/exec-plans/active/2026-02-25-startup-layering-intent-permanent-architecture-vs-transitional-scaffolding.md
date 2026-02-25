# Startup Layering Intent (Permanent Architecture Vs Transitional Scaffolding)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, startup lifecycle ownership will have one explicit permanent architecture and no ambiguous transitional pass-through layers. Contributors will know which namespaces are long-lived boundaries and which wrapper namespaces were temporary scaffolding that has now been retired.

A contributor can verify the result by running startup and bootstrap tests and seeing behavior-based coverage remain green while delegation-only wrapper layers are removed, with architecture documentation explicitly naming the permanent startup layers.

## Progress

- [x] (2026-02-25 21:15Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, and `/hyperopen/docs/RELIABILITY.md` for layering and startup idempotence constraints.
- [x] (2026-02-25 21:15Z) Audited startup/runtime code paths in `/hyperopen/src/hyperopen/core.cljs`, `/hyperopen/src/hyperopen/app/startup.cljs`, `/hyperopen/src/hyperopen/app/bootstrap.cljs`, and `/hyperopen/src/hyperopen/startup/**`.
- [x] (2026-02-25 21:15Z) Audited startup-related tests in `/hyperopen/test/hyperopen/startup/**`, `/hyperopen/test/hyperopen/runtime/bootstrap_test.cljs`, and `/hyperopen/test/hyperopen/core_bootstrap/runtime_startup_test.cljs`.
- [x] (2026-02-25 21:15Z) Reviewed architecture decision records for startup boundary intent in `/hyperopen/docs/architecture-decision-records/0002-entrypoint-system-startup-boundaries.md` and related ADRs.
- [x] (2026-02-25 21:15Z) Authored initial ExecPlan with permanent layer contract, migration milestones, and validation gates.
- [x] (2026-02-25 21:26Z) Implemented Milestone 1 by adding behavior-first startup boundary tests in `/hyperopen/test/hyperopen/app/startup_test.cljs`, extending runtime address-handler contract tests in `/hyperopen/test/hyperopen/startup/runtime_test.cljs`, and replacing `set!`-based startup seam mutations in `/hyperopen/test/hyperopen/core_bootstrap/runtime_startup_test.cljs` with collaborator injection.
- [x] (2026-02-25 21:26Z) Implemented Milestone 2 by flattening startup call flow in `/hyperopen/src/hyperopen/app/startup.cljs`, inlining watcher dep shaping in `/hyperopen/src/hyperopen/app/bootstrap.cljs`, moving address-handler reify ownership into `/hyperopen/src/hyperopen/startup/runtime.cljs`, and deleting transitional scaffolding namespaces `/hyperopen/src/hyperopen/startup/wiring.cljs` and `/hyperopen/src/hyperopen/startup/composition.cljs`.
- [x] (2026-02-25 21:26Z) Implemented Milestone 3 by retiring delegation-only tests `/hyperopen/test/hyperopen/startup/wiring_test.cljs` and `/hyperopen/test/hyperopen/startup/composition_test.cljs`, and replacing coverage with behavior/boundary contract tests that target permanent owners.
- [x] (2026-02-25 21:28Z) Implemented Milestone 4 by adding ADR `/hyperopen/docs/architecture-decision-records/0020-startup-layering-intent.md`, updating startup governance wording in `/hyperopen/ARCHITECTURE.md` and `/hyperopen/docs/RELIABILITY.md`, and passing required gates (`npm run check`, `npm test`, `npm run test:websocket`).

## Surprises & Discoveries

- Observation: startup call flow currently traverses multiple near-pass-through layers before executing real behavior.
  Evidence: `/hyperopen/src/hyperopen/app/startup.cljs` calls `/hyperopen/src/hyperopen/startup/wiring.cljs`, which delegates to `/hyperopen/src/hyperopen/startup/composition.cljs`, which delegates to `/hyperopen/src/hyperopen/startup/runtime.cljs`.

- Observation: `startup.composition` tests primarily assert delegation to another namespace, which preserves wrapper structure rather than user-visible startup behavior.
  Evidence: `/hyperopen/test/hyperopen/startup/composition_test.cljs` checks only delegation calls to `startup-runtime-lib` and `startup-init`.

- Observation: several app-startup helper vars are externally visible mostly for tests and are patched via `set!`, which increases coupling to transitional seams.
  Evidence: `/hyperopen/test/hyperopen/core_bootstrap/runtime_startup_test.cljs` mutates `app-startup/stage-b-account-bootstrap!` and API vars for orchestration assertions.

- Observation: startup wiring currently owns both real contracts and scaffolding utilities; only a small subset provides non-trivial logic (for example address-handler `reify` and watcher dep shapes).
  Evidence: `/hyperopen/src/hyperopen/startup/wiring.cljs` has many one-line delegators plus `reify-address-handler`.

- Observation: startup code footprint is spread across many namespaces (over 1000 LOC total), but permanent behavioral logic is concentrated in `startup/runtime`, `startup/init`, and `startup/collaborators`.
  Evidence: `wc -l` across startup modules shows 1077 total LOC with most behavior in `/hyperopen/src/hyperopen/startup/runtime.cljs` and `/hyperopen/src/hyperopen/startup/collaborators.cljs`.

- Observation: ADR sequence slot `0019` was already allocated to command/action catalog authority before this plan implementation started.
  Evidence: `/hyperopen/docs/architecture-decision-records/0019-command-action-catalog-authority.md` exists, so startup layering ADR must use the next sequence number.

- Observation: after flattening wrappers, stage-B bootstrap interception in integration tests should come from collaborator dependency injection rather than runtime var mutation.
  Evidence: `/hyperopen/test/hyperopen/core_bootstrap/runtime_startup_test.cljs` now injects `:stage-b-account-bootstrap!` through `startup-collaborators/startup-base-deps` and no longer patches `app-startup/*` vars with `set!`.

## Decision Log

- Decision: Define permanent startup architecture as four layers: entrypoint facade, lifecycle orchestration, collaborator assembly, and runtime behavior modules.
  Rationale: This keeps intent explicit, preserves DDD/SOLID boundary direction, and removes uncertainty about temporary wrappers.
  Date/Author: 2026-02-25 / Codex

- Decision: Retire `startup/wiring.cljs` and `startup/composition.cljs` as transitional scaffolding once direct permanent seams are in place.
  Rationale: These namespaces currently add minimal business behavior and create indirection cost in production code and tests.
  Date/Author: 2026-02-25 / Codex

- Decision: Keep stable entrypoint behavior (`hyperopen.core/init`, `hyperopen.core/reload`) while reducing internal startup seam surface area.
  Rationale: Public API stability is required by repository guardrails even when internal architecture is simplified.
  Date/Author: 2026-02-25 / Codex

- Decision: Replace delegation-only tests with behavior/invariant tests for startup sequencing, idempotence, and boundary ownership.
  Rationale: Tests should enforce permanent architecture outcomes, not preserve transitional wrapper topology.
  Date/Author: 2026-02-25 / Codex

- Decision: Record final startup layering intent in a new ADR and refresh architecture/reliability wording where startup boundaries are referenced.
  Rationale: Layering intent must be durable and discoverable for future contributors.
  Date/Author: 2026-02-25 / Codex

- Decision: Add startup layering ADR as `0020` instead of `0019`.
  Rationale: `0019` is already occupied by the command/action catalog authority ADR and numbering must remain monotonic.
  Date/Author: 2026-02-25 / Codex

- Decision: Allow stage-B bootstrap override injection from collaborator deps in `app/startup`.
  Rationale: This preserves runtime-owned behavior while eliminating `set!` seam mutation in integration tests.
  Date/Author: 2026-02-25 / Codex

## Outcomes & Retrospective

Implementation is complete. Startup layering is now explicit: `app/startup` calls permanent `startup/collaborators`, `startup/init`, and `startup/runtime` owners directly; `startup/wiring` and `startup/composition` are removed; and tests now enforce behavior contracts instead of delegation topology.

Required validation gates all passed after implementation:

- `npm run check`
- `npm test`
- `npm run test:websocket`

## Context and Orientation

Startup behavior currently spans these key namespaces:

- Entrypoint orchestration: `/hyperopen/src/hyperopen/core.cljs`
- App startup facade and system-bound calls: `/hyperopen/src/hyperopen/app/startup.cljs`
- Runtime bootstrap/watcher installation: `/hyperopen/src/hyperopen/app/bootstrap.cljs` and `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`
- Startup behavior and state logic: `/hyperopen/src/hyperopen/startup/init.cljs` and `/hyperopen/src/hyperopen/startup/runtime.cljs`
- Startup collaborator assembly (API/ws/address dependencies): `/hyperopen/src/hyperopen/startup/collaborators.cljs`
- Transitional delegation layers: `/hyperopen/src/hyperopen/startup/wiring.cljs` and `/hyperopen/src/hyperopen/startup/composition.cljs`

For this plan:

- "Permanent architecture" means namespaces that are intended long-term boundaries with meaningful ownership and stable responsibilities.
- "Transitional scaffolding" means temporary adapter/delegator layers used during migration that no longer provide unique business behavior and should be retired.
- "Startup lifecycle orchestration" means deterministic ordering of startup steps: reset runtime startup state, restore persisted UI settings, initialize wallet/router/input listeners, initialize websocket/data streams, and schedule deferred bootstrap stages.

## Plan of Work

### Milestone 1: Lock Permanent Boundary Intent With Tests Before Refactor

Start by adding or updating tests that describe permanent startup behavior and boundary ownership rather than delegation mechanics. These tests should prove startup sequencing and idempotence remain deterministic regardless of internal namespace layout.

Add assertions that the startup entrypoint uses one direct lifecycle boundary (not multi-hop pass-through chaining), and that runtime bootstrap/install behavior remains idempotent and reentrant.

This milestone intentionally creates failure pressure against delegation-only scaffolding assumptions so subsequent flattening is safe.

### Milestone 2: Flatten Startup Call Graph To Permanent Layers

Refactor startup call flow so permanent layers are explicit:

1. `app/startup` remains the entrypoint-facing startup facade.
2. `startup/collaborators` remains collaborator assembly for injected dependencies.
3. `startup/init` and `startup/runtime` remain behavior owners.
4. `app/bootstrap` and `runtime/bootstrap` remain runtime registration/watcher installation owners.

Move any non-trivial helpers from `startup/wiring` or `startup/composition` into permanent owners (for example address-handler protocol reify helper into startup runtime or collaborators boundary). Then remove transitional one-line delegators and delete scaffolding namespaces once call sites are migrated.

Preserve startup behavior order and existing user-visible runtime outcomes throughout migration.

### Milestone 3: Retarget Startup Test Topology To Behavior Contracts

Replace tests that validate cross-namespace delegation with tests that validate behavior and invariants:

- startup order and idempotence,
- account bootstrap stage A/B gating and stale-address guards,
- address removal reset behavior,
- deferred bootstrap scheduling semantics,
- runtime bootstrap watcher/render installation contracts.

Where tests currently mutate `app-startup/*` vars with `set!` to intercept transitional seams, migrate to dependency-injection seams owned by permanent modules (`startup/collaborators`, `startup/runtime`) so tests are less topology-dependent.

### Milestone 4: Document Final Intent And Validate End-To-End

Add a startup layering ADR (next sequence number, actual `0020`) that explicitly labels:

- permanent startup layer responsibilities,
- removed transitional scaffolding layers,
- extension rules for future startup changes.

Update `/hyperopen/ARCHITECTURE.md` and `/hyperopen/docs/RELIABILITY.md` where startup ownership wording still implies ambiguous layering.

Run required validation gates and capture evidence in this plan.

## Concrete Steps

From `/hyperopen`:

1. Add or update startup tests to lock behavior-first boundaries before deleting scaffolding layers.

   - Update startup/core bootstrap tests so they fail on boundary drift but not on internal helper renames.
   - Run:
     - `npm test`

   Expected outcome: new/updated tests expose current transitional coupling and define desired permanent contracts.

2. Refactor startup call graph and delete transitional pass-through layers.

   - Migrate call sites in `/hyperopen/src/hyperopen/app/startup.cljs` and `/hyperopen/src/hyperopen/app/bootstrap.cljs` to permanent modules.
   - Remove `/hyperopen/src/hyperopen/startup/composition.cljs` and `/hyperopen/src/hyperopen/startup/wiring.cljs` once references are eliminated.
   - Run:
     - `npm test`

   Expected outcome: startup behavior remains unchanged while startup namespace graph is simpler and responsibility ownership is explicit.

3. Retarget tests from delegation assertions to startup behavior contracts.

   - Replace/retire composition/wiring delegation tests.
   - Add behavior-focused coverage where delegation-only tests were removed.
   - Run:
     - `npm test`
     - `npm run test:websocket`

   Expected outcome: startup and websocket/runtime startup integration coverage remains green without relying on scaffolding topology.

4. Finalize docs/ADR and run required gates.

   - Add ADR `0020` for startup layering intent.
   - Update architecture/reliability wording for startup ownership and idempotence boundaries.
   - Run required gates:
     - `npm run check`
     - `npm test`
     - `npm run test:websocket`

   Expected outcome: all required gates pass and permanent layering intent is codified.

## Validation and Acceptance

Acceptance is complete when all conditions below are true.

1. Startup layering intent is explicit and documented as permanent architecture, not implied by transitional wrappers.
2. `startup/wiring` and `startup/composition` scaffolding layers are removed (or reduced to genuinely non-transitional ownership with explicit rationale) and no longer form multi-hop pass-through chains.
3. Startup lifecycle behavior remains deterministic and idempotent (`core/init` once semantics, runtime bootstrap once semantics, deferred bootstrap scheduling guard).
4. Account bootstrap and address-change flows preserve existing functional behavior (stage A/B fetch ordering, stale address guard, state reset on address removal).
5. Tests enforce behavior and boundary invariants rather than namespace delegation mechanics.
6. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

Execute this migration in additive slices:

- lock behavior tests,
- flatten one startup seam at a time,
- delete scaffolding only after tests pass.

If a flattening step introduces regressions, restore the previous seam in a focused rollback commit while keeping new behavior-contract tests, then reapply migration in smaller refactor steps. Avoid large namespace deletions without passing tests in the same commit.

## Artifacts and Notes

Primary files expected to change:

- `/hyperopen/src/hyperopen/app/startup.cljs`
- `/hyperopen/src/hyperopen/app/bootstrap.cljs`
- `/hyperopen/src/hyperopen/startup/init.cljs`
- `/hyperopen/src/hyperopen/startup/runtime.cljs`
- `/hyperopen/src/hyperopen/startup/collaborators.cljs`
- `/hyperopen/src/hyperopen/startup/watchers.cljs` (if helper ownership moves here)
- `/hyperopen/src/hyperopen/startup/wiring.cljs` (expected removal or major reduction)
- `/hyperopen/src/hyperopen/startup/composition.cljs` (expected removal or major reduction)
- `/hyperopen/ARCHITECTURE.md`
- `/hyperopen/docs/RELIABILITY.md`
- `/hyperopen/docs/architecture-decision-records/0020-startup-layering-intent.md` (new)

Primary tests expected to change:

- `/hyperopen/test/hyperopen/startup/init_test.cljs`
- `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
- `/hyperopen/test/hyperopen/startup/collaborators_test.cljs`
- `/hyperopen/test/hyperopen/startup/wiring_test.cljs` (expected removal or repurpose)
- `/hyperopen/test/hyperopen/startup/composition_test.cljs` (expected removal or repurpose)
- `/hyperopen/test/hyperopen/app/startup_test.cljs` (new)
- `/hyperopen/test/hyperopen/runtime/bootstrap_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/runtime_startup_test.cljs`

Evidence to capture during implementation:

- Before/after test evidence showing delegation-only tests replaced by behavior-contract tests.
- Before/after namespace graph evidence showing removal of multi-hop startup pass-through.
- Required gate outputs.

Captured evidence:

- `npm run check`: docs lint, hiccup lint, app/test compile all passed.
- `npm test`: 1354 tests, 6576 assertions, 0 failures.
- `npm run test:websocket`: 148 tests, 644 assertions, 0 failures.

## Interfaces and Dependencies

Interfaces to preserve:

- `hyperopen.core` entrypoint API: `init`, `reload`, `start!`, and existing runtime bootstrapping behavior.
- Runtime bootstrap registration semantics in `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`.

Interfaces expected to narrow:

- `hyperopen.app.startup` should expose only stable startup facade functions needed by entrypoint/runtime tests, not incidental scaffolding helpers.

Dependency direction constraints:

- Entry boundary (`app/*`) may depend on `startup/*` modules.
- Startup collaborator assembly may depend on API/websocket/wallet/address watcher integration seams.
- Startup behavior modules must not depend back on app entrypoint modules.

No new external dependencies are required.

Plan revision note: 2026-02-25 21:15Z - Initial ExecPlan created to resolve startup layering ambiguity by codifying permanent boundaries and retiring transitional scaffolding.
Plan revision note: 2026-02-25 21:26Z - Completed milestones 1-3 by flattening startup call graph, removing transitional scaffolding namespaces/tests, and migrating startup integration assertions to collaborator/runtime dependency-injection seams; updated ADR target sequence from 0019 to 0020.
Plan revision note: 2026-02-25 21:28Z - Completed milestone 4 with ADR 0020, architecture/reliability governance updates, and successful execution of required validation gates.
