(ns hyperopen.domain.trading.indicators.trend.moving-averages
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

(defn- sma-aligned-values
  [values period]
  (imath/sma-values values period :aligned))

(defn- rma-aligned-values
  [values period]
  (imath/rma-values values period :aligned))

(defn- ema-values
  [values period]
  (imath/ema-values values period))

(defn- shift-right
  [values shift]
  (let [size (count values)
        shifted (concat (repeat shift nil) values)]
    (vec (take size shifted))))

(defn- make-hamming-weights
  [period]
  (if (= period 1)
    [1]
    (let [weights (mapv (fn [idx]
                          (- 0.54
                             (* 0.46
                                (js/Math.cos (/ (* 2 js/Math.PI idx)
                                                (dec period))))))
                        (range period))
          total (reduce + 0 weights)]
      (mapv #(/ % total) weights))))

(defn- weighted-ma
  [values weights]
  (let [period (count weights)]
    (imath/rolling-apply values
                         period
                         (fn [window]
                           (reduce + 0 (map * window weights)))
                         :aligned)))

(defn- aligned-window-for-index
  [values idx period]
  (imath/window-for-index values idx period :aligned))

(defn- wma-values
  [values period]
  (let [weights (range 1 (inc period))
        divisor (reduce + 0 weights)]
    (mapv (fn [idx]
            (when-let [window (aligned-window-for-index values idx period)]
              (when (every? finite-number? window)
                (/ (reduce + 0 (map * window weights)) divisor))))
          (range (count values)))))

(defn- hull-values
  [close-values period]
  (let [half-period (max 1 (int (js/Math.floor (/ period 2))))
        sqrt-period (max 1 (int (js/Math.floor (js/Math.sqrt period))))
        wma-half (wma-values close-values half-period)
        wma-full (wma-values close-values period)
        diff-values (mapv (fn [idx]
                            (let [a (nth wma-half idx)
                                  b (nth wma-full idx)]
                              (when (and (finite-number? a) (finite-number? b))
                                (- (* 2 a) b))))
                          (range (count close-values)))]
    (wma-values diff-values sqrt-period)))

(defn- alma-weights
  [period offset sigma]
  (let [m (* offset (dec period))
        s (/ period sigma)]
    (mapv (fn [idx]
            (js/Math.exp
             (/ (- (* (- idx m) (- idx m)))
                (* 2 s s))))
          (range period))))

(defn calculate-alma
  [data params]
  (let [period (parse-period (:period params) 9 2 200)
        offset (or (:offset params) 0.85)
        sigma (or (:sigma params) 6)
        weights (alma-weights period offset sigma)
        denominator (reduce + 0 weights)
        closes (field-values data :close)
        size (count data)
        values (mapv (fn [idx]
                       (when-let [window (window-for-index closes idx period)]
                         (when (every? finite-number? window)
                           (/ (reduce + 0 (map * window weights))
                              denominator))))
                     (range size))]
    (result/indicator-result :alma
                             :overlay
                             [(result/line-series :alma values)])))

(defn calculate-moving-average-multiple
  [data params]
  (let [periods (or (:periods params) [5 10 20 50])
        close-values (field-values data :close)
        series (mapv (fn [period]
                       (let [length (parse-period period period 1 400)
                             values (sma-aligned-values close-values length)]
                         (result/line-series (keyword (str "ma-" length)) values)))
                     periods)]
    (result/indicator-result :moving-average-multiple
                             :overlay
                             series)))

(defn calculate-double-ema
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (math-engine/dema (field-values data :close)
                                   {:period period}))]
    (result/indicator-result :double-ema
                             :overlay
                             [(result/line-series :dema values)])))

(defn calculate-hull-moving-average
  [data params]
  (let [period (parse-period (:period params) 21 2 400)
        values (hull-values (field-values data :close) period)]
    (result/indicator-result :hull-moving-average
                             :overlay
                             [(result/line-series :hma values)])))

(defn calculate-moving-average-double
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (math-engine/dema (field-values data :close)
                                   {:period period}))]
    (result/indicator-result :moving-average-double
                             :overlay
                             [(result/line-series :double values)])))

(defn calculate-moving-average-exponential
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (math-engine/ema (field-values data :close)
                                  {:period period}))]
    (result/indicator-result :moving-average-exponential
                             :overlay
                             [(result/line-series :ema values)])))

(defn calculate-moving-average-triple
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (math-engine/tema (field-values data :close)
                                   {:period period}))]
    (result/indicator-result :moving-average-triple
                             :overlay
                             [(result/line-series :triple values)])))

(defn calculate-moving-average-weighted
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (wma-values (field-values data :close) period)]
    (result/indicator-result :moving-average-weighted
                             :overlay
                             [(result/line-series :wma values)])))

(defn calculate-smoothed-moving-average
  [data params]
  (let [period (parse-period (:period params) 14 2 400)
        values (normalize-values
                (math-engine/rma (field-values data :close)
                                  {:period period}))]
    (result/indicator-result :smoothed-moving-average
                             :overlay
                             [(result/line-series :smma values)])))

(defn calculate-triple-ema
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (math-engine/tema (field-values data :close)
                                   {:period period}))]
    (result/indicator-result :triple-ema
                             :overlay
                             [(result/line-series :tema values)])))

(defn calculate-ema-cross
  [data params]
  (let [fast (parse-period (:fast params) 12 1 200)
        slow (parse-period (:slow params) 26 2 400)
        close-values (field-values data :close)
        fast-line (normalize-values (math-engine/ema close-values {:period fast}))
        slow-line (normalize-values (math-engine/ema close-values {:period slow}))]
    (result/indicator-result :ema-cross
                             :overlay
                             [(result/line-series :fast fast-line)
                              (result/line-series :slow slow-line)])))

(defn calculate-ma-cross
  [data params]
  (let [fast (parse-period (:fast params) 9 1 200)
        slow (parse-period (:slow params) 21 2 400)
        close-values (field-values data :close)
        fast-line (sma-aligned-values close-values fast)
        slow-line (sma-aligned-values close-values slow)]
    (result/indicator-result :ma-cross
                             :overlay
                             [(result/line-series :fast fast-line)
                              (result/line-series :slow slow-line)])))

(defn calculate-ma-with-ema-cross
  [data params]
  (let [ma-period (parse-period (:ma-period params) 20 2 400)
        ema-period (parse-period (:ema-period params) 50 2 400)
        close-values (field-values data :close)
        ma-line (sma-aligned-values close-values ma-period)
        ema-line (normalize-values (math-engine/ema close-values {:period ema-period}))]
    (result/indicator-result :ma-with-ema-cross
                             :overlay
                             [(result/line-series :ma ma-line)
                              (result/line-series :ema ema-line)])))

(defn calculate-guppy-multiple-moving-average
  [data _params]
  (let [close-values (field-values data :close)
        short-periods [3 5 8 10 12 15]
        long-periods [30 35 40 45 50 60]
        short-series (mapv (fn [period]
                             (result/line-series (keyword (str "ema-short-" period))
                                                 (ema-values close-values period)))
                           short-periods)
        long-series (mapv (fn [period]
                            (result/line-series (keyword (str "ema-long-" period))
                                                (ema-values close-values period)))
                          long-periods)]
    (result/indicator-result :guppy-multiple-moving-average
                             :overlay
                             (vec (concat short-series long-series)))))

(defn calculate-mcginley-dynamic
  [data params]
  (let [period (parse-period (:period params) 14 2 400)
        close-values (field-values data :close)
        size (count data)
        values (loop [idx 0
                      prev nil
                      out []]
                 (if (= idx size)
                   out
                   (let [close (nth close-values idx)
                         current (if (nil? prev)
                                   close
                                   (let [ratio (if (zero? prev)
                                                 1
                                                 (/ close prev))
                                         denom (* period (js/Math.pow ratio 4))]
                                     (+ prev (/ (- close prev)
                                                (if (zero? denom) period denom)))))]
                     (recur (inc idx)
                            current
                            (conj out current)))))]
    (result/indicator-result :mcginley-dynamic
                             :overlay
                             [(result/line-series :mcginley values)])))

(defn calculate-moving-average-adaptive
  [data params]
  (let [period (parse-period (:period params) 10 2 400)
        fast (parse-period (:fast params) 2 2 100)
        slow (parse-period (:slow params) 30 2 200)
        close-values (field-values data :close)
        size (count data)
        fast-sc (/ 2 (inc fast))
        slow-sc (/ 2 (inc slow))
        values (loop [idx 0
                      prev nil
                      out []]
                 (if (= idx size)
                   out
                   (let [close (nth close-values idx)
                         current (if (or (nil? prev) (< idx period))
                                   close
                                   (let [change (js/Math.abs (- close (nth close-values (- idx period))))
                                         volatility (reduce + 0
                                                            (map (fn [j]
                                                                   (js/Math.abs (- (nth close-values j)
                                                                                   (nth close-values (dec j)))))
                                                                 (range (- idx period -1) (inc idx))))
                                         er (if (zero? volatility)
                                              0
                                              (/ change volatility))
                                         sc (js/Math.pow (+ (* er (- fast-sc slow-sc)) slow-sc) 2)]
                                     (+ prev (* sc (- close prev)))))]
                     (recur (inc idx)
                            current
                            (conj out current)))))]
    (result/indicator-result :moving-average-adaptive
                             :overlay
                             [(result/line-series :kama values)])))

(defn calculate-moving-average-hamming
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        weights (make-hamming-weights period)
        values (weighted-ma (field-values data :close) weights)]
    (result/indicator-result :moving-average-hamming
                             :overlay
                             [(result/line-series :hamming-ma values)])))

(defn calculate-williams-alligator
  [data params]
  (let [jaw-period (parse-period (:jaw-period params) 13 2 200)
        jaw-shift (parse-period (:jaw-shift params) 8 0 50)
        teeth-period (parse-period (:teeth-period params) 8 2 200)
        teeth-shift (parse-period (:teeth-shift params) 5 0 50)
        lips-period (parse-period (:lips-period params) 5 2 200)
        lips-shift (parse-period (:lips-shift params) 3 0 50)
        median (mapv (fn [high low]
                       (/ (+ high low) 2))
                     (field-values data :high)
                     (field-values data :low))
        jaw (shift-right (rma-aligned-values median jaw-period) jaw-shift)
        teeth (shift-right (rma-aligned-values median teeth-period) teeth-shift)
        lips (shift-right (rma-aligned-values median lips-period) lips-shift)]
    (result/indicator-result :williams-alligator
                             :overlay
                             [(result/line-series :jaw jaw)
                              (result/line-series :teeth teeth)
                              (result/line-series :lips lips)])))
