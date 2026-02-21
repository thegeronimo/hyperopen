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

(deftest submit-button-renders-before-liquidation-metrics-test
  (let [view-node (view/order-form-view (base-state {:type :limit :size "1" :price "100"}))
        tokens (vec (collect-strings view-node))
        submit-index (first-index tokens "Place Order")
        liquidation-index (first-index tokens "Liquidation Price")]
    (is (number? submit-index))
    (is (number? liquidation-index))
    (is (< submit-index liquidation-index))))
