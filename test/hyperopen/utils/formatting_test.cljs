(ns hyperopen.utils.formatting-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.utils.formatting :as fmt]))

(deftest price-decimals-from-raw-test
  (testing "raw precision keeps significant fractional digits and trims trailing zeros"
    (is (= 6 (fmt/price-decimals-from-raw "0.002028000")))
    (is (= 2 (fmt/price-decimals-from-raw "12")))
    (is (= 2 (fmt/price-decimals-from-raw "12.0000")))))

(deftest infer-price-decimals-test
  (testing "magnitude fallback infers more decimals for sub-cent prices"
    (is (= 2 (fmt/infer-price-decimals 1.23)))
    (is (= 6 (fmt/infer-price-decimals 0.002028)))
    (is (= 8 (fmt/infer-price-decimals 0.0000873)))))

(deftest format-trade-price-test
  (testing "raw-guided formatting preserves API precision"
    (is (= "$0.002028" (fmt/format-trade-price 0.002028 "0.002028"))))

  (testing "fallback formatting infers adaptive precision"
    (is (= "$0.002028" (fmt/format-trade-price 0.002028)))
    (is (= "$0.009900" (fmt/format-trade-price 0.0099)))
    (is (= "$0.01" (fmt/format-trade-price 0.01))))

  (testing "very small positive values render with threshold label"
    (is (= "<$0.00000001" (fmt/format-trade-price 0.0000000001))))

  (testing "larger values stay at 2 decimals"
    (is (= "$1,234.57" (fmt/format-trade-price 1234.567)))))

(deftest format-trade-price-plain-test
  (testing "plain formatter omits currency symbol"
    (is (= "0.002028" (fmt/format-trade-price-plain 0.002028 "0.002028")))
    (is (= "<0.00000001" (fmt/format-trade-price-plain 0.0000000001)))))
