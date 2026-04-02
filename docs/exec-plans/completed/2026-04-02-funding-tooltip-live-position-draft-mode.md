# Funding Tooltip Live Position And Estimate Mode

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The live `bd` issue for this work is `hyperopen-ocob` ("Fix funding tooltip live-position draft semantics"), and `bd` remains the lifecycle source of truth until this plan is moved out of `active`.

## Purpose / Big Picture

Today the funding tooltip can show `Hypothetical Position` even when the user already has a real position, and the current implementation does not support the desired transition from "show me my live position" to "let me estimate a different one." The top block is either a read-only live summary or a fully hypothetical editor, with no governed way to move between them.

After this work, opening the funding tooltip for an asset with a real position will show `Your Position` with the actual size and value and the current funding projections for that live position. Choosing `Edit estimate` will switch only the top block into `Hypothetical Position`, seed the inputs from the live values, and let the user model a different size or value without losing the rest of the tooltip. Choosing `Use live` or simply closing the tooltip will return the surface to the live-position state on the next open. Users with no real position will continue to open directly into the hypothetical estimator with the current default notional.

## Progress

- [x] (2026-04-02 16:29Z) Created and claimed `bd` issue `hyperopen-ocob` for the funding-tooltip live-position / estimate-mode work.
- [x] (2026-04-02 16:34Z) Audited the current seam and confirmed that `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs`, `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`, `/hyperopen/src/hyperopen/views/active_asset/vm.cljs`, and `/hyperopen/src/hyperopen/asset_selector/actions.cljs` currently support only a binary live-summary-versus-hypothetical-input split.
- [x] (2026-04-02 16:43Z) Captured the initial design and implementation plan in this active ExecPlan after a local code audit and read-only UI / architecture reviews.
- [x] (2026-04-02 18:40Z) Hardened live-position lookup so the tooltip can find the correct position even when the row market is recovered from selector state or when named-dex / namespaced coin forms differ.
- [x] (2026-04-02 18:40Z) Added explicit estimate-mode lifecycle actions so the tooltip can move from `Your Position` to `Hypothetical Position`, seed the inputs from the live values, and clear the draft when the tooltip fully closes.
- [x] (2026-04-02 18:40Z) Updated the funding-tooltip model and render layer so only the top position block changes state while projections and predictability remain structurally stable.
- [x] (2026-04-02 18:40Z) Added deterministic unit coverage, the smallest relevant Playwright regression, the required repo gates except for one unrelated existing `npm test` footer failure, and the governed browser-QA pass.

## Surprises & Discoveries

- Observation: the requested user flow is impossible today even when live-position lookup succeeds, because the live branch renders static spans instead of editable controls.
  Evidence: `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs` `position-section` renders `live-position-summary` whenever `:position-mode` is `:live`, and only `hypothetical-position-inputs` exposes editable fields.

- Observation: the current hypothetical state has no close or reset lifecycle and will persist indefinitely once written.
  Evidence: `/hyperopen/src/hyperopen/asset_selector/actions.cljs` `set-funding-hypothetical-size` and `set-funding-hypothetical-value` save into `[:funding-ui :hypothetical-position-by-coin]`, while `set-funding-tooltip-visible` and `set-funding-tooltip-pinned` never clear that map.

- Observation: the current live-position selector is narrower than the account and portfolio surfaces that already tolerate mixed coin forms and multiple clearinghouse roots.
  Evidence: `/hyperopen/src/hyperopen/domain/trading/market.cljs` `position-for-active-asset` searches only the current clearinghouse `:assetPositions` collection and requires exact coin equality, while the active-asset row can recover market context from selector state in `/hyperopen/src/hyperopen/views/active_asset/vm.cljs` and named-dex account coverage already shows base-symbol rows for namespaced markets in `/hyperopen/test/hyperopen/views/account_info/projections_test.cljs`.

- Observation: fixing only the live-position selector was insufficient for named-dex assets because tooltip-open state, draft retention, and predictability fetches were still keyed by `:active-asset` alone.
  Evidence: the reproduced `BRENTOIL` report still rendered `Hypothetical Position` after the initial selector patch until `/hyperopen/src/hyperopen/views/active_asset/vm.cljs`, `/hyperopen/src/hyperopen/asset_selector/funding_drafts.cljs`, and `/hyperopen/src/hyperopen/runtime/effect_adapters/funding.cljs` were updated to treat `BRENTOIL` and `xyz:BRENTOIL` as aliases for the same active tooltip session.

## Decision Log

- Decision: keep the default live state read-only and require an explicit `Edit estimate` action to enter estimate mode.
  Rationale: this keeps the tooltip truthful by default, avoids accidental edits inside a very small surface, and reuses the existing conceptual split between live summary and hypothetical editor instead of forcing permanent editable inputs into the compact layout.
  Date/Author: 2026-04-02 / Codex

- Decision: use `Your Position` for the live state and `Hypothetical Position` for the estimate state, with a single low-emphasis inline action that toggles between `Edit estimate` and `Use live`.
  Rationale: the user specifically wants the tooltip to stop calling a real position hypothetical, and the compact header-action swap preserves the current restrained trading UI without adding extra rows, badges, or chrome.
  Date/Author: 2026-04-02 / Codex

- Decision: clear the seeded estimate draft when the tooltip fully closes, while also offering `Use live` as an explicit in-tooltip reset.
  Rationale: the tooltip is an estimator, not a long-lived form. Reopening it should show the current live position again, which matches the user’s expectation and avoids stale draft confusion.
  Date/Author: 2026-04-02 / Codex

- Decision: fix the live-position lookup seam before shipping any copy or rendering change.
  Rationale: if named-dex or namespaced-coin positions still fall through to the no-position path, changing the header text alone would not solve the reported bug.
  Date/Author: 2026-04-02 / Codex

## Outcomes & Retrospective

Implemented. The shipped change stayed localized to the existing active-asset funding seam, but the final patch was slightly broader than the original plan because the same identity mismatch affected the pure lookup, the VM dependency slice, the draft-clearing logic, and the async predictability effect.

The new behavior matches the requested product semantics: live positions open as `Your Position`, estimate mode is explicit and seeded from live values, closing clears the draft, and named-dex/base-symbol pairs no longer fall back to the hypothetical default just because the tooltip row and the stored position use different coin strings.

## Context and Orientation

The relevant UI lives in the active-asset strip at the top of the trade route. `/hyperopen/src/hyperopen/views/active_asset/row.cljs` renders the market row and funding trigger. `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs` renders the tooltip body. `/hyperopen/src/hyperopen/views/active_asset/vm.cljs` reads app state and builds the row view-model, including the funding-tooltip model only while the tooltip is open. `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs` owns the pure tooltip model and currently decides between two coarse states: `:live` and `:hypothetical`.

In this plan, "live position" means the real open position for the active market in the correct clearinghouse state. "Estimate mode" means the tooltip has switched from showing the live summary into editable size/value inputs so the user can model a different funding outcome. "Draft" means the transient size/value entry backing estimate mode. The draft must not become a new browser-persisted cache; it remains ordinary in-memory app state under `:funding-ui` and should be safe to clear on close.

The current model has three important limitations. First, the pure policy layer only builds hypothetical input values when there is no live position, so live users cannot enter estimate mode without a structural change. Second, the actions layer only knows how to update hypothetical inputs after they already exist; it has no explicit "enter estimate mode from live position" or "return to live position" action. Third, the live-position selector depends on `:active-market` and exact coin equality, which can miss positions when the row market is recovered from selector state or when the market coin and stored position coin differ by namespace or case.

## Plan of Work

### Milestone 1: Fix the live-position source and add estimate-mode lifecycle

Start with the live-position lookup because the tooltip cannot truthfully say `Your Position` until it can reliably find the real position for the row that is currently being rendered. In `/hyperopen/src/hyperopen/domain/trading/market.cljs`, add a helper that can decide whether a stored position row matches the current market context. The matching rules should be conservative and deterministic: exact coin match first, then uppercase-normalized match, then a base-symbol fallback so a market coin such as `xyz:GOLD` can match a position coin such as `GOLD` when both refer to the same instrument. Keep this logic in the domain layer rather than the view so the selector stays pure and testable.

Then add a market-aware selector in `/hyperopen/src/hyperopen/state/trading.cljs` that accepts the explicit row market and active coin instead of depending only on `:active-market`. The selector should choose the clearinghouse from the explicit market’s `:dex` when present and fall back to the default webdata clearinghouse otherwise. Keep `position-for-active-asset` as a compatibility wrapper, but change the funding-tooltip path in `/hyperopen/src/hyperopen/views/active_asset/vm.cljs` to call the new market-aware selector with the row-resolved market that `active-asset-row-vm` already has in hand.

In `/hyperopen/src/hyperopen/asset_selector/actions.cljs`, add one explicit action to enter estimate mode and one to return to live mode. Entering estimate mode should seed `[:funding-ui :hypothetical-position-by-coin <coin>]` from the live position when one exists, or from the existing default `$1000` notional when it does not. Returning to live mode should clear only the active coin’s draft entry. Keep the current size/value pairing behavior in `set-funding-hypothetical-size` and `set-funding-hypothetical-value`, but make them operate on a draft that was explicitly seeded by the new action instead of assuming the estimator always starts from the default hypothetical notional.

Also update `set-funding-tooltip-visible` and `set-funding-tooltip-pinned` so the draft is cleared only when the tooltip is fully closed. "Fully closed" means the coin is neither visible nor pinned after the action finishes. Batch the tooltip-state and draft-state writes with one `:effects/save-many` projection so the runtime does not expose intermediate visible-but-reset or reset-but-still-open states.

### Milestone 2: Change only the top position block and keep the rest of the tooltip stable

Once the lookup and lifecycle are correct, update `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs` so it no longer uses `:position-mode` to mean both "where the numbers came from" and "which top block to render." Instead, return explicit top-block fields for the renderer: a header label, a header action label, whether the top block is in estimate mode, the read-only live summary values, the seeded estimate input values, and the helper copy. Projections and predictability should not gain new branches. They should continue to use the effective size, direction, and position value for the current mode: live values when no draft exists, seeded / edited draft values when estimate mode is active, and default hypothetical values only when there is no live position and the estimator is open.

In `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`, keep the current quiet layout and make only the top position section dynamic. The live state should read `Your Position` and show the existing `Size` and `Value` rows as read-only summary text plus a low-emphasis `Edit estimate` text action in the header. Estimate mode should read `Hypothetical Position`, swap the top rows to the existing input controls, and show `Use live` in the same header-action slot. The helper text can stay short and operational: for example, the live state can explain that editing switches into an estimate, while the estimate state can remind the user that negative size or value means short.

Do not show the live summary and the estimate inputs at the same time. Do not add chips, badges, or a footer reset row. The only moving pieces should be the header text, the header action, and whether the top rows are summary text or seeded inputs. The projections section and the predictability charts should remain structurally unchanged so the tooltip does not feel like a different component after the top block switches mode.

Add stable `data-role` hooks for the new header action and top-block mode if the existing Playwright anchors are not enough. Keep these hooks limited to the new state transition points so the browser regression can assert the behavior without depending on brittle text-only selectors.

### Milestone 3: Add deterministic coverage and run the governed validation path

Update `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs` to cover the new top-block behavior. Add a case where a live position exists and the tooltip shows `Your Position` with the correct size and value and does not show `Hypothetical Position`. Add a second case where entering estimate mode seeds the inputs from that same live position, shows `Hypothetical Position`, and leaves the projections unchanged until the user edits a field. Keep the existing no-live-position cases so the default hypothetical estimator remains covered.

Update `/hyperopen/test/hyperopen/asset_selector/actions_test.cljs` to cover the new lifecycle. There should be focused tests for entering estimate mode from a live position, returning to live mode, keeping the paired size/value updates intact while in estimate mode, and clearing the draft when the tooltip fully closes. Add a regression that ensures closing a pinned tooltip or hover-only tooltip removes only the active coin’s estimate draft and does not disturb other `:funding-ui` state.

Update `/hyperopen/test/hyperopen/views/active_asset/vm_test.cljs` so the row view-model can still find the live position when `:active-market` is partial or stale but `market-by-key` already resolved the correct row market. Add a new selector-focused suite under `/hyperopen/test/hyperopen/state/trading/` that exercises the market-aware selector and the coin-equivalence rules for exact coin, case-only differences, and namespaced-versus-base matches on named-dex markets.

Finally, add the smallest relevant Playwright regression under `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`. Stub a trade route with one active position, open the funding tooltip, assert `Your Position`, trigger `Edit estimate`, assert `Hypothetical Position` with seeded values, edit one field, and assert the projection output changes. Run that targeted browser test first, then run the full repo gates required by the root contract, and finish with the governed trade-route browser-QA pass because this work changes a user-visible interaction surface under `/hyperopen/src/hyperopen/views/**`.

## Concrete Steps

All commands below run from `/Users/barry/.codex/worktrees/444e/hyperopen`.

1. Implement the lookup, action, policy, and render changes described above in:
   `/hyperopen/src/hyperopen/domain/trading/market.cljs`
   `/hyperopen/src/hyperopen/state/trading.cljs`
   `/hyperopen/src/hyperopen/views/active_asset/vm.cljs`
   `/hyperopen/src/hyperopen/asset_selector/actions.cljs`
   `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs`
   `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`

2. Update or add the deterministic tests in:
   `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`
   `/hyperopen/test/hyperopen/asset_selector/actions_test.cljs`
   `/hyperopen/test/hyperopen/views/active_asset/vm_test.cljs`
   `/hyperopen/test/hyperopen/state/trading/market_position_lookup_test.cljs`

3. Run the smallest relevant browser regression first:

      npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "funding tooltip"

   Expected result: the new funding-tooltip regression passes and shows the live-to-estimate transition without selector flakes.

4. Run the repository validation gates required for code changes:

      npm run check
      npm test
      npm run test:websocket

   Expected result: all commands exit with status `0`.

5. Run the governed browser-QA pass for the trade route and clean up browser sessions:

      npm run qa:design-ui -- --targets trade-route --manage-local-app
      npm run browser:cleanup

   Expected result: the trade-route design review records `PASS` for visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf at widths `375`, `768`, `1280`, and `1440`, and cleanup closes any tool-created browser sessions.

## Validation and Acceptance

Acceptance is behavioral, not structural.

With a real open position on the active market, opening the funding tooltip must show `Your Position`, the actual size, and the actual value. It must not show `Hypothetical Position` in that initial state. The projections and predictability rows should reflect the live position without any extra action from the user.

Choosing `Edit estimate` must switch only the top block into estimate mode. The header must change to `Hypothetical Position`, the size and value inputs must be prefilled from the live values, and the rest of the tooltip must remain in place. Editing either field must update the projection outputs as the user types. Choosing `Use live` must restore the live summary immediately.

Closing the tooltip after entering estimate mode must discard that estimate draft for the active coin. Reopening the tooltip must return to `Your Position` for a live market or to the default hypothetical estimator for a flat market. The change must work for both ordinary perps and named-dex / namespaced instruments where the market coin and the stored position coin are not identical strings.

The implementation is complete only after the focused Playwright regression passes, `npm run check`, `npm test`, and `npm run test:websocket` all pass, and the governed browser-QA run accounts for all required passes and widths.

## Idempotence and Recovery

This change is safe to iterate on because the estimate draft remains transient UI state. If the estimate-mode logic gets stuck during development, clearing the active coin entry from `[:funding-ui :hypothetical-position-by-coin]` and closing the tooltip should restore the surface to the live/default state.

There is no browser-storage migration in this plan. The draft remains ordinary in-memory app state under `:funding-ui`, so no IndexedDB or `localStorage` cleanup path is required. If later work intentionally persists estimate drafts across reloads, that follow-up must re-open the browser-storage decision under `/hyperopen/docs/BROWSER_STORAGE.md`.

If the new market-aware selector causes regressions elsewhere, keep `position-for-active-asset` as a wrapper and revert the funding-tooltip call site to the older selector temporarily while the new selector tests are fixed. Do not remove the compatibility wrapper until all dependent call sites are deliberately migrated.

## Artifacts and Notes

The user report for this plan is concrete: the funding tooltip currently shows `Hypothetical Position` and editable `$1000`-seeded inputs in a case where the user expected their real position. The design choice here is to treat that as two separate failures: an unreliable live-position source and a missing transition into estimate mode.

The UI direction for this plan is deliberately restrained. The tooltip remains a funding inspector, not a mini order ticket. Only the top position block changes state. The projections, predictability copy, and chart sections keep the current layout so the change reads as a semantic correction and not a new component.

## Interfaces and Dependencies

In `/hyperopen/src/hyperopen/domain/trading/market.cljs`, define pure helpers that can answer whether a stored position matches the current market context and that can return the matching position from a provided clearinghouse state.

In `/hyperopen/src/hyperopen/state/trading.cljs`, expose a selector with a shape equivalent to:

    (defn position-for-market [state active-coin market] ...)

This selector must choose the correct clearinghouse from the explicit market and must be safe to call from the active-asset row even when `:active-market` is partial or stale.

In `/hyperopen/src/hyperopen/asset_selector/actions.cljs`, add explicit actions equivalent to:

    :actions/enter-funding-estimate-mode
    :actions/reset-funding-estimate-mode

These actions own seeding and clearing `[:funding-ui :hypothetical-position-by-coin <coin>]`. The existing `:actions/set-funding-hypothetical-size` and `:actions/set-funding-hypothetical-value` remain the only writers for draft field edits after estimate mode is active.

In `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs`, the tooltip model produced for the renderer must expose separate fields for the header label, header action label, estimate-mode boolean, live summary values, and estimate input values. Do not require the renderer to infer those meanings from one overloaded `:position-mode` keyword.

Revision note (2026-04-02 16:43Z, Codex): Created the initial ExecPlan from the user report, repository code audit, and read-only UI / architecture reviews so the implementation can proceed against one explicit design instead of an informal discussion.
