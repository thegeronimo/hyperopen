# Lift Coverage for Websocket Application Runtime Files in the Main Test Build

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The coverage report currently shows red rows for the websocket application runtime modules in the main `:test` build output: `runtime`, `runtime_engine`, and `runtime_reducer`. This leaves critical connection-state and reducer behavior under-exercised in the default test command path. After this change, `npm test` will execute targeted websocket application runtime tests so those three files move from red to high coverage without broadening the main suite to all websocket tests.

## Progress

- [x] (2026-02-18 18:18Z) Captured baseline coverage values for the target files from `coverage/lcov.info` in the `:test` build output.
- [x] (2026-02-18 18:18Z) Authored active ExecPlan with approach, concrete steps, and acceptance criteria.
- [x] (2026-02-18 18:19Z) Updated `/hyperopen/shadow-cljs.edn` test namespace regex to include `hyperopen.websocket.application.*-test` in the main `:test` build.
- [x] (2026-02-18 18:20Z) Confirmed existing application runtime tests were sufficient; no additional test namespace or assertions were needed.
- [x] (2026-02-18 18:20Z) Ran validation (`npm run check`, `npm test`, `npm run test:websocket`, `npm run coverage`) and captured before/after target metrics.
- [x] (2026-02-18 18:20Z) Updated living sections and finalized retrospective with measured coverage lift.

## Surprises & Discoveries

- Observation: The low rows are specific to the `:test` build artifacts, while the `:ws-test` build already has strong coverage for the same source files.
  Evidence: `coverage/lcov.info` contains duplicate source-map rows:
  - `.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen/websocket/application/runtime.cljs` with `LH/LF = 46/255`.
  - `.shadow-cljs/builds/ws-test/dev/out/cljs-runtime/hyperopen/websocket/application/runtime.cljs` with `LH/LF = 173/255`.
- Observation: The main `:test` build excludes all websocket test namespaces by regex.
  Evidence: `/hyperopen/shadow-cljs.edn` has `:ns-regexp "^(hyperopen\\.(?!websocket\\.).*-test)$"` under `:builds :test`.
- Observation: No new runtime tests were required; simply running existing websocket application runtime tests in `npm test` removed red rows for the target files.
  Evidence: After regex change, `npm test` output includes `Testing hyperopen.websocket.application.runtime-test` and `Testing hyperopen.websocket.application.runtime-reducer-test`, and `:test` build coverage moved from `46/255`, `11/77`, `91/793` to `173/255`, `74/77`, `644/793` lines hit.

## Decision Log

- Decision: Lift coverage by selectively including websocket application runtime tests in `:test`, rather than moving all websocket tests into `npm test`.
  Rationale: This targets the exact low-coverage files while preserving the existing split between general tests and the broader websocket suite.
  Date/Author: 2026-02-18 / Codex
- Decision: Do not add new runtime-engine tests in this change.
  Rationale: Existing runtime and reducer suites already exercised runtime-engine paths sufficiently once included by regex; adding redundant tests would increase maintenance cost without meaningful additional coverage gain.
  Date/Author: 2026-02-18 / Codex

## Outcomes & Retrospective

Implementation completed with a single scoped configuration change in `/hyperopen/shadow-cljs.edn`.

Before/after coverage for `:test` build source-map rows:

- `runtime.cljs`: `LH/LF 46/255` -> `173/255`; `BRH/BRF 0/36` -> `62/131`; `FNH/FNF 0/18` -> `16/23`
- `runtime_engine.cljs`: `LH/LF 11/77` -> `74/77`; `BRH/BRF 0/3` -> `78/115`; `FNH/FNF 0/3` -> `8/8`
- `runtime_reducer.cljs`: `LH/LF 91/793` -> `644/793`; `BRH/BRF 1/56` -> `116/163`; `FNH/FNF 0/35` -> `35/35`

Validation results:

- `npm run check`: pass
- `npm test`: pass (includes websocket application runtime suites)
- `npm run test:websocket`: pass
- `npm run coverage`: pass

The user-visible objective is met: the previously red target rows are now substantially covered in the main test build path while retaining websocket suite separation.

## Context and Orientation

The three low-coverage files are source-mapped CLJS runtime artifacts under `.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen/websocket/application/`:

- `runtime.cljs`
- `runtime_engine.cljs`
- `runtime_reducer.cljs`

These files are exercised well in websocket-specific tests (`ws-test`), but `npm test` uses the `:test` build regex that excludes websocket test namespaces. As a result, runtime modules are only touched indirectly in `npm test`, yielding red coverage rows.

Key files involved:

- `/hyperopen/shadow-cljs.edn` (test namespace selection)
- `/hyperopen/test/hyperopen/websocket/application/runtime_test.cljs` (existing runtime behavior coverage)
- `/hyperopen/test/hyperopen/websocket/application/runtime_reducer_test.cljs` (existing reducer behavior coverage)
- `/hyperopen/coverage/lcov.info` (post-run evidence)

## Plan of Work

First, update the `:test` build namespace regex in `/hyperopen/shadow-cljs.edn` so `npm test` includes only `hyperopen.websocket.application.*-test` in addition to existing non-websocket tests. This keeps the default suite scoped while explicitly covering the three target runtime files.

Second, run `npm test` and inspect output to confirm the websocket application runtime tests execute in the main test run. Then run full coverage generation and capture the new `LH/LF`, branch, and function hit metrics for the three target files in the `:test` build rows.

Third, if any target remains low, add narrow tests in websocket application test namespaces focused on missing runtime-engine behaviors (dispatch timestamp defaulting, reducer-failure dead-letter emission, interpreter-failure telemetry path), then rerun coverage.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/shadow-cljs.edn`:
   - Update `:builds :test :ns-regexp` to include an alternative for `hyperopen.websocket.application.*-test` while preserving existing non-websocket matching.
2. Run `npm test` and verify application runtime tests run in the main suite output.
3. Run `npm run test:websocket` to ensure the dedicated websocket suite remains green.
4. Run `npm run coverage`.
5. Extract target metrics from `coverage/lcov.info` for:
   - `.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen/websocket/application/runtime.cljs`
   - `.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen/websocket/application/runtime_engine.cljs`
   - `.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen/websocket/application/runtime_reducer.cljs`

Expected transcript shape:

  - `npm test` includes:
    - `Testing hyperopen.websocket.application.runtime-test`
    - `Testing hyperopen.websocket.application.runtime-reducer-test`
  - `npm run test:websocket` passes with zero failures.
  - `npm run coverage` completes and reports improved hit counts on target rows.

## Validation and Acceptance

Acceptance requires all of the following:

- `npm test` passes and includes websocket application runtime test namespaces.
- `npm run test:websocket` passes unchanged.
- `npm run coverage` passes.
- Coverage for the three target files in the `:test` build rows improves from baseline:
  - `runtime.cljs`: from `46/255` lines hit.
  - `runtime_engine.cljs`: from `11/77` lines hit.
  - `runtime_reducer.cljs`: from `91/793` lines hit.
- Target rows are no longer red for lines/statements coverage in the report.

## Idempotence and Recovery

Regex and test changes are safe to reapply and rerun. If the `:test` regex accidentally broadens to all websocket tests and slows `npm test` too much, narrow it back to `hyperopen.websocket.application` namespaces only and rerun `npm test`. No data migration or destructive operation is involved.

## Artifacts and Notes

Baseline (2026-02-18) from `coverage/lcov.info`, `:test` build rows:

- `runtime.cljs`: `LH/LF 46/255`, `BRH/BRF 0/36`, `FNH/FNF 0/18`
- `runtime_engine.cljs`: `LH/LF 11/77`, `BRH/BRF 0/3`, `FNH/FNF 0/3`
- `runtime_reducer.cljs`: `LH/LF 91/793`, `BRH/BRF 1/56`, `FNH/FNF 0/35`

## Interfaces and Dependencies

No production interface changes are required. The plan only changes test selection and potentially test code.

Dependencies and interfaces used:

- Shadow-CLJS `:node-test` build namespace selection via `:ns-regexp`.
- Existing `cljs.test` namespaces:
  - `hyperopen.websocket.application.runtime-test`
  - `hyperopen.websocket.application.runtime-reducer-test`

Plan revision note: 2026-02-18 18:18Z - Initial plan authored with baseline metrics and a scoped strategy to lift `:test` build coverage for websocket application runtime modules.
Plan revision note: 2026-02-18 18:20Z - Recorded implementation results, validation outputs, and before/after coverage metrics after updating the `:test` namespace regex.
