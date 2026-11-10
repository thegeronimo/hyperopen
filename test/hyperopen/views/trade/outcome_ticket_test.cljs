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
    (is (contains? strings "If DET wins"))
    (is (contains? strings "You keep if right"))
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
    (is (= [[:actions/set-outcome-ticket-entry {:limit-cents [:event.target/value]}]]
           (get-in input [1 :on :input]))
        "one action, no store dispatch inside the view")
    (is (some #{"= 68.0% implied chance"} (hiccup/collect-strings view)))))

(defn- event-handlers
  "Every [:on event] action vector in the view."
  [view]
  (->> (hiccup/find-all-nodes view #(and (vector? %) (map? (get-in % [1 :on]))))
       (mapcat (fn [node] (vals (get-in node [1 :on]))))
       (filter vector?)))

(deftest every-ticket-control-dispatches-one-action-test
  (doseq [[label vm-overrides balances]
          [["buy market" {} []]
           ["buy limit" {:type :limit :form {:size "100" :price "0.68"}} []]
           ["holder" {} [{:coin "+270370" :total "122.0" :entryNtl "99.92"}]]
           ["cash out" {:side :sell :size-input-mode :base :size-display "100"}
            [{:coin "+270370" :total "122.0" :entryNtl "99.92"}]]]]
    (let [handlers (event-handlers (render vm-overrides balances))]
      (is (seq handlers) label)
      (is (every? #(= 1 (count %)) handlers)
          (str label ": " (pr-str (remove #(= 1 (count %)) handlers)))))))

(deftest cash-out-tab-sells-every-held-share-in-one-action-test
  (is (= [[:actions/set-outcome-ticket-entry {:side :sell :size-input-mode :base :size-display "100"}]]
         (outcome-ticket/mode-actions :cash-out 100.7)))
  (is (= [[:actions/set-outcome-ticket-entry {:side :buy :size-input-mode :quote :size-display ""}]]
         (outcome-ticket/mode-actions :buy 100))))

(deftest limit-button-names-its-price-or-asks-for-one-test
  (let [strings (set (hiccup/collect-strings (render {:type :limit :form {:size "100" :price ""}} [])))]
    (is (contains? strings "Enter a limit price"))
    (is (not-any? #(str/includes? % "at —") strings)))
  (let [strings (set (hiccup/collect-strings (render {:type :limit :form {:size "100" :price "0.29"}} [])))]
    (is (some #(str/includes? % "at 29.000¢") strings))))

(defn- render-merge
  [merge-state]
  (let [vm {:side :buy :type :market :form {:size "" :price ""} :size-input-mode :quote
            :size-display "" :available-usdc 275.67 :submit {:disabled? false}}
        state {:active-asset "#270370"
               :active-market (game)
               :orderbooks {"#270370" book}
               :spot {:clearinghouse-state {:balances [{:coin "+270370" :total "122.0" :entryNtl "99.92"}
                                                       {:coin "+270371" :total "40.0" :entryNtl "7.0"}]}}
               :outcome-ticket {:merge merge-state}}]
    (outcome-ticket/ticket (ticket-vm/ticket-model state vm now-ms) vm (handlers/build-handlers))))

(deftest merge-tab-appears-only-when-both-sides-are-held-test
  (let [tabs (fn [view] (set (hiccup/collect-strings (first (by-role view "outcome-ticket-mode")))))]
    (is (contains? (tabs (render-merge nil)) "Merge pairs"))
    (is (not (contains? (tabs (render {} [{:coin "+270370" :total "122.0" :entryNtl "99.92"}])) "Merge pairs")))))

(deftest merge-tab-is-one-action-and-the-merge-ticket-reads-like-the-board-test
  (is (= [[:actions/set-outcome-ticket-entry {:merge {:coins ["#270370" "#270371"] :pairs "40"}}]]
         (outcome-ticket/mode-actions :merge 122 {:coins ["#270370" "#270371"] :pairs "40"})))
  (let [view (render-merge {:coins ["#270370" "#270371"] :pairs "40"})
        strings (set (hiccup/collect-strings view))
        submit (hiccup/find-first-node view #(= "trade-submit-order-button" (get-in % [1 :data-parity-id])))]
    (is (contains? strings "Pairs to merge"))
    (is (contains? strings "You receive"))
    (is (contains? strings "$39.94"))
    (is (contains? strings "Merge 40 pairs · $39.94"))
    (is (= [[:actions/submit-outcome-merge {:outcome-id 27037 :pairs 40}]]
           (get-in submit [1 :on :click]))
        "the merge button sends the merge action, never the order submit")
    (is (empty? (by-role view "outcome-ticket-side")) "no side pickers while merging")
    (doseq [handler (event-handlers view)]
      (is (= 1 (count handler)) (pr-str handler)))))
