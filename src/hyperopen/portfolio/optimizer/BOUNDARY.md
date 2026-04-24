---
owner: portfolio
status: draft
last_reviewed: 2026-04-23
---

# Portfolio Optimizer Boundary

This bounded context owns Portfolio Optimizer V1 draft state, scenario lifecycle,
current-portfolio snapshots, optimization request/result contracts, diagnostics,
rebalance preview shaping, execution orchestration, local scenario persistence,
optimizer-specific route query state, arbitrary-universe history assembly,
Black-Litterman prior source labeling, and optimizer-specific orderbook preview
planning.

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
- `hyperopen.api.gateway.market.*` or default API wrappers through
  optimizer-owned infrastructure clients for arbitrary candle and funding
  history. Application and domain namespaces should consume normalized history
  data, not API functions directly.
- `hyperopen.websocket.orderbook` data through state/effect seams for rebalance
  preview depth. Optimizer application code may plan subscriptions but must not
  mutate websocket runtime state directly.

The optimizer worker may depend on pure optimizer domain/application namespaces
and worker-safe optimizer infrastructure adapters such as the solver adapter.
Browser UI, IndexedDB, websocket, and exchange submit effects belong in
infrastructure clients and runtime effect adapters outside the worker runtime.
