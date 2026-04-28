(ns hyperopen.views.portfolio.optimize.frontier-overlay-markers
  (:require [hyperopen.views.portfolio.optimize.frontier-callout :as frontier-callout]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(def modes [:standalone :contribution :none])

(def ^:private standalone-color "#8f96a3")
(def ^:private contribution-color "#59a5c8")

(defn normalize-mode
  [overlay-mode]
  (if (some #{overlay-mode} modes)
    overlay-mode
    :standalone))

(defn visible-points
  [result overlay-mode]
  (let [mode (normalize-mode overlay-mode)]
    (if (contains? #{:standalone :contribution} mode)
      (->> (get-in result [:frontier-overlays mode])
           (filter #(and (opt-format/finite-number? (:volatility %))
                         (opt-format/finite-number? (:expected-return %))))
           vec)
      [])))

(defn all-points
  [result]
  (->> [:standalone :contribution]
       (mapcat #(get-in result [:frontier-overlays %]))
       (filter #(and (opt-format/finite-number? (:volatility %))
                     (opt-format/finite-number? (:expected-return %))))
       vec))

(defn copy
  [overlay-mode]
  (case (normalize-mode overlay-mode)
    :contribution
    {:subtitle "Risk vs return — annualized frontier with contribution overlays"
     :x-axis-prefix "Vol"
     :y-axis-prefix "Ret"
     :reading-text "Frontier points stay on the same risk / return scale. Overlay markers show signed volatility contribution on x and return contribution on y for each selected asset."
     :legend-label "Signed contribution"}

    :standalone
    {:subtitle "Risk vs return — annualized frontier with standalone asset overlays"
     :x-axis-prefix "Vol"
     :y-axis-prefix "Ret"
     :reading-text "Frontier points are feasible portfolios. Overlay markers show each selected asset as its own standalone risk / return point."
     :legend-label "Standalone assets"}

    {:subtitle "Risk vs return — annualized"
     :x-axis-prefix "Vol"
     :y-axis-prefix "Ret"
     :reading-text "Each point is a feasible portfolio."
     :legend-label nil}))

(defn- overlay-label
  [point]
  (or (:label point) (:instrument-id point)))

(defn- marker-shell-attrs
  ([data-role label rows]
   (marker-shell-attrs data-role label rows nil))
  ([data-role label rows color]
  {:data-role data-role
   :role "img"
   :tabIndex 0
   :tabindex 0
   :focusable "true"
   :class ["portfolio-frontier-marker" "outline-none"]
   :aria-label (frontier-callout/aria-label label rows)
   :style (when color {:color color})}))

(defn- standalone-marker
  [{:keys [bounds point-position x-domain y-domain point]}]
  (let [position (point-position x-domain y-domain point)
        {:keys [x y]} position
        label (overlay-label point)
        rows (frontier-callout/point-rows
              point
              {:target-weight (:target-weight point)})]
    [:g (marker-shell-attrs
         (str "portfolio-optimizer-frontier-overlay-standalone-"
              (:instrument-id point))
         label
         rows
         standalone-color)
     [:rect {:x (- x 5)
             :y (- y 5)
             :width 10
             :height 10
             :transform (str "rotate(45 " x " " y ")")
             :fill "none"
             :stroke standalone-color
             :strokeWidth 2}]
     (frontier-callout/focus-ring x y 15)
     [:text {:x (+ x 9)
             :y (- y 8)
             :fill standalone-color
             :fontSize 9
             :fontWeight 700}
      label]
     (frontier-callout/hitbox
      (str "portfolio-optimizer-frontier-overlay-standalone-"
           (:instrument-id point)
           "-hitbox")
      x
      y
      16)
     (frontier-callout/callout
      {:bounds bounds
       :data-role (str "portfolio-optimizer-frontier-callout-standalone-"
                       (:instrument-id point))
       :label label
       :point position
       :rows rows})]))

(defn- triangle-path
  [x y]
  (str "M " x " " (- y 6)
       " L " (+ x 6) " " (+ y 6)
       " L " (- x 6) " " (+ y 6)
       " Z"))

(defn- contribution-marker
  [{:keys [bounds point-position x-domain y-domain point]}]
  (let [position (point-position x-domain y-domain point)
        {:keys [x y]} position
        label (overlay-label point)
        rows (frontier-callout/point-rows
              point
              {:return-label "Return Contribution"
               :volatility-label "Volatility Contribution"
               :target-weight (:target-weight point)})]
    [:g (marker-shell-attrs
         (str "portfolio-optimizer-frontier-overlay-contribution-"
              (:instrument-id point))
         label
         rows
         contribution-color)
     [:path {:d (triangle-path x y)
             :fill "none"
             :stroke contribution-color
             :strokeWidth 2}]
     (frontier-callout/focus-ring x y 15)
     [:text {:x (+ x 9)
             :y (+ y 14)
             :fill contribution-color
             :fontSize 9
             :fontWeight 700}
      label]
     (frontier-callout/hitbox
      (str "portfolio-optimizer-frontier-overlay-contribution-"
           (:instrument-id point)
           "-hitbox")
      x
      y
      16)
     (frontier-callout/callout
      {:bounds bounds
       :data-role (str "portfolio-optimizer-frontier-callout-contribution-"
                       (:instrument-id point))
       :label label
       :point position
       :rows rows})]))

(defn marker
  [{:keys [overlay-mode] :as opts}]
  (case (normalize-mode overlay-mode)
    :contribution (contribution-marker opts)
    :standalone (standalone-marker opts)
    nil))
