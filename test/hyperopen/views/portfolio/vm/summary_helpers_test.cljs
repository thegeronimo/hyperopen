(ns hyperopen.views.portfolio.vm.summary-helpers-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.portfolio.vm.summary :as vm-summary]))

(defn- approx=
  [left right]
  (< (js/Math.abs (- left right)) 1e-12))

(deftest summary-key-normalization-covers-aliases-unknowns-and-map-filtering-test
  (testing "canonical-summary-key normalizes alias variants for both scopes"
    (is (= :three-month (vm-summary/canonical-summary-key "3M")))
    (is (= :six-month (vm-summary/canonical-summary-key "halfYear")))
    (is (= :perp-two-year (vm-summary/canonical-summary-key "perp2Y")))
    (is (= :perp-three-month (vm-summary/canonical-summary-key "perpQuarter"))))
  (testing "canonical-summary-key keeps unknown cleaned tokens and ignores blank input"
    (is (= :mystery-range (vm-summary/canonical-summary-key " Mystery Range ")))
    (is (nil? (vm-summary/canonical-summary-key "   "))))
  (testing "normalize-summary-by-key only keeps canonical map entries"
    (is (= {:month {:vlm 1}
            :perp-two-year {:vlm 2}}
           (vm-summary/normalize-summary-by-key {"month" {:vlm 1}
                                                 "perp2Y" {:vlm 2}
                                                 :ignored "bad"
                                                 "" {:vlm 3}})))))

(deftest summary-key-selection-and-fallback-order-follow-range-ordering-test
  (testing "selected-summary-key defaults unknown ranges to the month bucket"
    (is (= :perp-two-year (vm-summary/selected-summary-key :perps :two-year)))
    (is (= :month (vm-summary/selected-summary-key :all :month)))
    (is (= :month (vm-summary/selected-summary-key :all :unknown)))
    (is (= :perp-month (vm-summary/selected-summary-key :perps :unknown))))
  (testing "summary-key-candidates prefer larger windows before smaller fallback windows"
    (is (= [:six-month :one-year :two-year :all-time :three-month :month :week :day]
           (vm-summary/summary-key-candidates :all :six-month)))
    (is (= [:perp-three-month :perp-six-month :perp-one-year :perp-two-year :perp-all-time :perp-month :perp-week :perp-day]
           (vm-summary/summary-key-candidates :perps :three-month)))
    (is (= [:month :three-month :six-month :one-year :two-year :all-time :week :day]
           (vm-summary/summary-key-candidates :all :unknown)))))

(deftest selected-summary-entry-prefers-nearest-candidate-when-derived-slice-is-unavailable-test
  (let [week-entry {:vlm 7
                    :pnlHistory [[1 1] [2 2]]
                    :accountValueHistory [[1 10] [2 11]]}
        day-entry {:vlm 1
                   :pnlHistory [[1 0] [2 1]]
                   :accountValueHistory [[1 9] [2 10]]}]
    (is (= week-entry
           (vm-summary/selected-summary-entry {:week week-entry
                                               :day day-entry}
                                              :all
                                              :month)))
    (is (= week-entry
           (vm-summary/selected-summary-entry {"perpWeek" week-entry
                                               "perpDay" day-entry}
                                              :perps
                                              :month)))))

(deftest derived-summary-and-metric-helpers-cover-all-time-fallbacks-test
  (let [t0 (.getTime (js/Date. "2024-01-01T00:00:00.000Z"))
        t1 (.getTime (js/Date. "2024-03-01T00:00:00.000Z"))
        t2 (.getTime (js/Date. "2024-04-01T00:00:00.000Z"))
        t3 (.getTime (js/Date. "2024-05-15T00:00:00.000Z"))
        t4 (.getTime (js/Date. "2024-06-30T00:00:00.000Z"))
        summary-by-key (vm-summary/normalize-summary-by-key
                        {:all-time {:pnlHistory [[t0 10] [t1 20] [t2 30] [t3 45] [t4 60]]
                                    :accountValueHistory [[t0 100] [t1 110] [t2 120] [t3 130] [t4 150]]}})
        derived (vm-summary/derived-summary-entry summary-by-key :all :three-month)
        selected (vm-summary/selected-summary-entry summary-by-key :all :three-month)
        drawdown-summary {:pnlHistory [[1 10] [2 30] [3 15]]
                          :accountValueHistory [[1 100] [2 100] [3 100]]}]
    (is (= [t2 t3 t4]
           (mapv :time-ms (:accountValueHistory derived))))
    (is (= [0 15 30]
           (mapv :value (:pnlHistory derived))))
    (is (nil? (vm-summary/derived-summary-entry summary-by-key :all :month)))
    (is (= derived selected))
    (is (= 5 (vm-summary/pnl-delta drawdown-summary)))
    (is (approx= 0.15 (vm-summary/max-drawdown-ratio drawdown-summary)))))
