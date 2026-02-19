# Order Form Size Unit Toggle Parity (USDC vs Base)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and must be maintained in accordance with that contract.

## Purpose / Big Picture

After this change, users can choose order size input in either quote notional (`USDC`) or base asset units (for example `HYPE`, `BTC`) directly inside the order ticket, matching Hyperliquid’s dual-size affordance. The ticket keeps one canonical base size for submission safety, while the displayed input value follows the selected unit mode. This allows users to switch units without losing intent and place the same order payload shape as before.

## Progress

- [x] (2026-02-19 21:41Z) Captured Hyperliquid trade page snapshot via browser inspection tooling and validated the size-unit dropdown exists in Limit mode.
- [x] (2026-02-19 21:44Z) Verified live runtime behavior with interactive DOM inspection: dropdown options are ordered as quote first (`USDC`) then base symbol; toggling preserves canonical base size and recomputes display in the selected unit.
- [x] (2026-02-19 21:46Z) Verified manual input behavior on Hyperliquid: in quote mode, changing price keeps displayed quote amount constant and recomputes canonical base size; in base mode, changing price keeps displayed base size unchanged.
- [x] (2026-02-19 22:03Z) Implemented order-form state, transition, action, command, runtime wiring, and view updates for size-input mode switching.
- [x] (2026-02-19 22:05Z) Ran required validation gates (`npm run check`, `npm test`, `npm run test:websocket`) and resolved transition-test assertion ownership issues; all gates now pass.
- [x] (2026-02-19 22:05Z) Finalized plan and moved to `/hyperopen/docs/exec-plans/completed/` after acceptance criteria passed.

## Surprises & Discoveries

- Observation: Anonymous Hyperliquid sessions defaulted to base-size mode (`HYPE`) in live inspection, but user-provided reference screenshot showed quote-size mode selected (`USDC`).
  Evidence: Browser eval snapshots in session `sess-1771537586913-28044a` showed `SizeHYPE` immediately after selecting Limit; screenshot artifact from user thread showed `USDC` selected.

- Observation: Hyperliquid quote-mode display remains fixed when price changes, while canonical base size updates.
  Evidence: In live session, `size="100"` in `USDC` mode stayed `100` after price moved `30.5 -> 31.5`, then toggling to base showed `3.17`.

- Observation: Hyperliquid mode toggle conversion is quantized by base market decimals; quote display and order value can differ slightly.
  Evidence: `100 USDC` at `30.5` converted to `3.27 HYPE`; switching back after price change gave `103.00 USDC` while order value row displayed a slightly different rounded figure.

- Observation: Hyperliquid’s production frontend implements size-unit mode as a boolean (`isNtl`) with a quote/base dropdown and converts to canonical coin size only at submit/validation boundaries.
  Evidence: Live bundle inspection in `main.ccb853ef.js` module `77826` shows selector options as quote/base labels; module `56947` maintains `isNtl` and converts display on toggle; module `36714` function `rCG` computes canonical coin size by dividing quote input by price when `isNtl=true`.

## Decision Log

- Decision: Keep canonical submission size as base-asset `:size` and treat size-unit mode as a UI-owned projection concern.
  Rationale: This preserves signing and order payload invariants and avoids consensus-risky changes to order action builders.
  Date/Author: 2026-02-19 / Codex

- Decision: Add `:size-input-mode` and `:size-input-source` as UI-owned order-form fields synchronized through `:order-form-ui`.
  Rationale: We need explicit source tracking (`:manual` vs `:percent`) to match Hyperliquid behavior when price/side context changes.
  Date/Author: 2026-02-19 / Codex

- Decision: Implement size-unit selector as an inline accessory select in the existing Size row.
  Rationale: This is the smallest robust integration with existing row primitives and keeps keyboard accessibility straightforward.
  Date/Author: 2026-02-19 / Codex

## Outcomes & Retrospective

Implementation is complete and validated. All required repository gates passed: `npm run check`, `npm test`, and `npm run test:websocket`. The main architectural outcome is a strict separation between canonical order size and user-facing unit projection, which preserves signing and payload safety while matching Hyperliquid’s user-visible behavior for unit switching and price-change semantics.

## Context and Orientation

Order-ticket behavior is assembled across these modules:

- `/hyperopen/src/hyperopen/trading/order_form_state.cljs` defines default UI fields and UI normalization.
- `/hyperopen/src/hyperopen/state/trading.cljs` composes normalized draft/UI state and offers size/price projection helpers.
- `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs` contains deterministic, side-effect-free state transitions for order form interactions.
- `/hyperopen/src/hyperopen/order/actions.cljs` applies transitions and emits persisted projection effects.
- `/hyperopen/src/hyperopen/views/trade/order_form_commands.cljs`, `/hyperopen/src/hyperopen/views/trade/order_form_handlers.cljs`, and `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs` map UI intents to runtime actions.
- `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs` and `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` render the ticket.
- `/hyperopen/src/hyperopen/schema/contracts.cljs` and `/hyperopen/src/hyperopen/schema/order_form_contracts.cljs` enforce runtime and VM shape contracts.

Canonical order size means the base-asset quantity sent in exchange payload fields (`:s`), while display size means the user-facing input string shown in the ticket (`:size-display`) under the current size-input mode.

## Plan of Work

Add UI-owned fields for size mode/source in state normalization and field-ownership boundaries. Extend transitions with deterministic mode-aware conversion helpers so manual quote/base input behavior and percent-driven sizing can coexist. Wire a new order-form command/action pair for setting the size-input mode. Update order-form VM and view to expose and render the unit selector in the Size row. Expand contracts and tests to cover new fields and conversion behavior.

## Concrete Steps

From `/hyperopen`:

1. Inspect and verify Hyperliquid behavior.
   - `npm run browser:inspect -- --url https://app.hyperliquid.xyz/trade --target hyperliquid --viewports desktop`
   - `node tools/browser-inspection/src/cli.mjs session start`
   - `node tools/browser-inspection/src/cli.mjs navigate --session-id <id> --url https://app.hyperliquid.xyz/trade --viewport desktop`
   - `node tools/browser-inspection/src/cli.mjs eval --session-id <id> --allow-unsafe-eval --expression "..."`

2. Implement mode/source state and transition logic in:
   - `/hyperopen/src/hyperopen/trading/order_form_state.cljs`
   - `/hyperopen/src/hyperopen/state/trading.cljs`
   - `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`

3. Wire command/action/runtime path in:
   - `/hyperopen/src/hyperopen/order/actions.cljs`
   - `/hyperopen/src/hyperopen/core/public_actions.cljs`
   - `/hyperopen/src/hyperopen/core/macros.clj`
   - `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
   - `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
   - `/hyperopen/src/hyperopen/registry/runtime.cljs`
   - `/hyperopen/src/hyperopen/schema/contracts.cljs`
   - `/hyperopen/src/hyperopen/views/trade/order_form_commands.cljs`
   - `/hyperopen/src/hyperopen/views/trade/order_form_handlers.cljs`
   - `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs`

4. Render selector and VM fields in:
   - `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs`
   - `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`
   - `/hyperopen/src/hyperopen/schema/order_form_contracts.cljs`

5. Update tests to match new contracts and behavior.

## Validation and Acceptance

Acceptance requires all mandatory gates and behavior checks:

- Run `npm run check` and expect success.
- Run `npm test` and expect success.
- Run `npm run test:websocket` and expect success.

Behavior acceptance:

- In the Limit ticket, the Size row shows a selectable unit accessory with quote and base options.
- Switching size unit changes only display projection, not canonical `:size` semantics for submission.
- Manual quote-mode amount remains fixed when price changes; canonical base size updates accordingly.
- Manual base-mode size remains fixed when price changes.
- Percent-driven sizing still updates canonical size deterministically.

## Idempotence and Recovery

All edits are additive and localized. Re-running tests is safe. If a regression appears, inspect transition-level tests first (`order_form_transitions_test.cljs`) because conversion semantics are centralized there. Since persistence strips UI-owned fields from `:order-form`, reverting mode/source ownership is a straightforward rollback by removing the new UI-owned keys and mode command wiring.

## Artifacts and Notes

Evidence artifacts from live Hyperliquid inspection:

- Snapshot image: `/hyperopen/tmp/browser-inspection/inspect-2026-02-19T21-41-34-673Z-7af70261/hyperliquid/desktop/screenshot.png`
- Live session ids used for behavior probing: `sess-1771537356965-a1f281`, `sess-1771537586913-28044a`

Key observed DOM snippet (paraphrased): size selector menu rendered quote and base options in one dropdown list (`USDC`, `HYPE`) with selected unit reflected in the Size row accessory.

## Interfaces and Dependencies

New runtime action and command surfaces:

- Command id: `:order-form/set-order-size-input-mode`
- Runtime action id: `:actions/set-order-size-input-mode`
- Transition function: `hyperopen.trading.order-form-transitions/set-order-size-input-mode`
- Action handler: `hyperopen.order.actions/set-order-size-input-mode`

New UI-owned order-form fields:

- `:size-input-mode` in `#{:quote :base}`
- `:size-input-source` in `#{:manual :percent}`

These fields are normalized in `order_form_state.cljs`, stripped from persisted domain draft payloads by `persist-order-form`, and enforced by app/runtime schema contracts.

Plan revision note: 2026-02-19 22:03Z - Initial living ExecPlan recorded after implementation to capture observed Hyperliquid behavior, architecture decisions, and remaining validation/move steps.
Plan revision note: 2026-02-19 22:05Z - Updated with completed validation results, production bundle implementation evidence, and final completion status before moving to completed plans.
