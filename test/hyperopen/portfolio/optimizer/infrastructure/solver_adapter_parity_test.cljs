(ns hyperopen.portfolio.optimizer.infrastructure.solver-adapter-parity-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]
            [hyperopen.portfolio.optimizer.infrastructure.solver-adapter :as solver-adapter]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0001))

(defn- weights-near?
  [expected actual]
  (and (= (count expected) (count actual))
       (every? true? (map near? expected actual))))

(defn- gross
  [weights]
  (reduce + 0 (map js/Math.abs weights)))

(defn- turnover-distance
  [current target]
  (reduce + 0 (map (fn [current-weight target-weight]
                     (js/Math.abs (- target-weight current-weight)))
                   current
                   target)))

(defn- first-problem
  [{:keys [universe current-weights constraints objective expected-returns covariance]}]
  (let [encoded (constraints/encode-constraints
                 {:universe universe
                  :current-weights current-weights
                  :constraints constraints})
        plan (objectives/build-solver-plan
              {:objective objective
               :instrument-ids (:instrument-ids encoded)
               :expected-returns expected-returns
               :covariance covariance
               :encoded-constraints encoded})]
    (assoc (first (:problems plan))
           :encoded-constraints encoded
           :solver-plan plan)))

(defn- assert-parity!
  [fixture problem expected]
  (let [quadprog-result (solver-adapter/solve-with-quadprog problem)]
    (-> (solver-adapter/solve-with-osqp problem)
        (.then (fn [osqp-result]
                 (let [quadprog-weights (:weights quadprog-result)
                       osqp-weights (:weights osqp-result)]
                   (is (= :solved (:status quadprog-result))
                       (str fixture " quadprog status"))
                   (is (= :solved (:status osqp-result))
                       (str fixture " OSQP status"))
                   (is (weights-near? expected quadprog-weights)
                       (str fixture " quadprog weights"))
                   (is (weights-near? expected osqp-weights)
                       (str fixture " OSQP weights"))
                   (is (weights-near? quadprog-weights osqp-weights)
                       (str fixture " solver parity"))))))))

(deftest signed-gross-and-net-exposure-fixture-matches-between-solvers-test
  (async done
    (let [problem (first-problem
                   {:universe [{:instrument-id "perp:A"}
                               {:instrument-id "perp:B"}]
                    :constraints {:long-only? false
                                  :gross-leverage 1.2
                                  :net-exposure {:min 0
                                                 :max 0}}
                    :objective {:kind :minimum-variance}
                    :expected-returns [1 -1]
                    :covariance [[1 0]
                                 [0 1]]})
          problem* (assoc problem :linear [-1 1])]
      (-> (assert-parity! "signed gross/net" problem* [0.6 -0.6])
          (.then (fn []
                   (is (near? 1.2 (gross [0.6 -0.6])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "signed gross/net parity failed: " err))
                    (done)))))))

(deftest turnover-cap-fixture-matches-between-solvers-test
  (async done
    (let [problem (first-problem
                   {:universe [{:instrument-id "perp:A"}
                               {:instrument-id "perp:B"}]
                    :current-weights {"perp:A" 0
                                      "perp:B" 0}
                    :constraints {:long-only? false
                                  :max-turnover 0.25
                                  :net-exposure {:min 0
                                                 :max 0}}
                    :objective {:kind :minimum-variance}
                    :expected-returns [1 -1]
                    :covariance [[1 0]
                                 [0 1]]})
          problem* (assoc problem :linear [-1 1])]
      (-> (assert-parity! "turnover cap" problem* [0.25 -0.25])
          (.then (fn []
                   (is (near? 0.5 (turnover-distance [0 0] [0.25 -0.25])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "turnover cap parity failed: " err))
                    (done)))))))

(deftest held-position-lock-fixture-matches-between-solvers-test
  (async done
    (let [problem (first-problem
                   {:universe [{:instrument-id "spot:A"}
                               {:instrument-id "spot:B"}]
                    :current-weights {"spot:A" 0.25}
                    :constraints {:long-only? true
                                  :held-position-locks #{"spot:A"}}
                    :objective {:kind :minimum-variance}
                    :expected-returns [0 0]
                    :covariance [[1 0]
                                 [0 1]]})]
      (-> (assert-parity! "held lock" problem [0.25 0.75])
          (.then done)
          (.catch (fn [err]
                    (is false (str "held lock parity failed: " err))
                    (done)))))))

(deftest per-perp-cap-fixture-matches-between-solvers-test
  (async done
    (let [problem {:kind :quadratic-program
                   :objective-kind :return-tilted
                   :instrument-ids ["perp:A" "perp:B"]
                   :quadratic [[1 0]
                               [0 1]]
                   :linear [0 -10]
                   :equalities [{:code :net-exposure
                                 :coefficients [1 1]
                                 :target 1}]
                   :inequalities []
                   :l1-constraints []
                   :lower-bounds [0 0]
                   :upper-bounds [1 0.25]}]
      (-> (assert-parity! "per-perp cap" problem [0.75 0.25])
          (.then done)
          (.catch (fn [err]
                    (is false (str "per-perp cap parity failed: " err))
                    (done)))))))

(deftest infeasible-target-return-fixture-fails-before-solver-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "spot:A"}
                             {:instrument-id "spot:B"}]
                  :constraints {:long-only? true
                                :max-asset-weight 0.7}})
        plan (objectives/build-solver-plan
              {:objective {:kind :target-return
                           :target-return 0.22}
               :instrument-ids (:instrument-ids encoded)
               :expected-returns [0.1 0.2]
               :covariance [[1 0]
                            [0 1]]
               :encoded-constraints encoded})]
    (is (= :infeasible (:status plan)))
    (is (= :target-return-above-feasible-maximum (:reason plan)))
    (is (near? 0.17 (get-in plan [:details :max-return])))))
