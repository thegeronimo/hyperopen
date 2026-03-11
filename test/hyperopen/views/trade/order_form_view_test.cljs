(ns hyperopen.views.trade.order-form-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form.test-support :refer [base-state
                                                                   collect-strings
                                                                   find-first-node
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

(deftest scale-preview-renders-in-footer-after-submit-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                     :size "1000"
                                                     :scale {:start "84"
                                                             :end "79"
                                                             :count 5
                                                             :skew "1.00"}}))
        tokens (vec (collect-strings view-node))
        submit-index (first-index tokens "Place Order")
        start-index (first-index tokens "Start")
        end-index (first-index tokens "End")
        order-value-index (first-index tokens "Order Value")
        margin-index (first-index tokens "Margin Required")
        fees-index (first-index tokens "Fees")]
    (is (number? submit-index))
    (is (number? start-index))
    (is (number? end-index))
    (is (number? order-value-index))
    (is (number? margin-index))
    (is (number? fees-index))
    (is (< submit-index start-index))
    (is (< start-index end-index))
    (is (< end-index order-value-index))
    (is (< order-value-index margin-index))
    (is (< margin-index fees-index))))

(deftest submit-button-uses-compact-height-test
  (let [view-node (view/order-form-view (base-state))
        submit-button (find-first-node view-node
                                       (fn [node]
                                         (let [attrs (when (map? (second node)) (second node))]
                                           (= "trade-submit-order-button"
                                              (:data-parity-id attrs)))))
        classes (set (get-in submit-button [1 :class]))]
    (is (some? submit-button))
    (is (contains? classes "h-[33px]"))
    (is (not (contains? classes "h-10")))))

(deftest order-form-panel-does-not-force-legacy-min-height-test
  (let [view-node (view/order-form-view (base-state))
        panel (find-first-node view-node
                               (fn [node]
                                 (let [attrs (when (map? (second node)) (second node))]
                                   (= "order-form" (:data-parity-id attrs)))))
        classes (set (get-in panel [1 :class]))]
    (is (some? panel))
    (is (not (contains? classes "min-h-[500px]")))
    (is (not (contains? classes "lg:min-h-[560px]")))
    (is (not (contains? classes "xl:min-h-[640px]")))))
