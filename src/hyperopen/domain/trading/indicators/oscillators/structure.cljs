(ns hyperopen.domain.trading.indicators.oscillators.structure
  (:require [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.oscillators.helpers :as helpers]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private finite-number? helpers/finite-number?)
(def ^:private parse-period helpers/parse-period)
(def ^:private parse-number helpers/parse-number)
(def ^:private field-values helpers/field-values)
(def ^:private sma-aligned-values helpers/sma-aligned-values)
(def ^:private rolling-sum-aligned helpers/rolling-sum-aligned)
(def ^:private rolling-max-aligned helpers/rolling-max-aligned)
(def ^:private rolling-min-aligned helpers/rolling-min-aligned)
(def ^:private rma-aligned-values helpers/rma-aligned-values)
(def ^:private stddev-aligned-values helpers/stddev-aligned-values)
(def ^:private true-range-values helpers/true-range-values)

(defn calculate-advance-decline
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

(defn calculate-chaikin-volatility
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

(defn calculate-chande-kroll-stop
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

(defn calculate-chop-zone
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

(defn calculate-majority-rule
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

(defn calculate-ratio
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

(defn calculate-spread
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

(defn calculate-relative-vigor-index
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

(defn calculate-relative-volatility-index
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

(defn calculate-choppiness-index
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
