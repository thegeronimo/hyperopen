# CLAUDE.md

See @AGENTS.md for the canonical operating contract for this repository
(workflow entry points, write authority, required validation gates, and
browser-testing routing). Treat it as the source of truth.

## Reading AGENTS.md from Claude Code

AGENTS.md is written for Codex, so some of its names have no direct
counterpart in a Claude Code session:

- Workflow skills (`$feature-flow`, `$bug-flow`, `$ui-flow`, `playwright-e2e`,
  `browser-mcp-explore`) live in `.agents/skills/<name>/SKILL.md`, which Claude
  Code does not load as skills. When AGENTS.md routes you to one, read that
  file and follow it.
- The agent roster, `agents.max_depth`, and the Write Authority section govern
  Codex multi-agent runs defined in `.codex/`. In Claude Code the main session
  edits `/hyperopen/src/**` directly; subagents you start for review or
  exploration stay read-only.
- Everything else (validation gates, browser-testing routing, git push and
  rebase rules, ExecPlans) applies unchanged.
