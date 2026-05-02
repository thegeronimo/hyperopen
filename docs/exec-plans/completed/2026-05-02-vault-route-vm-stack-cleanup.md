# Vault Route VM Stack Cleanup

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked `bd` work is `hyperopen-k7lr`, and `bd` remains the lifecycle source of truth while this plan records the implementation story.

## Purpose / Big Picture

The vault route works, but three adjacent owners have grown past the repository's 500-line namespace guardrail: `src/hyperopen/views/vaults/detail_vm.cljs`, `src/hyperopen/views/vaults/list_view.cljs`, and `src/hyperopen/vaults/application/list_vm.cljs`. This slows vault work because route rendering, list presentation, list caching and pagination, detail chart/benchmark assembly, and detail activity assembly are all harder to review than necessary.

After this cleanup, the same public entry points will remain available, but each oversized file will become a narrower facade or orchestrator backed by focused child namespaces. A contributor can verify the result by running `npm run lint:namespace-sizes` and seeing no size exceptions for these three source files, then running the focused vault tests and required repo gates.

## Progress

- [x] (2026-05-02T02:21Z) Created and claimed `bd` task `hyperopen-k7lr` for the vault route VM stack cleanup.
- [x] (2026-05-02T02:22Z) Audited the current target sizes: `detail_vm.cljs` 746 lines, `list_view.cljs` 745 lines, and `vaults/application/list_vm.cljs` 610 lines.
- [x] (2026-05-02T02:24Z) Authored this active ExecPlan with structural RED, source split, focused validation, and required gate expectations.
- [x] (2026-05-02T02:25Z) Removed the three source namespace-size exceptions and confirmed `npm run lint:namespace-sizes` fails on exactly those target source files.
- [x] (2026-05-02T02:33Z) Split `hyperopen.vaults.application.list-vm` into focused list-row, filtering/sorting, pagination, preview, and cache/model owners while preserving `vault-list-vm`, `build-startup-preview-record`, and `reset-vault-list-vm-cache!`.
- [x] (2026-05-02T02:41Z) Split `hyperopen.views.vaults.list-view` into focused control, row/sparkline, loading, pagination, and section rendering owners while preserving `vaults-view` and exported `route-view`.
- [x] (2026-05-02T02:51Z) Split `hyperopen.views.vaults.detail-vm` into focused detail context, cache, chart-section, and activity-section owners while preserving `vault-detail-vm` and `reset-vault-detail-vm-cache!`.
- [x] (2026-05-02T03:02Z) Ran focused vault model/view tests, namespace-size lint, `npm run check`, `npm test`, and `npm run test:websocket`; all exited 0.
- [x] (2026-05-02T03:03Z) Moved this plan to completed and closed `hyperopen-k7lr` after acceptance passed.
- [x] (2026-05-02T03:05Z) Reran `npm run check` after moving the plan to completed; the final tree check exited 0.

## Surprises & Discoveries

- Observation: Existing vault coverage is broad enough for a behavior-preserving split.
  Evidence: `test/hyperopen/vaults/application/list_vm_test.cljs`, `test/hyperopen/views/vaults/list_view_test.cljs`, and `test/hyperopen/views/vaults/detail_vm_test.cljs` already characterize grouping, filtering, sorting, pagination, preview handoff, Hiccup structure, chart output, activity output, and transfer output.

- Observation: The structural RED phase failed for exactly the three requested vault source files.
  Evidence: `npm run lint:namespace-sizes` reported missing exceptions for `src/hyperopen/views/vaults/list_view.cljs` at 745 lines, `src/hyperopen/views/vaults/detail_vm.cljs` at 746 lines, and `src/hyperopen/vaults/application/list_vm.cljs` at 610 lines.

- Observation: Local `node_modules` was missing the declared `lucide` package, so the Node test runner failed before any vault test executed.
  Evidence: `node out/test.js --test=hyperopen.vaults.application.list-vm-test` first failed with `Cannot find module 'lucide/dist/esm/icons/external-link.js'`; `npm install` restored declared packages without changing `package.json` or `package-lock.json`.

- Observation: The list application split preserved the existing read-model behavior.
  Evidence: `node out/test.js --test=hyperopen.vaults.application.list-vm-test` ran 9 tests / 51 assertions with 0 failures and 0 errors after the split.

- Observation: The list route view split preserved the existing Hiccup contract.
  Evidence: `npx shadow-cljs --force-spawn compile test` completed with 0 warnings, and `node out/test.js --test=hyperopen.views.vaults.list-view-test` ran 13 tests / 60 assertions with 0 failures and 0 errors.

- Observation: The detail VM cache atoms are used by private-var characterization tests, so the facade must retain those private var names even after moving cache ownership.
  Evidence: The first compile after the split failed at `test/hyperopen/views/vaults/detail_vm_test.cljs:556` with `Unable to resolve var: summary-cache`; aliasing the cache atoms from the facade restored the contract.

- Observation: The detail VM split preserved the existing chart, activity, transfer, and cache behavior covered by the focused suites.
  Evidence: `npx shadow-cljs --force-spawn compile test` completed with 0 warnings, and `node out/test.js --test=hyperopen.views.vaults.detail-vm-test,hyperopen.views.vaults.detail-vm-returns-chart-test` ran 22 tests / 133 assertions with 0 failures and 0 errors.

- Observation: The final source line counts are all below the repository's 500-line namespace guardrail.
  Evidence: `wc -l` reported `detail_vm.cljs` 120, largest `detail_vm/*` child 202, `list_view.cljs` 154, largest `list_view/*` child 149, `vaults/application/list_vm.cljs` 110, and largest `vaults/application/list_vm/*` child 188.

- Observation: No browser-inspection sessions were created for this refactor, and no browser-flow or browser-tooling behavior changed.
  Evidence: This work moved pure VM and Hiccup helpers behind stable facades. Browser QA is accounted for as not run; deterministic Hiccup/view-model tests, namespace lint, app/worker/test compilation, and full repo gates covered the behavior-preserving split.

## Decision Log

- Decision: Use compatibility facades instead of changing external imports.
  Rationale: Runtime route modules and tests already depend on `hyperopen.vaults.application.list-vm`, `hyperopen.views.vaults.list-view`, and `hyperopen.views.vaults.detail-vm`. Keeping those names stable reduces blast radius while still retiring the namespace-size debt.
  Date/Author: 2026-05-02 / Codex

- Decision: Use namespace-size lint as the RED phase for the structural cleanup.
  Rationale: This refactor is intended to preserve behavior. Removing the three exception entries before implementation should make `npm run lint:namespace-sizes` fail for the exact debt the cleanup retires; acceptance is that the same command passes without replacement exceptions.
  Date/Author: 2026-05-02 / Codex

- Decision: Treat browser QA as accounted-for but not required for this pass unless tests or rendering behavior change.
  Rationale: The implementation will move pure Hiccup and VM helper ownership without changing the rendered route contract. The existing Hiccup tests and Playwright vault chart regression remain the deterministic browser-facing coverage; if visual behavior changes during implementation, run the smallest relevant Playwright command before signoff.
  Date/Author: 2026-05-02 / Codex

## Outcomes & Retrospective

Implementation retired the three target source namespace-size exceptions without replacement caps. The old public namespaces now act as small route/read-model facades, and focused child namespaces own row parsing, list filtering, list pagination, list preview/cache behavior, list controls/rows/loading/sections, detail context helpers, detail cache behavior, chart/benchmark assembly, and detail activity assembly.

The final source line counts are:

- `src/hyperopen/views/vaults/detail_vm.cljs`: 120 lines; largest child namespace 202 lines.
- `src/hyperopen/views/vaults/list_view.cljs`: 154 lines; largest child namespace 149 lines.
- `src/hyperopen/vaults/application/list_vm.cljs`: 110 lines; largest child namespace 188 lines.

Validation passed:

- `npm run lint:namespace-sizes` exited 0.
- `npx shadow-cljs --force-spawn compile test` exited 0 with 0 warnings.
- `node out/test.js --test=hyperopen.vaults.application.list-vm-test,hyperopen.views.vaults.list-view-test,hyperopen.views.vaults.detail-vm-test,hyperopen.views.vaults.detail-vm-returns-chart-test,hyperopen.views.vaults.detail-view-test,hyperopen.views.vaults.vm-test` ran 59 tests / 315 assertions with 0 failures and 0 errors.
- `npm run check` exited 0.
- Final-tree `npm run check` after moving this plan to completed exited 0.
- `npm test` ran 3684 tests / 20315 assertions with 0 failures and 0 errors.
- `npm run test:websocket` ran 520 tests / 3027 assertions with 0 failures and 0 errors.
- `git diff --check` exited 0.

Retrospective: this reduced overall complexity. The public route and VM interfaces stayed stable, while the largest touched owner dropped from 746 lines to 202 lines in its largest focused child. The one compatibility wrinkle was the existing private-var cache characterization in `detail_vm_test.cljs`; keeping private facade aliases for those atoms preserves the current test contract without re-centralizing cache logic.

## Context and Orientation

The repository enforces a maximum of 500 lines per ClojureScript namespace. Oversized namespaces are allowed only when listed in `dev/namespace_size_exceptions.edn` with an owner, reason, maximum line count, and retirement date. The current target entries are:

- `src/hyperopen/views/vaults/detail_vm.cljs` with a cap of 747 lines.
- `src/hyperopen/views/vaults/list_view.cljs` with a cap of 745 lines.
- `src/hyperopen/vaults/application/list_vm.cljs` with a cap of 620 lines.

`src/hyperopen/vaults/application/list_vm.cljs` is the application read model for the vault list route. A "read model" is a pure data map shaped for rendering. The file currently parses raw vault rows, normalizes snapshot preview data, applies search/filter/sort policy, partitions protocol versus user vaults, paginates user rows, manages startup-preview handoff, and caches expensive derivation. The public functions that must remain stable are `vault-list-vm`, `build-startup-preview-record`, and `reset-vault-list-vm-cache!`.

`src/hyperopen/views/vaults/list_view.cljs` renders the vault list Hiccup. Hiccup is the ClojureScript vector representation of DOM nodes. The file currently owns formatting, menus, responsive desktop/mobile detection, sparkline SVG modeling, desktop rows, mobile cards, loading skeletons, pagination controls, table sections, and the top-level route view. The public functions that must remain stable are `vaults-view` and exported `route-view`.

`src/hyperopen/views/vaults/detail_vm.cljs` is the vault detail read-model assembler. It currently parses the route, looks up account and vault data, manages several VM-local caches, builds chart and benchmark sections, builds activity sections, and merges everything into the final map consumed by detail views. The public functions that must remain stable are `vault-detail-vm` and `reset-vault-detail-vm-cache!`.

## Plan of Work

First, remove the three target entries from `dev/namespace_size_exceptions.edn` and run `npm run lint:namespace-sizes`. This should fail because the three source files are still oversized. Record that output as the structural RED evidence.

Second, split `src/hyperopen/vaults/application/list_vm.cljs` while preserving the old namespace as the public facade. Create child namespaces under `src/hyperopen/vaults/application/list_vm/`: `rows.cljs` for row parsing and snapshot helpers, `filtering.cljs` for search/filter/sort/partition behavior, `pagination.cljs` for page normalization and model shaping, `preview.cljs` for startup-preview derivation, and `cache.cljs` for cache atoms and cached model orchestration. Keep all child namespaces under 500 lines.

Third, split `src/hyperopen/views/vaults/list_view.cljs` into child namespaces under `src/hyperopen/views/vaults/list_view/`: `format.cljs` for display formatting and class helpers, `controls.cljs` for dropdown, filter, range, and sort controls, `rows.cljs` for desktop rows, mobile cards, and sparkline modeling, `loading.cljs` for loading skeletons, `pagination.cljs` for user-vault page controls, and `sections.cljs` for section table/card rendering. Keep `list_view.cljs` as the route-level composition facade.

Fourth, split `src/hyperopen/views/vaults/detail_vm.cljs` into child namespaces under `src/hyperopen/views/vaults/detail_vm/`: `context.cljs` for route/account/detail source derivation and small normalization helpers, `cache.cljs` for summary/chart/benchmark/metrics caches and reset behavior, `chart_section.cljs` for chart, benchmark, and performance metrics section assembly, and `activity_section.cljs` for activity source and table model assembly. Keep `detail_vm.cljs` as the route-level assembler facade.

Fifth, run focused tests and required gates. Update this plan after each milestone with progress, surprises, and the exact validation evidence.

## Concrete Steps

All commands are run from `/Users/barry/.codex/worktrees/383a/hyperopen`.

1. Structural RED:

        npm run lint:namespace-sizes

   Expected before source splits: failure showing missing size exceptions for `src/hyperopen/views/vaults/detail_vm.cljs`, `src/hyperopen/views/vaults/list_view.cljs`, and `src/hyperopen/vaults/application/list_vm.cljs`.

2. Focused source/test validation after the splits:

        npx shadow-cljs --force-spawn compile test
        node out/test.js --test=hyperopen.vaults.application.list-vm-test,hyperopen.views.vaults.list-view-test,hyperopen.views.vaults.detail-vm-test,hyperopen.views.vaults.detail-vm-returns-chart-test,hyperopen.views.vaults.detail-view-test

   Expected after implementation: all listed tests run with 0 failures and 0 errors.

3. Required gates:

        npm run lint:namespace-sizes
        npm run check
        npm test
        npm run test:websocket

   Expected after implementation: every command exits 0. If any command fails for a pre-existing unrelated issue, record the exact failure and create or link a `bd` follow-up instead of hiding it in this plan.

## Validation and Acceptance

Acceptance is structural and behavioral. Structurally, all three target files must be at or below 500 lines, no replacement entries for those files may remain in `dev/namespace_size_exceptions.edn`, and every new source namespace created by this plan must also be at or below 500 lines. Behaviorally, the old public functions must continue to work through the same namespaces, the focused vault tests must pass, and the required repo gates must pass.

This refactor is not accepted if it only raises caps, deletes regression coverage, changes public route exports, or moves UI behavior without deterministic validation.

## Idempotence and Recovery

The splits are additive-first: create focused namespaces, call them from the old public namespace, run focused tests, then remove moved private code from the old namespace. If a split fails, inspect the failing test and move the smallest missing helper or require rather than changing behavior. `npm run lint:namespace-sizes` is safe to run repeatedly. If generated test output changes, keep generated files only when new test namespaces are intentionally added; this plan is not expected to add new test namespaces.

## Artifacts and Notes

Initial size inventory:

        746 src/hyperopen/views/vaults/detail_vm.cljs
        745 src/hyperopen/views/vaults/list_view.cljs
        610 src/hyperopen/vaults/application/list_vm.cljs

Final size inventory:

        120 src/hyperopen/views/vaults/detail_vm.cljs
        202 src/hyperopen/views/vaults/detail_vm/activity_section.cljs
        194 src/hyperopen/views/vaults/detail_vm/cache.cljs
        173 src/hyperopen/views/vaults/detail_vm/chart_section.cljs
         92 src/hyperopen/views/vaults/detail_vm/context.cljs
        154 src/hyperopen/views/vaults/list_view.cljs
        146 src/hyperopen/views/vaults/list_view/controls.cljs
         47 src/hyperopen/views/vaults/list_view/format.cljs
         48 src/hyperopen/views/vaults/list_view/loading.cljs
        140 src/hyperopen/views/vaults/list_view/pagination.cljs
        149 src/hyperopen/views/vaults/list_view/rows.cljs
         82 src/hyperopen/views/vaults/list_view/sections.cljs
        110 src/hyperopen/vaults/application/list_vm.cljs
        125 src/hyperopen/vaults/application/list_vm/cache.cljs
         81 src/hyperopen/vaults/application/list_vm/filtering.cljs
         26 src/hyperopen/vaults/application/list_vm/pagination.cljs
         96 src/hyperopen/vaults/application/list_vm/preview.cljs
        188 src/hyperopen/vaults/application/list_vm/rows.cljs

## Interfaces and Dependencies

Public source interfaces that must remain available:

- `hyperopen.vaults.application.list-vm/reset-vault-list-vm-cache!`
- `hyperopen.vaults.application.list-vm/build-startup-preview-record`
- `hyperopen.vaults.application.list-vm/vault-list-vm`
- `hyperopen.views.vaults.list-view/vaults-view`
- `hyperopen.views.vaults.list-view/route-view`
- `hyperopen.views.vaults.detail-vm/reset-vault-detail-vm-cache!`
- `hyperopen.views.vaults.detail-vm/vault-detail-vm`

Revision note, 2026-05-02T02:24Z: Initial ExecPlan created for `hyperopen-k7lr` after auditing the vault route VM stack, namespace-size exceptions, and existing vault model/view coverage. The plan intentionally uses compatibility facades and namespace-size lint as structural RED evidence because this is a behavior-preserving ownership refactor.

Revision note, 2026-05-02T02:25Z: Recorded structural RED evidence after removing the three target source exception entries and running `npm run lint:namespace-sizes`.

Revision note, 2026-05-02T02:33Z: Recorded the completed list application VM split, local dependency restore needed for Node test execution, and focused list model test evidence.

Revision note, 2026-05-02T02:41Z: Recorded the completed list route view split and focused Hiccup test evidence.

Revision note, 2026-05-02T02:51Z: Recorded the completed detail VM split, cache facade compatibility discovery, and focused detail VM test evidence.

Revision note, 2026-05-02T03:03Z: Recorded final line counts, focused validation, required repository gates, browser-QA accounting, and retrospective after accepting the refactor.

Revision note, 2026-05-02T03:05Z: Recorded the final-tree `npm run check` after moving the plan to completed and closing `hyperopen-k7lr`.
