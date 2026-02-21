(ns hyperopen.views.trade.order-form-view.scale-preview-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form.test-support :refer [base-state
                                                                   collect-strings
                                                                   collect-text-and-placeholders
                                                                   find-first-node
                                                                   metric-value-text
                                                                   preview-leading-size]]
            [hyperopen.views.trade.order-form-view :as view]))

(deftest scale-mode-renders-total-orders-and-size-skew-test
  (let [view-node (view/order-form-view (base-state {:type :scale}))
        tokens (set (collect-text-and-placeholders view-node))]
    (is (contains? tokens "Total Orders"))
    (is (contains? tokens "Size Skew"))
    (is (not (contains? tokens "Order count")))))

(deftest scale-mode-renders-start-and-end-preview-rows-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                      :size "13.8"
                                                      :scale {:start "80"
                                                              :end "60"
                                                              :count 20
                                                              :skew "1.00"}}))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Start"))
    (is (contains? strings "End"))))

(deftest scale-preview-rows-render-formatted-boundary-values-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                      :size "13.8"
                                                      :scale {:start "80"
                                                              :end "60"
                                                              :count 20
                                                              :skew "1.00"}}))]
    (is (= "0.6899 BTC @ 80.00 USDC" (metric-value-text view-node "Start")))
    (is (= "0.6899 BTC @ 60.00 USDC" (metric-value-text view-node "End")))))

(deftest scale-preview-rows-render-na-when-inputs-are-incomplete-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                      :size "13.8"
                                                      :scale {:start ""
                                                              :end "60"
                                                              :count 20
                                                              :skew "1.00"}}))]
    (is (= "N/A" (metric-value-text view-node "Start")))
    (is (= "N/A" (metric-value-text view-node "End")))))

(deftest scale-preview-rows-use-active-asset-as-canonical-base-symbol-fallback-test
  (let [state (-> (base-state {:type :scale
                               :size "13.8"
                               :scale {:start "80"
                                       :end "60"
                                       :count 20
                                       :skew "1.00"}})
                  (assoc :active-asset "SOL")
                  (assoc :active-market {:quote "USDC"
                                         :mark 100
                                         :maxLeverage 40
                                         :market-type :perp
                                         :szDecimals 4}))
        view-node (view/order-form-view state)]
    (is (= "0.6899 SOL @ 80.00 USDC" (metric-value-text view-node "Start")))
    (is (= "0.6899 SOL @ 60.00 USDC" (metric-value-text view-node "End")))))

(deftest scale-preview-values-follow-linear-ramp-ratio-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                      :size "9.45"
                                                      :scale {:start "80"
                                                              :end "70"
                                                              :count 20
                                                              :skew "2"}}))
        start-size (preview-leading-size (metric-value-text view-node "Start"))
        end-size (preview-leading-size (metric-value-text view-node "End"))]
    (is (number? start-size))
    (is (number? end-size))
    (is (<= (js/Math.abs (- 2 (/ end-size start-size))) 0.01))))

(deftest scale-preview-high-skew-front-size-near-zero-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                      :size "9.45"
                                                      :scale {:start "80"
                                                              :end "70"
                                                              :count 20
                                                              :skew "50"}}))
        start-size (preview-leading-size (metric-value-text view-node "Start"))
        end-size (preview-leading-size (metric-value-text view-node "End"))]
    (is (< start-size 0.03))
    (is (> end-size 0.9))))

(deftest scale-preview-low-skew-back-size-smaller-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                      :size "9.45"
                                                      :scale {:start "80"
                                                              :end "70"
                                                              :count 20
                                                              :skew "0.25"}}))
        start-size (preview-leading-size (metric-value-text view-node "Start"))
        end-size (preview-leading-size (metric-value-text view-node "End"))]
    (is (> start-size 0.7))
    (is (< end-size 0.2))))

(deftest size-skew-input-dispatch-path-test
  (let [view-node (view/order-form-view (base-state {:type :scale}))
        skew-input (find-first-node view-node
                                    (fn [node]
                                      (let [attrs (when (map? (second node)) (second node))]
                                        (and (= :input (first node))
                                             (= "Size Skew" (:aria-label attrs))))))
        skew-input-on (get-in skew-input [1 :on :input])
        skew-input-classes (set (get-in skew-input [1 :class]))]
    (is (some? skew-input))
    (is (contains? skew-input-classes "text-right"))
    (is (= [[:actions/update-order-form [:scale :skew] [:event.target/value]]]
           skew-input-on))))

(deftest scale-mode-hides-tpsl-toggle-test
  (let [view-node (view/order-form-view (base-state {:type :scale}))
        strings (set (collect-strings view-node))]
    (is (not (contains? strings "Take Profit / Stop Loss")))))

(deftest scale-mode-hides-liquidation-price-metric-row-test
  (let [view-node (view/order-form-view (base-state {:type :scale}))
        strings (set (collect-strings view-node))]
    (is (not (contains? strings "Liquidation Price")))
    (is (contains? strings "Order Value"))
    (is (contains? strings "Margin Required"))
    (is (contains? strings "Fees"))))

