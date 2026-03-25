# Add Order Confirmation Toggles To Trading Settings

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-v894`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

After this change, Trading Settings should expose two real safety toggles:

- `Confirm open orders`
- `Confirm close position`

Both toggles must be backed by actual behavior, not placeholder UI. Open-order submission from the trade form and close-position submission from the position reduce popover should prompt for confirmation by default, and users should be able to disable those confirmations in Trading Settings.

## Progress

- [x] (2026-03-19 19:26Z) Created and claimed `hyperopen-v894` for the order-confirmation toggle follow-up.
- [x] (2026-03-19 19:31Z) Re-read the existing Trading Settings persistence model, header settings UI, order submit path, position reduce submit path, and the platform-backed confirmation effect pattern already used elsewhere in the app.
- [x] (2026-03-19 19:33Z) Implemented persisted `Confirm open orders` and `Confirm close position` flags, added the new `Confirmations` section in Trading Settings, and routed both submit seams through a shared confirm-before-submit order effect.
- [x] (2026-03-19 19:34Z) Updated deterministic coverage for Trading Settings rendering, settings persistence, order-submit behavior, close-position behavior, and the new confirm effect adapter.
- [x] (2026-03-19 19:34Z) Ran `npm test`, `npm run test:websocket`, and `npm run check` successfully.
- [x] (2026-03-19 19:34Z) Ran governed browser QA for the changed header surface. `/trade` remained visually clean for the header slice, while the overall review still failed on standing `/portfolio`, desktop `/trade`, and `/vaults` route debt unrelated to this feature.
- [x] (2026-03-25 13:49 EDT) Verified the persisted confirmation toggles, confirm-gated submit seams, and deterministic coverage are still present on the current branch, then closed out the ticket by moving this ExecPlan to `completed` and closing `hyperopen-v894` in `bd`.

## Surprises & Discoveries

- Observation: both requested toggles map cleanly to existing action seams without introducing new modal state. Open orders already funnel through `/hyperopen/src/hyperopen/order/actions.cljs`, and close-position submit already funnels through `/hyperopen/src/hyperopen/account/history/position_overlay_actions.cljs`.
  Evidence: those files currently emit `:effects/api-submit-order` directly after validation.

- Observation: the app already keeps browser confirmation behind `platform/confirm!` and an effect adapter instead of calling `js/confirm` from pure action code.
  Evidence: `/hyperopen/src/hyperopen/platform.cljs`, `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs`, and `/hyperopen/src/hyperopen/websocket/diagnostics_effects.cljs`.

- Observation: adding these toggles changes the default behavior of bare-state submit tests unless those tests explicitly opt out of confirmations.
  Evidence: `/hyperopen/test/hyperopen/core_bootstrap/order_entry_actions_test.cljs` and `/hyperopen/test/hyperopen/account/history/actions_test.cljs` currently assume direct `:effects/api-submit-order` emission when `:trading-settings` is absent.

## Decision Log

- Decision: use a native confirmation effect backed by `platform/confirm!` instead of building a new in-app confirmation surface.
  Rationale: this is the lowest-effort truthful implementation, keeps submit actions pure, and matches the existing architecture boundary for confirmation side effects.
  Date/Author: 2026-03-19 / Codex

- Decision: store these as positive booleans with defaults of `true`.
  Rationale: the requested feature is order confirmation, not skip-confirmation, and safe defaults should preserve confirmation unless a user explicitly opts out.
  Date/Author: 2026-03-19 / Codex

- Decision: place the new rows in a dedicated `Confirmations` group inside Trading Settings.
  Rationale: these are safety controls, not display or session controls, and grouping them separately keeps the menu scannable.
  Date/Author: 2026-03-19 / Codex

## Outcomes & Retrospective

This slice landed two real Trading Settings safety controls with safe defaults:

- `Confirm open orders`
- `Confirm close position`

Both settings persist through the existing Trading Settings storage record, render in the current grouped-card Trading Settings UI, and gate the existing submit seams with a platform-backed confirmation effect instead of a placeholder toggle.

Repo gates are green:

- `npm test`
- `npm run test:websocket`
- `npm run check`

Governed browser QA still fails overall, but for the same unrelated shared-route debt already present on `/portfolio`, desktop `/trade`, and `/vaults`. The header-facing `/trade` pass stayed green for `visual`, `native-control`, and `styling-consistency`, and mobile/tablet `/trade` layout remained clean.

The 2026-03-25 closeout pass did not require new code changes. The feature implementation and deterministic coverage were already present in the branch; the remaining work was to verify the landed state, archive this ExecPlan, and close `hyperopen-v894`.

## Context and Orientation

Trading Settings persistence lives in `/hyperopen/src/hyperopen/trading_settings.cljs`, the settings actions live in `/hyperopen/src/hyperopen/header/actions.cljs`, and the Trading Settings UI is rendered in `/hyperopen/src/hyperopen/views/header_view.cljs`.

The two behavior seams to gate are:

- `/hyperopen/src/hyperopen/order/actions.cljs` for open-order submission
- `/hyperopen/src/hyperopen/account/history/position_overlay_actions.cljs` for close-position submission

Effect registration and validation live in:

- `/hyperopen/src/hyperopen/runtime/effect_adapters/order.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
- `/hyperopen/src/hyperopen/app/effects.cljs`
- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`

## Proposed Product Scope

This slice adds:

- two persisted Trading Settings toggles for order confirmations
- confirmation-on-submit for trade-form open orders
- confirmation-on-submit for close-position reduce orders

This slice does not add:

- confirmations for TP/SL or margin updates
- browser/system notifications
- audio alerts
- transaction-delay-protection or account-mode controls

## Plan of Work

First, extend the Trading Settings state shape and header actions to persist the two new boolean preferences with safe defaults.

Next, add the new `Confirmations` rows to the Trading Settings UI using the current grouped-card styling.

Then, add a generic confirm-before-submit order effect adapter and route both submit seams through it when the corresponding toggle is enabled.

Finally, update the existing deterministic tests and rerun the required repo gates plus governed browser QA for the changed header surface.

## Concrete Steps

1. Keep this plan current while the order-confirmation toggle slice is in progress.

2. Update Trading Settings state, UI, and actions in:

   `/hyperopen/src/hyperopen/trading_settings.cljs`
   `/hyperopen/src/hyperopen/header/actions.cljs`
   `/hyperopen/src/hyperopen/views/header_view.cljs`

3. Add confirm-gated submit behavior and effect registration in:

   `/hyperopen/src/hyperopen/order/actions.cljs`
   `/hyperopen/src/hyperopen/account/history/position_overlay_actions.cljs`
   `/hyperopen/src/hyperopen/runtime/effect_adapters/order.cljs`
   `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
   `/hyperopen/src/hyperopen/app/effects.cljs`
   `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
   `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
   `/hyperopen/src/hyperopen/schema/contracts.cljs`

4. Update tests in:

   `/hyperopen/test/hyperopen/header/actions_test.cljs`
   `/hyperopen/test/hyperopen/views/header_view_test.cljs`
   `/hyperopen/test/hyperopen/startup/restore_test.cljs`
   `/hyperopen/test/hyperopen/core_bootstrap/order_entry_actions_test.cljs`
   `/hyperopen/test/hyperopen/account/history/actions_test.cljs`
   `/hyperopen/test/hyperopen/runtime/effect_adapters/order_test.cljs`

5. Run required validation:

   `npm test`
   `npm run test:websocket`
   `npm run check`
   `npm run qa:design-ui -- --changed-files src/hyperopen/views/header_view.cljs --manage-local-app`

Plan update note: 2026-03-25 13:49 EDT - Confirmed the current branch still contains the Trading Settings confirmation toggles, the confirm-before-submit effect path, and the associated deterministic coverage in the files named above. No additional implementation changes were needed for closeout, so this plan is ready to move to `/completed/` and `hyperopen-v894` is ready to close in `bd`.
