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

(defn parse-num [value]
  (let [num-val (js/parseFloat (or value 0))]
    (if (js/isNaN num-val) 0 num-val)))

(defn format-amount [value decimals]
  (let [num-val (parse-num value)
        safe-decimals (-> (or decimals 2)
                          (max 0)
                          (min 8))]
    (.toLocaleString num-val "en-US" #js {:minimumFractionDigits safe-decimals
                                          :maximumFractionDigits safe-decimals})))

(defn format-balance-amount [value decimals]
  (if decimals
    (format-amount value decimals)
    (format-currency value)))

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

(defn format-timestamp [ms]
  (when ms
    (let [d (js/Date. ms)]
      (.toLocaleString d))))

(defn format-side [side]
  (case side
    "B" "Buy"
    "S" "Sell"
    (or side "-")))

(defn format-pnl [pnl-value pnl-pct]
  (if (and (some? pnl-value) (some? pnl-pct))
    (let [pnl-num (parse-num pnl-value)
          pct-num (parse-num pnl-pct)
          color-class (cond
                        (pos? pnl-num) "text-success"
                        (neg? pnl-num) "text-error"
                        :else "text-base-content")]
      [:span {:class color-class}
       (str (if (pos? pnl-num) "+" "")
            "$" (format-currency pnl-num)
            " (" (if (pos? pct-num) "+" "") (.toFixed pct-num 2) "%)")])
    [:span.text-base-content "--"]))

;; Balance row component
(defn balance-row [{:keys [coin total-balance available-balance usdc-value pnl-value pnl-pct amount-decimals]}]
  [:div.grid.grid-cols-7.gap-4.py-3.px-4.hover:bg-base-200.border-b.border-base-300.items-center
   ;; Coin
   [:div.font-medium coin]
   ;; Total Balance  
   [:div.text-right (format-balance-amount total-balance amount-decimals)]
   ;; Available Balance
   [:div.text-right (format-balance-amount available-balance amount-decimals)]
   ;; USDC Value
   [:div.text-right "$" (format-currency usdc-value)]
   ;; PNL (ROE %)
   [:div.text-right (format-pnl pnl-value pnl-pct)]
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
(defn balances-tab-content [webdata2 spot-data hide-small?]
  (let [clearinghouse-state (:clearinghouseState webdata2)
        spot-meta (:meta spot-data)
        spot-state (:clearinghouse-state spot-data)
        spot-asset-ctxs (:spotAssetCtxs webdata2)
        token-decimals (into {}
                             (map (fn [{:keys [index weiDecimals szDecimals]}]
                                    [index (or weiDecimals szDecimals 2)]))
                             (:tokens spot-meta))
        usdc-token (some #(when (= "USDC" (:name %)) %) (:tokens spot-meta))
        usdc-token-idx (:index usdc-token)
        price-by-token (if (and usdc-token-idx (seq (:universe spot-meta)) (seq spot-asset-ctxs))
                         (let [ctxs (vec spot-asset-ctxs)]
                           (reduce (fn [m {:keys [tokens index]}]
                                     (let [[base quote] tokens
                                           ctx (nth ctxs index nil)
                                           mark-px (parse-num (:markPx ctx))]
                                       (cond
                                         (and (= quote usdc-token-idx) (pos? mark-px))
                                         (assoc m base mark-px)

                                         (and (= base usdc-token-idx) (pos? mark-px))
                                         (assoc m quote (/ 1 mark-px))

                                         :else m)))
                                   {usdc-token-idx 1}
                                   (:universe spot-meta)))
                         {})
        perps-row (when clearinghouse-state
                    {:key "perps-usdc"
                     :coin "USDC (Perps)"
                     :total-balance (get-in clearinghouse-state [:marginSummary :accountValue])
                     :available-balance (:withdrawable clearinghouse-state)
                     :usdc-value (get-in clearinghouse-state [:marginSummary :accountValue])
                     :pnl-value nil
                     :pnl-pct nil
                     :amount-decimals nil})
        spot-rows (when (seq (get spot-state :balances))
                    (map (fn [{:keys [coin token hold total entryNtl]}]
                           (let [token-idx (if (string? token) (js/parseInt token) token)
                                 decimals (get token-decimals token-idx)
                                 total-num (parse-num total)
                                 hold-num (parse-num hold)
                                 available-num (- total-num hold-num)
                                 price (get price-by-token token-idx)
                                 usdc-value (if price (* total-num price) 0)
                                 entry-num (parse-num entryNtl)
                                 pnl-value (when (and price (pos? entry-num))
                                             (- usdc-value entry-num))
                                 pnl-pct (when (and pnl-value (pos? entry-num))
                                           (* 100 (/ pnl-value entry-num)))
                                 coin-label (if (= coin "USDC") "USDC (Spot)" coin)]
                             {:key (str "spot-" token-idx)
                              :coin coin-label
                              :total-balance total-num
                              :available-balance available-num
                              :usdc-value usdc-value
                              :pnl-value pnl-value
                              :pnl-pct pnl-pct
                              :amount-decimals decimals}))
                         (get spot-state :balances)))
        rows (->> (concat (when perps-row [perps-row]) spot-rows)
                  (remove nil?)
                  (filter (fn [row]
                            (if hide-small?
                              (>= (parse-num (:usdc-value row)) 1)
                              true))))]
    (if (seq rows)
      [:div
       ;; Filter toggle
       [:div.flex.justify-between.items-center.p-4.border-b.border-base-300
        [:div.text-lg.font-medium "Balances"]
        [:div.flex.items-center.space-x-2
         [:input.checkbox.checkbox-primary
          {:type "checkbox"
           :id "hide-small-balances"
           :checked (boolean hide-small?)
           :on {:change [[:actions/set-hide-small-balances :event.target/checked]]}}]
         [:label.text-sm {:for "hide-small-balances"} "Hide Small Balances"]]]
       
       ;; Balance table
       [:div
        (balance-table-header)
        (for [row rows]
          ^{:key (:key row)}
          (balance-row row))]]
      (empty-state "No balance data available"))))

;; Calculate mark price from position data (placeholder - would need market data)
(defn calculate-mark-price [position-data]
  ;; For now, use entry price as approximation - in real app would get from market data
  (:entryPx position-data))

;; Format position size display
(defn format-position-size [position-data]
  (let [leverage (get-in position-data [:leverage :value])
        size (:szi position-data)
        coin (:coin position-data)]
    (str leverage "x " size " " coin)))

;; Combine positions across the default DEX (webdata2) and any additional perp DEXes.
(defn position-unique-key [position-data]
  (str (get-in position-data [:position :coin]) "|" (or (:dex position-data) "default")))

(defn collect-positions [webdata2 perp-dex-states]
  (let [base-positions (->> (get-in webdata2 [:clearinghouseState :assetPositions])
                            (map #(assoc % :dex nil)))
        extra-positions (->> perp-dex-states
                             (mapcat (fn [[dex state]]
                                       (->> (:assetPositions state)
                                            (map #(assoc % :dex dex))))))
        combined (->> (concat base-positions extra-positions)
                      (remove nil?))]
    (second
      (reduce (fn [[seen acc] pos]
                (let [k (position-unique-key pos)]
                  (if (contains? seen k)
                    [seen acc]
                    [(conj seen k) (conj acc pos)])))
              [#{} []]
              combined))))

;; Position row component using real data
(defn position-row [position-data]
  (let [pos (:position position-data)
        coin (:coin pos)
        leverage (get-in pos [:leverage :value])
        size (:szi pos)
        position-value (:positionValue pos)
        entry-price (:entryPx pos)
        mark-price (calculate-mark-price pos)
        pnl-value (:unrealizedPnl pos)
        pnl-percent (* 100 (js/parseFloat (:returnOnEquity pos)))
        liq-price (:liquidationPx pos)
        margin (:marginUsed pos)
        funding (get-in pos [:cumFunding :allTime])]
    [:div.grid.grid-cols-11.gap-4.py-3.px-4.hover:bg-base-200.border-b.border-base-300.items-center.text-sm
     ;; Coin with leverage badge
     [:div.flex.items-center.space-x-2
      [:span.font-medium coin]
      [:span.badge.badge-sm.badge-outline (str leverage "x")]]
     ;; Size
     [:div.text-right (format-position-size pos)]
     ;; Position Value  
     [:div.text-right "$" (format-currency position-value)]
     ;; Entry Price
     [:div.text-right (format-currency entry-price)]
     ;; Mark Price
     [:div.text-right (format-currency mark-price)]
     ;; PNL (ROE %)
     [:div.text-right
      [:div 
       [:span {:class (if (pos? (js/parseFloat pnl-value))
                       "text-success" "text-error")}
        "$" (format-currency pnl-value)]
       [:div.text-xs.opacity-70 
        [:span {:class (if (pos? pnl-percent)
                        "text-success" "text-error")}
         "(" (if (pos? pnl-percent) "+" "") 
         (.toFixed pnl-percent 2) "%)"]]]]
     ;; Liq. Price
     [:div.text-right (if liq-price (format-currency liq-price) "N/A")]
     ;; Margin
     [:div.text-right "$" (format-currency margin)]
     ;; Funding
     [:div.text-right
      (let [funding-num (js/parseFloat funding)
            display-funding (if (pos? funding-num) (- funding-num) funding-num)
            display-text (str "$" (format-currency (str display-funding)))]
        [:span {:class (if (neg? display-funding)
                        "text-error" 
                        "text-success")}
         display-text])]
     ;; Close All
     [:div.text-center
      [:button.btn.btn-xs.btn-ghost "Limit"]
      [:button.btn.btn-xs.btn-ghost.ml-1 "Market"]]
     ;; TP/SL
     [:div.text-center
      [:button.btn.btn-xs.btn-ghost "-- / --"]]]))

;; Sort positions by column
(defn sort-positions-by-column [positions column direction]
  (let [sort-fn (case column
                  "Coin" (fn [pos] (:coin (:position pos)))
                  "Size" (fn [pos] (js/parseFloat (:szi (:position pos))))
                  "Position Value" (fn [pos] (js/parseFloat (:positionValue (:position pos))))
                  "Entry Price" (fn [pos] (js/parseFloat (:entryPx (:position pos))))
                  "Mark Price" (fn [pos] (js/parseFloat (:entryPx (:position pos)))) ; using entry as proxy
                  "PNL (ROE %)" (fn [pos] (js/parseFloat (:unrealizedPnl (:position pos))))
                  "Liq. Price" (fn [pos] (let [liq (:liquidationPx (:position pos))]
                                          (if liq (js/parseFloat liq) js/Number.MAX_VALUE)))
                  "Margin" (fn [pos] (js/parseFloat (:marginUsed (:position pos))))
                  "Funding" (fn [pos] (js/parseFloat (get-in (:position pos) [:cumFunding :allTime])))
                  (fn [pos] 0)) ; default sort
        sorted-positions (sort-by sort-fn positions)]
    (if (= direction :desc)
      (reverse sorted-positions)
      sorted-positions)))

;; Sortable column header component
(defn sortable-header [column-name sort-state]
  (let [current-column (:column sort-state)
        current-direction (:direction sort-state)
        is-active (= current-column column-name)
        sort-icon (when is-active
                    (if (= current-direction :asc) "↑" "↓"))]
    [:button.text-sm.font-medium.text-base-content.hover:text-primary.transition-colors.flex.items-center.space-x-1.group
     {:on {:click [[:actions/sort-positions column-name]]}}
     [:span column-name]
     (when sort-icon
       [:span.text-xs.opacity-70 sort-icon])]))

;; Non-sortable column header
(defn non-sortable-header [column-name]
  [:div.text-sm.font-medium.text-base-content column-name])

;; Position table header with sorting
(defn position-table-header [sort-state]
  [:div.grid.grid-cols-11.gap-4.py-2.px-4.bg-base-200.border-b.border-base-300
   [:div.text-left (sortable-header "Coin" sort-state)]
   [:div.text-right (sortable-header "Size" sort-state)]
   [:div.text-right (sortable-header "Position Value" sort-state)]
   [:div.text-right (sortable-header "Entry Price" sort-state)]
   [:div.text-right (sortable-header "Mark Price" sort-state)]
   [:div.text-right (sortable-header "PNL (ROE %)" sort-state)]
   [:div.text-right (sortable-header "Liq. Price" sort-state)]
   [:div.text-right (sortable-header "Margin" sort-state)]
   [:div.text-right (sortable-header "Funding" sort-state)]
   [:div.text-center (non-sortable-header "Close All")]
   [:div.text-center (non-sortable-header "TP/SL")]])

;; Positions tab content
(defn positions-tab-content [webdata2 sort-state perp-dex-states]
  (let [positions (collect-positions webdata2 perp-dex-states)
        sorted-positions (if positions
                          (sort-positions-by-column positions 
                                                   (:column sort-state) 
                                                   (:direction sort-state))
                          [])]
    (if (and positions (seq positions))
      [:div
       ;; Header with count
       [:div.flex.justify-between.items-center.p-4.border-b.border-base-300
        [:div.text-lg.font-medium "Positions (" (count positions) ")"]
        [:div.text-sm.text-base-content.opacity-70 "Active positions"]]
       
       ;; Position table
       [:div
        (position-table-header sort-state)
        ;; Real position rows from data
        (for [position sorted-positions]
          ^{:key (position-unique-key position)}
          (position-row position))]]
      (empty-state "No active positions"))))

;; Placeholder tab content for other tabs
(defn placeholder-tab-content [tab-name]
  [:div.p-4
   [:div.text-lg.font-medium.mb-4 (get tab-labels tab-name (name tab-name))]
   (empty-state (str (get tab-labels tab-name (name tab-name)) " coming soon"))])

(defn open-orders-tab-content [orders]
  (if (seq orders)
    [:div
     [:div.grid.grid-cols-7.gap-4.py-2.px-4.bg-base-200.border-b.border-base-300.text-sm.font-medium
      [:div "Coin"]
      [:div.text-right "Side"]
      [:div.text-right "Size"]
      [:div.text-right "Price"]
      [:div.text-right "Type"]
      [:div.text-right "Time"]
      [:div.text-right ""]]
     (for [o orders]
       ^{:key (str (:oid o) "-" (:coin o))}
       [:div.grid.grid-cols-7.gap-4.py-3.px-4.border-b.border-base-300.text-sm
        [:div (:coin o)]
        [:div.text-right (format-side (:side o))]
        [:div.text-right (format-currency (:sz o))]
        [:div.text-right (format-currency (:px o))]
        [:div.text-right (or (:type o) "order")]
        [:div.text-right (format-timestamp (:time o))]
        [:div.text-right
         [:button.btn.btn-xs.btn-ghost
          {:on {:click [[:actions/cancel-order o]]}}
          "Cancel"]]])]
    (empty-state "No open orders")))

(defn trade-history-tab-content [fills]
  (if (seq fills)
    [:div
     [:div.grid.grid-cols-6.gap-4.py-2.px-4.bg-base-200.border-b.border-base-300.text-sm.font-medium
      [:div "Coin"]
      [:div.text-right "Side"]
      [:div.text-right "Size"]
      [:div.text-right "Price"]
      [:div.text-right "Fee"]
      [:div.text-right "Time"]]
     (for [f fills]
       ^{:key (str (:tid f) "-" (:coin f) "-" (:time f))}
       [:div.grid.grid-cols-6.gap-4.py-3.px-4.border-b.border-base-300.text-sm
        [:div (:coin f)]
        [:div.text-right (format-side (:side f))]
        [:div.text-right (format-currency (:sz f))]
        [:div.text-right (format-currency (:px f))]
        [:div.text-right (format-currency (:fee f))]
        [:div.text-right (format-timestamp (:time f))]])]
    (empty-state "No fills")))

(defn funding-history-tab-content [fundings]
  (if (seq fundings)
    [:div
     [:div.grid.grid-cols-5.gap-4.py-2.px-4.bg-base-200.border-b.border-base-300.text-sm.font-medium
      [:div "Coin"]
      [:div.text-right "Rate"]
      [:div.text-right "Payment"]
      [:div.text-right "Position"]
      [:div.text-right "Time"]]
     (for [f fundings]
       ^{:key (str (:coin f) "-" (:time f))}
       [:div.grid.grid-cols-5.gap-4.py-3.px-4.border-b.border-base-300.text-sm
        [:div (:coin f)]
        [:div.text-right (format-pnl-percentage (* 100 (js/parseFloat (or (:fundingRate f) 0))))]
        [:div.text-right (format-currency (:payment f))]
        [:div.text-right (format-currency (:positionSize f))]
        [:div.text-right (format-timestamp (:time f))]])]
    (empty-state "No funding history")))

(defn order-history-tab-content [ledger]
  (if (seq ledger)
    [:div
     [:div.grid.grid-cols-4.gap-4.py-2.px-4.bg-base-200.border-b.border-base-300.text-sm.font-medium
      [:div "Type"]
      [:div.text-right "Asset"]
      [:div.text-right "Delta"]
      [:div.text-right "Time"]]
     (for [l ledger]
       ^{:key (str (:time l) "-" (:coin l) "-" (:delta l))}
       [:div.grid.grid-cols-4.gap-4.py-3.px-4.border-b.border-base-300.text-sm
        [:div (or (:type l) "event")]
        [:div.text-right (or (:coin l) "-")]
        [:div.text-right (format-currency (:delta l))]
        [:div.text-right (format-timestamp (:time l))]])]
    (empty-state "No order history")))

;; Main tab content renderer
(defn tab-content [selected-tab webdata2 sort-state spot-data hide-small? perp-dex-states]
  (case selected-tab
    :balances (balances-tab-content webdata2 spot-data hide-small?)
    :positions (positions-tab-content webdata2 sort-state perp-dex-states)
    :open-orders (open-orders-tab-content (get-in webdata2 [:open-orders]))
    :twap (placeholder-tab-content :twap)
    :trade-history (trade-history-tab-content (get-in webdata2 [:fills]))
    :funding-history (funding-history-tab-content (get-in webdata2 [:fundings]))
    :order-history (order-history-tab-content (get-in webdata2 [:ledger]))
    (empty-state "Unknown tab")))

;; Account info panel component
(defn account-info-panel [state]
  (let [selected-tab (get-in state [:account-info :selected-tab] :balances)
        webdata2 (merge (:webdata2 state) (get state :orders))
        loading? (get-in state [:account-info :loading] false)
        error (get-in state [:account-info :error])
        sort-state (get-in state [:account-info :positions-sort] {:column nil :direction :asc})
        spot-data (:spot state)
        hide-small? (get-in state [:account-info :hide-small-balances?] false)
        perp-dex-states (:perp-dex-clearinghouse state)]
    [:div.bg-base-100.rounded-lg.shadow-lg.overflow-hidden.w-full.max-w-6xl
     ;; Tab navigation
     (tab-navigation selected-tab)
     
     ;; Content area
     [:div.min-h-96
      (cond
        error (error-state error)
        loading? (loading-spinner)
        :else (tab-content selected-tab webdata2 sort-state spot-data hide-small? perp-dex-states))]]))

;; Main component that takes state and renders the UI
(defn account-info-view [state]
  (account-info-panel state))
