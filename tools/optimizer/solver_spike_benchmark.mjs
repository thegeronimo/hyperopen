#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { performance } from 'node:perf_hooks';
import { fileURLToPath, pathToFileURL } from 'node:url';

const DEFAULT_SIZES = [20, 40, 60];
const DEFAULT_CANDIDATES = ['projected-gradient-js', 'quadprog', 'osqp'];
const SIMPLEX_TOLERANCE = 1e-10;

function makeRng(seed) {
  let state = seed >>> 0;
  return () => {
    state = (1664525 * state + 1013904223) >>> 0;
    return state / 0x100000000;
  };
}

function sum(values) {
  return values.reduce((acc, value) => acc + value, 0);
}

function dot(a, b) {
  let total = 0;
  for (let i = 0; i < a.length; i += 1) {
    total += a[i] * b[i];
  }
  return total;
}

function matVec(matrix, vector) {
  return matrix.map((row) => dot(row, vector));
}

function portfolioVariance(covariance, weights) {
  return Math.max(0, dot(weights, matVec(covariance, weights)));
}

function portfolioVolatility(covariance, weights) {
  return Math.sqrt(portfolioVariance(covariance, weights));
}

function portfolioReturn(expectedReturns, weights) {
  return dot(expectedReturns, weights);
}

function clamp(value, lower, upper) {
  return Math.min(upper, Math.max(lower, value));
}

function maxAbsRowSum(matrix) {
  let max = 0;
  for (const row of matrix) {
    max = Math.max(max, row.reduce((acc, value) => acc + Math.abs(value), 0));
  }
  return max;
}

function validateBounds(lower, upper) {
  const lowerSum = sum(lower);
  const upperSum = sum(upper);
  if (lower.length !== upper.length) {
    throw new Error('lower and upper bounds must have the same length');
  }
  if (lowerSum > 1 + SIMPLEX_TOLERANCE) {
    throw new Error(`lower bounds are infeasible: sum=${lowerSum}`);
  }
  if (upperSum < 1 - SIMPLEX_TOLERANCE) {
    throw new Error(`upper bounds are infeasible: sum=${upperSum}`);
  }
}

export function projectCappedSimplex(values, options = {}) {
  const lower = options.lower ?? values.map(() => 0);
  const upper = options.upper ?? values.map(() => 1);
  validateBounds(lower, upper);

  let low = Infinity;
  let high = -Infinity;
  for (let i = 0; i < values.length; i += 1) {
    low = Math.min(low, values[i] - upper[i]);
    high = Math.max(high, values[i] - lower[i]);
  }

  let theta = 0;
  for (let step = 0; step < 120; step += 1) {
    theta = (low + high) / 2;
    const projectedSum = values.reduce(
      (acc, value, index) => acc + clamp(value - theta, lower[index], upper[index]),
      0,
    );
    if (projectedSum > 1) {
      low = theta;
    } else {
      high = theta;
    }
  }

  const projected = values.map((value, index) => clamp(value - theta, lower[index], upper[index]));
  let residual = 1 - sum(projected);
  for (let i = 0; i < projected.length && Math.abs(residual) > SIMPLEX_TOLERANCE; i += 1) {
    const room =
      residual > 0
        ? Math.min(residual, upper[i] - projected[i])
        : Math.max(residual, lower[i] - projected[i]);
    projected[i] += room;
    residual -= room;
  }

  return projected;
}

function equalFeasibleWeights(lower, upper) {
  return projectCappedSimplex(lower.map((value, index) => (value + upper[index]) / 2), {
    lower,
    upper,
  });
}

function greedyBoundedPortfolio(expectedReturns, lower, upper, direction = 'max') {
  const weights = lower.slice();
  let remaining = 1 - sum(weights);
  const indexes = expectedReturns
    .map((value, index) => ({ index, value }))
    .sort((a, b) => (direction === 'max' ? b.value - a.value : a.value - b.value));

  for (const { index } of indexes) {
    if (remaining <= SIMPLEX_TOLERANCE) {
      break;
    }
    const allocation = Math.min(upper[index] - lower[index], remaining);
    weights[index] += allocation;
    remaining -= allocation;
  }

  return weights;
}

function buildCovariance(size, rng) {
  const factorCount = 4;
  const factorLoadings = Array.from({ length: size }, () =>
    Array.from({ length: factorCount }, () => (rng() - 0.5) * 0.16),
  );
  const idiosyncraticVariance = Array.from({ length: size }, () => {
    const annualVol = 0.12 + rng() * 0.38;
    return annualVol * annualVol;
  });

  return Array.from({ length: size }, (_, row) =>
    Array.from({ length: size }, (_, column) => {
      let value = row === column ? idiosyncraticVariance[row] : 0;
      for (let factor = 0; factor < factorCount; factor += 1) {
        value += factorLoadings[row][factor] * factorLoadings[column][factor];
      }
      return value;
    }),
  );
}

function buildInstrumentUniverse(size, rng) {
  return Array.from({ length: size }, (_, index) => {
    const perp = index % 3 !== 0;
    const fundingAnnualized = perp ? (rng() - 0.5) * 0.08 : 0;
    return {
      id: `${perp ? 'PERP' : 'SPOT'}-${String(index + 1).padStart(3, '0')}`,
      asset: `ASSET-${String(index + 1).padStart(3, '0')}`,
      instrumentType: perp ? 'perp' : 'spot',
      fundingAnnualized,
    };
  });
}

export function buildBenchmarkProblems(options = {}) {
  const sizes = options.sizes ?? DEFAULT_SIZES;
  const rng = makeRng(options.seed ?? 1729);
  const problems = [];

  for (const size of sizes) {
    const instruments = buildInstrumentUniverse(size, rng);
    const covariance = buildCovariance(size, rng);
    const rawExpectedReturns = instruments.map((instrument, index) => {
      const structuralReturn = 0.025 + rng() * 0.22;
      const momentumTilt = ((index % 7) - 3) * 0.006;
      return structuralReturn + momentumTilt + instrument.fundingAnnualized;
    });
    const lower = Array.from({ length: size }, () => 0);
    const cap = Math.min(0.35, Math.max(0.08, 2.75 / size));
    const upper = Array.from({ length: size }, () => cap);
    const currentWeights = equalFeasibleWeights(lower, upper);
    const equalReturn = portfolioReturn(rawExpectedReturns, currentWeights);
    const equalVolatility = portfolioVolatility(covariance, currentWeights);
    const maxReturnWeights = greedyBoundedPortfolio(rawExpectedReturns, lower, upper, 'max');
    const minReturnWeights = greedyBoundedPortfolio(rawExpectedReturns, lower, upper, 'min');
    const feasibleMaxReturn = portfolioReturn(rawExpectedReturns, maxReturnWeights);
    const feasibleMinReturn = portfolioReturn(rawExpectedReturns, minReturnWeights);
    const targetReturn =
      equalReturn + Math.max(0, feasibleMaxReturn - equalReturn) * 0.42;
    const targetVolatility = equalVolatility * 0.85;
    const baseProblem = {
      size,
      instruments,
      expectedReturns: rawExpectedReturns,
      covariance,
      constraints: {
        allowShort: false,
        lower,
        upper,
        maxGross: 1,
        netExposure: { min: 1, max: 1 },
        dustThreshold: 0.0005,
      },
      currentWeights,
      riskFreeRate: 0.025,
      generatedTargets: {
        equalReturn,
        equalVolatility,
        feasibleMinReturn,
        feasibleMaxReturn,
        targetReturn,
        targetVolatility,
      },
    };

    problems.push({
      ...baseProblem,
      id: `${size}-minimum-variance`,
      objective: { kind: 'minimum-variance' },
    });
    problems.push({
      ...baseProblem,
      id: `${size}-max-sharpe`,
      objective: { kind: 'max-sharpe' },
    });
    problems.push({
      ...baseProblem,
      id: `${size}-target-return`,
      objective: { kind: 'target-return', targetReturn },
    });
    problems.push({
      ...baseProblem,
      id: `${size}-target-volatility`,
      objective: { kind: 'target-volatility', targetVolatility },
    });
  }

  return problems;
}

function infeasibleResult(problem, reason, details = {}) {
  return {
    solver: 'projected-gradient-js',
    status: 'infeasible',
    reason,
    details,
    weights: [],
    iterations: 0,
    elapsedMs: 0,
  };
}

function objectiveValue(problem, weights, options = {}) {
  const variance = portfolioVariance(problem.covariance, weights);
  const expectedReturn = portfolioReturn(problem.expectedReturns, weights);
  const volatility = Math.sqrt(variance);
  const excessReturn = expectedReturn - (problem.riskFreeRate ?? 0);
  const sharpe = volatility > 0 ? excessReturn / volatility : -Infinity;

  if (options.kind === 'max-sharpe') {
    return -sharpe;
  }
  if (options.kind === 'target-volatility') {
    return Math.abs(volatility - options.targetVolatility) - expectedReturn * 0.01;
  }
  return 0.5 * variance - (options.returnTilt ?? 0) * expectedReturn;
}

function optimizeProjectedQuadratic(problem, options = {}) {
  const lower = problem.constraints.lower;
  const upper = problem.constraints.upper;
  let weights = options.initialWeights ?? equalFeasibleWeights(lower, upper);
  const covariance = problem.covariance;
  const expectedReturns = problem.expectedReturns;
  const maxIterations = options.maxIterations ?? 1200;
  const lipschitz = maxAbsRowSum(covariance) + (options.targetReturnPenalty ? 5 : 0) + 1e-9;
  const baseStep = options.stepSize ?? 0.75 / lipschitz;
  let previousValue = Infinity;
  let iterations = 0;

  for (; iterations < maxIterations; iterations += 1) {
    const gradient = matVec(covariance, weights);
    const returnTilt = options.returnTilt ?? 0;
    if (returnTilt !== 0) {
      for (let i = 0; i < gradient.length; i += 1) {
        gradient[i] -= returnTilt * expectedReturns[i];
      }
    }

    if (options.targetReturnPenalty) {
      const shortfall = Math.max(0, options.targetReturnPenalty.target - portfolioReturn(expectedReturns, weights));
      if (shortfall > 0) {
        const penalty = options.targetReturnPenalty.strength;
        for (let i = 0; i < gradient.length; i += 1) {
          gradient[i] -= 2 * penalty * shortfall * expectedReturns[i];
        }
      }
    }

    if (options.targetVolatilityPenalty) {
      const volatility = portfolioVolatility(covariance, weights);
      if (volatility > 1e-12) {
        const varianceGradient = matVec(covariance, weights);
        const gap = volatility - options.targetVolatilityPenalty.target;
        const penalty = options.targetVolatilityPenalty.strength;
        for (let i = 0; i < gradient.length; i += 1) {
          gradient[i] += (2 * penalty * gap * varianceGradient[i]) / volatility;
        }
      }
    }

    const step = baseStep / Math.sqrt(1 + iterations / 80);
    const candidate = projectCappedSimplex(
      weights.map((weight, index) => weight - step * gradient[index]),
      { lower, upper },
    );
    const value = objectiveValue(problem, candidate, options);
    if (Math.abs(previousValue - value) < 1e-13) {
      weights = candidate;
      break;
    }
    previousValue = value;
    weights = candidate;
  }

  return { weights, iterations: iterations + 1 };
}

function solveTiltGrid(problem, options = {}) {
  const tilts = options.tilts ?? [0, 0.025, 0.05, 0.1, 0.2, 0.4, 0.8, 1.6, 3.2, 6.4, 12.8];
  let best = null;
  let bestScore = Infinity;

  for (const returnTilt of tilts) {
    const solved = optimizeProjectedQuadratic(problem, {
      returnTilt,
      maxIterations: options.maxIterations ?? 1000,
      targetReturnPenalty: options.targetReturnPenalty,
      targetVolatilityPenalty: options.targetVolatilityPenalty,
    });
    const score = objectiveValue(problem, solved.weights, {
      kind: options.kind,
      targetVolatility: options.targetVolatility,
      returnTilt,
    });

    if (score < bestScore) {
      best = solved;
      bestScore = score;
    }
  }

  return best;
}

export function solveWithProjectedGradient(problem, options = {}) {
  const startedAt = performance.now();
  const lower = problem.constraints.lower;
  const upper = problem.constraints.upper;

  try {
    validateBounds(lower, upper);
  } catch (error) {
    return infeasibleResult(problem, 'bounds-infeasible', { message: error.message });
  }

  if (problem.objective.kind === 'target-return') {
    const maxReturnWeights = greedyBoundedPortfolio(problem.expectedReturns, lower, upper, 'max');
    const maxReturn = portfolioReturn(problem.expectedReturns, maxReturnWeights);
    if (problem.objective.targetReturn > maxReturn + 1e-8) {
      return infeasibleResult(problem, 'target-return-above-feasible-maximum', {
        targetReturn: problem.objective.targetReturn,
        maxReturn,
      });
    }
  }

  let solved;
  if (problem.objective.kind === 'minimum-variance') {
    solved = optimizeProjectedQuadratic(problem, options);
  } else if (problem.objective.kind === 'target-return') {
    solved = solveTiltGrid(problem, {
      kind: 'minimum-variance',
      targetReturnPenalty: {
        target: problem.objective.targetReturn,
        strength: 250,
      },
    });
  } else if (problem.objective.kind === 'target-volatility') {
    solved = solveTiltGrid(problem, {
      kind: 'target-volatility',
      targetVolatility: problem.objective.targetVolatility,
      targetVolatilityPenalty: {
        target: problem.objective.targetVolatility,
        strength: 2,
      },
    });
  } else if (problem.objective.kind === 'max-sharpe') {
    solved = solveTiltGrid(problem, { kind: 'max-sharpe' });
  } else {
    return infeasibleResult(problem, 'unknown-objective', { objective: problem.objective.kind });
  }

  const elapsedMs = performance.now() - startedAt;
  const expectedReturn = portfolioReturn(problem.expectedReturns, solved.weights);
  const volatility = portfolioVolatility(problem.covariance, solved.weights);
  const sharpe = volatility > 0 ? (expectedReturn - (problem.riskFreeRate ?? 0)) / volatility : null;

  return {
    solver: 'projected-gradient-js',
    status: 'solved',
    weights: solved.weights,
    iterations: solved.iterations,
    elapsedMs,
    expectedReturn,
    volatility,
    sharpe,
    objectiveValue: objectiveValue(problem, solved.weights, {
      kind: problem.objective.kind,
      targetVolatility: problem.objective.targetVolatility,
    }),
  };
}

export function summarizeProblemResult(problem, result) {
  if (result.status !== 'solved') {
    return {
      id: problem.id,
      size: problem.size,
      objective: problem.objective.kind,
      status: result.status,
      reason: result.reason,
      sumError: null,
      maxUpperViolation: null,
      maxLowerViolation: null,
      expectedReturn: null,
      volatility: null,
    };
  }

  const weights = result.weights;
  const lower = problem.constraints.lower;
  const upper = problem.constraints.upper;
  const expectedReturn = portfolioReturn(problem.expectedReturns, weights);
  const volatility = portfolioVolatility(problem.covariance, weights);
  const effectiveN = 1 / weights.reduce((acc, value) => acc + value * value, 0);
  const maxUpperViolation = Math.max(
    0,
    ...weights.map((weight, index) => weight - upper[index]),
  );
  const maxLowerViolation = Math.max(
    0,
    ...weights.map((weight, index) => lower[index] - weight),
  );

  return {
    id: problem.id,
    size: problem.size,
    objective: problem.objective.kind,
    status: result.status,
    sumError: Math.abs(sum(weights) - 1),
    maxUpperViolation,
    maxLowerViolation,
    expectedReturn,
    volatility,
    sharpe: volatility > 0 ? (expectedReturn - (problem.riskFreeRate ?? 0)) / volatility : null,
    maxWeight: Math.max(...weights),
    minWeight: Math.min(...weights),
    effectiveN,
    targetReturnShortfall:
      problem.objective.kind === 'target-return'
        ? Math.max(0, problem.objective.targetReturn - expectedReturn)
        : 0,
    targetVolatilityError:
      problem.objective.kind === 'target-volatility'
        ? Math.abs(problem.objective.targetVolatility - volatility)
        : 0,
  };
}

function packageRoot(packageName, externalRoot) {
  const candidates = [];
  if (externalRoot) {
    candidates.push(path.join(externalRoot, packageName));
  }
  candidates.push(path.join(process.cwd(), 'node_modules', packageName));

  for (const candidate of candidates) {
    if (fs.existsSync(path.join(candidate, 'package.json'))) {
      return candidate;
    }
  }

  return null;
}

function packageFootprintBytes(root) {
  let total = 0;
  const stack = [root];
  while (stack.length > 0) {
    const current = stack.pop();
    const stat = fs.statSync(current);
    if (stat.isDirectory()) {
      for (const entry of fs.readdirSync(current)) {
        stack.push(path.join(current, entry));
      }
    } else {
      total += stat.size;
    }
  }
  return total;
}

function readPackageMetadata(root) {
  const packageJson = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
  return {
    packageName: packageJson.name,
    version: packageJson.version,
    license: packageJson.license ?? null,
    description: packageJson.description ?? null,
    footprintBytes: packageFootprintBytes(root),
  };
}

function toOneIndexedMatrix(matrix) {
  return [null, ...matrix.map((row) => [null, ...row])];
}

function toOneIndexedVector(vector) {
  return [null, ...vector];
}

function quadprogSolveBase(problem, quadprog, options = {}) {
  const n = problem.size;
  const epsilon = options.epsilon ?? 1e-8;
  const dmat = toOneIndexedMatrix(
    problem.covariance.map((row, rowIndex) =>
      row.map((value, columnIndex) => value + (rowIndex === columnIndex ? epsilon : 0)),
    ),
  );
  const dvec = toOneIndexedVector(
    problem.expectedReturns.map((value) => (options.returnTilt ?? 0) * value),
  );

  const constraintColumns = [];
  const bvec = [];
  constraintColumns.push(Array.from({ length: n }, () => 1));
  bvec.push(1);

  if (options.targetReturn) {
    constraintColumns.push(problem.expectedReturns.slice());
    bvec.push(options.targetReturn);
  }

  for (let i = 0; i < n; i += 1) {
    const lowerColumn = Array.from({ length: n }, (_, index) => (index === i ? 1 : 0));
    constraintColumns.push(lowerColumn);
    bvec.push(problem.constraints.lower[i]);
  }
  for (let i = 0; i < n; i += 1) {
    const upperColumn = Array.from({ length: n }, (_, index) => (index === i ? -1 : 0));
    constraintColumns.push(upperColumn);
    bvec.push(-problem.constraints.upper[i]);
  }

  const amat = [null];
  for (let row = 0; row < n; row += 1) {
    amat.push([null, ...constraintColumns.map((column) => column[row])]);
  }
  const solved = quadprog.solveQP(dmat, dvec, amat, toOneIndexedVector(bvec), 1);
  if (solved.message) {
    return { status: 'infeasible', reason: solved.message };
  }
  return {
    status: 'solved',
    weights: solved.solution.slice(1),
    iterations: solved.iterations?.[1] ?? null,
  };
}

function solveWithQuadprog(problem, quadprog) {
  const startedAt = performance.now();
  let solved;
  if (problem.objective.kind === 'minimum-variance') {
    solved = quadprogSolveBase(problem, quadprog);
  } else if (problem.objective.kind === 'target-return') {
    solved = quadprogSolveBase(problem, quadprog, {
      targetReturn: problem.objective.targetReturn,
    });
  } else {
    const tilts = [0.025, 0.05, 0.1, 0.2, 0.4, 0.8, 1.6, 3.2, 6.4, 12.8, 25.6];
    let best = null;
    let bestScore = Infinity;
    for (const returnTilt of tilts) {
      const candidate = quadprogSolveBase(problem, quadprog, { returnTilt });
      if (candidate.status !== 'solved') {
        continue;
      }
      const score = objectiveValue(problem, candidate.weights, {
        kind: problem.objective.kind,
        targetVolatility: problem.objective.targetVolatility,
        returnTilt,
      });
      if (score < bestScore) {
        best = candidate;
        bestScore = score;
      }
    }
    solved = best ?? { status: 'infeasible', reason: 'quadprog-grid-returned-no-solution' };
  }

  if (solved.status !== 'solved') {
    return {
      solver: 'quadprog',
      status: 'infeasible',
      reason: solved.reason,
      weights: [],
      elapsedMs: performance.now() - startedAt,
    };
  }

  const expectedReturn = portfolioReturn(problem.expectedReturns, solved.weights);
  const volatility = portfolioVolatility(problem.covariance, solved.weights);
  return {
    solver: 'quadprog',
    status: 'solved',
    weights: solved.weights,
    iterations: solved.iterations,
    elapsedMs: performance.now() - startedAt,
    expectedReturn,
    volatility,
    sharpe: volatility > 0 ? (expectedReturn - (problem.riskFreeRate ?? 0)) / volatility : null,
  };
}

function denseToCsc(matrix, mode = 'full') {
  const data = [];
  const rowIndices = [];
  const columnPointers = [0];
  const n = matrix.length;
  const columns = matrix[0].length;
  for (let column = 0; column < columns; column += 1) {
    for (let row = 0; row < n; row += 1) {
      if (mode === 'upper' && row > column) {
        continue;
      }
      const value = matrix[row][column];
      if (Math.abs(value) > 1e-14) {
        data.push(value);
        rowIndices.push(row);
      }
    }
    columnPointers.push(data.length);
  }
  return {
    data: new Float64Array(data),
    row_indices: new Int32Array(rowIndices),
    column_pointers: new Int32Array(columnPointers),
  };
}

async function solveWithOsqp(problem, osqpModule) {
  const startedAt = performance.now();
  const OSQP = osqpModule.default ?? osqpModule;
  const n = problem.size;
  const rows = [];
  const lower = [];
  const upper = [];
  rows.push(Array.from({ length: n }, () => 1));
  lower.push(1);
  upper.push(1);
  for (let i = 0; i < n; i += 1) {
    rows.push(Array.from({ length: n }, (_, index) => (index === i ? 1 : 0)));
    lower.push(problem.constraints.lower[i]);
    upper.push(problem.constraints.upper[i]);
  }

  if (problem.objective.kind === 'target-return') {
    rows.push(problem.expectedReturns.slice());
    lower.push(problem.objective.targetReturn);
    upper.push(Number.POSITIVE_INFINITY);
  }

  const q = problem.expectedReturns.map(() => 0);
  if (problem.objective.kind === 'max-sharpe') {
    for (let i = 0; i < q.length; i += 1) {
      q[i] = -0.4 * problem.expectedReturns[i];
    }
  }
  if (problem.objective.kind === 'target-volatility') {
    for (let i = 0; i < q.length; i += 1) {
      q[i] = -0.1 * problem.expectedReturns[i];
    }
  }

  let solver;
  try {
    const weights = await withStdoutSilenced(async () => {
      solver = await OSQP.setup({
        P: denseToCsc(problem.covariance, 'upper'),
        A: denseToCsc(rows, 'full'),
        q: new Float64Array(q),
        l: new Float64Array(lower),
        u: new Float64Array(upper),
      }, {
        verbose: false,
        eps_abs: 1e-7,
        eps_rel: 1e-7,
        max_iter: 20000,
        polish: true,
        check_termination: 10,
      });
      return Array.from(solver.solve());
    });
    const expectedReturn = portfolioReturn(problem.expectedReturns, weights);
    const volatility = portfolioVolatility(problem.covariance, weights);
    return {
      solver: 'osqp',
      status: 'solved',
      weights,
      iterations: null,
      elapsedMs: performance.now() - startedAt,
      expectedReturn,
      volatility,
      sharpe: volatility > 0 ? (expectedReturn - (problem.riskFreeRate ?? 0)) / volatility : null,
    };
  } catch (error) {
    return {
      solver: 'osqp',
      status: 'failed',
      reason: error.message,
      weights: [],
      elapsedMs: performance.now() - startedAt,
    };
  } finally {
    solver?.cleanup?.();
  }
}

async function withStdoutSilenced(fn) {
  const originalStdoutWrite = process.stdout.write;
  process.stdout.write = function suppressedStdoutWrite() {
    return true;
  };
  try {
    return await fn();
  } finally {
    process.stdout.write = originalStdoutWrite;
  }
}

async function loadCandidate(name, externalRoot) {
  if (name === 'projected-gradient-js') {
    return {
      available: true,
      metadata: {
        packageName: 'projected-gradient-js',
        version: 'internal-spike',
        license: 'AGPL-3.0',
        description: 'Deterministic in-repo projected-gradient baseline for solver selection only.',
        footprintBytes: fs.statSync(fileURLToPath(import.meta.url)).size,
      },
      solve: (problem) => solveWithProjectedGradient(problem),
    };
  }

  const root = packageRoot(name, externalRoot);
  if (!root) {
    return {
      available: false,
      unavailableReason: `${name} package not found; pass --external-root or install locally for spike runs`,
    };
  }

  const metadata = readPackageMetadata(root);
  if (name === 'quadprog') {
    const require = createRequire(import.meta.url);
    const quadprog = require(root);
    return {
      available: true,
      metadata,
      solve: (problem) => solveWithQuadprog(problem, quadprog),
    };
  }

  if (name === 'osqp') {
    const modulePath = path.join(root, 'dist', 'osqp.min.js');
    const osqpModule = await import(pathToFileURL(modulePath).href);
    return {
      available: true,
      metadata,
      solve: (problem) => solveWithOsqp(problem, osqpModule),
    };
  }

  return {
    available: false,
    unavailableReason: `unknown candidate: ${name}`,
  };
}

function summarizeCandidateRun(runSummaries) {
  const solved = runSummaries.filter((summary) => summary.status === 'solved');
  const elapsed = runSummaries.map((summary) => summary.elapsedMs).filter(Number.isFinite);
  return {
    solvedCount: solved.length,
    failedCount: runSummaries.length - solved.length,
    meanElapsedMs: elapsed.length > 0 ? sum(elapsed) / elapsed.length : null,
    maxElapsedMs: elapsed.length > 0 ? Math.max(...elapsed) : null,
    maxSumError: Math.max(0, ...solved.map((summary) => summary.sumError ?? 0)),
    maxBoundViolation: Math.max(
      0,
      ...solved.map((summary) =>
        Math.max(summary.maxUpperViolation ?? 0, summary.maxLowerViolation ?? 0),
      ),
    ),
    maxTargetReturnShortfall: Math.max(
      0,
      ...solved.map((summary) => summary.targetReturnShortfall ?? 0),
    ),
    maxTargetVolatilityError: Math.max(
      0,
      ...solved.map((summary) => summary.targetVolatilityError ?? 0),
    ),
  };
}

export async function benchmarkCandidates(options = {}) {
  const problems = options.problems ?? buildBenchmarkProblems();
  const candidates = options.candidates ?? DEFAULT_CANDIDATES;
  const warmupRuns = options.warmupRuns ?? 2;
  const measuredRuns = options.measuredRuns ?? 3;
  const result = {
    generatedAt: new Date().toISOString(),
    node: process.version,
    problemCount: problems.length,
    warmupRuns,
    measuredRuns,
    candidates: {},
  };

  for (const candidateName of candidates) {
    const loaded = await loadCandidate(candidateName, options.externalRoot);
    if (!loaded.available) {
      result.candidates[candidateName] = loaded;
      continue;
    }

    for (let warmup = 0; warmup < warmupRuns; warmup += 1) {
      for (const problem of problems) {
        await loaded.solve(problem);
      }
    }

    const heapBefore = process.memoryUsage().heapUsed;
    const perProblem = [];
    for (const problem of problems) {
      const runs = [];
      for (let run = 0; run < measuredRuns; run += 1) {
        const startedAt = performance.now();
        const solved = await loaded.solve(problem);
        const elapsedMs = performance.now() - startedAt;
        runs.push({
          ...summarizeProblemResult(problem, solved),
          elapsedMs,
          solverStatus: solved.status,
          solverReason: solved.reason ?? null,
        });
      }
      perProblem.push({
        id: problem.id,
        size: problem.size,
        objective: problem.objective.kind,
        runs,
        summary: summarizeCandidateRun(runs),
      });
    }
    const heapAfter = process.memoryUsage().heapUsed;
    const flattenedRuns = perProblem.flatMap((entry) => entry.runs);

    result.candidates[candidateName] = {
      available: true,
      metadata: loaded.metadata,
      heapDeltaBytes: heapAfter - heapBefore,
      perProblem,
      summary: summarizeCandidateRun(flattenedRuns),
    };
  }

  return result;
}

export function candidateNames(result) {
  return Object.keys(result.candidates);
}

function parseArgs(argv) {
  const options = {};
  for (const arg of argv) {
    if (arg.startsWith('--sizes=')) {
      options.sizes = arg
        .slice('--sizes='.length)
        .split(',')
        .map((value) => Number.parseInt(value, 10))
        .filter(Number.isFinite);
    } else if (arg.startsWith('--seed=')) {
      options.seed = Number.parseInt(arg.slice('--seed='.length), 10);
    } else if (arg.startsWith('--warmup=')) {
      options.warmupRuns = Number.parseInt(arg.slice('--warmup='.length), 10);
    } else if (arg.startsWith('--runs=')) {
      options.measuredRuns = Number.parseInt(arg.slice('--runs='.length), 10);
    } else if (arg.startsWith('--external-root=')) {
      options.externalRoot = arg.slice('--external-root='.length);
    } else if (arg.startsWith('--candidate=')) {
      options.candidates = arg.slice('--candidate='.length).split(',');
    }
  }
  return options;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const problems = buildBenchmarkProblems({
    sizes: args.sizes ?? DEFAULT_SIZES,
    seed: args.seed ?? 1729,
  });
  const result = await benchmarkCandidates({
    problems,
    candidates: args.candidates ?? DEFAULT_CANDIDATES,
    externalRoot: args.externalRoot,
    warmupRuns: args.warmupRuns ?? 2,
    measuredRuns: args.measuredRuns ?? 3,
  });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}
