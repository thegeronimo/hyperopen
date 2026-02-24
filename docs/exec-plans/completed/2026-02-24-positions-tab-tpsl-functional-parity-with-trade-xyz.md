# Positions Tab TP/SL Functional Parity with trade.xyz

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, clicking the TP/SL control in a Positions row will open a working Position TP/SL flow instead of doing nothing. A user will be able to set take-profit and stop-loss triggers for an existing position from the Positions tab, optionally set custom reduce size, optionally set TP/SL limit prices, and submit those orders with validation and messaging behavior aligned to trade.xyz.

A user can verify this by opening the Positions tab, clicking the TP/SL edit affordance for a live position, seeing a Position TP/SL modal with prefilled position context (asset, size, value, entry price, mark price), entering TP and/or SL values, and placing the orders. The submission should result in TP/SL-trigger orders visible in Open Orders and Order History, with `TP/SL` labeling for position TP/SL rows.

## Progress

- [x] (2026-02-24 16:19Z) Audited current Hyperopen Positions TP/SL cell implementation in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`; confirmed the edit button has no click handler.
- [x] (2026-02-24 16:19Z) Audited current trade/order architecture in `/hyperopen/src/hyperopen/views/trade/order_form_*.cljs`, `/hyperopen/src/hyperopen/state/trading.cljs`, `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`, and runtime registration files.
- [x] (2026-02-24 16:19Z) Reverse engineered trade.xyz position TP/SL behavior from downloaded production bundle artifacts under `/hyperopen/tmp/trade-xyz-inspect/`.
- [x] (2026-02-24 16:19Z) Authored this ExecPlan with file-level implementation milestones.
- [x] (2026-02-24 18:05Z) Implemented positions TP/SL modal state, actions, runtime wiring, and row click dispatch in Positions tab.
- [x] (2026-02-24 18:07Z) Implemented pure TP/SL validator + request builder in `/hyperopen/src/hyperopen/account/history/position_tpsl.cljs` with side-aware checks and dynamic submit labels.
- [x] (2026-02-24 18:12Z) Implemented dedicated TP/SL modal UI with overlay, context rows, amount/limit toggles, dynamic CTA, and Escape-key close handling.
- [x] (2026-02-24 18:16Z) Added regression tests for row wiring, modal actions, validator/request behavior, and submit flow (`actions_test`, `positions_test`, `position_tpsl_test`).
- [x] (2026-02-24 18:20Z) Ran required validation gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: The current Positions TP/SL affordance is purely visual.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` renders a button in `position-row` without any `:on` handler.

- Observation: trade.xyz uses a dedicated order type for this flow (`POSITION_TPSL`) rather than reusing a generic entry form mode.
  Evidence: `/hyperopen/tmp/trade-xyz-inspect/beauty/7899-0617508e185314bd.beauty.js` around lines ~5623 and ~8236 includes `validatePositionTpSlOrder` and `POSITION_TPSL = "position-tpsl"`.

- Observation: trade.xyz preloads position TP/SL with full reduce size and reduce-only semantics and flips side semantics in a special-case path.
  Evidence: `/hyperopen/tmp/trade-xyz-inspect/beauty/7899-0617508e185314bd.beauty.js` around line ~8303 sets `tradingFormOrderSize`, `tradingFormIsReduceOnly = true`, and special `position-tpsl` side behavior.

- Observation: Position TP/SL submit copy is dynamic (`Place TP/SL Orders`, `Place TP Order`, `Place SL Order`) based on enabled legs.
  Evidence: `/hyperopen/tmp/trade-xyz-inspect/beauty/7899-0617508e185314bd.beauty.js` around line ~5676.

- Observation: Full live computed CSS/DOM tree for the trade.xyz Position TP/SL modal could not be captured from an authenticated open-position session in this environment.
  Evidence: Browser artifacts under `/hyperopen/tmp/trade-xyz-inspect/` include row-level screenshots and bundle logic, but not a complete authenticated modal DOM capture with computed styles.

- Observation: This repository's `npm test` command does not support test-namespace filtering via additional CLI args; unknown args are ignored and the full suite runs.
  Evidence: Running `npm test -- account/history/actions` prints `Unknown arg: account/history/actions` and then executes all generated test namespaces.

## Decision Log

- Decision: Implement Positions TP/SL as its own account-table modal state and action flow instead of piggybacking the right-rail order ticket draft.
  Rationale: The interaction is row-scoped to an existing position, has dedicated validation semantics, and should not mutate unrelated order-ticket draft state.
  Date/Author: 2026-02-24 / Codex

- Decision: Reuse existing Hyperliquid wire-order shape generation (`build-tpsl-orders`) for payload structure, while adding a position-scoped request builder and validation layer.
  Rationale: This minimizes wire-format risk and keeps signing/order payload behavior consensus-safe.
  Date/Author: 2026-02-24 / Codex

- Decision: Add a dedicated submit path for position TP/SL (action/effect pair) rather than forcing all state through `:order-form-runtime`.
  Rationale: Position TP/SL UX needs its own pending/error lifecycle and close-on-success behavior without side effects in the main order ticket runtime state.
  Date/Author: 2026-02-24 / Codex

- Decision: Keep TP/SL validation and request assembly as pure functions in an account-history-scoped module, and add a dedicated unit-test namespace for that module.
  Rationale: This keeps modal behavior deterministic and testable without DOM/runtime coupling while preserving existing signing/wire request builders.
  Date/Author: 2026-02-24 / Codex

## Outcomes & Retrospective

Implemented and validated. Clicking the TP/SL affordance in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` now opens a working row-scoped Position TP/SL modal, with prefilled position context, optional amount/limit controls, dynamic submit labels, and submit/error lifecycle isolated from the right-rail order form runtime.

Validation and request assembly now live in `/hyperopen/src/hyperopen/account/history/position_tpsl.cljs`, and submission is routed through a dedicated effect (`:effects/api-submit-position-tpsl`) in `/hyperopen/src/hyperopen/order/effects.cljs`, including wallet/agent guards, success reset, and order-history refresh behavior.

Regression coverage was added in:

- `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`
- `/hyperopen/test/hyperopen/account/history/actions_test.cljs`
- `/hyperopen/test/hyperopen/account/history/position_tpsl_test.cljs`

All required gates passed on completion (`npm run check`, `npm test`, `npm run test:websocket`).

Remaining gap: exact authenticated live computed-style parity for every modal element still depends on an authenticated trade.xyz devtools capture, but core behavior and major visual structure are implemented.

## Context and Orientation

The Positions table UI is rendered in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` and mounted via `/hyperopen/src/hyperopen/views/account_info_view.cljs`. Current account table state and tab derivations are built in `/hyperopen/src/hyperopen/views/account_info/vm.cljs` with defaults in `/hyperopen/src/hyperopen/state/app_defaults.cljs`.

Order-form behavior exists separately in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`, `/hyperopen/src/hyperopen/views/trade/order_form_handlers.cljs`, `/hyperopen/src/hyperopen/views/trade/order_form_commands.cljs`, `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs`, `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`, and `/hyperopen/src/hyperopen/order/actions.cljs`. Request assembly and TP/SL trigger payload format are implemented in `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` and consumed via `/hyperopen/src/hyperopen/state/trading.cljs`.

Runtime action/effect registration is centralized in `/hyperopen/src/hyperopen/runtime/collaborators.cljs`, `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`, `/hyperopen/src/hyperopen/registry/runtime.cljs`, and action/effect contract specs in `/hyperopen/src/hyperopen/schema/contracts.cljs` with coverage in `/hyperopen/test/hyperopen/schema/contracts_coverage_test.cljs`.

In this plan, `Position TP/SL modal` means a popup flow launched from a specific Positions row that edits TP/SL trigger orders for that position. `Position-scoped request` means the request contains reduce-only trigger orders referencing the clicked row’s market/asset identity, not the currently selected right-rail ticket market unless they are the same.

## Plan of Work

### Milestone 1: Introduce Position TP/SL UI State and Row Click Wiring

Add a dedicated state branch for position TP/SL interaction in `/hyperopen/src/hyperopen/state/app_defaults.cljs`, for example `:positions-ui` containing `:tpsl-modal` fields (`:open?`, `:position-key`, `:coin`, `:dex`, `:entry-price`, `:mark-price`, `:size`, `:size-input`, `:configure-amount?`, `:limit-price?`, `:tp`, `:sl`, `:submitting?`, `:error`).

Add account-history actions in `/hyperopen/src/hyperopen/account/history/actions.cljs` for opening, closing, and editing modal state with single-batch `:effects/save-many` writes. Wire these actions into runtime dependency maps and registration surfaces (`runtime/collaborators`, `runtime/registry_composition`, `registry/runtime`, and `schema/contracts`).

Update `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` so TP/SL button clicks dispatch an open action with row payload (position data plus unique key). Ensure UI-visible state updates occur first and atomically, satisfying `/hyperopen/docs/FRONTEND.md` ordering rules.

Milestone acceptance: Clicking TP/SL in any rendered position row toggles modal open state and stores that row context in state deterministically.

### Milestone 2: Implement Position TP/SL Validation and Request Builder

Create a position-specific validation module (new file under `/hyperopen/src/hyperopen/domain/trading/` or `/hyperopen/src/hyperopen/account/history/`) that reproduces trade.xyz position TP/SL behavior:

- require non-zero reduce size and reject over-position size (`Reduce Too Large`),
- require at least one trigger (TP or SL),
- enforce side-aware trigger direction constraints,
- enforce optional limit-price constraints when limit mode is enabled,
- derive dynamic CTA label text (`Place TP/SL Orders`, `Place TP Order`, `Place SL Order`).

Implement a position-scoped request builder (likely in `/hyperopen/src/hyperopen/state/trading.cljs` or `/hyperopen/src/hyperopen/api/trading.cljs`) that resolves the clicked position market asset index by coin/dex and emits a standard exchange `{:action {:type "order" :orders [...] :grouping ...}}` payload using `build-tpsl-orders` semantics. Keep signing payload conventions unchanged.

Milestone acceptance: A unit-tested pure function receives modal draft + position context and returns either deterministic validation errors or a ready request map with TP/SL trigger orders.

### Milestone 3: Add Dedicated Position TP/SL Submit Runtime Path

Add a dedicated action/effect pair for submitting position TP/SL (for example `:actions/submit-position-tpsl`, `:effects/api-submit-position-tpsl`) so modal pending/error lifecycle is isolated from `:order-form-runtime`. Implement effect behavior in `/hyperopen/src/hyperopen/order/effects.cljs` or a new account-history effects module:

- set modal `:submitting? true`,
- call existing trading API submit path,
- on success clear modal error, close modal, show success toast, and refresh open orders/order history,
- on failure keep modal open and show field-level/general error text.

Register the new effect/action IDs and contracts across runtime maps and contract coverage tests.

Milestone acceptance: Submitting valid modal inputs creates orders and closes modal on success; invalid or failed submissions preserve modal with actionable error message.

### Milestone 4: Implement Modal UI and Styling Parity

Create a dedicated modal view component (new file recommended: `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs`) and mount it from `/hyperopen/src/hyperopen/views/account_info_view.cljs` or `/hyperopen/src/hyperopen/views/app_view.cljs` with fixed overlay layering.

Render UI sections matching the observed trade.xyz flow:

- title `Position TP/SL`,
- read-only context rows (Asset, Size, Value, Entry Price, Mark Price),
- TP and SL input rows,
- optional amount configuration toggle,
- optional limit price toggle,
- primary CTA with dynamic label,
- secondary action for quick create when applicable.

For style parity, reuse existing order-form primitives where possible (`/hyperopen/src/hyperopen/views/trade/order_form_component_primitives.cljs`) and add minimal local style classes/tokens to match spacing, border radius, contrast, and input treatment from the provided screenshots.

Milestone acceptance: Modal is keyboard-operable (Escape to close, visible focus, tab order), visually consistent with trade surface conventions, and supports desktop/mobile viewport constraints.

### Milestone 5: Regression Tests and Validation Gates

Add tests covering behavior and runtime contracts:

- Positions row TP/SL click dispatches open action with row identity.
- Modal state transitions (open, close, toggles, input edits, submit pending).
- Position TP/SL validator edge cases (no triggers, reduce-too-large, side-invalid TP/SL, limit-price constraints).
- Request builder emits correct trigger wire shape with `:r true` and correct side inversion.
- Contract coverage remains exact after adding action/effect IDs.

Recommended test files:

- `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`
- `/hyperopen/test/hyperopen/account/history/actions_test.cljs` (new if needed)
- `/hyperopen/test/hyperopen/domain/trading/*position_tpsl*_test.cljs` (new)
- `/hyperopen/test/hyperopen/schema/contracts_coverage_test.cljs`

Run required repository gates after implementation.

Milestone acceptance: Required gates pass and new tests fail before implementation and pass after implementation.

## Concrete Steps

1. Add state defaults and actions for position TP/SL modal.

   cd /Users//projects/hyperopen
   npm test -- account/history/actions

   Expected result: action-level tests for modal state transitions pass.

2. Implement validator/request builder and unit tests.

   cd /Users//projects/hyperopen
   npm test -- position_tpsl

   Expected result: validator/request tests cover success and failure branches.

3. Wire runtime registration/contracts and submit effect.

   cd /Users//projects/hyperopen
   npm test -- schema/contracts-coverage

   Expected result: no action/effect contract drift.

4. Implement modal rendering and Positions row on-click integration.

   cd /Users//projects/hyperopen
   npm test -- positions

   Expected result: positions/modal render tests pass and inert-button regression is closed.

5. Run full required gates.

   cd /Users//projects/hyperopen
   npm run check
   npm test
   npm run test:websocket

   Expected result: all commands exit with status 0.

## Validation and Acceptance

This change is accepted when all of the following are true:

1. Clicking TP/SL in Positions opens a functional position-scoped TP/SL modal.
2. Modal preloads row context and defaults to full reduce-only position size.
3. TP/SL validation behavior matches trade.xyz logic for core constraints and messages.
4. Optional amount and optional limit-price branches work and validate correctly.
5. Submitting valid inputs creates trigger orders and updates account surfaces.
6. Submitting invalid inputs is blocked with deterministic user-facing errors.
7. Action/effect contracts and runtime registration remain synchronized.
8. `npm run check`, `npm test`, and `npm run test:websocket` all pass.

## Idempotence and Recovery

All implementation steps are source edits and test runs; rerunning steps is safe.

If runtime contract drift appears after adding action/effect IDs, fix by updating all four surfaces together: `registry/runtime.cljs`, `runtime/registry_composition.cljs`, `runtime/collaborators.cljs`, and `schema/contracts.cljs`, then re-run contract coverage tests.

If modal submit lifecycle integration causes regressions, temporarily keep modal open/close and validation behavior while routing submit through existing `:effects/api-submit-order` to isolate transport from UI lifecycle. Then reintroduce dedicated effect behavior in a follow-up patch.

## Artifacts and Notes

trade.xyz reverse-engineering evidence used for this plan:

- `/hyperopen/tmp/trade-xyz-inspect/beauty/7899-0617508e185314bd.beauty.js` around line ~5623 (`validatePositionTpSlOrder`).
- `/hyperopen/tmp/trade-xyz-inspect/beauty/7899-0617508e185314bd.beauty.js` around line ~5676 (dynamic TP/SL CTA labels).
- `/hyperopen/tmp/trade-xyz-inspect/beauty/7899-0617508e185314bd.beauty.js` around line ~8236 (`POSITION_TPSL` enum).
- `/hyperopen/tmp/trade-xyz-inspect/beauty/7899-0617508e185314bd.beauty.js` around line ~8303 (position-to-form TP/SL preset).
- `/hyperopen/tmp/trade-xyz-inspect/extracted-strings.txt` for message strings like `Place TP/SL Orders`, `Reduce Too Large`, `Take Profit Limit Price Too High`, and `Stop Loss Limit Price Too Low`.

Known evidence gap:

- Exact computed style tree for authenticated live modal controls still needs a session capture. Functional parity can proceed now, but final style polish should use authenticated devtools capture.

## Interfaces and Dependencies

No new external dependencies are required.

The following interfaces should exist after implementation:

- New runtime action IDs for position TP/SL modal lifecycle and submission, each with explicit arg specs in `/hyperopen/src/hyperopen/schema/contracts.cljs`.
- A pure position TP/SL validation+request API callable from actions/effects without direct DOM coupling.
- Positions row renderer in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` dispatching TP/SL open action with row payload.
- A mounted TP/SL modal component under `/hyperopen/src/hyperopen/views/account_info/**` with keyboard-safe close/submit interactions.

Plan revision note: 2026-02-24 16:19Z - Initial plan authored from local code audit plus trade.xyz bundle reverse engineering. Added explicit evidence gap for authenticated style capture.
Plan revision note: 2026-02-24 18:22Z - Marked implementation complete, moved plan to `completed/`, recorded decisions/discoveries from execution, and added final gate/test evidence.
