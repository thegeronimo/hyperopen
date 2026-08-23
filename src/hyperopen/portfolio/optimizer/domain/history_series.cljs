(ns hyperopen.portfolio.optimizer.domain.history-series
  (:require [hyperopen.portfolio.metrics.history :as metrics-history]
            [hyperopen.portfolio.optimizer.domain.math :as math]
            [hyperopen.portfolio.optimizer.domain.return-plausibility :as plausibility]))

(def year-days
  365.2425)

(def ^:private sparse-density-threshold
  0.5)

(def ^:private sparse-median-dt-days-threshold
  3)

(def ^:private base-risk-estimation
  {:dense-block-calendar :daily
   :sparse-policy :pairwise-interval-aggregation
   :sparse-correlation-shrinkage true})

(defn- pair-return
  "Simple return implied by a [previous current] row pair, or nil when either
  close is unusable."
  [[previous current]]
  (let [previous-close (:close previous)
        current-close (:close current)]
    (when (and (math/finite-number? previous-close)
               (math/finite-number? current-close)
               (pos? previous-close)
               (pos? current-close))
      (- (/ current-close previous-close) 1))))

(defn- contaminated-pairs
  "Set of pair indices to discard because an implausible return implicates one
  of their prices.

  `simple-return-series` and `return-intervals` MUST discard exactly the same
  pairs: `domain.returns/interval-observations` guards with
  `(when (= (count intervals) (count series)) ...)` and, on a length mismatch,
  silently falls through to a different expected-return estimator with no
  warning. Both therefore call this."
  [pairs]
  (plausibility/contaminated-pair-indices (mapv pair-return pairs)))

(defn implausible-observations
  "[{:time-ms t :return r} ...] for every pair of `rows` whose implied return
  breaks the plausibility bound. This is the EVIDENCE - the bars that actually
  exceeded the bound - not the full discard set, which also covers the adjoining
  pairs that share a suspect price. Reported so a caller can name what happened
  instead of shortening the series silently."
  [rows]
  (->> (partition 2 1 rows)
       (keep (fn [[_ current :as pair]]
               (let [value (pair-return pair)]
                 (when (plausibility/implausible-return? value)
                   {:time-ms (:time-ms current)
                    :return value}))))
       vec))

(defn return-intervals
  [rows]
  (let [pairs (partition 2 1 rows)
        contaminated (contaminated-pairs pairs)]
    (->> pairs
         (keep-indexed (fn [idx pair]
                         (when-not (contaminated idx) pair)))
         (keep (fn [[previous current]]
                 (let [start-ms (:time-ms previous)
                       end-ms (:time-ms current)
                       dt-ms (when (and (number? start-ms)
                                        (number? end-ms))
                               (- end-ms start-ms))
                       dt-days (when (number? dt-ms)
                                 (/ dt-ms metrics-history/day-ms))
                       dt-years (when (math/finite-number? dt-days)
                                  (/ dt-days year-days))]
                   (when (and (math/finite-number? dt-years)
                              (pos? dt-years))
                     {:start-ms start-ms
                      :end-ms end-ms
                      :dt-days dt-days
                      :dt-years dt-years}))))
         vec)))

(defn simple-return-series
  [rows]
  (let [pairs (partition 2 1 rows)
        contaminated (contaminated-pairs pairs)]
    (->> pairs
         (keep-indexed (fn [idx pair] (when-not (contaminated idx) pair)))
         (keep pair-return)
         vec)))

(defn- expected-return-intervals?
  [intervals]
  (and (seq intervals)
       (<= 1 (reduce + 0 (map :dt-days intervals)))
       (every? (fn [{:keys [dt-days]}]
                 (and (math/finite-number? dt-days)
                      (pos? dt-days)))
               intervals)))

(defn- median
  [values]
  (when (seq values)
    (let [sorted (vec (sort values))
          n (count sorted)
          mid (quot n 2)]
      (if (odd? n)
        (nth sorted mid)
        (/ (+ (nth sorted (dec mid))
              (nth sorted mid))
           2)))))

(defn cadence-summary
  [rows]
  (let [rows* (vec rows)
        intervals (return-intervals rows*)
        interval-days (mapv :dt-days intervals)
        observations (count rows*)
        interval-count (count intervals)
        elapsed-days (when (> observations 1)
                       (/ (- (:time-ms (last rows*))
                             (:time-ms (first rows*)))
                          metrics-history/day-ms))
        density-vs-daily (cond
                           (and (math/finite-number? elapsed-days)
                                (pos? elapsed-days))
                           (/ interval-count elapsed-days)

                           (pos? interval-count)
                           1

                           :else
                           0)
        median-dt-days (median interval-days)
        max-dt-days (when (seq interval-days)
                      (apply max interval-days))
        sparse? (and (pos? interval-count)
                     (or (> (or median-dt-days 0)
                            sparse-median-dt-days-threshold)
                         (< density-vs-daily
                            sparse-density-threshold)))]
    {:observations observations
     :interval-count interval-count
     :elapsed-days elapsed-days
     :median-dt-days median-dt-days
     :max-dt-days max-dt-days
     :density-vs-daily density-vs-daily
     :sparse? sparse?
     :kind (if sparse? :sparse :dense)}))

(defn risk-estimation
  [cadence-by-instrument]
  (when (seq cadence-by-instrument)
    (assoc base-risk-estimation
           :kind (if (some (fn [[_ cadence]]
                             (:sparse? cadence))
                           cadence-by-instrument)
                   :mixed-frequency
                   :aligned-frequency))))

(defn native-history-metadata
  ([rows-by-instrument]
   (native-history-metadata rows-by-instrument nil))
  ([rows-by-instrument expected-rows-by-instrument]
   (let [raw-price-series-by-instrument (into {}
                                             (keep (fn [[instrument-id rows]]
                                                     (when (seq rows)
                                                       [instrument-id (vec rows)])))
                                             rows-by-instrument)
         cadence-by-instrument (into {}
                                     (map (fn [[instrument-id rows]]
                                            [instrument-id (cadence-summary rows)]))
                                     raw-price-series-by-instrument)
         expected-return-rows-by-instrument
         (into {}
               (map (fn [[instrument-id raw-rows]]
                      [instrument-id
                       (let [expected-rows (get expected-rows-by-instrument instrument-id)]
                         (if (seq expected-rows)
                           (vec expected-rows)
                           raw-rows))]))
               raw-price-series-by-instrument)
         expected-return-series-by-instrument
         (into {}
               (keep (fn [[instrument-id rows]]
                       (let [series (simple-return-series rows)
                             intervals (return-intervals rows)]
                         (when (and (seq series)
                                    (expected-return-intervals? intervals))
                           [instrument-id series]))))
               expected-return-rows-by-instrument)
         expected-return-intervals-by-instrument
         (into {}
               (keep (fn [[instrument-id rows]]
                       (let [intervals (return-intervals rows)]
                         (when (expected-return-intervals? intervals)
                           [instrument-id intervals]))))
               expected-return-rows-by-instrument)]
     {:raw-price-series-by-instrument raw-price-series-by-instrument
      :cadence-by-instrument cadence-by-instrument
     :expected-return-series-by-instrument expected-return-series-by-instrument
     :expected-return-intervals-by-instrument expected-return-intervals-by-instrument
     :risk-estimation (risk-estimation cadence-by-instrument)})))

(defn native-history-metadata-for-series
  [eligible-series]
  (native-history-metadata
   (into {}
         (keep (fn [{:keys [instrument-id series]}]
                 (when (seq (:points series))
                   [instrument-id (:points series)])))
         eligible-series)))
