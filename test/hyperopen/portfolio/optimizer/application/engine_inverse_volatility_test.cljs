(ns hyperopen.portfolio.optimizer.application.engine-inverse-volatility-test
  "End-to-end engine coverage for the Risk-weighted sizing
  (:inverse-volatility) objective on the real quadprog adapter (ExecPlan
  optimizer-inverse-volatility-objective, acceptance items 2, 3, 6): every
  selected asset gets a nonzero target on its chosen side, |w|*sigma is equal
  across unbound assets, dust removal stays disabled, binding caps pin and
  are flagged, and an invalid return model never blocks the solve."
  (:require [cljs.spec.alpha :as s]
            [cljs.test :refer-macros [deftest is testing]]
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

(def ^:private base-constraints
  {:long-only? false
   :gross-max 2.0
   :net-min 0.0
   :net-max 0.0
   :max-asset-weight 2.0
   :max-turnover nil
   :perp-leverage {"perp:BTC" {:max-weight 2.0}
                   "perp:ETH" {:max-weight 2.0}
                   "perp:SOL" {:max-weight 2.0}}
   :rebalance-tolerance 0.001})

(defn- inverse-volatility-request
  "BTC long, ETH and SOL short, 2.0x gross target — mirrors the Equal Risk
  engine fixture with the objective kind swapped. `draft-overrides` is a
  shallow merge over the draft; pass :constraints as a partial map and it is
  merged over `base-constraints` (the fixture deep-merge would otherwise pull
  the long-only fixture defaults back in)."
  [& [draft-overrides]]
  (fixtures/sample-engine-request
   {:draft (fixtures/sample-draft
            (merge
             {:id "inverse-volatility-scenario"
              :universe [{:instrument-id "perp:BTC"
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
              :return-model {:kind :historical-mean}
              :risk-model {:kind :sample-covariance}
              :objective {:kind :inverse-volatility}
              :constraints base-constraints
              :execution-assumptions {:fallback-slippage-bps 20
                                      :prices-by-id {"perp:BTC" 100
                                                     "perp:ETH" 50
                                                     "perp:SOL" 20}}}
             (cond-> draft-overrides
               (:constraints draft-overrides)
               (update :constraints #(merge base-constraints %)))))
    :current-portfolio (fixtures/sample-current-portfolio
                        {:capital {:nav-usdc 10000}
                         :by-instrument {"perp:BTC" {:weight 0.6}
                                         "perp:ETH" {:weight 0.4}}})
    :history-data history-data
    :market-cap-by-coin {}
    :as-of-ms 1000}))

(defn- sizing-row
  [result instrument-id]
  (->> (get-in result [:inverse-volatility :sizing-rows])
       (filter #(= instrument-id (:instrument-id %)))
       first))

(defn- risk-weights-close?
  "|w|*sigma parity across rows within a 5% relative band — loose enough for
  the feasible projection, far tighter than any capped/uncapped gap."
  [rows]
  (let [risk-weights (keep :risk-weight rows)]
    (and (= (count rows) (count risk-weights))
         (seq risk-weights)
         (let [low (apply min risk-weights)
               high (apply max risk-weights)]
           (and (pos? low)
                (< (/ (- high low) high) 0.05))))))

(deftest inverse-volatility-solves-end-to-end-with-sizing-payload-test
  (let [problems (atom [])
        solve-problem (fn [problem]
                        (swap! problems conj problem)
                        (solver-adapter/solve-with-quadprog problem))
        result (engine/run-optimization (inverse-volatility-request)
                                        {:solve-problem solve-problem})
        weights-by-id (:target-weights-by-instrument result)]
    (is (= :solved (:status result))
        (pr-str (select-keys result [:status :reason :details :message])))
    (is (= :inverse-volatility (get-in result [:solver :strategy])))
    (is (= :inverse-volatility (get-in result [:solver :objective-kind])))
    (testing "no frontier sweep: one covariance-only projection problem"
      (is (= 1 (count @problems)))
      (is (every? #(= :inverse-volatility (:objective-kind %)) @problems))
      (is (every? #(nil? (:return-tilt %)) @problems)))
    (testing "every selected asset holds a nonzero target on its chosen side"
      (is (pos? (or (get weights-by-id "perp:BTC") 0)))
      (is (neg? (or (get weights-by-id "perp:ETH") 0)))
      (is (neg? (or (get weights-by-id "perp:SOL") 0))))
    (testing "the signed-gross target is hit"
      (is (near? 2.0 (get-in result [:diagnostics :gross-exposure]) 1e-4)))
    (testing "|w|*sigma is equal across unbound assets (the sizing card's proof)"
      (let [rows (get-in result [:inverse-volatility :sizing-rows])]
        (is (= 3 (count rows))
            (str "expected one sizing row per asset, got " (pr-str rows)))
        (is (risk-weights-close? rows)
            (str "risk weights should equalize, got "
                 (pr-str (mapv (juxt :instrument-id :risk-weight) rows))))
        (is (every? #(number? (:sigma %)) rows))
        (is (every? #(number? (:weight %)) rows))
        (is (not-any? #(true? (:turnover-limited? %)) rows)
            "nothing binds on the clean run, so no row is turnover-limited")))
    (testing "the payload section carries the seed and the sizing deviation"
      (is (vector? (get-in result [:inverse-volatility :seed-weights])))
      (is (number? (get-in result [:inverse-volatility :max-sizing-deviation]))))
    (testing "the objective-agnostic analytics ride the payload (Milestone 6):
              signed contributions as a :diagnostic (no convergence quality
              applies to the deterministic projection) plus the correlation
              :risk-structure section"
      (let [contributions (:risk-contributions result)]
        (is (map? contributions)
            "solved inverse-vol payloads carry :risk-contributions")
        (is (= :diagnostic (:quality contributions))
            (str "the diagnostic quality label, got "
                 (pr-str (:quality contributions))))
        (is (= 3 (count (:relative-contributions contributions)))))
      (is (map? (:current-risk-contributions result))
          "the current book (BTC/ETH) summarizes for current-vs-recommended")
      (let [structure (:risk-structure result)]
        (is (map? structure)
            "solved inverse-vol payloads carry :risk-structure")
        (is (map? (:correlation structure)))
        (is (map? (:standalone-share-by-instrument structure)))))
    (testing "the solved payload passes the canonical result specs"
      (is (s/valid? :hyperopen.portfolio.optimizer.contracts/result-payload result)
          (s/explain-str :hyperopen.portfolio.optimizer.contracts/result-payload
                         result)))))

(deftest inverse-volatility-disables-dust-removal-test
  (let [result (engine/run-optimization
                (inverse-volatility-request
                 {:constraints {:dust-usdc 4500.0}})
                {:solve-problem solver-adapter/solve-with-quadprog})
        weights-by-id (:target-weights-by-instrument result)]
    (is (= :solved (:status result))
        (pr-str (select-keys result [:status :reason :details :message])))
    (is (= [] (:dropped-weights result)))
    (doseq [instrument-id ["perp:BTC" "perp:ETH" "perp:SOL"]]
      (is (not (near? 0.0 (or (get weights-by-id instrument-id) 0) 1e-9))
          (str instrument-id " must keep its position despite :dust-usdc")))))

(deftest inverse-volatility-binding-cap-pins-and-is-flagged-test
  (let [result (engine/run-optimization
                (inverse-volatility-request
                 {:constraints {:perp-leverage {"perp:BTC" {:max-weight 2.0}
                                                "perp:ETH" {:max-weight 2.0}
                                                "perp:SOL" {:max-weight 0.2}}}})
                {:solve-problem solver-adapter/solve-with-quadprog})
        weights-by-id (:target-weights-by-instrument result)
        sol-weight (get weights-by-id "perp:SOL")
        sol-row (sizing-row result "perp:SOL")
        free-rows [(sizing-row result "perp:BTC")
                   (sizing-row result "perp:ETH")]]
    (is (= :solved (:status result))
        (pr-str (select-keys result [:status :reason :details :message])))
    (testing "the capped asset pins at its cap, still on its side"
      (is (near? 0.2 (js/Math.abs (or sol-weight 0)) 1e-4))
      (is (neg? (or sol-weight 0))))
    (testing "the gross target still holds"
      (is (near? 2.0 (get-in result [:diagnostics :gross-exposure]) 1e-4)))
    (testing "the unbound assets re-equalize |w|*sigma among themselves"
      (is (risk-weights-close? free-rows)
          (str "free risk weights should re-equalize, got "
               (pr-str (mapv (juxt :instrument-id :risk-weight) free-rows)))))
    (testing "the payload flags the capped row as moved off the seed"
      (is (true? (:moved-off-seed? sol-row))
          (str "capped row must carry :moved-off-seed? true, got "
               (pr-str sol-row)))
      (is (not-any? #(true? (:moved-off-seed? %)) free-rows)
          "unbound rows are not flagged"))))

(deftest inverse-volatility-binding-turnover-limits-free-rows-and-is-flagged-test
  (let [result (engine/run-optimization
                (inverse-volatility-request
                 {:constraints {:gross-max 1.0
                                :max-turnover 0.5}})
                {:solve-problem solver-adapter/solve-with-quadprog})
        rows (get-in result [:inverse-volatility :sizing-rows])
        row-summary (mapv (juxt :instrument-id :risk-weight
                                :turnover-limited? :moved-off-seed?)
                          rows)]
    (is (= :solved (:status result))
        (pr-str (select-keys result [:status :reason :details :message])))
    (testing "the gross equality still pins realized gross"
      (is (near? 1.0 (get-in result [:diagnostics :gross-exposure]) 1e-4)))
    (testing "a displaced free row wears the turnover flag"
      (is (some #(true? (:turnover-limited? %)) rows)
          (str "expected a :turnover-limited? row, got " (pr-str row-summary))))
    (testing "no row is falsely blamed on a cap/lock/side bound"
      (is (not-any? #(true? (:moved-off-seed? %)) rows)
          (str "no bound pinned any row, got " (pr-str row-summary))))
    (testing "the parity spread is real, not numerical noise"
      (is (pos? (get-in result [:inverse-volatility :max-sizing-deviation]))))
    (testing "the section publishes the free-set parity the card renders"
      (is (number? (get-in result [:inverse-volatility :ideal-risk-weight]))))))

(deftest inverse-volatility-proceeds-when-return-model-is-invalid-test
  (let [invalid-views {:kind :black-litterman
                       :views [{:id "doge-view"
                                :kind :absolute
                                :instrument-id "perp:DOGE"
                                :weights {"perp:DOGE" 1}
                                :return 0.2
                                :confidence 0.75}]}
        result (engine/run-optimization
                (assoc (inverse-volatility-request) :return-model invalid-views)
                {:solve-problem solver-adapter/solve-with-quadprog})]
    (is (= :solved (:status result))
        (str "an invalid return model must not block a covariance-only solve: "
             (pr-str (select-keys result [:status :reason :details :message]))))
    (is (nil? (:expected-return result)))
    (is (nil? (get-in result [:performance :in-sample-sharpe])))
    (is (some #(= :return-model-unavailable-for-display (:code %))
              (:warnings result)))
    (is (some? (get result :inverse-volatility))
        "the sizing section still rides the payload")))
