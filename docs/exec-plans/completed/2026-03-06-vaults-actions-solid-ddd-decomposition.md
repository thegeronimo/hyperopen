# Decompose vaults actions into funding-style SOLID/DDD seams

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Tracking epic: `hyperopen-a6q`.

Child tasks:

- `hyperopen-a6q.1` Extract vault domain policy from actions monolith.
- `hyperopen-a6q.2` Move vault route parsing and load orchestration into focused seams.
- `hyperopen-a6q.3` Split vault UI commands into bounded application modules.
- `hyperopen-a6q.4` Move vault snapshot restore and persistence wiring into infrastructure.
- `hyperopen-a6q.5` Reduce vault actions to a compatibility facade and lock the boundaries.

## Purpose / Big Picture

After this refactor, changing vault route loading, transfer policy, startup restore, or detail interaction state will no longer require reopening one 912-line namespace that mixes unrelated concerns. `/hyperopen/src/hyperopen/vaults/actions.cljs` will remain the stable public entry point for runtime action vars, but it will become a thin compatibility facade similar to `/hyperopen/src/hyperopen/funding/actions.cljs`, with real ownership moved into focused `/hyperopen/src/hyperopen/vaults/domain/`, `/hyperopen/src/hyperopen/vaults/application/`, and `/hyperopen/src/hyperopen/vaults/infrastructure/` modules.

User-visible behavior must stay the same. `/vaults` list hydration, `/vaults/:address` detail hydration, `/portfolio` vault prefetch behavior, transfer modal validation and submit requests, chart hover behavior, and snapshot-range startup restore should all behave exactly as they do now. The observable proof is structural and behavioral: the root vault actions namespace becomes a short facade, focused tests move to boundary-specific files, and `npm run check`, `npm test`, and `npm run test:websocket` all pass.

## Progress

- [x] (2026-03-07 01:02Z) Audited `/hyperopen/src/hyperopen/vaults/actions.cljs`, the cited hotspot functions, and direct callers in startup, runtime, views, and tests.
- [x] (2026-03-07 01:04Z) Read the governing architecture, planning, work-tracking, browser-storage, reliability, and security documents needed for this boundary refactor.
- [x] (2026-03-07 01:05Z) Created `bd` epic `hyperopen-a6q` and child tasks `hyperopen-a6q.1` through `hyperopen-a6q.5`.
- [x] (2026-03-07 01:08Z) Authored this ExecPlan in `/hyperopen/docs/exec-plans/active/2026-03-06-vaults-actions-solid-ddd-decomposition.md`.
- [x] (2026-03-07 01:21Z) Added `/hyperopen/src/hyperopen/vaults/domain/identity.cljs`, `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, and `/hyperopen/src/hyperopen/vaults/domain/ui_state.cljs` to own pure vault identity, transfer policy, and reusable UI normalization.
- [x] (2026-03-07 01:21Z) Added `/hyperopen/src/hyperopen/vaults/infrastructure/routes.cljs` and `/hyperopen/src/hyperopen/vaults/infrastructure/persistence.cljs` for browser route translation and snapshot-range persistence ownership.
- [x] (2026-03-07 01:21Z) Added `/hyperopen/src/hyperopen/vaults/application/list_commands.cljs`, `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs`, `/hyperopen/src/hyperopen/vaults/application/transfer_commands.cljs`, and `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs`.
- [x] (2026-03-07 01:21Z) Reduced `/hyperopen/src/hyperopen/vaults/actions.cljs` to a 131-line compatibility facade that re-exports stable public vars and assembles the injected collaborators needed by application modules.
- [x] (2026-03-07 01:23Z) Updated `/hyperopen/src/hyperopen/app/startup.cljs`, `/hyperopen/src/hyperopen/state/app_defaults.cljs`, `/hyperopen/src/hyperopen/vaults/effects.cljs`, `/hyperopen/src/hyperopen/vaults/detail/transfer.cljs`, `/hyperopen/src/hyperopen/views/vaults/vm.cljs`, and `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` to depend on focused owner modules instead of the monolith where appropriate.
- [x] (2026-03-07 01:26Z) Added direct boundary tests under `/hyperopen/test/hyperopen/vaults/application/`, `/hyperopen/test/hyperopen/vaults/domain/`, and `/hyperopen/test/hyperopen/vaults/infrastructure/`, then regenerated `/hyperopen/test/test_runner_generated.cljs`.
- [x] (2026-03-07 01:32Z) Restored missing local Node dependencies with `npm ci` after `npm test` failed because the worktree did not have a runnable `shadow-cljs` binary on the npm PATH.
- [x] (2026-03-07 01:33Z) Passed `npm test`.
- [x] (2026-03-07 01:34Z) Passed `npm run check`.
- [x] (2026-03-07 01:34Z) Passed `npm run test:websocket`.

## Surprises & Discoveries

- Observation: the current vault action namespace is not just a command surface. It is also a startup restore dependency and a runtime route-loader dependency.
  Evidence: `/hyperopen/src/hyperopen/app/startup.cljs` injects `restore-vaults-snapshot-range!`, `/hyperopen/src/hyperopen/startup/init.cljs` calls that restore function twice during init, and `/hyperopen/src/hyperopen/runtime/action_adapters.cljs` plus `/hyperopen/src/hyperopen/startup/runtime.cljs` dispatch `:actions/load-vault-route`.

- Observation: the route loader already crosses page boundaries by hydrating vault list data on `/portfolio`, not only on `/vaults`.
  Evidence: `load-vault-route` in `/hyperopen/src/hyperopen/vaults/actions.cljs` returns `load-vault-list-effects` when the normalized path starts with `/portfolio`, and `/hyperopen/test/hyperopen/vaults/actions_test.cljs` locks that behavior in.

- Observation: there is already an active vaults refactor plan in `/hyperopen/docs/exec-plans/active/2026-03-03-vaults-bounded-context-consolidation.md`, but it targets view-file co-location rather than command and policy decomposition.
  Evidence: that plan explicitly keeps `/hyperopen/src/hyperopen/vaults/` in place and moves only view-layer files under `/hyperopen/src/hyperopen/views/vaults/`.

- Observation: the funding slice already demonstrates the intended end state for a stable facade over focused owners.
  Evidence: `/hyperopen/src/hyperopen/funding/actions.cljs` is a 30-line alias facade over `/hyperopen/src/hyperopen/funding/application/modal_actions.cljs` and `/hyperopen/src/hyperopen/funding/domain/*.cljs`, while `/hyperopen/src/hyperopen/vaults/actions.cljs` is currently 912 lines.

- Observation: snapshot-range restore is intentionally idempotent and currently runs twice during startup.
  Evidence: `/hyperopen/src/hyperopen/startup/init.cljs` restores persisted UI state before router initialization and then re-runs `restore-vaults-snapshot-range!` after router init for deep-link refreshes.

- Observation: the initial `npm test` failure was environmental, not architectural.
  Evidence: the first run failed with `sh: shadow-cljs: command not found`; running `npm ci` restored the local npm bin scripts and the next `npm test` passed without code changes.

## Decision Log

- Decision: keep `/hyperopen/src/hyperopen/vaults/actions.cljs` as the stable public facade instead of deleting it.
  Rationale: runtime registration, collaborators, tests, and view code already bind to its public vars. Preserving the namespace path and public function names keeps the refactor architectural instead of turning it into an avoidable integration churn event.
  Date/Author: 2026-03-07 / Codex

- Decision: treat browser route parsing as an Anti-Corruption Layer or infrastructure concern, and treat route-triggered vault hydration as an application concern.
  Rationale: the raw browser path is external input shape, while deciding which vault effects should run for `:list`, `:detail`, and `/portfolio` prefetch cases is application orchestration. Splitting those concerns follows both DDD boundary rules and Dependency Inversion.
  Date/Author: 2026-03-07 / Codex

- Decision: keep snapshot range in `localStorage`, but move restore ownership out of the command module into infrastructure.
  Rationale: `/hyperopen/docs/BROWSER_STORAGE.md` says tiny synchronous startup preferences should stay in `localStorage`; the problem is not the storage backend, it is the ownership boundary.
  Date/Author: 2026-03-07 / Codex

- Decision: preserve the current double-restore startup semantics in the first landing.
  Rationale: `/hyperopen/ARCHITECTURE.md` and `/hyperopen/docs/RELIABILITY.md` require startup handlers to be idempotent and reentrant. The safe refactor is to keep the existing semantics while moving ownership, then reconsider redundancy only if tests prove it is unnecessary.
  Date/Author: 2026-03-07 / Codex

- Decision: keep this plan separate from the active vault view consolidation plan.
  Rationale: combining file-move work in `/hyperopen/src/hyperopen/views/**` with action-domain decomposition in `/hyperopen/src/hyperopen/vaults/**` would blur acceptance criteria and make rollback harder. The two efforts can proceed independently.
  Date/Author: 2026-03-07 / Codex

- Decision: introduce `/hyperopen/src/hyperopen/vaults/domain/ui_state.cljs` in addition to `identity.cljs` and `transfer_policy.cljs`.
  Rationale: pure vault UI normalization and defaults are consumed by both application command modules and view-model modules. Keeping those rules in a dedicated pure owner avoids duplicating them across `application/*` while still keeping browser persistence and orchestration out of domain.
  Date/Author: 2026-03-07 / Codex

## Outcomes & Retrospective

Implemented. `/hyperopen/src/hyperopen/vaults/actions.cljs` now serves as a thin compatibility facade over focused owner modules in `/hyperopen/src/hyperopen/vaults/domain/`, `/hyperopen/src/hyperopen/vaults/application/`, and `/hyperopen/src/hyperopen/vaults/infrastructure/`. Startup restore now depends on the persistence owner directly, route parsing now lives in infrastructure, route-loading orchestration now lives in application, and transfer policy plus shared vault UI normalization now live in domain.

The compatibility surface remained stable: runtime collaborators and action ids still point at `hyperopen.vaults.actions`, `/portfolio` still triggers vault list hydration, and the transfer modal still emits the same submit request shape. The main lesson from the implementation is that pure UI normalization needed its own owner (`domain/ui_state.cljs`) because both view models and command modules rely on it; forcing those rules into command namespaces would have created new cross-layer coupling rather than removing it.

## Context and Orientation

The current implementation lives primarily in `/hyperopen/src/hyperopen/vaults/actions.cljs`. The review hotspots are:

- `parse-vault-route` at line 151, which interprets browser route strings.
- `vault-transfer-preview` at line 494, which mixes modal state normalization, route fallback, transfer policy, localized amount parsing, and request shaping.
- `load-vaults`, `load-vault-detail`, and `load-vault-route` around lines 561 through 610, which mix application orchestration with route interpretation and benchmark/history fanout.
- `restore-vaults-snapshot-range!` at line 643, which performs `localStorage`-backed store mutation in the same namespace as pure command functions.

In this repository, a "surface action" is a pure function that receives application state and user input, then returns a vector of declarative effect descriptions such as `[:effects/save ...]` or `[:effects/api-fetch-vault-details ...]`. A "compatibility facade" is a namespace that preserves stable public vars but delegates the actual behavior to smaller owner modules. An "Anti-Corruption Layer" means a boundary module that translates an external shape, such as a browser URL path, into a domain-meaningful internal map before the rest of the feature depends on it.

The current vault action file owns at least six different reasons to change:

1. URL parsing and route classification.
2. Pure normalization for snapshot ranges, tabs, page sizes, transfer modes, and chart series.
3. Vault transfer business policy and request shaping.
4. Load orchestration for list/detail routes, component-vault history fanout, and benchmark fetch fanout.
5. Detail interaction behavior such as benchmark chip management, pagination, activity sorting, and chart hover math.
6. Browser-persistence restore for snapshot range.

Those responsibilities already have different dependency directions. Route parsing translates browser input, so it belongs at the boundary. Transfer preview is pure vault policy, so it belongs in domain. Load sequencing is application orchestration. Startup restore belongs in infrastructure and startup assembly. Keeping them together is the architectural problem.

The direct callers that make compatibility important are:

- `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`, which calls `load-vault-route` during navigation and wallet-connection refresh.
- `/hyperopen/src/hyperopen/startup/runtime.cljs`, which dispatches `:actions/load-vault-route` during bootstrap and address changes.
- `/hyperopen/src/hyperopen/app/startup.cljs`, which injects `restore-vaults-snapshot-range!` into startup dependencies.
- `/hyperopen/src/hyperopen/views/vaults/detail/transfer.cljs`, which calls `vault-transfer-preview`.
- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`, which publishes the stable action ids.

The funding slice provides the local precedent for the target architecture. `/hyperopen/src/hyperopen/funding/actions.cljs` is a thin facade. Pure lifecycle and policy logic lives in `/hyperopen/src/hyperopen/funding/domain/*.cljs`. Orchestration lives in `/hyperopen/src/hyperopen/funding/application/*.cljs`. Infrastructure-specific route or RPC concerns live in `/hyperopen/src/hyperopen/funding/infrastructure/*.cljs`.

The target structure for vaults in this plan is:

- `/hyperopen/src/hyperopen/vaults/domain/identity.cljs` for vault-address normalization, leader or name lookup helpers, and child-address normalization used by other pure policy.
- `/hyperopen/src/hyperopen/vaults/domain/ui_state.cljs` for pure vault UI defaults and normalization shared by view models and command modules.
- `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` for transfer-mode normalization, default transfer modal state, localized amount parsing to USDC micros, deposit eligibility policy, and transfer preview request shaping.
- `/hyperopen/src/hyperopen/vaults/application/list_commands.cljs` for search, filters, snapshot range changes, sorting, page-size changes, and page navigation.
- `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs` for detail tabs, activity sorting and filter state, returns benchmark chip or search behavior, and chart-hover interaction state.
- `/hyperopen/src/hyperopen/vaults/application/transfer_commands.cljs` for opening, closing, mutating, and submitting the transfer modal while depending on `transfer_policy`.
- `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs` for list and detail load orchestration, projection-first ordering, component-vault fanout, and benchmark hydration decisions.
- `/hyperopen/src/hyperopen/vaults/infrastructure/routes.cljs` for route-path normalization and `parse-vault-route`.
- `/hyperopen/src/hyperopen/vaults/infrastructure/persistence.cljs` for snapshot-range storage key ownership, decode and restore behavior, and any infrastructure-only helpers needed by startup.
- `/hyperopen/src/hyperopen/vaults/actions.cljs` as the stable facade that re-exports public vars and, where necessary, assembles dependency maps into application commands in the same pattern used by funding.

The exact file names above are prescriptive for this plan. If implementation discovers a better split, update this plan first, then make the code change.

## Plan of Work

### Milestone 1: Extract pure domain owners

Create `/hyperopen/src/hyperopen/vaults/domain/identity.cljs` and `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`. Move the pure vault-address normalization and transfer policy behavior out of `/hyperopen/src/hyperopen/vaults/actions.cljs` into these files. This includes `normalize-vault-address`, relationship child-address normalization, leader and deposit-eligibility helpers, transfer-mode normalization, `default-vault-transfer-modal-state`, localized USDC micros parsing, and `vault-transfer-preview`.

Keep the output contracts identical. `vault-transfer-preview` must still return the same `{:ok? ... :display-message ... :request ...}` shape that `/hyperopen/src/hyperopen/views/vaults/detail/transfer.cljs` expects. The action facade may still expose `vault-transfer-preview`, but only as an alias to the domain owner.

Acceptance for this milestone is that transfer-policy behavior can be tested without pulling in route loading, persistence, or pagination code.

### Milestone 2: Move route parsing and load orchestration into focused seams

Create `/hyperopen/src/hyperopen/vaults/infrastructure/routes.cljs` for browser path normalization and route parsing. Create `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs` for `load-vaults`, `load-vault-detail`, and `load-vault-route`, plus the existing projection-first ordering, benchmark fetch fanout, and component-vault history fanout.

The application loader should consume parsed route maps from the route boundary instead of parsing raw strings directly. Keep `/portfolio` prefetch behavior unchanged. Preserve the projection-before-heavy ordering already covered by current tests and required by `/hyperopen/docs/RELIABILITY.md`.

Acceptance for this milestone is that route classification and route-triggered effect assembly are independently testable, while the stable public action vars still return the same effect vectors.

### Milestone 3: Split interaction commands into bounded application modules

Create `/hyperopen/src/hyperopen/vaults/application/list_commands.cljs`, `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs`, and `/hyperopen/src/hyperopen/vaults/application/transfer_commands.cljs`. Move list, detail, and transfer interaction actions out of the monolith into these files.

`list_commands.cljs` should own search-query updates, filter toggles, sort toggles, snapshot-range updates, user-page-size changes, and page navigation. `detail_commands.cljs` should own detail tabs, activity sorts and filters, returns benchmark chip or search behavior, and chart-hover index math. `transfer_commands.cljs` should own modal open, close, field mutation, keyboard handling, withdraw-all toggling, and submit orchestration while delegating all business validation to `transfer_policy.cljs`.

If command modules need infrastructure-owned collaborators such as a persistence effect builder, inject them through a small dependency map from the facade instead of hard-coding browser-specific details into application code. That keeps dependency direction aligned with `/hyperopen/ARCHITECTURE.md`.

Acceptance for this milestone is that list, detail, and transfer actions each have one clear reason to change and fit under the architecture size limit without broad catch-all helpers.

### Milestone 4: Move persistence and startup restore into infrastructure

Create `/hyperopen/src/hyperopen/vaults/infrastructure/persistence.cljs` as the canonical owner of the snapshot-range storage key, decode logic, and `restore-vaults-snapshot-range!`. Update `/hyperopen/src/hyperopen/app/startup.cljs` to depend on that infrastructure owner directly instead of routing through the general actions facade.

Preserve the current startup sequence in `/hyperopen/src/hyperopen/startup/init.cljs`: restore during persisted UI bootstrap and then re-run after router initialization. Do not change startup ordering in this milestone. The goal is ownership cleanup, not startup behavior change.

Acceptance for this milestone is that no browser-storage read or store mutation remains in `/hyperopen/src/hyperopen/vaults/actions.cljs`, and startup tests still prove idempotent restore behavior.

### Milestone 5: Reduce the facade, update callers, and validate

Rewrite `/hyperopen/src/hyperopen/vaults/actions.cljs` into a compatibility facade. Public vars should `def` alias the focused implementations. Tiny dependency-assembly helpers are acceptable, but the file should no longer own the algorithms themselves. Internal callers that only need one concern should depend on the real owner modules where that improves clarity, but compatibility assemblies such as runtime registration may continue using the facade intentionally.

Update tests so the new boundaries are explicit. Move pure domain assertions into domain tests, route loading assertions into application or infrastructure tests, persistence restore assertions into infrastructure tests, and keep `/hyperopen/test/hyperopen/vaults/actions_test.cljs` only for stable facade contract coverage. Finish by running the required validation gates and record the results in this plan.

Acceptance for this milestone is that the facade is materially smaller, the new boundaries are protected by direct tests, and all required gates pass.

## Concrete Steps

All commands and edits should be run from `/hyperopen`.

1. Capture the current baseline and direct dependencies before editing:

    wc -l src/hyperopen/vaults/actions.cljs src/hyperopen/funding/actions.cljs src/hyperopen/funding/application/modal_actions.cljs
    rg -n "parse-vault-route|vault-transfer-preview|load-vault-route|restore-vaults-snapshot-range!" src/hyperopen/vaults/actions.cljs
    rg -n "vaults.actions|restore-vaults-snapshot-range|load-vault-route|vault-transfer-preview" src test

   Expected observation: `/hyperopen/src/hyperopen/vaults/actions.cljs` is much larger than the funding facade and is directly referenced by startup, runtime, views, and tests.

2. Create the new domain, application, and infrastructure files listed in `Context and Orientation`. Start by moving pure helpers into domain modules, then route parsing and route loading, then interaction commands, and finally persistence restore.

3. Rewrite `/hyperopen/src/hyperopen/vaults/actions.cljs` into a facade. Preserve existing public function names so `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs` and existing callers do not need action-id changes.

4. Update direct internal callers where it improves boundary clarity:

   - `/hyperopen/src/hyperopen/app/startup.cljs` should depend on `/hyperopen/src/hyperopen/vaults/infrastructure/persistence.cljs` for restore ownership.
   - `/hyperopen/src/hyperopen/views/vaults/detail/transfer.cljs` may continue to call `hyperopen.vaults.actions/vault-transfer-preview` if the facade simply aliases the domain function.
   - `/hyperopen/src/hyperopen/runtime/action_adapters.cljs` and `/hyperopen/src/hyperopen/runtime/collaborators.cljs` can stay on the facade to preserve the runtime catalog seam.

5. Add or split tests into focused files and regenerate the test runner if this repository requires it for new namespaces.

6. Run the required validation gates:

    npm run check
    npm test
    npm run test:websocket

   Expected outcome: all commands exit with code `0`. If a gate fails, record the blocker here and in the relevant `bd` child task before stopping.

## Validation and Acceptance

This refactor is accepted when all of the following are true:

- `/hyperopen/src/hyperopen/vaults/actions.cljs` is a compatibility facade rather than the implementation home for route parsing, policy, orchestration, and persistence.
- Route parsing lives in `/hyperopen/src/hyperopen/vaults/infrastructure/routes.cljs`.
- Snapshot-range restore lives in `/hyperopen/src/hyperopen/vaults/infrastructure/persistence.cljs`, and `/hyperopen/src/hyperopen/app/startup.cljs` depends on that owner directly.
- Transfer preview and transfer eligibility rules live in `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`.
- List, detail, transfer, and route-loading behavior live in focused application modules under `/hyperopen/src/hyperopen/vaults/application/`.
- Public action ids and public vars remain stable; runtime registration and navigation behavior do not change.
- `/portfolio` still triggers vault list hydration, `/vaults/:address` still triggers detail hydration and benchmark fanout, and transfer submit still emits the same request shape.
- New boundary-specific tests exist, and `npm run check`, `npm test`, and `npm run test:websocket` all pass.

## Idempotence and Recovery

This refactor should be landed additively first and subtractively second. New owner modules can be created and called through the facade before the old implementations are deleted. That makes the work safe to repeat and keeps rollback simple.

If one extraction slice fails halfway, point the facade back at the old local implementation for that concern, get the build green, then resume the move. Do not change action ids, effect keywords, or request payload shapes during recovery. Architectural cleanup is the goal; behavioral drift is not.

If implementation finds a tighter split than the file plan above, update this ExecPlan first so the next contributor does not have to reverse-engineer why the target changed.

## Artifacts and Notes

Baseline evidence gathered before implementation:

    /hyperopen/src/hyperopen/vaults/actions.cljs is 912 LOC.
    /hyperopen/src/hyperopen/funding/actions.cljs is 30 LOC.
    /hyperopen/src/hyperopen/funding/application/modal_actions.cljs is 230 LOC.

Final structure after implementation:

    /hyperopen/src/hyperopen/vaults/actions.cljs                        -> 131 LOC
    /hyperopen/src/hyperopen/vaults/domain/identity.cljs               -> 58 LOC
    /hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs        -> 113 LOC
    /hyperopen/src/hyperopen/vaults/domain/ui_state.cljs               -> 195 LOC
    /hyperopen/src/hyperopen/vaults/application/detail_commands.cljs   -> 265 LOC
    /hyperopen/src/hyperopen/vaults/application/list_commands.cljs     -> 98 LOC
    /hyperopen/src/hyperopen/vaults/application/route_loading.cljs     -> 79 LOC
    /hyperopen/src/hyperopen/vaults/application/transfer_commands.cljs -> 82 LOC
    /hyperopen/src/hyperopen/vaults/infrastructure/persistence.cljs    -> 21 LOC
    /hyperopen/src/hyperopen/vaults/infrastructure/routes.cljs         -> 41 LOC

Validation transcripts:

    npm test
    ...
    Ran 1982 tests containing 10162 assertions.
    0 failures, 0 errors.

    npm run check
    ...
    [:app] Build completed. (456 files, 455 compiled, 0 warnings, 8.62s)
    [:portfolio-worker] Build completed. (58 files, 57 compiled, 0 warnings, 3.93s)
    [:test] Build completed. (752 files, 4 compiled, 0 warnings, 4.25s)

    npm run test:websocket
    ...
    Ran 333 tests containing 1840 assertions.
    0 failures, 0 errors.

Hotspot map:

    line 151  -> parse-vault-route
    line 494  -> vault-transfer-preview
    line 561  -> load-vaults
    line 565  -> load-vault-detail
    line 596  -> load-vault-route
    line 643  -> restore-vaults-snapshot-range!

Important direct consumers before the refactor:

    /hyperopen/src/hyperopen/app/startup.cljs
    /hyperopen/src/hyperopen/startup/init.cljs
    /hyperopen/src/hyperopen/startup/runtime.cljs
    /hyperopen/src/hyperopen/runtime/action_adapters.cljs
    /hyperopen/src/hyperopen/runtime/collaborators.cljs
    /hyperopen/src/hyperopen/views/vaults/detail/transfer.cljs
    /hyperopen/test/hyperopen/vaults/actions_test.cljs
    /hyperopen/test/hyperopen/startup/init_test.cljs
    /hyperopen/test/hyperopen/runtime/action_adapters_test.cljs

## Interfaces and Dependencies

The following interfaces must exist at the end of the refactor.

In `/hyperopen/src/hyperopen/vaults/domain/identity.cljs`, define pure helpers for canonical vault identity:

- `normalize-vault-address`
- `relationship-child-addresses`
- `vault-leader-address`
- `vault-entity-name`

In `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, define:

- `default-vault-transfer-modal-state`
- `normalize-vault-transfer-mode`
- `parse-usdc-micros`
- `vault-transfer-deposit-allowed?`
- `vault-transfer-preview`

In `/hyperopen/src/hyperopen/vaults/infrastructure/routes.cljs`, define:

- `normalize-vault-route-path`
- `parse-vault-route`

In `/hyperopen/src/hyperopen/vaults/infrastructure/persistence.cljs`, define:

- `restore-vaults-snapshot-range!`
- any private storage-key or decode helpers needed to keep `localStorage` details out of application code

In `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs`, define:

- `load-vaults`
- `load-vault-detail`
- `load-vault-route`

In `/hyperopen/src/hyperopen/vaults/application/list_commands.cljs`, define the current list interaction surface:

- `set-vaults-search-query`
- `toggle-vaults-filter`
- `set-vaults-snapshot-range`
- `set-vaults-sort`
- `set-vaults-user-page-size`
- `toggle-vaults-user-page-size-dropdown`
- `close-vaults-user-page-size-dropdown`
- `set-vaults-user-page`
- `next-vaults-user-page`
- `prev-vaults-user-page`

In `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs`, define the current detail interaction surface:

- `set-vault-detail-tab`
- `set-vault-detail-activity-tab`
- `sort-vault-detail-activity`
- `toggle-vault-detail-activity-filter-open`
- `close-vault-detail-activity-filter`
- `set-vault-detail-activity-direction-filter`
- `set-vault-detail-chart-series`
- `set-vault-detail-returns-benchmark-search`
- `set-vault-detail-returns-benchmark-suggestions-open`
- `select-vault-detail-returns-benchmark`
- `remove-vault-detail-returns-benchmark`
- `handle-vault-detail-returns-benchmark-search-keydown`
- `clear-vault-detail-returns-benchmark`
- `set-vault-detail-chart-hover`
- `clear-vault-detail-chart-hover`

In `/hyperopen/src/hyperopen/vaults/application/transfer_commands.cljs`, define:

- `open-vault-transfer-modal`
- `close-vault-transfer-modal`
- `handle-vault-transfer-modal-keydown`
- `set-vault-transfer-amount`
- `set-vault-transfer-withdraw-all`
- `submit-vault-transfer`

In `/hyperopen/src/hyperopen/vaults/actions.cljs`, keep the stable public vars by aliasing the focused implementations above.

Plan revision note: 2026-03-07 01:08Z - Initial ExecPlan created after auditing the monolithic vault action namespace, comparing it against the funding facade pattern, and creating `bd` epic `hyperopen-a6q` with five child tasks.
Plan revision note: 2026-03-07 01:34Z - Updated the living sections with the implemented module split, added the `domain/ui_state.cljs` decision, recorded the `npm ci` environment fix, and captured passing validation evidence before moving this plan to `/hyperopen/docs/exec-plans/completed/`.
