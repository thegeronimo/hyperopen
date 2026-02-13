---
owner: architecture
status: canonical
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Core Beliefs

## Agent-First Legibility
- Prefer discoverability and short entry points over dense one-file instruction blocks.
- Keep canonical guidance close to ownership boundaries.
- Keep indexes current so agents can progressively disclose context.

## Runtime Integrity
- Preserve deterministic runtime behavior where ordering matters.
- Keep domain decisions pure and side-effect-free.
- Keep transport/protocol translation isolated behind Anti-Corruption Layer boundaries.

## Engineering Discipline
- Prefer extending existing seams over introducing parallel abstractions.
- Keep tests behavior-focused and aligned to invariants.
- Keep docs review metadata explicit to prevent stale policy drift.
