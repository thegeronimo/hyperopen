(ns hyperopen.views.l2-orderbook-view-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.l2-orderbook-view :as view]))

(deftest symbol-resolution-test
  (testing "market metadata takes precedence"
    (is (= "PUMP" (view/resolve-base-symbol "PUMP" {:base "PUMP"})))
    (is (= "USDC" (view/resolve-quote-symbol "PUMP" {:quote "USDC"}))))

  (testing "coin fallback works for spot and dex-perp strings"
    (is (= "PURR" (view/resolve-base-symbol "PURR/USDC" nil)))
    (is (= "USDC" (view/resolve-quote-symbol "PURR/USDC" nil)))
    (is (= "GOLD" (view/resolve-base-symbol "hyna:GOLD" nil)))
    (is (= "USDC" (view/resolve-quote-symbol "hyna:GOLD" nil)))))

(deftest quote-vs-base-size-total-test
  (let [order {:px "2.5"
               :sz "100"
               :cum-size 250
               :cum-value 625}
        base-unit :base
        quote-unit :quote]
    (testing "size conversion switches between base and quote units"
      (is (= 100 (view/order-size-for-unit order base-unit)))
      (is (= 250 (view/order-size-for-unit order quote-unit))))

    (testing "cumulative total switches between base and quote units"
      (is (= 250 (view/order-total-for-unit order base-unit)))
      (is (= 625 (view/order-total-for-unit order quote-unit))))

    (testing "formatted quote values are rounded whole numbers"
      (is (= "250" (view/format-order-size order quote-unit)))
      (is (= "625" (view/format-order-total order quote-unit))))

    (testing "formatted base values preserve raw size and cumulative precision"
      (is (= "100" (view/format-order-size order base-unit)))
      (is (= "250" (view/format-order-total order base-unit))))))

(deftest cumulative-totals-test
  (let [orders [{:px "2" :sz "3"}
                {:px "4" :sz "5"}]
        totals (view/calculate-cumulative-totals orders)]
    (is (= 2 (count totals)))
    (is (= 3 (:cum-size (first totals))))
    (is (= 6 (:cum-value (first totals))))
    (is (= 8 (:cum-size (second totals))))
    (is (= 26 (:cum-value (second totals))))))
