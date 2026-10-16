(ns hyperopen.domain.trading.book-sweep-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.book-sweep :as book-sweep]
            [hyperopen.state.trading :as trading]
            [hyperopen.state.trading.test-support :as support]))

(def ^:private book
  ;; Deliberately unsorted: the sweep must order levels itself.
  {:bids [{:px "99" :sz "2"} {:px "100" :sz "1"} {:px "98" :sz "4"}]
   :asks [{:px "102" :sz "2"} {:px "101" :sz "1"} {:px "103" :sz "4"}]})

(defn- approx= [a b] (support/approx= a b))

(deftest market-buy-sweeps-asks-best-first-test
  (let [impact (book-sweep/market-impact book :buy 2.5)]
    (is (= 2 (:levels impact)))
    (is (= "102" (:worst-px impact)))
    (is (approx= 2.5 (:size impact)))
    (is (approx= (/ (+ 101 (* 1.5 102)) 2.5) (:avg-price impact)))
    (is (approx= 0.75 (:last-level-fraction impact)))
    (is (= {"101" 1 "102" 0.75} (:fill-fraction-by-px impact)))
    (is (true? (:complete? impact)))
    (is (approx= (* 100 (/ 2.5 7)) (:depth-used-pct impact)))
    (testing "slippage is measured from the top-of-book mid"
      (let [mid 100.5]
        (is (approx= (* 100 (/ (- (:avg-price impact) mid) mid)) (:slippage-pct impact)))))))

(deftest market-sell-sweeps-bids-best-first-test
  (let [impact (book-sweep/market-impact book :sell 1.5)]
    (is (= {"100" 1 "99" 0.25} (:fill-fraction-by-px impact)))
    (is (= "99" (:worst-px impact)))))

(deftest an-order-larger-than-the-visible-book-is-incomplete-test
  (let [impact (book-sweep/market-impact book :buy 10)]
    (is (false? (:complete? impact)))
    (is (approx= 7 (:size impact)))
    (is (= 10 (:requested-size impact)))
    (is (approx= 100 (:depth-used-pct impact)))
    (is (= 3 (:levels impact)))))

(deftest nothing-to-sweep-test
  (is (nil? (book-sweep/market-impact book :buy 0)))
  (is (nil? (book-sweep/market-impact book :buy nil)))
  (is (nil? (book-sweep/market-impact {:bids [] :asks []} :buy 1)))
  (is (nil? (book-sweep/market-impact book :hold 1))))

(deftest sweep-to-price-takes-every-level-through-the-price-test
  (let [readout (book-sweep/sweep-to-price book :buy "102")]
    (is (= 2 (:levels readout)))
    (is (approx= 3 (:size readout)))
    (is (approx= (+ 101 204) (:notional readout))))
  (let [readout (book-sweep/sweep-to-price book :sell "99")]
    (is (= 2 (:levels readout)))
    (is (approx= 3 (:size readout))))
  (testing "a price better than the best level sweeps nothing"
    (is (nil? (book-sweep/sweep-to-price book :buy "100")))))

(deftest agrees-with-the-order-form-slippage-estimate-test
  ;; The impact card and the footer's "Slippage Est" must never disagree.
  (let [state (assoc support/base-state :orderbooks {"BTC" book})
        form (assoc (trading/default-order-form) :type :market :side :buy :size "2.5")
        footer-pct (:slippage-est (trading/order-summary state form))]
    (is (approx= footer-pct (:slippage-pct (book-sweep/market-impact book :buy 2.5))))))
