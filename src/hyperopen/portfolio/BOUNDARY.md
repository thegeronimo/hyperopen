# Portfolio Boundary

## Owns

- Portfolio route parsing, portfolio UI action normalization, benchmark selection rules, performance-metric computation, worker payloads, and portfolio-specific metric bridge behavior.

## Stable Public Seams

- `hyperopen.portfolio.actions`
  The stable seam for portfolio tabs, time ranges, benchmark tokens, and request-shaping helpers.
- `hyperopen.portfolio.routes`
  The canonical route seam for `/portfolio` and `/portfolio/trader/<address>`.
- `hyperopen.portfolio.metrics`
  The compatibility facade over the pure `metrics/*` modules.
- `hyperopen.portfolio.application.metrics-bridge`
  The application seam for worker requests, result normalization, and benchmark alignment.
- `hyperopen.portfolio.worker`
  The dedicated worker entrypoint for performance metric computation.

## Dependency Rules

- Allowed:
  `portfolio.metrics.*` stays pure and may depend only on other portfolio metric helpers or generic math/parsing utilities.
- Allowed:
  `portfolio.application.metrics-bridge` may depend on portfolio actions, portfolio metrics, and system or worker seams.
- Allowed:
  Views and other bounded contexts may consume the public portfolio seams.
- Forbidden:
  Do not recreate the old `portfolio -> views` dependency inversion for metric or benchmark helpers.
- Forbidden:
  Do not put worker-orchestration logic back under `hyperopen.views.*` when `metrics-bridge` already owns it.
- Forbidden:
  Do not duplicate route parsing or benchmark-token normalization in callers when `portfolio.routes` or `portfolio.actions` already owns it.

## Key Tests

- Key namespaces:
  `hyperopen.portfolio.actions-test`,
  `hyperopen.portfolio.benchmark-actions-test`,
  `hyperopen.portfolio.fee-schedule-test`,
  `hyperopen.portfolio.fee-context-test`,
  `hyperopen.portfolio.application.metrics-bridge-test`,
  `hyperopen.portfolio.metrics.builder-test`,
  `hyperopen.portfolio.metrics.history-test`,
  `hyperopen.portfolio.metrics.quantstats-parity-test`
- Caller-side view tests remain important for route and render integration:
  `hyperopen.views.portfolio-view-test`,
  `hyperopen.views.portfolio-view-chart-test`,
  `hyperopen.views.portfolio-view-performance-metrics-test`,
  `hyperopen.views.portfolio-view-status-test`,
  `hyperopen.views.portfolio.vm.metrics-bridge-test`
- Final repo gates:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New tab, time-range, or benchmark normalization:
  `hyperopen.portfolio.actions`
- New route shape under `/portfolio`:
  `hyperopen.portfolio.routes`
- New metric formula, history parser, or metric catalog entry:
  `hyperopen.portfolio.metrics.*` behind the `hyperopen.portfolio.metrics` facade
- New worker payload normalization or benchmark alignment logic:
  `hyperopen.portfolio.application.metrics-bridge`
- New worker message handling:
  `hyperopen.portfolio.worker`
