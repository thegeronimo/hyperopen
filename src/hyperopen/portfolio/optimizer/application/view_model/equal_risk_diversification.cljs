(ns hyperopen.portfolio.optimizer.application.view-model.equal-risk-diversification
  "Pure read models for portfolio diversification comparisons and additive
  own-variance/cross-covariance bridges."
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def cross-effect-neutral-threshold 0.0005)

(def material-change-threshold-pts
  "Displayed points below which a current → recommended change is presented
  as noise, not signal: the row's tone goes :neutral and the view renders
  '≈ unchanged' instead of a red/green direction verdict. The factual
  `:direction` and the signed pts value are NOT thresholded — only the
  judgment is. 0.5 pts ≈ the smallest change a person would act on here."
  0.5)

(def ^:private marker-overlap-position-threshold 6.0)

(defn cross-effect-direction
  [effect]
  (cond
    (< (js/Math.abs effect) cross-effect-neutral-threshold) :neutral
    (neg? effect) :offsets
    :else :amplifies))

(def ^:private scalar-keys
  [:modeled-volatility
   :all-move-together-volatility
   :zero-correlation-volatility
   :reduction-vs-all-move-together
   :reduction-ratio-vs-all-move-together
   :modeled-minus-zero-correlation])

(defn- valid-summary?
  [summary]
  (and (map? summary)
       (every? #(coercion/finite-number? (get summary %)) scalar-keys)))

(defn- format-pct
  [value]
  (str (.toFixed (* 100 value) 1) "%"))

(defn- displayed-point-tenths
  [value]
  (when (coercion/finite-number? value)
    (let [magnitude (js/Math.floor
                     (+ (* (js/Math.abs value) 1000) 0.5))]
      (cond
        (zero? magnitude) 0
        (neg? value) (- magnitude)
        :else magnitude))))

(defn- displayed-points
  [value]
  (some-> (displayed-point-tenths value) (/ 10)))

(defn- comparison-direction
  [change-points]
  (cond
    (not (coercion/finite-number? change-points)) :unavailable
    (zero? change-points) :unchanged
    (neg? change-points) :falls
    :else :rises))

(defn- effect-polarity
  [effect]
  (if-let [points (displayed-points effect)]
    (cond
      (zero? points) :neutral
      (neg? points) :offsetting
      :else :amplifying)
    :unavailable))

(defn- comparison-tone
  [direction favorable-direction]
  (case direction
    :unavailable :unavailable
    :unchanged :neutral
    (if (= direction favorable-direction) :favorable :unfavorable)))

(defn- relative-change
  [current change]
  (when (and (coercion/finite-number? current)
             (not (zero? current))
             (coercion/finite-number? change))
    (/ change current)))

(defn- shared-row
  [{:keys [key label current recommended current-position
           recommended-position favorable-direction]}]
  (let [comparable? (and (coercion/finite-number? current)
                         (coercion/finite-number? recommended))
        change (when comparable? (- recommended current))
        current-point-tenths (displayed-point-tenths current)
        recommended-point-tenths (displayed-point-tenths recommended)
        change-points (when comparable?
                        (/ (- recommended-point-tenths current-point-tenths)
                           10))
        direction (comparison-direction change-points)
        material? (and (coercion/finite-number? change-points)
                       (>= (js/Math.abs change-points)
                           material-change-threshold-pts))
        marker-overlap? (and comparable?
                             (coercion/finite-number? current-position)
                             (coercion/finite-number? recommended-position)
                             (<= (js/Math.abs
                                  (- recommended-position current-position))
                                 marker-overlap-position-threshold))]
    {:key key
     :label label
     :current-value (when comparable? current)
     :recommended-value recommended
     :current-position (when comparable? current-position)
     :recommended-position recommended-position
     :marker-overlap? (boolean marker-overlap?)
     :connector (when (and comparable?
                           (coercion/finite-number? current-position)
                           (coercion/finite-number? recommended-position))
                  {:start current-position :end recommended-position})
     :change change
     :change-points change-points
     :relative-change (relative-change current change)
     :direction direction
     :material? material?
     :tone (comparison-tone (if (or material?
                                    (= direction :unchanged)
                                    (= direction :unavailable))
                              direction
                              :unchanged)
                            favorable-direction)}))

(defn comparison-model
  [result]
  (let [structure (:risk-structure result)
        target (:target-diversification structure)
        current-summary (:current-diversification structure)]
    (when (valid-summary? target)
      (let [current (when (valid-summary? current-summary) current-summary)
            source (keep (fn [[key label summary]]
                           (when summary
                             {:key key :label label :summary summary}))
                         [[:current "Current" current]
                          [:target "Recommended" target]])
            scale-max (reduce max 0
                              (mapcat (fn [{:keys [summary]}]
                                        [(:modeled-volatility summary)
                                         (:all-move-together-volatility summary)
                                         (:zero-correlation-volatility summary)])
                                      source))
            position #(if (pos? scale-max) (* 100 (/ % scale-max)) 0)
            benchmark-specs
            [[:all-move-together "All move together"
              :all-move-together-volatility]
             [:zero-correlation "Zero correlation"
              :zero-correlation-volatility]
             [:modeled "Modeled" :modeled-volatility]]
            benchmark-rows
            (mapv (fn [[key label value-key]]
                    (shared-row
                     {:key key
                      :label label
                      :current (get current value-key)
                      :recommended (get target value-key)
                      :current-position (some-> (get current value-key)
                                                position)
                      :recommended-position (position (get target value-key))
                      :favorable-direction :falls}))
                  benchmark-specs)
            outcome-rows
            [(shared-row
              {:key :diversification-benefit
               :label "Diversification versus all-move-together"
               :current (get current :reduction-ratio-vs-all-move-together)
               :recommended (:reduction-ratio-vs-all-move-together target)
               :favorable-direction :rises})
             (let [current-effect
                   (get current :modeled-minus-zero-correlation)
                   recommended-effect
                   (:modeled-minus-zero-correlation target)]
               (assoc
                (shared-row
                 {:key :correlation-effect
                  :label "Correlation effect versus zero"
                  :current current-effect
                  :recommended recommended-effect
                  :favorable-direction :falls})
                :current-polarity (effect-polarity current-effect)
                :recommended-polarity
                (effect-polarity recommended-effect)))]
            rows-by-key (into {} (map (juxt :key identity)) benchmark-rows)
            modeled (rows-by-key :modeled)
            stress (rows-by-key :all-move-together)]
        {:scale-max scale-max
         :cards
         (mapv
          (fn [{:keys [key label summary]}]
            (let [effect (:modeled-minus-zero-correlation summary)
                  direction (cross-effect-direction effect)]
              {:key key
               :label label
               :scale-max scale-max
               :benchmarks
               [{:key :all-move-together :label "All move together"
                 :value (:all-move-together-volatility summary)}
                {:key :zero-correlation :label "Zero correlation"
                 :value (:zero-correlation-volatility summary)}
                {:key :modeled :label "Modeled"
                 :value (:modeled-volatility summary)}]
               :positions
               {:all-move-together
                (position (:all-move-together-volatility summary))
                :zero-correlation
                (position (:zero-correlation-volatility summary))
                :modeled (position (:modeled-volatility summary))}
               :benefit-copy
               (str (format-pct
                     (:reduction-ratio-vs-all-move-together summary))
                    " lower than all move together")
               :correlation-direction direction
               :correlation-effect effect
               :correlation-copy
               (case direction
                 :offsets "Correlations offset risk vs zero correlation"
                 :amplifies "Correlations amplify risk vs zero correlation"
                 "Correlations are neutral vs zero correlation")}))
          source)
         :benchmark-rows benchmark-rows
         :outcome-rows outcome-rows
         :decision-summary
         {:status (if current :comparison :target-only)
          :current-available? (boolean current)
          :modeled-direction (:direction modeled)
          :stress-direction (:direction stress)
          :modeled-current-value (:current-value modeled)
          :modeled-recommended-value (:recommended-value modeled)
          :stress-current-value (:current-value stress)
          :stress-recommended-value (:recommended-value stress)}}))))

(defn bridge-model
  [fit-scale rows]
  (let [scale (fit-scale (mapcat (fn [{:keys [standalone share]}]
                                   [standalone share]) rows))
        x (:x scale)]
    {:scale scale
     :rows
     (mapv (fn [{:keys [standalone diversification share] :as row}]
             (let [own (when (coercion/finite-number? standalone) standalone)
                   cross (when (coercion/finite-number? diversification)
                           diversification)
                   net (cond
                         (and own cross) (+ own cross)
                         (coercion/finite-number? share) share
                         :else nil)]
               (assoc row
                      :own-end own :cross-start own :cross-end net :net-end net
                      :cross-direction (when cross
                                         (cross-effect-direction cross))
                      :positions {:zero (x 0)
                                  :own (when own (x own))
                                  :cross-start (when own (x own))
                                  :cross-end (when net (x net))
                                  :net (when net (x net))})))
           rows)}))
