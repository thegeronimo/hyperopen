# Position Overlay Incremental DOM Patching

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Today, every chart repaint for position overlays clears the overlay root and rebuilds the full subtree in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs`. That means lightweight repaints such as chart size changes, visible range changes, and series coordinate recalculations discard and recreate the PNL row and liquidation row DOM even when only text or coordinates changed.

After this change, the position overlay layer will keep stable DOM nodes for those rows and patch their text, styling, coordinates, and visibility in place. Users should see the same overlay behavior, but without repaint flicker or repeated DOM churn during live chart updates and liquidation-drag previews.

## Progress

- [x] (2026-03-06 02:55Z) Confirmed planning requirements from `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`.
- [x] (2026-03-06 02:56Z) Audited `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs`, related chart overlay tests, and UI/runtime guardrails.
- [x] (2026-03-06 02:57Z) Created and claimed `bd` issue `hyperopen-py9` for this repaint refactor.
- [x] (2026-03-06 03:00Z) Authored this active ExecPlan.
- [x] (2026-03-06 03:02Z) Implemented retained overlay row node ownership and incremental patch helpers in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs`.
- [x] (2026-03-06 03:04Z) Added regression coverage for retained-node repaint/update behavior in `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/position_overlays_test.cljs`.
- [x] (2026-03-06 03:08Z) Ran required validation gates successfully: `npm run check`, `npm test`, and `npm run test:websocket`.
- [ ] Close `bd` issue `hyperopen-py9` after acceptance passes (blocked: `bd close` failed twice because the local Dolt server did not accept connections on `127.0.0.1:13881`).

## Surprises & Discoveries

- Observation: The current `build-pnl-row!` signature still accepts `start-x` and `end-x`, but the rendered PNL row line spans the full overlay width and does not use those arguments.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs` `build-pnl-row!` applies `"left" "0px"` and `"right" "0px"` to the line regardless of the passed coordinates.

- Observation: The liquidation drag start handler currently relies on per-render node recreation because `drag-started?` is a fresh atom inside `build-liquidation-row!`.
  Evidence: `build-liquidation-row!` closes over `drag-started?`, so retaining the same node requires moving duplicate-event suppression to a state-based guard instead of a one-render atom.

- Observation: Existing tests cover text/visibility behavior and drag callbacks, but they do not assert DOM identity preservation across repaints or overlay updates.
  Evidence: `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/position_overlays_test.cljs` asserts content and callback payloads only.

- Observation: The local workspace was missing installed npm dependencies when validation started.
  Evidence: The first `npm run check` attempt failed during Shadow dependency resolution with `The required JS dependency "@noble/secp256k1" is not available`; running `npm ci` restored the expected packages and unblocked the gates.

- Observation: Local `bd` lifecycle updates are currently blocked by tracker infrastructure, not by application code.
  Evidence: Two `bd close hyperopen-py9 --reason "Completed" --json` attempts failed with `Dolt server unreachable at 127.0.0.1:13881 ... server started ... but not accepting connections`.

## Decision Log

- Decision: Retain exactly two row node groups per chart overlay root, one for PNL and one for liquidation, and patch them in place instead of clearing the root on repaint.
  Rationale: The overlay surface has fixed row cardinality, so a stable two-node cache is simpler and safer than introducing a generic keyed diff layer.
  Date/Author: 2026-03-06 / Codex

- Decision: Keep row nodes mounted under the overlay root and toggle visibility plus content in place rather than rebuilding/remounting per repaint.
  Rationale: Stable attachment preserves node identity, avoids ordering churn, and keeps drag affordance listeners attached once.
  Date/Author: 2026-03-06 / Codex

- Decision: Replace per-render liquidation drag duplicate-event suppression with a state-driven `:drag` guard read from overlay sidecar state.
  Rationale: Persistent nodes would otherwise permanently latch the old `drag-started?` atom after the first interaction.
  Date/Author: 2026-03-06 / Codex

## Outcomes & Retrospective

Implementation is complete for the scoped repaint refactor.

Delivered outcomes:

- Replaced the steady-state `clear-children!` rebuild path in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs` with cached PNL/liquidation row node groups that patch style, text, coordinates, and visibility in place.
- Moved liquidation drag-start duplicate-event suppression from a one-render atom to a sidecar-state `:drag` guard so retained listeners stay correct across repeated interactions.
- Added regression tests that prove both repaint-triggered coordinate updates and overlay-data updates preserve existing DOM node identity.
- Preserved public overlay entry points (`sync-position-overlays!`, `clear-position-overlays!`) and existing drag payload behavior.

Validation evidence:

- `npm run check`: pass.
- `npm test`: pass (`1947` tests, `9988` assertions, `0` failures, `0` errors).
- `npm run test:websocket`: pass (`333` tests, `1840` assertions, `0` failures, `0` errors).

Acceptance status:

- Normal repaint path no longer clears and rebuilds the overlay subtree: met.
- Retained-node repaint behavior is covered by regression tests: met.
- Overlay data changes patch existing nodes in place: met.
- Liquidation drag preview/confirm behavior remains covered and passing: met.
- `bd` issue closure: blocked by local Dolt server startup failure, not by the implementation.

## Context and Orientation

Position overlays live in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs`. This namespace creates a DOM root that sits on top of the trading chart and renders two visual rows:

- A PNL row showing the position PNL label and the entry-price axis chip.
- A liquidation row showing the liquidation label, optional margin-adjustment hint, drag hit area, and the liquidation-price axis chip.

The canonical entry point is `sync-position-overlays!`, which stores overlay state in a sidecar `WeakMap` keyed by chart object. Repaint subscriptions are installed through `subscribe-overlay-repaint!`, and those callbacks call `render-overlays!`.

Right now `render-overlays!` begins by clearing all children from the overlay root, then calls `build-pnl-row!` and `build-liquidation-row!` to create entirely new DOM trees. The refactor in this plan keeps the root, PNL row, and liquidation row stable and updates their styles/text in place.

The regression tests for this behavior live in `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/position_overlays_test.cljs`, using the fake DOM helpers in `/hyperopen/test/hyperopen/views/trading_chart/test_support/fake_dom.cljs`.

## Plan of Work

### Milestone 1: Replace full rebuilds with retained overlay row nodes

Refactor `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs` so `render-overlays!` no longer calls `clear-children!` as part of its steady-state repaint path. Introduce persistent DOM node ownership in sidecar state for the PNL row and liquidation row, with creation helpers that allocate the subtree once and patch helpers that update row visibility, coordinates, style colors, badge text, axis-chip text, and drag-label text.

This milestone also updates the liquidation drag start listener so it reads the current overlay payload from sidecar state instead of depending on one-render listener closures. At the end of the milestone, repeated repaint callbacks should reuse the same DOM nodes while still reflecting updated chart coordinates and overlay values.

### Milestone 2: Add retained-node regression coverage

Extend `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/position_overlays_test.cljs` with focused tests that prove:

- a repaint callback triggered through the stored chart subscriptions preserves chip/handle node identity while updating styles or coordinates in place;
- a new overlay value passed through `sync-position-overlays!` updates the text and colors on the existing nodes instead of replacing them.

The fake chart fixture should expose repaint callbacks so the tests can exercise the real `render-overlays!` repaint path instead of relying only on repeated `sync-position-overlays!` calls.

### Milestone 3: Validate and land the refactor

Run the required repository quality gates:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Record the results in this plan, then close `bd` issue `hyperopen-py9` if all acceptance criteria pass.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs`.
   Add persistent row-node creation helpers, in-place patch helpers, and a repaint path that updates existing nodes without `clear-children!`.

2. Edit `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/position_overlays_test.cljs`.
   Extend the fake chart fixture to expose repaint callbacks and add retained-node regression assertions.

3. Run validation gates.
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

Expected acceptance evidence:

- The same DOM node references are observed before and after repaint/update tests.
- Updated overlay values appear on those retained nodes.
- All required gates complete successfully.

## Validation and Acceptance

Acceptance is met when all of the following are true:

1. `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs` no longer performs a full overlay-root child teardown during normal repaint execution.
2. The PNL row and liquidation row keep stable node identity across repaint callbacks.
3. Overlay value changes patch retained nodes in place, including text, colors, and coordinates.
4. Liquidation drag behavior still emits the same preview/confirm payloads after the refactor.
5. `npm run check`, `npm test`, and `npm run test:websocket` pass.

## Idempotence and Recovery

This refactor is source-only and safe to reapply. Re-running the implementation steps should leave one active ExecPlan and one tracked `bd` issue with no duplicate runtime state.

If retained-node patching introduces a correctness regression, recovery is to restore the prior rebuild path inside `render-overlays!` while keeping the new regression tests. That provides a safe fallback and preserves the behavioral expectations for a follow-up fix.

## Artifacts and Notes

Primary implementation file:

- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs`

Primary regression test file:

- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/position_overlays_test.cljs`

Tracking:

- `bd` issue `hyperopen-py9`

## Interfaces and Dependencies

This refactor keeps the public `sync-position-overlays!` and `clear-position-overlays!` interfaces unchanged.

The internal sidecar state for a chart overlay must own:

- the overlay root node;
- the cached DOM node groups for the PNL row and liquidation row;
- the latest rendered overlay payload used by drag-start handlers;
- the existing subscription and drag-listener bookkeeping.

The fake DOM test fixture in `/hyperopen/test/hyperopen/views/trading_chart/test_support/fake_dom.cljs` remains the only DOM dependency for the regression tests.

Revision note: 2026-03-06. Created the active ExecPlan for `hyperopen-py9` after auditing the repaint path and identifying the need for persistent row nodes plus a state-based drag-start guard.

Revision note: 2026-03-06. Updated the plan after implementation to record the retained-node patch architecture, the `npm ci` validation prerequisite, and the passing gate results.
