---
owner: platform
status: canonical
last_reviewed: 2026-03-03
review_cycle_days: 90
source_of_truth: true
---

# Planning and Execution

## Scope
This document governs planning artifacts for implementation work.

## ExecPlan Contract
- ExecPlans must follow `/hyperopen/.agents/PLANS.md`.
- Use an ExecPlan for complex features and significant refactors.

## Tracking Boundary
- Issue lifecycle tracking lives in `/hyperopen/docs/WORK_TRACKING.md` and `bd`.
- ExecPlan checklists/progress entries are required implementation artifacts and do not replace `bd` issue status tracking.

## Storage Layout
- Active plans: `/hyperopen/docs/exec-plans/active/`
- Completed plans: `/hyperopen/docs/exec-plans/completed/`
- Debt tracker: `/hyperopen/docs/exec-plans/tech-debt-tracker.md`

## Workflow
1. Capture intent, assumptions, and acceptance criteria in an active plan.
2. Keep progress, discoveries, decision log, and retrospective updated while implementing.
3. Move the plan to completed after acceptance criteria pass.
