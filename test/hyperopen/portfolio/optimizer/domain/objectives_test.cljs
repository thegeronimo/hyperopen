(ns hyperopen.portfolio.optimizer.domain.objectives-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.closed-form :as closed-form]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(deftest minimum-variance-builds-single-qp-with-net-equality-and-bounds-test
  ;; The low-variance asset dominates the unconstrained GMV (~0.99 weight),
  ;; which violates the 0.8 cap, so the closed-form candidate is rejected and
  ;; planning falls back to the single QP whose encoding this test pins.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :constraints {:long-only? true
                                :max-asset-weight 0.8}})
        plan (objectives/build-solver-plan
              {:objective {:kind :minimum-variance}
               :instrument-ids ["A" "B"]
               :expected-returns [0.1 0.2]
               :covariance [[0.01 0]
                            [0 1]]
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
    ;; The min-variance-at-0.16 portfolio [0.4 0.6] sits inside the 0.7 cap, so
    ;; the closed-form candidate is accepted; the closed-form problem still
    ;; carries the same target-return floor inequality for downstream checks.
    (is (= :closed-form (:strategy feasible)))
    (is (= :closed-form-portfolio (get-in feasible [:problems 0 :kind])))
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

(deftest default-frontier-sweep-uses-dense-log-spaced-tilts-test
  (let [tilts objectives/default-return-tilts
        encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :constraints {:long-only? true}})
        plan (objectives/build-solver-plan
              {:objective {:kind :target-volatility
                           :target-volatility 0.12
                           :frontier-points 12}
               :instrument-ids ["A" "B"]
               :expected-returns [0.1 0.2]
               :covariance [[1 0]
                            [0 1]]
               :encoded-constraints encoded})]
    (is (= objectives/default-frontier-point-count (count tilts)))
    (is (= 0 (first tilts)))
    (is (apply < (rest tilts)))
    (is (< 12.8 (last tilts))
        "Dense frontier sweeps should extend beyond the old sparse tilt ceiling.")
    (is (= 12 (count (:problems plan))))
    (is (= 0 (get-in plan [:problems 0 :return-tilt])))
    (is (< 12.8 (get-in plan [:problems 11 :return-tilt])))))

(deftest display-frontier-uses-target-return-sweep-when-range-is-known-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "BTC"}
                             {:instrument-id "ETH"}
                             {:instrument-id "HYPE"}
                             {:instrument-id "SOL"}]
                  :constraints {:long-only? true
                                :max-asset-weight 0.5}})
        plan (objectives/build-display-frontier-plan
              {:objective {:kind :target-return
                           :target-return 0.1584
                           :frontier-points 12}
               :instrument-ids ["BTC" "ETH" "HYPE" "SOL"]
               :expected-returns [-0.0933 0.5266 1.2449 -0.2713]
               :covariance [[0.18 0 0 0]
                            [0 0.52 0 0]
                            [0 0 0.93 0]
                            [0 0 0 0.54]]
               :encoded-constraints encoded})
        problems (:problems plan)
        target-floors (keep #(get-in % [:inequalities 0 :lower]) problems)]
    (is (= :frontier-sweep (:strategy plan)))
    (is (= 12 (count problems)))
    (is (= :return-tilted (get-in problems [0 :objective-kind]))
        "The display sweep keeps a minimum-variance anchor before return floors.")
    (is (= :target-return (get-in problems [1 :objective-kind])))
    (is (= 11 (count target-floors)))
    (is (apply < target-floors))
    (is (<= 0.1584 (first target-floors)))
    (is (near? 0.88575 (last target-floors)))
    (is (every? zero? (map :return-tilt problems))
        "Target-return frontier points should minimize variance at each return floor, not add return tilts.")))

(deftest max-sharpe-display-frontier-uses-target-return-sweep-when-range-is-known-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "BTC"}
                             {:instrument-id "ETH"}
                             {:instrument-id "HYPE"}
                             {:instrument-id "SOL"}]
                  :constraints {:long-only? true
                                :max-asset-weight 0.5}})
        plan (objectives/build-display-frontier-plan
              {:objective {:kind :max-sharpe
                           :frontier-points 12}
               :instrument-ids ["BTC" "ETH" "HYPE" "SOL"]
               :expected-returns [-0.0933 0.5266 1.2449 -0.2713]
               :covariance [[0.18 0 0 0]
                            [0 0.52 0 0]
                            [0 0 0.93 0]
                            [0 0 0 0.54]]
               :encoded-constraints encoded})
        problems (:problems plan)
        target-floors (keep #(get-in % [:inequalities 0 :lower]) problems)]
    (is (= :frontier-sweep (:strategy plan)))
    (is (= 12 (count problems)))
    (is (= :return-tilted (get-in problems [0 :objective-kind]))
        "The display sweep keeps a minimum-variance anchor before return floors.")
    (is (= :target-return (get-in problems [1 :objective-kind])))
    (is (= 11 (count target-floors)))
    (is (apply < target-floors))
    (is (near? 0 (first target-floors)))
    (is (near? 0.88575 (last target-floors)))
    (is (every? zero? (map :return-tilt problems))
        "Max-Sharpe display frontier points should sample return floors instead of reusing return tilts.")))

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
  ;; The net pin is what keeps this a solvable request at all: a signed Minimum
  ;; risk book whose only controls are ceilings has an all-cash optimum and is
  ;; now rejected as :objective-collapses-to-cash before any problem is built.
  ;; A 0.4 current book under a 0.25 turnover cap can still reach cash (the row
  ;; is sum|w - w0| <= 2*max-turnover), so turnover alone would not save it.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :current-weights {"A" 0.3
                                    "B" -0.1}
                  :constraints {:long-only? false
                                :net-exposure {:min 1.0 :max 1.0}
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

(def ^:private unbounded-encoded
  {:status :ok
   :long-only? false
   :net-target 1
   :lower-bounds [js/Number.NEGATIVE_INFINITY js/Number.NEGATIVE_INFINITY]
   :upper-bounds [js/Number.POSITIVE_INFINITY js/Number.POSITIVE_INFINITY]
   :locked-weights []})

(defn- closed-form-opts
  [objective]
  {:objective objective
   :instrument-ids ["A" "B"]
   :expected-returns [0.1 0.2]
   :covariance [[0.04 0]
                [0 0.09]]
   :encoded-constraints unbounded-encoded})

(deftest closed-form-eligible-objectives-plan-one-closed-form-problem-test
  (doseq [objective [{:kind :minimum-variance}
                     {:kind :target-return :target-return 0.16}
                     {:kind :max-sharpe}
                     {:kind :target-volatility :target-volatility 0.2}]]
    (let [plan (objectives/build-solver-plan (closed-form-opts objective))
          problem (get-in plan [:problems 0])]
      (is (= :ok (:status plan)) (str objective))
      (is (= :closed-form (:strategy plan)) (str objective))
      (is (= objective (:selection-objective plan)) (str objective))
      (is (= 1 (count (:problems plan))) (str objective))
      (is (= :closed-form-portfolio (:kind problem)) (str objective))
      (is (= (:kind objective) (:objective-kind problem)) (str objective))
      (is (= {:eligible? true :net-target 1}
             (:closed-form-eligibility problem))
          (str objective))
      (is (= [{:code :net-exposure
               :coefficients [1 1]
               :target 1}]
             (:equalities problem))
          (str objective)))))

(deftest constrained-infeasible-candidates-keep-existing-qp-strategies-test
  ;; A tight max-asset-weight (0.5) means the unconstrained optima for these
  ;; diagonal inputs (min-var w1=0.69, max-sharpe w1=0.53, target-vol w2=0.62)
  ;; all violate the box, so post-validation rejects the closed-form candidate
  ;; and planning falls back to the existing QP strategies.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :constraints {:long-only? true
                                :max-asset-weight 0.5}})
        constrained-opts (fn [objective]
                           (assoc (closed-form-opts objective)
                                  :encoded-constraints encoded))
        max-sharpe-plan (objectives/build-solver-plan
                         (constrained-opts {:kind :max-sharpe}))
        target-volatility-plan (objectives/build-solver-plan
                                (constrained-opts {:kind :target-volatility
                                                   :target-volatility 0.2}))
        minimum-variance-plan (objectives/build-solver-plan
                               (constrained-opts {:kind :minimum-variance}))]
    (is (= :frontier-sweep (:strategy max-sharpe-plan)))
    (is (= :frontier-sweep (:strategy target-volatility-plan)))
    (is (= :single-qp (:strategy minimum-variance-plan)))
    (is (every? #(= :quadratic-program (:kind %))
                (mapcat :problems [max-sharpe-plan
                                   target-volatility-plan
                                   minimum-variance-plan])))))

(deftest long-only-feasible-requests-use-closed-form-test
  ;; The widened fast path: a long-only request whose unconstrained optimum
  ;; already lands inside the default [0,1] box is solved in closed form,
  ;; because a feasible candidate is provably optimal for the constrained
  ;; problem. This is the latency win for the common case.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :constraints {:long-only? true}})
        plan-for (fn [objective]
                   (objectives/build-solver-plan
                    (assoc (closed-form-opts objective)
                           :encoded-constraints encoded)))]
    (is (= [0 0] (:lower-bounds encoded)))
    (is (= [1 1] (:upper-bounds encoded)))
    (doseq [objective [{:kind :minimum-variance}
                       {:kind :max-sharpe}
                       {:kind :target-volatility :target-volatility 0.2}]]
      (let [plan (plan-for objective)]
        (is (= :closed-form (:strategy plan)) (str objective))
        (is (= :closed-form-portfolio (get-in plan [:problems 0 :kind]))
            (str objective))))))

(deftest signed-leveraged-request-without-net-equality-stays-on-frontier-sweep-test
  ;; Mirrors a real leveraged Max-Sharpe / Target-Volatility run: signed (not
  ;; long-only), a finite gross-leverage cap, a large per-asset cap, and NO
  ;; net-exposure equality. The closed-form formulas need a single net target
  ;; b (sum of weights), which only long-only or an equal net-exposure min/max
  ;; supplies, so this is core-ineligible (:missing-net-equality) and correctly
  ;; runs the existing QP frontier sweep (40 problems by default).
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A" :market-type :perp}
                             {:instrument-id "B" :market-type :perp}
                             {:instrument-id "C" :market-type :perp}]
                  :constraints {:long-only? false
                                :gross-leverage 33.06
                                :max-asset-weight 11.66}})
        opts {:objective {:kind :max-sharpe}
              :instrument-ids ["A" "B" "C"]
              :expected-returns [0.10 0.20 0.15]
              :covariance [[0.04 0 0]
                           [0 0.09 0]
                           [0 0 0.06]]
              :encoded-constraints encoded}]
    (is (= :missing-net-equality (:reason (closed-form/eligible? opts))))
    (is (= :frontier-sweep (:strategy (objectives/build-solver-plan opts))))
    (is (= objectives/default-frontier-point-count
           (count (:problems (objectives/build-solver-plan opts)))))))

(deftest explicit-return-tilts-keep-frontier-sweep-for-eligible-requests-test
  (let [plan (objectives/build-solver-plan
              (assoc (closed-form-opts {:kind :max-sharpe})
                     :return-tilts [0 0.5 1]))]
    (is (= :frontier-sweep (:strategy plan)))
    (is (= [0 0.5 1] (mapv :return-tilt (:problems plan))))))

(deftest target-volatility-below-gmv-falls-back-to-frontier-sweep-test
  (let [plan (objectives/build-solver-plan
              (closed-form-opts {:kind :target-volatility
                                 :target-volatility 0.1}))]
    (is (= :frontier-sweep (:strategy plan)))
    (is (= objectives/default-frontier-point-count (count (:problems plan))))
    (is (every? #(= :quadratic-program (:kind %)) (:problems plan)))))

(deftest numeric-frontier-failure-falls-back-to-qp-test
  ;; Negative-definite (indefinite) covariance is symmetric and non-singular,
  ;; so eligibility's structural checks pass but the moment guards reject it;
  ;; planning must fall back to the existing single QP rather than closed form.
  (let [plan (objectives/build-solver-plan
              (assoc (closed-form-opts {:kind :minimum-variance})
                     :covariance [[-0.04 0]
                                  [0 -0.09]]))]
    (is (= :single-qp (:strategy plan)))
    (is (= :quadratic-program (get-in plan [:problems 0 :kind])))))

(deftest solver-plan-omits-turnover-l1-constraint-when-cap-disabled-test
  ;; Net pin as above: without it this signed Minimum risk request collapses to
  ;; cash and never reaches problem building.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :current-weights {"A" 0.3
                                    "B" -0.1}
                  :constraints {:long-only? false
                                :net-exposure {:min 1.0 :max 1.0}
                                :max-turnover nil}})
        plan (objectives/build-solver-plan
              {:objective {:kind :minimum-variance}
               :instrument-ids ["A" "B"]
               :expected-returns [0.1 0.2]
               :covariance [[1 0]
                            [0 1]]
               :encoded-constraints encoded})]
    (is (= [] (get-in plan [:problems 0 :l1-constraints])))))

(deftest gross-floor-encodes-as-signed-inequality-not-l1-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A" :market-type :perp :position-side :long}
                             {:instrument-id "B" :market-type :perp :position-side :short}]
                  :constraints {:long-only? false
                                :gross-floor 1.0
                                :gross-leverage 3.0
                                :net-exposure {:min 0.2 :max 0.3}
                                :max-asset-weight 2.0}})
        plan (objectives/build-solver-plan
              {:objective {:kind :minimum-variance}
               :instrument-ids ["A" "B"]
               :expected-returns [0.1 0.1]
               :covariance [[1 0]
                            [0 1]]
               :encoded-constraints encoded})
        inequalities (get-in plan [:problems 0 :inequalities])]
    (is (= :single-qp (:strategy plan)))
    ;; the floor rides the generic signed-linear inequality channel ...
    (is (some #(and (= :gross-floor (:code %))
                    (= [1 -1] (:coefficients %))
                    (= 1.0 (:lower %)))
              inequalities))
    ;; ... and NOT the L1 split-variable channel (where a >= floor is unenforceable)
    (is (not-any? #(= :gross-floor (:code %))
                  (get-in plan [:problems 0 :l1-constraints])))))

(deftest build-solver-plan-selects-sequential-equal-risk-strategy-test
  (let [encoded {:status :ok
                 :long-only? false
                 :instrument-ids ["perp:A" "perp:B"]
                 :current-weights [0 0]
                 :lower-bounds [0 -1]
                 :upper-bounds [1 0]
                 :locked-weights []
                 :gross-exposure {:max 2.0}
                 :net-exposure {:min 0.0 :max 0.0}
                 :exposure-targets {:gross-target 2.0 :gross-band 0.0
                                    :net-target 0.0 :net-band 0.0}
                 :side-metadata [{:instrument-id "perp:A"
                                  :requested-side :long
                                  :shortable? true}
                                 {:instrument-id "perp:B"
                                  :requested-side :short
                                  :shortable? true}]
                 :violations []}
        opts {:objective {:kind :equal-risk}
              :instrument-ids ["perp:A" "perp:B"]
              :expected-returns [0.1 0.2]
              :covariance [[0.04 0.01] [0.01 0.04]]
              :encoded-constraints encoded}
        plan (objectives/build-solver-plan opts)]
    (is (= :ok (:status plan)))
    (is (= :sequential-equal-risk (:strategy plan)))
    (is (= 1 (count (:problems plan))))
    (is (= :sequential-equal-risk (get-in plan [:problems 0 :kind])))
    (is (= :equal-risk (get-in plan [:problems 0 :objective-kind])))
    ;; Covariance-only: the planned problem never carries expected returns.
    (is (nil? (get-in plan [:problems 0 :expected-returns])))
    (is (nil? (get-in plan [:problems 0 :linear])))
    ;; Equal-risk never builds a display-frontier plan (no sweep to draw).
    (is (nil? (objectives/build-display-frontier-plan opts)))))
;; --- the joint reachability code is objective-CONDITIONAL -------------------
;;
;; encode-constraints is objective-agnostic, so it publishes the joint net/gross
;; reachability code on :conditional-violations and leaves :status alone. The
;; code is derived from NET rows, and equal-risk-plan/subproblem-template omits
;; those deliberately ("Stored net rows are deliberately omitted for Equal Risk")
;; with inverse-volatility-plan reusing the same template. Setting :status from
;; it rejected both objectives on the DEFAULT draft net pin plus any gross band
;; dragged on the exposure pad - requests they solve correctly.

(def ^:private net-pin-ids
  ["perp:L0" "perp:L1" "perp:L2" "perp:L3" "perp:L4" "perp:L5"])

(def ^:private net-pin-covariance
  (mapv (fn [row]
          (mapv (fn [col]
                  (if (= row col)
                    (+ 0.04 (* 0.005 row))
                    0.002))
                (range 6)))
        (range 6)))

(def ^:private net-pin-encoded
  ;; :net-min 1.0 / :net-max 1.0 is what defaults.cljs ships; the 1.5-2.5 gross
  ;; band is one pad drag. On an all-long book gross = net, so a 1.5x floor
  ;; against a 1.0x net pin genuinely is unreachable - for the objectives that
  ;; encode the net rows.
  (constraints/encode-constraints
   {:universe (mapv (fn [idx]
                      {:instrument-id (str "perp:L" idx)
                       :market-type :perp
                       :shortable? true
                       :position-side :long})
                    (range 6))
    :constraints {:long-only? false
                  :include-spot? false
                  :max-asset-weight 0.5
                  :gross-floor 1.5
                  :gross-leverage 2.5
                  :net-exposure {:min 1.0 :max 1.0}
                  :net-band-pct 0.0}}))

(defn- net-pin-plan
  [objective-kind]
  (objectives/build-solver-plan
   {:objective {:kind objective-kind}
    :instrument-ids net-pin-ids
    :expected-returns [0.1 0.12 0.09 0.15 0.11 0.13]
    :covariance net-pin-covariance
    :encoded-constraints net-pin-encoded}))

(deftest joint-reachability-rides-a-key-that-never-sets-encoded-status-test
  (is (= :ok (:status net-pin-encoded)))
  (is (= [] (:violations net-pin-encoded)))
  (is (= [:net-unreachable-given-sides]
         (mapv :code (:conditional-violations net-pin-encoded)))))

(deftest joint-reachability-leaves-the-net-ignoring-objectives-alone-test
  (let [equal-risk (net-pin-plan :equal-risk)
        inverse-vol (net-pin-plan :inverse-volatility)]
    (is (= :ok (:status equal-risk)))
    (is (= :sequential-equal-risk (:strategy equal-risk)))
    (is (= :ok (:status inverse-vol)))
    (is (= :inverse-volatility (:strategy inverse-vol)))))

(deftest joint-reachability-still-blocks-every-net-encoding-objective-test
  (is (= #{:minimum-variance :target-return :max-sharpe :target-volatility}
         objectives/net-row-objective-kinds)
      "the promoted set is exactly the kinds whose problems carry net rows")
  (doseq [kind objectives/net-row-objective-kinds]
    (let [plan (net-pin-plan kind)
          label (str kind)]
      (is (= :infeasible (:status plan)) label)
      (is (= :constraint-presolve (:reason plan)) label)
      (is (= [:net-unreachable-given-sides]
             (mapv :code (get-in plan [:details :violations])))
          label))))
