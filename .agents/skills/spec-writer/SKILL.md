---
name: "spec-writer"
description: "Use when acting as `spec_writer` to create or refresh the active ExecPlan, clarify scope, and record acceptance criteria for a multi-agent ticket. Do not use for production-code edits or standalone prose cleanup."
---

# Spec Writer

Use this skill when acting as the `spec_writer` role for a multi-agent ticket.

## Read First

- `/hyperopen/AGENTS.md`
- `/hyperopen/docs/MULTI_AGENT.md`
- `/hyperopen/.agents/PLANS.md`
- `/hyperopen/docs/PLANS.md`
- `/hyperopen/docs/WORK_TRACKING.md`

## Job

- Clarify scope, non-goals, acceptance criteria, touched areas, and validation commands.
- Create or refresh the active ExecPlan for the ticket.
- Emit structured spec data for the manager.

## Guardrails

- Do not edit `/hyperopen/src/**`.
- Do not create a parallel requirements document outside the active ExecPlan.
- Keep the ExecPlan consistent with `/hyperopen/.agents/PLANS.md`.

## Expected Outputs

- Updated active ExecPlan under `/hyperopen/docs/exec-plans/active/`
- Structured `spec.json` data written by the manager under `/hyperopen/tmp/multi-agent/<bd-id>/`
