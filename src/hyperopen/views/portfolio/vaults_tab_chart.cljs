(ns hyperopen.views.portfolio.vaults-tab-chart
  "Static all-time chart for the Vaults tab: vault value (solid, accent) over
  net deposited (muted), with deposit and withdrawal markers. Lines are one
  stretched SVG; markers are absolutely positioned by percentage so they stay
  round at any width.")

(def ^:private accent-stroke "rgb(var(--ho-accent))")

(def ^:private muted-stroke "rgb(var(--ho-text-muted))")

(def ^:private warn-stroke "rgb(var(--ho-warn))")

(defn- legend-item
  [swatch label]
  [:span {:class ["inline-flex" "items-center" "gap-1.5" "text-xs" "text-trading-text-secondary"]}
   swatch
   label])

(defn- legend
  []
  [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-4" "gap-y-1"]
         :data-role "portfolio-vaults-chart-legend"}
   (legend-item [:span {:class ["inline-block" "h-0.5" "w-4" "rounded-full"]
                        :style {:background-color accent-stroke}}]
                "Vault value")
   (legend-item [:span {:class ["inline-block" "h-0.5" "w-4" "rounded-full"]
                        :style {:background-color muted-stroke}}]
                "Net deposited")
   (legend-item [:span {:class ["inline-block" "h-2" "w-2" "rounded-full"]
                        :style {:background-color accent-stroke}}]
                "Deposit")
   (legend-item [:span {:class ["inline-block" "h-2" "w-2" "rounded-full" "border-2"]
                        :style {:border-color warn-stroke}}]
                "Withdrawal")])

(defn- marker
  [{:keys [left top kind title]}]
  (let [withdraw? (= :withdraw kind)]
    [:span {:class ["absolute" "h-2.5" "w-2.5" "-translate-x-1/2" "-translate-y-1/2" "rounded-full"]
            :style (cond-> {:left (str left "%")
                            :top (str top "%")}
                     withdraw? (assoc :border (str "2px solid " warn-stroke)
                                      :background-color "transparent")
                     (not withdraw?) (assoc :background-color accent-stroke))
            :title title
            :aria-label title
            :data-role (if withdraw?
                         "portfolio-vaults-chart-withdrawal"
                         "portfolio-vaults-chart-deposit")}]))

(defn- line
  [points stroke width]
  [:polyline {:points points
              :fill "none"
              :stroke stroke
              :stroke-width width
              :stroke-linejoin "round"
              :vector-effect "non-scaling-stroke"}])

(defn chart
  [{:keys [value-points net-points ticks first-label last-label markers]}]
  [:div {:class ["flex" "flex-col" "gap-2"]
         :data-role "portfolio-vaults-chart"}
   (legend)
   [:div {:class ["relative" "h-56" "pl-14" "pr-2"]}
    (into [:div {:class ["absolute" "inset-y-0" "left-0" "w-12"]
                 :aria-hidden true}]
          (map (fn [{:keys [top label]}]
                 [:span {:class ["num" "absolute" "right-0" "-translate-y-1/2" "text-xs"
                                 "text-trading-text-secondary"]
                         :style {:top (str top "%")}
                         :data-role "portfolio-vaults-chart-y-label"}
                  label]))
          ticks)
    (into [:div {:class ["relative" "h-full" "w-full"]}
           (into [:svg {:viewBox "0 0 100 100"
                        :preserveAspectRatio "none"
                        :class ["absolute" "inset-0" "h-full" "w-full" "overflow-visible"]
                        :role "img"
                        :aria-label "Vault value against net deposited"}]
                 (concat
                  (map (fn [{:keys [top]}]
                         [:line {:x1 "0" :x2 "100" :y1 top :y2 top
                                 :stroke muted-stroke
                                 :stroke-opacity "0.18"
                                 :stroke-width "1"
                                 :vector-effect "non-scaling-stroke"}])
                       ticks)
                  [(line net-points muted-stroke "1.5")
                   (line value-points accent-stroke "2")]))]
          (map marker)
          markers)]
   [:div {:class ["flex" "justify-between" "pl-14" "pr-2" "text-xs" "text-trading-text-secondary"]}
    [:span {:data-role "portfolio-vaults-chart-first-date"} first-label]
    [:span {:data-role "portfolio-vaults-chart-last-date"} last-label]]])
