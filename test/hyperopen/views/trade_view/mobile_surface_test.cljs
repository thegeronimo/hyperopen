(ns hyperopen.views.trade-view.mobile-surface-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade-view :as trade-view]
            [hyperopen.views.trade.test-support :as support]))

(deftest trade-view-funding-tooltip-open-state-lifts-chart-shell-test
  (let [closed-view (support/with-viewport-width
                      1280
                      #(trade-view/trade-view (support/active-asset-state)))
        open-view (support/with-viewport-width
                    1280
                    #(trade-view/trade-view
                      (support/with-visible-funding-tooltip
                       (support/active-asset-state)
                       "BTC")))
        closed-chart-panel (support/find-by-parity-id closed-view "trade-chart-panel")
        open-chart-panel (support/find-by-parity-id open-view "trade-chart-panel")
        open-market-strip (support/find-by-parity-id open-view "market-strip")
        closed-chart-classes (support/node-class-set closed-chart-panel)
        open-chart-classes (support/node-class-set open-chart-panel)
        open-market-strip-classes (support/node-class-set open-market-strip)]
    (is (contains? closed-chart-classes "overflow-hidden"))
    (is (not (contains? closed-chart-classes "overflow-visible")))
    (is (contains? open-chart-classes "overflow-visible"))
    (is (contains? open-chart-classes "z-[160]"))
    (is (not (contains? open-chart-classes "overflow-hidden")))
    (is (contains? open-market-strip-classes "z-[160]"))))

(deftest trade-view-mobile-funding-sheet-lifts-market-strip-above-chart-test
  (support/with-viewport-width
    430
    (fn []
      (let [open-view (trade-view/trade-view
                       (-> (support/active-asset-state)
                           (support/with-visible-funding-tooltip "BTC")
                           (assoc-in [:trade-ui :mobile-asset-details-open?] true)))
            mobile-market-strip (support/find-by-parity-id open-view "trade-mobile-active-asset-strip")
            chart-panel (support/find-by-parity-id open-view "trade-chart-panel")
            mobile-market-strip-classes (support/node-class-set mobile-market-strip)
            chart-panel-classes (support/node-class-set chart-panel)]
        (is (contains? mobile-market-strip-classes "overflow-visible"))
        (is (contains? mobile-market-strip-classes "z-[200]"))
        (is (contains? chart-panel-classes "overflow-visible"))
        (is (contains? chart-panel-classes "z-[160]"))))))

(deftest trade-view-restores-market-tables-and-keeps-account-surface-summary-only-test
  (support/with-viewport-width
    430
    (fn []
      (let [chart-view (trade-view/trade-view (support/base-state))
            chart-account-panel (support/find-by-parity-id chart-view "trade-account-tables-panel")
            chart-classes (support/node-class-set chart-account-panel)
            chart-account-text (set (support/collect-strings chart-account-panel))
            chart-summary-panel (support/find-by-parity-id chart-view "trade-mobile-account-summary-panel")
            trades-view (trade-view/trade-view (support/with-mobile-surface
                                                (support/base-state)
                                                :trades))
            trades-account-panel (support/find-by-parity-id trades-view "trade-account-tables-panel")
            trades-orderbook-panel (support/find-by-parity-id trades-view "trade-orderbook-panel")
            trades-order-entry-panel (support/find-by-parity-id trades-view "trade-order-entry-panel")
            trades-account-classes (support/node-class-set trades-account-panel)
            trades-orderbook-classes (support/node-class-set trades-orderbook-panel)
            trades-order-entry-classes (support/node-class-set trades-order-entry-panel)
            ticket-view (trade-view/trade-view (support/with-mobile-surface
                                                (support/base-state)
                                                :ticket))
            ticket-account-panel (support/find-by-parity-id ticket-view "trade-account-tables-panel")
            ticket-classes (support/node-class-set ticket-account-panel)
            ticket-desktop-equity-panel (support/find-by-parity-id ticket-view "trade-desktop-account-equity-panel")
            ticket-desktop-equity-classes (support/node-class-set ticket-desktop-equity-panel)
            ticket-summary-panel (support/find-by-parity-id ticket-view "trade-mobile-account-summary-panel")
            account-view (trade-view/trade-view (support/with-mobile-surface
                                                 (support/base-state)
                                                 :account))
            account-active-asset-strip (support/find-by-parity-id account-view "trade-mobile-active-asset-strip")
            account-active-asset-strip-classes (support/node-class-set account-active-asset-strip)
            account-surface-tabs (support/find-by-parity-id account-view "trade-mobile-surface-tabs")
            account-surface-tabs-classes (support/node-class-set account-surface-tabs)
            account-panel (support/find-by-parity-id account-view "trade-account-tables-panel")
            account-panel-classes (support/node-class-set account-panel)
            account-summary-panel (support/find-by-parity-id account-view "trade-mobile-account-summary-panel")
            account-summary-classes (support/node-class-set account-summary-panel)
            account-actions-panel (support/find-by-parity-id account-view "trade-mobile-account-actions")
            account-actions-classes (support/node-class-set account-actions-panel)
            account-mobile-panel (support/find-by-parity-id account-view "trade-mobile-account-surface")
            account-mobile-text (set (support/collect-strings account-mobile-panel))]
        (is (contains? chart-classes "flex"))
        (is (not (contains? chart-classes "hidden")))
        (is (nil? chart-summary-panel))
        (is (some #(str/starts-with? % "Balances") chart-account-text))
        (is (some #(str/starts-with? % "Open Orders") chart-account-text))
        (is (contains? chart-account-text "Trade History"))
        (is (contains? trades-account-classes "flex"))
        (is (not (contains? trades-account-classes "hidden")))
        (is (contains? trades-orderbook-classes "block"))
        (is (not (contains? trades-orderbook-classes "hidden")))
        (is (contains? trades-order-entry-classes "hidden"))
        (is (contains? ticket-classes "hidden"))
        (is (nil? ticket-summary-panel))
        (is (contains? ticket-desktop-equity-classes "hidden"))
        (is (contains? account-active-asset-strip-classes "hidden"))
        (is (contains? account-surface-tabs-classes "hidden"))
        (is (contains? account-panel-classes "hidden"))
        (is (contains? account-summary-classes "flex"))
        (is (not (contains? account-summary-classes "hidden")))
        (is (contains? account-summary-classes "absolute"))
        (is (contains? account-summary-classes "inset-0"))
        (is (contains? account-actions-classes "mt-auto"))
        (is (contains? account-actions-classes "pt-2"))
        (is (contains? account-actions-classes "pb-1.5"))
        (is (not (contains? account-actions-classes "py-3")))
        (is (contains? account-mobile-text "Account Equity"))
        (is (contains? account-mobile-text "Deposit"))
        (is (contains? account-mobile-text "Withdraw"))
        (is (not (some #(str/starts-with? % "Balances") account-mobile-text)))
        (is (not (some #(str/starts-with? % "Open Orders") account-mobile-text)))
        (is (not (contains? account-mobile-text "Trade History")))))))

(deftest trade-view-primary-mobile-tabs-route-to-market-surfaces-test
  (let [view-node (trade-view/trade-view (support/base-state))
        top-buttons (support/find-all-nodes view-node #(str/starts-with? (get-in % [1 :data-role] "")
                                                                         "trade-mobile-surface-button-"))
        chart-button (support/find-by-data-role view-node "trade-mobile-surface-button-chart")
        orderbook-button (support/find-by-data-role view-node "trade-mobile-surface-button-orderbook")
        trades-button (support/find-by-data-role view-node "trade-mobile-surface-button-trades")]
    (is (= 3 (count top-buttons)))
    (is (= "Chart" (support/node-text chart-button)))
    (is (= "Order Book" (support/node-text orderbook-button)))
    (is (= "Trades" (support/node-text trades-button)))
    (is (= [[:actions/select-trade-mobile-surface :chart]]
           (get-in chart-button [1 :on :click])))
    (is (= [[:actions/select-trade-mobile-surface :orderbook]]
           (get-in orderbook-button [1 :on :click])))
    (is (= [[:actions/select-trade-mobile-surface :trades]]
           (get-in trades-button [1 :on :click])))
    (is (nil? (support/find-by-data-role view-node "trade-mobile-surface-button-ticket")))
    (is (nil? (support/find-by-data-role view-node "trade-mobile-surface-button-account")))))

(deftest trade-view-reads-runtime-health-snapshot-for-surface-freshness-cues-test
  (let [state (-> (support/base-state)
                  (assoc :active-asset "BTC")
                  (assoc-in [:websocket :health]
                            {:generated-at-ms 5000
                             :streams {["l2Book" "BTC" nil nil nil]
                                       {:topic "l2Book"
                                        :status :live
                                        :subscribed? true
                                        :last-payload-at-ms 4900
                                        :stale-threshold-ms 5000}}})
                  (assoc-in [:websocket-ui :show-surface-freshness-cues?] true)
                  (assoc :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}]
                                            :asks [{:px "101" :sz "1"}]}}))
        view-node (trade-view/trade-view state)
        cue-node (support/find-by-data-role view-node "orderbook-freshness-cue")]
    (is (some? cue-node))
    (is (some #(str/includes? % "Updated")
              (support/collect-strings cue-node)))))

(deftest trade-view-skips-hidden-heavy-surface-subtrees-on-mobile-chart-layout-test
  (support/with-viewport-width
    430
    (fn []
      (let [active-asset-calls (atom 0)
            chart-calls (atom 0)
            orderbook-calls (atom 0)
            order-form-calls (atom 0)
            account-info-calls (atom 0)
            equity-metrics-calls (atom 0)
            account-equity-calls (atom 0)]
        (with-redefs [hyperopen.views.active-asset-view/active-asset-view (fn [_state]
                                                                            (swap! active-asset-calls inc)
                                                                            [:div {:data-role "stub-active-asset"}])
                      hyperopen.trade-modules/render-trade-chart-view (fn [_state]
                                                                        (swap! chart-calls inc)
                                                                        [:div {:data-role "stub-chart"}])
                      hyperopen.views.l2-orderbook-view/l2-orderbook-view (fn [_state]
                                                                            (swap! orderbook-calls inc)
                                                                            [:div {:data-role "stub-orderbook"}])
                      hyperopen.views.trade.order-form-view/order-form-view (fn [_state]
                                                                               (swap! order-form-calls inc)
                                                                               [:div {:data-role "stub-order-form"}])
                      hyperopen.views.account-info-view/account-info-view (fn
                                                                           ([_state]
                                                                            (swap! account-info-calls inc)
                                                                            [:div {:data-role "stub-account-info"}])
                                                                           ([_state _options]
                                                                            (swap! account-info-calls inc)
                                                                            [:div {:data-role "stub-account-info"}]))
                      hyperopen.views.account-equity-view/account-equity-metrics (fn [_state]
                                                                                   (swap! equity-metrics-calls inc)
                                                                                   {:account-value-display 12})
                      hyperopen.views.account-equity-view/account-equity-view (fn
                                                                                ([_state]
                                                                                 (swap! account-equity-calls inc)
                                                                                 [:div {:data-role "stub-account-equity"}])
                                                                                ([_state _opts]
                                                                                 (swap! account-equity-calls inc)
                                                                                 [:div {:data-role "stub-account-equity"}]))]
          (let [view-node (trade-view/trade-view (support/base-state))
                orderbook-nodes (support/find-all-nodes view-node #(= "stub-orderbook"
                                                                      (get-in % [1 :data-role])))
                order-form-nodes (support/find-all-nodes view-node #(= "stub-order-form"
                                                                       (get-in % [1 :data-role])))
                account-equity-nodes (support/find-all-nodes view-node #(= "stub-account-equity"
                                                                           (get-in % [1 :data-role])))]
            (is (= 1 @active-asset-calls))
            (is (= 1 @chart-calls))
            (is (= 0 @orderbook-calls))
            (is (= 0 @order-form-calls))
            (is (= 1 @account-info-calls))
            (is (= 0 @equity-metrics-calls))
            (is (= 0 @account-equity-calls))
            (is (= 0 (count orderbook-nodes)))
            (is (= 0 (count order-form-nodes)))
            (is (= 0 (count account-equity-nodes)))))))))
