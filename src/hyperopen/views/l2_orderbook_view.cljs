(ns hyperopen.views.l2-orderbook-view
  (:require [clojure.string :as str]
            [hyperopen.domain.market.instrument :as instrument]
            [hyperopen.orderbook.price-aggregation :as price-agg]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.websocket.orderbook-policy :as orderbook-policy]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.websocket-freshness :as ws-freshness]))

;; Utility functions for formatting
(defn parse-number [value]
  (cond
    (number? value) value
    (string? value) (let [n (js/parseFloat value)]
                      (when-not (js/isNaN n) n))
    :else nil))

(def orderbook-tabs
  #{:orderbook :trades})

(def ^:private max-render-levels-per-side orderbook-policy/default-max-render-levels-per-side)
(def ^:private orderbook-columns-class "grid-cols-[1fr_2fr_2fr]")
(def ^:private mobile-split-columns-class "grid-cols-[minmax(0,1.2fr)_minmax(0,0.8fr)_minmax(0,0.8fr)_minmax(0,1.2fr)]")
(def ^:private header-neutral-text-class "text-[rgb(148,158,156)]")
(def ^:private body-neutral-text-class "text-[rgb(210,218,215)]")
(def ^:private ask-depth-bar-class "bg-[rgba(237,112,136,0.15)]")
(def ^:private bid-depth-bar-class "bg-[rgba(31,166,125,0.15)]")
(def ^:private ask-price-text-class "text-[rgb(237,112,136)]")
(def ^:private bid-price-text-class "text-[rgb(31,166,125)]")
(def ^:private orderbook-tab-indicator-class "bg-[rgb(80,210,193)]")
(def ^:private desktop-breakpoint-px 1024)
(def ^:private depth-bar-transition-classes
  ["transition-all"
   "duration-300"
   "ease-[cubic-bezier(0.68,-0.6,0.32,1.6)]"])

(defn normalize-orderbook-tab [tab]
  (let [tab* (cond
               (keyword? tab) tab
               (string? tab) (keyword tab)
               :else :orderbook)]
    (if (contains? orderbook-tabs tab*) tab* :orderbook)))

(defn- viewport-width-px [viewport-width]
  (let [width (or (when (number? viewport-width) viewport-width)
                  (some-> js/globalThis .-innerWidth))]
    (if (number? width)
      width
      desktop-breakpoint-px)))

(defn- desktop-orderbook-layout? [layout]
  (if (boolean? (:desktop-layout? layout))
    (:desktop-layout? layout)
    (>= (viewport-width-px (:viewport-width layout))
        desktop-breakpoint-px)))

(defn format-price
  ([price] (orderbook-policy/format-price price))
  ([price raw] (orderbook-policy/format-price price raw)))

(defn format-percent [value decimals]
  (orderbook-policy/format-percent value decimals))

(defn format-total [total & {:keys [decimals] :or {decimals 0}}]
  (orderbook-policy/format-total total :decimals decimals))

(defn calculate-spread [best-bid best-ask]
  (orderbook-policy/calculate-spread best-bid best-ask))

(defn calculate-cumulative-totals [orders]
  (orderbook-policy/calculate-cumulative-totals orders))

(defn normalize-size-unit [size-unit]
  (if (= size-unit :quote) :quote :base))

(defn base-symbol-from-coin [coin]
  (instrument/base-symbol-from-value coin))

(defn quote-symbol-from-coin [coin]
  (or (instrument/quote-symbol-from-value coin) "USDC"))

(defn resolve-base-symbol [coin market]
  (instrument/resolve-base-symbol coin market "Asset"))

(defn resolve-quote-symbol [coin market]
  (instrument/resolve-quote-symbol coin market "USDC"))

(defn infer-market-type [coin market]
  (instrument/infer-market-type coin market))

(defn midpoint-price [best-bid best-ask]
  (let [bid (some-> best-bid :px parse-number)
        ask (some-> best-ask :px parse-number)]
    (when (and bid ask (> bid 0) (> ask 0))
      (/ (+ bid ask) 2))))

(defn resolve-reference-price [best-bid best-ask market]
  (or (midpoint-price best-bid best-ask)
      (parse-number (:mark market))
      1))

(defn trade-time->ms [value]
  (when-some [n (parse-number value)]
    (if (< n 1000000000000) (* n 1000) n)))

(defn format-trade-time [value]
  (when-let [time-ms (trade-time->ms value)]
    (fmt/format-local-time-hh-mm-ss time-ms)))

(defn trade-side->price-class [side]
  (case (some-> side str str/upper-case)
    "B" "text-green-400"
    "A" "text-red-400"
    "S" "text-red-400"
    "text-gray-100"))

(defn trade-matches-coin? [trade coin]
  (let [trade-coin (or (:coin trade) (:symbol trade) (:asset trade))]
    (if (seq coin)
      (= trade-coin coin)
      true)))

(defn normalize-trade [trade]
  (let [price-raw (or (:px trade) (:price trade) (:p trade))
        size-raw (or (:sz trade) (:size trade) (:s trade))
        time-raw (or (:time trade) (:t trade) (:ts trade) (:timestamp trade))
        side (or (:side trade) (:dir trade))
        coin (or (:coin trade) (:symbol trade) (:asset trade))]
    {:coin coin
     :price (parse-number price-raw)
     :price-raw price-raw
     :size (or (parse-number size-raw) 0)
     :size-raw size-raw
     :side side
     :time-ms (trade-time->ms time-raw)
     :tid (or (:tid trade) (:id trade))}))

(defn format-trade-size [trade]
  (let [raw-size (:size-raw trade)]
    (if (string? raw-size)
      raw-size
      (or (format-total (:size trade) :decimals 8) "0"))))

(defn recent-trades-for-coin [coin]
  (let [cached-trades (trades/get-recent-trades-for-coin coin)]
    (if (seq cached-trades)
      (take 100 cached-trades)
      (->> (trades/get-recent-trades)
           (filter #(trade-matches-coin? % coin))
           (map normalize-trade)
           (sort-by (fn [trade] (or (:time-ms trade) 0)) >)
           (take 100)))))

(defn order-size-for-unit [order size-unit]
  (orderbook-policy/order-size-for-unit order size-unit))

(defn order-total-for-unit [order size-unit]
  (orderbook-policy/order-total-for-unit order size-unit))

(defn get-max-cumulative-total [orders size-unit]
  (orderbook-policy/get-max-cumulative-total orders size-unit))

(defn format-order-size [order size-unit]
  (orderbook-policy/format-order-size order size-unit))

(defn format-order-total [order size-unit]
  (orderbook-policy/format-order-total order size-unit))

(defn cumulative-bar-width [cum-size max-cum-size]
  (orderbook-policy/cumulative-bar-width cum-size max-cum-size))

(defn precision-dropdown [selected-option price-options dropdown-visible?]
  (let [selected-label (or (:label selected-option) "0.000001")
        selected-mode (:mode selected-option)
        interactive? (> (count price-options) 1)]
    [:div.relative
     [:button.flex.items-center.space-x-2.rounded.px-2.py-1.transition-colors
      (cond-> {:type "button"
               :class (if interactive?
                        ["hover:bg-gray-800" "cursor-pointer"]
                        ["cursor-default"])
               :disabled (not interactive?)}
        interactive?
        (assoc :on {:click [[:actions/toggle-orderbook-price-aggregation-dropdown]]}))
      [:span.text-white.text-sm selected-label]
      [:svg.w-4.h-4.text-gray-400.transition-transform {:fill "none"
                                                        :stroke "currentColor"
                                                        :viewBox "0 0 24 24"
                                                        :class (when dropdown-visible? "rotate-180")}
       [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]]
     (when interactive?
       [:div.absolute.top-full.left-0.mt-1.bg-base-100.border.border-base-300.rounded.spectate-lg.z-30.min-w-24.overflow-hidden
        {:class (if dropdown-visible?
                  ["opacity-100" "scale-y-100" "translate-y-0"]
                  ["opacity-0" "scale-y-95" "-translate-y-2" "pointer-events-none"])
         :style {:transition "all 80ms ease-in-out"}}
        (for [option price-options]
          ^{:key (str "precision-option-" (name (:mode option)))}
          [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-800
           {:class (if (= selected-mode (:mode option))
                     ["text-white" "bg-gray-800"]
                     ["text-gray-300"])
            :on {:click [[:actions/select-orderbook-price-aggregation (:mode option)]]}}
           (:label option)])])]))

(defn size-unit-dropdown [base-symbol quote-symbol size-unit dropdown-visible?]
  (let [selected-symbol (if (= size-unit :quote) quote-symbol base-symbol)]
    [:div.relative
     [:button.flex.items-center.space-x-2.hover:bg-gray-800.rounded.px-2.py-1.transition-colors
      {:type "button"
       :on {:click [[:actions/toggle-orderbook-size-unit-dropdown]]}}
      [:span.text-white.text-sm selected-symbol]
      [:svg.w-4.h-4.text-gray-400.transition-transform {:fill "none"
                                                        :stroke "currentColor"
                                                        :viewBox "0 0 24 24"
                                                        :class (when dropdown-visible? "rotate-180")}
       [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]]
     [:div.absolute.top-full.right-0.mt-1.bg-base-100.border.border-base-300.rounded.spectate-lg.z-20.min-w-20.overflow-hidden
      {:class (if dropdown-visible?
                ["opacity-100" "scale-y-100" "translate-y-0"]
                ["opacity-0" "scale-y-95" "-translate-y-2" "pointer-events-none"])
       :style {:transition "all 80ms ease-in-out"}}
      [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-800
       {:class (if (= size-unit :quote) ["text-white" "bg-gray-800"] ["text-gray-300"])
        :on {:click [[:actions/select-orderbook-size-unit :quote]]}}
       quote-symbol]
      [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-800
       {:class (if (= size-unit :base) ["text-white" "bg-gray-800"] ["text-gray-300"])
        :on {:click [[:actions/select-orderbook-size-unit :base]]}}
	       base-symbol]]]))

(defn- freshness-tone-classes [tone]
  (case tone
    :success ["text-success"]
    :warning ["text-warning"]
    ["text-base-content/70"]))

(defn- freshness-cue-node [{:keys [text tone]}]
  [:span {:class (into ["text-xs" "font-medium" "tracking-wide"]
                       (freshness-tone-classes tone))
          :data-role "orderbook-freshness-cue"}
   text])

;; Header component
(defn orderbook-header [selected-option
                        price-options
                        price-dropdown-visible?
                        base-symbol
                        quote-symbol
                        size-unit
                        size-dropdown-visible?
                        show-freshness-cue?
   freshness-cue]
  [:div.flex.items-center.justify-between.px-3.py-2.bg-base-100.border-b.border-base-300
   (precision-dropdown selected-option price-options price-dropdown-visible?)
   [:div {:class ["flex" "items-center" "gap-3"]}
    (when show-freshness-cue?
      ^{:replicant/key "orderbook-freshness-cue"}
      (freshness-cue-node freshness-cue))
    ^{:replicant/key "orderbook-size-unit"}
    (size-unit-dropdown base-symbol quote-symbol size-unit size-dropdown-visible?)]])

(defn orderbook-tab-button [active-tab tab-id label]
  [:button.flex-1.px-3.py-2.text-sm.font-medium.transition-colors
   {:type "button"
    :data-role (str "orderbook-tab-button-" (name tab-id))
    :class (if (= active-tab tab-id)
             ["text-white"]
             ["text-gray-400" "hover:text-gray-200"])
    :on {:click [[:actions/select-orderbook-tab tab-id]]}}
   label])

(defn orderbook-tabs-row [active-tab]
  [:div {:class ["relative" "flex" "items-center" "bg-base-100" "border-b" "border-base-300"]
         :data-role "orderbook-tabs-row"}
   (orderbook-tab-button active-tab :orderbook "Order Book")
   (orderbook-tab-button active-tab :trades "Trades")
   [:div {:class ["pointer-events-none"
                  "absolute"
                  "bottom-0"
                  "left-0"
                  "h-px"
                  "w-1/2"
                  orderbook-tab-indicator-class]
          :style {:left (if (= active-tab :trades) "50%" "0%")
                  :transition "left 0.3s"}}]])

(defn tab-content-viewport [content]
  [:div {:class ["flex-1" "h-full" "min-h-0" "overflow-hidden" "bg-base-100"]}
   content])

(defn trades-column-headers [base-symbol]
  [:div {:class ["grid" orderbook-columns-class "items-center" "py-2" "pl-2" "pr-2" "bg-base-100" "border-b" "border-base-300"]
         :data-role "trades-column-headers-row"}
   [:div {:class ["text-left"]
          :data-role "trades-price-header-cell"}
    [:span {:class [header-neutral-text-class "text-xs" "num"]} "Price"]]
   [:div {:class ["text-right" "num-right"]
          :data-role "trades-size-header-cell"}
    [:span {:class [header-neutral-text-class "text-xs" "num"]} (str "Size (" base-symbol ")")]]
   [:div {:class ["text-right" "num-right"]
          :data-role "trades-time-header-cell"}
    [:span {:class [header-neutral-text-class "text-xs" "num"]} "Time"]]])

(defn trades-row [trade]
  (let [price-class (trade-side->price-class (:side trade))]
    [:div {:class ["flex" "items-center" "h-6" "relative" "bg-base-100" "text-xs" "border-b" "border-base-300"]}
     [:div {:class ["grid" orderbook-columns-class "w-full" "items-center" "pl-2" "pr-2"]
            :data-role "trades-level-content-row"}
      [:div {:class ["text-left"]
             :data-role "trades-level-price-cell"}
       [:span {:class [price-class "num"]} (or (format-price (:price trade) (:price-raw trade)) "0.00")]]
      [:div {:class ["text-right" "num-right"]
             :data-role "trades-level-size-cell"}
       [:span {:class [body-neutral-text-class "num"]} (format-trade-size trade)]]
      [:div {:class ["text-right" "num-right"]
             :data-role "trades-level-time-cell"}
       [:span {:class [body-neutral-text-class "num"]} (or (format-trade-time (:time-ms trade)) "--:--:--")]]]]))

(defn empty-trades []
  [:div {:class ["flex" "flex-col" "items-center" "justify-center" "p-8" "text-center" "bg-base-100" "rounded-none" "border" "border-base-300" "h-full"]}
   [:h3.text-lg.font-medium.text-gray-300 "No Trades Yet"]
   [:p.text-sm.text-gray-500 "Recent trades will appear here"]])

(defn trades-panel [coin base-symbol]
  (let [recent-trades (recent-trades-for-coin coin)]
    (if (seq recent-trades)
      [:div {:class ["bg-base-100" "border" "border-base-300" "rounded-none" "overflow-hidden" "h-full" "min-h-0" "flex" "flex-col" "num" "num-dense"]}
       (trades-column-headers base-symbol)
       [:div.flex-1.min-h-0.overflow-y-auto.scrollbar-hide
        (for [trade recent-trades]
          ^{:key (str "trade-" coin "-" (:tid trade) "-" (:time-ms trade) "-" (:price-raw trade) "-" (:size-raw trade))}
          (trades-row trade))]]
      (empty-trades))))

(defn- row-price-label [row]
  (or (get-in row [:display :price])
      (format-price (:px row) (:px row))
      "0.00"))

(defn- row-size-label [row size-unit]
  (or (get-in row [:display :size size-unit])
      (format-order-size row size-unit)))

(defn- row-total-label [row size-unit]
  (or (get-in row [:display :total size-unit])
      (format-order-total row size-unit)))

(defn- row-bar-width [row size-unit]
  (or (get-in row [:display :bar-width size-unit])
      (str (or (cumulative-bar-width (order-total-for-unit row size-unit)
                                     (get-in row [:max-total-by-unit size-unit]))
               0)
           "%")))

(defn- depth-bar-classes
  [bar-color animate?]
  (cond-> ["h-full" bar-color]
    animate? (into depth-bar-transition-classes)))

;; Component for individual order row
(defn order-row
  ([row size-unit]
   (order-row row size-unit true))
  ([row size-unit animate?]
   (let [is-ask? (= :ask (:side row))
         bar-color (if is-ask? ask-depth-bar-class bid-depth-bar-class)
         price-text-color (if is-ask? ask-price-text-class bid-price-text-class)]
     [:div {:class ["flex" "items-center" "h-[23px]" "relative" "bg-base-100" "text-xs" "orderbook-level-row"]
            :data-role "orderbook-level-row"}
      ;; Size bar background - always positioned from left
      [:div.absolute.inset-0.flex.items-center.justify-start
       [:div {:class (depth-bar-classes bar-color animate?)
              :style {:width (row-bar-width row size-unit)}}]]
      ;; Content
      [:div {:class ["grid" orderbook-columns-class "w-full" "items-center" "pl-2" "pr-2" "relative" "z-10"]
             :data-role "orderbook-level-content-row"}
       [:div {:class ["text-left"]
              :data-role "orderbook-level-price-cell"}
        [:span {:class [price-text-color "num" "orderbook-level-value"]}
         (row-price-label row)]]
       [:div {:class ["text-right" "num-right"]
              :data-role "orderbook-level-size-cell"}
        [:span {:class [body-neutral-text-class "num" "orderbook-level-value"]}
         (row-size-label row size-unit)]]
       [:div {:class ["text-right" "num-right"]
              :data-role "orderbook-level-total-cell"}
        [:span {:class [body-neutral-text-class "num" "orderbook-level-value"]}
         (row-total-label row size-unit)]]]])))

;; Spread component
(defn spread-row [spread]
  [:div {:class ["flex" "items-center" "justify-center" "h-[23px]" "bg-base-100" "border-y" "border-base-300" "text-xs"]}
   [:div {:class ["flex" "items-center" "space-x-3" "text-white" "num" "orderbook-level-value"]}
    [:span "Spread"]
    [:span (or (:absolute-label spread)
               (format-price (:absolute spread)))]
    [:span (or (:percentage-label spread)
               (str (format-percent (:percentage spread) 3) "%"))]]])

;; Column headers
(defn column-headers [size-symbol]
  [:div {:class ["grid" orderbook-columns-class "items-center" "py-2" "pl-2" "pr-2" "bg-base-100" "border-b" "border-base-300"]
         :data-role "orderbook-column-headers-row"}
   [:div {:class ["text-left"]
          :data-role "orderbook-price-header-cell"}
    [:span {:class [header-neutral-text-class "text-xs" "num"]} "Price"]]
   [:div {:class ["text-right" "num-right"]
          :data-role "orderbook-size-header-cell"}
    [:span {:class [header-neutral-text-class "text-xs" "num"]} (str "Size (" size-symbol ")")]]
   [:div {:class ["text-right" "num-right"]
          :data-role "orderbook-total-header-cell"}
    [:span {:class [header-neutral-text-class "text-xs" "num"]} (str "Total (" size-symbol ")")]]])

(defn- mobile-split-column-headers [size-symbol]
  [:div {:class ["grid" mobile-split-columns-class "items-center" "gap-x-2" "px-2" "py-2" "bg-base-100" "border-b" "border-base-300"]
         :data-role "orderbook-mobile-split-headers"}
   [:div {:class ["text-left"]
          :data-role "orderbook-mobile-bid-total-header-cell"}
    [:span {:class [header-neutral-text-class "text-xs" "num"]} (str "Total (" size-symbol ")")]]
   [:div {:class ["text-right" "num-right"]
          :data-role "orderbook-mobile-bid-price-header-cell"}
    [:span {:class [header-neutral-text-class "text-xs" "num"]} "Bid"]]
   [:div {:class ["text-left"]
          :data-role "orderbook-mobile-ask-price-header-cell"}
    [:span {:class [header-neutral-text-class "text-xs" "num"]} "Ask"]]
   [:div {:class ["text-right" "num-right"]
          :data-role "orderbook-mobile-ask-total-header-cell"}
    [:span {:class [header-neutral-text-class "text-xs" "num"]} (str "Total (" size-symbol ")")]]])

(defn- mobile-split-order-row
  ([row size-unit]
   (mobile-split-order-row row size-unit true))
  ([{:keys [bid ask]} size-unit animate?]
   (let [bid-total (when bid (row-total-label bid size-unit))
         ask-total (when ask (row-total-label ask size-unit))
         bid-price (when bid (row-price-label bid))
         ask-price (when ask (row-price-label ask))]
     [:div {:class ["relative" "grid" mobile-split-columns-class "items-center" "gap-x-2" "px-2" "h-5" "bg-base-100" "text-xs" "border-b" "border-base-300/60"]
            :data-role "orderbook-mobile-split-row"}
      [:div {:class ["pointer-events-none" "absolute" "inset-y-0" "left-0" "flex" "w-1/2" "items-center" "justify-end" "pr-1"]}
       (when bid
         [:div {:class (depth-bar-classes bid-depth-bar-class animate?)
                :style {:width (row-bar-width bid size-unit)}}])]
      [:div {:class ["pointer-events-none" "absolute" "inset-y-0" "right-0" "flex" "w-1/2" "items-center" "justify-start" "pl-1"]}
       (when ask
         [:div {:class (depth-bar-classes ask-depth-bar-class animate?)
                :style {:width (row-bar-width ask size-unit)}}])]
      [:div {:class ["relative" "z-10" "text-left"]
             :data-role "orderbook-mobile-bid-total-cell"}
       [:span {:class [body-neutral-text-class "num"]} bid-total]]
      [:div {:class ["relative" "z-10" "text-right" "num-right"]
             :data-role "orderbook-mobile-bid-price-cell"}
       [:span {:class [bid-price-text-class "num"]} bid-price]]
      [:div {:class ["relative" "z-10" "text-left"]
             :data-role "orderbook-mobile-ask-price-cell"}
       [:span {:class [ask-price-text-class "num"]} ask-price]]
      [:div {:class ["relative" "z-10" "text-right" "num-right"]
             :data-role "orderbook-mobile-ask-total-cell"}
       [:span {:class [body-neutral-text-class "num"]} ask-total]]])))

(defn- strip-cumulative-totals [levels]
  (mapv #(dissoc % :cum-size :cum-value) (or levels [])))

(defn- fallback-render-snapshot [orderbook-data visible-branch]
  (orderbook-policy/build-render-snapshot (:bids orderbook-data)
                                          (:asks orderbook-data)
                                          max-render-levels-per-side
                                          {:visible-branch visible-branch}))

(defn- render-branch-keys [visible-branch]
  (case visible-branch
    :mobile [:mobile-pairs]
    :desktop [:desktop-bids :desktop-asks]
    [:desktop-bids :desktop-asks :mobile-pairs]))

(defn- full-render-snapshot? [render visible-branch]
  (and (map? render)
       (every? #(contains? render %)
               (concat (render-branch-keys visible-branch)
                       [:best-bid
                        :best-ask
                        :spread
                        :max-total-by-unit]))))

(defn- legacy-render-snapshot? [render]
  (and (map? render)
       (every? #(contains? render %)
               [:best-bid :best-ask])
       (or (contains? render :display-bids)
           (contains? render :bids-with-totals))
       (or (contains? render :display-asks)
           (contains? render :asks-with-totals))))

(defn- upgrade-legacy-render-snapshot [render visible-branch]
  (let [display-bids (or (:display-bids render)
                         (strip-cumulative-totals (:bids-with-totals render)))
        display-asks (or (:display-asks render)
                         (strip-cumulative-totals (:asks-with-totals render)))
        snapshot (orderbook-policy/build-render-snapshot display-bids
                                                         display-asks
                                                         max-render-levels-per-side
                                                         {:visible-branch visible-branch})]
    (assoc snapshot
           :best-bid (or (:best-bid render) (:best-bid snapshot))
           :best-ask (or (:best-ask render) (:best-ask snapshot)))))

(defn- render-snapshot [orderbook-data visible-branch]
  (let [render (:render orderbook-data)]
    (cond
      (full-render-snapshot? render visible-branch) render
      (legacy-render-snapshot? render) (upgrade-legacy-render-snapshot render visible-branch)
      :else (fallback-render-snapshot orderbook-data visible-branch))))

;; Main order book component
(defn l2-orderbook-panel
  ([coin market orderbook-data orderbook-ui]
   (l2-orderbook-panel coin market orderbook-data orderbook-ui nil))
  ([coin market orderbook-data orderbook-ui websocket-health]
   (l2-orderbook-panel coin market orderbook-data orderbook-ui websocket-health true))
  ([coin market orderbook-data orderbook-ui websocket-health show-freshness-cue?]
   (l2-orderbook-panel coin market orderbook-data orderbook-ui websocket-health show-freshness-cue? nil))
  ([coin market orderbook-data orderbook-ui websocket-health show-freshness-cue? layout]
   (l2-orderbook-panel coin market orderbook-data orderbook-ui websocket-health show-freshness-cue? layout true))
  ([coin market orderbook-data orderbook-ui websocket-health show-freshness-cue? layout animate-orderbook?]
   (let [size-unit (normalize-size-unit (:size-unit orderbook-ui))
         size-unit-dropdown-visible? (boolean (:size-unit-dropdown-visible? orderbook-ui))
         price-dropdown-visible? (boolean (:price-aggregation-dropdown-visible? orderbook-ui))
         aggregation-by-coin (or (:price-aggregation-by-coin orderbook-ui) {})
         selected-mode (get aggregation-by-coin coin :full)
         base-symbol (resolve-base-symbol coin market)
         quote-symbol (resolve-quote-symbol coin market)
         selected-size-symbol (if (= size-unit :quote) quote-symbol base-symbol)
         desktop-layout? (desktop-orderbook-layout? layout)
         visible-branch (if desktop-layout? :desktop :mobile)
         snapshot (render-snapshot orderbook-data visible-branch)
         desktop-asks (:desktop-asks snapshot)
         desktop-bids (:desktop-bids snapshot)
         mobile-pairs (:mobile-pairs snapshot)
         best-bid (:best-bid snapshot)
         best-ask (:best-ask snapshot)
         spread (:spread snapshot)
         reference-price (resolve-reference-price best-bid best-ask market)
         market-type (infer-market-type coin market)
         sz-decimals (:szDecimals market)
         price-options (price-agg/build-options {:market-type market-type
                                                 :sz-decimals sz-decimals
                                                 :reference-price reference-price})
         selected-option (price-agg/option-for-mode price-options selected-mode)
         freshness-cue (when show-freshness-cue?
                         (ws-freshness/surface-cue websocket-health
                                                   {:topic "l2Book"
                                                    :selector {:coin coin}
                                                    :live-prefix "Updated"}))
         depth-dimmed? (boolean (and freshness-cue (:delayed? freshness-cue)))]
     [:div {:class ["bg-base-100" "border" "border-base-300" "rounded-none" "overflow-hidden" "h-full" "flex" "flex-col" "num" "orderbook-panel-aligned"]}
      ;; Header
      (orderbook-header selected-option
                        price-options
                        price-dropdown-visible?
                        base-symbol
                        quote-symbol
                        size-unit
                        size-unit-dropdown-visible?
                        show-freshness-cue?
                        freshness-cue)

      (if desktop-layout?
        [:div {:class ["hidden" "flex-1" "min-h-0" "flex-col" "lg:flex"]
               :data-role "orderbook-desktop-panel"}
         (column-headers selected-size-symbol)
         [:div {:class (cond-> ["flex-1" "min-h-0" "flex" "flex-col"]
                         depth-dimmed? (conj "opacity-90"))
                :data-role "orderbook-depth-body"}
          [:div {:class ["flex-1" "min-h-0" "overflow-hidden" "flex" "flex-col" "gap-0.5" "justify-end"]
                 :data-role "orderbook-asks-pane"}
           (for [ask desktop-asks]
             ^{:key (:row-key ask)}
             (order-row ask size-unit animate-orderbook?))]
          (when spread
            (spread-row spread))
          [:div {:class ["flex-1" "min-h-0" "overflow-hidden" "flex" "flex-col" "gap-0.5"]
                 :data-role "orderbook-bids-pane"}
           (for [bid desktop-bids]
             ^{:key (:row-key bid)}
             (order-row bid size-unit animate-orderbook?))]]]
        [:div {:class (cond-> ["flex" "flex-1" "min-h-0" "flex-col" "lg:hidden"]
                        depth-dimmed? (conj "opacity-90"))
               :data-role "orderbook-mobile-split-panel"}
         (mobile-split-column-headers selected-size-symbol)
         [:div {:class ["flex-1" "min-h-0" "overflow-hidden" "bg-base-100"]
                :data-role "orderbook-mobile-split-body"}
          (for [split-row mobile-pairs]
            ^{:key (:row-key split-row)}
            (mobile-split-order-row split-row size-unit animate-orderbook?))]])])))

;; Empty state
(defn empty-orderbook []
  [:div {:class ["flex" "flex-col" "items-center" "justify-center" "p-8" "text-center" "bg-base-100" "rounded-none" "border" "border-base-300" "h-full"]}
   [:div.text-gray-400.mb-4
    [:svg.w-12.h-12.mx-auto {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"}]]]
   [:h3.text-lg.font-medium.text-gray-300 "No Order Book Data"]
   [:p.text-sm.text-gray-500 "Subscribe to an asset to see its order book"]])

;; Loading state
(defn loading-orderbook []
  [:div {:class ["flex" "items-center" "justify-center" "p-8" "bg-base-100" "rounded-none" "border" "border-base-300" "h-full"]}
   [:div.animate-spin.rounded-full.h-8.w-8.border-b-2.border-blue-500]])

;; Main component that takes state and renders the UI
(defn l2-orderbook-view [state]
  (let [coin (:coin state)
        market (:market state)
        orderbook-data (:orderbook state)
        show-surface-freshness-cues?
        (boolean (:show-surface-freshness-cues? state))
        websocket-health (or (:websocket-health state)
                             (get-in state [:websocket :health]))
        active-tab-override (:active-tab-override state)
        show-tabs? (not= false (:show-tabs? state))
        orderbook-ui (merge {:size-unit :base
                             :size-unit-dropdown-visible? false
                             :price-aggregation-dropdown-visible? false
                             :price-aggregation-by-coin {}
                             :active-tab :orderbook}
                            (:orderbook-ui state))
        layout {:desktop-layout? (:desktop-layout? state)
                :viewport-width (:viewport-width state)}
        animate-orderbook? (trading-settings/animate-orderbook? state)
        loading? (:loading state)
        active-tab (normalize-orderbook-tab (or active-tab-override
                                               (:active-tab orderbook-ui)))
        base-symbol (resolve-base-symbol coin market)]
    [:div {:class ["w-full" "h-full" "min-h-0" "overflow-hidden" "flex" "flex-col"]
           :data-parity-id "orderbook-panel"}
     (when show-tabs?
       (orderbook-tabs-row active-tab))
     [:div {:class ["flex-1" "h-full" "min-h-0" "overflow-hidden" "bg-base-100"]}
      (if (= active-tab :trades)
        (tab-content-viewport
         (trades-panel coin base-symbol))
        (tab-content-viewport
         (cond
           loading? (loading-orderbook)
           (and coin orderbook-data) (l2-orderbook-panel coin
                                                          market
                                                          orderbook-data
                                                          orderbook-ui
                                                          websocket-health
                                                          show-surface-freshness-cues?
                                                          layout
                                                          animate-orderbook?)
           :else (empty-orderbook))))]]))
