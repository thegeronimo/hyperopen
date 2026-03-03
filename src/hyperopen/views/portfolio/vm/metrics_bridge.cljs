(ns hyperopen.views.portfolio.vm.metrics-bridge
  (:require [goog.object :as gobj]
            [hyperopen.views.portfolio.vm.history :as vm-history]
            [hyperopen.views.portfolio.vm.benchmarks :as vm-benchmarks]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.portfolio.metrics.parsing :as parsing]
            [hyperopen.views.portfolio.vm.constants :as constants]))

(defonce metrics-worker
  (when (and (exists? js/window) (exists? js/Worker))
    (let [worker (js/Worker. "/js/portfolio_worker.js")]
      worker)))

(defonce last-metrics-request (atom nil))

(defn normalize-worker-metric-values
  [values]
  (if values
    (js->clj values :keywordize-keys true)
    nil))

(defn normalize-worker-metrics-result
  [result]
  (when result
    (let [clj-result (js->clj result :keywordize-keys true)]
      (-> clj-result
          (update :values normalize-worker-metric-values)))))

(defn request-metrics-computation!
  [request-data on-complete]
  (if metrics-worker
    (do
      (set! (.-onmessage metrics-worker)
            (fn [event]
              (let [data (.-data event)]
                (when (= (gobj/get data "type") "metrics-result")
                  (on-complete (normalize-worker-metrics-result (gobj/get data "payload")))))))
      (.postMessage metrics-worker (clj->js {:type "compute-metrics"
                                             :payload request-data})))
    (let [result (portfolio-metrics/compute-performance-metrics request-data)]
      (on-complete {:values result
                    :rows (portfolio-metrics/metric-rows result)}))))

(defn metrics-request-signature
  [strategy-rows benchmark-daily-rows rf-rate]
  (let [strategy-count (count strategy-rows)
        strategy-last (when (seq strategy-rows)
                        (vm-history/history-point-time-ms (last strategy-rows)))
        benchmark-count (count benchmark-daily-rows)
        benchmark-last (when (seq benchmark-daily-rows)
                         (:time-ms (last benchmark-daily-rows)))]
    (str strategy-count "-" strategy-last "-" benchmark-count "-" benchmark-last "-" rf-rate)))

(defn request-benchmark-daily-rows
  [benchmark]
  (or (:performance-daily-rows benchmark) []))

(defn request-strategy-daily-rows
  [strategy-rows]
  (or strategy-rows []))

(defn compute-metrics-sync
  [request-data]
  (let [result (portfolio-metrics/compute-performance-metrics request-data)]
    {:values result
     :rows (portfolio-metrics/metric-rows result)}))

(defn vault-snapshot-range-keys
  []
  ["1d" "7d" "30d"])

(defn vault-snapshot-point-value
  [point]
  (when point
    (or (parsing/optional-number (nth point 1 nil))
        (parsing/optional-number (:value point)))))

(defn normalize-vault-snapshot-return
  [span-key row]
  (let [returns-map (:returns row)]
    (when returns-map
      (let [val (get returns-map span-key)]
        (when (parsing/finite-number? val)
          val)))))

(defn vault-benchmark-snapshot-values
  [vault-row]
  (let [keys (vault-snapshot-range-keys)]
    (into {}
          (map (fn [k]
                 [k (normalize-vault-snapshot-return k vault-row)]))
          keys)))

(defn aligned-vault-return-rows
  [vault-row strategy-points]
  (let [vault-points (vm-history/normalized-history-rows
                      (or (:history vault-row) []))]
    (if (and (seq vault-points) (seq strategy-points))
      (let [strategy-start-ms (:time-ms (first strategy-points))
            anchor-point (last (filter #(<= (:time-ms %) strategy-start-ms) vault-points))
            anchor-val (:value anchor-point)
            relevant-vaults (filter #(>= (:time-ms %) strategy-start-ms) vault-points)
            vault-by-time (into {} (map (juxt :time-ms :value) relevant-vaults))]
        (if (parsing/finite-number? anchor-val)
          (->> strategy-points
               (keep (fn [{:keys [time-ms]}]
                       (when-let [v-val (get vault-by-time time-ms)]
                         (let [factor (/ v-val anchor-val)
                               percent (* 100 (- factor 1))]
                           (when (parsing/finite-number? percent)
                             {:time-ms time-ms
                              :value percent})))))
               vec)
          []))
      [])))
