# Close Custom Mutation Follow-Up Batch Survivors

This ExecPlan is a completed execution record. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` capture the implementation story that closed the work.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan was `hyperopen-ymir`. That `bd` issue was closed as `Completed` on `2026-04-02T14:10:35Z`.

## Purpose / Big Picture

The goal of this pass was to take the custom mutation follow-up batch from the April 1, 2026 baseline with `79` survivors and `24` uncovered selected mutants to a verified clean state. The scope included repairing the broken coverage prerequisite, closing the surviving mutants across the custom target set, closing the remaining uncovered lifecycle-polling seams, and leaving the repo with green required gates and test namespaces back under the repo’s size policy.

## Progress

- [x] (2026-04-01 15:59Z) Created and claimed `hyperopen-ymir` for the custom mutation follow-up batch closure pass.
- [x] (2026-04-01 15:59Z) Audited `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md` to re-enter the governed workflow.
- [x] (2026-04-01 15:59Z) Confirmed the last successful custom mutation report at `/hyperopen/target/mutation/nightly/2026-04-01T13-23-51.559201Z/summary.md`: `79` survivors and `24` uncovered selected mutants across funding policy, vault endpoints, leaderboard endpoints, lifecycle polling, API trading, and portfolio actions.
- [x] (2026-04-01 16:03Z) Reproduced the coverage blocker, corrected the three failing mutation-pass expectations, and restored a green `npm run coverage`.
- [x] (2026-04-01 20:30Z) Closed the custom batch survivors and reran `bb tools/mutate_nightly.clj --config target/mutation/custom/followups-2026-03-29-to-2026-03-31.edn` to a clean zero-survivor summary at `/hyperopen/target/mutation/nightly/2026-04-01T22-52-03.610727Z/summary.md`: `294/294` killed, `0` survivors, `0` failed targets.
- [x] (2026-04-02 01:45Z) Closed the remaining `5` uncovered lifecycle-polling mutation sites with a targeted module rerun at `/hyperopen/target/mutation/reports/2026-04-02T01-45-25.007184Z-src-hyperopen-funding-application-lifecycle_polling.cljs.edn`: `18/18` killed, `0` uncovered.
- [x] (2026-04-02 02:15Z) Split the mutation follow-up test namespaces and websocket adapter health tests, regenerated `/hyperopen/test/test_runner_generated.cljs`, and retired the temporary namespace-size exceptions for those surfaces.
- [x] (2026-04-02 14:10Z) Revalidated the final tree with `npm run coverage`, `npm run check`, `npm test`, and `npm run test:websocket`, moved this ExecPlan to `completed`, and closed `hyperopen-ymir`.

## Surprises & Discoveries

- Observation: the first rerun failure looked like a mutation-runner problem, but the immediate blocker was a red coverage build caused by expectation drift in mutation-oriented tests.
  Evidence: direct `npm run coverage` reproduction failed before any target mutations ran, and the failures were assertion mismatches rather than compile errors.

- Observation: the custom batch reached `0` survivors before it reached `0` uncovered selected mutants.
  Evidence: `/hyperopen/target/mutation/nightly/2026-04-01T22-52-03.610727Z/summary.md` reported `294/294` killed with `5` uncovered selected mutants, all concentrated in `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`.

- Observation: the remaining uncovered lifecycle-polling seams were narrow helper/control-flow branches rather than broad missing coverage.
  Evidence: the targeted lifecycle rerun at `/hyperopen/target/mutation/reports/2026-04-02T01-45-25.007184Z-src-hyperopen-funding-application-lifecycle_polling.cljs.edn` shows `18` selected sites, `18` covered sites, `0` uncovered sites, and `0` survivors after adding focused helper tests.

- Observation: the mutation follow-up additions pushed a few test namespaces beyond the repo’s default size threshold.
  Evidence: `dev/check-namespace-sizes` initially required temporary exceptions for `/hyperopen/test/hyperopen/api/endpoints/vaults_test.cljs`, `/hyperopen/test/hyperopen/funding/application/lifecycle_polling_test.cljs`, `/hyperopen/test/hyperopen/funding/domain/policy_test.cljs`, and later `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_test.cljs`.

## Decision Log

- Decision: treat the custom follow-up batch at `/hyperopen/target/mutation/custom/followups-2026-03-29-to-2026-03-31.edn` as the acceptance target for this closure pass.
  Rationale: the checked-in nightly set was already green; the actionable mutation debt was concentrated in the custom follow-up set.
  Date/Author: 2026-04-01 / Codex

- Decision: fix the shared coverage prerequisite before trusting any survivor rerun.
  Rationale: mutation results are not trustworthy when the shared coverage build is already red.
  Date/Author: 2026-04-01 / Codex

- Decision: prefer test-only changes, escalating to production edits only when a mutant exposed dead or obsolete behavior.
  Rationale: the user asked for a mutation pass, and most weak seams were test-coverage gaps rather than product defects.
  Date/Author: 2026-04-01 / Codex

- Decision: close the remaining lifecycle uncovered sites with a focused module rerun instead of burning another full custom-batch rerun after the batch was already at zero survivors.
  Rationale: the only remaining uncovered sites were isolated to lifecycle-polling helper branches, and the targeted module report was the shortest path to explicit evidence.
  Date/Author: 2026-04-01 / Codex

- Decision: split the mutation follow-up test namespaces and websocket adapter health coverage into focused files rather than leave temporary size exceptions in place.
  Rationale: the repo norm is a `500`-line namespace threshold, and the cleanup was straightforward once the survivor work was complete.
  Date/Author: 2026-04-02 / Codex

## Outcomes & Retrospective

This pass completed successfully. The custom follow-up mutation batch reached a verified zero-survivor, zero-failed-target state, and the remaining uncovered lifecycle-polling mutation sites were then closed with targeted helper coverage and a clean `18/18` module rerun. The required repo gates passed on the final tree, and the final split tree also rebuilt coverage cleanly at `Statements 91.94%`, `Branches 69.8%`, `Functions 85.97%`, and `Lines 91.94%`.

The result reduced overall complexity slightly rather than increasing it. Mutation-specific test coverage became more direct, the mutation runner hardening and small production cleanups removed dead or obsolete seams, and the later namespace split work removed the temporary size exceptions that the mutation follow-up had introduced. The final closure commits were:

- `cf0a10a2` `Harden mutation runner and add survivor follow-ups`
- `9b59e517` `Stabilize mutation tooling and close survivor gaps`
- `e72b8dc4` `Close custom mutation survivor gaps`
- `c9aca5f4` `Cover lifecycle polling uncovered mutation sites`
- `3bf49c56` `Split mutation follow-up test namespaces`
- `a5c75ec3` `Split websocket adapter health tests`

## Context and Orientation

The custom target batch is defined in `/hyperopen/target/mutation/custom/followups-2026-03-29-to-2026-03-31.edn`. It covers:

- `/hyperopen/src/hyperopen/api/trading.cljs`
- `/hyperopen/src/hyperopen/funding/domain/policy.cljs`
- `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`
- `/hyperopen/src/hyperopen/api/endpoints/leaderboard.cljs`
- `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs`
- `/hyperopen/src/hyperopen/portfolio/actions.cljs`

The key mutation-focused test surfaces that were strengthened or later split during this closure were:

- `/hyperopen/test/hyperopen/funding/domain/policy_test.cljs`
- `/hyperopen/test/hyperopen/funding/domain/policy_preview_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/lifecycle_polling_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/lifecycle_polling/internal_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/lifecycle_polling/test_support.cljs`
- `/hyperopen/test/hyperopen/api/endpoints/leaderboard_test.cljs`
- `/hyperopen/test/hyperopen/api/endpoints/vaults_test.cljs`
- `/hyperopen/test/hyperopen/api/endpoints/vaults_helpers_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/internal_seams_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_health_test.cljs`

## Validation and Acceptance

Acceptance for this pass was met with the following evidence:

- `npm run coverage` succeeded after the initial blocker fix and again on the final split tree.
- `bb tools/mutate_nightly.clj --config target/mutation/custom/followups-2026-03-29-to-2026-03-31.edn` completed successfully and produced a zero-survivor, zero-failed-target summary at `/hyperopen/target/mutation/nightly/2026-04-01T22-52-03.610727Z/summary.md`.
- The remaining lifecycle-polling uncovered sites were closed by `/hyperopen/target/mutation/reports/2026-04-02T01-45-25.007184Z-src-hyperopen-funding-application-lifecycle_polling.cljs.edn`.
- The required repo gates passed on the final tree:
  - `npm run check`
  - `npm test`
  - `npm run test:websocket`

## Artifacts and Notes

Initial custom-batch baseline:

    target/mutation/nightly/2026-04-01T13-23-51.559201Z/summary.md
      Executed mutants: 282
      Killed mutants: 203
      Surviving mutants: 79
      Uncovered selected mutants: 24
      Failed targets: 0

Verified zero-survivor custom-batch result:

    target/mutation/nightly/2026-04-01T22-52-03.610727Z/summary.md
      Executed mutants: 294
      Killed mutants: 294
      Surviving mutants: 0
      Uncovered selected mutants: 5
      Failed targets: 0

Verified lifecycle uncovered-site closure:

    target/mutation/reports/2026-04-02T01-45-25.007184Z-src-hyperopen-funding-application-lifecycle_polling.cljs.edn
      Selected mutation sites: 18
      Covered mutation sites: 18
      Uncovered mutation sites: 0
      Surviving mutants: 0

Final coverage rebuild on the split tree:

    npm run coverage
      Statements 91.94%
      Branches 69.8%
      Functions 85.97%
      Lines 91.94%

## Interfaces and Dependencies

The runtime behavior that this pass protected lives primarily in:

- `/hyperopen/src/hyperopen/funding/domain/policy.cljs`
- `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`
- `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs`
- `/hyperopen/src/hyperopen/api/endpoints/leaderboard.cljs`
- `/hyperopen/src/hyperopen/api/trading.cljs`

The mutation tooling used during the pass depends on:

- `/hyperopen/tools/mutate.clj`
- `/hyperopen/tools/mutate_nightly.clj`
- `/hyperopen/target/mutation/custom/followups-2026-03-29-to-2026-03-31.edn`
- `coverage/lcov.info` generated by `npm run coverage`

Plan update note (2026-04-01 15:59Z): created the initial active ExecPlan after claiming `hyperopen-ymir`, confirming the `79`-survivor custom batch, reproducing the coverage blocker, and narrowing the work to coverage repair plus survivor closure.
Plan update note (2026-04-01 16:03Z): updated the plan after restoring a green `npm run coverage`, which unblocked survivor closure.
Plan update note (2026-04-02 14:10Z): completed the plan after recording the zero-survivor custom summary, the lifecycle uncovered-site closure, the namespace cleanup, the final coverage and gate results, and the closure of `hyperopen-ymir`.
