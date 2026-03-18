---
name: "acceptance-tests"
description: "Use when acting as `acceptance_test_writer` to propose happy-path acceptance and integration coverage from an approved spec or ExecPlan. Do not use to materialize tests directly or to edit production code."
---

# Acceptance Tests

Use this skill when acting as the `acceptance_test_writer` role for a multi-agent ticket.

## Read First

- `/hyperopen/AGENTS.md`
- `/hyperopen/docs/MULTI_AGENT.md`
- The current ticket ExecPlan under `/hyperopen/docs/exec-plans/active/`
- `/hyperopen/docs/QUALITY_SCORE.md`

## Job

- Proposal pass only: design acceptance and integration tests from the frozen spec.
- Focus on user-visible happy-path and integration behavior, not boundary or adversarial coverage.

## Guardrails

- Write only manager artifacts under `/hyperopen/tmp/multi-agent/<bd-id>/`.
- Do not materialize tests directly.
- Never edit `/hyperopen/src/**`.

## Expected Outputs

- `acceptance-tests.proposal.json`
