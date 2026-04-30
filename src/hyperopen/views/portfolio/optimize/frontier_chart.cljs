(ns hyperopen.views.portfolio.optimize.frontier-chart
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.frontier-chart-axes :as chart-axes]
            [hyperopen.views.portfolio.optimize.frontier-callout :as frontier-callout]
            [hyperopen.views.portfolio.optimize.frontier-overlay-markers :as frontier-overlays]
            [hyperopen.views.portfolio.optimize.frontier-target :as frontier-target]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(defn- objective-target
  [draft point]
  (if (= :target-volatility (get-in draft [:objective :kind]))
    {:kind :target-volatility
     :parameter-key :target-volatility
     :parameter-value (:volatility point)
     :label "Target Volatility"}
    {:kind :target-return
     :parameter-key :target-return
     :parameter-value (:expected-return point)
     :label "Target Return"}))

(defn- point-actions
  [target]
  [[:actions/set-portfolio-optimizer-objective-kind (:kind target)]
   [:actions/set-portfolio-optimizer-objective-parameter
    (:parameter-key target)
    (:parameter-value target)]])

(def ^:private chart-width 680)
(def ^:private chart-height 380)
(def ^:private chart-plot-left 64)
(def ^:private chart-plot-right 42)
(def ^:private chart-plot-top 34)
(def ^:private chart-plot-bottom 58)
(def ^:private chart-tick-count 6)
(def ^:private chart-grid-stroke "#1d2025")
(def ^:private chart-axis-stroke "#292d33")
(def ^:private frontier-color "#e2b84f")
(def ^:private chart-bounds {:width chart-width
                             :height chart-height})
(def ^:private plot-right (- chart-width chart-plot-right))
(def ^:private plot-bottom (- chart-height chart-plot-bottom))
(def ^:private plot-width (- plot-right chart-plot-left))
(def ^:private plot-height (- plot-bottom chart-plot-top))
(def ^:private plot-center-x (+ chart-plot-left (/ plot-width 2)))
(def ^:private plot-center-y (+ chart-plot-top (/ plot-height 2)))
(def ^:private plot-geometry {:left chart-plot-left
                              :right plot-right
                              :top chart-plot-top
                              :bottom plot-bottom})

(defn- numeric-values
  [points key]
  (keep (fn [point]
          (let [value (get point key)]
            (when (opt-format/finite-number? value) value)))
        points))

(defn- domain
  ([values fallback-min fallback-max]
   (domain values fallback-min fallback-max nil))
  ([values fallback-min fallback-max {:keys [floor-zero? include-zero?]}]
   (let [values* (seq values)
         min* (if values* (apply min values*) fallback-min)
         max* (if values* (apply max values*) fallback-max)]
     (if (= min* max*)
       [(if floor-zero? 0 (- min* 0.01)) (+ max* 0.01)]
       (let [span (- max* min*)
             lower (- min* (* span 0.08))
             upper (+ max* (* span 0.08))
             lower* (cond
                      floor-zero? 0
                      (and include-zero? (pos? lower)) 0
                      :else lower)
             upper* (if (and include-zero? (neg? upper)) 0 upper)]
         [lower* upper*])))))

(defn- scale-value
  [domain-min domain-max range-min range-max value]
  (if (and (opt-format/finite-number? value)
           (not= domain-min domain-max))
    (+ range-min
       (* (/ (- value domain-min)
             (- domain-max domain-min))
          (- range-max range-min)))
    (/ (+ range-min range-max) 2)))

(defn- point-position
  [x-domain y-domain point]
  {:x (scale-value (first x-domain)
                   (second x-domain)
                   chart-plot-left
                   plot-right
                   (:volatility point))
   :y (scale-value (first y-domain)
                   (second y-domain)
                   plot-bottom
                   chart-plot-top
                   (:expected-return point))})

(defn- path-data
  [positions]
  (when (seq positions)
    (str/join " "
              (map-indexed
               (fn [idx {:keys [x y]}]
                 (str (if (zero? idx) "M" "L")
                      " "
                      (opt-format/format-decimal x)
                      " "
                      (opt-format/format-decimal y)))
               positions))))

(defn- grid-line
  [orientation idx position]
  (case orientation
    :vertical
    [:line {:key (str "v-" idx)
            :x1 position
            :x2 position
            :y1 chart-plot-top
            :y2 plot-bottom
            :stroke chart-grid-stroke}]
    [:line {:key (str "h-" idx)
            :x1 chart-plot-left
            :x2 plot-right
            :y1 position
            :y2 position
            :stroke chart-grid-stroke}]))

(defn- frontier-callout-id
  [idx]
  (str "frontier-" idx))

(defn- frontier-callout-visibility-rule
  [idx]
  (let [callout-id (frontier-callout-id idx)
        trigger-selector (str "[data-frontier-callout-trigger=\"" callout-id "\"]")
        callout-selector (str "[data-frontier-callout-id=\"" callout-id "\"]")
        svg-selector "[data-role=\"portfolio-optimizer-frontier-svg\"]"]
    (str svg-selector ":has(" trigger-selector ":hover) " callout-selector ",\n"
         svg-selector ":has(" trigger-selector ":focus) " callout-selector ",\n"
         svg-selector ":has(" trigger-selector ":focus-within) " callout-selector
         " { display: inline; opacity: 1; }")))

(defn- frontier-callout-style
  [points]
  (when (seq points)
    [:style {:type "text/css"}
     (str/join "\n" (map-indexed (fn [idx _]
                                    (frontier-callout-visibility-rule idx))
                                  points))]))

(defn- frontier-point
  [draft idx point x-domain y-domain]
  (let [target (objective-target draft point)
        position (point-position x-domain y-domain point)
        {:keys [x y]} position
        label (str "Frontier Point " (inc idx))
        rows (frontier-callout/point-rows point)
        callout-id (frontier-callout-id idx)]
    [:g {:role "button"
         :tabIndex 0
         :tabindex 0
         :focusable "true"
         :class ["portfolio-frontier-marker" "cursor-pointer" "outline-none"]
         :style {:color frontier-color}
         :data-role (str "portfolio-optimizer-frontier-point-" idx)
         :data-frontier-drag-target "true"
         :data-return (opt-format/format-pct (:expected-return point))
         :data-volatility (opt-format/format-pct (:volatility point))
         :data-sharpe (opt-format/format-decimal (:sharpe point))
         :data-frontier-callout-trigger callout-id
         :aria-label (frontier-callout/aria-label label rows)
         :draggable true
         :on {:click (point-actions target)
              :drag-start (point-actions target)
              :drag-enter (point-actions target)}}
     [:circle {:cx x
               :cy y
               :r 4
               :fill frontier-color}]
     [:circle {:cx x
               :cy y
               :r 11
               :fill "transparent"
               :stroke "rgba(212, 181, 88, 0.16)"}]
     (frontier-callout/focus-ring x y 15)
     (frontier-callout/hitbox
      (str "portfolio-optimizer-frontier-point-" idx "-hitbox")
      x
      y
      14)]))

(defn- frontier-point-callout
  [result idx point x-domain y-domain]
  (let [position (point-position x-domain y-domain point)
        label (str "Frontier Point " (inc idx))
        rows (frontier-callout/point-rows point)
        allocations (frontier-callout/allocation-summary
                     (:instrument-ids result)
                     (:weights point)
                     (:labels-by-instrument result))]
    (frontier-callout/callout
     {:bounds chart-bounds
      :data-role (str "portfolio-optimizer-frontier-callout-frontier-" idx)
      :data-frontier-callout-id (frontier-callout-id idx)
      :label label
      :point position
      :rows rows
      :allocations allocations})))

(defn- overlay-mode-button
  [current-mode mode]
  (let [selected? (= current-mode mode)]
    [:button {:type "button"
              :class (cond-> ["bg-transparent"
                              "text-center"
                              "whitespace-nowrap"
                              "px-3"
                              "py-1.5"
                              "text-[0.62rem]"
                              "font-semibold"
                              "uppercase"
                              "tracking-[0.06em]"
                              "text-trading-muted"
                              "transition-colors"
                              "hover:text-trading-text"]
                       selected? (conj "bg-base-200/60" "text-trading-text"))
              :data-role (str "portfolio-optimizer-frontier-overlay-mode-" (name mode))
              :data-selected (str selected?)
              :aria-pressed (str selected?)
              :on {:click [[:actions/set-portfolio-optimizer-frontier-overlay-mode mode]]}}
     (case mode
       :standalone "Standalone"
       :contribution "Contribution"
       :none "None")]))

(defn- frontier-points
  [result constrain-frontier?]
  (or (get-in result [:frontiers (if (true? constrain-frontier?)
                                   :constrained
                                   :unconstrained)])
      (:frontier result)))

(defn- constrain-frontier-control
  [constrain-frontier?]
  [:label {:class ["flex" "items-center" "gap-2" "border" "border-base-300"
                   "bg-base-100/90" "px-2.5" "py-1.5" "text-[0.68rem]"
                   "font-medium" "text-trading-muted" "transition-colors"
                   "hover:text-trading-text"]
           :data-role "portfolio-optimizer-constrain-frontier-control"}
   [:input {:type "checkbox"
            :class ["h-3.5" "w-3.5" "accent-warning" "outline-none"]
            :data-role "portfolio-optimizer-constrain-frontier-checkbox"
            :checked (true? constrain-frontier?)
            :on {:change [[:actions/set-portfolio-optimizer-constrain-frontier
                           :event.target/checked]]}}]
   [:span "Constrain Frontier"]])

(defn frontier-chart
  ([draft result]
   (frontier-chart draft result :standalone))
  ([draft result overlay-mode]
   (frontier-chart draft result overlay-mode false))
  ([draft result overlay-mode constrain-frontier?]
   (let [overlay-mode* (frontier-overlays/normalize-mode overlay-mode)
         overlay-points (frontier-overlays/visible-points result overlay-mode*)
         domain-overlay-points (frontier-overlays/all-points result)
         {:keys [subtitle
                 x-axis-prefix
                 y-axis-prefix
                 reading-text
                 legend-label]} (frontier-overlays/copy overlay-mode*)
         points (->> (frontier-points result constrain-frontier?)
                     (filter #(and (opt-format/finite-number? (:volatility %))
                                   (opt-format/finite-number? (:expected-return %))))
                     (sort-by :volatility)
                     vec)
        x-domain (domain (concat (numeric-values points :volatility)
                                 (numeric-values domain-overlay-points :volatility)
                                 (when (opt-format/finite-number? (:volatility result))
                                   [(:volatility result)]))
                         0
                         1
                         {:floor-zero? true})
        y-domain (domain (concat (numeric-values points :expected-return)
                                 (numeric-values domain-overlay-points :expected-return)
                                 (when (opt-format/finite-number? (:expected-return result))
                                   [(:expected-return result)]))
                         0
                         1
                         {:include-zero? true})
        x-ticks (chart-axes/axis-ticks x-domain chart-tick-count)
        y-ticks (chart-axes/axis-ticks y-domain chart-tick-count)
        x-domain* (chart-axes/tick-domain x-ticks x-domain)
        y-domain* (chart-axes/tick-domain y-ticks y-domain)
        positions (map #(point-position x-domain* y-domain* %) points)
        target (objective-target draft (first points))]
     (when (seq points)
       [:section {:class ["min-w-0" "overflow-hidden" "bg-transparent" "leading-4"]
                  :data-role "portfolio-optimizer-frontier-panel"}
        [:div {:class ["grid" "items-start" "gap-3" "lg:grid-cols-[minmax(0,1fr)_auto]"]
               :data-role "portfolio-optimizer-frontier-toolbar"}
         [:div {:class ["min-w-0"]}
          [:p {:class ["font-mono"
                       "text-[0.62rem]"
                       "uppercase"
                       "tracking-[0.08em]"
                       "text-trading-muted/70"]}
           "Efficient Frontier"]
          [:p {:class ["mt-1" "text-xs" "text-trading-muted"]}
           subtitle]]
         [:div {:class ["flex" "items-start" "justify-start" "gap-3" "lg:justify-end"]
                :data-role "portfolio-optimizer-frontier-controls"}
          (constrain-frontier-control constrain-frontier?)
          [:div {:class ["min-w-[19.25rem]" "border" "border-base-300" "bg-base-100/90" "p-0.5"]
                 :data-role "portfolio-optimizer-frontier-overlay-mode-group"}
           (into [:div {:class ["grid"
                                "grid-cols-[minmax(0,1fr)_minmax(0,1.28fr)_minmax(0,0.78fr)]"
                                "items-stretch"
                                "gap-1"]}]
                 (map #(overlay-mode-button overlay-mode* %) frontier-overlays/modes))]
          [:p {:class ["font-mono" "text-[0.62rem]" "text-trading-muted/70"]}
           (str (count points) " points")]]]
        [:div {:class ["relative" "mt-4" "overflow-hidden" "border" "border-base-300" "bg-base-100" "p-4"]
               :data-role "portfolio-optimizer-frontier-chart-box"}
         [:div {:class ["absolute" "right-6" "top-6" "z-10" "border" "border-base-300" "bg-base-200/80" "px-3" "py-2" "text-[0.65rem]"]
                :data-role "portfolio-optimizer-frontier-legend"}
          [:div {:class ["flex" "items-center" "gap-2" "text-trading-text"]}
           (frontier-target/legend-dot)
           "Target"]
          [:div {:class ["mt-1" "flex" "items-center" "gap-2" "text-trading-muted"]}
           [:span {:class ["h-px" "w-5"]
                   :style {:background-color frontier-color}}]
           "Efficient frontier"]
          (when legend-label
            [:div {:class ["mt-1" "flex" "items-center" "gap-2" "text-trading-muted"]}
             [:span {:class ["flex" "items-center" "gap-0.5"]}
              [:span {:class ["h-1.5" "w-1.5" "rounded-full" "border"]
                      :style {:border-color "#8f96a3"}}]
              [:span {:class ["h-1.5" "w-1.5" "rounded-full" "border"]
                      :style {:border-color "#6f4aa5"}}]
              [:span {:class ["h-1.5" "w-1.5" "rounded-full" "border"]
                      :style {:border-color "#59a5c8"}}]]
             legend-label])]
         [:svg {:viewBox (str "0 0 " chart-width " " chart-height)
                :class ["h-[23.75rem]" "w-full" "overflow-visible" "text-trading-text"]
                :data-role "portfolio-optimizer-frontier-svg"
                :aria-label "Efficient frontier chart. X axis is annualized volatility. Y axis is annualized expected return."}
          (frontier-callout-style points)
          (frontier-target/gradient-defs)
          [:g {:data-role "portfolio-optimizer-frontier-grid"}
           (map-indexed (fn [idx value]
                          (grid-line :vertical idx (chart-axes/x-tick-position plot-geometry x-domain* value)))
                        x-ticks)
           (map-indexed (fn [idx value]
                          (grid-line :horizontal idx (chart-axes/y-tick-position plot-geometry y-domain* value)))
                        y-ticks)]
          [:line {:x1 chart-plot-left
                  :x2 plot-right
                  :y1 plot-bottom
                  :y2 plot-bottom
                  :stroke chart-axis-stroke}]
          [:line {:x1 chart-plot-left
                  :x2 chart-plot-left
                  :y1 chart-plot-top
                  :y2 plot-bottom
                  :stroke chart-axis-stroke}]
          [:g {:data-role "portfolio-optimizer-frontier-x-axis-ticks"}
           (map-indexed (fn [idx value]
                          (chart-axes/tick-label
                           plot-geometry
                           :x
                           idx
                           (chart-axes/x-tick-position plot-geometry x-domain* value)
                           value))
                        x-ticks)]
          [:g {:data-role "portfolio-optimizer-frontier-y-axis-ticks"}
           (map-indexed (fn [idx value]
                          (chart-axes/tick-label
                           plot-geometry
                           :y
                           idx
                           (chart-axes/y-tick-position plot-geometry y-domain* value)
                           value))
                        y-ticks)]
          [:text {:x plot-center-x
                  :y (- chart-height 10)
                  :fill "currentColor"
                  :fontSize 11
                  :opacity 0.78
                  :text-anchor "middle"
                  :dominant-baseline "middle"
                  :data-role "portfolio-optimizer-frontier-x-axis-label"}
           "Volatility (Annualized)"]
          [:text {:x 14
                  :y plot-center-y
                  :fill "currentColor"
                  :fontSize 11
                  :opacity 0.78
                  :text-anchor "middle"
                  :dominant-baseline "middle"
                  :transform (str "rotate(-90 14 " plot-center-y ")")
                  :data-role "portfolio-optimizer-frontier-y-axis-label"}
           "Expected Return (Annualized)"]
          [:path {:d (path-data positions)
                  :fill "none"
                  :stroke frontier-color
                  :strokeWidth 2.5
                  :strokeLinecap "round"
                  :strokeLinejoin "round"
                  :data-role "portfolio-optimizer-frontier-path"}]
          (map-indexed (fn [idx point]
                         (frontier-point draft idx point x-domain* y-domain*))
                       points)
          (frontier-target/marker
           {:bounds chart-bounds
            :point-position point-position
            :x-domain x-domain*
            :y-domain y-domain*
            :result result})
          (map #(frontier-overlays/marker
                 {:bounds chart-bounds
                  :overlay-mode overlay-mode*
                  :point-position point-position
                  :x-domain x-domain*
                  :y-domain y-domain*
                  :point %})
               overlay-points)
          [:text {:x plot-right
                  :y (- chart-plot-top 12)
                  :fill "currentColor"
                  :fontSize 10
                  :opacity 0.58
                  :text-anchor "end"}
           (str x-axis-prefix " / " y-axis-prefix)]
          [:g {:data-role "portfolio-optimizer-frontier-callout-layer"}
           (map-indexed (fn [idx point]
                          (frontier-point-callout result idx point x-domain* y-domain*))
                        points)]]]
        [:div {:class ["mt-4" "flex" "gap-3" "text-[0.7rem]" "text-trading-muted"]}
         [:span {:class ["font-mono" "text-[0.62rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
          "Reading this"]
         [:span "·"]
         [:span
          (str reading-text
               " Click or drag a point to set "
               (:label target)
               " and rerun.")]]]))))
