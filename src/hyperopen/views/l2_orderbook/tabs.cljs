(ns hyperopen.views.l2-orderbook.tabs
  (:require [hyperopen.views.l2-orderbook.dropdowns :refer [precision-dropdown
                                                             size-unit-dropdown]]
            [hyperopen.views.l2-orderbook.styles :refer [orderbook-tab-indicator-class]]))

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
