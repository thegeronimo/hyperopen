(ns hyperopen.views.trading-chart.utils.indicators-wave3
  (:require [hyperopen.domain.trading.indicators.math :as imath]))

(def ^:private wave3-indicator-definitions
  [{:id :chaikin-volatility
    :name "Chaikin Volatility"
    :short-name "CHV"
    :description "Rate-of-change of EMA high-low range"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10
                     :roc-period 10}}
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
                     :multiplier 1.0}}
   {:id :chop-zone
    :name "Chop Zone"
    :short-name "CZ"
    :description "Trend-angle zone derived from EMA slope"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}
   {:id :connors-rsi
    :name "Connors RSI"
    :short-name "CRSI"
    :description "Average of short RSI, streak RSI, and percent-rank"
    :supports-period? false
    :default-config {:rsi-period 3
                     :streak-period 2
                     :rank-period 100}}
   {:id :coppock-curve
    :name "Coppock Curve"
    :short-name "COPP"
    :description "WMA of summed long and short ROC"
    :supports-period? false
    :default-config {:long-roc 14
                     :short-roc 11
                     :wma-period 10}}
   {:id :correlation-log
    :name "Correlation - Log"
    :short-name "Corr Log"
    :description "Rolling Pearson correlation of log price and time"
    :supports-period? true
    :default-period 20
    :min-period 3
    :max-period 400
    :default-config {:period 20}}
   {:id :fisher-transform
    :name "Fisher Transform"
    :short-name "Fisher"
    :description "Fisher transform of normalized median price"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10}}
   {:id :guppy-multiple-moving-average
    :name "Guppy Multiple Moving Average"
    :short-name "GMMA"
    :description "Short and long EMA ribbon"
    :supports-period? false
    :default-config {}}
   {:id :klinger-oscillator
    :name "Klinger Oscillator"
    :short-name "KVO"
    :description "Volume-force EMA oscillator"
    :supports-period? false
    :default-config {:fast 34
                     :slow 55
                     :signal 13}}
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
                     :signal 9}}
   {:id :majority-rule
    :name "Majority Rule"
    :short-name "Majority"
    :description "Percent of bars closing above SMA"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 400
    :default-config {:period 14}}
   {:id :mcginley-dynamic
    :name "McGinley Dynamic"
    :short-name "MGD"
    :description "Adaptive moving average with speed correction"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 400
    :default-config {:period 14}}
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
                     :slow 30}}
   {:id :moving-average-hamming
    :name "Moving Average Hamming"
    :short-name "HAMMA"
    :description "Moving average with Hamming window weights"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}}
   {:id :pivot-points-standard
    :name "Pivot Points Standard"
    :short-name "Pivots"
    :description "PP, R1-R3 and S1-S3 from previous window"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}}
   {:id :rank-correlation-index
    :name "Rank Correlation Index"
    :short-name "RCI"
    :description "Spearman rank correlation oscillator"
    :supports-period? true
    :default-period 9
    :min-period 3
    :max-period 400
    :default-config {:period 9}}
   {:id :ratio
    :name "Ratio"
    :short-name "Ratio"
    :description "Single-stream proxy: close divided by close n bars ago"
    :supports-period? true
    :default-period 1
    :min-period 1
    :max-period 400
    :default-config {:period 1}}
   {:id :relative-vigor-index
    :name "Relative Vigor Index"
    :short-name "RVI"
    :description "Smoothed ratio of close-open to high-low"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10}}
   {:id :relative-volatility-index
    :name "Relative Volatility Index"
    :short-name "RVI Vol"
    :description "RSI-style oscillator over volatility"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}
   {:id :smi-ergodic
    :name "SMI Ergodic Indicator/Oscillator"
    :short-name "SMI Ergodic"
    :description "TSI-derived indicator, signal, and oscillator"
    :supports-period? false
    :default-config {:short 13
                     :long 25
                     :signal 13}}
   {:id :spread
    :name "Spread"
    :short-name "Spread"
    :description "Single-stream proxy: close minus close n bars ago"
    :supports-period? true
    :default-period 1
    :min-period 1
    :max-period 400
    :default-config {:period 1}}
   {:id :ultimate-oscillator
    :name "Ultimate Oscillator"
    :short-name "UO"
    :description "Weighted multi-timeframe buying pressure oscillator"
    :supports-period? false
    :default-config {:short 7
                     :medium 14
                     :long 28}}
   {:id :volatility-close-to-close
    :name "Volatility Close-to-Close"
    :short-name "Vol C-C"
    :description "Annualized volatility of log close returns"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :annualization 365}}
   {:id :volatility-index
    :name "Volatility Index"
    :short-name "VolIdx"
    :description "ATR as percent of close"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 400
    :default-config {:period 14}}
   {:id :volatility-ohlc
    :name "Volatility O-H-L-C"
    :short-name "Vol OHLC"
    :description "Annualized Garman-Klass volatility"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :annualization 365}}
   {:id :volatility-zero-trend-close-to-close
    :name "Volatility Zero Trend Close-to-Close"
    :short-name "Vol ZT C-C"
    :description "Detrended annualized close-to-close volatility"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :annualization 365}}
   {:id :volume
    :name "Volume"
    :short-name "VOL"
    :description "Raw traded volume"
    :supports-period? false
    :default-config {}}
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
                     :lips-shift 3}}
   {:id :williams-fractal
    :name "Williams Fractal"
    :short-name "Fractal"
    :description "Five-bar high/low fractal markers"
    :supports-period? false
    :default-config {}}
   {:id :zig-zag
    :name "Zig Zag"
    :short-name "ZigZag"
    :description "Swing-line connecting pivots that exceed threshold"
    :supports-period? false
    :default-config {:threshold-percent 5}}])

(defn get-wave3-indicators
  []
  wave3-indicator-definitions)

(def ^:private finite-number? imath/finite-number?)
(def ^:private clamp imath/clamp)
(def ^:private parse-period imath/parse-period)
(def ^:private parse-number imath/parse-number)
(def ^:private times imath/times)
(def ^:private field-values imath/field-values)

(defn- highs [data] (field-values data :high))
(defn- lows [data] (field-values data :low))
(defn- opens [data] (field-values data :open))
(defn- closes [data] (field-values data :close))
(defn- volumes [data] (field-values data :volume))

(def ^:private mean imath/mean)

(defn- window-for-index
  [values idx period]
  (imath/window-for-index values idx period :aligned))

(defn- rolling-apply
  [values period f]
  (imath/rolling-apply values period f :aligned))

(defn- rolling-sum
  [values period]
  (imath/rolling-sum values period :aligned))

(defn- sma-values
  [values period]
  (imath/sma-values values period :aligned))

(defn- ema-values
  [values period]
  (imath/ema-values values period))

(defn- rma-values
  [values period]
  (imath/rma-values values period :aligned))

(defn- wma-values
  [values period]
  (let [weights (vec (range 1 (inc period)))
        weight-sum (reduce + 0 weights)]
    (rolling-apply values
                   period
                   (fn [window]
                     (/ (reduce + 0 (map * window weights))
                        weight-sum)))))

(defn- shift-right
  [values shift]
  (let [size (count values)
        shifted (concat (repeat shift nil) values)]
    (vec (take size shifted))))

(defn- true-range-values
  [data]
  (let [high-values (highs data)
        low-values (lows data)
        close-values (closes data)
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

(defn- rolling-max
  [values period]
  (imath/rolling-max values period :aligned))

(defn- rolling-min
  [values period]
  (imath/rolling-min values period :aligned))

(defn- stddev-values
  [values period]
  (imath/stddev-values values period :aligned))

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

(defn- pearson-correlation
  [xs ys]
  (when (and (= (count xs) (count ys))
             (seq xs)
             (every? finite-number? xs)
             (every? finite-number? ys))
    (let [mx (mean xs)
          my (mean ys)
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
            (when-let [window (window-for-index values idx period)]
              (pearson-correlation window time-axis)))
          (range size))))

(defn- rs-rolling
  [values period]
  (let [size (count values)]
    (mapv (fn [idx]
            (when-let [window (window-for-index values idx period)]
              (when (every? finite-number? window)
                (let [x-values (vec (range period))
                      x-mean (/ (reduce + 0 x-values) period)
                      y-mean (mean window)
                      sxx (reduce + 0 (map (fn [x]
                                             (let [dx (- x x-mean)]
                                               (* dx dx)))
                                           x-values))
                      sxy (reduce + 0 (map (fn [x y]
                                             (* (- x x-mean)
                                                (- y y-mean)))
                                           x-values window))
                      slope (if (zero? sxx) 0 (/ sxy sxx))
                      intercept (- y-mean (* slope x-mean))
                      residuals (map (fn [x y]
                                       (- y (+ intercept (* slope x))))
                                     x-values window)
                      rss (reduce + 0 (map #(* % %) residuals))
                      denom (max 1 (- period 2))]
                  {:slope slope
                   :intercept intercept
                   :standard-error (js/Math.sqrt (/ rss denom))
                   :center (+ intercept (* slope (dec period)))}))))
          (range size))))

(defn- point
  [time value]
  (if (finite-number? value)
    {:time time :value value}
    {:time time}))

(defn- histogram-point
  [time value]
  (if (finite-number? value)
    {:time time
     :value value
     :color (if (neg? value) "#ef4444" "#22c55e")}
    {:time time}))

(defn- points-from-values
  [time-values indicator-values]
  (mapv point time-values indicator-values))

(defn- histogram-points-from-values
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
   :data (histogram-points-from-values time-values indicator-values)})

(defn- indicator-result
  ([indicator-type pane series]
   {:type indicator-type
    :pane pane
    :series series})
  ([indicator-type pane series markers]
   (cond-> {:type indicator-type
            :pane pane
            :series series}
     (seq markers) (assoc :markers markers))))

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

(defn- percent-rank-values
  [values period]
  (let [size (count values)]
    (mapv (fn [idx]
            (when-let [window (window-for-index values idx period)]
              (let [current (last window)]
                (when (finite-number? current)
                  (let [comparable (filter finite-number? window)
                        below (count (filter #(< % current) comparable))
                        denom (max 1 (count comparable))]
                    (* 100 (/ below denom)))))))
          (range size))))

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
    (rolling-apply values
                   period
                   (fn [window]
                     (reduce + 0 (map * window weights))))))

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

(defn- calculate-chaikin-volatility
  [data params]
  (let [period (parse-period (:period params) 10 2 200)
        roc-period (parse-period (:roc-period params) period 1 200)
        ranges (mapv - (highs data) (lows data))
        ema-range (ema-values ranges period)
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
                     (range size))
        time-values (times data)]
    (indicator-result :chaikin-volatility
                      :separate
                      [(line-series :chv "CHV" "#22d3ee" time-values values)])))

(defn- calculate-chande-kroll-stop
  [data params]
  (let [period (parse-period (:period params) 10 2 200)
        atr-period (parse-period (:atr-period params) period 2 200)
        multiplier (parse-number (:multiplier params) 1.0)
        high-stop-base (rolling-max (highs data) period)
        low-stop-base (rolling-min (lows data) period)
        atr-values (rma-values (true-range-values data) atr-period)
        long-stop (mapv (fn [high atr]
                          (when (and (finite-number? high)
                                     (finite-number? atr))
                            (- high (* multiplier atr))))
                        high-stop-base atr-values)
        short-stop (mapv (fn [low atr]
                           (when (and (finite-number? low)
                                      (finite-number? atr))
                             (+ low (* multiplier atr))))
                         low-stop-base atr-values)
        time-values (times data)]
    (indicator-result :chande-kroll-stop
                      :overlay
                      [(line-series :long-stop "CK Long" "#ef4444" time-values long-stop)
                       (line-series :short-stop "CK Short" "#22c55e" time-values short-stop)])))

(defn- calculate-chop-zone
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        close-values (closes data)
        ema-close (ema-values close-values period)
        atr-values (rma-values (true-range-values data) period)
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
                    (range size))
        time-values (times data)]
    (indicator-result :chop-zone
                      :separate
                      [(histogram-series :chop-zone "Chop Zone" time-values zones)])))

(defn- calculate-connors-rsi
  [data params]
  (let [rsi-period (parse-period (:rsi-period params) 3 2 50)
        streak-period (parse-period (:streak-period params) 2 2 50)
        rank-period (parse-period (:rank-period params) 100 2 400)
        close-values (closes data)
        streaks (streak-values close-values)
        close-rsi (rsi-values close-values rsi-period)
        streak-rsi (rsi-values (mapv #(+ 100 %) streaks) streak-period)
        roc1 (roc-percent-values close-values 1)
        rank (percent-rank-values roc1 rank-period)
        values (mapv (fn [a b c]
                       (when (and (finite-number? a)
                                  (finite-number? b)
                                  (finite-number? c))
                         (/ (+ a b c) 3)))
                     close-rsi streak-rsi rank)
        time-values (times data)]
    (indicator-result :connors-rsi
                      :separate
                      [(line-series :connors-rsi "Connors RSI" "#f97316" time-values values)])))

(defn- calculate-coppock-curve
  [data params]
  (let [long-roc (parse-period (:long-roc params) 14 1 200)
        short-roc (parse-period (:short-roc params) 11 1 200)
        wma-period (parse-period (:wma-period params) 10 2 200)
        close-values (closes data)
        roc-a (roc-percent-values close-values long-roc)
        roc-b (roc-percent-values close-values short-roc)
        sum-roc (mapv (fn [a b]
                        (when (and (finite-number? a)
                                   (finite-number? b))
                          (+ a b)))
                      roc-a roc-b)
        values (wma-values sum-roc wma-period)
        time-values (times data)]
    (indicator-result :coppock-curve
                      :separate
                      [(line-series :coppock "Coppock" "#38bdf8" time-values values)])))

(defn- calculate-correlation-log
  [data params]
  (let [period (parse-period (:period params) 20 3 400)
        close-values (closes data)
        logged (mapv (fn [price]
                       (when (and (finite-number? price)
                                  (pos? price))
                         (js/Math.log price)))
                     close-values)
        values (rolling-correlation-with-time logged period)
        time-values (times data)]
    (indicator-result :correlation-log
                      :separate
                      [(line-series :correlation-log "Corr Log" "#a78bfa" time-values values)])))

(defn- calculate-fisher-transform
  [data params]
  (let [period (parse-period (:period params) 10 2 200)
        median-price (mapv (fn [high low]
                             (/ (+ high low) 2))
                           (highs data)
                           (lows data))
        rolling-high (rolling-max median-price period)
        rolling-low (rolling-min median-price period)
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
                     (conj signal prev-fisher)))))
        time-values (times data)]
    (indicator-result :fisher-transform
                      :separate
                      [(line-series :fisher "Fisher" "#f97316" time-values fisher)
                       (line-series :signal "Signal" "#22d3ee" time-values signal)])))

(defn- calculate-guppy-multiple-moving-average
  [data _params]
  (let [close-values (closes data)
        short-periods [3 5 8 10 12 15]
        long-periods [30 35 40 45 50 60]
        short-colors ["#22c55e" "#4ade80" "#86efac" "#16a34a" "#15803d" "#166534"]
        long-colors ["#ef4444" "#f87171" "#fca5a5" "#dc2626" "#b91c1c" "#991b1b"]
        time-values (times data)
        short-series (mapv (fn [period color]
                             (line-series (keyword (str "ema-short-" period))
                                          (str "EMA " period)
                                          color
                                          time-values
                                          (ema-values close-values period)))
                           short-periods short-colors)
        long-series (mapv (fn [period color]
                            (line-series (keyword (str "ema-long-" period))
                                         (str "EMA " period)
                                         color
                                         time-values
                                         (ema-values close-values period)))
                          long-periods long-colors)]
    (indicator-result :guppy-multiple-moving-average
                      :overlay
                      (vec (concat short-series long-series)))))

(defn- calculate-klinger-oscillator
  [data params]
  (let [fast (parse-period (:fast params) 34 2 400)
        slow (parse-period (:slow params) 55 2 400)
        signal-period (parse-period (:signal params) 13 2 400)
        highs-v (highs data)
        lows-v (lows data)
        closes-v (closes data)
        volumes-v (volumes data)
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
        fast-ema (ema-values vf fast)
        slow-ema (ema-values vf slow)
        kvo (mapv (fn [f s]
                    (when (and (finite-number? f)
                               (finite-number? s))
                      (- f s)))
                  fast-ema slow-ema)
        signal (ema-values kvo signal-period)
        hist (mapv (fn [k s]
                     (when (and (finite-number? k)
                                (finite-number? s))
                       (- k s)))
                   kvo signal)
        time-values (times data)]
    (indicator-result :klinger-oscillator
                      :separate
                      [(histogram-series :hist "KVO Hist" time-values hist)
                       (line-series :kvo "KVO" "#22d3ee" time-values kvo)
                       (line-series :signal "Signal" "#f97316" time-values signal)])))

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
        close-values (closes data)
        rcma1 (sma-values (roc-percent-values close-values roc1) sma1)
        rcma2 (sma-values (roc-percent-values close-values roc2) sma2)
        rcma3 (sma-values (roc-percent-values close-values roc3) sma3)
        rcma4 (sma-values (roc-percent-values close-values roc4) sma4)
        kst (mapv (fn [a b c d]
                    (when (every? finite-number? [a b c d])
                      (+ a (* 2 b) (* 3 c) (* 4 d))))
                  rcma1 rcma2 rcma3 rcma4)
        signal (sma-values kst signal-period)
        time-values (times data)]
    (indicator-result :know-sure-thing
                      :separate
                      [(line-series :kst "KST" "#22d3ee" time-values kst)
                       (line-series :signal "Signal" "#f97316" time-values signal)])))

(defn- calculate-majority-rule
  [data params]
  (let [period (parse-period (:period params) 14 2 400)
        close-values (closes data)
        sma-close (sma-values close-values period)
        above-sma (mapv (fn [close sma]
                          (when (and (finite-number? close)
                                     (finite-number? sma))
                            (if (> close sma) 1 0)))
                        close-values sma-close)
        counts (rolling-sum above-sma period)
        values (mapv (fn [count]
                       (when (finite-number? count)
                         (* 100 (/ count period))))
                     counts)
        time-values (times data)]
    (indicator-result :majority-rule
                      :separate
                      [(line-series :majority "Majority %" "#4ade80" time-values values)])))

(defn- calculate-mcginley-dynamic
  [data params]
  (let [period (parse-period (:period params) 14 2 400)
        close-values (closes data)
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
                            (conj out current)))))
        time-values (times data)]
    (indicator-result :mcginley-dynamic
                      :overlay
                      [(line-series :mcginley "McGinley" "#f59e0b" time-values values)])))

(defn- calculate-moving-average-adaptive
  [data params]
  (let [period (parse-period (:period params) 10 2 400)
        fast (parse-period (:fast params) 2 2 100)
        slow (parse-period (:slow params) 30 2 200)
        close-values (closes data)
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
                            (conj out current)))))
        time-values (times data)]
    (indicator-result :moving-average-adaptive
                      :overlay
                      [(line-series :kama "KAMA" "#22d3ee" time-values values)])))

(defn- calculate-moving-average-hamming
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        weights (make-hamming-weights period)
        values (weighted-ma (closes data) weights)
        time-values (times data)]
    (indicator-result :moving-average-hamming
                      :overlay
                      [(line-series :hamming-ma "Hamming MA" "#38bdf8" time-values values)])))

(defn- calculate-pivot-points-standard
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        highs-v (highs data)
        lows-v (lows data)
        closes-v (closes data)
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
               (mapv #(get % k) pivot-data))
        time-values (times data)]
    (indicator-result :pivot-points-standard
                      :overlay
                      [(line-series :pp "PP" "#e5e7eb" time-values (pick :pp))
                       (line-series :r1 "R1" "#22c55e" time-values (pick :r1))
                       (line-series :s1 "S1" "#ef4444" time-values (pick :s1))
                       (line-series :r2 "R2" "#16a34a" time-values (pick :r2))
                       (line-series :s2 "S2" "#dc2626" time-values (pick :s2))
                       (line-series :r3 "R3" "#15803d" time-values (pick :r3))
                       (line-series :s3 "S3" "#b91c1c" time-values (pick :s3))])))

(defn- calculate-rank-correlation-index
  [data params]
  (let [period (parse-period (:period params) 9 3 400)
        close-values (closes data)
        size (count data)
        values (mapv (fn [idx]
                       (when-let [window (window-for-index close-values idx period)]
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
                     (range size))
        time-values (times data)]
    (indicator-result :rank-correlation-index
                      :separate
                      [(line-series :rci "RCI" "#a855f7" time-values values)])))

(defn- calculate-ratio
  [data params]
  (let [period (parse-period (:period params) 1 1 400)
        close-values (closes data)
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
                     (range size))
        time-values (times data)]
    (indicator-result :ratio
                      :separate
                      [(line-series :ratio "Ratio" "#22d3ee" time-values values)])))

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
        co (mapv - (closes data) (opens data))
        hl (mapv - (highs data) (lows data))
        num (sma-values (weighted-four co) period)
        den (sma-values (weighted-four hl) period)
        rvi (mapv (fn [n d]
                    (when (and (finite-number? n)
                               (finite-number? d)
                               (not= d 0))
                      (/ n d)))
                  num den)
        signal (weighted-four rvi)
        time-values (times data)]
    (indicator-result :relative-vigor-index
                      :separate
                      [(line-series :rvi "RVI" "#22d3ee" time-values rvi)
                       (line-series :signal "Signal" "#f97316" time-values signal)])))

(defn- calculate-relative-volatility-index
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        close-values (closes data)
        vol (stddev-values close-values period)
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
        up-rma (rma-values up period)
        down-rma (rma-values down period)
        values (mapv (fn [u d]
                       (when (and (finite-number? u)
                                  (finite-number? d)
                                  (pos? (+ u d)))
                         (* 100 (/ u (+ u d)))))
                     up-rma down-rma)
        time-values (times data)]
    (indicator-result :relative-volatility-index
                      :separate
                      [(line-series :rvi-vol "RVI" "#a855f7" time-values values)])))

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
        ema1 (ema-values mtm short-period)
        ema2 (ema-values ema1 long-period)
        abs-ema1 (ema-values abs-mtm short-period)
        abs-ema2 (ema-values abs-ema1 long-period)]
    (mapv (fn [a b]
            (when (and (finite-number? a)
                       (finite-number? b)
                       (not= b 0))
              (* 100 (/ a b))))
          ema2 abs-ema2)))

(defn- calculate-smi-ergodic
  [data params]
  (let [short-period (parse-period (:short params) 13 2 200)
        long-period (parse-period (:long params) 25 2 200)
        signal-period (parse-period (:signal params) 13 2 200)
        tsi (tsi-core (closes data) short-period long-period)
        signal (ema-values tsi signal-period)
        osc (mapv (fn [t s]
                    (when (and (finite-number? t)
                               (finite-number? s))
                      (- t s)))
                  tsi signal)
        time-values (times data)]
    (indicator-result :smi-ergodic
                      :separate
                      [(histogram-series :osc "Osc" time-values osc)
                       (line-series :indicator "SMI Ergodic" "#22d3ee" time-values tsi)
                       (line-series :signal "Signal" "#f97316" time-values signal)])))

(defn- calculate-spread
  [data params]
  (let [period (parse-period (:period params) 1 1 400)
        close-values (closes data)
        size (count data)
        values (mapv (fn [idx]
                       (if (< idx period)
                         nil
                         (let [current (nth close-values idx)
                               base (nth close-values (- idx period))]
                           (when (and (finite-number? current)
                                      (finite-number? base))
                             (- current base)))))
                     (range size))
        time-values (times data)]
    (indicator-result :spread
                      :separate
                      [(line-series :spread "Spread" "#f97316" time-values values)])))

(defn- calculate-ultimate-oscillator
  [data params]
  (let [short-period (parse-period (:short params) 7 2 100)
        medium-period (parse-period (:medium params) 14 2 200)
        long-period (parse-period (:long params) 28 2 400)
        highs-v (highs data)
        lows-v (lows data)
        closes-v (closes data)
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
        bp-short (rolling-sum bp short-period)
        tr-short (rolling-sum tr short-period)
        bp-medium (rolling-sum bp medium-period)
        tr-medium (rolling-sum tr medium-period)
        bp-long (rolling-sum bp long-period)
        tr-long (rolling-sum tr long-period)
        values (mapv (fn [bs ts bm tm bl tl]
                       (when (every? finite-number? [bs ts bm tm bl tl])
                         (let [a (if (zero? ts) nil (/ bs ts))
                               b (if (zero? tm) nil (/ bm tm))
                               c (if (zero? tl) nil (/ bl tl))]
                           (when (every? finite-number? [a b c])
                             (* 100 (/ (+ (* 4 a) (* 2 b) c)
                                       7))))))
                     bp-short tr-short bp-medium tr-medium bp-long tr-long)
        time-values (times data)]
    (indicator-result :ultimate-oscillator
                      :separate
                      [(line-series :uo "Ultimate Osc" "#a78bfa" time-values values)])))

(defn- calculate-volatility-close-to-close
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        annualization (parse-number (:annualization params) 365)
        close-values (closes data)
        size (count data)
        log-returns (mapv (fn [idx]
                            (if (zero? idx)
                              nil
                              (let [current (nth close-values idx)
                                    previous (nth close-values (dec idx))]
                                (when (and (finite-number? current)
                                           (finite-number? previous)
                                           (pos? current)
                                           (pos? previous))
                                  (js/Math.log (/ current previous))))))
                          (range size))
        values (mapv (fn [stdev]
                       (when (finite-number? stdev)
                         (* 100 stdev (js/Math.sqrt annualization))))
                     (stddev-values log-returns period))
        time-values (times data)]
    (indicator-result :volatility-close-to-close
                      :separate
                      [(line-series :vol-cc "Vol C-C" "#22d3ee" time-values values)])))

(defn- calculate-volatility-index
  [data params]
  (let [period (parse-period (:period params) 14 2 400)
        atr (rma-values (true-range-values data) period)
        close-values (closes data)
        values (mapv (fn [a c]
                       (when (and (finite-number? a)
                                  (finite-number? c)
                                  (not= c 0))
                         (* 100 (/ a c))))
                     atr close-values)
        time-values (times data)]
    (indicator-result :volatility-index
                      :separate
                      [(line-series :vol-index "Vol Index" "#f97316" time-values values)])))

(defn- calculate-volatility-ohlc
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        annualization (parse-number (:annualization params) 365)
        opens-v (opens data)
        highs-v (highs data)
        lows-v (lows data)
        closes-v (closes data)
        size (count data)
        rs (mapv (fn [idx]
                   (let [open (nth opens-v idx)
                         high (nth highs-v idx)
                         low (nth lows-v idx)
                         close (nth closes-v idx)]
                     (when (every? finite-number? [open high low close])
                       (let [log-hl (when (and (> high 0) (> low 0))
                                      (js/Math.log (/ high low)))
                             log-co (when (and (> close 0) (> open 0))
                                      (js/Math.log (/ close open)))]
                         (when (and (finite-number? log-hl)
                                    (finite-number? log-co))
                           (- (* 0.5 (* log-hl log-hl))
                              (* (- (* 2 (js/Math.log 2)) 1)
                                 (* log-co log-co))))))))
                 (range size))
        values (mapv (fn [avg]
                       (when (and (finite-number? avg)
                                  (not (neg? avg)))
                         (* 100 (js/Math.sqrt (* annualization avg)))))
                     (sma-values rs period))
        time-values (times data)]
    (indicator-result :volatility-ohlc
                      :separate
                      [(line-series :vol-ohlc "Vol OHLC" "#22d3ee" time-values values)])))

(defn- calculate-volatility-zero-trend-close-to-close
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        annualization (parse-number (:annualization params) 365)
        close-values (closes data)
        trend (sma-values close-values period)
        size (count data)
        detrended (mapv (fn [close avg]
                          (when (and (finite-number? close)
                                     (finite-number? avg))
                            (- close avg)))
                        close-values trend)
        returns (mapv (fn [idx]
                        (if (zero? idx)
                          nil
                          (let [current (nth detrended idx)
                                previous (nth detrended (dec idx))]
                            (when (and (finite-number? current)
                                       (finite-number? previous))
                              (- current previous)))))
                      (range size))
        values (mapv (fn [stdev]
                       (when (finite-number? stdev)
                         (* 100 stdev (js/Math.sqrt annualization))))
                     (stddev-values returns period))
        time-values (times data)]
    (indicator-result :volatility-zero-trend-close-to-close
                      :separate
                      [(line-series :vol-zt-cc "Vol ZT C-C" "#a855f7" time-values values)])))

(defn- calculate-volume
  [data _params]
  (let [time-values (times data)
        values (volumes data)]
    (indicator-result :volume
                      :separate
                      [(histogram-series :volume "Volume" time-values values)])))

(defn- calculate-williams-alligator
  [data params]
  (let [jaw-period (parse-period (:jaw-period params) 13 2 200)
        jaw-shift (parse-period (:jaw-shift params) 8 0 50)
        teeth-period (parse-period (:teeth-period params) 8 2 200)
        teeth-shift (parse-period (:teeth-shift params) 5 0 50)
        lips-period (parse-period (:lips-period params) 5 2 200)
        lips-shift (parse-period (:lips-shift params) 3 0 50)
        median (mapv (fn [high low]
                       (/ (+ high low) 2))
                     (highs data)
                     (lows data))
        jaw (shift-right (rma-values median jaw-period) jaw-shift)
        teeth (shift-right (rma-values median teeth-period) teeth-shift)
        lips (shift-right (rma-values median lips-period) lips-shift)
        time-values (times data)]
    (indicator-result :williams-alligator
                      :overlay
                      [(line-series :jaw "Jaw" "#3b82f6" time-values jaw)
                       (line-series :teeth "Teeth" "#ef4444" time-values teeth)
                       (line-series :lips "Lips" "#22c55e" time-values lips)])))

(defn- calculate-williams-fractal
  [data _params]
  (let [high-values (highs data)
        low-values (lows data)
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
    (indicator-result :williams-fractal
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
        close-values (closes data)
        pivots (zigzag-pivots close-values threshold)
        values (interpolate-zigzag (count data) pivots)
        time-values (times data)]
    (indicator-result :zig-zag
                      :overlay
                      [(line-series :zig-zag "Zig Zag" "#f97316" time-values values)])))

(def ^:private wave3-calculators
  {:chaikin-volatility calculate-chaikin-volatility
   :chande-kroll-stop calculate-chande-kroll-stop
   :chop-zone calculate-chop-zone
   :connors-rsi calculate-connors-rsi
   :coppock-curve calculate-coppock-curve
   :correlation-log calculate-correlation-log
   :fisher-transform calculate-fisher-transform
   :guppy-multiple-moving-average calculate-guppy-multiple-moving-average
   :klinger-oscillator calculate-klinger-oscillator
   :know-sure-thing calculate-know-sure-thing
   :majority-rule calculate-majority-rule
   :mcginley-dynamic calculate-mcginley-dynamic
   :moving-average-adaptive calculate-moving-average-adaptive
   :moving-average-hamming calculate-moving-average-hamming
   :pivot-points-standard calculate-pivot-points-standard
   :rank-correlation-index calculate-rank-correlation-index
   :ratio calculate-ratio
   :relative-vigor-index calculate-relative-vigor-index
   :relative-volatility-index calculate-relative-volatility-index
   :smi-ergodic calculate-smi-ergodic
   :spread calculate-spread
   :ultimate-oscillator calculate-ultimate-oscillator
   :volatility-close-to-close calculate-volatility-close-to-close
   :volatility-index calculate-volatility-index
   :volatility-ohlc calculate-volatility-ohlc
   :volatility-zero-trend-close-to-close calculate-volatility-zero-trend-close-to-close
   :volume calculate-volume
   :williams-alligator calculate-williams-alligator
   :williams-fractal calculate-williams-fractal
   :zig-zag calculate-zig-zag})

(defn calculate-wave3-indicator
  [indicator-type data params]
  (let [config (or params {})
        calculator (get wave3-calculators indicator-type)]
    (when calculator
      (calculator data config))))
