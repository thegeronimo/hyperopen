(ns hyperopen.websocket.formatting-coverage-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.utils.formatting :as fmt]))

(defn- with-fake-date!
  [minutes seconds f]
  (let [orig-date (.-Date js/globalThis)
        fake-date (fn []
                    (js-obj "getMinutes" (fn [] minutes)
                            "getSeconds" (fn [] seconds)))]
    (set! (.-Date js/globalThis) fake-date)
    (try
      (f)
      (finally
        (set! (.-Date js/globalThis) orig-date)))))

(deftest price-decimals-and-inference-branches-test
  (testing "raw precision parsing handles empty, integer, trailing zero, and capped precision cases"
    (is (nil? (fmt/price-decimals-from-raw nil)))
    (is (nil? (fmt/price-decimals-from-raw "   ")))
    (is (= 2 (fmt/price-decimals-from-raw "12")))
    (is (= 2 (fmt/price-decimals-from-raw "12.0000")))
    (is (= 5 (fmt/price-decimals-from-raw "0.00012000")))
    (is (= 2 (fmt/price-decimals-from-raw "1.2300e-5")))
    (is (= 8 (fmt/price-decimals-from-raw "0.123456789123"))))

  (testing "magnitude inference handles invalid, zero, normal, and tiny values"
    (is (nil? (fmt/infer-price-decimals nil)))
    (is (nil? (fmt/infer-price-decimals "bad")))
    (is (= 2 (fmt/infer-price-decimals 0)))
    (is (= 2 (fmt/infer-price-decimals 1.23)))
    (is (= 6 (fmt/infer-price-decimals "0.002028")))
    (is (= 8 (fmt/infer-price-decimals 0.0000000001)))))

(deftest trade-price-formatter-branches-test
  (testing "currency formatter handles nil, invalid, threshold, and locale formatting"
    (is (nil? (fmt/format-trade-price nil)))
    (is (nil? (fmt/format-trade-price "bad")))
    (is (= "$0.002028" (fmt/format-trade-price 0.002028 "0.002028000")))
    (is (= "<$0.00000001" (fmt/format-trade-price 0.0000000001)))
    (is (= "$0.01" (fmt/format-trade-price 0.01)))
    (is (= "$1,234.50" (fmt/format-trade-price 1234.5))))

  (testing "plain formatter and delta wrapper share adaptive behavior"
    (is (nil? (fmt/format-trade-price-plain nil)))
    (is (= "<0.00000001" (fmt/format-trade-price-plain 0.0000000001)))
    (is (= "1,234.50" (fmt/format-trade-price-plain 1234.5)))
    (is (= "0.10" (fmt/format-trade-price-delta 0.1 "0.1000")))
    (is (nil? (fmt/format-trade-price-delta nil)))))

(deftest fixed-number-and-numeric-helper-coverage-test
  (testing "fixed number formatting normalizes input values and decimal arguments"
    (is (= "1,234.57" (fmt/format-fixed-number 1234.567 2)))
    (is (= "1.2350" (fmt/format-fixed-number "1.235" "4")))
    (is (= "0" (fmt/format-fixed-number nil -2)))
    (is (= "0.00" (fmt/format-fixed-number "not-a-number" "bad"))))

  (testing "basic number, percentage, and currency helpers"
    (is (= "12.35" (fmt/format-number 12.345 2)))
    (is (= "0.00" (fmt/format-number 0 2)))
    (is (nil? (fmt/format-number "12.3" 2)))
    (is (= "1.2" (fmt/safe-to-fixed 1.234 1)))
    (is (= "0.0" (fmt/safe-to-fixed "1.234" 1)))
    (is (= "$1,234.50" (fmt/format-currency 1234.5)))
    (is (nil? (fmt/format-currency nil)))
    (is (= "$1,235" (fmt/format-large-currency 1234.5)))
    (is (nil? (fmt/format-large-currency nil)))
    (is (= "1.23%" (fmt/format-percentage 1.234)))
    (is (= "1.2%" (fmt/format-percentage 1.234 1)))
    (is (nil? (fmt/format-percentage nil)))
    (is (= 87.6 (fmt/annualized-funding-rate 0.01)))
    (is (nil? (fmt/annualized-funding-rate nil))))

  (testing "safe-number and open-interest helpers handle invalid and missing values"
    (is (= 42 (fmt/safe-number 42)))
    (is (= 42.5 (fmt/safe-number "42.5")))
    (is (= 0 (fmt/safe-number "bad")))
    (is (= 0 (fmt/safe-number nil)))
    (is (= 20 (fmt/calculate-open-interest-usd 10 2)))
    (is (nil? (fmt/calculate-open-interest-usd nil 2)))
    (is (= "$2,000" (fmt/format-open-interest-usd 1000 2)))
    (is (nil? (fmt/format-open-interest-usd nil 2)))))

(deftest date-time-formatter-coverage-test
  (testing "pad helper zero-pads single-digit values"
    (is (= "04" (fmt/pad2 4)))
    (is (= "10" (fmt/pad2 10))))

  (testing "date and time formatters return nil for nil input"
    (is (nil? (fmt/format-local-date-time nil)))
    (is (nil? (fmt/format-local-time-hh-mm-ss nil)))
    (is (nil? (fmt/format-local-datetime-input-value nil))))

  (testing "date and time formatters produce expected shapes"
    (let [ts 1700000000000]
      (is (re-matches #"\d{1,2}/\d{1,2}/\d{4} - \d{2}:\d{2}:\d{2}"
                      (fmt/format-local-date-time ts)))
      (is (re-matches #"\d{2}:\d{2}:\d{2}"
                      (fmt/format-local-time-hh-mm-ss ts)))
      (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}"
                      (fmt/format-local-datetime-input-value ts))))))

(deftest countdown-and-time-coverage-test
  (testing "time formatter handles nil and hh:mm:ss composition"
    (is (nil? (fmt/format-time nil)))
    (is (= "00:00:00" (fmt/format-time 0)))
    (is (= "01:01:01" (fmt/format-time 3661))))

  (testing "funding countdown handles exact-minute and partial-minute branches"
    (with-fake-date! 15 60
      #(is (= "00:45:00" (fmt/format-funding-countdown))))
    (with-fake-date! 15 5
      #(is (= "00:44:55" (fmt/format-funding-countdown))))))
