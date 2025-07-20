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

(defn search-input [search-term]
  [:div.relative.mb-4
   [:input.w-full.px-3.py-2.bg-base-200.border.border-base-300.rounded-lg.text-sm.placeholder-gray-400
    {:type "text"
     :placeholder "Search"
     :value search-term
     :on {:input [[:actions/update-asset-search [:event.target/value]]]}}]
   [:div.absolute.inset-y-0.right-0.flex.items-center.pr-3
    [:svg.w-4.h-4.text-gray-400 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "m21 21-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"}]]]])

(defn sort-button [label active? direction sort-field]
  [:button.flex.items-center.space-x-1.px-2.py-1.text-xs.font-medium.rounded.transition-colors
   {:class (if active? ["bg-primary" "text-primary-content"] ["text-gray-400" "hover:text-gray-300"])
    :on {:click [[:actions/update-asset-sort sort-field]]}}
   [:span label]
   (when active?
     [:svg.w-3.h-3 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
      (if (= direction :asc)
        [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M5 15l7-7 7 7"}]
        [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}])])])

(defn sort-controls [sort-by sort-direction]
  [:div.grid.grid-cols-12.gap-3.items-center.px-4.mb-4
   ;; Name column (2 cols)
   [:div.col-span-2.justify-items-center
    (sort-button "Name" (= sort-by :name) sort-direction :name)]
   ;; Price column (2 cols)
   [:div.col-span-2.justify-items-center
    (sort-button "Price" (= sort-by :price) sort-direction :price)]
   ;; Volume column (2 cols)
   [:div.col-span-2.justify-items-center
    (sort-button "Volume" (= sort-by :volume) sort-direction :volume)]
   ;; Change column (2 cols)
   [:div.col-span-2.justify-items-center
    (sort-button "Change" (= sort-by :change) sort-direction :change)]
   ;; Open Interest column (2 cols)
   [:div.col-span-2.justify-items-center
    (sort-button "Open Interest" (= sort-by :openInterest) sort-direction :openInterest)]
   ;; Funding column (2 cols)
   [:div.col-span-2.justify-items-center
    (sort-button "Funding" (= sort-by :funding) sort-direction :funding)]])

(defn asset-list-item [asset selected?]
  (let [{:keys [coin mark volume24h change24h change24hPct openInterest fundingRate info]} asset
        safe-mark (or mark 0)
        safe-volume (or volume24h 0)
        safe-change (or change24h 0)
        safe-change-pct (or change24hPct 0)
        safe-open-interest (or openInterest 0)
        safe-funding-rate (or fundingRate 0)
        max-leverage (get-in info [:maxLeverage] 0)
        is-positive (>= safe-change 0)
        change-color (if is-positive "text-success" "text-error")
        funding-positive (>= safe-funding-rate 0)
        funding-color (if funding-positive "text-success" "text-error")]
    [:div.grid.grid-cols-12.gap-3.items-center.px-4.py-2.cursor-pointer.rounded.hover:bg-base-200.transition-colors
     {:class (when selected? ["bg-primary" "bg-opacity-10" "border" "border-primary"])
      :on {:click [[:actions/select-asset coin]]}}
     ;; Icon + Symbol + Leverage (2 cols)
     [:div.col-span-2.flex.items-center.space-x-2
      [:img.w-5.h-5.rounded-full {:src (str "https://app.hyperliquid.xyz/coins/" coin ".svg") :alt coin}]
      [:div.flex.items-center.space-x-2
       [:div.font-medium.text-sm coin]
               (when (and max-leverage (> max-leverage 0))
          [:span.px-2.py-0.5.text-xs.font-medium.bg-primary.text-primary-content.rounded
           (str max-leverage "x")])]]
     ;; Price (2 cols)
     [:div.col-span-2.text-sm.text-gray-400.text-center (fmt/format-currency safe-mark)]
     ;; Volume (2 cols)
     [:div.col-span-2.text-sm.font-medium.text-center (fmt/format-large-currency safe-volume)]
     ;; Change (2 cols)
     [:div.col-span-2.text-center
      [:div {:class [change-color "text-sm"]}
       (str (if is-positive "+" "") (fmt/safe-to-fixed safe-change 2) " (" (fmt/safe-to-fixed safe-change-pct 2) "%)")]]
     ;; Open Interest (2 cols)
     [:div.col-span-2.text-sm.font-medium.text-center (fmt/format-large-currency safe-open-interest)]
     ;; Funding Rate (2 cols)
     [:div.col-span-2.text-center
      (tooltip 
        [[:div {:class [funding-color "text-sm" "cursor-help"]
                :style {:min-width "max-content"}}
          (str (if funding-positive "+" "") (fmt/safe-to-fixed (* safe-funding-rate 100) 4) "%")]
         (str "Annualized: " (fmt/format-percentage (fmt/annualized-funding-rate (* safe-funding-rate 100)) 2))]
        "bottom")]]))

(defn asset-list [assets selected-asset]
  [:div.max-h-96.overflow-y-auto.space-y-1.scrollbar-hide
   (if (empty? assets)
     [:div.text-center.py-8.text-gray-400
      [:div "No assets found"]
      [:div.text-xs "Try adjusting your search"]]
     (for [asset assets]
       ^{:key (:coin asset)}
       (asset-list-item asset (= selected-asset (:coin asset)))))])





(defn filter-and-sort-assets [assets search-term sort-by sort-direction]
  "Apply search filtering and sorting to assets list"
  (let [;; Filter by search term
        filtered-assets (if (and search-term (not (str/blank? search-term)))
                         (filter #(str/includes? 
                                   (str/lower-case (:coin %))
                                   (str/lower-case search-term))
                                 assets)
                         assets)
        ;; Sort by the selected field using explicit comparison functions
        sorted-assets (case sort-by
                       :name (sort-by :coin filtered-assets)
                       :price (sort #(< (fmt/safe-number (:mark %1)) (fmt/safe-number (:mark %2))) filtered-assets)
                       :volume (sort #(< (fmt/safe-number (:volume24h %1)) (fmt/safe-number (:volume24h %2))) filtered-assets)
                       :change (sort #(< (fmt/safe-number (:change24hPct %1)) (fmt/safe-number (:change24hPct %2))) filtered-assets)
                       :openInterest (sort #(< (fmt/safe-number (:openInterest %1)) (fmt/safe-number (:openInterest %2))) filtered-assets)
                       :funding (sort #(< (fmt/safe-number (:fundingRate %1)) (fmt/safe-number (:fundingRate %2))) filtered-assets)
                       filtered-assets)]
    ;; Apply sort direction
    (if (= sort-direction :desc)
      (reverse sorted-assets)
      sorted-assets)))

(defn asset-selector-dropdown 
  "Asset selector dropdown component
   Props:
   - :visible? - whether the dropdown is shown
   - :assets - list of asset data
   - :selected-asset - currently selected asset
   - :search-term - current search query
   - :sort-by - current sort field (:name, :price, :volume, :change, :openInterest, :funding)
   - :sort-direction - :asc or :desc"
  [{:keys [visible? assets selected-asset search-term sort-by sort-direction]}]
  (when visible?
    (let [processed-assets (filter-and-sort-assets assets search-term sort-by sort-direction)]
      [:div.absolute.top-full.left-0.right-0.mt-2.bg-base-100.border.border-base-300.rounded-lg.shadow-lg.z-50 
       {:style {:transition "opacity 0.3s ease-in-out, transform 0.3s ease-in-out"
                :opacity 1
                :transform "translateY(0)"}
        :replicant/mounting {:style {:opacity 0 :transform "translateY(-8px)"}}
        :replicant/unmounting {:style {:opacity 0 :transform "translateY(-8px)"}}}
       [:div.p-4
        [:div.mb-4
         (search-input search-term)
         [:div.border-b.border-base-300.mt-3.mb-3]]
        (sort-controls sort-by sort-direction)
        (asset-list processed-assets selected-asset)]])))

;; Wrapper component that can be used in active-asset-view
(defn asset-selector-wrapper [props]
  [:div.relative
   (asset-selector-dropdown props)
   ;; Invisible overlay to handle click-outside-to-close
   (when (:visible? props)
     [:div.fixed.inset-0.z-40.transition.duration-700.ease-in-out
      {:on {:click [[:actions/close-asset-dropdown]]}}])]) 