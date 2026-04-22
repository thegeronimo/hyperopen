# Views Boundary

## Owns

- Reagent view composition, route shells, visible UI surfaces, local view-model shaping, render-only components, lazy surface module entrypoints, chart interop UI code, and shared UI helpers under `views/ui`.
- Route-level shells such as app, trade, portfolio, vaults, leaderboard, staking, funding comparison, and API-wallet surfaces.
- Surface-local VM and rendering helpers under `views/<surface>/`.

## Stable Public Seams

- `hyperopen.views.app-view`
  The top-level app-view route selection and surface orchestration seam.
- `hyperopen.views.trade-view`
  The trade-route composition seam, including trade layout and shared account-surface mounting.
- `hyperopen.views.portfolio-view`
  The portfolio route view seam for portfolio page composition.
- `hyperopen.views.active-asset-view`
  The active asset row and selector-wrapper seam.
- `hyperopen.views.asset-selector-view`
  The selector dropdown and wrapper render seam that delegates behavior to `hyperopen.asset-selector.*`.
- `hyperopen.views.account-surfaces-module`
  The lazy shared-account surface export seam for account-info, account-equity, and funding-action surfaces.
- `hyperopen.views.header-view` and `hyperopen.views.footer-view`
  The top-level shared shell surface seams.
- `hyperopen.views.funding-modal`, `hyperopen.views.spectate-mode-modal`, and `hyperopen.views.order-submit-confirmation-modal`
  Modal render seams that should consume feature-owned policy instead of owning business rules.
- `hyperopen.views.trading-chart.*`
  The trading chart render, toolbar, runtime state, and chart interop seam.
- `hyperopen.views.ui.*`
  Shared low-level view helpers for focus, popovers, toggles, dialog behavior, and DOM helpers.

## Dependency Rules

- Allowed:
  Views may depend on public seams from account, API, asset selector, funding, portfolio, runtime, trading, vaults, wallet, and websocket contexts to render state and dispatch actions.
- Allowed:
  Surface-local `views/<surface>/vm*` namespaces may shape UI-ready data when the shape is render-specific.
- Allowed:
  Chart interop namespaces may own imperative chart DOM/library integration that is inherently view-layer behavior.
- Forbidden:
  Do not put reusable non-rendering helpers in `hyperopen.views.*` when they belong in a domain, application, API, runtime, or feature-owned context.
- Forbidden:
  Do not import `hyperopen.views.*` from non-view contexts unless the dependency is a documented, time-bounded boundary exception.
- Forbidden:
  Do not duplicate canonical policy from trading, account, asset selector, funding, portfolio, vaults, wallet, websocket, or schema contracts in views.
- Forbidden:
  Do not make `app-view` or route shells own surface-specific behavior when a local `views/<surface>/` namespace already exists.

## Key Tests

- Top-level route and shared surfaces:
  `hyperopen.views.app-view-test`,
  `hyperopen.views.trade-view.layout-test`,
  `hyperopen.views.trade-view.render-cache-test`,
  `hyperopen.views.trade-view.startup-defer-test`,
  `hyperopen.views.trade-view.mobile-surface-test`,
  `hyperopen.views.portfolio-view-test`,
  `hyperopen.views.portfolio-view-hover-freeze-test`,
  `hyperopen.views.active-asset-view-test`,
  `hyperopen.views.asset-selector-view-test`,
  `hyperopen.views.header-view-test`,
  `hyperopen.views.footer-view-test`
- Surface families:
  `hyperopen.views.account-info-view-test`,
  `hyperopen.views.api-wallets-view-test`,
  `hyperopen.views.funding-modal-test`,
  `hyperopen.views.funding-modal-accessibility-test`,
  `hyperopen.views.spectate-mode-modal-test`,
  `hyperopen.views.leaderboard-view-test`,
  `hyperopen.views.staking-view-test`,
  `hyperopen.views.vaults.list-view-test`,
  `hyperopen.views.vaults.detail-view-test`
- Trading/chart surfaces:
  `hyperopen.views.trade.order-form-view-test`,
  `hyperopen.views.trade.order-form-vm-test`,
  `hyperopen.views.trading-chart.core-test`,
  `hyperopen.views.trading-chart.runtime-test`,
  `hyperopen.views.trading-chart.utils.chart-interop-test`,
  `hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays-test`,
  `hyperopen.views.trading-chart.utils.chart-interop.position-overlays-test`
- Shared UI helpers:
  `hyperopen.views.ui.anchored-popover-test`,
  `hyperopen.views.ui.dialog-focus-test`,
  `hyperopen.views.ui.toggle-test`
- UI-facing changes also require the governed browser-QA accounting in `docs/BROWSER_TESTING.md`.
- Final repo gates:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New app route selection or top-level surface orchestration:
  `hyperopen.views.app-view`
- New trade route layout, mobile/desktop shell, or account-surface mounting:
  `hyperopen.views.trade-view` or `hyperopen.views.trade-view.*`
- New order-form rendering, controls, or render-specific VM data:
  `hyperopen.views.trade.*`, while pure order policy stays in `hyperopen.trading.*`
- New selector rendering or row controls:
  `hyperopen.views.asset-selector-view` or `hyperopen.views.asset-selector.*`, while search, sorting, market identity, cache, and effects stay in `hyperopen.asset-selector.*`
- New chart rendering, toolbar, or chart-library DOM integration:
  `hyperopen.views.trading-chart.*`
- New portfolio, vault, staking, leaderboard, funding, or account-info visual surface:
  the nearest `hyperopen.views.<surface>*` namespace, delegating non-rendering policy to the owning bounded context
- New reusable popover, focus, dialog, toggle, or DOM view helper:
  `hyperopen.views.ui.*`
