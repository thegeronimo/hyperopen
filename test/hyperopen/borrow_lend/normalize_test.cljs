(ns hyperopen.borrow-lend.normalize-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.borrow-lend.normalize :as normalize]))

;; Captured from mainnet `allBorrowLendReserveStates` on 2026-10-20.
(def live-reserve-states
  (js->clj
   (js/JSON.parse
    "[[0,{\"borrowYearlyRate\":\"0.0501061434\",\"supplyYearlyRate\":\"0.0360774309\",\"balance\":\"108264162.223737359\",\"utilization\":\"0.800022346\",\"oraclePx\":\"1.0\",\"ltv\":\"0.0\",\"totalSupplied\":\"541380906.1451343298\",\"totalBorrowed\":\"433116822.6010892987\"}],[150,{\"borrowYearlyRate\":\"0.05\",\"supplyYearlyRate\":\"0.0\",\"balance\":\"14352709.0563689005\",\"utilization\":\"0.0\",\"oraclePx\":\"79.35\",\"ltv\":\"0.65\",\"totalSupplied\":\"14352709.0563689005\",\"totalBorrowed\":\"0.0\"}],[197,{\"borrowYearlyRate\":\"0.05\",\"supplyYearlyRate\":\"0.0\",\"balance\":\"2234.6617754175\",\"utilization\":\"0.0\",\"oraclePx\":\"82302.0\",\"ltv\":\"0.5\",\"totalSupplied\":\"2234.6617754175\",\"totalBorrowed\":\"0.0\"}],[268,{\"borrowYearlyRate\":\"0.05\",\"supplyYearlyRate\":\"0.0359945595\",\"balance\":\"386178.89396093\",\"utilization\":\"0.7998790991\",\"oraclePx\":\"0.99891\",\"ltv\":\"0.0\",\"totalSupplied\":\"1929727.5079121001\",\"totalBorrowed\":\"1543548.70047116\"}]]")))

(deftest normalize-reserve-states-test
  (let [rows (normalize/normalize-reserve-states live-reserve-states)
        usdc (first rows)
        hype (second rows)]
    (is (= [0 150 197 268] (mapv :token rows)))
    (is (= {:token 0
            :borrow-apy 0.0501061434
            :supply-apy 0.0360774309
            :available 108264162.223737359
            :utilization 0.800022346
            :oracle-px 1
            :ltv 0
            :total-supplied 541380906.1451343298
            :total-borrowed 433116822.6010892987}
           usdc))
    (is (= 0.65 (:ltv hype)))
    (is (= 79.35 (:oracle-px hype)))))

(deftest normalize-reserve-states-tolerates-bad-input-test
  (is (= [] (normalize/normalize-reserve-states nil)))
  (is (= [] (normalize/normalize-reserve-states "boom")))
  (testing "drops malformed entries and keeps good ones"
    (is (= [7] (mapv :token (normalize/normalize-reserve-states
                             [[-1 {}] ["x" {}] [1.5 {}] [3] [7 {"utilization" "0.5"}] [8 "nope"]])))))
  (testing "missing numbers default to zero; missing oracle stays nil"
    (let [row (first (normalize/normalize-reserve-states [[7 {}]]))]
      (is (= 0 (:utilization row)))
      (is (nil? (:oracle-px row))))))

(deftest normalize-user-state-test
  (testing "empty account (live shape)"
    (is (= {:positions [] :health "healthy" :health-factor nil}
           (normalize/normalize-user-state
            {"tokenToState" [] "health" "healthy" "healthFactor" nil}))))
  (testing "positions keep supply and borrow legs and drop empty tokens"
    (let [state (normalize/normalize-user-state
                 {"tokenToState" [[150 {"supply" {"basis" "100" "value" "100"}}]
                                  [0 {"supply" {"basis" "5000" "value" "5012.5"}
                                      "borrow" {"basis" "2000" "value" "2003.1"}}]
                                  [268 {"supply" {"basis" "0" "value" "0"}}]]
                  "health" "healthy"
                  "healthFactor" "1.3"})]
      (is (= [{:token 0 :supplied 5012.5 :supplied-basis 5000 :borrowed 2003.1 :borrowed-basis 2000}
              {:token 150 :supplied 100 :supplied-basis 100 :borrowed 0 :borrowed-basis 0}]
             (:positions state)))
      (is (= 1.3 (:health-factor state)))))
  (testing "garbage input yields an empty state"
    (is (= {:positions [] :health nil :health-factor nil}
           (normalize/normalize-user-state nil)))))

(deftest interest-rows-test
  (let [rows (normalize/normalize-interest-rows
              [{"time" 2 "token" "USDC" "borrow" "0.5" "supply" "1.25"}
               {"time" 1 "token" "USDC" "borrow" "0" "supply" "0.75"}
               {"token" "USDC"}
               {"time" 3 "token" "USDT0" "borrow" "0.1" "supply" "0"}])]
    (is (= [1 2 3] (mapv :time-ms rows)))
    (is (= {"USDC" {:supply 2 :borrow 0.5}
            "USDT0" {:supply 0 :borrow 0.1}}
           (normalize/interest-totals rows)))))
