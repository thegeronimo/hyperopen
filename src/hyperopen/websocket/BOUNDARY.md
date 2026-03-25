# Websocket Boundary

## Owns

- The canonical websocket client seam, connection lifecycle, runtime state machine, subscription intent, market-message coalescing, health tracking, diagnostics payloads, and raw-envelope normalization.
- Pure websocket policy and model rules under `domain/`.
- Runtime orchestration under `application/`.
- Transport and interpreter side effects under `infrastructure/`.

## Stable Public Seams

- `hyperopen.websocket.client`
  The public client seam for starting, stopping, configuring, and observing websocket runtime behavior.
- `hyperopen.websocket.application.runtime`
  The runtime assembly seam for normalized commands, transport events, routing, and startup wiring.
- `hyperopen.websocket.application.runtime-reducer`
  The pure runtime state machine entrypoint. Keep `initial-runtime-state` and `step` stable.
- `hyperopen.websocket.infrastructure.runtime-effects`
  The only interpreter boundary for websocket runtime effects.
- `hyperopen.websocket.health`
  Shared freshness and transport-health policy consumed by the runtime and projections.

## Dependency Rules

- Allowed:
  `websocket.domain.*` stays pure and may depend only on generic utilities or other pure websocket helpers.
- Allowed:
  `websocket.application.*` may depend on websocket domain policy, injected collaborators, and effect data contracts.
- Allowed:
  `websocket.infrastructure.*` may depend on browser, timer, transport, and runtime I/O APIs.
- Allowed:
  Other bounded contexts should prefer `hyperopen.websocket.client` or the explicit diagnostics seams instead of reaching into reducer internals.
- Forbidden:
  Do not add browser or network side effects to `domain/` or the reducer.
- Forbidden:
  Do not import `hyperopen.views.*` into websocket runtime code.
- Forbidden:
  Do not bypass `runtime-effects` with ad hoc socket/timer manipulation from other namespaces.

## Key Tests

- Use websocket-focused suites first:
  `npm run test:websocket`
- Key namespaces:
  `hyperopen.websocket.application.runtime-test`,
  `hyperopen.websocket.application.runtime-engine-test`,
  `hyperopen.websocket.application.runtime-reducer-test`,
  `hyperopen.websocket.infrastructure.runtime-effects-test`,
  `hyperopen.websocket.client-test`,
  `hyperopen.websocket.user-runtime.subscriptions-test`,
  `hyperopen.websocket.health-runtime-test`
- For full landing gates:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New connection retry, freshness, or backpressure policy:
  `hyperopen.websocket.domain.policy` or `hyperopen.websocket.health`
- New runtime state transition or effect emission:
  `hyperopen.websocket.application.runtime-reducer`
- New command or transport normalization:
  `hyperopen.websocket.application.runtime`
- New browser/timer/socket side effect:
  `hyperopen.websocket.infrastructure.runtime-effects` or `hyperopen.websocket.infrastructure.transport`
- New diagnostics payload, sanitization, or dev tooling behavior:
  `hyperopen.websocket.diagnostics.*`
