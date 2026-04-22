# Schema Boundary

## Owns

- Development-time boundary contracts for runtime actions, runtime effects, app-state projection shape, provider messages, signed exchange payloads, exchange responses, runtime registration IDs, order-form commands, and focused domain payload or view-model contracts.
- The canonical action/effect registration catalog consumed by runtime registry and validation.
- Reusable assertion and shape helpers under `contracts/`.

## Stable Public Seams

- `hyperopen.schema.contracts`
  The stable contract facade for validation gating, action/effect argument assertions, emitted-effect assertions, app-state assertions, provider message assertions, signed payload assertions, exchange response assertions, and contracted ID queries.
- `hyperopen.schema.runtime-registration-catalog`
  The canonical catalog seam for action/effect binding rows, IDs, and handler keys.
- `hyperopen.schema.runtime-registration.*`
  Feature-owned registration rows that feed the catalog.
- `hyperopen.schema.contracts.action-args` and `hyperopen.schema.contracts.effect-args`
  The action/effect argument specification maps.
- `hyperopen.schema.contracts.assertions`
  Shared assertion implementation for schema boundary checks.
- `hyperopen.schema.order-form-command-catalog`
  The canonical order-form command to runtime-action mapping seam.
- `hyperopen.schema.order-form-command-contracts`
  The command-shape and runtime-action list validation seam.
- Focused contract namespaces such as `order-request-contracts`, `vault-transfer-contracts`, `trading-submit-policy-contracts`, `funding-modal-contracts`, `order-form-contracts`, `order-form-ownership-contracts`, `portfolio-returns-contracts`, `portfolio-returns-normalization-contracts`, `chart-interop-contracts`, `effect-order-contracts`, and `api-market-contracts`.

## Dependency Rules

- Allowed:
  Schema contract namespaces may depend on generic contract helpers and pure data/predicate logic.
- Allowed:
  Runtime validation, runtime registry composition, tests, and feature tests may depend on schema contracts to fail fast on shape drift.
- Allowed:
  Feature-specific contract modules may encode expected payload, projection, or view-model shape for the owning feature seam.
- Forbidden:
  Do not put browser, API, websocket, storage, or runtime side effects in schema namespaces.
- Forbidden:
  Do not maintain parallel action/effect ID lists outside `hyperopen.schema.runtime-registration-catalog`.
- Forbidden:
  Do not duplicate schema contract predicates inline in callers when a focused contract namespace already owns the shape.
- Forbidden:
  Do not make `hyperopen.schema.contracts` own every focused rule directly; keep detailed shape rules in focused contract modules and expose stable facade hooks where needed.

## Key Tests

- Top-level contract and coverage gates:
  `hyperopen.schema.contracts-test`,
  `hyperopen.schema.contracts-coverage-test`
- Low-level contract helpers:
  `hyperopen.schema.contracts.action-args-test`,
  `hyperopen.schema.contracts.effect-args-test`,
  `hyperopen.schema.contracts.assertions-test`,
  `hyperopen.schema.contracts.common-test`,
  `hyperopen.schema.contracts.state-test`
- Focused contract suites:
  `hyperopen.schema.api-market-contracts-test`,
  `hyperopen.schema.order-request-contracts-test`,
  `hyperopen.schema.vault-transfer-contracts-test`,
  `hyperopen.schema.chart-interop-contracts-test`,
  `hyperopen.schema.order-form-command-catalog-test`
- Cross-boundary contract users:
  `hyperopen.runtime.validation-test`,
  `hyperopen.runtime.registry-composition-test`,
  `hyperopen.runtime.wiring-test`,
  `hyperopen.runtime.effect-order-contract-test`,
  `hyperopen.runtime.effect-order-contract-formal-conformance-test`,
  `hyperopen.views.account-info.table-contract-test`,
  `hyperopen.views.trade.order-form-view.styling-contract-test`
- Final repo gates:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New runtime action/effect registration row:
  the owning `hyperopen.schema.runtime-registration.*` namespace, then verify `hyperopen.schema.runtime-registration-catalog`
- New action/effect argument shape:
  `hyperopen.schema.contracts.action-args` or `hyperopen.schema.contracts.effect-args`, plus facade/coverage updates if needed
- New app-state, provider, signed payload, emitted-effect, or exchange response assertion:
  `hyperopen.schema.contracts.assertions` and `hyperopen.schema.contracts`
- New order-form command or command/action mapping:
  `hyperopen.schema.order-form-command-catalog` and `hyperopen.schema.order-form-command-contracts`
- New focused payload, VM, formal vector, or projection contract:
  the relevant focused `hyperopen.schema.*-contracts` namespace
- New runtime registration drift rule:
  `hyperopen.schema.runtime-registration-catalog`, `hyperopen.schema.contracts`, and the catalog/registry coverage tests
