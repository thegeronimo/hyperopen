# Vault Transfer Deposit MAX and Lockup Copy Parity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Vault transfer support is now implemented, but users still lack two critical parity cues on deposit: they cannot quickly set the maximum available amount, and they cannot see the vault deposit lock-up period in the modal. After this change, a user opening `Deposit` on `/vaults/:vaultAddress` can click a `MAX` control to populate the largest available USDC amount and can read lock-up period copy in the modal subtitle.

A user verifies this by opening a vault detail page, clicking `Deposit`, seeing `MAX: <amount> USDC`, clicking it to populate the input, and seeing lock-up copy such as `The deposit lock-up period is 4 days.` for HLP.

## Progress

- [x] (2026-02-28 20:52Z) Re-read vault transfer implementation touchpoints in view model, view, actions, and tests.
- [x] (2026-02-28 21:03Z) Re-validated external references: official docs, official SDK action shapes, and Hyperliquid frontend modal behavior for MAX and lock-up copy.
- [x] (2026-02-28 21:11Z) Implemented VM derivations for deposit max amount, lock-up days, and modal copy; wired model fields into vault transfer projection.
- [x] (2026-02-28 21:14Z) Implemented deposit modal UI updates: lock-up subtitle and clickable `MAX` control dispatching `:actions/set-vault-transfer-amount`.
- [x] (2026-02-28 21:17Z) Added/updated VM and view tests for new fields and MAX click wiring.
- [x] (2026-02-28 21:21Z) Ran required validation gates successfully (`npm run check`, `npm test`, `npm run test:websocket`).
- [x] (2026-02-28 21:22Z) Moved this plan to `/hyperopen/docs/exec-plans/completed/` after implementation and validation.

## Surprises & Discoveries

- Observation: `:webdata2 :clearinghouseState :withdrawable` can exist without margin summary values, so deriving max only from account-value math yields `0` unexpectedly.
  Evidence: initial VM test/view test failed with rendered `MAX: 0.00 USDC` despite `withdrawable` being set; helper updated to read direct withdrawable fields.

- Observation: Hyperliquid deposit lock-up copy for vault deposit modal is not directly returned as a static `lockupDays` field in `vaultDetails`; frontend uses vault-name rule (`HLP => 4`, else `1`) for subtitle.
  Evidence: `tmp/hyperliquid-frontend/4114.js` deposit modal chunk computes `period` from vault name and renders that in subtitle.

- Observation: `vaultDetails` and `userVaultEquities` still provide lockup timestamps that can be used for user-specific lockup window inference.
  Evidence: official info docs and SDK types include `followerState.lockupUntil` and `lockedUntilTimestamp`.

## Decision Log

- Decision: Derive deposit max from wallet-centric data (`:spot :clearinghouse-state` and `:webdata2`) with vault-webdata fallback, then floor to 2 decimals for safe prefill.
  Rationale: this matches Hyperliquid behavior more closely and avoids overfilling due to precision rounding.
  Date/Author: 2026-02-28 / Codex

- Decision: Show lockup copy in VM as computed text and expose both day count and copy string to the view.
  Rationale: keeps view rendering simple and deterministic while making tests explicit.
  Date/Author: 2026-02-28 / Codex

- Decision: Prefer user-specific lockup window (`follower-state` timestamps) when available, otherwise fallback to Hyperliquid frontend rule (`HLP=4`, others=1).
  Rationale: satisfies user request to query lockup when possible while preserving parity copy for users without follower state.
  Date/Author: 2026-02-28 / Codex

## Outcomes & Retrospective

Vault deposit modal parity is now improved with both missing behaviors implemented.

Delivered behavior:

- Deposit modal shows lock-up copy under the modal title.
- Deposit modal shows a clickable `MAX: <amount> USDC` control that sets the amount input.
- MAX amount is derived from wallet available USDC sources and normalized for safe submission.
- Lock-up period is derived from `follower-state` timestamps when available and otherwise follows Hyperliquid frontend fallback behavior for known vault naming.

Validation outcomes:

- `npm run check` passed.
- `npm test` passed (1553 tests, 8043 assertions).
- `npm run test:websocket` passed (154 tests, 710 assertions).

## Context and Orientation

Vault transfer modal rendering lives in `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` (`vault-transfer-modal-view`). Vault detail transfer model derivation lives in `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` (`vault-detail-vm`). Existing transfer actions and submit semantics are in `/hyperopen/src/hyperopen/vaults/actions.cljs` and were not changed for this scope.

This change only extends projection and presentation for deposit mode. No exchange payload formats or signing paths were modified.

## Plan of Work

First, extend `detail_vm.cljs` with helpers that produce a deterministic deposit max amount from wallet balances/withdrawable fields and format both display/input strings. In the same VM, compute deposit lockup days with precedence: user-specific timestamps first, then parity fallback by vault name.

Second, extend `vault_detail_view.cljs` modal rendering to display deposit lock-up subtitle and a MAX control wired to the existing amount action.

Third, update tests in `test/hyperopen/views/vaults/detail_vm_test.cljs` and `test/hyperopen/views/vault_detail_view_test.cljs` to assert new model fields, copy rendering, and dispatch payload wiring.

## Concrete Steps

Run from repository root `/hyperopen`.

1. Implement VM helper and projection updates in:
   - `src/hyperopen/views/vaults/detail_vm.cljs`
2. Implement modal UI updates in:
   - `src/hyperopen/views/vault_detail_view.cljs`
3. Update tests in:
   - `test/hyperopen/views/vaults/detail_vm_test.cljs`
   - `test/hyperopen/views/vault_detail_view_test.cljs`
4. Run validation gates:

    npm run check
    npm test
    npm run test:websocket

Expected result: all commands exit `0`.

## Validation and Acceptance

Acceptance criteria are behavioral:

1. On deposit modal open, lock-up period sentence is visible.
2. On deposit modal open, `MAX: <amount> USDC` is visible.
3. Clicking `MAX` sets the amount input by dispatching `:actions/set-vault-transfer-amount` with the computed amount string.
4. Existing withdraw flow remains unchanged.
5. Required validation gates pass.

## Idempotence and Recovery

Changes are additive and idempotent. Re-running the same edits only overwrites deterministic helpers/rendering and tests. If any gate fails, fix forward and rerun the same three required commands. No migrations or destructive operations are involved.

## Artifacts and Notes

External references used for this implementation:

- Hyperliquid docs, exchange endpoint (`vaultTransfer`):
  `https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint#deposit-or-withdraw-from-a-vault`
- Hyperliquid docs, info endpoint (`vaultDetails` includes `lockupUntil`):
  `https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint#retrieve-details-for-a-vault`
- Official Python SDK (`vault_usd_transfer` action shape):
  `tmp/hyperliquid-python-sdk/hyperliquid/exchange.py`
- Official TypeScript SDK parity (`vaultTransfer`, `vaultDetails`, `userVaultEquities`):
  `tmp/sdk-audit/hyperliquid/src/api/exchange/_methods/vaultTransfer.ts`
  `tmp/sdk-audit/hyperliquid/src/api/info/_methods/vaultDetails.ts`
  `tmp/sdk-audit/hyperliquid/src/api/info/_methods/userVaultEquities.ts`
- Hyperliquid frontend vault modal behavior capture:
  `tmp/hyperliquid-frontend/4114.js`

## Interfaces and Dependencies

No new libraries were introduced.

Updated view-model interface (`:vault-transfer` map) now includes:

- `:deposit-max-usdc`
- `:deposit-max-display`
- `:deposit-max-input`
- `:deposit-lockup-days`
- `:deposit-lockup-copy`

Updated view data roles:

- `vault-transfer-deposit-max`
- `vault-transfer-deposit-lockup-copy`

Revision note (2026-02-28): Added post-transfer parity enhancement plan for deposit MAX and lock-up copy, then updated to complete state after implementation and gate validation.
