# Ghost Mode Account Spectating and Read-Identity Seam

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Tracking epic: `hyperopen-pze`.

## Purpose / Big Picture

After this change, Hyperopen users will be able to spectate any public Hyperliquid address in a Trade.xyz-style Ghost Mode flow, including a clear enter/exit control path and explicit "currently spectating" UI state. Hyperopen will separate signing identity from read identity so account streams, account history, and portfolio/vault/account surfaces can follow a spectated address without mutating wallet ownership or agent-signing invariants.

A user will verify this by opening Ghost Mode from the header wallet area, entering a public address, seeing account surfaces switch to that address, seeing mutating trading controls blocked with a Stop Ghost Mode path, and then exiting Ghost Mode to return to their own wallet context.

## Progress

- [x] (2026-03-04 12:33Z) Re-read planning and architecture constraints from `/hyperopen/.agents/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/RELIABILITY.md`, `/hyperopen/docs/SECURITY.md`, `/hyperopen/docs/FRONTEND.md`, and `/hyperopen/docs/WORK_TRACKING.md`.
- [x] (2026-03-04 12:34Z) Audited existing wallet-agent signing, startup address bootstrap, websocket subscription ownership, runtime registration contracts, and order/funding mutation preconditions.
- [x] (2026-03-04 12:35Z) Researched Trade.xyz Ghost Mode documentation and captured behavior parity targets (entry points, spectating state, and explicit stop path).
- [x] (2026-03-04 12:36Z) Created epic `hyperopen-pze` in `bd` for issue lifecycle tracking.
- [x] (2026-03-04 12:37Z) Authored this implementation ExecPlan.
- [x] (2026-03-04 13:09Z) Implemented Milestone 1 domain/state/docs slice:
  - Added `/hyperopen/src/hyperopen/account/context.cljs` with canonical owner/ghost/effective address selectors, mutation eligibility selector, and account-context defaults.
  - Added top-level `:account-context` default state in `/hyperopen/src/hyperopen/state/app_defaults.cljs`.
  - Added startup preference restoration for Ghost watchlist + last-search in `/hyperopen/src/hyperopen/startup/restore.cljs`, and wired startup dependency chain in `/hyperopen/src/hyperopen/startup/init.cljs` + `/hyperopen/src/hyperopen/app/startup.cljs`.
  - Added ADR `/hyperopen/docs/architecture-decision-records/0024-effective-account-address-and-ghost-mode-ownership.md`.
  - Added test coverage in `/hyperopen/test/hyperopen/account/context_test.cljs`, `/hyperopen/test/hyperopen/startup/restore_test.cljs`, and updated existing startup/state tests.
- [x] (2026-03-04 13:19Z) Ran validation gates for current scope: `npm run check`, `npm test`, and `npm run test:websocket` all passed.
- [x] (2026-03-04 15:39Z) Implemented Milestone 2 effective-address routing for read-side runtime paths:
  - Migrated effective-address subscription/watch ownership in `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`.
  - Updated stale-guard account bootstrap routing in `/hyperopen/src/hyperopen/startup/runtime.cljs`.
  - Updated user websocket refresh stale-guards in `/hyperopen/src/hyperopen/websocket/user.cljs`.
  - Migrated account history effects + order-history freshness keying in `/hyperopen/src/hyperopen/account/history/effects.cljs` and `/hyperopen/src/hyperopen/account/history/actions.cljs`.
  - Migrated user abstraction stale-write guard and account freshness selectors in `/hyperopen/src/hyperopen/api/projections.cljs` and `/hyperopen/src/hyperopen/views/account_info/vm.cljs`.
  - Updated runtime fallback read-data address resolution in `/hyperopen/src/hyperopen/runtime/api_effects.cljs`.
  - Updated portfolio metrics cache token ownership to effective address in `/hyperopen/src/hyperopen/views/portfolio/vm/utils.cljs`.
  - Added regression coverage for effective-address semantics under Ghost Mode in watcher/startup/websocket/projections/account-history/account-info tests.
- [x] (2026-03-04 15:39Z) Re-ran validation gates after Milestone 2 changes: `npm test`, `npm run check`, and `npm run test:websocket` all passed.
- [ ] Implement Milestone 3 (Ghost Mode UI, watchlist persistence, and stop controls).
- [ ] Implement Milestone 4 (mutation guardrails, tests, docs, and validation gates).

## Surprises & Discoveries

- Observation: Trade.xyz Ghost Mode is explicitly read-only and framed as observation, not interaction.
  Evidence: Docs text states Ghost Mode does not let users interact with the trader and is limited to observing onchain activity.

- Observation: Trade.xyz parity includes explicit entry and explicit stop affordances in multiple places.
  Evidence: Docs screenshots show a Ghost icon entry near wallet controls, a persistent "currently spectating" banner, and a Stop Ghost Mode action in the order ticket area.

- Observation: Hyperopen currently uses `[:wallet :address]` as both signing identity and read-subscription identity in many boundaries.
  Evidence: `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`, `/hyperopen/src/hyperopen/startup/runtime.cljs`, `/hyperopen/src/hyperopen/runtime/api_effects.cljs`, `/hyperopen/src/hyperopen/account/history/effects.cljs`, `/hyperopen/src/hyperopen/websocket/user.cljs`, and multiple view-model freshness selectors read `[:wallet :address]` directly.

- Observation: Hyperopen already has strong signing/agent-session separation that must remain untouched by Ghost Mode.
  Evidence: `/hyperopen/src/hyperopen/api/trading.cljs`, `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`, `/hyperopen/src/hyperopen/wallet/agent_session.cljs`, and `/hyperopen/docs/SECURITY.md` enforce signer/session determinism and cautious invalidation.

- Observation: Fresh worktrees may not have local JS dependencies installed, which blocks test execution even when compile passes.
  Evidence: Initial test attempt failed with `Cannot find module '@noble/secp256k1'`; running `npm install` resolved runtime test execution.

## Decision Log

- Decision: Introduce a canonical "effective account address" seam and migrate read-side account flows to that seam, instead of wiring Ghost Mode ad hoc into each API call.
  Rationale: This contains complexity, preserves determinism, and avoids repeated conditional logic across websocket/API/view layers.
  Date/Author: 2026-03-04 / Codex

- Decision: Keep wallet owner/signer identity (`[:wallet :address]`) authoritative for signing and agent-session ownership, and never repurpose it for spectating.
  Rationale: Security invariants require signer identity stability and explicit reconciliation; Ghost Mode must not blur custody boundaries.
  Date/Author: 2026-03-04 / Codex

- Decision: Enforce read-only behavior during Ghost Mode in action/effect preconditions, not just by hiding UI buttons.
  Rationale: Prevents accidental side effects from any stale UI path or direct action dispatch and keeps behavior auditable in tests.
  Date/Author: 2026-03-04 / Codex

- Decision: Treat watchlist as local, client-side persistence scoped to the browser profile.
  Rationale: Trade.xyz-style quick switching is a UX affordance and does not require backend ownership; local persistence keeps rollout low-risk.
  Date/Author: 2026-03-04 / Codex

- Decision: Add an ADR for the effective-address seam and ownership split.
  Rationale: This is architecture-affecting because it changes identity flow contracts across startup, websocket, API, and UI projection boundaries.
  Date/Author: 2026-03-04 / Codex

- Decision: Restore Ghost watchlist and last-search through one startup function (`restore-ghost-mode-preferences!`) rather than multiple independent restore calls.
  Rationale: Keeps startup restore ordering simple and ensures Ghost UI substate is initialized atomically.
  Date/Author: 2026-03-04 / Codex

## Outcomes & Retrospective

Milestones 1 and 2 are complete.

Delivered in Milestone 1:

- account-context domain seam with explicit effective-address and mutation-eligibility selectors;
- default app-state branch for Ghost Mode account context;
- startup restoration for Ghost watchlist and last-used modal search preference;
- architecture decision record documenting owner vs effective account identity ownership split;
- focused automated coverage for new domain and restore behavior.

Delivered in Milestone 2:

- read-side address routing now consistently follows `effective-account-address` across watcher, startup stale-guards, websocket account refreshes, account history fetch guards, and user-abstraction stale-write projection guards;
- account-info websocket freshness cues and portfolio metrics cache tokening now key by effective address instead of wallet owner;
- regression coverage now explicitly exercises Ghost Mode effective-address paths (without mutating signer ownership) across runtime and projection boundaries.

Expected outcome of this plan is a stable, test-covered Ghost Mode that mirrors Trade.xyz behavior without violating Hyperopen signing/runtime invariants. The main risk area is migration completeness from direct `[:wallet :address]` reads to effective-address reads; this plan mitigates that with explicit file touchpoints and regression coverage requirements.

## Context and Orientation

Define these terms for this repository:

- Owner address: connected wallet address in `[:wallet :address]`. This remains the only signing identity.
- Ghost address: the address being spectated when Ghost Mode is active.
- Effective account address: the canonical read identity used for account subscriptions, account fetches, and account-scoped freshness cues. This equals Ghost address when Ghost Mode is active; otherwise equals owner address.
- Ghost Mode: a read-only spectating mode where user/account data reflects the effective account address and mutating actions are blocked until Ghost Mode is stopped.

Current architecture to preserve:

- Signing and submit ownership is in `/hyperopen/src/hyperopen/api/trading.cljs`, `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`, and `/hyperopen/src/hyperopen/wallet/agent_session.cljs`.
- Startup address-bootstrap and account fetch orchestration is in `/hyperopen/src/hyperopen/startup/runtime.cljs` and `/hyperopen/src/hyperopen/startup/collaborators.cljs`.
- Address-change websocket subscription handling is in `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`, `/hyperopen/src/hyperopen/websocket/user.cljs`, and `/hyperopen/src/hyperopen/websocket/webdata2.cljs`.
- Runtime action/effect catalog contracts are in `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`, `/hyperopen/src/hyperopen/schema/contracts.cljs`, and `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`.
- Header wallet controls are in `/hyperopen/src/hyperopen/views/header_view.cljs`.
- Order ticket submit path and mutation preconditions are in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`, `/hyperopen/src/hyperopen/state/trading.cljs`, `/hyperopen/src/hyperopen/order/actions.cljs`, and `/hyperopen/src/hyperopen/order/effects.cljs`.

## Plan of Work

### Milestone 1: Add Effective Account Address Domain Seam and Ghost Mode State

Create a pure identity policy module at `/hyperopen/src/hyperopen/account/context.cljs` that owns address normalization and account-context selectors.

The module must export at least:

- `normalize-address`
- `owner-address`
- `ghost-address`
- `ghost-mode-active?`
- `effective-account-address`
- `mutations-allowed?`

Extend app defaults in `/hyperopen/src/hyperopen/state/app_defaults.cljs` with a new top-level `:account-context` branch, including Ghost Mode modal/search/watchlist state and active spectating metadata.

Add startup restoration for Ghost watchlist and last-used modal preferences in `/hyperopen/src/hyperopen/startup/restore.cljs` (local-only state, no network assumptions).

Create ADR `0024` under `/hyperopen/docs/architecture-decision-records/` describing the new ownership split between owner address and effective account address.

### Milestone 2: Route Read-Side Runtime Through Effective Account Address

Replace direct `[:wallet :address]` usage in read-side address routing with `account-context/effective-account-address` where behavior is account-data/subscription scoped.

Required migration touchpoints:

- `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`
- `/hyperopen/src/hyperopen/startup/runtime.cljs`
- `/hyperopen/src/hyperopen/startup/collaborators.cljs`
- `/hyperopen/src/hyperopen/runtime/api_effects.cljs`
- `/hyperopen/src/hyperopen/websocket/user.cljs`
- `/hyperopen/src/hyperopen/account/history/effects.cljs`
- `/hyperopen/src/hyperopen/account/history/actions.cljs` (order-history freshness address key)
- `/hyperopen/src/hyperopen/api/projections.cljs` (stale-write guard comparisons)
- `/hyperopen/src/hyperopen/views/account_info/vm.cljs` and other freshness-selector sites that currently key cues by wallet address.

Preserve mutation identity in signing/effects modules: `submit-order!`, `cancel-order!`, funding submits, and vault-transfer submits continue to use owner address only.

### Milestone 3: Deliver Ghost Mode UI and Entry/Exit Flow

Implement Ghost Mode controls in header wallet UX, including:

- open Ghost Mode modal action,
- search input for public address (hex EVM first-pass; ENS optional follow-up),
- start spectating action,
- clear banner showing the spectated address,
- stop spectating action.

Primary files:

- `/hyperopen/src/hyperopen/views/header_view.cljs`
- `/hyperopen/src/hyperopen/views/app_view.cljs` (global spectating banner)
- new Ghost Mode view namespace(s) under `/hyperopen/src/hyperopen/views/` (for modal/list/watchlist UI).

Add runtime actions/effects/contracts/registry entries for Ghost Mode command IDs in:

- `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`
- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `/hyperopen/src/hyperopen/registry/runtime.cljs`
- `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`

Persist watchlist in local storage via existing persistence effect seam (`:effects/local-storage-set-json`) and dedicated storage key under Ghost Mode domain ownership.

### Milestone 4: Enforce Read-Only Guardrails and Complete Validation

Add explicit mutation blocking when `mutations-allowed?` is false (Ghost Mode active), with user-facing remediation pointing to Stop Ghost Mode.

Required mutation boundaries:

- `/hyperopen/src/hyperopen/state/trading.cljs` submit-policy reason and message
- `/hyperopen/src/hyperopen/order/actions.cljs`
- `/hyperopen/src/hyperopen/order/effects.cljs`
- `/hyperopen/src/hyperopen/funding/actions.cljs` and `/hyperopen/src/hyperopen/funding/application/submit_effects.cljs`
- `/hyperopen/src/hyperopen/vaults/actions.cljs` and `/hyperopen/src/hyperopen/vaults/effects.cljs` (vault transfer submit path)

Add order-ticket stop affordance parity in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` so Ghost Mode provides a direct exit control where users would otherwise place orders.

Add keyboard stop shortcut parity (Cmd/Ctrl + X) in startup interaction install path, ensuring deterministic cleanup and no collisions with existing shortcuts.

Document rollout + QA:

- add `/hyperopen/docs/qa/ghost-mode-manual-matrix.md`
- add `/hyperopen/docs/runbooks/ghost-mode-rollout.md`
- add index links where needed under `/hyperopen/docs/`.

## Concrete Steps

Run from repository root `/hyperopen`.

1. Implement identity seam and state:
   - add `/hyperopen/src/hyperopen/account/context.cljs`
   - edit `/hyperopen/src/hyperopen/state/app_defaults.cljs`
   - edit `/hyperopen/src/hyperopen/startup/restore.cljs`
   - add ADR `/hyperopen/docs/architecture-decision-records/0024-effective-account-address-and-ghost-mode-ownership.md`

2. Migrate read-side address routing:
   - edit startup, watcher, websocket user, runtime API, projection stale-guard, and account-history files listed in Milestone 2.

3. Implement Ghost Mode actions and UI:
   - add Ghost Mode action namespace (recommended `/hyperopen/src/hyperopen/account/ghost_mode_actions.cljs`)
   - wire runtime adapters/catalog/contracts/registry
   - implement modal/banner/watchlist UI in header/app views.

4. Enforce mutation guardrails and stop affordances:
   - edit trading submit policy and mutation effects/actions boundaries.

5. Add or update tests:
   - `/hyperopen/test/hyperopen/account/context_test.cljs` (new)
   - `/hyperopen/test/hyperopen/wallet/address_watcher_test.cljs`
   - `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
   - `/hyperopen/test/hyperopen/runtime/action_adapters_test.cljs`
   - `/hyperopen/test/hyperopen/runtime/api_effects_test.cljs`
   - `/hyperopen/test/hyperopen/account/history/actions_test.cljs`
   - `/hyperopen/test/hyperopen/account/history/effects_test.cljs`
   - `/hyperopen/test/hyperopen/order/actions_test.cljs`
   - `/hyperopen/test/hyperopen/order/effects_test.cljs`
   - `/hyperopen/test/hyperopen/views/header_view_test.cljs`
   - `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs`
   - `/hyperopen/test/hyperopen/schema/contracts_coverage_test.cljs`
   - `/hyperopen/test/hyperopen/runtime/registry_composition_test.cljs`
   - `/hyperopen/test/hyperopen/runtime/wiring_test.cljs`

6. Run validation gates and compile checks:

    npm run check
    npm test
    npm run test:websocket
    npx shadow-cljs compile app
    npx shadow-cljs compile test

Expected result: all commands exit `0`.

## Validation and Acceptance

Acceptance is met when all conditions are true:

1. User can start Ghost Mode without wallet connection by entering a valid public address.
2. While Ghost Mode is active, account-scoped surfaces (open orders, fills, funding rows, balances/positions/account summaries, websocket freshness cues) reflect the spectated address.
3. Header/app surfaces clearly indicate active spectating context and show an explicit Stop Ghost Mode path.
4. Order placement/cancel/funding submit/vault transfer actions are blocked during Ghost Mode and return deterministic user-facing guidance to stop Ghost Mode first.
5. Stopping Ghost Mode restores owner-address context (if connected) and refreshes account data/subscriptions accordingly.
6. Wallet connect/disconnect/account-switch flows preserve signing invariants and do not corrupt agent session ownership.
7. Required tests and gates pass.

## Idempotence and Recovery

All milestones are additive and can be re-run safely.

If migration is partially complete, keep `effective-account-address` helper returning owner address when Ghost Mode state is absent so existing behavior remains functional.

If regressions appear in account bootstrap/subscription churn:

- temporarily gate Ghost Mode entry behind an internal feature flag in `:account-context` while retaining the domain seam,
- retain new tests to capture failing scenarios,
- fix stale-write guard mismatches before re-enabling.

No destructive storage migrations are required. Watchlist and Ghost state keys should be versioned (`v1`) and ignored when malformed.

## Artifacts and Notes

Embedded parity facts used for implementation (so this plan is self-contained):

- Trade.xyz Ghost Mode allows spectating public addresses and is read-only observation.
- Trade.xyz UI includes wallet-area entry affordance, persistent spectating indicator, and explicit stop action in order-entry context.
- Hyperopen already supports deterministic projection-before-heavy action contracts and runtime registration catalogs; Ghost Mode actions must plug into those same contracts rather than bypassing them.

Traceability links consulted during plan authoring:

- https://docs.trade.xyz/getting-started/ghost-mode
- https://docs.trade.xyz/trading/ghost-mode

## Interfaces and Dependencies

No new third-party dependency is required for MVP address-based Ghost Mode.

Expected new/updated interfaces:

- New domain module:
  - `hyperopen.account.context/effective-account-address`
  - `hyperopen.account.context/mutations-allowed?`

- New action IDs (minimum set):
  - `:actions/open-ghost-mode-modal`
  - `:actions/close-ghost-mode-modal`
  - `:actions/set-ghost-mode-search`
  - `:actions/start-ghost-mode`
  - `:actions/stop-ghost-mode`
  - `:actions/toggle-ghost-watchlist-entry`
  - `:actions/start-ghost-mode-from-watchlist`

- Optional new effect IDs if needed:
  - `:effects/persist-ghost-watchlist`

If optional effect IDs are not introduced, reuse existing storage effects and keep catalog/contracts consistent with no drift.

Plan revision note (2026-03-04): Initial plan authored from repository architecture audit and Trade.xyz Ghost Mode behavior research; scoped as epic `hyperopen-pze` with explicit effective-address migration boundaries.
Plan revision note (2026-03-04): Updated after Milestone 1 implementation to reflect completed code/docs/tests and gate results.
