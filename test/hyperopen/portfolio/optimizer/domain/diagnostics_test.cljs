(ns hyperopen.portfolio.optimizer.domain.diagnostics-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.diagnostics :as diagnostics]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(deftest portfolio-diagnostics-compute-exposure-concentration-turnover-and-binding-constraints-test
  (let [result (diagnostics/portfolio-diagnostics
                {:instrument-ids ["A" "B"]
                 :current-weights [0.6 0.4]
                 :target-weights [0.5 0.5]
                 :expected-returns [0.1 0.2]
                 :lower-bounds [0 0]
                 :upper-bounds [0.5 0.8]
                 :covariance [[1 0]
                              [0 4]]})]
    (is (near? 1 (:gross-exposure result)))
    (is (near? 1 (:net-exposure result)))
    (is (near? 2 (:effective-n result)))
    (is (near? 0.5 (:max-weight result)))
    (is (near? 0.1 (:turnover result)))
    (is (= [{:instrument-id "A"
             :constraint :upper-bound
             :weight 0.5
             :bound 0.5}]
           (:binding-constraints result)))
    (is (= :ok (get-in result [:covariance-conditioning :status])))
    (is (contains? (:weight-sensitivity-by-instrument result) "A"))))

(deftest weight-sensitivity-perturbs-top-weights-and-reports-return-range-test
  (let [result (diagnostics/weight-sensitivity
                {:instrument-ids ["A" "B"]
                 :weights [0.7 0.3]
                 :expected-returns [0.1 0.2]
                 :shock 0.01
                 :top-n 1})]
    (is (= ["A"] (mapv :instrument-id result)))
    (is (near? 0.13 (:base-expected-return (first result))))
    (is (near? 0.1303030303 (:down-expected-return (first result))))
    (is (near? 0.1297029703 (:up-expected-return (first result))))
    (is (near? 0.01 (:shock (first result))))))
