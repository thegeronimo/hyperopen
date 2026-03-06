(ns hyperopen.views.portfolio.vm.volume-helpers-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.views.portfolio.vm.volume :as vm-volume]))

(def ^:private day-ms
  (* 24 60 60 1000))

(defn- approx=
  [left right]
  (< (js/Math.abs (- left right)) 1e-12))

(use-fixtures :each
  (fn [f]
    (reset! vm-volume/fills-volume-cache nil)
    (f)
    (reset! vm-volume/fills-volume-cache nil)))

(deftest volume-14d-usd-uses-windowed-fills-and-falls-back-when-times-missing-test
  (let [now (.now js/Date)
        within (- now (* 2 day-ms))
        outside (- now (* 30 day-ms))
        timed-state {:orders {:fills [{:time within :sz "2" :px "100"}
                                      {:time outside :sz "5" :px "100"}]}}
        untimed-state {:orders {:fills [{:sz "1" :px "50"}
                                        {:sz "2" :px "25"}]}}]
    (is (= 200 (vm-volume/volume-14d-usd timed-state)))
    (testing "cache hit keeps the same result for identical fill identity"
      (is (= 200 (vm-volume/volume-14d-usd timed-state))))
    (is (= 100 (vm-volume/volume-14d-usd untimed-state)))))

(deftest user-fee-volume-and-rate-helpers-follow-portfolio-contract-test
  (let [state {:portfolio {:user-fees {:dailyUserVlm [{:exchange 100
                                                       :userCross 60
                                                       :userAdd 20}
                                                      {:exchange 10
                                                       :userCross 1
                                                       :userAdd 1}]}}}]
    (is (= [{:exchange 100
             :userCross 60
             :userAdd 20}
            {:exchange 10
             :userCross 1
             :userAdd 1}]
           (vm-volume/daily-user-vlm-rows state)))
    (is (= [] (vm-volume/daily-user-vlm-rows {})))
    (is (= 80 (vm-volume/daily-user-vlm-row-volume {:exchange 100
                                                    :userCross 60
                                                    :userAdd 20})))
    (is (= 25 (vm-volume/daily-user-vlm-row-volume [1 "25"])))
    (is (= 0 (vm-volume/daily-user-vlm-row-volume :bad)))
    (is (= 80 (vm-volume/volume-14d-usd-from-user-fees state)))
    (let [standard-fees (vm-volume/fees-from-user-fees {:userCrossRate 0.0005
                                                        :userAddRate 0.0001
                                                        :activeReferralDiscount 0.1})
          negative-maker-fees (vm-volume/fees-from-user-fees {:userCrossRate 0.0005
                                                              :userAddRate -0.0001
                                                              :activeReferralDiscount 0.1})]
      (is (approx= 0.045 (:taker standard-fees)))
      (is (approx= 0.009 (:maker standard-fees)))
      (is (approx= 0.045 (:taker negative-maker-fees)))
      (is (= -0.01 (:maker negative-maker-fees))))
    (is (nil? (vm-volume/fees-from-user-fees {:userCrossRate nil
                                              :userAddRate 0.0001})))))
