# Fix Passkey Toggle Regression That Strands Live Trading Sessions

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-zzz1`, and that `bd` issue remains the lifecycle source of truth while this file is active.

## Purpose / Big Picture

After this change, a user who already has trading enabled should be able to turn `Lock trading with passkey` on without getting stranded behind an `Enable Trading Again` modal on the next order submit. The user-visible outcome is straightforward: with a ready trading session, toggling passkey protection should preserve a usable trading session immediately, and the next order should submit normally without forcing a wallet re-approval. The only time the recovery modal should appear is when Hyperliquid no longer recognizes the agent or the app has genuinely lost the trading setup, not when the user simply changed local session protection.

The way to see this working after implementation is to connect a wallet, enable trading, turn on `Remember session`, turn on `Lock trading with passkey`, and then submit an order in the browser simulator. The order path should remain usable. Reloading afterward should still restore the remembered session as `:locked`, requiring one passkey unlock per browser session, but the act of flipping the toggle while the live session is already ready should not destroy the current signer.

## Progress

- [x] (2026-04-08 22:49Z) Reproduced the regression from the current code path by auditing the passkey toggle flow, order submit flow, and recovery modal wiring in `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`, `/hyperopen/src/hyperopen/api/trading.cljs`, `/hyperopen/src/hyperopen/order/actions.cljs`, `/hyperopen/src/hyperopen/order/effects.cljs`, and `/hyperopen/src/hyperopen/views/agent_trading_recovery_modal.cljs`.
- [x] (2026-04-08 22:49Z) Filed and claimed `hyperopen-zzz1` in `bd` as the tracked bug for this regression.
- [x] (2026-04-09 01:49Z) Added focused regression coverage in `/hyperopen/test/hyperopen/wallet/agent_runtime_test.cljs`, `/hyperopen/test/hyperopen/header/actions_test.cljs`, `/hyperopen/test/hyperopen/views/header/vm_test.cljs`, `/hyperopen/test/hyperopen/views/header_settings_view_test.cljs`, and `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` for live migration, locked downgrade blocking, and the browser submit path after toggling passkey on.
- [x] (2026-04-09 01:49Z) Replaced destructive local-protection-mode switching with an in-place session migration flow for live ready sessions in `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs` and `/hyperopen/src/hyperopen/runtime/effect_adapters/wallet.cljs`.
- [x] (2026-04-09 01:49Z) Tightened settings behavior so locked remembered passkey sessions block downgrade in `/hyperopen/src/hyperopen/views/header/vm.cljs` and `/hyperopen/src/hyperopen/header/actions.cljs` instead of silently dropping into a broken non-ready state.
- [x] (2026-04-09 01:49Z) Validated the fix with `npm test`, `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "passkey|session toggles"`, `npm run test:websocket`, `npm run check`, and `npm run browser:cleanup`.

## Surprises & Discoveries

- Observation: the current `Lock trading with passkey` toggle is not a migration; it is a destructive reset.
  Evidence: `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs` implements `set-agent-local-protection-mode!` by clearing the persisted session for the current mode, clearing the persisted session for the next mode, clearing the unlocked signer cache, persisting the new preference, and then calling `reset-agent-state!`. `reset-agent-state!` writes `default-agent-state`, whose status is `:not-ready`.

- Observation: the order submit path treats any non-`locked`, non-`unlocking`, non-`ready` agent status as a recovery problem, not a passkey-protection transition.
  Evidence: `/hyperopen/src/hyperopen/order/actions.cljs` and `/hyperopen/src/hyperopen/order/effects.cljs` both route non-ready submit attempts to `open-enable-trading-recovery` unless the agent status is explicitly `:locked` or `:unlocking`.

- Observation: the recovery modal copy is specifically about Hyperliquid no longer recognizing the agent, but the passkey-toggle regression triggers it without any exchange round-trip.
  Evidence: `/hyperopen/src/hyperopen/views/agent_trading_recovery_modal.cljs` shows the idle description `Hyperliquid no longer recognizes this trading setup...`, while the mode-switch regression happens entirely from the local toggle handler before `submit-order!` ever gets a signed exchange response.

- Observation: the bug became much easier to hit after the recent settings UX simplification removed the confirmation step that used to warn the user that changing protection mode would require re-enabling trading.
  Evidence: `request-agent-local-protection-mode-change` now dispatches `:effects/set-agent-local-protection-mode` directly from `/hyperopen/src/hyperopen/header/actions.cljs`, while the destructive reset behavior in `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs` was left unchanged.

- Observation: a deterministic browser regression for the live toggle path does not need real WebAuthn if the lockbox boundary is mocked below the settings flow.
  Evidence: `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` now patches `hyperopen.wallet.agent_lockbox/create-locked-session!` inside the browser context, which lets the test assert the real settings toggle and next-submit behavior without relying on platform passkey UI in CI.

## Decision Log

- Decision: fix the regression by migrating live `:ready` sessions across protection modes in place instead of resetting agent state to `:not-ready`.
  Rationale: the user explicitly wants the toggle itself to be the action. If the app already holds a valid live signer, changing how that signer is persisted locally should not force another `approveAgent` flow. Destroying the live signer makes the toggle feel broken and semantically misclassifies a local protection change as agent recovery.
  Date/Author: 2026-04-08 / Codex

- Decision: keep the recovery modal reserved for true invalid-agent or missing-agent conditions reported by the exchange, not for protection-mode transitions.
  Rationale: the current modal copy is specific and high-severity. Showing it after a local settings change teaches the wrong mental model and hides the actual state transition that occurred.
  Date/Author: 2026-04-08 / Codex

- Decision: if the user tries to switch from passkey mode back to plain local mode while the session is `:locked` and there is no decrypted signer in memory, require an unlock before allowing that downgrade.
  Rationale: a plain local session requires the raw private key to be persisted again. When the app is locked, it does not have that key in memory by design. The safe and honest behavior is to block the downgrade until the user unlocks, not silently reset state or synthesize a broken plain session.
  Date/Author: 2026-04-08 / Codex

## Outcomes & Retrospective

Implemented under `hyperopen-zzz1`. The shipped behavior now migrates live `:ready` sessions in place when the user turns passkey lock on or off, preserves immediate submit usability after `plain -> passkey`, and blocks `passkey -> plain` while the session is locked and no decrypted signer is present. The recovery modal remains reserved for true invalid-agent conditions because the passkey settings path no longer forces the wallet agent through `:not-ready`.

Validation passed with the required repo gates plus focused browser coverage. The one remaining limitation is unchanged from the broader passkey design: if the origin is compromised while the session is already unlocked, the in-memory signer is still usable until the session is locked or the browser exits.

## Context and Orientation

Hyperopen has three trading-session storage shapes today. Session-only mode persists the raw agent key in `sessionStorage`. Remembered plain mode persists the raw agent key in `localStorage`. Remembered passkey mode stores a passkey metadata record in `localStorage`, an encrypted lockbox in IndexedDB, and an unlocked signer only in memory. The relevant runtime owners are:

`/hyperopen/src/hyperopen/wallet/agent_runtime.cljs` owns lifecycle transitions such as changing storage mode, changing local protection mode, enabling trading, unlocking trading, and locking trading.

`/hyperopen/src/hyperopen/api/trading.cljs` owns signer resolution and signed order submission. A “signer” here means the in-memory agent private key material used to sign orders, cancels, and vault transfers.

`/hyperopen/src/hyperopen/order/actions.cljs` and `/hyperopen/src/hyperopen/order/effects.cljs` own the submit-order gating logic. They decide whether submit is allowed, blocked, or routed into the recovery modal.

`/hyperopen/src/hyperopen/views/agent_trading_recovery_modal.cljs` owns the modal that says `Enable Trading Again`. That modal is meant for broken or invalid trading setups, not for normal passkey toggles.

`/hyperopen/src/hyperopen/wallet/agent_lockbox.cljs` owns the encrypted remembered-session store for passkey mode and the in-memory unlocked-session cache.

The current bug happens because changing `local-protection-mode` uses the same destructive reset pattern as changing storage mode. That made sense when the settings UI explicitly warned the user that re-enabling was required. It no longer matches the current UX, which treats the passkey toggle as an immediate preference change. The fix therefore needs to be behavioral, not cosmetic.

## Plan of Work

Milestone 1 adds proof of failure before changing behavior. Extend the wallet runtime and browser tests so they explicitly cover this sequence: start from a live ready remembered plain session, toggle `Lock trading with passkey` on, and attempt to submit an order without refreshing or reconnecting. Today the app will fall back to `:not-ready` and show the recovery modal. The new tests should make that regression visible in `/hyperopen/test/hyperopen/wallet/agent_runtime_test.cljs`, `/hyperopen/test/hyperopen/core_bootstrap/wallet_actions_effects_test.cljs`, and `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`.

Milestone 2 changes the protection-mode switch from reset to migration for live sessions. In `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`, replace the destructive `set-agent-local-protection-mode!` branch with logic that first inspects the current wallet agent state and signer availability. When the agent is `:ready` and an unlocked signer is present, switching `:plain -> :passkey` should create a lockbox from the current session, persist passkey metadata, clear the old raw local session, keep the unlocked signer installed in memory, and leave the wallet agent status `:ready`. Switching `:passkey -> :plain` from a live ready session should persist the current in-memory session back into raw local storage, clear the lockbox plus metadata, and also remain `:ready`. Only non-live transitions should fall back to a reset or block.

Milestone 3 closes the state-gap that currently misroutes submit into recovery. In `/hyperopen/src/hyperopen/order/actions.cljs` and `/hyperopen/src/hyperopen/order/effects.cljs`, keep the existing `:locked` and `:unlocking` behavior, but ensure that a protection-mode switch cannot leave the app in a misleading `:not-ready` state after a successful migration. For the remaining edge case where passkey mode is toggled off while the app is `:locked`, block the toggle in the settings view or return an explicit `Unlock trading before turning off passkey protection.` message rather than letting submit expose the problem later.

Milestone 4 aligns the settings and recovery UI with the new lifecycle contract. In `/hyperopen/src/hyperopen/views/header/vm.cljs` and `/hyperopen/src/hyperopen/views/header/settings.cljs`, keep the immediate toggle interaction but add a deterministic disabled or helper state for impossible downgrades, specifically locked passkey sessions that have no decrypted signer in memory. In `/hyperopen/src/hyperopen/views/agent_trading_recovery_modal.cljs`, keep the current invalid-agent copy, but ensure the modal is opened only from true exchange-side recovery conditions or explicit invalid-session resets, not from local passkey-protection changes.

Milestone 5 verifies the end-to-end outcome. Run the focused browser regression for the Session settings, then the required repo gates. The observable behavior must be: passkey toggling on from a ready live session preserves order submit immediately; reloading still restores passkey mode as `:locked`; unlocking after reload still works; and true invalid-agent scenarios still route through `Enable Trading Again`.

## Concrete Steps

From `/Users/barry/.codex/worktrees/daf8/hyperopen`, use this order:

1. Inspect the current local-protection-mode seams and submit gating:

   `rg -n "set-agent-local-protection-mode|reset-agent-state|recovery-modal-open|agent-not-ready|unlock trading before submitting" src/hyperopen test/hyperopen`

2. Add failing coverage first:

   - update `/hyperopen/test/hyperopen/wallet/agent_runtime_test.cljs`
   - update `/hyperopen/test/hyperopen/core_bootstrap/wallet_actions_effects_test.cljs`
   - update `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`

3. Implement the live-session migration in:

   - `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`
   - `/hyperopen/src/hyperopen/runtime/effect_adapters/wallet.cljs`
   - if needed, `/hyperopen/src/hyperopen/api/trading.cljs`

4. Tighten the submit and recovery routing in:

   - `/hyperopen/src/hyperopen/order/actions.cljs`
   - `/hyperopen/src/hyperopen/order/effects.cljs`
   - `/hyperopen/src/hyperopen/views/agent_trading_recovery_modal.cljs`
   - `/hyperopen/src/hyperopen/views/header/vm.cljs`
   - `/hyperopen/src/hyperopen/views/header/settings.cljs`

5. Validate with the repository’s required commands:

   - `npm test`
   - `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "trading settings session toggles gate passkey lock"`
   - `npm run test:websocket`
   - `npm run check`
   - `npm run browser:cleanup`

Expected proof after the fix:

   A ready trading session remains usable immediately after enabling passkey protection, and the next order submit no longer opens the recovery modal.

## Validation and Acceptance

Acceptance requires all of the following behaviors.

Starting from a live ready remembered plain session, enabling `Lock trading with passkey` must not change `[:wallet :agent :status]` to `:not-ready` or `:error`. The in-memory signer must remain available for the current browser session, and the next order submit must stay on the normal order path.

After that same toggle, browser persistence must change shape correctly. Raw local session storage must be removed, passkey metadata plus lockbox storage must exist, and a page reload must restore the session as `:locked`, not `:ready`.

Starting from a live ready remembered passkey session that has already been unlocked, disabling `Lock trading with passkey` must persist a plain raw local session and keep submit usable without a re-enable.

Starting from a locked remembered passkey session, disabling `Lock trading with passkey` must not silently reset the wallet into a broken non-ready state. The UI must either block the toggle with an explicit unlock-first reason or route through an intentional unlock-first flow.

The `Enable Trading Again` recovery modal must open only when Hyperliquid actually rejects the agent or when the app invalidates the persisted session because the signer is no longer valid. It must not appear as a side effect of a successful local protection-mode switch.

## Idempotence and Recovery

The implementation should be safe to retry. Toggling from plain to passkey should clear stale raw local records only after the new lockbox and metadata are successfully written. If passkey enrollment or lockbox creation fails halfway, the runtime should keep the existing plain session intact and surface an error instead of leaving the app between modes.

For the reverse path, toggling from unlocked passkey to plain should write the raw local session before clearing lockbox storage. If raw-session persistence fails, keep the existing passkey lockbox and unlocked signer intact so the user does not lose the current live session.

## Artifacts and Notes

Root-cause evidence from the current tree:

  In `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`, `set-agent-local-protection-mode!` currently does all of the following when the mode changes:

      (clear-persisted-agent-session!* wallet-address storage-mode current-mode)
      (clear-persisted-agent-session!* wallet-address storage-mode next-mode)
      (clear-unlocked-session! wallet-address)
      (persist-local-protection-mode-preference! next-mode)
      (reset-agent-state! store default-agent-state storage-mode next-mode ...)

  In `/hyperopen/src/hyperopen/order/effects.cljs`, submit routing currently opens recovery for non-ready states unless the state is explicitly `:locked` or `:unlocking`:

      (if (#{:locked :unlocking} agent-status)
        ...
        (swap! store open-enable-trading-recovery message))

This combination is the bug: the toggle resets the agent to `:not-ready`, and submit treats that reset as a recovery failure.

Plan revision note: created on 2026-04-08 because manual browser validation showed the passkey toggle now strands active trading sessions after the recent UX simplification removed the re-enable confirmation while leaving the destructive runtime reset behavior unchanged.
