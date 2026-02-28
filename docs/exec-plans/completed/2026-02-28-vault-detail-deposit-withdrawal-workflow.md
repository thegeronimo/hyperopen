# Vault Detail Deposit/Withdraw Workflow (Hyperliquid Parity)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Today the vault detail page shows `Withdraw` and `Deposit` controls but they are disabled, so users cannot move capital into or out of a vault from Hyperopen. After this change, a connected wallet with an enabled agent can open a vault transfer modal on `/vaults/:vaultAddress`, enter an amount (or choose withdraw-all), submit it, and see deterministic local submit state with success/error feedback and data refresh.

A user verifies the behavior by opening a vault detail page, clicking `Deposit` or `Withdraw`, submitting a valid amount, and observing: (1) submit status transitions, (2) toast/error handling, and (3) refreshed vault detail + user equity + ledger rows.

## Progress

- [x] (2026-02-28 16:58Z) Re-read planning and architecture constraints from `/hyperopen/.agents/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, and vault/frontend guardrails.
- [x] (2026-02-28 16:58Z) Researched Hyperliquid transfer behavior from primary sources (exchange docs + official SDK + frontend bundle behavior).
- [x] (2026-02-28 16:58Z) Audited Hyperopen vault detail/view-model/actions/effects/runtime registration/state defaults/tests for exact insertion points.
- [x] (2026-02-28 17:03Z) Authored this ExecPlan.
- [x] (2026-02-28 20:29Z) Implemented `hyperopen.api.trading/submit-vault-transfer!` seam and added API trading test coverage for missing-session rejection parity.
- [x] (2026-02-28 20:44Z) Implemented vault transfer action/effect flow, runtime wiring, contracts, and app defaults (`:actions/*vault-transfer*`, `:effects/api-submit-vault-transfer`).
- [x] (2026-02-28 20:58Z) Implemented vault detail transfer UI/VM wiring (enabled hero buttons + transfer modal with withdraw-all) and added/updated view/action/effect/runtime/default-state tests.
- [x] (2026-02-28 21:01Z) Ran required validation gates successfully (`npm run check`, `npm test`, `npm run test:websocket`).
- [x] (2026-02-28 21:04Z) Updated plan with completion outcomes and moved this file to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: Hyperliquid uses a dedicated exchange action for vault balance movement, not order-style payloads.
  Evidence: Official action contract is `{"type":"vaultTransfer","vaultAddress":"0x...","isDeposit":<bool>,"usd":<int microunits>}`.

- Observation: Hyperliquid frontend passes withdraw-all as literal zero microunits in the same action.
  Evidence: Production bundle call pattern for withdraw-all is equivalent to `vaultTransfer(..., isDeposit=false, usd=0)`.

- Observation: Existing Hyperopen signing path already supports arbitrary L1 actions and optional vault-address signing context.
  Evidence: `/hyperopen/src/hyperopen/api/trading.cljs` uses `sign-and-post-agent-action!` and can sign/post non-order actions without changing crypto code.

- Observation: Hyperliquid frontend helper module uses a dedicated transfer helper that always submits `type: "vaultTransfer"` and applies integer microunit conversion before signing.
  Evidence: Module `73412` in `https://app.hyperliquid.xyz/static/js/main.be4d3bab.js` exports `rI` with payload `{type:"vaultTransfer", vaultAddress, isDeposit, usd: ...}`.

## Decision Log

- Decision: Reuse `api.trading/sign-and-post-agent-action!` via a new explicit public seam `submit-vault-transfer!`.
  Rationale: Minimizes risk by preserving already-tested signing/noncing/session behavior and keeps vault transfer semantics additive.
  Date/Author: 2026-02-28 / Codex

- Decision: Keep vault transfer UI state under `:vaults-ui` with deterministic projection-first updates.
  Rationale: Aligns with architecture constraints (pure action decisions, side effects in effects layer) and existing vault UI state organization.
  Date/Author: 2026-02-28 / Codex

- Decision: Represent transfer amount in UI as a string and convert to integer microunits in action/effect submit path.
  Rationale: Avoid float drift in state and preserve consensus-safe integer payload requirements for `usd`.
  Date/Author: 2026-02-28 / Codex

- Decision: Mirror Hyperliquid gating by allowing deposit only when `allowDeposits` is true (except leader can always deposit), and permit withdraw action path regardless.
  Rationale: Matches observed frontend behavior and prevents user-facing controls from offering unsupported operations.
  Date/Author: 2026-02-28 / Codex

- Decision: Mirror Hyperliquid frontend withdraw-all semantics by submitting `usd=0` when withdraw-all is selected.
  Rationale: Keeps server-side behavior aligned with production frontend and avoids introducing an unsupported alternate action shape.
  Date/Author: 2026-02-28 / Codex

## Outcomes & Retrospective

Vault detail deposit/withdraw functionality is now implemented end-to-end with deterministic action/effect ordering and runtime registration parity.

Implemented behavior:

- `Withdraw` and `Deposit` hero controls are enabled based on wallet/agent and vault gating state.
- Clicking either opens a vault transfer modal with amount input, submit/cancel controls, and withdraw-all support for withdraw mode.
- Transfer submit uses `vaultTransfer` action payload with integer micro-USDC amount and routes through existing signed agent transport.
- Submit flow is projection-first (`submitting?` + error clear) before network effect, then handles success/error with deterministic modal state and toast feedback.
- Successful submits close/reset the modal and trigger refresh actions (`load-vault-detail`, `load-vaults`).

Validation outcomes:

- `npm run check` passed.
- `npm test` passed (1552 tests, 8032 assertions).
- `npm run test:websocket` passed (154 tests, 710 assertions).

## Context and Orientation

Vault detail rendering is in `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` and currently hardcodes disabled hero buttons for `Withdraw` and `Deposit`. Derived vault detail behavior/state is produced by `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`.

Vault action emission is centralized in `/hyperopen/src/hyperopen/vaults/actions.cljs`; side effects live in `/hyperopen/src/hyperopen/vaults/effects.cljs`. Runtime registration and schema contracts are enforced by:

- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
- `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
- `/hyperopen/src/hyperopen/registry/runtime.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`

Default state lives in `/hyperopen/src/hyperopen/state/app_defaults.cljs`.

Trading/signing integration lives in `/hyperopen/src/hyperopen/api/trading.cljs`, which already handles:

- Agent session loading/persistence
- Monotonic nonces and retry-on-nonce-error
- L1 signing
- POST `/exchange` payload submission and response parsing

For this feature, the key external contract is Hyperliquid `vaultTransfer`:

- `type`: `"vaultTransfer"`
- `vaultAddress`: lowercase `0x` address string
- `isDeposit`: boolean
- `usd`: integer microunits of USDC (`1 USDC = 1_000_000`)

## Plan of Work

Milestone 1 adds API seam and tests. In `/hyperopen/src/hyperopen/api/trading.cljs`, add a public helper `submit-vault-transfer!` that delegates to `sign-and-post-agent-action!` exactly like `submit-order!` and `cancel-order!`. The vault actions module will construct the `vaultTransfer` action payload. Add API tests in `/hyperopen/test/hyperopen/api/trading_test.cljs` to lock missing-session rejection parity and basic call surface.

Milestone 2 adds deterministic vault transfer actions/effects/runtime wiring. In `/hyperopen/src/hyperopen/vaults/actions.cljs`, define modal lifecycle + form update + submit actions, including strict amount normalization and microunit conversion helpers. Emit projection effects first for submit start/error clear, then a heavy effect `:effects/api-submit-vault-transfer`. On successful submit, close/reset modal and trigger refresh effects for detail/equities/ledger.

In `/hyperopen/src/hyperopen/vaults/effects.cljs`, add `api-submit-vault-transfer!` with precondition handling (`wallet connected`, `agent ready`), runtime error mapping, and exchange response checks. Route signing through `trading/submit-vault-transfer!` and return success/error callbacks into store state and toasts.

Wire this effect/action end-to-end through:

- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
- `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
- `/hyperopen/src/hyperopen/registry/runtime.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `/hyperopen/src/hyperopen/app/effects.cljs`

Milestone 3 adds UI + VM parity and tests. Extend `/hyperopen/src/hyperopen/state/app_defaults.cljs` with vault transfer UI defaults (open/mode/amount/withdraw-all/submitting/error). Extend `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` to surface vault transfer UI state, gating booleans, and derived button/submit labels. In `/hyperopen/src/hyperopen/views/vault_detail_view.cljs`, enable hero buttons, render modal, wire input/close/submit actions, and show validation/error/submitting states.

Update tests in:

- `/hyperopen/test/hyperopen/vaults/actions_test.cljs`
- `/hyperopen/test/hyperopen/vaults/effects_test.cljs`
- `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs`
- `/hyperopen/test/hyperopen/views/vault_detail_view_test.cljs`
- `/hyperopen/test/hyperopen/runtime/collaborators_test.cljs`
- `/hyperopen/test/hyperopen/app/effects_test.cljs` (if runtime effect factory map expectations change)

## Concrete Steps

Run from repository root `/hyperopen`.

1. Implement API seam and action/effect/runtime wiring in source files listed above.
2. Implement vault detail modal/view-model/default-state updates.
3. Update affected tests.
4. Run required gates:

    npm run check
    npm test
    npm run test:websocket

Expected result: all commands exit `0`.

## Validation and Acceptance

Acceptance is behavioral:

1. On `/vaults/:vaultAddress`, `Withdraw` and `Deposit` hero controls are enabled according to VM gating (not hard-disabled).
2. Clicking either opens a modal with amount entry and cancel/confirm controls.
3. Submitting invalid input keeps submit disabled and/or shows a deterministic error message.
4. Submitting valid input dispatches `:effects/api-submit-vault-transfer` only after projection updates.
5. On success, modal closes, submit state clears, and vault detail/equity/ledger refresh effects are emitted.
6. On failure (precondition/runtime/exchange), modal remains open with error text and no stale submitting spinner.
7. Validation gates pass.

## Idempotence and Recovery

All changes are additive and safe to re-run. If tests fail mid-way, fix forward and re-run targeted tests plus the required full gates. No destructive migration or data rewrite is required.

## Artifacts and Notes

Key external contract used for this implementation:

    vaultTransfer action:
      type: "vaultTransfer"
      vaultAddress: "0x..."
      isDeposit: true | false
      usd: integer micro-USDC

Observed frontend parity behavior:

    Deposit submit -> vaultTransfer with isDeposit=true and usd from typed amount.
    Withdraw-all submit -> vaultTransfer with isDeposit=false and usd=0.

## Interfaces and Dependencies

No new third-party libraries are required.

New/updated interfaces that must exist at completion:

- `hyperopen.api.trading/submit-vault-transfer!`
- `:actions/open-vault-transfer-modal`
- `:actions/close-vault-transfer-modal`
- `:actions/set-vault-transfer-amount`
- `:actions/set-vault-transfer-withdraw-all`
- `:actions/submit-vault-transfer`
- `:effects/api-submit-vault-transfer`

These names may be adjusted during implementation if a clearer repository-consistent naming emerges, but runtime registration, contracts, and tests must stay in lockstep.

Revision note (2026-02-28): Initial plan authored from direct codebase audit plus Hyperliquid docs/SDK/frontend action contract verification.
Revision note (2026-02-28): Updated with implemented milestones, final decisions, and validation evidence before archive.
