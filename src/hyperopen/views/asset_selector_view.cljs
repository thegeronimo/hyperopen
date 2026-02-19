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
    [:input.w-full.px-3.py-1.5.bg-base-200.border.border-base-300.rounded-md.text-sm.placeholder-gray-400
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

(defn- parse-cache-order [value]
  (let [parsed (cond
                 (number? value) value
                 (string? value) (js/parseInt value 10)
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed)))
      (js/Math.floor parsed))))

(defn- safe-sort-number [value]
  (let [n (fmt/safe-number value)]
    (if (js/isNaN n) 0 n)))

(defn- sort-token [value]
  (str/lower-case (or (some-> value str str/trim) "")))

(defn- market-primary-sort-rank [sort-key asset]
  (case sort-key
    :name (sort-token (:symbol asset))
    :price (safe-sort-number (:mark asset))
    :volume (safe-sort-number (:volume24h asset))
    :change (safe-sort-number (:change24hPct asset))
    :openInterest (safe-sort-number (:openInterest asset))
    :funding (safe-sort-number (:fundingRate asset))
    (safe-sort-number (:volume24h asset))))

(defn- market-fallback-sort-rank [asset]
  [(or (parse-cache-order (:cache-order asset))
       js/Number.MAX_SAFE_INTEGER)
   (sort-token (:symbol asset))
   (sort-token (:coin asset))
   (sort-token (:key asset))])

(defn- compare-markets [sort-key sort-direction a b]
  (let [primary-cmp (compare (market-primary-sort-rank sort-key a)
                             (market-primary-sort-rank sort-key b))
        directional-primary (if (= :desc sort-direction)
                              (- primary-cmp)
                              primary-cmp)]
    (if (zero? directional-primary)
      (compare (market-fallback-sort-rank a)
               (market-fallback-sort-rank b))
      directional-primary)))

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

(def ^:private asset-list-default-render-limit
  120)

(def ^:private asset-list-row-height-px
  24)

(def ^:private asset-list-viewport-height-px
  256)

(def ^:private asset-list-overscan-rows
  6)

(defn- normalize-render-limit [render-limit total]
  (let [candidate (parse-cache-order render-limit)
        default-limit (min total asset-list-default-render-limit)]
    (if (and (number? candidate)
             (not (js/isNaN candidate)))
      (-> candidate
          (max 1)
          (min total))
      default-limit)))

(defn- normalize-scroll-top [scroll-top]
  (max 0 (or (parse-cache-order scroll-top) 0)))

(defn- virtual-window [limit scroll-top]
  (let [rows-in-view (-> (/ asset-list-viewport-height-px asset-list-row-height-px)
                         js/Math.ceil
                         int)
        window-size (+ rows-in-view (* 2 asset-list-overscan-rows))
        first-visible-row (-> (/ scroll-top asset-list-row-height-px)
                              js/Math.floor
                              int)
        start-index (-> first-visible-row
                        (- asset-list-overscan-rows)
                        (max 0)
                        (min limit))
        end-index (-> (+ start-index window-size)
                      (min limit)
                      (max start-index))
        top-spacer-px (* start-index asset-list-row-height-px)
        bottom-spacer-px (* (- limit end-index) asset-list-row-height-px)]
    {:start-index start-index
     :end-index end-index
     :top-spacer-px top-spacer-px
     :bottom-spacer-px bottom-spacer-px}))

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

(defn- shortcut-keycap [label]
  [:span {:class ["rounded"
                  "border"
                  "border-base-300"
                  "bg-base-200"
                  "px-1.5"
                  "py-0.5"
                  "text-xs"
                  "font-medium"
                  "text-gray-200"]}
   label])

(defn- shortcut-item [key-label label]
  [:div {:class ["flex" "items-center" "gap-1.5" "text-xs" "text-gray-400"]}
   (shortcut-keycap key-label)
   [:span label]])

(defn- selector-shortcut-footer []
  [:div {:class ["flex"
                 "items-center"
                 "gap-3"
                 "overflow-x-auto"
                 "border-t"
                 "border-base-300"
                 "bg-base-100"
                 "px-4"
                 "py-2"
                 "scrollbar-hide"]}
   (shortcut-item "Cmd/Ctrl+K" "Open")
   (shortcut-item "Up/Down" "Navigate")
   (shortcut-item "Enter" "Select")
   (shortcut-item "Cmd/Ctrl+S" "Favorite")
   (shortcut-item "Esc" "Close")])

(defn asset-list [assets selected-market-key highlighted-market-key favorites missing-icons loaded-icons render-limit scroll-top]
  (let [assets* (if (vector? assets) assets (vec assets))
        total (count assets*)]
    (if (zero? total)
      [:div.max-h-64.overflow-y-auto.scrollbar-hide
       [:div.text-center.py-8.text-gray-400
        [:div "No assets found"]
        [:div.text-xs "Try adjusting your search"]]]
      (let [limit (normalize-render-limit render-limit total)
            scroll-top* (normalize-scroll-top scroll-top)
            {:keys [start-index end-index top-spacer-px bottom-spacer-px]}
            (virtual-window limit scroll-top*)
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
        comparator (fn [a b]
                     (neg? (compare-markets sort-key sort-direction a b)))]
    (vec (sort comparator filtered-assets))))

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
           highlighted-market-key render-limit scroll-top loading? phase]}]
  (when visible?
    (let [processed-assets-list (processed-assets markets search-term sort-by sort-direction
                                                  favorites favorites-only? strict? active-tab)
          ordered-market-keys (mapv :key processed-assets-list)
          highlighted-market-key* (effective-highlighted-market-key
                                    processed-assets-list
                                    selected-market-key
                                    highlighted-market-key)]
      [:div
       {:class ["absolute" "top-full" "left-0" "right-0" "mt-1" "bg-base-100"
                "border" "border-base-300" "rounded-none" "shadow-none" "z-[220]" "isolate"]
        :on {:keydown [[:actions/handle-asset-selector-shortcut
                        [:event/key]
                        [:event/metaKey]
                        [:event/ctrlKey]
                        ordered-market-keys]]}
        :style {:transition "opacity 0.2s ease-in-out, transform 0.2s ease-in-out"
                :opacity 1
                :transform "translateY(0)"
                :background-color "var(--color-base-100)"}
        :replicant/mounting {:style {:opacity 0 :transform "translateY(-8px)"}}
        :replicant/unmounting {:style {:opacity 0 :transform "translateY(-8px)"}}}
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
        (selector-shortcut-footer)]])))

;; Wrapper component that can be used in active-asset-view
(defn asset-selector-wrapper [props]
  [:div.relative
   (asset-selector-dropdown props)
   ;; Invisible overlay to handle click-outside-to-close
   (when (:visible? props)
     [:div {:class ["fixed" "inset-0" "z-[210]" "transition" "duration-700" "ease-in-out"]
            :on {:click [[:actions/close-asset-dropdown]]}}])])
