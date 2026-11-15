(ns hyperopen.views.account-info.position-tpsl-modal.fields
  (:require [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.utils.parse :as parse-utils]
            [hyperopen.views.account-info.shared :as shared]))

(defn amount-text [value]
  (if (and (number? value) (not (js/isNaN value)))
    (trading-domain/number->clean-string value 8)
    "0"))

(defn percent-text [value]
  (if (and (number? value) (not (js/isNaN value)))
    (trading-domain/number->clean-string value 2)
    "0"))

(defn usd-input-text
  [modal value]
  (let [num-value (cond
                    (number? value) value
                    :else (parse-utils/parse-localized-currency-decimal value (:locale modal)))]
    (if (or (js/isNaN num-value)
            (< (js/Math.abs num-value) 0.00000001))
      "0"
      (shared/format-currency num-value))))

(defn coin-label [coin]
  (let [parsed (shared/parse-coin-namespace coin)]
    (or (:base parsed)
        (shared/non-blank-text coin)
        "-")))

(defn metric-row [label value]
  [:div {:class ["flex" "items-center" "justify-between"]}
   [:span {:class ["text-gray-400"]} label]
   [:span {:class ["font-semibold" "text-gray-100" "num"]} value]])

(defn checkbox-row [id label checked? on-change]
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
   "hover:border-ho-text-dim"
   "hover:ring-1"
   "hover:ring-ho-text-dim/30"
   "hover:ring-offset-0"
   "focus:outline-none"
   "focus:ring-1"
   "focus:ring-ho-text-muted/40"
   "focus:ring-offset-0"
   "focus:shadow-none"
   "focus:border-ho-text-muted"])

(def ^:private pnl-mode-options
  [:usd :roe-percent :position-percent])

(defn- select-input-value!
  [event]
  (let [target (or (some-> event .-currentTarget)
                   (some-> event .-target))]
    (when (and target (fn? (.-select target)))
      (js/setTimeout #(.select target) 0))))

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
                   "spectate-lg"]}
     tooltip-text]]])

(defn pnl-mode-select
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

(defn input-row
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
                      "focus:ring-ho-text-muted/40"
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

(defn configure-amount-controls
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

(defn expected-pnl-text
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
