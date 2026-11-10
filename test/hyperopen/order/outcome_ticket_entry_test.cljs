(ns hyperopen.order.outcome-ticket-entry-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.order.actions :as actions]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-transitions :as transitions]))

(def ^:private sides
  [{:side-index 0 :coin "#1610" :asset-id 100001610}
   {:side-index 1 :coin "#1611" :asset-id 100001611}])

(defn- outcome-state
  []
  {:active-asset "#1610"
   :active-market {:coin "#1610" :market-type :outcome :asset-id 100001610 :szDecimals 0
                   :outcome-sides sides}
   :orderbooks {"#1610" {:bids [{:px "0.40" :sz "500"}] :asks [{:px "0.42" :sz "500"}]}}
   :spot {:clearinghouse-state {:balances [{:coin "USDC" :total "1000" :hold "0"}
                                           {:coin "+1610" :total "100" :hold "0"}]}}
   :order-form (assoc (trading/default-order-form) :side :buy :type :market :size "" :price "")
   :order-form-ui (assoc (trading/default-order-form-ui)
                         :entry-mode :market :size-input-mode :quote :size-display "")
   :order-form-runtime (trading/default-order-form-runtime)})

(defn- apply-save-many
  "Apply an action's effects the way the store would."
  [state effects]
  (reduce (fn [state* [effect-id & args]]
            (case effect-id
              :effects/save-many (reduce (fn [s [path value]] (assoc-in s path value)) state* (first args))
              :effects/save (assoc-in state* (first args) (second args))
              state*))
          state
          effects))

(deftest cash-out-is-one-complete-change-test
  (let [state (outcome-state)
        effects (actions/set-outcome-ticket-entry state {:side :sell :size-input-mode :base :size-display "100"})
        next-state (apply-save-many state effects)
        form (trading/order-form-draft next-state)]
    (is (= 1 (count effects)))
    (is (= :sell (:side form)))
    (is (= :base (:size-input-mode form)))
    (is (= "100" (:size-display form)))
    (is (= "100" (:size form)) "sells 100 shares, not a $100 buy")))

(deftest old-three-action-cash-out-lost-writes-test
  (testing "the old click (three actions expanded against one snapshot) left a buy form"
    (let [state (outcome-state)
          lossy (reduce (fn [s [handler & args]]
                          (apply-save-many s (apply handler state args)))
                        state
                        [[actions/update-order-form [:side] :sell]
                         [actions/set-order-size-input-mode :base]
                         [actions/set-order-size-display "100"]])]
      (is (= :buy (:side (trading/order-form-draft lossy))) "the side write was lost"))))

(deftest buy-tab-resets-side-size-and-unit-test
  (let [state (apply-save-many (outcome-state)
                               (actions/set-outcome-ticket-entry (outcome-state)
                                                                 {:side :sell :size-input-mode :base :size-display "100"}))
        next-state (apply-save-many state (actions/set-outcome-ticket-entry state {:side :buy :size-input-mode :quote :size-display ""}))
        form (trading/order-form-draft next-state)]
    (is (= :buy (:side form)))
    (is (= :quote (:size-input-mode form)))
    (is (= "" (:size form)))))

(deftest stale-confirmation-lives-in-the-form-and-resets-on-side-change-test
  (let [state (apply-save-many (outcome-state)
                               (actions/set-outcome-ticket-entry (outcome-state) {:stale-confirmed? true}))]
    (is (true? (:outcome-stale-confirmed? (trading/order-form-draft state))))
    (let [switched (apply-save-many state (actions/set-outcome-ticket-entry state {:side :sell}))]
      (is (false? (:outcome-stale-confirmed? (trading/order-form-draft switched)))))
    (let [same (apply-save-many state (actions/set-outcome-ticket-entry state {:side :buy}))]
      (is (true? (:outcome-stale-confirmed? (trading/order-form-draft same)))))))

(deftest limit-cents-entry-writes-a-bounded-dollar-price-test
  (let [state (outcome-state)
        priced (fn [text] (:price (trading/order-form-draft
                                   (apply-save-many state (actions/set-outcome-ticket-entry state {:limit-cents text})))))]
    (is (= "0.29" (priced "29")))
    (is (= "0.685" (priced "68.5")))
    (is (= "0.005" (priced ".5")))
    (is (= "0.205" (priced "20,5")))
    (is (= "0.68" (priced "68.")))
    (is (= "" (priced "abc")))
    (is (= "" (priced "")))))

(deftest outcome-limit-cents->price-test
  (is (= "0.29" (transitions/outcome-limit-cents->price "29")))
  (is (= "0.81902" (transitions/outcome-limit-cents->price "81.902")))
  (is (= "1.5" (transitions/outcome-limit-cents->price "150")) "kept for the gate to refuse")
  (is (= "" (transitions/outcome-limit-cents->price "."))))

(deftest empty-entry-does-nothing-test
  (is (= [] (actions/set-outcome-ticket-entry (outcome-state) {}))))

(deftest limit-cents-entry-keeps-the-raw-typed-text-test
  (let [state (outcome-state)
        next-state (apply-save-many state (actions/set-outcome-ticket-entry state {:limit-cents "68."}))]
    (is (= "68." (get-in next-state [:outcome-ticket :limit-cents-text])))
    (is (= "0.68" (:price (trading/order-form-draft next-state))))))

(deftest a-cash-out-of-nothing-falls-back-to-buy-test
  (let [state (-> (outcome-state)
                  (assoc-in [:order-form :side] :sell)
                  (assoc-in [:order-form-ui :size-input-mode] :base)
                  (assoc-in [:spot :clearinghouse-state :balances] [{:coin "USDC" :total "1000" :hold "0"}]))
        next-state (apply-save-many state (actions/set-outcome-ticket-entry state {:size-display "25"}))
        form (trading/order-form-draft next-state)]
    (is (= :buy (:side form)) "typing an amount after the shares are gone is a buy")
    (is (= :quote (:size-input-mode form))))
  (testing "a held side stays in Cash out"
    (let [state (assoc-in (outcome-state) [:order-form :side] :sell)
          next-state (apply-save-many state (actions/set-outcome-ticket-entry state {:size-display "25"}))]
      (is (= :sell (:side (trading/order-form-draft next-state)))))))

(deftest phone-buy-bar-opens-the-sheet-as-a-buy-in-one-action-test
  (let [state (-> (outcome-state)
                  (assoc-in [:order-form :side] :sell)
                  (assoc-in [:order-form-ui :size-input-mode] :base))
        effects (actions/set-outcome-ticket-entry state {:side :buy :size-input-mode :quote :open-ticket-sheet? true})
        next-state (apply-save-many state effects)
        form (trading/order-form-draft next-state)]
    (is (= 1 (count effects)))
    (is (= :ticket (get-in next-state [:trade-ui :mobile-surface])))
    (is (= :buy (:side form)))
    (is (= :quote (:size-input-mode form)))))
