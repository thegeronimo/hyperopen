(ns hyperopen.views.trade.order-form-view
  (:require [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-tpsl-policy :as tpsl-policy]
            [hyperopen.views.trade.order-form-component-primitives :as primitives]
            [hyperopen.views.trade.order-form-component-sections :as sections]
            [hyperopen.views.trade.order-form-handlers :as handlers]
            [hyperopen.views.trade.order-form-vm :as order-form-vm]))

(defn- unsupported-market-banner [message]
  [:div {:class ["px-3" "py-2" "bg-base-200" "border" "border-base-300" "rounded-lg" "text-xs" "text-gray-300"]}
   message])

(defn- twap-runtime-label [total-minutes]
  (if (number? total-minutes)
    (let [hours (quot total-minutes 60)
          minutes (mod total-minutes 60)]
      (cond
        (and (pos? hours) (pos? minutes)) (str hours "h " minutes "m")
        (pos? hours) (str hours "h")
        :else (str minutes "m")))
    "--"))

(defn- twap-preview [state form base-symbol]
  (let [total-minutes (trading/twap-total-minutes (get-in form [:twap]))
        order-count (trading/twap-suborder-count total-minutes)
        suborder-size (trading/twap-suborder-size (:size form) total-minutes)]
    {:runtime (twap-runtime-label total-minutes)
     :frequency (str trading/twap-frequency-seconds "s")
     :order-count (if (number? order-count) (str order-count) "--")
     :size-per-suborder (if (number? suborder-size)
                          (str (trading/base-size-string state suborder-size)
                               " "
                               base-symbol)
                          "--")}))

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

(defn- margin-mode-label [mode]
  (if (= mode :isolated) "Isolated" "Cross"))

(defn- margin-mode-option [mode selected? on-select-mode]
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
   (margin-mode-label mode)])

(defn- margin-mode-static-chip [margin-mode]
  [:div {:class ["flex"
                 "h-10"
                 "w-full"
                 "items-center"
                 "justify-center"
                 "rounded-lg"
                 "border"
                 "border-base-300"
                 "bg-base-200"
                 "text-sm"
                 "font-semibold"
                 "text-gray-100"]}
   [:span (margin-mode-label margin-mode)]])

(defn- margin-mode-chip
  [margin-mode cross-margin-allowed? dropdown-open? leverage-handlers]
  (if-not cross-margin-allowed?
    [:div {:class ["flex-1"]}
     (margin-mode-static-chip :isolated)]
    [:div {:class ["relative" "flex-1"]
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
                 :aria-label "Close margin mode menu"
                 :on {:click (:on-close-margin-mode-dropdown leverage-handlers)}}])
     [:button {:type "button"
               :class ["relative"
                       "flex"
                       "h-10"
                       "w-full"
                       "items-center"
                       "justify-center"
                       "gap-1"
                       "rounded-lg"
                       "border"
                       "border-base-300"
                       "bg-base-200"
                       "text-sm"
                       "font-semibold"
                       "text-gray-100"
                       "transition-colors"
                       "outline-none"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"
                       "focus:shadow-none"]
               :aria-label "Margin mode"
               :aria-haspopup "listbox"
               :aria-expanded (boolean dropdown-open?)
               :style (when dropdown-open?
                        {:z-index 1201})
               :on {:click (:on-toggle-margin-mode-dropdown leverage-handlers)
                    :keydown (:on-margin-mode-dropdown-keydown leverage-handlers)}}
      [:span (margin-mode-label margin-mode)]
      [:svg {:class (into ["pointer-events-none"
                           "h-3.5"
                           "w-3.5"
                           "text-gray-400"
                           "transition-transform"
                           "duration-150"
                           "ease-out"]
                          (if dropdown-open?
                            ["rotate-180"]
                            ["rotate-0"]))
             :viewBox "0 0 20 20"
             :fill "currentColor"}
       [:path {:fill-rule "evenodd"
               :clip-rule "evenodd"
               :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]]
     [:div {:class ["ui-dropdown-panel"
                    "absolute"
                    "left-0"
                    "top-full"
                    "mt-1"
                    "min-w-[116px]"
                    "rounded-lg"
                    "border"
                    "border-[#273035]"
                    "bg-[#1B2429]"
                    "p-1"
                    "spectate-[0_10px_24px_rgba(0,0,0,0.35)]"]
            :style {:z-index 1202
                    :--ui-dropdown-origin "top left"}
            :data-ui-state (if dropdown-open? "open" "closed")
            :role "listbox"
            :aria-label "Margin mode options"
            :aria-hidden (not dropdown-open?)
            :on {:keydown (:on-margin-mode-dropdown-keydown leverage-handlers)}}
      (margin-mode-option :cross
                          (= margin-mode :cross)
                          (:on-select-margin-mode leverage-handlers))
      (margin-mode-option :isolated
                          (= margin-mode :isolated)
                          (:on-select-margin-mode leverage-handlers))]]))

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
      [:svg {:class (into ["pointer-events-none"
                           "h-3.5"
                           "w-3.5"
                           "text-gray-400"
                           "transition-transform"
                           "duration-150"
                           "ease-out"]
                          (if dropdown-open?
                            ["rotate-180"]
                            ["rotate-0"]))
             :viewBox "0 0 20 20"
             :fill "currentColor"}
       [:path {:fill-rule "evenodd"
               :clip-rule "evenodd"
               :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]]
     [:div {:class ["ui-dropdown-panel"
                    "absolute"
                    "right-0"
                    "top-full"
                    "mt-1"
                    "min-w-[88px]"
                    "rounded-lg"
                    "border"
                    "border-[#273035]"
                    "bg-[#1B2429]"
                    "p-1"
                    "spectate-[0_10px_24px_rgba(0,0,0,0.35)]"]
            :style {:z-index 1202}
            :data-ui-state (if dropdown-open? "open" "closed")
            :role "listbox"
            :aria-label "Size unit options"
            :aria-hidden (not dropdown-open?)
            :on {:keydown on-dropdown-keydown}}
      (size-unit-option :quote quote-symbol (= selected-mode :quote) on-select-mode)
      (size-unit-option :base base-symbol (= selected-mode :base) on-select-mode)]]))

(defn- leverage-control
  [state ui-leverage leverage-draft max-leverage popover-open? leverage-handlers]
  (let [max-leverage* (-> (or (trading/parse-num max-leverage)
                              (trading/parse-num ui-leverage)
                              1)
                          js/Math.round
                          int
                          (max 1))
        draft* (trading/normalize-ui-leverage state
                                              (or leverage-draft ui-leverage))
        displayed-leverage (if popover-open?
                             draft*
                             ui-leverage)
        slider-progress (if (> max-leverage* 1)
                          (* 100 (/ (- draft* 1)
                                    (- max-leverage* 1)))
                          100)]
    [:div {:class ["relative" "flex-1"]
           :style (when popover-open?
                    {:z-index 1200})}
     (when popover-open?
       [:button {:type "button"
                 :class ["absolute" "bg-transparent" "cursor-default"]
                 :style {:left "-100vmax"
                         :top "-100vmax"
                         :width "200vmax"
                         :height "200vmax"
                         :z-index 1200}
                 :aria-label "Close leverage menu"
                 :on {:click (:on-close-leverage-popover leverage-handlers)}}])
     [:button {:type "button"
               :class ["relative"
                       "flex"
                       "h-10"
                       "w-full"
                       "items-center"
                       "justify-between"
                       "gap-1.5"
                       "rounded-lg"
                       "border"
                       "border-base-300"
                       "bg-base-200"
                       "px-3"
                       "text-sm"
                       "font-semibold"
                       "text-gray-100"
                       "transition-colors"
                       "outline-none"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"
                       "focus:shadow-none"]
               :aria-label "Adjust leverage"
               :aria-haspopup "dialog"
               :aria-expanded (boolean popover-open?)
               :style (when popover-open?
                        {:z-index 1201})
               :on {:click (:on-toggle-leverage-popover leverage-handlers)
                    :keydown (:on-leverage-popover-keydown leverage-handlers)}}
      [:span {:class ["num"]} (str displayed-leverage "x")]
      [:svg {:class ["pointer-events-none"
                     "h-3.5"
                     "w-3.5"
                     "text-gray-400"
                     "transition-transform"
                     "duration-150"
                     "ease-out"
                     (when popover-open? "rotate-180")]
             :viewBox "0 0 16 16"
             :fill "none"
             :aria-hidden true}
       [:path {:d "M4 6.5L8 10.5L12 6.5"
               :stroke "currentColor"
               :stroke-width "1.5"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]]]
     [:div {:class ["ui-dropdown-panel"
                    "absolute"
                    "right-0"
                    "top-full"
                    "mt-2"
                    "w-[320px]"
                    "rounded-xl"
                    "border"
                    "border-base-300"
                    "bg-base-100"
                    "p-3"
                    "spectate-[0_18px_36px_rgba(0,0,0,0.35)]"
                    "space-y-2.5"]
            :style {:z-index 1202}
            :data-ui-state (if popover-open? "open" "closed")
            :role "dialog"
            :aria-label "Adjust Leverage"
            :aria-hidden (not popover-open?)
            :on {:keydown (:on-leverage-popover-keydown leverage-handlers)}}
      [:div {:class ["text-sm" "font-semibold" "text-gray-100"]} "Adjust Leverage"]
      [:div {:class ["space-y-1" "text-xs"]}
       [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
        [:span {:class ["text-gray-400"]} "Maximum leverage"]
        [:span {:class ["font-semibold" "text-gray-100" "num"]} (str max-leverage* "x")]]
       [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
        [:span {:class ["text-gray-400"]} "Max position size"]
        [:span {:class ["font-semibold" "text-gray-100" "num"]} "N/A"]]]
      [:div {:class ["pt-1" "flex" "items-center" "gap-2"]}
       [:input {:class ["leverage-adjust-slider" "range" "range-sm" "w-full" "flex-1"]
                :type "range"
                :min 1
                :max max-leverage*
                :step 1
                :value draft*
                :style {:--leverage-adjust-slider-progress (str slider-progress "%")}
                :aria-label "Leverage slider"
                :on {:input (:on-set-leverage-draft leverage-handlers)}}]
       [:div {:class ["relative" "w-[92px]" "shrink-0"]}
        [:input {:class (into ["h-10"
                               "w-full"
                               "rounded-lg"
                               "border"
                               "border-base-300"
                               "bg-base-200"
                               "px-3"
                               "pr-7"
                               "text-center"
                               "text-base"
                               "font-semibold"
                               "text-gray-100"
                               "num"
                               "appearance-none"]
                              primitives/neutral-input-focus-classes)
                 :type "text"
                 :inputmode "numeric"
                 :pattern "[0-9]*"
                 :value draft*
                 :aria-label "Leverage value"
                 :on {:input (:on-set-leverage-draft leverage-handlers)}}]
        [:button {:type "button"
                  :class ["absolute"
                          "right-1"
                          "top-1/2"
                          "-translate-y-1/2"
                          "h-8"
                          "w-6"
                          "rounded-md"
                          "text-sm"
                          "font-semibold"
                          "text-gray-400"
                          "hover:bg-base-300"
                          "hover:text-gray-100"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                  :aria-label "Close leverage menu"
                  :on {:click (:on-close-leverage-popover leverage-handlers)}}
         "x"]]
       ]
      [:button {:type "button"
                :class ["h-10"
                        "w-full"
                        "rounded-lg"
                        "border"
                        "border-base-300"
                        "bg-base-200"
                        "px-3"
                        "text-sm"
                        "font-semibold"
                        "text-gray-100"
                        "transition-colors"
                        "hover:bg-base-300"
                        "focus:outline-none"
                        "focus:ring-0"
                        "focus:ring-offset-0"]
                :on {:click (:on-confirm-leverage leverage-handlers)}}
       "Confirm"]]]))

(defn- leverage-row
  [state margin-mode cross-margin-allowed? margin-mode-dropdown-open? leverage-popover-open? ui-leverage leverage-draft max-leverage leverage-handlers]
  (let [mode (trading/effective-margin-mode state margin-mode)
        dropdown-open? (and cross-margin-allowed?
                            margin-mode-dropdown-open?)]
    [:div {:class ["grid" "grid-cols-3" "gap-1.5" "sm:gap-2"]}
     (margin-mode-chip mode cross-margin-allowed? dropdown-open? leverage-handlers)
     (leverage-control state
                       ui-leverage
                       leverage-draft
                       max-leverage
                       leverage-popover-open?
                       leverage-handlers)
     (primitives/chip-button "Classic" true :disabled? true)]))

(defn- side-row [side side-handlers]
  [:div {:class ["flex" "h-[33px]" "items-center" "gap-1.5" "rounded-lg" "bg-base-200" "p-0.5" "sm:gap-2"]}
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
    [:span {:class ["text-xs" "text-gray-400" "sm:text-sm"]} "Available to Trade"]
    [:span {:class ["text-xs" "font-semibold" "text-gray-100" "num" "sm:text-sm"]}
     (:available-to-trade display)]]
   [:div {:class ["flex" "items-center" "justify-between"]}
    [:span {:class ["text-xs" "text-gray-400" "sm:text-sm"]} "Current position"]
    [:span {:class ["text-xs" "font-semibold" "text-gray-100" "num" "sm:text-sm"]}
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
  [:div {:class ["space-y-1.5" "sm:space-y-2"]}
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

(defn- tpsl-panel-model
  [state form side ui-leverage controls]
  (let [ui-state (trading/order-form-ui-state state)
        pricing-policy (trading/order-price-policy state form ui-state)
        limit-like? (boolean (:show-limit-like-controls? controls))
        unit (tpsl-policy/normalize-unit (get-in form [:tpsl :unit]))
        baseline (tpsl-policy/baseline-price form pricing-policy limit-like?)
        size (trading/parse-num (:size form))
        leverage (trading/parse-num ui-leverage)
        tp-inverse (tpsl-policy/inverse-for-leg side :tp)
        sl-inverse (tpsl-policy/inverse-for-leg side :sl)]
    {:form form
     :unit unit
     :unit-dropdown-open? (boolean (:tpsl-unit-dropdown-open? ui-state))
     :tp-offset (tpsl-policy/offset-display {:offset-input (get-in form [:tp :offset-input])
                                             :trigger (get-in form [:tp :trigger])
                                             :baseline baseline
                                             :size size
                                             :leverage leverage
                                             :inverse tp-inverse
                                             :unit unit})
     :sl-offset (tpsl-policy/offset-display {:offset-input (get-in form [:sl :offset-input])
                                             :trigger (get-in form [:sl :trigger])
                                             :baseline baseline
                                             :size size
                                             :leverage leverage
                                             :inverse sl-inverse
                                             :unit unit})}))

(defn- submit-row [{:keys [submitting? submit-disabled? submit-tooltip on-submit]}]
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
                           "focus:ring-[#8a96a6]/40"
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

(defn- spectate-mode-icon
  [size-classes]
  [:svg {:viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.9"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :class size-classes
         :aria-hidden "true"}
   [:path {:d "M9 10h.01"}]
   [:path {:d "M15 10h.01"}]
   [:path {:d "M12 2a7 7 0 0 0-7 7v10l2-2 2 2 2-2 2 2 2-2 2 2V9a7 7 0 0 0-7-7z"}]])

(defn- spectate-mode-stop-affordance []
  [:div {:data-role "order-form-spectate-mode-stop"}
   [:button {:type "button"
             :class ["flex"
                     "h-9"
                     "w-full"
                     "items-center"
                     "justify-between"
                     "gap-2"
                     "rounded-lg"
                     "border"
                     "border-[#2f7067]"
                     "bg-[#0f433d]/25"
                     "px-3"
                     "text-sm"
                     "font-medium"
                     "text-[#d6f1ed]"
                     "transition-colors"
                     "hover:bg-[#0f433d]/45"
                     "focus:outline-none"
                     "focus:ring-1"
                     "focus:ring-[#87c8c0]/40"
                     "focus:ring-offset-0"]
             :on {:click [[:actions/stop-spectate-mode]]}
             :data-role "order-form-spectate-mode-stop-button"}
    [:span {:class ["inline-flex" "min-w-0" "items-center" "gap-2"]}
     (spectate-mode-icon ["h-5" "w-5" "shrink-0"])
     [:span {:class ["truncate"]} "Stop Spectate Mode"]]
    [:span {:class ["shrink-0"
                    "rounded-[4px]"
                    "border"
                    "border-[#2f7067]"
                    "bg-[#0f433d]"
                    "px-1.5"
                    "py-0.5"
                    "text-xs"
                    "font-semibold"
                    "uppercase"
                    "leading-none"
                    "tracking-[0.04em]"
                    "text-[#c2e5e0]"]}
     "⌘⇧X"]]])

(def ^:private liquidation-price-tooltip
  "Position risk is low, so there is no liquidation price for the time being. Note that increasing the position or reducing the margin will increase the risk.")

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

(defn- fee-row-copy [fees-display]
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

(defn- footer-metrics
  [display show-liquidation-row? show-slippage-row? fee-copy scale-preview-lines]
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
                              (:order-value display))
       (primitives/metric-row "Margin Required"
                              (:margin-required display))]
      (when show-slippage-row?
        [(primitives/metric-row "Slippage"
                                (:slippage display)
                                "text-primary")])
      [(fees-row (:fees display) fee-copy)]))))

(defn render-order-form
  [{:keys [state vm handlers ui]}]
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
                submit]} vm
        margin-mode-dropdown-open? (boolean (or (:margin-mode-dropdown-open? ui)
                                                (get-in state [:order-form-ui :margin-mode-dropdown-open?])))
        leverage-popover-open? (boolean (or (:leverage-popover-open? ui)
                                            (get-in state [:order-form-ui :leverage-popover-open?])))
        leverage-draft (or (:leverage-draft ui)
                           (get-in state [:order-form-ui :leverage-draft]))
        size-unit-dropdown-open? (boolean (or (:size-unit-dropdown-open? ui)
                                              (get-in state [:order-form-ui :size-unit-dropdown-open?])))
        tif-dropdown-open? (boolean (or (:tif-dropdown-open? ui)
                                        (get-in state [:order-form-ui :tif-dropdown-open?])))
        max-leverage (or (:max-leverage ui)
                         (trading/market-max-leverage state))
        cross-margin-allowed? (if (contains? ui :cross-margin-allowed?)
                                (:cross-margin-allowed? ui)
                                (trading/cross-margin-allowed? state))
        entry-mode-handlers (:entry-mode handlers)
        leverage-handlers (:leverage handlers)
        side-handlers (:side handlers)
        price-handlers (:price handlers)
        size-handlers (:size handlers)
        section-handlers (assoc (:order-type-sections handlers)
                                :twap-preview (twap-preview state form base-symbol))
        toggle-handlers (:toggles handlers)
        tif-handlers (:tif handlers)
        tp-sl-handlers (:tp-sl handlers)
        submit-handlers (:submit handlers)
        fee-copy (fee-row-copy (:fees display))
        {:keys [show-limit-like-controls?
                show-tpsl-toggle?
                show-tpsl-panel?
                show-post-only?
                show-scale-preview?
                show-liquidation-row?
                show-slippage-row?]}
        controls
        spectate-mode-read-only? (= :spectate-mode-read-only (:reason submit))
        tpsl-panel (tpsl-panel-model state form side ui-leverage controls)]
    [:div {:class ["bg-base-100"
                   "border"
                   "border-base-300"
                   "rounded-none"
                   "spectate-none"
                   "p-2.5"
                   "sm:p-3"
                   "font-sans"
                   "flex"
                   "flex-col"
                   "gap-2"
                   "sm:gap-2.5"]
           :data-parity-id "order-form"}
     (when spot?
       (unsupported-market-banner "Spot trading is not supported yet. You can still view spot charts and order books."))

     [:div {:class (into ["flex" "flex-col" "gap-2" "sm:gap-2.5"]
                         (when read-only? ["opacity-60" "pointer-events-none"]))}
      (leverage-row state
                    (:margin-mode form)
                    cross-margin-allowed?
                    margin-mode-dropdown-open?
                    leverage-popover-open?
                    ui-leverage
                    leverage-draft
                    max-leverage
                    leverage-handlers)

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
                                      (assoc tif-handlers
                                             :dropdown-open? tif-dropdown-open?)))]

      (when show-tpsl-toggle?
        (primitives/row-toggle "Take Profit / Stop Loss"
                               show-tpsl-panel?
                               (:on-toggle-tpsl-panel toggle-handlers)))

      (when show-tpsl-panel?
        (sections/tp-sl-panel tpsl-panel tp-sl-handlers))

      (when show-post-only?
        (primitives/row-toggle "Post Only"
                               (:post-only form)
                               (:on-toggle-post-only toggle-handlers)))

      (when error
        [:div {:class ["text-xs" "text-red-400"]} error])

      (when spectate-mode-read-only?
        (spectate-mode-stop-affordance))

      (when-not spectate-mode-read-only?
        (submit-row {:submitting? submitting?
                     :submit-disabled? (:disabled? submit)
                     :submit-tooltip (:tooltip submit)
                     :on-submit (:on-submit submit-handlers)}))

      (footer-metrics display
                      show-liquidation-row?
                      show-slippage-row?
                      fee-copy
                      (when show-scale-preview? scale-preview-lines))]]))

(defn order-form-view [state]
  (render-order-form
   {:state state
    :vm (order-form-vm/order-form-vm state)
    :handlers (handlers/build-handlers)
    :ui {:margin-mode-dropdown-open? (boolean (get-in state [:order-form-ui :margin-mode-dropdown-open?]))
         :leverage-popover-open? (boolean (get-in state [:order-form-ui :leverage-popover-open?]))
         :leverage-draft (get-in state [:order-form-ui :leverage-draft])
         :size-unit-dropdown-open? (boolean (get-in state [:order-form-ui :size-unit-dropdown-open?]))
         :tif-dropdown-open? (boolean (get-in state [:order-form-ui :tif-dropdown-open?]))
         :max-leverage (trading/market-max-leverage state)
         :cross-margin-allowed? (trading/cross-margin-allowed? state)}}))
