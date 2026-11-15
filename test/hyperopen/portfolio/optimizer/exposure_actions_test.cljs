(ns hyperopen.portfolio.optimizer.exposure-actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.application.constraint-profiles :as profiles]
            [hyperopen.portfolio.optimizer.domain.exposure-policy :as policy]))

(def ^:private constraints-path [:portfolio :optimizer :draft :constraints])
(def ^:private dirty-path [:portfolio :optimizer :draft :metadata :dirty?])
(def ^:private zoom-level-path [:portfolio-ui :optimizer :exposure-zoom-level])

(def ^:private clear-zoom-effect
  [:effects/save zoom-level-path nil])

(def ^:private base-constraints
  {:gross-max 2.0 :net-min 1.0 :net-max 1.0 :max-asset-weight 0.5})

(defn- state-with
  [constraints]
  {:portfolio {:optimizer {:draft {:constraints constraints}}}})

(defn- state-with-draft
  [draft]
  {:portfolio {:optimizer {:draft draft}}})

(defn- expect-write
  "The save-many effect a handler emits when it writes the whole constraints map."
  [constraints]
  [[:effects/save-many
    [[constraints-path constraints]
     [dirty-path true]]]])

(deftest exposure-point-writes-the-policy-derived-constraints-test
  (let [bounds {:left 0.0 :top 0.0 :width 100.0 :height 100.0}
        expected (policy/apply-point base-constraints {:gross-target 1.5 :net-target 0.0})]
    (is (= (expect-write expected)
           (actions/set-portfolio-optimizer-exposure-point
            (state-with base-constraints) 50.0 50.0 bounds 1)))
    (is (= {:gross-max 1.5 :net-min 0.0 :net-max 0.0 :net-band-pct 0.0
            :max-asset-weight 0.5}
           expected)
        "centre drag caps gross at 1.5 and flattens net, with no floor")))

(deftest exposure-point-hover-is-a-no-op-test
  (let [bounds {:left 0.0 :top 0.0 :width 100.0 :height 100.0}]
    (is (= [] (actions/set-portfolio-optimizer-exposure-point
               (state-with base-constraints) 50.0 50.0 bounds 0))
        "a pointer move with no button pressed must not rewrite the draft")))

(deftest equal-risk-exposure-point-writes-gross-only-and-preserves-net-policy-test
  (let [constraints {:gross-max 2.0
                     :net-min -0.4
                     :net-max 0.6
                     :net-band-pct 0.15
                     :max-asset-weight 0.5}
        bounds {:left 0.0 :top 0.0 :width 100.0 :height 100.0}
        out (actions/set-portfolio-optimizer-exposure-point
             (state-with-draft {:objective {:kind :equal-risk}
                                :constraints constraints})
             10.0 25.0 bounds 1 4.0 3.0 1)
        written (-> out first second first second)]
    (is (= 3.0 (:gross-max written))
        "Y-axis movement remains the Equal Risk gross target control.")
    (is (not= (:gross-max constraints) (:gross-max written)))
    (is (= (select-keys constraints [:net-min :net-max :net-band-pct])
           (select-keys written [:net-min :net-max :net-band-pct]))
        "X-axis movement is ignored for Equal Risk so stored net policy survives objective switching.")
    (is (= [dirty-path true] (-> out first second second)))))

(deftest exposure-interactions-pin-the-render-level-test
  (let [bounds {:left 0.0 :top 0.0 :width 100.0 :height 100.0}]
    (testing "a drag at a baked level stores that level"
      (let [out (actions/set-portfolio-optimizer-exposure-point
                 (state-with base-constraints) 50.0 50.0 bounds 1 5.0 3.0 1)]
        (is (= [:effects/save zoom-level-path 1] (second out)))))
    (testing "a drag at the already-stored level adds no redundant save"
      (let [state (assoc-in (state-with base-constraints) zoom-level-path 1)
            out (actions/set-portfolio-optimizer-exposure-point
                 state 50.0 50.0 bounds 1 5.0 3.0 1)]
        (is (= 1 (count out)) "only the constraints save-many is emitted")))
    (testing "an overflow-scale drag (nil level) never pins"
      (let [out (actions/set-portfolio-optimizer-exposure-point
                 (state-with base-constraints) 50.0 50.0 bounds 1 60.0 20.0 nil)]
        (is (= 1 (count out)))))
    (testing "a band change pins the baked level too"
      (let [out (actions/set-portfolio-optimizer-exposure-band
                 (state-with base-constraints) :net "0.25" 2)]
        (is (= [:effects/save zoom-level-path 2] (second out)))))))

(deftest exposure-band-writes-one-axis-test
  (testing "net band stores the percentage-of-gross fraction, leaving the target alone"
    (let [expected (policy/apply-band base-constraints :net 0.25)]
      (is (= (expect-write expected)
             (actions/set-portfolio-optimizer-exposure-band
              (state-with base-constraints) :net "0.25")))
      (is (= 1.0 (:net-min expected)))
      (is (= 1.0 (:net-max expected)))
      (is (= 0.25 (:net-band-pct expected)))))
  (testing ":net-pct is the same band with the value in percent (UI controls)"
    (is (= (actions/set-portfolio-optimizer-exposure-band
            (state-with base-constraints) :net "0.25")
           (actions/set-portfolio-optimizer-exposure-band
            (state-with base-constraints) :net-pct "25"))))
  (testing "an unknown axis or non-number is a no-op"
    (is (= [] (actions/set-portfolio-optimizer-exposure-band
               (state-with base-constraints) :sideways "0.1")))
    (is (= [] (actions/set-portfolio-optimizer-exposure-band
               (state-with base-constraints) :net "not-a-number")))))

(deftest exposure-preset-writes-the-merged-preset-test
  (let [expected (policy/apply-preset base-constraints :long-bias)]
    (is (= (conj (expect-write expected) clear-zoom-effect)
           (actions/apply-portfolio-optimizer-exposure-preset
            (state-with base-constraints) :long-bias))
        "a preset writes the merged constraints and clears the stored zoom so the pad re-fits")
    (is (= :long-bias (policy/active-preset expected))))
  (is (= [] (actions/apply-portfolio-optimizer-exposure-preset
             (state-with base-constraints) :nonsense))
      "an unknown preset is a no-op"))

(deftest exposure-zoom-level-validates-and-saves-test
  (is (= [[:effects/save zoom-level-path 2]]
         (actions/set-portfolio-optimizer-exposure-zoom-level (state-with base-constraints) 2)))
  (is (= [[:effects/save zoom-level-path 0]]
         (actions/set-portfolio-optimizer-exposure-zoom-level (state-with base-constraints) 0)))
  (testing "out-of-range, fractional, or non-numeric levels are no-ops"
    (is (= [] (actions/set-portfolio-optimizer-exposure-zoom-level
               (state-with base-constraints) 9)))
    (is (= [] (actions/set-portfolio-optimizer-exposure-zoom-level
               (state-with base-constraints) 1.5)))
    (is (= [] (actions/set-portfolio-optimizer-exposure-zoom-level
               (state-with base-constraints) -1)))
    (is (= [] (actions/set-portfolio-optimizer-exposure-zoom-level
               (state-with base-constraints) "sideways")))))

(deftest save-constraint-default-emits-persist-effect-test
  (is (= [[:effects/save-portfolio-optimizer-constraint-default]]
         (actions/save-portfolio-optimizer-constraint-default (state-with base-constraints)))
      "the action is pure; the effect reads the draft, stamps time, and persists"))

(deftest apply-constraint-default-writes-remembered-constraints-test
  (let [remembered {:gross-max 1.5 :net-min 0.0 :net-max 0.0 :net-band-pct 0.0
                    :max-asset-weight 0.3}
        universe [{:instrument-id "perp:BTC"} {:instrument-id "perp:ETH"}]
        uk (profiles/universe-key universe)
        state {:portfolio {:optimizer
                           {:draft {:constraints base-constraints :universe universe}
                            :constraint-profiles nil}}}]
    (testing "no profile for this universe ⇒ no-op"
      (is (= [] (actions/apply-portfolio-optimizer-constraint-default state))))
    (testing "a saved profile is written to the draft (and the stored zoom clears)"
      (let [state* (assoc-in state [:portfolio :optimizer :constraint-profiles]
                             {uk {:universe-key uk :controls remembered}})]
        (is (= (conj (expect-write remembered) clear-zoom-effect)
               (actions/apply-portfolio-optimizer-constraint-default state*)))))))

(deftest reset-constraints-to-system-restores-defaults-test
  (let [out (actions/reset-portfolio-optimizer-constraints-to-system
             (state-with {:gross-min 1.5 :gross-max 1.6 :net-min 1.4 :net-max 1.6
                          :max-asset-weight 0.2}))
        written (-> out first second first second)]
    (is (= 2.0 (:gross-max written)) "system default gross ceiling restored")
    (is (not (contains? written :gross-min)) "system default has no gross floor")
    (is (= 0.5 (:max-asset-weight written)))
    (is (= [dirty-path true] (-> out first second second)))
    (is (= clear-zoom-effect (second out)) "reset also clears the stored zoom")))
