# Smooth Dropdown Menu Motion

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-2wgg`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

After this change, the dropdown menus that currently appear abruptly on the trading route should open with a fast downward motion instead of popping in. The target user-facing surfaces for this pass are the connected-wallet menu in the header, the header `More` menu, the `Pro` order-type dropdown, the size-unit dropdown shown as `USDC` in the size field, and the adjacent order-form dropdown/popover surfaces that should share the same motion treatment once the pattern is in place.

This pass is intentionally narrow. It does not redesign dropdown layout, spacing, copy, or state ownership. It also does not attempt to re-architect header menus into a new global menu system unless the existing native disclosure structure proves impossible to animate safely.

## Progress

- [x] (2026-03-18 20:00Z) Created and claimed `bd` issue `hyperopen-2wgg` for dropdown motion work.
- [x] (2026-03-18 20:15Z) Read the UI planning and QA requirements in `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/browser-qa.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, `/hyperopen/docs/agent-guides/trading-ui-policy.md`, and `/hyperopen/docs/MULTI_AGENT.md`.
- [x] (2026-03-18 20:18Z) Confirmed the wallet and `More` menus in `/hyperopen/src/hyperopen/views/header_view.cljs` are native `<details>` disclosures, while the order-form dropdowns use explicit booleans in `:order-form-ui`.
- [x] (2026-03-18 20:20Z) Confirmed the order form already contains a usable motion precedent in `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs` for the TIF and TP/SL unit menus.
- [x] (2026-03-18 20:12Z) Implemented a shared `ui-dropdown-panel` motion surface in `/hyperopen/src/styles/main.css`, applied it to the header wallet / `More` menus and the order-form dropdowns/popovers, and updated the relevant view tests for mounted-but-hidden panels.
- [x] (2026-03-18 20:17Z) Ran `npm test` successfully after fixing two delimiter mistakes introduced during the markup refactor and tightening one stale view assertion that had assumed hidden menu content was absent from the Hiccup tree.
- [x] (2026-03-18 20:17Z) Ran `npm run test:websocket` successfully.
- [x] (2026-03-18 20:18Z) Ran `npm run check` successfully.
- [x] (2026-03-18 20:20Z) Ran browser validation at `375`, `768`, `1280`, and `1440` through `/hyperopen/tmp/browser-inspection/design-review-2026-03-18T20-18-12-137Z-6f3568f9/`, recorded the PASS / FAIL results, and filed follow-up `bd` issue `hyperopen-vp2j` for unrelated cross-route QA findings surfaced by the shared header/styles sweep.

## Surprises & Discoveries

- Observation: the header wallet menu and header `More` menu still rely on native `<details>` behavior in `/hyperopen/src/hyperopen/views/header_view.cljs`, which means a normal mount/unmount transition is not available without either overriding the user-agent hidden state or replacing the disclosure structure.
- Observation: the `Pro` dropdown and the size-unit `USDC` dropdown currently mount their panels only while open, so adding transition classes alone would not create a visible motion effect.
- Observation: `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs` already keeps the TIF and TP/SL unit menus mounted and toggles them with `visible` / `invisible`, `opacity`, and `translate-y` classes. That existing pattern is the closest in-repo precedent and should be reused instead of inventing a third menu style.

## Decision Log

- Decision: keep the current open-state booleans and command handlers for order-form menus.
  Reason: the user asked for less jarring motion, not a behavior rewrite. The existing `:order-form-ui` booleans already support deterministic open/close updates, escape handling, and close-on-selection flows.

- Decision: keep the header menus on native `<details>` for this pass unless animation proves impossible with a CSS hook.
  Reason: preserving native disclosure semantics avoids unnecessary header state churn and keeps the change scoped to motion.

- Decision: implement one shared menu-motion surface in `/hyperopen/src/styles/main.css`.
  Reason: the affected menus span multiple namespaces. A shared CSS component keeps timing, transform, visibility, and reduced-motion behavior aligned without duplicating class bundles in every view function.

- Decision: keep the browser-QA follow-up separate from this dropdown-motion change and track it in `hyperopen-vp2j`.
  Reason: the required design review swept `/portfolio`, `/trade`, and `/vaults` because this patch touched shared header/styles files. The resulting failures are broader route-level overflow, focus, and jank findings rather than a direct regression in the dropdown-motion implementation itself.

## Outcomes & Retrospective

- Implemented a shared `ui-dropdown-panel` motion surface in `/hyperopen/src/styles/main.css` and used it to make the header wallet menu, the desktop `More` menu, the `Pro` menu, the `USDC` size-unit dropdown, and adjacent order-form dropdown/popover surfaces open with a quick downward motion rather than a hard pop.
- Preserved the existing state model and action identifiers. The state-driven order-form panels now stay mounted and expose explicit open/closed attributes for animation, while the native header `<details>` menus use the same surface through a native-details hook.
- Updated the pure view tests so they assert explicit panel state (`data-ui-state`, `aria-hidden`, and motion hooks) instead of relying on old conditional-mount behavior.
- All required automated gates passed: `npm test`, `npm run test:websocket`, and `npm run check`.
- The required design review did not end `PASS` overall because the changed files routed QA through `/portfolio`, `/trade`, and `/vaults`, where the tool reported existing route-level layout/focus/jank findings. The targeted trade-route review still passed `visual`, `native-control`, `styling-consistency`, and `interaction` at all four required widths. Follow-up work is tracked in `hyperopen-vp2j`.

## Context and Orientation

The header dropdowns live in `/hyperopen/src/hyperopen/views/header_view.cljs`. The connected-wallet trigger is `wallet-trigger`; the connected-wallet panel is `wallet-menu`; the wrapper is `wallet-control`. The desktop `More` menu is another `<details>` disclosure further down in the same file. These menus currently render immediately when opened and do not share a reusable menu primitive.

The trading-form dropdowns live across `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` and `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs`. The `USDC` size-unit dropdown is `size-unit-accessory` in `order_form_view.cljs`. The `Pro` dropdown is inside `entry-mode-tabs` in `order_form_component_sections.cljs`. TIF and TP/SL unit menus in `order_form_component_sections.cljs` already use the mounted-but-hidden pattern with `aria-hidden`, `opacity`, and `translate-y` changes; those components are the nearest motion reference for this task. The order-form booleans are stored in `:order-form-ui` and surfaced through the handlers in `/hyperopen/src/hyperopen/views/trade/order_form_handlers.cljs`, the actions in `/hyperopen/src/hyperopen/order/actions.cljs`, and the transitions in `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`.

The shared CSS surface for custom component rules is `/hyperopen/src/styles/main.css`. Existing UI tests that cover these menus live in:

- `/hyperopen/test/hyperopen/views/header_view_test.cljs`
- `/hyperopen/test/hyperopen/views/trade/order_form_view/entry_mode_test.cljs`
- `/hyperopen/test/hyperopen/views/trade/order_form_view/size_and_slider_test.cljs`
- `/hyperopen/test/hyperopen/views/trade/order_form_component_sections_test.cljs`

## Plan of Work

Add a shared dropdown-motion surface to `/hyperopen/src/styles/main.css`. The CSS should define the fast menu timing, the slight upward starting offset that resolves downward into place, visibility gating, and a reduced-motion fallback. The shared surface must work in two modes: state-driven menus that expose an explicit open/closed attribute, and native `<details>` menus that should animate from their parent `open` attribute without requiring app-state ownership.

Update `/hyperopen/src/hyperopen/views/header_view.cljs` so the wallet menu panel and the desktop `More` menu panel use the shared motion surface. Preserve the existing native `<details>` wrappers and existing trigger semantics. Give each panel an explicit motion hook and origin so the wallet menu still opens from the right edge and the `More` menu still opens from the left edge.

Update `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs` and `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` so the `Pro`, size-unit, margin-mode, leverage, TIF, and TP/SL unit panels all render through the same motion surface. For menus that currently mount only while open, keep the overlay behavior conditional but keep the floating panel mounted and flip it between open and closed state via attributes and `aria-hidden`. Preserve all existing click, keydown, and select handlers.

Update the relevant tests under `/hyperopen/test/hyperopen/views/**` so they assert the new motion hooks and hidden/open state contract instead of relying on the old “panel absent when closed” behavior. Keep the assertions concrete: open/closed attributes, `aria-hidden`, and the existing action wiring should remain verifiable from pure Hiccup.

## Concrete Steps

From `/hyperopen`:

1. Create the shared dropdown-motion CSS in `src/styles/main.css`.
2. Update `src/hyperopen/views/header_view.cljs` to opt the header dropdown panels into the shared motion surface.
3. Update `src/hyperopen/views/trade/order_form_component_sections.cljs` and `src/hyperopen/views/trade/order_form_view.cljs` so the order-form dropdown panels stay mounted and expose explicit open/closed state for animation.
4. Update the affected tests in `test/hyperopen/views/header_view_test.cljs`, `test/hyperopen/views/trade/order_form_view/entry_mode_test.cljs`, `test/hyperopen/views/trade/order_form_view/size_and_slider_test.cljs`, and `test/hyperopen/views/trade/order_form_component_sections_test.cljs`.
5. Run:

    cd /hyperopen
    npm test
    npm run test:websocket
    npm run check

Expected result after implementation:

    > hyperopen@0.1.0 test
    ...
    <all tests pass>

    > hyperopen@0.1.0 test:websocket
    ...
    <all websocket tests pass>

    > hyperopen@0.1.0 check
    ...
    <check passes>

Actual result:

    npm test                # passed
    npm run test:websocket  # passed
    npm run check           # passed

Browser review result:

    npm run qa:design-ui -- --changed-files src/styles/main.css,src/hyperopen/views/header_view.cljs,src/hyperopen/views/trade/order_form_view.cljs,src/hyperopen/views/trade/order_form_component_sections.cljs --manage-local-app

    overall state: FAIL
    trade-route: visual PASS, native-control PASS, styling-consistency PASS, interaction PASS
    follow-up bd issue: hyperopen-vp2j

## Validation and Acceptance

Acceptance is behavior, not just class changes.

On the live trade route, opening the connected-wallet menu, the header `More` menu, the `Pro` dropdown, and the `USDC` size-unit dropdown should no longer feel abrupt. Each target panel should appear quickly with a slight downward settling motion instead of a hard pop, and reduced-motion users should get an instant state change without the animated transform.

Automated acceptance requires:

    cd /hyperopen
    npm test
    npm run test:websocket
    npm run check

Browser acceptance requires reviewing the changed menus at `375`, `768`, `1280`, and `1440` and reporting every required pass:

- `visual`
- `native-control`
- `styling-consistency`
- `interaction`
- `layout-regression`
- `jank-perf`

Each pass must be recorded as `PASS`, `FAIL`, or `BLOCKED` with any evidence-backed issues called out explicitly.

Recorded browser-QA result for this implementation:

- `/trade` at `375`: `visual PASS`, `native-control PASS`, `styling-consistency PASS`, `interaction PASS`, `layout-regression FAIL`, `jank-perf PASS`
- `/trade` at `768`: `visual PASS`, `native-control PASS`, `styling-consistency PASS`, `interaction PASS`, `layout-regression FAIL`, `jank-perf FAIL`
- `/trade` at `1280`: `visual PASS`, `native-control PASS`, `styling-consistency PASS`, `interaction PASS`, `layout-regression FAIL`, `jank-perf PASS`
- `/trade` at `1440`: `visual PASS`, `native-control PASS`, `styling-consistency PASS`, `interaction PASS`, `layout-regression FAIL`, `jank-perf PASS`

The overall review also swept `/portfolio` and `/vaults` because this patch touched shared header/styles files. Those routes produced additional failures that are tracked in `hyperopen-vp2j`.

## Idempotence and Recovery

The planned edits are normal tracked-file changes and are safe to repeat. Re-running the quality gates is idempotent. If the mounted-but-hidden menu panels create focus or hit-target regressions, revert the always-mounted change for the affected menu first and keep the shared CSS surface intact so the remaining menus can still use it.

## Artifacts and Notes

Key implementation anchors:

- Header menus: `/hyperopen/src/hyperopen/views/header_view.cljs`
- Order-form menus: `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`
- Order-form section menus: `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs`
- Shared CSS surface: `/hyperopen/src/styles/main.css`

Sub-agent findings recorded during planning:

- The wallet and `More` menus are native `<details>` disclosures and need a CSS-based hook rather than a standard state-driven enter animation.
- The `Pro` and size-unit menus already have deterministic open booleans, so the smallest motion change is to keep those booleans and stop conditionally mounting the floating panel.

## Interfaces and Dependencies

No new libraries are required. This work must stay within the existing Tailwind-plus-custom-CSS stack and the current order-form/header action system.

The shared motion surface must be usable by:

- native `<details>` dropdown panels in `/hyperopen/src/hyperopen/views/header_view.cljs`
- explicit boolean-driven floating panels in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`
- explicit boolean-driven floating panels in `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs`

The existing public action identifiers and reducer/state keys must remain unchanged, including:

- `:actions/toggle-pro-order-type-dropdown`
- `:actions/close-pro-order-type-dropdown`
- `:actions/toggle-size-unit-dropdown`
- `:actions/close-size-unit-dropdown`
- `:actions/toggle-margin-mode-dropdown`
- `:actions/close-margin-mode-dropdown`
- `:actions/toggle-leverage-popover`
- `:actions/close-leverage-popover`
- `:actions/toggle-tpsl-unit-dropdown`
- `:actions/close-tpsl-unit-dropdown`
- `:actions/toggle-tif-dropdown`
- `:actions/close-tif-dropdown`

Revision note: created this ExecPlan on 2026-03-18 to execute user-requested dropdown motion work for the header and trading-form menus while keeping the current state model intact.
Revision note: updated on 2026-03-18 after implementation, automated validation, and browser QA. Recorded the completed dropdown-motion work and linked follow-up `bd` issue `hyperopen-vp2j` for unrelated cross-route browser-QA findings.
