(ns hyperopen.workbench.scenes.primitives.order-form-primitives-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.views.account-info.mobile-cards :as mobile-cards]
            [hyperopen.views.account-info.table :as table]
            [hyperopen.views.trade.order-form-component-primitives :as primitives]))

(portfolio/configure-scenes
  {:title "Order Form Primitives"
   :collection :primitives})

(defonce primitive-state
  (atom {:toggle-on? true
         :input-value "0.2500"
         :compact-value "2.5"
         :side :buy}))

(defn- set-primitive!
  [k value]
  (swap! primitive-state assoc k value))

(portfolio/defscene controls
  :params primitive-state
  [state]
  (layout/page-shell
   (layout/desktop-shell
    (layout/panel-shell
     [:div {:class ["grid" "gap-4" "md:grid-cols-2"]}
      [:div {:class ["space-y-3"]}
       (primitives/row-toggle "Reduce Only"
                              (:toggle-on? @state)
                              #(swap! state update :toggle-on? not))
       (primitives/input (:input-value @state)
                         #(set-primitive! :input-value (.. % -target -value))
                         :placeholder "0.00")
       (primitives/row-input (:input-value @state)
                             "Price (USDC)"
                             #(set-primitive! :input-value (.. % -target -value))
                             (primitives/quote-accessory "USDC")
                             :input-padding-right "pr-16")
       (primitives/compact-row-input (:compact-value @state)
                                     "TP Price"
                                     #(set-primitive! :compact-value (.. % -target -value))
                                     [:span {:class ["text-xs" "text-gray-300"]} "%"]
                                     :short-label "TP")
       (primitives/chip-button "Classic" true)
       (primitives/chip-button "Scale" false)
       [:div {:class ["flex" "gap-2"]}
        (primitives/side-button "Buy / Long"
                                :buy
                                (= :buy (:side @state))
                                #(set-primitive! :side :buy))
        (primitives/side-button "Sell / Short"
                                :sell
                                (= :sell (:side @state))
                                #(set-primitive! :side :sell))]]
      [:div {:class ["space-y-2"]}
       (primitives/metric-row "Order Value" "2,558.50 USDC")
       (primitives/metric-row "Margin Required" "511.70 USDC")
       (primitives/metric-row "Slippage" "0.08%" "text-primary")
       (primitives/metric-row "Liquidation Price" "82,754.11" nil "Position risk is low right now.")]]))))

(portfolio/defscene account-table-primitives
  []
  (layout/page-shell
   (layout/desktop-shell
    (layout/panel-shell
     [:div {:class ["space-y-3"]}
      [:div {:class ["grid" "grid-cols-3" "gap-4" "border-b" "border-base-300" "pb-2"]}
       (table/sortable-header-button "Coin" {:column "Coin" :direction :asc} :actions/noop)
       (table/sortable-header-button "USDC Value" {:column "USDC Value" :direction :desc} :actions/noop
                                     {:explanation "Sorted descending to surface largest balances first."})
       (table/non-sortable-header "Transfer" :right {:explanation "Unified transfer availability depends on route context."})]
      (table/tab-table-content
       [:div {:class ["grid" "grid-cols-3" "gap-3" "bg-base-200" "px-3" "py-2" "text-xs" "font-medium"]}
        [:div "Coin"]
        [:div "USDC Value"]
        [:div "Transfer"]]
       [[:div {:class ["grid" "grid-cols-3" "gap-3" "px-3" "py-2" "text-sm"]}
         [:div "USDC"]
         [:div {:class ["num"]} "12,450.32"]
         [:div "Enabled"]]
        [:div {:class ["grid" "grid-cols-3" "gap-3" "px-3" "py-2" "text-sm"]}
         [:div "BTC"]
         [:div {:class ["num"]} "15,351.81"]
         [:div "Disabled"]]])]))))

(portfolio/defscene mobile-card-expanded
  []
  (layout/page-shell
   (layout/mobile-shell
    (mobile-cards/expandable-card
     {:data-role "workbench-mobile-balance-card"
      :expanded? true
      :toggle-actions #(js/console.info "toggle mobile card")
      :summary-items [(mobile-cards/summary-item "Coin" "BTC")
                      (mobile-cards/summary-item "Balance" "0.1500 BTC" {:value-classes ["num"]})
                      (mobile-cards/summary-item "USDC Value" "15,351.81" {:value-classes ["num"]})]
      :detail-content [:div {:class ["space-y-3"]}
                       (mobile-cards/detail-grid
                        "grid-cols-2"
                        [(mobile-cards/detail-item "Available" "0.1200 BTC" {:value-classes ["num"]})
                         (mobile-cards/detail-item "PNL (ROE %)" "+63.78 (4.1%)")
                         (mobile-cards/detail-item "Contract"
                                                   [:a {:href "#"
                                                        :class ["text-primary" "underline"]}
                                                    "0x1234...abcd"]
                                                   {:full-width? true})])]}))))
