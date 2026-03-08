# Mobile Account Tab Expandable Cards

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

After this change, the phone-sized account surfaces inside Hyperopen will stop rendering the Balances and Trade History tabs as compressed desktop tables. Instead, each mobile row will become a tappable summary card that shows three key fields at a glance and expands inline to reveal the remaining fields. A user on the trade page will be able to read balances and fills without overlapping columns, and they will be able to tap a full-width card to reveal details instead of trying to hit narrow table cells.

The behavior is observable by running the app, opening the trade page in an iPhone-sized viewport, scrolling to the account panel, and confirming that the Balances and Trade History tabs render cards rather than dense tables. Each card must show a three-field summary, must expand inline with a chevron, and must keep desktop tables unchanged on larger viewports.

## Progress

- [x] (2026-03-08 16:14Z) Audited current account-info implementation in `/hyperopen/src/hyperopen/views/account_info_view.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`, and state/action seams under `/hyperopen/src/hyperopen/account/history/**`.
- [x] (2026-03-08 16:14Z) Filed tracking issues: epic `hyperopen-w5b`, shared task `hyperopen-3fl`, balances conversion `hyperopen-wri`, and trade-history conversion `hyperopen-3gq`.
- [x] (2026-03-08 16:16Z) Authored this ExecPlan with concrete implementation, validation, and acceptance scope.
- [x] (2026-03-08 16:22Z) Added shared mobile card expansion state under `:account-info`, registered `:actions/toggle-account-info-mobile-card`, and threaded the new state through the account-info view model.
- [x] (2026-03-08 16:28Z) Implemented shared mobile card primitives plus the Balances mobile card renderer with three-field summary and inline expandable detail content while leaving the desktop table path intact.
- [x] (2026-03-08 16:31Z) Implemented the Trade History mobile card renderer with summary/detail treatment, preserved desktop table and pagination behavior, and normalized mobile row identity to avoid live-state expansion mismatches.
- [x] (2026-03-08 16:34Z) Added regression coverage for mobile card action toggling plus Balances and Trade History mobile-card rendering/expansion.
- [x] (2026-03-08 16:40Z) Ran required validation gates and completed manual browser QA in the iPhone 14 Pro Max viewport with populated spectate-mode data.

## Surprises & Discoveries

- Observation: The current Balances and Trade History tabs do not have any mobile-specific row rendering path; they only render dense grid tables regardless of viewport.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` currently returns `balance-table-header` plus a scrollable list of `balance-row`, and `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs` currently returns a single `table/tab-table-content` path with grid headers and rows.

- Observation: The account-info state already has a clean local UI home under `:account-info`, so mobile card expansion can be tracked there without adding new global state.
  Evidence: `/hyperopen/src/hyperopen/state/app_defaults.cljs` defines balances, positions, open-orders, trade-history, and order-history UI state under one `:account-info` map.

- Observation: Mobile summary cards cannot safely keep the existing coin-select button inside the summary tap target because nested buttons would produce invalid interactive markup.
  Evidence: Current coin cells are rendered with `shared/coin-select-control`, which is itself a `button` when a coin exists.

- Observation: The first cut of the shared mobile card shell accidentally nested the summary-items vector instead of splicing it into the header grid, which caused the first summary column to disappear from the Hiccup tree.
  Evidence: The initial test pass showed missing `Coin` labels in `/hyperopen/test/hyperopen/views/account_info/tabs/balances_test.cljs`, and the fix was to change `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs` to `into` the parent grid vector directly.

- Observation: Trade History manual QA is subject to live fill churn in spectate mode, so the newest fill can reorder between a click and a screenshot.
  Evidence: Browser QA on `http://localhost:8080/trade?spectate=0x162cc7c861ebd0c06b3d72319201150482518185` showed that expanded newest-fill cards could collapse out of the captured frame as fresh fills arrived; using an older visible row produced stable expansion evidence.

## Decision Log

- Decision: Keep desktop table renderers intact and add a separate `lg:hidden` mobile card path for Balances and Trade History.
  Rationale: The user’s complaint is specific to phone-sized layouts. Preserving the existing `hidden lg:block` desktop tables minimizes regression risk for sorting, column order, and non-mobile parity work.
  Date/Author: 2026-03-08 / Codex

- Decision: Track exactly one expanded card per tab (`:balances` and `:trade-history`) in app state instead of using uncontrolled DOM-only expansion.
  Rationale: One-open-at-a-time behavior matches the compact mobile reference, keeps the surface tidy, and avoids expansion state resetting unpredictably during rerenders or live data refreshes.
  Date/Author: 2026-03-08 / Codex

- Decision: Use non-interactive coin labels inside mobile summary buttons and keep interactive links/actions inside the expanded detail panel.
  Rationale: The summary row itself must be a large tap target. Nested coin-select buttons inside that tap target would be invalid and brittle; moving interactive affordances to expanded content preserves accessibility and finger-sized controls.
  Date/Author: 2026-03-08 / Codex

## Outcomes & Retrospective

This wave shipped the requested mobile account-tab treatment. Balances and Trade History now render as tappable summary cards on small viewports, each with a three-field summary and inline detail expansion, while desktop continues to use the existing grid tables.

The implementation stayed localized to account-info seams: one new mobile-card helper module, one new tab-scoped expansion action/state path, and mobile-only render branches inside the Balances and Trade History tab modules. The main regression risk was Hiccup shape drift between mobile and desktop paths; that was mitigated by keeping the desktop header/rows subtree ordering intact and adding explicit tests for the mobile card tree.

Manual browser QA succeeded on the iPhone 14 Pro Max viewport using spectate address `0x162cc7c861ebd0c06b3d72319201150482518185`. The Balances cards showed `Coin`, `USDC Value`, and `Total Balance` without overlap and expanded cleanly. Trade History showed `Coin`, `Time`, and `Size` summary cards with inline detail expansion; timestamps still wrap tightly, but the original overlapping-table failure is gone. Evidence is recorded in `/hyperopen/docs/qa/mobile-account-tab-expandable-cards-qa-2026-03-08.md` and `/hyperopen/tmp/browser-inspection/manual-mobile-account-tabs-2026-03-08T16-38-40Z/summary.json`.

## Context and Orientation

The trade page account panel comes from `/hyperopen/src/hyperopen/views/account_info_view.cljs`. That file selects which tab content renderer to use, but the Balances and Trade History row layouts live in their own files.

Balances are rendered in `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`. Today that module builds a header with eight columns (`Coin`, `Total Balance`, `Available Balance`, `USDC Value`, `PNL (ROE %)`, `Send`, `Transfer`, `Contract`) and then renders every row as one eight-column grid. This is why the phone viewport overlaps.

Trade history is rendered in `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`. Today that module builds an eight-column table (`Time`, `Coin`, `Direction`, `Price`, `Size`, `Trade Value`, `Fee`, `Closed PNL`) and uses pagination. The data and formatting helpers in that file are already correct; the problem is only the mobile presentation.

Shared account-info formatting helpers live in `/hyperopen/src/hyperopen/views/account_info/shared.cljs` and shared table helpers live in `/hyperopen/src/hyperopen/views/account_info/table.cljs`. Account-info action wiring lives in `/hyperopen/src/hyperopen/account/history/actions.cljs`, `/hyperopen/src/hyperopen/account/history/surface_actions.cljs`, and `/hyperopen/src/hyperopen/account/history/order_actions.cljs`. Runtime registration for public actions flows through `/hyperopen/src/hyperopen/core/public_actions.cljs`, `/hyperopen/src/hyperopen/runtime/collaborators.cljs`, `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`, and `/hyperopen/src/hyperopen/schema/contracts.cljs`.

The state default for the account-info panel lives in `/hyperopen/src/hyperopen/state/app_defaults.cljs`. Any new mobile expansion state must be initialized there so the runtime and tests start from a deterministic shape.

Regression coverage already exists for Balances and Trade History in:

- `/hyperopen/test/hyperopen/views/account_info/tabs/balances_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs`
- `/hyperopen/test/hyperopen/account/history/actions_test.cljs`

The user-provided visual target is a Hyperliquid-style chip/card row: a compact card on mobile that shows three key values in summary form and expands inline when tapped. For Balances, the summary fields are `Coin`, `USDC Value`, and `Total Balance`. For Trade History, the summary fields are `Coin`, `Time`, and `Size`. The expanded panel should reveal the remaining fields in a small grid beneath the summary, with a chevron indicating open or closed state.

## Plan of Work

The first step is to add explicit mobile expansion state under `:account-info`. Use a new map such as `:mobile-expanded-card {:balances nil :trade-history nil}` in `/hyperopen/src/hyperopen/state/app_defaults.cljs`. Add one public action, `:actions/toggle-account-info-mobile-card`, that accepts a tab id and a row id. It should save the clicked row id for that tab when the row is currently collapsed, and save `nil` when the user taps the already-expanded row. Register that action through the normal action contract path so it is validated like the rest of the UI actions.

The second step is to add a shared mobile card shell. Put this in a new helper module under `/hyperopen/src/hyperopen/views/account_info/` so both Balances and Trade History can use it. The shell must provide:

- a full-width summary button with `aria-expanded`
- a compact chevron icon
- a summary layout that supports three content cells plus the chevron
- an expanded detail container with a thin divider and a small two-column grid of label/value fields

This shell must be visual-only and reusable. It should not know anything about balances or trade history beyond the supplied summary and detail nodes.

The third step is the Balances tab conversion. Keep all existing sorting, search, hide-small filtering, formatting, tooltip, and contract-link logic. Add a mobile renderer that:

- uses the same filtered and sorted rows as desktop
- shows a summary card per row on small viewports
- uses `Coin`, `USDC Value`, and `Total Balance` in the summary
- moves `Available Balance`, `PNL (ROE %)`, `Send`, `Transfer`, and `Contract` into the expanded detail grid
- leaves the existing table header and row rendering under `hidden lg:block`

The mobile summary card should use a plain coin label, plus the existing namespace chip when present. The expanded content can still use the current tooltip-aware available-balance node and contract explorer link because those elements will no longer live inside the summary button.

The fourth step is the Trade History conversion. Keep all existing sorting, filtering, coin search, pagination, direction formatting, and explorer-link logic. Add a mobile renderer that:

- uses the same sorted and paginated rows as desktop
- shows `Coin`, `Time`, and `Size` in the summary
- reveals `Direction`, `Price`, `Trade Value`, `Fee`, and `Closed PNL` when expanded
- keeps the existing table header and row rendering under `hidden lg:block`

The expanded details should reuse the existing formatted nodes, especially the direction node and the time/explorer link node, so that price-improved tooltip behavior and explorer links are preserved.

The fifth step is regression coverage. Add state-action tests proving only one row per tab can be expanded at a time and that tapping the same row twice collapses it. Add view tests proving Balances and Trade History render mobile cards with summary labels and expanded detail sections while leaving desktop table classes present. Update any table-contract tests that assume every tab has only one row renderer, but keep the desktop table assertions intact.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/6717/hyperopen`.

1. Claim the child tasks before editing:

   `bd update hyperopen-3fl --claim --json`
   `bd update hyperopen-wri --claim --json`
   `bd update hyperopen-3gq --claim --json`

2. Add the default mobile expansion state in `/hyperopen/src/hyperopen/state/app_defaults.cljs`.

3. Add `toggle-account-info-mobile-card` in `/hyperopen/src/hyperopen/account/history/surface_actions.cljs` and re-export/register it through:

   - `/hyperopen/src/hyperopen/account/history/actions.cljs`
   - `/hyperopen/src/hyperopen/core/public_actions.cljs`
   - `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
   - `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
   - `/hyperopen/src/hyperopen/schema/contracts.cljs`

4. Create the shared mobile card helper module under `/hyperopen/src/hyperopen/views/account_info/`.

5. Update `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` to render:

   - `lg:hidden` mobile summary cards
   - `hidden lg:block` desktop table

6. Update `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs` to render:

   - `lg:hidden` mobile summary cards
   - `hidden lg:block` desktop table

7. Add or update regression coverage in:

   - `/hyperopen/test/hyperopen/account/history/actions_test.cljs`
   - `/hyperopen/test/hyperopen/views/account_info/tabs/balances_test.cljs`
   - `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`
   - `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs`

8. Run validation:

   `npm test`
   `npm run check`
   `npm run test:websocket`

9. Run iPhone manual QA / browser capture and confirm:

   - Balances summary cards no longer overlap columns.
   - Trade History summary cards no longer overlap columns.
   - A card expands when tapped and collapses when tapped again.
   - Desktop table layout still exists for large viewports.

## Validation and Acceptance

Acceptance requires all of the following:

- `npm test`, `npm run check`, and `npm run test:websocket` pass.
- In a phone-sized viewport on `/trade`, the Balances tab shows one card per balance row rather than a dense eight-column table.
- Each balance card summary shows `Coin`, `USDC Value`, and `Total Balance`, with a chevron on the right.
- Expanding a balance card reveals `Available Balance`, `PNL (ROE %)`, `Send`, `Transfer`, and `Contract` in a readable detail layout.
- In the Trade History tab, the mobile viewport shows one card per fill with `Coin`, `Time`, and `Size` in the summary.
- Expanding a trade-history card reveals `Direction`, `Price`, `Trade Value`, `Fee`, and `Closed PNL`.
- On desktop-sized viewports, the legacy grid tables still render.

## Idempotence and Recovery

These changes are additive and safe to rerun. The new mobile card state is local UI state only and is not persisted to storage. If a mobile renderer regresses, the safest recovery is to keep the shared action/state wiring and temporarily route the tab back to the existing desktop table path for all viewports, because that preserves the original data formatting and sorting logic.

If any test fails because it assumes a single renderer per tab, update the test to target the desktop table subtree explicitly rather than deleting the mobile renderer. Do not remove the desktop table path; it remains the fallback and large-viewport implementation.

## Artifacts and Notes

Issue tracking for this wave:

- Epic: `hyperopen-w5b`
- Shared mobile state/layout: `hyperopen-3fl`
- Balances mobile cards: `hyperopen-wri`
- Trade-history mobile cards: `hyperopen-3gq`

Artifacts produced by this plan:

- QA report: `/hyperopen/docs/qa/mobile-account-tab-expandable-cards-qa-2026-03-08.md`
- Browser QA summary JSON: `/hyperopen/tmp/browser-inspection/manual-mobile-account-tabs-2026-03-08T16-38-40Z/summary.json`
- Browser screenshots:
  - `/hyperopen/tmp/browser-inspection/manual-mobile-account-tabs-2026-03-08T16-38-40Z/balances-expanded.png`
  - `/hyperopen/tmp/browser-inspection/manual-mobile-account-tabs-2026-03-08T16-38-40Z/trade-history-expanded-stable.png`

Plan revision note: 2026-03-08 16:16Z - Initial ExecPlan created after auditing the existing account-info table implementation and filing the tracking epic plus child tasks for the mobile expandable-card wave.
Plan completion note: 2026-03-08 16:40Z - Completed implementation, regression coverage, and iPhone manual QA for the mobile account-tab expandable-card wave.
