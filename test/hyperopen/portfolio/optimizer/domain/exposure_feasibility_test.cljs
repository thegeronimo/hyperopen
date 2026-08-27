(ns hyperopen.portfolio.optimizer.domain.exposure-feasibility-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.exposure-feasibility
             :as feasibility]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]))

;; The presolve in domain.exposure-reachability may answer "is this provably
;; unreachable?" with a RELAXED bound - it widens the net band by pct*Ghi, the
;; largest gross the window permits, which can only under-detect. A REMEDIATION
;; has the opposite burden: the value written must leave a portfolio the solver
;; can reach. This namespace is that exact answer, and these tests pin the
;; arithmetic the panel's buttons are built from.

(defn- near?
  ([actual expected] (near? actual expected 1e-9))
  ([actual expected tolerance]
   (and (number? actual)
        (<= (js/Math.abs (- actual expected)) tolerance))))

;; The reported book: 18 long legs capped at 15.45 and two short legs whose
;; history assumptions cap the ENTIRE short side at 0.08x, under a 4.005x cap.
(def ^:private thin-short-geometry
  (feasibility/exposure-geometry
   {:lower-bounds (into (vec (repeat 18 0)) [-0.03 -0.05])
    :upper-bounds (into (vec (repeat 18 15.45)) [0 0])
    :gross-max 4.005
    :gross-capacity 278.18}))

(def ^:private pinned-net {:min 1.25 :max 1.25 :pct 0.028})

(deftest geometry-splits-the-box-into-long-and-short-capacity-test
  (is (near? (:l-min thin-short-geometry) 0))
  (is (near? (:l-max thin-short-geometry) 278.1 1e-9))
  (is (near? (:s-min thin-short-geometry) 0))
  (is (near? (:s-max thin-short-geometry) 0.08))
  (is (near? (:gross-ceiling thin-short-geometry) 4.005)
      "the encoded cap, since the box could physically reach 278.18x")
  (is (nil? (feasibility/exposure-geometry {:lower-bounds [] :upper-bounds []})))
  (is (nil? (feasibility/exposure-geometry {:lower-bounds [js/NaN]
                                            :upper-bounds [1]}))
      "a non-finite bound poisons every sum, so no geometry and no claims")
  (is (nil? (feasibility/exposure-geometry {:lower-bounds [1] :upper-bounds [0]}))
      "an inverted box belongs to the coarser single-constraint codes"))

(deftest window-solves-the-coupled-band-row-at-the-realized-gross-test
  ;; The upper band row is net - q*gross <= net-max. Substituting the smallest
  ;; net the box can hold at gross g (g - 2*S_max) and solving for g gives
  ;; g <= (net-max + 2*S_max) / (1 - q) = 1.41 / 0.972.
  (let [window (feasibility/feasible-gross-window thin-short-geometry pinned-net)]
    (is (near? (:max window) 1.4506172839506173))
    ;; The lower end is the mirrored row net + q*gross >= net-min: with no forced
    ;; shorts that is 1.25 / 1.028.
    (is (near? (:min window) 1.2159533073929961))
    (is (near? (feasibility/max-feasible-gross-floor thin-short-geometry pinned-net)
               1.4506172839506173))
    (is (< (feasibility/max-feasible-gross-floor thin-short-geometry pinned-net)
           1.52214)
        "strictly below what the band widened at the CEILING produced")))

(deftest a-floor-is-reachable-exactly-at-or-below-the-windows-upper-end-test
  (is (true? (feasibility/gross-floor-reachable? thin-short-geometry pinned-net 1.45)))
  (is (true? (feasibility/gross-floor-reachable? thin-short-geometry pinned-net 1.4506172839)))
  (is (false? (feasibility/gross-floor-reachable? thin-short-geometry pinned-net 1.46)))
  (is (false? (feasibility/gross-floor-reachable? thin-short-geometry pinned-net 3.995))
      "the floor the reported request actually set")
  (is (true? (feasibility/gross-floor-reachable? thin-short-geometry pinned-net nil))
      "no floor at all is a question worth asking: the net rows can rule out
       every gross value on their own"))

(deftest an-empty-window-says-no-gross-floor-helps-test
  ;; A zero net pin against a book with NO short capacity: net = L - S = 0 forces
  ;; L = S = 0, so every positive gross is out and clearing the floor is no fix
  ;; either. The window's ends coincide at 0 rather than vanishing...
  (let [no-shorts (feasibility/exposure-geometry {:lower-bounds [0 0]
                                                  :upper-bounds [1 1]
                                                  :gross-max 4.0})]
    (is (= {:min 0 :max 0}
           (feasibility/feasible-gross-window no-shorts {:min 0 :max 0 :pct 0})))
    (is (= 0 (feasibility/max-feasible-gross-floor no-shorts {:min 0 :max 0 :pct 0})))
    (is (false? (feasibility/gross-floor-reachable? no-shorts
                                                   {:min 0 :max 0 :pct 0}
                                                   0.01))))
  ;; ... and it vanishes outright when the two net rows cannot both hold.
  (let [forced-long (feasibility/exposure-geometry {:lower-bounds [0.5]
                                                    :upper-bounds [1]
                                                    :gross-max 1.0})]
    (is (nil? (feasibility/feasible-gross-window forced-long
                                                 {:min -2.0 :max -1.0 :pct 0}))
        "a long-only box cannot reach a negative net at any gross")))

(deftest a-full-band-never-divides-by-zero-test
  ;; q = 1 is the hard ceiling (it adds nothing beyond |net| <= gross), and it is
  ;; exactly where solving the upper row for gross divides by (1 - q).
  (let [full (feasibility/feasible-gross-window thin-short-geometry
                                                (assoc pinned-net :pct 1.0))]
    (is (near? (:max full) 4.005) "the row stops bounding gross; only the cap does")
    (is (near? (:min full) 0.625) "net + gross >= 1.25 still needs 0.625x of longs"))
  (is (= (feasibility/feasible-gross-window thin-short-geometry
                                            (assoc pinned-net :pct 1.0))
         (feasibility/feasible-gross-window thin-short-geometry
                                            (assoc pinned-net :pct 4.2)))
      "anything above 1 normalizes to 1 rather than flipping the inequality")
  ;; At q = 1 the upper row reduces to 0 <= net-max + 2*S_max, which a negative
  ;; right-hand side makes unsatisfiable at EVERY gross.
  (let [long-only (feasibility/exposure-geometry {:lower-bounds [0]
                                                  :upper-bounds [1]})]
    (is (nil? (feasibility/feasible-gross-window long-only
                                                 {:min nil :max -1.0 :pct 1.0})))))

(deftest the-smallest-band-that-rescues-a-floor-is-found-on-the-written-grid-test
  ;; 1.41 / (1 - q) >= 3.995 needs q >= 0.6471, and the control is entered in
  ;; whole percent, so 65% is both the smallest workable value AND the value
  ;; actually stored - verified at the number that will be written, not at the
  ;; exact crossing point a rounding step then moves off.
  (is (near? (feasibility/min-feasible-net-band-pct thin-short-geometry
                                                    pinned-net
                                                    3.995
                                                    1.0)
             0.65 1e-12))
  (is (true? (feasibility/gross-floor-reachable? thin-short-geometry
                                                 (assoc pinned-net :pct 0.65)
                                                 3.995)))
  (is (false? (feasibility/gross-floor-reachable? thin-short-geometry
                                                  (assoc pinned-net :pct 0.64)
                                                  3.995))
      "and 64% is genuinely not enough")
  ;; Capped below the answer, or beyond what any band can rescue, it says nothing
  ;; rather than quoting a value that does not work.
  (is (nil? (feasibility/min-feasible-net-band-pct thin-short-geometry
                                                   pinned-net
                                                   3.995
                                                   0.5)))
  (is (nil? (feasibility/min-feasible-net-band-pct thin-short-geometry
                                                   pinned-net
                                                   200.0
                                                   1.0))
      "no band rescues a floor above the gross cap")
  (is (= 0 (feasibility/min-feasible-net-band-pct thin-short-geometry
                                                  pinned-net
                                                  1.4
                                                  1.0))
      "a floor already reachable needs no band at all"))

;; --- the cash-collapse gate reads the same window ----------------------------

(deftest cash-collapse-says-so-when-no-gross-floor-fits-at-all-test
  ;; Minimum risk with only ceilings answers with cash, and the flagship fix is
  ;; "Set Gross Exposure Min". On a book whose short side is capped at zero under
  ;; a zero net pin, EVERY positive floor is rejected on read - so the gate must
  ;; not name that control at all.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:A" :market-type :perp
                              :position-side :long}
                             {:instrument-id "perp:B" :market-type :perp
                              :position-side :short}]
                  :constraints {:long-only? false
                                :include-spot? false
                                :gross-leverage 2.0
                                :max-asset-weight 1.0
                                :per-asset-overrides {"perp:B" {:max-short-weight 0}}
                                :net-exposure {:min 0 :max 0}}})
        violation (get-in (objectives/build-solver-plan
                           {:objective {:kind :minimum-variance}
                            :instrument-ids ["perp:A" "perp:B"]
                            :expected-returns [0.1 0.15]
                            :covariance [[0.04 0] [0 0.09]]
                            :encoded-constraints encoded})
                          [:details :violations 0])]
    (is (= :minimum-risk-without-exposure-floor (:code violation)))
    (is (= 0 (:max-feasible-gross-floor violation)))
    (is (nil? (:suggested-gross-floor violation)))
    (is (str/includes? (:message violation) "leaves no room for a gross floor"))
    (is (not (str/includes? (:message violation) "Set Gross Exposure Min")))))
