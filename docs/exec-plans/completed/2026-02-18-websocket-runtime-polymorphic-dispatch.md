# Refactor WebSocket Runtime Dispatch To Polymorphic Handlers

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The websocket runtime currently routes core behavior with large `case` statements in three critical places: runtime message reduction, runtime effect interpretation, and command/transport event normalization. This creates high merge pressure and makes safe extension harder because every new message/effect type edits a central switch.

After this refactor, those branches become polymorphic dispatch handlers keyed by `:msg/type`, `:fx/type`, `:op`, and `:event/type`. Behavior remains identical, but the extension seam becomes additive: adding a new type mostly means adding one handler instead of editing a giant switch body. You can validate no behavior regressions by running the websocket tests and required quality gates.

## Progress

- [x] (2026-02-18 02:30Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/RELIABILITY.md`, and `/hyperopen/docs/QUALITY_SCORE.md` for runtime invariants and required gates.
- [x] (2026-02-18 02:30Z) Audited current branch-heavy hotspots in `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`, `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`, and `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`.
- [x] (2026-02-18 02:30Z) Authored this active ExecPlan with concrete implementation and validation steps.
- [x] (2026-02-18 02:33Z) Refactored reducer `step` message branching into multimethod runtime message handlers while preserving the public `step` API.
- [x] (2026-02-18 02:34Z) Refactored runtime effect interpretation into multimethod effect handlers while preserving the public `interpret-effect!` API.
- [x] (2026-02-18 02:34Z) Refactored runtime command/event normalization into dispatch registries keyed by `:op` and `:event/type`.
- [x] (2026-02-18 02:36Z) Ran required validation gates (`npm run check`, `npm test`, `npm run test:websocket`) with all tests passing.
- [x] (2026-02-18 02:36Z) Updated this living plan with final outcomes and prepared it to move to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: Runtime branches already encode deterministic fallback behavior for unknown message/effect payloads (`:evt/parse-error`, `:fx/dead-letter`, or `nil`) and tests assert these boundaries.
  Evidence: `/hyperopen/test/hyperopen/websocket/infrastructure/runtime_effects_test.cljs` includes an unknown effect assertion; runtime reducer and runtime normalization fallbacks are explicit in current source.
- Observation: `npm test -- --namespace <ns>` is not supported by this repository's Node test runner; it reports unknown args and still executes the full suite.
  Evidence: Command output included `Unknown arg: --namespace` and then ran all tests (`1093` tests, `4961` assertions, `0` failures).

## Decision Log

- Decision: Use ClojureScript multimethod-based polymorphism for `:msg/type` and `:fx/type` runtime dispatch.
  Rationale: Multimethods directly express keyword-dispatched behavior and provide additive extension points while preserving local branch logic and deterministic semantics.
  Date/Author: 2026-02-18 / Codex
- Decision: Use explicit dispatch maps for command `:op` and transport `:event/type` normalization handlers.
  Rationale: These mappings are pure translation tables where data-driven registries are simpler than additional multimethod definitions while still eliminating central switch statements.
  Date/Author: 2026-02-18 / Codex
- Decision: Keep validation to required gate commands after discovering namespace filtering is unsupported.
  Rationale: This guarantees complete behavioral coverage for the refactor and aligns with repository-required gates.
  Date/Author: 2026-02-18 / Codex

## Outcomes & Retrospective

Implementation completed and validated end-to-end.

What changed:

- `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` now dispatches runtime message handling through `handle-runtime-msg` multimethods keyed by `:msg/type`, and `step` delegates to that polymorphic handler.
- `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs` now dispatches effect interpretation through `interpret-effect-by-type!` multimethods keyed by `:fx/type`, while keeping `interpret-effect!` as the public entrypoint.
- `/hyperopen/src/hyperopen/websocket/application/runtime.cljs` now normalizes connection commands and transport events via explicit handler registries (`connection-command-handlers`, `transport-event-handlers`) instead of large `case` branches.

Validation evidence:

- `npm run check`: pass.
- `npm test`: pass (`1093` tests, `4961` assertions, `0` failures, `0` errors).
- `npm run test:websocket`: pass (`129` tests, `530` assertions, `0` failures, `0` errors).

Result against original purpose:

- Runtime dispatch is now additive and extensible without editing one central switch for each new message/effect/event operation.
- Existing runtime behavior stayed stable under full repository and websocket test suites.

## Context and Orientation

`/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` owns pure runtime transitions via `step`, now delegated to polymorphic multimethod handlers keyed by `:msg/type`.

`/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs` owns IO side-effect interpretation in `interpret-effect!`, now delegated to polymorphic multimethod handlers keyed by `:fx/type`.

`/hyperopen/src/hyperopen/websocket/application/runtime.cljs` owns normalization of external command/transport input payloads into internal runtime messages using `command->runtime-msg` and `transport-event->runtime-msg`, now routed through handler registries keyed by command/event tags.

In this plan, polymorphic dispatch means selecting behavior by the runtime tag value (`:msg/type`, `:fx/type`, `:op`, `:event/type`) via open handler methods or dispatch tables instead of one centralized branch expression.

## Plan of Work

Milestone 1 converts reducer dispatch to multimethods. The existing body for each runtime message type will move into a dedicated `defmethod` and the public `step` function will delegate by `:msg/type`, preserving current helper usage (`ensure-connect`, `schedule-retry`, projections, health refresh behavior, and unknown-message dead-letter behavior).

Milestone 2 converts effect interpretation dispatch to multimethods. Each effect branch in `interpret-effect!` will become a dedicated `defmethod` keyed by `:fx/type`, with a `:default` method returning `nil` to preserve unknown-effect behavior.

Milestone 3 converts runtime command and transport event normalization to dispatch registries. `command->runtime-msg` and `transport-event->runtime-msg` will route through handler maps keyed by `:op` and `:event/type`, preserving recursive coercion from plain maps and existing parse-error fallbacks.

Milestone 4 validates behavior with focused websocket runtime tests and required quality gates, then updates this plan’s living sections and relocates it to completed.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`:
   - Introduce `defmulti`/`defmethod` dispatch for runtime messages.
   - Keep `step` as the public pure entrypoint, delegating to polymorphic handlers.
2. Edit `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`:
   - Introduce `defmulti`/`defmethod` dispatch for runtime effects.
   - Keep `interpret-effect!` as the public entrypoint.
3. Edit `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`:
   - Replace command and transport event `case` branches with handler maps plus safe fallback constructors.
4. Run required gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

Expected results:

  - Required gates pass with zero failures.

## Validation and Acceptance

Acceptance criteria:

- `reducer/step` behavior remains deterministic and emits equivalent effect sequences for existing message types.
- `runtime-effects/interpret-effect!` preserves side-effect semantics and unknown effect behavior (`nil`).
- `runtime/command->runtime-msg` and `runtime/transport-event->runtime-msg` preserve supported mappings and fallback parse-error payload behavior.
- Required validation gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

This refactor is source-only and idempotent. Re-running edits/tests is safe. If a regression appears, recovery is straightforward: restore only the handler whose behavior diverged while keeping polymorphic scaffolding intact, then rerun targeted runtime tests to confirm parity.

## Artifacts and Notes

Planned changed files:

- `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
- `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`
- `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`
- `/hyperopen/docs/exec-plans/completed/2026-02-18-websocket-runtime-polymorphic-dispatch.md`

## Interfaces and Dependencies

Public runtime API functions remain unchanged:

- `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`: `initial-runtime-state`, `step`
- `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`: `interpret-effect!`
- `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`: `start-runtime!`, `publish-command!`, `publish-transport-event!`, `stop-runtime!`

Key dependency contracts preserved:

- Runtime message/effect tags and constructors from `/hyperopen/src/hyperopen/websocket/domain/model.cljs`.
- Runtime reliability purity and boundary rules from `/hyperopen/docs/RELIABILITY.md`.

Plan revision note: 2026-02-18 02:30Z - Initial plan created from websocket runtime switch hotspot analysis and reliability constraints.
Plan revision note: 2026-02-18 02:36Z - Updated progress, discoveries, decisions, and outcomes after completing polymorphic dispatch refactor and passing required validation gates.
