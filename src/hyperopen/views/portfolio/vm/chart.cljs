(ns hyperopen.views.portfolio.vm.chart
  (:require [hyperopen.views.portfolio.vm.utils :as utils]
            [hyperopen.views.portfolio.vm.history :as vm-history]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.portfolio.metrics.parsing :as parsing]
            [hyperopen.views.portfolio.vm.metrics-bridge :as vm-metrics-bridge]))

(defn normalize-chart-point-value
  [chart-tab value]
  (if (= chart-tab :returns)
    (if (utils/finite-number? value) value 0)
    value))

(defn rows->chart-points
  [rows chart-tab]
  (->> rows
       (keep (fn [row]
               (let [time-ms (vm-history/history-point-time-ms row)
                     value (vm-history/history-point-value row)]
                 (when (and (utils/finite-number? time-ms)
                            (utils/finite-number? value))
                   {:time-ms time-ms
                    :value (normalize-chart-point-value chart-tab value)}))))
       (sort-by :time-ms)
       vec))

(defn chart-history-rows
  [state summary chart-tab summary-scope]
  (case chart-tab
    :returns (portfolio-metrics/returns-history-rows state summary summary-scope)
    :account-value (vm-history/account-value-history-rows summary)
    :pnl (vm-history/pnl-history-rows summary)
    []))

(defn chart-data-points
  [state summary chart-tab summary-scope]
  (rows->chart-points (chart-history-rows state summary chart-tab summary-scope) chart-tab))

(defn benchmark-performance-column
  [state request-data option benchmark-points strategy-points rf-rate is-vault?]
  (let [aligned-rows (if is-vault?
                       (vm-metrics-bridge/aligned-vault-return-rows option strategy-points)
                       (vm-history/aligned-benchmark-return-rows benchmark-points strategy-points))
        result (if is-vault?
                 nil
                 (vm-metrics-bridge/compute-metrics-sync (assoc request-data :strategy-cumulative-rows aligned-rows)))
        metrics* (:values result)
        rows (:rows result)
        base-col {:key (:value option)
                  :label (:label option)
                  :is-vault? is-vault?
                  :vault-address (if is-vault? (:vaultAddress option) nil)
                  :metrics metrics*
                  :rows rows}
        vault-vals (when is-vault? (vm-metrics-bridge/vault-benchmark-snapshot-values option))]
    (if is-vault?
      (assoc base-col
             :metrics {:cumulative-return (get vault-vals "all")}
             :vault-metrics vault-vals)
      base-col)))