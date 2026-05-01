(ns hyperopen.portfolio.metrics.builder
  (:require [hyperopen.portfolio.metrics.builder.benchmark :as benchmark]
            [hyperopen.portfolio.metrics.builder.core :as core]
            [hyperopen.portfolio.metrics.builder.window :as window]
            [hyperopen.portfolio.metrics.catalog :as catalog]
            [hyperopen.portfolio.metrics.history :as history]
            [hyperopen.portfolio.metrics.quality :as quality]
            [hyperopen.portfolio.metrics.returns :as returns]))

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
        resolved-cumulative-rows (core/resolve-cumulative-rows strategy-cumulative-rows
                                                               strategy-daily-rows)
        cumulative-rows* (core/cumulative-rows->pairs resolved-cumulative-rows)
        strategy-rows (core/resolve-strategy-rows strategy-daily-rows cumulative-rows*)
        strategy-returns (history/returns-values strategy-rows)
        intervals (history/cumulative-rows->irregular-intervals cumulative-rows*)
        quality-context (core/build-quality-context intervals strategy-rows mar gates)
        benchmark-context (benchmark/build-benchmark-context strategy-rows
                                                             benchmark-daily-rows
                                                             gates)
        core-context (core/build-core-metric-context intervals
                                                     strategy-rows
                                                     strategy-returns
                                                     rf
                                                     mar
                                                     periods-per-year)
        enable-context (core/build-enable-context quality-context
                                                  gates
                                                  (:benchmark-min? benchmark-context)
                                                  (:sortino-downside-count core-context))
        window-context (window/build-window-context resolved-cumulative-rows gates)
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
        (core/add-overview-metrics context)
        (window/add-drawdown-and-window-metrics context)
        (core/add-daily-risk-adjusted-metrics context)
        (core/add-daily-distribution-metrics context)
        (benchmark/add-benchmark-relative-metrics context))))

(defn metric-rows
  [metric-values]
  (catalog/metric-rows metric-values))
