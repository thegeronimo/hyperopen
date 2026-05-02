(ns hyperopen.views.l2-orderbook.panel
  (:require [hyperopen.orderbook.price-aggregation :as price-agg]
            [hyperopen.views.l2-orderbook.depth :refer [column-headers
                                                         mobile-split-column-headers
                                                         mobile-split-order-row
                                                         order-row
                                                         spread-row]]
            [hyperopen.views.l2-orderbook.model :refer [desktop-orderbook-layout?
                                                         infer-market-type
                                                         normalize-size-unit
                                                         render-snapshot
                                                         resolve-base-symbol
                                                         resolve-quote-symbol
                                                         resolve-reference-price]]
            [hyperopen.views.l2-orderbook.tabs :refer [orderbook-header]]
            [hyperopen.views.websocket-freshness :as ws-freshness]))

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
