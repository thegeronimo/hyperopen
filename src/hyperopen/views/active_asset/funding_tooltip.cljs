(ns hyperopen.views.active-asset.funding-tooltip
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.active-asset.funding-tooltip-mobile :as funding-tooltip-mobile]
            [hyperopen.views.active-asset.funding-tooltip-sections :as sections]
            [hyperopen.views.ui.anchored-popover :as anchored-popover]
            [hyperopen.views.active-asset.funding-tooltip-runtime :as tooltip-runtime]))

(defn signed-percentage-text [value decimals]
  (if (number? value)
    (let [normalized (if (< (js/Math.abs value) 1e-8) 0 value)
          sign (cond
                 (pos? normalized) "+"
                 (neg? normalized) "-"
                 :else "")]
      (str sign (fmt/format-percentage (js/Math.abs normalized) decimals)))
    "—"))

(defn signed-tone-class [value]
  (cond
    (and (number? value) (pos? value)) "text-success"
    (and (number? value) (neg? value)) "text-error"
    :else "text-gray-100"))

(defn- tooltip-dismiss-actions
  [pin-id]
  [[:actions/set-funding-tooltip-pinned pin-id false]
   [:actions/set-funding-tooltip-visible pin-id false]])

(defn- tooltip-trigger-click-actions
  [pin-id pinned?]
  (if pinned?
    (tooltip-dismiss-actions pin-id)
    [[:actions/set-funding-tooltip-pinned pin-id true [:event.currentTarget/bounds]]
     [:actions/set-funding-tooltip-visible pin-id true [:event.currentTarget/bounds]]]))

(def ^:private input-shell-classes
  ["relative" "flex" "h-9" "min-w-0" "items-center" "gap-1.5" "rounded-md" "border"
   "border-ho-border-accent-muted" "bg-ho-bg-deep" "px-2.5"
   "focus-within:border-ho-accent/60"])

(def ^:private input-classes
  ["w-full" "min-w-0" "border-0" "bg-transparent" "p-0" "text-sm" "text-ho-text"
   "placeholder:text-ho-text-dim" "focus:outline-none" "focus:ring-0" "focus:ring-offset-0" "num"])

(defn- side-button
  [side current coin mark]
  (let [active? (= side current)]
    [:button {:type "button"
              :aria-pressed (if active? "true" "false")
              :data-role (str "funding-hypothetical-side-" (name side))
              :class (into ["h-7" "rounded" "px-3" "text-xs" "font-semibold" "transition-colors"
                            "focus:outline-none" "focus-visible:ring-1" "focus-visible:ring-ho-accent"]
                           (if active?
                             ["bg-ho-text" "text-ho-bg-deep"]
                             ["text-ho-text-muted" "hover:text-ho-text"]))
              :on {:click [[:actions/set-funding-hypothetical-side coin mark (name side)]]}}
     (if (= side :short) "Short" "Long")]))

(defn- side-toggle
  [{:keys [hypothetical-side hypothetical-coin hypothetical-mark]}]
  [:div {:role "group"
         :aria-label "Position side"
         :class ["flex" "gap-0.5" "rounded-md" "border" "border-ho-border-accent-muted"
                 "bg-ho-bg-deep" "p-0.5"]}
   (side-button :long hypothetical-side hypothetical-coin hypothetical-mark)
   (side-button :short hypothetical-side hypothetical-coin hypothetical-mark)])

(defn- labeled-input
  [label input-node]
  [:label {:class ["flex" "min-w-0" "flex-col" "gap-0.5"]}
   [:span {:class ["text-xs" "text-ho-text-muted"]} label]
   input-node])

(defn- hypothetical-position-inputs
  [{:keys [hypothetical-size-display
           hypothetical-value-display
           hypothetical-coin
           hypothetical-mark
           position-base-symbol
           leverage
           margin]}]
  [:div {:class ["flex" "flex-col" "gap-1.5"]}
   [:div {:class ["grid" "grid-cols-2" "gap-1.5"]}
    (labeled-input
     "Value"
     [:div {:class input-shell-classes}
      [:span {:class ["text-sm" "text-ho-text-muted"]} "$"]
      [:input {:type "text"
               :inputmode "decimal"
               :spellCheck false
               :aria-label "Hypothetical position value"
               :class input-classes
               :placeholder "1000.00"
               :value (or hypothetical-value-display "")
               :on {:input [[:actions/set-funding-hypothetical-value
                             hypothetical-coin
                             hypothetical-mark
                             [:event.target/value]]]}}]])
    (labeled-input
     "Size"
     [:div {:class input-shell-classes}
      [:input {:type "text"
               :inputmode "decimal"
               :spellCheck false
               :aria-label "Hypothetical position size"
               :class input-classes
               :placeholder "0.0000"
               :value (or hypothetical-size-display "")
               :on {:input [[:actions/set-funding-hypothetical-size
                             hypothetical-coin
                             hypothetical-mark
                             [:event.target/value]]]}}]
      [:span {:class ["num" "text-xs" "font-medium" "uppercase" "text-ho-text-muted"]}
       position-base-symbol]])]
   (when (number? margin)
     [:span {:class ["num" "text-xs" "text-ho-text-muted"]}
      (str "At your order leverage of "
           (fmt/format-fixed-number leverage (if (== leverage (js/Math.round leverage)) 0 1))
           "× → $" (fmt/format-fixed-number margin 2) " margin")])])

(defn- stat-card
  [label value-node]
  [:div {:class ["min-w-0" "rounded-md" "bg-ho-surface/60" "px-2" "py-1.5"]}
   [:div {:class ["text-xs" "text-ho-text-muted" "whitespace-nowrap"]} label]
   value-node])

(defn- live-position-summary
  [{:keys [position-size-label position-value direction leverage margin]}]
  [:div {:class ["grid" "grid-cols-3" "gap-1.5"]}
   (stat-card [:span "Size · "
               [:span {:class [(if (= direction :short) "text-error" "text-success")]}
                (if (= direction :short) "Short" "Long")]]
              [:div {:class ["num" "truncate" "text-sm" "font-semibold" "text-ho-text"]
                     :title position-size-label}
               (str/replace (or position-size-label "") #"^(Long|Short) " "")])
   (stat-card "Value"
              [:div {:class ["num" "text-sm" "font-semibold" "text-ho-text"]}
               (if (number? position-value)
                 (str "$" (fmt/format-fixed-number position-value 2))
                 "—")])
   (stat-card (if (number? leverage)
                (str "Margin · " (fmt/format-fixed-number leverage 0) "×")
                "Margin")
              [:div {:class ["num" "text-sm" "font-semibold" "text-ho-text"]}
               (if (number? margin)
                 (str "$" (fmt/format-fixed-number margin 2))
                 "—")])])

(defn- position-section
  [{:keys [position-mode
           position-title
           position-action-label
           position-action-coin
           position-action-mark
           position-action-entry
           position-pin-id] :as model}]
  (let [hypothetical? (= position-mode :hypothetical)]
    [:div {:class ["flex" "flex-col" "gap-2"]
           :data-role "active-asset-funding-position-section"
           :data-position-mode (name position-mode)}
     (sections/section-heading
      position-title
      [:div {:class ["flex" "items-center" "gap-2"]}
       (when (seq position-action-label)
         [:button {:type "button"
                   :class ["text-xs" "font-semibold" "text-ho-accent" "transition-colors"
                           "hover:text-ho-accent-hi" "focus:outline-none" "focus:ring-0"
                           "focus:ring-offset-0"]
                   :data-role "active-asset-funding-position-action"
                   :on {:click (if hypothetical?
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
          position-action-label])
       (when hypothetical?
         (side-toggle model))])
     (if hypothetical?
       (hypothetical-position-inputs model)
       (live-position-summary model))]))

(defn- funding-tooltip-panel-classes
  [mobile-sheet?]
  (if mobile-sheet?
    ["relative"
     "z-[181]"
     "isolate"
     "w-full"
     "max-h-[calc(100vh-1.25rem)]"
     "overflow-y-auto"
     "scrollbar-hide"
     "rounded-t-[22px]"
     "border"
     "border-ho-border-accent-muted"
     "bg-ho-bg-deep"
     "px-4"
     "pt-4"
     "pb-[max(env(safe-area-inset-bottom),1rem)]"
     "text-sm"
     "text-left"
     "whitespace-normal"
     "text-gray-100"
     "shadow-[0_-24px_60px_rgba(0,0,0,0.45)]"]
    ["w-[20rem]"
     "relative"
     "z-[140]"
     "isolate"
     "overflow-hidden"
     "rounded-lg"
     "border"
     "border-ho-border-accent-muted"
     "bg-ho-bg-deep"
     "px-3.5"
     "py-3"
     "text-xs"
     "text-left"
     "whitespace-normal"
     "text-gray-100"
     "spectate-xl"
     "shadow-[0_20px_45px_rgba(0,0,0,0.45)]"]))

(defn- divider []
  [:div {:class ["h-px" "w-full" "bg-ho-border/70"]}])

(defn funding-tooltip-panel
  ([model]
   (funding-tooltip-panel model {}))
  ([model {:keys [mobile-sheet? attrs chart-style countdown-text]}]
   [:div (merge {:class (funding-tooltip-panel-classes mobile-sheet?)}
                (or attrs {}))
    [:div {:class ["flex" "flex-col" "gap-3"]}
     (sections/header-section model countdown-text)
     (position-section model)
     (sections/verdict-section model)
     (sections/since-open-section model)
     (sections/projections-section model)
     (when (:rate-breakdown model)
       (list (divider)
             (sections/rate-breakdown-section model)))
     (divider)
     (sections/history-section model chart-style)
     (sections/year-outlook-section model)
     (sections/lag-strip-section model)]]))

(defn funding-tooltip-popover
  [{:keys [trigger body position open? pin-id pinned? anchor viewport-layer?]}]
  (let [placement-classes (case (or position "top")
                            "top" ["bottom-full" "left-1/2" "transform" "-translate-x-1/2" "mb-2"]
                            "bottom" ["top-full" "left-1/2" "transform" "-translate-x-1/2" "mt-2"]
                            "left" ["right-full" "top-1/2" "transform" "-translate-y-1/2" "mr-2"]
                            "right" ["left-full" "top-1/2" "transform" "-translate-y-1/2" "ml-2"])
        open?* (boolean open?)
        fixed-layer? (and viewport-layer? (anchored-popover/complete-anchor? anchor))
        dismiss-actions (tooltip-dismiss-actions pin-id)
        trigger-click-actions (tooltip-trigger-click-actions pin-id pinned?)]
    [:div {:class ["relative" "inline-flex"]
           :on {:mouseenter [[:actions/set-funding-tooltip-visible pin-id true [:event.currentTarget/bounds]]]
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
               :aria-expanded (if open?* "true" "false")
               :aria-haspopup "dialog"
               :aria-pressed (boolean pinned?)
               :on {:click trigger-click-actions
                    :focus [[:actions/set-funding-tooltip-visible pin-id true [:event.currentTarget/bounds]]]
                    :blur [[:actions/set-funding-tooltip-visible pin-id false]]}}
      trigger]
     (when open?*
       [:div {:class (into [(if fixed-layer? "fixed" "absolute")
                            "z-[140]"
                            "transition-opacity"
                            "duration-200"
                            "pointer-events-auto"]
                           (if fixed-layer?
                             ["max-h-[calc(100vh-1.5rem)]" "overflow-y-auto" "scrollbar-hide"]
                             placement-classes))
              :data-role "active-asset-funding-tooltip"
              :style (merge {:min-width "max-content" :max-width "22rem"}
                            (when fixed-layer?
                              (let [layout (anchored-popover/anchored-popover-layout-style
                                            {:anchor anchor :preferred-width-px 320 :estimated-height-px 760})]
                                ;; The panel is taller than most viewports; scroll inside it
                                ;; rather than spilling past the bottom edge.
                                (assoc layout :max-height (str "calc(100vh - " (:top layout) " - 12px)")))))
              :replicant/on-render (when fixed-layer?
                                    (tooltip-runtime/close-on-resize pin-id))}
        body])]))

(defn funding-tooltip-mobile-sheet
  [opts]
  (funding-tooltip-mobile/funding-tooltip-mobile-sheet opts))
