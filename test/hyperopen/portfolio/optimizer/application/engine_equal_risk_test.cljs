(ns hyperopen.portfolio.optimizer.application.engine-equal-risk-test
  "End-to-end engine coverage for the :equal-risk objective: request-builder
  -> context -> sequential solve -> payload, on the real quadprog adapter."
  (:require [cljs.spec.alpha :as s]
            [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.portfolio.optimizer.application.engine :as engine]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.contracts.spec-registry]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.portfolio.optimizer.infrastructure.solver-adapter :as solver-adapter]))

(defn- near?
  ([expected actual] (near? expected actual 1e-6))
  ([expected actual tolerance]
   (and (number? actual)
        (< (js/Math.abs (- expected actual)) tolerance))))

(def ^:private history-data
  {:candle-history-by-coin
   {"BTC" [{:time-ms 0 :close "100"}
           {:time-ms 100 :close "101"}
           {:time-ms 200 :close "103.02"}
           {:time-ms 300 :close "106.1106"}
           {:time-ms 400 :close "104"}]
    "ETH" [{:time-ms 0 :close "100"}
           {:time-ms 100 :close "102"}
           {:time-ms 200 :close "103.02"}
           {:time-ms 300 :close "103.02"}
           {:time-ms 400 :close "107"}]
    "SOL" [{:time-ms 0 :close "100"}
           {:time-ms 100 :close "99"}
           {:time-ms 200 :close "104"}
           {:time-ms 300 :close "101"}
           {:time-ms 400 :close "105"}]}
   :funding-history-by-coin
   {"BTC" [{:time-ms 0 :funding-rate-raw 0.00001}]
    "ETH" [{:time-ms 0 :funding-rate-raw -0.00001}]
    "SOL" [{:time-ms 0 :funding-rate-raw 0.00001}]}})

(defn- equal-risk-request
  [& [draft-overrides]]
  (fixtures/sample-engine-request
   {:draft (fixtures/sample-draft
            (merge
             {:id "equal-risk-scenario"
              :universe [{:instrument-id "perp:BTC"
                          :market-type :perp
                          :coin "BTC"
                          :shortable? true
                          :position-side :long}
                         {:instrument-id "perp:ETH"
                          :market-type :perp
                          :coin "ETH"
                          :shortable? true
                          :position-side :long}]
              :return-model {:kind :historical-mean}
              :risk-model {:kind :sample-covariance}
              :objective {:kind :equal-risk}
              :constraints {:long-only? false
                            :gross-max 1.0
                            :net-min 1.0
                            :net-max 1.0
                            :max-asset-weight 0.9
                            :rebalance-tolerance 0.001}
              :execution-assumptions {:fallback-slippage-bps 20
                                      :prices-by-id {"perp:BTC" 100
                                                     "perp:ETH" 50}}}
             draft-overrides))
    :current-portfolio (fixtures/sample-current-portfolio
                        {:capital {:nav-usdc 10000}
                         :by-instrument {"perp:BTC" {:weight 0.6}
                                         "perp:ETH" {:weight 0.4}}})
    :history-data history-data
    :market-cap-by-coin {}
    :as-of-ms 1000}))

(deftest equal-risk-request-carries-canonical-exposure-targets-test
  (let [request (equal-risk-request)]
    (is (= :equal-risk (get-in request [:objective :kind])))
    (is (= 1.0 (get-in request [:constraints :gross-leverage])))
    (is (= {:min 1.0 :max 1.0} (get-in request [:constraints :net-exposure])))))

(deftest equal-risk-solves-end-to-end-with-contributions-payload-test
  (let [problems (atom [])
        solve-problem (fn [problem]
                        (swap! problems conj problem)
                        (solver-adapter/solve-with-quadprog problem))
        result (engine/run-optimization (equal-risk-request)
                                        {:solve-problem solve-problem})]
    (is (= :solved (:status result)))
    (is (= :sequential-equal-risk (get-in result [:solver :strategy])))
    (is (= :equal-risk (get-in result [:solver :objective-kind])))
    (testing "no frontier sweep: every solver problem is an equal-risk subproblem"
      (is (pos? (count @problems)))
      (is (every? #(= :equal-risk (:objective-kind %)) @problems))
      (is (every? #(nil? (:return-tilt %)) @problems)))
    (testing "the frontier is the single selected point from the target solve"
      (is (= 1 (count (:frontier result))))
      (is (= :target-solve (get-in result [:frontier-summary :source])))
      (is (every? #(= :target-solve (:source %))
                  (vals (:frontier-summaries result)))))
    (testing "published weights satisfy the exact book targets with no dust drops"
      (is (near? 1.0 (reduce + 0 (:target-weights result))))
      (is (every? #(>= % 0) (:target-weights result)))
      (is (= [] (:dropped-weights result))))
    (testing "risk contributions are computed from the published weights"
      (let [contributions (:risk-contributions result)]
        (is (= :signed-euler-volatility (:method contributions)))
        (is (contains? #{:exact :approximate :not-converged} (:quality contributions)))
        (is (near? 1.0 (:sum-relative-contributions contributions) 1e-9))
        (is (= [0.5 0.5] (:target-relative-contributions contributions)))
        (is (contains? (:relative-contributions-by-instrument contributions) "perp:BTC"))
        (is (contains? (:target-relative-contributions-by-instrument contributions) "perp:ETH"))
        (is (number? (:rms-error contributions)))
        (is (number? (:max-absolute-error contributions)))
        (is (number? (:negative-contribution-count contributions)))))
    (testing "solver metadata is present and truthful"
      (let [solver (:equal-risk-solver result)]
        (is (= :sequential-equal-risk (:strategy solver)))
        (is (boolean? (:converged? solver)))
        (is (keyword? (:termination-reason solver)))
        (is (pos? (:initialization-count solver)))
        (is (keyword? (:selected-initialization solver)))
        (is (number? (:objective-value solver)))
        (is (number? (:exactness-tolerance solver)))))
    (testing "current-book contributions ride the payload for the balance chart"
      (let [current (:current-risk-contributions result)]
        (is (map? current))
        (is (contains? (:relative-contributions-by-instrument current) "perp:BTC"))
        (is (number? (:max-absolute-error current)))))
    (testing "the correlation-view structure section rides the payload"
      (let [structure (:risk-structure result)
            correlation (:correlation structure)
            nets (:relative-contributions-by-instrument
                  (:risk-contributions result))]
        (is (= :signed-euler-decomposition (:method structure)))
        (is (number? (:portfolio-volatility structure)))
        (testing "standalone + diversification reproduces the sibling section's
                  net share exactly (same covariance, same weights)"
          (doseq [instrument-id ["perp:BTC" "perp:ETH"]]
            (is (near? (get nets instrument-id)
                       (+ (get-in structure [:standalone-share-by-instrument
                                             instrument-id])
                          (get-in structure [:diversification-share-by-instrument
                                             instrument-id]))
                       1e-12))))
        (is (every? #(and (>= % -1) (<= % 1))
                    (vals (:pnl-portfolio-correlation-by-instrument structure))))
        (is (= (set (:instrument-ids correlation)) #{"perp:BTC" "perp:ETH"}))
        (is (every? #(= 1.0 %)
                    (map-indexed (fn [idx row] (nth row idx))
                                 (:matrix correlation))))
        (is (zero? (:hidden-count correlation)))))
    (testing "current and recommended diversification benchmarks ride the same payload"
      (let [structure (:risk-structure result)
            target (:target-diversification structure)
            current (:current-diversification structure)]
        (doseq [summary [target current]]
          (is (map? summary))
          (is (every? number?
                      (map summary
                           [:modeled-volatility
                            :all-move-together-volatility
                            :zero-correlation-volatility
                            :reduction-vs-all-move-together
                            :reduction-ratio-vs-all-move-together
                            :modeled-minus-zero-correlation])))
          (is (every? #(js/isFinite %) (vals summary))))
        (is (near? (:portfolio-volatility structure)
                   (:modeled-volatility target)))
        (is (not (contains? target :relative-contributions-by-instrument))
            "scalar benchmarks do not duplicate or rewrite Euler contributions")))
    (testing "allocation freedom classifies the problem geometry"
      (let [freedom (get-in result [:equal-risk-solver :allocation-freedom])]
        (is (contains? #{:open :limited} (:status freedom))
            "two free longs in one book leave one degree of freedom")
        (is (= {:long 2 :short 0} (:books freedom)))
        (is (= 1 (:free-degrees freedom)))))
    (testing "the solved payload passes the canonical result specs"
      (is (s/valid? :hyperopen.portfolio.optimizer.contracts/result-payload result)
          (s/explain-str :hyperopen.portfolio.optimizer.contracts/result-payload result)))))

(deftest equal-risk-sync-and-async-engine-paths-agree-test
  (async done
    (let [request (equal-risk-request)
          sync-result (engine/run-optimization
                       request
                       {:solve-problem solver-adapter/solve-with-quadprog})
          progress-events (atom [])]
      (-> (engine/run-optimization-async
           request
           {:solve-problem solver-adapter/solve-with-quadprog
            :on-progress (fn [payload] (swap! progress-events conj payload))})
          (.then (fn [async-result]
                   (is (= :solved (:status sync-result) (:status async-result)))
                   (is (= (:target-weights sync-result)
                          (:target-weights async-result)))
                   (is (= (:risk-contributions sync-result)
                          (:risk-contributions async-result)))
                   (is (= (:equal-risk-solver sync-result)
                          (:equal-risk-solver async-result)))
                   (testing "iterative solve progress is reported"
                     (is (some #(and (= :solve (:step %))
                                     (re-find #"init" (str (:detail %))))
                               @progress-events)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "async equal-risk engine run failed: " err))
                    (done)))))))

(deftest equal-risk-proceeds-when-return-model-is-invalid-test
  (let [invalid-views {:kind :black-litterman
                       :views [{:id "sol-view"
                                :kind :absolute
                                :instrument-id "perp:SOL"
                                :weights {"perp:SOL" 1}
                                :return 0.2
                                :confidence 0.75}]}
        equal-risk-result (engine/run-optimization
                           (assoc (equal-risk-request) :return-model invalid-views)
                           {:solve-problem solver-adapter/solve-with-quadprog})
        max-sharpe-result (engine/run-optimization
                           (assoc (equal-risk-request {:objective {:kind :max-sharpe}})
                                  :return-model invalid-views)
                           {:solve-problem solver-adapter/solve-with-quadprog})]
    (is (= :solved (:status equal-risk-result)))
    (is (nil? (:expected-return equal-risk-result)))
    (is (nil? (get-in equal-risk-result [:performance :in-sample-sharpe])))
    (is (some #(= :return-model-unavailable-for-display (:code %))
              (:warnings equal-risk-result)))
    (is (some? (:risk-contributions equal-risk-result)))
    (is (= :infeasible (:status max-sharpe-result)))
    (is (= :invalid-return-model (:reason max-sharpe-result)))))

(deftest equal-risk-solves-mixed-books-independent-of-stored-net-through-request-builder-test
  (letfn [(run-with-net [net-target]
            (engine/run-optimization
             (equal-risk-request
              {:universe [{:instrument-id "perp:BTC"
                           :market-type :perp
                           :coin "BTC"
                           :shortable? true
                           :position-side :long}
                          {:instrument-id "perp:ETH"
                           :market-type :perp
                           :coin "ETH"
                           :shortable? true
                           :position-side :short}]
               :constraints {:long-only? false
                             :gross-max 2.0
                             :net-min net-target
                             :net-max net-target
                             :max-asset-weight 2.0
                             :max-turnover nil
                             :perp-leverage {"perp:BTC" {:max-weight 2.0}
                                             "perp:ETH" {:max-weight 2.0}}
                             :rebalance-tolerance 0.001}})
             {:solve-problem solver-adapter/solve-with-quadprog}))]
    (let [zero-net-result (run-with-net 0.0)
          short-net-result (run-with-net -1.0)
          weights-a (:target-weights zero-net-result)
          weights-b (:target-weights short-net-result)]
      (doseq [result [zero-net-result short-net-result]]
        (is (= :solved (:status result))
            (pr-str (select-keys result [:status :reason :details :message])))
        (is (pos? (nth (:target-weights result) 0)))
        (is (neg? (nth (:target-weights result) 1)))
        (is (near? 2.0 (get-in result [:diagnostics :gross-exposure]) 1e-6)))
      (is (near? (get-in zero-net-result [:diagnostics :net-exposure])
                 (get-in short-net-result [:diagnostics :net-exposure])
                 1e-6))
      (is (not (near? 0.0 (get-in zero-net-result [:diagnostics :net-exposure])
                      1e-3)))
      (is (not (near? -1.0 (get-in short-net-result [:diagnostics :net-exposure])
                      1e-3)))
      (doseq [[a b] (map vector weights-a weights-b)]
        (is (near? a b 1e-6)))
      (is (= (get-in zero-net-result [:equal-risk-solver :selected-initialization])
             (get-in short-net-result [:equal-risk-solver :selected-initialization])))
      (doseq [instrument-id ["perp:BTC" "perp:ETH"]]
        (is (near? (get-in zero-net-result
                           [:risk-contributions
                            :relative-contributions-by-instrument
                            instrument-id])
                   (get-in short-net-result
                           [:risk-contributions
                            :relative-contributions-by-instrument
                            instrument-id])
                   1e-6)))
      (let [freedom (get-in zero-net-result [:equal-risk-solver
                                             :allocation-freedom])]
        (is (not= :fully-determined (:status freedom)))
        (is (= 1 (:free-degrees freedom)))
        (is (= {:long 1 :short 1} (:books freedom))))
      (doseq [result [zero-net-result short-net-result]]
        (is (s/valid? :hyperopen.portfolio.optimizer.contracts/result-payload result)
            (s/explain-str :hyperopen.portfolio.optimizer.contracts/result-payload
                           result))))))

(deftest equal-risk-stored-net-extreme-does-not-warn-on-the-solved-result-test
  (let [result (engine/run-optimization
                (equal-risk-request
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"
                              :shortable? true
                              :position-side :long}
                             {:instrument-id "perp:ETH"
                              :market-type :perp
                              :coin "ETH"
                              :shortable? true
                              :position-side :short}
                             {:instrument-id "perp:SOL"
                              :market-type :perp
                              :coin "SOL"
                              :shortable? true
                              :position-side :short}]
                  :constraints {:long-only? false
                                :gross-max 2.0
                                :net-min 1.9
                                :net-max 1.9
                                :max-asset-weight 2.0
                                :max-turnover nil
                                :perp-leverage {"perp:BTC" {:max-weight 2.0}
                                                "perp:ETH" {:max-weight 2.0}}
                                :rebalance-tolerance 0.001}})
                {:solve-problem solver-adapter/solve-with-quadprog})
        lopsided (filter #(= :equal-risk-lopsided-books (:code %))
                         (:warnings result))]
    (is (= :solved (:status result))
        (pr-str (select-keys result [:status :reason :details :message])))
    (is (= [] (vec lopsided)))))
