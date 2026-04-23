(ns hyperopen.portfolio.optimizer.infrastructure.solver-adapter-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.infrastructure.solver-adapter :as solver-adapter]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.000001))

(def min-variance-problem
  {:kind :quadratic-program
   :objective-kind :minimum-variance
   :instrument-ids ["A" "B"]
   :quadratic [[1 0]
               [0 1]]
   :linear [0 0]
   :equalities [{:code :net-exposure
                 :coefficients [1 1]
                 :target 1}]
   :inequalities []
   :l1-constraints []
   :lower-bounds [0 0]
   :upper-bounds [1 1]})

(deftest quadprog-adapter-solves-long-only-minimum-variance-test
  (let [result (solver-adapter/solve-with-quadprog min-variance-problem)]
    (is (= :solved (:status result)))
    (is (= :quadprog (:solver result)))
    (is (near? 0.5 (first (:weights result))))
    (is (near? 0.5 (second (:weights result))))))

(deftest quadprog-adapter-enforces-target-return-inequality-test
  (let [result (solver-adapter/solve-with-quadprog
                (assoc min-variance-problem
                       :linear [0 0]
                       :inequalities [{:code :target-return
                                       :coefficients [0.1 0.2]
                                       :lower 0.16}]))]
    (is (= :solved (:status result)))
    (is (near? 0.4 (first (:weights result))))
    (is (near? 0.6 (second (:weights result))))))

(def signed-gross-problem
  {:kind :quadratic-program
   :objective-kind :return-tilted
   :instrument-ids ["A" "B"]
   :quadratic [[1 0]
               [0 1]]
   :linear [-1 1]
   :equalities [{:code :net-exposure
                 :coefficients [1 1]
                 :target 0}]
   :inequalities []
   :l1-constraints [{:code :gross-exposure
                     :max 1
                     :requires-split-variables? true}]
   :lower-bounds [-1 -1]
   :upper-bounds [1 1]})

(def turnover-capped-problem
  (assoc signed-gross-problem
         :l1-constraints [{:code :turnover
                           :max 0.5
                           :current-weights [0 0]
                           :requires-split-variables? true}]))

(deftest quadprog-adapter-solves-signed-gross-exposure-with-split-variables-test
  (let [result (solver-adapter/solve-with-quadprog
                signed-gross-problem)]
    (is (= :solved (:status result)))
    (is (= :quadprog (:solver result)))
    (is (near? 0.5 (first (:weights result))))
    (is (near? -0.5 (second (:weights result))))))

(deftest osqp-adapter-solves-long-only-minimum-variance-test
  (async done
    (-> (solver-adapter/solve-with-osqp min-variance-problem)
        (.then (fn [result]
                 (is (= :solved (:status result)))
                 (is (= :osqp (:solver result)))
                 (is (near? 0.5 (first (:weights result))))
                 (is (near? 0.5 (second (:weights result))))
                 (done)))
        (.catch (fn [err]
                  (is false (str "OSQP solve failed: " err))
                  (done))))))

(deftest osqp-adapter-solves-signed-gross-exposure-with-split-variables-test
  (async done
    (-> (solver-adapter/solve-with-osqp signed-gross-problem)
        (.then (fn [result]
                 (is (= :solved (:status result)))
                 (is (= :osqp (:solver result)))
                 (is (near? 0.5 (first (:weights result))))
                 (is (near? -0.5 (second (:weights result))))
                 (done)))
        (.catch (fn [err]
                  (is false (str "OSQP split-variable solve failed: " err))
                  (done))))))

(deftest quadprog-adapter-solves-turnover-cap-with-split-variables-test
  (let [result (solver-adapter/solve-with-quadprog
                turnover-capped-problem)]
    (is (= :solved (:status result)))
    (is (near? 0.25 (first (:weights result))))
    (is (near? -0.25 (second (:weights result))))))

(deftest adapter-rejects-turnover-cap-without-current-weights-test
  (let [result (solver-adapter/solve-with-quadprog
                (assoc signed-gross-problem
                       :l1-constraints [{:code :turnover
                                         :max 0.5
                                         :requires-split-variables? true}]))]
    (is (= :unsupported (:status result)))
    (is (= :invalid-l1-constraints (:reason result)))))

(deftest osqp-adapter-solves-turnover-cap-with-split-variables-test
  (async done
    (-> (solver-adapter/solve-with-osqp turnover-capped-problem)
        (.then (fn [result]
                 (is (= :solved (:status result)))
                 (is (near? 0.25 (first (:weights result))))
                 (is (near? -0.25 (second (:weights result))))
                 (done)))
        (.catch (fn [err]
                  (is false (str "OSQP turnover solve failed: " err))
                  (done))))))
