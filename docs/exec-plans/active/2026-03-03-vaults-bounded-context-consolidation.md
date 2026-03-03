# Consolidate Vaults Bounded Context (DDD View Layer Unification)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Today, a developer or agent trying to understand "the vaults feature" must search across eight separate directories in the source tree. The vault detail view components live in `/hyperopen/src/hyperopen/views/vault_detail/`, while the vault list view model and detail view model live in `/hyperopen/src/hyperopen/views/vaults/`, and two additional standalone entry-point files sit at the `/hyperopen/src/hyperopen/views/` root (`vaults_view.cljs` and `vault_detail_view.cljs`). This fragmentation makes it difficult to reason about the feature boundary, trace dependencies, or determine the impact of changes.

After this change, all vault view-layer code will live under a single directory tree: `/hyperopen/src/hyperopen/views/vaults/`. The domain and application layers (`/hyperopen/src/hyperopen/vaults/`) and the API layers (`/hyperopen/src/hyperopen/api/endpoints/vaults.cljs`, `/hyperopen/src/hyperopen/api/gateway/vaults.cljs`) remain where they are because they already follow consistent patterns shared by account, market, and order features.

A developer can verify the result by confirming that the directory `/hyperopen/src/hyperopen/views/vault_detail/` no longer exists, that no standalone vault view files remain at the `views/` root, and that all vault UI rendering traces back to files under `views/vaults/`. All tests pass (`npm run check`, `npm test`, `npm run test:websocket`) and the application behavior is identical before and after.

## Progress

- [ ] Read all files to be moved and their consumers to confirm namespace references.
- [ ] Milestone 1: Move vault detail components from `views/vault_detail/` into `views/vaults/detail/`.
- [ ] Milestone 2: Move top-level vault entry-point views into `views/vaults/`.
- [ ] Milestone 3: Update external consumers and test runner.
- [ ] Milestone 4: Delete empty directories and run validation gates.

## Surprises & Discoveries

(None yet.)

## Decision Log

- Decision: Keep the API layer (`api/endpoints/vaults.cljs`, `api/gateway/vaults.cljs`) in its current location rather than moving it under `/vaults/`.
  Rationale: The API layer follows a consistent pattern used by every other feature (account, market, orders, funding). Moving vault API files would break that uniformity for zero DDD gain since the API layer is infrastructure, not domain. Consistency across features is more valuable than per-feature co-location for infrastructure code.
  Date/Author: 2026-03-03 / Claude

- Decision: Keep the domain and application layer (`/hyperopen/src/hyperopen/vaults/`) in its current location.
  Rationale: This directory already functions as a coherent bounded context for vault business logic (actions, effects, adapters, detail models). It is well-organized internally with a clean `detail/` sub-tree and `adapters/` directory. No structural change is needed here.
  Date/Author: 2026-03-03 / Claude

- Decision: Rename `views/vault_detail/chart.cljs` to `views/vaults/detail/chart_view.cljs` (namespace `hyperopen.views.vaults.detail.chart-view`) to avoid collision with the existing `views/vaults/detail/chart.cljs` (namespace `hyperopen.views.vaults.detail.chart`), which is a pure chart data model.
  Rationale: The existing `chart.cljs` in `views/vaults/detail/` contains pure functions (`build-chart-model`, `strategy-series-stroke`, `normalize-hover-index`) and is consumed by `detail_vm.cljs`. The file being moved from `vault_detail/chart.cljs` contains Hiccup-rendering view functions (`chart-section`, `chart-series-button`, `chart-timeframe-menu`). Naming the view file `chart_view.cljs` preserves the model/view distinction and avoids a filename collision.
  Date/Author: 2026-03-03 / Claude

- Decision: Rename `views/vaults_view.cljs` to `views/vaults/list_view.cljs` rather than preserving the original name.
  Rationale: Once inside `views/vaults/`, the name `vaults_view` would be redundant. The name `list_view` clarifies that this is the vault list page (as opposed to the detail page) and pairs naturally with `detail_view.cljs`.
  Date/Author: 2026-03-03 / Claude

## Outcomes & Retrospective

(To be filled after completion.)

## Context and Orientation

The Hyperopen vaults feature allows users to browse a list of vaults (at route `/vaults`) and inspect individual vault details (at route `/vaults/:vaultAddress`). The feature was built in a single implementation pass (see the completed exec plan `2026-02-26-vaults-page-parity-with-hyperliquid.md`) and subsequently extended (see `2026-02-27-vault-detail-data-model-and-timeframe-parity.md`). During that work, files were distributed across directories that made sense for incremental delivery but left the view layer fragmented.

The vault feature currently spans these source directories (all paths relative to `/hyperopen/src/hyperopen/`):

1. `vaults/` (8 files) — Domain and application logic: action creators (`actions.cljs`), side effects (`effects.cljs`), a webdata adapter (`adapters/webdata.cljs`), and domain models for vault detail sub-features (`detail/activity.cljs`, `detail/benchmarks.cljs`, `detail/performance.cljs`, `detail/transfer.cljs`, `detail/types.cljs`). This layer is well-organized and does not need structural changes.

2. `views/vaults/` (3 files) — View models and a chart data model: `vm.cljs` (vault list view model), `detail_vm.cljs` (vault detail view model), and `detail/chart.cljs` (pure chart model functions). This is the natural home directory for all vault view code.

3. `views/vault_detail/` (6 files) — Vault detail UI components: `activity.cljs`, `chart.cljs`, `format.cljs`, `hero.cljs`, `panels.cljs`, `transfer_modal.cljs`. These are Hiccup-rendering functions (meaning they produce the HTML-like data structures that Replicant, the rendering library, turns into DOM elements). They are imported by `vault_detail_view.cljs`.

4. `views/vaults_view.cljs` (1 file) — The top-level vault list page view. Imports `views/vaults/vm.cljs`.

5. `views/vault_detail_view.cljs` (1 file) — The top-level vault detail page view. Imports all six files from `views/vault_detail/` and the detail view model from `views/vaults/detail_vm.cljs`.

6. `api/endpoints/vaults.cljs` (1 file) — HTTP endpoint definitions and response normalizers.

7. `api/gateway/vaults.cljs` (1 file) — Thin gateway facade over the endpoint module.

The test tree mirrors the source structure under `/hyperopen/test/hyperopen/`.

A "namespace" in this ClojureScript codebase is the identifier declared at the top of each file in the `(ns ...)` form. ClojureScript maps file paths to namespaces by converting underscores in filenames to hyphens in namespace segments (e.g., file `vault_detail_view.cljs` has namespace `hyperopen.views.vault-detail-view`). When a file moves, its namespace must change to match the new path, and every file that `require`s the old namespace must be updated to reference the new one.

The "test runner" is the file `/hyperopen/test/test_runner_generated.cljs` that lists every test namespace for the test framework to load. When test files move, their entries in this file must be updated.

## Plan of Work

The work is organized into four milestones. Each milestone is independently verifiable: after completing it, `npm test` and `npm run check` must still pass. The milestones move files in dependency order so that at each step, all imports resolve correctly.

### Milestone 1: Move Vault Detail Components into `views/vaults/detail/`

This milestone moves the six UI component files from `views/vault_detail/` into `views/vaults/detail/`, which already exists and contains `chart.cljs` (the chart data model). After this milestone, the `views/vault_detail/` directory is empty and can be deleted. The top-level entry point `views/vault_detail_view.cljs` still exists and is updated to import from the new locations.

The six files to move and their namespace transformations:

`views/vault_detail/activity.cljs` becomes `views/vaults/detail/activity.cljs`. The namespace changes from `hyperopen.views.vault-detail.activity` to `hyperopen.views.vaults.detail.activity`. Inside this file, update the require for `hyperopen.views.vault-detail.chart` to `hyperopen.views.vaults.detail.chart-view` and the require for `hyperopen.views.vault-detail.format` to `hyperopen.views.vaults.detail.format`.

`views/vault_detail/chart.cljs` becomes `views/vaults/detail/chart_view.cljs`. The namespace changes from `hyperopen.views.vault-detail.chart` to `hyperopen.views.vaults.detail.chart-view`. Inside this file, update the require for `hyperopen.views.vault-detail.format` to `hyperopen.views.vaults.detail.format`.

`views/vault_detail/format.cljs` becomes `views/vaults/detail/format.cljs`. The namespace changes from `hyperopen.views.vault-detail.format` to `hyperopen.views.vaults.detail.format`. This file has no internal vault cross-references to update; it only imports `hyperopen.utils.formatting` which does not change.

`views/vault_detail/hero.cljs` becomes `views/vaults/detail/hero.cljs`. The namespace changes from `hyperopen.views.vault-detail.hero` to `hyperopen.views.vaults.detail.hero`. Inside this file, update requires for `hyperopen.views.vault-detail.format` to `hyperopen.views.vaults.detail.format`, `hyperopen.views.vault-detail.panels` to `hyperopen.views.vaults.detail.panels`, and `hyperopen.views.vault-detail.transfer-modal` to `hyperopen.views.vaults.detail.transfer-modal`.

`views/vault_detail/panels.cljs` becomes `views/vaults/detail/panels.cljs`. The namespace changes from `hyperopen.views.vault-detail.panels` to `hyperopen.views.vaults.detail.panels`. Inside this file, update the require for `hyperopen.views.vault-detail.format` to `hyperopen.views.vaults.detail.format`.

`views/vault_detail/transfer_modal.cljs` becomes `views/vaults/detail/transfer_modal.cljs`. The namespace changes from `hyperopen.views.vault-detail.transfer-modal` to `hyperopen.views.vaults.detail.transfer-modal`. This file has no vault cross-references to update.

After moving all six files, update `views/vault_detail_view.cljs` (which stays in place for now). Change its five requires that reference `hyperopen.views.vault-detail.*` to the new `hyperopen.views.vaults.detail.*` namespaces. Specifically:
- `hyperopen.views.vault-detail.activity` becomes `hyperopen.views.vaults.detail.activity`
- `hyperopen.views.vault-detail.chart` becomes `hyperopen.views.vaults.detail.chart-view`
- `hyperopen.views.vault-detail.hero` becomes `hyperopen.views.vaults.detail.hero`
- `hyperopen.views.vault-detail.panels` becomes `hyperopen.views.vaults.detail.panels`
- `hyperopen.views.vault-detail.transfer-modal` becomes `hyperopen.views.vaults.detail.transfer-modal`

Move the corresponding five test files (there is no test for `hero.cljs`):
- `test/hyperopen/views/vault_detail/activity_test.cljs` to `test/hyperopen/views/vaults/detail/activity_test.cljs`, updating its namespace to `hyperopen.views.vaults.detail.activity-test` and its require to `hyperopen.views.vaults.detail.activity`.
- `test/hyperopen/views/vault_detail/chart_test.cljs` to `test/hyperopen/views/vaults/detail/chart_view_test.cljs`, updating its namespace to `hyperopen.views.vaults.detail.chart-view-test` and its require to `hyperopen.views.vaults.detail.chart-view`.
- `test/hyperopen/views/vault_detail/format_test.cljs` to `test/hyperopen/views/vaults/detail/format_test.cljs`, updating its namespace to `hyperopen.views.vaults.detail.format-test` and its require to `hyperopen.views.vaults.detail.format`.
- `test/hyperopen/views/vault_detail/panels_test.cljs` to `test/hyperopen/views/vaults/detail/panels_test.cljs`, updating its namespace to `hyperopen.views.vaults.detail.panels-test` and its require to `hyperopen.views.vaults.detail.panels`.
- `test/hyperopen/views/vault_detail/transfer_modal_test.cljs` to `test/hyperopen/views/vaults/detail/transfer_modal_test.cljs`, updating its namespace to `hyperopen.views.vaults.detail.transfer-modal-test` and its require to `hyperopen.views.vaults.detail.transfer-modal`.

Update `/hyperopen/test/test_runner_generated.cljs` to replace the five old test namespace references with the five new ones.

Delete the now-empty `views/vault_detail/` and `test/hyperopen/views/vault_detail/` directories.

Run `npm test` from `/hyperopen` to verify. All tests should pass with zero failures.

### Milestone 2: Move Top-Level Vault Entry-Point Views into `views/vaults/`

This milestone moves the two standalone vault view files from the `views/` root into `views/vaults/`, completing the view-layer consolidation.

`views/vaults_view.cljs` becomes `views/vaults/list_view.cljs`. The namespace changes from `hyperopen.views.vaults-view` to `hyperopen.views.vaults.list-view`. Inside this file, the existing require for `hyperopen.views.vaults.vm` does not change because that namespace is already under `views/vaults/`.

`views/vault_detail_view.cljs` becomes `views/vaults/detail_view.cljs`. The namespace changes from `hyperopen.views.vault-detail-view` to `hyperopen.views.vaults.detail-view`. All requires inside this file already point to `hyperopen.views.vaults.*` namespaces (updated in Milestone 1) and `hyperopen.views.vaults.detail-vm`, so no internal require changes are needed.

Move the two corresponding test files:
- `test/hyperopen/views/vaults_view_test.cljs` to `test/hyperopen/views/vaults/list_view_test.cljs`, updating its namespace to `hyperopen.views.vaults.list-view-test` and its require to `hyperopen.views.vaults.list-view`.
- `test/hyperopen/views/vault_detail_view_test.cljs` to `test/hyperopen/views/vaults/detail_view_test.cljs`, updating its namespace to `hyperopen.views.vaults.detail-view-test` and its require to `hyperopen.views.vaults.detail-view`.

Update `/hyperopen/test/test_runner_generated.cljs` to replace the two old test namespace references with the two new ones.

Run `npm test` from `/hyperopen` to verify.

### Milestone 3: Update External Consumers

This milestone updates the one external consumer that imports the moved entry-point namespaces.

In `/hyperopen/src/hyperopen/views/app_view.cljs`, update:
- The require `[hyperopen.views.vaults-view :as vaults-view]` to `[hyperopen.views.vaults.list-view :as vaults-view]`. The alias `vaults-view` stays the same so that no rendering code inside `app_view.cljs` needs to change.
- The require `[hyperopen.views.vault-detail-view :as vault-detail-view]` to `[hyperopen.views.vaults.detail-view :as vault-detail-view]`. Again, the alias stays the same.

No other source files outside the `views/vaults/` tree import vault view namespaces. The runtime registration files (`runtime/collaborators.cljs`, `runtime/action_adapters.cljs`, `runtime/effect_adapters.cljs`, `registry/runtime.cljs`) import from `hyperopen.vaults.actions` and `hyperopen.vaults.effects`, which are not changing.

Run `npm test` and `npm run check` from `/hyperopen` to verify.

### Milestone 4: Final Cleanup and Full Validation

Delete any empty directories left behind (`src/hyperopen/views/vault_detail/`, `test/hyperopen/views/vault_detail/`). Verify that no file in the repository still references any of the eight old namespaces by searching for `vault-detail.activity`, `vault-detail.chart`, `vault-detail.format`, `vault-detail.hero`, `vault-detail.panels`, `vault-detail.transfer-modal`, `views.vaults-view`, and `views.vault-detail-view` across all `.cljs` files.

Run all three required validation gates from `/hyperopen`:

    npm run check
    npm test
    npm run test:websocket

All commands must exit with code 0.

## Concrete Steps

All commands run from working directory `/hyperopen`.

Step 1 (Milestone 1): For each of the six source files in `src/hyperopen/views/vault_detail/`, create the new file at its target path under `src/hyperopen/views/vaults/detail/` with the updated namespace declaration and updated internal requires, then delete the original. Repeat for the five test files. Update `vault_detail_view.cljs` requires. Update `test/test_runner_generated.cljs`. Run:

    npm test

Expected: all tests pass, zero failures.

Step 2 (Milestone 2): Move `src/hyperopen/views/vaults_view.cljs` to `src/hyperopen/views/vaults/list_view.cljs` with updated namespace. Move `src/hyperopen/views/vault_detail_view.cljs` to `src/hyperopen/views/vaults/detail_view.cljs` with updated namespace. Move corresponding test files. Update `test/test_runner_generated.cljs`. Run:

    npm test

Expected: all tests pass.

Step 3 (Milestone 3): Update the two requires in `src/hyperopen/views/app_view.cljs`. Run:

    npm test
    npm run check

Expected: all tests pass, type/lint checks pass.

Step 4 (Milestone 4): Delete empty directories. Search for stale namespace references. Run:

    npm run check
    npm test
    npm run test:websocket

Expected: all three commands exit 0.

## Validation and Acceptance

Acceptance is met when all of the following are true:

The directory `src/hyperopen/views/vault_detail/` does not exist. The files `src/hyperopen/views/vaults_view.cljs` and `src/hyperopen/views/vault_detail_view.cljs` do not exist. All vault view code lives under `src/hyperopen/views/vaults/`. No `.cljs` file in the repository references any of the eight old namespaces. The three required validation gates (`npm run check`, `npm test`, `npm run test:websocket`) all exit with code 0. The application renders `/vaults` and `/vaults/:vaultAddress` identically to before the refactor (no behavioral change).

## Idempotence and Recovery

Every step is a file move plus a find-and-replace of namespace strings. Steps can be retried safely. If a step fails partway (for example, some files moved but namespace references not yet updated), the fix is to complete the remaining namespace updates. No data is destroyed; original files are deleted only after the new files are confirmed in place.

If tests fail after a milestone, the most likely cause is a missed namespace reference. Search for the old namespace string across all `.cljs` files and update any remaining occurrences.

## Artifacts and Notes

Complete namespace rename map (8 source namespaces, 7 test namespaces):

    hyperopen.views.vault-detail.activity        -> hyperopen.views.vaults.detail.activity
    hyperopen.views.vault-detail.chart            -> hyperopen.views.vaults.detail.chart-view
    hyperopen.views.vault-detail.format           -> hyperopen.views.vaults.detail.format
    hyperopen.views.vault-detail.hero             -> hyperopen.views.vaults.detail.hero
    hyperopen.views.vault-detail.panels           -> hyperopen.views.vaults.detail.panels
    hyperopen.views.vault-detail.transfer-modal   -> hyperopen.views.vaults.detail.transfer-modal
    hyperopen.views.vaults-view                   -> hyperopen.views.vaults.list-view
    hyperopen.views.vault-detail-view             -> hyperopen.views.vaults.detail-view

    hyperopen.views.vault-detail.activity-test        -> hyperopen.views.vaults.detail.activity-test
    hyperopen.views.vault-detail.chart-test            -> hyperopen.views.vaults.detail.chart-view-test
    hyperopen.views.vault-detail.format-test           -> hyperopen.views.vaults.detail.format-test
    hyperopen.views.vault-detail.panels-test           -> hyperopen.views.vaults.detail.panels-test
    hyperopen.views.vault-detail.transfer-modal-test   -> hyperopen.views.vaults.detail.transfer-modal-test
    hyperopen.views.vaults-view-test                   -> hyperopen.views.vaults.list-view-test
    hyperopen.views.vault-detail-view-test             -> hyperopen.views.vaults.detail-view-test

Target directory structure after completion (all paths relative to `src/hyperopen/`):

    vaults/                              # Domain + Application (unchanged)
    +-- actions.cljs
    +-- effects.cljs
    +-- adapters/
    |   +-- webdata.cljs
    +-- detail/
        +-- activity.cljs
        +-- benchmarks.cljs
        +-- performance.cljs
        +-- transfer.cljs
        +-- types.cljs

    views/vaults/                        # All vault views (consolidated)
    +-- vm.cljs                          # List view model (unchanged)
    +-- list_view.cljs                   # List page view (moved from views/vaults_view.cljs)
    +-- detail_vm.cljs                   # Detail view model (unchanged)
    +-- detail_view.cljs                 # Detail page view (moved from views/vault_detail_view.cljs)
    +-- detail/
        +-- chart.cljs                   # Chart data model (unchanged)
        +-- chart_view.cljs              # Chart UI (moved from views/vault_detail/chart.cljs)
        +-- activity.cljs                # Activity panel (moved from views/vault_detail/activity.cljs)
        +-- format.cljs                  # Formatting helpers (moved from views/vault_detail/format.cljs)
        +-- hero.cljs                    # Hero section (moved from views/vault_detail/hero.cljs)
        +-- panels.cljs                  # Tab panels (moved from views/vault_detail/panels.cljs)
        +-- transfer_modal.cljs          # Transfer modal (moved from views/vault_detail/transfer_modal.cljs)

    api/endpoints/vaults.cljs            # API endpoints (unchanged)
    api/gateway/vaults.cljs              # API gateway (unchanged)

External consumer requiring update: `src/hyperopen/views/app_view.cljs` (2 require lines).

Files that do NOT change (runtime registration, state defaults, API projections, all files under `vaults/`, all files under `api/`): these reference `hyperopen.vaults.actions`, `hyperopen.vaults.effects`, and `hyperopen.api.gateway.vaults` namespaces, none of which are affected by this refactor.

## Interfaces and Dependencies

No new libraries or external dependencies are required.

No public interfaces change semantically. The functions exported by each moved file remain identical; only their namespace addresses change. The two interfaces consumed by `app_view.cljs` are:

- `hyperopen.views.vaults.list-view/vaults-view` (was `hyperopen.views.vaults-view/vaults-view`)
- `hyperopen.views.vaults.detail-view/vault-detail-view` (was `hyperopen.views.vault-detail-view/vault-detail-view`)

All other public interfaces in the vaults feature remain at their current namespace paths and are unaffected.
