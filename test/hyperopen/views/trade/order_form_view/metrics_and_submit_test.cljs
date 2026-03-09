(ns hyperopen.views.trade.order-form-view.metrics-and-submit-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.state.trading :as trading]
            [hyperopen.views.trade.order-form.test-support :refer [base-state
                                                                   button-node-by-click-action
                                                                   collect-strings
                                                                   find-first-node
                                                                   liquidation-price-tooltip-text
                                                                   metric-value-text]]
            [hyperopen.views.trade.order-form-view :as view]))

(deftest slippage-is-hidden-for-limit-and-shown-for-market-test
  (let [limit-view (view/order-form-view (base-state {:type :limit}))
        limit-strings (set (collect-strings limit-view))
        market-view (view/order-form-view (base-state {:type :market}))
        market-strings (set (collect-strings market-view))]
    (is (not (contains? limit-strings "Slippage")))
    (is (contains? market-strings "Slippage"))))

(deftest market-slippage-row-renders-estimate-with-4dp-and-max-with-2dp-test
  (let [state (-> (base-state {:type :market
                               :side :buy
                               :size "2.5"})
                  (assoc :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}
                                                    {:px "100" :sz "2"}]
                                             :asks [{:px "102" :sz "1"}
                                                    {:price "101" :size "2"}
                                                    {:p "103" :s "5"}]}}))
        view-node (view/order-form-view state)
        strings (set (collect-strings view-node))]
    (is (contains? strings "Slippage"))
    (is (contains? strings "Est 0.6965% / Max 8.00%"))))

(deftest order-summary-and-position-fallback-render-test
  (let [state (assoc (base-state)
                     :orderbooks {}
                     :webdata2 {}
                     :active-market {:coin "BTC" :quote "USDC" :market-type :perp}
                     :order-form (merge (trading/default-order-form) {:type :limit :price "" :size ""}))
        view-node (view/order-form-view state)
        strings (set (collect-strings view-node))]
    (is (contains? strings "Available to Trade"))
    (is (contains? strings "Current position"))
    (is (contains? strings "Liquidation Price"))
    (is (contains? strings "Order Value"))
    (is (contains? strings "Margin Required"))
    (is (contains? strings "Fees"))
    (is (contains? strings "N/A"))))

(deftest fees-row-renders-crossed-baseline-when-effective-fee-is-discounted-test
  (let [state (-> (base-state {:type :limit :size "1" :price "1"})
                  (assoc :active-market {:coin "USDT/USDC"
                                         :quote "USDC"
                                         :mark 1
                                         :market-type :spot
                                         :stable-pair? true
                                         :szDecimals 4})
                  (assoc :portfolio {:user-fees {:userSpotCrossRate 0.0003
                                                 :userSpotAddRate 0.00012
                                                 :activeReferralDiscount 0.1
                                                 :activeStakingDiscount {:discount 0.25}}}))
        view-node (view/order-form-view state)
        strings (set (collect-strings view-node))
        crossed-node (find-first-node view-node
                                      (fn [node]
                                        (let [attrs (when (map? (second node)) (second node))
                                              classes (set (:class attrs))]
                                          (contains? classes "line-through"))))]
    (is (contains? strings "Current fee:"))
    (is (contains? strings "Base tier fee:"))
    (is (contains? strings "0.0054% / 0.0022%"))
    (is (contains? strings "0.0400% / 0.0160%"))
    (is (contains? strings "Taker orders pay a 0.0054% fee. Maker orders pay a 0.0022% fee."))
    (is (some? crossed-node))))

(deftest fees-row-omits-current-label-when-no-baseline-fee-test
  (let [view-node (view/order-form-view (base-state {:type :limit :size "1" :price "100"}))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Fees"))
    (is (contains? strings "0.0450% / 0.0150%"))
    (is (contains? strings "Taker orders pay a 0.0450% fee. Maker orders pay a 0.0150% fee."))
    (is (not (contains? strings "Current fee:")))
    (is (not (contains? strings "Base tier fee:")))))

(deftest liquidation-price-renders-projected-value-for-flat-position-test
  (let [state (-> (base-state {:type :limit :side :buy :size "2" :price "100"})
                  (assoc :active-market {:coin "SOL"
                                         :quote "USDC"
                                         :mark 100
                                         :maxLeverage 50
                                         :market-type :perp
                                         :szDecimals 4})
                  (assoc :webdata2 {:clearinghouseState {:marginSummary {:accountValue "100"
                                                                         :totalMarginUsed "0"}
                                                         :assetPositions []}}))
        view-node (view/order-form-view state)]
    (is (= "$52.00" (metric-value-text view-node "Liquidation Price")))))

(deftest liquidation-price-na-renders-hyperliquid-tooltip-copy-test
  (let [view-node (view/order-form-view (base-state {:type :limit :size "" :price ""}))
        strings (set (collect-strings view-node))
        underlined-liquidation-label (find-first-node view-node
                                                      (fn [node]
                                                        (let [attrs (when (map? (second node)) (second node))
                                                              classes (set (:class attrs))
                                                              children (if attrs (drop 2 node) (drop 1 node))]
                                                          (and (= :span (first node))
                                                               (contains? classes "decoration-dashed")
                                                               (contains? classes "underline")
                                                               (contains? (set (collect-strings children))
                                                                          "Liquidation Price")))))]
    (is (contains? strings liquidation-price-tooltip-text))
    (is (some? underlined-liquidation-label))))

(deftest liquidation-price-value-omits-na-tooltip-copy-test
  (let [state (-> (base-state {:type :limit :side :buy :size "2" :price "100"})
                  (assoc :active-market {:coin "SOL"
                                         :quote "USDC"
                                         :mark 100
                                         :maxLeverage 50
                                         :market-type :perp
                                         :szDecimals 4})
                  (assoc :webdata2 {:clearinghouseState {:marginSummary {:accountValue "100"
                                                                         :totalMarginUsed "0"}
                                                         :assetPositions []}}))
        view-node (view/order-form-view state)
        strings (set (collect-strings view-node))]
    (is (not (contains? strings liquidation-price-tooltip-text)))))

(deftest available-to-trade-prefers-unified-spot-usdc-balance-test
  (let [state (-> (base-state)
                  (assoc :account {:mode :unified})
                  (assoc-in [:spot :clearinghouse-state :balances]
                            [{:coin "USDC"
                              :total "204.41936500"
                              :hold "3.03000000"}])
                  (assoc-in [:webdata2 :clearinghouseState :withdrawable] "0.03"))
        view-node (view/order-form-view state)]
    (is (= "201.39 USDC"
           (metric-value-text view-node "Available to Trade")))))

(deftest submit-button-is-disabled-until-required-limit-fields-are-present-test
  (let [disabled-view (view/order-form-view (base-state {:type :limit :size "" :price ""}))
        enabled-view (view/order-form-view (base-state {:type :limit :size "1" :price "100"}))
        disabled-button (button-node-by-click-action disabled-view :actions/submit-order)
        enabled-button (button-node-by-click-action enabled-view :actions/submit-order)
        disabled-attrs (second disabled-button)
        enabled-attrs (second enabled-button)
        disabled-classes (set (:class disabled-attrs))
        enabled-classes (set (:class enabled-attrs))]
    (is (some? disabled-button))
    (is (= "trade-submit-order-button" (:data-parity-id disabled-attrs)))
    (is (= true (:disabled disabled-attrs)))
    (is (contains? disabled-classes "bg-[rgb(23,69,63)]"))
    (is (contains? disabled-classes "cursor-not-allowed"))
    (is (some? enabled-button))
    (is (= "trade-submit-order-button" (:data-parity-id enabled-attrs)))
    (is (not (:disabled enabled-attrs)))
    (is (contains? enabled-classes "bg-primary"))
    (is (contains? enabled-classes "hover:bg-primary/90"))))

(deftest disabled-submit-tooltip-lists-required-fields-test
  (let [state (-> (base-state {:type :limit :size "" :price ""})
                  (assoc :orderbooks {})
                  (assoc :active-market {:coin "BTC"
                                         :quote "USDC"
                                         :maxLeverage 40
                                         :market-type :perp
                                         :szDecimals 4}))
        view-node (view/order-form-view state)
        tooltip (find-first-node view-node
                                 (fn [node]
                                   (let [attrs (when (map? (second node)) (second node))
                                         classes (set (:class attrs))]
                                     (contains? classes "order-submit-tooltip"))))
        tooltip-strings (set (collect-strings tooltip))]
    (is (some? tooltip))
    (is (contains? tooltip-strings "Fill required fields: Price, Size."))))

(deftest enabled-submit-hides-required-fields-tooltip-test
  (let [view-node (view/order-form-view (base-state {:type :limit :size "1" :price "100"}))
        tooltip (find-first-node view-node
                                 (fn [node]
                                   (let [attrs (when (map? (second node)) (second node))
                                         classes (set (:class attrs))]
                                     (contains? classes "order-submit-tooltip"))))]
    (is (nil? tooltip))))

(deftest spectate-mode-submit-block-renders-stop-control-and-tooltip-test
  (let [state (-> (base-state {:type :limit :size "1" :price "100"})
                  (assoc :asset-contexts {:BTC {:idx 0}})
                  (assoc :account-context {:spectate-mode {:active? true
                                                        :address "0x1234567890abcdef1234567890abcdef12345678"}}))
        view-node (view/order-form-view state)
        submit-button (button-node-by-click-action view-node :actions/submit-order)
        stop-button (button-node-by-click-action view-node :actions/stop-spectate-mode)
        stop-container (find-first-node view-node
                                        (fn [node]
                                          (= "order-form-spectate-mode-stop"
                                             (get-in node [1 :data-role]))))
        tooltip (find-first-node view-node
                                 (fn [node]
                                   (let [attrs (when (map? (second node)) (second node))
                                         classes (set (:class attrs))]
                                     (contains? classes "order-submit-tooltip"))))]
    (is (nil? submit-button))
    (is (some? stop-button))
    (is (some? stop-container))
    (is (contains? (set (collect-strings stop-container)) "Stop Spectate Mode"))
    (is (nil? tooltip))))
