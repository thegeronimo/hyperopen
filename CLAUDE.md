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
  Codex multi-agent runs defined in `.codex/`. In Claude Code, write authority
  follows "Subagent orchestration" below.
- Everything else (validation gates, browser-testing routing, git push and
  rebase rules, ExecPlans) applies unchanged.

## Subagent orchestration (main session only)

This section applies to the main session. If you are a subagent, ignore it:
do the task in your brief, do not spawn further agents, and report back.

Delegate when the task has independent parts that can run in parallel, needs a
wide search or log/test-output digest whose detail you do not need, or is
finished work that deserves a review not anchored on your own reasoning. Do
the work yourself when it is small, tightly coupled, or quicker to do than to
brief; a subagent starts cold and must rediscover context. Tell the user the
plan first if a task will use more than about 5 subagents.

Pick the model per call with the Agent tool's `model` parameter:

| Work | Model |
| --- | --- |
| Search, locating files, summarizing logs or test output, renames, boilerplate | `haiku` |
| Well-specified implementation, tests, routine refactors, docs | `sonnet` |
| Tricky debugging, cross-module refactors, architecture trade-offs | `opus` |

Repo calibration: view components under `src/hyperopen/views/**` and styles go
to `sonnet`. Websocket runtime, order signing and submission, and optimizer
solver math stay with the main session or an `opus` subagent, never lower.

Who orchestrates depends on the main session's model:

- Fable main session: you plan, write briefs, integrate, and sign off. Before
  finishing a multi-file behavior change, have an `opus` subagent that did not
  write the code review the full diff against the original request.
- Opus main session: you are still the orchestrator and you make the
  decisions. Route work down to `haiku` and `sonnet` as above and do the
  Opus-tier work yourself. Use a read-only `fable` subagent as an advisor:
  before committing to an approach on complex work, after two failed attempts
  at the same problem, and to review a risky change before finishing. It
  starts cold, so give it the goal, the relevant paths, what you tried, and
  the specific question. It returns advice; you decide and act.

Rules for every delegation:

- Briefs are self-contained: goal, file paths, constraints, what done means,
  and the report format. Ask for conclusions and diffs, not file dumps.
- Subagents are read-only unless the brief grants a write scope. At most one
  subagent writes to a given set of files at a time, and the main session does
  not edit those files while it runs. Subagents never commit, push, or switch
  branches.
- Verify each result yourself (read the diff, run the tests) before relying
  on it. The main session runs the required gates.
- If a result is weak, retry once with a sharper brief, then move up one tier
  (`haiku`, `sonnet`, `opus`, then the main session or the Fable advisor).

This does not make `$feature-flow`, `$bug-flow`, or `$ui-flow` ambient. Those
stay explicit-only, as `docs/MULTI_AGENT.md` says.
