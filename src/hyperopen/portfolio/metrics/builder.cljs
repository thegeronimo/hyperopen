(ns hyperopen.portfolio.metrics.builder
  (:require [hyperopen.portfolio.metrics.catalog :as catalog]
            [hyperopen.portfolio.metrics.math :as math]
            [hyperopen.portfolio.metrics.history :as history]
            [hyperopen.portfolio.metrics.returns :as returns]
            [hyperopen.portfolio.metrics.drawdown :as drawdown]
            [hyperopen.portfolio.metrics.distribution :as distribution]
            [hyperopen.portfolio.metrics.quality :as quality]))

(defn- rows-since-ms
  [rows threshold-ms]
  (->> rows
       (filter (fn [{:keys [day time-ms]}]
                 (if-let [anchor-ms (or (history/optional-number time-ms)
                                        (history/parse-day-ms day))]
                   (>= anchor-ms threshold-ms)
                   false)))
       vec))

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

(defn- window-return
  [rows compounded]
  (let [returns* (history/returns-values rows)]
    (when (seq returns*)
      (if compounded
        (returns/comp returns*)
        (reduce + 0 returns*)))))

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

(defn- resolve-cumulative-rows
  [strategy-cumulative-rows strategy-daily-rows]
  (let [direct (history/normalize-cumulative-percent-rows strategy-cumulative-rows)]
    (if (seq direct)
      direct
      (history/normalize-cumulative-percent-rows
       (history/daily-rows->cumulative-percent-rows strategy-daily-rows)))))

(defn- cumulative-rows->pairs
  [resolved-cumulative-rows]
  (mapv (fn [{:keys [time-ms percent]}]
          [time-ms percent])
        resolved-cumulative-rows))

(defn- resolve-strategy-rows
  [strategy-daily-rows cumulative-rows*]
  (let [daily* (history/normalize-daily-rows strategy-daily-rows)]
    (if (seq daily*)
      daily*
      (history/daily-compounded-returns cumulative-rows*))))

(defn- annualized-expected-return
  [cagr* periods]
  (when (number? cagr*)
    (- (js/Math.pow (+ 1 cagr*) (/ 1 periods))
       1)))

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

(defn- build-window-context
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

(defn- build-quality-context
  [intervals strategy-rows mar gates]
  (let [diagnostics (quality/cadence-diagnostics intervals strategy-rows mar)
        gates* (quality/compute-quality-gates diagnostics gates)
        core-enabled? (and (:core-min? gates*)
                           (not (:structural-gap? gates*)))
        core-low-confidence? (and core-enabled?
                                  (not (:core-high-confidence? gates*)))
        daily-enabled? (and (:daily-min? gates*)
                            (not (:structural-gap? gates*)))]
    {:diagnostics diagnostics
     :gates* gates*
     :core-enabled? core-enabled?
     :core-low-confidence? core-low-confidence?
     :daily-enabled? daily-enabled?
     :psr-enabled? (and daily-enabled? (:psr-min? gates*))
     :drawdown-reliable? (and core-enabled? (:drawdown-reliable? gates*))}))

(defn- build-benchmark-context
  [strategy-rows benchmark-daily-rows gates]
  (let [aligned-benchmark (history/align-daily-returns strategy-rows benchmark-daily-rows)]
    {:aligned-benchmark aligned-benchmark
     :strategy-aligned (mapv :strategy-return aligned-benchmark)
     :benchmark-aligned (mapv :benchmark-return aligned-benchmark)
     :benchmark-min? (>= (count aligned-benchmark)
                         (:benchmark-min-points gates))}))

(defn- build-core-metric-context
  [intervals strategy-rows strategy-returns rf mar periods-per-year]
  (let [cagr* (returns/interval-cagr intervals)
        sortino-result (returns/sortino-irregular intervals mar)]
    {:drawdown-stats (drawdown/max-drawdown-stats strategy-rows)
     :cagr* cagr*
     :volatility-ann* (returns/volatility-ann-irregular intervals)
     :sharpe* (returns/sharpe-irregular intervals rf)
     :sortino* (:value sortino-result)
     :sortino-downside-count (:downside-count sortino-result)
     :expected-daily* (annualized-expected-return cagr* 365.2425)
     :expected-monthly* (annualized-expected-return cagr* 12)
     :expected-yearly* cagr*
     :smart-sharpe* (returns/smart-sharpe strategy-returns {:rf rf
                                                    :periods-per-year periods-per-year})
     :smart-sortino* (returns/smart-sortino strategy-returns {:rf rf
                                                      :periods-per-year periods-per-year})}))

(defn- build-enable-context
  [quality-context gates benchmark-min? sortino-downside-count]
  (let [{:keys [core-enabled? daily-enabled?]} quality-context
        gates* (:gates* quality-context)]
    {:sortino-enabled? (and core-enabled?
                            (:sortino-min? gates*)
                            (>= sortino-downside-count
                                (:sortino-min-downside gates)))
     :benchmark-enabled? (and daily-enabled?
                             benchmark-min?)}))

(defn- assoc-metric-result
  [acc key value enabled status reason]
  (let [status* (cond
                  (not enabled) :suppressed
                  (nil? value) :suppressed
                  :else status)
        value* (when (not= status* :suppressed) value)
        reason* (when (not= status* :ok) reason)]
    (-> acc
        (assoc key value*)
        (assoc-in [:metric-status key] status*)
        (cond-> reason*
          (assoc-in [:metric-reason key] reason*)))))

(defn- assoc-estimated-metric-result
  [acc key value high-confidence? reason]
  (assoc-metric-result acc
                       key
                       value
                       true
                       (if high-confidence?
                         :ok
                         :low-confidence)
                       reason))

(defn- core-status-token
  [core-low-confidence?]
  (if core-low-confidence?
    :low-confidence
    :ok))

(defn- add-overview-metrics
  [acc {:keys [intervals
               resolved-cumulative-rows
               all-time-cumulative-return
               cagr*
               volatility-ann*
               sharpe*
               sortino*
               expected-daily*
               expected-monthly*
               expected-yearly*
               core-enabled?
               core-low-confidence?
               sortino-enabled?]}]
  (let [core-status (core-status-token core-low-confidence?)]
    (-> acc
        (assoc-metric-result :time-in-market
                             (returns/interval-weighted-exposure intervals)
                             (pos? (count intervals))
                             :ok
                             :insufficient-intervals)
        (assoc-metric-result :cumulative-return
                             all-time-cumulative-return
                             (>= (count resolved-cumulative-rows) 2)
                             :ok
                             :insufficient-rows)
        (assoc-metric-result :cagr cagr* core-enabled? core-status :core-gate-failed)
        (assoc-metric-result :volatility-ann volatility-ann* core-enabled? core-status :core-gate-failed)
        (assoc-metric-result :sharpe sharpe* core-enabled? core-status :core-gate-failed)
        (assoc-metric-result :sortino sortino* sortino-enabled? core-status :sortino-gate-failed)
        (assoc-metric-result :sortino-sqrt2
                             (some-> sortino* (/ (js/Math.sqrt 2)))
                             sortino-enabled?
                             core-status
                             :sortino-gate-failed)
        (assoc-metric-result :expected-daily expected-daily* core-enabled? core-status :core-gate-failed)
        (assoc-metric-result :expected-monthly expected-monthly* core-enabled? core-status :core-gate-failed)
        (assoc-metric-result :expected-yearly expected-yearly* core-enabled? core-status :core-gate-failed)
        (assoc-metric-result :all-time-ann cagr* core-enabled? core-status :core-gate-failed))))

(defn- add-drawdown-and-window-metrics
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
      (assoc-metric-result :max-drawdown (:max-drawdown drawdown-stats)
                           (boolean drawdown-stats)
                           (if drawdown-reliable? :ok :low-confidence)
                           :drawdown-unavailable)
      (assoc-metric-result :max-dd-date (:max-dd-date drawdown-stats)
                           (boolean drawdown-stats)
                           (if drawdown-reliable? :ok :low-confidence)
                           :drawdown-unavailable)
      (assoc-metric-result :max-dd-period-start (:max-dd-period-start drawdown-stats)
                           (boolean drawdown-stats)
                           (if drawdown-reliable? :ok :low-confidence)
                           :drawdown-unavailable)
      (assoc-metric-result :max-dd-period-end (:max-dd-period-end drawdown-stats)
                           (boolean drawdown-stats)
                           (if drawdown-reliable? :ok :low-confidence)
                           :drawdown-unavailable)
      (assoc-metric-result :longest-dd-days (:longest-dd-days drawdown-stats)
                           (boolean drawdown-stats)
                           (if drawdown-reliable? :ok :low-confidence)
                           :drawdown-unavailable)
      (assoc-metric-result :calmar
                           (drawdown/calmar strategy-returns {:periods-per-year periods-per-year})
                           drawdown-reliable?
                           (if drawdown-reliable? :ok :low-confidence)
                           :drawdown-reliability-gate-failed)
      (assoc-metric-result :mtd (window-return-from-cumulative mtd-cumulative)
                           (>= (count mtd-cumulative) 2)
                           :ok
                           :window-unavailable)
      (assoc-metric-result :m3 (window-return-from-cumulative m3-cumulative)
                           (>= (count m3-cumulative) 2)
                           :ok
                           :window-unavailable)
      (assoc-metric-result :m6 (window-return-from-cumulative m6-cumulative)
                           (>= (count m6-cumulative) 2)
                           :ok
                           :window-unavailable)
      (assoc-metric-result :ytd (window-return-from-cumulative ytd-cumulative)
                           (>= (count ytd-cumulative) 2)
                           :ok
                           :window-unavailable)
      (assoc-metric-result :y1 (window-return-from-cumulative y1-cumulative)
                           (>= (count y1-cumulative) 2)
                           :ok
                           :window-unavailable)
      (assoc-metric-result :y3-ann (window-cagr-from-cumulative y3-cumulative)
                           rolling-y3-ok?
                           (core-status-token core-low-confidence?)
                           :rolling-window-span-insufficient)
      (assoc-metric-result :y5-ann (window-cagr-from-cumulative y5-cumulative)
                           rolling-y5-ok?
                           (core-status-token core-low-confidence?)
                           :rolling-window-span-insufficient)
      (assoc-metric-result :y10-ann (window-cagr-from-cumulative y10-cumulative)
                           rolling-y10-ok?
                           (core-status-token core-low-confidence?)
                           :rolling-window-span-insufficient)))

(defn- add-daily-risk-adjusted-metrics
  [acc {:keys [strategy-returns
               strategy-rows
               rf
               periods-per-year
               smart-sharpe*
               smart-sortino*
               daily-enabled?
               psr-enabled?]}]
  (let [psr-high-confidence? (and daily-enabled? psr-enabled?)
        psr-reason (if daily-enabled?
                     :psr-gate-failed
                     :daily-coverage-gate-failed)]
    (-> acc
        (assoc-estimated-metric-result :omega
                                       (returns/omega strategy-returns {:rf rf
                                                                        :required-return 0
                                                                        :periods-per-year periods-per-year})
                                       daily-enabled?
                                       :daily-coverage-gate-failed)
        (assoc-estimated-metric-result :smart-sharpe smart-sharpe* daily-enabled? :daily-coverage-gate-failed)
        (assoc-estimated-metric-result :smart-sortino smart-sortino* daily-enabled? :daily-coverage-gate-failed)
        (assoc-estimated-metric-result :smart-sortino-sqrt2
                                       (some-> smart-sortino* (/ (js/Math.sqrt 2)))
                                       daily-enabled?
                                       :daily-coverage-gate-failed)
        (assoc-estimated-metric-result :prob-sharpe-ratio
                                       (returns/probabilistic-sharpe-ratio strategy-returns {:rf rf
                                                                                              :periods-per-year periods-per-year})
                                       psr-high-confidence?
                                       psr-reason)
        (assoc-estimated-metric-result :gain-pain-ratio
                                       (distribution/gain-to-pain-ratio strategy-rows :day)
                                       daily-enabled?
                                       :daily-coverage-gate-failed)
        (assoc-estimated-metric-result :gain-pain-1m
                                       (distribution/gain-to-pain-ratio strategy-rows :month)
                                       daily-enabled?
                                       :daily-coverage-gate-failed))))

(defn- add-daily-distribution-metrics
  [acc {:keys [strategy-returns daily-enabled?]}]
  (-> acc
      (assoc-estimated-metric-result :payoff-ratio (distribution/payoff-ratio strategy-returns) daily-enabled? :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :profit-factor (distribution/profit-factor strategy-returns) daily-enabled? :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :common-sense-ratio (distribution/common-sense-ratio strategy-returns) daily-enabled? :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :cpc-index (distribution/cpc-index strategy-returns) daily-enabled? :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :tail-ratio (distribution/tail-ratio strategy-returns) daily-enabled? :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :outlier-win-ratio (distribution/outlier-win-ratio strategy-returns) daily-enabled? :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :outlier-loss-ratio (distribution/outlier-loss-ratio strategy-returns) daily-enabled? :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :skew (math/skew strategy-returns) daily-enabled? :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :kurtosis (math/kurtosis strategy-returns) daily-enabled? :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :kelly-criterion (distribution/kelly-criterion strategy-returns) daily-enabled? :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :risk-of-ruin (distribution/risk-of-ruin strategy-returns) daily-enabled? :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :daily-var
                                     (some-> (distribution/value-at-risk strategy-returns)
                                             js/Math.abs
                                             (-))
                                     daily-enabled?
                                     :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :expected-shortfall
                                     (some-> (distribution/expected-shortfall strategy-returns)
                                             js/Math.abs
                                             (-))
                                     daily-enabled?
                                     :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :max-consecutive-wins (distribution/consecutive-wins strategy-returns) daily-enabled? :daily-coverage-gate-failed)
      (assoc-estimated-metric-result :max-consecutive-losses (distribution/consecutive-losses strategy-returns) daily-enabled? :daily-coverage-gate-failed)))

(defn- add-benchmark-relative-metrics
  [acc {:keys [aligned-benchmark
               strategy-aligned
               benchmark-aligned
               benchmark-enabled?]}]
  (-> acc
      (assoc-metric-result :r2
                           (when (seq aligned-benchmark)
                             (distribution/r-squared strategy-aligned benchmark-aligned))
                           benchmark-enabled?
                           :ok
                           :benchmark-coverage-gate-failed)
      (assoc-metric-result :information-ratio
                           (when (seq aligned-benchmark)
                             (distribution/information-ratio strategy-aligned benchmark-aligned))
                           benchmark-enabled?
                           :ok
                           :benchmark-coverage-gate-failed)))

(defn compute-performance-metrics
  [{:keys [strategy-cumulative-rows
           strategy-daily-rows
           benchmark-daily-rows
           rf
           mar
           periods-per-year
           quality-gates]
    :or {rf 0
         mar 0
         periods-per-year returns/default-periods-per-year}}]
  (let [gates (merge quality/default-quality-gates quality-gates)
        resolved-cumulative-rows (resolve-cumulative-rows strategy-cumulative-rows strategy-daily-rows)
        cumulative-rows* (cumulative-rows->pairs resolved-cumulative-rows)
        strategy-rows (resolve-strategy-rows strategy-daily-rows cumulative-rows*)
        strategy-returns (history/returns-values strategy-rows)
        intervals (history/cumulative-rows->irregular-intervals cumulative-rows*)
        quality-context (build-quality-context intervals strategy-rows mar gates)
        benchmark-context (build-benchmark-context strategy-rows benchmark-daily-rows gates)
        core-context (build-core-metric-context intervals strategy-rows strategy-returns rf mar periods-per-year)
        enable-context (build-enable-context quality-context
                                            gates
                                            (:benchmark-min? benchmark-context)
                                            (:sortino-downside-count core-context))
        window-context (build-window-context resolved-cumulative-rows gates)
        context (merge quality-context
                       benchmark-context
                       core-context
                       enable-context
                       window-context
                       {:rf rf
                        :periods-per-year periods-per-year
                        :intervals intervals
                        :resolved-cumulative-rows resolved-cumulative-rows
                        :strategy-rows strategy-rows
                        :strategy-returns strategy-returns
                        :all-time-cumulative-return (:all-time-cumulative-return window-context)})]
    (-> {:quality (merge (:diagnostics quality-context)
                         (:gates* quality-context))
         :metric-status {}
         :metric-reason {}}
        (add-overview-metrics context)
        (add-drawdown-and-window-metrics context)
        (add-daily-risk-adjusted-metrics context)
        (add-daily-distribution-metrics context)
        (add-benchmark-relative-metrics context))))

(defn metric-rows
  [metric-values]
  (catalog/metric-rows metric-values))
