(ns hyperopen.outcomes.domain.fees-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.outcomes.domain.fees :as fees]))

(defn- cents [x] (/ (js/Math.round (* 100 x)) 100))

(deftest board-4c-worked-numbers-test
  (let [rates (fees/spot-rates nil)
        bought (fees/buy {:shares 122 :price 0.81902})
        win (fees/settlement {:shares 122 :cost (:cost bought) :rates rates})
        sale (fees/sell {:shares 122 :price 0.81886 :rates rates})
        merged (fees/merge-pairs {:pairs 40 :rates rates})]
    (is (= 99.92 (cents (:cost bought))))
    (is (= 0 (:fee bought)))
    (is (= 122 (:gross win)))
    (is (= 0.17 (cents (:fee win))))
    (is (= 121.83 (cents (:net win))))
    (is (= 21.91 (cents (:profit win))))
    (is (= 99.9 (cents (:gross sale))))
    (is (= 0.14 (cents (:fee sale))))
    (is (= 99.76 (cents (:net sale))))
    (is (= 40 (:gross merged)))
    (is (= 0.06 (cents (:fee merged))))
    (is (= 39.94 (cents (:net merged))))))

(deftest rates-test
  (is (= :base-tier (:source (fees/spot-rates nil))))
  (is (< (js/Math.abs (- 0.0014 (fees/exit-rate (fees/spot-rates nil)))) 1e-12))
  (is (< (js/Math.abs (- 0.0008 (fees/exit-rate (fees/spot-rates nil) {:resting? true}))) 1e-12))
  (let [r (fees/spot-rates {:userSpotCrossRate "0.0005" :userSpotAddRate "0.0002"
                            :activeReferralDiscount "0.1"})]
    (is (= :user-fees (:source r)))
    (is (< (js/Math.abs (- 0.00045 (:taker r))) 1e-12)))
  (is (= :base-tier (:source (fees/rates-from-state {}))))
  (is (= :user-fees (:source (fees/rates-from-state
                              {:portfolio {:user-fees {:userSpotCrossRate "0.0007"
                                                       :userSpotAddRate "0.0004"}}}))))
  (is (= "0.14%" (fees/rate-text 0.0014)))
  (is (= "0.08%" (fees/rate-text 0.0008))))

(deftest losing-side-and-split-test
  (is (zero? (:fee (fees/settlement {:shares 50 :payout-per-share 0}))))
  (let [r (fees/settlement {:shares 50 :payout-per-share 1 :cost 26})]
    (is (= 0.07 (cents (:fee r))))
    (is (= 49.93 (cents (:net r))))
    (is (= 23.93 (cents (:profit r))))))

(deftest resting-sale-uses-maker-test
  (is (= 0.08 (cents (:fee (fees/sell {:shares 100 :price 1 :resting? true}))))))

(deftest unconfirmed-venues-test
  (testing "other venues return no number"
    (doseq [v ["skew" "txyz" "fox"]]
      (is (false? (:fee-known? (fees/sell {:shares 1 :price 0.5 :venue v}))))
      (is (false? (:fee-known? (fees/settlement {:shares 1 :venue v}))))
      (is (false? (:fee-known? (fees/merge-pairs {:pairs 1 :venue v}))))
      (is (false? (:fee-known? (fees/buy {:shares 1 :price 0.5 :venue v}))))))
  (is (true? (:fee-known? (fees/sell {:shares 1 :price 0.5 :venue "out"})))))
