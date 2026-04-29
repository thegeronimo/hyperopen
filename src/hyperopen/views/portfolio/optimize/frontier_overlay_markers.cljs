(ns hyperopen.views.portfolio.optimize.frontier-overlay-markers
  (:require [clojure.string :as str]
            [hyperopen.views.asset-icon :as asset-icon]
            [hyperopen.views.portfolio.optimize.frontier-callout :as frontier-callout]
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

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- base-symbol
  [value]
  (some-> value
          non-blank-text
          (str/replace #"^.*:" "")
          (str/split #"/|-" 2)
          first
          non-blank-text))

(defn- point-market
  [point]
  (let [instrument-id (non-blank-text (:instrument-id point))
        label (non-blank-text (:label point))
        [kind raw-coin] (when instrument-id
                          (str/split instrument-id #":" 2))
        coin (or (non-blank-text raw-coin)
                 label)
        base (or (base-symbol coin)
                 (base-symbol label))
        market-type (case kind
                      "spot" :spot
                      "perp" :perp
                      nil)]
    {:key instrument-id
     :coin coin
     :symbol (or (when (= :spot market-type) coin)
                 (when (and base (not= coin base))
                   (str base "-USDC"))
                 base)
     :base base
     :market-type market-type}))

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

(defn- symbol-marker
  [data-role x y label color]
  [:text {:x x
          :y (+ y 3)
          :fill color
          :fontSize 9
          :fontWeight 700
          :text-anchor "middle"
          :class "portfolio-frontier-symbol-marker"
          :data-role data-role}
   label])

(defn- asset-marker
  [data-role x y point color]
  (let [label (overlay-label point)
        icon-url (asset-icon/market-icon-url (point-market point))]
    (if (seq icon-url)
      [:g {:data-role data-role
           :class "portfolio-frontier-asset-icon-marker"}
       [:circle {:cx x
                 :cy y
                 :r 9
                 :fill "var(--optimizer-surface)"
                 :stroke color
                 :strokeWidth 1
                 :opacity 0.9}]
       [:image {:x (- x 7)
                :y (- y 7)
                :width 14
                :height 14
                :href icon-url
                :preserveAspectRatio "xMidYMid meet"
                :aria-hidden true}]]
      (symbol-marker data-role x y label color))))

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
     (asset-marker
      (str "portfolio-optimizer-frontier-overlay-symbol-standalone-"
           (:instrument-id point))
      x
      y
      point
      standalone-color)
     (frontier-callout/focus-ring x y 15)
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
     (asset-marker
      (str "portfolio-optimizer-frontier-overlay-symbol-contribution-"
           (:instrument-id point))
      x
      y
      point
      contribution-color)
     (frontier-callout/focus-ring x y 15)
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
