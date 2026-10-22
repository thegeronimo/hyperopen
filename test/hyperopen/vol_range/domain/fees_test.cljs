(ns hyperopen.vol-range.domain.fees-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.precision :as p]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.fees :as fees]))

(def legs-13-2
  {:buy [{:size "7" :notional "534.8"} {:size "9" :notional "666"}
         {:size "11" :notional "787.6"} {:size "13" :notional "899.6"}]
   :sell [{:size "10" :notional "860"} {:size "10" :notional "884"} {:size "10" :notional "908"}]})

(def synthetic
  {:status :ok :maker-fraction "0.0004" :buy-fee-token :quote :sell-fee-token :quote
   :settlement-verified? true :source :synthetic :loaded-at-ms 0})

(deftest percent->fraction-test
  (is (= "0.0004" (p/decimal->string (fees/percent->fraction 0.04))))
  (is (= "0.00045" (p/decimal->string (fees/percent->fraction 0.045))))
  (is (= "0.0004" (p/decimal->string (fees/percent->fraction "0.040"))))
  (is (nil? (fees/percent->fraction nil)))
  (is (nil? (fees/percent->fraction "abc"))))

(deftest fee-capability-test
  (let [cap (fees/fee-capability {:quote {:effective {:maker 0.04 :taker 0.07}} :loaded-at-ms 5
                                  :source :live})]
    (is (= :ok (:status cap)))
    (is (= "0.0004" (:maker-fraction cap)))
    (is (= :base (:buy-fee-token cap)))
    (is (= :quote (:sell-fee-token cap)))
    (is (false? (:settlement-verified? cap)))
    (is (contracts/fee-capability-valid? cap)))
  (doseq [in [{:quote nil} {:quote {:effective {:maker 0.04}} :fallback? true}]]
    (let [cap (fees/fee-capability in)]
      (is (= :unavailable (:status cap)))
      (is (nil? (:maker-fraction cap)))
      (is (contracts/fee-capability-valid? cap)))))

(deftest fee-reserves-test
  (let [r (fees/fee-reserves {:buy-legs (:buy legs-13-2) :sell-legs (:sell legs-13-2) :capability synthetic})]
    (is (= {:token :quote :amount "1.1552"} (:buy r)))
    (is (= {:token :quote :amount "1.0608"} (:sell r)))
    (is (= "1.1552" (:usdc-debit-bound r)))
    (is (= "0" (:base-debit-bound r)))
    (is (= "0" (:base-credit-bound r))))
  (testing "base-settled buy fee"
    (let [r (fees/fee-reserves {:buy-legs (:buy legs-13-2) :sell-legs (:sell legs-13-2)
                                :capability (assoc synthetic :buy-fee-token :base :sell-fee-token :base)})]
      (is (= "0" (:usdc-debit-bound r)))
      (is (= "0.016" (:amount (:buy r))))
      (is (= "0.012" (:base-debit-bound r)))))
  (testing "negative maker fee never funds, raises the base upper bound"
    (let [r (fees/fee-reserves {:buy-legs (:buy legs-13-2) :sell-legs (:sell legs-13-2)
                                :capability (assoc synthetic :maker-fraction "-0.0001" :buy-fee-token :base)})]
      (is (= "0" (:usdc-debit-bound r)))
      (is (= "0" (:base-debit-bound r)))
      (is (= "0.004" (:base-credit-bound r)))))
  (testing "unavailable capability reserves nothing"
    (is (= "0" (:usdc-debit-bound (fees/fee-reserves {:buy-legs [] :sell-legs []
                                                      :capability {:status :unavailable}}))))))
