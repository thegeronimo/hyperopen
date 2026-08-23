(ns hyperopen.portfolio.optimizer.application.engine.transportable-results-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.engine :as engine]
            [hyperopen.portfolio.optimizer.application.engine.solve :as solve]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]))

(deftest drops-only-the-quadratic-from-the-attached-problem-test
  ;; target-selection re-validates returned weights against the problem, but it
  ;; reads the bounds and constraint rows -- never the covariance. Everything
  ;; except :quadratic has to survive or that re-check breaks.
  (let [results [{:status :solved
                  :solver :osqp
                  :weights [0.5 0.5]
                  :problem {:instrument-ids ["perp:BTC" "perp:ETH"]
                            :quadratic [[1 0] [0 1]]
                            :linear [-1 -1]
                            :lower-bounds [0 0]
                            :upper-bounds [1 1]
                            :equalities [{:coefficients [1 1] :target 1}]
                            :inequalities []
                            :return-tilt 0.25}}]
        [out] (solve/transportable-solver-results results)]
    (is (nil? (get-in out [:problem :quadratic])))
    (is (= ["perp:BTC" "perp:ETH"] (get-in out [:problem :instrument-ids])))
    (is (= [0 0] (get-in out [:problem :lower-bounds])))
    (is (= [1 1] (get-in out [:problem :upper-bounds])))
    (is (= [{:coefficients [1 1] :target 1}] (get-in out [:problem :equalities])))
    (is (= 0.25 (get-in out [:problem :return-tilt])))
    (is (= [0.5 0.5] (:weights out)) "the result itself is untouched")))

(deftest tolerates-results-with-no-attached-problem-test
  (is (= [{:status :error}] (solve/transportable-solver-results [{:status :error}])))
  (is (= [] (solve/transportable-solver-results [])))
  (is (= [] (solve/transportable-solver-results nil))))

(deftest engine-result-ships-no-covariance-inside-solver-results-test
  ;; The frontier sweep attaches the same covariance to every point, so this is
  ;; the difference between an 8 MB and a 0.4 MB result payload at N=100.
  (let [calls (atom 0)
        result (engine/run-optimization
                (fixtures/sample-engine-request)
                {:solve-problem (fn [problem]
                                  (swap! calls inc)
                                  (let [n (count (:instrument-ids problem))]
                                    {:status :solved
                                     :solver :osqp
                                     :weights (vec (repeat n (/ 1.0 n)))}))})
        solver-results (:solver-results result)]
    (is (= :solved (:status result)))
    (is (pos? @calls))
    (is (seq solver-results))
    (is (every? #(nil? (get-in % [:problem :quadratic])) solver-results))
    (is (every? #(seq (get-in % [:problem :instrument-ids])) solver-results)
        "the rest of the problem still travels")))
