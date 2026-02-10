# Hyperopen Agent Wallet Trading Enablement PRD

Status: Draft  
Last updated: 2026-02-10  
Owner: Hyperopen engineering  
Audience: Product + engineering threads implementing phased order execution

## 1. Purpose

Enable real order placement/cancellation in Hyperopen with the same UX pattern as Hyperliquid frontend:

- user signs once to approve an API/agent wallet
- subsequent order/cancel actions are signed in-browser by the agent wallet
- no MetaMask prompt per order

This document is the canonical reference for implementation phases across threads.

## 2. Problem Statement

Current Hyperopen has order-entry UI and request construction, but the trading path signs each order directly with `window.ethereum`, causing per-order wallet prompts and missing agent-wallet lifecycle.

We need a secure, deterministic, testable implementation of:

- agent-wallet approval (`approveAgent`)
- local action signing for `/exchange` trading actions
- nonce management and recovery behavior
- session/persistence and account-switch lifecycle

## 3. Goals and Non-Goals

### Goals

- Implement one-time trading enablement via `approveAgent`.
- Use local agent private key for order and cancel signing.
- Preserve deterministic action/effect flow and existing architecture boundaries.
- Add robust nonce handling and invalid-agent recovery.
- Ship with full regression coverage and compile/test gates.

### Non-Goals

- Spot order support in this phase (still unsupported in existing submit flow).
- HIP-3 order support in this phase (still unsupported in existing submit flow).
- Reworking websocket runtime architecture.
- Introducing server-side custody of user/agent keys.

## 4. Research Summary (Verified)

### Protocol and docs

- Hyperliquid supports API wallets (agent wallets) approved by a master account.
- `approveAgent` is a user-signed action (`HyperliquidTransaction:ApproveAgent`) containing:
  - `hyperliquidChain`
  - `signatureChainId`
  - `agentAddress`
  - `agentName` (optional)
  - `nonce`
- Trading actions continue to use `POST /exchange` with outer fields:
  - `action`
  - `nonce`
  - `signature`
  - optional `vaultAddress`
  - optional `expiresAfter`
- Nonces are tracked per signer; for API wallets, signer is the agent address.
- Hyperliquid stores the top 100 nonces per signer and enforces time-window validity.
- API wallet addresses can be pruned/deregistered; reusing old agent addresses is discouraged.

### SDK behavior (official)

- SDK generates a fresh random 32-byte key for new agent approval.
- Approves agent once with user wallet signature.
- Signs order/cancel L1 actions with agent key thereafter.
- Uses two distinct signing schemes:
  - L1 action signing for trading actions.
  - User-signed action signing for `approveAgent` and similar control actions.

### Frontend behavior (inference from live bundle)

- Hyperliquid frontend generates browser-side agent key material.
- Stores agent key scoped by user address.
- Uses a persistence toggle to choose `localStorage` vs `sessionStorage`.
- Uses the approved agent key for trading signatures without recurring wallet popups.

## 5. Current Hyperopen State (Gap Analysis)

- `/hyperopen/src/hyperopen/api/trading.cljs` signs order/cancel through `window.ethereum` each request.
- `/hyperopen/src/hyperopen/utils/hl_signing.cljs` only exposes wallet-sign path; no local-key signing path.
- `/hyperopen/src/hyperopen/wallet/core.cljs` has wallet connect state but no agent lifecycle state.
- `/hyperopen/src/hyperopen/core.cljs` submit/cancel effects gate only on wallet/provider, not agent readiness.
- Signing parity risks exist and must be aligned to protocol/SDK behavior before enabling production trading.

## 6. Product Requirements

### 6.1 Functional requirements

FR-1 Trading Enablement

- User can explicitly enable trading after wallet connection.
- System generates an agent keypair client-side.
- System sends `approveAgent` signed by connected wallet.
- On success, agent session becomes ready for trading.

FR-2 Agent-Signed Trading

- `order` and `cancel` actions are signed with local agent key.
- Trading requests are sent to `/exchange` with protocol-compliant payload.
- No wallet signature prompt is required per order/cancel.

FR-3 Nonce Manager

- Maintain monotonic per-signer nonce cursor.
- Ensure nonce uniqueness and timestamp suitability.
- Support fast-forward/retry behavior on nonce-too-low responses.

FR-4 Lifecycle Management

- Agent session is invalidated on wallet disconnect/account change.
- Account switch never reuses a different account’s agent credentials.
- Detect and recover from invalid/pruned/revoked agent states by re-approval flow.

FR-5 Persistence Policy

- Default: session-scoped key storage.
- Optional: persistent storage mode (explicit user opt-in).
- Persist only required metadata and encrypted/plain key as decided by implementation thread.

FR-6 Address Correctness

- Data queries/subscriptions continue using actual user/subaccount/vault addresses.
- Agent address is only used as signing identity, not data-query identity.

### 6.2 Security requirements

- No private keys in logs, diagnostics, or error toasts.
- No agent private key exposure in app-state snapshots intended for diagnostics.
- Clear/wipe keys when session is invalidated.
- Document residual XSS risk of browser-held keys.

### 6.3 Engineering constraints

- Keep changes scoped; preserve public APIs unless required.
- Follow deterministic action/effect patterns.
- Preserve existing websocket runtime boundaries and invariants.
- Add tests before/with behavior changes (TDD workflow).

## 7. Architecture and Ownership

Proposed ownership by module:

- Agent session domain and storage:
  - `/hyperopen/src/hyperopen/wallet/agent_session.cljs` (new)
- Signing utilities:
  - `/hyperopen/src/hyperopen/utils/hl_signing.cljs`
- Trading API integration:
  - `/hyperopen/src/hyperopen/api/trading.cljs`
- Action/effect orchestration:
  - `/hyperopen/src/hyperopen/core.cljs`
- Wallet lifecycle integration:
  - `/hyperopen/src/hyperopen/wallet/core.cljs`

### Proposed app-state additions

- `:wallet :agent`
  - `:status` (`:not-ready | :approving | :ready | :error`)
  - `:agent-address`
  - `:storage-mode` (`:session | :local`)
  - `:last-approved-at`
  - `:error`
  - `:nonce-cursor`

## 8. Phased Implementation Plan (Thread-Friendly)

### Phase 0: Signing Parity Foundation

Status: Completed (2026-02-10)

Scope:

- Align signing primitives to protocol/SDK parity.
- Add deterministic test vectors.

Deliverables:

- Correct L1 action hash/signing path.
- Correct user-signed `approveAgent` typed-data path.
- Unit tests for hash, typed payload, signature split/serialization.

Exit criteria:

- Signing tests pass and parity checks match known-good outputs.

Completion notes:

- Implemented in `/hyperopen/src/hyperopen/utils/hl_signing.cljs`.
- Covered by parity and typed-data tests in `/hyperopen/test/hyperopen/utils/hl_signing_test.cljs`.

### Phase 1: Agent Session Lifecycle

Scope:

- Add agent key generation/storage/load/clear and approval flow.

Deliverables:

- `Enable Trading` action/effect pipeline.
- `approveAgent` request execution and success/failure state transitions.
- Account switch/disconnect invalidation behavior.

Exit criteria:

- Fresh connect + enable trading yields `:wallet :agent :status :ready`.

### Phase 2: Order/Cancel via Agent

Scope:

- Route submit/cancel through agent signer once ready.

Deliverables:

- `submit-order!` and `cancel-order!` use local agent signatures.
- Error handling + nonce retries.
- No per-order wallet prompt in ready state.

Exit criteria:

- Existing order submit/cancel flows execute against `/exchange` with agent signing.

### Phase 3: Persistence and Recovery Hardening

Scope:

- Session/local persistence mode and resilient recovery.

Deliverables:

- Persistence toggle wiring.
- Invalid/pruned agent detection and re-approval UX.
- Clear messaging for user actions needed.

Exit criteria:

- Restart/session scenarios behave predictably for both persistence modes.

### Phase 4: QA and Rollout Readiness

Scope:

- Full regression, observability hardening, documentation updates.

Deliverables:

- Updated tests and runbooks.
- Diagnostics redaction verification.
- PR notes documenting effect order and behavior guarantees.

Exit criteria:

- All required gates pass:
  - `npm run check`
  - `npm test`
  - `npm run test:websocket`
  - `npx shadow-cljs compile app`
  - `npx shadow-cljs compile test`

## 9. Test Plan

Must-have test coverage:

- Signing determinism and payload parity tests.
- Agent lifecycle tests:
  - connect -> approve -> ready
  - account change -> reset
  - disconnect -> reset
- Submit/cancel effect-order and no-duplicate-effect tests.
- Nonce monotonicity and low-nonce retry tests.
- Regression tests for unsupported market classes (spot/HIP-3) preserving current behavior.

Likely test files:

- `/hyperopen/test/hyperopen/utils/hl_signing_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap_test.cljs`
- `/hyperopen/test/hyperopen/wallet/agent_session_test.cljs` (new)
- `/hyperopen/test/hyperopen/api/trading_test.cljs` (new or expanded)

## 10. Risks and Mitigations

Risk: Browser key custody is XSS-sensitive.

- Mitigation: minimize key exposure, redact diagnostics/logging, default to session storage.

Risk: Nonce collisions in bursty flows.

- Mitigation: atomic nonce service and retry fast-forward strategy.

Risk: Agent pruning/revocation causes silent failures.

- Mitigation: explicit detection and guided re-enable flow.

Risk: Signing mismatch leads to opaque server errors.

- Mitigation: SDK-parity tests and strict serialization rules.

## 11. Open Questions

- Should persistent mode be exposed in Phase 1 or Phase 3?
- Should local key material be plain storage or encrypted at rest in-browser?
- Should we support multiple named agents immediately or only unnamed session agent first?
- What exact error strings should trigger automatic re-approval path vs hard stop?

## 12. Thread Update Log Template

For each implementation thread, append:

- Thread scope:
- Phase:
- Decisions made:
- Files changed:
- Tests added/updated:
- Remaining blockers:

## 13. Source References

- Hyperliquid docs:
  - [Nonces and API wallets](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/nonces-and-api-wallets.md)
  - [Exchange endpoint](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint.md)
  - [Signing](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/signing.md)
  - [Info endpoint](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint.md)
- Hyperliquid Python SDK (commit `b4d2d1bfde9bfb3411fec3f781e3981b48a1a0c5`):
  - `approve_agent`: `hyperliquid/exchange.py`
  - Signing helpers: `hyperliquid/utils/signing.py`
  - Example flow: `examples/basic_agent.py`
- Live frontend bundle (for behavior inference only):
  - `https://app.hyperliquid.xyz/static/js/main.3f543c82.js`
