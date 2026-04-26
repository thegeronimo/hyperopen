(ns hyperopen.portfolio.optimizer.application.engine-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.application.engine :as engine]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]
            [hyperopen.portfolio.optimizer.defaults :as defaults]
            [hyperopen.portfolio.optimizer.infrastructure.solver-adapter :as solver-adapter]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.000001))

(def base-request
  {:scenario-id "scenario-1"
   :universe [{:instrument-id "perp:BTC"
               :market-type :perp
               :coin "BTC"}
              {:instrument-id "perp:ETH"
               :market-type :perp
               :coin "ETH"}]
   :current-portfolio {:capital {:nav-usdc 10000}
                       :by-instrument {"perp:BTC" {:weight 0.6}
                                       "perp:ETH" {:weight 0.4}}}
   :return-model {:kind :historical-mean}
   :risk-model {:kind :sample-covariance}
   :objective {:kind :minimum-variance}
   :constraints {:long-only? true
                 :max-asset-weight 0.8
                 :rebalance-tolerance 0.001}
   :execution-assumptions {:fallback-slippage-bps 20
                           :prices-by-id {"perp:BTC" 100
                                          "perp:ETH" 50}
                           :fee-bps-by-id {"perp:BTC" 4
                                           "perp:ETH" 5}}
   :history {:return-series-by-instrument {"perp:BTC" [0.01 0.02 0.03]
                                           "perp:ETH" [0.02 0.01 0.0]}
             :return-calendar [100 200 300]
             :freshness {:oldest-common-ms 0
                         :latest-common-ms 300
                         :age-ms 700
                         :stale? false}
             :funding-by-instrument {"perp:BTC" {:annualized-carry 0.05
                                                 :source :market-funding-history}
                                     "perp:ETH" {:annualized-carry -0.01
                                                 :source :market-funding-history}}}
   :warnings []
   :as-of-ms 1000})

(deftest run-optimization-assembles-solved-result-with-diagnostics-and-rebalance-preview-test
  (let [calls (atom [])
        result (engine/run-optimization
                base-request
                {:solve-problem (fn [problem]
                                  (swap! calls conj problem)
                                  {:status :solved
                                   :solver :fixture-solver
                                   :weights [0.5 0.5]
                                   :iterations 12
                                   :elapsed-ms 2})})]
    (is (= :solved (:status result)))
    (is (= "scenario-1" (:scenario-id result)))
    (is (= 1 (count @calls)))
    (is (= :minimum-variance (get-in result [:solver :objective-kind])))
    (is (= ["perp:BTC" "perp:ETH"] (:instrument-ids result)))
    (is (= [0.5 0.5] (:target-weights result)))
    (is (= {"perp:BTC" 0.6
            "perp:ETH" 0.4}
           (:current-weights-by-instrument result)))
    (is (= {"perp:BTC" 0.5
            "perp:ETH" 0.5}
           (:target-weights-by-instrument result)))
    (is (near? 1 (:gross-exposure (:diagnostics result))))
    (is (near? 0.1 (get-in result [:diagnostics :turnover])))
    (is (= :ready (get-in result [:rebalance-preview :status])))
    (is (= :market-funding-history
           (get-in result [:return-decomposition-by-instrument "perp:BTC" :funding-source])))
    (is (= {:return-observations 3
            :oldest-common-ms 0
            :latest-common-ms 300
            :age-ms 700
            :stale? false}
           (:history-summary result)))
    (is (seq (:frontier result)))))

(deftest run-optimization-uses-latest-history-price-for-rebalance-preview-test
  (let [result (engine/run-optimization
                (-> base-request
                    (assoc :current-portfolio {:capital {:nav-usdc 100000}
                                               :by-instrument {"perp:BTC" {:weight 0}
                                                               "perp:ETH" {:weight 0}}})
                    (assoc :execution-assumptions {:fallback-slippage-bps 20})
                    (assoc-in [:history :price-series-by-instrument]
                              {"perp:BTC" [{:close 90} {:close 100}]
                               "perp:ETH" [{:close 45} {:close 50}]}))
                {:solve-problem (fn [_problem]
                                  {:status :solved
                                   :weights [0.4 0.6]})})
        rows-by-id (into {}
                         (map (juxt :instrument-id identity))
                         (get-in result [:rebalance-preview :rows]))]
    (is (= :ready (get-in result [:rebalance-preview :status])))
    (is (= 100 (:price (get rows-by-id "perp:BTC"))))
    (is (= 50 (:price (get rows-by-id "perp:ETH"))))
    (is (= 400 (:quantity (get rows-by-id "perp:BTC"))))
    (is (= 1200 (:quantity (get rows-by-id "perp:ETH"))))))

(deftest run-optimization-returns-structured-infeasibility-without-calling-solver-test
  (let [called? (atom false)
        result (engine/run-optimization
                (assoc base-request
                       :constraints {:long-only? true
                                     :max-asset-weight 0.4})
                {:solve-problem (fn [_]
                                  (reset! called? true)
                                  {:status :solved
                                   :weights [0.5 0.5]})})]
    (is (= :infeasible (:status result)))
    (is (= :constraint-presolve (:reason result)))
    (is (false? @called?))
    (is (= [{:code :sum-upper-below-target
             :sum-upper 0.8
             :target-net 1}]
           (get-in result [:details :violations])))))

(deftest run-optimization-solves-frontier-sweep-and-selects-target-volatility-result-test
  (let [result (engine/run-optimization
                (assoc base-request
                       :objective {:kind :target-volatility
                                   :target-volatility 0}
                       :return-tilts [0 1])
                {:solve-problem (fn [problem]
                                  (if (zero? (:return-tilt problem))
                                    {:status :solved
                                     :weights [0.8 0.2]}
                                    {:status :solved
                                     :weights [0.5 0.5]}))})]
    (is (= :solved (:status result)))
    (is (= :frontier-sweep (get-in result [:solver :strategy])))
    (is (= [0.5 0.5] (:target-weights result)))
    (is (= 2 (count (:solver-results result))))
    (is (= 2 (count (:frontier result))))))

(deftest run-optimization-converts-usdc-dust-threshold-to-weight-threshold-test
  (let [result (engine/run-optimization
                (assoc base-request
                       :constraints {:long-only? true
                                     :max-asset-weight 1
                                     :dust-usdc 50})
                {:solve-problem (fn [_problem]
                                  {:status :solved
                                   :weights [0.996 0.004]})})]
    (is (= :solved (:status result)))
    (is (= [1 0] (:target-weights result)))
    (is (= [{:instrument-id "perp:ETH"
             :weight 0.004
             :reason :dust-threshold}]
           (:dropped-weights result)))))

(deftest run-optimization-async-awaits-promise-solver-results-test
  (async done
    (-> (engine/run-optimization-async
         base-request
         {:solve-problem (fn [_problem]
                           (js/Promise.resolve
                            {:status :solved
                             :solver :promise-fixture-solver
                             :weights [0.5 0.5]}))})
        (.then (fn [result]
                 (is (= :solved (:status result)))
                 (is (= :promise-fixture-solver
                        (get-in result [:solver-results 0 :solver])))
                 (is (= [0.5 0.5] (:target-weights result)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "async optimization failed: " err))
                  (done))))))

(deftest default-signed-minimum-variance-run-can-return-near-cash-with-warning-test
  (async done
    (let [instrument (fn [coin]
                       {:instrument-id (str "perp:" coin)
                        :market-type :perp
                        :coin coin
                        :shortable? true})
          candle (fn [time close]
                   {:time time
                    :close close})
          coins ["BTC" "ETH" "SOL" "HYPE"]
          draft (assoc (defaults/default-draft)
                       :id "default-cash-regression"
                       :universe (mapv instrument coins))
          history-by-coin {"BTC" [(candle 1000 "100")
                                  (candle 2000 "104")
                                  (candle 3000 "103")
                                  (candle 4000 "108")]
                           "ETH" [(candle 1000 "50")
                                  (candle 2000 "52")
                                  (candle 3000 "55")
                                  (candle 4000 "54")]
                           "SOL" [(candle 1000 "20")
                                  (candle 2000 "21")
                                  (candle 3000 "20.5")
                                  (candle 4000 "22")]
                           "HYPE" [(candle 1000 "10")
                                   (candle 2000 "10.4")
                                   (candle 3000 "10.2")
                                   (candle 4000 "10.8")]}
          request (request-builder/build-engine-request
                   {:draft draft
                    :current-portfolio {:capital {:nav-usdc 10000}
                                        :by-instrument {}}
                    :history-data {:candle-history-by-coin history-by-coin
                                   :funding-history-by-coin {}}
                    :market-cap-by-coin {}
                    :as-of-ms 5000})]
      (-> (engine/run-optimization-async request
                                          {:solve-problem solver-adapter/solve-with-osqp})
          (.then (fn [result]
                   (is (= :solved (:status result)))
                   (is (near? 0 (get-in result [:diagnostics :gross-exposure])))
                   (is (= [0 0 0 0] (:target-weights result)))
                   (is (contains? (set (map :code (:warnings result)))
                                  :low-invested-exposure))
                   (done)))
          (.catch (fn [err]
                    (is false (str "default minimum-variance regression failed: " err))
                    (done)))))))

(deftest explicit-net-min-floor-forces-default-minimum-variance-exposure-test
  (async done
    (let [instrument (fn [coin]
                       {:instrument-id (str "perp:" coin)
                        :market-type :perp
                        :coin coin
                        :shortable? true})
          candle (fn [time close]
                   {:time time
                    :close close})
          coins ["BTC" "ETH" "SOL" "HYPE"]
          draft (-> (defaults/default-draft)
                    (assoc :id "explicit-net-floor"
                           :universe (mapv instrument coins))
                    (assoc-in [:constraints :net-min] 0.8))
          history-by-coin {"BTC" [(candle 1000 "100")
                                  (candle 2000 "104")
                                  (candle 3000 "103")
                                  (candle 4000 "108")]
                           "ETH" [(candle 1000 "50")
                                  (candle 2000 "52")
                                  (candle 3000 "55")
                                  (candle 4000 "54")]
                           "SOL" [(candle 1000 "20")
                                  (candle 2000 "21")
                                  (candle 3000 "20.5")
                                  (candle 4000 "22")]
                           "HYPE" [(candle 1000 "10")
                                   (candle 2000 "10.4")
                                   (candle 3000 "10.2")
                                   (candle 4000 "10.8")]}
          request (request-builder/build-engine-request
                   {:draft draft
                    :current-portfolio {:capital {:nav-usdc 10000}
                                        :by-instrument {}}
                    :history-data {:candle-history-by-coin history-by-coin
                                   :funding-history-by-coin {}}
                    :market-cap-by-coin {}
                    :as-of-ms 5000})]
      (-> (engine/run-optimization-async request
                                          {:solve-problem solver-adapter/solve-with-osqp})
          (.then (fn [result]
                   (is (= :solved (:status result)))
                   (is (<= 0.799 (get-in result [:diagnostics :net-exposure])))
                   (is (< 0.79 (reduce + 0 (map js/Math.abs (:target-weights result)))))
                   (is (not (contains? (set (map :code (:warnings result)))
                                       :low-invested-exposure)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "explicit net-min regression failed: " err))
                    (done)))))))
