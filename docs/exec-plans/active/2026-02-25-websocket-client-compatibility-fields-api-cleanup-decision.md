# WebSocket Client Compatibility Fields (API Cleanup Decision)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, websocket client state APIs will have one explicit canonical surface and one explicit compatibility boundary instead of mixed public mutable atoms. `runtime-view` and accessor functions will remain the supported client state authority surface. Legacy compatibility projection fields will be removed from the primary client API and replaced by an explicit, read-only compatibility adapter for any transitional debug usage.

A contributor can verify the result by running websocket and runtime tests and confirming that production code no longer reads or writes `ws-client/connection-state` or `ws-client/stream-runtime`, while websocket status/metrics/health behavior remains unchanged through `runtime-view` and existing accessor functions.

## Progress

- [x] (2026-02-25 23:34Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/RELIABILITY.md`, and ADR 0017 for websocket API ownership constraints.
- [x] (2026-02-25 23:34Z) Audited websocket client compatibility fields and projection wiring in `/hyperopen/src/hyperopen/websocket/client.cljs`, `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`, `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`, and `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`.
- [x] (2026-02-25 23:34Z) Audited compatibility-field consumers in `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`, `/hyperopen/src/hyperopen/startup/watchers.cljs`, `/hyperopen/src/hyperopen/runtime/app_effects.cljs`, `/hyperopen/src/hyperopen/startup/runtime.cljs`, and related tests.
- [x] (2026-02-25 23:34Z) Authored initial ExecPlan with explicit API cleanup decision, migration milestones, and required validation gates.
- [x] (2026-02-25 23:50Z) Implemented Milestone 1 by adding a deterministic source-inventory guard test in `/hyperopen/test/hyperopen/websocket/client_test.cljs` and migrating websocket client tests to canonical `runtime-view` assertions.
- [x] (2026-02-25 23:51Z) Implemented Milestone 2 by adding read-only adapter `/hyperopen/src/hyperopen/websocket/client_compat.cljs`, migrating debug snapshot compatibility reads in `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`, and removing legacy store-mirror writes from `/hyperopen/src/hyperopen/startup/watchers.cljs`/`/hyperopen/src/hyperopen/runtime/app_effects.cljs` while preserving status-transition diagnostics/health sync behavior.
- [x] (2026-02-25 23:52Z) Implemented Milestone 3 by removing primary websocket client compatibility atoms from `/hyperopen/src/hyperopen/websocket/client.cljs` and stale effect algebra entries from `/hyperopen/src/hyperopen/websocket/domain/model.cljs`.
- [x] (2026-02-25 23:58Z) Implemented Milestone 4 by adding ADR `/hyperopen/docs/architecture-decision-records/0022-websocket-client-compatibility-field-policy.md`, updating `/hyperopen/docs/RELIABILITY.md`, and passing required gates (`npm run check`, `npm test`, `npm run test:websocket`).

## Surprises & Discoveries

- Observation: compatibility projection atoms are still public and mutable even though they are no longer authoritative.
  Evidence: `/hyperopen/src/hyperopen/websocket/client.cljs` exports `connection-state` and `stream-runtime` as `defonce` atoms and syncs them from `runtime-view` via watch.

- Observation: production code paths already consume `runtime-view`; compatibility projection atoms are effectively unused in runtime behavior.
  Evidence: `/hyperopen/src/hyperopen/app/bootstrap.cljs` injects only `ws-client/runtime-view` into websocket watchers, and startup watchers consume only that atom.

- Observation: direct production usage of compatibility atoms is currently limited to debug snapshot output.
  Evidence: `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` includes `:compat-projections` by dereferencing `ws-client/connection-state` and `ws-client/stream-runtime`.

- Observation: tests still mutate compatibility atoms directly, proving the current surface allows non-canonical state drift writes.
  Evidence: `/hyperopen/test/hyperopen/websocket/client_test.cljs` calls `reset!` on `ws-client/connection-state` and `ws-client/stream-runtime` in `compatibility-projection-mutations-do-not-change-canonical-health-test`.

- Observation: websocket runtime effect algebra still declares deprecated projection effect keywords that are no longer emitted or interpreted.
  Evidence: `/hyperopen/src/hyperopen/websocket/domain/model.cljs` still includes `:fx/project-connection-state` and `:fx/project-stream-metrics` in `runtime-effect-types`, while reducer/interpreter now use only `:fx/project-runtime-view`.

- Observation: app store still carries legacy websocket connection mirror fields that have almost no active consumers.
  Evidence: `/hyperopen/src/hyperopen/startup/watchers.cljs` writes legacy projection keys under `:websocket`, while current views and diagnostics primarily consume `[:websocket :health]`.

- Observation: after migration, production source no longer references removed compatibility atom paths.
  Evidence: guard scan in `/hyperopen/test/hyperopen/websocket/client_test.cljs` enforces zero matches for `ws-client/connection-state` and `ws-client/stream-runtime` under `/hyperopen/src/hyperopen/**/*.cljs`.

- Observation: status-transition diagnostics behavior remains intact without legacy `:websocket` projection merges.
  Evidence: `/hyperopen/test/hyperopen/startup/watchers_test.cljs` asserts reconnect count and diagnostics event updates still occur on `:reconnecting`/`:connected` transitions while non-status churn no longer enqueues legacy projection writes.

## Decision Log

- Decision: Canonical websocket client state API remains `runtime-view` plus existing accessor functions (`connected?`, `get-connection-status`, `get-runtime-metrics`, `get-tier-depths`, `get-health-snapshot`).
  Rationale: This preserves the single-source runtime-view authority contract and keeps stable read APIs that already represent supported behavior.
  Date/Author: 2026-02-25 / Codex

- Decision: Remove mutable compatibility projection atoms (`connection-state`, `stream-runtime`) from the primary websocket client API and replace any remaining internal need with an explicit read-only compatibility adapter namespace.
  Rationale: Keeping mutable mirrors on the primary API contradicts cleanup intent, enables accidental writes, and is no longer needed for runtime behavior.
  Date/Author: 2026-02-25 / Codex

- Decision: Treat legacy websocket store connection fields (`:status`, `:attempt`, `:next-retry-at-ms`, `:last-close`, `:queue-size`) as deprecated compatibility fields and stop updating them once call sites are migrated.
  Rationale: They are transitional mirrors from pre-runtime-view ownership and create duplicate state surfaces with minimal current value.
  Date/Author: 2026-02-25 / Codex

- Decision: Clean stale runtime effect algebra entries for removed projection effects as part of the same API cleanup.
  Rationale: Declared-but-unused effect keywords weaken contract clarity and make runtime trace interpretation ambiguous.
  Date/Author: 2026-02-25 / Codex

- Decision: Record the final support/deprecation boundary in a new ADR (`0022`) and update reliability wording.
  Rationale: This is an architecture-affecting API surface decision and must be durable.
  Date/Author: 2026-02-25 / Codex

- Decision: Keep deprecated legacy websocket store connection fields in default state shape for compatibility, but stop runtime updates and migrate active consumers to health/runtime-view paths.
  Rationale: This removes live duplicate state maintenance risk without forcing unrelated shape-removal churn in the same migration.
  Date/Author: 2026-02-25 / Codex

## Outcomes & Retrospective

Implementation completed end-to-end. Primary websocket client compatibility atoms were removed, debug compatibility reads moved behind explicit read-only adapter functions, and startup watcher behavior now tracks only status-transition/health-fingerprint semantics rather than writing legacy websocket mirror fields. Runtime effect algebra entries now match emitted/interpreted effects.

All required gates passed:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Acceptance criteria are satisfied: canonical API ownership is explicit, compatibility scope is isolated, production usage of removed compatibility atom paths is guarded, and websocket diagnostics/health behavior remained functionally stable in tests.

## Context and Orientation

The websocket runtime authority model was recently consolidated so the reducer/engine owns canonical truth and the interpreter projects one `runtime-view` atom. That contract is implemented across:

- `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
- `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs`
- `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`
- `/hyperopen/src/hyperopen/websocket/client.cljs`

Before implementation, `/hyperopen/src/hyperopen/websocket/client.cljs` exposed legacy compatibility projection atoms:

- `connection-state`
- `stream-runtime`

Those atoms were derived from `runtime-view` and non-authoritative, but they remained mutable public vars and therefore invited accidental misuse. After implementation, compatibility reads are isolated in `/hyperopen/src/hyperopen/websocket/client_compat.cljs` as read-only snapshots.

For this plan:

- "Canonical websocket client API" means read paths that are intended to remain supported and authoritative for state projection (`runtime-view` and accessor functions).
- "Compatibility fields" means legacy projection-shaped fields retained for transitional callers (client compatibility atoms and legacy store websocket mirror keys).
- "API cleanup decision" means codifying which compatibility fields remain supported, which are deprecated, and which are removed, with explicit migration behavior and tests.

Current relevant files and responsibilities:

- Websocket client surface and compatibility atoms: `/hyperopen/src/hyperopen/websocket/client.cljs`
- Runtime projection emission: `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
- Runtime effect interpretation: `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`
- Startup websocket projection sync: `/hyperopen/src/hyperopen/startup/watchers.cljs`
- Debug snapshot payload: `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`
- App websocket defaults/mirror fields: `/hyperopen/src/hyperopen/state/app_defaults.cljs`

## Plan of Work

### Milestone 1: Lock The Cleanup Contract With Tests And Consumer Inventory

Start by adding tests that encode the intended post-cleanup contract before editing implementation. Add coverage that proves canonical read APIs remain stable and that no production namespace depends on legacy mutable compatibility fields.

This milestone should include one inventory guard test (or equivalent deterministic check) that fails if production namespaces reintroduce direct `ws-client/connection-state` or `ws-client/stream-runtime` usage.

### Milestone 2: Introduce Explicit Compatibility Adapter And Migrate Internal Consumers

Create a dedicated compatibility adapter namespace (for example `/hyperopen/src/hyperopen/websocket/client_compat.cljs`) that exposes read-only compatibility snapshots derived from `runtime-view` for any transitional/debug need. Migrate debug snapshot paths and any residual internal compatibility reads to this adapter.

Update startup/store compatibility handling so deprecated mirror fields are either removed or clearly isolated behind the same compatibility boundary. Keep user-visible websocket diagnostics and freshness behavior unchanged.

### Milestone 3: Remove Legacy Mutable Compatibility Fields From Primary API

Refactor `/hyperopen/src/hyperopen/websocket/client.cljs` so the primary client API no longer exports mutable compatibility projection atoms. Keep canonical runtime-view and accessor functions unchanged.

As part of this milestone, remove stale runtime effect algebra entries (`:fx/project-connection-state`, `:fx/project-stream-metrics`) from `/hyperopen/src/hyperopen/websocket/domain/model.cljs`, and update tests/trace expectations that still reference deprecated effect names.

### Milestone 4: Document Final Support/Deprecation Boundary And Validate

Add ADR `0022` describing websocket client compatibility field support/deprecation policy, canonical API ownership, and extension rules. Update `/hyperopen/docs/RELIABILITY.md` with explicit websocket client API cleanup governance.

Run required validation gates and capture evidence.

## Concrete Steps

From `/hyperopen`:

1. Add contract tests and usage guards.

   - Update websocket client and startup watcher tests to assert canonical API behavior without compatibility atoms.
   - Add guard coverage that fails if production code depends on deprecated compatibility vars.
   - Run:
     - `npm run test:websocket`

   Expected outcome: tests fail before migration where legacy compatibility fields are still required.

2. Introduce compatibility adapter and migrate internal call sites.

   - Add `/hyperopen/src/hyperopen/websocket/client_compat.cljs`.
   - Migrate `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` and any remaining internal usage paths.
   - Update startup/store legacy mirror handling in `/hyperopen/src/hyperopen/startup/watchers.cljs`, `/hyperopen/src/hyperopen/runtime/app_effects.cljs`, and `/hyperopen/src/hyperopen/state/app_defaults.cljs` as needed by the chosen boundary.
   - Run:
     - `npm run test:websocket`
     - `npm test`

   Expected outcome: runtime behavior is unchanged, internal compatibility usage is explicit, and tests are green.

3. Remove legacy mutable compatibility fields and stale effect algebra entries.

   - Edit `/hyperopen/src/hyperopen/websocket/client.cljs` and `/hyperopen/src/hyperopen/websocket/domain/model.cljs`.
   - Update impacted tests and trace expectations.
   - Run:
     - `npm run test:websocket`
     - `npm test`

   Expected outcome: canonical websocket client API is clean, deprecated compatibility fields are not on the primary client surface, and effect algebra matches actual runtime behavior.

4. Finalize docs/ADR and run required gates.

   - Add `/hyperopen/docs/architecture-decision-records/0022-websocket-client-compatibility-field-policy.md`.
   - Update `/hyperopen/docs/RELIABILITY.md` with final policy wording.
   - Run required gates:
     - `npm run check`
     - `npm test`
     - `npm run test:websocket`

   Expected outcome: required gates pass and compatibility field policy is explicit and durable.

## Validation and Acceptance

Acceptance is complete when all conditions below are true.

1. `runtime-view` and existing websocket accessor functions remain the documented canonical websocket client state API.
2. `ws-client/connection-state` and `ws-client/stream-runtime` are no longer part of the primary websocket client API surface.
3. Any remaining compatibility needs are isolated in an explicit compatibility adapter namespace with read-only semantics.
4. Production code no longer depends on deprecated websocket compatibility fields.
5. Runtime effect algebra no longer lists deprecated projection effect keywords that are not emitted/interpreted.
6. Websocket diagnostics and health/freshness user-visible behavior remain functionally equivalent.
7. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

Apply this migration in small additive slices:

- tests/guards first,
- adapter introduction,
- call-site migration,
- compatibility field removal,
- docs finalization.

If a removal step causes breakage, temporarily restore a thin compatibility adapter mapping from canonical runtime-view while keeping new contract tests, then continue migration until no production dependencies remain. Avoid reintroducing mutable compatibility atoms on the primary client API.

## Artifacts and Notes

Primary files expected to change:

- `/hyperopen/src/hyperopen/websocket/client.cljs`
- `/hyperopen/src/hyperopen/websocket/client_compat.cljs` (new)
- `/hyperopen/src/hyperopen/websocket/domain/model.cljs`
- `/hyperopen/src/hyperopen/startup/watchers.cljs`
- `/hyperopen/src/hyperopen/runtime/app_effects.cljs`
- `/hyperopen/src/hyperopen/startup/runtime.cljs`
- `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`
- `/hyperopen/docs/RELIABILITY.md`
- `/hyperopen/docs/architecture-decision-records/0022-websocket-client-compatibility-field-policy.md` (new)

Primary tests expected to change:

- `/hyperopen/test/hyperopen/websocket/client_test.cljs`
- `/hyperopen/test/hyperopen/startup/watchers_test.cljs`
- `/hyperopen/test/hyperopen/runtime/app_effects_test.cljs`

Evidence to capture during implementation:

- Before/after test evidence proving removal of primary compatibility fields with no runtime behavior regression.
- Before/after usage evidence that production namespaces no longer dereference removed compatibility vars.
- Required gate outputs.

## Interfaces and Dependencies

Interfaces to preserve:

- `/hyperopen/src/hyperopen/websocket/client.cljs` lifecycle/send/handler APIs (`init-connection!`, `disconnect!`, `force-reconnect!`, `send-message!`, `register-handler!`).
- Canonical read APIs (`runtime-view`, `connected?`, `get-connection-status`, `get-runtime-metrics`, `get-tier-depths`, `get-health-snapshot`).

Interfaces to deprecate/remove:

- Primary compatibility projection atom exports on websocket client (`connection-state`, `stream-runtime`).
- Legacy websocket store mirror fields as actively maintained runtime projection outputs.

Dependency direction constraints:

- Compatibility adapter may depend on websocket client canonical APIs.
- Websocket client canonical module must not depend on the compatibility adapter.
- Startup/runtime/view modules should read canonical websocket projection/state paths, not deprecated compatibility fields.

No new external libraries are required.

Plan revision note: 2026-02-25 23:34Z - Initial ExecPlan created to resolve websocket client compatibility-field ambiguity by choosing a canonical API cleanup policy, defining migration milestones, and codifying acceptance gates.
Plan revision note: 2026-02-25 23:58Z - Completed milestones 1-4 by removing primary compatibility atoms, adding read-only compatibility adapter + source-inventory guard, migrating debug/startup call sites, updating ADR/reliability policy, and passing all required gates.
