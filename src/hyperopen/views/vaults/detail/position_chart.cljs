(ns hyperopen.views.vaults.detail.position-chart
  "Value of the viewer's current vault position over time against its cost
   basis, with deposit and withdrawal markers. The plot is an SVG stretched to
   its box (`preserveAspectRatio=none`, non-scaling strokes); labels, markers
   and hover readouts are HTML positioned in percent so they never distort.
   Hover is pure CSS (one named `group/pt` band per sample)."
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.vaults.detail.format :as vf]))

(def ^:private plot-width 1000)
(def ^:private plot-height 240)

(def ^:private value-stroke "rgb(var(--ho-accent-hi))")
(def ^:private basis-stroke "rgb(var(--ho-text-dim))")
(def ^:private gain-fill "rgb(var(--ho-buy) / 0.14)")
(def ^:private loss-fill "rgb(var(--ho-sell) / 0.14)")

(defn- compact-usd
  [value]
  (let [magnitude (js/Math.abs value)]
    (cond
      (>= magnitude 1e6) (str "$" (.toFixed (/ value 1e6) 2) "M")
      (>= magnitude 1e4) (str "$" (.toFixed (/ value 1e3) 1) "k")
      :else (vf/format-currency value))))

(defn- format-point-time
  [time-ms span]
  (or (fmt/format-intl-date-time time-ms (case span
                                           :short {:month "short" :day "numeric"
                                                   :hour "numeric" :minute "2-digit"}
                                           :long {:month "short" :day "numeric" :year "numeric"}
                                           {:month "short" :day "numeric"}))
      "—"))

(defn- signed-usd
  [value]
  (if (neg? value)
    (str "−" (vf/format-currency (js/Math.abs value)))
    (str "+" (vf/format-currency value))))

(defn- domain
  [points]
  (let [values (mapcat (juxt :value :basis) points)
        lo (apply min values)
        hi (apply max values)
        pad (if (> hi lo) (* 0.12 (- hi lo)) (max 1 (* 0.01 (js/Math.abs hi))))]
    ;; Balances are never negative: padding must not invent a $-49k tick.
    [(if (>= lo 0) (max 0 (- lo pad)) (- lo pad))
     (+ hi pad)]))

(defn chart-geometry
  "Pure projection of a position series onto the plot box."
  [{:keys [points start-ms end-ms markers]}]
  (let [[lo hi] (domain points)
        span-ms (max 1 (- end-ms start-ms))
        x (fn [t] (* plot-width (/ (- t start-ms) span-ms)))
        y (fn [v] (- plot-height (* plot-height (/ (- v lo) (- hi lo)))))
        xy (fn [k] (map (fn [p] [(x (:time-ms p)) (y (get p k))]) points))
        ->path (fn [pairs] (apply str (map-indexed (fn [i [px py]]
                                                     (str (if (zero? i) "M" " L")
                                                          (.toFixed px 2) "," (.toFixed py 2)))
                                                   pairs)))
        value-xy (xy :value)
        basis-xy (xy :basis)
        ;; One hover sample per timestamp: a transfer's "after" point wins.
        samples (vals (into (sorted-map) (map (juxt :time-ms identity) points)))
        sample-xs (mapv #(x (:time-ms %)) samples)]
    {:lo lo
     :hi hi
     :value-path (->path value-xy)
     :basis-path (->path basis-xy)
     :area-path (str (->path value-xy) " " (subs (->path (reverse basis-xy)) 1) " Z")
     :span (cond
             (< span-ms (* 2 86400000)) :short
             (> span-ms (* 300 86400000)) :long
             :else :medium)
     :samples (map-indexed
               (fn [i point]
                 (let [px (nth sample-xs i)
                       left (if (zero? i) 0 (/ (+ px (nth sample-xs (dec i))) 2))
                       right (if (= i (dec (count sample-xs)))
                               plot-width
                               (/ (+ px (nth sample-xs (inc i))) 2))]
                   (assoc point
                          :x-pct (* 100 (/ px plot-width))
                          :y-pct (* 100 (/ (y (:value point)) plot-height))
                          :band-left-pct (* 100 (/ left plot-width))
                          :band-width-pct (* 100 (/ (- right left) plot-width)))))
               samples)
     :markers (map (fn [{:keys [time-ms] :as marker}]
                     (let [after (last (filter #(= time-ms (:time-ms %)) points))]
                       (assoc marker
                              :x-pct (* 100 (/ (x time-ms) plot-width))
                              :y-pct (* 100 (/ (y (:value after)) plot-height)))))
                   markers)}))

(defn- pct
  [n]
  (str (.toFixed n 3) "%"))

(defn- legend-swatch
  [classes label]
  [:span {:class ["inline-flex" "items-center" "gap-1.5"]}
   [:span {:class classes}]
   label])

(defn- hover-band
  [{:keys [time-ms value basis x-pct y-pct band-left-pct band-width-pct]} span]
  (let [pnl (- value basis)
        flip? (> x-pct 62)]
    [:div {:class ["group/pt" "absolute" "inset-y-0"]
           :style {:left (pct band-left-pct) :width (pct band-width-pct)}
           :data-role "vault-position-chart-sample"}
     [:div {:class ["pointer-events-none" "absolute" "inset-y-0" "w-px" "bg-ho-border-accent"
                    "opacity-0" "group-hover/pt:opacity-100"]
            :style {:left (pct (* 100 (/ (- x-pct band-left-pct) (max band-width-pct 0.001))))}}]
     [:div {:class ["pointer-events-none" "absolute" "z-10" "w-56" "rounded-lg" "border" "border-ho-border-accent"
                    "bg-ho-bg-deep" "px-3" "py-2" "text-xs" "opacity-0" "shadow-lg" "group-hover/pt:opacity-100"]
            :style (merge {:top (pct (min 60 (max 0 (- y-pct 30))))}
                          (if flip?
                            {:right "50%"}
                            {:left "50%"}))}
      [:div {:class ["mb-1" "text-ho-text-muted"]} (format-point-time time-ms span)]
      [:div {:class ["flex" "justify-between" "gap-3"]}
       [:span {:class ["text-ho-text-secondary"]} "Value"]
       [:span {:class ["num" "text-trading-text"]} (vf/format-currency value)]]
      [:div {:class ["flex" "justify-between" "gap-3"]}
       [:span {:class ["text-ho-text-secondary"]} "Cost basis"]
       [:span {:class ["num" "text-trading-text"]} (vf/format-currency basis)]]
      [:div {:class ["flex" "justify-between" "gap-3"]}
       [:span {:class ["text-ho-text-secondary"]} "P&L"]
       [:span {:class ["num" (cond
                               (>= pnl 0.005) "text-ho-buy"
                               (<= pnl -0.005) "text-ho-sell"
                               :else "text-trading-text")]}
        (signed-usd pnl)]]]]))

(defn position-chart
  [series]
  (when series
    (let [{:keys [lo hi value-path basis-path area-path samples markers span]}
          (chart-geometry series)
          loss? (let [last-point (peek (:points series))]
                  (< (:value last-point) (:basis last-point)))]
      [:div {:class ["min-w-0"] :data-role "vault-position-chart"}
       [:div {:class ["mb-2" "flex" "flex-wrap" "items-center" "justify-between" "gap-x-4" "gap-y-1"]}
        [:div {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-ho-text-muted"]}
         "Position value since deposit"]
        [:div {:class ["flex" "flex-wrap" "gap-x-4" "gap-y-1" "text-xs" "text-ho-text-secondary"]}
         (legend-swatch ["h-0.5" "w-4" "bg-ho-accent-hi"] "Value")
         (legend-swatch ["h-0" "w-4" "border-t-2" "border-dashed" "border-ho-text-dim"] "Cost basis")
         (legend-swatch ["h-2.5" "w-2.5" "rounded-full" "bg-ho-info"] "Deposit")
         (legend-swatch ["h-2.5" "w-2.5" "rounded-full" "bg-ho-warn"] "Withdrawal")]]
       [:div {:class ["flex" "gap-2"]}
        [:div {:class ["num" "flex" "w-12" "shrink-0" "flex-col" "justify-between" "py-0.5" "text-right" "text-xs" "text-ho-text-dim"]
               :aria-hidden true}
         [:span (compact-usd hi)]
         [:span (compact-usd (/ (+ hi lo) 2))]
         [:span (compact-usd lo)]]
        [:div {:class ["relative" "h-44" "min-w-0" "flex-1" "md:h-56"]}
         [:svg {:viewBox (str "0 0 " plot-width " " plot-height)
                :preserveAspectRatio "none"
                :class ["absolute" "inset-0" "h-full" "w-full" "overflow-visible"]
                :role "img"
                :aria-label "Estimated value of your position over time compared with the amount you deposited."}
          (for [gy [0 (/ plot-height 2) plot-height]]
            [:line {:key (str "grid-" gy)
                    :x1 0 :x2 plot-width :y1 gy :y2 gy
                    :stroke "rgb(var(--ho-border-accent-muted))"
                    :stroke-width 1
                    :vector-effect "non-scaling-stroke"}])
          [:path {:d area-path :fill (if loss? loss-fill gain-fill) :stroke "none"}]
          [:path {:d basis-path :fill "none" :stroke basis-stroke :stroke-width 1.5
                  :stroke-dasharray "5 4" :vector-effect "non-scaling-stroke"}]
          [:path {:d value-path :fill "none" :stroke value-stroke :stroke-width 2
                  :stroke-linejoin "round" :vector-effect "non-scaling-stroke"}]]
         (for [{:keys [kind time-ms x-pct y-pct]} markers]
           [:span {:key (str "marker-" (name kind) "-" time-ms)
                   :class ["pointer-events-none" "absolute" "h-2.5" "w-2.5" "-translate-x-1/2" "-translate-y-1/2"
                           "rounded-full" "ring-2" "ring-ho-bg-deep"
                           (if (= :deposit kind) "bg-ho-info" "bg-ho-warn")]
                   :style {:left (pct x-pct) :top (pct y-pct)}
                   :data-role "vault-position-chart-marker"}])
         (for [sample samples]
           ^{:key (str "sample-" (:time-ms sample))}
           (hover-band sample span))]]
       [:div {:class ["ml-14" "mt-1.5" "flex" "justify-between" "text-xs" "text-ho-text-dim"]}
        [:span (format-point-time (:start-ms series) span)]
        [:span "Now"]]
       [:div {:class ["mt-1" "text-xs" "text-ho-text-dim"]}
        "Estimated from the vault's share-price history; the last point is your live balance."]])))
