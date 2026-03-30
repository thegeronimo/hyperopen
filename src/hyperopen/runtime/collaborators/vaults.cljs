(ns hyperopen.runtime.collaborators.vaults
  (:require [hyperopen.vaults.actions :as vault-actions]))

(defn action-deps []
  {:load-vault-route vault-actions/load-vault-route
   :load-vaults vault-actions/load-vaults
   :load-vault-detail vault-actions/load-vault-detail
   :set-vaults-search-query vault-actions/set-vaults-search-query
   :toggle-vaults-filter vault-actions/toggle-vaults-filter
   :toggle-vault-detail-chart-timeframe-dropdown
   vault-actions/toggle-vault-detail-chart-timeframe-dropdown
   :close-vault-detail-chart-timeframe-dropdown
   vault-actions/close-vault-detail-chart-timeframe-dropdown
   :toggle-vault-detail-performance-metrics-timeframe-dropdown
   vault-actions/toggle-vault-detail-performance-metrics-timeframe-dropdown
   :close-vault-detail-performance-metrics-timeframe-dropdown
   vault-actions/close-vault-detail-performance-metrics-timeframe-dropdown
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
   :set-vault-detail-returns-benchmark-suggestions-open
   vault-actions/set-vault-detail-returns-benchmark-suggestions-open
   :select-vault-detail-returns-benchmark vault-actions/select-vault-detail-returns-benchmark
   :remove-vault-detail-returns-benchmark vault-actions/remove-vault-detail-returns-benchmark
   :handle-vault-detail-returns-benchmark-search-keydown
   vault-actions/handle-vault-detail-returns-benchmark-search-keydown
   :clear-vault-detail-returns-benchmark vault-actions/clear-vault-detail-returns-benchmark
   :open-vault-transfer-modal vault-actions/open-vault-transfer-modal
   :close-vault-transfer-modal vault-actions/close-vault-transfer-modal
   :handle-vault-transfer-modal-keydown vault-actions/handle-vault-transfer-modal-keydown
   :set-vault-transfer-amount vault-actions/set-vault-transfer-amount
   :set-vault-transfer-withdraw-all vault-actions/set-vault-transfer-withdraw-all
   :submit-vault-transfer vault-actions/submit-vault-transfer})
