(ns hyperopen.outcomes.domain.price-unit-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.outcomes.domain.price-unit :as price-unit]))

(def ^:private pct {:unit :percent :price-decimals 5})
(def ^:private cents {:unit :cents :price-decimals 5})

(deftest normalize-unit-defaults-to-percent-test
  (is (= :percent (price-unit/normalize-unit nil)))
  (is (= :percent (price-unit/normalize-unit "dollars")))
  (is (= :cents (price-unit/normalize-unit "cents")))
  (is (= :cents (price-unit/normalize-unit :cents))))

(deftest outcome-market-needs-matching-coin-test
  (is (true? (price-unit/outcome-market? "#30156" {:market-type :outcome :coin "#30156"})))
  (testing "a lagging outcome market for another coin never counts"
    (is (false? (price-unit/outcome-market? "BTC" {:market-type :outcome :coin "#30156"}))))
  (is (false? (price-unit/outcome-market? "BTC" {:market-type :perp :coin "BTC"})))
  (is (false? (price-unit/outcome-market? "#1" nil))))

(deftest format-price-shows-one-decimal-and-more-only-as-needed-test
  (is (= "20.9%" (price-unit/format-price 0.209 pct)))
  (is (= "20.9¢" (price-unit/format-price 0.209 cents)))
  (is (= "20.0%" (price-unit/format-price 0.2 pct)))
  (is (= "20.875%" (price-unit/format-price 0.20875 pct)))
  (is (= "20.8¢" (price-unit/format-price "0.20800" cents)) "no trailing-zero noise")
  (testing "never more digits than the tick allows"
    (is (= "20.876%" (price-unit/format-price 0.2087634 pct))))
  (is (nil? (price-unit/format-price nil pct)))
  (is (nil? (price-unit/format-price "abc" pct))))

(deftest format-ticks-uses-fewest-distinct-decimals-test
  (is (= ["20.0%" "20.5%" "21.0%"] (price-unit/format-ticks [0.2 0.205 0.21] pct)))
  (is (= ["20.85¢" "20.90¢" "20.95¢"] (price-unit/format-ticks [0.2085 0.209 0.2095] cents)))
  (is (= ["20.001%" "20.002%"] (price-unit/format-ticks [0.20001 0.20002] pct)))
  (is (= 1 (price-unit/tick-decimals [0.1 0.2 0.3] 5)))
  (is (= 2 (price-unit/tick-decimals [0.2085 0.209] 5)) "ticks 0.05 apart keep two digits")
  (is (= 3 (price-unit/tick-decimals [0.200001 0.200002] 5)) "capped at the tick's digits"))

(deftest format-delta-test
  (is (= "+0.5 pts" (price-unit/format-delta 0.005 pct)))
  (is (= "−1.25¢" (price-unit/format-delta -0.0125 cents)))
  (is (= "0.0 pts" (price-unit/format-delta 0 pct))))

(deftest max-decimals-rule-test
  (is (= 3 (price-unit/max-decimals 5)))
  (is (= 1 (price-unit/max-decimals 2)) "at least one decimal")
  (is (= 3 (price-unit/max-decimals nil)) "outcome tick by default"))

(deftest book-cents-follow-the-grouping-test
  (is (= 5 (price-unit/step-decimals 0.00001)))
  (is (= 2 (price-unit/step-decimals 0.01)))
  (is (= 0 (price-unit/step-decimals 1)))
  (is (= 3 (price-unit/cents-decimals 5)))
  (is (= 0 (price-unit/cents-decimals 2)))
  (is (= 0 (price-unit/cents-decimals 1)) "never negative")
  (is (= "22.500" (price-unit/book-cents-text "0.22500" 3)))
  (is (= "23" (price-unit/book-cents-text 0.225 0)) "rounds to the grouping")
  (is (= "0.001¢" (price-unit/step-cents-label 0.00001)))
  (is (= "0.01¢" (price-unit/step-cents-label 0.0001)))
  (is (= "1¢" (price-unit/step-cents-label 0.01))))

(deftest cents-number-text-is-what-the-ticket-takes-test
  (is (= "20.9" (price-unit/cents-number-text 0.209 {:price-decimals 5})))
  (is (= "20.875" (price-unit/cents-number-text "0.20875" {:price-decimals 5})))
  (is (nil? (price-unit/cents-number-text nil {:price-decimals 5}))))

(deftest raw-cents-decimals-keeps-every-digit-test
  (is (= 3 (price-unit/raw-cents-decimals "0.22500")))
  (is (= 4 (price-unit/raw-cents-decimals "0.000123")))
  (is (= 0 (price-unit/raw-cents-decimals "0.5")))
  (is (= 0 (price-unit/raw-cents-decimals "1")))
  (is (= "0.0123" (price-unit/book-cents-text "0.000123" (price-unit/raw-cents-decimals "0.000123")))))
