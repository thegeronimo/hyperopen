(ns hyperopen.views.asset-selector-view
  (:require [clojure.string :as str]
            [replicant.dom :as r]
            [hyperopen.utils.formatting :as fmt]))

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
  [:span {:class (into ["px-1.5" "py-0.5" "text-[10px]" "font-medium" "rounded" "border" "border-base-300" "bg-base-200" "text-gray-300"]
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
  [:div.flex.items-center.gap-2.mb-3
   [:div.relative.flex-1
    [:input.w-full.px-3.py-2.bg-base-200.border.border-base-300.rounded-lg.text-sm.placeholder-gray-400
     {:type "text"
      :placeholder "Search"
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
  [:div.flex.items-center.gap-2.mb-3
   (tab-button "All" (= active-tab :all) :all)
   (tab-button "Perps" (= active-tab :perps) :perps)
   (tab-button "Spot" (= active-tab :spot) :spot)
   (tab-button "Crypto" (= active-tab :crypto) :crypto)
   (tab-button "Tradfi" (= active-tab :tradfi) :tradfi)
   (tab-button "HIP-3" (= active-tab :hip3) :hip3)])

(defn sort-button [label active? direction sort-field]
  [:button.flex.items-center.space-x-1.text-xs.font-medium.rounded.transition-colors
   {:class (if active? ["text-primary"] ["text-gray-400" "hover:text-gray-300"])
    :on {:click [[:actions/update-asset-selector-sort sort-field]]}}
   [:span label]
   (when active?
     [:svg.w-3.h-3 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
      (if (= direction :asc)
        [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M5 15l7-7 7 7"}]
        [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}])])])

(defn sort-controls [sort-by sort-direction]
  [:div {:class ["grid" "grid-cols-12" "gap-3" "items-center" "px-4" "pb-2"
                 "border-b" "border-base-300" "text-[11px]" "uppercase"
                 "tracking-wide" "text-gray-400"]}
   [:div.col-span-3 (sort-button "Symbol" (= sort-by :name) sort-direction :name)]
   [:div.col-span-2.text-center (sort-button "Last Price" (= sort-by :price) sort-direction :price)]
   [:div.col-span-2.text-center (sort-button "24H Change" (= sort-by :change) sort-direction :change)]
   [:div.col-span-1.text-center (sort-button "8H Funding" (= sort-by :funding) sort-direction :funding)]
   [:div.col-span-2.text-center (sort-button "Volume" (= sort-by :volume) sort-direction :volume)]
   [:div.col-span-2.text-center (sort-button "Open Interest" (= sort-by :openInterest) sort-direction :openInterest)]])

(defn favorite-button [favorite? market-key]
  [:button.w-4.h-4.text-gray-400.hover:text-yellow-400.transition-colors
   {:on {:click [[:actions/toggle-asset-favorite market-key]]}}
   [:svg.w-4.h-4 {:viewBox "0 0 24 24"
                 :fill (if favorite? "currentColor" "none")
                 :stroke "currentColor"
                 :stroke-width 1.5}
    [:path {:stroke-linecap "round" :stroke-linejoin "round"
            :d "M11.48 3.499a.75.75 0 011.04 0l2.162 2.162 3.03.44a.75.75 0 01.416 1.279l-2.192 2.136.517 3.018a.75.75 0 01-1.088.79L12 13.347l-2.715 1.425a.75.75 0 01-1.088-.79l.517-3.018-2.192-2.136a.75.75 0 01.416-1.279l3.03-.44 2.162-2.162z"}]]])

(defn format-or-dash [value formatter]
  (or (formatter value) "—"))

(defn asset-list-item [asset selected? favorites]
  (let [{:keys [key coin symbol base mark volume24h change24h change24hPct openInterest fundingRate
                market-type dex maxLeverage]} asset
        safe-change (or change24h 0)
        safe-change-pct (or change24hPct 0)
        safe-funding-rate (or fundingRate 0)
        is-positive (>= safe-change 0)
        change-color (if is-positive "text-success" "text-error")
        funding-positive (>= safe-funding-rate 0)
        funding-color (if funding-positive "text-success" "text-error")
        is-spot (= market-type :spot)
        favorite? (contains? favorites key)
        icon-name (or base coin)]
    [:div.grid.grid-cols-12.gap-3.items-center.px-4.py-2.cursor-pointer.rounded.hover:bg-base-200.transition-colors
     {:class (when selected? ["bg-primary" "bg-opacity-10" "border" "border-primary"])
      :on {:click [[:actions/select-asset asset]]}}
     ;; Symbol column
     [:div.col-span-3.flex.items-center.space-x-2
      (favorite-button favorite? key)
      [:img.w-5.h-5.rounded-full {:src (str "https://app.hyperliquid.xyz/coins/" icon-name ".svg") :alt icon-name}]
      [:div.flex.items-center.space-x-2
       [:div.font-medium.text-sm symbol]
       (when is-spot
         (chip "SPOT" ["bg-base-300" "text-gray-200"]))
       (when dex
         (chip dex ["bg-emerald-500/20" "text-emerald-300" "border-emerald-500/30"]))
       (when (and maxLeverage (> maxLeverage 0))
         (chip (str maxLeverage "x") ["bg-primary" "text-primary-content" "border-primary"]))]]
     ;; Last Price
     [:div.col-span-2.text-sm.text-gray-400.text-center (format-or-dash mark fmt/format-currency)]
     ;; 24H Change
     [:div.col-span-2.text-center
      [:div {:class [change-color "text-sm"]}
       (str (if is-positive "+" "") (fmt/safe-to-fixed safe-change 2)
            " (" (fmt/safe-to-fixed safe-change-pct 2) "%)")]]
     ;; 8H Funding
     [:div.col-span-1.text-center
      (if is-spot
        [:div.text-sm.text-gray-400 "—"]
        (tooltip
          [[:div {:class [funding-color "text-sm" "cursor-help"]
                  :style {:min-width "max-content"}}
            (str (if funding-positive "+" "") (fmt/safe-to-fixed (* safe-funding-rate 100) 4) "%")]
           (str "Annualized: " (fmt/format-percentage (fmt/annualized-funding-rate (* safe-funding-rate 100)) 2))]
          "bottom"))]
     ;; Volume
     [:div.col-span-2.text-sm.font-medium.text-center (format-or-dash volume24h fmt/format-large-currency)]
     ;; Open Interest
     [:div.col-span-2.text-sm.font-medium.text-center
      (if is-spot
        "—"
        (format-or-dash openInterest fmt/format-large-currency))]]))

(defn asset-list [assets selected-market-key favorites]
  [:div.max-h-96.overflow-y-auto.space-y-1.scrollbar-hide
   (if (empty? assets)
     [:div.text-center.py-8.text-gray-400
      [:div "No assets found"]
      [:div.text-xs "Try adjusting your search"]]
     (for [asset assets]
       ^{:key (:key asset)}
       (asset-list-item asset (= selected-market-key (:key asset)) favorites)))])

(defn matches-search? [asset search-term strict?]
  (let [query (str/lower-case (or search-term ""))
        symbol (str/lower-case (or (:symbol asset) ""))
        coin (str/lower-case (or (:coin asset) ""))
        base (str/lower-case (or (:base asset) ""))]
    (if strict?
      (or (str/starts-with? symbol query)
          (str/starts-with? coin query)
          (str/starts-with? base query))
      (or (str/includes? symbol query)
          (str/includes? coin query)
          (str/includes? base query)))))

(defn tab-match? [asset active-tab]
  (case active-tab
    :all true
    :perps (= :perp (:market-type asset))
    :spot (= :spot (:market-type asset))
    :crypto (and (= :perp (:market-type asset)) (= :crypto (:category asset)))
    :tradfi (and (= :perp (:market-type asset)) (= :tradfi (:category asset)))
    :hip3 (and (= :perp (:market-type asset)) (:hip3? asset))
    true))

(defn filter-and-sort-assets
  "Apply search filtering and sorting to assets list"
  [assets search-term sort-key sort-direction favorites favorites-only? strict? active-tab]
  (let [filtered-assets (->> assets
                             (filter #(tab-match? % active-tab))
                             (filter (fn [asset]
                                       (if favorites-only?
                                         (contains? favorites (:key asset))
                                         true)))
                             (filter (fn [asset]
                                       (if (and search-term (not (str/blank? search-term)))
                                         (matches-search? asset search-term strict?)
                                         true))))
        sorted-assets (case sort-key
                        :name (clojure.core/sort-by (comp str/lower-case :symbol) filtered-assets)
                        :price (sort #(< (fmt/safe-number (:mark %1)) (fmt/safe-number (:mark %2))) filtered-assets)
                        :volume (sort #(< (fmt/safe-number (:volume24h %1)) (fmt/safe-number (:volume24h %2))) filtered-assets)
                        :change (sort #(< (fmt/safe-number (:change24hPct %1)) (fmt/safe-number (:change24hPct %2))) filtered-assets)
                        :openInterest (sort #(< (fmt/safe-number (:openInterest %1)) (fmt/safe-number (:openInterest %2))) filtered-assets)
                        :funding (sort #(< (fmt/safe-number (:fundingRate %1)) (fmt/safe-number (:fundingRate %2))) filtered-assets)
                        filtered-assets)]
    (if (= sort-direction :desc)
      (reverse sorted-assets)
      sorted-assets)))

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
   - :active-tab - current tab"
  [{:keys [visible? markets selected-market-key search-term sort-by sort-direction
           favorites favorites-only? strict? active-tab]}]
  (when visible?
    (let [processed-assets (filter-and-sort-assets markets search-term sort-by sort-direction
                                                   favorites favorites-only? strict? active-tab)]
      [:div.absolute.top-full.left-0.right-0.mt-1.bg-base-100.border.border-base-300.rounded-none.shadow-none.z-50
       {:style {:transition "opacity 0.2s ease-in-out, transform 0.2s ease-in-out"
                :opacity 1
                :transform "translateY(0)"}
        :replicant/mounting {:style {:opacity 0 :transform "translateY(-8px)"}}
        :replicant/unmounting {:style {:opacity 0 :transform "translateY(-8px)"}}}
       [:div.p-4
        (search-controls search-term strict? favorites-only?)
        (tab-row active-tab)
        (sort-controls sort-by sort-direction)
        (asset-list processed-assets selected-market-key favorites)]])))

;; Wrapper component that can be used in active-asset-view
(defn asset-selector-wrapper [props]
  [:div.relative
   (asset-selector-dropdown props)
   ;; Invisible overlay to handle click-outside-to-close
   (when (:visible? props)
     [:div.fixed.inset-0.z-40.transition.duration-700.ease-in-out
      {:on {:click [[:actions/close-asset-dropdown]]}}])])
