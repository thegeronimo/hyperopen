# Runtime Flight Recorder v1 (Capture, Redacted Export, Deterministic Replay)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Hyperopen already has strong runtime architecture and targeted websocket tests, but intermittent live issues are still expensive to reproduce because we do not have a canonical way to capture and replay reducer inputs from real sessions. This plan adds a bounded Runtime Flight Recorder that captures normalized runtime messages and emitted effects, exposes a redacted export path for support/debug workflows, and provides deterministic replay utilities that turn production traces into local regression tests.

After this work, a developer can reproduce websocket/runtime behavior from a captured trace, inspect a redacted flight recording from the console, and verify that replay produces deterministic state/effect transitions.

## Progress

- [x] (2026-02-24 01:41Z) Reviewed architecture/runtime/telemetry constraints and mapped concrete seams for a recorder-first implementation.
- [x] (2026-02-24 01:41Z) Authored this ExecPlan with scoped milestones, acceptance criteria, and validation commands.
- [x] (2026-02-24 01:44Z) Implemented `/hyperopen/src/hyperopen/websocket/flight_recorder.cljs` with bounded capture, redacted snapshot/export, runtime-message extraction, and deterministic replay helper; added tests in `/hyperopen/test/hyperopen/websocket/application/flight_recorder_test.cljs`.
- [x] (2026-02-24 01:45Z) Integrated recorder hooks into `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs` and `/hyperopen/src/hyperopen/websocket/application/runtime.cljs` with optional callbacks and runtime-session metadata initialization.
- [x] (2026-02-24 01:45Z) Exposed recorder controls/snapshots in `/hyperopen/src/hyperopen/websocket/client.cljs` and `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`; added engine and client regression coverage.
- [x] (2026-02-24 01:46Z) Updated `/hyperopen/docs/runbooks/telemetry-dev-observability.md` with flight recorder usage and redacted export guidance.
- [x] (2026-02-24 01:46Z) Ran required repository gates (`npm run check`, `npm test`, `npm run test:websocket`) with all commands passing.

## Surprises & Discoveries

- Observation: Runtime reducer and effect interpreter seams are already explicit and pure enough to support capture/replay with minimal code intrusion.
  Evidence: `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs` and `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` already model `msg -> {:state :effects}` transitions deterministically.
- Observation: Dev telemetry already documents an explicit “support capture mode” follow-up, so recorder work aligns with current roadmap intent.
  Evidence: `/hyperopen/docs/runbooks/telemetry-dev-observability.md` “Suggested Production Strategy (Next Step)” section.
- Observation: Existing diagnostics sanitization logic can be reused for recorder export redaction, reducing risk of inconsistent masking rules.
  Evidence: `/hyperopen/src/hyperopen/websocket/diagnostics_sanitize.cljs`.
- Observation: Redaction treats `:session` as sensitive and redacts the entire session payload, not just nested fields.
  Evidence: Initial recorder redaction test failed until assertion was aligned to expect `:session` -> `"<redacted>"`.

## Decision Log

- Decision: Deliver v1 as a bounded in-memory recorder wired behind optional callbacks in runtime engine, not as a transport rewrite.
  Rationale: This preserves websocket runtime invariants and minimizes regression risk while still unlocking deterministic replay and redacted exports.
  Date/Author: 2026-02-24 / Codex
- Decision: Capture normalized runtime messages and emitted runtime effects as the canonical trace surface.
  Rationale: Those boundaries are stable, deterministic, and sufficient to replay reducer behavior without storing raw socket internals.
  Date/Author: 2026-02-24 / Codex
- Decision: Reuse diagnostics sanitization for recorder export redaction.
  Rationale: A single masking/redaction policy lowers security drift and implementation duplication.
  Date/Author: 2026-02-24 / Codex
- Decision: Keep runtime recorder enabled by default only in dev (`goog.DEBUG`) via websocket client config.
  Rationale: This preserves conservative production behavior while making recorder tooling immediately available for local debugging.
  Date/Author: 2026-02-24 / Codex
- Decision: Include dispatch enqueue/interpreter failures as `:runtime/drop` events.
  Rationale: Drop visibility is a core reliability gap; recording these failures gives immediate signal in captures/replays.
  Date/Author: 2026-02-24 / Codex

## Outcomes & Retrospective

Runtime Flight Recorder v1 is implemented end-to-end. The runtime now records normalized messages/effects/drops in a bounded in-memory log, captures runtime session metadata at start, and exposes snapshot/redacted/clear/replay APIs via websocket client and console preload debug globals.

The replay helper provides deterministic reducer re-execution for captured runtime messages, making it possible to turn live traces into reproducible local diagnostics. Documentation now includes concrete console commands for capture, replay, and redacted export.

All required gates passed after implementation: `npm run check`, `npm test`, and `npm run test:websocket`.

## Context and Orientation

Hyperopen websocket runtime behavior is orchestrated through three main layers:

1. `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs` runs two `core.async` loops: one reducer loop that applies `msg -> state/effects`, and one interpreter loop that executes effects.
2. `/hyperopen/src/hyperopen/websocket/application/runtime.cljs` builds runtime dependencies and owns public runtime start/publish/stop seams.
3. `/hyperopen/src/hyperopen/websocket/client.cljs` is the stable app-facing manager that starts runtime, exposes status helpers, and powers dev tooling snapshots through `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`.

In this plan, “flight recording” means a bounded, append-only event log of normalized runtime messages and emitted runtime effects, plus session metadata (start time, config snapshot, drop counters). “Deterministic replay” means applying the same message sequence to the same reducer and initial state and observing reproducible end state/effects.

## Plan of Work

### Milestone 1: Recorder Core + Replay Utility

Add `/hyperopen/src/hyperopen/websocket/flight_recorder.cljs` with pure helpers and recorder state management:

- bounded event append with drop counting,
- session start/reset and snapshot APIs,
- redacted export API using diagnostics sanitize helpers,
- deterministic replay helper that consumes recorded runtime messages and a reducer function.

Add `/hyperopen/test/hyperopen/websocket/application/flight_recorder_test.cljs` covering capacity behavior, redaction behavior, and replay determinism.

### Milestone 2: Runtime Capture Hooks

Update `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs` to accept optional recorder callbacks:

- record normalized runtime message ingress,
- record emitted effects associated with each message,
- record enqueue/drop failures when dispatch cannot enqueue.

Update `/hyperopen/src/hyperopen/websocket/application/runtime.cljs` to initialize recorder session metadata at runtime start and pass callback hooks into engine. Keep `publish-command!`, `publish-transport-event!`, and `stop-runtime!` signatures unchanged.

Add focused engine/runtime tests in websocket application test namespaces to verify callback invocation and no behavior regression when callbacks are absent.

### Milestone 3: Client + Console Debug API Surface

Extend `/hyperopen/src/hyperopen/websocket/client.cljs` with recorder lifecycle helpers:

- create recorder when enabled in config (default dev-on),
- return raw snapshot,
- return redacted snapshot,
- clear recorder,
- replay current recording summary against runtime reducer.

Extend `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` debug API with recorder endpoints (`flightRecording`, `flightRecordingRedacted`, `clearFlightRecording`, replay helper output).

Ensure existing snapshot API remains backward compatible.

### Milestone 4: Validation + Documentation Touch-up

Run required gates and record results in this plan. Add concise documentation updates in `/hyperopen/docs/runbooks/telemetry-dev-observability.md` describing how to capture and replay a flight recording in dev.

## Concrete Steps

From `/Users//projects/hyperopen`:

1. Implement Milestone 1 recorder module and tests.

   cd /Users//projects/hyperopen
   rg -n "diagnostics-sanitize|runtime_engine|runtime-reducer" src/hyperopen test/hyperopen
   npm test -- --help

   Expected indicator: new recorder tests compile and pass; redaction and replay behaviors are directly asserted.
   Result: completed (2026-02-24 01:44Z); recorder module and tests added, including bounded capacity, redaction behavior, and replay determinism assertions.

2. Implement Milestone 2 runtime hooks and runtime integration.

   cd /Users//projects/hyperopen
   npm test
   npm run test:websocket

   Expected indicator: existing websocket runtime invariants stay green, and new capture-hook tests pass.
   Result: completed (2026-02-24 01:45Z); runtime engine/runtime wiring added and validated by new `/hyperopen/test/hyperopen/websocket/application/runtime_engine_test.cljs`.

3. Implement Milestone 3 client/console APIs and dev workflow support.

   cd /Users//projects/hyperopen
   npm test

   Expected indicator: console-preload snapshot tests (where applicable) and websocket/client tests pass with new recorder APIs present.
   Result: completed (2026-02-24 01:45Z); client APIs (`get-flight-recording*`, `clear`, `replay`) and console debug methods (`flightRecording*`, `downloadFlightRecording`, replay helper) are now wired and covered.

4. Run required validation gates.

   cd /Users//projects/hyperopen
   npm run check
   npm test
   npm run test:websocket

   Expected indicator: all commands exit successfully.
   Result: completed (2026-02-24 01:46Z); all required commands passed.

This section will be updated with concrete run outcomes as milestones complete.

## Validation and Acceptance

Acceptance criteria for this plan:

1. Recorder core exists at `/hyperopen/src/hyperopen/websocket/flight_recorder.cljs` with bounded storage, snapshot, redacted export, and replay functions.
2. Websocket runtime engine records normalized runtime message and effect transitions when recorder callbacks are provided, and remains behaviorally unchanged when callbacks are absent.
3. Client/debug surfaces expose recorder snapshot/redacted snapshot/clear/replay endpoints without breaking existing APIs.
4. At least one deterministic replay test proves a captured message trace reproduces expected state/effect outcomes.
5. Required validation gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

This plan is additive and safe to rerun. Recorder state is in-memory and resettable, so repeated test runs and runtime restarts do not require migration or data cleanup.

If a regression appears during runtime hook integration, disable recorder callback wiring first (while keeping core recorder module/tests) to restore baseline runtime behavior, then reintroduce hooks incrementally with targeted tests.

If console API additions cause preload/runtime issues, keep recorder functionality accessible through websocket client functions and temporarily remove preload aliases until fixed.

## Artifacts and Notes

Recorder event shape target (v1):

    {:seq 1
     :kind :runtime/msg | :runtime/effects | :runtime/drop
     :at-ms 1700000000000
     :msg-type :evt/socket-message
     :payload {...}}

Replay summary target (v1):

    {:message-count 120
     :step-count 120
     :final-state {...}
     :trace [{:msg-type :evt/socket-open :effect-types [:fx/project-connection-state ...]} ...]}

## Interfaces and Dependencies

Target interfaces at completion:

- In `/hyperopen/src/hyperopen/websocket/flight_recorder.cljs`:

    (defn create-recorder [{:keys [capacity now-ms]}] ...)
    (defn start-session! [recorder session-metadata] ...)
    (defn clear-recorder! [recorder] ...)
    (defn record-runtime-msg! [recorder msg] ...)
    (defn record-runtime-effects! [recorder msg effects] ...)
    (defn record-runtime-drop! [recorder payload] ...)
    (defn snapshot [recorder] ...)
    (defn redacted-snapshot [recorder] ...)
    (defn replay-runtime-messages [{:keys [recording reducer initial-state]}] ...)

- In `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs`, `start-engine!` must support optional callbacks:

    :record-runtime-msg!
    :record-runtime-effects!
    :record-runtime-drop!

- In `/hyperopen/src/hyperopen/websocket/client.cljs`, expose stable helpers:

    (defn get-flight-recording [] ...)
    (defn get-flight-recording-redacted [] ...)
    (defn clear-flight-recording! [] ...)
    (defn replay-flight-recording [] ...)

Dependencies to preserve:

- Keep `/hyperopen/src/hyperopen/websocket/client.cljs` existing public connection APIs unchanged.
- Keep reducer purity and runtime single-writer invariants from `/hyperopen/docs/RELIABILITY.md`.
- Keep redaction behavior aligned with `/hyperopen/src/hyperopen/websocket/diagnostics_sanitize.cljs`.

Plan revision note: 2026-02-24 01:41Z - Initial plan created for Runtime Flight Recorder v1 implementation with milestones for recorder core, runtime hooks, client/debug API exposure, and validation gates.
Plan revision note: 2026-02-24 01:46Z - Completed implementation: recorder core, runtime hooks, client/console APIs, documentation updates, and passing validation gates.
