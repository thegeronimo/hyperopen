(ns hyperopen.domain.trading.precision-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.precision :as p]))

(defn- ds [d] (when d (p/decimal->string d)))
(defn- pd [s] (p/parse-decimal s))

(defn- qp [raw side opts]
  (ds (p/quantize-price (pd raw) side opts)))

(deftest parse-decimal-test
  (doseq [bad ["1e5" "" " " "1,000" "abc" "1.2.3" "NaN" "." "-" nil 5]]
    (is (nil? (pd bad)) (str "rejects " (pr-str bad))))
  (is (= "1.5" (ds (pd "+1.50"))))
  (is (= "0.5" (ds (pd ".5"))))
  (is (= "5" (ds (pd "5."))))
  (is (= "-2.25" (ds (pd "-2.25")))))

(deftest decimal->string-test
  (is (= "1.5" (ds (pd "1.500"))))
  (is (= "0" (ds (pd "0.000"))))
  (is (= "0" (ds (pd "-0"))))
  (is (= "-0.05" (ds (pd "-0.050"))))
  (is (= "100" (ds (pd "100")))))

(deftest number->decimal-test
  (is (= "0.0000001" (ds (p/number->decimal 1e-7))))
  (is (= "76.4" (ds (p/number->decimal 76.4))))
  (is (= "0.30000000000000004" (ds (p/number->decimal (+ 0.1 0.2)))))
  (is (= "1500000000000000000000" (ds (p/number->decimal 1.5e21))))
  (is (= "-0.25" (ds (p/number->decimal -0.25))))
  (is (nil? (p/number->decimal js/NaN)))
  (is (nil? (p/number->decimal js/Infinity)))
  (is (nil? (p/number->decimal "1"))))

(deftest arithmetic-test
  (is (= "76.4" (ds (p/mul (pd "80") (p/sub (pd "1") (p/mul (pd "0.75") (pd "0.06")))))))
  (is (= "0.3" (ds (p/add (pd "0.1") (pd "0.2")))))
  (is (= -1 (p/cmp (pd "1.10") (pd "1.2"))))
  (is (= 0 (p/cmp (pd "1.10") (pd "1.1"))))
  (is (= 1 (p/cmp (pd "2") (pd "1.999"))))
  (is (p/zero-dec? (pd "0.00")))
  (is (p/pos-dec? (pd "0.01")))
  (is (p/neg-dec? (pd "-0.01")))
  (is (= "3" (ds (p/dec-max (pd "3") (pd "2.5")))))
  (is (= "2.5" (ds (p/dec-min (pd "3") (pd "2.5")))))
  (is (= "-5" (ds (p/neg (p/int->decimal 5)))))
  (is (= "5" (ds (p/abs-dec (pd "-5"))))))

(deftest quantize-modes-test
  (let [d (pd "-1.25")]
    (is (= "-1.3" (ds (p/quantize d 1 :floor))))
    (is (= "-1.2" (ds (p/quantize d 1 :ceil))))
    (is (= "-1.3" (ds (p/quantize d 1 :half-up))))
    (is (= "-1.2" (ds (p/quantize d 1 :down)))))
  (is (= "1.3" (ds (p/quantize (pd "1.25") 1 :half-up))))
  (is (= "1.2" (ds (p/quantize (pd "1.25") 1 :floor))))
  (is (= "1.250" (p/to-fixed (pd "1.25") 3)))
  (is (= "2.00" (p/to-fixed (pd "1.999") 2)))
  (is (= "-0.01" (p/to-fixed (pd "-0.005") 2)))
  (is (= "0.00" (p/to-fixed (pd "0.001") 2))))

(deftest divide-test
  (is (= "0.3333" (ds (p/divide (pd "1") (pd "3") 4 :floor))))
  (is (= "0.3334" (ds (p/divide (pd "1") (pd "3") 4 :ceil))))
  (is (= "-0.3334" (ds (p/divide (pd "1") (pd "-3") 4 :floor))))
  (is (nil? (p/divide (pd "1") (pd "0") 4 :floor))))

(deftest lots-test
  (let [r (p/decimal->lots (pd "0.29") 2)]
    (is (= "29" (.toString (:lots r))))
    (is (= "0.29" (ds (p/lots->decimal (:lots r) 2)))))
  (is (= {:error :not-lot-multiple :suggested-down "0.29" :suggested-up "0.3"}
         (p/decimal->lots (pd "0.295") 2)))
  (is (nil? (p/decimal->lots (pd "0.29") nil)))
  (is (nil? (p/decimal->lots (pd "0.29") 9)))
  (is (nil? (p/decimal->lots (pd "-1") 2))))

(deftest price-legality-test
  (is (= 7 (p/max-price-decimals :spot 1)))
  (is (= 6 (p/max-price-decimals :perp 0)))
  (is (= 0 (p/max-price-decimals :perp 6)))
  (is (nil? (p/max-price-decimals :spot nil)))
  (is (= 4 (p/significant-figures (pd "0.0001234"))))
  (is (= 5 (p/significant-figures (pd "81130"))))
  (is (p/legal-price? (pd "123456") {:market-type :spot :sz-decimals 2}))
  (is (not (p/legal-price? (pd "79.2546") {:market-type :spot :sz-decimals 2})))
  (is (not (p/legal-price? (pd "0") {:market-type :spot :sz-decimals 2}))))

(deftest spec-13-3-quantize-price-rows-test
  (let [spot1 {:market-type :spot :sz-decimals 1}
        spot2 {:market-type :spot :sz-decimals 2}]
    (testing "tiny spot price"
      (is (= "0.0001234" (qp "0.0001234" :buy spot1)))
      (is (= "0.0001234" (qp "0.0001234" :sell spot1))))
    (testing "perp tiny price"
      (is (= "0.000123" (qp "0.0001234" :buy {:market-type :perp :sz-decimals 0})))
      (is (= "0.000124" (qp "0.0001234" :sell {:market-type :perp :sz-decimals 0}))))
    (testing "five significant figures"
      (is (= "79.254" (qp "79.254" :buy spot2)))
      (is (= "79.254" (qp "79.254" :sell spot2)))
      (is (= "79.254" (qp "79.2546" :buy spot2)))
      (is (= "79.255" (qp "79.2546" :sell spot2))))
    (testing "large prices round to integers"
      (is (= "81129" (qp "81129.4" :buy spot2)))
      (is (= "81130" (qp "81129.4" :sell spot2)))
      (is (= "123456" (qp "123456" :buy spot2)))
      (is (= "123456" (qp "123456" :sell spot2))))
    (testing "magnitude change"
      (is (= "9999.9" (qp "9999.96" :buy spot2)))
      (is (= "10000" (qp "9999.96" :sell spot2))))
    (testing "no legal buy price"
      (is (nil? (qp "0.4" :buy {:market-type :perp :sz-decimals 6}))))
    (testing "invalid inputs"
      (is (nil? (qp "1" :buy {:market-type :spot :sz-decimals nil})))
      (is (nil? (p/quantize-price (pd "-1") :buy spot2)))
      (is (nil? (p/quantize-price nil :buy spot2))))))
