(ns hyperopen.views.account-info-view)

;; Available tabs for the account info component
(def available-tabs [:balances :positions :open-orders :twap :trade-history :funding-history :order-history])

;; Main tab labels for display
(def tab-labels
  {:balances "Balances"
   :positions "Positions" 
   :open-orders "Open Orders"
   :twap "TWAP"
   :trade-history "Trade History"
   :funding-history "Funding History"
   :order-history "Order History"})

;; Tab navigation component
(defn tab-navigation [selected-tab]
  [:div.flex.items-center.border-b.border-base-300.bg-base-200
   (for [tab available-tabs]
     [:button.px-4.py-2.text-sm.font-medium.transition-colors.border-b-2
      {:key (name tab)
       :class (if (= selected-tab tab)
                ["text-primary" "border-primary" "bg-base-100"]
                ["text-base-content" "border-transparent" "hover:text-primary" "hover:bg-base-100"])
       :on {:click [[:actions/select-account-info-tab tab]]}}
      (get tab-labels tab (name tab))])])

;; Loading spinner component
(defn loading-spinner []
  [:div.flex.justify-center.items-center.py-8
   [:div.animate-spin.rounded-full.h-8.w-8.border-b-2.border-primary]])

;; Empty state component
(defn empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div.text-sm.opacity-70.mt-2 "No data available"]])

;; Error state component  
(defn error-state [error]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-error
   [:div.text-lg.font-medium "Error loading account data"]
   [:div.text-sm.opacity-70.mt-2 (str error)]])

;; Format currency values
(defn format-currency [value]
  (if (and value (not= value "N/A"))
    (let [num-val (js/parseFloat value)]
      (if (js/isNaN num-val)
        "0.00"
        (.toLocaleString num-val "en-US" #js {:minimumFractionDigits 2 :maximumFractionDigits 2})))
    "0.00"))

;; Format percentage with color
(defn format-pnl-percentage [value]
  (if (and value (not= value "N/A"))
    (let [num-val (js/parseFloat value)
          formatted (if (js/isNaN num-val) "0.00" (.toFixed num-val 2))
          color-class (cond
                        (pos? num-val) "text-success"
                        (neg? num-val) "text-error"
                        :else "text-base-content")]
      [:span {:class color-class} 
       (if (pos? num-val) "+" "") formatted "%"])
    [:span.text-base-content "0.00%"]))

;; Balance row component
(defn balance-row [coin total-balance available-balance usdc-value pnl-pct]
  [:div.grid.grid-cols-7.gap-4.py-3.px-4.hover:bg-base-200.border-b.border-base-300.items-center
   ;; Coin
   [:div.font-medium coin]
   ;; Total Balance  
   [:div.text-right (format-currency total-balance)]
   ;; Available Balance
   [:div.text-right (format-currency available-balance)]
   ;; USDC Value
   [:div.text-right "$" (format-currency usdc-value)]
   ;; PNL (ROE %)
   [:div.text-right (format-pnl-percentage pnl-pct)]
   ;; Send
   [:div.text-center
    [:button.btn.btn-xs.btn-ghost "Send"]]
   ;; Transfer/Contract
   [:div.text-center
    [:button.btn.btn-xs.btn-ghost "Transfer"]]])

;; Balance table header
(defn balance-table-header []
  [:div.grid.grid-cols-7.gap-4.py-2.px-4.bg-base-200.border-b.border-base-300.text-sm.font-medium.text-base-content
   [:div "Coin"]
   [:div.text-right "Total Balance"] 
   [:div.text-right "Available Balance"]
   [:div.text-right "USDC Value"]
   [:div.text-right "PNL (ROE %)"]
   [:div.text-center "Send"]
   [:div.text-center "Transfer"]])

;; Balances tab content
(defn balances-tab-content [webdata2]
  (let [clearinghouse-state (:clearinghouseState webdata2)]
    (if clearinghouse-state
      [:div
       ;; Filter toggle
       [:div.flex.justify-between.items-center.p-4.border-b.border-base-300
        [:div.text-lg.font-medium "Balances"]
        [:div.flex.items-center.space-x-2
         [:input.checkbox.checkbox-primary
          {:type "checkbox" :id "hide-small-balances"}]
         [:label.text-sm {:for "hide-small-balances"} "Hide Small Balances"]]]
       
       ;; Balance table
       [:div
        (balance-table-header)
        ;; Sample balance rows - will be replaced with real data
        (balance-row "USDC" "29,060.48" "27,243.19" "29,060.48" nil)
        (balance-row "HYPE" "2,980.83245490" "2,980.83245490" "149,909.61" "+83,888.91 (+50.0%)")]]
      (empty-state "No balance data available"))))

;; Positions tab content
(defn positions-tab-content [webdata2]
  (let [positions (get-in webdata2 [:clearinghouseState :assetPositions])]
    (if (and positions (seq positions))
      [:div.p-4
       [:div.text-lg.font-medium.mb-4 "Active Positions"]
       [:div.space-y-4
        (for [position positions]
          (let [pos-data (:position position)
                coin (:coin pos-data)]
            [:div.border.border-base-300.rounded.p-3
             {:key coin}
             [:div.font-medium coin]
             [:div.text-sm.space-y-1
              [:div "Size: " (:szi pos-data)]
              [:div "Entry Price: " (:entryPx pos-data)]
              [:div "Unrealized P&L: " 
               [:span {:class (if (pos? (js/parseFloat (:unrealizedPnl pos-data)))
                               "text-success" "text-error")}
                (:unrealizedPnl pos-data)]]
              [:div "Leverage: " (get-in pos-data [:leverage :value])]
              [:div "Margin Used: " (:marginUsed pos-data)]]]))]]
      (empty-state "No active positions"))))

;; Placeholder tab content for other tabs
(defn placeholder-tab-content [tab-name]
  [:div.p-4
   [:div.text-lg.font-medium.mb-4 (get tab-labels tab-name (name tab-name))]
   (empty-state (str (get tab-labels tab-name (name tab-name)) " coming soon"))])

;; Main tab content renderer
(defn tab-content [selected-tab webdata2]
  (case selected-tab
    :balances (balances-tab-content webdata2)
    :positions (positions-tab-content webdata2)
    :open-orders (placeholder-tab-content :open-orders)
    :twap (placeholder-tab-content :twap)
    :trade-history (placeholder-tab-content :trade-history)
    :funding-history (placeholder-tab-content :funding-history)
    :order-history (placeholder-tab-content :order-history)
    (empty-state "Unknown tab")))

;; Account info panel component
(defn account-info-panel [state]
  (let [selected-tab (get-in state [:account-info :selected-tab] :balances)
        webdata2 (:webdata2 state)
        loading? (get-in state [:account-info :loading] false)
        error (get-in state [:account-info :error])]
    [:div.bg-base-100.rounded-lg.shadow-lg.overflow-hidden.w-full.max-w-6xl
     ;; Tab navigation
     (tab-navigation selected-tab)
     
     ;; Content area
     [:div.min-h-96
      (cond
        error (error-state error)
        loading? (loading-spinner)
        :else (tab-content selected-tab webdata2))]]))

;; Main component that takes state and renders the UI
(defn account-info-view [state]
  (account-info-panel state))
