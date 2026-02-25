(ns hyperopen.views.account-info.position-reduce-popover
  (:require [hyperopen.account.history.position-reduce :as position-reduce]))

(def ^:private panel-gap-px 8)
(def ^:private panel-margin-px 16)
(def ^:private preferred-panel-width-px 390)
(def ^:private fallback-viewport-width 1280)
(def ^:private fallback-anchor-top 640)
(def ^:private quick-percent-values [25 50 75 100])

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

(defn- popover-layout-style
  [popover]
  (let [anchor (or (:anchor popover) {})
        viewport-width (max 320
                           (anchor-number anchor :viewport-width fallback-viewport-width)
                           (+ (anchor-number anchor :right 0) panel-margin-px))
        anchor-right (anchor-number anchor :right (- viewport-width panel-margin-px))
        anchor-top (anchor-number anchor :top fallback-anchor-top)
        panel-width (clamp (- viewport-width (* 2 panel-margin-px))
                           300
                           preferred-panel-width-px)
        left (clamp (- anchor-right panel-width)
                    panel-margin-px
                    (- viewport-width panel-width panel-margin-px))]
    {:left (str left "px")
     :top (str (- anchor-top panel-gap-px) "px")
     :transform "translateY(-100%)"
     :width (str panel-width "px")}))

(defn- selected-percent
  [popover]
  (position-reduce/configured-size-percent popover))

(defn- close-title
  [popover]
  (if (position-reduce/limit-close? popover)
    "Limit Close"
    "Market Close"))

(defn- side-label
  [popover]
  (position-reduce/position-side-label popover))

(defn- primary-button-label
  [popover]
  (let [side (side-label popover)]
    (if (position-reduce/limit-close? popover)
      (str "Limit Close " side)
      (str "Close " side))))

(defn- percent-preset-button
  [popover pct]
  (let [selected? (= pct (js/Math.round (selected-percent popover)))]
    [:button {:type "button"
              :class (into ["h-8"
                            "rounded-md"
                            "border"
                            "text-sm"
                            "font-medium"
                            "transition-colors"
                            "focus:outline-none"
                            "focus:ring-1"
                            "focus:ring-[#8a96a6]/40"
                            "focus:ring-offset-0"
                            "focus:shadow-none"]
                           (if selected?
                             ["border-[#c5ccd8]"
                              "bg-[#c5ccd8]"
                              "text-[#1A212B]"]
                             ["border-base-300"
                              "bg-base-200"
                              "text-gray-200"
                              "hover:bg-base-300"
                              "hover:text-gray-100"]))
              :on {:click [[:actions/set-position-reduce-size-percent pct]]}}
     (str pct "%")]))

(defn- mode-toggle-button
  [mode current-mode]
  (let [active? (= mode current-mode)
        mode-name (name mode)
        label (if (= mode :market) "Market" "Limit")]
    [:button {:type "button"
              :class (into ["h-8"
                            "min-w-[58px]"
                            "rounded-md"
                            "px-3"
                            "text-xs"
                            "font-semibold"
                            "transition-colors"
                            "focus:outline-none"
                            "focus:ring-1"
                            "focus:ring-[#8a96a6]/40"
                            "focus:ring-offset-0"
                            "focus:shadow-none"]
                           (if active?
                             ["bg-[#2b3748]" "text-gray-100"]
                             ["bg-transparent" "text-gray-400" "hover:text-gray-200"]))
              :aria-pressed active?
              :on {:click [[:actions/set-position-reduce-popover-field [:close-type] mode-name]]}}
     label]))

(defn position-reduce-popover-view
  [popover]
  (let [popover* (or popover (position-reduce/default-popover-state))]
    (when (position-reduce/open? popover*)
      (let [size-percent (selected-percent popover*)
            slider-percent (js/Math.round size-percent)
            limit-close? (position-reduce/limit-close? popover*)
            close-type (position-reduce/close-type popover*)
            mid-available? (boolean (seq (:mid-price popover*)))
            layout-style (popover-layout-style popover*)]
        [:div {:class ["fixed"
                       "z-[250]"
                       "rounded-[10px]"
                       "border"
                       "border-base-300"
                       "bg-base-100"
                       "p-3"
                       "shadow-[0_24px_60px_rgba(0,0,0,0.45)]"
                       "space-y-3"]
               :style layout-style
               :role "dialog"
               :aria-label "Position Reduce"
               :data-position-reduce-surface "true"
               :on {:keydown [[:actions/handle-position-reduce-popover-keydown [:event/key]]]}}
         [:h3 {:class ["text-[30px]" "font-semibold" "leading-[1.15]" "text-gray-100"]}
          (close-title popover*)]

         (when limit-close?
           [:div {:class ["relative"]}
            [:input {:class ["h-10"
                             "w-full"
                             "rounded-lg"
                             "border"
                             "border-base-300"
                             "bg-base-200"
                             "pl-3"
                             "pr-24"
                             "text-sm"
                             "font-semibold"
                             "text-gray-100"
                             "focus:outline-none"
                             "focus:ring-1"
                             "focus:ring-[#8a96a6]/40"
                             "focus:ring-offset-0"
                             "focus:shadow-none"
                             "focus:border-[#8a96a6]"]
                     :type "text"
                     :placeholder "Limit Price"
                     :value (or (:limit-price popover*) "")
                     :on {:input [[:actions/set-position-reduce-popover-field [:limit-price] [:event.target/value]]]}}]
            [:div {:class ["absolute"
                           "right-2.5"
                           "top-1/2"
                           "-translate-y-1/2"
                           "flex"
                           "items-center"
                           "gap-1.5"]}
             [:span {:class ["text-sm" "font-semibold" "text-gray-400"]} "USD"]
             [:button {:type "button"
                       :disabled (not mid-available?)
                       :class (into ["rounded-md"
                                     "border"
                                     "px-1.5"
                                     "py-0.5"
                                     "text-xs"
                                     "font-semibold"
                                     "transition-colors"
                                     "focus:outline-none"
                                     "focus:ring-1"
                                     "focus:ring-[#8a96a6]/40"
                                     "focus:ring-offset-0"
                                     "focus:shadow-none"]
                                    (if mid-available?
                                      ["border-base-300"
                                       "bg-base-200"
                                       "text-gray-300"
                                       "hover:bg-base-300"
                                       "hover:text-gray-100"]
                                      ["border-base-300"
                                       "bg-base-200"
                                       "text-gray-500"
                                       "cursor-default"]))
                       :aria-label "Set limit price to mid"
                       :on (when mid-available?
                             {:click [[:actions/set-position-reduce-limit-price-to-mid]]})}
              "MID"]]])

         [:div {:class ["flex" "items-center" "gap-2"]}
          [:div {:class ["relative" "flex-1"]}
           [:input {:class ["order-size-slider" "range" "range-sm" "w-full" "relative" "z-20"]
                    :type "range"
                    :min 0
                    :max 100
                    :step 1
                    :style {:--order-size-slider-progress (str slider-percent "%")}
                    :value slider-percent
                    :on {:input [[:actions/set-position-reduce-popover-field [:size-percent-input] [:event.target/value]]]}}]]
          [:div {:class ["relative" "w-[82px]"]}
           [:input {:class ["order-size-percent-input"
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
                            "pr-6"
                            "focus:outline-none"
                            "focus:ring-1"
                            "focus:ring-[#8a96a6]/40"
                            "focus:ring-offset-0"
                            "focus:shadow-none"
                            "focus:border-[#8a96a6]"]
                    :type "text"
                    :inputmode "decimal"
                    :value (or (:size-percent-input popover*) "")
                    :on {:input [[:actions/set-position-reduce-popover-field [:size-percent-input] [:event.target/value]]]}}]
           [:span {:class ["pointer-events-none"
                           "absolute"
                           "right-2.5"
                           "top-1/2"
                           "-translate-y-1/2"
                           "text-sm"
                           "font-semibold"
                           "text-gray-300"]}
            "%"]]]

         [:div {:class ["grid" "grid-cols-4" "gap-2"]}
          (for [pct quick-percent-values]
            ^{:key (str "position-reduce-pct-" pct)}
            (percent-preset-button popover* pct))]

         [:div {:class ["flex" "items-center" "gap-3"]}
          [:button {:type "button"
                    :class ["h-10"
                            "flex-1"
                            "rounded-lg"
                            "bg-[#b9bec7]"
                            "px-3"
                            "text-sm"
                            "font-semibold"
                            "text-[#1A212B]"
                            "transition-colors"
                            "hover:bg-[#c7ccd4]"
                            "focus:outline-none"
                            "focus:ring-1"
                            "focus:ring-[#8a96a6]/40"
                            "focus:ring-offset-0"
                            "focus:shadow-none"]
                    :on {:click [[:actions/submit-position-reduce-close]]}}
           (primary-button-label popover*)]
          [:div {:class ["inline-flex"
                         "h-10"
                         "items-center"
                         "rounded-lg"
                         "bg-base-200"
                         "p-1"]}
           (mode-toggle-button :market close-type)
           (mode-toggle-button :limit close-type)]]

         (when (seq (:error popover*))
           [:div {:class ["text-xs" "text-[#ED7088]"]}
            (:error popover*)])]))))
