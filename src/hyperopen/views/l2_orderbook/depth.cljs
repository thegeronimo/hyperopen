(ns hyperopen.views.l2-orderbook.depth
  (:require [hyperopen.views.l2-orderbook.model :refer [cumulative-bar-scale
                                                         format-order-size
                                                         format-order-total
                                                         format-percent
                                                         format-price
                                                         order-total-for-unit]]
            [hyperopen.views.l2-orderbook.styles :refer [ask-depth-bar-class
                                                          ask-price-text-class
                                                          bid-depth-bar-class
                                                          bid-price-text-class
                                                          body-neutral-text-class
                                                          depth-bar-transition-classes
                                                          level-flash-class
                                                          header-neutral-text-class
                                                          mobile-split-columns-class
                                                          orderbook-columns-class]]))

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

(defn- row-bar-scale [row size-unit]
  (or (get-in row [:display :bar-scale size-unit])
      (cumulative-bar-scale (order-total-for-unit row size-unit)
                            (get-in row [:max-total-by-unit size-unit]))))

(defn- depth-bar
  "A full-width fill scaled along x. Animating `transform` stays on the compositor,
   where animating `width` would relayout the row on every frame."
  [row size-unit bar-color origin-class animate?]
  [:div {:class (cond-> ["pointer-events-none" "absolute" "inset-0" origin-class bar-color]
                  animate? (into depth-bar-transition-classes))
         :style {:transform (str "scaleX(" (row-bar-scale row size-unit) ")")}}])

;; Component for individual order row
(defn order-row
  ([row size-unit]
   (order-row row size-unit true))
  ([row size-unit animate?]
   (order-row row size-unit animate? nil))
  ([row size-unit animate? flash-version]
   (let [is-ask? (= :ask (:side row))
         bar-color (if is-ask? ask-depth-bar-class bid-depth-bar-class)
         price-text-color (if is-ask? ask-price-text-class bid-price-text-class)
         flash-class (when animate? (level-flash-class (:side row) flash-version))]
     [:div {:class (cond-> ["flex" "items-center" "h-[23px]" "relative" "bg-base-100" "text-xs" "orderbook-level-row"]
                     flash-class (conj flash-class))
            :data-role "orderbook-level-row"}
      ;; Size bar background - always grows from the left
      (depth-bar row size-unit bar-color "origin-left" animate?)
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
  [:div {:class ["ob-spread-row" "flex" "items-center" "justify-center" "h-[23px]" "bg-base-100" "border-y" "border-base-300" "text-xs"]}
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

(defn mobile-split-column-headers [size-symbol]
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

(defn mobile-split-order-row
  ([row size-unit]
   (mobile-split-order-row row size-unit true))
  ([row size-unit animate?]
   (mobile-split-order-row row size-unit animate? nil nil))
  ([{:keys [bid ask]} size-unit animate? bid-flash-version ask-flash-version]
   (let [bid-total (when bid (row-total-label bid size-unit))
         ask-total (when ask (row-total-label ask size-unit))
         bid-price (when bid (row-price-label bid))
         ask-price (when ask (row-price-label ask))
         bid-flash (when animate? (level-flash-class :bid bid-flash-version))
         ask-flash (when animate? (level-flash-class :ask ask-flash-version))]
     [:div {:class ["relative" "grid" mobile-split-columns-class "items-center" "gap-x-2" "px-2" "h-5" "bg-base-100" "text-xs" "border-b" "border-base-300/60"]
            :data-role "orderbook-mobile-split-row"}
      [:div {:class (cond-> ["pointer-events-none" "absolute" "inset-y-0" "left-0" "w-1/2" "pr-1"]
                      bid-flash (conj bid-flash))}
       (when bid
         [:div {:class ["relative" "h-full"]}
          (depth-bar bid size-unit bid-depth-bar-class "origin-right" animate?)])]
      [:div {:class (cond-> ["pointer-events-none" "absolute" "inset-y-0" "right-0" "w-1/2" "pl-1"]
                      ask-flash (conj ask-flash))}
       (when ask
         [:div {:class ["relative" "h-full"]}
          (depth-bar ask size-unit ask-depth-bar-class "origin-left" animate?)])]
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
