(ns hyperopen.views.trade.order-form-view
  (:require [hyperopen.views.trade.order-form-component-primitives :as primitives]
            [hyperopen.views.trade.order-form-component-sections :as sections]
            [hyperopen.views.trade.order-form-handlers :as handlers]
            [hyperopen.views.trade.order-form-vm :as order-form-vm]))

(defn- unsupported-market-banner [message]
  [:div {:class ["px-3" "py-2" "bg-base-200" "border" "border-base-300" "rounded-lg" "text-xs" "text-gray-300"]}
   message])

(defn- price-context-accessory [{:keys [label mid-available?]} on-set-to-mid]
  [:button {:type "button"
            :disabled (not mid-available?)
            :class (into ["text-xs" "font-semibold" "transition-colors"]
                         (if mid-available?
                           ["text-primary" "cursor-pointer" "hover:text-primary/80"]
                           ["text-gray-500" "cursor-default"]))
            :on (when mid-available?
                  {:click on-set-to-mid})}
   (or label "Ref")])

(defn- size-unit-option [mode label selected? on-select-mode]
  [:button {:type "button"
            :class (into ["w-full"
                          "rounded-md"
                          "px-3"
                          "py-1.5"
                          "text-left"
                          "text-xs"
                          "font-semibold"
                          "transition-colors"]
                         (if selected?
                           ["bg-[#273035]" "text-[#50D2C1]"]
                           ["text-[#D2DAD7]" "hover:bg-[#273035]" "hover:text-[#F6FEFD]"]))
            :role "option"
            :aria-selected (boolean selected?)
            :on {:click (on-select-mode mode)}}
   label])

(defn- size-unit-accessory [{:keys [size-input-mode quote-symbol base-symbol]}
                            {:keys [dropdown-open?
                                    on-toggle-dropdown
                                    on-close-dropdown
                                    on-dropdown-keydown
                                    on-select-mode]}]
  (let [selected-mode (if (= size-input-mode :base) :base :quote)
        selected-label (if (= selected-mode :base) base-symbol quote-symbol)]
    [:div {:class ["relative"]
           :style (when dropdown-open?
                    {:z-index 1200})}
     (when dropdown-open?
       [:button {:type "button"
                 :class ["absolute" "bg-transparent" "cursor-default"]
                 :style {:left "-100vmax"
                         :top "-100vmax"
                         :width "200vmax"
                         :height "200vmax"
                         :z-index 1200}
                 :aria-label "Close size unit menu"
                 :on {:click on-close-dropdown}}])
     [:button {:type "button"
               :class ["relative"
                       "flex"
                       "items-center"
                       "gap-1"
                       "bg-transparent"
                       "text-sm"
                       "font-semibold"
                       "text-gray-100"
                       "outline-none"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"
                       "focus:shadow-none"]
               :aria-label "Size unit"
               :aria-haspopup "listbox"
               :aria-expanded (boolean dropdown-open?)
               :style (when dropdown-open?
                        {:z-index 1201})
               :on {:click on-toggle-dropdown
                    :keydown on-dropdown-keydown}}
      [:span selected-label]
      [:svg {:class ["pointer-events-none" "h-3.5" "w-3.5" "text-gray-400"]
             :viewBox "0 0 20 20"
             :fill "currentColor"}
       [:path {:fill-rule "evenodd"
               :clip-rule "evenodd"
               :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]]
     (when dropdown-open?
       [:div {:class ["absolute"
                      "right-0"
                      "top-full"
                      "mt-1"
                      "min-w-[88px]"
                      "rounded-lg"
                      "border"
                      "border-[#273035]"
                      "bg-[#1B2429]"
                      "p-1"
                      "shadow-[0_10px_24px_rgba(0,0,0,0.35)]"]
              :style {:z-index 1202}
              :role "listbox"
              :aria-label "Size unit options"
              :on {:keydown on-dropdown-keydown}}
        (size-unit-option :quote quote-symbol (= selected-mode :quote) on-select-mode)
        (size-unit-option :base base-symbol (= selected-mode :base) on-select-mode)])]))

(defn- leverage-row [ui-leverage next-leverage leverage-handlers]
  [:div {:class ["grid" "grid-cols-3" "gap-2"]}
   (primitives/chip-button "Cross" true :disabled? true)
   (primitives/chip-button (str ui-leverage "x")
                           true
                           :on-click ((:on-next-leverage leverage-handlers) next-leverage))
   (primitives/chip-button "Classic" true :disabled? true)])

(defn- side-row [side side-handlers]
  [:div {:class ["flex" "items-center" "gap-2" "bg-base-200" "rounded-md" "p-1"]}
   (primitives/side-button "Buy / Long"
                           :buy
                           (= side :buy)
                           ((:on-select-side side-handlers) :buy))
   (primitives/side-button "Sell / Short"
                           :sell
                           (= side :sell)
                           ((:on-select-side side-handlers) :sell))])

(defn- balances-row [display]
  [:div {:class ["space-y-1.5"]}
   [:div {:class ["flex" "items-center" "justify-between"]}
    [:span {:class ["text-sm" "text-gray-400"]} "Available to Trade"]
    [:span {:class ["text-sm" "font-semibold" "text-gray-100" "num"]}
     (:available-to-trade display)]]
   [:div {:class ["flex" "items-center" "justify-between"]}
    [:span {:class ["text-sm" "text-gray-400"]} "Current position"]
    [:span {:class ["text-sm" "font-semibold" "text-gray-100" "num"]}
     (:current-position display)]]])

(defn- size-row [{:keys [size-display
                         size-input-mode
                         quote-symbol
                         base-symbol
                         size-unit-dropdown-open?
                         display-size-percent
                         size-percent
                         notch-overlap-threshold]}
                 size-handlers]
  [:div {:class ["space-y-2"]}
   (primitives/row-input size-display
                         "Size"
                         (:on-change-display size-handlers)
                         (size-unit-accessory {:size-input-mode size-input-mode
                                               :quote-symbol quote-symbol
                                               :base-symbol base-symbol}
                                              {:dropdown-open? size-unit-dropdown-open?
                                               :on-toggle-dropdown (:on-toggle-dropdown size-handlers)
                                               :on-close-dropdown (:on-close-dropdown size-handlers)
                                               :on-dropdown-keydown (:on-dropdown-keydown size-handlers)
                                               :on-select-mode (:on-select-mode size-handlers)}))
   [:div {:class ["flex" "items-center" "gap-2"]}
    [:div {:class ["relative" "flex-1"]}
     [:input {:class ["order-size-slider" "range" "range-sm" "w-full" "relative" "z-20"]
              :type "range"
              :min 0
              :max 100
              :step 1
              :style {:--order-size-slider-progress (str size-percent "%")}
              :value size-percent
              :on {:input (:on-change-percent size-handlers)}}]
     [:div {:class ["order-size-slider-notches"
                    "pointer-events-none"
                    "absolute"
                    "inset-x-0"
                    "top-1/2"
                    "z-30"
                    "flex"
                    "items-center"
                    "justify-between"
                    "px-0.5"]}
      (for [pct [0 25 50 75 100]]
        ^{:key (str "size-slider-notch-" pct)}
        [:span {:class (into ["order-size-slider-notch"
                              "block"
                              "h-[7px]"
                              "w-[7px]"
                              "-translate-y-1/2"
                              "rounded-full"]
                             (remove nil?
                                     [(if (>= size-percent pct)
                                        "order-size-slider-notch-active"
                                        "order-size-slider-notch-inactive")
                                      (when (<= (js/Math.abs (- size-percent pct))
                                                notch-overlap-threshold)
                                        "opacity-0")]))}])]]
    [:div {:class ["relative" "w-[82px]"]}
     [:input {:class (into ["order-size-percent-input"
                            "h-10"
                            "w-full"
                            "bg-base-200/80"
                            "border"
                            "border-base-300"
                            "rounded-lg"
                            "text-right"
                            "text-sm"
                            "font-semibold"
                            "text-gray-100"
                            "num"
                            "appearance-none"
                            "pl-2.5"
                            "pr-6"]
                           primitives/neutral-input-focus-classes)
              :type "text"
              :inputmode "numeric"
              :pattern "[0-9]*"
              :value display-size-percent
              :on {:input (:on-change-percent size-handlers)}}]
     [:span {:class ["pointer-events-none"
                     "absolute"
                     "right-2.5"
                     "top-1/2"
                     "-translate-y-1/2"
                     "text-sm"
                     "font-semibold"
                     "text-gray-300"]}
      "%"]]]])

(defn- submit-row [{:keys [submitting? submit-disabled? submit-tooltip on-submit]}]
  [:div {:class ["relative" "group"]
         :tabindex (when (seq submit-tooltip) 0)}
   [:button {:type "button"
             :class (into ["w-full"
                           "h-11"
                           "rounded-lg"
                           "text-sm"
                           "font-semibold"
                           "transition-colors"
                           "focus:outline-none"
                           "focus:ring-1"
                           "focus:ring-[#8a96a6]/40"
                           "focus:ring-offset-0"]
                          (if submit-disabled?
                            ["bg-[rgb(23,69,63)]"
                             "text-[#7f9f9a]"
                             "cursor-not-allowed"]
                            ["bg-primary"
                             "text-primary-content"
                             "hover:bg-primary/90"]))
             :disabled submit-disabled?
             :on {:click on-submit}}
    (if submitting? "Submitting..." "Place Order")]
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
                    "shadow-lg"
                    "opacity-0"
                    "translate-y-1"
                    "transition-all"
                    "duration-150"
                    "group-hover:opacity-100"
                    "group-hover:translate-y-0"
                    "group-focus:opacity-100"
                    "group-focus:translate-y-0"]}
      submit-tooltip])])

(def ^:private liquidation-price-tooltip
  "Position risk is low, so there is no liquidation price for the time being. Note that increasing the position or reducing the margin will increase the risk.")

(defn- footer-metrics [display show-liquidation-row? show-slippage-row?]
  (let [liquidation-price (:liquidation-price display)
        liquidation-tooltip (when (= liquidation-price "N/A")
                              liquidation-price-tooltip)]
    [:div {:class ["border-t" "border-base-300" "pt-3" "space-y-2"]}
     (when show-liquidation-row?
       (primitives/metric-row "Liquidation Price"
                              liquidation-price
                              nil
                              liquidation-tooltip))
   (primitives/metric-row "Order Value"
                          (:order-value display))
   (primitives/metric-row "Margin Required"
                          (:margin-required display))
   (when show-slippage-row?
     (primitives/metric-row "Slippage"
                            (:slippage display)
                            "text-primary"))
   (primitives/metric-row "Fees"
                          (:fees display))]))

(defn order-form-view [state]
  (let [{:keys [form
                side
                type
                entry-mode
                pro-dropdown-open?
                pro-dropdown-options
                pro-tab-label
                controls
                spot?
                read-only?
                display
                ui-leverage
                next-leverage
                size-percent
                display-size-percent
                notch-overlap-threshold
                size-input-mode
                size-display
                price
                quote-symbol
                base-symbol
                scale-preview-lines
                error
                submitting?
                submit]}
        (order-form-vm/order-form-vm state)
        handler-map (handlers/build-handlers)
        size-unit-dropdown-open? (boolean (get-in state [:order-form-ui :size-unit-dropdown-open?]))
        entry-mode-handlers (:entry-mode handler-map)
        leverage-handlers (:leverage handler-map)
        side-handlers (:side handler-map)
        price-handlers (:price handler-map)
        size-handlers (:size handler-map)
        section-handlers (:order-type-sections handler-map)
        toggle-handlers (:toggles handler-map)
        tif-handlers (:tif handler-map)
        tp-sl-handlers (:tp-sl handler-map)
        submit-handlers (:submit handler-map)
        {:keys [show-limit-like-controls?
                show-tpsl-toggle?
                show-tpsl-panel?
                show-post-only?
                show-scale-preview?
                show-liquidation-row?
                show-slippage-row?]}
        controls]
    [:div {:class ["bg-base-100"
                   "border"
                   "border-base-300"
                   "rounded-none"
                   "shadow-none"
                   "p-3"
                   "font-sans"
                   "min-h-[560px]"
                   "xl:min-h-[640px]"
                   "flex"
                   "flex-col"
                   "gap-3"]
           :data-parity-id "order-form"}
     (when spot?
       (unsupported-market-banner "Spot trading is not supported yet. You can still view spot charts and order books."))

     [:div {:class (into ["flex" "flex-col" "flex-1" "gap-3"]
                         (when read-only? ["opacity-60" "pointer-events-none"]))}
      (leverage-row ui-leverage next-leverage leverage-handlers)

      (sections/entry-mode-tabs {:entry-mode entry-mode
                                 :type type
                                 :pro-dropdown-open? pro-dropdown-open?
                                 :pro-tab-label pro-tab-label
                                 :pro-dropdown-options pro-dropdown-options
                                 :order-type-label order-form-vm/order-type-label}
                                entry-mode-handlers)

      (side-row side side-handlers)
      (balances-row display)

      (when show-limit-like-controls?
        (primitives/row-input (:display price)
                              (str "Price (" quote-symbol ")")
                              (:on-change price-handlers)
                              (price-context-accessory (:context price)
                                                       (:on-set-to-mid price-handlers))
                              :input-padding-right "pr-14"
                              :on-focus (:on-focus price-handlers)
                              :on-blur (:on-blur price-handlers)))

      (size-row {:size-display size-display
                 :size-input-mode size-input-mode
                 :quote-symbol quote-symbol
                 :base-symbol base-symbol
                 :size-unit-dropdown-open? size-unit-dropdown-open?
                 :display-size-percent display-size-percent
                 :size-percent size-percent
                 :notch-overlap-threshold notch-overlap-threshold}
                size-handlers)

      (sections/render-order-type-sections type
                                           form
                                           section-handlers)

      [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
       (primitives/row-toggle "Reduce Only"
                              (:reduce-only form)
                              (:on-toggle-reduce-only toggle-handlers))
       (when show-limit-like-controls?
         (sections/tif-inline-control form
                                      tif-handlers))]

      (when show-tpsl-toggle?
        (primitives/row-toggle "Take Profit / Stop Loss"
                               show-tpsl-panel?
                               (:on-toggle-tpsl-panel toggle-handlers)))

      (when show-tpsl-panel?
        (sections/tp-sl-panel form tp-sl-handlers))

      (when show-post-only?
        (primitives/row-toggle "Post Only"
                               (:post-only form)
                               (:on-toggle-post-only toggle-handlers)))

      [:div {:class ["flex-1"]}]

      (when show-scale-preview?
        [:div {:class ["space-y-1.5"]}
         (primitives/metric-row "Start" (:start scale-preview-lines))
         (primitives/metric-row "End" (:end scale-preview-lines))])

      (when error
        [:div {:class ["text-xs" "text-red-400"]} error])

      (submit-row {:submitting? submitting?
                   :submit-disabled? (:disabled? submit)
                   :submit-tooltip (:tooltip submit)
                   :on-submit (:on-submit submit-handlers)})

      (footer-metrics display show-liquidation-row? show-slippage-row?)]]))
