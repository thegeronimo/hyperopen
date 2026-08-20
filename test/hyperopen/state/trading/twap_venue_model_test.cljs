(ns hyperopen.state.trading.twap-venue-model-test
  "Pins the venue's TWAP slicing model.

   Before 2026-08-01 Hyperliquid worked every twapOrder as one clip each 30 seconds, so the
   clip count was purely a function of runtime. It now derives the spacing from BOTH the
   runtime and the order's notional: it clips as often as the 30-second floor allows, but
   spaces clips further apart rather than let one fall below $10.

   The two examples in the venue's own documentation are the reference points these tests
   pin, because reproducing both is what distinguishes the real rule from a guess:

     a $10,000 order over 1 hour  -> ~121 sub-orders of ~$83, sent every 30 seconds
     a $10,000 order over 4 days  -> ~1,000 sub-orders of ~$10, sent roughly every 6 minutes"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.core :as core]))

(deftest venue-suborder-count-reproduces-the-documented-examples-test
  (testing "$10,000 over 1 hour is spacing-bound: 121 clips of ~$83 every 30s"
    (let [count* (core/twap-venue-suborder-count 60 10000)]
      (is (= 121 count*))
      (is (= 30 (core/twap-interval-seconds-for-count 60 count*)))
      (is (< 82 (/ 10000 count*) 83))))

  (testing "$10,000 over 4 days is notional-bound: 1000 clips of $10 every ~6 minutes"
    (let [minutes (* 4 24 60)
          count* (core/twap-venue-suborder-count minutes 10000)
          interval (core/twap-interval-seconds-for-count minutes count*)]
      (is (= 1000 count*))
      (is (= 10 (/ 10000 count*)))
      (is (< 340 interval 350))
      (is (< 5.5 (/ interval 60) 6.0)))))

(deftest venue-suborder-count-takes-the-smaller-of-the-two-bounds-test
  (testing "the spacing floor binds when the order is large for its runtime"
    (is (= (core/twap-suborder-count 10)
           (core/twap-venue-suborder-count 10 1000000))))

  (testing "the notional floor binds when the order is small for its runtime"
    ;; The defect case: $200 over 24 hours. The old fixed-cadence model implied 2,881 clips
    ;; of $0.07 and the form refused to submit; the venue works it as 20 clips of $10.
    (is (= 2881 (core/twap-suborder-count 1440)))
    (is (= 20 (core/twap-venue-suborder-count 1440 200)))
    (is (= 10 (/ 200 (core/twap-venue-suborder-count 1440 200)))))

  (testing "never fewer than two clips, so the interval divisor is never zero"
    (is (= 2 (core/twap-venue-suborder-count 60 5)))
    (is (some? (core/twap-suborder-interval-seconds 60 5)))))

(deftest venue-suborder-count-fails-open-on-unusable-inputs-test
  (is (nil? (core/twap-venue-suborder-count 60 nil)))
  (is (nil? (core/twap-venue-suborder-count 60 0)))
  (is (nil? (core/twap-venue-suborder-count nil 10000)))
  (is (nil? (core/twap-suborder-interval-seconds nil 10000))))

(deftest twap-runtime-window-spans-five-minutes-to-seven-days-test
  (is (= 5 core/twap-min-runtime-minutes))
  (is (= 10080 core/twap-max-runtime-minutes))
  (is (true? (core/valid-twap-runtime? 5)))
  (is (true? (core/valid-twap-runtime? 10080)))
  (is (not (core/valid-twap-runtime? 4)))
  (is (not (core/valid-twap-runtime? 10081))))

(deftest twap-runtime-splits-and-recombines-across-days-hours-minutes-test
  (testing "a total splits into days, hours and minutes"
    (is (= {:days 0 :hours 0 :minutes 0} (core/split-twap-total-minutes nil)))
    (is (= {:days 0 :hours 1 :minutes 35} (core/split-twap-total-minutes 95)))
    (is (= {:days 1 :hours 9 :minutes 20} (core/split-twap-total-minutes 2000)))
    (is (= {:days 7 :hours 0 :minutes 0} (core/split-twap-total-minutes 10080))))

  (testing "the split shape recombines to the same total"
    (doseq [total [0 95 2000 10080]]
      (is (= total (core/twap-total-minutes (core/split-twap-total-minutes total))))))

  (testing "a legacy draft with no days or hours treats :minutes as the whole runtime"
    (is (= 95 (core/twap-total-minutes {:minutes 95}))))

  (testing "a draft with hours but no days still works"
    (is (= 95 (core/twap-total-minutes {:hours 1 :minutes 35})))))

(deftest twap-order-notional-and-clip-size-test
  (is (= 10000 (core/twap-order-notional "100" "100")))
  (is (nil? (core/twap-order-notional "100" nil)))
  (is (nil? (core/twap-order-notional "0" "100")))

  (testing "clip size uses the venue count when a reference price is known"
    ;; 100 units at $100 is $10,000 over 4 days -> 1000 clips.
    (is (= (/ 100 1000) (core/twap-suborder-size "100" (* 4 24 60) "100"))))

  (testing "with no reference price it falls back to the notional-blind spacing bound"
    (is (= (/ 100 (core/twap-suborder-count 60))
           (core/twap-suborder-size "100" 60)))))

(deftest twap-minimum-order-notional-is-one-hundred-test
  (is (= 100 core/twap-min-order-notional))
  ;; The per-clip floor still exists, but the venue maintains it by stretching the
  ;; interval, so it is no longer a reason to reject an order.
  (is (= 10 core/twap-min-suborder-notional)))
