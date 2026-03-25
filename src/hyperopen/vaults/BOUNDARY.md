# Vaults Boundary

## Owns

- Vault list and detail route loading, vault list UI state, detail interaction state, transfer modal state and commands, vault performance and benchmark shaping, and vault cache or persistence seams.
- Pure vault identity and transfer policy under `domain/`.
- Load and interaction orchestration under `application/`.
- Route parsing, preview caching, list caching, and persistence under `infrastructure/`.

## Stable Public Seams

- `hyperopen.vaults.actions`
  The stable action facade for vault list/detail interactions, route loads, and transfer commands.
- `hyperopen.vaults.effects`
  The stable effect seam for vault index, summaries, details, transfer submission, and related fetch flows.
- `hyperopen.vaults.infrastructure.routes`
  The canonical route parsing seam for `/vaults` list and detail paths.
- `hyperopen.vaults.application.list-vm`
  The shared non-view owner for vault list model shaping.
- `hyperopen.vaults.detail.metrics-bridge`
  The vault-detail seam that coordinates portfolio metrics usage for vault detail surfaces.

## Dependency Rules

- Allowed:
  `vaults.domain.*` stays pure and owns normalization, identity, and transfer policy only.
- Allowed:
  `vaults.application.*` may depend on vault domain helpers plus injected persistence or route collaborators.
- Allowed:
  `vaults.infrastructure.*` may depend on browser storage, routes, and API/cache seams.
- Allowed:
  Views and runtime wiring should depend on `hyperopen.vaults.actions`, `hyperopen.vaults.effects`, or the explicit infrastructure route seam rather than on view-owned helpers.
- Forbidden:
  Do not move route parsing or storage restore back into `hyperopen.vaults.actions`.
- Forbidden:
  Do not import `hyperopen.views.vaults.*` into non-view vault namespaces.
- Forbidden:
  Do not put transfer-modal defaults or other UI state back under `vaults.domain.*` unless it is truly pure shared policy.

## Key Tests

- Key namespaces:
  `hyperopen.vaults.actions-test`,
  `hyperopen.vaults.effects-test`,
  `hyperopen.vaults.application.list-commands-test`,
  `hyperopen.vaults.application.list-vm-test`,
  `hyperopen.vaults.application.route-loading-test`,
  `hyperopen.vaults.application.transfer-commands-test`,
  `hyperopen.vaults.application.transfer-state-test`,
  `hyperopen.vaults.application.ui-state-test`,
  `hyperopen.vaults.infrastructure.routes-test`,
  `hyperopen.vaults.infrastructure.preview-cache-test`,
  `hyperopen.vaults.detail.activity-test`,
  `hyperopen.vaults.detail.metrics-bridge-test`
- Final repo gates:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New list filter, page, or sort interaction:
  `hyperopen.vaults.application.list-commands` or `hyperopen.vaults.application.ui-state`
- New detail tab, chart, activity, or benchmark interaction:
  `hyperopen.vaults.application.detail-commands`
- New transfer preview rule or modal default:
  `hyperopen.vaults.domain.transfer-policy` or `hyperopen.vaults.application.transfer-state`
- New vault route parsing or persisted cache behavior:
  `hyperopen.vaults.infrastructure.*`
- New stable caller-facing entrypoint:
  keep `hyperopen.vaults.actions` or `hyperopen.vaults.effects` as the compatibility facade
