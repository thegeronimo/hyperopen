# Add Missing Bounded-Context Boundary Docs

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan follows `docs/PLANS.md` and `.agents/PLANS.md`. The live tracking issue is `hyperopen-2lnh`.

## Purpose / Big Picture

Hyperopen uses `BOUNDARY.md` files to tell future contributors what a bounded context owns, which namespaces are stable public seams, where a change belongs, and which tests protect the area. Today only six contexts have that map. After this change, the major contexts `api`, `runtime`, `views`, `wallet`, `schema`, `asset_selector`, and `startup` will have the same investigation-shortening guide without changing application behavior.

This is documentation-only work. It is visible by reading the new `src/hyperopen/**/BOUNDARY.md` files and by running the repository documentation and test gates.

## Progress

- [x] (2026-04-22 11:54Z) Created and claimed `bd` issue `hyperopen-2lnh` for this docs cleanup.
- [x] (2026-04-22 11:55Z) Read existing boundary docs for account, funding, portfolio, trading, vaults, and websocket to mirror the current format.
- [x] (2026-04-22 11:56Z) Dispatched read-only explorer agents over independent source areas: API, runtime/startup, views/asset selector, and wallet/schema.
- [x] (2026-04-22 12:05Z) Drafted `BOUNDARY.md` files for `api`, `runtime`, `views`, `wallet`, `schema`, `asset_selector`, and `startup`.
- [x] (2026-04-22 12:09Z) Validated documentation style and repository gates with `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-04-22 12:10Z) Moved this ExecPlan to completed and closed `hyperopen-2lnh`.

## Surprises & Discoveries

- Observation: The existing boundary docs live directly under `src/hyperopen/<context>/BOUNDARY.md`, not in `docs/`.
  Evidence: `rg --files -g 'BOUNDARY.md'` returned `src/hyperopen/account/BOUNDARY.md`, `src/hyperopen/funding/BOUNDARY.md`, `src/hyperopen/portfolio/BOUNDARY.md`, `src/hyperopen/trading/BOUNDARY.md`, `src/hyperopen/vaults/BOUNDARY.md`, and `src/hyperopen/websocket/BOUNDARY.md`.
- Observation: The missing docs expand the current boundary-doc set from six to thirteen.
  Evidence: `rg --files -g 'BOUNDARY.md' src/hyperopen | sort` now returns account, api, asset_selector, funding, portfolio, runtime, schema, startup, trading, vaults, views, wallet, and websocket.

## Decision Log

- Decision: Create one active ExecPlan and one `bd` task even though the implementation is documentation-only.
  Rationale: The root `AGENTS.md` requires complex multi-agent work to have an ExecPlan, and `docs/PLANS.md` requires active plans to reference live `bd` work.
  Date/Author: 2026-04-22 / Codex.
- Decision: Use read-only explorer agents for source discovery and keep the final writing pass centralized.
  Rationale: The seven docs must read consistently, while the source investigation can be split by independent context without shared-state conflicts.
  Date/Author: 2026-04-22 / Codex.
- Decision: Keep the new docs under the existing `src/hyperopen/<context>/BOUNDARY.md` convention instead of creating a central docs index.
  Rationale: Future investigators usually start from a source directory, and the existing six boundary docs already establish local maps as the repository pattern.
  Date/Author: 2026-04-22 / Codex.

## Outcomes & Retrospective

To be completed after validation.

Implemented seven additive local boundary maps under `src/hyperopen`: `api`, `runtime`, `views`, `wallet`, `schema`, `asset_selector`, and `startup`. This reduces investigation complexity by giving future contributors a same-shaped ownership, public seam, dependency rule, key test, and change-routing map in every targeted major context. The implementation reduces documentation complexity because the ownership hints now live at the source directories where investigators start, and it does not change runtime behavior.

## Context and Orientation

A bounded context is a source-code area with a clear owner and dependency direction. In this repository, a `BOUNDARY.md` file is the local map for that area. Existing examples under `src/hyperopen/account/BOUNDARY.md`, `src/hyperopen/funding/BOUNDARY.md`, `src/hyperopen/portfolio/BOUNDARY.md`, `src/hyperopen/trading/BOUNDARY.md`, `src/hyperopen/vaults/BOUNDARY.md`, and `src/hyperopen/websocket/BOUNDARY.md` use this shape:

The `Owns` section states the domain responsibility. The `Stable Public Seams` section lists namespaces callers should depend on. The `Dependency Rules` section tells contributors what dependency direction is allowed or forbidden. The `Key Tests` section lists focused suites and final gates. The `Where This Change Goes` section routes common future edits to owning namespaces.

The missing target files are:

- `src/hyperopen/api/BOUNDARY.md`
- `src/hyperopen/runtime/BOUNDARY.md`
- `src/hyperopen/views/BOUNDARY.md`
- `src/hyperopen/wallet/BOUNDARY.md`
- `src/hyperopen/schema/BOUNDARY.md`
- `src/hyperopen/asset_selector/BOUNDARY.md`
- `src/hyperopen/startup/BOUNDARY.md`

## Plan of Work

First, inspect each target context with `rg --files` and read the most important source, tests, and ADRs. Second, write a boundary file under each target directory using the same section names and compact prose style as the existing six docs. Third, run focused documentation checks first and broaden to the required repository gates for code changes if the docs tooling requires it. Because this task only adds Markdown files, browser QA is not required.

## Concrete Steps

Run from `/Users/barry/.codex/worktrees/b0ad/hyperopen`:

- `rg --files -g 'BOUNDARY.md'`
- `rg --files src/hyperopen/<context> test | rg '<context>|related-pattern'`
- Create the seven missing `BOUNDARY.md` files.
- `npm run check`
- `npm test`
- `npm run test:websocket`

Validation evidence from this implementation:

- `npm run check` passed after running `npm install` to restore declared local packages `zod` and `smol-toml` that were missing from `node_modules`.
- `npm test` passed with 3383 tests, 18432 assertions, 0 failures, and 0 errors.
- `npm run test:websocket` passed with 461 tests, 2798 assertions, 0 failures, and 0 errors.

## Validation and Acceptance

Acceptance is met when each target context has a `BOUNDARY.md` file with `Owns`, `Stable Public Seams`, `Dependency Rules`, `Key Tests`, and `Where This Change Goes` sections, and the content names real namespaces and tests from the current tree. `npm run check`, `npm test`, and `npm run test:websocket` should pass. If a full gate cannot be run within the session, record the blocker and any focused checks that did run.

This acceptance has been met for all seven target contexts.

## Idempotence and Recovery

The edits are additive Markdown files. Re-running the discovery commands is safe. If validation fails due to documentation style, edit only the new docs or this ExecPlan. If unrelated existing tests fail, record the failing command and evidence without changing unrelated code.

## Artifacts and Notes

The source of truth for progress is this ExecPlan and `bd` issue `hyperopen-2lnh`. Explorer agent summaries are supporting evidence only; final docs must be verified against the working tree before completion.

The initial `npm run check` attempt failed at `npm run test:multi-agent` because `node_modules` did not contain the declared `zod` and `smol-toml` packages. Running `npm install` restored the declared dependency set without changing tracked package files, and the rerun passed.

Plan revision note, 2026-04-22: Updated the plan after implementation to record completed docs, validation evidence, the dependency-restore observation, completion move, and closed `bd` status.

## Interfaces and Dependencies

No runtime interfaces change. The documentation interface being added is the standard `src/hyperopen/<context>/BOUNDARY.md` file used by future contributors to locate ownership, seams, tests, and change destinations.
