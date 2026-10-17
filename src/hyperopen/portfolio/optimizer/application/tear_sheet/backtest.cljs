(ns hyperopen.portfolio.optimizer.application.tear-sheet.backtest
  "Hypothetical backtest of an optimizer target: hold the target weights over
  each leg's own published history and report the portfolio's growth.

  Vault histories are sparse (Hyperliquid spreads a fixed point budget over a
  vault's life), so every leg is put on one daily UTC grid by log-linear
  interpolation between its samples. Before a leg's first sample it is not
  live; after its last sample it holds its last value.

  Pure: no state, no clock.")

(def day-ms
  (* 24 60 60 1000))

(def rebalance-options
  #{:daily :monthly :hold})

(def missing-options
  #{:spread :cash :common})

(defn- floor-day
  [time-ms]
  (* day-ms (js/Math.floor (/ time-ms day-ms))))

(defn daily-grid
  "UTC midnights from the day of `start-ms` through `end-ms`, plus `end-ms`
  itself when it falls after the last midnight."
  [start-ms end-ms]
  (when (and (number? start-ms) (number? end-ms) (< start-ms end-ms))
    (let [days (vec (range (floor-day start-ms) (inc end-ms) day-ms))]
      (if (> end-ms (peek days))
        (conj days end-ms)
        days))))

(defn- usable-rows
  [rows]
  (->> rows
       (filter (fn [{:keys [time-ms close]}]
                 (and (number? time-ms) (number? close) (pos? close))))
       (sort-by :time-ms)
       vec))

(defn value-at
  "Leg value at `t` by log-linear interpolation between samples; nil before
  the first sample, the last close after the last sample. `rows` must be
  sorted by time. `hint` is the index to start the bracket search from."
  ([rows t]
   (:value (value-at rows t 0)))
  ([rows t hint]
   (let [n (count rows)]
     (cond
       (or (zero? n) (< t (:time-ms (nth rows 0))))
       {:value nil :hint 0}

       (>= t (:time-ms (nth rows (dec n))))
       {:value (:close (nth rows (dec n))) :hint (dec n)}

       :else
       (loop [i (max 0 (min hint (- n 2)))]
         (let [a (nth rows i)
               b (nth rows (inc i))]
           (cond
             (< t (:time-ms a)) (recur (dec i))
             (> t (:time-ms b)) (recur (inc i))
             :else
             (let [span (- (:time-ms b) (:time-ms a))
                   f (if (pos? span) (/ (- t (:time-ms a)) span) 0)]
               {:value (js/Math.exp (+ (js/Math.log (:close a))
                                       (* f (- (js/Math.log (:close b))
                                               (js/Math.log (:close a))))))
                :hint i}))))))))

(defn leg-grid-values
  "Values of one leg on `grid` (nil where not yet live)."
  [rows grid]
  (let [rows* (usable-rows rows)]
    (loop [ts (seq grid)
           hint 0
           out (transient [])]
      (if-let [t (first ts)]
        (let [{:keys [value hint]} (value-at rows* t hint)]
          (recur (next ts) hint (conj! out value)))
        (persistent! out)))))

(defn median-gap-days
  "Median spacing of a leg's samples inside [start-ms, end-ms], in days."
  [rows start-ms end-ms]
  (let [times (->> rows
                   (keep :time-ms)
                   (filter #(<= start-ms % end-ms))
                   sort
                   vec)
        gaps (sort (map (fn [[a b]] (- b a)) (partition 2 1 times)))]
    (when (seq gaps)
      (/ (nth gaps (quot (count gaps) 2)) day-ms))))

(defn- month-key
  [time-ms]
  (let [d (js/Date. time-ms)]
    (+ (* 12 (.getUTCFullYear d)) (.getUTCMonth d))))

(defn- target-holdings
  "Dollar holdings for portfolio value `v` over the live legs."
  [legs live? v missing]
  (let [total-w (reduce + 0 (map :weight legs))
        live-w (reduce + 0 (keep-indexed (fn [i leg] (when (live? i) (:weight leg))) legs))
        scale (if (and (= missing :spread) (pos? live-w) (pos? total-w))
                (/ total-w live-w)
                1)]
    (into []
          (map-indexed (fn [i leg]
                         (if (live? i) (* v (:weight leg) scale) 0)))
          legs)))

(defn- net-index
  "Depositor's index after the leader's profit share: the share of every gain
  up to the gross index's high-water mark `peak` is owed, as on withdrawal."
  [gross peak fee-rate]
  (- gross (* fee-rate (max 0 (- peak 1)))))

(defn- step-leg
  "One period of a leg: gross factor `g` moves its gross `index` and `peak`.
  Returns [net-factor index peak]."
  [g index peak fee-rate]
  (let [gross (* index g)
        peak* (max peak gross)]
    (if (pos? fee-rate)
      (let [before (net-index index peak fee-rate)
            after (net-index gross peak* fee-rate)]
        [(if (pos? before) (/ after before) g) gross peak*])
      [g gross peak*])))

(defn run
  "Backtest the target. Input:

    :legs        [{:id :weight :rows [{:time-ms :close}] :fee-rate}]
    :start-ms    window start; :end-ms window end
    :rebalance   :daily | :monthly | :hold
    :missing     :spread | :cash | :common
    :profit-share? deduct each leg's :fee-rate on gains above its high-water mark

  Returns nil when no leg has history in the window, else:

    :rows         [[time-ms cumulative-percent]] (the vault tear-sheet shape)
    :live-counts  [[time-ms live-leg-count]]
    :joins        [{:id :time-ms}] legs that start inside the window
    :start-ms     effective start (later than asked under :common)
    :contribution {id fraction-of-start-value-added}
    :leg-count    number of legs"
  [{:keys [legs start-ms end-ms rebalance missing profit-share?]}]
  (let [legs (vec (filter #(and (number? (:weight %)) (not (zero? (:weight %)))) legs))
        rebalance (if (contains? rebalance-options rebalance) rebalance :daily)
        missing (if (contains? missing-options missing) missing :spread)
        grid0 (daily-grid start-ms end-ms)
        values0 (mapv #(leg-grid-values (:rows %) grid0) legs)
        common-start (when (= missing :common)
                       (first (filter (fn [i] (every? #(some? (nth % i)) values0))
                                      (range (count grid0)))))
        offset (if (= missing :common) common-start 0)]
    (when (and (seq legs) (seq grid0) (some? offset)
               (some (fn [vals] (some some? (subvec vals offset))) values0))
      (let [grid (subvec grid0 offset)
            values (mapv #(subvec % offset) values0)
            n (count grid)
            k (count legs)
            live? (fn [i] (fn [j] (some? (nth (nth values j) i))))
            fees (mapv (fn [leg] (if profit-share? (or (:fee-rate leg) 0) 0)) legs)]
        (loop [i 0
               holdings nil
               v 1
               idx (vec (repeat k 1))
               hwm (vec (repeat k 1))
               added (vec (repeat k 0))
               rows (transient [])
               lives (transient [])]
          (if (= i n)
            {:rows (persistent! rows)
             :live-counts (persistent! lives)
             :joins (->> values
                         (keep-indexed
                          (fn [j vals]
                            (let [first-live (first (keep-indexed (fn [i x] (when (some? x) i)) vals))]
                              (when (and first-live (pos? first-live))
                                {:id (:id (nth legs j)) :time-ms (nth grid first-live)}))))
                         (sort-by :time-ms)
                         vec)
             :start-ms (first grid)
             :contribution (into {} (map (fn [leg a] [(:id leg) a]) legs added))
             :leg-count k}
            (let [t (nth grid i)
                  live-now? (live? i)
                  live-before? (when (pos? i) (live? (dec i)))
                  ;; grow each holding over (i-1, i]
                  [holdings* idx* hwm* added*]
                  (if (nil? holdings)
                    [nil idx hwm added]
                    (reduce (fn [[h ix hw ad] j]
                              (if (and (live-before? j) (live-now? j))
                                (let [g (/ (nth (nth values j) i) (nth (nth values j) (dec i)))
                                      [f ix' hw'] (step-leg g (nth ix j) (nth hw j) (nth fees j))
                                      hj (nth h j)
                                      hj' (* hj f)]
                                  [(assoc h j hj') (assoc ix j ix') (assoc hw j hw') (update ad j + (- hj' hj))])
                                [h ix hw ad]))
                            [holdings idx hwm added]
                            (range k)))
                  v* (if holdings
                       (+ v (reduce + 0 (map - holdings* holdings)))
                       v)
                  joined? (and (pos? i) (some (fn [j] (and (live-now? j) (not (live-before? j)))) (range k)))
                  rebalance? (or (nil? holdings)
                                 joined?
                                 (= rebalance :daily)
                                 (and (= rebalance :monthly)
                                      (not= (month-key t) (month-key (nth grid (dec i))))))
                  holdings** (if rebalance?
                               (target-holdings legs live-now? v* missing)
                               holdings*)
                  live-count (count (filter live-now? (range k)))]
              (recur (inc i)
                     holdings**
                     v*
                     idx*
                     hwm*
                     added*
                     (conj! rows [t (* 100 (- v* 1))])
                     (conj! lives [t live-count])))))))))

(defn drawdown-rows
  "Drawdown (percent below the running peak) of cumulative-percent rows."
  [rows]
  (:out (reduce (fn [{:keys [peak out]} [t pct]]
                  (let [g (+ 1 (/ pct 100))
                        peak* (max peak g)]
                    {:peak peak*
                     :out (conj out [t (* 100 (- (/ g peak*) 1))])}))
                {:peak 1 :out []}
                rows)))
