# Indicator SOLID/DDD Final Hardening

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The indicator subsystem has completed the large migration to semantic domain namespaces, but there are still architectural gaps that keep it from being fully SOLID/DDD aligned. After this plan is complete, domain marker logic will return semantic facts instead of chart-specific visual metadata, view adapters will own marker presentation mapping, and registry extension will be more open and data-driven so adding a new indicator family does not require modifying dispatch control flow. Users should observe no visible behavior regressions in the chart; the key win is safer extension and cleaner domain boundaries.

## Progress

- [x] (2026-02-15 17:17Z) Created active ExecPlan for final indicator SOLID/DDD hardening.
- [x] (2026-02-15 17:20Z) Milestone 1 completed: converted Williams Fractal markers to semantic marker facts in domain code and moved marker visualization mapping into the view adapter projection layer.
- [x] (2026-02-15 17:21Z) Milestone 2 completed: refactored registry orchestration to a data-driven family descriptor model and added additive `register-domain-family!` extension seam.
- [x] (2026-02-15 17:21Z) Milestone 3 completed: added focused domain regression tests for semantic structure markers and registry extension behavior.
- [x] (2026-02-15 17:22Z) Validated with required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-15 17:22Z) Moved this plan to `/hyperopen/docs/exec-plans/completed/` with final artifacts captured.

## Surprises & Discoveries

- Observation: Williams Fractal currently emits chart-specific marker visuals directly from domain code.
  Evidence: `/hyperopen/src/hyperopen/domain/trading/indicators/structure.cljs` currently builds markers with `:shape`, `:color`, and `:text`.
- Observation: Existing chart-level tests already encode expected fractal marker visuals, so adapter projection could be changed internally without altering user-visible behavior.
  Evidence: `indicators_test.cljs` assertions for Williams Fractal marker shape/color/text remained green after domain marker semanticization.
- Observation: Registry extension can be introduced without breaking existing APIs by preserving `get-domain-indicators` and `calculate-domain-indicator` signatures.
  Evidence: All existing indicator tests and runtime suites passed after replacing hardcoded `or` chain with family-descriptor iteration.

## Decision Log

- Decision: Start with marker semanticization before registry refactor.
  Rationale: It resolves the highest-impact domain-to-view leakage and gives a clear acceptance signal while preserving user-visible chart output.
  Date/Author: 2026-02-15 / Codex
- Decision: Keep adapter marker projection backward-compatible by passing through markers that do not have semantic `:kind`.
  Rationale: This supports incremental migration and avoids forcing simultaneous conversion of any future or legacy marker producers.
  Date/Author: 2026-02-15 / Codex
- Decision: Add `reset-registered-domain-families!` to support deterministic tests for dynamic family registration.
  Rationale: Extension seam tests must isolate state per test; explicit reset avoids flaky cross-test coupling.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

Milestones 1-3 were completed with no user-visible indicator regressions. Domain marker payloads are now semantic for Williams Fractal, marker draw metadata is owned by the view adapter, and the registry uses a descriptor-based extension seam instead of hardcoded branch chaining. The ExecPlan was moved to completed after all required validation gates passed.

## Context and Orientation

Relevant files and ownership:

- `/hyperopen/src/hyperopen/domain/trading/indicators/structure.cljs`: market-structure indicators, including Williams Fractal marker generation.
- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`: projection from domain indicator output into chart-ready series and markers; owns line/histogram style metadata.
- `/hyperopen/src/hyperopen/domain/trading/indicators/registry.cljs`: central domain orchestrator for indicator definitions and calculation dispatch across families.
- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicators.cljs`: chart-facing coordinator that calls the domain registry and adapter.
- `/hyperopen/src/hyperopen/domain/trading/indicators/contracts.cljs`: domain boundary validation for indicator input/output contracts.
- `/hyperopen/test/hyperopen/views/trading_chart/utils/indicators_test.cljs`: high-level parity tests that verify chart indicator output shape.
- `/hyperopen/test/test_runner.cljs`: test namespace registration.

Definitions used in this plan:

- Semantic marker fact: a domain-level marker representation that says what happened (for example `:kind :fractal-high`) without encoding how to draw it.
- Marker visualization metadata: chart-rendering fields such as marker shape, color, and placement. These belong in the view adapter.
- Data-driven registry seam: a registry that iterates a family descriptor collection instead of hardcoding control flow in an `or` chain.

## Plan of Work

Milestone 1 will update `structure.cljs` so `:williams-fractal` returns semantic markers (kind/time/price) and no chart styling fields. Then `indicator_view_adapter.cljs` will gain marker style mapping and marker projection logic that translates semantic markers into the exact chart marker payload expected by Lightweight Charts. Existing user-facing behavior must remain identical.

Milestone 2 will refactor `registry.cljs` to use explicit family descriptor data and helper functions for definition aggregation and dispatch. It will also expose an additive registration seam for extra families so extension can happen without editing dispatch branches.

Milestone 3 will add and update tests: a domain-level structure test for semantic marker output and a registry test for extension seam behavior. Existing chart-facing tests should continue to pass to prove no visible regression.

## Concrete Steps

From `/hyperopen`:

1. Implement Milestone 1 marker semanticization and adapter projection in:
   - `/hyperopen/src/hyperopen/domain/trading/indicators/structure.cljs`
   - `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`
   - `/hyperopen/src/hyperopen/domain/trading/indicators/contracts.cljs` (if marker contracts need tightening)
2. Implement Milestone 2 registry refactor in:
   - `/hyperopen/src/hyperopen/domain/trading/indicators/registry.cljs`
3. Implement Milestone 3 tests in:
   - `/hyperopen/test/hyperopen/domain/trading/indicators/structure_test.cljs` (new)
   - `/hyperopen/test/hyperopen/domain/trading/indicators/registry_test.cljs` (new)
   - `/hyperopen/test/test_runner.cljs`
4. Run required validation gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
5. Update this ExecPlan sections (`Progress`, `Surprises & Discoveries`, `Decision Log`, `Outcomes & Retrospective`) with completion notes and evidence.
6. Move the plan to completed when all acceptance criteria are met.

## Validation and Acceptance

Acceptance criteria:

- Williams Fractal domain output no longer hardcodes marker draw fields (`:shape`, `:color`, `:text`, `:position`) in `structure.cljs`.
- Chart output for Williams Fractal markers remains visually equivalent through adapter projection (validated by existing indicator view tests).
- Registry dispatch no longer relies on hardcoded branch chaining and supports additive family registration.
- New tests for structure semantic markers and registry extension pass.
- Required validation gates pass with zero failures.

## Idempotence and Recovery

All steps are additive refactors with existing test coverage as safety rails. If regression appears:

- Restore previous marker projection shape by temporarily passing through legacy markers in `indicator_view_adapter.cljs` while keeping semantic marker generation in place.
- Revert registry seam to prior behavior by routing through built-in family descriptors only.

No destructive data migrations or runtime state migrations are involved.

## Artifacts and Notes

Validation evidence:

- `npm test` -> `Ran 779 tests containing 2992 assertions. 0 failures, 0 errors.`
- `npm run check` -> lint/docs/compile checks passed with 0 failures.
- `npm run test:websocket` -> `Ran 86 tests containing 267 assertions. 0 failures, 0 errors.`

## Interfaces and Dependencies

Interfaces expected at completion:

- `hyperopen.domain.trading.indicators.registry/get-domain-indicators` remains stable.
- `hyperopen.domain.trading.indicators.registry/calculate-domain-indicator` remains stable.
- New additive registry API for family registration (name to be finalized during implementation).
- `hyperopen.views.trading-chart.utils.indicators/get-available-indicators` remains stable.
- `hyperopen.views.trading-chart.utils.indicators/calculate-indicator` remains stable.

Dependency rules preserved:

- Domain indicator modules remain pure calculation owners and emit semantic outputs.
- View adapter remains the owner of chart presentation metadata.

Plan revision note: 2026-02-15 17:17Z - Initial final-hardening plan created to execute remaining SOLID/DDD gaps (marker semanticization, registry extensibility, focused tests).
Plan revision note: 2026-02-15 17:22Z - Updated living sections after completing milestones 1-3 and full validation; remaining administrative step is moving plan to completed.
Plan revision note: 2026-02-15 17:22Z - Plan moved from active to completed after final validation and artifact capture.
