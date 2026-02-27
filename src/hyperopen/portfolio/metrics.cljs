(ns hyperopen.portfolio.metrics
  (:refer-clojure :exclude [comp])
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.projections :as projections]))

(defn- optional-number [value]
  (projections/parse-optional-num value))

(defn- finite-number? [value]
  (and (number? value)
       (js/isFinite value)))

(defn history-point-value [row]
  (cond
    (and (sequential? row)
         (>= (count row) 2))
    (optional-number (second row))

    (map? row)
    (or (optional-number (:value row))
        (optional-number (:pnl row))
        (optional-number (:account-value row))
        (optional-number (:accountValue row)))

    :else
    nil))

(defn history-point-time-ms [row]
  (cond
    (and (sequential? row)
         (seq row))
    (optional-number (first row))

    (map? row)
    (or (optional-number (:time row))
        (optional-number (:timestamp row))
        (optional-number (:time-ms row))
        (optional-number (:timeMs row))
        (optional-number (:ts row))
        (optional-number (:t row)))

    :else
    nil))

(defn- history-points
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [time-ms (history-point-time-ms row)
                     value (history-point-value row)]
                 (when (and (finite-number? time-ms)
                            (finite-number? value))
                   {:time-ms time-ms
                    :value value}))))
       (sort-by :time-ms)
       vec))

(defn- dedupe-history-points-by-time
  [points]
  (reduce (fn [acc {:keys [time-ms] :as point}]
            ;; Keep last observation when multiple values share the same timestamp.
            (if (and (seq acc)
                     (= (:time-ms (peek acc)) time-ms))
              (conj (pop acc) point)
              (conj acc point)))
          []
          points))

(defn- aligned-account-pnl-points
  [summary]
  (let [account-points (-> (:accountValueHistory summary)
                           history-points
                           dedupe-history-points-by-time)
        pnl-by-time (->> (:pnlHistory summary)
                         history-points
                         dedupe-history-points-by-time
                         (map (juxt :time-ms :value))
                         (into {}))]
    (->> account-points
         (keep (fn [{:keys [time-ms value]}]
                 (when-let [pnl-value (get pnl-by-time time-ms)]
                   {:time-ms time-ms
                    :account-value value
                    :pnl-value pnl-value})))
         vec)))

(defn returns-history-rows
  [_state summary _summary-scope]
  (let [points (aligned-account-pnl-points summary)
        anchor-index (first (keep-indexed (fn [idx {:keys [account-value]}]
                                            (when (pos? account-value)
                                              idx))
                                          points))]
    (if (number? anchor-index)
      (let [points* (subvec points anchor-index)]
        (if (seq points*)
          (loop [idx 1
                 previous (first points*)
                 cumulative-factor 1
                 output [[(:time-ms (first points*)) 0]]
                 point-count (count points*)]
            (if (>= idx point-count)
              output
              (let [current (nth points* idx)
                    delta-account (- (:account-value current)
                                     (:account-value previous))
                    delta-pnl (- (:pnl-value current)
                                 (:pnl-value previous))
                    implied-cash-flow (- delta-account delta-pnl)
                    denominator (+ (:account-value previous)
                                   (* 0.5 implied-cash-flow))
                    period-return (if (and (finite-number? denominator)
                                           (pos? denominator))
                                    (/ delta-pnl denominator)
                                    0)
                    period-return* (if (finite-number? period-return)
                                     (max -0.999999 period-return)
                                     0)
                    cumulative-factor* (* cumulative-factor (+ 1 period-return*))
                    cumulative-percent (* 100 (- cumulative-factor* 1))
                    cumulative-percent* (if (finite-number? cumulative-percent)
                                          cumulative-percent
                                          (* 100 (- cumulative-factor 1)))]
                (recur (inc idx)
                       current
                       cumulative-factor*
                       (conj output [(:time-ms current) cumulative-percent*])
                       point-count))))
          []))
      [])))

(defn cumulative-percent-rows->interval-returns
  [cumulative-percent-rows]
  (let [rows (->> (or cumulative-percent-rows [])
                  (keep (fn [row]
                          (let [time-ms (history-point-time-ms row)
                                value (history-point-value row)]
                            (when (and (number? time-ms)
                                       (finite-number? value))
                              {:time-ms time-ms
                               :value value}))))
                  (sort-by :time-ms)
                  vec)
        count* (count rows)]
    (if (< count* 2)
      []
      (loop [idx 1
             previous (first rows)
             output []]
        (if (>= idx count*)
          output
          (let [current (nth rows idx)
                previous-ratio (/ (:value previous) 100)
                current-ratio (/ (:value current) 100)
                denominator (+ 1 previous-ratio)
                period-return (if (and (finite-number? denominator)
                                       (pos? denominator))
                                (- (/ (+ 1 current-ratio) denominator) 1)
                                0)
                period-return* (if (finite-number? period-return)
                                 period-return
                                 0)]
            (recur (inc idx)
                   current
                   (conj output
                         {:time-ms (:time-ms current)
                          :return period-return*}))))))))

(defn- utc-day-key [time-ms]
  (subs (.toISOString (js/Date. time-ms)) 0 10))

(defn daily-compounded-returns
  [cumulative-percent-rows]
  (let [rows (cumulative-percent-rows->interval-returns cumulative-percent-rows)]
    (if (empty? rows)
      []
      (loop [remaining rows
             current-day nil
             current-factor 1
             current-time-ms nil
             output []]
        (if (empty? remaining)
          (if (some? current-day)
            (conj output
                  {:day current-day
                   :time-ms current-time-ms
                   :return (- current-factor 1)})
            output)
          (let [{:keys [time-ms return]} (first remaining)
                day (utc-day-key time-ms)
                factor (+ 1 return)]
            (if (= day current-day)
              (recur (rest remaining)
                     current-day
                     (* current-factor factor)
                     time-ms
                     output)
              (recur (rest remaining)
                     day
                     factor
                     time-ms
                     (if (some? current-day)
                       (conj output
                             {:day current-day
                              :time-ms current-time-ms
                              :return (- current-factor 1)})
                       output)))))))))

(defn strategy-daily-compounded-returns
  [state summary summary-scope]
  (daily-compounded-returns (returns-history-rows state summary summary-scope)))

(def ^:private day-ms
  (* 24 60 60 1000))

(def ^:private default-periods-per-year
  252)

(def ^:private ms-per-year
  (* 365.2425 24 60 60 1000))

(def ^:private epsilon
  1e-12)

(def ^:private default-quality-gates
  {:core-min-intervals 10
   :core-min-span-days 30
   :core-high-min-intervals 20
   :core-high-min-span-days 90
   :core-high-max-cv-gap 1.5
   :core-high-max-gap-days 30
   :sortino-min-intervals 20
   :sortino-min-downside 5
   :daily-min-points 60
   :daily-min-coverage 0.90
   :daily-max-missing-streak 3
   :psr-min-points 252
   :drawdown-max-gap-days 2
   :drawdown-min-daily-points 180
   :benchmark-min-points 10
   :rolling-min-fraction 0.5
   :structural-gap-days 90})

(declare mean sample-stddev quantile)

(defn- day-string-from-ms
  [time-ms]
  (subs (.toISOString (js/Date. time-ms)) 0 10))

(defn- parse-day-ms
  [day]
  (when (string? day)
    (let [ms (.getTime (js/Date. (str day "T00:00:00.000Z")))]
      (when (and (number? ms)
                 (not (js/isNaN ms)))
        ms))))

(defn- clamp-near-zero
  [value]
  (if (< (js/Math.abs value) epsilon)
    0
    value))

(defn normalize-daily-rows
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [return (or (optional-number (:return row))
                                (history-point-value row))
                     time-ms (or (optional-number (:time-ms row))
                                 (history-point-time-ms row))
                     day (or (some-> (:day row) str str/trim)
                             (when (number? time-ms)
                               (day-string-from-ms time-ms)))]
                 (when (and (finite-number? return)
                            (number? time-ms)
                            (seq day))
                   {:day day
                    :time-ms time-ms
                    :return return}))))
       (sort-by :time-ms)
       vec))

(defn- returns-values
  [daily-rows]
  (->> daily-rows
       (map :return)
       (filter finite-number?)
       vec))

(defn- normalize-cumulative-percent-rows
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [time-ms (history-point-time-ms row)
                     percent (history-point-value row)
                     factor (when (finite-number? percent)
                              (+ 1 (/ percent 100)))]
                 (when (and (number? time-ms)
                            (finite-number? percent)
                            (finite-number? factor)
                            (pos? factor))
                   {:time-ms time-ms
                    :percent percent
                    :factor factor}))))
       (sort-by :time-ms)
       (reduce (fn [acc row]
                 (if (and (seq acc)
                          (= (:time-ms (peek acc))
                             (:time-ms row)))
                   (conj (pop acc) row)
                   (conj acc row)))
               [])
       vec))

(defn- daily-rows->cumulative-percent-rows
  [daily-rows]
  (let [rows (normalize-daily-rows daily-rows)]
    (if (seq rows)
      (let [anchor-time-ms (- (:time-ms (first rows)) day-ms)]
        (loop [remaining rows
               cumulative-factor 1
               output [[anchor-time-ms 0]]]
          (if (empty? remaining)
            output
            (let [{:keys [time-ms return]} (first remaining)
                  factor (if (finite-number? return)
                           (+ 1 return)
                           1)
                  cumulative-factor* (* cumulative-factor factor)
                  cumulative-percent (* 100 (- cumulative-factor* 1))]
              (recur (rest remaining)
                     cumulative-factor*
                     (conj output [time-ms cumulative-percent]))))))
      [])))

(defn- cumulative-rows->irregular-intervals
  [cumulative-rows]
  (let [rows (normalize-cumulative-percent-rows cumulative-rows)]
    (if (< (count rows) 2)
      []
      (loop [idx 1
             previous (first rows)
             output []]
        (if (>= idx (count rows))
          output
          (let [current (nth rows idx)
                start-ms (:time-ms previous)
                end-ms (:time-ms current)
                dt-ms (- end-ms start-ms)
                dt-years (/ dt-ms ms-per-year)
                prev-factor (:factor previous)
                curr-factor (:factor current)
                ratio (if (and (finite-number? prev-factor)
                               (finite-number? curr-factor)
                               (pos? prev-factor))
                        (/ curr-factor prev-factor)
                        nil)
                simple-return (when (and (number? ratio)
                                         (finite-number? ratio))
                                (- ratio 1))
                log-return (when (and (number? ratio)
                                      (finite-number? ratio)
                                      (pos? ratio))
                             (js/Math.log ratio))]
            (recur (inc idx)
                   current
                   (if (and (number? dt-years)
                            (pos? dt-years)
                            (finite-number? dt-years)
                            (number? simple-return)
                            (finite-number? simple-return)
                            (number? log-return)
                            (finite-number? log-return))
                     (conj output
                           {:start-ms start-ms
                            :end-ms end-ms
                            :dt-ms dt-ms
                            :dt-days (/ dt-ms day-ms)
                            :dt-years dt-years
                            :simple-return simple-return
                            :log-return log-return})
                     output))))))))

(defn- interval-total-years
  [intervals]
  (reduce (fn [acc {:keys [dt-years]}]
            (+ acc dt-years))
          0
          intervals))

(defn- interval-sum-log-returns
  [intervals]
  (reduce (fn [acc {:keys [log-return]}]
            (+ acc log-return))
          0
          intervals))

(defn- interval-drift-rate
  [intervals]
  (let [t (interval-total-years intervals)]
    (when (pos? t)
      (/ (interval-sum-log-returns intervals) t))))

(defn- interval-variance-rate
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
        (when (finite-number? variance)
          (max 0 variance))))))

(defn- interval-cagr
  [intervals]
  (let [t (interval-total-years intervals)
        sum-log (interval-sum-log-returns intervals)]
    (when (and (pos? t)
               (finite-number? sum-log))
      (- (js/Math.exp (/ sum-log t)) 1))))

(defn- annual-log-rate
  [rate]
  (if (and (number? rate)
           (> rate -1))
    (js/Math.log (+ 1 rate))
    0))

(defn- volatility-ann-irregular
  [intervals]
  (when-let [variance-rate (interval-variance-rate intervals)]
    (js/Math.sqrt variance-rate)))

(defn- sharpe-irregular
  [intervals rf]
  (let [mu (interval-drift-rate intervals)
        sigma (volatility-ann-irregular intervals)
        rf-log-rate (annual-log-rate rf)]
    (when (and (number? mu)
               (number? sigma)
               (pos? sigma))
      (/ (- mu rf-log-rate) sigma))))

(defn- sortino-irregular
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

(defn- interval-weighted-exposure
  [intervals]
  (let [total-years (interval-total-years intervals)]
    (when (pos? total-years)
      (let [active-years (reduce (fn [acc {:keys [dt-years simple-return]}]
                                   (if (> (js/Math.abs simple-return) epsilon)
                                     (+ acc dt-years)
                                     acc))
                                 0
                                 intervals)
            ratio (/ active-years total-years)]
        (/ (js/Math.ceil (* ratio 100))
           100)))))

(defn- daily-max-missing-streak
  [daily-rows]
  (let [day-indices (->> (normalize-daily-rows daily-rows)
                         (map (fn [{:keys [time-ms]}]
                                (js/Math.floor (/ time-ms day-ms))))
                         distinct
                         sort
                         vec)]
    (if (< (count day-indices) 2)
      0
      (reduce (fn [best [left right]]
                (max best (max 0 (dec (- right left)))))
              0
              (partition 2 1 day-indices)))))

(defn- cadence-diagnostics
  [intervals daily-rows mar]
  (let [interval-count (count intervals)
        total-years (interval-total-years intervals)
        span-days (* total-years 365.2425)
        gap-days (mapv :dt-days intervals)
        median-gap (when (seq gap-days) (quantile gap-days 0.5))
        p95-gap (when (seq gap-days) (quantile gap-days 0.95))
        max-gap (when (seq gap-days) (apply max gap-days))
        mean-gap (mean gap-days)
        gap-std (sample-stddev gap-days)
        cv-gap (if (and (number? mean-gap)
                        (pos? mean-gap)
                        (number? gap-std))
                 (/ gap-std mean-gap)
                 nil)
        downside-threshold (annual-log-rate mar)
        downside-count (count (filter (fn [{:keys [log-return dt-years]}]
                                        (< log-return (* downside-threshold dt-years)))
                                      intervals))
        normalized-daily (normalize-daily-rows daily-rows)
        daily-points (count normalized-daily)
        total-calendar-days (if (pos? span-days)
                              (max 1 (inc (int (js/Math.floor span-days))))
                              0)
        daily-coverage (if (pos? total-calendar-days)
                         (/ daily-points total-calendar-days)
                         0)
        obs-density-per-week (if (pos? span-days)
                               (/ interval-count (/ span-days 7))
                               0)]
    {:interval-count interval-count
     :span-days span-days
     :total-years total-years
     :median-gap-days median-gap
     :p95-gap-days p95-gap
     :max-gap-days max-gap
     :cv-gap cv-gap
     :downside-count downside-count
     :daily-points daily-points
     :daily-coverage daily-coverage
     :daily-max-missing-streak (daily-max-missing-streak normalized-daily)
     :obs-density-per-week obs-density-per-week}))

(defn- compute-quality-gates
  [diagnostics gates]
  (let [{:keys [interval-count
                span-days
                cv-gap
                max-gap-days
                downside-count
                daily-points
                daily-coverage
                daily-max-missing-streak]} diagnostics]
    {:core-min? (and (>= interval-count (:core-min-intervals gates))
                     (>= span-days (:core-min-span-days gates)))
     :core-high-confidence? (and (>= interval-count (:core-high-min-intervals gates))
                                 (>= span-days (:core-high-min-span-days gates))
                                 (number? cv-gap)
                                 (<= cv-gap (:core-high-max-cv-gap gates))
                                 (number? max-gap-days)
                                 (<= max-gap-days (:core-high-max-gap-days gates)))
     :sortino-min? (and (>= interval-count (:sortino-min-intervals gates))
                        (>= downside-count (:sortino-min-downside gates)))
     :daily-min? (and (>= daily-points (:daily-min-points gates))
                      (>= daily-coverage (:daily-min-coverage gates))
                      (<= daily-max-missing-streak (:daily-max-missing-streak gates)))
     :psr-min? (>= daily-points (:psr-min-points gates))
     :drawdown-reliable? (and (number? max-gap-days)
                              (<= max-gap-days (:drawdown-max-gap-days gates))
                              (>= daily-points (:drawdown-min-daily-points gates)))
     :structural-gap? (and (number? max-gap-days)
                           (> max-gap-days (:structural-gap-days gates)))}))

(defn- mean
  [values]
  (when (seq values)
    (/ (reduce + 0 values)
       (count values))))

(defn- sample-variance
  [values]
  (let [n (count values)
        avg (mean values)]
    (when (and (number? avg)
               (> n 1))
      (/ (reduce + 0
                 (map (fn [value]
                        (let [delta (- value avg)]
                          (* delta delta)))
                      values))
         (dec n)))))

(defn- sample-stddev
  [values]
  (when-let [variance (sample-variance values)]
    (js/Math.sqrt variance)))

(defn- periodic-risk-free-rate
  [rf periods-per-year]
  (if (and (number? rf)
           (pos? rf)
           (number? periods-per-year)
           (pos? periods-per-year))
    (- (js/Math.pow (+ 1 rf)
                    (/ 1 periods-per-year))
       1)
    0))

(defn- excess-returns
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
   (when-let [std (sample-stddev returns)]
     (if annualize
       (* std (js/Math.sqrt periods-per-year))
       std))))

(defn- pearson-correlation
  [xs ys]
  (let [n (count xs)]
    (when (and (= n (count ys))
               (> n 1))
      (let [mx (mean xs)
            my (mean ys)
            cov (reduce + 0
                        (map (fn [x y]
                               (* (- x mx) (- y my)))
                             xs ys))
            sx (reduce + 0
                       (map (fn [x]
                              (let [delta (- x mx)]
                                (* delta delta)))
                            xs))
            sy (reduce + 0
                       (map (fn [y]
                              (let [delta (- y my)]
                                (* delta delta)))
                            ys))
            denom (js/Math.sqrt (* sx sy))]
        (when (and (finite-number? denom)
                   (pos? denom))
          (/ cov denom))))))

(defn- autocorr-penalty
  [returns]
  (let [returns* (vec returns)
        n (count returns*)]
    (if (< n 2)
      1
      (let [coef (js/Math.abs (or (pearson-correlation (subvec returns* 0 (dec n))
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
         denominator (sample-stddev returns*)
         denominator* (if smart
                        (some-> denominator (* (autocorr-penalty returns*)))
                        denominator)
         numerator (mean returns*)]
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
         numerator (mean returns*)]
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

(defn- sample-skewness
  [returns]
  (let [values (vec returns)
        n (count values)]
    (when (> n 2)
      (let [avg (mean values)
            centered (mapv #(- % avg) values)
            m2 (/ (reduce + 0 (map #(* % %) centered)) n)
            m3 (/ (reduce + 0 (map #(* % % %) centered)) n)]
        (when (pos? m2)
          (let [g1 (/ m3 (js/Math.pow m2 1.5))]
            (* (/ (js/Math.sqrt (* n (dec n)))
                  (- n 2))
               g1)))))))

(defn skew
  [returns]
  (sample-skewness returns))

(defn- sample-kurtosis-excess
  [returns]
  (let [values (vec returns)
        n (count values)]
    (when (> n 3)
      (let [avg (mean values)
            centered (mapv #(- % avg) values)
            m2 (/ (reduce + 0 (map #(* % %) centered)) n)
            m4 (/ (reduce + 0 (map #(js/Math.pow % 4) centered)) n)]
        (when (pos? m2)
          (let [g2 (- (/ m4 (* m2 m2)) 3)]
            (* (/ (dec n)
                  (* (- n 2) (- n 3)))
               (+ (* (inc n) g2) 6))))))))

(defn kurtosis
  [returns]
  (sample-kurtosis-excess returns))

(defn- horner
  [x coeffs]
  (reduce (fn [acc c]
            (+ (* acc x) c))
          0
          coeffs))

(defn- erf
  [x]
  (let [sign (if (neg? x) -1 1)
        x* (js/Math.abs x)
        a1 0.254829592
        a2 -0.284496736
        a3 1.421413741
        a4 -1.453152027
        a5 1.061405429
        p 0.3275911
        t (/ 1 (+ 1 (* p x*)))
        poly (horner t [a5 a4 a3 a2 a1])
        y (- 1 (* poly
                  t
                  (js/Math.exp (- (* x* x*)))))]
    (* sign y)))

(defn- normal-cdf
  [x]
  (* 0.5 (+ 1 (erf (/ x (js/Math.sqrt 2))))))

(defn- inverse-normal-cdf
  [p]
  (let [p* p
        plow 0.02425
        phigh (- 1 plow)
        a [ -39.69683028665376
            220.9460984245205
            -275.9285104469687
            138.357751867269
            -30.66479806614716
            2.506628277459239]
        b [ -54.47609879822406
            161.5858368580409
            -155.6989798598866
            66.80131188771972
            -13.28068155288572]
        c [ -0.007784894002430293
            -0.3223964580411365
            -2.400758277161838
            -2.549732539343734
            4.374664141464968
            2.938163982698783]
        d [0.007784695709041462
           0.3224671290700398
           2.445134137142996
           3.754408661907416]]
    (cond
      (<= p* 0) js/Number.NEGATIVE_INFINITY
      (>= p* 1) js/Number.POSITIVE_INFINITY
      (< p* plow)
      (let [q (js/Math.sqrt (* -2 (js/Math.log p*)))]
        (/ (horner q c)
           (horner q (conj d 1))))
      (> p* phigh)
      (let [q (js/Math.sqrt (* -2 (js/Math.log (- 1 p*))))]
        (- (/ (horner q c)
              (horner q (conj d 1)))))
      :else
      (let [q (- p* 0.5)
            r (* q q)]
        (/ (* (horner r a) q)
           (horner r (conj b 1)))))))

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
         skew* (skew returns)
         kurtosis* (kurtosis returns)
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
                 psr (normal-cdf ratio)]
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

(defn to-drawdown-series
  [returns]
  (if (seq returns)
    (loop [remaining returns
           equity 1
           peak 1
           output []]
      (if (empty? remaining)
        output
        (let [next-equity (* equity (+ 1 (first remaining)))
              peak* (max peak next-equity)
              drawdown (if (pos? peak*)
                         (- (/ next-equity peak*) 1)
                         0)
              drawdown* (clamp-near-zero drawdown)]
          (recur (rest remaining)
                 next-equity
                 peak*
                 (conj output drawdown*)))))
    []))

(defn max-drawdown
  [returns]
  (if-let [drawdowns (seq (to-drawdown-series returns))]
    (apply min drawdowns)
    0))

(defn- drawdown-period-entry
  [rows drawdowns start-idx end-idx]
  (let [[valley-idx valley-dd]
        (reduce (fn [[best-idx best-dd] j]
                  (let [candidate (nth drawdowns j)]
                    (if (< candidate best-dd)
                      [j candidate]
                      [best-idx best-dd])))
                [start-idx (nth drawdowns start-idx)]
                (range start-idx (inc end-idx)))
        start-day (:day (nth rows start-idx))
        end-day (:day (nth rows end-idx))
        valley-day (:day (nth rows valley-idx))
        start-ms (parse-day-ms start-day)
        end-ms (parse-day-ms end-day)
        days (if (and (number? start-ms)
                      (number? end-ms))
               (inc (js/Math.round (/ (- end-ms start-ms) day-ms)))
               1)]
    {:start start-day
     :valley valley-day
     :end end-day
     :days days
     :max-drawdown (* 100 valley-dd)}))

(defn drawdown-details
  [daily-rows]
  (let [rows (normalize-daily-rows daily-rows)
        drawdowns (vec (to-drawdown-series (returns-values rows)))
        n (count drawdowns)]
    (if (zero? n)
      []
      (loop [idx 0
             current-start nil
             details []]
        (if (>= idx n)
          (if (number? current-start)
            (conj details
                  (drawdown-period-entry rows drawdowns current-start (dec n)))
            details)
          (let [dd (nth drawdowns idx)
                in-drawdown? (neg? dd)
                recovered? (and (number? current-start)
                                (zero? dd))]
            (cond
              (and in-drawdown?
                   (nil? current-start))
              (recur (inc idx) idx details)

              recovered?
              (recur (inc idx)
                     nil
                     (conj details
                           (drawdown-period-entry rows
                                                  drawdowns
                                                  current-start
                                                  (dec idx))))

              :else
              (recur (inc idx) current-start details))))))))

(defn max-drawdown-stats
  [daily-rows]
  (let [details (drawdown-details daily-rows)]
    (when (seq details)
      (let [worst (apply min-key :max-drawdown details)
            longest (apply max-key :days details)]
        {:max-drawdown (/ (:max-drawdown worst) 100)
         :max-dd-date (:valley worst)
         :max-dd-period-start (:start worst)
         :max-dd-period-end (:end worst)
         :longest-dd-days (:days longest)}))))

(defn calmar
  ([returns]
   (calmar returns {}))
  ([returns {:keys [periods-per-year years]
             :or {periods-per-year default-periods-per-year}}]
   (let [growth (cagr returns {:periods-per-year periods-per-year
                               :years years})
         drawdown (max-drawdown returns)]
     (when (and (number? growth)
                (number? drawdown)
                (neg? drawdown))
       (/ growth (js/Math.abs drawdown))))))

(defn- group-key
  [day period]
  (case period
    :day day
    :month (subs day 0 7)
    :year (subs day 0 4)
    day))

(defn aggregate-period-returns
  [daily-rows period compounded]
  (let [rows (normalize-daily-rows daily-rows)
        grouped (reduce (fn [acc {:keys [day return]}]
                          (update acc (group-key day period) (fnil conj []) return))
                        (sorted-map)
                        rows)]
    (mapv (fn [[_ values]]
            (if compounded
              (comp values)
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
  (mean (filter pos? returns)))

(defn- avg-loss
  [returns]
  (mean (filter neg? returns)))

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
   (let [mu (mean returns)
         std (sample-stddev returns)
         confidence* (if (> confidence 1)
                       (/ confidence 100)
                       confidence)
         quantile-p (max 1e-9 (min 0.999999 (- 1 confidence*)))]
     (when (and (number? mu)
                (number? std))
       (+ mu (* sigma std (inverse-normal-cdf quantile-p)))))))

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
         (mean tail-values)
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

(defn- quantile
  [values q]
  (let [sorted-values (vec (sort values))
        n (count sorted-values)]
    (when (pos? n)
      (if (= n 1)
        (first sorted-values)
        (let [position (* (dec n) q)
              lower-idx (int (js/Math.floor position))
              upper-idx (int (js/Math.ceil position))
              lower (nth sorted-values lower-idx)
              upper (nth sorted-values upper-idx)
              weight (- position lower-idx)]
          (+ lower (* weight (- upper lower))))))))

(defn tail-ratio
  [returns]
  (let [upper (quantile returns 0.95)
        lower (quantile returns 0.05)]
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
  (let [positive-mean (mean (filter #(>= % 0) returns))
        q99 (quantile returns 0.99)]
    (when (and (number? positive-mean)
               (number? q99)
               (not (zero? positive-mean)))
      (/ q99 positive-mean))))

(defn outlier-loss-ratio
  [returns]
  (let [negative-mean (mean (filter neg? returns))
        q1 (quantile returns 0.01)]
    (when (and (number? negative-mean)
               (number? q1)
               (not (zero? negative-mean)))
      (/ q1 negative-mean))))

(defn align-daily-returns
  [strategy-daily-rows benchmark-daily-rows]
  (let [strategy (normalize-daily-rows strategy-daily-rows)
        benchmark-by-day (into {}
                               (map (juxt :day :return))
                               (normalize-daily-rows benchmark-daily-rows))]
    (->> strategy
         (keep (fn [{:keys [day return]}]
                 (when-let [benchmark-return (get benchmark-by-day day)]
                   {:day day
                    :strategy-return return
                    :benchmark-return benchmark-return})))
         vec)))

(defn r-squared
  [strategy-returns benchmark-returns]
  (when-let [corr (pearson-correlation strategy-returns benchmark-returns)]
    (* corr corr)))

(defn information-ratio
  [strategy-returns benchmark-returns]
  (let [diff (mapv - strategy-returns benchmark-returns)
        std (sample-stddev diff)]
    (if (and (number? std)
             (pos? std))
      (/ (or (mean diff) 0)
         std)
      0)))

(defn- rows-since-ms
  [rows threshold-ms]
  (->> rows
       (filter (fn [{:keys [day time-ms]}]
                 (if-let [anchor-ms (or (optional-number time-ms)
                                        (parse-day-ms day))]
                   (>= anchor-ms threshold-ms)
                   false)))
       vec))

(defn- cumulative-rows-since-ms
  [rows threshold-ms]
  (let [sorted-rows (->> rows
                         (filter (fn [{:keys [time-ms]}]
                                   (number? time-ms)))
                         (sort-by :time-ms)
                         vec)
        anchor-row (last (filter (fn [{:keys [time-ms]}]
                                   (< time-ms threshold-ms))
                                 sorted-rows))
        window-rows (filter (fn [{:keys [time-ms]}]
                              (>= time-ms threshold-ms))
                            sorted-rows)]
    (cond
      (and anchor-row (seq window-rows))
      (vec (cons anchor-row window-rows))

      (seq window-rows)
      (vec window-rows)

      :else
      [])))

(defn- with-utc-months-offset
  [time-ms months]
  (let [date (js/Date. time-ms)]
    (.setUTCMonth date (+ (.getUTCMonth date) months))
    (.getTime date)))

(defn- with-utc-years-offset
  [time-ms years]
  (let [date (js/Date. time-ms)]
    (.setUTCFullYear date (+ (.getUTCFullYear date) years))
    (.getTime date)))

(defn- window-return
  [rows compounded]
  (let [returns (returns-values rows)]
    (when (seq returns)
      (if compounded
        (comp returns)
        (reduce + 0 returns)))))

(defn- window-return-from-cumulative
  [rows]
  (when (>= (count rows) 2)
    (let [start-factor (:factor (first rows))
          end-factor (:factor (last rows))]
      (when (and (finite-number? start-factor)
                 (finite-number? end-factor)
                 (pos? start-factor))
        (- (/ end-factor start-factor) 1)))))

(defn- window-cagr-from-cumulative
  [rows]
  (interval-cagr (cumulative-rows->irregular-intervals rows)))

(defn- window-span-days
  [rows]
  (if (>= (count rows) 2)
    (/ (- (:time-ms (last rows))
          (:time-ms (first rows)))
       day-ms)
    0))

(defn compute-performance-metrics
  [{:keys [strategy-cumulative-rows
           strategy-daily-rows
           benchmark-daily-rows
           rf
           mar
           periods-per-year
           quality-gates]
    :or {rf 0
         mar 0
         periods-per-year default-periods-per-year}}]
  (let [gates (merge default-quality-gates quality-gates)
        resolved-cumulative-rows (let [direct (normalize-cumulative-percent-rows strategy-cumulative-rows)]
                                   (if (seq direct)
                                     direct
                                     (normalize-cumulative-percent-rows
                                      (daily-rows->cumulative-percent-rows strategy-daily-rows))))
        cumulative-rows* (mapv (fn [{:keys [time-ms percent]}]
                                 [time-ms percent])
                               resolved-cumulative-rows)
        strategy-rows (let [daily* (normalize-daily-rows strategy-daily-rows)]
                        (if (seq daily*)
                          daily*
                          (daily-compounded-returns cumulative-rows*)))
        strategy-returns (returns-values strategy-rows)
        intervals (cumulative-rows->irregular-intervals cumulative-rows*)
        diagnostics (cadence-diagnostics intervals strategy-rows mar)
        gates* (compute-quality-gates diagnostics gates)
        aligned-benchmark (align-daily-returns strategy-rows benchmark-daily-rows)
        strategy-aligned (mapv :strategy-return aligned-benchmark)
        benchmark-aligned (mapv :benchmark-return aligned-benchmark)
        drawdown-stats (max-drawdown-stats strategy-rows)
        cagr* (interval-cagr intervals)
        volatility-ann* (volatility-ann-irregular intervals)
        sharpe* (sharpe-irregular intervals rf)
        sortino-result (sortino-irregular intervals mar)
        sortino* (:value sortino-result)
        sortino-downside-count (:downside-count sortino-result)
        expected-daily* (when (number? cagr*)
                          (- (js/Math.pow (+ 1 cagr*) (/ 1 365.2425))
                             1))
        expected-monthly* (when (number? cagr*)
                            (- (js/Math.pow (+ 1 cagr*) (/ 1 12))
                               1))
        expected-yearly* cagr*
        smart-sharpe* (smart-sharpe strategy-returns {:rf rf
                                                      :periods-per-year periods-per-year})
        smart-sortino* (smart-sortino strategy-returns {:rf rf
                                                        :periods-per-year periods-per-year})
        benchmark-min? (>= (count aligned-benchmark)
                           (:benchmark-min-points gates))
        last-ms (some-> resolved-cumulative-rows last :time-ms)
        last-date (when (number? last-ms) (js/Date. last-ms))
        month-start-ms (when last-date
                         (.getTime (js/Date. (.UTC js/Date
                                                  (.getUTCFullYear last-date)
                                                  (.getUTCMonth last-date)
                                                  1))))
        year-start-ms (when last-date
                        (.getTime (js/Date. (.UTC js/Date
                                                 (.getUTCFullYear last-date)
                                                 0
                                                 1))))
        m3-ms (when (number? last-ms) (with-utc-months-offset last-ms -3))
        m6-ms (when (number? last-ms) (with-utc-months-offset last-ms -6))
        y1-ms (when (number? last-ms) (with-utc-years-offset last-ms -1))
        y3-ms (when (number? last-ms) (with-utc-months-offset last-ms -35))
        y5-ms (when (number? last-ms) (with-utc-months-offset last-ms -59))
        y10-ms (when (number? last-ms) (with-utc-years-offset last-ms -10))
        mtd-cumulative (if (number? month-start-ms)
                         (cumulative-rows-since-ms resolved-cumulative-rows month-start-ms)
                         [])
        m3-cumulative (if (number? m3-ms)
                        (cumulative-rows-since-ms resolved-cumulative-rows m3-ms)
                        [])
        m6-cumulative (if (number? m6-ms)
                        (cumulative-rows-since-ms resolved-cumulative-rows m6-ms)
                        [])
        ytd-cumulative (if (number? year-start-ms)
                         (cumulative-rows-since-ms resolved-cumulative-rows year-start-ms)
                         [])
        y1-cumulative (if (number? y1-ms)
                        (cumulative-rows-since-ms resolved-cumulative-rows y1-ms)
                        [])
        y3-cumulative (if (number? y3-ms)
                        (cumulative-rows-since-ms resolved-cumulative-rows y3-ms)
                        [])
        y5-cumulative (if (number? y5-ms)
                        (cumulative-rows-since-ms resolved-cumulative-rows y5-ms)
                        [])
        y10-cumulative (if (number? y10-ms)
                         (cumulative-rows-since-ms resolved-cumulative-rows y10-ms)
                         [])
        rolling-min-fraction (:rolling-min-fraction gates)
        rolling-y3-ok? (>= (window-span-days y3-cumulative)
                           (* 3 365.2425 rolling-min-fraction))
        rolling-y5-ok? (>= (window-span-days y5-cumulative)
                           (* 5 365.2425 rolling-min-fraction))
        rolling-y10-ok? (>= (window-span-days y10-cumulative)
                            (* 10 365.2425 rolling-min-fraction))
        all-time-cumulative-return (window-return-from-cumulative resolved-cumulative-rows)
        core-enabled? (and (:core-min? gates*)
                           (not (:structural-gap? gates*)))
        core-low-confidence? (and core-enabled?
                                  (not (:core-high-confidence? gates*)))
        daily-enabled? (and (:daily-min? gates*)
                            (not (:structural-gap? gates*)))
        psr-enabled? (and daily-enabled? (:psr-min? gates*))
        sortino-enabled? (and core-enabled?
                              (:sortino-min? gates*)
                              (>= sortino-downside-count
                                  (:sortino-min-downside gates)))
        drawdown-reliable? (and core-enabled? (:drawdown-reliable? gates*))
        benchmark-enabled? (and daily-enabled?
                               benchmark-min?)]
    (letfn [(assoc-metric
              [acc key value enabled status reason]
              (let [status* (cond
                              (not enabled) :suppressed
                              (nil? value) :suppressed
                              :else status)
                    value* (when (not= status* :suppressed) value)
                    reason* (when (= status* :suppressed)
                              reason)]
                (-> acc
                    (assoc key value*)
                    (assoc-in [:metric-status key] status*)
                    (cond-> reason*
                      (assoc-in [:metric-reason key] reason*)))))
            (core-status []
              (if core-low-confidence?
                :low-confidence
                :ok))]
      (-> {:quality (merge diagnostics gates*)
           :metric-status {}
           :metric-reason {}}
          (assoc-metric :time-in-market
                        (interval-weighted-exposure intervals)
                        (pos? (count intervals))
                        :ok
                        :insufficient-intervals)
          (assoc-metric :cumulative-return
                        all-time-cumulative-return
                        (>= (count resolved-cumulative-rows) 2)
                        :ok
                        :insufficient-rows)
          (assoc-metric :cagr cagr* core-enabled? (core-status) :core-gate-failed)
          (assoc-metric :volatility-ann volatility-ann* core-enabled? (core-status) :core-gate-failed)
          (assoc-metric :sharpe sharpe* core-enabled? (core-status) :core-gate-failed)
          (assoc-metric :sortino sortino* sortino-enabled? (core-status) :sortino-gate-failed)
          (assoc-metric :sortino-sqrt2
                        (some-> sortino* (/ (js/Math.sqrt 2)))
                        sortino-enabled?
                        (core-status)
                        :sortino-gate-failed)
          (assoc-metric :expected-daily expected-daily* core-enabled? (core-status) :core-gate-failed)
          (assoc-metric :expected-monthly expected-monthly* core-enabled? (core-status) :core-gate-failed)
          (assoc-metric :expected-yearly expected-yearly* core-enabled? (core-status) :core-gate-failed)
          (assoc-metric :max-drawdown (:max-drawdown drawdown-stats)
                        (boolean drawdown-stats)
                        (if drawdown-reliable? :ok :low-confidence)
                        :drawdown-unavailable)
          (assoc-metric :max-dd-date (:max-dd-date drawdown-stats)
                        (boolean drawdown-stats)
                        (if drawdown-reliable? :ok :low-confidence)
                        :drawdown-unavailable)
          (assoc-metric :max-dd-period-start (:max-dd-period-start drawdown-stats)
                        (boolean drawdown-stats)
                        (if drawdown-reliable? :ok :low-confidence)
                        :drawdown-unavailable)
          (assoc-metric :max-dd-period-end (:max-dd-period-end drawdown-stats)
                        (boolean drawdown-stats)
                        (if drawdown-reliable? :ok :low-confidence)
                        :drawdown-unavailable)
          (assoc-metric :longest-dd-days (:longest-dd-days drawdown-stats)
                        (boolean drawdown-stats)
                        (if drawdown-reliable? :ok :low-confidence)
                        :drawdown-unavailable)
          (assoc-metric :calmar
                        (calmar strategy-returns {:periods-per-year periods-per-year})
                        drawdown-reliable?
                        (if drawdown-reliable? :ok :low-confidence)
                        :drawdown-reliability-gate-failed)
          (assoc-metric :mtd (window-return-from-cumulative mtd-cumulative)
                        (>= (count mtd-cumulative) 2)
                        :ok
                        :window-unavailable)
          (assoc-metric :m3 (window-return-from-cumulative m3-cumulative)
                        (>= (count m3-cumulative) 2)
                        :ok
                        :window-unavailable)
          (assoc-metric :m6 (window-return-from-cumulative m6-cumulative)
                        (>= (count m6-cumulative) 2)
                        :ok
                        :window-unavailable)
          (assoc-metric :ytd (window-return-from-cumulative ytd-cumulative)
                        (>= (count ytd-cumulative) 2)
                        :ok
                        :window-unavailable)
          (assoc-metric :y1 (window-return-from-cumulative y1-cumulative)
                        (>= (count y1-cumulative) 2)
                        :ok
                        :window-unavailable)
          (assoc-metric :y3-ann (window-cagr-from-cumulative y3-cumulative)
                        rolling-y3-ok?
                        (if core-low-confidence? :low-confidence :ok)
                        :rolling-window-span-insufficient)
          (assoc-metric :y5-ann (window-cagr-from-cumulative y5-cumulative)
                        rolling-y5-ok?
                        (if core-low-confidence? :low-confidence :ok)
                        :rolling-window-span-insufficient)
          (assoc-metric :y10-ann (window-cagr-from-cumulative y10-cumulative)
                        rolling-y10-ok?
                        (if core-low-confidence? :low-confidence :ok)
                        :rolling-window-span-insufficient)
          (assoc-metric :all-time-ann cagr* core-enabled? (core-status) :core-gate-failed)
          (assoc-metric :omega
                        (omega strategy-returns {:rf rf
                                                 :required-return 0
                                                 :periods-per-year periods-per-year})
                        daily-enabled?
                        :ok
                        :daily-coverage-gate-failed)
          (assoc-metric :smart-sharpe smart-sharpe* daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :smart-sortino smart-sortino* daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :smart-sortino-sqrt2
                        (some-> smart-sortino* (/ (js/Math.sqrt 2)))
                        daily-enabled?
                        :ok
                        :daily-coverage-gate-failed)
          (assoc-metric :prob-sharpe-ratio
                        (probabilistic-sharpe-ratio strategy-returns {:rf rf
                                                                        :periods-per-year periods-per-year})
                        psr-enabled?
                        :ok
                        :psr-gate-failed)
          (assoc-metric :gain-pain-ratio (gain-to-pain-ratio strategy-rows :day) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :gain-pain-1m (gain-to-pain-ratio strategy-rows :month) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :payoff-ratio (payoff-ratio strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :profit-factor (profit-factor strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :common-sense-ratio (common-sense-ratio strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :cpc-index (cpc-index strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :tail-ratio (tail-ratio strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :outlier-win-ratio (outlier-win-ratio strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :outlier-loss-ratio (outlier-loss-ratio strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :skew (skew strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :kurtosis (kurtosis strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :kelly-criterion (kelly-criterion strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :risk-of-ruin (risk-of-ruin strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :daily-var
                        (some-> (value-at-risk strategy-returns)
                                js/Math.abs
                                (-))
                        daily-enabled?
                        :ok
                        :daily-coverage-gate-failed)
          (assoc-metric :expected-shortfall
                        (some-> (expected-shortfall strategy-returns)
                                js/Math.abs
                                (-))
                        daily-enabled?
                        :ok
                        :daily-coverage-gate-failed)
          (assoc-metric :max-consecutive-wins (consecutive-wins strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :max-consecutive-losses (consecutive-losses strategy-returns) daily-enabled? :ok :daily-coverage-gate-failed)
          (assoc-metric :r2
                        (when (seq aligned-benchmark)
                          (r-squared strategy-aligned benchmark-aligned))
                        benchmark-enabled?
                        :ok
                        :benchmark-coverage-gate-failed)
          (assoc-metric :information-ratio
                        (when (seq aligned-benchmark)
                          (information-ratio strategy-aligned benchmark-aligned))
                        benchmark-enabled?
                        :ok
                        :benchmark-coverage-gate-failed)))))

(def ^:private performance-metric-groups
  [{:id :overview
    :rows [{:key :time-in-market
            :label "Time in Market"
            :kind :percent}
           {:key :cumulative-return
            :label "Cumulative Return"
            :kind :percent}
           {:key :cagr
            :label "CAGR"
            :kind :percent}]}
   {:id :risk-adjusted
    :rows [{:key :sharpe
            :label "Sharpe"
            :kind :ratio}
           {:key :prob-sharpe-ratio
            :label "Prob. Sharpe Ratio"
            :kind :ratio}
           {:key :smart-sharpe
            :label "Smart Sharpe"
            :kind :ratio}
           {:key :sortino
            :label "Sortino"
            :kind :ratio}
           {:key :smart-sortino
            :label "Smart Sortino"
            :kind :ratio}
           {:key :sortino-sqrt2
            :label "Sortino/sqrt(2)"
            :kind :ratio}
           {:key :smart-sortino-sqrt2
            :label "Smart Sortino/sqrt(2)"
            :kind :ratio}
           {:key :omega
            :label "Omega"
            :kind :ratio}]}
   {:id :drawdown-and-risk
    :rows [{:key :max-drawdown
            :label "Max Drawdown"
            :kind :percent}
           {:key :max-dd-date
            :label "Max DD Date"
            :kind :date}
           {:key :max-dd-period-start
            :label "Max DD Period Start"
            :kind :date}
           {:key :max-dd-period-end
            :label "Max DD Period End"
            :kind :date}
           {:key :longest-dd-days
            :label "Longest DD Days"
            :kind :integer}
           {:key :volatility-ann
            :label "Volatility (ann.)"
            :kind :percent}
           {:key :r2
            :label "R^2"
            :kind :ratio}
           {:key :information-ratio
            :label "Information Ratio"
            :kind :ratio}
           {:key :calmar
            :label "Calmar"
            :kind :ratio}
           {:key :skew
            :label "Skew"
            :kind :ratio}
           {:key :kurtosis
            :label "Kurtosis"
            :kind :ratio}]}
   {:id :expectation-and-var
    :rows [{:key :expected-daily
            :label "Expected Daily"
            :kind :percent}
           {:key :expected-monthly
            :label "Expected Monthly"
            :kind :percent}
           {:key :expected-yearly
            :label "Expected Yearly"
            :kind :percent}
           {:key :kelly-criterion
            :label "Kelly Criterion"
            :kind :percent}
           {:key :risk-of-ruin
            :label "Risk of Ruin"
            :kind :ratio}
           {:key :daily-var
            :label "Daily Value-at-Risk"
            :kind :percent}
           {:key :expected-shortfall
            :label "Expected Shortfall (cVaR)"
            :kind :percent}]}
   {:id :streaks-and-pain
    :rows [{:key :max-consecutive-wins
            :label "Max Consecutive Wins"
            :kind :integer}
           {:key :max-consecutive-losses
            :label "Max Consecutive Losses"
            :kind :integer}
           {:key :gain-pain-ratio
            :label "Gain/Pain Ratio"
            :kind :ratio}
           {:key :gain-pain-1m
            :label "Gain/Pain (1M)"
            :kind :ratio}]}
   {:id :trade-shape
    :rows [{:key :payoff-ratio
            :label "Payoff Ratio"
            :kind :ratio}
           {:key :profit-factor
            :label "Profit Factor"
            :kind :ratio}
           {:key :common-sense-ratio
            :label "Common Sense Ratio"
            :kind :ratio}
           {:key :cpc-index
            :label "CPC Index"
            :kind :ratio}
           {:key :tail-ratio
            :label "Tail Ratio"
            :kind :ratio}
           {:key :outlier-win-ratio
            :label "Outlier Win Ratio"
            :kind :ratio}
           {:key :outlier-loss-ratio
            :label "Outlier Loss Ratio"
            :kind :ratio}]}
   {:id :period-returns
    :rows [{:key :mtd
            :label "MTD"
            :kind :percent}
           {:key :m3
            :label "3M"
            :kind :percent}
           {:key :m6
            :label "6M"
            :kind :percent}
           {:key :ytd
            :label "YTD"
            :kind :percent}
           {:key :y1
            :label "1Y"
            :kind :percent}
           {:key :y3-ann
            :label "3Y (ann.)"
            :kind :percent}
           {:key :y5-ann
            :label "5Y (ann.)"
            :kind :percent}
           {:key :y10-ann
            :label "10Y (ann.)"
            :kind :percent}
           {:key :all-time-ann
            :label "All-time (ann.)"
            :kind :percent}]}])

(defn metric-rows
  [metric-values]
  (let [metric-status (or (:metric-status metric-values)
                          {})
        metric-reason (or (:metric-reason metric-values)
                          {})]
    (mapv (fn [{:keys [rows] :as group}]
            (assoc group
                   :rows (mapv (fn [{:keys [key] :as row}]
                                 (assoc row
                                        :value (get metric-values key)
                                        :status (get metric-status key)
                                        :reason (get metric-reason key)))
                               rows)))
          performance-metric-groups)))
