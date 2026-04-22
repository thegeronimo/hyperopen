# Runtime Boundary

## Owns

- Application runtime wiring, action and effect adapter facades, runtime dependency composition, runtime bootstrap, render-loop installation, store watchers, dev-time validation wrappers, runtime-wide state/configuration, and adapter glue from pure/domain seams to side-effect interpreters.
- Action adapter facade and sub-adapters under `action_adapters/`.
- Effect adapter facade and sub-adapters under `effect_adapters/`.
- Runtime collaborator assembly under `collaborators/`.

## Stable Public Seams

- `hyperopen.runtime.wiring`
  The dependency composition seam for runtime registration.
- `hyperopen.runtime.registry-composition`
  The catalog-driven action/effect handler map seam.
- `hyperopen.runtime.bootstrap`
  The runtime registration, render-loop, watcher, and validation installation seam.
- `hyperopen.runtime.state`
  Runtime-wide atom ownership, config constants, timeout/config values, and startup flags.
- `hyperopen.runtime.validation`
  The action/effect argument, emitted-effect order, and app-state validation wrapper seam.
- `hyperopen.runtime.action-adapters`
  The stable facade for app action handlers exposed to runtime registration.
- `hyperopen.runtime.effect-adapters`
  The stable facade for app effect handlers exposed to runtime registration.
- `hyperopen.runtime.collaborators`
  The default dependency assembly seam for action/effect collaborators.
- `hyperopen.runtime.app-effects` and `hyperopen.runtime.api-effects`
  Runtime-owned helpers for browser persistence/history and API effect flows that are not domain policy.

## Dependency Rules

- Allowed:
  Runtime adapters may depend on feature-owned public seams, browser APIs, API facades, websocket clients, wallet helpers, and injected collaborators needed to perform side effects.
- Allowed:
  `runtime.registry-composition` may depend on the schema runtime-registration catalog to derive handler maps.
- Allowed:
  `runtime.bootstrap` may install runtime handlers, render-loop hooks, watchers, and validation.
- Forbidden:
  Do not add business policy, domain normalization, or pure calculation rules to runtime adapters when a feature/domain context owns them.
- Forbidden:
  Do not maintain parallel action/effect ID lists outside the schema runtime-registration catalog and registry composition.
- Forbidden:
  Do not put browser/network side effects in schema, domain, or pure feature policy to avoid using runtime effect adapters.
- Forbidden:
  Do not bypass `runtime.validation` for new handler paths that should participate in development-time boundary checks.

## Key Tests

- Runtime composition and bootstrap:
  `hyperopen.runtime.wiring-test`,
  `hyperopen.runtime.registry-composition-test`,
  `hyperopen.runtime.bootstrap-test`,
  `hyperopen.runtime.collaborators-test`,
  `hyperopen.runtime.collaborators.action-maps-test`,
  `hyperopen.runtime.state-test`
- Adapter and validation coverage:
  `hyperopen.runtime.action-adapters-test`,
  `hyperopen.runtime.effect-adapters.facade-contract-test`,
  `hyperopen.runtime.validation-test`,
  `hyperopen.runtime.app-effects-test`,
  `hyperopen.runtime.api-effects-test`
- Focused effect/action adapter suites:
  `hyperopen.runtime.action-adapters.wallet-test`,
  `hyperopen.runtime.action-adapters.websocket-test`,
  `hyperopen.runtime.action-adapters.navigation-test`,
  `hyperopen.runtime.action-adapters.leaderboard-test`,
  `hyperopen.runtime.action-adapters.ws-diagnostics-test`,
  `hyperopen.runtime.effect-adapters.wallet-test`,
  `hyperopen.runtime.effect-adapters.websocket-test`,
  `hyperopen.runtime.effect-adapters.asset-selector-test`,
  `hyperopen.runtime.effect-adapters.order-test`,
  `hyperopen.runtime.effect-adapters.funding-test`,
  `hyperopen.runtime.effect-adapters.vaults-test`,
  `hyperopen.runtime.effect-adapters.leaderboard-test`,
  `hyperopen.runtime.effect-adapters.staking-test`
- Effect order contract:
  `hyperopen.runtime.effect-order-contract-test`,
  `hyperopen.runtime.effect-order-contract-formal-conformance-test`
- Final repo gates:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New runtime handler registration, duplicate-handler policy, or handler map derivation:
  `hyperopen.runtime.registry-composition` and the schema runtime-registration catalog
- New action handler glue:
  the relevant `hyperopen.runtime.action-adapters.*` namespace and the `hyperopen.runtime.action-adapters` facade if it must be registered
- New effect interpreter glue:
  the relevant `hyperopen.runtime.effect-adapters.*` namespace and the `hyperopen.runtime.effect-adapters` facade if it must be registered
- New runtime-wide configuration, timeout storage, or startup/runtime flag:
  `hyperopen.runtime.state`
- New runtime bootstrap, render-loop, watcher, or validation installation behavior:
  `hyperopen.runtime.bootstrap`
- New dev-time validation or effect-order assertion:
  `hyperopen.runtime.validation` or `hyperopen.runtime.effect-order-contract`
- New collaborator assembly:
  `hyperopen.runtime.collaborators` or the owning collaborator submodule
