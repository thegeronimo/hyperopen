# Asset Selector Boundary

## Owns

- Canonical selector market identity, market matching, coin-to-market resolution, market classification, selector search/sort/windowing, selector actions, selector preferences, favorites, active tab and strict-mode state, funding tooltip drafts, icon status batching, market cache normalization/persistence, active-market display persistence, and live websocket market projection patches.
- Non-rendering selector behavior under `hyperopen.asset-selector.*`.
- Rendering and DOM virtualization under `hyperopen.views.asset-selector*`.

## Stable Public Seams

- `hyperopen.asset-selector.markets`
  The market identity, market-key, alias matching, coin resolution, classification, and perp/spot market construction seam.
- `hyperopen.asset-selector.query`
  The pure search, tab filtering, sorting, render-limit, scroll, virtual-window, and visible-market calculation seam.
- `hyperopen.asset-selector.actions`
  The selector interaction and effect-list seam for opening/closing, selecting, search, sorting, strict mode, favorites, tabs, scrolling, render limits, live subscriptions, icon status, and funding tooltip drafts.
- `hyperopen.asset-selector.markets-cache`
  The market cache normalization, cache build, persist, load, and restore seam.
- `hyperopen.asset-selector.active-market-cache`
  The active market display persistence seam.
- `hyperopen.asset-selector.market-live-projection`
  The pure websocket active-market context patching seam.
- `hyperopen.asset-selector.settings`
  The persisted selector sort and preference restore seam.
- `hyperopen.asset-selector.icon-status-runtime`
  The queued icon status update seam.
- `hyperopen.views.asset-selector-view` and `hyperopen.views.asset-selector.*`
  The render, row, control, processing, and runtime DOM seam for selector UI.

## Dependency Rules

- Allowed:
  `hyperopen.asset-selector.*` may depend on pure utilities, API market shapes, funding draft helpers, websocket projection data, and browser storage only in cache/settings owners.
- Allowed:
  `views/asset_selector/*` may render selector state and dispatch selector actions.
- Allowed:
  Runtime effect adapters may call selector cache, active-market persistence, and subscription sync seams.
- Forbidden:
  Do not put market identity, matching, filtering, sorting, cache, or websocket patching rules in `views/asset_selector/*`.
- Forbidden:
  Do not make `market-live-projection` depend on browser APIs or runtime mutation; it must remain pure and deterministic.
- Forbidden:
  Do not change render-limit, row-height, overscan, or scroll semantics in only one layer; update `list-metrics`, `query`, view runtime, and tests together.
- Forbidden:
  Do not bypass selector actions when UI interactions need ordered effects, storage persistence, or live subscription synchronization.

## Key Tests

- Non-rendering selector behavior:
  `hyperopen.asset-selector.actions-test`,
  `hyperopen.asset-selector.actions-property-test`,
  `hyperopen.asset-selector.markets-test`,
  `hyperopen.asset-selector.markets-cache-test`,
  `hyperopen.asset-selector.market-live-projection-test`,
  `hyperopen.asset-selector.active-market-cache-test`,
  `hyperopen.asset-selector.settings-test`,
  `hyperopen.asset-selector.funding-drafts-test`,
  `hyperopen.asset-selector.icon-status-runtime-test`
- View/render selector behavior:
  `hyperopen.views.asset-selector-view-test`,
  `hyperopen.views.asset-selector.controls-test`,
  `hyperopen.views.asset-selector.rows-test`,
  `hyperopen.views.asset-selector.runtime-test`,
  `hyperopen.views.asset-selector.processing-test`
- Runtime and websocket integration:
  `hyperopen.runtime.effect-adapters.asset-selector-test`,
  `hyperopen.core-bootstrap.asset-selector-actions-test`,
  `hyperopen.websocket.asset-selector-coverage-test`
- Final repo gates:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New market identity, alias, classification, HIP-3, spot, or perp construction rule:
  `hyperopen.asset-selector.markets`
- New search, sort, tab filter, visible market, render limit, or virtual-window rule:
  `hyperopen.asset-selector.query` and, for shared sizing constants, `hyperopen.asset-selector.list-metrics`
- New selector action, persistence effect ordering, favorite, strict-mode, active tab, scroll, render-limit, shortcut, or subscription-sync behavior:
  `hyperopen.asset-selector.actions`
- New selector market cache shape or restore behavior:
  `hyperopen.asset-selector.markets-cache`
- New active market display persistence:
  `hyperopen.asset-selector.active-market-cache`
- New websocket active-market patching behavior:
  `hyperopen.asset-selector.market-live-projection`
- New selector row, controls, layout, rendering, DOM virtualization, or scroll timer behavior:
  `hyperopen.views.asset-selector-view` or `hyperopen.views.asset-selector.*`
- New asset icon status batching:
  `hyperopen.asset-selector.icon-status-runtime`
