(ns hyperopen.views.trade.order-form-controls
  (:require [hyperopen.state.trading :as trading]
            [hyperopen.views.trade.order-form-component-primitives :as primitives]))

(defn- price-context-accessory [{:keys [label mid-available?]} on-set-to-mid]
  [:button {:type "button"
            :disabled (not mid-available?)
            :aria-label "Set order price to mid"
            :class (into ["inline-flex"
                          "min-h-6"
                          "min-w-6"
                          "items-center"
                          "justify-center"
                          "rounded"
                          "px-1"
                          "text-xs"
                          "font-semibold"
                          "transition-colors"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                         (if mid-available?
                           ["text-primary" "cursor-pointer" "hover:text-primary/80"]
                           ["text-gray-500" "cursor-default"]))
            :on (when mid-available?
                  {:click on-set-to-mid})}
   (or label "Ref")])

(defn price-row [{:keys [display context]} quote-symbol price-handlers]
  (primitives/row-input display
                        (str "Price (" quote-symbol ")")
                        (:on-change price-handlers)
                        (price-context-accessory context
                                                 (:on-set-to-mid price-handlers))
                        :input-padding-right "pr-14"
                        :on-focus (:on-focus price-handlers)
                        :on-blur (:on-blur price-handlers)))

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
               :aria-label (str "Margin mode: " (margin-mode-label margin-mode))
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
               :aria-label (str "Size unit: " selected-label)
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
               :aria-label (str "Adjust leverage: " displayed-leverage "x")
               :aria-haspopup "dialog"
               :aria-expanded (boolean popover-open?)
               :style (when popover-open?
                        {:z-index 1201})
               :on {:click (:on-toggle-leverage-popover leverage-handlers)
                    :keydown (:on-leverage-popover-keydown leverage-handlers)}}
      [:span {:class ["num"]} (str displayed-leverage "x")]
      [:svg {:class (into ["pointer-events-none"
                           "h-3.5"
                           "w-3.5"
                           "text-gray-400"
                           "transition-transform"
                           "duration-150"
                           "ease-out"]
                          (when popover-open? ["rotate-180"]))
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
         "x"]]]
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

(defn leverage-row
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

(defn side-row
  ([side side-handlers]
   (side-row side side-handlers nil))
  ([side side-handlers {:keys [buy-label sell-label]
                        :or {buy-label "Buy / Long"
                             sell-label "Sell / Short"}}]
   [:div {:class ["flex" "h-[33px]" "items-center" "gap-1.5" "rounded-lg" "bg-base-200" "p-0.5" "sm:gap-2"]}
    (primitives/side-button buy-label :buy (= side :buy) ((:on-select-side side-handlers) :buy))
    (primitives/side-button sell-label :sell (= side :sell) ((:on-select-side side-handlers) :sell))]))

(defn- outcome-side-index
  [side]
  (let [value (:side-index side)
        parsed (cond
                 (number? value) value
                 (string? value) (js/parseInt value 10)
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed)))
      (int parsed))))

(defn outcome-side-row
  ([outcome-sides selected-side-index outcome-handlers]
   (outcome-side-row outcome-sides selected-side-index outcome-handlers nil))
  ([outcome-sides selected-side-index outcome-handlers {:keys [action-prefix side->intent]
                                                        :or {action-prefix ""
                                                             side->intent (constantly :buy)}}]
   (when (seq outcome-sides)
     [:div {:class ["flex" "h-[33px]" "items-center" "gap-1.5" "rounded-lg" "bg-base-200" "p-0.5" "sm:gap-2"]}
      (for [side outcome-sides
            :let [side-index (outcome-side-index side)
                  side-label (or (:side-label side) (:side-name side) (str "Side " side-index))
                  intent (side->intent side)]]
        ^{:key (str "outcome-side-" side-index)}
        (primitives/side-button (str action-prefix side-label)
                                intent
                                (= side-index selected-side-index)
                                ((:on-select-outcome-side outcome-handlers) side-index)))])))

(defn balances-row [display]
  [:div {:class ["space-y-1.5"]}
   [:div {:class ["flex" "items-center" "justify-between"]}
    [:span {:class ["text-xs" "text-gray-400" "sm:text-sm"]} "Available to Trade"]
    [:span {:class ["text-xs" "font-semibold" "text-gray-100" "num" "sm:text-sm"]}
     (:available-to-trade display)]]
   [:div {:class ["flex" "items-center" "justify-between"]}
    [:span {:class ["text-xs" "text-gray-400" "sm:text-sm"]} "Current position"]
    [:span {:class ["text-xs" "font-semibold" "text-gray-100" "num" "sm:text-sm"]}
     (:current-position display)]]])

(defn size-row [{:keys [size-display
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
              :aria-label "Order size percentage slider"
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
              :aria-label "Order size percentage input"
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
