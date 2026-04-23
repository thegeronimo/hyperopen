# ADR 0027: Portfolio Optimizer Solver Selection

- Status: Accepted
- Date: 2026-04-23

## Context

Portfolio Optimizer V1 needs constrained mean-variance optimization for long-only and signed
cross-margin portfolios, efficient-frontier sweeps, target-return selection, target-volatility
selection, infeasibility diagnostics, and reruns inside a browser worker. The repo has no current
quadratic-programming solver or matrix stack.

The active ExecPlan requires a benchmark-backed decision before production engine work starts. The
solver must not bloat the main app bundle, must run off the main thread, and must avoid turning V1
into a custom numerical-methods project.

## Decision

Use a worker-isolated OSQP adapter as the first production solver path for V1, with a quadprog
adapter retained as a fallback and parity oracle during engine development.

Do not build a custom in-repo constrained QP solver for V1. The internal projected-gradient harness
may remain as a deterministic spike baseline only; it is not accepted as the production optimizer.

Target Volatility should be implemented through efficient-frontier sweep and point selection rather
than as a direct single QP objective. Minimum Variance and Target Return are direct constrained QP
solves. Max Sharpe is selected from the frontier or from repeated return-tilted QP solves.

## Benchmark Evidence

Benchmark harness:

    tools/optimizer/solver_spike_benchmark.mjs

Validation command:

    npm run test:optimizer-spike

Benchmark command used for the decision:

    node tools/optimizer/solver_spike_benchmark.mjs --external-root=/tmp/hyperopen-solver-spike.x2HSY7/packages --warmup=1 --runs=3

The benchmark generated deterministic 20-, 40-, and 60-instrument fixtures across Minimum Variance,
Max Sharpe, Target Return, and Target Volatility. Optional packages were unpacked outside the repo
for the spike, so this ADR does not add a runtime dependency by itself.

| Candidate | Package | Footprint | Solves | Mean | Max | Constraint max violation | Notes |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| OSQP | `osqp@0.0.2` | 137,705 bytes | 36/36 | 0.57 ms | 0.98 ms | 1.39e-17 | Fastest; requires `verbose: false`, tighter tolerances, and worker-only isolation. |
| quadprog | `quadprog@1.6.1` | 33,978 bytes | 36/36 | 14.59 ms | 63.24 ms | 1.39e-17 | Precise and small; dense JS path is slower but acceptable as fallback/parity. |
| projected-gradient-js | internal harness | 29,463 bytes | 36/36 | 47.78 ms | 208.85 ms | 0 | Useful baseline; not a production QP solver. |

Observed OSQP target-volatility error was higher than quadprog because the spike used a return-tilt
proxy for Target Volatility. That does not disqualify OSQP because production Target Volatility will
be frontier-driven, not a direct nonlinear target-volatility QP.

## Consequences

- Solver dependencies stay behind the optimizer worker and must not be imported from route views.
- The engine should expose a small solver protocol so OSQP and quadprog can be swapped in tests.
- Worker tests must include infeasible target-return fixtures and late-response handling before UI
  work relies on optimizer output.
- Production constraints must be encoded once in an application/domain layer before solver-specific
  conversion so OSQP and quadprog parity tests can share fixture expectations.
- Dependency review remains required before adding `osqp` to `package.json`, because the current npm
  wrapper is old, minified, and reports OSQP runtime version `0.0.0` even though the npm package is
  `0.0.2`.

## Implementation Rules

- Configure OSQP with `verbose: false`, tighter tolerances, and worker-only execution.
- Keep a quadprog adapter available for deterministic fixture parity and fallback investigation.
- Use committed solver fixtures for long-only caps, target return, infeasible bounds, gross/net
  signed exposure, turnover caps, and held-position locks before enabling execution preview.
- Treat solver infeasibility as structured data. Do not surface raw package messages directly in the
  UI without mapping them to affected controls and constraints.
- If OSQP browser bundling or dependency review fails, fall back to quadprog for V1 rather than
  building a custom solver.
