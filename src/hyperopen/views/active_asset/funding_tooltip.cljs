(ns hyperopen.views.active-asset.funding-tooltip
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.autocorrelation-plot :as autocorrelation-plot]
            [hyperopen.views.funding-rate-plot :as funding-rate-plot]))

(defn signed-percentage-text [value decimals]
  (if (number? value)
    (let [normalized (if (< (js/Math.abs value) 1e-8) 0 value)
          sign (cond
                 (pos? normalized) "+"
                 (neg? normalized) "-"
                 :else "")]
      (str sign (fmt/format-percentage (js/Math.abs normalized) decimals)))
    "—"))

(defn- unsigned-percentage-text [value decimals]
  (if (number? value)
    (fmt/format-percentage (js/Math.abs value) decimals)
    "—"))

(defn- signed-usd-text [value]
  (if (number? value)
    (let [normalized (if (< (js/Math.abs value) 0.005) 0 value)
          sign (cond
                 (pos? normalized) "+"
                 (neg? normalized) "-"
                 :else "")]
      (str sign "$" (fmt/format-fixed-number (js/Math.abs normalized) 2)))
    "—"))

(defn signed-tone-class [value]
  (cond
    (and (number? value) (pos? value)) "text-success"
    (and (number? value) (neg? value)) "text-error"
    :else "text-gray-100"))

(defn- payment-range-text
  [lower-payment upper-payment]
  (if (and (number? lower-payment)
           (number? upper-payment))
    (str (signed-usd-text lower-payment)
         " to "
         (signed-usd-text upper-payment))
    "—"))

(defn- predictability-rate-text [{:keys [rate-kind rate]}]
  (case rate-kind
    :signed-percentage (signed-percentage-text rate 4)
    :unsigned-percentage (unsigned-percentage-text rate 4)
    "—"))

(defn- predictability-rate-class [{:keys [rate-kind rate]}]
  (if (= rate-kind :signed-percentage)
    (signed-tone-class rate)
    "text-gray-100"))

(defn- predictability-payment-text
  [{:keys [payment-kind payment]}]
  (case payment-kind
    :signed-usd
    (signed-usd-text payment)

    :usd-range
    (payment-range-text (:lower payment) (:upper payment))

    "—"))

(defn- predictability-payment-class
  [{:keys [payment-kind payment]}]
  (case payment-kind
    :signed-usd (signed-tone-class payment)
    :usd-range "text-gray-100"
    "text-gray-100"))

(defn- predictability-payment-cell-classes
  [{:keys [payment-kind]}]
  (if (= payment-kind :usd-range)
    ["text-left"
     "min-w-0"
     "break-words"
     "text-[0.78rem]"
     "leading-[1.05rem]"
     "font-medium"]
    ["text-left"
     "font-medium"
     "whitespace-nowrap"]))

(defn- hypothetical-position-inputs
  [{:keys [hypothetical-size-input
           hypothetical-value-input
           hypothetical-coin
           hypothetical-mark
           position-base-symbol
           hypothetical-helper-text]}]
  [:div
   [:div {:class ["grid"
                  "grid-cols-[minmax(3.75rem,auto)_minmax(0,1fr)]"
                  "gap-x-3.5"
                  "gap-y-1"
                  "text-[0.86rem]"
                  "leading-[1.2rem]"]}
    [:span {:class ["text-gray-300/95" "text-left"]} "Size"]
    [:div {:class ["relative"
                   "min-w-0"
                   "rounded-md"
                   "border"
                   "border-[#28414d]"
                   "bg-[#0b1820]"
                   "focus-within:border-emerald-400/60"]}
     [:input {:type "text"
              :inputmode "decimal"
              :spellCheck false
              :aria-label "Hypothetical position size"
              :class ["w-full"
                      "min-w-0"
                      "border-0"
                      "bg-transparent"
                      "pl-2"
                      "pr-10"
                      "py-1"
                      "text-[0.82rem]"
                      "leading-5"
                      "text-gray-100"
                      "placeholder:text-slate-400"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"
                      "num"]
              :placeholder "0.0000"
              :value (or hypothetical-size-input "")
              :on {:input [[:actions/set-funding-hypothetical-size
                            hypothetical-coin
                            hypothetical-mark
                            [:event.target/value]]]}}]
     [:span {:class ["pointer-events-none"
                     "absolute"
                     "right-2"
                     "top-1/2"
                     "-translate-y-1/2"
                     "num"
                     "text-[0.72rem]"
                     "font-medium"
                     "uppercase"
                     "tracking-wide"
                     "text-gray-400"]}
      position-base-symbol]]
    [:span {:class ["text-gray-300/95" "text-left"]} "Value"]
    [:div {:class ["relative"
                   "min-w-0"
                   "rounded-md"
                   "border"
                   "border-[#28414d]"
                   "bg-[#0b1820]"
                   "focus-within:border-emerald-400/60"]}
     [:span {:class ["pointer-events-none"
                     "absolute"
                     "left-2"
                     "top-1/2"
                     "-translate-y-1/2"
                     "num"
                     "font-medium"
                     "text-gray-300/95"]}
      "$"]
     [:input {:type "text"
              :inputmode "decimal"
              :spellCheck false
              :aria-label "Hypothetical position value"
              :class ["w-full"
                      "min-w-0"
                      "border-0"
                      "bg-transparent"
                      "pl-6"
                      "pr-2"
                      "py-1"
                      "text-[0.82rem]"
                      "leading-5"
                      "text-gray-100"
                      "placeholder:text-slate-400"
                      "focus:border-emerald-400/60"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"
                      "num"]
              :placeholder "1000.00"
              :value (or hypothetical-value-input "")
              :on {:input [[:actions/set-funding-hypothetical-value
                            hypothetical-coin
                            hypothetical-mark
                            [:event.target/value]]]}}]]]
   [:p {:class ["mt-1.5"
                "text-[0.72rem]"
                "leading-[1.05rem]"
                "text-gray-400"]}
    (or hypothetical-helper-text
        "Edit size or value to estimate payments. Use negative size or value for short.")]])

(defn- live-position-summary
  [{:keys [position-size-label position-value]}]
  [:div {:class ["grid"
                 "grid-cols-[minmax(3.75rem,auto)_minmax(0,1fr)]"
                 "gap-x-3.5"
                 "gap-y-1"
                 "text-[0.86rem]"
                 "leading-[1.2rem]"]}
   [:span {:class ["text-gray-300/95" "text-left"]} "Size"]
   [:span {:class ["num" "text-left" "text-emerald-300" "whitespace-nowrap" "font-medium"]}
    position-size-label]
   [:span {:class ["text-gray-300/95" "text-left"]} "Value"]
   [:span {:class ["num" "text-left" "font-medium" "text-gray-100"]}
    (if (number? position-value)
      (str "$" (fmt/format-fixed-number position-value 2))
      "—")]])

(defn- position-section
  [{:keys [position-mode
           position-title
           position-action-label
           position-action-coin
           position-action-mark
           position-action-entry
           position-pin-id] :as model}]
  [:div {:class ["mb-3"]
         :data-role "active-asset-funding-position-section"
         :data-position-mode (name position-mode)}
   [:div {:class ["mb-1.5" "flex" "items-center" "justify-between" "gap-3"]}
    [:h4 {:class ["text-[0.9rem]"
                  "font-semibold"
                  "leading-5"
                  "text-gray-100"]}
     position-title]
    (when (seq position-action-label)
      [:button {:type "button"
                :class ["text-[0.72rem]"
                        "font-medium"
                        "leading-4"
                        "text-emerald-300"
                        "transition-colors"
                        "hover:text-emerald-200"
                        "focus:outline-none"
                        "focus:ring-0"
                        "focus:ring-offset-0"]
                :data-role "active-asset-funding-position-action"
                :on {:click (if (= position-mode :hypothetical)
                              [[:actions/reset-funding-hypothetical-position
                                position-action-coin]]
                              [[:actions/enter-funding-hypothetical-position
                                position-action-coin
                                position-action-mark
                                position-action-entry]
                               [:actions/set-funding-tooltip-pinned
                                position-pin-id
                                true]
                               [:actions/set-funding-tooltip-visible
                                position-pin-id
                                true]])}}
       position-action-label])]
   (if (= position-mode :hypothetical)
     (hypothetical-position-inputs model)
     (live-position-summary model))])

(defn- projections-section
  [{:keys [projection-rows]}]
  [:div {:class ["mb-2.5"]}
   [:div {:class ["grid"
                  "grid-cols-[minmax(0,1fr)_8ch_8ch]"
                  "gap-x-2.5"
                  "gap-y-1"
                  "text-[0.86rem]"
                  "leading-[1.2rem]"]}
    [:span {:class ["text-[0.9rem]" "font-semibold" "leading-5" "text-gray-100"]} "Projections"]
    [:span {:class ["text-left"
                    "text-[0.75rem]"
                    "font-medium"
                    "uppercase"
                    "tracking-wide"
                    "leading-5"
                    "text-gray-400"]}
     "Rate"]
    [:span {:class ["text-left"
                    "text-[0.75rem]"
                    "font-medium"
                    "uppercase"
                    "tracking-wide"
                    "leading-5"
                    "text-gray-400"]}
     "Payment"]
    (for [{:keys [id label rate payment]} projection-rows]
      ^{:key id}
      [:div {:class ["contents"]}
       [:span {:class ["text-gray-100/95" "text-left"]} label]
       [:span {:class ["num" "justify-self-end" "whitespace-nowrap" "font-medium" (signed-tone-class rate)]}
        (signed-percentage-text rate 4)]
       [:span {:class ["num" "text-left" "whitespace-nowrap" "font-medium" (signed-tone-class payment)]}
        (signed-usd-text payment)]])]])

(defn- predictability-grid
  [predictability-rows]
  [:div {:class ["grid"
                 "grid-cols-[minmax(0,1fr)_8ch_minmax(0,1fr)]"
                 "gap-x-2.5"
                 "gap-y-1"
                 "text-[0.86rem]"
                 "leading-[1.2rem]"]}
   (for [{:keys [id label] :as row} predictability-rows]
     ^{:key id}
     [:div {:class ["contents"]}
      [:span {:class ["text-gray-100/95" "text-left"]} label]
      [:span {:class ["num"
                      "text-left"
                      "whitespace-nowrap"
                      "font-medium"
                      (predictability-rate-class row)]}
       (predictability-rate-text row)]
      [:span {:class (into ["num"
                            (predictability-payment-class row)]
                           (predictability-payment-cell-classes row))}
       (predictability-payment-text row)]])])

(defn- predictability-section
  [{:keys [predictability-loading?
           predictability-error
           predictability-rows
           predictability-daily-rate-series
           predictability-autocorrelation-series
           predictability-lag-note]}]
  [:div
   [:h4 {:class ["mb-1.5"
                 "text-[0.9rem]"
                 "font-semibold"
                 "leading-5"
                 "text-gray-100"]}
    "Predictability (30d)"]
   (cond
     predictability-loading?
     [:div {:class ["text-[0.82rem]" "leading-[1.2rem]" "text-gray-300/90"]}
      "Loading 30d stats..."]

     (seq predictability-error)
     [:div {:class ["text-[0.82rem]" "leading-[1.2rem]" "text-red-300/90"]}
      "Unable to load 30d stats"]

     (seq predictability-rows)
     (predictability-grid predictability-rows)

     :else
     [:div {:class ["num" "text-[0.86rem]" "leading-[1.2rem]" "text-gray-300/90"]}
      "—"])
   (when (and (not predictability-loading?)
              (not (seq predictability-error))
              (seq predictability-daily-rate-series))
     (funding-rate-plot/funding-rate-plot predictability-daily-rate-series))
   (when (and (not predictability-loading?)
              (not (seq predictability-error))
              (seq predictability-autocorrelation-series))
     (autocorrelation-plot/autocorrelation-plot predictability-autocorrelation-series))
   (when (seq predictability-lag-note)
     [:p {:class ["mt-1.5"
                  "text-[0.72rem]"
                  "leading-[1.05rem]"
                  "text-gray-400"]}
      predictability-lag-note])])

(defn funding-tooltip-panel
  [model]
  [:div {:class ["w-[18rem]"
                 "relative"
                 "z-[140]"
                 "isolate"
                 "overflow-hidden"
                 "rounded-lg"
                 "border"
                 "border-[#17313d]"
                 "bg-[#06131a]"
                 "px-3.5"
                 "py-3"
                 "text-xs"
                 "text-left"
                 "text-gray-100"
                 "spectate-xl"
                 "shadow-[0_20px_45px_rgba(0,0,0,0.45)]"]}
   (position-section model)
   [:div {:class ["mb-2.5"
                  "h-px"
                  "w-full"
                  "bg-slate-600/70"]}]
   (projections-section model)
   [:div {:class ["mb-2.5"
                  "h-px"
                  "w-full"
                  "bg-slate-600/70"]}]
   (predictability-section model)])

(defn funding-tooltip-popover
  [{:keys [trigger body position open? pin-id pinned?]}]
  (let [placement-classes (case (or position "top")
                            "top" ["bottom-full" "left-1/2" "transform" "-translate-x-1/2" "mb-2"]
                            "bottom" ["top-full" "left-1/2" "transform" "-translate-x-1/2" "mt-2"]
                            "left" ["right-full" "top-1/2" "transform" "-translate-y-1/2" "mr-2"]
                            "right" ["left-full" "top-1/2" "transform" "-translate-y-1/2" "ml-2"])
        open?* (boolean open?)
        dismiss-actions [[:actions/set-funding-tooltip-pinned pin-id false]
                         [:actions/set-funding-tooltip-visible pin-id false]]
        trigger-click-actions (if pinned?
                                dismiss-actions
                                [[:actions/set-funding-tooltip-pinned pin-id true]
                                 [:actions/set-funding-tooltip-visible pin-id true]])]
    [:div {:class ["relative" "inline-flex"]
           :on {:mouseenter [[:actions/set-funding-tooltip-visible pin-id true]]
                :mouseleave [[:actions/set-funding-tooltip-visible pin-id false]]}}
     (when pinned?
       [:div {:class ["fixed"
                      "inset-0"
                      "z-[135]"
                      "cursor-default"]
              :on {:click dismiss-actions}}])
     [:button {:type "button"
               :class ["relative"
                       "z-[141]"
                       "inline-flex"
                       "cursor-pointer"
                       "appearance-none"
                       "border-0"
                       "bg-transparent"
                       "p-0"
                       "text-inherit"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"]
               :data-role "active-asset-funding-trigger"
               :aria-expanded open?*
               :aria-haspopup "dialog"
               :aria-pressed (boolean pinned?)
               :on {:click trigger-click-actions
                    :focus [[:actions/set-funding-tooltip-visible pin-id true]]
                    :blur [[:actions/set-funding-tooltip-visible pin-id false]]}}
      trigger]
     (when open?*
       [:div {:class (into ["absolute"
                            "z-[140]"
                            "transition-opacity"
                            "duration-200"
                            "pointer-events-auto"]
                           placement-classes)
              :data-role "active-asset-funding-tooltip"
              :style {:min-width "max-content"
                      :max-width "22rem"}}
        body])]))
