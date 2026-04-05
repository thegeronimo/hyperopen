# Run Overnight Mutation Sweep And Fix Survivors

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-3j6u`, which was created and claimed on 2026-04-05 during the overnight mutation pass requested by the user.

## Purpose / Big Picture

The goal of this pass is to spend the available unattended time on a broad mutation audit instead of a narrow spot check. After this work, the repository should have a fresh overnight mutation summary covering the checked-in nightly hotspots plus the recently repaired follow-up modules, any newly regressed survivors or uncovered mutation sites should be closed or explicitly documented, and the required repository gates should still pass.

The visible proof will be a fresh mutation summary under `/hyperopen/target/mutation/nightly/**`, focused rerun artifacts under `/hyperopen/target/mutation/reports/**` for any repaired modules, and a final green validation run with `npm run check`, `npm test`, and `npm run test:websocket`.

## Progress

- [x] (2026-04-05 02:36Z) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, and `/hyperopen/docs/tools.md` to re-enter the governed workflow for long-running mutation work.
- [x] (2026-04-05 02:36Z) Created and claimed `hyperopen-3j6u` for the overnight mutation sweep and survivor-fix pass.
- [x] (2026-04-05 02:38Z) Confirmed the worktree started clean and identified the checked-in nightly target set at `/hyperopen/tools/mutate/nightly_targets.edn` as only four modules, which is too narrow for the requested overnight pass.
- [x] (2026-04-05 02:40Z) Created `/hyperopen/target/mutation/custom/overnight-2026-04-04.edn` with ten `:mutate-all` targets: the four checked-in nightly modules plus API trading, funding policy, funding lifecycle polling, leaderboard endpoints, vault endpoints, and portfolio actions.
- [x] (2026-04-05 03:32Z) Restored missing `node_modules` with `npm ci`, confirmed standalone `npm run test:websocket` still passed (`432` tests, `2471` assertions), and produced a fresh `coverage/lcov.info` through a safer manual coverage sequence after the default `npm run coverage` path proved flaky in this environment.
- [x] (2026-04-05 09:55 EDT) Completed the first live module rerun at `/hyperopen/target/mutation/reports/2026-04-05T13-55-12.712067Z-src-hyperopen-api_wallets-domain-policy.cljs.edn`: `12/12` killed, `0` survivors, `0` uncovered.
- [x] (2026-04-05 10:00 EDT) Completed the second live module rerun at `/hyperopen/target/mutation/reports/2026-04-05T14-00-50.362777Z-src-hyperopen-domain-trading-validation.cljs.edn`: `13/13` killed, `0` survivors, `0` uncovered.
- [x] (2026-04-05 10:09 EDT) Completed the `vaults/domain/transfer_policy.cljs` live rerun at `/hyperopen/target/mutation/reports/2026-04-05T14-09-03.666551Z-src-hyperopen-vaults-domain-transfer_policy.cljs.edn`: `22/22` killed, `0` survivors, `0` uncovered.
- [x] (2026-04-05 10:20 EDT) Completed the `portfolio/actions.cljs` live rerun at `/hyperopen/target/mutation/reports/2026-04-05T14-20-56.235581Z-src-hyperopen-portfolio-actions.cljs.edn`: `30/30` killed, `0` survivors, `0` uncovered.
- [x] (2026-04-05 10:47 EDT) Completed the historically volatile `api/trading.cljs` live rerun at `/hyperopen/target/mutation/reports/2026-04-05T14-47-16.650504Z-src-hyperopen-api-trading.cljs.edn`: `54/54` killed, `0` survivors, `0` uncovered. One mutant at line `541` timed out during execution but still did not survive the module run.
- [x] (2026-04-05 10:50 EDT) Completed the `api/endpoints/leaderboard.cljs` live rerun at `/hyperopen/target/mutation/reports/2026-04-05T14-50-52.698033Z-src-hyperopen-api-endpoints-leaderboard.cljs.edn`: `5/5` killed, `0` survivors, `0` uncovered.
- [x] (2026-04-05 10:58 EDT) Completed the `funding/application/lifecycle_polling.cljs` live rerun at `/hyperopen/target/mutation/reports/2026-04-05T14-58-52.829644Z-src-hyperopen-funding-application-lifecycle_polling.cljs.edn`: `18/18` killed, `0` survivors, `0` uncovered.
- [x] (2026-04-05 11:31 EDT) Completed a focused benchmark rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T15-31-14.614703Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: line `11` killed cleanly in `88.25s`, which confirmed the module is slow but executable when left uninterrupted.
- [x] (2026-04-05 11:47 EDT) Completed the first bounded `websocket/orderbook_policy.cljs` slice at `/hyperopen/target/mutation/reports/2026-04-05T15-47-42.038973Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: `13/13` killed, `0` survivors, `0` uncovered, and the tree remained clean with no leftover backup artifacts.
- [x] (2026-04-05 11:56 EDT) Completed the first narrowed single-line fallback rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T15-56-34.163367Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: line `97` killed cleanly with `0` survivors, which confirms the worker can keep draining the remaining orderbook lines one at a time when wider slices misbehave.
- [x] (2026-04-05 12:00 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-00-05.357311Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: both mutation sites on line `98` were killed cleanly (`2/2`) with `0` survivors.
- [x] (2026-04-05 12:01 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-01-57.030203Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: line `96` was killed cleanly (`1/1`) with `0` survivors.
- [x] (2026-04-05 12:03 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-03-49.298349Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: line `108` was killed cleanly (`1/1`) with `0` survivors.
- [x] (2026-04-05 12:05 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-05-47.024429Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: line `111` was killed cleanly (`1/1`) with `0` survivors.
- [x] (2026-04-05 12:08 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-08-51.728407Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: line `112` was killed cleanly (`1/1`) with `0` survivors.
- [x] (2026-04-05 12:10 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-10-46.795530Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: line `113` was killed cleanly (`1/1`) with `0` survivors.
- [x] (2026-04-05 12:12 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-12-43.118485Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: line `118` was killed cleanly (`1/1`) with `0` survivors.
- [x] (2026-04-05 12:14 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-14-42.513515Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: line `119` was killed cleanly (`1/1`) with `0` survivors.
- [x] (2026-04-05 12:16 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-16-57.130644Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: both line-`120` mutation sites were killed cleanly (`2/2`) with `0` survivors.
- [x] (2026-04-05 12:18 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-18-51.480395Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: line `121` was killed cleanly (`1/1`) with `0` survivors.
- [x] (2026-04-05 12:21 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-21-49.233117Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: both line-`125` mutation sites were killed cleanly (`2/2`) with `0` survivors.
- [x] (2026-04-05 12:24 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-24-08.958876Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: line `130` was killed cleanly (`1/1`) with `0` survivors.
- [ ] (2026-04-05 12:24 EDT) Attempted the next blocked-module fallback on `api/endpoints/vaults.cljs` with line `19`, but the clean `ws-test` baseline failed before mutation execution and no report artifact was produced.
- [ ] (2026-04-05 12:30 EDT) Retried the `api/endpoints/vaults.cljs` fallback after a fresh manual `ws-test` reset; the standalone reset passed, but the mutation run still failed in the clean `ws-test` baseline before mutation execution and produced no report artifact.
- [x] (2026-04-05 12:37 EDT) Completed another narrowed rerun for `websocket/orderbook_policy.cljs` at `/hyperopen/target/mutation/reports/2026-04-05T16-37-24.718307Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`: line `131` was killed cleanly (`1/1`) with `0` survivors after the same manual `ws-test` reset that failed to unblock the `vaults` mutation baseline.
- [ ] Run the expanded overnight mutation sweep, inspect the summary, and rank any surviving or uncovered modules by urgency. In progress: after the nightly wrapper repeatedly died before summary emission, the live overnight execution moved first to the detached sequential driver `/hyperopen/target/mutation/nightly/overnight_direct_2026_04_04.sh` and then to the safer bounded slice driver `/hyperopen/target/mutation/nightly/run_remaining_slices_2026_04_05.sh` for the three large blocked modules.
- [ ] Fix the resulting survivors or uncovered sites with the smallest defensible changes, preferring test-only closures unless a mutant exposes dead or misleading production behavior.
- [ ] Re-run focused mutation slices for touched modules and then re-run `npm run check`, `npm test`, and `npm run test:websocket`.
- [ ] Move this plan to `/hyperopen/docs/exec-plans/completed/` after acceptance is met and close `hyperopen-3j6u`.

## Surprises & Discoveries

- Observation: the checked-in nightly mutation config is intentionally small and currently covers only four modules.
  Evidence: `/hyperopen/tools/mutate/nightly_targets.edn` lists `api_wallets/domain/policy.cljs`, `domain/trading/validation.cljs`, `vaults/domain/transfer_policy.cljs`, and `websocket/orderbook_policy.cljs`.

- Observation: the most recent larger mutation closure work was not captured in a checked-in config file inside the current worktree state.
  Evidence: the April 1 completed ExecPlan references `/hyperopen/target/mutation/custom/followups-2026-03-29-to-2026-03-31.edn`, but the current worktree did not contain a `target/` tree before this pass began.

- Observation: the default `npm run coverage` path is flaky in this environment even when the underlying `test` and `ws-test` suites are green.
  Evidence: repeated default-script runs produced generated-artifact failures such as `ReferenceError: s is not defined` in `/hyperopen/.shadow-cljs/builds/ws-test/dev/out/cljs-runtime/hyperopen.api_test.js` and `SyntaxError: Unexpected token '}'` in `/hyperopen/.shadow-cljs/builds/ws-test/dev/out/cljs-runtime/hyperopen.schema.contracts.state.js`, while standalone `npm run test:websocket` still passed.

- Observation: rebuilding `ws-test` separately after the main test run avoids the flaky coverage failure and still yields a valid `coverage/lcov.info`.
  Evidence: the manual sequence `rm -rf .coverage coverage .shadow-cljs/builds/test .shadow-cljs/builds/ws-test out/test.js out/ws-test.js`, `npm run test:runner:generate`, `npx shadow-cljs --force-spawn compile test`, `NODE_V8_COVERAGE=.coverage node out/test.js`, fresh `ws-test` rebuild, `NODE_V8_COVERAGE=.coverage node out/ws-test.js`, and `npx c8 report --temp-directory .coverage` completed successfully with `Statements 91.93%`, `Branches 73.58%`, `Functions 85.91%`, and `Lines 91.93%`.

- Observation: the batch wrapper produced only scan-mode reports and repeatedly died before writing a nightly summary, but those scan reports still established full coverage for the intended target set.
  Evidence: `/hyperopen/target/mutation/reports/**` now includes scan reports for all ten planned targets, including `/hyperopen/src/hyperopen/api/trading.cljs` at `54/54` covered, `/hyperopen/src/hyperopen/funding/domain/policy.cljs` at `131/131` covered, `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs` at `18/18` covered, and `/hyperopen/src/hyperopen/portfolio/actions.cljs` at `15/15` covered.

- Observation: direct single-module live mutation execution does run in this environment once launched outside the fragile nightly wrapper.
  Evidence: the detached overnight driver has an active `bb tools/mutate.clj --format json --module src/hyperopen/api_wallets/domain/policy.cljs` process whose child command is currently `node out/test.js` after the clean test compile baseline.

- Observation: the smaller and medium-sized mutation targets in the expanded overnight set are all closing cleanly under live execution so far.
  Evidence: fresh live reports now show `/hyperopen/src/hyperopen/api_wallets/domain/policy.cljs` at `12/12`, `/hyperopen/src/hyperopen/domain/trading/validation.cljs` at `13/13`, `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` at `22/22`, and `/hyperopen/src/hyperopen/portfolio/actions.cljs` at `30/30`, each with `0` survivors and `0` uncovered mutation sites.

- Observation: the old `api/trading` mutation follow-up appears fully closed in live execution, not just at scan level.
  Evidence: `/hyperopen/target/mutation/reports/2026-04-05T14-47-16.650504Z-src-hyperopen-api-trading.cljs.edn` records `54/54` killed, `0` survivors, and `0` uncovered mutation sites for `/hyperopen/src/hyperopen/api/trading.cljs`.

- Observation: `websocket/orderbook_policy.cljs` is not inherently deadlocked; it is just expensive because every covered mutation line currently requires both the main `test` suite and `ws-test`.
  Evidence: the focused line-11 rerun finished cleanly at `/hyperopen/target/mutation/reports/2026-04-05T15-31-14.614703Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`, and a direct coverage partition check showed all `51/51` covered orderbook mutation sites mapped to `[:test :ws-test]`.

- Observation: the bounded-slice fallback is working in practice for the remaining orderbook module.
  Evidence: `/hyperopen/target/mutation/reports/2026-04-05T15-47-42.038973Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` completed the first twelve-line slice plus the dual-site line `78` at `13/13` killed with no survivors and no stale backups left on disk afterward.

- Observation: when a wider follow-up slice misbehaves, the mutation tool can still make forward progress on the same module with narrowed single-line reruns.
  Evidence: `/hyperopen/target/mutation/reports/2026-04-05T15-56-34.163367Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` cleared line `97` at `1/1` killed immediately after the second twelve-line batch proved unstable, and the worktree returned to a clean tracked state afterward.

- Observation: the narrowed fallback is consistently clearing adjacent remaining orderbook lines without leaving tracked source dirty.
  Evidence: `/hyperopen/target/mutation/reports/2026-04-05T16-00-05.357311Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed the two sites on line `98`, `/hyperopen/target/mutation/reports/2026-04-05T16-01-57.030203Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed line `96`, `/hyperopen/target/mutation/reports/2026-04-05T16-03-49.298349Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed line `108`, and `/hyperopen/target/mutation/reports/2026-04-05T16-05-47.024429Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed line `111`; the worktree returned to a clean tracked state after each run.

- Observation: the narrowed fallback remains reliable through the next orderbook block that previously belonged to the unstable second slice.
  Evidence: `/hyperopen/target/mutation/reports/2026-04-05T16-08-51.728407Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed line `112`, `/hyperopen/target/mutation/reports/2026-04-05T16-10-46.795530Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed line `113`, `/hyperopen/target/mutation/reports/2026-04-05T16-12-43.118485Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed line `118`, `/hyperopen/target/mutation/reports/2026-04-05T16-14-42.513515Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed line `119`, `/hyperopen/target/mutation/reports/2026-04-05T16-16-57.130644Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed both sites on line `120`, and `/hyperopen/target/mutation/reports/2026-04-05T16-18-51.480395Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed line `121`, all with `0` survivors.

- Observation: the narrowed orderbook fallback continued to make progress after the manual `ws-test` reset even though the same reset did not unblock `api/endpoints/vaults.cljs`.
  Evidence: `/hyperopen/target/mutation/reports/2026-04-05T16-21-49.233117Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed both sites on line `125`, `/hyperopen/target/mutation/reports/2026-04-05T16-24-08.958876Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed line `130`, and `/hyperopen/target/mutation/reports/2026-04-05T16-37-24.718307Z-src-hyperopen-websocket-orderbook_policy.cljs.edn` killed line `131`, all with `0` survivors.

- Observation: the practical risk on the remaining large modules is interruption recovery, not confirmed survivors.
  Evidence: interrupted reruns restored stale backups into tracked source for `/hyperopen/src/hyperopen/websocket/orderbook_policy.cljs`, `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs`, and `/hyperopen/src/hyperopen/funding/domain/policy.cljs`; each tracked diff was restored, and the replacement slice driver now aborts on any leftover dirty file or backup artifact.

- Observation: `api/endpoints/vaults.cljs` is currently blocked by a clean baseline failure in `ws-test`, not by a confirmed mutation survivor.
  Evidence: `bb tools/mutate.clj --format json --module src/hyperopen/api/endpoints/vaults.cljs --lines 19 --timeout-factor 20` exited with `Baseline failed for suite ws-test.` before any mutant was executed, and the worktree remained clean afterward.

- Observation: the manual `ws-test` reset itself is healthy, but that does not unblock the vaults mutation runner.
  Evidence: `rm -rf .shadow-cljs/builds/ws-test out/ws-test.js && npx shadow-cljs --force-spawn compile ws-test && node out/ws-test.js` passed with `432` tests and `2471` assertions, yet the same `bb tools/mutate.clj --format json --module src/hyperopen/api/endpoints/vaults.cljs --lines 19 --timeout-factor 20` retry still failed in the clean `ws-test` baseline.

## Decision Log

- Decision: create a fresh active ExecPlan and `bd` task before running the overnight mutation command.
  Rationale: Hyperopen policy requires complex work to stay tied to a live ExecPlan and `bd` issue while implementation is active.
  Date/Author: 2026-04-05 / Codex

- Decision: broaden the run beyond the stock nightly config instead of treating the four-module sweep as sufficient.
  Rationale: the user explicitly asked for an extensive overnight suite, and recent completed plans show several additional modules with real survivor history that are worth revalidating.
  Date/Author: 2026-04-05 / Codex

- Decision: bypass the stock `npm run coverage` script for this pass and build `coverage/lcov.info` with a manual clean-and-rebuild sequence.
  Rationale: the mutation tooling only requires a trustworthy coverage file, and the manual sequence was the first path that proved both suites cleanly under V8 coverage in this environment.
  Date/Author: 2026-04-05 / Codex

- Decision: switch the overnight execution path from `tools/mutate_nightly.clj` to a detached sequential `tools/mutate.clj --mutate-all` driver.
  Rationale: the nightly wrapper repeatedly stopped after scan-mode artifact generation and never emitted a usable summary, while direct module execution demonstrably reached live baseline test execution.
  Date/Author: 2026-04-05 / Codex

- Decision: prioritize live mutation execution by module size and prior volatility, starting with smaller modules to prove the environment is stable before attempting the historically fragile `api/trading` and larger endpoint namespaces.
  Rationale: the mutation runner can leave stale source edits behind when interrupted, so proving the execution path on safer modules reduces the chance of corrupting the worktree before the more hazardous seams are attempted.
  Date/Author: 2026-04-05 / Codex

- Decision: execute the remaining large modules in bounded `--lines` slices with tree-cleanliness checks between runs.
  Rationale: the line-11 benchmark proved the orderbook path completes when uninterrupted, while earlier aborted full-module reruns showed that the highest operational risk is stale-backup recovery rather than trustworthy survivor output.
  Date/Author: 2026-04-05 / Codex

## Outcomes & Retrospective

This section will be completed once the overnight sweep, survivor closure work, and final validation are finished. The expected outcome is a fresh mutation artifact set with either zero residual survivor debt in scope or an explicit documented account of any equivalent or tool-limited residuals.

## Context and Orientation

Hyperopen has two mutation entry points. The single-module tool is `/hyperopen/tools/mutate.clj`, which can scan or run mutation testing for one module and writes per-module reports under `/hyperopen/target/mutation/reports/**`. The batch runner is `/hyperopen/tools/mutate_nightly.clj`, which rebuilds coverage once, then runs a configured vector of targets serially and writes aggregate summaries under `/hyperopen/target/mutation/nightly/**`.

Mutation testing in this repository depends on `coverage/lcov.info`, which is produced by `npm run coverage`. A mutation “survivor” means the test suite still passed after a source-level change, so the tests did not prove the original behavior mattered. An “uncovered” mutation site means the coverage build never executed that line, so mutation execution cannot say anything useful about it. The accepted closure pattern in recent Hyperopen work is to prefer test-only changes when behavior is valid but weakly asserted, and to simplify production code only when a survivor reveals dead, misleading, or redundant logic.

The checked-in nightly target list is `/hyperopen/tools/mutate/nightly_targets.edn`. Recent completed mutation work that shapes this pass is documented in `/hyperopen/docs/exec-plans/completed/2026-03-31-api-trading-mutation-uncovered-signing-helpers.md` and `/hyperopen/docs/exec-plans/completed/2026-04-01-custom-mutation-followup-batch-closure.md`. Those records identify the recently volatile modules worth rechecking overnight: `/hyperopen/src/hyperopen/api/trading.cljs`, `/hyperopen/src/hyperopen/funding/domain/policy.cljs`, `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`, `/hyperopen/src/hyperopen/api/endpoints/leaderboard.cljs`, `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs`, and `/hyperopen/src/hyperopen/portfolio/actions.cljs`.

## Plan of Work

First, create an expanded nightly config that combines the stable checked-in nightly hotspots with the recent follow-up modules that had real survivor or uncovered history in the last mutation closure pass. Store that config under `/hyperopen/target/mutation/custom/` so it is colocated with this run’s artifacts instead of changing the repository’s small default nightly list.

Second, build `coverage/lcov.info` with the safe manual sequence instead of trusting the stock combined script, then execute the expanded nightly batch in `--skip-coverage` mode so the mutation sweep starts from that known-good coverage artifact. Inspect the markdown and JSON summaries to identify any regressions, survivor clusters, or uncovered modules.

Third, repair the weakest modules one at a time. For each survivor cluster, inspect the reported mutation lines, strengthen focused tests on the existing private seams when the production logic is correct, and only alter production code when a survivor points at dead fallback logic or misleading branches that should be removed. After each repair, rerun the affected module with `bb tools/mutate.clj --module <path> --mutate-all` or the equivalent scan/rerun flow until the targeted report is clean enough to justify moving on.

Fourth, once the batch scope is back to an acceptable state, rerun the smallest necessary overnight or focused mutation commands to capture final evidence, then run the required repository gates and update this plan with exact artifact paths, command history, outcomes, and any remaining caveats such as tool limitations or equivalent mutants.

## Concrete Steps

Run the overnight sweep from `/hyperopen` with an expanded config:

    bb tools/mutate_nightly.clj --skip-coverage --config target/mutation/custom/overnight-2026-04-04.edn

Detached live driver currently used for the unattended run:

    ./target/mutation/nightly/overnight_direct_2026_04_04.sh

Safer remaining-work driver for the three large blocked modules:

    ./target/mutation/nightly/run_remaining_slices_2026_04_05.sh

If a single module needs a focused rerun after a fix, run:

    bb tools/mutate.clj --module src/hyperopen/<module>.cljs

If only scan evidence is needed for a helper-heavy seam after tests change, run:

    bb tools/mutate.clj --scan --module src/hyperopen/<module>.cljs

Final repository validation commands from `/hyperopen`:

    npm run check
    npm test
    npm run test:websocket

Coverage preparation actually used for this pass:

    npm ci
    rm -rf .coverage coverage .shadow-cljs/builds/test .shadow-cljs/builds/ws-test out/test.js out/ws-test.js
    npm run test:runner:generate
    npx shadow-cljs --force-spawn compile test
    NODE_V8_COVERAGE=.coverage node out/test.js
    rm -rf .shadow-cljs/builds/ws-test out/ws-test.js
    npx shadow-cljs --force-spawn compile ws-test
    NODE_V8_COVERAGE=.coverage node out/ws-test.js
    npx c8 report --temp-directory .coverage

## Validation and Acceptance

Acceptance for this pass requires four things. First, the expanded overnight mutation sweep must complete and write a fresh summary under `/hyperopen/target/mutation/nightly/**`. Second, any survivors or uncovered sites in the chosen batch scope must either be closed with targeted evidence or explicitly documented as equivalent or tool-limited residuals with enough context for a future contributor to resume safely. Third, touched modules must have current focused mutation evidence after their repairs. Fourth, the repository gates must pass with `npm run check`, `npm test`, and `npm run test:websocket`.

## Idempotence and Recovery

The nightly runner is designed to be rerunnable. It writes each run to a timestamped directory under `/hyperopen/target/mutation/nightly/**`, so repeated executions do not overwrite prior summaries. Focused module reruns also write timestamped reports under `/hyperopen/target/mutation/reports/**`.

Recent repo history documents one important hazard: interrupted live mutation runs can restore stale source backups into the working tree for some modules, especially `/hyperopen/src/hyperopen/api/trading.cljs`. After any failed or interrupted mutation run, inspect the affected source file immediately before trusting the tree or starting the next rerun.

## Artifacts and Notes

Current setup evidence:

    npm ci
      added 333 packages, and audited 334 packages in 2s

    npm run test:websocket
      Ran 432 tests containing 2471 assertions.
      0 failures, 0 errors.

    npx c8 report --temp-directory .coverage
      Statements 91.93% (122273/132993)
      Branches 73.58% (27587/37489)
      Functions 85.91% (7548/8785)
      Lines 91.93% (122273/132993)

    active batch:
      ./target/mutation/nightly/overnight_direct_2026_04_04.sh

    scan artifacts already captured for the ten-target scope:
      target/mutation/reports/2026-04-05T02-48-26.264886Z-src-hyperopen-portfolio-actions.cljs.edn
      target/mutation/reports/2026-04-05T02-48-22.407253Z-src-hyperopen-api-endpoints-vaults.cljs.edn
      target/mutation/reports/2026-04-05T02-48-18.548757Z-src-hyperopen-api-endpoints-leaderboard.cljs.edn
      target/mutation/reports/2026-04-05T02-48-14.793108Z-src-hyperopen-funding-application-lifecycle_polling.cljs.edn
      target/mutation/reports/2026-04-05T02-48-10.912283Z-src-hyperopen-funding-domain-policy.cljs.edn

    live mutation reports already completed cleanly:
      target/mutation/reports/2026-04-05T13-55-12.712067Z-src-hyperopen-api_wallets-domain-policy.cljs.edn
      target/mutation/reports/2026-04-05T14-00-50.362777Z-src-hyperopen-domain-trading-validation.cljs.edn
      target/mutation/reports/2026-04-05T14-09-03.666551Z-src-hyperopen-vaults-domain-transfer_policy.cljs.edn
      target/mutation/reports/2026-04-05T14-20-56.235581Z-src-hyperopen-portfolio-actions.cljs.edn
      target/mutation/reports/2026-04-05T14-47-16.650504Z-src-hyperopen-api-trading.cljs.edn
      target/mutation/reports/2026-04-05T14-50-52.698033Z-src-hyperopen-api-endpoints-leaderboard.cljs.edn
      target/mutation/reports/2026-04-05T14-58-52.829644Z-src-hyperopen-funding-application-lifecycle_polling.cljs.edn
      target/mutation/reports/2026-04-05T15-31-14.614703Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T15-47-42.038973Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T15-56-34.163367Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-00-05.357311Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-01-57.030203Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-03-49.298349Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-05-47.024429Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-08-51.728407Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-10-46.795530Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-12-43.118485Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-14-42.513515Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-16-57.130644Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-18-51.480395Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-21-49.233117Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-24-08.958876Z-src-hyperopen-websocket-orderbook_policy.cljs.edn
      target/mutation/reports/2026-04-05T16-37-24.718307Z-src-hyperopen-websocket-orderbook_policy.cljs.edn

## Interfaces and Dependencies

The execution entrypoints for this work are `/hyperopen/tools/mutate.clj`, `/hyperopen/tools/mutate_nightly.clj`, and `/hyperopen/tools/mutate/nightly.clj`. Coverage generation depends on the `coverage` script in `/hyperopen/package.json`, which compiles the test and websocket suites, runs them under V8 coverage collection, and emits `coverage/lcov.info`.

The most likely production namespaces to revisit are the recent mutation-history hotspots named above. Their primary test surfaces include:

- `/hyperopen/test/hyperopen/api/trading/**`
- `/hyperopen/test/hyperopen/funding/domain/policy_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/lifecycle_polling/**`
- `/hyperopen/test/hyperopen/api/endpoints/leaderboard_test.cljs`
- `/hyperopen/test/hyperopen/api/endpoints/vaults*.cljs`
- `/hyperopen/test/hyperopen/portfolio/actions_test.cljs`

Plan update note (2026-04-05 02:38Z): created the active plan after claiming `hyperopen-3j6u`, confirming the default nightly config was too narrow for the requested overnight pass, and narrowing the next step to building an expanded custom sweep config before the first full run.
Plan update note (2026-04-05 03:32Z): updated the plan after restoring `node_modules`, documenting the flaky stock coverage path, producing fresh manual coverage metrics, and launching the expanded nightly mutation batch in `--skip-coverage` mode.
Plan update note (2026-04-05 03:49Z): updated the plan after the nightly wrapper kept stopping at scan-mode artifact generation; all ten target modules now have fresh scan coverage evidence, and the unattended overnight work has moved to the detached sequential live-mutation driver under `/hyperopen/target/mutation/nightly/`.
Plan update note (2026-04-05 10:20 EDT): updated the plan after four direct live mutation reruns completed cleanly with zero survivors on the smaller and medium-sized modules, which clears the path to focus the remaining work on the historically volatile larger targets.
Plan update note (2026-04-05 10:47 EDT): updated the plan after `api/trading.cljs` also completed a full live rerun at `54/54` killed, which removes the main survivor-risk namespace cited by the March 31 mutation follow-up plan.
Plan update note (2026-04-05 11:31 EDT): updated the plan after confirming `websocket/orderbook_policy.cljs` can complete a focused live rerun when left uninterrupted, then switched the remaining large-module work to a bounded slice driver that verifies the tree stays clean after each batch.
Plan update note (2026-04-05 11:47 EDT): updated the plan after the first full `orderbook_policy` slice completed cleanly at `13/13` killed, which validates the slice-by-slice execution strategy for the remaining large modules.
Plan update note (2026-04-05 11:56 EDT): updated the plan after narrowed single-line rerun `97` also completed cleanly, which establishes a reliable fallback path for the remaining orderbook lines when a larger batch exits inconsistently.
Plan update note (2026-04-05 12:01 EDT): updated the plan after narrowed reruns for lines `98` and `96` both completed cleanly, which keeps the remaining orderbook queue moving.
Plan update note (2026-04-05 12:05 EDT): updated the plan after narrowed reruns for lines `108` and `111` both completed cleanly, confirming the single-line fallback remains reliable across the next orderbook block.
Plan update note (2026-04-05 12:18 EDT): updated the plan after narrowed reruns for lines `112`, `113`, `118`, `119`, `120`, and `121` all completed cleanly, which closes the previously unstable second orderbook slice without any survivor debt.
Plan update note (2026-04-05 12:24 EDT): updated the plan after narrowed reruns for lines `125` and `130` also completed cleanly, which keeps the orderbook queue moving even while the next blocked-module attempt was being investigated.
Plan update note (2026-04-05 12:24 EDT): updated the plan after the first vaults fallback attempt failed at the clean `ws-test` baseline, so the next step there is blocked on tool stability rather than a verified survivor.
Plan update note (2026-04-05 12:30 EDT): updated the plan after the manual `ws-test` reset passed but the vaults retry still failed at the clean `ws-test` baseline, confirming the blocker is specific to the mutation runner path rather than the reset command itself.
Plan update note (2026-04-05 12:37 EDT): updated the plan after line `131` also completed cleanly, which confirms the manual `ws-test` reset kept `orderbook_policy` runnable even though it did not unblock `api/endpoints/vaults.cljs`.
