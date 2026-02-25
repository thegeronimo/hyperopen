---
owner: architecture
status: canonical
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Hyperopen Architecture Map

## Purpose
This document defines top-level domain boundaries, dependency direction, stable seams, and architecture governance for Hyperopen.

## Layering
- Domain: pure models and policy decisions.
- Application: orchestration and reducer/engine transitions.
- Infrastructure: side-effect interpreters, transport, timers, and integration input and output operations.
- Anti-Corruption Layer and client seam: external schema translation and public Application Programming Interface boundaries.

Dependency direction is intentional: domain -> application -> infrastructure. Anti-Corruption Layer adapters sit at the boundary to absorb protocol-shape change.

## Domain-Driven Design Rules (MUST)
- MUST use ubiquitous language aligned to Hyperliquid protocol and product concepts.
- MUST keep domain decision logic pure and deterministic (no input and output side effects, no hidden mutable state).
- MUST model business transitions through explicit domain messages/effects, not ad hoc maps/calls.
- MUST enforce invariants in a single domain decision point (reducer/domain policy), not duplicated across UI/infrastructure.
- MUST isolate external protocol payloads behind Anti-Corruption Layer translation before they become domain envelopes.
- MUST keep domain models and policies implementation-agnostic where practical.
- MUST preserve lossless ordering rules for account/order/funding flows as a domain invariant.

## S.O.L.I.D. Design Rules (MUST)
- MUST apply Single Responsibility Principle: each module/function should have one reason to change.
- MUST keep websocket responsibilities separated: reducer (pure decisions), engine (single-writer orchestration), effect interpreters (input and output), Anti-Corruption Layer (schema translation), client (public Application Programming Interface seam).
- MUST apply Open-Closed Principle: extend behavior through `RuntimeMsg`, `RuntimeEffect`, policy maps, and adapters before modifying stable core flows.
- MUST preserve stable public Application Programming Interfaces unless explicitly requested, especially `/hyperopen/src/hyperopen/websocket/client.cljs`.
- MUST apply Liskov Substitution Principle: replacement transport/scheduler/clock/router implementations must preserve contract shape and semantics.
- MUST apply Interface Segregation Principle: keep protocols and handler interfaces focused; avoid broad interfaces that force unused methods or arguments.
- MUST apply Dependency Inversion Principle: high-level application/domain logic must depend on abstractions and injected collaborators, not concrete browser/JS primitives.

## Architecture Governance Rules (MUST)
- MUST keep namespace dependencies acyclic (Acyclic Dependencies Principle); if a cycle is introduced temporarily, an Architecture Decision Record with explicit removal plan is required.
- MUST follow Stable Dependencies Principle: dependency direction points toward more stable modules.
- MUST follow Stable Abstractions Principle: shared modules expose abstract seams, not concrete input/output-heavy implementations.
- MUST define and preserve explicit namespace Application Programming Interface surfaces; production code calls public vars only.
- MUST create/update Architecture Decision Records for architecture-affecting changes.
- MUST normalize errors at a single boundary into typed categories before UI/application branching.
- MUST make startup/bootstrap and effect handlers idempotent and reentrant.
- MUST keep startup layering explicit with permanent owners:
  `/hyperopen/src/hyperopen/app/startup.cljs` (facade),
  `/hyperopen/src/hyperopen/startup/collaborators.cljs` (dependency assembly),
  `/hyperopen/src/hyperopen/startup/init.cljs` + `/hyperopen/src/hyperopen/startup/runtime.cljs` (lifecycle behavior),
  and `/hyperopen/src/hyperopen/app/bootstrap.cljs` + `/hyperopen/src/hyperopen/runtime/bootstrap.cljs` + `/hyperopen/src/hyperopen/startup/watchers.cljs` (runtime bootstrap/watchers).
- MUST NOT introduce delegation-only startup wrapper namespaces between `app/startup` and startup behavior owners; new startup boundaries require ADR + contract tests.
- MUST add boundary contract tests for each new seam/module boundary.
- MUST keep complexity bounded: new namespaces under 500 LOC and new functions under 80 LOC unless justified by an Architecture Decision Record.
- MUST include invariant ownership notes in PR documentation for changed invariants.

## Domain-Driven Design Layer Boundaries (MUST)
- Domain model/policy ownership:
  - `/hyperopen/src/hyperopen/websocket/domain/model.cljs`
  - `/hyperopen/src/hyperopen/websocket/domain/policy.cljs`
- Application orchestration/decision flow ownership:
  - `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
  - `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`
  - `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs`
- Infrastructure input and output/effect interpretation ownership:
  - `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`
  - `/hyperopen/src/hyperopen/websocket/infrastructure/transport.cljs`
- Anti-Corruption Layer/adapters/public client seam ownership:
  - `/hyperopen/src/hyperopen/websocket/acl/hyperliquid.cljs`
  - `/hyperopen/src/hyperopen/websocket/client.cljs`
- MUST keep dependency direction intentional: domain -> application -> infrastructure, with Anti-Corruption Layer adapters at system boundaries.
- MUST ensure domain decisions never directly perform transport/timer/dom/log side effects.
- MUST absorb external schema changes in Anti-Corruption Layer mapping and keep domain contracts stable.

## Domain-Driven Design Modeling Checklist
- [ ] Yes/No: ubiquitous language is consistent in message/effect names.
- [ ] Yes/No: new behavior is expressed as domain message/effect variants.
- [ ] Yes/No: invariants are enforced in reducer/domain policy, not split across layers.
- [ ] Yes/No: external payload normalization/mapping is handled in Anti-Corruption Layer/interpreter boundaries.
- [ ] Yes/No: domain changes include determinism, ordering, and replay safety tests.
- [ ] Yes/No: projection entities used by views (for example `:active-market`) are either fully shaped or deterministically derived from canonical state.
- [ ] Yes/No: canonical identity fields (for example `:active-asset`) have deterministic fallback rendering paths when projections are partial.

## S.O.L.I.D. Checklist
- [ ] Yes/No: Single Responsibility Principle is preserved.
- [ ] Yes/No: Open-Closed Principle is preserved.
- [ ] Yes/No: Liskov Substitution Principle is preserved.
- [ ] Yes/No: Interface Segregation Principle is preserved.
- [ ] Yes/No: Dependency Inversion Principle is preserved.

## Canonical Companion Docs
- Reliability invariants and runtime rules: `/hyperopen/docs/RELIABILITY.md`
- Security and signing invariants: `/hyperopen/docs/SECURITY.md`
- Frontend interaction/runtime constraints: `/hyperopen/docs/FRONTEND.md`
- Design beliefs and rationale: `/hyperopen/docs/DESIGN.md`
- Quality and testing standards: `/hyperopen/docs/QUALITY_SCORE.md`
- Reindex map for prior AGENTS sections: `/hyperopen/docs/design-docs/agents-section-index.md`

## Architecture Decision Record Index
Authoritative Architecture Decision Record files live in `/hyperopen/docs/architecture-decision-records/`.
