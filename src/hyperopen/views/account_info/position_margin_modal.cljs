(ns hyperopen.views.account-info.position-margin-modal
  (:require [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.utils.parse :as parse-utils]
            [hyperopen.views.account-info.shared :as shared]))

(def ^:private panel-gap-px 8)
(def ^:private panel-margin-px 16)
(def ^:private preferred-panel-width-px 430)
(def ^:private estimated-panel-height-px 280)
(def ^:private fallback-viewport-width 1280)
(def ^:private fallback-viewport-height 800)
(def ^:private fallback-anchor-top 640)
(def ^:private mobile-sheet-breakpoint-px 640)
(def ^:private mobile-sheet-top-offset-px 20)
(def ^:private quick-percent-values [0 25 50 75 100])
(def ^:private slider-notch-overlap-threshold 2)

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
        viewport-height (max 320
                             (anchor-number anchor :viewport-height fallback-viewport-height))
        anchor-right (anchor-number anchor :right (- viewport-width panel-margin-px))
        anchor-top (anchor-number anchor :top fallback-anchor-top)
        anchor-bottom (anchor-number anchor :bottom anchor-top)
        panel-width (clamp (- viewport-width (* 2 panel-margin-px))
                           320
                           preferred-panel-width-px)
        left (clamp (- anchor-right panel-width)
                    panel-margin-px
                    (- viewport-width panel-width panel-margin-px))
        below-top (+ anchor-bottom panel-gap-px)
        above-top (- anchor-top panel-gap-px estimated-panel-height-px)
        fits-below? (<= (+ below-top estimated-panel-height-px panel-margin-px)
                        viewport-height)
        top (if fits-below?
              (clamp below-top
                     panel-margin-px
                     (- viewport-height estimated-panel-height-px panel-margin-px))
              (clamp above-top
                     panel-margin-px
                     (- viewport-height estimated-panel-height-px panel-margin-px)))]
    {:left (str left "px")
     :top (str top "px")
     :width (str panel-width "px")}))

(defn- mobile-sheet?
  [modal]
  (let [anchor (or (:anchor modal) {})
        viewport-width (max 320
                            (anchor-number anchor :viewport-width fallback-viewport-width)
                            (+ (anchor-number anchor :right 0) panel-margin-px))]
    (<= viewport-width mobile-sheet-breakpoint-px)))

(defn- mobile-sheet-style
  [modal]
  (let [anchor (or (:anchor modal) {})
        viewport-height (max 320
                             (anchor-number anchor :viewport-height fallback-viewport-height))
        max-height (max 320 (- viewport-height mobile-sheet-top-offset-px))]
    {:max-height (str max-height "px")
     :padding-bottom "max(env(safe-area-inset-bottom), 1rem)"
     :transition "transform 0.16s ease-out, opacity 0.16s ease-out"
     :transform "translateY(0)"
     :opacity 1}))

(defn- metric-row
  [label value]
  [:div {:class ["flex" "items-center" "justify-between"]}
   [:span {:class ["text-sm" "text-gray-400"]} label]
   [:span {:class ["text-sm" "font-semibold" "text-gray-100" "num"]} value]])

(defn- coin-label
  [coin]
  (let [parsed (shared/parse-coin-namespace coin)]
    (or (:base parsed)
        (shared/non-blank-text coin)
        "-")))

(defn- format-usdc
  [value]
  (str (shared/format-currency value) " USDC"))

(defn- format-price
  [value]
  (or (shared/format-trade-price value)
      "—"))

(defn- drag-confirmation-summary
  [modal]
  (when (= :chart-liquidation-drag (:prefill-source modal))
    [:div {:class ["rounded-lg"
                   "border"
                   "border-base-300"
                   "bg-base-200/60"
                   "px-3"
                   "py-2"
                   "space-y-1"]}
     [:div {:class ["text-xs" "font-semibold" "uppercase" "tracking-wide" "text-gray-400"]}
      "Chart liquidation drag"]
     [:div {:class ["text-xs" "text-gray-300"]}
      (str "Current " (format-price (:prefill-liquidation-current-price modal))
           " -> Target " (format-price (:prefill-liquidation-target-price modal)))]
     [:div {:class ["text-xs" "text-gray-400"]}
      "Review and confirm margin update."]]))

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

(defn- select-input-value!
  [event]
  (let [target (or (some-> event .-currentTarget)
                   (some-> event .-target))]
    (when (and target (fn? (.-select target)))
      ;; Defer so browser caret placement does not cancel selection.
      (js/setTimeout #(.select target) 0))))

(defn- parsed-percent
  [modal]
  (let [parsed (or (parse-utils/parse-localized-decimal (:amount-percent-input modal)
                                                        (:locale modal))
                   0)]
    (if (js/isNaN parsed)
      0
      (clamp parsed 0 100))))

(defn- amount-input-row
  [modal]
  [:div {:class ["relative" "w-full"]}
   [:div {:class ["absolute"
                  "left-3"
                  "top-1/2"
                  "-translate-y-1/2"
                  "z-10"
                  "flex"
                  "items-center"
                  "gap-1.5"]}
    [:span {:class ["pointer-events-none" "text-sm" "text-gray-500"]} "Amount"]
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
              :on {:click [[:actions/set-position-margin-amount-to-max]]}}
     "MAX"]]
   [:input {:class (into ["h-10"
                          "w-full"
                          "rounded-lg"
                          "border"
                          "border-base-300"
                          "bg-base-200"
                          "pl-[104px]"
                          "pr-16"
                          "text-right"
                          "text-sm"
                          "font-semibold"
                          "text-gray-100"
                          "num"]
                         neutral-input-focus-classes)
            :type "text"
            :inputmode "decimal"
            :value (or (:amount-input modal) "")
            :on {:input [[:actions/set-position-margin-modal-field [:amount-input] [:event.target/value]]]
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
    [:span {:class ["text-sm" "font-semibold" "text-gray-400"]} "USDC"]]])

(defn- percent-slider-row
  [modal]
  (let [slider-percent (js/Math.round (parsed-percent modal))]
    [:div {:class ["flex" "items-center" "gap-2"]}
     [:div {:class ["relative" "flex-1"]}
      [:input {:class ["order-size-slider" "range" "range-sm" "w-full" "relative" "z-20"]
               :type "range"
               :min 0
               :max 100
               :step 1
               :style {:--order-size-slider-progress (str slider-percent "%")}
               :value slider-percent
               :on {:input [[:actions/set-position-margin-amount-percent [:event.target/value]]]}}]
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
       (for [pct quick-percent-values]
         ^{:key (str "position-margin-slider-notch-" pct)}
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
                                                 slider-notch-overlap-threshold)
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
               :value (or (:amount-percent-input modal) "")
               :on {:input [[:actions/set-position-margin-amount-percent [:event.target/value]]]}}]
      [:span {:class ["pointer-events-none"
                      "absolute"
                      "right-2.5"
                      "top-1/2"
                      "-translate-y-1/2"
                      "text-sm"
                      "font-semibold"
                      "text-gray-300"]}
       "%"]]]))

(defn- mode-toggle-button
  [mode active-mode disabled?]
  (let [active? (= mode active-mode)
        label (if (= mode :add) "Add" "Remove")]
    [:button {:type "button"
              :disabled disabled?
              :class (into ["h-8"
                            "min-w-[74px]"
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
                           (if disabled?
                             ["bg-transparent"
                              "text-gray-500"
                              "cursor-default"]
                             (if active?
                               ["bg-[#2b3748]" "text-gray-100"]
                               ["bg-transparent" "text-gray-400" "hover:text-gray-200"])))
              :aria-pressed active?
              :on (when-not disabled?
                    {:click [[:actions/set-position-margin-modal-field [:mode] (name mode)]]})}
     label]))

(defn- close-button []
  [:button {:type "button"
            :class ["inline-flex"
                    "h-8"
                    "w-8"
                    "items-center"
                    "justify-center"
                    "rounded-lg"
                    "border"
                    "border-[#17313d]"
                    "bg-[#0b181d]"
                    "text-gray-300"
                    "transition-colors"
                    "hover:bg-[#102229]"
                    "hover:text-gray-100"
                    "focus:outline-none"
                    "focus:ring-1"
                    "focus:ring-[#66e3c5]/40"
                    "focus:ring-offset-0"
                    "focus:shadow-none"]
            :aria-label "Close margin sheet"
            :on {:click [[:actions/close-position-margin-modal]]}}
   "x"])

(defn position-margin-modal-view
  [modal]
  (let [modal* (or modal (position-margin/default-modal-state))]
    (when (position-margin/open? modal*)
      (let [validation (position-margin/validate-modal modal*)
            mode (position-margin/mode modal*)
            remove-disabled? (<= (or (:max-removable modal*) 0) 0)
            submitting? (true? (:submitting? modal*))
            submit-enabled? (and (:is-ok validation)
                                 (not submitting?))
            submit-label (if submitting?
                           "Updating..."
                           (:display-message validation))
            mobile-sheet? (mobile-sheet? modal*)
            layout-style (if mobile-sheet?
                           (mobile-sheet-style modal*)
                           (modal-layout-style modal*))
            panel-children
            [[:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
              [:div {:class ["space-y-1"]}
               [:h3 {:class (if mobile-sheet?
                              ["text-[2rem]" "font-semibold" "leading-[1.1]" "text-gray-100"]
                              ["text-[30px]" "font-semibold" "leading-[1.15]" "text-gray-100"])}
                "Adjust Margin"]
               [:p {:class ["max-w-[28rem]" "text-sm" "leading-5" "text-gray-400"]}
                "Decrease the chance of liquidation by adding more margin or remove excess margin to use for other positions."]]
              (close-button)]

             (metric-row "Current Margin" (format-usdc (:margin-used modal*)))
             (metric-row "Margin Available to Add" (format-usdc (:available-to-add modal*)))
             (drag-confirmation-summary modal*)

             (amount-input-row modal*)
             (percent-slider-row modal*)

             [:div {:class ["flex" "items-center" "gap-3"]}
              [:button {:type "button"
                        :disabled (not submit-enabled?)
                        :class (into ["h-10"
                                      "flex-1"
                                      "rounded-lg"
                                      "px-3"
                                      "text-sm"
                                      "font-semibold"
                                      "transition-colors"
                                      "focus:outline-none"
                                      "focus:ring-1"
                                      "focus:ring-[#8a96a6]/40"
                                      "focus:ring-offset-0"
                                      "focus:shadow-none"]
                                     (if submit-enabled?
                                       ["bg-[#b9bec7]"
                                        "text-[#1A212B]"
                                        "hover:bg-[#c7ccd4]"]
                                       ["bg-base-300"
                                        "text-gray-500"
                                        "cursor-default"]))
                        :on (when submit-enabled?
                              {:click [[:actions/submit-position-margin-update]]})}
               submit-label]
              [:div {:class ["inline-flex"
                             "h-10"
                             "items-center"
                             "rounded-lg"
                             "bg-base-200"
                             "p-1"]}
               (mode-toggle-button :add mode false)
               (mode-toggle-button :remove mode remove-disabled?)]]

             (when (seq (:error modal*))
               [:div {:class ["text-xs" "text-[#ED7088]"]}
                (:error modal*)])]]
        (if mobile-sheet?
          [:div {:class ["fixed" "inset-0" "z-[250]"]
                 :data-role "position-margin-mobile-sheet-layer"}
           [:button {:type "button"
                     :class ["absolute" "inset-0" "bg-black/55" "backdrop-blur-[1px]"]
                     :style {:transition "opacity 0.14s ease-out"
                             :opacity 1}
                     :replicant/mounting {:style {:opacity 0}}
                     :replicant/unmounting {:style {:opacity 0}}
                     :on {:click [[:actions/close-position-margin-modal]]}
                     :aria-label "Close margin sheet backdrop"
                     :data-role "position-margin-mobile-sheet-backdrop"}]
           (into [:div {:class ["absolute"
                                "inset-x-0"
                                "bottom-0"
                                "w-full"
                                "overflow-y-auto"
                                "rounded-t-[22px]"
                                "border"
                                "border-[#17313d]"
                                "bg-[#06131a]"
                                "px-4"
                                "pt-4"
                                "text-sm"
                                "shadow-[0_-24px_60px_rgba(0,0,0,0.45)]"
                                "space-y-3"]
                        :style layout-style
                        :replicant/mounting {:style {:transform "translateY(18px)"
                                                     :opacity 0}}
                        :replicant/unmounting {:style {:transform "translateY(18px)"
                                                       :opacity 0}}
                        :role "dialog"
                        :aria-modal true
                        :aria-label "Adjust Margin"
                        :data-position-margin-surface "true"
                        :on {:keydown [[:actions/handle-position-margin-modal-keydown [:event/key]]]}}]
                 (keep identity panel-children))]
          (into [:div {:class ["fixed"
                               "z-[250]"
                               "rounded-[10px]"
                               "border"
                               "border-base-300"
                               "bg-base-100"
                               "p-3"
                               "spectate-[0_24px_60px_rgba(0,0,0,0.45)]"
                               "space-y-3"]
                       :style layout-style
                       :role "dialog"
                       :aria-label "Adjust Margin"
                       :data-position-margin-surface "true"
                       :on {:keydown [[:actions/handle-position-margin-modal-keydown [:event/key]]]}}]
                (keep identity panel-children)))))))
