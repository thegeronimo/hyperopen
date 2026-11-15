(ns hyperopen.portfolio.optimizer.application.setup-readiness-freshness-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]))

(def ^:private day-ms (* 24 60 60 1000))

(defn- daily-candles
  [start-day count* base]
  (mapv (fn [idx]
          {:time (* (+ start-day idx) day-ms)
           :close (str (+ base idx))})
        (range count*)))

(defn- freshness-state
  [{:keys [as-of-days stale-after-ms]}]
  {:portfolio
   {:optimizer
    {:draft
     {:universe [{:instrument-id "perp:BTC" :market-type :perp :coin "BTC"}
                 {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"}]
      :objective {:kind :minimum-variance}
      :return-model {:kind :historical-mean}
      :risk-model {:kind :diagonal-shrink}
      :constraints {:long-only? true}}
     :runtime {:as-of-ms (* as-of-days day-ms)
               :stale-after-ms stale-after-ms
               :funding-periods-per-year 1095}
     :history-data
     {:candle-history-by-coin
      {"BTC" (daily-candles 0 60 100)
       "ETH" (daily-candles 0 60 2000)}
      :funding-history-by-coin {}}}}})

(defn- request-freshness
  [opts]
  (get-in (setup-readiness/build-readiness (freshness-state opts))
          [:request :history :freshness]))

(deftest build-readiness-defaults-history-staleness-threshold-test
  (let [stale (request-freshness {:as-of-days 81 :stale-after-ms nil})
        fresh (request-freshness {:as-of-days 61 :stale-after-ms nil})
        overridden (request-freshness {:as-of-days 81
                                       :stale-after-ms (* 40 day-ms)})]
    (is (number? (:latest-common-ms stale)) "an aligned shared calendar exists")
    (is (= true (:stale? stale))
        "a 22-day-old shared window is stale under the default week threshold")
    (is (= false (:stale? fresh))
        "a window trailing the run date by ~2 days stays fresh")
    (is (= false (:stale? overridden))
        "an explicit runtime threshold still wins over the default")))
