(ns hyperopen.portfolio.optimizer.application.execution-order-type-test
  "The per-order routing policy is the single source for the type the Execution table
  displays AND the type the wire order carries, so its rules are pinned here directly:
  cost-aware passive protection first (a 'Recommended' that markets through a 45bp
  spread is a trust-breaking lie), then the clip-size/side rules."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.execution-order-type
             :as order-type]))

(defn- perp-buy
  [notional & [cost-bps]]
  (cond-> {:row-id "perp:X"
           :instrument-id "perp:X"
           :instrument-type :perp
           :side :buy
           :delta-notional-usd notional}
    (some? cost-bps) (assoc :cost {:slippage-bps cost-bps})))

(deftest recommend-exec-type-cost-aware-test
  (testing "a small clip whose estimated crossing cost is high posts passively"
    ;; The motivating case: an $87 buy with a 45.4bp market cost must not route :market.
    (is (= :passive (order-type/recommend-exec-type (perp-buy 87 45.4))))
    ;; Exactly at the threshold counts as high-cost (flat 25bp fallback rows included).
    (is (= :passive (order-type/recommend-exec-type (perp-buy 18 order-type/high-cost-crossing-bps))))
    (is (= :passive (order-type/recommend-exec-type (perp-buy 18 25)))))
  (testing "a small clip with a cheap crossing stays market"
    (is (= :market (order-type/recommend-exec-type (perp-buy 130 0.3))))
    (is (= :market (order-type/recommend-exec-type (perp-buy 95 8.1)))))
  (testing "a row with no cost estimate falls through to the size rules"
    (is (= :market (order-type/recommend-exec-type (perp-buy 5000))))
    (is (= :passive (order-type/recommend-exec-type (perp-buy 30000))))))

(deftest recommend-exec-type-size-and-side-rules-test
  (testing "very large clips slice over time regardless of cost"
    (is (= :twap (order-type/recommend-exec-type (perp-buy 70000 45))))
    (is (= :twap (order-type/recommend-exec-type (perp-buy 120000 0)))))
  (testing "spot sells rest as limits"
    (is (= :limit (order-type/recommend-exec-type
                   {:instrument-type :spot :side :sell :delta-notional-usd -8
                    :cost {:slippage-bps 30}}))))
  (testing "medium perp clips post passively"
    (is (= :passive (order-type/recommend-exec-type (perp-buy 25000 1))))))

(deftest high-cost-crossing-row-predicate-test
  (is (true? (order-type/high-cost-crossing-row? (perp-buy 87 45.4))))
  (is (false? (order-type/high-cost-crossing-row? (perp-buy 87 8))))
  (is (false? (order-type/high-cost-crossing-row? (perp-buy 87)))
      "no estimate is not high-cost — the size rules decide")
  (is (nil? (order-type/crossing-cost-bps (perp-buy 87 js/NaN)))
      "a non-finite estimate reads as no estimate"))

(deftest effective-type-override-precedence-test
  (let [row (perp-buy 87 45.4)]
    (testing "a per-row override beats the recommendation"
      (is (= :market (order-type/effective-type
                      {:default-order-type :recommended
                       :overrides {"perp:X" :market}}
                      row))))
    (testing ":recommended default expands through the cost-aware policy"
      (is (= :passive (order-type/effective-type
                       {:default-order-type :recommended :overrides {}}
                       row))))
    (testing "a concrete default applies verbatim"
      (is (= :twap (order-type/effective-type
                    {:default-order-type :twap :overrides {}}
                    row))))))

(def ^:private splittable-row
  ;; A large row with a real spread/impact split, as the rebalance preview stamps it:
  ;; one-shot 102 bp (2 spread + 100 impact) on $100k.
  {:row-id "perp:BIG"
   :instrument-id "perp:BIG"
   :instrument-type :perp
   :side :buy
   :delta-notional-usd 100000
   :cost {:slippage-bps 102 :estimated-slippage-usd 1020
          :spread-bps 2 :spread-usd 20
          :impact-bps 100 :impact-usd 1000
          :notional-usd 100000}})

(deftest twap-clip-schedule-follows-the-venue-slice-model-test
  ;; 30s is the venue's spacing FLOOR, not a cadence: since 2026-08-01 it stretches the
  ;; gap rather than let a clip fall under $10, so the schedule the editor renders has to
  ;; come from the row's notional as well as its runtime.
  (testing "a large clip is spacing-bound: one every 30 seconds"
    (let [schedule (order-type/twap-clip-schedule (perp-buy 100000) 20)]
      (is (= 41 (:clips schedule)))
      (is (= 30 (:interval-seconds schedule)))
      (is (true? (:notional-known? schedule)))))
  (testing "a small clip is notional-bound: fewer clips, spaced wider than 30s"
    ;; $87 over 10 minutes is 8 clips of ~$10.88, ~86s apart -- not the 21 the
    ;; notional-blind bound claims.
    (let [schedule (order-type/twap-clip-schedule (perp-buy 87) 10)]
      (is (= 8 (:clips schedule)))
      (is (< 85 (:interval-seconds schedule) 86))
      (is (true? (:notional-known? schedule)))))
  (testing "the cost map's notional wins over the row delta when both are present"
    (let [row (assoc (perp-buy 100000) :cost {:notional-usd 150})]
      (is (= 15 (:clips (order-type/twap-clip-schedule row 20))))))
  (testing "with no usable notional only the spacing bound is knowable"
    (let [schedule (order-type/twap-clip-schedule {:row-id "perp:X"} 10)]
      (is (= 21 (:clips schedule)))
      (is (= 30 (:interval-seconds schedule)))
      (is (false? (:notional-known? schedule))))))

(deftest effective-crossing-cost-is-type-aware-test
  (testing "a market override pays the full one-shot walk"
    (let [c (order-type/effective-crossing-cost
             {:default-order-type :market :overrides {} :params {}}
             splittable-row)]
      (is (true? (:crossing? c)))
      (is (= 102 (:slippage-bps c)))
      (is (= 1020 (:estimated-slippage-usd c)))))
  (testing "a resting type pays no price cost"
    (is (= {:crossing? false}
           (order-type/effective-crossing-cost
            {:default-order-type :passive :overrides {} :params {}}
            splittable-row))))
  (testing "a TWAP row pays the sliced model at its live per-row duration — the fix
            for TWAP projecting identical to Market"
    (let [selections {:default-order-type :twap :overrides {} :params {}}
          c (order-type/effective-crossing-cost selections splittable-row)]
      (is (true? (:crossing? c)))
      (is (true? (:twap-adjusted? c)))
      ;; $100k defaults to 20 minutes = 41 clips: impact 100/41 + 0.3*100*40/82
      ;; = 17.0732; + spread 2 = 19.0732 bp, far below the 102 bp one-shot.
      (is (= 41 (:suborders c)))
      (is (< 19 (:slippage-bps c) 20))
      (is (< (:slippage-bps c) 102))
      ;; a shorter per-row duration override re-prices the same row upward
      (let [shorter (order-type/effective-crossing-cost
                     (assoc selections :params {"perp:BIG" {:twap-min 5}})
                     splittable-row)]
        (is (= 11 (:suborders shorter)))
        (is (> (:slippage-bps shorter) (:slippage-bps c))))))
  (testing "TWAP on a flat (unsplittable) estimate passes through unadjusted"
    (let [c (order-type/effective-crossing-cost
             {:default-order-type :twap :overrides {} :params {}}
             (perp-buy 87 25))]
      (is (true? (:crossing? c)))
      (is (false? (:twap-adjusted? c)))
      (is (= 25 (:slippage-bps c))))))
