# Load Vault Benchmark Sources on Portfolio Route Entry

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, vaults will appear in the Portfolio returns benchmark search even when a user has never visited the Vaults page in the current app session. The user-visible behavior change is simple: opening `/portfolio`, focusing benchmark search, and typing `vault` should surface vault options without requiring a prior `/vaults` visit.

## Progress

- [x] (2026-02-27 23:15Z) Reviewed planning/runtime guardrails in `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/RELIABILITY.md`, and `/hyperopen/docs/FRONTEND.md`.
- [x] (2026-02-27 23:15Z) Diagnosed root cause: portfolio VM depends on `[:vaults :merged-index-rows]`, but vault index/summaries are loaded only from `load-vault-route` when route matches `/vaults`.
- [x] (2026-02-27 23:15Z) Authored this active ExecPlan.
- [x] (2026-02-27 23:16Z) Updated `load-vault-route` in `/hyperopen/src/hyperopen/vaults/actions.cljs` so `/portfolio` routes trigger `load-vault-list-effects`.
- [x] (2026-02-27 23:16Z) Added regressions in `/hyperopen/test/hyperopen/vaults/actions_test.cljs` for `/portfolio` route hydration and `/trade` no-op fallback.
- [x] (2026-02-27 23:16Z) Ran required validation gates: `npm run check`, `npm test`, `npm run test:websocket` (all passed).
- [x] (2026-02-27 23:16Z) Updated this plan with implementation outcomes and prepared move to completed.
- [x] (2026-02-27 23:16Z) Re-ran `npm run check`, `npm test`, and `npm run test:websocket` after final documentation updates; all remained green.

## Surprises & Discoveries

- Observation: Portfolio benchmark selector already supports vault options in VM, but the source rows are populated only by vault route loaders.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` reads `[:vaults :merged-index-rows]`; `/hyperopen/src/hyperopen/vaults/actions.cljs` populates via `load-vault-list-effects`.

- Observation: Startup and wallet-address handler flows always dispatch `:actions/load-vault-route` for current route, but `/portfolio` currently yields no effects.
  Evidence: `/hyperopen/src/hyperopen/startup/runtime.cljs` dispatches `:actions/load-vault-route`; `load-vault-route` only handles `:list` and `:detail` route kinds today.

- Observation: Existing startup route bootstrap tests already assert dispatch of `:actions/load-vault-route` on `/portfolio`; route-loader behavior change required only vault action-level assertions.
  Evidence: `/hyperopen/test/hyperopen/startup/runtime_test.cljs` includes portfolio-route dispatch checks; `/hyperopen/test/hyperopen/vaults/actions_test.cljs` now verifies effect payload for `/portfolio`.

## Decision Log

- Decision: Fix at vault route-loader boundary (`load-vault-route`) instead of adding portfolio-only VM fetch side effects.
  Rationale: Keeps side effects outside VM/pure rendering and reuses existing tested vault list loading behavior.
  Date/Author: 2026-02-27 / Codex

- Decision: Scope new prefetch behavior narrowly to `/portfolio` paths and preserve current behavior for other non-vault routes.
  Rationale: Prevents broad network fan-out while meeting benchmark selector requirements.
  Date/Author: 2026-02-27 / Codex

## Outcomes & Retrospective

Implemented and validated.

What changed:

- `/hyperopen/src/hyperopen/vaults/actions.cljs` now treats `:other` routes that start with `/portfolio` as vault-list hydration triggers via `load-vault-list-effects`.
- `/hyperopen/test/hyperopen/vaults/actions_test.cljs` now includes explicit regression assertions that:
  - `/portfolio` route emits vault index/summaries (and user-equities when wallet address exists),
  - `/trade` route remains no-op.

User-visible impact:

- Portfolio returns benchmark search receives vault option source rows without requiring a prior `/vaults` visit.

Validation evidence:

- `npm run check` passed.
- `npm test` passed (`Ran 1503 tests containing 7633 assertions. 0 failures, 0 errors.`).
- `npm run test:websocket` passed (`Ran 153 tests containing 701 assertions. 0 failures, 0 errors.`).
- The same three gates were re-run after final plan relocation/update; all remained green.

Scope gaps:

- None identified for this fix scope.

## Context and Orientation

The Portfolio returns benchmark selector options are built in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` by combining:

1. asset-based benchmark options from `[:asset-selector :markets]`, and
2. vault benchmark options from `[:vaults :merged-index-rows]`.

The vault rows are hydrated by vault API effects emitted from `/hyperopen/src/hyperopen/vaults/actions.cljs` `load-vault-list-effects` (`:effects/api-fetch-vault-index`, `:effects/api-fetch-vault-summaries`, and optional `:effects/api-fetch-user-vault-equities`).

Route-triggered loading currently flows through `load-vault-route`. It emits list/detail effects for `/vaults` routes only; for `/portfolio` it returns `[]`. Because of that, fresh sessions on Portfolio lack vault benchmark source rows until the user first visits Vaults.

## Plan of Work

### Milestone 1: Extend Route Loader for Portfolio Vault-Benchmark Prefetch

In `/hyperopen/src/hyperopen/vaults/actions.cljs`, update `load-vault-route` so that when route kind is `:other` and normalized path begins with `/portfolio`, it returns `load-vault-list-effects state`. Keep existing `/vaults` list and detail behavior unchanged.

### Milestone 2: Add Route-Level Regressions

In `/hyperopen/test/hyperopen/vaults/actions_test.cljs`, add assertions that:

- `load-vault-route` on `/portfolio` emits vault list loading effects (including optional wallet-specific equity fetch),
- non-vault/non-portfolio routes continue to emit `[]`.

This directly guards the reported issue by proving portfolio route entry loads the data source required for vault benchmark options.

### Milestone 3: Validate Required Gates and Capture Results

Run repository-required commands from `/hyperopen`:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Then update all living sections and move this plan to `/hyperopen/docs/exec-plans/completed/` after acceptance criteria pass.

## Concrete Steps

1. Edit `/hyperopen/src/hyperopen/vaults/actions.cljs` (`load-vault-route`).
2. Edit `/hyperopen/test/hyperopen/vaults/actions_test.cljs` (new route regression assertions).
3. Execute validation gates from repo root:

       npm run check
       npm test
       npm run test:websocket

4. Update and relocate this plan to completed with outcomes and evidence.

## Validation and Acceptance

Acceptance is complete when all are true:

1. Entering `/portfolio` emits vault list fetch effects via `load-vault-route`.
2. Portfolio benchmark selector has access to vault option source rows without visiting `/vaults` first (indirectly guaranteed by route-level hydration contract plus existing VM behavior).
3. Non-vault/non-portfolio routes still emit no vault route effects.
4. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

The change is additive and route-branch-local. Re-running tests is safe. If regressions appear, revert the new `/portfolio` branch in `load-vault-route` and keep new tests to drive a revised implementation.

No data migration or destructive operation is involved.

## Artifacts and Notes

Primary code target:

- `/hyperopen/src/hyperopen/vaults/actions.cljs`

Primary regression target:

- `/hyperopen/test/hyperopen/vaults/actions_test.cljs`

This fix intentionally does not modify portfolio view-model rendering logic because the missing behavior is caused by route-driven data hydration, not selector filtering logic.

## Interfaces and Dependencies

Stable interfaces preserved:

- `:actions/load-vault-route` command id and invocation shape.
- `:actions/navigate` behavior of applying router projection before heavy effects.

Behavioral dependency used:

- `load-vault-list-effects` in `/hyperopen/src/hyperopen/vaults/actions.cljs` remains the single owner of vault list hydration effects.

Plan revision note: 2026-02-27 23:15Z - Initial ExecPlan created after root-cause diagnosis; implementation pending.
Plan revision note: 2026-02-27 23:16Z - Marked implementation complete with route-loader patch, regression coverage, and required gate results.
Plan revision note: 2026-02-27 23:16Z - Recorded final-tree validation rerun after moving the plan to completed.
