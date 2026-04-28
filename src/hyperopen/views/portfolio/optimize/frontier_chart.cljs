(ns hyperopen.views.portfolio.optimize.frontier-chart
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.frontier-callout :as frontier-callout]
            [hyperopen.views.portfolio.optimize.frontier-overlay-markers :as frontier-overlays]
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
(def ^:private chart-pad 42)
(def ^:private chart-grid-stroke "#1d2025")
(def ^:private chart-axis-stroke "#292d33")
(def ^:private current-halo-stroke "rgba(107, 141, 181, 0.45)")
(def ^:private target-halo-stroke "rgba(212, 181, 88, 0.45)")
(def ^:private chart-bounds {:width chart-width
                             :height chart-height})

(defn- numeric-values
  [points key]
  (keep (fn [point]
          (let [value (get point key)]
            (when (opt-format/finite-number? value) value)))
        points))

(defn- domain
  [values fallback-min fallback-max]
  (let [values* (seq values)
        min* (if values* (apply min values*) fallback-min)
        max* (if values* (apply max values*) fallback-max)]
    (if (= min* max*)
      [(- min* 0.01) (+ max* 0.01)]
      (let [span (- max* min*)]
        [(- min* (* span 0.08))
         (+ max* (* span 0.08))]))))

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
                   chart-pad
                   (- chart-width chart-pad)
                   (:volatility point))
   :y (scale-value (first y-domain)
                   (second y-domain)
                   (- chart-height chart-pad)
                   chart-pad
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
  [orientation idx ratio]
  (let [x (+ chart-pad (* ratio (- chart-width (* 2 chart-pad))))
        y (+ chart-pad (* ratio (- chart-height (* 2 chart-pad))))]
    (case orientation
      :vertical
      [:line {:key (str "v-" idx)
              :x1 x
              :x2 x
              :y1 chart-pad
              :y2 (- chart-height chart-pad)
              :stroke chart-grid-stroke}]
      [:line {:key (str "h-" idx)
              :x1 chart-pad
              :x2 (- chart-width chart-pad)
              :y1 y
              :y2 y
              :stroke chart-grid-stroke}])))

(defn- frontier-point
  [draft idx point x-domain y-domain]
  (let [target (objective-target draft point)
        position (point-position x-domain y-domain point)
        {:keys [x y]} position
        label (str "Frontier Point " (inc idx))
        rows (frontier-callout/point-rows point)]
    [:g {:role "button"
         :tabIndex 0
         :tabindex 0
         :focusable "true"
         :class ["portfolio-frontier-marker" "cursor-pointer" "text-primary" "outline-none"]
         :data-role (str "portfolio-optimizer-frontier-point-" idx)
         :data-frontier-drag-target "true"
         :data-return (opt-format/format-pct (:expected-return point))
         :data-volatility (opt-format/format-pct (:volatility point))
         :data-sharpe (opt-format/format-decimal (:sharpe point))
         :aria-label (frontier-callout/aria-label label rows)
         :draggable true
         :on {:click (point-actions target)
              :drag-start (point-actions target)
              :drag-enter (point-actions target)}}
     [:circle {:cx x
               :cy y
               :r 4
               :fill "currentColor"}]
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
      14)
     (frontier-callout/callout
      {:bounds chart-bounds
       :data-role (str "portfolio-optimizer-frontier-callout-frontier-" idx)
       :label label
       :point position
       :rows rows})]))

(defn- overlay-mode-button
  [current-mode mode]
  (let [selected? (= current-mode mode)]
    [:button {:type "button"
              :class (cond-> ["border-r"
                              "border-base-300"
                              "bg-transparent"
                              "px-2.5"
                              "py-1.5"
                              "text-[0.62rem]"
                              "font-semibold"
                              "uppercase"
                              "tracking-[0.08em]"
                              "text-trading-muted"
                              "transition-colors"
                              "last:border-r-0"
                              "hover:text-trading-text"]
                       selected? (conj "bg-base-200/60" "text-trading-text"))
              :data-role (str "portfolio-optimizer-frontier-overlay-mode-" (name mode))
              :aria-pressed (str selected?)
              :on {:click [[:actions/set-portfolio-optimizer-frontier-overlay-mode mode]]}}
     (case mode
       :standalone "Standalone"
       :contribution "Contribution"
       :none "None")]))

(defn- target-marker
  [result x-domain y-domain]
  (let [point {:expected-return (:expected-return result)
               :volatility (:volatility result)
               :sharpe (get-in result [:performance :in-sample-sharpe])}
        position (point-position x-domain y-domain point)
        {:keys [x y]} position
        label "Target Portfolio"
        rows (frontier-callout/point-rows
              point
              {:exposure (frontier-callout/exposure-summary result :target)})]
    [:g {:class ["portfolio-frontier-marker" "text-primary" "outline-none"]
         :data-role "portfolio-optimizer-frontier-target-marker"
         :role "img"
         :tabIndex 0
         :tabindex 0
         :focusable "true"
         :aria-label (frontier-callout/aria-label label rows)}
     [:circle {:cx x
               :cy y
               :r 6
               :fill "currentColor"
               :stroke "currentColor"
               :strokeWidth 2}]
     [:circle {:cx x
               :cy y
               :r 12
               :fill "transparent"
               :stroke target-halo-stroke}]
     (frontier-callout/focus-ring x y 16)
     [:text {:x (+ x 10)
             :y (- y 10)
             :fill "currentColor"
             :fontSize 10
             :fontWeight 700}
      "Target"]
     (frontier-callout/hitbox
      "portfolio-optimizer-frontier-target-marker-hitbox"
      x
      y
      18)
     (frontier-callout/callout
      {:bounds chart-bounds
       :data-role "portfolio-optimizer-frontier-callout-target"
       :label label
       :point position
       :rows rows})]))

(defn- current-marker
  [result x-domain y-domain]
  (when (and (opt-format/finite-number? (:current-expected-return result))
             (opt-format/finite-number? (:current-volatility result)))
    (let [point {:expected-return (:current-expected-return result)
                 :volatility (:current-volatility result)
                 :sharpe (get-in result [:current-performance :in-sample-sharpe])}
          position (point-position x-domain y-domain point)
          {:keys [x y]} position
          label "Current Portfolio"
          rows (frontier-callout/point-rows
                point
                {:exposure (frontier-callout/exposure-summary result :current)})]
      [:g {:class ["portfolio-frontier-marker" "text-info" "outline-none"]
           :data-role "portfolio-optimizer-frontier-current-marker"
           :role "img"
           :tabIndex 0
           :tabindex 0
           :focusable "true"
           :aria-label (frontier-callout/aria-label label rows)}
       [:circle {:cx x
                 :cy y
                 :r 5
                 :fill "currentColor"
                 :stroke "currentColor"
                 :strokeWidth 2}]
       [:circle {:cx x
                 :cy y
                 :r 11
                 :fill "transparent"
                 :stroke current-halo-stroke}]
       (frontier-callout/focus-ring x y 15)
       [:text {:x (+ x 10)
               :y (+ y 16)
               :fill "currentColor"
               :fontSize 10
               :fontWeight 700}
        "Current"]
       (frontier-callout/hitbox
        "portfolio-optimizer-frontier-current-marker-hitbox"
        x
        y
        18)
       (frontier-callout/callout
        {:bounds chart-bounds
         :data-role "portfolio-optimizer-frontier-callout-current"
         :label label
         :point position
         :rows rows})])))

(defn frontier-chart
  ([draft result]
   (frontier-chart draft result :standalone))
  ([draft result overlay-mode]
   (let [overlay-mode* (frontier-overlays/normalize-mode overlay-mode)
         overlay-points (frontier-overlays/visible-points result overlay-mode*)
         domain-overlay-points (frontier-overlays/all-points result)
         {:keys [subtitle
                 x-axis-prefix
                 y-axis-prefix
                 reading-text
                 legend-label]} (frontier-overlays/copy overlay-mode*)
         points (->> (:frontier result)
                     (filter #(and (opt-format/finite-number? (:volatility %))
                                   (opt-format/finite-number? (:expected-return %))))
                     (sort-by :volatility)
                     vec)
        x-domain (domain (concat (numeric-values points :volatility)
                                 (numeric-values domain-overlay-points :volatility)
                                 (when (opt-format/finite-number? (:volatility result))
                                   [(:volatility result)])
                                 (when (opt-format/finite-number? (:current-volatility result))
                                   [(:current-volatility result)]))
                         0
                         1)
        y-domain (domain (concat (numeric-values points :expected-return)
                                 (numeric-values domain-overlay-points :expected-return)
                                 (when (opt-format/finite-number? (:expected-return result))
                                   [(:expected-return result)])
                                 (when (opt-format/finite-number? (:current-expected-return result))
                                   [(:current-expected-return result)]))
                         0
                         1)
        positions (map #(point-position x-domain y-domain %) points)
        target (objective-target draft (first points))]
     (when (seq points)
       [:section {:class ["min-w-0" "overflow-hidden" "bg-transparent" "leading-4"]
                  :data-role "portfolio-optimizer-frontier-panel"}
        [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-3"]}
         [:div
          [:p {:class ["font-mono"
                       "text-[0.62rem]"
                       "uppercase"
                       "tracking-[0.08em]"
                       "text-trading-muted/70"]}
           "Efficient Frontier"]
          [:p {:class ["mt-1" "text-xs" "text-trading-muted"]}
           subtitle]]
         [:div {:class ["flex" "items-start" "gap-3"]}
          [:div {:class ["overflow-hidden" "border" "border-base-300" "bg-base-100/90"]}
           (into [:div {:class ["flex" "items-stretch"]}]
                 (map #(overlay-mode-button overlay-mode* %) frontier-overlays/modes))]
          [:p {:class ["font-mono" "text-[0.62rem]" "text-trading-muted/70"]}
           (str (count points) " points")]]]
        [:div {:class ["relative" "mt-4" "overflow-hidden" "border" "border-base-300" "bg-base-100" "p-4"]
               :data-role "portfolio-optimizer-frontier-chart-box"}
         [:div {:class ["absolute" "right-6" "top-6" "z-10" "border" "border-base-300" "bg-base-200/80" "px-3" "py-2" "text-[0.65rem]"]
                :data-role "portfolio-optimizer-frontier-legend"}
          [:div {:class ["flex" "items-center" "gap-2" "text-trading-muted"]}
           [:span {:class ["h-2" "w-2" "rounded-full" "bg-info"]}]
           "Where you are now"]
          [:div {:class ["mt-1" "flex" "items-center" "gap-2" "text-trading-text"]}
           [:span {:class ["h-2" "w-2" "rounded-full" "bg-primary"]}]
           "Recommended target"]
          (when legend-label
            [:div {:class ["mt-1" "flex" "items-center" "gap-2" "text-trading-muted"]}
             [:span {:class ["font-mono" "text-[0.62rem]" "uppercase" "tracking-[0.08em]"]}
              (case overlay-mode*
                :contribution "tri"
                :standalone "dia"
                "")]
             legend-label])]
         [:svg {:viewBox (str "0 0 " chart-width " " chart-height)
                :class ["h-[23.75rem]" "w-full" "overflow-visible" "text-trading-text"]
                :data-role "portfolio-optimizer-frontier-svg"}
          [:g {:data-role "portfolio-optimizer-frontier-grid"}
           (map-indexed (partial grid-line :vertical) [0 0.25 0.5 0.75 1])
           (map-indexed (partial grid-line :horizontal) [0 0.25 0.5 0.75 1])]
          [:line {:x1 chart-pad
                  :x2 (- chart-width chart-pad)
                  :y1 (- chart-height chart-pad)
                  :y2 (- chart-height chart-pad)
                  :stroke chart-axis-stroke}]
          [:line {:x1 chart-pad
                  :x2 chart-pad
                  :y1 chart-pad
                  :y2 (- chart-height chart-pad)
                  :stroke chart-axis-stroke}]
          [:path {:d (path-data positions)
                  :fill "none"
                  :stroke "currentColor"
                  :strokeWidth 2
                  :strokeLinecap "round"
                  :strokeLinejoin "round"
                  :class ["text-primary"]
                  :data-role "portfolio-optimizer-frontier-path"}]
          (map-indexed (fn [idx point]
                         (frontier-point draft idx point x-domain y-domain))
                       points)
          (current-marker result x-domain y-domain)
          (target-marker result x-domain y-domain)
          (map #(frontier-overlays/marker
                 {:bounds chart-bounds
                  :overlay-mode overlay-mode*
                  :point-position point-position
                  :x-domain x-domain
                  :y-domain y-domain
                  :point %})
               overlay-points)
          [:text {:x chart-pad
                  :y (- chart-height 8)
                  :fill "currentColor"
                  :fontSize 11
                  :opacity 0.65}
           (str x-axis-prefix " " (opt-format/format-pct (first x-domain)))]
          [:text {:x (- chart-width chart-pad)
                  :y (- chart-height 8)
                  :fill "currentColor"
                  :fontSize 11
                  :opacity 0.65
                  :textAnchor "end"}
           (str x-axis-prefix " " (opt-format/format-pct (second x-domain)))]
          [:text {:x 10
                  :y chart-pad
                  :fill "currentColor"
                  :fontSize 11
                  :opacity 0.65}
           (str y-axis-prefix " " (opt-format/format-pct (second y-domain)))]
          [:text {:x 10
                  :y (- chart-height chart-pad)
                  :fill "currentColor"
                  :fontSize 11
                  :opacity 0.65}
           (str y-axis-prefix " " (opt-format/format-pct (first y-domain)))]]]
        [:div {:class ["mt-4" "flex" "gap-3" "text-[0.7rem]" "text-trading-muted"]}
         [:span {:class ["font-mono" "text-[0.62rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
          "Reading this"]
         [:span "·"]
         [:span
          (str reading-text
               " Click or drag a point to set "
               (:label target)
               " and rerun.")]]]))))
