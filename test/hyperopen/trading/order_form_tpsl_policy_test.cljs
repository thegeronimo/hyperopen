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
