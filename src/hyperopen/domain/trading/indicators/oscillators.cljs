(ns hyperopen.domain.trading.indicators.oscillators
  (:require [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private oscillator-indicator-definitions
  [{:id :accelerator-oscillator
    :name "Accelerator Oscillator"
    :short-name "AC"
    :description "Awesome Oscillator minus its 5-period simple moving average"
    :supports-period? false
    :default-config {}}
   {:id :advance-decline
    :name "Advance/Decline"
    :short-name "A/D (Bars)"
    :description "Single-instrument proxy using cumulative up/down bar count"
    :supports-period? false
    :default-config {}}
   {:id :awesome-oscillator
    :name "Awesome Oscillator"
    :short-name "AO"
    :description "5-period SMA minus 34-period SMA of median price"
    :supports-period? false
    :default-config {}}
   {:id :balance-of-power
    :name "Balance of Power"
    :short-name "BOP"
    :description "(Close - Open) / (High - Low)"
    :supports-period? false
    :default-config {}}
   {:id :rate-of-change
    :name "Rate Of Change"
    :short-name "ROC"
    :description "Percent change over n periods"
    :supports-period? true
    :default-period 9
    :min-period 1
    :max-period 400
    :default-config {:period 9}}
   {:id :relative-strength-index
    :name "Relative Strength Index"
    :short-name "RSI"
    :description "Momentum oscillator of gains vs losses"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
    :migrated-from :wave2}
   {:id :correlation-coefficient
    :name "Correlation Coefficient"
    :short-name "Corr"
    :description "Rolling Pearson correlation of price and time"
    :supports-period? true
    :default-period 20
    :min-period 3
    :max-period 400
    :default-config {:period 20}
    :migrated-from :wave3}
   {:id :true-strength-index
    :name "True Strength Index"
    :short-name "TSI"
    :description "Double-smoothed momentum oscillator"
    :supports-period? false
    :default-config {:short 13
                     :long 25}
    :migrated-from :wave3}
   {:id :trend-strength-index
    :name "Trend Strength Index"
    :short-name "TrendSI"
    :description "Absolute TSI with smoothing"
    :supports-period? false
    :default-config {:short 13
                     :long 25
                     :signal 13}
    :migrated-from :wave3}
   {:id :smi-ergodic
    :name "SMI Ergodic Indicator/Oscillator"
    :short-name "SMI Ergodic"
    :description "TSI-derived indicator, signal, and oscillator"
    :supports-period? false
    :default-config {:short 13
                     :long 25
                     :signal 13}
    :migrated-from :wave3}
   {:id :ultimate-oscillator
    :name "Ultimate Oscillator"
    :short-name "UO"
    :description "Weighted multi-timeframe buying pressure oscillator"
    :supports-period? false
    :default-config {:short 7
                     :medium 14
                     :long 28}
    :migrated-from :wave3}
   {:id :connors-rsi
    :name "Connors RSI"
    :short-name "CRSI"
    :description "Average of short RSI, streak RSI, and percent-rank"
    :supports-period? false
    :default-config {:rsi-period 3
                     :streak-period 2
                     :rank-period 100}
    :migrated-from :wave3}
   {:id :chop-zone
    :name "Chop Zone"
    :short-name "CZ"
    :description "Trend-angle zone derived from EMA slope"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
    :migrated-from :wave3}
   {:id :klinger-oscillator
    :name "Klinger Oscillator"
    :short-name "KVO"
    :description "Volume-force EMA oscillator"
    :supports-period? false
    :default-config {:fast 34
                     :slow 55
                     :signal 13}
    :migrated-from :wave3}
   {:id :know-sure-thing
    :name "Know Sure Thing"
    :short-name "KST"
    :description "Weighted sum of smoothed rate-of-change"
    :supports-period? false
    :default-config {:roc1 10
                     :roc2 15
                     :roc3 20
                     :roc4 30
                     :sma1 10
                     :sma2 10
                     :sma3 10
                     :sma4 15
                     :signal 9}
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
   {:id :relative-vigor-index
    :name "Relative Vigor Index"
    :short-name "RVI"
    :description "Smoothed ratio of close-open to high-low"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10}
    :migrated-from :wave3}
   {:id :relative-volatility-index
    :name "Relative Volatility Index"
    :short-name "RVI Vol"
    :description "RSI-style oscillator over volatility"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
    :migrated-from :wave3}
   {:id :spread
    :name "Spread"
    :short-name "Spread"
    :description "Single-stream proxy: close minus close n bars ago"
    :supports-period? true
    :default-period 1
    :min-period 1
    :max-period 400
    :default-config {:period 1}
    :migrated-from :wave3}
   {:id :ratio
    :name "Ratio"
    :short-name "Ratio"
    :description "Single-stream proxy: close divided by close n bars ago"
    :supports-period? true
    :default-period 1
    :min-period 1
    :max-period 400
    :default-config {:period 1}
    :migrated-from :wave3}
   {:id :majority-rule
    :name "Majority Rule"
    :short-name "Majority"
    :description "Percent of bars closing above SMA"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 400
    :default-config {:period 14}
    :migrated-from :wave3}
   {:id :fisher-transform
    :name "Fisher Transform"
    :short-name "Fisher"
    :description "Fisher transform of normalized median price"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10}
    :migrated-from :wave3}
   {:id :coppock-curve
    :name "Coppock Curve"
    :short-name "COPP"
    :description "WMA of summed long and short ROC"
    :supports-period? false
    :default-config {:long-roc 14
                     :short-roc 11
                     :wma-period 10}
    :migrated-from :wave3}
   {:id :chaikin-volatility
    :name "Chaikin Volatility"
    :short-name "CHV"
    :description "Rate-of-change of EMA high-low range"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10
                     :roc-period 10}
    :migrated-from :wave3}
   {:id :chande-kroll-stop
    :name "Chande Kroll Stop"
    :short-name "CKS"
    :description "ATR-based long and short stop lines"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10
                     :atr-period 10
                     :multiplier 1.0}
    :migrated-from :wave3}
   {:id :guppy-multiple-moving-average
    :name "Guppy Multiple Moving Average"
    :short-name "GMMA"
    :description "Short and long EMA ribbon"
    :supports-period? false
    :default-config {}
    :migrated-from :wave3}
   {:id :mcginley-dynamic
    :name "McGinley Dynamic"
    :short-name "MGD"
    :description "Adaptive moving average with speed correction"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 400
    :default-config {:period 14}
    :migrated-from :wave3}
   {:id :moving-average-adaptive
    :name "Moving Average Adaptive"
    :short-name "KAMA"
    :description "Kaufman Adaptive Moving Average"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 400
    :default-config {:period 10
                     :fast 2
                     :slow 30}
    :migrated-from :wave3}
   {:id :moving-average-hamming
    :name "Moving Average Hamming"
    :short-name "HAMMA"
    :description "Moving average with Hamming window weights"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
    :migrated-from :wave3}
   {:id :pivot-points-standard
    :name "Pivot Points Standard"
    :short-name "Pivots"
    :description "PP, R1-R3 and S1-S3 from previous window"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
    :migrated-from :wave3}
   {:id :williams-alligator
    :name "Williams Alligator"
    :short-name "Alligator"
    :description "Three smoothed moving averages with offsets"
    :supports-period? false
    :default-config {:jaw-period 13
                     :jaw-shift 8
                     :teeth-period 8
                     :teeth-shift 5
                     :lips-period 5
                     :lips-shift 3}
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
    :default-config {}}])

(defn get-oscillator-indicators
  []
  oscillator-indicator-definitions)

(def ^:private finite-number? imath/finite-number?)
(def ^:private parse-period imath/parse-period)
(def ^:private field-values imath/field-values)
(def ^:private mean imath/mean)

(defn- sma-values
  [values period]
  (imath/sma-values values period :lagged))

(defn- rolling-apply
  [values period f]
  (imath/rolling-apply values period f :lagged))

(defn- rma-values
  [values period]
  (imath/rma-values values period :lagged))

(defn- roc-percent-values
  [values period]
  (let [size (count values)]
    (mapv (fn [idx]
            (if (< idx period)
              nil
              (let [current (nth values idx)
                    base (nth values (- idx period))]
                (when (and (finite-number? current)
                           (finite-number? base)
                           (not= base 0))
                  (* 100 (/ (- current base) base))))))
          (range size))))

(defn- rsi-values
  [values period]
  (let [size (count values)
        diffs (mapv (fn [idx]
                      (if (pos? idx)
                        (- (nth values idx) (nth values (dec idx)))
                        nil))
                    (range size))
        gains (mapv (fn [d]
                      (when (finite-number? d)
                        (max d 0)))
                    diffs)
        losses (mapv (fn [d]
                       (when (finite-number? d)
                         (max (- d) 0)))
                     diffs)
        avg-gains (rma-values gains period)
        avg-losses (rma-values losses period)]
    (mapv (fn [g l]
            (when (and (finite-number? g)
                       (finite-number? l))
              (if (zero? l)
                100
                (- 100 (/ 100 (+ 1 (/ g l)))))))
          avg-gains avg-losses)))

(defn- pearson-correlation
  [xs ys]
  (when (and (= (count xs) (count ys))
             (seq xs)
             (every? finite-number? xs)
             (every? finite-number? ys))
    (let [mx (imath/mean xs)
          my (imath/mean ys)
          cov (reduce + 0 (map (fn [x y]
                                 (* (- x mx) (- y my)))
                               xs ys))
          sx (reduce + 0 (map (fn [x]
                                (let [d (- x mx)]
                                  (* d d)))
                              xs))
          sy (reduce + 0 (map (fn [y]
                                (let [d (- y my)]
                                  (* d d)))
                              ys))
          denom (js/Math.sqrt (* sx sy))]
      (when (and (finite-number? denom) (> denom 0))
        (/ cov denom)))))

(defn- rolling-correlation-with-time
  [values period]
  (let [time-axis (vec (range 1 (inc period)))
        size (count values)]
    (mapv (fn [idx]
            (when-let [window (imath/window-for-index values idx period :aligned)]
              (pearson-correlation window time-axis)))
          (range size))))

(defn- tsi-core
  [close-values short-period long-period]
  (let [size (count close-values)
        mtm (mapv (fn [idx]
                    (if (pos? idx)
                      (- (nth close-values idx) (nth close-values (dec idx)))
                      nil))
                  (range size))
        abs-mtm (mapv (fn [v]
                        (when (finite-number? v)
                          (js/Math.abs v)))
                      mtm)
        ema1 (imath/ema-values mtm short-period)
        ema2 (imath/ema-values ema1 long-period)
        abs-ema1 (imath/ema-values abs-mtm short-period)
        abs-ema2 (imath/ema-values abs-ema1 long-period)]
    (mapv (fn [a b]
            (when (and (finite-number? a)
                       (finite-number? b)
                       (not= b 0))
              (* 100 (/ a b))))
          ema2 abs-ema2)))

(defn- calculate-awesome-oscillator
  [data _params]
  (let [highs (field-values data :high)
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
    (result/indicator-result :awesome-oscillator
                             :separate
                             [(result/histogram-series :ao values)])))

(defn- calculate-accelerator-oscillator
  [data _params]
  (let [highs (field-values data :high)
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
    (result/indicator-result :accelerator-oscillator
                             :separate
                             [(result/histogram-series :ac values)])))

(defn- calculate-balance-of-power
  [data _params]
  (let [opens (field-values data :open)
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
    (result/indicator-result :balance-of-power
                             :separate
                             [(result/line-series :bop values)])))

(defn- calculate-rate-of-change
  [data params]
  (let [period (parse-period (:period params) 9 1 400)
        close-values (field-values data :close)
        values (roc-percent-values close-values period)]
    (result/indicator-result :rate-of-change
                             :separate
                             [(result/line-series :roc values)])))

(defn- calculate-relative-strength-index
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        values (rsi-values (field-values data :close) period)]
    (result/indicator-result :relative-strength-index
                             :separate
                             [(result/line-series :rsi values)])))

(defn- calculate-correlation-coefficient
  [data params]
  (let [period (parse-period (:period params) 20 3 400)
        values (rolling-correlation-with-time (field-values data :close) period)]
    (result/indicator-result :correlation-coefficient
                             :separate
                             [(result/line-series :correlation values)])))

(defn- calculate-true-strength-index
  [data params]
  (let [short-period (parse-period (:short params) 13 2 200)
        long-period (parse-period (:long params) 25 2 200)
        tsi (tsi-core (field-values data :close) short-period long-period)]
    (result/indicator-result :true-strength-index
                             :separate
                             [(result/line-series :tsi tsi)])))

(defn- calculate-trend-strength-index
  [data params]
  (let [short-period (parse-period (:short params) 13 2 200)
        long-period (parse-period (:long params) 25 2 200)
        signal-period (parse-period (:signal params) 13 2 200)
        tsi (tsi-core (field-values data :close) short-period long-period)
        trend (mapv (fn [value]
                      (when (finite-number? value)
                        (js/Math.abs value)))
                    tsi)
        signal (imath/ema-values trend signal-period)]
    (result/indicator-result :trend-strength-index
                             :separate
                             [(result/line-series :trend-si trend)
                              (result/line-series :signal signal)])))

(defn- calculate-advance-decline
  [data _params]
  (let [closes (field-values data :close)
        size (count data)
        values (loop [idx 0
                      running 0
                      out []]
                 (if (= idx size)
                   out
                   (if (zero? idx)
                     (recur (inc idx) 0 (conj out 0))
                     (let [change (cond
                                    (> (nth closes idx) (nth closes (dec idx))) 1
                                    (< (nth closes idx) (nth closes (dec idx))) -1
                                    :else 0)
                           next-value (+ running change)]
                       (recur (inc idx) next-value (conj out next-value))))))]
    (result/indicator-result :advance-decline
                             :separate
                             [(result/line-series :ad-bars values)])))

(def ^:private oscillator-calculators
  {:accelerator-oscillator calculate-accelerator-oscillator
   :advance-decline calculate-advance-decline
   :awesome-oscillator calculate-awesome-oscillator
   :balance-of-power calculate-balance-of-power
   :rate-of-change calculate-rate-of-change
   :relative-strength-index calculate-relative-strength-index
   :correlation-coefficient calculate-correlation-coefficient
   :true-strength-index calculate-true-strength-index
   :trend-strength-index calculate-trend-strength-index})

(defn calculate-oscillator-indicator
  [indicator-type data params]
  (let [config (or params {})
        calculator (get oscillator-calculators indicator-type)]
    (when calculator
      (calculator data config))))
