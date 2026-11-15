(ns hyperopen.views.account-info.margin-rec-curve
  "SVG chart for the margin recommendation panel: modeled probability of
  liquidation as a function of isolated collateral, with Current and
  Recommended markers sitting on the sampled curve the engine emits
  (`:curve` on the recommendation result).

  It is the panel's centrepiece, so it renders full-width and large; the
  viewBox is sized so that at the panel width the axis labels and marker
  callouts are comfortably legible."
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.margin-rec-copy :as copy]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.ui.hint-tooltip :as hint]))

(defn- fmt-usd
  [value]
  (if (number? value)
    (str "$" (shared/format-currency value))
    "--"))

(defn- fmt-probability
  [p]
  (cond
    (not (number? p)) "--"
    (< p 0.0005) "<0.1%"
    :else (str (.toFixed (* 100 p) 1) "%")))

(def ^:private chart-width 640)
(def ^:private chart-height 250)
(def ^:private plot-left 52)
(def ^:private plot-right 626)
(def ^:private plot-top 30)
(def ^:private plot-bottom 198)
(def ^:private grid-stroke "rgb(var(--ho-text-muted) / 0.25)")
(def ^:private curve-stroke "rgb(var(--ho-warn))")
(def ^:private curve-stroke-safe "rgb(var(--ho-buy))")
(def ^:private curve-gradient-id "margin-rec-curve-gradient")
(def ^:private label-box-fill "rgb(var(--ho-bg-deep) / 0.92)")
(def ^:private label-title-fill "rgb(var(--ho-text) / 0.92)")
(def ^:private label-box-top 34)
(def ^:private label-box-height 56)
(def ^:private label-box-width 100)

(defn- chart-x
  [x-max e]
  (+ plot-left
     (* (/ (-> e (max 0) (min x-max)) x-max)
        (- plot-right plot-left))))

(defn- chart-y
  [p]
  (+ plot-top (* (- 1 (-> p (max 0) (min 1))) (- plot-bottom plot-top))))

(defn- axis-usd
  [value]
  (str "$" (js/parseFloat (.toFixed value 2))))

(defn- marker
  "Dashed drop line, dot on the curve, and a labeled callout box:
  name / collateral / probability."
  [role color-class label-x marker-x e p title]
  (let [y (chart-y p)
        box-left (- label-x (/ label-box-width 2))]
    [:g {:class [color-class]
         :data-role role}
     [:line {:x1 marker-x
             :x2 marker-x
             :y1 (+ label-box-top label-box-height)
             :y2 plot-bottom
             :stroke "currentColor"
             :stroke-dasharray "4 4"
             :opacity 0.55}]
     [:circle {:cx marker-x
               :cy y
               :r 6
               :fill "currentColor"}]
     [:rect {:x box-left
             :y label-box-top
             :width label-box-width
             :height label-box-height
             :rx 6
             :fill label-box-fill
             :stroke "currentColor"
             :stroke-opacity 0.45}]
     [:text {:x label-x
             :y (+ label-box-top 16)
             :fill label-title-fill
             :font-size 11
             :text-anchor "middle"}
      title]
     [:text {:x label-x
             :y (+ label-box-top 34)
             :fill "currentColor"
             :font-size 15
             :font-weight 600
             :text-anchor "middle"}
      (fmt-usd e)]
     [:text {:x label-x
             :y (+ label-box-top 50)
             :fill "currentColor"
             :font-size 15
             :font-weight 600
             :text-anchor "middle"}
      (fmt-probability p)]]))

(defn- curve-chart
  [{:keys [x-max points]} current-e p-now rec-e p-after]
  (let [polyline (str/join " " (map (fn [{:keys [e p]}]
                                      (str (chart-x x-max e) ","
                                           (chart-y p)))
                                    points))
        cur-x (when (number? current-e) (chart-x x-max current-e))
        rec-x (when (number? rec-e) (chart-x x-max rec-e))
        plot-width (- plot-right plot-left)
        marker-frac (fn [x] (-> (/ (- x plot-left) plot-width) (max 0) (min 1)))
        cur-frac (when cur-x (marker-frac cur-x))
        rec-frac (when rec-x (max (marker-frac rec-x) (or cur-frac 0)))
        gradient? (and cur-frac rec-frac)
        curve-paint (if gradient?
                      (str "url(#" curve-gradient-id ")")
                      curve-stroke)
        half-box (/ label-box-width 2)
        cur-label-x (when cur-x
                      (-> cur-x
                          (max (+ plot-left half-box))
                          (min (- plot-right label-box-width half-box 8))))
        rec-label-x (when rec-x
                      (-> rec-x
                          (max (if cur-label-x
                                 (+ cur-label-x label-box-width 8)
                                 (+ plot-left half-box)))
                          (min (- plot-right half-box))))]
    (-> [:svg {:viewBox (str "0 0 " chart-width " " chart-height)
               :class ["w-full" "overflow-visible" "text-trading-text"]
               :data-role "margin-rec-curve"
               :aria-label "Modeled probability of liquidation versus isolated margin. The curve falls as collateral increases; markers show the current and recommended margin."}]
        (into (map (fn [p]
                     (let [y (chart-y p)]
                       [:g {:key (str "y-" p)}
                        [:line {:x1 plot-left :x2 plot-right :y1 y :y2 y
                                :stroke grid-stroke}]
                        [:text {:x (- plot-left 8)
                                :y (+ y 4)
                                :fill "currentColor"
                                :font-size 12
                                :opacity 0.6
                                :text-anchor "end"}
                         (str (js/Math.round (* 100 p)) "%")]]))
                   [0 0.5 1]))
        (into (map (fn [i]
                     (let [value (* (/ x-max 4) i)
                           x (chart-x x-max value)]
                       [:text {:key (str "x-" i)
                               :x x
                               :y (+ plot-bottom 20)
                               :fill "currentColor"
                               :font-size 12
                               :opacity 0.6
                               :text-anchor "middle"}
                        (axis-usd value)]))
                   (range 5)))
        (conj [:text {:x (/ (+ plot-left plot-right) 2)
                      :y (+ plot-bottom 40)
                      :fill "currentColor"
                      :font-size 12
                      :opacity 0.72
                      :text-anchor "middle"}
               "Isolated margin (USDC)"]
              [:polyline {:points polyline
                          :fill "none"
                          :stroke curve-paint
                          :stroke-width 2.5
                          :stroke-linejoin "round"}]
              (when gradient?
                [:defs
                 [:linearGradient {:id curve-gradient-id
                                   :x1 "0%" :y1 "0%" :x2 "100%" :y2 "0%"}
                  [:stop {:offset "0%" :stop-color curve-stroke}]
                  [:stop {:offset (str (.toFixed (* 100 cur-frac) 2) "%")
                          :stop-color curve-stroke}]
                  [:stop {:offset (str (.toFixed (* 100 rec-frac) 2) "%")
                          :stop-color curve-stroke-safe}]
                  [:stop {:offset "100%" :stop-color curve-stroke-safe}]]])
              (when (and cur-x (number? p-now))
                (marker "margin-rec-curve-current" "text-amber-400"
                        cur-label-x cur-x current-e p-now "Current"))
              (when (and rec-x (number? p-after))
                (marker "margin-rec-curve-recommended" "text-trading-green"
                        rec-label-x rec-x rec-e p-after "Recommended"))))))

(defn curve-card
  [curve current-e p-now rec-e p-after]
  (when (seq (:points curve))
    [:div {:class ["rounded-lg" "bg-base-300/40" "p-3"]
           :data-role "margin-rec-curve-card"}
     (hint/attach
      (copy/tip :chart)
      [:div {:class ["mb-1.5" "inline-flex" "items-center" "gap-1.5" "text-sm"
                     "font-semibold" "text-trading-text"]}
       [:span "Modeled probability of liquidation vs. collateral"]
       [:span {:class ["text-xs" "font-normal" "text-trading-text-secondary"]}
        "ⓘ"]]
      {:placement :bottom-start})
     (curve-chart curve current-e p-now rec-e p-after)]))
