# Canonical WebSocket Truth Ownership (Single Source Of State Authority)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, websocket truth has one authority: the reducer-owned runtime state managed by the websocket engine loop. All other websocket values used by UI, diagnostics, and startup watchers become explicit projections derived from that single canonical source.

A contributor can verify the result by running websocket tests and observing that connection status, stream metrics, and health snapshots remain consistent across reconnects without any module depending on parallel mutable websocket atoms as authoritative state.

## Progress

- [x] (2026-02-25 19:14Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, and `/hyperopen/docs/RELIABILITY.md` for ownership and single-writer constraints.
- [x] (2026-02-25 19:14Z) Audited websocket state surfaces in `/hyperopen/src/hyperopen/websocket/client.cljs`, `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`, `/hyperopen/src/hyperopen/startup/watchers.cljs`, and `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`.
- [x] (2026-02-25 19:14Z) Identified current authority drift points and captured them in this plan.
- [x] (2026-02-25 19:14Z) Authored initial ExecPlan with milestones, acceptance criteria, and required validation gates.
- [x] (2026-02-25 22:48Z) Implemented Milestone 1 by updating websocket runtime, client, watchers, and diagnostics tests to assert canonical runtime-view authority and projection-only compatibility atoms.
- [x] (2026-02-25 22:56Z) Implemented Milestone 2 by introducing `:fx/project-runtime-view`, runtime interpreter/runtime wiring updates, and client `runtime-view` projection ownership.
- [x] (2026-02-25 23:05Z) Implemented Milestone 3 by migrating startup watchers to one `runtime-view` source and moving websocket health dedupe ownership to `ws-client/websocket-health-projection-state`.
- [x] (2026-02-25 23:12Z) Implemented Milestone 4 by removing targeted UI health fallback ambiguity, updating diagnostics snapshot ownership labels, adding ADR 0017, tightening reliability wording, and passing required gates.

## Surprises & Discoveries

- Observation: websocket status and metrics are maintained in multiple mutable atoms (`connection-state`, `stream-runtime`, `runtime-state`, app store `:websocket`), even though runtime decisions are already centralized in engine state.
  Evidence: `/hyperopen/src/hyperopen/websocket/client.cljs` defines `connection-state`, `stream-runtime`, and `runtime-state`; `/hyperopen/src/hyperopen/startup/watchers.cljs` mirrors atom changes into store `:websocket`.

- Observation: startup sync logic treats `connection-state` as a "legacy projection" and writes store websocket fields on projection changes, which hides the real canonical source.
  Evidence: `legacy-websocket-projection` and connection-state watch in `/hyperopen/src/hyperopen/startup/watchers.cljs`.

- Observation: health fingerprint dedupe state lives in app runtime (`runtime.state`) instead of websocket runtime ownership.
  Evidence: `/hyperopen/src/hyperopen/runtime/state.cljs` has `:websocket-health`, and `/hyperopen/src/hyperopen/websocket/health_runtime.cljs` reads/writes `[:websocket-health ...]` when `:runtime` is passed.

- Observation: view code still carries fallback logic that implies dual health sources (`:websocket-health` vs `[:websocket :health]`).
  Evidence: `/hyperopen/src/hyperopen/views/trade_view.cljs`, `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`, and `/hyperopen/src/hyperopen/views/account_info/vm.cljs` use `(or (:websocket-health state) ...)`.

- Observation: several tests mutate websocket projection atoms directly, which currently blurs projection data with authority data.
  Evidence: `/hyperopen/test/hyperopen/core_bootstrap/websocket_diagnostics_test.cljs` and `/hyperopen/test/hyperopen/websocket/client_test.cljs` reset/swap `ws-client/connection-state` and `ws-client/stream-runtime`.

## Decision Log

- Decision: Define canonical websocket truth as the runtime engine state atom (`engine :state`) produced by `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs`.
  Rationale: This is already the single-writer reducer state and best matches repository invariants in `/hyperopen/docs/RELIABILITY.md`.
  Date/Author: 2026-02-25 / Codex

- Decision: Introduce one explicit websocket runtime view projection that aggregates connection and stream projections, and make all UI/store sync consume that projection.
  Rationale: This keeps derived data explicit while removing ambiguous multi-atom ownership in startup watchers.
  Date/Author: 2026-02-25 / Codex

- Decision: Preserve websocket client public API behavior while migrating internals; compatibility vars can remain temporarily but are projection-only and non-authoritative.
  Rationale: `/hyperopen/ARCHITECTURE.md` requires stable public websocket APIs unless explicitly requested.
  Date/Author: 2026-02-25 / Codex

- Decision: Move websocket health dedupe ownership out of app runtime state and into websocket-scoped projection state.
  Rationale: Health fingerprint dedupe belongs to websocket runtime projection mechanics, not generic app runtime flags.
  Date/Author: 2026-02-25 / Codex

- Decision: Add an ADR for websocket state authority to prevent future drift.
  Rationale: This is an architecture-affecting ownership change and must be durable.
  Date/Author: 2026-02-25 / Codex

## Outcomes & Retrospective

Implemented. Canonical websocket authority is now reducer/engine state, with one interpreter-applied runtime projection source (`ws-client/runtime-view`).

What landed:

- Reducer now emits one projection effect (`:fx/project-runtime-view`) with dedupe fingerprint.
- Interpreter now writes one projection atom (`runtime-view-atom`) and attaches active socket there.
- Client exposes `runtime-view` as canonical projection state; `connection-state` and `stream-runtime` remain as derived compatibility mirrors.
- Startup watchers now consume only `runtime-view`.
- Health dedupe ownership moved out of app runtime state into websocket-scoped `ws-client/websocket-health-projection-state`.
- Targeted UI freshness fallback paths were normalized to store `[:websocket :health]`.
- Added ADR: `/hyperopen/docs/architecture-decision-records/0017-websocket-canonical-state-authority.md`.
- Tightened reliability wording in `/hyperopen/docs/RELIABILITY.md`.

Compatibility shim status:

- `ws-client/connection-state` and `ws-client/stream-runtime` still exist for compatibility but are projection-only, derived from `ws-client/runtime-view`.

Validation evidence:

- `npm run check` passed.
- `npm test` passed.
- `npm run test:websocket` passed.

## Context and Orientation

In this repository, websocket runtime behavior is split across pure transition logic, an engine loop, an effect interpreter, a client seam, and startup watchers.

The pure reducer in `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` computes `{:state :effects}`. The engine in `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs` is the single writer of canonical runtime state. The interpreter in `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs` executes side effects and currently projects data into separate atoms.

Today, `/hyperopen/src/hyperopen/websocket/client.cljs` exposes multiple websocket atoms (`connection-state`, `stream-runtime`, `runtime-state`). Startup watchers in `/hyperopen/src/hyperopen/startup/watchers.cljs` observe those projection atoms and merge selected fields into app store state `[:websocket ...]`. Health synchronization in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` and `/hyperopen/src/hyperopen/websocket/health_runtime.cljs` uses additional dedupe state in `/hyperopen/src/hyperopen/runtime/state.cljs`.

For this plan, "canonical state" means the one state that owns correctness for websocket decisions and ordering. "Projection" means any derived snapshot used for UI rendering, diagnostics, or inter-module integration that never feeds authority back into reducer decisions.

## Plan of Work

### Milestone 1: Lock The Authority Contract With Failing Tests

Start by codifying authority expectations before moving code. Add focused tests that fail under current drift and pass once the migration is complete. The tests must prove three things: reducer/engine state is authoritative, projections are derived-only, and startup/store sync does not read from multiple websocket authority candidates.

This milestone updates websocket runtime tests and startup watcher tests first, then introduces temporary compatibility assertions so migration can proceed incrementally without breaking public client behavior.

### Milestone 2: Introduce A Single Websocket Runtime View Projection

Add one aggregated projection effect (for example `:fx/project-runtime-view`) emitted by reducer/runtime orchestration. The effect payload should contain the connection projection, stream projection, and projection fingerprints needed for dedupe. Interpret this effect into one websocket runtime view atom owned by the websocket client runtime.

Refactor runtime wiring in `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`, `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`, and `/hyperopen/src/hyperopen/websocket/client.cljs` so downstream consumers use this single projection source. Keep compatibility accessors for existing public behavior, but mark/structure them as projections derived from the new runtime view.

### Milestone 3: Migrate Watchers, Health Sync, And Store Projection Flow

Update startup websocket watchers to watch one runtime view projection instead of separate connection and stream atoms. Remove legacy projection merge logic and derive store `:websocket` and health updates from the unified projection transitions.

Move health fingerprint/write dedupe ownership out of `/hyperopen/src/hyperopen/runtime/state.cljs` and into websocket-scoped state used by health sync adapters. Update `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`, `/hyperopen/src/hyperopen/websocket/health_runtime.cljs`, and related tests so app runtime state is no longer a websocket authority side-channel.

### Milestone 4: Remove Ambiguous Fallbacks, Document Ownership, And Validate

Clean up view-level fallback patterns that imply multiple websocket health authorities, and standardize to store `[:websocket :health]` (or one explicit selector path) for UI rendering. Update diagnostics snapshot helpers so debug output clearly distinguishes canonical runtime state from projections.

Add an architecture decision record (next sequence in `/hyperopen/docs/architecture-decision-records/`) that defines canonical websocket state ownership, projection boundaries, and compatibility policy. Update `/hyperopen/docs/RELIABILITY.md` if wording needs to be explicit about projection ownership.

## Concrete Steps

From `/hyperopen`:

1. Add and run failing authority tests before refactoring implementation.

   - Update websocket runtime and watcher tests (`runtime`, `runtime_effects`, `watchers`, `health_runtime`, `client`) with explicit authority assertions.
   - Run:
     - `npm run test:websocket`

   Expected outcome: new tests fail first on authority drift, while existing websocket tests continue to show current baseline behavior.

2. Implement unified runtime view projection and interpreter wiring.

   - Edit websocket runtime reducer/runtime/effects/client modules to emit, interpret, and expose one runtime view projection.
   - Run:
     - `npm run test:websocket`

   Expected outcome: websocket tests pass with compatibility behavior intact and new authority tests green.

3. Migrate watcher and health sync ownership.

   - Edit startup watcher and runtime/effect adapter health sync paths.
   - Remove websocket health dedupe from app runtime state and migrate tests.
   - Run:
     - `npm run test:websocket`
     - `npm test`

   Expected outcome: no store projection regressions; diagnostics and health tests pass without app runtime websocket-health side-channel state.

4. Finish cleanup, docs, and required gates.

   - Remove or narrow ambiguous fallback code paths in views and debug snapshots.
   - Add/update ADR and reliability wording.
   - Run required gates:
     - `npm run check`
     - `npm test`
     - `npm run test:websocket`

   Expected outcome: all gates pass and architecture documentation reflects final authority model.

## Validation and Acceptance

Acceptance is complete when all conditions below are true.

1. Websocket canonical truth is one source: runtime engine state is authoritative for connection lifecycle, queue, subscriptions, stream health, and metrics.
2. Startup/store websocket sync consumes one explicit runtime view projection source and no longer merges separate legacy websocket projections.
3. Health dedupe state is websocket-scoped and not stored under `/hyperopen/src/hyperopen/runtime/state.cljs`.
4. UI freshness cues and diagnostics read websocket health from one normalized store path without ambiguous fallback authority behavior.
5. Existing websocket client API behaviors remain compatible for callers.
6. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

Each milestone is additive and can be rerun safely. If a migration step breaks behavior, keep the new authority tests and roll implementation back to the last passing commit while preserving the test contract. Compatibility shims should only be removed after tests prove no remaining internal call paths depend on them.

Because this refactor touches state ownership, avoid partial merges. Land milestones in small commits that each keep websocket tests green, and prefer temporary adapters over broad in-place rewrites.

## Artifacts and Notes

Primary files expected to change:

- `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
- `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`
- `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`
- `/hyperopen/src/hyperopen/websocket/client.cljs`
- `/hyperopen/src/hyperopen/startup/watchers.cljs`
- `/hyperopen/src/hyperopen/app/bootstrap.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
- `/hyperopen/src/hyperopen/websocket/health_runtime.cljs`
- `/hyperopen/src/hyperopen/runtime/state.cljs`
- `/hyperopen/src/hyperopen/views/trade_view.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`
- `/hyperopen/src/hyperopen/views/account_info/vm.cljs`
- `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`
- `/hyperopen/docs/RELIABILITY.md` (if ownership language needs tightening)
- `/hyperopen/docs/architecture-decision-records/0017-websocket-canonical-state-authority.md` (new)

Primary tests expected to change:

- `/hyperopen/test/hyperopen/startup/watchers_test.cljs`
- `/hyperopen/test/hyperopen/websocket/infrastructure/runtime_effects_test.cljs`
- `/hyperopen/test/hyperopen/websocket/application/runtime_test.cljs`
- `/hyperopen/test/hyperopen/websocket/client_test.cljs`
- `/hyperopen/test/hyperopen/websocket/health_runtime_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/websocket_diagnostics_test.cljs`
- `/hyperopen/test/hyperopen/runtime/state_test.cljs`

Implementation evidence to capture during execution:

- Before/after test evidence that projection atom mutation no longer acts as authority.
- Before/after watcher evidence that one projection source drives store websocket writes.
- Gate outputs for required commands.

## Interfaces and Dependencies

Public interfaces to preserve:

- `/hyperopen/src/hyperopen/websocket/client.cljs` external API behaviors (`init-connection!`, `disconnect!`, `force-reconnect!`, `send-message!`, `register-handler!`, health/metrics accessors).
- Runtime reducer and interpreter entrypoints (`step`, `interpret-effect!`) remain pure-effect boundaries.

Internal interface changes expected:

- Runtime context passed to interpreter should include one runtime-view atom projection target instead of separate connection/stream projection atoms.
- Startup websocket watcher dependency contract should consume one websocket runtime view source.
- Health sync adapter contract should accept websocket-owned fingerprint storage instead of app runtime `:websocket-health`.

No new external libraries are required.

Plan revision notes:

- 2026-02-25 19:14Z - Initial ExecPlan created to resolve websocket state-authority ambiguity by converging on one canonical runtime source and explicit projection boundaries.
- 2026-02-25 23:12Z - Implementation completed across runtime/view/watcher/health ownership paths with required gates passing.
