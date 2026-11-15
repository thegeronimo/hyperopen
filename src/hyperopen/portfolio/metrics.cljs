(ns hyperopen.portfolio.metrics
  (:refer-clojure :exclude [comp])
  (:require [hyperopen.portfolio.metrics.history :as history]
            [hyperopen.portfolio.metrics.returns :as returns]
            [hyperopen.portfolio.metrics.drawdown :as drawdown]
            [hyperopen.portfolio.metrics.distribution :as distribution]
            [hyperopen.portfolio.metrics.math :as math]
            [hyperopen.portfolio.metrics.builder :as builder]))

(def history-point-value history/history-point-value)
(def history-point-time-ms history/history-point-time-ms)
(def returns-history-rows-from-summary history/returns-history-rows-from-summary)
(def returns-history-rows history/returns-history-rows)
(def cumulative-percent-rows->interval-returns history/cumulative-percent-rows->interval-returns)
(def daily-compounded-returns history/daily-compounded-returns)

(def comp returns/comp)
(def time-in-market returns/time-in-market)
(def cagr returns/cagr)
(def volatility returns/volatility)
(def sharpe returns/sharpe)
(def smart-sharpe returns/smart-sharpe)
(def sortino returns/sortino)
(def smart-sortino returns/smart-sortino)
(def probabilistic-sharpe-ratio returns/probabilistic-sharpe-ratio)
(def omega returns/omega)

(def to-drawdown-series drawdown/to-drawdown-series)
(def max-drawdown drawdown/max-drawdown)
(def drawdown-details drawdown/drawdown-details)
(def max-drawdown-stats drawdown/max-drawdown-stats)
(def avg-drawdown drawdown/avg-drawdown)
(def avg-drawdown-days drawdown/avg-drawdown-days)
(def recovery-factor drawdown/recovery-factor)
(def ulcer-index drawdown/ulcer-index)
(def serenity-index drawdown/serenity-index)
(def calmar drawdown/calmar)

(def expected-return distribution/expected-return)
(def best-period-return distribution/best-period-return)
(def worst-period-return distribution/worst-period-return)
(def avg-win distribution/avg-win)
(def avg-loss distribution/avg-loss)
(def win-rate distribution/win-rate)
(def payoff-ratio distribution/payoff-ratio)
(def kelly-criterion distribution/kelly-criterion)
(def risk-of-ruin distribution/risk-of-ruin)
(def value-at-risk distribution/value-at-risk)
(def expected-shortfall distribution/expected-shortfall)
(def consecutive-wins distribution/consecutive-wins)
(def consecutive-losses distribution/consecutive-losses)
(def gain-to-pain-ratio distribution/gain-to-pain-ratio)
(def profit-factor distribution/profit-factor)
(def tail-ratio distribution/tail-ratio)
(def common-sense-ratio distribution/common-sense-ratio)
(def cpc-index distribution/cpc-index)
(def outlier-win-ratio distribution/outlier-win-ratio)
(def outlier-loss-ratio distribution/outlier-loss-ratio)
(def r-squared distribution/r-squared)
(def information-ratio distribution/information-ratio)
(def beta distribution/beta)
(def alpha distribution/alpha)
(def correlation distribution/correlation)
(def treynor-ratio distribution/treynor-ratio)

(def benchmark-column-relative-metric-keys
  [:r2 :information-ratio :beta :alpha :correlation :treynor-ratio])

(defn- assoc-relative-metric-result
  [acc relative-values metric-key]
  (let [status (get-in relative-values [:metric-status metric-key])
        reason (get-in relative-values [:metric-reason metric-key])]
    (-> acc
        (assoc metric-key (get relative-values metric-key))
        (update :metric-status
                (fn [statuses]
                  (let [statuses* (or statuses {})]
                    (if status
                      (assoc statuses* metric-key status)
                      (dissoc statuses* metric-key)))))
        (update :metric-reason
                (fn [reasons]
                  (let [reasons* (or reasons {})]
                    (if reason
                      (assoc reasons* metric-key reason)
                      (dissoc reasons* metric-key))))))))

(defn merge-benchmark-column-relative-metrics
  [benchmark-values relative-values]
  (reduce (fn [acc metric-key]
            (if (or (contains? relative-values metric-key)
                    (contains? (:metric-status relative-values) metric-key)
                    (contains? (:metric-reason relative-values) metric-key))
              (assoc-relative-metric-result acc relative-values metric-key)
              acc))
          (or benchmark-values {})
          benchmark-column-relative-metric-keys))

(def skew math/skew)
(def kurtosis math/kurtosis)

(def compute-performance-metrics builder/compute-performance-metrics)
(def metric-rows builder/metric-rows)
