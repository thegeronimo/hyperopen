---
owner: architecture
status: canonical
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Design System of Record

## Scope
This document points to design intent, operating principles, and verified architecture rationale.

## Repo-Wide Engineering Rules (MUST)
- MUST keep changes scoped to the task and avoid unrelated edits.
- MUST preserve existing public APIs unless explicitly requested to change them.
- MUST avoid duplicate logic; extend existing code where feasible.
- MUST add or update tests for all behavioral changes.
- MUST keep runtime behavior deterministic where architecture depends on ordered/event-driven flows.
- MUST NOT include machine-specific absolute paths in repo docs or agent guidance; use repo-root paths like `/hyperopen/...` instead.
- MUST prefer full term names over abbreviations in policy and architecture docs; when abbreviations are used, expand the full term first.

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
