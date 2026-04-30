(ns hyperopen.views.portfolio.optimize.frontier-callout
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(def ^:private callout-width 188)
(def ^:private target-callout-width 244)
(def ^:private row-height 16)
(def ^:private header-height 39)
(def ^:private callout-margin 8)
(def ^:private designer-metric-start 38)
(def ^:private designer-section-gap 19)
(def ^:private designer-section-row-gap 17)
(def ^:private designer-footer-gap 9)
(def ^:private designer-footer-height 26)

(defn finite-positive?
  [value]
  (and (opt-format/finite-number? value)
       (pos? value)))

(defn sharpe
  [point]
  (let [provided (:sharpe point)
        expected-return (:expected-return point)
        volatility (:volatility point)]
    (cond
      (opt-format/finite-number? provided) provided
      (and (opt-format/finite-number? expected-return)
           (finite-positive? volatility)) (/ expected-return volatility)
      :else nil)))

(defn exposure-from-weights
  [weights]
  (let [weights* (filter opt-format/finite-number? weights)]
    (when (seq weights*)
      {:gross (reduce + (map js/Math.abs weights*))
       :net (reduce + weights*)})))

(defn exposure-summary
  [{:keys [diagnostics target-weights current-weights]} kind]
  (let [weights-exposure (case kind
                           :target (exposure-from-weights target-weights)
                           :current (exposure-from-weights current-weights)
                           nil)]
    (case kind
      :target
      {:gross (or (:gross-exposure diagnostics)
                  (:gross weights-exposure))
       :net (or (:net-exposure diagnostics)
                (:net weights-exposure))}

      :current weights-exposure

      nil)))

(defn- allocation-label
  [labels-by-instrument instrument-id]
  (let [value (or (get labels-by-instrument instrument-id)
                  (str instrument-id))
        unprefixed (last (str/split value #":"))
        base (first (str/split unprefixed #"[/-]"))]
    (if (seq base) base value)))

(defn allocation-summary
  ([instrument-ids weights]
   (allocation-summary instrument-ids weights nil))
  ([instrument-ids weights labels-by-instrument]
   (let [labels-by-instrument* (or labels-by-instrument {})
         rows (->> (map vector instrument-ids weights)
                   (keep (fn [[instrument-id weight]]
                           (when (and (some? instrument-id)
                                      (opt-format/finite-number? weight)
                                      (not (zero? weight)))
                             {:label (allocation-label labels-by-instrument*
                                                       instrument-id)
                              :weight weight
                              :value (opt-format/format-pct
                                      weight
                                      {:minimum-fraction-digits 1
                                       :maximum-fraction-digits 1})})))
                   vec)
         sum* (reduce + 0 (map :weight rows))]
     (when (seq rows)
       {:rows rows
        :sum (opt-format/format-pct sum*
                                    {:minimum-fraction-digits 1
                                     :maximum-fraction-digits 1})}))))

(defn hitbox
  [data-role x y radius]
  [:circle {:cx x
            :cy y
            :r radius
            :fill "transparent"
            :stroke "transparent"
            :pointerEvents "all"
            :data-role data-role}])

(defn focus-ring
  [x y radius]
  [:circle {:cx x
            :cy y
            :r radius
            :class "portfolio-frontier-focus-ring"}])

(defn point-rows
  ([point]
   (point-rows point {}))
  ([point {:keys [return-label volatility-label target-weight exposure]
           :or {return-label "Expected Return"
                volatility-label "Volatility"}}]
   (cond-> [{:label return-label
             :value (opt-format/format-pct (:expected-return point))}
            {:label volatility-label
             :value (opt-format/format-pct (:volatility point))}
            {:label "Sharpe"
             :value (opt-format/format-decimal (sharpe point))}]
     (some? target-weight)
     (conj {:label "Target Weight"
            :value (opt-format/format-pct target-weight)})

     (some? (:gross exposure))
     (conj {:label "Gross Exposure"
            :value (opt-format/format-pct (:gross exposure))})

     (some? (:net exposure))
     (conj {:label "Net Exposure"
            :value (opt-format/format-pct (:net exposure))}))))

(defn aria-label
  [label rows]
  (str label
       ", "
       (apply str
              (interpose ", "
                         (map (fn [{:keys [label value]}]
                                (str label " " value))
                              rows)))))

(defn- clamp
  [min* max* value]
  (if (< max* min*)
    min*
    (-> value
        (max min*)
        (min max*))))

(defn- callout-height
  ([rows allocations]
   (callout-height rows allocations header-height))
  ([rows allocations header-height*]
   (if-let [allocation-rows (seq (:rows allocations))]
     (+ designer-metric-start
        (* row-height (count rows))
        designer-section-gap
        designer-section-row-gap
        (* row-height (count allocation-rows))
        designer-footer-gap
        designer-footer-height)
     (+ header-height* (* row-height (count rows)) 10))))

(defn- origin
  ([bounds point rows allocations]
   (origin bounds point rows allocations callout-width header-height))
  ([{:keys [width height]} {:keys [x y]} rows allocations width* header-height*]
   (let [callout-height* (callout-height rows allocations header-height*)
         right-x (+ x 14)
         left-x (- x width* 14)
         raw-x (if (> (+ right-x width*) (- width callout-margin))
                 left-x
                 right-x)
         raw-y (- y 18)]
     {:x (clamp callout-margin
                (- width width* callout-margin)
                raw-x)
      :y (clamp callout-margin
                (- height callout-height* callout-margin)
                raw-y)})))

(defn- metric-row
  [idx {:keys [label value]}]
  (let [row-y (+ header-height (* row-height idx))]
    [:g {:key (str "row-" idx)}
     [:text {:x 10
             :y row-y
             :fill "var(--optimizer-text-2)"
             :fontSize 10}
      label]
     [:text {:x (- callout-width 10)
             :y row-y
             :fill "var(--optimizer-text)"
             :fontSize 10
             :fontWeight 700
             :text-anchor "end"}
      value]]))

(defn- designer-metric-row
  [idx {:keys [label value]}]
  (let [row-y (+ designer-metric-start (* row-height idx))]
    [:g {:key (str "metric-row-" idx)}
     [:text {:x 10
             :y row-y
             :fill "var(--optimizer-text-2)"
             :fontSize 10}
      label]
     [:text {:x (- callout-width 10)
             :y row-y
             :fill "var(--optimizer-text)"
             :fontSize 10
             :fontWeight 700
             :text-anchor "end"}
      value]]))

(defn- allocation-row
  [idx start-y {:keys [label value]}]
  (let [row-y (+ start-y (* row-height idx))]
    [:g {:key (str "allocation-row-" idx)}
     [:text {:x 10
             :y row-y
             :fill "var(--optimizer-text)"
             :fontSize 10
             :fontWeight 700}
      label]
     [:text {:x (- callout-width 10)
             :y row-y
             :fill "var(--optimizer-text)"
             :fontSize 10
             :fontWeight 700
             :text-anchor "end"}
      value]]))

(defn- designer-callout-content
  [label rows allocations]
  (let [allocation-rows (vec (:rows allocations))
        section-y (+ designer-metric-start
                     (* row-height (count rows))
                     designer-section-gap)
        allocation-start-y (+ section-y designer-section-row-gap)
        footer-y (+ allocation-start-y
                    (* row-height (count allocation-rows))
                    designer-footer-gap)
        sum-y (+ footer-y 18)]
    (concat
     [[:text {:x 10
              :y 18
              :fill "var(--optimizer-accent)"
              :fontSize 11
              :fontWeight 700}
       label]]
     (map-indexed designer-metric-row rows)
     [[:text {:x 10
              :y section-y
              :fill "var(--optimizer-text-2)"
              :fontSize 10
              :fontWeight 700}
       "ALLOCATIONS"]]
     (map-indexed #(allocation-row %1 allocation-start-y %2) allocation-rows)
     [[:line {:x1 0
              :x2 callout-width
              :y1 footer-y
              :y2 footer-y
              :stroke "var(--optimizer-border)"
              :strokeWidth 1
              :opacity 0.85}]
      [:text {:x 10
              :y sum-y
              :fill "var(--optimizer-text-2)"
              :fontSize 10}
       "Sum"]
	      [:text {:x (- callout-width 10)
	              :y sum-y
	              :fill "var(--optimizer-text)"
	              :fontSize 10
	              :text-anchor "end"}
	       (:sum allocations)]])))

(defn- row-nodes
  [rows width* header-height*]
  (map-indexed
   (fn [idx {:keys [label value]}]
     (let [row-y (+ header-height* (* row-height idx))]
       [:g {:key (str "row-" idx)}
        [:text {:x 10
                :y row-y
                :fill "var(--optimizer-text-2)"
                :fontSize 10}
         label]
        [:text {:x (- width* 10)
                :y row-y
                :fill "var(--optimizer-text)"
                :fontSize 10
	                :fontWeight 700
	                :text-anchor "end"}
	         value]]))
	   rows))

(defn- target-callout
  [{:keys [bounds data-role label point rows]}]
  (let [rows* (vec rows)
        width* target-callout-width
        header-height* 43
        height* (callout-height rows* nil header-height*)
        {:keys [x y]} (origin bounds point rows* nil width* header-height*)]
    (into
     [:g {:class "portfolio-frontier-callout"
          :data-role data-role
          :aria-hidden "true"
          :pointer-events "none"
          :transform (str "translate(" x " " y ")")}
      [:rect {:x 0
              :y 0
              :width width*
              :height height*
              :rx 4
              :fill "url(#portfolioOptimizerTargetTooltipBorderGradient)"
              :data-role "portfolio-optimizer-frontier-callout-target-border"}]
      [:rect {:x 1
              :y 1
              :width (- width* 2)
              :height (- height* 2)
              :rx 3
              :fill "rgba(13, 17, 24, 0.96)"}]
      [:circle {:cx 13
                :cy 17
                :r 4
                :fill "url(#portfolioOptimizerTargetOrbGradient)"
                :filter "url(#portfolioOptimizerTargetSoftGlow)"
                :data-role "portfolio-optimizer-frontier-callout-target-dot"}]
      [:text {:x 25
              :y 18
              :fill "#f5efff"
              :fontSize 13
              :fontWeight 650}
       label]
      [:line {:x1 10
              :x2 (- width* 10)
              :y1 30
	              :y2 30
	              :stroke "rgba(255, 255, 255, 0.08)"
	              :strokeWidth 1}]]
	     (row-nodes rows* width* header-height*))))

(defn callout
  [{:keys [bounds data-role label point rows allocations data-frontier-callout-id variant] :as opts}]
  (let [rows* (vec rows)]
    (if (= :target variant)
      (target-callout opts)
      (let [height* (callout-height rows* allocations)
            designer? (seq (:rows allocations))
            {:keys [x y]} (origin bounds point rows* allocations)]
        (into
         [:g (cond-> {:class "portfolio-frontier-callout"
                      :data-role data-role
                      :aria-hidden "true"
                      :pointer-events "none"
                      :transform (str "translate(" x " " y ")")}
               data-frontier-callout-id
               (assoc :data-frontier-callout-id data-frontier-callout-id))
          [:rect {:x 0
                  :y 0
                  :width callout-width
                  :height height*
                  :rx (if designer? 0 2)
                  :fill (if designer?
                          "rgba(10, 15, 19, 0.98)"
                          "var(--optimizer-surface-2)")
                  :stroke (if designer? "var(--optimizer-accent)" "none")
                  :strokeWidth (if designer? 1 0)}]
          (when-not designer?
            [:text {:x 10
                    :y 18
                    :fill "var(--optimizer-accent)"
                    :fontSize 11
                    :fontWeight 700}
             label])
          (when-not designer?
            [:line {:x1 10
                    :x2 (- callout-width 10)
                    :y1 27
                    :y2 27
                    :stroke "var(--optimizer-border)"
                    :strokeWidth 1
                    :opacity 0.8}])]
         (if designer?
           (designer-callout-content label rows* allocations)
           (map-indexed metric-row rows*)))))))
