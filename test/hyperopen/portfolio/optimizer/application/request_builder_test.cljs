(ns hyperopen.portfolio.optimizer.application.request-builder-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.defaults :as defaults]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

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
                                         :views [{:id "view-1"
                                                  :kind :relative
                                                  :long-instrument-id "perp:BTC"
                                                  :short-instrument-id "spot:PURR"
                                                  :return 0.04
                                                  :confidence 0.8}]}
                          :risk-model {:kind :diagonal-shrink
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
    (is (= :diagonal-shrink (get-in request [:risk-model :kind])))
    (is (= {:id "view-1"
            :kind :relative
            :long-instrument-id "perp:BTC"
            :short-instrument-id "spot:PURR"
            :return 0.04
            :confidence 0.8
            :weights {"perp:BTC" 1
                      "spot:PURR" -1}}
           (dissoc (first (get-in request [:return-model :views]))
                   :confidence-variance)))
    (is (near? 0.2 (get-in request [:return-model :views 0 :confidence-variance])))
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

(deftest build-engine-request-normalizes-setup-constraint-keys-test
  (let [draft (assoc (defaults/default-draft)
                     :id "draft-constraints"
                     :universe [{:instrument-id "perp:BTC"
                                 :market-type :perp
                                 :coin "BTC"}]
                     :constraints {:long-only? false
                                   :gross-max 1.3
                                   :net-min -0.2
                                   :net-max 0.8
                                   :max-asset-weight 0.6
                                   :allowlist ["perp:BTC"]
                                   :blocklist ["spot:PURR"]
                                   :asset-overrides {"perp:BTC" {:max-weight 0.5}}
                                   :held-locks ["perp:BTC"]
                                   :perp-leverage {"perp:BTC" {:max-weight 0.4}}
                                   :max-turnover 0.25
                                   :rebalance-tolerance 0.01})
        request (request-builder/build-engine-request
                 {:draft draft
                  :current-portfolio {:by-instrument {"perp:BTC" {:weight 1}}}
                  :history-data {:candle-history-by-coin
                                 {"BTC" [{:time 1000 :close "100"}
                                         {:time 2000 :close "110"}]}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms 2500})
        constraints (:constraints request)]
    (is (= 1.3 (:gross-leverage constraints)))
    (is (= {:min -0.2 :max 0.8} (:net-exposure constraints)))
    (is (= ["perp:BTC"] (:allowlist constraints)))
    (is (= ["spot:PURR"] (:blocklist constraints)))
    (is (= {"perp:BTC" {:max-weight 0.5}}
           (:per-asset-overrides constraints)))
    (is (= ["perp:BTC"] (:held-position-locks constraints)))
    (is (= {"perp:BTC" {:max-weight 0.4}}
           (:per-perp-leverage-caps constraints)))
    (is (not (contains? constraints :gross-max)))
    (is (not (contains? constraints :net-min)))
    (is (not (contains? constraints :asset-overrides)))))

(deftest build-engine-request-treats-empty-allowlist-as-unbounded-test
  (let [draft (assoc (defaults/default-draft)
                     :id "draft-default-constraints"
                     :universe [{:instrument-id "perp:BTC"
                                 :market-type :perp
                                 :coin "BTC"}])
        request (request-builder/build-engine-request
                 {:draft draft
                  :current-portfolio {:by-instrument {"perp:BTC" {:weight 1}}}
                  :history-data {:candle-history-by-coin
                                 {"BTC" [{:time 1000 :close "100"}
                                         {:time 2000 :close "110"}]}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms 2500})
        constraints (:constraints request)]
    (is (nil? (:allowlist constraints)))
    (is (= [] (:blocklist constraints)))
    (is (= 1.0 (:gross-leverage constraints)))
    (is (= {:min 1.0 :max 1.0} (:net-exposure constraints)))
    (is (= 1.0 (:max-turnover constraints)))))

(deftest build-engine-request-normalizes-execution-assumptions-test
  (let [draft (assoc (defaults/default-draft)
                     :id "draft-execution-assumptions"
                     :universe [{:instrument-id "perp:BTC"
                                 :market-type :perp
                                 :coin "BTC"}]
                     :execution-assumptions {:default-order-type :market
                                             :slippage-fallback-bps 25
                                             :fee-mode :taker})
        request (request-builder/build-engine-request
                 {:draft draft
                  :current-portfolio {:by-instrument {"perp:BTC" {:weight 1}}}
                  :history-data {:candle-history-by-coin
                                 {"BTC" [{:time 1000 :close "100"}
                                         {:time 2000 :close "110"}]}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms 2500})
        assumptions (:execution-assumptions request)]
    (is (= :market (:default-order-type assumptions)))
    (is (= 25 (:fallback-slippage-bps assumptions)))
    (is (= :taker (:fee-mode assumptions)))
    (is (not (contains? assumptions :slippage-fallback-bps)))))
