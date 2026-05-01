(ns hyperopen.portfolio.metrics.builder.core
  (:require [hyperopen.portfolio.metrics.distribution :as distribution]
            [hyperopen.portfolio.metrics.drawdown :as drawdown]
            [hyperopen.portfolio.metrics.history :as history]
            [hyperopen.portfolio.metrics.math :as math]
            [hyperopen.portfolio.metrics.quality :as quality]
            [hyperopen.portfolio.metrics.returns :as returns]))

(defn resolve-cumulative-rows
  [strategy-cumulative-rows strategy-daily-rows]
  (let [direct (history/normalize-cumulative-percent-rows strategy-cumulative-rows)]
    (if (seq direct)
      direct
      (history/normalize-cumulative-percent-rows
       (history/daily-rows->cumulative-percent-rows strategy-daily-rows)))))

(defn cumulative-rows->pairs
  [resolved-cumulative-rows]
  (mapv (fn [{:keys [time-ms percent]}]
          [time-ms percent])
        resolved-cumulative-rows))

(defn resolve-strategy-rows
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

(defn build-quality-context
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

(defn build-core-metric-context
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

(defn build-enable-context
  [quality-context gates benchmark-min? sortino-downside-count]
  (let [{:keys [core-enabled? daily-enabled?]} quality-context
        gates* (:gates* quality-context)]
    {:sortino-enabled? (and core-enabled?
                            (:sortino-min? gates*)
                            (>= sortino-downside-count
                                (:sortino-min-downside gates)))
     :benchmark-enabled? (and daily-enabled?
                             benchmark-min?)}))

(defn assoc-metric-result
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

(defn assoc-estimated-metric-result
  [acc key value high-confidence? reason]
  (assoc-metric-result acc
                       key
                       value
                       true
                       (if high-confidence?
                         :ok
                         :low-confidence)
                       reason))

(defn core-status-token
  [core-low-confidence?]
  (if core-low-confidence?
    :low-confidence
    :ok))

(defn calmar
  [strategy-returns periods-per-year]
  (drawdown/calmar strategy-returns {:periods-per-year periods-per-year}))

(defn add-overview-metrics
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

(defn add-daily-risk-adjusted-metrics
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

(defn add-daily-distribution-metrics
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
