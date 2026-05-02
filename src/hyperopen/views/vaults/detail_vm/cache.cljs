(ns hyperopen.views.vaults.detail-vm.cache
  (:require [hyperopen.vaults.detail.benchmarks :as benchmarks-model]
            [hyperopen.vaults.detail.metrics-bridge :as metrics-bridge]
            [hyperopen.vaults.detail.performance :as performance-model]
            [hyperopen.views.vaults.detail-vm.context :as context]))

(defonce summary-cache
  (atom nil))

(defonce chart-series-data-cache
  (atom nil))

(defonce benchmark-points-cache
  (atom nil))

(defonce performance-metrics-cache
  (atom nil))

(defn reset-cache! []
  (benchmarks-model/reset-vault-detail-benchmarks-cache!)
  (reset! metrics-bridge/last-metrics-request nil)
  (reset! summary-cache nil)
  (reset! chart-series-data-cache nil)
  (reset! benchmark-points-cache nil)
  (reset! performance-metrics-cache nil))

(defn- source-row-time-ms
  [row]
  (cond
    (map? row)
    (or (context/optional-number (:time-ms row))
        (context/optional-number (:timestamp row))
        (context/optional-number (:time row))
        (context/optional-number (:t row)))

    (and (sequential? row)
         (>= (count row) 2))
    (context/optional-number (first row))

    :else nil))

(defn- source-row-value
  [row]
  (cond
    (map? row)
    (or (context/optional-number (:value row))
        (context/optional-number (:account-value row))
        (context/optional-number (:accountValue row))
        (context/optional-number (:pnl row)))

    (and (sequential? row)
         (>= (count row) 2))
    (context/optional-number (second row))

    :else nil))

(defn sampled-series-source-version
  [rows]
  (let [rows* (vec (or rows []))
        row-count (count rows*)]
    (if (pos? row-count)
      (let [mid-idx (quot row-count 2)
            first-row (nth rows* 0 nil)
            mid-row (nth rows* mid-idx nil)
            last-row (nth rows* (dec row-count) nil)]
        (hash [row-count
               (source-row-time-ms first-row)
               (source-row-value first-row)
               (source-row-time-ms mid-row)
               (source-row-value mid-row)
               (source-row-time-ms last-row)
               (source-row-value last-row)]))
      0)))

(defn- summary-source-version
  [summary]
  (hash [(sampled-series-source-version (:accountValueHistory summary))
         (sampled-series-source-version (:pnlHistory summary))]))

(defn- selected-benchmark-labels
  [returns-benchmark-selector]
  (let [label-by-coin (or (:label-by-coin returns-benchmark-selector)
                          {})]
    (mapv (fn [coin]
            [coin
             (or (get label-by-coin coin)
                 coin)])
          (vec (or (:selected-coins returns-benchmark-selector)
                   [])))))

(defn benchmark-source-version-map
  [benchmark-points-by-coin selected-benchmark-coins]
  (into {}
        (map (fn [coin]
               [coin
                (sampled-series-source-version
                 (get benchmark-points-by-coin coin))]))
        selected-benchmark-coins))

(defn cached-portfolio-summary
  [details-base viewer-details snapshot-range]
  (let [cache @summary-cache]
    (if (and (map? cache)
             (= snapshot-range (:snapshot-range cache))
             (identical? details-base (:details-base cache))
             (identical? viewer-details (:viewer-details cache)))
      (:summary cache)
      (let [summary (performance-model/portfolio-summary (merge (or details-base {})
                                                                (or viewer-details {}))
                                                         snapshot-range)]
        (reset! summary-cache {:snapshot-range snapshot-range
                               :details-base details-base
                               :viewer-details viewer-details
                               :summary summary})
        summary))))

(defn cached-chart-series-data
  [state summary]
  (let [summary-version (summary-source-version summary)
        cache @chart-series-data-cache]
    (if (and (map? cache)
             (= summary-version (:summary-version cache)))
      (:series-by-key cache)
      (let [series-by-key (performance-model/chart-series-data state summary)]
        (reset! chart-series-data-cache {:summary-version summary-version
                                         :series-by-key series-by-key})
        series-by-key))))

(defn cached-benchmark-points-by-coin
  [state snapshot-range selected-benchmark-coins strategy-return-points strategy-window]
  (let [strategy-source-version (sampled-series-source-version strategy-return-points)
        strategy-window-version (hash (select-keys (or strategy-window {})
                                                   [:cutoff-ms :window-start-ms :window-end-ms
                                                    :complete-window? :returns-source :point-count]))
        candles (get state :candles)
        merged-index-rows (get-in state [:vaults :merged-index-rows])
        benchmark-details-by-address (get-in state [:vaults :benchmark-details-by-address])
        details-by-address (get-in state [:vaults :details-by-address])
        cache @benchmark-points-cache]
    (if (and (map? cache)
             (= snapshot-range (:snapshot-range cache))
             (= selected-benchmark-coins (:selected-benchmark-coins cache))
             (= strategy-source-version (:strategy-source-version cache))
             (= strategy-window-version (:strategy-window-version cache))
             (identical? candles (:candles cache))
             (identical? merged-index-rows (:merged-index-rows cache))
             (identical? benchmark-details-by-address (:benchmark-details-by-address cache))
             (identical? details-by-address (:details-by-address cache)))
      (:benchmark-points-by-coin cache)
      (let [benchmark-points-by-coin (benchmarks-model/benchmark-cumulative-return-points-by-coin
                                      state
                                      snapshot-range
                                      selected-benchmark-coins
                                      strategy-return-points
                                      strategy-window)]
        (reset! benchmark-points-cache {:snapshot-range snapshot-range
                                        :selected-benchmark-coins selected-benchmark-coins
                                        :strategy-source-version strategy-source-version
                                        :strategy-window-version strategy-window-version
                                        :candles candles
                                        :merged-index-rows merged-index-rows
                                        :benchmark-details-by-address benchmark-details-by-address
                                        :details-by-address details-by-address
                                        :benchmark-points-by-coin benchmark-points-by-coin})
        benchmark-points-by-coin))))

(defn cached-performance-metrics-model
  [state snapshot-range returns-benchmark-selector benchmark-context]
  (let [selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector)
                                          []))
        benchmark-labels (selected-benchmark-labels returns-benchmark-selector)
        request-signature (metrics-bridge/metrics-request-signature snapshot-range
                                                                    selected-benchmark-coins
                                                                    (:strategy-source-version benchmark-context)
                                                                    (:benchmark-source-version-map benchmark-context))
        metrics-result (get-in state [:vaults-ui :detail-performance-metrics-result])
        loading? (boolean (get-in state [:vaults-ui :detail-performance-metrics-loading?]))
        cache @performance-metrics-cache]
    (if (and (map? cache)
             (= request-signature (:request-signature cache))
             (= benchmark-labels (:benchmark-labels cache))
             (identical? metrics-result (:metrics-result cache))
             (= loading? (:loading? cache)))
      (:model cache)
      (let [model (performance-model/performance-metrics-model state
                                                               snapshot-range
                                                               returns-benchmark-selector
                                                               benchmark-context)]
        (reset! performance-metrics-cache {:request-signature request-signature
                                           :benchmark-labels benchmark-labels
                                           :metrics-result metrics-result
                                           :loading? loading?
                                           :model model})
        model))))
