import assert from 'node:assert/strict';
import test from 'node:test';

import {
  benchmarkCandidates,
  buildBenchmarkProblems,
  candidateNames,
  projectCappedSimplex,
  solveWithProjectedGradient,
  summarizeProblemResult,
} from './solver_spike_benchmark.mjs';

test('buildBenchmarkProblems creates deterministic objective fixtures for target sizes', () => {
  const first = buildBenchmarkProblems({ sizes: [20, 40, 60], seed: 42 });
  const second = buildBenchmarkProblems({ sizes: [20, 40, 60], seed: 42 });

  assert.equal(first.length, 12);
  assert.deepEqual(
    first.map((problem) => [problem.size, problem.objective.kind]),
    [
      [20, 'minimum-variance'],
      [20, 'max-sharpe'],
      [20, 'target-return'],
      [20, 'target-volatility'],
      [40, 'minimum-variance'],
      [40, 'max-sharpe'],
      [40, 'target-return'],
      [40, 'target-volatility'],
      [60, 'minimum-variance'],
      [60, 'max-sharpe'],
      [60, 'target-return'],
      [60, 'target-volatility'],
    ],
  );
  assert.deepEqual(first, second);
  assert.equal(first[0].covariance.length, 20);
  assert.equal(first[8].covariance.length, 60);
});

test('projectCappedSimplex returns bounded weights that sum to one', () => {
  const projected = projectCappedSimplex([0.8, -0.2, 0.4, 0.1], {
    lower: [0, 0, 0, 0],
    upper: [0.45, 0.45, 0.45, 0.45],
  });

  assert.ok(Math.abs(projected.reduce((sum, value) => sum + value, 0) - 1) < 1e-10);
  assert.ok(projected.every((value) => value >= -1e-12));
  assert.ok(projected.every((value) => value <= 0.45 + 1e-12));
});

test('solveWithProjectedGradient produces a feasible signed result summary for fixture inputs', () => {
  const [problem] = buildBenchmarkProblems({ sizes: [20], seed: 7 });
  const result = solveWithProjectedGradient(problem);
  const summary = summarizeProblemResult(problem, result);

  assert.equal(result.status, 'solved');
  assert.ok(summary.sumError < 1e-8);
  assert.ok(summary.maxUpperViolation < 1e-8);
  assert.ok(summary.maxLowerViolation < 1e-8);
  assert.ok(Number.isFinite(summary.volatility));
  assert.ok(Number.isFinite(summary.expectedReturn));
});

test('benchmarkCandidates uses locally installed optional solver packages when present', async () => {
  const problems = buildBenchmarkProblems({ sizes: [20], seed: 3 });
  const result = await benchmarkCandidates({
    problems,
    candidates: ['projected-gradient-js', 'quadprog', 'osqp'],
    externalRoot: '/definitely/missing/solver/packages',
    warmupRuns: 1,
    measuredRuns: 1,
  });

  assert.deepEqual(candidateNames(result), ['projected-gradient-js', 'quadprog', 'osqp']);
  assert.equal(result.candidates['projected-gradient-js'].available, true);
  assert.equal(result.candidates.quadprog.available, true);
  assert.equal(result.candidates.quadprog.metadata.packageName, 'quadprog');
  assert.equal(result.candidates.osqp.available, true);
  assert.equal(result.candidates.osqp.metadata.packageName, 'osqp');
  assert.equal(result.problemCount, 4);
});
