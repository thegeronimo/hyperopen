(ns hyperopen.views.trade.order-form-view
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.trade.order-form-commands :as cmd]
            [hyperopen.views.trade.order-form-components :as components]
            [hyperopen.views.trade.order-form-vm :as order-form-vm]))

(defn- format-usdc [value]
  (if (and (number? value) (not (js/isNaN value)))
    (str (.toLocaleString (js/Number. value) "en-US"
                          #js {:minimumFractionDigits 2
                               :maximumFractionDigits 2})
         " USDC")
    "N/A"))

(defn- format-position-label [position sz-decimals]
  (let [size (:abs-size position)
        coin (:coin position)]
    (if (and (number? size) (pos? size) (seq coin))
      (str (.toLocaleString (js/Number. size) "en-US"
                            #js {:minimumFractionDigits (or sz-decimals 4)
                                 :maximumFractionDigits (or sz-decimals 4)})
           " "
           coin)
      (str "0.0000 " (or coin "--")))))

(defn- format-percent
  ([value]
   (format-percent value 2))
  ([value decimals]
   (if (and (number? value) (not (js/isNaN value)))
     (str (fmt/safe-to-fixed value decimals) "%")
     "N/A")))

(defn- price-context-accessory [{:keys [label mid-available?]}]
  [:button {:type "button"
            :disabled (not mid-available?)
            :class (into ["text-xs" "font-semibold" "transition-colors"]
                         (if mid-available?
                           ["text-primary" "cursor-pointer" "hover:text-primary/80"]
                           ["text-gray-500" "cursor-default"]))
            :on (when mid-available?
                  {:click (cmd/set-order-price-to-mid)})}
   (or label "Ref")])

(defn order-form-view [state]
  (let [{:keys [form
                side
                type
                entry-mode
                pro-dropdown-open?
                pro-dropdown-options
                pro-tab-label
                order-type-sections
                pro-mode?
                tpsl-panel-open?
                show-limit-like-controls?
                limit-like?
                spot?
                hip3?
                read-only?
                summary
                ui-leverage
                next-leverage
                size-percent
                display-size-percent
                notch-overlap-threshold
                size-display
                price
                quote-symbol
                scale-preview-lines
                order-value
                margin-required
                liq-price
                slippage-est
                slippage-max
                fees
                error
                submitting?
                submit]}
        (order-form-vm/order-form-vm state)
        available-to-trade (:available-to-trade summary)
        position (:current-position summary)
        sz-decimals (or (get-in state [:active-market :szDecimals]) 4)
        display-price (:display price)
        price-context (:context price)
        start-preview-line (:start scale-preview-lines)
        end-preview-line (:end scale-preview-lines)
        submit-tooltip (:tooltip submit)
        submit-disabled? (:disabled? submit)]
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
                   "gap-3"]}
     (when spot?
       [:div {:class ["px-3" "py-2" "bg-base-200" "border" "border-base-300" "rounded-lg" "text-xs" "text-gray-300"]}
        "Spot trading is not supported yet. You can still view spot charts and order books."])
     (when hip3?
       [:div {:class ["px-3" "py-2" "bg-base-200" "border" "border-base-300" "rounded-lg" "text-xs" "text-gray-300"]}
        "HIP-3 trading is not supported yet. You can still view these markets."])

     [:div {:class (into ["flex" "flex-col" "flex-1" "gap-3"]
                         (when read-only? ["opacity-60" "pointer-events-none"]))}
      [:div {:class ["grid" "grid-cols-3" "gap-2"]}
       (components/chip-button "Cross" true :disabled? true)
       (components/chip-button (str ui-leverage "x")
                               true
                               :on-click (cmd/set-order-ui-leverage next-leverage))
       (components/chip-button "Classic" true :disabled? true)]

      (components/entry-mode-tabs {:entry-mode entry-mode
                                   :type type
                                   :pro-dropdown-open? pro-dropdown-open?
                                   :pro-tab-label pro-tab-label
                                   :pro-dropdown-options pro-dropdown-options
                                   :order-type-label order-form-vm/order-type-label})

      [:div {:class ["flex" "items-center" "gap-2" "bg-base-200" "rounded-md" "p-1"]}
       (components/side-button "Buy / Long"
                               :buy
                               (= side :buy)
                               (cmd/update-order-form [:side] :buy))
       (components/side-button "Sell / Short"
                               :sell
                               (= side :sell)
                               (cmd/update-order-form [:side] :sell))]

      [:div {:class ["space-y-1.5"]}
       [:div {:class ["flex" "items-center" "justify-between"]}
        [:span {:class ["text-sm" "text-gray-400"]} "Available to Trade"]
        [:span {:class ["text-sm" "font-semibold" "text-gray-100" "num"]}
         (format-usdc available-to-trade)]]
       [:div {:class ["flex" "items-center" "justify-between"]}
        [:span {:class ["text-sm" "text-gray-400"]} "Current position"]
        [:span {:class ["text-sm" "font-semibold" "text-gray-100" "num"]}
         (format-position-label position sz-decimals)]]]

      (when show-limit-like-controls?
        (components/row-input display-price
                              (str "Price (" quote-symbol ")")
                              (cmd/update-order-form [:price] [:event.target/value])
                              (price-context-accessory price-context)
                              :input-padding-right "pr-14"
                              :on-focus (cmd/focus-order-price-input)
                              :on-blur (cmd/blur-order-price-input)))

      (components/row-input size-display
                            "Size"
                            (cmd/set-order-size-display [:event.target/value])
                            (components/quote-accessory quote-symbol))

      [:div {:class ["flex" "items-center" "gap-2"]}
       [:div {:class ["relative" "flex-1"]}
        [:input {:class ["order-size-slider" "range" "range-sm" "w-full" "relative" "z-20"]
                 :type "range"
                 :min 0
                 :max 100
                 :step 1
                 :style {:--order-size-slider-progress (str size-percent "%")}
                 :value size-percent
                 :on {:input (cmd/set-order-size-percent [:event.target/value])}}]
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
                              components/neutral-input-focus-classes)
                 :type "text"
                 :inputmode "numeric"
                 :pattern "[0-9]*"
                 :value display-size-percent
                 :on {:input (cmd/set-order-size-percent [:event.target/value])}}]
        [:span {:class ["pointer-events-none"
                        "absolute"
                        "right-2.5"
                        "top-1/2"
                        "-translate-y-1/2"
                        "text-sm"
                        "font-semibold"
                        "text-gray-300"]}
         "%"]]]

      (for [section order-type-sections]
        ^{:key (str "order-type-section-" (name section))}
        (components/render-order-type-section section form))

      [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
       (components/row-toggle "Reduce Only"
                              (:reduce-only form)
                              (cmd/update-order-form [:reduce-only] [:event.target/checked]))
       (when show-limit-like-controls?
         (components/tif-inline-control form))]

      (when (not= :scale type)
        (components/row-toggle "Take Profit / Stop Loss"
                               tpsl-panel-open?
                               (cmd/toggle-order-tpsl-panel)))

      (when (and (not= :scale type) tpsl-panel-open?)
        (components/tp-sl-panel form))

      (when (and pro-mode? limit-like?)
        (components/row-toggle "Post Only"
                               (:post-only form)
                               (cmd/update-order-form [:post-only] [:event.target/checked])))

      [:div {:class ["flex-1"]}]

      (when (= :scale type)
        [:div {:class ["space-y-1.5"]}
         (components/metric-row "Start" start-preview-line)
         (components/metric-row "End" end-preview-line)])

      (when error
        [:div {:class ["text-xs" "text-red-400"]} error])

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
                 :on {:click (cmd/submit-order)}}
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
          submit-tooltip])]

      [:div {:class ["border-t" "border-base-300" "pt-3" "space-y-2"]}
       (when (not= :scale type)
         (components/metric-row "Liquidation Price"
                                (if liq-price
                                  (or (fmt/format-trade-price liq-price) "N/A")
                                  "N/A")))
       (components/metric-row "Order Value"
                              (if order-value
                                (or (fmt/format-currency order-value) "N/A")
                                "N/A"))
       (components/metric-row "Margin Required"
                              (if margin-required
                                (or (fmt/format-currency margin-required) "N/A")
                                "N/A"))
       (when (= :market type)
         (components/metric-row "Slippage"
                                (str "Est " (format-percent slippage-est 4)
                                     " / Max " (format-percent slippage-max 2))
                                "text-primary"))
       (components/metric-row "Fees"
                              (if (and (number? (:taker fees)) (number? (:maker fees)))
                                (str (fmt/safe-to-fixed (:taker fees) 3)
                                     "% / "
                                     (fmt/safe-to-fixed (:maker fees) 3)
                                     "%")
                                "N/A"))]]]))
