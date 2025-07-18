(ns hyperopen.views.l2-orderbook-view
  (:require [hyperopen.websocket.orderbook :as orderbook]))

;; Utility functions for formatting
(defn format-price [price decimals]
  (when (and price (number? price))
    (.toFixed price decimals)))

(defn format-size [size]
  (when (and size (number? size))
    (.toLocaleString (js/Number. size) "en-US" #js {:maximumFractionDigits 0})))

(defn format-total [total]
  (when (and total (number? total))
    (.toLocaleString (js/Number. total) "en-US" #js {:maximumFractionDigits 0})))

(defn calculate-spread [best-bid best-ask]
  (when (and best-bid best-ask)
    (let [bid-price (:px best-bid)
          ask-price (:px best-ask)
          spread-abs (- ask-price bid-price)
          spread-pct (* (/ spread-abs bid-price) 100)]
      {:absolute spread-abs
       :percentage spread-pct})))

(defn calculate-cumulative-totals [orders]
  (loop [orders orders
         total 0
         result []]
    (if (empty? orders)
      result
      (let [order (first orders)
            new-total (+ total (:sz order))]
        (recur (rest orders)
               new-total
               (conj result (assoc order :total new-total)))))))

(defn get-max-size [orders]
  (when (seq orders)
    (apply max (map :sz orders))))

(defn size-bar-width [size max-size]
  (when (and size max-size (> max-size 0))
    (* (/ size max-size) 100)))

;; Component for individual order row
(defn order-row [order max-size is-ask?]
  (let [price (:px order)
        size (:sz order)
        total (:total order)
        bar-width (size-bar-width size max-size)
        color-class (if is-ask? "bg-red-900/20" "bg-green-900/20")
        text-color (if is-ask? "text-red-400" "text-green-400")]
    [:div.flex.items-center.h-8.relative
     {:class [color-class]}
     ;; Size bar background
     [:div.absolute.inset-0.flex.items-center
      [:div {:class ["h-full" color-class]
             :style {:width (str bar-width "%")}}]]
     ;; Content
     [:div.flex.w-full.items-center.justify-between.px-3.relative.z-10
      [:div.text-right.flex-1
       [:span {:class [text-color]} (or (format-price price 6) "0.000000")]]
      [:div.text-right.flex-1
       [:span {:class [text-color]} (or (format-size size) "0")]]
      [:div.text-right.flex-1
       [:span {:class [text-color]} (or (format-total total) "0")]]]]))

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
    [:span.text-gray-400.text-xs "Size (PUMP)"]]
   [:div.text-right.flex-1
    [:span.text-gray-400.text-xs "Total (PUMP)"]]])

;; Main order book component
(defn l2-orderbook-panel [coin orderbook-data]
  (let [bids (:bids orderbook-data)
        asks (:asks orderbook-data)
        best-bid (first bids)
        best-ask (first asks)
        spread (calculate-spread best-bid best-ask)
        asks-with-totals (calculate-cumulative-totals asks)
        bids-with-totals (calculate-cumulative-totals bids)
        max-ask-size (get-max-size asks)
        max-bid-size (get-max-size bids)
        max-size (max (or max-ask-size 0) (or max-bid-size 0))]
    [:div.bg-gray-900.rounded-lg.border.border-gray-700.overflow-hidden
     ;; Header
     (orderbook-header coin "0.000001")
     
     ;; Column headers
     (column-headers)
     
     ;; Asks (sell orders) - top section
     [:div
      (for [ask asks-with-totals]
        ^{:key (str "ask-" (:px ask))}
        (order-row ask max-size true))]
     
     ;; Spread - middle section
     (when spread
       (spread-row spread))
     
     ;; Bids (buy orders) - bottom section
     [:div
      (for [bid bids-with-totals]
        ^{:key (str "bid-" (:px bid))}
        (order-row bid max-size false))]]))

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