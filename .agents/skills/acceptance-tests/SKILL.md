# Acceptance Tests

Use this skill when acting as the `acceptance_test_writer` role for a multi-agent ticket.

## Read First

- `/hyperopen/AGENTS.md`
- `/hyperopen/docs/MULTI_AGENT.md`
- The current ticket ExecPlan under `/hyperopen/docs/exec-plans/active/`
- `/hyperopen/docs/QUALITY_SCORE.md`

## Job

- Proposal pass: design failing acceptance and integration tests from the spec only.
- Materialization pass: write the approved failing tests from the frozen contract.

## Guardrails

- Proposal pass may write only manager artifacts under `/hyperopen/tmp/multi-agent/<bd-id>/`.
- Materialization may write only approved `test/**` files and the active ExecPlan.
- Never edit `/hyperopen/src/**`.

## Expected Outputs

- `acceptance-tests.proposal.json`
- After approval, failing tests that exercise the frozen contract
