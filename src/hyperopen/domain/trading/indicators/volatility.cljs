(ns hyperopen.domain.trading.indicators.volatility
  (:require [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private seconds-per-week (* 7 24 60 60))

(def ^:private volatility-indicator-definitions
  [{:id :week-52-high-low
    :name "52 Week High/Low"
    :short-name "52W H/L"
    :description "Rolling 52-week high and low levels"
    :supports-period? true
    :default-period 52
    :min-period 1
    :max-period 260
    :default-config {:period 52}}
   {:id :atr
    :name "Average True Range"
    :short-name "ATR"
    :description "Wilder average true range"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}
   {:id :bollinger-bands
    :name "Bollinger Bands"
    :short-name "BOLL"
    :description "Upper, basis, and lower volatility bands"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20
                     :multiplier 2}}
   {:id :standard-deviation
    :name "Standard Deviation"
    :short-name "StdDev"
    :description "Rolling standard deviation of close"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
    :migrated-from :wave2}
   {:id :standard-error
    :name "Standard Error"
    :short-name "StdErr"
    :description "Standard error of rolling linear regression"
    :supports-period? true
    :default-period 20
    :min-period 3
    :max-period 400
    :default-config {:period 20}
    :migrated-from :wave3}
   {:id :standard-error-bands
    :name "Standard Error Bands"
    :short-name "SE Bands"
    :description "Linear-regression centerline plus/minus standard error"
    :supports-period? true
    :default-period 20
    :min-period 3
    :max-period 400
    :default-config {:period 20
                     :multiplier 2}
    :migrated-from :wave3}
   {:id :volatility-close-to-close
    :name "Volatility Close-to-Close"
    :short-name "Vol C-C"
    :description "Annualized volatility of log close returns"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :annualization 365}
    :migrated-from :wave3}
   {:id :volatility-index
    :name "Volatility Index"
    :short-name "VolIdx"
    :description "ATR as percent of close"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 400
    :default-config {:period 14}
    :migrated-from :wave3}
   {:id :volatility-ohlc
    :name "Volatility O-H-L-C"
    :short-name "Vol OHLC"
    :description "Annualized Garman-Klass volatility"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :annualization 365}
    :migrated-from :wave3}
   {:id :volatility-zero-trend-close-to-close
    :name "Volatility Zero Trend Close-to-Close"
    :short-name "Vol ZT C-C"
    :description "Detrended annualized close-to-close volatility"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :annualization 365}
    :migrated-from :wave3}])

(defn get-volatility-indicators
  []
  volatility-indicator-definitions)

(def ^:private finite-number? imath/finite-number?)
(def ^:private parse-period imath/parse-period)
(def ^:private field-values imath/field-values)

(defn- sma-values
  [values period]
  (imath/sma-values values period :lagged))

(defn- stddev-values
  [values period]
  (imath/stddev-values values period :lagged))

(defn- rma-values
  [values period]
  (imath/rma-values values period :lagged))

(defn- calculate-52-week-high-low
  [data params]
  (let [weeks (parse-period (:period params) 52 1 260)
        lookback-seconds (* weeks seconds-per-week)
        time-values (imath/times data)
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
    (result/indicator-result :week-52-high-low
                             :overlay
                             [(result/line-series :high high-line)
                              (result/line-series :low low-line)])))

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

(defn- calculate-atr
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        highs (field-values data :high)
        lows (field-values data :low)
        closes (field-values data :close)
        tr-values (true-range-values highs lows closes)
        values (rma-values tr-values period)]
    (result/indicator-result :atr
                             :separate
                             [(result/line-series :atr values)])))

(defn- calculate-bollinger-bands
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        multiplier (or (:multiplier params) 2)
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
    (result/indicator-result :bollinger-bands
                             :overlay
                             [(result/line-series :upper upper-values)
                              (result/line-series :basis basis-values)
                              (result/line-series :lower lower-values)])))

(defn- calculate-standard-deviation
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (stddev-values (field-values data :close) period)]
    (result/indicator-result :standard-deviation
                             :separate
                             [(result/line-series :stddev values)])))

(defn- rs-rolling
  [values period]
  (let [size (count values)]
    (mapv (fn [idx]
            (when-let [window (imath/window-for-index values idx period :aligned)]
              (when (every? finite-number? window)
                (let [x-values (vec (range period))
                      x-mean (/ (reduce + 0 x-values) period)
                      y-mean (imath/mean window)
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

(defn- calculate-standard-error
  [data params]
  (let [period (parse-period (:period params) 20 3 400)
        regressions (rs-rolling (field-values data :close) period)
        values (mapv :standard-error regressions)]
    (result/indicator-result :standard-error
                             :separate
                             [(result/line-series :stderr values)])))

(defn- calculate-standard-error-bands
  [data params]
  (let [period (parse-period (:period params) 20 3 400)
        multiplier (or (:multiplier params) 2)
        regressions (rs-rolling (field-values data :close) period)
        center (mapv :center regressions)
        se (mapv :standard-error regressions)
        upper (mapv (fn [c s]
                      (when (and (finite-number? c)
                                 (finite-number? s))
                        (+ c (* multiplier s))))
                    center se)
        lower (mapv (fn [c s]
                      (when (and (finite-number? c)
                                 (finite-number? s))
                        (- c (* multiplier s))))
                    center se)]
    (result/indicator-result :standard-error-bands
                             :overlay
                             [(result/line-series :upper upper)
                              (result/line-series :center center)
                              (result/line-series :lower lower)])))

(defn- calculate-volatility-close-to-close
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        annualization (imath/parse-number (:annualization params) 365)
        close-values (field-values data :close)
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
                     (stddev-values log-returns period))]
    (result/indicator-result :volatility-close-to-close
                             :separate
                             [(result/line-series :vol-cc values)])))

(defn- calculate-volatility-index
  [data params]
  (let [period (parse-period (:period params) 14 2 400)
        atr (rma-values (true-range-values (field-values data :high)
                                           (field-values data :low)
                                           (field-values data :close))
                        period)
        close-values (field-values data :close)
        values (mapv (fn [a c]
                       (when (and (finite-number? a)
                                  (finite-number? c)
                                  (not= c 0))
                         (* 100 (/ a c))))
                     atr close-values)]
    (result/indicator-result :volatility-index
                             :separate
                             [(result/line-series :vol-index values)])))

(defn- calculate-volatility-ohlc
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        annualization (imath/parse-number (:annualization params) 365)
        opens-v (field-values data :open)
        highs-v (field-values data :high)
        lows-v (field-values data :low)
        closes-v (field-values data :close)
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
                     (sma-values rs period))]
    (result/indicator-result :volatility-ohlc
                             :separate
                             [(result/line-series :vol-ohlc values)])))

(defn- calculate-volatility-zero-trend-close-to-close
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        annualization (imath/parse-number (:annualization params) 365)
        close-values (field-values data :close)
        size (count data)
        trend (sma-values close-values period)
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
                     (stddev-values returns period))]
    (result/indicator-result :volatility-zero-trend-close-to-close
                             :separate
                             [(result/line-series :vol-zt-cc values)])))

(def ^:private volatility-calculators
  {:week-52-high-low calculate-52-week-high-low
   :atr calculate-atr
   :bollinger-bands calculate-bollinger-bands
   :standard-deviation calculate-standard-deviation
   :standard-error calculate-standard-error
   :standard-error-bands calculate-standard-error-bands
   :volatility-close-to-close calculate-volatility-close-to-close
   :volatility-index calculate-volatility-index
   :volatility-ohlc calculate-volatility-ohlc
   :volatility-zero-trend-close-to-close calculate-volatility-zero-trend-close-to-close})

(defn calculate-volatility-indicator
  [indicator-type data params]
  (let [config (or params {})
        calculator (get volatility-calculators indicator-type)]
    (when calculator
      (calculator data config))))
