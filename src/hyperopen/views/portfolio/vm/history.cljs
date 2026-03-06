(ns hyperopen.views.portfolio.vm.history
  (:require [hyperopen.portfolio.metrics.parsing :as parsing]
            [hyperopen.views.portfolio.vm.constants :as constants]))

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

(defn benchmark-candle-points
  [rows]
  (->> rows
       (keep (fn [row]
               (let [time-ms (history-point-time-ms row)
                     close (candle-point-close row)]
                 (when (and (parsing/finite-number? time-ms)
                            (parsing/finite-number? close)
                            (pos? close))
                   {:time-ms time-ms
                    :value close}))))
       (sort-by :time-ms)
       vec))

(defn aligned-benchmark-return-rows
  [benchmark-points strategy-points]
  (let [benchmark-count (count benchmark-points)
        strategy-time-points (mapv :time-ms strategy-points)
        strategy-count (count strategy-time-points)]
    (loop [time-idx 0
           candle-idx 0
           latest-close nil
           anchor-close nil
           output []]
      (if (>= time-idx strategy-count)
        output
        (let [time-ms (nth strategy-time-points time-idx)
              [candle-idx* latest-close*]
              (loop [idx candle-idx
                     latest latest-close]
                (if (>= idx benchmark-count)
                  [idx latest]
                  (let [{candle-time-ms :time-ms
                         close :value} (nth benchmark-points idx)]
                    (if (<= candle-time-ms time-ms)
                      (recur (inc idx) close)
                      [idx latest]))))
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
                 output*))))))

(defn cumulative-return-time-points
  [cumulative-rows]
  (mapv (fn [[time-ms percent]]
          {:time-ms time-ms
           :value percent})
        cumulative-rows))
