(ns hyperopen.trading.order-form-transitions-update-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-transitions :as transitions]))

(defn- base-state
  ([] (base-state {}))
  ([order-form-overrides]
   (let [order-form (merge (trading/default-order-form) order-form-overrides)]
     {:active-asset "BTC"
      :active-market {:coin "BTC"
                      :quote "USDC"
                      :mark 100
                      :maxLeverage 40
                      :market-type :perp
                      :szDecimals 4}
      :asset-contexts {:BTC {:idx 0}}
      :orderbooks {"BTC" {:bids [{:px "99"}]
                          :asks [{:px "101"}]}}
      :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                      :totalMarginUsed "250"}}}
      :order-form order-form
      :order-form-ui (trading/default-order-form-ui)
      :order-form-runtime (trading/default-order-form-runtime)})))

(deftest side-change-preserves-existing-tpsl-triggers-and-offset-inputs-test
  (let [state (base-state {:type :limit
                           :side :buy
                           :price "100"
                           :size "2"
                           :ui-leverage 20
                           :tpsl {:unit :usd}
                           :tp {:enabled? true
                                :trigger "110"
                                :offset-input "20"
                                :is-market true
                                :limit ""}
                           :sl {:enabled? true
                                :trigger "90"
                                :offset-input "20"
                                :is-market true
                                :limit ""}})
        transition (transitions/update-order-form state [:side] :sell)]
    (is (= :sell (get-in transition [:order-form :side])))
    (is (= "20" (get-in transition [:order-form :tp :offset-input])))
    (is (= "20" (get-in transition [:order-form :sl :offset-input])))
    (is (= "110" (get-in transition [:order-form :tp :trigger])))
    (is (= "90" (get-in transition [:order-form :sl :trigger])))
    (is (true? (get-in transition [:order-form :tp :enabled?])))
    (is (true? (get-in transition [:order-form :sl :enabled?])))))

(deftest direct-trigger-edit-clears-only-the-target-leg-offset-cache-test
  (let [state (base-state {:type :limit
                           :side :buy
                           :price "100"
                           :size "2"
                           :ui-leverage 20
                           :tpsl {:unit :usd}
                           :tp {:enabled? true
                                :trigger "110"
                                :offset-input "20"
                                :is-market true
                                :limit ""}
                           :sl {:enabled? true
                                :trigger "90"
                                :offset-input "20"
                                :is-market true
                                :limit ""}})
        tp-transition (transitions/update-order-form state [:tp :trigger] "120")
        sl-transition (transitions/update-order-form state [:sl :trigger] "80")]
    (is (= "" (get-in tp-transition [:order-form :tp :offset-input])))
    (is (= "20" (get-in tp-transition [:order-form :sl :offset-input])))
    (is (= "120" (get-in tp-transition [:order-form :tp :trigger])))
    (is (= "90" (get-in tp-transition [:order-form :sl :trigger])))
    (is (= "" (get-in sl-transition [:order-form :sl :offset-input])))
    (is (= "20" (get-in sl-transition [:order-form :tp :offset-input])))
    (is (= "80" (get-in sl-transition [:order-form :sl :trigger])))
    (is (= "110" (get-in sl-transition [:order-form :tp :trigger])))))

(deftest blank-offset-input-clears-only-the-target-leg-trigger-test
  (let [state (base-state {:type :limit
                           :side :buy
                           :price "100"
                           :size "2"
                           :ui-leverage 20
                           :tpsl {:unit :usd}
                           :tp {:enabled? true
                                :trigger "110"
                                :offset-input "20"
                                :is-market true
                                :limit ""}
                           :sl {:enabled? true
                                :trigger "90"
                                :offset-input "20"
                                :is-market true
                                :limit ""}})
        transition (transitions/update-order-form state [:tp :offset-input] "")]
    (is (= "" (get-in transition [:order-form :tp :offset-input])))
    (is (= "" (get-in transition [:order-form :tp :trigger])))
    (is (false? (get-in transition [:order-form :tp :enabled?])))
    (is (= "20" (get-in transition [:order-form :sl :offset-input])))
    (is (= "90" (get-in transition [:order-form :sl :trigger])))
    (is (true? (get-in transition [:order-form :sl :enabled?])))))
