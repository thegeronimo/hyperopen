# Add Passkey Lock Trading Toggle with Explicit Local Compatibility Mode

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-k63w`, and that `bd` issue remains the lifecycle source of truth while this file is active.

## Purpose / Big Picture

After this change, Hyperopen should keep the current no-wallet-popup-per-order trading experience and add a second toggle under the Trading Settings `Session` section that lets the user choose how remembered trading sessions are persisted on the device. When `Remember session` is on and the new passkey toggle is off, Hyperopen should keep today’s behavior and persist the raw agent key in `localStorage` so trading comes back immediately after restart. When `Remember session` is on and the passkey toggle is on, Hyperopen should persist a passkey-locked session instead, restoring trading to a `locked` state that requires one fresh unlock step for the browser session before local signing resumes.

The visible proof now has two branches. In the trade route, a connected wallet with remembered trading and the passkey toggle off should still come back as immediately `ready` after a reload because the app is using the current plain `localStorage` path. The same remembered session with the passkey toggle on should come back `locked`, the header should show `Unlock Trading`, and a successful unlock should return the state to `ready` without wallet popups per order. Browser storage inspection should show raw private key material only in the explicitly disabled passkey mode; when passkey mode is on, storage should contain metadata plus ciphertext only. Trading actions in both modes should also carry `expiresAfter`, and every ready trading session should keep Hyperliquid’s `scheduleCancel` dead man’s switch armed so stale sessions self-cancel open orders if the app disappears.

## Progress

- [x] (2026-04-08 12:57Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`.
- [x] (2026-04-08 12:57Z) Claimed `hyperopen-k63w` and audited the current agent-key lifecycle in `/hyperopen/src/hyperopen/wallet/agent_session.cljs`, `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`, `/hyperopen/src/hyperopen/wallet/core.cljs`, `/hyperopen/src/hyperopen/api/trading.cljs`, and the related tests.
- [x] (2026-04-08 12:58Z) Audited the existing browser persistence boundary in `/hyperopen/src/hyperopen/platform/indexed_db.cljs`, confirmed that `expiresAfter` plumbing already exists in `/hyperopen/src/hyperopen/api/trading.cljs`, and then discovered that the worktree already contained partial WebAuthn and lockbox plumbing that needed integration hardening rather than greenfield implementation.
- [x] (2026-04-08 13:29Z) Updated scope for the user-requested Session-section toggle: passkey lock trading is now an explicit local persistence mode, while toggle-off preserves the current raw-`localStorage` behavior as an insecure compatibility path.
- [x] (2026-04-08 15:28Z) Landed the dual local persistence design across wallet restore, settings preferences, signing resolution, WebAuthn capability gating, and passkey lockbox storage; the app now supports `:session`, `:local/:plain`, and `:local/:passkey` with `:locked` and `:unlocking` lifecycle states.
- [x] (2026-04-08 15:28Z) Added transaction delay protection and dead-man’s-switch refresh on the agent-signing path, including default `expiresAfter`, periodic `scheduleCancel`, and locked-session gating for order, cancel, and vault-transfer flows.
- [x] (2026-04-08 15:28Z) Added unit plus Playwright coverage for the new Session toggle and locked-session flow, then passed `npm test`, `npm run test:websocket`, the focused Playwright regression, `npm run browser:cleanup`, and `npm run check`.

## Surprises & Discoveries

- Observation: the current wallet restore path is synchronous and marks the agent state `:ready` solely from persisted browser state that includes an agent address.
  Evidence: `/hyperopen/src/hyperopen/wallet/core.cljs` calls `load-agent-session-by-mode` inside `set-connected!`, and `persisted-session->agent-state` immediately returns `{:status :ready ...}` when `:agent-address` exists.

- Observation: local persistence cannot move wholesale to IndexedDB without either adding an async wallet restore path or splitting synchronous metadata from the encrypted lockbox.
  Evidence: `/hyperopen/src/hyperopen/platform/indexed_db.cljs` exposes Promise-based `get-json!` and `put-json!`, while `/hyperopen/src/hyperopen/wallet/core.cljs` expects sync restore during wallet connect.

- Observation: Hyperopen already supports `expiresAfter` at the signing and transport layers, so transaction delay protection is not a greenfield protocol change.
  Evidence: `/hyperopen/src/hyperopen/api/trading.cljs`, `/hyperopen/src/hyperopen/utils/hl_signing.cljs`, `/hyperopen/test/hyperopen/api/trading/sign_and_submit_test.cljs`, and `/hyperopen/test/hyperopen/utils/hl_signing_test.cljs` already cover `expires-after` handling.

- Observation: the worktree already contained partial passkey, WebAuthn, and lockbox modules, so the implementation became an integration and hardening pass rather than a clean-sheet addition.
  Evidence: `/hyperopen/src/hyperopen/platform/webauthn.cljs`, `/hyperopen/src/hyperopen/wallet/agent_lockbox.cljs`, `/hyperopen/src/hyperopen/wallet/agent_session.cljs`, and `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs` already existed with partial passkey-specific seams when implementation work started.

- Observation: the legacy `v1` agent-session records still need explicit migration logic, but the new user-requested plain local mode means that migration can no longer be a universal destructive purge.
  Evidence: `/hyperopen/src/hyperopen/wallet/agent_session.cljs` currently uses the `hyperopen:agent-session:v1:` prefix and writes JSON records containing `:private-key`, while the updated plan keeps an explicit raw-`localStorage` mode behind a toggle.

- Observation: the new user requirement keeps the current raw-`localStorage` path available behind a settings toggle, so the passkey work becomes an opt-in secure mode rather than a universal removal of browser-readable raw-key persistence.
  Evidence: user requirement on 2026-04-08: under Trading Settings `Session`, a disabled toggle should preserve the current unencrypted `localStorage` behavior and an enabled toggle should use passkey lock trading.

- Observation: the browser settings flow exposed a runtime contract mismatch for the new passkey row because the view-model emitted a boolean while runtime validation required a keyword-shaped argument.
  Evidence: the first focused Playwright regression failed to open the passkey confirmation strip until `/hyperopen/src/hyperopen/views/header/vm.cljs`, `/hyperopen/src/hyperopen/schema/contracts/common.cljs`, and `/hyperopen/src/hyperopen/schema/contracts/action_args.cljs` were updated to align the request payload and contract.

## Decision Log

- Decision: Ship the browser-only secure-storage path as “approve once, unlock once per browser session,” not “reopen and trade instantly with zero fresh unlock.”
  Rationale: a plain web page cannot safely offer zero-click reuse after browser restart without leaving persistent signing authority reachable to same-origin JavaScript. The passkey-enabled path therefore uses one unlock per browser session even though the user-requested plain local mode preserves today’s insecure immediate-resume behavior.
  Date/Author: 2026-04-08 / Codex

- Decision: Use one named Hyperliquid agent per device or browser profile and keep the device label stable across rotations.
  Rationale: Hyperliquid recommends one API wallet per trading process or frontend session because nonces are tracked per signer, and a matching named `approveAgent` deregisters the prior named agent. Reusing the same stable label gives safe device rotation behavior without reusing the old agent address.
  Date/Author: 2026-04-08 / Codex

- Decision: Split restored agent data into synchronous metadata plus an encrypted lockbox, instead of making wallet connection fully async.
  Rationale: `/hyperopen/src/hyperopen/wallet/core.cljs` currently depends on synchronous restore to decide whether the wallet agent state is `:not-ready`, `:locked`, or `:ready`. Local-mode metadata can remain synchronously readable because it is non-secret, while the encrypted blob moves to IndexedDB.
  Date/Author: 2026-04-08 / Codex

- Decision: Treat passkey-backed local persistence as capability-gated. If the browser lacks the required WebAuthn support, leave plain local persistence available but do not expose a working passkey mode.
  Rationale: the user-requested design now keeps an explicit insecure compatibility mode for local persistence, but the secure path still needs a hard capability gate. A browser that cannot support the passkey flow must not falsely claim to be in passkey mode.
  Date/Author: 2026-04-08 / Codex

- Decision: Make `expiresAfter` default-on for supported trading actions and add `scheduleCancel` refresh in the same plan as the storage fix.
  Rationale: the user explicitly requested Hyperliquid’s damage-control features, the exchange already supports them on the same signed-action path, and they meaningfully reduce damage from stale tabs or delayed network delivery without reintroducing wallet popups.
  Date/Author: 2026-04-08 / Codex

- Decision: Add a second Session-section toggle named conceptually as `Lock trading with passkey`, and make it applicable only when `Remember session` is enabled.
  Rationale: the screenshot and user requirement place the control under Trading Settings `Session`. The toggle has no meaning in session-only mode because the app is not trying to persist trading across browser restarts.
  Date/Author: 2026-04-08 / Codex

- Decision: Model remembered local persistence as a separate protection-mode preference with `:plain` and `:passkey` values, and initialize it to the current behavior (`:plain`) unless the user explicitly enables passkey locking.
  Rationale: this directly matches the requested toggle semantics. It also keeps the state space extensible and avoids overloading the existing `:storage-mode` preference with three unrelated meanings.
  Date/Author: 2026-04-08 / Codex

- Decision: Treat toggle-off local persistence as an explicit insecure compatibility mode that requires warning copy and does not, by itself, close `hyperopen-k63w`.
  Rationale: persisting the raw key in `localStorage` is still the original critical behavior. The settings toggle can expose that mode if product wants it, but the plan must not mislabel it as a completed security remediation.
  Date/Author: 2026-04-08 / Codex

## Outcomes & Retrospective

The implementation landed as a dual-mode browser custody model. Users now have two coordinated controls under Trading Settings `Session`: `Remember session` still chooses session-only versus device persistence, and `Lock trading with passkey` switches remembered local persistence between the insecure raw-`localStorage` compatibility path and the passkey-locked path. When the passkey mode is enabled, reload restores the agent to `:locked`, the header surfaces `Unlock Trading`, and trading resumes after one browser-native unlock without wallet prompts per order. When passkey mode is disabled, the app intentionally preserves the existing zero-click raw-key resume behavior.

The security and runtime boundaries are materially tighter in the passkey-enabled path. Persistent passkey mode now stores metadata plus ciphertext at rest, keeps decrypted signer material in memory only, rejects locked trading actions deterministically, defaults agent-signed actions to bounded `expiresAfter`, and refreshes `scheduleCancel` while the signer stays `:ready`. Unit coverage, websocket coverage, and a committed Playwright regression now exercise the new Session toggle, locked-session CTA, and runtime validation path that gates the passkey settings action.

The most important remaining caveat is unchanged from the plan: this ticket does not remove the raw-key local-storage risk when the user leaves passkey locking off. The toggle-off local path remains an explicit insecure compatibility mode, not a security fix. If product later wants universal hardening or true CEX-style zero-click resume without a browser-readable signing authority, that must become a separate policy or architecture decision rather than a claim attached to this toggle.

## Context and Orientation

In this repository, “agent trading” means Hyperliquid order, cancel, and vault-transfer actions that are signed locally with an approved API wallet, which Hyperliquid also calls an “agent wallet.” Today Hyperopen generates that secp256k1 private key in `/hyperopen/src/hyperopen/wallet/agent_session_crypto.cljs`, persists it directly in `/hyperopen/src/hyperopen/wallet/agent_session.cljs`, restores it during wallet connect in `/hyperopen/src/hyperopen/wallet/core.cljs`, and uses it for order signing in `/hyperopen/src/hyperopen/api/trading.cljs`.

That current shape is the release blocker described by `hyperopen-k63w`. The session record stored by `persist-agent-session!` includes `:private-key`, so any successful same-origin script execution can read it from `localStorage` or `sessionStorage` and sign actions without another wallet prompt. The user has now requested that this behavior remain available as an explicit local compatibility mode when the new passkey toggle is off. That means this plan no longer describes a universal removal of raw-key browser storage. Instead it describes a dual local-mode design: the existing raw-key behavior remains available behind a settings toggle, while the new passkey-enabled mode avoids persisting the raw key at rest and restores remembered trading as `:locked` rather than immediately `:ready`.

Several Hyperliquid rules shape the design and must be treated as repository-local facts in this plan. Hyperliquid tracks nonces per signer, not per user, so each trading process or frontend session should use its own API wallet. A matching named `approveAgent` deregisters the old named agent. Once an agent is deregistered, its nonce set may be pruned, so old agent addresses must not be reused because previously signed actions can replay after pruning. The exchange also supports `expiresAfter` on trading actions and a `scheduleCancel` action that arms a future cancel-all if the client stops refreshing it. This plan assumes those protocol behaviors remain true and uses them directly.

The key files today are:

`/hyperopen/src/hyperopen/wallet/agent_session.cljs` owns storage mode preference, session key naming, and raw-session persistence helpers.

`/hyperopen/src/hyperopen/wallet/agent_runtime.cljs` owns enable-trading orchestration and currently persists the generated raw key immediately after `approveAgent` succeeds.

`/hyperopen/src/hyperopen/wallet/core.cljs` owns wallet connect, disconnect, and account-switch lifecycle. It currently loads persisted agent state synchronously and upgrades it directly to `:ready`.

`/hyperopen/src/hyperopen/api/trading.cljs` owns submit/cancel signing. Its `resolve-agent-session`, `sign-agent-action!`, and nonce persistence helpers currently assume the session record contains a usable raw private key.

`/hyperopen/src/hyperopen/views/header/vm.cljs`, `/hyperopen/src/hyperopen/views/header/settings.cljs`, and `/hyperopen/src/hyperopen/views/agent_trading_recovery_modal.cljs` own the current enable-trading, remember-session, and recovery copy. They do not yet model `:locked` or `:unlocking` states, and the settings view does not yet expose a second local-protection toggle under `Session`.

`/hyperopen/src/hyperopen/platform/indexed_db.cljs` is the canonical browser persistence boundary for async IndexedDB-backed data and should own the new durable lockbox store instead of adding ad hoc `js/indexedDB` calls elsewhere.

The important terms for this plan are:

A “locked blob” is an encrypted agent-key record used only when passkey locking is enabled. It contains ciphertext plus only the metadata required to unwrap and validate it, such as the agent address, owner address, stable device label, credential identifier, IV, salt, version, and nonce cursor. It must never contain the raw secp256k1 private key in plaintext.

An “unlocked signer handle” is the in-memory-only representation of a usable agent signer for the current browser session. In passkey mode it may hold the decrypted private key in module-local memory, but it must never be serialized into app state, reducers, logs, diagnostics, localStorage, sessionStorage, or IndexedDB.

A “passkey unlock” in this plan means a browser-native WebAuthn flow that requires fresh user presence or verification in order to derive or release the key-encryption material needed to decrypt the locked blob. If that WebAuthn capability is not present, Hyperopen must not simulate the same outcome while claiming to be in passkey mode.

A “plain local mode” in this plan means the exact current remembered-session behavior: the agent key is persisted unencrypted in `localStorage`, reconnecting or reloading comes back immediately `:ready`, and the mode remains explicitly insecure.

## Plan of Work

Milestone 1 establishes the new settings and state model. Keep `:wallet :agent :storage-mode` as the existing `:session | :local` preference, but add a second local-persistence protection preference with concrete values `:plain` and `:passkey`. Expand `:wallet :agent :status` so the runtime can represent `:locked` and `:unlocking` in addition to the existing `:not-ready`, `:approving`, `:ready`, and `:error`. In `/hyperopen/src/hyperopen/wallet/core.cljs`, restore behavior must now branch by both storage mode and local protection mode: `:session` keeps the current session-scoped restore semantics, `:local/:plain` keeps the current immediate `:ready` restore semantics, and `:local/:passkey` restores to `:locked` until a signer handle is reinstalled. This milestone is complete when reconnecting a remembered wallet behaves differently for the two local toggle states and no code has to guess which path is active.

Milestone 2 replaces the single local persistence path with explicit backend owners. In `/hyperopen/src/hyperopen/wallet/agent_session.cljs`, keep the current plain raw-session helpers for `:local/:plain` and `:session`, but wrap them behind a versioned protection-mode contract instead of treating them as the only storage shape. Add metadata helpers for the passkey-enabled local path. For `:local/:passkey`, store only non-secret metadata synchronously in `localStorage` and store the encrypted lockbox in a new IndexedDB object store under `/hyperopen/src/hyperopen/platform/indexed_db.cljs`; this preserves synchronous wallet restore while moving the sensitive payload behind the async storage boundary. Do not destructively purge legacy local raw-key records on upgrade because the user-requested `:plain` mode intentionally preserves that behavior. Instead, migrate them into the new explicit `:plain` protection-mode model and purge only when the user switches a remembered session into passkey mode. This milestone is complete when browser storage inspection shows the correct shape for each mode and the selected protection mode is explicit.

Milestone 3 introduces the passkey and lockbox boundary. Add a new platform-facing namespace such as `/hyperopen/src/hyperopen/platform/webauthn.cljs` that owns capability detection and WebAuthn browser calls, and a wallet-facing namespace such as `/hyperopen/src/hyperopen/wallet/agent_lockbox.cljs` that owns encryption, decryption, and the in-memory signer-handle map. The passkey-enabled local path should be capability-gated: require a platform authenticator or passkey path that supports the needed unlock flow before allowing the toggle to turn on. The persistent lockbox algorithm should be concrete and deterministic: generate the secp256k1 agent keypair, derive a symmetric wrapping key from the WebAuthn-authenticated unlock path, encrypt the raw hex private key with AES-GCM, persist only ciphertext plus metadata, and keep the decrypted result only in module-local memory. If the browser does not support the required passkey capability, leave `:local/:plain` and `:session` behavior intact but disable the passkey toggle with explicit helper copy. This milestone is complete when the passkey-enabled local mode restores only a locked record and unlock requires a fresh browser-native authenticator interaction.

Milestone 4 rewires enable, unlock, and signing flows around the selected local protection mode. In `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`, change `enable-agent-trading!` so it creates the agent keypair, uses a stable per-device agent name for `approveAgent`, and then persists either the current raw local record (`:local/:plain`), the current raw session record (`:session`), or the new passkey lockbox (`:local/:passkey`) according to settings. Add explicit `unlock-agent-trading!` and `lock-agent-trading!` runtime functions plus matching actions and effect adapters in `/hyperopen/src/hyperopen/wallet/actions.cljs`, `/hyperopen/src/hyperopen/runtime/action_adapters/wallet.cljs`, and `/hyperopen/src/hyperopen/runtime/effect_adapters/wallet.cljs`; these actions apply only to passkey-enabled local mode. In `/hyperopen/src/hyperopen/api/trading.cljs`, replace `resolve-agent-session` and `sign-agent-action!` so passkey-enabled local mode requires an unlocked signer handle, while plain local and session modes keep the current direct raw-key signing path. Invalid-agent recovery must still clear the active persisted record for whichever mode is selected, and the next enable must always mint a brand-new agent keypair rather than reusing the old address. This milestone is complete when trading works deterministically from all three supported persistence modes.

Milestone 5 updates the user interface and copy to make the dual-mode behavior visible and intentional. In `/hyperopen/src/hyperopen/views/header/vm.cljs`, show `Unlock Trading` when the agent status is `:locked`, `Awaiting passkey...` or equivalent when it is `:unlocking`, and keep `Enable Trading` only for `:not-ready` or unrecoverable invalidation. In `/hyperopen/src/hyperopen/views/header/settings.cljs`, keep the existing `Remember session` toggle and add a second row beneath it, in the same `Session` section, for `Lock trading with passkey`. Only show or enable that second row when `Remember session` is on. Its helper copy must explain that off preserves the current raw `localStorage` behavior and on stores the remembered session in a passkey-locked blob that requires unlock after restart. Disabling passkey locking should require explicit warning copy because it reintroduces browser-readable raw-key persistence. In `/hyperopen/src/hyperopen/views/agent_trading_recovery_modal.cljs`, preserve the current re-enable flow for invalid or pruned agents, but do not conflate `:locked` with “Hyperliquid no longer recognizes this trading setup.” Add view-model and action coverage so the header and recovery surfaces remain deterministic under `:not-ready`, `:locked`, `:unlocking`, `:ready`, and `:error`. This milestone is complete when the trade header and settings panel expose the correct action and explanatory copy for each lifecycle and protection state.

Milestone 6 adds Hyperliquid damage-control defaults on the same agent-signing path. Keep the current no-wallet-popup-per-order experience, but update `/hyperopen/src/hyperopen/api/trading.cljs` and the order-submit or cancel effect callers so supported agent-signed trading actions automatically send a bounded `expiresAfter` window. Use one repo-owned configuration value for that window rather than sprinkling literals through the code. Then add a small runtime owner, likely under `/hyperopen/src/hyperopen/wallet/` or `/hyperopen/src/hyperopen/runtime/`, that arms `scheduleCancel` for every unlocked session and refreshes it periodically while the signer remains `:ready`. If the app locks, disconnects, or loses the signer handle, stop refreshing and leave the pending cancel-all in place so stale sessions fail safe. This milestone is complete when order, cancel, and vault-transfer requests include `expiresAfter`, and a ready trading session keeps the dead man’s switch armed with deterministic refresh behavior.

Milestone 7 expands automated coverage and browser verification. Update the existing unit suites in `/hyperopen/test/hyperopen/wallet/agent_session_test.cljs`, `/hyperopen/test/hyperopen/wallet/agent_runtime_test.cljs`, `/hyperopen/test/hyperopen/wallet/connection_runtime_test.cljs`, `/hyperopen/test/hyperopen/core_bootstrap/agent_trading_lifecycle_test.cljs`, and the internal trading tests under `/hyperopen/test/hyperopen/api/trading/**` so they cover the new metadata shape, lockbox backends, `:locked` restore, unlock flow, invalidation, and dead-man’s-switch behavior. Add telemetry or diagnostics regression checks proving the lockbox, raw key, and decrypted signer never leak into console preload or debug APIs. Extend `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` with the stable local flow: enable trading, reload, observe `locked`, unlock, then submit a simulator-backed order without wallet prompts. Finish with the required repo gates plus a focused browser QA pass for the trade route. This milestone is complete when the browser and unit suites prove the new lifecycle end to end.

## Concrete Steps

From the repository root (`/Users/barry/.codex/worktrees/daf8/hyperopen`), start by re-reading the current seams and recording their baseline behavior:

1. Inspect the current storage and signing seams:

   `rg -n "persist-agent-session|load-agent-session|clear-agent-session|storage-mode-preference|resolve-agent-session|sign-agent-action!" src/hyperopen/wallet src/hyperopen/api/trading.cljs test/hyperopen`

2. Confirm the repo has no reusable WebAuthn boundary and find the exact header or recovery surfaces that need lifecycle-state changes:

   `rg -n "PublicKeyCredential|navigator\\.credentials|webauthn|passkey|Unlock Trading|Enable Trading" src test`

3. Add the new platform and wallet owners first, before editing the public adapters. While doing this, introduce a new local protection-mode preference and keep the current plain-storage helpers available under that explicit mode:

   - `/hyperopen/src/hyperopen/platform/webauthn.cljs`
   - `/hyperopen/src/hyperopen/wallet/agent_lockbox.cljs`
   - update `/hyperopen/src/hyperopen/platform/indexed_db.cljs`
   - update `/hyperopen/src/hyperopen/wallet/agent_session.cljs`

4. After the lockbox helpers compile, refactor the wallet and API runtime seams:

   - update `/hyperopen/src/hyperopen/wallet/core.cljs`
   - update `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`
   - update `/hyperopen/src/hyperopen/wallet/actions.cljs`
   - update `/hyperopen/src/hyperopen/runtime/action_adapters/wallet.cljs`
   - update `/hyperopen/src/hyperopen/runtime/effect_adapters/wallet.cljs`
   - update `/hyperopen/src/hyperopen/api/trading.cljs`

5. Then land the UI and runtime safety changes, including the new Session-section toggle row and any confirmation copy needed before disabling passkey locking:

   - update `/hyperopen/src/hyperopen/views/header/vm.cljs`
   - update `/hyperopen/src/hyperopen/views/header/settings.cljs`
   - update `/hyperopen/src/hyperopen/views/agent_trading_recovery_modal.cljs`
   - update any runtime-state helpers that hold dead-man’s-switch timers

6. Use a small compile and focused-test loop before running the full repo suite:

   - `npm run test:runner:generate`
   - `npx shadow-cljs --force-spawn compile test`

7. Run the focused lifecycle and trading suites once the new seams are wired:

   - `npm test`

   If the full suite is too slow during development, keep rerunning the compile gate plus the directly affected files through the existing test runner workflow, but do not call the work complete without a full green `npm test`.

8. Run the required repo gates for any source-code change:

   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

9. Run the smallest relevant deterministic browser verification first:

   - `npx playwright test tools/playwright/test/trade-regressions.spec.mjs -g "wallet connect and enable trading stays deterministic|order submit and cancel gating uses simulator-backed assertions"`

   After the existing regression stays green, add mode-specific coverage for:

   - remembered local session with passkey toggle off returns to `ready` after reload
   - remembered local session with passkey toggle on returns to `locked` after reload and requires unlock
   - settings toggle copy and confirmation flow match the Session section requirements

10. After the focused browser path passes, run the broader smoke if the flow touches shared trade-route behavior:

   - `npm run test:playwright:smoke`

11. If Browser MCP or browser-inspection is used for UI confirmation or parity checks, close every created session before finishing:

   - `npm run browser:cleanup`

## Validation and Acceptance

Acceptance is complete only when all of the following are true.

Trading Settings `Session` contains two coordinated controls. `Remember session` keeps its current responsibility for choosing session-only versus device-persistent behavior, and a second toggle row under the same section controls whether device-persistent mode uses `:plain` raw `localStorage` persistence or `:passkey` locked persistence. That second row must be hidden or disabled when `Remember session` is off.

When the passkey toggle is off and `Remember session` is on, Hyperopen preserves the current local behavior. Inspect `localStorage` and confirm the agent private key is still stored there in plaintext, reconnecting or reloading returns immediately to `:ready`, and the copy clearly states that the passkey lock is disabled.

When the passkey toggle is on and `Remember session` is on, Hyperopen uses the secure path. Inspect `localStorage`, `sessionStorage`, and the new IndexedDB store and confirm that remembered local persistence contains metadata plus ciphertext only, never a raw agent private key. Reconnecting or reloading must return the agent to `:locked` until the user explicitly unlocks.

Unlock is one per browser session in passkey-enabled local mode, not one per order. After a successful unlock, order submission, cancel, and vault transfer still sign locally and do not prompt the wallet again until the app is locked, reloaded, disconnected, or restarted.

The trading sign path branches correctly by protection mode. In passkey mode, `/hyperopen/src/hyperopen/api/trading.cljs` must reject missing unlocked sessions without reading a raw private key from persisted storage. In plain local and session modes, the current raw-key path remains functional. Nonce cursor persistence must still survive the selected mode without corrupting the signer state.

Invalid or pruned agent recovery still behaves safely in every mode. When Hyperliquid confirms the API wallet is gone, Hyperopen clears the active persisted record, clears any in-memory handle, returns to an error or not-ready state, and the next enable flow mints a brand-new agent address with the same stable device label.

Supported trading actions include delay protection. Orders, cancels, and vault transfers sent on the agent-signing path carry `expiresAfter`, and the app keeps `scheduleCancel` armed while the signer is `:ready`. Losing the unlocked session in passkey mode must stop refreshes so the dead man’s switch can trip naturally.

The existing regression surfaces stay green. The required gates are `npm run check`, `npm test`, and `npm run test:websocket`. Because this work touches `/hyperopen/src/hyperopen/views/**`, finish with the focused Playwright verification and broaden only after that passes.

## Idempotence and Recovery

This plan is intentionally additive and safe to retry. The new lockbox code should land alongside the old raw-key restore path instead of deleting it, because the plain local mode now intentionally preserves that path. If a refactor breaks passkey unlock or locked restore, the safe fallback is not to auto-flip the user into plain local mode; it is to leave the selected mode intact, fail closed to `:not-ready` or `:locked`, and require explicit enable or unlock.

Migration is now mode-specific instead of a universal purge. Existing raw-key records may be adopted into the explicit `:plain` protection-mode model so the toggle-off behavior stays compatible. When a user switches from `:plain` to `:passkey`, that is the point where the implementation should rewrap or replace the remembered session and remove the old raw local record. The retry path after a failed conversion is either unlock from the new lockbox if one exists or enable trading again to mint a new agent.

Do not reuse old agent addresses after invalidation or migration. If any step fails after `approveAgent` but before the lockbox is persisted, discard that generated keypair and restart the enable flow with a new one. Hyperliquid explicitly warns that reused agent addresses can replay once nonce history is pruned.

## Artifacts and Notes

Relevant current-code evidence:

  /hyperopen/src/hyperopen/wallet/agent_session.cljs
    - `storage-by-mode` selects `localStorage` or `sessionStorage`
    - `sanitize-agent-session` requires `:private-key`
    - `persist-agent-session!` JSON-serializes the raw key

  /hyperopen/src/hyperopen/wallet/agent_runtime.cljs
    - `enable-agent-trading!` always calls `persist-agent-session-by-mode!`

  /hyperopen/src/hyperopen/wallet/core.cljs
    - `persisted-session->agent-state` marks restored sessions `:ready`

  /hyperopen/src/hyperopen/api/trading.cljs
    - `resolve-agent-session` loads persisted session data
    - `sign-agent-action!` reads `(:private-key session)`

  /hyperopen/src/hyperopen/platform/indexed_db.cljs
    - existing async IndexedDB boundary available for a durable lockbox store

Hyperliquid facts this plan assumes and depends on:

  - nonces are tracked per signer, so separate API wallets should be used per trading process or frontend session
  - matching named `approveAgent` deregisters the old named API wallet
  - old agent addresses must not be reused after deregistration because previous actions can replay once nonce history is pruned
  - `expiresAfter` is supported on trading actions
  - `scheduleCancel` sets a future cancel-all and can be refreshed while the session is healthy

Plan revision note: 2026-04-08 12:58Z / Codex. Initial active ExecPlan drafted from the `hyperopen-k63w` audit. The first version assumed passkey-locked local persistence would replace the current local raw-key path.

Plan revision note: 2026-04-08 13:29Z / Codex. Revised the active ExecPlan to incorporate the user-requested Session-section toggle. The plan now models two local persistence modes: `:plain` retains the current unencrypted `localStorage` behavior, and `:passkey` uses the new locked-until-unlock flow.

## Interfaces and Dependencies

The existing wallet agent lifecycle should end this work with these observable state values:

- `:not-ready` means no remembered agent exists for the connected wallet.
- `:approving` means Hyperopen is creating a new agent and waiting on `approveAgent`.
- `:locked` means remembered metadata exists but no in-memory signer handle is installed.
- `:unlocking` means the browser is prompting for passkey or authenticator approval to decrypt the lockbox.
- `:ready` means an in-memory signer handle is installed and trading actions may sign locally.
- `:error` means the last enable or unlock attempt failed and the error copy is user-visible.

Introduce a new durable store name in `/hyperopen/src/hyperopen/platform/indexed_db.cljs`:

- `agent-locked-session-store`

Introduce a second persisted local-protection preference, distinct from `:storage-mode`, with stable values:

- `:plain`
- `:passkey`

In `/hyperopen/src/hyperopen/platform/webauthn.cljs`, define stable helpers for:

- capability detection for safe local unlock support
- passkey or authenticator registration for a device label
- passkey or authenticator assertion for unlock
- extraction of the deterministic key-derivation output used to unwrap the lockbox

In `/hyperopen/src/hyperopen/wallet/agent_lockbox.cljs`, define stable helpers for:

- creating and clearing the in-memory signer-handle registry keyed by owner address
- encrypting a raw secp256k1 private key into a versioned lockbox record
- decrypting a lockbox record after the authenticator proves user presence
- loading and clearing the persistent lockbox by storage mode

In `/hyperopen/src/hyperopen/wallet/agent_session.cljs`, keep the public storage-mode and default-state helpers, but change the persistence contract so:

- plain local mode keeps the current raw `localStorage` session shape
- passkey local mode uses synchronous metadata plus IndexedDB ciphertext
- session mode keeps the current session-scoped raw session behavior unless a later ticket changes it
- a migration helper can adopt legacy local records into the explicit `:plain` mode and can remove them when converting to `:passkey`
- a stable per-device agent label is persisted as non-secret preference data
- the passkey-toggle preference is persisted as a non-secret setting used only when `:storage-mode` is `:local`

In `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`, add or update runtime entrypoints so the final interface includes:

- `enable-agent-trading!`
- `unlock-agent-trading!`
- `lock-agent-trading!`
- `set-agent-storage-mode!`

`enable-agent-trading!` must accept enough dependencies to create a keypair, optionally create the lockbox, approve the named agent, persist the encrypted record, and install the in-memory handle. `unlock-agent-trading!` must accept enough dependencies to load the lockbox, invoke the WebAuthn unlock path, decrypt the signer, and set `:wallet :agent :status` to `:ready`. `lock-agent-trading!` must clear the in-memory signer and leave the persisted lockbox intact when appropriate.
`enable-agent-trading!` must also accept enough dependencies to choose between `:session`, `:local/:plain`, and `:local/:passkey` before persistence begins.

In `/hyperopen/src/hyperopen/api/trading.cljs`, the agent-signing path must end with these stable properties:

- a resolver that returns either the current raw session data for plain modes or an unlocked signer handle for passkey mode
- `sign-agent-action!` that signs from the correct owner for the selected protection mode
- `submit-order!`, `cancel-order!`, and `submit-vault-transfer!` that reject `:locked` or missing sessions deterministically in passkey mode
- a `scheduleCancel` wrapper on the same agent-signing path
- `expiresAfter` defaulting via one repo-owned configuration value

The implementation must continue to use the existing signing primitives in:

- `/hyperopen/src/hyperopen/utils/hl_signing.cljs`
- `/hyperopen/src/hyperopen/trading_crypto/module.cljs`
- `/hyperopen/src/hyperopen/trading_crypto_modules.cljs`

Do not move protocol serialization or signature-shape translation into UI code. The Anti-Corruption Layer remains `/hyperopen/src/hyperopen/api/trading.cljs` plus the signing utilities. The new secure-storage work changes where the raw key may exist, not how Hyperliquid signatures are computed.
