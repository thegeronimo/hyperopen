---
owner: api-runtime
status: canonical
last_reviewed: 2026-02-26
review_cycle_days: 90
source_of_truth: true
---

# Hyperliquid Portfolio History and Returns

## Purpose
Explain how to obtain portfolio history data from Hyperliquid, what each field means, and how to use it to plot:
- account value over time,
- PNL over time,
- returns over time (derived).

This document is based on:
- live frontend/network inspection on `2026-02-26`,
- Hyperliquid docs and SDK references,
- existing bundle artifacts already captured in this repo.

## Primary Data Source

### Endpoint
Use Hyperliquid `info` with request type `portfolio`.

Production UI currently calls:
- `POST https://api-ui.hyperliquid.xyz/info`

Docs and SDKs commonly show:
- `POST https://api.hyperliquid.xyz/info`

Both use the same request/response payload shape for this method.

### Request
```json
{
  "type": "portfolio",
  "user": "0x1234567890abcdef1234567890abcdef12345678"
}
```

### Response Shape
`portfolio` returns an array of 8 tuple entries:

```json
[
  ["day", { "accountValueHistory": [...], "pnlHistory": [...], "vlm": "..." }],
  ["week", { "accountValueHistory": [...], "pnlHistory": [...], "vlm": "..." }],
  ["month", { "accountValueHistory": [...], "pnlHistory": [...], "vlm": "..." }],
  ["allTime", { "accountValueHistory": [...], "pnlHistory": [...], "vlm": "..." }],
  ["perpDay", { "accountValueHistory": [...], "pnlHistory": [...], "vlm": "..." }],
  ["perpWeek", { "accountValueHistory": [...], "pnlHistory": [...], "vlm": "..." }],
  ["perpMonth", { "accountValueHistory": [...], "pnlHistory": [...], "vlm": "..." }],
  ["perpAllTime", { "accountValueHistory": [...], "pnlHistory": [...], "vlm": "..." }]
]
```

Each history point is:
- `[timestampMs, valueString]`

where:
- `timestampMs`: Unix epoch in milliseconds,
- `valueString`: decimal string (not integer cents).

## Field Meaning

For each period key:
- `accountValueHistory`: historical account value curve.
- `pnlHistory`: historical PNL curve for the same period/scope key.
- `vlm`: volume metric for that same key.

Observed in live data:
- `pnlHistory[0]` is typically `"0.0"` for a slice.
- `pnlHistory` can match account value delta for short windows with no major transfers.
- `pnlHistory` can diverge from account value delta over longer windows when cash flows or scope differences exist.

Example from `/tmp/hl-portfolio-sample-rich.json`:
- `day`: account delta ~= pnl delta.
- `month`: account delta and pnl delta differ materially.

This means returns should not rely on one simplistic interpretation in all windows.

## How Hyperliquid Maps It Into the Portfolio Chart

From captured bundle artifacts:
- `/hyperopen/tmp/hyperliquid-185.pretty.js`
- `/hyperopen/tmp/hyperliquid-6951.pretty.js`
- `/hyperopen/tmp/module-36714.js`

Behavior:
1. `portfolio` response tuple array is normalized into an object map keyed by:
   - `day`, `week`, `month`, `allTime`, `perpDay`, `perpWeek`, `perpMonth`, `perpAllTime`.
2. Selected scope + selected range choose a single key.
   - Scope `"all"` uses `day|week|month|allTime`.
   - Scope `"perps"` maps with:
     - `day -> perpDay`
     - `week -> perpWeek`
     - `month -> perpMonth`
     - `allTime -> perpAllTime`
3. Chart tab chooses source series:
   - `Account Value` tab -> `accountValueHistory`
   - `PNL` tab -> `pnlHistory`
4. Data is rendered as a step line with auto y-domain.
5. Portfolio polling is periodic (bundle uses a 300000ms interval in the shared polling helper).

## Plot Construction Guide

### Account Value Over Time
Use selected slice `accountValueHistory`:
1. Parse `valueString` to decimal/number.
2. Convert `timestampMs` to chart x coordinate.
3. Sort by timestamp ascending.
4. Plot step or line chart.

### PNL Over Time
Use selected slice `pnlHistory`:
1. Parse values as signed decimal.
2. Same timestamp handling as account value.
3. Plot with zero baseline visible.

### Volume Context (Optional)
Use slice `vlm` in summary/KPI, not line chart points.

## Returns Over Time (Derived)

Hyperliquid does not expose a dedicated `returns` series in `portfolio`.
Returns should be derived from `accountValueHistory`, optionally adjusted by cash flows.

### Method A: Raw Equity Return (Simple)
Best for quick visuals.

For each point `t`:
- `V_t`: account value at `t`
- point return: `r_t = (V_t / V_(t-1)) - 1`
- cumulative return from first point: `R_t = (V_t / V_0) - 1`

Pros:
- simple and deterministic.

Cons:
- distorted by deposits/withdrawals/transfers.

### Method B: Cash-Flow-Adjusted Return (Recommended for reporting)
Use `userNonFundingLedgerUpdates` to estimate external cash flows.

Endpoint:
```json
{
  "type": "userNonFundingLedgerUpdates",
  "user": "0x...",
  "startTime": 1769468400079,
  "endTime": 1772111479505
}
```

For each interval `(t-1, t]`:
1. Compute net flow `F_t` from ledger events in interval.
2. Compute performance delta: `P_t = V_t - V_(t-1) - F_t`.
3. Compute adjusted return:
   - simple: `r_t = P_t / V_(t-1)`
   - or Modified Dietz for irregular intra-interval flows.
4. Chain returns: `R_t = Π(1 + r_i) - 1`.

Notes:
- Classify flow signs carefully per event type and direction.
- Use decimal arithmetic, not float, for audit-grade results.

### Method C: PNL-Based Return Proxy
If flow data is unavailable:
1. Use `pnlHistory` deltas per interval.
2. Divide by chosen capital base (`V_(t-1)` or window start value).

This is a proxy only; define methodology explicitly in report metadata.

## Recommended Reporting Pipeline
1. Fetch `portfolio` for target user.
2. Select slice key from report scope/time config.
3. Build account-value and pnl chart series directly from selected history arrays.
4. Fetch `userNonFundingLedgerUpdates` for same time window.
5. Derive adjusted periodic and cumulative returns.
6. Persist both:
   - `raw_return_series`,
   - `flow_adjusted_return_series`,
   so consumers can choose strictness level.

## Data Quality and Interpretation Notes
- Points are sampled snapshots, not per-trade ticks.
- Sampling intervals are irregular across ranges.
- Value fields arrive as strings; parse carefully.
- Some windows may begin with repeated or zero values.
- Scope matters:
  - `all*` keys can differ materially from `perp*` keys.
- Do not assume `accountValueHistory` delta equals `pnlHistory` delta in every window.

## Minimal Type Definitions
```ts
type PortfolioPoint = [timestampMs: number, value: string];

type PortfolioSlice = {
  accountValueHistory: PortfolioPoint[];
  pnlHistory: PortfolioPoint[];
  vlm: string;
};

type PortfolioResponse = [
  ["day", PortfolioSlice],
  ["week", PortfolioSlice],
  ["month", PortfolioSlice],
  ["allTime", PortfolioSlice],
  ["perpDay", PortfolioSlice],
  ["perpWeek", PortfolioSlice],
  ["perpMonth", PortfolioSlice],
  ["perpAllTime", PortfolioSlice]
];
```

## References
- Hyperliquid Info endpoint docs:
  - `https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint#query-a-users-portfolio`
- nktkas TypeScript SDK:
  - `https://github.com/nktkas/hyperliquid/blob/main/src/api/info/_methods/portfolio.ts`
  - `https://github.com/nktkas/hyperliquid/blob/main/src/api/info/_methods/userNonFundingLedgerUpdates.ts`
- nomeida TypeScript SDK:
  - `https://github.com/nomeida/hyperliquid/blob/main/src/rest/info/general.ts`
  - `https://github.com/nomeida/hyperliquid/blob/main/src/types/index.ts`
- hyperliquid-dex Python SDK:
  - `https://github.com/hyperliquid-dex/hyperliquid-python-sdk/blob/master/hyperliquid/info.py`
  - `https://github.com/hyperliquid-dex/hyperliquid-python-sdk/blob/master/tests/cassettes/info_test/test_portfolio.yaml`
- Local evidence artifacts:
  - `/hyperopen/tmp/hl-portfolio-info-capture-override.json`
  - `/hyperopen/tmp/hl-portfolio-sample-rich.json`
  - `/hyperopen/tmp/hyperliquid-185.pretty.js`
  - `/hyperopen/tmp/hyperliquid-6951.pretty.js`
  - `/hyperopen/tmp/module-36714.js`
