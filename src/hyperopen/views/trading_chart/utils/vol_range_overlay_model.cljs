(ns hyperopen.views.trading-chart.utils.vol-range-overlay-model
  "Pure layout for the Vol Range draft chart overlays (HO-SPEC-014 v2.1 11.1).

   Input is the display-ready chart model from
   `hyperopen.vol-range.application.projections/chart-model` (price strings);
   `price->y` is injected by the caller so this namespace has no DOM or chart
   dependency. VR-24: layout only, no order identity.")

(def ^:private cull-margin 30)
(def ^:private label-height 14)
(def ^:private min-thickness 1)
(def ^:private max-thickness 3)

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- price->coordinate
  [price->y price]
  (let [n (when (string? price) (js/parseFloat price))
        y (when (finite-number? n) (price->y n))]
    (when (finite-number? y) y)))

(defn- in-range?
  [y height]
  (or (not (pos? height))
      (and (>= y (- cull-margin))
           (<= y (+ height cull-margin)))))

(defn thickness-for-weight
  "Line thickness in px (1..3) from a 0..1 size weight."
  [weight]
  (let [w (if (finite-number? weight) (-> weight (max 0) (min 1)) 1)]
    (-> (+ min-thickness (* 2 w))
        (max min-thickness)
        (min max-thickness))))

(defn- layout-band
  [price->y height {:keys [sigma lower upper]}]
  (let [y1 (price->coordinate price->y lower)
        y2 (price->coordinate price->y upper)]
    (when (and y1 y2)
      (let [top (min y1 y2)
            bottom (max y1 y2)]
        (when (and (< top (+ height cull-margin))
                   (> bottom (- cull-margin)))
          {:sigma sigma :top top :bottom bottom})))))

(defn- layout-line
  [price->y height {:keys [price label]}]
  (when-let [y (price->coordinate price->y price)]
    (when (in-range? y height)
      {:y y :label label})))

(defn- layout-leg
  [price->y height {:keys [leg-id side price weight label]}]
  (when-let [y (price->coordinate price->y price)]
    (when (in-range? y height)
      {:leg-id leg-id
       :side side
       :y y
       :thickness (thickness-for-weight weight)
       :label label
       :label-y (- y label-height)})))

(defn- deoverlap-labels
  "Pushes label-y values down so no two labels are closer than a label height."
  [legs]
  (let [sorted (sort-by :label-y legs)]
    (loop [remaining sorted prev nil acc []]
      (if-let [leg (first remaining)]
        (let [min-y (when prev (+ prev label-height))
              label-y (if (and min-y (< (:label-y leg) min-y)) min-y (:label-y leg))]
          (recur (rest remaining) label-y (conj acc (assoc leg :label-y label-y))))
        acc))))

(defn overlay-layout
  "Returns {:bands :anchor :shifted-anchor :legs :stage-label} in pixel space,
   or nil without a chart model. Items whose y is not finite or lies outside
   [-30, height+30] are dropped."
  [chart-model price->y height width]
  (when (map? chart-model)
    (let [height* (if (finite-number? height) height 0)
          _ width]
      {:bands (->> (:bands chart-model)
                   (keep #(layout-band price->y height* %))
                   (sort-by :sigma >)
                   vec)
       :anchor (layout-line price->y height* (:anchor chart-model))
       :shifted-anchor (some->> (:shifted-anchor chart-model)
                                (layout-line price->y height*))
       :legs (->> (:legs chart-model)
                  (keep #(layout-leg price->y height* %))
                  deoverlap-labels
                  vec)
       :stage-label (:stage-label chart-model)})))
