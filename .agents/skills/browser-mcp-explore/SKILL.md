---
name: "browser-mcp-explore"
description: "Use Browser MCP for exploratory browser debugging, live-session inspection, parity compare, design review, and selector or flow discovery before converting stable paths into Playwright."
---

# Browser MCP Explore

Use this skill when the task is better served by the existing browser-inspection MCP server than by a committed Playwright test.

## Read First

- `/hyperopen/AGENTS.md`
- `/hyperopen/docs/BROWSER_TESTING.md`
- `/hyperopen/docs/agent-guides/browser-qa.md`
- `/hyperopen/docs/runbooks/browser-live-inspection.md`

## Use Browser MCP When

- you need live browser attach or inspection of an existing tab
- the work is exploratory, flaky, or still being diagnosed
- you need governed design-review passes, parity compare, or artifact-heavy visual evidence
- you need to discover selectors, reachable states, or exact repro steps before writing Playwright coverage

## Do Not Use Browser MCP When

- the browser flow is already stable and should be committed as regression coverage
- the task explicitly asks for CI-safe or reviewable end-to-end assertions

## Workflow

- Start from the existing browser-inspection commands and MCP server already registered in `/hyperopen/.codex/config.toml`.
- Capture artifacts under `/hyperopen/tmp/browser-inspection/**`.
- Stop sessions before ending the task. Prefer `npm run browser:cleanup` unless you intentionally need to close only one session.
- When a stable path is found, convert that path into Playwright coverage unless the task is explicitly exploratory.
- Keep Browser MCP as the right tool for design review, parity compare, live attach, and one-off investigation.
