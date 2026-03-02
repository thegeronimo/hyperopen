(ns hyperopen.views.account-info.position-tpsl-modal
  (:require [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.utils.parse :as parse-utils]
            [hyperopen.views.account-info.shared :as shared]))

(defn- amount-text [value]
  (if (and (number? value) (not (js/isNaN value)))
    (trading-domain/number->clean-string value 8)
    "0"))

(defn- percent-text [value]
  (if (and (number? value) (not (js/isNaN value)))
    (trading-domain/number->clean-string value 2)
    "0"))

(defn- usd-input-text
  [modal value]
  (let [num-value (cond
                    (number? value) value
                    :else (or (parse-utils/parse-localized-decimal value (:locale modal))
                              (js/parseFloat (str (or value "")))))]
    (if (or (js/isNaN num-value)
            (< (js/Math.abs num-value) 0.00000001))
      "0"
      (shared/format-currency num-value))))

(defn- select-input-value!
  [event]
  (let [target (or (some-> event .-currentTarget)
                   (some-> event .-target))]
    (when (and target (fn? (.-select target)))
      ;; Defer to the next tick so the browser's click caret placement doesn't win.
      (js/setTimeout #(.select target) 0))))

(defn- coin-label [coin]
  (let [parsed (shared/parse-coin-namespace coin)]
    (or (:base parsed)
        (shared/non-blank-text coin)
        "-")))

(defn- metric-row [label value]
  [:div {:class ["flex" "items-center" "justify-between"]}
   [:span {:class ["text-gray-400"]} label]
   [:span {:class ["font-semibold" "text-gray-100" "num"]} value]])

(defn- checkbox-row [id label checked? on-change]
  [:div {:class ["inline-flex" "items-center" "gap-2"]}
   [:input {:id id
            :type "checkbox"
            :class ["h-4"
                    "w-4"
                    "rounded-[3px]"
                    "border"
                    "border-base-300"
                    "bg-transparent"
                    "trade-toggle-checkbox"
                    "transition-colors"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"
                    "focus:shadow-none"]
            :checked (boolean checked?)
            :on {:change on-change}}]
   [:label {:for id
            :class ["cursor-pointer" "select-none" "text-sm" "text-gray-100"]}
    label]])

(def ^:private neutral-input-focus-classes
  ["outline-none"
   "transition-[border-color,box-shadow]"
   "duration-150"
   "hover:border-[#6f7a88]"
   "hover:ring-1"
   "hover:ring-[#6f7a88]/30"
   "hover:ring-offset-0"
   "focus:outline-none"
   "focus:ring-1"
   "focus:ring-[#8a96a6]/40"
   "focus:ring-offset-0"
   "focus:shadow-none"
   "focus:border-[#8a96a6]"])

(def ^:private pnl-mode-options
  [:usd :roe-percent :position-percent])

(defn- immediate-tooltip
  [trigger tooltip-text]
  [:div {:class ["relative" "group" "inline-flex" "w-full"]}
   trigger
   [:div {:class ["pointer-events-none"
                  "absolute"
                  "right-0"
                  "top-full"
                  "z-20"
                  "mt-1.5"
                  "opacity-0"
                  "group-hover:opacity-100"]
          :style {:min-width "max-content"}}
    [:div {:class ["max-w-[16rem]"
                   "rounded"
                   "border"
                   "border-base-300"
                   "bg-base-100"
                   "px-2"
                   "py-1"
                   "text-xs"
                   "leading-4"
                   "text-gray-100"
                   "shadow-lg"]}
     tooltip-text]]])

(defn- pnl-mode-select
  [mode path aria-label]
  (immediate-tooltip
   [:div {:class ["relative" "w-[58px]"]}
    [:select {:class ["h-7"
                      "w-full"
                      "border-0"
                      "bg-transparent"
                      "pl-1"
                      "pr-5"
                      "text-xs"
                      "font-semibold"
                      "text-left"
                      "truncate"
                      "text-gray-100"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"
                      "focus:shadow-none"]
              :aria-label aria-label
              :value (name mode)
              :on {:change [[:actions/set-position-tpsl-modal-field path [:event.target/value]]]}}
     (for [option-mode pnl-mode-options]
       ^{:key (name option-mode)}
       [:option {:value (name option-mode)}
        (position-tpsl/pnl-mode-option-label option-mode)])]]
   (position-tpsl/pnl-mode-menu-label mode)))

(defn- input-row
  ([label value action]
   (input-row label value action {}))
  ([label value action {:keys [unit-control select-on-focus?]}]
   [:div {:class ["relative" "w-full"]}
    [:span {:class ["pointer-events-none"
                    "absolute"
                    "left-3"
                    "top-1/2"
                    "-translate-y-1/2"
                    "text-sm"
                    "text-gray-500"]}
     label]
    [:input (cond-> {:class (into ["h-10"
                                   "w-full"
                                   "rounded-lg"
                                   "border"
                                   "border-base-300"
                                   "bg-base-200"
                                   "pl-24"
                                   (if unit-control "pr-[64px]" "pr-3")
                                   "text-right"
                                   "text-sm"
                                   "font-semibold"
                                   "text-gray-100"
                                   "num"]
                                  neutral-input-focus-classes)
                     :type "text"
                     :value (or value "")}
              (some? action) (assoc :on {:input action})
              (nil? action) (assoc :readonly true)
              select-on-focus? (update :on (fnil merge {})
                                       {:focus select-input-value!
                                        :click select-input-value!}))]
    (when unit-control
      [:div {:class ["absolute"
                     "right-2"
                     "top-1/2"
                     "-translate-y-1/2"
                     "z-10"]}
       unit-control])]))

(defn- configure-amount-input-row
  [size-input coin]
  [:div {:class ["relative" "w-full"]}
   [:div {:class ["absolute"
                  "left-3"
                  "top-1/2"
                  "-translate-y-1/2"
                  "z-10"
                  "flex"
                  "items-center"
                  "gap-1.5"]}
    [:span {:class ["pointer-events-none" "text-sm" "text-gray-500"]}
     "Amount"]
    [:button {:type "button"
              :class ["inline-flex"
                      "h-6"
                      "min-w-6"
                      "items-center"
                      "justify-center"
                      "rounded-md"
                      "border"
                      "border-base-300"
                      "bg-base-200"
                      "px-1.5"
                      "text-xs"
                      "font-semibold"
                      "text-gray-300"
                      "hover:bg-base-300"
                      "hover:text-gray-100"
                      "focus:outline-none"
                      "focus:ring-1"
                      "focus:ring-[#8a96a6]/40"
                      "focus:ring-offset-0"
                      "focus:shadow-none"]
              :on {:click [[:actions/set-position-tpsl-modal-field [:size-percent-input] "100"]]}}
     "MAX"]]
   [:input {:class (into ["h-10"
                          "w-full"
                          "rounded-lg"
                          "border"
                          "border-base-300"
                          "bg-base-200"
                          "pl-32"
                          "pr-12"
                          "text-right"
                          "text-sm"
                          "font-semibold"
                          "text-gray-100"
                          "num"]
                         neutral-input-focus-classes)
            :type "text"
            :value (or size-input "")
            :on {:input [[:actions/set-position-tpsl-modal-field [:size-input] [:event.target/value]]]
                 :focus select-input-value!
                 :click select-input-value!}}]
   [:div {:class ["pointer-events-none"
                  "absolute"
                  "right-2.5"
                  "top-1/2"
                  "-translate-y-1/2"
                  "flex"
                  "items-center"
                  "gap-1"]}
    [:span {:class ["text-sm" "font-semibold" "text-gray-400"]} coin]]])

(def ^:private size-slider-notch-overlap-threshold 2)
(def ^:private size-slider-notch-values [0 25 50 75 100])

(defn- configure-amount-controls
  [{:keys [size-input size-percent-input limit-price?]} coin configure-size-percent]
  (let [slider-percent (js/Math.round configure-size-percent)]
    [:div {:class ["space-y-2"]}
     (configure-amount-input-row size-input coin)
     [:div {:class ["flex" "items-center" "gap-2"]}
      [:div {:class ["relative" "flex-1"]}
       [:input {:class ["order-size-slider" "range" "range-sm" "w-full" "relative" "z-20"]
                :type "range"
                :min 0
                :max 100
                :step 1
                :style {:--order-size-slider-progress (str slider-percent "%")}
                :value slider-percent
                :on {:input [[:actions/set-position-tpsl-modal-field [:size-percent-input] [:event.target/value]]]}}]
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
        (for [pct size-slider-notch-values]
          ^{:key (str "position-tpsl-size-slider-notch-" pct)}
          [:span {:class (into ["order-size-slider-notch"
                                "block"
                                "h-[7px]"
                                "w-[7px]"
                                "-translate-y-1/2"
                                "rounded-full"]
                               (remove nil?
                                       [(if (>= slider-percent pct)
                                          "order-size-slider-notch-active"
                                          "order-size-slider-notch-inactive")
                                        (when (<= (js/Math.abs (- slider-percent pct))
                                                  size-slider-notch-overlap-threshold)
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
                             neutral-input-focus-classes)
                :type "text"
                :inputmode "decimal"
                :value (or size-percent-input "")
                :on {:input [[:actions/set-position-tpsl-modal-field [:size-percent-input] [:event.target/value]]]}}]
       [:span {:class ["pointer-events-none"
                       "absolute"
                       "right-2.5"
                       "top-1/2"
                       "-translate-y-1/2"
                       "text-sm"
                       "font-semibold"
                       "text-gray-300"]}
        "%"]]]
     (checkbox-row "position-tpsl-limit-price"
                   "Limit Price"
                   limit-price?
                   [[:actions/set-position-tpsl-limit-price [:event.target/checked]]])]))

(def ^:private panel-gap-px 8)
(def ^:private panel-margin-px 16)
(def ^:private preferred-panel-width-px 500)
(def ^:private fallback-viewport-width 1280)
(def ^:private fallback-anchor-top 640)

(defn- clamp
  [value min-value max-value]
  (-> value
      (max min-value)
      (min max-value)))

(defn- anchor-number
  [anchor k default]
  (let [value (get anchor k)]
    (if (number? value)
      value
      default)))

(defn- modal-layout-style
  [modal]
  (let [anchor (or (:anchor modal) {})
        viewport-width (max 320
                           (anchor-number anchor :viewport-width fallback-viewport-width)
                           (+ (anchor-number anchor :right 0) panel-margin-px))
        anchor-right (anchor-number anchor :right (- viewport-width panel-margin-px))
        anchor-top (anchor-number anchor :top fallback-anchor-top)
        panel-width (clamp (- viewport-width (* 2 panel-margin-px))
                           280
                           preferred-panel-width-px)
        left (clamp (- anchor-right panel-width)
                    panel-margin-px
                    (- viewport-width panel-width panel-margin-px))
        available-above (max 1 (- anchor-top panel-margin-px panel-gap-px))]
    {:left (str left "px")
     :top (str (- anchor-top panel-gap-px) "px")
     :transform "translateY(-100%)"
     :width (str panel-width "px")
     :max-height (str available-above "px")}))

(defn- expected-pnl-text
  [mode usd-value roe-percent position-percent]
  (case mode
    :usd (str (percent-text position-percent)
              "% Position | "
              (percent-text roe-percent)
              "% ROE")
    :position-percent (str (shared/format-currency usd-value)
                           " USDC | "
                           (percent-text roe-percent)
                           "% ROE")
    (str (shared/format-currency usd-value)
         " USDC | "
         (percent-text position-percent)
         "% Position")))

(defn position-tpsl-modal-view
  [modal]
  (let [modal* (or modal (position-tpsl/default-modal-state))]
    (when (position-tpsl/open? modal*)
      (let [preview (position-tpsl/validate-modal modal*)
            submitting? (boolean (:submitting? modal*))
            submit-label (if submitting?
                           "Submitting..."
                           (:display-message preview))
            submit-disabled? (or submitting?
                               (not (:is-ok preview)))
            coin (coin-label (:coin modal*))
            position-size (:position-size modal*)
            gain (position-tpsl/estimated-gain-usd modal*)
            loss (position-tpsl/estimated-loss-usd modal*)
            gain-roe-percent (position-tpsl/estimated-gain-roe-percent modal*)
            loss-roe-percent (position-tpsl/estimated-loss-roe-percent modal*)
            gain-position-percent (position-tpsl/estimated-gain-position-percent modal*)
            loss-position-percent (position-tpsl/estimated-loss-position-percent modal*)
            gain-mode (position-tpsl/tp-gain-mode modal*)
            loss-mode (position-tpsl/sl-loss-mode modal*)
            configure-size-percent (position-tpsl/configured-size-percent modal*)
            gain-input-value (if (not= gain-mode :usd)
                               (percent-text (position-tpsl/estimated-gain-percent-for-mode modal* gain-mode))
                               (usd-input-text modal* gain))
            loss-input-value (if (not= loss-mode :usd)
                               (percent-text (position-tpsl/estimated-loss-percent-for-mode modal* loss-mode))
                               (usd-input-text modal* loss))
            expected-profit-value (expected-pnl-text gain-mode gain gain-roe-percent gain-position-percent)
            expected-loss-value (expected-pnl-text loss-mode loss loss-roe-percent loss-position-percent)
            layout-style (modal-layout-style modal*)]
        [:div {:class ["fixed"
                       "z-[260]"
                       "rounded-[10px]"
                       "border"
                       "border-base-300"
                       "bg-base-100"
                       "p-4"
                       "text-sm"
                       "shadow-[0_24px_60px_rgba(0,0,0,0.45)]"
                       "space-y-3"
                       "overflow-y-auto"]
               :style layout-style
               :role "dialog"
               :aria-label "Position TP/SL"
               :data-position-tpsl-surface "true"
               :on {:keydown [[:actions/handle-position-tpsl-modal-keydown [:event/key]]]}}
          [:div {:class ["flex" "items-center" "justify-between"]}
           [:h2 {:class ["text-2xl" "font-semibold" "text-gray-100"]} "Position TP/SL"]
           [:button {:type "button"
                     :class ["h-7" "w-7" "rounded-md" "text-gray-400" "hover:bg-base-300" "hover:text-gray-100"]
                     :on {:click [[:actions/close-position-tpsl-modal]]}}
            "x"]]

          [:div {:class ["space-y-1.5"]}
           (metric-row "Asset" coin)
           (metric-row "Size" (str (amount-text position-size) " " coin))
           (metric-row "Value" (str (shared/format-currency (:position-value modal*)) " USDC"))
           (metric-row "Entry Price" (shared/format-trade-price (:entry-price modal*)))
           (metric-row "Mark Price" (shared/format-trade-price (:mark-price modal*)))]

          [:div {:class ["grid" "grid-cols-2" "gap-2"]}
           (input-row "TP Price"
                      (:tp-price modal*)
                      [[:actions/set-position-tpsl-modal-field [:tp-price] [:event.target/value]]])
           (input-row "Gain"
                      gain-input-value
                      [[:actions/set-position-tpsl-modal-field [:tp-gain] [:event.target/value]]]
                      {:unit-control (pnl-mode-select gain-mode
                                                      [:tp-gain-mode]
                                                      "Gain unit")
                       :select-on-focus? true})]

          (when (pos? gain)
            [:div {:class ["flex" "justify-end" "pr-1" "text-sm"]}
             [:span {:class ["text-gray-400"]} "Expected profit:"]
             [:span {:class ["ml-1" "font-semibold" "text-gray-100" "num"]}
              expected-profit-value]])

          (when (boolean (:limit-price? modal*))
            [:div {:class ["grid" "grid-cols-2" "gap-2"]}
             (input-row "TP Limit"
                        (:tp-limit modal*)
                        [[:actions/set-position-tpsl-modal-field [:tp-limit] [:event.target/value]]])
             [:div]])

          [:div {:class ["grid" "grid-cols-2" "gap-2"]}
           (input-row "SL Price"
                      (:sl-price modal*)
                      [[:actions/set-position-tpsl-modal-field [:sl-price] [:event.target/value]]])
           (input-row "Loss"
                      loss-input-value
                      [[:actions/set-position-tpsl-modal-field [:sl-loss] [:event.target/value]]]
                      {:unit-control (pnl-mode-select loss-mode
                                                      [:sl-loss-mode]
                                                      "Loss unit")
                       :select-on-focus? true})]

          (when (pos? loss)
            [:div {:class ["flex" "justify-end" "pr-1" "text-sm"]}
             [:span {:class ["text-gray-400"]} "Expected loss:"]
             [:span {:class ["ml-1" "font-semibold" "text-gray-100" "num"]}
              expected-loss-value]])

          (when (boolean (:limit-price? modal*))
            [:div {:class ["grid" "grid-cols-2" "gap-2"]}
             (input-row "SL Limit"
                        (:sl-limit modal*)
                        [[:actions/set-position-tpsl-modal-field [:sl-limit] [:event.target/value]]])
             [:div]])

          [:div {:class ["space-y-1"]}
           (checkbox-row "position-tpsl-configure-amount"
                         "Configure Amount"
                         (:configure-amount? modal*)
                         [[:actions/set-position-tpsl-configure-amount [:event.target/checked]]])]

          (when (boolean (:configure-amount? modal*))
            (configure-amount-controls modal* coin configure-size-percent))

          (when-not (boolean (:configure-amount? modal*))
            (checkbox-row "position-tpsl-limit-price"
                          "Limit Price"
                          (:limit-price? modal*)
                          [[:actions/set-position-tpsl-limit-price [:event.target/checked]]]))

          (when (seq (:error modal*))
            [:div {:class ["text-xs" "text-[#ED7088]"]} (:error modal*)])

          [:div {:class ["grid" "grid-cols-2" "gap-3" "pt-1"]}
           [:button {:type "button"
                     :class ["h-11"
                             "rounded-lg"
                             "bg-[#74808F]"
                             "text-sm"
                             "font-semibold"
                             "text-[#1A212B]"
                             "hover:bg-[#8893a0]"
                             "disabled:cursor-not-allowed"
                             "disabled:opacity-50"]
                     :disabled submit-disabled?
                     :on {:click [[:actions/submit-position-tpsl]]}}
            submit-label]
           [:button {:type "button"
                     :class ["h-11"
                             "rounded-lg"
                             "border"
                             "border-base-300"
                             "bg-base-200"
                             "text-sm"
                             "font-semibold"
                             "text-gray-100"
                             "hover:bg-base-300"]
                     :on {:click [[:actions/close-position-tpsl-modal]]}}
            "Close"]]]))))
