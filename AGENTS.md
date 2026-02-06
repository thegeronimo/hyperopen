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

## UI Interaction Runtime Rules (MUST)
- MUST apply user-visible UI state transitions first in an action pipeline (example: close dropdown immediately before unsubscribe/subscribe/fetch effects).
- MUST batch related UI state writes caused by one interaction into a single state projection effect when feasible.
- MUST avoid duplicate side-effect issuance in one interaction flow (example: only one candle snapshot trigger per asset selection).
- MUST define a single owner per projection path in a flow (`:active-asset`, `:selected-asset`, `:active-market`) and avoid redundant writers unless explicitly documented and tested.
- MUST represent multi-token Replicant `:class` values as collections (for example `["opacity-0" "scale-y-95"]`) and MUST NOT use space-separated class strings in `:class` (for example `"opacity-0 scale-y-95"`), to avoid Replicant warnings and normalization overhead.

## Domain-Driven Design Rules (MUST)
- MUST use ubiquitous language aligned to Hyperliquid protocol and product concepts.
- MUST keep domain decision logic pure and deterministic (no IO, no hidden mutable state).
- MUST model business transitions through explicit domain messages/effects, not ad hoc maps/calls.
- MUST enforce invariants in a single domain decision point (reducer/domain policy), not duplicated across UI/infrastructure.
- MUST isolate external protocol payloads behind ACL translation before they become domain envelopes.
- MUST keep domain models and policies implementation-agnostic (framework/runtime-independent where practical).
- MUST preserve lossless ordering rules for account/order/funding flows as a domain invariant.

## S.O.L.I.D. Design Rules (MUST)
- MUST apply Single Responsibility Principle: each module/function should have one reason to change.
- MUST keep websocket responsibilities separated: reducer (pure decisions), engine (single-writer orchestration), effect interpreters (IO), ACL (schema translation), client (public API seam).
- MUST apply Open-Closed Principle: extend behavior through `RuntimeMsg`, `RuntimeEffect`, policy maps, and adapters before modifying stable core flows.
- MUST preserve stable public interfaces unless explicitly requested, especially `/hyperopen/src/hyperopen/websocket/client.cljs`.
- MUST apply Liskov Substitution Principle: replacement transport/scheduler/clock/router implementations must preserve contract shape and semantics (ordering, timing units, and return behavior).
- MUST apply Interface Segregation Principle: keep protocols and handler interfaces focused; avoid broad interfaces that force unused methods or arguments.
- MUST apply Dependency Inversion Principle: high-level application/domain logic must depend on abstractions and injected collaborators, not concrete browser/JS primitives.

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
- [ ] Yes/No: projection entities used by views (for example `:active-market`) are either fully shaped or deterministically derived from canonical state.
- [ ] Yes/No: canonical identity fields (for example `:active-asset`) have deterministic fallback rendering paths when denormalized projections are absent/incomplete.

## S.O.L.I.D. Checklist
- [ ] Yes/No: SRP is preserved (no mixed domain decision + IO + projection responsibilities in one unit).
- [ ] Yes/No: OCP is preserved (behavior added via extension seams before core flow rewrites).
- [ ] Yes/No: LSP is preserved (substitutable implementations satisfy existing contracts and invariants).
- [ ] Yes/No: ISP is preserved (interfaces/protocols remain minimal and purpose-specific).
- [ ] Yes/No: DIP is preserved (high-level logic depends on abstractions/injection, not concrete infra types).

## Testing Requirements (MUST)
- MUST include reducer determinism tests (same state + same msg => same state/effects).
- MUST include single-writer invariant tests for runtime ownership.
- MUST include FIFO and replay correctness tests under reconnect.
- MUST include market coalescing correctness tests under burst traffic.
- MUST include lossless ordering tests for `openOrders`, `userFills`, `userFundings`, and `userNonFundingLedgerUpdates`.
- MUST include lifecycle and watchdog behavior tests.
- MUST include address-watcher compatibility tests for websocket status transitions.
- MUST include effect-order tests for user-interaction actions where responsiveness is critical (UI-close/save projection effects must precede heavy subscription/fetch effects).
- MUST include no-duplicate-effects tests for selection flows (no repeated network/subscription effects caused by one action dispatch).
- MUST include view fallback tests ensuring active symbol/icon text renders from canonical identity when market projection is partial.
- MUST include regression tests for selection transitions from asset A -> B covering both timing and render correctness.
- MUST pass compile gates: `npx shadow-cljs compile app` and `npx shadow-cljs compile test`.
- MUST keep websocket-focused tests independently runnable.
- MUST acknowledge the known full `npm test` limitation: Node import issue with `lightweight-charts` exports.

## Interaction Regression Scenarios (MUST)
- Asset select emits immediate close/projection update before unsubscribe/subscribe effects.
- Asset select emits no duplicate fetch/subscription effects.
- Active asset bar still renders symbol if `:active-market` is partial.
- Transition from one active asset to another preserves visible symbol and closes selector instantly.

## Interaction Assumptions and Defaults
- Assume existing Nexus/Replicant synchronous dispatch model remains unchanged.
- Assume `:active-asset` is canonical identity and `:active-market` is a denormalized projection.
- Default policy style is strict `MUST` / `DO NOT` guidance for interaction flow constraints.

## Anti-Patterns (DO NOT)
- DO NOT perform side effects inside reducer transitions.
- DO NOT allow multiple writers to mutate canonical runtime state.
- DO NOT add hidden synchronous fallback paths that bypass the channel model.
- DO NOT use unbounded queues for lossless flows.
- DO NOT bypass message/effect contracts with direct infrastructure calls from domain logic.
- DO NOT put business rules in effect interpreters, transport handlers, or UI callbacks.
- DO NOT leak raw exchange payload shapes directly into domain consumers without ACL mapping.
- DO NOT introduce new behavior via direct infrastructure calls that bypass runtime message/effect algebra.
- DO NOT mix domain decision logic and infrastructure IO concerns in the same function/module.
- DO NOT modify stable interfaces for one-off behavior when existing extension points can satisfy the change.
- DO NOT couple high-level runtime logic directly to concrete browser/JS infrastructure primitives.
- DO NOT design broad protocols/handlers that force consumers to implement unused methods or accept unused arguments.
- DO NOT place UI-close/visibility effects after subscription or fetch effects in the same synchronous interaction pipeline.
- DO NOT write the same semantic state in multiple layers of one flow (action + effect) unless idempotency and intent are documented.
- DO NOT persist partial denormalized market projections without required identity/display fields (`:coin`, `:symbol`) when non-nil.
- DO NOT pass space-separated class strings to `:class` in Hiccup attrs.

## Change Workflow for Agents
- Read websocket runtime files before editing:
- `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
- `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs`
- `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`
- `/hyperopen/src/hyperopen/websocket/client.cljs`
- Validate SRP/OCP/LSP/ISP/DIP impact before editing websocket runtime components; document intentional tradeoffs in PR notes.
- Add or adjust tests before finalizing large runtime behavior changes.
- When changing interaction flows (selector/modals/dropdowns), explicitly document intended effect order in PR notes.
- Require at least one targeted regression test that validates both responsiveness ordering and rendering fallback behavior.
- Keep compatibility adapter behavior explicit and documented.
- Document any intentional invariant deviations in PR notes.

## Mini-Template: Add a New WebSocket Event
- Define or update the domain concept/invariant and ubiquitous term first.
- Validate the intended extension seam (OCP) and responsibility split (SRP) before adding message/effect branches.
- Add a `RuntimeMsg` variant in domain model (constructor/predicate coverage).
- Extend reducer `step` with pure state transition and emitted effects.
- Add or extend interpreter handling for any new effect type.
- Follow the RuntimeMsg -> reducer -> interpreter -> tests sequence.
- Add tests for:
- reducer branch determinism,
- expected effect emission,
- integration path through engine + interpreter.
