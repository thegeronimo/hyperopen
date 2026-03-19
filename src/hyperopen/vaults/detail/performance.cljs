(ns hyperopen.vaults.detail.performance
  (:require [clojure.string :as str]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.vaults.detail.metrics-bridge :as metrics-bridge]))

(def ^:private performance-periods-per-year
  365)

(def ^:private empty-source-version-counter
  0)

(defn- optional-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [trimmed (str/trim value)]
      (when (seq trimmed)
        (let [parsed (js/Number trimmed)]
          (when (js/isFinite parsed)
            parsed))))

    :else nil))

(defn- normalize-percent-value
  [value]
  (when-let [n (optional-number value)]
    (if (<= (js/Math.abs n) 1)
      (* 100 n)
      n)))

(defn- snapshot-point-value
  [entry]
  (cond
    (number? entry) entry

    (and (sequential? entry)
         (>= (count entry) 2))
    (optional-number (second entry))

    (map? entry)
    (or (optional-number (:value entry))
        (optional-number (:pnl entry))
        (optional-number (:account-value entry))
        (optional-number (:accountValue entry)))

    :else
    nil))

(defn- snapshot-preview-entry
  [row snapshot-range]
  (let [entry (get-in row [:snapshot-preview-by-key snapshot-range])]
    (when (map? entry)
      entry)))

(defn- last-snapshot-value
  [snapshot-values]
  (when (sequential? snapshot-values)
    (some->> snapshot-values
             (keep snapshot-point-value)
             seq
             last)))

(defn snapshot-value-by-range
  [row snapshot-range tvl]
  (let [raw (or (some-> (snapshot-preview-entry row snapshot-range)
                        :last-value
                        optional-number)
                (some-> (get-in row [:snapshot-by-key snapshot-range])
                        last-snapshot-value
                        optional-number))]
    (cond
      (nil? raw) nil
      (and (number? tvl)
           (pos? tvl)
           (> (js/Math.abs raw) 1000))
      (* 100 (/ raw tvl))
      :else
      (normalize-percent-value raw))))

(defn- with-utc-months-offset
  [time-ms months]
  (let [date (js/Date. time-ms)]
    (.setUTCMonth date (+ (.getUTCMonth date) months))
    (.getTime date)))

(defn- with-utc-years-offset
  [time-ms years]
  (let [date (js/Date. time-ms)]
    (.setUTCFullYear date (+ (.getUTCFullYear date) years))
    (.getTime date)))

(defn- summary-window-cutoff-ms
  [snapshot-range end-time-ms]
  (when (number? end-time-ms)
    (case snapshot-range
      :three-month (with-utc-months-offset end-time-ms -3)
      :six-month (with-utc-months-offset end-time-ms -6)
      :one-year (with-utc-years-offset end-time-ms -1)
      :two-year (with-utc-years-offset end-time-ms -2)
      nil)))

(defn- normalized-history-rows
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [time-ms (portfolio-metrics/history-point-time-ms row)
                     value (portfolio-metrics/history-point-value row)]
                 (when (and (number? time-ms)
                            (number? value))
                   [time-ms value]))))
       (sort-by first)
       vec))

(defn- history-window-rows
  [rows cutoff-ms]
  (if (number? cutoff-ms)
    (->> rows
         (filter (fn [[time-ms _value]]
                   (>= time-ms cutoff-ms)))
         vec)
    []))

(defn- rebase-history-rows
  [rows]
  (if-let [baseline (some-> rows first second)]
    (mapv (fn [[time-ms value]]
            [time-ms (- value baseline)])
          rows)
    []))

(defn- derived-portfolio-summary
  [all-time-summary snapshot-range]
  (let [account-rows (normalized-history-rows (:accountValueHistory all-time-summary))
        pnl-rows (normalized-history-rows (:pnlHistory all-time-summary))
        end-time-ms (or (some-> account-rows last first)
                        (some-> pnl-rows last first))
        cutoff-ms (summary-window-cutoff-ms snapshot-range end-time-ms)]
    (when (number? cutoff-ms)
      (let [account-window (history-window-rows account-rows cutoff-ms)
            pnl-window (history-window-rows pnl-rows cutoff-ms)
            pnl-window* (rebase-history-rows pnl-window)]
        (when (or (seq account-window)
                  (seq pnl-window*))
          (assoc all-time-summary
                 :accountValueHistory account-window
                 :pnlHistory pnl-window*))))))

(defn portfolio-summary
  [details snapshot-range]
  (let [portfolio (or (:portfolio details) {})
        all-time-summary (get portfolio :all-time)]
    (or (get portfolio snapshot-range)
        (derived-portfolio-summary all-time-summary snapshot-range)
        (get portfolio :month)
        (get portfolio :week)
        (get portfolio :day)
        all-time-summary
        {})))

(defn portfolio-summary-by-range
  [details snapshot-range]
  (let [portfolio (or (:portfolio details) {})
        direct-summary (get portfolio snapshot-range)
        all-time-summary (get portfolio :all-time)]
    (or direct-summary
        (derived-portfolio-summary all-time-summary snapshot-range))))

(defn summary-cumulative-return-percent
  [state summary]
  (some->> (portfolio-metrics/returns-history-rows state summary :all)
           last
           portfolio-metrics/history-point-value
           optional-number))

(defn- history-points
  [rows]
  (->> (if (sequential? rows) rows [])
       (keep (fn [row]
               (let [time-ms (portfolio-metrics/history-point-time-ms row)
                     value (portfolio-metrics/history-point-value row)]
                 (when (and (number? time-ms)
                            (number? value))
                   {:time-ms time-ms
                    :value value}))))
       (sort-by :time-ms)
       vec))

(defn- normalize-chart-point-value
  [series value]
  (when (number? value)
    (if (= series :returns)
      (let [rounded (/ (js/Math.round (* value 100)) 100)]
        (if (== rounded -0)
          0
          rounded))
      value)))

(defn- rows->chart-points
  [rows series]
  (->> rows
       (map-indexed (fn [idx row]
                      (let [time-ms (portfolio-metrics/history-point-time-ms row)
                            value (portfolio-metrics/history-point-value row)
                            value* (normalize-chart-point-value series value)]
                        (when (and (number? time-ms)
                                   (number? value*))
                          {:index idx
                           :time-ms time-ms
                           :value value*}))))
       (keep identity)
       vec))

(defn- returns-history-points
  [state summary]
  (rows->chart-points (portfolio-metrics/returns-history-rows state summary :all)
                      :returns))

(defn chart-series-data
  [state summary]
  {:account-value (history-points (:accountValueHistory summary))
   :pnl (history-points (:pnlHistory summary))
   :returns (returns-history-points state summary)})

(defn cumulative-rows
  [points]
  (mapv (fn [{:keys [time-ms value]}]
          [time-ms value])
        (or points [])))

(defn- current-worker
  [worker-ref]
  (cond
    (nil? worker-ref) nil
    (satisfies? IDeref worker-ref) @worker-ref
    :else worker-ref))

(defn- benchmark-performance-column
  [benchmark-cumulative-rows label-by-coin coin]
  (let [benchmark-daily-rows (portfolio-metrics/daily-compounded-returns benchmark-cumulative-rows)
        values (if (seq benchmark-daily-rows)
                 (portfolio-metrics/compute-performance-metrics {:strategy-daily-rows benchmark-daily-rows
                                                                 :rf 0
                                                                 :periods-per-year performance-periods-per-year
                                                                 :compounded true})
                 {})]
    {:coin coin
     :label (or (get label-by-coin coin)
                coin)
     :daily-rows benchmark-daily-rows
     :values values}))

(defn- with-performance-metric-columns
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
                                 (assoc row
                                        :portfolio-value (get portfolio-values key)
                                        :portfolio-status (get-in portfolio-values [:metric-status key])
                                        :portfolio-reason (get-in portfolio-values [:metric-reason key])
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
                                                                benchmark-columns)))
                               (or rows []))))
          (or groups []))))

(defn build-metrics-request-data
  [strategy-cumulative-rows benchmark-cumulative-rows-by-coin selected-benchmark-coins]
  (let [benchmark-requests (mapv (fn [coin]
                                   {:coin coin
                                    :request {:strategy-cumulative-rows (or (get benchmark-cumulative-rows-by-coin coin)
                                                                            [])}})
                                 selected-benchmark-coins)
        portfolio-request {:strategy-cumulative-rows strategy-cumulative-rows
                           :strategy-daily-rows (portfolio-metrics/daily-compounded-returns strategy-cumulative-rows)
                           :benchmark-cumulative-rows (or (some-> benchmark-requests first :request :strategy-cumulative-rows)
                                                          [])}]
    {:portfolio-request portfolio-request
     :benchmark-requests benchmark-requests}))

(defn compute-metrics-sync
  [request-data]
  (metrics-bridge/compute-metrics-sync request-data))

(def ^:dynamic *metrics-worker*
  metrics-bridge/metrics-worker)

(def ^:dynamic *last-metrics-request*
  metrics-bridge/last-metrics-request)

(def ^:dynamic *metrics-request-signature*
  metrics-bridge/metrics-request-signature)

(def ^:dynamic *build-metrics-request-data*
  build-metrics-request-data)

(def ^:dynamic *request-metrics-computation!*
  metrics-bridge/request-metrics-computation!)

(def ^:dynamic *compute-metrics-sync*
  compute-metrics-sync)

(defn- performance-metrics-from-result
  [returns-benchmark-selector metrics-result loading?]
  (let [selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector)
                                          []))
        benchmark-label-by-coin (or (:label-by-coin returns-benchmark-selector)
                                    {})
        portfolio-values (or (:portfolio-values metrics-result) {})
        benchmark-values-by-coin-result (or (:benchmark-values-by-coin metrics-result) {})
        benchmark-columns (mapv (fn [coin]
                                  {:coin coin
                                   :label (or (get benchmark-label-by-coin coin)
                                              coin)
                                   :values (or (get benchmark-values-by-coin-result coin)
                                               {})})
                                selected-benchmark-coins)
        primary-benchmark-column (first benchmark-columns)
        benchmark-coin (:coin primary-benchmark-column)
        benchmark-values (or (:values primary-benchmark-column)
                             {})
        groups (with-performance-metric-columns (portfolio-metrics/metric-rows portfolio-values)
                 portfolio-values
                 benchmark-columns)
        benchmark-label (:label primary-benchmark-column)]
    {:loading? loading?
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

(defn performance-metrics-model
  ([returns-benchmark-selector strategy-cumulative-rows benchmark-cumulative-rows-by-coin]
   (let [selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector)
                                           []))
         request-data (*build-metrics-request-data* strategy-cumulative-rows
                                                    benchmark-cumulative-rows-by-coin
                                                    selected-benchmark-coins)
         metrics-result (*compute-metrics-sync* request-data)]
     (performance-metrics-from-result returns-benchmark-selector metrics-result false)))
  ([state snapshot-range returns-benchmark-selector benchmark-context]
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
         request-signature (*metrics-request-signature* snapshot-range
                                                        selected-benchmark-coins
                                                        strategy-source-version
                                                        benchmark-source-version-map)
         worker (current-worker *metrics-worker*)
         request-signature-changed? (not= request-signature
                                         (:signature @*last-metrics-request*))
         request-data (when (or (nil? worker)
                                request-signature-changed?)
                        (*build-metrics-request-data* strategy-cumulative-rows
                                                      benchmark-cumulative-rows-by-coin
                                                      selected-benchmark-coins))
         _ (when (and worker
                      request-signature-changed?
                      request-data)
             (*request-metrics-computation!* request-data request-signature))
         metrics-result (if worker
                          (get-in state [:vaults-ui :detail-performance-metrics-result])
                          (*compute-metrics-sync* request-data))
         loading? (if worker
                    (boolean (get-in state [:vaults-ui :detail-performance-metrics-loading?]))
                    false)]
     (performance-metrics-from-result returns-benchmark-selector metrics-result loading?))))
