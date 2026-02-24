# Coalesce WebSocket Market Store Writes Per Frame

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

WebSocket market handlers currently write to the application store on nearly every incoming payload, and the render loop in `/hyperopen/src/hyperopen/runtime/bootstrap.cljs` renders on every store write. During burst market traffic, this creates avoidable render pressure and UI jitter. After this change, market payloads for order book and active asset context are coalesced per animation frame so one frame produces at most one application-store `swap!`, while still applying the latest payload for each coin/topic. Users should see smoother updates under heavy market traffic without data lag or subscription regressions.

## Progress

- [x] (2026-02-16 01:35Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/RELIABILITY.md`, and `/hyperopen/docs/QUALITY_SCORE.md` constraints for websocket determinism, responsiveness ordering, and required validation gates.
- [x] (2026-02-16 01:35Z) Confirmed hot paths and render trigger points in `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`, `/hyperopen/src/hyperopen/websocket/orderbook.cljs`, and `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs`.
- [x] (2026-02-16 01:35Z) Authored this active ExecPlan with concrete implementation and validation milestones.
- [x] (2026-02-16 01:42Z) Implemented shared frame-coalesced market projection runtime in `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs` and integrated queueing in orderbook and active-asset websocket handlers.
- [x] (2026-02-16 01:42Z) Collapsed touched multi-`swap!` paths into atomic transitions in `/hyperopen/src/hyperopen/websocket/orderbook.cljs` and `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs`.
- [x] (2026-02-16 01:43Z) Added websocket regression tests in `/hyperopen/test/hyperopen/websocket/market_projection_runtime_test.cljs`, `/hyperopen/test/hyperopen/websocket/orderbook_test.cljs`, and `/hyperopen/test/hyperopen/websocket/active_asset_ctx_test.cljs`.
- [x] (2026-02-16 01:44Z) Ran required gates: `npm run check`, `npm test`, and `npm run test:websocket` (all passed).
- [x] (2026-02-16 01:44Z) Captured before/after evidence through deterministic write-count assertions proving one store write per frame under burst input.

## Surprises & Discoveries

- Observation: Market-tier coalescing already exists in websocket runtime for envelope dispatch (`:market-coalesce` + `:evt/timer-market-flush-fired`), but application-store writes still occur in per-topic handlers after dispatch.
  Evidence: `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` coalesces envelopes; `/hyperopen/src/hyperopen/websocket/orderbook.cljs` and `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs` still each schedule store writes independently.
- Observation: `active-asset-ctx` handler currently performs two consecutive store `swap!` calls inside one timeout callback, causing two render-triggering writes per payload.
  Evidence: `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs` updates `[:active-assets :contexts coin]` and `[:active-assets :loading]` in separate `swap!` calls.
- Observation: The repository already contains an animation-frame batching pattern that can be reused for design and tests.
  Evidence: `/hyperopen/src/hyperopen/asset_selector/icon_status_runtime.cljs` and `/hyperopen/test/hyperopen/asset_selector/icon_status_runtime_test.cljs`.
- Observation: Scheduling state in the shared coalescer needs a sentinel handle state to avoid duplicate frame scheduling before a real frame id exists.
  Evidence: `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs` uses `::scheduled-frame-handle` and tests prove one scheduled frame per burst.

## Decision Log

- Decision: Add a shared market projection coalescer used by both `l2Book` and `activeAssetCtx` handlers instead of adding independent coalescers per module.
  Rationale: A shared coalescer is required to enforce one application-store write per frame across all targeted market handlers, not one write per module.
  Date/Author: 2026-02-16 / Codex
- Decision: Keep websocket client public APIs unchanged (`register-handler!`, `init!`, subscribe/unsubscribe functions) and scope the refactor to handler internals plus a new runtime helper namespace.
  Rationale: This preserves stable seams required by architecture guardrails while delivering the performance behavior change.
  Date/Author: 2026-02-16 / Codex
- Decision: Prefer “latest state wins per key within a frame” for market projections.
  Rationale: This matches existing market-tier coalescing intent and improves throughput while keeping user/account/order lossless flows untouched.
  Date/Author: 2026-02-16 / Codex
- Decision: Keep module-local cache atoms (`orderbook-state`, `active-asset-ctx-state`) behavior intact while only coalescing application-store projections.
  Rationale: This avoids changing module API behavior and isolates the performance refactor to render-driving writes.
  Date/Author: 2026-02-16 / Codex
- Decision: Apply pending projection updates in deterministic key order during flush.
  Rationale: Deterministic ordering preserves replay safety and removes hidden non-determinism from map iteration.
  Date/Author: 2026-02-16 / Codex

## Outcomes & Retrospective

Implementation complete for the targeted finding. The websocket orderbook and active-asset handlers now queue application-store projection updates into a shared per-frame coalescer (`/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs`) rather than issuing per-payload `swap!` writes.

What was achieved:

- Added frame-batched projection runtime with one scheduled flush per store/frame and latest-value-wins replacement per coalesce key.
- Rewired `/hyperopen/src/hyperopen/websocket/orderbook.cljs` and `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs` to use coalesced projection writes.
- Collapsed touched local unsubscribe multi-write paths to single atomic transitions.
- Added deterministic websocket regression tests for burst coalescing, latest-value-wins behavior, and atomic unsubscribe updates.

Proof and acceptance evidence:

- `hyperopen.websocket.market-projection-runtime-test` asserts:
  - multiple different keys in one frame flush via one store write;
  - same-key replacements keep latest payload;
  - new frames schedule correctly after prior flush.
- `hyperopen.websocket.orderbook-test` asserts two rapid `l2Book` payloads produce one store write after flush while preserving latest local module state.
- `hyperopen.websocket.active-asset-ctx-test` asserts two rapid `activeAssetCtx` payloads produce one store write and atomically update `:contexts` and `:loading`.

Validation summary:

- `npm run check`: pass.
- `npm test`: pass (`903` tests, `4100` assertions, `0` failures, `0` errors).
- `npm run test:websocket`: pass (`116` tests, `458` assertions, `0` failures, `0` errors).

Remaining risk:

- This change intentionally optimizes only the identified market projection paths. Other websocket handlers that still write store state directly are out of scope for this refactor.

## Context and Orientation

The app render loop is installed in `/hyperopen/src/hyperopen/runtime/bootstrap.cljs`. It adds a watch on the root store and calls `render!` on each store change, so each store `swap!` can trigger a full render pass.

The two hot websocket handlers are:

- `/hyperopen/src/hyperopen/websocket/orderbook.cljs` (`create-orderbook-data-handler`), which currently uses `platform/set-timeout!` and then writes `[:orderbooks coin]`.
- `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs` (`create-active-asset-data-handler`), which currently uses `platform/set-timeout!` and then performs two store writes.

In this plan, “coalescing per frame” means collecting updates during one browser paint interval and applying them in one atomic store transition scheduled with `requestAnimationFrame` (via `/hyperopen/src/hyperopen/platform.cljs`). “Atomic transition” means one `swap!` call that applies all intended path updates, so no intermediate state is rendered unless explicitly required.

This plan does not change websocket transport, reducer message algebra, or signing behavior. Scope is limited to market projection write frequency and handler-level update composition.

## Plan of Work

Milestone 1 introduces a shared market projection coalescer runtime in websocket infrastructure-facing code. The coalescer owns pending market updates, schedules one animation-frame flush at a time, and applies queued updates with a single application-store `swap!`. Within one frame, updates are merged by deterministic key (topic + coin + target store path family) so only the latest payload per key is applied.

Milestone 2 rewires orderbook and active-asset handlers to enqueue projection updates into the shared coalescer instead of directly calling `set-timeout` + `swap!`. Orderbook local module state (`orderbook-state`) can remain immediate for module introspection, but application-store projection moves to coalesced flushes. Active-asset projection updates are combined into one atomic map update (`contexts` + `loading`) during coalesced flush.

Milestone 3 collapses remaining obvious multi-`swap!` paths in touched modules when intermediate states are not intentional. This includes local-state multi-write paths in unsubscribe flows where two updates can be represented as one atomic update function.

Milestone 4 adds deterministic regression tests in websocket test namespaces. Tests must prove: burst payloads in the same frame produce one store write, latest-value-wins per key inside a frame, and both orderbook + active-asset updates can flush together in one atomic transition. Tests should use controlled stubs for `platform/request-animation-frame!` and avoid real timers.

Milestone 5 runs repository-required validation gates and records evidence in this plan. Evidence includes concise test output and a small write-count comparison from focused tests showing that pre-flush behavior would emit multiple writes while post-refactor behavior emits one write per frame.

## Concrete Steps

From `/hyperopen`:

1. Add a new helper namespace for shared frame batching, for example:
   - `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs`
   This namespace should expose queue and flush functions with dependency injection for scheduler and store-apply behavior so tests stay deterministic.
2. Update websocket market handlers:
   - `/hyperopen/src/hyperopen/websocket/orderbook.cljs`
   - `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs`
   Replace handler-level `set-timeout` store writes with coalescer queue calls, and keep payload normalization behavior unchanged.
3. Apply atomic update cleanup in touched modules where multi-`swap!` is currently used without intentional intermediate states.
4. Add tests (new files expected):
   - `/hyperopen/test/hyperopen/websocket/market_projection_runtime_test.cljs`
   - `/hyperopen/test/hyperopen/websocket/orderbook_test.cljs`
   - `/hyperopen/test/hyperopen/websocket/active_asset_ctx_test.cljs`
5. Run required gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

Expected transcript shape:

  - `npm run check` completes without lint or compile failures.
  - `npm test` reports 0 failures and 0 errors.
  - `npm run test:websocket` reports 0 failures and 0 errors, including new burst-coalescing tests.

## Validation and Acceptance

Acceptance is met when all conditions below are true:

- For burst market payloads delivered before one animation-frame flush, the application store is mutated once for the combined flush (not once per payload).
- Orderbook and active-asset market updates can be coalesced into the same frame flush and still project correct final values.
- Active-asset context handler no longer performs separate consecutive store writes for `:contexts` and `:loading` when processing one payload batch.
- Updated modules preserve existing public API contracts and channel registration behavior.
- Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

Human verification path after tests:

- Start dev app and connect websocket feeds.
- Trigger or simulate high-frequency `l2Book` and `activeAssetCtx` traffic.
- Confirm UI remains responsive while market data continues updating correctly with no visible stale lockups.

## Idempotence and Recovery

The refactor is additive-first: introduce the coalescer, then route handlers through it. If regressions occur, safely recover by temporarily switching handlers back to direct store projection paths while retaining new tests and coalescer namespace for iterative repair. No destructive data migration or irreversible command is involved. Re-running test commands is safe.

## Artifacts and Notes

Planned changed paths:

- `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs` (new)
- `/hyperopen/src/hyperopen/websocket/orderbook.cljs`
- `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs`
- `/hyperopen/test/hyperopen/websocket/market_projection_runtime_test.cljs` (new)
- `/hyperopen/test/hyperopen/websocket/orderbook_test.cljs` (new)
- `/hyperopen/test/hyperopen/websocket/active_asset_ctx_test.cljs` (new)
- `/hyperopen/docs/exec-plans/completed/2026-02-16-websocket-store-write-coalescing.md` (living updates)

Evidence to capture during implementation:

- Focused test assertions showing write-count reduction under burst input.
- Proof that latest payload per key is what lands in store after one frame flush.
- Required gate outputs.

## Interfaces and Dependencies

Public websocket module interfaces that must remain stable:

- `/hyperopen/src/hyperopen/websocket/orderbook.cljs`
  - `subscribe-orderbook!`
  - `unsubscribe-orderbook!`
  - `create-orderbook-data-handler`
  - `init!`
- `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs`
  - `subscribe-active-asset-ctx!`
  - `unsubscribe-active-asset-ctx!`
  - `create-active-asset-data-handler`
  - `init!`
- `/hyperopen/src/hyperopen/websocket/client.cljs`
  - `register-handler!` and surrounding client API surface unchanged.

Dependencies and utilities to use:

- `/hyperopen/src/hyperopen/platform.cljs` for `request-animation-frame!` (with existing fallback behavior).
- Existing websocket runtime and tier coalescing behavior remain unchanged; this plan only optimizes projection writes at handler/store boundary.
- Existing required gates and websocket-focused deterministic test expectations from `/hyperopen/docs/QUALITY_SCORE.md`.

Plan revision note: 2026-02-16 01:35Z - Initial plan created from static performance finding, hotspot code review, and repository architecture/reliability constraints.
Plan revision note: 2026-02-16 01:44Z - Updated living sections after implementing shared per-frame projection coalescing, adding burst regression tests, and passing all required validation gates.
