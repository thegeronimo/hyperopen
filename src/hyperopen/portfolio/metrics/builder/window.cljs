(ns hyperopen.portfolio.metrics.builder.window
  (:require [hyperopen.portfolio.metrics.builder.core :as core]
            [hyperopen.portfolio.metrics.history :as history]
            [hyperopen.portfolio.metrics.returns :as returns]))

(defn- cumulative-rows-since-ms
  [rows threshold-ms]
  (let [sorted-rows (->> rows
                         (filter (fn [{:keys [time-ms]}]
                                   (number? time-ms)))
                         (sort-by :time-ms)
                         vec)
        anchor-row (last (filter (fn [{:keys [time-ms]}]
                                   (< time-ms threshold-ms))
                                 sorted-rows))
        window-rows (filter (fn [{:keys [time-ms]}]
                              (>= time-ms threshold-ms))
                            sorted-rows)]
    (cond
      (and anchor-row (seq window-rows))
      (vec (cons anchor-row window-rows))

      (seq window-rows)
      (vec window-rows)

      :else
      [])))

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

(defn- window-return-from-cumulative
  [rows]
  (when (>= (count rows) 2)
    (let [start-factor (:factor (first rows))
          end-factor (:factor (last rows))]
      (when (and (history/finite-number? start-factor)
                 (history/finite-number? end-factor)
                 (pos? start-factor))
        (- (/ end-factor start-factor) 1)))))

(defn- window-cagr-from-cumulative
  [rows]
  (returns/interval-cagr (history/cumulative-rows->irregular-intervals rows)))

(defn- window-span-days
  [rows]
  (if (>= (count rows) 2)
    (/ (- (:time-ms (last rows))
          (:time-ms (first rows)))
       history/day-ms)
    0))

(defn- window-anchor-ms
  [last-ms]
  (let [last-date (when (number? last-ms) (js/Date. last-ms))]
    {:month-start-ms (when last-date
                       (.getTime (js/Date. (.UTC js/Date
                                                (.getUTCFullYear last-date)
                                                (.getUTCMonth last-date)
                                                1))))
     :year-start-ms (when last-date
                      (.getTime (js/Date. (.UTC js/Date
                                               (.getUTCFullYear last-date)
                                               0
                                               1))))
     :m3-ms (when (number? last-ms) (with-utc-months-offset last-ms -3))
     :m6-ms (when (number? last-ms) (with-utc-months-offset last-ms -6))
     :y1-ms (when (number? last-ms) (with-utc-years-offset last-ms -1))
     :y3-ms (when (number? last-ms) (with-utc-months-offset last-ms -35))
     :y5-ms (when (number? last-ms) (with-utc-months-offset last-ms -59))
     :y10-ms (when (number? last-ms) (with-utc-years-offset last-ms -10))}))

(defn- cumulative-window
  [rows threshold-ms]
  (if (number? threshold-ms)
    (cumulative-rows-since-ms rows threshold-ms)
    []))

(defn build-window-context
  [resolved-cumulative-rows gates]
  (let [anchors (window-anchor-ms (some-> resolved-cumulative-rows last :time-ms))
        mtd-cumulative (cumulative-window resolved-cumulative-rows (:month-start-ms anchors))
        m3-cumulative (cumulative-window resolved-cumulative-rows (:m3-ms anchors))
        m6-cumulative (cumulative-window resolved-cumulative-rows (:m6-ms anchors))
        ytd-cumulative (cumulative-window resolved-cumulative-rows (:year-start-ms anchors))
        y1-cumulative (cumulative-window resolved-cumulative-rows (:y1-ms anchors))
        y3-cumulative (cumulative-window resolved-cumulative-rows (:y3-ms anchors))
        y5-cumulative (cumulative-window resolved-cumulative-rows (:y5-ms anchors))
        y10-cumulative (cumulative-window resolved-cumulative-rows (:y10-ms anchors))
        rolling-min-fraction (:rolling-min-fraction gates)]
    {:mtd-cumulative mtd-cumulative
     :m3-cumulative m3-cumulative
     :m6-cumulative m6-cumulative
     :ytd-cumulative ytd-cumulative
     :y1-cumulative y1-cumulative
     :y3-cumulative y3-cumulative
     :y5-cumulative y5-cumulative
     :y10-cumulative y10-cumulative
     :rolling-y3-ok? (>= (window-span-days y3-cumulative)
                         (* 3 365.2425 rolling-min-fraction))
     :rolling-y5-ok? (>= (window-span-days y5-cumulative)
                         (* 5 365.2425 rolling-min-fraction))
     :rolling-y10-ok? (>= (window-span-days y10-cumulative)
                          (* 10 365.2425 rolling-min-fraction))
     :all-time-cumulative-return (window-return-from-cumulative resolved-cumulative-rows)}))

(defn add-drawdown-and-window-metrics
  [acc {:keys [drawdown-stats
               drawdown-reliable?
               strategy-returns
               periods-per-year
               mtd-cumulative
               m3-cumulative
               m6-cumulative
               ytd-cumulative
               y1-cumulative
               y3-cumulative
               y5-cumulative
               y10-cumulative
               rolling-y3-ok?
               rolling-y5-ok?
               rolling-y10-ok?
               core-low-confidence?]}]
  (-> acc
      (core/assoc-metric-result :max-drawdown (:max-drawdown drawdown-stats)
                                (boolean drawdown-stats)
                                (if drawdown-reliable? :ok :low-confidence)
                                :drawdown-unavailable)
      (core/assoc-metric-result :max-dd-date (:max-dd-date drawdown-stats)
                                (boolean drawdown-stats)
                                (if drawdown-reliable? :ok :low-confidence)
                                :drawdown-unavailable)
      (core/assoc-metric-result :max-dd-period-start (:max-dd-period-start drawdown-stats)
                                (boolean drawdown-stats)
                                (if drawdown-reliable? :ok :low-confidence)
                                :drawdown-unavailable)
      (core/assoc-metric-result :max-dd-period-end (:max-dd-period-end drawdown-stats)
                                (boolean drawdown-stats)
                                (if drawdown-reliable? :ok :low-confidence)
                                :drawdown-unavailable)
      (core/assoc-metric-result :longest-dd-days (:longest-dd-days drawdown-stats)
                                (boolean drawdown-stats)
                                (if drawdown-reliable? :ok :low-confidence)
                                :drawdown-unavailable)
      (core/assoc-metric-result :calmar
                                (core/calmar strategy-returns periods-per-year)
                                drawdown-reliable?
                                (if drawdown-reliable? :ok :low-confidence)
                                :drawdown-reliability-gate-failed)
      (core/assoc-metric-result :mtd (window-return-from-cumulative mtd-cumulative)
                                (>= (count mtd-cumulative) 2)
                                :ok
                                :window-unavailable)
      (core/assoc-metric-result :m3 (window-return-from-cumulative m3-cumulative)
                                (>= (count m3-cumulative) 2)
                                :ok
                                :window-unavailable)
      (core/assoc-metric-result :m6 (window-return-from-cumulative m6-cumulative)
                                (>= (count m6-cumulative) 2)
                                :ok
                                :window-unavailable)
      (core/assoc-metric-result :ytd (window-return-from-cumulative ytd-cumulative)
                                (>= (count ytd-cumulative) 2)
                                :ok
                                :window-unavailable)
      (core/assoc-metric-result :y1 (window-return-from-cumulative y1-cumulative)
                                (>= (count y1-cumulative) 2)
                                :ok
                                :window-unavailable)
      (core/assoc-metric-result :y3-ann (window-cagr-from-cumulative y3-cumulative)
                                rolling-y3-ok?
                                (core/core-status-token core-low-confidence?)
                                :rolling-window-span-insufficient)
      (core/assoc-metric-result :y5-ann (window-cagr-from-cumulative y5-cumulative)
                                rolling-y5-ok?
                                (core/core-status-token core-low-confidence?)
                                :rolling-window-span-insufficient)
      (core/assoc-metric-result :y10-ann (window-cagr-from-cumulative y10-cumulative)
                                rolling-y10-ok?
                                (core/core-status-token core-low-confidence?)
                                :rolling-window-span-insufficient)))
