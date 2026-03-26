# Raise Coverage For Leaderboard And Staking Effect Adapters

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The primary live `bd` issue is `hyperopen-epku`, and `bd` remains the source of truth for issue status while this plan is active.

## Purpose / Big Picture

The coverage report currently shows `src/hyperopen/runtime/effect_adapters/leaderboard.cljs` and `src/hyperopen/runtime/effect_adapters/staking.cljs` lagging well behind the other adapter namespaces in the same directory. After this work, those adapter seams will have focused tests that prove they pass the correct dependency maps into the underlying workflow functions, preserve the intended default behavior for optional toast seams, and keep the public runtime facade wired correctly. The observable result is that `npm run coverage` should report materially higher coverage for both files, and the adapter behavior should now be locked by deterministic tests rather than incidental transitive exercise.

## Progress

- [x] (2026-03-26 15:35Z) Inspected `/hyperopen/src/hyperopen/runtime/effect_adapters/leaderboard.cljs` and `/hyperopen/src/hyperopen/runtime/effect_adapters/staking.cljs`, confirmed both namespaces were missing direct tests, and identified the uncovered branches as dependency-map wiring plus the optional toast arities on the staking submit adapters.
- [x] (2026-03-26 15:39Z) Created and claimed `hyperopen-epku` in `bd` for this coverage task.
- [x] (2026-03-26 15:42Z) Created this active ExecPlan and recorded the intended validation commands, scope, and non-goals.
- [x] (2026-03-26 15:46Z) Added `/hyperopen/test/hyperopen/runtime/effect_adapters/leaderboard_test.cljs` and `/hyperopen/test/hyperopen/runtime/effect_adapters/staking_test.cljs` to cover facade aliasing, fetch dependency maps, preference delegation, and the default versus custom toast seams on staking submit adapters.
- [x] (2026-03-26 15:47Z) Regenerated `/hyperopen/test/test_runner_generated.cljs` so the new test namespaces are part of the compiled runner.
- [x] (2026-03-26 15:50Z) Restored the missing JavaScript toolchain with `npm ci` after the first `npm test` attempt failed because `shadow-cljs` was not installed in `node_modules/.bin`.
- [x] (2026-03-26 15:57Z) Confirmed the new adapter tests compile and run cleanly inside the full `npm test` pass; the remaining failure is an unrelated repo-baseline typography guard in `/hyperopen/src/hyperopen/views/notifications_view.cljs`.
- [x] (2026-03-26 16:00Z) Generated a `c8` coverage report from the compiled test build and recorded the new adapter coverage numbers for the two target namespaces.
- [x] (2026-03-26 16:03Z) Ran `npm run test:websocket` successfully; it passed with `416` tests and `0` failures.
- [x] (2026-03-26 16:06Z) Filed follow-up issues `hyperopen-ajjb` and `hyperopen-sfry` for the unrelated validation blockers discovered during `npm test` and `npm run check`.
- [x] (2026-03-26 16:10Z) Addressed the reviewer findings by asserting exact adapter delegation counts and call order, preventing duplicate underlying effect invocations from being hidden by the new tests.
- [x] (2026-03-26 16:12Z) Re-ran the compiled `npm test` binary after the reviewer hardening and confirmed the only remaining failure is still the unrelated typography guard in `/hyperopen/src/hyperopen/views/notifications_view.cljs`.
- [x] (2026-03-26 16:13Z) Re-ran `npm run test:websocket` after the reviewer hardening and confirmed it still passes with `416` tests and `0` failures.
- [x] (2026-03-26 16:14Z) Updated this ExecPlan with the measured coverage improvement, validation evidence, reviewer follow-through, and retrospective so `hyperopen-epku` can be closed with blockers explicitly tracked elsewhere.

## Surprises & Discoveries

- Observation: the target namespaces are almost entirely dependency-injection wrappers, so the missing coverage is not hiding algorithmic complexity; it is hiding unverified wiring.
  Evidence: `/hyperopen/src/hyperopen/runtime/effect_adapters/leaderboard.cljs` delegates to three seams, while `/hyperopen/src/hyperopen/runtime/effect_adapters/staking.cljs` is composed of fetch wrappers and four submit wrappers with optional `show-toast!` defaults.

- Observation: most neighboring adapter namespaces already use a common test style that captures dependency maps rather than trying to execute real network or runtime behavior.
  Evidence: `/hyperopen/test/hyperopen/runtime/effect_adapters/funding_test.cljs`, `/hyperopen/test/hyperopen/runtime/effect_adapters/order_test.cljs`, and `/hyperopen/test/hyperopen/runtime/effect_adapters/vaults_test.cljs` all use `with-redefs` plus captured dependency assertions.

- Observation: this worktree did not have a usable local JavaScript toolchain at the start of validation.
  Evidence: the first `npm test` run exited with `sh: shadow-cljs: command not found`, and `node_modules/.bin/shadow-cljs` was missing until `npm ci` completed.

- Observation: `c8` in this environment reports the target adapter coverage through the compiled `.shadow-cljs/builds/test/dev/out/cljs-runtime/**` paths rather than remapping cleanly back to `/hyperopen/src/**`.
  Evidence: `coverage/coverage-summary.json` records the relevant rows under `/Users/barry/.codex/worktrees/f127/hyperopen/.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen/runtime/effect_adapters/leaderboard.cljs` and the matching `staking.cljs` path, and `coverage/test/dev/out/cljs-runtime/hyperopen/runtime/effect_adapters/index.html` shows the same percentages.

- Observation: the required repo-wide validation commands are currently blocked by unrelated baseline issues outside the changed test files.
  Evidence: `npm test` and the first pass of the coverage run stop at `views-do-not-use-forbidden-sub-16px-explicit-text-utilities-test`, which reports `src/hyperopen/views/notifications_view.cljs => ["text-[13px]"]`. `npm run check` later aborts in unrelated `shadow-cljs` compile work with dependency wait errors such as `replicant/dom.cljs` waiting for `replicant.alias`.

- Observation: the reviewer surfaced a real weakness in the first draft of the new adapter tests.
  Evidence: the initial assertions collapsed captured calls into maps or partially inspected grouped results, which meant a duplicate delegation could have been hidden. The final test revision now asserts exact call count and call order before inspecting the dependency maps.

## Decision Log

- Decision: keep this task test-only and avoid production changes unless a test exposes an actual defect.
  Rationale: the adapter code already expresses the intended behavior clearly; the coverage gap is due to missing tests, not a known runtime bug.
  Date/Author: 2026-03-26 / Codex

- Decision: treat this as non-UI work and skip browser QA.
  Rationale: the target files are runtime adapter namespaces with no view rendering, DOM behavior, or governed browser workflow surface. The meaningful acceptance signal is deterministic test and coverage evidence.
  Date/Author: 2026-03-26 / Codex

- Decision: test the staking submit adapters at the module level instead of relying only on the facade wrappers in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`.
  Rationale: the low reported branch coverage lives in `/hyperopen/src/hyperopen/runtime/effect_adapters/staking.cljs`, specifically in the multi-arity functions and default `show-toast!` path. Direct tests on that namespace are the smallest way to cover the missing branches.
  Date/Author: 2026-03-26 / Codex

- Decision: stop short of unrelated production fixes even though validation exposed a baseline typography failure elsewhere in the repo.
  Rationale: the user asked for coverage improvements on the two adapter namespaces. Expanding this task into typography policy work in `/hyperopen/src/hyperopen/views/notifications_view.cljs` would have blurred the scope and risked unnecessary churn in unrelated files.
  Date/Author: 2026-03-26 / Codex

## Outcomes & Retrospective

The requested coverage work is complete. `/hyperopen/test/hyperopen/runtime/effect_adapters/leaderboard_test.cljs` now locks the leaderboard fetch dependency map plus preference persistence and restore delegation. `/hyperopen/test/hyperopen/runtime/effect_adapters/staking_test.cljs` now locks all six staking fetch adapters and the default-versus-custom toast seam for deposit, withdraw, delegate, and undelegate submits. `/hyperopen/test/test_runner_generated.cljs` was regenerated so both namespaces are part of the standard compiled runner.

The measured coverage improvement from the generated `c8` report is meaningful even though it is reported through the compiled `shadow-cljs` output paths in this environment. In `coverage/test/dev/out/cljs-runtime/hyperopen/runtime/effect_adapters/index.html`, `leaderboard.cljs` moved from the user-supplied baseline of `38.23%` lines and `0%` branches/functions to `52.94%` lines, `100%` branches, and `100%` functions. `staking.cljs` moved from `46.15%` lines and `0%` branches/functions to `96.58%` lines, `100%` branches, and `60%` functions. The remaining function gap on `staking.cljs` comes from the way the current coverage tooling attributes the multi-arity submit wrappers in the compiled output, not from missing submit-path assertions in the new tests.

Overall complexity went down. Before this change, the target adapter coverage depended on indirect exercise from unrelated flows and left the dependency maps largely unverified. After this change, the intended seams are described and asserted directly in dedicated tests, so future refactors in these adapters are less likely to drift silently.

## Context and Orientation

`/hyperopen/src/hyperopen/runtime/effect_adapters/leaderboard.cljs` is the runtime seam that injects Hyperopen’s leaderboard API, cache, projection, and clock dependencies into `hyperopen.leaderboard.effects` and `hyperopen.leaderboard.preferences`. It also owns the small internal set of known excluded addresses used during leaderboard fetches.

`/hyperopen/src/hyperopen/runtime/effect_adapters/staking.cljs` is the equivalent runtime seam for staking. The fetch functions supply request and projection dependencies to `hyperopen.staking.effects`. The submit functions also inject `nexus.registry/dispatch`, the shared runtime error helpers from `/hyperopen/src/hyperopen/runtime/effect_adapters/common.cljs`, and an optional `show-toast!` callback that defaults to a no-op.

The existing neighboring tests in `/hyperopen/test/hyperopen/runtime/effect_adapters/*.cljs` establish the house style for this work: redefine the underlying effect functions, capture the dependency maps, assert the exact injected seams, and only execute lightweight defaults such as no-op toast handlers when necessary.

The public runtime facade in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` already exposes both target namespaces, and the generated test runner at `/hyperopen/test/test_runner_generated.cljs` must be refreshed after adding new test namespaces so `npm test` compiles and executes them.

## Plan of Work

First, add `/hyperopen/test/hyperopen/runtime/effect_adapters/leaderboard_test.cljs`. This file should prove that the facade aliases in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` still point at the leaderboard module, that `api-fetch-leaderboard-effect` passes the expected API, cache, projection, excluded-address, clock, store, and optional `opts` dependencies into `hyperopen.leaderboard.effects/api-fetch-leaderboard!`, and that the persistence and restore helpers delegate correctly to `hyperopen.leaderboard.preferences`.

Second, add `/hyperopen/test/hyperopen/runtime/effect_adapters/staking_test.cljs`. This file should prove that the staking facade aliases are intact, that each fetch adapter supplies the correct store, address, request function, and projection callbacks to `hyperopen.staking.effects`, and that each submit adapter covers both arities: the default three-argument path that should inject a no-op `show-toast!`, and the four-argument path that should preserve a custom `show-toast!` function.

Third, regenerate the test runner and run validations. This repo does not expose a narrower namespace-filtered `npm test` entry point, so the smallest practical execution pass is the full `npm test` runner. Use `npm run coverage` to confirm the report moved in the intended direction. Because the root repo contract requires it whenever code changes, finish with `npm run check`, `npm test`, and `npm run test:websocket`, while recording any unrelated baseline blockers explicitly.

## Concrete Steps

From `/Users/barry/.codex/worktrees/f127/hyperopen`:

1. Add the focused adapter tests.

       edit test/hyperopen/runtime/effect_adapters/leaderboard_test.cljs
       edit test/hyperopen/runtime/effect_adapters/staking_test.cljs

   Expect the leaderboard file to capture one fetch dependency map and two preference delegation calls, and the staking file to capture six fetch maps plus eight submit dependency maps covering default and custom toast branches.

2. Refresh the generated runner.

       npm run test:runner:generate

   Expect `/hyperopen/test/test_runner_generated.cljs` to include the two new namespaces.

3. Run the smallest relevant focused test command first.

       npm test

   This repository’s generated runner does not support namespace filtering. Use the full run and confirm that `hyperopen.runtime.effect-adapters.leaderboard-test` and `hyperopen.runtime.effect-adapters.staking-test` appear in the output.

4. Measure coverage and then run the required repository gates.

       npm test
       rm -rf .coverage coverage
       npx shadow-cljs compile test
       NODE_V8_COVERAGE=.coverage node out/test.js
       npx c8 report --temp-directory .coverage --reporter json-summary
       npm run test:websocket
       npm run check

   Expect the coverage report for the two target adapter files to increase substantially. In the current repository state, `npm run test:websocket` should exit with status `0`, while `npm test` and `npm run check` still surface unrelated baseline failures that must be recorded rather than silently ignored.

## Validation and Acceptance

Acceptance for this task is entirely test-driven.

`/hyperopen/test/hyperopen/runtime/effect_adapters/leaderboard_test.cljs` must prove that the leaderboard adapter passes the correct dependency seams into the workflow layer and delegates the preference persistence helpers without mutating the contract.

`/hyperopen/test/hyperopen/runtime/effect_adapters/staking_test.cljs` must prove that every fetch adapter forwards the correct request and projection callbacks, and that every submit adapter covers both the default no-op toast path and the explicit custom toast path.

The generated coverage report must show the target files with materially improved statement, branch, function, and line coverage compared with the baseline screenshot supplied by the user. In the current run, the relevant rows are in `coverage/test/dev/out/cljs-runtime/hyperopen/runtime/effect_adapters/index.html`, where `leaderboard.cljs` now reports `52.94%` lines, `100%` branches, and `100%` functions, and `staking.cljs` now reports `96.58%` lines, `100%` branches, and `60%` functions.

Required validation commands for this task are:

    npm test
    npm run test:websocket
    npm run check

Browser QA is explicitly not required because this task does not change UI code or browser-tooling behavior.

## Idempotence and Recovery

These test additions are safe to repeat because they only add new test namespaces and regenerate the test runner. If a validation command fails, fix the specific test or runner issue and rerun the same command. Do not reset unrelated files. If the generated runner changes unexpectedly, rerun `npm run test:runner:generate` from the repository root and inspect only the added namespace entries.

## Artifacts and Notes

The user-provided baseline is the coverage screenshot showing approximately `38.23%` statement coverage for `/hyperopen/src/hyperopen/runtime/effect_adapters/leaderboard.cljs` and `46.15%` statement coverage for `/hyperopen/src/hyperopen/runtime/effect_adapters/staking.cljs`.

Validation evidence captured in this session:

    npm ci

completed successfully after the initial `shadow-cljs` binary lookup failure.

    npm test

now compiles and runs the new adapter tests successfully, but the overall command still fails on the unrelated baseline typography guard `views-do-not-use-forbidden-sub-16px-explicit-text-utilities-test` against `/hyperopen/src/hyperopen/views/notifications_view.cljs`.

    rm -rf .coverage coverage
    npx shadow-cljs compile test
    NODE_V8_COVERAGE=.coverage node out/test.js
    npx c8 report --temp-directory .coverage --reporter json-summary

records the target adapter coverage rows after the test run exited with the same unrelated typography failure:

    .../runtime/effect_adapters/leaderboard.cljs  lines 18/34 (52.94%), branches 3/3 (100%), functions 2/2 (100%)
    .../runtime/effect_adapters/staking.cljs     lines 113/117 (96.58%), branches 6/6 (100%), functions 6/10 (60%)

    node out/test.js >/tmp/hyperopen-test.log 2>&1; tail -n 80 /tmp/hyperopen-test.log

confirms the latest rerun still ends with:

    Ran 2764 tests containing 14687 assertions.
    1 failures, 0 errors.

and the only failure in `/tmp/hyperopen-test.log` is the tracked typography guard in `hyperopen-ajjb`.

    npm run test:websocket

passed with:

    Ran 416 tests containing 2339 assertions.
    0 failures, 0 errors.

    npm run check

completed the lint/test/tooling phases and the `shadow-cljs` `app` build, then aborted later in unrelated aggregate compile work with dependency wait errors such as `replicant/dom.cljs` waiting for `replicant.alias`. That follow-up is tracked as `hyperopen-sfry`.

## Interfaces and Dependencies

The leaderboard tests must exercise these public functions in `/hyperopen/src/hyperopen/runtime/effect_adapters/leaderboard.cljs`:

- `api-fetch-leaderboard-effect`
- `persist-leaderboard-preferences-effect`
- `restore-leaderboard-preferences!`

The staking tests must exercise these public functions in `/hyperopen/src/hyperopen/runtime/effect_adapters/staking.cljs`:

- `api-fetch-staking-validator-summaries-effect`
- `api-fetch-staking-delegator-summary-effect`
- `api-fetch-staking-delegations-effect`
- `api-fetch-staking-rewards-effect`
- `api-fetch-staking-history-effect`
- `api-fetch-staking-spot-state-effect`
- `api-submit-staking-deposit-effect`
- `api-submit-staking-withdraw-effect`
- `api-submit-staking-delegate-effect`
- `api-submit-staking-undelegate-effect`

The submit adapter assertions must verify the presence of these injected seams where applicable:

- `nexus.registry/dispatch`
- `hyperopen.runtime.effect-adapters.common/exchange-response-error`
- `hyperopen.runtime.effect-adapters.common/runtime-error-message`
- the default no-op `show-toast!` callback when no custom seam is supplied

Revision note: updated on 2026-03-26 after implementation to record the added adapter tests, the measured coverage deltas, the reviewer-driven duplicate-call hardening, the successful websocket validation, and the unrelated baseline blockers tracked as `hyperopen-ajjb` and `hyperopen-sfry`.
