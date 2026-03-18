---
owner: platform
status: canonical
last_reviewed: 2026-03-18
review_cycle_days: 90
source_of_truth: true
---

# Planning and Execution

## Purpose

This document is the public planning entry point for implementation work in Hyperopen.

## What An ExecPlan Is

An ExecPlan is Hyperopen’s term for a living execution plan: a self-contained implementation artifact that explains the purpose of the change, the files and commands involved, the current progress, the decisions made while working, and the evidence that the result works. It is not an issue tracker.

## Canonical Planning Rule

- Use an ExecPlan for complex features, significant refactors, multi-agent tickets, or risky bug and UI work.
- This file is the public planning contract that other governed docs should point to first.
- The detailed ExecPlan template and writing requirements live in `/hyperopen/.agents/PLANS.md`.

## Required Properties Of Every ExecPlan

- self-contained context and acceptance criteria
- explicit `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` sections
- concrete file paths, commands, and validation expectations
- a live connection to the tracked `bd` work while implementation is active

## Storage Layout

- Active plans: `/hyperopen/docs/exec-plans/active/`
- Completed plans: `/hyperopen/docs/exec-plans/completed/`
- Deferred plans: `/hyperopen/docs/exec-plans/deferred/`
- Debt tracker: `/hyperopen/docs/exec-plans/tech-debt-tracker.md`

## Lifecycle

1. Capture intent, assumptions, and acceptance criteria in an active plan.
2. Link the active plan to live `bd` work and keep its living sections current while implementing.
3. Keep at least one unchecked progress item in an active plan while work remains.
4. Move the plan to completed after acceptance criteria pass.
5. Move stale or intentionally paused planning notes out of active and into deferred.

## Relationship To `bd`

- Issue lifecycle tracking lives in `/hyperopen/docs/WORK_TRACKING.md` and `bd`.
- ExecPlan checklists and progress entries are required implementation artifacts; they do not replace `bd` issue status tracking.
- Markdown may reference `bd` ids for context, but `bd` remains the status source of truth.

## Multi-Agent Integration

- `spec_writer` owns creating or refreshing the active ExecPlan for multi-agent work.
- Workflow skills and the repo-local manager must update the active ExecPlan instead of creating a parallel requirements document.
- Approved test contracts, major scope changes, and important implementation discoveries belong back in the active ExecPlan.

## Validation And Guardrails

- `active` means work is being executed now, not “maybe later” or “historical context.”
- Active ExecPlans must reference at least one live `bd` issue and must retain at least one unchecked progress item.
- `npm run check` enforces the active-plan guardrails through `/hyperopen/dev/check_docs.clj`.
- `completed` is for accepted or otherwise closed historical records.
- `deferred` is for non-active planning notes; `bd` remains the source of truth for whether deferred work is actually open backlog.

## Detailed Template Source

For the full ExecPlan writing contract, formatting rules, and skeleton, follow `/hyperopen/.agents/PLANS.md`.
