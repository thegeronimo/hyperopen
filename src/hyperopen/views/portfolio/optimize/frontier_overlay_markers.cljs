(ns hyperopen.views.portfolio.optimize.frontier-overlay-markers
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model.frontier :as overlay-model]
            [hyperopen.views.asset-icon :as asset-icon]
            [hyperopen.views.portfolio.optimize.frontier-callout :as frontier-callout]
            [hyperopen.views.portfolio.optimize.frontier-vault-markers :as vault-markers]))

(def ^:private standalone-color "#8f96a3")
(def ^:private contribution-color "#59a5c8")

(defn- marker-color
  [point default-color]
  (if (overlay-model/vault-point? point)
    vault-markers/accent
    default-color))

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
          :font-size 9
          :font-weight 700
          :text-anchor "middle"
          :class "portfolio-frontier-symbol-marker"
          :data-role data-role}
   label])

(defn- vault-marker
  [data-role x y point]
  (let [{:keys [code label-half leader-x1 leader-x2 label-x label-width]}
        (vault-markers/marker-layout point)]
    [:g {:data-role data-role
         :class ["portfolio-frontier-asset-icon-marker"
                 "portfolio-frontier-vault-marker"]
         :transform (str "translate(" x " " y ")")}
     [:g {:data-role (str/replace data-role
                                  "portfolio-optimizer-frontier-overlay-symbol"
                                  "portfolio-optimizer-frontier-vault-icon")}
      (vault-markers/layers-icon)]
     [:line {:x1 leader-x1
             :y1 0
             :x2 leader-x2
             :y2 0
             :class "portfolio-frontier-vault-leader"
             :stroke vault-markers/border
             :stroke-width 1
             :stroke-linecap "round"}]
     [:rect {:x label-x
             :y (- label-half)
             :width label-width
             :height vault-markers/label-height
             :rx 4
             :class "portfolio-frontier-vault-label"
             :fill "transparent"
             :stroke vault-markers/border
             :stroke-width 1
             :style {:filter "drop-shadow(0 0 4px rgba(53, 215, 199, 0.18))"}}]
     [:text {:x (+ label-x (/ label-width 2))
             :y 0
             :fill vault-markers/text
             :font-size 12
             :font-weight 600
             :letter-spacing "0.02em"
             :dominant-baseline "middle"
             :alignment-baseline "middle"
             :text-anchor "middle"
             :data-role (str/replace data-role
                                     "portfolio-optimizer-frontier-overlay-symbol"
                                     "portfolio-optimizer-frontier-vault-code")}
      code]]))

(defn- asset-clip-id
  [data-role]
  (str (str/replace data-role #"[^A-Za-z0-9_-]" "-") "-clip"))

(defn- asset-marker
  [data-role x y point color]
  (let [label (overlay-model/overlay-label point)]
    (if (overlay-model/vault-point? point)
      (vault-marker data-role x y point)
      (let [icon-url (asset-icon/market-icon-url (overlay-model/point-market point))]
        (if (seq icon-url)
          (let [icon-half 7
                clip-id (asset-clip-id data-role)]
            [:g {:data-role data-role
                 :class "portfolio-frontier-asset-icon-marker"}
             [:defs
              [:clipPath {:id clip-id
                          :clipPathUnits "userSpaceOnUse"}
               [:circle {:cx x
                         :cy y
                         :r icon-half}]]]
             [:circle {:cx x
                       :cy y
                       :r 9
                       :fill "var(--optimizer-surface)"
                       :stroke color
                       :stroke-width 1
                       :opacity 0.9}]
             [:image {:x (- x icon-half)
                      :y (- y icon-half)
                      :width (* icon-half 2)
                      :height (* icon-half 2)
                      :href icon-url
                      :clip-path (str "url(#" clip-id ")")
                      :preserveAspectRatio "xMidYMid slice"
                      :aria-hidden true}]])
          (symbol-marker data-role x y label color))))))

(defn- overlay-hitbox
  [data-role x y point]
  (if (overlay-model/vault-point? point)
    (let [{:keys [icon-half full-width]} (vault-markers/marker-layout point)]
      [:rect {:x (- x icon-half)
              :y (- y icon-half)
              :width full-width
              :height vault-markers/icon-size
              :rx 6
              :fill "transparent"
              :stroke "transparent"
              :pointerEvents "all"
              :data-role data-role}])
    (frontier-callout/hitbox data-role x y 16)))

(defn- standalone-point-model
  [{:keys [point-position x-domain y-domain point]}]
  (let [position (point-position x-domain y-domain point)
        {:keys [x y]} position
        label (overlay-model/overlay-label point)
        rows (frontier-callout/point-rows
              point
              {:target-weight (:target-weight point)})]
    {:position position
     :x x
     :y y
     :label label
     :rows rows}))

(defn- standalone-callout
  [{:keys [bounds point] :as opts}]
  (let [{:keys [position label rows]} (standalone-point-model opts)]
    (frontier-callout/callout
     {:bounds bounds
      :data-role (str "portfolio-optimizer-frontier-callout-standalone-"
                      (:instrument-id point))
      :label label
      :point position
      :rows rows})))

(defn- standalone-marker
  [{:keys [point render-callout?] :as opts}]
  (let [{:keys [x y label rows]} (standalone-point-model opts)]
    [:g (marker-shell-attrs
         (str "portfolio-optimizer-frontier-overlay-standalone-"
              (:instrument-id point))
         label
         rows
         (marker-color point standalone-color))
     (asset-marker
      (str "portfolio-optimizer-frontier-overlay-symbol-standalone-"
           (:instrument-id point))
      x
      y
      point
      (marker-color point standalone-color))
     (when-not (overlay-model/vault-point? point)
       (frontier-callout/focus-ring x y 15))
     (overlay-hitbox
      (str "portfolio-optimizer-frontier-overlay-standalone-"
           (:instrument-id point)
           "-hitbox")
      x
      y
      point)
     (when-not (false? render-callout?)
       (standalone-callout opts))]))

(defn- contribution-point-model
  [{:keys [point-position x-domain y-domain point]}]
  (let [position (point-position x-domain y-domain point)
        {:keys [x y]} position
        label (overlay-model/overlay-label point)
        rows (frontier-callout/point-rows
              point
              {:return-label "Return Contribution"
               :volatility-label "Volatility Contribution"
               :target-weight (:target-weight point)})]
    {:position position
     :x x
     :y y
     :label label
     :rows rows}))

(defn- contribution-callout
  [{:keys [bounds point] :as opts}]
  (let [{:keys [position label rows]} (contribution-point-model opts)]
    (frontier-callout/callout
     {:bounds bounds
      :data-role (str "portfolio-optimizer-frontier-callout-contribution-"
                      (:instrument-id point))
      :label label
      :point position
      :rows rows})))

(defn- contribution-marker
  [{:keys [point render-callout?] :as opts}]
  (let [{:keys [x y label rows]} (contribution-point-model opts)]
    [:g (marker-shell-attrs
         (str "portfolio-optimizer-frontier-overlay-contribution-"
              (:instrument-id point))
         label
         rows
         (marker-color point contribution-color))
     (asset-marker
      (str "portfolio-optimizer-frontier-overlay-symbol-contribution-"
           (:instrument-id point))
      x
      y
      point
      (marker-color point contribution-color))
     (when-not (overlay-model/vault-point? point)
       (frontier-callout/focus-ring x y 15))
     (overlay-hitbox
      (str "portfolio-optimizer-frontier-overlay-contribution-"
           (:instrument-id point)
           "-hitbox")
      x
      y
      point)
     (when-not (false? render-callout?)
       (contribution-callout opts))]))

(defn marker
  [{:keys [overlay-mode] :as opts}]
  (case (overlay-model/normalize-mode overlay-mode)
    :contribution (contribution-marker opts)
    :standalone (standalone-marker opts)
    nil))

(defn callout
  [{:keys [overlay-mode] :as opts}]
  (case (overlay-model/normalize-mode overlay-mode)
    :contribution (contribution-callout opts)
    :standalone (standalone-callout opts)
    nil))
