(ns hyperopen.portfolio.optimizer.application.engine-solver-diagnostics-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.engine.target-selection :as target-selection]))

(def infeasible-turnover-problem
  {:kind :quadratic-program
   :objective-kind :minimum-variance
   :instrument-ids ["perp:BTC" "perp:ETH"]
   :quadratic [[1 0]
               [0 1]]
   :linear [0 0]
   :equalities [{:code :net-exposure
                 :coefficients [1 1]
                 :target 1}]
   :inequalities []
   :l1-constraints [{:code :gross-exposure
                     :max 1}
                    {:code :turnover
                     :current-weights [20 -11.313329083687073]
                     :max 2}]
   :lower-bounds [0 0]
   :upper-bounds [0.5 0.5]})

(deftest target-selection-explains-solver-results-that-violate-constraints-test
  (let [result (target-selection/target-selection
                {:objective {:kind :minimum-variance}}
                {:strategy :single-qp}
                [{:status :solved
                  :solver :osqp
                  :weights [0 0]
                  :problem infeasible-turnover-problem}]
                [0 0]
                [[1 0]
                 [0 1]])
        violations (get-in result [:details :violations])
        messages (mapv :message violations)]
    (is (= :infeasible (:status result)))
    (is (= :solver-returned-invalid-solution (:reason result)))
    (is (= #{:solver-result-equality-violation
             :solver-result-turnover-violation}
           (set (map :code violations))))
    (is (some #(str/includes? % "net-exposure expected 1.0000")
              messages))
    (is (some #(str/includes? % "turnover limit 2.0000")
              messages))
    (is (= 1 (get-in result [:details :solver-result-count])))
    (is (= 1 (get-in result [:details :rejected-result-count])))))

(def one-sided-inequality-problem
  ;; The shape the owner's 2026-08-27 report hit: a signed-linear gross FLOOR
  ;; and the lower edge of the percentage net band are both `>=` rows carrying
  ;; only a :lower. Reported through a "between <lower> and <upper>" template
  ;; they read "expected between 3.9950 and N/A", which looks like corrupt data
  ;; rather than an ordinary one-sided constraint.
  {:kind :quadratic-program
   :objective-kind :minimum-variance
   :instrument-ids ["perp:BTC" "perp:ETH"]
   :quadratic [[1 0]
               [0 1]]
   :linear [0 0]
   :equalities []
   :inequalities [{:code :gross-floor
                   :coefficients [1 1]
                   :lower 3.995}
                  {:code :net-band
                   :coefficients [1.028 1.028]
                   :lower 1.25}
                  {:code :net-band
                   :coefficients [0.972 0.972]
                   :upper 1.25}]
   :l1-constraints []
   :lower-bounds [0 0]
   :upper-bounds [15.45 15.45]})

(deftest one-sided-inequality-violations-never-report-a-missing-bound-test
  (let [result (target-selection/target-selection
                {:objective {:kind :minimum-variance}}
                {:strategy :single-qp}
                [{:status :solved
                  :solver :osqp
                  :weights [0 0]
                  :problem one-sided-inequality-problem}]
                [0 0]
                [[1 0]
                 [0 1]])
        messages (mapv :message (get-in result [:details :violations]))]
    (is (= :infeasible (:status result)))
    ;; Both `>=` rows are violated by the all-zero vector; the `<=` row is not.
    (is (= 2 (count messages)))
    (is (some #(str/includes? % "gross-floor expected at least 3.9950") messages))
    (is (some #(str/includes? % "net-band expected at least 1.2500") messages))
    (is (not-any? #(str/includes? % "N/A") messages))
    (is (not-any? #(str/includes? % "between") messages))))

(deftest two-sided-inequality-violations-still-read-as-a-range-test
  (let [problem (assoc one-sided-inequality-problem
                       :inequalities [{:code :net-exposure
                                       :coefficients [1 1]
                                       :lower 1.0
                                       :upper 2.0}])
        result (target-selection/target-selection
                {:objective {:kind :minimum-variance}}
                {:strategy :single-qp}
                [{:status :solved
                  :solver :osqp
                  :weights [0 0]
                  :problem problem}]
                [0 0]
                [[1 0]
                 [0 1]])
        messages (mapv :message (get-in result [:details :violations]))]
    (is (some #(str/includes? % "net-exposure expected between 1.0000 and 2.0000")
              messages))))

;; --- Solver-declined results -------------------------------------------------
;;
;; infrastructure.osqp no longer stamps :solved on a solve OSQP declined. It
;; returns {:status :infeasible :reason ... :message ... :details {:violations}}
;; with NO :weights, so `solver-result-violations` -- which re-derives
;; violations FROM the weights -- has nothing to say about it. Everything the
;; solver diagnosed therefore has to survive on the result itself.

(defn- run-selection
  [solver-results]
  (target-selection/target-selection
   {:objective {:kind :minimum-variance}}
   {:strategy :single-qp}
   solver-results
   [0 0]
   [[1 0]
    [0 1]]))

(def declined-osqp-result
  {:status :infeasible
   :solver :osqp
   :reason :solver-primal-infeasible
   :message "No portfolio can satisfy every constraint in this request at the same time."
   :details {:violations []}})

(deftest solver-declined-runs-repeat-the-solver-own-explanation-test
  (let [result (run-selection [declined-osqp-result])]
    (is (= :infeasible (:status result)))
    ;; Not :solver-returned-no-solution: the solver read the request correctly
    ;; and reported it unsatisfiable, which is a different fact about the world
    ;; than "the solver failed to produce anything".
    (is (= :constraints-unsatisfiable (:reason result)))
    (is (= (:message declined-osqp-result) (:message result)))
    (is (not= "The solver did not return any feasible solution." (:message result)))
    ;; Which of the two declined reasons it was stays available for developers.
    (is (= [:solver-primal-infeasible]
           (mapv :reason (get-in result [:details :rejected-results]))))))

(def boundary-row-violation
  {:code :solver-boundary-row-violation
   :constraint-code :gross-exposure
   :lower 3.995
   :upper 1.0e20
   :value 0
   :message "gross-exposure left its bounds by 4.00e+00."})

(def outside-constraints-osqp-result
  {:status :infeasible
   :solver :osqp
   :reason :solver-solution-outside-constraints
   :message "The solver returned a point that does not satisfy the constraints it was given."
   :details {:violations [boundary-row-violation]}})

(deftest solver-reported-violations-survive-into-the-run-details-test
  (let [result (run-selection [outside-constraints-osqp-result])]
    (is (= :solver-solution-failed-boundary-check (:reason result)))
    (is (= "The solver returned a point that does not satisfy the constraints it was given."
           (:message result)))
    ;; The banner renders [:details :violations]; without the merge a declined
    ;; result produced an empty list, so it showed no bullets and highlighted no
    ;; control -- strictly less than the itemised diagnostics shipped in the
    ;; 2026-05-31 no-solution-diagnostics work.
    (is (= [boundary-row-violation] (get-in result [:details :violations])))
    (is (= [:gross-exposure]
           (mapv :constraint-code (get-in result [:details :violations]))))))

(deftest unclassified-solver-failures-still-report-no-solution-test
  (let [result (run-selection [{:status :infeasible
                                :solver :quadprog
                                :reason :solver-message
                                :message "constraints are inconsistent, no solution!"}])]
    (is (= :solver-returned-no-solution (:reason result)))
    (is (= "The solver did not return any feasible solution." (:message result)))
    (is (= [] (get-in result [:details :violations])))))

(deftest message-less-strategy-violations-do-not-reclassify-the-run-test
  ;; equal-risk's seed feasibility check reports {:code :bound :index 0 ...}:
  ;; raw indices and bounds with no copy. Merging those would chip the banner
  ;; with tokens no user can read AND flip the run onto the
  ;; "solved but invalid" branch, which is not what happened.
  (let [result (run-selection
                [{:status :infeasible
                  :solver :sequential-equal-risk
                  :reason :equal-risk-no-feasible-start
                  :message "No feasible starting portfolio satisfies the exposure targets."
                  :details {:violations [{:code :bound
                                          :index 0
                                          :weight 1.4
                                          :lower 0
                                          :upper 0.5}]}}])]
    (is (= :solver-returned-no-solution (:reason result)))
    (is (= [] (get-in result [:details :violations])))))

;; --- Rows whose value or limit is not a number -------------------------------
;;
;; The companion wart to the missing BOUND that `range-phrase` fixed: splicing
;; "N/A" into a sentence shaped like a numeric comparison.

(deftest l1-rows-that-cannot-be-evaluated-say-so-plainly-test
  (let [problem (assoc infeasible-turnover-problem
                       :equalities []
                       ;; One current weight for a two-instrument problem, so
                       ;; the turnover row cannot be evaluated at all.
                       :l1-constraints [{:code :turnover
                                         :current-weights [20]
                                         :max 2}])
        result (run-selection [{:status :solved
                                :solver :osqp
                                :weights [0 0]
                                :problem problem}])
        messages (mapv :message (get-in result [:details :violations]))]
    (is (= :solver-returned-invalid-solution (:reason result)))
    (is (= ["turnover limit 2.0000 but the solver returned no value for this row."]
           messages))
    (is (not-any? #(str/includes? % "N/A") messages))))

(deftest l1-rows-without-a-finite-limit-say-so-plainly-test
  (let [problem (assoc infeasible-turnover-problem
                       :equalities []
                       :l1-constraints [{:code :turnover
                                         :current-weights [20]
                                         :max nil}])
        result (run-selection [{:status :solved
                                :solver :osqp
                                :weights [0 0]
                                :problem problem}])
        messages (mapv :message (get-in result [:details :violations]))]
    (is (= ["turnover limit is not a finite number."] messages))
    (is (not-any? #(str/includes? % "N/A") messages))))

(deftest equality-rows-without-a-finite-target-say-so-plainly-test
  (let [problem (assoc infeasible-turnover-problem
                       :l1-constraints []
                       :equalities [{:code :net-exposure
                                     :coefficients [1 1]
                                     :target nil}])
        result (run-selection [{:status :solved
                                :solver :osqp
                                :weights [0 0]
                                :problem problem}])
        messages (mapv :message (get-in result [:details :violations]))]
    (is (= ["net-exposure target is not a finite number."] messages))
    (is (not-any? #(str/includes? % "N/A") messages))))

(deftest equality-rows-that-cannot-be-evaluated-say-so-plainly-test
  (let [problem (assoc infeasible-turnover-problem
                       :l1-constraints []
                       ;; Coefficients that do not match the weight vector.
                       :equalities [{:code :net-exposure
                                     :coefficients [1]
                                     :target 1}])
        result (run-selection [{:status :solved
                                :solver :osqp
                                :weights [0 0]
                                :problem problem}])
        messages (mapv :message (get-in result [:details :violations]))]
    (is (= ["net-exposure expected 1.0000 but the solver returned no value for this row."]
           messages))
    (is (not-any? #(str/includes? % "N/A") messages))))

;; --- The two solver-declined outcomes are different facts ---------------------
;;
;; infrastructure.osqp classifies a solve it cannot use in one of two ways, and
;; only ONE of them is evidence about the request:
;;
;;   :solver-primal-infeasible          OSQP wrote its OSQP_NAN sentinel into
;;                                      every element of solution->x, i.e. it
;;                                      declined the problem as infeasible.
;;   :solver-solution-outside-constraints
;;                                      OSQP returned a real point and OUR row
;;                                      check rejected it -- an unconverged
;;                                      iterate. It says nothing about whether
;;                                      any portfolio satisfies the request.
;;
;; Collapsing both onto :constraints-unsatisfiable made the banner headline "No
;; portfolio satisfies every constraint" for the second case too, which asserts
;; something no certificate backs and sends the user editing constraints that
;; may never have been the problem.

(def invalid-solution-result
  ;; A result the solver stamped :solved whose weights miss the constraints that
  ;; produced them -- the third arm, re-derived from the returned weights.
  {:status :solved
   :solver :osqp
   :weights [0 0]
   :problem infeasible-turnover-problem})

(def no-solution-result
  {:status :infeasible
   :solver :quadprog
   :reason :solver-message
   :message "constraints are inconsistent, no solution!"})

(defn- bullet-messages
  [result]
  (mapv :message (get-in result [:details :violations])))

(deftest solver-declined-arms-report-different-run-reasons-test
  (let [certificate (run-selection [declined-osqp-result])
        bad-point (run-selection [outside-constraints-osqp-result])]
    (is (= :constraints-unsatisfiable (:reason certificate)))
    (is (= :solver-solution-failed-boundary-check (:reason bad-point)))
    (is (not= (:reason certificate) (:reason bad-point)))
    ;; Each run repeats ITS OWN solver's message, never the other arm's copy.
    (is (= (:message declined-osqp-result) (:message certificate)))
    (is (= (:message outside-constraints-osqp-result) (:message bad-point)))
    (is (not= (:message certificate) (:message bad-point)))))

(deftest a-returned-point-outside-its-rows-never-asserts-infeasibility-test
  (let [result (run-selection [outside-constraints-osqp-result])]
    (is (= :infeasible (:status result)))
    ;; The defect: this arm used to headline as if the request were impossible.
    (is (not= :constraints-unsatisfiable (:reason result)))
    (is (not (str/includes? (:message result) "No portfolio")))
    (is (str/includes? (:message result) "The solver returned a point"))
    ;; The solver's own classification stays visible for whoever debugs it.
    (is (= [:solver-solution-outside-constraints]
           (mapv :reason (get-in result [:details :rejected-results]))))))

(deftest an-infeasibility-certificate-outranks-a-bad-point-in-either-order-test
  ;; Within a sweep every point is handed the same A/l/u, so a certificate at
  ;; any point is a statement about the constraint set the whole sweep shares,
  ;; while a row-check rejection is local to the point that returned badly.
  ;; Precedence, not plan order, decides -- so the headline is stable.
  (let [bad-point-first (run-selection [outside-constraints-osqp-result
                                        declined-osqp-result])
        certificate-first (run-selection [declined-osqp-result
                                          outside-constraints-osqp-result])]
    (is (= :constraints-unsatisfiable (:reason bad-point-first)))
    (is (= :constraints-unsatisfiable (:reason certificate-first)))
    (is (= (:message declined-osqp-result) (:message bad-point-first)))
    ;; The rejected point's own bullet is still carried.
    (is (= [boundary-row-violation] (get-in bad-point-first [:details :violations])))))

(deftest each-declined-arm-falls-back-to-its-own-copy-test
  ;; A declining result that carried no usable message of its own must not
  ;; borrow the other arm's sentence.
  (let [certificate (run-selection [(dissoc declined-osqp-result :message)])
        bad-point (run-selection [(assoc outside-constraints-osqp-result
                                         :message "   ")])]
    (is (= :constraints-unsatisfiable (:reason certificate)))
    (is (= "No portfolio can satisfy every constraint in this request at the same time."
           (:message certificate)))
    (is (= :solver-solution-failed-boundary-check (:reason bad-point)))
    (is (= "The solver returned a point that does not satisfy the constraints it was given."
           (:message bad-point)))
    (is (not (str/includes? (:message bad-point) "No portfolio")))))

(deftest per-constraint-bullets-survive-the-declined-arm-split-test
  ;; The itemised diagnostics come from `rejected-results`, which is
  ;; reason-independent: splitting the arms must not cost a single bullet.
  (let [unsatisfiable (run-selection [declined-osqp-result invalid-solution-result])
        boundary (run-selection [outside-constraints-osqp-result])
        invalid (run-selection [invalid-solution-result])
        none (run-selection [no-solution-result])]
    (is (= :constraints-unsatisfiable (:reason unsatisfiable)))
    ;; A declined result carries no violations of its own, so these bullets come
    ;; from the sibling rejection -- and the declined arm still outranks the
    ;; "solved but invalid" one, exactly as before the split.
    (is (some #(str/includes? % "turnover limit 2.0000") (bullet-messages unsatisfiable)))
    (is (some #(str/includes? % "net-exposure expected 1.0000") (bullet-messages unsatisfiable)))

    (is (= :solver-solution-failed-boundary-check (:reason boundary)))
    (is (= ["gross-exposure left its bounds by 4.00e+00."] (bullet-messages boundary)))
    (is (= [:gross-exposure]
           (mapv :constraint-code (get-in boundary [:details :violations]))))

    ;; Unchanged third arm: a :solved result whose weights fail the re-check.
    (is (= :solver-returned-invalid-solution (:reason invalid)))
    (is (= "The solver reported a solution, but it violated optimizer constraints."
           (:message invalid)))
    (is (some #(str/includes? % "net-exposure expected 1.0000") (bullet-messages invalid)))

    ;; Unchanged fourth arm, which is DEFINED by carrying no bullets:
    ;; `failure-explanation` only reaches it when the merged violation list is
    ;; empty, so there is no bullet-bearing shape that lands here.
    (is (= :solver-returned-no-solution (:reason none)))
    (is (= "The solver did not return any feasible solution." (:message none)))
    (is (= [] (bullet-messages none)))))
