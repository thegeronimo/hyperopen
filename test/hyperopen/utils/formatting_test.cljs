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

(deftest format-fixed-number-test
  (testing "fixed formatting handles numbers, numeric strings, nil, and invalid values"
    (is (= "1,234.57" (fmt/format-fixed-number 1234.567 2)))
    (is (= "1,234.57" (fmt/format-fixed-number 1234.567 2 "en-US")))
    (is (= "1.2350" (fmt/format-fixed-number "1.235" 4)))
    (is (= "0.00" (fmt/format-fixed-number nil 2)))
    (is (= "0.00" (fmt/format-fixed-number "not-a-number" 2)))))

(deftest format-intl-number-test
  (testing "intl number helper supports numeric strings, locale arg, and invalid input"
    (is (= "1,234.6"
           (fmt/format-intl-number 1234.56
                                   {:maximumFractionDigits 1}
                                   "en-US")))
    (is (= "1,234.00"
           (fmt/format-intl-number "1234"
                                   {:minimumFractionDigits 2
                                    :maximumFractionDigits 2}
                                   "en-US")))
    (is (nil? (fmt/format-intl-number "bad" {:maximumFractionDigits 2})))))

(deftest format-intl-date-time-helpers-test
  (testing "date helpers return nil for invalid input and structured output for valid values"
    (is (nil? (fmt/format-intl-date-time nil {:year "numeric"})))
    (is (nil? (fmt/format-intl-date-parts nil {:year "numeric"})))
    (let [formatted (fmt/format-intl-date-time 1700000000000
                                               {:hour "2-digit"
                                                :minute "2-digit"
                                                :hour12 false}
                                               "en-US")
          parts (fmt/format-intl-date-parts 1700000000000
                                            {:year "numeric"
                                             :month "short"
                                             :day "2-digit"}
                                            "en-US")]
      (is (re-matches #"\d{2}:\d{2}" formatted))
      (is (vector? parts))
      (is (some #(= "year" (:type %)) parts)))))

(deftest format-local-date-time-test
  (testing "returns nil for nil and local datetime text with padded time components"
    (is (nil? (fmt/format-local-date-time nil)))
    (let [formatted (fmt/format-local-date-time 1700000000000)]
      (is (string? formatted))
      (is (re-matches #"\d{1,2}/\d{1,2}/\d{4} - \d{2}:\d{2}:\d{2}" formatted)))))
