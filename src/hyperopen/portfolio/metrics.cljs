(ns hyperopen.portfolio.metrics
  (:refer-clojure :exclude [comp])
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.projections :as projections]))

(defn- optional-number [value]
  (projections/parse-optional-num value))

(defn- number-or-zero [value]
  (if-let [n (optional-number value)]
    n
    0))

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

(defn- normalize-address [value]
  (some-> value str str/lower-case str/trim))

(defn- same-address?
  [left right]
  (let [left* (normalize-address left)
        right* (normalize-address right)]
    (and (seq left*)
         (seq right*)
         (= left* right*))))

(defn- canonical-ledger-delta-type
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      (let [token (-> text
                      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                      str/lower-case
                      (str/replace #"[^a-z0-9]+" "-")
                      (str/replace #"(^-+)|(-+$)" ""))]
        (keyword token)))))

(defn- ledger-row-id
  [row]
  (let [hash* (some-> (:hash row) str str/lower-case str/trim)
        time-ms (history-point-time-ms row)
        delta (:delta row)]
    (when (or (seq hash*)
              (number? time-ms)
              (some? delta))
      ;; Dedupe exact duplicates across bootstrap/websocket sources without
      ;; collapsing distinct deltas that share a transaction hash.
      (str (or hash* "")
           "|"
           (or time-ms "")
           "|"
           (pr-str delta)))))

(defn- combined-ledger-rows
  [state]
  (let [rows (concat (or (get-in state [:portfolio :ledger-updates]) [])
                     (or (get-in state [:orders :ledger]) []))
        normalized (->> rows
                        (keep (fn [row]
                                (let [time-ms (history-point-time-ms row)]
                                  (when (number? time-ms)
                                    {:id (ledger-row-id row)
                                     :time-ms time-ms
                                     :row row}))))
                        (sort-by :time-ms))]
    (->> normalized
         (reduce (fn [{:keys [seen rows]} {:keys [id row]}]
                   (let [id* (or id (str "row-" (count seen)))]
                     (if (contains? seen id*)
                       {:seen seen
                        :rows rows}
                       {:seen (conj seen id*)
                        :rows (conj rows row)})))
                 {:seen #{}
                  :rows []})
         :rows
         vec)))

(defn- transfer-flow
  [amount sender destination current-user-address]
  (let [sender? (same-address? sender current-user-address)
        destination? (same-address? destination current-user-address)]
    (cond
      (and sender? (not destination?)) (- amount)
      (and destination? (not sender?)) amount
      :else 0)))

(defn- usdc-fee
  [delta]
  (let [fee (number-or-zero (:fee delta))
        fee-token (some-> (:feeToken delta) str str/upper-case)]
    (if (or (nil? fee-token)
            (= fee-token "")
            (= fee-token "USDC"))
      fee
      0)))

(defn- ledger-row-flow-usd
  [row summary-scope current-user-address]
  (let [delta (:delta row)]
    (cond
      (number? (optional-number delta))
      (optional-number delta)

      (map? delta)
      (case (canonical-ledger-delta-type (:type delta))
        :deposit
        (number-or-zero (:usdc delta))

        :withdraw
        (- (+ (number-or-zero (:usdc delta))
              (number-or-zero (:fee delta))))

        :account-class-transfer
        (if (= summary-scope :perps)
          (let [amount (number-or-zero (:usdc delta))]
            (if (true? (:toPerp delta))
              amount
              (- amount)))
          0)

        :sub-account-transfer
        (transfer-flow (number-or-zero (:usdc delta))
                       (:user delta)
                       (:destination delta)
                       current-user-address)

        :internal-transfer
        (let [sender? (same-address? (:user delta) current-user-address)
              amount (+ (number-or-zero (:usdc delta))
                        (if sender?
                          (number-or-zero (:fee delta))
                          0))]
          (transfer-flow amount
                         (:user delta)
                         (:destination delta)
                         current-user-address))

        :spot-transfer
        (let [sender? (same-address? (:user delta) current-user-address)
              amount (+ (number-or-zero (or (:usdcValue delta)
                                            (:usdc delta)))
                        (if sender?
                          (usdc-fee delta)
                          0))]
          (transfer-flow amount
                         (:user delta)
                         (:destination delta)
                         current-user-address))

        :send
        (let [sender? (same-address? (:user delta) current-user-address)
              amount (+ (number-or-zero (or (:usdcValue delta)
                                            (:usdc delta)))
                        (if sender?
                          (usdc-fee delta)
                          0))]
          (transfer-flow amount
                         (:user delta)
                         (:destination delta)
                         current-user-address))

        0)

      :else
      nil)))

(defn- ledger-flow-events
  [state summary-scope]
  (let [current-user-address (normalize-address (get-in state [:wallet :address]))]
    (->> (combined-ledger-rows state)
         (keep (fn [row]
                 (let [time-ms (history-point-time-ms row)
                       flow (ledger-row-flow-usd row summary-scope current-user-address)]
                   (when (and (number? time-ms)
                              (finite-number? flow)
                              (not (zero? flow)))
                     {:time-ms time-ms
                      :flow flow}))))
         (sort-by :time-ms)
         vec)))

(defn- account-history-points
  [summary]
  (->> (or (:accountValueHistory summary) [])
       (map-indexed (fn [idx row]
                      (let [value (history-point-value row)
                            time-ms (history-point-time-ms row)]
                        (when (finite-number? value)
                          {:index idx
                           :time-ms (or time-ms idx)
                           :value value}))))
       (keep identity)
       (sort-by :time-ms)
       vec))

(defn- interval-flow-stats
  [flows start-time-ms end-time-ms]
  (let [duration-ms (- end-time-ms start-time-ms)
        interval-flows (filter (fn [{:keys [time-ms]}]
                                 (and (number? time-ms)
                                      (> time-ms start-time-ms)
                                      (<= time-ms end-time-ms)))
                               flows)
        net-flow (reduce (fn [acc {:keys [flow]}]
                           (+ acc flow))
                         0
                         interval-flows)
        weighted-flow (if (pos? duration-ms)
                        (reduce (fn [acc {:keys [time-ms flow]}]
                                  (let [weight (/ (- end-time-ms time-ms) duration-ms)
                                        weight* (cond
                                                  (< weight 0) 0
                                                  (> weight 1) 1
                                                  :else weight)]
                                    (+ acc (* flow weight*))))
                                0
                                interval-flows)
                        0)]
    {:net-flow net-flow
     :weighted-flow weighted-flow}))

(defn returns-history-rows
  [state summary summary-scope]
  (let [points (account-history-points summary)
        anchor-index (first (keep-indexed (fn [idx {:keys [value]}]
                                            (when (pos? value)
                                              idx))
                                          points))]
    (if (number? anchor-index)
      (let [points* (subvec points anchor-index)
            flows (ledger-flow-events state summary-scope)]
        (if (seq points*)
          (loop [idx 1
                 previous (first points*)
                 cumulative-factor 1
                 output [[(:time-ms (first points*)) 0]]
                 point-count (count points*)]
            (if (>= idx point-count)
              output
              (let [current (nth points* idx)
                    start-time-ms (:time-ms previous)
                    end-time-ms (:time-ms current)
                    {:keys [net-flow weighted-flow]} (interval-flow-stats flows start-time-ms end-time-ms)
                    denominator (+ (:value previous) weighted-flow)
                    numerator (- (:value current) (:value previous) net-flow)
                    period-return (if (and (finite-number? denominator)
                                           (pos? denominator))
                                    (/ numerator denominator)
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

(def ^:private epsilon
  1e-12)

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
  ([returns {:keys [periods-per-year compounded]
             :or {periods-per-year default-periods-per-year
                  compounded true}}]
   (let [n (count returns)]
     (when (and (pos? n)
                (pos? periods-per-year))
       (let [total (if compounded
                     (comp returns)
                     (reduce + 0 returns))
             years (/ n periods-per-year)]
         (when (and (number? total)
                    (pos? years))
           (- (js/Math.pow (js/Math.abs (+ total 1))
                           (/ 1 years))
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
  ([returns {:keys [periods-per-year]
             :or {periods-per-year default-periods-per-year}}]
   (let [growth (cagr returns {:periods-per-year periods-per-year})
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
       (filter (fn [{:keys [day]}]
                 (if-let [day-ms* (parse-day-ms day)]
                   (>= day-ms* threshold-ms)
                   false)))
       vec))

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

(defn compute-performance-metrics
  [{:keys [strategy-daily-rows
           benchmark-daily-rows
           rf
           periods-per-year
           compounded]
    :or {rf 0
         periods-per-year default-periods-per-year
         compounded true}}]
  (let [strategy-rows (normalize-daily-rows strategy-daily-rows)
        strategy-returns (returns-values strategy-rows)
        aligned-benchmark (align-daily-returns strategy-rows benchmark-daily-rows)
        strategy-aligned (mapv :strategy-return aligned-benchmark)
        benchmark-aligned (mapv :benchmark-return aligned-benchmark)
        drawdown-stats (max-drawdown-stats strategy-rows)
        sortino* (sortino strategy-returns {:rf rf
                                            :periods-per-year periods-per-year})
        smart-sortino* (smart-sortino strategy-returns {:rf rf
                                                        :periods-per-year periods-per-year})
        last-ms (some-> strategy-rows last :time-ms)
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
        mtd-rows (if (number? month-start-ms)
                   (rows-since-ms strategy-rows month-start-ms)
                   [])
        m3-rows (if (number? m3-ms)
                  (rows-since-ms strategy-rows m3-ms)
                  [])
        m6-rows (if (number? m6-ms)
                  (rows-since-ms strategy-rows m6-ms)
                  [])
        ytd-rows (if (number? year-start-ms)
                   (rows-since-ms strategy-rows year-start-ms)
                   [])
        y1-rows (if (number? y1-ms)
                  (rows-since-ms strategy-rows y1-ms)
                  [])
        y3-rows (if (number? y3-ms)
                  (rows-since-ms strategy-rows y3-ms)
                  [])
        y5-rows (if (number? y5-ms)
                  (rows-since-ms strategy-rows y5-ms)
                  [])
        y10-rows (if (number? y10-ms)
                   (rows-since-ms strategy-rows y10-ms)
                   [])]
    {:time-in-market (time-in-market strategy-returns)
     :cumulative-return (comp strategy-returns)
     :cagr (cagr strategy-returns {:periods-per-year periods-per-year
                                   :compounded compounded})
     :sharpe (sharpe strategy-returns {:rf rf
                                       :periods-per-year periods-per-year})
     :prob-sharpe-ratio (probabilistic-sharpe-ratio strategy-returns {:rf rf
                                                                       :periods-per-year periods-per-year})
     :smart-sharpe (smart-sharpe strategy-returns {:rf rf
                                                   :periods-per-year periods-per-year})
     :sortino sortino*
     :smart-sortino smart-sortino*
     :sortino-sqrt2 (some-> sortino*
                            (/ (js/Math.sqrt 2)))
     :smart-sortino-sqrt2 (some-> smart-sortino*
                                  (/ (js/Math.sqrt 2)))
     :omega (omega strategy-returns {:rf rf
                                     :required-return 0
                                     :periods-per-year periods-per-year})
     :max-drawdown (:max-drawdown drawdown-stats)
     :max-dd-date (:max-dd-date drawdown-stats)
     :max-dd-period-start (:max-dd-period-start drawdown-stats)
     :max-dd-period-end (:max-dd-period-end drawdown-stats)
     :longest-dd-days (:longest-dd-days drawdown-stats)
     :volatility-ann (volatility strategy-returns {:periods-per-year periods-per-year})
     :r2 (when (seq aligned-benchmark)
           (r-squared strategy-aligned benchmark-aligned))
     :information-ratio (when (seq aligned-benchmark)
                          (information-ratio strategy-aligned benchmark-aligned))
     :calmar (calmar strategy-returns {:periods-per-year periods-per-year})
     :skew (skew strategy-returns)
     :kurtosis (kurtosis strategy-returns)
     :expected-daily (expected-return strategy-rows {:period :day
                                                     :compounded compounded})
     :expected-monthly (expected-return strategy-rows {:period :month
                                                       :compounded compounded})
     :expected-yearly (expected-return strategy-rows {:period :year
                                                      :compounded compounded})
     :kelly-criterion (kelly-criterion strategy-returns)
     :risk-of-ruin (risk-of-ruin strategy-returns)
     :daily-var (value-at-risk strategy-returns)
     :expected-shortfall (expected-shortfall strategy-returns)
     :max-consecutive-wins (consecutive-wins strategy-returns)
     :max-consecutive-losses (consecutive-losses strategy-returns)
     :gain-pain-ratio (gain-to-pain-ratio strategy-rows :day)
     :gain-pain-1m (gain-to-pain-ratio strategy-rows :month)
     :payoff-ratio (payoff-ratio strategy-returns)
     :profit-factor (profit-factor strategy-returns)
     :common-sense-ratio (common-sense-ratio strategy-returns)
     :cpc-index (cpc-index strategy-returns)
     :tail-ratio (tail-ratio strategy-returns)
     :outlier-win-ratio (outlier-win-ratio strategy-returns)
     :outlier-loss-ratio (outlier-loss-ratio strategy-returns)
     :mtd (window-return mtd-rows compounded)
     :m3 (window-return m3-rows compounded)
     :m6 (window-return m6-rows compounded)
     :ytd (window-return ytd-rows compounded)
     :y1 (window-return y1-rows compounded)
     :y3-ann (cagr (returns-values y3-rows) {:periods-per-year periods-per-year
                                             :compounded compounded})
     :y5-ann (cagr (returns-values y5-rows) {:periods-per-year periods-per-year
                                             :compounded compounded})
     :y10-ann (cagr (returns-values y10-rows) {:periods-per-year periods-per-year
                                               :compounded compounded})
     :all-time-ann (cagr strategy-returns {:periods-per-year periods-per-year
                                           :compounded compounded})}))

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
  (mapv (fn [{:keys [rows] :as group}]
          (assoc group
                 :rows (mapv (fn [{:keys [key] :as row}]
                               (assoc row :value (get metric-values key)))
                             rows)))
        performance-metric-groups))
