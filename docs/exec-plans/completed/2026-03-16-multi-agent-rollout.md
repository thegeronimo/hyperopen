# Multi-Agent Rollout Scaffold

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and executes `bd` issue `hyperopen-q0qy`.

## Purpose / Big Picture

Hyperopen already has agent-legible docs, structured planning, browser MCP tooling, and deterministic quality gates. This change adds the missing repo-local multi-agent contract so one `bd` ticket can move through a repeatable local pipeline: ExecPlan creation, separate test-design proposals, a merged failing-test contract, implementation, review, and browser QA or an explicit browser-QA skip.

After this change, a contributor can run repo-local multi-agent commands, inspect structured artifacts under `/hyperopen/tmp/multi-agent/<bd-id>/`, and use the checked-in role and skill files as the source of truth for how each agent is allowed to work.

## Progress

- [x] (2026-03-16 14:41Z) Created and claimed `bd` issue `hyperopen-q0qy` for the multi-agent rollout scaffold.
- [x] (2026-03-16 14:48Z) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/tools.md`, and the browser-inspection runbook to align the rollout with existing repo contracts.
- [x] (2026-03-16 15:02Z) Added `/hyperopen/docs/MULTI_AGENT.md`, repo-local skills under `/hyperopen/.agents/skills/**`, project Codex config at `/hyperopen/.codex/config.toml`, and six role TOMLs under `/hyperopen/agents/`.
- [x] (2026-03-16 15:15Z) Implemented the Node/ESM multi-agent manager under `/hyperopen/tools/multi-agent/` with artifact schemas, TOML/config loading, issue lookup, dry-run support, diff/path gates, and package scripts.
- [x] (2026-03-16 15:30Z) Added targeted Node tests, ran `npm run agent:dry-run -- --issue hyperopen-q0qy`, `npm run test:multi-agent`, `npm run check`, `npm test`, and `npm run test:websocket`, then moved this plan to completed and closed `hyperopen-q0qy`.

## Surprises & Discoveries

- Observation: The local Codex CLI reports the `multi_agent` feature as experimental but enabled.
  Evidence: `codex features list` includes `multi_agent experimental true`.
- Observation: Project-level MCP server declarations are not surfaced by `codex mcp list`, which appears to read only user-scoped registrations.
  Evidence: A temporary repo with `.codex/config.toml` containing `[mcp_servers.demo]` still returned `No MCP servers configured yet` from `codex -C <repo> mcp list`.
- Observation: This environment does not currently expose `OPENAI_API_KEY`, so runtime verification must rely on dry-run paths and unit tests instead of a live Agents SDK run.
  Evidence: `if [ -n "$OPENAI_API_KEY" ]; then ...` returned `OPENAI_API_KEY_MISSING`.
- Observation: The existing `npm run test:runner:generate` step picked up five new test namespaces after adding the multi-agent suite and current chart/vault view tests, increasing the generated namespace count from `333` to `338`.
  Evidence: The command output now reports `Generated test/test_runner_generated.cljs with 338 namespaces.`

## Decision Log

- Decision: Implement the orchestration manager in Node/ESM under `/hyperopen/tools/multi-agent/` and keep the first milestone dry-run and unit-testable without a live model call.
  Rationale: The repo already standardizes on `tools/*.mjs`, and the current environment lacks the API key required for a real Agents SDK run.
  Date/Author: 2026-03-16 / Codex
- Decision: Treat `agents/*.toml` as the manager-readable source of truth for per-role model, reasoning, sandbox, and instructions.
  Rationale: The plan requires exact model ids to live only in role TOMLs and not be duplicated in orchestration code.
  Date/Author: 2026-03-16 / Codex
- Decision: Keep browser QA on the existing browser-inspection MCP server and toolchain instead of introducing a second browser harness.
  Rationale: Hyperopen already has governed browser QA passes, scripts, and artifact conventions under `/hyperopen/tools/browser-inspection/` and `/hyperopen/docs/agent-guides/browser-qa.md`.
  Date/Author: 2026-03-16 / Codex

## Outcomes & Retrospective

Completed. Hyperopen now has a repo-local multi-agent contract, native Codex role and skill files, and a Node/ESM manager that can:

- read `bd` issue context,
- emit structured dry-run artifacts under `/hyperopen/tmp/multi-agent/<bd-id>/`,
- load role config from `/hyperopen/agents/*.toml`,
- validate artifacts with Zod,
- enforce phase-level path gates, and
- fail clearly when live Agents SDK prerequisites are missing.

This reduced overall complexity. Before this change, the repo had browser MCP tooling and strong docs, but no canonical multi-agent workflow, no role source of truth, and no manager-owned artifact contract. After this change, the new orchestration logic is isolated under `/hyperopen/tools/multi-agent/` and the durable policy lives in `/hyperopen/docs/MULTI_AGENT.md`, which keeps the rest of the repo free of ad hoc agent-specific glue.

## Context and Orientation

Hyperopen already keeps durable repo guidance in canonical docs and uses `AGENTS.md` as a table of contents. Complex work must use ExecPlans under `/hyperopen/docs/exec-plans/**`, while issue lifecycle tracking stays in `bd`. The browser inspection subsystem under `/hyperopen/tools/browser-inspection/` already exposes CLI and MCP flows for browser QA, and `/hyperopen/docs/tools.md` is the canonical tool reference.

The new work must fit those existing seams:

- `/hyperopen/AGENTS.md` stays short and points to durable docs.
- `/hyperopen/docs/MULTI_AGENT.md` will become the canonical multi-agent policy document.
- `/hyperopen/.agents/skills/**` will hold repo-local, task-specific skills so rarely used instructions are loaded on demand.
- `/hyperopen/.codex/config.toml` and `/hyperopen/agents/*.toml` will define repo-local roles for native Codex use and for the new orchestration manager to read.
- `/hyperopen/tools/multi-agent/**` will host the new Node/ESM manager, schemas, diff gates, and CLI.
- `/hyperopen/tmp/multi-agent/<bd-id>/` will hold transient run artifacts.

## Plan of Work

First, add the declarative contract surface. Create the multi-agent policy doc, repo-local skill files, project Codex config, and per-role TOMLs. Update `/hyperopen/AGENTS.md` and `/hyperopen/docs/tools.md` so contributors can discover the new workflow from the existing entry points.

Second, implement the orchestration code under `/hyperopen/tools/multi-agent/`. The manager should load ticket context from `bd`, read role TOMLs, define Zod-backed artifacts for spec/test/review/browser reports, enforce phase ordering, and apply diff-based path gates between phases. It should expose a dry-run path that produces synthetic artifacts without model access and a real path that uses the JavaScript Agents SDK with `codex mcp-server`.

Third, add tests for the non-networked parts of the system: contract parsing, proposal merging, path-gate enforcement, role/config loading, and dry-run orchestration. Wire new package scripts so the workflow and its tests are discoverable and runnable through `npm`.

## Concrete Steps

From `/Users/barry/.codex/worktrees/3391/hyperopen`:

    bd show hyperopen-q0qy --json
    npm run agent:dry-run -- --issue hyperopen-q0qy
    npm run test:multi-agent
    npm run check
    npm test
    npm run test:websocket

Expected dry-run behavior:

    - Writes `/hyperopen/tmp/multi-agent/hyperopen-q0qy/`.
    - Produces `spec.json`, proposal artifacts, an approved contract, and a run summary.
    - Does not touch `/hyperopen/src/**`.

## Validation and Acceptance

Acceptance requires:

1. Repo docs and config clearly describe the new multi-agent workflow and role boundaries.
2. `npm run agent:dry-run -- --issue <bd-id>` produces structured artifacts under `/hyperopen/tmp/multi-agent/<bd-id>/`.
3. The manager rejects overlapping test-file ownership and disallowed path edits through unit-tested gate logic.
4. `npm run test:multi-agent` passes.
5. Repository-required gates still pass: `npm run check`, `npm test`, and `npm run test:websocket`.

Because the current environment lacks `OPENAI_API_KEY`, live Agents SDK execution is not part of this turn's acceptance. The shipped code must fail clearly and instructively when the key is missing.

## Idempotence and Recovery

The new dry-run flow is additive. Re-running it overwrites only the current ticket's JSON artifacts under `/hyperopen/tmp/multi-agent/<bd-id>/`, which is already ignored by git. The manager should refuse to run real phases in a dirty worktree by default so phase-level diff gates remain attributable to the current run.

If a real run fails mid-phase, the artifact directory should remain intact for inspection, and `npm run agent:resume-ticket -- --issue <bd-id>` should reuse validated prior artifacts where possible instead of forcing the entire pipeline to restart.

## Artifacts and Notes

Expected artifacts for a ticket run:

- `/hyperopen/tmp/multi-agent/<bd-id>/spec.json`
- `/hyperopen/tmp/multi-agent/<bd-id>/acceptance-tests.proposal.json`
- `/hyperopen/tmp/multi-agent/<bd-id>/edge-case-tests.proposal.json`
- `/hyperopen/tmp/multi-agent/<bd-id>/approved-test-contract.json`
- `/hyperopen/tmp/multi-agent/<bd-id>/review-report.json`
- `/hyperopen/tmp/multi-agent/<bd-id>/browser-report.json`
- `/hyperopen/tmp/multi-agent/<bd-id>/run-summary.json`

## Interfaces and Dependencies

The runtime manager will depend on:

- `@openai/agents` for the JavaScript Agents SDK runtime.
- `zod` for artifact schemas.
- `smol-toml` for reading `/hyperopen/.codex/config.toml` and `/hyperopen/agents/*.toml`.

The public code surface added by this work should include:

- `tools/multi-agent/src/cli.mjs`
- `tools/multi-agent/src/manager.mjs`
- `tools/multi-agent/src/contracts.mjs`
- `tools/multi-agent/src/git_state.mjs`
- `tools/multi-agent/src/codex_roles.mjs`

Revision note: Completed the rollout scaffold after adding the canonical policy doc, repo-local skills and role files, the Node/ESM manager plus tests, validating the dry-run flow, and passing the repo-required gates.
