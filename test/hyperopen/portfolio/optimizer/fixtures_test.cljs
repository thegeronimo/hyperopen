(ns hyperopen.portfolio.optimizer.fixtures-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]))

(defn- instrument-ids
  [instruments]
  (mapv :instrument-id instruments))

(defn- approx=
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.000001))

(deftest sample-engine-request-uses-valid-draft-current-portfolio-and-history-test
  (let [draft (fixtures/sample-draft)
        request (fixtures/sample-engine-request)
        ids ["perp:BTC" "perp:ETH" "spot:PURR"]]
    (is (= "fixture-scenario" (:id draft)))
    (is (= "Fixture Optimization" (:name draft)))
    (is (= ids (instrument-ids (:universe draft))))
    (is (= ids (instrument-ids (:universe request))))
    (is (= ids (instrument-ids (get-in request [:history :eligible-instruments]))))
    (is (= [] (:warnings request)))
    (is (= 100000 (get-in request [:current-portfolio :capital :nav-usdc])))
    (is (= 0.45 (get-in request [:current-portfolio :by-instrument "perp:BTC" :weight])))
    (is (= :max-sharpe (get-in request [:objective :kind])))
    (is (= :historical-mean (get-in request [:return-model :kind])))
    (is (= :diagonal-shrink (get-in request [:risk-model :kind])))
    (is (= 3 (count (get-in request [:history :return-calendar]))))
    (is (= :market-funding-history
           (get-in request [:history :funding-by-instrument "perp:BTC" :source])))))

(deftest fixture-builders-accept-focused-overrides-test
  (let [draft (fixtures/sample-draft
               {:id "custom-scenario"
                :constraints {:long-only? false
                              :net-min -0.1}
                :universe [{:instrument-id "perp:SOL"
                            :market-type :perp
                            :coin "SOL"}]})
        current (fixtures/sample-current-portfolio
                 {:capital {:nav-usdc 50000}
                  :by-instrument {"perp:SOL" {:weight 1}}})
        result (fixtures/sample-solved-result
                {:instrument-ids ["perp:SOL"]
                 :target-weights-by-instrument {"perp:SOL" 1}
                 :current-weights-by-instrument {"perp:SOL" 0}})]
    (is (= "custom-scenario" (:id draft)))
    (is (= ["perp:SOL"] (instrument-ids (:universe draft))))
    (is (= false (get-in draft [:constraints :long-only?])))
    (is (= -0.1 (get-in draft [:constraints :net-min])))
    (is (= 0.85 (get-in draft [:constraints :max-asset-weight])))
    (is (= 50000 (get-in current [:capital :nav-usdc])))
    (is (= {"perp:SOL" {:weight 1}} (:by-instrument current)))
    (is (= {"perp:SOL" 1} (:target-weights-by-instrument result)))
    (is (= {"perp:SOL" 0} (:current-weights-by-instrument result)))))

(deftest sample-solved-result-is-internally-aligned-test
  (let [result (fixtures/sample-solved-result)
        ids ["perp:BTC" "perp:ETH" "spot:PURR"]]
    (is (= :solved (:status result)))
    (is (= "fixture-scenario" (:scenario-id result)))
    (is (= ids (:instrument-ids result)))
    (is (= [0.5 0.35 0.05] (:target-weights result)))
    (is (= {"perp:BTC" 0.5
            "perp:ETH" 0.35
            "spot:PURR" 0.05}
           (:target-weights-by-instrument result)))
    (is (= {"perp:BTC" 0.45
            "perp:ETH" 0.3
            "spot:PURR" 0.05}
           (:current-weights-by-instrument result)))
    (is (approx= 0.15 (get-in result [:diagnostics :turnover])))
    (is (= :ready (get-in result [:rebalance-preview :status])))
    (is (= 2 (get-in result [:rebalance-preview :summary :ready-count])))
    (is (= :market-funding-history
           (get-in result [:return-decomposition-by-instrument "perp:BTC" :funding-source])))))

(deftest sample-scenario-state-is-route-and-view-ready-test
  (let [state (fixtures/sample-scenario-state)
        scenario-id "fixture-scenario"]
    (is (= scenario-id (get-in state [:portfolio :optimizer :draft :id])))
    (is (= scenario-id (get-in state [:portfolio :optimizer :active-scenario :loaded-id])))
    (is (= scenario-id (get-in state [:portfolio :optimizer :last-successful-run :result :scenario-id])))
    (is (= scenario-id (get-in state [:portfolio :optimizer :tracking :scenario-id])))
    (is (= [scenario-id] (get-in state [:portfolio :optimizer :scenario-index :ordered-ids])))
    (is (= scenario-id
           (get-in state [:portfolio :optimizer :scenario-index :by-id scenario-id :id])))
    (is (= :recommendation
           (get-in state [:portfolio-ui :optimizer :results-tab])))
    (is (= ["perp:BTC" "perp:ETH" "spot:PURR"]
           (mapv :key (get-in state [:asset-selector :markets]))))
    (is (= :ready
           (get-in state [:portfolio :optimizer :last-successful-run
                          :result :rebalance-preview :status])))))
