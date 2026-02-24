# Order Form TIF Custom Dropdown Parity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, the order form Time In Force (TIF) control in `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs` will behave like a custom dropdown instead of a native browser `select`. Users will see one caret only, the caret will rotate up while the menu is open, the menu will close on outside click/option selection/Escape, and the open-close motion will animate with the same style family as Hyperliquid (fade + small vertical translate). A user can verify this by opening the Limit ticket, clicking `GTC`, observing the popover animation and rotated caret, then clicking outside or selecting `IOC`/`ALO` and observing close + caret reset.

## Progress

- [x] (2026-02-23 23:27Z) Captured live Hyperliquid TIF trigger/menu behavior and CSS details via browser inspection session against `https://app.hyperliquid.xyz/trade`.
- [x] (2026-02-23 23:30Z) Created this ExecPlan and recorded concrete parity targets and implementation scope.
- [x] (2026-02-23 23:33Z) Implemented custom TIF dropdown rendering, caret rotation, outside-close overlay, and animated open/close motion in `order_form_component_sections.cljs`.
- [x] (2026-02-23 23:35Z) Added TIF dropdown UI state/action wiring through commands, runtime gateway, actions, transitions, registry, and collaborators.
- [x] (2026-02-23 23:36Z) Updated app/order-form contracts for new `:tif-dropdown-open?` UI flag.
- [x] (2026-02-23 23:39Z) Updated and extended tests for dropdown rendering/interaction, command coverage, runtime mapping, and state-transition invariants.
- [x] (2026-02-23 23:42Z) Ran validation gates (`npm run check`, `npm test`, `npm run test:websocket`) with all passing.

## Surprises & Discoveries

- Observation: Hyperliquid TIF dropdown is a custom popover, not native `select`; the trigger and list are div-based and styled through generated classes.
  Evidence: Live DOM capture returned trigger class `sc-iJnaPW dEDyOL variant_default` and list class `sc-bYMpWt gMLyQx dropper-select-list variant_default`.
- Observation: Hyperliquid uses explicit open/close keyframe animations rather than only transitions.
  Evidence: CSS rules included keyframes `geaNeN` (`opacity 0 -> 1`, `translateY(-5px) -> 0`) and `fJqcqh` (`opacity 1 -> 0`, `translateY(0) -> -5px`) with `0.3s ease`.
- Observation: Caret rotation is direct style mutation on the trigger icon with a 0.3s transition.
  Evidence: Open-state chevron had inline style `transform: rotate(180deg);` and computed `transition-duration: 0.3s`.

## Decision Log

- Decision: Implement TIF as a custom dropdown component with explicit open-state in `:order-form-ui`.
  Rationale: Required for deterministic caret state, outside-click behavior, and custom open-close animation parity.
  Date/Author: 2026-02-23 / Codex
- Decision: Reuse existing order-form runtime flow (commands -> runtime gateway -> actions -> transitions) instead of adding local component state.
  Rationale: Keeps behavior deterministic, testable, and aligned with repository interaction/runtime policy.
  Date/Author: 2026-02-23 / Codex
- Decision: Implement open/close motion with persistent DOM node + class-based transition instead of introducing a dedicated closing-state timer.
  Rationale: Achieves smooth fade/translate in both directions while avoiding additional delayed runtime actions and preserving deterministic event flow.
  Date/Author: 2026-02-23 / Codex

## Outcomes & Retrospective

The native TIF `select` has been replaced by a custom dropdown that matches Hyperliquid’s interaction model: single caret, caret rotation while open, outside click close, Escape close, and option-select close. The menu now uses dark popover styling and 300ms fade/translate motion aligned to captured parity behavior. Runtime wiring and contracts were extended with `:tif-dropdown-open?` and new TIF dropdown actions/commands, and all required repository gates passed after updates.

## Context and Orientation

The current TIF control is a native `:select` in `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs` under `tif-inline-control`. This currently causes browser-native control behavior and cannot support parity interactions (custom popover animation/caret lifecycle).

Order-form UI interaction state is centralized in `:order-form-ui` and managed through:

- Commands: `/hyperopen/src/hyperopen/views/trade/order_form_commands.cljs`
- Command -> action mapping: `/hyperopen/src/hyperopen/views/trade/order_form_runtime_gateway.cljs`
- Action handlers: `/hyperopen/src/hyperopen/order/actions.cljs`
- Pure transitions: `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`
- UI defaults/normalization: `/hyperopen/src/hyperopen/trading/order_form_state.cljs`
- Runtime registration: `/hyperopen/src/hyperopen/registry/runtime.cljs`, `/hyperopen/src/hyperopen/runtime/collaborators.cljs`, `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
- App-state contract checks: `/hyperopen/src/hyperopen/schema/contracts.cljs`

The same architecture already exists for `:pro-order-type-dropdown-open?` and `:size-unit-dropdown-open?`, which is the implementation anchor for the new TIF dropdown flag.

## Plan of Work

First, replace the native `select` rendering in `tif-inline-control` with a custom trigger button and popover options (`GTC`, `IOC`, `ALO`). The trigger will show current TIF value and a single caret icon. The caret will rotate when open, and the menu will animate in/out using classes equivalent to Hyperliquid motion (`opacity` + `translateY` over `~300ms`).

Second, introduce a new UI flag `:tif-dropdown-open?` in order-form UI defaults and normalization. Add transitions/actions/commands for toggle, close, and Escape handling, mirroring the pattern used by size-unit/pro dropdowns. Ensure selecting a TIF option updates `[:tif]` and closes the menu.

Third, wire new action ids through runtime registration/composition and contracts so command dispatch remains valid and checked.

Fourth, update tests in the order-form view/component/commands/runtime/transitions/schema surface to cover the new custom control and prevent regressions.

## Concrete Steps

From `/hyperopen`:

1. Edit order-form UI state and transitions:
   - `src/hyperopen/trading/order_form_state.cljs`
   - `src/hyperopen/trading/order_form_transitions.cljs`
   - `src/hyperopen/order/actions.cljs`

2. Edit command/runtime mappings and registrations:
   - `src/hyperopen/views/trade/order_form_commands.cljs`
   - `src/hyperopen/views/trade/order_form_runtime_gateway.cljs`
   - `src/hyperopen/views/trade/order_form_handlers.cljs`
   - `src/hyperopen/registry/runtime.cljs`
   - `src/hyperopen/runtime/collaborators.cljs`
   - `src/hyperopen/runtime/registry_composition.cljs`
   - `src/hyperopen/core/public_actions.cljs`

3. Replace TIF UI markup and animation classes:
   - `src/hyperopen/views/trade/order_form_component_sections.cljs`
   - `src/hyperopen/views/trade/order_form_view.cljs`

4. Update contract/state key expectations:
   - `src/hyperopen/schema/contracts.cljs`
   - `src/hyperopen/state/trading.cljs`

5. Update/add tests across affected modules.

6. Run validation:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance is complete when all of the following are true:

- In Limit-like order form mode, clicking TIF trigger opens a custom menu (not native select UI).
- Trigger caret rotates up while menu is open and returns down when closed.
- Menu closes when user:
  - clicks outside,
  - selects `GTC`/`IOC`/`ALO`,
  - presses Escape while focused on trigger/menu.
- Menu appears/disappears with fade + slight vertical translate motion.
- All required gates pass:
  - `npm run check`
  - `npm test`
  - `npm run test:websocket`

## Idempotence and Recovery

Edits are additive and safe to rerun. If a partial change fails tests, rerun the same commands after completing remaining files. No destructive data migration is involved. If runtime registration fails due missing handler wiring, recover by ensuring each new action id appears consistently in contracts, registry bindings, and collaborator/composition maps.

## Artifacts and Notes

Hyperliquid inspection reference details captured during this plan:

- Trigger class/style family: `dEDyOL` (`inline-flex`, transparent background, 12px text, 8px radius, hover text brighten).
- Caret behavior: trigger child svg transition `0.3s`; inline style toggles between `rotate(0deg)` and `rotate(180deg)`.
- Menu class/style family: `gMLyQx dropper-select-list` (`position:absolute`, `z-index:800`, `background:#1B2429`, `border:1px solid #273035`, `border-radius:8px`).
- Open animation: `geaNeN` keyframe (`opacity 0 -> 1`, `translateY(-5px) -> 0`, `0.3s ease`).
- Close animation: `fJqcqh` keyframe (`opacity 1 -> 0`, `translateY(0) -> -5px`, `0.3s ease`).

## Interfaces and Dependencies

No external library additions are needed. The change extends existing order-form interfaces by adding new action/command ids and one UI-state flag:

- New UI key in `:order-form-ui`: `:tif-dropdown-open?`.
- New command ids (expected):
  - `:order-form/toggle-tif-dropdown`
  - `:order-form/close-tif-dropdown`
  - `:order-form/handle-tif-dropdown-keydown`
- New runtime action ids (expected):
  - `:actions/toggle-tif-dropdown`
  - `:actions/close-tif-dropdown`
  - `:actions/handle-tif-dropdown-keydown`

Plan revision note: 2026-02-23 23:30Z - Initial plan created after live Hyperliquid inspection to lock parity targets before implementation.
Plan revision note: 2026-02-23 23:42Z - Marked implementation/testing complete, added final decisions, and recorded outcomes after all required validation gates passed.
