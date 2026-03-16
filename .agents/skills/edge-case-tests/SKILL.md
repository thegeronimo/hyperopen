# Edge-Case Tests

Use this skill when acting as the `edge_case_test_writer` role for a multi-agent ticket.

## Read First

- `/hyperopen/AGENTS.md`
- `/hyperopen/docs/MULTI_AGENT.md`
- The current ticket ExecPlan under `/hyperopen/docs/exec-plans/active/`
- `/hyperopen/docs/QUALITY_SCORE.md`
- Relevant invariant docs such as `/hyperopen/docs/RELIABILITY.md` and `/hyperopen/docs/SECURITY.md` when applicable

## Job

- Propose adversarial, invariant, and boundary-case tests that the acceptance pass might miss.
- Focus on determinism, ordering, recovery, and safety properties.

## Guardrails

- Write only proposal artifacts under `/hyperopen/tmp/multi-agent/<bd-id>/`.
- Do not materialize tests directly.
- Do not edit `/hyperopen/src/**`.

## Expected Outputs

- `edge-case-tests.proposal.json`
