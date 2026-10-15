(ns hyperopen.views.active-asset.funding-history-chart
  "30-day funding chart for the funding tooltip: daily APYs oldest to newest,
  drawn as a filled area (default) or bars, with the 30-day average dashed and
  the latest day marked. Fill above 0% uses the buy token, below 0% the sell
  token, so a chart never misreports which side paid."
  (:require [hyperopen.utils.formatting :as fmt]))

(def ^:private width 292)

(def ^:private height 96)

(def ^:private pad-top 12)

(def ^:private area-gradient-id "funding-history-area-fill")

(def ^:private clip-above-id "funding-history-clip-above")

(def ^:private clip-below-id "funding-history-clip-below")

(defn- domain
  [values]
  (let [lo (apply min 0 values)
        hi (apply max 0 values)
        span (- hi lo)
        pad (if (zero? span) 1 (* span 0.08))]
    [(if (neg? lo) (- lo pad) 0)
     (+ hi pad)]))

(defn- y-scale
  [[lo hi]]
  (let [span (max 1e-9 (- hi lo))]
    (fn [value]
      (+ pad-top (* (- 1 (/ (- value lo) span)) (- height pad-top))))))

(defn- fmt-num
  [value]
  (.toFixed value 1))

(defn- percent-label
  [value]
  (str (fmt/format-fixed-number value 1) "%"))

(defn- point-x
  [idx point-count]
  (if (> point-count 1)
    (* width (/ idx (dec point-count)))
    (/ width 2)))

(defn- line-path
  [coords]
  (apply str (map-indexed (fn [idx [x y]]
                            (str (if (zero? idx) "M" " L") (fmt-num x) " " (fmt-num y)))
                          coords)))

(defn- area-chart
  [coords zero-y]
  (let [path (line-path coords)
        [first-x _] (first coords)
        [last-x _] (last coords)
        area (str path
                  " L" (fmt-num last-x) " " (fmt-num zero-y)
                  " L" (fmt-num first-x) " " (fmt-num zero-y) " Z")]
    [:g
     [:defs
      ;; currentColor in a gradient stop resolves where the gradient is defined,
      ;; so the gradient itself carries the buy color.
      [:linearGradient {:id area-gradient-id :x1 "0" :y1 "0" :x2 "0" :y2 "1" :class ["text-ho-buy"]}
       [:stop {:offset "0" :stop-color "currentColor" :stop-opacity "0.38"}]
       [:stop {:offset "1" :stop-color "currentColor" :stop-opacity "0.03"}]]
      [:clipPath {:id clip-above-id}
       [:rect {:x 0 :y 0 :width width :height zero-y}]]
      [:clipPath {:id clip-below-id}
       [:rect {:x 0 :y zero-y :width width :height (max 0 (- height zero-y))}]]]
     [:g {:class ["text-ho-buy"] :clip-path (str "url(#" clip-above-id ")")}
      [:path {:d area :fill (str "url(#" area-gradient-id ")")}]
      [:path {:d path :fill "none" :stroke "currentColor" :stroke-width 1.75
              :stroke-linejoin "round" :stroke-linecap "round"}]]
     [:g {:class ["text-ho-sell"] :clip-path (str "url(#" clip-below-id ")")}
      [:path {:d area :fill "currentColor" :fill-opacity "0.18"}]
      [:path {:d path :fill "none" :stroke "currentColor" :stroke-width 1.75
              :stroke-linejoin "round" :stroke-linecap "round"}]]]))

(defn- bar-chart
  [points y zero-y]
  (let [point-count (count points)
        step (/ width (max 1 point-count))
        bar-width (max 1.5 (* step 0.72))]
    [:g
     (for [[idx {:keys [apy day-index]}] (map-indexed vector points)
           :when (number? apy)]
       (let [top (y apy)
             latest? (= idx (dec point-count))]
         ^{:key (str "bar-" (or day-index idx))}
         [:rect {:x (fmt-num (+ (* idx step) (/ (- step bar-width) 2)))
                 :y (fmt-num (min top zero-y))
                 :width (fmt-num bar-width)
                 :height (fmt-num (max 1 (js/Math.abs (- zero-y top))))
                 :rx 1
                 :class [(if (neg? apy) "fill-ho-sell" "fill-ho-buy")]
                 :fill-opacity (if latest? "1" "0.55")
                 :data-funding-rate-value (str apy)}]))]))

(defn- signed-percent-label
  [value]
  (str (if (neg? value) "-" "+") (fmt/format-fixed-number (js/Math.abs value) 2) "%"))

(defn- day-label
  "Daily buckets are UTC days, so the date is formatted in UTC."
  [{:keys [day-start-ms]} idx point-count]
  (if (number? day-start-ms)
    (.toLocaleDateString (js/Date. day-start-ms) "en-US" #js {:month "short"
                                                              :day "numeric"
                                                              :timeZone "UTC"})
    (let [days-ago (- point-count 1 idx)]
      (if (zero? days-ago) "Today" (str days-ago "d ago")))))

(defn readout-text
  [point idx point-count]
  (str (day-label point idx point-count) " · "
       (if (number? (:apy point))
         (str (signed-percent-label (:apy point)) " APY")
         "no data")))

(def ^:private readout-half-width 62)

(defn- hover-layer
  "One hover target per day. Each day's guide, dot and readout show on hover
  through CSS alone, so moving the pointer never dispatches an action."
  [points y area?]
  (let [point-count (count points)
        slot (if area?
               (/ width (max 1 (dec point-count)))
               (/ width (max 1 point-count)))]
    [:g {:data-role "funding-history-hover-layer"}
     (for [[idx {:keys [apy day-index] :as point}] (map-indexed vector points)]
       (let [x (if area?
                 (point-x idx point-count)
                 (* slot (+ idx 0.5)))
             label-x (-> x (max readout-half-width) (min (- width readout-half-width)))]
         ^{:key (str "hover-" (or day-index idx))}
         [:g {:class ["group/day"] :data-role "funding-history-day"}
          [:rect {:x (fmt-num (- x (/ slot 2))) :y 0 :width (fmt-num slot) :height height
                  :fill "transparent" :pointer-events "all"}]
          [:g {:class ["pointer-events-none" "opacity-0" "group-hover/day:opacity-100"]}
           [:line {:x1 (fmt-num x) :x2 (fmt-num x) :y1 pad-top :y2 height
                   :stroke "currentColor" :class ["text-ho-text-muted"] :stroke-width 1
                   :stroke-dasharray "2 2"}]
           (when (number? apy)
             [:circle {:cx (fmt-num x) :cy (fmt-num (y apy)) :r 3.25
                       :class [(if (neg? apy) "fill-ho-sell" "fill-ho-text") "stroke-ho-bg-deep"]
                       :stroke-width 1.5}])
           [:text {:x (fmt-num label-x) :y 9 :font-size 10 :font-weight 600
                   :text-anchor "middle" :fill "currentColor"
                   :class ["text-ho-text" "stroke-ho-bg-deep"] :stroke-width 4
                   :paint-order "stroke" :stroke-linejoin "round"
                   :data-role "funding-history-readout"}
            (readout-text point idx point-count)]]]))]))

(defn- style-button
  [style current label icon]
  (let [active? (= style current)]
    [:button {:type "button"
              :aria-label label
              :aria-pressed (if active? "true" "false")
              :data-role (str "funding-chart-style-" (name style))
              :class (into ["inline-flex" "h-6" "w-7" "items-center" "justify-center" "rounded"
                            "transition-colors" "focus:outline-none" "focus-visible:ring-1"
                            "focus-visible:ring-ho-accent"]
                           (if active?
                             ["bg-ho-surface-raised" "text-ho-text"]
                             ["text-ho-text-muted" "hover:text-ho-text"]))
              :on {:click [[:actions/set-funding-chart-style (name style)]]}}
     icon]))

(defn chart-style-toggle
  [chart-style]
  (let [current (or chart-style :area)]
    [:div {:role "group"
           :aria-label "Chart style"
           :class ["flex" "gap-0.5" "rounded-md" "border" "border-ho-border-accent-muted"
                   "bg-ho-bg-deep" "p-0.5"]}
     (style-button :area current "Area chart"
                   [:svg {:viewBox "0 0 14 14" :width 14 :height 14 :fill "none" :stroke "currentColor"
                          :stroke-width 1.5 :stroke-linejoin "round" :aria-hidden "true"}
                    [:path {:d "M1 11 L4 6 L7 8 L10 3 L13 5"}]
                    [:path {:d "M1 13 H13"}]])
     (style-button :bars current "Bar chart"
                   [:svg {:viewBox "0 0 14 14" :width 14 :height 14 :fill "none" :stroke "currentColor"
                          :stroke-width 1.5 :stroke-linecap "round" :aria-hidden "true"}
                    [:path {:d "M2.5 12 V7"}]
                    [:path {:d "M5.5 12 V4"}]
                    [:path {:d "M8.5 12 V8"}]
                    [:path {:d "M11.5 12 V2"}]])]))

(defn funding-history-chart
  [{:keys [points mean-apy max-apy day-count]} chart-style]
  (let [values (vec (keep :apy points))]
    (when (seq values)
      (let [dom (domain values)
            y (y-scale dom)
            zero-y (y 0)
            point-count (count points)
            coords (vec (keep-indexed (fn [idx {:keys [apy]}]
                                        (when (number? apy)
                                          [(point-x idx point-count) (y apy)]))
                                      points))
            [last-x last-y] (last coords)
            area? (not= :bars chart-style)
            mean-y (when (number? mean-apy) (y mean-apy))]
        [:div {:data-role "funding-history-chart"
               :data-chart-style (if area? "area" "bars")}
         [:svg {:viewBox (str "0 0 " width " " height)
                :class ["group/chart" "block" "h-auto" "w-full" "overflow-visible"]
                :role "img"
                :aria-label (str "Daily funding APY over the last " day-count
                                 " days, oldest to newest"
                                 (when (number? mean-apy)
                                   (str ", averaging " (percent-label mean-apy))))}
          [:line {:x1 0 :x2 width :y1 (fmt-num zero-y) :y2 (fmt-num zero-y)
                  :stroke "currentColor" :class ["text-ho-border"] :stroke-width 1}]
          (if area?
            (area-chart coords zero-y)
            (bar-chart points y zero-y))
          (when area?
            [:g {:class ["text-ho-buy"]}
             [:circle {:cx (fmt-num last-x) :cy (fmt-num last-y) :r 5
                       :fill "currentColor" :fill-opacity "0.25"}]
             [:circle {:cx (fmt-num last-x) :cy (fmt-num last-y) :r 2.75
                       :fill "currentColor"}]])
          (when mean-y
            [:g {:class ["text-ho-text-muted"]}
             [:line {:x1 0 :x2 width :y1 (fmt-num mean-y) :y2 (fmt-num mean-y)
                     :stroke "currentColor" :stroke-width 1 :stroke-dasharray "3 3"}]
             [:text {:x 2 :y (fmt-num (- mean-y 3)) :font-size 10 :fill "currentColor"
                     :class ["stroke-ho-bg-deep"] :stroke-width 3 :paint-order "stroke"
                     :stroke-linejoin "round"}
              (str "avg " (percent-label mean-apy))]])
          (when (number? max-apy)
            [:text {:x width :y 9 :font-size 10 :text-anchor "end" :fill "currentColor"
                    :class ["text-ho-text-muted" "stroke-ho-bg-deep" "transition-opacity"
                            "group-hover/chart:opacity-0"]
                    :stroke-width 3 :paint-order "stroke" :stroke-linejoin "round"}
             (str "peak " (percent-label max-apy))])
          (hover-layer points y area?)]
         [:div {:class ["mt-1" "flex" "justify-between" "text-xs" "text-ho-text-muted"]}
          [:span (str day-count "d ago")]
          [:span {:class ["font-semibold" "text-ho-buy"]} "Today"]]]))))
