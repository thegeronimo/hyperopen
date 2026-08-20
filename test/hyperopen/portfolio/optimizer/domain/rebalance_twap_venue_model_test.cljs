(ns hyperopen.portfolio.optimizer.domain.rebalance-twap-venue-model-test
  "The optimizer's own copy of the venue TWAP slice model. Since the 2026-08-01 upgrade
  Hyperliquid derives suborder spacing from the order's NOTIONAL as well as its runtime:
  30 seconds is a spacing FLOOR, not a cadence, and the venue stretches the gap rather
  than let a clip fall below $10. So the clip count the cost model slices against is the
  smaller of the spacing bound and notional/$10 -- with the notional-blind bound kept as
  the fail-open fallback, and the legs the optimizer actually routes as TWAPs (>= $70,000
  over 10-20 minutes) still spacing-bound and therefore priced exactly as before."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.core :as trading-core]
            [hyperopen.portfolio.optimizer.domain.rebalance :as rebalance]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(deftest twap-venue-suborder-count-reproduces-the-venue-examples-test
  (testing "$10,000 over 60 minutes is spacing-bound: 121 clips of ~$82.64 every 30s"
    (is (= 121 (rebalance/twap-venue-suborder-count 60 10000)))
    (is (= 121 (rebalance/twap-suborder-count 60))))
  (testing "$10,000 over 4 days is notional-bound: 1,000 clips of $10, not 11,521 of $0.87"
    (is (= 1000 (rebalance/twap-venue-suborder-count 5760 10000)))
    (is (= 11521 (rebalance/twap-suborder-count 5760))))
  (testing "and agrees with the trading domain's independent copy of the same mechanics"
    (is (= (trading-core/twap-venue-suborder-count 60 10000)
           (rebalance/twap-venue-suborder-count 60 10000)))
    (is (= (trading-core/twap-venue-suborder-count 5760 10000)
           (rebalance/twap-venue-suborder-count 5760 10000)))))

(deftest twap-venue-suborder-count-fails-open-to-the-spacing-bound-test
  ;; A cost map with no usable notional must price exactly as it did before the
  ;; notional-aware count landed: fall back to the spacing bound, never collapse to the
  ;; 2-clip minimum on missing data.
  (doseq [notional [nil 0 -50 js/NaN "10000" :none]]
    (is (= (rebalance/twap-suborder-count 20)
           (rebalance/twap-venue-suborder-count 20 notional))
        (str "unusable notional " (pr-str notional) " keeps the spacing bound")))
  ;; A notional far above the floor is spacing-bound too.
  (is (= (rebalance/twap-suborder-count 20)
         (rebalance/twap-venue-suborder-count 20 5000000))))

(deftest twap-venue-suborder-count-never-drops-below-two-test
  ;; A TWAP is at least two clips; a $15 order is not one clip of $10 and one of $5.
  (is (= 2 (rebalance/twap-venue-suborder-count 20 15)))
  (is (= 2 (rebalance/twap-venue-suborder-count 20 5))))

(deftest twap-cost-slices-against-the-notional-aware-clip-count-test
  ;; A $150 leg worked over 20 minutes cannot be 41 clips of $3.66 -- the venue spaces it
  ;; as 15 clips of $10. Slicing the impact 41 ways was the bug: it under-priced the leg.
  ;; 15 clips: impact 100/15 + 0.3*100*14/30 = 20.6667; + spread 2 = 22.6667 bp.
  (let [twap (rebalance/twap-cost {:spread-bps 2 :impact-bps 100 :slippage-bps 102
                                   :estimated-slippage-usd 1.53 :notional-usd 150}
                                  20)]
    (is (true? (:twap-adjusted? twap)))
    (is (= 15 (:suborders twap)))
    (is (near? 20.666666666666664 (:impact-bps twap)))
    (is (near? 22.666666666666664 (:slippage-bps twap)))
    ;; strictly dearer than the 41-clip fiction the old count priced (19.0732 bp)
    (is (> (:slippage-bps twap) 19.08))))

(deftest twap-cost-reports-the-venue-clip-count-on-unsliceable-estimates-test
  ;; A flat estimate still passes through unadjusted, but the clip count it reports (the
  ;; one the editor renders) is the venue's real one, not the 30s-floor bound.
  (let [twap (rebalance/twap-cost {:slippage-bps 25 :estimated-slippage-usd 0.04
                                   :notional-usd 150}
                                  20)]
    (is (false? (:twap-adjusted? twap)))
    (is (= 15 (:suborders twap)))
    (is (= 25 (:slippage-bps twap)))))

(deftest routed-twap-legs-are-unchanged-by-the-notional-aware-count-test
  ;; The optimizer only routes >= $70,000 as a TWAP, over 10 or 20 minutes, where the
  ;; spacing floor is the binding bound ($70,000 over 10 min: spacing 21 vs notional
  ;; 7,000). Every leg it actually routes must therefore price bit-identically to before.
  (doseq [minutes [5 10 20]
          notional [70000 100000 2500000]]
    (is (= (rebalance/twap-suborder-count minutes)
           (rebalance/twap-venue-suborder-count minutes notional))
        (str "$" notional " over " minutes "m stays spacing-bound")))
  (let [twap (rebalance/twap-cost {:spread-bps 2 :impact-bps 100 :slippage-bps 102
                                   :estimated-slippage-usd 1020 :notional-usd 100000}
                                  20)]
    (is (= 41 (:suborders twap)))
    (is (near? 17.073170731707318 (:impact-bps twap)))
    (is (near? 19.073170731707318 (:slippage-bps twap)))
    (is (near? 190.73170731707316 (:estimated-slippage-usd twap)))))
