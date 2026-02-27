#!/usr/bin/env node
import fs from "node:fs";

const MS_PER_DAY = 24 * 60 * 60 * 1000;
const MS_PER_YEAR = 365.2425 * MS_PER_DAY;
const API_URL = "https://api-ui.hyperliquid.xyz/info";

const REAL_ACCOUNT_CANDIDATES = [
  "0x00071360d75385c4c25dd8f217465693ffe91a69",
  "0x00c07d5370605db4d449f389d1aaad3b2ee78e15",
  "0x010461c14e146ac35fe42271bdc1134ee31c703a",
  "0x068f321fa8fb9f0d135f290ef6a3e2813e1c8a29",
  "0x07fd993f0fa3a185f7207adccd29f7a87404689d",
];

const MONTE_CARLO = {
  trials: 750,
  days: 1000,
  muAnnual: 0.10,
  sigmaAnnual: 0.30,
};

function mean(xs) {
  if (!xs.length) return null;
  return xs.reduce((a, b) => a + b, 0) / xs.length;
}

function stdSample(xs) {
  if (xs.length < 2) return null;
  const m = mean(xs);
  const v = xs.reduce((a, x) => a + (x - m) ** 2, 0) / (xs.length - 1);
  return Math.sqrt(v);
}

function median(xs) {
  if (!xs.length) return null;
  const ys = [...xs].sort((a, b) => a - b);
  const mid = Math.floor(ys.length / 2);
  return ys.length % 2 === 0 ? (ys[mid - 1] + ys[mid]) / 2 : ys[mid];
}

function relError(a, b) {
  if (!Number.isFinite(a) || !Number.isFinite(b)) return null;
  if (Math.abs(b) < 1e-12) return null;
  return Math.abs(a - b) / Math.abs(b);
}

function parsePortfolioPoints(history) {
  return (history || [])
    .map(([t, v]) => ({ t: Number(t), v: Number(v) }))
    .filter((p) => Number.isFinite(p.t) && Number.isFinite(p.v) && p.v > 0)
    .sort((a, b) => a.t - b.t)
    .filter((p, i, arr) => i === 0 || p.t > arr[i - 1].t);
}

function intervalsFromPoints(points) {
  const out = [];
  for (let i = 1; i < points.length; i += 1) {
    const prev = points[i - 1];
    const cur = points[i];
    const dtMs = cur.t - prev.t;
    const dtYears = dtMs / MS_PER_YEAR;
    const ratio = cur.v / prev.v;
    if (!(dtYears > 0) || !(ratio > 0)) continue;
    out.push({
      dtYears,
      log: Math.log(ratio),
      simple: ratio - 1,
      t: cur.t,
    });
  }
  return out;
}

function computeNewMetrics(points) {
  if (points.length < 2) return null;
  const intervals = intervalsFromPoints(points);
  if (!intervals.length) return null;

  const v0 = points[0].v;
  const vn = points[points.length - 1].v;
  const totalYears = (points[points.length - 1].t - points[0].t) / MS_PER_YEAR;
  if (!(totalYears > 0) || !(v0 > 0) || !(vn > 0)) return null;

  const cagr = Math.exp(Math.log(vn / v0) / totalYears) - 1;
  const sumLog = intervals.reduce((a, x) => a + x.log, 0);
  const mu = sumLog / totalYears;

  let sigma = null;
  if (intervals.length >= 2) {
    const acc = intervals.reduce((a, x) => {
      const e = x.log - mu * x.dtYears;
      return a + (e * e) / x.dtYears;
    }, 0);
    const sigma2 = acc / (intervals.length - 1);
    sigma = sigma2 > 0 ? Math.sqrt(sigma2) : null;
  }

  const sharpe = sigma && sigma > 0 ? mu / sigma : null;
  return { cagr, vol: sigma, sharpe, intervals: intervals.length };
}

function computeOldMetrics(points) {
  if (points.length < 2) return null;
  const intervals = intervalsFromPoints(points);
  if (!intervals.length) return null;

  const returns = intervals.map((x) => x.simple);
  const n = returns.length;
  const cumulative = points[points.length - 1].v / points[0].v - 1;

  let cagr = null;
  if (n > 0 && cumulative > -1) {
    cagr = Math.pow(1 + cumulative, 252 / n) - 1;
  }

  const s = stdSample(returns);
  const m = mean(returns);
  const vol = s ? s * Math.sqrt(252) : null;
  const sharpe = s && s > 0 ? (m / s) * Math.sqrt(252) : null;

  return { cagr, vol, sharpe, intervals: n };
}

function downsamplePoints(points, step) {
  if (points.length <= 3) return points;
  const out = [points[0]];
  for (let i = step; i < points.length - 1; i += step) {
    out.push(points[i]);
  }
  out.push(points[points.length - 1]);
  return out;
}

function pointsFromSim(logReturns) {
  const start = Date.UTC(2020, 0, 1);
  const points = [{ t: start, v: 1 }];
  let v = 1;
  for (let i = 0; i < logReturns.length; i += 1) {
    v *= Math.exp(logReturns[i]);
    points.push({ t: start + (i + 1) * MS_PER_DAY, v });
  }
  return points;
}

function randomNormal() {
  let u = 0;
  let v = 0;
  while (u === 0) u = Math.random();
  while (v === 0) v = Math.random();
  return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * v);
}

function generateDailyLogReturns(days, muAnnual, sigmaAnnual) {
  const muDay = muAnnual / 365.2425;
  const sigmaDay = sigmaAnnual / Math.sqrt(365.2425);
  const out = [];
  for (let i = 0; i < days; i += 1) {
    out.push(muDay + sigmaDay * randomNormal());
  }
  return out;
}

function toSparseRandom(points, minGap = 7, maxGap = 21) {
  if (points.length <= 2) return points;
  const out = [points[0]];
  let idx = 0;
  while (idx < points.length - 1) {
    const gap = minGap + Math.floor(Math.random() * (maxGap - minGap + 1));
    idx += gap;
    if (idx < points.length - 1) out.push(points[idx]);
  }
  out.push(points[points.length - 1]);
  return out;
}

function summarizeErrors(errors, threshold) {
  const clean = errors.filter((x) => Number.isFinite(x));
  if (!clean.length) return { n: 0, mean: null, median: null, passRate: null };
  const pass = clean.filter((x) => x <= threshold).length;
  return {
    n: clean.length,
    mean: mean(clean),
    median: median(clean),
    passRate: pass / clean.length,
  };
}

function runMonteCarlo() {
  const variants = ["weekly", "biweekly", "random14"];
  const metrics = ["cagr", "vol", "sharpe"];
  const thresholds = { cagr: 0.01, vol: 0.15, sharpe: 0.20 };

  const errors = {};
  for (const variant of variants) {
    errors[variant] = { new: { cagr: [], vol: [], sharpe: [] }, old: { cagr: [], vol: [], sharpe: [] } };
  }

  for (let trial = 0; trial < MONTE_CARLO.trials; trial += 1) {
    const logs = generateDailyLogReturns(MONTE_CARLO.days, MONTE_CARLO.muAnnual, MONTE_CARLO.sigmaAnnual);
    const full = pointsFromSim(logs);
    const baseline = computeNewMetrics(full);
    if (!baseline) continue;

    const sparseMap = {
      weekly: downsamplePoints(full, 7),
      biweekly: downsamplePoints(full, 14),
      random14: toSparseRandom(full),
    };

    for (const [variant, sparse] of Object.entries(sparseMap)) {
      const n = computeNewMetrics(sparse);
      const o = computeOldMetrics(sparse);
      for (const metric of metrics) {
        errors[variant].new[metric].push(relError(n?.[metric], baseline[metric]));
        errors[variant].old[metric].push(relError(o?.[metric], baseline[metric]));
      }
    }
  }

  const summary = {};
  for (const variant of variants) {
    summary[variant] = { new: {}, old: {} };
    for (const metric of metrics) {
      summary[variant].new[metric] = summarizeErrors(errors[variant].new[metric], thresholds[metric]);
      summary[variant].old[metric] = summarizeErrors(errors[variant].old[metric], thresholds[metric]);
      summary[variant].new[metric].threshold = thresholds[metric];
      summary[variant].old[metric].threshold = thresholds[metric];
    }
  }

  return { config: MONTE_CARLO, summary };
}

async function fetchPortfolio(user) {
  const res = await fetch(API_URL, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ type: "portfolio", user }),
  });
  if (!res.ok) return null;
  const data = await res.json();
  if (!Array.isArray(data)) return null;
  return Object.fromEntries(data.filter(Array.isArray));
}

function overlapPoints(allTime, startMs, endMs) {
  return allTime.filter((p) => p.t >= startMs && p.t <= endMs);
}

function metricDeltas(reference, candidate) {
  return {
    cagr: relError(candidate?.cagr, reference?.cagr),
    vol: relError(candidate?.vol, reference?.vol),
    sharpe: relError(candidate?.sharpe, reference?.sharpe),
  };
}

async function runRealAccountSensitivity() {
  const rows = [];
  for (const account of REAL_ACCOUNT_CANDIDATES) {
    const portfolio = await fetchPortfolio(account);
    if (!portfolio) continue;
    const month = parsePortfolioPoints(portfolio.month?.accountValueHistory || []);
    const allTime = parsePortfolioPoints(portfolio.allTime?.accountValueHistory || []);
    if (month.length < 20 || allTime.length < 10) continue;

    const monthSparse = downsamplePoints(month, 7);
    const monthStart = month[0].t;
    const monthEnd = month[month.length - 1].t;
    const allTimeOverlap = overlapPoints(allTime, monthStart, monthEnd);

    const baseNew = computeNewMetrics(month);
    const sparseNew = computeNewMetrics(monthSparse);
    const sparseOld = computeOldMetrics(monthSparse);
    const overlapNew = computeNewMetrics(allTimeOverlap);
    const overlapOld = computeOldMetrics(allTimeOverlap);

    rows.push({
      account,
      monthPoints: month.length,
      monthSparsePoints: monthSparse.length,
      allTimePoints: allTime.length,
      allTimeOverlapPoints: allTimeOverlap.length,
      sparseVsMonthNew: metricDeltas(baseNew, sparseNew),
      sparseVsMonthOld: metricDeltas(baseNew, sparseOld),
      overlapVsMonthNew: metricDeltas(baseNew, overlapNew),
      overlapVsMonthOld: metricDeltas(baseNew, overlapOld),
    });
  }

  const aggregate = (selector) => {
    const vals = { cagr: [], vol: [], sharpe: [] };
    for (const row of rows) {
      const x = selector(row);
      vals.cagr.push(x.cagr);
      vals.vol.push(x.vol);
      vals.sharpe.push(x.sharpe);
    }
    return {
      cagr: mean(vals.cagr.filter(Number.isFinite)),
      vol: mean(vals.vol.filter(Number.isFinite)),
      sharpe: mean(vals.sharpe.filter(Number.isFinite)),
    };
  };

  return {
    accountsUsed: rows.length,
    rows,
    aggregate: {
      sparseVsMonthNew: aggregate((r) => r.sparseVsMonthNew),
      sparseVsMonthOld: aggregate((r) => r.sparseVsMonthOld),
      overlapVsMonthNew: aggregate((r) => r.overlapVsMonthNew),
      overlapVsMonthOld: aggregate((r) => r.overlapVsMonthOld),
    },
  };
}

function pct(x) {
  return Number.isFinite(x) ? `${(x * 100).toFixed(2)}%` : "n/a";
}

function renderMarkdown(result) {
  const date = new Date().toISOString().slice(0, 10);
  const m = result.monteCarlo.summary;
  const r = result.real;

  return `# Performance Metrics Irregular-Cadence Sensitivity Run (${date})

## Run Metadata

- Script: \`node tools/metrics_irregular_sensitivity.mjs\`
- Monte Carlo trials: ${result.monteCarlo.config.trials}
- Synthetic horizon: ${result.monteCarlo.config.days} daily observations
- Real accounts analyzed: ${r.accountsUsed}

## Monte Carlo Summary

Relative error is measured against the full daily-series irregular estimator baseline.

### Weekly Subsample

- New CAGR mean error: ${pct(m.weekly.new.cagr.mean)} (threshold ${pct(m.weekly.new.cagr.threshold)})
- Old CAGR mean error: ${pct(m.weekly.old.cagr.mean)}
- New Vol mean error: ${pct(m.weekly.new.vol.mean)} (threshold ${pct(m.weekly.new.vol.threshold)})
- Old Vol mean error: ${pct(m.weekly.old.vol.mean)}
- New Sharpe mean error: ${pct(m.weekly.new.sharpe.mean)} (threshold ${pct(m.weekly.new.sharpe.threshold)})
- Old Sharpe mean error: ${pct(m.weekly.old.sharpe.mean)}

### Biweekly Subsample

- New CAGR mean error: ${pct(m.biweekly.new.cagr.mean)} (threshold ${pct(m.biweekly.new.cagr.threshold)})
- Old CAGR mean error: ${pct(m.biweekly.old.cagr.mean)}
- New Vol mean error: ${pct(m.biweekly.new.vol.mean)} (threshold ${pct(m.biweekly.new.vol.threshold)})
- Old Vol mean error: ${pct(m.biweekly.old.vol.mean)}
- New Sharpe mean error: ${pct(m.biweekly.new.sharpe.mean)} (threshold ${pct(m.biweekly.new.sharpe.threshold)})
- Old Sharpe mean error: ${pct(m.biweekly.old.sharpe.mean)}

### Random 7-21 Day Subsample

- New CAGR mean error: ${pct(m.random14.new.cagr.mean)} (threshold ${pct(m.random14.new.cagr.threshold)})
- Old CAGR mean error: ${pct(m.random14.old.cagr.mean)}
- New Vol mean error: ${pct(m.random14.new.vol.mean)} (threshold ${pct(m.random14.new.vol.threshold)})
- Old Vol mean error: ${pct(m.random14.old.vol.mean)}
- New Sharpe mean error: ${pct(m.random14.new.sharpe.mean)} (threshold ${pct(m.random14.new.sharpe.threshold)})
- Old Sharpe mean error: ${pct(m.random14.old.sharpe.mean)}

## Real-Account Sensitivity Summary

Divergence measured against each account's full \`month\` window baseline.

- Sparse(weekly) vs month (new): CAGR ${pct(r.aggregate.sparseVsMonthNew.cagr)}, Vol ${pct(r.aggregate.sparseVsMonthNew.vol)}, Sharpe ${pct(r.aggregate.sparseVsMonthNew.sharpe)}
- Sparse(weekly) vs month (old): CAGR ${pct(r.aggregate.sparseVsMonthOld.cagr)}, Vol ${pct(r.aggregate.sparseVsMonthOld.vol)}, Sharpe ${pct(r.aggregate.sparseVsMonthOld.sharpe)}
- AllTime-overlap vs month (new): CAGR ${pct(r.aggregate.overlapVsMonthNew.cagr)}, Vol ${pct(r.aggregate.overlapVsMonthNew.vol)}, Sharpe ${pct(r.aggregate.overlapVsMonthNew.sharpe)}
- AllTime-overlap vs month (old): CAGR ${pct(r.aggregate.overlapVsMonthOld.cagr)}, Vol ${pct(r.aggregate.overlapVsMonthOld.vol)}, Sharpe ${pct(r.aggregate.overlapVsMonthOld.sharpe)}

## Notes

- This run validates cadence sensitivity reduction relative to the legacy fixed-period annualization pattern.
- Real-account comparisons depend on public account data available at run time and may vary across dates.
`;
}

async function main() {
  const monteCarlo = runMonteCarlo();
  const real = await runRealAccountSensitivity();
  const result = {
    generatedAt: new Date().toISOString(),
    monteCarlo,
    real,
  };

  fs.mkdirSync("docs/qa", { recursive: true });
  fs.mkdirSync("tmp", { recursive: true });

  const jsonPath = "tmp/performance-metrics-irregular-cadence-sensitivity-2026-02-27.json";
  const mdPath = "docs/qa/performance-metrics-irregular-cadence-sensitivity-2026-02-27.md";

  fs.writeFileSync(jsonPath, JSON.stringify(result, null, 2));
  fs.writeFileSync(mdPath, renderMarkdown(result));

  console.log(`Wrote ${jsonPath}`);
  console.log(`Wrote ${mdPath}`);
  console.log(`Accounts analyzed: ${real.accountsUsed}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
