(ns hyperopen.views.account-info-view
  (:require [hyperopen.account-tab-modules :as account-tab-modules]
            [hyperopen.ui.voice :as voice]
            [hyperopen.views.account-info.loading-skeleton :as loading-skeleton]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.tab-actions :as tab-actions]
            [hyperopen.views.account-info.tab-registry :as tab-registry]
            [hyperopen.views.account-info.vm :as account-info-vm]
            [hyperopen.views.account-info.tabs.balances :as balances-tab]))

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

(def lazy-tab-loading-state loading-skeleton/lazy-tab-loading-state)

(def lazy-tab-error-state loading-skeleton/lazy-tab-error-state)

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
(def balance-row balances-tab/balance-row)
(def balance-table-header balances-tab/balance-table-header)
(def balances-tab-content balances-tab/balances-tab-content)

(defn placeholder-tab-content
  ([tab-name]
   (placeholder-tab-content tab-name tab-registry/tab-labels))
  ([tab-name labels]
   [:div.p-4
    [:div.text-lg.font-medium.mb-4 (get labels tab-name (name tab-name))]
    (empty-state (str (get labels tab-name (name tab-name)) " coming soon"))]))

(def ^:private eager-tab-renderers
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
                                      :read-only-message read-only-message}))})

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
                           (get eager-tab-renderers (:selected-tab view-model))
                           (account-tab-modules/resolved-tab-renderer (:selected-tab view-model)))]
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
         ;; Voice copy loses to caller overrides (e.g. portfolio's
         ;; :funding-history -> "Interest" context rename).
         tab-label-overrides* (merge (voice/account-tab-overrides state)
                                     tab-label-overrides)
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
                 error]} view-model
         available-tabs* (tab-registry/available-tabs-for extra-tabs* tab-order tab-label-overrides*)
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
         selected-tab-renderer (or selected-extra-renderer
                                   (get eager-tab-renderers selected-tab*)
                                   (account-tab-modules/resolved-tab-renderer selected-tab*))
         lazy-tab-error (when (and (nil? selected-extra-renderer)
                                   (account-tab-modules/lazy-tab? selected-tab*))
                          (account-tab-modules/tab-error state selected-tab*))
         lazy-tab-pending? (and (nil? selected-extra-renderer)
                                (account-tab-modules/lazy-tab? selected-tab*)
                                (nil? selected-tab-renderer)
                                (nil? lazy-tab-error))
         ;; The pending/failed states name the tab, so they need the label after
         ;; route overrides (portfolio renames :funding-history to "Interest")
         ;; but without the row-count suffix the tab strip appends.
         selected-tab-plain-label (get (tab-registry/tab-labels-for extra-tabs* tab-label-overrides*)
                                       selected-tab*)
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
                                   :tab-label-overrides tab-label-overrides*
                                   :tab-order tab-order})
      [:div {:class ["flex-1" "min-h-0" "min-w-0" "overflow-hidden"]}
       (cond
         lazy-tab-error
         (lazy-tab-error-state
          {:tab-label selected-tab-plain-label
           :message lazy-tab-error
           ;; Re-selecting the tab is the retry: select-account-info-tab always
           ;; re-emits the module-load effect, and mark-account-tab-module-loading
           ;; clears the stored error. It also rewrites the already-selected tab
           ;; and pushes the same URL on the trade route; both are harmless.
           :retry-actions [[:actions/select-account-info-tab selected-tab*]]})

         (and (nil? selected-extra-renderer) error)
         (error-state error)

         lazy-tab-pending?
         (lazy-tab-loading-state {:tab-label selected-tab-plain-label})

         :else
         (tab-content (assoc view-model :selected-tab selected-tab*)
                      extra-renderers))]])))

(defn account-info-view
  ([state]
   (account-info-panel state))
  ([state options]
   (account-info-panel state options)))
