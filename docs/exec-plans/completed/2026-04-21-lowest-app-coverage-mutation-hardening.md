# Lift coverage for the lowest-covered app files

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`. It is self-contained so a future contributor can continue from this file without relying on conversation history.

Tracked issue: `hyperopen-ofau` ("Lift coverage for lowest app files").

## Purpose / Big Picture

The repository already has broad automated coverage, but a few production application files still have weak line and function coverage. This plan raises confidence in those low-coverage surfaces by adding focused tests for real application behavior, then running the repository mutation test runner against the same files. A mutation test temporarily changes source expressions and reruns the covering tests; a killed mutant means the tests detected the changed behavior, while a survived mutant means the tests executed code without asserting the right contract.

After this work, the five lowest-covered app-owned source files identified from the merged `test` and `ws-test` LCOV baseline should have targeted tests that document their intended behavior. The observable proof is a fresh `npm run coverage` report, focused test runs for the new namespaces, and `bb tools/mutate.clj` runs for the changed source files showing no surviving covered mutants for the lines targeted by the new tests.

## Progress

- [x] (2026-04-21 14:58Z) Restored locked npm dependencies with `npm ci` after the first coverage attempt failed because this worktree had no `node_modules`.
- [x] (2026-04-21 14:58Z) Ran `npm run coverage`; it passed with 3,367 `test` tests, 461 `ws-test` tests, and total coverage of 90.24% lines, 82.64% functions, and 69.53% branches.
- [x] (2026-04-21 14:58Z) Merged LCOV entries by source path across `test` and `ws-test`, filtered to real `src/hyperopen/**/*.cljs` source files that exist in the repository, and excluded generated, test-support, and module-shim style targets from the top-five selection.
- [x] (2026-04-21 14:58Z) Created and claimed `bd` issue `hyperopen-ofau`.
- [x] (2026-04-21 14:58Z) Created this active ExecPlan with the selected top-five files and validation plan.
- [x] (2026-04-21 15:04Z) Added focused tests for the top-five files and a behavior-preserving extraction in `src/hyperopen/runtime/effect_adapters/leaderboard.cljs` so c8 can credit the leaderboard effect adapter dependency construction.
- [x] (2026-04-21 15:09Z) Ran focused tests for the new namespaces; 25 tests and 123 assertions passed with 0 failures and 0 errors.
- [x] (2026-04-21 15:10Z) Reran `npm run coverage`; it passed with 3,382 `test` tests, 461 `ws-test` tests, and total coverage of 90.39% lines, 82.90% functions, and 69.61% branches.
- [x] (2026-04-21 15:21Z) Ran mutation scans for all five selected files. The two leaderboard adapter files had zero mutation sites. The other three files had 26 covered mutation sites total.
- [x] (2026-04-21 15:21Z) Ran mutation tests for all selected files with mutation sites: TWAP actions killed 1 of 1 mutants, staking effects killed 14 of 14 mutants, and portfolio VM utils killed 11 of 11 mutants.
- [x] (2026-04-21 15:23Z) Ran required repository gates: `npm run check`, `npm test`, and `npm run test:websocket` all passed.

## Surprises & Discoveries

- Observation: The first `npm run coverage` attempt compiled both Shadow CLJS test builds but failed before running tests because Node could not resolve `lucide/dist/esm/icons/external-link.js`.
  Evidence: The stack trace came from `out/test.js` and `node_modules` did not exist in the worktree. Running `npm ci` installed the package tree from `package-lock.json` without source changes.

- Observation: Raw `coverage-summary.json` contains separate Shadow CLJS runtime paths for the `test` and `ws-test` builds, so ranking those rows directly can understate coverage for files hit by one suite but emitted in both builds.
  Evidence: Files such as `src/hyperopen/api/promise_effects.cljs` appeared twice in the raw summary. Merging `coverage/lcov.info` by normalized source path produced the app-file ranking used in this plan.

- Observation: `src/hyperopen/runtime/effect_adapters/leaderboard.cljs` reached function coverage through tests, but c8 did not credit the original multi-line dependency map body as line-covered.
  Evidence: LCOV showed `FNDA:1` for `api-fetch-leaderboard-effect` while lines 12 through 27 remained at `DA:...,0`. Extracting dependency construction into `api-fetch-leaderboard-deps` and exercising the real route-gated boundary moved the file from 52.94% to 86.84% line coverage.

- Observation: The repository mutation runner found no mutation sites in the two thin leaderboard adapter files.
  Evidence: `bb tools/mutate.clj --scan --module src/hyperopen/runtime/action_adapters/leaderboard.cljs` and `bb tools/mutate.clj --scan --module src/hyperopen/runtime/effect_adapters/leaderboard.cljs` both reported zero total mutation sites.

## Decision Log

- Decision: Rank files by merged line coverage percentage after normalizing Shadow CLJS runtime paths back to existing `src/hyperopen/**/*.cljs` files.
  Rationale: The user asked for actual application files, and merged source-path coverage is the clearest measure across the repository's two ClojureScript test builds. Filtering out generated paths, test support, and non-existent runtime artifacts prevents spending effort on harness noise.
  Date/Author: 2026-04-21 / Codex

- Decision: Treat runtime leaderboard adapters as application code for this effort.
  Rationale: Although adapters are thin, they are production action/effect boundaries used by the runtime registry. Testing their delegation contracts protects user-visible leaderboard query, sort, pagination, fetch, and preference behavior.
  Date/Author: 2026-04-21 / Codex

- Decision: Prefer narrow tests in existing nearby test namespaces when they already exercise the same behavior, and create new focused namespaces only when no nearby namespace exists.
  Rationale: This keeps coverage additions easy to review and keeps each assertion close to the production surface it describes.
  Date/Author: 2026-04-21 / Codex

- Decision: Extract leaderboard effect adapter dependency construction into a private helper.
  Rationale: The original implementation was behaviorally fine, but the multi-line map was not receiving c8 line credit even when the function executed. The helper keeps the public API unchanged, makes the dependency map easier to test through the adapter, and allowed the line coverage lift requested by the user to be measured accurately.
  Date/Author: 2026-04-21 / Codex

## Outcomes & Retrospective

This plan is complete. Focused tests were added for the five selected application files, the only production change was a private helper extraction in `src/hyperopen/runtime/effect_adapters/leaderboard.cljs`, and the final coverage pass shows all five selected files improved from the baseline. The mutation runner killed all 26 executable covered mutants in files with mutation sites. The two leaderboard adapter files have no mutation sites, so the coverage proof there is focused behavior tests plus the mutation scan result.

Overall complexity is essentially flat. The new tests increase test volume but document existing runtime boundaries and small pure helpers. The private leaderboard dependency helper reduces visual complexity in the adapter by giving the dependency map a name and avoiding a source-map coverage blind spot.

## Context and Orientation

The working directory is `/Users/barry/.codex/worktrees/0ca4/hyperopen`. The project is a ClojureScript app built with Shadow CLJS and tested through Node. The relevant commands are in `package.json`: `npm run coverage` builds the `test` and `ws-test` Shadow CLJS targets, runs both generated Node test bundles with `NODE_V8_COVERAGE=.coverage`, and emits `coverage/lcov.info`. The mutation runner is `bb tools/mutate.clj --module <repo-relative-source-file>`, with options such as `--lines`, `--suite test`, `--suite ws-test`, and `--mutate-all`.

The baseline merged LCOV ranking selected these five real application files:

1. `src/hyperopen/runtime/action_adapters/leaderboard.cljs`: baseline 5 of 42 lines covered, 11.90%; final 42 of 42 lines covered, 100.00%.
2. `src/hyperopen/account/history/twap_actions.cljs`: baseline 10 of 22 lines covered, 45.45%; final 22 of 22 lines covered, 100.00%.
3. `src/hyperopen/staking/effects.cljs`: baseline 160 of 309 lines covered, 51.78%; final 289 of 309 lines covered, 93.53%.
4. `src/hyperopen/runtime/effect_adapters/leaderboard.cljs`: baseline 18 of 34 lines covered, 52.94%; final 33 of 38 lines covered, 86.84%.
5. `src/hyperopen/views/portfolio/vm/utils.cljs`: baseline 23 of 40 lines covered, 57.50%; final 40 of 40 lines covered, 100.00%.

The selected files are all in `src/hyperopen`, exist as production source, and are not generated files, browser artifacts, test support, or command-line tooling. `runtime/action_adapters/leaderboard.cljs` and `runtime/effect_adapters/leaderboard.cljs` are included because they are production runtime boundaries for leaderboard actions and effects. `account/history/twap_actions.cljs`, `staking/effects.cljs`, and `views/portfolio/vm/utils.cljs` contain direct application behavior.

## Plan of Work

First, add focused tests for `src/hyperopen/runtime/action_adapters/leaderboard.cljs`. The tests should call every public adapter function and assert that the returned effects match the underlying leaderboard action behavior for route load, query reset, timeframe persistence, sort toggling, page-size normalization, dropdown open/close, page clamping, next page, and previous page. The best home is a new `test/hyperopen/runtime/action_adapters/leaderboard_test.cljs` namespace that requires the specific adapter namespace. This file should not mock the delegated action functions; the test should prove the adapter boundary preserves the real action contract.

Second, add focused tests for `src/hyperopen/account/history/twap_actions.cljs`. The tests should assert that `default-twap-state` selects `:active`, `normalize-twap-subtab` accepts keyword and string variants for `:active`, `:history`, and `:fill-history`, and unsupported values fall back to `:active`. The action test should assert that `select-account-info-twap-subtab` emits one save effect under `[:account-info :twap :selected-subtab]` with the normalized value. The best home is a new `test/hyperopen/account/history/twap_actions_test.cljs`.

Third, broaden `test/hyperopen/staking/effects_test.cljs` to cover the currently uncovered fetch effects and submission branches. Add a helper that exercises each address-gated fetch effect with an explicit address, validates that route gating and `:skip-route-gate?` are respected, checks that `:skip-route-gate?` is removed before request functions receive opts, and checks that success projections update the provided store. Add submit tests for withdraw success, invalid request payload, blocked mutation messages, and runtime rejection. These contracts cover the untested submit labels, input reset paths, fallback error messages, and `resolve-address` branch without requiring production changes.

Fourth, add focused tests for `src/hyperopen/runtime/effect_adapters/leaderboard.cljs`. The tests should use `with-redefs` around `hyperopen.leaderboard.effects/api-fetch-leaderboard!` to assert that the adapter injects the store, API functions, projection functions, cache functions, known excluded address set, `now-ms-fn`, and opts. Add a real route-gated fetch call so c8 credits the adapter body without hitting the network. For preference persistence and restore, call the real adapter boundary and accept either supported or unsupported IndexedDB behavior, because the full suite may leave a test IndexedDB implementation installed. The best home is `test/hyperopen/runtime/effect_adapters/leaderboard_test.cljs`.

Fifth, add focused tests for `src/hyperopen/views/portfolio/vm/utils.cljs`. The tests should assert numeric helper behavior, canonical summary key aliases and fallbacks, drawdown clamping from both top-level and nested metric sources, and metric-token sensitivity to account info, effective account address, and request data. The best home is a new `test/hyperopen/views/portfolio/vm/utils_test.cljs` namespace.

After the tests pass, rerun `npm run coverage` and recompute the merged ranking for these files. Then run mutation tests for each touched source file. Use `bb tools/mutate.clj --module <file> --suite auto --mutate-all` when the selected file has a manageable number of covered mutation sites. If a file has too many sites for a full run, use `--lines` limited to the lines covered by the new tests and record the narrowed line set in this plan. A mutation run is accepted when covered mutants are killed or equivalent-surviving mutants are documented with a reason grounded in the source behavior. This plan found no surviving mutants and no equivalent-mutant exceptions were needed.

## Concrete Steps

Run these commands from `/Users/barry/.codex/worktrees/0ca4/hyperopen`.

1. Baseline coverage has already been run:

    npm run coverage

   Expected result: both `test` and `ws-test` pass, then c8 reports around 90.24% line coverage and writes `coverage/lcov.info`.

2. Add the focused test namespaces and edits described in the Plan of Work.

3. Run focused tests:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.runtime.action-adapters.leaderboard-test --test=hyperopen.account.history.twap-actions-test --test=hyperopen.staking.effects-test --test=hyperopen.runtime.effect-adapters.leaderboard-test --test=hyperopen.views.portfolio.vm.utils-test

   Expected result: all listed namespaces pass with zero failures and zero errors.

   Actual result:

    Ran 25 tests containing 123 assertions.
    0 failures, 0 errors.

4. Refresh coverage and merged ranking:

    npm run coverage

   Expected result: total coverage remains above the repository threshold, and the five selected files show materially improved line/function coverage from the baseline listed above.

   Actual result:

    Statements   : 90.39% (136040/150495)
    Branches     : 69.61% (30505/43821)
    Functions    : 82.90% (8419/10155)
    Lines        : 90.39% (136040/150495)

5. Run mutation tests for the selected files. Start with scan commands to size each run:

    bb tools/mutate.clj --scan --module src/hyperopen/runtime/action_adapters/leaderboard.cljs
    bb tools/mutate.clj --scan --module src/hyperopen/account/history/twap_actions.cljs
    bb tools/mutate.clj --scan --module src/hyperopen/staking/effects.cljs
    bb tools/mutate.clj --scan --module src/hyperopen/runtime/effect_adapters/leaderboard.cljs
    bb tools/mutate.clj --scan --module src/hyperopen/views/portfolio/vm/utils.cljs

   Then run either full or line-narrowed mutation commands, based on the scan sizes:

    bb tools/mutate.clj --module src/hyperopen/account/history/twap_actions.cljs --suite auto --mutate-all
    bb tools/mutate.clj --module src/hyperopen/staking/effects.cljs --suite auto --mutate-all
    bb tools/mutate.clj --module src/hyperopen/views/portfolio/vm/utils.cljs --suite auto --mutate-all

   The two leaderboard adapter files scanned to zero mutation sites, so no mutation run was possible for those files.

6. Run required repository gates after code changes:

    npm run check
    npm test
    npm run test:websocket

   Actual result: all three commands passed. `npm test` ran 3,383 tests with 18,432 assertions. `npm run test:websocket` ran 461 tests with 2,798 assertions.

7. Update this ExecPlan with coverage deltas, mutation outcomes, any surviving equivalent mutants, and final validation evidence. This step is complete.

## Validation and Acceptance

This work is accepted when all of these are true:

1. The five selected app files have focused tests that assert behavior, not merely function existence.
2. `npm run coverage` passes after the test additions.
3. The merged LCOV coverage for each selected file is higher than the baseline recorded in this plan.
4. Mutation testing has been run against each selected file or against the exact newly covered source lines in that file.
5. Any surviving covered mutants are either fixed by strengthening assertions or documented as equivalent mutants with a concise explanation.
6. `npm run check`, `npm test`, and `npm run test:websocket` pass, or any unrelated blocker is documented with a reproducible command and error.

## Idempotence and Recovery

The test additions are additive and can be rerun safely. `npm run coverage` deletes and recreates `.coverage` and `coverage`, which are generated artifacts and not source of truth. The mutation runner temporarily rewrites source files while testing mutants and restores them afterward; after every mutation run, confirm `git status --short` does not show unintended production source edits. If a mutation run is interrupted, inspect the target source file and the `target/mutation` artifacts before continuing. Do not use destructive git commands to recover; restore only the specific interrupted mutation if needed.

If a focused test fails, keep the failure local to the new test namespace unless it exposes a production bug. If it exposes a real production bug, document the discovery here and fix the production behavior only after making the failing assertion precise.

## Artifacts and Notes

Baseline command:

    npm run coverage

Baseline result:

    Ran 3367 tests containing 18367 assertions.
    0 failures, 0 errors.
    Ran 461 tests containing 2798 assertions.
    0 failures, 0 errors.
    Statements   : 90.24% (135817/150491)
    Branches     : 69.53% (30436/43772)
    Functions    : 82.64% (8392/10154)
    Lines        : 90.24% (135817/150491)

Baseline selected-file ranking:

    11.90 lines 5/42 funcs 0/9 branches 0/0 src/hyperopen/runtime/action_adapters/leaderboard.cljs
    45.45 lines 10/22 funcs 0/2 branches 0/0 src/hyperopen/account/history/twap_actions.cljs
    51.78 lines 160/309 funcs 14/22 branches 42.19 src/hyperopen/staking/effects.cljs
    52.94 lines 18/34 funcs 2/2 branches 75.00 src/hyperopen/runtime/effect_adapters/leaderboard.cljs
    57.50 lines 23/40 funcs 2/6 branches 33.33 src/hyperopen/views/portfolio/vm/utils.cljs

Final selected-file coverage:

    100.00 lines 42/42 funcs 9/9 branches 9/9 src/hyperopen/runtime/action_adapters/leaderboard.cljs
    100.00 lines 22/22 funcs 2/2 branches 6/6 src/hyperopen/account/history/twap_actions.cljs
    93.53 lines 289/309 funcs 22/22 branches 49/87 src/hyperopen/staking/effects.cljs
    86.84 lines 33/38 funcs 3/3 branches 4/5 src/hyperopen/runtime/effect_adapters/leaderboard.cljs
    100.00 lines 40/40 funcs 6/6 branches 22/22 src/hyperopen/views/portfolio/vm/utils.cljs

Mutation scan and run evidence:

    src/hyperopen/runtime/action_adapters/leaderboard.cljs: 0 mutation sites
    src/hyperopen/runtime/effect_adapters/leaderboard.cljs: 0 mutation sites
    src/hyperopen/account/history/twap_actions.cljs: 1/1 mutants killed
    src/hyperopen/staking/effects.cljs: 14/14 mutants killed
    src/hyperopen/views/portfolio/vm/utils.cljs: 11/11 mutants killed

## Interfaces and Dependencies

The tests should use `cljs.test`. Asynchronous tests for promise-returning staking effects should use `cljs.test/async`. Use `with-redefs` for adapter dependency injection checks and atoms for captured calls. Avoid changing production interfaces unless a test reveals a real bug.

`bb tools/mutate.clj` depends on `coverage/lcov.info` to route mutants to the right suite when `--suite auto` is used. Always run `npm run coverage` before mutation testing unless deliberately using `--skip-coverage` through the nightly tool, which this plan does not need.

## Revision Notes

- 2026-04-21 / Codex: Created the active plan after the baseline coverage run and top-five app file ranking.
- 2026-04-21 / Codex: Recorded final implementation, coverage deltas, mutation results, and validation gates before moving the plan to completed.
