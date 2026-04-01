# Close Custom Mutation Follow-Up Batch Survivors

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-ymir`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

The custom mutation follow-up batch from April 1, 2026 currently proves that several high-value seams still have surviving mutants or uncovered selected mutation sites. The immediate goal is to restore a green coverage build, close the remaining survivors in the custom batch, and rerun the exact batch config until the result is a verified zero-survivor report. After this pass, the code should behave exactly as before to users, but the funding, vault, leaderboard, lifecycle-polling, and trading seams should have stronger direct regression evidence and a clean custom mutation summary.

## Progress

- [x] (2026-04-01 15:59Z) Created and claimed `hyperopen-ymir` for the custom mutation follow-up batch closure pass.
- [x] (2026-04-01 15:59Z) Audited `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md` to re-enter the governed multi-agent workflow.
- [x] (2026-04-01 15:59Z) Confirmed the latest successful custom mutation report at `/hyperopen/target/mutation/nightly/2026-04-01T13-23-51.559201Z/summary.md`: `79` survivors and `24` uncovered selected mutants across `funding/domain/policy`, `api/endpoints/vaults`, `api/endpoints/leaderboard`, `funding/application/lifecycle_polling`, `api/trading`, and `portfolio/actions`.
- [x] (2026-04-01 15:59Z) Reproduced the current runner blocker with `npm run coverage` and identified three failing mutation-pass tests in `/hyperopen/test/hyperopen/api/endpoints/vaults_test.cljs`, `/hyperopen/test/hyperopen/api/trading/internal_seams_test.cljs`, and `/hyperopen/test/hyperopen/funding/domain/policy_test.cljs`.
- [x] (2026-04-01 16:03Z) Corrected the three mismatched test expectations and restored a green `npm run coverage` run (`2944` test assertions for `test`, `430` websocket assertions, `0` failures).
- [ ] Use parallel worker passes on the surviving custom-batch modules, keeping ownership disjoint by test file and preferring test-only fixes unless a real source defect is proven.
- [ ] Rerun `bb tools/mutate_nightly.clj --config target/mutation/custom/followups-2026-03-29-to-2026-03-31.edn`, confirm zero survivors and zero target failures, then run the required repo gates and close `hyperopen-ymir`.

## Surprises & Discoveries

- Observation: the failed nightly rerun did not indicate a mutation-runner bug first; it failed because `npm run coverage` was red after the new mutation-oriented tests were added.
  Evidence: direct `npm run coverage` reproduction now fails with three assertion mismatches and no compile errors.

- Observation: the current blocker is test expectation drift, not source breakage.
  Evidence: the failing assertions are all expectation mismatches against real existing behavior: one preview-series sample shape, one trading nonce-error interpretation, and one funding deposit copy string.

- Observation: the failed rerun directory `/hyperopen/target/mutation/nightly/2026-04-01T15-52-02.249401Z` is empty, so there is no newer verified mutation evidence after the successful `79`-survivor summary.
  Evidence: the directory exists with no files and no `summary.json`.

- Observation: once the three assertion mismatches were corrected, the full coverage pipeline succeeded without any code changes outside the mutation-targeted test surfaces.
  Evidence: `npm run coverage` now exits `0` with `Statements 91.91%`, `Branches 69.67%`, `Functions 85.95%`, and `Lines 91.91%`.

## Decision Log

- Decision: treat the custom follow-up batch at `/hyperopen/target/mutation/custom/followups-2026-03-29-to-2026-03-31.edn` as the only acceptance target for this pass.
  Rationale: the checked-in nightly batch is already green, and the remaining actionable mutation debt is concentrated in the custom follow-up set.
  Date/Author: 2026-04-01 / Codex

- Decision: fix the coverage blocker before delegating survivor cleanup.
  Rationale: workers cannot produce trustworthy mutation closure while the shared coverage prerequisite is red.
  Date/Author: 2026-04-01 / Codex

- Decision: prefer test-only changes, escalating to production edits only if a surviving mutant exposes a real behavioral defect or a source-map blind spot that tests cannot close.
  Rationale: the user asked for a mutation pass, and the initial evidence already suggests several weak seams are test-coverage gaps rather than product bugs.
  Date/Author: 2026-04-01 / Codex

## Outcomes & Retrospective

This section will be completed when the batch rerun is verified. The intended outcome is a clean custom mutation summary with zero survivors, zero target failures, and preserved public behavior. If production edits are required, this section must record whether the result reduced or increased overall complexity and why.

## Context and Orientation

The active target batch is defined in `/hyperopen/target/mutation/custom/followups-2026-03-29-to-2026-03-31.edn`. It includes:

- `/hyperopen/src/hyperopen/api/trading.cljs`
- `/hyperopen/src/hyperopen/funding/domain/policy.cljs`
- `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`
- `/hyperopen/src/hyperopen/api/endpoints/leaderboard.cljs`
- `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs`
- `/hyperopen/src/hyperopen/portfolio/actions.cljs`

The last successful batch summary is `/hyperopen/target/mutation/nightly/2026-04-01T13-23-51.559201Z/summary.md`. That report shows `79` survivors total: `61` in funding policy, `15` in vault endpoints, `1` in leaderboard endpoints, `1` in lifecycle polling, `1` in API trading, and `0` in portfolio actions.

The mutation-oriented test edits already live in:

- `/hyperopen/test/hyperopen/funding/domain/policy_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/lifecycle_polling_test.cljs`
- `/hyperopen/test/hyperopen/api/endpoints/leaderboard_test.cljs`
- `/hyperopen/test/hyperopen/api/endpoints/vaults_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/internal_seams_test.cljs`

The mutation reports that identify surviving and uncovered lines are under `/hyperopen/target/mutation/reports/`. The most important current reports are:

- `/hyperopen/target/mutation/reports/2026-04-01T14-31-51.359371Z-src-hyperopen-funding-domain-policy.cljs.edn`
- `/hyperopen/target/mutation/reports/2026-04-01T15-25-48.882252Z-src-hyperopen-api-endpoints-vaults.cljs.edn`
- `/hyperopen/target/mutation/reports/2026-04-01T14-38-49.638496Z-src-hyperopen-api-endpoints-leaderboard.cljs.edn`
- `/hyperopen/target/mutation/reports/2026-04-01T14-37-07.847936Z-src-hyperopen-funding-application-lifecycle_polling.cljs.edn`
- `/hyperopen/target/mutation/reports/2026-04-01T13-45-36.661056Z-src-hyperopen-api-trading.cljs.edn`

## Plan of Work

First, repair the coverage prerequisite by correcting the three failing test expectations so they match the current implementation. Re-run `npm run coverage` immediately after those edits and do not proceed until the full coverage build succeeds.

Second, use the mutation reports to drive targeted survivor closure. Keep each parallel worker on disjoint test surfaces so they do not collide. The funding worker should own `/hyperopen/test/hyperopen/funding/domain/policy_test.cljs` and `/hyperopen/test/hyperopen/funding/application/lifecycle_polling_test.cljs`. The endpoints worker should own `/hyperopen/test/hyperopen/api/endpoints/vaults_test.cljs` and `/hyperopen/test/hyperopen/api/endpoints/leaderboard_test.cljs`. The trading worker should own `/hyperopen/test/hyperopen/api/trading/internal_seams_test.cljs` and only escalate to `/hyperopen/src/hyperopen/api/trading.cljs` if a source defect or coverage-mapping blind spot is proven.

Third, rerun the custom mutation batch and inspect the fresh nightly summary plus module reports. If survivors remain, iterate on the specific module with the weakest evidence first until the batch is clean. When the batch reaches zero survivors, run the required repo gates and record the exact results in this plan before moving it to `completed` and closing `hyperopen-ymir`.

## Concrete Steps

All commands are run from `/Users/barry/.codex/worktrees/a996/hyperopen`.

Immediate unblock:

    npm run coverage

Targeted mutation rerun:

    bb tools/mutate_nightly.clj --config target/mutation/custom/followups-2026-03-29-to-2026-03-31.edn

Focused single-module mutation reruns when needed:

    bb tools/mutate.clj --module src/hyperopen/funding/domain/policy.cljs --suite test --mutate-all
    bb tools/mutate.clj --module src/hyperopen/api/endpoints/vaults.cljs --suite test --mutate-all
    bb tools/mutate.clj --module src/hyperopen/api/endpoints/leaderboard.cljs --suite test --mutate-all
    bb tools/mutate.clj --module src/hyperopen/funding/application/lifecycle_polling.cljs --suite test --mutate-all
    bb tools/mutate.clj --module src/hyperopen/api/trading.cljs --suite test --mutate-all

Required final gates:

    npm run check
    npm test
    npm run test:websocket

## Validation and Acceptance

Acceptance for this pass is strict:

- `npm run coverage` succeeds with the mutation-oriented tests in place.
- `bb tools/mutate_nightly.clj --config target/mutation/custom/followups-2026-03-29-to-2026-03-31.edn` completes successfully.
- The resulting summary reports `0` surviving mutants and `0` failed targets for the custom batch.
- Any remaining uncovered selected mutants must either be closed or explicitly justified as source-map/tooling limitations with matching evidence.
- `npm run check`, `npm test`, and `npm run test:websocket` pass on the final tree.

## Idempotence and Recovery

The test-only edits planned here are safe to rerun. If a mutation execution is interrupted, inspect the touched source files before trusting the worktree because the mutation runner has previously restored stale backups on some surfaces. The safe recovery path is: confirm `git status`, restore only unintended mutations with surgical patches, rerun `npx shadow-cljs compile test`, then rerun `npm run coverage` before resuming mutation execution.

## Artifacts and Notes

Current verified baseline:

    target/mutation/nightly/2026-04-01T13-23-51.559201Z/summary.md
      Executed mutants: 282
      Killed mutants: 203
      Surviving mutants: 79
      Uncovered selected mutants: 24
      Failed targets: 0

Current failed rerun:

    target/mutation/nightly/2026-04-01T15-52-02.249401Z/
      no summary artifacts written
      failure occurred during coverage rebuild

Coverage blocker evidence from the current tree:

    npm run coverage
      pass after fixing the three expectation mismatches
      Statements 91.91%
      Branches 69.67%
      Functions 85.95%
      Lines 91.91%

## Interfaces and Dependencies

The public runtime behavior under test must remain stable. The primary interfaces are the existing functions in:

- `/hyperopen/src/hyperopen/funding/domain/policy.cljs`
- `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`
- `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs`
- `/hyperopen/src/hyperopen/api/endpoints/leaderboard.cljs`
- `/hyperopen/src/hyperopen/api/trading.cljs`

The mutation runner depends on:

- `coverage/lcov.info` from `npm run coverage`
- `/hyperopen/tools/mutate.clj`
- `/hyperopen/tools/mutate_nightly.clj`
- `/hyperopen/target/mutation/custom/followups-2026-03-29-to-2026-03-31.edn`

Plan update note (2026-04-01 15:59Z): created the initial active ExecPlan after claiming `hyperopen-ymir`, confirming the last successful `79`-survivor custom batch, reproducing the coverage blocker, and narrowing the implementation plan to coverage repair plus survivor closure on the custom follow-up targets.
Plan update note (2026-04-01 16:03Z): updated progress and discoveries after correcting the three failing mutation-pass expectations and restoring a green `npm run coverage`, which unblocked the parallel survivor-closure phase.
