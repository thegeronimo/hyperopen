(ns hyperopen.runtime.collaborators
  (:require [hyperopen.account.spectate-mode-actions :as spectate-mode-actions]
            [hyperopen.account.history.actions :as account-history-actions]
            [hyperopen.account.history.effects :as account-history-effects]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.chart.actions :as chart-actions]
            [hyperopen.chart.settings :as chart-settings]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.header.actions :as header-actions]
            [hyperopen.funding.effects :as funding-effects]
            [hyperopen.order.actions :as order-actions]
            [hyperopen.orderbook.actions :as orderbook-actions]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.trade.layout-actions :as trade-layout-actions]
            [hyperopen.funding-comparison.actions :as funding-comparison-actions]
            [hyperopen.vaults.actions :as vault-actions]
            [hyperopen.funding-comparison.effects :as funding-comparison-effects]
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
   :close-agent-recovery-modal-action wallet-actions/close-agent-recovery-modal-action
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
   :mark-missing-asset-icon asset-actions/mark-missing-asset-icon
   :set-funding-tooltip-visible asset-actions/set-funding-tooltip-visible
   :set-funding-tooltip-pinned asset-actions/set-funding-tooltip-pinned
   :set-funding-hypothetical-size asset-actions/set-funding-hypothetical-size
   :set-funding-hypothetical-value asset-actions/set-funding-hypothetical-value})

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
   :select-trade-mobile-surface trade-layout-actions/select-trade-mobile-surface
   :toggle-trade-mobile-asset-details trade-layout-actions/toggle-trade-mobile-asset-details
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
   :toggle-account-info-mobile-card account-history-actions/toggle-account-info-mobile-card
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
   :submit-position-reduce-close account-history-actions/submit-position-reduce-close
   :open-position-margin-modal account-history-actions/open-position-margin-modal
   :close-position-margin-modal account-history-actions/close-position-margin-modal
   :handle-position-margin-modal-keydown account-history-actions/handle-position-margin-modal-keydown
   :set-position-margin-modal-field account-history-actions/set-position-margin-modal-field
   :set-position-margin-amount-percent account-history-actions/set-position-margin-amount-percent
   :set-position-margin-amount-to-max account-history-actions/set-position-margin-amount-to-max
   :submit-position-margin-update account-history-actions/submit-position-margin-update})

(defn- spectate-mode-action-deps []
  {:open-mobile-header-menu header-actions/open-mobile-header-menu
   :close-mobile-header-menu header-actions/close-mobile-header-menu
   :navigate-mobile-header-menu header-actions/navigate-mobile-header-menu
   :open-spectate-mode-mobile-header-menu header-actions/open-spectate-mode-mobile-header-menu
   :open-spectate-mode-modal spectate-mode-actions/open-spectate-mode-modal
   :close-spectate-mode-modal spectate-mode-actions/close-spectate-mode-modal
   :set-spectate-mode-search spectate-mode-actions/set-spectate-mode-search
   :set-spectate-mode-label spectate-mode-actions/set-spectate-mode-label
   :start-spectate-mode spectate-mode-actions/start-spectate-mode
   :stop-spectate-mode spectate-mode-actions/stop-spectate-mode
   :add-spectate-mode-watchlist-address spectate-mode-actions/add-spectate-mode-watchlist-address
   :remove-spectate-mode-watchlist-address spectate-mode-actions/remove-spectate-mode-watchlist-address
   :edit-spectate-mode-watchlist-address spectate-mode-actions/edit-spectate-mode-watchlist-address
   :clear-spectate-mode-watchlist-edit spectate-mode-actions/clear-spectate-mode-watchlist-edit
   :copy-spectate-mode-watchlist-address spectate-mode-actions/copy-spectate-mode-watchlist-address
   :copy-spectate-mode-watchlist-link spectate-mode-actions/copy-spectate-mode-watchlist-link
   :start-spectate-mode-watchlist-address spectate-mode-actions/start-spectate-mode-watchlist-address})

(defn- order-action-deps []
  {:select-order-entry-mode order-actions/select-order-entry-mode
   :select-pro-order-type order-actions/select-pro-order-type
   :toggle-pro-order-type-dropdown order-actions/toggle-pro-order-type-dropdown
   :close-pro-order-type-dropdown order-actions/close-pro-order-type-dropdown
   :handle-pro-order-type-dropdown-keydown order-actions/handle-pro-order-type-dropdown-keydown
   :toggle-margin-mode-dropdown order-actions/toggle-margin-mode-dropdown
   :close-margin-mode-dropdown order-actions/close-margin-mode-dropdown
   :handle-margin-mode-dropdown-keydown order-actions/handle-margin-mode-dropdown-keydown
   :toggle-leverage-popover order-actions/toggle-leverage-popover
   :close-leverage-popover order-actions/close-leverage-popover
   :handle-leverage-popover-keydown order-actions/handle-leverage-popover-keydown
   :set-order-ui-leverage-draft order-actions/set-order-ui-leverage-draft
   :confirm-order-ui-leverage order-actions/confirm-order-ui-leverage
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
   :set-order-margin-mode order-actions/set-order-margin-mode
   :set-order-size-percent order-actions/set-order-size-percent
   :set-order-size-display order-actions/set-order-size-display
   :set-order-size-input-mode order-actions/set-order-size-input-mode
   :focus-order-price-input order-actions/focus-order-price-input
   :blur-order-price-input order-actions/blur-order-price-input
   :set-order-price-to-mid order-actions/set-order-price-to-mid
   :toggle-order-tpsl-panel order-actions/toggle-order-tpsl-panel
   :update-order-form order-actions/update-order-form
   :dismiss-order-feedback-toast order-actions/dismiss-order-feedback-toast
   :submit-order order-actions/submit-order
   :confirm-cancel-visible-open-orders order-actions/confirm-cancel-visible-open-orders
   :close-cancel-visible-open-orders-confirmation order-actions/close-cancel-visible-open-orders-confirmation
   :handle-cancel-visible-open-orders-confirmation-keydown order-actions/handle-cancel-visible-open-orders-confirmation-keydown
   :submit-cancel-visible-open-orders-confirmation order-actions/submit-cancel-visible-open-orders-confirmation
   :cancel-visible-open-orders order-actions/cancel-visible-open-orders
   :cancel-order order-actions/cancel-order
   :open-funding-send-modal funding-actions/open-funding-send-modal
   :open-funding-transfer-modal funding-actions/open-funding-transfer-modal
   :open-funding-withdraw-modal funding-actions/open-funding-withdraw-modal
   :open-funding-deposit-modal funding-actions/open-funding-deposit-modal
   :close-funding-modal funding-actions/close-funding-modal
   :handle-funding-modal-keydown funding-actions/handle-funding-modal-keydown
   :set-funding-modal-field funding-actions/set-funding-modal-field
   :search-funding-deposit-assets funding-actions/search-funding-deposit-assets
   :search-funding-withdraw-assets funding-actions/search-funding-withdraw-assets
   :select-funding-deposit-asset funding-actions/select-funding-deposit-asset
   :return-to-funding-deposit-asset-select funding-actions/return-to-funding-deposit-asset-select
   :return-to-funding-withdraw-asset-select funding-actions/return-to-funding-withdraw-asset-select
   :enter-funding-deposit-amount funding-actions/enter-funding-deposit-amount
   :set-funding-deposit-amount-to-minimum funding-actions/set-funding-deposit-amount-to-minimum
   :enter-funding-transfer-amount funding-actions/enter-funding-transfer-amount
   :select-funding-withdraw-asset funding-actions/select-funding-withdraw-asset
   :enter-funding-withdraw-destination funding-actions/enter-funding-withdraw-destination
   :enter-funding-withdraw-amount funding-actions/enter-funding-withdraw-amount
   :set-hyperunit-lifecycle funding-actions/set-hyperunit-lifecycle
   :clear-hyperunit-lifecycle funding-actions/clear-hyperunit-lifecycle
   :set-hyperunit-lifecycle-error funding-actions/set-hyperunit-lifecycle-error
   :set-funding-transfer-direction funding-actions/set-funding-transfer-direction
   :set-funding-amount-to-max funding-actions/set-funding-amount-to-max
   :submit-funding-send funding-actions/submit-funding-send
   :submit-funding-transfer funding-actions/submit-funding-transfer
   :submit-funding-withdraw funding-actions/submit-funding-withdraw
   :submit-funding-deposit funding-actions/submit-funding-deposit})

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
   :sort-vault-detail-activity vault-actions/sort-vault-detail-activity
   :toggle-vault-detail-activity-filter-open vault-actions/toggle-vault-detail-activity-filter-open
   :close-vault-detail-activity-filter vault-actions/close-vault-detail-activity-filter
   :set-vault-detail-activity-direction-filter vault-actions/set-vault-detail-activity-direction-filter
   :set-vault-detail-chart-series vault-actions/set-vault-detail-chart-series
   :set-vault-detail-returns-benchmark-search vault-actions/set-vault-detail-returns-benchmark-search
   :set-vault-detail-returns-benchmark-suggestions-open vault-actions/set-vault-detail-returns-benchmark-suggestions-open
   :select-vault-detail-returns-benchmark vault-actions/select-vault-detail-returns-benchmark
   :remove-vault-detail-returns-benchmark vault-actions/remove-vault-detail-returns-benchmark
   :handle-vault-detail-returns-benchmark-search-keydown vault-actions/handle-vault-detail-returns-benchmark-search-keydown
   :clear-vault-detail-returns-benchmark vault-actions/clear-vault-detail-returns-benchmark
   :open-vault-transfer-modal vault-actions/open-vault-transfer-modal
   :close-vault-transfer-modal vault-actions/close-vault-transfer-modal
   :handle-vault-transfer-modal-keydown vault-actions/handle-vault-transfer-modal-keydown
   :set-vault-transfer-amount vault-actions/set-vault-transfer-amount
   :set-vault-transfer-withdraw-all vault-actions/set-vault-transfer-withdraw-all
   :submit-vault-transfer vault-actions/submit-vault-transfer
   :set-vault-detail-chart-hover vault-actions/set-vault-detail-chart-hover
   :clear-vault-detail-chart-hover vault-actions/clear-vault-detail-chart-hover})

(defn- funding-comparison-action-deps []
  {:load-funding-comparison-route funding-comparison-actions/load-funding-comparison-route
   :load-funding-comparison funding-comparison-actions/load-funding-comparison
   :set-funding-comparison-query funding-comparison-actions/set-funding-comparison-query
   :set-funding-comparison-timeframe funding-comparison-actions/set-funding-comparison-timeframe
   :set-funding-comparison-sort funding-comparison-actions/set-funding-comparison-sort})

(defn runtime-effect-deps
  [effect-overrides]
  (merge-nested
   {:api {:api-fetch-user-funding-history account-history-effects/api-fetch-user-funding-history-effect
          :api-fetch-historical-orders account-history-effects/api-fetch-historical-orders-effect
          :export-funding-history-csv account-history-effects/export-funding-history-csv-effect
          :api-fetch-predicted-fundings funding-comparison-effects/api-fetch-predicted-fundings!
          :api-fetch-vault-index vault-effects/api-fetch-vault-index!
          :api-fetch-vault-summaries vault-effects/api-fetch-vault-summaries!
          :api-fetch-user-vault-equities vault-effects/api-fetch-user-vault-equities!
          :api-fetch-vault-details vault-effects/api-fetch-vault-details!
          :api-fetch-vault-webdata2 vault-effects/api-fetch-vault-webdata2!
          :api-fetch-vault-fills vault-effects/api-fetch-vault-fills!
          :api-fetch-vault-funding-history vault-effects/api-fetch-vault-funding-history!
          :api-fetch-vault-order-history vault-effects/api-fetch-vault-order-history!
          :api-fetch-vault-ledger-updates vault-effects/api-fetch-vault-ledger-updates!
          :api-submit-vault-transfer vault-effects/api-submit-vault-transfer!
          :api-fetch-hyperunit-fee-estimate funding-effects/api-fetch-hyperunit-fee-estimate!
          :api-fetch-hyperunit-withdrawal-queue funding-effects/api-fetch-hyperunit-withdrawal-queue!
          :api-submit-funding-send funding-effects/api-submit-funding-send!
          :api-submit-funding-transfer funding-effects/api-submit-funding-transfer!
          :api-submit-funding-withdraw funding-effects/api-submit-funding-withdraw!
          :api-submit-funding-deposit funding-effects/api-submit-funding-deposit!}}
   effect-overrides))

(defn runtime-action-deps
  [action-overrides]
  (merge-nested
   {:core {}
    :wallet (wallet-action-deps)
    :asset-selector (asset-selector-action-deps)
    :chart (chart-and-orderbook-action-deps)
    :account-history (account-history-action-deps)
    :spectate-mode (spectate-mode-action-deps)
    :vaults (vault-action-deps)
    :funding-comparison (funding-comparison-action-deps)
    :orders (order-action-deps)}
   action-overrides))
