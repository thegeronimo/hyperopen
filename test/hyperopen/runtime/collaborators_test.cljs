(ns hyperopen.runtime.collaborators-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.history.actions :as account-history-actions]
            [hyperopen.account.history.effects :as account-history-effects]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.chart.actions :as chart-actions]
            [hyperopen.chart.settings :as chart-settings]
            [hyperopen.order.actions :as order-actions]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.runtime.collaborators :as collaborators]
            [hyperopen.vaults.actions :as vault-actions]
            [hyperopen.vaults.effects :as vault-effects]
            [hyperopen.wallet.actions :as wallet-actions]))

(deftest runtime-effect-deps-merges-defaults-with-overrides-test
  (let [save-fn (fn [& _] :save)
        export-fn (fn [& _] :export)
        deps (collaborators/runtime-effect-deps
              {:storage {:save save-fn}
               :api {:export-funding-history-csv export-fn}})]
    (is (identical? save-fn (get-in deps [:storage :save])))
    (is (identical? export-fn (get-in deps [:api :export-funding-history-csv])))
    (is (identical? account-history-effects/api-fetch-user-funding-history-effect
                    (get-in deps [:api :api-fetch-user-funding-history])))
    (is (identical? account-history-effects/api-fetch-historical-orders-effect
                    (get-in deps [:api :api-fetch-historical-orders])))
    (is (identical? vault-effects/api-fetch-vault-index!
                    (get-in deps [:api :api-fetch-vault-index])))
    (is (identical? vault-effects/api-fetch-vault-webdata2!
                    (get-in deps [:api :api-fetch-vault-webdata2])))
    (is (identical? vault-effects/api-fetch-vault-ledger-updates!
                    (get-in deps [:api :api-fetch-vault-ledger-updates])))))

(deftest runtime-action-deps-provides-default-domain-action-handlers-test
  (let [deps (collaborators/runtime-action-deps {})]
    (is (identical? wallet-actions/connect-wallet-action
                    (get-in deps [:wallet :connect-wallet-action])))
    (is (identical? asset-actions/select-asset
                    (get-in deps [:asset-selector :select-asset])))
    (is (identical? chart-actions/select-chart-type
                    (get-in deps [:chart :select-chart-type])))
    (is (identical? portfolio-actions/toggle-portfolio-summary-scope-dropdown
                    (get-in deps [:chart :toggle-portfolio-summary-scope-dropdown])))
    (is (identical? portfolio-actions/toggle-portfolio-performance-metrics-time-range-dropdown
                    (get-in deps [:chart :toggle-portfolio-performance-metrics-time-range-dropdown])))
    (is (identical? portfolio-actions/select-portfolio-chart-tab
                    (get-in deps [:chart :select-portfolio-chart-tab])))
    (is (identical? portfolio-actions/set-portfolio-account-info-tab
                    (get-in deps [:chart :set-portfolio-account-info-tab])))
    (is (identical? portfolio-actions/set-portfolio-chart-hover
                    (get-in deps [:chart :set-portfolio-chart-hover])))
    (is (identical? portfolio-actions/clear-portfolio-chart-hover
                    (get-in deps [:chart :clear-portfolio-chart-hover])))
    (is (identical? portfolio-actions/select-portfolio-returns-benchmark
                    (get-in deps [:chart :select-portfolio-returns-benchmark])))
    (is (identical? portfolio-actions/clear-portfolio-returns-benchmark
                    (get-in deps [:chart :clear-portfolio-returns-benchmark])))
    (is (identical? chart-settings/hide-volume-indicator
                    (get-in deps [:chart :hide-volume-indicator])))
    (is (identical? account-history-actions/select-account-info-tab
                    (get-in deps [:account-history :select-account-info-tab])))
    (is (identical? account-history-actions/toggle-positions-direction-filter-open
                    (get-in deps [:account-history :toggle-positions-direction-filter-open])))
    (is (identical? vault-actions/load-vault-route
                    (get-in deps [:vaults :load-vault-route])))
    (is (identical? vault-actions/set-vaults-user-page-size
                    (get-in deps [:vaults :set-vaults-user-page-size])))
    (is (identical? vault-actions/toggle-vaults-user-page-size-dropdown
                    (get-in deps [:vaults :toggle-vaults-user-page-size-dropdown])))
    (is (identical? vault-actions/close-vaults-user-page-size-dropdown
                    (get-in deps [:vaults :close-vaults-user-page-size-dropdown])))
    (is (identical? vault-actions/next-vaults-user-page
                    (get-in deps [:vaults :next-vaults-user-page])))
    (is (identical? vault-actions/set-vault-detail-tab
                    (get-in deps [:vaults :set-vault-detail-tab])))
    (is (identical? vault-actions/set-vault-detail-activity-tab
                    (get-in deps [:vaults :set-vault-detail-activity-tab])))
    (is (identical? vault-actions/sort-vault-detail-activity
                    (get-in deps [:vaults :sort-vault-detail-activity])))
    (is (identical? vault-actions/toggle-vault-detail-activity-filter-open
                    (get-in deps [:vaults :toggle-vault-detail-activity-filter-open])))
    (is (identical? vault-actions/close-vault-detail-activity-filter
                    (get-in deps [:vaults :close-vault-detail-activity-filter])))
    (is (identical? vault-actions/set-vault-detail-activity-direction-filter
                    (get-in deps [:vaults :set-vault-detail-activity-direction-filter])))
    (is (identical? vault-actions/set-vault-detail-chart-series
                    (get-in deps [:vaults :set-vault-detail-chart-series])))
    (is (identical? order-actions/submit-order
                    (get-in deps [:orders :submit-order])))))

(deftest runtime-action-deps-overrides-default-action-handlers-test
  (let [connect-wallet-action* (fn [& _] :override-connect)
        navigate* (fn [& _] :navigate)
        deps (collaborators/runtime-action-deps
              {:wallet {:connect-wallet-action connect-wallet-action*}
               :core {:navigate navigate*}})]
    (is (identical? connect-wallet-action*
                    (get-in deps [:wallet :connect-wallet-action])))
    (is (identical? navigate*
                    (get-in deps [:core :navigate])))))
