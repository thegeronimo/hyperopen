# Repair Stale Balances Gap Contract Tests

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-53th`, and that `bd` issue remains the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

The repository test suite currently stops on two failures in the account-info table contract tests even though the balances desktop layout itself was intentionally changed later in the code history. After this fix, `npm test` should stop failing on those stale expectations, and the suite should once again reflect the current balances desktop spacing contract instead of an older parity snapshot.

The visible proof is straightforward. Running the JS suite from `/hyperopen` should no longer report the two `gap-x-3` assertion failures in `test/hyperopen/views/account_info/table_contract_test.cljs`. If the investigation proves the runtime should still be `gap-x-3`, the implementation must be restored instead. If the investigation proves the runtime intentionally moved on, the tests must be updated to the newer value and the rationale must be recorded here.

## Progress

- [x] (2026-03-21 20:02-0400) Reproduced the suite failure after installing missing local dependencies. `npm test` now runs and fails only in `test/hyperopen/views/account_info/table_contract_test.cljs` on the balances row/header `gap-x-3` assertions.
- [x] (2026-03-21 20:06-0400) Traced the relevant history in `src/hyperopen/views/account_info/tabs/balances.cljs`: the March 18 parity pass introduced `gap-x-3`, `Adjust balances table column spacing` changed it to `gap-x-5`, and `Keep balances table within viewport` narrowed that to `gap-x-4` without updating the tests.
- [x] (2026-03-21 20:18-0400) Claimed `bd` issue `hyperopen-53th` and created this active ExecPlan.
- [x] (2026-03-21 20:24-0400) Updated the two balances-specific contract assertions in `test/hyperopen/views/account_info/table_contract_test.cljs` from `gap-x-3` to `gap-x-4`, matching the current runtime in `src/hyperopen/views/account_info/tabs/balances.cljs`.
- [x] (2026-03-21 20:31-0400) Ran `npm test` successfully. The suite finished with `Ran 2580 tests containing 13755 assertions. 0 failures, 0 errors.`
- [x] (2026-03-21 20:35-0400) Ran `npm run test:websocket` successfully. The suite finished with `Ran 405 tests containing 2308 assertions. 0 failures, 0 errors.`
- [x] (2026-03-21 20:44-0400) Ran `npm run check` successfully. Shadow-cljs printed cache-read warnings during compile, but the gate completed with exit code `0`.
- [x] (2026-03-21 20:46-0400) Collected independent reviewer confirmation that the correct repair is test-only and recorded a governed browser-QA skip because no runtime UI file under `src/hyperopen/views/**` changed.

## Surprises & Discoveries

- Observation: the first reproduction attempt failed before test execution because this worktree had no installed npm dependencies, so `npm test` could not find `shadow-cljs` or `@noble/secp256k1`.
  Evidence: the initial run exited with `sh: shadow-cljs: command not found`, and a direct runtime attempt failed with `Cannot find module '@noble/secp256k1'` until `npm install` populated `node_modules`.

- Observation: once dependencies were installed, the JS suite reported exactly two failures and zero errors.
  Evidence: `npm test` ended with `Ran 2580 tests containing 13755 assertions. 2 failures, 0 errors.` and both failures pointed to `test/hyperopen/views/account_info/table_contract_test.cljs` lines `90` and `103`.

- Observation: the failing expectations are stale relative to the current implementation history, not random drift.
  Evidence: `git show 0fcf7a8d` shows the tests and implementation aligned on `gap-x-3` on March 18, while `git show eb9cccce` and `git show 760cd48f` show the implementation later changed to `gap-x-5` and then `gap-x-4` with no matching test update.

- Observation: `npm run check` emitted multiple shadow-cljs cache-read warnings during compilation but still completed successfully.
  Evidence: the command logged repeated `Failed reading cache ... JsonEOFException` messages and then completed all compile targets with exit code `0`.

- Observation: governed browser QA is not required for this final patch because the fix stayed entirely in a test file.
  Evidence: the independent browser-QA agent reviewed `/hyperopen/docs/FRONTEND.md` and `/hyperopen/docs/agent-guides/browser-qa.md` and concluded that a test-only contract realignment may be recorded as an explicit skip as long as no runtime file under `/hyperopen/src/hyperopen/views/**` changes.

## Decision Log

- Decision: treat this as diagnosis-first bug work even though the likely code change is small.
  Rationale: the suite failures could have meant either a product regression or stale test expectations, and the correct fix depends on proving which side drifted.
  Date/Author: 2026-03-21 / Codex

- Decision: install local npm dependencies before drawing conclusions from the test runner.
  Rationale: the first failure mode was environmental and masked the actual two failing tests the user asked about.
  Date/Author: 2026-03-21 / Codex

- Decision: prefer the latest deliberate balances layout contract unless current evidence shows the later spacing commits were accidental.
  Rationale: the March 21 commits were targeted spacing and viewport-fit changes in the production view module, so the older March 18 test expectation should not win automatically.
  Date/Author: 2026-03-21 / Codex

- Decision: repair the stale tests instead of restoring the runtime `gap-x-3` spacing.
  Rationale: the current tree renders `gap-x-4`, the March 21 commit history shows that spacing was changed deliberately in production code, and the independent reviewer found no current source of truth that still requires `gap-x-3`.
  Date/Author: 2026-03-21 / Codex

- Decision: record browser QA as an explicit governed skip.
  Rationale: the final patch changed only `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs`, so there is no runtime UI change to inspect in the browser. The skip remains valid only because `/hyperopen/src/hyperopen/views/**` was left untouched.
  Date/Author: 2026-03-21 / Codex

## Outcomes & Retrospective

The issue is complete. The repository failures came from stale view-contract assertions, not from a current runtime defect in the balances desktop table. Updating the balances-specific row and header assertions from `gap-x-3` to `gap-x-4` brought the tests back into alignment with the present production view code and removed the only two failing assertions in the JS suite.

This reduced complexity slightly because the final repair removed disagreement between the test contract and the shipped view code without introducing any new runtime branches, feature flags, or layout conditionals. Validation passed through `npm test`, `npm run test:websocket`, and `npm run check`. The only residual note is that exact spacing-class assertions may need normal maintenance if future balances layout tuning intentionally changes the gap again.

## Context and Orientation

The affected runtime code lives in `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`. That module renders the desktop balances table header and row grid classes for the account-info surface shown on `/trade`. The failing tests live in `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs`, which exercises shared account-info table presentation contracts across balances, positions, open orders, trade history, funding history, and order history.

In this repository, a “contract test” is a deterministic view-level assertion that a rendered Hiccup tree carries required layout classes or structural markers. For this issue, the contract under dispute is the horizontal gap class on the balances desktop table header and rows. The March 18 parity pass treated that contract as `gap-x-3`. Two later March 21 balances-layout commits changed the implementation to wider spacing and finally settled on `gap-x-4` in the current tree.

The validation commands required by `/hyperopen/AGENTS.md` when code changes are `npm run check`, `npm test`, and `npm run test:websocket`. Because this issue may end as a test-only repair rather than a runtime UI change, browser QA may be an explicit governed skip instead of a live browser pass, but that decision must still be recorded and justified.

## Plan of Work

First, keep this plan current while the diagnosis is converted into a fix. The root cause is already known: the tests still assert the earlier `gap-x-3` balances contract even though the current implementation renders `gap-x-4`. The next step is to verify that no other code or governed documentation still treats `gap-x-3` as the intended current production contract.

Second, update `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs` so the balances-specific row and header assertions reflect the intended current runtime value. Keep the other account-info tabs on their existing `gap-2` contract. Do not weaken the test into a broad “some gap exists” assertion unless independent review shows the exact class is no longer meaningful.

Third, rerun the required repository gates from `/hyperopen`: `npm run check`, `npm test`, and `npm run test:websocket`. If any other failures appear, capture them here before making further edits.

Fourth, obtain independent quality signals. A read-only reviewer should confirm that aligning the tests is the correct fix rather than masking a regression. Browser QA should either confirm that no runtime UI changed and therefore governed browser review is skipped, or run the required review flow if the implementation path changes.

Finally, update this plan’s living sections with the completed work, move it to `/hyperopen/docs/exec-plans/completed/` if the issue is closed in this turn, and close `hyperopen-53th` with the completed reason.

## Concrete Steps

Work from `/hyperopen`.

1. Keep this file current as the single execution plan for `hyperopen-53th`.

2. Edit the failing contract file:

    `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs`

   Update only the balances-specific `gap-x-*` expectations unless the investigation uncovers another necessary adjustment.

3. Run the required validation commands:

    cd /hyperopen
    npm run check
    npm test
    npm run test:websocket

4. Collect independent review and QA evidence:

    - reviewer: confirm whether the correct repair is test-only or runtime-changing
    - browser QA: explicit skip if no UI runtime changed; otherwise record PASS/FAIL/BLOCKED for all required passes and widths

5. Close the `bd` issue and move the plan to `completed` once the validation and evidence are complete.

## Validation and Acceptance

Acceptance is met when the repository no longer reports the two stale balances-gap failures and all required validation commands succeed:

    cd /hyperopen
    npm run check
    npm test
    npm run test:websocket

The minimum behavioral acceptance for the specific bug is:

- `npm test` no longer fails at `table_contract_test.cljs:90` or `table_contract_test.cljs:103`.
- The balances table contract assertions now match the current intended runtime spacing class.
- Independent review agrees the patch fixes stale expectations instead of hiding a product regression.
- Browser QA is explicitly accounted for as either `PASS`/`FAIL`/`BLOCKED` or a governed skip with rationale.

## Idempotence and Recovery

This fix should be safe to rerun because it is a small tracked-file change plus normal validation commands. If the validation commands surface broader regressions, stop expanding scope blindly and record the new failures in this plan before deciding whether they belong to `hyperopen-53th` or a discovered follow-up `bd` issue.

If later evidence proves the March 21 runtime spacing change was accidental, the recovery path is to revert the balances runtime classes to the intended value and then keep these tests aligned with that restored runtime. The tests should always follow the proven product contract, not vice versa.

## Artifacts and Notes

Key evidence gathered during diagnosis:

- `npm test` final summary after installing dependencies:

    Ran 2580 tests containing 13755 assertions.
    2 failures, 0 errors.

- Failing assertions:

    expected: (contains? row-classes "gap-x-3")
    actual: rendered balances row classes include "gap-x-4"

    expected: (contains? header-classes "gap-x-3")
    actual: rendered balances header classes include "gap-x-4"

- Relevant history:

    git show 0fcf7a8d  # introduces balances gap-x-3 and matching tests
    git show eb9cccce  # changes balances gap-x-3 -> gap-x-5
    git show 760cd48f  # changes balances gap-x-5 -> gap-x-4 to keep viewport fit

- Validation summary after the patch:

    npm test            -> pass
    npm run test:websocket -> pass
    npm run check       -> pass

- Independent quality evidence:

    reviewer: no findings; recommended updating the stale test assertions to gap-x-4
    browser QA: explicit governed skip because the final patch is test-only and leaves /src/hyperopen/views/** unchanged

## Interfaces and Dependencies

No new libraries or runtime interfaces are expected. The touched files should remain limited to:

- `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs`
- this ExecPlan file while active, then its completed location

If the fix unexpectedly requires a runtime change, the affected implementation surface is `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`, and that would convert the work into a true UI-facing code change that requires full governed browser QA.

Revision note: created on 2026-03-21 for `hyperopen-53th` after reproducing two JS suite failures and tracing them to stale balances-gap expectations left behind by later March 21 spacing commits. Updated later the same session to record the test-only repair, the passing validation gates, the reviewer confirmation, the explicit browser-QA skip, and completion of the issue.
