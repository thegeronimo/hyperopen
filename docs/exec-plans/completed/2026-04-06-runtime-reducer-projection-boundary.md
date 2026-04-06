# Split the websocket runtime reducer projection boundary

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/docs/PLANS.md` and `/hyperopen/.agents/PLANS.md`.

Tracked `bd` issue: `hyperopen-z8ve` (`Split websocket runtime reducer projection boundary`).

## Purpose / Big Picture

Today, `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` is the highest-risk stateful hotspot among the requested exception-list candidates because it combines websocket connection lifecycle, retry and timer control, health hysteresis, stream-group rollups, runtime-view projection fingerprints, and effect emission in one oversized reducer. After this change, the reducer remains the public transition entrypoint, while dedicated runtime helper namespaces own health refresh and runtime-view projection behavior with direct boundary tests. A contributor can verify the refactor by running the focused websocket tests and the required repo gates without any runtime behavior drift.

## Progress

- [x] (2026-04-06 15:52Z) Compared the requested hotspot candidates against `dev/namespace_size_exceptions.edn` and selected `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` as the highest-risk target because it concentrates central websocket state transitions, timers, health hysteresis, and projection effects behind one repo-wide runtime boundary.
- [x] (2026-04-06 15:52Z) Created and claimed `hyperopen-z8ve` to track the refactor in `bd`.
- [x] (2026-04-06 15:52Z) Mapped the current reducer seam: `/hyperopen/src/hyperopen/websocket/application/runtime/{connection,market,subscriptions}.cljs` already own connection, market-buffer, and subscription state helpers, leaving the projection and health-refresh logic as the remaining oversized mixed concern inside the reducer root.
- [x] (2026-04-06 15:58Z) Extracted health-refresh logic into `/hyperopen/src/hyperopen/websocket/application/runtime/health_projection.cljs` and runtime-view projection building into `/hyperopen/src/hyperopen/websocket/application/runtime/projections.cljs`, then rewired `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` to delegate to those boundaries while preserving `initial-runtime-state` and `step`.
- [x] (2026-04-06 15:59Z) Added direct boundary suites in `/hyperopen/test/hyperopen/websocket/application/runtime/health_projection_test.cljs` and `/hyperopen/test/hyperopen/websocket/application/runtime/projections_test.cljs`, and trimmed projection-specific assertions out of `/hyperopen/test/hyperopen/websocket/application/runtime_reducer_test.cljs`.
- [x] (2026-04-06 16:00Z) Dropped `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` from 628 lines to 453 lines and `/hyperopen/test/hyperopen/websocket/application/runtime_reducer_test.cljs` from 548 lines to 471 lines, then removed both stale entries from `/hyperopen/dev/namespace_size_exceptions.edn`.
- [x] (2026-04-06 16:05Z) Passed focused websocket validation with `npm run test:runner:generate`, `./node_modules/.bin/shadow-cljs --force-spawn compile ws-test`, and `node out/ws-test.js --test=hyperopen.websocket.application.runtime-reducer-test,hyperopen.websocket.application.runtime.health-projection-test,hyperopen.websocket.application.runtime.projections-test`.
- [x] (2026-04-06 16:06Z) Passed the required repo gates: `npm run check`, `npm test`, and `npm run test:websocket`.

## Surprises & Discoveries

- Observation: the reducer is already partially decomposed, which lowers implementation risk for one more extraction.
  Evidence: `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` already delegates connection, market-buffer, and subscription mutations to `/hyperopen/src/hyperopen/websocket/application/runtime/connection.cljs`, `/hyperopen/src/hyperopen/websocket/application/runtime/market.cljs`, and `/hyperopen/src/hyperopen/websocket/application/runtime/subscriptions.cljs`.

- Observation: the reducer root and its root test are both on the size-exception list by narrow margins.
  Evidence: `dev/namespace_size_exceptions.edn` allows `628` lines for `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` and `548` lines for `/hyperopen/test/hyperopen/websocket/application/runtime_reducer_test.cljs`, while the repo-wide guardrail threshold in `/hyperopen/dev/check_namespace_sizes.clj` is `500`.

- Observation: the split fell naturally into two helper namespaces, not one.
  Evidence: the health-refresh code and runtime-view projection code have different dependencies and test surfaces, so the final extraction landed as `/hyperopen/src/hyperopen/websocket/application/runtime/health_projection.cljs` at 118 lines and `/hyperopen/src/hyperopen/websocket/application/runtime/projections.cljs` at 68 lines.

- Observation: the first focused websocket test run failed because a generated `.shadow-cljs` artifact was corrupted, not because of a source regression.
  Evidence: `node out/ws-test.js --test=...` initially failed on `.shadow-cljs/builds/ws-test/dev/out/cljs-runtime/cljs.pprint.js` with a duplicated trailing fragment, and a targeted cleanup of `.shadow-cljs/builds/ws-test` plus `out/ws-test.js` fixed the runner before the focused tests passed.

## Decision Log

- Decision: target `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` instead of the larger UI namespaces from the same request.
  Rationale: `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs` is larger in raw lines, but the websocket reducer owns a broader blast radius: global socket lifecycle, retry scheduling, stale-connection watchdog behavior, transport freshness, and view-projection effect boundaries used across the app.
  Date/Author: 2026-04-06 / Codex

- Decision: extract the projection and health-refresh concern instead of splitting message handlers first.
  Rationale: the reducer’s message handlers still read clearly as the public state machine, while the health-refresh, rollup, fingerprint, and projection helpers form a coherent boundary that can be tested directly and removed wholesale from the oversized root.
  Date/Author: 2026-04-06 / Codex

- Decision: add direct boundary tests for the extracted helper namespace and keep the root reducer tests focused on message transitions.
  Rationale: the request explicitly asks for boundary tests during the split, and moving projection-specific assertions out of the root reducer suite should also reduce the oversized test owner without weakening coverage.
  Date/Author: 2026-04-06 / Codex

- Decision: split the reducer helper block into two namespaces, `health_projection` and `projections`, instead of one combined helper.
  Rationale: health refresh depends on websocket health derivation and stream rollups, while runtime-view projection depends only on packaging reducer state into effect payloads and fingerprints. Keeping those concerns separate preserved the existing runtime helper pattern and yielded smaller direct test surfaces.
  Date/Author: 2026-04-06 / Codex

- Decision: close the duplicate `bd` issue `hyperopen-nj34` created during delegated analysis and keep `hyperopen-z8ve` as the single source of truth.
  Rationale: the repo should not carry two live issues or two active ExecPlans for the same refactor. Consolidation preserved one canonical plan and one issue lifecycle.
  Date/Author: 2026-04-06 / Codex

## Outcomes & Retrospective

Implementation landed. Overall complexity went down. `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` now keeps the message-dispatch state machine while `/hyperopen/src/hyperopen/websocket/application/runtime/health_projection.cljs` owns health refresh and `/hyperopen/src/hyperopen/websocket/application/runtime/projections.cljs` owns runtime-view projection packaging. The reducer root dropped from 628 lines to 453, the root reducer test dropped from 548 lines to 471, both stale namespace-size exceptions were removed, and the extracted boundaries now have direct regression coverage. The only unexpected cost was clearing one corrupted `.shadow-cljs` websocket build artifact before the focused runner could execute.

## Context and Orientation

The websocket runtime reducer lives in `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`. Its public API is `initial-runtime-state` plus `step`, where `step` takes reducer dependencies, current state, and a runtime message, then returns `{:state next-state :effects [...]}`. This reducer is pure; it does not touch sockets or timers directly. Instead, it emits runtime effects like `:fx/socket-connect`, `:fx/timer-set-interval`, and `:fx/project-runtime-view`, which infrastructure code applies elsewhere.

Three helper namespaces already existed beside it. `/hyperopen/src/hyperopen/websocket/application/runtime/connection.cljs` owns socket lifecycle and retry helpers. `/hyperopen/src/hyperopen/websocket/application/runtime/market.cljs` owns market-buffer clearing and flush behavior. `/hyperopen/src/hyperopen/websocket/application/runtime/subscriptions.cljs` owns stream intent, subscription replay, and stream payload bookkeeping. This refactor added `/hyperopen/src/hyperopen/websocket/application/runtime/health_projection.cljs` for health refresh and `/hyperopen/src/hyperopen/websocket/application/runtime/projections.cljs` for runtime-view packaging and effect fingerprints.

The current root reducer test lives in `/hyperopen/test/hyperopen/websocket/application/runtime_reducer_test.cljs`. Before the refactor it mixed message-transition assertions with projection and health-refresh assertions. The split moved those boundary-specific assertions into `/hyperopen/test/hyperopen/websocket/application/runtime/health_projection_test.cljs` and `/hyperopen/test/hyperopen/websocket/application/runtime/projections_test.cljs`.

The repo enforces a namespace-size guardrail with `/hyperopen/dev/check_namespace_sizes.clj`. Any `.cljs` file over `500` lines must have a temporary exception in `/hyperopen/dev/namespace_size_exceptions.edn`. Before this refactor, the reducer root and its root test both relied on those exceptions; after the split, both files are below threshold and their stale exception entries are gone.

## Plan of Work

The implementation followed the planned sequence. First, it moved reducer-local health refresh, group rollup, and projection-fingerprint logic into `/hyperopen/src/hyperopen/websocket/application/runtime/health_projection.cljs`. Second, it moved runtime-view packaging and `:fx/project-runtime-view` effect assembly into `/hyperopen/src/hyperopen/websocket/application/runtime/projections.cljs`. Third, it updated `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` so the existing `result`, `emit-runtime-result`, and `emit-runtime-result-force-health` helpers delegate into those extracted boundaries without changing the reducer’s public API.

On the test side, the implementation created `/hyperopen/test/hyperopen/websocket/application/runtime/health_projection_test.cljs` for health-refresh throttling and sequence-gap rollup, and `/hyperopen/test/hyperopen/websocket/application/runtime/projections_test.cljs` for deterministic runtime-view fingerprints. The root reducer suite kept message-transition coverage only. After the file split, the implementation removed the stale reducer-root and reducer-test entries from `/hyperopen/dev/namespace_size_exceptions.edn`.

## Concrete Steps

Work from `/hyperopen`.

1. Added `/hyperopen/src/hyperopen/websocket/application/runtime/health_projection.cljs` and `/hyperopen/src/hyperopen/websocket/application/runtime/projections.cljs`.
2. Updated `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` to depend on those helpers while preserving the reducer’s public API.
3. Added `/hyperopen/test/hyperopen/websocket/application/runtime/health_projection_test.cljs` and `/hyperopen/test/hyperopen/websocket/application/runtime/projections_test.cljs`, then trimmed `/hyperopen/test/hyperopen/websocket/application/runtime_reducer_test.cljs`.
4. Ran:

   `npm run test:runner:generate`

   `./node_modules/.bin/shadow-cljs --force-spawn compile ws-test`

   `node out/ws-test.js --test=hyperopen.websocket.application.runtime-reducer-test,hyperopen.websocket.application.runtime.health-projection-test,hyperopen.websocket.application.runtime.projections-test`

   `npm run check`

   `npm test`

   `npm run test:websocket`

5. Removed the stale reducer exceptions from `/hyperopen/dev/namespace_size_exceptions.edn`, updated this ExecPlan, closed duplicate `bd` issue `hyperopen-nj34`, and kept `hyperopen-z8ve` as the canonical tracked issue.

## Validation and Acceptance

Acceptance requires three things.

First, the extracted helpers must have direct regression coverage that proves health refresh throttling, sequence-gap rollup, and runtime-view fingerprint behavior without going through unrelated reducer handlers.

Second, the root reducer suite must still prove message-level behavior such as connection initiation, queueing, market flush handling, retry and disconnect behavior, and stale-socket ignores.

Third, the repo gates must pass unchanged: `npm run check`, `npm test`, and `npm run test:websocket`. If the reducer root or root reducer test remain oversized, the exception registry must reflect the new exact line count and no stale entries may remain.

Validation result: satisfied on 2026-04-06. The focused websocket command passed after clearing one corrupted generated ws-test artifact, and the required repo gates all passed with the reducer and root reducer test below the namespace-size threshold.

## Idempotence and Recovery

This refactor is code-local and can be repeated safely. If a partial extraction breaks the reducer, the safe recovery path is to keep the public `step` contract stable and move only pure helper logic first, then rerun the focused websocket tests before broader gates. If the new helper namespace proves too broad, keep the boundary narrower and leave any remaining oversized owner on a refreshed time-bounded exception rather than forcing an unsafe split.

## Artifacts and Notes

Key source anchors after the refactor:

`/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`

  Keeps `initial-runtime-state`, the `handle-runtime-msg` multimethod, and the public `step` surface while delegating final result assembly to the extracted helpers.

`/hyperopen/src/hyperopen/websocket/application/runtime/health_projection.cljs`

  Owns `maybe-refresh-health-hysteresis` and the reducer-local health-fingerprint derivation logic.

`/hyperopen/src/hyperopen/websocket/application/runtime/projections.cljs`

  Owns `runtime-view-projection`, `runtime-view-projection-fingerprint`, and `append-runtime-view-projection`.

`/hyperopen/test/hyperopen/websocket/application/runtime/{health_projection,projections}_test.cljs`

  Provide direct regression coverage for the extracted boundaries.

Line-count evidence after the refactor:

    /hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs -> 453 lines
    /hyperopen/test/hyperopen/websocket/application/runtime_reducer_test.cljs -> 471 lines

## Interfaces and Dependencies

The reducer public surface must remain:

`(initial-runtime-state config) -> state`

`(step {:calculate-retry-delay-ms f} state msg) -> {:state next-state :effects [RuntimeEffect ...]}`

The new helper namespaces expose pure functions for reducer-internal use and testability and do not introduce side effects or infrastructure dependencies. `/hyperopen/src/hyperopen/websocket/application/runtime/health_projection.cljs` depends on `/hyperopen/src/hyperopen/websocket/health.cljs` and `/hyperopen/src/hyperopen/websocket/domain/model.cljs`. `/hyperopen/src/hyperopen/websocket/application/runtime/projections.cljs` depends only on `/hyperopen/src/hyperopen/websocket/domain/model.cljs` and emits the same `:fx/project-runtime-view` payload shape the reducer already used.

Plan update note: 2026-04-06 16:06Z - Completed the reducer hotspot split by extracting `/hyperopen/src/hyperopen/websocket/application/runtime/{health_projection,projections}.cljs`, adding direct boundary suites under `/hyperopen/test/hyperopen/websocket/application/runtime/`, shrinking the reducer root and root reducer test below the namespace-size threshold, removing the stale reducer exceptions, passing focused websocket validation plus `npm run check`, `npm test`, and `npm run test:websocket`, and consolidating tracking onto `hyperopen-z8ve` after closing duplicate issue `hyperopen-nj34`.
