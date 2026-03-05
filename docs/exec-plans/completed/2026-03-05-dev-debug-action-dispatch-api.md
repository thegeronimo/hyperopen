# Dev Debug Action Dispatch API

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Related tracked issue: `hyperopen-2qi`.

## Purpose / Big Picture

Hyperopen already exposes a dev-only `HYPEROPEN_DEBUG` object, but today it is read-only: a developer can inspect snapshots and websocket recordings, yet cannot drive deterministic runtime actions through the same stable seam. That forces automation and manual debugging workflows to click DOM elements such as the Ghost Mode header trigger, which is brittle because it depends on specific markup and anchor behavior rather than the canonical runtime action catalog.

After this change, a developer running the app dev build can open the browser console and call `HYPEROPEN_DEBUG.registeredActionIds()` to discover supported runtime action ids, then call `HYPEROPEN_DEBUG.dispatch([...])` with a single action vector to drive state transitions such as starting or stopping Ghost Mode without targeting the DOM. The behavior remains dev-only and is not exposed in release builds.

## Progress

- [x] (2026-03-05 19:43Z) Reviewed repository planning, work-tracking, architecture, reliability, and security constraints relevant to dev-only runtime debug tooling.
- [x] (2026-03-05 19:43Z) Created and claimed `bd` issue `hyperopen-2qi` for this work.
- [x] (2026-03-05 19:43Z) Authored this ExecPlan with concrete API shape, implementation seams, and validation commands.
- [x] (2026-03-05 19:45Z) Implemented the dev-only `HYPEROPEN_DEBUG.registeredActionIds()` and `HYPEROPEN_DEBUG.dispatch(actionVector)` bridge in `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`.
- [x] (2026-03-05 19:45Z) Added regression coverage in `/hyperopen/test/hyperopen/telemetry/console_preload_test.cljs` for debug global exposure, string id enumeration, action normalization, validation failures, and dispatch delegation.
- [x] (2026-03-05 19:45Z) Updated `/hyperopen/docs/runbooks/telemetry-dev-observability.md` with the expanded console API and deterministic Ghost Mode usage examples.
- [x] (2026-03-05 19:49Z) Repaired missing local npm dependencies with `npm ci`, then ran the required validation gates successfully: `npm run check`, `npm test`, and `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Hyperopen already has the exact internal seams needed for this API: one canonical runtime action catalog and one canonical dispatch function.
  Evidence: `/hyperopen/src/hyperopen/registry/runtime.cljs` exposes `registered-action-ids`, while `/hyperopen/src/hyperopen/app/bootstrap.cljs` wires `nexus.registry/dispatch` into the app runtime.
- Observation: The existing `HYPEROPEN_DEBUG` preload is limited to snapshot, flight recorder, and telemetry helpers, so extending that namespace is an additive change rather than a competing debug surface.
  Evidence: `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` exports only snapshot, flight recording, and event functions.
- Observation: Ghost Mode entry through the UI requires DOM trigger bounds because the action opens an anchored popover, which is exactly the kind of UI concern the new debug action seam should bypass for deterministic state changes.
  Evidence: `/hyperopen/src/hyperopen/views/header_view.cljs` dispatches `[:actions/open-ghost-mode-modal :event.currentTarget/bounds]`, and `/hyperopen/src/hyperopen/account/ghost_mode_actions.cljs` normalizes and stores the anchor bounds.
- Observation: The validation failure encountered during the first `npm run check` was an environment issue, not a code regression.
  Evidence: `shadow-cljs compile app` initially failed with `The required JS dependency "@noble/secp256k1" is not available`; running `npm ci` restored the missing dependency and the full gate set passed immediately after.

## Decision Log

- Decision: Keep the new dispatch bridge inside `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` and do not create a second dev tooling namespace.
  Rationale: `HYPEROPEN_DEBUG` is already the canonical dev-only browser debug surface, and the preload is already restricted to the app dev build.
  Date/Author: 2026-03-05 / Codex
- Decision: Use the existing runtime registration catalog and registry helper as the source of truth for exposed action ids.
  Rationale: Repository reliability rules require one canonical runtime registration catalog, and the debug API should not duplicate or drift from that list.
  Date/Author: 2026-03-05 / Codex
- Decision: Support a single action vector per `dispatch` call rather than a batch API in this change.
  Rationale: The user request asks for `dispatch(actionVector)`, and single-action dispatch is enough to cover deterministic Ghost Mode activation and similar state transitions without widening the surface unnecessarily.
  Date/Author: 2026-03-05 / Codex
- Decision: Accept JavaScript arrays whose first element is a runtime action id string, and normalize both `\":actions/foo\"` and `"actions/foo"` forms.
  Rationale: This keeps console and browser automation usage ergonomic while preserving stable keyword-based internal runtime dispatch.
  Date/Author: 2026-03-05 / Codex
- Decision: Reject unregistered or malformed action vectors with explicit errors before calling runtime dispatch.
  Rationale: A debug bridge should fail fast with useful feedback instead of silently sending malformed input into the runtime.
  Date/Author: 2026-03-05 / Codex

## Outcomes & Retrospective

The dev-only debug API now exposes runtime action discovery and single-action dispatch through the existing `HYPEROPEN_DEBUG` preload surface. Developers and automation can enumerate registered action ids and drive deterministic state transitions such as `:actions/start-ghost-mode` and `:actions/stop-ghost-mode` without clicking DOM controls.

The implementation stayed within existing repository invariants: the runtime registration catalog remains the single source of truth, runtime dispatch still flows through `nexus.registry/dispatch`, and the new functionality remains scoped to the app dev preload rather than release builds. Regression coverage and documentation were added, and all required repository gates passed after repairing the local npm install gap with `npm ci`.

## Context and Orientation

The current dev debug console surface lives in `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`. Shadow CLJS loads that namespace only for the browser app dev build through the `:devtools :preloads` entry in `/hyperopen/shadow-cljs.edn`. In practical terms, that means anything exported there is available in local development through `globalThis.HYPEROPEN_DEBUG`, but does not ship in the release build.

The runtime action catalog lives in `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`. That file is the canonical list of registered runtime action ids such as `:actions/start-ghost-mode` and `:actions/stop-ghost-mode`. `/hyperopen/src/hyperopen/registry/runtime.cljs` already exposes `registered-action-ids`, which returns that canonical set for internal code and tests.

The actual runtime dispatch path is `nexus.registry/dispatch`. Hyperopen wires that dispatch function to the app store in `/hyperopen/src/hyperopen/app/bootstrap.cljs` and `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`. A debug bridge does not need a new runtime; it only needs a safe, dev-only way to normalize a JavaScript action vector into the existing registered action format and then delegate to the already-bootstrapped dispatch seam.

Ghost Mode is the motivating example because the user-visible entry point is a header button in `/hyperopen/src/hyperopen/views/header_view.cljs`, and opening the Ghost Mode surface requires anchor bounds for the popover. That makes DOM clicking a poor fit for deterministic automation. The actual state transition that matters for deterministic spectating is `:actions/start-ghost-mode`, implemented in `/hyperopen/src/hyperopen/account/ghost_mode_actions.cljs`.

## Plan of Work

### Milestone 1: Add the dev-only action discovery and dispatch bridge

Update `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` to require the runtime registry helper and the Nexus dispatch function. Add small helper functions that do three jobs: convert the canonical registered action ids into stable JavaScript-visible strings, normalize incoming JavaScript action vectors into the internal keyword-first vector format, and throw explicit errors when the input is malformed or references an unregistered action.

The exported `debug-api` object must gain two new functions:

- `registeredActionIds()` returning a JavaScript array of stable string ids such as `":actions/start-ghost-mode"`;
- `dispatch(actionVector)` accepting one JavaScript array, validating it, and delegating to the existing runtime dispatch function using the canonical app store.

`dispatch` should not accept effect ids, raw batches, or DOM placeholders in this change. It should return a small JavaScript object that confirms the normalized action id and argument count so console and automation callers can inspect success directly.

### Milestone 2: Add regression coverage

Create a focused test namespace for the console preload behavior. The tests should cover four behaviors:

1. `registeredActionIds` includes canonical runtime action ids and emits string values.
2. Action vector normalization accepts the supported string formats and preserves arguments.
3. Invalid inputs fail fast with explicit error messages.
4. `dispatch` delegates exactly one normalized action vector to `nexus.registry/dispatch` using the shared app store.

These tests should avoid relying on browser preload mechanics. Instead, they should exercise the console preload helpers directly and stub `nexus.registry/dispatch` where needed, following existing test patterns in the repository.

### Milestone 3: Document the new console API

Update `/hyperopen/docs/runbooks/telemetry-dev-observability.md` so the documented `HYPEROPEN_DEBUG` API matches the actual implementation. Add a short deterministic Ghost Mode example that shows how a developer can enumerate action ids and then start or stop Ghost Mode by dispatching `:actions/start-ghost-mode` and `:actions/stop-ghost-mode` from the console.

The documentation must state clearly that this API exists only in the app dev build and does not exist in `:test`, `:ws-test`, or `:release`.

### Milestone 4: Validate and close out

Run the repository’s required validation gates from the repo root. Record pass or failure status in this plan, update the `bd` issue, and move this plan to `/hyperopen/docs/exec-plans/completed/` once the work is finished.

## Concrete Steps

From `/Users/barry/.codex/worktrees/d9e1/hyperopen`:

1. Implement the console preload bridge in `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`.

   Expected indicator: the debug API object now exposes `registeredActionIds` and `dispatch`, and the code still remains guarded by `goog.DEBUG`.
   Result: completed (2026-03-05 19:45Z). `console_preload.cljs` now normalizes supported action id strings, validates against the canonical runtime registry, and delegates one action vector through `nexus.registry/dispatch`.

2. Add focused regression tests under `/hyperopen/test/hyperopen/`.

   Expected indicator: tests prove string normalization, validation failures, and delegation to `nexus.registry/dispatch`.
   Result: completed (2026-03-05 19:45Z). `/hyperopen/test/hyperopen/telemetry/console_preload_test.cljs` covers debug global exposure, `registeredActionIds`, supported id normalization, validation failures, and dispatch delegation.

3. Update `/hyperopen/docs/runbooks/telemetry-dev-observability.md`.

   Expected indicator: the console API section documents the new methods and shows a deterministic Ghost Mode example.
   Result: completed (2026-03-05 19:45Z). The runbook now documents `registeredActionIds`, `dispatch`, and direct Ghost Mode debug usage.

4. Run the required gates.

   cd /Users/barry/.codex/worktrees/d9e1/hyperopen
   npm run check
   npm test
   npm run test:websocket

   Expected indicator: all commands exit successfully. If the environment blocks any gate, record the exact blocker here.
   Result: completed (2026-03-05 19:49Z). The first `npm run check` attempt exposed a missing local npm install for `@noble/secp256k1`; after `npm ci`, `npm run check`, `npm test`, and `npm run test:websocket` all passed.

## Validation and Acceptance

Acceptance for this work is behavioral:

1. In the app dev build, `globalThis.HYPEROPEN_DEBUG.registeredActionIds()` returns a JavaScript array of runtime action id strings that includes Ghost Mode actions such as `:actions/start-ghost-mode` and `:actions/stop-ghost-mode`.
2. In the app dev build, calling `globalThis.HYPEROPEN_DEBUG.dispatch([\":actions/start-ghost-mode\", \"0xabc...\"])` dispatches the corresponding runtime action through the canonical runtime dispatch seam rather than by clicking the UI.
3. Invalid action vectors, such as an unknown action id or a non-array input, throw explicit errors before dispatch.
4. The debug API documentation matches the implemented surface.
5. Required gates pass or any environment blocker is recorded with the exact failing command and error.

## Idempotence and Recovery

This change is additive and safe to rerun. The debug surface is dev-only and lives behind the existing preload guard, so repeated compiles or reloads do not require data migration or cleanup.

If dispatch normalization causes regressions, remove only the new `registeredActionIds` and `dispatch` additions from `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` while keeping the existing snapshot and flight recorder API intact. If tests fail because the new helper functions are difficult to exercise, extract pure helper functions inside the same namespace and test those directly rather than weakening the validation.

## Artifacts and Notes

Target console usage after implementation:

    HYPEROPEN_DEBUG.registeredActionIds().includes(":actions/start-ghost-mode")
    HYPEROPEN_DEBUG.dispatch([":actions/start-ghost-mode", "0x1234..."])
    HYPEROPEN_DEBUG.dispatch([":actions/stop-ghost-mode"])

Target successful dispatch return shape:

    { dispatched: true, actionId: ":actions/start-ghost-mode", argCount: 1 }

Target invalid input failure example:

    Error: HYPEROPEN_DEBUG.dispatch expected a JavaScript array whose first item is a registered action id string.

## Interfaces and Dependencies

At completion, `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` must expose the following dev-only JavaScript functions on `HYPEROPEN_DEBUG` in addition to the existing snapshot and telemetry helpers:

    registeredActionIds() -> string[]
    dispatch(actionVector) -> { dispatched: boolean, actionId: string, argCount: number }

Required dependencies and ownership:

- `/hyperopen/src/hyperopen/registry/runtime.cljs` remains the single source of truth for registered action ids exposed to the debug API.
- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs` remains the canonical runtime registration catalog and must not be duplicated.
- `nexus.registry/dispatch` remains the only runtime dispatch function used by the debug bridge.
- `/hyperopen/shadow-cljs.edn` continues to scope this API to the browser app dev preload only.

Revision note (2026-03-05): Initial ExecPlan created for `hyperopen-2qi` after confirming that the current `HYPEROPEN_DEBUG` surface is read-only and that the repo already contains stable internal seams for runtime action enumeration and dispatch.
Revision note (2026-03-05): Updated after implementation to record the shipped API, validation results, and the local npm dependency recovery step required to complete the repository gates.
