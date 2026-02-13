# ADR 0006: Info Client Instance and Request/Data Boundary

## Status
Accepted

## Context
`src/hyperopen/api.cljs` previously combined:
- global request runtime atoms,
- retry/cooldown/single-flight internals,
- HTTP response parsing concerns,
- and API endpoint functions that mixed transport behavior with state projection.

This made tests depend on global mutable runtime and tied endpoint behavior to `Response` stream semantics.

## Decision
Introduce an instance-scoped info client:
- `src/hyperopen/api/info_client.cljs`
  - `make-info-client` owns queue/cooldown/single-flight runtime in closure state,
  - accepts injected collaborators (`fetch-fn`, clock/sleep, logger, config),
  - retries and dedupes parsed Clojure data (not raw `Response` objects),
  - exposes reset/stats methods for runtime and tests.

Refactor `src/hyperopen/api.cljs` to:
- delegate request runtime to the info client instance,
- consume parsed data from `post-info!`,
- expose data-returning `request-*` endpoint functions alongside compatibility `fetch-*` wrappers.

Move selected projection ownership into application effects:
- `src/hyperopen/runtime/api_effects.cljs` now applies asset-selector/open-orders/fills projections from request data,
- `src/hyperopen/runtime/app_effects.cljs` now owns candle snapshot projection writes from request data.

## Consequences
- Request runtime behavior is no longer global by default; it is encapsulated per client instance.
- Single-flight complexity around cloning `Response` bodies is removed.
- Application layers can depend on request/data functions without requiring store mutation in API transport paths.
- Existing `fetch-*` wrappers remain available for compatibility while migration to request/data seams continues.
