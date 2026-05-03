(ns hyperopen.views.account-info-view
  (:require [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.tab-actions :as tab-actions]
            [hyperopen.views.account-info.tab-registry :as tab-registry]
            [hyperopen.views.account-info.table :as account-table]
            [hyperopen.views.account-info.vm :as account-info-vm]
            [hyperopen.views.account-info.tabs.balances :as balances-tab]
            [hyperopen.views.account-info.tabs.funding-history :as funding-history-tab]
            [hyperopen.views.account-info.tabs.open-orders :as open-orders-tab]
            [hyperopen.views.account-info.tabs.order-history :as order-history-tab]
            [hyperopen.views.account-info.tabs.outcomes :as outcomes-tab]
            [hyperopen.views.account-info.tabs.positions :as positions-tab]
            [hyperopen.views.account-info.tabs.twap :as twap-tab]
            [hyperopen.views.account-info.tabs.trade-history :as trade-history-tab]))

(def ^:private account-tab-default-panel-classes
  ["h-96" "lg:h-[29rem]"])

(def available-tabs
  tab-registry/available-tabs)

(def tab-labels
  tab-registry/tab-labels)

(def tab-label
  tab-registry/tab-label)

(def tab-navigation
  tab-actions/tab-navigation)

(defn loading-spinner []
  [:div.flex.justify-center.items-center.py-8
   [:div.animate-spin.rounded-full.h-8.w-8.border-b-2.border-primary]])

(defn empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div {:class ["mt-2" "text-sm" "text-trading-text-secondary"]} "No data available"]])

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
  (let [num-val (if (and value (not= value "N/A"))
                  (let [parsed (js/parseFloat value)
                        rounded (if (js/isNaN parsed)
                                  0
                                  (/ (js/Math.round (* parsed 100)) 100))]
                    (if (zero? rounded) 0 rounded))
                  0)
        color-class (cond
                      (pos? num-val) "text-success"
                      (neg? num-val) "text-error"
                      :else "text-base-content")]
    [:span {:class color-class}
     (str (if (pos? num-val) "+" "") (.toFixed num-val 2) "%")]))
(defn format-timestamp [ms]
  (when ms (.toLocaleString (js/Date. ms))))

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
(def reset-positions-sort-cache! positions-tab/reset-positions-sort-cache!)

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
(def reset-open-orders-sort-cache! open-orders-tab/reset-open-orders-sort-cache!)

(def default-trade-history-sort trade-history-tab/default-trade-history-sort)
(def trade-history-sort-state trade-history-tab/trade-history-sort-state)
(def sort-trade-history-by-column trade-history-tab/sort-trade-history-by-column)
(def sortable-trade-history-header trade-history-tab/sortable-trade-history-header)
(def trade-history-table trade-history-tab/trade-history-table)
(def trade-history-tab-content trade-history-tab/trade-history-tab-content)
(def reset-trade-history-sort-cache! trade-history-tab/reset-trade-history-sort-cache!)

(def default-funding-history-sort funding-history-tab/default-funding-history-sort)
(def funding-history-sort-state funding-history-tab/funding-history-sort-state)
(def sort-funding-history-by-column funding-history-tab/sort-funding-history-by-column)
(def sortable-funding-history-header funding-history-tab/sortable-funding-history-header)
(def funding-history-controls funding-history-tab/funding-history-controls)
(def funding-history-table funding-history-tab/funding-history-table)
(def funding-history-tab-content funding-history-tab/funding-history-tab-content)
(def reset-funding-history-sort-cache! funding-history-tab/reset-funding-history-sort-cache!)

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
(def reset-order-history-sort-cache! order-history-tab/reset-order-history-sort-cache!)

(defn placeholder-tab-content
  ([tab-name]
   (placeholder-tab-content tab-name tab-registry/tab-labels))
  ([tab-name labels]
   [:div.p-4
    [:div.text-lg.font-medium.mb-4 (get labels tab-name (name tab-name))]
    (empty-state (str (get labels tab-name (name tab-name)) " coming soon"))]))

(def ^:private tab-renderers
  {:balances (fn [{:keys [balance-rows
                          hide-small?
                          balances-sort
                          balances-coin-search
                          mobile-expanded-card
                          read-only?
                          read-only-message]}]
               (balances-tab-content balance-rows
                                     hide-small?
                                     balances-sort
                                     balances-coin-search
                                     {:mobile-expanded-card mobile-expanded-card
                                      :read-only? read-only?
                                      :read-only-message read-only-message}))
   :positions (fn [{:keys [positions
                           webdata2
                           positions-sort
                           perp-dex-states
                           position-tpsl-modal
                           position-reduce-popover
                           position-margin-modal
                           positions-state
                           mobile-expanded-card]}]
                (if (some? positions)
                  (positions-tab-content {:positions positions
                                          :sort-state positions-sort
                                          :tpsl-modal position-tpsl-modal
                                          :reduce-popover position-reduce-popover
                                          :margin-modal position-margin-modal
                                          :positions-state (assoc positions-state
                                                                  :mobile-expanded-card mobile-expanded-card)})
                  (positions-tab-content {:webdata2 webdata2
                                          :sort-state positions-sort
                                          :perp-dex-states perp-dex-states
                                          :tpsl-modal position-tpsl-modal
                                          :reduce-popover position-reduce-popover
                                          :margin-modal position-margin-modal
                                          :positions-state (assoc positions-state
                                                                  :mobile-expanded-card mobile-expanded-card)})))
   :outcomes (fn [{:keys [outcomes position-reduce-popover read-only?]}]
               (outcomes-tab/outcomes-tab-content {:outcomes outcomes
                                                   :reduce-popover position-reduce-popover
                                                   :read-only? read-only?}))
   :open-orders (fn [{:keys [open-orders open-orders-sort open-orders-state]}]
                  (open-orders-tab-content open-orders open-orders-sort open-orders-state))
   :twap (fn [view-model]
           (twap-tab/twap-tab-content view-model))
   :trade-history (fn [{:keys [trade-history-rows trade-history-state mobile-expanded-card]}]
                    (trade-history-tab-content trade-history-rows
                                               (assoc trade-history-state
                                                      :mobile-expanded-card mobile-expanded-card)))
   :funding-history (fn [{:keys [funding-history-rows funding-history-state funding-history-raw]}]
                      (funding-history-tab-content funding-history-rows
                                                   funding-history-state
                                                   funding-history-raw))
   :order-history (fn [{:keys [order-history-rows order-history-state]}]
                    (order-history-tab-content order-history-rows order-history-state))})

(defn- extra-tab-renderers [extra-tabs]
  (reduce (fn [acc {:keys [id content render]}]
            (if (keyword? id)
          (cond
                (fn? render)
                (assoc acc id render)

                (some? content)
                (assoc acc id (fn [_]
                                content))

                :else acc)
              acc))
          {}
          (tab-registry/normalized-extra-tabs extra-tabs)))

(defn tab-content
  ([view-model]
   (tab-content view-model {}))
  ([view-model extra-renderers]
   (if-let [render-tab (or (get extra-renderers (:selected-tab view-model))
                           (get tab-renderers (:selected-tab view-model)))]
     (render-tab view-model)
     (empty-state "Unknown tab")))
  ([selected-tab webdata2 sort-state hide-small? perp-dex-states open-orders open-orders-sort balance-rows balances-sort trade-history-state funding-history-state order-history-state]
   (tab-content selected-tab
                webdata2
                sort-state
                hide-small?
                perp-dex-states
                open-orders
                open-orders-sort
                balance-rows
                balances-sort
                trade-history-state
                funding-history-state
                order-history-state
                ""))
  ([selected-tab webdata2 sort-state hide-small? perp-dex-states open-orders open-orders-sort balance-rows balances-sort trade-history-state funding-history-state order-history-state balances-coin-search]
   (tab-content {:selected-tab selected-tab
                 :webdata2 webdata2
                 :positions-sort sort-state
                 :hide-small? hide-small?
                 :perp-dex-states perp-dex-states
                 :open-orders open-orders
                 :open-orders-sort open-orders-sort
                 :balance-rows balance-rows
                 :balances-sort balances-sort
                 :balances-coin-search balances-coin-search
                 :trade-history-rows (get-in webdata2 [:fills])
                 :trade-history-state trade-history-state
                 :funding-history-rows (get-in webdata2 [:fundings])
                 :funding-history-state funding-history-state
                 :funding-history-raw (get-in webdata2 [:fundings-raw])
                 :order-history-rows (get-in webdata2 [:order-history])
                 :order-history-state order-history-state})))

(defn account-info-panel
  ([state]
   (account-info-panel state {}))
  ([state {:keys [extra-tabs
                  selected-tab-override
                  default-selected-tab
                  default-panel-classes
                  default-panel-style
                  tab-click-actions-by-tab
                  tab-label-overrides
                  tab-order]
           :or {extra-tabs []
                default-panel-classes account-tab-default-panel-classes
                tab-click-actions-by-tab {}
                tab-label-overrides {}
                tab-order []}}]
   (let [view-model (account-info-vm/account-info-vm state)
         extra-tabs* (tab-registry/normalized-extra-tabs extra-tabs)
         {:keys [selected-tab
                 tab-counts
                 hide-small?
                 balances-coin-search
                 funding-history-state
                 trade-history-state
                 order-history-state
                 positions-state
                 open-orders-state
                 freshness-cues
                 error
                 loading?]} view-model
         available-tabs* (tab-registry/available-tabs-for extra-tabs* tab-order tab-label-overrides)
         fallback-selected-tab (if (some #(= % default-selected-tab) available-tabs*)
                                 default-selected-tab
                                 (or (first available-tabs*)
                                     :balances))
         selected-tab* (let [candidate (or selected-tab-override selected-tab)]
                         (if (some #(= % candidate) available-tabs*)
                           candidate
                           fallback-selected-tab))
         selected-extra-tab (some (fn [{:keys [id] :as tab}]
                                    (when (= id selected-tab*)
                                      tab))
                                  extra-tabs*)
         extra-renderers (extra-tab-renderers extra-tabs)
         selected-extra-renderer (get extra-renderers selected-tab*)
         panel-shell-classes (or (:panel-classes selected-extra-tab)
                                 default-panel-classes)
         panel-shell-style (or (:panel-style selected-extra-tab)
                               default-panel-style)]
     [:div {:class (into ["bg-base-100"
                          "border-t"
                          "border-base-300"
                          "rounded-none"
                          "spectate-none"
                          "overflow-hidden"
                          "w-full"
                          "flex"
                          "flex-col"
                          "min-h-0"]
                         panel-shell-classes)
            :style panel-shell-style
            :data-parity-id "account-tables"}
      (tab-actions/tab-navigation selected-tab*
                                  tab-counts
                                  hide-small?
                                  funding-history-state
                                  trade-history-state
                                  order-history-state
                                  positions-state
                                  open-orders-state
                                  freshness-cues
                                  balances-coin-search
                                  {:extra-tabs extra-tabs
                                   :tab-click-actions-by-tab tab-click-actions-by-tab
                                   :tab-label-overrides tab-label-overrides
                                   :tab-order tab-order})
      [:div {:class ["flex-1" "min-h-0" "min-w-0" "overflow-hidden"]}
       (cond
         (and (nil? selected-extra-renderer) error)
         (error-state error)

         (and (nil? selected-extra-renderer) loading?)
         (loading-spinner)

         :else
         (tab-content (assoc view-model :selected-tab selected-tab*)
                      extra-renderers))]])))

(defn account-info-view
  ([state]
   (account-info-panel state))
  ([state options]
   (account-info-panel state options)))
