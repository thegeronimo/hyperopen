# API Boundary

## Owns

- Hyperliquid `/info` and exchange request shaping, response normalization, request runtime ownership, cache and in-flight request state, API error normalization, API projection helpers, market metadata loading, global compatibility facades, and order signing/submission seams.
- Endpoint request body construction and response normalization under `endpoints/`.
- Gateway orchestration and dependency injection under `gateway/`.
- Store projection compatibility under `projections/`, `compat`, and `fetch-compat`.

## Stable Public Seams

- `hyperopen.api`
  The instance-first facade for `make-api`, `make-default-api-service`, `info-url`, and default info-client configuration.
- `hyperopen.api.default`
  The process-wide compatibility facade for the installed API service, request stats, reset/configuration, and legacy public wrappers.
- `hyperopen.api.service` and `hyperopen.api.runtime`
  The runtime ownership seam for request cache, in-flight state, and reset behavior.
- `hyperopen.api.info-client`
  The `/info` transport, retry, request tracking, and request hotspot seam.
- `hyperopen.api.endpoints.*`
  The focused request-shape and normalization seam for market, account, orders, vaults, leaderboard, and Hyperunit funding endpoints.
- `hyperopen.api.gateway.*`
  The orchestration seam that adapts endpoint modules to runtime callers and injected dependencies.
- `hyperopen.api.projections`
  The stable projection facade over the owner namespaces under `hyperopen.api.projections.*`.
- `hyperopen.api.trading`
  The exchange signing, submit, cancel, agent approval, and session invalidation seam.
- `hyperopen.api.market-metadata.facade`
  The stable seam for perp-DEX metadata normalization and application.

## Dependency Rules

- Allowed:
  Endpoint namespaces may own external request payloads, response normalization, and transport-specific request quirks.
- Allowed:
  Gateway namespaces may depend on endpoints, validators, and injected runtime collaborators.
- Allowed:
  Projection namespaces may mutate app-state projections from successful or failed API responses.
- Forbidden:
  Do not put mutable request runtime state in `hyperopen.api`; keep it in `api.service`, `api.runtime`, or `api.default`.
- Forbidden:
  Do not patch `/info` payloads, TTLs, dedupe keys, or normalization in compatibility wrappers when an `api.endpoints.*` owner exists.
- Forbidden:
  Do not duplicate API projection mutation rules in views or runtime adapters.
- Forbidden:
  Do not route exchange signing or order mutation semantics through generic `/info` endpoint modules; keep signing and submit/cancel behavior in `hyperopen.api.trading` and `hyperopen.api.gateway.orders.*`.

## Key Tests

- Core API and runtime:
  `hyperopen.api-test`,
  `hyperopen.api.facade-runtime-test`,
  `hyperopen.api.runtime-test`,
  `hyperopen.api.service-test`,
  `hyperopen.api.default-test`,
  `hyperopen.api.instance-test`,
  `hyperopen.api.info-client-test`,
  `hyperopen.api.errors-test`
- Compatibility and projection seams:
  `hyperopen.api.compat-test`,
  `hyperopen.api.fetch-compat-test`,
  `hyperopen.api.projections.facade-contract-test`,
  `hyperopen.api.projections.market-test`,
  `hyperopen.api.projections.orders-test`,
  `hyperopen.api.projections.portfolio-test`,
  `hyperopen.api.projections.vaults-test`,
  `hyperopen.api.projections.asset-selector-test`,
  `hyperopen.api.projections.api-wallets-test`
- Endpoint and gateway seams:
  `hyperopen.api.endpoints.account-test`,
  `hyperopen.api.endpoints.market-test`,
  `hyperopen.api.endpoints.orders-test`,
  `hyperopen.api.endpoints.vaults-test`,
  `hyperopen.api.endpoints.funding-hyperunit-test`,
  `hyperopen.api.gateway.account-test`,
  `hyperopen.api.gateway.market-test`,
  `hyperopen.api.gateway.orders-test`,
  `hyperopen.api.gateway.orders.commands-test`,
  `hyperopen.api.gateway.vaults-test`,
  `hyperopen.api.gateway.funding-hyperunit-test`
- Trading and mutation seams:
  `hyperopen.api.trading-test`,
  `hyperopen.api.trading.internal-seams-test`,
  `hyperopen.api.trading.sign-and-submit-test`,
  `hyperopen.api.trading.cancel-request-test`,
  `hyperopen.api.trading.approve-agent-test`,
  `hyperopen.api.trading.session-invalidation-test`,
  `hyperopen.api.trading.debug-exchange-simulator-test`
- Final repo gates:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New `/info` request shape, response normalization, pagination, TTL, or dedupe policy:
  the owning `hyperopen.api.endpoints.*` namespace and, when shared, `hyperopen.api.request-policy`
- New request orchestration or injected dependency path:
  the relevant `hyperopen.api.gateway.*` namespace
- New process-wide facade lifecycle behavior:
  `hyperopen.api.default`, `hyperopen.api.service`, or `hyperopen.api.runtime`
- New app-state projection after an API response:
  the owning `hyperopen.api.projections.*` namespace and the `hyperopen.api.projections` facade if a stable alias is needed
- New exchange signing, order submit, cancel, or approval behavior:
  `hyperopen.api.trading` or `hyperopen.api.gateway.orders.commands`
- New API error category or user-facing fallback message:
  `hyperopen.api.errors`
- New market metadata or asset-selector market assembly behavior:
  `hyperopen.api.market-loader` or `hyperopen.api.market-metadata.*`
