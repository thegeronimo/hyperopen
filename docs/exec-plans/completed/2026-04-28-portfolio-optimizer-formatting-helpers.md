---
owner: frontend
status: completed
created: 2026-04-28
source_of_truth: false
tracked_issue: hyperopen-aawh
---

# Portfolio Optimizer Formatting Helpers

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while work proceeds. This document follows `.agents/PLANS.md`.

Tracked issue: `hyperopen-aawh` ("Extract portfolio optimizer formatting helpers").

## Purpose / Big Picture

The portfolio optimizer views currently define the same small display helpers in several namespaces. Examples include finite-number checks, percentage formatting, USDC formatting, decimal formatting, and keyword label formatting. This creates noisy duplication and makes future display changes harder to assign to a small, safe owner. After this change, optimizer view-only formatting lives in `src/hyperopen/views/portfolio/optimize/format.cljs`, and the existing panels call that shared namespace without changing rendered labels, numbers, or action behavior.

This is an internal refactor. A user should see the same portfolio optimizer screens before and after the change. The result is demonstrable by a new focused formatter test that fails before the helper namespace exists and by existing optimizer view tests continuing to pass after call sites are migrated.

## Progress

- [x] (2026-04-28 16:10Z) Created and claimed `bd` issue `hyperopen-aawh`.
- [x] (2026-04-28 16:11Z) Audited duplicated optimizer view helper definitions under `src/hyperopen/views/portfolio/optimize`.
- [x] (2026-04-28 16:13Z) Added `test/hyperopen/views/portfolio/optimize/format_test.cljs` and verified the RED failure: the CLJS test build cannot find `hyperopen.views.portfolio.optimize.format`.
- [x] (2026-04-28 16:18Z) Created `src/hyperopen/views/portfolio/optimize/format.cljs` with view-only formatting helpers.
- [x] (2026-04-28 16:20Z) Migrated optimizer view call sites from local duplicate helpers to the shared formatter namespace.
- [x] (2026-04-28 16:24Z) Ran required repo gates: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-04-28 16:25Z) Moved this ExecPlan to `docs/exec-plans/completed/` and closed `hyperopen-aawh`.

## Surprises & Discoveries

- Observation: The repeated helpers are not limited to `scenario_detail_view.cljs` and `tracking_panel.cljs`; similar local copies also exist in `results_panel.cljs`, `frontier_chart.cljs`, `target_exposure_table.cljs`, `rebalance_tab.cljs`, `inputs_tab.cljs`, `execution_modal.cljs`, `black_litterman_views_panel.cljs`, and `infeasible_panel.cljs`.
  Evidence: `rg -n "defn- (finite-number\\?|format-pct|format-usdc|keyword-label|format-decimal|format-pct-delta|display-label|format-time)|\\b(format-pct|format-usdc|keyword-label|format-decimal|format-pct-delta|display-label|format-time)\\b" src/hyperopen/views/portfolio/optimize test/hyperopen/views/portfolio/optimize`.
- Observation: The new formatter test is connected to the generated CLJS test runner.
  Evidence: `npm test` exited 1 during the RED phase with `The required namespace "hyperopen.views.portfolio.optimize.format" is not available, it was required by "hyperopen/views/portfolio/optimize/format_test.cljs".`
- Observation: Local npm dependencies were not installed when the first GREEN run reached Node execution, so the generated bundle could not resolve `lucide/dist/esm/icons/external-link.js`.
  Evidence: `test -d node_modules` returned false and `npm test` failed after compile with `Error: Cannot find module 'lucide/dist/esm/icons/external-link.js'`. Running `npm install` installed the package set from `package-lock.json`.
- Observation: After dependency installation and one missed call-site fix in `frontier_chart.cljs`, the CLJS test suite passed.
  Evidence: `npm test` reported `Ran 3598 tests containing 19732 assertions. 0 failures, 0 errors.`

## Decision Log

- Decision: Extract only view-level formatting helpers into `hyperopen.views.portfolio.optimize.format`.
  Rationale: The duplicated helpers format already-shaped UI values. Domain and application namespaces use numeric parsing for solver, history, tracking, and request-building semantics; mixing those with display helpers would blur ownership and risk behavioral changes outside the UI.
  Date/Author: 2026-04-28 / Codex

- Decision: Preserve current formatting behavior, including the `"N/A"` fallback, two-decimal percentage labels, three-decimal decimal labels, and unspecialized `keyword-label` output for keywords.
  Rationale: The user asked for extraction, not a product copy or formatting redesign. Existing view tests should not need assertion changes for visible behavior.
  Date/Author: 2026-04-28 / Codex

## Context and Orientation

The relevant UI files are ClojureScript Hiccup-style view namespaces under `src/hyperopen/views/portfolio/optimize`. Hiccup is represented as vectors such as `[:section {:data-role "..."} ...]`; tests inspect those vectors directly rather than rendering a browser. The optimizer view surface is assembled by `src/hyperopen/views/portfolio/optimize/scenario_detail_view.cljs`, which routes the recommendation tab to `results_panel.cljs`, the rebalance tab to `rebalance_tab.cljs`, the tracking tab to `tracking_panel.cljs`, and the inputs tab to `inputs_tab.cljs`.

The helpers being extracted are display-only. `finite-number?` means "a JavaScript finite numeric value suitable for display"; it rejects `nil`, non-numbers, `NaN`, and infinities. `format-pct` accepts a ratio such as `0.1234` and displays `12.34%`. `format-decimal` displays a plain decimal with up to three fractional digits. `format-usdc` displays a dollar-prefixed USDC amount with two fractional digits by default. `keyword-label` converts keywords such as `:partially-blocked` to `"partially-blocked"` and returns `"N/A"` for missing values. `display-label` is an optimizer-specific friendly label mapper for known objective, return-model, and risk-model keywords.

The new namespace must not become a public domain API. It belongs under `views/portfolio/optimize` because its behavior is presentation-specific and uses browser `toLocaleString`.

## Plan of Work

First, add `test/hyperopen/views/portfolio/optimize/format_test.cljs`. It should require `hyperopen.views.portfolio.optimize.format` and directly assert `finite-number?`, `format-pct`, `format-pct-delta`, `format-decimal`, `format-effective-n`, `format-usdc`, `keyword-label`, `display-label`, and `format-time`. Running the CLJS test build before the production namespace exists should fail because the namespace cannot be found.

Second, create `src/hyperopen/views/portfolio/optimize/format.cljs`. Define the helper functions with the same behavior currently duplicated in the view files. Keep the implementation small and deterministic. For `format-usdc`, support an optional map with `:maximum-fraction-digits`; default to `2` because most existing call sites use two decimal places. For `format-effective-n`, preserve the current `results_panel.cljs` behavior by clamping to the universe size only when `universe-size` is positive.

Third, migrate each duplicate local helper definition to a `:require [hyperopen.views.portfolio.optimize.format :as opt-format]` and update calls to use the `opt-format/` prefix. Remove only helper definitions that are replaced by the shared namespace. Preserve all existing component function names, `data-role` hooks, action vectors, class names, and conditional rendering.

The call-site migration is expected to touch:

- `src/hyperopen/views/portfolio/optimize/scenario_detail_view.cljs`
- `src/hyperopen/views/portfolio/optimize/tracking_panel.cljs`
- `src/hyperopen/views/portfolio/optimize/frontier_chart.cljs`
- `src/hyperopen/views/portfolio/optimize/black_litterman_views_panel.cljs`
- `src/hyperopen/views/portfolio/optimize/infeasible_panel.cljs`
- `src/hyperopen/views/portfolio/optimize/execution_modal.cljs`
- `src/hyperopen/views/portfolio/optimize/results_panel.cljs`
- `src/hyperopen/views/portfolio/optimize/target_exposure_table.cljs`
- `src/hyperopen/views/portfolio/optimize/rebalance_tab.cljs`
- `src/hyperopen/views/portfolio/optimize/inputs_tab.cljs`

## Concrete Steps

Run all commands from `/Users/barry/.codex/worktrees/53c0/hyperopen`.

1. Add the formatter test and run:

       npm run test

   Expected before implementation: the test build fails because `hyperopen.views.portfolio.optimize.format` does not exist.

2. Add `src/hyperopen/views/portfolio/optimize/format.cljs`, then run:

       npm run test

   Expected after implementation and migration: optimizer view tests and formatter tests pass as part of the full CLJS test suite.

3. Run required repository gates:

       npm run check
       npm test
       npm run test:websocket

   Expected: each command exits with status 0.

## Validation and Acceptance

Acceptance requires all of the following:

- `test/hyperopen/views/portfolio/optimize/format_test.cljs` fails before the production formatter namespace exists and passes after it is implemented.
- No migrated optimizer view test loses a `data-role` hook or action vector.
- The formatter namespace owns the duplicated display helpers; local duplicate definitions are removed from the optimizer view files listed in the plan.
- Required gates pass: `npm run check`, `npm test`, and `npm run test:websocket`.
- Browser QA is not required because this refactor does not change interaction flows or visual styling. Existing Hiccup-level view tests are the appropriate deterministic verification surface.

## Idempotence and Recovery

The refactor is additive before it is subtractive. If a migration step fails, keep the new formatter namespace and restore the last edited call site from the diff by reintroducing the local helper or correcting the `opt-format/` call. No persistent data, migrations, or external services are involved. Re-running tests is safe.

## Interfaces and Dependencies

At completion, `hyperopen.views.portfolio.optimize.format` must expose:

- `(finite-number? value)`
- `(format-pct value)`
- `(format-pct-delta value)`
- `(format-decimal value)`
- `(format-effective-n value universe-size)`
- `(format-usdc value)` and `(format-usdc value opts)`
- `(keyword-label value)`
- `(display-label value)`
- `(format-time ms)`

All functions return strings except `finite-number?`, which returns a boolean truth value. These helpers depend only on browser/JavaScript primitives available to ClojureScript and do not depend on optimizer state.

## Outcomes & Retrospective

Implemented the view-scoped optimizer formatter namespace and migrated duplicate display helpers across the optimizer view folder. This reduced local duplication in ten view namespaces while preserving existing view component names, action vectors, and `data-role` hooks. The new direct formatter test documents the shared behavior for finite numeric detection, percentages, deltas, decimal labels, effective-N labels, USDC labels, keyword labels, display labels, and nil time fallback.

Complexity decreased overall because future optimizer display-format changes now have one obvious owner under `src/hyperopen/views/portfolio/optimize/format.cljs`. The only added complexity is optional formatting arguments for existing one-decimal and zero-decimal call sites; that was kept inside the helper API so call sites remain explicit about their display precision.

Validation passed:

- `npm test` RED phase before implementation: failed because `hyperopen.views.portfolio.optimize.format` was not available.
- `npm run check`: exit 0.
- `npm test`: exit 0, `Ran 3598 tests containing 19732 assertions. 0 failures, 0 errors.`
- `npm run test:websocket`: exit 0, `Ran 461 tests containing 2798 assertions. 0 failures, 0 errors.`

Browser QA was not run because this was a behavior-preserving formatter extraction with no visual styling or interaction-flow changes. Deterministic Hiccup-level view tests and full repo gates covered the change.

Revision note 2026-04-28: Initial ExecPlan created to scope the formatting-helper extraction and record the TDD and validation path before production edits.

Revision note 2026-04-28: Recorded RED-phase formatter-test evidence before adding the production namespace.

Revision note 2026-04-28: Recorded implementation progress, dependency-state discovery, and passing CLJS test evidence before required gates.

Revision note 2026-04-28: Recorded final validation evidence and retrospective before moving the ExecPlan to completed.

Revision note 2026-04-28: Marked the plan completed after moving it to `docs/exec-plans/completed/` and closing `hyperopen-aawh`.
