(ns hyperopen.domain.trading.indicators.volatility.dispersion
  (:require [hyperopen.domain.trading.indicators.math.statistics :as mstats]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private finite-number? imath/finite-number?)
(def ^:private parse-period imath/parse-period)
(def ^:private parse-number imath/parse-number)
(def ^:private field-values imath/field-values)

(defn- sma-values
  [values period]
  (imath/sma-values values period :lagged))

(defn- stddev-values
  [values period]
  (imath/stddev-values values period :lagged))

(defn- sma-aligned-values
  [values period]
  (imath/sma-values values period :aligned))

(defn- stddev-aligned-values
  [values period]
  (imath/stddev-values values period :aligned))

(defn- bollinger-components-aligned
  [close-values period multiplier]
  (let [basis (sma-aligned-values close-values period)
        stdev-values (stddev-aligned-values close-values period)
        upper (mapv (fn [b s]
                      (when (and (finite-number? b)
                                 (finite-number? s))
                        (+ b (* multiplier s))))
                    basis stdev-values)
        lower (mapv (fn [b s]
                      (when (and (finite-number? b)
                                 (finite-number? s))
                        (- b (* multiplier s))))
                    basis stdev-values)]
    {:basis basis
     :upper upper
     :lower lower}))

(defn calculate-bollinger-bands
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

(defn calculate-bollinger-bands-percent-b
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        multiplier (parse-number (:multiplier params) 2)
        close-values (field-values data :close)
        {:keys [upper lower]} (bollinger-components-aligned close-values period multiplier)
        percent-b (mapv (fn [close u l]
                          (let [spread (- (or u 0) (or l 0))]
                            (when (and (finite-number? close)
                                       (finite-number? u)
                                       (finite-number? l)
                                       (not (zero? spread)))
                              (/ (- close l) spread))))
                        close-values upper lower)]
    (result/indicator-result :bollinger-bands-percent-b
                             :separate
                             [(result/line-series :percent-b percent-b)])))

(defn calculate-bollinger-bands-width
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        multiplier (parse-number (:multiplier params) 2)
        close-values (field-values data :close)
        {:keys [basis upper lower]} (bollinger-components-aligned close-values period multiplier)
        width (mapv (fn [b u l]
                      (when (and (finite-number? b)
                                 (finite-number? u)
                                 (finite-number? l)
                                 (not (zero? b)))
                        (/ (- u l) b)))
                    basis upper lower)]
    (result/indicator-result :bollinger-bands-width
                             :separate
                             [(result/line-series :bbw width)])))

(defn calculate-standard-deviation
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (stddev-values (field-values data :close) period)]
    (result/indicator-result :standard-deviation
                             :separate
                             [(result/line-series :stddev values)])))

(defn calculate-standard-error
  [data params]
  (let [period (parse-period (:period params) 20 3 400)
        regressions (mstats/rolling-regression (field-values data :close) period)
        values (mapv :standard-error regressions)]
    (result/indicator-result :standard-error
                             :separate
                             [(result/line-series :stderr values)])))

(defn calculate-standard-error-bands
  [data params]
  (let [period (parse-period (:period params) 20 3 400)
        multiplier (or (:multiplier params) 2)
        regressions (mstats/rolling-regression (field-values data :close) period)
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

(defn calculate-volatility-close-to-close
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

(defn calculate-volatility-ohlc
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

(defn calculate-volatility-zero-trend-close-to-close
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
