(ns hyperopen.views.portfolio.optimize.risk-return-context
  "The RISK / RETURN tab of the risk-contribution balance card (designer spec
  2026-07-11): a real vol/return chart for objectives that are NOT selected
  from an efficient frontier (the covariance-only pair — Equal Risk and
  Risk-weighted sizing, which passes its own :objective-label). Built on the same chart chrome as
  the frontier chart — shared geometry, nice ticks, gridlines, and the
  standalone asset icon markers with hover callouts — but deliberately NEVER
  drawing a frontier curve: none was calculated and none is implied, and the
  header note + Context-only box say so in words. The designer's marker
  language replaces the frontier's halo/orb markers: the current portfolio is
  a small near-white core dot inside a thin ring, the recommended portfolio
  the same form in green (painted last), joined by a thin connector; a dashed
  crosshair drops through the current book's volatility and a dashed line
  marks 0% expected return. Above the chart: three context boxes (current /
  recommended / context-only). Below: an asset legend (capped, honest
  overflow) plus the two portfolio-marker swatches, and the Sharpe footnote.
  Everything renders straight off the solved result — no derived app state."
  (:require [hyperopen.portfolio.optimizer.application.view-model.frontier
             :as overlay-model]
            [hyperopen.views.asset-icon :as asset-icon]
            [hyperopen.views.portfolio.optimize.format :as opt-format]
            [hyperopen.views.portfolio.optimize.frontier-callout :as frontier-callout]
            [hyperopen.views.portfolio.optimize.frontier-chart-axes :as chart-axes]
            [hyperopen.views.portfolio.optimize.frontier-chart-model :as model]
            [hyperopen.views.portfolio.optimize.frontier-overlay-markers
             :as frontier-overlays]))

(def ^:private current-color "var(--optimizer-text)")
(def ^:private current-muted "var(--optimizer-text-2)")
(def ^:private recommended-color "var(--optimizer-long)")
(def ^:private guide-color "var(--optimizer-text-3)")
(def ^:private marker-fill "var(--optimizer-bg)")

(defn- context-note
  [objective-label]
  (str "Expected returns are shown for context and did not determine the "
       objective-label " allocation."))

(def ^:private analytics-only-note
  "Expected returns come from the return model (analytics only). Not used to size positions.")

(defn- finite?*
  [value]
  (and (number? value) (js/isFinite value)))

(defn- scatter-points
  [result objective-label]
  (let [standalone (->> (get-in result [:frontier-overlays :standalone])
                        (filter #(and (finite?* (:volatility %))
                                      (finite?* (:expected-return %))))
                        vec)
        target (when (and (finite?* (:volatility result))
                          (finite?* (:expected-return result)))
                 {:label (str "Recommended (" objective-label ")")
                  :volatility (:volatility result)
                  :expected-return (:expected-return result)
                  :sharpe (get-in result [:performance :in-sample-sharpe])})
        current (when (and (finite?* (:current-volatility result))
                           (finite?* (:current-expected-return result)))
                  {:label "Current"
                   :volatility (:current-volatility result)
                   :expected-return (:current-expected-return result)
                   :sharpe (get-in result [:current-performance :in-sample-sharpe])})]
    {:assets standalone
     :target target
     :current current}))

(defn- scatter-scale
  "Domains and ticks fitted exactly like the frontier chart: volatility floors
  at zero, the return axis always includes zero (the dashed zero line needs
  it), both snapped to nice tick values so the axes read the same across
  objectives."
  [{:keys [assets target current]}]
  (let [points (concat assets (keep identity [target current]))
        x-domain (model/domain (model/numeric-values points :volatility)
                               0 1 {:floor-zero? true})
        y-domain (model/domain (model/numeric-values points :expected-return)
                               0 1 {:include-zero? true})
        x-ticks (chart-axes/axis-ticks x-domain model/chart-tick-count)
        y-ticks (chart-axes/axis-ticks y-domain model/chart-tick-count)]
    {:x-domain (chart-axes/tick-domain x-ticks x-domain)
     :y-domain (chart-axes/tick-domain y-ticks y-domain)
     :x-ticks x-ticks
     :y-ticks y-ticks}))

(defn- grid-lines
  [{:keys [x-domain y-domain x-ticks y-ticks]}]
  (into [:g {:data-role "portfolio-optimizer-risk-return-grid"}]
        (concat
         (map (fn [value]
                [:line {:x1 (chart-axes/x-tick-position model/plot-geometry x-domain value)
                        :x2 (chart-axes/x-tick-position model/plot-geometry x-domain value)
                        :y1 model/chart-plot-top
                        :y2 model/plot-bottom
                        :stroke model/chart-grid-stroke}])
              x-ticks)
         (map (fn [value]
                [:line {:x1 model/chart-plot-left
                        :x2 model/plot-right
                        :y1 (chart-axes/y-tick-position model/plot-geometry y-domain value)
                        :y2 (chart-axes/y-tick-position model/plot-geometry y-domain value)
                        :stroke model/chart-grid-stroke}])
              y-ticks))))

(defn- axis-frame
  [{:keys [x-domain y-domain x-ticks y-ticks]}]
  [:g {:data-role "portfolio-optimizer-risk-return-axes"}
   [:line {:x1 model/chart-plot-left
           :x2 model/plot-right
           :y1 model/plot-bottom
           :y2 model/plot-bottom
           :stroke model/chart-axis-stroke}]
   [:line {:x1 model/chart-plot-left
           :x2 model/chart-plot-left
           :y1 model/chart-plot-top
           :y2 model/plot-bottom
           :stroke model/chart-axis-stroke}]
   (map-indexed (fn [idx value]
                  (chart-axes/tick-label
                   model/plot-geometry
                   :x
                   idx
                   (chart-axes/x-tick-position model/plot-geometry x-domain value)
                   value))
                x-ticks)
   (map-indexed (fn [idx value]
                  (chart-axes/tick-label
                   model/plot-geometry
                   :y
                   idx
                   (chart-axes/y-tick-position model/plot-geometry y-domain value)
                   value))
                y-ticks)
   [:text {:x model/plot-center-x
           :y (- model/chart-height 10)
           :fill "currentColor"
           :font-size 11
           :opacity 0.78
           :text-anchor "middle"
           :dominant-baseline "middle"
           :data-role "portfolio-optimizer-risk-return-x-axis-label"}
    "Volatility (Annualized)"]
   [:text {:x 14
           :y model/plot-center-y
           :fill "currentColor"
           :font-size 11
           :opacity 0.78
           :text-anchor "middle"
           :dominant-baseline "middle"
           :transform (str "rotate(-90 14 " model/plot-center-y ")")
           :data-role "portfolio-optimizer-risk-return-y-axis-label"}
    "Expected Return (Annualized)"]])

(defn- zero-return-line
  "Dashed horizontal guide at 0% expected return — the designer's horizontal
  crosshair arm. The y-domain always includes zero (see scatter-scale)."
  [{:keys [y-domain]}]
  (let [[lo hi] y-domain]
    (when (<= lo 0 hi)
      [:line {:x1 model/chart-plot-left
              :x2 model/plot-right
              :y1 (chart-axes/y-tick-position model/plot-geometry y-domain 0)
              :y2 (chart-axes/y-tick-position model/plot-geometry y-domain 0)
              :stroke guide-color
              :stroke-width 1
              :stroke-dasharray "4 4"
              :opacity 0.65
              :data-role "portfolio-optimizer-risk-return-zero-line"}])))

(defn- current-vol-crosshair
  "Dashed vertical guide through the CURRENT portfolio's volatility — 'where
  my book sits today' against every other point."
  [{:keys [x-domain]} current]
  (when current
    (let [x (chart-axes/x-tick-position model/plot-geometry x-domain
                                        (:volatility current))]
      [:line {:x1 x
              :x2 x
              :y1 model/chart-plot-top
              :y2 model/plot-bottom
              :stroke guide-color
              :stroke-width 1
              :stroke-dasharray "4 4"
              :opacity 0.65
              :data-role "portfolio-optimizer-risk-return-crosshair"}])))

(defn- position
  [{:keys [x-domain y-domain]} point]
  (model/point-position x-domain y-domain point))

(defn- connector
  "Thin line joining the current book to the recommended portfolio — the
  designer draws the move as one short hop."
  [scale current target]
  (when (and current target)
    (let [{x1 :x y1 :y} (position scale current)
          {x2 :x y2 :y} (position scale target)]
      (when (< 1 (+ (js/Math.abs (- x2 x1)) (js/Math.abs (- y2 y1))))
        [:line {:x1 x1
                :y1 y1
                :x2 x2
                :y2 y2
                :stroke guide-color
                :stroke-width 1
                :opacity 0.6
                :data-role "portfolio-optimizer-risk-return-connector"}]))))

(defn- asset-label
  "Static symbol text beside each icon marker (the mock labels every point);
  the symbol-marker class paints a surface-colored halo behind the text so it
  stays readable over gridlines."
  [scale point]
  (let [{:keys [x y]} (position scale point)]
    [:text {:x (+ x 13)
            :y (+ y 3.5)
            :fill current-muted
            :font-size 9
            :font-weight 600
            :class "portfolio-frontier-symbol-marker"
            :data-role (str "portfolio-optimizer-risk-return-asset-label-"
                            (:instrument-id point))}
     (overlay-model/overlay-label point)]))

(defn- asset-markers
  [scale points]
  (mapcat (fn [point]
            [(frontier-overlays/marker
              {:bounds model/chart-bounds
               :overlay-mode :standalone
               :point-position model/point-position
               :x-domain (:x-domain scale)
               :y-domain (:y-domain scale)
               :point point})
             (asset-label scale point)])
          points))

(defn- ring-marker
  "The designer's portfolio marker: a small core dot inside a thin ring on a
  surface-filled disc (so grid/crosshair lines never show through the middle)."
  [x y color]
  [:g
   [:circle {:cx x
             :cy y
             :r 8
             :fill marker-fill
             :fill-opacity 0.85
             :stroke color
             :stroke-width 1.3}]
   [:circle {:cx x
             :cy y
             :r 2.6
             :fill color}]])

(defn- portfolio-marker
  "Current/recommended marker with its text label, nested hover callout, and
  hitbox. `label-side` :below (current) or :leader (recommended — short leader
  line to a label that flips left when the point sits near the right edge)."
  [scale point {:keys [data-role color label-side label-color]}]
  (let [{:keys [x y] :as pos} (position scale point)
        label (:label point)
        rows (frontier-callout/point-rows point)
        below? (= :below label-side)
        label-above? (and below? (> y (- model/plot-bottom 26)))
        flip? (> x (- model/plot-right 150))]
    [:g {:class ["portfolio-frontier-marker" "outline-none"]
         :style {:color color}
         :data-role data-role
         :role "img"
         :tabIndex 0
         :tabindex 0
         :focusable "true"
         :aria-label (frontier-callout/aria-label label rows)}
     (when-not below?
       [:line {:x1 (+ x (if flip? -7 7))
               :y1 (- y 5)
               :x2 (+ x (if flip? -13 13))
               :y2 (- y 13)
               :stroke color
               :stroke-width 1
               :opacity 0.55
               :data-role (str data-role "-leader")}])
     (ring-marker x y color)
     [:text {:x (if below? x (+ x (if flip? -16 16)))
             :y (cond
                  label-above? (- y 15)
                  below? (+ y 21)
                  :else (- y 16))
             :fill (or label-color color)
             :font-size 10
             :font-weight 650
             :text-anchor (cond
                            below? "middle"
                            flip? "end"
                            :else "start")
             :class "portfolio-frontier-symbol-marker"
             :data-role (str data-role "-label")}
      label]
     (frontier-callout/focus-ring x y 13)
     (frontier-callout/hitbox (str data-role "-hitbox") x y 14)
     (frontier-callout/callout
      {:bounds model/chart-bounds
       :data-role (str data-role "-callout")
       :label label
       :point pos
       :rows rows})]))

(defn- chart-svg
  [{:keys [assets target current]} scale]
  (into [:svg {:viewBox (str "0 0 " model/chart-width " " model/chart-height)
               :class ["h-[23.75rem]" "w-full" "overflow-visible" "text-trading-text"]
               :data-role "portfolio-optimizer-risk-return-svg"
               :aria-label "Risk and return context chart. X axis is annualized volatility. Y axis is annualized expected return. No efficient frontier is drawn."}
         (grid-lines scale)
         (axis-frame scale)
         (zero-return-line scale)
         (current-vol-crosshair scale current)
         (connector scale current target)]
        (concat
         (asset-markers scale assets)
         (when current
           [(portfolio-marker scale current
                              {:data-role "portfolio-optimizer-risk-return-current"
                               :color current-color
                               :label-color current-muted
                               :label-side :below})])
         (when target
           [(portfolio-marker scale target
                              {:data-role "portfolio-optimizer-risk-return-target"
                               :color recommended-color
                               :label-side :leader})]))))

(defn- point-stats
  [point]
  [:div {:class ["optimizer-risk-return-box-stats"]}
   [:div
    [:p {:class ["optimizer-risk-return-box-label"]} "Volatility (ann.)"]
    [:p {:class ["optimizer-risk-return-box-value"
                 "optimizer-risk-return-box-value--vol"
                 "font-mono" "tabular-nums"]}
     (opt-format/format-pct (:volatility point))]]
   [:div
    [:p {:class ["optimizer-risk-return-box-label"]} "Expected return (ann.)"]
    [:p {:class ["optimizer-risk-return-box-value"
                 "optimizer-risk-return-box-value--ret"
                 "font-mono" "tabular-nums"]}
     (opt-format/format-pct (:expected-return point))]]])

(defn- context-boxes
  [current target objective-label]
  [:div {:class ["optimizer-risk-return-boxes"]}
   (when current
     [:div {:class ["optimizer-risk-return-box"]
            :data-role "portfolio-optimizer-risk-return-current-box"}
      [:p {:class ["optimizer-risk-return-box-title"]} "Current portfolio"]
      (point-stats current)])
   (when target
     [:div {:class ["optimizer-risk-return-box" "optimizer-risk-return-box--recommended"]
            :data-role "portfolio-optimizer-risk-return-recommended-box"}
      [:p {:class ["optimizer-risk-return-box-title"]}
       (str "Recommended (" objective-label ")")]
      (point-stats target)])
   [:div {:class ["optimizer-risk-return-box"]
          :data-role "portfolio-optimizer-risk-return-context-box"}
    [:p {:class ["optimizer-risk-return-box-title"]} "Context only"]
    [:p {:class ["optimizer-risk-return-box-body"]} analytics-only-note]]])

(def ^:private legend-asset-cap 8)

(defn- legend-asset-entry
  [point]
  (let [icon-url (asset-icon/market-icon-url (overlay-model/point-market point))]
    [:span {:class ["optimizer-risk-return-legend-item"]}
     (if (seq icon-url)
       [:img {:class ["optimizer-risk-return-legend-icon"]
              :src icon-url
              :alt ""
              :aria-hidden "true"}]
       [:span {:class ["optimizer-risk-return-legend-dot"]}])
     (overlay-model/overlay-label point)]))

(defn- legend-row
  [assets current? objective-label]
  (let [shown (take legend-asset-cap assets)
        hidden (- (count assets) (count shown))]
    (into [:div {:class ["optimizer-risk-return-legend"]
                 :data-role "portfolio-optimizer-risk-return-legend"}]
          (concat
           (map legend-asset-entry shown)
           (when (pos? hidden)
             [[:span {:class ["optimizer-risk-return-legend-more"]}
               (str "+ " hidden " more assets")]])
           (when current?
             [[:span {:class ["optimizer-risk-return-legend-item"]}
               [:span {:class ["optimizer-risk-return-legend-swatch"
                               "optimizer-risk-return-legend-swatch--current"]}]
               "Current Portfolio"]])
           [[:span {:class ["optimizer-risk-return-legend-item"]}
             [:span {:class ["optimizer-risk-return-legend-swatch"
                             "optimizer-risk-return-legend-swatch--recommended"]}]
             (str "Recommended (" objective-label ")")]]))))

(defn- sharpe-line
  [result]
  (let [current (get-in result [:current-performance :in-sample-sharpe])
        target (get-in result [:performance :in-sample-sharpe])]
    (when (or (finite?* current) (finite?* target))
      [:p {:class ["mt-2" "font-mono" "text-[0.6875rem]" "text-trading-muted"]
           :data-role "portfolio-optimizer-risk-return-sharpe"}
       (str "Sharpe · current " (opt-format/format-decimal current)
            " → recommended " (opt-format/format-decimal target))])))

(defn risk-return-panel
  "The RISK / RETURN tab panel body; nil when neither portfolio point exists
  (e.g. return metrics omitted because the return model was invalid) — the
  caller then omits the tab entirely. `objective-label` names the
  covariance-only objective in the recommended-marker copy and the
  context-only framing; the one-argument form keeps the Equal Risk default."
  ([result]
   (risk-return-panel result nil))
  ([result {:keys [objective-label] :or {objective-label "Equal Risk"}}]
   (let [{:keys [target current] :as points} (scatter-points result
                                                             objective-label)
         note (context-note objective-label)]
     (when (or target current)
       (let [scale (scatter-scale points)]
         [:div {:data-role "portfolio-optimizer-risk-return-context"}
          [:div {:class ["optimizer-risk-return-header"]}
           [:div {:class ["optimizer-risk-return-title-wrap"]}
            [:p {:class ["optimizer-risk-return-title"]} "Risk / return context"]
            [:span {:class ["optimizer-risk-return-info"]
                    :title (str note " " analytics-only-note
                                " No efficient frontier was calculated.")}
             "ⓘ"]]
           [:p {:class ["optimizer-risk-return-note"]
                :data-role "portfolio-optimizer-risk-return-note"}
            note]]
          (context-boxes current target objective-label)
          [:div {:class ["mt-3"]}
           (chart-svg points scale)]
          (legend-row (:assets points) (some? current) objective-label)
          (sharpe-line result)])))))
