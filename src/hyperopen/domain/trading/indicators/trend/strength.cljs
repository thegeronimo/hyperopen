(ns hyperopen.domain.trading.indicators.trend.strength
  (:require [hyperopen.domain.trading.indicators.math-engine :as math-engine]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private finite-number? imath/finite-number?)
(def ^:private parse-period imath/parse-period)
(def ^:private parse-number imath/parse-number)
(def ^:private field-values imath/field-values)
(def ^:private normalize-values imath/normalize-values)

(defn- window-for-index
  [values idx period]
  (imath/window-for-index values idx period :lagged))

(defn- rma-values
  [values period]
  (imath/rma-values values period :lagged))

(defn- last-index-of
  [values target]
  (loop [idx (dec (count values))]
    (cond
      (< idx 0) nil
      (= (nth values idx) target) idx
      :else (recur (dec idx)))))

(defn- true-range-values
  [highs lows closes]
  (let [size (count highs)]
    (mapv (fn [idx]
            (let [high (nth highs idx)
                  low (nth lows idx)
                  prev-close (if (zero? idx) (nth closes idx) (nth closes (dec idx)))]
              (max (- high low)
                   (js/Math.abs (- high prev-close))
                   (js/Math.abs (- low prev-close)))))
          (range size))))

(defn- plus-minus-di-values
  [data period]
  (let [high-values (field-values data :high)
        low-values (field-values data :low)
        close-values (field-values data :close)
        size (count high-values)
        plus-dm (mapv (fn [idx]
                        (if (zero? idx)
                          0
                          (let [up-move (- (nth high-values idx) (nth high-values (dec idx)))
                                down-move (- (nth low-values (dec idx)) (nth low-values idx))]
                            (if (and (> up-move down-move) (> up-move 0))
                              up-move
                              0))))
                      (range size))
        minus-dm (mapv (fn [idx]
                         (if (zero? idx)
                           0
                           (let [up-move (- (nth high-values idx) (nth high-values (dec idx)))
                                 down-move (- (nth low-values (dec idx)) (nth low-values idx))]
                             (if (and (> down-move up-move) (> down-move 0))
                               down-move
                               0))))
                       (range size))
        tr-values (true-range-values high-values low-values close-values)
        atr-values (rma-values tr-values period)
        plus-rma (rma-values plus-dm period)
        minus-rma (rma-values minus-dm period)
        plus-di (mapv (fn [idx]
                        (let [atr (nth atr-values idx)
                              value (nth plus-rma idx)]
                          (when (and (finite-number? atr)
                                     (finite-number? value)
                                     (pos? atr))
                            (* 100 (/ value atr)))))
                      (range size))
        minus-di (mapv (fn [idx]
                         (let [atr (nth atr-values idx)
                               value (nth minus-rma idx)]
                           (when (and (finite-number? atr)
                                      (finite-number? value)
                                      (pos? atr))
                             (* 100 (/ value atr)))))
                       (range size))]
    {:plus-di plus-di
     :minus-di minus-di}))

(defn calculate-aroon
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        highs (field-values data :high)
        lows (field-values data :low)
        size (count data)
        [up-values down-values]
        (loop [idx 0
               up-result []
               down-result []]
          (if (= idx size)
            [up-result down-result]
            (if (< idx period)
              (recur (inc idx)
                     (conj up-result nil)
                     (conj down-result nil))
              (let [high-window (window-for-index highs idx period)
                    low-window (window-for-index lows idx period)
                    max-high (apply max high-window)
                    min-low (apply min low-window)
                    high-index (or (last-index-of high-window max-high) 0)
                    low-index (or (last-index-of low-window min-low) 0)
                    bars-since-high (- (dec period) high-index)
                    bars-since-low (- (dec period) low-index)
                    up (* 100 (/ (- period bars-since-high) period))
                    down (* 100 (/ (- period bars-since-low) period))]
                (recur (inc idx)
                       (conj up-result up)
                       (conj down-result down))))))]
    (result/indicator-result :aroon
                             :separate
                             [(result/line-series :aroon-up up-values)
                              (result/line-series :aroon-down down-values)])))

(defn calculate-adx
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        smoothing (parse-period (:smoothing params) 14 2 200)
        highs (field-values data :high)
        lows (field-values data :low)
        closes (field-values data :close)
        size (count data)
        plus-dm (mapv (fn [idx]
                        (if (zero? idx)
                          0
                          (let [up-move (- (nth highs idx) (nth highs (dec idx)))
                                down-move (- (nth lows (dec idx)) (nth lows idx))]
                            (if (and (> up-move down-move) (> up-move 0))
                              up-move
                              0))))
                      (range size))
        minus-dm (mapv (fn [idx]
                         (if (zero? idx)
                           0
                           (let [up-move (- (nth highs idx) (nth highs (dec idx)))
                                 down-move (- (nth lows (dec idx)) (nth lows idx))]
                             (if (and (> down-move up-move) (> down-move 0))
                               down-move
                               0))))
                       (range size))
        tr-values (true-range-values highs lows closes)
        atr-values (rma-values tr-values period)
        plus-rma (rma-values plus-dm period)
        minus-rma (rma-values minus-dm period)
        plus-di (mapv (fn [idx]
                        (let [atr (nth atr-values idx)
                              value (nth plus-rma idx)]
                          (when (and (finite-number? atr)
                                     (finite-number? value)
                                     (pos? atr))
                            (* 100 (/ value atr)))))
                      (range size))
        minus-di (mapv (fn [idx]
                         (let [atr (nth atr-values idx)
                               value (nth minus-rma idx)]
                           (when (and (finite-number? atr)
                                      (finite-number? value)
                                      (pos? atr))
                             (* 100 (/ value atr)))))
                       (range size))
        dx-values (mapv (fn [idx]
                          (let [plus (nth plus-di idx)
                                minus (nth minus-di idx)
                                total (+ (or plus 0) (or minus 0))]
                            (when (and (finite-number? plus)
                                       (finite-number? minus)
                                       (pos? total))
                              (* 100 (/ (js/Math.abs (- plus minus)) total)))))
                        (range size))
        adx-raw (rma-values (mapv #(or % 0) dx-values) smoothing)
        warmup (+ period smoothing)
        adx-values (mapv (fn [idx value]
                           (when (and (>= idx warmup)
                                      (finite-number? value))
                             value))
                         (range size)
                         adx-raw)]
    (result/indicator-result :adx
                             :separate
                             [(result/line-series :adx adx-values)])))

(defn calculate-directional-movement
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        {:keys [plus-di minus-di]} (plus-minus-di-values data period)]
    (result/indicator-result :directional-movement
                             :separate
                             [(result/line-series :plus-di plus-di)
                              (result/line-series :minus-di minus-di)])))

(defn calculate-supertrend
  [data params]
  (let [period (parse-period (:period params) 10 2 200)
        multiplier (parse-number (:multiplier params) 3)
        high-values (field-values data :high)
        low-values (field-values data :low)
        close-values (field-values data :close)
        tr-values (true-range-values high-values low-values close-values)
        atr-values (rma-values tr-values period)
        size (count close-values)
        hl2 (mapv (fn [idx]
                    (/ (+ (nth high-values idx)
                          (nth low-values idx))
                       2))
                  (range size))
        basic-upper (mapv (fn [idx]
                            (let [mid (nth hl2 idx)
                                  atr (nth atr-values idx)]
                              (when (and (finite-number? mid)
                                         (finite-number? atr))
                                (+ mid (* multiplier atr)))))
                          (range size))
        basic-lower (mapv (fn [idx]
                            (let [mid (nth hl2 idx)
                                  atr (nth atr-values idx)]
                              (when (and (finite-number? mid)
                                         (finite-number? atr))
                                (- mid (* multiplier atr)))))
                          (range size))
        [final-upper final-lower supertrend trend-up]
        (loop [idx 0
               prev-final-upper nil
               prev-final-lower nil
               prev-supertrend nil
               upper-result []
               lower-result []
               supertrend-result []
               trend-result []]
          (if (= idx size)
            [upper-result lower-result supertrend-result trend-result]
            (let [current-upper (nth basic-upper idx)
                  current-lower (nth basic-lower idx)
                  prev-close (when (pos? idx) (nth close-values (dec idx)))
                  final-up (if (or (nil? prev-final-upper)
                                   (nil? current-upper)
                                   (nil? prev-close)
                                   (< current-upper prev-final-upper)
                                   (> prev-close prev-final-upper))
                             current-upper
                             prev-final-upper)
                  final-low (if (or (nil? prev-final-lower)
                                    (nil? current-lower)
                                    (nil? prev-close)
                                    (> current-lower prev-final-lower)
                                    (< prev-close prev-final-lower))
                              current-lower
                              prev-final-lower)
                  next-supertrend (cond
                                    (nil? prev-supertrend) final-up
                                    (= prev-supertrend prev-final-upper)
                                    (if (<= (nth close-values idx) final-up)
                                      final-up
                                      final-low)
                                    :else
                                    (if (>= (nth close-values idx) final-low)
                                      final-low
                                      final-up))
                  next-trend-up (when (finite-number? next-supertrend)
                                  (<= next-supertrend (nth close-values idx)))]
              (recur (inc idx)
                     final-up
                     final-low
                     next-supertrend
                     (conj upper-result final-up)
                     (conj lower-result final-low)
                     (conj supertrend-result next-supertrend)
                     (conj trend-result next-trend-up)))))
        up-line (mapv (fn [idx]
                        (when (true? (nth trend-up idx))
                          (nth supertrend idx)))
                      (range size))
        down-line (mapv (fn [idx]
                          (when (false? (nth trend-up idx))
                            (nth supertrend idx)))
                        (range size))]
    (result/indicator-result :supertrend
                             :overlay
                             [(result/line-series :up up-line)
                              (result/line-series :down down-line)])))

(defn calculate-vortex-indicator
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        result (math-engine/vortex (field-values data :high)
                                    (field-values data :low)
                                    (field-values data :close)
                                    {:period period})
        plus-values (normalize-values (:plus result))
        minus-values (normalize-values (:minus result))]
    (result/indicator-result :vortex-indicator
                             :separate
                             [(result/line-series :plus plus-values)
                              (result/line-series :minus minus-values)])))
