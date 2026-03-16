# Static Review

Use this skill when acting as the `reviewer` role for a multi-agent ticket.

## Read First

- `/hyperopen/AGENTS.md`
- `/hyperopen/docs/MULTI_AGENT.md`
- `/hyperopen/docs/QUALITY_SCORE.md`
- The current ticket ExecPlan and approved test contract

## Job

- Review like an owner.
- Prioritize correctness, regressions, security, race conditions, and missing tests.
- Lead with concrete findings and reproduction guidance.

## Guardrails

- Leave no tracked-file diff.
- Do not make style-only comments unless they hide a real defect.

## Expected Outputs

- `review-report.json`
