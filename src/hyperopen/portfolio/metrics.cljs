(ns hyperopen.portfolio.metrics
  (:refer-clojure :exclude [comp])
  (:require [hyperopen.portfolio.metrics.history :as history]
            [hyperopen.portfolio.metrics.returns :as returns]
            [hyperopen.portfolio.metrics.drawdown :as drawdown]
            [hyperopen.portfolio.metrics.distribution :as distribution]
            [hyperopen.portfolio.metrics.math :as math]
            [hyperopen.portfolio.metrics.builder :as builder]))

;; History functions
(def history-point-value history/history-point-value)
(def history-point-time-ms history/history-point-time-ms)
(def returns-history-rows-from-summary history/returns-history-rows-from-summary)
(def returns-history-rows history/returns-history-rows)
(def cumulative-percent-rows->interval-returns history/cumulative-percent-rows->interval-returns)
(def daily-compounded-returns history/daily-compounded-returns)
(def strategy-daily-compounded-returns history/strategy-daily-compounded-returns)
(def normalize-daily-rows history/normalize-daily-rows)
(def align-daily-returns history/align-daily-returns)

;; Returns functions
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

;; Drawdown functions
(def to-drawdown-series drawdown/to-drawdown-series)
(def max-drawdown drawdown/max-drawdown)
(def drawdown-details drawdown/drawdown-details)
(def max-drawdown-stats drawdown/max-drawdown-stats)
(def calmar drawdown/calmar)

;; Distribution functions
(def aggregate-period-returns distribution/aggregate-period-returns)
(def expected-return distribution/expected-return)
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

;; Math functions (exported directly in the original file)
(def skew math/skew)
(def kurtosis math/kurtosis)

;; Builder functions
(def compute-performance-metrics builder/compute-performance-metrics)
(def metric-rows builder/metric-rows)
