(ns hyperopen.views.portfolio.optimize.frontier-callout
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.frontier-callout-blended :as blended-callout]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(def ^:private callout-width 188)
(def ^:private target-callout-width 244)
(def ^:private row-height 16)
(def ^:private header-height 39)
(def ^:private title-line-height 13)
(def ^:private title-max-lines 2)
(def ^:private callout-title-max-chars 26)
(def ^:private target-title-max-chars 30)
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
  [{:keys [diagnostics
           target-weights
           current-weights
           current-portfolio-weights]} kind]
  (let [weights-exposure (case kind
                           :target (exposure-from-weights target-weights)
                           :current (exposure-from-weights
                                     (or current-portfolio-weights
                                         current-weights))
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
                              :instrument-id instrument-id
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

(defn- horizon-rows
  "Divider + the shorter-horizon 1σ rows for the Current/Target callouts
  (`horizons` from view-model.volatility-intuition/horizons — same scaling
  basis as the displayed annualized volatility). Sweep-point callouts don't
  pass horizons and stay compact."
  [{:keys [daily weekly monthly]}]
  (let [pm #(str "±" (opt-format/format-pct %))]
    [{:divider? true}
     {:label "Daily 1σ" :value (pm daily)}
     {:label "Weekly 1σ" :value (pm weekly)}
     {:label "Monthly 1σ" :value (pm monthly)}]))

(defn point-rows
  ([point]
   (point-rows point {}))
  ([point {:keys [return-label volatility-label target-weight exposure horizons]
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
            :value (opt-format/format-multiple (:gross exposure))})

     (some? (:net exposure))
     (conj {:label "Net Exposure"
            :value (opt-format/format-multiple (:net exposure))})

     (some? horizons)
     (into (horizon-rows horizons)))))

(defn aria-label
  [label rows]
  (str label
       ", "
       (apply str
              (interpose ", "
                         (map (fn [{:keys [label value]}]
                                (str label " " value))
                              (remove :divider? rows))))))

(defn- clamp
  [min* max* value]
  (if (< max* min*)
    min*
    (-> value
        (max min*)
        (min max*))))

(defn- title-wrap-limit
  [width*]
  (if (>= width* target-callout-width)
    target-title-max-chars
    callout-title-max-chars))

(defn- merge-overflow-line
  [line overflow]
  (str/trim (str line " " overflow)))

(defn- line-ellipsis
  [limit line]
  (let [line* (str/trim line)]
    (if (<= (count line*) limit)
      line*
      (str (subs line* 0 (max 0 (- limit 3))) "..."))))

(defn- word-pieces
  [limit word]
  (let [word* (str word)]
    (if (<= (count word*) limit)
      [word*]
      (map #(apply str %)
           (partition-all limit word*)))))

(defn- title-lines
  ([label]
   (title-lines label callout-width))
  ([label width*]
   (let [limit (title-wrap-limit width*)
         words (mapcat #(word-pieces limit %)
                       (str/split (str/trim (str label)) #"\s+"))]
     (if (<= (count (str/trim (str label))) limit)
       [(str/trim (str label))]
       (let [{:keys [lines current]}
             (reduce
              (fn [{:keys [lines current] :as acc} word]
                (let [candidate (str/trim (str current " " word))]
                  (cond
                    (empty? current)
                    (assoc acc :current word)

                    (<= (count candidate) limit)
                    (assoc acc :current candidate)

                    :else
                    {:lines (conj lines current)
                     :current word})))
              {:lines [] :current ""}
              words)
             wrapped (cond-> lines (seq current) (conj current))]
         (if (<= (count wrapped) title-max-lines)
           wrapped
           (conj (vec (take (dec title-max-lines) wrapped))
                 (line-ellipsis
                  limit
                  (merge-overflow-line (nth wrapped (dec title-max-lines))
                                       (str/join " " (drop title-max-lines wrapped)))))))))))

(defn- extra-title-height
  [lines]
  (* title-line-height (dec (count lines))))

(defn- title-text-node
  [{:keys [lines x y fill font-size font-weight data-role]}]
  (into
   [:text (cond-> {:x x
                   :y y
                   :fill fill
                   :font-size font-size
                   :font-weight font-weight}
            data-role (assoc :data-role data-role))]
   (map-indexed
    (fn [idx line]
      [:tspan {:x x
               :dy (if (zero? idx) 0 title-line-height)}
       line])
    lines)))

(defn- callout-height
  ([rows]
   (callout-height rows header-height))
  ([rows header-height*]
   (+ header-height* (* row-height (count rows)) 10)))

(defn- origin
  ([bounds point rows]
   (origin bounds point rows callout-width header-height))
  ([{:keys [width height]} {:keys [x y]} rows width* header-height*]
   (let [callout-height* (callout-height rows header-height*)
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

(defn- row-nodes
  [rows width* header-height*]
  (map-indexed
   (fn [idx {:keys [label value divider?]}]
     (let [row-y (+ header-height* (* row-height idx))]
       (if divider?
         [:line {:key (str "row-" idx)
                 :x1 10
                 :x2 (- width* 10)
                 :y1 (- row-y 4)
                 :y2 (- row-y 4)
                 :stroke "rgba(255, 255, 255, 0.08)"
                 :stroke-width 1}]
         [:g {:key (str "row-" idx)}
          [:text {:x 10
                  :y row-y
                  :fill "var(--optimizer-text-2)"
                  :font-size 10}
           label]
          [:text {:x (- width* 10)
                  :y row-y
                  :fill "var(--optimizer-text)"
                  :font-size 10
                  :font-weight 700
                  :text-anchor "end"}
           value]])))
   rows))

(defn- target-callout
  [{:keys [bounds data-role label point rows]}]
  (let [rows* (vec rows)
        width* target-callout-width
        title-lines* (title-lines label width*)
        header-height* (+ 43 (extra-title-height title-lines*))
        height* (callout-height rows* header-height*)
        {:keys [x y]} (origin bounds point rows* width* header-height*)]
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
      (title-text-node
       {:lines title-lines*
        :x 25
        :y 18
        :fill "#f5efff"
        :font-size 13
        :font-weight 650})
      [:line {:x1 10
              :x2 (- width* 10)
              :y1 (- header-height* 13)
              :y2 (- header-height* 13)
              :stroke "rgba(255, 255, 255, 0.08)"
              :stroke-width 1}]]
     (row-nodes rows* width* header-height*))))

(defn callout
  [{:keys [bounds data-role label point rows allocations data-frontier-callout-id variant] :as opts}]
  (let [rows* (vec rows)]
    (if (= :target variant)
      (target-callout opts)
      (let [blended? (seq (:rows allocations))
            title-lines* (title-lines label)
            header-height* (+ header-height (extra-title-height title-lines*))
            height* (callout-height rows* header-height*)
            {:keys [x y]} (origin bounds
                                   point
                                   rows*
                                   callout-width
                                   header-height*)]
        (if blended?
          (blended-callout/callout opts)
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
                    :rx 2
                    :fill "var(--optimizer-surface-2)"
                    :stroke "none"
                    :stroke-width 0}]
            (title-text-node
             {:lines title-lines*
              :x 10
              :y 18
              :fill "var(--optimizer-accent)"
              :font-size 11
              :font-weight 700})
            [:line {:x1 10
                    :x2 (- callout-width 10)
                    :y1 (- header-height* 12)
                    :y2 (- header-height* 12)
                    :stroke "var(--optimizer-border)"
                    :stroke-width 1
                    :opacity 0.8}]]
           (row-nodes rows* callout-width header-height*)))))))
