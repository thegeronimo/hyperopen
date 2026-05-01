# API Namespace Debt Retirement

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked `bd` work is `hyperopen-y4f6`, and `bd` remains the lifecycle source of truth while this plan records the implementation story.

## Purpose / Big Picture

The API layer currently has five source namespaces above the repository's 500-line namespace guardrail: `src/hyperopen/api/endpoints/account.cljs`, `src/hyperopen/api/info_client.cljs`, `src/hyperopen/api/trading.cljs`, `src/hyperopen/api/default.cljs`, and `src/hyperopen/api/endpoints/vaults.cljs`. These files concentrate endpoint normalization, request orchestration, trading signing, and public facade wrappers in places that are difficult to review and easy to push past their temporary exception caps.

After this work, those five public namespaces should remain callable through the same names, but most implementation details should live in smaller focused child namespaces. A contributor can verify the result by running `npm run lint:namespace-sizes` and seeing no size-exception entries for these five API source files, then running focused API tests and the required repository gates.

## Progress

- [x] (2026-05-01T14:16Z) Created and claimed `bd` task `hyperopen-y4f6` for the API namespace-size debt retirement scope.
- [x] (2026-05-01T14:16Z) Audited the target source files: `endpoints/account.cljs` 847 lines, `info_client.cljs` 726, `trading.cljs` 700, `default.cljs` 670, and `endpoints/vaults.cljs` 552.
- [x] (2026-05-01T14:17Z) Removed the five target source exceptions and confirmed `npm run lint:namespace-sizes` fails only on those five target files.
- [x] (2026-05-01T14:20Z) Split `hyperopen.api.endpoints.account` into focused account endpoint child namespaces while preserving public request and normalizer names.
- [x] (2026-05-01T14:20Z) Split `hyperopen.api.endpoints.vaults` into focused vault endpoint child namespaces while preserving public request and normalizer names plus private test seams used by existing helper tests.
- [x] (2026-05-01T14:23Z) Split `hyperopen.api.info-client` into focused stats, flow, and runtime namespaces while preserving `default-config`, `top-request-hotspots`, and `make-info-client`.
- [x] (2026-05-01T14:33Z) Split `hyperopen.api.trading` into focused HTTP, cancel request, agent action, and user action namespaces while preserving public functions and existing private test seams.
- [x] (2026-05-01T14:35Z) Split `hyperopen.api.default` into state, market, orders, account, vaults, funding Hyperunit, and leaderboard support namespaces while preserving the default facade public wrappers and service state behavior.
- [x] (2026-05-01T14:43Z) Ran focused API validation, namespace-size lint, and all required repository gates successfully.

## Surprises & Discoveries

- Observation: The current checkout has fewer namespace-size exceptions than the issue text described.
  Evidence: `dev/namespace_size_exceptions.edn` currently contains 68 entries: 39 source and 29 test. The API source targets remain present.
- Observation: Existing tests intentionally reach some private vars in `hyperopen.api.trading` and `hyperopen.api.endpoints.vaults`.
  Evidence: `test/hyperopen/api/trading/internal_seams_test.cljs` dereferences private vars such as `safe-private-key->agent-address`, `next-nonce`, `parse-json!`, `nonce-error-response?`, `parse-chain-id-int`, `resolve-user-signing-context`, and `post-signed-action!`; `test/hyperopen/api/endpoints/vaults_helpers_test.cljs` dereferences private vars such as `normalize-snapshot-key`, `cross-origin-browser-request?`, `boolean-value`, `normalize-follower-state`, and `followers-count`.
- Observation: The structural RED phase failed for exactly the API source debt files targeted by this plan.
  Evidence: `npm run lint:namespace-sizes` reported missing exceptions for `src/hyperopen/api/trading.cljs`, `src/hyperopen/api/info_client.cljs`, `src/hyperopen/api/endpoints/account.cljs`, `src/hyperopen/api/default.cljs`, and `src/hyperopen/api/endpoints/vaults.cljs`.
- Observation: The first mechanical endpoint split over-replaced `wait-ms` in local binding names and introduced an invalid namespaced local def in `vaults/index.cljs`.
  Evidence: `npx shadow-cljs --force-spawn compile test` failed in `account/funding_history.cljs` on `common/wait-ms-fn`; after tightening those replacements and removing the stray `defn- common/non-blank-text`, the test build compiled with 0 warnings.
- Observation: The initial info-client split missed the tail bodies for `default-request-runtime`, `request-info-at-attempt!`, and `reset-client!` because the source slices cut through top-level forms.
  Evidence: two compile attempts failed with EOF errors in `info_client/stats.cljs` and `info_client/flow.cljs`; after reconstructing those function bodies and moving rate-limit state helpers back to runtime ownership, the test build compiled.
- Observation: The first trading split clipped the `approve-agent!` boundary and left moved helper calls unqualified.
  Evidence: `npx shadow-cljs --force-spawn compile test` initially failed in `trading/user_actions.cljs`; after restoring the full user-action function bodies and qualifying helpers through `trading/http.cljs`, the test build completed with 0 warnings.
- Observation: The first vault endpoint split moved the CORS predicate behind the compatibility facade, which bypassed an existing private test seam.
  Evidence: focused API tests failed in `request-vault-index-response-avoids-cors-preflight-validator-headers-test`; passing the facade's `cross-origin-browser-request?` wrapper into the index child namespace restored the expected validator-header stripping.
- Observation: This worktree did not have `node_modules` installed when focused Node tests were first run.
  Evidence: `node out/test.js ...` failed before test execution with `Cannot find module 'lucide/dist/esm/icons/external-link.js'`; `npm ci` installed locked dependencies and the same focused command then ran.

## Decision Log

- Decision: Treat this as a behavior-preserving exception-retirement refactor, not an API redesign.
  Rationale: The user asked to retire API namespace debt. The safest path is to keep stable public namespace names and function names while reducing implementation file size.
  Date/Author: 2026-05-01 / Codex
- Decision: Use the namespace-size lint as the RED phase for this structural refactor.
  Rationale: The desired behavior is structural: the five target source files should no longer need size exceptions. Removing those exception entries before splitting should make `npm run lint:namespace-sizes` fail for the target files, and the implementation is accepted when that lint passes without adding replacement exceptions for them.
  Date/Author: 2026-05-01 / Codex
- Decision: Keep private compatibility wrappers in `hyperopen.api.trading` and `hyperopen.api.endpoints.vaults` for existing tests.
  Rationale: The current tests assert important signing and normalization invariants through private vars. Moving internals without wrappers would turn a low-risk ownership split into broad test churn that does not improve runtime behavior.
  Date/Author: 2026-05-01 / Codex

## Outcomes & Retrospective

The five target API source exceptions were removed from `dev/namespace_size_exceptions.edn`, and the target public namespaces now all sit below the 500-line standard: `default.cljs` 437, `trading.cljs` 127, `endpoints/vaults.cljs` 103, `endpoints/account.cljs` 80, and `info_client.cljs` 15. New API implementation namespaces created by this plan also remain below 500 lines; the largest are `endpoints/account/staking.cljs` 310, `info_client/runtime.cljs` 294, `trading/agent_actions.cljs` 263, `endpoints/account/portfolio.cljs` 258, and `info_client/flow.cljs` 235.

Validation passed:

    npm run test:runner:generate
    npx shadow-cljs --force-spawn compile test
    node out/test.js --test=hyperopen.api-test,hyperopen.api.default-test,hyperopen.api.info-client-test,hyperopen.api.instance-test,hyperopen.api.endpoints.account-test,hyperopen.api.endpoints.vaults-test,hyperopen.api.endpoints.vaults-helpers-test,hyperopen.api.fetch-compat-test,hyperopen.api.trading-test,hyperopen.api.trading.internal-seams-test,hyperopen.api.trading.cancel-request-test,hyperopen.api.trading.sign-and-submit-test,hyperopen.api.trading.session-invalidation-test,hyperopen.api.trading.approve-agent-test
    npm run lint:namespace-sizes
    npm run check
    npm test
    npm run test:websocket

The focused API run covered 171 tests / 878 assertions, `npm test` covered 3677 tests / 20255 assertions, and `npm run test:websocket` covered 461 tests / 2798 assertions. Remaining risk is ordinary refactor risk around private compatibility seams: `hyperopen.api.default/post-info!`, vault CORS helpers, and trading internal helpers are intentionally preserved in the public facades so existing tests and callers continue to intercept the same vars.

## Context and Orientation

The repository enforces a maximum of 500 lines per ClojureScript namespace. Oversized namespaces must be listed in `dev/namespace_size_exceptions.edn` with an owner, reason, maximum line count, and retirement date. The target API files are currently listed there and all retire on `2026-06-30`.

`src/hyperopen/api/endpoints/account.cljs` owns account-related `/info` endpoint normalization and request functions. It mixes extra agent parsing, staking validator/delegator normalization, user funding history pagination, account abstraction requests, clearinghouse state requests, portfolio summary normalization, user fees, and non-funding ledger requests. The public functions in that namespace must remain available, especially `request-user-funding-history!`, `request-extra-agents!`, `request-user-webdata2!`, staking request functions, `normalize-user-abstraction-mode`, `request-user-abstraction!`, `request-clearinghouse-state!`, `normalize-portfolio-summary`, `request-portfolio!`, `request-user-fees!`, and `request-user-non-funding-ledger-updates!`.

`src/hyperopen/api/endpoints/vaults.cljs` owns vault index HTTP fetches, vault summary normalization, vault details normalization, user vault equity normalization, and vault detail requests. It depends on `hyperopen.api.endpoints.account/normalize-portfolio-summary` for vault detail portfolio summaries. The public functions and `default-vault-index-url` must remain available. Existing tests also reach some private helper names, so the facade should keep tiny private wrappers for those names.

`src/hyperopen/api/info_client.cljs` owns the shared Hyperliquid `/info` client: queueing, request stats, retries, rate-limit cooldown, single-flight dedupe, response caching, and the public `make-info-client` constructor. The public values `default-config`, `top-request-hotspots`, and `make-info-client` must remain available.

`src/hyperopen/api/trading.cljs` owns exchange POST helpers, cancel request normalization, agent-session signing, schedule cancel, approve agent, and user-signed transfer actions. Existing tests reach several private helpers, so the public namespace should continue exposing those private vars as wrappers while moving implementation into focused child namespaces.

`src/hyperopen/api/default.cljs` owns the process-wide default API facade: service state, request wrappers, fetch compatibility wrappers, and market/account/vault/order gateway delegation. Runtime code imports these public wrapper functions directly, and several tests verify exact gateway delegation under `with-direct-redefs`, so child namespaces must still call the existing gateway vars rather than bypassing them through a new instance object.

## Plan of Work

First, remove only the five target source entries from `dev/namespace_size_exceptions.edn` and run `npm run lint:namespace-sizes`. The expected RED result is a failure naming those five source files as missing size exceptions. If unrelated namespace-size failures appear, record them here and do not mask them with broader exception edits.

Second, split `account.cljs` into child namespaces under `src/hyperopen/api/endpoints/account/`: `common.cljs` for shared parsing/address helpers, `agents.cljs` for extra agents and user webdata2, `staking.cljs` for staking validator and delegator normalization and requests, `funding_history.cljs` for user funding history pagination, and `portfolio.cljs` for account abstraction, clearinghouse, portfolio, fees, and non-funding ledger helpers. Keep `account.cljs` as the compatibility facade.

Third, split `vaults.cljs` into child namespaces under `src/hyperopen/api/endpoints/vaults/`: `common.cljs` for shared text/address/number/boolean helpers, `snapshots.cljs` for snapshot keys, PnL normalization, summaries, index rows, and index merging, `index.cljs` for browser-safe index fetches and vault summary requests, and `details.cljs` for user vault equities, follower state, vault details, and vault webdata2 requests. Keep `vaults.cljs` as the compatibility facade.

Fourth, split `info_client.cljs` into child namespaces under `src/hyperopen/api/info_client/`: `stats.cljs` for request tokens, counters, runtime defaults, stats mutations, and hotspot sorting; `flow.cljs` for request-flow opts, cache reads/writes, single-flight dedupe, retry status, retry delays, HTTP error creation, JSON parsing, request attempts, and request-info wrappers; and `runtime.cljs` for configuration, queue scheduling, cooldown handling, `make-info-client`, and service reset wiring. Keep `info_client.cljs` as the public facade.

Fifth, split `trading.cljs` into child namespaces under `src/hyperopen/api/trading/`: `http.cljs` for exchange/info URLs, JSON POST, response parsing, error predicates, nonce helpers, and signed payload POST; `cancel_request.cljs` for cancel and TWAP cancel action normalization; `agent_actions.cljs` for agent-session resolution, missing-wallet handling, nonce persistence, signing, submit order, cancel order, vault transfer, and schedule cancel; and `user_actions.cljs` for approve agent and user-signed transfer actions. Keep `trading.cljs` as the public facade and private test-seam wrapper owner.

Sixth, split `default.cljs` into child namespaces under `src/hyperopen/api/default/`: `state.cljs` for default service state, `market.cljs` for market wrappers and asset selector requests, `orders.cljs` for order wrappers, `account.cljs` for account wrappers, `vaults.cljs` for vault wrappers, and `funding_hyperunit.cljs` for Hyperunit wrappers. Keep `default.cljs` as the public facade. The child namespaces should accept small dependency maps or use stable functions from `state.cljs` so `with-direct-redefs` tests continue to intercept gateway calls.

Seventh, after each major split, check line counts with `wc -l`. Once every target source file and every new child source namespace is below 500 lines, run `npm run lint:namespace-sizes` and focused API tests. Then run `npm run check`, `npm test`, and `npm run test:websocket`.

## Concrete Steps

All commands are run from `/Users/barry/.codex/worktrees/3602/hyperopen`.

1. Structural RED:

        npm run lint:namespace-sizes

   Expected before source splits: failure showing missing size exceptions for the five target API source files after their exception entries are removed.

2. Focused API validation:

        npm run test:runner:generate
        npx shadow-cljs --force-spawn compile test
        node out/test.js --test=hyperopen.api-test,hyperopen.api.default-test,hyperopen.api.info-client-test,hyperopen.api.instance-test,hyperopen.api.endpoints.account-test,hyperopen.api.endpoints.vaults-test,hyperopen.api.endpoints.vaults-helpers-test,hyperopen.api.fetch-compat-test,hyperopen.api.trading-test,hyperopen.api.trading.internal-seams-test,hyperopen.api.trading.cancel-request-test,hyperopen.api.trading.sign-and-submit-test,hyperopen.api.trading.session-invalidation-test,hyperopen.api.trading.approve-agent-test

   Expected after implementation: all listed tests run with 0 failures and 0 errors.

3. Required gates:

        npm run lint:namespace-sizes
        npm run check
        npm test
        npm run test:websocket

   Expected after implementation: every command exits 0. If any command fails for an unrelated pre-existing issue, record the exact failure and create or link a `bd` follow-up instead of hiding it in this plan.

## Validation and Acceptance

Acceptance is structural and behavioral. Structurally, all five target source files must be at or below 500 lines, no replacement namespace-size exception entries for those five files may remain in `dev/namespace_size_exceptions.edn`, and every new API source namespace created by this plan must also be at or below 500 lines. Behaviorally, all existing public functions from the five public namespaces must remain callable with the same arities, private test seams in `trading.cljs` and `vaults.cljs` must continue to satisfy existing tests, and the focused API test command plus `npm run check`, `npm test`, and `npm run test:websocket` must pass.

This refactor is not accepted if it only raises caps, adds new long-lived exceptions for the target source files, removes regression coverage, or forces callers outside this scope to change imports.

## Idempotence and Recovery

The source splits are additive-first: create focused child namespaces, delegate from the old public namespace, run focused tests, and then remove moved private code from the old namespace. If a split fails, keep the facade in place, inspect the failed test, and move the smallest missing helper or require rather than changing behavior.

`npm run test:runner:generate` is safe to run repeatedly. If namespace-size lint fails after behavior tests pass, use `wc -l` to identify the oversized file and continue splitting; do not restore the removed exception unless this plan records a blocker and the `bd` issue is updated.

## Artifacts and Notes

Initial size inventory:

        847 src/hyperopen/api/endpoints/account.cljs
        726 src/hyperopen/api/info_client.cljs
        700 src/hyperopen/api/trading.cljs
        670 src/hyperopen/api/default.cljs
        552 src/hyperopen/api/endpoints/vaults.cljs

## Interfaces and Dependencies

Public interfaces that must remain available include the existing public vars in `hyperopen.api.endpoints.account`, `hyperopen.api.endpoints.vaults`, `hyperopen.api.info-client`, `hyperopen.api.trading`, and `hyperopen.api.default`. Existing caller imports should not change in this plan. New child namespaces are implementation owners only unless future work intentionally promotes them to documented public seams.

Revision note, 2026-05-01T14:16Z: Initial active ExecPlan created for `hyperopen-y4f6` after auditing the API namespace-size cluster, repo planning rules, and existing private test seams. The plan uses compatibility facades and namespace-size lint as structural RED evidence because this is a behavior-preserving ownership refactor.

Revision note, 2026-05-01T14:17Z: Recorded structural RED evidence after removing the five target API source exception entries and running `npm run lint:namespace-sizes`.

Revision note, 2026-05-01T14:20Z: Recorded the endpoint source split and the compile correction for mechanical replacement fallout.

Revision note, 2026-05-01T14:23Z: Recorded the info-client split into stats, flow, and runtime owners plus the compile fixes needed after slicing through function boundaries.

Revision note, 2026-05-01T14:33Z: Recorded the trading split into HTTP, cancel request, agent action, and user action owners. The compatibility facade now preserves public trading functions and private test-seam wrappers while the split namespaces compile with 0 warnings.

Revision note, 2026-05-01T14:43Z: Recorded the default facade split, the vault CORS test-seam correction, final line counts, focused API validation, namespace-size lint, and the required repository gates.
