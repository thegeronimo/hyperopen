# Indicator SOLID/DDD Phase D Trend, Oscillator, and Kernel Bench Hardening

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

This phase further improves indicator cohesion and maintainability by extracting the remaining large non-moving-average trend cluster into semantic trend submodules, reducing duplicated low-level oscillator helper logic, and adding optional baseline trend tracking for kernel micro-bench tests. After this phase, trend and oscillator domains are cleaner and kernel performance drift is visible over time.

## Progress

- [x] (2026-02-15 18:59Z) Created active ExecPlan for Phase D.
- [x] (2026-02-15 19:01Z) Milestone 1 completed: extracted non-MA trend cluster into `/hyperopen/src/hyperopen/domain/trading/indicators/trend/{strength,clouds}.cljs` and rewired `trend.cljs` delegates.
- [x] (2026-02-15 19:02Z) Milestone 2 completed: introduced shared oscillator helper namespace at `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/helpers.cljs` and removed duplicated low-level helpers in `classic.cljs` and `structure.cljs`.
- [x] (2026-02-15 19:03Z) Milestone 3 completed: added baseline snapshot support via `/hyperopen/test/hyperopen/domain/trading/indicators/math_kernel_bench_baselines.cljs` and optional strict trend checks in `math_kernels_test.cljs`.
- [x] (2026-02-15 19:04Z) Milestone 4 completed: required validation gates passed (`npm run check`, `npm test`, `npm run test:websocket`).

## Surprises & Discoveries

- Observation: kernel benchmark times are substantially below initial baseline snapshots in this environment, indicating conservative starting baselines.
  Evidence: `npm test` logged `rolling-regression: elapsed=257ms (baseline=1200ms)`, `rolling-correlation: elapsed=297ms (baseline=1200ms)`, and `zigzag-pivots: elapsed=1ms (baseline=1000ms)`.
- Observation: strict benchmark gating is best kept opt-in to avoid non-deterministic failures across developer machines and CI resource classes.
  Evidence: baseline checks are always reported via console info/warn, while threshold assertions only activate when `HYPEROPEN_STRICT_KERNEL_BENCH=1`.

## Decision Log

- Decision: create a dedicated Phase D plan before implementation.
  Rationale: scope spans multiple semantic extractions plus test infrastructure; a standalone living plan is needed for resumability.
  Date/Author: 2026-02-15 / Codex
- Decision: split the trend non-MA cluster into two modules (`trend/strength` and `trend/clouds`) instead of one.
  Rationale: directional/trend-strength indicators and Ichimoku cloud logic are semantically distinct and easier to maintain independently.
  Date/Author: 2026-02-15 / Codex
- Decision: centralize oscillator shared helpers in one namespace consumed by both `classic` and `structure`.
  Rationale: duplicate wrappers and core routines (aligned rolling ops, RSI/ROC helpers, true range) created dual-maintenance risk.
  Date/Author: 2026-02-15 / Codex
- Decision: add baseline snapshots plus optional strict enforcement controlled by environment variable.
  Rationale: this enables trend tracking and optional hard-gating without making default test runs flaky.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

Phase D delivered the intended SOLID/DDD outcomes. The remaining non-moving-average trend cluster is now extracted into semantic trend modules, oscillator shared low-level helpers are centralized, and kernel micro-bench tests include baseline snapshot reporting with optional strict regression gating. Family-level public interfaces remain unchanged, and all required validation gates passed.

## Context and Orientation

Relevant files for this phase:

- `/hyperopen/src/hyperopen/domain/trading/indicators/trend.cljs` still contains non-MA trend calculators after Phase C extraction.
- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/classic.cljs` and `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/structure.cljs` share duplicated low-level helper wrappers and routines.
- `/hyperopen/test/hyperopen/domain/trading/indicators/math_kernels_test.cljs` has focused kernel tests and current micro-bench upper-bound checks.

## Plan of Work

Milestone 1 will split the remaining non-moving-average trend cluster into semantic submodules and keep `trend.cljs` as family composition plus residual calculators.

Milestone 2 will introduce a shared oscillator helper namespace for common low-level rolling/stat wrappers and routines used by both classic and structure oscillator modules.

Milestone 3 will add baseline snapshot metadata and optional trend-enforcement mechanics for kernel micro-bench tests so regressions can be tracked gradually without making default CI brittle.

Milestone 4 will run required repo validation gates and update this plan with concrete outcomes and evidence.

## Concrete Steps

From `/hyperopen`:

1. Create new trend submodules and migrate target calculators from `trend.cljs`.
2. Create oscillator helper namespace and rewrite classic/structure modules to consume it.
3. Add baseline snapshot support and optional trend checks in kernel micro-bench tests.
4. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
5. Update this plan and move to completed when all milestones are done.

## Validation and Acceptance

Acceptance criteria:

- Trend non-MA cluster is delegated from semantic trend submodules, with `trend.cljs` no longer implementing those migrated calculators.
- Duplicated low-level oscillator helper logic between classic/structure is removed and centralized.
- Kernel micro-bench tests include baseline snapshot comparison and optional strict trend enforcement mode.
- Required validation gates pass.

## Idempotence and Recovery

Changes are refactor/additive. If regressions occur, migrated calculators can be temporarily re-pointed in the family maps while keeping new modules in place for iterative recovery.

## Artifacts and Notes

Validation evidence from `/hyperopen`:

- `npm run check` passed with zero failures and zero compile warnings.
- `npm test` passed (`Ran 789 tests containing 3058 assertions. 0 failures, 0 errors.`) and emitted benchmark trend logs from `math_kernels_test.cljs`.
- `npm run test:websocket` passed (`Ran 86 tests containing 267 assertions. 0 failures, 0 errors.`).

## Interfaces and Dependencies

Public interfaces to preserve:

- `hyperopen.domain.trading.indicators.trend/get-trend-indicators`
- `hyperopen.domain.trading.indicators.trend/calculate-trend-indicator`
- `hyperopen.domain.trading.indicators.oscillators/get-oscillator-indicators`
- `hyperopen.domain.trading.indicators.oscillators/calculate-oscillator-indicator`

Plan revision note: 2026-02-15 18:59Z - Initial Phase D plan created for trend cluster extraction, oscillator helper consolidation, and kernel baseline tracking.
Plan revision note: 2026-02-15 19:02Z - Completed Milestones 1-3 with trend extraction, oscillator helper consolidation, and optional kernel trend tracking.
Plan revision note: 2026-02-15 19:04Z - Completed Milestone 4 and recorded validation evidence/outcomes.
