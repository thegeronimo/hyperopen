(ns hyperopen.domain.trading.fees-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.fees :as fees]))

(defn- approx=
  [a b]
  (<= (js/Math.abs (- a b)) 0.000001))

(deftest quote-fees-requires-user-rate-inputs-test
  (is (nil? (fees/quote-fees nil {:market-type :perp})))
  (is (nil? (fees/quote-fees {}
                           {:market-type :spot
                            :stable-pair? true}))))

(deftest quote-fees-applies-spot-stable-pair-and-baseline-test
  (let [quote (fees/quote-fees {:userSpotCrossRate 0.0003
                                :userSpotAddRate 0.00012
                                :activeReferralDiscount 0.1
                                :activeStakingDiscount {:discount 0.25}}
                               {:market-type :spot
                                :stable-pair? true
                                :deployer-fee-scale 0
                                :growth-mode? false
                                :extra-adjustment? false})]
    (is (approx= 0.0054 (get-in quote [:effective :taker])))
    (is (approx= 0.00216 (get-in quote [:effective :maker])))
    (is (approx= 0.04 (get-in quote [:baseline :taker])))
    (is (approx= 0.016 (get-in quote [:baseline :maker])))))

(deftest quote-fees-applies-perp-growth-and-special-adjustment-test
  (testing "positive maker fees scale with deployer and growth multipliers"
    (let [quote (fees/quote-fees {:userCrossRate 0.0005
                                  :userAddRate 0.0002
                                  :activeReferralDiscount 0
                                  :activeStakingDiscount {:discount 0.2}}
                                 {:market-type :perp
                                  :deployer-fee-scale 1.2
                                  :growth-mode? true
                                  :extra-adjustment? false})]
      (is (approx= 0.012 (get-in quote [:effective :taker])))
      (is (approx= 0.0048 (get-in quote [:effective :maker])))))

  (testing "negative maker rebates and taker fees use special adjustment branch"
    (let [quote (fees/quote-fees {:userCrossRate 0.0005
                                  :userAddRate -0.0001
                                  :activeReferralDiscount 0.2
                                  :activeStakingDiscount {:discount 0.2}}
                                 {:market-type :perp
                                  :deployer-fee-scale 0.5
                                  :growth-mode? false
                                  :extra-adjustment? true})]
      (is (approx= 0.052 (get-in quote [:effective :taker])))
      (is (approx= -0.0133333333 (get-in quote [:effective :maker])))
      (is (approx= 0.0625 (get-in quote [:baseline :taker])))
      (is (approx= -0.01 (get-in quote [:baseline :maker]))))))
