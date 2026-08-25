(ns hyperopen.portfolio.optimizer.domain.returns
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]))

(def default-periods-per-year
  365)

(def default-ew-alpha
  0.015159678336035098)

(def default-min-observations
  30)

(defn- return-series
  [history instrument-id]
  (vec (or (get-in history [:expected-return-series-by-instrument instrument-id])
           (get-in history [:return-series-by-instrument instrument-id]))))

(defn- expected-return-series?
  [history instrument-id]
  (contains? (or (:expected-return-series-by-instrument history) {})
             instrument-id))

(defn- return-intervals
  [history instrument-id]
  (vec (or (get-in history [:expected-return-intervals-by-instrument instrument-id])
           (:return-intervals history))))

(defn- funding-summary
  [history instrument-id]
  (get-in history [:funding-by-instrument instrument-id]))

(defn- funding-carry
  [history instrument-id]
  (or (:annualized-carry (funding-summary history instrument-id))
      0))

(defn- funding-source
  [history instrument-id]
  (or (:source (funding-summary history instrument-id))
      :missing))

(defn- sorted-instrument-ids
  "The expected-return universe. Must be the SAME union `domain.risk` uses
  (risk/sorted-instrument-ids), not the calendar-sampled map alone: an
  off-calendar sparse member has no `:return-series-by-instrument` entry by
  design, and `engine.context/expected-return-vector` coerces a missing expected
  return to 0 with no warning - a silent, invisible error. Identical to the old
  key set on every dense universe, because `:raw-price-series-by-instrument` is
  built from the same eligible members."
  [history]
  (->> [(keys (:return-series-by-instrument history))
        (keys (:raw-price-series-by-instrument history))]
       (apply concat)
       set
       sort))

(defn- ew-mean
  [values alpha]
  (let [n (count values)
        indexed (map-indexed vector values)
        weights (mapv (fn [[idx _]]
                        (js/Math.pow (- 1 alpha)
                                     (- (dec n) idx)))
                      indexed)
        total-weight (reduce + 0 weights)]
    (when (pos? total-weight)
      (/ (reduce + 0
                 (map (fn [[_ value] weight]
                        (* value weight))
                      indexed
                      weights))
         total-weight))))

(defn- interval-observations
  [history instrument-id series {:keys [allow-subdaily?]}]
  (let [intervals (return-intervals history instrument-id)]
    (when (= (count intervals) (count series))
      (let [observations (mapv (fn [simple-return {:keys [dt-days dt-years]}]
                                 (when (and (math/finite-number? simple-return)
                                            (> simple-return -1)
                                            (or (nil? dt-days)
                                                (and (math/finite-number? dt-days)
                                                     (if allow-subdaily?
                                                       (pos? dt-days)
                                                       (<= 1 dt-days))))
                                            (math/finite-number? dt-years)
                                            (pos? dt-years))
                                   {:dt-years dt-years
                                    :log-return (js/Math.log (+ 1 simple-return))
                                    :annualized-return (- (js/Math.exp (/ (js/Math.log (+ 1 simple-return))
                                                                          dt-years))
                                                          1)}))
                               series
                               intervals)]
        (when (and (every? some? observations)
                   (or allow-subdaily?
                       (some (fn [{:keys [dt-days]}]
                               (or (nil? dt-days)
                                   (> dt-days 1)))
                             intervals)))
          observations)))))

(defn- geometric-annualized-return
  [observations]
  (let [total-years (reduce + 0 (map :dt-years observations))]
    (when (pos? total-years)
      (- (js/Math.exp (/ (reduce + 0 (map :log-return observations))
                         total-years))
         1))))

(defn- return-component
  [return-model periods-per-year history instrument-id series]
  (let [kind (:kind return-model)
        observations (interval-observations
                      history
                      instrument-id
                      series
                      {:allow-subdaily?
                       (and (expected-return-series? history instrument-id)
                            (contains? #{:historical-mean :black-litterman}
                                       kind))})]
    (or (case kind
          :ew-mean (when observations
                     (ew-mean (mapv :annualized-return observations)
                              (or (:alpha return-model) default-ew-alpha)))
          :historical-mean (when observations
                             (geometric-annualized-return observations))
          :black-litterman (when observations
                             (geometric-annualized-return observations))
          nil)
        (* periods-per-year
           (case kind
             :ew-mean (or (ew-mean series (or (:alpha return-model) default-ew-alpha))
                          0)
             :historical-mean (or (math/mean series) 0)
             :black-litterman (or (math/mean series) 0)
             (or (math/mean series) 0))))))

(defn- sample-size-warning
  [instrument-id series]
  (when (< (count series) default-min-observations)
    {:code :low-return-sample-size
     :instrument-id instrument-id
     :observations (count series)
     :recommended-observations default-min-observations}))

(defn estimate-expected-returns
  [{:keys [return-model periods-per-year history]}]
  (let [return-model* (or return-model {:kind :historical-mean})
        periods-per-year* (or periods-per-year default-periods-per-year)
        instrument-ids (sorted-instrument-ids history)]
    (reduce (fn [acc instrument-id]
              (let [series (return-series history instrument-id)]
                (if (seq series)
                  (let [return-part (return-component return-model*
                                                      periods-per-year*
                                                      history
                                                      instrument-id
                                                      series)
                        ;; Hyperliquid convention: positive funding => longs PAY shorts
                        ;; (active_asset/funding_policy.cljs:178-187, :long -1). The
                        ;; expected-return vector is the per-unit LONG return (weights are
                        ;; signed, w·mu), so positive funding is a COST to a long and must be
                        ;; SUBTRACTED. :annualized-carry carries the raw HL sign (positive
                        ;; funding => positive carry), so negate it to get the long's carry
                        ;; contribution (negative when funding is positive). A short then
                        ;; earns it automatically via its negative weight.
                        funding-part (- (funding-carry history instrument-id))
                        total (+ return-part funding-part)
                        warning (sample-size-warning instrument-id series)]
                    (cond-> (-> acc
                                (update :instrument-ids conj instrument-id)
                                (assoc-in [:expected-returns-by-instrument instrument-id] total)
                                (assoc-in [:decomposition-by-instrument instrument-id]
                                          {:return-component return-part
                                           :funding-component funding-part
                                           :funding-source (funding-source history instrument-id)}))
                      warning
                      (update :warnings conj warning)))
                  (update acc :warnings conj
                          {:code :missing-return-series
                           :instrument-id instrument-id}))))
            {:model (:kind return-model*)
             :instrument-ids []
             :expected-returns-by-instrument {}
             :decomposition-by-instrument {}
             :warnings []}
            instrument-ids)))
