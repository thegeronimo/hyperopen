---
owner: portfolio
status: draft
last_reviewed: 2026-04-23
---

# Portfolio Optimizer Boundary

This bounded context owns Portfolio Optimizer V1 draft state, scenario lifecycle,
current-portfolio snapshots, optimization request/result contracts, diagnostics,
rebalance preview shaping, execution orchestration, local scenario persistence,
and optimizer-specific route query state.

The domain and application namespaces under this directory must not depend on
`hyperopen.views.*`. Views may consume optimizer view models, but optimizer math
and account/exposure assembly must stay reusable from workers and tests.

Allowed upstream seams:

- `hyperopen.account.*` for effective account identity, Spectate Mode, and
  read-only mutation rules.
- `hyperopen.asset-selector.*` and market/domain namespaces for resolving
  markets and instrument metadata.
- `hyperopen.trading.*`, `hyperopen.order.*`, and
  `hyperopen.api.gateway.orders.*` for execution preview and submit integration.
- `hyperopen.platform.*` and infrastructure adapters for browser persistence and
  worker communication.

The optimizer worker may depend on pure optimizer domain namespaces only. Browser,
IndexedDB, websocket, and exchange submit effects belong in infrastructure and
runtime effect adapters.
