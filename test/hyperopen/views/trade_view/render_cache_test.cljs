(ns hyperopen.views.trade-view.render-cache-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.active-asset-view :as active-asset-view]
            [hyperopen.views.asset-selector-view :as asset-selector-view]
            [hyperopen.views.l2-orderbook-view :as l2-orderbook-view]
            [hyperopen.views.trade.order-form-view :as order-form-view]
            [hyperopen.views.trade-view :as trade-view]
            [hyperopen.views.trade.test-support :as support]))

(deftest trade-view-active-asset-panel-memoization-ignores-closed-selector-bookkeeping-test
  (let [active-asset-calls (atom 0)
        state-a (support/active-asset-state)
        state-b (-> (support/active-asset-state)
                    (assoc-in [:asset-selector :search-term] "eth")
                    (assoc-in [:asset-selector :scroll-top] 144)
                    (assoc-in [:asset-selector :highlighted-market-key] "perp:ETH")
                    (assoc-in [:active-assets :contexts "ETH"] {:coin "ETH"
                                                                :mark 3200.0})
                    (assoc-in [:active-assets :funding-predictability :by-coin "ETH"] {:mean 0.2}))]
    (support/with-viewport-width
      1280
      (fn []
        (with-redefs [active-asset-view/active-asset-view (fn [_state]
                                                            (swap! active-asset-calls inc)
                                                            [:div {:data-role "stub-active-asset"}])]
          (trade-view/trade-view state-a)
          (trade-view/trade-view state-b)
          (is (= 1 @active-asset-calls)))))))

(deftest trade-view-active-asset-panel-open-selector-still-reacts-to-dropdown-state-test
  (let [active-asset-calls (atom 0)
        state-a (assoc-in (support/active-asset-state)
                          [:asset-selector :visible-dropdown]
                          :asset-selector)
        state-b (assoc-in state-a
                          [:asset-selector :search-term]
                          "eth")]
    (support/with-viewport-width
      1280
      (fn []
        (with-redefs [asset-selector-view/asset-list-scroll-active? (constantly false)
                      active-asset-view/active-asset-view (fn [_state]
                                                            (swap! active-asset-calls inc)
                                                            [:div {:data-role "stub-active-asset"}])]
          (trade-view/trade-view state-a)
          (trade-view/trade-view state-b)
          (is (= 2 @active-asset-calls)))))))

(deftest trade-view-computes-account-equity-metrics-once-per-rendered-equity-surface-test
  (let [metrics-calls (atom 0)
        rendered-account-equity-views (atom 0)
        stub-metrics {:account-value-display 25}
        account-view (support/with-mobile-surface (support/active-asset-state)
                                                  :account)]
    (support/with-viewport-width
      430
      (fn []
        (with-redefs [account-equity-view/account-equity-metrics (fn [_state]
                                                                   (swap! metrics-calls inc)
                                                                   stub-metrics)
                      account-equity-view/account-equity-view (fn
                                                                ([_state]
                                                                 (swap! rendered-account-equity-views inc)
                                                                 [:div {:data-role "stub-account-equity"
                                                                        :data-metrics nil}])
                                                                ([_state opts]
                                                                 (swap! rendered-account-equity-views inc)
                                                                 [:div {:data-role "stub-account-equity"
                                                                        :data-metrics (:metrics opts)}]))
                      account-equity-view/funding-actions-view (fn
                                                                ([_state]
                                                                 [:div {:data-role "stub-mobile-funding-actions"}])
                                                                ([_state _opts]
                                                                 [:div {:data-role "stub-mobile-funding-actions"}]))]
          (let [view-node (trade-view/trade-view account-view)
                stub-equity-nodes (support/find-all-nodes view-node
                                                          #(= "stub-account-equity"
                                                              (get-in % [1 :data-role])))]
            (is (= 1 @metrics-calls))
            (is (= 1 @rendered-account-equity-views))
            (is (= 1 (count stub-equity-nodes)))
            (is (every? #(= stub-metrics
                            (get-in % [1 :data-metrics]))
                        stub-equity-nodes))))))))

(deftest trade-view-renders-heavy-surfaces-once-on-desktop-layout-test
  (support/with-viewport-width
    1280
    (fn []
      (let [active-asset-calls (atom 0)
            chart-calls (atom 0)
            orderbook-calls (atom 0)
            order-form-calls (atom 0)
            account-info-calls (atom 0)
            equity-metrics-calls (atom 0)
            account-equity-calls (atom 0)]
        (with-redefs [active-asset-view/active-asset-view (fn [_state]
                                                            (swap! active-asset-calls inc)
                                                            [:div {:data-role "stub-active-asset"}])
                      trade-modules/render-trade-chart-view (fn [_state]
                                                              (swap! chart-calls inc)
                                                              [:div {:data-role "stub-chart"}])
                      l2-orderbook-view/l2-orderbook-view (fn [_state]
                                                            (swap! orderbook-calls inc)
                                                            [:div {:data-role "stub-orderbook"}])
                      order-form-view/order-form-view (fn [_state]
                                                        (swap! order-form-calls inc)
                                                        [:div {:data-role "stub-order-form"}])
                      account-info-view/account-info-view (fn
                                                            ([_state]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}])
                                                            ([_state _options]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}]))
                      account-equity-view/account-equity-metrics (fn [_state]
                                                                   (swap! equity-metrics-calls inc)
                                                                   {:account-value-display 12})
                      account-equity-view/account-equity-view (fn
                                                                ([_state]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}])
                                                                ([_state _opts]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}]))]
          (let [view-node (trade-view/trade-view (support/active-asset-state))]
            (is (= 1 @active-asset-calls))
            (is (= 1 @chart-calls))
            (is (= 1 @orderbook-calls))
            (is (= 1 @order-form-calls))
            (is (= 1 @account-info-calls))
            (is (= 1 @equity-metrics-calls))
            (is (= 1 @account-equity-calls))
            (is (= 1 (count (support/find-all-nodes view-node #(= "stub-active-asset"
                                                                  (get-in % [1 :data-role]))))))
            (is (= 1 (count (support/find-all-nodes view-node #(= "stub-orderbook"
                                                                  (get-in % [1 :data-role]))))))
            (is (= 1 (count (support/find-all-nodes view-node #(= "stub-order-form"
                                                                  (get-in % [1 :data-role]))))))
            (is (= 1 (count (support/find-all-nodes view-node #(= "stub-account-info"
                                                                  (get-in % [1 :data-role]))))))
            (is (= 1 (count (support/find-all-nodes view-node #(= "stub-account-equity"
                                                                  (get-in % [1 :data-role]))))))))))))

(deftest trade-view-memoizes-non-orderbook-subtrees-across-orderbook-only-updates-test
  (support/with-viewport-width
    1280
    (fn []
      (let [active-asset-calls (atom 0)
            chart-calls (atom 0)
            orderbook-calls (atom 0)
            order-form-calls (atom 0)
            account-info-calls (atom 0)
            equity-metrics-calls (atom 0)
            account-equity-calls (atom 0)
            state-a (-> (support/active-asset-state)
                        (assoc :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}]
                                                   :asks [{:px "101" :sz "1"}]}}))
            state-b (assoc state-a
                           :orderbooks {"BTC" {:bids [{:px "98.5" :sz "2.5"}]
                                               :asks [{:px "101.5" :sz "1.5"}]}})]
        (with-redefs [active-asset-view/active-asset-view (fn [_state]
                                                            (swap! active-asset-calls inc)
                                                            [:div {:data-role "stub-active-asset"}])
                      trade-modules/render-trade-chart-view (fn [_state]
                                                              (swap! chart-calls inc)
                                                              [:div {:data-role "stub-chart"}])
                      l2-orderbook-view/l2-orderbook-view (fn [_state]
                                                            (swap! orderbook-calls inc)
                                                            [:div {:data-role "stub-orderbook"}])
                      order-form-view/order-form-view (fn [_state]
                                                        (swap! order-form-calls inc)
                                                        [:div {:data-role "stub-order-form"}])
                      account-info-view/account-info-view (fn
                                                            ([_state]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}])
                                                            ([_state _options]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}]))
                      account-equity-view/account-equity-metrics (fn [_state]
                                                                   (swap! equity-metrics-calls inc)
                                                                   {:account-value-display 12})
                      account-equity-view/account-equity-view (fn
                                                                ([_state]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}])
                                                                ([_state _opts]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}]))]
          (trade-view/trade-view state-a)
          (trade-view/trade-view state-b)
          (is (= 1 @active-asset-calls))
          (is (= 1 @chart-calls))
          (is (= 2 @orderbook-calls))
          (is (= 2 @order-form-calls))
          (is (= 1 @account-info-calls))
          (is (= 1 @equity-metrics-calls))
          (is (= 1 @account-equity-calls)))))))

(deftest trade-view-skips-websocket-only-rerenders-when-surface-freshness-cues-are-disabled-test
  (support/with-viewport-width
    1280
    (fn []
      (let [active-asset-calls (atom 0)
            chart-calls (atom 0)
            orderbook-calls (atom 0)
            order-form-calls (atom 0)
            account-info-calls (atom 0)
            equity-metrics-calls (atom 0)
            account-equity-calls (atom 0)
            state-a (-> (support/active-asset-state)
                        (assoc :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}]
                                                   :asks [{:px "101" :sz "1"}]}})
                        (assoc-in [:websocket :health] {:generated-at-ms 5000}))
            state-b (assoc-in state-a
                              [:websocket :health :generated-at-ms]
                              6000)]
        (with-redefs [active-asset-view/active-asset-view (fn [_state]
                                                            (swap! active-asset-calls inc)
                                                            [:div {:data-role "stub-active-asset"}])
                      trade-modules/render-trade-chart-view (fn [_state]
                                                              (swap! chart-calls inc)
                                                              [:div {:data-role "stub-chart"}])
                      l2-orderbook-view/l2-orderbook-view (fn [_state]
                                                            (swap! orderbook-calls inc)
                                                            [:div {:data-role "stub-orderbook"}])
                      order-form-view/order-form-view (fn [_state]
                                                        (swap! order-form-calls inc)
                                                        [:div {:data-role "stub-order-form"}])
                      account-info-view/account-info-view (fn
                                                            ([_state]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}])
                                                            ([_state _options]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}]))
                      account-equity-view/account-equity-metrics (fn [_state]
                                                                   (swap! equity-metrics-calls inc)
                                                                   {:account-value-display 12})
                      account-equity-view/account-equity-view (fn
                                                                ([_state]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}])
                                                                ([_state _opts]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}]))]
          (trade-view/trade-view state-a)
          (trade-view/trade-view state-b)
          (is (= 1 @active-asset-calls))
          (is (= 1 @chart-calls))
          (is (= 1 @orderbook-calls))
          (is (= 1 @order-form-calls))
          (is (= 1 @account-info-calls))
          (is (= 1 @equity-metrics-calls))
          (is (= 1 @account-equity-calls)))))))

(deftest trade-view-freezes-orderbook-and-order-form-during-active-selector-scroll-test
  (support/with-viewport-width
    1280
    (fn []
      (let [orderbook-calls (atom 0)
            order-form-calls (atom 0)
            scroll-active?* (atom false)
            state-a (-> (support/active-asset-state)
                        (assoc-in [:asset-selector :visible-dropdown] :asset-selector)
                        (assoc :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}]
                                                   :asks [{:px "101" :sz "1"}]}}))
            state-b (-> state-a
                        (assoc :orderbooks {"BTC" {:bids [{:px "98.5" :sz "2.5"}]
                                                   :asks [{:px "101.5" :sz "1.5"}]}})
                        (assoc-in [:active-assets :contexts "BTC" :mark] 64125.0)
                        (assoc-in [:active-assets :contexts "BTC" :change24h] 1600.0))]
        (with-redefs [asset-selector-view/asset-list-scroll-active? (fn []
                                                                      @scroll-active?*)
                      l2-orderbook-view/l2-orderbook-view (fn [_state]
                                                            (swap! orderbook-calls inc)
                                                            [:div {:data-role "stub-orderbook"}])
                      order-form-view/order-form-view (fn [_state]
                                                        (swap! order-form-calls inc)
                                                        [:div {:data-role "stub-order-form"}])]
          (trade-view/trade-view state-a)
          (reset! scroll-active?* true)
          (trade-view/trade-view state-b)
          (is (= 1 @orderbook-calls))
          (is (= 1 @order-form-calls))
          (reset! scroll-active?* false)
          (trade-view/trade-view state-b)
          (is (= 2 @orderbook-calls))
          (is (= 2 @order-form-calls)))))))

(deftest trade-view-rerenders-orderbook-and-order-form-when-selector-scroll-inactive-test
  (support/with-viewport-width
    1280
    (fn []
      (let [orderbook-calls (atom 0)
            order-form-calls (atom 0)
            state-a (-> (support/active-asset-state)
                        (assoc :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}]
                                                   :asks [{:px "101" :sz "1"}]}}))
            state-b (-> state-a
                        (assoc :orderbooks {"BTC" {:bids [{:px "98.5" :sz "2.5"}]
                                                   :asks [{:px "101.5" :sz "1.5"}]}})
                        (assoc-in [:active-assets :contexts "BTC" :mark] 64125.0)
                        (assoc-in [:active-assets :contexts "BTC" :change24h] 1600.0))]
        (with-redefs [asset-selector-view/asset-list-scroll-active? (constantly false)
                      l2-orderbook-view/l2-orderbook-view (fn [_state]
                                                            (swap! orderbook-calls inc)
                                                            [:div {:data-role "stub-orderbook"}])
                      order-form-view/order-form-view (fn [_state]
                                                        (swap! order-form-calls inc)
                                                        [:div {:data-role "stub-order-form"}])]
          (trade-view/trade-view state-a)
          (trade-view/trade-view state-b)
          (is (= 2 @orderbook-calls))
          (is (= 2 @order-form-calls)))))))

(deftest trade-view-freezes-chart-and-account-panels-during-active-selector-scroll-test
  (support/with-viewport-width
    1280
    (fn []
      (let [chart-calls (atom 0)
            account-info-calls (atom 0)
            equity-metrics-calls (atom 0)
            account-equity-calls (atom 0)
            scroll-active?* (atom false)
            state-a (-> (support/active-asset-state)
                        (assoc-in [:asset-selector :visible-dropdown] :asset-selector)
                        (assoc-in [:websocket-ui :show-surface-freshness-cues?] true)
                        (assoc-in [:websocket :health] {:generated-at-ms 5000}))
            state-b (-> state-a
                        (assoc-in [:asset-selector :market-by-key "perp:BTC" :mark] 64125.0)
                        (assoc-in [:asset-selector :market-by-key "perp:ETH" :mark] 3300.0)
                        (assoc-in [:websocket :health :generated-at-ms] 6000)
                        (assoc-in [:webdata2 :updated-at] 1234))]
        (with-redefs [asset-selector-view/asset-list-scroll-active? (fn []
                                                                      @scroll-active?*)
                      trade-modules/render-trade-chart-view (fn [_state]
                                                              (swap! chart-calls inc)
                                                              [:div {:data-role "stub-chart"}])
                      account-info-view/account-info-view (fn
                                                            ([_state]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}])
                                                            ([_state _options]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}]))
                      account-equity-view/account-equity-metrics (fn [_state]
                                                                   (swap! equity-metrics-calls inc)
                                                                   {:account-value-display 12})
                      account-equity-view/account-equity-view (fn
                                                                ([_state]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}])
                                                                ([_state _opts]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}]))]
          (trade-view/trade-view state-a)
          (reset! scroll-active?* true)
          (trade-view/trade-view state-b)
          (is (= 1 @chart-calls))
          (is (= 1 @account-info-calls))
          (is (= 1 @equity-metrics-calls))
          (is (= 1 @account-equity-calls))
          (reset! scroll-active?* false)
          (trade-view/trade-view state-b)
          (is (= 2 @chart-calls))
          (is (= 2 @account-info-calls))
          (is (= 2 @equity-metrics-calls))
          (is (= 2 @account-equity-calls)))))))

(deftest trade-view-freezes-desktop-active-asset-panel-during-active-selector-scroll-test
  (support/with-viewport-width
    1280
    (fn []
      (let [active-asset-calls (atom 0)
            scroll-active?* (atom false)
            state-a (-> (support/active-asset-state)
                        (assoc-in [:asset-selector :visible-dropdown] :asset-selector))
            state-b (-> state-a
                        (assoc-in [:asset-selector :search-term] "eth")
                        (assoc-in [:active-assets :contexts "BTC" :mark] 64125.0)
                        (assoc-in [:asset-selector :highlighted-market-key] "perp:ETH"))]
        (with-redefs [asset-selector-view/asset-list-scroll-active? (fn []
                                                                      @scroll-active?*)
                      active-asset-view/active-asset-view (fn [_state]
                                                            (swap! active-asset-calls inc)
                                                            [:div {:data-role "stub-active-asset"}])]
          (trade-view/trade-view state-a)
          (reset! scroll-active?* true)
          (trade-view/trade-view state-b)
          (is (= 1 @active-asset-calls))
          (reset! scroll-active?* false)
          (trade-view/trade-view state-b)
          (is (= 2 @active-asset-calls)))))))
