# Portfolio Performance Metrics Irregular-Cadence Hardening

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, `/portfolio` performance metrics will remain mathematically defensible when Hyperliquid history arrives at irregular intervals instead of true daily cadence. The user-visible outcome is that cadence-sensitive rows stop emitting misleading annualized values and instead show either stable calendar-time estimates or deterministic `"--"` fallback when the data cannot support the metric.

A contributor will be able to verify this by loading `/portfolio` with sparse `all-time` data and seeing that annualized drift/risk rows are stable, while path-dependent and fixed-horizon rows are suppressed or caveated under poor cadence. The same contributor will then run repository gates and cadence-distortion tests to confirm the new metrics are less sensitive to sampling gaps than the current implementation.

## Progress

- [x] (2026-02-27 16:43Z) Reviewed `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md` planning requirements.
- [x] (2026-02-27 16:43Z) Audited current metrics implementation in `/hyperopen/src/hyperopen/portfolio/metrics.cljs`, VM wiring in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`, and rendering fallback behavior in `/hyperopen/src/hyperopen/views/portfolio_view.cljs`.
- [x] (2026-02-27 16:43Z) Reviewed existing parity artifacts and tests in `/hyperopen/docs/qa/performance-metrics-quantstats-parity-report-2026-02-26.md` and `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs`.
- [x] (2026-02-27 16:43Z) Captured researcher recommendations and selected implementation strategy for irregular-cadence robustness.
- [x] (2026-02-27 16:43Z) Authored this ExecPlan with milestones, acceptance criteria, gating policy, and validation approach.
- [x] (2026-02-27 16:52Z) Reconciled the follow-up recommendation set against the first research output and produced a metric-by-metric companion spec at `/hyperopen/docs/exec-plans/active/artifacts/2026-02-27-performance-metrics-irregular-cadence-metric-spec.md`.
- [x] (2026-02-27 17:05Z) Implemented irregular-interval metric primitives, cadence diagnostics, and quality-gate evaluation in `/hyperopen/src/hyperopen/portfolio/metrics.cljs`.
- [x] (2026-02-27 17:05Z) Integrated hybrid metric policy (irregular-core + daily-gated metrics) and propagated metric status/reason metadata through `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`.
- [x] (2026-02-27 17:05Z) Expanded tests for gating/fallback behavior in `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`.
- [x] (2026-02-27 17:05Z) Ran required gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-27 17:05Z) Published QA validation notes at `/hyperopen/docs/qa/performance-metrics-irregular-cadence-validation-2026-02-27.md`.

## Surprises & Discoveries

- Observation: The current portfolio metrics path always normalizes to daily rows before metric computation, then annualizes using a fixed periods-per-year constant (`252` from VM configuration), even when source observations are sparse and irregular.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` (`performance-periods-per-year`, `daily-compounded-returns`, `compute-performance-metrics`) and `/hyperopen/src/hyperopen/portfolio/metrics.cljs` (`cagr`, `volatility`, `sharpe`, `sortino`).

- Observation: Existing parity work intentionally matched QuantStats report semantics, which assume regular-frequency return vectors. That parity does not guarantee correctness on irregularly sampled all-time Hyperliquid history.
  Evidence: `/hyperopen/docs/qa/performance-metrics-quantstats-parity-report-2026-02-26.md` and `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs` parity fixtures.

- Observation: The system already has a flow-adjusted cumulative return source (`returns-history-rows`) at irregular event timestamps, so implementing Δt-aware metrics does not require new network calls.
  Evidence: `/hyperopen/src/hyperopen/portfolio/metrics.cljs` (`returns-history-rows`) and related flow-adjustment tests in `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs`.

- Observation: Drawdown-family and fixed-horizon distribution metrics can look numerically valid while being structurally unidentifiable under sparse cadence.
  Evidence: All-time cadence stats provided in research context (multi-day median gaps) and current max drawdown / Calmar / Omega / Gain-Pain formulas in `/hyperopen/src/hyperopen/portfolio/metrics.cljs`.

- Observation: The follow-up recommendation contains stronger formula details and edge-case policies, but its “rewrite every metric in direct irregular mode” direction conflicts with identifiability limits for fixed-horizon metrics.
  Evidence: Follow-up text recommends Option 1 as primary and provides irregular formulations for Omega/GPR/smart metrics, while first research output explicitly classifies these as daily-horizon or suppressible under sparse cadence.

## Decision Log

- Decision: Use a hybrid strategy: direct irregular-time formulas for identifiable annualized metrics, daily-horizon formulas only when daily coverage gates pass.
  Rationale: This maximizes correctness without fabricating dense paths from sparse all-time observations.
  Date/Author: 2026-02-27 / Codex

- Decision: Treat elapsed calendar time as the annualization basis for irregular-time CAGR, volatility, Sharpe, and Sortino, and stop inferring annualization from assumed daily row counts.
  Rationale: Annualization by nominal periods is cadence-sensitive and is the root cause of unstable all-time metrics.
  Date/Author: 2026-02-27 / Codex

- Decision: Suppress non-identifiable metrics under sparse cadence instead of emitting extrapolated values.
  Rationale: Hidden rows with explicit fallback are safer than plausible-looking but mathematically invalid numbers.
  Date/Author: 2026-02-27 / Codex

- Decision: Preserve existing row keys and presentation fallback contract (`nil` -> `"--"`) while adding metric diagnostics metadata for traceability.
  Rationale: Minimizes UI break risk and maintains deterministic rendering behavior.
  Date/Author: 2026-02-27 / Codex

- Decision: Keep all computations pure in metric and VM layers and avoid introducing side effects or runtime ownership changes.
  Rationale: Aligns with repository reliability invariants and keeps this refactor low-risk.
  Date/Author: 2026-02-27 / Codex

- Decision: Adopt the follow-up recommendation’s detailed formulas, gate taxonomy, and edge-case rules as implementation detail, but keep the first recommendation’s hybrid identifiability policy.
  Rationale: This preserves mathematical defensibility for sparse all-time cadence while adding the missing metric-by-metric execution detail needed for implementation.
  Date/Author: 2026-02-27 / Codex

- Decision: Create and maintain a separate metric-by-metric companion spec and treat it as the execution contract for all metric keys.
  Rationale: The plan remains prose-first and milestone-driven, while the companion spec provides exact per-metric directives without ambiguity.
  Date/Author: 2026-02-27 / Codex

## Outcomes & Retrospective

Planning outcome: a complete implementation-ready strategy and companion metric-spec contract were established for irregular cadence handling.

Implementation outcome: complete for this scope. Metrics now use irregular interval core estimators with cadence gating, daily-horizon suppression policy, and deterministic status/reason metadata. VM integration and regression tests are updated, and required gates pass.

## Context and Orientation

Performance metrics are currently computed from daily-normalized rows in `/hyperopen/src/hyperopen/portfolio/metrics.cljs`, then wired into `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` and rendered in `/hyperopen/src/hyperopen/views/portfolio_view.cljs`. The core issue is that Hyperliquid portfolio data for long ranges is not guaranteed daily; all-time and perp-all-time can have multi-day to multi-week observation gaps. A daily-row annualization model becomes unstable in that environment.

The existing data path already provides flow-adjusted cumulative portfolio returns at real event timestamps through `returns-history-rows`. This is the correct starting point for Δt-aware metrics because it captures cash-flow-adjusted performance and preserves actual observation timing.

In this ExecPlan, “Δt-aware” means each return interval carries explicit elapsed time in years and formulas scale drift, volatility, risk-free rates, and thresholds by that elapsed time. “Daily-horizon metric” means a metric that is only well-defined for a fixed sampling horizon such as daily returns. “Suppressed metric” means returning `nil` and attaching a reason code so the UI keeps rendering deterministic fallback (`"--"`).

The metric-by-metric implementation contract lives in `/hyperopen/docs/exec-plans/active/artifacts/2026-02-27-performance-metrics-irregular-cadence-metric-spec.md`. Contributors should treat that file as the canonical formula and gating matrix for each metric key in `performance-metric-groups`.

## Plan of Work

### Milestone 0: Metric Spec Lock

Review and lock `/hyperopen/docs/exec-plans/active/artifacts/2026-02-27-performance-metrics-irregular-cadence-metric-spec.md` before code changes. This includes confirming formulas, gate thresholds, and sparse-data suppression behavior for every metric key in `performance-metric-groups`.

This milestone is complete when formula and gate decisions are fully represented in the companion spec and no remaining metric key has ambiguous implementation instructions.

### Milestone 1: Irregular Interval and Cadence-Diagnostics Primitives

Add pure helpers in `/hyperopen/src/hyperopen/portfolio/metrics.cljs` that transform flow-adjusted cumulative rows into irregular return intervals. Each interval needs start timestamp, end timestamp, year fraction (`dt-years`), simple return, and log return. This milestone also adds cadence diagnostics used for gating: interval count, total span, median gap, p95 gap, max gap, and daily-coverage estimates when bucketed.

This milestone is complete when tests prove interval construction is deterministic, ignores non-finite values, and reports consistent diagnostics for sparse, dense, and mixed fixtures.

### Milestone 2: Core Δt-Aware Metric Engine

Implement irregular-time formulas for metrics that are identifiable from sparse points: cumulative return, elapsed-time CAGR, annualized volatility (variance-rate estimator), Sharpe, Sortino, and expected daily/monthly/yearly derived from CAGR. Risk-free and MAR conversions use annual continuous log rates with per-interval scaling.

The implementation should compute drift and variance in calendar time, not by synthetic daily row count. Keep formulas pure and deterministic, and return `nil` on invalid denominators or insufficient intervals.

This milestone is complete when unit tests show cadence-invariant behavior under synthetic downsampling and when core metrics do not materially drift solely from observation frequency changes.

### Milestone 3: Metric Capability Policy and Gating

Add a metric trust policy layer that classifies rows as `:ok`, `:low-confidence`, or `:suppressed` using cadence diagnostics and minimum data gates. Use the thresholds defined in the companion spec unless implementation evidence requires refinement:

- Core annualized metrics (`cagr`, `volatility-ann`, `sharpe`): require span >= 30 days and interval count >= 10.
- Core high-confidence threshold: interval count >= 20, span >= 90 days, `cv-gap <= 1.5`, and `max-gap-days <= 30`.
- Sortino: require interval count >= 20 and at least 5 downside intervals relative to MAR.
- Daily-horizon metrics (`omega`, `gain-pain-ratio`, `gain-pain-1m`, `smart-sharpe`, `smart-sortino`, `prob-sharpe-ratio`): require daily coverage >= 0.90, max missing streak <= 3 days, and at least 60 daily points (252 for probabilistic Sharpe).
- Drawdown/Calmar: treat drawdown as observed-only under sparse cadence; suppress Calmar unless max gap <= 2 days and daily points >= 180.

The output contract must preserve current metric keys while adding diagnostics maps (for example `:metric-status` and `:quality`) that explain suppression reasons.

### Milestone 4: Windowing and Benchmark Integration Under Irregular Cadence

Replace row-count assumptions in rolling windows with timestamp-anchored slicing on irregular series. Window cutoffs should use absolute timestamps. Boundary interpolation and nearest-snap usage must follow the explicit limits in the companion spec; otherwise suppress the affected window metric.

Update `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` so both portfolio and benchmark metric columns consume the new irregular engine. Maintain existing benchmark selection semantics and keep the presentation model stable for `/hyperopen/src/hyperopen/views/portfolio_view.cljs`.

This milestone is complete when benchmark and portfolio rows both carry consistent values/status metadata and no existing selectors or tabs regress.

### Milestone 5: Test Suite Expansion and Stability Validation

Extend `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs` and add a dedicated irregular-cadence test module (for example `/hyperopen/test/hyperopen/portfolio/metrics_irregular_test.cljs`) with synthetic cadence-distortion fixtures:

- Start from a known daily ground-truth process.
- Subsample to weekly, biweekly, and variable-gap schedules.
- Compare old cadence-sensitive outputs vs new Δt-aware outputs.

Add VM/view tests to assert suppression/fallback behavior remains deterministic, benchmark columns tolerate suppressed metrics, and metric status metadata is wired correctly.

Acceptance thresholds for synthetic stability should be explicit:

- CAGR absolute error near zero across subsampling (numerical tolerance only).
- Volatility relative error within 10% when interval count >= 30.
- Sharpe relative error within 15% when interval count >= 30.
- Sortino stability only enforced when downside-count gate passes.

### Milestone 6: Documentation and Rollout Guardrails

Update relevant docs and QA artifacts to record the new semantics and caveat policy:

- `/hyperopen/docs/references/hyperliquid-portfolio-history-and-returns.md` for cadence caveats and metric identifiability.
- `/hyperopen/docs/qa/` with before/after stability evidence and gate rationale.

Keep this ExecPlan updated during implementation. On completion, move it to `/hyperopen/docs/exec-plans/completed/` with final outcomes and validation evidence.

## Concrete Steps

From `/Users//projects/hyperopen`:

1. Lock and keep synchronized the companion spec at `/hyperopen/docs/exec-plans/active/artifacts/2026-02-27-performance-metrics-irregular-cadence-metric-spec.md`.
2. Implement irregular-interval primitives, cadence diagnostics, and Δt-aware metric formulas in `/hyperopen/src/hyperopen/portfolio/metrics.cljs`.
3. Implement metric status/reason policy exactly as defined in the companion spec.
4. Update VM metric wiring in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` to use the irregular metric engine for portfolio and benchmark columns.
5. Keep view rendering contract in `/hyperopen/src/hyperopen/views/portfolio_view.cljs` stable (`nil` remains `"--"`), and optionally surface quality caveats if included in VM.
6. Add or update tests in:
   - `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs`
   - `/hyperopen/test/hyperopen/portfolio/metrics_irregular_test.cljs` (new)
   - `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
   - `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`
7. Run required gates:

   npm run check
   npm test
   npm run test:websocket

8. Publish QA notes under `/hyperopen/docs/qa/` with synthetic and real-series sensitivity comparisons.

## Validation and Acceptance

Acceptance is met when all conditions below are true.

1. Core annualized metrics on sparse all-time data are computed with elapsed-time Δt-aware formulas, not fixed daily-period assumptions.
2. Daily-horizon and path-dependent metrics are suppressed or caveated when cadence gates fail, with deterministic `nil` values that render as `"--"`.
3. Window metrics are sliced by timestamp and do not rely on row-count heuristics.
4. Risk-free and MAR conversions respect per-interval elapsed time.
5. New cadence-distortion tests demonstrate materially lower sampling-frequency sensitivity than the current implementation.
6. Existing portfolio view behavior remains deterministic, including benchmark columns and fallback rendering.
7. Required repository gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

Manual verification scenario:

- Load `/portfolio` with an account whose `all-time` history is sparse and `month` history is near-daily.
- Confirm all-time rows show stable core metrics and suppressed non-identifiable metrics.
- Switch to month-like dense range and confirm daily-horizon rows become available when coverage gates pass.

## Idempotence and Recovery

This migration is designed to be additive and reversible at the metric-engine boundary. If a milestone introduces regressions, keep the diagnostics layer and revert VM usage to the previous metric path while retaining new tests for comparison. No state schema migrations or destructive operations are required.

If acceptance thresholds are not met, do not relax gates silently. Record the observed failure in `Surprises & Discoveries`, update the threshold decision in `Decision Log`, and rerun validation.

## Artifacts and Notes

Primary implementation files expected to change during execution:

- `/hyperopen/src/hyperopen/portfolio/metrics.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
- `/hyperopen/src/hyperopen/views/portfolio_view.cljs` (only if caveat metadata is surfaced)
- `/hyperopen/test/hyperopen/portfolio/metrics_test.cljs`
- `/hyperopen/test/hyperopen/portfolio/metrics_irregular_test.cljs` (new)
- `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`

Reference inputs for this plan:

- Research recommendations supplied in this task (irregular cadence formulas, gating policy, validation outline).
- `/hyperopen/docs/exec-plans/active/artifacts/2026-02-27-performance-metrics-irregular-cadence-metric-spec.md`
- `/hyperopen/docs/references/hyperliquid-portfolio-history-and-returns.md`
- `/hyperopen/docs/qa/performance-metrics-quantstats-parity-report-2026-02-26.md`
- `/hyperopen/docs/qa/performance-metrics-quantstats-tests-coverage-2026-02-26.md`

## Interfaces and Dependencies

No new external dependencies are required.

The implementation should keep public-facing VM/view contracts stable while introducing internal irregular-time interfaces in `/hyperopen/src/hyperopen/portfolio/metrics.cljs`.

Expected internal interfaces at completion:

- A pure interval-construction helper that accepts cumulative rows and returns ordered intervals with elapsed-time metadata.
- A pure cadence-diagnostics helper that returns span and gap statistics used by gating.
- A pure irregular metric compute entry point that returns existing metric keys plus optional diagnostics/status metadata.
- A policy helper that decides whether each metric row is emitted, downgraded, or suppressed based on cadence diagnostics and data sufficiency.

The VM in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` remains the integration point that composes strategy and benchmark contexts and passes them into metrics computation. The view in `/hyperopen/src/hyperopen/views/portfolio_view.cljs` continues to render by existing row keys and fallback conventions.

Plan revision note: 2026-02-27 16:43Z - Initial ExecPlan created to address irregular Hyperliquid cadence with Δt-aware metrics, selective daily-horizon gating, and validation thresholds before code changes.
Plan revision note: 2026-02-27 16:52Z - Added reconciliation decisions against the follow-up recommendations and introduced a dedicated metric-by-metric companion spec for formula-level execution guidance.
Plan revision note: 2026-02-27 17:05Z - Marked implementation complete, updated progress/outcomes with executed code+test work, and attached QA validation evidence.
