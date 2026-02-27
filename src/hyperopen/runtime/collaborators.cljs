(ns hyperopen.runtime.collaborators
  (:require [hyperopen.account.history.actions :as account-history-actions]
            [hyperopen.account.history.effects :as account-history-effects]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.chart.actions :as chart-actions]
            [hyperopen.chart.settings :as chart-settings]
            [hyperopen.order.actions :as order-actions]
            [hyperopen.orderbook.actions :as orderbook-actions]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.vaults.actions :as vault-actions]
            [hyperopen.vaults.effects :as vault-effects]
            [hyperopen.wallet.actions :as wallet-actions]))

(defn- merge-nested
  [left right]
  (merge-with (fn [left-value right-value]
                (if (and (map? left-value)
                         (map? right-value))
                  (merge-nested left-value right-value)
                  right-value))
              (or left {})
              (or right {})))

(defn- wallet-action-deps []
  {:connect-wallet-action wallet-actions/connect-wallet-action
   :disconnect-wallet-action wallet-actions/disconnect-wallet-action
   :copy-wallet-address-action wallet-actions/copy-wallet-address-action})

(defn- asset-selector-action-deps []
  {:toggle-asset-dropdown asset-actions/toggle-asset-dropdown
   :close-asset-dropdown asset-actions/close-asset-dropdown
   :select-asset asset-actions/select-asset
   :update-asset-search asset-actions/update-asset-search
   :update-asset-selector-sort asset-actions/update-asset-selector-sort
   :toggle-asset-selector-strict asset-actions/toggle-asset-selector-strict
   :toggle-asset-favorite asset-actions/toggle-asset-favorite
   :set-asset-selector-favorites-only asset-actions/set-asset-selector-favorites-only
   :set-asset-selector-tab asset-actions/set-asset-selector-tab
   :handle-asset-selector-shortcut asset-actions/handle-asset-selector-shortcut
   :set-asset-selector-scroll-top asset-actions/set-asset-selector-scroll-top
   :increase-asset-selector-render-limit asset-actions/increase-asset-selector-render-limit
   :show-all-asset-selector-markets asset-actions/show-all-asset-selector-markets
   :maybe-increase-asset-selector-render-limit asset-actions/maybe-increase-asset-selector-render-limit
   :mark-loaded-asset-icon asset-actions/mark-loaded-asset-icon
   :mark-missing-asset-icon asset-actions/mark-missing-asset-icon})

(defn- chart-and-orderbook-action-deps []
  {:toggle-timeframes-dropdown chart-actions/toggle-timeframes-dropdown
   :select-chart-timeframe chart-actions/select-chart-timeframe
   :toggle-chart-type-dropdown chart-actions/toggle-chart-type-dropdown
   :select-chart-type chart-actions/select-chart-type
   :toggle-indicators-dropdown chart-actions/toggle-indicators-dropdown
   :update-indicators-search chart-actions/update-indicators-search
   :toggle-portfolio-summary-scope-dropdown portfolio-actions/toggle-portfolio-summary-scope-dropdown
   :select-portfolio-summary-scope portfolio-actions/select-portfolio-summary-scope
   :toggle-portfolio-summary-time-range-dropdown portfolio-actions/toggle-portfolio-summary-time-range-dropdown
   :toggle-portfolio-performance-metrics-time-range-dropdown portfolio-actions/toggle-portfolio-performance-metrics-time-range-dropdown
   :select-portfolio-summary-time-range portfolio-actions/select-portfolio-summary-time-range
   :select-portfolio-chart-tab portfolio-actions/select-portfolio-chart-tab
   :set-portfolio-account-info-tab portfolio-actions/set-portfolio-account-info-tab
   :set-portfolio-chart-hover portfolio-actions/set-portfolio-chart-hover
   :clear-portfolio-chart-hover portfolio-actions/clear-portfolio-chart-hover
   :set-portfolio-returns-benchmark-search portfolio-actions/set-portfolio-returns-benchmark-search
   :set-portfolio-returns-benchmark-suggestions-open portfolio-actions/set-portfolio-returns-benchmark-suggestions-open
   :select-portfolio-returns-benchmark portfolio-actions/select-portfolio-returns-benchmark
   :remove-portfolio-returns-benchmark portfolio-actions/remove-portfolio-returns-benchmark
   :handle-portfolio-returns-benchmark-search-keydown portfolio-actions/handle-portfolio-returns-benchmark-search-keydown
   :clear-portfolio-returns-benchmark portfolio-actions/clear-portfolio-returns-benchmark
   :toggle-orderbook-size-unit-dropdown orderbook-actions/toggle-orderbook-size-unit-dropdown
   :select-orderbook-size-unit orderbook-actions/select-orderbook-size-unit
   :toggle-orderbook-price-aggregation-dropdown orderbook-actions/toggle-orderbook-price-aggregation-dropdown
   :select-orderbook-price-aggregation orderbook-actions/select-orderbook-price-aggregation
   :select-orderbook-tab orderbook-actions/select-orderbook-tab
   :add-indicator chart-settings/add-indicator
   :remove-indicator chart-settings/remove-indicator
   :update-indicator-period chart-settings/update-indicator-period
   :show-volume-indicator chart-settings/show-volume-indicator
   :hide-volume-indicator chart-settings/hide-volume-indicator})

(defn- account-history-action-deps []
  {:select-account-info-tab account-history-actions/select-account-info-tab
   :set-funding-history-filters account-history-actions/set-funding-history-filters
   :toggle-funding-history-filter-open account-history-actions/toggle-funding-history-filter-open
   :toggle-funding-history-filter-coin account-history-actions/toggle-funding-history-filter-coin
   :add-funding-history-filter-coin account-history-actions/add-funding-history-filter-coin
   :handle-funding-history-coin-search-keydown account-history-actions/handle-funding-history-coin-search-keydown
   :reset-funding-history-filter-draft account-history-actions/reset-funding-history-filter-draft
   :apply-funding-history-filters account-history-actions/apply-funding-history-filters
   :view-all-funding-history account-history-actions/view-all-funding-history
   :export-funding-history-csv account-history-actions/export-funding-history-csv
   :set-funding-history-page-size account-history-actions/set-funding-history-page-size
   :set-funding-history-page account-history-actions/set-funding-history-page
   :next-funding-history-page account-history-actions/next-funding-history-page
   :prev-funding-history-page account-history-actions/prev-funding-history-page
   :set-funding-history-page-input account-history-actions/set-funding-history-page-input
   :apply-funding-history-page-input account-history-actions/apply-funding-history-page-input
   :handle-funding-history-page-input-keydown account-history-actions/handle-funding-history-page-input-keydown
   :set-trade-history-page-size account-history-actions/set-trade-history-page-size
   :set-trade-history-page account-history-actions/set-trade-history-page
   :next-trade-history-page account-history-actions/next-trade-history-page
   :prev-trade-history-page account-history-actions/prev-trade-history-page
   :set-trade-history-page-input account-history-actions/set-trade-history-page-input
   :apply-trade-history-page-input account-history-actions/apply-trade-history-page-input
   :handle-trade-history-page-input-keydown account-history-actions/handle-trade-history-page-input-keydown
   :sort-trade-history account-history-actions/sort-trade-history
   :toggle-trade-history-direction-filter-open account-history-actions/toggle-trade-history-direction-filter-open
   :set-trade-history-direction-filter account-history-actions/set-trade-history-direction-filter
   :sort-positions account-history-actions/sort-positions
   :toggle-positions-direction-filter-open account-history-actions/toggle-positions-direction-filter-open
   :set-positions-direction-filter account-history-actions/set-positions-direction-filter
   :sort-balances account-history-actions/sort-balances
   :sort-open-orders account-history-actions/sort-open-orders
   :toggle-open-orders-direction-filter-open account-history-actions/toggle-open-orders-direction-filter-open
   :set-open-orders-direction-filter account-history-actions/set-open-orders-direction-filter
   :sort-funding-history account-history-actions/sort-funding-history
   :sort-order-history account-history-actions/sort-order-history
   :toggle-order-history-filter-open account-history-actions/toggle-order-history-filter-open
   :set-order-history-status-filter account-history-actions/set-order-history-status-filter
   :set-account-info-coin-search account-history-actions/set-account-info-coin-search
   :set-order-history-page-size account-history-actions/set-order-history-page-size
   :set-order-history-page account-history-actions/set-order-history-page
   :next-order-history-page account-history-actions/next-order-history-page
   :prev-order-history-page account-history-actions/prev-order-history-page
   :set-order-history-page-input account-history-actions/set-order-history-page-input
   :apply-order-history-page-input account-history-actions/apply-order-history-page-input
   :handle-order-history-page-input-keydown account-history-actions/handle-order-history-page-input-keydown
   :refresh-order-history account-history-actions/refresh-order-history
   :set-hide-small-balances account-history-actions/set-hide-small-balances
   :open-position-tpsl-modal account-history-actions/open-position-tpsl-modal
   :close-position-tpsl-modal account-history-actions/close-position-tpsl-modal
   :handle-position-tpsl-modal-keydown account-history-actions/handle-position-tpsl-modal-keydown
   :set-position-tpsl-modal-field account-history-actions/set-position-tpsl-modal-field
   :set-position-tpsl-configure-amount account-history-actions/set-position-tpsl-configure-amount
   :set-position-tpsl-limit-price account-history-actions/set-position-tpsl-limit-price
   :submit-position-tpsl account-history-actions/submit-position-tpsl
   :trigger-close-all-positions account-history-actions/trigger-close-all-positions
   :open-position-reduce-popover account-history-actions/open-position-reduce-popover
   :close-position-reduce-popover account-history-actions/close-position-reduce-popover
   :handle-position-reduce-popover-keydown account-history-actions/handle-position-reduce-popover-keydown
   :set-position-reduce-popover-field account-history-actions/set-position-reduce-popover-field
   :set-position-reduce-size-percent account-history-actions/set-position-reduce-size-percent
   :set-position-reduce-limit-price-to-mid account-history-actions/set-position-reduce-limit-price-to-mid
   :submit-position-reduce-close account-history-actions/submit-position-reduce-close})

(defn- order-action-deps []
  {:select-order-entry-mode order-actions/select-order-entry-mode
   :select-pro-order-type order-actions/select-pro-order-type
   :toggle-pro-order-type-dropdown order-actions/toggle-pro-order-type-dropdown
   :close-pro-order-type-dropdown order-actions/close-pro-order-type-dropdown
   :handle-pro-order-type-dropdown-keydown order-actions/handle-pro-order-type-dropdown-keydown
   :toggle-size-unit-dropdown order-actions/toggle-size-unit-dropdown
   :close-size-unit-dropdown order-actions/close-size-unit-dropdown
   :handle-size-unit-dropdown-keydown order-actions/handle-size-unit-dropdown-keydown
   :toggle-tpsl-unit-dropdown order-actions/toggle-tpsl-unit-dropdown
   :close-tpsl-unit-dropdown order-actions/close-tpsl-unit-dropdown
   :handle-tpsl-unit-dropdown-keydown order-actions/handle-tpsl-unit-dropdown-keydown
   :toggle-tif-dropdown order-actions/toggle-tif-dropdown
   :close-tif-dropdown order-actions/close-tif-dropdown
   :handle-tif-dropdown-keydown order-actions/handle-tif-dropdown-keydown
   :set-order-ui-leverage order-actions/set-order-ui-leverage
   :set-order-size-percent order-actions/set-order-size-percent
   :set-order-size-display order-actions/set-order-size-display
   :set-order-size-input-mode order-actions/set-order-size-input-mode
   :focus-order-price-input order-actions/focus-order-price-input
   :blur-order-price-input order-actions/blur-order-price-input
   :set-order-price-to-mid order-actions/set-order-price-to-mid
   :toggle-order-tpsl-panel order-actions/toggle-order-tpsl-panel
   :update-order-form order-actions/update-order-form
   :submit-order order-actions/submit-order
   :cancel-order order-actions/cancel-order})

(defn- vault-action-deps []
  {:load-vault-route vault-actions/load-vault-route
   :load-vaults vault-actions/load-vaults
   :load-vault-detail vault-actions/load-vault-detail
   :set-vaults-search-query vault-actions/set-vaults-search-query
   :toggle-vaults-filter vault-actions/toggle-vaults-filter
   :set-vaults-snapshot-range vault-actions/set-vaults-snapshot-range
   :set-vaults-sort vault-actions/set-vaults-sort
   :set-vaults-user-page-size vault-actions/set-vaults-user-page-size
   :toggle-vaults-user-page-size-dropdown vault-actions/toggle-vaults-user-page-size-dropdown
   :close-vaults-user-page-size-dropdown vault-actions/close-vaults-user-page-size-dropdown
   :set-vaults-user-page vault-actions/set-vaults-user-page
   :next-vaults-user-page vault-actions/next-vaults-user-page
   :prev-vaults-user-page vault-actions/prev-vaults-user-page
   :set-vault-detail-tab vault-actions/set-vault-detail-tab
   :set-vault-detail-activity-tab vault-actions/set-vault-detail-activity-tab
   :set-vault-detail-chart-series vault-actions/set-vault-detail-chart-series})

(defn runtime-effect-deps
  [effect-overrides]
  (merge-nested
   {:api {:api-fetch-user-funding-history account-history-effects/api-fetch-user-funding-history-effect
          :api-fetch-historical-orders account-history-effects/api-fetch-historical-orders-effect
          :export-funding-history-csv account-history-effects/export-funding-history-csv-effect
          :api-fetch-vault-index vault-effects/api-fetch-vault-index!
          :api-fetch-vault-summaries vault-effects/api-fetch-vault-summaries!
          :api-fetch-user-vault-equities vault-effects/api-fetch-user-vault-equities!
          :api-fetch-vault-details vault-effects/api-fetch-vault-details!
          :api-fetch-vault-webdata2 vault-effects/api-fetch-vault-webdata2!
          :api-fetch-vault-fills vault-effects/api-fetch-vault-fills!
          :api-fetch-vault-funding-history vault-effects/api-fetch-vault-funding-history!
          :api-fetch-vault-order-history vault-effects/api-fetch-vault-order-history!
          :api-fetch-vault-ledger-updates vault-effects/api-fetch-vault-ledger-updates!}}
   effect-overrides))

(defn runtime-action-deps
  [action-overrides]
  (merge-nested
   {:core {}
    :wallet (wallet-action-deps)
    :asset-selector (asset-selector-action-deps)
    :chart (chart-and-orderbook-action-deps)
    :account-history (account-history-action-deps)
    :vaults (vault-action-deps)
    :orders (order-action-deps)}
   action-overrides))
