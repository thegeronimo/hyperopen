(ns hyperopen.domain.trading.indicators.structure
  (:require [hyperopen.domain.trading.indicators.contracts :as contracts]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private structure-indicator-definitions
  [{:id :pivot-points-standard
    :name "Pivot Points Standard"
    :short-name "Pivots"
    :description "PP, R1-R3 and S1-S3 from previous window"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
    :migrated-from :wave3}
   {:id :rank-correlation-index
    :name "Rank Correlation Index"
    :short-name "RCI"
    :description "Spearman rank correlation oscillator"
    :supports-period? true
    :default-period 9
    :min-period 3
    :max-period 400
    :default-config {:period 9}
    :migrated-from :wave3}
   {:id :zig-zag
    :name "Zig Zag"
    :short-name "ZigZag"
    :description "Swing-line connecting pivots that exceed threshold"
    :supports-period? false
    :default-config {:threshold-percent 5}
    :migrated-from :wave3}
   {:id :williams-fractal
    :name "Williams Fractal"
    :short-name "Fractal"
    :description "Five-bar high/low fractal markers"
    :supports-period? false
    :default-config {}
    :migrated-from :wave3}])

(defn get-structure-indicators
  []
  structure-indicator-definitions)

(def ^:private finite-number? imath/finite-number?)
(def ^:private parse-period imath/parse-period)
(def ^:private parse-number imath/parse-number)
(def ^:private times imath/times)
(def ^:private field-values imath/field-values)

(defn- tie-aware-ranks
  [values]
  (let [indexed (map-indexed vector values)
        sorted (vec (sort-by second indexed))
        size (count sorted)]
    (loop [idx 0
           ranks (vec (repeat size nil))]
      (if (= idx size)
        ranks
        (let [value (second (nth sorted idx))
              same-run-end (loop [j idx]
                             (if (and (< (inc j) size)
                                      (= value (second (nth sorted (inc j)))))
                               (recur (inc j))
                               j))
              avg-rank (/ (+ (inc idx) (inc same-run-end)) 2)
              updated (reduce (fn [acc k]
                                (let [orig-idx (first (nth sorted k))]
                                  (assoc acc orig-idx avg-rank)))
                              ranks
                              (range idx (inc same-run-end)))]
          (recur (inc same-run-end) updated))))))

(defn- calculate-pivot-points-standard
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        highs-v (field-values data :high)
        lows-v (field-values data :low)
        closes-v (field-values data :close)
        size (count data)
        pivot-data (mapv (fn [idx]
                           (when (>= idx period)
                             (let [from (- idx period)
                                   to idx
                                   window-high (subvec highs-v from to)
                                   window-low (subvec lows-v from to)
                                   window-close (subvec closes-v from to)
                                   h (apply max window-high)
                                   l (apply min window-low)
                                   c (last window-close)
                                   pp (/ (+ h l c) 3)
                                   r1 (- (* 2 pp) l)
                                   s1 (- (* 2 pp) h)
                                   r2 (+ pp (- h l))
                                   s2 (- pp (- h l))
                                   r3 (+ h (* 2 (- pp l)))
                                   s3 (- l (* 2 (- h pp)))]
                               {:pp pp :r1 r1 :s1 s1 :r2 r2 :s2 s2 :r3 r3 :s3 s3})))
                         (range size))
        pick (fn [k]
               (mapv #(get % k) pivot-data))]
    (result/indicator-result :pivot-points-standard
                             :overlay
                             [(result/line-series :pp (pick :pp))
                              (result/line-series :r1 (pick :r1))
                              (result/line-series :s1 (pick :s1))
                              (result/line-series :r2 (pick :r2))
                              (result/line-series :s2 (pick :s2))
                              (result/line-series :r3 (pick :r3))
                              (result/line-series :s3 (pick :s3))])))

(defn- calculate-rank-correlation-index
  [data params]
  (let [period (parse-period (:period params) 9 3 400)
        close-values (field-values data :close)
        size (count data)
        values (mapv (fn [idx]
                       (when-let [window (imath/window-for-index close-values idx period :aligned)]
                         (let [price-ranks (tie-aware-ranks window)
                               time-ranks (vec (range 1 (inc period)))
                               d-squared (reduce + 0
                                                 (map (fn [time-rank price-rank]
                                                        (let [d (- time-rank price-rank)]
                                                          (* d d)))
                                                      time-ranks price-ranks))]
                           (* 100
                              (- 1 (/ (* 6 d-squared)
                                      (* period (- (* period period) 1))))))))
                     (range size))]
    (result/indicator-result :rank-correlation-index
                             :separate
                             [(result/line-series :rci values)])))

(defn- calculate-williams-fractal
  [data _params]
  (let [high-values (field-values data :high)
        low-values (field-values data :low)
        size (count data)
        time-values (times data)
        markers (->> (range size)
                     (reduce (fn [acc idx]
                               (if (or (< idx 2) (>= idx (- size 2)))
                                 acc
                                 (let [time (nth time-values idx)
                                       high-window (subvec high-values (- idx 2) (+ idx 3))
                                       low-window (subvec low-values (- idx 2) (+ idx 3))
                                       center-high (nth high-values idx)
                                       center-low (nth low-values idx)
                                       bearish? (and (finite-number? center-high)
                                                     (= center-high (apply max high-window))
                                                     (= 1 (count (filter #(= % center-high) high-window))))
                                       bullish? (and (finite-number? center-low)
                                                     (= center-low (apply min low-window))
                                                     (= 1 (count (filter #(= % center-low) low-window))))
                                       with-bearish (if bearish?
                                                      (conj acc {:id (str "fractal-high-" time)
                                                                 :time time
                                                                 :position "aboveBar"
                                                                 :shape "arrowDown"
                                                                 :color "#22c55e"
                                                                 :text "▲"
                                                                 :size 0})
                                                      acc)]
                                   (if bullish?
                                     (conj with-bearish {:id (str "fractal-low-" time)
                                                         :time time
                                                         :position "belowBar"
                                                         :shape "arrowUp"
                                                         :color "#ef4444"
                                                         :text "▼"
                                                         :size 0})
                                     with-bearish))))
                             [])
                     vec)]
    (result/indicator-result :williams-fractal
                             :overlay
                             []
                             markers)))

(defn- zigzag-pivots
  [close-values threshold]
  (let [size (count close-values)]
    (if (zero? size)
      []
      (loop [idx 1
             trend nil
             last-pivot-idx 0
             last-pivot-price (nth close-values 0)
             candidate-idx 0
             candidate-price (nth close-values 0)
             pivots [{:idx 0 :price (nth close-values 0)}]]
        (if (= idx size)
          (let [last-candidate {:idx candidate-idx :price candidate-price}]
            (if (= (:idx (last pivots)) (:idx last-candidate))
              pivots
              (conj pivots last-candidate)))
          (let [price (nth close-values idx)]
            (cond
              (nil? trend)
              (cond
                (>= price (* last-pivot-price (+ 1 threshold)))
                (recur (inc idx) :up last-pivot-idx last-pivot-price idx price pivots)

                (<= price (* last-pivot-price (- 1 threshold)))
                (recur (inc idx) :down last-pivot-idx last-pivot-price idx price pivots)

                (or (> price candidate-price)
                    (< price candidate-price))
                (recur (inc idx)
                       nil
                       last-pivot-idx
                       last-pivot-price
                       idx
                       price
                       pivots)

                :else
                (recur (inc idx) trend last-pivot-idx last-pivot-price candidate-idx candidate-price pivots))

              (= trend :up)
              (cond
                (> price candidate-price)
                (recur (inc idx) :up last-pivot-idx last-pivot-price idx price pivots)

                (<= price (* candidate-price (- 1 threshold)))
                (let [pivot {:idx candidate-idx :price candidate-price}]
                  (recur (inc idx)
                         :down
                         (:idx pivot)
                         (:price pivot)
                         idx
                         price
                         (if (= (:idx (last pivots)) (:idx pivot))
                           pivots
                           (conj pivots pivot))))

                :else
                (recur (inc idx) :up last-pivot-idx last-pivot-price candidate-idx candidate-price pivots))

              :else
              (cond
                (< price candidate-price)
                (recur (inc idx) :down last-pivot-idx last-pivot-price idx price pivots)

                (>= price (* candidate-price (+ 1 threshold)))
                (let [pivot {:idx candidate-idx :price candidate-price}]
                  (recur (inc idx)
                         :up
                         (:idx pivot)
                         (:price pivot)
                         idx
                         price
                         (if (= (:idx (last pivots)) (:idx pivot))
                           pivots
                           (conj pivots pivot))))

                :else
                (recur (inc idx) :down last-pivot-idx last-pivot-price candidate-idx candidate-price pivots)))))))))

(defn- interpolate-zigzag
  [size pivots]
  (let [initial (vec (repeat size nil))
        with-segments (reduce (fn [acc [a b]]
                                (let [i1 (:idx a)
                                      p1 (:price a)
                                      i2 (:idx b)
                                      p2 (:price b)
                                      length (max 1 (- i2 i1))]
                                  (reduce (fn [inner idx]
                                            (let [ratio (/ (- idx i1) length)
                                                  value (+ p1 (* ratio (- p2 p1)))]
                                              (assoc inner idx value)))
                                          acc
                                          (range i1 (inc i2)))))
                              initial
                              (partition 2 1 pivots))]
    with-segments))

(defn- calculate-zig-zag
  [data params]
  (let [threshold-percent (parse-number (:threshold-percent params) 5)
        threshold (max 0.001 (/ threshold-percent 100))
        close-values (field-values data :close)
        pivots (zigzag-pivots close-values threshold)
        values (interpolate-zigzag (count data) pivots)]
    (result/indicator-result :zig-zag
                             :overlay
                             [(result/line-series :zig-zag values)])))

(def ^:private structure-calculators
  {:pivot-points-standard calculate-pivot-points-standard
   :rank-correlation-index calculate-rank-correlation-index
   :williams-fractal calculate-williams-fractal
   :zig-zag calculate-zig-zag})

(defn calculate-structure-indicator
  [indicator-type data params]
  (let [config (or params {})
        calculator (get structure-calculators indicator-type)]
    (when (and calculator
               (contracts/valid-indicator-input? data config))
      (contracts/enforce-indicator-result indicator-type
                                          (count data)
                                          (calculator data config)))))
