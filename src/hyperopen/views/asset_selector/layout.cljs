(ns hyperopen.views.asset-selector.layout
  (:require [hyperopen.views.asset-selector.controls :as controls]
            [hyperopen.views.asset-selector.outcome-list :as outcome-list]
            [hyperopen.views.asset-selector.processing :as processing]
            [hyperopen.views.asset-selector.rows :as rows]
            [hyperopen.views.asset-selector.runtime :as runtime]))

(defn memoize-last
  [f]
  (let [cache (atom nil)]
    (fn [& args]
      (let [cached @cache]
        (if (and (map? cached)
                 (= args (:args cached)))
          (:result cached)
          (let [result (apply f args)]
            (reset! cache {:args args
                           :result result})
            result))))))

(defn- outcome-market?
  [market]
  (= :outcome (:market-type market)))

(defn- all-tab-events
  "The All tab shows outcome markets as the same event rows the Outcome tab
   does (never one plain row per side): the event model for the outcome markets
   a search left in `assets`, else nil."
  [active-tab assets now-ms opts]
  (when (= :all active-tab)
    (let [events (filterv outcome-market? assets)]
      (when (seq events)
        (outcome-list/list-model events :outcome now-ms opts)))))

(defn- keyboard-keys
  [outcome? outcome-model all-events plain-assets]
  (cond
    outcome? (:ordered-keys outcome-model)
    all-events (into (outcome-list/all-tab-event-keys all-events) (map :key) plain-assets)
    :else (mapv :key plain-assets)))

(defn desktop-asset-selector-dropdown
  [{:keys [loading? phase search-term strict? favorites-only? active-tab sort-by sort-direction
           markets market-by-key selected-market-key favorites missing-icons loaded-icons highlighted-market-key
           render-limit scroll-top outcome-opts]}]
  (let [outcome? (outcome-list/outcome-tab? active-tab)
        processed-assets-list (processing/processed-assets markets market-by-key search-term sort-by sort-direction
                                                           favorites favorites-only? strict?
                                                           (if outcome? :outcome active-tab))
        outcome-model (when outcome?
                        (outcome-list/list-model processed-assets-list active-tab (.now js/Date) outcome-opts))
        suppress-empty-state? (and loading? (empty? markets))
        scroll-reset-key (pr-str [search-term strict? favorites-only? active-tab sort-by sort-direction])
        all-events (when-not outcome?
                     (all-tab-events active-tab processed-assets-list (.now js/Date) outcome-opts))
        plain-assets (if all-events
                       (filterv (complement outcome-market?) processed-assets-list)
                       processed-assets-list)
        ordered-market-keys (keyboard-keys outcome? outcome-model all-events plain-assets)
        highlighted-market-key* (rows/effective-highlighted-market-key
                                  (if (or outcome? all-events)
                                    (mapv (fn [k] {:key k}) ordered-market-keys)
                                    processed-assets-list)
                                  selected-market-key
                                  highlighted-market-key)]
    [:div
     (cond-> {:class (if outcome?
                       ["fixed" "hidden" "flex-col" "bg-base-100" "border-r" "border-b" "border-base-300"
                        "rounded-none" "spectate-none" "z-[220]" "isolate" "lg:flex"]
                       ["absolute" "top-full" "left-0" "right-0" "mt-1" "hidden" "bg-base-100"
                        "border" "border-base-300" "rounded-none" "spectate-none" "z-[220]" "isolate" "lg:block"])
              :data-role "asset-selector-desktop-dropdown"
              :data-layout (if outcome? "outcome-panel" "dropdown")
              :data-parity-id "asset-selector-desktop"
              :on {:keydown [[:actions/handle-asset-selector-shortcut
                              [:event/key]
                              [:event/metaKey]
                              [:event/ctrlKey]
                              ordered-market-keys]]}
              :style {:transition "opacity 0.12s ease-out, transform 0.12s ease-out"
                      :opacity 1
                      :transform "translateY(0)"
                      :background-color "var(--color-base-100)"}
              :replicant/mounting {:style {:opacity 0 :transform "translateY(-4px)"}}
              :replicant/unmounting {:style {:opacity 0 :transform "translateY(-4px)"}}
              ;; every tab: fits the Outcome panel, clears that fit elsewhere
              :replicant/on-render outcome-list/sync-panel-box!})
     (when-not outcome?
       [:div {:class ["absolute" "inset-0" "pointer-events-none"]
              :style {:background-color "var(--color-base-100)"}}
        nil])
     [:div {:class (if outcome?
                     ["relative" "flex" "min-h-0" "flex-1" "flex-col" "bg-base-100" "p-3"]
                     ["relative" "p-4" "bg-base-100"])}
      (if outcome?
        [:div {:class ["shrink-0" "[&>*]:!mb-2"]
               :data-role "outcome-panel-header"}
         (when loading?
           [:div.mb-2.text-xs.text-gray-400
            (if (= phase :full)
              "Loading markets..."
              "Loading markets (bootstrap)...")])
         (controls/search-controls search-term strict? favorites-only? ordered-market-keys)
         (controls/tab-row active-tab)
         (outcome-list/category-chip-row active-tab (:counts outcome-model))
         (outcome-list/sort-and-freshness-row outcome-model)]
        [:div
         (when loading?
           [:div.mb-2.text-xs.text-gray-400
            (if (= phase :full)
              "Loading markets..."
              "Loading markets (bootstrap)...")])
         (controls/search-controls search-term strict? favorites-only? ordered-market-keys)
         (controls/tab-row active-tab)
         (controls/tradfi-subtab-row active-tab)
         (controls/sort-controls sort-by sort-direction active-tab)])
      (if outcome?
        (outcome-list/event-list {:sections (:sections outcome-model)
                                  :selected-market-key selected-market-key
                                  :highlighted-market-key highlighted-market-key*
                                  :favorites favorites
                                  :suppress-empty-state? suppress-empty-state?
                                  :load (:load outcome-model)
                                  :panel? true})
        [:div
         (when all-events
           (outcome-list/all-tab-events-block all-events selected-market-key highlighted-market-key* favorites false))
         (runtime/asset-list plain-assets selected-market-key highlighted-market-key* favorites missing-icons loaded-icons render-limit scroll-top suppress-empty-state? scroll-reset-key)])
      (when-not outcome?
        (rows/selector-shortcut-footer))]]))

(defn mobile-asset-selector-dropdown
  [{:keys [loading? phase search-term strict? favorites-only? active-tab sort-by sort-direction
           markets market-by-key selected-market-key favorites highlighted-market-key outcome-opts]}]
  (let [outcome? (outcome-list/outcome-tab? active-tab)
        processed-assets-list (processing/processed-assets markets market-by-key search-term sort-by sort-direction
                                                           favorites favorites-only? strict?
                                                           (if outcome? :outcome active-tab))
        outcome-model (when outcome?
                        (outcome-list/list-model processed-assets-list active-tab (.now js/Date) outcome-opts))
        suppress-empty-state? (and loading? (empty? markets))
        all-events (when-not outcome?
                     (all-tab-events active-tab processed-assets-list (.now js/Date) outcome-opts))
        plain-assets (if all-events
                       (filterv (complement outcome-market?) processed-assets-list)
                       processed-assets-list)
        ordered-market-keys (keyboard-keys outcome? outcome-model all-events plain-assets)
        highlighted-market-key* (rows/effective-highlighted-market-key
                                  (if (or outcome? all-events)
                                    (mapv (fn [k] {:key k}) ordered-market-keys)
                                    processed-assets-list)
                                  selected-market-key
                                  highlighted-market-key)]
    [:div {:class ["fixed" "inset-0" "z-[260]" "bg-base-100" "flex" "flex-col" "lg:hidden"]
           :data-role "asset-selector-mobile-overlay"
           :data-parity-id "asset-selector-mobile"
           :on {:keydown [[:actions/handle-asset-selector-shortcut
                           [:event/key]
                           [:event/metaKey]
                           [:event/ctrlKey]
                           ordered-market-keys]]}
           :style {:padding-top "max(1rem, env(safe-area-inset-top))"
                   :padding-bottom "max(1rem, env(safe-area-inset-bottom))"}}
     [:div {:class ["app-shell-gutter" "flex" "items-center" "justify-end" "pb-3"]}
      [:button {:type "button"
                :class ["inline-flex"
                        "h-9"
                        "w-9"
                        "items-center"
                        "justify-center"
                        "rounded-lg"
                        "text-gray-400"
                        "transition-colors"
                        "hover:bg-base-200"
                        "hover:text-gray-200"]
                :on {:click [[:actions/close-asset-dropdown]]}
                :aria-label "Close asset selector"
                :data-role "asset-selector-mobile-close"}
       [:svg {:class ["h-5" "w-5"]
              :fill "none"
              :stroke "currentColor"
              :viewBox "0 0 24 24"}
        [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M6 18L18 6M6 6l12 12"}]]]]
     [:div {:class ["app-shell-gutter" "flex" "min-h-0" "flex-1" "flex-col" "gap-4"]}
      (when loading?
        [:div {:class ["text-xs" "text-gray-400"]}
         (if (= phase :full)
           "Loading markets..."
           "Loading markets (bootstrap)...")])
      (controls/search-controls search-term strict? favorites-only? ordered-market-keys)
      (controls/mobile-tab-row active-tab)
      (when outcome?
        (outcome-list/category-chip-row active-tab (:counts outcome-model)))
      (when outcome?
        (outcome-list/sort-and-freshness-row outcome-model))
      (controls/mobile-tradfi-subtab-row active-tab)
      (when-not outcome?
        (controls/mobile-sort-header sort-by sort-direction active-tab))
      (if outcome?
        (outcome-list/event-list {:sections (:sections outcome-model)
                                  :selected-market-key selected-market-key
                                  :highlighted-market-key highlighted-market-key*
                                  :favorites favorites
                                  :suppress-empty-state? suppress-empty-state?
                                  :load (:load outcome-model)
                                  :mobile? true})
        [:div {:class ["min-h-0" "flex-1" "overflow-y-auto"]}
         (when all-events
           (outcome-list/all-tab-events-block all-events selected-market-key highlighted-market-key* favorites true))
         (rows/mobile-asset-list plain-assets selected-market-key highlighted-market-key* favorites suppress-empty-state?)])]]))

(defn asset-selector-dropdown
  [{:keys [visible? markets selected-market-key search-term sort-by sort-direction
           market-by-key favorites favorites-only? strict? active-tab missing-icons loaded-icons
           highlighted-market-key render-limit scroll-top loading? phase desktop? outcome-opts]}]
  (when visible?
    (let [desktop-layout? (controls/desktop-selector-layout? desktop?)]
      [:div
       (if desktop-layout?
         (desktop-asset-selector-dropdown {:loading? loading?
                                           :phase phase
                                           :search-term search-term
                                           :strict? strict?
                                           :favorites-only? favorites-only?
                                           :active-tab active-tab
                                           :sort-by sort-by
                                           :sort-direction sort-direction
                                           :markets markets
                                           :market-by-key market-by-key
                                           :selected-market-key selected-market-key
                                           :favorites favorites
                                           :missing-icons missing-icons
                                           :loaded-icons loaded-icons
                                           :highlighted-market-key highlighted-market-key
                                           :render-limit render-limit
                                           :scroll-top scroll-top
                                           :outcome-opts outcome-opts})
         (mobile-asset-selector-dropdown {:loading? loading?
                                          :phase phase
                                          :search-term search-term
                                          :strict? strict?
                                          :favorites-only? favorites-only?
                                          :active-tab active-tab
                                          :sort-by sort-by
                                          :sort-direction sort-direction
                                          :markets markets
                                          :market-by-key market-by-key
                                          :selected-market-key selected-market-key
                                          :favorites favorites
                                          :highlighted-market-key highlighted-market-key
                                          :outcome-opts outcome-opts}))])))

(def ^:private memoized-asset-selector-wrapper
  (memoize-last
    (fn [props]
      (let [desktop-layout? (controls/desktop-selector-layout? (:desktop? props))]
        [:div.relative
         (asset-selector-dropdown props)
         (when (and (:visible? props) desktop-layout?)
           [:div {:class ["fixed" "inset-0" "z-[210]"]
                  :on {:click [[:actions/close-asset-dropdown]]}}])]))))

(defn asset-selector-wrapper [props]
  (memoized-asset-selector-wrapper props))
