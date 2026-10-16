(ns hyperopen.views.l2-orderbook.depth
  (:require [hyperopen.views.l2-orderbook.readout :refer [card-id] :rename {card-id readout-card-id}]
            [hyperopen.views.l2-orderbook.model :refer [cumulative-bar-scale
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

;; Own resting order marker. Sits in the row's left padding, clear of the price text.
(defn- own-order-pip [position-classes]
  [:span {:class (into ["pointer-events-none" "absolute" "top-1/2" "-translate-y-1/2" "z-10"
                        "h-[5px]" "w-[5px]" "rounded-full" "bg-ho-warn"]
                       position-classes)
          :data-role "orderbook-own-order-pip"
          :aria-hidden "true"}])

(defn- own-order-note [own-size]
  (str "Your resting order here: " own-size))

(defn- level-attrs
  "A clickable level: sets the order form's limit price to the level's raw price."
  [row label-prefix own-size]
  (cond-> {:type "button"
           :aria-label (cond-> (str label-prefix " " (row-price-label row) ", set limit price")
                         own-size (str ". " (own-order-note own-size)))
           :on {:click [[:actions/set-order-price-from-orderbook (:px row)]]}}
    own-size (assoc :title (own-order-note own-size))))

(defn- hover-handlers
  "Hover or keyboard focus selects the row, by side and depth from the spread, for the
   depth readout. Leaving the book clears it from the depth body, so moving between
   rows is one write per row."
  [row depth]
  {:mouseenter [[:actions/set-orderbook-hover-level (:side row) depth]]
   :focus [[:actions/set-orderbook-hover-level (:side row) depth]]
   :blur [[:actions/clear-orderbook-hover-level]]})

;; Marks a level the order form's market order would take: a rail down the left edge
;; and a band for the share of the level it takes.
(defn- sweep-marks [fill-fraction]
  (list
   [:span {:class ["pointer-events-none" "absolute" "inset-y-0" "left-0" "z-10" "w-[2px]" "bg-ho-accent"]
           :data-role "orderbook-impact-rail"
           :aria-hidden "true"}]
   [:span {:class ["pointer-events-none" "absolute" "inset-0" "origin-left" "bg-ho-accent/15"]
           :style {:transform (str "scaleX(" fill-fraction ")")}
           :data-role "orderbook-impact-band"
           :aria-hidden "true"}]))

;; Component for individual order row.
;; `opts`:
;;   :depth          the row's distance from the spread (0 = best level), for hover
;;   :flash-version  update number this level changed in (nil = unchanged)
;;   :own-size       "0.5 BTC" when the user rests orders here
;;   :sweep-fill     0..1 share the order form's market order would take from this level
;;   :in-readout?    inside the hovered level's sweep (best price through the hovered level)
;;   :readout        the depth readout card, on the hovered level only
(defn order-row
  ([row size-unit]
   (order-row row size-unit true))
  ([row size-unit animate?]
   (order-row row size-unit animate? nil))
  ([row size-unit animate? {:keys [depth flash-version own-size sweep-fill in-readout? readout]}]
   (let [is-ask? (= :ask (:side row))
         bar-color (if is-ask? ask-depth-bar-class bid-depth-bar-class)
         price-text-color (if is-ask? ask-price-text-class bid-price-text-class)
         flash-class (when animate? (level-flash-class (:side row) flash-version))]
     [:button (-> (level-attrs row (if is-ask? "Ask" "Bid") own-size)
                  (cond-> (some? depth) (update :on merge (hover-handlers row depth)))
                  (merge {:class (cond-> ["flex" "items-center" "w-full" "h-[23px]" "relative" "bg-base-100"
                                          "text-xs" "text-left" "cursor-pointer" "orderbook-level-row"
                                          "focus:outline-none" "focus-visible:ring-1" "focus-visible:ring-inset"
                                          "focus-visible:ring-ho-accent"]
                                   flash-class (conj flash-class))
                          :data-role "orderbook-level-row"})
                  (cond-> readout (assoc :aria-describedby readout-card-id)))
      ;; Size bar background - always grows from the left
      (depth-bar row size-unit bar-color "origin-left" animate?)
      ;; Readout tint and hovered-row outline are their own layers: the change flash
      ;; animates the row's background and box-shadow, and would wipe them mid-flash.
      (when in-readout?
        [:span {:class ["pointer-events-none" "absolute" "inset-0" "bg-ho-text/5"]
                :data-role "orderbook-readout-tint"
                :aria-hidden "true"}])
      (when readout
        [:span {:class ["pointer-events-none" "absolute" "inset-0" "z-20" "border" "border-ho-text-secondary"]
                :data-role "orderbook-readout-outline"
                :aria-hidden "true"}])
      (when sweep-fill
        (sweep-marks sweep-fill))
      (when own-size
        (own-order-pip ["left-[3px]"]))
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
         (row-total-label row size-unit)]]]
      readout])))

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
  ([row size-unit animate? bid-flash-version ask-flash-version]
   (mobile-split-order-row row size-unit animate? bid-flash-version ask-flash-version nil nil))
  ([{:keys [bid ask]} size-unit animate? bid-flash-version ask-flash-version bid-own-size ask-own-size]
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
       [:span {:class [body-neutral-text-class "num"]} ask-total]]
      ;; One tap target per half, over the cells, so either side sets the limit price.
      (when bid
        [:button (merge (level-attrs bid "Bid" bid-own-size)
                        {:class ["absolute" "inset-y-0" "left-0" "z-20" "w-1/2" "cursor-pointer"
                                 "focus:outline-none" "focus-visible:ring-1" "focus-visible:ring-inset"
                                 "focus-visible:ring-ho-accent"]
                         :data-role "orderbook-mobile-bid-level"})
         (when bid-own-size
           (own-order-pip ["left-[2px]"]))])
      (when ask
        [:button (merge (level-attrs ask "Ask" ask-own-size)
                        {:class ["absolute" "inset-y-0" "right-0" "z-20" "w-1/2" "cursor-pointer"
                                 "focus:outline-none" "focus-visible:ring-1" "focus-visible:ring-inset"
                                 "focus-visible:ring-ho-accent"]
                         :data-role "orderbook-mobile-ask-level"})
         (when ask-own-size
           (own-order-pip ["right-[2px]"]))])])))
