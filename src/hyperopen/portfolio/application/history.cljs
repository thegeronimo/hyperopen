(ns hyperopen.portfolio.application.history
  (:require [clojure.string :as str]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.metrics.parsing :as parsing]))

(def ^:private supported-benchmark-time-ranges
  #{:day :week :month :three-month :six-month :one-year :two-year :all-time})

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

(defn summary-window-cutoff-ms
  [time-range last-ms]
  (when (and last-ms (number? last-ms))
    (case time-range
      :day (- last-ms (* 24 60 60 1000))
      :week (- last-ms (* 7 24 60 60 1000))
      :month (- last-ms (* 30 24 60 60 1000))
      :three-month (with-utc-months-offset last-ms -3)
      :six-month (with-utc-months-offset last-ms -6)
      :one-year (with-utc-years-offset last-ms -1)
      :two-year (with-utc-years-offset last-ms -2)
      :all-time nil
      nil)))

(defn history-point-value
  [row]
  (parsing/history-point-value row))

(defn history-point-time-ms
  [row]
  (parsing/history-point-time-ms row))

(defn account-value-history-rows
  [summary]
  (or (:accountValueHistory summary) []))

(defn pnl-history-rows
  [summary]
  (or (:pnlHistory summary) []))

(defn normalized-history-rows
  [rows]
  (->> rows
       (keep (fn [row]
               (let [time-ms (history-point-time-ms row)
                     value (history-point-value row)]
                 (when (and (parsing/finite-number? time-ms)
                            (parsing/finite-number? value))
                   {:time-ms time-ms
                    :value value}))))
       (sort-by :time-ms)
       vec))

(defn history-window-rows
  [rows cutoff-ms]
  (if (number? cutoff-ms)
    (let [filtered (filter (fn [{:keys [time-ms]}]
                             (>= time-ms cutoff-ms))
                           rows)]
      (vec filtered))
    (vec rows)))

(defn rebase-history-rows
  [rows base-value]
  (if (parsing/finite-number? base-value)
    (mapv (fn [row]
            (update row :value - base-value))
          rows)
    rows))

(defn benchmark-time-range
  [requested-summary-time-range effective-key]
  (let [requested-range (portfolio-actions/normalize-summary-time-range requested-summary-time-range)]
    (if-let [effective-range (cond
                               (contains? supported-benchmark-time-ranges effective-key)
                               effective-key
                               (and (keyword? effective-key)
                                    (str/starts-with? (name effective-key) "perp-"))
                               (let [unscoped-key (keyword (subs (name effective-key) 5))]
                                 (when (contains? supported-benchmark-time-ranges unscoped-key)
                                   unscoped-key))
                               :else nil)]
      effective-range
      requested-range)))

(defn market-benchmark-anchor-time-ms
  [summary-time-range strategy-time-points]
  (when-let [last-time-ms (some-> strategy-time-points last :time-ms)]
    (summary-window-cutoff-ms summary-time-range last-time-ms)))

(defn range-all-time-key
  [time-range]
  (if (= time-range :all-time) :all :month))

(defn pnl-delta
  [summary]
  (let [pnl-history (pnl-history-rows summary)]
    (if (seq pnl-history)
      (let [first-pnl (history-point-value (first pnl-history))
            last-pnl (history-point-value (last pnl-history))]
        (when (and (parsing/finite-number? first-pnl)
                   (parsing/finite-number? last-pnl))
          (- last-pnl first-pnl)))
      0)))

(defn candle-point-close
  [row]
  (cond
    (map? row) (or (parsing/optional-number (:c row))
                   (parsing/optional-number (:close row)))
    (vector? row) (parsing/optional-number (nth row 4 nil))
    :else nil))

(defn- benchmark-candle-rows
  [rows]
  (loop [value rows
         depth 0]
    (cond
      (sequential? value) value
      (and (map? value) (< depth 4))
      (let [nested (or (:rows value)
                       (:data value)
                       (:candles value))]
        (if (some? nested)
          (recur nested (inc depth))
          []))
      :else [])))

(defn- dedupe-time-ms-last-write-wins
  [rows]
  (reduce (fn [acc row]
            (if (= (:time-ms row) (:time-ms (peek acc)))
              (conj (pop acc) row)
              (conj acc row)))
          []
          rows))

(defn benchmark-candle-points
  [rows]
  (->> (benchmark-candle-rows rows)
       (keep (fn [row]
               (let [time-ms (history-point-time-ms row)
                     close (candle-point-close row)]
                 (when (and (parsing/finite-number? time-ms)
                            (parsing/finite-number? close)
                            (pos? close))
                   {:time-ms time-ms
                    :value close}))))
       (sort-by :time-ms)
       dedupe-time-ms-last-write-wins
       vec))

(defn- latest-benchmark-point-at-or-before
  [benchmark-points target-time-ms]
  (let [benchmark-count (count benchmark-points)]
    (loop [idx 0
           latest nil]
      (if (>= idx benchmark-count)
        latest
        (let [{point-time-ms :time-ms :as point} (nth benchmark-points idx)]
          (if (and (parsing/finite-number? point-time-ms)
                   (<= point-time-ms target-time-ms))
            (recur (inc idx) point)
            latest))))))

(defn- benchmark-anchor
  [benchmark-points anchor-time-ms]
  (let [first-point (first benchmark-points)]
    (when (seq benchmark-points)
      (if (parsing/finite-number? anchor-time-ms)
        (if-let [{anchor-close :value} (latest-benchmark-point-at-or-before benchmark-points
                                                                            anchor-time-ms)]
          {:time-ms anchor-time-ms
           :close anchor-close}
          (when first-point
            {:time-ms (:time-ms first-point)
             :close (:value first-point)}))
        (when first-point
          {:time-ms (:time-ms first-point)
           :close (:value first-point)})))))

(defn benchmark-market-return-rows
  [benchmark-points {:keys [anchor-time-ms end-time-ms]}]
  (if-let [{anchor-row-time-ms :time-ms
            anchor-close :close} (benchmark-anchor benchmark-points anchor-time-ms)]
    (let [end-time-ms* (if (parsing/finite-number? end-time-ms)
                         end-time-ms
                         js/Infinity)]
      (if (> anchor-row-time-ms end-time-ms*)
        []
        (reduce (fn [rows {:keys [time-ms value]}]
                  (if (and (parsing/finite-number? time-ms)
                           (> time-ms anchor-row-time-ms)
                           (<= time-ms end-time-ms*)
                           (parsing/finite-number? value)
                           (pos? anchor-close))
                    (let [cumulative-return (* 100 (- (/ value anchor-close) 1))]
                      (if (parsing/finite-number? cumulative-return)
                        (conj rows {:time-ms time-ms
                                    :value cumulative-return})
                        rows))
                    rows))
                [{:time-ms anchor-row-time-ms
                  :value 0}]
                benchmark-points)))
    []))

(defn- advance-benchmark-candles
  [benchmark-points start-idx latest-close target-time-ms]
  (let [benchmark-count (count benchmark-points)]
    (loop [idx start-idx
           latest latest-close]
      (if (>= idx benchmark-count)
        [idx latest]
        (let [{candle-time-ms :time-ms
               close :value} (nth benchmark-points idx)]
          (if (<= candle-time-ms target-time-ms)
            (recur (inc idx) close)
            [idx latest]))))))

(defn aligned-benchmark-return-rows
  ([benchmark-points strategy-points]
   (aligned-benchmark-return-rows benchmark-points strategy-points nil))
  ([benchmark-points strategy-points anchor-time-ms]
   (let [strategy-time-points (mapv :time-ms strategy-points)
         strategy-count (count strategy-time-points)
         [candle-idx latest-close]
         (if (parsing/finite-number? anchor-time-ms)
           (advance-benchmark-candles benchmark-points 0 nil anchor-time-ms)
           [0 nil])
         anchor-close (when (and (parsing/finite-number? latest-close)
                                 (pos? latest-close))
                        latest-close)]
     (loop [time-idx 0
            candle-idx candle-idx
            latest-close latest-close
            anchor-close anchor-close
            output []]
       (if (>= time-idx strategy-count)
         output
         (let [time-ms (nth strategy-time-points time-idx)
               [candle-idx* latest-close*]
               (advance-benchmark-candles benchmark-points
                                          candle-idx
                                          latest-close
                                          time-ms)
               anchor-close* (or anchor-close latest-close*)
               output* (if (and (parsing/finite-number? latest-close*)
                                (parsing/finite-number? anchor-close*)
                                (pos? anchor-close*))
                         (let [cumulative-return (* 100 (- (/ latest-close* anchor-close*) 1))]
                           (if (parsing/finite-number? cumulative-return)
                             (conj output {:time-ms time-ms
                                           :value cumulative-return})
                             output))
                         output)]
           (recur (inc time-idx)
                  candle-idx*
                  latest-close*
                  anchor-close*
                  output*)))))))

(defn cumulative-return-time-points
  [cumulative-rows]
  (mapv (fn [[time-ms percent]]
          {:time-ms time-ms
           :value percent})
        cumulative-rows))

(defn aligned-summary-return-rows
  [benchmark-rows strategy-points]
  (let [benchmark-rows* (->> (or benchmark-rows [])
                             (keep (fn [row]
                                     (let [time-ms (history-point-time-ms row)
                                           value (history-point-value row)]
                                       (when (and (number? time-ms)
                                                  (parsing/finite-number? value))
                                         [time-ms value]))))
                             (sort-by first)
                             vec)
        benchmark-count (count benchmark-rows*)
        strategy-time-points (mapv :time-ms strategy-points)
        strategy-count (count strategy-time-points)]
    (loop [time-idx 0
           benchmark-idx 0
           latest-value nil
           output []]
      (if (>= time-idx strategy-count)
        output
        (let [time-ms (nth strategy-time-points time-idx)
              [benchmark-idx* latest-value*]
              (loop [idx benchmark-idx
                     latest latest-value]
                (if (>= idx benchmark-count)
                  [idx latest]
                  (let [[benchmark-time-ms benchmark-value] (nth benchmark-rows* idx)]
                    (if (<= benchmark-time-ms time-ms)
                      (recur (inc idx) benchmark-value)
                      [idx latest]))))
              output* (if (parsing/finite-number? latest-value*)
                        (conj output [time-ms latest-value*])
                        output)]
          (recur (inc time-idx)
                 benchmark-idx*
                 latest-value*
                 output*))))))

(defn cumulative-return-row-pairs
  [rows]
  (mapv (fn [{:keys [time-ms value]}]
          [time-ms value])
        rows))
