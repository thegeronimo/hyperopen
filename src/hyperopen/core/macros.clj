(ns hyperopen.core.macros)

(defmacro def-public-action-aliases
  [actions-alias publics]
  (let [alias-name (name actions-alias)]
    `(do
       ~@(for [sym publics]
           `(def ~sym ~(symbol alias-name (name sym)))))))

(def ^:private core-effect-adapter-publics
  '[append-diagnostics-event!
    sync-websocket-health!
    save
    save-many
    local-storage-set
    local-storage-set-json
    schedule-animation-frame!
    flush-queued-asset-icon-statuses!
    queue-asset-icon-status
    push-state
    replace-state
    fetch-candle-snapshot
    init-websocket
    persist-asset-selector-markets-cache!
    restore-asset-selector-markets-cache!
    persist-active-market-display!
    load-active-market-display
    subscribe-active-asset
    unsubscribe-active-asset
    subscribe-orderbook
    subscribe-trades
    unsubscribe-orderbook
    unsubscribe-trades
    subscribe-webdata2
    unsubscribe-webdata2
    connect-wallet
    disconnect-wallet
    set-agent-storage-mode
    copy-wallet-address
    reconnect-websocket
    refresh-websocket-health
    ws-reset-subscriptions
    confirm-ws-diagnostics-reveal
    copy-websocket-diagnostics
    restore-active-asset!
    api-submit-order
    api-cancel-order
    fetch-asset-selector-markets-effect
    api-load-user-data-effect])

(def ^:private core-action-adapter-publics
  '[init-websockets
    subscribe-to-asset
    subscribe-to-webdata2
    connect-wallet-action
    disconnect-wallet-action
    should-auto-enable-agent-trading?
    handle-wallet-connected
    enable-agent-trading
    enable-agent-trading-action
    set-agent-storage-mode-action
    copy-wallet-address-action
    reconnect-websocket-action
    toggle-ws-diagnostics
    close-ws-diagnostics
    toggle-ws-diagnostics-sensitive
    ws-diagnostics-reconnect-now
    ws-diagnostics-copy
    set-show-surface-freshness-cues
    toggle-show-surface-freshness-cues
    ws-diagnostics-reset-market-subscriptions
    ws-diagnostics-reset-orders-subscriptions
    ws-diagnostics-reset-all-subscriptions
    navigate])

(def ^:private core-public-action-publics
  '[toggle-asset-dropdown
    close-asset-dropdown
    select-asset
    update-asset-search
    update-asset-selector-sort
    toggle-asset-selector-strict
    toggle-asset-favorite
    set-asset-selector-favorites-only
    set-asset-selector-tab
    handle-asset-selector-shortcut
    set-asset-selector-scroll-top
    increase-asset-selector-render-limit
    show-all-asset-selector-markets
    maybe-increase-asset-selector-render-limit
    refresh-asset-markets
    apply-asset-icon-status-updates
    mark-loaded-asset-icon
    mark-missing-asset-icon
    restore-open-orders-sort-settings!
    restore-order-history-pagination-settings!
    restore-funding-history-pagination-settings!
    restore-trade-history-pagination-settings!
    restore-chart-options!
    restore-orderbook-ui!
    restore-agent-storage-mode!
    restore-ui-font-preference!
    toggle-timeframes-dropdown
    select-chart-timeframe
    toggle-chart-type-dropdown
    select-chart-type
    toggle-indicators-dropdown
    update-indicators-search
    toggle-orderbook-size-unit-dropdown
    select-orderbook-size-unit
    toggle-orderbook-price-aggregation-dropdown
    select-orderbook-price-aggregation
    select-orderbook-tab
    add-indicator
    remove-indicator
    update-indicator-period
    show-volume-indicator
    hide-volume-indicator
    select-account-info-tab
    set-funding-history-filters
    toggle-funding-history-filter-open
    toggle-funding-history-filter-coin
    reset-funding-history-filter-draft
    apply-funding-history-filters
    view-all-funding-history
    export-funding-history-csv
    sort-positions
    sort-balances
    sort-open-orders
    sort-funding-history
    set-funding-history-page-size
    set-funding-history-page
    next-funding-history-page
    prev-funding-history-page
    set-funding-history-page-input
    apply-funding-history-page-input
    handle-funding-history-page-input-keydown
    set-trade-history-page-size
    set-trade-history-page
    next-trade-history-page
    prev-trade-history-page
    set-trade-history-page-input
    apply-trade-history-page-input
    handle-trade-history-page-input-keydown
    sort-trade-history
    sort-order-history
    toggle-order-history-filter-open
    set-order-history-status-filter
    set-order-history-page-size
    set-order-history-page
    next-order-history-page
    prev-order-history-page
    set-order-history-page-input
    apply-order-history-page-input
    handle-order-history-page-input-keydown
    refresh-order-history
    set-hide-small-balances
    select-order-entry-mode
    select-pro-order-type
    toggle-pro-order-type-dropdown
    close-pro-order-type-dropdown
    handle-pro-order-type-dropdown-keydown
    set-order-ui-leverage
    set-order-size-percent
    set-order-size-display
    set-order-size-input-mode
    focus-order-price-input
    blur-order-price-input
    set-order-price-to-mid
    toggle-order-tpsl-panel
    update-order-form
    submit-order
    prune-canceled-open-orders
    cancel-order
    load-user-data
    set-funding-modal])

(def ^:private core-legacy-publics
  (vec
   (concat
    core-effect-adapter-publics
    core-action-adapter-publics
    core-public-action-publics)))

(defmacro def-core-compat-exports
  []
  `(do
     (def-public-action-aliases effect-adapters ~core-effect-adapter-publics)
     (def-public-action-aliases action-adapters ~core-action-adapter-publics)
     (def-public-action-aliases public-actions ~core-public-action-publics)))

(defmacro def-core-legacy-exports
  [compat-alias]
  `(def-public-action-aliases ~compat-alias ~core-legacy-publics))
