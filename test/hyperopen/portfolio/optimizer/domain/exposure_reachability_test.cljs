(ns hyperopen.portfolio.optimizer.domain.exposure-reachability-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.exposure-reachability
             :as reachability]))

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

(defn- unreachable
  [encoded]
  (first (filter #(= :net-unreachable-given-sides (:code %))
                 (:conditional-violations encoded))))

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
                 :per-asset-overrides {"perp:S1" {:max-short-weight 0.03}
                                       "perp:S2" {:max-short-weight 0.05}}}})

(deftest thin-short-book-under-a-gross-floor-is-infeasible-before-the-solver-test
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
    (is (near? (:gross-floor violation) 3.995))
    (is (near? (get-in violation [:gross-window :min]) 3.995))
    (is (near? (get-in violation [:gross-window :max]) 4.005))
    (is (near? (:required-short-notional violation) 1.31643))
    (is (near? (:available-short-notional violation) 0.08))
    (is (= [{:instrument-id "perp:S2" :capacity 0.05}
            {:instrument-id "perp:S1" :capacity 0.03}]
           (:binding-capacity violation)))
    (is (= 18 (:zero-capacity-count violation))
        "the eighteen long legs contribute no short capacity at all")
    (is (string? (:message violation)))
    (is (some? (re-find #"gross floor" (:message violation)))
        "the message names the floor in leverage units without extra context")))

(deftest thin-short-book-remediation-values-are-solved-not-relaxed-test
  (let [violation (unreachable (constraints/encode-constraints
                                thin-short-book-request))]
    (is (near? (:max-feasible-gross-floor violation) 1.4506172839506173 1e-9))
    (is (< (:max-feasible-gross-floor violation) 1.52214)
        "strictly below the value the relaxed slack produced")
    (is (near? (:min-feasible-net-band-pct violation) 0.65 1e-12))
    (is (nil? (:min-feasible-gross-max violation))
        "the unconsumed mirror key is gone, not left carrying the relaxed slack")))

(deftest ample-short-capacity-at-the-same-targets-stays-feasible-test
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

(deftest long-only-cap-below-target-reports-only-the-coarser-code-test
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

(deftest gross-floor-against-thin-long-capacity-reports-net-below-band-test
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
    (is (near? (:min-feasible-net-band-pct violation) 0.43 1e-12))
    (is (true? (:net-band-encodable? violation))
        "a single-signed book CAN carry a band even with none set")
    (is (= [{:instrument-id "perp:L0" :capacity 1.5}]
           (:binding-capacity violation)))))

(deftest quoted-message-numbers-never-overstate-the-traders-room-test
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

(deftest violation-says-which-remediation-controls-can-actually-move-test
  (let [violation (unreachable (constraints/encode-constraints
                                thin-short-book-request))]
    (is (false? (:long-only? violation)))
    (is (true? (:net-band-encodable? violation))
        "single-signed book, not long-only: the band CAN be encoded"))
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
