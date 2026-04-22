# Startup Boundary

## Owns

- Startup lifecycle sequencing, startup state reset, critical and deferred UI restore, system initialization, remote stream initialization, post-render/deferred bootstrap scheduling, address-handler lifecycle, account bootstrap triggers, persisted preference restore, route-aware refresh effects, store/websocket watcher installation, and startup dependency assembly.
- Entrypoint-facing startup facade behavior in `hyperopen.app.startup`.
- Runtime bootstrap and watcher installation coordination with `hyperopen.runtime.bootstrap`.

## Stable Public Seams

- `hyperopen.startup.init`
  The startup lifecycle entrypoint for resetting startup state, restoring critical UI state, restoring deferred UI state, initializing systems, and `init!`.
- `hyperopen.startup.runtime`
  The lifecycle runtime seam for performance marks, idle scheduling, address handlers, account bootstrap triggers, critical bootstrap, deferred bootstrap, and remote data stream initialization.
- `hyperopen.startup.restore`
  The persisted preference restore seam for agent storage/protection mode, passkey capability, locale, trading settings, spectate preferences, trade route tab, and active asset.
- `hyperopen.startup.route-refresh`
  The route-aware refresh-effect selection seam.
- `hyperopen.startup.watchers`
  The agent-safety, store-cache, and websocket watcher installation seam.
- `hyperopen.startup.collaborators`
  The startup dependency assembly seam for browser, runtime, route, wallet, websocket, account, API, funding, and persistence collaborators.
- `hyperopen.app.startup`
  The top-level facade used by app bootstrap and hot-reload paths.

## Dependency Rules

- Allowed:
  Startup may depend on runtime bootstrap, app startup facade, account surface service, wallet/address watchers, websocket client, route module loading, browser persistence, and feature-owned public seams needed for startup orchestration.
- Allowed:
  `startup.collaborators` may assemble dependencies from many contexts, but should not own the policy those contexts expose.
- Allowed:
  `startup.runtime` may own trigger timing for account bootstrap while account-surface policy and refresh orchestration remain in the account boundary.
- Forbidden:
  Do not add delegation-only startup wrapper namespaces between `hyperopen.app.startup` and startup behavior owners.
- Forbidden:
  Do not put new feature business rules in startup when a feature context owns the rule.
- Forbidden:
  Do not duplicate account-surface bootstrap, stream coverage, or REST fallback policy outside `hyperopen.account.surface-service` and `hyperopen.account.surface-policy`.
- Forbidden:
  Do not make startup restore write large or asynchronous browser persistence paths; follow `docs/BROWSER_STORAGE.md`.

## Key Tests

- Startup lifecycle and facade:
  `hyperopen.app.startup-test`,
  `hyperopen.startup.init-test`,
  `hyperopen.startup.runtime-test`,
  `hyperopen.core-bootstrap.runtime-startup-test`
- Restore, watchers, and route refresh:
  `hyperopen.startup.restore-test`,
  `hyperopen.startup.watchers-test`,
  `hyperopen.startup.route-refresh-test`,
  `hyperopen.startup.route-aware-bootstrap-test`,
  `hyperopen.startup.collaborators-test`
- Cross-boundary startup/account coverage:
  `hyperopen.startup.account-lifecycle-test`,
  `hyperopen.runtime.bootstrap-test`,
  `hyperopen.core-bootstrap.asset-cache-persistence-test`,
  `hyperopen.views.trade-view.startup-defer-test`,
  `hyperopen.views.vaults.startup-preview-test`
- Final repo gates:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New startup sequencing, idempotence, critical restore, deferred restore, or system initialization behavior:
  `hyperopen.startup.init`
- New post-render scheduling, address-handler lifecycle, account bootstrap trigger, deferred bootstrap, or remote stream initialization:
  `hyperopen.startup.runtime`
- New persisted startup preference restore:
  `hyperopen.startup.restore`
- New route-aware refresh behavior:
  `hyperopen.startup.route-refresh`
- New store-cache, websocket, or agent-safety watcher installation:
  `hyperopen.startup.watchers`
- New startup dependency or injected collaborator:
  `hyperopen.startup.collaborators`
- New app-facing startup facade behavior:
  `hyperopen.app.startup`
- New account-surface bootstrap policy or fallback refresh orchestration:
  `hyperopen.account.surface-service` or `hyperopen.account.surface-policy`, with startup only triggering it
