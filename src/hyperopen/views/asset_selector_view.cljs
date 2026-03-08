(ns hyperopen.views.asset-selector-view
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.query :as query]
            [hyperopen.utils.formatting :as fmt]
            [replicant.dom :as r]))

;; Asset selector dropdown component

(defn tooltip [content & [position]]
  (let [pos (or position "top")]
    [:div.relative.group
     [:div (first content)]
     [:div {:class (into ["absolute" "opacity-0" "group-hover:opacity-100" "transition-opacity" "duration-200" "pointer-events-none" "z-50"]
                         (case pos
                           "top" ["bottom-full" "left-1/2" "transform" "-translate-x-1/2" "mb-2"]
                           "bottom" ["top-full" "left-1/2" "transform" "-translate-x-1/2" "mt-2"]
                           "left" ["right-full" "top-1/2" "transform" "-translate-y-1/2" "mr-2"]
                           "right" ["left-full" "top-1/2" "transform" "-translate-y-1/2" "ml-2"]))
             :style {:min-width "max-content"}}
      [:div.bg-gray-800.text-white.text-xs.rounded.py-1.px-2.whitespace-nowrap
       (second content)
       [:div {:class (into ["absolute" "w-0" "h-0" "border-4" "border-transparent"]
                           (case pos
                             "top" ["top-full" "border-t-gray-800"]
                             "bottom" ["bottom-full" "border-b-gray-800"]
                             "left" ["left-full" "border-l-gray-800"]
                             "right" ["right-full" "border-r-gray-800"]))}]]]]))

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

(defn search-controls [search-term strict? favorites-only?]
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
      :value search-term
      :on {:input [[:actions/update-asset-search [:event.target/value]]]}}]
    [:div.absolute.inset-y-0.right-0.flex.items-center.pr-3
     [:svg.w-4.h-4.text-gray-400 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
      [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "m21 21-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"}]]]]
   [:div.flex.items-center.gap-2
    (toggle-button "Strict" strict? [[:actions/toggle-asset-selector-strict]])
    (segmented-control favorites-only?)]] )

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
   (tab-button "Crypto" (= active-tab :crypto) :crypto)
   (tab-button "Tradfi" (= active-tab :tradfi) :tradfi)
   (tab-button "HIP-3" (= active-tab :hip3) :hip3)])

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

(defn sort-controls [sort-by sort-direction]
  [:div {:class ["grid" "grid-cols-12" "gap-2" "items-center" "px-2" "h-6"
                 "text-xs" "text-gray-400" "bg-base-100"]}
   [:div.col-span-3 (sort-button "Symbol" (= sort-by :name) sort-direction :name)]
   [:div.col-span-2.text-left (sort-button "Last Price" (= sort-by :price) sort-direction :price)]
   [:div.col-span-2.text-left (sort-button "24H Change" (= sort-by :change) sort-direction :change)]
   [:div.col-span-1.text-left (sort-button "8H Funding" (= sort-by :funding) sort-direction :funding)]
   [:div.col-span-2.text-left (sort-button "Volume" (= sort-by :volume) sort-direction :volume)]
   [:div.col-span-2.text-left (sort-button "Open Interest" (= sort-by :openInterest) sort-direction :openInterest)]])

(defn favorite-button [favorite? market-key]
  [:button.w-3.h-3.text-gray-400.hover:text-yellow-400.transition-colors
   {:on {:click [[:actions/toggle-asset-favorite market-key]]}}
   [:svg.w-3.h-3 {:viewBox "0 0 24 24"
                 :fill (if favorite? "currentColor" "none")
                 :stroke "currentColor"
                 :stroke-width 1.5}
    [:path {:stroke-linecap "round" :stroke-linejoin "round"
            :d "M11.48 3.499a.75.75 0 011.04 0l2.162 2.162 3.03.44a.75.75 0 01.416 1.279l-2.192 2.136.517 3.018a.75.75 0 01-1.088.79L12 13.347l-2.715 1.425a.75.75 0 01-1.088-.79l.517-3.018-2.192-2.136a.75.75 0 01.416-1.279l3.03-.44 2.162-2.162z"}]]])

(defn format-or-dash [value formatter]
  (or (formatter value) "—"))

(defn asset-list-item [asset selected? highlighted? favorites _missing-icons _loaded-icons]
  (let [{:keys [key coin symbol mark markRaw volume24h change24h change24hPct openInterest fundingRate
                market-type dex maxLeverage]} asset
        safe-change (when (some? change24h) (fmt/safe-number change24h))
        safe-change-pct (when (some? change24hPct) (fmt/safe-number change24hPct))
        safe-funding-rate (when (some? fundingRate) (fmt/safe-number fundingRate))
        change-available? (and (number? safe-change)
                               (number? safe-change-pct)
                               (not (js/isNaN safe-change))
                               (not (js/isNaN safe-change-pct)))
        is-positive (and change-available?
                         (>= safe-change 0))
        change-color (if is-positive "text-success" "text-error")
        funding-available? (and (number? safe-funding-rate)
                                (not (js/isNaN safe-funding-rate)))
        funding-positive (and funding-available?
                              (>= safe-funding-rate 0))
        funding-color (if funding-positive "text-success" "text-error")
        is-spot (= market-type :spot)
        favorite? (contains? favorites key)
        row-highlight-classes (cond-> []
                                highlighted? (into ["bg-base-200/70"])
                                selected? (into ["bg-base-200"]))]
    [:div.grid.grid-cols-12.gap-2.items-center.px-2.h-6.box-border.cursor-pointer.bg-base-100.hover:bg-base-200.transition-colors
     {:class row-highlight-classes
      :on {:click [[:actions/select-asset asset]]}}
     ;; Symbol column
     [:div.col-span-3.flex.items-center.space-x-1.5.min-w-0
      (favorite-button favorite? key)
      [:div.flex.items-center.space-x-1.5.min-w-0.overflow-hidden
       [:div.text-sm.truncate.whitespace-nowrap symbol]
       (when is-spot
         (chip "SPOT" ["bg-gray-500/20" "text-gray-200" "border-gray-500/30" "shrink-0"]))
       (when dex
         (chip dex ["bg-emerald-500/20" "text-emerald-300" "border-emerald-500/30" "shrink-0"]))
       (when (and maxLeverage (> maxLeverage 0))
         (chip (str maxLeverage "x") ["bg-primary/20" "text-primary" "border-primary/30" "shrink-0"]))]]
     ;; Last Price
     [:div.col-span-2.text-left.text-sm.text-gray-400.num
      (or (fmt/format-trade-price mark markRaw) "—")]
     ;; 24H Change
     [:div.col-span-2.text-left
      (if change-available?
        [:div {:class [change-color "text-sm" "num"]}
         (str (if is-positive "+" "") (or (fmt/format-trade-price-delta safe-change) "0.00")
              " (" (fmt/safe-to-fixed safe-change-pct 2) "%)")]
        [:div.text-sm.text-gray-400.num "—"])]
     ;; 8H Funding
     [:div.col-span-1.text-left
      (if is-spot
        [:div.text-sm.text-gray-400.num "—"]
        (if funding-available?
          (tooltip
            [[:div {:class [funding-color "text-sm" "cursor-help" "num" "text-left"]
                    :style {:min-width "max-content"}}
              (str (if funding-positive "+" "") (fmt/safe-to-fixed (* safe-funding-rate 100) 4) "%")]
             (str "Annualized: " (fmt/format-percentage (fmt/annualized-funding-rate (* safe-funding-rate 100)) 2))]
            "bottom")
          [:div.text-sm.text-gray-400.num "—"]))]
     ;; Volume
     [:div.col-span-2.text-left.text-sm.num (format-or-dash volume24h fmt/format-large-currency)]
     ;; Open Interest
     [:div.col-span-2.text-left.text-sm.num
      (if is-spot
        "—"
        (format-or-dash openInterest fmt/format-large-currency))]]))

(defn- market-key-present?
  [market-key assets]
  (some (fn [asset]
          (= market-key (:key asset)))
        assets))

(defn- effective-highlighted-market-key
  [assets selected-market-key highlighted-market-key]
  (cond
    (and (string? highlighted-market-key)
         (market-key-present? highlighted-market-key assets))
    highlighted-market-key

    (and (string? selected-market-key)
         (market-key-present? selected-market-key assets))
    selected-market-key

    :else
    (:key (first assets))))

(defn- navigate-shortcut-icon []
  [:svg {:width 16
         :height 9.454545454545455
         :fill "none"
         :stroke "currentColor"
         :focusable "false"
         :aria-hidden "true"
         :viewBox "0 0 22 13"}
   [:path {:d "M3.81809 12.6989L-8.88109e-05 8.88068L0.656161 8.22443L3.34934 10.9261V1.77273L0.656161 4.47443L-8.88109e-05 3.81818L3.81809 -1.43051e-06L7.63628 3.81818L6.98855 4.47443L4.28684 1.77273V10.9261L6.98855 8.22443L7.63628 8.88068L3.81809 12.6989ZM18.432 6.76691H17.748C18.132 5.91491 18.528 5.26691 18.9 4.84691H10.152V3.99491H18.9C18.516 3.57491 18.132 2.92691 17.748 2.08691H18.432C19.272 3.07091 20.16 3.79091 21.072 4.25891V4.59491C20.16 5.06291 19.272 5.78291 18.432 6.76691ZM12.792 11.4469C11.952 10.4629 11.064 9.74291 10.152 9.27491V8.93891C11.064 8.47091 11.952 7.75091 12.792 6.76691H13.476C13.092 7.60691 12.708 8.25491 12.324 8.67491H21.072V9.52691H12.324C12.696 9.94691 13.092 10.5949 13.476 11.4469H12.792Z"
           :fill "currentColor"}]])

(defn- shortcut-keycap [content]
  [:div {:class ["rounded"
                 "text-xs"
                 "leading-none"
                 "text-white"
                 "whitespace-nowrap"]
         :style {:background "rgb(39, 48, 53)"
                 :border-radius "5px"
                 :padding "2px 4px"}}
   content])

(defn- shortcut-item [key-content label]
  [:div {:class ["flex" "items-center" "gap-2" "whitespace-nowrap"]}
   (shortcut-keycap key-content)
   [:div {:class ["text-xs" "text-gray-400"]}
    label]])

(defn- selector-shortcut-footer []
  [:div {:class ["flex"
                 "items-center"
                 "gap-4"
                 "mt-4"
                 "overflow-x-auto"
                 "bg-base-100"
                 "px-4"
                 "py-0.5"
                 "scrollbar-hide"]}
   (shortcut-item "⌘K" "Open")
   (shortcut-item (navigate-shortcut-icon) "Navigate")
   (shortcut-item "Enter" "Select")
   (shortcut-item "⌘S" "Favorite")
   (shortcut-item "Esc" "Close")])

(declare processed-assets
         asset-list)

(def ^:private desktop-breakpoint-px
  1024)

(defn- viewport-width-px []
  (let [width (some-> js/globalThis .-innerWidth)]
    (if (number? width)
      width
      desktop-breakpoint-px)))

(defn- desktop-selector-layout? [desktop?]
  (if (boolean? desktop?)
    desktop?
    (>= (viewport-width-px) desktop-breakpoint-px)))

(def ^:private selector-tabs
  [["All" :all]
   ["Perps" :perps]
   ["Spot" :spot]
   ["Crypto" :crypto]
   ["Tradfi" :tradfi]
   ["HIP-3" :hip3]])

(defn- mobile-tab-button [label active? tab-key]
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

(defn- mobile-tab-row [active-tab]
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

(defn- sort-chevron [direction]
  [:svg {:class ["h-3" "w-3"]
         :fill "none"
         :stroke "currentColor"
         :viewBox "0 0 24 24"}
   (if (= direction :asc)
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M5 15l7-7 7 7"}]
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}])])

(defn- mobile-sort-header-cell
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

(defn- mobile-sort-header [sort-by sort-direction]
  [:div {:class ["grid"
                 "grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)_minmax(0,0.95fr)]"
                 "gap-3"
                 "border-b"
                 "border-base-300/80"
                 "px-4"
                 "py-3"]}
   (mobile-sort-header-cell "Symbol" nil (= sort-by :name) sort-direction [[:actions/update-asset-selector-sort :name]] :left)
   (mobile-sort-header-cell "Volume" "Open Interest" (= sort-by :volume) sort-direction [[:actions/update-asset-selector-sort :volume]] :left)
   (mobile-sort-header-cell "Last Price" "24h Change" (= sort-by :price) sort-direction [[:actions/update-asset-selector-sort :price]] :right)])

(defn- mobile-favorite-button [favorite? market-key]
  [:button {:class ["mt-0.5" "shrink-0" "text-gray-400" "hover:text-yellow-400" "transition-colors"]
            :on {:click [[:actions/toggle-asset-favorite market-key]]}}
   [:svg {:class ["h-4" "w-4"]
          :viewBox "0 0 24 24"
          :fill (if favorite? "currentColor" "none")
          :stroke "currentColor"
          :stroke-width 1.5}
    [:path {:stroke-linecap "round" :stroke-linejoin "round"
            :d "M11.48 3.499a.75.75 0 011.04 0l2.162 2.162 3.03.44a.75.75 0 01.416 1.279l-2.192 2.136.517 3.018a.75.75 0 01-1.088.79L12 13.347l-2.715 1.425a.75.75 0 01-1.088-.79l.517-3.018-2.192-2.136a.75.75 0 01.416-1.279l3.03-.44 2.162-2.162z"}]]])

(defn- mobile-asset-list-item [asset selected? highlighted? favorites]
  (let [{:keys [key symbol mark markRaw volume24h change24h change24hPct openInterest market-type dex maxLeverage]} asset
        safe-change (when (some? change24h) (fmt/safe-number change24h))
        safe-change-pct (when (some? change24hPct) (fmt/safe-number change24hPct))
        change-available? (and (number? safe-change)
                               (number? safe-change-pct)
                               (not (js/isNaN safe-change))
                               (not (js/isNaN safe-change-pct)))
        positive-change? (and change-available? (>= safe-change 0))
        change-color (if positive-change? "text-success" "text-error")
        is-spot (= market-type :spot)
        favorite? (contains? favorites key)
        row-highlight-classes (cond-> []
                                highlighted? (into ["bg-base-200/60"])
                                selected? (into ["bg-base-200"]))]
    [:div {:class (into ["grid"
                         "grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)_minmax(0,0.95fr)]"
                         "gap-3"
                         "items-center"
                         "border-b"
                         "border-base-300/70"
                         "px-4"
                         "py-3"
                         "transition-colors"
                         "cursor-pointer"]
                        row-highlight-classes)
           :on {:click [[:actions/select-asset asset]]}
           :data-role "mobile-asset-selector-row"}
     [:div {:class ["flex" "items-start" "gap-2.5" "min-w-0"]}
      (mobile-favorite-button favorite? key)
      [:div {:class ["min-w-0" "space-y-1"]}
       [:div {:class ["truncate" "text-base" "font-medium" "leading-none" "text-trading-text"]}
        symbol]
       [:div {:class ["flex" "items-center" "gap-1.5" "overflow-hidden"]}
        (when is-spot
          (chip "SPOT" ["bg-gray-500/20" "text-gray-200" "border-gray-500/30" "shrink-0"]))
        (when dex
          (chip dex ["bg-emerald-500/20" "text-emerald-300" "border-emerald-500/30" "shrink-0"]))
        (when (and maxLeverage (> maxLeverage 0))
          (chip (str maxLeverage "x") ["bg-primary/20" "text-primary" "border-primary/30" "shrink-0"]))]]]
     [:div {:class ["min-w-0" "space-y-1" "text-left"]}
      [:div {:class ["num" "text-base" "font-semibold" "leading-none" "text-trading-text"]}
       (format-or-dash volume24h fmt/format-large-currency)]
      [:div {:class ["num" "text-xs" "text-trading-text-secondary"]}
       (if is-spot
         "--"
         (format-or-dash openInterest fmt/format-large-currency))]]
     [:div {:class ["min-w-0" "space-y-1" "text-right"]}
      [:div {:class ["num" "text-base" "font-semibold" "leading-none" "text-trading-text"]}
       (or (fmt/format-trade-price mark markRaw) "—")]
      [:div
       (if change-available?
         {:class [change-color "num" "text-xs"]}
         {:class ["num" "text-xs" "text-trading-text-secondary"]})
       (if change-available?
         (fmt/format-percentage safe-change-pct)
         "—")]]]))

(defn- mobile-asset-list [assets selected-market-key highlighted-market-key favorites]
  (if (empty? assets)
    [:div {:class ["flex-1" "px-4" "py-10" "text-center" "text-gray-400"]}
     [:div "No assets found"]
     [:div {:class ["mt-1" "text-xs"]} "Try adjusting your search"]]
    [:div {:class ["flex-1" "overflow-y-auto" "scrollbar-hide"]}
     (for [asset assets]
       ^{:key (:key asset)}
       (mobile-asset-list-item asset
                               (= selected-market-key (:key asset))
                               (= highlighted-market-key (:key asset))
                               favorites))]))

(defn- desktop-asset-selector-dropdown
  [{:keys [loading? phase search-term strict? favorites-only? active-tab sort-by sort-direction
           markets selected-market-key favorites missing-icons loaded-icons highlighted-market-key
           render-limit scroll-top]}]
  (let [processed-assets-list (processed-assets markets search-term sort-by sort-direction
                                                favorites favorites-only? strict? active-tab)
        ordered-market-keys (mapv :key processed-assets-list)
        highlighted-market-key* (effective-highlighted-market-key
                                  processed-assets-list
                                  selected-market-key
                                  highlighted-market-key)]
    [:div
     {:class ["absolute" "top-full" "left-0" "right-0" "mt-1" "hidden" "bg-base-100"
              "border" "border-base-300" "rounded-none" "spectate-none" "z-[220]" "isolate" "lg:block"]
      :data-role "asset-selector-desktop-dropdown"
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
      (search-controls search-term strict? favorites-only?)
      (tab-row active-tab)
      (sort-controls sort-by sort-direction)
      (asset-list processed-assets-list selected-market-key highlighted-market-key* favorites missing-icons loaded-icons render-limit scroll-top)
      (selector-shortcut-footer)]]))

(defn- mobile-asset-selector-dropdown
  [{:keys [loading? phase search-term strict? favorites-only? active-tab sort-by sort-direction
           markets selected-market-key favorites highlighted-market-key]}]
  (let [processed-assets-list (processed-assets markets search-term sort-by sort-direction
                                                favorites favorites-only? strict? active-tab)
        ordered-market-keys (mapv :key processed-assets-list)
        highlighted-market-key* (effective-highlighted-market-key
                                  processed-assets-list
                                  selected-market-key
                                  highlighted-market-key)]
    [:div {:class ["fixed" "inset-0" "z-[260]" "bg-base-100" "flex" "flex-col" "lg:hidden"]
           :data-role "asset-selector-mobile-overlay"
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
      (search-controls search-term strict? favorites-only?)
      (mobile-tab-row active-tab)
      (mobile-sort-header sort-by sort-direction)
      (mobile-asset-list processed-assets-list selected-market-key highlighted-market-key* favorites)]]))

(defn asset-list [assets selected-market-key highlighted-market-key favorites missing-icons loaded-icons render-limit scroll-top]
  (let [assets* (if (vector? assets) assets (vec assets))
        total (count assets*)]
    (if (zero? total)
      [:div.max-h-64.overflow-y-auto.scrollbar-hide
       [:div.text-center.py-8.text-gray-400
        [:div "No assets found"]
        [:div.text-xs "Try adjusting your search"]]]
      (let [limit (query/normalize-render-limit render-limit total)
            scroll-top* (query/normalize-scroll-top scroll-top)
            {:keys [start-index end-index top-spacer-px bottom-spacer-px]}
            (query/virtual-window limit scroll-top*)
            visible-assets (subvec assets* start-index end-index)
            rows (mapv (fn [asset]
                         ^{:key (:key asset)}
                         (asset-list-item asset
                                          (= selected-market-key (:key asset))
                                          (= highlighted-market-key (:key asset))
                                          favorites
                                          missing-icons
                                          loaded-icons))
                       visible-assets)]
        [:div.max-h-64.overflow-y-auto.scrollbar-hide
         {:style {:overflow-anchor "none"}
          :on {:scroll [[:actions/maybe-increase-asset-selector-render-limit
                         [:event.target/scrollTop]
                         [:event/timeStamp]]]}}
         (into
           [:div {:style {:overflow-anchor "none"}}]
           (concat
             (when (pos? top-spacer-px)
               [[:div {:style {:height (str top-spacer-px "px")}}]])
             rows
             (when (pos? bottom-spacer-px)
               [[:div {:style {:height (str bottom-spacer-px "px")}}]])))]))))

(defn matches-search? [asset search-term strict?]
  (query/matches-search? asset search-term strict?))

(defn tab-match? [asset active-tab strict?]
  (query/tab-match? asset active-tab strict?))

(defn filter-and-sort-assets
  "Apply search filtering and sorting to assets list"
  [assets search-term sort-key sort-direction favorites favorites-only? strict? active-tab]
  (query/filter-and-sort-assets assets
                                search-term
                                sort-key
                                sort-direction
                                favorites
                                favorites-only?
                                strict?
                                active-tab))

(defonce ^:private processed-assets-cache
  (atom nil))

(defn reset-processed-assets-cache! []
  (reset! processed-assets-cache nil))

(defn processed-assets
  [markets search-term sort-key sort-direction favorites favorites-only? strict? active-tab]
  (let [cache @processed-assets-cache
        cache-hit? (and (map? cache)
                        (identical? markets (:markets cache))
                        (identical? favorites (:favorites cache))
                        (= search-term (:search-term cache))
                        (= sort-key (:sort-key cache))
                        (= sort-direction (:sort-direction cache))
                        (= favorites-only? (:favorites-only? cache))
                        (= strict? (:strict? cache))
                        (= active-tab (:active-tab cache)))]
    (if cache-hit?
      (:result cache)
      (let [result (filter-and-sort-assets markets search-term sort-key sort-direction
                                           favorites favorites-only? strict? active-tab)]
        (reset! processed-assets-cache {:markets markets
                                        :favorites favorites
                                        :search-term search-term
                                        :sort-key sort-key
                                        :sort-direction sort-direction
                                        :favorites-only? favorites-only?
                                        :strict? strict?
                                        :active-tab active-tab
                                        :result result})
        result))))

(defn asset-selector-dropdown
  "Asset selector dropdown component
   Props:
   - :visible? - whether the dropdown is shown
   - :markets - list of market data
   - :selected-market-key - currently selected market key
   - :search-term - current search query
   - :sort-by - current sort field (:name, :price, :volume, :change, :openInterest, :funding)
   - :sort-direction - :asc or :desc
   - :favorites - set of favorite market keys
   - :favorites-only? - whether to filter to favorites
   - :strict? - strict search toggle
   - :active-tab - current tab
   - :missing-icons - set of market keys with missing icons
   - :loaded-icons - set of market keys with loaded icons
   - :highlighted-market-key - keyboard navigation highlighted row
   - :render-limit - max rows currently rendered in list
   - :scroll-top - current row-viewport scroll offset
   - :loading? - whether market refresh is in flight
   - :phase - :bootstrap | :full"
  [{:keys [visible? markets selected-market-key search-term sort-by sort-direction
           favorites favorites-only? strict? active-tab missing-icons loaded-icons
           highlighted-market-key render-limit scroll-top loading? phase desktop?]}]
  (when visible?
    (let [desktop-layout? (desktop-selector-layout? desktop?)]
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
                                          :selected-market-key selected-market-key
                                          :favorites favorites
                                          :highlighted-market-key highlighted-market-key}))])))

;; Wrapper component that can be used in active-asset-view
(defn asset-selector-wrapper [props]
  (let [desktop-layout? (desktop-selector-layout? (:desktop? props))]
    [:div.relative
     (asset-selector-dropdown props)
     ;; Only desktop uses an outside-click overlay; mobile uses its own full-screen shell.
     (when (and (:visible? props) desktop-layout?)
       [:div {:class ["fixed" "inset-0" "z-[210]"]
              :on {:click [[:actions/close-asset-dropdown]]}}])]))
