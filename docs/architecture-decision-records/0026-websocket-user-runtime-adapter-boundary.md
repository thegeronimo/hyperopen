# ADR 0026: Websocket User Runtime Adapter Boundary

- Status: Accepted
- Date: 2026-03-07

## Context

`/hyperopen/src/hyperopen/websocket/user.cljs` historically sat on top of the canonical websocket runtime, but it still owned several runtime-like responsibilities itself:

- mutable user and per-dex subscription bookkeeping via `user-state`
- a namespace-local debounce timer for post-fill refresh
- fill normalization and toast payload shaping
- per-topic message parsing and direct store mutation
- REST fallback wrappers

This made the module feel like a second websocket architecture beside the reducer/engine/runtime-view model already established by ADR 0017. The generic websocket runtime still owned transport truth, reconnect logic, and desired subscriptions, but `user.cljs` kept enough extra mutable state that architectural ownership was no longer obvious.

The repository architecture rules require single-source runtime ownership, thin adapter seams around stable boundaries, and explicit documentation when a module’s permanent responsibility changes.

## Decision

1. `/hyperopen/src/hyperopen/websocket/user.cljs` remains the stable public adapter namespace for startup and collaborator call sites.
2. User websocket implementation details move behind focused helper seams under `/hyperopen/src/hyperopen/websocket/user_runtime/`:
   - `common.cljs`
   - `subscriptions.cljs`
   - `refresh.cljs`
   - `fills.cljs`
   - `handlers.cljs`
3. Subscription truth for user and per-dex clearinghouse streams now comes from canonical websocket `runtime-view` in `/hyperopen/src/hyperopen/websocket/client.cljs`, not from a second mutable atom.
4. Post-fill debounce timer ownership now lives in app runtime timeout state (`/hyperopen/src/hyperopen/runtime/state.cljs`) through `/hyperopen/src/hyperopen/websocket/user_runtime/refresh.cljs`, not in a namespace-local timer atom.
5. Topic parsing, fill shaping, and store projection logic may remain imperative handler code for now, but it must stay localized to the `user_runtime` helper boundary rather than expanding the top-level adapter namespace.

## Consequences

- `websocket/user.cljs` is small again and easier to audit.
- Subscription duplicate suppression is now aligned with reducer-owned websocket state.
- Future user-stream work has clear extension seams without reopening a parallel runtime ownership path.
- This refactor does not move user-topic store projection into the generic websocket effect algebra. That larger change remains possible later, but it is intentionally out of scope for this boundary cleanup.

## Invariant Ownership

- Canonical websocket runtime truth:
  - `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
  - `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`
  - `/hyperopen/src/hyperopen/websocket/client.cljs`
- Stable user websocket adapter entrypoints:
  - `/hyperopen/src/hyperopen/websocket/user.cljs`
- User-topic helper ownership:
  - `/hyperopen/src/hyperopen/websocket/user_runtime/common.cljs`
  - `/hyperopen/src/hyperopen/websocket/user_runtime/subscriptions.cljs`
  - `/hyperopen/src/hyperopen/websocket/user_runtime/refresh.cljs`
  - `/hyperopen/src/hyperopen/websocket/user_runtime/fills.cljs`
  - `/hyperopen/src/hyperopen/websocket/user_runtime/handlers.cljs`
- Regression coverage:
  - `/hyperopen/test/hyperopen/websocket/user_test.cljs`
  - `/hyperopen/test/hyperopen/websocket/user_runtime/subscriptions_test.cljs`
  - `/hyperopen/test/hyperopen/websocket/user_runtime/refresh_test.cljs`
  - `/hyperopen/test/hyperopen/websocket/user_runtime/fills_test.cljs`

## Extension Rules

- Do not reintroduce a second mutable subscription registry for user websocket topics outside canonical websocket runtime state.
- Do not add new namespace-local timer atoms to `websocket/user.cljs` when app runtime timeout storage or websocket runtime timer effects already provide a stable owner.
- Add new user-topic parsing or projection logic inside `/hyperopen/src/hyperopen/websocket/user_runtime/**` first, then expose only the required stable adapter hooks from `/hyperopen/src/hyperopen/websocket/user.cljs`.
- If a future change needs generic application-store effects inside websocket runtime itself, document that as a separate architecture decision instead of smuggling it into the adapter layer.
