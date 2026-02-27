# Avoid Full Telemetry Scan During WebSocket Health Sync

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, websocket health synchronization will no longer scan the entire dev telemetry event log to build market projection diagnostics on each sync. Instead, market projection flush telemetry events will be captured into a dedicated bounded ring at emit time and read directly.

This improves health-sync efficiency under burst telemetry traffic while preserving the same diagnostics payload shape (`:flush-events`, `:flush-event-count`, `:latest-flush-event-seq`, and `:latest-flush-at-ms`). You can verify behavior by running tests that prove the dedicated ring tracks flush events and that health diagnostics continue to include the expected flush rows without depending on full-log filtering.

## Progress

- [x] (2026-02-27 03:01Z) Reviewed `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md` requirements for ExecPlan structure and placement.
- [x] (2026-02-27 03:02Z) Audited current hotspot in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` where `market-projection-flush-events` filters `telemetry/events` each sync.
- [x] (2026-02-27 03:03Z) Audited telemetry runtime and tests in `/hyperopen/src/hyperopen/telemetry.cljs`, `/hyperopen/test/hyperopen/telemetry_test.cljs`, and websocket health/runtime test suites to identify stable seams for regression coverage.
- [x] (2026-02-27 03:04Z) Authored this ExecPlan.
- [x] (2026-02-27 03:04Z) Implemented dedicated bounded ring support for `:websocket/market-projection-flush` events in `/hyperopen/src/hyperopen/telemetry.cljs` and wired emit-time indexing.
- [x] (2026-02-27 03:04Z) Switched websocket health diagnostics in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` to read telemetry flush events directly from the dedicated ring.
- [x] (2026-02-27 03:04Z) Added deterministic regression tests in `/hyperopen/test/hyperopen/telemetry_test.cljs` and `/hyperopen/test/hyperopen/runtime/effect_adapters_test.cljs`.
- [x] (2026-02-27 03:04Z) Ran required validation gates with passing results: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-02-27 03:05Z) Moved this plan to `/hyperopen/docs/exec-plans/completed/` after acceptance criteria passed.

## Surprises & Discoveries

- Observation: The health diagnostics helper currently does `filter` + `take-last` over `telemetry/events` on every sync.
  Evidence: `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` function `market-projection-flush-events` reads full `telemetry/events` and filters by event id.

- Observation: The global telemetry ring is intentionally broad and can contain many non-market-projection events (`max-events` = 2000), so per-sync filtering can become a repeated O(n) cost even though the diagnostics UI only needs recent flush events.
  Evidence: `/hyperopen/src/hyperopen/telemetry.cljs` stores all emitted dev events in `event-log`, and market projection diagnostics in effect adapters only consume the flush subset.

- Observation: Existing telemetry tests reset private atoms directly, so adding a second telemetry ring required adjusting shared test reset flow to avoid stale flush-ring entries between tests.
  Evidence: `/hyperopen/test/hyperopen/telemetry_test.cljs` fixture now calls `telemetry/clear-events!` and resets sequence atom separately.

## Decision Log

- Decision: Implement the dedicated bounded ring in `/hyperopen/src/hyperopen/telemetry.cljs` at emit time instead of adding another runtime cache inside websocket health adapters.
  Rationale: Emit-time indexing avoids repeated scan/filter work, keeps telemetry ownership in one module, and allows all consumers to share one canonical flush-event ring API.
  Date/Author: 2026-02-27 / Codex

- Decision: Keep the ring capacity aligned with the existing health diagnostics flush-event cap (60 events) and surface that value through telemetry so consumers do not duplicate constants.
  Rationale: This preserves UI payload size expectations and prevents divergence between emitter retention and adapter display limits.
  Date/Author: 2026-02-27 / Codex

- Decision: Keep the diagnostics payload shaping (`select-keys`) in effect adapters even after switching to direct ring reads.
  Rationale: This preserves stable health payload contract boundaries and avoids leaking future telemetry fields directly into websocket health state.
  Date/Author: 2026-02-27 / Codex

## Outcomes & Retrospective

Implemented as planned with no API contract regressions.

Outcomes:

- `telemetry.cljs` now maintains a dedicated bounded ring (`market-projection-flush-event-log`) for `:websocket/market-projection-flush` events and indexes that ring at emit time.
- `clear-events!` now clears both the general telemetry log and the dedicated flush-event ring.
- `effect_adapters.cljs` no longer scans/filter the full telemetry log for flush diagnostics; it reads `telemetry/market-projection-flush-events` directly.
- Existing market projection diagnostics payload shape remains stable, including selected flush-event keys and `:flush-event-limit`.

Validation evidence:

- `npm test` passed (`Ran 1476 tests containing 7440 assertions. 0 failures, 0 errors.`).
- `npm run check` passed (docs/test lint + app/test compiles green).
- `npm run test:websocket` passed (`Ran 153 tests containing 701 assertions. 0 failures, 0 errors.`).

Scope gaps: none for this optimization target.

## Context and Orientation

`/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` enriches websocket health snapshots with market projection diagnostics before writing store state. The current helper `market-projection-flush-events` computes diagnostics by scanning `telemetry/events` and filtering entries where `:event` is `:websocket/market-projection-flush`.

`/hyperopen/src/hyperopen/telemetry.cljs` currently retains only one general bounded event log (`event-log`, max 2000 entries). Every dev telemetry emission writes into that log, regardless of event type.

The optimization goal is to keep behavior identical for diagnostics output while removing full-log scan dependency in the health-sync path:

- Introduce a dedicated ring that retains only `:websocket/market-projection-flush` events (bounded to recent entries).
- Populate that ring in telemetry `emit!`.
- Consume that ring directly in effect adapters.

Relevant files:

- `/hyperopen/src/hyperopen/telemetry.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
- `/hyperopen/test/hyperopen/telemetry_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters_test.cljs`

## Plan of Work

### Milestone 1: Add Dedicated Flush Event Ring in Telemetry

Extend telemetry internals with a dedicated bounded ring for `:websocket/market-projection-flush` events. Keep the existing general event log unchanged. Update `emit!` so each flush event is indexed into that dedicated ring as events are emitted.

Expose a read API for the dedicated ring (for example `market-projection-flush-events`) and a limit accessor so downstream diagnostics can report ring capacity consistently. Ensure `clear-events!` resets both rings.

### Milestone 2: Switch Health Diagnostics to Direct Ring Reads

Update `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` so `market-projection-diagnostics` reads from telemetry’s dedicated flush-event ring API instead of filtering `telemetry/events` each sync. Preserve the same selected keys and payload contract.

At the end of this milestone, health sync should not need to inspect unrelated telemetry events to build flush diagnostics.

### Milestone 3: Add Regression Coverage

Add deterministic tests that verify:

- Telemetry dedicated ring captures only flush events and respects bounded retention.
- Clearing telemetry clears the dedicated ring.
- Effect adapter diagnostics source flush events from the dedicated ring path (and therefore do not require full-log scans).

### Milestone 4: Validate and Finalize

Run required validation gates (`npm run check`, `npm test`, `npm run test:websocket`) and record outcomes. If all acceptance criteria pass, move this plan to completed.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/telemetry.cljs`:

   - Add dedicated flush-event ring state and bounded append helper usage.
   - Update `emit!` to append flush events into that ring at emit time.
   - Add read/accessor functions for flush ring and ring limit.
   - Ensure `clear-events!` resets both general and flush rings.

2. Edit `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`:

   - Replace filter-over-`telemetry/events` logic with direct reads from telemetry flush-event ring.
   - Keep diagnostics payload key selection unchanged.

3. Edit tests:

   - `/hyperopen/test/hyperopen/telemetry_test.cljs` for ring retention/clear behavior.
   - `/hyperopen/test/hyperopen/runtime/effect_adapters_test.cljs` for adapter integration seam.

4. Run validation commands from `/hyperopen`:

       npm run check
       npm test
       npm run test:websocket

## Validation and Acceptance

Acceptance is complete when all conditions below are true.

1. Telemetry maintains a dedicated bounded ring for `:websocket/market-projection-flush` events populated at emit time.
2. Websocket health diagnostics read flush events directly from that dedicated ring and no longer scan/filter the full telemetry log per sync.
3. Diagnostics payload contract remains stable for market projection data (`:flush-events`, `:flush-event-count`, `:latest-flush-event-seq`, `:latest-flush-at-ms`, and `:flush-event-limit`).
4. Regression tests cover dedicated ring behavior and adapter integration.
5. Required gates pass: `npm run check`, `npm test`, and `npm run test:websocket`.

## Idempotence and Recovery

This work is additive and safe to rerun:

- Reapplying edits does not mutate external state beyond normal code changes.
- Test runs are repeatable and can be rerun after any failure.

If a regression appears:

- Keep the dedicated telemetry ring API in place.
- Temporarily route effect adapters back to a compatibility fallback only if needed for quick stabilization.
- Restore direct-ring reads once tests confirm payload parity.

## Artifacts and Notes

Expected implementation files:

- `/hyperopen/src/hyperopen/telemetry.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
- `/hyperopen/test/hyperopen/telemetry_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters_test.cljs`

Expected evidence:

- Test assertions proving flush ring retention and clear semantics.
- Test assertions proving health diagnostics still include flush-event details while sourcing from dedicated ring.
- Green outputs for required validation gates.

## Interfaces and Dependencies

Telemetry interface updates to add:

- Read-only accessor for dedicated flush ring events (vector of event maps).
- Read-only accessor for dedicated flush ring capacity.

Runtime adapter dependency update:

- `hyperopen.runtime.effect-adapters` should depend on telemetry flush ring accessor instead of scanning `telemetry/events`.

No external library changes are required.

Plan revision note: 2026-02-27 03:04Z - Initial ExecPlan created for dedicated emit-time market-projection flush-event ring and health-sync direct-read integration.
Plan revision note: 2026-02-27 03:05Z - Recorded implementation completion, regression coverage, required gate evidence, and completion handoff status.
