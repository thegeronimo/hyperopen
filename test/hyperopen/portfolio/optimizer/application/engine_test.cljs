(ns hyperopen.portfolio.optimizer.application.engine-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.application.engine :as engine]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]
            [hyperopen.portfolio.optimizer.defaults :as defaults]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.portfolio.optimizer.infrastructure.solver-adapter :as solver-adapter]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.000001))

(def base-request
  (fixtures/sample-engine-request
   {:draft (fixtures/sample-draft
            {:id "scenario-1"
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
                           :max-asset-weight 0.8
                           :rebalance-tolerance 0.001}
             :execution-assumptions {:fallback-slippage-bps 20
                                     :prices-by-id {"perp:BTC" 100
                                                    "perp:ETH" 50}
                                     :fee-bps-by-id {"perp:BTC" 4
                                                    "perp:ETH" 5}}})
    :current-portfolio (fixtures/sample-current-portfolio
                        {:capital {:nav-usdc 10000}
                         :by-instrument {"perp:BTC" {:weight 0.6}
                                         "perp:ETH" {:weight 0.4}}})
    :history-data {:candle-history-by-coin
                   {"BTC" [{:time-ms 0 :close "100"}
                           {:time-ms 100 :close "101"}
                           {:time-ms 200 :close "103.02"}
                           {:time-ms 300 :close "106.1106"}]
                    "ETH" [{:time-ms 0 :close "100"}
                           {:time-ms 100 :close "102"}
                           {:time-ms 200 :close "103.02"}
                           {:time-ms 300 :close "103.02"}]}
                   :funding-history-by-coin
                   {"BTC" [{:time-ms 0 :funding-rate-raw 0.000045662100456621}]
                    "ETH" [{:time-ms 0 :funding-rate-raw -0.000009132420091324}]}}
    :market-cap-by-coin {}
    :as-of-ms 1000}))

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
    (is (= :minimum-variance (:objective-kind (first @calls))))
    (is (> (count @calls) 1))
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

(deftest run-optimization-labels-vault-frontier-overlays-by-human-name-test
  (let [vault-address "0x1111111111111111111111111111111111111111"
        vault-id (str "vault:" vault-address)
        request (fixtures/sample-engine-request
                 {:draft (fixtures/sample-draft
                          {:id "vault-frontier-labels"
                           :universe [{:instrument-id "perp:BTC"
                                       :market-type :perp
                                       :coin "BTC"
                                       :shortable? true}
                                      {:instrument-id vault-id
                                       :market-type :vault
                                       :coin vault-id
                                       :vault-address vault-address
                                       :name "BTC Basis Carry Vault"
                                       :symbol "BTC Basis Carry Vault"
                                       :shortable? false}]
                           :objective {:kind :minimum-variance}
                           :return-model {:kind :historical-mean}
                           :risk-model {:kind :diagonal-shrink}
                           :constraints {:long-only? true
                                         :max-asset-weight 0.8
                                         :rebalance-tolerance 0.001}})
                  :current-portfolio (fixtures/sample-current-portfolio
                                      {:by-instrument {"perp:BTC" {:weight 0.45
                                                                  :market-type :perp
                                                                  :coin "BTC"}}})
                  :history-data {:candle-history-by-coin
                                 {"BTC" [{:time-ms 1000 :close "100"}
                                         {:time-ms 2000 :close "110"}
                                         {:time-ms 3000 :close "121"}
                                         {:time-ms 4000 :close "133.1"}]}
                                 :funding-history-by-coin
                                 {"BTC" [{:time-ms 1000
                                          :funding-rate-raw 0.0001}]}
                                 :vault-details-by-address
                                 {vault-address
                                  {:portfolio
                                   {:month
                                    {:accountValueHistory [[1000 100]
                                                           [2000 106]
                                                           [3000 114]
                                                           [4000 125]]
                                     :pnlHistory [[1000 0]
                                                  [2000 6]
                                                  [3000 14]
                                                  [4000 25]]}}}}}
                  :market-cap-by-coin {"BTC" 600}
                  :as-of-ms 5000})
        result (engine/run-optimization
                request
                {:solve-problem (fn [_problem]
                                  {:status :solved
                                   :solver :fixture-solver
                                   :weights [0.5 0.5]})})
        vault-standalone (first (filter #(= vault-id (:instrument-id %))
                                        (get-in result [:frontier-overlays :standalone])))
        vault-contribution (first (filter #(= vault-id (:instrument-id %))
                                          (get-in result [:frontier-overlays :contribution])))]
    (is (= :solved (:status result)))
    (is (= "BTC Basis Carry Vault" (:label vault-standalone)))
    (is (= "BTC Basis Carry Vault" (:label vault-contribution)))
    (is (not= vault-id (:label vault-standalone)))
    (is (not= vault-id (:label vault-contribution)))))

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

(deftest minimum-variance-emits-display-frontier-without-changing-selected-target-test
  (let [calls (atom [])
        result (engine/run-optimization
                (assoc base-request
                       :objective {:kind :minimum-variance}
                       :return-tilts [0 1])
                {:solve-problem (fn [problem]
                                  (let [idx (count @calls)]
                                    (swap! calls conj problem)
                                     {:status :solved
                                      :solver :fixture-solver
                                      :weights (case idx
                                                0 [0.5 0.5]
                                                1 [0.8 0.2]
                                                2 [0.5 0.5]
                                                3 [0.8 0.2]
                                                4 [0.5 0.5]
                                                [0.5 0.5])}))})]
    (is (= :solved (:status result)))
    (is (= :single-qp (get-in result [:solver :strategy])))
    (is (= [0.5 0.5] (:target-weights result))
        "Minimum variance target weights must come from the target solve, not the display sweep.")
    (is (= 5 (count @calls)))
    (is (= :minimum-variance (:objective-kind (first @calls))))
    (is (= [:return-tilted :return-tilted :return-tilted :return-tilted]
           (mapv :objective-kind (rest @calls))))
    (is (= :display-sweep (get-in result [:frontier-summary :source])))
    (is (= 2 (get-in result [:frontier-summary :point-count])))
    (is (= 2 (count (:frontier result))))
    (is (seq (get-in result [:frontier-overlays :standalone])))
    (is (seq (get-in result [:frontier-overlays :contribution])))))

(deftest minimum-variance-emits-unconstrained-and-constrained-display-frontiers-test
  (let [calls (atom [])
        result (engine/run-optimization
                (assoc base-request
                       :objective {:kind :minimum-variance
                                   :frontier-points 3}
                       :constraints {:long-only? true
                                     :max-asset-weight 0.5})
                {:solve-problem (fn [problem]
                                  (swap! calls conj problem)
                                  {:status :solved
                                   :solver :fixture-solver
                                   :weights (cond
                                              (= :minimum-variance
                                                 (:objective-kind problem))
                                              [0.5 0.5]

                                              (= [1 1] (:upper-bounds problem))
                                              [0 1]

                                              :else
                                              [0.5 0.5])})})]
    (is (= :solved (:status result)))
    (is (= :unconstrained (get-in result [:frontier-summary :constraint-mode])))
    (is (= (:frontier result) (get-in result [:frontiers :unconstrained])))
    (is (seq (get-in result [:frontiers :constrained])))
    (is (= :constrained
           (get-in result [:frontier-summaries :constrained :constraint-mode])))
    (is (some #(= [1 1] (:upper-bounds %)) @calls)
        "The default display frontier should remove per-asset caps.")
    (is (some #(= [0.5 0.5] (:upper-bounds %)) @calls)
        "The constrained display frontier should retain scenario caps.")))

(deftest minimum-variance-keeps-target-result-when-display-frontier-fails-test
  (let [calls (atom [])
        result (engine/run-optimization
                (assoc base-request
                       :objective {:kind :minimum-variance}
                       :return-tilts [0 1])
                {:solve-problem (fn [problem]
                                  (swap! calls conj problem)
                                  (if (= :minimum-variance (:objective-kind problem))
                                    {:status :solved
                                     :solver :fixture-solver
                                     :weights [0.5 0.5]}
                                    {:status :error
                                     :reason :fixture-display-frontier-failure}))})]
    (is (= :solved (:status result)))
    (is (= [0.5 0.5] (:target-weights result)))
    (is (= 5 (count @calls)))
    (is (= :target-solve (get-in result [:frontier-summary :source])))
    (is (= 1 (get-in result [:frontier-summary :point-count])))
    (is (= 1 (count (:frontier result))))
    (is (some #(= :display-frontier-unavailable (:code %))
              (:warnings result)))))

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

(deftest default-signed-minimum-variance-run-respects-net-min-floor-test
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
                   (is (<= 0.049 (get-in result [:diagnostics :net-exposure])))
                   (is (<= 0.049 (get-in result [:diagnostics :gross-exposure])))
                   (is (not (contains? (set (map :code (:warnings result)))
                                       :low-invested-exposure)))
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
