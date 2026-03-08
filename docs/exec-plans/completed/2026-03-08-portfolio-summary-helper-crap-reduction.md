---
owner: platform
status: completed
last_reviewed: 2026-03-08
review_cycle_days: 90
source_of_truth: false
---

# Reduce Branch-Heavy Portfolio Summary Helpers

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, a contributor can open `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`, follow the portfolio summary key-selection rules without stepping through giant `case` forms, and verify the behavior with direct regression tests. The user-visible behavior stays the same: portfolio summary cards and chart selection still resolve the same summary buckets and the same all-time-derived fallback windows, but the helper module becomes easier to change safely and should score lower on the repo-local CRAP report.

The observable workflow from `/hyperopen` is:

    npm run check
    npm test
    npm run test:websocket
    npm run coverage
    bb tools/crap_report.clj --module src/hyperopen/views/portfolio/vm/summary.cljs

The first three commands are the repository’s required gates. The coverage and CRAP commands prove that the helper module still behaves correctly under test and that the refactor reduced branch-heavy hotspot risk in the specific module named by `bd` issue `hyperopen-45n`.

## Progress

- [x] (2026-03-08 01:05Z) Claimed `bd` issue `hyperopen-45n`, reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, and `/hyperopen/docs/agent-guides/trading-ui-policy.md`.
- [x] (2026-03-08 01:05Z) Audited `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`, and the existing summary helper tests to capture the current selection and fallback contract.
- [x] (2026-03-08 01:05Z) Created this active ExecPlan.
- [x] (2026-03-08 01:08Z) Refactored the summary key normalization and candidate-order helpers into smaller pure functions backed by shared data tables instead of repeated `case` branches.
- [x] (2026-03-08 01:08Z) Added direct regression tests that cover alias normalization, scoped fallback ordering, unknown-range defaults, and summary-entry fallback behavior.
- [x] (2026-03-08 01:12Z) Installed npm dependencies with `npm ci`, ran the required quality gates, generated fresh coverage, and re-ran the module CRAP report to capture the post-refactor outcome.

## Surprises & Discoveries

- Observation: The module hotspot is not only `summary-key-candidates`; `canonical-summary-key` duplicates the same domain in a giant alias `case`, which means branch reduction has to target both normalization and candidate selection to move the module-level CRAP score.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs` currently contains large `case` forms in both `canonical-summary-key` and `summary-key-candidates`.
- Observation: There is another `canonical-summary-key` function in `/hyperopen/src/hyperopen/views/portfolio/vm/utils.cljs`, but it normalizes summary scope (`:perps`, `:spot`, `:vaults`, `:all`), not summary time-range keys.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm/utils.cljs` maps values such as `"perps"` and `"spot"` to scope keywords, while `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs` maps `"3M"` and `"perp2Y"` to summary-range keywords.
- Observation: The current worktree does not yet have `coverage/lcov.info`, so CRAP verification must happen after generating fresh coverage in this worktree.
  Evidence: `bb tools/crap_report.clj --module src/hyperopen/views/portfolio/vm/summary.cljs --format json` failed with `Missing coverage/lcov.info. Run npm run coverage first.`
- Observation: `some->` threads the intermediate value into the first argument position, which briefly caused `aliased-summary-key` to return `:all` and `:perps` instead of scoped summary-range keys.
  Evidence: The first `npm test` run failed with `canonical-summary-key "3M"` returning `:all` and `canonical-summary-key "perp2Y"` returning `:perps`; switching that helper to `some->>` fixed the argument order and cleared the downstream summary-entry regressions.

## Decision Log

- Decision: Keep the refactor scoped to `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs` and its direct tests instead of merging it with `/hyperopen/src/hyperopen/views/portfolio/vm/utils.cljs`.
  Rationale: The similarly named helper in `utils.cljs` serves a different bounded concept. Reusing the name would not reduce risk here and would widen the change surface beyond the `bd` issue.
  Date/Author: 2026-03-08 / Codex
- Decision: Replace repeated branch tables with data-driven helpers built from one canonical ordered vector of base summary ranges and one alias map.
  Rationale: The fallback ordering in `summary-key-candidates` follows a stable rule: prefer the requested time bucket, then larger windows, then smaller windows in descending proximity. Encoding that rule once is simpler and easier to test than maintaining duplicated branch tables for spot-plus-perps and perps-only scopes.
  Date/Author: 2026-03-08 / Codex
- Decision: Stop the refactor once the module-level CRAP report fell below threshold for every function instead of continuing to split `derived-summary-entry` or `max-drawdown-ratio`.
  Rationale: The issue goal was to remove the CRAP hotspot while preserving behavior. After the data-driven rewrite plus direct tests, the module report showed `crappy-functions = 0` and `project-crapload = 0.0`, so further extraction would add churn without improving the requested outcome.
  Date/Author: 2026-03-08 / Codex

## Outcomes & Retrospective

Implementation completed. `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs` now derives summary keys from one ordered base-range vector and one alias map, with small private helpers for token cleanup, scope application, base-key fallback, and candidate ordering. The public namespace surface stayed intact, and the widened helper tests plus the existing integration tests kept the portfolio VM contract stable.

This reduced overall complexity. Before the change, the issue recorded `summary-key-candidates` at `CRAP 170.32` with module `crapload 196.73` and three functions above threshold. After the change and fresh coverage, `bb tools/crap_report.clj --module src/hyperopen/views/portfolio/vm/summary.cljs --format json` reported `crappy-functions = 0`, module `crapload = 0.0`, and `summary-key-candidates` at `complexity 1`, `coverage 1.0`, `CRAP 1.0`. `canonical-summary-key` dropped to `complexity 3`, `coverage 1.0`, `CRAP 3.0`.

## Context and Orientation

`/hyperopen/src/hyperopen/views/portfolio/vm.cljs` builds the portfolio page view-model. It delegates summary bucket selection to `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`. That summary helper namespace owns four important behaviors.

`canonical-summary-key` takes incoming summary bucket identifiers from state, such as `"3M"` or `"perp2Y"`, and converts them into canonical keywords like `:three-month` or `:perp-two-year`. `selected-summary-key` converts the currently selected scope and time-range into the exact key the view-model expects, for example `:perp-six-month`. `summary-key-candidates` builds the fallback lookup order when the exact summary slice is missing. `selected-summary-entry` uses those helpers, plus `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`, to derive a summary window from all-time history when an explicit summary slice is absent.

The current hotspot comes from repeated branching in these helpers. The branch tables are logically deterministic and pure, so they are good candidates for extraction into smaller helpers and data constants without touching public APIs.

The existing direct tests live in `/hyperopen/test/hyperopen/views/portfolio/vm/summary_helpers_test.cljs` and the higher-level portfolio VM integration checks live in `/hyperopen/test/hyperopen/views/portfolio/vm/summary_test.cljs`. The implementation must preserve both public function names and the behavior consumed by `portfolio-vm`.

## Plan of Work

First, rewrite the summary-key derivation logic around one ordered vector of base time ranges in `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs`. Add private helpers that answer three questions separately: what is the canonical base time-range for an input token, how does a base time-range become a perps-scoped key, and how do we generate the fallback order from the base range ordering rule. This should collapse the duplicated `case` forms in `canonical-summary-key`, `selected-summary-key`, and `summary-key-candidates` into smaller helpers with one responsibility each.

Second, keep the public behavior intact by preserving the same fallback defaults. Unknown or missing time-ranges still need to default to `:month` for non-perps scopes and `:perp-month` for perps scopes. Unknown normalized tokens still need to round-trip to a keyword of the cleaned token instead of returning `nil` unless the input is blank.

Third, extend `/hyperopen/test/hyperopen/views/portfolio/vm/summary_helpers_test.cljs` so the direct tests cover the data-driven rules, not just one happy path. The new tests should assert multiple aliases, both scoped and unscoped fallback orders, unknown-range defaults, and the candidate-based fallback behavior in `selected-summary-entry` when derived slices are unavailable. Keep `/hyperopen/test/hyperopen/views/portfolio/vm/summary_test.cljs` as the end-to-end proof that `portfolio-vm` still exposes the same selected key and chart points.

Finally, run the repository quality gates from `/hyperopen`, generate fresh coverage, re-run the module CRAP report, and then update this document so a future contributor can see both the implementation result and the verification evidence in one place.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs` to introduce the shared summary-range order, alias map, and smaller private helpers, then rewrite the public helpers to call them.
2. Edit `/hyperopen/test/hyperopen/views/portfolio/vm/summary_helpers_test.cljs` to add regression coverage for normalization, defaulting, fallback ordering, and summary-entry lookup order.
3. Keep `/hyperopen/test/hyperopen/views/portfolio/vm/summary_test.cljs` passing without changing the public VM contract.
4. Run:

       npm run check
       npm test
       npm run test:websocket

5. Generate fresh coverage and measure the module hotspot again:

       npm run coverage
       bb tools/crap_report.clj --module src/hyperopen/views/portfolio/vm/summary.cljs

This was the executed validation path. The post-change CRAP report named the same module path and showed no remaining functions above the default threshold because the repeated branch tables were replaced with smaller helpers and the direct tests now exercise the selection logic much more directly.

## Validation and Acceptance

The work is complete when all of the following are true:

- `npm run check` passes from `/hyperopen`.
- `npm test` passes from `/hyperopen`.
- `npm run test:websocket` passes from `/hyperopen`.
- The direct helper tests prove that alias normalization still handles unscoped and `perp`-prefixed time-range tokens, unknown time-range selections still fall back to the same default keys, and candidate ordering still prefers larger windows before smaller ones.
- The higher-level tests in `/hyperopen/test/hyperopen/views/portfolio/vm/summary_test.cljs` still prove that `portfolio-vm` exposes the expected selected key and derived chart data.
- After `npm run coverage`, `bb tools/crap_report.clj --module src/hyperopen/views/portfolio/vm/summary.cljs` completes successfully and shows lower hotspot risk than the pre-refactor issue context, or clearly documents if the score reduction came mainly from coverage instead of branch reduction.

## Idempotence and Recovery

The refactor is source-only and safe to re-run. If one helper rewrite breaks behavior, rerun the direct summary helper tests first because they isolate the contract faster than the full test suite. If coverage generation fails or takes too long, the required repository gates still determine correctness; the CRAP re-measurement can be retried later once `coverage/lcov.info` exists because the tool reads generated artifacts and does not mutate application state. In this run, the only recovery step needed was `npm ci` because the worktree initially lacked `node_modules`.

## Artifacts and Notes

Before implementation, the relevant hotspot context from `bd` issue `hyperopen-45n` is:

- `summary-key-candidates` in `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs` scored `CRAP 170.32`, `complexity 17`, `coverage 0.19`.
- The module `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs` had `crapload 196.73` with three functions above the default threshold.

This plan intentionally keeps the public namespace surface intact so downstream callers do not need updates.

Post-implementation evidence from `/hyperopen`:

- `npm run check` passed.
- `npm test` passed with `Ran 2036 tests containing 10454 assertions. 0 failures, 0 errors.`
- `npm run test:websocket` passed with `Ran 342 tests containing 1869 assertions. 0 failures, 0 errors.`
- `npm run coverage` produced `Statements 88.79%`, `Branches 66.43%`, `Functions 83.14%`, and `Lines 88.79%`.
- `bb tools/crap_report.clj --module src/hyperopen/views/portfolio/vm/summary.cljs --format json` reported `functions-scanned = 15`, `crappy-functions = 0`, module `crapload = 0.0`, `max-crap = 12.0`, and `avg-coverage = 0.9830687830687831`.

## Interfaces and Dependencies

At the end of the refactor, `/hyperopen/src/hyperopen/views/portfolio/vm/summary.cljs` must still expose these public functions with compatible behavior:

    canonical-summary-key
    normalize-summary-by-key
    selected-summary-key
    summary-key-candidates
    derived-summary-entry
    selected-summary-entry
    pnl-delta
    max-drawdown-ratio

The new private helpers should remain pure and file-local. They may depend on `clojure.string` and `/hyperopen/src/hyperopen/views/portfolio/vm/history.cljs`, but they must not introduce new runtime side effects or change the data shape consumed by `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`.

Revision note: Completed the plan after implementing the summary-helper refactor, widening direct regression tests, installing missing npm dependencies for this worktree, and recording the passing gates plus post-change CRAP evidence for `bd` issue `hyperopen-45n`.
