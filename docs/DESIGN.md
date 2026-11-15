---
owner: architecture
status: canonical
last_reviewed: 2026-06-07
review_cycle_days: 90
source_of_truth: true
---

# Design System of Record

## Scope
This document points to design intent, operating principles, and verified architecture rationale.

Review note, 2026-06-07: verified the canonical inputs below still exist and remain
the active owners for design intent, AGENTS section routing, architecture boundaries,
and Architecture Decision Records.

## Repo-Wide Engineering Rules (MUST)
- MUST keep changes scoped to the task and avoid unrelated edits.
- MUST preserve existing public APIs unless explicitly requested to change them.
- MUST avoid duplicate logic; extend existing code where feasible.
- MUST add or update tests for all behavioral changes.
- MUST keep runtime behavior deterministic where architecture depends on ordered/event-driven flows.
- MUST NOT include machine-specific absolute paths in repo docs or agent guidance; use repo-root paths like `/hyperopen/...` instead.
- MUST prefer full term names over abbreviations in policy and architecture docs; when abbreviations are used, expand the full term first.

## Simplicity and Maintainability
- MUST prefer the simplest solution that is correct, easy to understand, and aligned with existing patterns.
- SHOULD prefer changes that reduce overall complexity, duplication, and moving parts when all else is equal.
- SHOULD treat smaller code surface area as a tie-breaker, not the primary goal.
- MUST NOT compress code, hide logic, or introduce clever abstractions solely to reduce lines of code, file size, or diff size.
- MAY add code when it improves readability, explicitness, testability, error handling, or consistency with established patterns.
- MUST prefer deleting dead code, consolidating duplicate logic, and reusing existing utilities before adding new abstractions or dependencies.
- MUST NOT remove tests, type information, or validation solely to make a change look smaller.

## Canonical Inputs
- Core beliefs and agent-first principles: `/hyperopen/docs/design-docs/core-beliefs.md`
- AGENTS section reindex map: `/hyperopen/docs/design-docs/agents-section-index.md`
- Design docs index and verification status: `/hyperopen/docs/design-docs/index.md`
- Architecture map: `/hyperopen/ARCHITECTURE.md`
- Architecture Decision Record decisions: `/hyperopen/docs/architecture-decision-records/`

## Working Rules
- Keep design rationale near implementation boundaries.
- Keep terminology aligned with Hyperliquid protocol concepts.
- Keep docs additive and index-driven rather than embedding long policy blocks in AGENTS.
