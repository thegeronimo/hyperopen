(ns hyperopen.views.l2-orderbook-view
  (:require [clojure.string :as str]
            [hyperopen.orderbook.price-aggregation :as price-agg]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.utils.formatting :as fmt]))

;; Utility functions for formatting
(defn parse-number [value]
  (cond
    (number? value) value
    (string? value) (let [n (js/parseFloat value)]
                      (when-not (js/isNaN n) n))
    :else nil))

(def orderbook-tabs
  #{:orderbook :trades})

(def ^:private max-render-levels-per-side 80)

(defn normalize-orderbook-tab [tab]
  (let [tab* (cond
               (keyword? tab) tab
               (string? tab) (keyword tab)
               :else :orderbook)]
    (if (contains? orderbook-tabs tab*) tab* :orderbook)))

(defn format-price
  ([price] (fmt/format-trade-price-plain price price))
  ([price raw] (fmt/format-trade-price-plain price raw)))

(defn format-percent [value decimals]
  (when-some [num-value (parse-number value)]
    (.toLocaleString (js/Number. num-value)
                     "en-US"
                     #js {:minimumFractionDigits decimals
                          :maximumFractionDigits decimals})))

(defn format-total [total & {:keys [decimals] :or {decimals 0}}]
  (when-some [num-total (parse-number total)]
    (.toLocaleString (js/Number. num-total)
                     "en-US"
                     #js {:maximumFractionDigits decimals})))

(defn calculate-spread [best-bid best-ask]
  (when (and best-bid best-ask)
    (let [bid-price (parse-number (:px best-bid))
          ask-price (parse-number (:px best-ask))]
      (when (and bid-price ask-price (number? bid-price) (number? ask-price))
        (let [spread-abs (- ask-price bid-price)
              spread-pct (* (/ spread-abs ask-price) 100)]
          {:absolute spread-abs
           :percentage spread-pct})))))

(defn calculate-cumulative-totals [orders]
  "Given a vector of orders {:px price :sz size}
   returns the same orders with:
     :cum-size   running total size
     :cum-value  running notional value in quote currency (price*size)
   Orders must already be sorted in display order."
  (if (empty? orders)
    []
    (loop [remaining orders
           cum-size 0
           cum-value 0
           result []]
      (if (empty? remaining)
        result
        (let [order (first remaining)
              price (parse-number (:px order))
              size (parse-number (:sz order))
              new-cum-size (+ cum-size (or size 0))
              new-cum-value (+ cum-value (* (or price 0) (or size 0)))
              updated-order (assoc order
                                   :cum-size new-cum-size
                                   :cum-value new-cum-value)]
          (recur (rest remaining)
                 new-cum-size
                 new-cum-value
                 (conj result updated-order)))))))

(defn normalize-size-unit [size-unit]
  (if (= size-unit :quote) :quote :base))

(defn base-symbol-from-coin [coin]
  (cond
    (and (string? coin) (str/includes? coin "/"))
    (first (str/split coin #"/" 2))

    (and (string? coin) (str/includes? coin ":"))
    (second (str/split coin #":" 2))

    :else coin))

(defn quote-symbol-from-coin [coin]
  (if (and (string? coin) (str/includes? coin "/"))
    (second (str/split coin #"/" 2))
    "USDC"))

(defn resolve-base-symbol [coin market]
  (or (:base market) (base-symbol-from-coin coin) "Asset"))

(defn resolve-quote-symbol [coin market]
  (or (:quote market) (quote-symbol-from-coin coin) "USDC"))

(defn infer-market-type [coin market]
  (or (:market-type market)
      (if (and (string? coin) (str/includes? coin "/")) :spot :perp)))

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
    (let [d (js/Date. time-ms)
          pad2 (fn [v] (.padStart (str v) 2 "0"))]
      (str (pad2 (.getHours d)) ":" (pad2 (.getMinutes d)) ":" (pad2 (.getSeconds d))))))

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
  (->> (trades/get-recent-trades)
       (filter #(trade-matches-coin? % coin))
       (map normalize-trade)
       (sort-by (fn [trade] (or (:time-ms trade) 0)) >)
       (take 100)))

(defn order-size-for-unit [order size-unit]
  (if (= size-unit :quote)
    (* (or (parse-number (:px order)) 0)
       (or (parse-number (:sz order)) 0))
    (parse-number (:sz order))))

(defn order-total-for-unit [order size-unit]
  (if (= size-unit :quote)
    (:cum-value order)
    (:cum-size order)))

(defn get-max-cumulative-total [orders size-unit]
  (when (seq orders)
    (apply max (map #(or (order-total-for-unit % size-unit) 0) orders))))

(defn format-order-size [order size-unit]
  (if (= size-unit :quote)
    (or (format-total (order-size-for-unit order size-unit) :decimals 0) "0")
    (let [raw-size (:sz order)]
      (if (string? raw-size)
        raw-size
        (or (format-total raw-size :decimals 8) "0")))))

(defn format-order-total [order size-unit]
  (if (= size-unit :quote)
    (or (format-total (:cum-value order) :decimals 0) "0")
    (or (format-total (:cum-size order) :decimals 8) "0")))

(defn cumulative-bar-width [cum-size max-cum-size]
  (when (and cum-size max-cum-size (> max-cum-size 0))
    (* (/ cum-size max-cum-size) 100)))

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
       [:div.absolute.top-full.left-0.mt-1.bg-base-100.border.border-base-300.rounded.shadow-lg.z-30.min-w-24.overflow-hidden
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
     [:div.absolute.top-full.right-0.mt-1.bg-base-100.border.border-base-300.rounded.shadow-lg.z-20.min-w-20.overflow-hidden
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

;; Header component
(defn orderbook-header [selected-option price-options price-dropdown-visible? base-symbol quote-symbol size-unit size-dropdown-visible?]
  [:div.flex.items-center.justify-between.px-3.py-2.bg-base-100.border-b.border-base-300
   (precision-dropdown selected-option price-options price-dropdown-visible?)
   (size-unit-dropdown base-symbol quote-symbol size-unit size-dropdown-visible?)])

(defn orderbook-tab-button [active-tab tab-id label]
  [:button.flex-1.px-3.py-2.text-sm.font-medium.border-b-2.transition-colors
   {:type "button"
    :class (if (= active-tab tab-id)
             ["text-white" "border-cyan-400"]
             ["text-gray-400" "border-transparent" "hover:text-gray-200"])
    :on {:click [[:actions/select-orderbook-tab tab-id]]}}
   label])

(defn orderbook-tabs-row [active-tab]
  [:div.flex.items-center.bg-base-100.border-b.border-base-300
   (orderbook-tab-button active-tab :orderbook "Order Book")
   (orderbook-tab-button active-tab :trades "Trades")])

(defn tab-content-viewport [content]
  [:div {:class ["flex-1" "h-full" "min-h-0" "overflow-hidden" "bg-base-100"]}
   content])

(defn trades-column-headers [base-symbol]
  [:div.flex.items-center.justify-between.px-3.py-2.bg-base-100.border-b.border-base-300
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs "Price"]]
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs (str "Size (" base-symbol ")")]]
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs "Time"]]])

(defn trades-row [trade]
  (let [price-class (trade-side->price-class (:side trade))]
    [:div {:class ["flex" "items-center" "h-6" "relative" "bg-base-100" "text-xs" "border-b" "border-base-300"]}
     [:div.flex.w-full.items-center.justify-between.px-2
      [:div.text-right.flex-1
       [:span {:class [price-class]} (or (format-price (:price trade) (:price-raw trade)) "0.00")]]
      [:div.text-right.flex-1
       [:span.text-gray-100 (format-trade-size trade)]]
      [:div.text-right.flex-1
       [:span.text-gray-100 (or (format-trade-time (:time-ms trade)) "--:--:--")]]]]))

(defn empty-trades []
  [:div {:class ["flex" "flex-col" "items-center" "justify-center" "p-8" "text-center" "bg-base-100" "rounded-none" "border" "border-base-300" "h-full"]}
   [:h3.text-lg.font-medium.text-gray-300 "No Trades Yet"]
   [:p.text-sm.text-gray-500 "Recent trades will appear here"]])

(defn trades-panel [coin base-symbol]
  (let [recent-trades (recent-trades-for-coin coin)]
    (if (seq recent-trades)
      [:div {:class ["bg-base-100" "border" "border-base-300" "rounded-none" "overflow-hidden" "h-full" "min-h-0" "flex" "flex-col"]}
       (trades-column-headers base-symbol)
       [:div.flex-1.min-h-0.overflow-y-auto.scrollbar-hide
        (for [trade recent-trades]
          ^{:key (str "trade-" coin "-" (:tid trade) "-" (:time-ms trade) "-" (:price-raw trade) "-" (:size-raw trade))}
          (trades-row trade))]]
      (empty-trades))))

;; Component for individual order row
(defn order-row [order max-cum-size is-ask? size-unit]
  (let [price (:px order)
        cum-total (order-total-for-unit order size-unit)
        bar-width (cumulative-bar-width cum-total max-cum-size)
        bar-color (if is-ask? "bg-red-500/30" "bg-green-500/30")
        text-color (if is-ask? "text-red-400" "text-green-400")]
    [:div.flex.items-center.h-6.relative.bg-base-100.text-xs {:data-role "orderbook-level-row"}
     ;; Size bar background - always positioned from left
     [:div.absolute.inset-0.flex.items-center.justify-start
      [:div {:class ["h-full" bar-color "transition-all" "duration-300" "ease-[cubic-bezier(0.68,-0.6,0.32,1.6)]"]
             :style {:width (str (or bar-width 0) "%")}}]]
     ;; Content
     [:div.flex.w-full.items-center.justify-between.px-2.relative.z-10
      [:div.text-right.flex-1
       [:span {:class [text-color]} (or (format-price price price) "0.00")]]
      [:div.text-right.flex-1
       [:span {:class [text-color]} (format-order-size order size-unit)]]
      [:div.text-right.flex-1
       [:span {:class [text-color]} (format-order-total order size-unit)]]]]))

;; Spread component
(defn spread-row [spread]
  (let [absolute (:absolute spread)
        percentage (:percentage spread)]
    [:div.flex.items-center.justify-center.h-6.bg-base-100.border-y.border-base-300.text-xs
     [:div.flex.items-center.space-x-3.text-white
      [:span "Spread"]
      [:span (format-price absolute)]
      [:span (str (format-percent percentage 3) "%")]]]))

;; Column headers
(defn column-headers [size-symbol]
  [:div.flex.items-center.justify-between.px-3.py-2.bg-base-100.border-b.border-base-300
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs "Price"]]
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs (str "Size (" size-symbol ")")]]
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs (str "Total (" size-symbol ")")]]])

;; Main order book component
(defn l2-orderbook-panel [coin market orderbook-data orderbook-ui]
  (let [size-unit (normalize-size-unit (:size-unit orderbook-ui))
        size-unit-dropdown-visible? (boolean (:size-unit-dropdown-visible? orderbook-ui))
        price-dropdown-visible? (boolean (:price-aggregation-dropdown-visible? orderbook-ui))
        aggregation-by-coin (or (:price-aggregation-by-coin orderbook-ui) {})
        selected-mode (get aggregation-by-coin coin :full)
        base-symbol (resolve-base-symbol coin market)
        quote-symbol (resolve-quote-symbol coin market)
        selected-size-symbol (if (= size-unit :quote) quote-symbol base-symbol)
        raw-bids (:bids orderbook-data)
        raw-asks (:asks orderbook-data)

        ;; Sort into display order:
        ;; Asks: best->worst (lowest->highest). Lowest price = best ask.
        display-asks (sort-by #(or (parse-number (:px %)) 0) < raw-asks)
        ;; Bids: best->worst (highest->lowest). Highest price = best bid.
        display-bids (sort-by #(or (parse-number (:px %)) 0) > raw-bids)
        asks-limited (take max-render-levels-per-side display-asks)
        bids-limited (take max-render-levels-per-side display-bids)

        ;; Calculate cumulative totals in display order
        asks-with-totals (calculate-cumulative-totals asks-limited)
        bids-with-totals (calculate-cumulative-totals bids-limited)

        ;; Get best prices for spread calculation
        best-bid (first display-bids)
        best-ask (first display-asks)
        spread (calculate-spread best-bid best-ask)

        reference-price (resolve-reference-price best-bid best-ask market)
        market-type (infer-market-type coin market)
        sz-decimals (:szDecimals market)
        price-options (price-agg/build-options {:market-type market-type
                                                :sz-decimals sz-decimals
                                                :reference-price reference-price})
        selected-option (price-agg/option-for-mode price-options selected-mode)

        ;; Calculate max size for bar width in the selected unit
        max-ask-cum-size (get-max-cumulative-total asks-with-totals size-unit)
        max-bid-cum-size (get-max-cumulative-total bids-with-totals size-unit)
        max-cum-size (max (or max-ask-cum-size 0) (or max-bid-cum-size 0))]
    [:div {:class ["bg-base-100" "border" "border-base-300" "rounded-none" "overflow-hidden" "h-full" "flex" "flex-col"]}
     ;; Header
     (orderbook-header selected-option
                       price-options
                       price-dropdown-visible?
                       base-symbol
                       quote-symbol
                       size-unit
                       size-unit-dropdown-visible?)

     ;; Column headers
     (column-headers selected-size-symbol)

     ;; Order rows
     [:div {:class ["flex-1" "min-h-0" "flex" "flex-col"]
            :data-role "orderbook-depth-body"}
      ;; Asks (sell orders) - top section, rendered worst->best (reversed for display)
      [:div {:class ["flex-1" "min-h-0" "overflow-hidden" "flex" "flex-col" "justify-end"]
             :data-role "orderbook-asks-pane"}
       (for [ask (reverse asks-with-totals)]
         ^{:key (str "ask-" (:px ask))}
         (order-row ask max-cum-size true size-unit))]

      ;; Spread - middle section
      (when spread
        (spread-row spread))

      ;; Bids (buy orders) - bottom section, rendered best->worst
      [:div {:class ["flex-1" "min-h-0" "overflow-hidden" "flex" "flex-col"]
             :data-role "orderbook-bids-pane"}
       (for [bid bids-with-totals]
         ^{:key (str "bid-" (:px bid))}
         (order-row bid max-cum-size false size-unit))]]]))

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
        orderbook-ui (merge {:size-unit :base
                             :size-unit-dropdown-visible? false
                             :price-aggregation-dropdown-visible? false
                             :price-aggregation-by-coin {}
                             :active-tab :orderbook}
                            (:orderbook-ui state))
        loading? (:loading state)
        active-tab (normalize-orderbook-tab (:active-tab orderbook-ui))
        base-symbol (resolve-base-symbol coin market)]
    [:div {:class ["w-full" "h-full" "min-h-0" "overflow-hidden" "flex" "flex-col"]}
     (orderbook-tabs-row active-tab)
     [:div {:class ["flex-1" "h-full" "min-h-0" "overflow-hidden" "bg-base-100"]}
      (if (= active-tab :trades)
        (tab-content-viewport
         (trades-panel coin base-symbol))
        (tab-content-viewport
         (cond
           loading? (loading-orderbook)
           (and coin orderbook-data) (l2-orderbook-panel coin market orderbook-data orderbook-ui)
           :else (empty-orderbook))))]]))
