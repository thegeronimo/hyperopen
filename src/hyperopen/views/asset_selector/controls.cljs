(ns hyperopen.views.asset-selector.controls
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.system :as app-system]
            [nexus.registry :as nxr]))

(def ^:private selector-navigation-keys
  #{"ArrowDown" "ArrowUp" "Enter" "Escape"})

(defn- search-input-mount-focus!
  [{:keys [:replicant/life-cycle :replicant/node]}]
  (when (= :replicant.life-cycle/mount life-cycle)
    (platform/queue-microtask!
     (fn []
       (when (and node
                  (.-isConnected node)
                  (fn? (.-focus node)))
         (.focus node)
         (when (fn? (.-select node))
           (.select node)))))))

(defn- search-input-shortcut-handler
  [market-keys]
  (fn [event]
    (let [key (some-> event .-key)
          meta-key? (true? (some-> event .-metaKey))
          ctrl-key? (true? (some-> event .-ctrlKey))
          normalized-key (some-> key str str/lower-case)
          favorite-shortcut? (and (or meta-key? ctrl-key?)
                                  (= normalized-key "s"))
          handled-shortcut? (or (contains? selector-navigation-keys key)
                                favorite-shortcut?)]
      (when handled-shortcut?
        (when (fn? (.-preventDefault event))
          (.preventDefault event))
        (when (fn? (.-stopPropagation event))
          (.stopPropagation event))
        (when app-system/store
          (nxr/dispatch app-system/store
                        nil
                        [[:actions/handle-asset-selector-shortcut
                          key
                          meta-key?
                          ctrl-key?
                          market-keys]]))))))

(def ^:private tooltip-panel-position-classes
  {"top" ["bottom-full" "left-1/2" "transform" "-translate-x-1/2" "mb-2"]
   "bottom" ["top-full" "left-1/2" "transform" "-translate-x-1/2" "mt-2"]
   "left" ["right-full" "top-1/2" "transform" "-translate-y-1/2" "mr-2"]
   "right" ["left-full" "top-1/2" "transform" "-translate-y-1/2" "ml-2"]})

(def ^:private tooltip-arrow-position-classes
  {"top" ["top-full" "border-t-gray-800"]
   "bottom" ["bottom-full" "border-b-gray-800"]
   "left" ["left-full" "border-l-gray-800"]
   "right" ["right-full" "border-r-gray-800"]})

(defn tooltip-position-classes
  [position]
  (or (get tooltip-panel-position-classes position)
      (throw (js/Error. (str "Unsupported tooltip position: " position)))))

(defn tooltip-arrow-classes
  [position]
  (or (get tooltip-arrow-position-classes position)
      (throw (js/Error. (str "Unsupported tooltip position: " position)))))

(defn tooltip [content & [position]]
  (let [pos (or position "top")]
    [:div.relative.group
     [:div (first content)]
     [:div {:class (into ["absolute" "opacity-0" "group-hover:opacity-100" "transition-opacity" "duration-200" "pointer-events-none" "z-50"]
                         (tooltip-position-classes pos))
            :style {:min-width "max-content"}}
      [:div.bg-gray-800.text-white.text-xs.rounded.py-1.px-2.whitespace-nowrap
       (second content)
       [:div {:class (into ["absolute" "w-0" "h-0" "border-4" "border-transparent"]
                           (tooltip-arrow-classes pos))}]]]]))

(defn chip [label & [extra-classes]]
  [:span {:class (into ["px-1.5" "py-0.5" "text-xs" "font-medium" "rounded" "border" "border-base-300" "bg-base-200" "text-gray-300"]
                       (or extra-classes []))}
   label])

(defn toggle-button [label active? on-click]
  [:button.flex.items-center.px-2.py-1.text-xs.font-medium.rounded.transition-colors
   {:class (if active?
             ["bg-primary" "text-primary-content"]
             ["bg-base-200" "text-gray-300" "hover:text-gray-100" "border" "border-base-300"])
    :on {:click on-click}}
   label])

(defn segmented-control [favorites-only?]
  [:div.flex.items-center.bg-base-200.border.border-base-300.rounded-md.overflow-hidden
   [:button.px-2.py-1.text-xs.font-medium
    {:class (if favorites-only?
              ["text-gray-400" "hover:text-gray-200"]
              ["bg-primary" "text-primary-content"])
     :on {:click [[:actions/set-asset-selector-favorites-only false]]}}
    "All"]
   [:button.px-2.py-1.text-xs.font-medium
    {:class (if favorites-only?
              ["bg-primary" "text-primary-content"]
              ["text-gray-400" "hover:text-gray-200"])
     :on {:click [[:actions/set-asset-selector-favorites-only true]]}}
    "Favs"]])

(defn search-controls
  ([search-term strict? favorites-only?]
   (search-controls search-term strict? favorites-only? nil))
  ([search-term strict? favorites-only? market-keys]
  [:div.flex.items-center.gap-2.mb-4
   [:div.relative.flex-1
    [:input
     {:class ["asset-selector-search-input"
              "w-full"
              "pr-9"
              "text-sm"
              "transition-colors"
              "duration-200"
              "focus:outline-none"
              "focus:ring-0"]
      :type "text"
      :placeholder "Search"
      :aria-label "Search assets"
      :replicant/on-render search-input-mount-focus!
      :value search-term
      :on {:input [[:actions/update-asset-search [:event.target/value]]]
           :keydown (search-input-shortcut-handler market-keys)}}]
    [:div.absolute.inset-y-0.right-0.flex.items-center.pr-3
     [:svg.w-4.h-4.text-gray-400 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
      [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "m21 21-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"}]]]]
   [:div.flex.items-center.gap-2
    (toggle-button "Strict" strict? [[:actions/toggle-asset-selector-strict]])
    (segmented-control favorites-only?)]]))

(defn tab-button [label active? tab-key]
  [:button.px-2.py-1.text-xs.font-medium.rounded-md.transition-colors
   {:class (if active?
             ["bg-base-200" "text-gray-100"]
             ["text-gray-400" "hover:text-gray-200"])
    :on {:click [[:actions/set-asset-selector-tab tab-key]]}}
   label])

(defn tab-row [active-tab]
  [:div.flex.items-center.gap-2.mb-4
   (tab-button "All" (= active-tab :all) :all)
   (tab-button "Perps" (= active-tab :perps) :perps)
   (tab-button "Spot" (= active-tab :spot) :spot)
   (tab-button "Outcome" (contains? #{:outcome :outcome-15m :outcome-1d} active-tab) :outcome)
   (tab-button "Crypto" (= active-tab :crypto) :crypto)
   (tab-button "Tradfi" (= active-tab :tradfi) :tradfi)
   (tab-button "HIP-3" (= active-tab :hip3) :hip3)])

(defn outcome-subtab-row [active-tab]
  (when (contains? #{:outcome :outcome-15m :outcome-1d} active-tab)
    [:div.flex.items-center.gap-4.mb-3
     (tab-button "All" (= active-tab :outcome) :outcome)
     (tab-button "Crypto (15m)" (= active-tab :outcome-15m) :outcome-15m)
     (tab-button "Crypto (1d)" (= active-tab :outcome-1d) :outcome-1d)]))

(defn sort-button [label active? direction sort-field]
  [:button.flex.items-center.space-x-1.text-xs.transition-colors
   {:class (if active? ["text-primary"] ["text-gray-400" "hover:text-gray-300"])
    :on {:click [[:actions/update-asset-selector-sort sort-field]]}}
   [:span label]
   (when active?
     [:svg.w-3.h-3 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
      (if (= direction :asc)
        [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M5 15l7-7 7 7"}]
        [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}])])])

(defn- desktop-sort-grid-classes
  [outcome?]
  (cond-> ["grid" "gap-2" "items-center" "px-2" "h-6" "text-xs" "text-gray-400" "bg-base-100"]
    outcome? (conj "grid-cols-[minmax(0,1fr)_4.75rem_10.5rem_3.5rem_7rem_7rem]")
    (not outcome?) (conj "grid-cols-12")))

(defn- desktop-sort-cell-classes
  [outcome? fallback-classes]
  (if outcome?
    ["min-w-0" "text-left"]
    fallback-classes))

(defn sort-controls
  ([sort-by sort-direction]
   (sort-controls sort-by sort-direction nil))
  ([sort-by sort-direction active-tab]
   (let [outcome? (contains? #{:outcome :outcome-15m :outcome-1d} active-tab)]
     [:div {:class (desktop-sort-grid-classes outcome?)}
      [:div {:class (if outcome?
                      ["min-w-0"]
                      ["col-span-3"])}
       (sort-button (if outcome? "Outcome" "Symbol") (= sort-by :name) sort-direction :name)]
      [:div {:class (desktop-sort-cell-classes outcome? ["col-span-2" "text-left"])}
       (sort-button (if outcome? "% Chance" "Last Price") (= sort-by :price) sort-direction :price)]
      [:div {:class (desktop-sort-cell-classes outcome? ["col-span-2" "text-left"])}
       (sort-button "24H Change" (= sort-by :change) sort-direction :change)]
      [:div {:class (desktop-sort-cell-classes outcome? ["col-span-1" "text-left"])}
       (when-not outcome?
         (sort-button "8H Funding" (= sort-by :funding) sort-direction :funding))]
      [:div {:class (desktop-sort-cell-classes outcome? ["col-span-2" "text-left"])}
       (sort-button "Volume" (= sort-by :volume) sort-direction :volume)]
      [:div {:class (desktop-sort-cell-classes outcome? ["col-span-2" "text-left"])}
       (sort-button "Open Interest" (= sort-by :openInterest) sort-direction :openInterest)]])))

(def ^:private desktop-breakpoint-px
  1024)

(defn viewport-width-px []
  (let [width (some-> js/globalThis .-innerWidth)]
    (if (number? width)
      width
      desktop-breakpoint-px)))

(defn desktop-selector-layout? [desktop?]
  (if (boolean? desktop?)
    desktop?
    (>= (viewport-width-px) desktop-breakpoint-px)))

(def ^:private selector-tabs
  [["All" :all]
   ["Perps" :perps]
   ["Spot" :spot]
   ["Outcome" :outcome]
   ["Crypto" :crypto]
   ["Tradfi" :tradfi]
   ["HIP-3" :hip3]])

(defn mobile-tab-button [label active? tab-key]
  [:button {:class (into ["shrink-0"
                          "border-b-2"
                          "pb-2"
                          "text-sm"
                          "font-medium"
                          "transition-colors"]
                         (if active?
                           ["border-primary" "text-gray-100"]
                           ["border-transparent" "text-gray-400" "hover:text-gray-200"]))
            :on {:click [[:actions/set-asset-selector-tab tab-key]]}}
   label])

(defn mobile-tab-row [active-tab]
  [:div {:class ["-mx-4"
                 "overflow-x-auto"
                 "border-b"
                 "border-base-300/80"
                 "px-4"
                 "scrollbar-hide"]}
   [:div {:class ["flex" "items-center" "gap-5" "whitespace-nowrap"]}
    (for [[label tab-key] selector-tabs]
      ^{:key (name tab-key)}
      (mobile-tab-button label (= active-tab tab-key) tab-key))]])

(defn sort-chevron [direction]
  [:svg {:class ["h-3" "w-3"]
         :fill "none"
         :stroke "currentColor"
         :viewBox "0 0 24 24"}
   (if (= direction :asc)
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M5 15l7-7 7 7"}]
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}])])

(defn mobile-sort-header-cell
  [title subtitle active? direction on-click align]
  [:button {:class (into ["flex" "flex-col" "gap-0.5" "transition-colors"]
                         (concat
                           (if (= align :right)
                             ["items-end" "text-right"]
                             ["items-start" "text-left"])
                           (if active?
                             ["text-gray-100"]
                             ["text-gray-400" "hover:text-gray-200"])))
            :on {:click on-click}}
   [:div {:class ["flex" "items-center" "gap-1" "whitespace-nowrap" "text-xs" "font-medium"]}
    [:span title]
    (when active?
      (sort-chevron direction))]
   [:div {:class ["text-xs" "leading-tight" "text-gray-500"]}
    subtitle]])

(defn mobile-outcome-subtab-row [active-tab]
  (outcome-subtab-row active-tab))

(defn mobile-sort-header
  ([sort-by sort-direction]
   (mobile-sort-header sort-by sort-direction nil))
  ([sort-by sort-direction active-tab]
   (let [outcome? (contains? #{:outcome :outcome-15m :outcome-1d} active-tab)]
     [:div {:class ["grid"
                    "grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)_minmax(0,0.95fr)]"
                    "gap-3"
                    "border-b"
                    "border-base-300/80"
                    "px-4"
                    "py-3"]}
      (mobile-sort-header-cell (if outcome? "Outcome" "Symbol") nil (= sort-by :name) sort-direction [[:actions/update-asset-selector-sort :name]] :left)
      (mobile-sort-header-cell "Volume" "Open Interest" (= sort-by :volume) sort-direction [[:actions/update-asset-selector-sort :volume]] :left)
      (mobile-sort-header-cell (if outcome? "% Chance" "Last Price") "24h Change" (= sort-by :price) sort-direction [[:actions/update-asset-selector-sort :price]] :right)])))
