(ns hyperopen.vaults.screener.metrics
  "Screener metrics for one vault, computed through the same pipeline as the
  vault detail page's tear sheet so the numbers match what a user sees when
  they open the vault."
  (:require [hyperopen.vaults.detail.performance :as performance]
            [hyperopen.vaults.screener.model :as model]))

(def cached-portfolio-keys
  "vaultDetails only carries day/week/month/all-time windows; 3M/6M/1Y are
  derived from all-time exactly as the detail page derives them."
  [:day :week :month :all-time])

(defn cacheable-portfolio
  [portfolio]
  (into {}
        (keep (fn [k]
                (when-let [summary (get portfolio k)]
                  [k (select-keys summary [:accountValueHistory :pnlHistory])])))
        cached-portfolio-keys))

(defn strategy-cumulative-rows
  [details window]
  (let [returns-context (performance/returns-history-context nil details window)
        summary (performance/portfolio-summary details window)
        points (:returns (performance/chart-series-data nil summary (:summary returns-context)))]
    (performance/cumulative-rows points)))

(defn- never-under-water?
  "True when the cumulative return series never falls. The tear-sheet engine
  reports no drawdown statistics at all for such a series (there is no
  drawdown period to measure), which a screen must read as a 0% drawdown,
  not as missing data."
  [rows]
  (and (>= (count rows) 2)
       (every? (fn [[[_ a] [_ b]]] (>= b a)) (partition 2 1 rows))))

(defn- with-zero-drawdown
  [values rows]
  (if (and (map? values)
           (nil? (:max-drawdown values))
           (never-under-water? rows))
    (-> values
        (assoc :max-drawdown 0 :longest-dd-days 0)
        (update :metric-status assoc :max-drawdown :ok :longest-dd-days :ok))
    values))

(defn window-metrics
  "`details` is `{:portfolio {...}}` (normalized vaultDetails). Returns the
  screenable metric values, their tear-sheet statuses and the number of return
  points in the window."
  [details window]
  (let [rows (strategy-cumulative-rows details window)
        values (when (seq rows)
                 (-> (performance/compute-metrics-sync
                      (performance/build-metrics-request-data rows {} []))
                     :portfolio-values
                     (with-zero-drawdown rows)))]
    {:point-count (count rows)
     :values (select-keys (or values {}) model/metric-keys)
     :statuses (select-keys (or (:metric-status values) {}) model/metric-keys)}))
