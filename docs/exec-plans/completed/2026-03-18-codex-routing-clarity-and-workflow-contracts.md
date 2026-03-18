# Improve Codex Routing Clarity And Workflow Contracts

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-60i5`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

After this change, Codex should have a single, predictable operating contract for this repository instead of a split-brain mix of root instructions, partially broken repo skills, and multi-agent docs that drift from the enforced runtime behavior. A contributor should be able to start a fresh Codex session in `/hyperopen`, ask for the current instructions, and get a concise root contract that routes work toward the intended workflow without relying on inference or stale linked guidance.

The user explicitly asked to review the execution plan before implementation started. That approval was given, the implementation is complete, and this document records the executed rollout and validation evidence.

## Progress

- [x] (2026-03-18 22:40Z) Created and claimed `bd` issue `hyperopen-60i5` for Codex routing clarity and workflow-contract alignment.
- [x] (2026-03-18 22:40Z) Verified the worktree was clean before authoring this plan and confirmed there was no existing active ExecPlan dedicated to this scope.
- [x] (2026-03-18 22:40Z) Re-audited `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/.codex/config.toml`, and the repo-local skill files to ground this plan in the current checked-in behavior.
- [x] (2026-03-18 22:40Z) Verified with fresh `codex exec` runs that project-scoped `.codex/config.toml` is active in this repo and that the current repo skills fail to load because their `SKILL.md` files are missing YAML frontmatter.
- [x] (2026-03-18 22:40Z) Confirmed that the current multi-agent runtime and docs drift: the checked-in manager still implements the older `acceptance_test_writer` materialization flow while the proposed future workflow centers `tdd_test_writer`.
- [x] (2026-03-18 22:40Z) Authored this ExecPlan with the proposed explicit-only workflow-skill model, root-contract rewrite, runtime/doc alignment pass, and fresh-session validation sequence.
- [x] (2026-03-18 22:54Z) Received user approval to begin implementation.
- [x] (2026-03-18 22:54Z) Repaired the existing repo-local skills under `/hyperopen/.agents/skills/**` by adding valid frontmatter, tightening role descriptions, and narrowing `acceptance-tests` to proposal-only behavior.
- [x] (2026-03-18 22:54Z) Added explicit-only workflow skills for feature, bug, and UI orchestration under `/hyperopen/.agents/skills/feature-flow`, `/hyperopen/.agents/skills/bug-flow`, and `/hyperopen/.agents/skills/ui-flow`.
- [x] (2026-03-18 22:54Z) Rewrote `/hyperopen/AGENTS.md` into a short root operating contract whose first screen now carries workflow entry points, write authority, validation gates, and the return contract.
- [x] (2026-03-18 22:54Z) Aligned `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/tools.md`, `/hyperopen/tools/multi-agent/src/manager.mjs`, `/hyperopen/tools/multi-agent/src/codex_roles.mjs`, and the related tests around the explicit-only workflow model and the `tdd_test_writer` RED phase.
- [x] (2026-03-18 23:12Z) Validated the new contract in fresh `codex exec` runs from `/hyperopen` and `/hyperopen/src/hyperopen/views`, confirmed explicit `$feature-flow` invocation works, and recorded that there are still no repo-local `AGENTS.override.md` files.
- [x] (2026-03-18 23:20Z) Installed the locked Node dependency set with `npm ci`, fixed the AGENTS governed-doc markdown-link regression found by `npm run check`, and passed `npm run test:multi-agent`, `npm run check`, `npm test`, and `npm run test:websocket`.

## Surprises & Discoveries

- Observation: project-scoped `.codex/config.toml` is already active in this repo, so trust is not a blocker for this worktree.
  Evidence: fresh `codex exec` runs from `/hyperopen` reported active project-scoped settings from `/hyperopen/.codex/config.toml`, including `features.multi_agent = true`, `agents.max_threads = 6`, `agents.max_depth = 1`, the registered repo-local agents, and the `hyperopen-browser` MCP server.

- Observation: the current repo skills are broken in the exact way the external feedback predicted.
  Evidence: `codex exec` logged `failed to load skill ... missing YAML frontmatter delimited by ---` for `/hyperopen/.agents/skills/acceptance-tests/SKILL.md`, `/hyperopen/.agents/skills/edge-case-tests/SKILL.md`, `/hyperopen/.agents/skills/browser-qa/SKILL.md`, `/hyperopen/.agents/skills/static-review/SKILL.md`, and `/hyperopen/.agents/skills/spec-writer/SKILL.md`.

- Observation: the root `AGENTS.md` is the only repo-local instructions file currently active across the repository tree.
  Evidence: fresh `codex exec` runs from `/hyperopen` and `/hyperopen/src/hyperopen/views` reported `/hyperopen/AGENTS.md` as the only directly active repo instruction file and confirmed there are no nested `AGENTS.override.md` files today.

- Observation: the current root `AGENTS.md` spends its early bytes on navigation and linked-doc indexing instead of routing and ownership rules.
  Evidence: the opening sections of `/hyperopen/AGENTS.md` are `Purpose`, `Source of Truth and Precedence`, `Start Here`, and `Domain Guides`, while dispatch behavior and workflow rules appear later in the file or only by reference.

- Observation: the multi-agent runtime and the desired workflow are not aligned yet.
  Evidence: `/hyperopen/docs/MULTI_AGENT.md` and `/hyperopen/tools/multi-agent/src/manager.mjs` still model `acceptance_test_writer` as the role that materializes failing tests, while the proposed future workflow discussed in this planning session uses `tdd_test_writer` as the RED-phase owner.

- Observation: the current planning docs still create a mild split between the public planning entry point and the detailed ExecPlan contract.
  Evidence: `/hyperopen/AGENTS.md` points users to `/hyperopen/docs/PLANS.md`, while both `/hyperopen/AGENTS.md` and `/hyperopen/docs/PLANS.md` say ExecPlans must follow `/hyperopen/.agents/PLANS.md`.

- Observation: optional configured agents can silently drift away from their configured names because the manager validator originally checked only the required runtime roles.
  Evidence: the checked-in custom agent files for `architect_review`, `clojure_expert`, `documentation_specialist`, `ui_designer`, and `ui_visual_validator` used hyphenated internal `name` values even though `/hyperopen/.codex/config.toml` registered them with underscore keys.

- Observation: this worktree did not have `node_modules` installed, so the required validation gates were initially blocked even though `package.json` and `package-lock.json` already declared the needed dependencies.
  Evidence: the first `npm run test:multi-agent` run failed with `ERR_MODULE_NOT_FOUND` for `zod` and `smol-toml`, `require.resolve('zod')` failed from `/hyperopen`, `package-lock.json` was present, and `node_modules` was absent until `npm ci` installed the locked dependency set.

- Observation: the docs linter requires governed AGENTS references to be actual markdown links, not only plain-text repo-root paths.
  Evidence: the first `npm run check` run failed at `npm run lint:docs` with `missing-agents-link` errors for the required governed docs until `/hyperopen/AGENTS.md` was patched to restore explicit markdown links.

- Observation: the fresh subdirectory instruction check did not reveal any checked-in nested override file; the only extra instruction layer came from the directory-specific AGENTS content supplied in this chat session.
  Evidence: `codex exec -s read-only --cd /hyperopen/src/hyperopen/views "Show which instruction files are active."` reported the root `/hyperopen/AGENTS.md`, no additional repo-local `AGENTS.md` files between root and the views directory, and the path-specific instruction layer provided in-chat.

## Decision Log

- Decision: treat workflow skills as explicit-only entry points rather than implicitly discoverable ambient automation.
  Rationale: this repository wants predictable orchestration, Codex does not spawn subagents automatically, and `agents.max_depth = 1` means the parent thread must explicitly orchestrate the workflow anyway. Explicit invocation reduces routing ambiguity and keeps the root contract honest about what will and will not happen automatically.
  Date/Author: 2026-03-18 / Codex

- Decision: keep “preflight” guidance as troubleshooting and validation material, not as mandatory ceremony before ordinary tasks.
  Rationale: trust/config loading and instruction-chain validation are session-level concerns that matter when behavior looks wrong, after config/instruction changes, and in tooling validation. Turning them into required ritual for every task would create dead process.
  Date/Author: 2026-03-18 / Codex

- Decision: make the operational source of truth the executable workflow artifacts, not the prose docs alone.
  Rationale: drift already exists between `/hyperopen/docs/MULTI_AGENT.md` and `/hyperopen/tools/multi-agent/src/manager.mjs`. The intended steady state is that workflow skills and runtime code define behavior, docs summarize when to use that behavior, and tests keep the two aligned.
  Date/Author: 2026-03-18 / Codex

- Decision: start with zero new `AGENTS.override.md` files.
  Rationale: the repo currently has no nested overrides, the root contract plus repo-wide skills should carry most of the routing load, and adding overrides before that simplification would create instruction sprawl instead of clarity.
  Date/Author: 2026-03-18 / Codex

- Decision: use repo-root paths in all committed docs and planning artifacts.
  Rationale: `/hyperopen/AGENTS.md` already forbids machine-specific absolute paths in repository docs, and this work directly touches the most visible instruction documents.
  Date/Author: 2026-03-18 / Codex

- Decision: validate every configured custom agent identity, not just the manager-required runtime roles.
  Rationale: the manager only executes a subset of roles directly, but the repo contract still needs prompts, docs, tests, and configured agent files to agree on exact agent `name` values. Identity validation catches silent drift without requiring every optional role to satisfy the manager-only runtime schema.
  Date/Author: 2026-03-18 / Codex

## Outcomes & Retrospective

Implementation is complete in the working tree. The repo-local skill files now load cleanly, the new explicit-only workflow skills are checked in, the root `AGENTS.md` now leads with the operating contract instead of a table of contents, and the manager/test contract now treats `tdd_test_writer` as the RED-phase owner instead of `acceptance_test_writer`.

Fresh-session Codex validation passed from repo root and from a representative UI subdirectory, explicit `$feature-flow` invocation behaved as intended, and the required repository gates passed after restoring the governed markdown links in `/hyperopen/AGENTS.md` and installing the locked Node dependency set with `npm ci`.

No nested `AGENTS.override.md` file was needed. The validated root contract plus repo-wide explicit workflow skills were enough to remove the routing ambiguity that motivated this work.

## Context and Orientation

The current root instructions live in `/hyperopen/AGENTS.md`. That file is automatically loaded by Codex and, in this repository, it is currently the only directly active repo instruction file. It contains important guardrails, but its top section is optimized as a table of contents rather than as a compact operating contract. Because Codex merges instruction files from repo root to the current directory and stops once the combined byte budget is exhausted, the top of `/hyperopen/AGENTS.md` is the highest-leverage place to put routing and ownership rules.

The detailed multi-agent prose lives in `/hyperopen/docs/MULTI_AGENT.md`. The detailed planning contract lives in `/hyperopen/docs/PLANS.md` and `/hyperopen/.agents/PLANS.md`. The repo-local Codex agent registry lives in `/hyperopen/.codex/config.toml`, with custom-agent definitions under `/hyperopen/.codex/agents/*.toml`. The checked-in repo skills live under `/hyperopen/.agents/skills/**`.

In this repository, a “workflow skill” means a repo-local skill that packages an orchestration pattern such as feature work, bug investigation, or UI work. A “root operating contract” means the first screen of `/hyperopen/AGENTS.md`, where Codex should immediately see workflow entry points, write authority, validation gates, and the return contract. An “executable workflow surface” means the files that actually control or embody orchestration behavior: workflow `SKILL.md` files, `/hyperopen/.codex/config.toml`, `/hyperopen/.codex/agents/*.toml`, and the manager/runtime code under `/hyperopen/tools/multi-agent/`.

The repo-local skill metadata is now repaired in the working tree, but fresh-session validation still needs to confirm that Codex loads those skills cleanly. The manager code has also been updated in the working tree so the RED phase now belongs to `tdd_test_writer` rather than `acceptance_test_writer`.

## Plan of Work

The first milestone was skill repair and workflow entry-point creation. The second milestone was the root-contract rewrite. The third milestone was workflow convergence across docs, manager code, and tests. The final milestone, now complete, was validation and override reassessment: fresh Codex runs from `/hyperopen` and `/hyperopen/src/hyperopen/views` reflected the intended contract, the targeted multi-agent tests and required repository gates passed, and no local `AGENTS.override.md` file was justified.

## Concrete Steps

Work from `/hyperopen`.

1. Keep this ExecPlan current.

2. Repair the existing repo skills:

    `/hyperopen/.agents/skills/spec-writer/SKILL.md`
    `/hyperopen/.agents/skills/acceptance-tests/SKILL.md`
    `/hyperopen/.agents/skills/edge-case-tests/SKILL.md`
    `/hyperopen/.agents/skills/browser-qa/SKILL.md`
    `/hyperopen/.agents/skills/static-review/SKILL.md`

   Add YAML frontmatter with `name` and `description`, keep the instructions focused, and ensure the descriptions clearly state when the skill should and should not trigger.

3. Add new workflow skills under `/hyperopen/.agents/skills/` for:

    `$feature-flow`
    `$bug-flow`
    `$ui-flow`

   Configure them as explicit-only invocation if an `agents/openai.yaml` policy file is used.

4. Rewrite only the opening section of:

    `/hyperopen/AGENTS.md`

   Keep the first screen limited to workflow entry points, write authority, validation gates, and return contract. Move or compress long navigation/tutorial material below that line.

5. Align workflow docs and runtime behavior in:

    `/hyperopen/docs/MULTI_AGENT.md`
    `/hyperopen/docs/PLANS.md`
    `/hyperopen/tools/multi-agent/src/manager.mjs`
    `/hyperopen/tools/multi-agent/src/codex_roles.mjs`
    `/hyperopen/tools/multi-agent/test/**`

   Ensure that docs describe the enforced workflow, not an aspirational one.

6. Validate in fresh Codex runs and normal repository gates:

    cd /hyperopen
    env -u TERM codex exec -s read-only --cd /hyperopen "Summarize the current instructions."
    env -u TERM codex exec -s read-only --cd /hyperopen/src/hyperopen/views "Show which instruction files are active."
    npm run test:multi-agent
    npm run check
    npm test
    npm run test:websocket

7. Reassess whether any nested `AGENTS.override.md` file is still justified. Add one only if a subtree still has real routing rules that cannot live in the root contract plus repo-wide skills.

## Validation and Acceptance

Acceptance is not “the docs look nicer.” Acceptance means a fresh Codex run in `/hyperopen` and a fresh run in a representative subdirectory both reflect the intended contract.

At minimum, the following must be true:

1. `codex exec` no longer logs repo-skill frontmatter failures for `/hyperopen/.agents/skills/**`.
2. A fresh run from `/hyperopen` reports a short root operating contract whose first screen contains workflow entry points, write authority, validation gates, and return contract rather than a doc index.
3. A fresh run from `/hyperopen/src/hyperopen/views` still picks up the root contract and the UI-specific governed docs without ambiguity.
4. `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/PLANS.md`, and the manager/runtime code all describe or implement the same real workflow.
5. The repository passes:

    cd /hyperopen
    npm run test:multi-agent
    npm run check
    npm test
    npm run test:websocket

6. If no nested override is added, the resulting behavior is still clear enough that the routing ambiguity identified in this planning session is materially reduced. If an override is added, it must be justified by genuinely local rules rather than by convenience.

## Idempotence and Recovery

This work is safe to stage incrementally. Skill frontmatter additions and description edits are ordinary tracked-file changes and can be repeated safely. Rewriting the top of `/hyperopen/AGENTS.md` is also idempotent as long as the same short-contract structure is preserved. The main recovery rule is to avoid a half-migrated state where docs claim a new workflow but the manager/runtime and tests still enforce the old one. If the convergence pass cannot land fully, keep the docs honest about any remaining compatibility path and defer the unfinished workflow transition behind the existing enforced behavior.

Fresh-session validation matters here. After changing `/hyperopen/AGENTS.md` or `/hyperopen/.codex/config.toml`, restart the Codex run or open a new `codex exec` invocation before concluding that the new behavior is or is not active. Skill-content changes should be detected automatically, but if a change does not appear, restart and retest before treating it as a product bug.

## Artifacts and Notes

Key evidence captured so far:

- Fresh `codex exec -s read-only --cd /hyperopen "Summarize the current instructions."` now reports the short root operating contract centered on workflow skills, write authority, validation gates, and return contract.
- Fresh `codex exec -s read-only --cd /hyperopen/src/hyperopen/views "Show which instruction files are active."` confirmed that `/hyperopen/AGENTS.md` remains the only checked-in repo instruction file active for that subtree; no nested override was added.
- Fresh `codex exec -s read-only --cd /hyperopen "$feature-flow ..."` used the explicit workflow skill and summarized the intended feature phases, including `tdd_test_writer` as the RED-phase owner.
- `npm ci` installed the locked dependency set from `/hyperopen/package-lock.json` so the required Node-based gates could run in this worktree.
- `npm run test:multi-agent` passed after the dependency install.
- `npm run check` initially failed on `missing-agents-link` errors in `/hyperopen/AGENTS.md`, then passed after restoring the governed markdown links.
- `npm test` passed with 2497 tests and 13166 assertions.
- `npm run test:websocket` passed with 396 tests and 2263 assertions.

## Interfaces and Dependencies

This work should not add new third-party libraries. It is expected to touch:

- `/hyperopen/AGENTS.md`
- `/hyperopen/docs/MULTI_AGENT.md`
- `/hyperopen/docs/PLANS.md`
- `/hyperopen/.agents/PLANS.md` only if a narrow clarification is required after the public planning contract is tightened
- `/hyperopen/.agents/skills/**`
- `/hyperopen/.codex/config.toml` only if workflow-skill or agent integration needs a config-level adjustment
- `/hyperopen/tools/multi-agent/src/manager.mjs`
- `/hyperopen/tools/multi-agent/src/codex_roles.mjs`
- `/hyperopen/tools/multi-agent/test/**`

The important interface rule is that prompts, docs, tests, and runtime code must use exact agent `name` values. Filenames are secondary hygiene. The important documentation rule is that committed files must use repo-root paths such as `/hyperopen/docs/MULTI_AGENT.md`, never machine-specific absolute paths.

Revision note: created this ExecPlan on 2026-03-18 after an instruction-chain audit, repo-skill loading audit, and workflow-alignment review to stage the Codex clarity work behind an explicit user approval gate.
Revision note: updated this ExecPlan on 2026-03-18 22:54Z after implementation began to record the completed skill repair, explicit workflow skills, root-contract rewrite, and runtime/doc alignment work before fresh-session validation.
