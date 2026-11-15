(ns hyperopen.portfolio.optimizer.domain.exposure-policy-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.exposure-policy :as policy]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]))

(def ^:private default-constraints
  {:long-only? false
   :include-spot? false
   :gross-max 2.0
   :net-min 1.0
   :net-max 1.0
   :max-asset-weight 0.5})

(deftest constraints->policy-derives-default-targets-and-zero-bands-test
  (let [{:keys [gross-target gross-band net-target net-band-pct]}
        (policy/constraints->policy default-constraints)]
    (is (= 2.0 gross-target) "gross target is the ceiling when there is no floor")
    (is (= 0.0 gross-band) "no gross floor ⇒ zero gross band")
    (is (= 1.0 net-target))
    (is (= 0.0 net-band-pct) "no :net-band-pct key ⇒ zero percentage band")))

(deftest constraints->policy-handles-a-seeded-gross-floor-test
  (let [{:keys [gross-target gross-band net-target net-band-pct]}
        (policy/constraints->policy {:gross-min 1.91 :gross-max 1.92
                                     :net-min 1.31 :net-max 1.42
                                     :net-band-pct 0.05})]
    (is (= 1.915 gross-target))
    (is (= 0.005 gross-band))
    (is (= 1.365 net-target) "net target is the min/max midpoint")
    (is (= 0.05 net-band-pct) "the percentage band reads straight from :net-band-pct")))

(deftest policy->constraints-round-trips-and-preserves-no-floor-test
  (testing "zero gross band clears the floor (dissoc, not nil)"
    (let [out (policy/policy->constraints default-constraints
                                          (policy/constraints->policy default-constraints))]
      (is (not (contains? out :gross-min))
          "a zero gross band must DISSOC :gross-min so the solver sees no floor")
      (is (= 2.0 (:gross-max out)))
      (is (= 1.0 (:net-min out)))
      (is (= 1.0 (:net-max out)))))
  (testing "a positive gross band writes a floor and round-trips"
    (let [seeded {:gross-min 1.91 :gross-max 1.92 :net-min 1.31 :net-max 1.42}
          out (policy/policy->constraints seeded (policy/constraints->policy seeded))]
      (is (= 1.91 (:gross-min out)))
      (is (= 1.92 (:gross-max out)))
      (is (= 1.31 (:net-min out)))
      (is (= 1.42 (:net-max out))))))

(deftest apply-point-moves-targets-and-keeps-bands-test
  (let [seeded {:gross-min 1.0 :gross-max 2.0 :net-min 0.8 :net-max 1.2 :max-asset-weight 0.5}
        out (policy/apply-point seeded {:gross-target 2.5 :net-target 0.5})]
    (is (= 3.0 (:gross-max out)) "gross-max = target 2.5 + band 0.5")
    (is (= 2.0 (:gross-min out)) "gross-min = target 2.5 - band 0.5")
    (is (= 0.3 (:net-min out)) "net-min = target 0.5 - band 0.2")
    (is (= 0.7 (:net-max out)) "net-max = target 0.5 + band 0.2")
    (is (= 0.5 (:max-asset-weight out)) "unrelated keys are preserved")))

(deftest apply-band-widens-one-axis-and-clamps-test
  (let [out (policy/apply-band default-constraints :net 0.25)]
    (is (= 1.0 (:net-min out)) "the percentage band never moves the net target")
    (is (= 1.0 (:net-max out)))
    (is (= 0.25 (:net-band-pct out)) "the net band is stored as a decimal fraction of gross")
    (is (not (contains? out :gross-min)) "net band change leaves gross floor absent"))
  (testing "a positive gross band introduces a floor"
    (let [out (policy/apply-band default-constraints :gross 0.1)]
      (is (= 1.9 (:gross-min out)))
      (is (= 2.1 (:gross-max out)))))
  (testing "the net band clamps to max-net-band-pct (100%), never below 0%"
    (is (= policy/max-net-band-pct
           (:net-band-pct (policy/apply-band default-constraints :net 5.0))))
    (is (= 0.0 (:net-band-pct (policy/apply-band default-constraints :net -0.2))))))

(deftest point->targets-maps-fractions-and-ignores-hover-test
  (let [bounds {:left 0.0 :top 0.0 :width 100.0 :height 100.0}]
    (testing "centre of the pad while dragging"
      (is (= {:gross-target 1.5 :net-target 0.0}
             (policy/point->targets {:client-x 50.0 :client-y 50.0
                                     :bounds bounds :buttons 1}))))
    (testing "top-right corner: max gross, max long"
      (is (= {:gross-target 3.0 :net-target 2.0}
             (policy/point->targets {:client-x 100.0 :client-y 0.0
                                     :bounds bounds :buttons 1}))))
    (testing "gross is clamped to at least |net|"
      (is (= {:gross-target 2.0 :net-target 2.0}
             (policy/point->targets {:client-x 100.0 :client-y 100.0
                                     :bounds bounds :buttons 1}))))
    (testing "no pressed button ⇒ nil (a hover, not a drag)"
      (is (nil? (policy/point->targets {:client-x 50.0 :client-y 50.0
                                        :bounds bounds :buttons 0}))))
    (testing "degenerate bounds ⇒ nil"
      (is (nil? (policy/point->targets {:client-x 50.0 :client-y 50.0
                                        :bounds {:left 0 :top 0 :width 0 :height 0}
                                        :buttons 1}))))))

(deftest fit-level-frames-policy-without-headroom-test
  (testing "small policy + no current exposure ⇒ the floor level"
    (is (= 0 (policy/fit-level {:gross-target 2.0 :gross-band 0.0
                                :net-target 1.0 :net-band 0.0}))))
  (testing "a target dragged exactly to the visible max still fits its own level (no headroom),
            so a drag can never force a re-fit"
    (is (= 0 (policy/fit-level {:gross-target 3.0 :gross-band 0.0 :net-target 0.0})))
    (is (= 0 (policy/fit-level {:gross-target 2.5 :gross-band 0.5 :net-target 0.0}))))
  (testing "a gross need beyond a level steps to the next paired level"
    (is (= 1 (policy/fit-level {:gross-target 4.0 :gross-band 0.0})))
    (is (= 2 (policy/fit-level {:gross-target 6.0 :gross-band 0.5}))))
  (testing "the current portfolio exposure also expands the frame"
    (is (= 2 (policy/fit-level {:gross-target 2.0 :current-gross 8.0}))))
  (testing "a wide long/short bias raises the level through the paired net extent"
    (is (= 1 (policy/fit-level {:net-target 2.5 :net-band 0.0}))))
  (testing "beyond the largest level nothing fits (the overflow case)"
    (is (nil? (policy/fit-level {:gross-target 55.0 :gross-band 0.0})))))

(deftest render-axis-is-fixed-and-only-widens-test
  (testing "no stored zoom ⇒ the fit level's paired axes"
    (let [{:keys [axis level fit-level zoom-in-level zoom-out-level]}
          (policy/render-axis {:gross-target 2.0 :net-target 1.0} nil)]
      (is (= {:gross-max 3.0 :net-extent 2.0} axis))
      (is (= 0 level))
      (is (= 0 fit-level))
      (is (nil? zoom-in-level) "already at the tightest level that fits")
      (is (= 1 zoom-out-level))))
  (testing "a stored zoom widens the view and exposes a zoom-in step back toward fit"
    (let [{:keys [axis level zoom-in-level zoom-out-level]}
          (policy/render-axis {:gross-target 2.0 :net-target 1.0} 3)]
      (is (= {:gross-max 20.0 :net-extent 10.0} axis))
      (is (= 3 level))
      (is (= 2 zoom-in-level))
      (is (= 4 zoom-out-level))))
  (testing "the largest level disables zooming out"
    (is (nil? (:zoom-out-level (policy/render-axis {:gross-target 2.0 :net-target 1.0}
                                                   policy/max-zoom-level)))))
  (testing "a stored zoom below fit is ignored — zooming in can never clip the band box"
    (let [{:keys [axis level zoom-in-level]}
          (policy/render-axis {:gross-target 6.0 :gross-band 0.5 :net-target 1.0} 0)]
      (is (= {:gross-max 10.0 :net-extent 5.0} axis))
      (is (= 2 level))
      (is (nil? zoom-in-level))))
  (testing "an unfittable policy renders a computed overflow scale with zoom disabled"
    (let [{:keys [axis level zoom-in-level zoom-out-level]}
          (policy/render-axis {:gross-target 55.0 :net-target 0.0} 2)]
      (is (= 60.0 (:gross-max axis)))
      (is (nil? level))
      (is (nil? zoom-in-level))
      (is (nil? zoom-out-level)))))

(deftest point->targets-honors-the-baked-scale-test
  (let [bounds {:left 0.0 :top 0.0 :width 100.0 :height 100.0}]
    (testing "top of a 10x-scaled pad yields gross 10x, not the 3x floor"
      (is (= 10.0 (:gross-target (policy/point->targets
                                  {:client-x 50.0 :client-y 0.0 :bounds bounds :buttons 1
                                   :gross-axis-max 10.0 :net-axis-extent 2.0})))))
    (testing "a missing scale falls back to the floor"
      (is (= 3.0 (:gross-target (policy/point->targets
                                 {:client-x 50.0 :client-y 0.0 :bounds bounds :buttons 1})))))))

(deftest point->targets-keeps-the-band-box-inside-the-view-test
  (let [bounds {:left 0.0 :top 0.0 :width 100.0 :height 100.0}]
    (testing "a positive gross band lowers the reachable gross ceiling"
      (is (= 2.5 (:gross-target (policy/point->targets
                                 {:client-x 50.0 :client-y 0.0 :bounds bounds :buttons 1
                                  :gross-band 0.5})))))
    (testing "a positive percentage net band pulls the reachable net edges inward by
              pct · gross so the wedge stays in view"
      (is (= 1.25 (:net-target (policy/point->targets
                                {:client-x 100.0 :client-y 0.0 :bounds bounds :buttons 1
                                 :net-band-pct 0.25}))))
      (is (= -1.25 (:net-target (policy/point->targets
                                 {:client-x 0.0 :client-y 0.0 :bounds bounds :buttons 1
                                  :net-band-pct 0.25})))))
    (testing "band 0 keeps the full range reachable"
      (is (= 3.0 (:gross-target (policy/point->targets
                                 {:client-x 50.0 :client-y 0.0 :bounds bounds :buttons 1
                                  :gross-band 0.0})))))
    (testing "net reach also respects the gross reach: an oversized gross band (advanced raw
              fields are not capped at max-band) cannot let the gross ≥ |net| lift push
              target + band past the axis max"
      (let [out (policy/point->targets {:client-x 100.0 :client-y 0.0 :bounds bounds :buttons 1
                                        :gross-band 1.5})]
        (is (= 1.5 (:net-target out)))
        (is (= 1.5 (:gross-target out)))))))

(deftest presets-apply-and-are-detected-test
  (testing "a ceiling-only preset applies its partial and clears the gross floor"
    (let [out (policy/apply-preset {:gross-min 1.5 :gross-max 1.5} :balanced)]
      (is (not (contains? out :gross-min)))
      (is (= 2.0 (:gross-max out)))
      (is (= 1.0 (:net-min out)))
      (is (= 1.0 (:net-max out)))
      (is (= 0.5 (:max-asset-weight out)))))
  (testing "active-preset detects an applied preset and falls back to :custom"
    (is (= :balanced (policy/active-preset (policy/apply-preset {} :balanced))))
    (is (= :conservative (policy/active-preset (policy/apply-preset {} :conservative))))
    (is (= :long-bias (policy/active-preset (policy/apply-preset {} :long-bias))))
    (is (= :balanced (policy/active-preset default-constraints))
        "the system default constraints ARE the Balanced preset by design")
    (is (= :custom (policy/active-preset {:gross-max 2.5 :net-min 0.4 :net-max 0.6
                                          :max-asset-weight 0.5}))
        "values that match no preset read as :custom")
    (is (= :custom (policy/active-preset (assoc (policy/apply-preset {} :balanced)
                                                :gross-min 1.0)))
        "a gross floor disqualifies a ceiling-only preset match")))

(deftest plotting-helpers-place-markers-test
  (let [marker (policy/target-marker {:gross-target 1.5 :net-target 0.0})]
    (is (= 0.5 (:x marker)) "net 0 is centre-x")
    (is (= 0.5 (:y marker)) "gross 1.5 of 3.0 is centre-y"))
  (let [rect (policy/band-rect {:gross-target 1.5 :gross-band 0.0
                                :net-target 0.0 :net-band-pct 0.25})]
    (is (= 0.0 (:h rect)) "zero gross band ⇒ flat box")
    (is (< 0.0 (:w rect)) "net band ⇒ box has width"))
  (is (nil? (policy/current-exposure-marker {:gross nil :net 1.0})))
  (is (some? (policy/current-exposure-marker {:gross 1.8 :net 1.2}))))

(deftest engine-constraints-policy-derives-the-same-targets-test
  (is (= (policy/constraints->policy {:gross-max 2.0 :net-min 1.0 :net-max 1.0})
         (policy/engine-constraints->policy {:gross-leverage 2.0
                                             :net-exposure {:min 1.0 :max 1.0}}))
      "zero band: gross target IS the ceiling")
  (is (= (policy/constraints->policy {:gross-max 3.0 :gross-min 1.0
                                      :net-min -0.5 :net-max 1.5})
         (policy/engine-constraints->policy {:gross-leverage 3.0
                                             :gross-floor 1.0
                                             :net-exposure {:min -0.5 :max 1.5}}))
      "banded: targets are the midpoints, never the ceilings")
  (is (= {:gross-target 2.0 :gross-band 1.0 :net-target 0.5 :net-band-pct 0.1}
         (policy/engine-constraints->policy {:gross-leverage 3.0
                                             :gross-floor 1.0
                                             :net-band-pct 0.1
                                             :net-exposure {:min -0.5 :max 1.5}}))))

(deftest net-band-edges-scale-with-gross-and-clip-naturally-test
  (testing "the permitted net range is target ± pct·gross"
    (is (= {:left -0.75 :right 0.75} (policy/net-band-edges 0.0 0.05 15.0)))
    (is (= {:left -0.1 :right 0.1} (policy/net-band-edges 0.0 0.05 2.0)))
    (is (= {:left 0.0 :right 2.0} (policy/net-band-edges 1.0 0.1 10.0))
        "a nonzero target keeps the band centered on the target"))
  (testing "the |net| ≤ gross relationship clips the edges"
    (is (= {:left -1.0 :right 1.0} (policy/net-band-edges 0.0 1.0 1.0)))
    (is (= {:left -0.5 :right 1.0} (policy/net-band-edges 0.5 1.0 1.0))
        "a right edge past gross clamps to gross")
    (is (= {:left 1.0 :right 1.0} (policy/net-band-edges 1.5 0.5 1.0))
        "a band entirely past gross collapses to the reachable edge"))
  (testing "zero gross collapses to a point (never divides)"
    (is (= {:left 0.0 :right 0.0} (policy/net-band-edges 0.0 0.05 0.0)))))

(deftest band-wedge-renders-sloped-boundaries-test
  (let [axis {:gross-max 20.0 :net-extent 10.0}
        {:keys [points]} (policy/band-wedge {:gross-target 10.0 :gross-band 5.0
                                             :net-target 0.0 :net-band-pct 0.1}
                                            axis)
        by-y (group-by second points)
        width-at (fn [y] (let [xs (map first (get by-y y))]
                           (- (apply max xs) (apply min xs))))
        y-top (apply min (map second points))
        y-bot (apply max (map second points))]
    (is (= 4 (count points)))
    (is (< (width-at y-bot) (width-at y-top))
        "the wedge is wider at the higher-gross edge — sloped, not vertical")
    (is (< (js/Math.abs (- 0.15 (width-at y-top))) 1e-9))
    (is (< (js/Math.abs (- 0.05 (width-at y-bot))) 1e-9)))
  (testing "zero band degenerates to a vertical segment at the target"
    (let [{:keys [points]} (policy/band-wedge {:gross-target 2.0 :gross-band 0.5
                                               :net-target 1.0 :net-band-pct 0.0})]
      (is (every? #(= (ffirst points) (first %)) points)))))

(deftest conservative-preset-ships-a-gross-floor-at-its-own-gross-figure-test
  (let [out (policy/apply-preset {:gross-min 3.0 :gross-max 3.0} :conservative)]
    (is (= 1.0 (:gross-min out)) "the preset's own floor overwrites a stale one")
    (is (= 1.0 (:gross-max out)))
    (is (= 0.0 (:net-min out)))
    (is (= 0.0 (:net-max out)))
    (is (= 0.25 (:max-asset-weight out))))
  (is (= :conservative (policy/active-preset (policy/apply-preset {} :conservative)))
      "the floor is part of the match, so the chip still reads Conservative")
  (is (= :custom (policy/active-preset (dissoc (policy/apply-preset {} :conservative)
                                               :gross-min)))
      "a Conservative-shaped policy with the floor stripped is no longer Conservative")
  (is (= 1.0 (:gross-target (policy/constraints->policy
                             (policy/apply-preset {} :conservative))))
      "gross-min = gross-max keeps the pad marker exactly where it was")
  (is (= 0.0 (:gross-band (policy/constraints->policy
                           (policy/apply-preset {} :conservative))))))

(deftest conservative-gross-pin-survives-a-no-op-pad-round-trip-test
  (let [conservative (policy/apply-preset {} :conservative)
        {:keys [gross-target net-target]} (policy/constraints->policy conservative)
        round-trip (policy/apply-point conservative {:gross-target gross-target
                                                     :net-target net-target})]
    (is (= 1.0 (:gross-min round-trip))
        "a no-op drag must not delete the preset's gross floor")
    (is (= 1.0 (:gross-max round-trip)))
    (is (= :conservative (policy/active-preset round-trip))
        "and the chip must still read Conservative, never flip to Custom"))
  (testing "a net-band edit is the same zero-gross-band round trip"
    (let [out (policy/apply-band (policy/apply-preset {} :conservative) :net 0.05)]
      (is (= 1.0 (:gross-min out)))
      (is (= 1.0 (:gross-max out)))
      (is (= 0.05 (:net-band-pct out)))))
  (testing "the pin travels with the target instead of vanishing mid-drag"
    (let [out (policy/apply-point (policy/apply-preset {} :conservative)
                                  {:gross-target 2.0 :net-target 0.0})]
      (is (= 2.0 (:gross-min out)) "gross stays pinned, at the new target")
      (is (= 2.0 (:gross-max out)))
      (is (= :custom (policy/active-preset out))
          "moved off the preset's 1.0x, so the chip is honestly Custom"))))

(deftest ceiling-only-preset-stays-floorless-through-the-same-round-trip-test
  (let [balanced (policy/apply-preset {} :balanced)
        {:keys [gross-target net-target]} (policy/constraints->policy balanced)
        round-trip (policy/apply-point balanced {:gross-target gross-target
                                                 :net-target net-target})]
    (is (not (contains? round-trip :gross-min))
        "no floor to preserve ⇒ a zero band still means ceiling-only")
    (is (= 2.0 (:gross-max round-trip)))
    (is (= :balanced (policy/active-preset round-trip)))
    (is (not (contains? (policy/apply-band balanced :net 0.1) :gross-min))
        "and a band edit does not conjure one either")))

(deftest gross-band-widened-from-conservative-yields-a-coherent-pair-test
  (let [out (policy/apply-band (policy/apply-preset {} :conservative) :gross 0.25)]
    (is (= 0.75 (:gross-min out)) "the floor drops half a band below the 1.0x target")
    (is (= 1.25 (:gross-max out)) "and the ceiling rises half a band above it")
    (is (< (:gross-min out) (:gross-max out)))
    (is (= {:gross-target 1.0 :gross-band 0.25}
           (select-keys (policy/constraints->policy out) [:gross-target :gross-band]))
        "which reads back as the same target with a wider band")
    (testing "collapsing that POSITIVE band back to zero is still how 'no floor' is said"
      (let [collapsed (policy/apply-band out :gross 0.0)]
        (is (not (contains? collapsed :gross-min)))
        (is (= 1.0 (:gross-max collapsed)))))))

(deftest a-deliberately-cleared-gross-floor-stays-cleared-test
  (let [cleared (assoc (policy/apply-preset {} :conservative) :gross-min nil)
        out (policy/apply-point cleared {:gross-target 1.0 :net-target 0.0})]
    (is (not (contains? out :gross-min)) "a nil floor is not a pin; the key is dropped")
    (is (= 1.0 (:gross-max out)))))

(def ^:private sided-universe
  [{:instrument-id "perp:A" :market-type :perp :shortable? true :position-side :long}
   {:instrument-id "perp:B" :market-type :perp :shortable? true :position-side :long}
   {:instrument-id "perp:C" :market-type :perp :shortable? true :position-side :short}
   {:instrument-id "perp:D" :market-type :perp :shortable? true :position-side :short}])

(defn- engine-constraints
  "The draft -> engine key rename `application.request-builder/normalize-constraints`
  performs before the solver sees a request: :gross-max -> :gross-leverage,
  :gross-min -> :gross-floor, :net-min/:net-max -> :net-exposure {:min :max}.
  Mirrored here so this test exercises the shipped preset values end to end
  without reaching across into the application layer."
  [draft]
  (cond-> {:long-only? false
           :include-spot? false
           :max-asset-weight (:max-asset-weight draft)
           :net-band-pct (:net-band-pct draft)
           :net-exposure {:min (:net-min draft) :max (:net-max draft)}}
    (contains? draft :gross-max) (assoc :gross-leverage (:gross-max draft))
    (contains? draft :gross-min) (assoc :gross-floor (:gross-min draft))))

(defn- preset-plan
  [draft]
  (objectives/build-solver-plan
   {:objective {:kind :minimum-variance}
    :instrument-ids ["perp:A" "perp:B" "perp:C" "perp:D"]
    :expected-returns [0.10 0.12 0.08 0.09]
    :covariance [[0.04 0.001 0.001 0.001]
                 [0.001 0.05 0.001 0.001]
                 [0.001 0.001 0.06 0.001]
                 [0.001 0.001 0.001 0.07]]
    :encoded-constraints (constraints/encode-constraints
                          {:universe sided-universe
                           :constraints (engine-constraints draft)})}))

(deftest conservative-preset-no-longer-collapses-minimum-risk-to-cash-test
  (let [draft (policy/apply-preset {} :conservative)
        plan (preset-plan draft)]
    (is (= :ok (:status plan)))
    (is (not= :objective-collapses-to-cash (:reason plan)))
    (is (some #(and (= :gross-floor (:code %)) (= 1.0 (:lower %)))
              (get-in plan [:problems 0 :inequalities]))
        "the floor reaches the solver as a signed-linear row"))
  (let [ceiling-only (dissoc (policy/apply-preset {} :conservative) :gross-min)
        plan (preset-plan ceiling-only)]
    (is (= :infeasible (:status plan)))
    (is (= :objective-collapses-to-cash (:reason plan)))))
