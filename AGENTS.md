# Hyperopen AGENTS

## Scope and Precedence
- This file is the canonical instruction source for coding agents working in this repository.
- Precedence order for repository guidance is: `AGENTS.md` > task-specific user/developer instructions > `.cursorrules` and `GUIDELINES.md` references.
- `.cursorrules` and `GUIDELINES.md` remain important context documents, but they are not the primary agent contract.
- This document is repo-wide and includes strict websocket runtime standards.

## Repo-Wide Engineering Rules (MUST)
- MUST keep changes scoped to the task and avoid unrelated edits.
- MUST preserve existing public APIs unless explicitly requested to change them.
- MUST avoid duplicate logic; extend existing code where feasible.
- MUST add or update tests for all behavioral changes.
- MUST keep runtime behavior deterministic where architecture depends on ordered/event-driven flows.
- MUST NOT include machine-specific absolute paths in repo docs or agent guidance; use repo-root paths like `/hyperopen/...` instead.

## Domain-Driven Design Rules (MUST)
- MUST use ubiquitous language aligned to Hyperliquid protocol and product concepts.
- MUST keep domain decision logic pure and deterministic (no IO, no hidden mutable state).
- MUST model business transitions through explicit domain messages/effects, not ad hoc maps/calls.
- MUST enforce invariants in a single domain decision point (reducer/domain policy), not duplicated across UI/infrastructure.
- MUST isolate external protocol payloads behind ACL translation before they become domain envelopes.
- MUST keep domain models and policies implementation-agnostic (framework/runtime-independent where practical).
- MUST preserve lossless ordering rules for account/order/funding flows as a domain invariant.

## WebSocket Runtime Architecture Rules (MUST)
- MUST keep runtime decisions pure via `step(state, msg) -> {:state next-state :effects [...]}`.
- MUST keep canonical websocket runtime state single-writer: only the engine loop mutates it.
- MUST execute IO only through effect interpreters (transport, timers, lifecycle hooks, logging, routing, state projections).
- MUST NOT perform direct transport/timer/dom/log side effects inside reducers or domain message handling.
- MUST NOT use multi-loop shared mutable writes for connection/metrics runtime ownership.
- MUST preserve existing websocket public APIs in `/hyperopen/src/hyperopen/websocket/client.cljs` unless explicitly requested.
- MUST maintain message/effect algebra using `RuntimeMsg` and `RuntimeEffect` style contracts.

## DDD Layer Boundaries (MUST)
- Domain model/policy ownership:
- `/hyperopen/src/hyperopen/websocket/domain/model.cljs`
- `/hyperopen/src/hyperopen/websocket/domain/policy.cljs`
- Application orchestration/decision flow ownership:
- `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
- `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`
- `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs`
- Infrastructure IO/effect interpretation ownership:
- `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`
- `/hyperopen/src/hyperopen/websocket/infrastructure/transport.cljs`
- ACL/adapters/public client seam ownership:
- `/hyperopen/src/hyperopen/websocket/acl/hyperliquid.cljs`
- `/hyperopen/src/hyperopen/websocket/client.cljs`
- MUST keep dependency direction intentional: domain -> application -> infrastructure, with ACL adapters at system boundaries.
- MUST ensure domain decisions never directly perform transport/timer/dom/log side effects.
- MUST absorb external schema changes in ACL mapping and keep domain contracts stable.

## core.async / Channel Best Practices (MUST)
- MUST define explicit channel roles and keep them stable:
- `mailbox`: runtime command/event ingress, lossless, bounded.
- `effects`: effect execution queue, bounded.
- `router-bus`: topic fanout for decoded envelopes.
- `metrics`: best-effort telemetry (dropping allowed).
- `dead-letter`: diagnostics/error sink (dropping allowed).
- MUST keep control and lossless domain flows lossless and bounded.
- MUST only use sliding or dropping buffers where loss policy is explicitly intended and documented.
- MUST define overflow behavior for bounded queues (drop policy, dead-letter, and metric/log signal).
- MUST isolate handlers behind dedicated channels/workers so slow handlers do not stall core runtime loops.
- MUST document tiering and backpressure rationale for market vs user/account/order flows.

## Purity and IO Boundary Checklist
- [ ] Yes/No: reducer code has no `swap!`, `reset!`, `println`, JS interop side effects, or `infra/*` IO calls.
- [ ] Yes/No: all side effects are represented as `RuntimeEffect` values.
- [ ] Yes/No: effect interpreters are the only place where socket/timer/dom/log IO executes.
- [ ] Yes/No: canonical runtime state has a single writer (engine loop).
- [ ] Yes/No: connection and stream projections are applied as explicit projection effects.
- [ ] Yes/No: new websocket behaviors are introduced through message/effect algebra, not ad hoc direct calls.

## DDD Modeling Checklist
- [ ] Yes/No: ubiquitous language is consistent in message/effect names.
- [ ] Yes/No: new behavior is expressed as domain message/effect variants.
- [ ] Yes/No: invariants are enforced in reducer/domain policy, not split across layers.
- [ ] Yes/No: external payload normalization/mapping is handled in ACL/interpreter boundaries.
- [ ] Yes/No: domain changes include determinism, ordering, and replay safety tests.

## Testing Requirements (MUST)
- MUST include reducer determinism tests (same state + same msg => same state/effects).
- MUST include single-writer invariant tests for runtime ownership.
- MUST include FIFO and replay correctness tests under reconnect.
- MUST include market coalescing correctness tests under burst traffic.
- MUST include lossless ordering tests for `openOrders`, `userFills`, `userFundings`, and `userNonFundingLedgerUpdates`.
- MUST include lifecycle and watchdog behavior tests.
- MUST include address-watcher compatibility tests for websocket status transitions.
- MUST pass compile gates: `npx shadow-cljs compile app` and `npx shadow-cljs compile test`.
- MUST keep websocket-focused tests independently runnable.
- MUST acknowledge the known full `npm test` limitation: Node import issue with `lightweight-charts` exports.

## Anti-Patterns (DO NOT)
- DO NOT perform side effects inside reducer transitions.
- DO NOT allow multiple writers to mutate canonical runtime state.
- DO NOT add hidden synchronous fallback paths that bypass the channel model.
- DO NOT use unbounded queues for lossless flows.
- DO NOT bypass message/effect contracts with direct infrastructure calls from domain logic.
- DO NOT put business rules in effect interpreters, transport handlers, or UI callbacks.
- DO NOT leak raw exchange payload shapes directly into domain consumers without ACL mapping.
- DO NOT introduce new behavior via direct infrastructure calls that bypass runtime message/effect algebra.

## Change Workflow for Agents
- Read websocket runtime files before editing:
- `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
- `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs`
- `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`
- `/hyperopen/src/hyperopen/websocket/client.cljs`
- Add or adjust tests before finalizing large runtime behavior changes.
- Keep compatibility adapter behavior explicit and documented.
- Document any intentional invariant deviations in PR notes.

## Mini-Template: Add a New WebSocket Event
- Define or update the domain concept/invariant and ubiquitous term first.
- Add a `RuntimeMsg` variant in domain model (constructor/predicate coverage).
- Extend reducer `step` with pure state transition and emitted effects.
- Add or extend interpreter handling for any new effect type.
- Follow the RuntimeMsg -> reducer -> interpreter -> tests sequence.
- Add tests for:
- reducer branch determinism,
- expected effect emission,
- integration path through engine + interpreter.
