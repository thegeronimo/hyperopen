(ns hyperopen.views.trading-chart.utils.indicators
  (:require [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.oscillators :as domain-oscillators]
            [hyperopen.domain.trading.indicators.trend :as domain-trend]
            [hyperopen.domain.trading.indicators.volatility :as domain-volatility]
            [hyperopen.views.trading-chart.utils.indicator-view-adapter :as view-adapter]
            [hyperopen.views.trading-chart.utils.indicators-wave2 :as wave2]
            [hyperopen.views.trading-chart.utils.indicators-wave3 :as wave3]))

(def ^:private seconds-per-week (* 7 24 60 60))

(def ^:private indicator-definitions
  [{:id :accumulation-distribution
    :name "Accumulation/Distribution"
    :short-name "A/D"
    :description "Cumulative money flow volume line"
    :supports-period? false
    :default-config {}}
   {:id :accumulative-swing-index
    :name "Accumulative Swing Index"
    :short-name "ASI"
    :description "Wilder swing index accumulated over time"
    :supports-period? false
    :default-config {}}
   {:id :average-price
    :name "Average Price"
   :short-name "OHLC4"
   :description "(Open + High + Low + Close) / 4"
   :supports-period? false
    :default-config {}}])

(def ^:private finite-number? imath/finite-number?)
(def ^:private parse-period imath/parse-period)
(def ^:private times imath/times)
(def ^:private field-values imath/field-values)
(def ^:private mean imath/mean)

(defn- window-for-index
  [values idx period]
  (imath/window-for-index values idx period :lagged))

(defn- rolling-apply
  [values period f]
  (imath/rolling-apply values period f :lagged))

(defn- sma-values
  [values period]
  (imath/sma-values values period :lagged))

(defn- stddev-values
  [values period]
  (imath/stddev-values values period :lagged))

(defn- rma-values
  [values period]
  (imath/rma-values values period :lagged))

(defn- point
  [time value]
  (if (finite-number? value)
    {:time time :value value}
    {:time time}))

(defn- points-from-values
  [time-values indicator-values]
  (mapv point time-values indicator-values))

(defn- histogram-point
  [time value]
  (if (finite-number? value)
    {:time time
     :value value
     :color (if (neg? value) "#ef4444" "#10b981")}
    {:time time}))

(defn- histogram-points
  [time-values indicator-values]
  (mapv histogram-point time-values indicator-values))

(defn- line-series
  [id name color time-values indicator-values]
  {:id id
   :name name
   :series-type :line
   :color color
   :line-width 2
   :data (points-from-values time-values indicator-values)})

(defn- histogram-series
  [id name time-values indicator-values]
  {:id id
   :name name
   :series-type :histogram
   :data (histogram-points time-values indicator-values)})

(defn- indicator-result
  [indicator-type pane series]
  {:type indicator-type
   :pane pane
   :series series})

(defn calculate-sma
  "Calculate Simple Moving Average for given data and period"
  [data period]
  (let [time-values (times data)
        values (domain-trend/calculate-sma-values data period)]
    (view-adapter/points-from-values time-values values)))

(defn- calculate-52-week-high-low
  [data params]
  (let [weeks (parse-period (:period params) 52 1 260)
        lookback-seconds (* weeks seconds-per-week)
        time-values (times data)
        highs (field-values data :high)
        lows (field-values data :low)
        size (count data)
        [high-line low-line]
        (loop [idx 0
               start-idx 0
               high-result []
               low-result []]
          (if (= idx size)
            [high-result low-result]
            (let [cutoff (- (nth time-values idx) lookback-seconds)
                  next-start (loop [cursor start-idx]
                               (if (and (< cursor idx)
                                        (< (nth time-values cursor) cutoff))
                                 (recur (inc cursor))
                                 cursor))
                  high-window (subvec highs next-start (inc idx))
                  low-window (subvec lows next-start (inc idx))]
              (recur (inc idx)
                     next-start
                     (conj high-result (apply max high-window))
                     (conj low-result (apply min low-window))))))]
    (indicator-result :week-52-high-low
                      :overlay
                      [(line-series :high "52W High" "#10b981" time-values high-line)
                       (line-series :low "52W Low" "#ef4444" time-values low-line)])))

(defn- calculate-accumulation-distribution
  [data _params]
  (let [time-values (times data)
        highs (field-values data :high)
        lows (field-values data :low)
        closes (field-values data :close)
        volumes (field-values data :volume)
        size (count data)
        values (loop [idx 0
                      running 0
                      result []]
                 (if (= idx size)
                   result
                   (let [high (nth highs idx)
                         low (nth lows idx)
                         close (nth closes idx)
                         volume (nth volumes idx)
                         range-value (- high low)
                         multiplier (if (zero? range-value)
                                      0
                                      (/ (- (- close low)
                                            (- high close))
                                         range-value))
                         next-value (+ running (* multiplier volume))]
                     (recur (inc idx) next-value (conj result next-value)))))]
    (indicator-result :accumulation-distribution
                      :separate
                      [(line-series :adl "A/D" "#22d3ee" time-values values)])))

(defn- calculate-accumulative-swing-index
  [data _params]
  (let [time-values (times data)
        opens (field-values data :open)
        highs (field-values data :high)
        lows (field-values data :low)
        closes (field-values data :close)
        size (count data)
        values (loop [idx 0
                      running 0
                      result []]
                 (if (= idx size)
                   result
                   (if (zero? idx)
                     (recur (inc idx) 0 (conj result 0))
                     (let [open (nth opens idx)
                           high (nth highs idx)
                           low (nth lows idx)
                           close (nth closes idx)
                           prev-open (nth opens (dec idx))
                           prev-close (nth closes (dec idx))
                           distance-a (js/Math.abs (- high prev-close))
                           distance-b (js/Math.abs (- low prev-close))
                           distance-c (js/Math.abs (- high low))
                           distance-d (js/Math.abs (- prev-close prev-open))
                           range-value (cond
                                         (and (> distance-a distance-b)
                                              (> distance-a distance-c))
                                         (+ (- distance-a (/ distance-b 2))
                                            (/ distance-d 4))

                                         (and (> distance-b distance-a)
                                              (> distance-b distance-c))
                                         (+ (- distance-b (/ distance-a 2))
                                            (/ distance-d 4))

                                         :else
                                         (+ distance-c (/ distance-d 4)))
                           k (max distance-a distance-b)
                           t (max distance-a distance-b distance-c)
                           swing-index (if (or (zero? range-value) (zero? t))
                                         0
                                         (* 50
                                            (/ (+ (- close prev-close)
                                                  (/ (- close open) 2)
                                                  (/ (- prev-close prev-open) 4))
                                               range-value)
                                            (/ k t)))
                           next-value (+ running swing-index)]
                       (recur (inc idx) next-value (conj result next-value))))))]
    (indicator-result :accumulative-swing-index
                      :separate
                      [(line-series :asi "ASI" "#f97316" time-values values)])))

(defn- calculate-advance-decline
  [data _params]
  (let [time-values (times data)
        closes (field-values data :close)
        size (count data)
        values (loop [idx 0
                      running 0
                      result []]
                 (if (= idx size)
                   result
                   (if (zero? idx)
                     (recur (inc idx) 0 (conj result 0))
                     (let [change (cond
                                    (> (nth closes idx) (nth closes (dec idx))) 1
                                    (< (nth closes idx) (nth closes (dec idx))) -1
                                    :else 0)
                           next-value (+ running change)]
                       (recur (inc idx) next-value (conj result next-value))))))]
    (indicator-result :advance-decline
                      :separate
                      [(line-series :ad-bars "A/D" "#06b6d4" time-values values)])))

(defn- alma-weights
  [period offset sigma]
  (let [m (* offset (dec period))
        s (/ period sigma)]
    (mapv (fn [idx]
            (js/Math.exp
             (/ (- (* (- idx m) (- idx m)))
                (* 2 s s))))
          (range period))))

(defn- calculate-alma
  [data params]
  (let [period (parse-period (:period params) 9 2 200)
        offset (or (:offset params) 0.85)
        sigma (or (:sigma params) 6)
        weights (alma-weights period offset sigma)
        denominator (reduce + 0 weights)
        time-values (times data)
        closes (field-values data :close)
        size (count data)
        values (mapv (fn [idx]
                       (when-let [window (window-for-index closes idx period)]
                         (when (every? finite-number? window)
                           (/ (reduce + 0 (map * window weights))
                              denominator))))
                     (range size))]
    (indicator-result :alma
                      :overlay
                      [(line-series :alma "ALMA" "#f59e0b" time-values values)])))

(defn- last-index-of
  [values target]
  (loop [idx (dec (count values))]
    (cond
      (< idx 0) nil
      (= (nth values idx) target) idx
      :else (recur (dec idx)))))

(defn- calculate-aroon
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        time-values (times data)
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
    (indicator-result :aroon
                      :separate
                      [(line-series :aroon-up "Aroon Up" "#22c55e" time-values up-values)
                       (line-series :aroon-down "Aroon Down" "#ef4444" time-values down-values)])))

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

(defn- calculate-adx
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        smoothing (parse-period (:smoothing params) 14 2 200)
        time-values (times data)
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
    (indicator-result :adx
                      :separate
                      [(line-series :adx "ADX" "#a855f7" time-values adx-values)])))

(defn- calculate-average-price
  [data _params]
  (let [time-values (times data)
        opens (field-values data :open)
        highs (field-values data :high)
        lows (field-values data :low)
        closes (field-values data :close)
        size (count data)
        values (mapv (fn [idx]
                       (/ (+ (nth opens idx)
                             (nth highs idx)
                             (nth lows idx)
                             (nth closes idx))
                          4))
                     (range size))]
    (indicator-result :average-price
                      :overlay
                      [(line-series :ohlc4 "OHLC4" "#a3e635" time-values values)])))

(defn- calculate-atr
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        time-values (times data)
        highs (field-values data :high)
        lows (field-values data :low)
        closes (field-values data :close)
        tr-values (true-range-values highs lows closes)
        values (rma-values tr-values period)]
    (indicator-result :atr
                      :separate
                      [(line-series :atr "ATR" "#14b8a6" time-values values)])))

(defn- calculate-awesome-oscillator
  [data _params]
  (let [time-values (times data)
        highs (field-values data :high)
        lows (field-values data :low)
        size (count data)
        median-values (mapv (fn [idx]
                              (/ (+ (nth highs idx) (nth lows idx)) 2))
                            (range size))
        fast-values (sma-values median-values 5)
        slow-values (sma-values median-values 34)
        values (mapv (fn [idx]
                       (let [fast (nth fast-values idx)
                             slow (nth slow-values idx)]
                         (when (and (finite-number? fast)
                                    (finite-number? slow))
                           (- fast slow))))
                     (range size))]
    (indicator-result :awesome-oscillator
                      :separate
                      [(histogram-series :ao "AO" time-values values)])))

(defn- calculate-accelerator-oscillator
  [data _params]
  (let [time-values (times data)
        highs (field-values data :high)
        lows (field-values data :low)
        size (count data)
        median-values (mapv (fn [idx]
                              (/ (+ (nth highs idx) (nth lows idx)) 2))
                            (range size))
        fast-values (sma-values median-values 5)
        slow-values (sma-values median-values 34)
        ao-values (mapv (fn [idx]
                          (let [fast (nth fast-values idx)
                                slow (nth slow-values idx)]
                            (when (and (finite-number? fast)
                                       (finite-number? slow))
                              (- fast slow))))
                        (range size))
        ao-signal (rolling-apply ao-values 5 mean)
        values (mapv (fn [idx]
                       (let [ao (nth ao-values idx)
                             signal (nth ao-signal idx)]
                         (when (and (finite-number? ao)
                                    (finite-number? signal))
                           (- ao signal))))
                     (range size))]
    (indicator-result :accelerator-oscillator
                      :separate
                      [(histogram-series :ac "AC" time-values values)])))

(defn- calculate-balance-of-power
  [data _params]
  (let [time-values (times data)
        opens (field-values data :open)
        highs (field-values data :high)
        lows (field-values data :low)
        closes (field-values data :close)
        size (count data)
        values (mapv (fn [idx]
                       (let [high (nth highs idx)
                             low (nth lows idx)
                             open (nth opens idx)
                             close (nth closes idx)
                             denominator (- high low)]
                         (if (zero? denominator)
                           0
                           (/ (- close open) denominator))))
                     (range size))]
    (indicator-result :balance-of-power
                      :separate
                      [(line-series :bop "BOP" "#22c55e" time-values values)])))

(defn- calculate-bollinger-bands
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        multiplier (or (:multiplier params) 2)
        time-values (times data)
        closes (field-values data :close)
        basis-values (sma-values closes period)
        std-values (stddev-values closes period)
        upper-values (mapv (fn [idx]
                             (let [basis (nth basis-values idx)
                                   stdev (nth std-values idx)]
                               (when (and (finite-number? basis)
                                          (finite-number? stdev))
                                 (+ basis (* multiplier stdev)))))
                           (range (count closes)))
        lower-values (mapv (fn [idx]
                             (let [basis (nth basis-values idx)
                                   stdev (nth std-values idx)]
                               (when (and (finite-number? basis)
                                          (finite-number? stdev))
                                 (- basis (* multiplier stdev)))))
                           (range (count closes)))]
    (indicator-result :bollinger-bands
                      :overlay
                      [(line-series :upper "BB Upper" "#22c55e" time-values upper-values)
                       (line-series :basis "BB Basis" "#f59e0b" time-values basis-values)
                       (line-series :lower "BB Lower" "#ef4444" time-values lower-values)])))

(defn get-available-indicators
  "Return list of available indicators"
  []
  (vec (concat indicator-definitions
               (domain-trend/get-trend-indicators)
               (domain-oscillators/get-oscillator-indicators)
               (domain-volatility/get-volatility-indicators)
               (wave2/get-wave2-indicators)
               (wave3/get-wave3-indicators))))

(def ^:private indicator-calculators
  {:accumulation-distribution calculate-accumulation-distribution
   :accumulative-swing-index calculate-accumulative-swing-index
   :average-price calculate-average-price})

(defn calculate-indicator
  "Calculate indicator based on type and parameters"
  [indicator-type data params]
  (let [config (or params {})
        calculator (get indicator-calculators indicator-type)
        domain-result (or (domain-trend/calculate-trend-indicator indicator-type data config)
                          (domain-oscillators/calculate-oscillator-indicator indicator-type data config)
                          (domain-volatility/calculate-volatility-indicator indicator-type data config))]
    (or (when calculator
          (calculator data config))
        (when domain-result
          (view-adapter/project-domain-indicator data domain-result))
        (wave2/calculate-wave2-indicator indicator-type data config)
        (wave3/calculate-wave3-indicator indicator-type data config))))
