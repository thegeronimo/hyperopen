(ns hyperopen.views.active-asset.market-info
  "\"About this market\" for HIP-3 markets: an info trigger beside the ticker,
  a panel under the market strip on desktop, and a bottom sheet below `lg`.
  Renders `hyperopen.asset-selector.market-info/market-info-model`.")

(defn info-trigger
  "Icon button that opens and closes the panel. `size` is :desktop or :mobile;
  mobile gets a 44px touch target."
  [open? size]
  [:button {:type "button"
            :class (into ["inline-flex" "shrink-0" "items-center" "justify-center" "rounded-md" "border"
                          "transition-colors" "focus:outline-none" "focus-visible:ring-1" "focus-visible:ring-primary"]
                         (concat
                          (if (= size :mobile) ["h-11" "w-11"] ["h-7" "w-7"])
                          (if open?
                            ["border-primary/40" "bg-primary/10" "text-primary"]
                            ["border-transparent" "text-trading-text-secondary" "hover:text-trading-text"
                             "hover:bg-base-300/60"])))
            :aria-label "About this market"
            :aria-expanded (boolean open?)
            :aria-haspopup "dialog"
            :data-role "market-info-toggle"
            :on {:click [[:actions/toggle-market-info-panel]]}}
   [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width 2
          :stroke-linecap "round" :class ["h-4" "w-4"] :aria-hidden true}
    [:circle {:cx 12 :cy 12 :r 9}]
    [:path {:d "M12 11v5"}]
    [:path {:d "M12 8h.01"}]]])

(defn mobile-details-entry
  "Full-width row at the end of the mobile market-details drawer, where a
  phone has room the header's ticker line does not."
  []
  [:button {:type "button"
            :class ["col-span-2" "flex" "min-h-[44px]" "w-full" "items-center" "justify-between" "rounded-lg" "border"
                    "border-base-300" "px-3" "text-sm" "font-medium" "text-trading-text" "hover:bg-base-300/60"]
            :aria-haspopup "dialog"
            :data-role "market-info-mobile-entry"
            :on {:click [[:actions/toggle-market-info-panel]]}}
   [:span "About this market"]
   [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width 2
          :stroke-linecap "round" :class ["h-4" "w-4" "text-trading-text-secondary"] :aria-hidden true}
    [:path {:d "M9 6l6 6-6 6"}]]])

(defn- close-button
  [size]
  [:button {:type "button"
            :class (into ["inline-flex" "shrink-0" "items-center" "justify-center" "rounded-md"
                          "text-trading-text-secondary" "hover:text-trading-text" "hover:bg-base-300/60"]
                         (if (= size :mobile) ["h-11" "w-11"] ["h-8" "w-8"]))
            :aria-label "Close market details"
            :data-role "market-info-close"
            :on {:click [[:actions/close-market-info-panel]]}}
   [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width 2
          :stroke-linecap "round" :class ["h-4" "w-4"] :aria-hidden true}
    [:path {:d "M6 6l12 12M18 6L6 18"}]]])

(defn- chip
  [label classes]
  [:span {:class (into ["inline-flex" "items-center" "rounded-full" "px-2" "py-0.5" "text-xs" "font-medium"]
                       classes)}
   label])

(defn- status-copy
  [{:keys [status deployer]}]
  (case status
    :loading "Loading market details…"
    :undescribed (str deployer " hasn't published a description for this market.")
    nil))

(defn- panel-body
  [{:keys [symbol headline details category-label warning facts keywords deployer] :as model} size]
  [:div {:class ["flex" "flex-col"]}
   [:div {:class ["flex" "items-start" "justify-between" "gap-3" "border-b" "border-base-300" "px-4" "pb-3" "pt-3"]}
    [:div {:class ["min-w-0" "space-y-2"]}
     [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
      [:h2 {:class ["text-xs" "font-semibold" "uppercase" "tracking-wider" "text-trading-text-secondary"]
            :id (str "market-info-title-" (name size))}
       (str "About " symbol)]
      (when category-label
        (chip category-label ["bg-primary/10" "text-primary"]))
      (when warning
        (chip warning ["bg-warning/10" "text-warning"]))]
     (when headline
       [:p {:class ["text-base" "font-semibold" "leading-snug" "text-trading-text"]
            :data-role "market-info-headline"}
        headline])
     (when details
       [:p {:class ["text-sm" "leading-relaxed" "text-trading-text-secondary"]
            :data-role "market-info-details"}
        details])
     (when-let [copy (status-copy model)]
       [:p {:class ["text-sm" "text-trading-text-secondary"]
            :data-role "market-info-status"}
        copy])]
    (close-button size)]
   (when (seq facts)
     [:dl {:class ["grid" "grid-cols-2" "gap-x-6" "gap-y-3" "px-4" "py-3"]
           :data-role "market-info-facts"}
      (for [{:keys [label value]} facts]
        ^{:key label}
        [:div {:class ["min-w-0"]}
         [:dt {:class ["text-xs" "text-trading-text-secondary"]} label]
         [:dd {:class ["truncate" "text-sm" "text-trading-text"]} value]])])
   [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-2" "border-t" "border-base-300"
                  "px-4" "py-3"]}
    [:div {:class ["flex" "flex-wrap" "gap-1.5"]}
     (for [keyword keywords]
       ^{:key keyword}
       [:span {:class ["num" "rounded" "bg-base-300/60" "px-1.5" "py-0.5" "text-xs" "text-trading-text-secondary"]}
        (str "#" keyword)])]
    [:span {:class ["text-xs" "text-trading-text-secondary"]}
     (str "Published on-chain by " deployer)]]])

(defn market-info-panel
  "Nil when closed. Deployer text renders as plain text nodes."
  [model]
  (when model
    [:div {:data-role "market-info"}
     ;; Desktop: under the strip, outside its horizontal scroll container.
     [:div {:class ["fixed" "inset-0" "z-[210]" "hidden" "lg:block"]
            :aria-hidden true
            :on {:click [[:actions/close-market-info-panel]]}}]
     [:section {:class ["absolute" "left-0" "top-full" "z-[220]" "mt-1" "hidden" "w-[40rem]" "max-w-[calc(100vw-2rem)]"
                        "rounded-lg" "border" "border-base-300" "bg-base-100" "shadow-xl" "lg:block"
                        "lg:left-3"]
                :role "dialog"
                :aria-labelledby "market-info-title-desktop"
                :data-role "market-info-panel"}
      (panel-body model :desktop)]
     ;; Mobile: bottom sheet over a dimmed backdrop.
     [:div {:class ["fixed" "inset-0" "z-[260]" "flex" "flex-col" "justify-end" "lg:hidden"]
            :data-role "market-info-sheet"}
      [:button {:type "button"
                :class ["flex-1" "bg-black/50"]
                :aria-label "Close market details"
                :on {:click [[:actions/close-market-info-panel]]}}]
      [:section {:class ["max-h-[85vh]" "overflow-y-auto" "rounded-t-2xl" "border-t" "border-base-300" "bg-base-100"
                         "pb-6"]
                 :role "dialog"
                 :aria-modal true
                 :aria-labelledby "market-info-title-mobile"}
       [:div {:class ["flex" "justify-center" "pt-2"]}
        [:span {:class ["h-1" "w-10" "rounded-full" "bg-base-300"]}]]
       (panel-body model :mobile)]]]))
