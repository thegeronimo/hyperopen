(ns hyperopen.views.account-info.tab-actions
  (:require [hyperopen.ui.fonts :as fonts]
            [hyperopen.views.account-info.margin-rec-batch-panel :as margin-rec-batch-panel]
            [hyperopen.views.account-info.tab-registry :as tab-registry]
            [hyperopen.views.account-info.tab-filters :as tab-filters]
            [hyperopen.views.account-info.tabs.balances.location-filter :as location-filter]))

(defn- freshness-cue-text-classes
  [tone]
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

(defn- account-tab-width-px
  [label]
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

(defn- format-px
  [value]
  (let [safe-value (double (or value 0))]
    (str (.toFixed safe-value 3) "px")))

(defn- account-tab-strip-style
  [tabs counts labels selected-tab]
  (let [tab-labels* (mapv #(tab-registry/tab-label % counts labels) tabs)
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

(defn- apply-active-tab-indicator!
  "Pin the sliding indicator to the *actual* rendered active tab by reading its
   offsetLeft/offsetWidth. account-tab-strip-style gives a close first-paint
   estimate, but canvas-measuring the label text alone cannot see inline
   affordances (e.g. the Monte Carlo \"New\" badge), so the estimate drifts left
   for every tab after such an affordance. Measuring real layout is exact and
   immune to any future unmeasured affordance or font change. `animate?` false
   (mount) snaps without a transition; true (tab change) lets the CSS transition
   slide it. The button's offsetParent is the position:relative strip, so
   offsetLeft is already relative to the indicator's containing block."
  [node animate?]
  (when-let [active (and node (.querySelector node ".account-info-tab-button-active"))]
    (let [strip-style (.-style node)
          indicator (.querySelector node ".account-info-tab-indicator")
          left (.-offsetLeft active)
          width (.-offsetWidth active)]
      (when (and indicator (not animate?))
        (.setProperty (.-style indicator) "transition" "none"))
      (.setProperty strip-style "--account-info-tab-indicator-left" (str left "px"))
      (.setProperty strip-style "--account-info-tab-indicator-width" (str width "px"))
      (when (and indicator (not animate?))
        ;; Force a synchronous reflow so the un-animated snap to the exact
        ;; position lands, then hand the transition back to the stylesheet for
        ;; subsequent tab switches. Gating removeProperty on the offsetWidth read
        ;; keeps :advanced DCE from dropping the (otherwise unused) reflow.
        (when (<= 0 (.-offsetWidth indicator))
          (.removeProperty (.-style indicator) "transition"))))))

(defn- account-tab-indicator-on-render
  "Replicant lifecycle hook keeping the active-tab indicator on the real tab."
  [{:keys [:replicant/life-cycle :replicant/node]}]
  (case life-cycle
    :replicant.life-cycle/mount (apply-active-tab-indicator! node false)
    :replicant.life-cycle/update (apply-active-tab-indicator! node true)
    nil))

(defn- funding-history-header-actions
  []
  [:div {:class ["ml-auto" "flex" "items-center" "justify-end" "gap-2" "px-4" "py-2" "md:min-h-12" "md:py-0"]
         :data-role "account-info-tab-actions-shell"}
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
  tab-filters/order-history-status-options)

(def order-history-status-labels
  tab-filters/order-history-status-labels)

(defn- order-history-status-filter-key
  [order-history-state]
  (tab-filters/order-history-status-filter-key order-history-state))

(defn- chevron-caret-icon
  [open?]
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

(def ^:private account-tab-header-shell-classes
  ["border-b"
   "border-base-300"
   "bg-base-200"
   "flex"
   "flex-col"
   "justify-between"
   "md:flex-row"
   "md:items-stretch"
   "md:min-h-12"])

(def ^:private account-tab-strip-viewport-classes
  ["overflow-x-auto"
   "scrollbar-hide"
   "border-b"
   "border-base-300"
   "min-w-0"
   "md:flex"
   "md:flex-1"
   "md:self-stretch"
   "md:items-center"
   "md:min-h-12"
   "md:border-b-0"])

;; The Balances actions (search, location filter, Hide Small Balances) are
;; about 530 px wide. Beside the tab strip they left it about 200 px at
;; 768 px, too narrow to show the selected tab, so below lg they take their
;; own row under the strip, as on phones.
(def ^:private stacked-below-lg-classes
  {"md:flex-row" "lg:flex-row"
   "md:items-stretch" "lg:items-stretch"
   "md:border-b-0" "lg:border-b-0"})

(defn- header-classes
  [classes stacked-below-lg?]
  (if stacked-below-lg?
    (mapv #(get stacked-below-lg-classes % %) classes)
    classes))

(def ^:private account-tab-empty-actions-shell-classes
  ["hidden"
   "md:flex"
   "md:min-h-12"
   "md:items-center"
   "md:justify-end"
   "md:px-4"])

(defn- search-icon
  []
  [:svg {:class ["h-3.5" "w-3.5"]
         :fill "none"
         :stroke "currentColor"
         :viewBox "0 0 24 24"
         :aria-hidden true}
   [:path {:stroke-linecap "round"
           :stroke-linejoin "round"
           :stroke-width "2"
           :d "m21 21-6-6m2-5a7 7 0 1 1-14 0 7 7 0 0 1 14 0z"}]])

(defn- empty-tab-actions-shell
  []
  [:div {:class account-tab-empty-actions-shell-classes
         :data-role "account-info-tab-actions-shell"}])

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

(defn- order-history-header-actions
  [order-history-state]
  (let [filter-open? (boolean (:filter-open? order-history-state))
        status-filter (order-history-status-filter-key order-history-state)
        status-label (get order-history-status-labels status-filter "All")
        coin-search (:coin-search order-history-state "")]
    [:div {:class ["ml-auto" "relative" "flex" "items-center" "justify-end" "gap-2" "px-4" "py-2" "md:min-h-12" "md:py-0"]
           :data-role "account-info-tab-actions-shell"}
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
  tab-filters/positions-direction-filter-options)

(def positions-direction-filter-labels
  tab-filters/positions-direction-filter-labels)

(defn- positions-direction-filter-key
  [positions-state]
  (tab-filters/positions-direction-filter-key positions-state))

(defn- positions-header-actions
  [positions-state freshness-cue]
  (let [filter-open? (boolean (:filter-open? positions-state))
        direction-filter (positions-direction-filter-key positions-state)
        direction-label (get positions-direction-filter-labels direction-filter "All")
        coin-search (:coin-search positions-state "")]
    [:div {:class ["ml-auto" "relative" "flex" "items-center" "justify-end" "gap-2" "px-4" "py-2" "md:min-h-12" "md:py-0"]
           :data-role "account-info-tab-actions-shell"}
     (margin-rec-batch-panel/batch-trigger (:margin-rec positions-state)
                                           (:read-only? positions-state))
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
  tab-filters/open-orders-direction-filter-options)

(def open-orders-direction-filter-labels
  tab-filters/open-orders-direction-filter-labels)

(defn- open-orders-direction-filter-key
  [open-orders-state]
  (tab-filters/open-orders-direction-filter-key open-orders-state))

(defn- balances-header-actions
  ;; Wraps below md (the location filter drops under the search on a phone);
  ;; one row from md up, under the tab strip until lg (`header-classes`).
  [hide-small? coin-search location-filter*]
  [:div {:class ["ml-auto" "relative" "flex" "flex-wrap" "items-center" "justify-end" "gap-x-3" "gap-y-2" "px-4" "py-2" "md:flex-nowrap" "md:min-h-12" "md:py-0"]
         :data-role "account-info-tab-actions-shell"}
   (account-info-coin-search-control :balances coin-search)
   (location-filter/location-filter-control location-filter*)
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

(defn- open-orders-header-actions
  [open-orders-state freshness-cue]
  (let [filter-open? (boolean (:filter-open? open-orders-state))
        direction-filter (open-orders-direction-filter-key open-orders-state)
        direction-label (get open-orders-direction-filter-labels direction-filter "All")
        coin-search (:coin-search open-orders-state "")]
    [:div {:class ["ml-auto" "relative" "flex" "items-center" "justify-end" "gap-2" "px-4" "py-2" "md:min-h-12" "md:py-0"]
           :data-role "account-info-tab-actions-shell"}
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
  tab-filters/trade-history-direction-filter-options)

(def trade-history-direction-filter-labels
  tab-filters/trade-history-direction-filter-labels)

(defn- trade-history-direction-filter-key
  [trade-history-state]
  (tab-filters/trade-history-direction-filter-key trade-history-state))

(defn- trade-history-header-actions
  [trade-history-state]
  (let [filter-open? (boolean (:filter-open? trade-history-state))
        direction-filter (trade-history-direction-filter-key trade-history-state)
        direction-label (get trade-history-direction-filter-labels direction-filter "All")
        coin-search (:coin-search trade-history-state "")]
    [:div {:class ["ml-auto" "relative" "flex" "items-center" "justify-end" "gap-2" "px-4" "py-2" "md:min-h-12" "md:py-0"]
           :data-role "account-info-tab-actions-shell"}
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
                                                                                                                                                                                balances-location-filter
                                                                                                                                                                                tab-click-actions-by-tab
                                                                                                                                                                                tab-label-overrides
                                                                                                                                                                                tab-order]
                                                                                                                                                                         :or {extra-tabs []
                                                                                                                                                                              tab-click-actions-by-tab {}
                                                                                                                                                                              tab-label-overrides {}
                                                                                                                                                                              tab-order []}}]
   (let [tab-labels* (tab-registry/tab-labels-for extra-tabs tab-label-overrides)
         tabs* (tab-registry/available-tabs-for extra-tabs tab-order tab-label-overrides)
         actions-node (case selected-tab
                        :balances
                        (balances-header-actions hide-small? balances-coin-search balances-location-filter)

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

                        nil)]
     [:div {:class (header-classes account-tab-header-shell-classes (= :balances selected-tab))}
      [:div {:class (header-classes account-tab-strip-viewport-classes (= :balances selected-tab))}
       [:div {:class ["account-info-tab-strip"]
              :data-role "account-info-tab-strip"
              :replicant/on-render account-tab-indicator-on-render
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
           (tab-registry/tab-label tab counts tab-labels*)
           (when (= tab :monte-carlo)
             [:span {:class ["account-info-tab-badge"]
                     :data-role "account-info-tab-badge-new"}
              "New"])])]]
      (or actions-node
          (empty-tab-actions-shell))])))
