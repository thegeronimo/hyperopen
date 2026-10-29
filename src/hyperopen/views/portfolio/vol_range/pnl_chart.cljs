(ns hyperopen.views.portfolio.vol-range.pnl-chart
  "The cumulative P&L chart of the Vol Range report (HO-SPEC-014 v2.1
  section 12.4): Net tracked asset P&L and Hold P&L at the window's
  checkpoints on the shared d3 chart host (`:surface :vol-range-report`).
  The axis title is \"Cumulative P&L (USDC)\"; it is never named account
  value. Gaps arrive as separate segment series with the same stroke.

  The d3 spec is built from the memoized view model and carries its own
  `:update-key`, so the memoized `on-render` handler is reused until the
  chart's data changes and replaced (not accumulated) when it does.
  Renders the view model only; dispatches nothing."
  (:require [hyperopen.views.chart.d3.runtime :as chart-runtime]))

(def ^:private strokes
  "Theme color tokens are RGB channel triplets (docs/THEMING.md)."
  {:net "rgb(var(--ho-accent))" :hold "rgb(var(--ho-text-secondary))"})

(def ^:private theme
  {:data-role-prefix "vol-range-report-chart"
   :baseline-stroke "rgb(var(--ho-border))"
   :baseline-stroke-width 0.8
   :hover-line-stroke "rgb(var(--ho-text-muted))"
   :line-stroke-width 1.6
   :line-linecap "round"
   :line-linejoin "round"
   :tooltip-border-color "rgb(var(--ho-border))"
   :tooltip-background "rgb(var(--ho-surface-raised))"})

(defn- tone-classes [tone]
  (case tone :positive ["text-success"] :negative ["text-error"] []))

(defn- tooltip-model [legend {:keys [time-text net hold]}]
  (let [label (into {} (map (juxt :id :label)) legend)]
    {:timestamp time-text
     :metric-label (get label :net)
     :metric-value (:display net)
     :value-classes (tone-classes (:tone net))
     :benchmark-values [{:coin "hold" :label (get label :hold) :value (:display hold) :stroke (:hold strokes)}]}))

(defn d3-spec
  "The shared chart host's spec for the chart view model."
  [{:keys [points series y-ticks tooltips legend x-labels cadence]}]
  (let [base {:surface :vol-range-report
              :axis-kind :pnl
              :time-range [cadence x-labels]
              :points points
              :series (mapv #(assoc % :stroke (get strokes (:kind %))) series)
              :y-ticks y-ticks
              :tooltip-key tooltips
              :theme theme}]
    (assoc base
           :update-key (chart-runtime/spec-update-key base)
           :build-tooltip (fn [{:keys [index]} _series]
                            (tooltip-model legend (nth tooltips index nil))))))

(defn- legend-row [legend]
  [:ul {:class ["flex" "flex-wrap" "items-center" "gap-3" "text-xs" "text-ho-text-secondary"]
        :data-role "vol-range-report-chart-legend"}
   (for [{:keys [id label]} legend]
     ^{:key (name id)}
     [:li {:class ["flex" "items-center" "gap-1.5"] :data-role (str "vol-range-report-chart-legend-" (name id))}
      [:span {:class ["inline-block" "w-4" "border-t-2" "border-solid"]
              :style {:border-color (get strokes id)}
              :aria-hidden "true"}]
      [:span label]])])

(defn- y-axis [y-ticks]
  [:div {:class ["absolute" "left-0" "top-0" "bottom-0" "w-[76px]" "border-r" "border-ho-border"]
         :data-role "vol-range-report-chart-y-axis"
         :aria-hidden "true"}
   (for [{:keys [value y-ratio]} y-ticks]
     ^{:key (str y-ratio)}
     [:span {:class ["absolute" "right-2" "-translate-y-1/2" "num" "text-right" "text-xs" "text-ho-text-secondary"]
             :style {:top (str (* 100 y-ratio) "%")}}
      value])])

(defn pnl-chart
  [{:keys [title caption legend y-ticks x-labels end-text] :as chart}]
  (when chart
    [:figure {:class ["flex" "flex-col" "gap-2" "rounded-md" "border" "border-ho-border" "p-3"]
              :data-role "vol-range-report-chart"}
     [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-2"]}
      [:h3 {:class ["text-sm" "font-semibold" "text-ho-text"] :data-role "vol-range-report-chart-axis-title"} title]
      (legend-row legend)]
     [:div {:class ["relative" "h-[180px]" "md:h-[210px]"]}
      (y-axis y-ticks)
      [:div {:class ["absolute" "right-2" "top-2" "bottom-2" "left-[86px]" "cursor-crosshair"]
             :role "img"
             :aria-label title
             :data-role "vol-range-report-chart-plot-area"}
       [:div {:class ["h-full" "w-full"]
              :data-role "vol-range-report-chart-d3-host"
              :replicant/on-render (chart-runtime/on-render (d3-spec chart))}]]]
     [:div {:class ["flex" "justify-between" "gap-2" "pl-[86px]" "text-xs" "text-ho-text-muted" "num"]
            :data-role "vol-range-report-chart-x-axis"}
      [:span (:start x-labels)]
      [:span (:end x-labels)]]
     [:figcaption {:class ["text-xs" "text-ho-text-muted"] :data-role "vol-range-report-chart-caption"}
      caption
      (when end-text
        [:span {:class ["block"] :data-role "vol-range-report-chart-end"} end-text])]]))
