(ns hyperopen.portfolio.optimizer.domain.closed-form-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.closed-form :as closed-form]
            [hyperopen.portfolio.optimizer.domain.math :as math]))

(def ^:private neg-inf js/Number.NEGATIVE_INFINITY)
(def ^:private pos-inf js/Number.POSITIVE_INFINITY)

(defn- near?
  ([expected actual]
   (near? expected actual 1.0e-7))
  ([expected actual tolerance]
   (and (number? actual)
        (< (js/Math.abs (- expected actual)) tolerance))))

(def ^:private diagonal-covariance
  [[0.04 0]
   [0 0.09]])

(def ^:private signed-covariance
  [[0.01 0.02]
   [0.02 0.09]])

(defn- unbounded-encoded
  "Encoded constraints with no active restriction beyond the sum=1 equality:
  unbounded box, no long-only/gross/turnover/locks."
  ([] (unbounded-encoded 2))
  ([n]
   {:status :ok
    :long-only? false
    :net-target 1
    :instrument-ids (vec (take n ["A" "B" "C" "D"]))
    :current-weights (vec (repeat n 0))
    :lower-bounds (vec (repeat n neg-inf))
    :upper-bounds (vec (repeat n pos-inf))
    :locked-weights []}))

(defn- request
  ([objective] (request objective {}))
  ([objective overrides]
   (merge {:objective objective
           :instrument-ids ["A" "B"]
           :expected-returns [0.1 0.2]
           :covariance diagonal-covariance
           :encoded-constraints (unbounded-encoded 2)}
          overrides)))

(defn- with-encoded
  "Build a request with the base unbounded encoded constraints overlaid by
  encoded-overrides, so a test can add a single restriction in isolation."
  [objective encoded-overrides]
  (request objective
           {:encoded-constraints (merge (unbounded-encoded 2) encoded-overrides)}))

(deftest minimum-variance-diagonal-covariance-matches-inverse-variance-weights-test
  (let [result (closed-form/solve-portfolio (request {:kind :minimum-variance}))]
    (is (= :solved (:status result)))
    (is (= :closed-form (:solver result)))
    (is (= 0 (:iterations result)))
    (is (= 0 (:elapsed-ms result)))
    (is (near? 0.6923076923 (get-in result [:weights 0])))
    (is (near? 0.3076923077 (get-in result [:weights 1])))
    (is (near? (/ 9 325) (:objective-value result)))))

(deftest accepted-candidate-emits-explicit-accepted-diagnostics-test
  (let [result (closed-form/solve-portfolio (request {:kind :minimum-variance}))]
    (is (= {:strategy :closed-form
            :closed-form? true
            :closed-form-accepted? true
            :closed-form-post-validated? true
            :fallback? false
            :objective-kind :minimum-variance
            :eligibility-reason nil}
           (:diagnostics result)))))

(deftest minimum-variance-correlated-covariance-solves-the-full-system-test
  (let [result (closed-form/solve-portfolio
                (request {:kind :minimum-variance}
                         {:covariance [[0.04 0.01]
                                       [0.01 0.09]]}))]
    (is (= :solved (:status result)))
    (is (near? (/ 8 11) (get-in result [:weights 0])))
    (is (near? (/ 3 11) (get-in result [:weights 1])))))

(deftest target-return-binding-floor-pins-expected-return-test
  (let [result (closed-form/solve-portfolio
                (request {:kind :target-return :target-return 0.16}))]
    (is (= :solved (:status result)))
    (is (near? 0.4 (get-in result [:weights 0])))
    (is (near? 0.6 (get-in result [:weights 1])))
    (is (near? 0.16 (math/portfolio-return (:weights result) [0.1 0.2])))))

(deftest target-return-below-gmv-return-keeps-gmv-weights-test
  (let [result (closed-form/solve-portfolio
                (request {:kind :target-return :target-return 0.12}))]
    (is (= :solved (:status result)))
    (is (near? 0.6923076923 (get-in result [:weights 0])))
    (is (near? 0.3076923077 (get-in result [:weights 1])))))

(deftest max-sharpe-diagonal-covariance-matches-tangency-weights-test
  (let [result (closed-form/solve-portfolio (request {:kind :max-sharpe}))]
    (is (= :solved (:status result)))
    (is (near? 0.5294117647 (get-in result [:weights 0])))
    (is (near? 0.4705882353 (get-in result [:weights 1])))
    (is (near? 1 (reduce + 0 (:weights result))))))

(deftest target-volatility-above-gmv-hits-requested-volatility-test
  (let [result (closed-form/solve-portfolio
                (request {:kind :target-volatility :target-volatility 0.2}))
        weights (:weights result)]
    (is (= :solved (:status result)))
    (is (near? (/ 5 13) (get-in result [:weights 0])))
    (is (near? (/ 8 13) (get-in result [:weights 1])))
    (is (near? 0.2 (js/Math.sqrt (math/portfolio-variance weights diagonal-covariance))))
    (is (near? (/ 21 130) (math/portfolio-return weights [0.1 0.2])))))

(deftest target-volatility-at-gmv-volatility-resolves-to-gmv-weights-test
  (let [sigma (js/Math.sqrt (/ 9 325))
        result (closed-form/solve-portfolio
                (request {:kind :target-volatility :target-volatility sigma}))]
    (is (= :solved (:status result)))
    (is (near? 0.6923076923 (get-in result [:weights 0])))
    (is (near? 0.3076923077 (get-in result [:weights 1])))))

(deftest additional-constraints-accepted-when-candidate-already-satisfies-them-test
  (is (= {:eligible? true :net-target 1}
         (closed-form/eligible?
          (with-encoded {:kind :minimum-variance}
                        {:long-only? true
                         :lower-bounds [0 0]
                         :upper-bounds [1 1]}))))
  (is (:eligible? (closed-form/eligible?
                   (with-encoded {:kind :minimum-variance}
                                 {:upper-bounds [1 1]}))))
  (is (:eligible? (closed-form/eligible?
                   (with-encoded {:kind :minimum-variance}
                                 {:gross-exposure {:max 2}}))))
  (is (:eligible? (closed-form/eligible?
                   (with-encoded {:kind :minimum-variance}
                                 {:current-weights [0.69 0.31]
                                  :max-turnover 0.5}))))
  (is (:eligible? (closed-form/eligible?
                   (with-encoded {:kind :minimum-variance}
                                 {:rebalance-tolerance 0.01}))))
  (is (:eligible? (closed-form/eligible?
                   (with-encoded {:kind :minimum-variance}
                                 {:locked-weights [{:instrument-id "A"
                                                    :weight (/ 9 13)}]})))))

(deftest post-validation-rejects-infeasible-candidates-with-reasons-test
  (let [reason (fn [encoded-overrides covariance]
                 (:reason (closed-form/eligible?
                           (assoc (with-encoded {:kind :minimum-variance}
                                                 encoded-overrides)
                                  :covariance covariance))))]
    (is (= :violates-bounds
           (reason {:upper-bounds [0.5 1]} diagonal-covariance)))
    (is (= :violates-bounds
           (reason {:long-only? true :lower-bounds [0 0] :upper-bounds [1 1]}
                   signed-covariance)))
    (is (= :violates-gross
           (reason {:gross-exposure {:max 1.2}} signed-covariance)))
    (is (= :violates-turnover
           (reason {:current-weights [0 0] :max-turnover 0.4} diagonal-covariance)))
    (is (= :violates-locked-weights
           (reason {:locked-weights [{:instrument-id "A" :weight 0.2}]}
                   diagonal-covariance)))))

(deftest post-validation-uses-absolute-tolerance-subset-of-target-selection-test
  (let [result (closed-form/validate-solution
                {:weights [1000.00005 0]
                 :net-target 1000.00005
                 :objective {:kind :minimum-variance}
                 :expected-returns [0.1 0.2]
                 :covariance diagonal-covariance
                 :encoded-constraints {:lower-bounds [neg-inf neg-inf]
                                       :upper-bounds [1000 pos-inf]
                                       :locked-weights []}})]
    (is (false? (:valid? result)))
    (is (= [:bound-violated] (mapv :code (:violations result))))))

(deftest core-preconditions-disqualify-closed-form-test
  (is (= :net-exposure-range
         (:reason (closed-form/eligible?
                   (with-encoded {:kind :minimum-variance}
                                 {:net-target nil
                                  :net-exposure {:min 0.8 :max 1.2}})))))
  (is (= :missing-net-equality
         (:reason (closed-form/eligible?
                   (with-encoded {:kind :minimum-variance}
                                 {:net-target nil})))))
  (is (= :explicit-return-tilts
         (:reason (closed-form/eligible?
                   (request {:kind :minimum-variance}
                            {:return-tilts [0 0.5 1]})))))
  (is (= :constraints-infeasible
         (:reason (closed-form/eligible?
                   (with-encoded {:kind :minimum-variance}
                                 {:status :infeasible})))))
  (is (= :unsupported-objective
         (:reason (closed-form/eligible?
                   (request {:kind :return-tilted}))))))

(deftest numeric-shapes-disqualify-closed-form-test
  (let [reason (fn [overrides]
                 (:reason (closed-form/eligible?
                           (request {:kind :minimum-variance} overrides))))]
    (is (= :singular-covariance
           (reason {:covariance [[0.04 0.04]
                                 [0.04 0.04]]})))
    (is (= :invalid-covariance
           (reason {:covariance [[0.04 js/NaN]
                                 [js/NaN 0.09]]})))
    (is (= :invalid-covariance
           (reason {:covariance [[0.04 0]
                                 [0 0.09]
                                 [0 0]]})))
    (is (= :asymmetric-covariance
           (reason {:covariance [[0.04 0.05]
                                 [0.01 0.09]]})))
    (is (= :invalid-expected-returns
           (reason {:expected-returns [0.1 js/NaN]})))
    (is (= :invalid-expected-returns
           (reason {:expected-returns [0.1]})))
    (is (= :empty-universe
           (reason {:instrument-ids []
                    :expected-returns []
                    :covariance []
                    :encoded-constraints (unbounded-encoded 0)})))))

(deftest objective-conditions-disqualify-closed-form-test
  (is (= :target-volatility-below-gmv
         (:reason (closed-form/eligible?
                   (request {:kind :target-volatility :target-volatility 0.1})))))
  (is (= :non-positive-sharpe-denominator
         (:reason (closed-form/eligible?
                   (request {:kind :max-sharpe}
                            {:expected-returns [-0.1 -0.2]})))))
  (is (= :non-finite-target-return
         (:reason (closed-form/eligible?
                   (request {:kind :target-return})))))
  (is (= :non-finite-target-volatility
         (:reason (closed-form/eligible?
                   (request {:kind :target-volatility}))))))

(deftest numeric-frontier-failures-fall-back-with-reasons-test
  (is (= :indefinite-covariance
         (:reason (closed-form/eligible?
                   (request {:kind :minimum-variance}
                            {:covariance [[-0.04 0]
                                          [0 -0.09]]})))))
  (let [degenerate (request {:kind :target-volatility :target-volatility 0.2}
                            {:expected-returns [0.1 0.1]})]
    (is (= :degenerate-frontier (:reason (closed-form/eligible? degenerate))))
    (let [result (closed-form/solve-portfolio degenerate)]
      (is (= :error (:status result)))
      (is (= :degenerate-frontier (:reason result)))
      (is (true? (get-in result [:diagnostics :fallback?]))))))

(deftest validate-solution-rejects-bad-weights-test
  (let [base {:net-target 1
              :objective {:kind :minimum-variance}
              :expected-returns [0.1 0.2]
              :covariance diagonal-covariance
              :encoded-constraints (unbounded-encoded 2)}
        net-violation (closed-form/validate-solution
                       (assoc base :weights [0.7 0.7]))
        non-finite (closed-form/validate-solution
                    (assoc base :weights [js/NaN 0.5]))
        valid (closed-form/validate-solution
               (assoc base :weights [0.6923076923 0.3076923077]))]
    (is (false? (:valid? net-violation)))
    (is (= [:net-target-violated]
           (mapv :code (:violations net-violation))))
    (is (false? (:valid? non-finite)))
    (is (= [:non-finite-weights]
           (mapv :code (:violations non-finite))))
    (is (true? (:valid? valid)))
    (is (near? 0.1307692308 (get-in valid [:metrics :expected-return])))))

(deftest solve-portfolio-emits-rejected-diagnostics-on-fallback-test
  (let [result (closed-form/solve-portfolio
                (with-encoded {:kind :minimum-variance}
                              {:upper-bounds [0.5 1]}))]
    (is (= :error (:status result)))
    (is (= :closed-form (:solver result)))
    (is (= :violates-bounds (:reason result)))
    (is (nil? (:weights result)))
    (is (= {:strategy :closed-form
            :closed-form? true
            :closed-form-candidate-rejected? true
            :fallback? true
            :objective-kind :minimum-variance
            :rejection-reason :violates-bounds}
           (:diagnostics result)))))
