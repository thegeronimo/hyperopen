(ns hyperopen.vaults.actions
  (:require [hyperopen.vaults.application.detail-commands :as detail-commands]
            [hyperopen.vaults.application.list-commands :as list-commands]
            [hyperopen.vaults.application.route-loading :as route-loading]
            [hyperopen.vaults.application.transfer-commands :as transfer-commands]
            [hyperopen.vaults.domain.identity :as identity]
            [hyperopen.vaults.domain.transfer-policy :as transfer-policy]
            [hyperopen.vaults.domain.ui-state :as ui-state]
            [hyperopen.vaults.infrastructure.persistence :as persistence]
            [hyperopen.vaults.infrastructure.routes :as routes]))

(def default-vault-snapshot-range ui-state/default-vault-snapshot-range)
(def default-vault-sort-column ui-state/default-vault-sort-column)
(def default-vault-sort-direction ui-state/default-vault-sort-direction)
(def default-vault-user-page-size ui-state/default-vault-user-page-size)
(def default-vault-user-page ui-state/default-vault-user-page)
(def default-vault-detail-tab ui-state/default-vault-detail-tab)
(def default-vault-detail-activity-tab ui-state/default-vault-detail-activity-tab)
(def default-vault-detail-activity-direction-filter
  ui-state/default-vault-detail-activity-direction-filter)
(def default-vault-detail-activity-sort-direction
  ui-state/default-vault-detail-activity-sort-direction)
(def default-vault-detail-chart-series ui-state/default-vault-detail-chart-series)
(def default-vault-transfer-mode transfer-policy/default-vault-transfer-mode)
(def vault-user-page-size-options ui-state/vault-user-page-size-options)

(def non-blank-text identity/non-blank-text)
(def normalize-vault-address identity/normalize-vault-address)
(def normalize-vault-route-path routes/normalize-vault-route-path)
(def parse-vault-route routes/parse-vault-route)
(def normalize-vault-snapshot-range ui-state/normalize-vault-snapshot-range)
(def normalize-vault-sort-column ui-state/normalize-vault-sort-column)
(def normalize-vault-detail-tab ui-state/normalize-vault-detail-tab)
(def normalize-vault-detail-activity-tab ui-state/normalize-vault-detail-activity-tab)
(def normalize-vault-detail-activity-direction-filter
  ui-state/normalize-vault-detail-activity-direction-filter)
(def normalize-vault-detail-chart-series ui-state/normalize-vault-detail-chart-series)
(def normalize-vault-transfer-mode transfer-policy/normalize-vault-transfer-mode)
(def default-vault-transfer-modal-state transfer-policy/default-vault-transfer-modal-state)
(def vault-transfer-deposit-allowed? transfer-policy/vault-transfer-deposit-allowed?)
(def normalize-vault-user-page-size ui-state/normalize-vault-user-page-size)
(def normalize-vault-user-page ui-state/normalize-vault-user-page)
(def load-vaults route-loading/load-vaults)
(def load-vault-detail route-loading/load-vault-detail)
(def set-vaults-search-query list-commands/set-vaults-search-query)
(def toggle-vaults-filter list-commands/toggle-vaults-filter)
(def restore-vaults-snapshot-range! persistence/restore-vaults-snapshot-range!)
(def set-vaults-sort list-commands/set-vaults-sort)
(def set-vaults-user-page-size list-commands/set-vaults-user-page-size)
(def toggle-vaults-user-page-size-dropdown list-commands/toggle-vaults-user-page-size-dropdown)
(def close-vaults-user-page-size-dropdown list-commands/close-vaults-user-page-size-dropdown)
(def set-vaults-user-page list-commands/set-vaults-user-page)
(def next-vaults-user-page list-commands/next-vaults-user-page)
(def prev-vaults-user-page list-commands/prev-vaults-user-page)
(def set-vault-detail-tab detail-commands/set-vault-detail-tab)
(def set-vault-detail-activity-tab detail-commands/set-vault-detail-activity-tab)
(def sort-vault-detail-activity detail-commands/sort-vault-detail-activity)
(def toggle-vault-detail-activity-filter-open detail-commands/toggle-vault-detail-activity-filter-open)
(def close-vault-detail-activity-filter detail-commands/close-vault-detail-activity-filter)
(def set-vault-detail-activity-direction-filter
  detail-commands/set-vault-detail-activity-direction-filter)
(def set-vault-detail-returns-benchmark-search
  detail-commands/set-vault-detail-returns-benchmark-search)
(def set-vault-detail-returns-benchmark-suggestions-open
  detail-commands/set-vault-detail-returns-benchmark-suggestions-open)
(def remove-vault-detail-returns-benchmark
  detail-commands/remove-vault-detail-returns-benchmark)
(def clear-vault-detail-returns-benchmark
  detail-commands/clear-vault-detail-returns-benchmark)
(def open-vault-transfer-modal transfer-commands/open-vault-transfer-modal)
(def close-vault-transfer-modal transfer-commands/close-vault-transfer-modal)
(def handle-vault-transfer-modal-keydown transfer-commands/handle-vault-transfer-modal-keydown)
(def set-vault-transfer-amount transfer-commands/set-vault-transfer-amount)
(def set-vault-transfer-withdraw-all transfer-commands/set-vault-transfer-withdraw-all)
(def set-vault-detail-chart-hover detail-commands/set-vault-detail-chart-hover)
(def clear-vault-detail-chart-hover detail-commands/clear-vault-detail-chart-hover)

(defn- current-route-vault-address
  [state]
  (-> state
      (get-in [:router :path])
      routes/parse-vault-route
      :vault-address))

(defn- detail-command-deps
  []
  {:parse-vault-route-fn routes/parse-vault-route})

(defn- list-command-deps
  []
  (assoc (detail-command-deps)
         :snapshot-range-save-effect-fn persistence/snapshot-range-save-effect))

(defn- transfer-command-deps
  []
  {:route-vault-address-fn current-route-vault-address})

(defn vault-transfer-preview
  [state modal]
  (transfer-policy/vault-transfer-preview (transfer-command-deps) state modal))

(defn load-vault-route
  [state path]
  (route-loading/load-vault-route state (routes/parse-vault-route path)))

(defn set-vaults-snapshot-range
  [state snapshot-range]
  (list-commands/set-vaults-snapshot-range (list-command-deps) state snapshot-range))

(defn set-vault-detail-chart-series
  [state series]
  (detail-commands/set-vault-detail-chart-series (detail-command-deps) state series))

(defn select-vault-detail-returns-benchmark
  [state benchmark]
  (detail-commands/select-vault-detail-returns-benchmark
   (detail-command-deps)
   state
   benchmark))

(defn handle-vault-detail-returns-benchmark-search-keydown
  [state key top-coin]
  (detail-commands/handle-vault-detail-returns-benchmark-search-keydown
   (detail-command-deps)
   state
   key
   top-coin))

(defn submit-vault-transfer
  [state]
  (transfer-commands/submit-vault-transfer (transfer-command-deps) state))
