(ns hyperopen.portfolio.metrics.returns
  (:refer-clojure :exclude [comp])
  (:require [hyperopen.portfolio.metrics.math :as math]
            [hyperopen.portfolio.metrics.history :as history]))

(def default-periods-per-year 252)

(defn interval-total-years
  [intervals]
  (reduce (fn [acc {:keys [dt-years]}]
            (+ acc dt-years))
          0
          intervals))

(defn interval-sum-log-returns
  [intervals]
  (reduce (fn [acc {:keys [log-return]}]
            (+ acc log-return))
          0
          intervals))

(defn interval-drift-rate
  [intervals]
  (let [t (interval-total-years intervals)]
    (when (pos? t)
      (/ (interval-sum-log-returns intervals) t))))

(defn interval-variance-rate
  [intervals]
  (let [n (count intervals)
        mu (interval-drift-rate intervals)]
    (when (and (> n 1)
               (number? mu))
      (let [acc (reduce (fn [sum {:keys [dt-years log-return]}]
                          (if (pos? dt-years)
                            (let [residual (- log-return (* mu dt-years))]
                              (+ sum (/ (* residual residual) dt-years)))
                            sum))
                        0
                        intervals)
            variance (/ acc (dec n))]
        (when (history/finite-number? variance)
          (max 0 variance))))))

(defn interval-cagr
  [intervals]
  (let [t (interval-total-years intervals)
        sum-log (interval-sum-log-returns intervals)]
    (when (and (pos? t)
               (history/finite-number? sum-log))
      (- (js/Math.exp (/ sum-log t)) 1))))

(defn annual-log-rate
  [rate]
  (if (and (number? rate)
           (> rate -1))
    (js/Math.log (+ 1 rate))
    0))

(defn volatility-ann-irregular
  [intervals]
  (when-let [variance-rate (interval-variance-rate intervals)]
    (js/Math.sqrt variance-rate)))

(defn sharpe-irregular
  [intervals rf]
  (let [mu (interval-drift-rate intervals)
        sigma (volatility-ann-irregular intervals)
        rf-log-rate (annual-log-rate rf)]
    (when (and (number? mu)
               (number? sigma)
               (pos? sigma))
      (/ (- mu rf-log-rate) sigma))))

(defn sortino-irregular
  [intervals mar]
  (let [n (count intervals)
        mu (interval-drift-rate intervals)
        mar-log-rate (annual-log-rate mar)
        downside (reduce (fn [{:keys [acc count]} {:keys [dt-years log-return]}]
                           (if (pos? dt-years)
                             (let [d (min 0 (- log-return (* mar-log-rate dt-years)))
                                   acc* (+ acc (/ (* d d) dt-years))
                                   count* (if (neg? d) (inc count) count)]
                               {:acc acc*
                                :count count*})
                             {:acc acc
                              :count count}))
                         {:acc 0
                          :count 0}
                         intervals)
        downside-dev (when (> n 1)
                       (js/Math.sqrt (/ (:acc downside) (dec n))))]
    (when (and (number? mu)
               (number? downside-dev)
               (pos? downside-dev))
      {:value (/ (- mu mar-log-rate) downside-dev)
       :downside-count (:count downside)})))

(defn interval-weighted-exposure
  [intervals]
  (let [total-years (interval-total-years intervals)]
    (when (pos? total-years)
      (let [active-years (reduce (fn [acc {:keys [dt-years simple-return]}]
                                   (if (> (js/Math.abs simple-return) history/epsilon)
                                     (+ acc dt-years)
                                     acc))
                                 0
                                 intervals)
            ratio (/ active-years total-years)]
        (/ (js/Math.ceil (* ratio 100))
           100)))))

(defn periodic-risk-free-rate
  [rf periods-per-year]
  (if (and (number? rf)
           (pos? rf)
           (number? periods-per-year)
           (pos? periods-per-year))
    (- (js/Math.pow (+ 1 rf)
                    (/ 1 periods-per-year))
       1)
    0))

(defn excess-returns
  [returns rf periods-per-year]
  (let [rf* (periodic-risk-free-rate rf periods-per-year)]
    (mapv (fn [value]
            (- value rf*))
          returns)))

(defn comp
  [returns]
  (when (seq returns)
    (let [total-factor (reduce (fn [acc value]
                                 (* acc (+ 1 value)))
                               1
                               returns)]
      (- total-factor 1))))

(defn time-in-market
  [returns]
  (let [n (count returns)]
    (when (pos? n)
      (let [exposure-ratio (/ (count (filter (complement zero?) returns))
                              n)]
        (/ (js/Math.ceil (* exposure-ratio 100))
           100)))))

(defn cagr
  ([returns]
   (cagr returns {}))
  ([returns {:keys [periods-per-year compounded years]
             :or {periods-per-year default-periods-per-year
                  compounded true}}]
   (let [n (count returns)]
     (when (pos? n)
       (let [total (if compounded
                     (comp returns)
                     (reduce + 0 returns))
             years* (if (and (number? years)
                             (pos? years))
                      years
                      (when (and (number? periods-per-year)
                                 (pos? periods-per-year))
                        (/ n periods-per-year)))]
         (when (and (number? total)
                    (number? years*)
                    (pos? years*))
           (- (js/Math.pow (js/Math.abs (+ total 1))
                           (/ 1 years*))
              1)))))))

(defn volatility
  ([returns]
   (volatility returns {}))
  ([returns {:keys [periods-per-year annualize]
             :or {periods-per-year default-periods-per-year
                  annualize true}}]
   (when-let [std (math/sample-stddev returns)]
     (if annualize
       (* std (js/Math.sqrt periods-per-year))
       std))))

(defn autocorr-penalty
  [returns]
  (let [returns* (vec returns)
        n (count returns*)]
    (if (< n 2)
      1
      (let [coef (js/Math.abs (or (math/pearson-correlation (subvec returns* 0 (dec n))
                                                        (subvec returns* 1 n))
                                  0))
            corr-sum (reduce + 0
                             (map (fn [x]
                                    (* (/ (- n x) n)
                                       (js/Math.pow coef x)))
                                  (range 1 n)))]
        (js/Math.sqrt (+ 1 (* 2 corr-sum)))))))

(defn sharpe
  ([returns]
   (sharpe returns {}))
  ([returns {:keys [rf periods-per-year annualize smart]
             :or {rf 0
                  periods-per-year default-periods-per-year
                  annualize true
                  smart false}}]
   (let [returns* (excess-returns returns rf periods-per-year)
         denominator (math/sample-stddev returns*)
         denominator* (if smart
                        (some-> denominator (* (autocorr-penalty returns*)))
                        denominator)
         numerator (math/mean returns*)]
     (when (and (number? numerator)
                (number? denominator*)
                (pos? denominator*))
       (let [ratio (/ numerator denominator*)]
         (if annualize
           (* ratio (js/Math.sqrt periods-per-year))
           ratio))))))

(defn smart-sharpe
  ([returns]
   (smart-sharpe returns {}))
  ([returns opts]
   (sharpe returns (assoc opts :smart true))))

(defn sortino
  ([returns]
   (sortino returns {}))
  ([returns {:keys [rf periods-per-year annualize smart]
             :or {rf 0
                  periods-per-year default-periods-per-year
                  annualize true
                  smart false}}]
   (let [returns* (excess-returns returns rf periods-per-year)
         n (count returns*)
         downside-sum (->> returns*
                           (filter neg?)
                           (map #(* % %))
                           (reduce + 0))
         downside (when (pos? n)
                    (js/Math.sqrt (/ downside-sum n)))
         downside* (if smart
                     (some-> downside (* (autocorr-penalty returns*)))
                     downside)
         numerator (math/mean returns*)]
     (when (and (number? numerator)
                (number? downside*)
                (pos? downside*))
       (let [ratio (/ numerator downside*)]
         (if annualize
           (* ratio (js/Math.sqrt periods-per-year))
           ratio))))))

(defn smart-sortino
  ([returns]
   (smart-sortino returns {}))
  ([returns opts]
   (sortino returns (assoc opts :smart true))))

(defn probabilistic-sharpe-ratio
  ([returns]
   (probabilistic-sharpe-ratio returns {}))
  ([returns {:keys [rf periods-per-year annualize smart]
             :or {rf 0
                  periods-per-year default-periods-per-year
                  annualize false
                  smart false}}]
   (let [base (sharpe returns {:rf rf
                               :periods-per-year periods-per-year
                               :annualize false
                               :smart smart})
         skew* (math/skew returns)
         kurtosis* (math/kurtosis returns)
         n (count returns)]
     (when (and (number? base)
                (number? skew*)
                (number? kurtosis*)
                (> n 1))
       (let [sigma-sr-sq (/ (+ 1
                               (* 0.5 (* base base))
                               (- (* skew* base))
                               (* (/ (- kurtosis* 3) 4)
                                  (* base base)))
                            (dec n))]
         (when (pos? sigma-sr-sq)
           (let [sigma-sr (js/Math.sqrt sigma-sr-sq)
                 ratio (/ (- base rf) sigma-sr)
                 psr (math/normal-cdf ratio)]
             (if annualize
               (* psr (js/Math.sqrt periods-per-year))
               psr))))))))

(defn omega
  ([returns]
   (omega returns {}))
  ([returns {:keys [rf required-return periods-per-year]
             :or {rf 0
                  required-return 0
                  periods-per-year default-periods-per-year}}]
   (when (and (>= (count returns) 2)
              (> required-return -1))
     (let [returns* (excess-returns returns rf periods-per-year)
           threshold (if (= periods-per-year 1)
                       required-return
                       (- (js/Math.pow (+ 1 required-return)
                                       (/ 1 periods-per-year))
                          1))
           deviations (mapv #(- % threshold) returns*)
           numer (reduce + 0 (filter pos? deviations))
           denom (- (reduce + 0 (filter neg? deviations)))]
       (when (pos? denom)
         (/ numer denom))))))