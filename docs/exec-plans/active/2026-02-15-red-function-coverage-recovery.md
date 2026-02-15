# Raise Red Function Coverage in Gateway and Indicator Families

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The current coverage report has multiple red function-coverage hotspots, which means regressions can slip through in high-risk logic paths without tests failing. After this work, the red hotspots in `api/gateway` and the indicator subfamilies (`flow`, `oscillators`, `trend`, `volatility`) will have direct behavioral tests that execute currently missed functions through stable public APIs. The observable outcome is a new coverage report where those function columns are no longer red and required quality gates still pass.

## Progress

- [x] (2026-02-15 23:12Z) Captured baseline red function-coverage hotspots from `coverage/index.html` and mapped them to concrete source files and missing function groups.
- [x] (2026-02-15 23:12Z) Authored active ExecPlan with milestones, acceptance targets, and validation commands.
- [x] (2026-02-15 23:18Z) Added reusable deterministic OHLCV fixtures in `/hyperopen/test/hyperopen/domain/trading/indicators/support.cljs`.
- [x] (2026-02-15 23:19Z) Added flow-family coverage tests executing all supported flow indicators.
- [x] (2026-02-15 23:19Z) Added oscillator-family coverage tests executing all supported oscillator indicators.
- [x] (2026-02-15 23:19Z) Added trend-family coverage tests executing all supported trend indicators.
- [x] (2026-02-15 23:19Z) Added volatility-family coverage tests executing all supported volatility indicators.
- [x] (2026-02-15 23:21Z) Expanded API gateway wrapper/compat coverage in `/hyperopen/test/hyperopen/api/gateway/market_test.cljs` and `/hyperopen/test/hyperopen/api/gateway/account_test.cljs`.
- [x] (2026-02-15 23:20Z) Registered new family coverage namespaces in `/hyperopen/test/test_runner.cljs`.
- [x] (2026-02-15 23:24Z) Ran required gates (`npm run check`, `npm test`, `npm run test:websocket`) and regenerated coverage (`npm run coverage`); target rows are no longer red.

## Surprises & Discoveries

- Observation: The repository-level red hotspots in the screenshot correspond to aggregate directory rows in `coverage/index.html`, not just single source files.
  Evidence: `coverage/index.html` reports function coverage at `41.93%` for `test/dev/out/cljs-runtime/hyperopen/api/gateway`, `20%` for `.../indicators/flow`, `24.52%` for `.../indicators/oscillators`, `34.14%` for `.../indicators/trend`, and `18.51%` for `.../indicators/volatility`.
- Observation: Existing indicator tests validate contracts/parity and selected UI indicator flows, but many calculators in red families are still not executed.
  Evidence: LCOV function misses include `flow/money.cljs` (`0/4`), `oscillators/momentum.cljs` (`0/9`), `oscillators/patterns.cljs` (`0/7`), `trend/moving_averages.cljs` (`5/28`), and `volatility/channels.cljs` (`0/10`).
- Observation: `test/test_runner.cljs` requires explicit namespace registration, so new tests will not execute unless the runner is updated.
  Evidence: The runner maintains a static require list and explicit `run-tests` symbol list.
- Observation: Executing all supported indicator IDs through family entrypoints was sufficient to drive every function in the four red indicator-family directories.
  Evidence: Coverage moved to `100%` function coverage for `flow`, `oscillators`, `trend`, and `volatility` rows after adding four family coverage test namespaces.

## Decision Log

- Decision: Raise coverage by driving public family and gateway entrypoints rather than unit-testing private helpers directly.
  Rationale: This preserves API-level behavior guarantees while still executing helper functions transitively, and avoids brittle tests coupled to private function names.
  Date/Author: 2026-02-15 / Codex
- Decision: Use table-driven tests with deterministic synthetic OHLCV fixtures.
  Rationale: Reduces duplication, keeps assertions consistent, and makes adding new indicator IDs straightforward if catalog coverage changes.
  Date/Author: 2026-02-15 / Codex
- Decision: Treat "no longer red" as the minimum acceptance threshold for each target module (function coverage >= 50%), with higher stretch thresholds documented but optional.
  Rationale: The user request is to focus on red function-coverage hotspots first; clearing red is the fastest measurable milestone.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

Implementation completed. All five targeted red rows are now non-red for function coverage, and required repository validation gates passed.

Before/after function coverage for targeted rows:

- `test/dev/out/cljs-runtime/hyperopen/api/gateway`: `13/31` (`41.93%`) -> `29/31` (`93.54%`)
- `test/dev/out/cljs-runtime/hyperopen/domain/trading/indicators/flow`: `2/10` (`20%`) -> `10/10` (`100%`)
- `test/dev/out/cljs-runtime/hyperopen/domain/trading/indicators/oscillators`: `13/53` (`24.52%`) -> `53/53` (`100%`)
- `test/dev/out/cljs-runtime/hyperopen/domain/trading/indicators/trend`: `14/41` (`34.14%`) -> `41/41` (`100%`)
- `test/dev/out/cljs-runtime/hyperopen/domain/trading/indicators/volatility`: `5/27` (`18.51%`) -> `27/27` (`100%`)

Validation status:

- `npm test`: pass (838 tests, 3758 assertions, 0 failures, 0 errors)
- `npm run test:websocket`: pass (86 tests, 267 assertions, 0 failures, 0 errors)
- `npm run check`: pass (all lint/docs/style checks and compiles green)
- `npm run coverage`: pass (overall functions: `1832/2463`, `74.38%`)

## Context and Orientation

Coverage is generated from compiled CLJS output paths under `test/dev/out/cljs-runtime/...`, but each hotspot maps to source namespaces under `/hyperopen/src/hyperopen/...` and tests under `/hyperopen/test/hyperopen/...`.

The red targets in this plan are:

- `test/dev/out/cljs-runtime/hyperopen/api/gateway` (function coverage `13/31`, `41.93%`)
- `test/dev/out/cljs-runtime/hyperopen/domain/trading/indicators/flow` (`2/10`, `20%`)
- `test/dev/out/cljs-runtime/hyperopen/domain/trading/indicators/oscillators` (`13/53`, `24.52%`)
- `test/dev/out/cljs-runtime/hyperopen/domain/trading/indicators/trend` (`14/41`, `34.14%`)
- `test/dev/out/cljs-runtime/hyperopen/domain/trading/indicators/volatility` (`5/27`, `18.51%`)

The highest-impact missing files inside those rows are:

- `/hyperopen/src/hyperopen/api/gateway/market.cljs` (function coverage `3/16`)
- `/hyperopen/src/hyperopen/domain/trading/indicators/flow/money.cljs` (`0/4`)
- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/momentum.cljs` (`0/9`)
- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/patterns.cljs` (`0/7`)
- `/hyperopen/src/hyperopen/domain/trading/indicators/trend/moving_averages.cljs` (`5/28`)
- `/hyperopen/src/hyperopen/domain/trading/indicators/volatility/channels.cljs` (`0/10`)

## Plan of Work

Milestone 1 establishes reusable test inputs and assertions for indicator families. Add deterministic OHLCV fixture helpers in a shared test namespace and helper assertions that verify common indicator-result invariants: non-nil result map, expected `:type`, expected pane (`:overlay` or `:separate`), at least one series for non-marker indicators, and sequence lengths matching input candle count where applicable.

Milestone 2 expands coverage for the red indicator families by adding dedicated family-level tests that execute currently unhit calculators through public APIs (`calculate-flow-indicator`, `calculate-oscillator-indicator`, `calculate-trend-indicator`, `calculate-volatility-indicator`). Use table-driven vectors of `[indicator-id params expected-pane]` and assert successful result shape for each case. Include one negative case per family (`:unknown-indicator`) to exercise fallback branches.

Milestone 3 expands API gateway tests, concentrating on wrappers and compatibility aliases in `market.cljs` and `account.cljs`. Add tests that verify delegation arguments and promise behavior for functions currently unexecuted, using `with-redefs` to stub endpoint/fetch-compat dependencies and to assert passthrough semantics without network or runtime side effects.

Milestone 4 wires new test namespaces into `test/test_runner.cljs`, runs required gates, regenerates coverage, and records before/after function percentages for the five target rows. If a target remains red, add follow-up tests only for the still-uncovered functions shown in LCOV rather than broad new suites.

## Concrete Steps

From `/hyperopen`:

1. Add shared fixture helpers in test scope, for example a namespace under `/hyperopen/test/hyperopen/domain/trading/indicators/` that exports deterministic candle generators and finite-number/result-shape assertions.
2. Add new family coverage test namespaces:
   - `/hyperopen/test/hyperopen/domain/trading/indicators/flow_family_coverage_test.cljs`
   - `/hyperopen/test/hyperopen/domain/trading/indicators/oscillators_family_coverage_test.cljs`
   - `/hyperopen/test/hyperopen/domain/trading/indicators/trend_family_coverage_test.cljs`
   - `/hyperopen/test/hyperopen/domain/trading/indicators/volatility_family_coverage_test.cljs`
3. Expand gateway tests in:
   - `/hyperopen/test/hyperopen/api/gateway/market_test.cljs`
   - `/hyperopen/test/hyperopen/api/gateway/account_test.cljs`
4. Update `/hyperopen/test/test_runner.cljs` to include the new namespaces in both the `:require` list and `run-tests` list.
5. Run validation commands:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
   - `npm run coverage`
6. Confirm coverage deltas by reading `coverage/index.html` for the five target rows.

Expected transcript shape:

  - `npm run check` completes with zero lint/doc/style failures and successful `shadow-cljs compile app/test`.
  - `npm test` completes with zero failures and zero errors.
  - `npm run test:websocket` completes with zero failures and zero errors.
  - `npm run coverage` regenerates `coverage/index.html` and function percentages for target rows increase to non-red levels.

## Validation and Acceptance

Acceptance is met when all of the following are true:

- Target rows in `coverage/index.html` are no longer red for function coverage (minimum function coverage >= 50%) for:
  - `test/dev/out/cljs-runtime/hyperopen/api/gateway`
  - `test/dev/out/cljs-runtime/hyperopen/domain/trading/indicators/flow`
  - `test/dev/out/cljs-runtime/hyperopen/domain/trading/indicators/oscillators`
  - `test/dev/out/cljs-runtime/hyperopen/domain/trading/indicators/trend`
  - `test/dev/out/cljs-runtime/hyperopen/domain/trading/indicators/volatility`
- Existing behavior-focused tests continue to pass; no production API signatures are changed.
- Required repository gates pass:
  - `npm run check`
  - `npm test`
  - `npm run test:websocket`

Stretch target (optional): each target row reaches >= 60% function coverage in the first pass.

## Idempotence and Recovery

All steps are additive and safe to rerun. Re-running test commands is idempotent. If a new test is flaky or over-constrained, keep the fixture/test namespace and relax only assertion strictness (for example, assert result shape and finite values instead of exact floating-point tails). If coverage remains red after first pass, use LCOV missing-function names to add narrow follow-up tests instead of rewriting existing suites.

## Artifacts and Notes

Baseline function-coverage snapshot (2026-02-15):

  - `api/gateway`: `13/31` (`41.93%`)
  - `indicators/flow`: `2/10` (`20%`)
  - `indicators/oscillators`: `13/53` (`24.52%`)
  - `indicators/trend`: `14/41` (`34.14%`)
  - `indicators/volatility`: `5/27` (`18.51%`)

Key uncovered function clusters to prioritize first:

  - `market.cljs` wrappers and compatibility aliases (`request-*`, `fetch-*`, `ensure-*`, `build-market-state` passthrough)
  - Flow money/volume calculators (`chaikin-*`, `ease-of-movement`, `elders-force-index`, `accumulation-distribution`, `price-volume-trend`, `net-volume`)
  - Oscillator momentum/pattern/structure calculators (`rate-of-change`, `momentum`, `trix`, `connors-rsi`, `klinger-oscillator`, `know-sure-thing`, `relative-vigor-index`, `chande-kroll-stop`, etc.)
  - Trend moving-average variants and helper-driven calculators (`alma`, `hull`, `mcginley`, `ma/ema cross`)
  - Volatility channels/dispersion/range calculators (`donchian`, `price-channel`, `historical-volatility`, `keltner`, `bollinger width/%B`, `atr`)

## Interfaces and Dependencies

No production interface changes are required for this plan. The implementation should use these existing public entrypoints:

- `/hyperopen/src/hyperopen/domain/trading/indicators/flow.cljs`: `calculate-flow-indicator`
- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators.cljs`: `calculate-oscillator-indicator`
- `/hyperopen/src/hyperopen/domain/trading/indicators/trend.cljs`: `calculate-trend-indicator`
- `/hyperopen/src/hyperopen/domain/trading/indicators/volatility.cljs`: `calculate-volatility-indicator`
- `/hyperopen/src/hyperopen/api/gateway/market.cljs` and `/hyperopen/src/hyperopen/api/gateway/account.cljs` wrapper functions under test

The test harness dependency remains the existing `cljs.test` runner in `/hyperopen/test/test_runner.cljs`; new test namespaces must be explicitly required and listed there.

Plan revision note: 2026-02-15 23:12Z - Initial plan created to raise red function-coverage hotspots in gateway and indicator family modules, with baseline metrics and acceptance thresholds.
Plan revision note: 2026-02-15 23:24Z - Updated living sections after implementing family/gateway coverage tests and validating post-change coverage and required gates.
