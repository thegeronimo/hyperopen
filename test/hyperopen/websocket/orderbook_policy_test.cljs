(ns hyperopen.websocket.orderbook-policy-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.orderbook-policy :as policy]))

(deftest parse-number-test
  (is (= 42 (policy/parse-number 42)))
  (is (= 12.5 (policy/parse-number "12.5")))
  (is (nil? (policy/parse-number "abc")))
  (is (nil? (policy/parse-number nil))))

(deftest sort-levels-test
  (let [levels [{:px "100"} {:px 101} {:px "99.5"}]]
    (testing "bids sorted descending by parsed price"
      (is (= [101 "100" "99.5"]
             (mapv :px (policy/sort-bids levels)))))
    (testing "asks keep legacy descending sort for compatibility"
      (is (= [101 "100" "99.5"]
             (mapv :px (policy/sort-asks levels)))))))

(deftest normalize-aggregation-config-test
  (is (= {:nSigFigs 4}
         (policy/normalize-aggregation-config {:nSigFigs 4})))
  (is (= {}
         (policy/normalize-aggregation-config {:nSigFigs 6})))
  (is (= {}
         (policy/normalize-aggregation-config {}))))

(deftest build-subscription-test
  (is (= {:type "l2Book" :coin "BTC" :nSigFigs 3}
         (policy/build-subscription "BTC" {:nSigFigs 3})))
  (is (= {:type "l2Book" :coin "ETH"}
         (policy/build-subscription "ETH" {:nSigFigs 8}))))
