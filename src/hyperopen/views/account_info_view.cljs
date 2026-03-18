(ns hyperopen.views.account-info-view
  (:require [hyperopen.ui.fonts :as fonts]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.table :as account-table]
            [hyperopen.views.account-info.vm :as account-info-vm]
            [hyperopen.views.account-info.tabs.balances :as balances-tab]
            [hyperopen.views.account-info.tabs.funding-history :as funding-history-tab]
            [hyperopen.views.account-info.tabs.open-orders :as open-orders-tab]
            [hyperopen.views.account-info.tabs.order-history :as order-history-tab]
            [hyperopen.views.account-info.tabs.positions :as positions-tab]
            [hyperopen.views.account-info.tabs.twap :as twap-tab]
            [hyperopen.views.account-info.tabs.trade-history :as trade-history-tab]))

(def ^:private base-tab-definitions
  (array-map
   :balances {:label "Balances"}
   :positions {:label "Positions"}
   :open-orders {:label "Open Orders"}
   :twap {:label "TWAP"}
   :trade-history {:label "Trade History"}
   :funding-history {:label "Funding History"}
   :order-history {:label "Order History"}))

(def available-tabs
  (vec (keys base-tab-definitions)))

(def tab-labels
  (into {}
        (map (fn [[tab {:keys [label]}]]
               [tab label]))
        base-tab-definitions))

(defn- normalize-panel-classes [panel-classes]
  (let [classes* (->> (or panel-classes [])
                      (filter string?)
                      vec)]
    (when (seq classes*)
      classes*)))

(defn- normalize-extra-tab [{:keys [id label] :as tab}]
  (when (and (keyword? id)
             (string? label)
             (seq label))
    {:id id
     :label label
     :content (:content tab)
     :render (:render tab)
     :panel-classes (normalize-panel-classes (:panel-classes tab))
     :panel-style (when (map? (:panel-style tab))
                    (:panel-style tab))}))

(defn- normalized-extra-tabs [extra-tabs]
  (->> (or extra-tabs [])
       (keep normalize-extra-tab)
       vec))

(defn- apply-tab-label-overrides [tab-definitions label-overrides]
  (reduce-kv (fn [acc tab label]
               (if (and (keyword? tab)
                        (string? label)
                        (seq label)
                        (contains? acc tab))
                 (assoc-in acc [tab :label] label)
                 acc))
             tab-definitions
             (or label-overrides {})))

(defn- merged-tab-definitions [extra-tabs label-overrides]
  (let [extra-tabs* (normalized-extra-tabs extra-tabs)
        extra-tab-ids (set (map :id extra-tabs*))
        base-tab-pairs (remove (fn [[tab _]]
                                 (contains? extra-tab-ids tab))
                               base-tab-definitions)]
    (apply-tab-label-overrides
     (into (array-map)
           (concat (map (fn [{:keys [id label]}]
                          [id {:label label}])
                        extra-tabs*)
                   base-tab-pairs))
     label-overrides)))

(defn- ordered-tab-ids [tab-definitions tab-order]
  (let [tabs* (vec (keys tab-definitions))
        ordered-prefix (->> (or tab-order [])
                            (filter #(contains? tab-definitions %))
                            distinct
                            vec)
        ordered-set (set ordered-prefix)]
    (into ordered-prefix
          (remove ordered-set tabs*))))

(defn- available-tabs-for [extra-tabs tab-order label-overrides]
  (ordered-tab-ids (merged-tab-definitions extra-tabs label-overrides)
                   tab-order))

(defn- tab-labels-for [extra-tabs label-overrides]
  (into {}
        (map (fn [[tab {:keys [label]}]]
               [tab label]))
        (merged-tab-definitions extra-tabs label-overrides)))

(defn tab-label
  ([tab counts]
   (tab-label tab counts tab-labels))
  ([tab counts labels]
   (let [base (get labels tab (name tab))
         count (get counts tab)]
     (cond
       (and (contains? #{:positions :open-orders :twap} tab)
            (number? count)
            (pos? count))
       (str base " (" count ")")

       (and (contains? #{:positions :open-orders :twap} tab)
            (number? count))
       base

       (and (= tab :balances) (number? count))
       (str base " (" count ")")

       :else base))))

(defn- freshness-cue-text-classes [tone]
  (case tone
    :success ["text-xs" "font-medium" "text-success" "tracking-wide"]
    :warning ["text-xs" "font-medium" "text-warning" "tracking-wide"]
    ["text-xs" "font-medium" "text-base-content/70" "tracking-wide"]))

(def ^:private account-tab-horizontal-padding-px
  10)

(def ^:private account-tab-fallback-char-width-px
  6.4)

(def ^:private account-tab-measure-context
  (delay
    (when (and (exists? js/document)
               (some? js/document))
      (let [canvas (.createElement js/document "canvas")]
        (.getContext canvas "2d")))))

(defn- account-tab-width-px [label]
  (let [context @account-tab-measure-context
        label-width (if context
                      (do
                        ;; Match the compact 12px tab label metrics used by the tab strip.
                        (set! (.-font context) (fonts/canvas-font 12))
                        (-> context
                            (.measureText (or label ""))
                            .-width))
                      (* account-tab-fallback-char-width-px
                         (count (or label ""))))]
    (+ label-width (* 2 account-tab-horizontal-padding-px))))

(defn- format-px [value]
  (let [safe-value (double (or value 0))]
    (str (.toFixed safe-value 3) "px")))

(defn- account-tab-strip-style [tabs counts labels selected-tab]
  (let [tab-labels* (mapv #(tab-label % counts labels) tabs)
        widths (mapv account-tab-width-px tab-labels*)
        active-index (or (first (keep-indexed (fn [idx tab]
                                                (when (= tab selected-tab)
                                                  idx))
                                              tabs))
                         0)
        left (reduce + 0 (take active-index widths))
        width (or (nth widths active-index nil) 0)]
    {:--account-info-tab-indicator-left (format-px left)
     :--account-info-tab-indicator-width (format-px width)}))

(defn- funding-history-header-actions []
  [:div {:class ["ml-auto" "flex" "items-center" "justify-end" "gap-2" "px-4" "py-2"]}
   [:button {:class ["btn" "btn-xs" "btn-spectate" "font-normal" "text-trading-text" "hover:bg-base-100" "hover:text-trading-text"]
             :on {:click [[:actions/toggle-funding-history-filter-open]]}}
    "Filter"]
   [:button {:class ["btn" "btn-xs" "btn-spectate" "font-normal" "text-trading-text" "hover:bg-base-100" "hover:text-trading-text"]
             :on {:click [[:actions/view-all-funding-history]]}}
    "View All"]
   [:button {:class ["btn" "btn-xs" "btn-spectate" "font-normal" "text-trading-green" "hover:bg-base-100" "hover:text-trading-green"]
             :on {:click [[:actions/export-funding-history-csv]]}}
    "Export as CSV"]])

(def order-history-status-options
  order-history-tab/order-history-status-options)

(def order-history-status-labels
  order-history-tab/order-history-status-labels)

(defn- order-history-status-filter-key [order-history-state]
  (order-history-tab/order-history-status-filter-key order-history-state))

(defn- chevron-caret-icon [open?]
  [:svg {:class (into ["ml-1" "h-3" "w-3" "shrink-0" "opacity-70" "transition-transform"]
                      (if open?
                        ["rotate-180"]
                        ["rotate-0"]))
         :viewBox "0 0 12 12"
         :aria-hidden true}
   [:path {:d "M3 4.5L6 7.5L9 4.5"
           :fill "none"
           :stroke "currentColor"
           :stroke-width "1.5"
           :stroke-linecap "round"
           :stroke-linejoin "round"}]])

(def ^:private filter-trigger-button-classes
  ["btn"
   "btn-xs"
   "btn-spectate"
   "font-normal"
   "text-trading-text"
   "hover:bg-base-100"
   "hover:text-trading-text"
   "focus:outline-none"
   "focus:ring-0"
   "focus:ring-offset-0"
   "focus:shadow-none"
   "focus-visible:outline-none"
   "focus-visible:ring-0"
   "focus-visible:ring-offset-0"
   "focus-visible:spectate-none"])

(def ^:private coin-search-input-classes
  ["asset-selector-search-input"
   "h-8"
   "w-32"
   "pr-2"
   "pl-8"
   "text-xs"
   "transition-colors"
   "duration-200"
   "focus:outline-none"
   "focus:ring-0"])

(defn- search-icon []
  [:svg {:class ["h-3.5" "w-3.5"]
         :fill "none"
         :stroke "currentColor"
         :viewBox "0 0 24 24"
         :aria-hidden true}
   [:path {:stroke-linecap "round"
           :stroke-linejoin "round"
           :stroke-width "2"
           :d "m21 21-6-6m2-5a7 7 0 1 1-14 0 7 7 0 0 1 14 0z"}]])

(defn- account-info-coin-search-control
  [tab search-value]
  [:label {:class ["relative" "inline-flex" "items-center"]}
   [:span {:class ["sr-only"]} "Search coins"]
   [:span {:class ["pointer-events-none" "absolute" "left-2.5" "text-trading-text-secondary"]}
    (search-icon)]
   [:input {:class coin-search-input-classes
            :type "search"
            :placeholder "Coins..."
            :aria-label "Search coins"
            :autocomplete "off"
            :spellcheck false
            :value (or search-value "")
            :on {:input [[:actions/set-account-info-coin-search tab [:event.target/value]]]}}]])

(defn- order-history-header-actions [order-history-state]
  (let [filter-open? (boolean (:filter-open? order-history-state))
        status-filter (order-history-status-filter-key order-history-state)
        status-label (get order-history-status-labels status-filter "All")
        coin-search (:coin-search order-history-state "")]
    [:div {:class ["ml-auto" "relative" "flex" "items-center" "justify-end" "gap-2" "px-4" "py-2"]}
     (account-info-coin-search-control :order-history coin-search)
     [:button {:class filter-trigger-button-classes
               :style {:--btn-focus-scale "1"}
               :on {:click [[:actions/toggle-order-history-filter-open]]}}
      status-label
      (chevron-caret-icon filter-open?)]
     (when filter-open?
       [:div {:class ["absolute" "right-4" "top-full" "z-20" "mt-1" "w-32" "overflow-hidden" "rounded-md" "border" "border-base-300" "bg-base-100" "spectate-lg"]}
        (for [[option-key option-label] order-history-status-options]
          ^{:key (name option-key)}
          [:button {:class (into ["flex" "w-full" "items-center" "justify-between" "px-3" "py-2" "text-xs" "transition-colors"]
                                 (if (= status-filter option-key)
                                   ["bg-base-200" "text-trading-text"]
                                   ["text-trading-text-secondary" "hover:bg-base-200" "hover:text-trading-text"]))
                    :on {:click [[:actions/set-order-history-status-filter option-key]]}}
           option-label
           (when (= status-filter option-key)
             [:span {:class ["text-trading-text"]} "*"])])])]))

(def positions-direction-filter-options
  positions-tab/positions-direction-filter-options)

(def positions-direction-filter-labels
  positions-tab/positions-direction-filter-labels)

(defn- positions-direction-filter-key [positions-state]
  (positions-tab/positions-direction-filter-key positions-state))

(defn- positions-header-actions [positions-state freshness-cue]
  (let [filter-open? (boolean (:filter-open? positions-state))
        direction-filter (positions-direction-filter-key positions-state)
        direction-label (get positions-direction-filter-labels direction-filter "All")
        coin-search (:coin-search positions-state "")]
    [:div {:class ["ml-auto" "relative" "flex" "items-center" "justify-end" "gap-2" "px-4" "py-2"]}
     (when (map? freshness-cue)
       [:div {:class ["px-1" "py-1"]
              :data-role "account-tab-freshness-cue"}
        [:span {:class (freshness-cue-text-classes (:tone freshness-cue))}
         (:text freshness-cue)]])
     (account-info-coin-search-control :positions coin-search)
     [:button {:class filter-trigger-button-classes
               :style {:--btn-focus-scale "1"}
               :on {:click [[:actions/toggle-positions-direction-filter-open]]}}
      direction-label
      (chevron-caret-icon filter-open?)]
     (when filter-open?
       [:div {:class ["absolute" "right-4" "top-full" "z-20" "mt-1" "w-32" "overflow-hidden" "rounded-md" "border" "border-base-300" "bg-base-100" "spectate-lg"]}
        (for [[option-key option-label] positions-direction-filter-options]
          ^{:key (name option-key)}
          [:button {:class (into ["flex" "w-full" "items-center" "justify-between" "px-3" "py-2" "text-xs" "transition-colors"]
                                 (if (= direction-filter option-key)
                                   ["bg-base-200" "text-trading-text"]
                                   ["text-trading-text-secondary" "hover:bg-base-200" "hover:text-trading-text"]))
                    :on {:click [[:actions/set-positions-direction-filter option-key]]}}
           option-label
           (when (= direction-filter option-key)
             [:span {:class ["text-trading-text"]} "*"])])])]))

(def open-orders-direction-filter-options
  open-orders-tab/open-orders-direction-filter-options)

(def open-orders-direction-filter-labels
  open-orders-tab/open-orders-direction-filter-labels)

(defn- open-orders-direction-filter-key [open-orders-state]
  (open-orders-tab/open-orders-direction-filter-key open-orders-state))

(defn- balances-header-actions [hide-small? coin-search]
  [:div {:class ["ml-auto" "relative" "flex" "items-center" "justify-end" "gap-3" "px-4" "py-2"]}
   (account-info-coin-search-control :balances coin-search)
   [:div {:class ["flex" "items-center" "space-x-2"]}
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
     "Hide Small Balances"]]])

(defn- open-orders-header-actions [open-orders-state freshness-cue]
  (let [filter-open? (boolean (:filter-open? open-orders-state))
        direction-filter (open-orders-direction-filter-key open-orders-state)
        direction-label (get open-orders-direction-filter-labels direction-filter "All")
        coin-search (:coin-search open-orders-state "")]
    [:div {:class ["ml-auto" "relative" "flex" "items-center" "justify-end" "gap-2" "px-4" "py-2"]}
     (when (map? freshness-cue)
       [:div {:class ["px-1" "py-1"]
              :data-role "account-tab-freshness-cue"}
        [:span {:class (freshness-cue-text-classes (:tone freshness-cue))}
         (:text freshness-cue)]])
     (account-info-coin-search-control :open-orders coin-search)
     [:button {:class filter-trigger-button-classes
               :style {:--btn-focus-scale "1"}
               :on {:click [[:actions/toggle-open-orders-direction-filter-open]]}}
      direction-label
      (chevron-caret-icon filter-open?)]
     (when filter-open?
       [:div {:class ["absolute" "right-4" "top-full" "z-20" "mt-1" "w-32" "overflow-hidden" "rounded-md" "border" "border-base-300" "bg-base-100" "spectate-lg"]}
        (for [[option-key option-label] open-orders-direction-filter-options]
          ^{:key (name option-key)}
          [:button {:class (into ["flex" "w-full" "items-center" "justify-between" "px-3" "py-2" "text-xs" "transition-colors"]
                                 (if (= direction-filter option-key)
                                   ["bg-base-200" "text-trading-text"]
                                   ["text-trading-text-secondary" "hover:bg-base-200" "hover:text-trading-text"]))
                    :on {:click [[:actions/set-open-orders-direction-filter option-key]]}}
           option-label
           (when (= direction-filter option-key)
             [:span {:class ["text-trading-text"]} "*"])])])]))

(def trade-history-direction-filter-options
  trade-history-tab/trade-history-direction-filter-options)

(def trade-history-direction-filter-labels
  trade-history-tab/trade-history-direction-filter-labels)

(defn- trade-history-direction-filter-key [trade-history-state]
  (trade-history-tab/trade-history-direction-filter-key trade-history-state))

(defn- trade-history-header-actions [trade-history-state]
  (let [filter-open? (boolean (:filter-open? trade-history-state))
        direction-filter (trade-history-direction-filter-key trade-history-state)
        direction-label (get trade-history-direction-filter-labels direction-filter "All")
        coin-search (:coin-search trade-history-state "")]
    [:div {:class ["ml-auto" "relative" "flex" "items-center" "justify-end" "gap-2" "px-4" "py-2"]}
     (account-info-coin-search-control :trade-history coin-search)
     [:button {:class filter-trigger-button-classes
               :style {:--btn-focus-scale "1"}
               :on {:click [[:actions/toggle-trade-history-direction-filter-open]]}}
      direction-label
      (chevron-caret-icon filter-open?)]
     (when filter-open?
       [:div {:class ["absolute" "right-4" "top-full" "z-20" "mt-1" "w-32" "overflow-hidden" "rounded-md" "border" "border-base-300" "bg-base-100" "spectate-lg"]}
        (for [[option-key option-label] trade-history-direction-filter-options]
          ^{:key (name option-key)}
          [:button {:class (into ["flex" "w-full" "items-center" "justify-between" "px-3" "py-2" "text-xs" "transition-colors"]
                                 (if (= direction-filter option-key)
                                   ["bg-base-200" "text-trading-text"]
                                   ["text-trading-text-secondary" "hover:bg-base-200" "hover:text-trading-text"]))
                    :on {:click [[:actions/set-trade-history-direction-filter option-key]]}}
           option-label
           (when (= direction-filter option-key)
             [:span {:class ["text-trading-text"]} "*"])])])]))

(defn tab-navigation
  ([selected-tab counts hide-small? funding-history-state]
   (tab-navigation selected-tab counts hide-small? funding-history-state {} {} {} {} nil "" {}))
  ([selected-tab counts hide-small? funding-history-state order-history-state]
   (tab-navigation selected-tab counts hide-small? funding-history-state {} order-history-state {} {} nil "" {}))
  ([selected-tab counts hide-small? funding-history-state order-history-state freshness-cues]
   (tab-navigation selected-tab counts hide-small? funding-history-state {} order-history-state {} {} freshness-cues "" {}))
  ([selected-tab counts hide-small? funding-history-state order-history-state open-orders-state freshness-cues]
   (tab-navigation selected-tab counts hide-small? funding-history-state {} order-history-state {} open-orders-state freshness-cues "" {}))
  ([selected-tab counts hide-small? funding-history-state trade-history-state order-history-state open-orders-state freshness-cues]
   (tab-navigation selected-tab counts hide-small? funding-history-state trade-history-state order-history-state {} open-orders-state freshness-cues "" {}))
  ([selected-tab counts hide-small? _funding-history-state trade-history-state order-history-state positions-state open-orders-state freshness-cues]
   (tab-navigation selected-tab counts hide-small? _funding-history-state trade-history-state order-history-state positions-state open-orders-state freshness-cues "" {}))
  ([selected-tab counts hide-small? _funding-history-state trade-history-state order-history-state positions-state open-orders-state freshness-cues balances-coin-search]
   (tab-navigation selected-tab counts hide-small? _funding-history-state trade-history-state order-history-state positions-state open-orders-state freshness-cues balances-coin-search {}))
  ([selected-tab counts hide-small? _funding-history-state trade-history-state order-history-state positions-state open-orders-state freshness-cues balances-coin-search {:keys [extra-tabs
                                                                                                                                                                                tab-click-actions-by-tab
                                                                                                                                                                                tab-label-overrides
                                                                                                                                                                                tab-order]
                                                                                                                                                                         :or {extra-tabs []
                                                                                                                                                                              tab-click-actions-by-tab {}
                                                                                                                                                                              tab-label-overrides {}
                                                                                                                                                                              tab-order []}}]
   (let [tab-labels* (tab-labels-for extra-tabs tab-label-overrides)
         tabs* (available-tabs-for extra-tabs tab-order tab-label-overrides)]
     [:div {:class ["border-b" "border-base-300" "bg-base-200" "flex" "flex-col" "justify-between" "md:flex-row" "md:items-center" "md:justify-between"]}
      [:div {:class ["overflow-x-auto" "scrollbar-hide" "border-b" "border-base-300" "md:flex" "md:self-stretch" "md:items-end" "md:border-b-0"]}
       [:div {:class ["account-info-tab-strip"]
              :data-role "account-info-tab-strip"
              :style (account-tab-strip-style tabs* counts tab-labels* selected-tab)}
        [:div {:class ["account-info-tab-indicator"]
               :data-role "account-info-tab-indicator"
               :aria-hidden true}]
        (for [tab tabs*]
          [:button {:key (name tab)
                    :type "button"
                    :aria-pressed (= selected-tab tab)
                    :data-role (str "account-info-tab-" (name tab))
                    :class (into ["account-info-tab-button"]
                                 (if (= selected-tab tab)
                                   ["account-info-tab-button-active"]
                                   ["account-info-tab-button-inactive"]))
                    :on {:click (or (get tab-click-actions-by-tab tab)
                                    [[:actions/select-account-info-tab tab]])}}
           (tab-label tab counts tab-labels*)])]]
      (case selected-tab
        :balances
        (balances-header-actions hide-small? balances-coin-search)

        :funding-history
        (funding-history-header-actions)

        :order-history
        (order-history-header-actions order-history-state)

        :trade-history
        (trade-history-header-actions trade-history-state)

        :positions
        (positions-header-actions positions-state
                                  (get freshness-cues :positions))

        :open-orders
        (open-orders-header-actions open-orders-state
                                    (get freshness-cues :open-orders))

        nil)])))

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
   (placeholder-tab-content tab-name tab-labels))
  ([tab-name labels]
   [:div.p-4
    [:div.text-lg.font-medium.mb-4 (get labels tab-name (name tab-name))]
    (empty-state (str (get labels tab-name (name tab-name)) " coming soon"))]))

(def ^:private tab-renderers
  {:balances (fn [{:keys [balance-rows hide-small? balances-sort balances-coin-search mobile-expanded-card]}]
               (balances-tab-content balance-rows
                                     hide-small?
                                     balances-sort
                                     balances-coin-search
                                     mobile-expanded-card))
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
          (normalized-extra-tabs extra-tabs)))

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
                  tab-click-actions-by-tab
                  tab-label-overrides
                  tab-order]
           :or {extra-tabs []
                tab-click-actions-by-tab {}
                tab-label-overrides {}
                tab-order []}}]
   (let [view-model (account-info-vm/account-info-vm state)
         extra-tabs* (normalized-extra-tabs extra-tabs)
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
         available-tabs* (available-tabs-for extra-tabs* tab-order tab-label-overrides)
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
                                 (if (= selected-tab* :balances)
                                   ["h-96" "lg:h-[29rem]"]
                                   ["h-96"]))
         panel-shell-style (:panel-style selected-extra-tab)]
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
      (tab-navigation selected-tab*
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
      [:div {:class ["flex-1" "min-h-0" "overflow-hidden"]}
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
