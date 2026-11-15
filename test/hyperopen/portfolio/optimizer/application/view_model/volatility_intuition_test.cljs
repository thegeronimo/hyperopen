(ns hyperopen.portfolio.optimizer.application.view-model.volatility-intuition-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.view-model.volatility-intuition
             :as vm]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]))

(defn- near?
  ([expected actual] (near? expected actual 1e-6))
  ([expected actual tolerance]
   (and (number? actual)
        (< (js/Math.abs (- expected actual)) tolerance))))

(deftest card-model-derives-both-portfolios-on-the-repo-basis-test
  (let [result (fixtures/sample-solved-result)
        {:keys [basis target current change radio-name
                target-radio-id current-radio-id]} (vm/card-model result)]
    (is (= 365 (:periods-per-year basis)))
    (is (near? 0.0146559 (:daily target) 1e-6))
    (is (near? 0.0387758 (:weekly target) 1e-6))
    (is (near? 0.0802735 (:monthly target) 1e-6))
    (is (near? 0.0125622 (:daily current) 1e-6))
    (is (every? pos? (vals change)))
    (is (= "optimizer-volatility-intuition-5000" radio-name))
    (is (= "optimizer-volatility-intuition-5000-target" target-radio-id))
    (is (= "optimizer-volatility-intuition-5000-current" current-radio-id))))

(deftest card-model-nil-without-usable-target-volatility-test
  (is (nil? (vm/card-model (fixtures/sample-solved-result {:volatility nil}))))
  (is (nil? (vm/card-model (fixtures/sample-solved-result {:volatility js/NaN}))))
  (is (nil? (vm/card-model {}))))

(deftest leverage-risk-model-gates-on-gross-or-volatility-test
  (is (nil? (vm/leverage-risk-model (fixtures/sample-solved-result))))
  (is (some? (vm/leverage-risk-model
              (fixtures/sample-solved-result
               {:diagnostics {:gross-exposure 2.0}}))))
  (is (some? (vm/leverage-risk-model
              (fixtures/sample-solved-result {:volatility 1.0}))))
  (is (nil? (vm/leverage-risk-model
             (fixtures/sample-solved-result
              {:diagnostics {:gross-exposure 1.9}
               :volatility 0.99})))))

(deftest leverage-risk-model-carries-dollar-outcomes-test
  (let [{:keys [target current capital-usd gross-exposure]}
        (vm/leverage-risk-model
         (fixtures/sample-solved-result {:diagnostics {:gross-exposure 2.5}}))]
    (is (= 100000 capital-usd))
    (is (near? 2.5 gross-exposure 1e-9))
    (is (near? (* 100000
                  (js/Math.exp (- (js/Math.log 1.16) (* 0.5 0.28 0.28))))
               (get-in target [:dollar :median-usd])
               1e-3))
    (is (some? (get-in current [:dollar :median-usd])))
    (is (< (get-in target [:dollar :p5-usd])
           (get-in target [:dollar :median-usd])
           (get-in target [:dollar :p95-usd])))))

(deftest leverage-risk-model-degrades-without-capital-or-current-test
  (let [no-capital (vm/leverage-risk-model
                    (fixtures/sample-solved-result
                     {:diagnostics {:gross-exposure 2.5}
                      :rebalance-preview {:capital-usd nil}}))
        no-current (vm/leverage-risk-model
                    (fixtures/sample-solved-result
                     {:diagnostics {:gross-exposure 2.5}
                      :current-volatility nil}))]
    (is (some? (:target no-capital)))
    (is (nil? (:capital-usd no-capital)))
    (is (nil? (get-in no-capital [:target :dollar])))
    (is (some? (:target no-current)))
    (is (nil? (:current no-current)))))

(deftest leverage-risk-model-nil-when-target-outcome-invalid-test
  (is (nil? (vm/leverage-risk-model
             (fixtures/sample-solved-result
              {:diagnostics {:gross-exposure 2.5}
               :expected-return -1.2})))))

(deftest horizons-helper-matches-card-scaling-test
  (let [{:keys [daily weekly monthly]} (vm/horizons 4.1182)]
    (is (near? 0.2155565 daily 1e-5))
    (is (near? 0.5703081 weekly 1e-5))
    (is (near? 1.1806513 monthly 1e-5)))
  (is (nil? (vm/horizons nil)))
  (is (nil? (vm/horizons -0.3))))
