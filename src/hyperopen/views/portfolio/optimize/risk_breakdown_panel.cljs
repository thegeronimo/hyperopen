(ns hyperopen.views.portfolio.optimize.risk-breakdown-panel
  "The ALL ASSETS sub-view of the Equal Risk BREAKDOWN tab: one lane per
  balance-chart row (same cap, same signed-share display order) splitting the
  signed net contribution into its own-variance term (always positive,
  purple) and cross-covariance effect (signed, green/red). The second segment
  is anchored at the own endpoint and ends at the purple net marker, making
  the additive identity visible against the shared dashed equal-target line
  behind. Numeric Div / Net columns mirror the contribution tab's
  Target / Deviation columns. The tab's default per-asset sub-view and the
  toggle between the two live in risk-asset-breakdown-panel, which composes
  this chart as the second view.

  Also home of the small plot primitives (backdrop, axis, lane scale ticks)
  the per-asset panel reuses — both views draw the same decomposition
  language, just grouped differently."
  (:require [hyperopen.portfolio.optimizer.application.view-model.equal-risk-structure
             :as structure-model]))

(defn format-signed-pct
  [value]
  (if (and (number? value) (js/isFinite value))
    (str (when (>= value 0) "+") (.toFixed (* 100 value) 1) "%")
    "—"))

(defn- sign-token
  [value]
  (cond
    (not (and (number? value) (js/isFinite value))) nil
    (neg? value) "negative"
    :else "positive"))

(defn plot-backdrop
  "Gridlines + zero axis + the continuous dashed equal-target line, drawn once
  behind a stack of lane rows. `row-class` picks the grid template (the
  4-column balance grid here, the 3-column decomposition grid in the
  correlation panel); `lead-cells`/`tail-cells` pad the non-lane columns."
  [{:keys [x] :as scale} target-share
   {:keys [row-class lead-cells tail-cells max-labels]
    :or {max-labels 8}}]
  (into [:div {:class [row-class "optimizer-risk-balance-backdrop"
                       "pointer-events-none"]}]
        (concat
         (repeat lead-cells [:span])
         [(into [:div {:class ["relative" "h-full"]}]
                (concat
                 (map (fn [tick]
                        [:div {:class ["optimizer-risk-balance-gridline"]
                               :style {:left (str (x tick) "%")}}])
                      (remove zero? (structure-model/scale-ticks scale
                                                                 max-labels)))
                 [[:div {:class ["optimizer-risk-balance-zeroline"]
                         :style {:left (str (x 0) "%")}}]]
                 (when (number? target-share)
                   [[:div {:class ["optimizer-risk-balance-targetline"]
                           :style {:left (str (x target-share) "%")}}]])))]
         (repeat tail-cells [:span]))))

(defn axis-rows
  "% tick row (+ optional title row when `title` is non-nil) padded into the
  same grid template as the lanes above it."
  [{:keys [x] :as scale}
   {:keys [title row-class lead-cells tail-cells max-labels]
    :or {max-labels 8}}]
  (let [pad (fn [content]
              (into [:div {:class [row-class]}]
                    (concat (repeat lead-cells [:span])
                            [content]
                            (repeat tail-cells [:span]))))]
    [:div
     (pad (into [:div {:class ["optimizer-risk-balance-axis" "relative"]}]
                (map (fn [tick]
                       [:span {:class ["optimizer-risk-balance-axis-label"
                                       "absolute" "-translate-x-1/2"
                                       "font-mono"]
                               :style {:left (str (x tick) "%")}}
                        (structure-model/format-pct tick 0)])
                     (structure-model/scale-ticks scale max-labels))))
     (when title
       (pad [:p {:class ["optimizer-risk-balance-axis-title"]} title]))]))

(defn- component-segment
  [{:keys [x]} start end kind]
  (when (and (number? start) (js/isFinite start)
             (number? end) (js/isFinite end))
    (let [xs (x start)
          xe (x end)
          effect (- end start)]
      (when (> (js/Math.abs effect) 1e-12)
        [:div {:class ["optimizer-risk-decomp-bar"]
               :data-kind kind
               :data-sign (sign-token effect)
               :style {:left (str (min xs xe) "%")
                       :width (str (max 0.4 (js/Math.abs (- xe xs))) "%")}}]))))

(defn- net-marker
  [{:keys [x]} value]
  (when (and (number? value) (js/isFinite value))
    [:div {:class ["optimizer-risk-decomp-net" "optimizer-risk-balance-marker"]
           :data-role "portfolio-optimizer-risk-breakdown-net"
           :data-tone "target"
           :style {:left (str (x value) "%")}}]))

(defn- breakdown-lane
  "Additive bridge: own variance runs from zero to its endpoint; the
  cross-covariance segment starts there and ends at net."
  [scale {:keys [own-end cross-start cross-end net-end]}]
  [:div {:class ["optimizer-risk-balance-lane" "relative" "min-w-0"]}
   (component-segment scale 0 own-end "standalone")
   (component-segment scale cross-start cross-end "diversification")
   (net-marker scale net-end)])

(defn- cross-semantics
  [effect]
  (cond
    (or (not (number? effect)) (not (js/isFinite effect)))
    {:effect nil :tone nil :label "unavailable"}
    (< (js/Math.abs effect) structure-model/cross-effect-neutral-threshold)
    {:effect "neutral" :tone "neutral" :label "neutral"}
    (neg? effect)
    {:effect "offsets" :tone "risk-offsetting" :label "offsets"}
    :else
    {:effect "amplifies" :tone "risk-amplifying" :label "amplifies"}))

(defn- breakdown-row
  [scale {:keys [instrument-id label standalone diversification share
                 own-end cross-start cross-end net-end]
          :as row}]
  (let [{:keys [effect tone] :as semantics}
        (cross-semantics diversification)
        effect-label (:label semantics)]
    [:div {:class ["optimizer-risk-balance-row"]
           :data-role "portfolio-optimizer-risk-breakdown-row"
           :data-instrument-id instrument-id
           :data-own-end own-end
           :data-cross-start cross-start
           :data-cross-end cross-end
           :data-net-end net-end
           :data-cross-effect effect
           :data-cross-tone tone
           :title (str label
                       " · own term ends "
                       (structure-model/format-pct own-end)
                       " · cross-covariance " effect-label
                       ", starts " (structure-model/format-pct cross-start)
                       " and ends " (structure-model/format-pct cross-end)
                       " · net " (structure-model/format-pct net-end))}
     [:span {:class ["optimizer-risk-balance-label" "truncate"]} label]
     (breakdown-lane scale row)
     [:span {:class ["optimizer-risk-decomp-div-cell" "font-mono" "tabular-nums"]
             :data-role "portfolio-optimizer-risk-breakdown-diversification"
             :data-sign (sign-token diversification)
             :data-tone tone}
      (str (format-signed-pct diversification) " " effect-label)]
     [:span {:class ["optimizer-risk-decomp-net-cell" "font-mono" "tabular-nums"]
             :data-role "portfolio-optimizer-risk-breakdown-net-cell"
             :data-tone "target"}
      (structure-model/format-pct share)]]))

(defn- legend-row
  [target-share]
  [:div {:class ["optimizer-risk-balance-row" "optimizer-risk-balance-legend-row"]}
   [:span]
   [:div {:class ["optimizer-risk-balance-legend"]}
    [:span {:class ["optimizer-risk-balance-legend-item"]}
     [:span {:class ["optimizer-risk-decomp-swatch"]
             :data-kind "standalone"}]
     "Own-variance term"]
    [:span {:class ["optimizer-risk-balance-legend-item"]}
     [:span {:class ["optimizer-risk-decomp-swatch"]
             :data-kind "diversification"
             :data-sign "negative"}]
     "Offsets risk"]
    [:span {:class ["optimizer-risk-balance-legend-item"]}
     [:span {:class ["optimizer-risk-decomp-swatch"]
             :data-kind "diversification"
             :data-sign "positive"}]
     "Amplifies risk"]
    [:span {:class ["optimizer-risk-balance-legend-item"]}
     [:span {:class ["optimizer-risk-decomp-net"]}]
     "Net contribution"]
    (when (number? target-share)
      [:span {:class ["optimizer-risk-balance-legend-item"]}
       [:span {:class ["optimizer-risk-balance-legend-dash"]}]
       (str "Target (" (structure-model/format-pct target-share) ")")])]
   [:span {:class ["optimizer-risk-balance-col-head"]} "Cross"]
   [:span {:class ["optimizer-risk-balance-col-head"]} "Net"]])

(defn- reading-note
  []
  [:p {:class ["optimizer-risk-balance-reading"]
       :data-role "portfolio-optimizer-risk-breakdown-reading"}
   [:span {:class ["optimizer-risk-balance-reading-label"]} "Reading this"]
   [:span {:class ["optimizer-risk-balance-reading-sep"]} "·"]
   (str "This is final-weight attribution, not removal impact. Own-variance "
        "runs from zero; cross-covariance starts at that endpoint and moves "
        "left when correlations offset risk or right when they amplify it. "
        "The endpoint is net risk contribution.")])

(defn breakdown-panel
  "The all-assets chart body. `rows` come pre-joined from the structure
  view-model (balance rows + standalone/diversification); the composing
  per-asset panel passes the remainder line (and owns the KPI strip, so
  `kpi-strip` is normally nil here)."
  [{:keys [rows target-share kpi-strip overflow-note]}]
  (when (seq rows)
    (let [{:keys [scale rows]} (structure-model/breakdown-bridge-model rows)]
      [:div
       kpi-strip
       [:div {:class ["optimizer-risk-balance-plot-frame"]
              :data-role "portfolio-optimizer-risk-breakdown-chart"}
        (legend-row target-share)
        [:div {:class ["optimizer-risk-balance-rows" "relative"]}
         (plot-backdrop scale target-share
                        {:row-class "optimizer-risk-balance-row"
                         :lead-cells 1
                         :tail-cells 2})
         (into [:div {:class ["relative" "space-y-1"]}]
               (map (partial breakdown-row scale))
               rows)]
        (axis-rows scale
                   {:title "Contribution Components (% of Total Volatility)"
                    :row-class "optimizer-risk-balance-row"
                    :lead-cells 1
                    :tail-cells 2})]
       (reading-note)
       overflow-note])))
