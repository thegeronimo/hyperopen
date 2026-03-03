---
owner: platform
status: canonical
last_reviewed: 2026-03-03
review_cycle_days: 90
source_of_truth: true
---

# Hyperopen AGENTS

## Purpose
This file is the agent entry point for this repository. It is a table of contents, not an encyclopedia.

## Source of Truth and Precedence
- Canonical requirements live in `/hyperopen/ARCHITECTURE.md` and `/hyperopen/docs/**`.
- Task-specific user/developer instructions override repository docs for the current task.

## Start Here
1. [Architecture Map](/hyperopen/ARCHITECTURE.md)
2. [Reliability Invariants](/hyperopen/docs/RELIABILITY.md)
3. [Security and Signing Safety](/hyperopen/docs/SECURITY.md)
4. [Quality Scorecard](/hyperopen/docs/QUALITY_SCORE.md)
5. [Planning and Execution](/hyperopen/docs/PLANS.md)
6. [Tooling and Agent Capabilities](/hyperopen/docs/tools.md)
7. [Work Tracking and Session Handoff](/hyperopen/docs/WORK_TRACKING.md)

## Domain Guides
- Architecture boundaries: [ARCHITECTURE.md](/hyperopen/ARCHITECTURE.md)
- Design foundations and beliefs: [DESIGN.md](/hyperopen/docs/DESIGN.md) and [design docs index](/hyperopen/docs/design-docs/index.md)
- Prior AGENTS section map: [AGENTS Section Reindex Map](/hyperopen/docs/design-docs/agents-section-index.md)
- Frontend policy: [FRONTEND.md](/hyperopen/docs/FRONTEND.md)
- Product specs and roadmap intent: [PRODUCT_SENSE.md](/hyperopen/docs/PRODUCT_SENSE.md) and [product specs index](/hyperopen/docs/product-specs/index.md)
- Reliability invariants and runtime behavior: [RELIABILITY.md](/hyperopen/docs/RELIABILITY.md)
- Security and signing invariants: [SECURITY.md](/hyperopen/docs/SECURITY.md)
- Quality posture and test expectations: [QUALITY_SCORE.md](/hyperopen/docs/QUALITY_SCORE.md)
- Planning and execution artifacts: [PLANS.md](/hyperopen/docs/PLANS.md)
- Issue tracking and handoff workflow: [WORK_TRACKING.md](/hyperopen/docs/WORK_TRACKING.md)
- References and external anchors: [references index](/hyperopen/docs/references/index.md)

## Hard Guardrails
- Keep changes scoped; preserve public Application Programming Interfaces unless explicitly requested.
- Keep websocket runtime decisions pure and deterministic.
- Keep side effects in interpreters/infrastructure boundaries only.
- Keep signing payload behavior consensus-safe and covered by parity tests when changed.
- Keep interaction responsiveness deterministic: user-visible state updates must precede heavy subscription/fetch work.

## UI Tasks
When work touches `/hyperopen/src/hyperopen/views/**`, `/hyperopen/src/styles/**`, or user interaction flows:
1. Follow `/hyperopen/docs/FRONTEND.md`.
2. Then apply `/hyperopen/docs/agent-guides/ui-foundations.md`.
3. Then apply `/hyperopen/docs/agent-guides/trading-ui-policy.md`.

## Planning Workflow
- Complex work must use an ExecPlan shaped by `/hyperopen/.agents/PLANS.md`.
- Store active plans in `/hyperopen/docs/exec-plans/active/`.
- Move completed plans to `/hyperopen/docs/exec-plans/completed/`.
- Track known debt in `/hyperopen/docs/exec-plans/tech-debt-tracker.md`.

## Work Tracking
- Issue tracking source of truth is `/hyperopen/docs/WORK_TRACKING.md` (`bd`).
- Use that doc for rationale, quick-start commands, issue types/priorities, and sync/handoff workflow.
- ExecPlan checklists in `/hyperopen/docs/exec-plans/**` remain required planning artifacts, not issue tracking.
- Do not maintain duplicate markdown backlog or TODO trackers outside documented planning artifacts.

## Required Validation Gates
- `npm run check`
- `npm test`
- `npm run test:websocket`

## Documentation Hygiene
- Use repo-root paths in docs (for example `/hyperopen/docs/RELIABILITY.md`), never machine-specific absolute paths.
- Keep governed docs current with front matter ownership and review metadata.
- Keep index docs updated when adding/moving canonical documents.

<!-- BEGIN BEADS INTEGRATION -->
## Issue Tracking with bd (beads)

**IMPORTANT**: This project uses **bd (beads)** for ALL issue tracking. Do NOT use markdown TODOs, task lists, or other tracking methods.

### Why bd?

- Dependency-aware: Track blockers and relationships between issues
- Git-friendly: Dolt-powered version control with native sync
- Agent-optimized: JSON output, ready work detection, discovered-from links
- Prevents duplicate tracking systems and confusion

### Quick Start

**Check for ready work:**

```bash
bd ready --json
```

**Create new issues:**

```bash
bd create "Issue title" --description="Detailed context" -t bug|feature|task -p 0-4 --json
bd create "Issue title" --description="What this issue is about" -p 1 --deps discovered-from:bd-123 --json
```

**Claim and update:**

```bash
bd update <id> --claim --json
bd update bd-42 --priority 1 --json
```

**Complete work:**

```bash
bd close bd-42 --reason "Completed" --json
```

### Issue Types

- `bug` - Something broken
- `feature` - New functionality
- `task` - Work item (tests, docs, refactoring)
- `epic` - Large feature with subtasks
- `chore` - Maintenance (dependencies, tooling)

### Priorities

- `0` - Critical (security, data loss, broken builds)
- `1` - High (major features, important bugs)
- `2` - Medium (default, nice-to-have)
- `3` - Low (polish, optimization)
- `4` - Backlog (future ideas)

### Workflow for AI Agents

1. **Check ready work**: `bd ready` shows unblocked issues
2. **Claim your task atomically**: `bd update <id> --claim`
3. **Work on it**: Implement, test, document
4. **Discover new work?** Create linked issue:
   - `bd create "Found bug" --description="Details about what was found" -p 1 --deps discovered-from:<parent-id>`
5. **Complete**: `bd close <id> --reason "Done"`

### Auto-Sync

bd automatically syncs via Dolt:

- Each write auto-commits to Dolt history
- Use `bd dolt push`/`bd dolt pull` for remote sync
- No manual export/import needed!

### Important Rules

- ✅ Use bd for ALL task tracking
- ✅ Always use `--json` flag for programmatic use
- ✅ Link discovered work with `discovered-from` dependencies
- ✅ Check `bd ready` before asking "what should I work on?"
- ❌ Do NOT create markdown TODO lists
- ❌ Do NOT use external issue trackers
- ❌ Do NOT duplicate tracking systems

For more details, see README.md and docs/QUICKSTART.md.

## Landing the Plane (Session Completion)

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd sync
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds

<!-- END BEADS INTEGRATION -->
