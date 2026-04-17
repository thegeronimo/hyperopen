(ns hyperopen.views.portfolio.fee-schedule
  (:require [hyperopen.views.ui.anchored-popover :as anchored-popover]
            [hyperopen.views.ui.dialog-focus :as dialog-focus]))

(def ^:private title-id
  "portfolio-fee-schedule-title")

(def ^:private restore-selector
  "[data-role=\"portfolio-fee-schedule-trigger\"]")

(def ^:private preferred-popover-width-px
  480)

(def ^:private estimated-popover-height-px
  600)

(def ^:private dialog-focus-on-render
  (dialog-focus/dialog-focus-on-render {:restore-selector restore-selector}))

(defn- viewport-number
  [property]
  (let [value (some-> js/globalThis (aget property))]
    (when (number? value)
      value)))

(defn- element-anchor-bounds
  [selector]
  (when (seq selector)
    (let [document* (some-> js/globalThis .-document)
          target (some-> document* (.querySelector selector))]
      (when (and target (fn? (.-getBoundingClientRect target)))
        (let [rect (.getBoundingClientRect target)]
          {:left (.-left rect)
           :right (.-right rect)
           :top (.-top rect)
           :bottom (.-bottom rect)
           :width (.-width rect)
           :height (.-height rect)
           :viewport-width (viewport-number "innerWidth")
           :viewport-height (viewport-number "innerHeight")})))))

(defn- with-current-viewport
  [anchor]
  (let [anchor* (if (map? anchor) anchor {})]
    (cond-> anchor*
      (not (number? (:viewport-width anchor*)))
      (assoc :viewport-width (viewport-number "innerWidth"))

      (not (number? (:viewport-height anchor*)))
      (assoc :viewport-height (viewport-number "innerHeight")))))

(defn- complete-layout-anchor?
  [anchor]
  (and (anchored-popover/complete-anchor? anchor)
       (number? (:viewport-width anchor))
       (number? (:viewport-height anchor))))

(defn- resolved-anchor
  [anchor]
  (let [stored-anchor (with-current-viewport anchor)
        fallback-anchor (when-not (complete-layout-anchor? stored-anchor)
                          (element-anchor-bounds restore-selector))]
    (or fallback-anchor stored-anchor)))

(defn- close-icon []
  [:svg {:viewBox "0 0 20 20"
         :class ["h-4" "w-4"]
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.8"
         :stroke-linecap "round"
         :aria-hidden "true"}
   [:path {:d "M5 5 15 15"}]
   [:path {:d "M15 5 5 15"}]])

(defn- chevron-icon
  [open?]
  [:svg {:class (into ["h-3.5"
                       "w-3.5"
                       "text-trading-text-secondary"
                       "transition-transform"]
                      (when open?
                        ["rotate-180"]))
         :fill "none"
         :stroke "currentColor"
         :viewBox "0 0 24 24"
         :aria-hidden "true"}
   [:path {:stroke-linecap "round"
           :stroke-linejoin "round"
           :stroke-width 2
           :d "M19 9l-7 7-7-7"}]])

(defn- control-action
  [kind]
  (case kind
    :referral :actions/toggle-portfolio-fee-schedule-referral-dropdown
    :staking :actions/toggle-portfolio-fee-schedule-staking-dropdown
    :maker-rebate :actions/toggle-portfolio-fee-schedule-maker-rebate-dropdown
    :market :actions/toggle-portfolio-fee-schedule-market-dropdown))

(defn- select-action
  [kind]
  (case kind
    :referral :actions/select-portfolio-fee-schedule-referral-discount
    :staking :actions/select-portfolio-fee-schedule-staking-tier
    :maker-rebate :actions/select-portfolio-fee-schedule-maker-rebate-tier
    :market :actions/select-portfolio-fee-schedule-market-type))

(defn- role-value
  [value]
  (name value))

(defn- selector-control
  [{:keys [kind title label value selected-value dropdown-open? options]}]
  (let [role-prefix (case kind
                      :referral "portfolio-fee-schedule-referral"
                      :staking "portfolio-fee-schedule-staking"
                      :maker-rebate "portfolio-fee-schedule-maker-rebate"
                      :market "portfolio-fee-schedule-market")
        menu-max-height (case kind
                          :staking "max-h-56"
                          :market "max-h-72"
                          "max-h-44")]
    [:section {:class ["space-y-1"]
               :data-role role-prefix}
     (when title
       [:h3 {:class ["text-xs"
                     "font-semibold"
                     "uppercase"
                     "tracking-wide"
                     "text-trading-green"]}
        title])
     [:div {:class ["relative"]}
      [:button {:type "button"
                :class ["flex"
                        "h-7"
                        "w-full"
                        "items-center"
                        "justify-between"
                        "gap-3"
                        "rounded-md"
                        "border"
                        "border-base-300"
                        "bg-[#111f25]"
                        "px-3"
                        "text-xs"
                        "text-trading-text"
                        "focus:outline-none"
                        "focus:ring-0"
                        "focus:ring-offset-0"]
                :aria-expanded (if dropdown-open? "true" "false")
                :data-role (str role-prefix "-trigger")
                :on {:click [[(control-action kind)]]}}
       [:span {:class ["min-w-0" "truncate" "text-trading-text-secondary"]}
        label]
       [:span {:class ["flex" "min-w-0" "items-center" "gap-1.5"]}
        [:span {:class ["truncate" "text-right"]}
         value]
        (chevron-icon dropdown-open?)]]
      [:div {:class (into ["absolute"
                           "right-0"
                           "top-full"
                           "z-[655]"
                           "mt-1"
                           "w-full"
                           "min-w-full"
                           menu-max-height
                           "overflow-y-auto"
                           "rounded-md"
                           "border"
                           "border-base-300"
                           "bg-[#0f1a1f]"
                           "shadow-2xl"]
                          (if dropdown-open?
                            ["opacity-100" "scale-y-100" "translate-y-0"]
                            ["pointer-events-none" "opacity-0" "scale-y-95" "-translate-y-1"]))
             :style {:transition "all 80ms ease-in-out"}
             :data-role (str role-prefix "-menu")}
       (for [{:keys [value label description helper selected? current? current-label disabled?]} options]
         ^{:key (role-value value)}
         [:button (cond-> {:type "button"
                           :class (into ["flex"
                                         "h-7"
                                         "w-full"
                                         "items-center"
                                         "gap-2"
                                         "overflow-hidden"
                                         "px-3"
                                         "text-left"
                                         "text-xs"
                                         "transition-colors"
                                         "focus:outline-none"
                                         "focus:ring-0"
                                         "focus:ring-offset-0"]
                                        (concat
                                         (if disabled?
                                           ["cursor-not-allowed" "opacity-60"]
                                           ["hover:bg-base-300"
                                            "hover:text-trading-text"
                                            "focus-visible:bg-base-300"
                                            "focus-visible:text-trading-text"])
                                         (if (= value selected-value)
                                           ["bg-base-200" "text-trading-text"]
                                           ["text-trading-text-secondary"])))
                           :aria-pressed (= value selected-value)
                           :data-role (str role-prefix "-option-" (role-value value))}
                    disabled?
                    (assoc :aria-disabled "true"
                           :tab-index -1)

                    (not disabled?)
                    (assoc :on {:click [[(select-action kind) value]]}))
          [:span {:class ["shrink-0"
                          "font-medium"
                          (if selected?
                            "text-trading-green"
                            "text-trading-text")]}
           label]
          (when (or description helper)
            [:span {:class ["min-w-0"
                            "flex-1"
                            "truncate"
                            "text-xs"
                            "text-trading-text-secondary"]}
             (or description helper)])
          (when current?
            [:span {:class ["ml-auto"
                            "shrink-0"
                            "text-right"
                            "text-xs"
                            "font-medium"
                            "uppercase"
                            "tracking-wide"
                            "text-trading-green"]}
             (or current-label
                 (case kind
                   :referral "Current wallet status"
                   :staking "Current wallet staking tier"
                   :maker-rebate "Current wallet maker rebate"
                   :market "Current market type"))])])]]]))

(defn- scenario-selector
  [kind title model]
  (selector-control (assoc model
                           :kind kind
                           :title title)))

(defn- market-selector
  [{:keys [selected-market-type
           selected-market-label
           market-dropdown-open?
           market-options]}]
  (selector-control {:kind :market
                     :label "Market Type"
                     :value selected-market-label
                     :selected-value selected-market-type
                     :dropdown-open? market-dropdown-open?
                     :options (mapv (fn [{:keys [value label] :as option}]
                                      (assoc option
                                             :selected? (= value selected-market-type)))
                                    market-options)}))

(defn- schedule-table
  [rows]
  [:div {:class ["overflow-x-auto"]
         :data-role "portfolio-fee-schedule-table-scroll"}
   [:table {:class ["w-full"
                    "min-w-[24rem]"
                    "border-collapse"
                    "text-left"
                    "text-xs"]
            :data-role "portfolio-fee-schedule-table"}
    [:thead {:class ["bg-[#111f25]" "text-trading-text-secondary"]}
     [:tr
      [:th {:class ["border" "border-base-300" "px-2" "py-1" "font-medium"]} "Tier"]
      [:th {:class ["border" "border-base-300" "px-2" "py-1" "font-medium"]} "14 Day Volume"]
      [:th {:class ["border" "border-base-300" "px-2" "py-1" "font-medium"]} "Taker*"]
      [:th {:class ["border" "border-base-300" "px-2" "py-1" "font-medium"]} "Maker*"]]]
    [:tbody {:class ["text-trading-text"]}
     (for [{:keys [tier volume taker maker]} rows]
       ^{:key tier}
       [:tr {:data-role (str "portfolio-fee-schedule-tier-" tier)}
        [:td {:class ["border" "border-base-300" "px-2" "py-1"]} tier]
        [:td {:class ["border" "border-base-300" "px-2" "py-1"]} volume]
        [:td {:class ["num" "border" "border-base-300" "px-2" "py-1"]} taker]
        [:td {:class ["num" "border" "border-base-300" "px-2" "py-1"]} maker]])]]])

(defn fee-schedule-popover
  [{:keys [open?
           anchor
           title
           referral
           staking
           maker-rebate
           rows
           rate-note
           documentation-url]
    :as model}]
  (when open?
    (let [anchor* (resolved-anchor anchor)
          popover-style (anchored-popover/anchored-popover-layout-style
                         {:anchor anchor*
                          :preferred-width-px preferred-popover-width-px
                          :estimated-height-px estimated-popover-height-px})]
      [:div {:class ["fixed"
                     "inset-0"
                     "z-[650]"
                     "pointer-events-auto"]
             :data-role "portfolio-fee-schedule-overlay"}
     [:button {:type "button"
               :class ["absolute"
                       "inset-0"
                       "pointer-events-auto"
                       "bg-transparent"]
               :aria-label "Close fee schedule"
               :data-role "portfolio-fee-schedule-backdrop"
               :on {:click [[:actions/close-portfolio-fee-schedule]]}}]
     [:div {:class ["absolute"
                    "pointer-events-auto"
                    "z-[651]"
                    "max-h-[calc(100dvh-1rem)]"
                    "w-full"
                    "max-w-[30rem]"
                    "overflow-visible"
                    "rounded-lg"
                    "border"
                    "border-base-300"
                    "bg-[#0f1a1f]"
                    "shadow-[0_28px_90px_rgba(0,0,0,0.62)]"]
            :style (assoc popover-style :background-color "#0f1a1f")
            :role "dialog"
            :aria-modal false
            :aria-labelledby title-id
            :tab-index 0
            :data-role "portfolio-fee-schedule-dialog"
            :replicant/on-render dialog-focus-on-render
            :on {:keydown [[:actions/handle-portfolio-fee-schedule-keydown
                            [:event/key]]]}}
      [:div {:class ["space-y-2.5" "px-4" "pb-4" "pt-3" "sm:px-5"]}
       [:div {:class ["flex" "items-center" "justify-between" "gap-4" "border-b" "border-base-300" "pb-2"]}
        [:h2 {:id title-id
              :class ["text-base" "font-semibold" "text-trading-text"]}
         title]
        [:button {:type "button"
                  :class ["inline-flex"
                          "h-7"
                          "w-7"
                          "items-center"
                          "justify-center"
                          "rounded-md"
                          "text-trading-text-secondary"
                          "hover:bg-base-200"
                          "hover:text-trading-text"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                  :aria-label "Close fee schedule"
                  :data-role "portfolio-fee-schedule-close"
                  :on {:click [[:actions/close-portfolio-fee-schedule]]}}
         (close-icon)]]
       [:div {:class ["space-y-2"]}
        (scenario-selector :referral "REFERRAL DISCOUNT" referral)
        (scenario-selector :staking "STAKING DISCOUNT" staking)
        (scenario-selector :maker-rebate "MAKER REBATE" maker-rebate)]
       [:section {:class ["space-y-1.5"]
                  :data-role "portfolio-fee-schedule-volume-tier"}
        [:h3 {:class ["text-xs"
                      "font-semibold"
                      "uppercase"
                      "tracking-wide"
                      "text-trading-green"]}
         "VOLUME TIER"]
        (market-selector model)
        (schedule-table rows)
        [:p {:class ["text-xs" "leading-4" "text-trading-text-secondary"]
             :data-role "portfolio-fee-schedule-rate-note"}
         rate-note]]
       [:div {:class ["border-t" "border-base-300" "pt-2"]}
        [:p {:class ["text-xs" "text-trading-text-secondary"]}
         "You can read more about fees in "
         [:a {:class ["text-trading-green" "hover:text-trading-green/80"]
              :href documentation-url
              :target "_blank"
              :rel "noreferrer"
              :data-role "portfolio-fee-schedule-docs-link"}
          "Hyperliquid documentation"]]]]]])))
