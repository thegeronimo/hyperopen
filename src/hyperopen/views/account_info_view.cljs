(ns hyperopen.views.account-info-view
  (:require [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.table :as account-table]
            [hyperopen.views.account-info.vm :as account-info-vm]
            [hyperopen.views.account-info.tabs.balances :as balances-tab]
            [hyperopen.views.account-info.tabs.funding-history :as funding-history-tab]
            [hyperopen.views.account-info.tabs.open-orders :as open-orders-tab]
            [hyperopen.views.account-info.tabs.order-history :as order-history-tab]
            [hyperopen.views.account-info.tabs.positions :as positions-tab]
            [hyperopen.views.account-info.tabs.trade-history :as trade-history-tab]))

(def ^:private tab-definitions
  (array-map
   :balances {:label "Balances"}
   :positions {:label "Positions"}
   :open-orders {:label "Open Orders"}
   :twap {:label "TWAP"}
   :trade-history {:label "Trade History"}
   :funding-history {:label "Funding History"}
   :order-history {:label "Order History"}))

(def available-tabs
  (vec (keys tab-definitions)))

(def tab-labels
  (into {}
        (map (fn [[tab {:keys [label]}]]
               [tab label]))
        tab-definitions))

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

(defn- freshness-cue-node [cue]
  (when (map? cue)
    [:div {:class ["ml-auto" "px-4" "py-2"]
           :data-role "account-tab-freshness-cue"}
     [:span {:class (case (:tone cue)
                      :success ["text-xs" "font-medium" "text-success" "tracking-wide"]
                      :warning ["text-xs" "font-medium" "text-warning" "tracking-wide"]
                      ["text-xs" "font-medium" "text-base-content/70" "tracking-wide"])}
      (:text cue)]]))

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

(def order-history-status-options
  order-history-tab/order-history-status-options)

(def order-history-status-labels
  order-history-tab/order-history-status-labels)

(defn- order-history-status-filter-key [order-history-state]
  (order-history-tab/order-history-status-filter-key order-history-state))

(defn- order-history-header-actions [order-history-state]
  (let [filter-open? (boolean (:filter-open? order-history-state))
        status-filter (order-history-status-filter-key order-history-state)
        status-label (get order-history-status-labels status-filter "All")]
    [:div {:class ["ml-auto" "relative" "flex" "items-center" "justify-end" "gap-2" "px-4" "py-2"]}
     [:button {:class ["btn" "btn-xs" "btn-ghost" "font-normal" "text-trading-green" "hover:bg-trading-green/10" "hover:text-trading-green"]
               :on {:click [[:actions/toggle-order-history-filter-open]]}}
      "Filter"
      [:span {:class ["ml-1" "text-xs" "opacity-70"]} (str "(" status-label ")")]
      [:span {:class ["ml-1" "text-xs" "opacity-70"]} "v"]]
     (when filter-open?
       [:div {:class ["absolute" "right-4" "top-full" "z-20" "mt-1" "w-40" "overflow-hidden" "rounded-md" "border" "border-base-300" "bg-base-100" "shadow-lg"]}
        (for [[option-key option-label] order-history-status-options]
          ^{:key (name option-key)}
          [:button {:class (into ["flex" "w-full" "items-center" "justify-between" "px-3" "py-2" "text-xs" "transition-colors"]
                                 (if (= status-filter option-key)
                                   ["bg-base-200" "text-trading-green"]
                                   ["text-trading-text-secondary" "hover:bg-base-200" "hover:text-trading-text"]))
                    :on {:click [[:actions/set-order-history-status-filter option-key]]}}
           option-label
           (when (= status-filter option-key)
             [:span {:class ["text-trading-green"]} "*"])])])]))

(defn tab-navigation
  ([selected-tab counts hide-small? funding-history-state]
   (tab-navigation selected-tab counts hide-small? funding-history-state {} nil))
  ([selected-tab counts hide-small? _funding-history-state order-history-state]
   (tab-navigation selected-tab counts hide-small? _funding-history-state order-history-state nil))
  ([selected-tab counts hide-small? _funding-history-state order-history-state freshness-cues]
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

      :order-history
      (order-history-header-actions order-history-state)

      :positions
      (freshness-cue-node (get freshness-cues :positions))

      :open-orders
      (freshness-cue-node (get freshness-cues :open-orders))

      nil)]))

(defn loading-spinner []
  [:div.flex.justify-center.items-center.py-8
   [:div.animate-spin.rounded-full.h-8.w-8.border-b-2.border-primary]])

(defn empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div.text-sm.opacity-70.mt-2 "No data available"]])

(defn error-state [error]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-error
   [:div.text-lg.font-medium "Error loading account data"]
   [:div.text-sm.opacity-70.mt-2 (str error)]])

(def format-currency shared/format-currency)
(def parse-num shared/parse-num)
(def format-trade-price shared/format-trade-price)
(def format-amount shared/format-amount)
(def format-balance-amount shared/format-balance-amount)
(def format-funding-history-time shared/format-funding-history-time)
(def format-open-orders-time shared/format-open-orders-time)
(def format-pnl shared/format-pnl)
(def non-blank-text shared/non-blank-text)
(def parse-coin-namespace shared/parse-coin-namespace)
(def resolve-coin-display shared/resolve-coin-display)

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

(def build-balance-rows balances-tab/build-balance-rows)
(def build-balance-rows-for-account balances-tab/build-balance-rows-for-account)
(def sort-balances-by-column balances-tab/sort-balances-by-column)
(def sortable-balances-header balances-tab/sortable-balances-header)
(def non-sortable-header account-table/non-sortable-header)
(def tab-table-content account-table/tab-table-content)
(def balance-row balances-tab/balance-row)
(def balance-table-header balances-tab/balance-table-header)
(def balances-tab-content balances-tab/balances-tab-content)

(def calculate-mark-price positions-tab/calculate-mark-price)
(def format-position-size positions-tab/format-position-size)
(def position-unique-key positions-tab/position-unique-key)
(def collect-positions positions-tab/collect-positions)
(def position-row positions-tab/position-row)
(def sort-positions-by-column positions-tab/sort-positions-by-column)
(def sortable-header positions-tab/sortable-header)
(def position-table-header positions-tab/position-table-header)
(def positions-tab-content positions-tab/positions-tab-content)

(def format-side open-orders-tab/format-side)
(def normalize-open-order open-orders-tab/normalize-open-order)
(def open-orders-seq open-orders-tab/open-orders-seq)
(def open-orders-by-dex open-orders-tab/open-orders-by-dex)
(def open-orders-source open-orders-tab/open-orders-source)
(def normalized-open-orders open-orders-tab/normalized-open-orders)
(def trigger-condition-label open-orders-tab/trigger-condition-label)
(def format-trigger-conditions open-orders-tab/format-trigger-conditions)
(def format-tp-sl open-orders-tab/format-tp-sl)
(def order-value open-orders-tab/order-value)
(def direction-label open-orders-tab/direction-label)
(def direction-class open-orders-tab/direction-class)
(def sort-open-orders-by-column open-orders-tab/sort-open-orders-by-column)
(def sortable-open-orders-header open-orders-tab/sortable-open-orders-header)
(def open-orders-tab-content open-orders-tab/open-orders-tab-content)

(def default-trade-history-sort trade-history-tab/default-trade-history-sort)
(def trade-history-sort-state trade-history-tab/trade-history-sort-state)
(def sort-trade-history-by-column trade-history-tab/sort-trade-history-by-column)
(def sortable-trade-history-header trade-history-tab/sortable-trade-history-header)
(def trade-history-table trade-history-tab/trade-history-table)
(def trade-history-tab-content trade-history-tab/trade-history-tab-content)

(def default-funding-history-sort funding-history-tab/default-funding-history-sort)
(def funding-history-sort-state funding-history-tab/funding-history-sort-state)
(def sort-funding-history-by-column funding-history-tab/sort-funding-history-by-column)
(def sortable-funding-history-header funding-history-tab/sortable-funding-history-header)
(def funding-history-controls funding-history-tab/funding-history-controls)
(def funding-history-table funding-history-tab/funding-history-table)
(def funding-history-tab-content funding-history-tab/funding-history-tab-content)

(def default-order-history-sort order-history-tab/default-order-history-sort)
(def order-history-sort-state order-history-tab/order-history-sort-state)
(def normalize-order-history-page-size order-history-tab/normalize-order-history-page-size)
(def normalize-order-history-page order-history-tab/normalize-order-history-page)
(def normalize-order-history-row order-history-tab/normalize-order-history-row)
(def normalized-order-history order-history-tab/normalized-order-history)
(def sort-order-history-by-column order-history-tab/sort-order-history-by-column)
(def sortable-order-history-header order-history-tab/sortable-order-history-header)
(def format-order-history-filled-size order-history-tab/format-order-history-filled-size)
(def format-order-history-price order-history-tab/format-order-history-price)
(def format-order-history-reduce-only order-history-tab/format-order-history-reduce-only)
(def format-order-history-trigger order-history-tab/format-order-history-trigger)
(def order-history-long-coin-color order-history-tab/order-history-long-coin-color)
(def order-history-tab-content order-history-tab/order-history-tab-content)
(def order-history-table order-history-tab/order-history-table)

(defn placeholder-tab-content [tab-name]
  [:div.p-4
   [:div.text-lg.font-medium.mb-4 (get tab-labels tab-name (name tab-name))]
   (empty-state (str (get tab-labels tab-name (name tab-name)) " coming soon"))])

(def ^:private tab-renderers
  {:balances (fn [{:keys [balance-rows hide-small? balances-sort]}]
               (balances-tab-content balance-rows hide-small? balances-sort))
   :positions (fn [{:keys [webdata2 positions-sort perp-dex-states]}]
                (positions-tab-content webdata2 positions-sort perp-dex-states))
   :open-orders (fn [{:keys [open-orders open-orders-sort]}]
                  (open-orders-tab-content open-orders open-orders-sort))
   :twap (fn [_]
           (placeholder-tab-content :twap))
   :trade-history (fn [{:keys [trade-history-rows trade-history-state]}]
                    (trade-history-tab-content trade-history-rows trade-history-state))
   :funding-history (fn [{:keys [funding-history-rows funding-history-state funding-history-raw]}]
                      (funding-history-tab-content funding-history-rows
                                                   funding-history-state
                                                   funding-history-raw))
   :order-history (fn [{:keys [order-history-rows order-history-state]}]
                    (order-history-tab-content order-history-rows order-history-state))})

(defn tab-content
  ([view-model]
   (if-let [render-tab (get tab-renderers (:selected-tab view-model))]
     (render-tab view-model)
     (empty-state "Unknown tab")))
  ([selected-tab webdata2 sort-state hide-small? perp-dex-states open-orders open-orders-sort balance-rows balances-sort trade-history-state funding-history-state order-history-state]
   (tab-content {:selected-tab selected-tab
                 :webdata2 webdata2
                 :positions-sort sort-state
                 :hide-small? hide-small?
                 :perp-dex-states perp-dex-states
                 :open-orders open-orders
                 :open-orders-sort open-orders-sort
                 :balance-rows balance-rows
                 :balances-sort balances-sort
                 :trade-history-rows (get-in webdata2 [:fills])
                 :trade-history-state trade-history-state
                 :funding-history-rows (get-in webdata2 [:fundings])
                 :funding-history-state funding-history-state
                 :funding-history-raw (get-in webdata2 [:fundings-raw])
                 :order-history-rows (get-in webdata2 [:order-history])
                 :order-history-state order-history-state})))

(defn account-info-panel [state]
  (let [view-model (account-info-vm/account-info-vm state)
        {:keys [selected-tab
                tab-counts
                hide-small?
                funding-history-state
                order-history-state
                freshness-cues
                error
                loading?]} view-model]
    [:div {:class ["bg-base-100" "border-t" "border-base-300" "rounded-none" "shadow-none" "overflow-hidden" "w-full" "h-96" "flex" "flex-col" "min-h-0"]}
     (tab-navigation selected-tab tab-counts hide-small? funding-history-state order-history-state freshness-cues)
     [:div {:class ["flex-1" "min-h-0" "overflow-hidden"]}
      (cond
        error (error-state error)
        loading? (loading-spinner)
        :else (tab-content view-model))]]))

(defn account-info-view [state]
  (account-info-panel state))
