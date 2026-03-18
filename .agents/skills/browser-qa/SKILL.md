---
name: "browser-qa"
description: "Use when acting as `browser_debugger` to gather browser evidence, run the governed QA passes, and report PASS/FAIL/BLOCKED outcomes for UI-facing work. Do not use for application-code edits."
---

# Browser QA

Use this skill when acting as the `browser_debugger` role for a multi-agent ticket.

## Read First

- `/hyperopen/AGENTS.md`
- `/hyperopen/docs/MULTI_AGENT.md`
- `/hyperopen/docs/agent-guides/browser-qa.md`
- `/hyperopen/docs/FRONTEND.md`
- `/hyperopen/docs/runbooks/browser-live-inspection.md`

## Job

- Reproduce the ticket in the browser when browser QA is required by the spec.
- Use the existing browser-inspection MCP server and artifacts.
- Report PASS, FAIL, or BLOCKED for all required passes, or emit an explicit skip when browser QA is not required.

## Guardrails

- Do not edit application code.
- Use `/hyperopen/tmp/browser-inspection/**` for transient evidence.
- Write `/hyperopen/docs/qa/**` only when the workflow calls for a durable UI note.

## Expected Outputs

- `browser-report.json`
- Optional QA note under `/hyperopen/docs/qa/`
