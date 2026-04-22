# Wallet Boundary

## Owns

- Browser wallet provider detection, connection lifecycle, wallet owner address, EIP-1102 account/chain state, wallet actions, agent trading enable/unlock/lock runtime, agent-session persistence, passkey lockbox behavior, signer installation, copy feedback, wallet address watchers, and agent safety timers.
- The connected wallet owner address as signing authority. Read-side effective account or spectate identity policy belongs to the account boundary.

## Stable Public Seams

- `hyperopen.wallet.core`
  The browser/provider-facing entrypoint for provider overrides, provider detection, connection requests, listener installation, wallet state updates, and simple wallet UI helpers.
- `hyperopen.wallet.actions`
  The action-to-effect seam for connect/disconnect, agent trading enable/unlock, storage/protection mode changes, copy address, and recovery-modal behavior.
- `hyperopen.wallet.agent-runtime`
  The stable facade over agent trading runtime phases for storage mode, protection mode, approval, enable, unlock, lock, and error normalization.
- `hyperopen.wallet.agent-session`
  The persistence and normalization seam for storage mode, local protection mode, wallet addresses, device labels, approve-agent actions, session round trips, and passkey metadata.
- `hyperopen.wallet.agent-lockbox`
  The passkey lockbox, signer cache, encrypted/locked session, unlock, delete, and support-detection seam.
- `hyperopen.wallet.address-watcher`
  The address-change watcher adapter for registering handlers, syncing current address, and integrating webdata2 follow-up behavior.
- `hyperopen.wallet.connection-runtime`
  The runtime adapter for wallet connect/disconnect and post-connect agent trading decisions.
- `hyperopen.wallet.copy-feedback-runtime`
  The runtime adapter for copy-to-clipboard feedback timers and state.
- `hyperopen.wallet.agent-safety`
  The agent-expiry and safety watcher seam.

## Dependency Rules

- Allowed:
  Wallet runtime code may depend on browser provider APIs, storage APIs, crypto/passkey helpers, runtime state, API trading seams, and account context as a consumer.
- Allowed:
  Wallet actions may emit runtime effects, but should keep effect ordering explicit and covered by runtime effect-order tests when changed.
- Forbidden:
  Do not treat `[:wallet :address]` as read-side inspected-account authority; use `hyperopen.account.context` for effective account and spectate-mode behavior.
- Forbidden:
  Do not move passkey lockbox cryptography or signer-cache behavior into `agent-runtime` phase orchestration.
- Forbidden:
  Do not duplicate agent-session storage keys or address normalization outside `hyperopen.wallet.agent-session`.
- Forbidden:
  Do not add namespace-local timer or watcher state outside wallet/runtime owners when runtime state or watcher seams already own the lifecycle.

## Key Tests

- Wallet provider and action behavior:
  `hyperopen.wallet.core-test`,
  `hyperopen.wallet.actions-test`,
  `hyperopen.wallet.connection-runtime-test`
- Agent session and passkey behavior:
  `hyperopen.wallet.agent-session-test`,
  `hyperopen.wallet.agent-lockbox-test`,
  `hyperopen.wallet.agent-runtime-test`,
  `hyperopen.wallet.agent-runtime-edge-test`,
  `hyperopen.wallet.agent-runtime-concurrency-test`
- Watchers and feedback:
  `hyperopen.wallet.address-watcher-test`,
  `hyperopen.wallet.address-watcher-lifecycle-test`,
  `hyperopen.wallet.copy-feedback-runtime-test`
- Cross-boundary coverage:
  `hyperopen.runtime.action-adapters.wallet-test`,
  `hyperopen.runtime.effect-adapters.wallet-test`,
  `hyperopen.core-bootstrap.wallet-actions-effects-test`,
  `hyperopen.core-bootstrap.agent-trading-lifecycle-test`,
  `hyperopen.websocket.agent-session-coverage-test`
- Final repo gates:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New provider detection, connection request, account change, or wallet listener behavior:
  `hyperopen.wallet.core`
- New wallet action or effect-list behavior:
  `hyperopen.wallet.actions`
- New agent-session storage format, storage key, passkey metadata, device label, or address normalization:
  `hyperopen.wallet.agent-session`
- New agent credential generation or agent address derivation:
  `hyperopen.wallet.agent-session-crypto`
- New passkey lockbox, signer cache, encrypted session, or unlock behavior:
  `hyperopen.wallet.agent-lockbox`
- New agent trading enable, approval, unlock, storage mode, protection mode, or state projection behavior:
  the focused `hyperopen.wallet.agent-runtime.*` owner and the `hyperopen.wallet.agent-runtime` facade
- New wallet address watcher behavior:
  `hyperopen.wallet.address-watcher`
- New wallet copy feedback behavior:
  `hyperopen.wallet.copy-feedback-runtime`
- New read-only effective account, spectate, or watchlist policy:
  `hyperopen.account.context`, not wallet
