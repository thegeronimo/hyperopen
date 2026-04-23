(ns hyperopen.portfolio.optimizer.application.request-builder-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]))

(deftest build-engine-request-keeps-model-layers-separate-and-attaches-bl-prior-test
  (let [request (request-builder/build-engine-request
                 {:draft {:id "draft-1"
                          :universe [{:instrument-id "perp:BTC"
                                      :market-type :perp
                                      :coin "BTC"}
                                     {:instrument-id "spot:PURR"
                                      :market-type :spot
                                      :coin "PURR"}]
                          :objective {:kind :target-return
                                      :target-return 0.2}
                          :return-model {:kind :black-litterman
                                         :views []}
                          :risk-model {:kind :ledoit-wolf
                                       :shrinkage 0.3}
                          :constraints {:long-only? true
                                        :max-asset-weight 0.4}
                          :execution-assumptions {:slippage-bps 25}}
                  :current-portfolio {:capital {:nav-usdc 1000}
                                      :by-instrument {"perp:BTC" {:weight 0.6}
                                                      "spot:PURR" {:weight 0.4}}}
                  :history-data {:candle-history-by-coin {"BTC" [{:time 1000 :close "100"}
                                                                 {:time 2000 :close "110"}]
                                                          "PURR" [{:time 1000 :close "10"}
                                                                  {:time 2000 :close "12"}]}
                                 :funding-history-by-coin {"BTC" [{:time-ms 1000
                                                                   :funding-rate-raw 0.001}]}}
                  :market-cap-by-coin {"BTC" 900
                                       "PURR" 100}
                  :as-of-ms 2500})]
    (is (= :target-return (get-in request [:objective :kind])))
    (is (= :black-litterman (get-in request [:return-model :kind])))
    (is (= :ledoit-wolf (get-in request [:risk-model :kind])))
    (is (= :market-cap (get-in request [:black-litterman-prior :source])))
    (is (= ["perp:BTC" "spot:PURR"]
           (mapv :instrument-id (:universe request))))
    (is (= ["perp:BTC" "spot:PURR"]
           (mapv :instrument-id (get-in request [:history :eligible-instruments]))))
    (is (= [] (:warnings request)))))

(deftest build-engine-request-surfaces-excluded-history-rows-and-fallback-prior-test
  (let [request (request-builder/build-engine-request
                 {:draft {:universe [{:instrument-id "perp:BTC"
                                      :market-type :perp
                                      :coin "BTC"}
                                     {:instrument-id "spot:MISSING"
                                      :market-type :spot
                                      :coin "MISSING"}]
                          :objective {:kind :minimum-variance}
                          :return-model {:kind :black-litterman}
                          :risk-model {:kind :sample-covariance}
                          :constraints {}}
                  :current-portfolio {:by-instrument {"perp:BTC" {:weight 1}}}
                  :history-data {:candle-history-by-coin {"BTC" [{:time 1000 :close "100"}]}}
                  :market-cap-by-coin {}
                  :as-of-ms 2000})]
    (is (= [] (:universe request)))
    (is (= :fallback-current-portfolio
           (get-in request [:black-litterman-prior :source])))
    (is (= #{:insufficient-candle-history
             :missing-candle-history
             :missing-market-cap-prior}
           (set (map :code (:warnings request)))))
    (is (= ["perp:BTC" "spot:MISSING"]
           (mapv :instrument-id (get-in request [:history :excluded-instruments]))))))
