(ns hyperopen.outcomes.application.exit-actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.application.exit-actions :as exit-actions]
            [hyperopen.outcomes.test-support.fixture :as fixture]
            [hyperopen.state.trading :as trading]))

(defn- catalog []
  (into {} (map (juxt :key identity))
        (markets/build-outcome-markets fixture/outcome-meta fixture/asset-ctxs)))

(defn- base-state
  []
  {:active-asset "BTC"
   :active-market {:coin "BTC" :market-type :perp}
   :asset-selector {:market-by-key (catalog)}
   :order-form (assoc (trading/default-order-form) :side :buy :type :market :size "5" :price "")
   :order-form-ui (assoc (trading/default-order-form-ui) :size-input-mode :quote :size-display "5")
   :order-form-runtime (trading/default-order-form-runtime)})

(defn- run-saves
  "Apply save effects the way the store would."
  [state effects]
  (reduce (fn [state* [effect-id & args]]
            (case effect-id
              :effects/save-many (reduce (fn [s [path value]] (assoc-in s path value)) state* (first args))
              :effects/save (assoc-in state* (first args) (second args))
              state*))
          state
          effects))

(defn- save-many-count
  [effects]
  (count (filter #(= :effects/save-many (first %)) effects)))

(deftest cash-out-selects-the-market-and-sets-sell-shares-in-one-save-test
  (let [state (base-state)
        effects (exit-actions/open-outcome-cash-out state "outcome:27037" "#270370" 122.9)
        next-state (run-saves state effects)
        form (trading/order-form-draft next-state)]
    (is (= 1 (save-many-count effects)) "one state transition, not a second save after the switch")
    (is (= "#270370" (:active-asset next-state)))
    (is (= :sell (:side form)))
    (is (= "122" (get-in next-state [:order-form-ui :size-display])) "whole shares only")
    (is (= :base (get-in next-state [:order-form-ui :size-input-mode])) "typed as shares, not dollars")
    (is (= "122" (:size form)))
    (is (some #(= [:effects/subscribe-active-asset "#270370"] %) effects)
        "the market switch still subscribes the side's feeds")))

(deftest cash-out-from-the-already-selected-market-still-flips-the-ticket-test
  (let [state (-> (base-state)
                  (assoc :active-asset "#270370"
                         :active-market (markets/market-with-selected-outcome-coin
                                         (get (catalog) "outcome:27037") "#270370")))
        next-state (run-saves state (exit-actions/open-outcome-cash-out state "outcome:27037" "#270370" 40))]
    (is (= :sell (:side (trading/order-form-draft next-state))))
    (is (= "40" (get-in next-state [:order-form-ui :size-display])))))

(deftest cash-out-of-an-unknown-coin-changes-nothing-test
  (let [state (assoc-in (base-state) [:asset-selector :phase] :full)]
    (is (= [] (exit-actions/open-outcome-cash-out state "outcome:0" "#99999990" 5)))))

(deftest merge-opens-the-market-and-the-merge-ticket-in-one-save-test
  (let [state (assoc-in (base-state) [:outcome-ticket :merge] {:coins ["#1" "#2"] :pairs "9"})
        effects (exit-actions/merge-outcome-pairs state "outcome:27037" "#270370" "#270371" 40)
        next-state (run-saves state effects)]
    (testing "one transition"
      (is (= 1 (save-many-count effects)))
      (is (= "#270370" (:active-asset next-state))))
    (testing "the merge ticket is opened for exactly this pair"
      (is (= {:coins ["#270370" "#270371"] :pairs "40"}
             (get-in next-state [:outcome-ticket :merge]))))
    (testing "the order form is the freshly reset one, not a half-sell"
      (is (= :buy (:side (trading/order-form-draft next-state)))))))

(deftest cash-out-closes-an-open-merge-ticket-test
  (let [state (assoc-in (base-state) [:outcome-ticket :merge] {:coins ["#270370" "#270371"] :pairs "9"})
        next-state (run-saves state (exit-actions/open-outcome-cash-out state "outcome:27037" "#270370" 10))]
    (is (nil? (get-in next-state [:outcome-ticket :merge])))))
