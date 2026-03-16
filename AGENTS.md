---
owner: platform
status: canonical
last_reviewed: 2026-03-16
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
2. [Browser Storage Policy](/hyperopen/docs/BROWSER_STORAGE.md)
3. [Reliability Invariants](/hyperopen/docs/RELIABILITY.md)
4. [Security and Signing Safety](/hyperopen/docs/SECURITY.md)
5. [Quality Scorecard](/hyperopen/docs/QUALITY_SCORE.md)
6. [Planning and Execution](/hyperopen/docs/PLANS.md)
7. [Tooling and Agent Capabilities](/hyperopen/docs/tools.md)
8. [Work Tracking and Session Handoff](/hyperopen/docs/WORK_TRACKING.md)
9. [Multi-Agent Workflow](/hyperopen/docs/MULTI_AGENT.md)

## Domain Guides
- Architecture boundaries: [ARCHITECTURE.md](/hyperopen/ARCHITECTURE.md)
- Browser persistence decisions: [BROWSER_STORAGE.md](/hyperopen/docs/BROWSER_STORAGE.md)
- Design foundations and beliefs: [DESIGN.md](/hyperopen/docs/DESIGN.md) and [design docs index](/hyperopen/docs/design-docs/index.md)
- Prior AGENTS section map: [AGENTS Section Reindex Map](/hyperopen/docs/design-docs/agents-section-index.md)
- Frontend policy: [FRONTEND.md](/hyperopen/docs/FRONTEND.md)
- Product specs and roadmap intent: [PRODUCT_SENSE.md](/hyperopen/docs/PRODUCT_SENSE.md) and [product specs index](/hyperopen/docs/product-specs/index.md)
- Reliability invariants and runtime behavior: [RELIABILITY.md](/hyperopen/docs/RELIABILITY.md)
- Security and signing invariants: [SECURITY.md](/hyperopen/docs/SECURITY.md)
- Quality posture and test expectations: [QUALITY_SCORE.md](/hyperopen/docs/QUALITY_SCORE.md)
- Planning and execution artifacts: [PLANS.md](/hyperopen/docs/PLANS.md)
- Issue tracking and handoff workflow: [WORK_TRACKING.md](/hyperopen/docs/WORK_TRACKING.md)
- Multi-agent role, artifact, and gating contract: [MULTI_AGENT.md](/hyperopen/docs/MULTI_AGENT.md)
- References and external anchors: [references index](/hyperopen/docs/references/index.md)

## Hard Guardrails
- Keep changes scoped; preserve public Application Programming Interfaces unless explicitly requested.
- Keep websocket runtime decisions pure and deterministic.
- Keep side effects in interpreters/infrastructure boundaries only.
- Follow `/hyperopen/docs/BROWSER_STORAGE.md` for any browser persistence change; default to `localStorage` only for tiny synchronous preferences and use IndexedDB for growing or high-churn caches.
- Keep signing payload behavior consensus-safe and covered by parity tests when changed.
- Keep interaction responsiveness deterministic: user-visible state updates must precede heavy subscription/fetch work.
- Never run `git pull --rebase` or `git push` unless the user explicitly requests remote sync in the current session.

## UI Tasks
When work touches `/hyperopen/src/hyperopen/views/**`, `/hyperopen/src/styles/**`, or user interaction flows:
1. Follow `/hyperopen/docs/FRONTEND.md`.
2. Apply `/hyperopen/docs/agent-guides/browser-qa.md` for design-system review passes, required widths, and PASS / FAIL / BLOCKED reporting.
3. Then apply `/hyperopen/docs/agent-guides/ui-foundations.md`.
4. Then apply `/hyperopen/docs/agent-guides/trading-ui-policy.md`.
5. Do not conclude a UI review with “looks good” unless every browser-QA pass is explicitly accounted for.

## Planning Workflow
- Complex work must use an ExecPlan shaped by `/hyperopen/.agents/PLANS.md`.
- Store active plans in `/hyperopen/docs/exec-plans/active/`.
- Move completed plans to `/hyperopen/docs/exec-plans/completed/`.
- Move non-active or superseded planning notes to `/hyperopen/docs/exec-plans/deferred/`.
- Active ExecPlans must reference live `bd` work and retain unchecked progress items; `npm run check` enforces this.
- Track known debt in `/hyperopen/docs/exec-plans/tech-debt-tracker.md`.

## Multi-Agent Workflow
- Follow `/hyperopen/docs/MULTI_AGENT.md` for repo-local multi-agent phase order, artifact paths, path gates, and role responsibilities.
- Repo-local on-demand workflows live under `/hyperopen/.agents/skills/**`.
- Native Codex project and role config live under `/hyperopen/.codex/config.toml` and `/hyperopen/agents/*.toml`.

## Work Tracking
- Issue tracking source of truth is `/hyperopen/docs/WORK_TRACKING.md` (`bd`).
- Use that doc for rationale, quick-start commands, issue types/priorities, and local-only handoff workflow.
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
- Local-first: Issues stay in-repo without requiring remote tracker sync
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

### Local-Only Mode

This repository uses `bd` in local-only mode:

- Do not run `bd sync` (not available in current `bd`).
- Do not run `bd dolt push` or `bd dolt pull`.
- Keep git hooks for `bd` uninstalled unless explicitly requested.

### Important Rules

- ✅ Use bd for ALL task tracking
- ✅ Always use `--json` flag for programmatic use
- ✅ Link discovered work with `discovered-from` dependencies
- ✅ Check `bd ready` before asking "what should I work on?"
- ❌ Do NOT create markdown TODO lists
- ❌ Do NOT use external issue trackers
- ❌ Do NOT duplicate tracking systems

For more details, see README.md and `/hyperopen/docs/WORK_TRACKING.md`.

## Landing the Plane (Session Completion)

**When ending a work session**, complete all applicable local handoff steps below.

**WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Remote sync only if explicitly requested by user**:
   ```bash
   git pull --rebase
   git push
   git status  # confirm clean state and up-to-date branch
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All local changes committed; include remote-sync blocker details when push is not requested or not possible
7. **Hand off** - Provide context for next session

### Shared Agent Command Phrases
Resolve any shared agent command phrase with `/hyperopen/tools/phrase get "<phrase>" --suggest`.
Machine-readable registry: `/hyperopen/command-phrases.edn`.
Canonical long-form policy: `/hyperopen/docs/WORK_TRACKING.md#shared-agent-command-phrases`.
Invocation rule: when user input is a short phrase of 2-5 words after normalization, always run phrase lookup before interpreting intent.
Normalization rule: ignore punctuation and hyphenation differences when counting words for this trigger.
If lookup returns an exact phrase command, execute its instructions.
If lookup returns suggested matches, treat the top suggestion as the intended command when its score is greater than `0.80`, and execute its instructions; otherwise continue with normal intent handling.

**CRITICAL RULES:**
- Do not run `git pull --rebase` or `git push` unless the user explicitly instructs remote sync.
- When remote sync is not explicitly requested, stop at local commit + clean handoff notes.
- If remote sync is explicitly requested and fails, record blocker details in handoff and related `bd` issue notes.

<!-- END BEADS INTEGRATION -->
