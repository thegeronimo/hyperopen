(ns hyperopen.views.portfolio.vm.performance
  (:require [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.portfolio.application.metrics-bridge :as vm-metrics-bridge]))

(def ^:private performance-periods-per-year
  365)

(def ^:private hidden-portfolio-metric-keys
  #{:time-in-market})

(def ^:private benchmark-column-relative-metric-key-set
  (set portfolio-metrics/benchmark-column-relative-metric-keys))

(def ^:private empty-source-version-counter
  0)

(defn- current-worker
  [worker-ref]
  (cond
    (nil? worker-ref) nil
    (satisfies? IDeref worker-ref) @worker-ref
    :else worker-ref))

(defn benchmark-performance-column
  [benchmark-cumulative-rows label-by-coin coin]
  (let [benchmark-daily-rows (portfolio-metrics/daily-compounded-returns benchmark-cumulative-rows)
        values (if (seq benchmark-cumulative-rows)
                 (portfolio-metrics/compute-performance-metrics {:strategy-cumulative-rows benchmark-cumulative-rows
                                                                 :strategy-daily-rows benchmark-daily-rows
                                                                 :rf 0
                                                                 :periods-per-year performance-periods-per-year})
                 {})]
    {:coin coin
     :label (or (get label-by-coin coin)
                coin)
     :cumulative-rows benchmark-cumulative-rows
     :daily-rows benchmark-daily-rows
     :values values}))

(defn with-performance-metric-columns
  [groups portfolio-values benchmark-columns]
  (let [primary-benchmark-values (or (some-> benchmark-columns first :values)
                                     {})
        benchmark-values-by-coin (into {}
                                       (map (fn [{:keys [coin values]}]
                                              [coin values]))
                                       benchmark-columns)]
    (mapv (fn [{:keys [rows] :as group}]
            (assoc group
                   :rows (mapv (fn [{:keys [key] :as row}]
                                 (let [render-relative-in-benchmark-columns? (and (seq benchmark-columns)
                                                                                  (contains? benchmark-column-relative-metric-key-set
                                                                                             key))]
                                   (assoc row
                                          :portfolio-value (when-not render-relative-in-benchmark-columns?
                                                             (get portfolio-values key))
                                          :portfolio-status (when-not render-relative-in-benchmark-columns?
                                                              (get-in portfolio-values [:metric-status key]))
                                          :portfolio-reason (when-not render-relative-in-benchmark-columns?
                                                             (get-in portfolio-values [:metric-reason key]))
                                          :benchmark-value (get primary-benchmark-values key)
                                          :benchmark-status (get-in primary-benchmark-values [:metric-status key])
                                          :benchmark-reason (get-in primary-benchmark-values [:metric-reason key])
                                          :benchmark-values (into {}
                                                                 (map (fn [{:keys [coin]}]
                                                                        [coin (get-in benchmark-values-by-coin [coin key])]))
                                                                 benchmark-columns)
                                          :benchmark-statuses (into {}
                                                                   (map (fn [{:keys [coin]}]
                                                                          [coin (get-in benchmark-values-by-coin [coin :metric-status key])]))
                                                                   benchmark-columns)
                                          :benchmark-reasons (into {}
                                                                  (map (fn [{:keys [coin]}]
                                                                         [coin (get-in benchmark-values-by-coin [coin :metric-reason key])]))
                                                                  benchmark-columns))))
                               (or rows []))))
          (or groups []))))

(defn remove-hidden-portfolio-metric-rows
  [groups]
  (->> (or groups [])
       (keep (fn [{:keys [rows] :as group}]
               (let [rows* (->> (or rows [])
                                (remove (fn [{:keys [key]}]
                                          (contains? hidden-portfolio-metric-keys key)))
                                vec)]
                 (when (seq rows*)
                   (assoc group :rows rows*)))))
       vec))

(defn build-metrics-request-data
  [strategy-cumulative-rows benchmark-cumulative-rows-by-coin selected-benchmark-coins]
  (let [benchmark-requests (mapv (fn [coin]
                                   {:coin coin
                                    :request {:strategy-cumulative-rows (or (get benchmark-cumulative-rows-by-coin coin)
                                                                            [])}})
                                 selected-benchmark-coins)
        portfolio-request {:strategy-cumulative-rows strategy-cumulative-rows
                           :strategy-daily-rows (portfolio-metrics/daily-compounded-returns strategy-cumulative-rows)}]
    {:portfolio-request portfolio-request
     :benchmark-requests benchmark-requests}))

(defn compute-metrics-sync
  [request-data]
  (vm-metrics-bridge/compute-metrics-sync request-data))

(def ^:dynamic *metrics-worker*
  vm-metrics-bridge/metrics-worker)

(def ^:dynamic *last-metrics-request*
  vm-metrics-bridge/last-metrics-request)

(def ^:dynamic *metrics-request-signature*
  vm-metrics-bridge/metrics-request-signature)

(def ^:dynamic *build-metrics-request-data*
  build-metrics-request-data)

(def ^:dynamic *request-metrics-computation!*
  vm-metrics-bridge/request-metrics-computation!)

(def ^:dynamic *compute-metrics-sync*
  compute-metrics-sync)

(defn performance-metrics-model
  [state summary-time-range returns-benchmark-selector benchmark-context]
  (let [strategy-cumulative-rows (or (:strategy-cumulative-rows benchmark-context)
                                     [])
        benchmark-cumulative-rows-by-coin (or (:benchmark-cumulative-rows-by-coin benchmark-context)
                                              {})
        strategy-source-version (or (:strategy-source-version benchmark-context)
                                    empty-source-version-counter)
        benchmark-source-version-map (or (:benchmark-source-version-map benchmark-context)
                                         {})
        selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector)
                                          []))
        benchmark-label-by-coin (or (:label-by-coin returns-benchmark-selector)
                                    {})
        request-signature (*metrics-request-signature* summary-time-range
                                                       selected-benchmark-coins
                                                       strategy-source-version
                                                       benchmark-source-version-map)
        worker (current-worker *metrics-worker*)
        ;; The candle store is keyed by INTERVAL and every preset resolves to a
        ;; different one, so on the first render after a range switch the new
        ;; interval's slot is still empty and every selected benchmark has zero
        ;; rows. Posting then produced a full worker job whose benchmark side
        ;; was `[]`; the reply was applied, `benchmark-min?` failed, and the six
        ;; benchmark-relative metrics plus their whole labelled group vanished
        ;; from the tearsheet and reappeared ~700ms later when the real candles
        ;; landed. Waiting costs nothing — the previous result stays on screen,
        ;; now labelled stale — and it halves the worker jobs per switch.
        benchmark-rows-ready? (every? (fn [coin]
                                        (seq (get benchmark-cumulative-rows-by-coin coin)))
                                      selected-benchmark-coins)
        request-signature-changed? (not= request-signature
                                        (:signature @*last-metrics-request*))
        post-request? (and worker
                           request-signature-changed?
                           benchmark-rows-ready?)
        request-data (when (or (nil? worker)
                               post-request?)
                       (*build-metrics-request-data* strategy-cumulative-rows
                                                     benchmark-cumulative-rows-by-coin
                                                     selected-benchmark-coins))
        _ (when (and post-request? request-data)
            (*request-metrics-computation!* request-data request-signature))
        metrics-result (if worker
                         (get-in state [:portfolio-ui :metrics-result])
                         (*compute-metrics-sync* request-data))
        loading? (if worker
                   (boolean (get-in state [:portfolio-ui :metrics-loading?]))
                   false)
        ;; True when the numbers on screen answer a DIFFERENT QUESTION than the
        ;; one currently selected — a different window, or a different benchmark
        ;; set. They stay visible on purpose (blanking is the churn this work
        ;; removed); the label is what keeps that honest.
        ;;
        ;; Deliberately not the whole signature. The rest of it is data
        ;; freshness: `strategy-source-version` and the per-coin benchmark
        ;; versions move whenever live data refreshes, which on an active account
        ;; is constantly, and keying on those made the badge blink on and off
        ;; every few hundred milliseconds while nothing the trader chose had
        ;; changed. "A few seconds behind" is not stale; "answers the wrong
        ;; window" is.
        question-of (juxt :summary-time-range :selected-benchmark-coins)
        stale? (boolean (and worker
                             (some? metrics-result)
                             (not= (question-of request-signature)
                                   (question-of (get-in state [:portfolio-ui :metrics-result-signature])))))
        portfolio-values (or (:portfolio-values metrics-result) {})
        benchmark-values-by-coin-result (or (:benchmark-values-by-coin metrics-result) {})
        benchmark-columns (mapv (fn [coin]
                                  {:coin coin
                                   :label (or (get benchmark-label-by-coin coin) coin)
                                   :cumulative-rows (or (get benchmark-cumulative-rows-by-coin coin)
                                                        [])
                                   :values (or (get benchmark-values-by-coin-result coin) {})})
                                selected-benchmark-coins)
        primary-benchmark-column (first benchmark-columns)
        benchmark-coin (:coin primary-benchmark-column)
        benchmark-values (or (:values primary-benchmark-column)
                             {})
        groups (with-performance-metric-columns
                 (remove-hidden-portfolio-metric-rows
                  (portfolio-metrics/metric-rows portfolio-values))
                 portfolio-values
                 benchmark-columns)
        benchmark-label (:label primary-benchmark-column)]
    {:loading? loading?
     :stale? stale?
     :benchmark-selected? (boolean (seq benchmark-columns))
     :benchmark-coin benchmark-coin
     :benchmark-label benchmark-label
     :benchmark-coins (mapv :coin benchmark-columns)
     :benchmark-columns (mapv (fn [{:keys [coin label]}]
                                {:coin coin
                                 :label label})
                              benchmark-columns)
     :values portfolio-values
     :benchmark-values benchmark-values
     :groups groups}))
