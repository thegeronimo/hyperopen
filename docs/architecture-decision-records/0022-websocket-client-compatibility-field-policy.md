# ADR 0022: Websocket Client Compatibility Field API Cleanup Policy

- Status: Accepted
- Date: 2026-02-25

## Context

ADR 0017 established `runtime-view` as websocket projection authority, but the primary websocket client namespace still exported mutable compatibility atoms:

- `ws-client/connection-state`
- `ws-client/stream-runtime`

Those atoms were derived from `runtime-view` and were no longer authoritative, but their presence on the primary API invited accidental writes and made support/deprecation boundaries ambiguous.

At the same time, startup/store behavior still mirrored legacy websocket connection fields (`:status`, `:attempt`, `:next-retry-at-ms`, `:last-close`, `:queue-size`) even though user-visible diagnostics already depended on canonical health projections.

## Decision

1. Canonical websocket client state API remains:
   - `ws-client/runtime-view`
   - accessor functions in `/hyperopen/src/hyperopen/websocket/client.cljs` (`connected?`, `get-connection-status`, `get-runtime-metrics`, `get-tier-depths`, `get-health-snapshot`).
2. Remove mutable compatibility projection atoms from `/hyperopen/src/hyperopen/websocket/client.cljs`.
3. Provide explicit read-only compatibility projection access in:
   `/hyperopen/src/hyperopen/websocket/client_compat.cljs`.
4. Treat legacy store websocket connection fields as deprecated compatibility fields and stop runtime watcher updates for them.
5. Keep runtime effect algebra aligned with actual interpreter behavior by removing stale deprecated projection effect keywords.

## Consequences

- Primary websocket client API is explicit and single-surface for projection ownership.
- Compatibility reads remain possible for transitional debug tooling, but cannot mutate canonical state.
- Production code reintroduction of removed compatibility atom usage is prevented by guard tests.
- Startup websocket synchronization tracks status transitions and health fingerprints without maintaining duplicate legacy projection mirrors.
- Runtime effect contracts are clearer because declared effect types now match emitted/interpreted effects.

## Invariant Ownership

- Canonical websocket client API:
  `/hyperopen/src/hyperopen/websocket/client.cljs`
- Read-only compatibility adapter:
  `/hyperopen/src/hyperopen/websocket/client_compat.cljs`
- Startup status-transition and health-fingerprint synchronization:
  `/hyperopen/src/hyperopen/startup/watchers.cljs`
- Runtime effect type contract:
  `/hyperopen/src/hyperopen/websocket/domain/model.cljs`
- Compatibility usage guard tests:
  `/hyperopen/test/hyperopen/websocket/client_test.cljs`
