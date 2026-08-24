(ns hyperopen.portfolio.optimizer.domain.closed-form-support-bounds-test
  "Regression for the bound check in `validate-solution`.

  `within-bounds?` maps over the weights and both bound vectors together.
  `map` with three collections stops at the SHORTEST, so a bounds vector
  shorter than the weight vector silently truncated the check -- and because
  the truncation is driven by whichever vector is shortest, a short LOWER
  bounds vector disabled the UPPER bound check on the tail too.

  That matters more than it looks: post-validation is the whole safety story
  for the closed-form path. It is what lets the optimizer accept an
  analytically derived portfolio without re-solving it, so a bound it does not
  actually check is a bound the closed-form path does not actually enforce."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.closed-form-support :as support]))

(defn- validate
  [weights encoded-constraints]
  (support/validate-solution
   {:weights weights
    :net-target 1
    :objective {:kind :minimum-variance}
    :expected-returns (vec (repeat (count weights) 0.1))
    :covariance (mapv (fn [i]
                        (mapv (fn [j] (if (= i j) 0.04 0.001))
                              (range (count weights))))
                      (range (count weights)))
    :encoded-constraints encoded-constraints}))

(defn- bound-violated?
  [result]
  (boolean (some #(= :bound-violated (:code %)) (:violations result))))

(deftest a-short-lower-bounds-vector-does-not-disable-the-upper-bound-check-test
  ;; Three weights, an upper bound for each, but only two lower bounds. The
  ;; third weight is far outside its upper bound. Before the fix the map
  ;; stopped after two elements and this validated clean.
  (let [result (validate [0.2 0.2 0.6]
                         {:lower-bounds [0 0]
                          :upper-bounds [0.5 0.5 0.25]})]
    (is (bound-violated? result)
        "a weight of 0.6 against an upper bound of 0.25 must be rejected even
         though the lower-bounds vector is shorter than the weights")))

(deftest a-short-upper-bounds-vector-does-not-disable-the-lower-bound-check-test
  (let [result (validate [0.2 0.2 -0.4]
                         {:lower-bounds [0 0 0]
                          :upper-bounds [1 1]})]
    (is (bound-violated? result)
        "a weight of -0.4 against a lower bound of 0 must be rejected even
         though the upper-bounds vector is shorter than the weights")))

(deftest bounds-shorter-than-the-weights-are-treated-as-unbounded-not-as-absent-test
  ;; The positions a short vector does not reach are genuinely unconstrained,
  ;; so they must pass. The fix must not turn "no bound supplied" into a
  ;; violation.
  (let [result (validate [0.2 0.2 5.0]
                         {:lower-bounds [0 0]
                          :upper-bounds [1 1]})]
    (is (not (bound-violated? result))
        "a position with no bound on either side is unconstrained and must not
         be reported as a bound violation")))

(deftest bounds-longer-than-the-weights-are-ignored-test
  (let [result (validate [0.5 0.5]
                         {:lower-bounds [0 0 0 0]
                          :upper-bounds [1 1 0.0001 0.0001]})]
    (is (not (bound-violated? result))
        "bounds past the end of the weight vector describe nothing and must
         not be enforced")))

(deftest bounds-that-match-the-weights-still-behave-test
  (is (not (bound-violated? (validate [0.5 0.5] {:lower-bounds [0 0]
                                                 :upper-bounds [1 1]}))))
  (is (bound-violated? (validate [1.5 -0.5] {:lower-bounds [0 0]
                                             :upper-bounds [1 1]})))
  (is (not (bound-violated? (validate [0.5 0.5] {}))))
  (is (not (bound-violated? (validate [0.5 0.5] {:lower-bounds nil
                                                 :upper-bounds nil})))))
