(ns hyperopen.portfolio.optimizer.domain.exposure-reachability-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.exposure-reachability
             :as reachability]))

;; The net band and the gross window are checked independently, so a request can
;; clear every individual check while no weight vector satisfies both at once.
;; On a fixed-sign box net = gross - 2*short exactly, which makes the joint
;; reachability question cheap to answer BEFORE the solver returns a near-zero
;; iterate that gets stamped "solved".

(defn- near?
  ([actual expected]
   (near? actual expected 1e-9))
  ([actual expected tolerance]
   (and (number? actual)
        (<= (js/Math.abs (- actual expected)) tolerance))))

(defn- codes
  [encoded]
  (mapv :code (:violations encoded)))

(defn- conditional-codes
  [encoded]
  (mapv :code (:conditional-violations encoded)))

;; The joint code rides :conditional-violations, NOT :violations, and so never
;; touches (:status encoded). It is derived from the NET rows, and Equal Risk /
;; Risk-weighted sizing deliberately encode none of those - an objective-agnostic
;; :infeasible therefore blocked them on requests they solve correctly.
;; domain.objectives owns the promotion; see objectives-test.
(defn- unreachable
  [encoded]
  (first (filter #(= :net-unreachable-given-sides (:code %))
                 (:conditional-violations encoded))))

;; ---------------------------------------------------------------------------
;; The reported bug: 18 long-side assets and 2 short-side assets whose history
;; assumptions cap the ENTIRE short book at 0.08x of NAV, under a 3.995x gross
;; floor and a 1.25x net target.
;; ---------------------------------------------------------------------------

(defn- long-leg
  [idx]
  {:instrument-id (str "perp:L" idx)
   :market-type :perp
   :shortable? true
   :position-side :long})

(defn- short-leg
  [id]
  {:instrument-id id
   :market-type :perp
   :shortable? true
   :position-side :short})

(def ^:private thin-short-book-request
  {:universe (conj (mapv long-leg (range 18))
                   (short-leg "perp:S1")
                   (short-leg "perp:S2"))
   :current-weights {}
   :constraints {:long-only? false
                 :include-spot? false
                 :max-asset-weight 15.45
                 :gross-floor 3.995
                 :gross-leverage 4.005
                 :net-exposure {:min 1.25 :max 1.25}
                 :net-band-pct 0.028
                 ;; Conservative (3%) and proxy (5%) history-assumption caps:
                 ;; the only two modelled-history assets are the short legs.
                 :per-asset-overrides {"perp:S1" {:max-short-weight 0.03}
                                       "perp:S2" {:max-short-weight 0.05}}}})

(deftest thin-short-book-under-a-gross-floor-is-infeasible-before-the-solver-test
  ;; net = gross - 2*short, so a 3.995x floor against 0.08x of short capacity
  ;; pins net at >= 3.835x while the widened band tops out at ~1.362x. Every
  ;; single-constraint check passes; only the joint one sees it.
  (let [encoded (constraints/encode-constraints thin-short-book-request)
        violation (unreachable encoded)]
    (is (= :ok (:status encoded))
        "objective-agnostic encoding never calls a net-derived block infeasible")
    (is (= [] (codes encoded))
        "no single-constraint check fires on this request")
    (is (= [:net-unreachable-given-sides] (conditional-codes encoded))
        "the joint check is the ONLY thing that sees it")
    (is (near? (get-in violation [:reachable-net :min]) 3.835))
    (is (near? (get-in violation [:reachable-net :max]) 4.005))
    (is (= {:min 1.25 :max 1.25} (:net-bounds violation)))
    (is (near? (:net-band-pct violation) 0.028))
    (is (= :net-above-band (:direction violation)))
    (is (= :gross-floor-vs-short-capacity (:driver violation)))
    (is (= :gross-exposure (:constraint-code violation))
        "the gross window drives it, so the panel highlights the gross controls")))

(deftest thin-short-book-violation-carries-the-full-remediation-payload-test
  (let [violation (unreachable (constraints/encode-constraints
                                thin-short-book-request))]
    ;; the gross window that forces it
    (is (near? (:gross-floor violation) 3.995))
    (is (near? (get-in violation [:gross-window :min]) 3.995))
    (is (near? (get-in violation [:gross-window :max]) 4.005))
    ;; the shortfall the copy-only path quotes
    (is (near? (:required-short-notional violation) 1.31643))
    (is (near? (:available-short-notional violation) 0.08))
    ;; the assets that cap the binding side, largest first
    (is (= [{:instrument-id "perp:S2" :capacity 0.05}
            {:instrument-id "perp:S1" :capacity 0.03}]
           (:binding-capacity violation)))
    (is (= 18 (:zero-capacity-count violation))
        "the eighteen long legs contribute no short capacity at all")
    (is (string? (:message violation)))
    (is (some? (re-find #"gross floor" (:message violation)))
        "the message names the floor in leverage units without extra context")))

(deftest thin-short-book-remediation-values-are-solved-not-relaxed-test
  ;; THE defect. The presolve widens the band by q*Ghi because that is a sound
  ;; NECESSARY bound for DETECTION. Reused as a remediation it wrote a floor of
  ;; (1.25 + 0.028*4.005) + 2*0.08 = 1.52214 - still infeasible, because the
  ;; percentage band binds at the gross the run REALIZES, and under a floor that
  ;; is the floor itself: G - 2*S_max <= net-max + q*G, so G <= 1.41/0.972.
  (let [violation (unreachable (constraints/encode-constraints
                                thin-short-book-request))]
    (is (near? (:max-feasible-gross-floor violation) 1.4506172839506173 1e-9))
    (is (< (:max-feasible-gross-floor violation) 1.52214)
        "strictly below the value the relaxed slack produced")
    ;; The mirrored solve for the other knob: hold the 3.995x floor and widen the
    ;; band instead. 1.41/(1-q) >= 3.995 needs q >= 0.6471, so 65% on the whole
    ;; percent grid the control is entered on.
    (is (near? (:min-feasible-net-band-pct violation) 0.65 1e-12))
    ;; Both keys are absent rather than wrong when they cannot be solved for.
    (is (nil? (:min-feasible-gross-max violation))
        "the unconsumed mirror key is gone, not left carrying the relaxed slack")))

;; ---------------------------------------------------------------------------
;; Soundness: the check may never call a FEASIBLE request infeasible.
;; ---------------------------------------------------------------------------

(deftest ample-short-capacity-at-the-same-targets-stays-feasible-test
  ;; Same 3.995x floor / 4.005x cap / 1.25x net as the bug, but the short book
  ;; can reach 6x, so gross 4.0 with L 2.625 / S 1.375 hits net 1.25 exactly.
  (let [encoded (constraints/encode-constraints
                 {:universe [(long-leg 0)
                             (long-leg 1)
                             (short-leg "perp:S1")
                             (short-leg "perp:S2")]
                  :constraints {:long-only? false
                                :include-spot? false
                                :max-asset-weight 3.0
                                :gross-floor 3.995
                                :gross-leverage 4.005
                                :net-exposure {:min 1.25 :max 1.25}
                                :net-band-pct 0.028}})]
    (is (= [0 0 -3.0 -3.0] (:lower-bounds encoded)))
    (is (= [3.0 3.0 0 0] (:upper-bounds encoded)))
    (is (= :ok (:status encoded)))
    (is (= [] (codes encoded)))
    (is (= [] (conditional-codes encoded)))))

(deftest two-sided-universe-with-a-dropped-gross-floor-is-not-flagged-test
  ;; A straddling asset makes gross non-linear, so `gross-floor-spec` DROPS the
  ;; requested floor and the solver never sees `gross >= 4.0`. Reading the
  ;; requested floor instead of the encoded one would manufacture an infeasible
  ;; here: 4.0 - 2(0.01) = 3.98x of forced net against a ~1.36x band. The
  ;; request is in fact feasible (perp:X 0, perp:L 1.25, gross 1.25 <= 4.1).
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:X"
                              :market-type :perp
                              :shortable? true}
                             (long-leg 0)]
                  :constraints {:long-only? false
                                :include-spot? false
                                :max-asset-weight 5.0
                                :gross-floor 4.0
                                :gross-leverage 4.1
                                :net-exposure {:min 1.25 :max 1.25}
                                :net-band-pct 0.028
                                :per-asset-overrides
                                {"perp:X" {:max-short-weight 0.01}}}})]
    (is (= [-0.01 0] (:lower-bounds encoded)))
    (is (= [5.0 5.0] (:upper-bounds encoded)))
    (is (nil? (:gross-floor encoded))
        "the floor is dropped for a straddling universe")
    (is (= :ok (:status encoded)))
    (is (= [] (codes encoded)))
    (is (= [] (conditional-codes encoded)))))

(deftest locked-long-weight-does-not-produce-a-false-violation-test
  ;; A lock pins lower = upper, which feeds BOTH ends of the long capacity.
  (let [encoded (constraints/encode-constraints
                 {:universe [(long-leg 0) (long-leg 1)]
                  :current-weights {"perp:L0" 0.5}
                  :constraints {:long-only? false
                                :include-spot? false
                                :max-asset-weight 2.0
                                :gross-leverage 3.0
                                :net-exposure {:min 1.0 :max 1.0}
                                :held-position-locks #{"perp:L0"}}})]
    (is (= [0.5 0] (:lower-bounds encoded)))
    (is (= [0.5 2.0] (:upper-bounds encoded)))
    (is (= :ok (:status encoded)))
    (is (= [] (codes encoded)))
    (is (= [] (conditional-codes encoded)))))

(deftest long-only-book-exactly-at-the-net-target-is-not-flagged-test
  ;; 0.55 + 0.25 + 0.2 lands EXACTLY on the net target of 1; without the float
  ;; epsilon a knife-edge cap mix reads as infeasible.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}
                             {:instrument-id "C"}]
                  :current-weights {"C" 0.2}
                  :constraints {:long-only? true
                                :include-spot? false
                                :max-asset-weight 0.6
                                :max-long-weight 0.55
                                :per-asset-overrides {"B" {:max-weight 0.25}}
                                :held-position-locks #{"C"}}})]
    (is (= [0.55 0.25 0.2] (:upper-bounds encoded)))
    (is (= :ok (:status encoded)))
    (is (= [] (codes encoded)))
    (is (= [] (conditional-codes encoded)))))

;; ---------------------------------------------------------------------------
;; Suppression: coarser single-constraint codes own the cases they cover.
;; ---------------------------------------------------------------------------

(deftest long-only-cap-below-target-reports-only-the-coarser-code-test
  ;; The joint check WOULD fire here (reachable net tops out at 0.8 against a
  ;; target of 1), so this pins the suppression, not an accident of the math.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :constraints {:long-only? true
                                :include-spot? false
                                :max-asset-weight 0.4}})]
    (is (= :infeasible (:status encoded)))
    (is (= [{:code :sum-upper-below-target
             :sum-upper 0.8
             :target-net 1}]
           (:violations encoded))
        "the whole violation vector stays exactly as it was")
    (is (= [] (conditional-codes encoded))
        "suppression still holds: the joint check speaks only when nothing else does")))

(deftest gross-floor-above-gross-max-suppresses-the-joint-code-test
  (let [encoded (constraints/encode-constraints
                 {:universe [(long-leg 0) (short-leg "perp:S1")]
                  :constraints {:long-only? false
                                :include-spot? false
                                :max-asset-weight 5.0
                                :gross-floor 3.0
                                :gross-leverage 2.0
                                :net-exposure {:min 1.25 :max 1.25}
                                :net-band-pct 0.028}})]
    (is (= :infeasible (:status encoded)))
    (is (= [:gross-floor-above-gross-max] (codes encoded))
        "an empty gross window is that code's problem, not the joint check's")
    (is (= [] (conditional-codes encoded)))))

;; ---------------------------------------------------------------------------
;; The mirror direction and the degenerate guards.
;; ---------------------------------------------------------------------------

(deftest gross-floor-against-thin-long-capacity-reports-net-below-band-test
  ;; net = 2*long - gross: a 3.5x floor with only 1.5x of long capacity caps net
  ;; at 2(1.5) - 3.5 = -0.5x, under the 1.0x minimum.
  (let [encoded (constraints/encode-constraints
                 {:universe [(long-leg 0) (short-leg "perp:S1")]
                  :constraints {:long-only? false
                                :include-spot? false
                                :max-asset-weight 4.0
                                :gross-floor 3.5
                                :gross-leverage 4.0
                                :net-exposure {:min 1.0 :max 2.0}
                                :per-asset-overrides
                                {"perp:L0" {:max-long-weight 1.5}}}})
        violation (unreachable encoded)]
    (is (= [0 -4.0] (:lower-bounds encoded)))
    (is (= [1.5 0] (:upper-bounds encoded)))
    (is (= :ok (:status encoded)))
    (is (= [:net-unreachable-given-sides] (conditional-codes encoded)))
    (is (near? (get-in violation [:reachable-net :min]) -4.0))
    (is (near? (get-in violation [:reachable-net :max]) -0.5))
    (is (= :net-below-band (:direction violation)))
    (is (= :gross-floor-vs-long-capacity (:driver violation)))
    (is (near? (:required-long-notional violation) 2.25))
    (is (near? (:available-long-notional violation) 1.5))
    (is (near? (:max-feasible-gross-floor violation) 2.0)
        "2*L_max - net-min, with no band to widen it")
    ;; The band is offered here even though none is SET: 2/(1-q) >= 3.5 needs
    ;; q >= 0.4286, so 43%. Keying this on the stored pct hid the whole
    ;; suggestion from every trader on the 0.0 default.
    (is (near? (:min-feasible-net-band-pct violation) 0.43 1e-12))
    (is (true? (:net-band-encodable? violation))
        "a single-signed book CAN carry a band even with none set")
    (is (= [{:instrument-id "perp:L0" :capacity 1.5}]
           (:binding-capacity violation)))))

(deftest quoted-message-numbers-never-overstate-the-traders-room-test
  ;; Rounded to nearest, the 3.995x floor prints as "4.00x" - HIGHER than the
  ;; value actually set, so a trader retyping the quoted number tightens the very
  ;; constraint the sentence is blaming. Every bound the copy quotes is snapped in
  ;; the direction that cannot overstate the room available: capacities and
  ;; ceilings down, forced minimums up, and the reachable end from the domain's
  ;; own inward snap rather than the exact interval.
  (let [violation (unreachable (constraints/encode-constraints
                                thin-short-book-request))
        message (:message violation)]
    (is (= (str "A 3.99x gross floor with only 0.08x of short capacity forces "
                "net to at least 3.84x, above the 1.36x the net band allows.")
           message))
    (is (nil? (re-find #"4\.00x" message))
        "the floor is never quoted above the value the trader set")
    (is (= 3.84 (get-in violation [:reachable-net-display :min]))
        "and the quoted reachable end is the inward snap, not (fmt 3.835)")))

(deftest message-drops-the-reachable-number-when-none-is-representable-test
  ;; A window narrower than 0.01x publishes no display interval, so the sentence
  ;; must not fall back to the exact bound it would round outward.
  (let [violation (first (reachability/violations
                          {:instrument-ids ["A" "B"]
                           :lower-bounds [0 0]
                           :upper-bounds [1.2345 0]
                           :net-limits {:min 5.0 :max 5.0}
                           :net-band-pct 0
                           :gross-floor 1.2342
                           :gross-max 1.2367}))]
    (is (nil? (:reachable-net-display violation)))
    (is (nil? (re-find #"at or below" (:message violation))))
    (is (some? (re-find #"net below the 5\.00x the net band requires\."
                        (:message violation))))))


;; ---------------------------------------------------------------------------
;; Remediation steering: the panel must be able to SUPPRESS its own no-ops.
;; ---------------------------------------------------------------------------

(deftest violation-says-which-remediation-controls-can-actually-move-test
  ;; Two of the panel's one-click fixes are guaranteed no-ops on the wrong
  ;; setup: under :long-only? constraints/target-net pins net at 1 and
  ;; finite-net-limits ignores :net-exposure outright, so writing net-min/net-max
  ;; cannot move the band this check tests; and net-band-spec returns nil for a
  ;; long-only OR straddling universe, so "widen net band" changes nothing and
  ;; the identical violation re-fires. These flags are what lets the panel tell.
  (let [violation (unreachable (constraints/encode-constraints
                                thin-short-book-request))]
    (is (false? (:long-only? violation)))
    (is (true? (:net-band-encodable? violation))
        "single-signed book, not long-only: the band CAN be encoded"))
  ;; A long-only book: net-min/net-max and the net band are both inert.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"} {:instrument-id "B"}]
                  :constraints {:long-only? true
                                :include-spot? false
                                :max-asset-weight 3.0
                                :gross-floor 3.0
                                :gross-leverage 4.0
                                :net-band-pct 0.2
                                :net-exposure {:min 0.5 :max 0.5}}})
        violation (unreachable encoded)]
    (is (= [:net-unreachable-given-sides] (conditional-codes encoded))
        "net pinned at 1 by long-only, but gross >= 3 forces net >= 3")
    (is (true? (:long-only? violation)))
    (is (false? (:net-band-encodable? violation))
        "net-band-spec is nil under long-only, so the band is silently dropped")
    (is (= {:min 1 :max 1} (:net-bounds violation))
        "the stored 0.5/0.5 never reaches the check; target-net wins")))

(deftest reachable-interval-is-published-snapped-inward-for-display-test
  ;; The panel quotes the interval and offers "set net to <end>". Rounded
  ;; OUTWARD, the quoted end sits back outside the reachable set and typing it
  ;; re-fires this very check, so the display copy carries its own inward-snapped
  ;; pair alongside the exact math.
  (let [violation (unreachable (constraints/encode-constraints
                                thin-short-book-request))
        exact (:reachable-net violation)
        display (:reachable-net-display violation)]
    (is (near? (:min exact) 3.835))
    (is (near? (:max exact) 4.005))
    (is (= {:min 3.84 :max 4.0} display))
    (is (>= (:min display) (:min exact)) "snapped UP, never below the floor")
    (is (<= (:max display) (:max exact)) "snapped DOWN, never above the ceiling")))

(deftest snapped-display-interval-is-nil-when-no-two-decimal-value-fits-test
  ;; A reachable window narrower than 0.01x contains no 2-decimal value at all;
  ;; printing either end would print a number that is not actually reachable.
  (is (nil? (:reachable-net-display
             (first (reachability/violations
                     {:instrument-ids ["A" "B"]
                      :lower-bounds [0 0]
                      :upper-bounds [1.2345 0]
                      :net-limits {:min 5.0 :max 5.0}
                      :net-band-pct 0
                      :gross-floor 1.2342
                      :gross-max 1.2367}))))))

(deftest reachability-check-stays-silent-on-degenerate-inputs-test
  (is (= [] (reachability/violations {:instrument-ids []
                                      :lower-bounds []
                                      :upper-bounds []
                                      :net-limits {:min 1 :max 1}}))
      "an empty box has nothing to say")
  (is (= [] (reachability/violations {:instrument-ids ["A"]
                                      :lower-bounds [js/NaN]
                                      :upper-bounds [1]
                                      :net-limits {:min 1 :max 1}}))
      "a non-finite bound would poison every sum")
  (is (= [] (reachability/violations {:instrument-ids ["A"]
                                      :lower-bounds [0]
                                      :upper-bounds [1]
                                      :net-limits {:min 1 :max 1}
                                      :gross-floor 5
                                      :gross-max 1}))
      "an inverted gross window belongs to :gross-floor-above-gross-max")
  (is (= [] (reachability/violations {:instrument-ids ["A"]
                                      :lower-bounds [0]
                                      :upper-bounds [1]
                                      :net-limits {}}))
      "no finite net bound leaves nothing to be unreachable from"))
