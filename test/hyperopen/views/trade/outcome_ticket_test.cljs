(ns hyperopen.views.trade.outcome-ticket-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.test-support.fixture :as fixture]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.trade.order-form-handlers :as handlers]
            [hyperopen.views.trade.outcome-ticket :as outcome-ticket]
            [hyperopen.views.trade.outcome-ticket-vm :as ticket-vm]))

(def ^:private now-ms (js/Date.UTC 2026 10 9 16 25))

(defn- game []
  (let [market (some #(when (= "outcome:27037" (:key %)) %)
                     (markets/build-outcome-markets fixture/outcome-meta fixture/asset-ctxs))]
    (markets/market-with-selected-outcome-coin market "#270370")))

(def ^:private book
  {:asks [{:px "0.81902" :sz "758"}]
   :bids [{:px "0.81886" :sz "69"} {:px "0.81885" :sz "135"}]})

(defn- render
  [vm-overrides balances]
  (let [vm (merge {:side :buy :type :market :form {:size "122" :price ""} :size-input-mode :quote
                   :size-display "100" :available-usdc 275.67 :submit {:disabled? false}}
                  vm-overrides)
        state {:active-asset "#270370"
               :active-market (game)
               :orderbooks {"#270370" book}
               :spot {:clearinghouse-state {:balances balances}}}]
    (outcome-ticket/ticket (ticket-vm/ticket-model state vm now-ms) vm (handlers/build-handlers))))

(defn- by-role
  [view role]
  (hiccup/find-all-nodes view #(= role (get-in % [1 :data-role]))))

(deftest ticket-asks-side-amount-and-payout-test
  (let [view (render {} [])
        strings (set (hiccup/collect-strings view))
        sides (by-role view "outcome-ticket-side")]
    (is (= 2 (count sides)))
    (is (every? #(= :button (first %)) sides))
    (is (every? #(vector? (get-in % [1 :on :click])) sides))
    (is (contains? strings "Pick a side"))
    (is (contains? strings "Pays if DET wins"))
    (is (contains? strings "$122.00"))
    (is (contains? strings "Buy DET · $99.92"))
    (is (empty? (by-role view "outcome-ticket-mode")) "no Cash out switch without shares")
    (is (empty? (filter #(re-find #"#\d{5,}" %) (remove #(str/starts-with? % "Contract") strings))))))

(deftest holding-shares-offers-cash-out-test
  (let [view (render {} [{:coin "+270370" :total "122.0" :entryNtl "99.92"}])
        mode (first (by-role view "outcome-ticket-mode"))]
    (is (some? mode))
    (is (some #{"Cash out"} (hiccup/collect-strings mode)))
    (is (some #{"You hold 122 DET · 0 WAS"} (hiccup/collect-strings view)))))

(deftest limit-mode-renders-a-cents-field-test
  (let [view (render {:type :limit :form {:size "100" :price "0.68"}} [])
        input (hiccup/find-first-node view #(= "outcome-ticket-limit-price" (get-in % [1 :id])))]
    (is (= "68" (get-in input [1 :value])))
    (is (fn? (get-in input [1 :on :input])))
    (is (some #{"= 68.0% implied chance"} (hiccup/collect-strings view)))))
