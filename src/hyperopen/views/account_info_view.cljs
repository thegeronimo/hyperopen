(ns hyperopen.views.account-info-view
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]))

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
(defn tab-label [tab counts]
  (let [base (get tab-labels tab (name tab))
        count (get counts tab)]
    (cond
      (and (= tab :positions) (number? count) (pos? count))
      (str base " (" count ")")

      (and (= tab :positions) (number? count))
      base

      (number? count)
      (str base " (" count ")")

      :else base)))

(defn- funding-history-header-actions []
  [:div {:class ["ml-auto" "flex" "items-center" "justify-end" "gap-2" "px-4" "py-2"]}
   [:button {:class ["btn" "btn-xs" "btn-ghost" "font-normal" "text-trading-green" "hover:bg-trading-green/10" "hover:text-trading-green"]
             :on {:click [[:actions/toggle-funding-history-filter-open]]}}
    "Filter"]
   [:button {:class ["btn" "btn-xs" "btn-ghost" "font-normal" "text-trading-green" "hover:bg-trading-green/10" "hover:text-trading-green"]
             :on {:click [[:actions/view-all-funding-history]]}}
    "View All"]
   [:button {:class ["btn" "btn-xs" "btn-ghost" "font-normal" "text-trading-green" "hover:bg-trading-green/10" "hover:text-trading-green"]
             :on {:click [[:actions/export-funding-history-csv]]}}
    "Export as CSV"]])

(defn tab-navigation [selected-tab counts hide-small? _funding-history-state]
  [:div.flex.items-center.justify-between.border-b.border-base-300.bg-base-200
   [:div.flex.items-center
    (for [tab available-tabs]
      [:button.px-4.py-2.text-sm.font-medium.transition-colors.border-b-2
       {:key (name tab)
        :class (if (= selected-tab tab)
                 ["text-primary" "border-primary" "bg-base-100"]
                 ["text-base-content" "border-transparent" "hover:text-primary" "hover:bg-base-100"])
        :on {:click [[:actions/select-account-info-tab tab]]}}
       (tab-label tab counts)])]
   (case selected-tab
     :balances
     [:div.flex.items-center.space-x-2.px-4.py-2
      [:input
       {:type "checkbox"
        :id "hide-small-balances"
        :class ["h-4"
                "w-4"
                "rounded-[3px]"
                "border"
                "border-base-300"
                "bg-transparent"
                "trade-toggle-checkbox"
                "transition-colors"
                "focus:outline-none"
                "focus:ring-0"
                "focus:ring-offset-0"
                "focus:shadow-none"]
        :checked (boolean hide-small?)
        :on {:change [[:actions/set-hide-small-balances :event.target/checked]]}}]
      [:label.text-sm.text-trading-text.cursor-pointer.select-none
       {:for "hide-small-balances"}
       "Hide Small Balances"]]

     :funding-history
     (funding-history-header-actions)

     nil)])

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

(defn format-trade-price [value]
  (if (or (nil? value) (= value "N/A"))
    "0.00"
    (let [num-val (js/parseFloat value)]
      (if (js/isNaN num-val)
        "0.00"
        (or (fmt/format-trade-price num-val value) "0.00")))))

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

(defn format-funding-history-time [time-ms]
  (when time-ms
    (let [d (js/Date. time-ms)
          pad2 (fn [v] (.padStart (str v) 2 "0"))]
      (str (inc (.getMonth d))
           "/"
           (.getDate d)
           "/"
           (.getFullYear d)
           " - "
           (pad2 (.getHours d))
           ":"
           (pad2 (.getMinutes d))
           ":"
           (pad2 (.getSeconds d))))))

(defn- datetime-local-value [time-ms]
  (when time-ms
    (let [d (js/Date. time-ms)
          pad2 (fn [v] (.padStart (str v) 2 "0"))]
      (str (.getFullYear d)
           "-"
           (pad2 (inc (.getMonth d)))
           "-"
           (pad2 (.getDate d))
           "T"
           (pad2 (.getHours d))
           ":"
           (pad2 (.getMinutes d))))))

(def ^:private position-chip-classes
  ["px-1.5"
   "py-0.5"
   "text-xs"
   "leading-none"
   "font-medium"
   "rounded"
   "border"
   "bg-emerald-500/20"
   "text-emerald-300"
   "border-emerald-500/30"])

(def ^:private position-coin-cell-style
  {:background "linear-gradient(90deg, rgb(31, 166, 125) 0px, rgb(31, 166, 125) 4px, rgb(11, 50, 38) 4px, transparent 100%) transparent"
   :padding-left "12px"
   :margin-left "-12px"})

(defn- non-blank-text [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn- parse-coin-namespace [coin]
  (let [coin* (non-blank-text coin)]
    (when coin*
      (if (str/includes? coin* ":")
        (let [[prefix suffix] (str/split coin* #":" 2)]
          {:prefix (non-blank-text prefix)
           :base (non-blank-text suffix)})
        {:prefix nil
         :base coin*}))))

(defn- funding-filter-coin-label [coin]
  (let [coin* (non-blank-text coin)
        parsed (parse-coin-namespace coin*)
        base-label (or (:base parsed) coin* "-")
        prefix-label (:prefix parsed)]
    [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
     [:span {:class ["truncate"]} base-label]
     (when prefix-label
       [:span {:class position-chip-classes} prefix-label])]))

(defn- funding-side-value [row]
  (or (:position-side row)
      (let [signed-size (parse-num (or (:position-size-raw row)
                                       (:positionSize row)))]
        (cond
          (pos? signed-size) :long
          (neg? signed-size) :short
          :else :flat))))

(defn- funding-side-label [position-side]
  (case position-side
    :long "Long"
    :short "Short"
    :flat "Flat"
    "Flat"))

(defn- funding-side-class [position-side]
  (case position-side
    :long "text-success"
    :short "text-error"
    :flat "text-base-content"
    "text-base-content"))

(defn- funding-size-text [row]
  (let [size (js/Math.abs (parse-num (or (:position-size-raw row)
                                         (:positionSize row)
                                         (:size-raw row))))
        coin (or (:coin row) "-")]
    (str (.toLocaleString (js/Number. size)
                          "en-US"
                          #js {:minimumFractionDigits 3
                               :maximumFractionDigits 6})
         " "
         coin)))

(defn- funding-payment-node [row]
  (let [payment (parse-num (or (:payment-usdc-raw row)
                               (:payment row)))
        color-class (cond
                      (neg? payment) "text-error"
                      (pos? payment) "text-success"
                      :else "text-base-content")]
    [:span {:class color-class}
     (str (.toLocaleString (js/Number. payment)
                           "en-US"
                           #js {:minimumFractionDigits 4
                                :maximumFractionDigits 6})
          " USDC")]))

(defn- funding-rate-node [row]
  (let [rate (parse-num (or (:funding-rate-raw row)
                            (:fundingRate row)))
        color-class (cond
                      (neg? rate) "text-error"
                      (pos? rate) "text-success"
                      :else "text-base-content")]
    [:span {:class color-class}
     (str (.toFixed (* 100 rate) 4) "%")]))

(defn- funding-coin-options [fundings-raw]
  (->> fundings-raw
       (map :coin)
       (filter string?)
       distinct
       sort
       vec))

(defn- funding-history-controls [funding-history-state fundings-raw]
  (let [filters (or (:filters funding-history-state) {})
        draft-filters (or (:draft-filters funding-history-state) filters)
        coin-set (or (:coin-set draft-filters) #{})
        filter-open? (boolean (:filter-open? funding-history-state))
        loading? (boolean (:loading? funding-history-state))
        error (:error funding-history-state)
        status-open? (or loading? (some? error))
        start-time-ms (:start-time-ms draft-filters)
        end-time-ms (:end-time-ms draft-filters)
        coin-options (funding-coin-options fundings-raw)]
    (when (or status-open? filter-open?)
      [:div {:class ["border-b" "border-base-300" "bg-base-200"]}
       (when status-open?
         [:div {:class ["flex" "items-center" "gap-2" "px-4" "py-2" "text-sm"]}
          (when loading?
            [:span {:class ["text-xs" "text-trading-text-secondary"]} "Loading..."])
          (when error
            [:span {:class ["text-xs" "text-error"]} (str error)])])
       (when filter-open?
         [:div {:class (into ["grid" "grid-cols-1" "gap-3" "p-4" "text-sm" "md:grid-cols-2"]
                             (when status-open?
                               ["border-t" "border-base-300"]))}
        [:div {:class ["space-y-2"]}
         [:label {:class ["text-xs" "font-medium" "text-trading-text-secondary"]}
          "Start Time"]
         [:input.input.input-sm.input-bordered.w-full
          {:type "datetime-local"
           :value (or (datetime-local-value start-time-ms) "")
           :on {:change [[:actions/set-funding-history-filters [:draft-filters :start-time-ms] :event.target/value]]}}]]
        [:div {:class ["space-y-2"]}
         [:label {:class ["text-xs" "font-medium" "text-trading-text-secondary"]}
          "End Time"]
         [:input.input.input-sm.input-bordered.w-full
          {:type "datetime-local"
           :value (or (datetime-local-value end-time-ms) "")
           :on {:change [[:actions/set-funding-history-filters [:draft-filters :end-time-ms] :event.target/value]]}}]]
        [:div {:class ["space-y-2" "md:col-span-2"]}
         [:label {:class ["text-xs" "font-medium" "text-trading-text-secondary"]}
          "Coins"]
         (if (seq coin-options)
           [:div {:class ["flex" "max-h-28" "flex-wrap" "gap-2" "overflow-y-auto" "rounded-md" "border" "border-base-300" "bg-base-100" "p-2"]}
             (for [coin coin-options]
              ^{:key coin}
              [:label {:class ["flex" "items-center" "gap-1" "rounded-md" "px-1" "py-px" "hover:bg-base-200"]}
               [:input {:class ["h-4"
                                "w-4"
                                "rounded-[3px]"
                                "border"
                                "border-base-300"
                                "bg-transparent"
                                "trade-toggle-checkbox"
                                "transition-colors"
                                "focus:outline-none"
                                "focus:ring-0"
                                "focus:ring-offset-0"
                                "focus:shadow-none"]
                 :type "checkbox"
                 :checked (contains? coin-set coin)
                 :on {:change [[:actions/toggle-funding-history-filter-coin coin]]}}]
               (funding-filter-coin-label coin)])]
           [:div {:class ["text-xs" "text-trading-text-secondary"]} "No coin data available for current range."])]
        [:div {:class ["flex" "items-center" "justify-end" "gap-2" "md:col-span-2"]}
         [:button {:class ["btn" "btn-xs" "btn-ghost" "h-8" "px-3" "text-xs" "font-medium" "min-w-[4.5rem]"]
                   :on {:click [[:actions/reset-funding-history-filter-draft]]}}
          "Cancel"]
         [:button {:class ["btn" "btn-xs" "btn-primary" "h-8" "px-3" "text-xs" "font-medium" "min-w-[4.5rem]"]
                   :on {:click [[:actions/apply-funding-history-filters]]}}
          "Apply"]]])])))

(defn format-open-orders-time [ms]
  (when ms
    (let [d (js/Date. ms)
          month (inc (.getMonth d))
          day (.getDate d)
          year (.getFullYear d)
          hours (.getHours d)
          minutes (.getMinutes d)
          seconds (.getSeconds d)
          pad2 (fn [v] (.padStart (str v) 2 "0"))]
      (str month "/" day "/" year " - "
           (pad2 hours) ":" (pad2 minutes) ":" (pad2 seconds)))))

(defn format-side [side]
  (case side
    "B" "Buy"
    "A" "Sell"
    "S" "Sell"
    (or side "-")))

(defn normalize-open-order [order]
  (let [root (or (:order order) order)
        coin (or (:coin root) (:coin order))
        oid (or (:oid root) (:oid order))
        side (or (:side root) (:side order))
        sz (or (:sz root) (:origSz root) (:sz order) (:origSz order))
        orig-sz (or (:origSz root) (:origSz order))
        limit-px (or (:limitPx root) (:limitPx order))
        fallback-px (or (:px root) (:px order))
        trigger-px (or (:triggerPx root) (:triggerPx order))
        is-trigger? (or (:isTrigger root) (:isTrigger order))
        trigger-condition (or (:triggerCondition root) (:triggerCondition order)
                              (:triggerCond root) (:triggerCond order))
        px (let [candidate (or limit-px fallback-px)]
             (if (and is-trigger? (zero? (parse-num candidate)))
               trigger-px
               candidate))
        time (or (:timestamp root) (:timestamp order) (:time root) (:time order))
        order-type (or (:orderType root) (:orderType order) (:type root) (:type order) (:tif root) (:tif order))
        reduce-only (or (:reduceOnly root) (:reduceOnly order))
        is-position-tpsl (or (:isPositionTpsl root) (:isPositionTpsl order))]
    (when (or coin oid)
      {:coin coin
       :oid oid
       :side side
       :sz sz
       :orig-sz orig-sz
       :px px
       :type order-type
       :time time
       :reduce-only reduce-only
       :is-trigger is-trigger?
       :trigger-condition trigger-condition
       :trigger-px trigger-px
       :is-position-tpsl is-position-tpsl})))

(defn open-orders-seq [orders]
  (cond
    (nil? orders) []
    (sequential? orders) orders
    (map? orders) (let [nested (or (:orders orders) (:openOrders orders) (:data orders))]
                    (cond
                      (sequential? nested) nested
                      (:order orders) [orders]
                      :else []))
    :else []))

(defn open-orders-by-dex [orders-by-dex]
  (->> (vals (or orders-by-dex {}))
       (mapcat open-orders-seq)))

(defn open-orders-source [orders snapshot snapshot-by-dex]
  (let [live (open-orders-seq orders)
        fallback (open-orders-seq snapshot)
        dex-orders (open-orders-by-dex snapshot-by-dex)]
    (concat (if (seq live) live fallback) dex-orders)))

(defn normalized-open-orders [orders snapshot snapshot-by-dex]
  (->> (open-orders-source orders snapshot snapshot-by-dex)
       (map normalize-open-order)
       (remove nil?)
       (filter (fn [o] (and (:coin o) (:oid o))))
       vec))

(defn trigger-condition-label [trigger-condition]
  (case trigger-condition
    "Above" "≥"
    "Below" "≤"
    "N/A" nil
    trigger-condition))

(defn format-trigger-conditions [{:keys [is-trigger trigger-condition trigger-px]}]
  (if (and is-trigger (pos? (parse-num trigger-px)))
    (let [label (trigger-condition-label trigger-condition)
          price (format-trade-price trigger-px)]
      (if label
        (str label " " price)
        (str "Trigger @ " price)))
    "--"))

(defn format-tp-sl [{:keys [is-position-tpsl]}]
  (if is-position-tpsl
    "TP/SL"
    "-- / --"))

(defn order-value [{:keys [sz px]}]
  (let [size (parse-num sz)
        price (parse-num px)]
    (when (and (pos? size) (pos? price))
      (* size price))))

(defn direction-label [side]
  (case side
    "B" "Long"
    "A" "Short"
    "S" "Short"
    (or side "-")))

(defn direction-class [side]
  (case side
    "B" "text-success"
    "A" "text-error"
    "S" "text-error"
    "text-base-content"))

(defn sort-open-orders-by-column [orders column direction]
  (let [sort-fn (case column
                  "Time" (fn [o] (parse-num (:time o)))
                  "Type" (fn [o] (or (:type o) ""))
                  "Coin" (fn [o] (or (:coin o) ""))
                  "Direction" (fn [o] (direction-label (:side o)))
                  "Size" (fn [o] (parse-num (:sz o)))
                  "Original Size" (fn [o] (parse-num (or (:orig-sz o) (:sz o))))
                  "Order Value" (fn [o] (or (order-value o) 0))
                  "Price" (fn [o] (parse-num (:px o)))
                  (fn [_] 0))
        sorted (sort-by sort-fn orders)]
    (if (= direction :desc)
      (reverse sorted)
      sorted)))

(def header-base-text-classes
  ["text-m" "font-medium" "text-trading-text-secondary" "min-h-6" "py-0.5"])

(def sortable-header-interaction-classes
  ["hover:text-trading-text" "transition-colors"])

(def sortable-header-layout-classes
  ["flex" "items-center" "space-x-1" "group"])

(defn sortable-open-orders-header [column-name sort-state]
  (let [current-column (:column sort-state)
        current-direction (:direction sort-state)
        is-active (= current-column column-name)
        sort-icon (when is-active
                    (if (= current-direction :asc) "↑" "↓"))]
    [:button {:class (into []
                           (concat header-base-text-classes
                                   sortable-header-interaction-classes
                                   sortable-header-layout-classes))
              :on {:click [[:actions/sort-open-orders column-name]]}}
     [:span column-name]
     (when sort-icon
       [:span.text-xs.opacity-70 sort-icon])]))

(def default-funding-history-sort
  {:column "Time" :direction :desc})

(defn funding-history-sort-state [funding-history-state]
  (merge default-funding-history-sort
         (or (:sort funding-history-state) {})))

(defn- funding-row-sort-id [row]
  (or (:id row)
      (str (or (:time-ms row) (:time row) 0)
           "|"
           (or (:coin row) "")
           "|"
           (or (:position-size-raw row) (:positionSize row) (:size-raw row) 0)
           "|"
           (or (:payment-usdc-raw row) (:payment row) 0)
           "|"
           (or (:funding-rate-raw row) (:fundingRate row) 0))))

(defn sort-funding-history-by-column [rows column direction]
  (let [sort-fn (case column
                  "Time" (fn [row]
                           (parse-num (or (:time-ms row) (:time row))))
                  "Coin" (fn [row]
                           (or (:coin row) ""))
                  "Size" (fn [row]
                           (js/Math.abs
                            (parse-num (or (:position-size-raw row)
                                           (:positionSize row)
                                           (:size-raw row)))))
                  "Position Side" (fn [row]
                                    (name (funding-side-value row)))
                  "Payment" (fn [row]
                              (parse-num (or (:payment-usdc-raw row)
                                             (:payment row))))
                  "Rate" (fn [row]
                           (parse-num (or (:funding-rate-raw row)
                                          (:fundingRate row))))
                  (fn [_] 0))
        sorted (sort-by (fn [row]
                          [(sort-fn row)
                           (funding-row-sort-id row)])
                        rows)]
    (if (= direction :desc)
      (reverse sorted)
      sorted)))

(defn sortable-funding-history-header [column-name sort-state]
  (let [current-column (:column sort-state)
        current-direction (:direction sort-state)
        is-active (= current-column column-name)
        sort-icon (when is-active
                    (if (= current-direction :asc) "↑" "↓"))]
    [:button {:class (into []
                           (concat header-base-text-classes
                                   sortable-header-interaction-classes
                                   sortable-header-layout-classes))
              :on {:click [[:actions/sort-funding-history column-name]]}}
     [:span column-name]
     (when sort-icon
       [:span.text-xs.opacity-70 sort-icon])]))

(defn format-pnl [pnl-value pnl-pct]
  (if (and (some? pnl-value) (some? pnl-pct))
    (let [pnl-num (parse-num pnl-value)
          pct-num (parse-num pnl-pct)
          color-class (cond
                        (pos? pnl-num) "text-success"
                        (neg? pnl-num) "text-error"
                        :else "text-trading-text")]
      [:span {:class color-class}
       (str (if (pos? pnl-num) "+" "")
            "$" (format-currency pnl-num)
            " (" (if (pos? pct-num) "+" "") (.toFixed pct-num 2) "%)")])
    [:span.text-trading-text "--"]))

(defn- header-alignment-classes [align]
  (case align
    :right ["justify-end" "text-right"]
    :center ["justify-center" "text-center"]
    ["justify-start" "text-left"]))

;; Build balances rows for perps + spot
(defn build-balance-rows [webdata2 spot-data]
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
                    (let [account-value (parse-num (get-in clearinghouse-state [:marginSummary :accountValue]))
                          total-margin-used (parse-num (get-in clearinghouse-state [:marginSummary :totalMarginUsed]))
                          available (- account-value total-margin-used)]
                      {:key "perps-usdc"
                       :coin "USDC (Perps)"
                       :total-balance account-value
                       :available-balance available
                       :usdc-value account-value
                       :pnl-value nil
                       :pnl-pct nil
                       :amount-decimals nil}))
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
                         (get spot-state :balances)))]
    (->> (concat (when perps-row [perps-row]) spot-rows)
         (remove nil?)
         vec)))

;; Sort balances by column
(defn- usdc-balance-row? [row]
  (str/starts-with? (or (:coin row) "") "USDC"))

(defn- balance-sort-value [column row]
  (case column
    "Coin" (or (:coin row) "")
    "Total Balance" (parse-num (:total-balance row))
    "Available Balance" (parse-num (:available-balance row))
    "USDC Value" (parse-num (:usdc-value row))
    "PNL (ROE %)" (parse-num (:pnl-value row))
    0))

(defn- compare-balance-rows [column direction row-a row-b]
  (let [value-a (balance-sort-value column row-a)
        value-b (balance-sort-value column row-b)
        primary-cmp (if (= direction :desc)
                      (compare value-b value-a)
                      (compare value-a value-b))]
    (if (zero? primary-cmp)
      (let [coin-cmp (compare (or (:coin row-a) "")
                              (or (:coin row-b) ""))]
        (if (zero? coin-cmp)
          (compare (or (:key row-a) "")
                   (or (:key row-b) ""))
          coin-cmp))
      primary-cmp)))

(defn sort-balances-by-column [rows column direction]
  (let [[usdc-rows non-usdc-rows]
        (reduce (fn [[usdc* non-usdc*] row]
                  (if (usdc-balance-row? row)
                    [(conj usdc* row) non-usdc*]
                    [usdc* (conj non-usdc* row)]))
                [[] []]
                rows)
        compare-rows (partial compare-balance-rows column direction)]
    (->> (concat (sort compare-rows usdc-rows)
                 (sort compare-rows non-usdc-rows))
         vec)))

;; Sortable balances header
(defn sortable-balances-header
  ([column-name sort-state]
   (sortable-balances-header column-name sort-state :left))
  ([column-name sort-state align]
   (let [current-column (:column sort-state)
         current-direction (:direction sort-state)
         is-active (= current-column column-name)
         sort-icon (when is-active
                     (if (= current-direction :asc) "↑" "↓"))]
     [:button {:class (into ["w-full"]
                            (concat header-base-text-classes
                                    sortable-header-interaction-classes
                                    sortable-header-layout-classes
                                    (header-alignment-classes align)))
               :on {:click [[:actions/sort-balances column-name]]}}
      [:span column-name]
      (when sort-icon
        [:span.text-xs.opacity-70 sort-icon])])))

;; Non-sortable column header
(defn non-sortable-header
  ([column-name]
   (non-sortable-header column-name :left))
  ([column-name align]
   [:div {:class (into ["w-full"]
                       (concat header-base-text-classes
                               (header-alignment-classes align)))}
    column-name]))

(defn tab-table-content [header rows]
  [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
   header
   (into [:div {:class ["flex-1" "min-h-0" "overflow-y-auto" "scrollbar-hide"]}]
         rows)])

;; Balance row component
(defn balance-row [{:keys [coin total-balance available-balance usdc-value pnl-value pnl-pct amount-decimals]}]
  [:div.grid.grid-cols-7.gap-2.py-px.px-3.hover:bg-base-300.items-center.text-sm.text-trading-text
   ;; Coin
   [:div.font-semibold coin]
   ;; Total Balance  
   [:div.text-left.font-semibold (format-balance-amount total-balance amount-decimals)]
   ;; Available Balance
   [:div.text-left.font-semibold (format-balance-amount available-balance amount-decimals)]
   ;; USDC Value
   [:div.text-left.font-semibold "$" (format-currency usdc-value)]
   ;; PNL (ROE %)
   [:div.text-left.font-semibold (format-pnl pnl-value pnl-pct)]
   ;; Send
   [:div.text-left
    [:button {:class ["btn" "btn-xs" "btn-ghost" "text-trading-text"]} "Send"]]
   ;; Transfer/Contract
   [:div.text-left
    [:button {:class ["btn" "btn-xs" "btn-ghost" "text-trading-text"]} "Transfer"]]])

;; Balance table header
(defn balance-table-header [sort-state]
  [:div.grid.grid-cols-7.gap-2.py-1.px-3.bg-base-200.text-sm.font-medium.text-trading-text
   [:div (sortable-balances-header "Coin" sort-state :left)]
   [:div (sortable-balances-header "Total Balance" sort-state :left)]
   [:div (sortable-balances-header "Available Balance" sort-state :left)]
   [:div (sortable-balances-header "USDC Value" sort-state :left)]
   [:div (sortable-balances-header "PNL (ROE %)" sort-state :left)]
   [:div (non-sortable-header "Send" :left)]
   [:div (non-sortable-header "Transfer" :left)]])

;; Balances tab content
(defn balances-tab-content [balance-rows hide-small? sort-state]
  (let [visible-rows (if hide-small?
                       (filter (fn [row]
                                 (>= (parse-num (:usdc-value row)) 1))
                               balance-rows)
                       balance-rows)
        sorted-rows (if (:column sort-state)
                      (sort-balances-by-column visible-rows
                                               (:column sort-state)
                                               (:direction sort-state))
                      visible-rows)]
    (if (seq visible-rows)
      (tab-table-content (balance-table-header sort-state)
                         (for [row sorted-rows]
                           ^{:key (:key row)}
                           (balance-row row)))
      (empty-state "No balance data available"))))

;; Calculate mark price from position data (placeholder - would need market data)
(defn calculate-mark-price [position-data]
  ;; For now, use entry price as approximation - in real app would get from market data
  (:entryPx position-data))

(defn- display-coin [position-data]
  (let [coin (:coin position-data)
        parsed (parse-coin-namespace coin)]
    (or (:base parsed)
        (non-blank-text coin)
        "-")))

(defn- dex-chip-label [position-data]
  (let [explicit-dex (non-blank-text (:dex position-data))
        parsed-prefix (some-> (:coin position-data) parse-coin-namespace :prefix)]
    (or explicit-dex parsed-prefix)))

;; Format position size display
(defn format-position-size [position-data]
  (let [size (or (:szi position-data) "0")
        coin (display-coin position-data)]
    (str size " " coin)))

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
        coin-label (display-coin pos)
        dex-label (dex-chip-label {:coin (:coin pos)
                                   :dex (:dex position-data)})
        leverage (get-in pos [:leverage :value])
        position-value (:positionValue pos)
        entry-price (:entryPx pos)
        mark-price (calculate-mark-price pos)
        pnl-value (:unrealizedPnl pos)
        pnl-percent (* 100 (js/parseFloat (:returnOnEquity pos)))
        liq-price (:liquidationPx pos)
        margin (:marginUsed pos)
        funding (get-in pos [:cumFunding :allTime])]
    [:div.grid.grid-cols-11.gap-2.py-0.px-3.hover:bg-base-300.items-center.text-sm
     ;; Coin with leverage and dex chips
     [:div {:class ["flex" "items-center" "gap-1.5" "min-w-0" "self-stretch"]
            :style position-coin-cell-style}
      [:span {:class ["font-medium" "truncate"]} coin-label]
      (when (some? leverage)
        [:span {:class position-chip-classes} (str leverage "x")])
      (when dex-label
        [:span {:class position-chip-classes} dex-label])]
     ;; Size
     [:div.text-left.font-semibold (format-position-size pos)]
     ;; Position Value  
     [:div.text-left.font-semibold "$" (format-currency position-value)]
     ;; Entry Price
     [:div.text-left.font-semibold (format-trade-price entry-price)]
     ;; Mark Price
     [:div.text-left.font-semibold (format-trade-price mark-price)]
     ;; PNL (ROE %)
     [:div.text-left.font-semibold
      [:div
       [:span {:class (if (pos? (js/parseFloat pnl-value))
                       "text-success" "text-error")}
        "$" (format-currency pnl-value)]
       [:div.text-xs.opacity-70
        [:span {:class (if (pos? pnl-percent)
                        "text-success"
                        "text-error")}
         "(" (if (pos? pnl-percent) "+"
               "")
         (.toFixed pnl-percent 2) "%)"]]]]
     ;; Liq. Price
     [:div.text-left.font-semibold (if liq-price (format-trade-price liq-price) "N/A")]
     ;; Margin
     [:div.text-left.font-semibold "$" (format-currency margin)]
     ;; Funding
     [:div.text-left.font-semibold
      (let [funding-num (js/parseFloat funding)
            display-funding (if (pos? funding-num) (- funding-num) funding-num)
            display-text (str "$" (format-currency (str display-funding)))]
        [:span {:class (if (neg? display-funding)
                        "text-error" 
                        "text-success")}
         display-text])]
     ;; Close All
     [:div.text-left
      [:button.btn.btn-xs.btn-ghost "Limit"]
      [:button.btn.btn-xs.btn-ghost.ml-1 "Market"]]
     ;; TP/SL
     [:div.text-left
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
    [:button {:class (into []
                           (concat header-base-text-classes
                                   sortable-header-interaction-classes
                                   sortable-header-layout-classes))
              :on {:click [[:actions/sort-positions column-name]]}}
     [:span column-name]
     (when sort-icon
       [:span.text-xs.opacity-70 sort-icon])]))

;; Position table header with sorting
(defn position-table-header [sort-state]
  [:div.grid.grid-cols-11.gap-2.py-1.px-3.bg-base-200
   [:div.text-left (sortable-header "Coin" sort-state)]
   [:div.text-left (sortable-header "Size" sort-state)]
   [:div.text-left (sortable-header "Position Value" sort-state)]
   [:div.text-left (sortable-header "Entry Price" sort-state)]
   [:div.text-left (sortable-header "Mark Price" sort-state)]
   [:div.text-left (sortable-header "PNL (ROE %)" sort-state)]
   [:div.text-left (sortable-header "Liq. Price" sort-state)]
   [:div.text-left (sortable-header "Margin" sort-state)]
   [:div.text-left (sortable-header "Funding" sort-state)]
   [:div.text-left (non-sortable-header "Close All")]
   [:div.text-left (non-sortable-header "TP/SL")]])

;; Positions tab content
(defn positions-tab-content [webdata2 sort-state perp-dex-states]
  (let [positions (collect-positions webdata2 perp-dex-states)
        sorted-positions (if positions
                          (sort-positions-by-column positions 
                                                   (:column sort-state) 
                                                   (:direction sort-state))
                          [])]
    (if (and positions (seq positions))
      (tab-table-content (position-table-header sort-state)
                         (for [position sorted-positions]
                           ^{:key (position-unique-key position)}
                           (position-row position)))
      (empty-state "No active positions"))))

;; Placeholder tab content for other tabs
(defn placeholder-tab-content [tab-name]
  [:div.p-4
   [:div.text-lg.font-medium.mb-4 (get tab-labels tab-name (name tab-name))]
   (empty-state (str (get tab-labels tab-name (name tab-name)) " coming soon"))])

(defn open-orders-tab-content [normalized sort-state]
  (let [sorted (sort-open-orders-by-column normalized
                                           (:column sort-state)
                                           (:direction sort-state))]
    (if (seq sorted)
      (tab-table-content
        [:div {:class ["grid" "gap-2" "py-1" "px-3" "bg-base-200" "text-xs" "font-medium" "grid-cols-[130px_70px_60px_70px_60px_80px_100px_70px_70px_120px_50px_70px]"]}
         [:div.pr-2.whitespace-nowrap (sortable-open-orders-header "Time" sort-state)]
         [:div.pl-1 (sortable-open-orders-header "Type" sort-state)]
         [:div (sortable-open-orders-header "Coin" sort-state)]
         [:div (sortable-open-orders-header "Direction" sort-state)]
         [:div.text-left (sortable-open-orders-header "Size" sort-state)]
         [:div.text-left (sortable-open-orders-header "Original Size" sort-state)]
         [:div.text-left (sortable-open-orders-header "Order Value" sort-state)]
         [:div.text-left (sortable-open-orders-header "Price" sort-state)]
         [:div.text-left.whitespace-nowrap "Reduce Only"]
         [:div.text-left.whitespace-nowrap "Trigger Conditions"]
         [:div.text-left "TP/SL"]
         [:div.text-left "Cancel All"]]
        (for [o sorted]
          ^{:key (str (:oid o) "-" (:coin o))}
          [:div {:class ["grid" "gap-2" "py-px" "px-3" "hover:bg-base-300" "text-xs" "grid-cols-[130px_70px_60px_70px_60px_80px_100px_70px_70px_120px_50px_70px]"]}
           [:div.pr-2.whitespace-nowrap (format-open-orders-time (:time o))]
           [:div.pl-1 (or (:type o) "Order")]
           [:div (:coin o)]
           [:div {:class (direction-class (:side o))} (direction-label (:side o))]
           [:div.text-left (format-currency (:sz o))]
           [:div.text-left (format-currency (or (:orig-sz o) (:sz o)))]
           [:div.text-left (if-let [val (order-value o)]
                              (str (format-currency val) " USDC")
                              "--")]
           [:div.text-left (format-trade-price (:px o))]
           [:div.text-left (if (:reduce-only o) "Yes" "No")]
           [:div.text-left (format-trigger-conditions o)]
           [:div.text-left (format-tp-sl o)]
           [:div.text-left
            [:button {:class ["btn" "btn-xs" "btn-ghost"]
                      :on {:click [[:actions/cancel-order o]]}}
             "Cancel"]]]))
      (empty-state "No open orders"))))

(defn trade-history-tab-content [fills]
  (if (seq fills)
    (tab-table-content
      [:div.grid.grid-cols-6.gap-2.py-1.px-3.bg-base-200.text-sm.font-medium
       [:div "Coin"]
       [:div.text-left "Side"]
       [:div.text-left "Size"]
       [:div.text-left "Price"]
       [:div.text-left "Fee"]
       [:div.text-left "Time"]]
      (for [f fills]
        ^{:key (str (:tid f) "-" (:coin f) "-" (:time f))}
        [:div.grid.grid-cols-6.gap-2.py-px.px-3.hover:bg-base-300.text-sm
         [:div (:coin f)]
         [:div.text-left (format-side (:side f))]
         [:div.text-left (format-currency (:sz f))]
         [:div.text-left (format-trade-price (:px f))]
         [:div.text-left (format-currency (:fee f))]
         [:div.text-left (format-timestamp (:time f))]]))
    (empty-state "No fills")))

(defn- funding-history-table [fundings funding-history-state]
  (let [sort-state (funding-history-sort-state funding-history-state)
        sorted-fundings (sort-funding-history-by-column fundings
                                                        (:column sort-state)
                                                        (:direction sort-state))]
    (if (seq sorted-fundings)
      (tab-table-content
       [:div.grid.grid-cols-6.gap-2.py-1.px-3.bg-base-200.text-sm.font-medium
        [:div (sortable-funding-history-header "Time" sort-state)]
        [:div.text-left (sortable-funding-history-header "Coin" sort-state)]
        [:div.text-left (sortable-funding-history-header "Size" sort-state)]
        [:div.text-left (sortable-funding-history-header "Position Side" sort-state)]
        [:div.text-left (sortable-funding-history-header "Payment" sort-state)]
        [:div.text-left (sortable-funding-history-header "Rate" sort-state)]]
       (for [f sorted-fundings]
         ^{:key (funding-row-sort-id f)}
         [:div.grid.grid-cols-6.gap-2.py-px.px-3.hover:bg-base-300.text-sm
          [:div (format-funding-history-time (or (:time-ms f) (:time f)))]
          [:div.text-left (:coin f)]
          [:div.text-left (funding-size-text f)]
          [:div.text-left
           (let [position-side (funding-side-value f)]
             [:span {:class (funding-side-class position-side)}
              (funding-side-label position-side)])]
          [:div.text-left (funding-payment-node f)]
          [:div.text-left (funding-rate-node f)]]))
      (if (:loading? funding-history-state)
        (empty-state "Loading funding history...")
        (empty-state "No funding history")))))

(defn funding-history-tab-content
  ([fundings]
   (funding-history-table fundings {}))
  ([fundings funding-history-state fundings-raw]
   [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
    (funding-history-controls funding-history-state fundings-raw)
    (funding-history-table fundings funding-history-state)]))

(defn order-history-tab-content [ledger]
  (if (seq ledger)
    (tab-table-content
      [:div.grid.grid-cols-4.gap-2.py-1.px-3.bg-base-200.text-sm.font-medium
       [:div "Type"]
       [:div.text-left "Asset"]
       [:div.text-left "Delta"]
       [:div.text-left "Time"]]
      (for [l ledger]
        ^{:key (str (:time l) "-" (:coin l) "-" (:delta l))}
        [:div.grid.grid-cols-4.gap-2.py-px.px-3.hover:bg-base-300.text-sm
         [:div (or (:type l) "event")]
         [:div.text-left (or (:coin l) "-")]
         [:div.text-left (format-currency (:delta l))]
         [:div.text-left (format-timestamp (:time l))]]))
    (empty-state "No order history")))

;; Main tab content renderer
(defn tab-content [selected-tab webdata2 sort-state hide-small? perp-dex-states open-orders open-orders-sort balance-rows balances-sort funding-history-state]
  (case selected-tab
    :balances (balances-tab-content balance-rows hide-small? balances-sort)
    :positions (positions-tab-content webdata2 sort-state perp-dex-states)
    :open-orders (open-orders-tab-content open-orders open-orders-sort)
    :twap (placeholder-tab-content :twap)
    :trade-history (trade-history-tab-content (get-in webdata2 [:fills]))
    :funding-history (funding-history-tab-content (get-in webdata2 [:fundings])
                                                  funding-history-state
                                                  (get-in webdata2 [:fundings-raw]))
    :order-history (order-history-tab-content (get-in webdata2 [:ledger]))
    (empty-state "Unknown tab")))

;; Account info panel component
(defn account-info-panel [state]
  (let [selected-tab (get-in state [:account-info :selected-tab] :balances)
        webdata2 (merge (:webdata2 state) (get state :orders))
        loading? (get-in state [:account-info :loading] false)
        error (get-in state [:account-info :error])
        sort-state (get-in state [:account-info :positions-sort] {:column nil :direction :asc})
        balances-sort (get-in state [:account-info :balances-sort] {:column nil :direction :asc})
        spot-data (:spot state)
        balance-rows (build-balance-rows webdata2 spot-data)
        hide-small? (get-in state [:account-info :hide-small-balances?] false)
        perp-dex-states (:perp-dex-clearinghouse state)
        positions (collect-positions webdata2 perp-dex-states)
        open-orders (normalized-open-orders (get-in webdata2 [:open-orders])
                                            (get-in webdata2 [:open-orders-snapshot])
                                            (get-in webdata2 [:open-orders-snapshot-by-dex]))
        funding-history-state (get-in state [:account-info :funding-history] {})
        tab-counts {:open-orders (count open-orders)
                    :positions (count positions)
                    :balances (count balance-rows)}
        open-orders-sort (get-in state [:account-info :open-orders-sort] {:column "Time" :direction :desc})]
    [:div {:class ["bg-base-100" "border-t" "border-base-300" "rounded-none" "shadow-none" "overflow-hidden" "w-full" "h-96" "flex" "flex-col" "min-h-0"]}
     ;; Tab navigation
     (tab-navigation selected-tab tab-counts hide-small? funding-history-state)
     
     ;; Content area
     [:div {:class ["flex-1" "min-h-0" "overflow-hidden"]}
      (cond
        error (error-state error)
        loading? (loading-spinner)
        :else (tab-content selected-tab
                           webdata2
                           sort-state
                           hide-small?
                           perp-dex-states
                           open-orders
                           open-orders-sort
                           balance-rows
                           balances-sort
                           funding-history-state))]]))

;; Main component that takes state and renders the UI
(defn account-info-view [state]
  (account-info-panel state))
