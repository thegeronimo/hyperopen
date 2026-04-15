(ns hyperopen.views.portfolio.vm.chart
  (:require [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.views.portfolio.vm.chart-math :as vm-chart-math]
            [hyperopen.views.portfolio.vm.constants :as constants]
            [hyperopen.views.portfolio.vm.history :as vm-history]
            [hyperopen.views.portfolio.vm.utils :as utils]))

(def ^:private chart-empty-y-ticks
  [{:value 3 :y-ratio 0}
   {:value 2 :y-ratio (/ 1 3)}
   {:value 1 :y-ratio (/ 2 3)}
   {:value 0 :y-ratio 1}])

(def ^:private strategy-series-stroke
  "#f5f7f8")

(def ^:private benchmark-series-strokes
  ["#f2cf66"
   "#7cc2ff"
   "#ff9d7c"
   "#8be28b"
   "#d8a8ff"
   "#ffdf8a"])

(defn normalize-chart-point-value
  [chart-tab value]
  (when (utils/finite-number? value)
    (if (= chart-tab :returns)
      (let [rounded (/ (js/Math.round (* value 100)) 100)]
        (if (== rounded -0)
          0
          rounded))
      ;; Hyperliquid chart rounds account-value and pnl points to integers before plotting.
      (if (zero? value)
        0
        (js/parseInt (.toFixed value 0) 10)))))

(defn chart-history-rows
  [state summary chart-tab summary-scope]
  (let [source (case chart-tab
                 :pnl (vm-history/pnl-history-rows summary)
                 :returns (portfolio-metrics/returns-history-rows state summary summary-scope)
                 :account-value (vm-history/account-value-history-rows summary)
                 (vm-history/account-value-history-rows summary))]
    (if (sequential? source)
      source
      [])))

(defn rows->chart-points
  [rows chart-tab]
  (->> rows
       (map-indexed (fn [idx row]
                      (let [time-ms (vm-history/history-point-time-ms row)
                            value (vm-history/history-point-value row)
                            value* (normalize-chart-point-value chart-tab value)]
                        (when (number? value*)
                          {:index idx
                           :time-ms (or time-ms idx)
                           :has-time-ms? (utils/finite-number? time-ms)
                           :value value*}))))
       (keep identity)
       vec))

(defn chart-data-points
  [state summary chart-tab summary-scope]
  (rows->chart-points (chart-history-rows state summary chart-tab summary-scope)
                      chart-tab))

(defn- benchmark-series-stroke
  [idx]
  (let [palette-size (count benchmark-series-strokes)]
    (if (pos? palette-size)
      (nth benchmark-series-strokes (mod idx palette-size))
      strategy-series-stroke)))

(defn build-chart-model
  [state summary-entry summary-scope _summary-time-range returns-benchmark-selector benchmark-context]
  (let [selected-tab (portfolio-actions/normalize-portfolio-chart-tab
                      (get-in state [:portfolio-ui :chart-tab]
                              portfolio-actions/default-chart-tab))
        axis-kind (vm-chart-math/chart-axis-kind selected-tab)
        strategy-cumulative-rows (or (:strategy-cumulative-rows benchmark-context)
                                     [])
        benchmark-cumulative-rows-by-coin (or (:benchmark-cumulative-rows-by-coin benchmark-context)
                                              {})
        strategy-points (if (= selected-tab :returns)
                          (rows->chart-points strategy-cumulative-rows :returns)
                          (chart-data-points state summary-entry selected-tab summary-scope))
        selected-benchmark-coins (if (= selected-tab :returns)
                                   (vec (:selected-coins returns-benchmark-selector))
                                   [])
        benchmark-label-by-coin (or (:label-by-coin returns-benchmark-selector) {})
        benchmark-series (if (= selected-tab :returns)
                           (mapv (fn [idx coin]
                                   (let [label (or (get benchmark-label-by-coin coin)
                                                   coin)]
                                     {:id (keyword (str "benchmark-" idx))
                                      :coin coin
                                      :label label
                                      :stroke (benchmark-series-stroke idx)
                                      :raw-points (rows->chart-points (or (get benchmark-cumulative-rows-by-coin coin)
                                                                          [])
                                                                      :returns)}))
                                 (range)
                                 selected-benchmark-coins)
                           [])
        raw-series (cond-> [{:id :strategy
                             :label "Portfolio"
                             :stroke strategy-series-stroke
                             :raw-points strategy-points}]
                     (seq benchmark-series)
                     (into benchmark-series))
        time-domain (vm-chart-math/shared-time-domain (mapcat :raw-points raw-series))
        domain-values (->> raw-series
                           (mapcat (fn [{:keys [raw-points]}]
                                     (map :value raw-points)))
                           vec)
        domain (when (seq domain-values)
                 (vm-chart-math/chart-domain domain-values))
        series (mapv (fn [{:keys [raw-points] :as entry}]
                       (let [points (if domain
                                      (vm-chart-math/normalize-chart-points raw-points
                                                                            domain
                                                                            time-domain)
                                      [])]
                         (assoc entry
                                :points points
                                :has-data? (seq points))))
                     raw-series)
        strategy-series (or (some (fn [series-entry]
                                    (when (= :strategy (:id series-entry))
                                      series-entry))
                                  series)
                            {:points []
                             :has-data? false})
        strategy-points (:points strategy-series)]
     {:selected-tab selected-tab
      :axis-kind axis-kind
      :tabs constants/chart-tab-options
      :points strategy-points
      :series series
      :benchmark-selected? (and (= selected-tab :returns)
                                (seq selected-benchmark-coins))
      :y-ticks (if domain
                 (vm-chart-math/chart-y-ticks domain)
                 chart-empty-y-ticks)
      :has-data? (boolean (:has-data? strategy-series))}))
