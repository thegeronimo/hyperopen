(ns hyperopen.vol-range.application.quote-sizing-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.precision :as p]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.fixtures :as fx]))

(defn- plan [d] (planner/prepare-plan (fx/plan-input {:draft d})))
(defn- codes [r] (set (map :code (:errors r))))
(defn- draft [& kvs] (reduce (fn [d [path v]] (assoc-in d path v)) fx/draft-13-2 (partition 2 kvs)))
(defn- legs [r side] (mapv (juxt :leg-id :price :size) (filter #(= side (:side %)) (:legs r))))
(defn- dec [s] (p/parse-decimal s))

(defn- usdc-buys [amount]
  (draft [:buy :amount-unit] :quote [:buy :quote-amount] amount [:buy :max-usdc-debit] ""))

(deftest buy-amount-reproduces-the-base-plan-test
  (let [base (plan fx/draft-13-2)
        r (plan (usdc-buys "2889.16"))]
    (is (= :ready (:status r)) (pr-str (:errors r)))
    (is (contracts/plan-result-valid? r))
    (testing "the amount covers the base plan's maximum debit (2889.1552) and no more lots"
      (is (= (legs base :buy) (legs r :buy)))
      (is (= "40" (get-in r [:totals :buy :quantity]))))
    (testing "the amount is the debit cap and is reported as entered"
      (is (= "2889.16" (get-in r [:totals :buy :cap])))
      (is (= :quote (get-in r [:totals :buy :amount-unit])))
      (is (= "2889.16" (get-in r [:totals :buy :quote-amount])))
      (is (= :base (get-in r [:totals :sell :amount-unit]))))))

(deftest buy-amount-picks-the-largest-fitting-quantity-test
  (let [r (plan (usdc-buys "2889.15"))
        q (get-in r [:totals :buy :quantity])
        one-more (plan (draft [:buy :quantity] (p/decimal->string (p/add (dec q) (dec "0.01")))
                              [:buy :max-usdc-debit] "100000"))]
    (is (= :ready (:status r)) (pr-str (:errors r)))
    (is (= "39.99" q) "one cent short of the 40-unit plan drops one lot")
    (is (<= (p/cmp (dec (get-in r [:totals :buy :max-debit])) (dec "2889.15")) 0)
        "fee reserve included")
    (is (pos? (p/cmp (dec (get-in one-more [:totals :buy :max-debit])) (dec "2889.15")))
        "one more lot would not fit")))

(deftest buy-amount-needs-no-separate-cap-test
  (let [r (plan (usdc-buys "1000"))]
    (is (not (contains? (codes r) :input/max-debit-required)))
    (is (not (contains? (codes r) :plan/usdc-cap-exceeded)))
    (is (not-any? #(= :use-plan-total (:id %)) (:remediations r)))))

(deftest amount-errors-test
  (testing "blank amount"
    (let [r (plan (usdc-buys ""))]
      (is (contains? (codes r) :input/quote-amount-required))
      (is (some #(= [:buy :quote-amount] (get-in % [:data :path])) (:errors r)))
      (is (not (contains? (codes r) :input/quantity-required)))))
  (testing "too small for one lot on every rung"
    (let [r (plan (usdc-buys "1"))
          e (first (filter #(= :input/quote-amount-too-small (:code %)) (:errors r)))]
      (is (some? e))
      (is (= [:buy :quote-amount] (get-in e [:data :path])))
      (is (= :buy (get-in e [:data :side])))
      (is (re-find #"Enter at least \d+\.\d{2} USDC" (:message e)))
      (is (empty? (legs r :buy))))))

(deftest sell-amount-test
  (let [base (plan fx/draft-13-2)
        gross (get-in base [:totals :sell :gross])
        r (plan (draft [:sell :amount-unit] :quote [:sell :quote-amount] gross [:sell :quantity] ""))]
    (is (= "2652" gross))
    (is (= :ready (:status r)) (pr-str (:errors r)))
    (is (= (legs base :sell) (legs r :sell)) "sells are sized on gross order value")
    (testing "a smaller amount sells less"
      (let [r2 (plan (draft [:sell :amount-unit] :quote [:sell :quote-amount] "1500" [:sell :quantity] ""))]
        (is (<= (p/cmp (dec (get-in r2 [:totals :sell :gross])) (dec "1500")) 0))
        (is (neg? (p/cmp (dec (get-in r2 [:totals :sell :quantity])) (dec "30"))))))))

(deftest base-mode-ignores-the-amount-test
  (let [r (plan (draft [:buy :quote-amount] "5"))]
    (is (= :ready (:status r)) (pr-str (:errors r)))
    (is (= "40" (get-in r [:totals :buy :quantity])))))

(deftest huge-amount-terminates-and-fits-test
  (testing "lot counts above 2^53 are searched exactly"
    (let [amount "100000000000000000"
          r (plan (usdc-buys amount))
          max-debit (dec (get-in r [:totals :buy :max-debit]))]
      (is (= 4 (count (legs r :buy))))
      (is (<= (p/cmp max-debit (dec amount)) 0))
      (is (pos? (p/cmp max-debit (dec "99999999999999000"))) "uses nearly all of the amount")
      (is (contains? (codes r) :plan/insufficient-usdc)))))

(deftest usdc-mode-error-paths-and-offers-test
  (testing "quantity errors point at the amount field in USDC mode"
    (let [r (plan (-> (usdc-buys "1000") (assoc-in [:inventory :target] "100")))]
      (is (not-any? #(= [:buy :quantity] (get-in % [:data :path])) (:errors r)))))
  (testing "an amount too small for the rung count offers fewer rungs"
    ;; Whole-unit lots (~70 USDC each): 200 USDC cannot give 4 rungs a lot each.
    (let [r (planner/prepare-plan (fx/plan-input {:draft (usdc-buys "200")
                                                  :market (fx/market-snapshot {:sz-decimals 0})}))]
      (is (contains? (codes r) :input/quote-amount-too-small))
      (is (some #(= :use-fewer-rungs (:id %)) (:remediations r))))))

(defn- usdc-limits [core target maximum]
  (draft [:inventory :limit-unit] :quote
         [:inventory :protected-core-quote] core [:inventory :target-quote] target
         [:inventory :maximum-quote] maximum
         [:inventory :protected-core] "" [:inventory :target] "" [:inventory :maximum] ""))

(deftest usdc-limits-convert-at-the-anchor-test
  (let [base (plan fx/draft-13-2)
        r (plan (usdc-limits "9600" "12800" "15200"))]
    (is (= :ready (:status r)) (pr-str (:errors r)))
    (is (= (:legs base) (:legs r)) "120 / 160 / 190 SYN at the 80 anchor")
    (is (= ["120" "160" "190"] (mapv #(get-in r [:inventory-outcomes %]) [:core :target :maximum])))
    (is (= :quote (get-in r [:inventory-outcomes :limit-unit])))
    (is (= "80" (get-in r [:inventory-outcomes :limit-price]))))
  (testing "core rounds up and maximum down to the lot, toward protection"
    (let [r (plan (usdc-limits "9600.3" "12800" "15200.5"))]
      (is (= ["120.01" "190"] (mapv #(get-in r [:inventory-outcomes %]) [:core :maximum])))))
  (testing "a maximum below one lot is an input error on the USDC field"
    (let [r (plan (usdc-limits "" "" "0.001"))
          e (first (filter #(= :input/limit-below-lot (:code %)) (:errors r)))]
      (is (= [:inventory :maximum-quote] (get-in e [:data :path])))))
  (testing "core above maximum after rounding is an order error"
    (let [r (plan (usdc-limits "15200.5" "15200.5" "15200.6"))]
      (is (some #(and (= :input/inventory-limits-order (:code %))
                      (= [:inventory :protected-core-quote] (get-in % [:data :path])))
                (:errors r))))))

(deftest usdc-limit-errors-and-offers-test
  (let [r (plan (usdc-limits "9600" "12800" ""))
        e (first (filter #(= :input/maximum-required (:code %)) (:errors r)))
        offer (first (filter #(= :set-maximum-all-buys (:id %)) (:remediations r)))]
    (is (= [:inventory :maximum-quote] (get-in e [:data :path])))
    (is (some? offer))
    (is (= [[[:inventory :maximum-quote] "15200.00"]] (:patch offer)))
    (is (= "Set maximum to 15200.00 USDC · all buys filled" (:label offer))))
  (testing "inventory-order errors point at the USDC fields"
    (let [r (plan (usdc-limits "13000" "12800" "15200"))]
      (is (some #(= [:inventory :protected-core-quote] (get-in % [:data :path])) (:errors r)))
      (is (not-any? #(= [:inventory :protected-core] (get-in % [:data :path])) (:errors r))))))
