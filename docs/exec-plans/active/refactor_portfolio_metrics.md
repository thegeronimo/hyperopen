# Refactor Portfolio Metrics to Enforce Complexity Bounds

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The `src/hyperopen/portfolio/metrics.cljs` namespace was 1,768 lines of code (LOC), severely violating the `ARCHITECTURE.md` requirement that namespaces must remain under 500 LOC. By decomposing this monolithic file into smaller, highly-cohesive, SRP-compliant namespaces, we have improved the maintainability of the codebase, reduced cognitive load for both human engineers and AI agents, and ensured strict compliance with architectural constraints. The resulting code exposes the exact same public API functions via a facade so that downstream consumers remain completely unaffected. To further align with AI-agent best practices, the monolithic test file was also decomposed to perfectly mirror the new domain structure.

## Progress

- [x] (Completed) Create execution plan.
- [x] (Completed) Read and analyze `src/hyperopen/portfolio/metrics.cljs` to map out its logical sections.
- [x] (Completed) Create `src/hyperopen/portfolio/metrics/math.cljs` (Pure math functions).
- [x] (Completed) Create `src/hyperopen/portfolio/metrics/history.cljs` (Time-series deduplication and normalization).
- [x] (Completed) Create `src/hyperopen/portfolio/metrics/returns.cljs` (Interval math and risk-adjusted return stats).
- [x] (Completed) Create `src/hyperopen/portfolio/metrics/drawdown.cljs` (Max drawdown and series logic).
- [x] (Completed) Create `src/hyperopen/portfolio/metrics/distribution.cljs` (Win rate, Kelly criterion, value at risk).
- [x] (Completed) Create `src/hyperopen/portfolio/metrics/quality.cljs` (Cadence diagnostics and gating).
- [x] (Completed) Create `src/hyperopen/portfolio/metrics/builder.cljs` (Performance metrics composition context).
- [x] (Completed) Refactor `src/hyperopen/portfolio/metrics.cljs` to act as a pure facade.
- [x] (Completed) Decompose `test/hyperopen/portfolio/metrics_test.cljs` into `history_test`, `builder_test`, `quantstats_parity_test`, and `test_utils`.
- [x] (Completed) Ensure all decomposed tests pass without regressions.
- [x] (Completed) Verify codebase passes `npm run check`, `npm test`, and `npm run test:websocket`.

## Surprises & Discoveries

- Discovery: The dependency graph between the statistical methods in `metrics.cljs` was deep but acyclic, allowing for a clean separation into 7 sub-namespaces: `math`, `history`, `returns`, `drawdown`, `distribution`, `quality`, and `builder`.
- Optimization: The original plan assumed a division strictly between history, PNL, and margin. However, code reading revealed it was entirely mathematical performance metrics (no raw UI or layout code), making a strict DDD module grouping (by statistic type) significantly more natural and effective.
- Discovery: The test file contained integration-style "QuantStats Parity" tests that exercised the entire module boundary simultaneously. Grouping these into a dedicated `quantstats_parity_test.cljs` file preserved their integrity as boundary tests while still making the codebase easy to navigate.

## Decision Log

- Decision: Extract utility math like `quantile`, `mean`, `sample-stddev` to `math.cljs`.
  Rationale: Avoids cyclic dependencies for fundamental statistical helpers used by drawdowns, distributions, and core return logic.
  Date/Author: 2026-03-01 / Gemini CLI
- Decision: Re-write `metrics.cljs` to use explicitly listed `def` proxies to delegate all public members.
  Rationale: Preserves the exact public API contract allowing over 1500 assertions in the test suite to remain completely green without any modification to external code.
  Date/Author: 2026-03-01 / Gemini CLI
- Decision: Extract shared test fixtures into `test-utils.cljs`.
  Rationale: Prevents massive duplication of the complex mock return series arrays and timestamp helpers across the newly fragmented test namespaces.
  Date/Author: 2026-03-01 / Gemini CLI

## Outcomes & Retrospective

The refactor successfully disassembled the 1768 LOC "God" namespace into 7 distinct sub-modules, none of which exceed the 500 LOC architectural limit. The `metrics.cljs` file now serves simply as a facade interface. We then pushed this refactor a step further by breaking down the 467 LOC test file into 4 distinct, highly focused test modules. This completes the "Tests as Documentation" invariant by making it trivial for future engineers (or agents) to find exactly where historical data alignment is tested vs where drawdown logic is tested. The test suite, compilation step, and linter all completed with 0 errors and 0 warnings, verifying complete structural integrity.

## Context and Orientation

The current state of `src/hyperopen/portfolio/metrics.cljs` represents a "God namespace" for portfolio calculations. According to the `ARCHITECTURE.md`, pure domain decisions should live in the domain layer, and no namespace should exceed 500 LOC. This refactoring effort has applied the Single Responsibility Principle (SRP) to split the file by domain concept. 

Key files involved:
- `src/hyperopen/portfolio/metrics.cljs` (Facade)
- `src/hyperopen/portfolio/metrics/math.cljs` (Core statistical primitives)
- `src/hyperopen/portfolio/metrics/history.cljs` (Time-series alignment)
- `src/hyperopen/portfolio/metrics/returns.cljs` (CAGR, Sharpe, Sortino)
- `src/hyperopen/portfolio/metrics/distribution.cljs` (Win rate, Value-at-Risk)
- `src/hyperopen/portfolio/metrics/drawdown.cljs` (Drawdown series and calculation)
- `src/hyperopen/portfolio/metrics/quality.cljs` (Data density gating)
- `src/hyperopen/portfolio/metrics/builder.cljs` (Composite metrics pipeline)
- Test files have been symmetrically split in `test/hyperopen/portfolio/metrics/`.

## Validation and Acceptance

- `npm run check` completed with no errors.
- `npm test` completed, indicating the facade pattern successfully preserved the existing API contract.
- `npm run test:websocket` completed with no errors.