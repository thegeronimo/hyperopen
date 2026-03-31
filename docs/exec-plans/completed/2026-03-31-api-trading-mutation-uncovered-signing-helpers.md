# Investigate And Close Remaining API Trading Mutation Gaps

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` were kept up to date during execution.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Linked completed work: `hyperopen-kya0` ("Close current api/trading mutation coverage residuals").

## Purpose / Big Picture

This work closed the last live mutation-coverage blind spot in `/hyperopen/src/hyperopen/api/trading.cljs`. Before the change, the module still scanned as `48/53` covered with five uncovered sites in the private signing-helper cluster, even though the surrounding behavioral tests already existed. After the change, the helper shape is simpler, the scan is `53/53` covered with `0` uncovered, and the stale mixed-scope follow-up from `hyperopen-e4v9` is no longer the active story for this file.

The important user-visible outcome is confidence rather than new UI behavior. Order submit, cancel, and agent-signed exchange flows behave the same as before, but the test and mutation evidence around those money-moving seams is now accurate and current.

## Progress

- [x] (2026-03-31 16:05 EDT) Reproduced the old baseline locally with `npm install`, `npm run coverage`, targeted reruns, and a fresh scan showing `/hyperopen/src/hyperopen/api/trading.cljs` at `48/53` covered with uncovered lines `366`, `534`, `535`, `541`, and `555`.
- [x] (2026-03-31 16:16 EDT) Closed stale mixed-scope issue `hyperopen-e4v9`, reconciled the narrowed follow-up into `hyperopen-kya0`, and created the original active ExecPlan.
- [x] (2026-03-31 12:24 EDT) Confirmed via `coverage/lcov.info` that the real blind spot was not end-user behavior coverage but a source-map dead zone: the helper bodies around `post-signed-action!` and `sign-and-post-agent-action!` recorded zero hits even though the focused tests were exercising them.
- [x] (2026-03-31 12:28 EDT) Simplified the helper shape in `/hyperopen/src/hyperopen/api/trading.cljs` by replacing variadic keyword-option destructuring with explicit map-arity helpers, centralizing signed-payload assertion and retry/session helpers, and preserving the public trading API.
- [x] (2026-03-31 12:29 EDT) Updated focused tests in `/hyperopen/test/hyperopen/api/trading/internal_seams_test.cljs` and `/hyperopen/test/hyperopen/api/trading/sign_and_submit_test.cljs` to call the private helpers with explicit option maps and to assert the preserved no-validation and retry-budget behavior.
- [x] (2026-03-31 12:31 EDT) Regenerated coverage and confirmed `bb tools/mutate.clj --scan --module src/hyperopen/api/trading.cljs` now reports `53/53` covered with `0` uncovered.
- [x] (2026-03-31 12:36 EDT) Passed `npm test`, `npm run test:websocket`, and `npm run check`.
- [x] (2026-03-31 12:37 EDT) Moved the unrelated finished active plan for `hyperopen-f9y7` into `completed` so `lint:docs` and `npm run check` reflect the actual repo state.
- [x] (2026-03-31 12:37 EDT) Finalized this plan in `completed` and prepared `hyperopen-kya0` for closure.

## Surprises & Discoveries

- Observation: the old follow-up issue combined resolved work with live work.
  Evidence: `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` now reruns as `4/4` killed on the formerly cited parser lines, and commit `5f2d469b` is titled `Fix transfer policy mutation survivor`.

- Observation: the old `api/trading` line-128 survivor and the default-option lines 457 and 458 were already gone before implementation started.
  Evidence: `bb tools/mutate.clj --module src/hyperopen/api/trading.cljs --suite test --lines 128,366,457,458,461,468,473,478,490` reported `L128`, `L457`, and `L458` as `KILLED`.

- Observation: the live problem was a source-map dead zone inside private variadic helper forms, not a missing end-user behavior test.
  Evidence: the focused test namespaces already exercised the helper semantics, but `coverage/lcov.info` recorded zero hits across the old helper-body line range for `post-signed-action!` and `sign-and-post-agent-action!`.

- Observation: changing the helper shape to fixed option maps removed the blind spot without changing public behavior.
  Evidence: after the refactor, `bb tools/mutate.clj --scan --module src/hyperopen/api/trading.cljs` reported `Covered mutation sites: 53` and `Uncovered mutation sites: 0`, while `npm test` and `npm run test:websocket` still passed.

- Observation: live mutation execution on this module is still fragile because the runner can restore stale backups into the working tree.
  Evidence: both `--mutate-all` and `--since-last-run` runs emitted `Restored stale backup for src/hyperopen/api/trading.cljs`, and interrupted runs reintroduced bogus mutations such as `parse-text-body` flipping `if` to `if-not`.

## Decision Log

- Decision: close `hyperopen-e4v9` and keep only a narrowed `api/trading` follow-up.
  Rationale: the original issue title and body were no longer true once the March 31, 2026 reruns showed `transfer_policy` was closed and the old `api/trading` survivor was already killed.
  Date/Author: 2026-03-31 / Codex

- Decision: treat the remaining gap as a source-map or helper-shape problem first, not as evidence that user-facing trading behavior lacked tests.
  Rationale: the focused trading tests already covered successful submit, retry, missing-session rejection, and invalidation/preservation behavior; the scan problem was localized to variadic private helper forms.
  Date/Author: 2026-03-31 / Codex

- Decision: preserve the public trading API and refactor only the private helper seam.
  Rationale: the public functions `submit-order!`, `cancel-order!`, and `submit-vault-transfer!` did not need to change. The useful simplification was internal: explicit option maps, a dedicated signed-payload assertion helper, and named retry/session rejection helpers.
  Date/Author: 2026-03-31 / Codex

- Decision: accept scan-level mutation closure plus repo gates as the final evidence for this ticket.
  Rationale: the full scan now shows `53/53` covered and `0` uncovered, which closes the residual tracked by `hyperopen-kya0`. The live execution runner still has stale-backup instability on this surface, so repeated `mutate-all` attempts were lower-signal than the green scan plus green repository gates.
  Date/Author: 2026-03-31 / Codex

## Outcomes & Retrospective

The implementation closed the live mutation residual in `/hyperopen/src/hyperopen/api/trading.cljs` without changing user-facing trading behavior. The helper refactor replaced the coverage-hostile variadic option destructuring with explicit option maps and small named helpers, the focused tests were updated to match that private seam shape, and the fresh mutation scan now reports full coverage for the module.

This reduced complexity slightly. The file stayed within its existing namespace-size exception, the public trading API did not grow, and the resulting helper code is easier for both humans and the mutation tool to reason about. The only remaining caveat is tool reliability: `tools/mutate.clj` can still restore stale backups during live execution, so future mutation work on this file should treat scan coverage and interrupted-run cleanup carefully.

## Context and Orientation

The production file is `/hyperopen/src/hyperopen/api/trading.cljs`. This namespace owns the exchange mutation path for order submission, cancel submission, and agent-signed helper flows. The work in this ticket stayed inside two private seams:

- `post-signed-action!`, which builds the signed exchange payload and optionally runs schema-boundary validation before the simulated or real POST.
- `sign-and-post-agent-action!`, which resolves the agent session, normalizes options, rejects missing sessions, signs the action, posts it, and retries once on nonce failure.

The focused tests for these seams live in:

- `/hyperopen/test/hyperopen/api/trading/internal_seams_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/sign_and_submit_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/session_invalidation_test.cljs`

The mutation tool is `/hyperopen/tools/mutate.clj`. It depends on `coverage/lcov.info`, which is produced by `npm run coverage`.

## Plan of Work

The executed implementation followed three steps. First, confirm that the old uncovered helper lines were already behaviorally exercised but not receiving source-mapped coverage. Second, simplify the private helper seam so the option handling and retry/session logic live in ordinary fixed-arity helper code instead of the old variadic keyword-destructuring shape. Third, re-run coverage, the full `api/trading` scan, and the required repository gates, then document the remaining mutation-runner instability instead of distorting production code further.

The production changes in `/hyperopen/src/hyperopen/api/trading.cljs` were:

- add `maybe-assert-signed-exchange-payload!` so schema-boundary validation is an explicit helper rather than an uncovered inline `when`
- convert `post-signed-action!` from variadic keyword args to a 3-arity/4-arity shape using an explicit options map
- move defaulting entirely into `normalize-agent-action-options`
- add `missing-agent-session-rejection` and `next-retry-callback` to make the session guard and retry decrement ordinary helper code
- convert `sign-and-post-agent-action!` from variadic keyword args to a 3-arity/4-arity shape using an explicit raw options map

The test changes were:

- update the private helper tests to pass explicit option maps instead of variadic keyword args
- add an assertion that validation is not invoked when disabled in `internal_seams_test`
- add a focused retry-budget test in `sign_and_submit_test` so the extracted retry callback still has direct regression coverage

## Concrete Steps

All commands were run from `/Users/barry/.codex/worktrees/fc6a/hyperopen`.

Baseline investigation:

    npm install
    npm run coverage
    bb tools/mutate.clj --module src/hyperopen/api/trading.cljs --suite test --lines 128,366,457,458,461,468,473,478,490
    bb tools/mutate.clj --scan --module src/hyperopen/api/trading.cljs

Key baseline evidence:

    api/trading scan: 48/53 covered, 5 uncovered
    uncovered lines: 366, 534, 535, 541, 555

Implementation validation:

    npm test
    npm run coverage
    bb tools/mutate.clj --scan --module src/hyperopen/api/trading.cljs
    npm run test:websocket
    npm run check

Final evidence:

    api/trading scan: 53/53 covered, 0 uncovered
    npm test: passed
    npm run test:websocket: passed
    npm run check: passed

## Validation and Acceptance

Acceptance for this ticket was met.

Mutation acceptance:

- `/hyperopen/src/hyperopen/api/trading.cljs` now scans as `53/53` covered with `0` uncovered
- the old residual lines are no longer uncovered because the helper shape was simplified and the focused tests now map cleanly to the production source

Repository acceptance:

- `npm test` passed
- `npm run test:websocket` passed
- `npm run check` passed

Historical cleanup acceptance:

- the stale mixed-scope issue `hyperopen-e4v9` is no longer the active tracker for this surface
- the unrelated finished active ExecPlan for `hyperopen-f9y7` was moved to `completed`, which restored the docs gate needed by `npm run check`

## Idempotence and Recovery

The source changes are safe to rerun and the repository gates are repeatable. `npm run coverage` can be rerun any time to refresh `coverage/lcov.info`, and the scan command can be rerun safely:

    bb tools/mutate.clj --scan --module src/hyperopen/api/trading.cljs

The one recovery caveat discovered during this work is the mutation runner’s stale-backup behavior. If a live `mutate-all` or `since-last-run` execution on this module is interrupted or fails its baseline, inspect `/hyperopen/src/hyperopen/api/trading.cljs` immediately before trusting the worktree. During this ticket, interrupted runs reintroduced bogus mutations such as `parse-text-body` flipping `if` to `if-not`.

## Artifacts and Notes

Most important before/after evidence:

    Before
      bb tools/mutate.clj --scan --module src/hyperopen/api/trading.cljs
      Covered mutation sites: 48
      Uncovered mutation sites: 5
      Uncovered sites: L366, L534, L535, L541, L555

    After
      bb tools/mutate.clj --scan --module src/hyperopen/api/trading.cljs
      Covered mutation sites: 53
      Uncovered mutation sites: 0

Key repository gates:

    npm test
      Ran 2923 tests containing 15662 assertions.
      0 failures, 0 errors.

    npm run test:websocket
      Ran 430 tests containing 2441 assertions.
      0 failures, 0 errors.

    npm run check
      passed

Mutation-runner caveat recorded during execution:

    Restored stale backup for src/hyperopen/api/trading.cljs.
    Baseline failed for suite test.

Those messages came from live mutation execution attempts after the scan was already green. The intended source was restored immediately, and the final acceptance evidence uses the green scan plus repository gates.

## Interfaces and Dependencies

The public trading API remained unchanged:

- `/hyperopen/src/hyperopen/api/trading.cljs`
  `submit-order!`
- `/hyperopen/src/hyperopen/api/trading.cljs`
  `cancel-order!`
- `/hyperopen/src/hyperopen/api/trading.cljs`
  `submit-vault-transfer!`

The private helper seam is now simpler but still equivalent in meaning:

- `/hyperopen/src/hyperopen/api/trading.cljs`
  `post-signed-action!`
- `/hyperopen/src/hyperopen/api/trading.cljs`
  `sign-and-post-agent-action!`
- `/hyperopen/src/hyperopen/api/trading.cljs`
  `maybe-assert-signed-exchange-payload!`
- `/hyperopen/src/hyperopen/api/trading.cljs`
  `missing-agent-session-rejection`
- `/hyperopen/src/hyperopen/api/trading.cljs`
  `next-retry-callback`

The focused test surfaces that now pin the refactor are:

- `/hyperopen/test/hyperopen/api/trading/internal_seams_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/sign_and_submit_test.cljs`

Revision note (2026-03-31 / Codex): this completed plan supersedes the original active version after implementation closed `hyperopen-kya0`, eliminated the helper coverage blind spot, documented the stale-backup mutation-runner caveat, and passed the required repository gates.
