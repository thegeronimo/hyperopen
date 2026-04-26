(ns hyperopen.portfolio.optimizer.application.setup-readiness-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]))

(defn- deep-merge
  [& maps]
  (apply merge-with
         (fn [left right]
           (if (and (map? left)
                    (map? right))
             (deep-merge left right)
             right))
         maps))

(defn- optimizer-state
  [overrides]
  (deep-merge
   {:portfolio
    {:optimizer
     {:draft
      {:universe [{:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"}
                  {:instrument-id "perp:ETH"
                   :market-type :perp
                   :coin "ETH"}]
       :objective {:kind :minimum-variance}
       :return-model {:kind :historical-mean}
       :risk-model {:kind :diagonal-shrink}
       :constraints {:long-only? true}}
      :runtime {:as-of-ms 2500
                :stale-after-ms 5000
                :funding-periods-per-year 1095}}}}
   overrides))

(deftest build-readiness-blocks-when-retained-history-misses-new-universe-assets-test
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:history-data
                       {:candle-history-by-coin
                        {"BTC" [{:time 1000 :close "100"}
                                {:time 2000 :close "110"}]}
                        :funding-history-by-coin {}}}}}))]
    (is (= :blocked (:status readiness)))
    (is (= :incomplete-history (:reason readiness)))
    (is (= false (:runnable? readiness)))
    (is (= ["perp:BTC"]
           (mapv :instrument-id (get-in readiness [:request :universe]))))
    (is (= #{:missing-candle-history}
           (set (map :code (:warnings readiness)))))))

(deftest build-readiness-blocks-while-history-reload-is-pending-test
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:history-load-state {:status :loading
                                            :request-signature {:universe [{:instrument-id "perp:BTC"
                                                                           :market-type :perp
                                                                           :coin "BTC"}
                                                                          {:instrument-id "perp:ETH"
                                                                           :market-type :perp
                                                                           :coin "ETH"}]}}
                       :history-data
                       {:candle-history-by-coin
                        {"BTC" [{:time 1000 :close "100"}
                                {:time 2000 :close "110"}]
                         "ETH" [{:time 1000 :close "2000"}
                                {:time 2000 :close "2200"}]}
                        :funding-history-by-coin {}}}}}))]
    (is (= :blocked (:status readiness)))
    (is (= :history-loading (:reason readiness)))
    (is (= false (:runnable? readiness)))
    (is (= ["perp:BTC" "perp:ETH"]
           (mapv :instrument-id (get-in readiness [:request :universe]))))))

(deftest build-readiness-injects-orderbook-cost-contexts-into-request-test
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:orderbooks {"BTC" {:timestamp 2400
                                         :render {:best-bid {:px-num 99}
                                                  :best-ask {:px-num 101}}}}
                     :portfolio
                     {:optimizer
                      {:draft {:execution-assumptions {:fallback-slippage-bps 35}}
                       :history-data
                       {:candle-history-by-coin
                        {"BTC" [{:time 1000 :close "100"}
                                {:time 2000 :close "110"}]
                         "ETH" [{:time 1000 :close "2000"}
                                {:time 2000 :close "2200"}]}
                        :funding-history-by-coin {}}}}}))]
    (is (= :ready (:status readiness)))
    (is (= {:best-bid {:px-num 99}
            :best-ask {:px-num 101}
            :source :live-orderbook
            :stale? false}
           (get-in readiness
                   [:request :execution-assumptions :cost-contexts-by-id "perp:BTC"])))
    (is (= :fallback-cost-assumption
           (get-in readiness
                   [:request :execution-assumptions :cost-contexts-by-id "perp:ETH" :source])))
    (is (= 35
           (get-in readiness
                   [:request :execution-assumptions :cost-contexts-by-id "perp:ETH" :fallback-bps])))))
