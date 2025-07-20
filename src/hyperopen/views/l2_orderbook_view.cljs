(ns hyperopen.views.l2-orderbook-view
  (:require [hyperopen.websocket.orderbook :as orderbook]))

;; Utility functions for formatting
(defn format-price [price decimals]
  (when price
    (let [num-price (if (string? price) (js/parseFloat price) price)]
      (when (and num-price (number? num-price) (not (js/isNaN num-price)))
        (.toLocaleString (js/Number. num-price) "en-US" #js {:minimumFractionDigits decimals :maximumFractionDigits decimals})))))

(defn format-size [size]
  (when size
    (let [num-size (if (string? size) (js/parseFloat size) size)]
      (when (and num-size (number? num-size) (not (js/isNaN num-size)))
        (.toLocaleString (js/Number. num-size) "en-US" #js {:maximumFractionDigits 0})))))

(defn format-total [total & {:keys [decimals] :or {decimals 0}}]
  (when total
    (let [num-total (if (string? total) (js/parseFloat total) total)]
      (when (and num-total (number? num-total) (not (js/isNaN num-total)))
        (.toLocaleString (js/Number. num-total) "en-US" #js {:maximumFractionDigits decimals})))))

(defn calculate-spread [best-bid best-ask]
  (when (and best-bid best-ask)
    (let [bid-price (let [px (:px best-bid)]
                     (if (string? px) (js/parseFloat px) px))
          ask-price (let [px (:px best-ask)]
                     (if (string? px) (js/parseFloat px) px))]
      (when (and bid-price ask-price (number? bid-price) (number? ask-price))
        (let [spread-abs (- ask-price bid-price)
              spread-pct (* (/ spread-abs ask-price) 100)]
          {:absolute spread-abs
           :percentage spread-pct})))))

(defn calculate-cumulative-totals [orders]
  "Given a vector of orders {:px price :sz size}
   returns the same orders with:
     :cum-size   – running total size (BTC)
     :cum-value  – running notional value in quote currency (price*size)
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
              price (if (string? (:px order)) (js/parseFloat (:px order)) (:px order))
              size (if (string? (:sz order)) (js/parseFloat (:sz order)) (:sz order))
              new-cum-size (+ cum-size (or size 0))
              new-cum-value (+ cum-value (* (or price 0) (or size 0)))
              updated-order (assoc order 
                                   :cum-size new-cum-size
                                   :cum-value new-cum-value)]
          (recur (rest remaining)
                 new-cum-size
                 new-cum-value
                 (conj result updated-order)))))))

(defn get-max-size [orders]
  (when (seq orders)
    (apply max (map (fn [order]
                     (let [size (:sz order)]
                       (if (string? size) (js/parseFloat size) size)))
                   orders))))

(defn get-max-cumulative-size [orders]
  (when (seq orders)
    (apply max (map :cum-size orders))))

(defn cumulative-bar-width [cum-size max-cum-size]
  (when (and cum-size max-cum-size (> max-cum-size 0))
    (* (/ cum-size max-cum-size) 100)))

;; Component for individual order row
(defn order-row [order max-cum-size is-ask?]
  (let [price (:px order)
        size (:sz order)
        cum-size (:cum-size order)
        bar-width (cumulative-bar-width cum-size max-cum-size)
        bar-color (if is-ask? "bg-red-500/30" "bg-green-500/30")
        text-color (if is-ask? "text-red-400" "text-green-400")]
    [:div.flex.items-center.h-8.relative.bg-gray-900
     ;; Size bar background - always positioned from left
     [:div.absolute.inset-0.flex.items-center.justify-start
      [:div {:class ["h-full" bar-color]
             :style {:width (str bar-width "%")}}]]
     ;; Content
     [:div.flex.w-full.items-center.justify-between.px-3.relative.z-10
      [:div.text-right.flex-1
       [:span {:class [text-color]} (or (format-price price 2) "0.000000")]]
      [:div.text-right.flex-1
       [:span {:class [text-color]} (or size "0")]]
      [:div.text-right.flex-1
       [:span {:class [text-color]} (or (format-total cum-size :decimals 8) "0")]]]]))

;; Spread component
(defn spread-row [spread]
  (let [absolute (:absolute spread)
        percentage (:percentage spread)]
    [:div.flex.items-center.justify-center.h-8.bg-gray-800.border-y.border-gray-700
     [:div.flex.items-center.space-x-4.text-white.text-sm
      [:span "Spread"]
      [:span (format-price absolute 6)]
      [:span (str (format-price percentage 3) "%")]]]))

;; Header component
(defn orderbook-header [coin precision]
  [:div.flex.items-center.justify-between.px-3.py-2.bg-gray-900.border-b.border-gray-700
   [:div.flex.items-center.space-x-2
    [:span.text-white.text-sm precision]
    [:svg.w-4.h-4.text-gray-400 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]]
   [:div.flex.items-center.space-x-2
    [:span.text-white.text-sm coin]
    [:svg.w-4.h-4.text-gray-400 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]]])

;; Column headers
(defn column-headers []
  [:div.flex.items-center.justify-between.px-3.py-2.bg-gray-800.border-b.border-gray-700
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs "Price"]]
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs "Size"]]
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs "Total"]]])

;; Main order book component
(defn l2-orderbook-panel [coin orderbook-data]
  (let [raw-bids (:bids orderbook-data)
        raw-asks (:asks orderbook-data)
        
        ;; Sort into display order:
        ;; Asks: best→worst (lowest→highest). Lowest price = best ask.
        display-asks (sort-by (comp js/parseFloat :px) < raw-asks)
        ;; Bids: best→worst (highest→lowest). Highest price = best bid.  
        display-bids (sort-by (comp js/parseFloat :px) > raw-bids)
        
        ;; Calculate cumulative totals in display order
        asks-with-totals (calculate-cumulative-totals display-asks)
        bids-with-totals (calculate-cumulative-totals display-bids)
        
        ;; Get best prices for spread calculation
        best-bid (first display-bids)
        best-ask (first display-asks)  ; first because asks go best→worst now
        spread (calculate-spread best-bid best-ask)
        
        ;; Calculate max size for bar width
        max-ask-cum-size (get-max-cumulative-size asks-with-totals)
        max-bid-cum-size (get-max-cumulative-size bids-with-totals)
        max-cum-size (max (or max-ask-cum-size 0) (or max-bid-cum-size 0))]
    [:div.bg-gray-900.rounded-lg.border.border-gray-700.overflow-hidden
     ;; Header
     (orderbook-header coin "0.000001")
     
     ;; Column headers
     (column-headers)
     
     ;; Asks (sell orders) - top section, rendered worst→best (reversed for display)
     [:div
      (for [ask (reverse asks-with-totals)]
        ^{:key (str "ask-" (:px ask))}
        (order-row ask max-cum-size true))]
     
     ;; Spread - middle section
     (when spread
       (spread-row spread))
     
     ;; Bids (buy orders) - bottom section, rendered best→worst
     [:div
      (for [bid bids-with-totals]
        ^{:key (str "bid-" (:px bid))}
        (order-row bid max-cum-size false))]]))

;; Empty state
(defn empty-orderbook []
  [:div.flex.flex-col.items-center.justify-center.p-8.text-center.bg-gray-900.rounded-lg.border.border-gray-700
   [:div.text-gray-400.mb-4
    [:svg.w-12.h-12.mx-auto {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"}]]]
   [:h3.text-lg.font-medium.text-gray-300 "No Order Book Data"]
   [:p.text-sm.text-gray-500 "Subscribe to an asset to see its order book"]])

;; Loading state
(defn loading-orderbook []
  [:div.flex.items-center.justify-center.p-8.bg-gray-900.rounded-lg.border.border-gray-700
   [:div.animate-spin.rounded-full.h-8.w-8.border-b-2.border-blue-500]])

;; Main component that takes state and renders the UI
(defn l2-orderbook-view [state]
  (let [coin (:coin state)
        orderbook-data (:orderbook state)
        loading? (:loading state)]
    [:div.w-full.max-w-md
     (cond
       loading? (loading-orderbook)
       (and coin orderbook-data) (l2-orderbook-panel coin orderbook-data)
       :else (empty-orderbook))])) 