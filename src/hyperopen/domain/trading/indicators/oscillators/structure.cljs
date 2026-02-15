(ns hyperopen.domain.trading.indicators.oscillators.structure
  (:require [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.oscillators.helpers :as helpers]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private finite-number? helpers/finite-number?)
(def ^:private clamp imath/clamp)
(def ^:private parse-period helpers/parse-period)
(def ^:private parse-number helpers/parse-number)
(def ^:private field-values helpers/field-values)
(def ^:private sma-aligned-values helpers/sma-aligned-values)
(def ^:private rolling-sum-aligned helpers/rolling-sum-aligned)
(def ^:private rolling-max-aligned helpers/rolling-max-aligned)
(def ^:private rolling-min-aligned helpers/rolling-min-aligned)
(def ^:private rma-aligned-values helpers/rma-aligned-values)
(def ^:private stddev-aligned-values helpers/stddev-aligned-values)
(def ^:private roc-percent-values helpers/roc-percent-values)
(def ^:private rsi-aligned-values helpers/rsi-aligned-values)
(def ^:private true-range-values helpers/true-range-values)

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

(defn calculate-connors-rsi
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

(defn calculate-klinger-oscillator
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

(defn calculate-know-sure-thing
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

(defn calculate-coppock-curve
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

(defn calculate-fisher-transform
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
