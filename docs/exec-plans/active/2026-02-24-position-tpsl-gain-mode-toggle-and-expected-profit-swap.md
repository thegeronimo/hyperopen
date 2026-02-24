# Position TP/SL Gain Mode Toggle and Expected-Profit Swap

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, the Position TP/SL modal in Hyperopen will support reversible Gain/Loss input modes so users can type either nominal USDC or percentage targets. The TP side will also show an `Expected profit` line under the Gain row that swaps units with the active Gain input mode: when Gain input is USDC, expected profit shows percent; when Gain input is percent, expected profit shows USDC.

A user can verify the behavior by opening a position’s TP/SL modal, typing into Gain, clicking the reverse icon next to the unit marker, and confirming that both the input unit and expected-profit unit switch while TP price calculations remain correct.

## Progress

- [x] (2026-02-24 21:06Z) Audited current TP/SL modal rendering and state update paths in `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs` and `/hyperopen/src/hyperopen/account/history/position_tpsl.cljs`.
- [x] (2026-02-24 21:06Z) Confirmed existing tests for modal wiring and TP/SL conversion in `/hyperopen/test/hyperopen/views/account_info/position_tpsl_modal_test.cljs`, `/hyperopen/test/hyperopen/account/history/position_tpsl_test.cljs`, and `/hyperopen/test/hyperopen/account/history/actions_test.cljs`.
- [x] (2026-02-24 21:09Z) Authored this execution plan with implementation and validation milestones.
- [x] (2026-02-24 21:20Z) Added TP/SL modal state and conversion logic for USD/percent reversible gain/loss input handling in `/hyperopen/src/hyperopen/account/history/position_tpsl.cljs`.
- [x] (2026-02-24 21:21Z) Updated TP/SL modal UI with unit badges, reverse controls, and expected-profit value swap behavior in `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs`.
- [x] (2026-02-24 21:22Z) Extended TP/SL tests for percent conversion, mode toggles, and modal reverse-control wiring in `/hyperopen/test/hyperopen/account/history/position_tpsl_test.cljs` and `/hyperopen/test/hyperopen/views/account_info/position_tpsl_modal_test.cljs`.
- [x] (2026-02-24 21:24Z) Ran required validation gates: `npm run check`, `npm test`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Current Gain/Loss inputs are derived displays (from TP/SL price) and are never stored as raw independent draft values in modal state.
  Evidence: `/hyperopen/src/hyperopen/account/history/position_tpsl.cljs` `set-modal-field` writes `:tp-price`/`:sl-price` directly for `[:tp-gain]` and `[:sl-loss]`.

- Observation: Existing conversion uses nominal PnL only and does not currently model a percent basis for TP/SL modal calculations.
  Evidence: only `estimated-gain-usd`, `estimated-loss-usd`, and `pnl->price` exist in `/hyperopen/src/hyperopen/account/history/position_tpsl.cljs`.

- Observation: Position rows include margin/leverage data that can provide a deterministic percent basis for expected-profit style conversion.
  Evidence: fixture `sample-position-row` includes `:marginUsed` and `:leverage {:value ...}` in `/hyperopen/test/hyperopen/views/account_info/test_support/fixtures.cljs`.

- Observation: Websocket test runner showed an intermittent asynchronous failure in `runtime-engine` assertions on the first run but passed cleanly on immediate retry.
  Evidence: first `npm run test:websocket` run reported 3 failures in `hyperopen.websocket.application.runtime-engine-test`; second run reported `0 failures, 0 errors`.

## Decision Log

- Decision: Implement reversible mode handling through existing `:actions/set-position-tpsl-modal-field` path dispatch rather than introducing a new action id.
  Rationale: This keeps runtime action registry, contracts, and handler wiring stable while still supporting toggle behavior through a new modal field path.
  Date/Author: 2026-02-24 / Codex

- Decision: Use margin-aware percent basis (prefer `marginUsed`, fallback to `positionValue / leverage`, then `positionValue`) and scale it by configured amount ratio.
  Rationale: The requested expected-profit percentage behavior in the reference UI aligns with return-on-margin semantics rather than notional percentage.
  Date/Author: 2026-02-24 / Codex

- Decision: Apply reversible unit toggles to both Gain and Loss inputs for consistency, but only render the explicit `Expected profit` helper line on TP/Gain as requested.
  Rationale: Reference UI presents reverse controls on both rows while expected-profit text is shown under Gain.
  Date/Author: 2026-02-24 / Codex

## Outcomes & Retrospective

Implemented reversible `:usd`/`:percent` modes for both Gain and Loss without changing action contracts. The modal now renders unit badges plus reverse controls and maps percent input to TP/SL prices through margin-aware PnL conversion.

Implemented TP `Expected profit` helper text below the Gain row with unit-swapping behavior: it shows percent while Gain input is USDC, and shows nominal USDC while Gain input is percent.

Validation completed with all required gates passing:

- `npm run check`
- `npm test`
- `npm run test:websocket` (passed after one immediate rerun due a transient runtime-engine test flake)

## Context and Orientation

The TP/SL modal state is held in `:positions-ui :tpsl-modal` and initialized by `/hyperopen/src/hyperopen/account/history/position_tpsl.cljs` (`default-modal-state`, `from-position-row`). UI rendering lives in `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs`, where Gain/Loss fields currently render as plain text inputs with no unit affordance.

User edits are dispatched through `:actions/set-position-tpsl-modal-field` (defined in `/hyperopen/src/hyperopen/account/history/actions.cljs`) which delegates to `position-tpsl/set-modal-field`. That function is the canonical translation boundary from user input to TP/SL trigger prices.

This change must preserve validation and submit behavior in `/hyperopen/src/hyperopen/account/history/position_tpsl.cljs` (`validate-modal`, `prepare-submit`) while extending only the input-display/conversion layer.

## Plan of Work

Milestone 1 adds modal-state and conversion primitives in `/hyperopen/src/hyperopen/account/history/position_tpsl.cljs`:

- Add gain/loss mode flags in modal state (`:usd` or `:percent`).
- Add margin-basis helpers and percent estimation functions.
- Extend `set-modal-field` so `[:tp-gain]` and `[:sl-loss]` route through nominal or percent conversion depending on active mode.
- Add mode-toggle paths handled by `set-modal-field`.

Milestone 2 updates modal rendering in `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs`:

- Add compact unit indicators (`$` or `%`) and reverse buttons beside Gain/Loss inputs.
- Wire reverse buttons to `:actions/set-position-tpsl-modal-field` with mode-toggle paths.
- Render Gain input value from the active mode.
- Render `Expected profit: ...` beneath the TP/Gain row, swapping between percent and USDC based on active Gain mode.

Milestone 3 adds and updates tests:

- Extend `/hyperopen/test/hyperopen/account/history/position_tpsl_test.cljs` with percent-mode conversion and expected-percent assertions.
- Extend `/hyperopen/test/hyperopen/views/account_info/position_tpsl_modal_test.cljs` with reverse-button dispatch and expected-profit unit rendering assertions.
- Update `/hyperopen/test/hyperopen/account/history/actions_test.cljs` only if effect shape changes are required.

Milestone 4 runs required validation gates and records outcomes.

## Concrete Steps

1. Implement domain-side mode and conversion helpers.

   cd /hyperopen
   npm test -- hyperopen.account.history.position-tpsl-test

   Expected result: TP/SL domain tests pass with new coverage for mode toggles and percent conversions.

2. Implement modal UI affordances and expected-profit swap text.

   cd /hyperopen
   npm test -- hyperopen.views.account-info.position-tpsl-modal-test

   Expected result: modal tests pass including reverse control dispatch and expected-profit helper rendering.

3. Run full repository validation gates.

   cd /hyperopen
   npm run check
   npm test
   npm run test:websocket

   Expected result: all commands exit with status 0.

## Validation and Acceptance

Acceptance is complete when all of the following are true:

1. Gain and Loss rows each show a unit marker and reverse control.
2. Clicking reverse changes input mode between USDC and percent and keeps TP/SL price mapping consistent.
3. Under Gain, `Expected profit` is visible and swaps units opposite the Gain input mode.
4. Entering Gain in percent mode updates TP price via percent-to-PnL conversion without breaking existing validation rules.
5. Required validation gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

The changes are additive and idempotent: rerunning edits leaves deterministic mode defaults and conversion paths. If percent conversion introduces regressions, recovery is to keep mode flags and UI affordances but route `[:tp-gain]`/`[:sl-loss]` back to nominal conversion while preserving existing submit flow and tests.

## Artifacts and Notes

Primary files in scope:

- `/hyperopen/src/hyperopen/account/history/position_tpsl.cljs`
- `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs`
- `/hyperopen/test/hyperopen/account/history/position_tpsl_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/position_tpsl_modal_test.cljs`
- `/hyperopen/test/hyperopen/account/history/actions_test.cljs` (only if required)

Plan revision note: 2026-02-24 21:09Z - Initial plan created before implementation.
Plan revision note: 2026-02-24 21:24Z - Updated progress, discoveries, and retrospective after implementation and validation completion.

## Interfaces and Dependencies

No external dependencies are added.

Interfaces that remain stable:

- Action id: `:actions/set-position-tpsl-modal-field`
- State root: `:positions-ui :tpsl-modal`
- Submit pipeline: `position-tpsl/prepare-submit` and `:effects/api-submit-position-tpsl`

New domain-level function outputs to add in `position_tpsl.cljs`:

- Gain/Loss mode readers returning `:usd` or `:percent`
- Estimated gain/loss percent helpers based on active size and margin basis

These functions are view-facing helpers only and do not alter order request schema.
