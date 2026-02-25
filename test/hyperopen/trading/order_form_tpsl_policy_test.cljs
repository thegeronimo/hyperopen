(ns hyperopen.trading.order-form-tpsl-policy-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.trading.order-form-tpsl-policy :as policy]))

(deftest normalize-unit-supports-keywords-strings-and-booleans-test
  (is (= :usd (policy/normalize-unit :usd)))
  (is (= :percent (policy/normalize-unit :percent)))
  (is (= :usd (policy/normalize-unit "usd")))
  (is (= :percent (policy/normalize-unit "percent")))
  (is (= :usd (policy/normalize-unit true)))
  (is (= :percent (policy/normalize-unit false)))
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

(deftest tpsl-offset-display-and-trigger-conversion-percent-mode-test
  (is (= "20"
         (policy/offset-display-from-trigger {:trigger "101"
                                              :baseline 100
                                              :size 2
                                              :leverage 20
                                              :inverse false
                                              :unit :percent})))
  (is (= "99"
         (policy/trigger-from-offset-input {:raw-input "20"
                                            :baseline 100
                                            :size 2
                                            :leverage 20
                                            :inverse true
                                            :unit :percent}))))

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
                                            :unit :percent}))))
