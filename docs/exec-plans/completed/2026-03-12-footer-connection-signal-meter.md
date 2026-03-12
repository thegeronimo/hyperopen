# Footer Connection Signal Meter with Graded Degradation

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Tracking `bd` issue: `hyperopen-265c` (closed as completed).

## Purpose / Big Picture

After this change, the lower-left footer websocket status control will display a signal-strength meter (bars) instead of a binary status pill. The meter will degrade in steps when websocket quality worsens, so users can distinguish slight delay from severe delay before full offline.

A user can verify the result by observing the footer control while websocket health changes: bar count should drop progressively (not jump directly from full bars to none), and clicking the control must still open `Connection diagnostics`.

## Progress

- [x] (2026-03-12 19:31Z) Audited footer status rendering, websocket health sources, and current diagnostics toggle wiring.
- [x] (2026-03-12 19:35Z) Created and claimed `bd` issue `hyperopen-265c` for this feature.
- [x] (2026-03-12 19:36Z) Created this active ExecPlan with file-level scope, algorithm direction, and validation gates.
- [x] (2026-03-12 19:39Z) Implemented the graded meter algorithm and replaced pill rendering in `/hyperopen/src/hyperopen/views/footer_view.cljs` while preserving diagnostics click behavior.
- [x] (2026-03-12 19:40Z) Updated regression coverage in `/hyperopen/test/hyperopen/views/footer_view_test.cljs` for incremental degradation levels and new meter labeling.
- [x] (2026-03-12 19:43Z) Ran required validation gates successfully: `npm test`, `npm run test:websocket`, and `npm run check`.
- [x] (2026-03-12 19:44Z) Moved this plan to `/hyperopen/docs/exec-plans/completed/` and closed `bd` issue `hyperopen-265c`.

## Surprises & Discoveries

- Observation: Existing footer status selection already has deterministic group-priority semantics (`orders_oms` before `market_data` before `account`) but only returns one status token, so it cannot represent partial degradation.
  Evidence: `/hyperopen/src/hyperopen/views/footer_view.cljs` functions `dominant-status-from-groups` and `dominant-pill-state`.

- Observation: Stream-level data already contains enough signal for graded severity, including per-stream stale thresholds and payload age.
  Evidence: `/hyperopen/src/hyperopen/websocket/health.cljs` derives stream `:status`, `:age-ms`, and `:stale-threshold-ms` in `derive-health-snapshot`.

- Observation: A severe delayed scenario can legitimately render `0` active bars while still labeled `Delayed` because the meter model allows very high cumulative penalty without forcing the label to `Offline`.
  Evidence: Initial severe-delay test expected `1` bar and failed (`actual: 0`), then was corrected to assert ordered degradation (`slight > severe`) with severe bounded to `0..1`.

## Decision Log

- Decision: Compute meter bars directly from the existing health snapshot in the footer view layer instead of introducing a new runtime projection path.
  Rationale: The health snapshot already includes transport, group, and stream freshness fields needed for deterministic scoring, so this avoids new state ownership and keeps the change scoped to rendering logic plus tests.
  Date/Author: 2026-03-12 / Codex

- Decision: Use a weighted penalty model that maps to 0..4 bars, combining transport freshness/state, group worst statuses, delayed-stream severity, and sequence-gap flags.
  Rationale: A weighted model makes slight and severe degradation observably different while still allowing hard-failure states (reconnecting/offline) to collapse quickly to low bars.
  Date/Author: 2026-03-12 / Codex

- Decision: Use the user-facing live label `Online` for the meter instead of the prior `Connected` pill wording.
  Rationale: The meter now conveys connection quality continuously; `Online` better communicates service availability while bar count conveys quality degradation.
  Date/Author: 2026-03-12 / Codex

## Outcomes & Retrospective

Implementation is complete for code and tests. The footer control now renders a 4-bar signal meter with deterministic graded degradation based on websocket health, and still opens diagnostics on click.

Validation outcomes:

- `npm test`: pass (`Ran 2344 tests containing 12270 assertions. 0 failures, 0 errors.`)
- `npm run test:websocket`: pass (`Ran 376 tests containing 2167 assertions. 0 failures, 0 errors.`)
- `npm run check`: pass (all lint/docs gates and app/portfolio/worker/test compiles succeeded).

Retrospective: Complexity increased slightly inside `footer_view.cljs` because meter scoring introduces additional helper functions and weighting constants. Overall behavioral complexity for users decreased because connection quality is now represented continuously instead of a binary/near-binary status jump.

## Context and Orientation

The footer component is `/hyperopen/src/hyperopen/views/footer_view.cljs`. The lower-left desktop control currently renders as a status pill and dispatches `:actions/toggle-ws-diagnostics` on click. The same file contains the diagnostics drawer and related status chips.

Websocket health data arrives in app state at `[:websocket :health]` and includes:

- `:transport` (state, freshness, reconnect attempt metadata)
- `:groups` (worst status per domain group)
- `:streams` (per-subscription status, age, stale thresholds, sequence-gap flags)

The rendering tests for this control are in `/hyperopen/test/hyperopen/views/footer_view_test.cljs` and currently assert the old pill text (`Connected`, `DELAYED`, `OFFLINE`) and diagnostics toggle behavior.

In this plan, “graded degradation” means mapping websocket health to multiple non-binary levels where the meter can show small quality losses (for example 3/4 bars) before severe loss (1/4 or 0/4 bars).

## Plan of Work

First, add a meter model helper in `footer_view.cljs` that computes a penalty score from health inputs. The algorithm will start from a full-quality score and subtract weighted penalties from transport freshness/state, group worst statuses, delayed-stream age relative to threshold, and sequence-gap detection. The final score will map to active bars (`0..4`) and a human label (`Online`, `Delayed`, `Reconnecting`, `Offline`).

Next, replace the current status pill rendering with a signal-meter button that uses the computed bar count. The button will keep the existing click action (`:actions/toggle-ws-diagnostics`) and maintain keyboard semantics. The visual treatment will include ascending bars and a non-color status label so users do not rely on color alone.

Then update footer view tests to assert the new label and meter behavior. Add explicit cases proving that slight delay and severe delay produce different bar counts.

Finally, run required gates, update this ExecPlan sections with final evidence, move the plan to completed, and close the `bd` issue.

## Concrete Steps

Run from `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/views/footer_view.cljs` to add the graded meter algorithm and new button markup.
2. Edit `/hyperopen/test/hyperopen/views/footer_view_test.cljs` to update old pill assertions and add degradation-level tests.
3. Run:

   npm run check
   npm test
   npm run test:websocket

4. Update plan sections (`Progress`, `Surprises & Discoveries`, `Decision Log`, `Outcomes & Retrospective`) with implementation evidence.
5. Move this plan from `active` to `completed` and close `hyperopen-265c`.

## Validation and Acceptance

Acceptance is complete only when all of the following are true:

- Footer lower-left control shows a signal meter (bars) plus status label instead of the old pill text treatment.
- Meter bars degrade incrementally for delayed websocket health (not full-to-zero only).
- Slight delay and severe delay map to different bar counts under deterministic rules.
- Clicking the meter still opens the connection diagnostics drawer.
- Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

This change is safe to re-run because it is limited to view rendering logic and tests. If the meter scoring proves too aggressive in practice, recovery is to tune penalty constants and bar thresholds in one place (the meter helper), then re-run tests. Diagnostics wiring remains unchanged, so fallback to the previous appearance can be done by reverting only the footer rendering section if needed.

## Artifacts and Notes

Primary touched modules are expected to be:

- `/hyperopen/src/hyperopen/views/footer_view.cljs`
- `/hyperopen/test/hyperopen/views/footer_view_test.cljs`

No websocket runtime protocol or network-side behavior changes are planned.

## Interfaces and Dependencies

Interfaces that must remain stable:

- Footer diagnostics toggle action: `:actions/toggle-ws-diagnostics`
- Diagnostics drawer open behavior and action wiring in `footer_view.cljs`

New internal view-layer interface expected after this change:

- A private footer helper that returns a meter model map containing bar count and label derived from websocket health.

Plan revision note: 2026-03-12 19:36Z - Initial active plan created after auditing footer/websocket health paths and claiming `hyperopen-265c`.
Plan revision note: 2026-03-12 19:43Z - Updated progress, discoveries, decisions, and outcomes after implementing the graded meter and passing all required validation gates.
Plan revision note: 2026-03-12 19:44Z - Moved this ExecPlan from `active` to `completed` and closed `hyperopen-265c`.
