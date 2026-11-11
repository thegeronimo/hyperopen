(ns hyperopen.views.trading-chart.utils.chart-interop.axis-debug
  "Dev/test read hook: the price-axis labels the mounted chart paints.

   Axis labels live on a canvas, so browser tests cannot read them from the
   DOM. This runs the chart's own active price formatter
   (`series.priceFormatter().formatTickmarks`) over the tick marks of the
   visible price range, choosing the tick span the way Lightweight Charts'
   price scale does (its tick builder is private), plus the last-value label
   of every line.

   The span algorithm is copied from lightweight-charts 5.0.8
   (`PriceTickSpanCalculator` / `PriceTickMarkBuilder` in
   dist/lightweight-charts.development.mjs); re-check it when the library is
   upgraded.

   Required only by the dev console preload (`HYPEROPEN_DEBUG.chartAxis()`),
   so release builds carry none of it."
  (:require [hyperopen.views.trading-chart.runtime-state :as chart-runtime]))

(def ^:private tick-density 2.5)

(def ^:private epsilon 1e-14)

(defn- greater-or-equal? [a b] (<= (- b a) epsilon))

(defn- equal? [a b] (< (js/Math.abs (- a b)) epsilon))

(defn- decimal-base?
  [base]
  (and (>= base 0)
       (loop [current base]
         (cond
           (<= current 1) true
           (not (zero? (mod current 10))) false
           :else (recur (/ current 10))))))

(defn- fractional-dividers
  [base]
  (if (decimal-base? base)
    [2 2.5 2]
    (loop [rest* base out []]
      (cond
        (or (= rest* 1) (> (count out) 100)) out
        (zero? (mod rest* 2)) (recur (/ rest* 2) (conj out 2))
        (zero? (mod rest* 5)) (recur (/ rest* 5) (conj out 2 2.5))
        :else out))))

(defn- calculator-span
  "One of the price scale's three span calculators."
  [base integral high low max-span]
  (let [min-move (if (zero? base) 0 (/ 1 base))
        start (js/Math.pow 10 (max 0 (js/Math.ceil (js/Math.log10 (- high low)))))
        span (loop [span start index 0]
               (let [c (nth integral (mod index (count integral)))]
                 (if (and (greater-or-equal? span min-move)
                          (> span (+ min-move epsilon))
                          (greater-or-equal? span (* max-span c))
                          (greater-or-equal? span 1))
                   (recur (/ span c) (inc index))
                   span)))
        span (if (<= span (+ min-move epsilon)) min-move span)
        span (max 1 span)
        fractional (fractional-dividers base)]
    (if (and (seq fractional) (equal? span 1))
      (loop [span span index 0]
        (let [c (nth fractional (mod index (count fractional)))]
          (if (and (greater-or-equal? span (* max-span c))
                   (> span (+ min-move epsilon)))
            (recur (/ span c) (inc index))
            span)))
      span)))

(defn tick-span
  "The tick span the price scale picks for `[low, high]` over `height` px at
   `font-size`, for a series whose `min-move` sets the scale's base."
  [{:keys [high low height font-size min-move]}]
  (let [base (js/Math.round (/ 1 min-move))
        mark-height (js/Math.ceil (* font-size tick-density))
        max-span (/ (* (- high low) mark-height) height)]
    (apply min (map #(calculator-span base % high low max-span)
                    [[2 2.5 2] [2 2 2.5] [2.5 2 2]]))))

(defn tick-prices
  "Tick prices from `high` down, `span` apart, above `low`."
  [high low span]
  (let [m (mod high span)
        m (if (neg? m) (+ m span) m)]
    (vec (take-while #(> % low) (iterate #(- % span) (- high m))))))

(defn- chart-node
  []
  (when (exists? js/document)
    (or (.querySelector js/document "[data-role=\"outcome-answers-canvas\"]")
        (.querySelector js/document "[data-role=\"trading-chart-canvas\"]"))))

(defn- series-of
  [state]
  (if (:answers-chart? state)
    {:kind "answers"
     :chart (:chart state)
     :series (mapv :series (vals (:series-by-coin state)))}
    (when-let [chart-obj (:chart-obj state)]
      {:kind "candles"
       :chart (.-chart ^js chart-obj)
       :series (if-let [main (.-mainSeries ^js chart-obj)] [main] [])})))

(defn- last-value
  [^js series]
  (let [data (.data series)
        n (.-length data)]
    (when (pos? n)
      (let [point (aget data (dec n))]
        (or (.-close point) (.-value point))))))

(defn- min-move-of
  [^js series]
  (let [fmt (.-priceFormat (.options series))]
    (or (.-minMove fmt) 0.01)))

(defn snapshot
  "{:kind \"candles\"|\"answers\" :ticks [price] :labels [text]
    :lastLabels [text]} for the mounted chart, or {:present false}."
  []
  (let [{:keys [kind chart series]} (some-> (chart-node) chart-runtime/get-state series-of)
        ^js lead (first series)]
    (if-not (and chart lead)
      {:present false}
      (let [height (or (some-> ^js chart (.paneSize 0) .-height) 0)
            font-size (or (some-> ^js chart .options .-layout .-fontSize) 12)
            top (.coordinateToPrice lead 0)
            bottom (.coordinateToPrice lead (dec height))
            high (max top bottom)
            low (min top bottom)
            ticks (if (and (number? high) (number? low) (> high low) (pos? height))
                    (tick-prices high low (tick-span {:high high :low low :height height
                                                      :font-size font-size
                                                      :min-move (min-move-of lead)}))
                    [])
            formatter (.priceFormatter lead)]
        {:present true
         :kind kind
         :ticks ticks
         :labels (vec (array-seq (.formatTickmarks formatter (clj->js ticks))))
         :lastLabels (vec (keep (fn [^js s]
                                  (when-let [v (last-value s)]
                                    (.format (.priceFormatter s) v)))
                                series))}))))
