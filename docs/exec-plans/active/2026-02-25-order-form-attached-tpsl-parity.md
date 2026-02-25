# Order Form Attached TP/SL Parity With Hyperliquid

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, enabling `Take Profit / Stop Loss` in the order form behaves like Hyperliquid’s order box: the user sees TP and SL price rows with companion Gain/Loss inputs and unit switching (`$` or `%`), and attached TP/SL children are submitted in the same parent order flow (`normalTpsl`) instead of a separate modal interaction pattern. A user can verify parity by enabling the checkbox, entering TP/SL directly in the order form rows, and placing one order that carries attached TP/SL children.

## Progress

- [x] (2026-02-25 00:05Z) Audited current Hyperopen TP/SL flow in order-form view, handlers, transitions, validation, and request assembly.
- [x] (2026-02-25 00:13Z) Audited Hyperliquid behavior from official docs and current production bundle (`tmp/hyperliquid-inspect/main.semicolons.js`) for exact order-box semantics.
- [x] (2026-02-25 00:24Z) Implemented Hyperliquid-style TP/SL rows in order-form UI (`TP Price + Gain`, `SL Price + Loss`) with shared unit selector.
- [x] (2026-02-25 00:27Z) Implemented TP/SL conversion policy for price <-> gain/loss and wired order-form transitions for offset-input updates.
- [x] (2026-02-25 00:29Z) Implemented mutual exclusion semantics between `Reduce Only` and `Take Profit / Stop Loss` in transitions.
- [x] (2026-02-25 00:31Z) Updated/added tests for sections, view rendering, transitions, state defaults, commands, and TP/SL policy.
- [x] (2026-02-25 00:33Z) Ran required gates: `npm run check`, `npm test`, `npm run test:websocket` (all passing).

## Surprises & Discoveries

- Observation: Hyperopen already had protocol-level attached TP/SL support in request construction (`grouping: "normalTpsl"` and child trigger orders), but UI state semantics were still legacy panel toggles.
  Evidence: `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` already builds `normalTpsl` child orders; `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs` previously rendered `Enable TP/Enable SL` and market/limit toggles.

- Observation: Hyperliquid order-box TP/SL and `Reduce Only` are mutually exclusive in the same options block.
  Evidence: Bundle logic in `tmp/hyperliquid-inspect/main.semicolons.js` sets one option false when the other is enabled (`ut.NK` and `ut.UG` writes in the same handler).

- Observation: Hyperliquid TP/SL row math uses baseline price + side-aware inversion with leverage for percent mode.
  Evidence: Bundle component function `32368:y` computes gain/loss and reverse conversion using `baseline`, `inverse`, `leverage`, and `sz`.

## Decision Log

- Decision: Keep request assembly unchanged and implement parity at form-state/view level.
  Rationale: Attached TP/SL wire behavior already matched protocol expectations; changing wire code would increase regression risk without user-facing benefit.
  Date/Author: 2026-02-25 / Codex

- Decision: Implement TP/SL row conversion as a dedicated policy namespace (`order_form_tpsl_policy`) and call it from both view and transitions.
  Rationale: Conversion logic is non-trivial and must stay deterministic and testable across render/update paths.
  Date/Author: 2026-02-25 / Codex

- Decision: Enforce mutual exclusion between `Reduce Only` and attached TP/SL in transitions.
  Rationale: This matches Hyperliquid option semantics and prevents conflicting order-intent state.
  Date/Author: 2026-02-25 / Codex

- Decision: Default TP/SL gain/loss unit to dollars (`:usd`) in form state.
  Rationale: The requested parity screenshot and existing Hyperopen TP/SL modal affordance are dollar-first for gain/loss input.
  Date/Author: 2026-02-25 / Codex

## Outcomes & Retrospective

The implemented result brings order-form TP/SL interaction to Hyperliquid-style rows and behavior while preserving the existing deterministic order request pipeline. Users can now configure attached TP/SL inline with their parent order through TP/SL prices or gain/loss targets and submit in one action. The remaining gap to strict byte-for-byte parity is local-storage preference compatibility for TP/SL checkbox/unit keys, which is optional for functional parity and not required for order correctness.

## Context and Orientation

The order form is rendered from `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`, with section components in `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs`, command handlers in `/hyperopen/src/hyperopen/views/trade/order_form_handlers.cljs`, and command builders in `/hyperopen/src/hyperopen/views/trade/order_form_commands.cljs`. State transitions are centralized in `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`, while default form fields and normalization live in `/hyperopen/src/hyperopen/trading/order_form_state.cljs`. Protocol order assembly is in `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`.

“Attached TP/SL” here means child trigger orders that are sent with a parent order in one payload using Hyperliquid `grouping: "normalTpsl"`.

## Plan of Work

The work first replaced the old panel internals (`Enable TP`, `Enable SL`, optional TP/SL limit rows) with two fixed rows that map to Hyperliquid-style order-box controls. A policy module was added to own unit normalization and conversion math between trigger prices and Gain/Loss fields. Transitions were then extended so Gain/Loss inputs update trigger prices directly, TP/SL leg enablement follows trigger presence, and `Reduce Only`/TPSL options are mutually exclusive. Finally, tests were updated to assert row rendering, conversions, and new transition semantics.

## Concrete Steps

From `/hyperopen`:

1. Edited order-form state and transitions:
   - `src/hyperopen/trading/order_form_state.cljs`
   - `src/hyperopen/trading/order_form_transitions.cljs`
2. Added TP/SL policy module:
   - `src/hyperopen/trading/order_form_tpsl_policy.cljs`
3. Edited order-form UI and handlers:
   - `src/hyperopen/views/trade/order_form_component_primitives.cljs`
   - `src/hyperopen/views/trade/order_form_component_sections.cljs`
   - `src/hyperopen/views/trade/order_form_view.cljs`
   - `src/hyperopen/views/trade/order_form_handlers.cljs`
   - `src/hyperopen/views/trade/order_form_commands.cljs`
4. Updated/added tests:
   - `test/hyperopen/views/trade/order_form_component_sections_test.cljs`
   - `test/hyperopen/views/trade/order_form_view/entry_mode_test.cljs`
   - `test/hyperopen/trading/order_form_transitions_test.cljs`
   - `test/hyperopen/state/trading/order_form_state_test.cljs`
   - `test/hyperopen/views/trade/order_form_commands_test.cljs`
   - `test/hyperopen/trading/order_form_tpsl_policy_test.cljs`
5. Ran validation gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance criteria and results:

- Enabling `Take Profit / Stop Loss` now renders TP/SL rows with Gain/Loss companion inputs in the order form.
  - Verified by view tests and section tests.
- Gain/Loss input updates TP/SL trigger prices according to side, baseline price, selected unit, and leverage.
  - Verified by policy and transition tests.
- `Reduce Only` and attached TP/SL are mutually exclusive.
  - Verified by transition test.
- Required repo gates pass.
  - `npm run check` passed.
  - `npm test` passed.
  - `npm run test:websocket` passed.

## Idempotence and Recovery

All edits are source-only and idempotent. Re-running the commands is safe. If any parity regressions appear, rollback is straightforward by reverting only TP/SL-specific files above because request-wire behavior was intentionally left unchanged.

## Artifacts and Notes

Relevant Hyperliquid evidence captured locally:

- `tmp/hyperliquid-inspect/trade.html`
- `tmp/hyperliquid-inspect/main.js`
- `tmp/hyperliquid-inspect/main.semicolons.js`

These artifacts were used to verify option semantics (TP/SL checkbox behavior, Gain/Loss unit mode, and `normalTpsl` submit grouping) beyond public docs.

## Interfaces and Dependencies

No new external dependencies were added.

New internal interface:

- `hyperopen.trading.order-form-tpsl-policy`
  - `normalize-unit`
  - `baseline-price`
  - `inverse-for-leg`
  - `offset-input-ready?`
  - `offset-display-from-trigger`
  - `trigger-from-offset-input`

Transition integration point:

- `hyperopen.trading.order-form-transitions/update-order-form` now accepts virtual paths `[:tp :offset-input]` and `[:sl :offset-input]` and translates them into canonical trigger updates.

Plan revision note: 2026-02-25 00:33Z - Initial ExecPlan created and backfilled with implementation progress, decisions, and validation evidence after completing parity implementation in this branch.
