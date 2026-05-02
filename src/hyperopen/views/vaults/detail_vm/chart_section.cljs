(ns hyperopen.views.vaults.detail-vm.chart-section
  (:require [hyperopen.vaults.detail.benchmarks :as benchmarks-model]
            [hyperopen.vaults.detail.performance :as performance-model]
            [hyperopen.vaults.detail.types :as detail-types]
            [hyperopen.views.vaults.detail.chart :as chart-model]
            [hyperopen.views.vaults.detail-vm.cache :as cache]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]))

(def ^:private chart-timeframe-options
  [{:value :day
    :label "24H"}
   {:value :week
    :label "7D"}
   {:value :month
    :label "30D"}
   {:value :three-month
    :label "3M"}
   {:value :six-month
    :label "6M"}
   {:value :one-year
    :label "1Y"}
   {:value :two-year
    :label "2Y"}
   {:value :all-time
    :label "All-time"}])

(defn- resolve-chart-series
  [series-by-key selected-series]
  (let [selected* (vault-ui-state/normalize-vault-detail-chart-series selected-series)
        has-series? (fn [k]
                      (seq (get series-by-key k)))]
    (cond
      (= :returns selected*) :returns
      (has-series? selected*) selected*
      (has-series? :pnl) :pnl
      (has-series? :account-value) :account-value
      :else selected*)))

(defn- benchmark-history-pending?
  [selected-series activity-tab strategy-return-points selected-benchmark-coins benchmark-points-by-coin]
  (and (or (= selected-series :returns)
           (= activity-tab :performance-metrics))
       (seq strategy-return-points)
       (seq selected-benchmark-coins)
       (boolean
        (some (fn [coin]
                (and (seq coin)
                     (nil? (detail-types/vault-benchmark-address coin))
                     (empty? (get benchmark-points-by-coin coin))))
              selected-benchmark-coins))))

(defn- background-status-model
  [benchmark-history-pending?]
  (let [items (cond-> []
                benchmark-history-pending?
                (conj {:id :benchmark-history
                       :label "Benchmark history"}))]
    {:visible? (boolean (seq items))
     :title "Vault analytics are still syncing"
     :detail "The chart is ready. The remaining analytics will fill in automatically."
     :items items}))

(defn- build-benchmark-series
  [selected-series selected-benchmark-coins benchmark-label-by-coin benchmark-points-by-coin]
  (if (= selected-series :returns)
    (mapv (fn [idx coin]
            {:id (keyword (str "benchmark-" idx))
             :coin coin
             :label (or (get benchmark-label-by-coin coin)
                        coin)
             :stroke (chart-model/benchmark-series-stroke idx)
             :raw-points (vec (or (get benchmark-points-by-coin coin) []))})
          (range)
          selected-benchmark-coins)
    []))

(defn- build-benchmark-context
  [strategy-return-points benchmark-points-by-coin selected-benchmark-coins]
  (let [strategy-cumulative-rows (performance-model/cumulative-rows strategy-return-points)
        benchmark-cumulative-rows-by-coin
        (into {}
              (map (fn [coin]
                     [coin (performance-model/cumulative-rows
                            (get benchmark-points-by-coin coin))]))
              selected-benchmark-coins)]
    {:strategy-cumulative-rows strategy-cumulative-rows
     :benchmark-cumulative-rows-by-coin benchmark-cumulative-rows-by-coin
     :strategy-source-version (cache/sampled-series-source-version strategy-cumulative-rows)
     :benchmark-source-version-map (cache/benchmark-source-version-map benchmark-cumulative-rows-by-coin
                                                                       selected-benchmark-coins)}))

(defn build-vault-detail-chart-section
  [state snapshot-range activity-tab chart-series details-base viewer-details metrics-context vault-label]
  (let [details (merge (or details-base {})
                       (or viewer-details {}))
        summary (cache/cached-portfolio-summary details-base viewer-details snapshot-range)
        returns-history-context (performance-model/returns-history-context state details snapshot-range)
        returns-benchmark-selector (benchmarks-model/returns-benchmark-selector-model state)
        series-by-key (cache/cached-chart-series-data state summary)
        selected-series (resolve-chart-series series-by-key chart-series)
        strategy-return-points (vec (or (get (performance-model/chart-series-data
                                              state summary (:summary returns-history-context))
                                             :returns)
                                        []))
        strategy-raw-points (if (= selected-series :returns)
                              strategy-return-points
                              (vec (or (get series-by-key selected-series) [])))
        selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector) []))
        benchmark-label-by-coin (or (:label-by-coin returns-benchmark-selector) {})
        benchmark-points-by-coin (cache/cached-benchmark-points-by-coin state snapshot-range
                                                                        selected-benchmark-coins
                                                                        strategy-return-points
                                                                        returns-history-context)
        benchmark-history-loading? (benchmark-history-pending? selected-series activity-tab
                                                               strategy-return-points
                                                               selected-benchmark-coins
                                                               benchmark-points-by-coin)
        benchmark-series (build-benchmark-series selected-series
                                                 selected-benchmark-coins
                                                 benchmark-label-by-coin
                                                 benchmark-points-by-coin)
        raw-series (cond-> [{:id :strategy
                             :label vault-label
                             :stroke (chart-model/strategy-series-stroke selected-series)
                             :raw-points strategy-raw-points}]
                     (seq benchmark-series)
                     (into benchmark-series))
        chart-model* (chart-model/build-chart-model {:selected-series selected-series
                                                     :raw-series raw-series})
        series (:series chart-model*)
        benchmark-context (build-benchmark-context strategy-return-points
                                                   benchmark-points-by-coin
                                                   selected-benchmark-coins)
        performance-metrics-base (cache/cached-performance-metrics-model state
                                                                         snapshot-range
                                                                         returns-benchmark-selector
                                                                         benchmark-context)
        performance-metrics (assoc performance-metrics-base
                                   :loading? (or benchmark-history-loading?
                                                 (:loading? performance-metrics-base)))
        return-for-range (:return-for-range metrics-context)
        month-return (:month-return metrics-context)]
    {:background-status (background-status-model benchmark-history-loading?)
     :snapshot-range snapshot-range
     :snapshot {:day (return-for-range :day)
                :week (return-for-range :week)
                :month month-return
                :all-time (return-for-range :all-time)}
     :performance-metrics (assoc performance-metrics
                                 :vault-label vault-label
                                 :timeframe-options chart-timeframe-options
                                 :selected-timeframe snapshot-range
                                 :timeframe-menu-open? (true? (get-in state [:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?])))
     :chart {:axis-kind (case selected-series
                          :pnl :pnl
                          :returns :returns
                          :account-value :account-value
                          :account-value)
             :series-tabs [{:value :returns
                            :label "Returns"}
                           {:value :account-value
                            :label "Account Value"}
                           {:value :pnl
                            :label "PNL"}]
             :timeframe-options chart-timeframe-options
             :timeframe-menu-open? (true? (get-in state [:vaults-ui :detail-chart-timeframe-dropdown-open?]))
             :selected-timeframe snapshot-range
             :selected-series selected-series
             :returns-benchmark returns-benchmark-selector
             :strategy-window returns-history-context
             :y-ticks (:y-ticks chart-model*)
             :points (:points chart-model*)
             :series series}}))
