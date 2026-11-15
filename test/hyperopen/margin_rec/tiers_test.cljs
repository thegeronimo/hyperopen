(ns hyperopen.margin-rec.tiers-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.margin-rec.tiers :as tiers]))

(def two-tier-table
  {:description "tiered"
   :marginTiers [{:lowerBound "0" :maxLeverage "40"}
                 {:lowerBound "100000" :maxLeverage "20"}]})

(deftest tier-schedule-continuity
  (let [schedule (tiers/tier-schedule (tiers/normalize-margin-table two-tier-table))]
    (testing "rates derive from half the initial margin at each tier"
      (is (= [0.0125 0.025] (mapv :rate schedule))))
    (testing "deduction keeps the curve continuous at the boundary"
      (let [below (tiers/maintenance-margin schedule 99999.999)
            at (tiers/maintenance-margin schedule 100000)
            above (tiers/maintenance-margin schedule 100000.001)]
        (is (< (js/Math.abs (- at 1250)) 1e-6))
        (is (< (js/Math.abs (- at below)) 1e-3))
        (is (< (js/Math.abs (- at above)) 1e-3))))
    (testing "tier selection below and above the boundary"
      (is (< (js/Math.abs (- (tiers/maintenance-margin schedule 50000)
                             (* 0.0125 50000)))
             1e-9))
      (is (< (js/Math.abs (- (tiers/maintenance-margin schedule 200000)
                             (- (* 0.025 200000) 1250)))
             1e-9)))))

(deftest maintenance-fn-matches-maintenance-margin
  (let [schedule (tiers/tier-schedule (tiers/normalize-margin-table two-tier-table))
        flat (tiers/flat-schedule 10)
        mm-tiered (tiers/maintenance-fn schedule)
        mm-flat (tiers/maintenance-fn flat)]
    (doseq [notional [0 1 999 50000 100000 150000 1e6]]
      (is (< (js/Math.abs (- (mm-tiered notional)
                             (tiers/maintenance-margin schedule notional)))
             1e-9))
      (is (< (js/Math.abs (- (mm-flat notional)
                             (tiers/maintenance-margin flat notional)))
             1e-9)))))

(deftest liquidation-price-closed-form
  (testing "long, flat 40x maintenance (rate 0.0125 -> here use 20x=0.025)"
    (let [schedule (tiers/flat-schedule 20)
          p (tiers/liquidation-price schedule 1 100 10)]
      (is (< (js/Math.abs (- p (/ 90 0.975))) 1e-9))))
  (testing "short"
    (let [schedule (tiers/flat-schedule 10)
          p (tiers/liquidation-price schedule -1 100 20)]
      (is (< (js/Math.abs (- p (/ 120 1.05))) 1e-9))))
  (testing "fully collateralized long has no liquidation price"
    (is (nil? (tiers/liquidation-price (tiers/flat-schedule 20) 1 100 200))))
  (testing "liquidation price is self-consistent under a tiered schedule"
    (let [schedule (tiers/tier-schedule (tiers/normalize-margin-table two-tier-table))
          q 800
          p0 200
          e 9000
          p (tiers/liquidation-price schedule q p0 e)
          equity-at-p (+ e (* q (- p p0)))
          mm-at-p (tiers/maintenance-margin schedule (* q p))]
      (is (some? p))
      (is (< p p0))
      (is (< (js/Math.abs (- equity-at-p mm-at-p)) 1e-6)))))

(deftest calibration-recovers-rate
  (let [schedule (tiers/flat-schedule 20)
        q 2
        p0 150
        e 25
        liq (tiers/liquidation-price schedule q p0 e)
        m (tiers/calibrate-flat-rate q p0 e liq)]
    (is (< (js/Math.abs (- m 0.025)) 1e-9)))
  (testing "nonsense inputs return nil"
    (is (nil? (tiers/calibrate-flat-rate 0 100 10 90)))
    (is (nil? (tiers/calibrate-flat-rate 1 100 10 nil)))))

(deftest relative-mismatch-scales-by-distance
  (is (< (js/Math.abs (- (tiers/relative-mismatch 95 90 100) 0.5)) 1e-9))
  (is (nil? (tiers/relative-mismatch nil 90 100)))
  (testing "schedule-for prefers the table and falls back to flat"
    (is (= 2 (count (tiers/schedule-for two-tier-table 10))))
    (is (= [{:lower-bound 0 :rate 0.05 :deduction 0}]
           (tiers/schedule-for nil 10)))
    (is (nil? (tiers/schedule-for nil nil)))))
