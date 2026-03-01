# Trade Page Deposit, Perps-Spot Transfer, and Withdraw Workflow (Hyperliquid Parity)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Today `/trade` shows account equity metrics but does not let the user fund or withdraw directly from that surface. After this change, the trade page right-rail account panel will expose `Deposit`, `Perps <-> Spot`, and `Withdraw` actions with the same interaction shape users expect from Hyperliquid: one-click entry from the main trading surface, clear modal flows, wallet-sign confirmation for transfer/withdraw, and deterministic success/error feedback.

A user verifies behavior by opening `/trade`, clicking each funding action, and confirming they can complete transfer/withdraw without leaving the page (except deposit bridge handoff), while seeing immediate UI state transitions before network submission.

## Progress

- [x] (2026-03-01 17:53Z) Re-read planning and architecture constraints from `/hyperopen/.agents/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/RELIABILITY.md`, `/hyperopen/docs/SECURITY.md`, and `/hyperopen/docs/FRONTEND.md`.
- [x] (2026-03-01 17:53Z) Audited current Hyperopen trade-page funding state, modal placeholder behavior, runtime registration/contracts, and account-equity insertion points.
- [x] (2026-03-01 17:53Z) Researched Hyperliquid exchange docs, bridge docs, and SDK implementations (`nktkas`, `hyperliquid-python-sdk`, `nomeida`) for transfer/withdraw signing and action payload rules.
- [x] (2026-03-01 17:53Z) Inspected Hyperliquid frontend bundle and trade.xyz app bundle for trade-page action placement and flow hints.
- [x] (2026-03-01 17:53Z) Authored this implementation ExecPlan.
- [x] (2026-03-01 18:31Z) Implemented funding action cluster in `/trade` account-equity panel and replaced the app-level funding placeholder modal with real funding modal views.
- [x] (2026-03-01 18:31Z) Added user-signed transfer/withdraw signing and API submit seams with contract tests and SDK parity coverage.
- [x] (2026-03-01 18:31Z) Added funding action/effect runtime wiring, effect-order policies, schema contracts, and deterministic state transitions.
- [x] (2026-03-01 18:31Z) Added/updated unit tests and passed required gates (`npm run check`, `npm test`, `npm run test:websocket`).

## Surprises & Discoveries

- Observation: Hyperliquid transfer/withdraw actions are user-signed EIP-712 actions, not L1 agent-signed actions used for orders.
  Evidence: Hyperliquid docs + SDK method signatures for `usdClassTransfer` and `withdraw3` include EIP-712 typed-data fields (`hyperliquidChain`, `signatureChainId`, and `nonce` or `time`) and submit payload includes top-level `nonce` plus signature.

- Observation: Hyperliquid API wallets (agents) are intentionally blocked from transfer and withdraw.
  Evidence: Hyperliquid docs (`nonces-and-api-wallets`) and official python examples raise explicit errors for agents performing withdrawals or internal transfers.

- Observation: Hyperopen currently has only a generic funding placeholder modal (`Coming soon in Phase 2.`) and no funding submit effects.
  Evidence: `/hyperopen/src/hyperopen/views/app_view.cljs` renders placeholder copy for `[:funding-ui :modal]`; `set-funding-modal` only saves modal keyword in `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`.

- Observation: Hyperliquid trade-page action placement is a three-action cluster in account summary: deposit first, then transfer and withdraw.
  Evidence: Production `app.hyperliquid.xyz` bundle inspection shows account-summary action components corresponding to deposit, transfer, and withdraw in that order; trade.xyz app bundle mirrors this framing language.

- Observation: Deposit behavior is bridge-driven and distinct from exchange transfer/withdraw actions.
  Evidence: Hyperliquid bridge docs describe deposit/withdraw bridge behavior and timing; exchange endpoint covers `withdraw3`/transfer actions but not a trade-page-native USDC deposit action equivalent to `usdClassTransfer`.

## Decision Log

- Decision: Implement the trade-page funding cluster as three explicit actions (`Deposit`, `Perps <-> Spot`, `Withdraw`) in the account-equity panel, not hidden in portfolio-only UI.
  Rationale: This is the direct parity gap and gives users immediate funding controls where they place trades.
  Date/Author: 2026-03-01 / Codex

- Decision: Treat transfer and withdraw as first-class user-signed modal flows; keep deposit as bridge handoff flow (open bridge/deposit route) instead of inventing a non-existent exchange deposit action.
  Rationale: Matches protocol reality and avoids coupling trade-page UI to unsupported exchange action shapes.
  Date/Author: 2026-03-01 / Codex

- Decision: Preserve `:actions/set-funding-modal` as a compatibility alias while introducing typed funding actions/state for new workflows.
  Rationale: Avoids regressions for existing portfolio action buttons and keeps public action seams stable during migration.
  Date/Author: 2026-03-01 / Codex

- Decision: Add dedicated heavy effects for funding submit (`:effects/api-submit-funding-transfer`, `:effects/api-submit-funding-withdraw`) and opt them into effect-order contract enforcement.
  Rationale: Keeps projection-before-heavy determinism explicit and testable per frontend/runtime invariants.
  Date/Author: 2026-03-01 / Codex

- Decision: Keep protocol translation and signing details inside API/signing boundaries, never in view callbacks.
  Rationale: Required by security architecture and reduces future breakage when Hyperliquid payload rules evolve.
  Date/Author: 2026-03-01 / Codex

## Outcomes & Retrospective

Implementation is complete for trade-page funding parity scope.

Delivered behavior:
- `/trade` account equity now exposes `Deposit`, `Perps <-> Spot`, and `Withdraw` actions.
- Funding modal host in app shell now renders mode-specific funding flows (deposit handoff, transfer, withdraw) instead of placeholder copy.
- Transfer and withdraw submit through user-signed action seams (`usdClassTransfer`, `withdraw3`) with wallet-context chain metadata and monotonic user nonce cursor updates.
- Funding submit effects now handle wallet preconditions, success/error toasts, modal close/error projection, and post-submit `:actions/load-user-data` refresh.
- Legacy `:actions/set-funding-modal` callers remain supported through compatibility mapping.

Validation evidence (2026-03-01):
- `npm run check` passed.
- `npm test` passed.
- `npm run test:websocket` passed.

Notable divergence from initial draft:
- Added dedicated tests in `/hyperopen/test/hyperopen/funding/effects_test.cljs` to lock transfer/withdraw effect lifecycle semantics; this was marked as optional in earlier iteration notes but completed before closeout.

## Context and Orientation

Trade page layout is owned by `/hyperopen/src/hyperopen/views/trade_view.cljs`, with account equity rendered by `/hyperopen/src/hyperopen/views/account_equity_view.cljs`. The current app-level funding overlay lives in `/hyperopen/src/hyperopen/views/app_view.cljs`, where `:funding-ui :modal` only drives placeholder content.

Runtime action registration and effect wiring are centralized across:

- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
- `/hyperopen/src/hyperopen/registry/runtime.cljs`
- `/hyperopen/src/hyperopen/app/actions.cljs`
- `/hyperopen/src/hyperopen/app/effects.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`

Hyperopen currently has robust agent-signed L1 submit flow in `/hyperopen/src/hyperopen/api/trading.cljs` and typed-data signing helpers in `/hyperopen/src/hyperopen/utils/hl_signing.cljs`, but no user-signed helpers for `usdClassTransfer` or `withdraw3`.

In this plan:

- "Perps-Spot transfer" means Hyperliquid `usdClassTransfer` between spot USDC and perps account balance.
- "Withdraw" means Hyperliquid `withdraw3` bridge withdrawal request.
- "Deposit" means bridge handoff entry point (open deposit bridge path), not a new exchange action.

## Plan of Work

Milestone 1 establishes UI entry points and modal ownership. Update `/hyperopen/src/hyperopen/views/account_equity_view.cljs` to render funding action controls in both classic and unified account variants. Match observed parity structure: a prominent `Deposit` action plus `Perps <-> Spot` and `Withdraw` controls in the same card section. Replace placeholder rendering in `/hyperopen/src/hyperopen/views/app_view.cljs` with a real funding modal host that switches by modal mode and supports keyboard close (`Escape`) and backdrop close.

Milestone 2 adds a dedicated funding domain model/actions layer so views stay declarative. Add `/hyperopen/src/hyperopen/funding/actions.cljs` and optionally `/hyperopen/src/hyperopen/funding/model.cljs` for default modal state, input normalization, and pre-submit validation. Add explicit actions for opening/closing modal, editing transfer/withdraw fields, selecting transfer direction (`toPerp` boolean), setting max amount, and submit triggers. Keep `:actions/set-funding-modal` as adapter compatibility by delegating to the new funding open/close action behavior.

Milestone 3 adds user-signed API seams and signing helpers. Extend `/hyperopen/src/hyperopen/utils/hl_signing.cljs` with typed-data builders and signers for:

- `HyperliquidTransaction:UsdClassTransfer`
- `HyperliquidTransaction:Withdraw`

Then extend `/hyperopen/src/hyperopen/api/trading.cljs` with non-agent submit helpers (for example `submit-usd-class-transfer!` and `submit-withdraw3!`) that:

- resolve a monotonic nonce from wallet scope,
- inject `signatureChainId` and `hyperliquidChain`,
- set action `nonce` or `time` fields correctly,
- sign with connected wallet provider (not agent key),
- POST to exchange endpoint with top-level `nonce` and signature.

Milestone 4 adds funding submit effects and runtime wiring. Create `/hyperopen/src/hyperopen/funding/effects.cljs` with effect interpreters for transfer and withdraw submission lifecycle (preconditions, submitting state, success/error state, toast feedback, and targeted post-success refresh). Register these via collaborators/effect adapters/registry/contracts, and add effect-order policies proving projection writes happen before heavy network submit effects.

Milestone 5 finalizes parity polish, tests, and validation gates. Ensure modal copy, CTA labels, disabled states, and error language are consistent with existing vault transfer and order submission UX patterns. Add focused tests across funding action logic, effect interpreters, signing payload construction, runtime registration coverage, and view rendering. Run required gates and capture pass evidence in this plan.

## Concrete Steps

Run from repository root `/hyperopen`.

1. Implement funding UI controls and modal host:
   - Edit `/hyperopen/src/hyperopen/views/account_equity_view.cljs`.
   - Edit `/hyperopen/src/hyperopen/views/app_view.cljs`.
   - Add new funding modal view namespace(s) under `/hyperopen/src/hyperopen/views/` (for example `/hyperopen/src/hyperopen/views/funding_modal.cljs`).

2. Implement funding domain actions and state:
   - Add `/hyperopen/src/hyperopen/funding/actions.cljs` (and `/hyperopen/src/hyperopen/funding/model.cljs` if used).
   - Update `/hyperopen/src/hyperopen/state/app_defaults.cljs` to replace minimal `:funding-ui` placeholder with structured modal state.
   - Keep `/hyperopen/src/hyperopen/runtime/action_adapters.cljs` compatibility for `set-funding-modal`.

3. Implement signing and trading API seams:
   - Edit `/hyperopen/src/hyperopen/utils/hl_signing.cljs`.
   - Edit `/hyperopen/src/hyperopen/api/trading.cljs`.

4. Implement submit effects and runtime registration:
   - Add `/hyperopen/src/hyperopen/funding/effects.cljs`.
   - Edit `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`.
   - Edit `/hyperopen/src/hyperopen/runtime/collaborators.cljs`.
   - Edit `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`.
   - Edit `/hyperopen/src/hyperopen/registry/runtime.cljs`.
   - Edit `/hyperopen/src/hyperopen/app/actions.cljs`.
   - Edit `/hyperopen/src/hyperopen/app/effects.cljs`.
   - Edit `/hyperopen/src/hyperopen/schema/contracts.cljs`.
   - Edit `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`.

5. Add/update tests:
   - `/hyperopen/test/hyperopen/views/account_equity_view_test.cljs`
   - `/hyperopen/test/hyperopen/views/app_view_test.cljs` (create if absent)
   - `/hyperopen/test/hyperopen/funding/actions_test.cljs` (new)
   - `/hyperopen/test/hyperopen/funding/effects_test.cljs` (new)
   - `/hyperopen/test/hyperopen/api/trading_test.cljs`
   - `/hyperopen/test/hyperopen/api/trading/` additional focused user-signed tests (new file(s))
   - `/hyperopen/test/hyperopen/runtime/collaborators_test.cljs`
   - `/hyperopen/test/hyperopen/app/effects_test.cljs`
   - `/hyperopen/test/hyperopen/schema/contracts_coverage_test.cljs`
   - `/hyperopen/test/hyperopen/core_public_actions_test.cljs` (for `set-funding-modal` compatibility expectations)

6. Run required validation gates:

    npm run check
    npm test
    npm run test:websocket

Expected result: all commands exit `0`.

## Validation and Acceptance

Acceptance is satisfied when all of the following are true:

1. `/trade` account equity panel shows `Deposit`, `Perps <-> Spot`, and `Withdraw` controls in deterministic layout on desktop and mobile breakpoints.
2. Clicking `Deposit` performs the defined bridge handoff behavior (modal with bridge action or direct route open) and no longer shows placeholder copy.
3. Clicking `Perps <-> Spot` opens a transfer modal that validates amount and direction, requests wallet signature, and submits `usdClassTransfer` payload on confirm.
4. Clicking `Withdraw` opens a withdrawal modal that validates destination and amount, enforces documented minimum guardrails (at least 5 USDC unless protocol response changes), requests wallet signature, and submits `withdraw3` payload on confirm.
5. Transfer/withdraw actions update user-visible submitting/error state before issuing heavy network effects.
6. Success clears modal state and provides explicit success feedback; failure preserves inputs and shows actionable error copy.
7. Existing `:actions/set-funding-modal` callers continue to work via compatibility mapping.
8. Required validation gates pass.

## Idempotence and Recovery

All edits are additive and safe to re-run. If a milestone fails mid-way:

- keep `:actions/set-funding-modal` compatibility path intact so existing UI is not broken,
- disable new submit actions behind modal validation until signing/API seams are confirmed,
- re-run targeted tests first, then full required gates.

No destructive migrations or irreversible data changes are required.

## Artifacts and Notes

Protocol notes embedded for implementation:

- `usdClassTransfer` action fields: `hyperliquidChain`, `signatureChainId`, `amount`, `toPerp`, `nonce`.
- `withdraw3` action fields: `hyperliquidChain`, `signatureChainId`, `amount`, `destination`, `time`.
- Exchange payload for user-signed actions still includes top-level `nonce` and `signature` object (`r`, `s`, `v`).
- API wallet/agent key is not permitted for transfers/withdrawals; connected owner wallet signature is required.

UI parity notes embedded for implementation:

- Trade-page account summary action cluster should expose funding controls directly, with `Deposit` emphasized and transfer/withdraw adjacent.
- Hyperliquid deposit route includes `/portfolio/deposit`; using this as bridge handoff target is acceptable for parity-first delivery.

Reference URLs consulted during scoping:

- https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint
- https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/nonces-and-api-wallets
- https://hyperliquid.gitbook.io/hyperliquid-docs/trading/deposit-and-withdraw/bridge2
- https://github.com/nktkas/hyperliquid
- https://github.com/hyperliquid-dex/hyperliquid-python-sdk
- https://github.com/nomeida/hyperliquid
- https://app.hyperliquid.xyz
- https://app.trade.xyz/trade

## Interfaces and Dependencies

No new third-party dependencies are required.

Expected new or updated interfaces at completion:

- `hyperopen.funding.actions/*` action handlers for modal lifecycle and submit.
- `hyperopen.funding.effects/api-submit-funding-transfer!`.
- `hyperopen.funding.effects/api-submit-funding-withdraw!`.
- `hyperopen.api.trading/submit-usd-class-transfer!`.
- `hyperopen.api.trading/submit-withdraw3!`.
- `hyperopen.utils.hl-signing/sign-usd-class-transfer-action!`.
- `hyperopen.utils.hl-signing/sign-withdraw3-action!`.
- Runtime action IDs:
  - `:actions/open-funding-transfer-modal`
  - `:actions/open-funding-withdraw-modal`
  - `:actions/open-funding-deposit-modal`
  - `:actions/close-funding-modal`
  - `:actions/submit-funding-transfer`
  - `:actions/submit-funding-withdraw`
- Runtime effect IDs:
  - `:effects/api-submit-funding-transfer`
  - `:effects/api-submit-funding-withdraw`

If naming is adjusted during implementation, update contracts, runtime registration, and tests in the same commit to prevent drift.

Revision note (2026-03-01): Initial plan authored after repository audit plus Hyperliquid docs/SDK/frontend parity research for trade-page deposit/transfer/withdraw workflow.
