---
owner: platform
status: canonical
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Hyperopen AGENTS

## Purpose
This file is the agent entry point for this repository. It is a table of contents, not an encyclopedia.

## Source of Truth and Precedence
- Canonical requirements live in `/hyperopen/ARCHITECTURE.md` and `/hyperopen/docs/**`.
- Task-specific user/developer instructions override repository docs for the current task.

## Start Here
1. [Architecture Map](/hyperopen/ARCHITECTURE.md)
2. [Reliability Invariants](/hyperopen/docs/RELIABILITY.md)
3. [Security and Signing Safety](/hyperopen/docs/SECURITY.md)
4. [Quality Scorecard](/hyperopen/docs/QUALITY_SCORE.md)
5. [Planning and Execution](/hyperopen/docs/PLANS.md)

## Domain Guides
- Architecture boundaries: [ARCHITECTURE.md](/hyperopen/ARCHITECTURE.md)
- Design foundations and beliefs: [DESIGN.md](/hyperopen/docs/DESIGN.md) and [design docs index](/hyperopen/docs/design-docs/index.md)
- Prior AGENTS section map: [AGENTS Section Reindex Map](/hyperopen/docs/design-docs/agents-section-index.md)
- Frontend policy: [FRONTEND.md](/hyperopen/docs/FRONTEND.md)
- Product specs and roadmap intent: [PRODUCT_SENSE.md](/hyperopen/docs/PRODUCT_SENSE.md) and [product specs index](/hyperopen/docs/product-specs/index.md)
- Reliability invariants and runtime behavior: [RELIABILITY.md](/hyperopen/docs/RELIABILITY.md)
- Security and signing invariants: [SECURITY.md](/hyperopen/docs/SECURITY.md)
- Quality posture and test expectations: [QUALITY_SCORE.md](/hyperopen/docs/QUALITY_SCORE.md)
- Planning and execution artifacts: [PLANS.md](/hyperopen/docs/PLANS.md)
- References and external anchors: [references index](/hyperopen/docs/references/index.md)

## Hard Guardrails
- Keep changes scoped; preserve public Application Programming Interfaces unless explicitly requested.
- Keep websocket runtime decisions pure and deterministic.
- Keep side effects in interpreters/infrastructure boundaries only.
- Keep signing payload behavior consensus-safe and covered by parity tests when changed.
- Keep interaction responsiveness deterministic: user-visible state updates must precede heavy subscription/fetch work.

## UI Tasks
When work touches `/hyperopen/src/hyperopen/views/**`, `/hyperopen/src/styles/**`, or user interaction flows:
1. Follow `/hyperopen/docs/FRONTEND.md`.
2. Then apply `/hyperopen/docs/agent-guides/ui-foundations.md`.
3. Then apply `/hyperopen/docs/agent-guides/trading-ui-policy.md`.

## Planning Workflow
- Complex work must use an ExecPlan shaped by `/hyperopen/.agents/PLANS.md`.
- Store active plans in `/hyperopen/docs/exec-plans/active/`.
- Move completed plans to `/hyperopen/docs/exec-plans/completed/`.
- Track known debt in `/hyperopen/docs/exec-plans/tech-debt-tracker.md`.

## Required Validation Gates
- `npm run check`
- `npm test`
- `npm run test:websocket`

## Documentation Hygiene
- Use repo-root paths in docs (for example `/hyperopen/docs/RELIABILITY.md`), never machine-specific absolute paths.
- Keep governed docs current with front matter ownership and review metadata.
- Keep index docs updated when adding/moving canonical documents.
