(ns hyperopen.portfolio.optimizer.application.engine-current-portfolio-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.engine :as engine]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.000001))

(deftest run-optimization-locates-current-portfolio-outside-selected-universe-test
  (let [day-ms 86400000
        selected-history {"BTC" [{:time-ms 0 :close "100"}
                                 {:time-ms day-ms :close "101"}
                                 {:time-ms (* 2 day-ms) :close "102"}]
                          "ETH" [{:time-ms 0 :close "100"}
                                 {:time-ms day-ms :close "99"}
                                 {:time-ms (* 2 day-ms) :close "100"}]}
        hype-history [{:time-ms 0 :close "100"}
                      {:time-ms day-ms :close "110"}
                      {:time-ms (* 2 day-ms) :close "105"}
                      {:time-ms (* 3 day-ms) :close "120"}]
        request (request-builder/build-engine-request
                 {:draft (fixtures/sample-draft
                          {:id "outside-current"
                           :universe [{:instrument-id "perp:BTC"
                                       :market-type :perp
                                       :coin "BTC"}
                                      {:instrument-id "perp:ETH"
                                       :market-type :perp
                                       :coin "ETH"}]
                           :return-model {:kind :historical-mean}
                           :risk-model {:kind :sample-covariance}
                           :objective {:kind :minimum-variance}
                           :constraints {:long-only? true
                                         :max-asset-weight 1}})
                  :current-portfolio {:capital {:nav-usdc 10000}
                                      :exposures [{:instrument-id "perp:HYPE"
                                                   :market-type :perp
                                                   :coin "HYPE"
                                                   :weight 0.25
                                                   :signed-notional-usdc 2500
                                                   :abs-notional-usdc 2500}]
                                      :by-instrument {"perp:HYPE" {:instrument-id "perp:HYPE"
                                                                  :market-type :perp
                                                                  :coin "HYPE"
                                                                  :weight 0.25}}}
                  :history-data {:candle-history-by-coin selected-history
                                 :funding-history-by-coin {}
                                 :current-portfolio-history-data
                                 {:candle-history-by-coin {"HYPE" hype-history}
                                  :funding-history-by-coin {}}}
                  :market-cap-by-coin {}
                  :as-of-ms (* 4 day-ms)})
        result (engine/run-optimization
                request
                {:solve-problem (fn [_problem]
                                  {:status :solved
                                   :solver :fixture-solver
                                   :weights [0.5 0.5]})})]
    (is (= ["perp:BTC" "perp:ETH"] (:instrument-ids result)))
    (is (= {"perp:BTC" 0
            "perp:ETH" 0}
           (:current-weights-by-instrument result))
        "Selected-universe current weights should stay aligned to optimizer assets.")
    (is (= {"perp:HYPE" 0.25}
           (:current-portfolio-weights-by-instrument result))
        "The current marker should keep outside-universe current holdings.")
    (is (pos? (:current-volatility result)))
    (is (not (zero? (:current-expected-return result))))))

(deftest run-optimization-current-marker-uses-selected-basis-when-overlapping-test
  (let [day-ms 86400000
        selected-history {"BTC" [{:time-ms 0 :close "100"}
                                 {:time-ms day-ms :close "100.5"}
                                 {:time-ms (* 2 day-ms) :close "101"}]
                          "ETH" [{:time-ms 0 :close "100"}
                                 {:time-ms day-ms :close "100.4"}
                                 {:time-ms (* 2 day-ms) :close "100.8"}]}
        hype-history [{:time-ms 0 :close "100"}
                      {:time-ms day-ms :close "160"}
                      {:time-ms (* 2 day-ms) :close "70"}]
        request (request-builder/build-engine-request
                 {:draft (fixtures/sample-draft
                          {:id "overlap-current"
                           :universe [{:instrument-id "perp:BTC" :market-type :perp :coin "BTC"}
                                      {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"}]
                           :return-model {:kind :historical-mean}
                           :risk-model {:kind :sample-covariance}
                           :objective {:kind :minimum-variance}
                           :constraints {:long-only? true :max-asset-weight 1}})
                  :current-portfolio {:capital {:nav-usdc 10000}
                                      :exposures [{:instrument-id "perp:BTC" :market-type :perp :coin "BTC"
                                                   :weight 0.3 :signed-notional-usdc 3000 :abs-notional-usdc 3000}
                                                  {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"
                                                   :weight 0.2 :signed-notional-usdc 2000 :abs-notional-usdc 2000}
                                                  {:instrument-id "perp:HYPE" :market-type :perp :coin "HYPE"
                                                   :weight 0.25 :signed-notional-usdc 2500 :abs-notional-usdc 2500}]
                                      :by-instrument {"perp:BTC" {:instrument-id "perp:BTC" :market-type :perp :coin "BTC" :weight 0.3}
                                                      "perp:ETH" {:instrument-id "perp:ETH" :market-type :perp :coin "ETH" :weight 0.2}
                                                      "perp:HYPE" {:instrument-id "perp:HYPE" :market-type :perp :coin "HYPE" :weight 0.25}}}
                  :history-data {:candle-history-by-coin selected-history
                                 :funding-history-by-coin {}
                                 :current-portfolio-history-data
                                 {:candle-history-by-coin {"BTC" (get selected-history "BTC")
                                                           "ETH" (get selected-history "ETH")
                                                           "HYPE" hype-history}
                                  :funding-history-by-coin {}}}
                  :market-cap-by-coin {}
                  :as-of-ms (* 4 day-ms)})
        result (engine/run-optimization
                request
                {:solve-problem (fn [_problem]
                                  {:status :solved :solver :fixture-solver :weights [0.5 0.5]})})
        marker (:current-portfolio-weights-by-instrument result)]
    (is (= #{"perp:BTC" "perp:ETH"} (set (keys marker)))
        "Current marker uses the selected universe, not the held-book superset.")
    (is (not (contains? marker "perp:HYPE")))
    (is (pos? (:current-volatility result)))
    (is (< (:current-volatility result) (* 3 (:volatility result)))
        "Current vol must share the frontier's covariance basis, not the wild held-book one.")))

(deftest run-optimization-labels-held-only-spot-by-token-symbol-test
  (let [day-ms 86400000
        selected-history {"BTC" [{:time-ms 0 :close "100"}
                                 {:time-ms day-ms :close "101"}
                                 {:time-ms (* 2 day-ms) :close "102"}]
                          "ETH" [{:time-ms 0 :close "100"}
                                 {:time-ms day-ms :close "99"}
                                 {:time-ms (* 2 day-ms) :close "100"}]}
        request (request-builder/build-engine-request
                 {:draft (fixtures/sample-draft
                          {:id "held-only-spot-label"
                           :universe [{:instrument-id "perp:BTC"
                                       :market-type :perp
                                       :coin "BTC"}
                                      {:instrument-id "perp:ETH"
                                       :market-type :perp
                                       :coin "ETH"}]
                           :return-model {:kind :historical-mean}
                           :risk-model {:kind :sample-covariance}
                           :objective {:kind :minimum-variance}
                           :constraints {:long-only? true
                                         :max-asset-weight 1}})
                  :current-portfolio {:capital {:nav-usdc 10000}
                                      :exposures [{:instrument-id "spot:@113"
                                                   :market-type :spot
                                                   :coin "@113"
                                                   :base "PURR"
                                                   :symbol "PURR/USDC"
                                                   :weight 0.05
                                                   :signed-notional-usdc 500
                                                   :abs-notional-usdc 500}]
                                      :by-instrument {"spot:@113"
                                                      {:instrument-id "spot:@113"
                                                       :market-type :spot
                                                       :coin "@113"
                                                       :base "PURR"
                                                       :symbol "PURR/USDC"
                                                       :weight 0.05}}}
                  :history-data {:candle-history-by-coin selected-history
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms (* 4 day-ms)})
        result (engine/run-optimization
                request
                {:solve-problem (fn [_problem]
                                  {:status :solved
                                   :solver :fixture-solver
                                   :weights [0.5 0.5]})})]
    (is (= "PURR" (get-in result [:labels-by-instrument "spot:@113"]))
        "Held-only spot dust must resolve its token symbol, not the @113 pair id.")))

(deftest run-optimization-scores-current-portfolio-with-black-litterman-views-test
  (let [btc-id "perp:BTC"
        one-year-interval [{:dt-days 365.2425
                            :dt-years 1}]
        history {:calendar [0 1 2 3 4 5]
                 :return-calendar [1 2 3 4 5]
                 :return-series-by-instrument
                 {btc-id [-0.1 -0.05 0 0.05 0.1]}
                 :expected-return-series-by-instrument
                 {btc-id [-0.13]}
                 :expected-return-intervals-by-instrument
                 {btc-id one-year-interval}
                 :funding-by-instrument
                 {btc-id {:source :missing-market-funding-history
                          :annualized-carry 0}}
                 :freshness {:as-of-ms 6
                             :oldest-common-ms 0
                             :latest-common-ms 5
                             :age-ms 1
                             :stale? false}}
        request {:scenario-id "current-bl-views"
                 :universe [{:instrument-id btc-id
                             :market-type :perp
                             :coin "BTC"
                             :shortable? true}]
                 :current-portfolio {:capital {:nav-usdc 10000}
                                     :by-instrument {btc-id {:instrument-id btc-id
                                                            :market-type :perp
                                                            :coin "BTC"
                                                            :weight 1}}}
                 :current-portfolio-history history
                 :return-model {:kind :black-litterman
                                :views [{:id "btc-view"
                                         :kind :absolute
                                         :instrument-id btc-id
                                         :weights {btc-id 1}
                                         :return 0.2
                                         :confidence 0.75
                                         :confidence-variance 0.25}]}
                 :risk-model {:kind :sample-covariance}
                 :objective {:kind :minimum-variance}
                 :constraints {:long-only? true
                               :max-asset-weight 1
                               :rebalance-tolerance 0.001}
                 :history history
                 :black-litterman-prior {:source :market-cap
                                         :weights-by-instrument {btc-id 1}}
                 :warnings []
                 :as-of-ms 6}
        result (engine/run-optimization
                request
                {:solve-problem (fn [_problem]
                                  {:status :solved
                                   :solver :fixture-solver
                                   :weights [1]})})
        effective-return (get-in result [:expected-returns-by-instrument btc-id])]
    (is (= :solved (:status result)))
    (is (pos? effective-return)
        "The optimizer should use the positive Black-Litterman posterior for BTC.")
    (is (near? effective-return (:current-expected-return result))
        "The current portfolio marker should use the same view-adjusted forward return.")))
