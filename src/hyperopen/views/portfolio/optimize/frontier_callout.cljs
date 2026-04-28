(ns hyperopen.views.portfolio.optimize.frontier-callout
  (:require [hyperopen.views.portfolio.optimize.format :as opt-format]))

(def ^:private callout-width 188)
(def ^:private row-height 16)
(def ^:private header-height 30)
(def ^:private callout-margin 8)

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
  [rows]
  (+ header-height (* row-height (count rows)) 10))

(defn- origin
  [{:keys [width height]} {:keys [x y]} rows]
  (let [callout-height* (callout-height rows)
        right-x (+ x 14)
        left-x (- x callout-width 14)
        raw-x (if (> (+ right-x callout-width) (- width callout-margin))
                left-x
                right-x)
        raw-y (- y 18)]
    {:x (clamp callout-margin
               (- width callout-width callout-margin)
               raw-x)
     :y (clamp callout-margin
               (- height callout-height* callout-margin)
               raw-y)}))

(defn callout
  [{:keys [bounds data-role label point rows]}]
  (let [rows* (vec rows)
        height* (callout-height rows*)
        {:keys [x y]} (origin bounds point rows*)]
    (into
     [:g {:class "portfolio-frontier-callout"
          :data-role data-role
          :aria-hidden "true"
          :transform (str "translate(" x " " y ")")}
      [:rect {:x 0
              :y 0
              :width callout-width
              :height height*
              :rx 2
              :fill "var(--optimizer-surface-2)"
              :stroke "var(--optimizer-border-strong)"
              :strokeWidth 1}]
      [:text {:x 10
              :y 19
              :fill "var(--optimizer-accent)"
              :fontSize 11
              :fontWeight 700}
       label]]
     (map-indexed
      (fn [idx {:keys [label value]}]
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
      rows*))))
