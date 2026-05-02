(ns hyperopen.views.active-asset.row
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.active-asset.funding-tooltip :as funding-tooltip]
            [hyperopen.views.active-asset.icon-button :as icon-button]
            [hyperopen.views.active-asset.vm :as active-asset-vm]))

(def ^:private desktop-breakpoint-px
  1024)

(def active-asset-grid-template
  "md:grid-cols-[minmax(max-content,1.4fr)_minmax(0,0.9fr)_minmax(0,0.9fr)_minmax(0,1.1fr)_minmax(0,1.1fr)_minmax(0,1.2fr)_minmax(0,1.6fr)]")

(defn- viewport-width-px []
  (let [width (some-> js/globalThis .-innerWidth)]
    (if (number? width)
      width
      desktop-breakpoint-px)))

(defn- desktop-layout? []
  (>= (viewport-width-px) desktop-breakpoint-px))

(defn- render-visible-branch [mobile-render desktop-render]
  (if (desktop-layout?)
    (desktop-render)
    (mobile-render)))

(defn- change-indicator [change-value change-pct & [change-raw]]
  (let [is-positive (and change-value (>= change-value 0))
        color-class (if is-positive "text-success" "text-error")]
    [:span {:class [color-class "num"]}
     (str (or (fmt/format-trade-price-delta change-value change-raw) "--")
          " / "
          (or (fmt/format-percentage change-pct) "--"))]))

(defn- data-column [label value & [options]]
  (let [underlined? (:underlined options)
        value-component (if (:change? options)
                          (change-indicator (:change-value options)
                                            (:change-pct options)
                                            (:change-raw options))
                          [:span {:class (into ["font-medium"]
                                               (when (:numeric? options) ["num"]))}
                           value])]
    [:div {:class ["text-center"]}
     [:div {:class (into ["mb-1" "text-xs" "text-gray-400"]
                         (when underlined? ["border-b" "border-dashed" "border-gray-600"]))}
      label]
     [:div {:class (into ["text-xs"]
                         (when (:numeric? options) ["num"]))}
      value-component]]))

(defn- asset-selector-trigger [dropdown-visible?]
  [:button {:class ["flex"
                    "items-center"
                    "space-x-2"
                    "cursor-pointer"
                    "rounded"
                    "pr-2"
                    "py-1"
                    "transition-colors"
                    "hover:bg-base-300"]
            :type "button"
            :on {:click [[:actions/toggle-asset-dropdown :asset-selector]]
                 :keydown [[:actions/handle-asset-selector-shortcut
                            [:event/key]
                            [:event/metaKey]
                            [:event/ctrlKey]
                            []]]}}
   [:div {:class ["flex" "h-6" "w-6" "items-center" "justify-center" "rounded-full" "bg-base-300"]}
    [:svg {:class ["h-4" "w-4" "text-gray-400"]
           :fill "none"
           :stroke "currentColor"
           :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round"
             :stroke-linejoin "round"
             :stroke-width 2
             :d "m21 21-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"}]]]
   [:span {:class ["font-medium"]} "Select Asset"]
   [:svg {:class (into ["h-4" "w-4" "text-gray-400" "transition-transform"]
                       (when dropdown-visible? ["rotate-180"]))
          :fill "none"
          :stroke "currentColor"
          :viewBox "0 0 24 24"}
    [:path {:stroke-linecap "round"
            :stroke-linejoin "round"
            :stroke-width 2
            :d "M19 9l-7 7-7-7"}]]])

(defn- funding-rate-node
  [{:keys [funding-rate
           funding-tooltip-open?
           funding-tooltip-model
           funding-tooltip-id
           funding-tooltip-pinned?]}
   {:keys [trigger-classes underlined? mobile-sheet?]}]
  (if (number? funding-rate)
    (let [trigger [:span {:class (into ["cursor-help"
                                        "num"
                                        (funding-tooltip/signed-tone-class funding-rate)]
                                       (cond-> trigger-classes
                                         underlined? (into ["underline"
                                                            "decoration-dashed"
                                                            "underline-offset-2"])))}
                   (funding-tooltip/signed-percentage-text funding-rate 4)]
          body (when funding-tooltip-open?
                 (funding-tooltip/funding-tooltip-panel
                  funding-tooltip-model
                  (when mobile-sheet?
                    {:mobile-sheet? true
                     :attrs {:role "dialog"
                             :aria-modal true
                             :aria-label "Funding details"
                             :data-role "active-asset-funding-mobile-sheet"}})))]
      (if mobile-sheet?
        (funding-tooltip/funding-tooltip-mobile-sheet
         {:trigger trigger
          :body body
          :open? funding-tooltip-open?
          :pin-id funding-tooltip-id
          :pinned? funding-tooltip-pinned?})
        (funding-tooltip/funding-tooltip-popover
         {:trigger trigger
          :body body
          :position "bottom"
          :open? funding-tooltip-open?
          :pin-id funding-tooltip-id
          :pinned? funding-tooltip-pinned?})))
    [:span {:class ["num" "text-trading-text-secondary"]} "Loading..."]))

(defn- mobile-detail-item
  [label value-node]
  [:div {:class ["min-w-0" "space-y-1"]}
   [:div {:class ["text-xs" "font-medium" "text-trading-text-secondary" "whitespace-nowrap"]}
    label]
   value-node])

(defn- mobile-details-panel
  [{:keys [mark
           mark-raw
           oracle
           oracle-raw
           volume-24h
           open-interest-usd
           countdown-text
           is-spot]
    :as row-vm}]
  [:div {:class ["grid"
                 "grid-cols-2"
                 "gap-x-4"
                 "gap-y-3"
                 "border-t"
                 "border-base-300/80"
                 "pt-2.5"]
         :data-role "trade-mobile-asset-details-panel"}
   (mobile-detail-item
    "Mark / Oracle"
    [:div {:class ["flex"
                   "items-center"
                   "gap-1"
                   "whitespace-nowrap"
                   "text-xs"
                   "font-semibold"
                   "text-trading-text"]}
     [:span {:class ["num"]}
      (if (some? mark)
        (fmt/format-trade-price mark mark-raw)
        "Loading...")]
     [:span {:class ["text-trading-text-secondary"]} "/"]
     [:span {:class ["num" "text-trading-text-secondary"]}
      (if (and (not is-spot) (some? oracle))
        (fmt/format-trade-price oracle oracle-raw)
        (if is-spot "—" "Loading..."))]])
   (mobile-detail-item
    "24h Volume"
    [:div {:class ["text-sm" "font-semibold" "num" "text-trading-text"]}
     (if (some? volume-24h)
       (fmt/format-large-currency volume-24h)
       "Loading...")])
   (mobile-detail-item
    "Open Interest"
    [:div {:class ["text-sm" "font-semibold" "num" "text-trading-text"]}
     (cond
       is-spot "—"
       (some? open-interest-usd) (fmt/format-large-currency open-interest-usd)
       :else "Loading...")])
   (mobile-detail-item
    "Funding / Countdown"
    [:div {:class ["flex"
                   "items-center"
                   "gap-1"
                   "whitespace-nowrap"
                   "text-xs"
                   "font-semibold"
                   "text-trading-text"]}
     (if is-spot
       [:span {:class ["num" "text-trading-text-secondary"]} "--"]
       (funding-rate-node row-vm {:trigger-classes []
                                  :underlined? true
                                  :mobile-sheet? true}))
     [:span {:class ["text-trading-text-secondary"]} "/"]
     [:span {:class ["num" "text-trading-text-secondary"]}
      (if is-spot "--" countdown-text)]])])

(defn- mobile-active-asset-row
  [{:keys [icon-market
           dropdown-visible?
           details-open?
           missing-icons
           loaded-icons
           mark
           mark-raw
           change-24h
           change-24h-pct]
    :as row-vm}]
  [:div {:class ["lg:hidden" "space-y-2" "px-3" "py-2.5"]
         :data-role "trade-mobile-asset-summary"}
   [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
    [:div {:class ["min-w-0" "flex-1"]}
     (icon-button/asset-button icon-market
                               dropdown-visible?
                               missing-icons
                               loaded-icons)]
    [:div {:class ["flex" "items-start" "gap-2.5"]}
     [:div {:class ["space-y-1" "text-right"]}
      [:div {:class ["num" "text-[1.625rem]" "font-semibold" "leading-none" "text-trading-text"]}
       (if (some? mark)
         (fmt/format-trade-price mark mark-raw)
         "Loading...")]
      [:div {:class ["text-xs"]}
       (if (some? change-24h)
         (change-indicator change-24h change-24h-pct)
         [:span {:class ["text-trading-text-secondary"]} "--"])]]
     [:button {:type "button"
               :class ["inline-flex"
                       "h-10"
                       "w-10"
                       "items-center"
                       "justify-center"
                       "rounded-xl"
                       "border"
                       "border-base-300"
                       "bg-base-100"
                       "transition-colors"
                       "hover:bg-base-200"]
               :on {:click [[:actions/toggle-trade-mobile-asset-details]]}
               :aria-label (if details-open?
                             "Hide market details"
                             "Show market details")
               :data-role "trade-mobile-asset-details-toggle"}
      [:svg {:viewBox "0 0 20 20"
             :fill "currentColor"
             :class (into ["h-5" "w-5" "text-trading-text-secondary" "transition-transform"]
                          (when details-open? ["rotate-180"]))}
       [:path {:fill-rule "evenodd"
               :clip-rule "evenodd"
               :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]]]]
   (when details-open?
     (mobile-details-panel row-vm))])

(defn- desktop-funding-cell
  [{:keys [is-spot countdown-text] :as row-vm}]
  [:div {:class ["text-center"]}
   [:div {:class ["mb-1" "text-xs" "text-gray-400"]} "Funding / Countdown"]
   [:div {:class ["flex" "items-center" "justify-center" "text-xs"]}
    (if is-spot
      [:span "—"]
      (funding-rate-node row-vm {:trigger-classes []
                                 :underlined? false}))
    [:span {:class ["mx-1"]} "/"]
    [:span {:class ["num"]} (if is-spot "—" countdown-text)]]])

(defn- desktop-active-asset-row
  [{:keys [icon-market
           dropdown-visible?
           missing-icons
           loaded-icons
           mark
           mark-raw
           oracle
           oracle-raw
           change-24h
           change-24h-pct
           volume-24h
           open-interest-usd
           is-spot]
    :as row-vm}]
  [:div {:class ["relative"
                 "hidden"
                 "grid-cols-7"
                 "items-center"
                 "gap-2"
                 "px-0"
                 "py-2"
                 "lg:grid"
                 "md:gap-3"
                 active-asset-grid-template]}
   [:div {:class ["flex" "justify-start" "app-shell-gutter-left" "min-w-fit"]}
    (icon-button/asset-button icon-market
                              dropdown-visible?
                              missing-icons
                              loaded-icons)]
   [:div {:class ["flex" "justify-center"]}
    (data-column "Mark"
                 (if (some? mark)
                   (fmt/format-trade-price mark mark-raw)
                   "Loading...")
                 {:underlined true
                  :numeric? true})]
   [:div {:class ["flex" "justify-center"]}
    (data-column "Oracle"
                 (if (and (not is-spot) (some? oracle))
                   (fmt/format-trade-price oracle oracle-raw)
                   (if is-spot "—" "Loading..."))
                 {:underlined true
                  :numeric? true})]
   [:div {:class ["flex" "justify-center"]}
    (data-column "24h Change"
                 (if (some? change-24h) nil "Loading...")
                 {:change? (some? change-24h)
                  :change-value change-24h
                  :change-pct change-24h-pct
                  :change-raw nil
                  :numeric? true})]
   [:div {:class ["flex" "justify-center"]}
    (data-column "24h Volume"
                 (if (some? volume-24h)
                   (fmt/format-large-currency volume-24h)
                   "Loading...")
                 {:numeric? true})]
   [:div {:class ["flex" "justify-center"]}
    (data-column "Open Interest"
                 (cond
                   is-spot "—"
                   (some? open-interest-usd) (fmt/format-large-currency open-interest-usd)
                   :else "Loading...")
                 {:underlined true
                  :numeric? true})]
   [:div {:class ["flex" "justify-center"]}
    (desktop-funding-cell row-vm)]])

(defn- outcome-details-panel
  [{:keys [outcome-details]}]
  (when (seq outcome-details)
    [:div {:class ["absolute" "left-4" "top-full" "z-[240]" "mt-1"
                   "w-[min(28rem,calc(100vw-2rem))]" "rounded-md" "border"
                   "border-base-300" "bg-base-100" "p-3" "text-xs" "leading-5"
                   "text-trading-text-secondary" "shadow-xl" "opacity-0"
                   "pointer-events-none" "transition-opacity" "duration-150"
                   "group-hover/outcome-name:opacity-100"
                   "group-hover/outcome-name:pointer-events-auto"
                   "group-focus-within/outcome-name:opacity-100"
                   "group-focus-within/outcome-name:pointer-events-auto"]
           :role "dialog"
           :data-role "outcome-details-popover"}
     outcome-details]))

(defn- outcome-open-interest-column
  [{:keys [open-interest-usd open-interest-tooltip]}]
  [:div {:class ["relative" "group/outcome-open-interest" "flex" "justify-center"]}
   (data-column "Open Interest"
                (if (some? open-interest-usd)
                  (fmt/format-large-currency open-interest-usd)
                  "Loading...")
                {:numeric? true
                 :underlined true})
   (when (seq open-interest-tooltip)
     [:div {:class ["absolute" "right-0" "bottom-full" "z-[240]" "mb-1"
                    "w-[min(24rem,calc(100vw-2rem))]"
                    "whitespace-normal" "rounded-md" "bg-base-200" "px-3"
                    "py-2" "text-xs" "font-medium" "leading-4"
                    "text-left" "text-trading-text" "shadow-xl" "opacity-0"
                    "pointer-events-none" "transition-opacity" "duration-150"
                    "group-hover/outcome-open-interest:opacity-100"
                    "group-hover/outcome-open-interest:pointer-events-auto"
                    "group-focus-within/outcome-open-interest:opacity-100"
                    "group-focus-within/outcome-open-interest:pointer-events-auto"]
            :role "tooltip"
            :data-role "outcome-open-interest-tooltip"}
      open-interest-tooltip])])

(defn- desktop-outcome-active-asset-row
  [{:keys [icon-market
           dropdown-visible?
           missing-icons
           loaded-icons
           mark
           mark-raw
           change-24h
           change-24h-pct
           volume-24h
           open-interest-usd
           outcome-chance-label
           countdown-text]
    :as row-vm}]
  [:div {:class ["relative" "hidden"
                 "grid-cols-[max-content_minmax(0,0.8fr)_minmax(0,0.8fr)_minmax(0,0.9fr)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)]"
                 "items-center" "gap-2" "px-0" "py-2" "lg:grid" "md:gap-3"]}
   [:div {:class ["relative" "group/outcome-name" "flex" "justify-start"
                  "app-shell-gutter-left" "min-w-fit" "items-center" "gap-3"]
          :data-role "outcome-market-name-hover-region"}
    (icon-button/asset-button icon-market
                              dropdown-visible?
                              missing-icons
                              loaded-icons)
    (outcome-details-panel row-vm)]
   [:div {:class ["flex" "justify-center"]}
    (data-column "Countdown" (or countdown-text "—") {:numeric? true})]
   [:div {:class ["flex" "justify-center"]}
    (data-column "% Chance"
                 (or outcome-chance-label "—")
                 {:numeric? true})]
   [:div {:class ["flex" "justify-center"]}
    (data-column "Price (Yes)"
                 (if (some? mark)
                   (fmt/format-trade-price mark mark-raw)
                   "Loading...")
                 {:numeric? true})]
   [:div {:class ["flex" "justify-center"]}
    (data-column "24h Change"
                 (if (some? change-24h) nil "Loading...")
                 {:change? (some? change-24h)
                  :change-value change-24h
                  :change-pct change-24h-pct
                  :change-raw nil
                  :numeric? true})]
   [:div {:class ["flex" "justify-center"]}
    (data-column "24h Volume"
                 (if (some? volume-24h)
                   (fmt/format-large-currency volume-24h)
                   "Loading...")
                 {:numeric? true})]
   (outcome-open-interest-column row-vm)])

(defn active-asset-row-from-vm [row-vm]
  [:div
   (render-visible-branch #(mobile-active-asset-row row-vm)
                          #(if (:is-outcome row-vm)
                             (desktop-outcome-active-asset-row row-vm)
                             (desktop-active-asset-row row-vm)))])

(defn active-asset-row [ctx-data market dropdown-state full-state]
  (active-asset-row-from-vm
   (active-asset-vm/active-asset-row-vm ctx-data market dropdown-state full-state)))

(defn- mobile-select-asset-row
  [dropdown-visible?]
  [:div {:class ["lg:hidden" "space-y-2" "px-3" "py-2.5"]}
   [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
    [:div {:class ["min-w-0" "flex-1"]}
     (asset-selector-trigger dropdown-visible?)]
    [:div {:class ["space-y-1" "text-right"]}
     [:div {:class ["num" "text-[1.625rem]" "font-semibold" "leading-none" "text-trading-text-secondary"]} "—"]
     [:div {:class ["text-xs" "text-trading-text-secondary"]} "--"]]]
   [:div {:class ["rounded-xl"
                  "border"
                  "border-dashed"
                  "border-base-300/70"
                  "bg-base-100/40"
                  "px-3"
                  "py-2"
                  "text-xs"
                  "text-trading-text-secondary"]}
    "Select a market to view price, liquidity, and funding details."]])

(defn- desktop-select-asset-row
  [dropdown-visible?]
  [:div {:class ["relative"
                 "hidden"
                 "grid-cols-7"
                 "items-center"
                 "gap-2"
                 "px-0"
                 "py-2"
                 "lg:grid"
                 "md:gap-3"
                 active-asset-grid-template]}
   [:div {:class ["flex" "justify-start" "app-shell-gutter-left" "min-w-fit"]}
    (asset-selector-trigger dropdown-visible?)]
   [:div {:class ["flex" "justify-center"]}
    (data-column "Mark" "—" {:underlined true})]
   [:div {:class ["flex" "justify-center"]}
    (data-column "Oracle" "—" {:underlined true})]
   [:div {:class ["flex" "justify-center"]}
    (data-column "24h Change" "—")]
   [:div {:class ["flex" "justify-center"]}
    (data-column "24h Volume" "—")]
   [:div {:class ["flex" "justify-center"]}
    (data-column "Open Interest" "—" {:underlined true})]
   [:div {:class ["flex" "justify-center"]}
    [:div {:class ["text-center"]}
     [:div {:class ["mb-1" "text-xs" "text-gray-400"]} "Funding / Countdown"]
     [:div {:class ["text-xs" "text-gray-400"]} "— / —"]]]])

(defn select-asset-row [dropdown-state]
  (let [dropdown-visible? (= (:visible-dropdown dropdown-state) :asset-selector)]
    [:div
     (render-visible-branch #(mobile-select-asset-row dropdown-visible?)
                            #(desktop-select-asset-row dropdown-visible?))]))
