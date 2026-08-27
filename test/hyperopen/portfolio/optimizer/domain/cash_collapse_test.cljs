(ns hyperopen.portfolio.optimizer.domain.cash-collapse-test
  "The degenerate-minimum-risk gate and the gross floor it suggests. Split out of
  objectives-test alongside `domain.cash-collapse` itself; the gate is reached
  through `objectives/build-solver-plan`, which is the only way the engine ever
  reaches it."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.closed-form :as closed-form]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]))

;; --- degenerate Minimum risk: the all-cash optimum -------------------------
;;
;; Minimum risk minimises w'Ew with NO budget row (the exposure policy replaced
;; the textbook sum(w) = 1). When every remaining control is a CEILING, w = 0 is
;; feasible and globally optimal at exactly zero variance, so the engine used to
;; return an all-cash book and call it solved. build-solver-plan now rejects
;; that shape up front.

(def ^:private cash-collapse-ids ["perp:A" "perp:B"])

(def ^:private cash-collapse-covariance
  [[0.04 0]
   [0 0.09]])

(defn- cash-collapse-encoded
  [overrides]
  (constraints/encode-constraints
   {:universe [{:instrument-id "perp:A" :market-type :perp :position-side :long}
               {:instrument-id "perp:B" :market-type :perp :position-side :short}]
    :current-weights (:current-weights overrides)
    :constraints (merge {:long-only? false
                         :gross-leverage 2.0
                         :max-asset-weight 1.0}
                        (dissoc overrides :current-weights))}))

(defn- cash-collapse-opts
  ([overrides] (cash-collapse-opts overrides {:kind :minimum-variance}))
  ([overrides objective]
   {:objective objective
    :instrument-ids cash-collapse-ids
    :expected-returns [0.1 0.15]
    :covariance cash-collapse-covariance
    :encoded-constraints (cash-collapse-encoded overrides)}))

(defn- cash-collapse-plan
  ([overrides] (objectives/build-solver-plan (cash-collapse-opts overrides)))
  ([overrides objective]
   (objectives/build-solver-plan (cash-collapse-opts overrides objective))))

(defn- cash-collapse-violation
  [plan]
  (get-in plan [:details :violations 0]))

(deftest minimum-risk-with-only-ceilings-is-rejected-as-a-cash-collapse-test
  ;; Per-asset caps + a gross max and nothing else: cash satisfies every row.
  (let [encoded (cash-collapse-encoded {})
        plan (cash-collapse-plan {})
        violation (cash-collapse-violation plan)]
    (is (= :ok (:status encoded))
        "The constraint presolve passes — only the objective is degenerate.")
    (is (= :infeasible (:status plan)))
    (is (= :objective-collapses-to-cash (:reason plan)))
    (is (= [:minimum-risk-without-exposure-floor]
           (mapv :code (get-in plan [:details :violations]))))
    (is (= :minimum-variance (:objective-kind violation)))
    (is (nil? (:gross-floor violation)))
    (is (nil? (:net-min violation)))
    (is (nil? (:net-max violation)))
    (is (nil? (:net-band-pct violation)))
    ;; Product vocabulary: the objective is "Minimum risk" in the UI, and the
    ;; remediation names the control the user actually edits.
    (is (str/includes? (:message violation) "Minimum risk"))
    (is (str/includes? (:message violation) "Gross Exposure Min"))
    (is (str/includes? (:message violation) "Net Exposure Min"))
    (is (not (str/includes? (:message violation) "variance")))))

(deftest minimum-risk-with-a-gross-floor-still-plans-a-solve-test
  (let [plan (cash-collapse-plan {:gross-floor 1.0})]
    (is (= :ok (:status plan)))
    (is (= :single-qp (:strategy plan)))
    (is (some #(and (= :gross-floor (:code %)) (= 1.0 (:lower %)))
              (get-in plan [:problems 0 :inequalities])))))

(deftest minimum-risk-with-a-non-zero-net-equality-still-plans-a-solve-test
  (let [plan (cash-collapse-plan {:net-exposure {:min 1.0 :max 1.0}})]
    (is (= :ok (:status plan)))
    (is (= [{:code :net-exposure :coefficients [1 1] :target 1.0}]
           (get-in plan [:problems 0 :equalities])))))

(deftest minimum-risk-with-a-positive-net-floor-still-plans-a-solve-test
  (let [plan (cash-collapse-plan {:net-exposure {:min 0.5 :max 1.5}})]
    (is (= :ok (:status plan)))
    (is (some #(and (= :net-exposure (:code %)) (= 0.5 (:lower %)))
              (get-in plan [:problems 0 :inequalities])))))

(deftest minimum-risk-with-a-held-position-lock-still-plans-a-solve-test
  ;; A lock encodes as lower = upper = held weight, so it forbids cash on its
  ;; own even though nothing else bounds activity from below.
  (let [plan (cash-collapse-plan {:held-position-locks #{"perp:A"}
                                  :current-weights {"perp:A" 0.4}})]
    (is (= :ok (:status plan)))
    (is (= 0.4 (get-in plan [:problems 0 :lower-bounds 0])))
    (is (= 0.4 (get-in plan [:problems 0 :upper-bounds 0])))))

(deftest minimum-risk-with-a-zero-net-pin-is-still-a-cash-collapse-test
  ;; The subtle one: a net pin of 0 is SATISFIED by cash, so it forbids
  ;; nothing. It also keeps the closed form eligible (a net target of 0 is not
  ;; nil), and the GMV formula then returns (0/a)*u — the all-cash vector —
  ;; which passes post-validation against constraints that are all ceilings.
  ;; That is why the gate runs BEFORE closed-form-plan, not after it.
  (let [overrides {:net-exposure {:min 0 :max 0}}
        opts (cash-collapse-opts overrides)
        plan (cash-collapse-plan overrides)
        violation (cash-collapse-violation plan)]
    (is (= :ok (:status (:encoded-constraints opts))))
    (is (true? (:eligible? (closed-form/eligible? opts)))
        "Without the gate the closed-form fast path would answer with cash.")
    (is (= :infeasible (:status plan)))
    (is (= :objective-collapses-to-cash (:reason plan)))
    (is (= :minimum-risk-without-exposure-floor (:code violation)))
    ;; net = L - S, so a zero pin caps gross at twice the smaller side: the
    ;; suggested floor is measured against THAT, not against the 2.0x cap.
    (is (= 2.0 (:max-feasible-gross-floor violation)))))

(deftest cash-collapse-suggests-a-gross-floor-from-the-current-book-test
  (let [held (cash-collapse-violation
              (cash-collapse-plan {:current-weights {"perp:A" 0.8
                                                     "perp:B" -0.5}}))
        empty-book (cash-collapse-violation (cash-collapse-plan {}))]
    (is (= 1.3 (:current-gross held)))
    (is (= 1.3 (:suggested-gross-floor held)))
    (is (str/includes? (:message held) "1.30x gross"))
    ;; An empty current book has no honest floor to suggest.
    (is (= 0 (:current-gross empty-book)))
    (is (nil? (:suggested-gross-floor empty-book)))
    (is (not (str/includes? (:message empty-book) "current book")))))

(deftest cash-collapse-gate-leaves-other-objectives-alone-test
  ;; Max Sharpe sweeps return tilts, so a zero-variance point is never the
  ;; selected answer. Equal Risk pins gross to a positive exposure TARGET, and
  ;; equal-risk-presolve owns that check.
  (let [max-sharpe (cash-collapse-plan {} {:kind :max-sharpe})
        equal-risk (objectives/build-solver-plan
                    {:objective {:kind :equal-risk}
                     :instrument-ids cash-collapse-ids
                     :expected-returns [0.1 0.15]
                     :covariance [[0.04 0.01] [0.01 0.04]]
                     :encoded-constraints
                     {:status :ok
                      :long-only? false
                      :instrument-ids cash-collapse-ids
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
                      :violations []}})]
    (is (= :ok (:status max-sharpe)))
    (is (= :frontier-sweep (:strategy max-sharpe)))
    (is (= :ok (:status equal-risk)))
    (is (= :sequential-equal-risk (:strategy equal-risk)))))

;; --- the cash-collapse gate must name the REAL blocker ----------------------

(deftest cash-collapse-floor-is-clamped-to-the-reachable-net-window-test
  ;; THE defect: the suggested floor was clamped to the gross cap and the box
  ;; capacity only, ignoring the net window entirely. net = gross - 2*short, so
  ;; a book whose short side is capped at 0.1x cannot hold a net pin of 0 above
  ;; 0.2x of gross - and the flagship one-click fix wrote 1.30x, which the joint
  ;; reachability presolve rejected on the very next run. The two banners' fixes
  ;; then cycled between each other with no way out.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:A" :market-type :perp
                              :position-side :long}
                             {:instrument-id "perp:B" :market-type :perp
                              :position-side :short}]
                  :current-weights {"perp:A" 0.9 "perp:B" -0.4}
                  :constraints {:long-only? false
                                :include-spot? false
                                :gross-leverage 4.0
                                :max-asset-weight 2.0
                                :per-asset-overrides {"perp:B" {:max-short-weight 0.1}}
                                :net-exposure {:min 0 :max 0}}})
        violation (cash-collapse-violation
                   (objectives/build-solver-plan
                    {:objective {:kind :minimum-variance}
                     :instrument-ids cash-collapse-ids
                     :expected-returns [0.1 0.15]
                     :covariance cash-collapse-covariance
                     :encoded-constraints encoded}))]
    (is (= [0 -0.1] (:lower-bounds encoded)))
    (is (= [2.0 0] (:upper-bounds encoded)))
    (is (= 1.3 (:current-gross violation)) "the book itself is reported honestly")
    (is (= 0.2 (:max-feasible-gross-floor violation))
        "2 * the 0.1x short cap: a zero net pin can hold no more gross than that")
    (is (= 0.2 (:suggested-gross-floor violation))
        "clamped to the net window, NOT to the 1.30x book or the 4.00x cap")
    (is (str/includes? (:message violation) "up to 0.20x"))))

(deftest cash-collapse-floor-suggestion-is-clamped-to-the-gross-ceiling-test
  ;; Unclamped, a 1.30x book under a 1.0x ceiling suggested :gross-min 1.3, and
  ;; the next run failed :gross-floor-above-gross-max - a code with no
  ;; remediation model, so the flagship one-click fix landed the trader on a
  ;; strictly worse banner than the one it was offered from.
  (let [violation (cash-collapse-violation
                   (cash-collapse-plan {:gross-leverage 1.0
                                        :current-weights {"perp:A" 0.8
                                                          "perp:B" -0.5}}))]
    (is (= 1.3 (:current-gross violation)) "the book itself is reported honestly")
    (is (= 1.0 (:max-feasible-gross-floor violation)) "the ceiling the panel may quote")
    (is (= 1.0 (:suggested-gross-floor violation)))
    (is (str/includes? (:message violation) "up to 1.00x"))
    (is (not (str/includes? (:message violation) "1.30x"))
        "the parenthetical stops claiming the button writes the current book")))

(deftest cash-collapse-floor-suggestion-is-clamped-to-box-capacity-test
  ;; Caps of 0.25 over two assets can reach at most 0.5x gross no matter what
  ;; the ceiling says, so a 1.30x book cannot become a 1.30x floor.
  (let [violation (cash-collapse-violation
                   (cash-collapse-plan {:max-asset-weight 0.25
                                        :current-weights {"perp:A" 0.8
                                                          "perp:B" -0.5}}))]
    (is (= 0.5 (:suggested-gross-floor violation)))))
