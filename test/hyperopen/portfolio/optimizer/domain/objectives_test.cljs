(ns hyperopen.portfolio.optimizer.domain.objectives-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(deftest minimum-variance-builds-single-qp-with-net-equality-and-bounds-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :constraints {:long-only? true
                                :max-asset-weight 0.8}})
        plan (objectives/build-solver-plan
              {:objective {:kind :minimum-variance}
               :instrument-ids ["A" "B"]
               :expected-returns [0.1 0.2]
               :covariance [[1 0.2]
                            [0.2 2]]
               :encoded-constraints encoded})]
    (is (= :single-qp (:strategy plan)))
    (is (= :minimum-variance (get-in plan [:problems 0 :objective-kind])))
    (is (= [0 0] (get-in plan [:problems 0 :linear])))
    (is (= [{:code :net-exposure
             :coefficients [1 1]
             :target 1}]
           (get-in plan [:problems 0 :equalities])))
    (is (= [0 0] (get-in plan [:problems 0 :lower-bounds])))
    (is (= [0.8 0.8] (get-in plan [:problems 0 :upper-bounds])))))

(deftest target-return-adds-return-floor-and-reports-infeasible-targets-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :constraints {:long-only? true
                                :max-asset-weight 0.7}})
        feasible (objectives/build-solver-plan
                  {:objective {:kind :target-return
                               :target-return 0.16}
                   :instrument-ids ["A" "B"]
                   :expected-returns [0.1 0.2]
                   :covariance [[1 0]
                                [0 1]]
                   :encoded-constraints encoded})
        infeasible (objectives/build-solver-plan
                    {:objective {:kind :target-return
                                 :target-return 0.22}
                     :instrument-ids ["A" "B"]
                     :expected-returns [0.1 0.2]
                     :covariance [[1 0]
                                  [0 1]]
                     :encoded-constraints encoded})]
    (is (= :single-qp (:strategy feasible)))
    (is (= [{:code :target-return
             :coefficients [0.1 0.2]
             :lower 0.16}]
           (get-in feasible [:problems 0 :inequalities])))
    (is (= :infeasible (:status infeasible)))
    (is (= :target-return-above-feasible-maximum (:reason infeasible)))
    (is (near? 0.17 (get-in infeasible [:details :max-return])))))

(deftest frontier-objectives-build-tilt-grid-and-selection-objective-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :constraints {:long-only? true}})
        plan (objectives/build-solver-plan
              {:objective {:kind :target-volatility
                           :target-volatility 0.12}
               :instrument-ids ["A" "B"]
               :expected-returns [0.1 0.2]
               :covariance [[1 0]
                            [0 1]]
               :encoded-constraints encoded
               :return-tilts [0 0.5 1]})]
    (is (= :frontier-sweep (:strategy plan)))
    (is (= {:kind :target-volatility
            :target-volatility 0.12}
           (:selection-objective plan)))
    (is (= [0 0.5 1] (mapv :return-tilt (:problems plan))))
    (is (= [[0 0] [-0.05 -0.1] [-0.1 -0.2]]
           (mapv :linear (:problems plan))))))

(deftest solver-plan-propagates-constraint-presolve-infeasibility-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :constraints {:long-only? true
                                :max-asset-weight 0.4}})
        plan (objectives/build-solver-plan
              {:objective {:kind :minimum-variance}
               :instrument-ids ["A" "B"]
               :expected-returns [0.1 0.2]
               :covariance [[1 0]
                            [0 1]]
               :encoded-constraints encoded})]
    (is (= :infeasible (:status plan)))
    (is (= :constraint-presolve (:reason plan)))
    (is (= (:violations encoded) (get-in plan [:details :violations])))))

(deftest solver-plan-encodes-turnover-as-l1-constraint-with-current-weights-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :current-weights {"A" 0.3
                                    "B" -0.1}
                  :constraints {:long-only? false
                                :max-turnover 0.25}})
        plan (objectives/build-solver-plan
              {:objective {:kind :minimum-variance}
               :instrument-ids ["A" "B"]
               :expected-returns [0.1 0.2]
               :covariance [[1 0]
                            [0 1]]
               :encoded-constraints encoded})]
    (is (= [{:code :turnover
             :max 0.5
             :current-weights [0.3 -0.1]
             :requires-split-variables? true}]
           (get-in plan [:problems 0 :l1-constraints])))))
