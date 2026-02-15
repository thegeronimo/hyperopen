(ns hyperopen.views.trading-chart.utils.indicators-wave2
  (:require [hyperopen.domain.trading.indicators.math :as imath]
            ["indicatorts" :refer [apo cci cmf cmo dema ema emv fi ichimokuCloud kc macd mfi mi movingLeastSquare movingLinearRegressionUsingLeastSquare psar rma rsi stoch tema trix vortex vwap vwma willr]]))

(def ^:private wave2-indicator-definitions
  [{:id :bollinger-bands-percent-b
    :name "Bollinger Bands %B"
    :short-name "%B"
    :description "Position of price within Bollinger Bands"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20
                     :multiplier 2}}
   {:id :bollinger-bands-width
    :name "Bollinger Bands Width"
    :short-name "BBW"
    :description "Normalized width of Bollinger Bands"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20
                     :multiplier 2}}
   {:id :chaikin-money-flow
    :name "Chaikin Money Flow"
    :short-name "CMF"
    :description "Volume-weighted accumulation/distribution over a period"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20}}
   {:id :chaikin-oscillator
    :name "Chaikin Oscillator"
    :short-name "CHO"
    :description "EMA difference of accumulation/distribution"
    :supports-period? false
    :default-config {:fast 3
                     :slow 10}}
   {:id :chande-momentum-oscillator
    :name "Chande Momentum Oscillator"
    :short-name "CMO"
    :description "Momentum oscillator using rolling gains and losses"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}
   {:id :choppiness-index
    :name "Choppiness Index"
    :short-name "CHOP"
    :description "Log-scaled range efficiency oscillator"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}
   {:id :commodity-channel-index
    :name "Commodity Channel Index"
    :short-name "CCI"
    :description "Typical-price deviation from moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20}}
   {:id :detrended-price-oscillator
    :name "Detrended Price Oscillator"
    :short-name "DPO"
    :description "Price minus displaced moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20}}
   {:id :directional-movement
    :name "Directional Movement"
    :short-name "DMI"
    :description "+DI and -DI directional strength lines"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}
   {:id :donchian-channels
    :name "Donchian Channels"
    :short-name "DONCH"
    :description "Highest-high and lowest-low rolling channels"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}}
   {:id :double-ema
    :name "Double EMA"
    :short-name "DEMA"
    :description "Double exponential moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}}
   {:id :ease-of-movement
    :name "Ease Of Movement"
    :short-name "EOM"
    :description "Volume-adjusted distance moved"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}
   {:id :elders-force-index
    :name "Elder's Force Index"
    :short-name "EFI"
    :description "EMA of price change multiplied by volume"
    :supports-period? true
    :default-period 13
    :min-period 2
    :max-period 200
    :default-config {:period 13}}
   {:id :ema-cross
    :name "EMA Cross"
    :short-name "EMA X"
    :description "Fast and slow EMA crossover lines"
    :supports-period? false
    :default-config {:fast 12
                     :slow 26}}
   {:id :envelopes
    :name "Envelopes"
    :short-name "ENV"
    :description "SMA with percentage envelope bands"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :percent 0.025}}
   {:id :historical-volatility
    :name "Historical Volatility"
    :short-name "HV"
    :description "Annualized volatility from log close-to-close returns"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :annualization 365}}
   {:id :hull-moving-average
    :name "Hull Moving Average"
    :short-name "HMA"
    :description "Weighted moving average with reduced lag"
    :supports-period? true
    :default-period 21
    :min-period 2
    :max-period 400
    :default-config {:period 21}}
   {:id :ichimoku-cloud
    :name "Ichimoku Cloud"
    :short-name "ICHI"
    :description "Tenkan, Kijun, Senkou spans, and lagging span"
    :supports-period? false
    :default-config {:short 9
                     :medium 26
                     :long 52
                     :close 26}}
   {:id :keltner-channels
    :name "Keltner Channels"
    :short-name "KC"
    :description "EMA centerline with ATR-based bands"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20}}
   {:id :least-squares-moving-average
    :name "Least Squares Moving Average"
    :short-name "LSMA"
    :description "Moving linear-regression line"
    :supports-period? true
    :default-period 25
    :min-period 2
    :max-period 400
    :default-config {:period 25}}
   {:id :linear-regression-curve
    :name "Linear Regression Curve"
    :short-name "LRC"
    :description "Moving least-squares regression curve"
    :supports-period? true
    :default-period 25
    :min-period 2
    :max-period 400
    :default-config {:period 25}}
   {:id :linear-regression-slope
    :name "Linear Regression Slope"
    :short-name "LRS"
    :description "Slope of moving linear regression"
    :supports-period? true
    :default-period 25
    :min-period 2
    :max-period 400
    :default-config {:period 25}}
   {:id :ma-cross
    :name "MA Cross"
    :short-name "MA X"
    :description "Fast and slow simple moving average crossover"
    :supports-period? false
    :default-config {:fast 9
                     :slow 21}}
   {:id :ma-with-ema-cross
    :name "MA with EMA Cross"
    :short-name "MA/EMA X"
    :description "Simple moving average crossed with exponential moving average"
    :supports-period? false
    :default-config {:ma-period 20
                     :ema-period 50}}
   {:id :macd
    :name "MACD"
    :short-name "MACD"
    :description "MACD line, signal line, and histogram"
    :supports-period? false
    :default-config {:fast 12
                     :slow 26
                     :signal 9}}
   {:id :mass-index
    :name "Mass Index"
    :short-name "MI"
    :description "Range expansion/contraction trend reversal indicator"
    :supports-period? false
    :default-config {:emaPeriod 9
                     :miPeriod 25}}
   {:id :money-flow-index
    :name "Money Flow Index"
    :short-name "MFI"
    :description "Volume-weighted RSI-style oscillator"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}
   {:id :moving-average-channel
    :name "Moving Average Channel"
    :short-name "MA Channel"
    :description "SMA center with standard-deviation channel"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :multiplier 1.5}}
   {:id :moving-average-double
    :name "Moving Average Double"
    :short-name "MA Double"
    :description "Alias of DEMA"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}}
   {:id :moving-average-exponential
    :name "Moving Average Exponential"
    :short-name "EMA"
    :description "Exponential moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}}
   {:id :moving-average-multiple
    :name "Moving Average Multiple"
    :short-name "MA Multi"
    :description "Multiple moving averages (5, 10, 20, 50)"
    :supports-period? false
    :default-config {:periods [5 10 20 50]}}
   {:id :moving-average-triple
    :name "Moving Average Triple"
    :short-name "MA Triple"
    :description "Alias of TEMA"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}}
   {:id :moving-average-weighted
    :name "Moving Average Weighted"
    :short-name "WMA"
    :description "Linearly weighted moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}}
   {:id :parabolic-sar
    :name "Parabolic SAR"
    :short-name "PSAR"
    :description "Trend-following stop and reverse points"
    :supports-period? false
    :default-config {:step 0.02
                     :max 0.2}}
   {:id :price-channel
    :name "Price Channel"
    :short-name "PChannel"
    :description "Highest-high and lowest-low channel"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}}
   {:id :price-oscillator
    :name "Price Oscillator"
    :short-name "APO"
    :description "Absolute difference between fast and slow EMA"
    :supports-period? false
    :default-config {:fast 12
                     :slow 26}}
   {:id :smoothed-moving-average
    :name "Smoothed Moving Average"
    :short-name "SMMA"
    :description "Rolling moving average (RMA)"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 400
    :default-config {:period 14}}
   {:id :stochastic
    :name "Stochastic"
    :short-name "Stoch"
    :description "%K and %D stochastic oscillator"
    :supports-period? false
    :default-config {:kPeriod 14
                     :dPeriod 3}}
   {:id :stochastic-rsi
    :name "Stochastic RSI"
    :short-name "Stoch RSI"
    :description "Stochastic oscillator applied to RSI"
    :supports-period? false
    :default-config {:rsiPeriod 14
                     :stochPeriod 14
                     :kSmoothing 3
                     :dSmoothing 3}}
   {:id :supertrend
    :name "SuperTrend"
    :short-name "SuperTrend"
    :description "ATR-based trend-following overlay"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10
                     :multiplier 3}}
   {:id :triple-ema
    :name "Triple EMA"
    :short-name "TEMA"
    :description "Triple exponential moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}}
   {:id :trix
    :name "TRIX"
    :short-name "TRIX"
    :description "Triple-smoothed EMA rate of change"
    :supports-period? true
    :default-period 15
    :min-period 2
    :max-period 400
    :default-config {:period 15}}
   {:id :vortex-indicator
    :name "Vortex Indicator"
    :short-name "VI"
    :description "+VI and -VI trend oscillators"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}
   {:id :vwap
    :name "VWAP"
    :short-name "VWAP"
    :description "Volume weighted average price"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}}
   {:id :vwma
    :name "VWMA"
    :short-name "VWMA"
    :description "Volume weighted moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}}
   {:id :williams-r
    :name "Williams %R"
    :short-name "%R"
    :description "Overbought and oversold momentum oscillator"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}])

(defn get-wave2-indicators
  []
  wave2-indicator-definitions)

(def ^:private finite-number? imath/finite-number?)
(def ^:private normalize-values imath/normalize-values)
(def ^:private parse-period imath/parse-period)
(def ^:private parse-number imath/parse-number)
(def ^:private times imath/times)
(def ^:private field-values imath/field-values)

(defn- js-array
  [values]
  (clj->js values))

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

(defn- stddev-values
  [values period]
  (imath/stddev-values values period :aligned))

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
     :color (if (neg? value) "#ef4444" "#10b981")}
    {:time time}))

(defn- points-from-values
  [time-values values]
  (mapv point time-values values))

(defn- histogram-points-from-values
  [time-values values]
  (mapv histogram-point time-values values))

(defn- line-series
  [id name color time-values values]
  {:id id
   :name name
   :series-type :line
   :color color
   :line-width 2
   :data (points-from-values time-values values)})

(defn- histogram-series
  [id name time-values values]
  {:id id
   :name name
   :series-type :histogram
   :data (histogram-points-from-values time-values values)})

(defn- indicator-result
  [indicator-type pane series]
  {:type indicator-type
   :pane pane
   :series series})

(defn- highs
  [data]
  (field-values data :high))

(defn- lows
  [data]
  (field-values data :low))

(defn- closes
  [data]
  (field-values data :close))

(defn- opens
  [data]
  (field-values data :open))

(defn- volumes
  [data]
  (field-values data :volume))

(defn- true-range-values
  [high-values low-values close-values]
  (mapv (fn [idx]
          (let [high (nth high-values idx)
                low (nth low-values idx)
                prev-close (if (zero? idx) (nth close-values idx) (nth close-values (dec idx)))]
            (max (- high low)
                 (js/Math.abs (- high prev-close))
                 (js/Math.abs (- low prev-close)))))
        (range (count high-values))))

(defn- rma-values-local
  [values period]
  (imath/rma-values values period :lagged))

(defn- plus-minus-di-values
  [data period]
  (let [high-values (highs data)
        low-values (lows data)
        close-values (closes data)
        size (count high-values)
        plus-dm (mapv (fn [idx]
                        (if (zero? idx)
                          0
                          (let [up-move (- (nth high-values idx) (nth high-values (dec idx)))
                                down-move (- (nth low-values (dec idx)) (nth low-values idx))]
                            (if (and (> up-move down-move) (> up-move 0)) up-move 0))))
                      (range size))
        minus-dm (mapv (fn [idx]
                         (if (zero? idx)
                           0
                           (let [up-move (- (nth high-values idx) (nth high-values (dec idx)))
                                 down-move (- (nth low-values (dec idx)) (nth low-values idx))]
                             (if (and (> down-move up-move) (> down-move 0)) down-move 0))))
                       (range size))
        tr-values (true-range-values high-values low-values close-values)
        atr-values (rma-values-local tr-values period)
        plus-rma (rma-values-local plus-dm period)
        minus-rma (rma-values-local minus-dm period)
        plus-di (mapv (fn [idx]
                        (let [atr (nth atr-values idx)
                              v (nth plus-rma idx)]
                          (when (and (finite-number? atr) (finite-number? v) (pos? atr))
                            (* 100 (/ v atr)))))
                      (range size))
        minus-di (mapv (fn [idx]
                         (let [atr (nth atr-values idx)
                               v (nth minus-rma idx)]
                           (when (and (finite-number? atr) (finite-number? v) (pos? atr))
                             (* 100 (/ v atr)))))
                       (range size))]
    {:plus-di plus-di
     :minus-di minus-di
     :atr atr-values
     :tr tr-values}))

(defn- bollinger-components
  [close-values period multiplier]
  (let [basis (sma-values close-values period)
        stdev-values (stddev-values close-values period)
        upper (mapv (fn [idx]
                      (let [b (nth basis idx)
                            s (nth stdev-values idx)]
                        (when (and (finite-number? b) (finite-number? s))
                          (+ b (* multiplier s)))))
                    (range (count close-values)))
        lower (mapv (fn [idx]
                      (let [b (nth basis idx)
                            s (nth stdev-values idx)]
                        (when (and (finite-number? b) (finite-number? s))
                          (- b (* multiplier s)))))
                    (range (count close-values)))]
    {:basis basis
     :upper upper
     :lower lower
     :stdev stdev-values}))

(defn- wma-values
  [values period]
  (let [weights (range 1 (inc period))
        divisor (reduce + 0 weights)]
    (mapv (fn [idx]
            (when-let [window (window-for-index values idx period)]
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

(defn- log-return-values
  [close-values]
  (mapv (fn [idx]
          (if (zero? idx)
            nil
            (let [current (nth close-values idx)
                  prev (nth close-values (dec idx))]
              (when (and (finite-number? current)
                         (finite-number? prev)
                         (pos? current)
                         (pos? prev))
                (js/Math.log (/ current prev))))))
        (range (count close-values))))

(defn- indices
  [n]
  (vec (range n)))

(defn- rolling-max
  [values period]
  (rolling-apply values period (fn [window] (apply max window))))

(defn- rolling-min
  [values period]
  (rolling-apply values period (fn [window] (apply min window))))

(defn- calculate-bollinger-bands-percent-b
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        multiplier (parse-number (:multiplier params) 2)
        time-values (times data)
        close-values (closes data)
        {:keys [upper lower]} (bollinger-components close-values period multiplier)
        percent-b (mapv (fn [idx]
                          (let [close (nth close-values idx)
                                u (nth upper idx)
                                l (nth lower idx)
                                spread (- (or u 0) (or l 0))]
                            (when (and (finite-number? close)
                                       (finite-number? u)
                                       (finite-number? l)
                                       (not (zero? spread)))
                              (/ (- close l) spread))))
                        (range (count close-values)))]
    (indicator-result :bollinger-bands-percent-b
                      :separate
                      [(line-series :percent-b "%B" "#38bdf8" time-values percent-b)])))

(defn- calculate-bollinger-bands-width
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        multiplier (parse-number (:multiplier params) 2)
        time-values (times data)
        close-values (closes data)
        {:keys [basis upper lower]} (bollinger-components close-values period multiplier)
        width (mapv (fn [idx]
                      (let [b (nth basis idx)
                            u (nth upper idx)
                            l (nth lower idx)]
                        (when (and (finite-number? b)
                                   (finite-number? u)
                                   (finite-number? l)
                                   (not (zero? b)))
                          (/ (- u l) b))))
                    (range (count close-values)))]
    (indicator-result :bollinger-bands-width
                      :separate
                      [(line-series :bbw "BBW" "#0ea5e9" time-values width)])))

(defn- calculate-chaikin-money-flow
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        time-values (times data)
        values (normalize-values
                (cmf (js-array (highs data))
                     (js-array (lows data))
                     (js-array (closes data))
                     (js-array (volumes data))
                     #js {:period period}))]
    (indicator-result :chaikin-money-flow
                      :separate
                      [(line-series :cmf "CMF" "#22d3ee" time-values values)])))

(defn- calculate-chaikin-oscillator
  [data params]
  (let [fast (parse-period (:fast params) 3 1 200)
        slow (parse-period (:slow params) 10 2 400)
        time-values (times data)
        result (js->clj
                (cmo (js-array (highs data))
                     (js-array (lows data))
                     (js-array (closes data))
                     (js-array (volumes data))
                     #js {:fast fast :slow slow})
                :keywordize-keys true)
        ad-line (normalize-values (:adResult result))
        osc-line (normalize-values (:cmoResult result))]
    (indicator-result :chaikin-oscillator
                      :separate
                      [(line-series :chaikin-osc "Chaikin Osc" "#f97316" time-values osc-line)
                       (line-series :ad-line "A/D Line" "#6b7280" time-values ad-line)])))

(defn- calculate-chande-momentum-oscillator
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        close-values (closes data)
        time-values (times data)
        diffs (mapv (fn [idx]
                      (if (zero? idx)
                        0
                        (- (nth close-values idx) (nth close-values (dec idx)))))
                    (range (count close-values)))
        gains (mapv (fn [value] (max value 0)) diffs)
        losses (mapv (fn [value] (max (- value) 0)) diffs)
        sum-gains (rolling-sum gains period)
        sum-losses (rolling-sum losses period)
        values (mapv (fn [idx]
                       (let [g (nth sum-gains idx)
                             l (nth sum-losses idx)
                             total (+ (or g 0) (or l 0))]
                         (when (and (finite-number? g)
                                    (finite-number? l)
                                    (pos? total))
                           (* 100 (/ (- g l) total)))))
                     (range (count close-values)))]
    (indicator-result :chande-momentum-oscillator
                      :separate
                      [(line-series :cmo "CMO" "#eab308" time-values values)])))

(defn- calculate-choppiness-index
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        high-values (highs data)
        low-values (lows data)
        close-values (closes data)
        time-values (times data)
        tr-values (true-range-values high-values low-values close-values)
        tr-sum (rolling-sum tr-values period)
        high-max (rolling-max high-values period)
        low-min (rolling-min low-values period)
        denom-log (js/Math.log10 period)
        values (mapv (fn [idx]
                       (let [sum-tr (nth tr-sum idx)
                             hh (nth high-max idx)
                             ll (nth low-min idx)
                             range-value (- (or hh 0) (or ll 0))]
                         (when (and (finite-number? sum-tr)
                                    (finite-number? hh)
                                    (finite-number? ll)
                                    (pos? sum-tr)
                                    (pos? range-value)
                                    (not (zero? denom-log)))
                           (* 100 (/ (js/Math.log10 (/ sum-tr range-value))
                                     denom-log)))))
                     (range (count close-values)))]
    (indicator-result :choppiness-index
                      :separate
                      [(line-series :chop "CHOP" "#f97316" time-values values)])))

(defn- calculate-commodity-channel-index
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        time-values (times data)
        values (normalize-values
                (cci (js-array (highs data))
                     (js-array (lows data))
                     (js-array (closes data))
                     #js {:period period}))]
    (indicator-result :commodity-channel-index
                      :separate
                      [(line-series :cci "CCI" "#f59e0b" time-values values)])))

(defn- calculate-detrended-price-oscillator
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        close-values (closes data)
        sma-line (sma-values close-values period)
        shift (+ (int (js/Math.floor (/ period 2))) 1)
        size (count close-values)
        time-values (times data)
        values (mapv (fn [idx]
                       (let [shifted-idx (- idx shift)]
                         (when (>= shifted-idx 0)
                           (let [price (nth close-values shifted-idx)
                                 avg (nth sma-line shifted-idx)]
                             (when (and (finite-number? price)
                                        (finite-number? avg))
                               (- price avg))))))
                     (range size))]
    (indicator-result :detrended-price-oscillator
                      :separate
                      [(line-series :dpo "DPO" "#3b82f6" time-values values)])))

(defn- calculate-directional-movement
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        {:keys [plus-di minus-di]} (plus-minus-di-values data period)
        time-values (times data)]
    (indicator-result :directional-movement
                      :separate
                      [(line-series :plus-di "+DI" "#22c55e" time-values plus-di)
                       (line-series :minus-di "-DI" "#ef4444" time-values minus-di)])))

(defn- calculate-donchian-channels
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        high-values (highs data)
        low-values (lows data)
        upper (rolling-max high-values period)
        lower (rolling-min low-values period)
        middle (mapv (fn [idx]
                       (let [u (nth upper idx)
                             l (nth lower idx)]
                         (when (and (finite-number? u) (finite-number? l))
                           (/ (+ u l) 2))))
                     (range (count high-values)))
        time-values (times data)]
    (indicator-result :donchian-channels
                      :overlay
                      [(line-series :upper "Donchian Upper" "#22c55e" time-values upper)
                       (line-series :middle "Donchian Mid" "#f59e0b" time-values middle)
                       (line-series :lower "Donchian Lower" "#ef4444" time-values lower)])))

(defn- calculate-double-ema
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        time-values (times data)
        values (normalize-values
                (dema (js-array (closes data))
                      #js {:period period}))]
    (indicator-result :double-ema
                      :overlay
                      [(line-series :dema "DEMA" "#22d3ee" time-values values)])))

(defn- calculate-ease-of-movement
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        time-values (times data)
        values (normalize-values
                (emv (js-array (highs data))
                     (js-array (lows data))
                     (js-array (volumes data))
                     #js {:period period}))]
    (indicator-result :ease-of-movement
                      :separate
                      [(line-series :eom "EOM" "#06b6d4" time-values values)])))

(defn- calculate-elders-force-index
  [data params]
  (let [period (parse-period (:period params) 13 2 200)
        time-values (times data)
        values (normalize-values
                (fi (js-array (closes data))
                    (js-array (volumes data))
                    #js {:period period}))]
    (indicator-result :elders-force-index
                      :separate
                      [(line-series :efi "EFI" "#e879f9" time-values values)])))

(defn- calculate-ema-cross
  [data params]
  (let [fast (parse-period (:fast params) 12 1 200)
        slow (parse-period (:slow params) 26 2 400)
        time-values (times data)
        close-values (closes data)
        fast-line (normalize-values (ema (js-array close-values) #js {:period fast}))
        slow-line (normalize-values (ema (js-array close-values) #js {:period slow}))]
    (indicator-result :ema-cross
                      :overlay
                      [(line-series :fast "EMA Fast" "#22c55e" time-values fast-line)
                       (line-series :slow "EMA Slow" "#ef4444" time-values slow-line)])))

(defn- calculate-envelopes
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        percent (parse-number (:percent params) 0.025)
        close-values (closes data)
        basis (sma-values close-values period)
        upper (mapv (fn [value]
                      (when (finite-number? value)
                        (* value (+ 1 percent))))
                    basis)
        lower (mapv (fn [value]
                      (when (finite-number? value)
                        (* value (- 1 percent))))
                    basis)
        time-values (times data)]
    (indicator-result :envelopes
                      :overlay
                      [(line-series :upper "Env Upper" "#22c55e" time-values upper)
                       (line-series :basis "Env Basis" "#f59e0b" time-values basis)
                       (line-series :lower "Env Lower" "#ef4444" time-values lower)])))

(defn- calculate-historical-volatility
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        annualization (parse-number (:annualization params) 365)
        time-values (times data)
        returns (log-return-values (closes data))
        std-values (stddev-values returns period)
        hv-values (mapv (fn [value]
                          (when (finite-number? value)
                            (* value (js/Math.sqrt annualization) 100)))
                        std-values)]
    (indicator-result :historical-volatility
                      :separate
                      [(line-series :hv "HV" "#a855f7" time-values hv-values)])))

(defn- calculate-hull-moving-average
  [data params]
  (let [period (parse-period (:period params) 21 2 400)
        time-values (times data)
        values (hull-values (closes data) period)]
    (indicator-result :hull-moving-average
                      :overlay
                      [(line-series :hma "HMA" "#f97316" time-values values)])))

(defn- calculate-ichimoku-cloud
  [data params]
  (let [short (parse-period (:short params) 9 2 200)
        medium (parse-period (:medium params) 26 2 300)
        long (parse-period (:long params) 52 2 400)
        close-shift (parse-period (:close params) 26 1 300)
        result (js->clj
                (ichimokuCloud (js-array (highs data))
                               (js-array (lows data))
                               (js-array (closes data))
                               #js {:short short
                                    :medium medium
                                    :long long
                                    :close close-shift})
                :keywordize-keys true)
        time-values (times data)
        tenkan (normalize-values (:tenkan result) {:zero-as-nil? true})
        kijun (normalize-values (:kijun result) {:zero-as-nil? true})
        ssa (normalize-values (:ssa result) {:zero-as-nil? true})
        ssb (normalize-values (:ssb result) {:zero-as-nil? true})
        lagging-span (normalize-values (:laggingSpan result) {:zero-as-nil? true})]
    (indicator-result :ichimoku-cloud
                      :overlay
                      [(line-series :tenkan "Tenkan" "#22c55e" time-values tenkan)
                       (line-series :kijun "Kijun" "#ef4444" time-values kijun)
                       (line-series :ssa "Senkou A" "#38bdf8" time-values ssa)
                       (line-series :ssb "Senkou B" "#f59e0b" time-values ssb)
                       (line-series :lagging "Lagging" "#a855f7" time-values lagging-span)])))

(defn- calculate-keltner-channels
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        result (js->clj
                (kc (js-array (highs data))
                    (js-array (lows data))
                    (js-array (closes data))
                    #js {:period period})
                :keywordize-keys true)
        time-values (times data)]
    (indicator-result :keltner-channels
                      :overlay
                      [(line-series :upper "KC Upper" "#22c55e" time-values (normalize-values (:upper result)))
                       (line-series :middle "KC Middle" "#f59e0b" time-values (normalize-values (:middle result)))
                       (line-series :lower "KC Lower" "#ef4444" time-values (normalize-values (:lower result)))])))

(defn- calculate-least-squares-moving-average
  [data params]
  (let [period (parse-period (:period params) 25 2 400)
        close-values (closes data)
        x-values (indices (count close-values))
        regression (normalize-values
                    (movingLinearRegressionUsingLeastSquare period
                                                            (js-array x-values)
                                                            (js-array close-values)))
        time-values (times data)]
    (indicator-result :least-squares-moving-average
                      :overlay
                      [(line-series :lsma "LSMA" "#22d3ee" time-values regression)])))

(defn- calculate-linear-regression-curve
  [data params]
  (let [period (parse-period (:period params) 25 2 400)
        close-values (closes data)
        x-values (indices (count close-values))
        regression (normalize-values
                    (movingLinearRegressionUsingLeastSquare period
                                                            (js-array x-values)
                                                            (js-array close-values)))
        time-values (times data)]
    (indicator-result :linear-regression-curve
                      :overlay
                      [(line-series :lrc "LRC" "#60a5fa" time-values regression)])))

(defn- calculate-linear-regression-slope
  [data params]
  (let [period (parse-period (:period params) 25 2 400)
        close-values (closes data)
        x-values (indices (count close-values))
        result (js->clj
                (movingLeastSquare period
                                   (js-array x-values)
                                   (js-array close-values))
                :keywordize-keys true)
        slope (normalize-values (:m result))
        time-values (times data)]
    (indicator-result :linear-regression-slope
                      :separate
                      [(line-series :slope "LRS" "#c084fc" time-values slope)])))

(defn- calculate-ma-cross
  [data params]
  (let [fast (parse-period (:fast params) 9 1 200)
        slow (parse-period (:slow params) 21 2 400)
        close-values (closes data)
        fast-line (sma-values close-values fast)
        slow-line (sma-values close-values slow)
        time-values (times data)]
    (indicator-result :ma-cross
                      :overlay
                      [(line-series :fast "MA Fast" "#22c55e" time-values fast-line)
                       (line-series :slow "MA Slow" "#ef4444" time-values slow-line)])))

(defn- calculate-ma-with-ema-cross
  [data params]
  (let [ma-period (parse-period (:ma-period params) 20 2 400)
        ema-period (parse-period (:ema-period params) 50 2 400)
        close-values (closes data)
        ma-line (sma-values close-values ma-period)
        ema-line (normalize-values (ema (js-array close-values) #js {:period ema-period}))
        time-values (times data)]
    (indicator-result :ma-with-ema-cross
                      :overlay
                      [(line-series :ma "MA" "#22c55e" time-values ma-line)
                       (line-series :ema "EMA" "#ef4444" time-values ema-line)])))

(defn- calculate-macd
  [data params]
  (let [fast (parse-period (:fast params) 12 1 200)
        slow (parse-period (:slow params) 26 2 400)
        signal (parse-period (:signal params) 9 1 200)
        result (js->clj
                (macd (js-array (closes data))
                      #js {:fast fast
                           :slow slow
                           :signal signal})
                :keywordize-keys true)
        macd-line (normalize-values (:macdLine result))
        signal-line (normalize-values (:signalLine result))
        histogram (mapv (fn [idx]
                          (let [m (nth macd-line idx)
                                s (nth signal-line idx)]
                            (when (and (finite-number? m)
                                       (finite-number? s))
                              (- m s))))
                        (range (count macd-line)))
        time-values (times data)]
    (indicator-result :macd
                      :separate
                      [(histogram-series :hist "MACD Hist" time-values histogram)
                       (line-series :macd "MACD" "#38bdf8" time-values macd-line)
                       (line-series :signal "Signal" "#f59e0b" time-values signal-line)])))

(defn- calculate-mass-index
  [data params]
  (let [ema-period (parse-period (:emaPeriod params) 9 1 200)
        mi-period (parse-period (:miPeriod params) 25 2 400)
        values (normalize-values
                (mi (js-array (highs data))
                    (js-array (lows data))
                    #js {:emaPeriod ema-period
                         :miPeriod mi-period}))
        time-values (times data)]
    (indicator-result :mass-index
                      :separate
                      [(line-series :mi "Mass Index" "#f59e0b" time-values values)])))

(defn- calculate-money-flow-index
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        values (normalize-values
                (mfi (js-array (highs data))
                     (js-array (lows data))
                     (js-array (closes data))
                     (js-array (volumes data))
                     #js {:period period}))
        time-values (times data)]
    (indicator-result :money-flow-index
                      :separate
                      [(line-series :mfi "MFI" "#14b8a6" time-values values)])))

(defn- calculate-moving-average-channel
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        multiplier (parse-number (:multiplier params) 1.5)
        close-values (closes data)
        basis (sma-values close-values period)
        spread (stddev-values close-values period)
        upper (mapv (fn [idx]
                      (let [b (nth basis idx)
                            s (nth spread idx)]
                        (when (and (finite-number? b) (finite-number? s))
                          (+ b (* multiplier s)))))
                    (range (count close-values)))
        lower (mapv (fn [idx]
                      (let [b (nth basis idx)
                            s (nth spread idx)]
                        (when (and (finite-number? b) (finite-number? s))
                          (- b (* multiplier s)))))
                    (range (count close-values)))
        time-values (times data)]
    (indicator-result :moving-average-channel
                      :overlay
                      [(line-series :upper "MAC Upper" "#22c55e" time-values upper)
                       (line-series :basis "MAC Mid" "#f59e0b" time-values basis)
                       (line-series :lower "MAC Lower" "#ef4444" time-values lower)])))

(defn- calculate-moving-average-double
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (dema (js-array (closes data))
                      #js {:period period}))
        time-values (times data)]
    (indicator-result :moving-average-double
                      :overlay
                      [(line-series :double "MA Double" "#22d3ee" time-values values)])))

(defn- calculate-moving-average-exponential
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (ema (js-array (closes data))
                     #js {:period period}))
        time-values (times data)]
    (indicator-result :moving-average-exponential
                      :overlay
                      [(line-series :ema "EMA" "#38bdf8" time-values values)])))

(defn- calculate-moving-average-multiple
  [data params]
  (let [periods (or (:periods params) [5 10 20 50])
        close-values (closes data)
        time-values (times data)
        colors ["#22c55e" "#38bdf8" "#f59e0b" "#ef4444"]
        series (map-indexed
                (fn [idx period]
                  (let [length (parse-period period period 1 400)
                        values (sma-values close-values length)]
                    (line-series (keyword (str "ma-" length))
                                 (str "MA " length)
                                 (nth colors (mod idx (count colors)))
                                 time-values
                                 values)))
                periods)]
    (indicator-result :moving-average-multiple
                      :overlay
                      (vec series))))

(defn- calculate-moving-average-triple
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (tema (js-array (closes data))
                      #js {:period period}))
        time-values (times data)]
    (indicator-result :moving-average-triple
                      :overlay
                      [(line-series :triple "MA Triple" "#22d3ee" time-values values)])))

(defn- calculate-moving-average-weighted
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (wma-values (closes data) period)
        time-values (times data)]
    (indicator-result :moving-average-weighted
                      :overlay
                      [(line-series :wma "WMA" "#38bdf8" time-values values)])))

(defn- calculate-parabolic-sar
  [data params]
  (let [step (parse-number (:step params) 0.02)
        max-value (parse-number (:max params) 0.2)
        result (js->clj
                (psar (js-array (highs data))
                      (js-array (lows data))
                      (js-array (closes data))
                      #js {:step step :max max-value})
                :keywordize-keys true)
        values (normalize-values (:psarResult result))
        time-values (times data)]
    (indicator-result :parabolic-sar
                      :overlay
                      [(line-series :psar "PSAR" "#f97316" time-values values)])))

(defn- calculate-price-channel
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        high-values (highs data)
        low-values (lows data)
        upper (rolling-max high-values period)
        lower (rolling-min low-values period)
        middle (mapv (fn [idx]
                       (let [u (nth upper idx)
                             l (nth lower idx)]
                         (when (and (finite-number? u) (finite-number? l))
                           (/ (+ u l) 2))))
                     (range (count high-values)))
        time-values (times data)]
    (indicator-result :price-channel
                      :overlay
                      [(line-series :upper "Price Ch Upper" "#22c55e" time-values upper)
                       (line-series :middle "Price Ch Mid" "#f59e0b" time-values middle)
                       (line-series :lower "Price Ch Lower" "#ef4444" time-values lower)])))

(defn- calculate-price-oscillator
  [data params]
  (let [fast (parse-period (:fast params) 12 1 200)
        slow (parse-period (:slow params) 26 2 400)
        values (normalize-values
                (apo (js-array (closes data))
                     #js {:fast fast :slow slow}))
        time-values (times data)]
    (indicator-result :price-oscillator
                      :separate
                      [(line-series :apo "APO" "#eab308" time-values values)])))

(defn- calculate-smoothed-moving-average
  [data params]
  (let [period (parse-period (:period params) 14 2 400)
        values (normalize-values
                (rma (js-array (closes data))
                     #js {:period period}))
        time-values (times data)]
    (indicator-result :smoothed-moving-average
                      :overlay
                      [(line-series :smma "SMMA" "#22d3ee" time-values values)])))

(defn- calculate-stochastic
  [data params]
  (let [k-period (parse-period (:kPeriod params) 14 1 200)
        d-period (parse-period (:dPeriod params) 3 1 200)
        result (js->clj
                (stoch (js-array (highs data))
                       (js-array (lows data))
                       (js-array (closes data))
                       #js {:kPeriod k-period :dPeriod d-period})
                :keywordize-keys true)
        k-values (normalize-values (:k result))
        d-values (normalize-values (:d result))
        time-values (times data)]
    (indicator-result :stochastic
                      :separate
                      [(line-series :k "%K" "#22c55e" time-values k-values)
                       (line-series :d "%D" "#ef4444" time-values d-values)])))

(defn- calculate-stochastic-rsi
  [data params]
  (let [rsi-period (parse-period (:rsiPeriod params) 14 2 200)
        stoch-period (parse-period (:stochPeriod params) 14 2 200)
        k-smoothing (parse-period (:kSmoothing params) 3 1 50)
        d-smoothing (parse-period (:dSmoothing params) 3 1 50)
        rsi-values (normalize-values
                    (rsi (js-array (closes data))
                         #js {:period rsi-period}))
        min-rsi (rolling-min rsi-values stoch-period)
        max-rsi (rolling-max rsi-values stoch-period)
        raw-k (mapv (fn [idx]
                      (let [r (nth rsi-values idx)
                            mn (nth min-rsi idx)
                            mx (nth max-rsi idx)
                            range-value (- (or mx 0) (or mn 0))]
                        (when (and (finite-number? r)
                                   (finite-number? mn)
                                   (finite-number? mx)
                                   (pos? range-value))
                          (* 100 (/ (- r mn) range-value)))))
                    (range (count rsi-values)))
        k-values (sma-values raw-k k-smoothing)
        d-values (sma-values k-values d-smoothing)
        time-values (times data)]
    (indicator-result :stochastic-rsi
                      :separate
                      [(line-series :k "StochRSI %K" "#22c55e" time-values k-values)
                       (line-series :d "StochRSI %D" "#ef4444" time-values d-values)])))

(defn- calculate-supertrend
  [data params]
  (let [period (parse-period (:period params) 10 2 200)
        multiplier (parse-number (:multiplier params) 3)
        high-values (highs data)
        low-values (lows data)
        close-values (closes data)
        time-values (times data)
        tr-values (true-range-values high-values low-values close-values)
        atr-values (rma-values-local tr-values period)
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
    (indicator-result :supertrend
                      :overlay
                      [(line-series :up "SuperTrend Up" "#22c55e" time-values up-line)
                       (line-series :down "SuperTrend Down" "#ef4444" time-values down-line)])))

(defn- calculate-triple-ema
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (tema (js-array (closes data))
                      #js {:period period}))
        time-values (times data)]
    (indicator-result :triple-ema
                      :overlay
                      [(line-series :tema "TEMA" "#22d3ee" time-values values)])))

(defn- calculate-trix
  [data params]
  (let [period (parse-period (:period params) 15 2 400)
        values (normalize-values
                (trix (js-array (closes data))
                      #js {:period period}))
        time-values (times data)]
    (indicator-result :trix
                      :separate
                      [(line-series :trix "TRIX" "#f59e0b" time-values values)])))

(defn- calculate-vortex-indicator
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        result (js->clj
                (vortex (js-array (highs data))
                        (js-array (lows data))
                        (js-array (closes data))
                        #js {:period period})
                :keywordize-keys true)
        plus-values (normalize-values (:plus result))
        minus-values (normalize-values (:minus result))
        time-values (times data)]
    (indicator-result :vortex-indicator
                      :separate
                      [(line-series :plus "+VI" "#22c55e" time-values plus-values)
                       (line-series :minus "-VI" "#ef4444" time-values minus-values)])))

(defn- calculate-vwap
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (vwap (js-array (closes data))
                      (js-array (volumes data))
                      #js {:period period}))
        time-values (times data)]
    (indicator-result :vwap
                      :overlay
                      [(line-series :vwap "VWAP" "#22d3ee" time-values values)])))

(defn- calculate-vwma
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (vwma (js-array (closes data))
                      (js-array (volumes data))
                      #js {:period period}))
        time-values (times data)]
    (indicator-result :vwma
                      :overlay
                      [(line-series :vwma "VWMA" "#38bdf8" time-values values)])))

(defn- calculate-williams-r
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        values (normalize-values
                (willr (js-array (highs data))
                       (js-array (lows data))
                       (js-array (closes data))
                       #js {:period period}))
        time-values (times data)]
    (indicator-result :williams-r
                      :separate
                      [(line-series :williams-r "%R" "#e879f9" time-values values)])))

(def ^:private wave2-calculators
  {:bollinger-bands-percent-b calculate-bollinger-bands-percent-b
   :bollinger-bands-width calculate-bollinger-bands-width
   :chaikin-money-flow calculate-chaikin-money-flow
   :chaikin-oscillator calculate-chaikin-oscillator
   :chande-momentum-oscillator calculate-chande-momentum-oscillator
   :choppiness-index calculate-choppiness-index
   :commodity-channel-index calculate-commodity-channel-index
   :detrended-price-oscillator calculate-detrended-price-oscillator
   :directional-movement calculate-directional-movement
   :donchian-channels calculate-donchian-channels
   :double-ema calculate-double-ema
   :ease-of-movement calculate-ease-of-movement
   :elders-force-index calculate-elders-force-index
   :ema-cross calculate-ema-cross
   :envelopes calculate-envelopes
   :historical-volatility calculate-historical-volatility
   :hull-moving-average calculate-hull-moving-average
   :ichimoku-cloud calculate-ichimoku-cloud
   :keltner-channels calculate-keltner-channels
   :least-squares-moving-average calculate-least-squares-moving-average
   :linear-regression-curve calculate-linear-regression-curve
   :linear-regression-slope calculate-linear-regression-slope
   :ma-cross calculate-ma-cross
   :ma-with-ema-cross calculate-ma-with-ema-cross
   :macd calculate-macd
   :mass-index calculate-mass-index
   :money-flow-index calculate-money-flow-index
   :moving-average-channel calculate-moving-average-channel
   :moving-average-double calculate-moving-average-double
   :moving-average-exponential calculate-moving-average-exponential
   :moving-average-multiple calculate-moving-average-multiple
   :moving-average-triple calculate-moving-average-triple
   :moving-average-weighted calculate-moving-average-weighted
   :parabolic-sar calculate-parabolic-sar
   :price-channel calculate-price-channel
   :price-oscillator calculate-price-oscillator
   :smoothed-moving-average calculate-smoothed-moving-average
   :stochastic calculate-stochastic
   :stochastic-rsi calculate-stochastic-rsi
   :supertrend calculate-supertrend
   :triple-ema calculate-triple-ema
   :trix calculate-trix
   :vortex-indicator calculate-vortex-indicator
   :vwap calculate-vwap
   :vwma calculate-vwma
   :williams-r calculate-williams-r})

(defn calculate-wave2-indicator
  [indicator-type data params]
  (let [config (or params {})
        calculator (get wave2-calculators indicator-type)]
    (when calculator
      (calculator data config))))
