# Refactor App Shell View Tests Into Bounded Contexts

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/Users/barry/.codex/worktrees/0c2d/hyperopen/.agents/PLANS.md`, `/Users/barry/.codex/worktrees/0c2d/hyperopen/docs/PLANS.md`, and `/Users/barry/.codex/worktrees/0c2d/hyperopen/docs/WORK_TRACKING.md`. The tracked work item for this plan is `hyperopen-38i5`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

The current app-shell test file, `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs`, mixes several different domains: header shell spacing, footer shell spacing, app-level route layout, and trade-view layout and memoization behavior. That makes the file hard to navigate and easy to extend in the wrong place.

After this refactor, the view tests will be grouped by bounded context, the duplicated Hiccup helpers will live in a shared test-support namespace, and the state fixtures will be small builders that describe only the behavior under test. If a small pure seam in `trade_view.cljs` is still needed after the split, it will be added only if it materially removes brittle private-var access or repetitive re-render setup.

This is an internal maintainability change. A reviewer should be able to see it working by running the test suite and observing that the old omnibus namespace is gone, the new focused namespaces are registered in the generated test runner, and the same behavior coverage still passes.

## Progress

- [x] (2026-03-23 12:44Z) Claimed `hyperopen-38i5`, audited the current omnibus test file, and confirmed the generated test runner still hardcodes `hyperopen.views.app-shell-spacing-test`.
- [x] (2026-03-23 12:50Z) Added `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/test_support/hiccup.cljs` and switched the existing view helper namespaces that previously duplicated tree-walker logic to use it.
- [x] (2026-03-23 12:53Z) Moved the header assertions into `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/header_view_test.cljs`, the footer assertions into `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/footer_view_test.cljs`, the app shell assertions into `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/app_view_test.cljs`, and the trade assertions into the focused `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/trade_view/*.cljs` namespaces.
- [x] (2026-03-23 12:54Z) Replaced the old monolithic omnibus fixture with focused builders in `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/trade/test_support.cljs` and file-local app-view state setup.
- [x] (2026-03-23 12:55Z) Regenerated `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/test_runner_generated.cljs` after the split so it now registers the new namespaces and no longer references `hyperopen.views.app-shell-spacing-test`.
- [x] (2026-03-23 12:55Z) Deleted `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs` after verifying every assertion had a new home.
- [x] (2026-03-23 12:57Z) Ran `npm test`, `npm run test:websocket`, and `npm run check` successfully on the integrated tree.

## Surprises & Discoveries

- Observation: `test/test_runner_generated.cljs` explicitly includes `hyperopen.views.app-shell-spacing-test`, so splitting the file requires a runner update instead of relying on automatic discovery.
  Evidence: the current generated runner lists the namespace in both its `require` block and its test suite vector.

- Observation: the omnibus test file duplicates helper logic already present in other view test support files.
  Evidence: `app_shell_spacing_test.cljs` defines `class-values`, `find-first-node`, `find-nodes`, `collect-strings`, and `with-viewport-width`, while similar helpers already exist in `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/active_asset/test_support.cljs`, `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/account_info/test_support/hiccup.cljs`, and `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/trade/order_form/test_support.cljs`.

- Observation: `header_view_test.cljs` and `footer_view_test.cljs` already exist, so not every shell assertion needs a brand-new home.
  Evidence: those namespaces already cover header and footer behavior and can absorb the shell-specific assertions that belong to them.

- Observation: the trade-view split did not require a new production seam after all.
  Evidence: the migrated trade-view tests pass against the current `data-parity-id` and `data-role` contracts in `trade_view.cljs`, and the integrated `npm test` run passed without editing `/Users/barry/.codex/worktrees/0c2d/hyperopen/src/hyperopen/views/trade_view.cljs`.

- Observation: the generated runner stabilized only after the retired omnibus file was physically deleted.
  Evidence: `node tools/generate-test-runner.mjs` continued to register `hyperopen.views.app-shell-spacing-test` while the file still existed, even if `test/test_runner_generated.cljs` had been edited manually.

## Decision Log

- Decision: keep source changes optional and only introduce a new `trade_view.cljs` seam if the migrated tests still require private-var access or repeated render-cache scaffolding.
  Rationale: the primary goal is test structure, not widening the production diff. A pure seam is justified only if it materially simplifies the tests and preserves behavior exactly.
  Date/Author: 2026-03-23 / Codex

- Decision: centralize generic Hiccup traversal helpers in one shared test-support namespace instead of re-copying them into each new test file.
  Rationale: the helper vocabulary is infrastructure for view tests, not domain logic, so it should be consistent and reusable.
  Date/Author: 2026-03-23 / Codex

- Decision: align the new test files with the owning bounded context instead of keeping an umbrella `app_shell_spacing` bucket.
  Rationale: the refactor is meant to improve agent comprehension and make each file answer one question.
  Date/Author: 2026-03-23 / Codex

- Decision: keep `trade_view.cljs` unchanged and rely on the existing public parity/data-role contracts instead of introducing a new layout seam.
  Rationale: the migrated tests remained readable and deterministic without widening the production diff, so adding a seam would have increased interface surface without solving a remaining problem.
  Date/Author: 2026-03-23 / Codex

## Outcomes & Retrospective

This refactor completed without any production-code changes. The app-shell omnibus namespace is gone, the test runner now registers the split `app-view` and `trade-view` namespaces, and the shared Hiccup helper layer replaced the copied tree-walker utilities in the affected view test support files.

The required repo gates are green on the integrated tree:

- `npm test`
- `npm run test:websocket`
- `npm run check`

The optional `trade_view.cljs` seam was not needed. Overall complexity went down because the test suite is now organized by bounded context instead of a 1,253-line mixed-domain file, and the common selector vocabulary lives in one support namespace. The main residual risk is structural: `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/test_support/hiccup.cljs` is now a shared dependency for several view test packages, so future semantic changes there will have broader blast radius than the previous duplicated helpers.

## Context and Orientation

The current starting point is `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs`, which currently contains header assertions, trade layout assertions, app-level route assertions, and footer assertions in one namespace.

The likely destination files are:

- `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/test_support/hiccup.cljs` for shared Hiccup tree helpers.
- `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/header_view_test.cljs` for header shell contracts.
- `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/footer_view_test.cljs` for footer shell contracts.
- `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/app_view_test.cljs` for app-level route and shell-reserve behavior.
- `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/trade_view/layout_test.cljs`, `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/trade_view/mobile_surface_test.cljs`, `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/trade_view/render_cache_test.cljs`, and `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/trade_view/loading_shell_test.cljs` for the trade-view coverage.
- `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/test_runner_generated.cljs` for the explicit namespace registration.
- `/Users/barry/.codex/worktrees/0c2d/hyperopen/src/hyperopen/views/trade_view.cljs` only if the optional pure seam is required after the split.

The key production modules that frame the tests are `/Users/barry/.codex/worktrees/0c2d/hyperopen/src/hyperopen/views/app_view.cljs`, `/Users/barry/.codex/worktrees/0c2d/hyperopen/src/hyperopen/views/header_view.cljs`, `/Users/barry/.codex/worktrees/0c2d/hyperopen/src/hyperopen/views/footer_view.cljs`, and `/Users/barry/.codex/worktrees/0c2d/hyperopen/src/hyperopen/views/trade_view.cljs`.

In this plan, “Hiccup” means the Clojure vector representation of rendered HTML. A “bounded context” means one domain-owning slice of the UI, such as header, footer, app routing, or trade layout, with its own tests and helpers.

## Plan of Work

First, create a shared Hiccup helper namespace under `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/test_support/hiccup.cljs`. It should hold the generic tree traversal and class helpers that multiple view tests need, so the new files can focus on behavior instead of re-implementing walkers.

Next, extract the repeated test fixtures into small builders that describe the domain under test. The app-shell and route tests should use a minimal app state builder, and the trade-view tests should use a trade-state builder with explicit overrides for active asset, mobile surface, and viewport width.

Then, move the header and footer assertions into the already-existing focused test namespaces, and create the new app-view and trade-view test namespaces listed above for the remaining shell and layout behavior. The old omnibus namespace should shrink to zero unique assertions during this step.

After the assertions have moved, update `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/test_runner_generated.cljs` so it no longer imports `hyperopen.views.app-shell-spacing-test` and instead lists the new namespaces. Only after that file is stable should the old omnibus test file be deleted.

Finally, decide whether the trade-view tests still need a new source seam. If they do, add one pure helper in `/Users/barry/.codex/worktrees/0c2d/hyperopen/src/hyperopen/views/trade_view.cljs` that returns layout decisions from input state and viewport width, and keep the view rendering itself behaviorally identical. If the split tests can be written cleanly against existing public behavior and `data-parity-id` selectors, leave source unchanged.

## Concrete Steps

1. Work from `/Users/barry/.codex/worktrees/0c2d/hyperopen`.
2. Update shared helpers and fixtures first, then move assertions into the new files, then update `test/test_runner_generated.cljs`.
3. Remove `/Users/barry/.codex/worktrees/0c2d/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs` only after the new namespaces pass locally.
4. If and only if the trade-view refactor still requires a pure seam, make the smallest possible source edit in `/Users/barry/.codex/worktrees/0c2d/hyperopen/src/hyperopen/views/trade_view.cljs`.
5. Re-run the repo gates in this order: `npm test`, `npm run test:websocket`, and `npm run check`.

Expected command shape:

    cd /Users/barry/.codex/worktrees/0c2d/hyperopen
    npm test
    npm run test:websocket
    npm run check

## Validation and Acceptance

The refactor is accepted when the following are all true: the omnibus file is gone, the new focused namespaces are present in the generated test runner, and the full repo gates pass.

If a source seam was introduced, it must be pure, side-effect free, and covered by the moved tests so that the rendered output is unchanged for the same input state and viewport width.

This work does not require browser QA if the implementation stays in test-only code. If a production seam is added and changes render behavior or layout decisions, run the smallest relevant browser or Playwright check before the full gates.

## Idempotence and Recovery

This refactor should be safe to redo in smaller slices. The shared helper namespace can be created first, then individual test files can be moved one at a time while the generated runner temporarily references both the old and new namespaces.

If the optional seam turns out not to be necessary, remove it before finishing and keep the validation focused on the moved tests. If a test move causes a temporary failure, restore the old namespace registration in `test/test_runner_generated.cljs` and finish the split in smaller increments.

## Artifacts and Notes

Final validation evidence:

    npm test
    Ran 2584 tests containing 13774 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    Ran 405 tests containing 2308 assertions.
    0 failures, 0 errors.

    npm run check
    [:test] Build completed. (961 files, 4 compiled, 0 warnings, 4.47s)

## Interfaces and Dependencies

Shared helper namespace:

- `hyperopen.test-support.hiccup/class-values`
- `hyperopen.test-support.hiccup/node-class-set`
- `hyperopen.test-support.hiccup/node-children`
- `hyperopen.test-support.hiccup/collect-strings`
- `hyperopen.test-support.hiccup/find-first-node`
- `hyperopen.test-support.hiccup/find-by-data-role`
- `hyperopen.test-support.hiccup/with-viewport-width`

State builders:

- `hyperopen.views.trade.test-support/base-state`
- `hyperopen.views.trade.test-support/active-asset-state`
- `hyperopen.views.trade.test-support/with-mobile-surface`

Optional source seam, only if needed:

- `hyperopen.views.trade-view/trade-layout-model`
- Signature: `(trade-layout-model state)` or `(trade-layout-model state viewport-width-px)` returning a pure map of layout decisions that `trade-view` consumes.

Implementation subagents:

- `worker` should own the file moves, helper extraction, runner update, and any source seam if required.
- `acceptance_test_writer` can propose the minimum behavior-preserving acceptance coverage for the split.
- `edge_case_test_writer` can propose one or two invariants that protect the new shared helpers and any optional seam from regressions.
- `reviewer` should verify the split is domain-aligned, the runner is updated, and no behavior leak or duplicate helper remains.

Revision note: updated this ExecPlan on 2026-03-23 after implementation finished so the Progress, Decision Log, Outcomes, and evidence reflect the actual delivered file split, the no-seam decision, and the green validation gates.
