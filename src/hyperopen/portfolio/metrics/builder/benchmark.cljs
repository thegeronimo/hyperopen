(ns hyperopen.portfolio.metrics.builder.benchmark
  (:require [hyperopen.portfolio.metrics.builder.core :as core]
            [hyperopen.portfolio.metrics.distribution :as distribution]
            [hyperopen.portfolio.metrics.history :as history]))

(defn build-benchmark-context
  [strategy-rows benchmark-daily-rows gates]
  (let [aligned-benchmark (history/align-daily-returns strategy-rows benchmark-daily-rows)]
    {:aligned-benchmark aligned-benchmark
     :strategy-aligned (mapv :strategy-return aligned-benchmark)
     :benchmark-aligned (mapv :benchmark-return aligned-benchmark)
     :benchmark-min? (>= (count aligned-benchmark)
                         (:benchmark-min-points gates))}))

(defn add-benchmark-relative-metrics
  [acc {:keys [aligned-benchmark
               strategy-aligned
               benchmark-aligned
               benchmark-enabled?]}]
  (-> acc
      (core/assoc-metric-result :r2
                                (when (seq aligned-benchmark)
                                  (distribution/r-squared strategy-aligned benchmark-aligned))
                                benchmark-enabled?
                                :ok
                                :benchmark-coverage-gate-failed)
      (core/assoc-metric-result :information-ratio
                                (when (seq aligned-benchmark)
                                  (distribution/information-ratio strategy-aligned benchmark-aligned))
                                benchmark-enabled?
                                :ok
                                :benchmark-coverage-gate-failed)))
