# Model the WebSocket Runtime Kernel in TLA+

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The primary live `bd` issue for this work is `hyperopen-0iyp`.

## Purpose / Big Picture

Hyperopen already has a pure websocket runtime reducer and strong websocket regression coverage, but it still lacks a machine-checked proof surface for the kernel that owns reconnects, stale-socket rejection, queue overflow behavior, subscription replay, and market-message coalescing. After this work, a contributor will be able to run an optional bounded TLA+ verification step against the canonical websocket runtime kernel and get a concrete failure if the reducer stops respecting those safety rules.

This is an internal correctness feature, not a UI feature. The visible result is a trustworthy verification workflow: one command runs the websocket TLA+ model, existing websocket tests still pass, and a small trace-backed conformance layer proves that the real reducer still matches the same state-machine assumptions that the TLA+ spec encodes.

## Progress

- [x] (2026-03-27 10:13 EDT) Created and claimed `bd` issue `hyperopen-0iyp` for the websocket-only TLA+ modeling track.
- [x] (2026-03-27 10:13 EDT) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/WORK_TRACKING.md`, and `/hyperopen/.agents/PLANS.md` before drafting this plan.
- [x] (2026-03-27 10:14 EDT) Audited `/hyperopen/src/hyperopen/websocket/BOUNDARY.md`, `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`, `/hyperopen/src/hyperopen/websocket/application/runtime/connection.cljs`, `/hyperopen/src/hyperopen/websocket/application/runtime/subscriptions.cljs`, `/hyperopen/src/hyperopen/websocket/application/runtime/market.cljs`, `/hyperopen/src/hyperopen/websocket/domain/model.cljs`, the websocket reducer tests, the runtime flight recorder, and the existing Lean-only formal tooling.
- [x] (2026-03-27 10:14 EDT) Created this active ExecPlan and froze scope to the canonical websocket runtime kernel only. Order-request Lean work, order-form Lean work, and effect-order formalization are explicitly out of scope for this ticket.
- [x] (2026-03-27 10:22 EDT) Ran `npm run lint:docs` after drafting this plan and confirmed the current docs guardrail failure is unrelated to this file: `/hyperopen/docs/exec-plans/active/2026-03-26-vault-trade-rate-limit-regression.md` is still in `active` with no unchecked progress items.
- [x] (2026-03-27 11:01 EDT) Added the repo-local TLA+ tooling boundary: `/hyperopen/tools/tla.clj`, `/hyperopen/dev/tla_tooling_test.clj`, `npm run tla:verify -- --spec websocket-runtime`, and `npm run test:tla-tooling` now exist, and the fast wrapper tests pass.
- [x] (2026-03-27 11:01 EDT) Added the first bounded websocket safety model under `/hyperopen/spec/tla/websocket_runtime.tla` plus `/hyperopen/spec/tla/websocket_runtime.cfg`, then iterated on TLC-facing encodings so the spec moved from parse/type failures into real state-space exploration.
- [x] (2026-03-27 11:01 EDT) Added trace-backed websocket conformance fixtures and tests in `/hyperopen/test/hyperopen/websocket/application/runtime_tla_trace_fixtures.cljs` and `/hyperopen/test/hyperopen/websocket/application/runtime_tla_conformance_test.cljs`, regenerated the test runner, and kept `npm run test:websocket` green.
- [x] (2026-03-27 11:48 EDT) Tightened the bounded safety model so `npm run tla:verify -- --spec websocket-runtime` now completes cleanly. The checked-in safety run currently explores 2,553 states / 1,090 distinct states to depth 5 with no invariant violations.
- [x] (2026-03-27 11:48 EDT) Landed and verified the focused liveness path under `/hyperopen/spec/tla/websocket_runtime_liveness.cfg`; `npm run tla:verify -- --spec websocket-runtime-liveness` now passes and checks the eventual connect and market-flush properties over the reduced liveness state space.
- [x] (2026-03-27 11:53 EDT) Added a reduced real browser startup fixture derived from a local headless `HYPEROPEN_DEBUG.flightRecording()` capture and wired it into websocket conformance coverage so at least one committed replay path is anchored to a real recorder payload, not only synthetic traces.
- [x] (2026-03-28 07:23 EDT) Captured final repo validation evidence with a locally provisioned `tla2tools.jar` via `TLA2TOOLS_JAR`, reran `npm run tla:verify -- --spec websocket-runtime`, `npm run tla:verify -- --spec websocket-runtime-liveness`, `npm run test:websocket`, `npm test`, `npm run check`, and `npm run lint:docs`, and confirmed this plan is ready to move to `completed`.

## Surprises & Discoveries

- Observation: the repository has a real Lean formal-tool workflow, but nothing equivalent yet for TLA+.
  Evidence: `/hyperopen/docs/tools.md` and `/hyperopen/tools/formal/README.md` only describe Lean-backed surfaces (`vault-transfer`, `order-request-standard`, `order-request-advanced`), and the repo currently contains no `spec/tla/**`, `.tla`, or TLC config files.

- Observation: the websocket runtime already exposes the exact normalized trace surface needed to calibrate a formal model.
  Evidence: `/hyperopen/src/hyperopen/websocket/flight_recorder.cljs` records normalized runtime messages, emitted effect batches, and drops, and `/hyperopen/docs/runbooks/telemetry-dev-observability.md` documents `HYPEROPEN_DEBUG.flightRecording()` and `HYPEROPEN_DEBUG.replayFlightRecording()`.

- Observation: stale runtime events are not literal “no effect” in the current reducer because the reducer always appends one projection effect at the end of a step.
  Evidence: `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` routes every branch through `result` -> `append-projections`, even for ignored stale-socket branches such as `:evt/socket-open`, `:evt/socket-close`, `:evt/socket-message`, `:evt/decoded-envelope`, and `:evt/parse-error`.

- Observation: health projection fingerprinting and freshness hysteresis are materially more stateful than the first TLA+ delivery needs.
  Evidence: `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` keeps `:health-projection-fingerprint`, `:health-projection-last-refresh-at-ms`, transport freshness pending state, and per-stream pending counters, while the highest-value reducer tests currently target queue bounds, stale-socket rejection, replay ordering, seq-gap tracking, timer uniqueness, and disconnect cleanup.

- Observation: `:evt/parse-error` can represent either stale socket parse fallout or non-socket normalization problems.
  Evidence: the reducer only rejects parse errors when a socket id is present and stale, while the completed safety split plan for `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` documents that non-socket parse failures still need to surface as dead-letter diagnostics.

- Observation: the repo had an unrelated active-plan guardrail failure during implementation, but it is now resolved.
  Evidence: the first `npm run lint:docs` run failed with `[active-exec-plan-no-unchecked-progress] docs/exec-plans/active/2026-03-26-vault-trade-rate-limit-regression.md - active ExecPlan has no remaining unchecked progress items; move it out of active`, while the final 2026-03-28 rerun of `npm run lint:docs` passes cleanly.

- Observation: TLC is sensitive to mixed-type sentinel encodings in a way the reducer is not.
  Evidence: the first implementation attempts that used string sentinels for optional numeric and record-shaped fields (`activeSocketId`, `lastSeq`, `marketPending`, `lastGap`) produced TLC parse/type/fingerprint failures, so the model had to switch to total encodings such as numeric sentinels and uniform record shapes before state exploration became meaningful.

- Observation: local repo validation in this worktree initially lacked `node_modules`, which masked the actual websocket test status until dependencies were installed from the lockfile.
  Evidence: the first `npm run test:websocket` attempt failed on missing `shadow-cljs`, and the direct `node out/ws-test.js` path failed on missing `@noble/secp256k1`; after `npm ci`, both `npm run test:websocket` and `npm test` completed successfully.

## Decision Log

- Decision: keep the websocket TLA+ track separate from `/hyperopen/tools/formal/**` instead of overloading the existing Lean wrapper.
  Rationale: the current formal tooling is explicitly Lean-backed in both docs and code. Mixing TLA+ into the same wrapper would raise conceptual complexity immediately and make failure modes harder to reason about. A small dedicated `tools/tla.clj` surface keeps backend boundaries honest.
  Date/Author: 2026-03-27 / Codex

- Decision: model the websocket runtime kernel, not the entire websocket subsystem.
  Rationale: the canonical proof surface is the pure reducer entrypoint (`initial-runtime-state` plus `step`) and the helper modules it composes. Browser listeners, socket interpreters, startup watchers, and UI consumers are downstream of that kernel and should remain ordinary tests or trace consumers.
  Date/Author: 2026-03-27 / Codex

- Decision: define stale-socket safety as observational no-op over kernel state and business-relevant effects, not as “the reducer emitted zero effects”.
  Rationale: the reducer always emits the runtime-view projection effect. The TLA+ model should therefore ignore projection-only noise and focus on whether stale socket generations can mutate connection state, stream state, queue state, market pending state, or outbound socket effects.
  Date/Author: 2026-03-27 / Codex

- Decision: use bounded logical time and finite payload domains in the TLA+ model instead of exact millisecond arithmetic and raw exchange payloads.
  Rationale: the reducer’s most important safety properties depend on timer coherence, ordering, and stale-versus-live distinctions, not on large timestamp ranges or full payload shapes. A bounded logical clock plus abstract payload slices will keep TLC tractable.
  Date/Author: 2026-03-27 / Codex

- Decision: calibrate the TLA+ model against reducer tests and captured normalized traces, but do not build a TLA+-to-CLJS code generator in v1.
  Rationale: Hyperopen already has reducer tests and a runtime flight recorder. Reusing those seams is the cheapest reliable way to keep the spec and implementation aligned without introducing a second export pipeline beside the Lean-generated vector workflow.
  Date/Author: 2026-03-27 / Codex

- Decision: keep proof execution optional in normal development, but make wrapper tests cheap enough to run in `npm run check`.
  Rationale: this should mirror the current Lean approach. Fast Babashka tests that validate wrapper argument parsing and failure messaging are cheap enough for normal development, while the actual TLC run remains optional and explicit.
  Date/Author: 2026-03-27 / Codex

- Decision: use total, finite encodings inside the TLA+ model even when the reducer uses richer map-or-nil shapes.
  Rationale: TLC handled the reducer-aligned semantics reliably only after optional numeric fields used numeric sentinels and gap/pending state used uniform shapes. This keeps the safety model tractable without weakening the reducer-side conformance tests that still exercise the richer CLJS structures.
  Date/Author: 2026-03-27 / Codex

- Decision: keep the checked-in safety config intentionally small for tractability and let reducer tests plus conformance traces carry the wider behavioral surface.
  Rationale: the purpose of this first config is to give contributors a bounded, runnable TLC safety pass. Smaller finite domains are acceptable here because the reducer suite and conformance fixtures still cover the full-domain examples humans care about.
  Date/Author: 2026-03-27 / Codex

## Outcomes & Retrospective

This work is complete. The repo now contains the dedicated TLA+ wrapper, the bounded websocket safety model, the focused liveness config, and a trace-backed conformance namespace that exercises the modeled rules against the real reducer. Contributors can run the cheap wrapper tests in normal repo validation, inspect the bounded TLA+ spec directly, and execute the explicit TLC passes when they need machine-checked websocket runtime evidence.

Final close-out validation on 2026-03-28 passed end to end in a provisioned local environment: `npm run tla:verify -- --spec websocket-runtime`, `npm run tla:verify -- --spec websocket-runtime-liveness`, `npm run test:websocket`, `npm test`, `npm run check`, and `npm run lint:docs` all completed successfully. The safety TLC run finished with 2,553 generated states / 1,090 distinct states to depth 5, and the liveness TLC run finished with 4 generated states / 4 distinct states to depth 2; both logs remain under `/hyperopen/target/tla/**`. The conformance layer includes a reduced startup path derived from a real `HYPEROPEN_DEBUG.flightRecording()` capture, so the model is anchored to both bounded state exploration and reducer-level execution evidence.

## Context and Orientation

The websocket runtime kernel in this repository is the pure transition function that takes the current runtime state plus one normalized runtime message and returns the next runtime state plus a batch of explicit runtime effects. In Hyperopen that kernel lives at `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`, and its stable public seams are `initial-runtime-state` and `step`.

The reducer is already partially decomposed into pure helper modules:

- `/hyperopen/src/hyperopen/websocket/application/runtime/connection.cljs` owns connect eligibility, queue overflow handling, retry timer scheduling, and health/watchdog timer flag helpers.
- `/hyperopen/src/hyperopen/websocket/application/runtime/subscriptions.cljs` owns desired-subscription state, per-stream subscribe and unsubscribe state, message counts, and sequence-gap tracking.
- `/hyperopen/src/hyperopen/websocket/application/runtime/market.cljs` owns the bounded market coalesce buffer and market flush timer cleanup.
- `/hyperopen/src/hyperopen/websocket/domain/model.cljs` defines normalized runtime messages and effects, domain subscription keys, topic groups, and market coalesce keys.

The highest-value existing regression anchors are in `/hyperopen/test/hyperopen/websocket/application/runtime_reducer_test.cljs`. Those tests already prove reducer determinism, queue overflow behavior, timer uniqueness, stale decoded and parse event rejection, replay ordering, sequence-gap semantics, and disconnect/offline/force-reconnect cleanup. This plan must preserve those tests and use them as the human-readable implementation anchor for whatever the TLA+ model claims.

The repository also already has a websocket runtime flight recorder. `/hyperopen/src/hyperopen/websocket/flight_recorder.cljs` records normalized runtime messages and effect batches, and `/hyperopen/docs/runbooks/telemetry-dev-observability.md` documents how developers can capture and replay those traces through `HYPEROPEN_DEBUG`. That trace seam matters because it gives this plan a realistic way to compare the TLA+ state machine against real reducer traffic without inventing a second event normalization layer.

TLA+ is a formal specification language for state machines. In this plan, the TLA+ file describes the websocket runtime state variables and transition actions in a small, finite, fully explicit form. TLC is the model checker that executes that spec over a bounded configuration and fails if an invariant or liveness property is violated. This plan uses TLA+ and TLC only for the websocket runtime kernel. It does not use TLA+ for UI code, transport interpreters, browser lifecycle wiring, or Lean-backed business kernels.

The current repo state matters. Formal tooling under `/hyperopen/tools/formal/**` is Lean-only today, and `/hyperopen/docs/tools.md` documents it that way. This websocket track must not silently widen that contract. If we add TLA+ tooling, it must be explicit, optional, and documented as a separate path.

The intended proof surface is narrow:

- connection lifecycle transitions (`:disconnected`, `:connecting`, `:connected`, `:reconnecting`)
- socket generation ownership via `:active-socket-id`
- retry timer, health tick timer, watchdog timer, and market flush timer coherence
- bounded outbound queue behavior
- desired subscription replay ordering
- per-stream sequence-gap tracking and reset behavior
- market-message coalescing and flush behavior
- disconnect, offline, and force-reconnect cleanup behavior

The intended non-goals are equally important:

- do not formalize `:health-projection-fingerprint` contents or projection dedupe internals in v1
- do not formalize browser lifecycle listener installation, socket parser implementation, or runtime interpreter side effects
- do not formalize user-specific websocket adapter logic in `/hyperopen/src/hyperopen/websocket/user.cljs`
- do not attempt to formalize the whole app runtime or to merge this TLA+ track into the Lean vector-export pipeline

## Plan of Work

### Milestone 1: Add an optional TLA+ tooling boundary and verification command

This milestone creates the repo-local tool entry point for the websocket TLA+ track without touching the existing Lean wrapper. Add `/hyperopen/tools/tla.clj` as a small Babashka wrapper that supports one explicit command such as `verify --spec websocket-runtime`. The wrapper should look for the TLA+ tools jar in `TLA2TOOLS_JAR` first and a conventional repo-local path such as `/hyperopen/tools/tla/vendor/tla2tools.jar` second. If neither exists, it must fail fast with an explicit repair message and must not touch tracked files.

Add a fast Babashka test namespace such as `/hyperopen/dev/tla_tooling_test.clj` that covers argument parsing, missing-tool failure messaging, invalid spec ids, and the guarantee that the wrapper writes transient TLC output only under `/hyperopen/target/tla/**`. Wire that test into `package.json` as `npm run test:tla-tooling`. The actual TLC run must remain optional and explicit, but the wrapper tests are cheap enough to join `npm run check`.

Document the new tooling boundary in `/hyperopen/docs/tools.md`. Update `/hyperopen/src/hyperopen/websocket/BOUNDARY.md` so websocket contributors can see the optional TLA+ verification command next to `npm run test:websocket`. If the final wording in `/hyperopen/docs/RELIABILITY.md` would benefit from a short pointer to the new TLA+ kernel spec, add one short sentence there; do not duplicate the full invariant list across documents.

Milestone 1 is complete when the repo has a dedicated websocket TLA+ command surface, the fast wrapper tests pass without TLA+ installed, and the Lean formal workflow remains unchanged.

### Milestone 2: Author a bounded TLA+ safety model for the websocket runtime kernel

This milestone creates the first real model. Add `/hyperopen/spec/tla/websocket_runtime.tla` and at least one TLC config file under `/hyperopen/spec/tla/`. The model should represent the reducer’s kernel state with small finite variables that map directly back to reducer state fields:

- connection status and attempt count
- whether a websocket URL is configured
- socket generation counter and current active socket generation
- `online`, `hidden`, and `intentional close` flags
- bounded outbound queue
- desired subscriptions
- per-stream runtime facts needed for first payload, last payload, last sequence, and sequence-gap tracking
- retry timer state and due time
- health tick active flag
- watchdog active flag
- market flush active flag and pending coalesced market messages
- last-activity age or bounded logical clock state
- last-close metadata in whatever reduced form is needed for modeled properties
- the last emitted business-relevant effects for the current step

The model should use only normalized payload slices, not full exchange payloads. For outbound messages, model only the fields that affect queueing and desired-subscription intent: method plus subscription descriptor. For inbound envelopes, model only topic, tier, sequence number, coalesce key, and whether the message belongs to the active or a stale socket generation. Do not attempt to model full Hyperliquid payload structure in TLA+.

The first safety invariants to encode are:

- `QueueBounded`: the queue length never exceeds configured capacity.
- `ActiveSocketAuthority`: stale socket events cannot mutate kernel state or produce business-relevant socket effects.
- `RetryTimerCoherence`: retry timer active state and retry due time stay coherent.
- `HealthTickUniqueness`: initialization and reconnect paths do not create duplicate health tick timers.
- `WatchdogUniqueness`: initialization paths do not create duplicate watchdog timers.
- `MarketFlushUniqueness`: only one market flush timer can be active while pending market payloads exist.
- `MarketFlushClearsPending`: flushing market pending state empties the pending buffer and clears the flush-active flag.
- `SubscriptionReplayOrder`: on socket open, desired subscriptions replay in deterministic domain-key order before queued messages, and the queue clears afterward.
- `SeqGapSemantics`: a sequence gap is recorded only when the new sequence number jumps past `lastSeq + 1`, and resubscribe resets sequence-gap state.
- `DisconnectClearsVolatileState`: disconnect, offline, and force-reconnect paths clear retry state, market pending state, and active socket ownership consistently.
- `IntentionalCloseSuppressesRetry`: intentional disconnect paths do not schedule reconnect retries.

This milestone must keep the model small on purpose. Exclude projection fingerprints, transport freshness hysteresis internals, and browser-only concerns even if they remain ordinary reducer tests elsewhere. The goal is a durable kernel proof surface, not a full clone of every reducer field.

Milestone 2 is complete when TLC can explore the bounded safety model without invariant violations and when the model structure is clearly traceable back to the reducer fields and helper modules above.

### Milestone 3: Calibrate the model against reducer anchors and real normalized traces

This milestone keeps the TLA+ model honest. First, cross-check the model against `/hyperopen/test/hyperopen/websocket/application/runtime_reducer_test.cljs` and update that suite if the spec names an invariant that still lacks a readable reducer-level regression anchor. The TLA+ model should not become the only place that knows a rule exists.

Second, use the flight recorder to capture a few real normalized runtime traces in a dev build. Capture at least one clean connect-and-open trace and one reconnect or stale-socket trace. Turn those captures into small curated fixtures under a websocket test namespace such as:

- `/hyperopen/test/hyperopen/websocket/application/runtime_tla_trace_fixtures.cljs`
- `/hyperopen/test/hyperopen/websocket/application/runtime_tla_conformance_test.cljs`

These fixtures do not need to be generated from TLA+. In v1 they only need to replay the same normalized messages through the real reducer and assert the narrow state facts that the TLA+ model claims: stale socket rejection, replay ordering, market flush cleanup, retry suppression, and sequence-gap reset behavior. If a captured trace exposes a mismatch between the reducer and the modeled rules, add the trace or a reduced reproducer as a test before changing either side.

Milestone 3 is complete when the modeled rules are grounded in both reducer unit tests and at least one real normalized trace path.

### Milestone 4: Add bounded liveness checks and finish docs and validation

After the safety model is stable, add a bounded liveness pass. The two target properties are:

- when a websocket URL is configured, the runtime is online, the close is not intentional, and no active socket exists, the model eventually emits a connect request
- when coalesced market payloads are pending and no disconnect or offline transition intervenes, the model eventually flushes those pending payloads

Liveness should use the smallest fairness assumptions that make the reducer contract meaningful. Prefer fairness on internal timer-fire and connect-request actions, not on arbitrary external environment actions. If TLC state-space growth makes one shared config impractical, split safety and liveness into separate config files and document why. Do not weaken safety invariants to make liveness cheaper.

Finish the documentation pass here. `/hyperopen/docs/tools.md` must show the websocket TLA+ command. `/hyperopen/src/hyperopen/websocket/BOUNDARY.md` should mention when to run it. If `/hyperopen/docs/RELIABILITY.md` is updated, keep the pointer short and specific. Record all tool and modeling decisions back into this ExecPlan as they are made.

Milestone 4 is complete when the safety and liveness commands both pass in a correctly provisioned local environment, websocket conformance tests pass, and the usual repo gates remain green.

## Concrete Steps

From `/Users/barry/projects/hyperopen`:

1. Confirm the websocket reducer baseline before adding tooling or model files.

       npm run test:websocket

   Expect the websocket suite to pass before any formal-model work begins. If it does not, stop and fix baseline websocket regressions first.

2. Add the TLA+ wrapper and wrapper tests.

       npm run test:tla-tooling

   Expected outcome: the fast Babashka wrapper tests pass even on a machine that does not have TLA+ tools installed, and missing-tool failures point to `TLA2TOOLS_JAR` or the conventional repo-local jar path instead of failing with a generic Java exception.

3. Author the safety model and run the bounded safety check explicitly.

       npm run tla:verify -- --spec websocket-runtime

   Expected outcome in a provisioned environment: TLC completes with no invariant violations and no unexpected deadlock report for the safety configuration, and it writes transient artifacts only under `/hyperopen/target/tla/**`.

4. Capture and reduce at least two flight recordings after the safety model exists.

       npm run dev

   Then, in the browser console on a dev build:

       HYPEROPEN_DEBUG.clearFlightRecording()
       // reproduce one clean connect/open/replay flow
       HYPEROPEN_DEBUG.flightRecording()
       HYPEROPEN_DEBUG.replayFlightRecording()

   Repeat for one reconnect or stale-socket flow, reduce the traces into committed websocket fixtures, and add the reducer-level conformance assertions described in Milestone 3.

5. Run the full validation slice after tool, spec, docs, and tests are in place.

       npm run test:websocket
       npm test
       npm run check
       npm run tla:verify -- --spec websocket-runtime

   If the TLC run is intentionally excluded from normal `check`, keep it as an explicit final command and record the reason in this plan.

## Validation and Acceptance

This work is complete only when all of the following are true:

1. The repo has a dedicated websocket TLA+ verification command that is documented and does not disturb the existing Lean formal workflow.
2. TLC can explore the bounded websocket runtime safety model without invariant violations.
3. The model includes explicit checks for queue bounds, stale-socket authority, timer coherence, replay ordering, sequence-gap semantics, market flush cleanup, and disconnect cleanup.
4. At least one bounded liveness pass succeeds for eventual connect and eventual market flush behavior, or the plan records a clearly justified split configuration that makes those checks tractable.
5. The reducer still has readable websocket regression anchors for every rule the TLA+ model claims.
6. A trace-backed conformance namespace replays reduced normalized runtime traces and proves that the real reducer still matches the modeled assumptions.
7. `npm run test:websocket`, `npm test`, and `npm run check` all pass.

The acceptance bar is behavioral, not just structural. It is not enough to add `.tla` files. A contributor must be able to run the websocket verification command, see TLC complete cleanly, and understand which reducer behaviors that result is actually proving.

## Idempotence and Recovery

All tooling and model-checking steps in this plan must be safe to rerun. The wrapper should write transient output only under `/hyperopen/target/tla/**` so a failed TLC run can be retried without cleaning tracked files. The TLA+ command must be read-only with respect to tracked repo files.

If the model checker explodes in state count, shrink the bounded config first. Reduce domain cardinalities, step limits, or fairness scope before weakening invariants. Record every such change in `Decision Log` so the next contributor understands exactly what the model is and is not proving.

If a reducer mismatch appears, do not silently “fix” the model to match current behavior. First capture the mismatch as a reducer regression test or a reduced trace fixture, then decide whether the reducer is wrong, the model is wrong, or the claimed invariant was too broad.

## Artifacts and Notes

Expected tracked files for the implementation phase:

- `/hyperopen/docs/exec-plans/completed/2026-03-27-websocket-runtime-tla-model.md`
- `/hyperopen/spec/tla/websocket_runtime.tla`
- `/hyperopen/spec/tla/websocket_runtime.cfg` and, if needed, a separate liveness config
- `/hyperopen/tools/tla.clj`
- `/hyperopen/dev/tla_tooling_test.clj`
- `/hyperopen/docs/tools.md`
- `/hyperopen/src/hyperopen/websocket/BOUNDARY.md`
- `/hyperopen/test/hyperopen/websocket/application/runtime_tla_trace_fixtures.cljs`
- `/hyperopen/test/hyperopen/websocket/application/runtime_tla_conformance_test.cljs`
- `/hyperopen/test/hyperopen/websocket/application/runtime_reducer_test.cljs` when additional reducer anchors are needed

Expected transient artifacts:

- `/hyperopen/target/tla/**`

Evidence worth capturing while implementing:

- one successful TLC run transcript for the safety model
- one successful TLC run transcript for the liveness model or liveness config
- one reduced trace fixture derived from `HYPEROPEN_DEBUG.flightRecording()`
- reducer test output that proves the human-readable anchors still pass

## Interfaces and Dependencies

Stable interfaces that must remain stable through this work:

- `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
  - `initial-runtime-state`
  - `step`
- `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`
  - runtime startup and publish seams
- `/hyperopen/src/hyperopen/websocket/client.cljs`
  - public websocket client accessors and debug trace helpers

New interface to add:

- `/hyperopen/tools/tla.clj`
  - explicit wrapper for websocket TLA+ verification

External dependency assumptions:

- Java is available locally to run TLC.
- The TLA+ tools jar is supplied through `TLA2TOOLS_JAR` or a conventional repo-local jar path.
- No Docker, remote service, or browser-only workflow is required to execute the model checker.

Do not introduce these dependencies in this ticket:

- no changes to `/hyperopen/tools/formal/**` beyond docs that point to the separate TLA+ track
- no TLA+-driven code generation pipeline
- no new mutable runtime authority outside the existing websocket reducer and engine loop

Plan revision note: 2026-03-27 10:14 EDT - Initial plan created after auditing the websocket runtime kernel, websocket reducer tests, runtime flight recorder, and the existing Lean-only formal-tooling surface. This revision intentionally scopes the work to a websocket-only TLA+ track with separate tooling, bounded model size, and trace-backed reducer alignment.
Plan revision note: 2026-03-27 10:22 EDT - Updated the living sections after a narrow docs validation attempt confirmed the current docs guardrail failure is unrelated to this websocket plan and comes from another active ExecPlan with no unchecked progress items.
Plan revision note: 2026-03-27 11:01 EDT - Updated after implementation began: the repo now has the dedicated TLA wrapper, docs pointers, bounded websocket safety model, regenerated conformance tests, passing `npm run test:tla-tooling`, passing `npm run test:websocket`, and passing `npm test`. TLC/model tuning remains open, and `npm run check` is still blocked by the unrelated active-plan docs guardrail noted above.
Plan revision note: 2026-03-27 11:53 EDT - Updated after the next implementation slice: the bounded safety TLC run now passes, the focused liveness config now passes, docs now point to both verification commands, and the websocket conformance suite includes a reduced startup fixture derived from a real local `HYPEROPEN_DEBUG.flightRecording()` capture. The only remaining blocker to completion is the unrelated repo-wide docs guardrail.
Plan revision note: 2026-03-28 07:23 EDT - Final validation rerun complete in a provisioned local environment: `npm run tla:verify -- --spec websocket-runtime`, `npm run tla:verify -- --spec websocket-runtime-liveness`, `npm run test:websocket`, `npm test`, `npm run check`, and `npm run lint:docs` all passed, so the final unchecked progress item is resolved and this plan can move to `completed`.
