# Passkey Protection by Default for New Trading Keys, and Lock After Inactivity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Hyperopen signs orders with an agent key (a Hyperliquid "API wallet" private key that the browser holds). By default "Remember session" is on and the local protection mode is plain, so the key is stored unencrypted in `localStorage` (`hyperopen:agent-session:v1:<address>`). Anything that can read the browser profile or run script on the origin can take it and sign orders. Passkey protection (`hyperopen.wallet.agent-lockbox`, WebAuthn PRF) exists but is an opt-in switch in Trading Settings. Even with it on, the decrypted key stays in memory after one unlock until the page is reloaded, so an unattended tab can keep signing.

After this change:

1. A new trading key is protected with a passkey by default wherever the browser supports passkeys. Keys that are already stored keep the protection they were stored with, an explicit choice in Trading Settings always wins, and browsers without passkey support or session-only storage behave as before.
2. A passkey-unlocked session is locked again after 30 minutes without mouse, keyboard, wheel or touch input. The next signed action asks for the passkey through the existing unlock flow. Open orders on Hyperliquid are unaffected.

There is no new setting and no new UI.

To see it working: in a fresh browser profile on a passkey-capable browser, open Trading Settings: "Lock trading with passkey" is on. Enable trading: after the wallet approval, the browser asks to create a passkey. Leave the tab without input for 30 minutes, and the wallet menu shows "Unlock Trading".

## Context References

Durable context: direct maintainer request captured in this plan. No GitHub issue exists.

Repo artifacts:

- `/hyperopen/src/hyperopen/startup/restore.cljs` `restore-agent-storage-mode!` sets the protection mode in the store from the stored preference, which the wallet layer then uses to load the stored key and enable trading.
- `/hyperopen/src/hyperopen/wallet/agent_session.cljs` holds the preference keys and the stored-session keys.
- `/hyperopen/src/hyperopen/wallet/agent_runtime/enable.cljs` stores a new key plain or behind a passkey, depending on that mode.
- `/hyperopen/src/hyperopen/wallet/agent_runtime/unlock.cljs` defines `lock-agent-trading!`, which had no caller before this change.

## Progress

- [x] (2026-11-17) Recorded a clean baseline on unmodified upstream: `npm test` ran 8568 tests, 0 failures.
- [x] (2026-11-17) Added `hyperopen.wallet.idle-lock` (pure `passkey-session-unlocked?`, `should-lock?`; watcher `check!`, `note-activity!`, `install!`), installed from `hyperopen.app.startup`.
- [x] (2026-11-17) Added `agent-session/default-local-protection-mode` (pure) and `agent-session/stored-local-agent-sessions` (reads `localStorage` keys), and used them as the missing-preference default in `restore-agent-storage-mode!`.
- [x] (2026-11-17) A failed passkey setup during enable now says how to go on: try again, or turn passkey protection off in Trading Settings. The passkey toggle tooltip mentions the 30-minute lock.
- [x] (2026-11-17) `npm test` 8577 tests, 0 failures; `npm run test:websocket` 632 tests, 0 failures; `npm run check` pass.
- [ ] Maintainer review of the new default.

## Surprises & Discoveries

- Observation: `lock-agent-trading!` was already implemented and tested but had no caller.
  Evidence: `rg "lock-agent-trading!" src` only finds the definition in `unlock.cljs` and its re-export in `agent_runtime.cljs`.

- Observation: the stored key is loaded according to the global protection mode (`load-persisted-agent-session-snapshot`), not according to what is stored. Changing the missing-preference default alone would hide existing plain keys, so the default looks at what is stored first.

- Observation: `passkey-lock-supported?` only checks for WebAuthn and WebCrypto. Whether the authenticator supports PRF is only known when the passkey is created, after the wallet approved the key. If that fails, the key is not stored and the user sees the error with the hint above.

- Observation: `npx shadow-cljs release app` fails on unmodified `main` with 5 Closure errors (`"eval" cannot be redeclared in strict mode`) in `portfolio/vault_attribution/series.cljs`. Unrelated to this change.

## Decision Log

- Decision: Decide the default when the protection preference was never stored, from what this browser already holds: a stored plain key keeps plain, a stored passkey key keeps passkey, otherwise passkey where supported, else plain. The default is not written as a preference.
  Rationale: Existing users keep working without enabling trading again. Not storing the default keeps "the user chose" and "the app chose" apart, so a later change of the default still reaches people who never chose.
  Date/Author: 2026-11-17 / contributor.

- Decision: Lock only passkey-protected sessions.
  Rationale: A plain remembered session restores its key from storage on reload without any user gesture. Locking it would only annoy the user.
  Date/Author: 2026-11-17 / contributor.

- Decision: A fixed 30-minute limit, no setting and no manual lock button.
  Rationale: 30 minutes matches common banking and exchange session timeouts and keeps the interface unchanged.
  Date/Author: 2026-11-17 / contributor.

- Decision: Count `pointerdown`, `keydown`, `wheel` and `touchstart` on `window` in the capture phase as activity, checking before counting each event; poll every 30 seconds and on `visibilitychange`.
  Rationale: The first click after a long absence locks instead of extending the session; background tabs throttle timers. Mouse movement alone does not keep a key unlocked.
  Date/Author: 2026-11-17 / contributor.

## Outcomes & Retrospective

Pending maintainer review. New users get an encrypted key and a bounded unlocked window by default; existing users and explicit choices are unchanged. The cost is one passkey prompt when enabling trading and one per page load (or after 30 idle minutes) before the first signed action.

## Plan of Work

`agent-session/default-local-protection-mode` takes `{:passkey-supported? :plain-session-stored? :passkey-session-stored?}` and returns `:plain` or `:passkey`. `agent-session/stored-local-agent-sessions` scans `localStorage` keys for the plain-session and passkey-metadata prefixes. `restore-agent-storage-mode!` passes the result as the missing default to `load-local-protection-mode-preference`. The enable flow already stores a key behind a passkey when the mode is `:passkey`; its passkey-creation failure now carries a hint.

`hyperopen.wallet.idle-lock` keeps the last activity time in an atom; `check!` calls `agent-runtime/lock-agent-trading!` with `agent-lockbox/clear-unlocked-session!` once `should-lock?` holds.

## Concrete Steps

From the repository root:

    npm run setup:worktree
    npm run check
    npm test
    npm run test:websocket

## Validation and Acceptance

Unit tests cover: the default for a new key with and without passkey support, an explicit plain preference, an existing plain key with no preference, an existing passkey key; finding stored keys in `localStorage`; the passkey setup failure message; the idle lock for every agent status and protection mode, the 30-minute boundary, activity resetting the clock, and the real lock path.

## Idempotence and Recovery

Nothing new is persisted. `install!` guards with an atom. Reverting restores the plain default. A key stored behind a passkey in the meantime is then not loaded (the mode is plain again), so those users enable trading again; nothing else is lost.

## Interfaces and Dependencies

No new dependencies. New public vars: `hyperopen.wallet.agent-session/default-local-protection-mode`, `stored-local-agent-sessions`; `hyperopen.wallet.agent-runtime.enable/passkey-setup-failed-hint`; `hyperopen.wallet.idle-lock/idle-limit-ms`, `passkey-session-unlocked?`, `should-lock?`, `check!`, `note-activity!`, `install!`.
