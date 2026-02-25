(ns hyperopen.trading.order-form-tpsl-policy-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.trading.order-form-tpsl-policy :as policy]))

(deftest normalize-unit-supports-keywords-strings-and-booleans-test
  (is (= :usd (policy/normalize-unit :usd)))
  (is (= :roe-percent (policy/normalize-unit :percent)))
  (is (= :roe-percent (policy/normalize-unit :roe-percent)))
  (is (= :position-percent (policy/normalize-unit :position-percent)))
  (is (= :usd (policy/normalize-unit "usd")))
  (is (= :roe-percent (policy/normalize-unit "percent")))
  (is (= :roe-percent (policy/normalize-unit "roe-percent")))
  (is (= :position-percent (policy/normalize-unit "position-percent")))
  (is (= :usd (policy/normalize-unit true)))
  (is (= :roe-percent (policy/normalize-unit false)))
  (is (= :usd (policy/normalize-unit :unknown))))

(deftest tpsl-offset-display-and-trigger-conversion-usd-mode-test
  (is (= "20"
         (policy/offset-display-from-trigger {:trigger "110"
                                              :baseline 100
                                              :size 2
                                              :leverage 20
                                              :inverse false
                                              :unit :usd})))
  (is (= "110"
         (policy/trigger-from-offset-input {:raw-input "20"
                                            :baseline 100
                                            :size 2
                                            :leverage 20
                                            :inverse false
                                            :unit :usd})))
  (is (= "90"
         (policy/trigger-from-offset-input {:raw-input "20"
                                            :baseline 100
                                            :size 2
                                            :leverage 20
                                            :inverse true
                                            :unit :usd}))))

(deftest tpsl-offset-display-and-trigger-conversion-roe-percent-mode-test
  (is (= "20"
         (policy/offset-display-from-trigger {:trigger "101"
                                              :baseline 100
                                              :size 2
                                              :leverage 20
                                              :inverse false
                                              :unit :roe-percent})))
  (is (= "99"
         (policy/trigger-from-offset-input {:raw-input "20"
                                            :baseline 100
                                            :size 2
                                            :leverage 20
                                            :inverse true
                                            :unit :roe-percent}))))

(deftest tpsl-offset-display-and-trigger-conversion-position-percent-mode-test
  (is (= "2"
         (policy/offset-display-from-trigger {:trigger "102"
                                              :baseline 100
                                              :size 2
                                              :leverage 20
                                              :inverse false
                                              :unit :position-percent})))
  (is (= "98"
         (policy/trigger-from-offset-input {:raw-input "2"
                                            :baseline 100
                                            :size 2
                                            :leverage 20
                                            :inverse true
                                            :unit :position-percent}))))

(deftest tpsl-offset-display-avoids-float-precision-regression-test
  (is (= "1"
         (policy/offset-display-from-trigger {:trigger "1.3333333"
                                              :baseline 1
                                              :size 3
                                              :leverage 20
                                              :inverse false
                                              :unit :usd}))))

(deftest tpsl-trigger-from-offset-input-uses-five-decimals-test
  (is (= "0.63012"
         (policy/trigger-from-offset-input {:raw-input "1"
                                            :baseline 0.62012
                                            :size 100
                                            :leverage 20
                                            :inverse false
                                            :unit :usd})))
  (is (= "99.55556"
         (policy/trigger-from-offset-input {:raw-input "20"
                                            :baseline 100
                                            :size 2
                                            :leverage 45
                                            :inverse true
                                            :unit :roe-percent}))))

(deftest tpsl-offset-display-preserves-raw-input-when-math-roundtrip-matches-trigger-test
  (let [params {:baseline 0.62095
                :size 102
                :leverage 20
                :inverse false
                :unit :usd}
        trigger (policy/trigger-from-offset-input (assoc params :raw-input "1"))]
    (is (= "0.63075" trigger))
    (is (= "0.99"
           (policy/offset-display-from-trigger (assoc params :trigger trigger))))
    (is (= "1"
           (policy/offset-display (assoc params
                                         :offset-input "1"
                                         :trigger trigger))))))

(deftest tpsl-offset-display-falls-back-to-derived-value-when-raw-input-is-stale-test
  (let [params {:baseline 100
                :size 2
                :leverage 20
                :inverse false
                :unit :usd}
        derived (policy/offset-display-from-trigger (assoc params :trigger "120"))]
    (is (= derived
           (policy/offset-display (assoc params
                                         :offset-input "1"
                                         :trigger "120"))))))

(deftest tpsl-offset-display-preserves-raw-input-until-trigger-is-available-test
  (is (= "20"
         (policy/offset-display {:offset-input "20"
                                 :trigger ""
                                 :baseline 100
                                 :size nil
                                 :leverage 20
                                 :inverse false
                                 :unit :usd})))
  (is (= "20"
         (policy/offset-display {:offset-input "20"
                                 :trigger ""
                                 :baseline 100
                                 :size 2
                                 :leverage 20
                                 :inverse false
                                 :unit :usd}))))
