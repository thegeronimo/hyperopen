---
owner: platform
status: canonical
last_reviewed: 2026-03-04
review_cycle_days: 90
source_of_truth: true
---

# Work Tracking and Session Handoff

## Purpose
Define one source of truth for issue tracking and remove ambiguity between `bd` issue lifecycle tracking and markdown planning artifacts.

## Scope and Precedence
- This document governs issue tracking, dependency tracking, and session handoff expectations.
- Planning artifacts still follow `/hyperopen/docs/PLANS.md` and `/hyperopen/.agents/PLANS.md`.
- If guidance conflicts, task-specific user/developer instructions take precedence for the current task.

## `bd` Issue Tracking Source of Truth
- Use `bd` for all backlog, bug, feature, and follow-up issue tracking.
- Do not maintain a parallel issue queue in markdown.
- Use `--json` with `bd` commands when output is consumed programmatically by agents.

## Why `bd`
- Dependency-aware tracking: model blocked/unblocked relationships directly.
- Agent-friendly command surface: deterministic JSON output for automation.
- Local-first tracking: `bd` state stays in-repo without requiring remote sync.
- Single tracker discipline: prevents split status across docs, notes, and external systems.

## Quick Start

### Check ready work
Use this first to find unblocked issues:

`bd ready --json`

### Create new issues
Create explicitly typed issues with priority and context:

`bd create "Issue title" --description="Detailed context" -t bug|feature|task|epic|chore -p 0-4 --json`

When new work is discovered while implementing another issue, link it:

`bd create "Found issue" --description="What was discovered" -p 1 --deps discovered-from:<parent-id> --json`

### Claim and update
Claim atomically and keep status fields current:

`bd update <id> --claim --json`

Examples:
- `bd update bd-42 --priority 1 --json`
- `bd update bd-42 --assignee <owner> --json`

### Complete work
Close completed items with a reason:

`bd close <id> --reason "Completed" --json`

## Issue Types
- `bug`: broken behavior
- `feature`: net-new functionality
- `task`: implementation, testing, refactor, docs work item
- `epic`: parent issue that tracks multiple related items
- `chore`: maintenance or operational work

## Priority Scale
- `0`: critical (security, data loss, broken build/release)
- `1`: high (major capability or significant bug)
- `2`: medium (default)
- `3`: low (polish/optimization)
- `4`: backlog (not currently scheduled)

## Workflow for Agents
1. Check ready work: `bd ready --json`
2. Claim issue: `bd update <id> --claim --json`
3. Implement and validate.
4. Link discovered follow-up work using `discovered-from:<parent-id>`.
5. Close completed issue: `bd close <id> --reason "Completed" --json`

## Local-Only Behavior
- `bd` is local-only in this repository.
- Do not run `bd sync` (removed from current `bd` CLI).
- Do not run `bd dolt push`/`bd dolt pull` unless explicitly requested for a migration.

## Important Rules
- Use `bd` for all issue lifecycle tracking.
- Use `--json` for agent/programmatic invocations.
- Link discovered work to its parent issue with `discovered-from`.
- Do not create duplicate trackers in markdown or external tools.

## Markdown Artifacts: Allowed vs Disallowed

Allowed markdown usage (not issue tracking):
- ExecPlan checklists and progress logs under `/hyperopen/docs/exec-plans/**` required by `/hyperopen/.agents/PLANS.md`
- Governance/invariant checklists in canonical docs (for example `/hyperopen/docs/RELIABILITY.md`, `/hyperopen/ARCHITECTURE.md`)
- Product/spec acceptance checklists that describe scope or validation criteria

Disallowed markdown usage (issue tracking anti-pattern):
- Ad-hoc TODO/backlog/task lists in docs, PR descriptions, or notes used as the source of open work
- Tracking issue ownership/status in markdown when the same work should be tracked in `bd`
- Creating a second tracker alongside `bd`

Practical rule:
- If an item requires future follow-up beyond the current change, create or link a `bd` issue id.
- Markdown may reference the `bd` id for context, but `bd` remains the status source of truth.

## Session Completion and Handoff
When finishing a coding session or handing work to another contributor:
1. File or link `bd` issues for all remaining follow-up work.
2. Run required quality gates when code changed:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
3. Update `bd` issue status (close completed work, update remaining work).
4. Pull/rebase and push only when the user explicitly requests remote sync in the current session:
   - `git pull --rebase`
   - `git push`
   - `git status` (confirm clean state and up-to-date branch)
5. Provide handoff notes with relevant `bd` ids and any blockers.

If remote sync is not explicitly requested, stop at local commit and provide handoff notes.

If push is explicitly requested but cannot complete due environment or permissions, record the blocker explicitly in the handoff and in `bd`.

## Shared Agent Command Phrases
- Machine-readable registry: `/hyperopen/command-phrases.edn`
- Lookup command: `/hyperopen/tools/phrase get "<phrase>"`
- Canonical long-form intent and policy live in this section; keep it aligned with the registry.
- Registry schema: `:schema-version 2` with `:commands` and `:alias->id`.
- Store aliases in normalized form (trimmed, single-space, lowercase) for direct lookup.

### `land the worktree`
- Registry id: `land-the-worktree`
- Alias: `$land`
- Scope: local integration cleanup for commit/rebase/fast-forward merge/worktree cleanup.

Long-form workflow:
1. If the current worktree is detached `HEAD`, create an ephemeral branch from current `HEAD` (for example `codex/land-<timestamp>`).
2. Commit staged changes on the current branch (`git commit ...`). Do not auto-stage files.
3. Rebase the working branch onto local `main` (`git rebase main`).
4. If rebase succeeds, merge into local `main` with fast-forward only:
   - `git checkout main`
   - `git merge --ff-only <working-branch>`
5. Delete the feature worktree and branch:
   - `git worktree remove <feature-worktree-path>` (run from a different worktree)
   - `git branch -d <working-branch>`

Guardrails:
- If unstaged changes exist, stop and request stage/discard before rebase.
- Stop immediately on rebase/merge conflicts; do not force-delete branch/worktree.
- Push behavior is separate unless explicitly requested.
