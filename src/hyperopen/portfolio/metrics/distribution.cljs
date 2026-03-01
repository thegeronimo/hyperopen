(ns hyperopen.portfolio.metrics.distribution
  (:require [hyperopen.portfolio.metrics.math :as math]
            [hyperopen.portfolio.metrics.history :as history]
            [hyperopen.portfolio.metrics.returns :as returns]))

(defn- group-key
  [day period]
  (case period
    :day day
    :month (subs day 0 7)
    :year (subs day 0 4)
    day))

(defn aggregate-period-returns
  [daily-rows period compounded]
  (let [rows (history/normalize-daily-rows daily-rows)
        grouped (reduce (fn [acc {:keys [day return]}]
                          (update acc (group-key day period) (fnil conj []) return))
                        (sorted-map)
                        rows)]
    (mapv (fn [[_ values]]
            (if compounded
              (returns/comp values)
              (reduce + 0 values)))
          grouped)))

(defn expected-return
  ([daily-rows]
   (expected-return daily-rows {}))
  ([daily-rows {:keys [period compounded]
                :or {period :day
                     compounded true}}]
   (let [returns (aggregate-period-returns daily-rows period compounded)
         n (count returns)]
     (when (pos? n)
       (- (js/Math.pow (reduce (fn [acc value]
                                 (* acc (+ 1 value)))
                               1
                               returns)
                       (/ 1 n))
          1)))))

(defn win-rate
  [returns]
  (let [non-zero (vec (filter (complement zero?) returns))]
    (if (seq non-zero)
      (/ (count (filter pos? returns))
         (count non-zero))
      0)))

(defn- avg-win
  [returns]
  (math/mean (filter pos? returns)))

(defn- avg-loss
  [returns]
  (math/mean (filter neg? returns)))

(defn payoff-ratio
  [returns]
  (let [loss (avg-loss returns)
        win (avg-win returns)]
    (when (and (number? loss)
               (number? win)
               (not (zero? loss)))
      (/ win (js/Math.abs loss)))))

(defn kelly-criterion
  [returns]
  (let [win-loss (payoff-ratio returns)
        win-prob (win-rate returns)
        lose-prob (- 1 win-prob)]
    (when (and (number? win-loss)
               (not (zero? win-loss)))
      (/ (- (* win-loss win-prob) lose-prob)
         win-loss))))

(defn risk-of-ruin
  [returns]
  (let [wins (win-rate returns)
        n (count returns)]
    (when (pos? n)
      (js/Math.pow (/ (- 1 wins)
                      (+ 1 wins))
                   n))))

(defn value-at-risk
  ([returns]
   (value-at-risk returns {}))
  ([returns {:keys [sigma confidence]
             :or {sigma 1
                  confidence 0.95}}]
   (let [mu (math/mean returns)
         std (math/sample-stddev returns)
         confidence* (if (> confidence 1)
                       (/ confidence 100)
                       confidence)
         quantile-p (max 1e-9 (min 0.999999 (- 1 confidence*)))]
     (when (and (number? mu)
                (number? std))
       (+ mu (* sigma std (math/inverse-normal-cdf quantile-p)))))))

(defn expected-shortfall
  ([returns]
   (expected-shortfall returns {}))
  ([returns {:keys [sigma confidence]
             :or {sigma 1
                  confidence 0.95}}]
   (when-let [var* (value-at-risk returns {:sigma sigma
                                           :confidence confidence})]
     (let [tail-values (filter #(< % var*) returns)]
       (if (seq tail-values)
         (math/mean tail-values)
         var*)))))

(defn- longest-streak
  [returns pred]
  (loop [remaining returns
         current 0
         best 0]
    (if (empty? remaining)
      best
      (if (pred (first remaining))
        (let [next-current (inc current)]
          (recur (rest remaining)
                 next-current
                 (max best next-current)))
        (recur (rest remaining) 0 best)))))

(defn consecutive-wins
  [returns]
  (longest-streak returns pos?))

(defn consecutive-losses
  [returns]
  (longest-streak returns neg?))

(defn gain-to-pain-ratio
  ([daily-rows]
   (gain-to-pain-ratio daily-rows :day))
  ([daily-rows period]
   (let [returns (aggregate-period-returns daily-rows period false)
         downside (js/Math.abs (reduce + 0 (filter neg? returns)))]
     (when (pos? downside)
       (/ (reduce + 0 returns)
          downside)))))

(defn profit-factor
  [returns]
  (let [wins (reduce + 0 (filter #(>= % 0) returns))
        losses (js/Math.abs (reduce + 0 (filter neg? returns)))]
    (cond
      (and (zero? wins)
           (zero? losses)) 0
      (zero? losses) js/Number.POSITIVE_INFINITY
      :else (/ wins losses))))

(defn tail-ratio
  [returns]
  (let [upper (math/quantile returns 0.95)
        lower (math/quantile returns 0.05)]
    (when (and (number? upper)
               (number? lower)
               (not (zero? lower)))
      (js/Math.abs (/ upper lower)))))

(defn common-sense-ratio
  [returns]
  (let [profit-factor* (profit-factor returns)
        tail-ratio* (tail-ratio returns)]
    (when (and (number? profit-factor*)
               (number? tail-ratio*))
      (* profit-factor* tail-ratio*))))

(defn cpc-index
  [returns]
  (let [profit-factor* (profit-factor returns)
        win-rate* (win-rate returns)
        win-loss* (payoff-ratio returns)]
    (when (and (number? profit-factor*)
               (number? win-rate*)
               (number? win-loss*))
      (* profit-factor*
         win-rate*
         win-loss*))))

(defn outlier-win-ratio
  [returns]
  (let [positive-mean (math/mean (filter #(>= % 0) returns))
        q99 (math/quantile returns 0.99)]
    (when (and (number? positive-mean)
               (number? q99)
               (not (zero? positive-mean)))
      (/ q99 positive-mean))))

(defn outlier-loss-ratio
  [returns]
  (let [negative-mean (math/mean (filter neg? returns))
        q1 (math/quantile returns 0.01)]
    (when (and (number? negative-mean)
               (number? q1)
               (not (zero? negative-mean)))
      (/ q1 negative-mean))))

(defn r-squared
  [strategy-returns benchmark-returns]
  (when-let [corr (math/pearson-correlation strategy-returns benchmark-returns)]
    (* corr corr)))

(defn information-ratio
  [strategy-returns benchmark-returns]
  (let [diff (mapv - strategy-returns benchmark-returns)
        std (math/sample-stddev diff)]
    (if (and (number? std)
             (pos? std))
      (/ (or (math/mean diff) 0)
         std)
      0)))