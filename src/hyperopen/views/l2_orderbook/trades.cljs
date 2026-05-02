(ns hyperopen.views.l2-orderbook.trades
  (:require [hyperopen.views.l2-orderbook.model :refer [format-price
                                                         format-trade-size
                                                         format-trade-time
                                                         recent-trades-for-coin
                                                         trade-side->price-class]]
            [hyperopen.views.l2-orderbook.styles :refer [body-neutral-text-class
                                                          header-neutral-text-class
                                                          orderbook-columns-class]]))

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
