# Vault Detail Activity Sort/Filter and Typography Parity (Pass 4)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, the individual vault page should stop visually breaking in the hero area (title and metric values should stay within their containers), and the activity panel should regain parity with the rest of Hyperopen and Hyperliquid by supporting sortable columns and a working filter dropdown in the right header.

A user can verify this by opening `/vaults/0xdfc24b077bc1425ad1dea75bcb6f8158e10df303`, confirming large numeric values no longer overflow cards, clicking activity table headers to toggle sort direction, and using the filter dropdown to switch between `All`, `Long`, and `Short` rows on direction-aware tabs.

## Progress

- [x] (2026-02-27 12:58Z) Audited current vault detail view, VM, actions, defaults, and runtime contracts to isolate causes: oversized typography classes, static non-interactive activity headers, and disabled filter control.
- [x] (2026-02-27 12:58Z) Created this ExecPlan and discrepancy-driven implementation path.
- [x] (2026-02-27 13:03Z) Implemented vault-detail activity sort/filter state and action handlers with runtime/contract registration.
- [x] (2026-02-27 13:04Z) Implemented VM-level tab-specific sorting and direction filtering for activity rows and exposed sort/filter view-model state.
- [x] (2026-02-27 13:06Z) Replaced static activity table headers with sortable controls and wired a functional right-header filter dropdown.
- [x] (2026-02-27 13:06Z) Reduced hero and metric typography scale and added adaptive metric value sizing to prevent overflow.
- [x] (2026-02-27 13:10Z) Updated tests and passed required validation gates (`npm run check`, `npm test`, `npm run test:websocket`).
- [x] (2026-02-27 13:10Z) Finalized plan and prepared move to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: The right-side `Filter` control in vault activity is currently a disabled placeholder button with no state or action wiring.
  Evidence: `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` renders `:disabled true` and `cursor-not-allowed` for the filter button in `activity-panel`.

- Observation: Vault activity tables do not currently consume account-info reusable sort header helpers and have no view-model sort state.
  Evidence: `table-header` in `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` emits static `<th>` labels only, and `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` returns pre-sorted rows without UI sort state.

- Observation: Threading rows through `filter-activity-rows-by-direction` with `->` initially reversed the function argument order and attempted to treat a keyword tab as a sequence.
  Evidence: runtime test failures (`:balances is not ISeqable`) were resolved by changing the function signature to `[rows tab direction-filter]`.

- Observation: `into` with three arguments in `metric-card` caused ClojureScript to treat the third argument as a transducer path and throw `Key must be integer`.
  Evidence: stack traces pointed to `metric_card` in `/hyperopen/src/hyperopen/views/vault_detail_view.cljs`; replacing it with two-argument `into` + `concat` resolved the issue.

## Decision Log

- Decision: Implement sort/filter at the vault detail domain boundary (`vaults-ui` + `vaults/actions` + `views/vaults/detail_vm`) rather than adding ad-hoc local component state in the view.
  Rationale: This matches existing Hyperopen architecture where UI interaction state is normalized and action-driven, keeps behavior deterministic, and enables direct test coverage in actions/VM.
  Date/Author: 2026-02-27 / Codex

- Decision: Keep filter scope focused on direction (`All`, `Long`, `Short`) for direction-aware tabs in this pass.
  Rationale: The reported broken control is the right-header filter on activity tables; direction filtering is the minimal parity behavior used broadly in Hyperopen and aligns with available row semantics.
  Date/Author: 2026-02-27 / Codex

- Decision: Apply direction filtering only to direction-aware tabs (`positions`, `open-orders`, `twap`, `trade-history`, `funding-history`, `order-history`) and leave other tabs unaffected.
  Rationale: A global direction filter that blanks non-directional tabs is confusing; this preserves expected behavior while keeping a single, consistent filter control.
  Date/Author: 2026-02-27 / Codex

## Outcomes & Retrospective

Implemented and validated. The vault detail page now has the parity fixes requested in this pass:

1. Hero heading and metric cards no longer use oversized text that overflows their containers; metric value sizing now adapts to long values.
2. Activity table columns are sortable across all activity tabs via interactive headers and sort direction indicators.
3. The right-header filter dropdown now works, supports `All` / `Long` / `Short`, and closes on selection.
4. Vault detail interaction state for activity sorting and filtering is now action-driven and contract-registered, matching repository runtime architecture.

All required validation gates passed: `npm run check`, `npm test`, and `npm run test:websocket`.

## Context and Orientation

The vault detail page is rendered by `/hyperopen/src/hyperopen/views/vault_detail_view.cljs`, and its data model is assembled in `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`. Interaction events route through `/hyperopen/src/hyperopen/vaults/actions.cljs`, with action registration in `/hyperopen/src/hyperopen/runtime/collaborators.cljs`, `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`, and `/hyperopen/src/hyperopen/registry/runtime.cljs`. Action argument contracts are enforced in `/hyperopen/src/hyperopen/schema/contracts.cljs`.

Current discrepancy inventory for this pass:

1. Hero title and metric value font sizes are too large on this layout and can visually break container bounds.
2. Activity table columns are static labels and cannot be sorted.
3. Right-header filter control is non-functional and cannot filter rows.

## Plan of Work

Milestone 1 adds vault-detail activity UI state defaults and action handlers for sorting and filter menu behavior. This includes normalized direction filter handling and per-tab sort state writes, plus runtime registry and schema contract wiring for new action IDs.

Milestone 2 extends `views/vaults/detail_vm.cljs` with deterministic row filtering and sorting by selected activity tab. The VM will output filter menu state/options and tab sort state so the view can remain declarative.

Milestone 3 updates `views/vault_detail_view.cljs` to render interactive sortable headers, a working filter dropdown, and reduced hero typography scales. This milestone also ensures interactions close dropdowns predictably and preserve existing tab/table semantics.

Milestone 4 updates tests in `test/hyperopen/vaults/actions_test.cljs`, `test/hyperopen/views/vaults/detail_vm_test.cljs`, `test/hyperopen/views/vault_detail_view_test.cljs`, and any runtime/contract coverage impacted by new action IDs, then runs required validation gates.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/state/app_defaults.cljs` with vault detail activity sort/filter defaults.
2. Edit `/hyperopen/src/hyperopen/vaults/actions.cljs` with normalize helpers + new action handlers.
3. Register new actions in `/hyperopen/src/hyperopen/runtime/collaborators.cljs`, `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`, `/hyperopen/src/hyperopen/registry/runtime.cljs`, and `/hyperopen/src/hyperopen/schema/contracts.cljs`.
4. Edit `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` to apply direction filter + per-tab sort and expose UI state/options.
5. Edit `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` for sortable headers, filter dropdown behavior, and typography fixes.
6. Update tests, then run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance criteria:

1. Hero title and metric values stay within their visual containers across desktop and medium view widths.
2. Clicking an activity table header toggles sorting for that column and shows direction affordance.
3. The right-header filter dropdown opens, selection applies row filtering, and the dropdown closes on selection.
4. Required validation gates pass with no new contract drift.

## Idempotence and Recovery

All changes are additive to view-model/action state wiring and view rendering. If a regression appears, the changes can be reverted by file-scoped hunks without data migration. No persistent or destructive operations are introduced.

## Artifacts and Notes

Primary implementation targets:

- `/hyperopen/src/hyperopen/state/app_defaults.cljs`
- `/hyperopen/src/hyperopen/vaults/actions.cljs`
- `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`
- `/hyperopen/src/hyperopen/views/vault_detail_view.cljs`
- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
- `/hyperopen/src/hyperopen/registry/runtime.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`

## Interfaces and Dependencies

No new external dependencies are needed. This pass extends existing action IDs and VM shape for vault detail activity interactions and preserves existing API/effect boundaries.

Revision note (2026-02-27 / Codex): Initial plan created from direct code audit and user-reported discrepancies (oversized typography, unsortable columns, broken filter control).
Revision note (2026-02-27 / Codex): Updated with implementation progress, debugging discoveries, decisions, and final validated outcomes after completing sort/filter/typography parity work.
