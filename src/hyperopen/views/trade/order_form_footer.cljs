(ns hyperopen.views.trade.order-form-footer
  (:require [hyperopen.views.trade.order-form-component-primitives :as primitives]))

(def ^:private liquidation-price-tooltip
  "Position risk is low, so there is no liquidation price for the time being. Note that increasing the position or reducing the margin will increase the risk.")

(defn submit-row [{:keys [submitting? submit-disabled? submit-tooltip
                          submit-label submitting-label submit-recap on-submit]}]
  [:div {:class ["relative" "group"]
         :tabindex (when (seq submit-tooltip) 0)}
   [:button {:type "button"
             :class (into ["w-full"
                           "h-[33px]"
                           "rounded-lg"
                           "text-xs"
                           "font-semibold"
                           "transition-colors"
                           "focus:outline-none"
                           "focus:ring-1"
                           "focus:ring-ho-text-muted/40"
                           "focus:ring-offset-0"]
                          (if submit-disabled?
                            ["bg-[rgb(23,69,63)]"
                             "text-[#7f9f9a]"
                             "cursor-not-allowed"]
                            ["bg-primary"
                             "text-primary-content"
                             "hover:bg-primary/90"]))
             :data-parity-id "trade-submit-order-button"
             :disabled submit-disabled?
             :on {:click on-submit}}
    (if submitting?
      (or submitting-label "Submitting...")
      (or submit-label "Place Order"))]
   ;; A one-line recap of anything the button's own label cannot carry -- today the TWAP
   ;; price guards. Sits above the metrics divider so it reads as part of the action.
   (when (seq submit-recap)
     [:div {:class ["mt-1" "text-center" "text-ho-text-dim"]
            :style {:font-size "11px"}}
      submit-recap])
   (when (seq submit-tooltip)
     [:div {:class ["order-submit-tooltip"
                    "pointer-events-none"
                    "absolute"
                    "left-0"
                    "right-0"
                    "bottom-full"
                    "mb-2"
                    "rounded-md"
                    "border"
                    "border-base-300"
                    "bg-base-200"
                    "px-2.5"
                    "py-2"
                    "text-xs"
                    "text-gray-200"
                    "spectate-lg"
                    "opacity-0"
                    "translate-y-1"
                    "transition-all"
                    "duration-150"
                    "group-hover:opacity-100"
                    "group-hover:translate-y-0"
                    "group-focus:opacity-100"
                    "group-focus:translate-y-0"]}
      submit-tooltip])])

(defn- fees-label [tooltip]
  (if (seq tooltip)
    [:div {:class ["group" "relative" "inline-flex" "items-center"]
           :tabindex 0}
     [:span {:class ["text-sm"
                     "text-gray-400"
                     "underline"
                     "decoration-dashed"
                     "underline-offset-2"]}
      "Fees"]
     [:div {:class ["pointer-events-none"
                    "absolute"
                    "right-0"
                    "bottom-full"
                    "mb-1"
                    "z-[100]"
                    "w-max"
                    "opacity-0"
                    "transition-opacity"
                    "duration-150"
                    "group-hover:opacity-100"
                    "group-focus:opacity-100"]}
      [:div {:class ["relative"
                     "rounded-[5px]"
                     "bg-[rgb(39,48,53)]"
                     "px-[10px]"
                     "py-[6px]"
                     "text-left"
                     "whitespace-nowrap"
                     "font-normal"
                     "leading-[1.35]"
                     "text-white"]
             :style {:font-size "11px"}}
       tooltip
       [:div {:class ["absolute"
                      "left-1/2"
                      "-translate-x-1/2"
                      "top-full"
                      "h-0"
                      "w-0"
                      "border-x-4"
                      "border-x-transparent"
                      "border-t-4"
                      "border-t-[rgb(39,48,53)]"]}]]]]
    [:span {:class ["text-sm" "text-gray-400"]} "Fees"]))

(defn- fee-tooltip [effective]
  (if-let [[_ taker maker] (re-matches #"([^ ]+%) / ([^ ]+%)" (or effective ""))]
    (str "Taker orders pay a " taker " fee. Maker orders pay a " maker " fee.")
    "Taker orders pay a fee. Maker orders pay a fee."))

(defn fee-row-copy [fees-display]
  (let [effective (:effective fees-display)
        tooltip (fee-tooltip effective)]
    {:current-label "Current fee"
     :baseline-label "Base tier fee"
     :tooltip tooltip}))

(defn- fees-row
  [{:keys [effective baseline]}
   {:keys [current-label baseline-label tooltip]}]
  [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
   (fees-label tooltip)
   [:div {:class ["max-w-[260px]" "text-right" "leading-tight" "space-y-1"]}
    (if (seq baseline)
      [:div {:class ["flex" "items-center" "justify-end" "gap-1.5" "flex-wrap"]}
       [:span {:class ["text-xs" "text-gray-400"]} (str (or current-label "Current fee") ":")]
       [:span {:class ["text-sm" "font-semibold" "num" "text-gray-100"]}
        (or effective "N/A")]]
      [:span {:class ["block" "text-sm" "font-semibold" "num" "text-gray-100"]}
       (or effective "N/A")])
    (when (seq baseline)
      [:div {:class ["flex" "items-center" "justify-end" "gap-1.5" "flex-wrap"]}
       [:span {:class ["text-xs" "text-gray-500"]} (str (or baseline-label "Base tier fee") ":")]
       [:span {:class ["text-xs"
                       "font-semibold"
                       "num"
                       "text-gray-400"
                       "line-through"]}
        baseline]])]])

(defn footer-metrics
  [display show-liquidation-row? show-margin-row? show-slippage-row? fee-copy scale-preview-lines]
  (let [liquidation-price (:liquidation-price display)
        liquidation-tooltip (when (= liquidation-price "N/A")
                              liquidation-price-tooltip)]
    (into
     [:div {:class ["border-t" "border-base-300" "pt-2" "space-y-1.5" "sm:pt-2.5" "sm:space-y-2"]}]
     (concat
      (when scale-preview-lines
        [(primitives/metric-row "Start" (:start scale-preview-lines))
         (primitives/metric-row "End" (:end scale-preview-lines))])
      (when show-liquidation-row?
        [(primitives/metric-row "Liquidation Price"
                                liquidation-price
                                nil
                                liquidation-tooltip)])
      [(primitives/metric-row "Order Value"
                              (:order-value display))]
      ;; Margin Required is perp-only; hidden for spot.
      (when show-margin-row?
        [(primitives/metric-row "Margin Required"
                                (:margin-required display))])
      (when show-slippage-row?
        [(primitives/metric-row "Slippage"
                                (:slippage display)
                                "text-primary")])
      [(fees-row (:fees display) fee-copy)]))))
