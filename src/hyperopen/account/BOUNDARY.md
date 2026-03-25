# Account Boundary

## Owns

- Canonical effective-account identity, read-only mutation rules, spectate-mode and watchlist state, account-surface bootstrap and refresh orchestration, and account-history workflows such as orders, funding, margin, reduce, TWAP, and TP/SL.

## Stable Public Seams

- `hyperopen.account.context`
  The canonical read-side identity seam for `effective-account-address`, `mutations-allowed?`, watchlist normalization, and storage keys.
- `hyperopen.account.surface-service`
  The bootstrap and post-event refresh seam for account surfaces.
- `hyperopen.account.surface-policy`
  The seam for websocket/topic readiness and account-surface liveness rules.
- `hyperopen.account.spectate-mode-actions`
  The stable action seam for spectate modal and watchlist behavior.
- `hyperopen.account.history.actions` and `hyperopen.account.history.effects`
  The stable action/effect seams for account-history workflows.
- `hyperopen.account.history.position-tpsl`
  The compatibility facade over TP/SL state, policy, transitions, and application seams.

## Dependency Rules

- Allowed:
  Read-side account consumers should use `hyperopen.account.context` instead of reading `[:wallet :address]` directly.
- Allowed:
  `surface-service` and `surface-policy` may depend on websocket-health, platform, and runtime bootstrap collaborators.
- Allowed:
  Internal account-history facades may depend on their split owner modules under `account.history.*`.
- Forbidden:
  Do not key read-only or inspected-account behavior directly off `[:wallet :address]` when `effective-account-address` is the source of truth.
- Forbidden:
  Do not scatter account bootstrap or fallback refresh logic outside `hyperopen.account.surface-service`.
- Forbidden:
  Do not reintroduce view-owned identity helpers where `account.history.*` already owns the policy.
- Forbidden:
  Do not hide mutation blocking only in views; keep canonical rules in `account.context`.

## Key Tests

- Key namespaces:
  `hyperopen.account.context-test`,
  `hyperopen.account.surface-policy-test`,
  `hyperopen.account.surface-service-test`,
  `hyperopen.account.spectate-mode-actions-test`,
  `hyperopen.account.spectate-mode-links-test`,
  `hyperopen.account.history.actions-test`,
  `hyperopen.account.history.effects-test`,
  `hyperopen.account.history.position-margin-test`,
  `hyperopen.account.history.position-tpsl-test`
- Cross-boundary websocket coverage:
  `hyperopen.websocket.account-surface-service-coverage-test`,
  `hyperopen.websocket.user-runtime.refresh-test`,
  `hyperopen.websocket.user-runtime.subscriptions-test`
- Final repo gates:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New inspected-account routing, watchlist normalization, or read-only rule:
  `hyperopen.account.context`
- New startup backfill or post-event refresh orchestration:
  `hyperopen.account.surface-service`
- New websocket topic readiness rule:
  `hyperopen.account.surface-policy`
- New spectate modal or watchlist interaction:
  `hyperopen.account.spectate-mode-actions`
- New funding, order, or position history workflow:
  the owning `hyperopen.account.history.*` namespace, keeping `position-tpsl` as a compatibility facade when callers already depend on it
