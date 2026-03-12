# Active Asset View DDD Refactor

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and must be maintained in accordance with that file.

## Purpose / Big Picture

After this refactor, the active asset strip should behave the same for users, but the implementation should stop treating `/hyperopen/src/hyperopen/views/active_asset_view.cljs` as a kitchen-sink namespace. The strip will render from a dedicated view-model seam, funding math and tooltip derivation will live in a pure policy module, and icon probe side effects will move into their own infrastructure helper.

Users should still be able to open the trade page, see the active asset summary, open the funding tooltip on desktop and mobile, and switch assets without regressions. A contributor should be able to verify the change by reading the smaller modules, running the active asset view tests, and confirming the required repository gates still pass.

## Progress

- [x] (2026-03-11 23:59Z) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and the required UI guidance documents before changing the active asset strip.
- [x] (2026-03-11 23:59Z) Audited `/hyperopen/src/hyperopen/views/active_asset_view.cljs`, the existing active asset tests, and the asset icon batching runtime seams already present in `/hyperopen/src/hyperopen/asset_selector/icon_status_runtime.cljs` and `/hyperopen/src/hyperopen/runtime/effect_adapters/asset_selector.cljs`.
- [x] (2026-03-11 23:59Z) Created and claimed `bd` issue `hyperopen-o6tw` for this refactor.
- [x] (2026-03-12 00:00Z) Authored this active ExecPlan and locked the initial extraction boundaries.
- [x] (2026-03-12 00:18Z) Extracted pure funding policy into `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs`, funding-specific rendering into `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`, row/state seams into `/hyperopen/src/hyperopen/views/active_asset/row.cljs` and `/hyperopen/src/hyperopen/views/active_asset/vm.cljs`, and icon probe infrastructure into `/hyperopen/src/hyperopen/views/active_asset/icon_button.cljs`.
- [x] (2026-03-12 00:19Z) Reduced `/hyperopen/src/hyperopen/views/active_asset_view.cljs` to a 23-line orchestration entrypoint that delegates to the extracted VM and row render seams.
- [x] (2026-03-12 00:24Z) Updated `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs` to target the extracted seams, added regressions for semantic asset buttons, mobile funding tooltip opening, and `24h Change` rendering without funding-rate data, and preserved the memoized funding tooltip behavior checks.
- [x] (2026-03-12 00:27Z) Installed local npm dependencies in this worktree so the repository scripts could resolve `shadow-cljs`.
- [x] (2026-03-12 00:28Z) Ran `npm test` successfully (`2293` tests, `11965` assertions).
- [x] (2026-03-12 00:29Z) Ran `npm run check` successfully.
- [x] (2026-03-12 00:29Z) Ran `npm run test:websocket` successfully (`376` tests, `2145` assertions).
- [x] (2026-03-12 00:29Z) Updated this plan with final outcomes and prepared it for move from `active/` to `completed/`.

## Surprises & Discoveries

- Observation: The active asset view already has a repo-approved batching path for icon status updates, but the current file bypasses it during `:replicant/on-render` by mutating `app-system/store` directly.
  Evidence: `/hyperopen/src/hyperopen/runtime/effect_adapters/asset_selector.cljs` exposes `queue-asset-icon-status!` and `flush-queued-asset-icon-statuses!`, while `/hyperopen/src/hyperopen/views/active_asset_view.cljs` currently defines `apply-asset-icon-status!` and swaps `app-system/store` itself.

- Observation: The mobile funding tooltip branch really is inconsistent with desktop because it omits the explicit `:open?` value in the click-pinnable tooltip call.
  Evidence: In `/hyperopen/src/hyperopen/views/active_asset_view.cljs`, the desktop row passes `:open? funding-tooltip-open?`, while `mobile-active-asset-row` passes `:click-pinnable?`, `:pin-id`, and `:pinned?` but not `:open?`.

- Observation: Running the required repository scripts in this worktree initially failed because `npm test` expected a locally installed `shadow-cljs` binary, while the worktree only had the `npx` path available.
  Evidence: The first `npm test` attempt ended with `sh: shadow-cljs: command not found`; after `npm install`, `npm test` completed successfully.

- Observation: Dispatching icon probe results through `nexus.registry/dispatch` was a better fit than calling the asset-selector effect adapter directly from the on-render hook.
  Evidence: The extracted helper in `/hyperopen/src/hyperopen/views/active_asset/icon_button.cljs` now dispatches `:actions/mark-loaded-asset-icon` and `:actions/mark-missing-asset-icon`, which reuse the existing action-to-queued-effect path already covered by asset-selector tests.

## Decision Log

- Decision: Keep `hyperopen.views.active-asset-view/active-asset-view` as the stable public entrypoint, but move its internal responsibilities into smaller namespaces under `/hyperopen/src/hyperopen/views/active_asset/` plus one pure policy namespace under `/hyperopen/src/hyperopen/active_asset/`.
  Rationale: This preserves the current production API surface while aligning the implementation with the architecture rule that domain policy should stay pure and deterministic.
  Date/Author: 2026-03-12 / Codex

- Decision: Move icon probe lifecycle work into a dedicated helper that uses the existing queued icon-status runtime path instead of mutating the store directly from the view file.
  Rationale: This resolves the review feedback about boundary collapse without inventing a new asset icon persistence mechanism.
  Date/Author: 2026-03-12 / Codex

- Decision: Fix the mobile funding tooltip open-state bug as part of the refactor instead of carrying it forward unchanged.
  Rationale: The bug is directly caused by the misleading tooltip abstraction and the refactor will touch that seam anyway, so preserving the bug would leave the file cleaner but the behavior still wrong.
  Date/Author: 2026-03-12 / Codex

- Decision: Keep the split entirely behind the existing trade-page entrypoint instead of introducing a new top-level application namespace for this slice.
  Rationale: The repo already uses view-model modules under `/hyperopen/src/hyperopen/views/**` for presentation-facing shaping work. A pure top-level policy namespace plus view-local VM/render namespaces delivered the boundary cleanup without forcing unrelated call-site churn.
  Date/Author: 2026-03-12 / Codex

## Outcomes & Retrospective

Implemented result:

- `/hyperopen/src/hyperopen/views/active_asset_view.cljs` now acts only as the trade-page entrypoint and panel orchestration layer.
- Pure funding and position derivation now lives in `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs`.
- Funding-specific popover and panel rendering now lives in `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`.
- Icon rendering and probe lifecycle behavior now lives in `/hyperopen/src/hyperopen/views/active_asset/icon_button.cljs`, and probe results dispatch actions instead of mutating `app-system/store` directly from the main view file.
- State selection and row normalization now lives in `/hyperopen/src/hyperopen/views/active_asset/vm.cljs`.
- Desktop/mobile row rendering now lives in `/hyperopen/src/hyperopen/views/active_asset/row.cljs`.
- The asset trigger is now a semantic `button`.
- The mobile funding tooltip now honors the same `open?` contract as desktop.
- `24h Change` no longer depends on funding-rate presence to render.

Validation outcomes:

- `npm test`: pass (`2293` tests, `11965` assertions)
- `npm run check`: pass
- `npm run test:websocket`: pass (`376` tests, `2145` assertions)

Complexity outcome:

This refactor reduced overall complexity. The original file was about 1400 lines and mixed domain math, state selection, infrastructure behavior, and rendering. The new namespaces are each below the repository’s 500-line ceiling (`23`, `168`, `237`, `327`, `366`, and `411` lines respectively) and each now has a clearer reason to change.

## Context and Orientation

The current active asset strip lives in `/hyperopen/src/hyperopen/views/active_asset_view.cljs`. That file currently owns four different kinds of work:

1. Pure funding and position calculations such as annualization, payment estimates, hypothetical position defaults, and predictability row derivation.
2. State selection and orchestration such as resolving the active market, reading predictability and hypothetical inputs from app state, and building the row data consumed by the render layer.
3. Infrastructure behavior for asset icon probing via `:replicant/on-render`, image event listeners, and direct store writes for loaded or missing icons.
4. Desktop and mobile rendering for the strip, including the funding tooltip surface.

The existing tests in `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs` already cover important behavior: symbol fallbacks, icon probing, tooltip content, mobile layout toggling, and memoized tooltip derivation. That test coverage makes this a good candidate for a behavior-preserving refactor instead of a rewrite.

The repo already has an asset-selector icon batching runtime in `/hyperopen/src/hyperopen/asset_selector/icon_status_runtime.cljs` and `/hyperopen/src/hyperopen/runtime/effect_adapters/asset_selector.cljs`. The refactor should reuse that runtime path so icon updates stay batched and consistent with the rest of the system.

## Plan of Work

First, extract the pure funding and position rules from the view into `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs`. That namespace should own the deterministic parts of the funding tooltip model: position direction, normalized position value, hypothetical position defaults, annualization, payment estimates, predictability rows, lag-note derivation, and the memoized tooltip model cache. It should not read app state or render Hiccup.

Second, create `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs` for the funding-specific popover and panel rendering. The tooltip helper should stop pretending to be generic. It should take explicit funding tooltip state (`open?`, `pinned?`, `pin-id`) and render the funding panel model produced by the policy layer.

Third, create `/hyperopen/src/hyperopen/views/active_asset/icon_button.cljs` to own active asset icon rendering and icon probe lifecycle work. That helper should expose a semantic button instead of a clickable `div`, and the probe callback should send statuses through the existing queued icon-status adapter rather than mutating the store directly in the view file.

Fourth, create `/hyperopen/src/hyperopen/views/active_asset/vm.cljs` to read raw state and build the normalized row view model. That module should resolve the active market, pull funding predictability data, derive funding tooltip state, and expose the row data needed by both mobile and desktop renderers. It should also stop gating unrelated columns together, so `24h Change` can render independently from funding availability.

Fifth, reduce `/hyperopen/src/hyperopen/views/active_asset_view.cljs` to orchestration and public entrypoints. It should compose the extracted render helpers and the view-model seam, remove dead parameters like `loading?`, remove dead local UI state helpers if they are truly unused, and keep only the public vars that the rest of the app should call.

Finally, update `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs` to target the new seams while preserving the behavior assertions. Add explicit coverage for the mobile tooltip opening path and the semantic button trigger.

## Concrete Steps

All commands run from `/hyperopen`.

1. Create and claim the tracking issue, then create this ExecPlan.

       bd create "Refactor active asset strip into VM, policy, and render seams" --description="..." -t task -p 2 --json
       bd update hyperopen-o6tw --claim --json

2. Extract the pure funding policy.

   Files to create or modify:
   - `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs`
   - `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`

3. Extract the icon button and icon probe infrastructure.

   Files to create or modify:
   - `/hyperopen/src/hyperopen/views/active_asset/icon_button.cljs`
   - `/hyperopen/src/hyperopen/views/active_asset_view.cljs`

4. Extract the state-to-view-model seam and shrink the main view file.

   Files to create or modify:
   - `/hyperopen/src/hyperopen/views/active_asset/vm.cljs`
   - `/hyperopen/src/hyperopen/views/active_asset_view.cljs`

5. Update tests and validate.

       npm run check
       npm test
       npm run test:websocket

## Validation and Acceptance

Acceptance is satisfied when all of the following are true:

1. `/hyperopen/src/hyperopen/views/active_asset_view.cljs` is reduced to orchestration and public entrypoints instead of owning domain math, raw state reads, and icon side effects directly.
2. Funding tooltip derivation lives in a pure policy module and still produces the same projection and predictability content for existing scenarios.
3. The funding tooltip popover is explicitly funding-specific and the mobile path passes the same open-state contract as desktop.
4. The active asset icon trigger renders as a semantic button and icon probing no longer mutates `app-system/store` directly from the main view file.
5. `24h Change` can render from change data independently of funding-rate presence, so unrelated missing perp fields do not force a loading placeholder for that column.
6. `npm run check`, `npm test`, and `npm run test:websocket` all pass.

## Idempotence and Recovery

This refactor is file-local to the active asset strip and its tests, so the commands are safe to re-run. If a seam extraction causes an unexpected UI regression, recovery is to keep the new smaller namespace but temporarily re-export the old behavior through the new seam rather than collapsing responsibilities back into `/hyperopen/src/hyperopen/views/active_asset_view.cljs`.

## Artifacts and Notes

Tracked `bd` issue: `hyperopen-o6tw`

Primary files expected to change:

- `/hyperopen/src/hyperopen/views/active_asset_view.cljs`
- `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs`
- `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`
- `/hyperopen/src/hyperopen/views/active_asset/icon_button.cljs`
- `/hyperopen/src/hyperopen/views/active_asset/vm.cljs`
- `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs`

## Interfaces and Dependencies

No new external dependencies are needed.

Expected internal interfaces after implementation:

- `/hyperopen/src/hyperopen/active_asset/funding_policy.cljs` will expose pure helpers for funding tooltip model derivation and its memoized wrapper.
- `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs` will expose funding-specific popover and panel render helpers.
- `/hyperopen/src/hyperopen/views/active_asset/icon_button.cljs` will expose the active asset icon/selector trigger and the icon probe lifecycle helper.
- `/hyperopen/src/hyperopen/views/active_asset/vm.cljs` will expose view-model builders/selectors for the active asset strip.
- `/hyperopen/src/hyperopen/views/active_asset_view.cljs` will remain the stable entrypoint for the trade view.

Plan revision note: 2026-03-12 00:00Z - Created initial ExecPlan for the active asset strip DDD refactor and linked it to `hyperopen-o6tw`.
Plan revision note: 2026-03-12 00:29Z - Marked implementation complete, recorded the `npm install` requirement for local scripts, and added final validation results before moving this plan to `completed/`.
