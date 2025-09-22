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

;; Position row component
(defn position-row [coin size position-value entry-price mark-price pnl-value pnl-percent liq-price margin funding]
  [:div.grid.grid-cols-11.gap-4.py-3.px-4.hover:bg-base-200.border-b.border-base-300.items-center.text-sm
   ;; Coin with leverage badge
   [:div.flex.items-center.space-x-2
    [:span.font-medium coin]
    [:span.badge.badge-sm.badge-outline "10x"]]
   ;; Size
   [:div.text-right size]
   ;; Position Value  
   [:div.text-right "$" (format-currency position-value)]
   ;; Entry Price
   [:div.text-right (format-currency entry-price)]
   ;; Mark Price
   [:div.text-right (format-currency mark-price)]
   ;; PNL (ROE %)
   [:div.text-right
    [:div 
     [:span {:class (if (and pnl-value (pos? (js/parseFloat pnl-value)))
                     "text-success" "text-error")}
      "$" (format-currency pnl-value)]
     [:div.text-xs.opacity-70 
      [:span {:class (if (and pnl-percent (pos? (js/parseFloat pnl-percent)))
                      "text-success" "text-error")}
       "(" (if (and pnl-percent (pos? (js/parseFloat pnl-percent))) "+" "") 
       (format-currency pnl-percent) "%)"]]]]
   ;; Liq. Price
   [:div.text-right (if liq-price (format-currency liq-price) "N/A")]
   ;; Margin
   [:div.text-right "$" (format-currency margin)]
   ;; Funding
   [:div.text-right
    [:span {:class (if (and funding (pos? (js/parseFloat funding)))
                    "text-success" "text-error")}
     "$" (format-currency funding)]]
   ;; Close All
   [:div.text-center
    [:button.btn.btn-xs.btn-ghost "Limit"]
    [:button.btn.btn-xs.btn-ghost.ml-1 "Market"]]
   ;; TP/SL
   [:div.text-center
    [:button.btn.btn-xs.btn-ghost "-- / --"]]])

;; Position table header
(defn position-table-header []
  [:div.grid.grid-cols-11.gap-4.py-2.px-4.bg-base-200.border-b.border-base-300.text-sm.font-medium.text-base-content
   [:div "Coin"]
   [:div.text-right "Size"] 
   [:div.text-right "Position Value"]
   [:div.text-right "Entry Price"]
   [:div.text-right "Mark Price"]
   [:div.text-right "PNL (ROE %)"]
   [:div.text-right "Liq. Price"]
   [:div.text-right "Margin"]
   [:div.text-right "Funding"]
   [:div.text-center "Close All"]
   [:div.text-center "TP/SL"]])

;; Positions tab content
(defn positions-tab-content [webdata2]
  (let [positions (get-in webdata2 [:clearinghouseState :assetPositions])]
    (if (and positions (seq positions))
      [:div
       ;; Header with count
       [:div.flex.justify-between.items-center.p-4.border-b.border-base-300
        [:div.text-lg.font-medium "Positions (" (count positions) ")"]
        [:div.text-sm.text-base-content.opacity-70 "Active positions"]]
       
       ;; Position table
       [:div
        (position-table-header)
        ;; Sample position rows - will be replaced with real data
        (position-row "HYPE" "10x 30.16 HYPE" "19,575.93" "3,043" "49,186" "-1543.76" "-7.31" "2.5485" "1,957.59" "-37.55")
        (position-row "ETH" "20x 3.2912 ETH" "13,827.32" "4,505.5" "4,201.3" "-1001.02" "-135.0" "N/A" "891.37" "-13.07")
        (position-row "PUMP" "5x 771,949 PUMP" "4,717.38" "0.006730" "0.006111" "-478.48" "-46.0" "N/A" "943.48" "-16.18")]]
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
