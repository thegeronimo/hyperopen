# Consolidate Repo Agent Definitions Under `.codex/agents`

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Linked live work: `hyperopen-0q3i` ("Consolidate repo agent definitions under .codex/agents").

## Purpose / Big Picture

After this work, Hyperopen will have one unambiguous repository location for all checked-in Codex agent definitions: `/hyperopen/.codex/agents/*.toml`. A contributor inspecting the repo or a manager process loading agent roles will no longer need to reconcile two parallel directories (`/hyperopen/agents/*.toml` and `/hyperopen/.codex/agents/*.toml`) to understand which file is authoritative.

The change is intentionally narrow. `/hyperopen/.codex/config.toml` remains the small registry that parent processes read first, but every registered role file now lives under `/hyperopen/.codex/agents/*.toml`. The JavaScript multi-agent manager continues to require the same six workflow roles (`spec_writer`, `acceptance_test_writer`, `edge_case_test_writer`, `worker`, `reviewer`, and `browser_debugger`), only now it resolves them from the config index instead of a hardcoded `/hyperopen/agents/*.toml` contract.

## Progress

- [x] (2026-03-17 23:54Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md` to confirm the required planning and issue-tracking workflow for this refactor.
- [x] (2026-03-17 23:56Z) Created and claimed `hyperopen-0q3i` to track this consolidation in `bd`.
- [x] (2026-03-17 23:58Z) Audited the current source of truth split across `/hyperopen/.codex/config.toml`, `/hyperopen/agents/*.toml`, `/hyperopen/.codex/agents/*.toml`, `/hyperopen/tools/multi-agent/src/codex_roles.mjs`, and the governed docs.
- [x] (2026-03-18 00:05Z) Added the six workflow-role custom-agent files under `/hyperopen/.codex/agents/*.toml` with stable names/descriptions and removed the mirrored tracked files under `/hyperopen/agents/*.toml`.
- [x] (2026-03-18 00:06Z) Refactored `/hyperopen/tools/multi-agent/src/codex_roles.mjs` so required-role validation and loading resolve through `/hyperopen/.codex/config.toml`, reject paths outside `.codex/agents`, and require matching custom-agent `name` fields.
- [x] (2026-03-18 00:06Z) Updated `/hyperopen/.codex/config.toml`, `/hyperopen/tools/multi-agent/src/manager.mjs`, `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, and `/hyperopen/docs/tools.md` so live repo guidance and dry-run artifact metadata only reference `/hyperopen/.codex/agents/*.toml`.
- [x] (2026-03-18 00:07Z) Added focused regression coverage in `/hyperopen/tools/multi-agent/test/codex_roles.test.mjs` and extended `/hyperopen/tools/multi-agent/test/contracts.test.mjs` to pin the new touched-area contract.
- [x] (2026-03-18 00:16Z) Installed local JavaScript dependencies with `npm ci`, then passed `npm run test:multi-agent`, `npm run lint:docs`, `npm test`, `npm run test:websocket`, and `npm run check`.

## Surprises & Discoveries

- Observation: the current multi-agent manager does not merely document `/hyperopen/agents/*.toml`; it hardcodes that directory in both runtime loading and configuration validation.
  Evidence: `/hyperopen/tools/multi-agent/src/codex_roles.mjs` currently defines `expectedRoles` as direct mappings to `agents/spec-writer.toml`, `agents/acceptance-tests.toml`, `agents/edge-case-tests.toml`, `agents/worker.toml`, `agents/reviewer.toml`, and `agents/browser-debugger.toml`.

- Observation: the current worktree already contains uncommitted governed-doc changes that distinguish native Codex custom subagents from manager role files by directory.
  Evidence: `git status --short` showed tracked modifications in `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, and `/hyperopen/docs/tools.md` before implementation started.

- Observation: this worktree began without `node_modules/`, so the multi-agent tests could not import `zod` or `smol-toml` until local dependencies were installed.
  Evidence: `test -d node_modules && echo present || echo missing` returned `missing`, and earlier `npm run test:multi-agent` attempts failed with `ERR_MODULE_NOT_FOUND` for `zod`.

- Observation: `shadow-cljs` emitted a transient jar-manifest `EOFException` while refreshing dependency caches during `npm test`, but compilation and the full test run still completed successfully.
  Evidence: the `npm test` output logged `RuntimeException java.io.EOFException` while reading `.shadow-cljs/jar-manifest/...`, then continued to `[:test] Build completed` and finished with `Ran 2480 tests containing 13006 assertions. 0 failures, 0 errors.`

## Decision Log

- Decision: keep `/hyperopen/.codex/config.toml` as the explicit registry for all repo-scoped agents instead of making the manager crawl `/hyperopen/.codex/agents/*.toml`.
  Rationale: the user explicitly wants a small index the parent can read first, and the existing project config already expresses that registry shape.
  Date/Author: 2026-03-17 / Codex

- Decision: perform a hard cutover in one change by deleting `/hyperopen/agents/*.toml` once equivalent custom-agent files exist under `/hyperopen/.codex/agents/*.toml`.
  Rationale: a temporary compatibility bridge would preserve the current confusion and leave two authoritative-looking directories in the repo.
  Date/Author: 2026-03-17 / Codex

- Decision: intentionally define the manager implementation role as a project-scoped custom agent named `worker`.
  Rationale: OpenAI’s current subagent model allows project-scoped agents to override built-in agent names, and the user explicitly chose to make the repo’s `worker` semantics authoritative inside this project.
  Date/Author: 2026-03-17 / Codex

## Outcomes & Retrospective

The cutover completed as planned. The repo now has one checked-in agent-definition directory, `/hyperopen/.codex/agents/*.toml`, and the six workflow roles are valid Codex custom-agent files instead of a parallel manager-only format. `/hyperopen/.codex/config.toml` remains the explicit registry, but the JavaScript manager now resolves required roles from that registry instead of assuming `/hyperopen/agents/*.toml`.

Overall complexity was reduced. Before this change, contributors and tooling had to reconcile a config index, a native-Codex agent directory, and a second manager-only role directory. After this change, the config index and both runtime/documentation surfaces converge on one agent directory, which removes directory-level duplication without changing the manager’s role names or workflow semantics.

## Context and Orientation

Hyperopen currently has two parallel directories that look like agent sources of truth. `/hyperopen/.codex/agents/*.toml` contains standalone Codex custom-agent files with `name`, `description`, and `developer_instructions`. `/hyperopen/agents/*.toml` contains the six manager workflow roles, but those files omit `name` and `description` because they were originally designed for a repo-local JavaScript loader instead of native Codex custom-agent loading.

The registry that points at both sets of roles is `/hyperopen/.codex/config.toml`. Its `[agents]` table already indexes the six workflow roles plus the additional specialty agents. The JavaScript loader in `/hyperopen/tools/multi-agent/src/codex_roles.mjs` reads that config but still verifies that manager roles point to `/hyperopen/agents/*.toml`, and then loads those files directly. The manager runtime in `/hyperopen/tools/multi-agent/src/agent_runner.mjs` only needs four operational fields from each required role file: `model`, `model_reasoning_effort`, `sandbox_mode`, and `developer_instructions`.

The governed docs that describe this area are `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, and `/hyperopen/docs/tools.md`. They must describe the post-cutover state precisely because they are canonical repo guidance. Historical completed ExecPlans under `/hyperopen/docs/exec-plans/completed/` are evidence of prior decisions and should remain unchanged unless one of them is still used as a live index, which is not the case here.

## Plan of Work

First, create one valid custom-agent file under `/hyperopen/.codex/agents/*.toml` for each workflow role that currently lives under `/hyperopen/agents/*.toml`. Preserve the current model, reasoning, sandbox, and instructions; add the required `name` and `description` fields so each file is both native-Codex-valid and manager-readable. Keep the existing specialty agents in `/hyperopen/.codex/agents/*.toml` unchanged apart from any ordering needed by the registry.

Second, update `/hyperopen/.codex/config.toml` so the six workflow-role `config_file` entries point into `/hyperopen/.codex/agents/*.toml`. Then refactor `/hyperopen/tools/multi-agent/src/codex_roles.mjs` so the required roles are fixed by role key but the file path comes from the config index. Path validation must fail closed when a required entry is missing, points outside `.codex/agents`, uses traversal, or the target file does not parse as a valid custom-agent definition. The parsed result may include extra Codex custom-agent fields, but the loader must return at least the operational subset the manager already consumes.

Third, update any manager-owned metadata or governed docs that still mention `/hyperopen/agents/*.toml`. The dry-run artifact bundle built by `/hyperopen/tools/multi-agent/src/manager.mjs` currently claims that touched areas include `agents/*.toml`; that must change to `.codex/agents/*.toml` so new dry-runs describe the actual repo contract.

Finally, add focused tests under `/hyperopen/tools/multi-agent/test/` to pin the new loader behavior. At minimum, cover successful role resolution through `/.codex/config.toml`, rejection of required-role paths outside `/.codex/agents`, and parsing of manager roles from custom-agent files that include `name` and `description`. Run the repo’s required gates after installing local JavaScript dependencies if needed because `node_modules/` is currently absent in this worktree.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/b957/hyperopen`.

1. Add the active ExecPlan file and live `bd` linkage for `hyperopen-0q3i`.
2. Add the six manager role files under `.codex/agents/` and delete the mirrored files under `agents/`.
3. Update `.codex/config.toml`, `tools/multi-agent/src/codex_roles.mjs`, `tools/multi-agent/src/manager.mjs`, `AGENTS.md`, `docs/MULTI_AGENT.md`, and `docs/tools.md`.
4. Add focused multi-agent tests.
5. Run:
   - `npm ci`
   - `npm run test:multi-agent`
   - `npm run lint:docs`
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
6. Update this ExecPlan’s living sections with final results and close `hyperopen-0q3i` if all work is complete.

## Validation and Acceptance

Acceptance is met when all of the following are true:

1. `git diff --name-only` shows no tracked files under `/hyperopen/agents/` because that directory contract has been removed.
2. `/hyperopen/.codex/config.toml` points every required workflow role at `/.codex/agents/*.toml`.
3. The loader in `/hyperopen/tools/multi-agent/src/codex_roles.mjs` rejects required-role entries outside `/.codex/agents` and successfully loads valid custom-agent files that include `name` and `description`.
4. Governed docs only describe `/.codex/agents/*.toml` as the checked-in repo-scoped agent directory.
5. The validation commands complete successfully.

## Idempotence and Recovery

The file moves are safe to re-run because the final state is additive under `/.codex/agents` and subtractive under `/agents`. If validation fails after the move, keep `/.codex/config.toml` and `codex_roles.mjs` aligned with whichever directory is currently checked in; do not restore a partial dual-directory state. `npm ci` is safe to repeat whenever `node_modules/` is absent or stale.

## Artifacts and Notes

Important pre-change evidence:

    git status --short
    M AGENTS.md
    M docs/MULTI_AGENT.md
    M docs/tools.md

    bd create "Consolidate repo agent definitions under .codex/agents" ... --json
    {"id":"hyperopen-0q3i", ...}

    bd update hyperopen-0q3i --claim --json
    [{"id":"hyperopen-0q3i","status":"in_progress",...}]

## Interfaces and Dependencies

At the end of this work, `/hyperopen/tools/multi-agent/src/codex_roles.mjs` must still export:

    async function readProjectConfig(repoRoot)
    async function validateProjectConfig(repoRoot)
    async function loadRoleConfig(repoRoot, roleName)
    async function loadAllRoleConfigs(repoRoot)

`loadRoleConfig` and `loadAllRoleConfigs` must continue returning objects that provide:

    model
    model_reasoning_effort
    sandbox_mode
    developer_instructions

The underlying role files they parse must now also contain:

    name
    description

Plan revision note: 2026-03-17 23:58Z - Initial plan created for `hyperopen-0q3i` after auditing the split agent-directory contract, the manager loader, and the governed docs.
Plan revision note: 2026-03-18 00:16Z - Updated progress, discoveries, and retrospective after completing the `/.codex/agents` cutover, adding regression coverage, and passing the required validation gates.
