(ns hyperopen.views.trade.order-form-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form.test-support :refer [base-state
                                                                   collect-strings
                                                                   first-index]]
            [hyperopen.views.trade.order-form-view :as view]))

(deftest order-form-parity-controls-render-test
  (let [view-node (view/order-form-view (base-state))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Cross"))
    (is (contains? strings "20x"))
    (is (contains? strings "Classic"))
    (is (contains? strings "Market"))
    (is (contains? strings "Limit"))
    (is (contains? strings "Pro"))
    (is (contains? strings "Buy / Long"))
    (is (contains? strings "Sell / Short"))))

(deftest leverage-row-renders-isolated-margin-label-when-selected-test
  (let [view-node (view/order-form-view (base-state {:margin-mode :isolated}))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Isolated"))
    (is (not (contains? strings "Cross")))))

(deftest leverage-row-forces-isolated-label-when-market-disallows-cross-test
  (let [state (assoc (base-state {:margin-mode :cross})
                     :active-market {:coin "xyz:NATGAS"
                                     :quote "USDC"
                                     :market-type :perp
                                     :marginMode "noCross"
                                     :onlyIsolated true})
        view-node (view/order-form-view state)
        strings (set (collect-strings view-node))]
    (is (contains? strings "Isolated"))
    (is (not (contains? strings "Cross")))))

(deftest leverage-popover-renders-adjust-controls-when-open-test
  (let [view-node (view/order-form-view (base-state {:type :limit}
                                                     {:leverage-popover-open? true
                                                      :leverage-draft 18}))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Adjust Leverage"))
    (is (contains? strings "Maximum leverage"))
    (is (contains? strings "Max position size"))
    (is (contains? strings "Confirm"))))

(deftest submit-button-renders-before-liquidation-metrics-test
  (let [view-node (view/order-form-view (base-state {:type :limit :size "1" :price "100"}))
        tokens (vec (collect-strings view-node))
        submit-index (first-index tokens "Place Order")
        liquidation-index (first-index tokens "Liquidation Price")]
    (is (number? submit-index))
    (is (number? liquidation-index))
    (is (< submit-index liquidation-index))))
