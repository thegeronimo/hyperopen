(ns hyperopen.views.asset-selector.layout
  (:require [hyperopen.views.asset-selector.controls :as controls]
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

(defn desktop-asset-selector-dropdown
  [{:keys [loading? phase search-term strict? favorites-only? active-tab sort-by sort-direction
           markets market-by-key selected-market-key favorites missing-icons loaded-icons highlighted-market-key
           render-limit scroll-top]}]
  (let [processed-assets-list (processing/processed-assets markets market-by-key search-term sort-by sort-direction
                                                           favorites favorites-only? strict? active-tab)
        suppress-empty-state? (and loading? (empty? markets))
        scroll-reset-key (pr-str [search-term strict? favorites-only? active-tab sort-by sort-direction])
        ordered-market-keys (mapv :key processed-assets-list)
        highlighted-market-key* (rows/effective-highlighted-market-key
                                  processed-assets-list
                                  selected-market-key
                                  highlighted-market-key)]
    [:div
     {:class ["absolute" "top-full" "left-0" "right-0" "mt-1" "hidden" "bg-base-100"
              "border" "border-base-300" "rounded-none" "spectate-none" "z-[220]" "isolate" "lg:block"]
      :data-role "asset-selector-desktop-dropdown"
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
      :replicant/unmounting {:style {:opacity 0 :transform "translateY(-4px)"}}}
     [:div {:class ["absolute" "inset-0" "pointer-events-none"]
            :style {:background-color "var(--color-base-100)"}}
      nil]
     [:div.relative.p-4.bg-base-100
      (when loading?
        [:div.mb-2.text-xs.text-gray-400
         (if (= phase :full)
           "Loading markets..."
           "Loading markets (bootstrap)...")])
      (controls/search-controls search-term strict? favorites-only? ordered-market-keys)
      (controls/tab-row active-tab)
      (controls/outcome-subtab-row active-tab)
      (controls/sort-controls sort-by sort-direction active-tab)
      (runtime/asset-list processed-assets-list selected-market-key highlighted-market-key* favorites missing-icons loaded-icons render-limit scroll-top suppress-empty-state? scroll-reset-key)
      (rows/selector-shortcut-footer)]]))

(defn mobile-asset-selector-dropdown
  [{:keys [loading? phase search-term strict? favorites-only? active-tab sort-by sort-direction
           markets market-by-key selected-market-key favorites highlighted-market-key]}]
  (let [processed-assets-list (processing/processed-assets markets market-by-key search-term sort-by sort-direction
                                                           favorites favorites-only? strict? active-tab)
        suppress-empty-state? (and loading? (empty? markets))
        ordered-market-keys (mapv :key processed-assets-list)
        highlighted-market-key* (rows/effective-highlighted-market-key
                                  processed-assets-list
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
      (controls/mobile-outcome-subtab-row active-tab)
      (controls/mobile-sort-header sort-by sort-direction active-tab)
      (rows/mobile-asset-list processed-assets-list selected-market-key highlighted-market-key* favorites suppress-empty-state?)]]))

(defn asset-selector-dropdown
  [{:keys [visible? markets selected-market-key search-term sort-by sort-direction
           market-by-key favorites favorites-only? strict? active-tab missing-icons loaded-icons
           highlighted-market-key render-limit scroll-top loading? phase desktop?]}]
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
                                           :scroll-top scroll-top})
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
                                          :highlighted-market-key highlighted-market-key}))])))

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
