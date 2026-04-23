(ns hyperopen.portfolio.optimizer.domain.returns
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]))

(def default-periods-per-year
  365)

(def default-ew-alpha
  0.25)

(defn- return-series
  [history instrument-id]
  (vec (get-in history [:return-series-by-instrument instrument-id])))

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
  [history]
  (sort (keys (:return-series-by-instrument history))))

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

(defn- return-component
  [return-model periods-per-year series]
  (let [kind (:kind return-model)]
    (* periods-per-year
       (case kind
         :ew-mean (or (ew-mean series (or (:alpha return-model) default-ew-alpha))
                      0)
         :historical-mean (or (math/mean series) 0)
         :black-litterman (or (math/mean series) 0)
         (or (math/mean series) 0)))))

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
                                                      series)
                        funding-part (funding-carry history instrument-id)
                        total (+ return-part funding-part)]
                    (-> acc
                        (update :instrument-ids conj instrument-id)
                        (assoc-in [:expected-returns-by-instrument instrument-id] total)
                        (assoc-in [:decomposition-by-instrument instrument-id]
                                  {:return-component return-part
                                   :funding-component funding-part
                                   :funding-source (funding-source history instrument-id)})))
                  (update acc :warnings conj
                          {:code :missing-return-series
                           :instrument-id instrument-id}))))
            {:model (:kind return-model*)
             :instrument-ids []
             :expected-returns-by-instrument {}
             :decomposition-by-instrument {}
             :warnings []}
            instrument-ids)))
