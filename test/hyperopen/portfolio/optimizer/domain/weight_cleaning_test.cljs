(ns hyperopen.portfolio.optimizer.domain.weight-cleaning-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.weight-cleaning :as weight-cleaning]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.000001))

(deftest clean-weights-drops-dust-and-renormalizes-remaining-weights-test
  (let [result (weight-cleaning/clean-weights
                {:instrument-ids ["A" "B" "C"]
                 :weights [0.499 0.0004 0.5006]
                 :dust-threshold 0.001})]
    (is (= ["A" "C"] (:instrument-ids result)))
    (is (near? 0.4992 (first (:weights result))))
    (is (near? 0.5008 (second (:weights result))))
    (is (= [{:instrument-id "B"
             :weight 0.0004
             :reason :dust-threshold}]
           (:dropped result)))))

(deftest clean-weights-preserves-signed-zero-sum-when-all-weights-are-dust-test
  (let [result (weight-cleaning/clean-weights
                {:instrument-ids ["A" "B"]
                 :weights [0.0001 -0.0001]
                 :dust-threshold 0.001})]
    (is (= [] (:instrument-ids result)))
    (is (= [] (:weights result)))
    (is (= 2 (count (:dropped result))))))

(deftest clean-weights-does-not-renormalize-signed-portfolios-by-default-test
  (let [result (weight-cleaning/clean-weights
                {:instrument-ids ["A" "B" "C"]
                 :weights [0.6 -0.4 0.0001]
                 :long-only? false
                 :dust-threshold 0.001})]
    (is (= ["A" "B"] (:instrument-ids result)))
    (is (= [0.6 -0.4] (:weights result)))
    (is (near? 0.2 (reduce + 0 (:weights result))))))
