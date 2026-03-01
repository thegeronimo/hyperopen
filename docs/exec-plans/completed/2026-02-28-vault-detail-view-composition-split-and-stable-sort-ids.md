# Vault Detail View Composition Split and Stable Sort IDs

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained under `/hyperopen/.agents/PLANS.md` and follows that contract.

## Purpose / Big Picture

The vault detail surface currently renders correctly but still concentrates too many responsibilities in one view namespace and still binds activity sorting to display labels. After this change, `/vaults/<address>` will keep the same user-visible behavior while the page file becomes a thin composition root backed by concern-focused view namespaces (`format`, `chart`, `activity`, `panels`, `transfer`, `hero`). In the same pass, activity sorting will use stable column identifiers instead of label strings, reducing drift risk between VM/view/actions.

A user can validate this by opening vault detail and confirming: chart controls, activity tabs/tables, benchmark controls, and transfer modal still work; sorting headers still toggle direction; and the required validation gates pass.

## Progress

- [x] (2026-02-28 22:33Z) Reviewed repository guardrails (`/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, `/hyperopen/docs/agent-guides/trading-ui-policy.md`) and mapped current vault detail view/VM/action/test architecture.
- [x] (2026-02-28 22:33Z) Authored this ExecPlan in `/hyperopen/docs/exec-plans/active/` before implementation.
- [x] (2026-02-28 22:48Z) Refactored activity sort contract to stable column IDs across VM/actions/schema and updated tests.
- [x] (2026-02-28 22:48Z) Split `vault_detail_view.cljs` into concern namespaces and reduced the page namespace to composition orchestration.
- [x] (2026-02-28 22:48Z) Applied targeted bug fixes from commentary: timeframe fallback fix, safer currency fallback, signed ledger display consistency, and balance quantity formatting.
- [x] (2026-02-28 22:48Z) Ran `npm run check`, `npm test`, and `npm run test:websocket` successfully.
- [x] (2026-02-28 22:48Z) Updated this ExecPlan with outcomes and prepared move to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: The currently checked-in `detail_vm.cljs` already provides `:deposit-max-display`, `:deposit-max-input`, and `:deposit-lockup-copy` in `:vault-transfer`.
  Evidence: `src/hyperopen/views/vaults/detail_vm.cljs` under `vault-detail-vm` output map includes those keys.

- Observation: Local environment initially lacked required npm modules (`@noble/secp256k1`, `indicatorts`), which blocked early gate runs despite code compiling.
  Evidence: initial `npm run check`/`npm test` failed with module resolution errors before dependency restore.

## Decision Log

- Decision: Implement stable activity sort IDs in this refactor instead of preserving label-based columns.
  Rationale: The user commentary identifies label-coupled sorting as a concrete maintenance risk, and this can be corrected with controlled contract updates in actions/VM/tests.
  Date/Author: 2026-02-28 / Codex

- Decision: Keep UI behavior and visual parity intact while splitting namespaces, avoiding large style changes.
  Rationale: The request is architectural refactoring first, not redesign; preserving behavior minimizes regression risk.
  Date/Author: 2026-02-28 / Codex

- Decision: Restore dependencies with `npm ci` before final validation instead of patching package manifests.
  Rationale: `npm ci` restored the declared lockfile dependency set and avoided ad hoc package updates while unblocking all required gates.
  Date/Author: 2026-02-28 / Codex

## Outcomes & Retrospective

Completed. The vault detail page is now split into concern-focused namespaces:

- `/hyperopen/src/hyperopen/views/vault_detail/format.cljs`
- `/hyperopen/src/hyperopen/views/vault_detail/chart.cljs`
- `/hyperopen/src/hyperopen/views/vault_detail/activity.cljs`
- `/hyperopen/src/hyperopen/views/vault_detail/panels.cljs`
- `/hyperopen/src/hyperopen/views/vault_detail/transfer_modal.cljs`
- `/hyperopen/src/hyperopen/views/vault_detail/hero.cljs`

and `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` is now a composition root.

The activity sort path now uses stable keyword IDs end-to-end (actions, VM state, view header dispatch), backed by explicit activity table column metadata in the VM. The requested bug fixes were also applied during extraction:

- timeframe fallback now resolves from first option `:value`;
- currency fallback no longer silently returns `$0.00` for formatter misses on numeric inputs;
- ledger amount display uses signed values consistent with tone;
- balance totals/available render as quantities while USDC value remains currency.

Validation gates passed:

- `npm run check`
- `npm test` (1555 tests, 8049 assertions, 0 failures)
- `npm run test:websocket` (154 tests, 710 assertions, 0 failures)

## Context and Orientation

The current vault detail page is implemented in `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` and built from VM data in `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`. Interaction state updates are handled in `/hyperopen/src/hyperopen/vaults/actions.cljs`, and action argument contracts are validated by `/hyperopen/src/hyperopen/schema/contracts.cljs`.

The current pain points are:

1. One very large view namespace mixes page composition, chart rendering, activity tables, formatting, modal rendering, and semantic interpretation.
2. Activity sorting uses display labels (for example `"Size"`) as sort keys.
3. A few concrete bugs/risks are present in the view: timeframe fallback uses `ffirst`, currency fallback can mask formatting errors as `$0.00`, ledger withdraw tone/sign can diverge, and balances can be displayed as currency even when the source field is a quantity.

## Plan of Work

First, update the sort contract from string labels to stable column IDs. This requires coordinated edits in `detail_vm.cljs` (per-tab column configs and sort accessors), `vaults/actions.cljs` (normalize/store sort column IDs), `schema/contracts.cljs` (sort action argument spec), and tests covering actions + VM + view dispatch wiring.

Second, split `vault_detail_view.cljs` into focused namespaces under `/hyperopen/src/hyperopen/views/vault_detail/`: `format.cljs`, `chart.cljs`, `activity.cljs`, `panels.cljs`, `transfer_modal.cljs`, and `hero.cljs`. The existing `vault_detail_view.cljs` will become a composition root that calls these modules and consumes `detail-vm/vault-detail-vm`.

Third, apply bug fixes during extraction:

- chart timeframe fallback should use first option `:value`, not `ffirst`;
- currency formatter fallback should never silently claim `$0.00` for non-zero values when formatting fails;
- ledger amount display should use signed amount consistently with tone;
- balance `:total` and `:available` should render as quantities (coin units), while `:usdc-value` remains currency.

Finally, run full validation gates and then update/move this plan to completed.

## Concrete Steps

Run from repository root `/hyperopen`:

1. Edit `src/hyperopen/views/vaults/detail_vm.cljs` to expose stable column IDs and activity table metadata, and update sort defaults/accessors.
2. Edit `src/hyperopen/vaults/actions.cljs` and `src/hyperopen/schema/contracts.cljs` to normalize/accept keyword-or-string sort column IDs.
3. Add new view modules under `src/hyperopen/views/vault_detail/` and reduce `src/hyperopen/views/vault_detail_view.cljs` to composition.
4. Update tests:
   - `test/hyperopen/vaults/actions_test.cljs`
   - `test/hyperopen/views/vaults/detail_vm_test.cljs`
   - `test/hyperopen/views/vault_detail_view_test.cljs`
5. Run:

    npm run check
    npm test
    npm run test:websocket

6. Record final findings in this plan and move file to `/hyperopen/docs/exec-plans/completed/`.

## Validation and Acceptance

Acceptance criteria:

- `vault_detail_view.cljs` is a thin composition layer requiring concern-specific modules.
- Activity sorting is keyed by stable IDs (for example `:size`, `:time-ms`) instead of visible labels.
- Chart/activity/modal rendering behavior remains functionally intact in tests.
- Specific bug fixes are covered by tests or assertions in existing tests.
- Required validation gates succeed.

## Idempotence and Recovery

All code edits are source-level and repeatable. If any step fails, rerun tests after each focused file group change. If a bug appears after namespace extraction, temporarily route the composition root back to old module calls one concern at a time to isolate regressions.

## Artifacts and Notes

Primary files to edit in this plan:

- `/hyperopen/src/hyperopen/views/vault_detail_view.cljs`
- `/hyperopen/src/hyperopen/views/vault_detail/*.cljs` (new)
- `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`
- `/hyperopen/src/hyperopen/vaults/actions.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `/hyperopen/test/hyperopen/views/vault_detail_view_test.cljs`
- `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs`
- `/hyperopen/test/hyperopen/vaults/actions_test.cljs`

## Interfaces and Dependencies

No new external dependencies are expected. Existing public entry points remain:

- `hyperopen.views.vault-detail-view/vault-detail-view`
- `hyperopen.views.vaults.detail-vm/vault-detail-vm`
- `hyperopen.vaults.actions/sort-vault-detail-activity`

Internal contracts updated in this plan:

- `:activity-sort-state-by-tab` column token type: from display label strings to stable keyword IDs.
- Activity table header render inputs: from vector-of-labels to vector-of-column-maps (`{:id ... :label ...}`).

Revision note (2026-02-28): Initial plan authored from the supplied commentary and current repository state before implementation.
Revision note (2026-02-28): Updated progress/decisions/outcomes after full implementation and successful validation gates.
