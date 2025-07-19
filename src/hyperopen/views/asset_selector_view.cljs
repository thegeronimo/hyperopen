(ns hyperopen.views.asset-selector-view)

;; Asset selector dropdown component

(defn safe-to-fixed [value decimals]
  "Safely convert a value to fixed decimal places, defaulting to 0 if not a number"
  (let [num-value (if (and value (number? value)) value 0)]
    (.toFixed num-value decimals)))

(defn search-input [search-term]
  [:div.relative.mb-4
   [:input.w-full.px-3.py-2.bg-base-200.border.border-base-300.rounded-lg.text-sm.placeholder-gray-400
    {:type "text"
     :placeholder "Search assets..."
     :value search-term
     :on {:input [[:actions/update-asset-search]]}}]
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
  (let [{:keys [coin mark volume24h change24h change24hPct openInterest fundingRate]} asset
        safe-mark (or mark 0)
        safe-volume (or volume24h 0)
        safe-change (or change24h 0)
        safe-change-pct (or change24hPct 0)
        safe-open-interest (or openInterest 0)
        safe-funding-rate (or fundingRate 0)
        is-positive (>= safe-change 0)
        change-color (if is-positive "text-success" "text-error")
        funding-positive (>= safe-funding-rate 0)
        funding-color (if funding-positive "text-success" "text-error")]
    [:div.grid.grid-cols-12.gap-3.items-center.px-4.py-2.cursor-pointer.rounded.hover:bg-base-200.transition-colors
     {:class (when selected? ["bg-primary" "bg-opacity-10" "border" "border-primary"])
      :on {:click [[:actions/select-asset coin]]}}
     ;; Icon + Symbol (2 cols)
     [:div.col-span-2.flex.items-center.space-x-2
      [:img.w-5.h-5.rounded-full {:src (str "https://app.hyperliquid.xyz/coins/" coin ".svg") :alt coin}]
      [:div.font-medium.text-sm coin]]
     ;; Price (2 cols)
     [:div.col-span-2.text-sm.text-gray-400.text-center (str "$" (safe-to-fixed safe-mark 2))]
     ;; Volume (2 cols)
     [:div.col-span-2.text-sm.font-medium.text-center (str "$" (safe-to-fixed safe-volume 0))]
     ;; Change (2 cols)
     [:div.col-span-2.text-center
      [:div {:class [change-color "text-sm"]}
       (str (if is-positive "+" "") (safe-to-fixed safe-change 2) " (" (safe-to-fixed safe-change-pct 2) "%)")]]
     ;; Open Interest (2 cols)
     [:div.col-span-2.text-sm.font-medium.text-center (str "$" (safe-to-fixed safe-open-interest 0))]
     ;; Funding Rate (2 cols)
     [:div.col-span-2.text-center
      [:div {:class [funding-color "text-sm"]}
       (str (if funding-positive "+" "") (safe-to-fixed (* safe-funding-rate 100) 4) "%")]]]))

(defn asset-list [assets selected-asset]
  [:div.max-h-96.overflow-y-auto.space-y-1
   (if (empty? assets)
     [:div.text-center.py-8.text-gray-400
      [:div "No assets found"]
      [:div.text-xs "Try adjusting your search"]]
     (for [asset assets]
       ^{:key (:coin asset)}
       (asset-list-item asset (= selected-asset (:coin asset)))))])

(defn close-button []
  [:button.absolute.top-3.right-3.p-1.rounded.hover:bg-base-200.transition-colors
   {:on {:click [[:actions/close-asset-dropdown]]}}
   [:svg.w-4.h-4.text-gray-400 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
    [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M6 18L18 6M6 6l12 12"}]]])

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
    [:div.absolute.top-full.left-0.mt-2.bg-base-100.border.border-base-300.rounded-lg.shadow-lg.z-50 {:style {:width "800px"}}
     (close-button)
     [:div.p-4
      [:div.mb-4
       [:h3.text-lg.font-semibold.mb-2 "Select Asset"]
       (search-input search-term)
       [:div.border-b.border-base-300.mt-3.mb-3]]
      (sort-controls sort-by sort-direction)
      (asset-list assets selected-asset)]]))

;; Wrapper component that can be used in active-asset-view
(defn asset-selector-wrapper [props]
  [:div.relative
   (asset-selector-dropdown props)]) 