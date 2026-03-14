---
owner: platform
status: completed
last_reviewed: 2026-03-14
review_cycle_days: 90
source_of_truth: false
---

# Reduce CRAP In Account Endpoint Normalizers

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, a contributor can open `/hyperopen/src/hyperopen/api/endpoints/account.cljs`, follow the account payload normalization rules without stepping through large branch trees, and verify the module no longer exceeds the repository CRAP threshold. The user-visible behavior stays the same: the public `request-*` functions send the same request bodies, return the same normalized shapes, and continue handling alternate Hyperliquid payload shapes.

The observable workflow from `/hyperopen` is:

    npm run check
    npm test
    npm run test:websocket
    npm run coverage
    bb tools/crap_report.clj --module src/hyperopen/api/endpoints/account.cljs --format json --top-functions 100

The first three commands are the repository gates. The coverage and CRAP commands prove the module still behaves correctly under test and that `bd` issue `hyperopen-x3fg` is resolved by structural simplification rather than by moving the hotspot elsewhere.

## Progress

- [x] (2026-03-13 20:29Z) Created and claimed `bd` issue `hyperopen-x3fg` for this CRAP remediation.
- [x] (2026-03-13 20:30Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, the target namespace, and the existing endpoint tests.
- [x] (2026-03-13 20:31Z) Generated fresh coverage and confirmed the current module hotspots: `normalize-delegator-history-delta`, `normalize-portfolio-summary-key`, `validator-stats-window`, and `extra-agents-seq`.
- [x] (2026-03-13 20:32Z) Created this active ExecPlan.
- [x] (2026-03-14 00:33Z) Refactored the hotspot helpers in `/hyperopen/src/hyperopen/api/endpoints/account.cljs` into smaller data-driven or single-purpose helpers while preserving every public request and normalization contract.
- [x] (2026-03-14 00:34Z) Expanded `/hyperopen/test/hyperopen/api/endpoints/account_test.cljs` through public seams to cover alternate extra-agent payloads, validator stats shapes, and staking delegator normalization flows.
- [x] (2026-03-14 00:39Z) Ran `npm test`, `npm run test:websocket`, `npm run check`, `npm run coverage`, and the focused CRAP report; confirmed zero remaining hotspots, closed `hyperopen-x3fg`, and moved this file to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: The worst hotspot is not the portfolio helper despite its very high structural complexity; the real max CRAP score comes from the poorly covered delegator-history delta normalizer.
  Evidence: `bb tools/crap_report.clj --module src/hyperopen/api/endpoints/account.cljs --format json --top-functions 100` reported `normalize-delegator-history-delta` at `CRAP 112.41987172011662` and `normalize-portfolio-summary-key` at `CRAP 79.0`.
- Observation: The current test suite already covers `normalize-portfolio-summary-key` heavily through `normalize-portfolio-summary`, so reducing its CRAP score requires branch reduction, not more coverage.
  Evidence: The same CRAP report shows `normalize-portfolio-summary-key` with `complexity 79` and `coverage 1.0`.
- Observation: The worktree initially lacked `node_modules`, so local dependency bootstrap was required before coverage generation.
  Evidence: The first `npm run coverage` attempt failed with `sh: shadow-cljs: command not found`; `npm ci` fixed the toolchain and the next run completed successfully.
- Observation: The final max CRAP score moved to an older low-coverage helper that was already below threshold, so the requested remediation truly removed the hotspot cluster instead of merely reshuffling it.
  Evidence: The final report for `/hyperopen/src/hyperopen/api/endpoints/account.cljs` shows `crappy-functions = 0`, `project-crapload = 0.0`, and `non-funding-ledger-updates-seq` as the new module max at `CRAP 22.476375000000004`.

## Decision Log

- Decision: Keep the refactor in `/hyperopen/src/hyperopen/api/endpoints/account.cljs` instead of introducing a sibling helper namespace.
  Rationale: The issue is function-level CRAP in one endpoint boundary, not a namespace-ownership problem. Keeping the work local reduces churn and matches the requested scope.
  Date/Author: 2026-03-13 / Codex
- Decision: Drive the portfolio-key rewrite from alias data plus one `perp` prefix rule instead of keeping separate unscoped and `perp*` case tables.
  Rationale: The current behavior is deterministic and repetitive. A single alias map removes the 79-branch table while preserving unknown-key fallback to `(keyword token)`.
  Date/Author: 2026-03-13 / Codex
- Decision: Add coverage only through public request seams and normalizing public functions, not by reaching into private vars from tests.
  Rationale: The repository tests endpoint modules by contract. Public-seam coverage keeps the refactor safe without coupling tests to private implementation names.
  Date/Author: 2026-03-13 / Codex
- Decision: Stop once the module report showed `crappy-functions = 0` and `project-crapload = 0.0` instead of continuing to split other medium-complexity helpers such as `portfolio-summary-pairs`.
  Rationale: The issue goal was structural CRAP removal in this module, not maximal decomposition. Once the report dropped below threshold everywhere, extra extraction would only add churn.
  Date/Author: 2026-03-14 / Codex

## Outcomes & Retrospective

Implementation completed. `/hyperopen/src/hyperopen/api/endpoints/account.cljs` now resolves portfolio summary aliases from one base alias map plus a `perp` scope helper, scans extra-agent payload candidates in a fixed order, resolves validator stats through split name and payload helpers, and normalizes delegator-history deltas through explicit subtype selectors and subtype-specific helpers. The public `request-*` seams, request bodies, dedupe keys, and normalized response shapes remained stable.

This reduced overall complexity. Before the refactor, the module had four functions above the CRAP threshold with `project-crapload 146.20544314868803` and `max-crap 112.41987172011662`. After the refactor and widened endpoint tests, `bb tools/crap_report.clj --module src/hyperopen/api/endpoints/account.cljs --format json --top-functions 100` reports `crappy-functions = 0`, `project-crapload = 0.0`, and module `max-crap = 22.476375000000004`. The former hotspots now sit at `normalize-delegator-history-delta = 5.0`, `normalize-portfolio-summary-key = 5.0`, `validator-stats-window = 5.0`, and `extra-agents-seq = 4.0`.

## Context and Orientation

`/hyperopen/src/hyperopen/api/endpoints/account.cljs` owns request-body construction and response normalization for account-related `info` calls. A “normalizer” in this repository is a pure helper that takes raw API payload data and returns the stable CLJS map shape used by the rest of the app. The public functions in this file are the `request-*` entry points such as `request-portfolio!`, `request-extra-agents!`, and `request-staking-delegator-history!`.

The focused CRAP report for this module currently reports four hotspots over the default threshold of `30`:

- `normalize-delegator-history-delta` at line 263 with `CRAP 112.42`
- `normalize-portfolio-summary-key` at line 611 with `CRAP 79.0`
- `validator-stats-window` at line 146 with `CRAP 40.66`
- `extra-agents-seq` at line 54 with `CRAP 34.125`

The adjacent tests live in `/hyperopen/test/hyperopen/api/endpoints/account_test.cljs`. That file already covers most request wrappers and some normalization paths, but it currently under-covers alternate staking and extra-agent payload shapes. The required end state is zero functions above threshold in this module without changing any public request or normalized response contract.

## Plan of Work

First, simplify the portfolio summary key normalizer. In `/hyperopen/src/hyperopen/api/endpoints/account.cljs`, replace the giant `case` form in `normalize-portfolio-summary-key` with a token cleanup step, a `perp` prefix detector, a single alias map for the base range, and a helper that reapplies the `perp` scope to canonical base keys. Keep blank inputs returning `nil` and unknown tokens returning `(keyword token)` exactly as they do today.

Second, split the other branch-heavy helpers by responsibility. `extra-agents-seq` should scan a fixed ordered list of candidate locations and return the first sequential collection. `validator-stats-window` should separate entry-name matching from entry-payload extraction so the top-level function only chooses between map and sequential payload forms. `normalize-delegator-history-delta` should separate subtype selection from subtype-specific normalization so each branch is a small dedicated helper.

Third, extend `/hyperopen/test/hyperopen/api/endpoints/account_test.cljs` through public seams. Add request-level tests that exercise top-level and nested extra-agent payload variants, the remaining validator-stats shapes, delegator summary and delegation response normalization, reward sorting, and the full delegator-history delta family including unknown fallbacks. Keep all tests written against public functions such as `request-extra-agents!`, `request-staking-validator-summaries!`, and `request-staking-delegator-history!`.

Finally, run the required validation commands from `/hyperopen`, regenerate coverage, rerun the focused CRAP report for the module, and record the final before/after evidence here. When the report shows zero remaining hotspots, close `hyperopen-x3fg` and move this plan to `/hyperopen/docs/exec-plans/completed/`.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/api/endpoints/account.cljs` to extract the smaller helpers and replace the branch-heavy implementations.
2. Edit `/hyperopen/test/hyperopen/api/endpoints/account_test.cljs` to widen contract-level coverage for the hotspot normalization paths.
3. Run:

       npm run check
       npm test
       npm run test:websocket
       npm run coverage
       bb tools/crap_report.clj --module src/hyperopen/api/endpoints/account.cljs --format json --top-functions 100

4. Update this ExecPlan with the validation evidence, close `hyperopen-x3fg`, and move the file to `/hyperopen/docs/exec-plans/completed/`.

## Validation and Acceptance

The work is complete when all of the following are true:

- `npm run check` passes from `/hyperopen`.
- `npm test` passes from `/hyperopen`.
- `npm run test:websocket` passes from `/hyperopen`.
- `npm run coverage` regenerates `coverage/lcov.info` successfully.
- `bb tools/crap_report.clj --module src/hyperopen/api/endpoints/account.cljs --format json --top-functions 100` reports `crappy-functions = 0`.
- No replacement hotspot in this module exceeds the CRAP threshold of `30`.
- The public request-body shapes, dedupe keys, and normalized response shapes remain unchanged for callers.

## Idempotence and Recovery

These changes are source and test edits only, so rerunning the refactor steps is safe. If a rewrite breaks behavior, rerun `/hyperopen/test/hyperopen/api/endpoints/account_test.cljs` through `npm test` first because it isolates this boundary faster than the full repository gates. If the CRAP tool reports missing coverage again, rerun `npm run coverage` before rechecking the module because the report reads generated artifacts but does not mutate application state.

## Artifacts and Notes

Baseline focused CRAP evidence from `/hyperopen` before implementation:

- `normalize-delegator-history-delta` => `complexity 11`, `coverage 0.05714285714285714`, `CRAP 112.41987172011662`
- `normalize-portfolio-summary-key` => `complexity 79`, `coverage 1.0`, `CRAP 79.0`
- `validator-stats-window` => `complexity 14`, `coverage 0.4857142857142857`, `CRAP 40.66057142857142`
- `extra-agents-seq` => `complexity 13`, `coverage 0.5`, `CRAP 34.125`

The module summary before implementation is:

- `crappy-functions = 4`
- `project-crapload = 146.20544314868803`
- `max-crap = 112.41987172011662`

Post-implementation evidence from `/hyperopen`:

- `npm test` passed with `Ran 2375 tests containing 12478 assertions. 0 failures, 0 errors.`
- `npm run test:websocket` passed with `Ran 385 tests containing 2187 assertions. 0 failures, 0 errors.`
- `npm run check` passed.
- `npm run coverage` produced `Statements 90.59%`, `Branches 68.01%`, `Functions 85.3%`, and `Lines 90.59%`.
- `bb tools/crap_report.clj --module src/hyperopen/api/endpoints/account.cljs --format json --top-functions 100` reported `crappy-functions = 0`, `project-crapload = 0.0`, `max-crap = 22.476375000000004`, and `avg-coverage = 0.953876592157842`.

## Interfaces and Dependencies

At the end of this work, `/hyperopen/src/hyperopen/api/endpoints/account.cljs` must continue to expose the same public functions and compatible behavior for at least:

    request-user-funding-history!
    request-spot-clearinghouse-state!
    request-extra-agents!
    request-user-webdata2!
    request-staking-validator-summaries!
    request-staking-delegator-summary!
    request-staking-delegations!
    request-staking-delegator-rewards!
    request-staking-delegator-history!
    normalize-user-abstraction-mode
    request-user-abstraction!
    request-clearinghouse-state!
    normalize-portfolio-summary
    request-portfolio!
    request-user-fees!
    request-user-non-funding-ledger-updates!

The implementation may add private constants and helper functions, but it must not add side effects, change output keys, or alter the request-policy behavior already validated by the existing tests.

Revision note: Completed the plan after refactoring the hotspot helpers, widening endpoint-level staking and extra-agent coverage, running all required validation commands, confirming the module CRAP report dropped to zero hotspots, and closing `bd` issue `hyperopen-x3fg`.
