# Split the oversized account endpoint namespace

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`. It is self-contained so a future contributor can continue from this file without relying on conversation history.

Tracked issue: `hyperopen-gnrc` ("Retire oversized account endpoint namespace").

## Purpose / Big Picture

`src/hyperopen/api/endpoints/account.cljs` is the largest production namespace in `dev/namespace_size_exceptions.edn`, measured at 847 lines. Every edit to that endpoint currently forces contributors and Codex sessions to load unrelated account, funding, staking, portfolio, and ledger code together. This plan preserves the public `hyperopen.api.endpoints.account` API while moving cohesive endpoint concerns into smaller namespaces under `src/hyperopen/api/endpoints/account/`. After the change, callers should behave exactly as before, the focused account endpoint tests should pass, and the source exception for `src/hyperopen/api/endpoints/account.cljs` should be removed because the facade is under the 500-line target.

## Progress

- [x] (2026-04-22 13:13Z) Identified `src/hyperopen/api/endpoints/account.cljs` as the largest production namespace-size exception at 847 lines.
- [x] (2026-04-22 13:13Z) Created and claimed `bd` issue `hyperopen-gnrc`.
- [x] (2026-04-22 13:15Z) Split private helpers and public request functions into focused endpoint modules while keeping `hyperopen.api.endpoints.account` as a stable facade.
- [x] (2026-04-22 13:15Z) Removed the retired `src/hyperopen/api/endpoints/account.cljs` entry from `dev/namespace_size_exceptions.edn`.
- [x] (2026-04-22 13:17Z) Ran focused account endpoint and gateway tests after installing lockfile dependencies with `npm ci`; the focused command passed with 46 tests, 202 assertions, 0 failures, and 0 errors.
- [x] (2026-04-22 13:21Z) Ran the required validation gates. `npm run check`, `npm test`, and `npm run test:websocket` all passed.

## Surprises & Discoveries

- Observation: This worktree initially had no `node_modules`, so the first focused test attempt compiled CLJS successfully but failed before test execution on a missing Lucide JS module.
  Evidence: `node out/test.js --test=hyperopen.api.endpoints.account-test --test=hyperopen.api.gateway.account-test` failed with `Cannot find module 'lucide/dist/esm/icons/external-link.js'`. Running `npm ci` installed 333 packages from `package-lock.json`, and the same focused command then passed.

- Observation: The facade split did not require downstream caller changes.
  Evidence: `src/hyperopen/api/gateway/account.cljs`, `src/hyperopen/startup/collaborators.cljs`, and `src/hyperopen/api/endpoints/vaults.cljs` still require `hyperopen.api.endpoints.account`; focused and full test suites passed through those call paths.

## Decision Log

- Decision: Keep `hyperopen.api.endpoints.account` as the public facade.
  Rationale: `src/hyperopen/api/gateway/account.cljs`, `src/hyperopen/startup/collaborators.cljs`, `src/hyperopen/api/endpoints/vaults.cljs`, and multiple tests require this namespace directly. A facade split removes the oversized implementation without widening or breaking existing public API boundaries.
  Date/Author: 2026-04-22 / Codex

- Decision: Split implementation by endpoint concern rather than by private helper visibility.
  Rationale: The old file already had natural concern groups. `funding_history.cljs`, `metadata.cljs`, `staking.cljs`, `clearinghouse.cljs`, and `portfolio.cljs` let future edits load only the relevant endpoint family while keeping shared parsing helpers in `common.cljs`.
  Date/Author: 2026-04-22 / Codex

- Decision: Use `npm ci` to restore local dependencies before rerunning tests.
  Rationale: The failure was caused by an absent `node_modules` tree, not by source code. Installing exactly from the committed lockfile restored the expected local environment without changing dependency manifests.
  Date/Author: 2026-04-22 / Codex

## Outcomes & Retrospective

Implemented and validated. The 847-line account endpoint namespace is now an 81-line facade. Implementation code moved into six focused namespaces under `src/hyperopen/api/endpoints/account/`, each under the 500-line target: `clearinghouse.cljs` is 62 lines, `common.cljs` is 39 lines, `funding_history.cljs` is 135 lines, `metadata.cljs` is 119 lines, `portfolio.cljs` is 199 lines, and `staking.cljs` is 310 lines. `dev/namespace_size_exceptions.edn` no longer carries the account endpoint exception.

The change reduces overall maintenance complexity because future account endpoint edits can load a focused namespace instead of the full mixed account endpoint corpus. Runtime complexity is unchanged: public callers still enter through `hyperopen.api.endpoints.account`, and that facade forwards to the same endpoint behavior.

## Context and Orientation

The working directory is `/Users/barry/.codex/worktrees/c80b/hyperopen`.

The repository tracks temporary namespace-size exceptions in `dev/namespace_size_exceptions.edn`. Production source namespaces should generally stay at or below 500 lines so contributors can load focused context. The current largest production exception is `src/hyperopen/api/endpoints/account.cljs`, which owns several unrelated groups:

- Funding history pagination and normalization hooks for the `userFunding` info endpoint.
- API-wallet metadata endpoints for extra agents and `webData2`.
- Staking validator and delegator endpoints.
- Spot and perpetual clearinghouse endpoint request bodies.
- User abstraction normalization and request routing.
- Portfolio summary normalization, user fee requests, and non-funding ledger update requests.

Callers import `hyperopen.api.endpoints.account` today, so this plan must not require downstream callers to move to new namespaces. The new namespaces are implementation owners only. The facade should expose the same public functions that the old file exposed:

- `request-user-funding-history!`
- `request-spot-clearinghouse-state!`
- `request-extra-agents!`
- `request-user-webdata2!`
- `request-staking-validator-summaries!`
- `request-staking-delegator-summary!`
- `request-staking-delegations!`
- `request-staking-delegator-rewards!`
- `request-staking-delegator-history!`
- `normalize-user-abstraction-mode`
- `request-user-abstraction!`
- `request-clearinghouse-state!`
- `normalize-portfolio-summary`
- `request-portfolio!`
- `request-user-fees!`
- `request-user-non-funding-ledger-updates!`

## Plan of Work

Create `src/hyperopen/api/endpoints/account/common.cljs` for small shared parsing helpers such as millisecond parsing, optional numeric parsing, lowercase address normalization, and non-negative timeout waiting. These helpers remain pure except `wait-ms`, which wraps the existing `hyperopen.platform/set-timeout!`.

Move funding history pagination helpers into `src/hyperopen/api/endpoints/account/funding_history.cljs`. It should require `common.cljs`, `hyperopen.api.request-policy`, and `clojure.string`, then expose `request-user-funding-history!` with the same seven-argument shape currently used by the facade and gateway.

Move extra-agent and `webData2` request handling into `src/hyperopen/api/endpoints/account/metadata.cljs`. It should require `common.cljs`, `hyperopen.api.request-policy`, and `hyperopen.wallet.agent-session`, then expose `request-extra-agents!` and `request-user-webdata2!`.

Move staking-specific normalizers and request functions into `src/hyperopen/api/endpoints/account/staking.cljs`. It should require `common.cljs` and `hyperopen.api.request-policy`, then expose the five staking request functions.

Move user abstraction and clearinghouse request helpers into `src/hyperopen/api/endpoints/account/clearinghouse.cljs`. It should expose `request-spot-clearinghouse-state!`, `normalize-user-abstraction-mode`, `request-user-abstraction!`, and `request-clearinghouse-state!`.

Move portfolio summary, portfolio request, user fees, and non-funding ledger updates into `src/hyperopen/api/endpoints/account/portfolio.cljs`. It should expose `normalize-portfolio-summary`, `request-portfolio!`, `request-user-fees!`, and `request-user-non-funding-ledger-updates!`.

Rewrite `src/hyperopen/api/endpoints/account.cljs` as a thin facade that requires the focused implementation namespaces and defines forwarding functions with the same public names and arities. Then remove the matching source exception from `dev/namespace_size_exceptions.edn`.

## Concrete Steps

1. Confirm the current largest source exception and baseline line count:

    wc -l src/hyperopen/api/endpoints/account.cljs

2. Add the focused implementation namespaces under `src/hyperopen/api/endpoints/account/` and rewrite `src/hyperopen/api/endpoints/account.cljs` as a facade.

3. Remove only the `src/hyperopen/api/endpoints/account.cljs` map from `dev/namespace_size_exceptions.edn`.

4. Verify the new line counts:

    wc -l src/hyperopen/api/endpoints/account.cljs src/hyperopen/api/endpoints/account/*.cljs

   Completed output:

        81 src/hyperopen/api/endpoints/account.cljs
        62 src/hyperopen/api/endpoints/account/clearinghouse.cljs
        39 src/hyperopen/api/endpoints/account/common.cljs
       135 src/hyperopen/api/endpoints/account/funding_history.cljs
       119 src/hyperopen/api/endpoints/account/metadata.cljs
       199 src/hyperopen/api/endpoints/account/portfolio.cljs
       310 src/hyperopen/api/endpoints/account/staking.cljs
       945 total

5. Run focused account endpoint coverage:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.api.endpoints.account-test --test=hyperopen.api.gateway.account-test

   Completed result: 46 tests, 202 assertions, 0 failures, 0 errors.

6. Run the required repository gates for code changes:

    npm run check
    npm test
    npm run test:websocket

   Completed results: `npm run check` passed; `npm test` passed with 3,383 tests, 18,432 assertions, 0 failures, and 0 errors; `npm run test:websocket` passed with 461 tests, 2,798 assertions, 0 failures, and 0 errors.

## Validation and Acceptance

The work is accepted when all of these are true:

1. `src/hyperopen/api/endpoints/account.cljs` is under 500 lines and only acts as a public facade.

2. Every new `src/hyperopen/api/endpoints/account/*.cljs` file is under 500 lines.

3. `dev/namespace_size_exceptions.edn` no longer includes `src/hyperopen/api/endpoints/account.cljs`.

4. Existing callers still use `hyperopen.api.endpoints.account` without code changes.

5. The focused endpoint command passes for `hyperopen.api.endpoints.account-test` and `hyperopen.api.gateway.account-test`.

6. `npm run check`, `npm test`, and `npm run test:websocket` pass, or any unrelated environmental blocker is recorded with the exact failure.

## Idempotence and Recovery

The split is source-only and behavior-preserving. It can be retried safely by rerunning tests after each file move. If a new focused namespace has a compiler error, keep the public facade in place and fix the implementation namespace rather than changing downstream callers. Do not run destructive git commands or revert unrelated user work.

## Artifacts and Notes

Initial baseline:

    src/hyperopen/api/endpoints/account.cljs: 847 lines

Completed source line counts:

    src/hyperopen/api/endpoints/account.cljs: 81 lines
    src/hyperopen/api/endpoints/account/clearinghouse.cljs: 62 lines
    src/hyperopen/api/endpoints/account/common.cljs: 39 lines
    src/hyperopen/api/endpoints/account/funding_history.cljs: 135 lines
    src/hyperopen/api/endpoints/account/metadata.cljs: 119 lines
    src/hyperopen/api/endpoints/account/portfolio.cljs: 199 lines
    src/hyperopen/api/endpoints/account/staking.cljs: 310 lines

Focused validation:

    Ran 46 tests containing 202 assertions.
    0 failures, 0 errors.

Required validation:

    npm run check: passed
    npm test: Ran 3383 tests containing 18432 assertions. 0 failures, 0 errors.
    npm run test:websocket: Ran 461 tests containing 2798 assertions. 0 failures, 0 errors.

## Interfaces and Dependencies

The public interface remains `hyperopen.api.endpoints.account`. New implementation namespaces are internal to the account endpoint boundary and should not be required by existing callers unless a later plan intentionally narrows those dependencies.

## Revision Notes

- 2026-04-22 / Codex: Created the active ExecPlan after identifying the largest production namespace-size exception and claiming `hyperopen-gnrc`.
- 2026-04-22 / Codex: Recorded the completed facade split, namespace-size exception removal, validation results, and dependency-install discovery before moving the plan to completed.
