# TP/SL Percent Basis Clarity Across Position Modal and Order Form

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, users can choose whether TP/SL Gain/Loss percentages represent return on margin (ROE%) or return on position value (Position%). This removes the current ambiguity where `%` looks like position percent but is actually ROE percent.

A user can verify this by opening either TP/SL surface, selecting `% Position`, entering `10`, and seeing trigger prices and helper text reflect a 10% move on notional position value instead of leverage-scaled ROE.

## Progress

- [x] (2026-02-25 16:40Z) Audited current Position TP/SL modal behavior and confirmed percent mode is margin-based (`estimated-gain-percent` / `estimated-loss-percent`) in `/hyperopen/src/hyperopen/account/history/position_tpsl_policy.cljs`.
- [x] (2026-02-25 16:43Z) Audited order-form TP/SL unit behavior and confirmed `%` is leverage-based ROE in `/hyperopen/src/hyperopen/trading/order_form_tpsl_policy.cljs` and `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs`.
- [x] (2026-02-25 16:49Z) Drafted shared copy, interaction states, and implementation plan in this ExecPlan.
- [x] (2026-02-25 16:54Z) Implemented position-modal percent-basis model (`:usd`, `:roe-percent`, `:position-percent`) in `/hyperopen/src/hyperopen/account/history/position_tpsl_state.cljs`, `/hyperopen/src/hyperopen/account/history/position_tpsl_policy.cljs`, and `/hyperopen/src/hyperopen/account/history/position_tpsl_transitions.cljs`.
- [x] (2026-02-25 16:59Z) Implemented order-form TP/SL percent-basis model and explicit 3-option dropdown semantics in `/hyperopen/src/hyperopen/trading/order_form_state.cljs`, `/hyperopen/src/hyperopen/trading/order_form_tpsl_policy.cljs`, and `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs`.
- [x] (2026-02-25 17:03Z) Updated tests for modal behavior, policy math, transitions, and dropdown options in `/hyperopen/test/hyperopen/account/history/position_tpsl_test.cljs`, `/hyperopen/test/hyperopen/views/account_info/position_tpsl_modal_test.cljs`, `/hyperopen/test/hyperopen/trading/order_form_tpsl_policy_test.cljs`, `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`, and `/hyperopen/test/hyperopen/views/trade/order_form_component_sections_test.cljs`.
- [x] (2026-02-25 17:07Z) Ran required validation gates successfully: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-02-25 12:38Z) Applied regression follow-up for modal unit control styling: removed nested bordered accessory style, removed duplicate caret rendering, improved token legibility width, and added explicit style-contract test coverage plus frontend guardrail language in `/hyperopen/docs/FRONTEND.md`.
- [x] (2026-02-25 12:47Z) Applied second regression follow-up from user screenshot: tuned selector text/caret spacing and reduced input right-padding reserve so Gain/Loss values render right-aligned near the unit token without overlap; re-ran required gates successfully.

## Surprises & Discoveries

- Observation: The order-form TP/SL dropdown is visually compact but semantically overloaded; it only exposes `$` and `%` while `%` silently means ROE.
  Evidence: `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs` `tpsl-unit-options` contains only `[:usd "$"]` and `[:percent "%"]`.

- Observation: Position modal and order-form TP/SL currently use separate code paths for conversion but the same conceptual ambiguity.
  Evidence: modal conversions live in `/hyperopen/src/hyperopen/account/history/position_tpsl_policy.cljs`; order-form conversions live in `/hyperopen/src/hyperopen/trading/order_form_tpsl_policy.cljs`.

- Observation: Existing tests already assert deterministic conversion and dropdown behavior, so extending them is lower risk than introducing new UI flows.
  Evidence: `/hyperopen/test/hyperopen/trading/order_form_tpsl_policy_test.cljs`, `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`, and `/hyperopen/test/hyperopen/views/account_info/position_tpsl_modal_test.cljs`.

- Observation: Typography guardrails reject explicit `text-[11px]` utility classes in views.
  Evidence: `npm test` failed initially in `hyperopen.views.typography-scale-test` until `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs` used `text-xs`.

- Observation: Using a bordered native select accessory inside an existing input shell caused immediate UX regressions (double-caret visuals and clipped trigger token) and user feedback.
  Evidence: Follow-up fix replaced bordered accessory styling with borderless integrated styling and added regression assertions in `/hyperopen/test/hyperopen/views/account_info/position_tpsl_modal_test.cljs`.

- Observation: Over-allocating input right padding made Gain/Loss values appear visually centered instead of right-aligned near the unit token.
  Evidence: User screenshot feedback; resolved by reducing reserved accessory space and asserting right-padding/input alignment contract in `/hyperopen/test/hyperopen/views/account_info/position_tpsl_modal_test.cljs`.

## Decision Log

- Decision: Keep `%` as a first-class mode, but add an explicit percent basis (`ROE` or `Position`) instead of introducing a separate hidden formula switch.
  Rationale: Users think in percentages first; basis is the missing semantic. Exposing basis directly solves confusion without removing current workflows.
  Date/Author: 2026-02-25 / Codex

- Decision: Use one explicit three-option unit menu in each TP/SL surface: `$`, `% ROE`, `% Position`.
  Rationale: A single explicit selector is less error-prone than a two-step toggle + secondary basis control in high-stakes trading inputs.
  Date/Author: 2026-02-25 / Codex

- Decision: Preserve backward compatibility by mapping legacy `:percent` defaults to `% ROE`.
  Rationale: Existing users, persisted state, and tests assume current `%` behavior; migration must be non-breaking and deterministic.
  Date/Author: 2026-02-25 / Codex

- Decision: Use a native `<select>` for Position TP/SL modal unit controls and keep the custom dropdown in the order form.
  Rationale: The modal already has sufficient width for clear select labels and this avoids introducing extra modal-only open/close UI state while preserving keyboard accessibility.
  Date/Author: 2026-02-25 / Codex

- Decision: Keep modal unit selector borderless and focus-neutral inside the existing input shell, and enforce that via test contract.
  Rationale: Prevents repeated regressions where nested control borders and default browser focus visuals conflict with the input shell design.
  Date/Author: 2026-02-25 / Codex

## Outcomes & Retrospective

Implementation completed. Both TP/SL entry surfaces now expose explicit `$`, `ROE%`, and `Pos%` semantics, with legacy `%` values normalized to ROE behavior for compatibility.

Position modal gain/loss helper text now explicitly shows alternate basis values (`Position` and `ROE`) so users can see both perspectives without switching context. Order-form TP/SL rows now include a basis hint line when percent mode is active and preserve compact trigger tokens (`$`, `ROE%`, `Pos%`).

Validation outcomes:

- `npm run check` passed.
- `npm test` passed.
- `npm run test:websocket` passed.

## Context and Orientation

Two user-facing TP/SL surfaces exist today.

The Position TP/SL modal is rendered in `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs`. It stores modal draft state under `:positions-ui :tpsl-modal` and translates user edits through `/hyperopen/src/hyperopen/account/history/position_tpsl_transitions.cljs` and `/hyperopen/src/hyperopen/account/history/position_tpsl_policy.cljs`.

The order-form TP/SL panel is rendered inside `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` and `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs`. It stores form state in `:order-form` and UI dropdown state in `:order-form-ui`, with conversion logic in `/hyperopen/src/hyperopen/trading/order_form_tpsl_policy.cljs`.

In both surfaces, `%` currently means leverage-scaled return on margin (ROE), not return on position value. This plan introduces an explicit basis selector so users can choose either interpretation.

## Proposed UI Copy (Draft)

The copy below is the canonical draft for both surfaces.

### Shared unit labels

- `$` label: `USDC`
- `% ROE` label: `ROE %`
- `% Position` label: `Position %`

### Shared menu labels

- Option 1: `$ (USDC)`
- Option 2: `% ROE (Margin)`
- Option 3: `% Position (Notional)`

### Shared helper copy

- Basis info tooltip: `ROE % is based on margin used. Position % is based on position value.`

### Position modal copy

- Gain row placeholder label remains `Gain`.
- Loss row placeholder label remains `Loss`.
- Expected-profit line in `$` mode: `Expected profit: <Position %> Position | <ROE %> ROE`
- Expected-profit line in `% ROE` mode: `Expected profit: <USDC> USDC | <Position %> Position`
- Expected-profit line in `% Position` mode: `Expected profit: <USDC> USDC | <ROE %> ROE`
- Expected-loss line mirrors the same format and wording.

### Order-form TP/SL copy

- Gain row label remains `Gain`.
- Loss row label remains `Loss`.
- Unit dropdown trigger text uses the concise selected token: `$`, `ROE%`, or `Pos%`.
- Optional footnote below TP/SL rows when percent is selected: `Percent basis: ROE (margin)` or `Percent basis: Position (notional)`.

## Interaction States (Draft)

### Position modal state model

- Input mode per row supports three values: `:usd`, `:roe-percent`, `:position-percent`.
- Gain and Loss rows can remain independent for mode choice to preserve current flexibility.
- Default for both rows is `:usd`.
- Legacy `:percent` state must normalize to `:roe-percent`.

### Position modal interaction flow

1. User opens mode menu on Gain or Loss row.
2. User selects `$`, `ROE %`, or `Position %`.
3. Input suffix updates immediately.
4. Existing input text is reinterpreted by selected mode and updates trigger price deterministically.
5. Expected profit/loss helper text updates immediately and shows alternate representations with explicit basis tags.
6. Validation behavior remains unchanged for trigger direction and size constraints.

### Order-form TP/SL state model

- `:order-form :tpsl :unit` becomes a three-state unit token: `:usd`, `:roe-percent`, `:position-percent`.
- Default remains `$` (`:usd`).
- Legacy `:percent` and boolean false normalize to `:roe-percent`.

### Order-form interaction flow

1. User opens TP/SL unit dropdown in Gain row.
2. Menu shows three options with explicit basis labels.
3. Selecting an option closes menu and updates both Gain and Loss unit semantics (shared selector behavior is preserved).
4. Existing offset-input cache for TP/SL is cleared on unit change, matching current deterministic behavior.
5. Trigger price recalculation uses selected unit basis and side-aware inversion.
6. Dropdown remains keyboard-operable: `Enter`/`Space` open, `Escape` closes, arrow keys move focus (new test coverage required if arrow navigation is added).

### Error and edge states

- If required basis inputs are unavailable (`size`, `position value`, `margin`), offset-to-trigger conversion returns empty trigger exactly as current behavior does for invalid input.
- Menu selection never throws or partially updates state; state writes remain single-transition updates.
- Invalid typed text remains visible in input while trigger stays blank, preserving current user-feedback model.

## Plan of Work

Milestone 1 introduces a shared conceptual model for TP/SL unit semantics in both code paths. In `/hyperopen/src/hyperopen/account/history/position_tpsl_state.cljs`, add normalized row mode values that include `:roe-percent` and `:position-percent`. In `/hyperopen/src/hyperopen/trading/order_form_state.cljs`, extend `valid-tpsl-units` and normalization so legacy values map cleanly to `:roe-percent`.

Milestone 2 updates conversion policies. In `/hyperopen/src/hyperopen/account/history/position_tpsl_policy.cljs`, add percent basis resolvers for margin-based and position-value-based conversions and route `pnl-percent-input->price-text` and display estimators through selected basis. In `/hyperopen/src/hyperopen/trading/order_form_tpsl_policy.cljs`, extend `offset-value-from-trigger`, `trigger-from-offset-input`, and readiness checks to use `:roe-percent` versus `:position-percent` explicitly.

Milestone 3 updates TP/SL UIs. In `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs`, replace the current two-state reverse toggle affordance with explicit three-option unit menus on Gain and Loss rows and apply the copy in this plan. In `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs`, expand the TP/SL dropdown options from two to three and update labels to explicit basis terms while preserving current compact layout and keyboard behavior.

Milestone 4 updates transitions and handlers. In `/hyperopen/src/hyperopen/account/history/position_tpsl_transitions.cljs` and `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`, route new unit values through existing deterministic update paths and preserve current cache-clearing behavior when unit changes.

Milestone 5 extends tests and runs repo gates. Update modal tests, order-form section tests, policy tests, and transition tests to cover new unit labels, normalization compatibility, and conversion math for both percent bases. Then run required gates.

## Concrete Steps

From `/hyperopen`:

1. Extend TP/SL unit normalization and defaults in state modules.

   npm test -- hyperopen.state.trading.order-form-state-test

   Expect state tests to pass with new normalization cases for legacy `:percent` and new explicit percent units.

2. Extend conversion math and deterministic transition behavior.

   npm test -- hyperopen.trading.order-form-tpsl-policy-test
   npm test -- hyperopen.trading.order-form-transitions-test
   npm test -- hyperopen.account.history.position-tpsl-test

   Expect new tests to prove that identical numeric inputs produce different triggers for `ROE %` versus `Position %` where leverage != 1.

3. Update UI labels and dropdown/menu rendering.

   npm test -- hyperopen.views.trade.order-form-component-sections-test
   npm test -- hyperopen.views.account-info.position-tpsl-modal-test

   Expect tests to assert menu options `$ (USDC)`, `% ROE (Margin)`, `% Position (Notional)` and correct selected-state semantics.

4. Run full required validation gates.

   npm run check
   npm test
   npm run test:websocket

   Expect all commands to exit successfully.

## Validation and Acceptance

Acceptance is complete when all conditions below are true.

1. In Position TP/SL modal, Gain/Loss unit menus expose `$`, `% ROE`, and `% Position` with explicit labels.
2. In order-form TP/SL panel, the dropdown exposes the same three options and `%` is no longer ambiguous.
3. Entering `10` in `% Position` mode maps to a 10% move on position notional, not leverage-scaled ROE.
4. Entering `10` in `% ROE` mode preserves current leverage-scaled behavior.
5. Helper text explicitly communicates basis and alternate representation.
6. Existing validation rules for TP/SL direction, size limits, and limit-price constraints remain unchanged.
7. Required validation gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

The implementation is additive and idempotent. Re-running steps should not alter state shape beyond deterministic normalization defaults. If regressions occur, safe rollback is to map new units back to old two-state semantics (`:usd` and `:roe-percent`) while keeping explicit labels in UI until conversion parity is restored.

## Artifacts and Notes

Files expected to change during implementation:

- `/hyperopen/src/hyperopen/account/history/position_tpsl_state.cljs`
- `/hyperopen/src/hyperopen/account/history/position_tpsl_policy.cljs`
- `/hyperopen/src/hyperopen/account/history/position_tpsl_transitions.cljs`
- `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs`
- `/hyperopen/src/hyperopen/trading/order_form_state.cljs`
- `/hyperopen/src/hyperopen/trading/order_form_tpsl_policy.cljs`
- `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs`
- `/hyperopen/test/hyperopen/account/history/position_tpsl_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/position_tpsl_modal_test.cljs`
- `/hyperopen/test/hyperopen/trading/order_form_tpsl_policy_test.cljs`
- `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`
- `/hyperopen/test/hyperopen/views/trade/order_form_component_sections_test.cljs`

Plan revision note: 2026-02-25 16:49Z - Initial plan and copy/state draft created before implementation.
Plan revision note: 2026-02-25 17:07Z - Marked implementation complete, recorded final decisions/discoveries, and captured required gate results.
Plan revision note: 2026-02-25 12:38Z - Added regression follow-up work and permanent guardrails after user-reported UI issues.
Plan revision note: 2026-02-25 12:47Z - Added second regression follow-up for caret/text spacing and right-alignment tuning based on screenshot feedback.

## Interfaces and Dependencies

No external dependencies are required.

Internal interfaces to preserve:

- `:actions/set-position-tpsl-modal-field` remains the modal update action.
- `:actions/update-order-form` with path `[:tpsl :unit]` remains the order-form unit update action.
- TP/SL conversion functions remain pure and deterministic in policy namespaces.

Interface updates required:

- Position modal mode normalization must accept legacy values and return one of `:usd`, `:roe-percent`, `:position-percent`.
- Order-form TP/SL unit normalization must accept legacy `:percent` and boolean false and map them to `:roe-percent`.
- UI menus on both surfaces must render explicit basis labels and selected state for all three options.
