(ns hyperopen.portfolio.optimizer.inverse-volatility-plan-test
  "Solver-plan contract for the Risk-weighted sizing (:inverse-volatility)
  objective (ExecPlan optimizer-inverse-volatility-objective, acceptance items
  1, 4, 5). The plan is expressed through the existing public entry point
  `objectives/build-solver-plan`: one :quadratic-program projection problem
  whose linear term is the negated 1/sigma seed, presolve screening reused
  from Equal Risk with messages naming \"Risk-weighted sizing\", and a
  dedicated zero-volatility infeasibility."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]))

(defn- near?
  ([expected actual] (near? expected actual 1e-6))
  ([expected actual tolerance]
   (and (number? actual)
        (< (js/Math.abs (- expected actual)) tolerance))))

(def ^:private instrument-ids
  ["perp:A" "perp:B" "perp:C" "perp:D" "perp:E"])

(def ^:private sigmas
  [0.2 0.3 0.4 0.5 0.1])

(def ^:private covariance
  [[0.04 0 0 0 0]
   [0 0.09 0 0 0]
   [0 0 0.16 0 0]
   [0 0 0 0.25 0]
   [0 0 0 0 0.01]])

(def ^:private gross-target 1.5)

(def ^:private encoded-three-long-two-short
  {:status :ok
   :long-only? false
   :instrument-ids instrument-ids
   :current-weights [0 0 0 0 0]
   :lower-bounds [0 0 0 -2 -2]
   :upper-bounds [2 2 2 0 0]
   :locked-weights []
   :gross-exposure {:max 3.0}
   :net-exposure {:min 0.0 :max 0.0}
   :exposure-targets {:gross-target gross-target :gross-band 0.0
                      :net-target 0.0 :net-band 0.0}
   :side-metadata [{:instrument-id "perp:A" :requested-side :long :shortable? true}
                   {:instrument-id "perp:B" :requested-side :long :shortable? true}
                   {:instrument-id "perp:C" :requested-side :long :shortable? true}
                   {:instrument-id "perp:D" :requested-side :short :shortable? true}
                   {:instrument-id "perp:E" :requested-side :short :shortable? true}]
   :violations []})

(defn- build-plan
  [encoded covariance*]
  (objectives/build-solver-plan
   {:objective {:kind :inverse-volatility}
    :instrument-ids instrument-ids
    :expected-returns [0.1 0.2 0.3 0.4 0.5]
    :covariance covariance*
    :encoded-constraints encoded}))

(def ^:private expected-magnitudes
  (let [inverses (mapv #(/ 1 %) sigmas)
        total (reduce + 0 inverses)]
    (mapv #(* gross-target (/ % total)) inverses)))

(deftest inverse-volatility-plans-one-projection-qp-with-negated-seed-linear-test
  (let [plan (build-plan encoded-three-long-two-short covariance)
        problem (get-in plan [:problems 0])
        seed (vec (:seed-weights problem))
        diagonal (mapv #(nth (nth (:quadratic problem) %) %) (range 5))]
    (is (= :ok (:status plan))
        (str ":inverse-volatility must plan, not report "
             (pr-str (select-keys plan [:status :reason]))))
    (is (= :inverse-volatility (:strategy plan)))
    (is (= {:kind :inverse-volatility} (:selection-objective plan)))
    (is (= 1 (count (:problems plan))))
    (is (= :quadratic-program (:kind problem))
        "the plan is a single feasible-projection QP, no sweep")
    (is (= :inverse-volatility (:objective-kind problem)))
    (testing "covariance-only: the planned problem never carries expected returns"
      (is (nil? (:expected-returns problem)))
      (is (nil? (:return-tilt problem))))
    (testing "sigma-weighted projection: diagonal quadratic ∝ sigma, linear = -(d·seed)"
      (is (= 5 (count seed)))
      (doseq [row (range 5)
              col (range 5)
              :when (not= row col)]
        (is (zero? (get-in problem [:quadratic row col]))
            "the projection quadratic is diagonal"))
      (let [ratios (mapv / diagonal sigmas)]
        (doseq [ratio (rest ratios)]
          (is (near? (first ratios) ratio)
              "the quadratic diagonal is proportional to sigma")))
      (doseq [idx (range 5)]
        (is (near? (- (* (nth diagonal idx) (nth seed idx)))
                   (nth (:linear problem) idx))
            "linear term is the negated d-weighted seed")))
    (testing "seed magnitudes are proportional to 1/sigma and sum to the gross target"
      (doseq [idx (range 5)]
        (is (near? (nth expected-magnitudes idx)
                   (js/Math.abs (nth seed idx 0)))
            (str "seed magnitude for " (nth instrument-ids idx))))
      (is (near? gross-target
                 (reduce + 0 (map js/Math.abs seed)))))
    (testing "every asset's seed magnitude is strictly positive, on its book's side"
      (doseq [idx [0 1 2]]
        (is (pos? (nth seed idx 0))
            (str (nth instrument-ids idx) " is a long book")))
      (doseq [idx [3 4]]
        (is (neg? (nth seed idx 0))
            (str (nth instrument-ids idx) " is a short book"))))
    (testing "|seed|*sigma is equal across every asset (the sizing law)"
      (let [risk-weights (map (fn [idx]
                                (* (js/Math.abs (nth seed idx 0))
                                   (nth sigmas idx)))
                              (range 5))]
        (doseq [risk-weight (rest risk-weights)]
          (is (near? (first risk-weights) risk-weight)))))))

(deftest inverse-volatility-zero-volatility-asset-is-a-named-infeasibility-test
  (let [zero-vol-covariance (assoc-in covariance [4 4] 0)
        plan (build-plan encoded-three-long-two-short zero-vol-covariance)
        violations (get-in plan [:details :violations])]
    (is (= :infeasible (:status plan)))
    (is (not= :unknown-objective (:reason plan))
        ":inverse-volatility must be a known objective kind")
    (is (some #(= :inverse-volatility-zero-volatility-asset (:code %)) violations)
        (str "expected the zero-volatility violation code, got "
             (pr-str violations)))
    (is (some #(some #{"perp:E"} (:instrument-ids %)) violations)
        "the violation names the zero-volatility instrument")))

(deftest inverse-volatility-presolve-messages-name-risk-weighted-sizing-test
  (let [encoded (-> encoded-three-long-two-short
                    (assoc-in [:lower-bounds 0] -2))
        plan (build-plan encoded covariance)
        violations (get-in plan [:details :violations])]
    (is (= :infeasible (:status plan)))
    (is (not= :unknown-objective (:reason plan)))
    (is (some #(= :equal-risk-requires-fixed-sides (:code %)) violations)
        "the presolve violation codes are reused verbatim (infeasible-panel wiring)")
    (is (some #(str/includes? (str (:message %)) "Risk-weighted sizing")
              violations)
        (str "the fixed-side message must name \"Risk-weighted sizing\", got "
             (pr-str (mapv :message violations))))
    (is (not-any? #(str/includes? (str (:message %)) "Equal Risk") violations)
        "the message must not blame Equal Risk for a Risk-weighted sizing run")))
