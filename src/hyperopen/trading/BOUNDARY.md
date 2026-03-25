# Trading Boundary

## Owns

- Order-ticket state shape, normalization, TP/SL math and policy, deterministic order-form transitions, grouped order-form context shaping, and order-type capability registry.

## Stable Public Seams

- `hyperopen.trading.order-form-state`
  Canonical order-ticket defaults and normalization.
- `hyperopen.trading.order-form-transitions`
  Canonical deterministic field transitions and cross-field synchronization.
- `hyperopen.trading.order-form-tpsl-policy`
  Canonical TP/SL conversion, trigger, and unit policy.
- `hyperopen.trading.order-form-application`
  The grouped context seam consumed by trade VM and views.
- `hyperopen.trading.order-type-registry`
  The canonical order-type capability registry.

## Dependency Rules

- Allowed:
  Trading namespaces may depend on `hyperopen.state.trading`, `hyperopen.domain.trading`, and generic utility helpers.
- Allowed:
  Views may depend on trading seams.
- Allowed:
  Trading code should stay pure and deterministic so it can be reused by view models, tests, and runtime wiring.
- Forbidden:
  Do not add browser, API, websocket, or other side effects here.
- Forbidden:
  Do not import `hyperopen.views.*` into `hyperopen.trading.*`.
- Forbidden:
  Do not duplicate TP/SL math or order-type capability rules in view code when a trading-owned seam already exists.

## Key Tests

- Key namespaces:
  `hyperopen.trading.order-form-tpsl-policy-test`,
  `hyperopen.trading.order-form-transitions-test`
- Caller-side regression tests usually matter too:
  `hyperopen.views.trade.order-form-vm-test`,
  `hyperopen.views.trade.order-form-component-sections-test`,
  `hyperopen.views.trade.order-form-view-test`
- Final repo gates:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New default field, normalization rule, or persisted draft behavior:
  `hyperopen.trading.order-form-state`
- New deterministic field update or cross-field sync:
  `hyperopen.trading.order-form-transitions`
- New TP/SL conversion, offset, or readiness rule:
  `hyperopen.trading.order-form-tpsl-policy`
- New grouped VM-facing projection of existing order-form state:
  `hyperopen.trading.order-form-application`
- New order-type capability or rendering flag:
  `hyperopen.trading.order-type-registry`
