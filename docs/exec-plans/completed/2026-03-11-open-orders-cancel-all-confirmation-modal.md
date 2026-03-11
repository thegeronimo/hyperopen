# Open Orders Cancel-All Confirmation Modal

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Active `bd` issue: `hyperopen-h3m`.

## Purpose / Big Picture

After this change, clicking `Cancel All` in the Open Orders table will open a styled in-app confirmation surface instead of the browser's system dialog. The confirmation must stay aligned with Hyperopen's existing modal and anchored-popover patterns, explain that only currently visible rows will be canceled, and keep the destructive action near the trigger so the user does not lose context.

A user can verify the result by filtering Open Orders, clicking the red `Cancel All` header button, seeing a styled confirmation panel appear near that button, dismissing it with backdrop, close button, or Escape, and confirming that only the currently visible rows are canceled when they approve the action.

## Progress

- [x] (2026-03-11 14:42Z) Audited the current cancel-all implementation, current Open Orders view wiring, and the relevant UI policy/docs.
- [x] (2026-03-11 14:49Z) Claimed existing `bd` issue `hyperopen-h3m` and created this active ExecPlan.
- [x] (2026-03-11 15:07Z) Replaced the effect-driven browser confirm path with state-driven open, close, Escape, and submit actions backed by account-info UI state.
- [x] (2026-03-11 15:11Z) Rendered a styled confirmation surface in `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`, anchored it near the header trigger on desktop, and added centered fallback behavior when no anchor is available.
- [x] (2026-03-11 15:16Z) Removed obsolete runtime confirm-effect wiring and added regression coverage for the new open, dismiss, and submit flow.
- [x] (2026-03-11 15:27Z) Ran `npm run check`, `npm test`, and `npm run test:websocket` successfully and prepared this plan for move to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: The currently shipped cancel-all flow is still routed through a dedicated effect that calls `window.confirm`, so the UI cannot be restyled without changing the interaction shape.
  Evidence: `/hyperopen/src/hyperopen/order/actions.cljs` emits `:effects/confirm-cancel-visible-open-orders`, and `/hyperopen/src/hyperopen/order/effects.cljs` resolves that by calling the injected `confirm-fn`.

- Observation: Hyperopen already has two relevant surface patterns that fit this task: fully centered modals and anchored desktop popovers with a backdrop.
  Evidence: `/hyperopen/src/hyperopen/views/funding_modal.cljs` uses `anchored-popover/anchored-popover-layout-style` for desktop and a centered/mobile sheet fallback, while `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs` uses the account-surface overlay style family.

- Observation: The Open Orders table already computes the exact visible row set after sort, direction filter, and coin search in one place.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` renders from the `sorted` value returned by `memoized-sorted-open-orders`.

- Observation: The account-table helper already supports an optional footer slot, which made it possible to mount a fixed-position overlay without changing the table helper or disturbing existing header/row tests.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/table.cljs` `tab-table-content` accepts `[header rows footer]`, and the confirmation surface now renders through that footer while keeping header traversal stable.

- Observation: The typography lint gate rejects explicit font utilities under `16px`, so the count pill had to use the shared tokenized `text-xs` utility instead of an arbitrary `text-[11px]` value.
  Evidence: `npm test` initially failed `hyperopen.views.typography-scale-test` with `Forbidden explicit text utilities found: src/hyperopen/views/account_info/tabs/open_orders.cljs => [\"text-[11px]\"]`.

## Decision Log

- Decision: Keep the existing visible-scope batch cancel request and change only the confirmation surface plumbing.
  Rationale: The underlying cancel behavior is already correct and tested. Replacing the confirmation layer in isolation minimizes risk while satisfying the UX request.
  Date/Author: 2026-03-11 / Codex

- Decision: Model the new confirmation as ordinary UI state stored under `:account-info :open-orders` instead of a runtime effect.
  Rationale: A styled dialog needs view-driven state, dismissal controls, anchor geometry, and deterministic close-before-submit behavior. A browser confirm effect cannot provide that cleanly.
  Date/Author: 2026-03-11 / Codex

- Decision: Keep the existing `:actions/confirm-cancel-visible-open-orders` action id but change its implementation from “invoke a confirm effect” to “open the in-app confirmation surface.”
  Rationale: That kept the public action name already used by the view and tests while eliminating the obsolete effect layer. The real destructive step now lives in the new submit action.
  Date/Author: 2026-03-11 / Codex

- Decision: Use the anchored-popover layout helper on wider viewports and a centered modal fallback otherwise.
  Rationale: The user asked for something that stays near the trigger and feels like Hyperopen’s existing popups. The existing anchored helper already solves the near-trigger desktop placement problem, while the centered fallback preserves usability when bounds are unavailable or the viewport is narrow.
  Date/Author: 2026-03-11 / Codex

## Outcomes & Retrospective

Implementation completed successfully. Open Orders `Cancel All` now opens a styled in-app confirmation surface instead of the browser dialog. The surface can be dismissed with backdrop click, close button, or Escape, and confirming it closes the surface immediately before reusing the existing batched visible-order cancel pipeline.

Validation outcomes:

- `npm run check`: pass
- `npm test`: pass (`Ran 2267 tests containing 11818 assertions. 0 failures, 0 errors.`)
- `npm run test:websocket`: pass (`Ran 372 tests containing 2128 assertions. 0 failures, 0 errors.`)

Retrospective: Overall complexity increased slightly because the feature now owns a dedicated confirmation state model and three small UI actions, but it reduced interaction complexity at runtime by deleting the special-case browser confirm effect and consolidating the confirmation into the same state-driven surface model used elsewhere in the app.

## Context and Orientation

The Open Orders table lives in `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`. That file already owns the rendered header button and the filtered/sorted row set that defines "orders in view." The account info view model is built in `/hyperopen/src/hyperopen/views/account_info/vm.cljs`, and `/hyperopen/src/hyperopen/state/app_defaults.cljs` defines the default shape of the account-info UI state.

The current cancel-all interaction is split across `/hyperopen/src/hyperopen/order/actions.cljs`, `/hyperopen/src/hyperopen/order/effects.cljs`, `/hyperopen/src/hyperopen/runtime/effect_adapters/order.cljs`, `/hyperopen/src/hyperopen/app/effects.cljs`, and runtime registration files under `/hyperopen/src/hyperopen/runtime/**` and `/hyperopen/src/hyperopen/schema/**`. Today that path exists only to show a system confirm prompt and then dispatch the already-existing batched cancel action.

An "anchored popover" in this repository means a fixed-position panel that uses the clicked element's screen bounds to place itself near the trigger while still rendering above a backdrop. The reusable layout math for that pattern lives in `/hyperopen/src/hyperopen/views/ui/anchored_popover.cljs`.

## Plan of Work

First, add a default confirmation model for Open Orders UI state and expose it through the account-info view model so the view can render or hide the surface without reaching into raw state. The model must hold `open?`, the visible order rows to cancel, and the trigger bounds used for desktop placement.

Next, replace the current `confirm-cancel-visible-open-orders` action/effect split with state-driven actions. The header button should open the confirmation model with visible rows plus `:event.currentTarget/bounds`. A close action should reset the confirmation model. A submit action should close the model first and then reuse the existing batched cancel request path so the destructive network behavior remains centralized in `:effects/api-cancel-order`.

Then, render a styled confirmation surface in the Open Orders tab. On desktop with a complete anchor, use the anchored-popover layout helper so the panel stays close to the `Cancel All` header. When no anchor is available, fall back to a centered modal-style presentation. Include a clear title, an explanatory sentence that hidden rows are excluded, a count of visible orders, a neutral cancel button, and a destructive confirm button. Support close button, backdrop click, and Escape dismissal.

Finally, remove obsolete effect-adapter and registration plumbing for the browser confirm effect, update tests to cover the new action flow and rendering, run the required validation gates, and move this plan to completed with final notes.

## Concrete Steps

Run from `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/state/app_defaults.cljs`, `/hyperopen/src/hyperopen/views/account_info/vm.cljs`, and the relevant action modules so Open Orders owns a default confirmation model plus open/close/submit actions.
2. Edit `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` to dispatch the new open action with visible rows and trigger bounds, then render the confirmation surface from `open-orders-state`.
3. Remove the no-longer-needed browser confirm effect wiring from `/hyperopen/src/hyperopen/order/effects.cljs`, `/hyperopen/src/hyperopen/runtime/effect_adapters/order.cljs`, `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`, `/hyperopen/src/hyperopen/app/effects.cljs`, and the runtime registration/contract files.
4. Update regression coverage in the Open Orders view tests and the action/bootstrap tests so the surface open, close, and submit flows are locked in.
5. Run:

   npm run check
   npm test
   npm run test:websocket

## Validation and Acceptance

Acceptance is complete only when all of the following are true:

- Clicking the Open Orders `Cancel All` header opens a styled Hyperopen confirmation surface rather than a browser/system dialog.
- The confirmation surface explains that only currently visible orders are affected.
- On desktop, the surface appears near the trigger when bounds are available instead of jumping to a random location.
- Backdrop click, explicit cancel/close controls, and Escape dismiss the surface without firing a cancel request.
- Confirming the surface closes it immediately and then sends the existing batched visible-order cancel flow.
- `npm run check`, `npm test`, and `npm run test:websocket` pass.

## Idempotence and Recovery

The change is safe to repeat because it only modifies local UI state, view rendering, and action wiring. If the anchored presentation behaves badly in some viewport, the safest fallback is to keep the same state/action model and temporarily force the centered modal branch while leaving the browser confirm path removed.

## Artifacts and Notes

The intended design reference comes from existing in-app surfaces, not the browser. The closest local examples are:

- `/hyperopen/src/hyperopen/views/funding_modal.cljs` for anchored desktop overlay behavior with a backdrop.
- `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs` and `/hyperopen/src/hyperopen/views/account_info/position_margin_modal.cljs` for visual styling and destructive-action hierarchy on trading surfaces.

## Interfaces and Dependencies

Existing interfaces that should continue to exist:

- `hyperopen.order.actions/cancel-visible-open-orders`
- `hyperopen.order.effects/api-cancel-order`
- `hyperopen.views.account-info.tabs.open-orders/open-orders-tab-content`

Interfaces that should exist after this change:

- A default Open Orders cancel-all confirmation state constructor or constant reachable from both defaults and actions.
- An action that opens the confirmation surface with visible rows and anchor bounds.
- An action that closes the confirmation surface.
- An action that submits the confirmation and reuses the batched cancel path.

Plan revision note: 2026-03-11 14:49Z - Initial active plan created after auditing the current browser-confirm implementation and claiming `bd` issue `hyperopen-h3m`.
Plan revision note: 2026-03-11 15:27Z - Updated progress, discoveries, decisions, and outcomes after implementation completed and all required validation gates passed.
