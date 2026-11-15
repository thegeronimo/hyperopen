(ns hyperopen.views.portfolio.optimize.frontier-callout-blended
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.ids :as ids]
            [hyperopen.views.asset-icon :as asset-icon]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(def ^:private callout-width 224)
(def ^:private callout-margin 8)
(def ^:private row-height 16)
(def ^:private visible-allocation-count 6)
(def ^:private allocation-label-max-chars 24)
(def ^:private allocation-colors
  ["#e2b84f" "#2f80ed" "#7a8491" "#b26be5"
   "#35d7c7" "#83c44d" "#f59f43" "#ff6bb3"])

(defn- clamp
  [min* max* value]
  (if (< max* min*)
    min*
    (-> value
        (max min*)
        (min max*))))

(defn- origin
  [{:keys [width height]} {:keys [x y]} width* height*]
  (let [right-x (+ x 14)
        left-x (- x width* 14)
        raw-x (if (> (+ right-x width*) (- width callout-margin))
                left-x
                right-x)
        raw-y (- y 18)]
    {:x (clamp callout-margin
               (- width width* callout-margin)
               raw-x)
     :y (clamp callout-margin
               (- height height* callout-margin)
               raw-y)}))

(defn- metric-label
  [label]
  (case label
    "Expected Return" "μ · return"
    "Volatility" "σ · vol"
    label))

(defn- allocation-color
  [idx]
  (nth allocation-colors (mod idx (count allocation-colors))))

(def ^:private vault-instrument? ids/vault-instrument-id?)

(def ^:private non-blank-text coercion/non-blank-text)

(defn- base-symbol
  [value]
  (some-> value
          non-blank-text
          (str/replace #"^.*:" "")
          (str/split #"/|-" 2)
          first
          non-blank-text))

(defn- allocation-market
  [{:keys [instrument-id label]}]
  (let [instrument-id* (non-blank-text instrument-id)
        [kind raw-coin] (when instrument-id*
                          (str/split instrument-id* #":" 2))
        market-type (case kind
                      "spot" :spot
                      "perp" :perp
                      nil)
        coin (or (when market-type
                   (non-blank-text raw-coin))
                 instrument-id*
                 (base-symbol label))
        base (or (base-symbol coin)
                 (base-symbol label))]
    {:key instrument-id*
     :coin coin
     :symbol (or (when (= :spot market-type)
                   (when base
                     (str base "/USDC")))
                 base
                 label)
     :base base
     :market-type market-type}))

(defn- allocation-weight-magnitude
  [row]
  (let [weight (:weight row)]
    (if (opt-format/finite-number? weight)
      (js/Math.abs weight)
      0)))

(defn- compact-label
  [label]
  (let [label* (str/trim (str label))]
    (if (<= (count label*) allocation-label-max-chars)
      label*
      (let [[_ lead suffix] (re-matches #"^(.+?)\s+(\([^)]+\))$" label*)]
        (if (and lead suffix
                 (< (count suffix) (- allocation-label-max-chars 4)))
          (let [lead-limit (- allocation-label-max-chars
                              (count suffix)
                              4)]
            (str (str/trim (subs lead 0 (max 0 lead-limit)))
                 "... "
                 suffix))
          (str (subs label* 0 (- allocation-label-max-chars 3)) "..."))))))

(defn- normalized-allocation-rows
  [allocations]
  (->> (:rows allocations)
       (map-indexed (fn [idx row]
                      (assoc row :source-index idx)))
       (sort-by (fn [row]
                  [(- (allocation-weight-magnitude row))
                   (:source-index row)]))
       (map-indexed (fn [idx row]
                      (assoc row :color (or (:color row)
                                            (allocation-color idx)))))
       vec))

(defn- layout
  [rows allocation-rows]
  (let [metric-start-y 42
        bar-y (+ metric-start-y (* row-height (count rows)) 8)
        section-y (+ bar-y 20)
        allocation-start-y (+ section-y 17)
        visible-count (min visible-allocation-count
                           (count allocation-rows))
        overflow-count (max 0 (- (count allocation-rows)
                                 visible-allocation-count))
        height* (+ allocation-start-y
                   (* row-height visible-count)
                   (if (pos? overflow-count) 14 2)
                   8)]
    {:metric-start-y metric-start-y
     :bar-y bar-y
     :section-y section-y
     :allocation-start-y allocation-start-y
     :visible-count visible-count
     :overflow-count overflow-count
     :height height*}))

(defn- metric-row
  [start-y idx {:keys [label value divider?]}]
  (let [row-y (+ start-y (* row-height idx))]
    (if divider?
      [:line {:key (str "blended-metric-row-" idx)
              :x1 10
              :x2 (- callout-width 10)
              :y1 (- row-y 4)
              :y2 (- row-y 4)
              :stroke "rgba(255, 255, 255, 0.08)"
              :stroke-width 1}]
      [:g {:key (str "blended-metric-row-" idx)}
       [:text {:x 10
               :y row-y
               :fill "var(--optimizer-text-2)"
               :font-size 10}
        (metric-label label)]
       [:text {:x (- callout-width 10)
               :y row-y
               :fill "var(--optimizer-text)"
               :font-size 10
               :font-weight 700
               :text-anchor "end"}
        value]])))

(defn- allocation-segment-width
  [total-abs row]
  (let [bar-width (- callout-width 20)
        magnitude (allocation-weight-magnitude row)]
    (if (pos? total-abs)
      (* bar-width (/ magnitude total-abs))
      0)))

(defn- allocation-bar
  [y allocation-rows]
  (let [total-abs (reduce + 0 (map allocation-weight-magnitude allocation-rows))
        bar-width (- callout-width 20)
        segments (loop [rows allocation-rows
                        idx 0
                        x 10
                        acc []]
                   (if-let [row (first rows)]
                     (let [remaining (rest rows)
                           segment-width (if (seq remaining)
                                           (allocation-segment-width total-abs row)
                                           (- (+ 10 bar-width) x))
                           width* (max 0 segment-width)]
                       (recur remaining
                              (inc idx)
                              (+ x width*)
                              (conj acc
                                    [:rect {:x x
                                            :y y
                                            :width width*
                                            :height 3
                                            :fill (:color row)
                                            :data-role (str "portfolio-optimizer-frontier-callout-allocation-segment-"
                                                            idx)
                                            :data-allocation-segment (str idx)}])))
                     acc))]
    (into [:g {:data-role "portfolio-optimizer-frontier-callout-allocation-bar"}
           [:rect {:x 10
                   :y y
                   :width bar-width
                   :height 3
                   :fill "rgba(255, 255, 255, 0.08)"}]]
          segments)))

(defn- allocation-symbol
  [idx dot-y {:keys [color instrument-id] :as row}]
  (let [symbol-size 7
        symbol-half (/ symbol-size 2)
        icon-url (when (and (some? instrument-id)
                            (not (vault-instrument? instrument-id)))
                   (asset-icon/market-icon-url (allocation-market row)))]
    (cond
      (vault-instrument? instrument-id)
      [:rect {:x (- 13 3)
              :y (- dot-y 3)
              :width 6
              :height 6
              :fill "rgba(53, 215, 199, 0.16)"
              :stroke "rgba(143, 252, 241, 0.78)"
              :stroke-width 1
              :transform (str "rotate(45 13 " dot-y ")")
              :data-role (str "portfolio-optimizer-frontier-callout-allocation-vault-diamond-"
                              idx)}]

      (seq icon-url)
      [:image {:x (- 13 symbol-half)
               :y (- dot-y symbol-half)
               :width symbol-size
               :height symbol-size
               :href icon-url
               :preserveAspectRatio "xMidYMid meet"
               :aria-hidden true
               :data-role (str "portfolio-optimizer-frontier-callout-allocation-symbol-"
                               idx)}]

      :else
      [:circle {:cx 13
                :cy dot-y
                :r 3.5
                :fill color
                :data-role (str "portfolio-optimizer-frontier-callout-allocation-dot-"
                                idx)}])))

(defn- allocation-row
  [idx start-y {:keys [label value] :as row}]
  (let [row-y (+ start-y (* row-height idx))
        dot-y (- row-y 4)
        label* (compact-label label)]
    [:g {:key (str "blended-allocation-row-" idx)}
     (allocation-symbol idx dot-y row)
     [:text {:x 22
             :y row-y
             :fill "var(--optimizer-text)"
             :font-size 10
             :font-weight 700
             :data-role (str "portfolio-optimizer-frontier-callout-allocation-label-"
                             idx)
             :data-full-label label}
      label*]
     [:text {:x (- callout-width 10)
             :y row-y
             :fill "var(--optimizer-text)"
             :font-size 10
             :font-weight 700
             :text-anchor "end"}
      value]]))

(defn- content
  [label rows allocation-rows]
  (let [{:keys [metric-start-y bar-y section-y allocation-start-y
                visible-count overflow-count]} (layout rows allocation-rows)
        visible-rows (take visible-count allocation-rows)
        overflow-y (+ allocation-start-y (* row-height visible-count) 6)]
    (concat
     [[:circle {:cx 13
                :cy 15
                :r 3.5
                :fill "var(--optimizer-accent)"}]
      [:text {:x 24
              :y 18
              :fill "var(--optimizer-text)"
              :font-size 11
              :font-weight 700}
       (str/upper-case (str label))]
      [:text {:x (- callout-width 10)
              :y 18
              :fill "var(--optimizer-text-3)"
              :font-size 9
              :font-weight 700
              :letter-spacing "0.12em"
              :text-anchor "end"}
       "PORTFOLIO"]]
     (map-indexed #(metric-row metric-start-y %1 %2) rows)
     [(allocation-bar bar-y allocation-rows)
      [:text {:x 10
              :y section-y
              :fill "var(--optimizer-text-3)"
              :font-size 9.5
              :font-weight 700
              :letter-spacing "0.08em"}
       "IMPLIED ALLOCATION"]]
     (map-indexed #(allocation-row %1 allocation-start-y %2)
                  visible-rows)
     (when (pos? overflow-count)
       [[:text {:x 22
                :y overflow-y
                :fill "var(--optimizer-text-3)"
                :font-size 10}
         (str "+" overflow-count " more")]]))))

(defn callout
  [{:keys [bounds data-role label point rows allocations data-frontier-callout-id]}]
  (let [rows* (vec rows)
        allocation-rows (normalized-allocation-rows allocations)
        {:keys [height]} (layout rows* allocation-rows)
        {:keys [x y]} (origin bounds point callout-width height)]
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
              :height height
              :rx 0
              :fill "rgba(6, 10, 13, 0.98)"
              :stroke "var(--optimizer-border-strong)"
              :stroke-width 1
              :data-role "portfolio-optimizer-frontier-callout-card"}]]
     (content label rows* allocation-rows))))
