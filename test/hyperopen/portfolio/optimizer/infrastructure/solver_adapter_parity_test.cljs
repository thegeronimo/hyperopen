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

(defn- plan-rejection-message
  [plan]
  (str "build-solver-plan returned no solvable problem: status "
       (pr-str (:status plan))
       (when-let [reason (:reason plan)]
         (str ", reason " (pr-str reason)))
       (when-let [codes (seq (map :code (get-in plan [:details :violations])))]
         (str ", violations " (pr-str (vec codes))))
       ". Every parity fixture must stay non-degenerate: an :ok plan carrying at "
       "least one problem. Fix the fixture's constraints rather than the assertion."))

(defn- first-problem
  "The fixture's first solver problem, augmented with its encoding and plan.

  NEVER throws and never returns nil. When presolve rejects the plan this
  returns a MARKED pseudo-problem {::plan-error \"...\"} that assert-parity!
  turns into a single readable failure.

  Why the guard exists: the unguarded (assoc (first (:problems plan)) ...) made
  a problem with no :quadratic out of a rejected plan, and solve-with-quadprog
  then dereferenced .length on nil. That throw happened in the SYNCHRONOUS part
  of an (async done) body, which cljs.test does not wrap in try/catch, so done
  never fired and the runner abandoned the rest of the suite -- 392 of 898
  namespaces, no \"Ran N tests\" summary, exit 0. One bad fixture must fail one
  test, not decapitate the run."
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
               :encoded-constraints encoded})
        problem (first (:problems plan))]
    (assoc (if (map? problem)
             problem
             {::plan-error (plan-rejection-message plan)})
           :encoded-constraints encoded
           :solver-plan plan)))

(defn- assert-parity!
  [fixture problem expected]
  (if-let [rejection (::plan-error problem)]
    ;; Resolve rather than throw: the caller's `done` must still fire.
    (do (is false (str fixture " fixture was rejected before the solver: " rejection))
        (js/Promise.resolve))
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
                         (str fixture " solver parity")))))))))

(deftest signed-gross-and-net-exposure-fixture-matches-between-solvers-test
  (async done
    (let [problem (first-problem
                   ;; :position-side pins each asset to one side, which is what
                   ;; makes gross linear (gross-floor-signs -> [1 -1]) so the
                   ;; :gross-floor survives encoding. Both are required: minimum
                   ;; variance has no budget row, so with only ceilings w = 0 is
                   ;; globally optimal at zero variance and build-solver-plan
                   ;; rejects the request as :objective-collapses-to-cash. The
                   ;; 0.5x floor is slack at the asserted 1.2x gross, so it fixes
                   ;; the degeneracy without moving the optimum.
                   {:universe [{:instrument-id "perp:A"
                                :instrument-type :perp
                                :position-side :long}
                               {:instrument-id "perp:B"
                                :instrument-type :perp
                                :position-side :short}]
                    :constraints {:long-only? false
                                  :gross-leverage 1.2
                                  :gross-floor 0.5
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
                   ;; Same non-degeneracy pairing as the fixture above: a flat
                   ;; current book cannot forbid cash through the turnover row,
                   ;; so single-signed sides plus a 0.2x gross floor are what keep
                   ;; this minimum-variance request off the all-cash optimum. The
                   ;; floor is slack at the asserted 0.5x gross.
                   {:universe [{:instrument-id "perp:A"
                                :instrument-type :perp
                                :position-side :long}
                               {:instrument-id "perp:B"
                                :instrument-type :perp
                                :position-side :short}]
                    :current-weights {"perp:A" 0
                                      "perp:B" 0}
                    :constraints {:long-only? false
                                  :max-turnover 0.25
                                  :gross-floor 0.2
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

(deftest one-thirty-thirty-fixture-can-use-shortable-perp-test
  (async done
    (let [problem (first-problem
                   {:universe [{:instrument-id "perp:A"
                                :instrument-type :perp}
                               {:instrument-id "perp:B"
                                :instrument-type :perp}
                               {:instrument-id "perp:C"
                                :instrument-type :perp}]
                    :constraints {:long-only? false
                                  :max-long-weight 1.3
                                  :max-short-weight 0.3
                                  :gross-leverage 1.6
                                  :net-exposure {:min 1.0
                                                 :max 1.0}}
                    :objective {:kind :minimum-variance}
                    :expected-returns [1 0 -1]
                    :covariance [[1 0 0]
                                 [0 1 0]
                                 [0 0 1]]})
          problem* (assoc problem :linear [-10 0 10])]
      (-> (assert-parity! "130/30" problem* [1.3 0 -0.3])
          (.then (fn []
                   (is (near? 1.6 (gross [1.3 0 -0.3])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "130/30 parity failed: " err))
                    (done)))))))

(deftest held-position-lock-fixture-matches-between-solvers-test
  (async done
    (let [problem (first-problem
                   {:universe [{:instrument-id "spot:A"}
                               {:instrument-id "spot:B"}]
                    :current-weights {"spot:A" 0.25}
                    :constraints {:long-only? true
                                  :include-spot? true
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
                                :include-spot? true
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

(deftest gross-floor-holds-leverage-instead-of-delevering-test
  ;; Regression for the "min-vol delevers my book" report: with a gross FLOOR,
  ;; minimum-variance holds gross at the floor (preserves leverage) instead of
  ;; collapsing toward the net floor. Verified through both solver paths.
  (async done
    (let [base {:universe [{:instrument-id "perp:A" :instrument-type :perp
                            :position-side :long}
                           {:instrument-id "perp:B" :instrument-type :perp
                            :position-side :short}]
                :constraints {:long-only? false
                              :gross-leverage 2.0
                              :net-exposure {:min 0.2 :max 0.3}
                              :max-asset-weight 1.0}
                :objective {:kind :minimum-variance}
                :expected-returns [0.0 0.0]
                :covariance [[1 0]
                             [0 1]]}
          no-floor (first-problem base)
          with-floor (first-problem (assoc-in base [:constraints :gross-floor] 1.0))]
      ;; This test solves directly instead of going through assert-parity!, so it
      ;; needs its own guard: calling the adapter with a rejected plan would throw
      ;; synchronously inside this (async done) body and abandon the whole run.
      (if-let [rejection (some ::plan-error [no-floor with-floor])]
        (do (is false (str "gross floor fixture was rejected before the solver: " rejection))
            (done))
        (let [no-floor-qp (solver-adapter/solve-with-quadprog no-floor)
              with-floor-qp (solver-adapter/solve-with-quadprog with-floor)]
          ;; control: without a floor, min-vol delevers to ~the net floor
          (is (= :solved (:status no-floor-qp)))
          (is (near? 0.2 (gross (:weights no-floor-qp))) "no-floor delevers to net floor")
          ;; with the floor, gross is held at the floor (leverage preserved)
          (is (= :solved (:status with-floor-qp)))
          (is (near? 1.0 (gross (:weights with-floor-qp))) "floor preserves gross (quadprog)")
          ;; same result through OSQP
          (-> (solver-adapter/solve-with-osqp with-floor)
              (.then (fn [osqp-res]
                       (is (= :solved (:status osqp-res)))
                       (is (near? 1.0 (gross (:weights osqp-res))) "floor preserves gross (OSQP)")
                       (done)))))))))
