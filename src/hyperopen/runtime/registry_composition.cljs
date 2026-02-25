(ns hyperopen.runtime.registry-composition)

(defn- core-effect-handlers
  [{:keys [save
           save-many
           local-storage-set
           local-storage-set-json]}
   {:keys [queue-asset-icon-status]}
   {:keys [push-state
           replace-state]}]
  {:save save
   :save-many save-many
   :local-storage-set local-storage-set
   :local-storage-set-json local-storage-set-json
   :queue-asset-icon-status queue-asset-icon-status
   :push-state push-state
   :replace-state replace-state})

(defn- websocket-effect-handlers
  [{:keys [init-websocket
           subscribe-active-asset
           subscribe-orderbook
           subscribe-trades
           subscribe-webdata2
           fetch-candle-snapshot
           unsubscribe-active-asset
           unsubscribe-orderbook
           unsubscribe-trades
           unsubscribe-webdata2
           reconnect-websocket
           refresh-websocket-health]}
   {:keys [confirm-ws-diagnostics-reveal
           copy-websocket-diagnostics
           ws-reset-subscriptions]}]
  {:init-websocket init-websocket
   :subscribe-active-asset subscribe-active-asset
   :subscribe-orderbook subscribe-orderbook
   :subscribe-trades subscribe-trades
   :subscribe-webdata2 subscribe-webdata2
   :fetch-candle-snapshot fetch-candle-snapshot
   :unsubscribe-active-asset unsubscribe-active-asset
   :unsubscribe-orderbook unsubscribe-orderbook
   :unsubscribe-trades unsubscribe-trades
   :unsubscribe-webdata2 unsubscribe-webdata2
   :reconnect-websocket reconnect-websocket
   :refresh-websocket-health refresh-websocket-health
   :confirm-ws-diagnostics-reveal confirm-ws-diagnostics-reveal
   :copy-websocket-diagnostics copy-websocket-diagnostics
   :ws-reset-subscriptions ws-reset-subscriptions})

(defn- wallet-effect-handlers
  [{:keys [connect-wallet
           disconnect-wallet
           enable-agent-trading
           set-agent-storage-mode
           copy-wallet-address]}]
  {:connect-wallet connect-wallet
   :disconnect-wallet disconnect-wallet
   :enable-agent-trading enable-agent-trading
   :set-agent-storage-mode set-agent-storage-mode
   :copy-wallet-address copy-wallet-address})

(defn- trading-effect-handlers
  [{:keys [api-submit-order
           api-cancel-order
           api-submit-position-tpsl]}]
  {:api-submit-order api-submit-order
   :api-cancel-order api-cancel-order
   :api-submit-position-tpsl api-submit-position-tpsl})

(defn- api-effect-handlers
  [{:keys [fetch-asset-selector-markets
           api-fetch-user-funding-history
           api-fetch-historical-orders
           export-funding-history-csv
           api-load-user-data]}]
  {:fetch-asset-selector-markets fetch-asset-selector-markets
   :api-fetch-user-funding-history api-fetch-user-funding-history
   :api-fetch-historical-orders api-fetch-historical-orders
   :export-funding-history-csv export-funding-history-csv
   :api-load-user-data api-load-user-data})

(defn runtime-effect-handlers
  [{:keys [storage
           asset-selector
           navigation
           websocket
           diagnostics
           wallet
           orders
           api]}]
  (merge
   (core-effect-handlers storage asset-selector navigation)
   (websocket-effect-handlers websocket diagnostics)
   (wallet-effect-handlers wallet)
   (trading-effect-handlers orders)
   (api-effect-handlers api)))

(defn- runtime-core-action-handlers
  [{:keys [init-websockets
           subscribe-to-asset
           subscribe-to-webdata2
           reconnect-websocket-action
           navigate]}]
  {:init-websockets init-websockets
   :subscribe-to-asset subscribe-to-asset
   :subscribe-to-webdata2 subscribe-to-webdata2
   :reconnect-websocket-action reconnect-websocket-action
   :navigate navigate})

(defn- wallet-action-handlers
  [{:keys [connect-wallet-action
           disconnect-wallet-action
           enable-agent-trading-action
           set-agent-storage-mode-action
           copy-wallet-address-action]}]
  {:connect-wallet-action connect-wallet-action
   :disconnect-wallet-action disconnect-wallet-action
   :enable-agent-trading-action enable-agent-trading-action
   :set-agent-storage-mode-action set-agent-storage-mode-action
   :copy-wallet-address-action copy-wallet-address-action})

(defn- websocket-diagnostics-action-handlers
  [{:keys [toggle-ws-diagnostics
           close-ws-diagnostics
           toggle-ws-diagnostics-sensitive
           ws-diagnostics-reconnect-now
           ws-diagnostics-copy
           set-show-surface-freshness-cues
           toggle-show-surface-freshness-cues
           ws-diagnostics-reset-market-subscriptions
           ws-diagnostics-reset-orders-subscriptions
           ws-diagnostics-reset-all-subscriptions]}]
  {:toggle-ws-diagnostics toggle-ws-diagnostics
   :close-ws-diagnostics close-ws-diagnostics
   :toggle-ws-diagnostics-sensitive toggle-ws-diagnostics-sensitive
   :ws-diagnostics-reconnect-now ws-diagnostics-reconnect-now
   :ws-diagnostics-copy ws-diagnostics-copy
   :set-show-surface-freshness-cues set-show-surface-freshness-cues
   :toggle-show-surface-freshness-cues toggle-show-surface-freshness-cues
   :ws-diagnostics-reset-market-subscriptions ws-diagnostics-reset-market-subscriptions
   :ws-diagnostics-reset-orders-subscriptions ws-diagnostics-reset-orders-subscriptions
   :ws-diagnostics-reset-all-subscriptions ws-diagnostics-reset-all-subscriptions})

(defn- asset-selector-action-handlers
  [{:keys [toggle-asset-dropdown
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
           mark-loaded-asset-icon
           mark-missing-asset-icon]}]
  {:toggle-asset-dropdown toggle-asset-dropdown
   :close-asset-dropdown close-asset-dropdown
   :select-asset select-asset
   :update-asset-search update-asset-search
   :update-asset-selector-sort update-asset-selector-sort
   :toggle-asset-selector-strict toggle-asset-selector-strict
   :toggle-asset-favorite toggle-asset-favorite
   :set-asset-selector-favorites-only set-asset-selector-favorites-only
   :set-asset-selector-tab set-asset-selector-tab
   :handle-asset-selector-shortcut handle-asset-selector-shortcut
   :set-asset-selector-scroll-top set-asset-selector-scroll-top
   :increase-asset-selector-render-limit increase-asset-selector-render-limit
   :show-all-asset-selector-markets show-all-asset-selector-markets
   :maybe-increase-asset-selector-render-limit maybe-increase-asset-selector-render-limit
   :refresh-asset-markets refresh-asset-markets
   :mark-loaded-asset-icon mark-loaded-asset-icon
   :mark-missing-asset-icon mark-missing-asset-icon})

(defn- chart-and-orderbook-action-handlers
  [{:keys [toggle-timeframes-dropdown
           select-chart-timeframe
           toggle-chart-type-dropdown
           select-chart-type
           toggle-indicators-dropdown
           update-indicators-search
           toggle-portfolio-summary-scope-dropdown
           select-portfolio-summary-scope
           toggle-portfolio-summary-time-range-dropdown
           select-portfolio-summary-time-range
           select-portfolio-chart-tab
           toggle-orderbook-size-unit-dropdown
           select-orderbook-size-unit
           toggle-orderbook-price-aggregation-dropdown
           select-orderbook-price-aggregation
           select-orderbook-tab
           add-indicator
           remove-indicator
           update-indicator-period
           show-volume-indicator
           hide-volume-indicator]}]
  {:toggle-timeframes-dropdown toggle-timeframes-dropdown
   :select-chart-timeframe select-chart-timeframe
   :toggle-chart-type-dropdown toggle-chart-type-dropdown
   :select-chart-type select-chart-type
   :toggle-indicators-dropdown toggle-indicators-dropdown
   :update-indicators-search update-indicators-search
   :toggle-portfolio-summary-scope-dropdown toggle-portfolio-summary-scope-dropdown
   :select-portfolio-summary-scope select-portfolio-summary-scope
   :toggle-portfolio-summary-time-range-dropdown toggle-portfolio-summary-time-range-dropdown
   :select-portfolio-summary-time-range select-portfolio-summary-time-range
   :select-portfolio-chart-tab select-portfolio-chart-tab
   :toggle-orderbook-size-unit-dropdown toggle-orderbook-size-unit-dropdown
   :select-orderbook-size-unit select-orderbook-size-unit
   :toggle-orderbook-price-aggregation-dropdown toggle-orderbook-price-aggregation-dropdown
   :select-orderbook-price-aggregation select-orderbook-price-aggregation
   :select-orderbook-tab select-orderbook-tab
   :add-indicator add-indicator
   :remove-indicator remove-indicator
   :update-indicator-period update-indicator-period
   :show-volume-indicator show-volume-indicator
   :hide-volume-indicator hide-volume-indicator})

(defn- account-history-action-handlers
  [{:keys [select-account-info-tab
           set-funding-history-filters
           toggle-funding-history-filter-open
           toggle-funding-history-filter-coin
           reset-funding-history-filter-draft
           apply-funding-history-filters
           view-all-funding-history
           export-funding-history-csv
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
           toggle-trade-history-direction-filter-open
           set-trade-history-direction-filter
           sort-positions
           toggle-positions-direction-filter-open
           set-positions-direction-filter
           sort-balances
           sort-open-orders
           toggle-open-orders-direction-filter-open
           set-open-orders-direction-filter
           sort-funding-history
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
           open-position-tpsl-modal
           close-position-tpsl-modal
           handle-position-tpsl-modal-keydown
           set-position-tpsl-modal-field
           set-position-tpsl-configure-amount
           set-position-tpsl-limit-price
           submit-position-tpsl]}]
  {:select-account-info-tab select-account-info-tab
   :set-funding-history-filters set-funding-history-filters
   :toggle-funding-history-filter-open toggle-funding-history-filter-open
   :toggle-funding-history-filter-coin toggle-funding-history-filter-coin
   :reset-funding-history-filter-draft reset-funding-history-filter-draft
   :apply-funding-history-filters apply-funding-history-filters
   :view-all-funding-history view-all-funding-history
   :export-funding-history-csv export-funding-history-csv
   :set-funding-history-page-size set-funding-history-page-size
   :set-funding-history-page set-funding-history-page
   :next-funding-history-page next-funding-history-page
   :prev-funding-history-page prev-funding-history-page
   :set-funding-history-page-input set-funding-history-page-input
   :apply-funding-history-page-input apply-funding-history-page-input
   :handle-funding-history-page-input-keydown handle-funding-history-page-input-keydown
   :set-trade-history-page-size set-trade-history-page-size
   :set-trade-history-page set-trade-history-page
   :next-trade-history-page next-trade-history-page
   :prev-trade-history-page prev-trade-history-page
   :set-trade-history-page-input set-trade-history-page-input
   :apply-trade-history-page-input apply-trade-history-page-input
   :handle-trade-history-page-input-keydown handle-trade-history-page-input-keydown
   :sort-trade-history sort-trade-history
   :toggle-trade-history-direction-filter-open toggle-trade-history-direction-filter-open
   :set-trade-history-direction-filter set-trade-history-direction-filter
   :sort-positions sort-positions
   :toggle-positions-direction-filter-open toggle-positions-direction-filter-open
   :set-positions-direction-filter set-positions-direction-filter
   :sort-balances sort-balances
   :sort-open-orders sort-open-orders
   :toggle-open-orders-direction-filter-open toggle-open-orders-direction-filter-open
   :set-open-orders-direction-filter set-open-orders-direction-filter
   :sort-funding-history sort-funding-history
   :sort-order-history sort-order-history
   :toggle-order-history-filter-open toggle-order-history-filter-open
   :set-order-history-status-filter set-order-history-status-filter
   :set-order-history-page-size set-order-history-page-size
   :set-order-history-page set-order-history-page
   :next-order-history-page next-order-history-page
   :prev-order-history-page prev-order-history-page
   :set-order-history-page-input set-order-history-page-input
   :apply-order-history-page-input apply-order-history-page-input
   :handle-order-history-page-input-keydown handle-order-history-page-input-keydown
   :refresh-order-history refresh-order-history
   :set-hide-small-balances set-hide-small-balances
   :open-position-tpsl-modal open-position-tpsl-modal
   :close-position-tpsl-modal close-position-tpsl-modal
   :handle-position-tpsl-modal-keydown handle-position-tpsl-modal-keydown
   :set-position-tpsl-modal-field set-position-tpsl-modal-field
   :set-position-tpsl-configure-amount set-position-tpsl-configure-amount
   :set-position-tpsl-limit-price set-position-tpsl-limit-price
   :submit-position-tpsl submit-position-tpsl})

(defn- order-action-handlers
  [{:keys [select-order-entry-mode
           select-pro-order-type
           toggle-pro-order-type-dropdown
           close-pro-order-type-dropdown
           handle-pro-order-type-dropdown-keydown
           toggle-size-unit-dropdown
           close-size-unit-dropdown
           handle-size-unit-dropdown-keydown
           toggle-tpsl-unit-dropdown
           close-tpsl-unit-dropdown
           handle-tpsl-unit-dropdown-keydown
           toggle-tif-dropdown
           close-tif-dropdown
           handle-tif-dropdown-keydown
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
           cancel-order
           load-user-data
           set-funding-modal]}]
  {:select-order-entry-mode select-order-entry-mode
   :select-pro-order-type select-pro-order-type
   :toggle-pro-order-type-dropdown toggle-pro-order-type-dropdown
   :close-pro-order-type-dropdown close-pro-order-type-dropdown
   :handle-pro-order-type-dropdown-keydown handle-pro-order-type-dropdown-keydown
   :toggle-size-unit-dropdown toggle-size-unit-dropdown
   :close-size-unit-dropdown close-size-unit-dropdown
   :handle-size-unit-dropdown-keydown handle-size-unit-dropdown-keydown
   :toggle-tpsl-unit-dropdown toggle-tpsl-unit-dropdown
   :close-tpsl-unit-dropdown close-tpsl-unit-dropdown
   :handle-tpsl-unit-dropdown-keydown handle-tpsl-unit-dropdown-keydown
   :toggle-tif-dropdown toggle-tif-dropdown
   :close-tif-dropdown close-tif-dropdown
   :handle-tif-dropdown-keydown handle-tif-dropdown-keydown
   :set-order-ui-leverage set-order-ui-leverage
   :set-order-size-percent set-order-size-percent
   :set-order-size-display set-order-size-display
   :set-order-size-input-mode set-order-size-input-mode
   :focus-order-price-input focus-order-price-input
   :blur-order-price-input blur-order-price-input
   :set-order-price-to-mid set-order-price-to-mid
   :toggle-order-tpsl-panel toggle-order-tpsl-panel
   :update-order-form update-order-form
   :submit-order submit-order
   :cancel-order cancel-order
   :load-user-data load-user-data
   :set-funding-modal set-funding-modal})

(defn runtime-action-handlers
  [{:keys [core
           wallet
           diagnostics
           asset-selector
           chart
           account-history
           orders]}]
  (merge
   (runtime-core-action-handlers core)
   (wallet-action-handlers wallet)
   (websocket-diagnostics-action-handlers diagnostics)
   (asset-selector-action-handlers asset-selector)
   (chart-and-orderbook-action-handlers chart)
   (account-history-action-handlers account-history)
   (order-action-handlers orders)))

(defn runtime-registration-deps
  [{:keys [register-effects!
           register-actions!
           register-system-state!
           register-placeholders!]}
   {:keys [effect-deps action-deps]}]
  {:register-effects! register-effects!
   :effect-handlers (runtime-effect-handlers effect-deps)
   :register-actions! register-actions!
   :action-handlers (runtime-action-handlers action-deps)
   :register-system-state! register-system-state!
   :register-placeholders! register-placeholders!})
