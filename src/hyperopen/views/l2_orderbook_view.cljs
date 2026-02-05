(ns hyperopen.views.l2-orderbook-view
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]))

;; Utility functions for formatting
(defn parse-number [value]
  (cond
    (number? value) value
    (string? value) (let [n (js/parseFloat value)]
                      (when-not (js/isNaN n) n))
    :else nil))

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

;; Component for individual order row
(defn order-row [order max-cum-size is-ask? size-unit]
  (let [price (:px order)
        cum-total (order-total-for-unit order size-unit)
        bar-width (cumulative-bar-width cum-total max-cum-size)
        bar-color (if is-ask? "bg-red-500/30" "bg-green-500/30")
        text-color (if is-ask? "text-red-400" "text-green-400")]
    [:div.flex.items-center.h-6.relative.bg-gray-900.text-xs
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
    [:div.flex.items-center.justify-center.h-6.bg-gray-800.border-y.border-gray-700.text-xs
     [:div.flex.items-center.space-x-3.text-white
      [:span "Spread"]
      [:span (format-price absolute)]
      [:span (str (format-percent percentage 3) "%")]]]))

;; Header component
(defn orderbook-header [precision base-symbol quote-symbol size-unit dropdown-visible?]
  (let [selected-symbol (if (= size-unit :quote) quote-symbol base-symbol)]
    [:div.flex.items-center.justify-between.px-3.py-2.bg-gray-900.border-b.border-gray-700
     [:div.flex.items-center.space-x-2
      [:span.text-white.text-sm precision]
      [:svg.w-4.h-4.text-gray-400 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
       [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]]
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
      [:div.absolute.top-full.right-0.mt-1.bg-gray-900.border.border-gray-700.rounded.shadow-lg.z-20.min-w-20.overflow-hidden
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
        base-symbol]]]]))

;; Column headers
(defn column-headers [size-symbol]
  [:div.flex.items-center.justify-between.px-3.py-2.bg-gray-800.border-b.border-gray-700
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs "Price"]]
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs (str "Size (" size-symbol ")")]]
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs (str "Total (" size-symbol ")")]]])

;; Main order book component
(defn l2-orderbook-panel [coin market orderbook-data orderbook-ui]
  (let [max-rows 10
        size-unit (normalize-size-unit (:size-unit orderbook-ui))
        dropdown-visible? (boolean (:size-unit-dropdown-visible? orderbook-ui))
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
        asks-limited (take max-rows display-asks)
        bids-limited (take max-rows display-bids)

        ;; Calculate cumulative totals in display order
        asks-with-totals (calculate-cumulative-totals asks-limited)
        bids-with-totals (calculate-cumulative-totals bids-limited)

        ;; Get best prices for spread calculation
        best-bid (first display-bids)
        best-ask (first display-asks) ; first because asks go best->worst now
        spread (calculate-spread best-bid best-ask)

        ;; Calculate max size for bar width in the selected unit
        max-ask-cum-size (get-max-cumulative-total asks-with-totals size-unit)
        max-bid-cum-size (get-max-cumulative-total bids-with-totals size-unit)
        max-cum-size (max (or max-ask-cum-size 0) (or max-bid-cum-size 0))]
    [:div {:class ["bg-base-100" "border" "border-base-300" "rounded-none" "overflow-hidden" "h-full" "flex" "flex-col"]}
     ;; Header
     (orderbook-header "0.000001" base-symbol quote-symbol size-unit dropdown-visible?)

     ;; Column headers
     (column-headers selected-size-symbol)

     ;; Order rows
     [:div
      ;; Asks (sell orders) - top section, rendered worst->best (reversed for display)
      [:div
       (for [ask (reverse asks-with-totals)]
         ^{:key (str "ask-" (:px ask))}
         (order-row ask max-cum-size true size-unit))]

      ;; Spread - middle section
      (when spread
        (spread-row spread))

      ;; Bids (buy orders) - bottom section, rendered best->worst
      [:div
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
                             :size-unit-dropdown-visible? false}
                            (:orderbook-ui state))
        loading? (:loading state)]
    [:div {:class ["w-full" "h-full"]}
     (cond
       loading? (loading-orderbook)
       (and coin orderbook-data) (l2-orderbook-panel coin market orderbook-data orderbook-ui)
       :else (empty-orderbook))]))
