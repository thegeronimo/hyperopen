# Vault Detail VM SOLID and DDD Refactor

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this refactor, `/vaults/<address>` will keep the same user-facing behavior, but the code will be split into explicit domain boundaries: adapters, activity read model, performance and benchmark read model, chart presentation model, transfer read model, and a thin view-model assembler. This reduces regression risk and makes new activity tabs, benchmark types, and chart variants extensible without editing parallel maps across one giant namespace.

A user can verify this by opening the vault detail page, switching activity tabs and direction filters, changing chart series/timeframe, selecting benchmark symbols (including vault benchmarks), and using the transfer modal with unchanged behavior while test suites verify stable outputs and corrected domain semantics.

## Progress

- [x] (2026-02-28 22:03Z) Audited `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` and confirmed multi-responsibility coupling across route parsing, payload adaptation, domain math, chart SVG generation, activity sorting/filtering, transfer modal read-model, and final assembly.
- [x] (2026-02-28 22:03Z) Audited coupling surfaces in `/hyperopen/src/hyperopen/vaults/actions.cljs`, `/hyperopen/src/hyperopen/views/vault_detail_view.cljs`, and vault detail tests to preserve existing action and rendering contracts.
- [x] (2026-02-28 22:03Z) Authored this ExecPlan in `/hyperopen/docs/exec-plans/active/2026-02-28-vault-detail-vm-ddd-solid-refactor.md`.
- [x] (2026-02-28 22:19Z) Synced this worktree to the latest local `main` commit (`4f9d56d`) and created implementation branch `codex/vault-detail-vm-ddd-solid-refactor`.
- [x] (2026-02-28 22:19Z) Implemented first semantic and determinism slice in `detail_vm`: removed benchmark global cache, added `now-ms` injection path for TWAP runtime calculations, and removed APR fallback from `:past-month-return`.
- [x] (2026-02-28 22:19Z) Added/updated tests in `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs` for month-return semantics and deterministic TWAP runtime behavior.
- [x] (2026-02-28 22:19Z) Added `/hyperopen/src/hyperopen/vaults/detail/types.cljs` and `/hyperopen/test/hyperopen/vaults/detail/types_test.cljs`; wired benchmark identity helpers into `detail_vm` and benchmark fetch filtering in `vaults/actions`.
- [x] (2026-02-28 22:19Z) Ran validation gates successfully: `npm test`, `npm run check`, `npm run test:websocket`.
- [x] (2026-02-28 22:32Z) Implemented Milestone 3 adapter extraction: added `/hyperopen/src/hyperopen/vaults/adapters/webdata.cljs`, added adapter tests, and routed vault detail activity derivation through adapter functions.
- [x] (2026-02-28 22:32Z) Implemented Milestone 4 activity model extraction: added `/hyperopen/src/hyperopen/vaults/detail/activity.cljs`, migrated sorting to stable column IDs with legacy label compatibility, and updated `vaults/actions`, `detail_vm`, and `vault_detail_view` to use activity configuration as single source.
- [x] (2026-02-28 22:32Z) Added activity/read-model regression tests and re-ran validation gates (`npm test`, `npm run check`, `npm run test:websocket`) after Milestone 3 and 4 changes.
- [x] (2026-03-01 02:30Z) Implemented Milestone 5 extraction: added `/hyperopen/src/hyperopen/vaults/detail/performance.cljs` and `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs`; wired detail VM to those modules for summary derivation, benchmark selector/alignment, cumulative rows, and performance metrics composition.
- [x] (2026-03-01 02:30Z) Implemented Milestone 6 chart extraction: added `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`, moved chart domain/normalization/path/hover logic out of `detail_vm`, and localized chart stroke/fill palette decisions to the chart presentation module.
- [x] (2026-03-01 02:30Z) Added chart presentation regression tests in `/hyperopen/test/hyperopen/views/vaults/detail/chart_test.cljs` and re-ran full validation gates (`npm test`, `npm run check`, `npm run test:websocket`) after Milestone 6 changes.
- [ ] Continue with Milestone 7 transfer read-model extraction and final thin-assembler conversion in `detail_vm`.

## Surprises & Discoveries

- Observation: The current detail VM is 2,195 lines and already contains multiple layers (adapter/domain/presentation) in one namespace.
  Evidence: `wc -l /hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` returned `2195`.

- Observation: `:past-month-return` currently falls back to APR, which is semantically ambiguous for a monthly return field.
  Evidence: `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` computes `month-return` via `(or (normalize-percent-value apr) (snapshot-value-by-range ...))`.

- Observation: Activity sorting contracts currently depend on display labels, and UI dispatches those labels directly as sorting identifiers.
  Evidence: `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` dispatches `[:actions/sort-vault-detail-activity tab label]`, while `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` stores sort defaults/accessors keyed by strings like `"Size"` and `"Time"`.

- Observation: Hidden impurity exists inside VM helpers through wall-clock reads and global caching.
  Evidence: `twap-running-times` calls `(.now js/Date)` and benchmark option derivation uses `defonce` atom cache in `eligible-vault-benchmark-rows-cache`.

- Observation: Remote fetch from `origin` is not available in this execution environment due SSH key permissions, but local main integration still worked via fast-forward to the already-synced local `main` worktree commit.
  Evidence: `git fetch origin` failed with `Permission denied (publickey)` while `git merge --ff-only 4f9d56d...` succeeded.

- Observation: It is possible to migrate activity sorting to stable IDs without breaking existing stored UI state by normalizing string labels at the action/read-model boundary.
  Evidence: `sort-vault-detail-activity` now normalizes both `"Size"` and `:size` through `hyperopen.vaults.detail.activity/normalize-sort-column`, and tests pass for legacy label input paths.

- Observation: `npm test -- <namespace>` in this repository currently runs the full generated suite and prints `Unknown arg: ...` for namespace filters.
  Evidence: Running `npm test -- hyperopen.views.vaults.detail.chart-test` produced unknown-arg output and then executed all tests.

## Decision Log

- Decision: Preserve public action IDs and top-level VM output keys consumed by `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` while refactoring internals.
  Rationale: This enables an incremental refactor with low rollout risk and avoids coupling the architectural cleanup to a view rewrite.
  Date/Author: 2026-02-28 / Codex

- Decision: Introduce canonical benchmark identity as a typed value object map (`{:kind :market :coin "BTC"}` or `{:kind :vault :address "0x..."}`) and keep boundary encode/decode helpers for compatibility with existing persisted/UI string forms.
  Rationale: Removes string-prefix parsing from domain logic while allowing gradual migration of state/action boundaries.
  Date/Author: 2026-02-28 / Codex

- Decision: Treat `:past-month-return` as monthly performance only; do not populate it from APR fallback.
  Rationale: APR and one-month realized return are different concepts; fallback conflation hides data-quality issues and can silently mislead users.
  Date/Author: 2026-02-28 / Codex

- Decision: Replace scattered activity tab maps with one canonical tab-config map that owns columns, sort defaults, direction-filter support, loading keys, and row builders.
  Rationale: This is the highest-leverage Open/Closed fix and minimizes drift when adding or changing tabs.
  Date/Author: 2026-02-28 / Codex

- Decision: Remove hidden state/time dependencies from domain helpers by passing `now-ms` explicitly and deleting global `defonce` caches from the VM path.
  Rationale: Deterministic read-model derivation is required for reliable tests and replay-safe behavior.
  Date/Author: 2026-02-28 / Codex

- Decision: Begin implementation with a compatibility-preserving semantic slice before full module extraction (APR/month-return separation, deterministic TWAP timing, cache removal).
  Rationale: This de-risks the larger refactor by locking behavior with tests and fixing known domain ambiguity early.
  Date/Author: 2026-02-28 / Codex

- Decision: Keep current table rendering layouts in `vault_detail_view.cljs` while moving header/sort semantics to activity column config maps delivered by the VM.
  Rationale: This achieves stable sort IDs and single-source column metadata in Milestone 4 without introducing unrelated visual/UI behavior churn.
  Date/Author: 2026-02-28 / Codex

- Decision: Keep benchmark selection/alignment in the domain benchmark module but move benchmark and strategy chart palette rules into the chart presentation module.
  Rationale: This preserves DDD boundaries where benchmark read-model logic remains domain-focused and all visual stroke/fill concerns are owned by presentation.
  Date/Author: 2026-03-01 / Codex

## Outcomes & Retrospective

Implementation is in progress. Completed slices include:

- semantic correction for `:past-month-return` to use monthly snapshot data only;
- deterministic `now-ms` injection path for TWAP runtime calculations;
- removal of hidden benchmark cache state;
- initial domain vocabulary extraction for benchmark IDs in `vaults/detail/types`;
- extraction of raw payload normalization into `vaults/adapters/webdata`;
- extraction of activity tab configuration and stable sort semantics into `vaults/detail/activity`.
- extraction of portfolio summary/returns/metrics composition and benchmark selector/alignment into `vaults/detail/performance` and `vaults/detail/benchmarks`;
- extraction of chart domain/normalization/path/hover logic into `views/vaults/detail/chart`.

All current repository gates passed after these changes (`npm test`, `npm run check`, `npm run test:websocket`). Remaining work is Milestone 7 transfer read-model extraction and the final thin-assembler conversion/cleanup in `detail_vm`.

## Context and Orientation

Current vault detail read-model ownership is concentrated in `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`. That namespace currently contains several distinct concerns:

- adapter logic that translates inconsistent backend/exchange payload shapes;
- domain computations for summary returns, benchmarks, and performance metrics;
- presentation computations for chart domains and SVG paths;
- activity tab configuration and sorting/filtering policies;
- transfer modal read-side assembly;
- final page view-model composition.

In this plan, an "adapter" means code that reads raw payload keys (for example `:szi`, `:entryPx`, `:historicalOrders`) and emits canonical internal rows. A "read model" means a pure data structure designed for rendering. A "thin assembler" means one orchestration function that composes already-computed read models and avoids embedding business or protocol translation rules.

Primary current integration points to keep stable:

- `/hyperopen/src/hyperopen/vaults/actions.cljs` (route parsing, UI actions, benchmark fetch effects, transfer actions).
- `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` (consumes `vault-detail-vm` output and dispatches actions).
- `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs`.
- `/hyperopen/test/hyperopen/views/vault_detail_view_test.cljs`.
- `/hyperopen/test/hyperopen/vaults/actions_test.cljs`.

Architecture constraints from `/hyperopen/ARCHITECTURE.md` and `/hyperopen/docs/RELIABILITY.md` apply directly: keep domain logic deterministic, isolate anti-corruption translation at boundaries, and avoid mixing domain decisions with infrastructure concerns.

## Plan of Work

### Milestone 1: Characterization and semantic lock-in

Add characterization tests before any structural extraction. Extend `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs` with fixtures that pin output shape and values for normal vaults, parent/child relationships, missing webdata, benchmark combinations, transfer modal states, loading/error combinations, and chart modes.

Add explicit semantic tests for the `APR` vs `past-month-return` contract and for unit conversion behavior. These tests should fail if monthly return is sourced from APR or if percent normalization uses magnitude heuristics without explicit unit knowledge.

Acceptance for this milestone is deterministic test coverage that fails on accidental semantic drift and protects later extractions.

### Milestone 2: Introduce vault-detail domain vocabulary module

Create `/hyperopen/src/hyperopen/vaults/detail/types.cljs` as the canonical vocabulary layer. Move and centralize value object helpers currently duplicated in VM/action code: normalized vault address, benchmark identity parsing/encoding, snapshot range key selection, direction keys, and explicit percent/unit conversion helpers.

Use explicit constructors and predicates instead of implicit string conventions. For benchmark identity, expose encode/decode helpers so existing action/UI state can remain backward-compatible during migration while domain code consumes typed IDs.

Add focused tests in `/hyperopen/test/hyperopen/vaults/detail/types_test.cljs` for valid/invalid addresses, benchmark ID roundtrips, and percent/unit conversions.

### Milestone 3: Extract anti-corruption adapter layer

Create `/hyperopen/src/hyperopen/vaults/adapters/webdata.cljs` and move all raw payload parsing there (`rows-from-source`, position/open-order/funding/order/ledger/twap normalization, and channel-wrapper handling). This module is the only place allowed to reference vendor-specific fields like `:szi`, `:entryPx`, `:limitPx`, `:cumFunding`, and mixed payload wrappers under `:data`.

Define canonical output row shapes with stable keys (for example namespaced or consistently normalized keys) used by downstream read models. Keep adapter functions pure and deterministic.

Add tests in `/hyperopen/test/hyperopen/vaults/adapters/webdata_test.cljs` covering API-shaped and websocket-shaped payload variants already observed in vault detail tests.

### Milestone 4: Extract activity read model and unify tab config

Create `/hyperopen/src/hyperopen/vaults/detail/activity.cljs` that owns:

- one `tab-config` map keyed by tab keyword;
- per-tab columns (`:id`, `:label`, accessor); 
- default sort by stable column IDs;
- direction-filter support flags;
- loading/error aggregation keys;
- canonical row builders that consume adapter outputs.

Migrate sorting to stable IDs (`:size`, `:time-ms`, `:vault-amount`) instead of display strings while keeping compatibility normalization for legacy stored label-based sort state in `vaults-ui`.

Update `/hyperopen/src/hyperopen/vaults/actions.cljs` and `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` so sort events dispatch column IDs, not labels, and preserve current UI labels from `tab-config`.

Add/adjust tests in:

- `/hyperopen/test/hyperopen/vaults/actions_test.cljs`
- `/hyperopen/test/hyperopen/views/vault_detail_view_test.cljs`
- `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs`
- `/hyperopen/test/hyperopen/vaults/detail/activity_test.cljs`

### Milestone 5: Extract performance and benchmark read models

Create `/hyperopen/src/hyperopen/vaults/detail/performance.cljs` for summary-window derivation, cumulative rows, performance metrics composition, and benchmark alignment orchestration. Create `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs` for benchmark selector options, benchmark ID handling, vault benchmark snapshot interpretation, and benchmark series preparation.

In this milestone, replace heuristic unit inference with explicit unit handling from the adapter/types layer. Resolve `past-month-return` semantics by deriving monthly return from monthly snapshots (or `nil` when unavailable), while exposing APR independently under `:apr`.

Remove VM-local global cache behavior for benchmark eligible rows; if memoization is needed, implement it at selector/subscription boundaries with explicit inputs.

### Milestone 6: Extract chart presentation model

Create `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs` for chart-domain math, point normalization, y-axis ticks, hover-state normalization, and SVG path generation (`line-path`, `area-path`).

The chart module must consume canonical series points from performance read-model outputs and must not parse raw payload structures. Keep area fill/stroke decisions localized to presentation concerns only.

Add tests in `/hyperopen/test/hyperopen/views/vaults/detail/chart_test.cljs` to cover degenerate domains, single-point path behavior, zero-baseline area splitting, and hover clamping.

### Milestone 7: Extract transfer read model and thin assembler

Create `/hyperopen/src/hyperopen/vaults/detail/transfer.cljs` for transfer modal read-side data (max deposit, lockup copy, preview model mapping, submit state labels). Keep command-side submit logic in actions unchanged.

Refactor `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` to a thin orchestrator that:

- parses route/context;
- computes `now-ms` once at the boundary and injects it into modules that need current-time context;
- delegates to activity/performance/chart/transfer modules;
- assembles the final VM map without raw payload parsing or heavy domain math.

Acceptance target is a substantial reduction in file size/complexity with no hidden mutable caches and no direct raw vendor field references in the VM assembler.

### Milestone 8: Integration validation and cleanup

Run targeted suites after each extraction milestone, then run full required gates. Update this ExecPlan sections (`Progress`, `Surprises & Discoveries`, `Decision Log`, `Outcomes & Retrospective`) with evidence and any course corrections.

When complete, move this file from `/hyperopen/docs/exec-plans/active/` to `/hyperopen/docs/exec-plans/completed/`.

## Concrete Steps

Run from repository root `/hyperopen`.

1. Establish safety net and semantic tests first.

    npm test -- hyperopen.views.vaults.detail-vm-test
    npm test -- hyperopen.views.vault-detail-view-test
    npm test -- hyperopen.vaults.actions-test

2. Implement Milestone 2 and Milestone 3 modules and tests.

    npm test -- hyperopen.vaults.detail.types-test
    npm test -- hyperopen.vaults.adapters.webdata-test
    npm test -- hyperopen.views.vaults.detail-vm-test

3. Implement Milestone 4 activity config migration and compatibility normalization.

    npm test -- hyperopen.vaults.actions-test
    npm test -- hyperopen.views.vault-detail-view-test
    npm test -- hyperopen.vaults.detail.activity-test

4. Implement Milestone 5 and Milestone 6 (performance/benchmark/chart) and run targeted suites.

    npm test -- hyperopen.vaults.detail.performance-test
    npm test -- hyperopen.vaults.detail.benchmarks-test
    npm test -- hyperopen.views.vaults.detail.chart-test
    npm test -- hyperopen.views.vaults.detail-vm-test

5. Implement Milestone 7 thin assembler and transfer read-model extraction.

    npm test -- hyperopen.views.vaults.detail-vm-test
    npm test -- hyperopen.views.vault-detail-view-test

6. Run full required gates.

    npm run check
    npm test
    npm run test:websocket

7. Perform a quick static boundary audit for impurity/protocol leakage.

    rg -n "Date\.now|defonce\s+\^:private\s+eligible-vault-benchmark-rows-cache|:szi|:entryPx|:limitPx|:cumFunding" src/hyperopen/views/vaults/detail_vm.cljs src/hyperopen/vaults/detail src/hyperopen/views/vaults/detail

Expected final result: required gates exit with status 0, characterization tests remain green, and VM boundary checks show impurity/protocol concerns moved to explicit modules.

## Validation and Acceptance

Acceptance requires all items below.

1. `vault-detail-vm` remains the entrypoint used by `/hyperopen/src/hyperopen/views/vault_detail_view.cljs`, but now acts as an orchestrator without embedded raw payload parsing and without global mutable caches.
2. Raw protocol fields (`:szi`, `:entryPx`, `:limitPx`, `:cumFunding`, nested `:data` wrappers) are handled in adapter modules, not in final VM assembly.
3. Activity tab behavior is owned by one canonical tab config map; adding a new tab requires editing one configuration area rather than multiple parallel maps.
4. Activity sort state uses stable column IDs; compatibility for legacy label-based stored sort values is preserved during migration.
5. Benchmark identity uses a typed domain value object inside domain/read-model code, with boundary encode/decode for UI persistence compatibility.
6. `:past-month-return` is no longer derived from APR fallback; APR remains independently exposed.
7. Time-dependent calculations use injected `now-ms` in domain helpers.
8. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

This refactor is intentionally incremental and compatibility-first. Each milestone is additive and can be rerun safely. If a milestone introduces regressions, recover by routing only the affected assembly path back through existing helper behavior while keeping new tests and extracted modules in place.

Do not perform destructive state migrations. During sort and benchmark-ID migrations, preserve compatibility decoding so existing `vaults-ui` state and persisted local values still render correctly.

## Artifacts and Notes

Planned new modules:

- `/hyperopen/src/hyperopen/vaults/detail/types.cljs`
- `/hyperopen/src/hyperopen/vaults/adapters/webdata.cljs`
- `/hyperopen/src/hyperopen/vaults/detail/activity.cljs`
- `/hyperopen/src/hyperopen/vaults/detail/performance.cljs`
- `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs`
- `/hyperopen/src/hyperopen/vaults/detail/transfer.cljs`
- `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`

Planned primary modifications:

- `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`
- `/hyperopen/src/hyperopen/vaults/actions.cljs`
- `/hyperopen/src/hyperopen/views/vault_detail_view.cljs`
- `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs`
- `/hyperopen/test/hyperopen/views/vault_detail_view_test.cljs`
- `/hyperopen/test/hyperopen/vaults/actions_test.cljs`

Planned new tests:

- `/hyperopen/test/hyperopen/vaults/detail/types_test.cljs`
- `/hyperopen/test/hyperopen/vaults/adapters/webdata_test.cljs`
- `/hyperopen/test/hyperopen/vaults/detail/activity_test.cljs`
- `/hyperopen/test/hyperopen/vaults/detail/performance_test.cljs`
- `/hyperopen/test/hyperopen/vaults/detail/benchmarks_test.cljs`
- `/hyperopen/test/hyperopen/views/vaults/detail/chart_test.cljs`

## Interfaces and Dependencies

Stable interfaces that must remain available after refactor:

- `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` exports `(vault-detail-vm state)`.
- `/hyperopen/src/hyperopen/vaults/actions.cljs` action IDs and call signatures used by views remain stable.
- `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` keeps existing dispatch and render contract.

New internal seams expected at completion:

- `hyperopen.vaults.detail.types`: typed value objects and conversion helpers.
- `hyperopen.vaults.adapters.webdata`: anti-corruption payload normalization.
- `hyperopen.vaults.detail.activity`: activity tab config, filtering, sorting, and row assembly.
- `hyperopen.vaults.detail.performance`: summary derivation and performance metric model.
- `hyperopen.vaults.detail.benchmarks`: benchmark option/select/alignment logic.
- `hyperopen.vaults.detail.transfer`: transfer read-model composition.
- `hyperopen.views.vaults.detail.chart`: chart presentation model and SVG path helpers.

No new external libraries are required.

Plan revision note: 2026-02-28 22:03Z - Initial ExecPlan authored from repository audit and DDD/SOLID refactor recommendations for vault detail view-model architecture.
Plan revision note: 2026-02-28 22:19Z - Updated progress, discoveries, decisions, and retrospective after starting implementation, adding typed benchmark vocabulary, and passing required validation gates.
Plan revision note: 2026-02-28 22:32Z - Updated living sections after completing Milestone 3 and Milestone 4 implementation slices and re-running full validation gates.
Plan revision note: 2026-03-01 02:30Z - Updated living sections after completing Milestone 5 and Milestone 6 extraction slices, adding chart presentation tests, and re-running full validation gates.
