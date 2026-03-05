# Ghost Mode Watchlist Labels and Action Icons

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Tracking issue: `hyperopen-2i4`.

## Purpose / Big Picture

After this change, each Ghost Mode watchlist address can carry a human label and the popover renders rows as `Label` + `Address` with icon-based actions. Users can add/edit labels, copy an address via icon, and see a dedicated link icon placeholder for future ghost-link behavior.

A user can verify this by opening Ghost Mode, entering an address and label, adding it, seeing the label and address rendered in separate row columns, editing via pencil icon, copying via copy icon, and seeing the non-functional link icon present.

## Progress

- [x] (2026-03-05 14:58Z) Reviewed repository guardrails and frontend policy docs (`/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, `/hyperopen/docs/agent-guides/trading-ui-policy.md`) plus planning/tracking docs.
- [x] (2026-03-05 15:00Z) Created and claimed `bd` feature issue `hyperopen-2i4` for this work.
- [x] (2026-03-05 15:00Z) Authored this active ExecPlan in `/hyperopen/docs/exec-plans/active/`.
- [x] (2026-03-05 15:06Z) Implemented labeled watchlist entry normalization and helper APIs in `/hyperopen/src/hyperopen/account/context.cljs`, including legacy string compatibility.
- [x] (2026-03-05 15:06Z) Added Ghost Mode label/edit/copy actions and runtime/catalog/contract wiring updates across action adapters, collaborators, app actions, and schema metadata.
- [x] (2026-03-05 15:07Z) Refactored `/hyperopen/src/hyperopen/views/ghost_mode_modal.cljs` with label input plus icon action rows (copy, edit, link placeholder, spectate, remove).
- [x] (2026-03-05 15:08Z) Updated regression tests for context/actions/restore/contracts/view state and adjusted app defaults assertions for new ghost UI keys.
- [x] (2026-03-05 15:09Z) Ran required validation gates successfully: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-03-05 15:09Z) Closed `hyperopen-2i4` in `bd` with reason `Completed`.

## Surprises & Discoveries

- Observation: Existing Ghost watchlist storage persists as a JSON array of plain address strings, so label support must remain backward-compatible with legacy values.
  Evidence: `/hyperopen/src/hyperopen/startup/restore.cljs` parses and normalizes string arrays into `:account-context :watchlist`.

- Observation: Typography policy tests reject explicit `<16px` utility classes in view code.
  Evidence: `npm test` initially failed in `hyperopen.views.typography-scale-test` for `text-[11px]` added in `ghost_mode_modal.cljs`; switching to `text-xs` resolved the failure.

- Observation: Fresh worktree setup lacked required npm dependencies for Shadow build.
  Evidence: initial `npm run check` failed with missing `@noble/secp256k1`; running `npm install` restored gate execution.

## Decision Log

- Decision: Keep Ghost watchlist persistence under the existing storage key (`ghost-mode-watchlist:v1`) and make the normalizer accept both legacy string items and new map items.
  Rationale: This avoids migration steps and preserves already-saved watchlists in user browsers.
  Date/Author: 2026-03-05 / Codex

- Decision: Use icon controls for row actions but keep keyboard-accessible button semantics with explicit `aria-label` values.
  Rationale: UI request is icon-first while frontend policy requires accessible control semantics.
  Date/Author: 2026-03-05 / Codex

- Decision: Keep label editing in the existing top-of-modal input area (prefilled by pencil icon) instead of inline per-row editors.
  Rationale: This keeps keyboard focus behavior simple, minimizes new transient row state, and still satisfies explicit edit affordance requirements.
  Date/Author: 2026-03-05 / Codex

- Decision: Reuse existing `:effects/copy-wallet-address` for watchlist row copy behavior.
  Rationale: Clipboard behavior and failure handling already exist in runtime; reusing avoids new effect-surface complexity.
  Date/Author: 2026-03-05 / Codex

## Outcomes & Retrospective

Implemented scope for `hyperopen-2i4`:

- watchlist storage now supports labeled entry maps while still restoring legacy string arrays;
- ghost mode actions now support label draft/edit flows and row copy actions;
- modal watchlist now renders explicit label/address/action columns with icon controls and a disabled link placeholder icon;
- regression coverage was expanded for updated domain/action/view contracts and restore behavior.

Validation gates are green (`npm run check`, `npm test`, `npm run test:websocket`).

## Context and Orientation

Current Ghost Mode behavior is implemented across:

- `/hyperopen/src/hyperopen/account/context.cljs` for address normalization and watchlist normalization.
- `/hyperopen/src/hyperopen/account/ghost_mode_actions.cljs` for Ghost modal commands and local-storage persistence.
- `/hyperopen/src/hyperopen/views/ghost_mode_modal.cljs` for popover rendering and watchlist rows.
- `/hyperopen/src/hyperopen/startup/restore.cljs` for restoring watchlist/search state from local storage.
- `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`, `/hyperopen/src/hyperopen/runtime/collaborators.cljs`, `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`, and `/hyperopen/src/hyperopen/schema/contracts.cljs` for runtime action registration and contract validation.

Important term definitions for this task:

- Watchlist entry: one saved Ghost Mode address plus optional human label.
- Link placeholder icon: a visible, non-functional button that reserves UI space for a future address-link action.

## Plan of Work

Update the watchlist model first so every layer can consume a stable entry shape (`{:address <normalized-address> :label <optional-label>}`). Extend normalizers to accept both prior string storage and new map storage, with deterministic dedupe by address.

Then extend Ghost actions so users can manage label input state and trigger row-level copy/edit behaviors without introducing side effects in views. Reuse existing clipboard effect (`:effects/copy-wallet-address`) for row copy.

After action support is in place, refactor the modal watchlist section into a table-like row layout with explicit label/address columns and icon action buttons. The link icon remains disabled/placeholder-only for this slice.

Finally, update tests across domain/actions/view/contracts and run all required validation gates.

## Concrete Steps

Run from `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/account/context.cljs` to support label-aware watchlist normalization and helper accessors/upsert/remove behavior.
2. Edit `/hyperopen/src/hyperopen/account/ghost_mode_actions.cljs` to support label draft state, edit-prefill flow, and row copy effect emission.
3. Wire new actions in runtime and contract files:
   - `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`
   - `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
   - `/hyperopen/src/hyperopen/app/actions.cljs`
   - `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
   - `/hyperopen/src/hyperopen/schema/contracts.cljs`
4. Edit `/hyperopen/src/hyperopen/views/ghost_mode_modal.cljs` for label input and icon action rows with link placeholder button.
5. Update tests in:
   - `/hyperopen/test/hyperopen/account/context_test.cljs`
   - `/hyperopen/test/hyperopen/account/ghost_mode_actions_test.cljs`
   - `/hyperopen/test/hyperopen/startup/restore_test.cljs`
   - `/hyperopen/test/hyperopen/schema/contracts_test.cljs`
   - `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs`
6. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
7. Close `hyperopen-2i4` with `bd close hyperopen-2i4 --reason "Completed" --json` if all validations pass.

## Validation and Acceptance

Acceptance criteria:

- Adding a Ghost watchlist address can include a label and persists across reloads.
- Watchlist rows render label and full address separately.
- Copy icon dispatches a clipboard effect for that row address.
- Pencil icon enables row label editing flow and saved value updates the row.
- Link icon is visible and intentionally non-functional placeholder.
- Existing legacy watchlist storage (array of address strings) still restores correctly.

Validation commands must all succeed:

- `npm run check`
- `npm test`
- `npm run test:websocket`

## Idempotence and Recovery

These edits are additive and retry-safe. Re-running normalization against legacy or new storage formats should always return deterministic watchlist output. If UI behavior regresses, revert only touched files for this plan and rerun tests before reapplying incremental changes.

## Artifacts and Notes

- `bd ready --json` returned no ready issues at session start.
- Created issue:

    hyperopen-2i4 (feature, priority 1): Ghost Mode watchlist labels and row action icons.

## Interfaces and Dependencies

Required action IDs to exist by completion:

- Existing: `:actions/add-ghost-mode-watchlist-address`, `:actions/remove-ghost-mode-watchlist-address`, `:actions/spectate-ghost-mode-watchlist-address`
- New for this task: label/edit/copy action IDs in the `:actions/ghost-mode` group, each with explicit contracts in `/hyperopen/src/hyperopen/schema/contracts.cljs`.

Watchlist persistence remains local via:

- `:effects/local-storage-set-json` with key `ghost-mode-watchlist:v1`.

## Revision Notes

- 2026-03-05 / Codex: Initial plan created for `hyperopen-2i4` to add watchlist labels and icon actions in Ghost Mode popover.
- 2026-03-05 / Codex: Updated plan with implementation progress, test-discovered constraints, final decisions, and validation outcomes.
