(ns hyperopen.domain.trading.indicators.oscillators
  (:require [hyperopen.domain.trading.indicators.contracts :as contracts]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]
            ["indicatorts" :refer [apo cci macd mi rsi stoch trix willr]]))

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
   {:id :momentum
    :name "Momentum"
    :short-name "Momentum"
    :description "Price change over n periods"
    :supports-period? true
    :default-period 10
    :min-period 1
    :max-period 400
    :default-config {:period 10}
    :migrated-from :wave2}
   {:id :chande-momentum-oscillator
    :name "Chande Momentum Oscillator"
    :short-name "CMO"
    :description "Momentum oscillator using rolling gains and losses"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
    :migrated-from :wave2}
   {:id :detrended-price-oscillator
    :name "Detrended Price Oscillator"
    :short-name "DPO"
    :description "Price minus displaced moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20}
    :migrated-from :wave2}
   {:id :price-oscillator
    :name "Price Oscillator"
    :short-name "APO"
    :description "Absolute difference between fast and slow EMA"
    :supports-period? false
    :default-config {:fast 12
                     :slow 26}
    :migrated-from :wave2}
   {:id :stochastic
    :name "Stochastic"
    :short-name "Stoch"
    :description "%K and %D stochastic oscillator"
    :supports-period? false
    :default-config {:kPeriod 14
                     :dPeriod 3}
    :migrated-from :wave2}
   {:id :stochastic-rsi
    :name "Stochastic RSI"
    :short-name "Stoch RSI"
    :description "Stochastic oscillator applied to RSI"
    :supports-period? false
    :default-config {:rsiPeriod 14
                     :stochPeriod 14
                     :kSmoothing 3
                     :dSmoothing 3}
    :migrated-from :wave2}
   {:id :trix
    :name "TRIX"
    :short-name "TRIX"
    :description "Triple-smoothed EMA rate of change"
    :supports-period? true
    :default-period 15
    :min-period 2
    :max-period 400
    :default-config {:period 15}
    :migrated-from :wave2}
   {:id :williams-r
    :name "Williams %R"
    :short-name "%R"
    :description "Overbought and oversold momentum oscillator"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
    :migrated-from :wave2}
   {:id :choppiness-index
    :name "Choppiness Index"
    :short-name "CHOP"
    :description "Log-scaled range efficiency oscillator"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
    :migrated-from :wave2}
   {:id :commodity-channel-index
    :name "Commodity Channel Index"
    :short-name "CCI"
    :description "Typical-price deviation from moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20}
    :migrated-from :wave2}
   {:id :macd
    :name "MACD"
    :short-name "MACD"
    :description "MACD line, signal line, and histogram"
    :supports-period? false
    :default-config {:fast 12
                     :slow 26
                     :signal 9}
    :migrated-from :wave2}
   {:id :mass-index
    :name "Mass Index"
    :short-name "MI"
    :description "Range expansion/contraction trend reversal indicator"
    :supports-period? false
    :default-config {:emaPeriod 9
                     :miPeriod 25}
    :migrated-from :wave2}
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
   {:id :correlation-log
    :name "Correlation - Log"
    :short-name "Corr Log"
    :description "Rolling Pearson correlation of log price and time"
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
   ])

(defn get-oscillator-indicators
  []
  oscillator-indicator-definitions)

(def ^:private finite-number? imath/finite-number?)
(def ^:private clamp imath/clamp)
(def ^:private parse-period imath/parse-period)
(def ^:private parse-number imath/parse-number)
(def ^:private field-values imath/field-values)
(def ^:private mean imath/mean)
(def ^:private normalize-values imath/normalize-values)

(defn- sma-values
  [values period]
  (imath/sma-values values period :lagged))

(defn- rolling-apply
  [values period f]
  (imath/rolling-apply values period f :lagged))

(defn- rma-values
  [values period]
  (imath/rma-values values period :lagged))

(defn- sma-aligned-values
  [values period]
  (imath/sma-values values period :aligned))

(defn- rolling-sum-aligned
  [values period]
  (imath/rolling-sum values period :aligned))

(defn- rolling-max-aligned
  [values period]
  (imath/rolling-max values period :aligned))

(defn- rolling-min-aligned
  [values period]
  (imath/rolling-min values period :aligned))

(defn- rma-aligned-values
  [values period]
  (imath/rma-values values period :aligned))

(defn- stddev-aligned-values
  [values period]
  (imath/stddev-values values period :aligned))

(defn- wma-aligned-values
  [values period]
  (let [weights (vec (range 1 (inc period)))
        weight-sum (reduce + 0 weights)]
    (imath/rolling-apply values
                         period
                         (fn [window]
                           (/ (reduce + 0 (map * window weights))
                              weight-sum))
                         :aligned)))

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

(defn- rsi-aligned-values
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
        avg-gains (rma-aligned-values gains period)
        avg-losses (rma-aligned-values losses period)]
    (mapv (fn [g l]
            (when (and (finite-number? g)
                       (finite-number? l))
              (if (zero? l)
                100
                (- 100 (/ 100 (+ 1 (/ g l)))))))
          avg-gains avg-losses)))

(defn- streak-values
  [close-values]
  (let [size (count close-values)]
    (loop [idx 0
           prev-close nil
           prev-streak 0
           out []]
      (if (= idx size)
        out
        (let [close (nth close-values idx)
              streak (if (nil? prev-close)
                       0
                       (cond
                         (> close prev-close) (if (pos? prev-streak) (inc prev-streak) 1)
                         (< close prev-close) (if (neg? prev-streak) (dec prev-streak) -1)
                         :else 0))]
          (recur (inc idx)
                 close
                 streak
                 (conj out streak)))))))

(defn- percent-rank-values
  [values period]
  (let [size (count values)]
    (mapv (fn [idx]
            (when-let [window (imath/window-for-index values idx period :aligned)]
              (let [current (last window)]
                (when (finite-number? current)
                  (let [comparable (filter finite-number? window)
                        below (count (filter #(< % current) comparable))
                        denom (max 1 (count comparable))]
                    (* 100 (/ below denom)))))))
          (range size))))

(defn- true-range-values
  [data]
  (let [high-values (field-values data :high)
        low-values (field-values data :low)
        close-values (field-values data :close)
        size (count data)]
    (mapv (fn [idx]
            (let [high (nth high-values idx)
                  low (nth low-values idx)
                  prev-close (when (pos? idx)
                               (nth close-values (dec idx)))
                  range-1 (- high low)
                  range-2 (if (finite-number? prev-close)
                            (js/Math.abs (- high prev-close))
                            range-1)
                  range-3 (if (finite-number? prev-close)
                            (js/Math.abs (- low prev-close))
                            range-1)]
              (max range-1 range-2 range-3)))
          (range size))))

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

(defn- calculate-momentum
  [data params]
  (let [period (parse-period (:period params) 10 1 400)
        close-values (field-values data :close)
        values (mapv (fn [idx]
                       (if (< idx period)
                         nil
                         (- (nth close-values idx)
                            (nth close-values (- idx period)))))
                     (range (count close-values)))]
    (result/indicator-result :momentum
                             :separate
                             [(result/line-series :momentum values)])))

(defn- calculate-chande-momentum-oscillator
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        close-values (field-values data :close)
        diffs (mapv (fn [idx]
                      (if (zero? idx)
                        0
                        (- (nth close-values idx) (nth close-values (dec idx)))))
                    (range (count close-values)))
        gains (mapv (fn [value] (max value 0)) diffs)
        losses (mapv (fn [value] (max (- value) 0)) diffs)
        sum-gains (rolling-sum-aligned gains period)
        sum-losses (rolling-sum-aligned losses period)
        values (mapv (fn [g l]
                       (let [total (+ (or g 0) (or l 0))]
                         (when (and (finite-number? g)
                                    (finite-number? l)
                                    (pos? total))
                           (* 100 (/ (- g l) total)))))
                     sum-gains sum-losses)]
    (result/indicator-result :chande-momentum-oscillator
                             :separate
                             [(result/line-series :cmo values)])))

(defn- calculate-detrended-price-oscillator
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        close-values (field-values data :close)
        sma-line (sma-aligned-values close-values period)
        shift (+ (int (js/Math.floor (/ period 2))) 1)
        size (count close-values)
        values (mapv (fn [idx]
                       (let [shifted-idx (- idx shift)]
                         (when (>= shifted-idx 0)
                           (let [price (nth close-values shifted-idx)
                                 avg (nth sma-line shifted-idx)]
                             (when (and (finite-number? price)
                                        (finite-number? avg))
                               (- price avg))))))
                     (range size))]
    (result/indicator-result :detrended-price-oscillator
                             :separate
                             [(result/line-series :dpo values)])))

(defn- calculate-price-oscillator
  [data params]
  (let [fast (parse-period (:fast params) 12 1 200)
        slow (parse-period (:slow params) 26 2 400)
        values (normalize-values
                (apo (into-array (field-values data :close))
                     #js {:fast fast :slow slow}))]
    (result/indicator-result :price-oscillator
                             :separate
                             [(result/line-series :apo values)])))

(defn- calculate-stochastic
  [data params]
  (let [k-period (parse-period (:kPeriod params) 14 1 200)
        d-period (parse-period (:dPeriod params) 3 1 200)
        result (js->clj
                (stoch (into-array (field-values data :high))
                       (into-array (field-values data :low))
                       (into-array (field-values data :close))
                       #js {:kPeriod k-period :dPeriod d-period})
                :keywordize-keys true)
        k-values (normalize-values (:k result))
        d-values (normalize-values (:d result))]
    (result/indicator-result :stochastic
                             :separate
                             [(result/line-series :k k-values)
                              (result/line-series :d d-values)])))

(defn- calculate-stochastic-rsi
  [data params]
  (let [rsi-period (parse-period (:rsiPeriod params) 14 2 200)
        stoch-period (parse-period (:stochPeriod params) 14 2 200)
        k-smoothing (parse-period (:kSmoothing params) 3 1 50)
        d-smoothing (parse-period (:dSmoothing params) 3 1 50)
        rsi-series (normalize-values
                    (rsi (into-array (field-values data :close))
                         #js {:period rsi-period}))
        min-rsi (rolling-min-aligned rsi-series stoch-period)
        max-rsi (rolling-max-aligned rsi-series stoch-period)
        raw-k (mapv (fn [idx]
                      (let [r (nth rsi-series idx)
                            mn (nth min-rsi idx)
                            mx (nth max-rsi idx)
                            range-value (- (or mx 0) (or mn 0))]
                        (when (and (finite-number? r)
                                   (finite-number? mn)
                                   (finite-number? mx)
                                   (pos? range-value))
                          (* 100 (/ (- r mn) range-value)))))
                    (range (count rsi-series)))
        k-values (sma-aligned-values raw-k k-smoothing)
        d-values (sma-aligned-values k-values d-smoothing)]
    (result/indicator-result :stochastic-rsi
                             :separate
                             [(result/line-series :k k-values)
                              (result/line-series :d d-values)])))

(defn- calculate-trix
  [data params]
  (let [period (parse-period (:period params) 15 2 400)
        values (normalize-values
                (trix (into-array (field-values data :close))
                      #js {:period period}))]
    (result/indicator-result :trix
                             :separate
                             [(result/line-series :trix values)])))

(defn- calculate-williams-r
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        values (normalize-values
                (willr (into-array (field-values data :high))
                       (into-array (field-values data :low))
                       (into-array (field-values data :close))
                       #js {:period period}))]
    (result/indicator-result :williams-r
                             :separate
                             [(result/line-series :williams-r values)])))

(defn- calculate-choppiness-index
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        high-values (field-values data :high)
        low-values (field-values data :low)
        tr-values (true-range-values data)
        tr-sum (rolling-sum-aligned tr-values period)
        high-max (rolling-max-aligned high-values period)
        low-min (rolling-min-aligned low-values period)
        denom-log (js/Math.log10 period)
        values (mapv (fn [sum-tr hh ll]
                       (let [range-value (- (or hh 0) (or ll 0))]
                         (when (and (finite-number? sum-tr)
                                    (finite-number? hh)
                                    (finite-number? ll)
                                    (pos? sum-tr)
                                    (pos? range-value)
                                    (not (zero? denom-log)))
                           (* 100 (/ (js/Math.log10 (/ sum-tr range-value))
                                     denom-log)))))
                     tr-sum high-max low-min)]
    (result/indicator-result :choppiness-index
                             :separate
                             [(result/line-series :chop values)])))

(defn- calculate-commodity-channel-index
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        values (normalize-values
                (cci (into-array (field-values data :high))
                     (into-array (field-values data :low))
                     (into-array (field-values data :close))
                     #js {:period period}))]
    (result/indicator-result :commodity-channel-index
                             :separate
                             [(result/line-series :cci values)])))

(defn- calculate-macd
  [data params]
  (let [fast (parse-period (:fast params) 12 1 200)
        slow (parse-period (:slow params) 26 2 400)
        signal (parse-period (:signal params) 9 1 200)
        result (js->clj
                (macd (into-array (field-values data :close))
                      #js {:fast fast
                           :slow slow
                           :signal signal})
                :keywordize-keys true)
        macd-line (normalize-values (:macdLine result))
        signal-line (normalize-values (:signalLine result))
        histogram (mapv (fn [m s]
                          (when (and (finite-number? m)
                                     (finite-number? s))
                            (- m s)))
                        macd-line signal-line)]
    (result/indicator-result :macd
                             :separate
                             [(result/histogram-series :hist histogram)
                              (result/line-series :macd macd-line)
                              (result/line-series :signal signal-line)])))

(defn- calculate-mass-index
  [data params]
  (let [ema-period (parse-period (:emaPeriod params) 9 1 200)
        mi-period (parse-period (:miPeriod params) 25 2 400)
        values (normalize-values
                (mi (into-array (field-values data :high))
                    (into-array (field-values data :low))
                    #js {:emaPeriod ema-period
                         :miPeriod mi-period}))]
    (result/indicator-result :mass-index
                             :separate
                             [(result/line-series :mi values)])))

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

(defn- calculate-correlation-log
  [data params]
  (let [period (parse-period (:period params) 20 3 400)
        close-values (field-values data :close)
        logged (mapv (fn [price]
                       (when (and (finite-number? price)
                                  (pos? price))
                         (js/Math.log price)))
                     close-values)
        values (rolling-correlation-with-time logged period)]
    (result/indicator-result :correlation-log
                             :separate
                             [(result/line-series :correlation-log values)])))

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

(defn- calculate-chaikin-volatility
  [data params]
  (let [period (parse-period (:period params) 10 2 200)
        roc-period (parse-period (:roc-period params) period 1 200)
        ranges (mapv - (field-values data :high) (field-values data :low))
        ema-range (imath/ema-values ranges period)
        size (count data)
        values (mapv (fn [idx]
                       (if (< idx roc-period)
                         nil
                         (let [current (nth ema-range idx)
                               base (nth ema-range (- idx roc-period))]
                           (when (and (finite-number? current)
                                      (finite-number? base)
                                      (not= base 0))
                             (* 100 (/ (- current base) base))))))
                     (range size))]
    (result/indicator-result :chaikin-volatility
                             :separate
                             [(result/line-series :chv values)])))

(defn- calculate-chande-kroll-stop
  [data params]
  (let [period (parse-period (:period params) 10 2 200)
        atr-period (parse-period (:atr-period params) period 2 200)
        multiplier (parse-number (:multiplier params) 1.0)
        high-stop-base (rolling-max-aligned (field-values data :high) period)
        low-stop-base (rolling-min-aligned (field-values data :low) period)
        atr-values (rma-aligned-values (true-range-values data) atr-period)
        long-stop (mapv (fn [high atr]
                          (when (and (finite-number? high)
                                     (finite-number? atr))
                            (- high (* multiplier atr))))
                        high-stop-base atr-values)
        short-stop (mapv (fn [low atr]
                           (when (and (finite-number? low)
                                      (finite-number? atr))
                             (+ low (* multiplier atr))))
                         low-stop-base atr-values)]
    (result/indicator-result :chande-kroll-stop
                             :overlay
                             [(result/line-series :long-stop long-stop)
                              (result/line-series :short-stop short-stop)])))

(defn- calculate-chop-zone
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        close-values (field-values data :close)
        ema-close (imath/ema-values close-values period)
        atr-values (rma-aligned-values (true-range-values data) period)
        size (count data)
        zones (mapv (fn [idx]
                      (if (zero? idx)
                        nil
                        (let [ema-now (nth ema-close idx)
                              ema-prev (nth ema-close (dec idx))
                              atr-now (nth atr-values idx)]
                          (when (and (finite-number? ema-now)
                                     (finite-number? ema-prev)
                                     (finite-number? atr-now)
                                     (pos? atr-now))
                            (let [strength (* 100 (/ (- ema-now ema-prev) atr-now))]
                              (cond
                                (>= strength 35) 4
                                (>= strength 15) 3
                                (>= strength 5) 2
                                (>= strength -5) 1
                                (>= strength -15) 0
                                (>= strength -35) -1
                                :else -2))))))
                    (range size))]
    (result/indicator-result :chop-zone
                             :separate
                             [(result/histogram-series :chop-zone zones)])))

(defn- calculate-connors-rsi
  [data params]
  (let [rsi-period (parse-period (:rsi-period params) 3 2 50)
        streak-period (parse-period (:streak-period params) 2 2 50)
        rank-period (parse-period (:rank-period params) 100 2 400)
        close-values (field-values data :close)
        streaks (streak-values close-values)
        close-rsi (rsi-aligned-values close-values rsi-period)
        streak-rsi (rsi-aligned-values (mapv #(+ 100 %) streaks) streak-period)
        roc1 (roc-percent-values close-values 1)
        rank (percent-rank-values roc1 rank-period)
        values (mapv (fn [a b c]
                       (when (and (finite-number? a)
                                  (finite-number? b)
                                  (finite-number? c))
                         (/ (+ a b c) 3)))
                     close-rsi streak-rsi rank)]
    (result/indicator-result :connors-rsi
                             :separate
                             [(result/line-series :connors-rsi values)])))

(defn- calculate-klinger-oscillator
  [data params]
  (let [fast (parse-period (:fast params) 34 2 400)
        slow (parse-period (:slow params) 55 2 400)
        signal-period (parse-period (:signal params) 13 2 400)
        highs-v (field-values data :high)
        lows-v (field-values data :low)
        closes-v (field-values data :close)
        volumes-v (field-values data :volume)
        size (count data)
        typical (mapv (fn [h l c] (/ (+ h l c) 3)) highs-v lows-v closes-v)
        vf (mapv (fn [idx]
                   (if (zero? idx)
                     nil
                     (let [tp (nth typical idx)
                           prev-tp (nth typical (dec idx))
                           direction (cond
                                       (> tp prev-tp) 1
                                       (< tp prev-tp) -1
                                       :else 0)
                           dm (- (nth highs-v idx) (nth lows-v idx))
                           vol (nth volumes-v idx)]
                       (when (and (finite-number? dm)
                                  (finite-number? vol))
                         (* direction vol dm)))))
                 (range size))
        fast-ema (imath/ema-values vf fast)
        slow-ema (imath/ema-values vf slow)
        kvo (mapv (fn [f s]
                    (when (and (finite-number? f)
                               (finite-number? s))
                      (- f s)))
                  fast-ema slow-ema)
        signal (imath/ema-values kvo signal-period)
        hist (mapv (fn [k s]
                     (when (and (finite-number? k)
                                (finite-number? s))
                       (- k s)))
                   kvo signal)]
    (result/indicator-result :klinger-oscillator
                             :separate
                             [(result/histogram-series :hist hist)
                              (result/line-series :kvo kvo)
                              (result/line-series :signal signal)])))

(defn- calculate-know-sure-thing
  [data params]
  (let [roc1 (parse-period (:roc1 params) 10 1 400)
        roc2 (parse-period (:roc2 params) 15 1 400)
        roc3 (parse-period (:roc3 params) 20 1 400)
        roc4 (parse-period (:roc4 params) 30 1 400)
        sma1 (parse-period (:sma1 params) 10 2 400)
        sma2 (parse-period (:sma2 params) 10 2 400)
        sma3 (parse-period (:sma3 params) 10 2 400)
        sma4 (parse-period (:sma4 params) 15 2 400)
        signal-period (parse-period (:signal params) 9 2 400)
        close-values (field-values data :close)
        rcma1 (sma-aligned-values (roc-percent-values close-values roc1) sma1)
        rcma2 (sma-aligned-values (roc-percent-values close-values roc2) sma2)
        rcma3 (sma-aligned-values (roc-percent-values close-values roc3) sma3)
        rcma4 (sma-aligned-values (roc-percent-values close-values roc4) sma4)
        kst (mapv (fn [a b c d]
                    (when (every? finite-number? [a b c d])
                      (+ a (* 2 b) (* 3 c) (* 4 d))))
                  rcma1 rcma2 rcma3 rcma4)
        signal (sma-aligned-values kst signal-period)]
    (result/indicator-result :know-sure-thing
                             :separate
                             [(result/line-series :kst kst)
                              (result/line-series :signal signal)])))

(defn- calculate-coppock-curve
  [data params]
  (let [long-roc (parse-period (:long-roc params) 14 1 200)
        short-roc (parse-period (:short-roc params) 11 1 200)
        wma-period (parse-period (:wma-period params) 10 2 200)
        close-values (field-values data :close)
        roc-a (roc-percent-values close-values long-roc)
        roc-b (roc-percent-values close-values short-roc)
        sum-roc (mapv (fn [a b]
                        (when (and (finite-number? a)
                                   (finite-number? b))
                          (+ a b)))
                      roc-a roc-b)
        values (wma-aligned-values sum-roc wma-period)]
    (result/indicator-result :coppock-curve
                             :separate
                             [(result/line-series :coppock values)])))

(defn- calculate-fisher-transform
  [data params]
  (let [period (parse-period (:period params) 10 2 200)
        median-price (mapv (fn [high low]
                             (/ (+ high low) 2))
                           (field-values data :high)
                           (field-values data :low))
        rolling-high (rolling-max-aligned median-price period)
        rolling-low (rolling-min-aligned median-price period)
        size (count data)
        {:keys [fisher signal]}
        (loop [idx 0
               prev-value 0
               prev-fisher 0
               fisher []
               signal []]
          (if (= idx size)
            {:fisher fisher
             :signal signal}
            (let [price (nth median-price idx)
                  hi (nth rolling-high idx)
                  lo (nth rolling-low idx)
                  normalized (when (and (finite-number? price)
                                        (finite-number? hi)
                                        (finite-number? lo)
                                        (> hi lo))
                               (- (/ (- price lo)
                                     (- hi lo))
                                  0.5))
                  value (if (finite-number? normalized)
                          (let [raw (+ (* 0.66 normalized)
                                       (* 0.67 prev-value))]
                            (clamp raw -0.999 0.999))
                          nil)
                  fisher-value (when (finite-number? value)
                                 (+ (* 0.5 (js/Math.log (/ (+ 1 value)
                                                           (- 1 value))))
                                    (* 0.5 prev-fisher)))]
              (recur (inc idx)
                     (or value prev-value)
                     (or fisher-value prev-fisher)
                     (conj fisher fisher-value)
                     (conj signal prev-fisher)))))]
    (result/indicator-result :fisher-transform
                             :separate
                             [(result/line-series :fisher fisher)
                              (result/line-series :signal signal)])))

(defn- calculate-majority-rule
  [data params]
  (let [period (parse-period (:period params) 14 2 400)
        close-values (field-values data :close)
        sma-close (sma-aligned-values close-values period)
        above-sma (mapv (fn [close sma]
                          (when (and (finite-number? close)
                                     (finite-number? sma))
                            (if (> close sma) 1 0)))
                        close-values sma-close)
        counts (rolling-sum-aligned above-sma period)
        values (mapv (fn [count]
                       (when (finite-number? count)
                         (* 100 (/ count period))))
                     counts)]
    (result/indicator-result :majority-rule
                             :separate
                             [(result/line-series :majority values)])))

(defn- calculate-ratio
  [data params]
  (let [period (parse-period (:period params) 1 1 400)
        close-values (field-values data :close)
        size (count data)
        values (mapv (fn [idx]
                       (if (< idx period)
                         nil
                         (let [current (nth close-values idx)
                               base (nth close-values (- idx period))]
                           (when (and (finite-number? current)
                                      (finite-number? base)
                                      (not= base 0))
                             (/ current base)))))
                     (range size))]
    (result/indicator-result :ratio
                             :separate
                             [(result/line-series :ratio values)])))

(defn- calculate-spread
  [data params]
  (let [period (parse-period (:period params) 1 1 400)
        close-values (field-values data :close)
        size (count data)
        values (mapv (fn [idx]
                       (if (< idx period)
                         nil
                         (let [current (nth close-values idx)
                               base (nth close-values (- idx period))]
                           (when (and (finite-number? current)
                                      (finite-number? base))
                             (- current base)))))
                     (range size))]
    (result/indicator-result :spread
                             :separate
                             [(result/line-series :spread values)])))

(defn- weighted-four
  [values]
  (let [size (count values)]
    (mapv (fn [idx]
            (when (>= idx 3)
              (let [v0 (nth values idx)
                    v1 (nth values (dec idx))
                    v2 (nth values (- idx 2))
                    v3 (nth values (- idx 3))]
                (when (every? finite-number? [v0 v1 v2 v3])
                  (/ (+ v0 (* 2 v1) (* 2 v2) v3)
                     6)))))
          (range size))))

(defn- calculate-relative-vigor-index
  [data params]
  (let [period (parse-period (:period params) 10 2 200)
        co (mapv - (field-values data :close) (field-values data :open))
        hl (mapv - (field-values data :high) (field-values data :low))
        num (sma-aligned-values (weighted-four co) period)
        den (sma-aligned-values (weighted-four hl) period)
        rvi (mapv (fn [n d]
                    (when (and (finite-number? n)
                               (finite-number? d)
                               (not= d 0))
                      (/ n d)))
                  num den)
        signal (weighted-four rvi)]
    (result/indicator-result :relative-vigor-index
                             :separate
                             [(result/line-series :rvi rvi)
                              (result/line-series :signal signal)])))

(defn- calculate-relative-volatility-index
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        close-values (field-values data :close)
        vol (stddev-aligned-values close-values period)
        size (count data)
        up (mapv (fn [idx]
                   (when (and (pos? idx)
                              (finite-number? (nth vol idx)))
                     (if (> (nth close-values idx) (nth close-values (dec idx)))
                       (nth vol idx)
                       0)))
                 (range size))
        down (mapv (fn [idx]
                     (when (and (pos? idx)
                                (finite-number? (nth vol idx)))
                       (if (< (nth close-values idx) (nth close-values (dec idx)))
                         (nth vol idx)
                         0)))
                   (range size))
        up-rma (rma-aligned-values up period)
        down-rma (rma-aligned-values down period)
        values (mapv (fn [u d]
                       (when (and (finite-number? u)
                                  (finite-number? d)
                                  (pos? (+ u d)))
                         (* 100 (/ u (+ u d)))))
                     up-rma down-rma)]
    (result/indicator-result :relative-volatility-index
                             :separate
                             [(result/line-series :rvi-vol values)])))

(defn- calculate-smi-ergodic
  [data params]
  (let [short-period (parse-period (:short params) 13 2 200)
        long-period (parse-period (:long params) 25 2 200)
        signal-period (parse-period (:signal params) 13 2 200)
        tsi (tsi-core (field-values data :close) short-period long-period)
        signal (imath/ema-values tsi signal-period)
        osc (mapv (fn [t s]
                    (when (and (finite-number? t)
                               (finite-number? s))
                      (- t s)))
                  tsi signal)]
    (result/indicator-result :smi-ergodic
                             :separate
                             [(result/histogram-series :osc osc)
                              (result/line-series :indicator tsi)
                              (result/line-series :signal signal)])))

(defn- calculate-ultimate-oscillator
  [data params]
  (let [short-period (parse-period (:short params) 7 2 100)
        medium-period (parse-period (:medium params) 14 2 200)
        long-period (parse-period (:long params) 28 2 400)
        highs-v (field-values data :high)
        lows-v (field-values data :low)
        closes-v (field-values data :close)
        size (count data)
        bp (mapv (fn [idx]
                   (let [close (nth closes-v idx)
                         low (nth lows-v idx)
                         prev-close (if (pos? idx)
                                      (nth closes-v (dec idx))
                                      close)]
                     (- close (min low prev-close))))
                 (range size))
        tr (mapv (fn [idx]
                   (let [high (nth highs-v idx)
                         low (nth lows-v idx)
                         prev-close (if (pos? idx)
                                      (nth closes-v (dec idx))
                                      (nth closes-v idx))]
                     (- (max high prev-close)
                        (min low prev-close))))
                 (range size))
        bp-short (rolling-sum-aligned bp short-period)
        tr-short (rolling-sum-aligned tr short-period)
        bp-medium (rolling-sum-aligned bp medium-period)
        tr-medium (rolling-sum-aligned tr medium-period)
        bp-long (rolling-sum-aligned bp long-period)
        tr-long (rolling-sum-aligned tr long-period)
        values (mapv (fn [bs ts bm tm bl tl]
                       (when (every? finite-number? [bs ts bm tm bl tl])
                         (let [a (if (zero? ts) nil (/ bs ts))
                               b (if (zero? tm) nil (/ bm tm))
                               c (if (zero? tl) nil (/ bl tl))]
                           (when (every? finite-number? [a b c])
                             (* 100 (/ (+ (* 4 a) (* 2 b) c)
                                       7))))))
                     bp-short tr-short bp-medium tr-medium bp-long tr-long)]
    (result/indicator-result :ultimate-oscillator
                             :separate
                             [(result/line-series :uo values)])))

(def ^:private oscillator-calculators
  {:accelerator-oscillator calculate-accelerator-oscillator
   :advance-decline calculate-advance-decline
   :awesome-oscillator calculate-awesome-oscillator
   :balance-of-power calculate-balance-of-power
   :coppock-curve calculate-coppock-curve
   :chande-momentum-oscillator calculate-chande-momentum-oscillator
   :choppiness-index calculate-choppiness-index
   :commodity-channel-index calculate-commodity-channel-index
   :detrended-price-oscillator calculate-detrended-price-oscillator
   :fisher-transform calculate-fisher-transform
   :macd calculate-macd
   :mass-index calculate-mass-index
   :majority-rule calculate-majority-rule
   :chaikin-volatility calculate-chaikin-volatility
   :chande-kroll-stop calculate-chande-kroll-stop
   :chop-zone calculate-chop-zone
   :connors-rsi calculate-connors-rsi
   :correlation-log calculate-correlation-log
   :klinger-oscillator calculate-klinger-oscillator
   :know-sure-thing calculate-know-sure-thing
   :momentum calculate-momentum
   :price-oscillator calculate-price-oscillator
   :rate-of-change calculate-rate-of-change
   :relative-strength-index calculate-relative-strength-index
   :ratio calculate-ratio
   :correlation-coefficient calculate-correlation-coefficient
   :relative-vigor-index calculate-relative-vigor-index
   :relative-volatility-index calculate-relative-volatility-index
   :smi-ergodic calculate-smi-ergodic
   :spread calculate-spread
   :stochastic calculate-stochastic
   :stochastic-rsi calculate-stochastic-rsi
   :trix calculate-trix
   :true-strength-index calculate-true-strength-index
   :trend-strength-index calculate-trend-strength-index
   :williams-r calculate-williams-r
   :ultimate-oscillator calculate-ultimate-oscillator})

(defn calculate-oscillator-indicator
  [indicator-type data params]
  (let [config (or params {})
        calculator (get oscillator-calculators indicator-type)]
    (when (and calculator
               (contracts/valid-indicator-input? data config))
      (contracts/enforce-indicator-result indicator-type
                                          (count data)
                                          (calculator data config)))))
