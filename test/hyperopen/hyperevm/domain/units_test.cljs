(ns hyperopen.hyperevm.domain.units-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.hyperevm.domain.units :as units]))

(defn- big
  [text]
  (js/BigInt text))

(deftest parse-units-scales-to-base-units-test
  (is (= (big "1000000") (units/parse-units "1" 6)))
  (is (= (big "1500000") (units/parse-units "1.5" 6)))
  (is (= (big "1") (units/parse-units "0.000001" 6)))
  (is (= (big "500000") (units/parse-units ".5" 6)))
  (is (= (big "12") (units/parse-units "12." 0)))
  (is (= (big "0") (units/parse-units "0" 18)))
  (is (= (big "7") (units/parse-units "  7  " 0)) "surrounding whitespace is ignored")
  (is (= (big "10000000000000000000") (units/parse-units "10" 18)))
  (testing "18-decimal amounts past 2^53 stay exact"
    (is (= (big "948706777700642844709365332")
           (units/parse-units "948706777.700642844709365332" 18)))))

(deftest parse-units-floors-and-never-rounds-up-test
  (is (= (big "123") (units/parse-units "1.2399" 2)))
  (is (= (big "99") (units/parse-units "0.999999999" 2)))
  (is (= (big "0") (units/parse-units "0.009" 2)))
  (is (= (big "1") (units/parse-units "1.9" 0))))

(deftest parse-units-rejects-invalid-input-test
  (doseq [value [nil "" "   " "." "-1" "+1" "1e5" "1,000" "1.2.3" "abc" "0x10" 1 1.5]]
    (testing (pr-str value)
      (is (nil? (units/parse-units value 6)))))
  (doseq [decimals [-1 1.5 nil "6" 78]]
    (testing (pr-str decimals)
      (is (nil? (units/parse-units "1" decimals))))))

(deftest format-units-trims-trailing-zeros-test
  (is (= "1.5" (units/format-units (big "1500000") 6)))
  (is (= "1" (units/format-units (big "1000000") 6)))
  (is (= "0.000001" (units/format-units (big "1") 6)))
  (is (= "0" (units/format-units (big "0") 6)))
  (is (= "12" (units/format-units (big "12") 0)))
  (is (= "948706777.700642844709365332"
         (units/format-units (big "948706777700642844709365332") 18)))
  (is (= "-1.25" (units/format-units (big "-125") 2)))
  (testing "decimal and hex quantity strings are accepted"
    (is (= "1" (units/format-units "1000000" 6)))
    (is (= "0.107410883" (units/format-units "0x666f5c3" 9))))
  (testing "invalid input yields nil"
    (is (nil? (units/format-units nil 6)))
    (is (nil? (units/format-units "1.5" 6)))
    (is (nil? (units/format-units (big "1") -1)))))

(deftest parse-then-format-round-trips-test
  (doseq [[text decimals] [["1.5" 6] ["0.00002075" 8] ["100" 5] ["12.3456789" 8]]]
    (is (= text (units/format-units (units/parse-units text decimals) decimals)))))

(deftest floor-to-decimals-test
  (is (= "1.2345" (units/floor-to-decimals "1.23456789" 4)))
  (is (= "0.99" (units/floor-to-decimals "0.999999" 2)))
  (is (= "1.2" (units/floor-to-decimals "1.2000" 4)))
  (is (= "0" (units/floor-to-decimals "0.0001" 2)))
  (is (nil? (units/floor-to-decimals "abc" 2))))

(deftest units-text-test
  (is (= "1000000" (units/units-text (big "1000000"))))
  (is (= "0" (units/units-text units/zero)))
  (is (nil? (units/units-text "1000000")) "only BigInt input")
  (is (nil? (units/units-text 5))))

(deftest to-bigint-test
  (is (= (big "42") (units/to-bigint (big "42"))))
  (is (= (big "42") (units/to-bigint "42")))
  (is (= (big "107410883") (units/to-bigint "0x666f5c3")))
  (is (= (big "255") (units/to-bigint "0XFF")))
  (is (= (big "42") (units/to-bigint 42)))
  (doseq [value [nil "" "-1" "1.5" "0x" "0xzz" -1 1.5 js/Number.MAX_VALUE (big "-1") {}]]
    (testing (pr-str value)
      (is (nil? (units/to-bigint value))))))

(deftest pow10-and-bigint?-test
  (is (= (big "1") (units/pow10 0)))
  (is (= (big "1000000000000000000") (units/pow10 18)))
  (is (nil? (units/pow10 -1)))
  (is (true? (units/bigint? (big "1"))))
  (is (false? (units/bigint? 1)))
  (is (false? (units/bigint? "1"))))

(deftest compare-amounts-test
  (is (= -1 (units/compare-amounts "0.05" "0.1" 18)))
  (is (= 0 (units/compare-amounts "0.10" "0.1" 18)))
  (is (= 1 (units/compare-amounts "2" "1.999999999999999999" 18)))
  (is (nil? (units/compare-amounts "x" "1" 18))))
