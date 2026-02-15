(ns hyperopen.domain.trading.indicators.volatility.channels
  (:require [hyperopen.domain.trading.indicators.math-engine :as math-engine]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private finite-number? imath/finite-number?)
(def ^:private parse-period imath/parse-period)
(def ^:private parse-number imath/parse-number)
(def ^:private field-values imath/field-values)
(def ^:private normalize-values imath/normalize-values)

(defn- rolling-max-aligned
  [values period]
  (imath/rolling-max values period :aligned))

(defn- rolling-min-aligned
  [values period]
  (imath/rolling-min values period :aligned))

(defn- sma-aligned-values
  [values period]
  (imath/sma-values values period :aligned))

(defn- stddev-aligned-values
  [values period]
  (imath/stddev-values values period :aligned))

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

(defn- range-channel-series
  [high-values low-values period]
  (let [upper (rolling-max-aligned high-values period)
        lower (rolling-min-aligned low-values period)
        middle (mapv (fn [u l]
                       (when (and (finite-number? u)
                                  (finite-number? l))
                         (/ (+ u l) 2)))
                     upper lower)]
    {:upper upper
     :middle middle
     :lower lower}))

(defn calculate-donchian-channels
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        {:keys [upper middle lower]}
        (range-channel-series (field-values data :high)
                              (field-values data :low)
                              period)]
    (result/indicator-result :donchian-channels
                             :overlay
                             [(result/line-series :upper upper)
                              (result/line-series :middle middle)
                              (result/line-series :lower lower)])))

(defn calculate-price-channel
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        {:keys [upper middle lower]}
        (range-channel-series (field-values data :high)
                              (field-values data :low)
                              period)]
    (result/indicator-result :price-channel
                             :overlay
                             [(result/line-series :upper upper)
                              (result/line-series :middle middle)
                              (result/line-series :lower lower)])))

(defn calculate-historical-volatility
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        annualization (parse-number (:annualization params) 365)
        returns (log-return-values (field-values data :close))
        std-values (stddev-aligned-values returns period)
        hv-values (mapv (fn [value]
                          (when (finite-number? value)
                            (* value (js/Math.sqrt annualization) 100)))
                        std-values)]
    (result/indicator-result :historical-volatility
                             :separate
                             [(result/line-series :hv hv-values)])))

(defn calculate-keltner-channels
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        result (math-engine/keltner-channels (field-values data :high)
                                              (field-values data :low)
                                              (field-values data :close)
                                              {:period period})]
    (result/indicator-result :keltner-channels
                             :overlay
                             [(result/line-series :upper (normalize-values (:upper result)))
                              (result/line-series :middle (normalize-values (:middle result)))
                              (result/line-series :lower (normalize-values (:lower result)))])))

(defn calculate-moving-average-channel
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        multiplier (parse-number (:multiplier params) 1.5)
        close-values (field-values data :close)
        basis (sma-aligned-values close-values period)
        spread (stddev-aligned-values close-values period)
        upper (mapv (fn [b s]
                      (when (and (finite-number? b)
                                 (finite-number? s))
                        (+ b (* multiplier s))))
                    basis spread)
        lower (mapv (fn [b s]
                      (when (and (finite-number? b)
                                 (finite-number? s))
                        (- b (* multiplier s))))
                    basis spread)]
    (result/indicator-result :moving-average-channel
                             :overlay
                             [(result/line-series :upper upper)
                              (result/line-series :basis basis)
                              (result/line-series :lower lower)])))
