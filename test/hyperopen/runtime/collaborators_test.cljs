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
                    (get-in deps [:api :api-fetch-historical-orders])))))

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
    (is (identical? portfolio-actions/select-portfolio-chart-tab
                    (get-in deps [:chart :select-portfolio-chart-tab])))
    (is (identical? chart-settings/hide-volume-indicator
                    (get-in deps [:chart :hide-volume-indicator])))
    (is (identical? account-history-actions/select-account-info-tab
                    (get-in deps [:account-history :select-account-info-tab])))
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
