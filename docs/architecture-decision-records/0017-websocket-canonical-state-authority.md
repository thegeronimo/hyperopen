# ADR 0017: WebSocket Canonical State Authority

- Status: Accepted
- Date: 2026-02-25

## Context

WebSocket runtime state was projected into multiple mutable atoms (`connection-state`, `stream-runtime`) and app runtime dedupe state (`:websocket-health`), while reducer/engine state already owned runtime truth.

That created authority ambiguity:

- startup watchers merged connection and stream projections from separate atoms
- health dedupe writes depended on app runtime state
- diagnostics and UI fallbacks could imply multiple websocket health sources

## Decision

1. Canonical websocket authority remains reducer/engine state (`runtime_reducer` + `runtime_engine`).
2. Reducer emits one projection effect: `:fx/project-runtime-view`.
3. Interpreter applies one websocket projection atom: `ws-client/runtime-view`.
4. Compatibility atoms (`ws-client/connection-state`, `ws-client/stream-runtime`) are derived from `runtime-view` and are non-authoritative.
5. Startup websocket watchers consume only `runtime-view`.
6. Websocket health fingerprint dedupe ownership moves to websocket-scoped projection state (`ws-client/websocket-health-projection-state`), not app runtime state.
7. UI freshness callers standardize on store `[:websocket :health]` as the normalized health path.

## Consequences

- Runtime projection ingress becomes explicit and singular.
- Watcher/store sync no longer merges parallel websocket projection atoms.
- App runtime state is no longer a websocket health authority side-channel.
- Existing public websocket client API behavior remains compatible; legacy projection atoms still exist but are derived mirrors.

## Invariant Ownership

- Canonical websocket runtime authority:
  - `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
  - `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs`
- Runtime view projection ownership:
  - `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`
  - `/hyperopen/src/hyperopen/websocket/client.cljs`
- Startup/store projection sync ownership:
  - `/hyperopen/src/hyperopen/startup/watchers.cljs`
- Websocket health dedupe ownership:
  - `/hyperopen/src/hyperopen/websocket/client.cljs`
  - `/hyperopen/src/hyperopen/websocket/health_runtime.cljs`
