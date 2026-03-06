# Cache Websocket Flush Diagnostics Incrementally

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Websocket health sync currently rebuilds the transformed market-projection flush diagnostics rows every time `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs` enriches a health snapshot. A user does not see a behavioral bug, but the runtime pays a repeated remap cost on every sync even when no new flush event exists.

After this change, the diagnostics rows used by websocket health will be cached at telemetry emit time and appended incrementally into a bounded ring. Health sync will read the already-shaped diagnostics rows directly, preserving the existing diagnostics payload contract while removing the repeated remap from the sync path.

## Progress

- [x] (2026-03-06 02:57Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and the relevant websocket/telemetry modules to confirm the required plan shape and current hotspot.
- [x] (2026-03-06 02:58Z) Audited `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs`, `/hyperopen/src/hyperopen/telemetry.cljs`, and the existing regression tests to identify the narrowest safe implementation seam.
- [x] (2026-03-06 02:58Z) Created and claimed `bd` issue `hyperopen-89v` for this task.
- [x] (2026-03-06 02:59Z) Authored this ExecPlan in `/hyperopen/docs/exec-plans/active/2026-03-06-websocket-health-flush-diagnostics-incremental-cache.md`.
- [x] (2026-03-06 03:00Z) Implemented telemetry-backed cached diagnostics rows in `/hyperopen/src/hyperopen/telemetry.cljs` and switched `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs` to consume the cached accessor directly.
- [x] (2026-03-06 03:00Z) Updated `/hyperopen/test/hyperopen/telemetry_test.cljs` and `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_test.cljs` with regression coverage for the diagnostics ring and adapter read path.
- [x] (2026-03-06 03:02Z) Ran `npm run test:websocket`, `npm test`, and `npm run check` successfully after restoring the locked Node dependencies with `npm ci`.
- [x] (2026-03-06 03:02Z) Updated this plan with final evidence, closed `hyperopen-89v`, and prepared the plan for move to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: The earlier optimization already moved flush-event collection onto a dedicated telemetry ring, but `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs` still remaps that bounded ring with `select-keys` on every health sync.
  Evidence: `market-projection-flush-events` in `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs` calls `mapv` over `telemetry/market-projection-flush-events`.

- Observation: The transformed diagnostics row shape is stable and narrow, so shaping it once at emit time is lower risk than adding a second mutable cache in the websocket adapter.
  Evidence: The adapter selects the same fixed keys for every flush event and the diagnostics payload tests only depend on that stable subset.

- Observation: The worktree did not have `node_modules` installed, so the documented validation commands could not run until the locked dependencies were restored.
  Evidence: The first `npm run test:websocket` attempt exited with `sh: shadow-cljs: command not found`, and `npm ls shadow-cljs --depth=0` reported an empty dependency tree until `npm ci` completed.

## Decision Log

- Decision: Cache the shaped diagnostics rows inside `/hyperopen/src/hyperopen/telemetry.cljs` and expose them through a dedicated accessor instead of adding an adapter-local cache atom.
  Rationale: Telemetry already owns the bounded flush ring and append semantics. Moving the shape transform to emit time keeps the websocket adapter read-only and avoids duplicated cache invalidation logic.
  Date/Author: 2026-03-06 / Codex

- Decision: Preserve the existing raw flush-event ring API while adding a second diagnostics-focused accessor.
  Rationale: This keeps current telemetry behavior available for any future consumer while giving websocket health a zero-remap read path.
  Date/Author: 2026-03-06 / Codex

- Decision: Keep the diagnostics ring output identical to `select-keys` on the raw flush event rather than materializing absent keys with nil values.
  Rationale: This matches the prior adapter behavior exactly and keeps the payload minimal. The telemetry regression test now compares the diagnostics row against `select-keys` of the raw retained event.
  Date/Author: 2026-03-06 / Codex

## Outcomes & Retrospective

Implementation is complete.

Websocket health diagnostics still expose the same market-projection payload shape, but the repeated remap in `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs` is gone. Telemetry now owns a second bounded ring of already-shaped diagnostics rows, populated incrementally at emit time, and the websocket adapter reads that cached vector directly.

Validation evidence:

- `npm run test:websocket` passed with `Ran 333 tests containing 1840 assertions. 0 failures, 0 errors.`
- `npm test` passed with `Ran 1945 tests containing 9980 assertions. 0 failures, 0 errors.`
- `npm run check` passed with green lint steps plus successful `app`, `portfolio-worker`, and `test` compiles.

## Context and Orientation

`/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs` enriches websocket health snapshots before `/hyperopen/src/hyperopen/websocket/health_runtime.cljs` writes them into app state. The helper `market-projection-diagnostics` reads market-projection telemetry from `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs` and recent flush events from `/hyperopen/src/hyperopen/telemetry.cljs`.

The current hot path is local to the adapter helper `market-projection-flush-events`. That helper reads the bounded telemetry ring exposed by `telemetry/market-projection-flush-events` and remaps each raw event through `select-keys` on every sync. Because health sync can run frequently, this repeats work even when the flush ring has not changed.

`/hyperopen/src/hyperopen/telemetry.cljs` already appends flush events incrementally into a bounded ring when `emit!` sees the event `:websocket/market-projection-flush`. That module is the natural place to cache a second bounded ring containing the exact diagnostics row shape consumed by websocket health.

Relevant files for this task are:

- `/hyperopen/src/hyperopen/telemetry.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs`
- `/hyperopen/test/hyperopen/telemetry_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_test.cljs`

## Plan of Work

First, extend `/hyperopen/src/hyperopen/telemetry.cljs` with a dedicated bounded atom that stores already-shaped market-projection flush diagnostics rows. The transform should be applied once per emitted flush event, using the same field subset currently selected in the websocket adapter. `clear-events!` must clear this ring alongside the existing event logs, and telemetry should expose a read accessor for the shaped rows. This is implemented as `market-projection-flush-diagnostics-event-log` plus the accessor `market-projection-flush-diagnostics-events`.

Second, simplify `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs` so the market-projection diagnostics builder reads the cached shaped rows directly from telemetry. The adapter should keep the existing diagnostics payload contract, including `:flush-events`, `:flush-event-limit`, `:flush-event-count`, `:latest-flush-event-seq`, and `:latest-flush-at-ms`. This is implemented by replacing the old read-time `mapv` transform with a direct telemetry accessor call.

Third, extend regression coverage. Telemetry tests must prove the diagnostics ring is filtered, bounded, and cleared with the main telemetry state. Websocket adapter tests must prove health sync now consumes the diagnostics accessor directly and still produces the same market-projection diagnostics payload. This is implemented in `/hyperopen/test/hyperopen/telemetry_test.cljs` and `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_test.cljs`.

## Concrete Steps

From `/hyperopen`:

1. Edited `/hyperopen/src/hyperopen/telemetry.cljs`.
2. Edited `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs`.
3. Updated `/hyperopen/test/hyperopen/telemetry_test.cljs`.
4. Updated `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_test.cljs`.
5. Restored locked dependencies so validation could run:

       npm ci

6. Ran:

       npm run check
       npm test
       npm run test:websocket

## Validation and Acceptance

This task is complete when all of the following are true:

1. `telemetry/emit!` appends a pre-shaped diagnostics row for every `:websocket/market-projection-flush` event into a bounded ring.
2. `sync-websocket-health-with-runtime!` reads cached diagnostics rows directly and no longer remaps the flush ring in `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs`.
3. The health payload still exposes the same market-projection diagnostics keys and values.
4. Regression tests cover telemetry cache behavior and websocket adapter consumption.
5. `npm run check`, `npm test`, and `npm run test:websocket` pass.

## Idempotence and Recovery

This work is source-only and safe to rerun. The telemetry caches are in-memory development diagnostics only; editing and rerunning tests does not require any migration or cleanup beyond `telemetry/clear-events!`.

If the new diagnostics accessor regresses behavior, recovery is to keep the new telemetry ring in place and temporarily route the websocket adapter back to a full remap while preserving the new tests. That fallback is local to these files and does not require destructive git operations.

## Artifacts and Notes

Key evidence from implementation:

- Test assertions proving telemetry diagnostics rows are bounded and stripped to the expected key set.
- Test assertions proving websocket health consumes the new accessor directly.
- Passing outputs for `npm run check`, `npm test`, and `npm run test:websocket`.

Observed outputs:

    npm run check
    [:app] Build completed. (431 files, 430 compiled, 0 warnings, 8.79s)
    [:portfolio-worker] Build completed. (58 files, 57 compiled, 0 warnings, 3.52s)
    [:test] Build completed. (721 files, 4 compiled, 0 warnings, 3.90s)

    npm test
    Ran 1945 tests containing 9980 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    Ran 333 tests containing 1840 assertions.
    0 failures, 0 errors.

## Interfaces and Dependencies

`/hyperopen/src/hyperopen/telemetry.cljs` should expose one additional read-only function returning a vector of shaped diagnostics rows for market-projection flushes. Each row must include the exact keys currently selected by the websocket adapter:

- `:seq`
- `:event`
- `:at-ms`
- `:store-id`
- `:pending-count`
- `:overwrite-count`
- `:flush-duration-ms`
- `:queue-wait-ms`
- `:flush-count`
- `:max-pending-depth`
- `:p95-flush-duration-ms`
- `:queued-total`
- `:overwrite-total`

No external dependencies or public app APIs need to change for this task.

Plan revision note: 2026-03-06 02:59Z - Initial ExecPlan created for the websocket health flush diagnostics incremental-cache optimization.
Plan revision note: 2026-03-06 03:02Z - Recorded implementation completion, dependency-install discovery, validation evidence, and closeout status.
