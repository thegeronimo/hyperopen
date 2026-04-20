# Increase portfolio coverage and mutation score

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`. It is self-contained so a future contributor can continue from this file without relying on conversation history.

Tracked issue: `hyperopen-4uxr` ("Increase portfolio coverage and mutation score").

## Purpose / Big Picture

The portfolio coverage report shows weak coverage for the compiled websocket-test copies of `hyperopen.portfolio.actions` and `hyperopen.portfolio.fee-schedule`. The ordinary CLJS test build already exercises many of these source namespaces, but mutation scan evidence shows a few public behaviors and branch-sensitive paths are still under-tested. After this work, the portfolio tests should cover the benchmark selection effect planner, fee-schedule option normalization, active market classification, and connected-wallet fee derivation well enough that source-level mutation testing kills the new target mutants.

The user-visible outcome is confidence, not a UI change: running the focused portfolio tests, refreshing coverage, and running mutation testing for the two source modules should demonstrate stronger tests over the same behavior the portfolio page uses.

## Progress

- [x] (2026-04-19 22:05Z) Created and claimed `bd` issue `hyperopen-4uxr`.
- [x] (2026-04-19 22:06Z) Initial `npm run coverage` failed because this worktree had no installed `node_modules`; `out/test.js` could not resolve `lucide/dist/esm/icons/external-link.js`.
- [x] (2026-04-19 22:07Z) Ran `npm ci` to install locked dependencies locally.
- [x] (2026-04-19 22:08Z) Reran `npm run coverage`; 3298 ordinary tests and 449 websocket tests passed, and coverage artifacts were written under `coverage/`.
- [x] (2026-04-19 22:08Z) Ran mutation scan for `src/hyperopen/portfolio/actions.cljs`; 64 of 66 mutation sites were covered and lines 321 and 337 were uncovered.
- [x] (2026-04-19 22:09Z) Ran mutation scan for `src/hyperopen/portfolio/fee_schedule.cljs`; 71 of 74 mutation sites were covered and lines 223, 273, and 287 were uncovered.
- [x] (2026-04-19 22:09Z) Received read-only acceptance-test proposal covering portfolio benchmark effects, option normalizers, active market model cases, wallet fee derivation, and scenario-preview labels.
- [x] (2026-04-19 22:10Z) Wrote this active ExecPlan.
- [x] (2026-04-19 22:16Z) Added focused tests to `test/hyperopen/portfolio/actions_test.cljs`.
- [x] (2026-04-19 22:16Z) Added focused tests to `test/hyperopen/portfolio/fee_schedule_test.cljs`.
- [x] (2026-04-19 22:17Z) Focused portfolio CLJS tests passed with 35 tests, 227 assertions, 0 failures, and 0 errors.
- [x] (2026-04-19 22:18Z) Refreshed coverage after the first test additions; source-level mutation scans improved to full site coverage for both target modules after adding narrower active-market and anchor assertions.
- [x] (2026-04-19 22:25Z) Targeted actions mutation execution killed 14 of 14 selected mutants.
- [x] (2026-04-19 22:48Z) Targeted fee-schedule mutation execution killed 47 of 55 selected mutants; eight survivors were inspected.
- [x] (2026-04-19 22:52Z) Added public-output assertions for meaningful fee-schedule survivors at lines 459, 478, and 552 and reran those mutants; all 3 were killed.
- [x] (2026-04-19 22:54Z) Refreshed final coverage after survivor-focused assertions; ordinary test-build line coverage is 99.83% for `actions.cljs` and 98.77% for `fee_schedule.cljs`.
- [x] (2026-04-19 22:57Z) `npm run check` passed after adding namespace-size exception metadata for the enlarged test namespaces.
- [x] (2026-04-19 22:58Z) `npm test` passed with 3310 tests, 18085 assertions, 0 failures, and 0 errors.
- [x] (2026-04-19 22:59Z) `npm run test:websocket` passed with 449 tests, 2701 assertions, 0 failures, and 0 errors.
- [x] (2026-04-19 23:00Z) Moved this ExecPlan from active to completed.

## Surprises & Discoveries

- Observation: The screenshot points at `.shadow-cljs/builds/ws-test/dev/out/cljs-runtime/hyperopen/portfolio/actions.cljs` and `fee_schedule.cljs`, but source-level coverage in the ordinary `test` build is much higher.
  Evidence: `coverage/coverage-summary.json` recorded test-build coverage for `actions.cljs` at 98.37% lines, 90.38% functions, and 78.75% branches, and `fee_schedule.cljs` at 94.5% lines, 95.91% functions, and 84.69% branches. The duplicated `ws-test` compiled copies remained at 35.38% and 52.97% line coverage because the websocket suite compiles shared dependencies but does not directly exercise these portfolio public functions.

- Observation: Mutation scan still found useful uncovered source-level mutation sites even though test-build line coverage was high.
  Evidence: `bb tools/mutate.clj --scan --module src/hyperopen/portfolio/actions.cljs --suite test --format json` reported uncovered mutation sites at source lines 321 and 337, both `when -> when-not` conditionals in anchor-normalization helper forms. `bb tools/mutate.clj --scan --module src/hyperopen/portfolio/fee_schedule.cljs --suite test --format json` reported uncovered sites at source lines 223, 273, and 287 in string market normalization and active-market scenario classification.

- Observation: Installing dependencies was necessary before any CLJS validation could run in this worktree.
  Evidence: Before `npm ci`, `npm run coverage` and focused `npm test -- --test=...` failed with `Error: Cannot find module 'lucide/dist/esm/icons/external-link.js'`. After `npm ci`, `npm run coverage` passed with 3298 tests, 18013 assertions, 0 failures, 0 errors for the ordinary suite and 449 tests, 2701 assertions, 0 failures, 0 errors for the websocket suite.

- Observation: The focused test-only edit compiled cleanly and passed before mutation testing.
  Evidence: `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.actions-test --test=hyperopen.portfolio.fee-schedule-test` passed with 35 tests, 227 assertions, 0 failures, and 0 errors.

- Observation: The added tests lifted ordinary test-build coverage for both target files, while the websocket compiled copies stayed low as expected.
  Evidence: Final `coverage/coverage-summary.json` recorded `actions.cljs` at 99.83% lines, 90.38% functions, and 88.13% branches in the ordinary test build, up from 98.37% lines and 78.75% branches. It recorded `fee_schedule.cljs` at 98.77% lines, 95.91% functions, and 94.69% branches, up from 94.5% lines and 84.69% branches. The `ws-test` compiled copies remained unchanged at 35.38% and 52.97% line coverage because no websocket-only bridge tests were added.

- Observation: Mutation scans now report full covered-site counts for both target source modules.
  Evidence: `bb tools/mutate.clj --scan --module src/hyperopen/portfolio/actions.cljs --suite test --format json` reported 66 total, 66 covered, 0 uncovered. `bb tools/mutate.clj --scan --module src/hyperopen/portfolio/fee_schedule.cljs --suite test --format json` reported 74 total, 74 covered, 0 uncovered.

- Observation: Targeted mutation execution confirmed the new action tests are strong over the selected public behavior.
  Evidence: `bb tools/mutate.clj --module src/hyperopen/portfolio/actions.cljs --suite test --lines 203,220,241,249,302,307,309,319,321,328,337,345,542,568` killed 14 of 14 selected mutants.

- Observation: Targeted mutation execution confirmed most new fee-schedule tests are strong and identified five residual low-value or public-unreachable survivors after meaningful survivors were fixed.
  Evidence: The first fee-schedule mutation run killed 47 of 55 selected mutants. After adding public assertions for selected market option state, missing referral defaults, and selected control-option state, `bb tools/mutate.clj --module src/hyperopen/portfolio/fee_schedule.cljs --suite test --lines 459,478,552` killed 3 of 3 selected mutants. The remaining inspected survivors are line 347 `0 -> 1` in the private defensive non-finite formatter fallback, line 348 `< -> <=` at the display-zero epsilon boundary, line 373 `0 -> 1` in an option-discount fallback that public normalizers route around, line 474 `< -> <=` at the approximate-equality epsilon boundary, and line 493 `0 -> 1` in a staking-discount fallback that does not map to any public staking tier. These are not useful to chase with brittle or private-helper tests.

- Observation: The test-only coverage increase pushed two test namespaces past namespace-size guardrails.
  Evidence: The first `npm run check` failed at `lint:namespace-sizes` with `test/hyperopen/portfolio/fee_schedule_test.cljs - namespace has 520 lines` and `test/hyperopen/portfolio/actions_test.cljs - namespace has 705 lines; exception allows at most 600`. Updating `dev/namespace_size_exceptions.edn` with bounded test-split rationale let `npm run check` pass.

## Decision Log

- Decision: Target ordinary CLJS source tests rather than adding websocket-only coverage bridge tests for these portfolio namespaces.
  Rationale: The screenshot's low rows are duplicate compiled `ws-test` artifacts. The source modules are portfolio model/action code, not websocket runtime behavior. Source-level mutation scans and ordinary CLJS tests are the right confidence signal and avoid polluting websocket tests with unrelated portfolio assertions.
  Date/Author: 2026-04-19 / Codex

- Decision: Keep implementation scope to tests and plan metadata unless mutation results expose a real production bug.
  Rationale: The request is to increase test coverage and confirm soundness with mutation testing. Public APIs should be preserved, and no source behavior change is required by the current evidence.
  Date/Author: 2026-04-19 / Codex

- Decision: Use mutation scan uncovered lines to choose a small number of high-value tests, then run mutation execution on the changed modules after coverage refresh.
  Rationale: Adding tests solely to move line percentages can produce weak assertions. Mutation testing provides a stronger check that the new tests fail for meaningful behavior changes.
  Date/Author: 2026-04-19 / Codex

- Decision: Do not add tests for residual exact-epsilon or public-unreachable private fallback fee-schedule survivors.
  Rationale: The remaining survivors would require testing private helper details or exact floating-point boundaries that are not product requirements. Public-output assertions already kill the meaningful selected-option and missing-referral mutants exposed by the first mutation run.
  Date/Author: 2026-04-19 / Codex

## Outcomes & Retrospective

Completed. The change is test-only except for namespace-size exception metadata and this ExecPlan. It increases confidence without changing production behavior: benchmark effect planning, anchor normalization, fee option normalization, active market classification, wallet fee derivation, and selected/current option decoration now have stronger public-output assertions. Mutation testing killed every selected action mutant and every meaningful selected fee-schedule survivor after one tightening pass. Overall complexity is slightly higher in the test suite because the existing test namespaces are larger, but production complexity is unchanged and the namespace-size exceptions now explicitly track the deferred split.

## Context and Orientation

The working directory is `/Users/barry/.codex/worktrees/bfba/hyperopen`.

The target production namespaces are:

- `src/hyperopen/portfolio/actions.cljs`, which exposes portfolio action reducers and effect planners. The tests assert returned effect vectors such as `[:effects/save-many ...]`, `[:effects/fetch-candle-snapshot ...]`, and `[:effects/api-fetch-vault-benchmark-details ...]`.
- `src/hyperopen/portfolio/fee_schedule.cljs`, which exposes the fee schedule model and public normalizers used by actions and views. The tests assert returned model maps and formatted fee rows.

The existing deterministic tests are:

- `test/hyperopen/portfolio/actions_test.cljs`
- `test/hyperopen/portfolio/fee_schedule_test.cljs`

The repo-local mutation tool is `bb tools/mutate.clj`. It needs `coverage/lcov.info`, which is produced by `npm run coverage`, before scan and run modes can route mutation sites to the correct test suite.

The term "mutation site" means a small automatic source edit such as changing `=` to `not=` or `when` to `when-not`. A test "kills" a mutant when the altered behavior makes the test suite fail. Surviving mutants are evidence that tests may be too weak or the mutation is equivalent to the original behavior.

## Plan of Work

First, extend `test/hyperopen/portfolio/actions_test.cljs` with tests for public benchmark normalization and vault benchmark effect planning. Add assertions for `normalize-portfolio-returns-benchmark-coin`, `normalize-portfolio-returns-benchmark-coins`, `vault-benchmark-address`, `selected-portfolio-vault-benchmark-addresses`, `ensure-portfolio-vault-benchmark-effects`, `set-portfolio-returns-benchmark-suggestions-open`, `select-portfolio-returns-benchmark`, and `set-portfolio-metrics-result`. These functions are public and return plain values or effect vectors, so tests should not use mocks.

Second, extend `test/hyperopen/portfolio/fee_schedule_test.cljs` with tests for public option normalizers and active market model derivation. Add assertions for referral, staking, and maker rebate normalizers accepting labels and descriptions. Add model assertions for a spot stable aligned market, a HIP-3 growth aligned active market whose fields arrive as strings, wallet discounts derived from numeric strings, and helper/current-option labels when local what-if selections differ from wallet defaults. These cases cover source mutation lines in market-type normalization and active-market scenario classification through public `fee-schedule-model` output.

Third, run the focused portfolio tests. Use:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.actions-test --test=hyperopen.portfolio.fee-schedule-test

Expected result after the test-only edits: both namespaces pass with 0 failures and 0 errors.

Fourth, refresh coverage with:

    npm run coverage

Expected result: the ordinary and websocket suites pass and `coverage/lcov.info` is present. Record the updated target-file coverage in `Surprises & Discoveries` or `Outcomes & Retrospective`.

Fifth, rerun mutation scans:

    bb tools/mutate.clj --scan --module src/hyperopen/portfolio/actions.cljs --suite test
    bb tools/mutate.clj --scan --module src/hyperopen/portfolio/fee_schedule.cljs --suite test

Expected result: the prior uncovered source lines should become covered or be documented as intentionally unreachable through public behavior if they remain uncovered.

Sixth, run mutation execution for the targeted modules. Prefer line-limited runs against the newly covered sites if full-module runs are too slow, but run full modules if they complete in reasonable time:

    bb tools/mutate.clj --module src/hyperopen/portfolio/actions.cljs --suite test --mutate-all
    bb tools/mutate.clj --module src/hyperopen/portfolio/fee_schedule.cljs --suite test --mutate-all

Expected result: report the killed/total counts. Any surviving mutant must be inspected. Add tests for meaningful survivors; document equivalent or low-value survivors if the behavior cannot be distinguished through the public API.

Finally, run the required gates for code/test changes:

    npm run check
    npm test
    npm run test:websocket

No browser QA is required because this work does not touch `/src/hyperopen/views/**`, `/src/styles/**`, or browser interaction flows.

## Concrete Steps

1. Edit `test/hyperopen/portfolio/actions_test.cljs` near the existing benchmark tests. Keep the existing effect-vector style and reuse the local `replace-shareable-route-query-effect` var.

2. Edit `test/hyperopen/portfolio/fee_schedule_test.cljs` after the existing market normalizer and model tests. Keep assertions on public `fee-schedule` functions and model output.

3. Run the focused portfolio command from `/Users/barry/.codex/worktrees/bfba/hyperopen`.

4. If the focused tests fail, read the assertion output and correct the test expectations or the production bug that the test exposed. Do not weaken assertions just to pass.

5. Run coverage and mutation commands from the same working directory.

6. Update this ExecPlan after each significant result, including mutation survivors and final gates.

## Validation and Acceptance

The work is accepted when all of these are true:

1. `test/hyperopen/portfolio/actions_test.cljs` includes new assertions for benchmark coin normalization, vault-address extraction, deduplicated selected vault addresses, metadata/detail fetch planning, cached-detail suppression, and metrics-result saving.

2. `test/hyperopen/portfolio/fee_schedule_test.cljs` includes new assertions for fee schedule option normalizers, active spot stable aligned market classification, HIP-3 growth aligned active context classification, wallet discount derivation from API-like rate strings, and scenario-preview helper/current labels.

3. The focused portfolio test command passes with 0 failures and 0 errors.

4. `npm run coverage` passes and writes `coverage/lcov.info`.

5. Mutation scan and mutation execution for both target modules complete, and meaningful survivors are either killed by additional tests or documented with a clear reason.

6. `npm run check`, `npm test`, and `npm run test:websocket` pass before the plan is moved from `active` to `completed`.

## Idempotence and Recovery

All edits are additive tests and this plan file. Running `npm run test:runner:generate` is safe because `test/test_runner_generated.cljs` is generated by the repo tooling. Running `npm run coverage` replaces `.coverage` and `coverage/` artifacts; these are generated validation outputs. Running `bb tools/mutate.clj` writes reports under `target/mutation/`; those are generated artifacts.

If mutation execution is interrupted, rerun the same command after confirming no `.mutation-backup` files remain. The mutation tool includes filesystem restoration tests, but a cautious retry should run `git status --short` first and inspect any unexpected source changes before continuing.

## Artifacts and Notes

Baseline coverage evidence after `npm ci` and the first successful `npm run coverage`:

    Ran 3298 tests containing 18013 assertions.
    0 failures, 0 errors.
    Ran 449 tests containing 2701 assertions.
    0 failures, 0 errors.
    Statements: 90.62%, Branches: 69.45%, Functions: 83.58%, Lines: 90.62%.

Target-file baseline from `coverage/coverage-summary.json`:

    test build actions.cljs: 98.37% lines, 90.38% functions, 78.75% branches.
    test build fee_schedule.cljs: 94.5% lines, 95.91% functions, 84.69% branches.
    ws-test build actions.cljs: 35.38% lines, 0% functions, 0% branches.
    ws-test build fee_schedule.cljs: 52.97% lines, 2.04% functions, 5.45% branches.

Mutation scan baseline:

    actions.cljs: 66 total sites, 64 covered, 2 uncovered at lines 321 and 337.
    fee_schedule.cljs: 74 total sites, 71 covered, 3 uncovered at lines 223, 273, and 287.

Final focused evidence before repository gates:

    Focused portfolio command: 35 tests, 236 assertions, 0 failures, 0 errors.
    Final coverage: 3310 ordinary tests, 18085 assertions, 0 failures, 0 errors; 449 websocket tests, 2701 assertions, 0 failures, 0 errors.
    Final source scan: actions.cljs 66/66 covered mutation sites; fee_schedule.cljs 74/74 covered mutation sites.
    Mutation execution: actions selected lines 14/14 killed; fee_schedule selected lines 47/55 killed initially, then meaningful survivor rerun lines 459,478,552 killed 3/3.

Final gate evidence:

    npm run check: passed.
    npm test: 3310 tests, 18085 assertions, 0 failures, 0 errors.
    npm run test:websocket: 449 tests, 2701 assertions, 0 failures, 0 errors.

## Interfaces and Dependencies

No production interfaces should change.

The tests should continue to use `cljs.test` with `deftest` and `is`. The only namespaces required by the new tests should be the existing aliases in each file:

- `hyperopen.portfolio.actions-test` already requires `hyperopen.portfolio.actions` as `actions` and `hyperopen.platform` as `platform`.
- `hyperopen.portfolio.fee-schedule-test` already requires `hyperopen.portfolio.fee-schedule` as `fee-schedule`.

Do not add browser, network, or filesystem dependencies for these tests. The functions under test operate on plain maps and return plain values.

## Revision Notes

- 2026-04-19 22:10Z: Initial active ExecPlan created from current coverage, mutation scan evidence, and acceptance-test proposal. This plan intentionally targets source-level portfolio tests rather than websocket-only bridge assertions because the low screenshot rows are duplicated compiled artifacts, not websocket behavior.
- 2026-04-19 22:55Z: Updated plan with implemented tests, final coverage, mutation scan results, targeted mutation execution results, and residual survivor rationale before repository gates.
- 2026-04-19 23:00Z: Recorded successful repository gates and moved the plan to completed.
