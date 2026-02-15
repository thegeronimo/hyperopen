(ns hyperopen.domain.trading.indicators.oscillators.momentum
  (:require [hyperopen.domain.trading.indicators.math-engine :as math-engine]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private finite-number? imath/finite-number?)
(def ^:private parse-period imath/parse-period)
(def ^:private field-values imath/field-values)
(def ^:private normalize-values imath/normalize-values)

(defn- sma-aligned-values
  [values period]
  (imath/sma-values values period :aligned))

(defn- rolling-sum-aligned
  [values period]
  (imath/rolling-sum values period :aligned))

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

(defn calculate-rate-of-change
  [data params]
  (let [period (parse-period (:period params) 9 1 400)
        close-values (field-values data :close)
        values (roc-percent-values close-values period)]
    (result/indicator-result :rate-of-change
                             :separate
                             [(result/line-series :roc values)])))

(defn calculate-momentum
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

(defn calculate-chande-momentum-oscillator
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

(defn calculate-detrended-price-oscillator
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

(defn calculate-price-oscillator
  [data params]
  (let [fast (parse-period (:fast params) 12 1 200)
        slow (parse-period (:slow params) 26 2 400)
        values (normalize-values
                (math-engine/absolute-price-oscillator (field-values data :close)
                                                        {:fast fast :slow slow}))]
    (result/indicator-result :price-oscillator
                             :separate
                             [(result/line-series :apo values)])))

(defn calculate-trix
  [data params]
  (let [period (parse-period (:period params) 15 2 400)
        values (normalize-values
                (math-engine/trix (field-values data :close)
                                   {:period period}))]
    (result/indicator-result :trix
                             :separate
                             [(result/line-series :trix values)])))

(defn calculate-williams-r
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        values (normalize-values
                (math-engine/williams-r (field-values data :high)
                                         (field-values data :low)
                                         (field-values data :close)
                                         {:period period}))]
    (result/indicator-result :williams-r
                             :separate
                             [(result/line-series :williams-r values)])))
