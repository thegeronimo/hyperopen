(ns hyperopen.portfolio.optimizer.domain.black-litterman-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.black-litterman :as black-litterman]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(deftest implied-equilibrium-returns-use-risk-aversion-covariance-and-prior-weights-test
  (let [pi (black-litterman/implied-equilibrium-returns
            {:risk-aversion 2
             :covariance [[1 0.5]
                          [0.5 2]]
             :prior-weights [0.6 0.4]})]
    (is (= [1.6 2.2] pi))))

(deftest posterior-returns-combine-prior-and-views-test
  (let [posterior (black-litterman/posterior-returns
                   {:instrument-ids ["A" "B"]
                    :covariance [[1 0]
                                 [0 1]]
                    :prior-weights [0.6 0.4]
                    :risk-aversion 1
                    :tau 1
                    :views [{:weights {"A" 1
                                       "B" -1}
                             :return 0.1
                             :confidence-variance 1}]})]
    (is (= :black-litterman (:model posterior)))
    (is (near? 0.5666666667 (get-in posterior [:expected-returns-by-instrument "A"])))
    (is (near? 0.4333333333 (get-in posterior [:expected-returns-by-instrument "B"])))
    (is (= {:prior-source :market-cap
            :view-count 1
            :tau 1}
           (select-keys (:diagnostics posterior) [:prior-source :view-count :tau])))))
