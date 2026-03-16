---
owner: platform
status: canonical
last_reviewed: 2026-03-16
review_cycle_days: 90
source_of_truth: true
---

# Multi-Agent Workflow

## Purpose

Define the canonical multi-agent workflow for Hyperopen so local orchestration, native Codex multi-agent use, and future CI automation all follow the same role boundaries, artifact contract, and gating rules.

## Scope

This document governs repo-local multi-agent work for tickets that are large enough to justify an ExecPlan. It does not replace `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, or `/hyperopen/docs/agent-guides/browser-qa.md`; it layers on top of them.

## Preconditions

- Use a `bd` issue id as the workflow key.
- Complex work still requires an ExecPlan under `/hyperopen/docs/exec-plans/**`.
- Local transient artifacts live under `/hyperopen/tmp/multi-agent/<bd-id>/`.
- Durably useful UI QA notes still live under `/hyperopen/docs/qa/`.
- The manager uses the JavaScript Agents SDK plus `codex mcp-server` for real runs.
- Real runs require `OPENAI_API_KEY` plus a locally available `codex` CLI.

## Phase Order

The pipeline is fixed:

1. `spec_writer` creates or refreshes the active ExecPlan and emits the structured spec artifact.
2. `acceptance_test_writer` and `edge_case_test_writer` run in parallel and emit separate proposal artifacts only.
3. The manager merges the proposals into one approved test contract and rejects overlaps, contradictions, or unclear ownership.
4. `acceptance_test_writer` materializes the failing tests from the frozen contract.
5. The manager verifies the new tests fail before implementation starts.
6. `worker` makes the smallest change that satisfies the frozen contract.
7. `reviewer` and `browser_debugger` run in parallel.
8. The manager records summaries, optional follow-up `bd` issues, and final quality-gate results.

## Role Responsibilities

### `spec_writer`

- Clarifies scope, non-goals, acceptance criteria, touched areas, and validation commands.
- Creates or refreshes the active ExecPlan instead of creating a parallel `REQUIREMENTS.md`.
- Must not edit production code.

### `acceptance_test_writer`

- Produces acceptance/integration test proposals first.
- Materializes the approved failing tests only after the merged contract is frozen.
- Must not edit production code outside approved `test/**` files.

### `edge_case_test_writer`

- Produces adversarial, boundary-case, and invariant test proposals.
- Must not materialize tests directly and must not edit production code.

### `worker`

- The only role allowed to edit `/hyperopen/src/**`.
- Implements the smallest defensible change that satisfies the frozen test contract.
- May update owned test files and the active ExecPlan.

### `reviewer`

- Read-only reviewer for correctness, regressions, security, race conditions, and missing tests.
- Must leave no tracked-file diff.
- Reports findings first and style concerns only when they hide real defects.

### `browser_debugger`

- Uses the existing browser-inspection MCP server and QA contract.
- May write browser artifacts under `/hyperopen/tmp/browser-inspection/**`.
- May write a durable note under `/hyperopen/docs/qa/**` only when the workflow calls for a lasting UI QA record.
- Must not edit application code.

## Allowed Write Surfaces

- `spec_writer`: active ExecPlan path and `/hyperopen/tmp/multi-agent/**`
- `acceptance_test_writer` proposal pass: `/hyperopen/tmp/multi-agent/**`
- `acceptance_test_writer` materialization pass: approved `test/**` files, active ExecPlan path, `/hyperopen/tmp/multi-agent/**`
- `edge_case_test_writer`: `/hyperopen/tmp/multi-agent/**`
- `worker`: `/hyperopen/src/**`, approved `test/**` files, active ExecPlan path, `/hyperopen/tmp/multi-agent/**`
- `reviewer`: no tracked-file writes
- `browser_debugger`: `/hyperopen/tmp/browser-inspection/**`, `/hyperopen/tmp/multi-agent/**`, and optional `/hyperopen/docs/qa/**`

The manager enforces these through phase-level diff gates.

## Artifact Contract

Manager-owned artifacts are JSON and fail closed on schema mismatch:

- `spec.json`
- `acceptance-tests.proposal.json`
- `edge-case-tests.proposal.json`
- `approved-test-contract.json`
- `review-report.json`
- `browser-report.json`
- `run-summary.json`

These files live under `/hyperopen/tmp/multi-agent/<bd-id>/`.

## Failure Semantics

- Real runs fail fast when `OPENAI_API_KEY` is missing.
- Real runs fail fast in a dirty worktree unless explicitly overridden.
- A phase fails if its artifact does not validate.
- A phase fails if its diff touches disallowed paths.
- Proposal merge fails when both test-design roles claim the same concrete test file.
- Browser QA is required only when the spec marks the ticket as UI-facing or interaction-heavy. Otherwise the browser report must explicitly record a skip.

## `bd` Integration

- The issue id is the artifact key and run identifier.
- Follow-up work discovered by `reviewer` or `browser_debugger` should be filed with `discovered-from:<parent-id>`.
- `bd` remains the source of truth for issue lifecycle state; markdown artifacts do not replace it.

## Native Codex Support

- Repo-local Codex project configuration lives in `/hyperopen/.codex/config.toml`.
- Role definitions live under `/hyperopen/agents/*.toml`.
- Repo-local skills live under `/hyperopen/.agents/skills/**`.
- The checked-in role and skill files are the source of truth for native Codex multi-agent usage and for the JavaScript manager.

## Command Surface

- `npm run agent:dry-run -- --issue <bd-id>`
- `npm run agent:ticket -- --issue <bd-id>`
- `npm run agent:resume-ticket -- --issue <bd-id>`
- `npm run test:multi-agent`

For browser QA specifics, follow `/hyperopen/docs/agent-guides/browser-qa.md` and `/hyperopen/docs/runbooks/browser-live-inspection.md`.
