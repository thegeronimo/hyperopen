(ns hyperopen.state.trading.outcome-order-policy-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.market :as trading-market]
            [hyperopen.state.trading :as trading]))

(def ^:private yes-side {:side-index 0 :coin "#1610" :asset-id 100001610 :mark "0.62" :mid "0.62" :bid "0.61" :ask "0.63"})
(def ^:private no-side {:side-index 1 :coin "#1611" :asset-id 100001611 :mark "0.38" :mid "0.38"})

(defn- outcome-state
  [{:keys [form ui stale? balances asks]
    :or {balances [{:coin "USDC" :total "1000" :hold "0"}
                   {:coin "+1610" :total "100" :hold "0"}]
         asks [{:px "0.42" :sz "500"}]}}]
  (let [options [{:outcome-id 161 :label "A" :sides [yes-side no-side]}
                 {:outcome-id 162 :label "B"
                  :sides [{:side-index 0 :coin "#1620" :asset-id 100001620
                           :mark (if stale? "0.6" "0.3") :mid (if stale? "0.6" "0.3")
                           :bid (if stale? "0.59" "0.29") :ask (if stale? "0.61" "0.31")}
                          {:side-index 1 :coin "#1621" :asset-id 100001621}]}]]
    {:active-asset "#1610"
     :active-market {:key "question:16"
                     :coin "#1610"
                     :market-type :outcome
                     :outcome-shape :pick-one
                     :asset-id 100001610
                     :szDecimals 0
                     :outcome-option-id 161
                     :outcome-sides [yes-side no-side]
                     :question-options options}
     :orderbooks {"#1610" {:bids [{:px "0.40" :sz "500"}] :asks asks}}
     :spot {:clearinghouse-state {:balances balances}}
     :order-form (merge (trading/default-order-form)
                        {:side :buy :type :limit :price "" :size "10"}
                        form)
     :order-form-ui (merge (trading/default-order-form-ui) {:entry-mode :limit} ui)
     :order-form-runtime (trading/default-order-form-runtime)}))

(defn- policy
  [state]
  (trading/submit-policy state (trading/order-form-draft state) {:mode :submit :agent-ready? true}))

(deftest outcome-blank-limit-price-is-blocked-not-filled-at-mid-test
  (let [state (outcome-state {})
        result (policy state)]
    (is (= :outcome-order-blocked (:reason result)))
    (is (= "Enter a limit price" (:error-message result)))
    (is (= "" (get-in result [:form :price])) "no midpoint fallback for outcome limits")
    (is (nil? (:request result)) "nothing would be signed")))

(deftest outcome-limit-price-must-sit-inside-zero-and-one-test
  (doseq [price ["1" "1.2" "0"]]
    (is (= "Limit price must be between 0¢ and 100¢"
           (:error-message (policy (outcome-state {:form {:price price}}))))
        price))
  (is (nil? (:reason (policy (outcome-state {:form {:price "0.41"}}))))))

(deftest stale-confirmation-is-a-real-submit-gate-test
  (let [state (outcome-state {:stale? true :form {:price "0.41"}})]
    (is (true? (trading/outcome-stale-listed-prices? (:active-market state))))
    (is (= "Confirm that these prices may be stale" (:error-message (policy state))))
    (is (nil? (:reason (policy (assoc-in state [:order-form :outcome-stale-confirmed?] true)))))
    (testing "a consistent board needs no confirmation"
      (is (false? (trading/outcome-stale-listed-prices? (:active-market (outcome-state {})))))
      (is (nil? (:reason (policy (outcome-state {:form {:price "0.41"}}))))))))

(deftest outcome-balance-checks-before-submit-test
  (is (= "Not enough USDC for this order"
         (:error-message (policy (outcome-state {:form {:price "0.41" :size "5000"}})))))
  (is (= "You hold fewer shares than that"
         (:error-message (policy (outcome-state {:form {:side :sell :price "0.41" :size "150"}})))))
  (is (= "You hold fewer shares than that"
         (:error-message (policy (outcome-state {:form {:side :sell :price "0.41" :size "80"}
                                                 :balances [{:coin "USDC" :total "1000" :hold "0"}
                                                            {:coin "+1610" :total "100" :hold "30"}]}))))
      "shares held by open orders cannot be sold twice")
  (is (nil? (:reason (policy (outcome-state {:form {:side :sell :price "0.41" :size "100"}}))))))

(deftest perp-settings-do-not-leak-into-outcome-orders-test
  (let [state (outcome-state {:form {:type :scale :post-only true :reduce-only true :tif :ioc
                                     :tp {:enabled? true :trigger "0.9"}
                                     :sl {:enabled? true :trigger "0.1"}
                                     :price "0.41"}
                              :ui {:entry-mode :pro}})
        form (trading/order-form-draft state)
        result (policy state)
        order (first (get-in result [:request :action :orders]))]
    (is (= :limit (:type form)))
    (is (= :limit (:entry-mode form)))
    (is (false? (:post-only form)))
    (is (false? (get-in form [:tp :enabled?])))
    (is (false? (get-in form [:sl :enabled?])))
    (is (= {:limit {:tif "Gtc"}} (:t order)))
    (is (= 1 (count (get-in result [:request :action :orders]))) "no TP/SL legs")))

(deftest outcome-market-order-price-stays-below-one-test
  (let [state (outcome-state {:form {:type :market} :ui {:entry-mode :market}
                              :asks [{:px "0.99" :sz "500"}]})
        priced (trading/apply-market-price state (trading/order-form-draft state))]
    (is (= "0.99999" (:price priced)))
    (is (< (js/parseFloat (:price priced)) 1))))

(deftest price-truncation-forgives-float-error-test
  (is (= "0.29" (trading-market/canonical-order-price-string {:market {:szDecimals 0 :market-type :spot}} 0.29)))
  (is (= "0.57" (trading-market/canonical-order-price-string {:market {:szDecimals 0}} 0.57)))
  (is (= "29" (trading-market/base-size-string {:market {:szDecimals 0}} 29))))

(deftest outcome-available-reads-spot-usdc-of-the-trading-account-test
  (is (= 1000 (trading/available-to-trade (outcome-state {}))))
  (let [state (outcome-state {})]
    (is (= (get-in state [:spot :clearinghouse-state]) (trading/spot-clearinghouse-state state)))))

(deftest unheld-sell-is-blocked-in-every-account-mode-test
  (let [unheld {:form {:side :sell :price "0.41" :size "5"}
                :balances [{:coin "USDC" :total "1000" :hold "0"}]}]
    (is (= "You hold fewer shares than that"
           (:error-message (policy (outcome-state unheld)))))
    (is (= "You hold fewer shares than that"
           (:error-message (policy (assoc-in (outcome-state unheld) [:account :mode] :unified))))
        "unified mode used to skip the held-shares check")
    (is (= "You hold fewer shares than that"
           (:error-message (policy (outcome-state (assoc unheld :balances [])))))
        "no balances loaded means nothing is known to be held")))

(deftest market-orders-off-is-a-submit-gate-test
  (testing "a one-sided book forbids a market order even off the ticket"
    (let [state (outcome-state {:form {:type :market} :ui {:entry-mode :market}
                                :asks []})]
      (is (= "Market orders are off for this side; set a limit price"
             (:error-message (policy state))))))
  (testing "a 40c-wide side quote forbids a market order"
    (let [state (-> (outcome-state {:form {:type :market} :ui {:entry-mode :market}})
                    (assoc-in [:active-market :outcome-sides 0 :bid] "0.2")
                    (assoc-in [:active-market :outcome-sides 0 :ask] "0.6"))]
      (is (= "Market orders are off for this side; set a limit price"
             (:error-message (policy state))))))
  (testing "a tight two-sided book allows it"
    (is (not= :outcome-order-blocked
              (:reason (policy (outcome-state {:form {:type :market} :ui {:entry-mode :market}})))))))

(deftest stale-gate-reads-top-of-book-written-by-the-feed-test
  (let [side (fn [coin] {:side-index 0 :coin coin :prevDayRaw "0.5"})
        market {:market-type :outcome
                :outcome-shape :pick-one
                :question-options [{:outcome-id 1 :sides [(assoc (side "#10") :bid 0.6 :ask 0.62)
                                                          {:side-index 1 :coin "#11"}]}
                                   {:outcome-id 2 :sides [(side "#20")
                                                          {:side-index 1 :coin "#21" :bid 0.48 :ask 0.5}]}]}]
    (testing "a side's mirror (only side 1 fed) counts as a live chance"
      (is (true? (trading/outcome-stale-listed-prices? market))))
    (is (false? (trading/outcome-stale-listed-prices?
                 (update-in market [:question-options 1 :sides 1] dissoc :bid :ask))))))
